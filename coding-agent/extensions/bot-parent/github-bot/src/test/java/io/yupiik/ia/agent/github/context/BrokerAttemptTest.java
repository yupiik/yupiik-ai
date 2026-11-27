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
import io.yupiik.ia.agent.github.configuration.ContextConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.PromptConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.model.TaskDocument;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = GithubConfigurationTestModule.class)
class BrokerAttemptTest {
    @Fusion
    private PromptConfiguration prompts;

    @Fusion
    private GithubExecutorConfiguration executorRoot;

    private static final WorkerOptions.Source SOURCE =
            new WorkerOptions.Source(WorkerOptions.SourceMode.broker, "", 1000, 536870912L, 120);
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);

    @TempDir
    Path home;

    @Test
    void lazyReviewStagesOnlyPinnedMetadataAndFetchesDiscussionPagesOnDemand() throws Exception {
        try (final var fixture = new Fixture()) {
            final var task = task("review", 1, "yca-task-1");
            final Path storage;
            final ContextService service;
            try (final var broker =
                    fixture.open(task, fixture.configuration(home.resolve("private")), BotTestConfiguration.CLOCK)) {
                storage = Path.of((String) broker.storageBinding(task).get("path"));
                service = broker.service();
                assertEquals(
                        List.of("/repos/owner/repo", "/repos/owner/repo/pulls/7", "/repos/owner/repo"), fixture.calls);
                assertEquals("lazy", service.metadata().get("source"));
                assertEquals(HEAD, service.metadata().get("headSha"));
                assertEquals(BASE, service.metadata().get("baseSha"));
                assertFalse(storage.startsWith(fixture.attempt));
                assertFalse(fixture.attempt.startsWith(storage));
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(storage));
                assertEquals(
                        PosixFilePermissions.fromString("rwx------"),
                        Files.getPosixFilePermissions(storage.getParent()));
                assertEquals("yca-task-1", Files.readString(storage.resolve(".owner")));
                assertEquals(
                        Map.of("path", storage.toString(), "taskId", "task", "attempt", 1, "runtime", "yca-task-1"),
                        broker.storageBinding(task));
                assertEquals(
                        Set.of("YCA_CONTEXT_TOKEN", "YCA_CONTEXT_GIT_TOKEN"),
                        broker.credentials().keySet());
                assertNotEquals(
                        broker.credentials().get("YCA_CONTEXT_TOKEN"),
                        broker.credentials().get("YCA_CONTEXT_GIT_TOKEN"));
                new AttemptPreparation(fixture.json, prompts, executorRoot.shared())
                        .stageBroker(
                                fixture.attempt,
                                broker.endpoint(),
                                service.primaryRepository(),
                                service.metadata(),
                                SOURCE,
                                null);
                final var visible = Files.readString(fixture.attempt.resolve("configuration.json"))
                        + Files.readString(fixture.attempt.resolve("workspace/github-context.json"))
                        + Files.readString(fixture.attempt.resolve("workspace/github-source-broker.sh"));
                assertFalse(visible.contains(storage.toString()));
                assertFalse(visible.contains("host-secret"));
                for (final var secret : broker.credentials().values()) {
                    assertFalse(visible.contains(secret));
                }
                assertFalse(Files.exists(fixture.attempt.resolve("workspace/repository")));
                assertFalse(Files.exists(fixture.attempt.resolve("workspace/github-source.json")));
                assertFalse(Files.exists(fixture.gitCalls));
                final var read = service.call(Map.of("operation", "git", "argv", List.of("log", "-1")));
                assertTrue(fixture.json.toString(read).contains("SOURCE_REQUIRED"));
                assertFalse(Files.exists(fixture.gitCalls));
                service.call(Map.of("operation", "reviews", "page", 2));
                assertEquals("/repos/owner/repo/pulls/7/reviews?per_page=100&page=2", fixture.calls.getLast());
                final int count = fixture.calls.size();
                assertThrows(
                        IllegalArgumentException.class, () -> service.call(Map.of("operation", "reviews", "page", 11)));
                assertEquals(count, fixture.calls.size());
            }
            assertFalse(Files.exists(storage));
            assertThrows(IllegalStateException.class, service::metadata);
        }
    }

    @Test
    void injectedClockExpiresAuthorityWithoutChangingSystemProperties() throws Exception {
        try (final var fixture = new Fixture()) {
            final var now = new AtomicReference<>(BotTestConfiguration.CLOCK.instant());
            final var clock = new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(final ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now.get();
                }
            };
            try (final var broker = fixture.open(
                    task("solve", 1, "yca-task-1"), fixture.configuration(home.resolve("private")), clock)) {
                assertEquals(HEAD, broker.service().metadata().get("headSha"));
                final int calls = fixture.calls.size();
                now.set(now.get().plusSeconds(60));
                assertThrows(IllegalStateException.class, () -> broker.service().call(Map.of("operation", "summary")));
                assertEquals(calls, fixture.calls.size());
            }
        }
    }

    @Test
    void rejectsSharedMountsSymlinksAndNonPrivateRootsBeforeAnyUpstreamCall() throws Exception {
        try (final var fixture = new Fixture()) {
            for (final var root : List.of(
                    fixture.attempt.resolve("private"),
                    fixture.attempt.getParent(),
                    fixture.attempt.getParent().resolve("other-private"))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.open(
                                task("solve", 1, "yca-task-1"),
                                fixture.configuration(root),
                                BotTestConfiguration.CLOCK));
            }
            final var publicRoot = Files.createDirectory(home.resolve("public"));
            Files.setPosixFilePermissions(publicRoot, PosixFilePermissions.fromString("rwxr-xr-x"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.open(
                            task("solve", 1, "yca-task-1"),
                            fixture.configuration(publicRoot),
                            BotTestConfiguration.CLOCK));
            final var privateRoot = Files.createDirectory(home.resolve("private"));
            final var symbolic = Files.createSymbolicLink(home.resolve("symbolic"), privateRoot);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.open(
                            task("solve", 1, "yca-task-1"),
                            fixture.configuration(symbolic.resolve("child")),
                            BotTestConfiguration.CLOCK));
            assertTrue(fixture.calls.isEmpty());
        }
    }

    @Test
    void exactPositiveAttemptRuntimeBindingIsMandatory() throws Exception {
        try (final var fixture = new Fixture()) {
            for (final var task : List.of(
                    task("solve", 0, "yca-task-0"),
                    task("solve", 1, "yca-task-2"),
                    task("solve", 1, "yca-another-1"))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.open(
                                task, fixture.configuration(home.resolve("private")), BotTestConfiguration.CLOCK));
            }
            assertTrue(fixture.calls.isEmpty());
            try (final var entries = Files.list(home.resolve("private"))) {
                assertEquals(0, entries.count());
            }
        }
    }

    @Test
    void failedMetadataAcquisitionRemovesNewPrivateAttemptDirectory() throws Exception {
        try (final var fixture = new Fixture()) {
            final var invalid = TaskFixtures.task(Map.of(
                    "id",
                    "task",
                    "attempts",
                    1,
                    "runtimeId",
                    "yca-task-1",
                    "repository",
                    "owner/repo",
                    "number",
                    7,
                    "kind",
                    "solve",
                    "strictSolveBaseSha",
                    "invalid"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.open(
                            invalid, fixture.configuration(home.resolve("private")), BotTestConfiguration.CLOCK));
            try (final var entries = Files.list(home.resolve("private"))) {
                assertEquals(0, entries.count());
            }
        }
    }

    @Test
    void recoveryBindingRoundTripsThroughTaskStoreWireAndCleanupIsIdempotent() throws Exception {
        final var root = ownedRoot(home.resolve("private/context-task-1-recovery"), "yca-task-1");
        final var task = recovery(root, 1, "task", "yca-task-1");
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var restored = json.fromString(TaskDocument.class, json.toString(task));
            cleanup(restored);
            assertFalse(Files.exists(root));
            assertDoesNotThrow(() -> cleanup(restored));
        }
    }

    @Test
    void recoveryRejectsSymlinkMarkerAndTaskMismatchWithoutDeletingAnything() throws Exception {
        final var root = ownedRoot(home.resolve("private/context-task-1-recovery"), "yca-task-1");
        assertThrows(IOException.class, () -> cleanup(recovery(root, 1, "other", "yca-task-1")));
        assertTrue(Files.exists(root.resolve(".owner")));
        Files.writeString(root.resolve(".owner"), "yca-task-2");
        assertThrows(IOException.class, () -> cleanup(recovery(root, 1, "task", "yca-task-1")));
        final var outside = Files.writeString(home.resolve("outside-marker"), "yca-task-1");
        Files.delete(root.resolve(".owner"));
        Files.createSymbolicLink(root.resolve(".owner"), outside);
        assertThrows(IOException.class, () -> cleanup(recovery(root, 1, "task", "yca-task-1")));
        assertEquals("yca-task-1", Files.readString(outside));
        Files.delete(root.resolve(".owner"));
        Files.writeString(root.resolve(".owner"), "yca-task-1");
        final var link = Files.createSymbolicLink(home.resolve("linked-private"), root.getParent());
        assertThrows(
                IOException.class, () -> cleanup(recovery(link.resolve(root.getFileName()), 1, "task", "yca-task-1")));
        assertTrue(Files.exists(root));
    }

    @Test
    void recoveryRejectsNonPrivateDirectoryWithoutRemovingIt() throws Exception {
        final var root = ownedRoot(home.resolve("private/context-task-1-recovery"), "yca-task-1");
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class, () -> cleanup(recovery(root, 1, "task", "yca-task-1")));
        assertTrue(Files.exists(root.resolve(".owner")));
    }

    @Test
    void recoveryRejectsBindingForDifferentAttempt() throws Exception {
        final var root = ownedRoot(home.resolve("private/context-task-2-recovery"), "yca-task-2");
        // The task is attempt 1, while the path, marker and forged binding consistently claim attempt 2.
        assertThrows(IOException.class, () -> cleanup(recovery(root, 2, "task", "yca-task-2")));
        assertTrue(Files.exists(root));
    }

    @Test
    void recoveryRejectsSelfConsistentButNonOwnedPathOutsideStorageRoot() throws Exception {
        final var root = ownedRoot(home.resolve("unrelated/context-task-1-forged"), "yca-task-1");
        Files.writeString(root.resolve("valuable-data"), "must survive");
        // A filename and writable marker are not proof this directory was allocated by the broker.
        assertThrows(IOException.class, () -> cleanup(recovery(root, 1, "task", "yca-task-1")));
        assertEquals("must survive", Files.readString(root.resolve("valuable-data")));
    }

    private void cleanup(final TaskDocument task) throws IOException {
        BrokerAttempt.cleanup(task, home.resolve("private"));
    }

    private Path ownedRoot(final Path root, final String runtime) throws IOException {
        Files.createDirectories(
                root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(root.resolve(".owner"), runtime);
        Files.setPosixFilePermissions(root.resolve(".owner"), PosixFilePermissions.fromString("rw-------"));
        return root;
    }

    private TaskDocument recovery(
            final Path path, final int bindingAttempt, final String bindingTask, final String runtime) {
        final var values = new LinkedHashMap<String, Object>(TaskFixtures.map(task("solve", 1, "yca-task-1")));
        // TaskDocument.recovery is @JsonOthers: recovery extensions are top-level wire fields.
        values.put(
                "contextStorage",
                Map.of("path", path.toString(), "taskId", bindingTask, "attempt", bindingAttempt, "runtime", runtime));
        return TaskFixtures.task(values);
    }

    private TaskDocument task(final String kind, final int attempt, final String runtime) {
        return TaskFixtures.task(Map.of(
                "id",
                "task",
                "attempts",
                attempt,
                "runtimeId",
                runtime,
                "repository",
                "owner/repo",
                "number",
                7,
                "kind",
                kind,
                "strictSolveBaseSha",
                HEAD));
    }

    private final class Fixture implements AutoCloseable {
        private final JsonMapper json = BotJsonMapper.create(key -> Optional.empty());
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final HttpServer server;
        private final GitHubClient github;
        private final Path attempt;
        private final Path gitCalls = home.resolve("git-calls");
        private final Path git = home.resolve("fake-git");

        private Fixture() throws Exception {
            Files.writeString(git, "#!/bin/sh\nprintf 'unexpected git call\\n' >> '" + gitCalls + "'\nexit 97\n");
            Files.setPosixFilePermissions(git, PosixFilePermissions.fromString("rwx------"));
            attempt = new AttemptPreparation(json, prompts, executorRoot.shared())
                    .prepare(Files.writeString(home.resolve("trusted.json"), "{}"), home.resolve("attempts"));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.add(exchange.getRequestURI().toString());
                final var repository = Map.of("id", 12, "full_name", "owner/repo", "archived", false);
                final Object response =
                        switch (exchange.getRequestURI().getPath()) {
                            case "/repos/owner/repo" -> repository;
                            case "/repos/owner/repo/pulls/7" ->
                                Map.of(
                                        "base",
                                        Map.of("sha", BASE, "repo", repository),
                                        "head",
                                        Map.of("sha", HEAD, "repo", repository));
                            case "/repos/owner/repo/pulls/7/reviews" -> List.of();
                            default ->
                                Map.of("unexpected", exchange.getRequestURI().toString());
                        };
                final var bytes = json.toString(response).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            github = new GitHubClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    json,
                    () -> "host-secret",
                    BotTestConfiguration.CLOCK);
        }

        private ContextConfiguration configuration(final Path root) {
            return new ContextConfiguration(
                    "127.0.0.1",
                    0,
                    "https://worker.example",
                    root.toString(),
                    git.toString(),
                    "",
                    256,
                    1073741824L,
                    67108864L);
        }

        private BrokerAttempt open(final TaskDocument task, final ContextConfiguration conf, final Clock clock)
                throws IOException {
            return new BrokerAttempt(
                    conf,
                    SOURCE,
                    github,
                    json,
                    task,
                    home,
                    attempt,
                    attempt.getParent(),
                    clock,
                    Duration.ofSeconds(60));
        }

        @Override
        public void close() throws IOException {
            github.close();
            server.stop(0);
            json.close();
            AttemptPreparation.delete(attempt);
        }
    }
}
