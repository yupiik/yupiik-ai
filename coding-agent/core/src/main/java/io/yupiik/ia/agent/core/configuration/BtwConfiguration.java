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

@JsonModel
public record BtwConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation = "Model used for /btw side questions. Defaults to the provider model when empty.")
        String model,

        @Property(
                defaultValue = "0",
                documentation =
                        "Maximum output tokens of a /btw answer. 0 uses the provider/model budget (recommended, since reasoning models otherwise exhaust a small forced budget while thinking).")
        int maxTokens,

        @Property(
                defaultValue = "200",
                documentation =
                        "Maximum number of conversation messages inherited by a /btw answer as context (the most recent ones are kept). 0 or negative keeps the whole history.")
        int maxHistoryMessages,

        @Property(
                defaultValue = "5",
                documentation =
                        "Number of previous /btw questions and answers replayed to the model so follow-up side questions stay coherent (memory only, never persisted). 0 disables the replay.")
        int recall,

        @Property(
                defaultValue = "false",
                documentation =
                        "Whether a /btw side question may additionally run the plan-mode read-only tools (file/command readers, searches, and the read-only shell commands) to inspect the project while answering. Default false: /btw is a single generation that never executes anything - it reuses the session's system prompt and the full wire tool schema so the provider prompt cache stays byte-identical with the main agent loop, and the model is directed through the prompt to answer directly and never act. A model that still reaches for a tool (native or in-band text markup) is never executed and is surfaced as a retryable note instead of a silent truncated answer. Turn on this flag to also let /btw actually run the read-only plan-mode tools ('let /btw read the project').")
        boolean tools) {}
