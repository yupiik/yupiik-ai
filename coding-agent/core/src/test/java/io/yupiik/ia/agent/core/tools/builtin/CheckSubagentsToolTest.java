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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class CheckSubagentsToolTest {
    private CodingAgentConfiguration configuration;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
        storageHome = Path.of(configuration.storageHome());
    }

    @TempDir
    private static Path testWorkdir;

    private Path storageHome;

    private static Map<String, String> configurationValues(
            final Path storageHome, final Map<String, String> overrides) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.subagents.enabled", "true");
        entries.put("yca.subagents.maxConcurrent", "2");
        entries.put("yca.llm.maxConcurrent", "4");
        entries.putAll(overrides);
        return entries;
    }

    private ProviderManager providerManager(final int maxConcurrent) {
        return new ProviderManager() {
            @Override
            public ProviderConfiguration current() {
                return new ProviderConfiguration(
                        "default",
                        "http://test",
                        "key",
                        "model",
                        ApiFlavor.AUTO,
                        -1,
                        -1,
                        "",
                        null,
                        List.of(),
                        maxConcurrent,
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
        };
    }

    private SubagentRegistry registry(final CodingAgentConfiguration config) {
        return new SubagentRegistry(config, providerManager(4));
    }

    @Nested
    @FusionSupport(modules = PollReturnsEmptyListWhenNoSubagentsScenarioModule.class)
    class PollReturnsEmptyListWhenNoSubagentsScenario {
        @TempDir
        static Path directory;

        @Test
        void pollReturnsEmptyListWhenNoSubagents(final JsonMapper mapper) {
            final var reg = registry(configuration);
            final var tool = new CheckSubagentsTool(reg, mapper);
            final var result = tool.execute(
                    Map.of("action", "poll"), ToolContext.empty(storageHome, new DisabledSandbox(storageHome)));
            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("\"subagents\":[]"));
        }
    }

    @Nested
    @FusionSupport(modules = PollReturnsActiveSubagentsWithStatusScenarioModule.class)
    class PollReturnsActiveSubagentsWithStatusScenario {
        @TempDir
        static Path directory;

        @Test
        void pollReturnsActiveSubagentsWithStatus(final JsonMapper mapper) {
            final var reg = registry(configuration);
            reg.start("parent", "task1");
            reg.start("parent", "task2");
            final var tool = new CheckSubagentsTool(reg, mapper);
            final var result = tool.execute(
                    Map.of("action", "poll"), ToolContext.empty(storageHome, new DisabledSandbox(storageHome)));
            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("running"));
            assertTrue(result.output().contains("task1"));
            assertTrue(result.output().contains("task2"));
        }
    }

    @Nested
    @FusionSupport(modules = CollectReturnsSummaryWhenCompletedScenarioModule.class)
    class CollectReturnsSummaryWhenCompletedScenario {
        @TempDir
        static Path directory;

        @Test
        void collectReturnsSummaryWhenCompleted(final JsonMapper mapper) {
            final var reg = registry(configuration);
            final var state = reg.start("parent", "task").orElseThrow();
            reg.complete(state.id(), SubagentRegistry.SubagentResult.success("done", "full", List.of("artifact")));
            final var tool = new CheckSubagentsTool(reg, mapper);
            final var result = tool.execute(
                    Map.of("action", "collect", "subagent_id", state.id()),
                    ToolContext.empty(storageHome, new DisabledSandbox(storageHome)));
            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("\"completed\":true"));
            assertTrue(result.output().contains("done"));
        }
    }

    @Nested
    @FusionSupport(modules = CollectReturnsPendingWhenStillRunningScenarioModule.class)
    class CollectReturnsPendingWhenStillRunningScenario {
        @TempDir
        static Path directory;

        @Test
        void collectReturnsPendingWhenStillRunning(final JsonMapper mapper) {
            final var reg = registry(configuration);
            final var state = reg.start("parent", "task").orElseThrow();
            final var tool = new CheckSubagentsTool(reg, mapper);
            // a still-running sub-agent must NOT surface as a hard error: with a short max_wait_ms the
            // collect returns a neutral "pending" result the model can act on (proceed or retry later).
            final var result = tool.execute(
                    Map.of("action", "collect", "subagent_id", state.id(), "max_wait_ms", "100"),
                    ToolContext.empty(storageHome, new DisabledSandbox(storageHome)));
            assertFalse(result.success(), result.output());
            assertTrue(result.pending(), "a still-running collect must be pending, not an error: " + result.output());
            assertTrue(result.output().contains("\"status\":\"pending\""));
        }
    }

    @Nested
    @FusionSupport(modules = CollectInterruptsOnCancelledProbeScenarioModule.class)
    class CollectInterruptsOnCancelledProbeScenario {
        @TempDir
        static Path directory;

        @Test
        void collectInterruptsOnCancelledProbe(final JsonMapper mapper) {
            final var reg = registry(configuration);
            final var state = reg.start("parent", "task").orElseThrow();
            final var tool = new CheckSubagentsTool(reg, mapper);
            // the turn's cancellation probe (not per-call) aborts the blocking collect early and returns
            // a clean "cancelled" result rather than failing or waiting the whole max_wait_ms
            final var cancelled = new AtomicBoolean(false);
            final var context = ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir));
            final var ctx = new ToolContext(
                    context.sessionId(),
                    context.subagentId(),
                    context.subagentName(),
                    context.permissions(),
                    context.feedback(),
                    context.todos(),
                    context.statusReporter(),
                    context.usage(),
                    context.contextView(),
                    cancelled::get,
                    context.settings(),
                    context.callCancelled(),
                    context.projectDir(),
                    new DisabledSandbox(context.projectDir()),
                    context.ordinal(),
                    context.allowedTools(),
                    null,
                    context.toolRegistry());
            cancelled.set(true);
            final var result =
                    tool.execute(Map.of("action", "collect", "subagent_id", state.id(), "max_wait_ms", "5000"), ctx);
            // the turn cancelled: the collect reports it cleanly (ok, status=cancelled), not as a failure
            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("\"status\":\"cancelled\""));
        }
    }

    @Nested
    @FusionSupport(modules = CollectFailsWhenNotFoundScenarioModule.class)
    class CollectFailsWhenNotFoundScenario {
        @TempDir
        static Path directory;

        @Test
        void collectFailsWhenNotFound(final JsonMapper mapper) {
            final var reg = registry(configuration);
            final var tool = new CheckSubagentsTool(reg, mapper);
            final var result = tool.execute(
                    Map.of("action", "collect", "subagent_id", "unknown"),
                    ToolContext.empty(storageHome, new DisabledSandbox(storageHome)));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains("not found"));
        }
    }

    @Nested
    @FusionSupport(modules = CancelTerminatesRunningSubagentScenarioModule.class)
    class CancelTerminatesRunningSubagentScenario {
        @TempDir
        static Path directory;

        @Test
        void cancelTerminatesRunningSubagent(final JsonMapper mapper) {
            final var reg = registry(configuration);
            final var state = reg.start("parent", "task").orElseThrow();
            final var tool = new CheckSubagentsTool(reg, mapper);
            final var result = tool.execute(
                    Map.of("action", "cancel", "subagent_id", state.id()),
                    ToolContext.empty(storageHome, new DisabledSandbox(storageHome)));
            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("\"cancelled\":true"));
            assertTrue(reg.get(state.id()).isEmpty());
        }
    }

    @Nested
    @FusionSupport(modules = CancelNoopWhenAlreadyCompletedScenarioModule.class)
    class CancelNoopWhenAlreadyCompletedScenario {
        @TempDir
        static Path directory;

        @Test
        void cancelNoopWhenAlreadyCompleted(final JsonMapper mapper) {
            final var reg = registry(configuration);
            final var state = reg.start("parent", "task").orElseThrow();
            reg.complete(state.id(), SubagentRegistry.SubagentResult.success("done", "full", List.of()));
            final var tool = new CheckSubagentsTool(reg, mapper);
            final var result = tool.execute(
                    Map.of("action", "cancel", "subagent_id", state.id()),
                    ToolContext.empty(storageHome, new DisabledSandbox(storageHome)));
            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("\"cancelled\":false"));
            assertTrue(result.output().contains("already completed"));
        }
    }

    @Nested
    @FusionSupport(modules = ExecuteFailsWhenDisabledScenarioModule.class)
    class ExecuteFailsWhenDisabledScenario {
        @TempDir
        static Path directory;

        @Test
        void executeFailsWhenDisabled(final JsonMapper mapper) {
            final var reg = registry(configuration);
            final var tool = new CheckSubagentsTool(reg, mapper);
            final var result = tool.execute(
                    Map.of("action", "poll"), ToolContext.empty(storageHome, new DisabledSandbox(storageHome)));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains("disabled"));
        }
    }

    @Nested
    @FusionSupport(modules = ExecuteFailsWhenActionMissingScenarioModule.class)
    class ExecuteFailsWhenActionMissingScenario {
        @TempDir
        static Path directory;

        @Test
        void executeFailsWhenActionMissing(final JsonMapper mapper) {
            final var reg = registry(configuration);
            final var tool = new CheckSubagentsTool(reg, mapper);
            final var result = tool.execute(Map.of(), ToolContext.empty(storageHome, new DisabledSandbox(storageHome)));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains("action is required"));
        }
    }

    @Nested
    @FusionSupport(modules = ExecuteFailsWhenInvalidActionScenarioModule.class)
    class ExecuteFailsWhenInvalidActionScenario {
        @TempDir
        static Path directory;

        @Test
        void executeFailsWhenInvalidAction(final JsonMapper mapper) {
            final var reg = registry(configuration);
            final var tool = new CheckSubagentsTool(reg, mapper);
            final var result = tool.execute(
                    Map.of("action", "invalid"), ToolContext.empty(storageHome, new DisabledSandbox(storageHome)));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains("Invalid action"));
        }
    }

    public static class PollReturnsEmptyListWhenNoSubagentsScenarioModule extends ConfigurationTestModule {
        public PollReturnsEmptyListWhenNoSubagentsScenarioModule() {
            super(configurationValues(PollReturnsEmptyListWhenNoSubagentsScenario.directory, Map.of()));
        }
    }

    public static class PollReturnsActiveSubagentsWithStatusScenarioModule extends ConfigurationTestModule {
        public PollReturnsActiveSubagentsWithStatusScenarioModule() {
            super(configurationValues(PollReturnsActiveSubagentsWithStatusScenario.directory, Map.of()));
        }
    }

    public static class CollectReturnsSummaryWhenCompletedScenarioModule extends ConfigurationTestModule {
        public CollectReturnsSummaryWhenCompletedScenarioModule() {
            super(configurationValues(CollectReturnsSummaryWhenCompletedScenario.directory, Map.of()));
        }
    }

    public static class CollectReturnsPendingWhenStillRunningScenarioModule extends ConfigurationTestModule {
        public CollectReturnsPendingWhenStillRunningScenarioModule() {
            super(configurationValues(CollectReturnsPendingWhenStillRunningScenario.directory, Map.of()));
        }
    }

    public static class CollectInterruptsOnCancelledProbeScenarioModule extends ConfigurationTestModule {
        public CollectInterruptsOnCancelledProbeScenarioModule() {
            super(configurationValues(CollectInterruptsOnCancelledProbeScenario.directory, Map.of()));
        }
    }

    public static class CollectFailsWhenNotFoundScenarioModule extends ConfigurationTestModule {
        public CollectFailsWhenNotFoundScenarioModule() {
            super(configurationValues(CollectFailsWhenNotFoundScenario.directory, Map.of()));
        }
    }

    public static class CancelTerminatesRunningSubagentScenarioModule extends ConfigurationTestModule {
        public CancelTerminatesRunningSubagentScenarioModule() {
            super(configurationValues(CancelTerminatesRunningSubagentScenario.directory, Map.of()));
        }
    }

    public static class CancelNoopWhenAlreadyCompletedScenarioModule extends ConfigurationTestModule {
        public CancelNoopWhenAlreadyCompletedScenarioModule() {
            super(configurationValues(CancelNoopWhenAlreadyCompletedScenario.directory, Map.of()));
        }
    }

    public static class ExecuteFailsWhenDisabledScenarioModule extends ConfigurationTestModule {
        public ExecuteFailsWhenDisabledScenarioModule() {
            super(configurationValues(
                    ExecuteFailsWhenDisabledScenario.directory, Map.of("yca.subagents.enabled", "false")));
        }
    }

    public static class ExecuteFailsWhenActionMissingScenarioModule extends ConfigurationTestModule {
        public ExecuteFailsWhenActionMissingScenarioModule() {
            super(configurationValues(ExecuteFailsWhenActionMissingScenario.directory, Map.of()));
        }
    }

    public static class ExecuteFailsWhenInvalidActionScenarioModule extends ConfigurationTestModule {
        public ExecuteFailsWhenInvalidActionScenarioModule() {
            super(configurationValues(ExecuteFailsWhenInvalidActionScenario.directory, Map.of()));
        }
    }
}
