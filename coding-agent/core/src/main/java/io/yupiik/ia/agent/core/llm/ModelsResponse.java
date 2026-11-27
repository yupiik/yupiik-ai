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
package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import io.yupiik.fusion.json.pointer.GenericJsonPointer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonModel
public record ModelsResponse(List<Model> data) {

    /** Context window used when neither the provider configuration nor the /v1/models vendor extensions provide one. */
    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;

    /**
     * Conservative context window assumed when no configuration, no provider metadata and no
     * vendor extension provide a figure. Kept intentionally low (much lower than the permissive
     * {@link #DEFAULT_CONTEXT_WINDOW}) because guessing too high on a small-window model turns
     * every request into a {@code ContextWindowExceededException}. It is the "unknown window"
     * default, not a claim about any particular model.
     */
    public static final int AUTO_DETECTED_BASELINE = 32_000;

    public List<String> ids() {
        return data == null
                ? List.of()
                : data.stream()
                        .map(Model::id)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .sorted()
                        .toList();
    }

    /**
     * Categorizes the provenance of a token count for budget decisions.
     */
    @JsonModel
    public enum SourceType {
        /** Explicit configuration in provider/model settings */
        CONFIGURED,
        /** Hard limit from vendor extensions (context_window, max_input_tokens, max_tokens, max_completion_tokens, top_provider/*, max_output_tokens) */
        DETECTED_ABSOLUTE,
        /** Runtime/training metadata that may not reflect active limits (meta.n_ctx_train, max_output_length, output_token_limit) */
        DETECTED_RELATIVE,
        /** Conservative fallback when nothing is known */
        AUTO_BASELINE,
        /** Derived from overflow adjustment */
        OVERFLOW_ADJUSTED
    }

    /**
     * A token count with metadata about its provenance and whether it represents an absolute
     * hard limit (verified constraint) or a relative/estimated value (fallback, training metadata,
     * duplicated legacy field, etc.).
     */
    @JsonModel
    public record TokenCount(int value, boolean absolute, SourceType sourceType, String sourcePointer) {}

