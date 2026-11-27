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
import java.util.List;
import java.util.Map;

/**
 * Streaming chunk of the {@code /chat/completions} endpoint.
 *
 * @param id chunk/session identifier.
 * @param object wire object type, always {@code chat.completion.chunk}.
 * @param created creation timestamp (epoch seconds).
 * @param model model that produced the chunk.
 * @param choices incremental choice updates, usually a single element.
 * @param usage token usage, set on the final chunk when requested.
 * @param extensions unknown wire fields kept as-is.
 */
@JsonModel
public record ChatChunk(
        String id,
        String object,
        Long created,
        String model,
        List<ChunkChoice> choices,
        OpenAIUsage usage,
        @JsonOthers Map<String, Object> extensions) {

    /**
     * Incremental update of one choice.
     *
     * @param index choice index in the response.
     * @param delta content delta for this update.
     * @param finishReason finish reason, set on the last chunk of the choice.
     * @param extensions unknown wire fields kept as-is.
     */
    @JsonModel
    public record ChunkChoice(
            int index,
            ChunkDelta delta,
            @JsonProperty("finish_reason") String finishReason,
            @JsonOthers Map<String, Object> extensions) {}

    /**
     * Content carried by a chunk.
     *
     * @param role message role, usually {@code assistant}.
     * @param content textual delta, when the chunk carries text.
     * @param reasoningContent provider-specific reasoning delta.
     * @param reasoning alternative reasoning delta field.
     * @param toolCalls tool call fragments, when the chunk carries tool calls.
     * @param extensions unknown wire fields kept as-is.
     */
    @JsonModel
    public record ChunkDelta(
            String role,
            Object content,
            @JsonProperty("reasoning_content") String reasoningContent,
            @JsonProperty("reasoning") String reasoning,
            @JsonProperty("tool_calls") List<ChunkToolCall> toolCalls,
            @JsonOthers Map<String, Object> extensions) {}

    /**
     * Tool call fragment: id/type/name on the first fragment of an index, then argument fragments.
     *
     * @param index tool call index this fragment belongs to.
     * @param id tool call identifier, set on the first fragment.
     * @param type tool call type, usually {@code function}.
     * @param function function name and argument fragment.
     */
    @JsonModel
    public record ChunkToolCall(Integer index, String id, String type, Function function) {

        /**
         * Function fragment of a tool call.
         *
         * @param name function name, set on the first fragment.
         * @param arguments JSON argument fragment.
         */
        @JsonModel
        public record Function(String name, String arguments) {}
    }
}
