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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.yupiik.ia.agent.tui.TableRenderer.Cell;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableRendererTest {
    private final TableRenderer renderer = new TableRenderer();

    private static String plain(final Line line) {
        final var sb = new StringBuilder();
        for (final var span : line.spans()) {
            sb.append(span.content());
        }
        return sb.toString();
    }

    private static String plainCell(final Cell cell) {
        final var sb = new StringBuilder();
        for (final var span : cell.spans()) {
            sb.append(span.content());
        }
        return sb.toString();
    }

    private static List<String> plainCells(final List<Cell> cells) {
        return cells.stream().map(TableRendererTest::plainCell).toList();
    }

    @Test
    void rendersBorderedGridFittingContent() {
        final var lines = renderer.render(
                List.of(Cell.of("a"), Cell.of("bb")),
                List.of(List.of(Cell.of("1"), Cell.of("22"))),
                80,
                List.of(),
                List.of(),
                Style.EMPTY);
        final var texts = lines.stream().map(TableRendererTest::plain).toList();
        assertEquals("┌───┬────┐", texts.get(0));
        assertTrue(texts.get(1).startsWith("│ a │ bb "), texts.toString());
        assertEquals("├───┼────┤", texts.get(2));
        assertTrue(texts.get(3).startsWith("│ 1 │ 22 "), texts.toString());
        assertEquals("└───┴────┘", texts.get(texts.size() - 1));
    }

    @Test
    void neverExceedsRequestedWidthAndWrapsLongCells() {
        final var lines = renderer.render(
                List.of(Cell.of("name"), Cell.of("value")),
                List.of(List.of(Cell.of("configuration"), Cell.of("alpha beta gamma delta"))),
                20,
                List.of(),
                List.of(),
                Style.EMPTY);
        for (final var line : lines) {
            assertTrue(plain(line).length() <= 20, plain(line));
        }
        // the long cell content is still visible, split over wrapped grid rows
        final var texts =
                String.join("\n", lines.stream().map(TableRendererTest::plain).toList());
        assertTrue(texts.contains("alpha"), texts);
        assertTrue(texts.contains("beta"), texts);
        assertTrue(texts.contains("gamma"), texts);
        assertTrue(texts.contains("delta"), texts);
    }

    @Test
    void headerOnlyTableStillBorders() {
        final var lines = renderer.render(
                List.of(Cell.of("h1"), Cell.of("h2")), List.of(), 40, List.of(), List.of(), Style.EMPTY);
        assertEquals(3, lines.size(), "top border, header, bottom border (no body separator)");
        assertTrue(lines.get(1).spans().stream().anyMatch(s -> s.content().contains("h1")));
    }

    @Test
    void emojiAndWideCharactersKeepGridAligned() {
        final var lines = renderer.render(
                List.of(Cell.of("Run"), Cell.of("Résultat"), Cell.of("Détails")),
                List.of(
                        List.of(Cell.of("T8"), Cell.of("✅ SYNC OK"), Cell.of("load 1230 s")),
                        List.of(Cell.of("T10"), Cell.of("⏳ en génération"), Cell.of("35 min")),
                        List.of(Cell.of("T11"), Cell.of("ok"), Cell.of("borne 30 Go"))),
                120,
                List.of(),
                List.of(),
                Style.EMPTY);
        final var texts = lines.stream().map(TableRendererTest::plain).toList();
        final var widths = texts.stream().mapToInt(TableRenderer::displayWidth).toArray();
        for (int i = 1; i < widths.length; i++) {
            assertEquals(widths[0], widths[i], "line " + i + " has a different visible width: " + texts.get(i));
        }
        // the ✅ row emits fewer characters than the plain row: the 2-cell emoji
        // stole exactly one trailing space worth of padding, keeping the grid on-grid
        assertTrue(texts.get(3).length() < texts.get(5).length(), texts.toString());
    }

    @Test
    void wrapSplitsByDisplayCellsWithoutCuttingWideCharacters() {
        final var parts = TableRenderer.wrapCell(Cell.of("AB⏳EF"), 4);
        assertEquals(List.of("AB⏳", "EF"), plainCells(parts));
        assertTrue(parts.stream().allMatch(p -> TableRenderer.displayWidth(plainCell(p)) <= 4), parts.toString());
    }

    @Test
    void wrapPreservesSpanStylesOfCellText() {
        final var codeStyle = Style.EMPTY.fg(Color.GRAY).dim();
        final var cell = new Cell(List.of(
                Span.styled("run ", Style.EMPTY), Span.styled("code", codeStyle), Span.styled(" end", Style.EMPTY)));
        final var parts = TableRenderer.wrapCell(cell, 8);
        assertEquals(List.of("run code", "end"), plainCells(parts));
        final var span = parts.get(0).spans().stream()
                .filter(s -> s.content().equals("code"))
                .findFirst()
                .orElseThrow();
        assertEquals(codeStyle, span.style());
    }

    @Test
    void variationSelectorBumpsTextEmojiToTwoCells() {
        // bare ❤ is text-presentation (1 cell in jline), ❤️ with VS16 renders on 2 cells
        assertEquals(4, TableRenderer.displayWidth("A❤BC"));
        assertEquals(5, TableRenderer.displayWidth("A❤️BC"));
    }

    @Test
    void emptyStringHasZeroDisplayWidth() {
        // an empty continuation cell must not consume a display cell, otherwise
        // wrapped rows whose last column is empty end up one column short
        assertEquals(0, TableRenderer.displayWidth(""));
        assertEquals(0, TableRenderer.displayWidth(null));
    }

    @Test
    void wrappedRowsWithEmptyTrailingCellsKeepTheSameWidthAsBorders() {
        // last column wraps and the continuation row's cells are empty: every
        // physical grid row (and the trailing bar) must match the border width
        final var lines = renderer.render(
                List.of(Cell.of("name"), Cell.of("value")),
                List.of(List.of(Cell.of("T8"), Cell.of("alpha beta gamma delta"))),
                20,
                List.of(),
                List.of(),
                Style.EMPTY);
        final var texts = lines.stream().map(TableRendererTest::plain).toList();
        final var border = texts.get(0).length();
        for (final var text : texts) {
            assertEquals(border, text.length(), "line has a different visible width: " + text);
        }
        // wrapped body rows (starting with a bar) must end with the right border
        // bar, exactly like non-wrapped rows - this is the off-by-one regression
        for (final var text : texts) {
            if (text.startsWith("│")) {
                assertTrue(text.endsWith("│"), "row must end with a border bar: " + text);
            }
        }
    }

    @Test
    void doubleSpaceInsideBoldCellSurvivesRendering() {
        // a double space inside a bold/styled cell is kept by commonmark and must not
        // collapse in the table word wrapper: the rendered grid carries "a  b" and the
        // column width counts both spaces, so the borders stay aligned
        final var bold = Style.EMPTY.bold();
        final var cell = Cell.of(Span.styled("a  b", bold));
        final var lines =
                renderer.render(List.of(Cell.of("h")), List.of(List.of(cell)), 80, List.of(), List.of(), Style.EMPTY);
        final var plain = lines.stream().map(TableRendererTest::plain).toList();
        assertTrue(plain.stream().anyMatch(l -> l.contains("a  b")), plain.toString());
        // widths align: 4 content cells ("a"," "," ","b") + 2 padding + 2 borders = 8
        assertTrue(plain.stream().allMatch(l -> l.length() == 8), plain.toString());
    }

    @Test
    void cellInlineStyleSurvivesRendering() {
        final var codeStyle = Style.EMPTY.fg(Color.GRAY).dim();
        final var lines = renderer.render(
                List.of(Cell.of("a")),
                List.of(List.of(new Cell(List.of(Span.styled("run ", Style.EMPTY), Span.styled("code", codeStyle))))),
                40,
                List.of(),
                List.of(),
                Style.EMPTY);
        // 0 top, 1 header, 2 separator, 3 body, 4 bottom
        final var body = lines.get(3);
        final var span = body.spans().stream()
                .filter(s -> s.content().equals("code"))
                .findFirst()
                .orElseThrow();
        assertEquals(codeStyle, span.style());
    }
}
