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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent.CompactionPass;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.context.CompactionResult;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.CompactionStartingEvent;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
@Timeout(15)
class AgentCompactionLifecycleTest {
    @Nested
    @FusionSupport(modules = LifecycleModule.class)
    class Configured {
        @TempDir
        static Path root;

        @Test
        void roundBudgetAndAutomaticCheckpointReportEveryOutcomeInTheirOwningScope(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration baseline) {
            for (final var path : List.of("round", "checkpoint", "directive", "urgent-directive")) {
                for (final var scenario :
                        List.of("changed", "noop", "failed", "veto", "cancel", "exception", "adopted")) {
                    for (final var child : List.of(false, true)) {
                        final var session = child ? "B" : "A";
                        final var workdir = root.resolve(session).resolve(child ? "child" : "parent");
                        final var events = new ArrayList<Object>();
                        final var cancelled = new AtomicBoolean();
                        final var needed = new AtomicBoolean(true);
                        final var config = withWorkingDir(baseline, workdir.toString());
                        final var emitter = new Emitter() {
                            @Override
                            public <T> void emit(final T event) {
                                events.add(event);
                                if (event instanceof CompactionStartingEvent hook && "veto".equals(scenario)) {
                                    hook.result().forbid("test veto");
                                }
                            }
                        };
                        final var client = new ChatClient() {
                            @Override
                            public ChatResult chat(
                                    final ChatCompletionRequest request,
                                    final Consumer<PauseEvent> pause,
                                    final BooleanSupplier probe)
                                    throws IOException {
                                return switch (scenario) {
                                    case "failed" -> throw new IOException("fake unavailable");
                                    case "exception" -> throw new IllegalStateException("fake exception");
                                    case "cancel" -> {
                                        cancelled.set(true);
                                        yield new ChatResult(Message.assistant("discard this", null), Usage.EMPTY);
                                    }
                                    default ->
                                        new ChatResult(Message.assistant("internal retained state", null), Usage.EMPTY);
                                };
                            }

                            @Override
                            public Message stream(
                                    final ChatCompletionRequest request,
                                    final Consumer<PauseEvent> pause,
                                    final Consumer<LlmStreamEvent> delta,
                                    final BooleanSupplier probe) {
                                delta.accept(new LlmStreamEvent.End("stop", null));
                                return Message.assistant("done", null);
                            }
                        };
                        final var providers = new ProviderManager(config, mapper);
                        final var usage = new UsageTracker(providers, client, null, Clock.systemUTC()) {
                            @Override
                            public int budgetContext(final List<Message> messages, final int toolsTokens) {
                                return needed.get() ? 6_000 : 1_000;
                            }
                        };
                        final var coordinator = new CompactionCoordinator(
                                config,
                                client,
                                usage,
                                providers,
                                emitter,
                                session,
                                new BackoffSleeper() {
                                    @Override
                                    public boolean sleep(final long millis) {
                                        return true;
                                    }
                                },
                                root.resolve(session));
                        final var manager = new ContextManager(config, usage, coordinator) {
                            @Override
                            public CompactionResult adoptSpeculation(
                                    final List<Message> messages,
                                    final int toolsTokens,
                                    final BooleanSupplier probe,
                                    final ChatSettings settings) {
                                if ("adopted".equals(scenario)) {
                                    needed.set(false);
                                    return new CompactionResult(
                                            List.of(
                                                    Message.system("system prompt"),
                                                    Message.user("<compacted-context>internal retained"
                                                            + " state</compacted-context>")),
                                            CompactionResult.Mode.SUMMARY,
                                            "");
                                }
                                return super.adoptSpeculation(messages, toolsTokens, probe, settings);
                            }

                            @Override
                            public boolean compactNeeded(final List<Message> messages, final int toolsTokens) {
                                return needed.get();
                            }

                            @Override
                            public int compactionTriggerTokens() {
                                return 5_000;
                            }

                            @Override
                            public CompactionResult compact(
                                    final List<Message> messages,
                                    final boolean force,
                                    final String focus,
                                    final int keepLast,
                                    final boolean urgent,
                                    final BooleanSupplier probe,
                                    final ChatSettings settings,
                                    final CompactionCoordinator.CompactionProgress progress) {
                                try {
                                    return super.compact(
                                            messages, force, focus, keepLast, urgent, probe, settings, progress);
                                } finally {
                                    needed.set(false); // this test admits one automatic pass, not a recovery gate
                                }
                            }
                        };
                        final var view = new ContextView();
                        if (path.endsWith("directive")) {
                            view.request(new ContextView.Directive(
                                    "compact", null, 0, "user", true, path.startsWith("urgent")));
                        }
                        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                            final var loop = new AgentLoop(
                                    client,
                                    new ToolRegistry(null) {
                                        @Override
                                        public List<Tool> toWireTools(final Path dir) {
                                            return List.of();
                                        }
                                    },
                                    new ToolExecutionService(null, null, null, null, null, null) {},
                                    config,
                                    new PermissionService(config, workdir),
                                    usage,
                                    null,
                                    manager,
                                    view,
                                    new SessionStore() {
                                        @Override
                                        public void append(final String id, final SessionEntry entry) {}
                                    },
                                    new SystemPromptBuilder(workdir) {
                                        @Override
                                        public String build(final String prompt, final AgentConfiguration agent) {
                                            return "system prompt";
                                        }
                                    },
                                    null,
                                    new StatusReporter(),
                                    emitter,
                                    mapper,
                                    new StructuralCodeGraph() {
                                        @Override
                                        public void ensureReadyAsync(final Path dir) {}
                                    },
                                    providers,
                                    null,
                                    Clock.systemUTC(),
                                    null,
                                    null,
                                    executor,
                                    session,
                                    child ? "child" : null,
                                    child ? "Worker" : null,
                                    null,
                                    new PrecisionDurationMarkerProvider(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    workdir,
                                    new DisabledSandbox(workdir));
                            final var history = new ArrayList<Message>();
                            history.add(Message.system("system prompt"));
                            if (!"noop".equals(scenario)) {
                                for (var i = 0; i < 12; i++) {
                                    history.add(Message.user("old task " + i));
                                    history.add(Message.assistant("old answer " + i, null));
                                }
                            }
                            try {
                                if (!"round".equals(path)) {
                                    final var answer = loop.run("continue", null, session, history, cancelled::get);
                                    if (List.of("changed", "noop", "failed", "veto", "adopted")
                                            .contains(scenario)) {
                                        assertEquals(
                                                "done", answer, "notices and compaction failures are nonfatal turns");
                                    }
                                } else {
                                    new AgentTurnExecutor(loop)
                                            .checkRoundBudget(session, history, 17, ChatSettings.EMPTY, cancelled::get);
                                }
                            } catch (final IllegalStateException expected) {
                                assertEquals("exception", scenario);
                            }
                        }
                        final var passes = events.stream()
                                .filter(CompactionPass.class::isInstance)
                                .map(CompactionPass.class::cast)
                                .toList();
                        assertEquals(2, passes.size(), path + ":" + scenario + ":" + child);
                        assertEquals(
                                CompactionPass.Status.STARTED, passes.getFirst().status());
                        assertEquals(
                                switch (scenario) {
                                    case "changed", "adopted" -> CompactionPass.Status.SUCCEEDED;
                                    case "noop", "veto" -> CompactionPass.Status.SKIPPED;
                                    case "cancel" -> CompactionPass.Status.CANCELLED;
                                    default ->
                                        "failed".equals(scenario) && "urgent-directive".equals(path)
                                                ? CompactionPass.Status.SUCCEEDED
                                                : CompactionPass.Status.FAILED;
                                },
                                passes.getLast().status(),
                                path + ":" + scenario + ":" + child);
                        assertEquals(passes.getFirst().id(), passes.getLast().id());
                        for (final var pass : passes) {
                            assertEquals(session, pass.sessionId());
                            assertEquals(child ? "child" : null, pass.subagentId());
                            assertEquals(child ? "Worker" : null, pass.subagentName());
                            assertEquals(workdir, pass.workdir());
                        }
                        assertEquals(
                                List.of("changed", "adopted").contains(scenario)
                                                || ("failed".equals(scenario) && "urgent-directive".equals(path))
                                        ? 1
                                        : 0,
                                events.stream()
                                        .filter(AgentEvent.Compacted.class::isInstance)
                                        .count());
                        assertTrue(passes.getLast().detail() != null);
                    }
                }
            }
        }
    }

