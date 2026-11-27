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
package io.yupiik.llm.client.anthropic.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages wire response.
 *
 * @param id response identifier.
 * @param type wire object type, always {@code message}.
 * @param role message role, usually {@code assistant}.
 * @param content content blocks: text, thinking and/or tool_use.
 * @param model model that produced the response.
 * @param stopReason why the generation stopped, e.g. {@code end_turn} or {@code tool_use}.
 * @param stopSequence stop sequence that ended the generation, when hit.
 * @param usage token usage.
 */
@JsonModel
public record AnthropicMessagesResponse(
        String id,
        String type,
        String role,
        List<ContentBlock> content,
        String model,
        @JsonProperty("stop_reason") String stopReason,
        @JsonProperty("stop_sequence") String stopSequence,
        AnthropicUsage usage) {

    /**
     * Concatenates the textual content blocks.
     *
     * @return the concatenation of the textual content blocks.
     */
    public String text() {
        if (content == null) {
            return "";
        }
        final var builder = new StringBuilder();
        for (final var block : content) {
            if (block.text() != null) {
                builder.append(block.text());
            }
        }
        return builder.toString();
    }

    /**
     * One content block of a response.
     *
     * @param type block type: {@code text}, {@code thinking} or {@code tool_use}.
     * @param text textual content, for {@code text} blocks.
     * @param thinking reasoning content, for {@code thinking} blocks.
     * @param id tool call identifier, for {@code tool_use} blocks.
     * @param name function name, for {@code tool_use} blocks.
     * @param input JSON function arguments, for {@code tool_use} blocks.
     */
    @JsonModel
    public record ContentBlock(String type, String text, String thinking, String id, String name, Object input) {}

    /**
     * Token usage of a response.
     *
     * @param inputTokens prompt tokens consumed.
     * @param outputTokens completion tokens produced.
     * @param cacheReadInputTokens prompt tokens served from the prompt cache.
     * @param cacheCreationInputTokens prompt tokens written to the prompt cache.
     * @param others non standard usage fields published by some Anthropic compatible gateways, used
     *        to recover the provider side generation time when present.
     */
    @JsonModel
    public record AnthropicUsage(
            @JsonProperty("input_tokens") Long inputTokens,
            @JsonProperty("output_tokens") Long outputTokens,
            @JsonProperty("cache_read_input_tokens") Long cacheReadInputTokens,
            @JsonProperty("cache_creation_input_tokens") Long cacheCreationInputTokens,
            @JsonOthers Map<String, Object> others) {

        public AnthropicUsage(
                final Long inputTokens,
                final Long outputTokens,
                final Long cacheReadInputTokens,
                final Long cacheCreationInputTokens) {
            this(inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens, null);
        }
    }
}
