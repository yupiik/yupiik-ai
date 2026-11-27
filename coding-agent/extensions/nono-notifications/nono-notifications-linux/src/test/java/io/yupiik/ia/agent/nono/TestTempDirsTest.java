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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestTempDirsTest {
    @Test
    void createsPrivateDirectoryAndDeletesItRecursively(@TempDir final Path parent) throws Exception {
        final var directory = TestTempDirs.create(parent, ".test-temp-dirs-");
        final var nested = Files.createDirectories(directory.resolve("a/b"));
        Files.writeString(nested.resolve("file.txt"), "content");

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
        assertTrue(pendingUnder(parent).contains(directory));

        TestTempDirs.cleanup(directory);

        assertFalse(Files.exists(directory));
        assertTrue(pendingUnder(parent).isEmpty());
    }

    @Test
    void cleanupIsIdempotent(@TempDir final Path parent) throws Exception {
        final var directory = TestTempDirs.create(parent, ".test-temp-dirs-");
        Files.writeString(directory.resolve("file.txt"), "content");

        TestTempDirs.cleanup(directory);
        Files.deleteIfExists(directory); // a test may delete its own directory before the teardown
        TestTempDirs.cleanup(directory);

        assertFalse(Files.exists(directory));
    }

    @Test
    void cleanupKeepsSymbolicLinkTargets(@TempDir final Path parent) throws Exception {
        final var sentinel = Files.createDirectories(parent.resolve("sentinel"));
        Files.writeString(sentinel.resolve("keep.txt"), "keep");
        final var directory = TestTempDirs.create(parent, ".test-temp-dirs-");
        Files.createSymbolicLink(directory.resolve("link"), sentinel);
        Files.createSymbolicLink(directory.resolve("nested"), sentinel.resolve("keep.txt"));

        TestTempDirs.cleanup(directory);

        assertFalse(Files.exists(directory, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(sentinel));
        assertEquals("keep", Files.readString(sentinel.resolve("keep.txt")));
    }

    @Test
    void cleanupReplacedRootSymbolicLinkDoesNotDeleteItsTarget(@TempDir final Path parent) throws Exception {
        final var sentinel = Files.createDirectories(parent.resolve("sentinel"));
        Files.writeString(sentinel.resolve("keep.txt"), "keep");
        final var directory = TestTempDirs.create(parent, ".test-temp-dirs-");
        Files.delete(directory); // simulate a test replacing its root with a link
        Files.createSymbolicLink(directory, sentinel);

        TestTempDirs.cleanup(directory);

        assertFalse(Files.exists(directory, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(sentinel));
        assertEquals("keep", Files.readString(sentinel.resolve("keep.txt")));
    }

    @Test
    void concurrentCreationsAndCleanupsDoNotCorruptTheRegistry(@TempDir final Path parent) throws Exception {
        final var start = new CountDownLatch(1);
        try (final var executor = Executors.newFixedThreadPool(8)) {
            final var created = IntStream.range(0, 32)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        final var directory = TestTempDirs.create(parent, ".test-temp-dirs-" + index + "-");
                        TestTempDirs.cleanup(directory);
                        return directory;
                    }))
                    .toList();
            start.countDown();
            for (final var directory : created) {
                assertFalse(Files.exists(directory.get()));
            }
        }
        assertTrue(pendingUnder(parent).isEmpty());
    }

    @Test
    void shutdownHookDeletesDirectoriesLeftBehind(@TempDir final Path parent) throws Exception {
        final var process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp",
                        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        LeakingChild.class.getName(),
                        parent.toString())
                .redirectErrorStream(true)
                .start();
        try (final var output =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            final var line = output.readLine();
            assertNotNull(line, "Child must publish its directory");
            final var published = Path.of(line);
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Child must exit");
            assertEquals(0, process.exitValue(), "Child must exit normally");
            assertFalse(
                    Files.exists(published, LinkOption.NOFOLLOW_LINKS),
                    "Shutdown hook must delete the directory the child did not clean up");
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Child must terminate");
        }
    }

    /**
     * Child process leaking a directory on purpose to check the shutdown hook.
     */
    public static final class LeakingChild {
        private LeakingChild() {}

        /**
         * @param args the parent directory in which the leaked directory is created.
         * @throws Exception if it cannot be created.
         */
        public static void main(final String[] args) throws Exception {
            final var directory = TestTempDirs.create(Path.of(args[0]), ".test-temp-dirs-leak-");
            System.out.println(directory);
            System.out.flush();
            // no cleanup on purpose: only the shutdown hook can delete it
        }
    }

    @Test
    void cleanupToleratesADirectoryDeletedByTheTest(@TempDir final Path parent) throws Exception {
        final var directory = TestTempDirs.create(parent, ".test-temp-dirs-");
        Files.delete(directory);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> TestTempDirs.cleanup(directory));

        assertFalse(Files.exists(directory, LinkOption.NOFOLLOW_LINKS));
    }

    /**
     * Other test classes run concurrently and legitimately own pending directories.
     *
     * @param parent the {@code @TempDir} parent of the current test.
     * @return the pending directories created under that parent.
     */
    private static List<Path> pendingUnder(final Path parent) {
        return TestTempDirs.pending().stream()
                .filter(directory -> directory.startsWith(parent))
                .toList();
    }
}
