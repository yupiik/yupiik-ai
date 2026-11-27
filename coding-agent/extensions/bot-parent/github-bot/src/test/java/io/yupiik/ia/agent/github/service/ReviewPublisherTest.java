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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.configuration.ReviewPresentationConfiguration;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewPublisherTest {
    private ReviewPresentationConfiguration presentation;

    private static final String PATCH =
            "@@ -10,3 +20,3 @@ method\n context\n-old\n+new\n tail\n\\ No newline at end of file\n"
                    + "@@ -50 +60 @@\n-before\n+after\n";
    private static final String URL = "https://github.com/owner/repo/pull/12#pullrequestreview-1";

    @TempDir
    Path home;

    private JsonMapper json;
    private HttpServer server;
    private GitHubClient github;
    private TaskStore store;
    private Map<String, Object> task;
    private final List<Map<String, Object>> posts = new CopyOnWriteArrayList<>();
    private final AtomicInteger heads = new AtomicInteger();
    private volatile List<Map<String, Object>> reviews = List.of();
    private volatile String initialSha = "head";
    private volatile String finalSha = "head";
    private volatile int postStatus = 200;
    private volatile boolean intentAtPost;

    @BeforeEach
    void setUp(@Fusion final ReviewPresentationConfiguration presentation) throws Exception {
        this.presentation = presentation;
        json = BotJsonMapper.create(key -> Optional.empty());
        store = new TaskStore(BotTestConfiguration.home(home), json);
        final var id = store.transaction(state -> state.enqueue(
                "review:1", TaskFixtures.task(Map.of("repository", "owner/repo", "number", 12, "kind", "review"))));
        task = TaskFixtures.map(store.transaction(state -> state.task(id).document()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final var path = exchange.getRequestURI().getPath();
            final Object response;
            int status = 200;
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.add(TaskStore.map(json.fromString(
                        Object.class, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))));
                intentAtPost = store.transaction(state -> (state.task(id).reviewIntent() != null));
                status = postStatus;
                response = Map.of("html_url", URL);
            } else if (path.endsWith("/reviews")) {
                response = reviews;
            } else if (path.endsWith("/files")) {
                response =
                        List.of(Map.of("filename", "src/App.java", "patch", PATCH), Map.of("filename", "binary.png"));
            } else if (path.equals("/repos/owner/repo/pulls/12")) {
                response = Map.of("head", Map.of("sha", heads.incrementAndGet() == 1 ? initialSha : finalSha));
            } else {
                status = 404;
                response = Map.of();
            }
            final var bytes = json.toString(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        github = new GitHubClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                json,
                () -> "test",
                BotTestConfiguration.CLOCK);
    }

    @AfterEach
    void tearDown() {
        if (github != null) {
            github.close();
        }
        if (server != null) {
            server.stop(0);
        }
        if (json != null) {
            json.close();
        }
    }

    @Nested
    @FusionSupport(modules = GithubConfigurationTestModule.class)
    class Defaults {
        @Test
        void publishesNativeCommentReviewPinnedToValidatedHead() {
            final var comments = List.of(finding("LEFT", 11), finding("RIGHT", 21), finding("RIGHT", 60));
            assertEquals(URL, publisher().publish(TaskFixtures.task(task), TaskFixtures.result(result(comments))));
            assertEquals(2, heads.get());
            assertTrue(intentAtPost);
            assertEquals(1, posts.size());
            final var posted = posts.getFirst();
            assertEquals("head", posted.get("commit_id"));
            assertEquals("COMMENT", posted.get("event"));
            final var body = String.valueOf(posted.get("body"));
            assertTrue(body.startsWith(marker() + "\n## \uD83D\uDCCB Summary\n\nFindings\n"));
            assertEquals(1, occurrences(body, marker()));
            assertEquals(1, occurrences(body, "## \uD83D\uDD0E Findings"));
            assertTrue(body.contains("3 finding(s) posted as inline review comments."));
            assertTrue(body.contains("No test evidence was reported."));
            assertFalse(body.contains("Explain the defect"));
            final var actual = (List<?>) posted.get("comments");
            assertEquals(3, actual.size());
            for (int i = 0; i < comments.size(); i++) {
                final var comment = TaskStore.map(actual.get(i));
                assertEquals(comments.get(i).get("path"), comment.get("path"));
                assertEquals(comments.get(i).get("side"), comment.get("side"));
                assertEquals(
                        ((Number) comments.get(i).get("line")).intValue(), ((Number) comment.get("line")).intValue());
                final var inline = String.valueOf(comment.get("body"));
                assertEquals(
                        marker() + "\n### " + (i + 1) + ". `src/App.java:"
                                + comments.get(i).get("line") + "` ("
                                + comments.get(i).get("side")
                                + ")\n\nExplain the defect",
                        inline);
                assertEquals(1, occurrences(inline, marker()));
                assertTrue(new MentionParser()
                        .parse(String.valueOf(comment.get("body")), "bot")
                        .isEmpty());
            }
        }

        @Test
        void defaultReportIncludesActualTestEvidenceWithoutClaimingSuccess() {
            final var tests = List.of("mvn test: FAILED (2 failures)", "integration tests: NOT RUN");
            final var result = Map.<String, Object>of(
                    "summary",
                    "Summary fallback with **Markdown** & <literal> {{notATemplate}}",
                    "review",
                    Map.of("headSha", "head", "comments", List.of()),
                    "tests",
                    tests);
            publisher().publish(TaskFixtures.task(task), TaskFixtures.result(result));
            final var body = String.valueOf(posts.getFirst().get("body"));
            assertEquals(1, occurrences(body, marker()));
            assertEquals(1, occurrences(body, "## \uD83D\uDCCB Summary"));
            assertEquals(1, occurrences(body, "## \uD83E\uDDEA Tests"));
            assertTrue(body.contains("Summary fallback with **Markdown** & <literal> {{notATemplate}}"));
            assertTrue(body.contains("No inline findings were reported."));
            assertTrue(body.contains("Agent-reported test evidence (not independently verified by the host):"));
            for (final var test : tests) {
                assertEquals(1, occurrences(body, test));
            }
            assertFalse(body.contains("passed"));
            assertFalse(body.contains("&amp;"));
            assertFalse(body.contains("&lt;"));
        }

        @Test
        void defaultReportHonestlyHandlesMissingSummaryFindingsAndTests() {
            publisher()
                    .publish(TaskFixtures.task(task), TaskFixtures.result(Map.of("review", Map.of("headSha", "head"))));
            final var body = String.valueOf(posts.getFirst().get("body"));
            assertTrue(body.contains("No review summary was provided."));
            assertTrue(body.contains("No inline findings were reported."));
            assertTrue(body.contains("No test evidence was reported."));
            assertFalse(body.contains("passed"));
            assertFalse(body.contains("Review completed."));
        }

        @Test
        void rejectsOversizedRenderedTemplatesBeforeWritingIntent() {
            final var defaults = presentation;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> publisher(new ReviewPresentationConfiguration("x".repeat(30001), defaults.findingTemplate()))
                            .publish(TaskFixtures.task(task), TaskFixtures.result(result(List.of()))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> publisher(new ReviewPresentationConfiguration(defaults.reportTemplate(), "x".repeat(10001)))
                            .publish(
                                    TaskFixtures.task(task),
                                    TaskFixtures.result(result(List.of(finding("RIGHT", 21))))));
            assertTrue(posts.isEmpty());
            assertFalse(store.<Boolean>transaction(state -> state.task(id()).reviewIntent() != null));
        }

        @Test
        void mapsBothSidesContextAndMultipleHunks() {
            assertEquals(
                    Set.of(
                            "a:LEFT:10",
                            "a:RIGHT:20",
                            "a:LEFT:11",
                            "a:RIGHT:21",
                            "a:LEFT:12",
                            "a:RIGHT:22",
                            "a:LEFT:50",
                            "a:RIGHT:60"),
                    ReviewPublisher.locations("a", PATCH));
        }

        @Test
        void rejectsEntireReviewBeforePostWhenAnyFindingIsInvalid() {
            final var invalid = List.of(
                    finding("RIGHT", 999),
                    finding("RIGHT", 21.5),
                    finding("LEFT", 21),
                    Map.<String, Object>of("path", "binary.png", "side", "RIGHT", "line", 1, "body", "No patch"),
                    Map.<String, Object>of("path", "../App.java", "side", "RIGHT", "line", 21, "body", "Wrong path"),
                    Map.<String, Object>of("path", "src/App.java", "side", "RIGHT", "line", 21, "body", " "));
            for (final var finding : invalid) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> publisher()
                                .publish(
                                        TaskFixtures.task(task),
                                        TaskFixtures.result(result(List.of(finding("RIGHT", 21), finding)))));
            }
            assertTrue(posts.isEmpty());
            assertFalse(store.<Boolean>transaction(state -> (state.task(id()).reviewIntent() != null)));
        }

        @Test
        void rejectsUnboundedReviewPayloadsWithoutWritingIntent() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> publisher()
                            .publish(
                                    TaskFixtures.task(task),
                                    TaskFixtures.result(result(Collections.nCopies(31, finding("RIGHT", 21))))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> publisher()
                            .publish(
                                    TaskFixtures.task(task),
                                    TaskFixtures.result(
                                            Map.of("review", Map.of("headSha", "head", "body", "x".repeat(30001))))));
            final var oversizedFinding = Map.<String, Object>of(
                    "path", "src/App.java", "side", "RIGHT", "line", 21, "body", "x".repeat(10001));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> publisher()
                            .publish(TaskFixtures.task(task), TaskFixtures.result(result(List.of(oversizedFinding)))));
            assertTrue(posts.isEmpty());
            assertFalse(store.<Boolean>transaction(state -> (state.task(id()).reviewIntent() != null)));
        }

        @Test
        void rejectsStaleHeadBeforePublication() {
            initialSha = "new-head";
            assertThrows(
                    IllegalArgumentException.class,
                    () -> publisher().publish(TaskFixtures.task(task), TaskFixtures.result(result(List.of()))));
            assertEquals(1, heads.get());
            assertTrue(posts.isEmpty());
        }

        @Test
        void rechecksHeadAfterDiffValidationBeforeWritingIntent() {
            finalSha = "changed-during-validation";
            assertThrows(
                    IllegalArgumentException.class,
                    () -> publisher()
                            .publish(
                                    TaskFixtures.task(task),
                                    TaskFixtures.result(result(List.of(finding("RIGHT", 21))))));
            assertEquals(2, heads.get());
            assertTrue(posts.isEmpty());
            assertFalse(store.<Boolean>transaction(state -> (state.task(id()).reviewIntent() != null)));
        }

        @Test
        void ambiguousWriteBlocksDuplicatesUntilMatchingNativeReviewIsVisible() {
            postStatus = 503;
            assertThrows(
                    IllegalStateException.class,
                    () -> publisher().publish(TaskFixtures.task(task), TaskFixtures.result(result(List.of()))));
            assertTrue(intentAtPost);
            assertEquals(1, posts.size());
            // A marker alone is not enough: author, commit and native review state must all match.
            reviews = List.of(
                    review("intruder", "head", "COMMENTED"),
                    review("bot", "old-head", "COMMENTED"),
                    review("bot", "head", "PENDING"),
                    review("bot", "head", "APPROVED"));
            assertThrows(
                    IllegalStateException.class,
                    () -> publisher().publish(TaskFixtures.task(task), TaskFixtures.result(result(List.of()))));
            assertEquals(1, posts.size());
            reviews = List.of(review("BoT", "head", "COMMENTED"));
            // Reopen storage as after a dispatcher restart; no structured review is needed for
            // reconciliation.
            assertEquals(
                    URL,
                    new ReviewPublisher(
                                    github,
                                    new TaskStore(BotTestConfiguration.home(home), json),
                                    BotTestConfiguration.configuration("", "bot", ""),
                                    presentation)
                            .publish(TaskFixtures.task(task), TaskFixtures.result(Map.of())));
            assertEquals(1, posts.size());
        }

        @Test
        void reconcilesPersistedReviewCommitAfterHeadMovesAcrossRestart() {
            postStatus = 503;
            assertThrows(
                    IllegalStateException.class,
                    () -> publisher().publish(TaskFixtures.task(task), TaskFixtures.result(result(List.of()))));
            assertEquals("head", store.transaction(state -> state.task(id()).reviewIntentSha()));
            initialSha = "new-head";
            finalSha = "new-head";
            reviews = List.of(review("bot", "head", "COMMENTED"));
            assertEquals(
                    URL,
                    new ReviewPublisher(
                                    github,
                                    new TaskStore(BotTestConfiguration.home(home), json),
                                    BotTestConfiguration.configuration("", "bot", ""),
                                    presentation)
                            .publish(TaskFixtures.task(task), TaskFixtures.result(Map.of())));
            assertEquals(1, posts.size());
        }
    }

    @Nested
    @FusionSupport(modules = CustomModule.class)
    class Custom {
        @Test
        void customTemplatesPreserveLiteralMarkdownAndHostMarkers() {
            final var literal = "**keep** & <T> {{summary}} {{{body}}} {{#if tests}}literal{{/if}}";
            final var result = Map.<String, Object>of(
                    "review",
                            Map.of(
                                    "headSha",
                                    "head",
                                    "body",
                                    literal,
                                    "comments",
                                    List.of(Map.of(
                                            "path", "src/App.java", "side", "RIGHT", "line", 21, "body", literal))),
                    "tests", List.of("not run {{summary}}"));
            publisher(presentation).publish(TaskFixtures.task(task), TaskFixtures.result(result));
            final var posted = posts.getFirst();
            assertEquals(marker() + "\nReport: " + literal + "\nnot run {{summary}}", posted.get("body"));
            final var inline = TaskStore.map(((List<?>) posted.get("comments")).getFirst());
            assertEquals(marker() + "\nFinding 1: " + literal, inline.get("body"));
        }
    }

    public static class CustomModule extends GithubConfigurationTestModule {
        public CustomModule() {
            super(Map.of(
                    "github.review.presentation.reportTemplate",
                    "Report: {{{summary}}}\n{{#each tests}}{{{this}}}{{/each}}",
                    "github.review.presentation.findingTemplate",
                    "Finding {{number}}: {{{body}}}"));
        }
    }

    private Map<String, Object> review(final String login, final String sha, final String state) {
        return Map.of(
                "body", marker(), "user", Map.of("login", login), "commit_id", sha, "state", state, "html_url", URL);
    }

    private Map<String, Object> finding(final String side, final Number line) {
        return Map.of("path", "src/App.java", "side", side, "line", line, "body", "Explain the defect");
    }

    private Map<String, Object> result(final List<Map<String, Object>> comments) {
        return Map.of("review", Map.of("headSha", "head", "body", "Findings", "comments", comments));
    }

    private String id() {
        return String.valueOf(task.get("id"));
    }

    private String marker() {
        return "<!-- yca-task:" + id() + ":review -->";
    }

    private ReviewPublisher publisher() {
        return publisher(presentation);
    }

    private ReviewPublisher publisher(final ReviewPresentationConfiguration presentation) {
        return new ReviewPublisher(github, store, BotTestConfiguration.configuration("", "bot", ""), presentation);
    }

    private int occurrences(final String value, final String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
