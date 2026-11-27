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
 * Response of the {@code /chat/completions} endpoint.
 *
 * @param id completion identifier.
 * @param object wire object type, always {@code chat.completion}.
 * @param created creation timestamp (epoch seconds).
 * @param model model that produced the completion.
 * @param choices generated choices.
 * @param usage token usage, when requested.
 * @param extensions unknown wire fields kept as-is.
 */
@JsonModel
public record ChatCompletionResponse(
        String id,
        String object,
        Long created,
        String model,
        List<Choice> choices,
        OpenAIUsage usage,
        @JsonOthers Map<String, Object> extensions) {

    /**
     * One generated choice.
     *
     * @param index choice index in the response.
     * @param message generated message.
     * @param finishReason why the generation stopped, e.g. {@code stop} or {@code tool_calls}.
     * @param extensions unknown wire fields kept as-is.
     */
    @JsonModel
    public record Choice(
            int index,
            WireMessage message,
            @JsonProperty("finish_reason") String finishReason,
            @JsonOthers Map<String, Object> extensions) {}
}
