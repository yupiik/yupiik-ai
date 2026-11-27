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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.github.support.TestJava;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheLocksTest {
    @TempDir
    Path root;

    @Test
    void failedSameJvmAcquisitionsAndOtherLeaseClosuresDoNotDropKernelLocks() throws Exception {
        final var file = root.resolve("locks");
        final var first = new CacheLocks(file, 1);
        final var second = new CacheLocks(file, 1);
        try (final var slot = first.foreground(System.nanoTime())) {
            assertNotNull(slot);
            assertNull(second.foreground(System.nanoTime()));
            try (final var metadata = second.metadata(System.nanoTime())) {
                assertNotNull(metadata);
                assertEquals("busy", probe(file));
            }
            assertEquals("busy", probe(file));
            assertNull(second.maintenance());
            assertEquals("busy", probe(file));
        }
        assertEquals("acquired", probe(file));
    }

    @Test
    void distinctRangesShareOneDescriptorWithoutSerializingTheirLeases() throws IOException {
        final var locks = new CacheLocks(root.resolve("locks"), 2);
        try (final var first = locks.foreground(System.nanoTime());
                final var second = locks.foreground(System.nanoTime());
                final var metadata = locks.metadata(System.nanoTime())) {
            assertNotNull(first);
            assertNotNull(second);
            assertNotNull(metadata);
            assertTrue(first.slot() != second.slot());
            assertNull(locks.foreground(System.nanoTime()));
        }
        try (final var group = locks.maintenance()) {
            assertNotNull(group);
        }
    }

    private String probe(final Path file) throws Exception {
        final var process = new ProcessBuilder(TestJava.javaCommand(
                        "-cp",
                        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        Probe.class.getName(),
                        file.toString()))
                .redirectErrorStream(true)
                .start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Lock probe timed out");
            assertEquals(0, process.exitValue());
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class Probe {
        public static void main(final String[] arguments) throws IOException {
            try (final var lease = new CacheLocks(Path.of(arguments[0]), 1).foreground(System.nanoTime())) {
                System.out.println(lease == null ? "busy" : "acquired");
            }
        }
    }
}
