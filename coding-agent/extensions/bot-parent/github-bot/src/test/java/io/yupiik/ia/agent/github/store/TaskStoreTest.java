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
package io.yupiik.ia.agent.github.store;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskStoreTest {
    @TempDir
    Path home;

    @Test
    void readOnlyTransactionDoesNotCreateState() {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            assertTrue(store.<Boolean>transaction(state -> state.list().isEmpty()));
            assertFalse(Files.exists(home.resolve("state.json")));
            assertTrue(Files.exists(home.resolve("state.lock")));
        }
    }

    @Test
    void readsAndNoOpMutationsLeaveStateFileUntouched() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(state -> state.enqueue("existing", TaskFixtures.task(Map.of())));
            final var file = home.resolve("state.json");
            Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2000-01-01T00:00:00Z")));
            final var before = Files.readAttributes(file, BasicFileAttributes.class);
            final var lockKey = Files.readAttributes(home.resolve("state.lock"), BasicFileAttributes.class)
                    .fileKey();
            final var content = Files.readAllBytes(file);
            for (int i = 0; i < 3; i++) {
                assertEquals(id, store.transaction(state -> state.task(id).id()));
                assertEquals(1, store.transaction(state -> state.list().size()).intValue());
                assertEquals(id, store.transaction(state -> state.enqueue("existing", TaskFixtures.task(Map.of()))));
                store.transaction(state -> {
                    state.task(id).state(TaskState.pending);
                    state.cursors().put("temporary", "value");
                    state.cursors().remove("temporary");
                    state.task(id).runs().add(new TaskDocument.Run(Map.of("temporary", true)));
                    state.task(id).runs().clear();
                    return null;
                });
                final var after = Files.readAttributes(file, BasicFileAttributes.class);
                assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
                assertEquals(before.fileKey(), after.fileKey());
                assertArrayEquals(content, Files.readAllBytes(file));
                assertEquals(
                        lockKey,
                        Files.readAttributes(home.resolve("state.lock"), BasicFileAttributes.class)
                                .fileKey());
            }
            try (final var files = Files.list(home)) {
                assertEquals(
                        List.of("state.json", "state.lock"),
                        files.map(path -> path.getFileName().toString())
                                .sorted()
                                .toList());
            }
        }
    }

    @Test
    void scalarAndInPlaceNestedMutationsEachSurviveReopening() {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(state -> state.enqueue(
                    "nested",
                    TaskFixtures.task(Map.of(
                            "future", Map.of("items", List.of("original")),
                            "forkProvisionIntent", Map.of("upstream", "acme/repo", "stage", Map.of("done", false)),
                            "strictSolvePublication", Map.of("blobs", Map.of("file", Map.of("intent", true)))))));
            final List<Consumer<MutableTask>> mutations = List.of(
                    task -> task.runtimeId("runtime-1"),
                    task -> task.runtimeId(null),
                    task -> task.runs().add(new TaskDocument.Run(new LinkedHashMap<>(Map.of("step", "one")))),
                    task -> task.runs().getFirst().extensions().put("step", "two"),
                    task -> task.runs().set(0, new TaskDocument.Run(Map.of("step", "three"))),
                    task -> task.runs().removeFirst(),
                    task -> TaskStore.map(task.recovery().get("future")).put("added", true),
                    task -> ((List<?>)
                                    TaskStore.map(task.recovery().get("future")).get("items"))
                            .clear(),
                    task -> TaskStore.map(task.recovery().get("future")).remove("added"),
                    task -> TaskStore.map(
                                    task.forkProvisionIntent().extensions().get("stage"))
                            .put("done", true),
                    task -> task.strictSolvePublication()
                            .blobs()
                            .put("file", new TaskDocument.PublicationObject(true, "sha")),
                    task -> task.strictSolvePublication().blobs().clear(),
                    task -> task.recovery().clear());
            for (final var mutation : mutations) {
                final var expected = store.transaction(state -> {
                    mutation.accept(state.task(id));
                    return state.task(id).document();
                });
                assertEquals(
                        expected,
                        new TaskStore(BotTestConfiguration.home(home), json)
                                .transaction(state -> state.task(id).document()));
            }
        }
    }

    @Test
    void directMapAndViewMutationsEachSurviveReopening() {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final List<Consumer<TaskStore.State>> mutations = List.of(
                    state -> state.cursors().put("endpoint", "first"),
                    state -> state.cursors().entrySet().iterator().next().setValue("second"),
                    state -> state.cursors().values().remove("second"),
                    state -> state.receipts().put("invocation", "task"),
                    state -> state.receipts().replaceAll((key, value) -> "updated"),
                    state -> state.receipts().keySet().remove("invocation"),
                    state -> state.tasks().put("task", new MutableTask(TaskFixtures.task(Map.of("id", "task")))),
                    state -> state.tasks()
                            .entrySet()
                            .iterator()
                            .next()
                            .setValue(new MutableTask(TaskFixtures.task(Map.of("id", "task", "request", "updated")))),
                    state -> state.tasks().values().clear());
            for (final var mutation : mutations) {
                final var expected = store.transaction(state -> {
                    mutation.accept(state);
                    return List.of(state.list(), Map.copyOf(state.cursors()), Map.copyOf(state.receipts()));
                });
                assertEquals(
                        expected,
                        new TaskStore(BotTestConfiguration.home(home), json)
                                .transaction(state -> List.of(state.list(), state.cursors(), state.receipts())));
            }
        }
    }

    @Test
    void failedNestedMutationLeavesFileUntouched() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(state ->
                    state.enqueue("rollback", TaskFixtures.task(Map.of("future", Map.of("retained", true)))));
            final var file = home.resolve("state.json");
            final var before = Files.readAllBytes(file);
            assertThrows(
                    IllegalStateException.class,
                    () -> store.transaction(state -> {
                        TaskStore.map(state.task(id).recovery().get("future")).clear();
                        state.task(id).runs().add(new TaskDocument.Run(Map.of("aborted", true)));
                        state.cursors().put("endpoint", "aborted");
                        throw new IllegalStateException("abort");
                    }));
            assertArrayEquals(before, Files.readAllBytes(file));
        }
    }

    @Test
    void readOnlyTransactionKeepsExclusiveLockUntilCallbackCompletes() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var writerStarted = new CountDownLatch(1);
        final var writerEntered = new CountDownLatch(1);
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var reader = executor.submit(() -> store.transaction(state -> {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return state.list();
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                final var writer = executor.submit(() -> {
                    writerStarted.countDown();
                    return new TaskStore(BotTestConfiguration.home(home), json).transaction(state -> {
                        writerEntered.countDown();
                        state.cursors().put("endpoint", "committed");
                        return null;
                    });
                });
                assertTrue(writerStarted.await(10, TimeUnit.SECONDS));
                assertFalse(writerEntered.await(200, TimeUnit.MILLISECONDS));
                release.countDown();
                assertTrue(reader.get(10, TimeUnit.SECONDS).isEmpty());
                writer.get(10, TimeUnit.SECONDS);
                assertEquals(
                        "committed", store.transaction(state -> state.cursors().get("endpoint")));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void typedTaskTransitionsPersistAndFailedMutationsRollBack() {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(state -> state.enqueue(
                    "typed",
                    TaskFixtures.task(Map.of("kind", "review", "repository", "acme/repo", "future", "preserved"))));
            store.transaction(state -> {
                final var task = state.task(id);
                task.state(TaskState.running);
                task.attempts(1);
                task.runtimeId("runtime-1");
                return null;
            });
            assertThrows(
                    IllegalStateException.class,
                    () -> store.transaction(state -> {
                        state.task(id).state(TaskState.done);
                        state.task(id).runtimeId(null);
                        throw new IllegalStateException("abort");
                    }));
            final var restored = new TaskStore(BotTestConfiguration.home(home), json)
                    .transaction(state -> state.task(id).document());
            assertEquals(TaskState.running, restored.state());
            assertEquals(TaskKind.review, restored.kind());
            assertEquals(1, restored.attempts());
            assertEquals("runtime-1", restored.runtimeId());
            assertEquals("preserved", restored.recovery().get("future"));
        }
    }

    @Test
    void receiptSurvivesTaskDeletionAndRestart() {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(
                    state -> state.enqueue("comment:123", TaskFixtures.task(Map.of("kind", "solve"))));
            store.transaction(state -> {
                state.tasks().remove(id);
                return null;
            });
            final var reopened = new TaskStore(BotTestConfiguration.home(home), json);
            assertEquals(
                    id,
                    reopened.transaction(
                            state -> state.enqueue("comment:123", TaskFixtures.task(Map.of("kind", "solve")))));
            assertTrue(reopened.transaction(state ->
                            state.list().stream().map(TaskFixtures::map).toList())
                    .isEmpty());
        }
    }

    @Test
    void concurrentTransactionsDoNotLoseTasks() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var futures = IntStream.range(0, 40)
                    .mapToObj(index -> executor.submit(() -> new TaskStore(BotTestConfiguration.home(home), json)
                            .transaction(state ->
                                    state.enqueue("invocation:" + index, TaskFixtures.task(Map.of("kind", "review"))))))
                    .toList();
            for (final var future : futures) {
                future.get();
            }
            assertEquals(
                    40,
                    store.transaction(state ->
                                    state.list().stream().map(TaskFixtures::map).toList())
                            .size());
        }
    }

    @Test
    void symlinkStateIsRejected() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var target = Files.createTempFile("yca-state-test", ".json");
            try {
                Files.createSymbolicLink(home.resolve("state.json"), target);
                assertThrows(
                        IllegalStateException.class,
                        () -> new TaskStore(BotTestConfiguration.home(home), json)
                                .transaction(state -> state.list().stream()
                                        .map(TaskFixtures::map)
                                        .toList()));
            } finally {
                Files.deleteIfExists(target);
            }
        }
    }

    @Test
    void malformedStateIsPreservedForOperatorRecovery() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var file = home.resolve("state.json");
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            for (final var content : List.of("{broken", "[]", "null", "{\"tasks\":[]}", "{\"cursors\":false}")) {
                Files.writeString(file, content);
                assertThrows(
                        RuntimeException.class,
                        () -> store.transaction(state ->
                                state.list().stream().map(TaskFixtures::map).toList()));
                assertEquals(content, Files.readString(file));
            }
        }
    }

    @Test
    void readOnlyTransactionCannotRewriteInvalidUnusedSection() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var content = "{\"schemaVersion\":1,\"tasks\":{},\"receipts\":[]}";
            Files.writeString(home.resolve("state.json"), content);
            assertThrows(
                    IllegalStateException.class,
                    () -> new TaskStore(BotTestConfiguration.home(home), json)
                            .transaction(state ->
                                    state.list().stream().map(TaskFixtures::map).toList()));
            assertEquals(content, Files.readString(home.resolve("state.json")));
        }
    }

    @Test
    void symbolicLockCannotRedirectWrites() throws Exception {
        final var target = home.resolve("protected");
        Files.writeString(target, "unchanged");
        Files.createSymbolicLink(home.resolve("state.lock"), target);
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            assertThrows(
                    IllegalStateException.class,
                    () -> new TaskStore(BotTestConfiguration.home(home), json)
                            .transaction(state ->
                                    state.list().stream().map(TaskFixtures::map).toList()));
            assertEquals("unchanged", Files.readString(target));
        }
    }

    @Test
    void failedTransactionDoesNotCommit() {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            assertThrows(
                    IllegalStateException.class,
                    () -> store.transaction(state -> {
                        state.enqueue("x", TaskFixtures.task(Map.of()));
                        throw new IllegalStateException();
                    }));
            assertTrue(store.transaction(state ->
                            state.list().stream().map(TaskFixtures::map).toList())
                    .isEmpty());
        }
    }
}
