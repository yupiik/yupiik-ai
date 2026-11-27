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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guesses the language of a tool output so the overlay/cards can syntax color it
 * <em>without</em> reformatting it: a wrong guess costs a wrong color, never a
 * reflowed layout. This is the whole point - the document format detection of
 * {@link ToolOutputFormat} reinterprets the text (tables, quotes, list markers),
 * which is why it stays strict while plain output is only colored here.
 *
 * <p>Signals, strongest first:
 * <ol>
 *     <li>the command that produced the output (<em>python</em>, <em>node</em>,
 *     <em>psql</em>...): free, it is already displayed in the card header, and a
 *     shell running another command (<code>sh -c "python x.py"</code>) is followed
 *     recursively;</li>
 *     <li>the shebang of a printed script;</li>
 *     <li>runtime fingerprints (python traceback, SQL statements);</li>
 *     <li>the extension of the files the output is about, when unambiguous;</li>
 *     <li>keyword density, when one language clearly dominates;</li>
 *     <li>nothing convincing -&gt; {@code null}, i.e. plain text.</li>
 * </ol>
 *
 * <p>Pure and terminal-free (no JSON, no palette, no frame) so it is unit testable
 * without a display. The returned ids are understood by
 * {@link SyntaxHighlighter#highlightLines(String, String)}, which already has a highlighter for each.
 */
public final class ToolOutputLanguage {
    /** Programs whose output <em>is</em> in that language; a shell/cat/grep is absent on purpose. */
    private static final Map<String, String> PROGRAMS = Map.ofEntries(
            Map.entry("python", "python"),
            Map.entry("python2", "python"),
            Map.entry("python3", "python"),
            Map.entry("pypy", "python"),
            Map.entry("ipython", "python"),
            Map.entry("pip", "python"),
            Map.entry("pip3", "python"),
            Map.entry("pipenv", "python"),
            Map.entry("poetry", "python"),
            Map.entry("pytest", "python"),
            Map.entry("py.test", "python"),
            Map.entry("tox", "python"),
            Map.entry("uv", "python"),
            Map.entry("conda", "python"),
            Map.entry("node", "javascript"),
            Map.entry("nodejs", "javascript"),
            Map.entry("npm", "javascript"),
            Map.entry("npx", "javascript"),
            Map.entry("yarn", "javascript"),
            Map.entry("pnpm", "javascript"),
            Map.entry("bun", "javascript"),
            Map.entry("deno", "javascript"),
            Map.entry("tsc", "typescript"),
            Map.entry("psql", "sql"),
            Map.entry("mysql", "sql"),
            Map.entry("mariadb", "sql"),
            Map.entry("sqlite3", "sql"),
            Map.entry("sqlplus", "sql"),
            Map.entry("javac", "java"),
            Map.entry("kotlinc", "java"),
            Map.entry("groovy", "java"));

    private static final Map<String, List<String>> KEYWORDS = Map.of(
            "python",
            List.of("def ", "class ", "import ", "self.", "print(", "elif ", "__init__", "lambda ", "None", "True"),
            "javascript",
            List.of("=>", "function ", "const ", "let ", "var ", "console.log(", "=== ", "await ", "export "),
            "sql",
            List.of("SELECT ", "FROM ", "WHERE ", "JOIN ", "INSERT INTO ", "GROUP BY ", "ORDER BY ", "CREATE TABLE "),
            "java",
            List.of(
                    "public ",
                    "private ",
                    "protected ",
                    "static ",
                    "class ",
                    "interface ",
                    "enum ",
                    "record ",
                    "void ",
                    "return ",
                    "extends ",
                    "implements ",
                    "package ",
                    "import ",
                    "@Override",
                    "throws ",
                    "System.out",
                    "String ",
                    "new "));
    private static final Set<String> SHELL_PROGRAMS = Set.of("sh", "bash", "zsh", "dash", "ksh");
    /** Launchers skipped when looking for the program actually doing the work. */
    private static final Set<String> WRAPPERS =
            Set.of("sudo", "doas", "env", "time", "nohup", "nice", "ionice", "stdbuf", "exec", "command", "builtin");

    private static final Set<String> EXEC_FLAGS = Set.of("-c", "-lc", "-ic", "-lic");
    private static final Pattern COMMAND_SEGMENT = Pattern.compile("(?:&&|\\|\\||[;|\\n])");
    /** Languages a referenced file extension may contribute ({@link SyntaxHighlighter} has a highlighter). */
    private static final Set<String> PATH_LANGUAGES = Set.of(
            "java",
            "python",
            "shell",
            "sql",
            "javascript",
            "typescript",
            "json",
            "yaml",
            "xml",
            "markdown",
            "asciidoc");

    private static final Pattern COMMAND_TOKEN = Pattern.compile("[\\s;&|()]+");
    private static final Pattern ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=.*$");
    private static final Pattern QUOTES = Pattern.compile("^['\"]+|['\"]+$");
    private static final Pattern SHEBANG = Pattern.compile("^#!\\s*(?:/[A-Za-z0-9_./-]*/env\\s+)?(?:\\S*/)?(\\S+)");
    private static final Pattern PYTHON_TRACEBACK =
            Pattern.compile("^Traceback \\(most recent call last\\):", Pattern.MULTILINE);
    private static final Pattern PYTHON_FRAME =
            Pattern.compile("^\\s*File \"[^\"]*\\.py[w]?\", line \\d+", Pattern.MULTILINE);
    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "^(?:select|insert\\s+into|update|delete\\s+from|create\\s+(?:table|index|view)|alter\\s+table|with)\\s+[\\w(\"']",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    /** How much of the output is inspected: every signal lives in its header. */
    private static final int MAX_INSPECTED = 8192;
    /** Minimum keyword hits before a language wins, and how much it must beat the runner-up. */
    private static final int MIN_KEYWORD_HITS = 3;

    private static final int KEYWORD_RATIO = 2;

    private ToolOutputLanguage() {
        // no-op
    }

    /**
     * @param output  the raw tool output (may be null)
     * @param command the command that produced it - a plain command line
     *                ({@code ToolCard.keyCommand()} or the tool arguments
     *                {@code command} property), may be null
     * @return a {@link SyntaxHighlighter#highlightLines(String, String)}-known language id, or {@code null}
     *         when nothing is convincing enough to color
     */
    public static String guess(final String output, final String command) {
        if (output == null || output.isBlank()) {
            return null;
        }
        final var head = output.length() > MAX_INSPECTED ? output.substring(0, MAX_INSPECTED) : output;
        final var fromCommand = fromCommand(command, 0);
        if (fromCommand != null) {
            return fromCommand;
        }
        final var fromShebang = fromShebang(head);
        if (fromShebang != null) {
            return fromShebang;
        }
        final var fromRuntime = fromRuntime(head);
        if (fromRuntime != null) {
            return fromRuntime;
        }
        final var fromPaths = fromPaths(head);
        if (fromPaths != null) {
            return fromPaths;
        }
        return fromKeywords(head);
    }

    /**
     * Language of the program the command line actually runs, following {@code sh -c "..."}.
     * The line is read segment by segment (split on {@code &&}, {@code ;}, pipes) so
     * {@code cd /tmp && python3 y.py} resolves to the program doing the work while
     * {@code ls -la} resolves to nothing: what stays unproven is left plain.
     */
    private static String fromCommand(final String command, final int depth) {
        if (command == null || command.isBlank() || depth > 2) {
            return null;
        }
        for (final var rawSegment : COMMAND_SEGMENT.split(command)) {
            final var segment = rawSegment.strip();
            if (segment.isEmpty()) {
                continue;
            }
            final var tokens = tokenize(segment);
            // a short command that names a known source file (eg. the read/cat path):
            // the output is that file's content, so its extension decides the language.
            // Multi-token commands (grep -Hn foo Foo.java) keep their old behavior: the
            // interpreter loop below only fires for known programs, file refs stay plain.
            if (tokens.size() <= 2) {
                for (final var token : tokens) {
                    final var byPath = SyntaxHighlighter.guessLanguage(token);
                    if (byPath != null) {
                        return byPath;
                    }
                }
            }
            for (var index = 0; index < tokens.size(); index++) {
                final var token = tokens.get(index);
                if (ASSIGNMENT.matcher(token).matches() || WRAPPERS.contains(token)) {
                    continue;
                }
                final var program = baseName(token);
                if (SHELL_PROGRAMS.contains(program)) {
                    final var inner = innerCommand(tokens, index);
                    if (inner != null) {
                        return fromCommand(inner, depth + 1);
                    }
                    break; // a bare shell invocation: the shebang/path signals know better
                }
                final var language = PROGRAMS.get(program);
                if (language != null) {
                    return language;
                }
                break; // a plain command: this segment says nothing about the language
            }
        }
        return null;
    }

    /** The command a shell interpreter is asked to run, {@code null} when there is none. */
    private static String innerCommand(final List<String> tokens, final int from) {
        for (var flag = from + 1; flag < tokens.size(); flag++) {
            if (EXEC_FLAGS.contains(tokens.get(flag))) {
                final var inner = String.join(" ", tokens.subList(flag + 1, tokens.size()));
                return inner.isBlank() ? null : inner;
            }
        }
        return null;
    }

    private static List<String> tokenize(final String command) {
        final var out = new ArrayList<String>();
        for (final var raw : COMMAND_TOKEN.split(command)) {
            final var token = QUOTES.matcher(raw).replaceAll("");
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    private static String baseName(final String program) {
        final var slash = Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\'));
        final var name = slash < 0 ? program : program.substring(slash + 1);
        return name.toLowerCase(Locale.ROOT);
    }

    /** Language of a script's interpreter when the output starts with a shebang. */
    private static String fromShebang(final String output) {
        final var matcher = SHEBANG.matcher(output);
        if (!matcher.find()) {
            return null;
        }
        return PROGRAMS.get(baseName(matcher.group(1)));
    }

    /** Language proven by the error shape a runtime leaves in the output. */
    private static String fromRuntime(final String output) {
        if (PYTHON_TRACEBACK.matcher(output).find()
                || PYTHON_FRAME.matcher(output).find()) {
            return "python";
        }
        if (SQL_STATEMENT.matcher(output).find()) {
            return "sql";
        }
        return null;
    }

    /**
     * Language of the files the output is about, but only when every recognized
     * reference agrees and is not a one-off: a lone {@code .md} in a log must not
     * paint the whole output.
     */
    private static String fromPaths(final String output) {
        var language = "";
        var hits = 0;
        for (var index = 0; index < output.length(); ) {
            if (!isPathCharacter(output.charAt(index))) {
                index++;
                continue;
            }
            final var start = index;
            var matchEnd = -1;
            // The old greedy path regex selected the rightmost valid dot in a run,
            // even if its extension was unknown. Never retry the run from each offset:
            // a dot-free run (or one with an overlong extension) made that quadratic.
            while (index < output.length() && isPathCharacter(output.charAt(index))) {
                if (output.charAt(index) == '.' && index > start) {
                    var end = index + 1;
                    while (end < output.length() && end - index <= 8 && isExtensionCharacter(output.charAt(end))) {
                        end++;
                    }
                    if (end > index + 1
                            && (end == output.length()
                                    || (!isExtensionCharacter(output.charAt(end))
                                            // Java's default \b treats a non-spacing mark after
                                            // a letter/digit (but not underscore) as part of the word.
                                            && (output.charAt(end - 1) == '_'
                                                    || Character.getType(output.codePointAt(end))
                                                            != Character.NON_SPACING_MARK)))) {
                        matchEnd = end;
                    }
                }
                index++;
            }
            if (matchEnd < 0) {
                continue;
            }
            final var candidate = SyntaxHighlighter.guessLanguage(output.substring(start, matchEnd));
            if (candidate == null || !PATH_LANGUAGES.contains(candidate)) {
                continue;
            }
            if (hits == 0) {
                language = candidate;
            } else if (!language.equals(candidate)) {
                return null;
            }
            hits++;
        }
        return hits >= 2 ? language : null;
    }

    private static boolean isPathCharacter(final char c) {
        return isExtensionCharacter(c) || c == '.' || c == '/' || c == '~' || c == '+' || c == '-';
    }

    private static boolean isExtensionCharacter(final char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }

    /** Language whose keywords clearly dominate the output. */
    private static String fromKeywords(final String output) {
        var language = "";
        var best = 0;
        var second = 0;
        for (final var entry : KEYWORDS.entrySet()) {
            var hits = 0;
            for (final var keyword : entry.getValue()) {
                hits += count(output, keyword);
            }
            if (hits > best) {
                second = best;
                best = hits;
                language = entry.getKey();
            } else if (hits > second) {
                second = hits;
            }
        }
        return best >= MIN_KEYWORD_HITS && best >= KEYWORD_RATIO * second ? language : null;
    }

    private static int count(final String haystack, final String needle) {
        var hits = 0;
        var from = 0;
        while (true) {
            final var at = haystack.indexOf(needle, from);
            if (at < 0) {
                return hits;
            }
            hits++;
            from = at + needle.length();
        }
    }
}
