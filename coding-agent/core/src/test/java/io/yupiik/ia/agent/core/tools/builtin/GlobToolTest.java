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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobToolTest {
    private final GlobTool tool = new GlobTool();

    @TempDir
    Path root;

    @Test
    void skipsIgnoredDirsAndHonorsGitignore() throws IOException {
        write(root.resolve(".gitignore"), "vendor/\n");
        write(root.resolve("src/Main.java"), "class Main {}\n");
        write(root.resolve("vendor/Lib.java"), "class Lib {}\n");
        write(root.resolve(".yca/generated/graph/index.bin"), "x");

        final var out = tool.execute(
                        Map.of("pattern", "**/*.java", "path", root.toString()),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();

        assertTrue(out.contains("Main.java"), out);
        assertFalse(out.contains("vendor"), "gitignored dirs must be skipped:\n" + out);
        assertFalse(out.contains(".yca"), "yca store must be skipped:\n" + out);
    }

    @Test
    void capsResultsWithMarker() throws IOException {
        for (var i = 0; i < 600; i++) {
            write(root.resolve("F" + i + ".txt"), "x");
        }
        final var out = tool.execute(
                        Map.of("pattern", "**/*.txt", "path", root.toString()),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();
        assertTrue(out.contains("100 match(es)"), out);
        assertTrue(out.contains("results capped at 100"), out);
    }

    @Test
    void keepsWalkingPastUnreadableDirectory() throws IOException {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "test relies on POSIX permission bits");
        // an unreadable directory/file (e.g. permission-denied subtrees) must not abort the whole walk
        final var denied = root.resolve("denied");
        Files.createDirectories(denied);
        write(denied.resolve("Hidden.java"), "class Hidden {}\n");
        Files.setPosixFilePermissions(denied, PosixFilePermissions.fromString("---------"));
        final var locked = root.resolve("locked.java");
        write(locked, "class Locked {}\n");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        write(root.resolve("ok/Main.java"), "class Main {}\n");

        final var out = tool.execute(
                        Map.of("pattern", "**/*.java", "path", root.toString()),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();

        assertTrue(out.contains("Main.java"), "the walk must continue past an unreadable dir:\n" + out);
        assertFalse(out.contains("Cannot walk"), "unreadable subtrees must not fail the search:\n" + out);
    }

    @Test
    void relativeBasePathResolvesAgainstSessionProjectDir(@TempDir final Path projectDir) throws IOException {
        write(projectDir.resolve("src/Main.java"), "class Main {}\n");
        write(projectDir.resolve("other/Ignored.java"), "ignored\n");
        // relative "src" must be rooted on the session projectDir, not the JVM working directory
        final var out = tool.execute(
                        Map.of("pattern", "**/*.java", "path", "src"),
                        ToolContext.empty(projectDir, new DisabledSandbox(projectDir)))
                .output();
        assertTrue(out.contains("Main.java"), out);
        assertFalse(out.contains("Ignored.java"), out);
    }

    private static void write(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
