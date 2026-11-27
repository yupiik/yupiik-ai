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
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Terminal-free, theme-aware diff layout. AUTO splits at 110 available cells. Explicit split
 * falls back to unified when there is no room for two usable source columns. Neutral rows
 * never paint a background; changed rows use a 15% palette tint, preserving syntax foregrounds.
 *
 * <p>Rendering is bounded to 20,000 output rows, 1,000,000 input characters, 20,000 input
 * lines and 4,096 columns. Omitted output is explicitly announced, never silently clipped.
 * Instances retain neither source nor theme state and can be shared across theme changes.
 */
public class DiffRenderer {
    private static final int AUTO_SPLIT_WIDTH = 110;
    private static final int MAX_ROWS = 20_000;
    private static final int MAX_WIDTH = 4_096;

    /** True when the text plausibly is a unified or OpenCode diff (not a markdown list). */
    public boolean looksLikeDiff(final String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        var hunk = false;
        var fileHeaders = false;
        var changed = 0;
        for (final var line : text.lines().limit(20_000).toList()) {
            final var s = line.stripLeading();
            if (s.startsWith("*** Add File:") || s.startsWith("*** Update File:") || s.startsWith("*** Delete File:")) {
                return true;
            } else if (s.startsWith("@@")) {
                hunk = true;
            } else if (s.startsWith("--- ") || s.startsWith("+++ ")) {
                fileHeaders = true;
            } else if (s.startsWith("+") || s.startsWith("-")) {
                changed++;
            }
        }
        return (hunk && changed >= 1) || (fileHeaders && changed >= 2);
    }

    /** Compatibility entry point; no highlighter is available on this path. */
    public List<Line> render(final String diff, final int width, final Palette palette) {
        return render(diff, width, palette, DiffViewMode.AUTO, null, null);
    }

    /**
     * Renders pre-wrapped rows. The supplied highlighter carries the active token theme; null
     * uses palette text only. A mono palette suppresses all colors, including token colors.
     * Line numbers are shown only for validated numbered hunk ranges, on first visual rows.
     * OpenCode unnumbered sections can split but never receive invented coordinates.
     */
    public List<Line> render(
            final String diff,
            final int width,
            final Palette palette,
            final DiffViewMode mode,
            final SyntaxHighlighter highlighter,
            final String language) {
        if (width <= 0) {
            return List.of();
        }
        final var available = Math.min(width, MAX_WIDTH);
        final var document = DiffPatch.parse(diff);
        final var rows = document.rows();
        final var digits = rows.stream()
                .mapToInt(row -> Math.max(
                        row.oldNumber() == null ? 0 : row.oldNumber().length(),
                        row.newNumber() == null ? 0 : row.newNumber().length()))
                .max()
                .orElse(0);
        final var leftWidth = (available - 3) / 2;
        final var rightWidth = available - 3 - leftWidth;
        final var gutter = digits == 0 ? 3 : digits + 4;
        final var split = mode != DiffViewMode.SIMPLE && available >= AUTO_SPLIT_WIDTH && leftWidth >= gutter + 2;
        final var out = new ArrayList<Line>();
        for (var i = 0; i < rows.size() && out.size() <= MAX_ROWS; ) {
            final var row = rows.get(i);
            final var limit = MAX_ROWS - out.size() + 1;
            if (split && changed(row)) {
                final var removed = new ArrayList<DiffPatch.Row>();
                final var added = new ArrayList<DiffPatch.Row>();
                do {
                    final var change = rows.get(i++);
                    (change.kind() == DiffPatch.Kind.REMOVE ? removed : added).add(change);
                } while (i < rows.size() && changed(rows.get(i)));
                for (var pair = 0; pair < Math.max(removed.size(), added.size()) && out.size() <= MAX_ROWS; pair++) {
                    splitRow(
                            out,
                            pair < removed.size() ? removed.get(pair) : null,
                            pair < added.size() ? added.get(pair) : null,
                            leftWidth,
                            rightWidth,
                            digits,
                            palette,
                            highlighter,
                            language);
                }
            } else if (split && row.kind() == DiffPatch.Kind.CONTEXT) {
                splitRow(out, row, row, leftWidth, rightWidth, digits, palette, highlighter, language);
                i++;
            } else if (source(row)) {
                final var prefix = available >= 5
                        ? switch (row.kind()) {
                            case ADD -> " + ";
                            case REMOVE -> " - ";
                            default -> " ";
                        }
                        : "";
                final var wrapped =
                        sourceLines(row, available - prefix.length(), limit, palette, highlighter, language);
                for (var line = 0; line < wrapped.size(); line++) {
                    final var spans = new ArrayList<Span>();
                    spans.add(Span.styled(line == 0 ? prefix : " ".repeat(prefix.length()), markerStyle(row, palette)));
                    spans.addAll(wrapped.get(line).spans());
                    final var used = spans.stream().mapToInt(Span::width).sum();
                    if (!palette.mono() && changed(row) && available > used) {
                        spans.add(Span.styled(
                                " ".repeat(available - used),
                                palette.style(palette.text()).patch(background(row, palette))));
                    }
                    out.add(Line.from(spans));
                }
                i++;
            } else {
                final var text = DiffTextLayout.displayText(row.text(), available);
                final var style =
                        switch (row.kind()) {
                            case HEADER -> palette.bold(palette.primary());
                            case HUNK -> palette.bold(palette.info());
                            default -> palette.muted();
                        };
                out.addAll(DiffTextLayout.wrap(text, List.of(Span.styled(text, style)), available, limit));
                i++;
            }
        }
        if (out.size() > MAX_ROWS || document.truncated()) {
            if (out.size() >= MAX_ROWS) {
                out.subList(MAX_ROWS - 1, out.size()).clear();
            }
            // The bounded notice itself must still respect very narrow viewports.
            final var notice = DiffTextLayout.wrap(
                            "[diff truncated]", List.of(Span.styled("[diff truncated]", palette.muted())), available, 1)
                    .getFirst();
            out.add(notice);
        }
        return List.copyOf(out);
    }

