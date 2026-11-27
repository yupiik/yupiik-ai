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
package io.yupiik.ia.agent.github.context.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A positive, lexical grammar for reads from an already prepared Git object database.
 * This does not execute Git, resolve refs, inspect a worktree, or parse shell syntax.
 * The caller must execute the returned arguments literally in its isolated repository.
 *
 * <p>Limits: 128 input arguments, 4096 characters per argument, 32768 characters total,
 * 32 paths (512 characters and 16 wildcard characters each), 64-character ref components,
 * 8 ancestry operators and 1000 total ancestry steps. History counts default to 100 and
 * are restricted to 1..1000. Refs are HEAD, BASE, 4..64 hex object IDs, simple branch/tag
 * names, or refs/heads, refs/tags and refs/remotes names. Reflogs, object searches, ref
 * peeling, exclusions and arbitrary revision expressions are intentionally not supported.
 * Branch and tag reads are always listings. Paths must follow a literal -- separator.
 * Diff needs two trees (or a two-ended range); grep and blame default to HEAD, never
 * the index/worktree. Omitted log/show/rev-list/ls-tree revisions default to HEAD.
 *
 * <p>This is not an output-size or execution-time limiter. The executor must enforce
 * those limits separately, including for a single large blob or a large ref listing.
 *
 * <p>Validation is a one-pass raw-input boundary. Normal forms are stable for ordinary
 * commands, but callers must not require revalidation: injected guards can exceed the
 * raw argument/count limits. In particular, prevalidating services must pass the original
 * immutable argv to an executor which performs its own final validation.
 */
public final class GitReadPolicy {
    private static final Pattern MERGE_PARENT_OPTION = Pattern.compile("-m[0-9]+");
    private static final Pattern SHORT_COUNT_OPTION = Pattern.compile("-[0-9]+");
    private static final Pattern MAX_COUNT_OPTION = Pattern.compile("-n[0-9]+");
    private static final Pattern RENAME_THRESHOLD_OPTION = Pattern.compile("-M[0-9]{1,3}%?");
    private static final Pattern RENAME_THRESHOLD = Pattern.compile("[0-9]{1,3}%?");
    private static final Pattern RENAME_LIMIT_OPTION = Pattern.compile("-l[0-9]+");
    private static final Pattern FOLLOW_COUNT = Pattern.compile("[0-9]{1,4}");
    private static final Pattern LINE_RANGE = Pattern.compile("[0-9]{1,6},[0-9]{1,6}");
    private static final Pattern DIFF_FILTER = Pattern.compile("[ACDMRTUXBacdmrtuxb]{1,18}");
    private static final Pattern BASE_REVISION = Pattern.compile("(^|\\.\\.\\.?)BASE(?=$|[~^.])");
    private static final Pattern CONTEXT_LINE_COUNT = Pattern.compile("[0-9]{1,6}");

