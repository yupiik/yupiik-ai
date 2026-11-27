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
package io.yupiik.ia.agent.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs property-sensitive assertions in a JVM whose properties are set only at startup. */
public final class TestJvm {
    private TestJvm() {
        // no-op
    }

    public static void run(
            final Path temporaryDirectory,
            final Class<?> main,
            final Map<String, String> properties,
            final String... arguments)
            throws Exception {
        final var output = Files.createTempFile(temporaryDirectory, "test-jvm-", ".log");
        final var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        properties.forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(main.getName());
        command.addAll(List.of(arguments));
        // File redirection drains both streams without pipe-buffer deadlocks or reader threads.
        final var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        final boolean finished;
        try {
            process.getOutputStream().close();
            finished = process.waitFor(30, TimeUnit.SECONDS);
        } finally {
            terminate(process);
        }
        final var diagnostics = Files.readString(output);
        assertTrue(finished, () -> "Child JVM timed out: " + main.getName() + "\n" + diagnostics);
        assertEquals(0, process.exitValue(), () -> "Child JVM failed: " + main.getName() + "\n" + diagnostics);
    }

    private static void terminate(final Process process) {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        // Reap even if the test thread was interrupted, but preserve its interrupt status.
        var interrupted = Thread.interrupted();
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (process.isAlive() && System.nanoTime() < deadline) {
                try {
                    process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (final InterruptedException e) {
                    interrupted = true;
                }
            }
            assertTrue(!process.isAlive(), "Child JVM did not terminate after forced destruction");
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
