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
 * Anthropic Messages wire request.
 *
 * @param model model to run.
 * @param maxTokens upper bound on generated tokens (required by the API).
 * @param system system prompt, string or list of content blocks.
 * @param messages conversation so far.
 * @param temperature sampling temperature.
 * @param topP nucleus sampling cutoff.
 * @param topK top-k sampling cutoff.
 * @param stopSequences stop sequences ending the generation.
 * @param stream when true the answer is streamed as server sent events.
 * @param tools the model can call.
 * @param toolChoice tool selection policy (e.g. {"type":"auto"}).
 * @param metadata request metadata (user id, ...).
 * @param extensions unknown wire fields kept as-is.
 */
@JsonModel
public record AnthropicMessagesRequest(
        String model,
        @JsonProperty("max_tokens") Integer maxTokens,
        Object system,
        List<AnthropicMessage> messages,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("top_k") Integer topK,
        @JsonProperty("stop_sequences") List<String> stopSequences,
        Boolean stream,
        List<AnthropicTool> tools,
        @JsonProperty("tool_choice") Map<String, Object> toolChoice,
        Map<String, Object> metadata,
        @JsonOthers Map<String, Object> extensions) {}