    private static final Set<String> COMMANDS = Set.of(
            "log",
            "show",
            "diff",
            "grep",
            "blame",
            "ls-tree",
            "rev-parse",
            "merge-base",
            "rev-list",
            "cat-file",
            "branch",
            "tag");
    private static final Set<String> HISTORY = Set.of("log", "show", "rev-list");
    private static final Set<String> DIFF_OUTPUT = Set.of("log", "show", "diff");
    private static final Set<String> PATH_COMMANDS = Set.of("log", "show", "diff", "grep", "blame", "ls-tree");
    private static final Map<String, Set<String>> FLAGS = Map.ofEntries(
            Map.entry(
                    "log",
                    Set.of(
                            "--oneline",
                            "--stat",
                            "--shortstat",
                            "--numstat",
                            "--name-only",
                            "--name-status",
                            "--patch",
                            "-p",
                            "--no-patch",
                            "-s",
                            "--first-parent",
                            "--merges",
                            "--no-merges",
                            "--reverse",
                            "--topo-order",
                            "--date-order",
                            "--graph",
                            "--decorate",
                            "--no-decorate",
                            "--no-color",
                            "--no-ext-diff",
                            "--no-textconv",
                            "--no-show-signature")),
            Map.entry(
                    "show",
                    Set.of(
                            "--oneline",
                            "--stat",
                            "--shortstat",
                            "--numstat",
                            "--name-only",
                            "--name-status",
                            "--patch",
                            "-p",
                            "--no-patch",
                            "-s",
                            "--no-color",
                            "--no-ext-diff",
                            "--no-textconv",
                            "--no-show-signature")),
            Map.entry(
                    "diff",
                    Set.of(
                            "--stat",
                            "--shortstat",
                            "--numstat",
                            "--name-only",
                            "--name-status",
                            "--patch",
                            "-p",
                            "--no-patch",
                            "-s",
                            "--check",
                            "--summary",
                            "--minimal",
                            "--patience",
                            "--histogram",
                            "--word-diff",
                            "--ignore-all-space",
                            "-w",
                            "--ignore-space-change",
                            "-b",
                            "--ignore-space-at-eol",
                            "--ignore-blank-lines",
                            "--no-renames",
                            "--no-color",
                            "--no-ext-diff",
                            "--no-textconv")),
            Map.entry(
                    "grep",
                    Set.of(
                            "-n",
                            "--line-number",
                            "-i",
                            "--ignore-case",
                            "-F",
                            "--fixed-strings",
                            "-E",
                            "--extended-regexp",
                            "-G",
                            "--basic-regexp",
                            "-w",
                            "--word-regexp",
                            "-v",
                            "--invert-match",
                            "-l",
                            "--files-with-matches",
                            "-L",
                            "--files-without-match",
                            "-c",
                            "--count",
                            "-h",
                            "-H",
                            "--full-name",
                            "--no-color",
                            "--no-textconv")),
            Map.entry(
                    "blame",
                    Set.of(
                            "-w",
                            "-s",
                            "-n",
                            "-l",
                            "-e",
                            "--line-porcelain",
                            "--porcelain",
                            "--incremental",
                            "--show-email",
                            "--show-name",
                            "--show-number",
                            "--root",
                            "--no-textconv")),
            Map.entry(
                    "ls-tree",
                    Set.of("-r", "-t", "-d", "-l", "-z", "--name-only", "--name-status", "--full-name", "--full-tree")),
            Map.entry(
                    "rev-list",
                    Set.of(
                            "--count",
                            "--first-parent",
                            "--merges",
                            "--no-merges",
                            "--reverse",
                            "--topo-order",
                            "--date-order")),
            Map.entry("rev-parse", Set.of("--verify", "--short", "--is-shallow-repository")),
            Map.entry("merge-base", Set.of("--all", "-a", "--is-ancestor", "--octopus")),
            Map.entry("cat-file", Set.of("-p", "-t", "-s")),
            Map.entry(
                    "branch",
                    Set.of("--list", "-a", "--all", "-r", "--remotes", "-v", "-vv", "--verbose", "--no-color")),
            Map.entry("tag", Set.of("--list", "-l")));
    private static final Pattern REF_COMPONENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}");
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{4,64}");
    private static final Set<String> PSEUDO_REFS = Set.of(
            "FETCH_HEAD", "ORIG_HEAD", "MERGE_HEAD", "REBASE_HEAD", "REVERT_HEAD", "CHERRY_PICK_HEAD", "AUTO_MERGE");

    /** Returns an immutable, subcommand-first argv, or throws an error which never echoes input. */
    public List<String> validate(final List<String> argv) {
        checkInput(argv);
        int start = "git".equals(argv.getFirst()) ? 1 : 0;
        while (start < argv.size()
                && Set.of("--no-pager", "--no-optional-locks").contains(argv.get(start))) {
            start++;
        }
        if (start == argv.size()) {
            throw invalid("Missing Git read command");
        }
        final var command = argv.get(start);
        if (Set.of("status", "ls-files").contains(command)) {
            throw local();
        }
        if (!COMMANDS.contains(command)) {
            throw invalid("Unsupported Git read command");
        }
        final var options = new ArrayList<String>();
        final var operands = new ArrayList<String>();
        final var paths = new ArrayList<String>();
        boolean separated = false;
        boolean pattern = false;
        boolean catMode = false;
        int count = 100;
        boolean hasCount = false;
        int grepCount = 100;
        boolean hasGrepCount = false;
        for (int i = start + 1; i < argv.size(); i++) {
            final var arg = argv.get(i);
            if (separated) {
                path(arg, !"blame".equals(command));
                paths.add(arg);
                if (paths.size() > 32) {
                    throw invalid("Too many paths");
                }
                continue;
            }
            if ("--".equals(arg)) {
                if (!PATH_COMMANDS.contains(command)) {
                    throw invalid("Paths are not supported for this command");
                }
                separated = true;
                continue;
            }
            if ("diff".equals(command) && Set.of("--cached", "--staged").contains(arg)) {
                throw local();
            }
            if ("grep".equals(command)
                    && ("-m".equals(arg)
                            || MERGE_PARENT_OPTION.matcher(arg).matches()
                            || "--max-count".equals(arg)
                            || arg.startsWith("--max-count="))) {
                if (hasGrepCount) {
                    throw invalid("Duplicate grep count");
                }
                final String value;
                if ("-m".equals(arg) || "--max-count".equals(arg)) {
                    if (++i == argv.size()) {
                        throw invalid("Missing grep count");
                    }
                    value = argv.get(i);
                } else {
                    value = arg.substring(arg.startsWith("--") ? 12 : 2);
                }
                grepCount = number(value, 1, 1000);
                hasGrepCount = true;
                continue;
            }
            if (HISTORY.contains(command)
                    && (SHORT_COUNT_OPTION.matcher(arg).matches()
                            || MAX_COUNT_OPTION.matcher(arg).matches()
                            || "-n".equals(arg)
                            || "--max-count".equals(arg)
                            || arg.startsWith("--max-count="))) {
                if (hasCount) {
                    throw invalid("Duplicate history count");
                }
                final String value;
                if ("-n".equals(arg) || "--max-count".equals(arg)) {
                    if (++i == argv.size()) {
                        throw invalid("Missing history count");
                    }
                    value = argv.get(i);
                } else {
                    value = arg.substring(arg.startsWith("--") ? 12 : arg.startsWith("-n") ? 2 : 1);
                }
                count = number(value, 1, 1000);
                hasCount = true;
                continue;
            }
            if ("grep".equals(command) && ("-e".equals(arg) || "--regexp".equals(arg) || arg.startsWith("--regexp="))) {
                if (pattern) {
                    throw invalid("Only one grep pattern is supported");
                }
                final String value;
                if (arg.startsWith("--regexp=")) {
                    value = arg.substring(9);
                } else {
                    if (++i == argv.size()) {
                        throw invalid("Missing grep pattern");
                    }
                    value = argv.get(i);
                }
                options.add("-e");
                options.add(value);
                pattern = true;
                continue;
            }
            if ("diff".equals(command)
                    && (arg.equals("-M")
                            || RENAME_THRESHOLD_OPTION.matcher(arg).matches()
                            || arg.equals("--find-renames")
                            || arg.startsWith("--find-renames="))) {
                final var value = arg.startsWith("-M")
                        ? arg.substring(2)
                        : arg.equals("--find-renames") ? "" : arg.substring("--find-renames=".length());
                if (!value.isEmpty()
                        && (!RENAME_THRESHOLD.matcher(value).matches()
                                || Integer.parseInt(value.replace("%", "")) > 100)) {
                    throw invalid("Rename similarity must be between 0 and 100 percent");
                }
                options.add("--find-renames=" + (value.isEmpty() ? "50%" : value));
                continue;
            }
            if ("diff".equals(command)
                    && (arg.equals("-l") || RENAME_LIMIT_OPTION.matcher(arg).matches())) {
                final String value;
                if (arg.equals("-l")) {
                    if (++i == argv.size()) {
                        throw invalid("Missing rename limit");
                    }
                    value = argv.get(i);
                } else {
                    value = arg.substring(2);
                }
                if (!FOLLOW_COUNT.matcher(value).matches()
                        || Integer.parseInt(value) < 1
                        || Integer.parseInt(value) > 1000) {
                    throw invalid("Rename limit must be between 1 and 1000");
                }
                options.add("-l" + value);
                continue;
            }
            if (FLAGS.get(command).contains(arg)) {
                if ("cat-file".equals(command)) {
                    if (catMode) {
                        throw invalid("Only one cat-file mode is supported");
                    }
                    catMode = true;
                }
                if (!Set.of("--no-ext-diff", "--no-textconv", "--no-color", "--no-show-signature", "--list")
                        .contains(arg)) {
                    options.add(arg);
                }
                continue;
            }
            if (arg.startsWith("-")) {
                final var equals = arg.indexOf('=');
                final var attached = equals < 0
                        && arg.length() > 2
                        && Set.of("-U", "-A", "-B", "-C", "-L").contains(arg.substring(0, 2))
                        && Character.isDigit(arg.charAt(2));
                final var key = attached ? arg.substring(0, 2) : equals < 0 ? arg : arg.substring(0, equals);
                String value = attached ? arg.substring(2) : equals < 0 ? null : arg.substring(equals + 1);
                if ("--color".equals(key)
                        && "never".equals(value)
                        && (DIFF_OUTPUT.contains(command)
                                || Set.of("grep", "branch").contains(command))) {
                    continue;
                }
                if (!valueOption(command, key)) {
                    throw invalid("Unsupported Git read option");
                }
                if (value == null) {
                    if (++i == argv.size()) {
                        throw invalid("Missing option value");
                    }
                    value = argv.get(i);
                }
                optionValue(command, key, value);
                if ("-U".equals(key)) {
                    options.add("--unified=" + value); // Git's -U takes an attached, optional value.
                } else if (Set.of("-L", "-A", "-B", "-C").contains(key)) {
                    options.add(key);
                    options.add(value);
                } else {
                    options.add(key + "="
                            + (Set.of("--contains", "--no-contains", "--merged", "--no-merged", "--points-at")
                                            .contains(key)
                                    ? canonicalRevision(value)
                                    : value));
                }
                continue;
            }
            if ("grep".equals(command) && !pattern) {
                options.add("-e");
                options.add(arg);
                pattern = true;
            } else {
                operands.add(arg);
            }
        }
        final var result = new ArrayList<String>();
        result.add(command);
        if (DIFF_OUTPUT.contains(command)) {
            result.addAll(List.of("--no-ext-diff", "--no-textconv", "--color=never"));
            if ("diff".equals(command)
                    && options.stream()
                            .noneMatch(it -> RENAME_LIMIT_OPTION.matcher(it).matches())) {
                result.add("-l200");
            }
        } else if ("grep".equals(command)) {
            result.addAll(List.of("--no-textconv", "--color=never"));
            result.add("--max-count=" + grepCount);
        } else if ("blame".equals(command)) {
            result.add("--no-textconv");
        } else if ("branch".equals(command)) {
            result.add("--color=never");
        }
        if (HISTORY.contains(command)
                && (!"show".equals(command) || (hasCount && operands.stream().noneMatch(it -> it.contains(":"))))) {
            result.add("--max-count=" + count);
        }
        if (Set.of("log", "show").contains(command)) {
            result.add("--no-show-signature");
        }
        result.addAll(options);
        switch (command) {
            case "branch", "tag" -> {
                result.add("--list");
                for (final var operand : operands) {
                    refPattern(operand);
                }
            }
            case "diff" -> {
                if (operands.isEmpty()
                        || (operands.size() == 1 && !operands.getFirst().contains(".."))) {
                    throw local();
                }
                if (operands.size() == 1) {
                    revision(operands.getFirst(), true, false);
                } else if (operands.size() == 2) {
                    operands.forEach(it -> revision(it, false, false));
                } else {
                    throw invalid("Diff requires two trees");
                }
            }
            case "grep" -> {
                if (!pattern) {
                    throw invalid("Missing grep pattern");
                }
                defaultHead(operands);
                requireSize(operands, 1, 8);
                operands.forEach(it -> revision(it, false, false));
            }
            case "blame" -> {
                defaultHead(operands);
                requireSize(operands, 1, 1);
                if (paths.size() != 1) {
                    throw invalid("Blame requires one tree path after separator");
                }
                revision(operands.getFirst(), false, false);
            }
            case "cat-file" -> {
                if (!catMode) {
                    throw invalid("Cat-file requires a read mode");
                }
                requireSize(operands, 1, 1);
                revision(operands.getFirst(), false, true);
            }
            case "merge-base" -> {
                requireSize(operands, 2, 8);
                operands.forEach(it -> revision(it, false, false));
                if (options.contains("--is-ancestor") && operands.size() != 2) {
                    throw invalid("Ancestor comparison requires two revisions");
                }
            }
            case "rev-parse" -> {
                if (options.contains("--is-shallow-repository")) {
                    if (options.size() != 1 || !operands.isEmpty()) {
                        throw invalid("Shallow-state query does not accept revisions or other options");
                    }
                } else {
                    requireSize(operands, 1, options.contains("--verify") ? 1 : 8);
                    operands.forEach(it -> revision(it, false, false));
                }
            }
            default -> {
                defaultHead(operands);
                requireSize(operands, 1, "ls-tree".equals(command) ? 1 : 8);
                operands.forEach(
                        it -> revision(it, !Set.of("show", "ls-tree").contains(command), "show".equals(command)));
            }
        }
        result.addAll(
                Set.of("branch", "tag").contains(command)
                        ? operands
                        : operands.stream().map(this::canonicalRevision).toList());
        final var blobShow = "show".equals(command)
                && operands.size() == 1
                && (operands.getFirst().contains(":")
                        || (SHA.matcher(operands.getFirst()).matches() && paths.isEmpty()));
        if (blobShow && (operands.size() != 1 || !paths.isEmpty())) {
            throw invalid("Object show requires one object and no path filters");
        }
        if (PATH_COMMANDS.contains(command) && !blobShow) {
            result.add("--"); // Prevent Git's implicit revision-to-worktree-path fallback.
            result.addAll(paths);
        }
        return List.copyOf(result);
    }

    private void checkInput(final List<String> argv) {
        if (argv == null || argv.isEmpty() || argv.size() > 128) {
            throw invalid("Expected 1..128 Git arguments");
        }
        int total = 0;
        for (final var arg : argv) {
            if (arg == null || arg.isEmpty() || arg.length() > 4096) {
                throw invalid("Invalid Git argument length");
            }
            total += arg.length();
            for (int i = 0; i < arg.length(); i++) {
                if (Character.isISOControl(arg.charAt(i)) || Character.isSurrogate(arg.charAt(i))) {
                    throw invalid("Control characters are not supported");
                }
            }
        }
        if (total > 32768) {
            throw invalid("Git arguments exceed size limit");
        }
    }

    private boolean valueOption(final String command, final String key) {
        return switch (command) {
            case "log", "show" ->
                Set.of("--format", "--pretty", "--date", "--decorate", "--abbrev", "--unified", "-U")
                        .contains(key);
            case "diff" ->
                Set.of("--unified", "-U", "--diff-filter", "--word-diff", "--diff-algorithm")
                        .contains(key);
            case "grep" ->
                Set.of("-A", "-B", "-C", "--after-context", "--before-context", "--context")
                        .contains(key);
            case "ls-tree" -> "--format".equals(key);
            case "blame" -> Set.of("-L", "--date").contains(key);
            case "branch", "tag" ->
                Set.of("--format", "--sort", "--contains", "--no-contains", "--merged", "--no-merged", "--points-at")
                        .contains(key);
            case "rev-parse" -> "--short".equals(key);
            default -> false;
        };
    }

    private void optionValue(final String command, final String key, final String value) {
        switch (key) {
            case "--format", "--pretty" -> {
                if ("ls-tree".equals(command)) {
                    treeFormat(value);
                } else {
                    format(value, Set.of("branch", "tag").contains(command));
                }
            }
            case "--date" -> {
                if (!Set.of("relative", "iso", "iso-strict", "short", "raw", "unix", "default", "rfc")
                        .contains(value)) {
                    throw invalid("Unsupported date format");
                }
            }
            case "--decorate" -> {
                if (!Set.of("short", "full", "no").contains(value)) {
                    throw invalid("Unsupported decoration");
                }
            }
            case "--sort" -> {
                if (!Set.of(
                                "refname",
                                "-refname",
                                "version:refname",
                                "-version:refname",
                                "committerdate",
                                "-committerdate",
                                "creatordate",
                                "-creatordate")
                        .contains(value)) {
                    throw invalid("Unsupported ref sort");
                }
            }
            case "--contains", "--no-contains", "--merged", "--no-merged", "--points-at" ->
                revision(value, false, false);
            case "--abbrev", "--short" -> number(value, 4, 64);
            case "--unified", "-U", "-A", "-B", "-C", "--after-context", "--before-context", "--context" ->
                number(value, 0, 1000);
            case "-L" -> {
                if (!LINE_RANGE.matcher(value).matches()) {
                    throw invalid("Only numeric blame line ranges are supported");
                }
                final var parts = value.split(",");
                final var first = number(parts[0], 1, 100000);
                final var last = number(parts[1], first, 100000);
                if (last - first >= 1000) {
                    throw invalid("Blame line range exceeds limit");
                }
            }
            case "--diff-filter" -> {
                if (!DIFF_FILTER.matcher(value).matches()) {
                    throw invalid("Unsupported diff filter");
                }
            }
            case "--word-diff" -> {
                if (!Set.of("plain", "porcelain", "none").contains(value)) {
                    throw invalid("Unsupported word diff mode");
                }
            }
            case "--diff-algorithm" -> {
                if (!Set.of("myers", "minimal", "patience", "histogram").contains(value)) {
                    throw invalid("Unsupported diff algorithm");
                }
            }
            default -> throw invalid("Unsupported option value");
        }
    }

    private void treeFormat(final String value) {
        if (value.length() > 512) {
            throw invalid("Format exceeds limit");
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '%') {
                continue;
            }
            if (++i >= value.length()) {
                throw invalid("Malformed tree format");
            }
            if (value.charAt(i) == '%') {
                continue;
            }
            final var end = value.indexOf(')', i);
            if (value.charAt(i) != '('
                    || end < 0
                    || !Set.of("objectmode", "objecttype", "objectname", "objectsize", "objectsize:padded", "path")
                            .contains(value.substring(i + 1, end))) {
                throw invalid("Unsupported tree format atom");
            }
            i = end;
        }
    }

    private String canonicalRevision(final String value) {
        // Only called for validated revisions, never patterns, formats or tree paths.
        final var colon = value.indexOf(':');
        final var revision = colon < 0 ? value : value.substring(0, colon);
        return BASE_REVISION.matcher(revision).replaceAll("$1refs/heads/base")
                + (colon < 0 ? "" : value.substring(colon));
    }

    private void format(final String value, final boolean refs) {
        if (value.length() > 512) {
            throw invalid("Format exceeds limit");
        }
        if (!refs
                && Set.of("oneline", "short", "medium", "full", "fuller", "reference", "raw")
                        .contains(value)) {
            return;
        }
        final var text = !refs && (value.startsWith("format:") || value.startsWith("tformat:"))
                ? value.substring(value.indexOf(':') + 1)
                : value;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '%') {
                continue;
            }
            if (++i >= text.length()) {
                throw invalid("Malformed format");
            }
            if (text.charAt(i) == '%') {
                continue;
            }
            if (refs) {
                final var end = text.indexOf(')', i);
                if (text.charAt(i) != '('
                        || end < 0
                        || !Set.of(
                                        "refname",
                                        "refname:short",
                                        "objectname",
                                        "objectname:short",
                                        "objecttype",
                                        "subject",
                                        "authorname",
                                        "authoremail",
                                        "committerdate:iso8601",
                                        "HEAD")
                                .contains(text.substring(i + 1, end))) {
                    throw invalid("Unsupported ref format atom");
                }
                i = end;
            } else {
                final var one = String.valueOf(text.charAt(i));
                if (Set.of("H", "h", "T", "t", "P", "p", "s", "b", "B", "n", "d", "D")
                        .contains(one)) {
                    continue;
                }
                if (i + 1 < text.length()
                        && Set.of("an", "ae", "ad", "aI", "at", "cn", "ce", "cd", "cI", "ct")
                                .contains(text.substring(i, i + 2))) {
                    i++;
                    continue;
                }
                throw invalid("Unsupported history format atom");
            }
        }
    }

    private void revision(final String value, final boolean ranges, final boolean objects) {
        if (value.length() > 512) {
            throw invalid("Revision exceeds limit");
        }
        final var colon = value.indexOf(':');
        if (colon >= 0) {
            if (!objects || colon == 0) {
                throw invalid("Unsupported object expression");
            }
            revision(value.substring(0, colon), false, false);
            path(value.substring(colon + 1), false);
            return;
        }
        final var dots = value.indexOf("..");
        if (dots >= 0) {
            if (!ranges) {
                throw invalid("Revision range is not supported here");
            }
            final var width = value.startsWith("...", dots) ? 3 : 2;
            revision(value.substring(0, dots), false, false);
            revision(value.substring(dots + width), false, false);
            return;
        }
        int end = 0;
        while (end < value.length() && value.charAt(end) != '~' && value.charAt(end) != '^') {
            end++;
        }
        ref(value.substring(0, end));
        int operators = 0;
        int steps = 0;
        while (end < value.length()) {
            final var operator = value.charAt(end++);
            if (operator != '~' && operator != '^') {
                throw invalid("Unsupported ancestry expression");
            }
            final var begin = end;
            while (end < value.length() && value.charAt(end) >= '0' && value.charAt(end) <= '9') {
                end++;
            }
            steps += begin == end ? 1 : number(value.substring(begin, end), 0, 1000);
            if (++operators > 8 || steps > 1000) {
                throw invalid("Ancestry exceeds limit");
            }
        }
    }

    private void ref(final String value) {
        if (Set.of("HEAD", "BASE").contains(value) || SHA.matcher(value).matches()) {
            return;
        }
        if (value.isEmpty()
                || PSEUDO_REFS.contains(value)
                || value.contains("..")
                || value.contains("@")
                || value.startsWith("refs/")
                        && !value.startsWith("refs/heads/")
                        && !value.startsWith("refs/tags/")
                        && !value.startsWith("refs/remotes/")) {
            throw invalid("Unsupported revision");
        }
        for (final var part : value.split("/", -1)) {
            if (!REF_COMPONENT.matcher(part).matches() || part.endsWith(".") || part.endsWith(".lock")) {
                throw invalid("Unsupported ref name");
            }
        }
    }

    private void refPattern(final String value) {
        path(value, true);
        if (value.contains(" ") || value.contains(":")) {
            throw invalid("Unsupported ref listing pattern");
        }
    }

    private void path(final String value, final boolean glob) {
        if (value.isEmpty()
                || value.length() > 512
                || value.startsWith("/")
                || value.startsWith("-")
                || value.contains("\\")
                || value.contains(":")
                || value.contains("@{")
                || value.contains("//")) {
            throw invalid("Expected a relative tree path");
        }
        int wildcards = 0;
        boolean bracket = false;
        for (int i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == ']') {
                if (!glob || ++wildcards > 16) {
                    throw invalid("Path glob exceeds limit");
                }
                if (c == '[') {
                    if (bracket || i + 1 == value.length() || value.charAt(i + 1) == ']') {
                        throw invalid("Malformed path glob");
                    }
                    bracket = true;
                } else if (c == ']') {
                    if (!bracket) {
                        throw invalid("Malformed path glob");
                    }
                    bracket = false;
                } else if (bracket) {
                    throw invalid("Malformed path glob");
                }
            } else if (!(Character.isLetterOrDigit(c) || " /._-".indexOf(c) >= 0)) {
                throw invalid("Unsupported tree path character");
            }
            if (bracket && c == '/') {
                throw invalid("Malformed path glob");
            }
        }
        if (bracket) {
            throw invalid("Malformed path glob");
        }
        for (final var part : value.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) || ".git".equalsIgnoreCase(part)) {
                throw invalid("Unsupported tree path component");
            }
        }
    }

    private int number(final String value, final int min, final int max) {
        if (!CONTEXT_LINE_COUNT.matcher(value).matches()) {
            throw invalid("Expected a bounded decimal integer");
        }
        final var number = Integer.parseInt(value);
        if (number < min || number > max) {
            throw invalid("Numeric value exceeds limit");
        }
        return number;
    }

    private void defaultHead(final List<String> operands) {
        if (operands.isEmpty()) {
            operands.add("HEAD");
        }
    }

    private void requireSize(final List<String> operands, final int min, final int max) {
        if (operands.size() < min || operands.size() > max) {
            throw invalid("Invalid number of revisions");
        }
    }

    private IllegalArgumentException local() {
        return invalid("LOCAL_WORKTREE_REQUIRED: this read requires a local worktree or index");
    }

    private IllegalArgumentException invalid(final String message) {
        return new IllegalArgumentException(message);
    }
}
