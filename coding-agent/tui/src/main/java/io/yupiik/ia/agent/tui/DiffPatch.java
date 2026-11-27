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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded, sparse patch model: storage depends on supplied lines, never hunk coordinates. */
public final class DiffPatch {
    private static final int MAX_CHARACTERS = 1_000_000;
    private static final int MAX_LINES = 20_000;
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?:.*)$");
    private static final Pattern COUNTS = Pattern.compile("\\s+\\(\\+\\d+ -\\d+\\)$");

    private DiffPatch() {}

    public enum Kind {
        HEADER,
        HUNK,
        CONTEXT,
        ADD,
        REMOVE,
        NOTE
    }

    public record Row(Kind kind, String text, String oldNumber, String newNumber) {}

    public record Document(List<Row> rows, boolean truncated) {}

    public static Document parse(final String diff) {
        if (diff == null || diff.isBlank()) {
            return new Document(List.of(), false);
        }
        var end = Math.min(diff.length(), MAX_CHARACTERS);
        if (end < diff.length() && Character.isHighSurrogate(diff.charAt(end - 1))) {
            end--;
        }
        final var lines = diff.substring(0, end).lines().limit(MAX_LINES + 1L).toList();
        final var count = Math.min(lines.size(), MAX_LINES);
        final var rows = new ArrayList<Row>(count);
        long old = 0;
        long next = 0;
        long oldLeft = 0;
        long newLeft = 0;
        for (var i = 0; i < count; i++) {
            final var raw = lines.get(i);
            final Kind kind;
            final String text;
            String oldNumber = null;
            String newNumber = null;
            // Inside a numbered hunk, "--- text" and "+++ text" are source, not file headers.
            final var inHunk = oldLeft > 0 || newLeft > 0;
            if (raw.startsWith("@@")) {
                kind = Kind.HUNK;
                text = raw;
                oldLeft = 0;
                newLeft = 0;
                final var match = HUNK.matcher(raw);
                if (match.matches()) {
                    try {
                        old = Long.parseLong(match.group(1));
                        next = Long.parseLong(match.group(3));
                        oldLeft = match.group(2) == null ? 1 : Long.parseLong(match.group(2));
                        newLeft = match.group(4) == null ? 1 : Long.parseLong(match.group(4));
                        // Zero is valid only for an empty range; reject overflowing coordinates too.
                        if ((old == 0 && oldLeft != 0)
                                || (next == 0 && newLeft != 0)
                                || old > Long.MAX_VALUE - oldLeft
                                || next > Long.MAX_VALUE - newLeft) {
                            oldLeft = 0;
                            newLeft = 0;
                        }
                    } catch (final NumberFormatException invalid) {
                        oldLeft = 0;
                        newLeft = 0;
                    }
                }
            } else if (raw.startsWith("diff --git")
                    || raw.startsWith("index ")
                    || raw.startsWith("*** ")
                    || (!inHunk && (raw.startsWith("--- ") || raw.startsWith("+++ ")))) {
                kind = Kind.HEADER;
                text = raw;
                oldLeft = 0;
                newLeft = 0;
            } else if (raw.startsWith("+")) {
                kind = Kind.ADD;
                text = raw.substring(1);
                if (newLeft > 0) {
                    newNumber = Long.toString(next++);
                    newLeft--;
                }
            } else if (raw.startsWith("-")) {
                kind = Kind.REMOVE;
                text = raw.substring(1);
                if (oldLeft > 0) {
                    oldNumber = Long.toString(old++);
                    oldLeft--;
                }
            } else if (raw.startsWith(" ")) {
                kind = Kind.CONTEXT;
                text = raw.substring(1);
                if (oldLeft > 0 && newLeft > 0) {
                    oldNumber = Long.toString(old++);
                    newNumber = Long.toString(next++);
                    oldLeft--;
                    newLeft--;
                } else {
                    oldLeft = 0;
                    newLeft = 0;
                }
            } else {
                kind = Kind.NOTE;
                text = raw;
                if (!raw.startsWith("\\ No newline")) {
                    oldLeft = 0;
                    newLeft = 0;
                }
            }
            rows.add(new Row(kind, text, oldNumber, newNumber));
        }
        for (var i = 0; i < rows.size(); i++) {
            final var row = rows.get(i);
            if (row.kind() == Kind.HUNK || (row.kind() == Kind.HEADER && operation(row.text()))) {
                rows.set(i, new Row(row.kind(), label(rows, i, row.kind() == Kind.HEADER), null, null));
            }
        }
        return new Document(List.copyOf(rows), end < diff.length() || lines.size() > MAX_LINES);
    }

    private static boolean operation(final String line) {
        return line.startsWith("*** Add File:")
                || line.startsWith("*** Update File:")
                || line.startsWith("*** Delete File:");
    }

    private static String label(final List<Row> rows, final int index, final boolean operation) {
        var add = 0;
        var remove = 0;
        for (var i = index + 1; i < rows.size(); i++) {
            final var row = rows.get(i);
            if (operation
                    ? row.kind() == Kind.HEADER
                            && (operation(row.text()) || row.text().startsWith("*** End Patch"))
                    : row.kind() == Kind.HUNK || row.kind() == Kind.HEADER) {
                break;
            }
            if (row.kind() == Kind.ADD) {
                add++;
            } else if (row.kind() == Kind.REMOVE) {
                remove++;
            }
        }
        return COUNTS.matcher(rows.get(index).text()).replaceFirst("") + " (+" + add + " -" + remove + ")";
    }
}
