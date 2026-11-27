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

/**
 * Token usage of a completion, provider-agnostic.
 *
 * @param promptTokens input tokens, may be {@code null} when the provider omits it.
 * @param completionTokens output tokens, may be {@code null} when the provider omits it.
 * @param totalTokens total tokens, may be {@code null} when the provider omits it.
 * @param cacheReadTokens prompt tokens served from a provider-side prompt cache, when reported.
 * @param cacheWriteTokens prompt tokens written to a provider-side prompt cache, when reported.
 * @param reasoningTokens output tokens spent on internal reasoning (hidden chain of thought), when the
 *        provider reports a breakdown; these are already counted in {@code completionTokens}.
 * @param generationTimeMs provider-side generation time in milliseconds, when reported. This is a
 *        non-standard field only emitted by some OpenAI compatible servers and proxies (e.g.
 *        {@code generation_time}, {@code tokens_per_second}), {@code null} otherwise.
 */
public record Usage(
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        Long cacheReadTokens,
        Long cacheWriteTokens,
        Long reasoningTokens,
        Long generationTimeMs,
        Long contextInputTokens) {

    public Usage(
            final Long promptTokens,
            final Long completionTokens,
            final Long totalTokens,
            final Long cacheReadTokens,
            final Long cacheWriteTokens,
            final Long reasoningTokens,
            final Long generationTimeMs) {
        this(
                promptTokens,
                completionTokens,
                totalTokens,
                cacheReadTokens,
                cacheWriteTokens,
                reasoningTokens,
                generationTimeMs,
                promptTokens);
    }

    /** Full input occupancy, independent of provider-specific billing/cache conventions. */
    public long contextInput() {
        return contextInputTokens == null ? prompt() : contextInputTokens;
    }

    public static final Usage EMPTY = new Usage(0L, 0L, 0L, 0L, 0L, null, null);

    public Usage(final Long promptTokens, final Long completionTokens, final Long totalTokens) {
        this(promptTokens, completionTokens, totalTokens, null, null, null, null);
    }

    public Usage(
            final Long promptTokens,
            final Long completionTokens,
            final Long totalTokens,
            final Long cacheReadTokens,
            final Long cacheWriteTokens) {
        this(promptTokens, completionTokens, totalTokens, cacheReadTokens, cacheWriteTokens, null, null);
    }

    /**
     * The provider-side generation time.
     *
     * @return the generation time in milliseconds, 0 when the provider does not report one.
     */
    public long generationTime() {
        return generationTimeMs == null ? 0 : generationTimeMs;
    }

    /**
     * The prompt token count.
     *
     * @return the prompt token count, 0 when absent.
     */
    public long prompt() {
        return promptTokens == null ? 0 : promptTokens;
    }

    /**
     * The completion token count.
     *
     * @return the completion token count, 0 when absent.
     */
    public long completion() {
        return completionTokens == null ? 0 : completionTokens;
    }

    /**
     * The output token count spent on internal reasoning.
     *
     * @return the reasoning token count, 0 when absent.
     */
    public long reasoning() {
        return reasoningTokens == null ? 0 : reasoningTokens;
    }

    /**
     * The total token count.
     *
     * @return the total token count, 0 when absent.
     */
    public long total() {
        return totalTokens == null ? 0 : totalTokens;
    }

    /**
     * The prompt-cache read token count.
     *
     * @return the cache read token count, 0 when absent.
     */
    public long cacheRead() {
        return cacheReadTokens == null ? 0 : cacheReadTokens;
    }

    /**
     * The prompt-cache write token count.
     *
     * @return the cache write token count, 0 when absent.
     */
    public long cacheWrite() {
        return cacheWriteTokens == null ? 0 : cacheWriteTokens;
    }
}
