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
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;

/**
 * Anthropic models listing.
 *
 * @param data available models.
 * @param hasMore whether another page follows.
 * @param firstId first model identifier of the page.
 * @param lastId last model identifier of the page.
 */
@JsonModel
public record AnthropicModels(
        List<Model> data,
        @JsonProperty("has_more") Boolean hasMore,
        @JsonProperty("first_id") String firstId,
        @JsonProperty("last_id") String lastId) {

    /**
     * One available model.
     *
     * @param type wire object type, always {@code model}.
     * @param id model identifier to use in requests.
     * @param displayName human readable model name.
     * @param createdAt creation timestamp (ISO-8601).
     * @param maxInputTokens maximum input context size.
     * @param maxTokens maximum output size.
     */
    @JsonModel
    public record Model(
            String type,
            String id,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("max_input_tokens") Integer maxInputTokens,
            @JsonProperty("max_tokens") Integer maxTokens) {}
}
