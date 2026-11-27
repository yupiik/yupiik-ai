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
package io.yupiik.ia.agent.github.execution;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Optional host-only cache of anonymous public GitHub mirrors.
 * The caller must keep root (including its lock files) private, outside all agent mounts,
 * and must not modify/reuse a workspace concurrently with staging. Persistent mirrors have
 * an operator-managed disk lifecycle; JVM lock storage is bounded and never retains repository names.
 * No credentials, configuration or environment are inherited by host Git.
 */
public final class RepositoryCache {
    private static final Pattern PUBLIC_REPOSITORY =
            Pattern.compile("https://github\\.com/[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9_][A-Za-z0-9_.-]*\\.git");
    // Stripes avoid an unbounded lock registry. A collision only serializes unrelated repositories.
    private static final ReentrantLock[] LOCKS = new ReentrantLock[256];

    static {
        for (int i = 0; i < LOCKS.length; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    private final Path root;
    private final String git;
    private final long timeoutNanos;

    public RepositoryCache(final Path root, final String git, final Duration timeout) {
        if (root == null
                || git == null
                || git.isBlank()
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero()) {
            throw new IllegalArgumentException("Cache root, Git executable and positive timeout are required");
        }
        this.root = root.toAbsolutePath().normalize();
        this.git = git;
        this.timeoutNanos = timeout.toNanos();
    }

    /** Accepts only https://github.com/owner/repository.git, never tokens or arbitrary Git transports. */
    public Path stage(final String repo, final Path workspace) throws IOException {
        if (repo == null || !PUBLIC_REPOSITORY.matcher(repo).matches()) {
            throw new IllegalArgumentException("Expected anonymous public GitHub HTTPS repository URL");
        }
        final var destination = workspace.toAbsolutePath().normalize();
        if (destination.startsWith(root) || root.startsWith(destination)) {
            throw new IllegalArgumentException("Host cache and agent workspace must be disjoint");
        }
        final var key = digest(repo.toLowerCase(Locale.ROOT));
        final var mirror = root.resolve(key + ".git");
        final var lockPath = root.resolve(key + ".lock");
        final var lock = LOCKS[Math.floorMod(lockPath.hashCode(), LOCKS.length)];
        final long started = System.nanoTime();
        try {
            if (!lock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS)) {
                throw new IOException("Timed out waiting for repository cache lock");
            }
            try {
                privateDirectory(root);
                noSymlinks(lockPath);
                try (final var channel = FileChannel.open(
                        lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"));
                    try (final var ignored = acquire(channel, started)) {
                        privateDirectory(destination);
                        final var target = destination.resolve("repository");
                        noSymlinks(target);
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("Workspace repository already exists");
                        }
                        noSymlinkTree(mirror);
                        if (!Files.exists(mirror, LinkOption.NOFOLLOW_LINKS)) {
                            final var temporary = Files.createTempDirectory(
                                    root,
                                    "clone-",
                                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                            try {
                                run(
                                        root,
                                        false,
                                        started,
                                        "clone",
                                        "--mirror",
                                        "--template=",
                                        "--",
                                        repo,
                                        temporary.toString());
                                secureTree(temporary);
                                Files.move(temporary, mirror, StandardCopyOption.ATOMIC_MOVE);
                            } finally {
                                delete(temporary);
                            }
                        } else {
                            secureTree(mirror);
                            // Use the validated URL, not a possibly stale remote URL in the mirror config.
                            run(
                                    root,
                                    false,
                                    started,
                                    "--git-dir=" + mirror,
                                    "fetch",
                                    "--prune",
                                    "--",
                                    repo,
                                    "+refs/*:refs/*");
                            secureTree(mirror);
                        }
                        try {
                            run(
                                    destination,
                                    true,
                                    started,
                                    "clone",
                                    "--no-hardlinks",
                                    "--template=",
                                    "--",
                                    mirror.toString(),
                                    target.toString());
                            noSymlinkTree(target.resolve(".git"));
                            if (!Files.isDirectory(target.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                                    || Files.exists(
                                            target.resolve(".git/objects/info/alternates"),
                                            LinkOption.NOFOLLOW_LINKS)) {
                                throw new IOException("Export must be an independent repository");
                            }
                            run(
                                    destination,
                                    false,
                                    started,
                                    "-C",
                                    target.toString(),
                                    "remote",
                                    "set-url",
                                    "origin",
                                    repo);
                            return target;
                        } catch (final IOException | RuntimeException | InterruptedException e) {
                            delete(target);
                            throw e;
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted staging repository", e);
        }
    }

    private FileLock acquire(final FileChannel channel, final long started) throws IOException, InterruptedException {
        while (true) {
            try {
                final var lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (final OverlappingFileLockException alreadyLocked) {
                // Another host component in this JVM may also hold the OS lock.
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining(started), TimeUnit.MILLISECONDS.toNanos(20)));
        }
    }

    private long remaining(final long started) throws IOException {
        final long remaining = timeoutNanos - (System.nanoTime() - started);
        if (remaining <= 0) {
            throw new IOException("Repository staging timed out");
        }
        return remaining;
    }

    private void run(final Path directory, final boolean local, final long started, final String... arguments)
            throws IOException, InterruptedException {
        final var command = new ArrayList<>(List.of(
                git,
                "-c",
                "credential.helper=",
                "-c",
                "core.hooksPath=/dev/null",
                "-c",
                "protocol.allow=never",
                "-c",
                "protocol.file.allow=" + (local ? "always" : "never"),
                "-c",
                "protocol.https.allow=" + (local ? "never" : "always"),
                "-c",
                "http.followRedirects=false",
                "-c",
                "core.askPass="));
        command.addAll(List.of(arguments));
        final var builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        final var environment = builder.environment();
        environment.clear();
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("HOME", "/dev/null");
        environment.put("XDG_CONFIG_HOME", "/dev/null");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_SYSTEM", "/dev/null");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_ASKPASS", "/bin/false");
        environment.put("GIT_ATTR_NOSYSTEM", "1");
        environment.put("GIT_LFS_SKIP_SMUDGE", "1");
        remaining(started);
        final var process = builder.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(remaining(started), TimeUnit.NANOSECONDS)) {
                throw new IOException("Repository Git command timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Repository Git command failed (exit " + process.exitValue() + ")");
            }
        } finally {
            // Do not leave helpers holding sockets or files after timeout/interruption.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(1, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static String digest(final String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void privateDirectory(final Path directory) throws IOException {
        noSymlinks(directory);
        Files.createDirectories(
                directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    private static void noSymlinks(final Path path) throws IOException {
        Path current = path.toAbsolutePath().getRoot();
        for (final var segment : path.toAbsolutePath()) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not allowed in repository cache paths");
            }
        }
    }

    private static void noSymlinkTree(final Path path) throws IOException {
        noSymlinks(path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try (final var paths = Files.walk(path)) {
                for (final var entry : paths.toList()) {
                    if (Files.isSymbolicLink(entry)) {
                        throw new IOException("Symbolic links are not allowed in Git metadata");
                    }
                }
            }
        }
    }

    private static void secureTree(final Path path) throws IOException {
        noSymlinkTree(path);
        if (Files.exists(path.resolve("objects/info/alternates"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cache must not use object alternates");
        }
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.toList()) {
                Files.setPosixFilePermissions(
                        entry,
                        PosixFilePermissions.fromString(
                                Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ? "rwx------" : "rw-------"));
            }
        }
    }

    private static void delete(final Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try (final var paths = Files.walk(path)) {
                for (final var entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }
}
