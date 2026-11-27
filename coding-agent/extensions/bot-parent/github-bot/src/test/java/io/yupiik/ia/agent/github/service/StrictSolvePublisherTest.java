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
import io.yupiik.fusion.json.JsonException;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrictSolvePublisherTest {
    private static final String BASE = "a".repeat(40);
    private static final String TREE = "b".repeat(40);
    private static final String BLOB = "c".repeat(40);
    private static final String NEW_TREE = "d".repeat(40);
    private static final String COMMIT = "e".repeat(40);
    private static final String URL = "https://github.com/up/repo/pull/42";

    @TempDir
    Path home;

    @Test
    void publishesWithDurableIntentsAndPreservesExecutableModeAndAttempt() throws Exception {
        try (final var fixture = new Fixture()) {
            assertEquals(URL, fixture.publish());
            assertEquals(
                    List.of("blob-0Intent", "treeIntent", "commitIntent", "refIntent", "prIntent"), fixture.intents);
            assertEquals(5, fixture.posts.size());
            final var tree = fixture.posts.get(1);
            assertEquals(TREE, tree.get("base_tree"));
            final var entry = TaskStore.map(((List<?>) tree.get("tree")).getFirst());
            assertEquals("100755", entry.get("mode"));
            assertEquals("blob", entry.get("type"));
            assertEquals("run.sh", entry.get("path"));
            assertEquals(BLOB, entry.get("sha"));
            assertEquals(List.of(BASE), fixture.posts.get(2).get("parents"));
            assertEquals(
                    "refs/heads/github-bot/" + fixture.id, fixture.posts.get(3).get("ref"));
            assertEquals("bot:github-bot/" + fixture.id, fixture.posts.get(4).get("head"));
            assertEquals("main", fixture.posts.get(4).get("base"));
            assertEquals("repo", fixture.posts.get(4).get("head_repo"));
            assertEquals(Boolean.FALSE, fixture.posts.get(4).get("maintainer_can_modify"));
            final var body = (String) fixture.posts.get(4).get("body");
            assertEquals(
                    PullRequestRenderer.render(
                            TaskFixtures.result(fixture.result), "<!-- yca-task:" + fixture.id + " -->"),
                    body);
            assertTrue(body.contains("## Summary"));
            assertTrue(body.contains("## Changes"));
            assertTrue(body.contains("## Validation / test evidence"));
            assertTrue(body.contains("## Risks / follow-up"));
            assertEquals(fingerprint(body), fixture.publication().get("fingerprint"));
            final var saved = fixture.task();
            assertEquals("retained-attempt", saved.get("attemptDirectory"));
            assertEquals(fixture.result, saved.get("result"));
            assertEquals(URL, fixture.publication().get("prUrl"));
            assertEquals(URL, fixture.publish());
            assertEquals(5, fixture.posts.size(), "Completed publication cannot repeat any POST");
        }
    }

    @Test
    void renderedEvidenceIsBoundBeforeWritesAndCannotChangeDuringRecovery() throws Exception {
        for (final var field : List.of("body", "summary", "tests")) {
            try (final var fixture = new Fixture()) {
                fixture.fail = "prIntent";
                assertThrows(IllegalStateException.class, fixture::publish);
                final var fingerprint = fixture.publication().get("fingerprint");
                final var changed = new LinkedHashMap<>(fixture.result);
                if ("body".equals(field)) {
                    final var solve = new LinkedHashMap<>(TaskStore.map(changed.get("strictSolve")));
                    solve.put("body", "Different supplied description");
                    changed.put("strictSolve", solve);
                } else {
                    changed.put(field, "tests".equals(field) ? List.of("New test claim") : "New summary");
                }
                fixture.result = changed;
                fixture.listed = true;
                final int reads = fixture.gets.size();
                assertThrows(IllegalStateException.class, fixture::publish, field);
                assertEquals(reads, fixture.gets.size(), field);
                assertEquals(5, fixture.posts.size(), field);
                assertEquals(fingerprint, fixture.publication().get("fingerprint"), field);
            }
        }
    }

    @Test
    void oversizedRenderedEvidenceCannotWriteOrBindPublication() throws Exception {
        try (final var fixture = new Fixture()) {
            final var result = new LinkedHashMap<>(fixture.result);
            result.put("tests", List.of("x".repeat(65000)));
            fixture.result = result;
            assertThrows(IllegalArgumentException.class, fixture::publish);
            assertTrue(fixture.posts.isEmpty());
            assertNull(fixture.task().get("strictSolvePublication"));
        }
    }

    @Test
    void uncertainPrIsReconciledAcrossRestartIncludingClosedPrWithoutAnotherPost() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.fail = "prIntent";
            assertThrows(IllegalStateException.class, fixture::publish);
            assertTrue(fixture.publication().containsKey("prIntent"));
            assertFalse(fixture.publication().containsKey("prUrl"));
            final var fingerprint = fixture.publication().get("fingerprint");
            assertEquals(fingerprint((String) fixture.posts.getLast().get("body")), fingerprint);
            fixture.fail = null;
            fixture.listed = true;
            fixture.stale = true; // Reconciliation must work after the upstream advances.
            final int reads = fixture.gets.size();
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.publisher.publish(
                            TaskFixtures.task(fixture.task()), TaskFixtures.result(fixture.result), "other"));
            assertEquals(reads, fixture.gets.size(), "A different invocation owner must fail before reconciliation");
            final var restarted = new StrictSolvePublisher(
                    fixture.github,
                    new TaskStore(BotTestConfiguration.home(fixture.directory), fixture.json),
                    fixture.json);
            assertEquals(
                    URL,
                    restarted.publish(TaskFixtures.task(fixture.task()), TaskFixtures.result(fixture.result), "bot"));
            assertEquals(5, fixture.posts.size());
            assertEquals(fingerprint, fixture.publication().get("fingerprint"));
            assertTrue(fixture.gets.stream()
                    .anyMatch(path -> path.contains("state=all")
                            && path.contains("head=bot%3Agithub-bot%2F")
                            && path.contains("base=main")));
        }
    }

    @Test
    void uncertainPrWithoutVerifiedMatchNeverRepeatsPost() throws Exception {
        for (final var mismatch : List.of("absent", "headRepo", "headSha", "base", "author", "marker")) {
            try (final var fixture = new Fixture()) {
                fixture.fail = "prIntent";
                assertThrows(IllegalStateException.class, fixture::publish);
                fixture.fail = null;
                fixture.listed = !"absent".equals(mismatch);
                fixture.mismatch = mismatch;
                assertThrows(IllegalStateException.class, fixture::publish, mismatch);
                assertThrows(IllegalStateException.class, fixture::publish, mismatch);
                assertEquals(5, fixture.posts.size(), mismatch);
            }
        }
    }

    @Test
    void everyAmbiguousWriteStopsAndCannotBeReplayedAfterRestart() throws Exception {
        for (final var stage : List.of("blob-0Intent", "treeIntent", "commitIntent", "refIntent")) {
            try (final var fixture = new Fixture()) {
                fixture.fail = stage;
                assertThrows(IllegalStateException.class, fixture::publish);
                final int count = fixture.posts.size();
                assertTrue(
                        stage.startsWith("blob-")
                                ? Boolean.TRUE.equals(TaskStore.map(TaskStore.map(
                                                        fixture.publication().get("blobs"))
                                                .get("blob-0"))
                                        .get("intent"))
                                : fixture.publication().containsKey(stage));
                fixture.fail = null;
                assertThrows(
                        IllegalStateException.class,
                        () -> new StrictSolvePublisher(
                                        fixture.github,
                                        new TaskStore(BotTestConfiguration.home(fixture.directory), fixture.json),
                                        fixture.json)
                                .publish(
                                        TaskFixtures.task(fixture.task()), TaskFixtures.result(fixture.result), "bot"));
                assertEquals(count, fixture.posts.size(), stage);
                assertEquals("retained-attempt", fixture.task().get("attemptDirectory"));
                assertEquals(fixture.result, fixture.task().get("result"));
            }
        }
    }

    @Test
    void changedForkUpstreamBaseOrResultCannotRebindIntent() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.fail = "blob-0Intent";
            assertThrows(IllegalStateException.class, fixture::publish);
            final int count = fixture.posts.size();
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.publisher.publish(
                            TaskFixtures.task(fixture.task()), TaskFixtures.result(fixture.result), "other"));
            final var changed = new LinkedHashMap<>(TaskStore.map(fixture.result.get("strictSolve")));
            changed.put("title", "Different result");
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.publisher.publish(
                            TaskFixtures.task(fixture.task()),
                            TaskFixtures.result(Map.of("strictSolve", changed)),
                            "bot"));
            fixture.store.transaction(state -> {
                state.task(fixture.id).repository("other/repo");
                return null;
            });
            assertThrows(IllegalStateException.class, fixture::publish);
            fixture.store.transaction(state -> {
                state.task(fixture.id).repository("up/repo");
                state.task(fixture.id).strictSolveBaseBranch("next");
                return null;
            });
            assertThrows(IllegalStateException.class, fixture::publish);
            assertEquals(count, fixture.posts.size());
        }
    }

    @Test
    void staleBaseWrongForkAndIncompleteOrSymbolicBaseTreesCannotWrite() throws Exception {
        for (final var mode : List.of("stale", "fork", "truncated", "symlink", "submodule", "ancestor")) {
            try (final var fixture = new Fixture()) {
                fixture.stale = "stale".equals(mode);
                fixture.wrongFork = "fork".equals(mode);
                fixture.truncated = "truncated".equals(mode);
                fixture.baseMode = switch (mode) {
                    case "symlink" -> "120000";
                    case "submodule" -> "160000";
                    default -> "100755";
                };
                if ("ancestor".equals(mode)) {
                    fixture.result = result(List.of(change("run.sh/child", "", false)));
                }
                assertThrows(RuntimeException.class, fixture::publish, mode);
                assertTrue(fixture.posts.isEmpty(), mode);
            }
        }
    }

    @Test
    void untrustedBaseCannotOverridePersistedHostAuthority() throws Exception {
        try (final var fixture = new Fixture()) {
            final var solve = new LinkedHashMap<>(TaskStore.map(fixture.result.get("strictSolve")));
            solve.put("baseSha", "f".repeat(40));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.publisher.publish(
                            TaskFixtures.task(fixture.task()),
                            TaskFixtures.result(Map.of("strictSolve", solve)),
                            "bot"));
            final var caller = fixture.task();
            caller.put("strictSolveBaseSha", "f".repeat(40));
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.publisher.publish(
                            TaskFixtures.task(caller), TaskFixtures.result(fixture.result), "bot"));
            fixture.store.transaction(state -> {
                state.task(fixture.id).strictSolveBaseSha(null);
                return null;
            });
            assertThrows(IllegalArgumentException.class, fixture::publish);
            assertTrue(fixture.posts.isEmpty());
        }
    }

    @Test
    void newFilesUseRegularModeAndTruncatedTreesDoNotFallBackToGuessedModes() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.result = result(List.of(change("new.txt", "", false)));
            assertEquals(URL, fixture.publish());
            final var entry = TaskStore.map(((List<?>) fixture.posts.get(1).get("tree")).getFirst());
            assertEquals("100644", entry.get("mode"));
        }
    }

    @Test
    void deletionEitherUsesExplicitNullOrFailsBeforeAnyWrite() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.result = result(List.of(change("run.sh", "", true)));
            final var probe = new GitHubClient.TreeRequest(
                    TREE, List.of(new GitHubClient.TreeEntry("run.sh", "100755", "blob", null)));
            if (fixture.json.toString(probe).contains("\"sha\":null")) {
                assertEquals(URL, fixture.publish());
                assertEquals(4, fixture.posts.size(), "Deleting a file never creates a blob");
                final var entry =
                        TaskStore.map(((List<?>) fixture.posts.getFirst().get("tree")).getFirst());
                assertTrue(entry.containsKey("sha"));
                assertNull(entry.get("sha"));
            } else {
                final var failure = assertThrows(IllegalStateException.class, fixture::publish);
                assertTrue(failure.getMessage().contains("serialize nulls"));
                assertTrue(fixture.posts.isEmpty());
            }
        }
    }

    @Test
    void rejectsUnsafePathsInvalidBase64FlagsModesAndSizeLimitsBeforeWriting() throws Exception {
        final var invalid = new ArrayList<List<Map<String, Object>>>();
        for (final var path : List.of(
                "../x",
                "/x",
                "a/../x",
                ".git/config",
                "a/.GIT/config",
                "a\\b",
                "a//b",
                "a/./b",
                "a/",
                "a\u0000b",
                "C:/x",
                "a.",
                "a ",
                ".github/workflows",
                ".GITHUB/workflows/ci.yml",
                ".git~1/x")) {
            invalid.add(List.of(change(path, "", false)));
        }
        invalid.add(List.of(change("a", "not base64", false)));
        invalid.add(List.of(Map.of("content", "", "delete", false)));
        invalid.add(List.of(change("a", "YQ", false))); // Non-canonical base64.
        invalid.add(List.of(change("a", "YQ==", true)));
        invalid.add(List.of(Map.of("path", "a", "content", "", "delete", false, "mode", "120000")));
        invalid.add(List.of(change("a", "", false), change("a", "", false)));
        invalid.add(List.of(change("a", "", false), change("a/b", "", false)));
        invalid.add(List.of());
        invalid.add(IntStream.range(0, 101)
                .mapToObj(i -> change("f" + i, "", false))
                .toList());
        final var large = Base64.getEncoder().encodeToString(new byte[1024 * 1024 + 1]);
        invalid.add(List.of(change("a", large, false)));
        final var maximum = Base64.getEncoder().encodeToString(new byte[1024 * 1024]);
        invalid.add(IntStream.range(0, 5)
                .mapToObj(i -> change("f" + i, maximum, false))
                .toList());
        try (final var fixture = new Fixture()) {
            for (final var changes : invalid) {
                fixture.result = result(changes);
                assertThrows(IllegalArgumentException.class, fixture::publish);
            }
            fixture.result = result(List.of(Map.of("path", "a", "content", "", "delete", "false")));
            assertThrows(JsonException.class, fixture::publish);
            assertTrue(fixture.posts.isEmpty());
            assertTrue(fixture.gets.isEmpty());
        }
    }

    @Test
    void targetedTreesArePinnedCachedAndPreserveExecutableMode() throws Exception {
        try (final var f = new Fixture()) {
            final var child = "1".repeat(40);
            f.directories = Map.of(
                    TREE,
                    List.of(
                            Map.of("path", "src", "mode", "040000", "type", "tree", "sha", child),
                            Map.of("path", "unrelated", "mode", "040000", "type", "tree", "sha", "2".repeat(40))),
                    child,
                    List.of(Map.of("path", "exec", "mode", "100755", "type", "blob", "sha", BLOB)));
            f.result = result(List.of(change("src/exec", "eA==", false), change("src/new", "eQ==", false)));
            assertEquals(URL, f.publish());
            assertEquals(
                    List.of("/repos/up/repo/git/trees/" + TREE, "/repos/up/repo/git/trees/" + child),
                    f.gets.stream().filter(p -> p.contains("/git/trees/")).toList());
            final var entries = (List<?>) f.posts.stream()
                    .filter(p -> p.containsKey("base_tree"))
                    .findFirst()
                    .orElseThrow()
                    .get("tree");
            assertEquals("100755", TaskStore.map(entries.get(0)).get("mode"));
            assertEquals("100644", TaskStore.map(entries.get(1)).get("mode"));
        }
    }

    @Test
    void newPathsCannotTraverseSymlinkOrBlobAncestors() throws Exception {
        for (final var mode : List.of("120000", "100644")) {
            try (final var f = new Fixture()) {
                f.directories = Map.of(TREE, List.of(Map.of("path", "src", "mode", mode, "type", "blob", "sha", BLOB)));
                f.result = result(List.of(change("src/new", "eA==", false)));
                assertThrows(IllegalArgumentException.class, f::publish);
                assertTrue(f.posts.isEmpty());
                assertEquals(
                        1,
                        f.gets.stream().filter(p -> p.contains("/git/trees/")).count());
            }
        }
    }

    @Test
    void newDirectoryAncestorsAreCreatedWithoutRecursiveTreeQueries() throws Exception {
        try (final var f = new Fixture()) {
            f.directories = Map.of(TREE, List.of());
            f.result = result(List.of(change("new/package/file.java", "eA==", false)));
            assertEquals(URL, f.publish());
            assertEquals(
                    List.of("/repos/up/repo/git/trees/" + TREE),
                    f.gets.stream().filter(p -> p.contains("/git/trees/")).toList());
            final var entries = (List<?>) f.posts.stream()
                    .filter(p -> p.containsKey("base_tree"))
                    .findFirst()
                    .orElseThrow()
                    .get("tree");
            assertEquals(1, entries.size());
            assertEquals(
                    "new/package/file.java", TaskStore.map(entries.getFirst()).get("path"));
            assertEquals("100644", TaskStore.map(entries.getFirst()).get("mode"));
        }
    }

    @Test
    void missingNestedAncestorsAllowNewFilesWhileExistingSiblingKeepsExecutableMode() throws Exception {
        try (final var f = new Fixture()) {
            final var child = "1".repeat(40);
            f.directories = Map.of(
                    TREE,
                    List.of(Map.of("path", "src", "mode", "040000", "type", "tree", "sha", child)),
                    child,
                    List.of(Map.of("path", "exec", "mode", "100755", "type", "blob", "sha", BLOB)));
            f.result = result(List.of(
                    change("src/new/package/file.java", "eA==", false),
                    change("src/new/other.java", "eQ==", false),
                    change("src/exec", "eg==", false)));
            assertEquals(URL, f.publish());
            assertEquals(
                    List.of("/repos/up/repo/git/trees/" + TREE, "/repos/up/repo/git/trees/" + child),
                    f.gets.stream().filter(p -> p.contains("/git/trees/")).toList());
            final var entries = (List<?>) f.posts.stream()
                    .filter(p -> p.containsKey("base_tree"))
                    .findFirst()
                    .orElseThrow()
                    .get("tree");
            assertEquals(3, entries.size());
            final var modes = new LinkedHashMap<String, Object>();
            for (final var entry : entries) {
                final var fields = TaskStore.map(entry);
                modes.put((String) fields.get("path"), fields.get("mode"));
            }
            assertEquals(
                    Map.of("src/new/package/file.java", "100644", "src/new/other.java", "100644", "src/exec", "100755"),
                    modes);
        }
    }

    @Test
    void directoryBudgetFailsBeforeAnyMutationAndNeverUsesRecursiveFallback() throws Exception {
        try (final var f = new Fixture()) {
            final var directories = new LinkedHashMap<String, List<Map<String, Object>>>();
            final var root = new ArrayList<Map<String, Object>>();
            final var changes = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 100; i++) {
                final var oid = String.format("%040x", i + 1);
                root.add(Map.of("path", "d" + i, "mode", "040000", "type", "tree", "sha", oid));
                directories.put(oid, List.of());
                changes.add(change("d" + i + "/new", "", false));
            }
            directories.put(TREE, root);
            f.directories = directories;
            f.result = result(changes);
            assertThrows(IllegalArgumentException.class, f::publish);
            assertTrue(f.posts.isEmpty());
            assertEquals(
                    100, f.gets.stream().filter(p -> p.contains("/git/trees/")).count());
            assertTrue(f.gets.stream().noneMatch(p -> p.contains("recursive")));
        }
    }

    @Test
    void malformedOrPartialPinnedDirectoriesNeverAuthorizeWrites() throws Exception {
        for (final var malformed : List.of("sha", "truncated", "missing", "duplicate", "path", "oid", "mode", "type")) {
            try (final var f = new Fixture()) {
                f.malformedTree = malformed;
                assertThrows(RuntimeException.class, f::publish, malformed);
                assertTrue(f.posts.isEmpty(), malformed);
            }
        }
    }

    private static String fingerprint(final String body) throws Exception {
        final var digest = MessageDigest.getInstance("SHA-256");
        for (final var field : List.of(BASE, "Fix the issue", body, "run.sh", "ZWNobyBvaw==", "false")) {
            final var bytes = field.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, Object> result(final List<Map<String, Object>> changes) {
        return Map.of(
                "strictSolve",
                Map.of("baseSha", BASE, "title", "Fix the issue", "body", "Summary", "changes", changes));
    }

    private static Map<String, Object> change(final String path, final String content, final boolean delete) {
        return Map.of("path", path, "content", content, "delete", delete);
    }

    private final class Fixture implements AutoCloseable {
        private final JsonMapper wireJson = new JsonMapperImpl(List.of(), key -> Optional.empty());
        private final JsonMapper json = BotJsonMapper.create(key -> Optional.empty());
        private final Path directory = home.resolve(UUID.randomUUID().toString());
        private final TaskStore store = new TaskStore(BotTestConfiguration.home(directory), json);
        private final String id = store.transaction(state -> state.enqueue(
                "test",
                TaskFixtures.task(Map.of(
                        "repository",
                        "up/repo",
                        "strictSolveBaseSha",
                        BASE,
                        "strictSolveBaseBranch",
                        "main",
                        "strictSolveForkOwner",
                        "bot",
                        "attemptDirectory",
                        "retained-attempt"))));
        private final HttpServer server;
        private final GitHubClient github;
        private final StrictSolvePublisher publisher;
        private final List<Map<String, Object>> posts = new CopyOnWriteArrayList<>();
        private final List<String> intents = new CopyOnWriteArrayList<>();
        private final List<String> gets = new CopyOnWriteArrayList<>();
        private Map<String, Object> result = result(List.of(change("run.sh", "ZWNobyBvaw==", false)));
        private volatile String fail;
        private volatile String mismatch;
        private volatile boolean listed;
        private volatile boolean stale;
        private volatile boolean wrongFork;
        private volatile boolean truncated;
        private volatile String baseMode = "100755";
        private Map<String, List<Map<String, Object>>> directories;
        private String malformedTree;

        private Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                final Object response;
                int status = 200;
                if ("POST".equals(exchange.getRequestMethod())) {
                    final var payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    final var request = TaskStore.map(wireJson.fromString(Object.class, payload));
                    posts.add(request);
                    final var stage = path.endsWith("/blobs")
                            ? "blob-0Intent"
                            : path.endsWith("/trees")
                                    ? "treeIntent"
                                    : path.endsWith("/commits")
                                            ? "commitIntent"
                                            : path.endsWith("/refs") ? "refIntent" : "prIntent";
                    // Read state from a separate transaction while the server handles the write.
                    // No matching durable intent means the request is refused, making success impossible.
                    final var persisted = publication();
                    final var intent = stage.startsWith("blob-")
                            ? TaskStore.map(TaskStore.map(persisted.get("blobs"))
                                            .get("blob-0"))
                                    .get("intent")
                            : persisted.get(stage);
                    if (!Boolean.TRUE.equals(intent)) {
                        status = 409;
                    } else {
                        intents.add(stage);
                    }
                    if (stage.equals(fail)) {
                        status = 503;
                    }
                    response = switch (stage) {
                        case "blob-0Intent" -> Map.of("sha", BLOB);
                        case "treeIntent" -> Map.of("sha", NEW_TREE);
                        case "commitIntent" -> Map.of("sha", COMMIT);
                        case "refIntent" -> ref("github-bot/" + id, COMMIT);
                        default -> pr();
                    };
                } else {
                    gets.add(exchange.getRequestURI().toASCIIString());
                    if (path.equals("/user")) {
                        response = Map.of("login", "bot-author");
                    } else if (path.equals("/repos/bot/repo")) {
                        response = Map.of(
                                "fork",
                                true,
                                "full_name",
                                "bot/repo",
                                "parent",
                                Map.of("full_name", wrongFork ? "wrong/repo" : "up/repo"));
                    } else if (path.equals("/repos/up/repo")) {
                        response = Map.of("full_name", "up/repo", "default_branch", "main");
                    } else if (path.contains("/git/commits/")) {
                        response = Map.of("sha", BASE, "tree", Map.of("sha", TREE));
                    } else if (path.contains("/git/trees/")) {
                        final var oid = path.substring(path.lastIndexOf('/') + 1);
                        final var entry = Map.<String, Object>of(
                                "path",
                                "path".equals(malformedTree) ? "a/b" : "run.sh",
                                "mode",
                                "mode".equals(malformedTree) ? "0100644" : baseMode,
                                "type",
                                "type".equals(malformedTree) ? "tree" : "blob",
                                "sha",
                                "oid".equals(malformedTree) ? "bad" : BLOB);
                        final var treeResponse = new LinkedHashMap<String, Object>();
                        treeResponse.put("sha", "sha".equals(malformedTree) ? BLOB : oid);
                        treeResponse.put("truncated", truncated || "truncated".equals(malformedTree));
                        if (!"missing".equals(malformedTree)) {
                            treeResponse.put(
                                    "tree",
                                    directories != null
                                            ? directories.get(oid)
                                            : "duplicate".equals(malformedTree)
                                                    ? List.of(entry, entry)
                                                    : List.of(entry));
                        }
                        response = treeResponse;
                    } else if (path.equals("/repos/up/repo/git/ref/heads/main")) {
                        response = ref("main", stale ? "f".repeat(40) : BASE);
                    } else if (path.contains("/git/ref/heads/github-bot/")) {
                        response = ref("github-bot/" + id, COMMIT);
                    } else if (path.endsWith("/pulls")) {
                        response = listed ? List.of(pr()) : List.of();
                    } else {
                        status = 404;
                        response = Map.of();
                    }
                }
                final var wire = json.toString(response).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, wire.length);
                try (exchange) {
                    exchange.getResponseBody().write(wire);
                }
            });
            server.start();
            github = new GitHubClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    json,
                    () -> "host-secret",
                    BotTestConfiguration.CLOCK);
            publisher = new StrictSolvePublisher(github, store, json);
        }

        private Map<String, Object> pr() {
            return Map.of(
                    "number",
                    42,
                    "state",
                    "closed",
                    "html_url",
                    URL,
                    "body",
                    "Summary\n\n<!-- yca-task:" + ("marker".equals(mismatch) ? "other" : id) + " -->",
                    "user",
                    Map.of("login", "author".equals(mismatch) ? "other" : "bot-author"),
                    "head",
                    Map.of(
                            "ref",
                            "github-bot/" + id,
                            "sha",
                            "headSha".equals(mismatch) ? BASE : COMMIT,
                            "repo",
                            Map.of("full_name", "headRepo".equals(mismatch) ? "other/repo" : "bot/repo")),
                    "base",
                    Map.of("ref", "base".equals(mismatch) ? "next" : "main", "repo", Map.of("full_name", "up/repo")));
        }

        private Map<String, Object> ref(final String branch, final String sha) {
            return Map.of("ref", "refs/heads/" + branch, "object", Map.of("sha", sha));
        }

        private Map<String, Object> task() {
            return store.transaction(
                    state -> new LinkedHashMap<>(TaskFixtures.map(state.task(id).document())));
        }

        private Map<String, Object> publication() {
            return TaskStore.map(task().get("strictSolvePublication"));
        }

        private String publish() {
            return publisher.publish(TaskFixtures.task(task()), TaskFixtures.result(result), "bot");
        }

        @Override
        public void close() {
            github.close();
            server.stop(0);
            json.close();
            wireJson.close();
        }
    }
}
