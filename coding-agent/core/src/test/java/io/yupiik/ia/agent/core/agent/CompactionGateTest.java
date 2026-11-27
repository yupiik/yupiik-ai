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
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.GateConfiguration;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.context.CompactionResult;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.UsageTracker;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage for the AgentLoop compaction gate: the bounded recovery loop that runs when the
 * automatic post-turn compact leaves the context over its trigger. Each case drives the loop with a
 * scripted {@link UsageTracker} (deterministic effective-context values) and a scripted
 * {@link ContextManager} (scripted recovery/spill outcomes) and asserts the gate escalation event
 * (or its absence) plus the recovery cadence.
 */
@ExtendWith(TestJsonMapper.class)
@Timeout(30)
class CompactionGateTest {
    @TempDir
    private static Path testWorkdir;

    private static final int TRIGGER = 5_000;
    private static final int USABLE = 128_000;

    /**
     * Scripted tracker: {@link #effective} is a mutable value the test changes between gate passes
     * to simulate the context shrinking or staying flat after a recovery attempt.
     */
    private static final class ScriptedUsageTracker extends UsageTracker {
        private volatile int effective;

        private ScriptedUsageTracker(final int effective) {
            this.effective = effective;
        }

        void setEffective(final int value) {
            this.effective = value;
        }

        @Override
        public int effectiveContext(final List<Message> messages, final int extraToolTokens) {
            return effective;
        }

        @Override
        public int budgetContext(final List<Message> messages, final int extraToolTokens) {
            return effective;
        }

        @Override
        public double contextPercent(final int estimatedTokens) {
            return estimatedTokens * 100d / USABLE;
        }
    }

    /** Scripted context manager: recovery/spill outcomes are set by each test. */
    private static class ScriptedContextManager extends ContextManager {
        private final int trigger;
        int recoveryCalls;
        int spillCalls;
        private int gateMaxAttempts = -1;
        private long gateMaxDurationMs = -1;

        private ScriptedContextManager(final int trigger) {
            this(trigger, -1, -1);
        }

        private ScriptedContextManager(final int trigger, final int gateMaxAttempts, final long gateMaxDurationMs) {
            super(null, null, null);
            this.trigger = trigger;
            this.gateMaxAttempts = gateMaxAttempts;
            this.gateMaxDurationMs = gateMaxDurationMs;
        }

        @Override
        public final int compactionTriggerTokens() {
            return trigger;
        }

        @Override
        public final int usableWindowTokens() {
            return USABLE;
        }

        @Override
        public GateConfiguration resolvedGate(final ChatSettings settings) {
            return new GateConfiguration(
                    gateMaxAttempts >= 0 ? gateMaxAttempts : GateConfiguration.DEFAULT_MAX_ATTEMPTS,
                    gateMaxDurationMs >= 0 ? (int) gateMaxDurationMs : GateConfiguration.DEFAULT_MAX_DURATION_MS);
        }

        @Override
        public CompactionResult compactForRecovery(
                final List<Message> messages,
                final BooleanSupplier cancelled,
                final ChatSettings settings,
                final CompactionCoordinator.CompactionProgress progress) {
            recoveryCalls++;
            return new CompactionResult(messages, CompactionResult.Mode.NONE, "");
        }

        @Override
        public CompactionResult compactForRecovery(
                final List<Message> messages, final BooleanSupplier cancelled, final ChatSettings settings) {
            return compactForRecovery(messages, cancelled, settings, null);
        }

        @Override
        public CompactionResult localSpill(final List<Message> messages, final int targetTokens) {
            spillCalls++;
            return new CompactionResult(messages, CompactionResult.Mode.NONE, "");
        }

        @Override
        public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
            return false;
        }

