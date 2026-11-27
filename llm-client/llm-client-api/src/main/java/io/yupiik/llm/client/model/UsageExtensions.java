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
package io.yupiik.llm.client.model;

import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * Reads the non standard usage extensions emitted by some OpenAI compatible servers and gateways.
 * <p>
 * Neither the OpenAI nor the Anthropic payload carries any generation timing, but a number of servers
 * and proxies (LiteLLM, vLLM, OpenRouter, ...) publish their own. These helpers normalize the shapes
 * found in the wild to a millisecond duration so the server side throughput can be displayed next to
 * the client side measurement. Unknown or malformed values are simply ignored: the caller always falls
 * back to its own measurement.
 *
 * @see Usage#generationTimeMs()
 */
public final class UsageExtensions {
    private UsageExtensions() {
        // no-op.
    }

    /**
     * Extracts a provider side generation time.
     * Millisecond aliases take precedence over seconds, then rates. The first candidate that
     * converts to a finite, positive duration below {@code 0x1p63} and rounds to at least one
     * millisecond wins; unusable candidates do not prevent trying later aliases.
     *
     * @param extensions the non standard fields of the usage payload, may be {@code null}.
     * @param completionTokens the completion token count of the same payload, used to convert a rate into a duration.
     * @return the generation time in milliseconds, {@code null} when the payload carries no usable timing.
     */
    public static Long generationTimeMs(final Map<String, Object> extensions, final Long completionTokens) {
        if (extensions == null || extensions.isEmpty()) {
            return null;
        }
        final var millis =
                duration(extensions, value -> value, "generation_time_ms", "generation_time_millis", "generation_ms");
        if (millis != null) {
            return millis;
        }
        // seconds, the most common gateway shape.
        final var seconds = duration(extensions, value -> value * 1_000d, "generation_time", "generation_time_seconds");
        if (seconds != null) {
            return seconds;
        }
        // rate, converted back to a duration when the token count is known.
        if (completionTokens != null && completionTokens > 0) {
            return duration(
                    extensions,
                    value -> completionTokens * 1_000d / value,
                    "tokens_per_second",
                    "tps",
                    "output_tokens_per_second");
        }
        return null;
    }

    private static Long duration(
            final Map<String, Object> extensions, final DoubleUnaryOperator conversion, final String... keys) {
        for (final var key : keys) {
            final var value = number(extensions.get(key));
            if (value == null || !Double.isFinite(value) || value <= 0) {
                continue;
            }
            final var converted = conversion.applyAsDouble(value);
            if (!Double.isFinite(converted) || converted <= 0 || converted >= 0x1p63) {
                continue;
            }
            final var rounded = Math.round(converted);
            if (rounded > 0) {
                return rounded;
            }
        }
        return null;
    }

    private static Double number(final Object value) {
        if (value instanceof final Number n) {
            return n.doubleValue();
        }
        if (value instanceof final String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.strip());
            } catch (final NumberFormatException e) {
                // Try the next alias.
            }
        }
        return null;
    }
}
