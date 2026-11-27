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

import dev.tamboui.text.CharWidth;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure input helpers of the TUI: slash command registry, word motions and
 * first-line extraction. Extracted from the TUI command so they can be unit
 * tested without a terminal.
 */
public final class TuiInputSupport {
    public static final List<String> SLASH_COMMANDS = List.of(
            "/help",
            "/shortcuts",
            "/new",
            "/resume",
            "/compact",
            "/model",
            "/variant",
            "/provider",
            "/mode",
            "/memory",
            "/context",
            "/permissions",
            "/thinking",
            "/tool-output",
            "/structural-code-graph",
            "/maven-repository-knowledge",
            "/mcp",
            "/tools",
            "/agent",
            "/clear",
            "/quit",
            "/exit",
            "/session-id",
            "/session-flush",
            "/session-housekeeping");

    private TuiInputSupport() {}

    public static int nextWord(final String s, final int col) {
        final var len = s.length();
        var i = col;
        while (i < len && s.charAt(i) == ' ') {
            i++;
        }
        while (i < len && s.charAt(i) != ' ') {
            i++;
        }
        return Math.min(i, len);
    }

    public static int previousWord(final String s, final int col) {
        if (s.isEmpty() || col <= 0) {
            return 0;
        }
        var i = Math.min(col, s.length()) - 1;
        while (i >= 0 && i < s.length() && s.charAt(i) == ' ') {
            i--;
        }
        while (i >= 0 && i < s.length() && s.charAt(i) != ' ') {
            i--;
        }
        return i + 1;
    }

    /**
     * Character index (relative to the fragment) at the given display column: the
     * boundary of the char whose start cell is nearest to (but not past) {@code x}.
     * A click on the second cell of a wide (2-cell) char lands on that char's
     * start; a column past the fragment end maps to the fragment length.
     */
    public static int columnAtDisplayWidth(final String fragment, final int x) {
        if (fragment == null || fragment.isEmpty() || x <= 0) {
            return 0;
        }
        var width = 0;
        var index = 0;
        while (index < fragment.length()) {
            final var codePoint = fragment.codePointAt(index);
            final var charWidth = CharWidth.of(codePoint);
            if (width + charWidth > x) {
                return index;
            }
            width += charWidth;
            index += Character.charCount(codePoint);
        }
        return fragment.length();
    }

    public static String firstLine(final String text) {
        if (text == null) {
            return "";
        }
        final var idx = text.indexOf('\n');
        final var line = idx < 0 ? text : text.substring(0, idx);
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }

    /**
     * Word-wraps prose to {@code width} columns, returning one fragment per row.
     * Existing newlines are hard breaks; a single token wider than {@code width}
     * is hard-split so no fragment ever exceeds the width.
     */
    public static List<String> wrapProse(final String text, final int width) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        final var w = Math.max(1, width);
        final var out = new ArrayList<String>();
        for (final var raw : text.split("\n", -1)) {
            if (raw.isBlank()) {
                out.add("");
                continue;
            }
            final var words = raw.split(" ");
            var line = new StringBuilder();
            for (final var word : words) {
                if (word.length() > w) {
                    if (line.length() > 0) {
                        out.add(line.toString());
                        line = new StringBuilder();
                    }
                    for (int from = 0; from < word.length(); from += w) {
                        out.add(word.substring(from, Math.min(word.length(), from + w)));
                    }
                    continue;
                }
                if (line.length() > 0 && line.length() + 1 + word.length() > w) {
                    out.add(line.toString());
                    line = new StringBuilder();
                }
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
            if (line.length() > 0) {
                out.add(line.toString());
            }
        }
        return out;
    }

    /**
     * Display-width aware variant of {@link #wrapProse} used to size the ask_user
     * feedback modal. It measures each word (and each wrapped row) by the number of
     * terminal columns each code point occupies via {@link CharWidth}, so wide glyphs
     * (CJK, emoji, combining sequences) are counted at their real 2-cell width instead
     * of 1 column per UTF-16 char. {@code wrapProse} is length-based and therefore
     * underestimates the row count for such text, which made the modal under-grow and
     * clip its (column-wrapped) question. The produced rows are only used for sizing,
     * the actual rendering re-wraps to the panel width.
     */
    public static List<String> wrapProseWidth(final String text, final int width) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        final var w = Math.max(1, width);
        final var out = new ArrayList<String>();
        for (final var raw : text.split("\n", -1)) {
            if (raw.isBlank()) {
                out.add("");
                continue;
            }
            final var words = raw.split(" ");
            final var line = new StringBuilder();
            var lineWidth = 0;
            for (final var word : words) {
                final var wordWidth = CharWidth.of(word);
                if (wordWidth > w) {
                    // a single token (eg. a CJK sentence without spaces) is hard-split
                    // at a column boundary so no row ever visually exceeds the width
                    if (line.length() > 0) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    var rem = word;
                    while (!rem.isEmpty() && CharWidth.of(rem) > w) {
                        final var piece = CharWidth.substringByWidth(rem, w);
                        if (piece.isEmpty()) {
                            break;
                        }
                        out.add(piece);
                        rem = rem.substring(piece.length());
                    }
                    line.append(rem);
                    lineWidth = CharWidth.of(rem);
                    continue;
                }
                if (line.length() > 0 && lineWidth + 1 + wordWidth > w) {
                    out.add(line.toString());
                    line.setLength(0);
                    lineWidth = 0;
                }
                if (line.length() > 0) {
                    line.append(' ');
                    lineWidth += 1;
                }
                line.append(word);
                lineWidth += wordWidth;
            }
            if (line.length() > 0) {
                out.add(line.toString());
            }
        }
        return out;
    }
}
