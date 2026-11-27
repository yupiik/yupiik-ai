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

/**
 * Tool definition sent with a request.
 *
 * @param type tool type, usually {@code function}.
 * @param name function name the model can call.
 * @param description what the function does.
 * @param parameters JSON schema of the function arguments.
 * @param strict whether the provider enforces strict schema validation.
 */
@JsonModel
public record WireTool(
        String type,
        String name,
        String description,
        Object parameters,
        @JsonProperty("strict") Boolean strict) {

    /**
     * Creates a function tool from its metadata.
     *
     * @param name function name the model can call.
     * @param description what the function does.
     * @param parameters JSON schema of the function arguments.
     * @return the wire tool.
     */
    public static WireTool fromName(final String name, final String description, final Object parameters) {
        return new WireTool("function", name, description, parameters, null);
    }

    /**
     * Creates a function tool from its wire function form.
     *
     * @param function function definition.
     * @return the wire tool.
     */
    public static WireTool fromFunction(final WireToolFunction function) {
        return new WireTool(
                "function", function.name(), function.description(), function.parameters(), function.strict());
    }

    /**
     * Function definition usable in a tool.
     *
     * @param name function name the model can call.
     * @param description what the function does.
     * @param parameters JSON schema of the function arguments.
     * @param strict whether the provider enforces strict schema validation.
     */
    @JsonModel
    public record WireToolFunction(String name, String description, Object parameters, Boolean strict) {}
}
