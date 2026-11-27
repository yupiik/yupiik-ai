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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real loopback transport and DiscussionClient codecs, with no external GitHub or global properties. */
class TaskDiscussionContextTest {
    private static final String ROOT = "https://github.com/owner/repo/discussions/12";

    @Test
    void advisoryDiscussionSnapshotNeverUsesRestEvenWithIssueSourceType(@TempDir final Path temporary)
            throws Exception {
        try (final var fixture = new Fixture()) {
            final var task = TaskFixtures.task(Map.of(
                    "id",
                    "assessment",
                    "repository",
                    "owner/repo",
                    "number",
                    12,
                    "kind",
                    "review",
                    "reviewTarget",
                    "discussion",
                    "sourceType",
                    "issue",
                    "discussionId",
                    "D1",
                    "assessmentBaseBranch",
                    "main",
                    "assessmentEmptyRepository",
                    true));
            Files.createDirectory(temporary.resolve("workspace"));
            fixture.connection = variables ->
                    connection("D1".equals(variables.get("id")) ? List.of(comment("C1", "D1", null)) : List.of(), null);
            new GithubContextSnapshot(fixture.github, fixture.json).stage(task, temporary);
            final var snapshot = map(fixture.json.fromString(
                    Object.class, Files.readString(temporary.resolve("workspace/github-context.json"))));
            assertTrue(snapshot.containsKey("discussion"));
            assertTrue(snapshot.containsKey("discussionComments"));
            assertFalse(snapshot.containsKey("issue"));
            assertFalse(snapshot.containsKey("pull"));
            assertFalse(snapshot.toString().contains("raw-secret"));
            assertNull(new GithubPrivateContext(fixture.github, fixture.json).stage(task, temporary));
            assertTrue(fixture.paths.stream().allMatch(it -> it.equals("POST /graphql")));
        }
    }

    @Test
    void explicitIssueReviewRejectsPrOperationsEvenWithPullReference() throws Exception {
        try (final var fixture = new Fixture()) {
            final var task = TaskFixtures.task(Map.of(
                    "repository",
                    "owner/repo",
                    "number",
                    12,
                    "kind",
                    "review",
                    "reviewTarget",
                    "issue",
                    "reference",
                    "https://github.com/owner/repo/pull/12"));
            final var context = new TaskDiscussionContext(fixture.github, task);
            for (final var operation : List.of("pull", "files", "reviews", "reviewComments", "discussion")) {
                assertThrows(IllegalArgumentException.class, () -> context.read(operation, 1, null));
            }
            assertTrue(fixture.paths.isEmpty());
        }
    }

    @Test
    void domainDenialsNeverRequestAnIssueWithACollidingDiscussionNumber() throws Exception {
        try (final var fixture = new Fixture()) {
            final var discussion = fixture.context("discussion", "review", ROOT);
            for (final var operation :
                    List.of("issue", "pull", "files", "reviews", "reviewComments", "issueComments")) {
                assertThrows(IllegalArgumentException.class, () -> discussion.read(operation, 1, null));
            }
            final var issue = fixture.context("issue", "solve", "https://github.com/owner/repo/issues/12");
            for (final var operation : List.of(
                    "pull",
                    "files",
                    "reviews",
                    "reviewComments",
                    "discussion",
                    "discussionComments",
                    "discussionReplies")) {
                assertThrows(IllegalArgumentException.class, () -> issue.read(operation, 1, null));
            }
            assertThrows(IllegalArgumentException.class, () -> discussion.read("discussionReplies", 1, "foreign-node"));
            assertThrows(IllegalArgumentException.class, () -> discussion.read("discussionComments", 2, null));
            assertThrows(IllegalArgumentException.class, () -> discussion.read("discussion", 2, null));
            assertThrows(IllegalArgumentException.class, () -> issue.read("issue", 2, null));
            assertThrows(IllegalArgumentException.class, () -> discussion.read("discussionComments", 11, null));
            assertThrows(IllegalArgumentException.class, () -> discussion.read("discussionComments", 1, "cursor"));
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void fixedIssueAndPullRoutesUseOnlyAllowlistedFields() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.rest = Map.of(
                    "id",
                    42,
                    "node_id",
                    "I1",
                    "title",
                    "Untrusted title",
                    "body",
                    "https://evil.invalid/secret",
                    "state",
                    "open",
                    "credentials",
                    "do-not-return",
                    "url",
                    "https://evil.invalid/");
            final var issue = fixture.context("issue", "solve", "https://evil.invalid/owner/repo/issues/12");
            final var result = map(issue.read("issue", 1, null));
            assertEquals(5, result.size());
            assertEquals("https://evil.invalid/secret", result.get("body"));
            assertEquals("GET /repos/owner/repo/issues/12", fixture.paths.getFirst());
            fixture.rest = List.of(Map.of("id", 1, "body", "comment", "credentials", "hidden"));
            final var comments = map(issue.read("issueComments", 1, null));
            assertFalse(comments.toString().contains("credentials"));
            assertEquals("GET /repos/owner/repo/issues/12/comments?per_page=100&page=1", fixture.paths.getLast());
            for (final var kind : List.of("review", "fix", "solve")) {
                final var pull = fixture.context("issue", kind, "https://github.com/owner/repo/pull/12#issuecomment-1");
                pull.read("files", 1, null);
                assertEquals("GET /repos/owner/repo/pulls/12/files?per_page=100&page=1", fixture.paths.getLast());
            }
            final var notPull = fixture.context("issue", "solve", "https://github.com/another/repo/pull/12");
            assertThrows(IllegalArgumentException.class, () -> notPull.read("files", 1, null));
        }
    }

