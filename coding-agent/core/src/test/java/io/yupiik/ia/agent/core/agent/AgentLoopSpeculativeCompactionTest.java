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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
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
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
@Timeout(30)
class AgentLoopSpeculativeCompactionTest {
    @TempDir
    private static Path testWorkdir;

    @Nested
    @FusionSupport(modules = DefaultPolicyModule.class)
    class DefaultPolicy {
        @TempDir
        static Path storageHome;

        @Test
        void defaultPolicyDoesNotIssueBackgroundRequests(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration config) throws Exception {
            final var summaries = new AtomicInteger();
            final var requests = new ArrayList<List<Message>>();
            final var client = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    summaries.incrementAndGet();
                    return new ChatResult(Message.assistant("unexpected digest", null), Usage.EMPTY);
                }

                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final Consumer<LlmStreamEvent> delta,
                        final BooleanSupplier cancelled) {
                    requests.add(List.copyOf(request.messages()));
                    delta.accept(new LlmStreamEvent.End("stop", null));
                    return Message.assistant("done", null);
                }
            };
            final var events = new CopyOnWriteArrayList<AgentEvent>();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var loop = loop(config, mapper, client, events, executor);
                assertEquals("done", loop.run("continue", null, "s1", history(), () -> false));
                assertEquals("done", loop.run("another turn", null, "s1", history(), () -> false));
            }
            assertEquals(2, requests.size());
            assertTrue(contains(requests.getFirst(), "old answer 0"));
            assertEquals(0, summaries.get(), "default-off must not issue background summary requests");
            assertFalse(events.stream().anyMatch(AgentEvent.Compacted.class::isInstance));
        }
    }

    @Nested
    @FusionSupport(modules = ConcurrentPolicyModule.class)
    class ConcurrentPolicy {
        @TempDir
        static Path storageHome;

        @Test
        void cancelledRunDiscardsWorkerAndNextRunStartsWithFreshHistory(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration config) throws Exception {
            final var started = new CountDownLatch(1);
            final var workerStopped = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var summaries = new AtomicInteger();
            final var cancelled = new AtomicBoolean();
            final var requests = new ArrayList<List<Message>>();
            final var client = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier probe)
                        throws InterruptedException {
                    final var call = summaries.incrementAndGet();
                    if (call == 1) {
                        started.countDown();
                        try {
                            await(release);
                        } finally {
                            workerStopped.countDown();
                        }
                    }
                    return new ChatResult(Message.assistant("digest from run " + call, null), Usage.EMPTY);
                }

                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final Consumer<LlmStreamEvent> delta,
                        final BooleanSupplier probe)
                        throws IOException, InterruptedException {
                    requests.add(List.copyOf(request.messages()));
                    if (requests.size() == 1) {
                        await(started);
                        assertEquals(1, release.getCount(), "foreground overlaps a blocked summary request");
                        cancelled.set(true);
                        throw new IOException("Cancelled");
                    }
                    delta.accept(new LlmStreamEvent.End("stop", null));
                    return Message.assistant("fresh run done", null);
                }
            };
            final var events = new CopyOnWriteArrayList<AgentEvent>();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    final var loop = loop(config, mapper, client, events, executor);
                    assertNull(loop.run("cancel this", null, "s1", history(), cancelled::get));
                    release.countDown();
                    await(workerStopped);
                    assertTrue(events.stream().anyMatch(AgentEvent.TurnCancelled.class::isInstance));
                    cancelled.set(false);
                    assertEquals("fresh run done", loop.run("fresh prompt", null, "s1", null, cancelled::get));
                } finally {
                    release.countDown();
                }
            }
            assertEquals(2, requests.size());
            assertEquals(1, summaries.get(), "the short fresh run must not need another summary");
            assertTrue(contains(requests.getLast(), "fresh prompt"));
            assertFalse(contains(requests.getLast(), "old answer 0"));
            assertFalse(contains(requests.getLast(), "digest from run 1"));
            assertFalse(events.stream().anyMatch(AgentEvent.Compacted.class::isInstance));
        }
    }

    private AgentLoop loop(
            final CodingAgentConfiguration config,
            final JsonMapper mapper,
            final ChatClient client,
            final List<AgentEvent> events,
            final ExecutorService executor) {
        final var providers = new ProviderManager(config, mapper);
        final var usage = new UsageTracker(providers, client, null, Clock.systemUTC());
        final var emitter = new Emitter() {
            @Override
            public <T> void emit(final T event) {
                if (event instanceof AgentEvent agentEvent) {
                    events.add(agentEvent);
                }
            }
        };
        final var manager = new ContextManager(
                config,
                usage,
                new CompactionCoordinator(
                        config,
                        client,
                        usage,
                        providers,
                        emitter,
                        "s1",
                        new BackoffSleeper(),
                        (config).resolveWorkingDir()));
        return new AgentLoop(
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
                null,
                (config).resolveWorkingDir(),
                new DisabledSandbox((config).resolveWorkingDir()));
    }

    private static List<Message> history() {
        final var messages = new ArrayList<Message>();
        for (var i = 0; i < 12; i++) {
            messages.add(Message.user("old question " + i));
            messages.add(Message.assistant("old answer " + i + " " + "x".repeat(4_000), null));
        }
        return messages;
    }

    private static boolean contains(final List<Message> messages, final String text) {
        return messages.stream().anyMatch(m -> String.valueOf(m.content()).contains(text));
    }

    private static void await(final CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "timed out waiting for the scripted request handshake");
    }

    public static class DefaultPolicyModule extends ConfigurationTestModule {
        public DefaultPolicyModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var entries = new HashMap<String, String>();
            entries.put("yca.storageHome", DefaultPolicy.storageHome.toString());
            entries.put("yca.workingDir", DefaultPolicy.storageHome.toString());
            entries.put("yca.provider.providers.length", "1");
            entries.put("yca.provider.providers.0.id", "default");
            entries.put("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions");
            entries.put("yca.provider.providers.0.apiKey", "test-key");
            entries.put("yca.provider.providers.0.model", "test-model");
            entries.put("yca.provider.providers.0.contextWindow", "128000");
            entries.put("yca.compaction.thresholdPercent", "20");
            entries.put("yca.compaction.tail.budgetTokens", "1");
            entries.put("yca.compaction.summarizer.chunkChars", "200000");
            entries.put("yca.compaction.speculative.startPercent", "1");
            entries.put("yca.pruning.enabled", "false");
            entries.put("yca.pruning.keepRecent", "2");
            entries.put("yca.llm.maxIterations", "3");
            return entries;
        }
    }

    public static class ConcurrentPolicyModule extends ConfigurationTestModule {
        public ConcurrentPolicyModule() {
            super(values());
        }

        private static Map<String, String> values() {
            final var entries = new HashMap<String, String>();
            entries.put("yca.storageHome", ConcurrentPolicy.storageHome.toString());
            entries.put("yca.workingDir", ConcurrentPolicy.storageHome.toString());
            entries.put("yca.provider.providers.length", "1");
            entries.put("yca.provider.providers.0.id", "default");
            entries.put("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions");
            entries.put("yca.provider.providers.0.apiKey", "test-key");
            entries.put("yca.provider.providers.0.model", "test-model");
            entries.put("yca.provider.providers.0.contextWindow", "128000");
            entries.put("yca.compaction.thresholdPercent", "20");
            entries.put("yca.compaction.tail.budgetTokens", "1");
            entries.put("yca.compaction.summarizer.chunkChars", "200000");
            entries.put("yca.compaction.speculative.startPercent", "1");
            entries.put("yca.pruning.enabled", "false");
            entries.put("yca.pruning.keepRecent", "2");
            entries.put("yca.llm.maxIterations", "3");
            entries.put("yca.compaction.speculative.policy", "concurrent");
            return entries;
        }
    }
}
