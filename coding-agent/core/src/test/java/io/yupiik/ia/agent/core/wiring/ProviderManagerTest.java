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
package io.yupiik.ia.agent.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CompactionLlmMode;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.ReasoningFormatMode;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderManagerTest {

    @TempDir
    static Path storageHome;

    private static ProviderManager manager(final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        return new ProviderManager(configuration, mapper);
    }

    @Nested
    @FusionSupport(modules = TwoProvidersSources.class)
    class TwoProviders {
        @Test
        void defaultVariantIsNullWithoutConfigurationScenario1(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            assertNull(manager.defaultVariant(provider, "claude"));
        }

        @Test
        void outputTokensExactWinsOverContainsAndInvalidIsIgnored(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            // exact match wins over the earlier contains entry ("claude" contains match is listed first)
            assertEquals(2000, manager.outputTokensOverride(provider, "claude-sonnet-5"));
            // contains fallback follows the list order
            assertEquals(1000, manager.outputTokensOverride(provider, "claude-opus-4"));
            // zero/negative override is ignored
            assertEquals(0, manager.outputTokensOverride(provider, "haiku-4-5"));
            // unlisted model
            assertEquals(0, manager.outputTokensOverride(provider, "gpt-5"));
        }

        @Test
        void contextWindowOverrideFollowsExactContainsAndProviderFallbackScenario1(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            // per-model override wins over the provider value
            assertEquals(1_000_000, manager.contextWindowOverride(provider, "deepseek-v4-flash"));
        }

        @Test
        void plateauRoundsAndMaxIterationsFollowExactThenContainsAndIgnoreInvalid(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            // exact match wins over the earlier contains entry
            assertEquals(6, manager.plateauRounds(provider, "claude-sonnet-5"));
            // contains fallback follows the list order
            assertEquals(4, manager.plateauRounds(provider, "claude-opus-4"));
            // zero/negative override is ignored (falls back to 0 = unset)
            assertEquals(0, manager.plateauRounds(provider, "haiku-4-5"));
            // unlisted model returns 0 = no override
            assertEquals(0, manager.plateauRounds(provider, "gpt-5"));
            // same cascade for the iteration window cap
            assertEquals(120, manager.maxIterationsOverride(provider, "claude-sonnet-5"));
            assertEquals(80, manager.maxIterationsOverride(provider, "claude-opus-4"));
            assertEquals(0, manager.maxIterationsOverride(provider, "haiku-4-5"));
            assertEquals(0, manager.maxIterationsOverride(provider, "gpt-5"));
            // same cascade for the doom-loop success threshold
            assertEquals(6, manager.repeatSuccessThreshold(provider, "claude-sonnet-5"));
            assertEquals(2, manager.repeatSuccessThreshold(provider, "claude-opus-4"));
            assertEquals(0, manager.repeatSuccessThreshold(provider, "haiku-4-5"));
            assertEquals(0, manager.repeatSuccessThreshold(provider, "gpt-5"));
        }

        @Test
        void systemPromptMaxCharsIsExactThenContainsAndIgnoresInvalid(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            // exact match wins over the earlier contains entry
            assertEquals(9000, manager.systemPromptMaxChars(provider, "claude-sonnet-5"));
            // contains fallback follows the list order
            assertEquals(4000, manager.systemPromptMaxChars(provider, "claude-opus-4"));
            // zero/negative override is ignored (falls back to 0 = unset)
            assertEquals(0, manager.systemPromptMaxChars(provider, "haiku-4-5"));
            // unlisted model returns 0 = no override
            assertEquals(0, manager.systemPromptMaxChars(provider, "gpt-5"));
        }

        @Test
        void modelIndexMatchesExactThenContains(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            assertEquals(1, manager.modelIndex(provider, "claude-sonnet-5"));
            assertEquals(0, manager.modelIndex(provider, "claude-opus-4"));
            assertEquals(-1, manager.modelIndex(provider, "gpt-5"));
        }

        @Test
        void reasoningEffortIsExactThenContainsAndIgnoresBlank(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            assertEquals("high", manager.reasoningEffort(provider, "claude-sonnet-5"));
            assertEquals("low", manager.reasoningEffort(provider, "claude-opus-4"));
            assertNull(manager.reasoningEffort(provider, "haiku-4-5"));
            assertNull(manager.reasoningEffort(provider, "gpt-5"));
        }
    }

    public static class TwoProvidersSources extends ConfigurationTestModule {
        public TwoProvidersSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "2"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "fallback-0"),
                    Map.entry("yca.provider.providers.0.models.length", "4"),
                    Map.entry("yca.provider.providers.0.models.0.model", "claude"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.outputTokens", "1000"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.reasoningEffort", "low"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.systemPromptMaxChars", "4000"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.plateauRounds", "4"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.maxIterations", "80"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.repeatSuccessThreshold", "2"),
                    Map.entry("yca.provider.providers.0.models.1.model", "claude-sonnet-5"),
                    Map.entry("yca.provider.providers.0.models.1.behavior.outputTokens", "2000"),
                    Map.entry("yca.provider.providers.0.models.1.behavior.reasoningEffort", "high"),
                    Map.entry("yca.provider.providers.0.models.1.behavior.systemPromptMaxChars", "9000"),
                    Map.entry("yca.provider.providers.0.models.1.behavior.plateauRounds", "6"),
                    Map.entry("yca.provider.providers.0.models.1.behavior.maxIterations", "120"),
                    Map.entry("yca.provider.providers.0.models.1.behavior.repeatSuccessThreshold", "6"),
                    Map.entry("yca.provider.providers.0.models.2.model", "haiku"),
                    Map.entry("yca.provider.providers.0.models.2.behavior.outputTokens", "-1"),
                    Map.entry("yca.provider.providers.0.models.2.behavior.reasoningEffort", ""),
                    Map.entry("yca.provider.providers.0.models.2.behavior.systemPromptMaxChars", "-1"),
                    Map.entry("yca.provider.providers.0.models.3.model", "deepseek-v4-flash"),
                    Map.entry("yca.provider.providers.0.models.3.contextWindow", "1000000"),
                    Map.entry("yca.provider.providers.1.id", "p1"),
                    Map.entry("yca.provider.providers.1.model", "fallback-1")));
        }
    }

    @Nested
    @FusionSupport(modules = BlankDefaultVariantSources.class)
    class BlankDefaultVariant {
        @Test
        void defaultVariantIsNullWithoutConfigurationScenario2(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Blank provider tuning counts as unset as well.
            final var withBlankTuning = manager(configuration, mapper);
            assertNull(
                    withBlankTuning.defaultVariant(withBlankTuning.providers().get(0), "m"));
        }
    }

    public static class BlankDefaultVariantSources extends ConfigurationTestModule {
        public BlankDefaultVariantSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "m"),
                    Map.entry("yca.provider.providers.0.tuning.defaultVariant", "")));
        }
    }

    @Nested
    @FusionSupport(modules = ContainsContextWindowSources.class)
    class ContainsContextWindow {
        @Test
        void contextWindowOverrideFollowsExactContainsAndProviderFallbackScenario2(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // A configured "deepseek" entry also matches "deepseek-v4-flash".
            final var withContains = manager(configuration, mapper);
            final var pContains = withContains.providers().get(0);
            assertEquals(1_000_000, withContains.contextWindowOverride(pContains, "deepseek-v4-flash"));
        }
    }

    public static class ContainsContextWindowSources extends ConfigurationTestModule {
        public ContainsContextWindowSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "deepseek-v4-flash"),
                    Map.entry("yca.provider.providers.0.contextWindow", "64000"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "deepseek"),
                    Map.entry("yca.provider.providers.0.models.0.contextWindow", "1000000")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderContextWindowSources.class)
    class ProviderContextWindow {
        @Test
        void contextWindowOverrideFollowsExactContainsAndProviderFallbackScenario3(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // An unmatched model falls back to the provider context window.
            final var withProviderWindow = manager(configuration, mapper);
            final var pWithModel = withProviderWindow.providers().get(0);
            assertEquals(1_000_000, withProviderWindow.contextWindowOverride(pWithModel, "deepseek-v4-flash"));
            // model has no entry -> provider window wins
            assertEquals(64_000, withProviderWindow.contextWindowOverride(pWithModel, "gpt-5"));
        }
    }

    public static class ProviderContextWindowSources extends ConfigurationTestModule {
        public ProviderContextWindowSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "gpt-5"),
                    Map.entry("yca.provider.providers.0.contextWindow", "64000"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "deepseek"),
                    Map.entry("yca.provider.providers.0.models.0.contextWindow", "1000000")));
        }
    }

    @Nested
    @FusionSupport(modules = UnsetContextWindowSources.class)
    class UnsetContextWindow {
        @Test
        void contextWindowOverrideFollowsExactContainsAndProviderFallbackScenario4(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Unset provider and model windows resolve to zero.
            final var noWindow = manager(configuration, mapper);
            final var pNoWindow = noWindow.providers().get(0);
            assertEquals(0, noWindow.contextWindowOverride(pNoWindow, "m"));
        }
    }

    public static class UnsetContextWindowSources extends ConfigurationTestModule {
        public UnsetContextWindowSources() {
            super(Map.of("yca.storageHome", storageHome.toString(), "yca.provider.model", "m"));
        }
    }

    @Nested
    @FusionSupport(modules = UnsetModelContextWindowSources.class)
    class UnsetModelContextWindow {
        @Test
        void contextWindowOverrideZeroMeansUnsetAndFallsThrough(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            assertEquals(32_000, manager.contextWindowOverride(provider, "m"));
        }
    }

    public static class UnsetModelContextWindowSources extends ConfigurationTestModule {
        public UnsetModelContextWindowSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "m"),
                    Map.entry("yca.provider.providers.0.contextWindow", "32000"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "m"),
                    // not set -> falls through to provider
                    Map.entry("yca.provider.providers.0.models.0.style.color", "#fff")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderOptionsSources.class)
    class ProviderOptions {
        @Test
        void providerOptionsAreCachedPerProviderInstance(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            final var first = manager.providerOptions(provider);
            final var second = manager.providerOptions(provider);
            assertSame(first, second, "parsed providerSpecificOptions must be cached, not re-parsed per request");
            assertEquals(0.2, ((Number) first.get("temperature")).doubleValue());
        }
    }

    public static class ProviderOptionsSources extends ConfigurationTestModule {
        public ProviderOptionsSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "m"),
                    Map.entry("yca.provider.providers.0.providerSpecificOptions", "{\"temperature\":0.2}")));
        }
    }

    @Nested
    @FusionSupport(modules = TuningProviderSources.class)
    class TuningProvider {
        @Test
        void tuningFallsBackToProviderLevelWhenNoModelEntryMatches(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            final var tuning = manager.tuning(provider, "gpt-5");
            assertEquals("medium", tuning.reasoningEffort());
            assertEquals(4096, tuning.outputTokens());
            assertEquals("medium", manager.reasoningEffort(provider, "gpt-5"));
            assertEquals(4096, manager.outputTokensOverride(provider, "gpt-5"));
            assertEquals(5000, manager.systemPromptMaxChars(provider, "gpt-5"));
            assertEquals(3, manager.plateauRounds(provider, "gpt-5"));
            assertEquals(60, manager.maxIterationsOverride(provider, "gpt-5"));
            assertEquals(2, manager.variants(provider, "gpt-5").size());
            assertEquals("balanced", manager.defaultVariant(provider, "gpt-5"));
        }

        @Test
        void perModelBehaviorWinsOverProviderLevelTuning(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            final var tuning = manager.tuning(provider, "claude-sonnet-5");
            assertEquals("low", tuning.reasoningEffort());
            assertEquals("low", manager.reasoningEffort(provider, "claude-sonnet-5"));
            // per-model providerSpecificOptions wins over the provider-level one
            assertEquals("{\"temperature\":0.2}", manager.providerSpecificOptions(provider, "claude-sonnet-5"));
            // per-model behavior.plateauRounds / maxIterations win over the provider tuning
            assertEquals(4, manager.plateauRounds(provider, "claude-sonnet-5"));
            assertEquals(90, manager.maxIterationsOverride(provider, "claude-sonnet-5"));
            // provider-level (top-level) providerSpecificOptions applies for an unlisted model
            assertEquals("{\"echo\":1}", manager.providerSpecificOptions(provider, "gpt-5"));
            // a per-model behavior without variants falls back to the provider-level variants
            assertEquals(2, manager.variants(provider, "claude-sonnet-5").size());
            // per-model behavior.defaultVariant wins over the provider-level tuning.defaultVariant
            assertEquals("fast", manager.defaultVariant(provider, "claude-sonnet-5"));
        }

        @Test
        void tuningOptionsAreParsedAndCached(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            final var tuning = manager.tuning(provider, "gpt-5");
            // tuning-level providerSpecificOptions is what ChatClient merges per-request
            assertEquals(0.5, ((Number) manager.tuningOptions(tuning).get("frequency_penalty")).doubleValue());
            final var first = manager.tuningOptions(tuning);
            final var second = manager.tuningOptions(tuning);
            assertSame(first, second, "parsed tuning providerSpecificOptions must be cached");
        }

        @Test
        void variantsExposeWireMappedFields(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            final var provider = manager.providers().get(0);
            final var variants = manager.variants(provider, "gpt-5");
            assertEquals("balanced", variants.get(0).name());
            assertEquals(0.7, variants.get(0).temperature());
            assertEquals(0.9, variants.get(0).topP());
            assertEquals("high", variants.get(1).reasoningEffort());
        }
    }

    public static class TuningProviderSources extends ConfigurationTestModule {
        public TuningProviderSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "m"),
                    Map.entry("yca.provider.providers.0.providerSpecificOptions", "{\"echo\":1}"),
                    Map.entry("yca.provider.providers.0.tuning.reasoningEffort", "medium"),
                    Map.entry("yca.provider.providers.0.tuning.defaultVariant", "balanced"),
                    Map.entry("yca.provider.providers.0.tuning.outputTokens", "4096"),
                    Map.entry("yca.provider.providers.0.tuning.systemPromptMaxChars", "5000"),
                    Map.entry("yca.provider.providers.0.tuning.plateauRounds", "3"),
                    Map.entry("yca.provider.providers.0.tuning.maxIterations", "60"),
                    Map.entry("yca.provider.providers.0.tuning.providerSpecificOptions", "{\"frequency_penalty\":0.5}"),
                    Map.entry("yca.provider.providers.0.tuning.variants.length", "2"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.name", "balanced"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.temperature", "0.7"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.top_p", "0.9"),
                    Map.entry("yca.provider.providers.0.tuning.variants.1.name", "reasoning"),
                    Map.entry("yca.provider.providers.0.tuning.variants.1.reasoningEffort", "high"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "claude-sonnet-5"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.reasoningEffort", "low"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.defaultVariant", "fast"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.plateauRounds", "4"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.maxIterations", "90"),
                    Map.entry(
                            "yca.provider.providers.0.models.0.behavior.providerSpecificOptions",
                            "{\"temperature\":0.2}")));
        }
    }

    @Nested
    @FusionSupport(modules = ModelRepairEnabledSources.class)
    class ModelRepairEnabled {
        @Test
        void repairToolCallArgumentsCascadeScenario1(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Model behavior wins over provider tuning.
            final var withBehaviorEnabled = manager(configuration, mapper);
            final var provider = withBehaviorEnabled.providers().get(0);
            assertTrue(
                    withBehaviorEnabled.repairToolCallArguments(provider, "qwen3.8-27b"),
                    "model behavior enabled must win over provider disabled");
        }
    }

    public static class ModelRepairEnabledSources extends ConfigurationTestModule {
        public ModelRepairEnabledSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "qwen3.8-27b"),
                    Map.entry("yca.provider.providers.0.tuning.repairToolCallArguments", "disabled"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "qwen3*"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.repairToolCallArguments", "enabled")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderRepairEnabledSources.class)
    class ProviderRepairEnabled {
        @Test
        void repairToolCallArgumentsCascadeScenario2(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Provider tuning wins over the automatic heuristic.
            final var withProviderEnabled = manager(configuration, mapper);
            final var provider2 = withProviderEnabled.providers().get(0);
            assertTrue(
                    withProviderEnabled.repairToolCallArguments(provider2, "gpt-5-mini"),
                    "provider enabled must apply to any model");
        }
    }

    public static class ProviderRepairEnabledSources extends ConfigurationTestModule {
        public ProviderRepairEnabledSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "gpt-5-mini"),
                    Map.entry("yca.provider.providers.0.tuning.repairToolCallArguments", "enabled")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderRepairDisabledSources.class)
    class ProviderRepairDisabled {
        @Test
        void repairToolCallArgumentsCascadeScenario3(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Provider disabled wins over automatic Qwen 27B repair.
            final var withProviderDisabled = manager(configuration, mapper);
            final var provider3 = withProviderDisabled.providers().get(0);
            assertFalse(
                    withProviderDisabled.repairToolCallArguments(provider3, "qwen3.8-27b"),
                    "provider disabled must win over auto qwen 27B");
        }
    }

    public static class ProviderRepairDisabledSources extends ConfigurationTestModule {
        public ProviderRepairDisabledSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "qwen3.8-27b"),
                    Map.entry("yca.provider.providers.0.tuning.repairToolCallArguments", "disabled")));
        }
    }

    @Nested
    @FusionSupport(modules = AutomaticRepairSources.class)
    class AutomaticRepair {
        @Test
        void repairToolCallArgumentsCascadeScenario4(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Automatic repair enables Qwen 27B and OpenAI-compatible models.
            final var autoOnly = manager(configuration, mapper);
            final var provider4 = autoOnly.providers().get(0);
            assertTrue(autoOnly.repairToolCallArguments(provider4, "qwen3.8-27b"), "auto must enable for qwen 27B");
            // a non-Qwen model on an OpenAI compatible (default OpenAI v1) provider is repaired too
            assertTrue(
                    autoOnly.repairToolCallArguments(provider4, "gpt-5-mini"),
                    "auto must enable for a non-qwen-27B model on an OpenAI compatible provider");
        }
    }

    public static class AutomaticRepairSources extends ConfigurationTestModule {
        public AutomaticRepairSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "qwen3.8-27b")));
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitDialectsSources.class)
    class ExplicitDialects {
        @Test
        void repairToolCallArgumentsCascadeScenario5(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Anthropic stays opted out; OpenAI completions opts in.
            final var dialects = manager(configuration, mapper);
            final var anthropicProvider = dialects.providers().get(0);
            assertFalse(
                    dialects.repairToolCallArguments(anthropicProvider, "claude-3-7-sonnet"),
                    "auto must stay disabled for the non-OpenAI anthropic dialect");
            final var completionsProvider = dialects.providers().get(1);
            assertTrue(
                    dialects.repairToolCallArguments(completionsProvider, "gpt-5-mini"),
                    "auto must enable for an explicit completions (OpenAI) dialect");
        }
    }

    public static class ExplicitDialectsSources extends ConfigurationTestModule {
        public ExplicitDialectsSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "2"),
                    Map.entry("yca.provider.providers.0.id", "a"),
                    Map.entry("yca.provider.providers.0.model", "claude-3-7-sonnet"),
                    Map.entry("yca.provider.providers.0.api", "anthropic"),
                    Map.entry("yca.provider.providers.1.id", "c"),
                    Map.entry("yca.provider.providers.1.model", "gpt-5-mini"),
                    Map.entry("yca.provider.providers.1.api", "completions")));
        }
    }

    @Nested
    @FusionSupport(modules = ModelInBandEnabledSources.class)
    class ModelInBandEnabled {
        @Test
        void inBandToolCallsCascadeScenario1(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Model behavior wins over provider tuning.
            final var withBehaviorEnabled = manager(configuration, mapper);
            final var provider = withBehaviorEnabled.providers().get(0);
            assertTrue(
                    withBehaviorEnabled.inBandToolCalls(provider, "deepseek-v4"),
                    "model behavior enabled must win over provider disabled");
        }
    }

    public static class ModelInBandEnabledSources extends ConfigurationTestModule {
        public ModelInBandEnabledSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "deepseek-v4"),
                    Map.entry("yca.provider.providers.0.tuning.inBandToolCalls", "disabled"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "deepseek*"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.inBandToolCalls", "enabled")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderInBandDisabledSources.class)
    class ProviderInBandDisabled {
        @Test
        void inBandToolCallsCascadeScenario2(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Provider tuning wins over automatic extraction.
            final var withProviderDisabled = manager(configuration, mapper);
            final var provider2 = withProviderDisabled.providers().get(0);
            assertFalse(
                    withProviderDisabled.inBandToolCalls(provider2, "deepseek-v4"),
                    "provider disabled must win over auto");
        }
    }

    public static class ProviderInBandDisabledSources extends ConfigurationTestModule {
        public ProviderInBandDisabledSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "deepseek-v4"),
                    Map.entry("yca.provider.providers.0.tuning.inBandToolCalls", "disabled")));
        }
    }

    @Nested
    @FusionSupport(modules = AutomaticInBandSources.class)
    class AutomaticInBand {
        @Test
        void inBandToolCallsCascadeScenario3(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Automatic mode enables extraction by default.
            final var autoOnly = manager(configuration, mapper);
            final var provider3 = autoOnly.providers().get(0);
            assertTrue(autoOnly.inBandToolCalls(provider3, "gpt-5-mini"), "auto must enable extraction by default");
        }

        @Test
        void reasoningFormatCascadeFollowsBehaviorThenProviderThenAutoScenario3(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Unset reasoning format resolves to automatic mode.
            final var autoOnly = manager(configuration, mapper);
            final var provider3 = autoOnly.providers().get(0);
            assertEquals(ReasoningFormatMode.AUTO, autoOnly.reasoningFormat(provider3, "gpt-5-mini"));
        }
    }

    public static class AutomaticInBandSources extends ConfigurationTestModule {
        public AutomaticInBandSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "gpt-5-mini")));
        }
    }

    @Nested
    @FusionSupport(modules = ModelReasoningFormatSources.class)
    class ModelReasoningFormat {
        @Test
        void reasoningFormatCascadeFollowsBehaviorThenProviderThenAutoScenario1(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Model behavior wins over provider tuning.
            final var withBehavior = manager(configuration, mapper);
            final var provider = withBehavior.providers().get(0);
            assertEquals(
                    ReasoningFormatMode.DEEPSEEK_R1,
                    withBehavior.reasoningFormat(provider, "deepseek-v4"),
                    "model behavior must win over provider tuning");
        }
    }

    public static class ModelReasoningFormatSources extends ConfigurationTestModule {
        public ModelReasoningFormatSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "deepseek-v4"),
                    Map.entry("yca.provider.providers.0.tuning.reasoningFormat", "qwen"),
                    Map.entry("yca.provider.providers.0.models.length", "1"),
                    Map.entry("yca.provider.providers.0.models.0.model", "deepseek*"),
                    Map.entry("yca.provider.providers.0.models.0.behavior.reasoningFormat", "deepseek_r1")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderReasoningFormatSources.class)
    class ProviderReasoningFormat {
        @Test
        void reasoningFormatCascadeFollowsBehaviorThenProviderThenAutoScenario2(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            // Provider tuning applies when model behavior is unset.
            final var withProvider = manager(configuration, mapper);
            final var provider2 = withProvider.providers().get(0);
            assertEquals(
                    ReasoningFormatMode.QWEN,
                    withProvider.reasoningFormat(provider2, "qwen3-coder"),
                    "provider tuning must apply when no model behavior is set");
        }
    }

    public static class ProviderReasoningFormatSources extends ConfigurationTestModule {
        public ProviderReasoningFormatSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "qwen3-coder"),
                    Map.entry("yca.provider.providers.0.tuning.reasoningFormat", "qwen")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderNoLlmSources.class)
    class ProviderNoLlm {
        @Test
        void resolvedCompactionModePerProviderNoLlmOptsInOverGlobalLlm(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            assertEquals(
                    CompactionLlmMode.NO_LLM,
                    manager.resolvedCompactionMode(new ChatSettings("p0", "m", null, null)),
                    "an explicit per-provider no_llm opts the provider into llm-free compaction");
            assertTrue(manager.resolvedIgnoreLlm(new ChatSettings("p0", "m", null, null)));
        }
    }

    public static class ProviderNoLlmSources extends ConfigurationTestModule {
        public ProviderNoLlmSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "m"),
                    Map.entry("yca.provider.providers.0.compaction.mode", "no_llm")));
        }
    }

    @Nested
    @FusionSupport(modules = InheritedNoLlmSources.class)
    class InheritedNoLlm {
        @Test
        void resolvedCompactionModeGlobalNoLlmAppliesWhenPerProviderAbsent(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            assertEquals(
                    CompactionLlmMode.NO_LLM,
                    manager.resolvedCompactionMode(new ChatSettings("p0", "m", null, null)),
                    "an absent per-provider mode (auto) must inherit the global no_llm");
            assertTrue(manager.resolvedIgnoreLlm(new ChatSettings("p0", "m", null, null)));
        }
    }

    public static class InheritedNoLlmSources extends ConfigurationTestModule {
        public InheritedNoLlmSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.compaction.mode", "no_llm"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "m"),
                    Map.entry("yca.provider.providers.0.compaction.retriesMaxAttempts", "5")));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderLlmSources.class)
    class ProviderLlm {
        @Test
        void resolvedCompactionModePerProviderLlmOptsOutOfGlobalNoLlm(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            assertEquals(
                    CompactionLlmMode.LLM,
                    manager.resolvedCompactionMode(new ChatSettings("p0", "m", null, null)),
                    "an explicit per-provider llm opts the provider out of the global no_llm");
            assertFalse(manager.resolvedIgnoreLlm(new ChatSettings("p0", "m", null, null)));
        }
    }

    public static class ProviderLlmSources extends ConfigurationTestModule {
        public ProviderLlmSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.compaction.mode", "no_llm"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "m"),
                    Map.entry("yca.provider.providers.0.compaction.mode", "llm")));
        }
    }

    @Nested
    @FusionSupport(modules = DefaultLlmSources.class)
    class DefaultLlm {
        @Test
        void resolvedCompactionModeDefaultsToLlmWithoutAnyConfiguration(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final JsonMapper mapper) {
            final var manager = manager(configuration, mapper);
            assertEquals(
                    CompactionLlmMode.LLM,
                    manager.resolvedCompactionMode(new ChatSettings("p0", "m", null, null)),
                    "no global and no per-provider mode means the built-in llm");
            assertFalse(manager.resolvedIgnoreLlm(new ChatSettings("p0", "m", null, null)));
        }
    }

    public static class DefaultLlmSources extends ConfigurationTestModule {
        public DefaultLlmSources() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "p0"),
                    Map.entry("yca.provider.providers.0.model", "m")));
        }
    }
}
