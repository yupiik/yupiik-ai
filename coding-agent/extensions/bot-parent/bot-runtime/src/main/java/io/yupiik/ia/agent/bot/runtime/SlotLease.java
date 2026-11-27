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
package io.yupiik.ia.agent.bot.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nonblocking cross-process slot in a trusted private state directory. Never unlink a slot file:
 * replacing its inode would allow simultaneous owners. All callers for a slot must use this API
 * and the same canonical path; lock files must not have hard-link aliases.
 */
public final class SlotLease implements AutoCloseable {
    private static final ReentrantLock GUARD = new ReentrantLock();
    private static final Map<Path, SlotLease> ACTIVE = new HashMap<>();
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;

    private SlotLease(final Path path, final FileChannel channel, final FileLock lock) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
    }

    public static Optional<SlotLease> tryAcquire(final Path file) throws IOException {
        final var path = file.toAbsolutePath().normalize();
        Path current = path.getRoot();
        for (final var segment : path) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic slot paths are forbidden");
            }
        }
        GUARD.lock();
        try {
            // Do not even open another descriptor for a live same-JVM slot. On POSIX, closing
            // a competing descriptor can release process-owned locks on the same inode.
            if (ACTIVE.containsKey(path)) {
                return Optional.empty();
            }
            final var channel = FileChannel.open(
                    path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            boolean acquired = false;
            try {
                final FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (final OverlappingFileLockException busy) {
                    return Optional.empty();
                }
                if (lock == null) {
                    return Optional.empty();
                }
                final var lease = new SlotLease(path, channel, lock);
                ACTIVE.put(path, lease);
                acquired = true;
                return Optional.of(lease);
            } finally {
                if (!acquired) {
                    channel.close();
                }
            }
        } finally {
            GUARD.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        GUARD.lock();
        try {
            if (ACTIVE.get(path) != this) {
                return;
            }
            try (channel) {
                lock.release();
            } finally {
                ACTIVE.remove(path, this);
            }
        } finally {
            GUARD.unlock();
        }
    }
}
