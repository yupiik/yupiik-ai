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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.LlmHttpFailureException;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Regression tests for cross-tab background routing: the compaction summarizer must reach the
 * compact model's provider even when the calling session runs on another provider, and two
 * concurrent requests on different providers must route independently.
 */
@ExtendWith(TestJsonMapper.class)
class BackgroundModelRoutingTest {

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** Two providers: {@code main} hosts {@code main-model}, {@code cheap} hosts {@code deepseek-v4-flash}. */
    private static Map<String, String> twoProviderConfigSettings(final String compactModel) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", "x");
        entries.put("yca.workingDir", ".");
        entries.put("yca.provider.providers.length", "2");
        entries.put("yca.provider.providers.0.id", "main");
        entries.put("yca.provider.providers.0.baseUrl", "https://main.example/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "main-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", "128000");
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        entries.put("yca.provider.providers.1.id", "cheap");
        entries.put("yca.provider.providers.1.baseUrl", "https://cheap.example/v1/chat/completions");
        entries.put("yca.provider.providers.1.apiKey", "");
        entries.put("yca.provider.providers.1.model", "deepseek-v4-flash");
        entries.put("yca.provider.providers.1.api", "auto");
        entries.put("yca.provider.providers.1.contextWindow", "128000");
        entries.put("yca.provider.providers.1.timeoutSeconds", "120");
        if (compactModel != null) {
            entries.put("yca.model.compact.name", compactModel);
        }
        entries.put("yca.compaction.tail.budgetTokens", "1");
        entries.put("yca.pruning.keepRecent", "1");
        return entries;
    }

    private static Map<String, String> jsonConfigSettings(
            final String compact, final String mainCompact, final String cheapMemory) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", "x");
        entries.put("yca.workingDir", ".");
        if (compact != null) {
            entries.put("yca.model.compact.name", compact);
        }
        entries.put("yca.provider.providers.length", "2");
        entries.put("yca.provider.providers.0.id", "main");
        entries.put("yca.provider.providers.0.baseUrl", "https://main.example/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "main-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", "128000");
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        if (mainCompact != null) {
            entries.put("yca.provider.providers.0.backgroundModelOverrides.compact.name", mainCompact);
        }
        entries.put("yca.provider.providers.1.id", "cheap");
        entries.put("yca.provider.providers.1.baseUrl", "https://cheap.example/v1/chat/completions");
        entries.put("yca.provider.providers.1.apiKey", "");
        entries.put("yca.provider.providers.1.model", "deepseek-v4-flash");
        entries.put("yca.provider.providers.1.api", "auto");
        entries.put("yca.provider.providers.1.contextWindow", "128000");
        entries.put("yca.provider.providers.1.timeoutSeconds", "120");
        if (cheapMemory != null) {
            entries.put("yca.provider.providers.1.backgroundModelOverrides.memory.name", cheapMemory);
        }
        entries.put("yca.compaction.tail.budgetTokens", "1");
        entries.put("yca.pruning.keepRecent", "1");
        return entries;
    }

    /** Drops a leading {@code $<provider>/} pin when the provider matches (helper for inherit assertions). */
    private static String stripPin(final String reference, final String providerId) {
        final var prefix = "$" + providerId + "/";
        return reference != null && reference.startsWith(prefix) ? reference.substring(prefix.length()) : reference;
    }

    /**
     * A summarizer double emulating the real ChatClient's {@code $provider/model} resolution and a
     * gateway's "No such model" 400 when a provider receives a model it does not host.
     */
    private static final class RoutingChatClient extends ChatClient {
        private final List<String> requestedModels = new ArrayList<>();

        List<String> requestedModels() {
            return requestedModels;
        }

