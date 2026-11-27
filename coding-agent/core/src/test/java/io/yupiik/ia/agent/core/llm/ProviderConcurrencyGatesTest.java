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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConcurrencyCapabilities;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.ProviderCapabilities;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.llm.client.spi.ConcurrencyGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class ProviderConcurrencyGatesTest {
    @TempDir
    static Path root;

    private ProviderConfiguration defaults;

    @BeforeEach
    void defaults(@Fusion final CodingAgentConfiguration configuration) {
        final var home = Path.of(configuration.resolveStorageHome());
        defaults = configuration.provider().providers().getFirst();
    }

    @Nested
    @FusionSupport(modules = GateModule.class)
    class DisabledByDefaultStaysInMemory {
        @Test
        void disabledByDefaultStaysInMemory(@Fusion final CodingAgentConfiguration configuration) {
            final var home = Path.of(configuration.resolveStorageHome());
            try (final var gates = new ProviderConcurrencyGates(configuration)) {
                assertCapacity(gates.gate(provider("free", 2, false), 2), 2);
                assertFalse(Files.exists(home.resolve("llm-concurrency")));
            }
        }
    }

    @Nested
    @FusionSupport(modules = GateModule.class)
    class EnabledCreatesTheLeaseDirectoryLazily {
        @Test
        void enabledCreatesTheLeaseDirectoryLazily(@Fusion final CodingAgentConfiguration configuration) {
            final var home = Path.of(configuration.resolveStorageHome());
            try (final var gates = new ProviderConcurrencyGates(configuration)) {
                final var gate = gates.gate(provider("free", 2, true), 2);
                assertFalse(Files.exists(home.resolve("llm-concurrency")));
                assertCapacity(gate, 2);
                assertTrue(Files.isDirectory(home.resolve("llm-concurrency/free")));
            }
        }
    }

    @Nested
    @FusionSupport(modules = GateModule.class)
    class EnabledButUnboundedStaysInMemory {
        @Test
        void enabledButUnboundedStaysInMemory(@Fusion final CodingAgentConfiguration configuration) {
            final var home = Path.of(configuration.resolveStorageHome());
            try (final var gates = new ProviderConcurrencyGates(configuration)) {
                assertCapacity(gates.gate(provider("free", -1, true), 1), 1);
                assertFalse(Files.exists(home.resolve("llm-concurrency")));
            }
        }
    }

    @Nested
    @FusionSupport(modules = GlobalLimitModule.class)
    class GlobalMaxConcurrentActivatesTheFileGate {
        @Test
        void globalMaxConcurrentActivatesTheFileGate(@Fusion final CodingAgentConfiguration configuration) {
            final var home = Path.of(configuration.resolveStorageHome());
            try (final var gates = new ProviderConcurrencyGates(configuration)) {
                assertCapacity(gates.gate(provider("free", -1, true), 2), 2);
                assertTrue(Files.isDirectory(home.resolve("llm-concurrency/free")));
            }
        }
    }

    @Nested
    @FusionSupport(modules = GateModule.class)
    class LookupsOnlyNarrowAndKeepOneBudget {
        @Test
        void lookupsOnlyNarrowAndKeepOneBudget(@Fusion final CodingAgentConfiguration configuration) {
            final var home = Path.of(configuration.resolveStorageHome());
            for (final var file : List.of(false, true)) {
                try (final var gates = new ProviderConcurrencyGates(configuration)) {
                    final var wide = gates.gate(provider("free", 4, file), 4);
                    final var narrow = gates.gate(provider("free", 2, file), 2);
                    assertSame(wide, narrow);
                    assertCapacity(narrow, 2);
                    assertSame(wide, gates.gate(provider("free", 4, file), 4));
                    assertSame(wide, gates.gate(provider("free", 0, file), 0));
                    assertSame(wide, gates.gate(provider("free", -1, file), -1));
                    assertCapacity(wide, 2);
                    wide.setCapacity(4);
                    assertCapacity(wide, 2, "explicit recovery cannot exceed the reconciled ceiling");
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = GateModule.class)
    class LearnedNarrowingSurvivesSameWiderAndNewlyNarrowerRequests {
        @Test
        void learnedNarrowingSurvivesSameWiderAndNewlyNarrowerRequests(
                @Fusion final CodingAgentConfiguration configuration) {
            final var home = Path.of(configuration.resolveStorageHome());
            for (final var file : List.of(false, true)) {
                try (final var gates = new ProviderConcurrencyGates(configuration)) {
                    final var gate = gates.gate(provider("free", 8, file), 8);
                    gate.learnCapacity(1);
                    assertSame(gate, gates.gate(provider("free", 8, file), 8));
                    assertCapacity(gate, 1);
                    gates.gate(provider("free", 12, file), 12);
                    assertCapacity(gate, 1);
                    gates.gate(provider("free", 4, file), 4);
                    assertCapacity(gate, 1);
                    gate.setCapacity(3);
                    assertCapacity(gate, 3, "explicit adaptation recovery remains supported");
                    gates.gate(provider("free", 8, file), 8);
                    assertCapacity(gate, 3);
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = GateModule.class)
    class NonpositiveBoundsAreUnboundedUntilAPositiveRequest {
        @Test
        void nonpositiveBoundsAreUnboundedUntilAPositiveRequest(@Fusion final CodingAgentConfiguration configuration) {
            final var home = Path.of(configuration.resolveStorageHome());
            try (final var gates = new ProviderConcurrencyGates(configuration)) {
                final var gate = gates.gate(provider("free", -1, false), -1);
                final var releases = new ArrayList<Runnable>();
                try {
                    for (var i = 0; i < 20; i++) {
                        final var release = gate.tryAcquire();
                        assertNotNull(release);
                        releases.add(release);
                    }
                } finally {
                    releases.forEach(Runnable::run);
                }
                assertSame(gate, gates.gate(provider("free", 3, false), 3));
                assertCapacity(gate, 3);
                gates.gate(provider("free", -1, false), -1);
                assertCapacity(gate, 3);
            }
        }
    }

    @Nested
    @FusionSupport(modules = GateModule.class)
    class PersistedNarrowingSurvivesLookupReconciliation {
        @Test
        void persistedNarrowingSurvivesLookupReconciliation(@Fusion final CodingAgentConfiguration configuration) {
            final var home = Path.of(configuration.resolveStorageHome());
            final var provider = provider("persisted", 8, true);
            try (final var gates = new ProviderConcurrencyGates(configuration)) {
                gates.gate(provider, 8).learnCapacity(1);
            }
            try (final var gates = new ProviderConcurrencyGates(configuration)) {
                final var gate = gates.gate(provider, 8);
                gates.gate(provider, 4);
                assertCapacity(gate, 1);
            }
        }
    }

    @Nested
    @FusionSupport(modules = GateModule.class)
    class ModeAndCapacityConflictsWarnOnceAndRetainTheLiveMode {
        @Test
        void modeAndCapacityConflictsWarnOnceAndRetainTheLiveMode(
                @Fusion final CodingAgentConfiguration configuration) {
            final var home = Path.of(configuration.resolveStorageHome());
            final var logger = Logger.getLogger(ProviderConcurrencyGates.class.getName());
            final var records = new ArrayList<LogRecord>();
            final var handler = new Handler() {
                @Override
                public void publish(final LogRecord record) {
                    records.add(record);
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            };
            final var previousLevel = logger.getLevel();
            logger.setLevel(Level.ALL);
            logger.addHandler(handler);
            try {
                for (final var file : List.of(false, true)) {
                    final var id = "mode-" + file;
                    try (final var gates = new ProviderConcurrencyGates(configuration)) {
                        final var first = gates.gate(provider(id, 4, file), 4);
                        assertCapacity(first, 4);
                        for (var i = 0; i < 3; i++) {
                            assertSame(first, gates.gate(provider(id, 2, !file), 2));
                        }
                        assertCapacity(first, 2);
                        assertEquals(
                                file,
                                Files.isDirectory(
                                        home.resolve("llm-concurrency").resolve(id)));
                    }
                    assertEquals(
                            1,
                            records.stream()
                                    .filter(r -> r.getLevel() == Level.WARNING
                                            && r.getMessage().contains("mode mismatch")
                                            && r.getMessage().contains("'" + id + "'"))
                                    .count());
                    assertEquals(
                            1,
                            records.stream()
                                    .filter(r -> r.getLevel() == Level.WARNING
                                            && r.getMessage().contains("capacity mismatch")
                                            && r.getMessage().contains("'" + id + "'"))
                                    .count());
                }
            } finally {
                logger.removeHandler(handler);
                logger.setLevel(previousLevel);
            }
        }
    }

    @Nested
    @FusionSupport(modules = GateModule.class)
    class DirectoryKeysAreSafeDistinctAndStable {
        @Test
        void directoryKeysAreSafeDistinctAndStable(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var home = Path.of(configuration.resolveStorageHome());
            // These unsafe ids have the same Java hash and the same old sanitized prefix.
            assertEquals("a:~".hashCode(), "a;_".hashCode());
            final var ids =
                    List.of("openai", "a_b", "a/b", "a:~", "a;_", ".", "..", "../escape", "a\\b", "x".repeat(300));
            final var directories = new ArrayList<Path>();
            for (final var id : ids) {
                try (final var gates = new ProviderConcurrencyGates(configuration)) {
                    final var gate = gates.gate(provider(id, 1, true), 1);
                    final var before = new ArrayList<Path>();
                    if (Files.exists(home.resolve("llm-concurrency"))) {
                        try (final var paths = Files.list(home.resolve("llm-concurrency"))) {
                            paths.forEach(before::add);
                        }
                    }
                    assertCapacity(gate, 1);
                    try (final var paths = Files.list(home.resolve("llm-concurrency"))) {
                        final var created =
                                paths.filter(p -> !before.contains(p)).toList();
                        assertEquals(1, created.size(), "distinct id must create a distinct child: " + id);
                        final var directory = created.getFirst();
                        assertEquals(home.resolve("llm-concurrency"), directory.getParent());
                        directories.add(directory);
                    }
                }
            }
            assertEquals("openai", directories.getFirst().getFileName().toString());
            final var hashedKey = directories.get(2).getFileName().toString();
            try (final var gates = new ProviderConcurrencyGates(configuration)) {
                assertCapacity(gates.gate(provider(hashedKey, 1, true), 1), 1);
            }
            try (final var paths = Files.list(home.resolve("llm-concurrency"))) {
                assertEquals(ids.size() + 1L, paths.count(), "a safe-looking id cannot impersonate a generated key");
            }
            try (final var gates = new ProviderConcurrencyGates(configuration)) {
                assertCapacity(gates.gate(provider("a/b", 1, true), 1), 1);
            }
            try (final var paths = Files.list(home.resolve("llm-concurrency"))) {
                assertEquals(ids.size() + 1L, paths.count(), "keys must be stable across registry lifecycles");
            }
        }
    }

    @Nested
    @FusionSupport(modules = GateModule.class)
    class ClosingRejectsExistingAndNewLookups {
        @Test
        void closingRejectsExistingAndNewLookups(@Fusion final CodingAgentConfiguration configuration) {
            final var home = Path.of(configuration.resolveStorageHome());
            for (final var file : List.of(false, true)) {
                final var gates = new ProviderConcurrencyGates(configuration);
                final var provider = provider("free", 2, file);
                final var gate = gates.gate(provider, 2);
                final var release = gate.acquire().join();
                gates.close();
                gates.close();
                release.run();
                assertTrue(gate.acquire(100L, null).isCompletedExceptionally());
                assertThrows(IllegalStateException.class, () -> gates.gate(provider, 2));
                assertThrows(IllegalStateException.class, () -> gates.gate(provider("new", 2, file), 2));
            }
        }
    }

    @Nested
    @FusionSupport(modules = GateModule.class)
    class ConcurrentLookupsAndCloseCannotLeaveAnOpenGate {
        @Test
        void concurrentLookupsAndCloseCannotLeaveAnOpenGate(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var home = Path.of(configuration.resolveStorageHome());
            final var gates = new ProviderConcurrencyGates(configuration);
            final var start = new CountDownLatch(1);
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var lookups = new ArrayList<Future<ConcurrencyGate>>();
                for (var i = 0; i < 20; i++) {
                    final var id = "race-" + i;
                    lookups.add(executor.submit(() -> {
                        start.await();
                        try {
                            return gates.gate(provider(id, 2, true), 2);
                        } catch (final IllegalStateException closed) {
                            return null;
                        }
                    }));
                }
                final var closing = executor.submit(() -> {
                    start.await();
                    gates.close();
                    return null;
                });
                start.countDown();
                closing.get();
                for (final var lookup : lookups) {
                    final var gate = lookup.get();
                    if (gate != null) {
                        assertTrue(gate.acquire(100L, null).isCompletedExceptionally());
                    }
                }
            } finally {
                gates.close();
            }
            assertThrows(IllegalStateException.class, () -> gates.gate(null, 1));
        }
    }

    private static void assertCapacity(final ConcurrencyGate gate, final int capacity) {
        assertCapacity(gate, capacity, "the shared capacity must apply");
    }

    private static void assertCapacity(final ConcurrencyGate gate, final int capacity, final String message) {
        final var releases = new ArrayList<Runnable>();
        try {
            for (var i = 0; i < capacity; i++) {
                final var release = gate.tryAcquire();
                assertNotNull(release, message);
                releases.add(release);
            }
            assertNull(gate.tryAcquire(), message);
        } finally {
            releases.forEach(Runnable::run);
        }
    }

    private ProviderConfiguration provider(final String id, final int maxConcurrent, final boolean useFileLock) {
        final var caps = defaults.capabilities();
        return new ProviderConfiguration(
                id,
                "https://example.com/v1",
                defaults.apiKey(),
                "test-model",
                defaults.api(),
                defaults.contextWindow(),
                defaults.outputTokens(),
                defaults.releaseDate(),
                defaults.tuning(),
                defaults.models(),
                maxConcurrent,
                defaults.timeoutSeconds(),
                defaults.providerSpecificOptions(),
                defaults.headers(),
                defaults.dynamicHeaders(),
                defaults.color(),
                defaults.prompt(),
                defaults.subagents(),
                defaults.backgroundModelOverrides(),
                defaults.compaction(),
                new ProviderCapabilities(
                        caps.codex(), caps.openai(), caps.opencode(), new ConcurrencyCapabilities(useFileLock)));
    }

    public static class GateModule extends ConfigurationTestModule {
        public GateModule() {
            super(Map.of(
                    "yca.storageHome",
                    root.resolve(UUID.randomUUID().toString()).toString(),
                    "yca.provider.providers.length",
                    "1"));
        }
    }

    public static class GlobalLimitModule extends ConfigurationTestModule {
        public GlobalLimitModule() {
            super(Map.of(
                    "yca.storageHome",
                            root.resolve(UUID.randomUUID().toString()).toString(),
                    "yca.provider.providers.length", "1",
                    "yca.llm.maxConcurrent", "2"));
        }
    }
}
