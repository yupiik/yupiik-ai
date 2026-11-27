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
package io.yupiik.ia.agent.core.session;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(TestJsonMapper.class)
@Timeout(10)
class SessionMetadataStoreTest {
    @TempDir
    static Path root;

    private Path home;
    private CodingAgentConfiguration configuration;

    private JsonMapper mapper;

    @BeforeEach
    void mapper(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
        this.mapper = mapper;
        this.configuration = configuration;
        home = Path.of(configuration.resolveStorageHome());
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class PresetIsOutOfBandAndSurvivesTurnsCompactionAndEmptySessions {
        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void presetIsOutOfBandAndSurvivesTurnsCompactionAndEmptySessions(final boolean file) {
            final var store = store(file);
            final var id = store.create();
            store.markProject(id, home.toString());
            assertTrue(store.metadata(id, preset("a")));
            assertTrue(store.isEmpty(id));
            assertEquals(1, store.count(id));
            assertEquals("", store.summary(id));
            store.append(id, SessionEntry.of(1, "user", "visible question", null, null));
            store.append(id, SessionEntry.of(2, "assistant", "visible answer", null, null));
            assertEquals(3, store.count(id));
            assertEquals("visible question", store.summary(id));
            assertFalse(mapper.toString(store.load(id)).contains("private-system-instructions"));
            store.replace(
                    id,
                    List.of(
                            Message.user("<compacted-context>summary</compacted-context>"),
                            Message.assistant("tail", null)));
            assertEquals(home.toString(), store.projectDirOf(id));
            assertEquals(preset("a"), store.metadata(id));
            assertTrue(store.metadata(id, preset("b")));
            store.replace(id, store.load(id)); // flush
            assertEquals(preset("b"), store.metadata(id));
            assertEquals(3, store.count(id));
            if (file) {
                assertEquals(preset("b"), store(true).metadata(id));
            }
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ConcurrentTurnAndPresetUpdatesDoNotLoseHistory {
        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void concurrentTurnAndPresetUpdatesDoNotLoseHistory(final boolean file) throws Exception {
            final var store = store(file);
            final var id = store.create();
            store.markProject(id, home.toString());
            final var barrier = new CyclicBarrier(3);
            final var observer = file ? store(true) : store;
            try (final var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                final var turn = workers.submit(() -> {
                    barrier.await();
                    for (var i = 0; i < 100; i++) {
                        store.append(id, SessionEntry.of(i, "user", "turn-" + i, null, null));
                    }
                    return null;
                });
                final var config = workers.submit(() -> {
                    barrier.await();
                    for (var i = 0; i < 100; i++) {
                        assertTrue(store.metadata(id, preset("config-" + i)));
                        assertNotNull(observer.metadata(
                                id)); // Independent file-store lock observes only complete atomic writes.
                    }
                    return null;
                });
                barrier.await(5, TimeUnit.SECONDS);
                turn.get(5, TimeUnit.SECONDS);
                config.get(5, TimeUnit.SECONDS);
            }
            assertEquals(101, store.count(id));
            assertEquals("turn-99", store.load(id).getLast().content());
            assertEquals(preset("config-99"), store.metadata(id));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class DeleteRacesAndLateConfigNeverResurrectPreset {
        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void deleteRacesAndLateConfigNeverResurrectPreset(final boolean file) throws Exception {
            final var store = store(file);
            final var id = store.create();
            assertTrue(store.metadata(id, preset("before")));
            final var barrier = new CyclicBarrier(3);
            try (final var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                final var delete = workers.submit(() -> {
                    barrier.await();
                    store.delete(id);
                    return null;
                });
                final var config = workers.submit(() -> {
                    barrier.await();
                    store.metadata(id, preset("racing"));
                    return null;
                });
                barrier.await(5, TimeUnit.SECONDS);
                delete.get(5, TimeUnit.SECONDS);
                config.get(5, TimeUnit.SECONDS);
            }
            assertNull(store.metadata(id));
            assertFalse(store.metadata(id, preset("late")));
            assertTrue(store.list().isEmpty());
            assertTrue(store.load(id).isEmpty());
            if (file) {
                assertFalse(Files.exists(((FileSessionStore) store).sessionDir(id)));
            }
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class CompressionAndHousekeepingSharePresetLifecycle {
        @Test
        void compressionAndHousekeepingSharePresetLifecycle() throws Exception {
            final var store = (FileSessionStore) store(true);
            final var id = store.create();
            store.append(id, SessionEntry.of(1, "user", "retained", null, null));
            store.append(id, SessionEntry.of(2, "assistant", "retained answer", null, null));
            store.metadata(id, preset("aged"));
            Files.setLastModifiedTime(
                    store.path(id), FileTime.from(Instant.now().minusSeconds(20 * 86400L)));
            assertEquals(1, store.housekeeping(false).compressed());
            assertEquals(preset("aged"), store(true).metadata(id));
            assertEquals(2, store.load(id).size());
            store.append(id, SessionEntry.of(3, "user", "continued after compression", null, null));
            assertEquals(3, store.load(id).size());
            assertEquals("retained", store.load(id).getFirst().content());
            assertEquals(preset("aged"), store.metadata(id));
            Files.setLastModifiedTime(
                    store.path(id), FileTime.from(Instant.now().minusSeconds(20 * 86400L)));
            assertEquals(1, store.housekeeping(false).compressed());
            final var gz = store.sessionDir(id).resolve(id + ".jsonl.gz");
            Files.setLastModifiedTime(gz, FileTime.from(Instant.now().minusSeconds(40 * 86400L)));
            assertEquals(1, store.housekeeping(false).deleted());
            assertNull(store.metadata(id));
            assertFalse(Files.exists(store.sessionDir(id)));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class MalformedSidecarFailsWithoutLeakingTextOrTouchingHistory {
        @Test
        void malformedSidecarFailsWithoutLeakingTextOrTouchingHistory() throws Exception {
            final var store = (FileSessionStore) store(true);
            final var id = store.create();
            store.append(id, SessionEntry.of(1, "user", "keep me", null, null));
            Files.writeString(store.sessionDir(id).resolve("preset.json"), "secret-token-not-json");
            final var error = assertThrows(IllegalArgumentException.class, () -> store.metadata(id));
            assertFalse(error.toString().contains("secret-token"));
            assertNull(error.getCause());
            assertEquals("keep me", store.load(id).getFirst().content());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ConcurrentFreshReservationsDoNotOverwriteExistingHistoryOrPreset {
        @Test
        void concurrentFreshReservationsDoNotOverwriteExistingHistoryOrPreset() throws Exception {
            final var store = store(true);
            final var existing = store.create();
            store.append(existing, SessionEntry.of(1, "user", "original", null, null));
            store.metadata(existing, preset("original-model"));
            final var start = new CountDownLatch(1);
            try (final var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                final var reservations = IntStream.range(0, 64)
                        .mapToObj(index -> workers.submit(() -> {
                            start.await();
                            return store(true).create();
                        }))
                        .toList();
                start.countDown();
                final var ids = new HashSet<String>();
                for (final var reservation : reservations) {
                    assertTrue(ids.add(reservation.get(5, TimeUnit.SECONDS)));
                }
                assertFalse(ids.contains(existing));
            }
            assertEquals(65, store.list().size());
            assertEquals("original", store.load(existing).getFirst().content());
            assertEquals(preset("original-model"), store.metadata(existing));
        }
    }

    private SessionStore store(final boolean file) {
        if (!file) {
            return new InMemorySessionStore(Clock.systemUTC());
        }
        return new FileSessionStore(configuration, mapper, Clock.systemUTC());
    }

    public static class SessionModule extends ConfigurationTestModule {
        public SessionModule() {
            super(Map.of(
                    "yca.storageHome",
                            root.resolve(UUID.randomUUID().toString()).toString(),
                    "yca.sessions.clean.retentionDays", "30",
                    "yca.sessions.compressDays", "15",
                    "yca.sessions.startupCleanup", "false"));
        }
    }

    private SessionMetadata preset(final String model) {
        return new SessionMetadata(
                1,
                "provider",
                model,
                "high",
                List.of(
                        new SessionMetadata.Sampling("TEMPERATURE", 0.25),
                        new SessionMetadata.Sampling("TOP_P", null),
                        new SessionMetadata.Sampling("TOP_K", 8.0)),
                "reviewer",
                "plan",
                new SessionMetadata.Instructions("append", List.of("private-system-instructions")));
    }
}
