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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Fences every supported worker slot before removing old, unreferenced prepared attempts. */
public final class OrphanCleanup {
    private static final Pattern ATTEMPT_NAME = Pattern.compile("yca-github-[A-Za-z0-9-]+");
    private final TaskStore store;
    private final Path home;
    private final Path attempts;
    private final Clock clock;

    public OrphanCleanup(final TaskStore store, final Path home, final Path attempts, final Clock clock) {
        this.store = store;
        this.home = validateRoot(home);
        this.attempts = validateRoot(attempts);
        this.clock = clock;
        if (this.home.startsWith(this.attempts) || this.attempts.startsWith(this.home)) {
            throw new IllegalArgumentException("Dedicated attempt root and dispatcher state must be disjoint");
        }
    }

    public Report cleanup(final Duration minimumAge, final boolean dryRun) {
        if (minimumAge.isNegative() || minimumAge.isZero()) {
            throw new IllegalArgumentException("Positive orphan minimum age required");
        }
        final var leases = new ArrayList<Lease>();
        try {
            // Fence both default serialization and every supported parallelism value. This also serializes collectors.
            leases.add(acquire(home.resolve("worker.lock")));
            for (int slot = 0; slot < 64; slot++) {
                leases.add(acquire(home.resolve("worker-" + slot + ".lock")));
            }
            final var referenced = store.transaction(state -> {
                final var paths = new HashSet<Path>();
                for (final var task : state.list()) {
                    if (task.attemptDirectory() != null) {
                        final var directory = task.attemptDirectory();
                        if (directory.isBlank()) {
                            throw new IllegalStateException("Invalid retained attempt reference; preserve attempts");
                        }
                        paths.add(Path.of(directory).toAbsolutePath().normalize());
                    }
                }
                return paths;
            });
            final var cutoff = clock.instant().minus(minimumAge);
            final var candidates = new ArrayList<Path>();
            try (final var entries = Files.list(attempts)) {
                for (final var path : entries.sorted().toList()) {
                    if (ATTEMPT_NAME.matcher(path.getFileName().toString()).matches()
                            && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            && !referenced.contains(path)
                            && Files.isRegularFile(path.resolve(".prepared-only"), LinkOption.NOFOLLOW_LINKS)
                            && Files.size(path.resolve(".prepared-only")) <= 64
                            && Files.readString(path.resolve(".prepared-only")).equals("yca-prepared-v1\n")
                            && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                                    .toInstant()
                                    .isBefore(cutoff)) {
                        candidates.add(path);
                    }
                }
            }
            if (!dryRun) {
                for (final var path : candidates) {
                    AttemptPreparation.delete(path);
                }
            }
            return new Report(
                    candidates.stream()
                            .map(path -> path.getFileName().toString())
                            .toList(),
                    dryRun);
        } catch (final IOException e) {
            throw new IllegalStateException("Orphan cleanup failed; remaining attempts retained", e);
        } finally {
            for (int index = leases.size() - 1; index >= 0; index--) {
                leases.get(index).close();
            }
        }
    }

    private static Path validateRoot(final Path root) {
        if (root == null) {
            throw new IllegalArgumentException("Explicit dedicated attempt root required for orphan cleanup");
        }
        final var absolute = root.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (final var part : absolute) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Cleanup roots must not contain symbolic links");
            }
        }
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Cleanup roots must be existing directories");
        }
        return absolute;
    }

    private static Lease acquire(final Path path) throws IOException {
        final var channel =
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean acquired = false;
        try {
            final var lock = channel.tryLock();
            if (lock == null) {
                throw new IllegalStateException("Workers are active; orphan cleanup did not run");
            }
            acquired = true;
            return new Lease(channel, lock);
        } catch (final OverlappingFileLockException e) {
            throw new IllegalStateException("Workers are active; orphan cleanup did not run");
        } finally {
            if (!acquired) {
                channel.close();
            }
        }
    }

    private record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() {
            try (channel) {
                lock.release();
            } catch (final IOException e) {
                throw new IllegalStateException("Cannot release cleanup fence", e);
            }
        }
    }

    @JsonModel
    public record Report(
            @Property(documentation = "Unreferenced attempt directory names removed or previewed.")
            List<String> attempts,

            @Property(documentation = "Whether this was a non-destructive preview.")
            boolean dryRun) {}
}
