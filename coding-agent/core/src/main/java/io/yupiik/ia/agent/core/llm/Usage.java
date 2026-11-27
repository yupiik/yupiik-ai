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

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

@JsonModel
public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens,
        @JsonProperty("cache_read_tokens") long cacheReadTokens,
        @JsonProperty("cache_write_tokens") long cacheWriteTokens,
        @JsonProperty("reasoning_tokens") int reasoningTokens,
        @JsonProperty("generation_time_ms") long generationTimeMs,
        @JsonProperty("context_input_tokens") int contextInputTokens) {

    public Usage(
            final int promptTokens,
            final int completionTokens,
            final int totalTokens,
            final long cacheReadTokens,
            final long cacheWriteTokens,
            final int reasoningTokens,
            final long generationTimeMs) {
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

    public static Usage EMPTY = new Usage(0, 0, 0, 0, 0, 0, 0);

    public Usage(final int promptTokens, final int completionTokens, final int totalTokens) {
        this(promptTokens, completionTokens, totalTokens, 0, 0, 0, 0);
    }

    public Usage(
            final int promptTokens,
            final int completionTokens,
            final int totalTokens,
            final long cacheReadTokens,
            final long cacheWriteTokens) {
        this(promptTokens, completionTokens, totalTokens, cacheReadTokens, cacheWriteTokens, 0, 0);
    }
}
