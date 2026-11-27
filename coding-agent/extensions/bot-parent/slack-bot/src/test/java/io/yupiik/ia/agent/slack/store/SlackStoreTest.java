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
package io.yupiik.ia.agent.slack.store;

import static io.yupiik.ia.agent.slack.support.SlackDomainFixture.task;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SlackStoreTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @CsvSource({
        "queued,false",
        "preparing,false",
        "running,false",
        "waiting_for_user,false",
        "cancel_requested,false",
        "publishing,false",
        "recovery_blocked,false",
        "done,true",
        "cancelled,true",
        "failed,true"
    })
    void preservesLowercaseStatusAndTerminalClassification(final String wireValue, final boolean terminal)
            throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var status = TaskStatus.valueOf(wireValue);
            final var expected = task("task", "U1", status, 1);
            fixture.add(expected);
            final var document =
                    Files.readString(fixture.configuration.stateRoot().resolve("state.json"));
            assertTrue(document.contains("\"status\":\"" + wireValue + "\""), document);
            assertEquals(List.of(expected), new SlackStore(fixture.configuration, fixture.json).list());
            assertEquals(terminal, expected.terminal());
            assertEquals(terminal, status.terminal());
        }
    }

    @Test
    void rejectsUnknownAndMissingTaskStatuses() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var document = fixture.json.toString(task("task", "U1", TaskStatus.queued, 1));
            assertThrows(
                    RuntimeException.class,
                    () -> fixture.json.fromString(SlackTask.class, document.replace("\"queued\"", "\"unknown\"")));
            assertThrows(IllegalStateException.class, () -> fixture.add(task("task", "U1", null, 1)));
            assertTrue(fixture.store.list().isEmpty());
        }
    }

    @Test
    void persistsGeneratedCodecStatePrivatelyAndLeavesNoTemporaryFiles() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var expected = task("task", "U1", TaskStatus.queued, 1);
            fixture.add(expected);
            final var state = fixture.configuration.stateRoot();
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(state));
            for (final var name : List.of("state.json", "state.lock")) {
                assertEquals(
                        PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(state.resolve(name)));
            }
            assertEquals(List.of(expected), new SlackStore(fixture.configuration, fixture.json).list());
            assertEquals(
                    1,
                    fixture.json
                            .fromString(SlackState.class, Files.readString(state.resolve("state.json")))
                            .version());
            try (final var files = Files.list(state)) {
                assertEquals(
                        List.of("state.json", "state.lock"),
                        files.map(p -> p.getFileName().toString()).sorted().toList());
            }
        }
    }

    @Test
    void callbackAndValidationFailuresLeavePriorStateByteForByteIntact() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var expected = task("task", "U1", TaskStatus.queued, 1);
            fixture.add(expected);
            final var file = fixture.configuration.stateRoot().resolve("state.json");
            final var before = Files.readAllBytes(file);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.store.transaction(tasks -> {
                        tasks.clear();
                        throw new IllegalArgumentException("abort");
                    }));
            assertArrayEquals(before, Files.readAllBytes(file));
            assertThrows(IllegalStateException.class, () -> fixture.add(expected));
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(List.of(expected), fixture.store.list());
        }
    }

    @Test
    void rejectsPublicRootRatherThanRepairingIt() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            Files.createDirectory(fixture.configuration.stateRoot());
            Files.setPosixFilePermissions(
                    fixture.configuration.stateRoot(), PosixFilePermissions.fromString("rwxr-xr-x"));
            assertThrows(IllegalStateException.class, () -> fixture.store.list());
            assertFalse(Files.exists(fixture.configuration.stateRoot().resolve("state.json")));
        }
    }

    @Test
    void rejectsSymlinkRootAndSymlinkStateWithoutTouchingTarget() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var target = Files.createDirectory(root.resolve("outside"));
            Files.createSymbolicLink(fixture.configuration.stateRoot(), target);
            assertThrows(IllegalStateException.class, () -> fixture.store.list());
            Files.delete(fixture.configuration.stateRoot());
            fixture.add();
            final var state = fixture.configuration.stateRoot().resolve("state.json");
            Files.delete(state);
            final var sentinel = Files.writeString(target.resolve("sentinel"), "do-not-touch");
            Files.createSymbolicLink(state, sentinel);
            assertThrows(IllegalStateException.class, () -> fixture.store.list());
            assertEquals("do-not-touch", Files.readString(sentinel));
        }
    }

    @Test
    void rejectsPublicStateFile() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            fixture.add();
            Files.setPosixFilePermissions(
                    fixture.configuration.stateRoot().resolve("state.json"),
                    PosixFilePermissions.fromString("rw-r--r--"));
            assertThrows(IllegalStateException.class, () -> fixture.store.list());
        }
    }

    @Test
    void rejectsPublicLockFile() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            fixture.add();
            Files.setPosixFilePermissions(
                    fixture.configuration.stateRoot().resolve("state.lock"),
                    PosixFilePermissions.fromString("rw-r--r--"));
            assertThrows(IllegalStateException.class, () -> fixture.store.list());
        }
    }

    @Test
    void listingDoesNotCreateOrReplaceStateReceipts() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            assertTrue(fixture.store.list().isEmpty());
            final var state = fixture.configuration.stateRoot().resolve("state.json");
            assertFalse(Files.exists(state));
            fixture.add(task("task", "U1", TaskStatus.queued, 1));
            final var before = Files.readAttributes(state, BasicFileAttributes.class);
            fixture.store.list();
            final var after = Files.readAttributes(state, BasicFileAttributes.class);
            assertEquals(before.fileKey(), after.fileKey());
            assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
        }
    }

    @Test
    void rejectsSymlinkLockWithoutTouchingTarget() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            fixture.add();
            final var lock = fixture.configuration.stateRoot().resolve("state.lock");
            Files.delete(lock);
            final var target = Files.writeString(root.resolve("outside-lock"), "preserve");
            Files.createSymbolicLink(lock, target);
            assertThrows(IllegalStateException.class, () -> fixture.store.list());
            assertEquals("preserve", Files.readString(target));
        }
    }

    @Test
    void invalidSchemaAndDuplicateIdsFailClosed() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            fixture.add();
            final var state = fixture.configuration.stateRoot().resolve("state.json");
            Files.writeString(state, "{\"version\":2,\"tasks\":[]}");
            assertThrows(RuntimeException.class, () -> fixture.store.list());
            assertEquals("{\"version\":2,\"tasks\":[]}", Files.readString(state));
            assertThrows(IllegalArgumentException.class, () -> new SlackState(1, null));
            final var duplicate = task("duplicate", "U1", TaskStatus.queued, 1);
            Files.writeString(state, fixture.json.toString(new SlackState(1, List.of(duplicate, duplicate))));
            assertThrows(IllegalStateException.class, () -> fixture.store.list());
        }
    }
}
