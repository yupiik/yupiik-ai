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
 * Working-context guidance limits of the model-driven compression (compress tool / nudges).
 *
 * <p>The global {@link CompressConfiguration#contextLimit()} carries the defaults below; the
 * per-provider {@link ProviderContextLimit} mirrors the shape with null fields meaning "inherit
 * the global value". {@link #resolveMin(int)} / {@link #resolveMax(int)} resolve a field to a
 * plain token count or a percentage of the window.</p>
 */
@JsonModel
public record ContextLimit(
        @Property(
                defaultValue = "\"50000\"",
                documentation = "Soft working-context limit (estimated tokens after compression): below it no "
                        + "compression nudge is emitted. Accepts a plain token count or a percentage "
                        + "string like \"40%\" of the context window.")
        String min,

        @Property(
                defaultValue = "\"100000\"",
                documentation = "Hard working-context limit (estimated tokens after compression) above which the "
                        + "model is strongly told to compress. Accepts a plain token count or a "
                        + "percentage string like \"80%\" of the context window.")
        String max) {

    /** Default soft limit: below it no compression nudge is emitted. */
    public static final String DEFAULT_MIN = "50000";

    /** Default hard limit: above it the strong compression nudge fires. */
    public static final String DEFAULT_MAX = "100000";

    /** The resolved soft target for a window. */
    public int resolveMin(final int window) {
        return resolveLimit(min, window, Integer.parseInt(DEFAULT_MIN));
    }

    /** The resolved hard target for a window. */
    public int resolveMax(final int window) {
        return resolveLimit(max, window, Integer.parseInt(DEFAULT_MAX));
    }

    /** Resolves a token limit expressed as a plain number or as a percentage of {@code window}. */
    private static int resolveLimit(final String value, final int window, final int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        final var stripped = value.strip();
        try {
            if (stripped.endsWith("%")) {
                final var percent = Double.parseDouble(
                        stripped.substring(0, stripped.length() - 1).strip());
                return window <= 0 ? fallback : (int) Math.round(window * Math.clamp(percent, 0, 100) / 100.0);
            }
            return Integer.parseInt(stripped);
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }
}
