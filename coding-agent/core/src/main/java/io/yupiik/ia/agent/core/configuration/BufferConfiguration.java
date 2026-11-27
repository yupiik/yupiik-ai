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
 * Buffer-mode trigger knobs of the automatic compaction.
 *
 * <p>The global {@link CompactionConfiguration#buffer()} carries the defaults below; the
 * per-provider {@link ProviderBufferConfiguration} mirrors the shape with null fields meaning
 * "inherit the global value".</p>
 */
@JsonModel
public record BufferConfiguration(
        @Property(
                defaultValue = "15",
                documentation = "Buffer mode trigger: compact when the estimated prompt reaches this percentage of the "
                        + "raw context window. Matches opencode's default 15% buffer.")
        int percent,

        @Property(
                defaultValue = "16000",
                documentation =
                        "Buffer mode trigger floor: the reserved headroom is at least this many tokens, so small "
                                + "windows keep a usable safety margin. Matches opencode's 16k minimum buffer.")
        int minTokens,

        @Property(
                defaultValue = "90",
                documentation = "Buffer mode safety cap: the compaction trigger never exceeds this percentage of the "
                        + "usable window, so automatic compaction always fires before the context gauge "
                        + "reaches 100%. A large declared output cap can push the raw opencode-style "
                        + "buffer trigger above the usable window; without this cap the trigger clamps "
                        + "exactly onto the usable window and compaction only starts at the very edge. "
                        + "100 keeps the legacy clamp behavior; values below 100 add real headroom.")
        int safetyPercent) {

    /** Default reserved headroom percentage (15 by default). */
    public static final int DEFAULT_PERCENT = 15;

    /** Default reserved headroom floor in tokens (16000 by default). */
    public static final int DEFAULT_MIN_TOKENS = 16_000;

    /** Default safety cap percentage of the usable window (90 by default). */
    public static final int DEFAULT_SAFETY_PERCENT = 90;

    public BufferConfiguration {
        if (safetyPercent < 1 || safetyPercent > 100) {
            throw new IllegalArgumentException("safetyPercent must be between 1 and 100");
        }
        if (percent < 0) {
            throw new IllegalArgumentException("percent must be zero or positive");
        }
        if (minTokens < 0) {
            throw new IllegalArgumentException("minTokens must be zero or positive");
        }
    }
}
