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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentRunSpec;
import io.yupiik.ia.agent.core.agent.SubagentBudget;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.agent.SubagentRunner;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpawnSubagentToolTest {
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
                return provider("default");
            }

            @Override
            public List<ProviderConfiguration> providers() {
                return List.of(provider("default"));
            }

            @Override
            public ProviderConfiguration provider(final String id) {
                return new ProviderConfiguration(
                        id == null ? "default" : id,
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

    private SubagentRunner mockRunner(final SubagentRegistry registry) {
        return new SubagentRunner(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                registry,
                new PrecisionDurationMarkerProvider(),
                new BackoffSleeper(),
                null, // secret redactor
                null) { // quota store
            @Override
            public void runAsync(
                    final SubagentRegistry.SubagentState state,
                    final SessionStore sessionStore,
                    final SnapshotStore snapshotStore,
                    final TodoStore todoStore,
                    final ToolContext parentContext,
                    final BooleanSupplier cancelled) {
                registry.complete(
                        state.id(),
                        SubagentRegistry.SubagentResult.success("mock result", "conv", List.of("artifact")));
            }
        };
    }

    private SessionStore noopStore() {
        return new SessionStore() {
            @Override
            public void append(final String id, final SessionEntry entry) {}
        };
    }

    private SnapshotStore noopSnapshotStore() {
        return new SnapshotStore() {
            @Override
            public synchronized Checkpoint save(final String sessionId, final String label) {
                return new Checkpoint(label, 0);
            }

            @Override
            public synchronized Retained restore(final String sessionId, final String summary) {
                return new Retained(summary);
            }

            @Override
            public synchronized void cancel(final String sessionId) {}

            @Override
            public String checkpointLabel(final String sessionId) {
                return null;
            }

            @Override
            public List<Retained> retained(final String sessionId) {
                return List.of();
            }
        };
    }

    private ToolContext context(final TodoStore todos) {
        return new ToolContext(
                "s1",
                null,
                null,
                null,
                null,
                todos,
                null,
                null,
                null,
                Cancellations.NEVER,
                ChatSettings.EMPTY.withProviderId("default").withModel("model"),
                null,
                testWorkdir,
                new DisabledSandbox(testWorkdir));
    }

    /** A context with the default per-session sub-agent budget of the given config. */
    private ToolContext context(final TodoStore todos, final SubagentBudget budget) {
        return new ToolContext(
                "s1",
                null,
                null,
                null,
                null,
                todos,
                null,
                null,
                null,
                Cancellations.NEVER,
                ChatSettings.EMPTY.withProviderId("default").withModel("model"),
                null,
                testWorkdir,
                new DisabledSandbox(testWorkdir),
                budget);
    }

    private TodoStore noopTodoStore() {
        return new TodoStore() {
            @Override
            public List<TodoItem> all() {
                return List.of();
            }

            @Override
            public synchronized List<TodoItem> replace(final List<TodoItem> items) {
                return items;
            }
        };
    }

    @Nested
    @FusionSupport(modules = ExecuteReturnsSubagentIdWhenStartedScenarioModule.class)
    class ExecuteReturnsSubagentIdWhenStartedScenario {
        @TempDir
        static Path directory;

        @Test
        void executeReturnsSubagentIdWhenStarted() {
            final var cfg = configuration;
            final var reg = registry(cfg);
            final var tool = new SpawnSubagentTool(
                    reg, mockRunner(reg), noopStore(), noopSnapshotStore(), cfg, providerManager(4));
            final var result = tool.execute(Map.of("task", "do work"), context(noopTodoStore()));
            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("Sub-agent started"));
            assertTrue(result.output().contains("do work"));
        }
    }

    @Nested
    @FusionSupport(modules = ExecuteFailsWhenRegistryDisabledScenarioModule.class)
    class ExecuteFailsWhenRegistryDisabledScenario {
        @TempDir
        static Path directory;

        @Test
        void executeFailsWhenRegistryDisabled() {
            final var cfg = configuration;
            final var reg = registry(cfg);
            final var tool = new SpawnSubagentTool(
                    reg, mockRunner(reg), noopStore(), noopSnapshotStore(), cfg, providerManager(4));
            final var result = tool.execute(Map.of("task", "do work"), context(noopTodoStore()));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains("disabled"));
        }
    }

    @Nested
    @FusionSupport(modules = ExecuteFailsWhenTaskMissingScenarioModule.class)
    class ExecuteFailsWhenTaskMissingScenario {
        @TempDir
        static Path directory;

        @Test
        void executeFailsWhenTaskMissing() {
            final var cfg = configuration;
            final var reg = registry(cfg);
            final var tool = new SpawnSubagentTool(
                    reg, mockRunner(reg), noopStore(), noopSnapshotStore(), cfg, providerManager(4));
            final var result = tool.execute(Map.of(), context(noopTodoStore()));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains("task is required"));
        }
    }

    @Nested
    @FusionSupport(modules = DescriptionCoachesAgainstPollingAndPromisesNotificationScenarioModule.class)
    class DescriptionCoachesAgainstPollingAndPromisesNotificationScenario {
        @TempDir
        static Path directory;

        @Test
        void descriptionCoachesAgainstPollingAndPromisesNotification() {
            final var cfg = configuration;
            final var description =
                    new SpawnSubagentTool(registry(cfg), null, null, null, cfg).description(testWorkdir);
            assertTrue(description.contains("you will be notified automatically"), description);
            assertTrue(description.contains("NOT sleep"), description);
            assertTrue(description.contains("poll"), description);
            assertTrue(description.contains("collect"), description);
        }
    }

    @Nested
    @FusionSupport(modules = ForwardsTheParentTurnCancellationProbeScenarioModule.class)
    class ForwardsTheParentTurnCancellationProbeScenario {
        @TempDir
        static Path directory;

        @Test
        void forwardsTheParentTurnCancellationProbe() {
            // an interrupt on the parent turn must reach the sub-agent: it would otherwise keep
            // burning tokens on an abandoned task (the registry latch stays available for a
            // model-driven cancel_subagent)
            final var cfg = configuration;
            final var reg = registry(cfg);
            final var forwarded = new AtomicReference<BooleanSupplier>();
            final var runner =
                    new SubagentRunner(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            reg,
                            new PrecisionDurationMarkerProvider(),
                            new BackoffSleeper(),
                            null, // secret redactor
                            null) { // quota store
                        @Override
                        public void runAsync(
                                final SubagentRegistry.SubagentState state,
                                final SessionStore sessionStore,
                                final SnapshotStore snapshotStore,
                                final TodoStore todoStore,
                                final ToolContext parentContext,
                                final BooleanSupplier cancelled) {
                            forwarded.set(cancelled);
                            reg.complete(
                                    state.id(),
                                    SubagentRegistry.SubagentResult.success("mock result", "conv", List.of()));
                        }
                    };
            final var parentCancelled = new AtomicBoolean(false);
            final var tool =
                    new SpawnSubagentTool(reg, runner, noopStore(), noopSnapshotStore(), cfg, providerManager(4));
            final var ctx = new ToolContext(
                    "s1",
                    null,
                    null,
                    null,
                    null,
                    noopTodoStore(),
                    null,
                    null,
                    null,
                    parentCancelled::get,
                    ChatSettings.EMPTY.withProviderId("default").withModel("model"),
                    null,
                    testWorkdir,
                    new DisabledSandbox(testWorkdir));

            final var result = tool.execute(Map.of("task", "do work"), ctx);
            assertTrue(result.success(), result.output());
            final var probe = forwarded.get();
            assertNotNull(probe, "the sub-agent must receive a probe");
            assertFalse(probe.getAsBoolean(), "a running turn is not cancelled");

            parentCancelled.set(true);
            assertTrue(probe.getAsBoolean(), "the parent's interrupt must be visible to the sub-agent");
        }
    }

    @Nested
    @FusionSupport(modules = ExecuteFailsWhenConcurrencyExhaustedScenarioModule.class)
    class ExecuteFailsWhenConcurrencyExhaustedScenario {
        @TempDir
        static Path directory;

        @Test
        void executeFailsWhenConcurrencyExhausted() {
            final var cfg = configuration;
            final var reg = registry(cfg);
            // Fill the session budget directly (the per-session cap is 1) with the same budget the
            // tool's context will carry, so the spawn is refused at the session cap.
            final var budget = new SubagentBudget(1);
            reg.start(new AgentRunSpec("parent", "t1", null, null, null, null, null, null), "default", budget)
                    .state();
            final var tool = new SpawnSubagentTool(
                    reg, mockRunner(reg), noopStore(), noopSnapshotStore(), cfg, providerManager(4));
            final var result = tool.execute(Map.of("task", "do work"), context(noopTodoStore(), budget));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains("yca.subagents.maxConcurrent"), result.output());
        }
    }

    @Nested
    @FusionSupport(modules = ForwardsExplicitModelToTheSubagentStateScenarioModule.class)
    class ForwardsExplicitModelToTheSubagentStateScenario {
        @TempDir
        static Path directory;

        @Test
        void forwardsExplicitModelToTheSubagentState() {
            final var cfg = configuration;
            final var reg = registry(cfg);
            final var captured = new AtomicReference<String>();
            final var runner =
                    new SubagentRunner(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            reg,
                            new PrecisionDurationMarkerProvider(),
                            new BackoffSleeper(),
                            null, // secret redactor
                            null) { // quota store
                        @Override
                        public void runAsync(
                                final SubagentRegistry.SubagentState state,
                                final SessionStore sessionStore,
                                final SnapshotStore snapshotStore,
                                final TodoStore todoStore,
                                final ToolContext parentContext,
                                final BooleanSupplier cancelled) {
                            captured.set(state.model());
                            reg.complete(
                                    state.id(),
                                    SubagentRegistry.SubagentResult.success("mock result", "conv", List.of()));
                        }
                    };
            final var tool =
                    new SpawnSubagentTool(reg, runner, noopStore(), noopSnapshotStore(), cfg, providerManager(4));
            final var result =
                    tool.execute(Map.of("task", "do work", "model", "$default/other-model"), context(noopTodoStore()));
            assertTrue(result.success(), result.output());
            assertEquals("$default/other-model", captured.get(), "the explicit model must reach the subagent state");
        }
    }

    @Nested
    @FusionSupport(modules = ToleratesBlankAgentProfileAndRejectsUnknownScenarioModule.class)
    class ToleratesBlankAgentProfileAndRejectsUnknownScenario {
        @TempDir
        static Path directory;

        @Test
        void toleratesBlankAgentProfileAndRejectsUnknown() {
            final var cfg = configuration;
            final var reg = registry(cfg);
            final var tool = new SpawnSubagentTool(
                    reg, mockRunner(reg), noopStore(), noopSnapshotStore(), cfg, providerManager(4));

            // null == blank: an empty/whitespace profile is treated as "no profile", so it succeeds.
            final var blankProfile = tool.execute(Map.of("task", "do work", "agent", "   "), context(noopTodoStore()));
            assertTrue(blankProfile.success(), blankProfile.output());

            final var unknownProfile =
                    tool.execute(Map.of("task", "do work", "agent", "nope"), context(noopTodoStore()));
            assertFalse(unknownProfile.success(), unknownProfile.output());
            assertTrue(unknownProfile.output().contains("Unknown agent profile: nope"));
        }
    }

    @Nested
    @FusionSupport(modules = ToleratesBlankExplicitModelScenarioModule.class)
    class ToleratesBlankExplicitModelScenario {
        @TempDir
        static Path directory;

        @Test
        void toleratesBlankExplicitModel() {
            final var cfg = configuration;
            final var reg = registry(cfg);
            final var tool = new SpawnSubagentTool(
                    reg, mockRunner(reg), noopStore(), noopSnapshotStore(), cfg, providerManager(4));
            // null == blank: an empty/whitespace model is treated as "no model", so it succeeds.
            final var result = tool.execute(Map.of("task", "do work", "model", "  "), context(noopTodoStore()));
            assertTrue(result.success(), result.output());
        }
    }

    @Nested
    @FusionSupport(modules = LeavesModelNullWhenNotRequestedScenarioModule.class)
    class LeavesModelNullWhenNotRequestedScenario {
        @TempDir
        static Path directory;

        @Test
        void leavesModelNullWhenNotRequested() {
            final var cfg = configuration;
            final var reg = registry(cfg);
            final var captured = new AtomicReference<String>();
            final var runner =
                    new SubagentRunner(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            reg,
                            new PrecisionDurationMarkerProvider(),
                            new BackoffSleeper(),
                            null, // secret redactor
                            null) { // quota store
                        @Override
                        public void runAsync(
                                final SubagentRegistry.SubagentState state,
                                final SessionStore sessionStore,
                                final SnapshotStore snapshotStore,
                                final TodoStore todoStore,
                                final ToolContext parentContext,
                                final BooleanSupplier cancelled) {
                            captured.set(state.model());
                            reg.complete(
                                    state.id(),
                                    SubagentRegistry.SubagentResult.success("mock result", "conv", List.of()));
                        }
                    };
            final var tool =
                    new SpawnSubagentTool(reg, runner, noopStore(), noopSnapshotStore(), cfg, providerManager(4));
            final var result = tool.execute(Map.of("task", "do work"), context(noopTodoStore()));
            assertTrue(result.success(), result.output());
            assertNull(captured.get(), "no explicit model means inherit (null on the state)");
        }
    }

    @Nested
    @FusionSupport(modules = IgnoresNonPositiveOrMissingMaxTurnsAsNoPerSpawnOverrideScenarioModule.class)
    class IgnoresNonPositiveOrMissingMaxTurnsAsNoPerSpawnOverrideScenario {
        @TempDir
        static Path directory;

        @Test
        void ignoresNonPositiveOrMissingMaxTurnsAsNoPerSpawnOverride() {
            final var cfg = configuration;
            final var reg = registry(cfg);
            final AtomicReference<Integer> captured = new AtomicReference<>();
            final var runner =
                    new SubagentRunner(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            reg,
                            new PrecisionDurationMarkerProvider(),
                            new BackoffSleeper(),
                            null, // secret redactor
                            null) { // quota store
                        @Override
                        public void runAsync(
                                final SubagentRegistry.SubagentState state,
                                final SessionStore sessionStore,
                                final SnapshotStore snapshotStore,
                                final TodoStore todoStore,
                                final ToolContext parentContext,
                                final BooleanSupplier cancelled) {
                            captured.set(state.maxTurns());
                            reg.complete(
                                    state.id(),
                                    SubagentRegistry.SubagentResult.success("mock result", "conv", List.of()));
                        }
                    };
            final var tool =
                    new SpawnSubagentTool(reg, runner, noopStore(), noopSnapshotStore(), cfg, providerManager(4));
            final var ctx = context(noopTodoStore());

            final var positive = tool.execute(Map.of("task", "do work", "max_turns", 7), ctx);
            assertTrue(positive.success(), positive.output());
            assertEquals(7, captured.get(), "a positive max_turns must set the per-spawn cap");

            // zero and negative values (and omission) disable the per-spawn override -> null cap
            for (final Object maxTurns : Arrays.asList(0, -3, null)) {
                final Map<String, Object> args = new HashMap<>(Map.of("task", "do work"));
                if (maxTurns != null) {
                    args.put("max_turns", maxTurns);
                }
                final var result = tool.execute(args, ctx);
                assertTrue(result.success(), result.output());
                assertNull(captured.get(), "max_turns=" + maxTurns + " must yield null (no per-spawn override)");
            }
        }
    }

    public static class ExecuteReturnsSubagentIdWhenStartedScenarioModule extends ConfigurationTestModule {
        public ExecuteReturnsSubagentIdWhenStartedScenarioModule() {
            super(configurationValues(ExecuteReturnsSubagentIdWhenStartedScenario.directory, Map.of()));
        }
    }

    public static class ExecuteFailsWhenRegistryDisabledScenarioModule extends ConfigurationTestModule {
        public ExecuteFailsWhenRegistryDisabledScenarioModule() {
            super(configurationValues(
                    ExecuteFailsWhenRegistryDisabledScenario.directory, Map.of("yca.subagents.enabled", "false")));
        }
    }

    public static class ExecuteFailsWhenTaskMissingScenarioModule extends ConfigurationTestModule {
        public ExecuteFailsWhenTaskMissingScenarioModule() {
            super(configurationValues(ExecuteFailsWhenTaskMissingScenario.directory, Map.of()));
        }
    }

    public static class DescriptionCoachesAgainstPollingAndPromisesNotificationScenarioModule
            extends ConfigurationTestModule {
        public DescriptionCoachesAgainstPollingAndPromisesNotificationScenarioModule() {
            super(configurationValues(
                    DescriptionCoachesAgainstPollingAndPromisesNotificationScenario.directory, Map.of()));
        }
    }

    public static class ForwardsTheParentTurnCancellationProbeScenarioModule extends ConfigurationTestModule {
        public ForwardsTheParentTurnCancellationProbeScenarioModule() {
            super(configurationValues(ForwardsTheParentTurnCancellationProbeScenario.directory, Map.of()));
        }
    }

    public static class ExecuteFailsWhenConcurrencyExhaustedScenarioModule extends ConfigurationTestModule {
        public ExecuteFailsWhenConcurrencyExhaustedScenarioModule() {
            super(configurationValues(
                    ExecuteFailsWhenConcurrencyExhaustedScenario.directory,
                    Map.of("yca.subagents.maxConcurrent", "1")));
        }
    }

    public static class ForwardsExplicitModelToTheSubagentStateScenarioModule extends ConfigurationTestModule {
        public ForwardsExplicitModelToTheSubagentStateScenarioModule() {
            super(configurationValues(
                    ForwardsExplicitModelToTheSubagentStateScenario.directory,
                    Map.of(
                            "yca.subagents.allowModelOverride", "true",
                            "yca.subagents.includes", "$default/other-model")));
        }
    }

    public static class ToleratesBlankAgentProfileAndRejectsUnknownScenarioModule extends ConfigurationTestModule {
        public ToleratesBlankAgentProfileAndRejectsUnknownScenarioModule() {
            super(configurationValues(ToleratesBlankAgentProfileAndRejectsUnknownScenario.directory, Map.of()));
        }
    }

    public static class ToleratesBlankExplicitModelScenarioModule extends ConfigurationTestModule {
        public ToleratesBlankExplicitModelScenarioModule() {
            super(configurationValues(ToleratesBlankExplicitModelScenario.directory, Map.of()));
        }
    }

    public static class LeavesModelNullWhenNotRequestedScenarioModule extends ConfigurationTestModule {
        public LeavesModelNullWhenNotRequestedScenarioModule() {
            super(configurationValues(LeavesModelNullWhenNotRequestedScenario.directory, Map.of()));
        }
    }

    public static class IgnoresNonPositiveOrMissingMaxTurnsAsNoPerSpawnOverrideScenarioModule
            extends ConfigurationTestModule {
        public IgnoresNonPositiveOrMissingMaxTurnsAsNoPerSpawnOverrideScenarioModule() {
            super(configurationValues(
                    IgnoresNonPositiveOrMissingMaxTurnsAsNoPerSpawnOverrideScenario.directory, Map.of()));
        }
    }
}
