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
package io.yupiik.ia.agent.core.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class ModelStyleServiceTest {

    private JsonMapper mapper;
    private ChatClient chatClient;
    private final List<ExtendedHttpClient> httpClients = new ArrayList<>();
    private final List<ChatClient> chatClients = new ArrayList<>();
    private ModelStyleService service;
    private ProviderManager providerManager;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    @AfterEach
    void tearDown() {
        chatClients.forEach(ChatClient::close);
        httpClients.forEach(ExtendedHttpClient::close);
    }

    private ChatClient client(final CodingAgentConfiguration configuration, final ProviderManager providers) {
        final var httpClient =
                new HttpClientProducer().httpClient(configuration, new NativeProviderConfiguration(null, null));
        httpClients.add(httpClient);
        final var client = new ChatClient(configuration, mapper, providers, Clock.systemUTC(), httpClient);
        chatClients.add(client);
        return client;
    }

    private void init(final CodingAgentConfiguration cfg) {

        providerManager = new ProviderManager(cfg, mapper);
        chatClient = client(cfg, providerManager);
        service = new ModelStyleService(cfg, chatClient);
    }

    @Nested
    @FusionSupport(modules = FirstTwoListedModelsAreBoldByDefaultModule.class)
    class FirstTwoListedModelsAreBoldByDefault {
        @TempDir
        static Path storageHome;

        @Test
        void firstTwoListedModelsAreBoldByDefault(@Fusion final CodingAgentConfiguration configuration) {
            init(configuration);
            final var provider = providerManager.providers().get(0);
            assertTrue(service.resolve(provider, "claude-opus-4", true).bold());
            assertTrue(service.resolve(provider, "claude-sonnet-5", true).bold());
            assertFalse(service.resolve(provider, "claude-haiku-4-5", true).bold());
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitModelColorAndBoldWinModule.class)
    class ExplicitModelColorAndBoldWin {
        @TempDir
        static Path storageHome;

        @Test
        void explicitModelColorAndBoldWin(@Fusion final CodingAgentConfiguration configuration) {
            init(configuration);
            final var provider = providerManager.providers().get(0);
            final var style = service.resolve(provider, "claude-haiku-4-5", true);
            assertTrue(isMagenta(style.hex()), "explicit color should drive the tone: " + style.hex());
            assertFalse(style.bold());
        }
    }

    @Nested
    @FusionSupport(modules = ModelColorWinsOverProviderToneModule.class)
    class ModelColorWinsOverProviderTone {
        @TempDir
        static Path storageHome;

        @Test
        void modelColorWinsOverProviderTone(@Fusion final CodingAgentConfiguration configuration) {
            init(configuration);
            final var provider = providerManager.providers().get(0);
            assertTrue(isGreen(service.resolve(provider, "claude-opus-4", true).hex()));
            assertTrue(isMagenta(
                    service.resolve(provider, "claude-haiku-4-5", true).hex()));
        }
    }

    @Nested
    @FusionSupport(modules = ContainsMatchingAppliesTheStyleModule.class)
    class ContainsMatchingAppliesTheStyle {
        @TempDir
        static Path storageHome;

        @Test
        void containsMatchingAppliesTheStyle(@Fusion final CodingAgentConfiguration configuration) {
            init(configuration);
            final var provider = providerManager.providers().get(0);
            final var style = service.resolve(provider, "claude-haiku-4-5-20251001", true);
            assertTrue(isMagenta(style.hex()), "contains match should apply the model style: " + style.hex());
            assertFalse(style.bold());
        }
    }

    @Nested
    @FusionSupport(modules = UnlistedModelsAreBoldAndDeterministicModule.class)
    class UnlistedModelsAreBoldAndDeterministic {
        @TempDir
        static Path storageHome;

        @Test
        void unlistedModelsAreBoldAndDeterministic(@Fusion final CodingAgentConfiguration configuration) {
            init(configuration);
            final var provider = providerManager.providers().get(0);
            assertTrue(service.resolve(provider, "some-unknown-model", true).bold());
            assertNotEquals(
                    service.resolve(provider, "some-unknown-model", true).hex(),
                    service.resolve(provider, "another-unknown-model", true).hex());
        }
    }

    @Nested
    @FusionSupport(modules = ContrastIsAdjustedAgainstTheBackgroundModule.class)
    class ContrastIsAdjustedAgainstTheBackground {
        @TempDir
        static Path storageHome;

        @Test
        void contrastIsAdjustedAgainstTheBackground(@Fusion final CodingAgentConfiguration configuration) {
            init(configuration);
            final var provider = providerManager.providers().get(0);
            assertNotEquals(
                    service.resolve(provider, "claude-opus-4", true).hex(),
                    service.resolve(provider, "claude-opus-4", false).hex());
        }
    }

    @Nested
    @FusionSupport(modules = CurrentUsesTheChatClientModelAndPersistedThemeModule.class)
    class CurrentUsesTheChatClientModelAndPersistedTheme {
        @TempDir
        static Path storageHome;

        @Test
        void currentUsesTheChatClientModelAndPersistedTheme(@Fusion final CodingAgentConfiguration configuration) {
            init(configuration);
            final var provider = providerManager.providers().get(0);
            final var current = service.current();
            assertEquals(service.resolve(provider, "claude-opus-4", true).hex(), current.hex());
            assertEquals(service.resolve(provider, "claude-opus-4", true).bold(), current.bold());
        }
    }

    @Nested
    @FusionSupport(modules = AnsiPrefixCarriesTrueColorAndOptionalBoldModule.class)
    class AnsiPrefixCarriesTrueColorAndOptionalBold {
        @TempDir
        static Path storageHome;

        @Test
        void ansiPrefixCarriesTrueColorAndOptionalBold(@Fusion final CodingAgentConfiguration configuration) {
            init(configuration);
            final var provider = providerManager.providers().get(0);
            final var boldStyle = service.resolve(provider, "claude-sonnet-5", true);
            assertTrue(boldStyle.ansiPrefix().contains("\u001b[38;2;"));
            assertTrue(boldStyle.ansiPrefix().contains("\u001b[1m"));
            final var plainStyle = service.resolve(provider, "claude-haiku-4-5", true);
            assertFalse(plainStyle.ansiPrefix().contains("\u001b[1m"));
        }
    }

    private void initValueBased(final CodingAgentConfiguration cfg) {

        providerManager = new ProviderManager(cfg, mapper);
        chatClient = client(cfg, providerManager);
        service = new ModelStyleService(cfg, chatClient);
    }

    @Nested
    @FusionSupport(modules = IdDerivedColorIsDeterministicAcrossCallsModule.class)
    class IdDerivedColorIsDeterministicAcrossCalls {
        @TempDir
        static Path storageHome;

        @Test
        void idDerivedColorIsDeterministicAcrossCalls(@Fusion final CodingAgentConfiguration configuration) {
            initValueBased(configuration);
            final var provider = providerManager.providers().get(0);
            assertEquals(
                    service.resolve(provider, "model-a", true).hex(),
                    service.resolve(provider, "model-a", true).hex(),
                    "same (provider, model) must resolve to the same color");
        }
    }

    @Nested
    @FusionSupport(modules = DifferentProviderIdsProduceDifferentFamilyHuesModule.class)
    class DifferentProviderIdsProduceDifferentFamilyHues {
        @TempDir
        static Path storageHome;

        @Test
        void differentProviderIdsProduceDifferentFamilyHues(@Fusion final CodingAgentConfiguration configuration) {
            initValueBased(configuration);
            final var alpha = providerManager.providers().get(0);
            final var beta = providerManager.providers().get(1);
            assertNotEquals(
                    service.resolve(alpha, "model-a", true).hex(),
                    service.resolve(beta, "model-a", true).hex(),
                    "two providers sharing a model name should not collide on the family hue");
        }
    }

    @Nested
    @FusionSupport(modules = DifferentModelsUnderOneProviderProduceDistinctShadesModule.class)
    class DifferentModelsUnderOneProviderProduceDistinctShades {
        @TempDir
        static Path storageHome;

        @Test
        void differentModelsUnderOneProviderProduceDistinctShades(
                @Fusion final CodingAgentConfiguration configuration) {
            initValueBased(configuration);
            final var provider = providerManager.providers().get(0);
            assertNotEquals(
                    service.resolve(provider, "model-a", true).hex(),
                    service.resolve(provider, "model-b", true).hex(),
                    "distinct model names under one provider should get distinguishable shades");
        }
    }

    @Nested
    @FusionSupport(modules = ListedModelColorIsInvariantToListPositionModule.class)
    class ListedModelColorIsInvariantToListPosition {
        @TempDir
        static Path storageHome;

        @Test
        void listedModelColorIsInvariantToListPosition(@Fusion final CodingAgentConfiguration configuration) {
            final CodingAgentConfiguration first = configuration;
            final var firstManager = new ProviderManager(first, mapper);
            final var firstClient = client(first, firstManager);
            final var firstService = new ModelStyleService(first, firstClient);
            final var ax = firstService
                    .resolve(firstManager.providers().get(0), "model-c", true)
                    .hex();

            final var original = firstManager.providers().getFirst();
            final var reordered = reorderedProvider(original);
            final var reorderedService = new ModelStyleService(first, firstClient);
            final var cx = reorderedService.resolve(reordered, "model-c", true).hex();

            assertEquals(ax, cx, "reordering the models list must not change a model's color");
        }
    }

    private static int channel(final String hex, final int offset) {
        return Integer.parseInt(hex.substring(1 + offset * 2, 3 + offset * 2), 16);
    }

    private static boolean isMagenta(final String hex) {
        return channel(hex, 0) > 180 && channel(hex, 2) > 180 && channel(hex, 1) < 80;
    }

    private static boolean isGreen(final String hex) {
        return channel(hex, 1) > 180 && channel(hex, 0) < 160 && channel(hex, 2) < 160;
    }

    private static Map<String, String> initValueBasedSettings(final Path storageHome) {
        return Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "2"),
                Map.entry("yca.provider.providers.0.id", "alpha"),
                Map.entry("yca.provider.providers.0.model", "model-a"),
                Map.entry("yca.provider.providers.0.models.length", "2"),
                Map.entry("yca.provider.providers.0.models.0.model", "model-a"),
                Map.entry("yca.provider.providers.0.models.1.model", "model-b"),
                Map.entry("yca.provider.providers.1.id", "beta"),
                Map.entry("yca.provider.providers.1.model", "model-a"),
                Map.entry("yca.provider.providers.1.models.length", "1"),
                Map.entry("yca.provider.providers.1.models.0.model", "model-a"));
    }

    private static Map<String, String> initSettings(final Path storageHome) {
        return Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "2"),
                Map.entry("yca.provider.providers.0.id", "tone"),
                Map.entry("yca.provider.providers.0.model", "claude-opus-4"),
                Map.entry("yca.provider.providers.0.color", "#00ff00"),
                Map.entry("yca.provider.providers.0.models.length", "3"),
                Map.entry("yca.provider.providers.0.models.0.model", "claude-opus-4"),
                Map.entry("yca.provider.providers.0.models.1.model", "claude-sonnet-5"),
                Map.entry("yca.provider.providers.0.models.2.model", "claude-haiku-4-5"),
                Map.entry("yca.provider.providers.0.models.2.style.color", "#ff00ff"),
                Map.entry("yca.provider.providers.0.models.2.style.bold", "false"),
                Map.entry("yca.provider.providers.1.id", "plain"),
                Map.entry("yca.provider.providers.1.model", "gpt-5"),
                Map.entry("yca.provider.providers.1.models.length", "1"),
                Map.entry("yca.provider.providers.1.models.0.model", "gpt-5"));
    }

    private static Map<String, String> positionSettings(final Path storageHome) {
        return Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "alpha"),
                Map.entry("yca.provider.providers.0.model", "model-a"),
                Map.entry("yca.provider.providers.0.models.length", "3"),
                Map.entry("yca.provider.providers.0.models.0.model", "model-a"),
                Map.entry("yca.provider.providers.0.models.1.model", "model-b"),
                Map.entry("yca.provider.providers.0.models.2.model", "model-c"));
    }

    private static ProviderConfiguration reorderedProvider(final ProviderConfiguration baseline) {
        return new ProviderConfiguration(
                baseline.id(),
                baseline.baseUrl(),
                baseline.apiKey(),
                baseline.model(),
                baseline.api(),
                baseline.contextWindow(),
                baseline.outputTokens(),
                baseline.releaseDate(),
                baseline.tuning(),
                baseline.models().reversed(),
                baseline.maxConcurrent(),
                baseline.timeoutSeconds(),
                baseline.providerSpecificOptions(),
                baseline.headers(),
                baseline.dynamicHeaders(),
                baseline.color(),
                baseline.prompt(),
                baseline.subagents(),
                baseline.backgroundModelOverrides(),
                baseline.compaction(),
                baseline.capabilities());
    }

    public static class FirstTwoListedModelsAreBoldByDefaultModule extends ConfigurationTestModule {
        public FirstTwoListedModelsAreBoldByDefaultModule() {
            super(initSettings(FirstTwoListedModelsAreBoldByDefault.storageHome));
        }
    }

    public static class ExplicitModelColorAndBoldWinModule extends ConfigurationTestModule {
        public ExplicitModelColorAndBoldWinModule() {
            super(initSettings(ExplicitModelColorAndBoldWin.storageHome));
        }
    }

    public static class ModelColorWinsOverProviderToneModule extends ConfigurationTestModule {
        public ModelColorWinsOverProviderToneModule() {
            super(initSettings(ModelColorWinsOverProviderTone.storageHome));
        }
    }

    public static class ContainsMatchingAppliesTheStyleModule extends ConfigurationTestModule {
        public ContainsMatchingAppliesTheStyleModule() {
            super(initSettings(ContainsMatchingAppliesTheStyle.storageHome));
        }
    }

    public static class UnlistedModelsAreBoldAndDeterministicModule extends ConfigurationTestModule {
        public UnlistedModelsAreBoldAndDeterministicModule() {
            super(initSettings(UnlistedModelsAreBoldAndDeterministic.storageHome));
        }
    }

    public static class ContrastIsAdjustedAgainstTheBackgroundModule extends ConfigurationTestModule {
        public ContrastIsAdjustedAgainstTheBackgroundModule() {
            super(initSettings(ContrastIsAdjustedAgainstTheBackground.storageHome));
        }
    }

    public static class CurrentUsesTheChatClientModelAndPersistedThemeModule extends ConfigurationTestModule {
        public CurrentUsesTheChatClientModelAndPersistedThemeModule() {
            super(initSettings(CurrentUsesTheChatClientModelAndPersistedTheme.storageHome));
        }
    }

    public static class AnsiPrefixCarriesTrueColorAndOptionalBoldModule extends ConfigurationTestModule {
        public AnsiPrefixCarriesTrueColorAndOptionalBoldModule() {
            super(initSettings(AnsiPrefixCarriesTrueColorAndOptionalBold.storageHome));
        }
    }

    public static class IdDerivedColorIsDeterministicAcrossCallsModule extends ConfigurationTestModule {
        public IdDerivedColorIsDeterministicAcrossCallsModule() {
            super(initValueBasedSettings(IdDerivedColorIsDeterministicAcrossCalls.storageHome));
        }
    }

    public static class DifferentProviderIdsProduceDifferentFamilyHuesModule extends ConfigurationTestModule {
        public DifferentProviderIdsProduceDifferentFamilyHuesModule() {
            super(initValueBasedSettings(DifferentProviderIdsProduceDifferentFamilyHues.storageHome));
        }
    }

    public static class DifferentModelsUnderOneProviderProduceDistinctShadesModule extends ConfigurationTestModule {
        public DifferentModelsUnderOneProviderProduceDistinctShadesModule() {
            super(initValueBasedSettings(DifferentModelsUnderOneProviderProduceDistinctShades.storageHome));
        }
    }

    public static class ListedModelColorIsInvariantToListPositionModule extends ConfigurationTestModule {
        public ListedModelColorIsInvariantToListPositionModule() {
            super(positionSettings(ListedModelColorIsInvariantToListPosition.storageHome));
        }
    }
}