        @Override
        public ChatResult chat(
                final ChatCompletionRequest request,
                final Consumer<PauseEvent> listener,
                final BooleanSupplier cancelled)
                throws IOException, InterruptedException {
            var model = request.model();
            var routedProvider =
                    request.settings() == null ? null : request.settings().providerId();
            // emulate ChatClient: an explicit $provider/model reference routes to that provider
            if (model != null && model.startsWith("$")) {
                final var slash = model.indexOf('/');
                routedProvider = model.substring(1, slash);
                model = model.substring(slash + 1);
            }
            requestedModels.add(model);
            final var hosting = model != null && model.contains("deepseek") ? "cheap" : "main";
            if (routedProvider != null && !hosting.equals(routedProvider)) {
                // the session's provider received a model it does not host: the original bug
                throw new LlmHttpFailureException(400, "{\"message\":\"No such model: " + model + "\"}", null);
            }
            return new ChatResult(Message.assistant("summary part", null), Usage.EMPTY);
        }
    }

    private static List<Message> longHistory() {
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        for (var i = 0; i < 60; i++) {
            messages.add(Message.user("step " + i + " " + "x".repeat(200)));
        }
        messages.add(Message.user("tail"));
        return messages;
    }

    private ProviderManager providerManager(final CodingAgentConfiguration cfg) {
        return new ProviderManager(cfg, mapper);
    }

