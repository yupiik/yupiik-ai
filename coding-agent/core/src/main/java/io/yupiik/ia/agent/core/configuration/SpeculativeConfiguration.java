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
 * Speculative-compaction knobs.
 *
 * <p>The global {@link CompactionConfiguration#speculative()} carries the defaults below; the
 * per-provider {@link ProviderSpeculativeConfiguration} mirrors the shape with null fields meaning
 * "inherit the global value", so this record keeps its fields nullable and only the global binding
 * fills {@code defaultValue}s.</p>
 */
@JsonModel
public record SpeculativeConfiguration(
        @Property(
                defaultValue = "\"off\"",
                documentation = "Speculative compaction policy: 'off' (default) keeps the synchronous compaction; "
                        + "'concurrent' lets a background summarizer request compute a compaction "
                        + "candidate before the trigger while foreground inference continues. Only opt in "
                        + "when the effective provider/model routing accepts overlapping requests (a "
                        + "single-slot local server cannot). History is still only ever replaced by the "
                        + "foreground checkpoint, and a failed speculation never touches it.")
        String policy,

        @Property(
                defaultValue = "80",
                documentation =
                        "Percentage of the automatic compaction trigger at which speculative compaction launches "
                                + "(policy=concurrent only); must stay between 1 and 99. Example: with a "
                                + "100k-token trigger and 80, the background summary starts around 80k estimated "
                                + "tokens.")
        Integer startPercent) {

    /** Default speculative policy (off). */
    public static final String DEFAULT_POLICY = "off";

    /** Default speculative launch percentage of the trigger (80). */
    public static final int DEFAULT_START_PERCENT = 80;

    public SpeculativeConfiguration {
        if (policy == null) {
            policy = DEFAULT_POLICY;
        }
        if (startPercent == null) {
            startPercent = DEFAULT_START_PERCENT;
        }
        if (!"off".equals(policy) && !"concurrent".equals(policy)) {
            throw new IllegalArgumentException("speculative policy must be off or concurrent");
        }
        if (startPercent < 1 || startPercent > 99) {
            throw new IllegalArgumentException("speculative startPercent must be between 1 and 99");
        }
    }

    /** Whether the compaction policy is the concurrent (speculative) one. */
    public boolean concurrent() {
        return "concurrent".equals(policy);
    }
}
