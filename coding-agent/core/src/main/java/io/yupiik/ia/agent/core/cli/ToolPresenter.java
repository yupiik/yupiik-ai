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

import io.yupiik.fusion.json.JsonMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns raw tool-call data into short, human-readable descriptions for the
 * CLI. Parsing relies on an injected {@link JsonMapper}; methods return plain
 * text (no ANSI styling - callers add color), so they degrade gracefully for
 * unknown or MCP tools with pretty-printed JSON request details.
 */
public final class ToolPresenter {
    private static final int RAW_TRUNCATE = 120;
    private static final Pattern BASH_EXIT_STATUS = Pattern.compile("(?:^|\\n)Exit code: (-?[0-9]+)\\s*\\z");
    private static final Set<String> BUILTINS = Set.of(
            "bash",
            "read",
            "write_file",
            "edit_file",
            "apply_patch",
            "grep",
            "glob",
            "write_todo",
            "memory_write",
            "ask_user",
            "webfetch",
            "websearch",
            "skill",
            "spawn_subagent",
            "check_subagents",
            "list_subagent_options",
            "maven_knowledge_content",
            "maven_repository_knowledge",
            "compress",
            "code_resolve",
            "code_trace_calls",
            "code_check_graph",
            "read_symbol",
            "code_find_code");
    private static final Pattern ENV_ASSIGNMENT =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=(\"[^\"]*\"|'[^']*'|\\S*)\\s+");
    private static final Set<String> NAVIGATION =
            Set.of("cd", "pushd", "popd", "export", "source", "unset", "set", "alias");

    private final JsonMapper mapper;
    private final AskUserAnswersFormatter askUserAnswersFormatter;

    public ToolPresenter(final JsonMapper mapper) {
        this.mapper = mapper;
        this.askUserAnswersFormatter = new AskUserAnswersFormatter(mapper);
    }

    /**
     * One-line, human summary of a tool invocation, eg. {@code bash(mvn -q test)}
     * or {@code edit_file(src/main/java/Foo.java)}. Unknown tools fall back to a
     * truncated rendering of the raw arguments.
     */
    public String summarize(final String name, final String argsJson) {
        final var args = parse(argsJson);
        final var detail =
                switch (name == null ? "" : name) {
                    case "bash" -> compact(str(args, "command")) + suffixWorkdir(args);
                    case "read" -> compact(str(args, "path")) + suffixRead(args);
                    case "write_file", "edit_file" -> compact(str(args, "path"));
                    case "apply_patch" -> "requested patch";
                    case "grep" -> shortString(str(args, "pattern")) + " in " + compact(str(args, "path"));
                    case "glob" -> compact(str(args, "pattern"));
                    case "write_todo" ->
                        args.get("todos") instanceof List<?> todos ? todos.size() + " items" : "0 items";
                    case "memory_write" -> compact(str(args, "fact"));
                    case "ask_user" ->
                        args.get("questions") instanceof List<?> questions
                                ? questions.size() + " questions"
                                : "0 questions";
                    case "webfetch" -> compact(str(args, "url"));
                    case "websearch", "code_find_code", "list_subagent_options" -> compact(str(args, "query"));
                    case "skill", "spawn_subagent" -> compact(str(args, "name"));
                    case "code_resolve", "code_trace_calls", "read_symbol" -> compact(str(args, "symbol"));
                    case "code_check_graph", "check_subagents", "compress" -> compact(str(args, "action"));
                    case "maven_knowledge_content" -> compact(str(args, "gav"));
                    case "maven_repository_knowledge" -> compact(str(args, "gavs"));
                    default -> truncate(argsJson);
                };
        return name == null || name.isBlank() ? "(unknown tool)" : name + "(" + detail + ")";
    }

    /**
     * Faithful requested arguments, not a claim about execution or filesystem state.
     * Only supplied fields are rendered; nested objects/lists keep their structure and
     * string values keep whitespace and newlines. The limit bounds detail lines, with
     * an explicit omission marker. This also works for external tools and future fields.
     */
    public List<String> request(final String name, final String argsJson, final int maxLines) {
        if (argsJson == null || argsJson.isBlank()) {
            return List.of();
        }
        final var lines = new ArrayList<String>();
        try {
            final var value = mapper.fromString(Object.class, argsJson);
            if (name != null && BUILTINS.contains(name)) {
                if ("write_file".equals(name)) {
                    lines.add("requested file content (not a filesystem diff):");
                }
                renderValue(lines, "", "", value);
            } else {
                lines.addAll(List.of(prettyJson(argsJson).split("\n", -1)));
            }
        } catch (final RuntimeException invalid) {
            lines.add("requested arguments (raw):");
            lines.addAll(List.of(argsJson.split("\n", -1)));
        }
        return bounded(lines, maxLines);
    }

