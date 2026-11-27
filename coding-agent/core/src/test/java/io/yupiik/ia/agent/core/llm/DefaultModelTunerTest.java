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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultModelTunerTest {

    @Test
    void temperatureFollowsTheModelFamily() {
        assertEquals(1.0, DefaultModelTuner.temperature("north-mini-code", null));
        assertEquals(1.0, DefaultModelTuner.temperature("gemini-2.5-pro", null));
        assertEquals(1.0, DefaultModelTuner.temperature("gemini-3-flash", null));
        assertEquals(1.0, DefaultModelTuner.temperature("glm-4.7", null));
        assertEquals(1.0, DefaultModelTuner.temperature("minimax-m2", null));
        assertEquals(1.0, DefaultModelTuner.temperature("kimi-k2-thinking", null));
        assertEquals(0.6, DefaultModelTuner.temperature("kimi-k2-base", null));
        assertNull(DefaultModelTuner.temperature("claude-sonnet-5", null));
        assertNull(DefaultModelTuner.temperature("gpt-5", null));
        assertNull(DefaultModelTuner.temperature("", null));
        assertNull(DefaultModelTuner.temperature(null, null));
    }

    @Test
    void topPPicksGeminiMinimaxKimiAndDeepseekFamilies() {
        assertEquals(0.95, DefaultModelTuner.topP("gemini-2.5-pro", null));
        assertEquals(0.95, DefaultModelTuner.topP("minimax-m2", null));
        assertEquals(0.95, DefaultModelTuner.topP("kimi-k2.5", null));
        assertEquals(0.95, DefaultModelTuner.topP("kimi-k2-5", null));
        assertEquals(0.95, DefaultModelTuner.topP("deepseek-v4-flash-0731", null));
        assertEquals(0.95, DefaultModelTuner.topP("deepseek-v4-flash-0731-preview", null));
        assertEquals(0.95, DefaultModelTuner.topP("deepseek-v4-flash:0731", null));
        assertEquals(0.95, DefaultModelTuner.topP("deepseek-v4-flash:0731-preview", null));
        assertEquals(0.95, DefaultModelTuner.topP("deepseek-v4-flash", "deepseek"));
        assertEquals(0.95, DefaultModelTuner.topP("deepseek-v4-flash", "OpenCode"));
        assertNull(DefaultModelTuner.topP("deepseek-v4-flash", "other"));
        assertNull(DefaultModelTuner.topP("claude-sonnet-5", null));
    }

    @Test
    void topKDistinguishesMinimaxAndGemini() {
        assertEquals(40.0, DefaultModelTuner.topK("minimax-m21", null));
        assertEquals(20.0, DefaultModelTuner.topK("minimax-m2", null));
        assertEquals(64.0, DefaultModelTuner.topK("gemini-2.5-flash", null));
        assertNull(DefaultModelTuner.topK("gpt-5", null));
    }

    @Test
    void implicitVariantNameIsOnlyExposedWhenADefaultApplies() {
        assertEquals("default", DefaultModelTuner.implicitVariantName("gemini-2.5-pro", null));
        assertNull(DefaultModelTuner.implicitVariantName("claude-sonnet-5", null));
    }

    @Test
    void variantsExposeReasoningEffortPresetsForDeepseekV4() {
        final var variants = DefaultModelTuner.variants("deepseek-v4-flash", null);
        assertEquals(4, variants.size());
        assertEquals("low", variants.get(0).name());
        assertEquals("medium", variants.get(1).name());
        assertEquals("high", variants.get(2).name());
        assertEquals("max", variants.get(3).name());
        assertEquals("low", variants.get(0).reasoningEffort());
        assertEquals("max", variants.get(3).reasoningEffort());
    }

    @Test
    void reasoningEffortDefaultsMaxForDeepseekAndMediumForGpt5() {
        assertEquals("max", DefaultModelTuner.reasoningEffort("deepseek-v4-flash", null));
        assertEquals("max", DefaultModelTuner.reasoningEffort("deepseek-v4-pro", null));
        assertEquals("medium", DefaultModelTuner.reasoningEffort("gpt-5", null));
        assertEquals("medium", DefaultModelTuner.reasoningEffort("gpt-5.1", null));
        assertEquals("medium", DefaultModelTuner.reasoningEffort("gpt-5.2", null));
        assertEquals("medium", DefaultModelTuner.reasoningEffort("gpt-6", null));
        assertEquals("medium", DefaultModelTuner.reasoningEffort("gpt-6.1-luna", null));
        assertNull(DefaultModelTuner.reasoningEffort("gpt-5-chat", null));
        assertNull(DefaultModelTuner.reasoningEffort("gpt-5-pro", null));
        assertNull(DefaultModelTuner.reasoningEffort("gpt-6.1-pro", null));
        assertNull(DefaultModelTuner.reasoningEffort("claude-sonnet-5", null));
        assertNull(DefaultModelTuner.reasoningEffort(null, null));
    }

    @Test
    void variantsAreEmptyForExcludedAndNonReasoningFamilies() {
        assertEquals(0, DefaultModelTuner.variants("deepseek-reasoner", null).size());
        assertEquals(0, DefaultModelTuner.variants("deepseek-chat", null).size());
        assertEquals(0, DefaultModelTuner.variants("deepseek-r1", null).size());
        assertEquals(0, DefaultModelTuner.variants("deepseek-v3", null).size());
        assertEquals(0, DefaultModelTuner.variants("qwen3-32b", null).size());
        assertEquals(0, DefaultModelTuner.variants("big-pickle", null).size());
        assertEquals(0, DefaultModelTuner.variants(null, null).size());
    }

    @Test
    void gpt5TieringFollowsReleaseDateAndSubFamily() {
        // No release date: no none/xhigh tiers, gpt-5 adds minimal.
        List<String> names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-5", "openai", ApiFlavor.RESPONSES, null, null)));
        assertEquals(List.of("minimal", "low", "medium", "high"), names);

        // gpt-5-pro exposes only high.
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-5-pro", "openai", ApiFlavor.RESPONSES, null, null)));
        assertEquals(List.of("high"), names);

        // gpt-5.1 exposes the none..high set (no xhigh).
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-5.1", "openai", ApiFlavor.RESPONSES, null, null)));
        assertEquals(List.of("none", "low", "medium", "high"), names);

        // gpt-5.2 (no -pro) additionally exposes xhigh.
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-5.2", "openai", ApiFlavor.RESPONSES, null, null)));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), names);

        // gpt-5-chat exposes only medium.
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-5.1-chat", "openai", ApiFlavor.RESPONSES, null, null)));
        assertEquals(List.of("medium"), names);

        // gpt-5.3-codex exposes the none..xhigh set.
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-5.3-codex", "openai", ApiFlavor.RESPONSES, null, null)));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), names);
    }

    @Test
    void gpt6TieringCoversUnversionedAndVersionedIds() {
        // gpt-6 (launch era, no release date needed) accepts the full none..xhigh set; the
        // gpt-5-only "minimal" effort is gone.
        List<String> names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-6", "openai", ApiFlavor.RESPONSES, null, null)));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), names);

        // the actual codex-yupiik main-thread models.
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-6-luna", "codex-yupiik", ApiFlavor.CODEX, null, null)));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), names);
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-6.1-sol", "codex-yupiik", ApiFlavor.CODEX, null, null)));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), names);
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-6.1-terra", "codex-yupiik", ApiFlavor.CODEX, null, null)));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), names);

        // sub-families: chat only exposes medium, versioned pro the PRO_2_PLUS set, unversioned
        // pro the high-only set.
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-6.1-chat", "openai", ApiFlavor.RESPONSES, null, null)));
        assertEquals(List.of("medium"), names);
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-6.1-pro", "openai", ApiFlavor.RESPONSES, null, null)));
        assertEquals(List.of("medium", "high", "xhigh"), names);
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-6-pro", "openai", ApiFlavor.RESPONSES, null, null)));
        assertEquals(List.of("high"), names);

        // gpt-6 codex ids expose the newest codex effort set (none..xhigh).
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-6.1-codex", "default", ApiFlavor.CODEX, null, null)));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), names);
    }

    @Test
    void underscoreSeparatedAndGenericProviderGptIdsUseTheSameTiers() {
        // azure-style ids ("azure_gpt-5.5") start the family with an underscore separator.
        List<String> names = namesOf(DefaultModelTuner.variants(new DefaultModelTuner.ModelContext(
                "azure_gpt-5.5", "chatgpt-procapital", ApiFlavor.RESPONSES, null, null)));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), names);

        // a provider without an openai/meta/azure/copilot catalog slug still gets the real
        // OpenAI tiers for a gpt id (previously the widely-supported-only default).
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-6.1-sol", "freeinference", ApiFlavor.COMPLETIONS, null, null)));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), names);
        names = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-5.5", "openrouter", ApiFlavor.COMPLETIONS, null, null)));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), names);
    }

    @Test
    void codexFlavorExposesTheSameReasoningTiersAsTheResponsesWire() {
        // a codex-flavored provider (ApiFlavor.CODEX) must route into the openai tiering so the
        // gpt-5.x-codex efforts (none..xhigh) apply, not fall through to the empty default
        final var variants = DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-5.3-codex", "default", ApiFlavor.CODEX, null, null));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), namesOf(variants));

        // a non-codex gpt-5 id still gets the widely-supported tiers for the numberless releases
        final var baseline = namesOf(DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("gpt-5", "default", ApiFlavor.CODEX, null, null)));
        assertEquals(List.of("minimal", "low", "medium", "high"), baseline);
    }

    @Test
    void o1MiniHasNoVariantsOnAzure() {
        assertEquals(
                0,
                DefaultModelTuner.variants(
                                new DefaultModelTuner.ModelContext("o1-mini", "azure", ApiFlavor.RESPONSES, null, null))
                        .size());
    }

    @Test
    void genericOpenAiCompatibleFamiliesGetWidelySupportedEfforts() {
        assertEquals(List.of("low", "medium", "high"), namesOf(DefaultModelTuner.variants("grok", "xai")));
        assertEquals(List.of("none", "low", "medium", "high"), namesOf(DefaultModelTuner.variants("llama", "groq")));
        assertEquals(List.of("none", "high"), namesOf(DefaultModelTuner.variants("north-mini-code", "deepseek")));
    }

    @Test
    void grok3MiniExposesLowHigh() {
        assertEquals(List.of("low", "high"), namesOf(DefaultModelTuner.variants("grok-3-mini", null)));
    }

    @Test
    void mistralOnlyExposesHighForReasoningReleaseIds() {
        assertEquals(
                List.of("high"),
                namesOf(DefaultModelTuner.variants(new DefaultModelTuner.ModelContext(
                        "mistral-small-latest", "mistral", ApiFlavor.COMPLETIONS, null, null))));
        assertEquals(
                0,
                DefaultModelTuner.variants(new DefaultModelTuner.ModelContext(
                                "mistral-large-latest", "mistral", ApiFlavor.COMPLETIONS, null, null))
                        .size());
    }

    @Test
    void anthropicModernClaudeUsesAdaptiveThinkingEfforts() {
        final var variants = DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("claude-sonnet-4.7", "anthropic", ApiFlavor.ANTHROPIC, 32000, null));
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), namesOf(variants));
        assertTrue(variants.get(0).extra().contains("\"type\":\"adaptive\""));
        // Modern adaptive models force display summarized.
        assertTrue(variants.get(0).extra().contains("\"display\":\"summarized\""));
    }

    @Test
    void anthropicOlderClaudeDefaultsToTokenBudgets() {
        final var variants = DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("claude-sonnet-4.5", "anthropic", ApiFlavor.ANTHROPIC, 32000, null));
        assertEquals(List.of("high", "max"), namesOf(variants));
        assertTrue(variants.get(0).extra().contains("\"budget_tokens\":"));
    }

    @Test
    void glm52ExposesMaxOnOpenAiWire() {
        assertEquals(
                List.of("high", "max"),
                namesOf(DefaultModelTuner.variants(
                        new DefaultModelTuner.ModelContext("glm-5.2", "zhipuai", ApiFlavor.COMPLETIONS, null, null))));
    }

    @Test
    void kimiOnAnthropicWireExposesAdaptiveEfforts() {
        final var variants = DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("kimi-k2.5", "moonshot", ApiFlavor.ANTHROPIC, null, null));
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), namesOf(variants));
        assertTrue(variants.get(0).extra().contains("\"thinking\":{\"type\":\"adaptive\",\"display\":\"summarized\"}"));
        // the Messages API documents effort inside output_config, not at the request top level
        assertTrue(
                variants.get(0).extra().contains("\"output_config\":{\"effort\":\"low\"}"),
                "effort must sit in output_config: " + variants.get(0).extra());
    }

    @Test
    void minimaxM3ExposesThinkingToggle() {
        final var variants = DefaultModelTuner.variants(
                new DefaultModelTuner.ModelContext("minimax-m3", "minimax", ApiFlavor.COMPLETIONS, null, null));
        assertEquals(List.of("none", "thinking"), namesOf(variants));
        assertTrue(variants.get(0).extra().contains("\"thinking\":{\"type\":\"disabled\"}"));
    }

    @Test
    void catalogReasoningEffortsWinOverHeuristics() {
        final var responses = DefaultModelTuner.variants(new DefaultModelTuner.ModelContext(
                "gpt-6.1-sol", "openai", ApiFlavor.RESPONSES, null, null, List.of("low", "xhigh", "ultra")));
        assertEquals(List.of("low", "xhigh", "ultra"), namesOf(responses));
        assertEquals("low", responses.get(0).reasoningEffort());
        assertNull(responses.get(0).extra(), "non-codex variants use the dedicated effort field");

        final var codex = DefaultModelTuner.variants(new DefaultModelTuner.ModelContext(
                "gpt-6.1-sol", "default", ApiFlavor.CODEX, null, null, List.of("low", "xhigh")));
        assertEquals(List.of("low", "xhigh"), namesOf(codex));
        assertTrue(codex.get(1).extra().contains("\"reasoning_effort\":\"xhigh\""));
    }

    @Test
    void emptyCatalogReasoningEffortsKeepTheHeuristics() {
        final var variants = DefaultModelTuner.variants(new DefaultModelTuner.ModelContext(
                "gpt-6.1-sol", "openai", ApiFlavor.RESPONSES, null, null, List.of()));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"), namesOf(variants));
    }

    private static List<String> namesOf(final List<ModelVariantConfiguration> variants) {
        return variants.stream().map(ModelVariantConfiguration::name).toList();
    }
}
