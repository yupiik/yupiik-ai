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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Pure, bounded text diff layout; never reads files or guesses unavailable content. */
public final class DiffLayout {
    public static final int MAX_CHARS = 131_072;
    public static final int MAX_LINES = 2_000;
    public static final int MAX_OUTPUT_LINES = 240;
    private static final Pattern HUNK =
            Pattern.compile("@@ -[0-9]+(?:,([0-9]{1,9}))? \\+[0-9]+(?:,([0-9]{1,9}))? @@.*");

    private DiffLayout() {}

    /**
     * Linear prefix/suffix diff, with three context lines. The changed middle is a replacement
     * (not a minimal edit script), avoiding quadratic work on unrelated or repetitive files.
     * A null old text is only appropriate for a confirmed creation.
     */
    public static List<String> between(final String oldText, final String newText) {
        final var before = oldText == null ? "" : oldText;
        if (newText == null) {
            return List.of("(content unavailable)");
        }
        if (before.length() > MAX_CHARS || newText.length() > MAX_CHARS) {
            return List.of("(diff omitted: content limit)");
        }
        final var oldLines = lines(before);
        final var newLines = lines(newText);
        if (oldLines.size() > MAX_LINES || newLines.size() > MAX_LINES) {
            return List.of("(diff omitted: line limit)");
        }
        var prefix = 0;
        while (prefix < oldLines.size()
                && prefix < newLines.size()
                && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }
        if (prefix == oldLines.size() && prefix == newLines.size()) {
            return List.of("(no text changes)");
        }
        var oldEnd = oldLines.size();
        var newEnd = newLines.size();
        while (oldEnd > prefix && newEnd > prefix && oldLines.get(oldEnd - 1).equals(newLines.get(newEnd - 1))) {
            oldEnd--;
            newEnd--;
        }
        final var out = new ArrayList<String>();
        final var start = Math.max(0, prefix - 3);
        out.add("@@ -" + (oldLines.isEmpty() ? 0 : start + 1) + ","
                + (Math.min(oldLines.size(), oldEnd + 3) - start)
                + " +" + (newLines.isEmpty() ? 0 : start + 1) + ","
                + (Math.min(newLines.size(), newEnd + 3) - start) + " @@");
        for (var i = start; i < prefix; i++) {
            append(out, " ", oldLines.get(i));
        }
        for (var i = prefix; i < oldEnd && i - prefix < 110; i++) {
            append(out, "-", oldLines.get(i));
        }
        if (oldEnd - prefix > 110) {
            out.add("... deletions truncated");
        }
        for (var i = prefix; i < newEnd && i - prefix < 110; i++) {
            append(out, "+", newLines.get(i));
        }
        if (newEnd - prefix > 110) {
            out.add("... additions truncated");
        }
        for (var i = newEnd; i < Math.min(newLines.size(), newEnd + 3) && out.size() < MAX_OUTPUT_LINES; i++) {
            append(out, " ", newLines.get(i));
        }
        if (out.size() >= MAX_OUTPUT_LINES) {
            out.add("... diff truncated");
        }
        return List.copyOf(out);
    }

    private static void append(final List<String> out, final String prefix, final Line line) {
        out.add(prefix + line.text());
        if (!line.terminated()) {
            out.add("\\ No newline at end of file");
        }
    }

    private record Line(String text, boolean terminated) {}

    private static List<Line> lines(final String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        // Only the terminal split sentinel is synthetic. CR and real empty lines are content.
        final var parts = text.split("\n", -1);
        final var terminated = text.endsWith("\n");
        final var size = parts.length - (terminated ? 1 : 0);
        final var result = new ArrayList<Line>(size);
        for (var i = 0; i < size; i++) {
            result.add(new Line(parts[i], i < size - 1 || terminated));
        }
        return result;
    }

    /** A split row: empty cells are gaps; metadata is kept outside the two code columns. */
    public record Row(String left, String right, String metadata) {}

    /** Pairs deletion/addition runs; pure additions and deletions retain an empty opposite cell. */
    public static List<Row> split(final List<String> lines) {
        final var rows = new ArrayList<Row>();
        var oldRemaining = 0;
        var newRemaining = 0;
        for (var i = 0; i < Math.min(lines.size(), MAX_OUTPUT_LINES); ) {
            final var line = lines.get(i);
            if (line.startsWith("@@ ")) {
                final var hunk = HUNK.matcher(line);
                oldRemaining = 0;
                newRemaining = 0;
                if (hunk.matches()) {
                    oldRemaining = hunk.group(1) == null ? 1 : Integer.parseInt(hunk.group(1));
                    newRemaining = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
                }
            }
            if (line.startsWith("-") && (oldRemaining > 0 || !line.startsWith("--- "))) {
                final var deleted = new ArrayList<String>();
                final var added = new ArrayList<String>();
                while (i < Math.min(lines.size(), MAX_OUTPUT_LINES)
                        && lines.get(i).startsWith("-")
                        && (oldRemaining > 0 || !lines.get(i).startsWith("--- "))) {
                    deleted.add(lines.get(i++));
                    oldRemaining = Math.max(0, oldRemaining - 1);
                }
                while (i < Math.min(lines.size(), MAX_OUTPUT_LINES)
                        && lines.get(i).startsWith("+")
                        && (newRemaining > 0 || !lines.get(i).startsWith("+++"))) {
                    added.add(lines.get(i++));
                    newRemaining = Math.max(0, newRemaining - 1);
                }
                for (var j = 0; j < Math.max(deleted.size(), added.size()); j++) {
                    rows.add(new Row(
                            j < deleted.size() ? deleted.get(j) : "", j < added.size() ? added.get(j) : "", ""));
                }
            } else if (line.startsWith("+") && (newRemaining > 0 || !line.startsWith("+++"))) {
                rows.add(new Row("", line, ""));
                newRemaining = Math.max(0, newRemaining - 1);
                i++;
            } else if (line.startsWith(" ")) {
                rows.add(new Row(line, line, ""));
                oldRemaining = Math.max(0, oldRemaining - 1);
                newRemaining = Math.max(0, newRemaining - 1);
                i++;
            } else {
                rows.add(new Row("", "", line));
                i++;
            }
        }
        return List.copyOf(rows);
    }
}
