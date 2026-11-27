/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.github.service;

import static java.util.stream.Collectors.toSet;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.model.AgentResult;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.store.MutableTask;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Host-only existing-PR publication. Recovery maps are durable, versioned host evidence.
 * Every POST is claimed in a short store transaction before transport, and never replayed.
 * An object intent without an acknowledged SHA requires manual recovery. A CAS intent can
 * only be reconciled by reads. This service never removes results or attempt artifacts.
 * Deletions require this service and GitHubClient to share a null-serializing mapper.
 */
@ApplicationScoped
public class FixPublisher {
    private static final Pattern REPOSITORY_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");

    private static final int FILE_LIMIT = 1024 * 1024;
    private static final String UPDATE_REFS =
            "mutation Fix($input:UpdateRefsInput!) {" + " updateRefs(input:$input) { clientMutationId } }";
    private final GitHubClient github;
    private final TaskStore store;
    private final JsonMapper json;
    private final FixRequestAuthority requests;

    public FixPublisher(
            final GitHubClient github,
            final TaskStore store,
            final JsonMapper json,
            final FixConfiguration configuration) {
        this.github = github;
        this.store = store;
        this.json = json;
        this.requests = new FixRequestAuthority(github, configuration);
    }

    /** Resolves an authorized host request before enqueue; the draft need not have an ID. */
    public Map<String, Object> pinRequest(final TaskDocument draft) {
        return requests.pinRequest(draft);
    }

    /** Pins once before execution; a retry never substitutes a new head for the saved SHA. */
    public Target prepare(final TaskDocument task) {
        final var current = authority(task);
        final var saved = recovery(current, "fixTarget");
        if (saved != null) {
            final var target = target(saved);
            verify(current, target, true);
            return target;
        }
        final var target = target(pinRequest(current));
        return store.transaction(state -> {
            final var mutable = state.task(current.id());
            binding(current, mutable.document());
            final var previous = recovery(mutable.document(), "fixTarget");
            if (previous != null) {
                if (!target(previous).equals(target)) {
                    throw new IllegalStateException("Fix target was already pinned; never repin a moving head");
                }
                return target(previous);
            }
            putRecovery(mutable, "fixTarget", targetMap(target));
            return target;
        });
    }

    /** Uses StrictSolve.baseSha as the expected PR head, title as commit message; body is ignored. */
    public String publish(final TaskDocument task, final AgentResult result) {
        final var current = authority(task);
        final var pinned = recovery(current, "fixTarget");
        if (pinned == null) {
            throw new IllegalStateException("prepare must durably pin the fix target before execution");
        }
        final var target = target(pinned);
        requests.policy(target.repository());
        if (result == null || result.strictSolve() == null || !current.id().equals(result.taskId())) {
            throw new IllegalArgumentException("Expected correlated structured fix result");
        }
        final var solve = result.strictSolve();
        if (!target.headSha().equals(sha(solve.baseSha()))) {
            throw new IllegalArgumentException("Fix base must equal pinned PR head");
        }
        final var title = solve.title();
        if (title == null
                || title.isBlank()
                || title.length() > 256
                || title.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("Invalid fix commit message");
        }
        final var changes = changes(solve.changes());
        final var fingerprint = fingerprint(target, title, changes);
        final var publication = journal(current, target, fingerprint, result);
        if (publication.containsKey("cas")) {
            return reconcile(current, target, publication);
        }
        verify(current, target, true);
        final var commit = github.get(prefix(target) + "/git/commits/" + target.headSha(), RestResponses.Commit.class);
        if (!target.headSha().equals(sha(commit.sha()))) {
            throw new IllegalStateException("Unexpected pinned commit");
        }
        final var baseTree = sha(commit.tree().sha());
        final var modes = new PinnedTreeChanges(github, prefix(target))
                .modes(
                        baseTree,
                        changes.stream().map(Change::path).toList(),
                        changes.stream()
                                .filter(Change::delete)
                                .map(Change::path)
                                .collect(toSet()));
        // TreeEntry uses the shared transport mapper: omission is not a deletion in GitHub's API.
        if (changes.stream().anyMatch(Change::delete)
                && !json.toString(new GitHubClient.TreeRequest(
                                baseTree, List.of(new GitHubClient.TreeEntry("probe", "100644", "blob", null))))
                        .contains("\"sha\":null")) {
            throw new IllegalStateException("Fix deletion requires a JSON mapper configured to serialize nulls");
        }
        final var entries = new ArrayList<GitHubClient.TreeEntry>();
        for (int i = 0; i < changes.size(); i++) {
            final var change = changes.get(i);
            final var blob = change.delete()
                    ? null
                    : object(
                            current,
                            target,
                            fingerprint,
                            "blob-" + i,
                            prefix(target) + "/git/blobs",
                            new GitHubClient.BlobRequest(change.content(), "base64"));
            entries.add(new GitHubClient.TreeEntry(
                    change.path(), modes.getOrDefault(change.path(), "100644"), "blob", blob));
        }
        final var tree = object(
                current,
                target,
                fingerprint,
                "tree",
                prefix(target) + "/git/trees",
                new GitHubClient.TreeRequest(baseTree, entries));
        final var newCommit = object(
                current,
                target,
                fingerprint,
                "commit",
                prefix(target) + "/git/commits",
                new GitHubClient.CommitRequest(title, tree, List.of(target.headSha())));
        // Check PR identity/open state and exact ref immediately before the server-side atomic CAS.
        verify(current, target, true);
        final var input = Map.<String, Object>of(
                "repositoryId",
                target.repositoryId(),
                "refUpdates",
                List.of(Map.of(
                        "name",
                        "refs/heads/" + target.branch(),
                        "beforeOid",
                        target.headSha(),
                        "afterOid",
                        newCommit,
                        "force",
                        false)),
                "clientMutationId",
                fingerprint);
        final var request = new GitHubClient.GraphqlRequest(UPDATE_REFS, Map.of("input", input));
        claim(current, target, fingerprint, "cas", "/graphql", request);
        // All responses (including errors or transport failure) leave the intent claimed forever.
        // Acknowledgement alone is insufficient: always read back the authoritative ref.
        try {
            github.graphql(UPDATE_REFS, Map.of("input", input), UpdateRefsEnvelope.class);
        } catch (final RuntimeException uncertain) {
            return reconcile(current, target, publication(current.id()));
        }
        return reconcile(current, target, publication(current.id()));
    }

