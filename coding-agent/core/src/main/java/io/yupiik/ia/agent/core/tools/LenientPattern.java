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
package io.yupiik.ia.agent.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A conservative, <em>transparent</em> wrapper around {@link Pattern} for the model-facing search
 * tools (the {@code grep} tool and the {@code grep} bash override). It exists because LLM-authored
 * patterns routinely slip into an inconsistency that Java's regex engine rejects outright: a top-level
 * alternation of near-literal fragments where one branch escapes its parentheses ({@code \(}) while
 * another leaves a bare {@code (} or {@code )}. The bare {@code (} then opens a group that is never
 * closed and {@link Pattern#compile(String)} throws {@code Unclosed group} — an opaque failure that
 * costs the model a round trip and trains it nothing.
 *
 * <p>The repair is deliberately narrow and provably safe:
 * <ul>
 *   <li>A pattern that {@link Pattern} compiles on the first try is <em>never touched</em> — the
 *       happy path has zero behaviour change and zero cost beyond one failed-compile probe.</li>
 *   <li>Repair only runs when compilation already failed, on the <em>unbalanced</em> bare parens
 *       that caused it: stray {@code (}/{@code )} that (after an escape- and character-class-aware
 *       scan of each top-level alternation branch) have no counterpart to form a group get escaped
 *       to {@code \(}/{@code )}. Parens that pair up are left as the groups the model meant. Correct
 *       escaping and already-valid constructs are never altered.</li>
 *   <li>If even the repaired pattern does not compile, the caller is told so with the original
 *       engine message — no guesswork is ever produced.</li>
 * </ul>
 */
public final class LenientPattern {

    private final Pattern pattern;
    private final boolean adjusted;
    private final String originalError;

    private LenientPattern(final Pattern pattern, final boolean adjusted, final String originalError) {
        this.pattern = pattern;
        this.adjusted = adjusted;
        this.originalError = originalError;
    }

    /**
     * Compiles {@code regex} with {@code flags}, first directly and, if that throws, after a repair
     * pass over unbalanced bare parentheses in each top-level alternation branch.
     *
     * @return the compiled {@link Pattern}, possibly from the repaired source. When even the repaired
     *         source fails to compile, {@link #pattern()} is {@code null} and {@link #originalError()}
     *         holds the underlying {@link PatternSyntaxException} message.
     */
    public static LenientPattern compile(final String regex, final int flags) {
        try {
            return new LenientPattern(Pattern.compile(regex, flags), false, null);
        } catch (final PatternSyntaxException e) {
            final var repaired = repair(regex);
            if (repaired == null) {
                return new LenientPattern(null, false, e.getMessage());
            }
            try {
                return new LenientPattern(Pattern.compile(repaired, flags), true, null);
            } catch (final PatternSyntaxException e2) {
                return new LenientPattern(null, false, e2.getMessage());
            }
        }
    }

    /** @return the compiled pattern, or {@code null} when it could not be compiled even after repair. */
    public Pattern pattern() {
        return pattern;
    }

    /** @return true when the original pattern did not compile and the repaired source was used. */
    public boolean adjusted() {
        return adjusted;
    }

    /** @return the underlying engine message when {@link #pattern()} is null, otherwise null. */
    public String originalError() {
        return originalError;
    }

    /**
     * Escapes unbalanced bare parentheses in each top-level alternation branch. Returns the repaired
     * pattern, or {@code null} when there is nothing to repair (the original already has no stray
     * parens, in which case the caller should report the original failure rather than fabricate one).
     */
    private static String repair(final String regex) {
        // Split on top-level `|` so a stray paren in one branch can never pair up with a stray paren
        // in another branch — each branch is an independent fragment in the model's mental model.
        final var branches = splitTopLevel(regex);
        final var repairedBranches = new ArrayList<String>(branches.size());
        var anyChanged = false;
        for (final String branch : branches) {
            final var repaired = repairBranch(branch);
            repairedBranches.add(repaired);
            if (repaired != null) {
                anyChanged = true;
            }
        }
        if (!anyChanged) {
            return null; // nothing to salvage -> let the caller surface the original engine error
        }
        final var sb = new StringBuilder(regex.length() + 4);
        for (int i = 0; i < repairedBranches.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(repairedBranches.get(i) == null ? branches.get(i) : repairedBranches.get(i));
        }
        return sb.toString();
    }

    /** Splits a pattern on top-level (escape- and character-class-aware) {@code |}. */
    private static List<String> splitTopLevel(final String regex) {
        final var out = new ArrayList<String>();
        final var cur = new StringBuilder();
        var escaped = false;
        var inClass = false;
        for (int i = 0; i < regex.length(); i++) {
            final char c = regex.charAt(i);
            if (escaped) {
                cur.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                cur.append(c);
                escaped = true;
                continue;
            }
            if (c == '[') {
                inClass = true;
                cur.append(c);
                continue;
            }
            if (c == ']' && inClass) {
                inClass = false;
                cur.append(c);
                continue;
            }
            if (c == '|' && !inClass) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /**
     * Escapes the unbalanced bare parentheses of one branch. Returns the repaired branch, or
     * {@code null} when the branch has no stray paren to repair.
     */
    private static String repairBranch(final String branch) {
        // First pass: pair up bare parens left-to-right (class- and escape-aware). A bare `(` is a
        // potential group-open; the matching bare `)` closes it. Parens left over after pairing are
        // the unbalanced strays that broke the pattern and are meant literally.
        final var opens = new ArrayList<Integer>(); // stack of indexes of unpaired bare `(`
        final var strayClose = new boolean[branch.length()]; // bare `)` with no partner -> literal
        var escaped = false;
        var inClass = false;
        for (int i = 0; i < branch.length(); i++) {
            final char c = branch.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '[') {
                inClass = true;
                continue;
            }
            if (c == ']' && inClass) {
                inClass = false;
                continue;
            }
            if (inClass) {
                continue; // `(` and `)` are literal inside a character class - never touch them
            }
            if (c == '(') {
                opens.add(i);
            } else if (c == ')') {
                if (!opens.isEmpty()) {
                    opens.remove(opens.size() - 1);
                } else {
                    strayClose[i] = true;
                }
            }
        }
        // The `(` still on the stack after the scan are unclosed -> literal.
        if (opens.isEmpty()) {
            var any = false;
            for (final boolean b : strayClose) {
                if (b) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return null;
            }
        }
        final StringBuilder sb = new StringBuilder(branch.length() + 4);
        escaped = false;
        inClass = false;
        int stackIdx = 0;
        for (int i = 0; i < branch.length(); i++) {
            final char c = branch.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                sb.append(c);
                escaped = true;
                continue;
            }
            if (c == '[') {
                inClass = true;
                sb.append(c);
                continue;
            }
            if (c == ']' && inClass) {
                inClass = false;
                sb.append(c);
                continue;
            }
            if (inClass) {
                sb.append(c);
                continue;
            }
            if (c == '(') {
                if (stackIdx < opens.size() && opens.get(stackIdx) == i) {
                    stackIdx++;
                    sb.append('\\').append(c); // unclosed -> literal
                } else {
                    sb.append(c);
                }
                continue;
            }
            if (c == ')' && strayClose[i]) {
                sb.append('\\').append(c);
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