    @Test
    void discussionMustMatchAllThreeFixedIdentities() throws Exception {
        try (final var fixture = new Fixture()) {
            for (final var field : List.of("id", "repository", "number")) {
                fixture.discussion = discussion();
                fixture.discussion.put(
                        field,
                        switch (field) {
                            case "id" -> "foreign";
                            case "repository" -> Map.of("nameWithOwner", "other/repo");
                            default -> 99;
                        });
                assertThrows(
                        RuntimeException.class,
                        () -> fixture.context("discussion", "solve", ROOT).read("discussion", 1, null));
            }
            fixture.discussion = discussion();
            final var value = map(fixture.context("discussion", "solve", ROOT).read("discussion", 1, null));
            assertEquals("D1", value.get("id"));
            assertFalse(value.containsKey("url"));
            assertFalse(value.toString().contains("raw-secret"));
        }
    }

    @Test
    void cursorsAreServerSideAndRepliesRequireObservedFixedParents() throws Exception {
        try (final var fixture = new Fixture()) {
            final var context = fixture.context("discussion", "solve", ROOT);
            fixture.connection = variables -> connection(List.of(comment("C1", "D1", null)), "opaque cursor");
            final var first = map(context.read("discussionComments", 1, null));
            assertEquals(false, first.get("complete"));
            assertFalse(first.toString().contains("opaque cursor"));
            assertFalse(first.toString().contains("https://github.com"));
            assertEquals("D1", fixture.variables().get("id"));
            final var before = fixture.requests.size();
            assertThrows(IllegalArgumentException.class, () -> context.read("discussionReplies", 1, "C-other-repo"));
            assertEquals(before, fixture.requests.size());
            fixture.connection = variables -> connection(List.of(comment("C2", "D1", null)), null);
            assertEquals(true, map(context.read("discussionComments", 2, null)).get("complete"));
            assertEquals("opaque cursor", fixture.variables().get("after"));
            assertThrows(IllegalArgumentException.class, () -> context.read("discussionComments", 3, null));
            fixture.connection = variables -> connection(List.of(comment("R1", "D1", "C1")), null);
            final var replies = map(context.read("discussionReplies", 1, "C1"));
            assertEquals("C1", fixture.variables().get("id"));
            assertEquals(
                    "R1", map(((List<?>) replies.get("entries")).getFirst()).get("id"));
            assertThrows(IllegalArgumentException.class, () -> context.read("discussionReplies", 1, "R1"));
            final var otherContext = fixture.context("discussion", "solve", ROOT);
            assertThrows(IllegalArgumentException.class, () -> otherContext.read("discussionReplies", 1, "C1"));
        }
    }