    private TaskDocument authority(final TaskDocument supplied) {
        if (supplied == null || supplied.id() == null) {
            throw new IllegalArgumentException("Missing fix task");
        }
        final var saved = store.transaction(state -> state.task(supplied.id()).document());
        binding(supplied, saved);
        requests.validateTask(saved);
        return saved;
    }

    private static void binding(final TaskDocument expected, final TaskDocument actual) {
        if (!Objects.equals(expected.id(), actual.id())
                || !Objects.equals(expected.repository(), actual.repository())
                || !Objects.equals(expected.number(), actual.number())
                || expected.kind() != actual.kind()
                || !Objects.equals(expected.sourceType(), actual.sourceType())
                || !Objects.equals(expected.discussionId(), actual.discussionId())) {
            throw new IllegalStateException("Fix caller does not match durable task binding");
        }
    }

    private String verify(final TaskDocument task, final Target target, final boolean exactHead) {
        return requests.verify(task, target, exactHead);
    }

    private String reconcile(final TaskDocument task, final Target target, final Map<String, Object> publication) {
        final var newCommit = sha(TaskStore.map(publication.get("commit")).get("sha"));
        final var actual = verify(task, target, false);
        if (!newCommit.equals(actual)) {
            final var comparison = github.get(
                    prefix(target) + "/compare/" + newCommit + "..." + actual, RestResponses.Comparison.class);
            if (!"ahead".equals(comparison.status())
                    || !newCommit.equals(sha(comparison.merge_base_commit().sha()))
                    || !newCommit.equals(sha(comparison.base_commit().sha()))) {
                throw new IllegalStateException(
                        "Uncertain fix CAS: no incorporation proof; manual recovery required, no replay");
            }
        }
        edit(task, target, text(publication, "fingerprint"), saved -> {
            saved.put("url", target.url());
            saved.put("observedHead", actual);
            return null;
        });
        return target.url();
    }

    private Map<String, Object> journal(
            final TaskDocument task, final Target target, final String fingerprint, final AgentResult result) {
        return store.transaction(state -> {
            final var saved = state.task(task.id());
            binding(task, saved.document());
            requireTarget(saved.document(), target);
            final var previous = recovery(saved.document(), "fixPublication");
            if (previous != null) {
                if (!fingerprint.equals(previous.get("fingerprint"))
                        || !targetMap(target).equals(previous.get("target"))) {
                    throw new IllegalStateException("Fix publication result or target changed");
                }
                return previous;
            }
            final var initial = new LinkedHashMap<String, Object>();
            initial.put("version", 1);
            initial.put("target", targetMap(target));
            initial.put("fingerprint", fingerprint);
            saved.result(result);
            putRecovery(saved, "fixPublication", initial);
            return initial;
        });
    }

    private Map<String, Object> publication(final String id) {
        return store.transaction(state -> recovery(state.task(id).document(), "fixPublication"));
    }

