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

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders markdown to text with ANSI SGR colors, one completed line at a
 * time: chunks are accumulated by a {@link StreamTextBuffer} and each
 * completed line is passed to {@link #colorizeLine(String)} so fenced-code
 * and inline state is preserved across chunk boundaries. Colorization is
 * pure string output (no terminal dependency); it is disabled by constructing
 * the colorizer with {@code enabled=false} ({@link #colorizeLine(String)}
 * then returns its input unchanged).
 */
public class MarkdownColorizer {
    private static final Pattern ORDERED_LIST_ITEM = Pattern.compile("\\d+\\.\\s.*");

    private static final String RESET = "\u001b[0m";

    private static final String BOLD = "\u001b[1m";
    private static final String UNDERLINE = "\u001b[4m";
    private static final String DIM = "\u001b[2m";

    // palette (fixed, default-theme-ish)
    private static final String THINK = "\u001b[38;5;240m"; // ~ #585858, lower-opacity grey
    private static final String ACCENT = "\u001b[38;5;141m"; // ~ #9d7cd8
    private static final String TEXT = "\u001b[38;5;253m"; // ~ #d9d9d9
    private static final String MUTED = "\u001b[38;5;244m"; // ~ #808080
    private static final String CODE_STR = "\u001b[38;5;215m"; // #e5c07b
    private static final String CODE_COM = "\u001b[38;5;244m"; // #7f848e
    private static final String CODE_NUM = "\u001b[38;5;216m"; // #d19a66
    private static final String CODE_KW = "\u001b[38;5;177m"; // #c678dd
    private static final String CODE_TY = "\u001b[38;5;74m"; // #61afef
    private static final String CODE_TAG = "\u001b[38;5;167m"; // #e06c75
    private static final String CODE_ATTR = "\u001b[38;5;73m"; // #56b6c2

    // CLI has no runtime theme: diff roles mirror Palette.DARK and CodeTheme.ONE_DARK.
    // Keep these separate from the legacy markdown ANSI palette above.
    private static final int DIFF_SUCCESS = 0x46f72f;
    private static final int DIFF_ERROR = 0xf87171;
    private static final int DIFF_PANEL = 0x12141a;
    private static final int DIFF_TEXT = 0xeef0f3;
    private static final String DIFF_ADD_BG = rgb(48, tint(DIFF_SUCCESS, DIFF_PANEL, 0.15));
    private static final String DIFF_DEL_BG = rgb(48, tint(DIFF_ERROR, DIFF_PANEL, 0.15));

    private final boolean enabled;
    private boolean inFence;
    private String fenceLang;

    public MarkdownColorizer(final boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Syntax-colored diff cell using YCA dark roles, with only changed cells tinted. */
    public String diffCell(final String language, final String text, final char change) {
        if (!enabled) {
            return text;
        }
        final var background =
                switch (change) {
                    case '+' -> DIFF_ADD_BG;
                    case '-' -> DIFF_DEL_BG;
                    default -> "";
                };
        final var marker = (change == '+' || change == '-') && text.startsWith(String.valueOf(change));
        final var highlighted = (marker ? rgb(38, change == '+' ? DIFF_SUCCESS : DIFF_ERROR) + change + RESET : "")
                + highlightCode(language, marker ? text.substring(1) : text)
                        .replace(CODE_STR, rgb(38, 0x98c379))
                        .replace(CODE_COM, rgb(38, 0x7f848e))
                        .replace(CODE_NUM, rgb(38, 0xd19a66))
                        .replace(CODE_KW, rgb(38, 0xc678dd))
                        .replace(CODE_TY, rgb(38, 0x61afef))
                        .replace(CODE_TAG, rgb(38, 0xe06c75))
                        .replace(CODE_ATTR, rgb(38, 0x56b6c2))
                        .replace(TEXT, rgb(38, DIFF_TEXT));
        return background.isEmpty() ? highlighted : background + highlighted.replace(RESET, RESET + background) + RESET;
    }

    private static String rgb(final int role, final int color) {
        return "\u001b[" + role + ";2;" + (color >> 16 & 255) + ";" + (color >> 8 & 255) + ";" + (color & 255) + "m";
    }

    private static int tint(final int foreground, final int background, final double opacity) {
        var result = 0;
        for (var shift = 0; shift <= 16; shift += 8) {
            final var fg = foreground >> shift & 255;
            final var bg = background >> shift & 255;
            result |= (int) Math.round(bg + (fg - bg) * opacity) << shift;
        }
        return result;
    }

    public static boolean autoEnabled() {
        if (System.getenv().containsKey("NO_COLOR")) {
            return false;
        }
        final var term = System.getenv("TERM");
        if (term != null && term.equalsIgnoreCase("dumb")) {
            return false;
        }
        return System.console() != null;
    }

    /**
     * Colorizes a single completed markdown line, tracking fenced-code state.
     */
    public String colorizeLine(final String line) {
        if (!enabled) {
            return line;
        }
        final var trimmed = line.stripLeading();
        if (trimmed.startsWith("```")) {
            if (inFence) {
                inFence = false;
                fenceLang = null;
            } else {
                inFence = true;
                fenceLang = trimmed.substring(3).strip();
            }
            return line;
        }
        if (inFence) {
            return highlightCode(fenceLang, line);
        }
        if (trimmed.startsWith("#")) {
            return heading(line);
        }
        if (trimmed.startsWith(">")) {
            return blockquote(line);
        }
        if (trimmed.startsWith("- ")
                || trimmed.startsWith("* ")
                || trimmed.startsWith("+ ")
                || ORDERED_LIST_ITEM.matcher(trimmed).matches()) {
            return bullet(line);
        }
        return inline(line);
    }

    private String heading(final String line) {
        return BOLD + ACCENT + line + RESET;
    }

    private String blockquote(final String line) {
        return MUTED + "│ " + inline(line.length() < 2 ? "" : line.substring(2)) + RESET;
    }

    private String bullet(final String line) {
        final var marker = line.indexOf(' ') < 0 ? line : line.substring(0, line.indexOf(' ') + 1);
        return marker + inline(line.substring(marker.length())) + RESET;
    }

    private String inline(final String line) {
        final var sb = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            final var c = line.charAt(i);
            if (c == '`') {
                final var end = line.indexOf('`', i + 1);
                if (end < 0) {
                    sb.append(c);
                    i++;
                } else {
                    // inline code: colored + bold like the TUI (the weight does the emphasis),
                    // backtick delimiters are dropped so code and prose share the same baseline
                    sb.append(BOLD).append(CODE_STR).append(line, i + 1, end).append(RESET);
                    i = end + 1;
                }
            } else if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                final var end = line.indexOf("**", i + 2);
                final var boldText = end < 0 ? line.substring(i + 2) : line.substring(i + 2, end);
                sb.append(BOLD).append(boldText).append(RESET);
                i = end < 0 ? line.length() : end + 2;
            } else if (c == '*' || c == '_') {
                final var end = line.indexOf(c, i + 1);
                final var em = end < 0 ? String.valueOf(c) : line.substring(i + 1, end);
                sb.append(UNDERLINE).append(em).append(RESET);
                i = end < 0 ? i + 1 : end + 1;
            } else if (c == '[') {
                final var close = line.indexOf(']', i + 1);
                final var paren = close > 0 ? line.indexOf('(', close) : -1;
                final var end = paren > 0 ? line.indexOf(')', paren) : -1;
                if (close > 0 && paren == close + 1 && end > 0) {
                    sb.append(plain(line, i, close))
                            .append(MUTED)
                            .append(line, close, paren + 1)
                            .append(line, paren + 1, end + 1)
                            .append(RESET);
                    i = end + 1;
                } else {
                    sb.append(c);
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private String plain(final String line, final int from, final int to) {
        return TEXT + line.substring(from, to) + RESET;
    }

    private static String highlightCode(final String lang, final String line) {
        final var h = pick(lang);
        final var out = new StringBuilder();
        final var m = h.pattern().matcher(line);
        var last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.append(TEXT).append(line, last, m.start()).append(RESET);
            }
            out.append(codeStyle(m)).append(m.group()).append(RESET);
            last = m.end();
        }
        if (last < line.length()) {
            out.append(TEXT).append(line.substring(last)).append(RESET);
        }
        return out.length() == 0 ? TEXT + line + RESET : out.toString();
    }

    private static String codeStyle(final Matcher m) {
        if (m.group("str") != null) {
            return CODE_STR;
        }
        if (m.group("com") != null) {
            return CODE_COM;
        }
        if (m.group("num") != null) {
            return CODE_NUM;
        }
        if (m.group("tag") != null) {
            return CODE_TAG;
        }
        if (m.group("attr") != null) {
            return CODE_ATTR;
        }
        if (m.group("kw") != null) {
            return CODE_KW;
        }
        return CODE_TY;
    }

    private record HL(Pattern pattern) {}

    private static HL language(final List<String> keywords, final List<String> types) {
        final var alt = "(" + String.join("|", keywords) + ")";
        final var altT = "(" + String.join("|", types) + ")";
        final var regex = "(?<str>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`)"
                + "|(?<com>//[^\\n]*|/\\*.*?\\*/|#[^\\n]*|--[^\\n]*|<!--.*?-->|\\{\\{-?[^\\n]*?}})"
                + "|(?<num>\\b\\d+(?:\\.\\d+)?\\b)"
                + "|(?<tag><\\/?[A-Za-z][A-Za-z0-9:-]*|/?>)"
                + "|(?<attr>[A-Za-z_:][A-Za-z0-9_:.-]*=)"
                + "|(?<kw>" + alt + ")"
                + "|(?<ty>" + altT + ")";
        return new HL(Pattern.compile(regex, Pattern.MULTILINE));
    }

    private static final HL JAVA = language(
            List.of(
                    "public",
                    "private",
                    "protected",
                    "static",
                    "final",
                    "class",
                    "interface",
                    "enum",
                    "record",
                    "void",
                    "return",
                    "if",
                    "else",
                    "for",
                    "while",
                    "do",
                    "switch",
                    "case",
                    "default",
                    "break",
                    "continue",
                    "new",
                    "try",
                    "catch",
                    "finally",
                    "throw",
                    "throws",
                    "extends",
                    "implements",
                    "import",
                    "package",
                    "this",
                    "super",
                    "null",
                    "true",
                    "false",
                    "var",
                    "instanceof",
                    "synchronized",
                    "abstract",
                    "yield",
                    "sealed",
                    "permits",
                    "assert",
                    "native",
                    "transient",
                    "volatile"),
            List.of(
                    "String",
                    "int",
                    "long",
                    "byte",
                    "short",
                    "float",
                    "double",
                    "boolean",
                    "char",
                    "Object",
                    "Integer",
                    "Long",
                    "Boolean",
                    "Double",
                    "Float",
                    "List",
                    "Map",
                    "Set",
                    "Collection",
                    "Optional"));
    private static final HL TS = language(
            List.of(
                    "const",
                    "let",
                    "var",
                    "function",
                    "return",
                    "if",
                    "else",
                    "for",
                    "while",
                    "do",
                    "switch",
                    "case",
                    "default",
                    "break",
                    "continue",
                    "new",
                    "try",
                    "catch",
                    "finally",
                    "throw",
                    "throws",
                    "extends",
                    "implements",
                    "import",
                    "export",
                    "from",
                    "async",
                    "await",
                    "yield",
                    "class",
                    "interface",
                    "enum",
                    "type",
                    "null",
                    "true",
                    "false",
                    "this",
                    "super",
                    "of",
                    "instanceof",
                    "typeof",
                    "delete",
                    "in",
                    "void",
                    "readonly",
                    "static",
                    "public",
                    "private",
                    "protected"),
            List.of(
                    "string",
                    "number",
                    "boolean",
                    "any",
                    "unknown",
                    "never",
                    "void",
                    "object",
                    "Promise",
                    "Array",
                    "Function",
                    "Record",
                    "Date"));
    private static final HL PYTHON = language(
            List.of(
                    "def",
                    "class",
                    "return",
                    "if",
                    "elif",
                    "else",
                    "for",
                    "while",
                    "try",
                    "except",
                    "finally",
                    "with",
                    "as",
                    "import",
                    "from",
                    "pass",
                    "break",
                    "continue",
                    "lambda",
                    "yield",
                    "global",
                    "nonlocal",
                    "raise",
                    "assert",
                    "del",
                    "and",
                    "or",
                    "not",
                    "in",
                    "is",
                    "None",
                    "True",
                    "False"),
            List.of(
                    "str",
                    "int",
                    "float",
                    "bool",
                    "list",
                    "dict",
                    "tuple",
                    "set",
                    "bytes",
                    "object",
                    "Exception",
                    "Any",
                    "Optional",
                    "Type"));
    private static final HL SHELL = language(
            List.of(
                    "if",
                    "then",
                    "else",
                    "elif",
                    "fi",
                    "for",
                    "while",
                    "do",
                    "done",
                    "case",
                    "esac",
                    "function",
                    "in",
                    "return",
                    "exit",
                    "export",
                    "local",
                    "readonly",
                    "echo",
                    "printf",
                    "cd",
                    "ls",
                    "mkdir",
                    "rm",
                    "cp",
                    "mv",
                    "touch",
                    "cat",
                    "grep",
                    "sed",
                    "awk",
                    "find",
                    "sudo",
                    "git",
                    "node",
                    "npm",
                    "mvn",
                    "java"),
            List.of());
    private static final HL SQL = language(
            List.of(
                    "select",
                    "from",
                    "where",
                    "insert",
                    "into",
                    "values",
                    "update",
                    "set",
                    "delete",
                    "create",
                    "table",
                    "drop",
                    "alter",
                    "index",
                    "join",
                    "left",
                    "right",
                    "inner",
                    "outer",
                    "on",
                    "group",
                    "by",
                    "order",
                    "having",
                    "limit",
                    "offset",
                    "and",
                    "or",
                    "not",
                    "in",
                    "like",
                    "between",
                    "is",
                    "null",
                    "as",
                    "distinct",
                    "union",
                    "all",
                    "primary",
                    "key",
                    "foreign",
                    "references",
                    "constraint",
                    "default",
                    "grant",
                    "revoke"),
            List.of(
                    "int",
                    "integer",
                    "bigint",
                    "varchar",
                    "text",
                    "char",
                    "date",
                    "datetime",
                    "timestamp",
                    "boolean",
                    "decimal",
                    "numeric",
                    "float",
                    "double",
                    "serial",
                    "uuid"));
    private static final HL GENERIC = language(
            List.of(
                    "return",
                    "if",
                    "else",
                    "for",
                    "while",
                    "switch",
                    "case",
                    "break",
                    "continue",
                    "class",
                    "interface",
                    "enum",
                    "record",
                    "function",
                    "def",
                    "import",
                    "export",
                    "var",
                    "let",
                    "const",
                    "new",
                    "try",
                    "catch",
                    "finally",
                    "throw",
                    "this",
                    "super",
                    "null",
                    "true",
                    "false",
                    "async",
                    "await",
                    "void"),
            List.of());

    private static HL pick(final String lang) {
        if (lang == null || lang.isBlank()) {
            return GENERIC;
        }
        final var l = lang.toLowerCase(Locale.ROOT);
        if (l.contains("java")) {
            return JAVA;
        }
        if (l.contains("shell") || l.contains("bash") || l.equals("sh") || l.equals("zsh")) {
            return SHELL;
        }
        if (l.contains("python") || l.contains("py")) {
            return PYTHON;
        }
        if (l.contains("sql")) {
            return SQL;
        }
        if (l.contains("javascript")
                || l.contains("typescript")
                || l.equals("js")
                || l.equals("ts")
                || l.equals("json")) {
            return TS;
        }
        return GENERIC;
    }

    /**
     * Dims a chunk of text (used for thinking and tool output on stderr).
     */
    public static String dim(final String text) {
        return DIM + text + RESET;
    }

    /**
     * Renders reasoning/thinking with a lower-opacity grey so the stream is
     * visible but clearly subordinate to the final answer.
     */
    public static String thinking(final String text) {
        return THINK + text + RESET;
    }

    /**
     * Dims a whole rendered markdown chunk (e.g. a replayed tool region), re-applying the
     * dim after every nested reset so inner colors cannot cancel the outer effect.
     */
    public static String dimChunk(final String text) {
        return DIM + text.replace(RESET, RESET + DIM) + RESET;
    }

    /**
     * Renders a whole rendered markdown chunk (e.g. a replayed thought region) in the
     * lower-opacity thinking grey, re-applying it after every nested reset.
     */
    public static String thinkChunk(final String text) {
        return THINK + text.replace(RESET, RESET + THINK) + RESET;
    }

    public static String status(final String text) {
        return MUTED + text + RESET;
    }

    /**
     * Renders a neutral informational notice in the full-brightness body grey (distinct from the
     * dimmer {@link #status(Object)}), used for durable user-facing banners such as the session id
     * that a user may want to copy/resume.
     */
    public static String info(final String text) {
        return TEXT + text + RESET;
    }

    public static String error(final String text) {
        return "\u001b[38;5;203m" + text + RESET;
    }

    public static String warning(final String text) {
        return "\u001b[38;5;215m" + text + RESET;
    }

    public static String success(final String text) {
        return "\u001b[38;5;114m" + text + RESET;
    }

    public static String diffAdd(final String text) {
        return "\u001b[38;5;150m" + text + RESET;
    }

    public static String diffDel(final String text) {
        return "\u001b[38;5;203m" + text + RESET;
    }

    public static String accent(final String text) {
        return ACCENT + text + RESET;
    }

    public static String bold(final String text) {
        return BOLD + text + RESET;
    }
}
