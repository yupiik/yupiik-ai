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
package io.yupiik.ia.agent.github.context;

import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One-shot host-side, read-only context staging for credential-free reviews, not an interactive API proxy.
 * The caller owns the client and must stage before exposing the attempt directory to an executor.
 */
@ApplicationScoped
public class GithubContextSnapshot {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PAGES = 10;
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}");
    private final GitHubClient github;
    private final JsonMapper json;

    public GithubContextSnapshot(final GitHubClient github, final JsonMapper json) {
        this.github = github;
        this.json = json;
    }

    public void stage(final TaskDocument task, final Path attempt) throws IOException {
        final var repo = task.repository();
        if (task.repository() == null
                || repo.length() > 140
                || !REPOSITORY.matcher(repo).matches()
                || repo.endsWith("/.")
                || repo.endsWith("/..")) {
            throw new IllegalArgumentException("Invalid GitHub repository");
        }
        final long number = number(task.number());
        final var workspace = attempt.toAbsolutePath().normalize().resolve("workspace");
        final var target = workspace.resolve("github-context.json");
        checkDestination(workspace, target);

        final var budget = new Budget();
        final Object snapshot;
        if (AssessmentBase.isAssessment(task)) {
            snapshot = assessment(task);
        } else {
            if (task.kind() != TaskKind.fix && !"pull_request".equals(task.reviewTarget())) {
                throw new IllegalArgumentException("Expected an explicit review target");
            }
            final var prefix = "/repos/" + repo + "/pulls/" + number;
            final var pull = budget.<ContextPull>read(prefix, ContextPull.class).body();
            if (pull == null || pull.head() == null || pull.base() == null) {
                throw new IllegalStateException("Expected GitHub context pull and refs");
            }
            snapshot = new Snapshot(
                    1,
                    repo,
                    number,
                    new Pull(
                            optionalText(pull.title()),
                            optionalText(pull.body()),
                            new Ref(optionalText(pull.head().sha())),
                            new Ref(optionalText(pull.base().sha()))),
                    pages(prefix + "/files", budget).stream()
                            .map(entry -> new ChangedFile(
                                    optionalText(entry.filename()),
                                    optionalText(entry.status()),
                                    optionalText(entry.patch())))
                            .toList(),
                    pages(prefix + "/reviews", budget).stream()
                            .map(entry -> new Review(
                                    optionalText(entry.body()),
                                    optionalText(entry.state()),
                                    optionalText(entry.commit_id())))
                            .toList(),
                    pages(prefix + "/comments", budget).stream()
                            .map(entry -> new Comment(
                                    optionalText(entry.path()),
                                    line(entry.line()),
                                    optionalText(entry.side()),
                                    optionalText(entry.body())))
                            .toList(),
                    pages("/repos/" + repo + "/issues/" + number + "/comments", budget).stream()
                            .map(entry -> new Conversation(optionalText(entry.body())))
                            .toList());
        }
        final var bytes = encoded(snapshot);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalStateException("GitHub context exceeds encoded JSON limit");
        }

        // All HTTP, parsing and limit checks complete before creating any snapshot file.
        checkDestination(workspace, target);
        boolean created = false;
        try {
            try (final var output = Files.newOutputStream(
                    target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                output.write(bytes);
            }
        } catch (final IOException e) {
            if (created) {
                try {
                    Files.deleteIfExists(target);
                } catch (final IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
    }

    private Map<String, Object> assessment(final TaskDocument task) {
        final var reader = new TaskDiscussionContext(github, task);
        final var discussion = "discussion".equals(task.reviewTarget());
        final var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", 1);
        result.put("repo", task.repository());
        result.put("number", task.number());
        result.put("reviewTarget", task.reviewTarget());
        final var base = AssessmentBase.pin(github, task);
        result.putAll(base.recovery());
        result.put("sourceStatus", base.emptyRepository() ? "EMPTY_REPOSITORY" : "PINNED");
        result.put(discussion ? "discussion" : "issue", reader.read(discussion ? "discussion" : "issue", 1, null));
        final var comments = new ArrayList<Object>();
        result.put(discussion ? "discussionComments" : "issueComments", comments);
        final var remaining = new int[] {MAX_PAGES};
        final var replies = new LinkedHashMap<String, Object>();
        if (discussion) {
            result.put("discussionReplies", replies);
            final var thread = task.replyToId() == null ? task.commentId() : task.replyToId();
            if (thread != null) {
                final var pages = new ArrayList<Object>();
                replies.put(thread, pages);
                if (!assessmentConnection(reader, "discussionReplies", thread, pages, result, remaining)) {
                    result.put("conversationComplete", false);
                    return result;
                }
            }
        }
        boolean complete = assessmentConnection(
                reader, discussion ? "discussionComments" : "issueComments", null, comments, result, remaining);
        if (discussion) {
            for (final var rawPage : comments) {
                for (final var rawEntry : (List<?>) object(rawPage).get("entries")) {
                    final var parent = String.valueOf(object(rawEntry).get("id"));
                    if (!replies.containsKey(parent)) {
                        final var pages = new ArrayList<Object>();
                        replies.put(parent, pages);
                        if (!assessmentConnection(reader, "discussionReplies", parent, pages, result, remaining)) {
                            complete = false;
                        }
                    }
                }
            }
        }
        result.put("conversationComplete", complete);
        return result;
    }

    private boolean assessmentConnection(
            final TaskDiscussionContext reader,
            final String operation,
            final String parent,
            final List<Object> pages,
            final Map<String, Object> result,
            final int[] remaining) {
        for (int page = 1; page <= MAX_PAGES && remaining[0] > 0; page++) {
            remaining[0]--;
            final var response = object(reader.read(operation, page, parent));
            pages.add(response);
            if (encoded(result).length > MAX_BYTES) {
                throw new IllegalStateException("GitHub context exceeds encoded JSON limit");
            }
            if (Boolean.TRUE.equals(response.get("complete"))) {
                return true;
            }
        }
        return false;
    }

    private List<RestResponses.ContextEntry> pages(final String endpoint, final Budget budget) {
        final var result = new ArrayList<RestResponses.ContextEntry>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            final var response = budget.<List<RestResponses.ContextEntry>>read(
                    endpoint + "?per_page=100" + (page == 1 ? "" : "&page=" + page),
                    new Types.ParameterizedTypeImpl(List.class, RestResponses.ContextEntry.class));
            if (response.body() == null || response.body().stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException("Expected GitHub context array");
            }
            result.addAll(response.body());
            if (response.next() == null) {
                return result;
            }
            if (page == MAX_PAGES) {
                throw new IllegalStateException("GitHub context exceeds pagination limit");
            }
            validateNext(response.next(), endpoint, page + 1);
        }
        throw new IllegalStateException("GitHub context exceeds pagination limit");
    }

    private static String optionalText(final Object value) {
        if (value != null && !(value instanceof String)) {
            throw new IllegalStateException("Invalid GitHub context field");
        }
        return (String) value;
    }

    private static BigDecimal line(final Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number)) {
            throw new IllegalStateException("Invalid GitHub context line");
        }
        return new BigDecimal(value.toString());
    }

    @JsonModel
    public record ContextPull(Object title, Object body, ContextRef head, ContextRef base) {}

    @JsonModel
    public record ContextRef(Object sha) {}

    private void validateNext(final String next, final String endpoint, final int page) {
        final var uri = URI.create(next);
        // Enterprise API prefixes are allowed, but links never become request targets.
        // Only the next sequential page of our locally constructed endpoint is requested.
        final var query = uri.getRawQuery();
        if (uri.getRawPath() == null
                || !uri.getRawPath().endsWith(endpoint)
                || uri.getRawPath().contains("%")
                || uri.getRawPath().contains("/../")
                || uri.getRawPath().contains("/./")
                || uri.getRawFragment() != null
                || !(("per_page=100&page=" + page).equals(query) || ("page=" + page + "&per_page=100").equals(query))) {
            throw new IllegalStateException("Invalid GitHub context pagination");
        }
    }

    private static long number(final Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("Expected positive integral GitHub number");
        }
        try {
            final long number = new BigDecimal(value.toString()).longValueExact();
            if (number > 0) {
                return number;
            }
        } catch (final NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Expected positive integral GitHub number", invalid);
        }
        throw new IllegalArgumentException("Expected positive integral GitHub number");
    }

    private static Map<String, Object> object(final Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Expected GitHub context object");
        }
        final var result = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> {
            if (key instanceof String name) {
                result.put(name, item);
            }
        });
        return result;
    }

    private static void checkDestination(final Path workspace, final Path target) throws IOException {
        Path current = workspace.getRoot();
        for (final var segment : workspace) {
            current = current.resolve(segment);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("GitHub context requires existing non-symbolic attempt directories");
            }
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("GitHub context destination already exists");
        }
    }

    private byte[] encoded(final Object value) {
        return json.toString(value).getBytes(StandardCharsets.UTF_8);
    }

    @JsonModel
    public record Snapshot(
            @Property(documentation = "Snapshot schema version.")
            int schemaVersion,

            @Property(documentation = "Upstream repository.")
            String repo,

            @Property(documentation = "Pull request number.")
            long number,

            @Property(documentation = "Whitelisted pull metadata and head/base SHAs.")
            Pull pull,

            @Property(documentation = "Changed files and available patches.")
            List<ChangedFile> files,

            @Property(documentation = "Submitted review metadata.")
            List<Review> reviews,

            @Property(documentation = "Inline review comments.")
            List<Comment> comments,

            @Property(documentation = "Issue conversation.") List<Conversation> issueComments) {}

    @JsonModel
    public record Ref(
            @Property(documentation = "Pinned commit SHA.") String sha) {}

    @JsonModel
    public record Pull(
            @Property(documentation = "Pull request title.") String title,
            @Property(documentation = "Pull request body.") String body,
            @Property(documentation = "Pinned head.") Ref head,
            @Property(documentation = "Pinned base.") Ref base) {}

    @JsonModel
    public record ChangedFile(
            @Property(documentation = "Changed path.") String filename,
            @Property(documentation = "Change status.") String status,

            @Property(documentation = "Available diff patch.")
            String patch) {}

    @JsonModel
    public record Review(
            @Property(documentation = "Review Markdown body.")
            String body,

            @Property(documentation = "Review state.") String state,

            @Property(documentation = "Reviewed commit SHA.")
            String commit_id) {}

    @JsonModel
    public record Comment(
            @Property(documentation = "Changed path.") String path,
            @Property(documentation = "Diff line.") BigDecimal line,
            @Property(documentation = "Diff side.") String side,
            @Property(documentation = "Comment body.") String body) {}

    @JsonModel
    public record Conversation(
            @Property(documentation = "Conversation body.") String body) {}

    private final class Budget {
        private long bytes;

        private <T> GitHubClient.Page<T> read(final String path, final Type type) {
            final var page = github.<T>page(path, type);
            bytes += page.responseBytes();
            if (bytes > MAX_BYTES) {
                throw new IllegalStateException("GitHub context exceeds encoded JSON limit");
            }
            return page;
        }
    }
}
