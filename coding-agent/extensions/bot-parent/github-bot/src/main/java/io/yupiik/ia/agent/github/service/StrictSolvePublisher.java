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

import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import io.yupiik.ia.agent.github.model.AgentResult;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.store.MutableSolvePublication;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Host-only publication of credential-free solve results. No POST is ever replayed.
 * An uncertain immutable-object or ref write requires operator recovery; an uncertain PR
 * can only be reconciled, never recreated. This class does not delete attempt data or results.
 * Requires a credential supporting GET /user, and complete (non-truncated) base trees.
 */
@ApplicationScoped
public class StrictSolvePublisher {
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}");
    private static final int FILE_LIMIT = 1024 * 1024;
    private static final String PUBLICATION = "strictSolvePublication";
    private final GitHubClient github;
    private final TaskStore store;
    private final JsonMapper json;

    public StrictSolvePublisher(final GitHubClient github, final TaskStore store, final JsonMapper json) {
        this.github = github;
        this.store = store;
        this.json = json;
    }

    /** Returns the verified upstream PR URL, or throws while retaining all recovery evidence. */
    public String publish(final TaskDocument taskDocument, final AgentResult agentResult, final String forkOwner) {
        final var task = taskDocument;
        if (forkOwner == null || !OWNER.matcher(forkOwner).matches()) {
            throw new IllegalArgumentException("Invalid fork owner");
        }
        final var id = task.id();
        if (!NAME.matcher(id).matches() || id.contains("..") || id.endsWith(".lock")) {
            throw new IllegalArgumentException("Invalid task identifier");
        }
        // Authority is reloaded from durable host state, never taken from the agent or stale caller.
        final var current = store.transaction(state -> state.task(id).document());
        final var upstream = current.repository();
        final var parts = upstream.split("/", -1);
        if (parts.length != 2
                || !OWNER.matcher(parts[0]).matches()
                || !NAME.matcher(parts[1]).matches()) {
            throw new IllegalArgumentException("Invalid upstream repository");
        }
        final var base = sha(current.strictSolveBaseSha());
        final var baseBranch = current.strictSolveBaseBranch();
        validateBranch(baseBranch);
        if (!upstream.equals(task.repository())
                || !base.equals(task.strictSolveBaseSha())
                || !baseBranch.equals(task.strictSolveBaseBranch())) {
            throw new IllegalStateException("Caller does not match persisted base authority");
        }
        if (!forkOwner.equals(current.strictSolveForkOwner())) {
            throw new IllegalStateException("Restore the pinned strict solve fork owner");
        }
        if (agentResult.strictSolve() == null) {
            throw new IllegalArgumentException("Expected structured strict solve changes");
        }
        final var solve = agentResult.strictSolve();
        if (!base.equals(sha(solve.baseSha()))) {
            throw new IllegalArgumentException("Agent base does not match host base");
        }
        final var title = solve.title();
        final var body = solve.body();
        if (title == null
                || body == null
                || title.isBlank()
                || title.length() > 256
                || title.indexOf('\n') >= 0
                || title.indexOf('\r') >= 0
                || body.length() > 60000) {
            throw new IllegalArgumentException("Invalid pull request title or body");
        }
        final var changes = validateChanges(solve.changes());
        final var branch = "github-bot/" + id;
        final var fork = forkOwner + "/" + parts[1];
        final var marker = "<!-- yca-task:" + id + " -->";
        final var renderedBody = PullRequestRenderer.render(agentResult, marker);
        final var fingerprint = fingerprint(base, title, renderedBody, changes);
        final var binding = new TaskDocument.SolvePublication(
                forkOwner,
                upstream,
                base,
                baseBranch,
                branch,
                fingerprint,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of());
        final var publication = store.transaction(state -> {
            final var saved = state.task(id);
            if (!Objects.equals(saved.strictSolveBaseSha(), base)
                    || !Objects.equals(saved.strictSolveBaseBranch(), baseBranch)
                    || !Objects.equals(saved.repository(), upstream)) {
                throw new IllegalStateException("Base authority changed during publication");
            }
            if (saved.strictSolvePublication() == null) {
                saved.strictSolvePublication(binding);
                saved.result(agentResult);
            }
            final var existing = saved.strictSolvePublication();
            if (!Objects.equals(binding.forkOwner(), existing.forkOwner())
                    || !Objects.equals(binding.upstream(), existing.upstream())
                    || !Objects.equals(binding.baseSha(), existing.baseSha())
                    || !Objects.equals(binding.baseBranch(), existing.baseBranch())
                    || !Objects.equals(binding.branch(), existing.branch())
                    || !Objects.equals(binding.fingerprint(), existing.fingerprint())) {
                throw new IllegalStateException("Publication target or result changed");
            }
            return existing;
        });
        if (publication.prUrl() != null) {
            return publication.prUrl();
        }
        // PR reconciliation must remain possible after the default branch has moved.
        if (publication.prIntent() != null) {
            return reconcile(id, upstream, fork, branch, baseBranch, marker, publication, forkOwner);
        }
        verifyTarget(upstream, fork, baseBranch, base);
        final var author = github.get("/user", RestResponses.User.class).login();
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("Missing login");
        }
        update(id, saved -> {
            final var previous = saved.author();
            if (previous == null) {
                saved.author(author);
            }
            if (previous != null && !author.equals(previous)) {
                throw new IllegalStateException("Publication credential identity changed");
            }
            return null;
        });
        final var commit = github.get("/repos/" + upstream + "/git/commits/" + base, RestResponses.Commit.class);
        if (!base.equals(sha(commit.sha()))) {
            throw new IllegalStateException("Unexpected base commit");
        }
        final var baseTree = sha(commit.tree().sha());
        final var modes = new PinnedTreeChanges(github, "/repos/" + upstream)
                .modes(
                        baseTree,
                        changes.stream().map(Change::path).toList(),
                        changes.stream()
                                .filter(Change::delete)
                                .map(Change::path)
                                .collect(toSet()));
        // Check the actual mapper's null policy before creating any immutable objects.
        if (changes.stream().anyMatch(Change::delete)
                && !json.toString(new GitHubClient.TreeRequest(
                                baseTree, List.of(new GitHubClient.TreeEntry("probe", "100644", "blob", null))))
                        .contains("\"sha\":null")) {
            throw new IllegalStateException("Tree deletion requires a JSON mapper configured to serialize nulls");
        }
        final var entries = new ArrayList<GitHubClient.TreeEntry>();
        for (int i = 0; i < changes.size(); i++) {
            final var change = changes.get(i);
            final var blob = change.delete()
                    ? null
                    : object(
                            id,
                            "blob-" + i,
                            "/repos/" + fork + "/git/blobs",
                            new GitHubClient.BlobRequest(change.content(), "base64"));
            entries.add(new GitHubClient.TreeEntry(
                    change.path(), modes.getOrDefault(change.path(), "100644"), "blob", blob));
        }
        final var request = new GitHubClient.TreeRequest(baseTree, entries);
        final var treeSha = object(id, "tree", "/repos/" + fork + "/git/trees", request);
        final var commitSha = object(
                id,
                "commit",
                "/repos/" + fork + "/git/commits",
                new GitHubClient.CommitRequest(title, treeSha, List.of(base)));
        if (!Boolean.TRUE.equals(update(id, saved -> saved.refDone()))) {
            // Recheck immediately before making the commit reachable; never update or force a ref.
            verifyTarget(upstream, fork, baseBranch, base);
            intent(id, "refIntent");
            final var ref = github.post(
                    "/repos/" + fork + "/git/refs",
                    new GitHubClient.RefRequest("refs/heads/" + branch, commitSha),
                    RestResponses.Ref.class);
            if (!("refs/heads/" + branch).equals(ref.ref())
                    || !commitSha.equals(sha(ref.object().sha()))) {
                throw new IllegalStateException("Unexpected created reference");
            }
            update(id, saved -> {
                saved.refDone(true);
                return null;
            });
        }
        final var live = github.get("/repos/" + fork + "/git/ref/heads/" + branch, RestResponses.Ref.class);
        if (!("refs/heads/" + branch).equals(live.ref())
                || !commitSha.equals(sha(live.object().sha()))) {
            throw new IllegalStateException("Published head changed");
        }
        verifyTarget(upstream, fork, baseBranch, base);
        intent(id, "prIntent");
        final var pr = github.post(
                "/repos/" + upstream + "/pulls",
                new GitHubClient.PullRequestRequest(
                        title, renderedBody, forkOwner + ":" + branch, parts[1], baseBranch, false),
                RestResponses.Pull.class);
        return complete(
                id, pr, upstream, fork, branch, baseBranch, marker, update(id, MutableSolvePublication::document));
    }

    private void verifyTarget(final String upstream, final String fork, final String branch, final String base) {
        final var target = github.get("/repos/" + fork, RestResponses.Repository.class);
        if (!Boolean.TRUE.equals(target.fork())
                || !fork.equals(target.full_name())
                || target.parent() == null
                || !upstream.equals(target.parent().full_name())) {
            throw new IllegalStateException("Fork parent does not match upstream");
        }
        final var repo = github.get("/repos/" + upstream, RestResponses.Repository.class);
        if (!upstream.equals(repo.full_name()) || !branch.equals(repo.default_branch())) {
            throw new IllegalStateException("Upstream default branch changed");
        }
        final var ref = github.get("/repos/" + upstream + "/git/ref/heads/" + encode(branch), RestResponses.Ref.class);
        if (!("refs/heads/" + branch).equals(ref.ref())
                || !base.equals(sha(ref.object().sha()))) {
            throw new IllegalStateException("Stale strict solve base");
        }
    }

    private String object(
            final String id, final String stage, final String endpoint, final GitHubClient.MutationRequest request) {
        final var previous = update(id, saved -> saved.objectSha(stage));
        if (previous != null) {
            return sha(previous);
        }
        intent(id, stage + "Intent");
        final var value =
                sha(github.post(endpoint, request, RestResponses.ObjectId.class).sha());
        update(id, saved -> {
            saved.objectSha(stage, value);
            return null;
        });
        return value;
    }

    private void intent(final String id, final String key) {
        update(id, saved -> {
            saved.claim(key);
            return null;
        });
    }

    private <T> T update(final String id, final Function<MutableSolvePublication, T> operation) {
        return store.transaction(state -> {
            final var task = state.task(id);
            final var publication = new MutableSolvePublication(task.strictSolvePublication());
            final var result = operation.apply(publication);
            task.strictSolvePublication(publication.document());
            return result;
        });
    }

    private String reconcile(
            final String id,
            final String upstream,
            final String fork,
            final String branch,
            final String baseBranch,
            final String marker,
            final TaskDocument.SolvePublication publication,
            final String forkOwner) {
        String next = "/repos/" + upstream + "/pulls?state=all&head=" + encode(forkOwner + ":" + branch) + "&base="
                + encode(baseBranch) + "&per_page=100";
        final var visited = new HashSet<String>();
        RestResponses.Pull found = null;
        while (next != null) {
            if (!visited.add(next) || visited.size() > 100) {
                throw new IllegalStateException("Unbounded pull request reconciliation");
            }
            final var page = github.<List<RestResponses.Pull>>page(
                    next, new Types.ParameterizedTypeImpl(List.class, RestResponses.Pull.class));
            final var pulls = page.body();
            if (pulls == null) {
                throw new IllegalStateException("Expected pull request list");
            }
            for (final var pr : pulls) {
                if (matches(pr, upstream, fork, branch, baseBranch, marker, publication)) {
                    if (found != null) {
                        throw new IllegalStateException("Multiple matching pull requests; operator recovery required");
                    }
                    found = pr;
                }
            }
            next = page.next();
        }
        if (found == null) {
            throw new IllegalStateException("Uncertain PR creation; no verified match, refusing another POST");
        }
        return complete(id, found, upstream, fork, branch, baseBranch, marker, publication);
    }

    private String complete(
            final String id,
            final RestResponses.Pull pr,
            final String upstream,
            final String fork,
            final String branch,
            final String baseBranch,
            final String marker,
            final TaskDocument.SolvePublication publication) {
        if (!matches(pr, upstream, fork, branch, baseBranch, marker, publication)) {
            throw new IllegalStateException("Pull request does not match publication intent");
        }
        final var url = pr.html_url();
        if (url == null
                || !url.startsWith("https://github.com/" + upstream + "/pull/")
                || !(pr.number() instanceof Number number)
                || number.longValue() <= 0
                || !url.equals("https://github.com/" + upstream + "/pull/" + number.longValue())) {
            throw new IllegalStateException("Unexpected pull request URL");
        }
        update(id, saved -> {
            saved.prUrl(url);
            return null;
        });
        return url;
    }

    private boolean matches(
            final RestResponses.Pull pr,
            final String upstream,
            final String fork,
            final String branch,
            final String baseBranch,
            final String marker,
            final TaskDocument.SolvePublication publication) {
        if (pr == null || pr.head() == null || pr.base() == null || pr.user() == null || pr.body() == null) {
            return false;
        }
        final var head = pr.head();
        final var base = pr.base();
        return branch.equals(head.ref())
                && publication.commitSha().equals(head.sha())
                && head.repo() != null
                && fork.equals(head.repo().full_name())
                && baseBranch.equals(base.ref())
                && base.repo() != null
                && upstream.equals(base.repo().full_name())
                && publication.author().equals(pr.user().login())
                && pr.body().lines().anyMatch(marker::equals);
    }

    private List<Change> validateChanges(final List<AgentResult.Change> values) {
        if (values == null || values.isEmpty() || values.size() > 100) {
            throw new IllegalArgumentException("Expected 1 to 100 changes");
        }
        final var paths = new HashSet<String>();
        final var result = new ArrayList<Change>();
        long total = 0;
        for (final var value : values) {
            final var change = value;
            if (change == null || (change.unknown() != null && !change.unknown().isEmpty())) {
                throw new IllegalArgumentException("Unknown change property (modes and symlinks are forbidden)");
            }
            final var path = change.path();
            validatePath(path);
            if (!paths.add(path) || change.delete() == null) {
                throw new IllegalArgumentException("Duplicate path or invalid delete flag");
            }
            final boolean delete = change.delete();
            final var content = delete && change.content() == null ? "" : change.content();
            if (content == null) {
                throw new IllegalArgumentException("Missing change contents");
            }
            if (content.length() > 4 * ((FILE_LIMIT + 2) / 3)) {
                throw new IllegalArgumentException("File too large");
            }
            final var bytes = Base64.getDecoder().decode(content);
            if (bytes.length > FILE_LIMIT
                    || (total += bytes.length) > 4L * FILE_LIMIT
                    || !Base64.getEncoder().encodeToString(bytes).equals(content)
                    || (delete && !content.isEmpty())) {
                throw new IllegalArgumentException("Invalid or oversized base64 contents");
            }
            result.add(new Change(path, content, delete));
        }
        for (final var path : paths) {
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                if (paths.contains(path.substring(0, slash))) {
                    throw new IllegalArgumentException("Overlapping change paths");
                }
            }
        }
        return result;
    }

    private static void validatePath(final String path) {
        if (path == null
                || path.toLowerCase(Locale.ROOT).equals(".github/workflows")
                || path.toLowerCase(Locale.ROOT).startsWith(".github/workflows/")
                || path.isEmpty()
                || path.length() > 1024
                || path.startsWith("/")
                || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0
                || path.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("Unsafe change path");
        }
        for (final var segment : path.split("/", -1)) {
            if (segment.isEmpty()
                    || segment.equals(".")
                    || segment.equals("..")
                    || segment.equalsIgnoreCase(".git")
                    || segment.endsWith(".")
                    || segment.endsWith(" ")
                    || segment.indexOf('~') >= 0) {
                throw new IllegalArgumentException("Unsafe change path segment");
            }
        }
    }

    private static void validateBranch(final String branch) {
        validatePath(branch);
        if (branch.contains("..")
                || branch.contains("@{")
                || branch.endsWith(".lock")
                || branch.chars().anyMatch(c -> " ~^:?*[".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("Unsafe base branch");
        }
    }

    private static String fingerprint(
            final String base, final String title, final String body, final List<Change> changes) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            for (final var value : List.of(base, title, body)) {
                hashField(digest, value);
            }
            for (final var change : changes) {
                hashField(digest, change.path());
                hashField(digest, change.content());
                hashField(digest, Boolean.toString(change.delete()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void hashField(final MessageDigest digest, final String value) {
        final var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static String sha(final Object value) {
        if (!(value instanceof String text) || !SHA.matcher(text).matches()) {
            throw new IllegalArgumentException("Expected full SHA-1");
        }
        return text;
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record Change(String path, String content, boolean delete) {}
}
