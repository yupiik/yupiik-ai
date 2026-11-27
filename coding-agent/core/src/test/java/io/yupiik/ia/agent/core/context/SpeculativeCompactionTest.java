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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.events.CompactionStartingEvent;
import io.yupiik.ia.agent.core.llm.*;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class SpeculativeCompactionTest {
    private record Fixture(CompactionCoordinator coordinator, ContextManager manager, UsageTracker usage)
            implements AutoCloseable {
        @Override
        public void close() {
            manager.shutdownSpeculation();
        }
    }

    private Fixture fixture(
            final CodingAgentConfiguration cfg, final JsonMapper mapper, final ChatClient chat, final Emitter emitter) {
        final var pm = new ProviderManager(cfg, mapper);
        final var usage = new UsageTracker(pm, chat, null, Clock.systemUTC());
        final var coordinator = new CompactionCoordinator(
                cfg, chat, usage, pm, emitter, "s1", new BackoffSleeper(), (cfg).resolveWorkingDir());
        return new Fixture(coordinator, new ContextManager(cfg, usage, coordinator), usage);
    }

    private static ChatClient chat(final AtomicInteger calls) {
        return new ChatClient() {
            @Override
            public ChatResult chat(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> listener,
                    final BooleanSupplier cancelled) {
                calls.incrementAndGet();
                return new ChatResult(Message.assistant("digest", null), new Usage(100, 10, 110));
            }
        };
    }

    private static List<Message> history() {
        final var result = new ArrayList<Message>();
        result.add(Message.system("system"));
        for (var i = 0; i < 20; i++) {
            result.add(Message.user("old request " + i + "x".repeat(600)));
        }
        result.add(Message.user("current request"));
        result.add(Message.assistant("current answer", null));
        return result;
    }

    @Nested
    @FusionSupport(modules = ConcurrentModule.class)
    class Concurrent {
        @Test
        void exactPrefixAndCurrentTail(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            try (final var f = fixture(configuration, mapper, chat(new AtomicInteger()), null)) {
                final var messages = history();
                final var source = f.coordinator
                        .prepareSpeculation(messages, ChatSettings.EMPTY, 0)
                        .orElseThrow();
                final var candidate = f.coordinator.generateSpeculativeSummary(source, () -> false);
                final var tail = new ArrayList<>(messages.subList(source.cutoff(), messages.size()));
                messages.add(Message.user("new request"));
                final var result = f.coordinator.validateAndAssemble(
                        candidate, messages, f.coordinator.eligibleCutoff(messages), 0);
                assertTrue(result.adopted());
                final var out = result.result().messages();
                assertEquals("new request", out.getLast().content());
                assertEquals(tail, out.subList(2, out.size() - 1));
                assertEquals(1, f.usage.auxiliaryRequests());
                assertEquals(100, f.usage.auxiliaryPromptTokens());
                assertEquals(
                        1,
                        out.stream()
                                .filter(m -> String.valueOf(m.content()).startsWith("<compacted-context>"))
                                .count());
            }
        }

        @Test
        void changedPrefixAndRetainedTailPolicy(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            try (final var f = fixture(configuration, mapper, chat(new AtomicInteger()), null)) {
                final var messages = history();
                final var source =
                        f.coordinator.prepareSpeculation(messages, null, 0).orElseThrow();
                final var candidate = f.coordinator.generateSpeculativeSummary(source, () -> false);
                assertFalse(f.coordinator
                        .validateAndAssemble(candidate, messages, source.cutoff() - 1, 0)
                        .adopted());
                messages.set(1, Message.user("changed"));
                assertFalse(f.coordinator
                        .validateAndAssemble(candidate, messages, source.cutoff(), 0)
                        .adopted());
                messages.set(1, source.prefix().get(1));
                messages.set(0, Message.system("different system"));
                assertFalse(f.coordinator.compatibleSpeculation(source, messages, null));
            }
        }

        @Test
        void nestedPayloadsAreDeepSnapshots(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            try (final var f = fixture(configuration, mapper, chat(new AtomicInteger()), null)) {
                final var messages = history();
                final var nested = new HashMap<String, Object>();
                nested.put("value", new ArrayList<>(List.of("before")));
                final var metadata = new HashMap<String, Object>();
                metadata.put("nested", nested);
                metadata.put("nullable", null);
                messages.set(1, new Message("user", List.of(nested), "name", null, null, metadata));
                final var source =
                        f.coordinator.prepareSpeculation(messages, null, 0).orElseThrow();
                nested.put("value", List.of("after"));
                assertFalse(f.coordinator.compatibleSpeculation(source, messages, null));
                assertEquals(
                        List.of("before"),
                        ((Map<?, ?>) ((List<?>) source.prefix().get(1).content()).getFirst()).get("value"));
                messages.set(1, new Message("user", new Object(), null, null, null, null));
                assertTrue(f.coordinator.prepareSpeculation(messages, null, 0).isEmpty());
            }
        }

        @Test
        void toolPairCannotCrossCutoff(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            try (final var f = fixture(configuration, mapper, chat(new AtomicInteger()), null)) {
                final var messages = history();
                final var calls = new ArrayList<ToolCall>();
                calls.add(new ToolCall("open", "function", new ToolCall.FunctionCall("bash", "{}"), 0));
                messages.set(2, Message.assistant(null, calls));
                final var source =
                        f.coordinator.prepareSpeculation(messages, null, 0).orElseThrow();
                assertTrue(source.cutoff() <= 2, "unanswered call stays in the retained tail");
                calls.set(0, new ToolCall("changed", "function", new ToolCall.FunctionCall("bash", "{}"), 1));
                assertEquals("changed", messages.get(2).toolCalls().getFirst().id());
            }
        }

        @Test
        void oversizedEmptyAndNonReducingCandidatesAreRejected(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            try (final var f = fixture(configuration, mapper, chat(new AtomicInteger()), null)) {
                final var messages = history();
                final var source =
                        f.coordinator.prepareSpeculation(messages, null, 0).orElseThrow();
                assertFalse(f.coordinator
                        .validateAndAssemble(
                                new SpeculativeCompaction.Candidate(source, " ", 1, 0), messages, source.cutoff(), 0)
                        .adopted());
                final var candidate = new SpeculativeCompaction.Candidate(source, "digest", 1, 0);
                assertEquals(
                        SpeculativeCompaction.Rejection.TOO_LARGE,
                        f.coordinator
                                .validateAndAssemble(candidate, messages, source.cutoff(), 200000)
                                .rejection());
                assertFalse(f.coordinator
                        .validateAndAssemble(
                                new SpeculativeCompaction.Candidate(source, "x".repeat(50000), 1, 0),
                                messages,
                                source.cutoff(),
                                0)
                        .adopted());
            }
        }

        @Test
        void preservesSubagentOnceAndDoesNotReplayOldRequest(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            try (final var f = fixture(configuration, mapper, chat(new AtomicInteger()), null)) {
                final var messages = history();
                final var subagent = Message.user("<subagent id='one'>result</subagent>");
                messages.set(2, subagent);
                final var source =
                        f.coordinator.prepareSpeculation(messages, null, 0).orElseThrow();
                final var candidate = f.coordinator.generateSpeculativeSummary(source, () -> false);
                messages.add(Message.user("latest"));
                final var result = f.coordinator
                        .validateAndAssemble(candidate, messages, f.coordinator.eligibleCutoff(messages), 0)
                        .result()
                        .messages();
                assertEquals(1, result.stream().filter(subagent::equals).count());
                assertEquals("latest", result.getLast().content());
            }
        }

        @Test
        void runningJobIsReusedAndHasNoForegroundEvents(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var started = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var calls = new AtomicInteger();
            final var events = new CopyOnWriteArrayList<Object>();
            final var emitter = new Emitter() {
                @Override
                public <T> void emit(final T event) {
                    events.add(event);
                }
            };
            final var client = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest r, final Consumer<PauseEvent> l, final BooleanSupplier c)
                        throws InterruptedException {
                    calls.incrementAndGet();
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return new ChatResult(Message.assistant("digest", null), new Usage(100, 10, 110));
                }
            };
            try (final var f = fixture(configuration, mapper, client, emitter);
                    final var executor = Executors.newSingleThreadExecutor()) {
                final var messages = history();
                final var original = List.copyOf(messages);
                assertTrue(f.manager.maybeLaunchSpeculation(messages, 0, () -> false, null));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertEquals(original, messages);
                assertFalse(f.manager.maybeLaunchSpeculation(messages, 0, () -> false, null));
                messages.add(Message.user("late"));
                final var adoption = executor.submit(() -> f.manager.adoptSpeculation(messages, 0, () -> false, null));
                release.countDown();
                final var result = adoption.get(5, TimeUnit.SECONDS);
                assertNotNull(result);
                assertTrue(result.changed());
                assertEquals("late", result.messages().getLast().content());
                assertEquals(1, calls.get());
                assertTrue(events.stream().allMatch(CompactionStartingEvent.class::isInstance));
                assertEquals(2, events.size());
            } finally {
                release.countDown();
            }
        }

        @Test
        void hookVetoAtLaunchAndAdoption(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            final var gates = new AtomicInteger();
            final var vetoAt = new AtomicInteger(1);
            final var calls = new AtomicInteger();
            final var emitter = new Emitter() {
                @Override
                public <T> void emit(final T event) {
                    if (event instanceof CompactionStartingEvent hook && gates.incrementAndGet() == vetoAt.get()) {
                        hook.result().forbid("no");
                    }
                }
            };
            try (final var f = fixture(configuration, mapper, chat(calls), emitter)) {
                assertFalse(f.manager.maybeLaunchSpeculation(history(), 0, () -> false, null));
                assertEquals(0, calls.get());
                vetoAt.set(3);
                assertTrue(f.manager.maybeLaunchSpeculation(history(), 0, () -> false, null));
                final var result = f.manager.adoptSpeculation(history(), 0, () -> false, null);
                assertNotNull(result);
                assertFalse(result.changed());
                assertEquals("compaction vetoed by hook", result.error());
                assertEquals(1, calls.get());
            }
        }

        @Test
        void cancelledWorkerRetainsSlotUntilActualExit(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var exited = new CountDownLatch(1);
            try (final var f = fixture(configuration, mapper, chat(new AtomicInteger()), null)) {
                final var source =
                        f.coordinator.prepareSpeculation(history(), null, 0).orElseThrow();
                try (final var manager = new SpeculativeCompactionManager((s, cancelled) -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        assertTrue(cancelled.getAsBoolean());
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exited.countDown();
                    }
                    return new SpeculativeCompaction.Candidate(s, "late", 1, 0);
                })) {
                    assertTrue(manager.launch(source, () -> false));
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    manager.newGeneration();
                    assertFalse(manager.canLaunch());
                    assertTrue(manager.take(source, () -> false).isEmpty());
                    release.countDown();
                    assertTrue(exited.await(5, TimeUnit.SECONDS));
                } finally {
                    release.countDown();
                }
            }
        }

        @Test
        void failedSpeculationIsNotTruncationAndCannotRelaunch(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            final var client = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest r, final Consumer<PauseEvent> l, final BooleanSupplier c)
                        throws IOException {
                    throw new IOException("failure");
                }
            };
            try (final var f = fixture(configuration, mapper, client, null)) {
                final var messages = history();
                final var before = List.copyOf(messages);
                assertTrue(f.manager.maybeLaunchSpeculation(messages, 0, () -> false, null));
                assertNull(f.manager.adoptSpeculation(messages, 0, () -> false, null));
                assertEquals(before, messages);
                assertFalse(f.manager.maybeLaunchSpeculation(messages, 0, () -> false, null));
            }
        }
    }

    @Nested
    @FusionSupport(modules = DisabledModule.class)
    class Disabled {
        @Test
        void disabledConfiguration(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            final var calls = new AtomicInteger();
            try (final var f = fixture(configuration, mapper, chat(calls), null)) {
                assertFalse(f.manager.maybeLaunchSpeculation(history(), 0, () -> false, null));
                assertEquals(0, calls.get());
            }
        }
    }

    @Nested
    @FusionSupport(modules = InvalidPolicyModule.class)
    class InvalidPolicy {
        @Test
        @SuppressWarnings("try") // Close the fixture if invalid configuration is unexpectedly accepted.
        void rejectsInvalidConfiguration(final JsonMapper mapper, @Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var configuration = container.lookup(CodingAgentConfiguration.class)) {
                    try (final var ignored =
                            fixture(configuration.instance(), mapper, chat(new AtomicInteger()), null)) {
                        fail("invalid speculative configuration must be rejected");
                    }
                }
            });
        }
    }

    @Nested
    @FusionSupport(modules = InvalidPercentModule.class)
    class InvalidPercent {
        @Test
        @SuppressWarnings("try") // Close the fixture if invalid configuration is unexpectedly accepted.
        void rejectsInvalidConfiguration(final JsonMapper mapper, @Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var configuration = container.lookup(CodingAgentConfiguration.class)) {
                    try (final var ignored =
                            fixture(configuration.instance(), mapper, chat(new AtomicInteger()), null)) {
                        fail("invalid speculative configuration must be rejected");
                    }
                }
            });
        }
    }

    private static Map<String, String> speculativeSettings(final Map<String, String> extra) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", "x");
        entries.put("yca.workingDir", ".");
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.baseUrl", "https://api.openai.com/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "m");
        entries.put("yca.provider.providers.0.contextWindow", "128000");
        entries.put("yca.compaction.tail.budgetTokens", "1");
        entries.put("yca.pruning.keepRecent", "2");
        entries.put("yca.compaction.summarizer.chunkChars", "100000");
        entries.put("yca.compaction.speculative.policy", "concurrent");
        entries.put("yca.compaction.speculative.startPercent", "1");
        entries.put("yca.llm.retriesMaxAttempts", "1");
        entries.putAll(extra);
        return entries;
    }

    public static class ConcurrentModule extends ConfigurationTestModule {
        public ConcurrentModule() {
            super(speculativeSettings(Map.of()));
        }
    }

    public static class DisabledModule extends ConfigurationTestModule {
        public DisabledModule() {
            super(speculativeSettings(Map.of("yca.compaction.speculative.policy", "off")));
        }
    }

    public static class InvalidPolicyModule extends ConfigurationTestModule {
        public InvalidPolicyModule() {
            super(speculativeSettings(Map.of("yca.compaction.speculative.policy", "unknown")));
        }
    }

    public static class InvalidPercentModule extends ConfigurationTestModule {
        public InvalidPercentModule() {
            super(speculativeSettings(Map.of("yca.compaction.speculative.startPercent", "100")));
        }
    }
}
