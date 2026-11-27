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

import io.yupiik.ia.agent.core.shell.DiffLayout;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Terminal diff rendering over bounded, in-memory previews or captures. */
public final class CliDiffRenderer {
    private static final int MAX_LINE_CHARS = 4096;
    private static final Pattern GRAPHEMES = Pattern.compile("\\X");

    private CliDiffRenderer() {}

    /** Width excludes the caller's indentation and actor prefix. Non-TTY output stays linear. */
    public static List<String> render(
            final List<String> lines,
            final String language,
            final DiffViewMode mode,
            final int columns,
            final boolean tty,
            final MarkdownColorizer colorizer) {
        final var out = new ArrayList<String>();
        final var syntax = language == null ? "" : language.substring(language.lastIndexOf('.') + 1);
        final var colors =
                tty && !System.getenv().containsKey("NO_COLOR") && !"dumb".equalsIgnoreCase(System.getenv("TERM"))
                        ? colorizer
                        : new MarkdownColorizer(false);
        final var width = Math.clamp(columns, 1, 500);
        if (tty && width < 3) {
            return List.of("(diff omitted: terminal width below 3 cells)");
        }
        final var split = tty && mode != DiffViewMode.SIMPLE && width >= 110;
        var truncated = lines.size() > DiffLayout.MAX_OUTPUT_LINES;
        var lineTruncated = false;
        if (split) {
            final var cellWidth = (width - 3) / 2;
            for (final var row : DiffLayout.split(lines)) {
                lineTruncated |= row.left().length() > MAX_LINE_CHARS
                        || row.right().length() > MAX_LINE_CHARS
                        || row.metadata().length() > MAX_LINE_CHARS;
                if (!row.metadata().isEmpty()) {
                    for (final var part : wrap(row.metadata(), width)) {
                        if (out.size() >= DiffLayout.MAX_OUTPUT_LINES) {
                            truncated = true;
                            break;
                        }
                        out.add(part);
                    }
                } else {
                    final var left = wrap(row.left(), cellWidth);
                    final var right = wrap(row.right(), cellWidth);
                    for (var i = 0; i < Math.max(left.size(), right.size()); i++) {
                        if (out.size() >= DiffLayout.MAX_OUTPUT_LINES) {
                            truncated = true;
                            break;
                        }
                        out.add(cell(
                                        i < left.size() ? left.get(i) : "",
                                        cellWidth,
                                        syntax,
                                        colors,
                                        i < left.size() ? change(row.left()) : ' ')
                                + " | "
                                + cell(
                                        i < right.size() ? right.get(i) : "",
                                        cellWidth,
                                        syntax,
                                        colors,
                                        i < right.size() ? change(row.right()) : ' '));
                    }
                }
                if (truncated && out.size() >= DiffLayout.MAX_OUTPUT_LINES) {
                    break;
                }
            }
        } else {
            for (var i = 0; i < Math.min(lines.size(), DiffLayout.MAX_OUTPUT_LINES); i++) {
                final var text = lines.get(i);
                lineTruncated |= text.length() > MAX_LINE_CHARS;
                // Redirected output remains linear, with no terminal-width wrapping or ANSI.
                for (final var part : wrap(text, tty ? width : Integer.MAX_VALUE)) {
                    if (out.size() >= DiffLayout.MAX_OUTPUT_LINES) {
                        truncated = true;
                        break;
                    }
                    out.add(cell(part, 0, syntax, colors, change(text)));
                }
                if (truncated && out.size() >= DiffLayout.MAX_OUTPUT_LINES) {
                    break;
                }
            }
        }
        if (lineTruncated) {
            out.add("... diff truncated: source line exceeds " + MAX_LINE_CHARS + " characters");
        }
        if (truncated) {
            out.add("... diff truncated: rendered row limit (" + DiffLayout.MAX_OUTPUT_LINES + ")");
        }
        return List.copyOf(out);
    }

    private static String cell(
            final String text,
            final int width,
            final String language,
            final MarkdownColorizer colorizer,
            final char change) {
        final var padded = text + " ".repeat(Math.max(0, width - displayWidth(text)));
        return colorizer.diffCell(language, padded, change);
    }

    private static char change(final String text) {
        return text.startsWith("+") && !text.startsWith("+++")
                ? '+'
                : text.startsWith("-") && !text.startsWith("--- ") ? '-' : ' ';
    }

    /** Bounded sanitization before tokenization; wrapping never replaces source with ellipses. */
    private static List<String> wrap(final String text, final int width) {
        final var parts = new ArrayList<String>();
        final var prefix = change(text) != ' ' || text.startsWith(" ") ? text.substring(0, 1) : "";
        final var safe = new StringBuilder();
        var offset = 0;
        while (offset < Math.min(text.length(), MAX_LINE_CHARS)) {
            final var cp = text.codePointAt(offset);
            if (offset + Character.charCount(cp) > MAX_LINE_CHARS) {
                break;
            }
            offset += Character.charCount(cp);
            final var value = cp == '\t'
                    ? "    "
                    : cp == '\r' ? "\\r" : Character.isISOControl(cp) ? "?" : new String(Character.toChars(cp));
            safe.append(value);
        }
        final var out = new StringBuilder();
        final var graphemes = GRAPHEMES.matcher(safe);
        var used = 0;
        while (graphemes.find()) {
            final var value = graphemes.group();
            final var count = clusterWidth(value);
            if (used + count > width) {
                parts.add(out.toString());
                if (parts.size() >= DiffLayout.MAX_OUTPUT_LINES + 1) {
                    return parts;
                }
                out.setLength(0);
                out.append(prefix);
                used = prefix.length();
            }
            out.append(value);
            used += count;
        }
        parts.add(out.toString());
        return parts;
    }

    private static int displayWidth(final String text) {
        final var graphemes = GRAPHEMES.matcher(text);
        var width = 0;
        while (graphemes.find()) {
            width += clusterWidth(graphemes.group());
        }
        return width;
    }

    private static int clusterWidth(final String text) {
        var width = 0;
        var emoji = false;
        var joined = false;
        for (var i = 0; i < text.length(); ) {
            final var cp = text.codePointAt(i);
            i += Character.charCount(cp);
            final var type = Character.getType(cp);
            emoji |= cp == 0xfe0f || cp == 0x20e3 || cp >= 0x1f3fb && cp <= 0x1f3ff;
            joined |= cp == 0x200d;
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.FORMAT
                    || cp >= 0x1f3fb && cp <= 0x1f3ff) {
                continue;
            }
            final var wide = cp >= 0x1100
                    && (cp <= 0x115f
                            || cp == 0x2329
                            || cp == 0x232a
                            || cp >= 0x2e80 && cp <= 0xa4cf
                            || cp >= 0xac00 && cp <= 0xd7a3
                            || cp >= 0xf900 && cp <= 0xfaff
                            || cp >= 0xfe10 && cp <= 0xfe6f
                            || cp >= 0xff01 && cp <= 0xff60
                            || cp >= 0xffe0 && cp <= 0xffe6
                            || cp >= 0x1f300 && cp <= 0x1faff
                            || cp >= 0x20000 && cp <= 0x3fffd);
            width += wide ? 2 : 1;
        }
        return emoji || joined ? Math.min(2, Math.max(emoji ? 2 : 0, width)) : width;
    }
}
