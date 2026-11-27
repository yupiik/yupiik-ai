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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies the compression guidance surfaces exact closed-turn pairs and a non-blocking no-candidate path. */
@ExtendWith(TestJsonMapper.class)
@FusionSupport(modules = ContextGuidanceTest.TestModule.class)
class ContextGuidanceTest {

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private ContextGuidance guidance(final CodingAgentConfiguration cfg, final ContextView view) {
        final var providerManager = new ProviderManager(cfg, mapper);
        final var usage = new UsageTracker(providerManager, null, null, Clock.systemUTC());
        return new ContextGuidance(cfg, () -> usage, () -> view, () -> providerManager, () -> ChatSettings.EMPTY);
    }

    @Test
    void hardGuidanceListsExactClosedTurnPairsWhenAvailable(@Fusion final CodingAgentConfiguration cfg) {
        final var view = new ContextView(cfg.compression());
        // one closed turn (user+assistant) followed by an open one
        final var messages = List.of(
                Message.system("system"),
                Message.user("first task"),
                Message.assistant("done", List.of()),
                Message.user("next task"));
        view.publishMessages(messages);
        final var guidance = guidance(cfg, view);
        final var out = guidance.compressionRefGuidance(messages, 2);
        assertTrue(out.contains("compressible inclusive ranges"), out);
        assertTrue(out.contains("1-2"), "the exact closed pair must be advertised, not bare starts");
        assertTrue(out.contains("call `compress`"), out);
        assertFalse(out.contains("user-turn starts"), out);
        assertFalse(out.contains("/compact"), "a whole-session compact must not be suggested just to compress");
    }

    @Test
    void noCandidateGuidanceIsNonBlockingAndDoesNotRecommendWholeCompact(@Fusion final CodingAgentConfiguration cfg) {
        final var view = new ContextView(cfg.compression());
        // only a single open turn: nothing is a fully-closed candidate
        final var messages = List.of(Message.system("system"), Message.user("only current task"));
        view.publishMessages(messages);
        final var guidance = guidance(cfg, view);
        final var out = guidance.compressionRefGuidance(messages, 2);
        assertTrue(out.contains("none"), out);
        assertEquals(out, guidance.compressionRefGuidance(messages, 1));
        assertTrue(out.contains("Continue the current task"), out);
        assertFalse(out.contains("/compact"), "the empty set must not recommend a whole-session compact");
        assertFalse(out.contains("start a new session"), out);
    }

    @Test
    void softTierListsPairsAndInactiveTierIsSilent(@Fusion final CodingAgentConfiguration cfg) {
        final var view = new ContextView(cfg.compression());
        final var messages = List.of(
                Message.system("system"),
                Message.user("first"),
                Message.assistant("done", List.of()),
                Message.user("last"));
        view.publishMessages(messages);
        final var guidance = guidance(cfg, view);
        assertEquals("", guidance.compressionRefGuidance(messages, 0));
        assertTrue(guidance.compressionRefGuidance(messages, 1).contains("1-2"));
    }

    public static class TestModule extends ConfigurationTestModule {
        public TestModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var entries = new HashMap<String, String>();
            entries.put("yca.storageHome", "x");
            entries.put("yca.workingDir", ".");
            entries.put("yca.provider.providers.length", "1");
            entries.put("yca.provider.providers.0.id", "default");
            entries.put("yca.provider.providers.0.baseUrl", "https://api.openai.com/v1/chat/completions");
            entries.put("yca.provider.providers.0.apiKey", "");
            entries.put("yca.provider.providers.0.model", "m");
            entries.put("yca.provider.providers.0.api", "auto");
            entries.put("yca.provider.providers.0.contextWindow", "100000");
            entries.put("yca.provider.providers.0.timeoutSeconds", "120");
            return entries;
        }
    }
}
