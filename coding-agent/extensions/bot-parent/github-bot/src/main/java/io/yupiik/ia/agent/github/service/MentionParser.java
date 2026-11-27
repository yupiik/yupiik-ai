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
package io.yupiik.ia.agent.github.service;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.Optional;

/** Parses the first unquoted, non-code bot mention, excluding task-marked bot output. */
@ApplicationScoped
public class MentionParser {
    public Optional<Invocation> parse(final String body, final String botLogin) {
        if (body == null || botLogin == null || botLogin.isBlank() || body.contains("<!-- yca-task:")) {
            return Optional.empty();
        }
        final var visible = visibleText(body);
        // Consume each login run once, including nonmatching logins. No backtracking
        // or repeated comparisons at each character of an almost-matching login.
        for (int i = 0; i < visible.length(); i++) {
            if (visible.charAt(i) != '@'
                    || (i > 0
                            && (word(visible.charAt(i - 1))
                                    || visible.charAt(i - 1) == '@'
                                    || visible.charAt(i - 1) == '/'))) {
                continue;
            }
            final int start = i + 1;
            int end = start;
            while (end < visible.length() && loginCharacter(visible.charAt(end))) {
                end++;
            }
            if (end == start) {
                continue;
            }
            if (asciiMatches(visible, end, "[bot]")) {
                end += 5;
            }
            final boolean boundary = end == visible.length()
                    || (!word(visible.charAt(end))
                            && visible.charAt(end) != '-'
                            && !asciiMatches(visible, end, "[bot]"));
            i = end - 1;
            if (boundary
                    && end - start == botLogin.length()
                    && visible.regionMatches(true, start, botLogin, 0, botLogin.length())) {
                return command(visible, end);
            }
        }
        return Optional.empty();
    }

    private Optional<Invocation> command(final String visible, final int mentionEnd) {
        int start = mentionEnd;
        while (start < visible.length() && (visible.charAt(start) == ' ' || visible.charAt(start) == '\t')) {
            start++;
        }
        if (start == mentionEnd) {
            return Optional.empty();
        }
        for (final var kind : new String[] {"solve", "review", "help", "fix"}) {
            final int end = start + kind.length();
            if (asciiMatches(visible, start, kind)
                    && (end == visible.length() || asciiWhitespace(visible.charAt(end)))) {
                return Optional.of(new Invocation(kind, visible.substring(end).strip()));
            }
        }
        return Optional.empty();
    }

    private boolean asciiMatches(final String text, final int start, final String token) {
        if (text.length() - start < token.length()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            final char c = text.charAt(start + i);
            final char lower = c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
            if (lower != token.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private boolean loginCharacter(final char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-';
    }

    private boolean word(final char c) {
        return loginCharacter(c) && c != '-' || c == '_';
    }

    private boolean asciiWhitespace(final char c) {
        return c == ' ' || c >= '\t' && c <= '\r';
    }

    private boolean lineBreak(final char c) {
        return c == '\n' || c == '\r' || c == '\u000b' || c == '\f' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    private String visibleText(final String body) {
        final var result = new StringBuilder();
        char fence = 0;
        int fenceLength = 0;
        int inline = 0;
        for (int start = 0; start <= body.length(); ) {
            int endOfLine = start;
            while (endOfLine < body.length() && !lineBreak(body.charAt(endOfLine))) {
                endOfLine++;
            }
            final var line = body.substring(start, endOfLine);
            start = endOfLine + 1;
            if (endOfLine < body.length()
                    && body.charAt(endOfLine) == '\r'
                    && start < body.length()
                    && body.charAt(start) == '\n') {
                start++;
            }
            final var trimmed = line.stripLeading();
            if (trimmed.startsWith(">")) {
                result.append('\n');
                continue;
            }
            final char first = trimmed.isEmpty() ? 0 : trimmed.charAt(0);
            int run = 0;
            if (first == '`' || first == '~') {
                while (run < trimmed.length() && trimmed.charAt(run) == first) {
                    run++;
                }
            }
            if (fence != 0) {
                if (first == fence
                        && run >= fenceLength
                        && trimmed.substring(run).isBlank()) {
                    fence = 0;
                }
                result.append('\n');
                continue;
            }
            if (run >= 3 && inline == 0) {
                fence = first;
                fenceLength = run;
                result.append('\n');
                continue;
            }
            for (int i = 0; i < line.length(); ) {
                if (line.charAt(i) == '`') {
                    int end = i + 1;
                    while (end < line.length() && line.charAt(end) == '`') {
                        end++;
                    }
                    final int size = end - i;
                    if (inline == 0) {
                        inline = size;
                    } else if (inline == size) {
                        inline = 0;
                    }
                    result.append(' ');
                    i = end;
                } else {
                    result.append(inline == 0 ? line.charAt(i) : ' ');
                    i++;
                }
            }
            result.append('\n');
        }
        return result.toString();
    }

    public record Invocation(String kind, String request) {}
}
