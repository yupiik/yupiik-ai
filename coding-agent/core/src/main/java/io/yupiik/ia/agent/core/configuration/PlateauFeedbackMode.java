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
 * Feedback strategy when the exploration-plateau guard approaches its stop limit: whether the model
 * is nudged once (a strategy-change user message) before the turn is hard-stopped, and how early.
 *
 * <p>The plateau guard stops a turn whose read-only tool rounds re-cover only already-seen
 * files/results (novel signatures, redundant targets). A tiny nudge at {@code limit-1} or
 * {@code limit-2} lets a model that is merely being inefficient adapt (stop re-reading, use what it
 * already has, batch reads) instead of losing the whole turn; if it keeps spinning the turn is
 * stopped at the limit exactly as with {@link #NONE}.</p>
 */
@JsonModel
public enum PlateauFeedbackMode {
    /** Never nudge: hard stop at the plateau limit. */
    NONE,
    /** Nudge once when the plateau streak reaches {@code limit-1}, then hard stop at the limit. */
    NUDGE_1,
    /** Nudge once when the plateau streak reaches {@code limit-2}, then hard stop at the limit. */
    NUDGE_2;

    public static PlateauFeedbackMode fromConfigurationString(final String value) {
        if (value == null || value.isBlank()) {
            return NUDGE_2;
        }
        return switch (strip(value)) {
            case "none" -> NONE;
            case "nudge", "nudge_1" -> NUDGE_1;
            default -> NUDGE_2;
        };
    }

    private static String strip(final String value) {
        return value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
