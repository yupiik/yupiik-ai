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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compacts GitHub CLI output for the read-only inspection commands an agent runs most:
 * {@code gh pr view} / {@code gh issue view} keep the short description and remove ASCII
 * decoration and verbose metadata, {@code gh pr checks} keeps status+name with failures
 * highlighted, {@code gh run list} keeps the run name/status, {@code gh run view} the jobs,
 * and {@code gh issue list} / {@code gh pr list} a compact one-line-per-item shape.
 * Matches the read-only gh families of {@code yca.permissions.bash.rules}.
 */
@ApplicationScoped
public class GhOutputProcessor implements OutputProcessor {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ISSUE_LIST_ROW = Pattern.compile("^#?\\d+\\s+.*");
    private static final Pattern REPEATED_WHITESPACE = Pattern.compile("\\s{2,}");
    private static final Pattern RUN_STATUS_SYMBOL = Pattern.compile("^(✓|✗|X|\\*|\\?)\\s+.*");
    private static final Pattern RUN_STATUS_NAME =
            Pattern.compile("^(completed|in_progress|queued|action_required|cancelled)\\s+.*");

    private final CommandOutputPostProcessor commandOutput;

    public GhOutputProcessor(final CommandOutputPostProcessor commandOutput) {
        this.commandOutput = commandOutput;
    }

    public GhOutputProcessor() {
        this(new CommandOutputPostProcessor());
    }

    private static final String[] DECORATION = {
        "title:",
        "head:",
        "base:",
        "labels:",
        "assignee:",
        "author:",
        "milestone:",
        "reviewers:",
        "projects:",
        "linked issues:",
        "number:",
        "state:",
        "draft:",
        "auto-merge:",
        "url:",
        "created:",
        "updated:",
        "closed:"
    };

    @Override
    public boolean supports(final String command) {
        return commandOutput.matchesHead(command, "gh");
    }

    @Override
    public int order() {
        return 13;
    }

    @Override
    public Set<String> names() {
        return Set.of("gh");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var all = lines.map(commandOutput::stripAnsi).toList();
        final var sub = subCommand(command);
        return switch (sub) {
            case "pr", "issue" -> processPrIssue(command, all);
            case "run" -> processRun(command, all);
            default -> all.stream().filter(l -> !isNoise(l));
        };
    }

    private Stream<String> processPrIssue(final String command, final List<String> all) {
        // list/status shapes: "123  title  branch  state" or a status line "checks: ..."
        final var verb = commandOutput.thirdToken(command);
        if ("list".equals(verb)) {
            final var seen = new LinkedHashSet<String>();
            for (final var raw : all) {
                final var line = raw.strip();
                if (line.isBlank() || line.startsWith("Showing") || line.startsWith("--") || line.startsWith("Draft")) {
                    continue;
                }
                // drop the trailing state column noise: keep #number + title + branch
                if (ISSUE_LIST_ROW.matcher(line).matches()) {
                    seen.add(REPEATED_WHITESPACE.matcher(line).replaceAll(" "));
                }
            }
            return seen.stream().limit(30);
        }
        // view: keep the first title line and the body; drop decoration metadata and markdown noise
        final var out = new ArrayList<String>();
        var inBody = false;
        for (final var raw : all) {
            final var line = raw.strip();
            if (line.isBlank()) {
                continue;
            }
            if (isNoise(line)) {
                continue;
            }
            if (startsAny(line, DECORATION) && !inBody) {
                continue;
            }
            if (line.startsWith("## ")) {
                inBody = true;
            }
            out.add(line);
        }
        return out.stream();
    }

    private Stream<String> processRun(final String command, final List<String> all) {
        final var verb = commandOutput.thirdToken(command);
        if ("view".equals(verb)) {
            final var out = new ArrayList<String>();
            for (final var raw : all) {
                final var line = raw.strip();
                if (line.isBlank() || startsAny(line, "You can also", "Use `gh run view")) {
                    continue;
                }
                out.add(line);
            }
            return out.stream();
        }
        // list: "STATUS  NAME  WORKFLOW  BRANCH  EVENT  ID" -> keep STATUS NAME BRANCH
        final var out = new ArrayList<String>();
        for (final var raw : all) {
            final var line = raw.strip();
            if (line.isBlank() || line.startsWith("Showing") || line.startsWith("No runs")) {
                continue;
            }
            if (RUN_STATUS_SYMBOL.matcher(line).matches()) {
                out.add(line);
            } else if (RUN_STATUS_NAME.matcher(line).matches()) {
                out.add(line);
            }
        }
        return out.stream();
    }

    private static String subCommand(final String command) {
        final var tokens = WHITESPACE.split(command.strip());
        for (final var t : tokens) {
            if (!t.equals("gh") && !t.startsWith("-")) {
                return t;
            }
        }
        return "";
    }

    private static boolean startsAny(final String line, final String... prefixes) {
        for (final var p : prefixes) {
            if (line.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNoise(final String line) {
        final var t = line.strip();
        return t.startsWith("[![")
                || t.startsWith("![")
                || t.startsWith("<!--")
                || t.endsWith("-->")
                || t.equals("---")
                || t.equals("***")
                || t.startsWith("<img ")
                || t.equals("<p align=\"center\">")
                || t.equals("</p>")
                || t.startsWith("<a href=");
    }
}
