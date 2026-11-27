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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrepToolTest {
    private final GrepTool tool = new GrepTool();

    @TempDir
    Path root;

    @Test
    void skipsIgnoredDirsAndHonorsGitignore() throws IOException {
        write(root.resolve(".gitignore"), "generated/\n");
        write(root.resolve("src/App.java"), "needle here\n");
        write(root.resolve("generated/Gen.java"), "needle here\n");
        write(root.resolve(".yca/generated/graph/wiring.json"), "needle here\n");
        write(root.resolve("target/Class.class"), "needle here\n");

        final var out = tool.execute(
                        Map.of("pattern", "needle", "path", root.toString()),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();

        assertTrue(out.contains("App.java"));
        assertFalse(out.contains("Gen.java"), "gitignored paths must be skipped:\n" + out);
        assertFalse(out.contains(".yca"), "yca store must be skipped:\n" + out);
        assertFalse(out.contains("target"), "target must be skipped:\n" + out);
    }

    @Test
    void capsResultsWithMarker() throws IOException {
        for (var i = 0; i < 10; i++) {
            write(root.resolve("F" + i + ".txt"), "hit\n");
        }
        final var out = tool.execute(
                        Map.of("pattern", "hit", "path", root.toString(), "maxResults", "3"),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();
        assertTrue(out.contains("3 match(es)"), out);
        assertTrue(out.contains("results capped at 3"), out);
    }

    @Test
    void keepsWalkingPastUnreadableDirectory() throws IOException {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "test relies on POSIX permission bits");
        // an unreadable directory/file (e.g. permission-denied subtrees) must not abort the whole walk
        final var denied = root.resolve("denied");
        Files.createDirectories(denied);
        write(denied.resolve("Hidden.txt"), "needle hidden\n");
        Files.setPosixFilePermissions(denied, PosixFilePermissions.fromString("---------"));
        final var locked = root.resolve("locked.txt");
        write(locked, "needle locked\n");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        write(root.resolve("ok/App.java"), "needle here\n");

        final var out = tool.execute(
                        Map.of("pattern", "needle", "path", root.toString()),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();

        assertTrue(out.contains("App.java"), "the walk must continue past an unreadable dir:\n" + out);
        assertFalse(out.contains("Cannot search"), "unreadable subtrees must not fail the search:\n" + out);
    }

    @Test
    void relativeBasePathResolvesAgainstSessionProjectDir(@TempDir final Path projectDir) throws IOException {
        write(projectDir.resolve("src/App.java"), "needle here\n");
        write(projectDir.resolve("src/Other.java"), "nothing\n");
        // relative "src" must be rooted on the session projectDir, not the JVM working directory
        final var out = tool.execute(
                        Map.of("pattern", "needle", "path", "src"),
                        ToolContext.empty(projectDir, new DisabledSandbox(projectDir)))
                .output();
        assertTrue(out.contains("App.java"), out);
        assertFalse(out.contains("Other.java"), out);
    }

    @Test
    void unbalancedParenPatternIsRepairedAndReported() throws IOException {
        write(root.resolve("Foo.java"), "boolean handleKey( final int a\nboolean key( final int b\n");
        write(root.resolve("Bar.java"), "void dispatch();\n");

        final var out = tool.execute(
                        Map.of("pattern", "handleKey(|key(", "path", root.toString()),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();

        assertTrue(out.contains("handleKey("), "the repaired pattern must actually match: " + out);
        assertTrue(out.contains("adjusted"), "the adjusted pattern must be surfaced to the model: " + out);
    }

    @Test
    void invalidPatternReturnsHelpfulErrorWithLiteralGuidance() throws IOException {
        write(root.resolve("a.txt"), "x\n");

        final var out = tool.execute(
                Map.of("pattern", "(bad\\p{q}", "path", root.toString()),
                ToolContext.empty(root, new DisabledSandbox(root)));

        assertFalse(out.success(), "an unrepairable pattern must error: " + out.output());
        assertTrue(out.output().contains("literal: true"), "the error must steer toward literal matching: " + out);
    }

    @Test
    void spaceSeparatedPathsSearchAllRoots() throws IOException {
        write(root.resolve("a/One.java"), "needle one\n");
        write(root.resolve("a/No.java"), "nothing\n");
        write(root.resolve("b/Two.java"), "needle two\n");

        final var out = tool.execute(
                        Map.of("pattern", "needle", "path", root.resolve("a") + " " + root.resolve("b")),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();

        assertTrue(out.contains("One.java"), "first root must be searched:\n" + out);
        assertTrue(out.contains("Two.java"), "second root must be searched:\n" + out);
        assertFalse(out.contains("No.java"), "only matches must be reported:\n" + out);
    }

    @Test
    void fileAndDirectoryMixedInOneSpaceSeparatedPath() throws IOException {
        write(root.resolve("README.adoc"), "needle readme\n");
        write(root.resolve("src/main/App.java"), "needle app\n");

        final var out = tool.execute(
                        Map.of(
                                "pattern",
                                "needle",
                                "path",
                                root.resolve("README.adoc") + " " + root.resolve("src/main")),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();

        assertTrue(out.contains("README.adoc"), "the file token must be searched:\n" + out);
        assertTrue(out.contains("App.java"), "the directory token must be searched:\n" + out);
    }

    @Test
    void existingPathContainingSpacesIsKeptVerbatim() throws IOException {
        write(root.resolve("my dir/App.java"), "needle in dir\n");
        write(root.resolve("my notes.txt"), "needle in file\n");

        final var dirOut = tool.execute(
                        Map.of(
                                "pattern",
                                "needle",
                                "path",
                                root.resolve("my dir").toString()),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();
        assertTrue(
                dirOut.contains("App.java"),
                "an existing directory with a space must be searched verbatim:\n" + dirOut);

        final var fileOut = tool.execute(
                        Map.of(
                                "pattern",
                                "needle",
                                "path",
                                root.resolve("my notes.txt").toString()),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();
        assertTrue(
                fileOut.contains("my notes.txt"),
                "an existing file with a space must be searched verbatim:\n" + fileOut);
    }

    @Test
    void missingRootsAreReportedPerRoot() {
        final var out = tool.execute(
                Map.of("pattern", "needle", "path", root.resolve("nope1") + " " + root.resolve("nope2")),
                ToolContext.empty(root, new DisabledSandbox(root)));

        assertFalse(out.success(), "missing roots must error rather than silently return no match: " + out.output());
        assertTrue(out.output().contains("nope1"), out.output());
        assertTrue(out.output().contains("nope2"), out.output());
        assertTrue(out.output().contains("no such file or directory"), out.output());
    }

    @Test
    void capsResultsAcrossMultipleRoots() throws IOException {
        write(root.resolve("a/F1.txt"), "hit\n");
        write(root.resolve("a/F2.txt"), "hit\n");
        write(root.resolve("b/F3.txt"), "hit\n");
        write(root.resolve("b/F4.txt"), "hit\n");

        final var out = tool.execute(
                        Map.of(
                                "pattern", "hit",
                                "path", root.resolve("a") + " " + root.resolve("b"),
                                "maxResults", "2"),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();

        assertTrue(out.contains("2 match(es)"), out);
        assertTrue(out.contains("results capped at 2"), out);
    }

    @Test
    void arrayOfPathsIsAcceptedVerbatim() throws IOException {
        write(root.resolve("a/One.java"), "needle one\n");
        write(root.resolve("b/Two.java"), "needle two\n");

        final var out = tool.execute(
                        Map.of(
                                "pattern",
                                "needle",
                                "path",
                                List.of(
                                        root.resolve("a").toString(),
                                        root.resolve("b").toString())),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();

        assertTrue(out.contains("One.java"), "first array element must be searched:\n" + out);
        assertTrue(out.contains("Two.java"), "second array element must be searched:\n" + out);
    }

    private static void write(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
