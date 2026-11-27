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
package io.yupiik.ia.agent.core.usage;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

/**
 * A decoded snapshot of a Codex account quota usage, mirroring the shape of the Codex CLI
 * {@code wham/usage} / {@code /api/codex/usage} response: the plan type, the metered rate-limit
 * windows and the credit balance. Every field is copied verbatim from the backend response (the
 * backend is the single source of truth, as in the Codex CLI - no value is computed locally).
 */
@JsonModel
public record CodexUsageSnapshot(
        String planType, List<RateLimit> rateLimits, Credits credits, Boolean ordinaryUsageAllowed, String accountId) {

    /** An empty snapshot: no window, no credits, no plan. */
    public static CodexUsageSnapshot empty() {
        return new CodexUsageSnapshot(null, List.of(), Credits.EMPTY, null, null);
    }

    /**
     * One metered rate-limit window. The primary/secondary windows report the used percentage of the
     * plan; additional windows (code review, other pools) follow the same shape.
     *
     * @param label human-readable window label (e.g. "primary"), {@code null} when unknown.
     * @param kind {@code primary}/{@code secondary}/{@code additional}.
     * @param usedPercent the used share of the window, 0-100 (never fabricated).
     * @param limitWindowSeconds the window length in seconds (18000 = 5h, 604800 = 7d).
     * @param resetAt epoch seconds of the next reset, or {@code null}.
     */
    @JsonModel
    public record RateLimit(String label, String kind, Double usedPercent, Long limitWindowSeconds, Long resetAt) {

        public static final String PRIMARY = "primary";
        public static final String SECONDARY = "secondary";
        public static final String ADDITIONAL = "additional";
    }

    /** The credit balance of the account.
     *
     * @param hasCredits whether the plan has credits at all.
     * @param balance the numeric balance as a string (e.g. "0"), {@code null} when unknown.
     * @param unlimited whether the plan is unlimited.
     */
    @JsonModel
    public record Credits(Boolean hasCredits, String balance, Boolean unlimited) {
        public static final Credits EMPTY = new Credits(null, null, null);
    }
}
