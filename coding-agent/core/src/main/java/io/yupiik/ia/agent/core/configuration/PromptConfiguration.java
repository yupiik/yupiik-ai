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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * Provider-tunable system prompt sections. Unlike {@link ProviderConfiguration#providerSpecificOptions()},
 * which is merged raw into the API payload, these values shape the system prompt the agent is given.
 */
@JsonModel
public record PromptConfiguration(
        @Property(
                defaultValue = "\"auto\"",
                documentation =
                        "Todo and plan guidance mode. 'auto' (the default) injects the todo-guidance tuned for the provider family detected from the base URL, defaulting to OpenAI behavior when the family is unknown. Any other non-empty value is injected verbatim as a todo-guidance appendix (opencode-style). An empty string or the literal 'false' disables the extra guidance entirely.")
        String todo) {}
