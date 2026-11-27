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
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;

/**
 * OpenAI responses API response, output items are messages or function calls.
 *
 * @param id response identifier.
 * @param object wire object type, always {@code response}.
 * @param createdAt creation timestamp (epoch seconds).
 * @param status response status: {@code completed}, {@code incomplete} or {@code failed}.
 * @param model model that produced the response.
 * @param output output items: messages and/or function calls.
 * @param incompleteDetails reason the response is incomplete, when status is {@code incomplete}.
 * @param usage token usage, when requested.
 */
@JsonModel
public record ResponsesResponse(
        String id,
        String object,
        @JsonProperty("created_at") Long createdAt,
        String status,
        String model,
        List<OutputItem> output,
        @JsonProperty("incomplete_details") IncompleteDetails incompleteDetails,
        ResponsesUsage usage) {

    /**
     * One output element of a response.
     *
     * @param type item type: {@code message} or {@code function_call}.
     * @param id item identifier.
     * @param status item status.
     * @param role message role, for {@code message} items.
     * @param content message content parts, for {@code message} items.
     * @param callId function call identifier, for {@code function_call} items.
     * @param name function name, for {@code function_call} items.
     * @param arguments JSON function arguments, for {@code function_call} items.
     */
    @JsonModel
    public record OutputItem(
            String type,
            String id,
            String status,
            String role,
            List<OutputContent> content,
            @JsonProperty("call_id") String callId,
            String name,
            String arguments) {}

    /**
     * One content part of a message item.
     *
     * @param type content part type, usually {@code output_text}.
     * @param text textual content.
     * @param annotations text annotations (citations, ...).
     */
    @JsonModel
    public record OutputContent(String type, String text, List<Object> annotations) {}

    /**
     * Reason a response is incomplete.
     *
     * @param reason incompleteness reason, e.g. {@code max_output_tokens}.
     */
    @JsonModel
    public record IncompleteDetails(String reason) {}

    /**
     * Token usage of a response.
     *
     * @param inputTokens prompt tokens consumed.
     * @param outputTokens completion tokens produced.
     * @param totalTokens total tokens consumed.
     * @param inputTokensDetails input token breakdown (cached tokens, ...).
     * @param outputTokensDetails output token breakdown (reasoning tokens, ...).
     */
    @JsonModel
    public record ResponsesUsage(
            @JsonProperty("input_tokens") Long inputTokens,
            @JsonProperty("output_tokens") Long outputTokens,
            @JsonProperty("total_tokens") Long totalTokens,
            @JsonProperty("input_tokens_details") InputTokensDetails inputTokensDetails,
            @JsonProperty("output_tokens_details") OutputTokensDetails outputTokensDetails) {}

    /**
     * Input token breakdown reported by the Responses API.
     *
     * @param cachedTokens input tokens served from the automatic prompt cache.
     */
    @JsonModel
    public record InputTokensDetails(
            @JsonProperty("cached_tokens") Long cachedTokens) {}

    /**
     * Output token breakdown reported by the Responses API.
     *
     * @param reasoningTokens output tokens spent on internal reasoning, already counted in
     *        {@code output_tokens}.
     */
    @JsonModel
    public record OutputTokensDetails(
            @JsonProperty("reasoning_tokens") Long reasoningTokens) {}
}
