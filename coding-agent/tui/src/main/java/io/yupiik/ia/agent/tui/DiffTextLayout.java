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
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.GraphemeClusters;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import java.util.ArrayList;
import java.util.List;

/** Cell-aware wrapping shared by the diff's full-width and half-width rows. */
public final class DiffTextLayout {
    private DiffTextLayout() {}

    /** Expands source-relative four-column tabs and makes terminal control characters safe. */
    public static String displayText(final String text, final int width) {
        if (printableAscii(text)) {
            // Most source lines need no normalization. In particular, do not allocate one
            // grapheme string and code-point stream per character of a huge ASCII line.
            return text;
        }
        final var out = new StringBuilder(text.length());
        var column = 0;
        for (var start = 0; start < text.length(); ) {
            final var end = GraphemeClusters.clusterEnd(text, start);
            final var cluster = text.substring(start, end);
            if (cluster.equals("\t")) {
                final var spaces = 4 - column % 4;
                out.append(" ".repeat(spaces));
                column += spaces;
            } else if (cluster.codePoints().anyMatch(Character::isISOControl) || CharWidth.of(cluster) > width) {
                // A two-cell grapheme cannot fit in a one-cell viewport. Use lossless ASCII escapes.
                for (final var point : cluster.codePoints().toArray()) {
                    final var escaped = "\\u{" + Integer.toHexString(point) + "}";
                    out.append(escaped);
                    column += escaped.length();
                }
            } else {
                out.append(cluster);
                column += CharWidth.of(cluster);
            }
            start = end;
        }
        return out.toString();
    }

    /**
     * Wraps at grapheme boundaries while keeping token styles. At most {@code limit} rows are
     * materialized; callers request one extra row to detect truncation without allocating the tail.
     */
    public static List<Line> wrap(final String text, final List<Span> tokens, final int width, final int limit) {
        final var ascii = printableAscii(text);
        final var rows = new ArrayList<Line>();
        var spans = new ArrayList<Span>();
        var used = 0;
        var token = 0;
        var tokenEnd = tokens.isEmpty() ? 0 : tokens.getFirst().content().length();
        var run = new StringBuilder();
        var runStyle = Style.EMPTY;
        for (var start = 0; start < text.length(); ) {
            final var end = ascii ? start + 1 : GraphemeClusters.clusterEnd(text, start);
            final var cells = ascii ? 1 : CharWidth.of(text.substring(start, end));
            if (used + cells > width && used > 0) {
                flush(spans, run, runStyle);
                rows.add(Line.from(spans));
                if (rows.size() >= limit) {
                    return rows;
                }
                spans = new ArrayList<>();
                used = 0;
            }
            while (token + 1 < tokens.size() && start >= tokenEnd) {
                token++;
                tokenEnd += tokens.get(token).content().length();
            }
            final var style = tokens.isEmpty() ? Style.EMPTY : tokens.get(token).style();
            if (!style.equals(runStyle)) {
                flush(spans, run, runStyle);
                runStyle = style;
            }
            run.append(text, start, end);
            used += cells;
            start = end;
        }
        flush(spans, run, runStyle);
        rows.add(Line.from(spans));
        return rows;
    }

    private static boolean printableAscii(final String text) {
        for (var i = 0; i < text.length(); i++) {
            final var character = text.charAt(i);
            if (character < ' ' || character > '~') {
                return false;
            }
        }
        return true;
    }

    private static void flush(final List<Span> spans, final StringBuilder run, final Style style) {
        if (!run.isEmpty()) {
            spans.add(Span.styled(run.toString(), style));
            run.setLength(0);
        }
    }
}
