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

import io.yupiik.ia.agent.core.shell.ShellIO;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/** Resolves user-entered text without mutating a transcript, queue or model payload. */
public final class ShellInputRouting {
    private static final Pattern COMMAND_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9-]*");
    private static final String INPUT = "Send as normal input";
    private static final String CORRECT = "Mistyped command - I'll fix it";

    private ShellInputRouting() {}

    public enum Decision {
        PROMPT,
        COMMAND,
        CORRECT,
        CANCEL
    }

    public static CompletionStage<Decision> resolve(final String text, final SlashRegistry registry, final ShellIO io) {
        final var value = text == null ? "" : text.stripLeading();
        if (!value.startsWith("/")) {
            return CompletableFuture.completedStage(Decision.PROMPT);
        }
        int end = 0;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end))) {
            end++;
        }
        final var token = value.substring(1, end);
        // Count only the first contiguous token: a slash in a command argument is irrelevant.
        if (token.contains("/")) {
            return CompletableFuture.completedStage(Decision.PROMPT);
        }
        if (registry.find(token).isPresent()) {
            return CompletableFuture.completedStage(Decision.COMMAND);
        }
        if (!COMMAND_NAME.matcher(token).matches()) {
            return CompletableFuture.completedStage(Decision.PROMPT);
        }
        final var suggestions = suggestions(token, registry);
        final var title = "Unknown command /" + token
                + (suggestions.isEmpty() ? "" : " - did you mean " + String.join(", ", suggestions) + "?")
                + " Is this input or a mistyped command?";
        return io.pick(title, List.of(INPUT, CORRECT)).thenApply(answer -> {
            if (answer.isEmpty()) {
                return Decision.CANCEL;
            }
            if (INPUT.equals(answer.get())) {
                return Decision.PROMPT;
            }
            return CORRECT.equals(answer.get()) ? Decision.CORRECT : Decision.CANCEL;
        });
    }

    private static List<String> suggestions(final String token, final SlashRegistry registry) {
        // Bound the work even for an accidentally pasted very long token.
        if (token.length() > 128) {
            return List.of();
        }
        final var name = token.toLowerCase(Locale.ROOT);
        return registry.specs().stream()
                .map(spec -> spec.command())
                .filter(candidate -> candidate.length() <= 128 && Math.abs(candidate.length() - name.length()) <= 2)
                .map(candidate -> new Suggestion(candidate, distance(name, candidate.toLowerCase(Locale.ROOT))))
                .filter(candidate -> candidate.distance() <= 2)
                .sorted(Comparator.comparingInt(Suggestion::distance).thenComparing(Suggestion::name))
                .limit(3)
                .map(candidate -> "/" + candidate.name())
                .toList();
    }

    private static int distance(final String left, final String right) {
        var previous = new int[right.length() + 1];
        var current = new int[right.length() + 1];
        for (var i = 0; i <= right.length(); i++) {
            previous[i] = i;
        }
        for (var i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (var j = 1; j <= right.length(); j++) {
                current[j] = Math.min(
                        Math.min(previous[j] + 1, current[j - 1] + 1),
                        previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            }
            final var swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private record Suggestion(String name, int distance) {}
}
