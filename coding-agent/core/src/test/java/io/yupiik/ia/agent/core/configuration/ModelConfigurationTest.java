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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModelConfigurationTest {

    private static ModelVariantConfiguration variant(final String name) {
        return new ModelVariantConfiguration(name, null, null, null, null, null, null);
    }

    @Test
    void resolvedPruneFallsBackToCompactWhenBlank() {
        assertEquals("compact-model", new ModelConfiguration(variant("compact-model"), null, null).resolvedPrune());
        assertEquals(
                "compact-model", new ModelConfiguration(variant("compact-model"), variant(null), null).resolvedPrune());
    }

    @Test
    void resolvedPruneKeepsItsOwnValue() {
        assertEquals(
                "prune-model",
                new ModelConfiguration(variant("compact-model"), variant("prune-model"), variant("")).resolvedPrune());
    }

    @Test
    void resolvedPruneCanBeBlankWhenBothAreBlank() {
        assertEquals("", new ModelConfiguration(variant(""), variant(""), variant("")).resolvedPrune());
    }

    @Test
    void resolvedMemoryFallsBackToTheCompactModelThenTheProviderDefault() {
        // memory empty -> compact model
        assertEquals("compact-model", new ModelConfiguration(variant("compact-model"), null, null).resolvedMemory());
        assertEquals(
                "compact-model",
                new ModelConfiguration(variant("compact-model"), variant("prune-model"), variant("")).resolvedMemory());
        // memory set -> its own value
        assertEquals(
                "memory-model",
                new ModelConfiguration(variant("compact-model"), variant("prune-model"), variant("memory-model"))
                        .resolvedMemory());
        // both blank -> blank (the provider default model applies at resolve time)
        assertEquals("", new ModelConfiguration(variant(""), variant(""), variant("")).resolvedMemory());
    }

    @Test
    void tuningFallsBackToCompactWhenPathUnset() {
        final var compact = new ModelVariantConfiguration("m1", null, null, null, 1024, "none", null);
        // memory path unset -> compact tuning (maxTokens 1024, reasoningEffort none)
        assertEquals(
                1024, new ModelConfiguration(compact, null, null).memoryTuning().maxTokens());
        assertEquals(
                "none",
                new ModelConfiguration(compact, null, null).memoryTuning().reasoningEffort());
        assertEquals(
                1024, new ModelConfiguration(compact, null, null).pruneTuning().maxTokens());
        // a dedicated memory tuning wins
        final var mem = new ModelVariantConfiguration("m2", null, null, null, 512, "low", null);
        final var cfg = new ModelConfiguration(compact, null, mem);
        assertEquals(512, cfg.memoryTuning().maxTokens());
        assertEquals("low", cfg.memoryTuning().reasoningEffort());
        assertEquals("m2", cfg.resolvedMemory());
        assertEquals(1024, cfg.compactTuning().maxTokens());
    }

    @Test
    void variantExposesSamplingFacetsWithWireMappedNames() {
        final var variant = new ModelVariantConfiguration(
                "balanced", 0.7, 0.9, 40.0, 2048, "high", "{\"thinking\":{\"type\":\"enabled\"}}");
        assertEquals("balanced", variant.name());
        assertEquals(0.7, variant.temperature());
        assertEquals(0.9, variant.topP());
        assertEquals(40.0, variant.topK());
        assertEquals(2048, variant.maxTokens());
        assertEquals("high", variant.reasoningEffort());
        assertEquals("{\"thinking\":{\"type\":\"enabled\"}}", variant.extra());
    }

    @Test
    void tuningUnsetNullableFacetsAreNull() {
        final var tuning = new ModelTuningConfiguration(
                "",
                RepairToolCallArgumentsMode.AUTO,
                InBandToolCallsMode.AUTO,
                ReasoningFormatMode.AUTO,
                null,
                null,
                null,
                null,
                null,
                PlateauFeedbackMode.NUDGE_2,
                null,
                null,
                null,
                List.of(),
                null,
                "");
        assertNull(tuning.outputTokens());
        assertNull(tuning.systemPromptMaxChars());
        assertNull(tuning.plateauRounds());
        assertNull(tuning.maxIterations());
        assertNull(tuning.repeatSuccessThreshold());
        assertEquals(PlateauFeedbackMode.NUDGE_2, tuning.plateauFeedback());
        assertEquals(RepairToolCallArgumentsMode.AUTO, tuning.repairToolCallArguments());
        assertEquals(InBandToolCallsMode.AUTO, tuning.inBandToolCalls());
        assertEquals(ReasoningFormatMode.AUTO, tuning.reasoningFormat());
        assertEquals(List.of(), tuning.variants());
        assertNull(tuning.defaultVariant());
    }

    @Test
    void defaultVariantIsExposed() {
        final var tuning = new ModelTuningConfiguration(
                "",
                RepairToolCallArgumentsMode.AUTO,
                InBandToolCallsMode.AUTO,
                ReasoningFormatMode.AUTO,
                null,
                null,
                null,
                null,
                null,
                PlateauFeedbackMode.NUDGE_2,
                null,
                null,
                null,
                List.of(),
                "reasoning",
                "");
        assertEquals("reasoning", tuning.defaultVariant());
    }
}
