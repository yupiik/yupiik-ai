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
 * A per-model configuration entry of a provider. The {@code model} id is matched exactly first,
 * then as a substring of the model name ({@code claude} matches {@code claude-sonnet-5}).
 * Everything is nullable so each facet falls back to the provider default then to the implicit
 * value-based scheme.
 */
@JsonModel
public record ProviderModelConfiguration(
        @Property(
                documentation =
                        "Model id matched exactly, then as a substring of the actual model name (eg. claude matches claude-sonnet-5). The entry applies to the first matching model: an exact id wins over a substring match. The list order also defines the implicit display scheme within the provider (the first two listed models are bold).")
        String model,

        @Property(documentation = "Display style (color and/or bold) override for this model. Nullable facets.")
        ModelStyleConfiguration style,

        @Property(
                documentation =
                        "Tuning defaults (reasoning effort, output token budget, variants and provider specific options) for this model. Nullable facets keep the provider defaults.")
        ModelTuningConfiguration behavior,

        @Property(
                defaultValue = "0",
                documentation =
                        "Context window size in tokens for this model, overriding the provider contextWindow value. "
                                + "Zero or negative (the default) continues the resolution chain: provider contextWindow, "
                                + "then the value detected from the provider /v1/models (context_length, max_model_len, ...), "
                                + "then the conservative 32000 baseline. Useful on providers serving models of very different "
                                + "windows (eg. a 32k qwen and a 1M deepseek on one base URL). Detected values are totals "
                                + "(prompt + completion combined).")
        int contextWindow) {}
