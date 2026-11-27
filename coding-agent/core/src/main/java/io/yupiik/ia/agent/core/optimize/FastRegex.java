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

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A lightweight, <em>bounded-by-design</em> regex matcher for the small, mostly-literal patterns
 * used by the tool-output filters ({@link LogNoiseOutputProcessor}). It exists because those
 * filters run once per emitted log line, on potentially enormous test/build outputs, where the JIT
 * cost of a heavyweight {@link Pattern} on every line is wasteful and unbounded backtracking (ReDoS)
 * is unacceptable.
 *
 * <p>Design goals (no {@code executor.get(timeout)} tricks — time is bounded by construction):
 * <ul>
 *   <li>Recognizes a conservative, safe subset of the syntax actually used — whole-line alternations
 *       of literal words ({@code \b(A|B|C)\b}), anchored literal prefixes ({@code ^literal ...}) and
 *       plain substrings — and matches them with direct {@link CharSequence} scans (O(n), no
 *       backtracking engine at all).</li>
 *   <li>For every other pattern it keeps the real {@link Pattern} but guards it behind a fast
 *       required-literal pre-check, so the (correct, but costlier) regex only ever runs on lines
 *       that contain a mandatory substring; lines that cannot possibly match are rejected with one
 *       or two {@code indexOf} calls.</li>
 * </ul>
 *
 * <p>Usage: {@code new FastRegex("\\b(WARNING|ERROR)\\b")} then {@code fast.find(line)}.</p>
 */
public final class FastRegex {
    private static final Pattern WORD_ALTERNATION = Pattern.compile("[A-Za-z0-9_]+(\\|[A-Za-z0-9_]+)*");
    private static final Pattern SIMPLE_ALTERNATION_BODY = Pattern.compile("[A-Za-z0-9 _|.,-]+");

    /** Fast predicate; never throws. */
    private final Predicate<CharSequence> matcher;

    /**
     * @param regex the pattern, in the same syntax {@link Pattern#compile(String)} accepts.
     */
    public FastRegex(final String regex) {
        this.matcher = compile(regex);
    }

    /** @return true when the line matches anywhere (like {@code Pattern.matcher(line).find()}). */
    public boolean find(final CharSequence line) {
        return matcher.test(line);
    }

    private static Predicate<CharSequence> compile(final String regex) {
        // Whole-line keyword alternation: `\b(A|B|C)\b` (and variants). This is the shape of the
        // include rule (WARNING|SEVERE|...) and of the `(INFO|FINE|...)` core of the excludes.
        final var keywords = keywordAlternation(regex);
        if (keywords != null) {
            return input -> containsAnyKeyword(input, keywords);
        }
        // Anchored literal prefix: `^literal ...`/`^(A|B|C) ...`. Sorted branches, no regex needed.
        final var prefixes = anchoredPrefixes(regex);
        if (prefixes != null) {
            return input -> startsWithAny(input, prefixes);
        }
        // Fallback: real regex, guarded by a required-literal pre-check so ReDoS/heavy work is only
        // ever attempted on lines that demonstrably carry a mandatory literal fragment.
        return guarded(regex);
    }

    /**
     * Recognizes {@code \b(A|B|C)\b} (optionally with surrounding scaffolding and an optional
     * leading {@code .*}): returns the alternation keywords, or null when the shape is not matched.
     */
    private static String[] keywordAlternation(final String regex) {
        // Strip a trailing `\b` and optional leading `.*`.
        String r = regex;
        if (r.endsWith("\\b")) {
            r = r.substring(0, r.length() - 2);
        }
        if (r.startsWith(".*")) {
            r = r.substring(2);
        }
        if (!r.startsWith("\\b(") || !r.endsWith(")")) {
            return null;
        }
        r = r.substring(3, r.length() - 1);
        if (!WORD_ALTERNATION.matcher(r).matches()) {
            return null;
        }
        final var branches = r.split("\\|");
        return branches.length <= 1 ? null : branches;
    }

    /**
     * Recognizes {@code ^literal} or {@code ^(A|B|C)} anchored prefixes: the pattern "matches" when
     * the input starts with one of the branches (optionally followed by any scaffolding). Returns
     * the sorted prefixes, or null when the shape is not matched.
     */
    private static String[] anchoredPrefixes(final String regex) {
        if (!regex.startsWith("^")) {
            return null;
        }
        String r = regex.substring(1);
        // Grab the leading branch: `(A|B|C)` or a literal word before a space/dot digit.
        final String[] branches;
        if (r.startsWith("(")) {
            final int close = r.indexOf(')');
            if (close < 0) {
                return null;
            }
            final String body = r.substring(1, close);
            if (!SIMPLE_ALTERNATION_BODY.matcher(body).matches()) {
                return null;
            }
            branches = body.split("\\|");
        } else {
            final int end = firstNonLiteral(r);
            if (end <= 0) {
                return null;
            }
            branches = new String[] {r.substring(0, end)};
        }
        if (branches.length == 0) {
            return null;
        }
        Arrays.sort(branches);
        return branches;
    }

