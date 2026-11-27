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
package io.yupiik.ia.agent.github.execution.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kernel range locks with one shared descriptor per active root in this JVM.
 * Closing any descriptor for a POSIX lock file can drop the process's other locks on it;
 * therefore the descriptor closes only after the last attempted or acquired lease settles.
 * Registry entries are removed at that point, not retained as a process-lifetime cache.
 */
final class CacheLocks {
    private static final ConcurrentHashMap<Path, Holder> CHANNELS = new ConcurrentHashMap<>();
    private final Path file;
    private final int slots;

    CacheLocks(final Path file, final int slots) {
        this.file = file.toAbsolutePath().normalize();
        this.slots = slots;
    }

    Lease metadata(final long deadline) throws IOException {
        return await(0, deadline);
    }

    Lease unit(final String key, final long deadline) throws IOException {
        return await(1L + slots + Integer.parseInt(key.substring(0, 2), 16), deadline);
    }

    Lease foreground(final long deadline) throws IOException {
        do {
            for (var slot = 0; slot < slots; slot++) {
                final var lease = attempt(1L + slot);
                if (lease != null) {
                    return lease;
                }
            }
            pause(deadline);
        } while (System.nanoTime() < deadline);
        return null;
    }

    Group maintenance() throws IOException {
        final var leases = new ArrayList<Lease>(slots);
        try {
            for (var slot = 0; slot < slots; slot++) {
                final var lease = attempt(1L + slot);
                if (lease == null) {
                    close(leases);
                    return null;
                }
                leases.add(lease);
            }
            return new Group(leases);
        } catch (final IOException | RuntimeException failure) {
            close(leases);
            throw failure;
        }
    }

    private Lease await(final long offset, final long deadline) throws IOException {
        do {
            final var lease = attempt(offset);
            if (lease != null) {
                return lease;
            }
            pause(deadline);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private Lease attempt(final long offset) throws IOException {
        final Holder holder;
        try {
            holder = CHANNELS.compute(file, (key, existing) -> {
                var selected = existing;
                if (selected == null) {
                    try {
                        selected = new Holder(FileChannel.open(
                                key, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
                    } catch (final IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                }
                selected.references++;
                return selected;
            });
        } catch (final UncheckedIOException failure) {
            throw failure.getCause();
        }
        boolean acquired = false;
        try {
            final FileLock lock;
            try {
                lock = holder.channel.tryLock(offset, 1, false);
            } catch (final OverlappingFileLockException busy) {
                return null;
            }
            if (lock == null) {
                return null;
            }
            acquired = true;
            return new Lease(file, holder, lock, (int) offset - 1, new AtomicBoolean());
        } finally {
            if (!acquired) {
                release(file, holder);
            }
        }
    }

    private static void release(final Path file, final Holder expected) throws IOException {
        try {
            CHANNELS.compute(file, (key, holder) -> {
                if (holder != expected || holder.references < 1) {
                    throw new IllegalStateException("Invalid cache lock ownership");
                }
                if (--holder.references == 0) {
                    try {
                        holder.channel.close();
                    } catch (final IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                    return null;
                }
                return holder;
            });
        } catch (final UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static void pause(final long deadline) throws IOException {
        if (System.nanoTime() >= deadline) {
            return;
        }
        try {
            Thread.sleep(5);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for cache admission", interrupted);
        }
    }

    private static void close(final List<Lease> leases) throws IOException {
        IOException failure = null;
        for (final var lease : leases.reversed()) {
            try {
                lease.close();
            } catch (final IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static final class Holder {
        private final FileChannel channel;
        private int references;

        private Holder(final FileChannel channel) {
            this.channel = channel;
        }
    }

    record Group(List<Lease> leases) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            CacheLocks.close(leases);
        }
    }

    record Lease(Path file, Holder holder, FileLock lock, int slot, AtomicBoolean closed) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                try {
                    lock.release();
                } finally {
                    CacheLocks.release(file, holder);
                }
            }
        }
    }
}
