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
package io.yupiik.llm.client.model;

/**
 * A tool exposed to the model. {@code parameters} is a JSON schema object describing the
 * accepted arguments.
 *
 * @param name tool name.
 * @param description tool description.
 * @param parameters JSON schema of the accepted arguments.
 * @param strict whether the provider should enforce the schema.
 */
public record LlmTool(String name, String description, Object parameters, Boolean strict) {

    /**
     * A tool whose schema is not strictly enforced.
     *
     * @param name the tool name.
     * @param description the tool description.
     * @param parameters the JSON schema of the accepted arguments.
     * @return the tool.
     */
    public static LlmTool of(final String name, final String description, final Object parameters) {
        return new LlmTool(name, description, parameters, null);
    }
}
