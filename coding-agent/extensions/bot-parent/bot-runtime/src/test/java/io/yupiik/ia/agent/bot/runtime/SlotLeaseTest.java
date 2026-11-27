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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlotLeaseTest {
    @TempDir
    Path home;

    @Test
    void independentSlotsAreExclusiveAndRemainReusableWithoutUnlinking() throws IOException {
        final var file = home.resolve("worker-0.lock");
        final var first = SlotLease.tryAcquire(file).orElseThrow();
        try (first;
                final var _ =
                        SlotLease.tryAcquire(home.resolve("worker-1.lock")).orElseThrow()) {
            assertTrue(SlotLease.tryAcquire(file).isEmpty());
            assertTrue(Files.isRegularFile(file));
        }
        try (final var _ = SlotLease.tryAcquire(file).orElseThrow()) {
            first.close(); // A stale close must never release a subsequent lease.
            assertTrue(SlotLease.tryAcquire(file).isEmpty());
        }
        assertTrue(Files.exists(file));
    }

    @Test
    void sameJvmContentionDoesNotReleaseTheCrossProcessFence() throws Exception {
        final var file = home.resolve("worker.lock");
        try (final var _ = SlotLease.tryAcquire(file).orElseThrow()) {
            assertTrue(SlotLease.tryAcquire(file).isEmpty());
            probe(file, "busy");
        }
        probe(file, "free");
    }

    @Test
    void rejectsSymbolicLockFilesAndParents() throws IOException {
        final var target = Files.writeString(home.resolve("target"), "");
        final var link = Files.createSymbolicLink(home.resolve("link"), target);
        final var parent = Files.createSymbolicLink(home.resolve("parent"), home);
        assertThrows(IOException.class, () -> SlotLease.tryAcquire(link));
        assertThrows(IOException.class, () -> SlotLease.tryAcquire(parent.resolve("other.lock")));
    }

    private void probe(final Path file, final String expected) throws Exception {
        final var command = TestJava.javaCommand(
                "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(),
                file.toString(),
                expected);
        final var process =
                new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(
                    0, process.exitValue(), new String(process.getInputStream().readNBytes(8192)));
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class Probe {
        public static void main(final String[] args) throws IOException {
            final var acquired = SlotLease.tryAcquire(Path.of(args[0]));
            try (final var _ = acquired.orElse(null)) {
                if (acquired.isPresent() != "free".equals(args[1])) {
                    throw new IllegalStateException("Unexpected cross-process slot availability");
                }
            }
        }
    }
}