        @Override
        public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
            return false;
        }
    }

    /** Virtual nano-time source so the duration-budget case does not wait on the wall clock. */
    private static final class VirtualNanos extends PrecisionDurationMarkerProvider {
        private volatile long value = TimeUnit.MILLISECONDS.toNanos(1_000);

        void advanceMillis(final long millis) {
            value += TimeUnit.MILLISECONDS.toNanos(millis);
        }

        @Override
        public long now() {
            return value;
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredModule.class)
    class Configured {
        @TempDir
        static Path storageHome;

        @Test
        void unproductiveRecoveryEscalatesImmediately(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration config) throws Exception {
            final var tracker = new ScriptedUsageTracker(15_000);
            final var manager = new ScriptedContextManager(TRIGGER); // recovery and spill both NONE
            final var events = new CopyOnWriteArrayList<AgentEvent>();
            final var requests = new CopyOnWriteArrayList<List<Message>>();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var loop = newLoop(config, mapper, newClient(requests), tracker, manager, events, executor, null);
                loop.run("work", null, "s1", List.of(), () -> false);
            }
            // the escalation ERROR event is exactly what the gate must emit when nothing can be reclaimed
            final var errors = errorNotices(events);
            assertEquals(1, errors.size(), "one escalation notice: " + events);
            assertTrue(errors.get(0).startsWith("Compaction gate:"), errors.get(0));
            assertEquals(1, manager.recoveryCalls);
            assertEquals(1, manager.spillCalls, "NONE recovery falls through to the local spill");
        }

        @Test
        void twoWeakPassesEscalateOnTheSecond(final JsonMapper mapper, @Fusion final CodingAgentConfiguration config)
                throws Exception {
            final var tracker = new ScriptedUsageTracker(15_000);
            final var manager = new ScriptedContextManager(TRIGGER) {
                @Override
                public CompactionResult compactForRecovery(
                        final List<Message> messages,
                        final BooleanSupplier cancelled,
                        final ChatSettings settings,
                        final CompactionCoordinator.CompactionProgress progress) {
                    recoveryCalls++; // bumps the call count
                    // reclaim ~2% of the CURRENT effective base, keeping it above the trigger
                    final var before = tracker.effective;
                    tracker.setEffective(before - Math.max(1, (int) (before * 0.02)));
                    return new CompactionResult(List.copyOf(messages), CompactionResult.Mode.SUMMARY, "");
                }
            };
            final var events = new CopyOnWriteArrayList<AgentEvent>();
            final var requests = new CopyOnWriteArrayList<List<Message>>();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var loop = newLoop(config, mapper, newClient(requests), tracker, manager, events, executor, null);
                loop.run("work", null, "s1", List.of(), () -> false);
            }
            final var errors = errorNotices(events);
            assertEquals(1, errors.size(), "second weak pass escalates: " + events);
            assertEquals(2, manager.recoveryCalls, "exactly two recovery passes before escalation");
            assertTrue(errors.get(0).startsWith("Compaction gate:"), errors.get(0));
        }

        @Test
        void llmFreeGateStillEscalatesWithoutAnyLlmCall(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration config) throws Exception {
            // under ignoreLlm the checkpoint compact is local-only; when even the local cascade cannot
            // reclaim (scripted NONE), the gate must still escalate - and never call the LLM (the
            // recovery here is the real local compact, exercised through the scripted manager).
            final var tracker = new ScriptedUsageTracker(15_000);
            final var manager = new ScriptedContextManager(TRIGGER) {
                @Override
                public boolean ignoreLlm(final ChatSettings settings) {
                    return true;
                }

                @Override
                public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                    // the checkpoint itself stays green (like a non-compactable context): the gate is the
                    // only path that runs, exactly like the existing gate tests
                    return false;
                }

                @Override
                public CompactionResult compactForRecovery(
                        final List<Message> messages,
                        final BooleanSupplier cancelled,
                        final ChatSettings settings,
                        final CompactionCoordinator.CompactionProgress progress) {
                    recoveryCalls++;
                    return new CompactionResult(messages, CompactionResult.Mode.NONE, "");
                }

                @Override
                public CompactionResult localSpill(final List<Message> messages, final int targetTokens) {
                    spillCalls++;
                    return new CompactionResult(messages, CompactionResult.Mode.NONE, "");
                }
            };
            final var events = new CopyOnWriteArrayList<AgentEvent>();
            final var requests = new CopyOnWriteArrayList<List<Message>>();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var loop = newLoop(config, mapper, newClient(requests), tracker, manager, events, executor, null);
                loop.run("work", null, "s1", List.of(), () -> false);
            }
            final var errors = errorNotices(events);
            assertEquals(1, errors.size(), "one escalation notice under llmFree: " + events);
            assertTrue(errors.get(0).startsWith("Compaction gate:"), errors.get(0));
            assertEquals(1, manager.recoveryCalls, "local-only recovery ran once");
            assertEquals(1, manager.spillCalls, "local spill ran once after NONE recovery");
        }

        @Test
        void strongReductionBelowTriggerExitsCleanlyWithoutEscalation(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration config) throws Exception {
            final var tracker = new ScriptedUsageTracker(6_000);
            final var manager = new ScriptedContextManager(TRIGGER) {
                @Override
                public CompactionResult compactForRecovery(
                        final List<Message> messages,
                        final BooleanSupplier cancelled,
                        final ChatSettings settings,
                        final CompactionCoordinator.CompactionProgress progress) {
                    recoveryCalls++;
                    tracker.setEffective(TRIGGER - 1); // a single strong pass drops below the trigger
                    return new CompactionResult(List.copyOf(messages), CompactionResult.Mode.SUMMARY, "");
                }
            };
            final var events = new CopyOnWriteArrayList<AgentEvent>();
            final var requests = new CopyOnWriteArrayList<List<Message>>();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var loop = newLoop(config, mapper, newClient(requests), tracker, manager, events, executor, null);
                loop.run("work", null, "s1", List.of(), () -> false);
            }
            assertTrue(
                    errorNotices(events).isEmpty(),
                    "no escalation notice when recovery lands below trigger: " + events);
            assertEquals(1, manager.recoveryCalls);
        }

        @Test
        void attemptBudgetExhaustionEscalates(final JsonMapper mapper, @Fusion final CodingAgentConfiguration config)
                throws Exception {
            final var tracker = new ScriptedUsageTracker(10_000);
            // recovery always reclaims a strong slice (6%, above the 5% weak-pass bar) - never enough to
            // drop below the trigger, so the gate runs out of ATTEMPTS (not weak passes) and escalates
            final var manager = new ScriptedContextManager(TRIGGER, 3, -1) {
                @Override
                public CompactionResult compactForRecovery(
                        final List<Message> messages,
                        final BooleanSupplier cancelled,
                        final ChatSettings settings,
                        final CompactionCoordinator.CompactionProgress progress) {
                    recoveryCalls++;
                    final var before = tracker.effective;
                    tracker.setEffective(Math.max(TRIGGER + 1, before - Math.max(1, (int) (before * 0.06))));
                    return new CompactionResult(List.copyOf(messages), CompactionResult.Mode.SUMMARY, "");
                }
            };
            final var events = new CopyOnWriteArrayList<AgentEvent>();
            final var requests = new CopyOnWriteArrayList<List<Message>>();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var loop = newLoop(config, mapper, newClient(requests), tracker, manager, events, executor, null);
                loop.run("work", null, "s1", List.of(), () -> false);
            }
            final var errors = errorNotices(events);
            assertEquals(1, errors.size(), "attempt-budget exhaustion escalates: " + events);
            assertEquals(3, manager.recoveryCalls, "exactly the budgeted attempts run before escalation");
            assertTrue(errors.get(0).startsWith("Compaction gate:"), errors.get(0));
        }

        @Test
        void wallClockBudgetExhaustionEscalates(final JsonMapper mapper, @Fusion final CodingAgentConfiguration config)
                throws Exception {
            final var tracker = new ScriptedUsageTracker(10_000);
            final var time = new VirtualNanos();
            final var manager = new ScriptedContextManager(TRIGGER, -1, 500) {
                @Override
                public CompactionResult compactForRecovery(
                        final List<Message> messages,
                        final BooleanSupplier cancelled,
                        final ChatSettings settings,
                        final CompactionCoordinator.CompactionProgress progress) {
                    recoveryCalls++;
                    // advance the virtual clock past the 500ms budget on the first pass
                    time.advanceMillis(501);
                    final var before = tracker.effective;
                    tracker.setEffective(Math.max(TRIGGER + 1, before - Math.max(1, before / 100)));
                    return new CompactionResult(List.copyOf(messages), CompactionResult.Mode.SUMMARY, "");
                }
            };
            final var events = new CopyOnWriteArrayList<AgentEvent>();
            final var requests = new CopyOnWriteArrayList<List<Message>>();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var loop = newLoop(config, mapper, newClient(requests), tracker, manager, events, executor, time);
                loop.run("work", null, "s1", List.of(), () -> false);
            }
            final var errors = errorNotices(events);
            assertEquals(1, errors.size(), "duration-budget exhaustion escalates: " + events);
            assertTrue(errors.get(0).startsWith("Compaction gate:"), errors.get(0));
        }

        @Test
        void cancelledTurnAbortsTheGateWithoutEscalation(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration config) throws Exception {
            final var tracker = new ScriptedUsageTracker(15_000);
            final var cancelled = new AtomicBoolean();
            final var manager = new ScriptedContextManager(TRIGGER) {
                @Override
                public CompactionResult compactForRecovery(
                        final List<Message> messages,
                        final BooleanSupplier probe,
                        final ChatSettings settings,
                        final CompactionCoordinator.CompactionProgress progress) {
                    recoveryCalls++;
                    // the scripted recovery flips the cancellation so the gate must abort mid-loop
                    cancelled.set(true);
                    return new CompactionResult(List.copyOf(messages), CompactionResult.Mode.SUMMARY, "");
                }
            };
            final var events = new CopyOnWriteArrayList<AgentEvent>();
            final var requests = new CopyOnWriteArrayList<List<Message>>();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var loop = newLoop(config, mapper, newClient(requests), tracker, manager, events, executor, null);
                loop.run("work", null, "s1", List.of(), cancelled::get);
            }
            assertTrue(errorNotices(events).isEmpty(), "no escalation when the turn is cancelled: " + events);
            assertEquals(1, manager.recoveryCalls, "the gate aborts right after the cancelled pass");
        }
    }

    private static List<String> errorNotices(final List<AgentEvent> events) {
        return events.stream()
                .filter(e -> e instanceof AgentEvent.Notice notice && notice.level() == AgentEvent.Notice.Level.ERROR)
                .map(e -> ((AgentEvent.Notice) e).message())
                .toList();
    }

    /** A chat client answering immediately, recording requests. */
    private static ChatClient newClient(final List<List<Message>> requests) {
        return new ChatClient() {
            @Override
            public ChatResult chat(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> listener,
                    final BooleanSupplier probe)
                    throws InterruptedException {
                return new ChatResult(Message.assistant("ok", null), Usage.EMPTY);
            }

            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> listener,
                    final Consumer<LlmStreamEvent> delta,
                    final BooleanSupplier probe)
                    throws IOException, InterruptedException {
                requests.add(List.copyOf(request.messages()));
                delta.accept(new LlmStreamEvent.End("stop", null));
                return Message.assistant("done", null);
            }
        };
    }

    private AgentLoop newLoop(
            final CodingAgentConfiguration config,
            final JsonMapper mapper,
            final ChatClient client,
            final UsageTracker usage,
            final ContextManager manager,
            final List<AgentEvent> events,
            final ExecutorService executor,
            final PrecisionDurationMarkerProvider precision) {
        final var resolvedPrecision = precision == null ? new PrecisionDurationMarkerProvider() : precision;
        final var emitter = new Emitter() {
            @Override
            public <T> void emit(final T event) {
                if (event instanceof AgentEvent agentEvent) {
                    events.add(agentEvent);
                }
            }
        };
        final var providers = new ProviderManager(config, mapper);
        final var loop = new AgentLoop(
                client,
                new ToolRegistry(null) {
                    @Override
                    public List<Tool> toWireTools(final Path workdir) {
                        return List.of();
                    }
                },
                new ToolExecutionService(null, null, null, null, null, null) {},
                config,
                new PermissionService(config, (config).resolveWorkingDir()),
                usage,
                null,
                manager,
                null,
                new SessionStore() {
                    @Override
                    public void append(final String id, final SessionEntry entry) {}
                },
                new SystemPromptBuilder(testWorkdir) {
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
                    public void ensureReadyAsync(final Path root) {}
                },
                providers,
                null,
                Clock.systemUTC(),
                null,
                null,
                executor,
                "s1",
                null,
                null,
                null,
                resolvedPrecision,
                null,
                null,
                null,
                null,
                null,
                (config).resolveWorkingDir(),
                new DisabledSandbox((config).resolveWorkingDir()));
        // the gate's per-pass backoff is a real Thread.sleep (capped at 5s), which would stall the
        // suite for ~9s across these cases; the tests assert the gate's cadence/outcome through the
        // recovery-call counts and escalation events, never the wall-clock delay, so a no-op sleeper
        // is safe and keeps the class fast. The wall-clock budget case runs on its own VirtualNanos.
        loop.backoffSleeper(new BackoffSleeper() {
            @Override
            public boolean sleep(final long millis) {
                return true;
            }
        });
        return loop;
    }

    public static class ConfiguredModule extends ConfigurationTestModule {
        public ConfiguredModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var entries = new HashMap<String, String>();
            entries.put("yca.storageHome", Configured.storageHome.toString());
            entries.put("yca.workingDir", Configured.storageHome.toString());
            entries.put("yca.provider.providers.length", "1");
            entries.put("yca.provider.providers.0.id", "default");
            entries.put("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions");
            entries.put("yca.provider.providers.0.apiKey", "test-key");
            entries.put("yca.provider.providers.0.model", "test-model");
            entries.put("yca.provider.providers.0.contextWindow", "128000");
            entries.put("yca.compaction.thresholdPercent", "20");
            entries.put("yca.compaction.speculative.startPercent", "1");
            entries.put("yca.pruning.enabled", "false");
            entries.put("yca.pruning.keepRecent", "2");
            entries.put("yca.llm.maxIterations", "3");
            return entries;
        }
    }
}
