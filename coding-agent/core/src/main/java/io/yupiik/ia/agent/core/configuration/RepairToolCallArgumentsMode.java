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

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.Locale;

/**
 * Whether tool call arguments emitted by the model are repaired before being exposed / stored.
 *
 * <p>Some OpenAI compatible backends (llama.cpp / ollama style, NVIDIA, ...) occasionally emit JSON
 * arguments with unescaped control characters (literal tabs/newlines inside string values), a
 * dangling tail or trailing free text after the closed object when the generation is cut short
 * under a heavy context, and reject the whole turn with an HTTP 400/500 ("trailing characters" /
 * "Failed to parse tool call arguments as JSON"). The repair pass escapes control chars and trims
 * trailing garbage, but never invents values: when the payload stays unparseable it is left
 * untouched. Repair is low-cost: valid payloads short-circuit on a strict parse and are never
 * modified.</p>
 */
@JsonModel
public enum RepairToolCallArgumentsMode {
    /** Never repair. */
    DISABLED,
    /** Always repair (best effort, unparseable payloads are left untouched). */
    ENABLED,
    /**
     * Repair by default: enabled for any effective model that looks like a Qwen 27B (contains
     * {@code qwen} and {@code 27b}) and for any OpenAI compatible dialect. The full heuristic is
     * resolved by {@code ProviderManager#repairToolCallArguments}, which also enables AUTO for
     * undetermined flavors and keeps the explicitly non-OpenAI anthropic dialect opted out.
     */
    AUTO;

    public static RepairToolCallArgumentsMode fromConfigurationString(final String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return switch (strip(value)) {
            case "enabled" -> ENABLED;
            case "disabled" -> DISABLED;
            default -> AUTO;
        };
    }

    /**
     * Whether the {@link #AUTO} mode should repair for a given effective model name: it activates for
     * any Qwen 27B variant (case-insensitive match on {@code qwen} and {@code 27b}), so
     * {@code qwen3.8-27b}, {@code Qwen3-27B-it} or {@code Qwen2.5-27B-Instruct} all qualify while
     * non-Qwen 27B models (like {@code qwq-27b}), 14B/72B and other families do not.
     */
    public boolean isAutoCandidate(final String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        final var normalized = model.toLowerCase(Locale.ROOT);
        return normalized.contains("qwen") && normalized.contains("27b");
    }

    private static String strip(final String value) {
        return value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
