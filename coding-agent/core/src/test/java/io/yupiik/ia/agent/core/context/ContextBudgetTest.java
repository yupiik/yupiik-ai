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
package io.yupiik.ia.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.ia.agent.core.llm.ModelsResponse.SourceType;
import io.yupiik.ia.agent.core.llm.ModelsResponse.TokenCount;
import org.junit.jupiter.api.Test;

class ContextBudgetTest {
    private static TokenCount window(final int value) {
        return new TokenCount(value, true, SourceType.CONFIGURED, "test");
    }

    private static TokenCount detectedWindow(final int value) {
        return new TokenCount(value, true, SourceType.DETECTED_ABSOLUTE, "test");
    }

    private static TokenCount output(final int value, final boolean absolute) {
        return new TokenCount(
                value, absolute, absolute ? SourceType.DETECTED_ABSOLUTE : SourceType.DETECTED_RELATIVE, "test");
    }

    @Test
    void declaredOutputCapIsReservedFromTheWindow() {
        assertEquals(32_768, ContextBudget.outputReserve(window(262_144), output(32_768, true)));
        assertEquals(229_376, ContextBudget.usableWindow(window(262_144), output(32_768, true)));
    }

    @Test
    void heuristicReserveAppliesWithoutADeclaredCap() {
        assertEquals(20_000, ContextBudget.outputReserve(window(100_000), null));
        assertEquals(80_000, ContextBudget.usableWindow(window(100_000), null));
        assertEquals(1_024, ContextBudget.outputReserve(window(4_096), null));
        assertEquals(3_072, ContextBudget.usableWindow(window(4_096), null));
    }

    @Test
    void windowNotExceededByAnAbsoluteCapIsATrustedPartition() {
        // total-window convention (gpt-4o 128k/16k, Anthropic 1M/128k): the cap partitions the window
        assertEquals(16_384, ContextBudget.outputReserve(window(128_000), output(16_384, true)));
        assertEquals(111_616, ContextBudget.usableWindow(window(128_000), output(16_384, true)));
        assertEquals(128_000, ContextBudget.outputReserve(window(1_000_000), output(128_000, true)));
        assertEquals(872_000, ContextBudget.usableWindow(window(1_000_000), output(128_000, true)));
    }

    @Test
    void equalInputAndOutputCapsArePerSideLimits() {
        // per-side convention (LiteLLM max_input_tokens == max_output_tokens): a detected window at
        // or below the output cap is the input limit itself, so nothing is reserved
        assertEquals(0, ContextBudget.outputReserve(detectedWindow(131_072), output(131_072, true)));
        assertEquals(131_072, ContextBudget.usableWindow(detectedWindow(131_072), output(131_072, true)));
        assertEquals(0, ContextBudget.outputReserve(detectedWindow(40_960), output(40_960, true)));
        assertEquals(40_960, ContextBudget.usableWindow(detectedWindow(40_960), output(40_960, true)));
    }

    @Test
    void outputCapNotSmallerThanADetectedWindowIsPerSide() {
        // a detected window at or below the output cap is per-side: nothing is reserved
        assertEquals(0, ContextBudget.outputReserve(detectedWindow(32_768), output(32_768, true)));
        assertEquals(32_768, ContextBudget.usableWindow(detectedWindow(32_768), output(32_768, true)));
        assertEquals(0, ContextBudget.outputReserve(detectedWindow(32_768), output(65_536, true)));
        assertEquals(32_768, ContextBudget.usableWindow(detectedWindow(32_768), output(65_536, true)));
    }

    @Test
    void outputCapNotSmallerThanAConfiguredWindowFallsBackToTheHeuristic() {
        // a configured window is always a total, so a contradictory cap falls back to the heuristic
        assertEquals(1_024, ContextBudget.outputReserve(window(4_096), output(8_192, true)));
        assertEquals(3_072, ContextBudget.usableWindow(window(4_096), output(8_192, true)));
    }

    @Test
    void usableWindowNeverDropsBelowTheFloor() {
        assertEquals(ContextBudget.MIN_USABLE_WINDOW, ContextBudget.usableWindow(window(2_048), output(1_500, true)));
        assertEquals(ContextBudget.MIN_USABLE_WINDOW, ContextBudget.usableWindow(null, output(8_000, true)));
        assertEquals(0, ContextBudget.outputReserve(null, output(8_000, true)));
    }

    @Test
    void largeDeclaredCapIsReservedInFullWhenItFits() {
        assertEquals(32_000, ContextBudget.outputReserve(window(128_000), output(32_000, true)));
        assertEquals(96_000, ContextBudget.usableWindow(window(128_000), output(32_000, true)));
    }

