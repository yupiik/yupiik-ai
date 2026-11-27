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
 * Request of the {@code /chat/completions} endpoint.
 *
 * @param model model to run.
 * @param messages conversation so far.
 * @param temperature sampling temperature.
 * @param topP nucleus sampling cutoff.
 * @param n number of choices to generate.
 * @param stream when true the answer is streamed as server sent events.
 * @param tools the model can call.
 * @param toolChoice tool selection policy ({@code auto}, {@code none}, {@code required} or a specific tool).
 * @param maxTokens upper bound on generated tokens (legacy).
 * @param maxCompletionTokens upper bound on generated tokens (including reasoning).
 * @param stop stop sequences ending the generation.
 * @param presencePenalty penalty applied to new tokens based on their presence.
 * @param frequencyPenalty penalty applied to new tokens based on their frequency.
 * @param seed seed for (best effort) deterministic sampling.
 * @param user end-user identifier for provider-side tracking.
 * @param reasoningEffort reasoning effort hint, for reasoning models.
 * @param responseFormat output format, e.g. {"type":"json_object"} or {"type":"json_schema","json_schema":{...}}.
 * @param others unknown wire fields kept as-is.
 */
@JsonModel
public record ChatCompletionRequest(
        String model,
        List<WireMessage> messages,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        Integer n,
        Boolean stream,
        List<CompletionsTool> tools,
        @JsonProperty("tool_choice") Object toolChoice,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
        List<String> stop,
        @JsonProperty("presence_penalty") Double presencePenalty,
        @JsonProperty("frequency_penalty") Double frequencyPenalty,
        Integer seed,
        String user,
        @JsonProperty("reasoning_effort") String reasoningEffort,
        // object ({"type":"json_object"} or {"type":"json_schema","json_schema":{...}})
        @JsonProperty("response_format") Object responseFormat,
        // server-side conversation storage opt-out (OpenAI `store`): false = do not retain
        @JsonProperty("store") Boolean store,
        @JsonOthers Map<String, Object> others) {}
