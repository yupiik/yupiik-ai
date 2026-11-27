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

import io.yupiik.ia.agent.core.llm.ModelsResponse;

/**
 * Converts the context window reported by a provider into the input budget the agent can really use.
 *
 * <p>The window (configured total or detected from {@code /v1/models}) covers both the prompt and the
 * completion, so the completion budget has to be reserved from it. When the model declares an output
 * cap, that cap is what the next reply may consume and it is the value reserved: a 262144 window
 * declaring 32768 completion tokens leaves 229376 for the input. Without a declared cap - or when the
 * declared cap is not smaller than the window, hence cannot be honoured - a conservative quarter of the
 * window (capped at {@link #DEFAULT_OUTPUT_RESERVE} tokens) is reserved instead.</p>
 *
 * <p>Two window conventions are distinguished by comparing the declared output cap to the window. When
 * the cap is strictly smaller (the total-window convention, eg. gpt-4o 128000/16384 or Anthropic
 * 1000000/128000), the window is the combined input+output budget and the cap is reserved from it. When
 * the cap is at least the window (the per-side convention, eg. LiteLLM {@code max_input_tokens} equal to
 * {@code max_output_tokens}), the window is the input limit itself and nothing is reserved. When the
 * window is only a relative/estimated figure (training metadata, overflow-adjusted), the conservative
 * reserve applies regardless.</p>
 */
public final class ContextBudget {
    /** Output headroom reserved when the model does not declare a usable output cap. */
    public static final int DEFAULT_OUTPUT_RESERVE = 20_000;

    /** Floor of the usable window, so a tiny or unknown window never makes a request impossible. */
    public static final int MIN_USABLE_WINDOW = 1_024;

    private ContextBudget() {
        // utility class
    }

    /**
     * The completion headroom to reserve in a context window.
     *
     * @param window               the total context window in tokens (with absolute flag), or non-positive when unknown.
     * @param declaredOutputTokens the output cap declared by the model/provider (with absolute flag), or non-positive when unknown.
     * @return the declared cap when positive, absolute, and smaller than the window, else
     *         {@code min(20000, window / 4)}; {@code 0} for an unknown window.
     */
    public static int outputReserve(
            final ModelsResponse.TokenCount window, final ModelsResponse.TokenCount declaredOutputTokens) {
        final int w = window == null ? 0 : window.value();
        if (w <= 0) {
            return 0;
        }
        final int declared = declaredOutputTokens == null ? 0 : declaredOutputTokens.value();
        if (declared > 0 && declaredOutputTokens.absolute()) {
            if (declared < w) {
                // total-window convention (gpt-4o 128k/16k, Anthropic 1M/128k): the output cap is a
                // strict partition of the window, so it is what gets reserved
                return declared;
            }
            if (window.sourceType() == ModelsResponse.SourceType.DETECTED_ABSOLUTE) {
                // per-side convention (LiteLLM max_input_tokens == max_output_tokens): a detected
                // hard window at or below the output cap is the input limit itself, not a total, so
                // nothing is reserved. A configured window is always a total, so a contradictory cap
                // falls back to the heuristic instead of reinterpreting the operator's figure.
                return 0;
            }
        }
        return Math.min(DEFAULT_OUTPUT_RESERVE, w / 4);
    }

    /**
     * The prompt size that triggers automatic compaction in buffer mode (opencode core parity):
     * {@code window - max(window * bufferPercent / 100, bufferMinTokens)}. A non-positive percentage
     * and floor disable the buffer (the whole window must be filled); the result is never below
     * {@link #MIN_USABLE_WINDOW}.
     *
     * @param window        the raw context window in tokens.
     * @param bufferPercent the reserved headroom percentage (15 by default).
     * @param bufferMinTokens the reserved headroom floor in tokens (16000 by default).
     * @return the prompt token count triggering compaction.
     */
    public static int compactionTrigger(final int window, final int bufferPercent, final int bufferMinTokens) {
        if (window <= 0) {
            return MIN_USABLE_WINDOW;
        }
        final var percent = Math.max(0, bufferPercent);
        final var floor = Math.max(0, bufferMinTokens);
        final var reserve = Math.max((long) window * percent / 100L, floor);
        return (int) Math.max(MIN_USABLE_WINDOW, window - Math.min(window, reserve));
    }

    /**
     * The buffer-mode compaction trigger capped so it never lands at or above the usable window.
     *
     * <p>The raw opencode-style trigger is computed against the raw window ({@link #compactionTrigger(int, int, int)}),
     * then capped at {@code usableWindow * safetyPercent / 100}. Without the cap, a provider declaring a large
     * absolute output cap (eg. azure-like 500k window with a 128k output cap) pushes the raw trigger above the
     * usable window and the {@link ContextManager} clamp then lands the trigger exactly on the usable window -
     * compaction only starts when the gauge is already at 100%. The safety fraction restores real headroom.</p>
     *
     * @param window           the raw context window in tokens; non-positive means unknown.
     * @param bufferPercent    the reserved headroom percentage (15 by default).
     * @param bufferMinTokens  the reserved headroom floor in tokens (16000 by default).
     * @param usableWindow     the usable (window minus output reserve) size in tokens; non-positive means unknown.
     * @param safetyPercent    the fraction of the usable window the trigger may not exceed, in percent (1..100).
     * @return the trigger in estimated prompt tokens, at least {@link #MIN_USABLE_WINDOW}, never above
     *         {@code usableWindow * safetyPercent / 100}.
     */
    public static int compactionTrigger(
            final int window,
            final int bufferPercent,
            final int bufferMinTokens,
            final int usableWindow,
            final int safetyPercent) {
        final var raw = compactionTrigger(window, bufferPercent, bufferMinTokens);
        final int usable = usableWindow > 0 ? usableWindow : raw;
        final var safe = Math.max(1, safetyPercent);
        final var cap = (int) Math.max(MIN_USABLE_WINDOW, (long) usable * safe / 100L);
        return Math.min(raw, cap);
    }

    /**
     * The window minus {@link #outputReserve(TokenCount, TokenCount)}, never below {@link #MIN_USABLE_WINDOW}.
     *
     * @param window               the total context window in tokens (with absolute flag), or non-positive when unknown.
     * @param declaredOutputTokens the output cap declared by the model/provider (with absolute flag), or non-positive when unknown.
     * @return the input budget the agent may fill without risking a window overflow.
     */
    public static int usableWindow(
            final ModelsResponse.TokenCount window, final ModelsResponse.TokenCount declaredOutputTokens) {
        final int w = window == null ? 0 : window.value();
        if (w <= 0) {
            return MIN_USABLE_WINDOW;
        }
        return Math.max(MIN_USABLE_WINDOW, w - outputReserve(window, declaredOutputTokens));
    }
}
