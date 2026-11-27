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

/**
 * OpenAI responses API tool declaration (flat, unlike the nested chat completions one).
 *
 * @param type tool type, usually {@code function}.
 * @param name function name the model can call.
 * @param description what the function does.
 * @param parameters JSON schema of the function arguments.
 * @param strict whether the provider enforces strict schema validation.
 */
@JsonModel
public record ResponsesTool(String type, String name, String description, Object parameters, Boolean strict) {}
