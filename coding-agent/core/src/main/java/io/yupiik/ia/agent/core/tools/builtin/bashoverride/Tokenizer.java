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
package io.yupiik.ia.agent.core.tools.builtin.bashoverride;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal quote-aware tokenizer for the read-only survey commands. It splits a command into {@code &&}/
 * {@code ;}-separated segments and each segment into whitespace-separated tokens. Any syntax the rewrite cannot
 * faithfully reproduce - pipe ({@code |}), command substitution ({@code $(...)}), backtick, input redirection
 * ({@code <}), backgrounding (lone {@code &}), write redirection other than the tolerated
 * {@code 2>/dev/null}/{@code >/dev/null}/{@code 2>&1} - makes the whole command fall back to the real shell
 * (return value {@code null}).
 */
final class Tokenizer {
    private Tokenizer() {}

    /** @return the &&/;-separated segments, or null when disallowed syntax is present. */
    static List<String> segments(final String command) {
        final var out = new ArrayList<String>();
        final var cur = new StringBuilder();
        char quote = 0;
        for (var i = 0; i < command.length(); i++) {
            final var c = command.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
                continue;
            }
            if (c == '|' || c == '`' || c == '<' || c == '(') {
                return null; // pipe, backtick, input redirect, or an unquoted '(' (could be $(, subshell...)
            }
            if (c == '$' && i + 1 < command.length() && command.charAt(i + 1) == '(') {
                return null;
            }
            if (c == ';') {
                out.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            if (c == '&') {
                if (i + 1 >= command.length() || command.charAt(i + 1) != '&') {
                    return null; // lone & = backgrounding
                }
                out.add(cur.toString().trim());
                cur.setLength(0);
                i++; // skip second '&'
                continue;
            }
            cur.append(c);
        }
        if (quote != 0) {
            return null; // unterminated quote
        }
        out.add(cur.toString().trim());
        return out;
    }

    /**
     * @return quote-aware, unquoted tokens of a segment; null when a write redirect (other than the tolerated
     *         {@code 2>/dev/null}/{@code >/dev/null}/{@code 2>&1}) is present.
     */
    static List<String> tokens(final String segment) {
        final var out = new ArrayList<String>();
        final var cur = new StringBuilder();
        char quote = 0;
        var toleratedRedirectSeen = false;
        for (var i = 0; i < segment.length(); i++) {
            final var c = segment.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
                continue;
            }
            if (c == '>') {
                final var rest = segment.substring(i).trim();
                if (rest.startsWith("2>/dev/null")
                        || rest.startsWith(">/dev/null")
                        || rest.startsWith("2>&1")
                        || rest.startsWith("2> /dev/null")
                        || rest.startsWith("> /dev/null")) {
                    toleratedRedirectSeen = true;
                    break; // the trailing tolerated redirect is dropped
                }
                return null; // other redirects -> fall back
            }
            if (Character.isWhitespace(c)) {
                if (!cur.isEmpty()) {
                    out.add(unescape(unquote(cur.toString())));
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (quote != 0) {
            return null;
        }
        if (!cur.isEmpty()) {
            out.add(unescape(unquote(cur.toString())));
        }
        if (toleratedRedirectSeen && !out.isEmpty() && "2>/dev/null".equals(out.getLast())) {
            out.removeLast();
        }
        return out;
    }

    private static String unquote(final String s) {
        if (s.length() >= 2 && (s.startsWith("'") && s.endsWith("'") || s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String unescape(final String s) {
        final var out = new StringBuilder(s.length());
        for (var i = 0; i < s.length(); i++) {
            final var c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                out.append(s.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