    private <T> T edit(
            final TaskDocument task,
            final Target target,
            final String fingerprint,
            final Function<Map<String, Object>, T> operation) {
        return store.transaction(state -> {
            final var saved = state.task(task.id());
            binding(task, saved.document());
            requireTarget(saved.document(), target);
            final var publication =
                    new LinkedHashMap<>(Objects.requireNonNull(recovery(saved.document(), "fixPublication")));
            if (!fingerprint.equals(publication.get("fingerprint"))
                    || !targetMap(target).equals(publication.get("target"))) {
                throw new IllegalStateException("Fix journal binding changed");
            }
            final var value = operation.apply(publication);
            putRecovery(saved, "fixPublication", publication);
            return value;
        });
    }

    private void claim(
            final TaskDocument task,
            final Target target,
            final String fingerprint,
            final String stage,
            final String path,
            final GitHubClient.MutationRequest request) {
        edit(task, target, fingerprint, publication -> {
            if (publication.containsKey(stage) || (!stage.equals("cas") && publication.containsKey("cas"))) {
                throw new IllegalStateException("Fix write already claimed; no replay: " + stage);
            }
            publication.put(stage, Map.of("intent", true, "path", path, "request", journalRequest(request)));
            return null;
        });
    }

    private Object journalRequest(final GitHubClient.MutationRequest request) {
        // The store mapper can omit null model fields. Deletion evidence must still contain sha:null,
        // exactly like the null-serializing transport, without a serialize/parse roundtrip.
        if (request instanceof GitHubClient.TreeRequest tree
                && tree.tree().stream().anyMatch(entry -> entry.sha() == null)) {
            return Map.of(
                    "base_tree", tree.base_tree(),
                    "tree",
                            tree.tree().stream()
                                    .map(entry -> {
                                        final var value = new LinkedHashMap<String, Object>();
                                        value.put("path", entry.path());
                                        value.put("mode", entry.mode());
                                        value.put("type", entry.type());
                                        value.put("sha", entry.sha());
                                        return value;
                                    })
                                    .toList());
        }
        return request;
    }

    private String object(
            final TaskDocument task,
            final Target target,
            final String fingerprint,
            final String stage,
            final String path,
            final GitHubClient.MutationRequest request) {
        final var prior = publication(task.id()).get(stage);
        if (prior != null) {
            final var value = TaskStore.map(prior);
            if (value.containsKey("sha")) {
                return sha(value.get("sha"));
            }
            throw new IllegalStateException("Uncertain fix object requires manual recovery; no replay: " + stage);
        }
        claim(task, target, fingerprint, stage, path, request);
        final String value;
        try {
            value = sha(github.post(path, request, RestResponses.ObjectId.class).sha());
        } catch (final RuntimeException uncertain) {
            throw new IllegalStateException(
                    "Uncertain fix object requires manual recovery; no replay: " + stage, uncertain);
        }
        edit(task, target, fingerprint, publication -> {
            final var acknowledged = new LinkedHashMap<>(TaskStore.map(publication.get(stage)));
            acknowledged.put("sha", value);
            publication.put(stage, acknowledged);
            return null;
        });
        return value;
    }

    private static List<Change> changes(final List<AgentResult.Change> changes) {
        if (changes == null || changes.isEmpty() || changes.size() > 100) {
            throw new IllegalArgumentException("Expected 1 to 100 fix changes");
        }
        final var paths = new HashSet<String>();
        final var result = new ArrayList<Change>();
        long total = 0;
        for (final var change : changes) {
            if (change == null
                    || (change.unknown() != null && !change.unknown().isEmpty())
                    || change.delete() == null) {
                throw new IllegalArgumentException("Unknown fix attributes or missing delete flag");
            }
            path(change.path());
            if (!paths.add(change.path())) {
                throw new IllegalArgumentException("Duplicate fix path");
            }
            final var content = change.delete() && change.content() == null ? "" : change.content();
            if (content == null || content.length() > 4 * ((FILE_LIMIT + 2) / 3)) {
                throw new IllegalArgumentException("Missing or oversized fix contents");
            }
            final var bytes = Base64.getDecoder().decode(content);
            if (bytes.length > FILE_LIMIT
                    || (total += bytes.length) > 4L * FILE_LIMIT
                    || !Base64.getEncoder().encodeToString(bytes).equals(content)
                    || (change.delete() && !content.isEmpty())) {
                throw new IllegalArgumentException("Invalid canonical base64 fix contents");
            }
            result.add(new Change(change.path(), content, change.delete()));
        }
        for (final var value : paths) {
            for (int slash = value.indexOf('/'); slash >= 0; slash = value.indexOf('/', slash + 1)) {
                if (paths.contains(value.substring(0, slash))) {
                    throw new IllegalArgumentException("Overlapping fix paths");
                }
            }
        }
        return result;
    }

