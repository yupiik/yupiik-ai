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
package io.yupiik.ia.agent.core.context;

/**
 * Shared markdown fenced-code-block classification used by the seam scrubbers
 * ({@link ContextCompression#stripEchoedRefs},
 * {@link ContextCompression#withholdDanglingPrefix}, {@code StreamTextBuffer})
 * so every surface (persisted ingest, live TUI/CLI render, selection copy)
 * applies the same content-preservation rule: a seam-shaped token is removed
 * only when it stands alone on a line OUTSIDE a fenced code block; anything
 * inside a fence - including a token alone on its own content line - is literal
 * user content and is preserved.
 *
 * <p>Fences follow CommonMark's minimum rules: a fence run is 3+ backticks or
 * tildes (possibly indented up to 3 spaces), an info string may follow the
 * opening run (the whole remainder of that line is part of the fence, so no
 * seam scan happens on it), and a closing run is the same character with at
 * least the opening run's length and nothing but whitespace after it.
 * Classification is a single linear pass and never uses a regex. Unbalanced or
 * unterminated fences keep the rest of the text treated as fenced (matches
 * commonmark's behavior of running to EOF for an unclosed fence, which is
 * exactly what a streaming half-arrived opener needs).
 *
 * <p>Use one {@link Classifier} per whole text and call {@link Classifier#classify}
 * for each source line in order.
 */
public final class MarkdownFence {
    private MarkdownFence() {
        // no instances
    }

    /**
     * Stateful code classifier: {@link #classify} is called once per source line,
     * in order, and returns whether that line is protected by a fenced block or
     * a code span carried over from a previous line (delimiter lines included).
     */
    public static final class Classifier {
        private boolean inFence;
        private char fenceChar;
        private int fenceLen;
        private int inlineRun;

        /** Whether the next line begins inside a fence or a continued code span. */
        public boolean inCode() {
            return inFence || inlineRun > 0;
        }

        /**
         * Classifies one source line ({@code [lineStart, lineEnd)} of {@code text}) and returns
         * whether the line is part of a fenced block or begins inside a multiline code span.
         * Call exactly once per line, in source order, including the final
         * unterminated line.
         */
        public boolean classify(final String text, final int lineStart, final int lineEnd) {
            final var wasInFence = inFence;
            final var wasInInline = inlineRun > 0;
            if (inFence) {
                if (isClosingFence(text, lineStart, lineEnd, fenceChar, fenceLen)) {
                    inFence = false;
                }
            } else if (inlineRun == 0) {
                final var open = openingFenceLength(text, lineStart, lineEnd);
                if (open > 0) {
                    fenceChar = text.charAt(firstNonSpace(text, lineStart, lineEnd));
                    fenceLen = open;
                    inFence = true;
                }
            }
            if (!wasInFence && !inFence) {
                for (var i = lineStart; i < lineEnd; i++) {
                    final var c = text.charAt(i);
                    if (c == '\\' && inlineRun == 0 && i + 1 < lineEnd) {
                        i++;
                    } else if (c == '`') {
                        final var start = i;
                        while (i + 1 < lineEnd && text.charAt(i + 1) == '`') {
                            i++;
                        }
                        final var run = i - start + 1;
                        if (inlineRun == 0) {
                            inlineRun = run;
                        } else if (inlineRun == run) {
                            inlineRun = 0;
                        }
                    }
                }
            }
            return wasInFence || inFence || wasInInline;
        }
    }

    /** The index of the first non-space character within {@code [start, end)} (may equal end). */
    private static int firstNonSpace(final String text, final int start, final int end) {
        var i = start;
        while (i < end && text.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** The number of leading spaces within {@code [start, end)}. */
    private static int leadingSpaces(final String text, final int start, final int end) {
        return firstNonSpace(text, start, end) - start;
    }

    /** True when the line at {@code [start, end)} begins with a fence run (3+ ` or ~ after <=3 indent). */
    private static boolean fenceStart(final String text, final int start, final int end) {
        if (leadingSpaces(text, start, end) > 3) {
            return false;
        }
        final var i = firstNonSpace(text, start, end);
        if (i >= end) {
            return false;
        }
        final var c = text.charAt(i);
        return c == '`' || c == '~';
    }

    /** The length of the opening fence run, or 0 when the line is not a fence opener. */
    private static int openingFenceLength(final String text, final int start, final int end) {
        if (!fenceStart(text, start, end)) {
            return 0;
        }
        final var runStart = firstNonSpace(text, start, end);
        final var c = text.charAt(runStart);
        var i = runStart;
        while (i < end && text.charAt(i) == c) {
            i++;
        }
        final var length = i - runStart;
        if (c == '`') {
            for (var j = i; j < end; j++) {
                if (text.charAt(j) == '`') {
                    return 0;
                }
            }
        }
        return length >= 3 ? length : 0;
    }

    /** True when the line at {@code [start, end)} closes the open fence {@code fenceChar}/{@code fenceLen}. */
    private static boolean isClosingFence(
            final String text, final int start, final int end, final char fenceChar, final int fenceLen) {
        final var runStart = firstNonSpace(text, start, end);
        if (runStart >= end || runStart - start > 3) {
            return false;
        }
        var i = runStart;
        while (i < end && text.charAt(i) == fenceChar) {
            i++;
        }
        if (i - runStart < fenceLen) {
            return false;
        }
        while (i < end && (text.charAt(i) == ' ' || text.charAt(i) == '\t' || text.charAt(i) == '\r')) {
            i++;
        }
        return i == end;
    }
}
