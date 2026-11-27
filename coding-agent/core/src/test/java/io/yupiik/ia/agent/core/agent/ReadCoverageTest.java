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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.builtin.ReadFileTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadCoverageTest {
    @Test
    void adjacentOverlappingContainedAndUnionRanges() {
        final var coverage = new ReadCoverage();
        final var key = ReadCoverage.key(Path.of("a.txt"));
        assertTrue(coverage.record(key, lines(1, 100)));
        assertTrue(coverage.record(key, lines(101, 200))); // same former 2048 bucket
        assertFalse(coverage.record(key, lines(1, 200)));
        assertFalse(coverage.record(key, lines(50, 150)));
        assertTrue(coverage.record(key, lines(190, 220)));
        assertTrue(coverage.record(key, lines(230, 240)));
        assertTrue(coverage.record(key, lines(220, 230))); // fills the gap
        assertFalse(coverage.record(key, lines(1, 240)));
    }

    @Test
    void realReadHonorsEofAndDoesNotCoverRequestedButUndeliveredLines(@TempDir final Path root) throws IOException {
        final var file = root.resolve("a.txt");
        Files.writeString(file, "one\ntwo\nthree\n");
        final var tool = new ReadFileTool() {};
        final var coverage = new ReadCoverage();
        final var key = ReadCoverage.key(file);
        final var output = tool.execute(
                        Map.of("path", file.toString(), "offset", 2, "limit", 2000),
                        ToolContext.empty(root, new DisabledSandbox(root)))
                .output();
        assertTrue(coverage.record(key, output));
        assertFalse(coverage.record(key, lines(2, 3)));
        assertTrue(coverage.record(key, lines(4, 4)));
        assertTrue(coverage.record(key, "")); // EOF beyond file is not evidence of redundancy
        assertTrue(coverage.record(key, ""));
        assertTrue(coverage.record(key, lines(1, 1)));
    }

    @Test
    void ingestionCapOnlyRecordsCompleteHeadAndTailLines() {
        final var coverage = new ReadCoverage();
        final var key = ReadCoverage.key(Path.of("a.txt"));
        final var delivered = lines(1, 2) + "     3\tpart"
                + "\n[... output truncated (10000 chars), kept head and tail ...]\n"
                + "99\ttail fragment\n" + lines(100, 101) + "   102\tunfinished";
        assertTrue(coverage.record(key, delivered));
        assertFalse(coverage.record(key, lines(1, 2)));
        assertFalse(coverage.record(key, lines(100, 101)));
        assertTrue(coverage.record(key, lines(3, 3)));
        assertTrue(coverage.record(key, lines(50, 50)));
        assertTrue(coverage.record(key, lines(99, 99)));
        assertTrue(coverage.record(key, lines(102, 102)));
    }

    @Test
    void toolTruncationAndContinuationDoNotInventCoverage() {
        final var coverage = new ReadCoverage();
        final var key = ReadCoverage.key(Path.of("a.txt"));
        assertTrue(coverage.record(key, lines(10, 12) + "... output truncated, use offset/limit to read more\n"));
        assertFalse(coverage.record(key, lines(10, 12)));
        assertTrue(coverage.record(key, lines(13, 14) + "... more lines below, use offset=15 to continue\n"));
        assertFalse(coverage.record(key, lines(10, 14)));
        assertTrue(coverage.record(key, lines(15, 15)));
    }

    @Test
    void directoryEntriesUseHeaderOffsetAndDeliveredCount(@TempDir final Path root) throws IOException {
        Files.writeString(root.resolve("a"), "a");
        Files.writeString(root.resolve("b"), "b");
        Files.createDirectory(root.resolve("dir"));
        final var tool = new ReadFileTool() {};
        final var coverage = new ReadCoverage();
        final var key = ReadCoverage.key(root);
        assertTrue(coverage.record(
                key,
                tool.execute(
                                Map.of("path", root.toString(), "limit", 1),
                                ToolContext.empty(root, new DisabledSandbox(root)))
                        .output()));
        assertTrue(coverage.record(
                key,
                tool.execute(
                                Map.of("path", root.toString(), "offset", 2),
                                ToolContext.empty(root, new DisabledSandbox(root)))
                        .output()));
        assertFalse(coverage.record(
                key,
                tool.execute(Map.of("path", root.toString()), ToolContext.empty(root, new DisabledSandbox(root)))
                        .output()));
        assertTrue(coverage.record(key, "Read directory " + root + ", entries 4-4\nnew\n"));
        assertTrue(coverage.record(key, lines(1, 4))); // separate coordinate space
    }

    @Test
    void truncatedDirectoryDoesNotClaimHeaderRangeOrPartialEntries() {
        final var coverage = new ReadCoverage();
        final var key = ReadCoverage.key(Path.of("dir"));
        assertTrue(coverage.record(
                key,
                "Read directory /dir, entries 10-20\na/\nb\npart"
                        + "\n[... output truncated (10000 chars), kept head and tail ...]\ntail\nz\n"));
        assertFalse(coverage.record(key, "Read directory /dir, entries 10-11\na/\nb\n"));
        assertTrue(coverage.record(key, "Read directory /dir, entries 12-12\npartial\n"));
        assertTrue(coverage.record(key, "Read directory /dir, entries 20-20\nz\n"));
        assertTrue(coverage.record(key, "Read directory /dir, entries 21-20\n"));
        assertTrue(coverage.record(key, "Read directory /dir, entries 21-20\n"));
    }

    @Test
    void unknownAndIncompleteOutputsStayConservativelyNovel() {
        final var coverage = new ReadCoverage();
        final var key = ReadCoverage.key(Path.of("a.txt"));
        assertTrue(coverage.supports(key));
        assertFalse(coverage.supports(null));
        assertFalse(coverage.supports("grep:foo"));
        assertFalse(coverage.supports("read-range:"));
        for (final var output : List.of("", "unknown\n", "     1\tpartial", "9223372036854775808\ttoo big\n")) {
            assertTrue(coverage.record(key, output));
            assertTrue(coverage.record(key, output));
        }
        assertTrue(coverage.record(key, null));
        assertTrue(coverage.record("grep:foo", lines(1, 2)));
        assertTrue(coverage.record(key, lines(1, 2)));
    }

    @Test
    void clearAndInvalidationPreserveUnrelatedFiles(@TempDir final Path root) {
        final var coverage = new ReadCoverage();
        final var file = root.resolve("a.txt");
        final var a = ReadCoverage.key(file);
        final var b = ReadCoverage.key(root.resolve("b.txt"));
        final var directory = ReadCoverage.key(root);
        final var listing = "Read directory " + root + ", entries 1-2\na.txt\nb.txt\n";
        assertTrue(coverage.record(a, lines(1, 2)));
        assertTrue(coverage.record(b, lines(1, 2)));
        assertTrue(coverage.record(directory, listing));
        coverage.invalidate(root.resolve("sub/../a.txt"));
        assertTrue(coverage.record(a, lines(1, 2)));
        assertFalse(coverage.record(b, lines(1, 2)));
        assertTrue(coverage.record(directory, listing));
        coverage.clear();
        assertTrue(coverage.record(a, lines(1, 2)));
        assertTrue(coverage.record(b, lines(1, 2)));
        assertTrue(coverage.record(directory, listing));
        assertEquals(a, ReadCoverage.key(root.resolve("sub/../a.txt")));
    }

    @Test
    void executionReadMarkerUsesSessionPathNotOffsetAndLeavesOtherKeysUnchanged(@TempDir final Path root)
            throws ReflectiveOperationException {
        final var execution = new ToolCallExecution(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                () -> false,
                new DisabledSandbox(root));
        final var method =
                ToolCallExecution.class.getDeclaredMethod("coverageKeys", String.class, Map.class, ToolContext.class);
        method.setAccessible(true);
        final var context = new ToolContext(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                () -> false,
                null,
                null,
                root,
                new DisabledSandbox(root),
                -1,
                null,
                null,
                null);
        final var expected = List.of(ReadCoverage.key(root.resolve("a.txt")));
        assertEquals(
                expected, method.invoke(execution, "read", Map.of("path", "./sub/../a.txt", "offset", 1), context));
        assertEquals(expected, method.invoke(execution, "read", Map.of("path", "a.txt", "offset", 9999), context));
        assertEquals(
                expected,
                method.invoke(
                        execution, "read", Map.of("path", root.resolve("a.txt").toString()), context));
        assertEquals(List.of(), method.invoke(execution, "read", Map.of(), context));
        assertEquals(List.of("grep:needle"), method.invoke(execution, "grep", Map.of("pattern", "needle"), context));
        final var normalized = root.resolve("a.txt").normalize().toString();
        assertEquals(
                List.of(normalized + "@1k"),
                method.invoke(execution, "ls", Map.of("path", "a.txt", "offset", 2048), context));
        assertEquals(List.of(normalized), method.invoke(execution, "glob", Map.of("path", "a.txt"), context));
    }

    private static String lines(final int start, final int end) {
        final var output = new StringBuilder();
        for (var line = start; line <= end; line++) {
            output.append(String.format("%6d\tline %d\n", line, line));
        }
        return output.toString();
    }
}
