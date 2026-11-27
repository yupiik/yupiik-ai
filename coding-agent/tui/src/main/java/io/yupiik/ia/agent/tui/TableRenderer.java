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

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.jline.utils.WCWidth;

/**
 * Renders a markdown GFM table as a bordered grid of {@link Line}s that never
 * exceeds the available width: column widths are computed from the natural
 * content measured in terminal display cells (East Asian wide and emoji glyphs
 * count 2), scaled down proportionally when the table is wider than the pane
 * (per-column floors, the last column absorbing the remainder) and long cells
 * are word-wrapped (overlong tokens hard-split). Every physical grid row stays
 * a separate {@link Line} so the output participates in the transcript's
 * text pipeline (search rows, click hit-areas, Ctrl+O overlay, Ctrl+Y modes).
 *
 * <p>Pure and terminal-free: the input is {@link Cell}s of explicitly styled
 * spans (commonmark is peeled off by the caller) and the style/prefix spans are
 * render arguments, so a single instance is shared and the class is unit
 * testable without a display. Column widths are computed from the raw text, so
 * inline styles never reflow the grid; the header is bold; borders reuse the
 * base style so the grid follows the current theme.</p>
 */
public final class TableRenderer {
    private static final char TOP_LEFT = '┌';
    private static final char TOP_RIGHT = '┐';
    private static final char BOTTOM_LEFT = '└';
    private static final char BOTTOM_RIGHT = '┘';
    private static final char HORIZONTAL = '─';
    private static final char VERTICAL = '│';
    private static final char TOP_T = '┬';
    private static final char MIDDLE_T = '┼';
    private static final char BOTTOM_T = '┴';
    private static final char LEFT_T = '├';
    private static final char RIGHT_T = '┤';
    /** Horizontal padding of each cell: one space on each side. */
    private static final int CELL_PAD = 2;
    /** Matches Unicode characters with Emoji_Presentation = Yes (rendered on one cell, displayed on two by most terminals). */
    private static final Pattern EMOJI_PRESENTATION = Pattern.compile("\\p{IsEmoji_Presentation}");
    /** Variation selector 16: turns the preceding text-presentation glyph into an emoji-presentation one. */
    private static final int VS16 = 0xFE0F;

    /** One table cell: its text split into explicitly styled spans. */
    public record Cell(List<Span> spans) {
        private static final Cell EMPTY = new Cell(List.of());

        public static Cell of(final String text) {
            return new Cell(List.of(Span.styled(text, Style.EMPTY)));
        }

        public static Cell of(final Span... spans) {
            return new Cell(List.of(spans));
        }
    }

