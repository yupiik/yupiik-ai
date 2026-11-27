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

import io.yupiik.ia.agent.bot.runtime.SlotLease;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Optional;
import java.util.Set;

/**
 * Opt-in, local-filesystem controller admission with durable fail-closed crash debt.
 * Validate state/attempt root disjointness before acquiring a lease, including default attempt paths.
 * Acquire immediately before claiming/preparing work; an empty result must not claim work.
 * Retain the lease through launcher quiescence and confirmed runtime/gateway termination.
 */
public final class HostAdmission {
    private final HostAdmissionConfiguration configuration;
    private final Path root;

    public HostAdmission(final HostAdmissionConfiguration configuration) {
        this.configuration = configuration;
        this.root = configuration.root().isBlank()
                ? null
                : Path.of(configuration.root()).normalize();
        if (root != null && !root.isAbsolute()) {
            throw new IllegalArgumentException("Host admission root must be absolute");
        }
    }

    public void validateDisjoint(final Path... paths) throws IOException {
        if (root == null) {
            return;
        }
        noSymlinks(root);
        for (final var path : paths) {
            if (path == null) {
                continue;
            }
            final var absolute = path.toAbsolutePath().normalize();
            noSymlinks(absolute);
            if (root.startsWith(absolute) || absolute.startsWith(root)) {
                throw new IllegalArgumentException("Host admission root must be disjoint from state and attempts");
            }
        }
    }

