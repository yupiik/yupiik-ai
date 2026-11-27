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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.usage.CodexUsageSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentQuotaTest {

    @Test
    void ofKeepsBothWindowsAndTheirMetadata() {
        final var snapshot = new CodexUsageSnapshot(
                "plus",
                List.of(
                        new CodexUsageSnapshot.RateLimit("primary", "primary", 90.0, 5 * 3600L, 1_705_000_000L),
                        new CodexUsageSnapshot.RateLimit("secondary", "secondary", 10.0, 7 * 86400L, 1_710_000_000L)),
                new CodexUsageSnapshot.Credits(true, "10", false),
                true,
                "acc-1");

        final var quota = AgentEvent.UsageUpdated.Quota.of(true, true, snapshot);

        assertTrue(quota.enabled());
        assertTrue(quota.supported());
        assertEquals("plus", quota.planType());
        // the primary window populates the legacy fields, its usedPercent stays nullable-typed
        assertEquals(90.0, quota.usedPercent());
        assertEquals(5 * 3600L, quota.windowSeconds());
        assertEquals(1_705_000_000L, quota.resetAtEpochSeconds());
        // both windows survive conversion, in order
        assertEquals(2, quota.windows().size());
        assertEquals("primary", quota.windows().get(0).kind());
        assertEquals("secondary", quota.windows().get(1).kind());
        assertEquals(90.0, quota.windows().get(0).usedPercent());
        assertEquals(10.0, quota.windows().get(1).usedPercent());
        assertEquals(604800L, quota.windows().get(1).windowSeconds());
        assertEquals("10", quota.creditsBalance());
        assertFalse(quota.creditsUnlimited());
    }

    @Test
    void ofTreatsMissingPrimaryAndMissingPercentWithoutFabricatingValues() {
        // a window with a nullable usedPercent is never coerced to a zero; it stays null.
        final var snapshot = new CodexUsageSnapshot(
                "plus",
                List.of(
                        new CodexUsageSnapshot.RateLimit("secondary", "secondary", null, 604800L, null),
                        new CodexUsageSnapshot.RateLimit("additional", "code-review", 40.0, 18000L, 1_705_000_000L)),
                null,
                null,
                null);

        final var quota = AgentEvent.UsageUpdated.Quota.of(true, true, snapshot);

        // no primary window: legacy primary fields stay null (not coerced to 0)
        assertNull(quota.usedPercent());
        assertEquals(0, quota.windowSeconds());
        // the windows carry the nullable usedPercent verbatim - missing (secondary) vs known (additional)
        assertEquals(2, quota.windows().size());
        assertNull(quota.windows().get(0).usedPercent());
        assertEquals(40.0, quota.windows().get(1).usedPercent());
    }

    @Test
    void ofReturnsEmptyQuotaForNullSnapshot() {
        final var quota = AgentEvent.UsageUpdated.Quota.of(true, true, null);
        assertEquals(0, quota.windows().size());
        assertNull(quota.usedPercent());
        assertNull(quota.planType());
        assertTrue(quota.windows().isEmpty());
    }

    @Test
    void windowDisplayLabelIsDurationDerived() {
        assertEquals(
                "weekly", new AgentEvent.UsageUpdated.Quota.Window(null, null, 10.0, 604800L, null).displayLabel());
        assertEquals("5h", new AgentEvent.UsageUpdated.Quota.Window(null, null, 10.0, 18000L, null).displayLabel());
        assertEquals("2d", new AgentEvent.UsageUpdated.Quota.Window(null, null, 10.0, 2 * 86400L, null).displayLabel());
        assertEquals("30m", new AgentEvent.UsageUpdated.Quota.Window(null, null, 10.0, 1800L, null).displayLabel());
        assertEquals("90s", new AgentEvent.UsageUpdated.Quota.Window(null, null, 10.0, 90L, null).displayLabel());
        // a blank/unknown duration falls back to the label, then "quota"
        assertEquals(
                "custom", new AgentEvent.UsageUpdated.Quota.Window(null, "custom", 10.0, null, null).displayLabel());
        assertEquals("quota", new AgentEvent.UsageUpdated.Quota.Window(null, " ", 10.0, 0L, null).displayLabel());
    }
}
