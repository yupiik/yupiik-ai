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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiffLayoutTest {
    private static final String EOF = "\\ No newline at end of file";

    @Test
    void creationDeletionAndUpdateUseRealLineCounts() {
        assertEquals(List.of("@@ -0,0 +1,1 @@", "+a"), DiffLayout.between(null, "a\n"));
        assertEquals(List.of("@@ -1,1 +0,0 @@", "-a"), DiffLayout.between("a\n", ""));
        assertEquals(List.of("@@ -1,1 +1,1 @@", "-a", "+b"), DiffLayout.between("a\n", "b\n"));
        assertEquals(List.of("@@ -0,0 +1,1 @@", "+a", EOF), DiffLayout.between(null, "a"));
        assertEquals(List.of("@@ -1,1 +0,0 @@", "-a", EOF), DiffLayout.between("a", ""));
        assertEquals(List.of("@@ -1,1 +1,1 @@", "-a", EOF, "+b", EOF), DiffLayout.between("a", "b"));
        assertEquals(List.of("@@ -1,1 +1,1 @@", "-a", "+b", EOF), DiffLayout.between("a\n", "b"));
        assertEquals(List.of("@@ -1,1 +1,1 @@", "-a", EOF, "+b"), DiffLayout.between("a", "b\n"));
    }

    @Test
    void newlineOnlyChangesReplaceTheFinalRealLine() {
        assertEquals(List.of("@@ -1,1 +1,1 @@", "-a", EOF, "+a"), DiffLayout.between("a", "a\n"));
        assertEquals(List.of("@@ -1,1 +1,1 @@", "-a", "+a", EOF), DiffLayout.between("a\n", "a"));
        assertEquals(
                List.of("@@ -1,2 +1,2 @@", " first", "-last", EOF, "+last"),
                DiffLayout.between("first\nlast", "first\nlast\n"));
        assertEquals(
                List.of("@@ -1,2 +1,2 @@", " first", "-last", "+last", EOF),
                DiffLayout.between("first\nlast\n", "first\nlast"));
        assertEquals(List.of("@@ -1,1 +1,2 @@", "-a", EOF, "+a", "+b"), DiffLayout.between("a", "a\nb\n"));
        assertEquals(List.of("(no text changes)"), DiffLayout.between("a\n", "a\n"));
        assertEquals(List.of("(no text changes)"), DiffLayout.between("a", "a"));
    }

    @Test
    void emptyLinesAndCarriageReturnsRemainContent() {
        assertEquals(List.of("@@ -0,0 +1,1 @@", "+"), DiffLayout.between("", "\n"));
        assertEquals(List.of("@@ -1,1 +0,0 @@", "-"), DiffLayout.between("\n", ""));
        assertEquals(List.of("@@ -0,0 +1,2 @@", "+", "+"), DiffLayout.between(null, "\n\n"));
        assertEquals(List.of("@@ -1,1 +1,2 @@", " a", "+"), DiffLayout.between("a\n", "a\n\n"));
        assertEquals(List.of("@@ -1,1 +1,1 @@", "-a\r", "+a"), DiffLayout.between("a\r\n", "a\n"));
        assertEquals(List.of("@@ -1,1 +1,1 @@", "-a\r", EOF, "+a\r"), DiffLayout.between("a\r", "a\r\n"));
    }

    @Test
    void contextAndLimitsDoNotCountTheSplitSentinel() {
        assertEquals(
                List.of("@@ -2,4 +2,4 @@", " b", " c", " d", "-old", "+new"),
                DiffLayout.between("a\nb\nc\nd\nold\n", "a\nb\nc\nd\nnew\n"));
        assertEquals(
                List.of("@@ -1,2 +1,2 @@", "-old", "+new", " same", EOF), DiffLayout.between("old\nsame", "new\nsame"));
        // An unavailable EOF outside the context must not be annotated on an unrelated line.
        assertEquals(
                List.of("@@ -1,4 +1,4 @@", "-old", "+new", " a", " b", " c"),
                DiffLayout.between("old\na\nb\nc\nd\nlast", "new\na\nb\nc\nd\nlast"));
        assertEquals(
                List.of("(no text changes)"),
                DiffLayout.between("x\n".repeat(DiffLayout.MAX_LINES), "x\n".repeat(DiffLayout.MAX_LINES)));
        assertEquals(
                List.of("(diff omitted: line limit)"),
                DiffLayout.between("x\n".repeat(DiffLayout.MAX_LINES + 1), "new\n"));
        final var replacement = DiffLayout.between("old\n".repeat(2000), "new\n".repeat(2000));
        assertTrue(replacement.size() <= DiffLayout.MAX_OUTPUT_LINES);
        assertTrue(replacement.contains("... deletions truncated"));
        assertTrue(replacement.contains("... additions truncated"));
    }

    @Test
    void headerLikeContentIsPairedInsideNumberedHunksOnly() {
        assertEquals(
                List.of(
                        new DiffLayout.Row("", "", "--- a/one"),
                        new DiffLayout.Row("", "", "+++ b/one"),
                        new DiffLayout.Row("", "", "@@ -1,3 +1,3 @@"),
                        new DiffLayout.Row(" context", " context", ""),
                        new DiffLayout.Row("--- old", "+++ new", ""),
                        new DiffLayout.Row("---tail", "+++tail", ""),
                        new DiffLayout.Row("", "", "--- a/two"),
                        new DiffLayout.Row("", "", "+++ b/two"),
                        new DiffLayout.Row("", "", "@@ -1 +1 @@ function"),
                        new DiffLayout.Row("--- old", "+++ new", "")),
                DiffLayout.split(List.of(
                        "--- a/one",
                        "+++ b/one",
                        "@@ -1,3 +1,3 @@",
                        " context",
                        "--- old",
                        "---tail",
                        "+++ new",
                        "+++tail",
                        "--- a/two",
                        "+++ b/two",
                        "@@ -1 +1 @@ function",
                        "--- old",
                        "+++ new")));
    }

    @Test
    void headerLikeContentInPureAdditionAndDeletionHunks() {
        assertEquals(
                List.of(
                        new DiffLayout.Row("", "", "@@ -0,0 +1,1 @@"),
                        new DiffLayout.Row("", "+++ added", ""),
                        new DiffLayout.Row("", "", "--- a/next"),
                        new DiffLayout.Row("", "", "+++ b/next"),
                        new DiffLayout.Row("", "", "@@ -1,1 +0,0 @@"),
                        new DiffLayout.Row("--- removed", "", "")),
                DiffLayout.split(List.of(
                        "@@ -0,0 +1,1 @@", "+++ added", "--- a/next", "+++ b/next", "@@ -1,1 +0,0 @@", "--- removed")));
        assertEquals(
                List.of(new DiffLayout.Row("", "", "@@ -1,1 +1,1 @@"), new DiffLayout.Row("--- old", "+++ new", "")),
                DiffLayout.split(DiffLayout.between("-- old\n", "++ new\n")));
    }
}
