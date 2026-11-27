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
package io.yupiik.ia.agent.bot.runtime.admission;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.bot.runtime.SlotLease;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostAdmissionTest {
    @TempDir
    Path home;

    @Test
    void disabledDoesNotTouchFilesystemAndNeedsNoConfirmation() throws IOException {
        final var admission = new HostAdmission(new HostAdmissionConfiguration("", 1, ""));
        try (final var _ = admission.tryAcquire().orElseThrow()) {
            admission.validateDisjoint(home);
        }
        try (final var _ = admission.tryAcquire().orElseThrow()) {
            assertEquals(0, home.toFile().list().length);
        }
    }

    @Test
    void independentInstancesShareBudgetAndPreserveInodes() throws IOException {
        final var admission = admission(2);
        final var first = admission.tryAcquire().orElseThrow();
        final var inode = Files.getAttribute(root().resolve("slot-0.lock"), "unix:ino");
        try (first;
                final var second = admission(2).tryAcquire().orElseThrow()) {
            assertTrue(admission(2).tryAcquire().isEmpty());
            first.confirmTermination();
            second.confirmTermination();
        }
        try (final var next = admission(2).tryAcquire().orElseThrow()) {
            first.close();
            assertEquals(inode, Files.getAttribute(root().resolve("slot-0.lock"), "unix:ino"));
            assertTrue(Files.exists(root().resolve("slot-1.lock")));
            next.confirmTermination();
        }
        assertFalse(Files.exists(root().resolve("slot-0.reservation")));
    }

    @Test
    void countMismatchIsRejectedEvenWhileAllSlotsAreHeld() throws IOException {
        try (final var lease = admission(1).tryAcquire().orElseThrow()) {
            assertThrows(IOException.class, () -> admission(2).tryAcquire());
            lease.confirmTermination();
        }
        assertThrows(IOException.class, () -> admission(2).tryAcquire());
        assertEquals("host-admission-v1\nslots=1\n", Files.readString(root().resolve("schema")));
    }

    @Test
    void uncertainCloseBlocksEntireBudgetInNewInstancesUntilManualResolution() throws IOException {
        final var first = admission(2).tryAcquire().orElseThrow();
        final var second = admission(2).tryAcquire().orElseThrow();
        first.confirmTermination();
        first.close();
        second.close(); // Free slot 0 must not hide debt in slot 1.
        assertThrows(IOException.class, () -> admission(2).tryAcquire());
        assertTrue(Files.exists(root().resolve("slot-1.reservation")));
        Files.delete(root().resolve("slot-1.reservation")); // Operator has established quiescence.
        try (final var recovered = admission(2).tryAcquire().orElseThrow()) {
            recovered.confirmTermination();
        }
    }

    @Test
    void markingUncertainRevokesPreviousConfirmation() throws IOException {
        try (final var lease = admission(1).tryAcquire().orElseThrow()) {
            lease.confirmTermination();
            lease.markUncertain();
        }
        assertThrows(IOException.class, () -> admission(1).tryAcquire());
    }

    @Test
    void schemaContentIsFailClosedAndSchemaLockIsNonblocking() throws IOException {
        try (final var lease = admission(1).tryAcquire().orElseThrow()) {
            lease.confirmTermination();
        }
        try (final var _ = SlotLease.tryAcquire(root().resolve("schema.lock")).orElseThrow()) {
            assertTrue(admission(1).tryAcquire().isEmpty());
        }
        Files.writeString(root().resolve("schema"), "");
        assertThrows(IOException.class, () -> admission(1).tryAcquire());
    }

    @Test
    void rejectsSymlinkRootAncestorAndFiles() throws IOException {
        final var real = Files.createDirectory(home.resolve("real"));
        final var alias = Files.createSymbolicLink(home.resolve("alias"), real);
        final var symbolic = new HostAdmission(
                new HostAdmissionConfiguration(alias.resolve("budget").toString(), 1, ""));
        assertThrows(IOException.class, symbolic::tryAcquire);
        Files.createSymbolicLink(root(), real);
        assertThrows(IOException.class, () -> admission(1).tryAcquire());
        Files.delete(root());
        try (final var lease = admission(1).tryAcquire().orElseThrow()) {
            lease.confirmTermination();
        }
        Files.createSymbolicLink(root().resolve("slot-1.lock"), root().resolve("slot-0.lock"));
        // Same schema count, but a symlink at a known slot is forbidden.
        Files.writeString(root().resolve("schema"), "host-admission-v1\nslots=2\n");
        assertThrows(IOException.class, () -> admission(2).tryAcquire());
    }

    @Test
    void rejectsHardLinkedLockAndInsecureRoot() throws IOException {
        Files.createDirectory(root());
        Files.setPosixFilePermissions(root(), PosixFilePermissions.fromString("rwxr-x---"));
        assertThrows(IOException.class, () -> admission(1).tryAcquire());
        Files.setPosixFilePermissions(root(), PosixFilePermissions.fromString("rwx------"));
        try (final var lease = admission(1).tryAcquire().orElseThrow()) {
            lease.confirmTermination();
        }
        Files.createLink(home.resolve("alias-lock"), root().resolve("slot-0.lock"));
        assertThrows(IOException.class, () -> admission(1).tryAcquire());
    }

    @Test
    void explicitTrustedGroupUsesPreProvisionedSetgidRoot() throws IOException {
        Files.createDirectory(root());
        Files.setAttribute(root(), "unix:mode", 02770);
        final var group =
                Files.readAttributes(root(), PosixFileAttributes.class).group().getName();
        final var configuration = new HostAdmissionConfiguration(root().toString(), 1, group);
        try (final var lease = new HostAdmission(configuration).tryAcquire().orElseThrow()) {
            assertTrue(new HostAdmission(configuration).tryAcquire().isEmpty());
            assertEquals(
                    PosixFilePermissions.fromString("rw-rw----"),
                    Files.getPosixFilePermissions(root().resolve("slot-0.lock")));
            lease.confirmTermination();
        }
        assertThrows(IOException.class, () -> admission(1).tryAcquire());
    }

    @Test
    void validatesDisjointRootsAndConfiguration() throws IOException {
        final var admission = admission(1);
        admission.validateDisjoint(home.resolve("state"), home.resolve("attempts"));
        assertThrows(IllegalArgumentException.class, () -> admission.validateDisjoint(home));
        assertThrows(IllegalArgumentException.class, () -> admission.validateDisjoint(root().resolve("attempts")));
        assertThrows(IllegalArgumentException.class, () -> new HostAdmissionConfiguration("", 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new HostAdmissionConfiguration("", 1, "controllers"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HostAdmission(new HostAdmissionConfiguration("relative", 1, "")));
        final var grouped = new HostAdmission(new HostAdmissionConfiguration(root().toString(), 1, "controllers"));
        assertThrows(IOException.class, grouped::tryAcquire); // Never implicitly provision a group root.
    }

    private HostAdmission admission(final int slots) {
        return new HostAdmission(new HostAdmissionConfiguration(root().toString(), slots, ""));
    }

    private Path root() {
        return home.resolve("admission");
    }
}
