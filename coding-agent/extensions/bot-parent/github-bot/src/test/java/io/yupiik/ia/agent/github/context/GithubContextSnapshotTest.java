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
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GithubContextSnapshotTest {
    private static final String PREFIX = "/repos/acme/private/pulls/7";
    private static final String ISSUE_COMMENTS = "/repos/acme/private/issues/7/comments";
    private static final String PULL = """
            {"title":"Review this","body":"Private context","head":{"sha":"head-sha",
            "repo":{"clone_url":"https://secret@github.com/private.git"}},
            "base":{"sha":"base-sha","repo":{"private":true}},"token":"host-secret"}
            """;
    private static final Map<String, Object> TASK = Map.of(
            "repository",
            "acme/private",
            "number",
            7,
            "kind",
            "review",
            "sourceUrl",
            "https://attacker.invalid/steal",
            "token",
            "task-secret");

    @TempDir
    Path temporary;

    @Test
    void stagesOnlyWhitelistedContextAndKeepsCredentialsUpstream() throws Exception {
        try (final var stub = new Stub(path -> {
            if (path.equals(PREFIX)) {
                return new Reply(PULL, null, 200);
            }
            final String body;
            if (path.startsWith(PREFIX + "/files?")) {
                body = """
                        [{"filename":"src/Main.java","status":"modified","patch":"@@ diff",
                        "raw_url":"https://private.invalid","clone_url":"host-secret"}]
                        """;
            } else if (path.startsWith(PREFIX + "/reviews?")) {
                body = """
                        [{"body":"Review body","state":"COMMENTED","commit_id":"review-sha",
                        "user":{"token":"host-secret"}}]
                        """;
            } else if (path.startsWith(PREFIX + "/comments?")) {
                body = """
                        [{"path":"src/Main.java","line":3,"side":"RIGHT","body":"Inline",
                        "diff_hunk":"not-whitelisted","user":{"token":"host-secret"}}]
                        """;
            } else {
                body = "[{\"body\":\"Conversation\",\"url\":\"host-secret\"}]";
            }
            return new Reply(body, null, 200);
        })) {
            final var attempt = attempt("whitelist");
            stub.snapshot.stage(TaskFixtures.task(TASK), attempt);
            final var target = target(attempt);
            assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS));
            final var content = Files.readString(target);
            assertFalse(content.contains("host-secret"));
            assertFalse(content.contains("task-secret"));
            assertFalse(content.contains("clone_url"));
            assertFalse(content.contains("raw_url"));
            assertFalse(content.contains("not-whitelisted"));
            assertFalse(content.contains("attacker"));
            assertEquals(
                    stub.json.fromString(GithubContextSnapshot.Snapshot.class, """
                            {"schemaVersion":1,"repo":"acme/private","number":7,
                            "pull":{"title":"Review this","body":"Private context",
                            "head":{"sha":"head-sha"},"base":{"sha":"base-sha"}},
                            "files":[{"filename":"src/Main.java","status":"modified","patch":"@@ diff"}],
                            "reviews":[{"body":"Review body","state":"COMMENTED","commit_id":"review-sha"}],
                            "comments":[{"path":"src/Main.java","line":3,"side":"RIGHT","body":"Inline"}],
                            "issueComments":[{"body":"Conversation"}]}
                            """),
                    stub.json.fromString(GithubContextSnapshot.Snapshot.class, content));
            assertEquals(
                    List.of(
                            PREFIX,
                            PREFIX + "/files?per_page=100",
                            PREFIX + "/reviews?per_page=100",
                            PREFIX + "/comments?per_page=100",
                            ISSUE_COMMENTS + "?per_page=100"),
                    stub.requests);
            assertTrue(stub.authorizations.stream().allMatch("Bearer host-secret"::equals));
            assertTrue(stub.methods.stream().allMatch("GET"::equals));
            assertEquals(5, stub.authorizations.size());
            assertThrows(IOException.class, () -> stub.snapshot.stage(TaskFixtures.task(TASK), attempt));
            assertEquals(content, Files.readString(target));
            assertEquals(5, stub.requests.size());
        }
    }

    @Test
    void traversesTenPagesForEveryCollection() throws Exception {
        for (final var endpoint :
                List.of(PREFIX + "/files", PREFIX + "/reviews", PREFIX + "/comments", ISSUE_COMMENTS)) {
            try (final var stub = new Stub(path -> {
                if (path.equals(PREFIX)) {
                    return new Reply(PULL, null, 200);
                }
                if (!path.startsWith(endpoint + "?")) {
                    return new Reply("[]", null, 200);
                }
                final int page = page(path);
                return new Reply(
                        "[{\"body\":\"page-" + page + "\",\"filename\":\"file-" + page + "\"}]",
                        page == 10 ? null : endpoint + "?page=" + (page + 1) + "&per_page=100",
                        200);
            })) {
                final var attempt = attempt("pages-" + endpoint.hashCode());
                stub.snapshot.stage(TaskFixtures.task(TASK), attempt);
                assertEquals(14, stub.requests.size());
                assertEquals(
                        10,
                        stub.requests.stream()
                                .filter(path -> path.startsWith(endpoint + "?"))
                                .count());
                final var content = Files.readString(target(attempt));
                assertTrue(content.contains("page-10") || content.contains("file-10"));
            }
        }
    }

    @Test
    void refusesEleventhPageWithoutPartialSnapshot() throws Exception {
        for (final var endpoint :
                List.of(PREFIX + "/files", PREFIX + "/reviews", PREFIX + "/comments", ISSUE_COMMENTS)) {
            try (final var stub = new Stub(path -> new Reply(
                    path.equals(PREFIX) ? PULL : "[]",
                    path.startsWith(endpoint + "?") ? endpoint + "?per_page=100&page=" + (page(path) + 1) : null,
                    200))) {
                final var attempt = attempt("limit-" + endpoint.hashCode());
                assertThrows(IllegalStateException.class, () -> stub.snapshot.stage(TaskFixtures.task(TASK), attempt));
                assertEquals(
                        10,
                        stub.requests.stream()
                                .filter(path -> path.startsWith(endpoint + "?"))
                                .count());
                assertFalse(Files.exists(target(attempt)));
            }
        }
    }

    @Test
    void refusesPaginationTraversalCyclesAndArbitraryUrls() throws Exception {
        for (final var next : List.of(
                "https://attacker.invalid/steal?per_page=100&page=2",
                "/repos/other/private/pulls/7/files?per_page=100&page=2",
                PREFIX + "/files?per_page=100&page=1",
                PREFIX + "/files?per_page=100&page=3",
                PREFIX + "/files?per_page=100&page=2&url=https://attacker.invalid",
                "/../" + PREFIX.substring(1) + "/files?per_page=100&page=2",
                "/%2e%2e" + PREFIX + "/files?per_page=100&page=2",
                PREFIX + "/files?per_page=100&page=2#fragment")) {
            try (final var stub = new Stub(
                    path -> new Reply(path.equals(PREFIX) ? PULL : "[]", path.equals(PREFIX) ? null : next, 200))) {
                final var attempt = attempt("traversal-" + next.hashCode());
                assertThrows(RuntimeException.class, () -> stub.snapshot.stage(TaskFixtures.task(TASK), attempt));
                assertEquals(List.of(PREFIX, PREFIX + "/files?per_page=100"), stub.requests);
                assertFalse(Files.exists(target(attempt)));
            }
        }
    }

    @Test
    void rejectsInvalidRepositoryAndNumbersBeforeAnyHttp() throws Exception {
        try (final var stub = new Stub(path -> new Reply(PULL, null, 200))) {
            final var attempt = attempt("invalid");
            for (final var repo : List.of(
                    "../private",
                    "acme/..",
                    "acme/.",
                    "acme/private/extra",
                    "https://github.com/acme/private",
                    "acme/%2e%2e",
                    "acme/private?x=y",
                    "acme/private#fragment",
                    "acme/private\n",
                    "a".repeat(100_000) + "/private")) {
                assertThrows(
                        RuntimeException.class,
                        () -> stub.snapshot.stage(TaskFixtures.task(Map.of("repository", repo, "number", 7)), attempt));
            }
            for (final var number : List.of(
                    0,
                    -1,
                    1.5,
                    Double.NaN,
                    Double.POSITIVE_INFINITY,
                    "7",
                    "../7",
                    new BigInteger("9223372036854775808"),
                    new BigDecimal("0.0001"))) {
                assertThrows(
                        RuntimeException.class,
                        () -> stub.snapshot.stage(
                                TaskFixtures.task(Map.of("repository", "acme/private", "number", number)), attempt));
            }
            assertThrows(
                    RuntimeException.class,
                    () -> stub.snapshot.stage(TaskFixtures.task(Map.of("repository", "acme/private")), attempt));
            assertTrue(stub.requests.isEmpty());
        }
    }

    @Test
    void fixSnapshotRetainsPrContextWithoutReviewTarget() throws Exception {
        try (final var stub = new Stub(path -> new Reply(path.equals(PREFIX) ? PULL : "[]", null, 200))) {
            final var attempt = attempt("fix");
            stub.snapshot.stage(
                    TaskFixtures.task(Map.of("repository", "acme/private", "number", 7, "kind", "fix")), attempt);
            assertTrue(Files.readString(target(attempt)).contains("\"pull\""));
            assertEquals(PREFIX, stub.requests.getFirst());
        }
    }

    @Test
    void acceptsIntegralJsonNumber() throws Exception {
        try (final var stub = new Stub(path -> new Reply(path.equals(PREFIX) ? PULL : "[]", null, 200))) {
            final var attempt = attempt("integral");
            stub.snapshot.stage(
                    TaskFixtures.task(Map.of(
                            "repository",
                            "acme/private",
                            "number",
                            new BigDecimal("7.0"),
                            "reviewTarget",
                            "pull_request")),
                    attempt);
            assertTrue(Files.isRegularFile(target(attempt)));
        }
    }

    @Test
    void boundsCumulativeEncodedBytesEvenForDiscardedFields() throws Exception {
        // Two individually small responses exceed 4 MiB together. UTF-8 bytes, not Java character count.
        final var large = "\u00e9".repeat(1_050_000);
        try (final var stub = new Stub(
                path -> new Reply(path.equals(PREFIX) ? PULL : "[{\"discarded\":\"" + large + "\"}]", null, 200))) {
            final var attempt = attempt("bytes");
            assertThrows(IllegalStateException.class, () -> stub.snapshot.stage(TaskFixtures.task(TASK), attempt));
            assertEquals(3, stub.requests.size());
            assertFalse(Files.exists(target(attempt)));
        }
    }

    @Test
    void acceptsExactlyFourMiBAndRejectsOneByteMore() throws Exception {
        try (final var _ = BotJsonMapper.create(key -> Optional.empty())) {
            final int fixed = PULL.getBytes(StandardCharsets.UTF_8).length
                    + "[{\"discarded\":\"\"}]".getBytes(StandardCharsets.UTF_8).length
                    + 3 * 2; // The other three collections are empty arrays.
            for (final int extra : List.of(0, 1)) {
                final var body = "[{\"discarded\":\"" + "a".repeat(4 * 1024 * 1024 - fixed + extra) + "\"}]";
                try (final var stub = new Stub(path -> new Reply(
                        path.equals(PREFIX) ? PULL : path.startsWith(PREFIX + "/files?") ? body : "[]", null, 200))) {
                    final var attempt = attempt("boundary-" + extra);
                    if (extra == 0) {
                        stub.snapshot.stage(TaskFixtures.task(TASK), attempt);
                        assertTrue(Files.isRegularFile(target(attempt)));
                    } else {
                        assertThrows(
                                IllegalStateException.class,
                                () -> stub.snapshot.stage(TaskFixtures.task(TASK), attempt));
                        assertFalse(Files.exists(target(attempt)));
                    }
                }
            }
        }
    }

    @Test
    void boundsSingleEncodedResponseAndJsonEscaping() throws Exception {
        // Newlines are doubled by JSON encoding despite occupying one UTF-8 byte before encoding.
        final var large = "\\n".repeat(2_100_000);
        try (final var stub = new Stub(
                path -> new Reply(path.equals(PREFIX) ? PULL : "[{\"patch\":\"" + large + "\"}]", null, 200))) {
            final var attempt = attempt("single-limit");
            assertThrows(IllegalStateException.class, () -> stub.snapshot.stage(TaskFixtures.task(TASK), attempt));
            assertEquals(2, stub.requests.size());
            assertFalse(Files.exists(target(attempt)));
        }
    }

    @Test
    void lateHttpOrMalformedContextFailureLeavesNoFile() throws Exception {
        for (final var failure : List.of(
                new Reply("{}", null, 404),
                new Reply("not-json", null, 200),
                new Reply("{}", null, 200),
                new Reply("[{\"body\":{\"token\":\"host-secret\"}}]", null, 200))) {
            try (final var stub = new Stub(path -> path.startsWith(ISSUE_COMMENTS)
                    ? failure
                    : new Reply(path.equals(PREFIX) ? PULL : "[]", null, 200))) {
                final var attempt = attempt("failure-" + failure.hashCode());
                assertThrows(RuntimeException.class, () -> stub.snapshot.stage(TaskFixtures.task(TASK), attempt));
                assertEquals(5, stub.requests.size());
                assertFalse(Files.exists(target(attempt)));
            }
        }
    }

    @Test
    void refusesSymlinkFileWorkspaceAttemptAndAncestorBeforeFetching() throws Exception {
        try (final var stub = new Stub(path -> new Reply(PULL, null, 200))) {
            final var outside = Files.createDirectory(temporary.resolve("outside"));
            final var sentinel = Files.writeString(outside.resolve("sentinel"), "unchanged");
            final var fileAttempt = attempt("file-link");
            Files.createSymbolicLink(target(fileAttempt), sentinel);
            assertThrows(IOException.class, () -> stub.snapshot.stage(TaskFixtures.task(TASK), fileAttempt));
            assertEquals("unchanged", Files.readString(sentinel));

            final var danglingAttempt = attempt("dangling-link");
            Files.createSymbolicLink(target(danglingAttempt), outside.resolve("missing"));
            assertThrows(IOException.class, () -> stub.snapshot.stage(TaskFixtures.task(TASK), danglingAttempt));
            assertFalse(Files.exists(outside.resolve("missing")));

            final var workspaceAttempt = Files.createDirectory(temporary.resolve("workspace-link"));
            Files.createSymbolicLink(workspaceAttempt.resolve("workspace"), outside);
            assertThrows(IOException.class, () -> stub.snapshot.stage(TaskFixtures.task(TASK), workspaceAttempt));

            final var realAttempt = attempt("real");
            final var attemptLink = Files.createSymbolicLink(temporary.resolve("attempt-link"), realAttempt);
            assertThrows(IOException.class, () -> stub.snapshot.stage(TaskFixtures.task(TASK), attemptLink));
            final var ancestorLink = Files.createSymbolicLink(temporary.resolve("ancestor-link"), temporary);
            assertThrows(
                    IOException.class,
                    () -> stub.snapshot.stage(TaskFixtures.task(TASK), ancestorLink.resolve("real")));
            assertFalse(Files.exists(target(realAttempt)));
            assertFalse(Files.exists(outside.resolve("github-context.json")));
            final var directoryAttempt = attempt("directory-target");
            Files.createDirectory(target(directoryAttempt));
            assertThrows(IOException.class, () -> stub.snapshot.stage(TaskFixtures.task(TASK), directoryAttempt));
            assertTrue(stub.requests.isEmpty());
        }
    }

    private Path attempt(final String name) throws IOException {
        final var attempt = Files.createDirectory(temporary.resolve(name));
        Files.createDirectory(attempt.resolve("workspace"));
        return attempt;
    }

    private static Path target(final Path attempt) {
        return attempt.resolve("workspace/github-context.json");
    }

    private static int page(final String path) {
        final int index = path.indexOf("&page=");
        return index < 0 ? 1 : Integer.parseInt(path.substring(index + 6));
    }

    private record Reply(String body, String next, int status) {}

    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        private final JsonMapper json = BotJsonMapper.create(key -> Optional.empty());
        private final GitHubClient client;
        private final GithubContextSnapshot snapshot;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private final List<String> authorizations = new CopyOnWriteArrayList<>();

        private Stub(final Function<String, Reply> handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            final var base =
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            server.createContext("/", exchange -> {
                try (exchange) {
                    final var path = exchange.getRequestURI().toString();
                    requests.add(path);
                    methods.add(exchange.getRequestMethod());
                    authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    final var reply = handler.apply(path);
                    if (reply.next() != null) {
                        final var next = reply.next().startsWith("/") ? base + reply.next() : reply.next();
                        exchange.getResponseHeaders().add("Link", "<" + next + ">; rel=\"next\"");
                    }
                    final var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(reply.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            client = new GitHubClient(base, json, () -> "host-secret", BotTestConfiguration.CLOCK);
            snapshot = new GithubContextSnapshot(client, json);
        }

        @Override
        public void close() {
            client.close();
            json.close();
            server.stop(0);
        }
    }
}
