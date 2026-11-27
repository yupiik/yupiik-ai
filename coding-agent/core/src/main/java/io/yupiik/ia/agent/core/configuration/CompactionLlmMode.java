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
import java.util.Locale;

/**
 * Compaction LLM mode selector ({@code yca.compaction.mode}, per-provider
 * {@code providers.<id>.compaction.mode}). The tri-state keeps the explicit per-provider opt-out
 * of a global {@code NO_LLM} expressible: a plain Boolean cannot, because the configuration
 * framework materializes a default-filled per-provider block, binding an absent Boolean to
 * {@code false} and making it indistinguishable from an explicit opt-out.
 */
@JsonModel
public enum CompactionLlmMode {
    @Property(
            documentation = "Inherit: for a per-provider block, the global yca.compaction.mode applies; for the global "
                    + "configuration, resolves to \"llm\" (the built-in behavior).")
    AUTO,

    @Property(
            documentation = "LLM-backed compaction: the summarizer model (backgroundModelOverrides.compact, then "
                    + "yca.model.compact, then the session model) folds old messages into a digest.")
    LLM,

    @Property(
            documentation = "LLM-free compaction: automatic, gate, overflow and manual compactions run only local "
                    + "strategies (prune, tool-output splice/spill, dedup/supersede/purge when enabled) "
                    + "and never call a model. History is spliced instead of folded into a digest and "
                    + "grows monotonically. Speculative compaction is always off in this mode.")
    NO_LLM;

    /** Parses the configuration string; blank/unrecognized values inherit (AUTO). */
    public static CompactionLlmMode fromConfigurationString(final String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "llm", "on", "true" -> LLM;
            case "no_llm", "off", "false" -> NO_LLM;
            default -> AUTO;
        };
    }

    /** Whether this (already-resolved) mode means LLM-free compaction; AUTO resolves to the LLM behavior. */
    public boolean llmFree() {
        return this == NO_LLM;
    }
}