    public static class LifecycleModule extends ConfigurationTestModule {
        public LifecycleModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", Configured.root.toString()),
                    Map.entry("yca.workingDir", Configured.root.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.contextWindow", "128000"),
                    Map.entry("yca.pruning.enabled", "false"),
                    Map.entry("yca.pruning.keepRecent", "2"),
                    Map.entry("yca.pruning.toolOutputMaxChars", "8"),
                    Map.entry("yca.compaction.tail.budgetTokens", "1"),
                    Map.entry("yca.compaction.fallbackToMainModel", "false")));
        }
    }

    private static CodingAgentConfiguration withWorkingDir(
            final CodingAgentConfiguration baseline, final String workingDir) {
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                workingDir,
                baseline.projectDir(),
                baseline.provider(),
                baseline.model(),
                baseline.llm(),
                baseline.compaction(),
                baseline.pruning(),
                baseline.compression(),
                baseline.memory(),
                baseline.todos(),
                baseline.optimize(),
                baseline.permissions(),
                baseline.sandbox(),
                baseline.mcp(),
                baseline.mavenRepositoryKnowledge(),
                baseline.structuralCodeGraph(),
                baseline.sessions(),
                baseline.skills(),
                baseline.agents(),
                baseline.mode(),
                baseline.httpClient(),
                baseline.tools(),
                baseline.subagents(),
                baseline.schedulerThreads(),
                baseline.metrics(),
                baseline.maxLineLength(),
                baseline.resumeAliases(),
                baseline.slashcommands(),
                baseline.prompt(),
                baseline.labels(),
                baseline.logging(),
                baseline.hooks(),
                baseline.notifications(),
                baseline.secrets(),
                null);
    }
}