    /**
     * Nonblocking acquisition: empty means busy/full; disabled admission returns a no-op lease.
     * Enabled leases start uncertain, and unresolved reservations fail closed across processes.
     *
     * @return the lease to close on every outcome, or empty when no slot can be acquired
     * @throws IOException if storage is unsafe, inconsistent or contains unresolved crash debt
     */
    public Optional<Lease> tryAcquire() throws IOException {
        if (root == null) {
            return Optional.of(new Lease(null, null));
        }
        noSymlinks(root);
        final var owner = currentOwner();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!configuration.trustedGroup().isBlank()) {
                throw new IOException("Trusted group admission root must be pre-provisioned");
            }
            try {
                Files.createDirectory(
                        root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (final FileAlreadyExistsException raced) {
                // Another controller created it; validate it below.
            }
        }
        validate(root, true, owner);
        final var schemaLock = root.resolve("schema.lock");
        createLock(schemaLock, owner);
        final var guard = SlotLease.tryAcquire(schemaLock);
        if (guard.isEmpty()) {
            return Optional.empty();
        }
        try (final var ignored = guard.orElseThrow()) {
            final var schema = root.resolve("schema");
            final var expected = "host-admission-v1\nslots=" + configuration.slots() + "\n";
            if (!Files.exists(schema, LinkOption.NOFOLLOW_LINKS)) {
                writeNew(schema, expected);
                syncRoot();
            }
            validate(schema, false, owner);
            if (!expected.equals(Files.readString(schema))) {
                throw new IOException(
                        "Host admission schema/count mismatch; stop all participants before manual migration");
            }
            SlotLease selected = null;
            Path reservation = null;
            try {
                // Check every unlocked slot for crash debt before admitting any new work.
                for (int index = 0; index < configuration.slots(); index++) {
                    final var slot = root.resolve("slot-" + index + ".lock");
                    createLock(slot, owner);
                    final var acquired = SlotLease.tryAcquire(slot);
                    if (acquired.isEmpty()) {
                        continue;
                    }
                    final var candidate = acquired.orElseThrow();
                    boolean keep = false;
                    try {
                        final var marker = root.resolve("slot-" + index + ".reservation");
                        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("Unresolved host admission reservation: " + marker);
                        }
                        if (selected == null) {
                            selected = candidate;
                            reservation = marker;
                            keep = true;
                        }
                    } finally {
                        if (!keep) {
                            candidate.close();
                        }
                    }
                }
                if (selected == null) {
                    return Optional.empty();
                }
                writeNew(
                        reservation, "controller-pid=" + ProcessHandle.current().pid() + "\n");
                syncRoot();
                final var lease = new Lease(selected, reservation);
                selected = null;
                return Optional.of(lease);
            } finally {
                if (selected != null) {
                    selected.close();
                }
            }
        }
    }

    private UserPrincipal currentOwner() throws IOException {
        final var user = ProcessHandle.current()
                .info()
                .user()
                .orElseThrow(() -> new IOException("Cannot establish current OS user"));
        return root.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(user);
    }

    private void validate(final Path path, final boolean directory, final UserPrincipal owner) throws IOException {
        noSymlinks(path);
        final var attributes = Files.readAttributes(path, "unix:mode,nlink", LinkOption.NOFOLLOW_LINKS);
        final var mode = ((Number) attributes.get("mode")).intValue();
        if ((directory
                        ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                || (!directory && ((Number) attributes.get("nlink")).intValue() != 1)
                || (mode & 0007) != 0) {
            throw new IOException(
                    "Admission paths must be private real directories/files without hard-link aliases: " + path);
        }
        if (configuration.trustedGroup().isBlank()) {
            if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner) || (mode & 0070) != 0) {
                throw new IOException("Admission paths must be private to the current OS user: " + path);
            }
        } else {
            final var group = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .group();
            if (!group.getName().equals(configuration.trustedGroup()) || (directory && (mode & 02000) == 0)) {
                throw new IOException(
                        "Admission path must belong to the explicit trusted group (root must be setgid): " + path);
            }
        }
    }

    private void createLock(final Path path, final UserPrincipal owner) throws IOException {
        try {
            Files.createFile(path, permissions());
            Files.setPosixFilePermissions(
                    path,
                    PosixFilePermissions.fromString(
                            configuration.trustedGroup().isBlank() ? "rw-------" : "rw-rw----"));
        } catch (final FileAlreadyExistsException exists) {
            // Stable inodes are never replaced.
        }
        validate(path, false, owner);
    }

    private FileAttribute<?> permissions() {
        return PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString(configuration.trustedGroup().isBlank() ? "rw-------" : "rw-rw----"));
    }

    private void writeNew(final Path path, final String value) throws IOException {
        try (final var channel = FileChannel.open(
                path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                permissions())) {
            Files.setPosixFilePermissions(
                    path,
                    PosixFilePermissions.fromString(
                            configuration.trustedGroup().isBlank() ? "rw-------" : "rw-rw----"));
            final var bytes = ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
    }

    private void syncRoot() throws IOException {
        try (final var channel = FileChannel.open(root, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void noSymlinks(final Path path) throws IOException {
        var current = path.getRoot();
        for (final var segment : path) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic admission paths are forbidden: " + current);
            }
        }
    }

    /**
     * Always close the lease, but first record the lifecycle outcome explicitly.
     * A close without confirmation releases the OS lock while retaining durable debt across restarts.
     */
    public final class Lease implements AutoCloseable {
        private final SlotLease slot;
        private final Path reservation;
        private boolean confirmed;
        private boolean closed;

        private Lease(final SlotLease slot, final Path reservation) {
            this.slot = slot;
            this.reservation = reservation;
        }

        /** Confirm only positive runtime/gateway termination or proof that launch was never possible. */
        public void confirmTermination() {
            if (closed) {
                throw new IllegalStateException("Admission lease already closed");
            }
            confirmed = true;
        }

        /** Revoke prior confirmation before every point at which launch becomes possible. */
        public void markUncertain() {
            if (closed) {
                throw new IllegalStateException("Admission lease already closed");
            }
            confirmed = false;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            final var interrupted = Thread.interrupted();
            try {
                if (slot != null) {
                    try (slot) {
                        if (confirmed) {
                            Files.delete(reservation);
                            syncRoot();
                        }
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
