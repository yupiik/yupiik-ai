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
public record ModelConfiguration(
        @Property(
                documentation = "Model + tuning used to compact the context. `name` is the model (eg. "
                        + "\\\"qwen3-0.6b\\\"); this is the model name on this background path, "
                        + "NOT a display variant qualifier. `reasoningEffort`=\\\"none\\\" disables "
                        + "thinking on compaction, `maxTokens` caps its output, `temperature` "
                        + "overrides sampling. When `name` is blank the compact model falls back "
                        + "to the provider model.")
        ModelVariantConfiguration compact,

        @Property(
                documentation = "Model + tuning used to prune the context. `name` is the model name on this "
                        + "background path (eg. \\\"qwen3-0.6b\\\"). `reasoningEffort`=\\\"none\\\" "
                        + "disables thinking on pruning. `maxTokens` caps its output, `temperature` "
                        + "overrides sampling. When `name` is blank the compact model is used, then "
                        + "the provider model when both are blank.")
        ModelVariantConfiguration prune,

        @Property(
                documentation = "Model + tuning used for the memory write path (extraction and rewrite). `name` "
                        + "is the model name on this background path (eg. \\\"qwen3-0.6b\\\"). "
                        + "`reasoningEffort`=\\\"none\\\" disables thinking on memory, `maxTokens` caps "
                        + "its output, `temperature` overrides sampling. When `name` is blank the "
                        + "compact model is used, then the provider model.")
        ModelVariantConfiguration memory) {

    /** The model name to use for pruning, defaulting to the compact model name when blank. */
    public String resolvedPrune() {
        return modelName(prune, compact);
    }

    /** The model name to use for memory writes, defaulting to the compact model name when blank. */
    public String resolvedMemory() {
        return modelName(memory, compact);
    }

    /** The compact tuning (may be null when unset). */
    public ModelVariantConfiguration compactTuning() {
        return compact;
    }

    /** The prune tuning, defaulting to the compact tuning when the prune one is unset. */
    public ModelVariantConfiguration pruneTuning() {
        return prune == null ? compact : prune;
    }

    /** The memory tuning, defaulting to the compact tuning when the memory one is unset. */
    public ModelVariantConfiguration memoryTuning() {
        return memory == null ? compact : memory;
    }

    private static String modelName(final ModelVariantConfiguration primary, final ModelVariantConfiguration fallback) {
        if (primary != null && modelName(primary.name()) != null) {
            return primary.name();
        }
        if (fallback != null && modelName(fallback.name()) != null) {
            return fallback.name();
        }
        return "";
    }

    /**
     * The effective background model name for a configured variant, or null when the variant is
     * unset or carries only the default placeholder ({@code name} defaults to {@code "default"} on
     * the shared {@link ModelVariantConfiguration}, which must not be mistaken for a real model).
     */
    private static String modelName(final String name) {
        if (name == null || name.isBlank() || "default".equals(name.strip())) {
            return null;
        }
        return name.strip();
    }
}
