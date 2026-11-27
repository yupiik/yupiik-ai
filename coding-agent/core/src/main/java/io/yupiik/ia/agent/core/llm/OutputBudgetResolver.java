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

/**
 * Computes the per-request output token budget (the {@code max_tokens} parameter for providers
 * that require it, such as Anthropic).
 *
 * <p>Resolution order: an explicit {@code max_tokens} on the request wins, then a per-model
 * override configured on the provider, then the per-provider default, then a value detected from
 * the provider /v1/models, then {@link #DEFAULT_MAX_TOKEN} (mirroring opencode's
 * {@code min(model.limit.output, 32000) || 32000} cap-and-fallback). The result is clamped down to
 * leave room inside a known, smaller context window.</p>
 */
public final class OutputBudgetResolver {
    /** Hard cap and fallback for the output budget, matching opencode's OUTPUT_TOKEN_MAX. */
    public static final int DEFAULT_MAX_TOKEN = 32_000;

    private OutputBudgetResolver() {
        // utility
    }

    /**
     * @param requested explicit {@code max_tokens} on the request (nullable)
     * @param configuredPerModel positive per-model override, or 0/negative when unset
     * @param configuredPerProvider positive per-provider default, or 0/negative when unset
     * @param detected value detected from /v1/models, or null/positive when known
     * @param contextWindow total context window in tokens, or null/positive when known
     */
    public static int resolve(
            final Integer requested,
            final int configuredPerModel,
            final int configuredPerProvider,
            final Integer detected,
            final Integer contextWindow) {
        return resolveTracked(requested, configuredPerModel, configuredPerProvider, detected, contextWindow)
                .budget();
    }

    /**
     * Same resolution as {@link #resolve} but also reports where the value comes from, for
     * observability.
     *
     * @param requested explicit {@code max_tokens} on the request (nullable)
     * @param configuredPerModel positive per-model override, or 0/negative when unset
     * @param configuredPerProvider positive per-provider default, or 0/negative when unset
     * @param detected value detected from /v1/models, or null/positive when known
     * @param contextWindow total context window in tokens, or null/positive when known
     */
    public static Decision resolveTracked(
            final Integer requested,
            final int configuredPerModel,
            final int configuredPerProvider,
            final Integer detected,
            final Integer contextWindow) {
        int budget;
        String source;
        if (requested != null && requested > 0) {
            budget = requested;
            source = "request";
        } else if (configuredPerModel > 0) {
            budget = configuredPerModel;
            source = "configured-per-model";
        } else if (configuredPerProvider > 0) {
            budget = configuredPerProvider;
            source = "configured-per-provider";
        } else if (detected != null && detected > 0) {
            budget = detected;
            source = "detected";
        } else {
            budget = DEFAULT_MAX_TOKEN;
            source = "fallback-" + DEFAULT_MAX_TOKEN;
        }
        if (contextWindow != null && contextWindow > 0 && budget > contextWindow - 1) {
            return new Decision(Math.max(1, contextWindow - 1), source + " clamped to context window");
        }
        return new Decision(Math.max(1, budget), source);
    }

    /** Resolved output budget and the origin the value was taken from. */
    public record Decision(int budget, String source) {}
}
