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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Argument completion of a slash command. A command accepting a closed set of
 * options registers {@link #options(String...)}; a command completing against
 * shell-provided values registers {@link #from(String, Function)}; a command
 * with positional or custom logic implements {@link #complete} itself. The
 * shells (TUI popup, JLine completer) only delegate: they tokenize the line
 * and turn the returned {@link Result} into their own UI, so each command owns
 * its completion instead of a centralized engine mapping.
 */
public interface ArgCompletion {

    /** Completion outcome: the label shown by the shell UI (null = generic) and the proposed items. */
    record Result(String label, List<String> items) {
        public Result {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public boolean empty() {
            return items.isEmpty();
        }

        public static Result of(final String label, final List<String> items) {
            return new Result(label, items == null ? List.of() : items);
        }
    }

    /**
     * Shell-neutral completion context: every value source a command can
     * complete against, supplied by the shell that renders completions. All
     * sources default to empty so minimal shells and tests stay cheap.
     */
    interface Env {
        default List<String> models() {
            return List.of();
        }

        default List<String> providers() {
            return List.of();
        }

        default List<String> agents() {
            return List.of();
        }

        default List<String> sessions() {
            return List.of();
        }

        default List<String> tools() {
            return List.of();
        }

        default List<String> subagents() {
            return List.of();
        }

        default List<String> servers() {
            return List.of();
        }

        default List<String> variants(final String model) {
            return List.of();
        }

        /**
         * The named variants of the shell's current model, for commands that are
         * contextual to the active model (e.g. {@code /variant}) rather than to a
         * model being typed. Defaults to empty; the shell resolves the active
         * model's variants.
         */
        default List<String> variants() {
            return List.of();
        }

        /** Filesystem paths for the given token; the walk is owned by the shell. */
        default List<String> paths(final String token) {
            return List.of();
        }
    }

    /** No argument completion: the common case of commands without argument. */
    ArgCompletion NONE = (arg, token, env) -> Result.of(null, List.of());

    /** Filesystem path completion, resolved through the shell env. */
    ArgCompletion PATH = (arg, token, env) -> fuzzyResult("path", env.paths(token), token);

    /** Closed set of options: the default completion for a plain option list. */
    static ArgCompletion options(final String... options) {
        return (arg, token, env) -> fuzzyResult("option", List.of(options), token);
    }

    /** Dynamic completion: the values come from the shell env through {@code values}. */
    static ArgCompletion from(final String label, final Function<Env, List<String>> values) {
        return (arg, token, env) -> fuzzyResult(label, values.apply(env), token);
    }

    /** Completes one literal argument, including spaces, rather than individual words. */
    static ArgCompletion wholeArgument(final String label, final Function<Env, List<String>> values) {
        return new ArgCompletion() {
            @Override
            public boolean wholeArgument() {
                return true;
            }

            @Override
            public Result complete(final String arg, final String token, final Env env) {
                return fuzzyResult(label, values.apply(env), token);
            }
        };
    }

    default boolean wholeArgument() {
        return false;
    }

    /**
     * The token typed before the current one, when {@code arg} (the raw text
     * stripped of the command and the current token) is non-blank - the helper
     * positional completions use to branch (e.g. /metrics session on|off).
     */
    static String previousToken(final String arg) {
        if (arg == null || arg.isBlank()) {
            return "";
        }
        final var stripped = arg.strip();
        for (var i = stripped.length() - 1; i >= 0; i--) {
            final var c = stripped.charAt(i);
            // Default regex \\s is ASCII-only, unlike Character.isWhitespace.
            if (c == ' ' || c == '\t' || c == '\n' || c == '\u000b' || c == '\f' || c == '\r') {
                return stripped.substring(i + 1);
            }
        }
        return stripped;
    }

    /**
     * Case-insensitive containment match, stable sorted; empty when nothing matches.
     * Containment (not only prefix) is intentional: a model named
     * {@code gpt-5-mini} is reachable by typing {@code mini}, its suffix.
     */
    static Result fuzzyResult(final String label, final List<String> candidates, final String token) {
        if (candidates == null || candidates.isEmpty()) {
            return Result.of(label, List.of());
        }
        final var needle = token == null ? "" : token.toLowerCase(Locale.ROOT);
        final var out = new ArrayList<String>();
        for (final var candidate : candidates) {
            if (candidate != null
                    && !candidate.isBlank()
                    && candidate.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(candidate);
            }
        }
        out.sort(Comparator.naturalOrder());
        return Result.of(label, out);
    }

    /**
     * Computes the argument completion: {@code arg} is the text typed before
     * the current token, stripped of the command name (trailing space
     * included, current token excluded), and {@code token} the token being
     * typed, so positional commands can branch (see {@link #previousToken}).
     */
    Result complete(String arg, String token, Env env);
}
