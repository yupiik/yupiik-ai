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
package io.yupiik.ia.agent.core.llm;

import io.yupiik.ia.agent.core.configuration.ReasoningFormat;

/**
 * Strips a model's reasoning from its complete text output, following a {@link ReasoningFormat}
 * descriptor. This is the shared persistence-side stripper so the stored / resent assistant message
 * provably equals what the UI displays: reasoning never leaks into the conversation history (and is
 * therefore never re-sent to the model on the next turn).
 *
 * <p>Three families are handled deliberately:
 *
 * <ul>
 *   <li>whole-line markers (GLM/Qwen, the {@code thinkTags} / AUTO preset): reasoning is a set of
 *       {@code  think} lines dropped from {@code content};</li>
 *   <li>no opening tag (DeepSeek-R1, the {@code deepSeekR1} preset): reasoning runs from the start
 *       until the {@code  response} marker;</li>
 *   <li>Qwen/Nemotron ({@code qwen} preset): a {@code  think} block closed by a {@code  response}
 *       marker or by an unclosed {@code <tool_call>} (the tool channel opening mid-block).</li>
 * </ul>
 */
public final class ReasoningStripper {

    private ReasoningStripper() {
        // utility
    }

    /**
     * Removes the reasoning from a complete assistant output.
     *
     * @param raw    the model text as accumulated.
     * @param format the reasoning format descriptor (never null).
     * @return the visible text with the reasoning removed.
     */
    public static String strip(final String raw, final ReasoningFormat format) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? "" : raw;
        }
        if (format.force() && format.open() == null) {
            return stripFromStartUntilClose(raw, format);
        }
        if (format.toolStart() != null) {
            return stripQwenBlock(raw, format);
        }
        return stripThinkLines(raw);
    }

    /** DeepSeek-R1: reasoning from the start until {@code close} (a {@code  response} marker). */
    private static String stripFromStartUntilClose(final String raw, final ReasoningFormat format) {
        final var close = format.close();
        if (close == null || close.isBlank()) {
            return "";
        }
        final var idx = raw.indexOf(close);
        return idx >= 0 ? raw.substring(idx + close.length()) : "";
    }

    /**
     * Strips a Qwen/Nemotron {@code  think ...} block: reasoning opens on a {@code  think}/{@code  thinking}
     * line and closes at the {@code  response} line or at an unclosed {@code <tool_call>} (the tool
     * channel opening mid-block). The reasoning-close line itself is dropped; the tool marker line and
     * everything after the block is preserved.
     */
    private static String stripQwenBlock(final String raw, final ReasoningFormat format) {
        if (raw.indexOf("think") < 0 && raw.indexOf(format.toolStart()) < 0) {
            return raw; // fast path: no reasoning marker at all
        }
        final var close = format.close();
        final var out = new StringBuilder(raw.length());
        final var lines = raw.split("\n", -1);
        final var last = lines.length - 1;
        for (var i = 0; i < lines.length; i++) {
            final var line = lines[i];
            if (isOpenLine(line)) {
                i++;
                while (i < lines.length) {
                    final var current = lines[i];
                    // an unclosed tool channel opening carries content after the reasoning: keep it
                    if (current.contains(format.toolStart())) {
                        out.append(current);
                        if (i < last) {
                            out.append('\n');
                        }
                        i = lines.length; // stop scanning: the rest of the buffer is content
                        break;
                    }
                    if (close != null && current.contains(close)) {
                        break; // the reasoning-close line is dropped
                    }
                    i++;
                }
                continue;
            }
            out.append(line);
            if (i < last) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** Whether a line opens a {@code  think}/{@code  thinking} reasoning block. */
    private static boolean isOpenLine(final String line) {
        final var trimmed = line.strip();
        return trimmed.equals("think") || trimmed.startsWith("think ") || trimmed.startsWith("<think");
    }

    /**
     * The default GLM/Qwen behaviour and the {@code thinkTags} preset: drop whole-line {@code  think}
     * markers, keep everything else. This is the exact semantics of the historical
     * {@code visibleText} stripper so the AUTO path is unchanged.
     */
    private static String stripThinkLines(final String raw) {
        if (raw.indexOf("think") < 0) {
            return raw; // fast path: no thinking marker at all
        }
        final var out = new StringBuilder(raw.length());
        for (final var line : raw.split("\n", -1)) {
            final var trimmed = line.strip();
            final var isThinkingLine = trimmed.equals("think") || trimmed.startsWith("think ");
            if (isThinkingLine || trimmed.contains("<think")) {
                continue; // a whole thinking line: drop it
            }
            out.append(line).append('\n');
        }
        // the split loop adds a trailing newline: drop it when the raw text had none
        if (!raw.endsWith("\n") && !out.isEmpty() && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }
}
