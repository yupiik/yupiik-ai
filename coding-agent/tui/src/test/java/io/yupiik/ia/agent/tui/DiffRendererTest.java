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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiffRendererTest {
    private final DiffRenderer renderer = new DiffRenderer();

    private static String plain(final Line line) {
        final var sb = new StringBuilder();
        for (final var span : line.spans()) {
            sb.append(span.content());
        }
        return sb.toString();
    }

    @Test
    void detectsHunkBasedDiffs() {
        final var diff = """
                @@ -1,2 +1,2 @@
                -old line
                +new line
                """;
        assertTrue(renderer.looksLikeDiff(diff));
        assertFalse(renderer.looksLikeDiff("just some text\nwith + signs\nonly"));
    }

    @Test
    void detectsFileHeaderDiffs() {
        final var diff = """
                --- a/file.txt
                +++ b/file.txt
                -old
                +new
                """;
        assertTrue(renderer.looksLikeDiff(diff));
    }

    @Test
    void rejectsPlainText() {
        assertFalse(renderer.looksLikeDiff("hello world"));
        assertFalse(renderer.looksLikeDiff(""));
    }

    @Test
    void detectsOpenCodeOperationsAndDoesNotDuplicateExistingStats() {
        assertTrue(renderer.looksLikeDiff("*** Begin Patch\n*** Delete File: a\n*** End Patch"));
        assertEquals(
                "@@ section (+1 -1)",
                plain(renderer.render("@@ section (+99 -99)\n-old\n+new", 100, PaletteTest.palette())
                        .getFirst()));
    }

    @Test
    void rendersHunkCounter() {
        final var diff = """
                @@ -1,3 +1,4 @@
                -a
                +b
                 context
                """;
        final var lines = renderer.render(diff, 80, PaletteTest.palette());
        assertTrue(lines.stream().map(DiffRendererTest::plain).anyMatch(l -> l.contains("+1") && l.contains("-1")));
    }

    @Test
    void rendersFileHeaders() {
        final var diff = """
                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                -x
                +y
                """;
        final var lines = renderer.render(diff, 80, PaletteTest.palette());
        final var texts = lines.stream().map(DiffRendererTest::plain).toList();
        assertTrue(texts.stream().anyMatch(l -> l.startsWith("diff --git")));
        assertTrue(texts.stream().anyMatch(l -> l.startsWith("+++ ")));
    }

    @Test
    void addsAndDelsKeptColored() {
        final var diff = """
                @@ -1,1 +1,1 @@
                -removed content
                +added content
                """;
        final var lines = renderer.render(diff, 80, PaletteTest.palette());
        final var texts = lines.stream().map(DiffRendererTest::plain).toList();
        assertTrue(texts.stream().anyMatch(l -> l.startsWith(" -") && l.contains("removed content")));
        assertTrue(texts.stream().anyMatch(l -> l.startsWith(" +") && l.contains("added content")));
    }

    @Test
    void longLinesWrapWithinWidth() {
        final var diff = "@@ -1 +1 @@\n-" + "x".repeat(200) + "\n+" + "y".repeat(200);
        final var lines = renderer.render(diff, 40, PaletteTest.palette());
        for (final var line : lines) {
            assertTrue(plain(line).length() <= 40);
        }
    }

    @Test
    void contextLinesUntouched() {
        final var lines = renderer.render(" plain context\n", 80, PaletteTest.palette());
        assertEquals(
                List.of(" plain context"),
                lines.stream().map(DiffRendererTest::plain).toList());
    }

    @Test
    void unequalReplacementLengthsKeepBothSources() {
        final var palette = PaletteTest.darkPalette();
        for (final var pair : List.of(List.of("OLD", "LONGER"), List.of("LONGER", "NEW"))) {
            final var lines = renderer.render(
                    "-prefix " + pair.get(0) + " suffix\n+prefix " + pair.get(1) + " suffix", 100, palette);
            assertEquals(
                    " - prefix " + pair.get(0) + " suffix", plain(lines.get(0)).stripTrailing());
            assertEquals(
                    " + prefix " + pair.get(1) + " suffix", plain(lines.get(1)).stripTrailing());
            assertEquals(100, CharWidth.of(plain(lines.getFirst())));
            assertEquals(100, CharWidth.of(plain(lines.getLast())));
            assertTrue(lines.get(0).spans().stream()
                    .allMatch(span -> span.style().bg().isPresent()));
            assertTrue(lines.get(1).spans().stream()
                    .allMatch(span -> span.style().bg().isPresent()));
        }
    }

    @Test
    void countersStopAtFileAndOpenCodeOperationBoundaries() {
        final var diff = "--- a/a\n+++ b/a\n@@ first\n-x\n+y\n--- a/b\n+++ b/b\n-z\n+w\n"
                + "*** Update File: c\n@@ second\n-old\n+new\n*** Add File: d\n+extra\n*** End Patch";
        final var texts = renderer.render(diff, 100, PaletteTest.palette()).stream()
                .map(DiffRendererTest::plain)
                .toList();
        assertTrue(texts.contains("@@ first (+1 -1)"), texts.toString());
        assertTrue(texts.contains("@@ second (+1 -1)"), texts.toString());
        assertTrue(texts.contains("*** Update File: c (+1 -1)"), texts.toString());
        assertTrue(texts.contains("*** Add File: d (+1 -0)"), texts.toString());
    }

    @Test
    void emptyDiffRendersNothing() {
        assertTrue(renderer.render("", 80, PaletteTest.palette()).isEmpty());
    }

    @Test
    void autoSplitsAt110AndExplicitSimpleNeverSplits() {
        final var diff = "@@ -1 +1 @@\n-old\n+new";
        for (final var width : List.of(109, 110, 150)) {
            final var lines = renderer.render(diff, width, PaletteTest.darkPalette());
            assertEquals(
                    width >= 110, lines.stream().map(DiffRendererTest::plain).anyMatch(s -> s.contains(" | ")));
            assertTrue(renderer.render(diff, width, PaletteTest.darkPalette(), DiffViewMode.SIMPLE, null, null).stream()
                    .map(DiffRendererTest::plain)
                    .noneMatch(s -> s.contains(" | ")));
        }
        for (final var width : List.of(25, 72, 109)) {
            final var narrow =
                    renderer.render(diff, width, PaletteTest.darkPalette(), DiffViewMode.SIDE_BY_SIDE, null, null);
            assertTrue(narrow.stream().map(DiffRendererTest::plain).anyMatch(s -> s.contains("- old")));
            assertTrue(narrow.stream().map(DiffRendererTest::plain).anyMatch(s -> s.contains("+ new")));
            assertTrue(narrow.stream().map(DiffRendererTest::plain).noneMatch(s -> s.contains(" | ")));
        }
        assertTrue(renderer.render(diff, 0, PaletteTest.darkPalette()).isEmpty());
    }

    @Test
    void contiguousChangesAlignAndContextAppearsOnBothSides() {
        final var diff = "--- a/A.java\n+++ b/A.java\n@@ -7,4 +9,3 @@\n before\n-oldA\n-oldB\n+newA\n after";
        final var rows = renderer.render(diff, 110, PaletteTest.darkPalette()).stream()
                .map(DiffRendererTest::plain)
                .toList();
        assertEquals(7, rows.size());
        assertEquals("--- a/A.java", rows.get(0));
        assertEquals("+++ b/A.java", rows.get(1));
        assertEquals("@@ -7,4 +9,3 @@ (+1 -2)", rows.get(2));
        assertEquals("7    before", halves(rows.get(3)).get(0).strip());
        assertEquals("9    before", halves(rows.get(3)).get(1).strip());
        assertEquals("8  - oldA", halves(rows.get(4)).get(0).strip());
        assertEquals("10  + newA", halves(rows.get(4)).get(1).strip());
        assertEquals("9  - oldB", halves(rows.get(5)).get(0).strip());
        assertTrue(halves(rows.get(5)).get(1).isBlank());
        assertEquals("10    after", halves(rows.get(6)).get(0).strip());
        assertEquals("11    after", halves(rows.get(6)).get(1).strip());
    }

    @Test
    void pureAddAndRemoveOccupyOnlyTheirOwnSide() {
        for (final var add : List.of(true, false)) {
            final var diff = add ? "@@ -0,0 +1,2 @@\n+first\n+second" : "@@ -1,2 +0,0 @@\n-first\n-second";
            final var rows = renderer.render(diff, 110, PaletteTest.darkPalette());
            assertEquals(3, rows.size());
            for (final var row : rows.subList(1, rows.size())) {
                final var sides = halves(plain(row));
                assertTrue(sides.get(add ? 0 : 1).isBlank());
                assertFalse(sides.get(add ? 1 : 0).isBlank());
            }
        }
    }

    @Test
    void openCodeSectionsSplitWithoutInventingLineNumbers() {
        final var diff =
                "*** Begin Patch\n*** Update File: A.java\n@@ change-context\n-old\n+new\n keep\n*** End Patch";
        final var rows = renderer.render(diff, 110, PaletteTest.darkPalette()).stream()
                .map(DiffRendererTest::plain)
                .toList();
        assertEquals("*** Update File: A.java (+1 -1)", rows.get(1));
        assertEquals("@@ change-context (+1 -1)", rows.get(2));
        assertEquals("- old", halves(rows.get(3)).get(0).strip());
        assertEquals("+ new", halves(rows.get(3)).get(1).strip());
        assertEquals("keep", halves(rows.get(4)).get(0).strip());
        assertEquals("keep", halves(rows.get(4)).get(1).strip());
    }

    @Test
    void lineNumbersAreSparseValidatedAndResetAtBoundaries() {
        assertTimeout(Duration.ofSeconds(3), () -> {
            final var rows = renderer
                    .render(
                            "@@ -999999999,1 +888888888,1 @@\n-old\n+new\n"
                                    + "*** Update File: next\n@@ context\n-x\n+y",
                            110,
                            PaletteTest.darkPalette())
                    .stream()
                    .map(DiffRendererTest::plain)
                    .toList();
            assertEquals(5, rows.size());
            assertTrue(halves(rows.get(1)).get(0).strip().startsWith("999999999  - old"));
            assertTrue(halves(rows.get(1)).get(1).strip().startsWith("888888888  + new"));
            assertEquals("- x", halves(rows.get(4)).get(0).strip());
            assertEquals("+ y", halves(rows.get(4)).get(1).strip());
        });
        for (final var header : List.of("@@ context", "@@ -999999999999999999999 +1 @@", "@@ -0 +0 @@")) {
            final var row = renderer.render(header + "\n-old\n+new", 110, PaletteTest.darkPalette())
                    .getLast();
            assertEquals("- old", halves(plain(row)).get(0).strip());
            assertEquals("+ new", halves(plain(row)).get(1).strip());
        }
        final var exhausted =
                renderer.render("@@ -1 +2 @@\n-old\n+new\n-extra\n+extra", 110, PaletteTest.darkPalette());
        assertEquals("- extra", halves(plain(exhausted.getLast())).get(0).strip());
        assertEquals("+ extra", halves(plain(exhausted.getLast())).get(1).strip());
    }

    @Test
    void headerLikeSourceAndNoNewlineMarkersDoNotCorruptHunkNumbers() {
        final var rows = renderer
                .render(
                        "@@ -5,2 +8,2 @@\n--- old source\n\\ No newline at end of file\n+++ new source\n context",
                        110,
                        PaletteTest.darkPalette())
                .stream()
                .map(DiffRendererTest::plain)
                .toList();
        assertEquals("@@ -5,2 +8,2 @@ (+1 -1)", rows.getFirst());
        assertTrue(rows.get(1).contains("5  - -- old source"));
        assertEquals("\\ No newline at end of file", rows.get(2));
        assertTrue(rows.get(3).contains("8  + ++ new source"));
        assertTrue(rows.getLast().contains("6    context"));
        assertTrue(rows.getLast().contains("9    context"));
    }

    @Test
    void tokenThemeForegroundsSurvivePaletteTintAndThemeSwitches() {
        final var palette = PaletteTest.darkPalette();
        final var highlighter = new SyntaxHighlighter();
        for (final var theme : List.of(
                CodeTheme.ONE_DARK,
                CodeTheme.ONE_LIGHT,
                new CodeTheme("111111", "222222", "333333", "444444", "555555", "666666", "777777", "888888"))) {
            highlighter.theme(theme);
            for (final var mode : List.of(DiffViewMode.SIMPLE, DiffViewMode.SIDE_BY_SIDE)) {
                final var lines = renderer.render(
                        "@@ -1,2 +1,2 @@\n-int count = 1;\n+int count = 2;\n int stable = 3;",
                        110,
                        palette,
                        mode,
                        highlighter,
                        "java");
                for (final var value : List.of("1", "2", "3")) {
                    // Number gutters have palette styles; identify actual syntax spans by foreground.
                    assertTrue(lines.stream()
                            .flatMap(line -> line.spans().stream())
                            .anyMatch(span -> span.content().contains(value)
                                    && Color.hex(theme.number())
                                            .equals(span.style().fg().orElse(null))));
                }
                final var changedSpans = lines.stream()
                        .flatMap(line -> line.spans().stream())
                        .filter(span -> span.style().bg().isPresent())
                        .toList();
                assertTrue(changedSpans.stream()
                        .anyMatch(span -> span.style()
                                .bg()
                                .orElseThrow()
                                .equals(TuiColors.blend(palette.error(), palette.backgroundPanel(), 0.85))));
                assertTrue(changedSpans.stream()
                        .anyMatch(span -> span.style()
                                .bg()
                                .orElseThrow()
                                .equals(TuiColors.blend(palette.success(), palette.backgroundPanel(), 0.85))));
                assertTrue(lines.getLast().spans().stream()
                        .allMatch(span -> span.style().bg().isEmpty()));
            }
        }
    }

    @Test
    void customPaletteOverridesControlBothTintAndGutters() {
        final var palette = new Palette(
                "#010101", "#020202", "#123456", "#040404", "#789abc", "#060606", "#070707", "#080808", "#090909",
                "#101010", "#ffffff", "#121212", false);
        final var lines = renderer.render("-old\n+new", 80, palette, DiffViewMode.SIMPLE, null, null);
        assertEquals(
                Color.hex(palette.error()),
                lines.getFirst().spans().getFirst().style().fg().orElseThrow());
        assertEquals(
                Color.hex(palette.success()),
                lines.getLast().spans().getFirst().style().fg().orElseThrow());
        assertEquals(
                Color.rgb(235, 240, 245),
                lines.getFirst().spans().getLast().style().bg().orElseThrow());
        assertEquals(
                Color.rgb(219, 225, 230),
                lines.getLast().spans().getLast().style().bg().orElseThrow());
        assertEquals(
                Color.hex(palette.text()),
                lines.getLast().spans().getLast().style().fg().orElseThrow());
    }

    @Test
    void monoDisablesSyntaxGutterAndBackgroundColorsInEveryMode() {
        for (final var mode : DiffViewMode.values()) {
            final var lines = renderer.render(
                    "--- a/A.java\n+++ b/A.java\n@@ -1,2 +1,2 @@\n-int old = 1;\n+int next = 2;\n int same = 3;",
                    110,
                    PaletteTest.palette(),
                    mode,
                    new SyntaxHighlighter(),
                    "java");
            assertTrue(lines.stream()
                    .flatMap(line -> line.spans().stream())
                    .allMatch(span -> span.style().equals(Style.EMPTY)));
        }
    }

    @Test
    void noColorEnvironmentSuppressesAllRenderedStyles() throws IOException, InterruptedException {
        for (final var noColor : List.of(false, true)) {
            final var process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    NoColorDiffProbe.class.getName());
            process.environment().remove("NO_COLOR");
            if (noColor) {
                process.environment().put("NO_COLOR", "1");
            }
            final var child = process.redirectErrorStream(true).start();
            final String output;
            try (final var stream = child.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            assertEquals(0, child.waitFor(), output);
            assertEquals(Boolean.toString(noColor), output);
        }
    }

    public static class NoColorDiffProbe {
        public static void main(final String[] args) {
            final var p = PaletteTest.darkPalette();
            final var palette = new Palette(
                    p.accent(),
                    p.primary(),
                    p.success(),
                    p.warning(),
                    p.error(),
                    p.info(),
                    p.text(),
                    p.textMuted(),
                    p.border(),
                    p.borderActive(),
                    p.backgroundPanel(),
                    p.yolo(),
                    Palette.noColorRequested());
            final var renderer = new DiffRenderer();
            System.out.println(renderer
                    .render(
                            "@@ -1 +1 @@\n-int old = 1;\n+int next = 2;",
                            110,
                            palette,
                            DiffViewMode.AUTO,
                            new SyntaxHighlighter(),
                            "java")
                    .stream()
                    .flatMap(line -> line.spans().stream())
                    .allMatch(span -> span.style().equals(Style.EMPTY)));
        }
    }

    @Test
    void unicodeTabsAndLongSourceWrapWithoutLossInEitherMode() {
        final var body = "\t\u754c\u754c e\u0301 \ud83d\ude00 \ud83d\udc69\u200d\ud83d\udcbb " + "tail".repeat(100);
        final var expected = "    " + body.substring(1);
        final var simple = renderer.render(
                "+" + body, 17, PaletteTest.darkPalette(), DiffViewMode.SIMPLE, new SyntaxHighlighter(), "text");
        assertEquals(
                expected,
                simple.stream()
                        .map(DiffRendererTest::plain)
                        .map(s -> s.substring(3))
                        .reduce("", String::concat)
                        .stripTrailing());
        assertTrue(simple.stream().allMatch(line -> CharWidth.of(plain(line)) <= 17));
        final var split = renderer.render(
                "-" + body + "\n+" + body + "EXTRA",
                110,
                PaletteTest.darkPalette(),
                DiffViewMode.SIDE_BY_SIDE,
                new SyntaxHighlighter(),
                "text");
        assertTrue(split.stream().allMatch(line -> CharWidth.of(plain(line)) == 110));
        // Padding is distinguishable from this source, whose only spaces are on the first wrapped row.
        final var left = split.stream()
                .map(DiffRendererTest::plain)
                .map(DiffRendererTest::halves)
                .map(sides -> sides.getFirst().substring(3).stripTrailing())
                .reduce("", String::concat);
        final var right = split.stream()
                .map(DiffRendererTest::plain)
                .map(DiffRendererTest::halves)
                .map(sides -> sides.getLast().substring(3).stripTrailing())
                .reduce("", String::concat);
        assertEquals(expected, left);
        assertEquals(expected + "EXTRA", right);
    }

    @Test
    void wrapsHeadersAndHandlesOneCellViewportsSafely() {
        final var header = "*** Update File: " + "long-name".repeat(20);
        final var rows = renderer.render(header, 12, PaletteTest.darkPalette());
        assertEquals(
                header + " (+0 -0)", rows.stream().map(DiffRendererTest::plain).reduce("", String::concat));
        final var narrow = renderer.render("+\u754c\tend", 1, PaletteTest.darkPalette());
        assertTrue(narrow.stream().allMatch(row -> CharWidth.of(plain(row)) <= 1));
        assertTrue(narrow.stream()
                .map(DiffRendererTest::plain)
                .reduce("", String::concat)
                .contains("\\u{754c}"));
    }

    @Test
    void printableAsciiWrappingPreservesTextAndTokenStyles() {
        final var first = Style.EMPTY.bold();
        final var second = Style.EMPTY.underlined();
        final var text = "abc def~";
        assertEquals(text, DiffTextLayout.displayText(text, 3));
        final var rows =
                DiffTextLayout.wrap(text, List.of(Span.styled("abc ", first), Span.styled("def~", second)), 3, 10);
        assertEquals(
                List.of("abc", " de", "f~"),
                rows.stream().map(DiffRendererTest::plain).toList());
        assertEquals(first, rows.getFirst().spans().getFirst().style());
        assertEquals(second, rows.getLast().spans().getFirst().style());
        assertEquals("    e\u0301\\u{1b}", DiffTextLayout.displayText("\te\u0301\u001b", 3));
    }

    @Test
    void hugePatchesAndLongWrappedLinesAreBoundedWithVisibleNotice() {
        // This suite runs test classes concurrently. Assert deterministic work/output bounds
        // below; this generous wall-clock guard detects hangs, not machine throughput.
        assertTimeout(Duration.ofSeconds(30), () -> {
            for (final var diff : List.of(
                    "+row\n".repeat(30_000), "+" + "x".repeat(1_100_000), "@@ -999999999 +999999999 @@\n-a\n+b")) {
                final var rows = renderer.render(diff, 8, PaletteTest.darkPalette());
                assertTrue(rows.size() <= 20_000);
                if (diff.length() > 100_000) {
                    assertEquals("[diff tr", plain(rows.getLast()));
                }
            }
        });
    }

    private static List<String> halves(final String row) {
        final var separator = row.indexOf(" | ");
        assertTrue(separator >= 0, row);
        return List.of(row.substring(0, separator), row.substring(separator + 3));
    }

    @Test
    void spanStyledContentPreserved() {
        final var lines = renderer.render("+kept", 80, PaletteTest.palette());
        assertEquals(1, lines.size());
        final var spans = lines.getFirst().spans();
        assertFalse(spans.isEmpty());
        assertTrue(spans.stream().map(Span::content).reduce("", String::concat).contains("kept"));
    }
}
