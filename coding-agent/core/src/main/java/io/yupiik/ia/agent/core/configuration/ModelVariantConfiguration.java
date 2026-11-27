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
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

/**
 * A named tuning preset a user can pick at runtime (eg. {@code idle}, {@code balanced},
 * {@code reasoning}). When a facet is unset it defers to the model default then to the implicit
 * scheme. Highly inspired from the {code variants} concept of opencode.
 */
@JsonModel
public record ModelVariantConfiguration(
        @Property(
                defaultValue = "\"default\"",
                documentation = "Name of this variant, referenced by the {code /model <name> <variant>} command. "
                        + "On the background compact/prune/memory paths of yca.model the value is the "
                        + "MODEL name (not a display qualifier): the other fields then tune that "
                        + "background model (reasoningEffort, maxTokens, temperature). Elsewhere it is "
                        + "the variant qualifier of the selected model.")
        String name,

        @Property(
                defaultValue = "null",
                documentation =
                        "Sampling temperature for this variant (eg. 1.0). Blank defers to the model default (or to the implicit per-family scheme).")
        Double temperature,

        @JsonProperty("top_p")
        @Property(
                value = "top_p",
                defaultValue = "null",
                documentation = "Nucleus sampling cutoff (top_p) for this variant. Blank defers to the model default.")
        Double topP,

        @JsonProperty("top_k")
        @Property(
                value = "top_k",
                defaultValue = "null",
                documentation = "Top-k sampling cutoff (top_k) for this variant. Blank defers to the model default.")
        Double topK,

        @JsonProperty("max_tokens")
        @Property(
                value = "max_tokens",
                defaultValue = "-1",
                documentation =
                        "Maximum output tokens for this variant, mapped per provider flavor (max_completion_tokens for OpenAI, max_tokens elsewhere). Unset or zero/negative keeps the budget policy default.")
        Integer maxTokens,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Reasoning effort hint for this variant (eg. low, medium or high), applied put-if-absent. Blank defers to the tuning/model default.")
        String reasoningEffort,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Optional raw JSON object merged (put-if-absent) into the request payload for this variant (eg. {\"thinking\":{\"type\":\"enabled\"}}). Empty is ignored.")
        String extra) {}
