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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskDiagnosticsTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T12:34:56Z"), ZoneOffset.UTC);

    @TempDir
    Path temporary;

    @Test
    void privateSnapshotRetainsOriginalMessagesWithoutPublishingThemAndSurvivesCleanupAndRestart() throws Exception {
        final var home = temporary.resolve("home");
        final var attempt = Files.createDirectory(temporary.resolve("attempt"));
        Files.writeString(attempt.resolve("execution.log"), "private-execution-tail");
        Files.writeString(attempt.resolve("configuration.json"), "must-not-copy-config");
        final var workspace = Files.createDirectory(attempt.resolve("workspace"));
        Files.writeString(workspace.resolve("source.txt"), "must-not-copy-workspace");
        final var failure = new IllegalStateException("token=original-secret", new IOException("cause-secret"));
        try (final var json = mapper()) {
            final var diagnostics = new TaskDiagnostics(home, json, CLOCK);
            final var task = TaskFixtures.task(Map.of(
                    "id", "task-1", "attempts", 3, "runtimeId", "runtime-1", "attemptDirectory", attempt.toString()));
            final var reference = diagnostics.capture(task, "execution", failure, 17);
            assertFalse(Path.of(reference).isAbsolute());
            assertTrue(reference.startsWith("diagnostics/task-1/diagnostic-"));
            assertFalse(reference.contains("secret"));
            assertNull(task.lastError());
            assertFalse(TaskFixtures.map(task).toString().contains("original-secret"));
            final var record = home.resolve(reference);
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(record));
            for (final var directory : List.of(home, home.resolve("diagnostics"), record.getParent())) {
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            }
            final var content = Files.readString(record);
            assertTrue(content.contains("token=original-secret"));
            assertTrue(content.contains("cause-secret"));
            assertFalse(content.contains("must-not-copy"));
            assertFalse(content.contains("configuration.json"));
            Files.delete(attempt.resolve("execution.log"));
            final var restored = new TaskDiagnostics(home, json, Clock.systemUTC());
            final var records = records(json, restored.read("task-1"));
            assertEquals(1, records.size());
            final var details = records.getFirst();
            assertEquals(CLOCK.instant().toString(), details.get("timestamp"));
            assertEquals("task-1", details.get("taskId"));
            assertEquals(3, ((Number) details.get("attempt")).intValue());
            assertEquals("runtime-1", details.get("runtimeId"));
            assertEquals("execution", details.get("stage"));
            assertEquals(17, ((Number) details.get("exitCode")).intValue());
            assertEquals(IllegalStateException.class.getName(), details.get("exceptionClass"));
            assertEquals("token=original-secret", details.get("message"));
            assertFalse(((List<?>) details.get("stack")).isEmpty());
            assertEquals("cause-secret", ((Map<?, ?>) ((List<?>) details.get("causes")).getFirst()).get("message"));
            assertEquals("private-execution-tail", details.get("logTail"));
            assertEquals("token=original-secret", failure.getMessage());
            assertFalse(Files.exists(home.resolve("audit.jsonl")));
        }
    }

    @Test
    void tightensExistingStorageDirectoriesWithoutChangingTheirParent() throws Exception {
        final var parentPermissions = Files.getPosixFilePermissions(temporary);
        final var home = Files.createDirectory(temporary.resolve("home"));
        final var root = Files.createDirectory(home.resolve("diagnostics"));
        final var directory = Files.createDirectory(root.resolve("task"));
        for (final var path : List.of(home, root, directory)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        try (final var json = mapper()) {
            new TaskDiagnostics(home, json, CLOCK).capture(TaskFixtures.task(Map.of("id", "task")), "run", null, null);
        }
        for (final var path : List.of(home, root, directory)) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(path));
        }
        assertEquals(parentPermissions, Files.getPosixFilePermissions(temporary));
    }

    @Test
    void retainsOnlyTheLast64KiBOfTheLog() throws Exception {
        final var attempt = Files.createDirectory(temporary.resolve("attempt"));
        final var tail = "x".repeat(64 * 1024 - 8) + "THE-END!";
        Files.writeString(attempt.resolve("execution.log"), "discard-this-prefix" + tail);
        try (final var json = mapper()) {
            final var diagnostics = new TaskDiagnostics(temporary.resolve("home"), json, CLOCK);
            diagnostics.capture(
                    TaskFixtures.task(Map.of("id", "task", "attemptDirectory", attempt.toString())), "run", null, 0);
            final var details = records(json, diagnostics.read("task")).getFirst();
            assertEquals(tail, details.get("logTail"));
            assertEquals(64 * 1024, ((String) details.get("logTail")).getBytes(StandardCharsets.UTF_8).length);
        }
    }

    @Test
    void malformedUtf8AndSplitCharactersCannotExpandTheTailLimit() throws Exception {
        final var attempt = Files.createDirectory(temporary.resolve("attempt"));
        final var bytes = new byte[70 * 1024];
        Arrays.fill(bytes, (byte) 0xff);
        bytes[bytes.length - 1] = 'z';
        Files.write(attempt.resolve("execution.log"), bytes);
        try (final var json = mapper()) {
            final var diagnostics = new TaskDiagnostics(temporary.resolve("home"), json, CLOCK);
            diagnostics.capture(
                    TaskFixtures.task(Map.of("id", "task", "attemptDirectory", attempt.toString())), "run", null, null);
            assertEquals("z", records(json, diagnostics.read("task")).getFirst().get("logTail"));
        }
    }

    @Test
    void missingLogsRetainMetadataAndNoAttemptIsAlsoSupported() throws Exception {
        try (final var json = mapper()) {
            final var diagnostics = new TaskDiagnostics(temporary.resolve("home"), json, CLOCK);
            assertEquals("[]", diagnostics.read("unknown"));
            diagnostics.delete("unknown");
            assertFalse(Files.exists(temporary.resolve("home")));
            for (final var task : List.of(
                    TaskFixtures.task(Map.of("id", "task")),
                    TaskFixtures.task(Map.of(
                            "id",
                            "task",
                            "attemptDirectory",
                            temporary.resolve("absent").toString())))) {
                diagnostics.capture(task, "preparation", new IOException("original failure"), null);
            }
            for (final var details : records(json, diagnostics.read("task"))) {
                assertEquals("original failure", details.get("message"));
                assertEquals("missing", details.get("logUnavailable"));
                assertFalse(details.containsKey("logTail"));
            }
        }
    }

    @Test
    void boundsMessagesAndCauseDepthEvenWithCycles() throws Exception {
        final var failure = new IOException("s".repeat(100_000));
        var current = failure;
        for (var i = 0; i < 20; i++) {
            final var next = new IOException("c".repeat(100_000));
            current.initCause(next);
            current = next;
        }
        current.initCause(failure);
        try (final var json = mapper()) {
            final var diagnostics = new TaskDiagnostics(temporary.resolve("home"), json, CLOCK);
            final var reference = diagnostics.capture(TaskFixtures.task(Map.of("id", "task")), "run", failure, null);
            final var details = records(json, diagnostics.read("task")).getFirst();
            assertEquals(4096, ((String) details.get("message")).length());
            final var causes = (List<?>) details.get("causes");
            assertEquals(16, causes.size());
            for (final var cause : causes) {
                assertEquals(4096, ((String) ((Map<?, ?>) cause).get("message")).length());
            }
            assertTrue(Files.size(temporary.resolve("home").resolve(reference)) < 1024 * 1024);
        }
    }

    @Test
    void latest16AreRetainedAcrossRestartsAndIdenticalTimestamps() throws Exception {
        final var home = temporary.resolve("home");
        try (final var json = mapper()) {
            String first = null;
            for (var i = 0; i < 20; i++) {
                final var reference = new TaskDiagnostics(home, json, CLOCK)
                        .capture(TaskFixtures.task(Map.of("id", "task", "attempts", i)), "run", null, null);
                if (i == 0) {
                    first = reference;
                }
            }
            assertNotNull(first);
            assertFalse(Files.exists(home.resolve(first)));
            final var records = records(json, new TaskDiagnostics(home, json, CLOCK).read("task"));
            assertEquals(16, records.size());
            for (var i = 0; i < 16; i++) {
                assertEquals(i + 4, ((Number) records.get(i).get("attempt")).intValue());
            }
            try (final var files = Files.list(home.resolve("diagnostics/task"))) {
                assertEquals(16, files.count());
            }
        }
    }

    @Test
    void rejectsMaliciousTaskIdentifiersForEveryOperation() throws Exception {
        try (final var json = mapper()) {
            final var diagnostics = new TaskDiagnostics(temporary.resolve("home"), json, CLOCK);
            for (final var id :
                    List.of("", ".", "..", "../escape", "/absolute", "a/b", "a\\b", "a.b", "a\n", "a".repeat(129))) {
                assertThrows(IllegalArgumentException.class, () -> diagnostics.read(id));
                assertThrows(IllegalArgumentException.class, () -> diagnostics.delete(id));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> diagnostics.capture(TaskFixtures.task(Map.of("id", id)), "run", null, null));
            }
            assertThrows(IllegalArgumentException.class, () -> diagnostics.read(null));
            assertThrows(IllegalArgumentException.class, () -> diagnostics.delete(null));
            assertFalse(Files.exists(temporary.resolve("home")));
        }
    }

    @Test
    void unsafeLogFilesAndSourceAncestorsRetainOnlyExceptionMetadata() throws Exception {
        final var actual = Files.createDirectory(temporary.resolve("actual"));
        Files.writeString(actual.resolve("execution.log"), "do-not-follow-secret");
        final var linkedParent = Files.createSymbolicLink(temporary.resolve("linked-parent"), actual);
        final var linkedFile = Files.createDirectory(temporary.resolve("linked-file"));
        Files.createSymbolicLink(linkedFile.resolve("execution.log"), actual.resolve("execution.log"));
        final var nonRegular = Files.createDirectory(temporary.resolve("non-regular"));
        Files.createDirectory(nonRegular.resolve("execution.log"));
        try (final var json = mapper()) {
            final var diagnostics = new TaskDiagnostics(temporary.resolve("home"), json, CLOCK);
            for (final var attempt : List.of(linkedParent, linkedFile, nonRegular)) {
                diagnostics.capture(
                        TaskFixtures.task(Map.of("id", "task", "attemptDirectory", attempt.toString())),
                        "run",
                        new IOException("original"),
                        null);
            }
            final var nested = Files.createDirectory(actual.resolve("nested"));
            Files.writeString(nested.resolve("execution.log"), "do-not-follow-secret");
            diagnostics.capture(
                    TaskFixtures.task(Map.of(
                            "id",
                            "task",
                            "attemptDirectory",
                            linkedParent.resolve("nested").toString())),
                    "run",
                    new IOException("original"),
                    null);
            final var text = diagnostics.read("task");
            assertFalse(text.contains("do-not-follow-secret"));
            for (final var details : records(json, text)) {
                assertEquals("unavailable", details.get("logUnavailable"));
                assertEquals("original", details.get("message"));
                assertFalse(details.containsKey("logTail"));
            }
        }
    }

    @Test
    void rejectsSymlinkedHomeAncestorsStorageRootAndTaskDirectory() throws Exception {
        try (final var json = mapper()) {
            final var actual = Files.createDirectory(temporary.resolve("actual"));
            final var link = Files.createSymbolicLink(temporary.resolve("link"), actual);
            for (final var home : List.of(link, link.resolve("nested"))) {
                assertUnsafeStorage(new TaskDiagnostics(home, json, CLOCK));
            }
            final var home = Files.createDirectory(temporary.resolve("home"));
            Files.createSymbolicLink(home.resolve("diagnostics"), actual);
            assertUnsafeStorage(new TaskDiagnostics(home, json, CLOCK));
            Files.delete(home.resolve("diagnostics"));
            Files.createDirectory(home.resolve("diagnostics"));
            Files.createSymbolicLink(home.resolve("diagnostics/task"), actual);
            assertUnsafeStorage(new TaskDiagnostics(home, json, CLOCK));
            try (final var entries = Files.list(actual)) {
                assertEquals(0, entries.count());
            }
        }
    }

    @Test
    void rejectsSymlinkedRecordsWithoutReadingOrDeletingTheirTargets() throws Exception {
        final var home = temporary.resolve("home");
        final var outside = Files.writeString(temporary.resolve("outside"), "keep-secret");
        try (final var json = mapper()) {
            final var diagnostics = new TaskDiagnostics(home, json, CLOCK);
            final var reference = diagnostics.capture(TaskFixtures.task(Map.of("id", "task")), "run", null, null);
            Files.delete(home.resolve(reference));
            Files.createSymbolicLink(home.resolve(reference), outside);
            assertUnsafeStorage(diagnostics);
            assertEquals("keep-secret", Files.readString(outside));
            assertTrue(Files.isSymbolicLink(home.resolve(reference)));
        }
    }

    @Test
    void oversizedRecordsAreRejectedWithoutUnboundedReads() throws Exception {
        final var home = temporary.resolve("home");
        try (final var json = mapper()) {
            final var diagnostics = new TaskDiagnostics(home, json, CLOCK);
            final var reference = diagnostics.capture(TaskFixtures.task(Map.of("id", "task")), "run", null, null);
            Files.writeString(home.resolve(reference), " ".repeat(1024 * 1024 + 1));
            assertThrows(IOException.class, () -> diagnostics.read("task"));
            diagnostics.delete("task");
            assertEquals("[]", diagnostics.read("task"));
        }
    }

    @Test
    void deletionIsIdempotentAndConfinedToGeneratedTaskFiles() throws Exception {
        final var home = temporary.resolve("home");
        try (final var json = mapper()) {
            final var diagnostics = new TaskDiagnostics(home, json, CLOCK);
            final var reference = diagnostics.capture(TaskFixtures.task(Map.of("id", "task")), "run", null, null);
            final var other = diagnostics.capture(TaskFixtures.task(Map.of("id", "other")), "run", null, null);
            final var unexpected = Files.createDirectories(home.resolve("diagnostics/task/unexpected"));
            final var keep = Files.writeString(unexpected.resolve("keep"), "keep");
            assertThrows(IOException.class, () -> diagnostics.delete("task"));
            assertThrows(IOException.class, () -> diagnostics.delete("task"));
            assertFalse(Files.exists(home.resolve(reference)));
            assertTrue(Files.exists(home.resolve(other)));
            assertEquals("keep", Files.readString(keep));
            assertEquals("[]", diagnostics.read("task"));
            Files.delete(keep);
            Files.delete(unexpected);
            diagnostics.delete("task");
            assertFalse(Files.exists(home.resolve("diagnostics/task")));
            diagnostics.delete("other");
            assertEquals("[]", diagnostics.read("other"));
        }
    }

    @Test
    void abandonedAtomicWriteFilesAreRemovedOnCaptureAndDelete() throws Exception {
        final var home = temporary.resolve("home");
        try (final var json = mapper()) {
            final var diagnostics = new TaskDiagnostics(home, json, CLOCK);
            final var task = TaskFixtures.task(Map.of("id", "task"));
            diagnostics.capture(task, "preparation", null, null);
            final var abandoned = Files.writeString(home.resolve("diagnostics/task/.diagnostic-123.tmp"), "private");
            diagnostics.capture(task, "preparation", null, null);
            assertFalse(Files.exists(abandoned));
            Files.writeString(abandoned, "private");
            diagnostics.delete("task");
            assertFalse(Files.exists(home.resolve("diagnostics/task")));
        }
    }

    private void assertUnsafeStorage(final TaskDiagnostics diagnostics) {
        assertThrows(
                IOException.class,
                () -> diagnostics.capture(
                        TaskFixtures.task(Map.of("id", "task")), "run", new IOException("private"), null));
        assertThrows(IOException.class, () -> diagnostics.read("task"));
        assertThrows(IOException.class, () -> diagnostics.delete("task"));
    }

    private JsonMapper mapper() {
        return new JsonMapperImpl(List.of(), ignored -> Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(final JsonMapper json, final String text) {
        return (List<Map<String, Object>>) json.fromString(Object.class, text);
    }
}