    @Test
    void malformedPagesDoNotObserveParentsOrLoseContinuation() throws Exception {
        try (final var fixture = new Fixture()) {
            final var context = fixture.context("discussion", "solve", ROOT);
            fixture.connection = variables -> connection(List.of(comment("C1", "D1", null)), "next-1");
            context.read("discussionComments", 1, null);
            fixture.connection = variables -> connection(List.of(comment("C2", "other-discussion", null)), "next-2");
            assertThrows(IllegalStateException.class, () -> context.read("discussionComments", 2, null));
            final var before = fixture.requests.size();
            assertThrows(IllegalArgumentException.class, () -> context.read("discussionReplies", 1, "C2"));
            assertThrows(IllegalArgumentException.class, () -> context.read("discussionComments", 3, null));
            assertEquals(before, fixture.requests.size());
            fixture.connection = variables -> connection(List.of(comment("C2", "D1", "wrong-parent")), "next-2");
            assertThrows(IllegalStateException.class, () -> context.read("discussionComments", 2, null));
            fixture.connection = variables -> connection(List.of(comment("C2", "D1", null)), null);
            context.read("discussionComments", 2, null);
            assertEquals("next-1", fixture.variables().get("after"));
            fixture.connection = variables -> connection(List.of(comment("R1", "D1", "C2")), null);
            assertThrows(IllegalStateException.class, () -> context.read("discussionReplies", 1, "C1"));
            fixture.connection = variables -> connection(List.of(comment("R1", "D1", "C1")), null);
            assertEquals(true, map(context.read("discussionReplies", 1, "C1")).get("complete"));
        }
    }