    private static void path(final String value) {
        if (value == null
                || value.isEmpty()
                || value.length() > 1024
                || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0
                || value.chars().anyMatch(c -> c < 32 || c == 127)
                || value.toLowerCase(Locale.ROOT).equals(".github/workflows")
                || value.toLowerCase(Locale.ROOT).startsWith(".github/workflows/")) {
            throw new IllegalArgumentException("Unsafe fix path");
        }
        for (final var segment : value.split("/", -1)) {
            if (segment.isEmpty()
                    || segment.equals(".")
                    || segment.equals("..")
                    || segment.equalsIgnoreCase(".git")
                    || segment.endsWith(".")
                    || segment.endsWith(" ")
                    || segment.contains("~")) {
                throw new IllegalArgumentException("Unsafe fix path segment");
            }
        }
    }

    static String branch(final String value) {
        path(value);
        if (value.contains("..") || value.contains("@{") || value.chars().anyMatch(c -> " ~^:?*[".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("Unsafe fix branch");
        }
        for (final var segment : value.split("/")) {
            if (segment.startsWith(".") || segment.endsWith(".lock")) {
                throw new IllegalArgumentException("Unsafe fix branch segment");
            }
        }
        return value;
    }

    static String repository(final String value) {
        if (value == null
                || !REPOSITORY_NAME.matcher(value).matches()
                || value.endsWith("/.")
                || value.endsWith("/..")) {
            throw new IllegalArgumentException("Invalid fix repository");
        }
        return value;
    }

    private String fingerprint(final Target target, final String title, final List<Change> changes) {
        // JSON arrays provide unambiguous boundaries; body and all other agent fields have no authority.
        final var fields = new ArrayList<Object>();
        fields.add(targetMap(target));
        fields.add(title);
        for (final var change : changes) {
            fields.add(List.of(change.path(), change.content(), change.delete()));
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(json.toString(fields).getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireTarget(final TaskDocument task, final Target target) {
        if (!targetMap(target).equals(recovery(task, "fixTarget"))) {
            throw new IllegalStateException("Durable fix target changed");
        }
    }

    private static Map<String, Object> recovery(final TaskDocument task, final String key) {
        return task.recovery() == null || !task.recovery().containsKey(key)
                ? null
                : TaskStore.map(task.recovery().get(key));
    }

    private static void putRecovery(final MutableTask task, final String key, final Map<String, Object> value) {
        final var recovery =
                task.recovery() == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(task.recovery());
        recovery.put(key, value);
        task.recovery(recovery);
    }

    static Map<String, Object> targetMap(final Target target) {
        final var map = new LinkedHashMap<String, Object>();
        map.put("version", "1");
        map.put("repositoryId", target.repositoryId());
        map.put("repository", target.repository());
        map.put("branch", target.branch());
        map.put("headSha", target.headSha());
        map.put("upstreamId", target.upstreamId());
        map.put("upstream", target.upstream());
        map.put("number", Long.toString(target.number()));
        map.put("pullRequestId", target.pullRequestId());
        map.put("url", target.url());
        return map;
    }

    static Target target(final Map<String, Object> map) {
        if (!"1".equals(map.get("version"))) {
            throw new IllegalStateException("Unsupported fix target version");
        }
        return new Target(
                text(map, "repositoryId"),
                repository(text(map, "repository")),
                branch(text(map, "branch")),
                sha(map.get("headSha")),
                text(map, "upstreamId"),
                repository(text(map, "upstream")),
                Long.parseLong(text(map, "number")),
                text(map, "pullRequestId"),
                text(map, "url"));
    }

    static String text(final Map<String, Object> map, final String key) {
        if (!(map.get(key) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("Expected nonempty string: " + key);
        }
        return value;
    }

    static String sha(final Object value) {
        if (!(value instanceof String text) || !COMMIT_SHA.matcher(text).matches()) {
            throw new IllegalArgumentException("Expected full lowercase SHA-1");
        }
        return text;
    }

    static String prefix(final Target target) {
        return "/repos/" + target.repository();
    }

    public record Target(
            String repositoryId,
            String repository,
            String branch,
            String headSha,
            String upstreamId,
            String upstream,
            long number,
            String pullRequestId,
            String url) {}

    private record Change(String path, String content, boolean delete) {}

    // The fixed updateRefs operation selects only clientMutationId, never repository data.
    @JsonModel
    public record UpdateRefsEnvelope(UpdateRefsData data) {}

    @JsonModel
    public record UpdateRefsData(UpdateRefsResult updateRefs) {}

    @JsonModel
    public record UpdateRefsResult(String clientMutationId) {}
}
