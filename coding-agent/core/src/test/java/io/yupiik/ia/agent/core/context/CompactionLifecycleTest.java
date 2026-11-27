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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent.CompactionPass;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.CompactionCoordinator.CompactionProgress;
import io.yupiik.ia.agent.core.events.CompactionStartingEvent;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
@Timeout(10)
class CompactionLifecycleTest {
    @TempDir
    Path root;

    private CompactionCoordinator coordinator(
            final CodingAgentConfiguration baseline,
            final JsonMapper mapper,
            final String sessionId,
            final Path workdir,
            final List<Object> events,
            final Consumer<Object> observer,
            final ChatClient chat) {
        final var config = withWorkdir(baseline, workdir);
        return new CompactionCoordinator(
                config,
                chat,
                null,
                new ProviderManager(config, mapper),
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        events.add(event);
                        observer.accept(event);
                    }
                },
                sessionId,
                new BackoffSleeper() {
                    @Override
                    public boolean sleep(final long millis) {
                        return true;
                    }
                },
                workdir);
    }

    private static List<Message> history() {
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("internal system"));
        for (var i = 0; i < 12; i++) {
            messages.add(Message.user("old task " + i));
            messages.add(Message.assistant("old answer " + i, null));
        }
        messages.add(Message.user("continue"));
        return messages;
    }

    private static List<CompactionPass> passes(final List<Object> events) {
        return events.stream()
                .filter(CompactionPass.class::isInstance)
                .map(CompactionPass.class::cast)
                .toList();
    }

    private static void assertPair(final List<CompactionPass> passes, final CompactionPass.Status terminal) {
        assertEquals(2, passes.size());
        assertEquals(CompactionPass.Status.STARTED, passes.getFirst().status());
        assertEquals(terminal, passes.getLast().status());
        assertEquals(passes.getFirst().id(), passes.getLast().id());
        assertEquals(passes.getFirst().sessionId(), passes.getLast().sessionId());
        assertEquals(passes.getFirst().subagentId(), passes.getLast().subagentId());
        assertEquals(passes.getFirst().workdir(), passes.getLast().workdir());
    }

    @Nested
    @FusionSupport(modules = DefaultLifecycleModule.class)
    class DefaultLifecycle {
        @Test
        void manualPassesReachNormalAgentEventListenersOnceWithTheirInvocationScope(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            final var events = new ArrayList<Object>();
            final var observed = new ArrayList<AgentEvent>();
            try (final var bus = ConfiguringContainer.of()
                    .disableAutoDiscovery(true)
                    .register(new FusionListener<AgentEvent>() {
                        @Override
                        public Type eventType() {
                            return AgentEvent.class;
                        }

                        @Override
                        public void onEvent(final RuntimeContainer container, final AgentEvent event) {
                            observed.add(event);
                        }
                    })
                    .start()) {
                final var a = coordinator(configuration, mapper, "A", root.resolve("a"), events, bus::emit, null);
                final var b = coordinator(configuration, mapper, "B", root.resolve("b"), events, bus::emit, null);
                final var small = List.of(Message.user("small"));
                a.compact(small, false, null, 0);
                b.compact(
                        small,
                        false,
                        null,
                        0,
                        false,
                        () -> false,
                        ChatSettings.EMPTY,
                        new CompactionProgress(0, null, "child", "Worker", root.resolve("child")));
                a.compact(small, true, null, 0, true, () -> true, ChatSettings.EMPTY);
            }
            final var passes = passes(events);
            assertEquals(6, observed.size());
            assertEquals(passes, observed);
            assertEquals(
                    List.of("A", "A", "B", "B", "A", "A"),
                    observed.stream().map(AgentEvent::sessionId).toList());
            assertPair(passes.subList(0, 2), CompactionPass.Status.SKIPPED);
            assertPair(passes.subList(2, 4), CompactionPass.Status.SKIPPED);
            assertPair(passes.subList(4, 6), CompactionPass.Status.CANCELLED);
            for (var i = 0; i < passes.size(); i++) {
                assertSame(passes.get(i), observed.get(i), "the original event is delivered directly, not copied");
                assertEquals(
                        i == 2 || i == 3 ? root.resolve("child") : root.resolve("a"),
                        observed.get(i).workdir());
                assertEquals(i == 2 || i == 3 ? "child" : null, observed.get(i).subagentId());
                assertEquals(i == 2 || i == 3 ? "Worker" : null, observed.get(i).subagentName());
            }
        }

        @Test
        void manualOverloadsAndManagerRecoveryOverflowShareExactlyOneOperationBoundary(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            final var events = new ArrayList<Object>();
            final var calls = new AtomicInteger();
            final var coordinator =
                    coordinator(configuration, mapper, "A", root, events, event -> {}, new ChatClient() {
                        @Override
                        public ChatResult chat(
                                final ChatCompletionRequest request,
                                final Consumer<PauseEvent> pause,
                                final BooleanSupplier cancelled) {
                            calls.incrementAndGet();
                            return new ChatResult(Message.assistant("internal retained state", null), Usage.EMPTY);
                        }
                    });
            final var messages = history();
            final var results = List.of(
                    coordinator.compact(messages, false, null, 0),
                    coordinator.compact(messages, true, "focus", 2, () -> false, ChatSettings.EMPTY),
                    coordinator.compact(messages, true, null, 0, true, () -> false, ChatSettings.EMPTY),
                    coordinator.compact(
                            messages,
                            false,
                            null,
                            0,
                            false,
                            () -> false,
                            ChatSettings.EMPTY,
                            new CompactionProgress(10, null, "child", "Worker", root.resolve("child"))));
            for (final var result : results) {
                assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            }
            final var manager = new ContextManager(null, null, coordinator);
            assertTrue(manager.compactForRecovery(messages, () -> false, ChatSettings.EMPTY)
                    .changed());
            assertTrue(manager.compactForOverflow(messages, () -> false, ChatSettings.EMPTY)
                    .changed());
            final var passes = passes(events);
            assertEquals(12, passes.size());
            for (var i = 0; i < passes.size(); i += 2) {
                assertPair(passes.subList(i, i + 2), CompactionPass.Status.SUCCEEDED);
                assertEquals(CompactionResult.Mode.SUMMARY, passes.get(i + 1).mode());
                assertFalse(passes.get(i + 1).detail().contains("internal retained state"));
            }
            assertEquals(6, passes.stream().map(CompactionPass::id).distinct().count());
            assertTrue(calls.get() >= 6, "chunks remain inside a pass, not additional lifecycle pairs");
            assertEquals("child", passes.get(6).subagentId());
            assertEquals(root.resolve("child"), passes.get(7).workdir());
        }

        @Test
        void noChangeVetoAndPreCancelledAreDistinctAndDoNotCallTheModel(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            final var events = new ArrayList<Object>();
            final var veto = new AtomicBoolean();
            final var coordinator = coordinator(
                    configuration,
                    mapper,
                    "A",
                    root,
                    events,
                    event -> {
                        if (event instanceof CompactionStartingEvent hook && veto.get()) {
                            hook.result().forbid("private hook reason");
                        }
                    },
                    new ChatClient() {
                        @Override
                        public ChatResult chat(
                                final ChatCompletionRequest request,
                                final Consumer<PauseEvent> pause,
                                final BooleanSupplier cancelled) {
                            throw new AssertionError("no model work expected");
                        }
                    });
            final var small = List.of(Message.system("system"), Message.user("hello"));
            assertSame(small, coordinator.compact(small, false, null, 0).messages());
            assertPair(passes(events), CompactionPass.Status.SKIPPED);
            events.clear();
            veto.set(true);
            final var progress = new CompactionProgress(0, null, "child", "Worker", root.resolve("child"));
            assertFalse(coordinator
                    .compact(history(), true, null, 0, true, () -> false, ChatSettings.EMPTY, progress)
                    .changed());
            assertPair(passes(events), CompactionPass.Status.SKIPPED);
            final var hook = events.stream()
                    .filter(CompactionStartingEvent.class::isInstance)
                    .map(CompactionStartingEvent.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertEquals("A", hook.eventContext().sessionId());
            assertEquals("child", hook.eventContext().subagentId());
            assertEquals(progress.workdir().toString(), hook.eventContext().cwd());
            assertFalse(passes(events).getLast().detail().contains("private hook reason"));
            events.clear();
            coordinator.compact(history(), true, null, 0, true, () -> true, ChatSettings.EMPTY);
            assertPair(passes(events), CompactionPass.Status.CANCELLED);
        }

        @Test
        void exhaustedFailuresAndUrgentFallbackHaveDifferentTruthfulOutcomes(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            final var events = new ArrayList<Object>();
            final var calls = new AtomicInteger();
            final var coordinator =
                    coordinator(configuration, mapper, "A", root, events, event -> {}, new ChatClient() {
                        @Override
                        public ChatResult chat(
                                final ChatCompletionRequest request,
                                final Consumer<PauseEvent> pause,
                                final BooleanSupplier cancelled)
                                throws IOException {
                            calls.incrementAndGet();
                            throw new IOException("fake provider unavailable");
                        }
                    });
            final var messages = history();
            final var failed = coordinator.compact(messages, false, null, 0);
            assertFalse(failed.changed());
            assertSame(messages, failed.messages());
            assertPair(passes(events), CompactionPass.Status.FAILED);
            assertTrue(calls.get() > 0);
            events.clear();
            final var fallback = coordinator.compact(messages, true, null, 0, true, () -> false, ChatSettings.EMPTY);
            assertEquals(CompactionResult.Mode.TRUNCATE, fallback.mode());
            assertPair(passes(events), CompactionPass.Status.SUCCEEDED);
            assertEquals(
                    CompactionResult.Mode.TRUNCATE, passes(events).getLast().mode());
        }

        @Test
        void uncheckedHookAndModelExceptionsStillHaveOneSafeTerminal(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            for (final var hookFailure : List.of(true, false)) {
                final var events = new ArrayList<Object>();
                final var coordinator = coordinator(
                        configuration,
                        mapper,
                        "A",
                        root,
                        events,
                        event -> {
                            if (hookFailure && event instanceof CompactionStartingEvent) {
                                throw new IllegalStateException("hidden hook exception");
                            }
                        },
                        new ChatClient() {
                            @Override
                            public ChatResult chat(
                                    final ChatCompletionRequest request,
                                    final Consumer<PauseEvent> pause,
                                    final BooleanSupplier cancelled) {
                                throw new IllegalStateException("hidden model exception");
                            }
                        });
                assertThrows(IllegalStateException.class, () -> coordinator.compact(history(), true, null, 0));
                assertPair(passes(events), CompactionPass.Status.FAILED);
                assertFalse(passes(events).getLast().detail().contains("hidden"));
            }
        }

        @Test
        void cancellationDuringModelWorkCannotApplyUrgentTruncationAndScopesStayIndependent(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var events = new CopyOnWriteArrayList<Object>();
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var cancelled = new AtomicBoolean();
            final var a =
                    coordinator(configuration, mapper, "A", root.resolve("a"), events, event -> {}, new ChatClient() {
                        @Override
                        public ChatResult chat(
                                final ChatCompletionRequest request,
                                final Consumer<PauseEvent> pause,
                                final BooleanSupplier probe)
                                throws InterruptedException {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return new ChatResult(Message.assistant("must not be installed", null), Usage.EMPTY);
                        }
                    });
            final var b = coordinator(configuration, mapper, "B", root.resolve("b"), events, event -> {}, null);
            final var messages = history();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var future = executor.submit(() -> a.compact(
                        messages,
                        true,
                        null,
                        0,
                        true,
                        cancelled::get,
                        ChatSettings.EMPTY,
                        new CompactionProgress(0, null, "child", "Worker", root.resolve("child"))));
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    b.compact(List.of(Message.user("small")), false, null, 0);
                    cancelled.set(true);
                } finally {
                    release.countDown();
                }
                final var result = future.get(5, TimeUnit.SECONDS);
                assertSame(messages, result.messages());
                assertEquals(CompactionResult.Mode.NONE, result.mode());
            }
            final var passes = passes(events);
            assertEquals(
                    List.of("A", "B", "B", "A"),
                    passes.stream().map(CompactionPass::sessionId).toList());
            assertNotEquals(passes.get(0).id(), passes.get(1).id());
            assertPair(List.of(passes.get(0), passes.get(3)), CompactionPass.Status.CANCELLED);
            assertPair(List.of(passes.get(1), passes.get(2)), CompactionPass.Status.SKIPPED);
            assertEquals("child", passes.getLast().subagentId());
            assertEquals(root.resolve("child"), passes.getLast().workdir());
        }

        @Test
        void cancellationExceptionIsNotAnOperationalFailure(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            final var events = new ArrayList<Object>();
            final var coordinator =
                    coordinator(configuration, mapper, "A", root, events, event -> {}, new ChatClient() {
                        @Override
                        public ChatResult chat(
                                final ChatCompletionRequest request,
                                final Consumer<PauseEvent> pause,
                                final BooleanSupplier cancelled) {
                            throw new CancellationException("cancelled model work");
                        }
                    });
            assertThrows(CancellationException.class, () -> coordinator.compact(history(), true, null, 0));
            assertPair(passes(events), CompactionPass.Status.CANCELLED);
        }

        @Test
        void interruptedModelWorkReturnsUnchangedWithCancellation(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var events = new CopyOnWriteArrayList<Object>();
            final var coordinator =
                    coordinator(configuration, mapper, "A", root, events, event -> {}, new ChatClient() {
                        @Override
                        public ChatResult chat(
                                final ChatCompletionRequest request,
                                final Consumer<PauseEvent> pause,
                                final BooleanSupplier cancelled)
                                throws InterruptedException {
                            throw new InterruptedException("fake interrupted call");
                        }
                    });
            final var messages = history();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                assertSame(
                        messages,
                        executor.submit(() -> coordinator
                                        .compact(messages, true, null, 0, true, () -> false, ChatSettings.EMPTY)
                                        .messages())
                                .get(5, TimeUnit.SECONDS));
            }
            assertPair(passes(events), CompactionPass.Status.CANCELLED);
        }

        @Test
        void operationAndProgressRequireExplicitNormalizedWorkdirs() {
            for (final var invalid : List.of(Path.of("relative"), root.resolve("nested/../other"))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new CompactionPass(
                                "p", CompactionPass.Status.STARTED, null, null, "A", null, null, invalid));
                assertThrows(
                        IllegalArgumentException.class, () -> new CompactionProgress(0, null, null, null, invalid));
            }
            assertThrows(
                    NullPointerException.class,
                    () -> new CompactionPass("p", CompactionPass.Status.STARTED, null, null, "A", null, null, null));
            assertThrows(NullPointerException.class, () -> new CompactionProgress(0, null, null, null, null));
            assertThrows(
                    NullPointerException.class,
                    () -> new CompactionPass(null, CompactionPass.Status.STARTED, null, null, "A", null, null, root));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new CompactionPass(" ", CompactionPass.Status.STARTED, null, null, "A", null, null, root));
            assertThrows(
                    NullPointerException.class, () -> new CompactionPass("p", null, null, null, "A", null, null, root));
            assertThrows(
                    NullPointerException.class,
                    () -> new CompactionPass("p", CompactionPass.Status.STARTED, null, null, null, null, null, root));
        }
    }

    @Nested
    @FusionSupport(modules = ClearingModule.class)
    class Clearing {
        @TempDir
        static Path root;

        @Test
        void llmFreeClearingReportsItsActualMode(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration config) {
            final var shrink = false;
            final var providers = new ProviderManager(config, mapper);
            final var events = new ArrayList<Object>();
            final var coordinator = new CompactionCoordinator(
                    config,
                    null,
                    new UsageTracker(providers, null, null, Clock.systemUTC()),
                    providers,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            events.add(event);
                        }
                    },
                    "A",
                    new BackoffSleeper(),
                    root);
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 20; i++) {
                messages.add(Message.user("old task " + i));
                messages.add(Message.tool("call-" + i, "x".repeat(30_000)));
            }
            messages.add(Message.user("current task"));
            final var result = coordinator.compact(messages, false, null, 0);
            assertEquals(shrink ? CompactionResult.Mode.SHRUNK : CompactionResult.Mode.CLEARED, result.mode());
            assertPair(passes(events), CompactionPass.Status.SUCCEEDED);
            assertEquals(result.mode(), passes(events).getLast().mode());
        }
    }

    @Nested
    @FusionSupport(modules = ShrinkingModule.class)
    class Shrinking {
        @TempDir
        static Path root;

        @Test
        void llmFreeShrinkingReportsItsActualMode(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration config) {
            final var shrink = true;
            final var providers = new ProviderManager(config, mapper);
            final var events = new ArrayList<Object>();
            final var coordinator = new CompactionCoordinator(
                    config,
                    null,
                    new UsageTracker(providers, null, null, Clock.systemUTC()),
                    providers,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            events.add(event);
                        }
                    },
                    "A",
                    new BackoffSleeper(),
                    root);
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 20; i++) {
                messages.add(Message.user("old task " + i));
                messages.add(Message.tool("call-" + i, "x".repeat(30_000)));
            }
            messages.add(Message.user("current task"));
            final var result = coordinator.compact(messages, false, null, 0);
            assertEquals(shrink ? CompactionResult.Mode.SHRUNK : CompactionResult.Mode.CLEARED, result.mode());
            assertPair(passes(events), CompactionPass.Status.SUCCEEDED);
            assertEquals(result.mode(), passes(events).getLast().mode());
        }
    }

    private static Map<String, String> lifecycleSettings() {
        return Map.of(
                "yca.workingDir", ".",
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.id", "default",
                "yca.provider.providers.0.model", "test-model",
                "yca.provider.providers.0.contextWindow", "128000",
                "yca.pruning.keepRecent", "2",
                "yca.pruning.toolOutputMaxChars", "8",
                "yca.compaction.tail.budgetTokens", "1",
                "yca.compaction.fallbackToMainModel", "false");
    }

    private static Map<String, String> localSettings(final Path root, final boolean shrink) {
        return Map.of(
                "yca.workingDir", root.toString(),
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.id", "default",
                "yca.provider.providers.0.model", "test-model",
                "yca.provider.providers.0.contextWindow", "128000",
                "yca.pruning.keepRecent", "2",
                "yca.pruning.shrinkOutputChars", shrink ? "200" : "0",
                "yca.pruning.protectRecentTokens", "1",
                "yca.compaction.mode", "no_llm",
                "yca.compaction.tail.budgetTokens", "1");
    }

    private static CodingAgentConfiguration withWorkdir(final CodingAgentConfiguration baseline, final Path workdir) {
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                workdir.toString(),
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

    public static class DefaultLifecycleModule extends ConfigurationTestModule {
        public DefaultLifecycleModule() {
            super(lifecycleSettings());
        }
    }

    public static class ClearingModule extends ConfigurationTestModule {
        public ClearingModule() {
            super(localSettings(Clearing.root, false));
        }
    }

    public static class ShrinkingModule extends ConfigurationTestModule {
        public ShrinkingModule() {
            super(localSettings(Shrinking.root, true));
        }
    }
}