    private void splitRow(
            final List<Line> out,
            final DiffPatch.Row left,
            final DiffPatch.Row right,
            final int leftWidth,
            final int rightWidth,
            final int digits,
            final Palette palette,
            final SyntaxHighlighter highlighter,
            final String language) {
        final var limit = MAX_ROWS - out.size() + 1;
        final var gutter = digits == 0 ? 3 : digits + 4;
        final var leftLines = left == null
                ? List.<Line>of()
                : sourceLines(left, leftWidth - gutter, limit, palette, highlighter, language);
        final var rightLines = right == null
                ? List.<Line>of()
                : sourceLines(right, rightWidth - gutter, limit, palette, highlighter, language);
        for (var line = 0; line < Math.max(leftLines.size(), rightLines.size()); line++) {
            final var spans = new ArrayList<Span>();
            half(spans, left, leftLines, line, leftWidth, digits, true, palette);
            spans.add(Span.styled(" | ", palette.style(palette.border())));
            half(spans, right, rightLines, line, rightWidth, digits, false, palette);
            out.add(Line.from(spans));
        }
    }

    private void half(
            final List<Span> spans,
            final DiffPatch.Row row,
            final List<Line> lines,
            final int index,
            final int width,
            final int digits,
            final boolean old,
            final Palette palette) {
        if (row == null || index >= lines.size()) {
            spans.add(Span.raw(" ".repeat(width)));
            return;
        }
        final var number = old ? row.oldNumber() : row.newNumber();
        final var coordinate = digits == 0
                ? ""
                : (index == 0 && number != null ? " ".repeat(digits - number.length()) + number : " ".repeat(digits))
                        + " ";
        final var marker = index == 0
                ? switch (row.kind()) {
                    case ADD -> " + ";
                    case REMOVE -> " - ";
                    default -> "   ";
                }
                : "   ";
        final var prefix = coordinate + marker;
        spans.add(Span.styled(prefix, markerStyle(row, palette)));
        spans.addAll(lines.get(index).spans());
        final var used = prefix.length()
                + lines.get(index).spans().stream().mapToInt(Span::width).sum();
        if (width > used) {
            spans.add(Span.styled(" ".repeat(width - used), background(row, palette)));
        }
    }

    private List<Line> sourceLines(
            final DiffPatch.Row row,
            final int width,
            final int limit,
            final Palette palette,
            final SyntaxHighlighter highlighter,
            final String language) {
        final var text = DiffTextLayout.displayText(row.text(), width);
        final var base = palette.style(palette.text()).patch(background(row, palette));
        final var tokens = highlighter == null || palette.mono()
                ? List.of(Span.styled(text, base))
                : highlighter.highlightLines(language, text).getFirst().spans().stream()
                        .map(span -> Span.styled(span.content(), base.patch(span.style())))
                        .toList();
        return DiffTextLayout.wrap(text, tokens, width, limit);
    }

    private Style markerStyle(final DiffPatch.Row row, final Palette palette) {
        return palette.style(
                        switch (row.kind()) {
                            case ADD -> palette.success();
                            case REMOVE -> palette.error();
                            default -> palette.textMuted();
                        })
                .patch(background(row, palette));
    }

    private Style background(final DiffPatch.Row row, final Palette palette) {
        if (palette.mono() || !changed(row)) {
            return Style.EMPTY;
        }
        // TuiColors' argument is transparency, hence 85% panel + 15% semantic color.
        return Style.EMPTY.bg(TuiColors.blend(
                row.kind() == DiffPatch.Kind.ADD ? palette.success() : palette.error(),
                palette.backgroundPanel(),
                0.85));
    }

    private boolean changed(final DiffPatch.Row row) {
        return row.kind() == DiffPatch.Kind.ADD || row.kind() == DiffPatch.Kind.REMOVE;
    }

    private boolean source(final DiffPatch.Row row) {
        return changed(row) || row.kind() == DiffPatch.Kind.CONTEXT;
    }
}
