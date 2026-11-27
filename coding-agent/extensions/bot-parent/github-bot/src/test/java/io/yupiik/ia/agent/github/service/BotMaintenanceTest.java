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

import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotMaintenanceTest {
    @TempDir
    Path home;

    @Test
    void purgesOnlyExpiredCleanTerminalTasksAndKeepsReceipts() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var now = Instant.parse("2026-11-11T00:00:00Z");
            store.transaction(state -> {
                for (final var status : List.of("done", "failed", "running", "publishing", "pending")) {
                    final var id = state.enqueue(
                            status,
                            TaskFixtures.task(Map.of(
                                    "completedAt", now.minus(Duration.ofDays(8)).toString())));
                    state.task(id).state(TaskState.valueOf(status));
                }
                final var retained = state.enqueue(
                        "secret-bearing",
                        TaskFixtures.task(Map.of(
                                "completedAt",
                                now.minus(Duration.ofDays(8)).toString(),
                                "attemptDirectory",
                                "/private/attempt")));
                state.task(retained).state(TaskState.done);
                final var fresh = state.enqueue("fresh", TaskFixtures.task(Map.of("completedAt", now.toString())));
                state.task(fresh).state(TaskState.done);
                return null;
            });
            final var clock = Clock.fixed(now, ZoneOffset.UTC);
            final var diagnostics = new TaskDiagnostics(home, json, clock);
            final var tasks = store.transaction(state -> state.list());
            for (final var task : tasks) {
                diagnostics.capture(task, "execution", new IllegalStateException("private"), 1);
            }
            final var originalState = Files.readString(home.resolve("state.json"));
            final var originalDiagnostics = new HashMap<String, String>();
            for (final var task : tasks) {
                originalDiagnostics.put(task.id(), diagnostics.read(task.id()));
            }
            final var purgedIds = store.transaction(state ->
                    List.of(state.receipts().get("done"), state.receipts().get("failed")));
            final var maintenance = new BotMaintenance(store, clock, diagnostics);
            assertEquals(2, maintenance.purge(Duration.ofDays(7), true));
            assertEquals(originalState, Files.readString(home.resolve("state.json")));
            for (final var task : tasks) {
                assertEquals(originalDiagnostics.get(task.id()), diagnostics.read(task.id()));
            }
            assertEquals(2, maintenance.purge(Duration.ofDays(7)));
            for (final var task : tasks) {
                if (purgedIds.contains(task.id())) {
                    assertFalse(Files.exists(home.resolve("diagnostics").resolve(task.id())));
                } else {
                    assertEquals(originalDiagnostics.get(task.id()), diagnostics.read(task.id()));
                }
            }
            assertEquals(5, store.transaction(state -> state.list().size()).intValue());
            assertEquals(7, store.transaction(state -> state.receipts().size()).intValue());
            final var receipt = store.transaction(state -> state.enqueue("done", TaskFixtures.task(Map.of())));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.transaction(
                            state -> TaskFixtures.map(state.task(receipt).document())));
            assertThrows(IllegalArgumentException.class, () -> maintenance.purge(Duration.ZERO));
        }
    }

    @Test
    void failedPrivateDeletionKeepsTaskAndReceiptsForAnotherPurge() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var now = Instant.parse("2026-11-11T00:00:00Z");
            final var clock = Clock.fixed(now, ZoneOffset.UTC);
            final var id = store.transaction(state -> {
                final var created = state.enqueue(
                        "expired",
                        TaskFixtures.task(Map.of(
                                "completedAt", now.minus(Duration.ofDays(8)).toString())));
                state.task(created).state(TaskState.failed);
                return created;
            });
            final var other = store.transaction(state -> state.enqueue("other", TaskFixtures.task(Map.of())));
            final var diagnostics = new TaskDiagnostics(home, json, clock);
            final var record = home.resolve(diagnostics.capture(
                    store.transaction(state -> state.task(id).document()), "execution", null, 1));
            diagnostics.capture(store.transaction(state -> state.task(other).document()), "execution", null, 2);
            final var otherDiagnostic = diagnostics.read(other);
            final var originalState = Files.readString(home.resolve("state.json"));
            final var saved = home.resolve("saved-diagnostic.json");
            Files.move(record, saved);
            Files.createDirectory(record);
            final var maintenance = new BotMaintenance(store, clock, diagnostics);
            assertThrows(UncheckedIOException.class, () -> maintenance.purge(Duration.ofDays(7)));
            assertEquals(originalState, Files.readString(home.resolve("state.json")));
            assertEquals(
                    TaskState.failed, store.transaction(state -> state.task(id).state()));
            assertEquals(otherDiagnostic, diagnostics.read(other));

            Files.delete(record);
            Files.move(saved, record);
            assertEquals(1, maintenance.purge(Duration.ofDays(7)));
            assertFalse(Files.exists(record.getParent()));
            assertEquals("[]", diagnostics.read(id));
            assertEquals(otherDiagnostic, diagnostics.read(other));
            assertEquals(
                    other, store.transaction(state -> state.list().getFirst().id()));
            assertEquals(id, store.transaction(state -> state.receipts().get("expired")));
            assertEquals(2, store.transaction(state -> state.receipts().size()).intValue());
        }
    }
}
