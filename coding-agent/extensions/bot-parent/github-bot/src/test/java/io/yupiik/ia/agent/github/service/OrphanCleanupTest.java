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

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.command.CleanupCommand;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrphanCleanupTest {
    @TempDir
    Path root;

    private final Instant now = Instant.parse("2026-11-11T12:00:00Z");

    @Nested
    @FusionSupport(modules = CommandModule.class)
    class Command {
        @TempDir
        private static Path root;

        @Fusion
        private GithubExecutorConfiguration executorRoot;

        @Test
        void cleanupCommandUsesInjectedClockForMinimumAge() throws Exception {
            final var home = Files.createDirectory(root.resolve("state"));
            final var attempts = Files.createDirectory(root.resolve("attempts"));
            final var orphan = prepared(attempts, "yca-github-clock");
            final var configuration = BotTestConfiguration.home(home);
            final var execution = executorRoot.shared();
            try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
                final var store = new TaskStore(configuration, json);
                final var options = new CleanupCommand.Options(24, false);
                new CleanupCommand(
                                options,
                                configuration,
                                execution,
                                json,
                                store,
                                Clock.fixed(now.minus(Duration.ofDays(4)), ZoneOffset.UTC))
                        .run();
                assertTrue(Files.exists(orphan));
                new CleanupCommand(options, configuration, execution, json, store, Clock.fixed(now, ZoneOffset.UTC))
                        .run();
                assertFalse(Files.exists(orphan));
            }
        }
    }

    public static class CommandModule extends GithubConfigurationTestModule {
        public CommandModule() {
            super(Map.of(
                    "github.executor.attemptRoot",
                    Command.root.resolve("attempts").toString()));
        }
    }

    @Test
    void deletesOnlyOldUnreferencedNeverLaunchableAttempts() throws Exception {
        final var home = Files.createDirectory(root.resolve("state"));
        final var attempts = Files.createDirectory(root.resolve("attempts"));
        final var old = prepared(attempts, "yca-github-old");
        final var referenced = prepared(attempts, "yca-github-referenced");
        final var launchable = prepared(attempts, "yca-github-launchable");
        Files.delete(launchable.resolve(".prepared-only"));
        final var recent = prepared(attempts, "yca-github-recent");
        Files.setLastModifiedTime(recent, FileTime.from(now));
        final var unknown = prepared(attempts, "unrelated");
        final var outside = Files.writeString(root.resolve("outside"), "preserve");
        Files.createSymbolicLink(old.resolve("escape"), outside);
        Files.setLastModifiedTime(old, FileTime.from(now.minus(Duration.ofDays(3))));
        Files.createSymbolicLink(attempts.resolve("yca-github-link"), root);
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(state ->
                    state.enqueue("invocation", TaskFixtures.task(Map.of("attemptDirectory", referenced.toString()))));
            final var cleanup = new OrphanCleanup(store, home, attempts, Clock.fixed(now, ZoneOffset.UTC));
            assertEquals(
                    List.of("yca-github-old"),
                    cleanup.cleanup(Duration.ofDays(1), true).attempts());
            assertTrue(Files.exists(old));
            assertEquals(
                    List.of("yca-github-old"),
                    cleanup.cleanup(Duration.ofDays(1), false).attempts());
            assertFalse(Files.exists(old));
            for (final var retained : List.of(referenced, launchable, recent, unknown, outside)) {
                assertTrue(Files.exists(retained), retained.toString());
            }
            assertEquals(id, store.transaction(state -> state.receipts().get("invocation")));
            assertTrue(cleanup.cleanup(Duration.ofDays(1), false).attempts().isEmpty());
        }
    }

    @Test
    void fencesDefaultAndAllParallelWorkerConfigurationsAndReleasesPartialFences() throws Exception {
        final var home = Files.createDirectory(root.resolve("state"));
        final var attempts = Files.createDirectory(root.resolve("attempts"));
        final var orphan = prepared(attempts, "yca-github-old");
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var cleanup = new OrphanCleanup(
                    new TaskStore(BotTestConfiguration.home(home), json),
                    home,
                    attempts,
                    Clock.fixed(now, ZoneOffset.UTC));
            for (final var name : List.of("worker.lock", "worker-0.lock", "worker-63.lock")) {
                try (final var channel = FileChannel.open(
                                home.resolve(name), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        final var _ = channel.lock()) {
                    assertThrows(IllegalStateException.class, () -> cleanup.cleanup(Duration.ofDays(1), false));
                    assertTrue(Files.exists(orphan));
                }
            }
            assertEquals(
                    1, cleanup.cleanup(Duration.ofDays(1), false).attempts().size());
        }
    }

    @Test
    void corruptStateAndUnsafeRootsNeverDeleteEvidence() throws Exception {
        final var home = Files.createDirectory(root.resolve("state"));
        final var attempts = Files.createDirectory(root.resolve("attempts"));
        final var orphan = prepared(attempts, "yca-github-old");
        Files.writeString(home.resolve("state.json"), "{\"tasks\":null}");
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var cleanup = new OrphanCleanup(store, home, attempts, Clock.fixed(now, ZoneOffset.UTC));
            assertThrows(RuntimeException.class, () -> cleanup.cleanup(Duration.ofDays(1), false));
            assertTrue(Files.exists(orphan));
            assertThrows(IllegalArgumentException.class, () -> new OrphanCleanup(store, home, home, Clock.systemUTC()));
            Files.createSymbolicLink(root.resolve("alias"), attempts);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OrphanCleanup(store, home, root.resolve("alias"), Clock.systemUTC()));
            assertThrows(IllegalArgumentException.class, () -> cleanup.cleanup(Duration.ZERO, false));
        }
    }

    private Path prepared(final Path attempts, final String name) throws Exception {
        final var path = Files.createDirectory(attempts.resolve(name));
        Files.writeString(path.resolve(".prepared-only"), "yca-prepared-v1\n");
        Files.writeString(path.resolve("configuration.json"), "sensitive-provider-data");
        Files.setLastModifiedTime(path, FileTime.from(now.minus(Duration.ofDays(3))));
        return path;
    }
}
