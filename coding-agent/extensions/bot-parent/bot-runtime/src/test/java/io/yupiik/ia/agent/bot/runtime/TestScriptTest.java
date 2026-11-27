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

import io.yupiik.ia.agent.testing.UnlinkingTempDirDeletionStrategy;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestScriptTest {
    @TempDir(deletionStrategy = UnlinkingTempDirDeletionStrategy.class)
    Path temporary;

    @Test
    void writableBodyPreservesInvokedPathArgumentsEnvironmentAndExitCode() throws Exception {
        final var executable = TestScript.write(
                temporary.resolve("fake command's path"),
                "#!/bin/sh\nprintf '%s\\n' \"$0\" \"$FIXTURE_VALUE\" \"$@\" > \"$0.calls\"\nexit 23\n");
        // Model a writable descriptor inherited by an unrelated concurrent JVM fork.
        // Executing that inode directly would fail with ETXTBSY on Linux.
        try (final var _ = FileChannel.open(
                executable.resolveSibling(executable.getFileName() + ".body"), StandardOpenOption.WRITE)) {
            final var output = temporary.resolve("output");
            final var builder = new ProcessBuilder(executable.toString(), "argument with spaces", "a'b", "")
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().put("FIXTURE_VALUE", "fixture environment");
            final var process = builder.start();
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Fixture command timed out");
                assertEquals(23, process.exitValue(), Files.readString(output));
                assertEquals(
                        executable + "\nfixture environment\nargument with spaces\na'b\n\n",
                        Files.readString(Path.of(executable + ".calls")));
                assertFalse(Files.exists(Path.of(executable + ".body.calls")));
            } finally {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void replacesBehaviorAtTheSameExecutablePath() throws Exception {
        final var executable = temporary.resolve("fake-command");
        TestScript.write(executable, "exit 17\n");
        assertExitCode(executable, 17);
        TestScript.write(executable, "exit 19\n");
        assertExitCode(executable, 19);
    }

    private void assertExitCode(final Path executable, final int expected) throws Exception {
        final var process = new ProcessBuilder(executable.toString()).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Fixture command timed out");
            assertEquals(expected, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }
}
