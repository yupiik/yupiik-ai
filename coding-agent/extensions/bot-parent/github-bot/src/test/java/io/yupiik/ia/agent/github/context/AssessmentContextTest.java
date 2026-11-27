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
import io.yupiik.ia.agent.github.configuration.ContextConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.context.git.TrustedGitStore;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssessmentContextTest {
    private static final String SHA = "a".repeat(40);
    private static final String TREE = "b".repeat(40);

    @TempDir
    Path temporary;

    @Test
    void issueSnapshotAndExportUseIssueAndPinnedDefaultCommitOnly() throws Exception {
        try (final var fixture = new Fixture()) {
            final var task = fixture.task("issue", Map.of());
            final var base = AssessmentBase.pin(fixture.github, task);
            assertEquals("release/stable", base.branch());
            assertEquals(SHA, base.sha());
            final var pinned = fixture.task("issue", base.recovery());
            fixture.paths.clear();
            fixture.commitStatus = 404; // Moving/deleted branch cannot change the saved authority.
            final var attempt = attempt("export");
            new GithubContextSnapshot(fixture.github, fixture.json).stage(pinned, attempt);
            final var snapshot = map(fixture.json.fromString(
                    Object.class, Files.readString(attempt.resolve("workspace/github-context.json"))));
            assertTrue(snapshot.containsKey("issue"));
            assertTrue(snapshot.containsKey("issueComments"));
            assertFalse(snapshot.containsKey("pull"));
            assertFalse(snapshot.toString().contains("secret"));
            final var source = new GithubPrivateContext(fixture.github, fixture.json).stage(pinned, attempt);
            assertEquals(SHA, source.headSha());
            assertFalse(Files.exists(attempt.resolve("workspace/github-solve.json")));
            assertTrue(
                    fixture.paths.stream().noneMatch(it -> it.contains("/pulls/") || it.contains("/commits/release")));
            assertTrue(fixture.paths.contains("GET /repos/acme/project/git/commits/" + SHA));
        }
    }

    @Test
    void brokerUsesAssessmentPinAndRejectsPrReadsForBothTargets() throws Exception {
        try (final var fixture = new Fixture()) {
            for (final var target : List.of("issue", "discussion")) {
                final var task = fixture.task(target, new AssessmentBase("main", SHA, false).recovery());
                try (final var service = fixture.service(task, temporary.resolve(target))) {
                    assertEquals(SHA, service.metadata().get("headSha"));
                    assertEquals(target, service.metadata().get("reviewTarget"));
                    for (final var operation : List.of("pull", "files", "reviews", "reviewComments")) {
                        assertThrows(
                                IllegalArgumentException.class, () -> service.call(Map.of("operation", operation)));
                    }
                    if ("issue".equals(target)) {
                        service.call(Map.of("operation", "issue"));
                        service.call(Map.of("operation", "issueComments"));
                    }
                }
            }
            assertTrue(fixture.paths.stream().noneMatch(it -> it.contains("/pulls/") || it.contains("/commits/")));
        }
    }

    @Test
    void emptyRepoAllowsAssessmentButPermissionFailuresAreNotSourceAbsence() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.size = 0;
            fixture.commitStatus = 409;
            final var base = AssessmentBase.pin(fixture.github, fixture.task("issue", Map.of()));
            assertTrue(base.emptyRepository());
            assertNull(base.sha());
            final var pinned = fixture.task("issue", base.recovery());
            final var attempt = attempt("empty");
            new GithubContextSnapshot(fixture.github, fixture.json).stage(pinned, attempt);
            assertNull(new GithubPrivateContext(fixture.github, fixture.json).stage(pinned, attempt));
            assertFalse(Files.exists(attempt.resolve("workspace/repository")));
            try (final var service = fixture.service(pinned, temporary.resolve("empty-service"))) {
                assertEquals("EMPTY_REPOSITORY", service.metadata().get("sourceStatus"));
                for (final var operation : List.of("git", "prepare_source", "request_repository")) {
                    final var request = new LinkedHashMap<String, Object>();
                    request.put("operation", operation);
                    if ("request_repository".equals(operation)) {
                        request.put("repository", "acme/project");
                    }
                    assertEquals(
                            "EMPTY_REPOSITORY",
                            map(service.call(request).get("body")).get("status"));
                }
                service.call(Map.of("operation", "issue"));
            }
            for (final var status : List.of(401, 403, 404)) {
                fixture.commitStatus = status;
                assertEquals(
                        status,
                        assertThrows(
                                        GitHubClient.HttpException.class,
                                        () -> AssessmentBase.pin(fixture.github, fixture.task("issue", Map.of())))
                                .status());
            }
            fixture.size = 1;
            fixture.commitStatus = 409;
            assertThrows(
                    GitHubClient.HttpException.class,
                    () -> AssessmentBase.pin(fixture.github, fixture.task("issue", Map.of())));
        }
    }

    @Test
    void malformedPinsNeverFallBackToMovingBranchOrStrictSolveAuthority() throws Exception {
        try (final var fixture = new Fixture()) {
            for (final var pin : List.of(
                    Map.<String, Object>of("assessmentBaseSha", SHA),
                    Map.<String, Object>of("assessmentBaseBranch", "main"),
                    Map.<String, Object>of("assessmentBaseBranch", "main", "assessmentBaseSha", "bad"),
                    Map.<String, Object>of(
                            "assessmentBaseBranch",
                            "main",
                            "assessmentBaseSha",
                            SHA,
                            "assessmentEmptyRepository",
                            true))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AssessmentBase.pin(fixture.github, fixture.task("issue", pin)));
            }
            assertTrue(fixture.paths.isEmpty());
        }
    }

    @Test
    void legacyMailboxCannotUseIssueReviewAsPrAuthority() throws Exception {
        try (final var fixture = new Fixture()) {
            final var attempt = attempt("mailbox");
            try (final var _ =
                    new GithubContextBroker(fixture.github, fixture.json, fixture.task("issue", Map.of()), attempt)) {
                final var root = attempt.resolve("workspace/github-broker");
                Files.writeString(root.resolve("requests/0.json"), "{\"operation\":\"files\",\"page\":1}");
                final var response = root.resolve("responses/0.json");
                ContextTestSupport.await(() -> {
                    try {
                        return Files.exists(response)
                                && fixture.json.fromString(Object.class, Files.readString(response))
                                        instanceof Map<?, ?>;
                    } catch (final IOException | RuntimeException incomplete) {
                        return false;
                    }
                });
                assertEquals(
                        false,
                        map(fixture.json.fromString(Object.class, Files.readString(response)))
                                .get("ok"));
                assertTrue(fixture.paths.isEmpty());
            }
        }
    }

    private Path attempt(final String name) throws Exception {
        final var attempt = temporary.resolve(name);
        Files.createDirectories(attempt.resolve("workspace"));
        return attempt;
    }

    private static Map<?, ?> map(final Object value) {
        return assertInstanceOf(Map.class, value);
    }

    private static final class Fixture implements AutoCloseable {
        private final JsonMapper json = BotJsonMapper.create(ignored -> Optional.empty());
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final GitHubClient github;
        private int commitStatus = 200;
        private int size = 1;

        private Fixture() throws Exception {
            server.createContext("/", exchange -> {
                try (exchange) {
                    final var path = exchange.getRequestURI().toString();
                    paths.add(exchange.getRequestMethod() + " " + path);
                    final String body;
                    int status = 200;
                    if (path.equals("/repos/acme/project")) {
                        body =
                                "{\"id\":1,\"full_name\":\"acme/project\",\"default_branch\":\"release/stable\",\"size\":"
                                        + size + "}";
                    } else if (path.startsWith("/repos/acme/project/commits/")) {
                        status = commitStatus;
                        body = "{\"sha\":\"" + SHA + "\"}";
                    } else if (path.equals("/repos/acme/project/issues/7")) {
                        body =
                                "{\"id\":7,\"node_id\":\"I7\",\"title\":\"Assess\",\"body\":\"Proposal\",\"state\":\"open\",\"secret\":\"hidden\"}";
                    } else if (path.startsWith("/repos/acme/project/issues/7/comments?")) {
                        body = "[{\"body\":\"Comment\",\"secret\":\"hidden\"}]";
                    } else if (path.equals("/repos/acme/project/git/commits/" + SHA)) {
                        body = "{\"sha\":\"" + SHA + "\",\"tree\":{\"sha\":\"" + TREE + "\"}}";
                    } else if (path.equals("/repos/acme/project/git/trees/" + TREE + "?recursive=1")) {
                        body = "{\"sha\":\"" + TREE + "\",\"truncated\":false,\"tree\":[]}";
                    } else {
                        status = 404;
                        body = "{}";
                    }
                    final var bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            github = new GitHubClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    json,
                    () -> "test-only",
                    Clock.systemUTC());
        }

        private TaskDocument task(final String target, final Map<String, Object> pins) {
            final var values = new LinkedHashMap<String, Object>(pins);
            values.putAll(Map.of(
                    "id",
                    "assessment",
                    "repository",
                    "acme/project",
                    "number",
                    7,
                    "kind",
                    "review",
                    "reviewTarget",
                    target,
                    "sourceType",
                    "issue",
                    "discussionId",
                    "D1",
                    "reference",
                    "https://github.com/acme/project/pull/7"));
            return TaskFixtures.task(values);
        }

        private ContextService service(final TaskDocument task, final Path root) {
            return new ContextService(
                    github,
                    json,
                    task,
                    Clock.systemUTC(),
                    Instant.now().plusSeconds(60),
                    new ContextBudget(100, 1024 * 1024),
                    new ContextConfiguration(
                            "127.0.0.1",
                            0,
                            "https://worker.invalid",
                            "",
                            "/not/executed",
                            "",
                            100,
                            1024 * 1024,
                            67108864L),
                    new WorkerOptions.Source(WorkerOptions.SourceMode.broker, "", 8, 1024 * 1024, 5),
                    new TrustedGitStore(
                            root, "/not/executed", Duration.ofSeconds(5), 1024 * 1024, 67108864L, () -> "test-only"));
        }

        @Override
        public void close() {
            github.close();
            server.stop(0);
            json.close();
        }
    }
}
