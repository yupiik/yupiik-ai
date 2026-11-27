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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.text.CharWidth;
import java.util.List;
import org.junit.jupiter.api.Test;

class TuiInputSupportTest {
    @Test
    void slashCommandsAreComplete() {
        assertEquals(25, TuiInputSupport.SLASH_COMMANDS.size());
        assertEquals("/help", TuiInputSupport.SLASH_COMMANDS.get(0));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/quit"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/exit"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/thinking"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/tool-output"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/variant"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/session-id"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/session-flush"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/session-housekeeping"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/maven-repository-knowledge"));
        assertThrows(UnsupportedOperationException.class, () -> TuiInputSupport.SLASH_COMMANDS.add("/nope"));
    }

    @Test
    void nextWordStopsAtWordEnds() {
        assertEquals(3, TuiInputSupport.nextWord("foo bar baz", 0));
        assertEquals(7, TuiInputSupport.nextWord("foo bar baz", 4));
        assertEquals(11, TuiInputSupport.nextWord("foo bar baz", 8));
        assertEquals(3, TuiInputSupport.nextWord("foo", 0));
    }

    @Test
    void previousWordStopsAtWordStarts() {
        assertEquals(0, TuiInputSupport.previousWord("foo bar baz", 3));
        assertEquals(4, TuiInputSupport.previousWord("foo bar baz", 7));
        assertEquals(8, TuiInputSupport.previousWord("foo bar baz", 11));
        assertEquals(0, TuiInputSupport.previousWord("foo bar baz", 0));
    }

    @Test
    void previousWordDoesNotCrashOnEmptyLine() {
        assertEquals(0, TuiInputSupport.previousWord("", 0));
        assertEquals(0, TuiInputSupport.previousWord(" ", 0));
        assertEquals(0, TuiInputSupport.previousWord("   ", 0));
        assertEquals(0, TuiInputSupport.previousWord("   ", 3));
        assertEquals(0, TuiInputSupport.previousWord("  foo", 0));
    }

    @Test
    void firstLineExtractsAndCaps() {
        assertEquals("first", TuiInputSupport.firstLine("first\nsecond"));
        assertEquals("", TuiInputSupport.firstLine(null));
        assertEquals(123, TuiInputSupport.firstLine("x".repeat(500)).length());
        assertTrue(TuiInputSupport.firstLine("x".repeat(500)).endsWith("..."));
    }

    @Test
    void columnAtDisplayWidthMapsCellsToCharIndex() {
        assertEquals(0, TuiInputSupport.columnAtDisplayWidth("abc", 0));
        assertEquals(1, TuiInputSupport.columnAtDisplayWidth("abc", 1));
        assertEquals(2, TuiInputSupport.columnAtDisplayWidth("abc", 2));
        assertEquals(3, TuiInputSupport.columnAtDisplayWidth("abc", 10)); // past the end -> length
        assertEquals(0, TuiInputSupport.columnAtDisplayWidth("", 5));
        assertEquals(0, TuiInputSupport.columnAtDisplayWidth("abc", -1));
        assertEquals(0, TuiInputSupport.columnAtDisplayWidth(null, 2));
        // "中" is 2 cells wide: both its cells map to its start (a caret cannot
        // sit inside a wide char)
        assertEquals(1, TuiInputSupport.columnAtDisplayWidth("a中b", 1));
        assertEquals(1, TuiInputSupport.columnAtDisplayWidth("a中b", 2));
        assertEquals(2, TuiInputSupport.columnAtDisplayWidth("a中b", 3));
        assertEquals(3, TuiInputSupport.columnAtDisplayWidth("a中b", 4));
    }

    @Test
    void wrapProseBreaksAtWidthAndKeepsNewlines() {
        assertEquals(List.of(), TuiInputSupport.wrapProse(null, 10));
        assertEquals(List.of(), TuiInputSupport.wrapProse("", 10));
        assertEquals(List.of("ab"), TuiInputSupport.wrapProse("ab", 10));
        assertEquals(List.of("one", "two"), TuiInputSupport.wrapProse("one\ntwo", 10));
        assertEquals(List.of("", "x"), TuiInputSupport.wrapProse("\nx", 10), "blank lines are preserved");
        // soft wrapping by words
        assertEquals(
                List.of("aaaa bbbb", "cccc dddd", "eeee"), TuiInputSupport.wrapProse("aaaa bbbb cccc dddd eeee", 10));
        // a single overlong token is hard-split and never exceeds the width
        final var token = "x".repeat(25);
        assertTrue(TuiInputSupport.wrapProse(token, 10).stream().allMatch(l -> l.length() <= 10));
        assertEquals(3, TuiInputSupport.wrapProse(token, 10).size());
    }

    @Test
    void wrapProseWidthBehavesLikeWrapProseForAscii() {
        assertEquals(List.of(), TuiInputSupport.wrapProseWidth(null, 10));
        assertEquals(List.of(), TuiInputSupport.wrapProseWidth("", 10));
        assertEquals(List.of("ab"), TuiInputSupport.wrapProseWidth("ab", 10));
        assertEquals(List.of("one", "two"), TuiInputSupport.wrapProseWidth("one\ntwo", 10));
        assertEquals(
                List.of("aaaa bbbb", "cccc dddd", "eeee"),
                TuiInputSupport.wrapProseWidth("aaaa bbbb cccc dddd eeee", 10));
        assertEquals(List.of("", "x"), TuiInputSupport.wrapProseWidth("\nx", 10), "blank lines are preserved");
        // a single overlong ASCII token is hard-split and never exceeds the width
        final var token = "x".repeat(25);
        assertTrue(TuiInputSupport.wrapProseWidth(token, 10).stream().allMatch(l -> CharWidth.of(l) <= 10));
        assertEquals(3, TuiInputSupport.wrapProseWidth(token, 10).size());
    }

    @Test
    void wrapProseWidthCountsWideGlyphCells() {
        // 5 two-cell CJK glyphs = 10 display columns; width 6 fits exactly 3 glyphs per row
        // (the length-based wrapProse would wrongly report a single 5-column row that visually
        // overflows, which is exactly the "modal under-grows and clips CJK" regression)
        assertEquals(List.of("中中中", "中中"), TuiInputSupport.wrapProseWidth("中中中中中", 6));
        // mixed ASCII + wide glyph: the token "ab中cd" is 6 cells wide, hard-split at a clean
        // column boundary (never inside the wide glyph)
        assertEquals(List.of("ab中", "cd", "ef"), TuiInputSupport.wrapProseWidth("ab中cd ef", 4));
        // every produced row stays within the requested display width
        for (final var row : TuiInputSupport.wrapProseWidth("ab中cd ef 中中中中中", 5)) {
            assertTrue(CharWidth.of(row) <= 5, "row '" + row + "' exceeds width 5");
        }
    }
}