    @Test
    void malformedGraphqlAndOversizedBodiesFailRatherThanReturningEmptyData() throws Exception {
        try (final var fixture = new Fixture()) {
            final var context = fixture.context("discussion", "solve", ROOT);
            fixture.connection = variables -> Map.of("nodes", List.of()); // missing pageInfo
            assertThrows(RuntimeException.class, () -> context.read("discussionComments", 1, null));
            fixture.connection = variables -> connection(List.of(), "non-progressing");
            assertThrows(RuntimeException.class, () -> context.read("discussionComments", 1, null));
            fixture.connection = variables -> {
                final var node = comment("huge", "D1", null);
                node.put("body", "x".repeat(512 * 1024 + 1));
                return connection(List.of(node), "next");
            };
            assertThrows(IllegalStateException.class, () -> context.read("discussionComments", 1, null));
            assertThrows(IllegalArgumentException.class, () -> context.read("discussionReplies", 1, "huge"));
            fixture.connection = variables -> {
                final var nodes = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < 51; i++) {
                    nodes.add(comment("C" + i, "D1", null));
                }
                return connection(nodes, null);
            };
            assertThrows(RuntimeException.class, () -> context.read("discussionComments", 1, null));
            fixture.connection = variables -> connection(List.of(comment("C1", "D1", null)), null);
            assertEquals(true, map(context.read("discussionComments", 1, null)).get("complete"));
        }
    }

    @Test
    void eachConnectionStopsAtTenPagesAndRejectsCursorCycles() throws Exception {
        try (final var fixture = new Fixture()) {
            final var context = fixture.context("discussion", "solve", ROOT);
            for (int page = 1; page <= 10; page++) {
                final var current = page;
                fixture.connection =
                        variables -> connection(List.of(comment("C" + current, "D1", null)), "cursor-" + current);
                final var result = map(context.read("discussionComments", page, null));
                assertEquals(page == 10, result.get("pageLimitReached"));
                assertEquals(false, result.get("complete"));
            }
            final var before = fixture.requests.size();
            assertThrows(IllegalArgumentException.class, () -> context.read("discussionComments", 11, null));
            assertEquals(before, fixture.requests.size());
            fixture.connection = variables -> connection(List.of(comment("R1", "D1", "C1")), "reply-next");
            context.read("discussionReplies", 1, "C1");
            fixture.connection = variables -> connection(List.of(comment("R2", "D1", "C1")), "reply-next");
            assertThrows(RuntimeException.class, () -> context.read("discussionReplies", 2, "C1"));
        }
    }

    @Test
    void partialGraphqlErrorsPreserveTheLastSuccessfulCursor() throws Exception {
        try (final var fixture = new Fixture()) {
            final var context = fixture.context("discussion", "solve", ROOT);
            fixture.connection = variables -> connection(List.of(comment("C1", "D1", null)), "opaque-next");
            context.read("discussionComments", 1, null);
            fixture.partialErrors = true;
            assertThrows(IllegalStateException.class, () -> context.read("discussionComments", 2, null));
            assertThrows(IllegalArgumentException.class, () -> context.read("discussionComments", 3, null));
            fixture.partialErrors = false;
            fixture.connection = variables -> connection(List.of(), null);
            assertEquals(true, map(context.read("discussionComments", 2, null)).get("complete"));
            assertEquals("opaque-next", fixture.variables().get("after"));
        }
    }

    @Test
    void frozenInvocationThreadIsValidatedBeforeItCanSeedReplies() throws Exception {
        try (final var fixture = new Fixture()) {
            final var task = TaskFixtures.task(Map.of(
                    "repository",
                    "owner/repo",
                    "number",
                    12,
                    "kind",
                    "solve",
                    "sourceType",
                    "discussion",
                    "discussionId",
                    "D1",
                    "commentId",
                    "R0",
                    "replyToId",
                    "C0"));
            fixture.nodes.put("C0", comment("C0", "D1", null));
            fixture.nodes.put("R0", comment("R0", "other-discussion", "C0"));
            final var context = new TaskDiscussionContext(fixture.github, task);
            assertThrows(IllegalStateException.class, () -> context.read("discussionReplies", 1, "C0"));
            assertTrue(fixture.requests.stream()
                    .noneMatch(it -> String.valueOf(it.get("query")).startsWith("query DiscussionReplies(")));
            fixture.nodes.put("R0", comment("R0", "D1", "C0"));
            fixture.connection = variables -> connection(List.of(comment("R1", "D1", "C0")), null);
            assertEquals(true, map(context.read("discussionReplies", 1, "C0")).get("complete"));
            assertEquals("C0", fixture.variables().get("id"));
            final var before = fixture.requests.size();
            assertThrows(IllegalArgumentException.class, () -> context.read("discussionReplies", 1, "R0"));
            assertEquals(before, fixture.requests.size());
        }
    }

    @Test
    void aggregateResponseBoundAndReferenceValidationDoNotCommitPages() throws Exception {
        try (final var fixture = new Fixture()) {
            final var context = fixture.context("discussion", "solve", ROOT);
            fixture.connection = variables -> {
                final var nodes = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < 3; i++) {
                    final var node = comment("C" + i, "D1", null);
                    node.put("body", "x".repeat(300000));
                    nodes.add(node);
                }
                return connection(nodes, "next");
            };
            assertThrows(IllegalStateException.class, () -> context.read("discussionComments", 1, null));
            assertThrows(IllegalArgumentException.class, () -> context.read("discussionComments", 2, null));
            fixture.connection = variables -> {
                final var node = comment("C1", "D1", null);
                node.put("url", "https://evil.invalid/owner/repo/discussions/12#discussioncomment-42");
                return connection(List.of(node), null);
            };
            assertThrows(IllegalStateException.class, () -> context.read("discussionComments", 1, null));
            assertThrows(IllegalArgumentException.class, () -> context.read("discussionReplies", 1, "C1"));
            fixture.connection = variables -> connection(List.of(), null);
            assertEquals(true, map(context.read("discussionComments", 1, null)).get("complete"));
        }
    }

    @Test
    void parallelParentsKeepTheirOwnConnections() throws Exception {
        try (final var fixture = new Fixture();
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var context = fixture.context("discussion", "solve", ROOT);
            fixture.connection =
                    variables -> connection(List.of(comment("C1", "D1", null), comment("C2", "D1", null)), null);
            context.read("discussionComments", 1, null);
            fixture.connection = variables -> {
                final var id = (String) variables.get("id");
                return connection(
                        List.of(comment("R-" + id, "D1", id)), variables.get("after") == null ? "cursor-" + id : null);
            };
            final var one = executor.submit(() -> context.read("discussionReplies", 1, "C1"));
            final var two = executor.submit(() -> context.read("discussionReplies", 1, "C2"));
            one.get();
            two.get();
            assertEquals(true, map(context.read("discussionReplies", 2, "C1")).get("complete"));
            assertEquals("cursor-C1", fixture.variables().get("after"));
            assertEquals(true, map(context.read("discussionReplies", 2, "C2")).get("complete"));
            assertEquals("cursor-C2", fixture.variables().get("after"));
        }
    }

    private static Map<?, ?> map(final Object value) {
        return (Map<?, ?>) value;
    }

    private static Map<String, Object> discussion() {
        return new LinkedHashMap<>(Map.of(
                "id",
                "D1",
                "number",
                12,
                "title",
                "Topic",
                "body",
                "Untrusted body",
                "updatedAt",
                "2026-11-15T00:00:00Z",
                "url",
                ROOT,
                "repository",
                Map.of("nameWithOwner", "owner/repo"),
                "raw",
                "raw-secret"));
    }

    private static Map<String, Object> comment(final String id, final String discussionId, final String replyToId) {
        final var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("databaseId", 42);
        result.put("body", "Untrusted comment https://evil.invalid/no-fetch");
        result.put("updatedAt", "2026-11-15T00:00:00Z");
        result.put("url", ROOT + "#discussioncomment-42");
        result.put("discussion", Map.of("id", discussionId));
        if (replyToId != null) {
            result.put("replyTo", Map.of("id", replyToId));
        }
        result.put(
                "author",
                Map.of("__typename", "User", "login", "alice", "databaseId", 123, "credentials", "raw-secret"));
        return result;
    }

    private static Map<String, Object> connection(final List<?> nodes, final String next) {
        final var info = new LinkedHashMap<String, Object>();
        info.put("hasNextPage", next != null);
        info.put("endCursor", next);
        return Map.of("nodes", nodes, "pageInfo", info);
    }

    private static final class Fixture implements AutoCloseable {
        private final JsonMapper json = BotJsonMapper.create(ignored -> Optional.empty());
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final List<Map<?, ?>> requests = new CopyOnWriteArrayList<>();
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final GitHubClient github;
        private Map<String, Object> discussion = discussion();
        private final Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        private Function<Map<?, ?>, Map<String, Object>> connection = variables -> connection(List.of(), null);
        private Object rest = Map.of();
        private boolean partialErrors;

        private Fixture() throws Exception {
            server.createContext("/", exchange -> {
                try (exchange) {
                    paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    final Object response;
                    if ("/graphql".equals(exchange.getRequestURI().getPath())) {
                        final var request = map(json.fromString(
                                Object.class,
                                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                        requests.add(request);
                        final var variables = map(request.get("variables"));
                        final var query = (String) request.get("query");
                        if (query.startsWith("query DiscussionRead(")) {
                            response = Map.of("data", Map.of("node", discussion));
                        } else if (query.startsWith("query DiscussionCommentRead(")) {
                            response = Map.of("data", Map.of("node", nodes.get(variables.get("id"))));
                        } else {
                            final var field = query.startsWith("query DiscussionReplies(") ? "replies" : "comments";
                            response = Map.of(
                                    "data",
                                    Map.of(
                                            "node",
                                            Map.of("id", variables.get("id"), field, connection.apply(variables))));
                        }
                    } else {
                        requests.add(Map.of("rest", exchange.getRequestURI().toString()));
                        response = rest;
                    }
                    final var bytes = json.toString(
                                    partialErrors
                                            ? Map.of(
                                                    "data",
                                                    Map.of(),
                                                    "errors",
                                                    List.of(Map.of("message", "untrusted diagnostics")))
                                            : response)
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            github = new GitHubClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    json,
                    () -> "test-credential",
                    Clock.systemUTC());
        }

        private TaskDiscussionContext context(final String source, final String kind, final String reference) {
            return new TaskDiscussionContext(
                    github,
                    TaskFixtures.task(Map.of(
                            "repository",
                            "owner/repo",
                            "number",
                            12,
                            "kind",
                            kind,
                            "sourceType",
                            source,
                            "discussionId",
                            "D1",
                            "reference",
                            reference)));
        }

        private Map<?, ?> variables() {
            return map(requests.getLast().get("variables"));
        }

        @Override
        public void close() {
            github.close();
            server.stop(0);
            json.close();
        }
    }
}
