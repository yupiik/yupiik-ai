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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Creates and deletes the temporary directories of the nono tests.
 *
 * <p>Those tests cannot use {@code @TempDir}: the production code rejects any directory whose ancestors are
 * group or world writable ({@code PrivateState#validateAncestors} and
 * {@code BaseExecApprovalUi#trustedExecutable}), which {@code java.io.tmpdir} always is because of the sticky
 * bit. They therefore allocate under {@code user.home} and are responsible for their own cleanup.
 *
 * <p>{@link #cleanup(Path)} is the normal path. Pending directories are also registered on a shutdown hook so
 * a run aborting before its teardown does not leak them; the hook is registered before the first allocation
 * and removed as soon as the last directory is cleaned, so a green run leaves no registered hook.
 */
public final class TestTempDirs {
    private static final System.Logger LOGGER = System.getLogger(TestTempDirs.class.getName());
    private static final FileAttribute<Set<PosixFilePermission>> PRIVATE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Set<Path> PENDING = new LinkedHashSet<>();
    private static Thread hook;

    private TestTempDirs() {
        // static helper
    }

    /**
     * @param parent directory in which the private directory is created, must not be group/world writable.
     * @param prefix prefix of the created directory name.
     * @return the created directory, owned by the caller, which must be passed to {@link #cleanup(Path)}.
     * @throws IOException if the directory cannot be created.
     * @throws IllegalStateException if the JVM is already shutting down, since the directory could not be
     *     protected by the shutdown hook.
     */
    public static Path create(final Path parent, final String prefix) throws IOException {
        LOCK.lock();
        try {
            registerHook(); // before the allocation, or a shutdown in between would leave the directory behind
            final Path directory;
            try {
                directory = Files.createTempDirectory(parent, prefix, PRIVATE);
            } catch (final IOException | RuntimeException e) {
                unregisterHookIfIdle();
                throw e;
            }
            PENDING.add(directory);
            return directory;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Deletes a directory created by {@link #create(Path, String)} and everything it contains.
     *
     * <p>A failing deletion is propagated and the directory stays registered so the shutdown hook retries it.
     *
     * @param directory the directory to delete, ignored when already absent.
     * @throws IOException if the directory cannot be deleted.
     */
    public static void cleanup(final Path directory) throws IOException {
        LOCK.lock();
        try {
            deleteTree(directory);
            PENDING.remove(directory);
            unregisterHookIfIdle();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * @return the directories created by {@link #create(Path, String)} and not cleaned up yet.
     */
    static List<Path> pending() {
        LOCK.lock();
        try {
            return new ArrayList<>(PENDING);
        } finally {
            LOCK.unlock();
        }
    }

    private static void cleanupPending() {
        final boolean locked;
        try {
            // bounded so a stuck test cannot hang the JVM exit forever
            locked = LOCK.tryLock(30, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(System.Logger.Level.WARNING, "Interrupted while waiting to delete the temporary directories");
            return;
        }
        if (!locked) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Can't delete the pending temporary directories, cleanup lock still held");
            return;
        }
        try {
            for (final var directory : List.copyOf(PENDING)) {
                try {
                    deleteTree(directory);
                    PENDING.remove(directory);
                } catch (final Throwable e) { // one failure must not skip the remaining directories
                    LOGGER.log(
                            System.Logger.Level.WARNING, "Can't delete the temporary directory '" + directory + "'", e);
                }
            }
            // running from the hook: it cannot be removed from the JVM anymore
        } finally {
            LOCK.unlock();
        }
    }

    private static void deleteTree(final Path directory) throws IOException {
        // NOFOLLOW_LINKS so a replaced root symlink is deleted as a link instead of being seen as absent
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(directory)) { // never delete what a replaced root points to
            Files.deleteIfExists(directory);
            return;
        }
        try (final var paths = Files.walk(directory)) { // does not follow symbolic links
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void registerHook() {
        if (hook != null) {
            return;
        }
        final var candidate =
                Thread.ofPlatform().name("test-temp-dirs-cleanup").unstarted(TestTempDirs::cleanupPending);
        Runtime.getRuntime().addShutdownHook(candidate);
        hook = candidate;
    }

    private static void unregisterHookIfIdle() {
        if (hook == null || !PENDING.isEmpty()) {
            return;
        }
        final var candidates = hook;
        hook = null;
        try {
            Runtime.getRuntime().removeShutdownHook(candidates);
        } catch (final IllegalStateException e) {
            // shutdown already started: the hook runs and finds nothing pending, which is harmless
        }
    }
}
