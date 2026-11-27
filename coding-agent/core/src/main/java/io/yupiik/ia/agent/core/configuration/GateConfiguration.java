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
 * Automatic-compaction gate budgets.
 *
 * <p>The global {@link CompactionConfiguration#gate()} carries the defaults below; the per-provider
 * {@link ProviderGateConfiguration} mirrors the shape with null fields meaning "inherit the global
 * value".</p>
 */
@JsonModel
public record GateConfiguration(
        @Property(
                defaultValue = "12",
                documentation = "Maximum compaction-summarizer attempts of the automatic compaction gate before it "
                        + "declares the context cannot be compacted further and escalates to a pinned "
                        + "error (see maxDurationMs). 0 disables the gate (the historical "
                        + "warn-once-and-continue behavior).")
        int maxAttempts,

        @Property(
                defaultValue = "300000",
                documentation =
                        "Wall-clock budget in milliseconds of the automatic compaction gate: once exceeded, even "
                                + "if more attempts remain, the gate escalates to a pinned error telling the user "
                                + "to run /compact urgent. Bounds how long a turn can block on a flaky "
                                + "summarizer. 0 disables the time bound.")
        int maxDurationMs) {

    /** Default maximum summarizer attempts of the gate (12). */
    public static final int DEFAULT_MAX_ATTEMPTS = 12;

    /** Default wall-clock budget of the gate in milliseconds (300000). */
    public static final int DEFAULT_MAX_DURATION_MS = 300_000;

    public GateConfiguration {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be zero or positive");
        }
        if (maxDurationMs < 0) {
            throw new IllegalArgumentException("maxDurationMs must be zero or positive");
        }
    }
}
