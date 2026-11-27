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
package io.yupiik.llm.client.openai.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import io.yupiik.llm.client.model.Usage;
import io.yupiik.llm.client.model.UsageExtensions;
import java.util.Map;

/**
 * Token usage reported by the OpenAI endpoints.
 *
 * @param promptTokens prompt tokens consumed.
 * @param completionTokens completion tokens produced.
 * @param totalTokens total tokens consumed.
 * @param promptTokensDetails prompt token breakdown (cached tokens, ...).
 * @param completionTokensDetails output token breakdown (reasoning tokens, ...).
 * @param others non standard usage fields published by some servers and proxies, used to recover
 *        the provider side generation time when present and a top-level {@code reasoning_tokens}
 *        fallback when the breakdown object is absent.
 */
@JsonModel
public record OpenAIUsage(
        @JsonProperty("prompt_tokens") Long promptTokens,
        @JsonProperty("completion_tokens") Long completionTokens,
        @JsonProperty("total_tokens") Long totalTokens,
        @JsonProperty("prompt_tokens_details") PromptTokensDetails promptTokensDetails,
        @JsonProperty("completion_tokens_details") CompletionTokensDetails completionTokensDetails,
        @JsonOthers Map<String, Object> others) {

    public OpenAIUsage(
            final Long promptTokens,
            final Long completionTokens,
            final Long totalTokens,
            final PromptTokensDetails promptTokensDetails) {
        this(promptTokens, completionTokens, totalTokens, promptTokensDetails, null, null);
    }

    /**
     * Prompt token breakdown reported by OpenAI endpoints.
     *
     * @param cachedTokens prompt tokens served from the automatic prompt cache.
     */
    @JsonModel
    public record PromptTokensDetails(
            @JsonProperty("cached_tokens") Long cachedTokens) {}

    /**
     * Output token breakdown reported by OpenAI endpoints.
     *
     * @param reasoningTokens output tokens spent on internal reasoning, already counted in
     *        {@code completion_tokens}.
     */
    @JsonModel
    public record CompletionTokensDetails(
            @JsonProperty("reasoning_tokens") Long reasoningTokens) {}

    /**
     * Maps the wire usage to the neutral model, summing prompt and completion when the total is absent.
     *
     * @return the neutral usage, {@link Usage#EMPTY} when no data is present.
     */
    public Usage toNeutral() {
        if (this == null) {
            return Usage.EMPTY;
        }
        final var generationTimeMs = UsageExtensions.generationTimeMs(others, completionTokens);
        if (promptTokens == null && completionTokens == null && totalTokens == null && generationTimeMs == null) {
            return Usage.EMPTY;
        }
        return new Usage(
                promptTokens == null ? 0 : promptTokens,
                completionTokens == null ? 0 : completionTokens,
                totalTokens == null
                        ? (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens)
                        : totalTokens,
                promptTokensDetails == null ? null : promptTokensDetails.cachedTokens(),
                null,
                reasoningTokens(),
                generationTimeMs);
    }

    /**
     * The output token count spent on internal reasoning, read from the {@code completion_tokens_details}
     * breakdown when present, falling back to a non-standard top-level {@code reasoning_tokens} field.
     *
     * @return the reasoning token count, {@code null} when the provider does not report any.
     */
    private Long reasoningTokens() {
        if (completionTokensDetails != null && completionTokensDetails.reasoningTokens() != null) {
            return completionTokensDetails.reasoningTokens();
        }
        if (others != null && others.get("reasoning_tokens") instanceof final Number n) {
            return n.longValue();
        }
        return null;
    }
}
