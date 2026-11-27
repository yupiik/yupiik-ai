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
 * Request of the {@code /responses} endpoint.
 *
 * @param model model to run.
 * @param input string or list of items ({role, content} messages, function_call, function_call_output).
 * @param instructions system-level instructions for the model.
 * @param maxOutputTokens upper bound on generated tokens.
 * @param temperature sampling temperature.
 * @param topP nucleus sampling cutoff.
 * @param stream when true the completed answer is replayed as server sent events, the upstream call stays single shot.
 * @param tools the model can call.
 * @param toolChoice tool selection policy ({@code auto}, {@code none}, {@code required} or a specific tool).
 * @param text output formatting config, e.g. {"format":{"type":"json_schema","name":...,"schema":...,"strict":...}}.
 * @param store whether the provider stores the response.
 * @param reasoning provider-specific reasoning configuration.
 * @param extensions unknown wire fields kept as-is.
 */
@JsonModel
public record ResponsesRequest(
        String model,
        // string or list of items ({role, content} messages, function_call, function_call_output)
        Object input,
        String instructions,
        @JsonProperty("max_output_tokens") Integer maxOutputTokens,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        // when true the completed answer is replayed as server sent events, the upstream call stays single shot
        Boolean stream,
        List<ResponsesTool> tools,
        @JsonProperty("tool_choice") Object toolChoice,
        // {"format":{"type":"json_schema","name":...,"schema":...,"strict":...}}
        Object text,
        Boolean store,
        Map<String, Object> reasoning,
        @JsonOthers Map<String, Object> extensions) {}
