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
package io.yupiik.ia.agent.core.shell.slash;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Prompt-line completion engine shared by the TUI and the JLine completer.
 * It only tokenizes the line (command name, current token, typed arguments)
 * and delegates argument completion to the command itself through
 * {@link ArgCompletion} - the registry resolves the command's completion and
 * the shell provides the value sources ({@link ArgCompletion.Env}). The
 * engine keeps the two prompt-level grammars: slash command names and the
 * free-text {@code @agent} prefix.
 */
public final class CompletionEngine {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** A completion candidate: the range of text to replace and the matches. */
    public record Suggestion(List<String> items, int replaceStart, int replaceEnd, String label) {
        public boolean empty() {
            return items == null || items.isEmpty();
        }

        public static Suggestion none() {
            return new Suggestion(List.of(), 0, 0, "");
        }
    }

    private final SlashRegistry registry;
    private final ArgCompletion.Env env;

    public CompletionEngine(final SlashRegistry registry, final ArgCompletion.Env env) {
        this.registry = registry;
        this.env = env == null ? new ArgCompletion.Env() {} : env;
    }

    /**
     * Builds a completion suggestion for the whole prompt line.
     *
     * @param text   the full prompt text
     * @param cursor the cursor column (index into text)
     */
    public Suggestion suggest(final String text, final int cursor) {
        if (text == null || text.isEmpty()) {
            return Suggestion.none();
        }
        return suggestWithin(text, cursor);
    }

    private Suggestion suggestWithin(final String text, final int cursor) {
        // token the cursor is inside of (any whitespace, not only space: a
        // tab-separated /model <model> must complete like a space-separated one)
        int tokenStart = cursor;
        while (tokenStart > 0 && !Character.isWhitespace(text.charAt(tokenStart - 1))) {
            tokenStart--;
        }
        final var prefixInner = text.substring(tokenStart, cursor);
        if (cursor == text.length() && prefixInner.startsWith("@")) {
            // free-text @agent prefix: strip '@' for matching ("@coder" -> "coder")
            final var name = prefixInner.substring(1);
            final var items =
                    ArgCompletion.fuzzyResult("agents", env.agents(), name).items();
            return items.isEmpty() ? Suggestion.none() : new Suggestion(items, tokenStart + 1, cursor, "agents");
        }
        final var beforeRaw = text.substring(0, tokenStart);
        // no text before the current token means we are still typing the command itself
        if (beforeRaw.isBlank()) {
            if (text.startsWith("/")) {
                return complete(text.substring(0, cursor), 0, registry.names(), "commands");
            }
            return Suggestion.none();
        }
        final var firstToken = WHITESPACE.split(beforeRaw.strip())[0];
        if (!firstToken.startsWith("/")) {
            return Suggestion.none();
        }
        final var command = firstToken.substring(1);
        // the arguments typed before the current token: the text before the
        // token minus the leading command name, so positional completions
        // (e.g. /metrics session on|off) branch on the typed arguments only.
        // Split on any whitespace (space, tab) so tab-separated args behave
        // exactly like space-separated ones.
        final var typed = text.substring(0, tokenStart).strip();
        final var typedTokens = WHITESPACE.split(typed, 2);
        final var arg = typedTokens.length > 1 ? typedTokens[1].strip() : "";
        final var completion = registry.completionFor(command);
        if (completion.wholeArgument()) {
            int argumentStart = text.indexOf(firstToken) + firstToken.length();
            while (argumentStart < cursor && Character.isWhitespace(text.charAt(argumentStart))) {
                argumentStart++;
            }
            final var result = completion.complete("", text.substring(argumentStart, cursor), env);
            return result.empty()
                    ? Suggestion.none()
                    : new Suggestion(result.items(), argumentStart, text.length(), result.label());
        }
        final var result = completion.complete(arg, prefixInner, env);
        if (result.empty()) {
            return Suggestion.none();
        }
        final var label = result.label() == null || result.label().isBlank() ? "complete" : result.label();
        return new Suggestion(result.items(), tokenStart, cursor, label);
    }

    private Suggestion complete(
            final String token, final int replaceStart, final List<String> candidates, final String label) {
        final var items = ArgCompletion.fuzzyResult(label, candidates, token).items();
        return items.isEmpty()
                ? Suggestion.none()
                : new Suggestion(items, replaceStart, replaceStart + token.length(), label);
    }

    /** Filesystem path completer used by the shell env implementations of /context and /editplan. */
    public static List<String> pathCandidates(final String token, final Path workdir) {
        Objects.requireNonNull(workdir, "workdir");
        final var base = token.isEmpty() ? "." : token;
        final var norm = base.replace("~", System.getProperty("user.home"));
        final var supplied = Path.of(norm);
        final var path = supplied.isAbsolute() ? supplied : workdir.resolve(supplied);
        final var parent = path.getParent() == null ? workdir : path.getParent();
        final var lastSlash = base.lastIndexOf('/');
        final var dirPart = lastSlash >= 0 ? base.substring(0, lastSlash + 1) : "";
        final var fileName =
                path.getFileName() == null ? "" : path.getFileName().toString();
        final var lcToken = token.toLowerCase(Locale.ROOT);
        final List<String> out;
        try (final var stream = Files.list(parent)) {
            out = stream.filter(p -> p.getFileName() != null)
                    .map(p -> {
                        final var name = p.getFileName().toString();
                        return Files.isDirectory(p) ? dirPart + name + "/" : dirPart + name;
                    })
                    .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(lcToken))
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            return List.of();
        }
        return out;
    }
}