    /**
     * Visible (terminal) width of {@code text} in cells: East Asian wide/fullwidth
     * and astral characters count 2 via jline's wcwidth, BMP emoji-presentation
     * glyphs (e.g. ✅ ⏳) are forced to 2 (jline reports them as narrow) and a
     * trailing variation selector 16 bumps the preceding 1-cell glyph to 2.
     * Non-empty input never reports 0.
     */
    static int displayWidth(final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        var total = 0;
        var upgradable = false; // previous glyph is 1-cell text-presentation and a VS16 can make it 2-cell
        for (int i = 0; i < text.length(); ) {
            final var cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == VS16) {
                if (upgradable) {
                    total++;
                }
                upgradable = false;
                continue;
            }
            final var cw = charWidth(cp);
            total += cw;
            upgradable = cw == 1 && !isEmojiPresentation(cp);
        }
        return Math.max(1, total);
    }

    private static int charWidth(final int cp) {
        final var w = WCWidth.wcwidth(cp);
        if (w > 1 || w < 0) {
            return w > 1 ? w : 1; // tabs/controls keep a single legacy cell
        }
        if (w == 0) {
            return 0; // combining marks, ZWJ, variation selectors
        }
        return isEmojiPresentation(cp) ? 2 : 1;
    }

    private static boolean isEmojiPresentation(final int cp) {
        return EMOJI_PRESENTATION.matcher(new String(Character.toChars(cp))).matches();
    }

    /**
     * Renders a table into bordered lines.
     *
     * @param header       the header cells, or {@code null}/empty when the table has none
     * @param rows         the body rows (may be empty)
     * @param width        the maximum width of a rendered line
     * @param prefixFirst  the prefix spans of the first physical grid row (list/quote marker)
     * @param prefixCont   the prefix spans of the following grid rows
     * @param base         the base style for cells and borders (header is bolded)
     */
    public List<Line> render(
            final List<Cell> header,
            final List<List<Cell>> rows,
            final int width,
            final List<Span> prefixFirst,
            final List<Span> prefixCont,
            final Style base) {
        final var out = new ArrayList<Line>();
        final var columns = Math.max(
                header == null ? 0 : header.size(),
                rows.stream().mapToInt(List::size).max().orElse(0));
        if (columns == 0 || width <= 0) {
            return out;
        }
        final var avail = Math.max(1, width - widthOf(prefixFirst));
        final var frame = (columns + 1) + CELL_PAD * columns; // borders + per-cell padding
        final var contentBudget = Math.max(1, avail - frame);
        final var natural = new int[columns];
        collectNatural(header, rows, natural);
        final var widths = fit(natural, contentBudget, columns);
        final var listWidths = new ArrayList<Integer>(widths.length);
        for (final var w : widths) {
            listWidths.add(w);
        }

        final var firstPrefix = prefixFirst;
        final var contPrefix = prefixCont.isEmpty() ? prefixFirst : prefixCont;
        // the top border carries the first-line prefix (list/quote marker); every
        // following grid line uses the continuation prefix so the marker is not repeated
        appendBorder(out, listWidths, TOP_LEFT, TOP_T, TOP_RIGHT, firstPrefix, base);
        if (header != null && !header.isEmpty()) {
            final var grid = gridRow(wrapCells(header, widths), listWidths);
            appendGridLines(out, grid, contPrefix, base.bold(), listWidths);
            if (!rows.isEmpty()) {
                // the header/body separator exists only when there is a body to separate
                appendBorder(out, listWidths, LEFT_T, MIDDLE_T, RIGHT_T, contPrefix, base);
            }
        }
        for (final var row : rows) {
            final var grid = gridRow(wrapCells(row, widths), listWidths);
            appendGridLines(out, grid, contPrefix, base, listWidths);
        }
        appendBorder(out, listWidths, BOTTOM_LEFT, BOTTOM_T, BOTTOM_RIGHT, contPrefix, base);
        return out;
    }

    /** Fills {@code natural} with the full single-line width of the widest cell (min 1). */
    private static void collectNatural(final List<Cell> header, final List<List<Cell>> rows, final int[] natural) {
        Arrays.fill(natural, 1);
        if (header != null) {
            for (int i = 0; i < header.size() && i < natural.length; i++) {
                natural[i] = Math.max(natural[i], cellWidth(header.get(i)));
            }
        }
        for (final var row : rows) {
            for (int i = 0; i < row.size() && i < natural.length; i++) {
                natural[i] = Math.max(natural[i], cellWidth(row.get(i)));
            }
        }
    }

    private static int cellWidth(final Cell cell) {
        return isBlank(cell.spans()) ? 1 : spanWidth(cell.spans());
    }

    private static boolean isBlank(final List<Span> spans) {
        for (final var span : spans) {
            if (span.content() != null && !span.content().isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** Display width of a span list = sum of the display widths of its raw text. */
    private static int spanWidth(final List<Span> spans) {
        var w = 0;
        for (final var span : spans) {
            w += displayWidth(span.content());
        }
        return w;
    }

    /**
     * Scales the natural widths to fit {@code budget}: when the natural total
     * already fits, the natural widths are kept (the table stays content-tight,
     * never stretched to the full pane); otherwise a proportional shrink with
     * per-column floors (4 then 3/2/1) applies, trimming from the widest columns
     * when even the floor-1 minimum is too large. The last column absorbs any
     * rounding remainder so the grid borders always line up.
     */
    static int[] fit(final int[] natural, final int budget, final int columns) {
        final var widths = natural.clone();
        var total = sum(widths);
        if (total <= budget) {
            return widths;
        }
        for (int floor = 4; floor >= 1; floor--) {
            final var next = new int[columns];
            var t = 0;
            for (int i = 0; i < columns; i++) {
                next[i] = Math.max(floor, (int) Math.round((double) natural[i] * budget / total));
                t += next[i];
            }
            if (t <= budget) {
                next[columns - 1] += budget - t;
                return next;
            }
        }
        // minimum (1 per column) still exceeds: reduce the widest columns until it fits
        var t = sum(widths);
        while (t > budget) {
            var widest = 0;
            for (int i = 1; i < columns; i++) {
                if (widths[i] > widths[widest]) {
                    widest = i;
                }
            }
            if (widths[widest] <= 1) {
                break; // cannot shrink further; let the terminal clip
            }
            widths[widest]--;
            t--;
        }
        return widths;
    }

    private static int sum(final int[] values) {
        var t = 0;
        for (final var v : values) {
            t += v;
        }
        return t;
    }

    /** Wraps every cell of a row to its column width; short rows are padded with empty cells. */
    private static List<List<Cell>> wrapCells(final List<Cell> cells, final int[] widths) {
        final var out = new ArrayList<List<Cell>>(widths.length);
        for (int i = 0; i < widths.length; i++) {
            out.add(i < cells.size() ? wrapCell(cells.get(i), widths[i]) : List.of(Cell.EMPTY));
        }
        return out;
    }

    /** Word-wraps one cell to {@code colW} display cells, preserving the span styles; overlong words are hard-split without cutting wide characters. */
    static List<Cell> wrapCell(final Cell cell, final int colW) {
        final var out = new ArrayList<Cell>();
        if (cell == null || isBlank(cell.spans())) {
            out.add(Cell.EMPTY);
            return out;
        }
        final var words = words(cell.spans());
        var line = new ArrayList<Span>();
        var lineWidth = 0; // display cells accumulated on the current line
        for (final var word : words) {
            final var wordWidth = spanWidth(word);
            if (wordWidth > colW) {
                if (!line.isEmpty()) {
                    out.add(new Cell(line));
                    line = new ArrayList<>();
                    lineWidth = 0;
                }
                out.addAll(splitWord(word, colW));
                continue;
            }
            if (!line.isEmpty() && lineWidth + 1 + wordWidth > colW) {
                out.add(new Cell(line));
                line = new ArrayList<>();
                lineWidth = 0;
            }
            if (!line.isEmpty()) {
                line.add(Span.styled(" ", Style.EMPTY));
                lineWidth++;
            }
            line.addAll(word);
            lineWidth += wordWidth;
        }
        if (!line.isEmpty()) {
            out.add(new Cell(line));
        }
        if (out.isEmpty()) {
            out.add(Cell.EMPTY);
        }
        return out;
    }

    /**
     * Splits {@code content} into whitespace-separated word tokens, each a list of
     * styled spans (a word never mixes source spans across a space). Consecutive
     * spaces survive: commonmark keeps them (inside inline formatting and in plain
     * prose alike) and the wrapper joins tokens with a single space, so each extra
     * space of a run becomes its own empty token which the joining space turns back
     * into the original run length. Leading/trailing runs stay dropped - they are
     * invisible and commonmark strips them at line boundaries anyway.
     */
    private static List<List<Span>> words(final List<Span> content) {
        final var tokens = new ArrayList<List<Span>>();
        var current = new ArrayList<Span>();
        for (final var span : content) {
            if (span.content() == null) {
                continue;
            }
            final var parts = span.content().split(" ", -1);
            for (var i = 0; i < parts.length; i++) {
                final var part = parts[i];
                if (!part.isEmpty()) {
                    current.add(Span.styled(part, span.style()));
                } else if (i > 0 && i < parts.length - 1 && !tokens.isEmpty()) {
                    // interior space run: one empty token per extra space, so the
                    // wrappers' single joining space keeps the original run length
                    // (a lone space-only span with no word ever renders nothing)
                    tokens.add(List.of());
                }
                if (i < parts.length - 1 && !current.isEmpty()) {
                    tokens.add(current);
                    current = new ArrayList<>();
                }
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current);
        }
        return tokens;
    }

    /** Hard-splits a word wider than {@code colW} into chunks of at most {@code colW} display cells, keeping each span's style. */
    private static List<Cell> splitWord(final List<Span> word, final int colW) {
        final var chunks = new ArrayList<Cell>();
        var cur = new ArrayList<Span>();
        for (final var span : word) {
            final var text = span.content() == null ? "" : span.content();
            var segment = new StringBuilder();
            var segmentWidth = 0;
            for (int i = 0; i < text.length(); ) {
                final var cp = text.codePointAt(i);
                i += Character.charCount(cp);
                final var cw = charWidth(cp);
                if (segment.length() > 0 && segmentWidth + cw > colW) {
                    cur.add(Span.styled(segment.toString(), span.style()));
                    chunks.add(new Cell(cur));
                    cur = new ArrayList<>();
                    segment = new StringBuilder();
                    segmentWidth = 0;
                }
                segment.appendCodePoint(cp);
                segmentWidth += cw;
            }
            if (segment.length() > 0) {
                cur.add(Span.styled(segment.toString(), span.style()));
            }
        }
        if (!cur.isEmpty()) {
            chunks.add(new Cell(cur));
        }
        return chunks;
    }

    /** Aligns the wrapped cell segments into a rectangular grid of height = tallest cell. */
    private static List<List<Cell>> gridRow(final List<List<Cell>> cells, final List<Integer> widths) {
        final var height = cells.stream().mapToInt(List::size).max().orElse(1);
        final var grid = new ArrayList<List<Cell>>(height);
        for (int h = 0; h < height; h++) {
            final var segments = new ArrayList<Cell>(cells.size());
            for (int c = 0; c < cells.size(); c++) {
                final var cell = cells.get(c);
                segments.add(h < cell.size() ? cell.get(h) : Cell.EMPTY);
            }
            grid.add(segments);
        }
        return grid;
    }

    private static void appendGridLines(
            final List<Line> out,
            final List<List<Cell>> grid,
            final List<Span> prefix,
            final Style style,
            final List<Integer> widths) {
        for (final var segments : grid) {
            final var spans = new ArrayList<Span>(prefix.size() + 1 + segments.size() * 2);
            spans.addAll(prefix);
            spans.add(Span.styled(String.valueOf(VERTICAL), style));
            for (int c = 0; c < segments.size(); c++) {
                final var width = widths.get(Math.min(c, widths.size() - 1));
                final var body = segments.get(c).spans();
                final var pad = Math.max(0, width - spanWidth(body));
                spans.add(Span.styled(" ", style));
                spans.addAll(body);
                spans.add(Span.styled(" ".repeat(pad) + " ", style));
                spans.add(Span.styled(String.valueOf(VERTICAL), style));
            }
            out.add(Line.from(spans));
        }
    }

    private static void appendBorder(
            final List<Line> out,
            final List<Integer> widths,
            final char left,
            final char t,
            final char right,
            final List<Span> prefix,
            final Style style) {
        final var sb = new StringBuilder();
        sb.append(left);
        for (int i = 0; i < widths.size(); i++) {
            sb.append(String.valueOf(HORIZONTAL).repeat(widths.get(i) + CELL_PAD));
            if (i < widths.size() - 1) {
                sb.append(t);
            }
        }
        sb.append(right);
        final var spans = new ArrayList<Span>(prefix.size() + 1);
        spans.addAll(prefix);
        spans.add(Span.styled(sb.toString(), style));
        out.add(Line.from(spans));
    }

    private static int widthOf(final List<Span> spans) {
        var w = 0;
        for (final var s : spans) {
            w += s.width();
        }
        return w;
    }
}
