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
package io.yupiik.llm.client.model;

import io.yupiik.fusion.json.pointer.GenericJsonPointer;
import java.util.Map;

/**
 * A single model descriptor. {@link #contextWindow()} and {@link #maxTokens()} are best-effort
 * heuristics resolving vendor-specific capability fields.
 *
 * @param id the model id.
 * @param provider the provider that reported the model, may be {@code null}.
 * @param created the creation epoch second, may be {@code null}.
 * @param ownedBy the owning organization, may be {@code null}.
 * @param others the raw vendor-specific capability fields.
 */
public record LlmModel(String id, String provider, Long created, String ownedBy, Map<String, Object> others) {

    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    public static final int AUTO_DETECTED_BASELINE = 32_000;

    /** Defaults {@code others} to an empty map when absent. */
    public LlmModel {
        others = others == null ? Map.of() : others;
    }

    /**
     * Best effort context window detection: flat capability keys first, then the llama.cpp
     * effective {@code meta.n_ctx}, then {@code meta.n_ctx_train}. The runtime {@code meta.n_ctx}
     * is preferred over the training value because the effective KV cache can be far smaller than
     * {@code n_ctx_train}. Falls back to {@link #DEFAULT_CONTEXT_WINDOW}.
     *
     * @return the detected context window in tokens.
     */
    public int contextWindow() {
        final var positive = firstPositive(
                "context_window",
                "model_context_window",
                "context_length",
                "max_model_len",
                "max_input_tokens",
                "context_window_size",
                "meta.n_ctx");
        if (positive != null) {
            return positive;
        }
        final var trained = find("meta.n_ctx_train");
        if (trained != null) {
            return number(trained, DEFAULT_CONTEXT_WINDOW);
        }
        return DEFAULT_CONTEXT_WINDOW;
    }

    /**
     * Best effort model max output tokens, falling back to the context window baseline.
     *
     * @return the max output tokens.
     */
    public int maxTokens() {
        return number(
                find(
                        "max_tokens",
                        "max_completion_tokens",
                        "output_token_limit",
                        "max_output_length",
                        "max_output_tokens",
                        "meta.n_ctx_train"),
                AUTO_DETECTED_BASELINE);
    }

    private Integer firstPositive(final String... keys) {
        for (final var key : keys) {
            final var value = find(key);
            if (value instanceof final Number number && number.intValue() > 0) {
                return number.intValue();
            }
        }
        return null;
    }

    private Object find(final String... keys) {
        for (final var key : keys) {
            final var value = lookup(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Object lookup(final String key) {
        if (key.contains("/")) {
            try {
                return new GenericJsonPointer(key).apply(others);
            } catch (final RuntimeException e) {
                return null;
            }
        }
        return others.get(key);
    }

    private static int number(final Object value, final int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }
}