    @JsonModel
    public record Model(
            String id,
            @JsonProperty("owned_by") String ownedBy,
            @JsonOthers Map<String, Object> others) {

        /**
         * Best effort context window extraction from the vendor extensions: flat keys first, then
         * the llama.cpp effective {@code meta.n_ctx}, then {@code meta.n_ctx_train}. The runtime
         * {@code meta.n_ctx} is preferred over the training value because the effective KV cache
         * can be far smaller than {@code n_ctx_train}; when only the training window is present it
         * is still used as a best-effort upper bound rather than assuming a huge default.
         * Anthropic exposes it as {@code max_input_tokens}. Detected values are totals
         * (prompt + completion combined). Gateways that nest the window under a provider
         * object (e.g. {@code top_provider} on OpenRouter/Kilo) are read as a fallback.
         *
         * Absolute=true for documented hard limits (context_window, max_input_tokens, top_provider/context_length)
         * and for the llama.cpp runtime KV-cache slot (meta.n_ctx), which the server enforces as a hard limit.
         * Absolute=false for training metadata (meta.n_ctx_train), which is only an upper bound and may
         * overstate the real runtime window.
         */
        public TokenCount contextWindow() {
            // Tier 1: documented hard limits (absolute)
            final var tier1 = firstPositive(
                    "/context_window",
                    "/model_context_window",
                    "/context_length",
                    "/top_provider/context_length",
                    "/max_model_len",
                    "/max_input_tokens");
            if (tier1 != null) {
                return new TokenCount(tier1.value(), true, SourceType.DETECTED_ABSOLUTE, tier1.pointer());
            }
            // Tier 2: runtime KV-cache slot (llama.cpp `-c`): a hard enforced limit, requests exceeding it reject
            final var tier2 = firstPositive("/meta/n_ctx");
            if (tier2 != null) {
                return new TokenCount(tier2.value(), true, SourceType.DETECTED_ABSOLUTE, tier2.pointer());
            }
            // Tier 3: training context only (relative/estimated) - may overstate the effective runtime window
            final var tier3 = firstPositive("/meta/n_ctx_train");
            if (tier3 != null) {
                return new TokenCount(tier3.value(), false, SourceType.DETECTED_RELATIVE, tier3.pointer());
            }
            return null;
        }

        /**
         * Best effort max output tokens (the cap for the {@code max_tokens} parameter) extracted
         * from the vendor extensions, or null when unknown. Anthropic exposes it as
         * {@code max_tokens}; OpenAI models occasionally set {@code max_completion_tokens};
         * sglang gateways (and some vLLM deploys) use {@code max_output_length};
         * LiteLLM exposes {@code max_output_tokens}. All are token counts.
         * Gateways that nest the cap under {@code top_provider.max_completion_tokens}
         * (Kilo/OpenRouter style) are read as a fallback.
         *
         * Absolute=true for documented output limits (max_tokens, max_completion_tokens,
         * top_provider/max_completion_tokens, max_output_tokens).
         * Absolute=false for gateway-specific fields that may be derived/duplicated
         * (max_output_length, output_token_limit).
         */
        public TokenCount maxTokens() {
            // Tier 1: documented output limits (absolute)
            final var tier1 = firstPositive(
                    "/max_tokens",
                    "/max_completion_tokens",
                    "/top_provider/max_completion_tokens",
                    "/max_output_tokens");
            if (tier1 != null) {
                return new TokenCount(tier1.value(), true, SourceType.DETECTED_ABSOLUTE, tier1.pointer());
            }
            // Tier 2: gateway-specific / possibly derived fields (relative)
            final var tier2 = firstPositive("/output_token_limit", "/max_output_length");
            if (tier2 != null) {
                return new TokenCount(tier2.value(), false, SourceType.DETECTED_RELATIVE, tier2.pointer());
            }
            return null;
        }

        /**
         * The catalog default reasoning effort ({@code default_reasoning_level}) advertised by
         * OpenAI-compatible model listings, or null when absent or malformed.
         */
        public String defaultReasoningLevel() {
            final var value = tryLookup("/default_reasoning_level");
            return value instanceof final String level && !level.isBlank() ? level.trim() : null;
        }

        /**
         * The catalog reasoning efforts ({@code supported_reasoning_levels}) advertised by
         * OpenAI-compatible model listings. Both a list of strings and a list of objects carrying an
         * {@code effort} (plus an optional {@code description}) are accepted. Empty when absent or
         * malformed so callers keep their existing fallback behavior.
         */
        public List<String> supportedReasoningLevels() {
            final var value = tryLookup("/supported_reasoning_levels");
            if (!(value instanceof final List<?> levels)) {
                return List.of();
            }
            final var efforts = new ArrayList<String>(levels.size());
            for (final var level : levels) {
                final String effort;
                if (level instanceof final String text) {
                    effort = text;
                } else if (level instanceof final Map<?, ?> object
                        && object.get("effort") instanceof final String text) {
                    effort = text;
                } else {
                    effort = null;
                }
                if (effort != null && !effort.isBlank()) {
                    efforts.add(effort.trim());
                }
            }
            return List.copyOf(efforts);
        }

        private Hit firstPositive(final String... pointers) {
            for (final var pointer : pointers) {
                final var value = tryLookup(pointer);
                if (value instanceof final Number number && number.intValue() > 0) {
                    return new Hit(number.intValue(), pointer);
                }
            }
            return null;
        }

        /** A positive vendor-extension hit with the pointer it came from. */
        private record Hit(int value, String pointer) {}

        private Object tryLookup(final String pointer) {
            if (others == null) {
                return null;
            }
            try {
                return new GenericJsonPointer(pointer).apply(others);
            } catch (final RuntimeException e) {
                return null;
            }
        }
    }
}
