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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.InBandToolCallsMode;
import io.yupiik.ia.agent.core.configuration.ModelTuningConfiguration;
import io.yupiik.ia.agent.core.configuration.PlateauFeedbackMode;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderModelConfiguration;
import io.yupiik.ia.agent.core.configuration.ReasoningFormatMode;
import io.yupiik.ia.agent.core.configuration.RepairToolCallArgumentsMode;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@FusionSupport(modules = LoopGuardsTest.TestModule.class)
class LoopGuardsTest {
    @Test
    void capBounding(@Fusion final CodingAgentConfiguration config) {
        final var provider = new ProviderManager(config, null);
        final var cap = new AtomicReference<Integer>(7);
        final var guards = new LoopGuards(provider, config, cap::get);
        final var resolved =
                new ProviderManager.ResolvedModel(provider.providers().get(0), "gpt-9");
        // per-model override is ignored while a positive cap bounds
        assertEquals(7, guards.resolvedMaxIterations(resolved));
        cap.set(null);
        // no cap and no override: the global default applies
        assertEquals(99, guards.resolvedMaxIterations(resolved));
    }

    @Test
    void negativeOverrideMeansUnboundedOnlyWithoutCap(@Fusion final CodingAgentConfiguration config) {
        final var providerOverride = new ProviderManager(config, null) {
            @Override
            public int maxIterationsOverride(final ProviderConfiguration provider, final String model) {
                return -1;
            }
        };
        final var guards = new LoopGuards(providerOverride, config, () -> null);
        final var resolved =
                new ProviderManager.ResolvedModel(providerOverride.providers().get(0), "gpt-9");
        // negative override without a cap means an unbounded window
        assertTrue(guards.resolvedMaxIterations(resolved) < 0);
    }

    @Test
    void capWinsOverNegativeOverride(@Fusion final CodingAgentConfiguration config) {
        final var provider = new ProviderManager(config, null) {
            @Override
            public int maxIterationsOverride(final ProviderConfiguration provider, final String model) {
                return -1; // unbounded
            }
        };
        final var resolved =
                new ProviderManager.ResolvedModel(provider.providers().get(0), "gpt-9");
        // a hard cap (sub-agent ceiling) must bound even an unbounded override
        assertEquals(5, new LoopGuards(provider, config, () -> 5).resolvedMaxIterations(resolved));
    }

    @Test
    void cascadePrecedencePerModelThenProviderThenGlobal(@Fusion final CodingAgentConfiguration config) {
        final var provider = providerManager(config, List.of(providerRecord()));
        final var guards = new LoopGuards(provider, config, () -> null);
        final var perModel = new ProviderManager.ResolvedModel(providerRecord(), "exact-red");

        // per-model wins over the provider tuning and the global
        assertEquals(11, guards.resolvedMaxIterations(perModel));
        assertEquals(3, guards.resolvedPlateauRounds(perModel));
        assertEquals(2, guards.resolvedModelExplorationNudgeRounds(perModel));
        assertEquals(PlateauFeedbackMode.NUDGE_1, guards.resolvedPlateauFeedback(perModel));
        assertEquals(7, guards.resolvedRepeatSuccessThreshold(perModel));

        // a model without a per-model entry falls back to the provider tuning
        final var fromTuning = new ProviderManager.ResolvedModel(providerRecord(), "gpt-9");
        assertEquals(22, guards.resolvedMaxIterations(fromTuning));
        assertEquals(5, guards.resolvedPlateauRounds(fromTuning));
        assertEquals(4, guards.resolvedModelExplorationNudgeRounds(fromTuning));
        assertEquals(PlateauFeedbackMode.NUDGE_2, guards.resolvedPlateauFeedback(fromTuning));
        assertEquals(2, guards.resolvedRepeatSuccessThreshold(fromTuning));

        // a provider with no tuning at all falls back to the global default
        final var globalOnly = new ProviderManager(config, null);
        final var globalResolved =
                new ProviderManager.ResolvedModel(globalOnly.providers().get(0), "gpt-9");
        final var globalGuards = new LoopGuards(globalOnly, config, () -> null);
        assertEquals(99, globalGuards.resolvedMaxIterations(globalResolved));
        assertEquals(4, globalGuards.resolvedPlateauRounds(globalResolved));
        assertEquals(6, globalGuards.resolvedModelExplorationNudgeRounds(globalResolved));
        assertEquals(PlateauFeedbackMode.NONE, globalGuards.resolvedPlateauFeedback(globalResolved));
        assertEquals(3, globalGuards.resolvedRepeatSuccessThreshold(globalResolved));
    }

