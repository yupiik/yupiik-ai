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
package io.yupiik.ia.agent.nono;

import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** A fresh, private host directory; never put it underneath a sandbox filesystem grant. */
public final class PrivateState implements AutoCloseable {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
    private static final String MARKER = ".nono-managed";
    private static final String LAUNCH_LOCK = ".nono-lock";
    private static final String COORDINATION_LOCK = ".nono-lifecycle.lock";
    private static final String DEFAULT_MARKER = "nono-companion-default-v1\n";
    private static final Set<String> GENERATED_FILES = Set.of(
            MARKER,
            LAUNCH_LOCK,
            "backend.json",
            "ready.json",
            "decisions.jsonl",
            "decisions.previous.jsonl",
            "profile.json",
            "attach.log");
    // FileChannel.lock() does not wait for overlapping locks in the same JVM.
    private static final ReentrantLock CREATION_LOCK = new ReentrantLock();

    private final Path directory;
    private final JsonMapper mapper;
    private final byte[] authorization;
    private final String token;
    private final UserPrincipal owner;
    private final ReentrantLock auditLock = new ReentrantLock();
    private FileChannel launchChannel;
    private FileLock launchLock;
    private boolean closed;

    /**
     * Null selects a managed launch UUID beneath ~/.yupiik/coding-agent/nono.
     * An explicit directory must not exist and must have an existing secure parent.
     * Both forms are removed by close; only default launches are recovered on later default startup.
     */
    public PrivateState(final Path directory, final JsonMapper mapper) throws IOException {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        owner = Path.of("/")
                .getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        final var defaults = directory == null;
        final var home =
                Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        this.directory = defaults
                ? home.resolve(".yupiik/coding-agent/nono/launch-" + UUID.randomUUID())
                : directory.toAbsolutePath().normalize();
        final var parent = this.directory.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Create a private parent directory first");
        }
        if (defaults) {
            validateAncestors(home);
            validateOwnedDirectory(home, false);
            for (final var path : new Path[] {home.resolve(".yupiik"), home.resolve(".yupiik/coding-agent"), parent}) {
                try {
                    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
                } catch (final FileAlreadyExistsException exists) {
                    // Never chmod or take over preexisting directories.
                }
                validateOwnedDirectory(path, true);
            }
        }
        validateAncestors(parent);
        validateOwnedDirectory(parent, defaults);
        final var secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        authorization = ("Basic "
                        + Base64.getEncoder().encodeToString(("nono:" + token).getBytes(StandardCharsets.US_ASCII)))
                .getBytes(StandardCharsets.US_ASCII);

        CREATION_LOCK.lock();
        boolean created = false;
        try (final var coordination = openPrivateFile(parent.resolve(COORDINATION_LOCK));
                final var ignored = coordination.lock()) {
            if (defaults) {
                recover(parent);
            }
            Files.createDirectory(this.directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            created = true;
            launchChannel = openPrivateFile(this.directory.resolve(LAUNCH_LOCK));
            launchLock = launchChannel.lock(); // Held until cleanup completes, including while publishing.
            writeNew(MARKER, defaults ? DEFAULT_MARKER.stripTrailing() : "nono-companion-explicit-v1");
        } catch (final IOException | RuntimeException error) {
            if (created) {
                try {
                    closeLaunch();
                } catch (final IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            throw error;
        } finally {
            CREATION_LOCK.unlock();
        }
    }

    public Path directory() {
        return directory;
    }

    private void validateAncestors(final Path parent) throws IOException {
        for (var path = parent; path != null; path = path.getParent()) {
            final var attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory()
                    || attrs.permissions().contains(PosixFilePermission.GROUP_WRITE)
                    || attrs.permissions().contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IllegalArgumentException(
                        "Runtime ancestor " + path + " must be a real directory, not writable by group or others");
            }
        }
    }

    private void validateOwnedDirectory(final Path path, final boolean privatePermissions) throws IOException {
        final var attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory()) {
            throw new IOException("Private state path " + path + " must be a real directory, not a symlink");
        }
        if (!attrs.owner().equals(owner)) {
            throw new IOException("Private state directory " + path + " must be owned by " + owner + "; actual owner: "
                    + attrs.owner());
        }
        if (privatePermissions && !attrs.permissions().equals(DIRECTORY_PERMISSIONS)) {
            final var mode =
                    ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
            throw new IOException("Private state directory " + path + " must have permissions 700; actual permissions: "
                    + Integer.toOctalString(mode));
        }
    }

