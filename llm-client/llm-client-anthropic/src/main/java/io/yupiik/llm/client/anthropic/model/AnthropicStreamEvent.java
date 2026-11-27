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
import java.util.Map;

/**
 * Anthropic Messages streaming SSE event.
 *
 * @param type event type: {@code message_start}, {@code content_block_start}, {@code content_block_delta},
 *        {@code content_block_stop}, {@code message_delta} or {@code message_stop}.
 * @param message full message snapshot, for {@code message_start}.
 * @param index index of the content block the event relates to.
 * @param contentBlock content block opened by the event, for {@code content_block_start}.
 * @param delta incremental content, for delta events.
 * @param usage cumulative token usage, for {@code message_start}/{@code message_delta}.
 * @param extensions unknown wire fields kept as-is.
 */
@JsonModel
public record AnthropicStreamEvent(
        String type,
        AnthropicMessagesResponse message,
        Integer index,
        @JsonProperty("content_block") AnthropicMessagesResponse.ContentBlock contentBlock,
        Delta delta,
        AnthropicMessagesResponse.AnthropicUsage usage,
        @JsonOthers Map<String, Object> extensions) {

    /**
     * Incremental content of a delta event.
     *
     * @param type delta type: {@code text_delta}, {@code thinking_delta}, {@code input_json_delta} or
     *        {@code stop_reason_delta}.
     * @param text textual fragment, for {@code text_delta}.
     * @param thinking reasoning fragment, for {@code thinking_delta}.
     * @param partialJson JSON argument fragment, for {@code input_json_delta}.
     * @param stopReason updated stop reason, for {@code stop_reason_delta}.
     * @param stopSequence updated stop sequence, for {@code stop_reason_delta}.
     * @param extensions unknown wire fields kept as-is.
     */
    @JsonModel
    public record Delta(
            String type,
            String text,
            String thinking,
            @JsonProperty("partial_json") String partialJson,
            @JsonProperty("stop_reason") String stopReason,
            @JsonProperty("stop_sequence") String stopSequence,
            @JsonOthers Map<String, Object> extensions) {}
}