    private static Map<String, String> codexTargetDefaultsToLowReasoningConfigSettings() {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", "x");
        entries.put("yca.workingDir", ".");
        entries.put("yca.model.compact.name", "$codex/codex-model");
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "codex");
        entries.put("yca.provider.providers.0.baseUrl", "https://chatgpt.com/backend-api/codex");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "codex-model");
        entries.put("yca.provider.providers.0.api", "codex");
        entries.put("yca.provider.providers.0.contextWindow", "128000");
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        entries.put("yca.compaction.tail.budgetTokens", "1");
        entries.put("yca.pruning.keepRecent", "1");
        return entries;
    }

    private static Map<String, String> explicitCompactTuningMaxTokensAndEffortOverrideTheDefaultsConfigSettings() {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", "x");
        entries.put("yca.workingDir", ".");
        entries.put("yca.model.compact.name", "$main/main-model");
        entries.put("yca.model.compact.max_tokens", "4096");
        entries.put("yca.model.compact.reasoningEffort", "high");
        entries.put("yca.provider.providers.0.id", "main");
        entries.put("yca.provider.providers.0.baseUrl", "https://main.example/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "main-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", "128000");
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        entries.put("yca.provider.providers.1.id", "cheap");
        entries.put("yca.provider.providers.1.baseUrl", "https://cheap.example/v1/chat/completions");
        entries.put("yca.provider.providers.1.apiKey", "");
        entries.put("yca.provider.providers.1.model", "deepseek-v4-flash");
        entries.put("yca.provider.providers.1.api", "auto");
        entries.put("yca.provider.providers.1.contextWindow", "128000");
        entries.put("yca.provider.providers.1.timeoutSeconds", "120");
        entries.put("yca.compaction.tail.budgetTokens", "1");
        entries.put("yca.pruning.keepRecent", "1");
        return entries;
    }

    private static Map<String, String> blankOverrideMeansTheProviderOwnDefaultModelConfigSettings() {
        return new HashMap<>(Map.ofEntries(
                Map.entry("yca.storageHome", "x"),
                Map.entry("yca.workingDir", "."),
                Map.entry("yca.model.compact.name", "global-compact"),
                Map.entry("yca.provider.providers.length", "2"),
                Map.entry("yca.provider.providers.0.id", "main"),
                Map.entry("yca.provider.providers.0.baseUrl", "https://main.example/v1/chat/completions"),
                Map.entry("yca.provider.providers.0.apiKey", ""),
                Map.entry("yca.provider.providers.0.model", "main-model"),
                Map.entry("yca.provider.providers.0.api", "auto"),
                Map.entry("yca.provider.providers.0.contextWindow", "128000"),
                Map.entry("yca.provider.providers.0.timeoutSeconds", "120"),
                Map.entry("yca.provider.providers.0.backgroundModelOverrides.prune.name", "main-mini"),
                Map.entry("yca.provider.providers.1.id", "cheap"),
                Map.entry("yca.provider.providers.1.baseUrl", "https://cheap.example/v1/chat/completions"),
                Map.entry("yca.provider.providers.1.apiKey", ""),
                Map.entry("yca.provider.providers.1.model", "deepseek-v4-flash"),
                Map.entry("yca.provider.providers.1.api", "auto"),
                Map.entry("yca.provider.providers.1.contextWindow", "128000"),
                Map.entry("yca.provider.providers.1.timeoutSeconds", "120")));
    }

    @Nested
    @FusionSupport(modules = BackgroundModelOverridesParsePerProviderModule.class)
    class BackgroundModelOverridesParsePerProvider {
        /** Verifies how the config layer parses backgroundModelOverrides entries. */
        @Test
        void backgroundModelOverridesParsePerProvider(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var pm = providerManager(cfg);
            assertEquals(
                    "main-mini",
                    pm.provider("main").backgroundModelOverrides().compact().name());
            assertEquals(
                    "inherit",
                    pm.provider("cheap").backgroundModelOverrides().memory().name());
        }
    }

    public static class BackgroundModelOverridesParsePerProviderModule extends ConfigurationTestModule {
        public BackgroundModelOverridesParsePerProviderModule() {
            super(jsonConfigSettings("global-compact", "main-mini", "inherit"));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderLevelCompactWinsOverTheGlobalOneModule.class)
    class ProviderLevelCompactWinsOverTheGlobalOne {
        @Test
        void providerLevelCompactWinsOverTheGlobalOne(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var pm = providerManager(cfg);
            // a session on 'main' uses its provider override, not the global ref
            assertEquals("$main/main-mini", pm.resolvedCompact(new ChatSettings("main", null, null, null)));
            // a session on 'cheap' has no override: the global (routed) ref applies
            assertEquals("$cheap/deepseek-v4-flash", pm.resolvedCompact(new ChatSettings("cheap", null, null, null)));
        }
    }

    public static class ProviderLevelCompactWinsOverTheGlobalOneModule extends ConfigurationTestModule {
        public ProviderLevelCompactWinsOverTheGlobalOneModule() {
            super(jsonConfigSettings("$cheap/deepseek-v4-flash", "main-mini", null));
        }
    }

    @Nested
    @FusionSupport(modules = BlankOverrideMeansTheProviderOwnDefaultModelModule.class)
    class BlankOverrideMeansTheProviderOwnDefaultModel {
        @Test
        void blankOverrideMeansTheProviderOwnDefaultModel(@Fusion final CodingAgentConfiguration configuration) {
            // 'main' configures prune but leaves compact blank: compact opts out (the provider's own
            // default model applies) while prune still resolves to its override
            final var withPrune = configuration;
            final var pm = providerManager(withPrune);
            assertNull(pm.resolvedCompact(new ChatSettings("main", null, null, null)));
            assertEquals("$main/main-mini", pm.resolvedPrune(new ChatSettings("main", null, null, null)));
            // the global chain still applies to a provider without any overrides group
            assertEquals("global-compact", pm.resolvedCompact(new ChatSettings("cheap", null, null, null)));
        }
    }

    public static class BlankOverrideMeansTheProviderOwnDefaultModelModule extends ConfigurationTestModule {
        public BlankOverrideMeansTheProviderOwnDefaultModelModule() {
            super(blankOverrideMeansTheProviderOwnDefaultModelConfigSettings());
        }
    }

    @Nested
    @FusionSupport(modules = InheritKeywordResolvesTheGlobalChainModule.class)
    class InheritKeywordResolvesTheGlobalChain {
        @Test
        void inheritKeywordResolvesTheGlobalChain(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var pm = providerManager(cfg);
            assertEquals("global-compact", pm.resolvedCompact(new ChatSettings("main", null, null, null)));
            // memory "inherit" resolves the global resolvedMemory chain (memory -> compact fallback)
            assertEquals(
                    "global-compact",
                    stripPin(pm.resolvedMemory(new ChatSettings("cheap", null, null, null)), "cheap"));
        }
    }

    public static class InheritKeywordResolvesTheGlobalChainModule extends ConfigurationTestModule {
        public InheritKeywordResolvesTheGlobalChainModule() {
            super(jsonConfigSettings("global-compact", "inherit", "inherit"));
        }
    }

    @Nested
    @FusionSupport(modules = MemoryModelResolvesPerProviderModule.class)
    class MemoryModelResolvesPerProvider {
        @Test
        void memoryModelResolvesPerProvider(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var pm = providerManager(cfg);
            // a bare provider-level memory name is pinned to its provider
            assertEquals("$cheap/deepseek-v4-flash", pm.resolvedMemory(new ChatSettings("cheap", null, null, null)));
            // 'main' has no override: the global chain applies (bare name kept as-is: no models list
            // identifies the hosting provider)
            assertEquals("global-compact", pm.resolvedMemory(new ChatSettings("main", null, null, null)));
        }
    }

    public static class MemoryModelResolvesPerProviderModule extends ConfigurationTestModule {
        public MemoryModelResolvesPerProviderModule() {
            super(jsonConfigSettings("global-compact", null, "deepseek-v4-flash"));
        }
    }

    @Nested
    @FusionSupport(modules = CompactModelRoutesToItsOwnProviderWhenTheSessionRunsOnAnotherModule.class)
    class CompactModelRoutesToItsOwnProviderWhenTheSessionRunsOnAnother {
        @Test
        void compactModelRoutesToItsOwnProviderWhenTheSessionRunsOnAnother(
                @Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var chat = new RoutingChatClient();
            final var coordinator = new CompactionCoordinator(
                    cfg,
                    chat,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    providerManager(cfg),
                    null,
                    "s1",
                    new BackoffSleeper(),
                    (cfg).resolveWorkingDir());
            // the session (tab 1) runs on 'main'
            final var result = coordinator.compact(
                    longHistory(), true, null, 0, () -> false, new ChatSettings("main", null, null, null));
            assertEquals(
                    CompactionResult.Mode.SUMMARY,
                    result.mode(),
                    "compaction must summarize through the LLM: " + result.error());
            assertTrue(
                    chat.requestedModels().stream().anyMatch(m -> m != null && m.contains("deepseek")),
                    "the compact model must be requested: " + chat.requestedModels());
        }
    }

    public static class CompactModelRoutesToItsOwnProviderWhenTheSessionRunsOnAnotherModule
            extends ConfigurationTestModule {
        public CompactModelRoutesToItsOwnProviderWhenTheSessionRunsOnAnotherModule() {
            super(twoProviderConfigSettings("$cheap/deepseek-v4-flash"));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModelModule.class)
    class SessionModel {
        @Test
        void compactModelRoutesToTheSessionProviderWhenNoCompactModelIsConfigured(
                @Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var chat = new RoutingChatClient();
            final var coordinator = new CompactionCoordinator(
                    cfg,
                    chat,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    providerManager(cfg),
                    null,
                    "s1",
                    new BackoffSleeper(),
                    (cfg).resolveWorkingDir());
            // session on 'cheap' with an explicit model: the summarizer must reuse it, not the process default
            final var result = coordinator.compact(
                    longHistory(),
                    true,
                    null,
                    0,
                    () -> false,
                    new ChatSettings("cheap", "deepseek-v4-flash", null, null));
            assertEquals(
                    CompactionResult.Mode.SUMMARY,
                    result.mode(),
                    "compaction must summarize through the LLM: " + result.error());
            assertTrue(
                    chat.requestedModels().stream().anyMatch(m -> "deepseek-v4-flash".equals(m)),
                    "the session model must be requested: " + chat.requestedModels());
        }

        @Test
        void summarizerRequestCarriesTheDefaultOutputBudgetAndNoEffortOnNonCodexTargets(
                @Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var captured = new ArrayList<ChatCompletionRequest>();
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    captured.add(request);
                    return new ChatResult(Message.assistant("summary", null), Usage.EMPTY);
                }
            };
            final var coordinator = new CompactionCoordinator(
                    cfg,
                    chat,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    providerManager(cfg),
                    null,
                    "s1",
                    new BackoffSleeper(),
                    (cfg).resolveWorkingDir());
            final var result = coordinator.compact(
                    longHistory(), true, null, 0, () -> false, new ChatSettings("main", "main-model", null, null));
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode(), result.error());
            assertFalse(captured.isEmpty());
            for (final var request : captured) {
                assertEquals(
                        Integer.valueOf(8192),
                        request.maxTokens(),
                        "an unset compact tuning maxTokens must default to the finite summarizer budget");
                assertNull(request.reasoningEffort(), "a non-Codex target must not have an effort forced on it");
            }
        }
    }

    public static class SessionModelModule extends ConfigurationTestModule {
        public SessionModelModule() {
            super(twoProviderConfigSettings(null));
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitCompactTuningMaxTokensAndEffortOverrideTheDefaultsModule.class)
    class ExplicitCompactTuningMaxTokensAndEffortOverrideTheDefaults {
        @Test
        void explicitCompactTuningMaxTokensAndEffortOverrideTheDefaults(
                @Fusion final CodingAgentConfiguration configuration) {
            // add an explicit compact tuning: maxTokens and reasoningEffort must be preserved
            final var explicitCfg = configuration;
            final var captured = new ArrayList<ChatCompletionRequest>();
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    captured.add(request);
                    return new ChatResult(Message.assistant("summary", null), Usage.EMPTY);
                }
            };
            final var coordinator = new CompactionCoordinator(
                    explicitCfg,
                    chat,
                    new UsageTracker(providerManager(explicitCfg), chat, null, Clock.systemUTC()),
                    providerManager(explicitCfg),
                    null,
                    "s1",
                    new BackoffSleeper(),
                    (explicitCfg).resolveWorkingDir());
            final var result = coordinator.compact(
                    longHistory(), true, null, 0, () -> false, new ChatSettings("main", "main-model", null, null));
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode(), result.error());
            assertFalse(captured.isEmpty());
            // an explicit positive tuning maxTokens always wins over the 8192 default
            assertEquals(Integer.valueOf(4096), captured.getFirst().maxTokens());
            // an explicit tuning reasoningEffort is preserved (kept by put-if-absent)
            assertEquals("high", captured.getFirst().reasoningEffort());
        }
    }

    public static class ExplicitCompactTuningMaxTokensAndEffortOverrideTheDefaultsModule
            extends ConfigurationTestModule {
        public ExplicitCompactTuningMaxTokensAndEffortOverrideTheDefaultsModule() {
            super(explicitCompactTuningMaxTokensAndEffortOverrideTheDefaultsConfigSettings());
        }
    }

    @Nested
    @FusionSupport(modules = CodexTargetDefaultsToLowReasoningModule.class)
    class CodexTargetDefaultsToLowReasoning {
        @Test
        void codexTargetDefaultsToLowReasoning(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var captured = new ArrayList<ChatCompletionRequest>();
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    captured.add(request);
                    return new ChatResult(Message.assistant("summary", null), Usage.EMPTY);
                }
            };
            final var coordinator = new CompactionCoordinator(
                    cfg,
                    chat,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    providerManager(cfg),
                    null,
                    "s1",
                    new BackoffSleeper(),
                    (cfg).resolveWorkingDir());
            final var result = coordinator.compact(
                    longHistory(), true, null, 0, () -> false, new ChatSettings("codex", "codex-model", null, null));
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode(), result.error());
            assertFalse(captured.isEmpty());
            assertEquals(
                    "low",
                    captured.getFirst().reasoningEffort(),
                    "an unconfigured Codex-backed summarizer must default to low reasoning");
            assertEquals(
                    Integer.valueOf(8192),
                    captured.getFirst().maxTokens(),
                    "the default output budget applies to the Codex path too");
        }
    }

    public static class CodexTargetDefaultsToLowReasoningModule extends ConfigurationTestModule {
        public CodexTargetDefaultsToLowReasoningModule() {
            super(codexTargetDefaultsToLowReasoningConfigSettings());
        }
    }
}