    private static int firstNonLiteral(final String s) {
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == ' ')) {
                return i;
            }
        }
        return s.length();
    }

    private static boolean containsAnyKeyword(final CharSequence input, final String[] keywords) {
        for (int i = 0; i < input.length(); i++) {
            final char c = input.charAt(i);
            // Fast reject: only scan starting at a word character.
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) {
                continue;
            }
            for (final String kw : keywords) {
                if (regionMatches(input, i, kw) && isBoundary(input, i - 1) && isBoundary(input, i + kw.length())) {
                    return true;
                }
            }
            // Skip the whole current word in one go to stay O(n).
            int j = i + 1;
            while (j < input.length() && isWordChar(input.charAt(j))) {
                j++;
            }
            i = j - 1;
        }
        return false;
    }

    private static boolean startsWithAny(final CharSequence input, final String[] prefixes) {
        for (final String p : prefixes) {
            if (regionMatches(input, 0, p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean regionMatches(final CharSequence input, final int start, final String s) {
        if (start < 0 || start + s.length() > input.length()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            // case-insensitive: log levels appear as INFO/info, ERROR/error, WARNING/warning...
            final char a = input.charAt(start + i);
            final char b = s.charAt(i);
            if (a != b && Character.toLowerCase(a) != Character.toLowerCase(b)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWordChar(final char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }

    private static boolean isBoundary(final CharSequence input, final int idx) {
        return idx < 0 || idx >= input.length() || !isWordChar(input.charAt(idx));
    }

    /** Fallback: real {@link Pattern} guarded by a required-literal pre-check. */
    private static Predicate<CharSequence> guarded(final String regex) {
        final Pattern compiled;
        try {
            compiled = Pattern.compile(regex);
        } catch (final RuntimeException e) {
            // Never let a bad config break a whole turn: a pattern that cannot compile never matches.
            return input -> false;
        }
        final var required = requiredLiterals(regex);
        if (required.length == 0) {
            return input -> compiled.matcher(input).find();
        }
        return input -> {
            for (final String lit : required) {
                if (!indexOf(input, lit)) {
                    return false;
                }
            }
            return compiled.matcher(input).find();
        };
    }

    /**
     * Returns a <em>sound</em> short-circuit guard: the longest fixed substring that <em>every</em>
     * possible match must contain. This can only be proven for a <em>leading anchored literal</em> —
     * optional leading {@code ^}/{@code .*} then a contiguous run of literal characters that appears
     * before any {@code (}, {@code [}, {@code |} or escape. Anything after an alternation or group is
     * branch-specific and cannot be assumed present (e.g. the Spring Boot banner art comes from a
     * different alternation branch than the {@code :: Spring Boot ::} label). Returns an empty array
     * when no such literal is provable, in which case the regex runs directly (these patterns are
     * anchored/literal and linear anyway).
     */
    private static String[] requiredLiterals(final String regex) {
        int i = 0;
        if (i < regex.length() && regex.charAt(i) == '^') {
            i++;
        }
        // skip a leading `.*` scaffolding
        if (regex.startsWith(".*", i)) {
            i += 2;
        }
        int start = -1;
        for (; i < regex.length(); i++) {
            final char c = regex.charAt(i);
            if (isLiteralChar(c)) {
                if (start < 0) {
                    start = i;
                }
            } else {
                break; // any regex construct ends the provable mandatory literal run
            }
        }
        if (start < 0 || i - start < 3) {
            return new String[0];
        }
        return new String[] {regex.substring(start, i)};
    }

    private static boolean isLiteralChar(final char c) {
        return c >= 'a' && c <= 'z'
                || c >= 'A' && c <= 'Z'
                || c >= '0' && c <= '9'
                || c == '_'
                || c == '-'; // e.g. the `---` separator in the Spring Boot runner-log pattern
    }

    private static boolean indexOf(final CharSequence input, final String needle) {
        final int n = input.length();
        final int m = needle.length();
        if (m > n) {
            return false;
        }
        final char first = needle.charAt(0);
        for (int i = 0; i <= n - m; i++) {
            if (input.charAt(i) != first) {
                continue;
            }
            if (regionMatches(input, i, needle)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(bounded)";
    }
}
