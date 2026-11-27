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
package io.yupiik.ia.agent.core.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Turn-local coverage of complete file lines/directory entries actually delivered to the model. */
public final class ReadCoverage {
    private static final String PREFIX = "read-range:";
    private static final Pattern NUMBERED_LINE = Pattern.compile(" *([1-9][0-9]*)\\t.*");
    private static final Pattern DIRECTORY = Pattern.compile("Read directory .+, entries ([1-9][0-9]*)-([0-9]+)");

    // File lines and directory entries are separate coordinate spaces even for the same path.
    private final Map<String, TreeMap<Long, Long>> intervals = new HashMap<>();

    /** Creates a marker from the same resolved path used by tool execution; no requested range is encoded. */
    public static String key(final Path path) {
        return PREFIX + path.toAbsolutePath().normalize();
    }

    public boolean supports(final String key) {
        return key != null && key.startsWith(PREFIX) && key.length() > PREFIX.length();
    }

    /**
     * Classifies and records a successful read using its post-ingestion-cap output, not its raw result.
     * Returns true for any newly delivered complete line/entry, or when coverage cannot be established.
     * The caller owns serialization, calls this only for successful non-pending reads, and handles other
     * coverage keys separately. Empty/unknown results never establish coverage or prove redundancy.
     */
    public boolean record(final String key, final String output) {
        if (!supports(key) || output == null || output.isEmpty()) {
            return true;
        }
        final var lines = output.split("\n", -1);
        final var directory = DIRECTORY.matcher(lines[0]);
        final var ranges = new ArrayList<Range>();
        if (directory.matches()) {
            final var start = number(directory.group(1));
            final var end = number(directory.group(2));
            if (start < 1 || end < start) {
                return true;
            }
            var count = 0L;
            for (var i = 1; i < lines.length - 1 && count < end - start + 1; i++) {
                if (lines[i].isEmpty() || lines[i].startsWith("... ") || isIngestionMarker(lines[i])) {
                    break;
                }
                // The cap inserts a newline after a potentially partial head entry.
                if (i + 1 < lines.length && isIngestionMarker(lines[i + 1])) {
                    break;
                }
                count++;
            }
            if (count > 0) {
                ranges.add(new Range(start, start + count - 1));
            }
        } else {
            for (var i = 0; i < lines.length - 1; i++) {
                // Head/tail capping can split both boundary lines. Never claim either fragment.
                if (isIngestionMarker(lines[i])
                        || (i > 0 && isIngestionMarker(lines[i - 1]))
                        || isIngestionMarker(lines[i + 1])) {
                    continue;
                }
                final var match = NUMBERED_LINE.matcher(lines[i]);
                if (match.matches()) {
                    final var line = number(match.group(1));
                    if (line > 0) {
                        ranges.add(new Range(line, line));
                    }
                }
            }
        }
        if (ranges.isEmpty()) {
            return true;
        }
        final var covered = intervals.computeIfAbsent(
                key + (directory.matches() ? ":directory" : ":file"), ignored -> new TreeMap<>());
        var novel = false;
        for (final var range : ranges) {
            novel |= merge(covered, range);
        }
        return novel;
    }

    /** Invalidates an absolute/resolved written path and ancestor directory listings, but not sibling files. */
    public void invalidate(final Path writtenPath) {
        final var path = writtenPath.toAbsolutePath().normalize();
        final var marker = key(path);
        intervals
                .keySet()
                .removeIf(candidate -> candidate.equals(marker + ":file")
                        || candidate.equals(marker + ":directory")
                        || (candidate.endsWith(":directory")
                                && path.startsWith(
                                        Path.of(candidate.substring(PREFIX.length(), candidate.length() - 10)))));
    }

    /** Clears all read coverage at the start of a new turn. */
    public void clear() {
        intervals.clear();
    }

    private static boolean isIngestionMarker(final String line) {
        return line.startsWith("[... output truncated (") && line.endsWith("kept head and tail ...]");
    }

    private static long number(final String value) {
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    private static boolean merge(final TreeMap<Long, Long> covered, final Range range) {
        final var previous = covered.floorEntry(range.start());
        if (previous != null && previous.getValue() >= range.end()) {
            return false;
        }
        var start = range.start();
        var end = range.end();
        if (previous != null && previous.getValue() >= start - 1) {
            start = previous.getKey();
            end = Math.max(end, previous.getValue());
            covered.remove(previous.getKey());
        }
        var next = covered.ceilingEntry(start);
        while (next != null && (next.getKey() <= end || next.getKey() - 1 == end)) {
            end = Math.max(end, next.getValue());
            covered.remove(next.getKey());
            next = covered.ceilingEntry(start);
        }
        covered.put(start, end);
        return true;
    }

    private record Range(long start, long end) {}
}
