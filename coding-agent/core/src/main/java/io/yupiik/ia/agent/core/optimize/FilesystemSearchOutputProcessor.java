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
package io.yupiik.ia.agent.core.optimize;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compacts filesystem listing and search command output: {@code ls}/{@code find}/{@code tree}/{@code du}
 * listings are grouped with counts, {@code grep}/{@code rg}/{@code egrep}/{@code fgrep}/{@code ast-grep}
 * matches are grouped per file with long lines truncated, and repeated lines are collapsed everywhere.
 * Mirrors the rtk filesystem/search compaction for the read-only bash families.
 */
@ApplicationScoped
public class FilesystemSearchOutputProcessor implements OutputProcessor {
    private static final int MAX_CONTENT_CHARS = 200;
    private static final Pattern COLON_LINE =
            Pattern.compile("^([^:]+):(\\d+):(.*)$"); // path:line:content (grep/rg -n over several files)
    private static final Pattern NUMBERED_LINE = Pattern.compile("^(\\d+):(.*)$"); // line:content (single file -n)

    private final CodingAgentConfiguration configuration;
    private final CommandOutputPostProcessor commandOutput;

    public FilesystemSearchOutputProcessor(
            final CodingAgentConfiguration configuration, final CommandOutputPostProcessor commandOutput) {
        this.configuration = configuration;
        this.commandOutput = commandOutput;
    }

    public FilesystemSearchOutputProcessor(final CodingAgentConfiguration configuration) {
        this(configuration, new CommandOutputPostProcessor());
    }

    protected FilesystemSearchOutputProcessor() {
        this.configuration = null;
        this.commandOutput = new CommandOutputPostProcessor();
    }

    @Override
    public boolean supports(final String command) {
        return commandOutput.matchesHeadToken(command, "ls", "find", "tree", "du")
                || commandOutput.matchesHead(command, "grep", "rg", "egrep", "fgrep", "ast-grep");
    }

    @Override
    public int order() {
        return 16;
    }

    @Override
    public Set<String> names() {
        return Set.of("ls", "find", "tree", "du", "grep", "rg", "egrep", "fgrep", "ast-grep");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var all = lines.map(commandOutput::stripAnsi).toList();
        final var head = commandOutput.headToken(command);
        if (isListing(head)) {
            return processListing(head, all);
        }
        return processSearch(all);
    }

    private boolean isListing(final String head) {
        return "ls".equals(head) || "find".equals(head) || "tree".equals(head) || "du".equals(head);
    }

    private Stream<String> processListing(final String head, final List<String> all) {
        final var out = new ArrayList<String>();
        if ("ls".equals(head)) {
            // drop the "total N" line, "." and ".." entries; keep the rest as-is (already short),
            // truncating the tail when a huge directory overflows the generic line budget.
            for (final var raw : all) {
                final var line = raw.strip();
                if (line.startsWith("total ") || line.equals(".") || line.equals("..") || line.isBlank()) {
                    continue;
                }
                out.add(line);
            }
            return dedupe(cap(out, maxEntries()).toList()).stream();
        }
        // find / tree / du: group paths by their parent so a directory is listed once with a count.
        final var grouped = new LinkedHashMap<String, List<String>>();
        for (final var raw : all) {
            final var line = commandOutput.stripAnsi(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if ("du".equals(head)) {
                // "12\t./path" -> keep the size and drop the leading ./ for grouping
                final var tab = line.indexOf('\t');
                final var size = tab >= 0 ? line.substring(0, tab) : "";
                final var path = tab >= 0 ? line.substring(tab + 1) : line;
                grouped.computeIfAbsent(parent(path), k -> new ArrayList<>()).add(size + " " + path);
                continue;
            }
            grouped.computeIfAbsent(parent(line), k -> new ArrayList<>()).add(line);
        }
        for (final var e : grouped.entrySet()) {
            out.add(e.getKey() + " (" + e.getValue().size() + "):");
            var count = 0;
            for (final var entry : e.getValue()) {
                if (count++ >= maxEntries()) {
                    out.add("  ... +" + (e.getValue().size() - count + 1) + " more");
                    break;
                }
                out.add("  " + entry);
            }
        }
        return cap(out, maxEntries() * 4L);
    }

    private static String parent(final String path) {
        final var normalized = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        final var slash = normalized.lastIndexOf('/');
        return slash <= 0 ? "." : normalized.substring(0, slash);
    }

    private Stream<String> processSearch(final List<String> all) {
        final var out = new ArrayList<String>();
        final var byFile = new LinkedHashMap<String, List<String>>();
        for (final var raw : all) {
            final var line = commandOutput.stripAnsi(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            final var m = COLON_LINE.matcher(line);
            if (m.matches()) {
                byFile.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                        .add(m.group(2) + ": " + truncate(m.group(3).strip()));
                continue;
            }
            final var n = NUMBERED_LINE.matcher(line);
            if (n.matches()) {
                byFile.computeIfAbsent("<stdin>", k -> new ArrayList<>())
                        .add(n.group(1) + ": " + truncate(n.group(2).strip()));
                continue;
            }
            // not a :line: match (e.g. `rg -l` listing only file names, or a trailing summary)
            out.add(line);
        }
        var total = 0;
        for (final var e : byFile.entrySet()) {
            if (total >= maxEntries()) {
                out.add("... +"
                        + byFile.entrySet().stream()
                                .skip(1)
                                .mapToInt(x -> x.getValue().size())
                                .sum() + " more");
                break;
            }
            out.add(e.getKey() + ":");
            var count = 0;
            for (final var entry : e.getValue()) {
                if (total + count >= maxEntries()) {
                    out.add("  ... +" + (e.getValue().size() - count) + " more");
                    break;
                }
                out.add("  " + entry);
                count++;
            }
            total += count;
        }
        return dedupe(out).stream();
    }

    private static String truncate(final String s) {
        return s.length() <= MAX_CONTENT_CHARS ? s : s.substring(0, MAX_CONTENT_CHARS) + "...";
    }

    private int maxEntries() {
        return configuration == null || configuration.optimize() == null
                ? 20
                : Math.max(1, configuration.optimize().maxEntries());
    }

    private Stream<String> cap(final List<String> lines, final long max) {
        if (lines.size() <= max) {
            return lines.stream();
        }
        final var out = new ArrayList<String>(lines.subList(0, (int) (max - 1)));
        out.add("... +" + (lines.size() - max + 1) + " more");
        return out.stream();
    }

    /** Collapses runs of 3+ identical consecutive lines into "N x <line>". */
    static List<String> dedupe(final List<String> lines) {
        final var out = new ArrayList<String>(lines.size());
        String previous = null;
        var run = 0;
        for (final var line : lines) {
            if (line.equals(previous)) {
                run++;
                continue;
            }
            flushRun(out, previous, run);
            previous = line;
            run = 1;
        }
        flushRun(out, previous, run);
        return out;
    }

    private static void flushRun(final List<String> out, final String line, final int run) {
        if (line == null) {
            return;
        }
        if (run > 2) {
            out.add(run + " x " + line);
        } else {
            for (var i = 0; i < run; i++) {
                out.add(line);
            }
        }
    }
}
