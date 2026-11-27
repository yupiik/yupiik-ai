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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * {@code yca.compaction.ignoreLlm}: a global/per-provider toggle that makes compaction run only local
 * strategies (prune + spill + tight splice) and never call the LLM. The cascade reuses the existing
 * reduction machinery and lands under the same trigger; the per-provider override wins over the
 * global, an absent block inherits the global, and the LLM-free contract holds for automatic,
 * urgent/force and (via the same compact entry) overflow compactions.
 */
@ExtendWith(TestJsonMapper.class)
class IgnoreLlmCompactionTest {

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private static Map<String, String> configSettings(final int window, final Map<String, String> extra) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", "x");
        entries.put("yca.workingDir", ".");
        entries.put("yca.provider.providers.length", "2");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.baseUrl", "https://api.openai.com/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "m");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", String.valueOf(window));
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        entries.put("yca.provider.providers.1.id", "other");
        entries.put("yca.provider.providers.1.model", "m2");
        entries.put("yca.provider.providers.1.contextWindow", String.valueOf(window));
        entries.put("yca.compaction.tail.budgetTokens", "1");
        entries.put("yca.pruning.keepRecent", "1");
        entries.putAll(extra);
        return entries;
    }

    private ProviderManager providerManager(final CodingAgentConfiguration cfg) {
        return new ProviderManager(cfg, mapper);
    }

    private BackoffSleeper noOpSleeper() {
        return new BackoffSleeper() {
            @Override
            public boolean sleep(final long millis) {
                return true;
            }
        };
    }

    /** A ChatClient that fails the test the moment any chat() is invoked - the LLM-free contract. */
    private ChatClient explodingChat(final AtomicInteger calls) {
        return new ChatClient() {
            @Override
            public ChatResult chat(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> pauseListener,
                    final BooleanSupplier cancelled)
                    throws IOException {
                calls.incrementAndGet();
                throw new AssertionError("ignoreLlm compaction must never call the LLM");
            }
        };
    }

    private CompactionCoordinator coordinator(
            final CodingAgentConfiguration cfg, final ChatClient chat, final AtomicInteger calls) {
        return new CompactionCoordinator(
                cfg,
                chat,
                new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                providerManager(cfg),
                null,
                "s1",
                noOpSleeper(),
                (cfg).resolveWorkingDir());
    }

    /** Old part is heavy (long user/tool turns) and the tail is tiny: compaction must reclaim locally. */
    private static List<Message> oldHeavyHistory() {
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        for (var i = 0; i < 20; i++) {
            // ~30k chars each: well above the 500-char splice cap and enough to exceed the
            // compaction trigger on a small window, so the local cascade must actually reclaim
            messages.add(Message.user("step " + i + " " + "x".repeat(30_000)));
        }
        messages.add(Message.user("tail"));
        return messages;
    }

    @Nested
    @FusionSupport(modules = GlobalLlmFreeModule.class)
    class GlobalLlmFree {
        @Test
        void globalIgnoreLlmNeverCallsTheLlmAndReclaimsUnderTheTrigger(
                @Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var calls = new AtomicInteger();
            final var coordinator = coordinator(cfg, explodingChat(calls), calls);
            final var messages = oldHeavyHistory();
            final var result = coordinator.compact(
                    messages, false, null, 0, () -> false, new ChatSettings("default", "m", null, null));
            assertTrue(result.changed(), "local cascade must change the history");
            assertNotEquals(CompactionResult.Mode.SUMMARY, result.mode(), "never a summary under ignoreLlm");
            assertEquals(0, calls.get(), "no chat() call under ignoreLlm");
        }

        @Test
        void urgentForceCompactionIsAlsoLlmFree(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var calls = new AtomicInteger();
            final var coordinator = coordinator(cfg, explodingChat(calls), calls);
            final var messages = oldHeavyHistory();
            final var result = coordinator.compact(
                    messages, true, null, 0, true, () -> false, new ChatSettings("default", "m", null, null));
            assertTrue(result.changed(), "urgent local cascade must reclaim");
            assertEquals(0, calls.get(), "urgent compaction must also never call the LLM");
        }
    }

    public static class GlobalLlmFreeModule extends ConfigurationTestModule {
        public GlobalLlmFreeModule() {
            super(configSettings(
                    128_000,
                    Map.of(
                            "yca.compaction.mode", "no_llm",
                            "yca.pruning.enabled", "true",
                            "yca.pruning.shrinkOutputChars", "200")));
        }
    }

    @Nested
    @FusionSupport(modules = PerProviderTrueOptsInOverGlobalFalseModule.class)
    class PerProviderTrueOptsInOverGlobalFalse {
        @Test
        void perProviderTrueOptsInOverGlobalFalse(@Fusion final CodingAgentConfiguration configuration) {
            // global mode=llm, but provider "other" explicitly opts into the LLM-free path
            final var cfg = configuration;
            final var manager = providerManager(cfg);
            final var coordinator = coordinator(cfg, explodingChat(new AtomicInteger()), new AtomicInteger());
            // the global default (llm) applies to the default provider
            assertFalse(manager.resolvedIgnoreLlm(new ChatSettings("default", "m", null, null)));
            assertFalse(coordinator.ignoreLlm(new ChatSettings("default", "m", null, null)));
            // per-provider no_llm opts "other" in
            assertTrue(manager.resolvedIgnoreLlm(new ChatSettings("other", "m2", null, null)));
            assertTrue(coordinator.ignoreLlm(new ChatSettings("other", "m2", null, null)));
        }
    }

    public static class PerProviderTrueOptsInOverGlobalFalseModule extends ConfigurationTestModule {
        public PerProviderTrueOptsInOverGlobalFalseModule() {
            super(configSettings(
                    128_000,
                    Map.of(
                            "yca.provider.providers.1.compaction.mode", "no_llm",
                            "yca.provider.providers.1.compaction.retriesMaxAttempts", "2")));
        }
    }

    @Nested
    @FusionSupport(modules = AbsentPerProviderBlockInheritsGlobalModule.class)
    class AbsentPerProviderBlockInheritsGlobal {
        @Test
        void absentPerProviderBlockInheritsGlobal(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var manager = providerManager(cfg);
            // the per-provider block exists (retries set) but mode is absent -> inherits global no_llm
            assertTrue(manager.resolvedIgnoreLlm(new ChatSettings("other", "m2", null, null)));
        }
    }

    public static class AbsentPerProviderBlockInheritsGlobalModule extends ConfigurationTestModule {
        public AbsentPerProviderBlockInheritsGlobalModule() {
            super(configSettings(
                    128_000,
                    Map.of(
                            "yca.compaction.mode", "no_llm",
                            "yca.provider.providers.1.compaction.retriesMaxAttempts", "5")));
        }
    }

    @Nested
    @FusionSupport(modules = PerProviderFalseOptsOutOfGlobalTrueModule.class)
    class PerProviderFalseOptsOutOfGlobalTrue {
        @Test
        void perProviderFalseOptsOutOfGlobalTrue(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var manager = providerManager(cfg);
            // the enum makes the explicit opt-out expressible: this provider goes back to the LLM path
            assertFalse(manager.resolvedIgnoreLlm(new ChatSettings("other", "m2", null, null)));
        }
    }

    public static class PerProviderFalseOptsOutOfGlobalTrueModule extends ConfigurationTestModule {
        public PerProviderFalseOptsOutOfGlobalTrueModule() {
            super(configSettings(
                    128_000,
                    Map.of(
                            "yca.compaction.mode", "no_llm",
                            "yca.provider.providers.1.compaction.mode", "llm")));
        }
    }

    @Nested
    @FusionSupport(modules = DisabledCompactionStillWinsOverIgnoreLlmModule.class)
    class DisabledCompactionStillWinsOverIgnoreLlm {
        @Test
        void disabledCompactionStillWinsOverIgnoreLlm(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var manager = providerManager(cfg);
            // resolvedIgnoreLlm still reports true, but compactNeeded is gated by enabled
            assertTrue(manager.resolvedIgnoreLlm(new ChatSettings("default", "m", null, null)));
            final var coordinator = coordinator(cfg, explodingChat(new AtomicInteger()), new AtomicInteger());
            final var contextManager = new ContextManager(
                    cfg, new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()), coordinator);
            final var messages = oldHeavyHistory();
            assertFalse(contextManager.compactNeeded(messages), "enabled=false wins: no compaction at all");
        }
    }

    public static class DisabledCompactionStillWinsOverIgnoreLlmModule extends ConfigurationTestModule {
        public DisabledCompactionStillWinsOverIgnoreLlmModule() {
            super(configSettings(
                    128_000,
                    Map.of(
                            "yca.compaction.mode", "no_llm",
                            "yca.compaction.enabled", "false")));
        }
    }
}
