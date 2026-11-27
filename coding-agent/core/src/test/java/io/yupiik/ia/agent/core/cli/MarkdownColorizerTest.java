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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarkdownColorizerTest {
    @Test
    void disabledColorizerReturnsInputUnchanged() {
        final var colorizer = new MarkdownColorizer(false);
        assertEquals("# Title", colorizer.colorizeLine("# Title"));
        assertEquals("- item", colorizer.colorizeLine("- item"));
        assertEquals("final var i = 0;", colorizer.colorizeLine("final var i = 0;"));
    }

    @Test
    void headingsAndInlineCodeAreColored() {
        final var colorizer = new MarkdownColorizer(true);
        final var heading = colorizer.colorizeLine("# Title");
        assertTrue(heading.contains("Title"));
        assertTrue(heading.contains("\u001b[1m"));
        final var bullet = colorizer.colorizeLine("- use `yca cli` today");
        assertTrue(bullet.contains("yca cli"));
        assertTrue(bullet.contains("\u001b["));
        assertFalse(bullet.contains("`yca cli`"), "backtick delimiters are dropped from the render");
        assertTrue(bullet.contains("\u001b[1m"), "inline code is bold like the TUI");
    }

    @Test
    void fencedCodeStateIsTrackedPerLine() {
        final var colorizer = new MarkdownColorizer(true);
        assertEquals("```java", colorizer.colorizeLine("```java"));
        assertTrue(colorizer.colorizeLine("final var s = \"x\"; // comment").contains("\u001b["));
        assertEquals("```", colorizer.colorizeLine("```"));
        assertEquals("plain again", colorizer.colorizeLine("plain again"));
    }

    @Test
    void blockquotesAndBoldAndLinksAreRendered() {
        final var colorizer = new MarkdownColorizer(true);
        assertTrue(colorizer.colorizeLine("> quoted").contains("quoted"));
        assertTrue(colorizer.colorizeLine("a **bold** word").contains("\u001b[1mbold\u001b[0m"));
        assertTrue(colorizer.colorizeLine("see [docs](https://yupiik.io)").contains("https://yupiik.io"));
    }

    @Test
    void styleHelpersWrapWithSgr() {
        assertTrue(MarkdownColorizer.success("ok").startsWith("\u001b["));
        assertTrue(MarkdownColorizer.success("ok").endsWith("\u001b[0m"));
        assertTrue(MarkdownColorizer.diffAdd("+ x").startsWith("\u001b["));
        assertTrue(MarkdownColorizer.diffDel("- x").startsWith("\u001b["));
        assertTrue(MarkdownColorizer.dim("x").startsWith("\u001b[2m"));
        assertTrue(MarkdownColorizer.thinking("x").startsWith("\u001b[38;5;240m"));
        assertTrue(MarkdownColorizer.thinking("x").endsWith("\u001b[0m"));
        assertTrue(
                MarkdownColorizer.info("id").startsWith("\u001b[38;5;253m"),
                "info uses the bright body grey so user-facing ids are legible, unlike the muted status()");
        assertTrue(MarkdownColorizer.info("id").endsWith("\u001b[0m"));
    }

    @Test
    void chunkDimmingIsResetAware() {
        final var dimmed = MarkdownColorizer.dimChunk("a\u001b[0mb");
        assertTrue(dimmed.startsWith("\u001b[2m"), dimmed);
        assertTrue(dimmed.contains("\u001b[0m\u001b[2m"), "dim must be re-applied after nested resets: " + dimmed);
        assertTrue(dimmed.endsWith("\u001b[0m"), dimmed);
        assertTrue(MarkdownColorizer.thinkChunk("x").startsWith("\u001b[38;5;240m"));
        assertTrue(MarkdownColorizer.thinkChunk("x").endsWith("\u001b[0m"));
    }

    @Test
    void disabledColorizerLeavesNewStyleOutputUnstyled() {
        final var colorizer = new MarkdownColorizer(false);
        assertEquals("final var i = 0;", colorizer.colorizeLine("final var i = 0;"));
        assertEquals("# Title", colorizer.colorizeLine("# Title"));
    }
}
