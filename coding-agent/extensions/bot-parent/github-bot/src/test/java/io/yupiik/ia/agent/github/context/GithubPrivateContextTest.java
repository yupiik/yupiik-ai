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
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.PromptConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions.SourceMode;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = GithubConfigurationTestModule.class)
class GithubPrivateContextTest {
    @Fusion
    private PromptConfiguration prompts;

    @Fusion
    private GithubExecutorConfiguration executorRoot;

    private static final String HEAD = "a".repeat(40);
    private static final String TREE = "b".repeat(40);
    private static final String PREFIX = "/repos/acme/private/git/";
    private static final Map<String, Object> TASK = Map.of(
            "repository",
            "acme/private",
            "number",
            7,
            "kind",
            "review",
            "sourceUrl",
            "https://attacker.invalid/steal",
            "headSha",
            "untrusted-task-head");
    private static final byte[] SOURCE = "class Example {}\n".getBytes(StandardCharsets.UTF_8);
    private static final String BLOB = hash(SOURCE);

    @TempDir
    Path temporary;

    @Test
    void promptDescribesCredentialFreeExportAndHostOwnedSolve() {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var prompt = new AttemptPreparation(json, prompts, executorRoot.shared())
                    .prompt(TaskFixtures.task(TASK), "example-fork", false);
            assertTrue(prompt.contains("github-source.json"));
            assertTrue(prompt.contains("not a Git clone"));
            assertTrue(prompt.contains("Never configure git credentials"));
            assertFalse(prompt.contains("gh auth setup-git"));
            final var task = new LinkedHashMap<>(TASK);
            task.put("kind", "solve");
            final var solve = new AttemptPreparation(json, prompts, executorRoot.shared())
                    .prompt(TaskFixtures.task(task), "example-fork", false);
            assertTrue(solve.contains("The host alone creates Git objects"));
            assertTrue(solve.contains("github-solve.json"));
        }
    }

    @Test
    void solvePinsHostDefaultBranchAndExportsCredentialFreeSource() throws Exception {
        try (final var stub = new Stub(path -> {
            if (path.equals("/repos/acme/private")) {
                return "{\"default_branch\":\"main\"}";
            }
            if (path.equals("/repos/acme/private/commits/main")) {
                return "{\"sha\":\"" + HEAD + "\"}";
            }
            return normal(path, SOURCE);
        })) {
            final var attempt = temporary.resolve("solve");
            Files.createDirectories(attempt.resolve("workspace"));
            final var task = new LinkedHashMap<>(TASK);
            task.put("kind", "solve");
            final var context = stub.source.stageSolve(TaskFixtures.task(task), attempt, SourceMode.export);
            assertEquals(HEAD, context.baseSha());
            assertEquals("main", context.baseBranch());
            assertTrue(Files.exists(attempt.resolve("workspace/github-solve.json")));
            assertTrue(Files.exists(attempt.resolve("workspace/github-source.json")));
            assertFalse(Files.exists(attempt.resolve("workspace/repository/.git")));
            final var other = temporary.resolve("changed");
            Files.createDirectories(other.resolve("workspace"));
            task.put("strictSolveBaseSha", "c".repeat(40));
            assertThrows(
                    IllegalStateException.class,
                    () -> stub.source.stageSolve(TaskFixtures.task(task), other, SourceMode.export));
            assertFalse(Files.exists(other.resolve("workspace/repository")));
        }
    }

    @Test
    void gitSolvePinsMetadataWithoutFetchingAnyTreeOrBlob() throws Exception {
        final var requests = new CopyOnWriteArrayList<String>();
        try (final var stub = new Stub(path -> {
            requests.add(path);
            return switch (path) {
                case "/repos/acme/private" -> "{\"default_branch\":\"main\"}";
                case "/repos/acme/private/commits/main" -> "{\"sha\":\"" + HEAD + "\"}";
                default -> throw new AssertionError("Unexpected eager source request: " + path);
            };
        })) {
            final var attempt = temporary.resolve("git-solve");
            Files.createDirectories(attempt.resolve("workspace"));
            final var task = new LinkedHashMap<>(TASK);
            task.put("kind", "solve");
            final var context = stub.source.stageSolve(TaskFixtures.task(task), attempt, SourceMode.git);
            assertEquals(HEAD, context.baseSha());
            assertEquals("main", context.baseBranch());
            assertEquals(List.of("/repos/acme/private", "/repos/acme/private/commits/main"), requests);
            assertTrue(Files.isRegularFile(attempt.resolve("workspace/github-solve.json")));
            assertFalse(Files.exists(attempt.resolve("workspace/github-source.json")));
            assertFalse(Files.exists(attempt.resolve("workspace/repository")));

            final var other = temporary.resolve("git-changed");
            Files.createDirectories(other.resolve("workspace"));
            task.put("strictSolveBaseSha", "c".repeat(40));
            assertThrows(
                    IllegalStateException.class,
                    () -> stub.source.stageSolve(TaskFixtures.task(task), other, SourceMode.git));
            assertFalse(Files.exists(other.resolve("workspace/github-solve.json")));
        }
    }

    @Test
    void lateBlobFailureLeavesNeitherManifestNorSource() throws Exception {
        final var other = "another file".getBytes(StandardCharsets.UTF_8);
        try (final var stub = new Stub(path -> {
            if (path.contains("trees/")) {
                return tree(regular() + "," + entry("second.txt", "blob", "100644", other.length, hash(other)));
            }
            if (path.equals(PREFIX + "blobs/" + hash(other))) {
                return "{}";
            }
            return normal(path, SOURCE);
        })) {
            final var attempt = attempt(stub, "late");
            assertThrows(IllegalStateException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
            assertEquals(4, stub.requests.size());
            absent(attempt);
        }
    }

    @Test
    void acceptsExactAggregateByteLimitAndWrappedBase64() throws Exception {
        final var bytes = new byte[1024 * 1024];
        final var entries = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0) {
                entries.append(',');
            }
            entries.append(entry("file-" + i, "blob", "100644", bytes.length, hash(bytes)));
        }
        try (final var stub = new Stub(path -> path.contains("trees/")
                ? tree(entries.toString())
                : normal(path, bytes).replace("\"content\":\"", "\"content\":\"\\n"))) {
            final var attempt = attempt(stub, "exact-total");
            assertEquals(
                    16 * 1024 * 1024,
                    stub.source.stage(TaskFixtures.task(TASK), attempt).sourceBytes());
            assertEquals(3, stub.requests.size()); // Identical blob hashes are fetched once.
        }
    }

    @Test
    void exportsPinnedVerifiedSourceWithNoCredentialsOrGitState() throws Exception {
        try (final var stub = new Stub(path -> normal(path, SOURCE))) {
            final var attempt = attempt(stub, "success");
            final var result = stub.source.stage(TaskFixtures.task(TASK), attempt);
            assertEquals(HEAD, result.headSha());
            assertEquals(TREE, result.treeSha());
            assertEquals(SOURCE.length, result.sourceBytes());
            assertEquals(List.of("src/Example.java"), result.files());
            assertArrayEquals(SOURCE, Files.readAllBytes(attempt.resolve("workspace/repository/src/Example.java")));
            assertFalse(Files.exists(attempt.resolve("workspace/repository/.git")));
            final var metadata = Files.readString(attempt.resolve("workspace/github-source.json"));
            assertEquals(result, stub.json.fromString(GithubPrivateContext.SourceSnapshot.class, metadata));
            assertFalse(metadata.contains("host-secret"));
            assertFalse(metadata.contains("attacker"));
            assertEquals(
                    List.of(
                            PREFIX + "commits/" + HEAD,
                            PREFIX + "trees/" + TREE + "?recursive=1",
                            PREFIX + "blobs/" + BLOB),
                    stub.requests);
            assertTrue(stub.authorizations.stream().allMatch("Bearer host-secret"::equals));
            assertTrue(stub.methods.stream().allMatch("GET"::equals));
            assertThrows(IOException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
            assertEquals(3, stub.requests.size());
        }
    }

    @Test
    void preservesBinaryBytesAndDoesNotExecuteExecutableBlobs() throws Exception {
        final byte[] bytes = {0, -1, 13, 10, 42};
        try (final var stub = new Stub(path -> normal(path, bytes).replace("100644", "100755"))) {
            final var attempt = attempt(stub, "binary");
            stub.source.stage(TaskFixtures.task(TASK), attempt);
            assertArrayEquals(bytes, Files.readAllBytes(attempt.resolve("workspace/repository/src/Example.java")));
        }
    }

    @Test
    void refusesUnsafePathsBeforeFetchingAnyBlob() throws Exception {
        for (final var path : List.of(
                "../escape",
                "/absolute",
                "a/../../escape",
                "a//b",
                "a/./b",
                "a/",
                "C:drive",
                "a\\b",
                ".git/config",
                "A/.GiT/hooks/pre-commit",
                "a\nname",
                "a\u0000b",
                "a/CON",
                "a/nul.txt",
                "a/trailing.",
                "a/trailing ",
                "a/".repeat(33) + "b")) {
            try (final var stub = new Stub(endpoint -> endpoint.contains("trees/")
                    ? tree("{\"path\":\"" + escape(path)
                            + "\",\"mode\":\"100644\",\"type\":\"blob\",\"size\":0,\"sha\":\"" + BLOB + "\"}")
                    : normal(endpoint, SOURCE))) {
                final var attempt = attempt(stub, "unsafe");
                assertThrows(
                        IllegalStateException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt), path);
                assertEquals(2, stub.requests.size());
                absent(attempt);
            }
        }
    }

    @Test
    void refusesSymlinksGitlinksAndSpecialModes() throws Exception {
        for (final var mode : List.of("120000", "160000", "100600", "040000")) {
            try (final var stub = new Stub(path -> path.contains("trees/")
                    ? tree(entry("file", mode.equals("160000") ? "commit" : "blob", mode, 0, BLOB))
                    : normal(path, SOURCE))) {
                final var attempt = attempt(stub, "mode");
                assertThrows(IllegalStateException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
                assertEquals(2, stub.requests.size());
                absent(attempt);
            }
        }
    }

    @Test
    void refusesTruncationWrongShasAndMalformedShapes() throws Exception {
        for (final var tree : List.of(
                "{}",
                "[]",
                tree(regular()).replace("false", "true"),
                tree(regular()).replace(TREE, HEAD),
                tree(regular()).replace(BLOB, "../evil"),
                tree(regular()).replace("\"size\":" + SOURCE.length, "\"size\":0.5"))) {
            try (final var stub = new Stub(path -> path.contains("trees/") ? tree : normal(path, SOURCE))) {
                final var attempt = attempt(stub, "tree");
                assertThrows(RuntimeException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
                absent(attempt);
            }
        }
        try (final var stub = new Stub(path -> normal(path, SOURCE).replace(HEAD, TREE))) {
            final var attempt = attempt(stub, "commit");
            assertThrows(IllegalStateException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
            assertEquals(1, stub.requests.size());
            absent(attempt);
        }
    }

    @Test
    void refusesDuplicateAndConflictingPathsWithoutPartialPublication() throws Exception {
        for (final var entries : List.of(
                regular() + "," + regular(),
                regular() + "," + regular().replace("src/Example.java", "SRC/EXAMPLE.JAVA"),
                regular() + "," + regular().replace("src/Example.java", "src"))) {
            try (final var stub = new Stub(path -> path.contains("trees/") ? tree(entries) : normal(path, SOURCE))) {
                final var attempt = attempt(stub, "conflict");
                assertThrows(Exception.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
                absent(attempt);
                try (final var files = Files.list(attempt.resolve("workspace"))) {
                    assertEquals(
                            List.of("github-context.json"),
                            files.map(p -> p.getFileName().toString()).toList());
                }
            }
        }
    }

    @Test
    void boundsDeclaredFileCountsSizesAndAggregateBytesBeforeBlobRequests() throws Exception {
        for (final var entries : List.of(
                entry("big", "blob", "100644", 1024 * 1024 + 1, BLOB),
                entry("negative", "blob", "100644", -1, BLOB),
                many(2_001, 0, "blob", "100644"),
                many(17, 1024 * 1024, "blob", "100644"),
                many(10_001, 0, "tree", "040000"))) {
            try (final var stub = new Stub(path -> path.contains("trees/") ? tree(entries) : normal(path, SOURCE))) {
                final var attempt = attempt(stub, "limit");
                assertThrows(IllegalStateException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
                assertEquals(2, stub.requests.size());
                absent(attempt);
            }
        }
    }

    @Test
    void acceptsExactFileSizeAndCountBoundaries() throws Exception {
        final var bytes = new byte[1024 * 1024];
        try (final var stub = new Stub(path -> normal(path, bytes))) {
            final var attempt = attempt(stub, "exact-size");
            assertEquals(
                    bytes.length,
                    stub.source.stage(TaskFixtures.task(TASK), attempt).sourceBytes());
        }
        // A count boundary with empty files also checks that zero-byte blobs are supported.
        try (final var stub = new Stub(
                path -> path.contains("trees/") ? tree(many(2_000, 0, "blob", "100644")) : normal(path, new byte[0]))) {
            final var attempt = attempt(stub, "exact-count");
            assertEquals(
                    2_000,
                    stub.source.stage(TaskFixtures.task(TASK), attempt).files().size());
        }
    }

    @Test
    void refusesInvalidBlobEncodingSizesAndDigestWithoutSourceFiles() throws Exception {
        for (final var blob : List.of(
                "{}",
                blob(SOURCE).replace("base64", "utf-8"),
                blob(SOURCE).replace(BLOB, TREE),
                blob(SOURCE).replace("\"size\":" + SOURCE.length, "\"size\":1"),
                blob(SOURCE).replace(Base64.getEncoder().encodeToString(SOURCE), "!!!!"),
                blob(SOURCE)
                        .replace(
                                Base64.getEncoder().encodeToString(SOURCE),
                                Base64.getEncoder().encodeToString(new byte[SOURCE.length])))) {
            try (final var stub = new Stub(path -> path.contains("blobs/") ? blob : normal(path, SOURCE))) {
                final var attempt = attempt(stub, "blob");
                assertThrows(RuntimeException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
                absent(attempt);
            }
        }
    }

    @Test
    void ignoresRemoteUrlsAndRefusesUnexpectedPaginationAndRedirects() throws Exception {
        for (final int status : List.of(200, 302, 404)) {
            try (final var stub = new Stub(path -> normal(path, SOURCE))) {
                stub.status = status;
                stub.next = status == 200;
                final var attempt = attempt(stub, "http");
                assertThrows(RuntimeException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
                assertEquals(1, stub.requests.size());
                absent(attempt);
            }
        }
    }

    @Test
    void boundsEncodedResponseBytesIncludingDiscardedFields() throws Exception {
        try (final var stub = new Stub(path -> normal(path, SOURCE)
                .replace("\"tree\":", "\"ignored\":\"" + "x".repeat(32 * 1024 * 1024) + "\",\"tree\":"))) {
            final var attempt = attempt(stub, "response-budget");
            assertThrows(IllegalStateException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
            assertEquals(1, stub.requests.size());
            absent(attempt);
        }
    }

    @Test
    void rejectsTaskMismatchAndInvalidHeadBeforeHttp() throws Exception {
        try (final var stub = new Stub(path -> normal(path, SOURCE))) {
            final var attempt = attempt(stub, "task");
            for (final var task : List.of(
                    Map.<String, Object>of("kind", "solve", "repository", "acme/private", "number", 7),
                    Map.<String, Object>of("kind", "review", "repository", "../private", "number", 7),
                    Map.<String, Object>of("kind", "review", "repository", "other/private", "number", 7),
                    Map.<String, Object>of("kind", "review", "repository", "acme/private", "number", 8),
                    Map.<String, Object>of("kind", "review", "repository", "acme/private", "number", 0.5))) {
                assertThrows(RuntimeException.class, () -> stub.source.stage(TaskFixtures.task(task), attempt));
            }
            final var context = attempt.resolve("workspace/github-context.json");
            Files.writeString(context, Files.readString(context).replace(HEAD, "main"));
            assertThrows(IllegalStateException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
            assertTrue(stub.requests.isEmpty());
        }
    }

    @Test
    void refusesExistingTargetsAndSymbolicAncestorsAndContext() throws Exception {
        try (final var stub = new Stub(path -> normal(path, SOURCE))) {
            final var attempt = attempt(stub, "links");
            final var workspace = attempt.resolve("workspace");
            final var outside = Files.createDirectory(temporary.resolve("outside"));
            for (final var name : List.of("repository", "github-source.json")) {
                final var link = workspace.resolve(name);
                Files.createSymbolicLink(link, outside);
                assertThrows(IOException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
                Files.delete(link);
            }
            Files.move(workspace.resolve("github-context.json"), outside.resolve("context"));
            Files.createSymbolicLink(workspace.resolve("github-context.json"), outside.resolve("context"));
            assertThrows(IOException.class, () -> stub.source.stage(TaskFixtures.task(TASK), attempt));
            final var alias = temporary.resolve("alias");
            Files.createSymbolicLink(alias, attempt);
            assertThrows(IOException.class, () -> stub.source.stage(TaskFixtures.task(TASK), alias));
            assertTrue(stub.requests.isEmpty());
            assertTrue(Files.isRegularFile(outside.resolve("context")));
        }
    }

    private Path attempt(final Stub stub, final String name) throws IOException {
        final var attempt = Files.createTempDirectory(temporary, name);
        final var workspace = Files.createDirectory(attempt.resolve("workspace"));
        Files.writeString(
                workspace.resolve("github-context.json"),
                stub.json.toString(new GithubContextSnapshot.Snapshot(
                        1,
                        "acme/private",
                        7,
                        new GithubContextSnapshot.Pull(null, null, new GithubContextSnapshot.Ref(HEAD), null),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of())));
        return attempt;
    }

    private static void absent(final Path attempt) {
        assertFalse(Files.exists(attempt.resolve("workspace/repository")));
        assertFalse(Files.exists(attempt.resolve("workspace/github-source.json")));
    }

    private static String normal(final String path, final byte[] bytes) {
        if (path.equals(PREFIX + "commits/" + HEAD)) {
            return "{\"sha\":\"" + HEAD + "\",\"tree\":{\"sha\":\"" + TREE
                    + "\",\"url\":\"https://attacker.invalid/steal\"}}";
        }
        if (path.equals(PREFIX + "trees/" + TREE + "?recursive=1")) {
            return tree(entry("src", "tree", "040000", 0, TREE) + ","
                    + entry("src/Example.java", "blob", "100644", bytes.length, hash(bytes)));
        }
        if (path.equals(PREFIX + "blobs/" + hash(bytes))) {
            return blob(bytes);
        }
        return "{}";
    }

    private static String regular() {
        return entry("src/Example.java", "blob", "100644", SOURCE.length, BLOB);
    }

    private static String entry(
            final String path, final String type, final String mode, final long size, final String sha) {
        return "{\"path\":\"" + path + "\",\"type\":\"" + type + "\",\"mode\":\"" + mode + "\",\"size\":" + size
                + ",\"sha\":\"" + sha + "\"}";
    }

    private static String many(final int count, final int size, final String type, final String mode) {
        final var result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(entry("file-" + i, type, mode, size, size == 0 ? hash(new byte[0]) : BLOB));
        }
        return result.toString();
    }

    private static String tree(final String entries) {
        return "{\"sha\":\"" + TREE + "\",\"truncated\":false,\"tree\":[" + entries + "]}";
    }

    private static String blob(final byte[] bytes) {
        return "{\"sha\":\"" + hash(bytes) + "\",\"size\":" + bytes.length + ",\"encoding\":\"base64\",\"content\":\""
                + Base64.getEncoder().encodeToString(bytes) + "\"}";
    }

    private static String hash(final byte[] bytes) {
        try {
            final var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (final Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String escape(final String text) {
        return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\0", "\\u0000");
    }

    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        private final JsonMapper json = BotJsonMapper.create(key -> Optional.empty());
        private final GitHubClient client;
        private final GithubPrivateContext source;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private final List<String> authorizations = new CopyOnWriteArrayList<>();
        private int status = 200;
        private boolean next;

        private Stub(final Function<String, String> handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            final var base =
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            server.createContext("/", exchange -> {
                try (exchange) {
                    final var path = exchange.getRequestURI().toString();
                    requests.add(path);
                    methods.add(exchange.getRequestMethod());
                    authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    if (next) {
                        exchange.getResponseHeaders().add("Link", "<" + base + "/arbitrary>; rel=\"next\"");
                    }
                    exchange.getResponseHeaders().add("Location", "https://attacker.invalid/steal");
                    final var bytes = handler.apply(path).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            client = new GitHubClient(base, json, () -> "host-secret", BotTestConfiguration.CLOCK);
            source = new GithubPrivateContext(client, json);
        }

        @Override
        public void close() {
            client.close();
            json.close();
            server.stop(0);
        }
    }
}