    private void validatePrivateFile(final Path path) throws IOException {
        final var attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()
                || !attrs.owner().equals(owner)
                || !attrs.permissions().equals(FILE_PERMISSIONS)
                || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() != 1) {
            throw new IOException("Not an owned private file: " + path);
        }
    }

    private FileChannel openPrivateFile(final Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            validatePrivateFile(path);
        }
        final var channel = FileChannel.open(
                path,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        try {
            validatePrivateFile(path);
            return channel;
        } catch (final IOException | RuntimeException error) {
            channel.close();
            throw error;
        }
    }

    private void recover(final Path base) throws IOException {
        try (final var children = Files.newDirectoryStream(base, "launch-*")) {
            for (final var candidate : children) {
                final var name = candidate.getFileName().toString();
                try {
                    if (!name.equals("launch-" + UUID.fromString(name.substring("launch-".length())))) {
                        continue;
                    }
                    validateOwnedDirectory(candidate, true);
                    validatePrivateFile(candidate.resolve(MARKER));
                    if (Files.size(candidate.resolve(MARKER)) != DEFAULT_MARKER.length()
                            || !DEFAULT_MARKER.equals(Files.readString(candidate.resolve(MARKER)))) {
                        continue;
                    }
                    validatePrivateFile(candidate.resolve(LAUNCH_LOCK));
                    try (final var channel = FileChannel.open(
                                    candidate.resolve(LAUNCH_LOCK),
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS);
                            final var lock = channel.tryLock()) {
                        if (lock != null) {
                            deleteGenerated(candidate);
                        }
                    }
                } catch (final IOException | IllegalArgumentException | OverlappingFileLockException suspiciousOrLive) {
                    // Unknown, modified, or live states are never reclaimed.
                }
            }
        }
    }

    private void deleteGenerated(final Path path) throws IOException {
        validateOwnedDirectory(path, true);
        // Validate the complete flat directory before removing anything; never walk unknown trees.
        try (final var files = Files.newDirectoryStream(path)) {
            for (final var file : files) {
                if (!GENERATED_FILES.contains(file.getFileName().toString())) {
                    throw new IOException("Unknown managed state content: " + file);
                }
                validatePrivateFile(file);
            }
        }
        for (final var name : GENERATED_FILES) {
            if (!name.equals(MARKER) && !name.equals(LAUNCH_LOCK)) {
                Files.deleteIfExists(path.resolve(name));
            }
        }
        Files.deleteIfExists(path.resolve(MARKER));
        Files.deleteIfExists(path.resolve(LAUNCH_LOCK));
        Files.delete(path);
    }

    @Override
    public void close() throws IOException {
        auditLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            CREATION_LOCK.lock();
            try (final var coordination = openPrivateFile(directory.getParent().resolve(COORDINATION_LOCK));
                    final var ignored = coordination.lock()) {
                closeLaunch();
            } finally {
                // Also release the live lock if opening/locking coordination failed.
                try {
                    closeLaunchResources();
                } finally {
                    CREATION_LOCK.unlock();
                }
            }
        } finally {
            auditLock.unlock();
        }
    }

    private void closeLaunch() throws IOException {
        try {
            deleteGenerated(directory);
        } finally {
            closeLaunchResources();
        }
    }

    private void closeLaunchResources() throws IOException {
        try (final var channel = launchChannel;
                final var lock = launchLock) {
            // Release the lock before closing its channel, even if releasing the lock fails.
        } finally {
            launchLock = null;
            launchChannel = null;
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Private state is closed");
        }
    }

    public boolean authenticates(final String header) {
        return header != null
                && header.length() < 256
                && MessageDigest.isEqual(authorization, header.getBytes(StandardCharsets.US_ASCII));
    }

    public void publish(final String host, final int port, final int timeoutSeconds) throws IOException {
        auditLock.lock();
        try {
            ensureOpen();
            final var authority = host.contains(":") ? "[" + host + "]" : host;
            writeNew(
                    "backend.json",
                    mapper.toString(Map.of(
                            "security",
                            Map.of(
                                    "approval_backends",
                                            Map.of(
                                                    "desktop",
                                                    Map.of(
                                                            "type",
                                                            "webhook",
                                                            "url",
                                                            "http://nono:" + token + "@" + authority + ":" + port
                                                                    + "/approve",
                                                            "timeout_secs",
                                                            timeoutSeconds)),
                                    "approval_defaults", Map.of("backend", "desktop")))));
            writeNew(
                    "ready.json",
                    mapper.toString(Map.of(
                            "host",
                            host,
                            "port",
                            port,
                            "path",
                            "/approve",
                            "pid",
                            ProcessHandle.current().pid())));
        } catch (final IOException | RuntimeException error) {
            try {
                close();
            } catch (final IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        } finally {
            auditLock.unlock();
        }
    }

    private void writeNew(final String name, final String text) throws IOException {
        final var file = Files.createFile(
                directory.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, text + "\n", StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    public void audit(final ApprovalProtocol.Approval request, final String decision) throws IOException {
        auditLock.lock();
        try {
            ensureOpen();
            final var file = directory.resolve("decisions.jsonl");
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                Files.createFile(
                        file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            validatePrivateFile(file);
            if (Files.exists(directory.resolve("decisions.previous.jsonl"), LinkOption.NOFOLLOW_LINKS)) {
                validatePrivateFile(directory.resolve("decisions.previous.jsonl"));
            }
            if (Files.size(file) > 1024 * 1024) {
                Files.move(file, directory.resolve("decisions.previous.jsonl"), StandardCopyOption.REPLACE_EXISTING);
                Files.createFile(
                        file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            // No body, URL, headers, target, command arguments or reason is ever persisted.
            final var event = Map.of(
                    "time",
                    Instant.now().toString(),
                    "request",
                    hash(request.id()),
                    "session",
                    hash(request.session()),
                    "type",
                    request.type(),
                    "decision",
                    decision);
            Files.writeString(
                    file, mapper.toString(event) + "\n", StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
        } finally {
            auditLock.unlock();
        }
    }

    private String hash(final String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
