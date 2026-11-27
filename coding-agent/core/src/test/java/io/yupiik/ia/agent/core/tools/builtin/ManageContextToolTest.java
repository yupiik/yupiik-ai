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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.ContextCompression;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class ManageContextToolTest {
    private CodingAgentConfiguration configuration;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    @TempDir
    private static Path testWorkdir;

    private JsonMapper mapper;

    @Nested
    @FusionSupport(modules = StatusBoundsCandidatesAndRefreshesAfterContextReplacementScenarioModule.class)
    class StatusBoundsCandidatesAndRefreshesAfterContextReplacementScenario {
        @TempDir
        static Path directory;

        @Test
        void statusBoundsCandidatesAndRefreshesAfterContextReplacement() {
            final var cfg = configuration;
            final var view = new ContextView(cfg.compression());
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 30; i++) {
                messages.add(Message.user("sample " + i));
                messages.add(Message.assistant("done", List.of()));
            }
            view.publishMessages(messages);
            final var tool = new ManageContextTool(cfg, mapper, providerManager());
            final var result = tool.execute(Map.of("action", "status"), context(view));
            final var cap = ContextCompression.MAX_GUIDANCE_REFS;
            assertTrue(cap < 29, "the fixture must exceed the bound so the cap is actually hit");
            assertTrue(result.output().contains("\"compressibleRangeCount\":" + cap), result.output());
            assertTrue(result.output().contains("\"compressibleRangeTotal\":29"), result.output());
            assertTrue(result.output().contains("\"omittedCompressibleRangeCount\":" + (29 - cap)), result.output());
            view.publishMessages(List.of(
                    Message.system("system"),
                    Message.user("<compacted-context>digest</compacted-context>"),
                    Message.user("current task")));
            final var refreshed = tool.execute(Map.of("action", "status"), context(view));
            assertTrue(refreshed.output().contains("\"compressibleRanges\":[]"), refreshed.output());
            assertTrue(refreshed.output().contains("\"compressibleRangeTotal\":0"), refreshed.output());
        }
    }

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private ToolContext context(final ContextView view) {
        return new ToolContext(
                "s1",
                null,
                null,
                null,
                null,
                null,
                null,
                tracker(),
                view,
                Cancellations.NEVER,
                null,
                null,
                testWorkdir,
                new DisabledSandbox(testWorkdir));
    }

    private ProviderManager providerManager() {
        return new ProviderManager(configuration, mapper);
    }

    @Nested
    @FusionSupport(modules = StatusRemeasuresPublishedMessagesInsteadOfReusingAStaleTokenCountScenarioModule.class)
    class StatusRemeasuresPublishedMessagesInsteadOfReusingAStaleTokenCountScenario {
        @TempDir
        static Path directory;

        @Test
        void statusRemeasuresPublishedMessagesInsteadOfReusingAStaleTokenCount() {
            final var view = new ContextView();
            view.publish(new ContextView.Snapshot(12, 5_000, 100_000, 80_000, 62.5, 65, 80));
            final var tool = new ManageContextTool(configuration, mapper, providerManager());
            final var result = tool.execute(Map.of("action", "status"), context(view));
            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("\"messageCount\":12"), result.output());
            assertTrue(result.output().contains("\"rawTokens\":0"), result.output());
            assertTrue(result.output().contains("\"effectiveTokens\":0"), result.output());
            assertTrue(result.output().contains("\"measurementSource\":\"heuristic\""), result.output());
        }
    }

    @Nested
    @FusionSupport(modules = StatusWithoutSnapshotReportsWindowOnlyScenarioModule.class)
    class StatusWithoutSnapshotReportsWindowOnlyScenario {
        @TempDir
        static Path directory;

        @Test
        void statusWithoutSnapshotReportsWindowOnly() {
            final var view = new ContextView();
            final var tool = new ManageContextTool(configuration, mapper, providerManager());
            final var result = tool.execute(Map.of("action", "status"), context(view));
            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("contextWindow"), result.output());
            assertTrue(result.output().contains("\"messageCount\":0"), result.output());
        }
    }

    @Nested
    @FusionSupport(modules = StatusSurfacesClosedTurnRangesScenarioModule.class)
    class StatusSurfacesClosedTurnRangesScenario {
        @TempDir
        static Path directory;

        @Test
        void statusSurfacesClosedTurnRanges() {
            final var cfg = configuration;
            final var view = new ContextView(cfg.compression());
            final var messages = List.of(
                    Message.system("system"),
                    Message.user("first task"),
                    Message.assistant("done", List.of()),
                    Message.user("next task"));
            view.publishMessages(messages);
            view.compression().renderRefs(messages); // assigns stable refs like the agent loop does
            final var tool = new ManageContextTool(cfg, mapper, providerManager());
            final var result = tool.execute(Map.of("action", "status"), context(view));
            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("\"compressibleRanges\""), result.output());
            assertTrue(result.output().contains("\"startId\":\"1\""), result.output());
            assertTrue(result.output().contains("\"endId\":\"2\""), result.output());
            assertTrue(result.output().contains("\"compressibleRangeCount\":1"), result.output());
        }
    }

    @Nested
    @FusionSupport(modules = CompactRequestIsAllowedBelowTheFloorScenarioModule.class)
    class CompactRequestIsAllowedBelowTheFloorScenario {
        @TempDir
        static Path directory;

        @Test
        void compactRequestIsAllowedBelowTheFloor() {
            final var view = new ContextView();
            view.publish(new ContextView.Snapshot(4, 500, 100_000, 80_000, 10.0, 65, 80));
            final var tool = new ManageContextTool(configuration, mapper, providerManager());
            final var result = tool.execute(Map.of("action", "compact"), context(view));
            assertTrue(result.success(), result.output());
            assertTrue(result.output().startsWith("Compaction scheduled"), result.output());
            assertTrue(view.poll().force(), "a manual compact bypasses the usage guard");
        }
    }

    @Nested
    @FusionSupport(modules = CompactRequestQueuesADirectiveScenarioModule.class)
    class CompactRequestQueuesADirectiveScenario {
        @TempDir
        static Path directory;

        @Test
        void compactRequestQueuesADirective() {
            final var view = new ContextView();
            view.publish(new ContextView.Snapshot(40, 70_000, 100_000, 80_000, 90.0, 65, 80));
            final var tool = new ManageContextTool(configuration, mapper, providerManager());
            final var args = new LinkedHashMap<String, Object>();
            args.put("action", "compact");
            args.put("focus", "keep the migration plan");
            args.put("keep_last", 4);
            final var result = tool.execute(args, context(view));
            assertTrue(result.success(), result.output());
            assertEquals(
                    new ContextView.Directive("compact", "keep the migration plan", 4, "compress", true), view.poll());
        }
    }

    @Nested
    @FusionSupport(modules = CompressSummarizesAClosedRangeScenarioModule.class)
    class CompressSummarizesAClosedRangeScenario {
        @TempDir
        static Path directory;

        @Test
        void compressSummarizesAClosedRange() {
            final var cfg = configuration;
            final var view = new ContextView(cfg.compression());
            final var messages = List.of(
                    Message.system("system"),
                    Message.user("first task"),
                    Message.assistant("done", List.of()),
                    Message.user("next task"));
            view.publishMessages(messages);
            view.compression().renderRefs(messages); // assigns stable refs like the agent loop does
            final var tool = new ManageContextTool(cfg, mapper, providerManager());
            final var result = tool.execute(
                    Map.of(
                            "action",
                            "compress",
                            "topic",
                            "first task",
                            "content",
                            List.of(Map.of("startId", "1", "endId", "2", "summary", "first task was completed"))),
                    context(view));
            assertTrue(result.success(), result.output());
            assertEquals(1, view.compression().blocks().size());
            assertEquals(4, messages.size(), "the transcript itself must never be mutated");
            assertEquals(3, view.compression().rewrite(messages).size(), "the block replaces the two-message span");
        }
    }

    @Nested
    @FusionSupport(modules = CompressToleratesAJsonEncodedStringContentScenarioModule.class)
    class CompressToleratesAJsonEncodedStringContentScenario {
        @TempDir
        static Path directory;

        @Test
        void compressToleratesAJsonEncodedStringContent() {
            final var cfg = configuration;
            final var view = new ContextView(cfg.compression());
            final var messages = List.of(
                    Message.system("system"),
                    Message.user("first task"),
                    Message.assistant("done", List.of()),
                    Message.user("next task"));
            view.publishMessages(messages);
            view.compression().renderRefs(messages); // assigns stable refs like the agent loop does
            final var tool = new ManageContextTool(cfg, mapper, providerManager());
            final var json = mapper.toString(
                    List.of(Map.of("startId", "1", "endId", "2", "summary", "first task was completed")));
            final var result =
                    tool.execute(Map.of("action", "compress", "topic", "first task", "content", json), context(view));
            assertTrue(result.success(), result.output());
            assertEquals(1, view.compression().blocks().size());
            assertEquals(4, messages.size(), "the transcript itself must never be mutated");
            assertEquals(3, view.compression().rewrite(messages).size(), "the block replaces the two-message span");
        }
    }

    @Nested
    @FusionSupport(modules = SecondRequestIsRejectedWhileOneIsPendingScenarioModule.class)
    class SecondRequestIsRejectedWhileOneIsPendingScenario {
        @TempDir
        static Path directory;

        @Test
        void secondRequestIsRejectedWhileOneIsPending() {
            final var view = new ContextView();
            view.publish(new ContextView.Snapshot(40, 70_000, 100_000, 80_000, 90.0, 65, 80));
            final var tool = new ManageContextTool(configuration, mapper, providerManager());
            assertTrue(view.request(new ContextView.Directive("prune", null, 0, "test")));
            final var result = tool.execute(Map.of("action", "compact"), context(view));
            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("already pending"), result.output());
            assertEquals("prune", view.poll().action(), "the first queued directive must survive");
        }
    }

    @Nested
    @FusionSupport(modules = DisabledToolErrorsScenarioModule.class)
    class DisabledToolErrorsScenario {
        @TempDir
        static Path directory;

        @Test
        void disabledToolErrors() {
            final var view = new ContextView();
            final var tool = new ManageContextTool(configuration, mapper, providerManager());
            final var result = tool.execute(Map.of("action", "status"), context(view));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains("disabled"), result.output());
        }
    }

    private static Map<String, String> configurationValues(final boolean enabled) {
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
        if (!enabled) {
            entries.put("yca.tools.manageContext.enabled", "false");
        }
        return entries;
    }

    private UsageTracker tracker() {
        return new UsageTracker(new ProviderManager(configuration, mapper), null, null, Clock.systemUTC());
    }

    public static class StatusBoundsCandidatesAndRefreshesAfterContextReplacementScenarioModule
            extends ConfigurationTestModule {
        public StatusBoundsCandidatesAndRefreshesAfterContextReplacementScenarioModule() {
            super(configurationValues(true));
        }
    }

    public static class StatusRemeasuresPublishedMessagesInsteadOfReusingAStaleTokenCountScenarioModule
            extends ConfigurationTestModule {
        public StatusRemeasuresPublishedMessagesInsteadOfReusingAStaleTokenCountScenarioModule() {
            super(configurationValues(true));
        }
    }

    public static class StatusWithoutSnapshotReportsWindowOnlyScenarioModule extends ConfigurationTestModule {
        public StatusWithoutSnapshotReportsWindowOnlyScenarioModule() {
            super(configurationValues(true));
        }
    }

    public static class StatusSurfacesClosedTurnRangesScenarioModule extends ConfigurationTestModule {
        public StatusSurfacesClosedTurnRangesScenarioModule() {
            super(configurationValues(true));
        }
    }

    public static class CompactRequestIsAllowedBelowTheFloorScenarioModule extends ConfigurationTestModule {
        public CompactRequestIsAllowedBelowTheFloorScenarioModule() {
            super(configurationValues(true));
        }
    }

    public static class CompactRequestQueuesADirectiveScenarioModule extends ConfigurationTestModule {
        public CompactRequestQueuesADirectiveScenarioModule() {
            super(configurationValues(true));
        }
    }

    public static class CompressSummarizesAClosedRangeScenarioModule extends ConfigurationTestModule {
        public CompressSummarizesAClosedRangeScenarioModule() {
            super(configurationValues(true));
        }
    }

    public static class CompressToleratesAJsonEncodedStringContentScenarioModule extends ConfigurationTestModule {
        public CompressToleratesAJsonEncodedStringContentScenarioModule() {
            super(configurationValues(true));
        }
    }

    public static class SecondRequestIsRejectedWhileOneIsPendingScenarioModule extends ConfigurationTestModule {
        public SecondRequestIsRejectedWhileOneIsPendingScenarioModule() {
            super(configurationValues(true));
        }
    }

    public static class DisabledToolErrorsScenarioModule extends ConfigurationTestModule {
        public DisabledToolErrorsScenarioModule() {
            super(configurationValues(false));
        }
    }
}