    private static String prettyJson(final String json) {
        final var out = new StringBuilder();
        var depth = 0;
        var quoted = false;
        var escaped = false;
        for (var i = 0; i < json.length(); i++) {
            final var c = json.charAt(i);
            if (quoted) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
                out.append(c);
            } else if (c == '{' || c == '[') {
                out.append(c).append('\n').append("  ".repeat(++depth));
            } else if (c == '}' || c == ']') {
                out.append('\n').append("  ".repeat(--depth)).append(c);
            } else if (c == ',') {
                out.append(c).append('\n').append("  ".repeat(depth));
            } else if (c == ':') {
                out.append(": ");
            } else if (!Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private void renderValue(final List<String> lines, final String indent, final String label, final Object value) {
        if (value instanceof Map<?, ?> map) {
            if (!label.isEmpty()) {
                lines.add(indent + label + ":" + (map.isEmpty() ? " {}" : ""));
            }
            final var child = label.isEmpty() ? indent : indent + "  ";
            map.forEach((key, item) -> renderValue(lines, child, String.valueOf(key), item));
        } else if (value instanceof List<?> list) {
            lines.add(indent + label + ":" + (list.isEmpty() ? " []" : ""));
            for (var i = 0; i < list.size(); i++) {
                renderValue(lines, indent + "  ", "[" + i + "]", list.get(i));
            }
        } else if (value instanceof String text && (text.contains("\n") || text.isBlank())) {
            lines.add(indent + label + ":");
            for (final var line : text.split("\n", -1)) {
                lines.add(indent + "  " + line);
            }
        } else {
            lines.add(indent + (label.isEmpty() ? "" : label + ": ") + String.valueOf(value));
        }
    }

    private static List<String> bounded(final List<String> lines, final int maxLines) {
        final var limit = Math.max(0, maxLines);
        if (lines.size() <= limit) {
            return List.copyOf(lines);
        }
        final var out = new ArrayList<>(lines.subList(0, limit));
        out.add("... " + (lines.size() - limit) + " more lines");
        return out;
    }

    /**
     * Produces a bounded requested replacement/patch preview, not a verified
     * filesystem diff. Whitespace and patch scope are retained; callers apply
     * color to {@code +}/{@code -} lines. Writes have no known old content and
     * therefore use {@link #request(String, String, int)} instead.
     */
    public List<String> diff(final String name, final String argsJson, final int maxLines) {
        final var args = parse(argsJson);
        if (name == null) {
            return List.of();
        }
        return switch (name) {
            case "edit_file" -> editDiff(args, maxLines);
            case "write_file" -> List.of(); // No old file is available: content is a request, not a diff.
            case "apply_patch" -> patchDiff(args, maxLines);
            default -> List.of();
        };
    }

    /**
     * One-line digest of a finished tool: first meaningful line on failure, the
     * last non-empty output line otherwise (covers {@code BUILD SUCCESS}, git,
     * maven and similar tail-significant commands).
     */
    public String result(final String name, final String output, final boolean success) {
        if (output == null || output.isBlank()) {
            return success ? "done" : "failed";
        }
        // ask_user answers read better as key: value pairs than a truncated JSON line
        if ("ask_user".equals(name)) {
            final var answers = askUserAnswersFormatter.answers(output);
            if (answers != null) {
                final var rendered = new StringBuilder();
                for (final var answer : answers) {
                    if (rendered.length() > 0) {
                        rendered.append('\n');
                    }
                    rendered.append(answer.key()).append(": ").append(answer.value());
                }
                return rendered.toString();
            }
        }
        if (success) {
            return truncate(lastMeaningfulLine(output));
        }
        return truncate(firstMeaningfulLine(output));
    }

    /** Execution status is separate from tool-call success; only BashTool's final trailer is recognized. */
    public String executionDiagnostic(final String name, final String output) {
        if (!"bash".equals(name) || output == null) {
            return "";
        }
        final var matcher = BASH_EXIT_STATUS.matcher(output);
        if (!matcher.find() || "0".equals(matcher.group(1))) {
            return "";
        }
        return "execution exit code: " + matcher.group(1);
    }

    /**
     * Extracts the most significant command of a compound shell command line:
     * environment assignments are dropped and navigation segments ({@code cd},
     * {@code export}, ...) are skipped, so {@code FOO=1 cd /tmp && mvn -q test}
     * yields {@code mvn -q test}. Shared by the CLI summary and the TUI
     * condensed tool lines. Line continuations and newlines are flattened to
     * spaces; blank-safe.
     */
    public static String significantCommand(final String raw) {
        if (raw == null) {
            return "";
        }
        final var flat = raw.replace("\\\r\n", " ")
                .replace("\\\n", " ")
                .replace("\r", " ")
                .replace("\n", " ")
                .strip();
        if (flat.isBlank()) {
            return "";
        }
        for (final var segment : splitSegments(flat)) {
            final var stripped = stripNavigation(stripEnvAssignments(segment));
            if (!stripped.isBlank()) {
                return stripped;
            }
        }
        return flat;
    }

    /** Splits on {@code &&}, {@code ||} and {@code ;}, ignoring separators inside quotes. */
    private static List<String> splitSegments(final String command) {
        final var segments = new ArrayList<String>();
        final var current = new StringBuilder();
        var quote = '\0';
        for (var i = 0; i < command.length(); i++) {
            final var c = command.charAt(i);
            if (quote != '\0') {
                if (c == quote) {
                    quote = '\0';
                }
                current.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == ';' || (c == '&' && command.startsWith("&&", i)) || (c == '|' && command.startsWith("||", i))) {
                segments.add(current.toString());
                current.setLength(0);
                if (c != ';') {
                    i++;
                }
                continue;
            }
            current.append(c);
        }
        segments.add(current.toString());
        return segments;
    }

    private static String stripEnvAssignments(final String segment) {
        var result = segment.strip();
        var matcher = ENV_ASSIGNMENT.matcher(result);
        while (matcher.lookingAt()) {
            result = result.substring(matcher.end()).strip();
            matcher = ENV_ASSIGNMENT.matcher(result);
        }
        return result;
    }

    private static String stripNavigation(final String segment) {
        final var t = segment.strip();
        final var space = t.indexOf(' ');
        final var first = space < 0 ? t : t.substring(0, space);
        return NAVIGATION.contains(first) ? "" : t;
    }

    private Map<String, Object> parse(final String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Map.of();
        }
        try {
            final var parsed = mapper.fromString(Object.class, argsJson);
            if (parsed instanceof Map<?, ?> map) {
                final var out = new LinkedHashMap<String, Object>();
                out.putAll(map.entrySet().stream()
                        .collect(
                                LinkedHashMap<String, Object>::new,
                                (collected, entry) -> collected.put(String.valueOf(entry.getKey()), entry.getValue()),
                                Map::putAll));
                return out;
            }
        } catch (final RuntimeException e) {
            // unparseable args; fall back to empty so summaries degrade gracefully
        }
        return Map.of();
    }

    private String suffixRead(final Map<String, Object> args) {
        final var offset = str(args, "offset");
        final var limit = str(args, "limit");
        final var sb = new StringBuilder();
        if (offset != null && !offset.isBlank()) {
            sb.append(" +").append(offset);
        }
        if (limit != null && !limit.isBlank()) {
            sb.append(": ").append(limit).append(" lines");
        }
        return sb.toString();
    }

    private String suffixWorkdir(final Map<String, Object> args) {
        final var workdir = str(args, "workdir");
        if (workdir == null || workdir.isBlank() || workdir.equals(".")) {
            return "";
        }
        return " · " + compact(workdir);
    }

    private static List<String> editDiff(final Map<String, Object> args, final int maxLines) {
        final var oldString = strRaw(args, "oldString");
        final var newString = strRaw(args, "newString");
        if (oldString == null && newString == null) {
            return List.of();
        }
        final var out = new ArrayList<String>();
        out.add("requested replacement: " + strRaw(args, "path")
                + (Boolean.TRUE.equals(args.get("replaceAll")) ? " (all matches)" : " (single match)"));
        if (oldString != null && !oldString.isEmpty()) {
            for (final var line : oldString.split("\n", -1)) {
                out.add("- " + line);
            }
        }
        if (newString != null && !newString.isEmpty()) {
            for (final var line : newString.split("\n", -1)) {
                out.add("+ " + line);
            }
        }
        return bounded(out, maxLines);
    }

    private static List<String> patchDiff(final Map<String, Object> args, final int maxLines) {
        final var patch = strRaw(args, "patch");
        if (patch == null) {
            return List.of();
        }
        final var out = new ArrayList<String>();
        out.add("requested patch:");
        // Both unified and OpenCode patches carry their own paths, scope and hunk
        // markers. Keep them verbatim, including move/delete and EOF directives.
        out.addAll(List.of(patch.split("\n", -1)));
        return bounded(out, maxLines);
    }

    private static String str(final Map<String, Object> args, final String key) {
        final var v = args.get(key);
        return v == null ? null : String.valueOf(v).replace('\n', ' ').strip();
    }

    private static String strRaw(final Map<String, Object> args, final String key) {
        final var v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String truncate(final String s) {
        if (s == null) {
            return "";
        }
        final var t = s.strip();
        if (t.length() <= RAW_TRUNCATE) {
            return t;
        }
        return t.substring(0, RAW_TRUNCATE) + "...";
    }

    private static String compact(final String s) {
        if (s == null || s.isBlank()) {
            return "?";
        }
        final var t = s.strip();
        if (t.length() <= 60) {
            return t;
        }
        return t.substring(0, 60) + "...";
    }

    private static String shortString(final String s) {
        if (s == null) {
            return "?";
        }
        final var t = s.strip();
        if (t.length() <= 24) {
            return t;
        }
        return t.substring(0, 24) + "...";
    }

    private static String lastMeaningfulLine(final String output) {
        final var lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            final var l = lines[i].strip();
            if (!l.isBlank()) {
                return l;
            }
        }
        return output.strip();
    }

    private static String firstMeaningfulLine(final String output) {
        for (final var line : output.split("\n")) {
            final var l = line.strip();
            if (!l.isBlank()) {
                return l;
            }
        }
        return output.strip();
    }
}