    @Test
    void relativeOutputCapsAreHintsNotHardPartitions() {
        // a gateway-derived cap (e.g. max_output_length) is never trusted as a hard partition
        assertEquals(1_024, ContextBudget.outputReserve(window(4_096), output(4_096, false)));
        assertEquals(3_072, ContextBudget.usableWindow(window(4_096), output(4_096, false)));
        assertEquals(20_000, ContextBudget.outputReserve(window(131_072), output(65_536, false)));
        assertEquals(111_072, ContextBudget.usableWindow(window(131_072), output(65_536, false)));
    }

    @Test
    void compactionTriggerUsesTheLargerOfPercentBufferAndTokenFloor() {
        // 15% of 1M (150k) dominates the 16k floor: 1M - 150k
        assertEquals(850_000, ContextBudget.compactionTrigger(1_000_000, 15, 16_000));
        // 15% of 128k (19.2k) dominates: 128k - 19.2k
        assertEquals(108_800, ContextBudget.compactionTrigger(128_000, 15, 16_000));
        // 15% of 32k is only 4.8k, so the 16k floor dominates: 32k - 16k
        assertEquals(16_000, ContextBudget.compactionTrigger(32_000, 15, 16_000));
        // a 0 bufferPercent means the floor alone reserves the headroom
        assertEquals(80_000, ContextBudget.compactionTrigger(100_000, 0, 20_000));
    }

    @Test
    void compactionTriggerNeverDropsBelowTheUsableFloor() {
        // tiny window: the result is floored to the minimum usable window, never below
        assertEquals(ContextBudget.MIN_USABLE_WINDOW, ContextBudget.compactionTrigger(1_024, 15, 16_000));
        assertEquals(ContextBudget.MIN_USABLE_WINDOW, ContextBudget.compactionTrigger(1_500, 50, 16_000));
        assertEquals(ContextBudget.MIN_USABLE_WINDOW, ContextBudget.compactionTrigger(0, 15, 16_000));
        assertEquals(ContextBudget.MIN_USABLE_WINDOW, ContextBudget.compactionTrigger(-100, 15, 16_000));
    }

    @Test
    void compactionTriggerWithZeroFloorKeepsFullPercentBuffer() {
        // bufferMinTokens=0 means only the percent buffer applies
        assertEquals(85_000, ContextBudget.compactionTrigger(100_000, 15, 0));
        assertEquals(80_000, ContextBudget.compactionTrigger(100_000, 20, 0));
    }

    @Test
    void usTriggerCanBeAboveTheCompactionTrigger() {
        final var usable = ContextBudget.usableWindow(window(128_000), output(16_384, true));
        final var trigger = ContextBudget.compactionTrigger(128_000, 15, 16_000);

        assertEquals(111_616, usable);
        assertEquals(108_800, trigger);
        assertEquals(2_816, usable - trigger);
    }

    @Test
    void compactTriggerSafetyCapNeverLandsOnTheUsableWindow() {
        // azure-like: 500k window declaring a 128k output cap -> usable 372k; the raw buffer trigger
        // 425k would clamp exactly onto the usable window without the safety fraction
        final var raw = ContextBudget.compactionTrigger(500_000, 15, 16_000);
        assertEquals(425_000, raw);
        assertEquals(
                334_800,
                ContextBudget.compactionTrigger(500_000, 15, 16_000, 372_000, 90),
                "default 90% safety: trigger = 90% of the usable window, not the usable window itself");
        assertEquals(
                279_000,
                ContextBudget.compactionTrigger(500_000, 15, 16_000, 372_000, 75),
                "75% safety adds more headroom");
        assertEquals(
                372_000,
                ContextBudget.compactionTrigger(500_000, 15, 16_000, 372_000, 100),
                "100% safety keeps the legacy clamp (usable window)");
    }

    @Test
    void compactTriggerSafetyCapIsANoOpWhenTheRawTriggerAlreadyFits() {
        // freeinference-like usable window: raw trigger 850k is below the 90% cap (882k)
        assertEquals(
                850_000,
                ContextBudget.compactionTrigger(1_000_000, 15, 16_000, 980_000, 90),
                "raw trigger unchanged when it already sits under the safety cap");
        assertEquals(
                882_000,
                ContextBudget.compactionTrigger(1_000_000, 0, 0, 980_000, 90),
                "a trigger above the cap is pulled down to the cap");
        assertEquals(
                ContextBudget.MIN_USABLE_WINDOW,
                ContextBudget.compactionTrigger(0, 15, 16_000, 0, 90),
                "unknown window stays floored");
    }
}