    @Test
    void zeroPerModelFacetFallsThrough(@Fusion final CodingAgentConfiguration config) {
        // a per-model/provider 0 must not silently zero the resolved value: it falls back down the
        // chain (negative keeps, 0 falls through) so only an explicit global 0 disables the guard
        final var provider = new ProviderManager(config, null) {
            @Override
            public int plateauRounds(final ProviderConfiguration provider, final String model) {
                return 0;
            }
        };
        final var resolved =
                new ProviderManager.ResolvedModel(provider.providers().get(0), "gpt-9");
        assertEquals(4, new LoopGuards(provider, config, () -> null).resolvedPlateauRounds(resolved));
    }

    @Test
    void nullResolvedFallsBackToGlobal(@Fusion final CodingAgentConfiguration config) {
        final var guards = new LoopGuards(null, config, () -> null);
        assertEquals(99, guards.resolvedMaxIterations(null));
        assertEquals(4, guards.resolvedPlateauRounds(null));
        assertEquals(6, guards.resolvedModelExplorationNudgeRounds(null));
        assertEquals(PlateauFeedbackMode.NONE, guards.resolvedPlateauFeedback(null));
        assertEquals(3, guards.resolvedRepeatSuccessThreshold(null));
    }

    @Test
    void plateauFeedbackNudgeAt() {
        assertEquals(3, LoopGuards.plateauFeedbackNudgeAt(PlateauFeedbackMode.NUDGE_2, 5));
        assertEquals(4, LoopGuards.plateauFeedbackNudgeAt(PlateauFeedbackMode.NUDGE_1, 5));
        assertEquals(0, LoopGuards.plateauFeedbackNudgeAt(PlateauFeedbackMode.NONE, 5));
        // a limit too small for an early nudge: hard stop only
        assertEquals(0, LoopGuards.plateauFeedbackNudgeAt(PlateauFeedbackMode.NUDGE_2, 2));
    }

    /** A provider manager whose provider list is the hand-built records (real resolution logic). */
    private static ProviderManager providerManager(
            final CodingAgentConfiguration config, final List<ProviderConfiguration> providers) {
        return new ProviderManager(config, null) {
            @Override
            public List<ProviderConfiguration> providers() {
                return providers;
            }
        };
    }

    /** A provider with a per-model entry ('exact-red') and a provider-level tuning fallback. */
    private static ProviderConfiguration providerRecord() {
        final var tuning = new ModelTuningConfiguration(
                "",
                RepairToolCallArgumentsMode.AUTO,
                InBandToolCallsMode.AUTO,
                ReasoningFormatMode.AUTO,
                null,
                null,
                null,
                5, // plateauRounds
                22, // maxIterations
                PlateauFeedbackMode.NUDGE_2,
                4, // explorationNudgeRounds
                null, // turnContinuesOnTextEnd
                2, // repeatSuccessThreshold
                List.of(),
                null,
                "");
        final var exactRed = new ProviderModelConfiguration(
                "exact-red",
                null,
                new ModelTuningConfiguration(
                        "",
                        RepairToolCallArgumentsMode.AUTO,
                        InBandToolCallsMode.AUTO,
                        ReasoningFormatMode.AUTO,
                        null,
                        null,
                        null,
                        3, // plateauRounds
                        11, // maxIterations
                        PlateauFeedbackMode.NUDGE_1,
                        2, // explorationNudgeRounds
                        null, // turnContinuesOnTextEnd
                        7, // repeatSuccessThreshold
                        List.of(),
                        null,
                        ""),
                0);
        return new ProviderConfiguration(
                "p1",
                "https://example.test/v1",
                "",
                "gpt-9",
                ApiFlavor.AUTO,
                -1,
                -1,
                "",
                tuning,
                List.of(exactRed),
                -1,
                300,
                "",
                Map.of(),
                Map.of(),
                "",
                null,
                null,
                null,
                null,
                null);
    }

    public static class TestModule extends ConfigurationTestModule {
        public TestModule() {
            super(Map.of(
                    "yca.llm.maxIterations", "99",
                    "yca.llm.plateauRounds", "4",
                    "yca.llm.explorationNudgeRounds", "6",
                    "yca.llm.plateauFeedback", "none",
                    "yca.tools.repeatSuccessThreshold", "3"));
        }
    }
}
