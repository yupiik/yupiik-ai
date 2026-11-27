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
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class GitOutputProcessor implements OutputProcessor {
    private static final Pattern PULL_SUMMARY =
            Pattern.compile("(\\d+) files? changed, (\\d+) insertions?\\(\\+\\), (\\d+) deletions?\\(-\\)");
    private static final Pattern GIT_STASH_PREFIX = Pattern.compile("^git\\s+stash\\s*");
    private static final Pattern GIT_PREFIX = Pattern.compile("^git\\s+");

    private static final int MAX_HUNK_LINES = 8;
    private static final int MAX_LOG_BODY = 3;
    private static final int DEFAULT_MAX_BLOB_BYTES = 8192;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern COMMIT_LINE = Pattern.compile("\\[([^\\]\\s]+)\\s+([0-9a-f]{7,40})\\]");
    private static final Pattern PUSH_BRANCH = Pattern.compile("\\*\\s+([^\\s]+)\\s+->");

    private final int maxBlobBytes;
    private final CommandOutputPostProcessor commandOutput;

    protected GitOutputProcessor() {
        this(DEFAULT_MAX_BLOB_BYTES);
    }

    public GitOutputProcessor(
            final CodingAgentConfiguration configuration, final CommandOutputPostProcessor commandOutput) {
        this(resolveMaxBlobBytes(configuration), commandOutput);
    }

    public GitOutputProcessor(final CodingAgentConfiguration configuration) {
        this(resolveMaxBlobBytes(configuration), new CommandOutputPostProcessor());
    }

    GitOutputProcessor(final int maxBlobBytes, final CommandOutputPostProcessor commandOutput) {
        this.maxBlobBytes = maxBlobBytes;
        this.commandOutput = commandOutput;
    }

    GitOutputProcessor(final int maxBlobBytes) {
        this(maxBlobBytes, new CommandOutputPostProcessor());
    }

    private static int resolveMaxBlobBytes(final CodingAgentConfiguration configuration) {
        if (configuration == null
                || configuration.optimize() == null
                || configuration.optimize().toolOutputs() == null
                || configuration.optimize().toolOutputs().git() == null) {
            return DEFAULT_MAX_BLOB_BYTES;
        }
        return configuration.optimize().toolOutputs().git().effectiveMaxBlobBytes();
    }

    @Override
    public boolean supports(final String command) {
        return commandOutput.matchesHead(command, "git");
    }

    @Override
    public int order() {
        return 12;
    }

    @Override
    public Set<String> names() {
        return Set.of("git");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var sub = subCommand(command);
        return switch (sub) {
            case "diff" ->
                commandOutput.containsFlag(
                                command,
                                "-p",
                                "-u",
                                "--patch",
                                "--stat",
                                "--numstat",
                                "--word-diff",
                                "--name-only",
                                "--name-status")
                        ? lines
                        : processDiff(lines);
            case "log" ->
                commandOutput.containsFlag(
                                command,
                                "-p",
                                "-u",
                                "--patch",
                                "--stat",
                                "--format",
                                "--pretty",
                                "--oneline",
                                "--graph")
                        ? lines
                        : processLog(lines, false);
            case "show" ->
                commandOutput.containsFlag(command, "--stat", "--format", "--pretty", "--name-only", "--name-status")
                        ? lines
                        : processShow(command, lines);
            case "status" -> commandOutput.containsFlag(command, "--porcelain") ? lines : processStatus(lines);
            case "stash" -> processStash(command, lines);
            case "add", "commit", "push", "pull", "merge", "fetch" -> processMutation(command, lines);
            default -> lines;
        };
    }

    private static String subCommand(final String command) {
        final var parts = WHITESPACE.split(command.strip());
        if (parts.length < 2) {
            return "";
        }
        var idx = 1;
        if (parts[idx].startsWith("-") && idx + 1 < parts.length) {
            idx++;
        }
        return parts[idx];
    }

    private Stream<String> processDiff(final Stream<String> lines) {
        final var all = lines.toList();
        final var out = new ArrayList<String>();
        var inHunk = false;
        var hunkLines = 0;
        var hunkSkipped = 0;
        var files = 0;
        var dropped = 0;
        for (final var line : all) {
            if (line.startsWith("diff --git ")) {
                files++;
                inHunk = false;
                out.add("");
                out.add(line);
                continue;
            }
            if (line.startsWith("index ")
                    || line.startsWith("new file mode")
                    || line.startsWith("deleted file mode")
                    || line.startsWith("similarity index")
                    || line.startsWith("rename ")) {
                dropped++;
                continue;
            }
            if (line.startsWith("@@")) {
                inHunk = true;
                hunkLines = 0;
                hunkSkipped = 0;
                out.add(line);
                continue;
            }
            if (inHunk) {
                if (hunkLines < MAX_HUNK_LINES) {
                    hunkLines++;
                    out.add(line);
                } else {
                    hunkSkipped++;
                }
                continue;
            }
            out.add(line);
        }
        dropped += hunkSkipped;
        if (hunkSkipped > 0) {
            out.add("... +" + hunkSkipped + " more lines in last hunk");
        }
        if (files > 0 && dropped > 0) {
            out.add("[processed diff of " + files
                    + " file(s); run the command with --no-pager and without the optimizer for the full output]");
        }
        return out.stream();
    }

    private Stream<String> processLog(final Stream<String> lines, final boolean withDiff) {
        final var out = new ArrayList<String>();
        final var buffer = new ArrayList<String>();
        var body = 0;
        for (final var raw : lines.toList()) {
            if (raw.startsWith("commit ")) {
                flushLogEntry(out, buffer);
                buffer.clear();
                body = 0;
                buffer.add(raw.substring("commit ".length(), Math.min(20, raw.length())));
                continue;
            }
            if (raw.startsWith("Author:") || raw.startsWith("Date:") || raw.startsWith("Merge:") || raw.isBlank()) {
                continue;
            }
            if (body < MAX_LOG_BODY) {
                buffer.add("  " + raw.strip());
                body++;
            }
        }
        flushLogEntry(out, buffer);
        return out.stream();
    }

    private void flushLogEntry(final List<String> out, final List<String> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        final var sha = buffer.get(0);
        out.add(sha);
        for (var i = 1; i < buffer.size(); i++) {
            out.add(buffer.get(i));
        }
    }

    private Stream<String> processShow(final String command, final Stream<String> lines) {
        final var all = lines.toList();
        if (all.stream().noneMatch(line -> line.startsWith("diff --git "))) {
            // `git show <rev>:<path>` prints raw file/tree content, never a commit diff:
            // compacting it to 8 lines + "..." made the model re-read the same file in
            // tiny windows (186 calls in one session). Pass it through whole, windowing
            // only the giant blobs so a single dump cannot blow the context. Output-based
            // gate only: drive letters (C:\...), `-S 'a:b'` and `--pretty=format:` can
            // never misroute a real commit diff (which always carries `diff --git`).
            final var max = maxBlobBytes;
            if (max <= 0) {
                return all.stream(); // windowing disabled: byte-identical passthrough
            }
            final var full = String.join("\n", all);
            if (full.length() <= max) {
                return all.stream(); // whole file fits
            }
            final var cut = full.lastIndexOf('\n', max - 1); // char boundary, no UTF-8 split
            if (cut < 0) {
                return all.stream(); // single line over budget -> passthrough (rtk parity)
            }
            final var head = full.substring(0, cut + 1);
            final var headLines = head.lines().count();
            final var remaining = all.size() - (int) headLines;
            final var hint = "git show " + blobArg(command) + " | sed -n '" + (headLines + 1) + ",$p'";
            return Stream.concat(
                    Stream.of(head, String.format("... (+%d lines truncated) [see remaining: %s]", remaining, hint)),
                    Stream.empty());
        }
        final var out = new ArrayList<String>();
        var inDiff = false;
        var headerLines = 0;
        final var diffLines = new ArrayList<String>();
        for (final var line : all) {
            if (!inDiff) {
                if (line.startsWith("diff --git ")) {
                    inDiff = true;
                    diffLines.add(line);
                    continue;
                }
                if (headerLines < 8) {
                    out.add(line);
                    headerLines++;
                } else if (headerLines == 8) {
                    out.add("...");
                    headerLines++;
                }
                continue;
            }
            diffLines.add(line);
        }
        if (!diffLines.isEmpty()) {
            out.addAll(processDiff(diffLines.stream()).toList());
        }
        return out.stream();
    }

    private static String blobArg(final String command) {
        final var tokens = WHITESPACE.split(command.strip());
        // Stop at a pipe or shell operator: only the `git show ...` segment precedes it,
        // so the trailing consumer (`sed`, `head`, ...) never leaks into the hint.
        for (var i = tokens.length - 1; i >= 0; i--) {
            final var token = tokens[i];
            if (token.equals("|") || token.equals("||") || token.equals("&&") || token.equals(";")) {
                break;
            }
            if (!token.isEmpty() && !token.startsWith("-")) {
                return "'" + token + "'";
            }
        }
        return "'<rev>:<path>'";
    }

    private Stream<String> processStatus(final Stream<String> lines) {
        return lines.filter(line -> !line.isBlank())
                .filter(line -> !line.strip().startsWith("(use "))
                .filter(line -> !line.startsWith("nothing to commit"))
                .map(commandOutput::stripAnsi);
    }

    private Stream<String> processStash(final String command, final Stream<String> lines) {
        final var action =
                commandOutput.secondToken(GIT_STASH_PREFIX.matcher(command).replaceFirst(""));
        if (!"list".equals(action) && !"".equals(action)) {
            return lines; // push/pop/apply/... carry real output, keep it
        }
        // stash list: "stash@{0}: WIP on main: abc1234 message" -> "stash@{0}: abc1234 message"
        return lines.map(commandOutput::stripAnsi).map(line -> {
            final var i = line.indexOf(": WIP on ");
            if (i >= 0) {
                return line.substring(0, i) + ":"
                        + line.substring(line.lastIndexOf(':', line.indexOf(": WIP on ") + 2));
            }
            return line;
        });
    }

    private Stream<String> processMutation(final String command, final Stream<String> lines) {
        final var out = new ArrayList<String>();
        final var sub = commandOutput.firstToken(GIT_PREFIX.matcher(command).replaceFirst(""));
        for (final var raw : lines.toList()) {
            final var line = commandOutput.stripAnsi(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            // pull/fetch progress noise
            if (line.startsWith("remote:")
                    || line.startsWith("Receiving objects")
                    || line.startsWith("Resolving deltas")
                    || line.startsWith("Unpacking objects")
                    || line.startsWith("Counting objects")
                    || line.startsWith("Compressing objects")
                    || line.startsWith("From ")
                    || line.startsWith(" * branch")) {
                continue;
            }
            // commit confirmation: "[main abc1234] message"
            final var c = COMMIT_LINE.matcher(line);
            if (c.find()) {
                out.add("ok " + c.group(2));
                continue;
            }
            // push confirmation: "To github.com:repo.git" / "* [new branch] main -> main"
            if (line.startsWith("To ") || PUSH_BRANCH.matcher(line).find() || line.startsWith(" * ")) {
                out.add(line);
                continue;
            }
            if ("pull".equals(sub)) {
                final var files = PULL_SUMMARY.matcher(line);
                if (files.matches()) {
                    out.add("ok " + files.group(1) + " files changed");
                    continue;
                }
                if (line.startsWith("Already up to date") || line.startsWith("Already up-to-date")) {
                    out.add("ok (already up to date)");
                    continue;
                }
            }
            if ("merge".equals(sub)) {
                if (line.startsWith("Merge made by") || line.startsWith("Fast-forward")) {
                    out.add("ok (merged)");
                    continue;
                }
            }
            out.add(line);
        }
        return out.stream();
    }
}
