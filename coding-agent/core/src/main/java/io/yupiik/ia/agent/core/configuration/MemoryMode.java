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
 * When the durable memory is flushed to disk. The prompt prefix is built once per
 * session and never re-read mid-session, so this governs only the <em>durability</em>
 * cadence (how eagerly newly extracted facts reach {@code MEMORY.md} for the next
 * session), not whether a mid-session fact is visible to the current turn (it already
 * lives in the conversation it was mined from).
 */
@JsonModel
public enum MemoryMode {
    /**
     * Pick the regime from the routing: when the memory model resolves to the same
     * model as the current turn (expensive), extract rarely ({@link #LAZY}); when it
     * routes to a distinct/cheaper model, extract eagerly ({@link #FULL}). The
     * machine picks the default so neither extreme is forced on every configuration.
     */
    AUTO,

    /** Eager extraction: the every-N-turns cadence plus the todo-completion flush. */
    FULL,

    /** Rare extraction: only at a natural milestone (all todos done) or session close. */
    LAZY;

    /** Case-insensitive parse for the configuration wiring, defaulting to {@link #AUTO}. */
    public static MemoryMode fromConfigurationString(final String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return AUTO;
        }
    }
}
