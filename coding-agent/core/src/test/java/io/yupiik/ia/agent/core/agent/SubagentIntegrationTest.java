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

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.tools.builtin.CheckSubagentsTool;
import io.yupiik.ia.agent.core.tools.builtin.SpawnSubagentTool;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class SubagentIntegrationTest {
    @TempDir
    private static Path testWorkdir;

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @TempDir
    Path storageHome;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
        this.mapper = mapper;
        this.configuration = withStorageHome(configuration, storageHome.toString());
    }

    private final List<AgentEvent> events = new ArrayList<>();

    /**
     * Waits for a {@link AgentEvent.SubagentCompleted} to be published and returns the snapshot.
     * The subagent runner completes the state (which is what {@code awaitResult()} waits for) <b>before</b> emitting the
     * event, so asserting on the collected events right after the result is visible is a race.
     */
    private List<AgentEvent.SubagentCompleted> awaitCompletions(final long timeout, final TimeUnit unit)
            throws InterruptedException {
        final var deadline = System.nanoTime() + unit.toNanos(timeout);
        List<AgentEvent.SubagentCompleted> completions;
        do {
            synchronized (events) {
                completions = events.stream()
                        .filter(AgentEvent.SubagentCompleted.class::isInstance)
                        .map(AgentEvent.SubagentCompleted.class::cast)
                        .toList();
            }
            if (!completions.isEmpty()) {
                return completions;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        return completions;
    }

    private List<AgentEvent> snapshot() {
        synchronized (events) {
            return List.copyOf(events);
        }
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

    private ToolExecutionService directExecutions() {
        return new ToolExecutionService(null, null, null, null, null, null) {
            @Override
            public ToolResult execute(
                    final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                return tool.execute(args, context);
            }
        };
    }

    private ToolRegistry barrierRegistry(final CyclicBarrier barrier, final String... names) {
        return new ToolRegistry(null) {
            @Override
            public List<EmbeddedTool> tools() {
                return Arrays.stream(names).map(this::tool).toList();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                if (!List.of(names).contains(name)) {
                    return null;
                }
                return new EmbeddedTool() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String description(final Path workdir) {
                        return "test tool";
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.READ;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public boolean readonly() {
                        return true;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        try {
                            barrier.await(250, TimeUnit.MILLISECONDS);
                        } catch (final Exception e) {
                            return ToolResult.error("not concurrent: " + e);
                        }
                        return ToolResult.ok("done-" + name.substring(name.length() - 1));
                    }
                };
            }
        };
    }

    private ToolRegistry cancellableToolRegistry(final CountDownLatch releaseLatch, final String... names) {
        return new ToolRegistry(null) {
            @Override
            public List<EmbeddedTool> tools() {
                return Arrays.stream(names).map(this::tool).toList();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                if (!List.of(names).contains(name)) {
                    return null;
                }
                return new EmbeddedTool() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String description(final Path workdir) {
                        return "test tool";
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.READ;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public boolean readonly() {
                        return true;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        try {
                            releaseLatch.await(10, TimeUnit.SECONDS);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return ToolResult.error("cancelled");
                        } catch (final Exception e) {
                            return ToolResult.error("not concurrent: " + e);
                        }
                        return ToolResult.ok("done-" + name.substring(name.length() - 1));
                    }
                };
            }
        };
    }

    private ToolRegistry emptyToolRegistry() {
        return new ToolRegistry(null) {
            @Override
            public List<EmbeddedTool> tools() {
                return List.of();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                return null;
            }
        };
    }

    private ChatClient scriptedClient(final Deque<ScriptedReply> replies, final List<List<Message>> requests) {
        return new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled)
                    throws IOException {
                final var reply = replies.poll();
                if (reply == null) {
                    throw new IllegalStateException("no scripted reply left");
                }
                requests.add(List.copyOf(request.messages()));
                if (reply.error() != null) {
                    throw reply.error();
                }
                if (reply.ioError() != null) {
                    throw reply.ioError();
                }
                if (reply.finishReason() != null) {
                    onDelta.accept(new End(reply.finishReason(), null));
                }
                return reply.toMessage();
            }
        };
    }

    private record ScriptedReply(
            String content,
            String finishReason,
            RuntimeException error,
            IOException ioError,
            List<ToolCall> toolCalls) {
        ScriptedReply(final String content, final String finishReason) {
            this(content, finishReason, null, null, null);
        }

        ScriptedReply(final String content, final String finishReason, final RuntimeException error) {
            this(content, finishReason, error, null, null);
        }

        ScriptedReply(final IOException ioError) {
            this(null, null, null, ioError, null);
        }

        static ScriptedReply toolCall(final String toolName, final String args) {
            return new ScriptedReply(
                    null,
                    "tool_calls",
                    null,
                    null,
                    List.of(new ToolCall("tc-" + toolName, "function", new ToolCall.FunctionCall(toolName, args), 0)));
        }

        Message toMessage() {
            return new Message("assistant", content, null, toolCalls, null, null);
        }
    }

    private static final class TestSubagentRunner extends SubagentRunner {
        private final CountDownLatch startLatch = new CountDownLatch(1);
        private volatile SubagentRegistry.SubagentState lastState;

        TestSubagentRunner(final SubagentRunner delegate) {
            super(
                    delegate.chatClient,
                    delegate.toolRegistry,
                    delegate.toolExecutionService,
                    delegate.configuration,
                    delegate.contextWindowCache,
                    delegate.memoryStore,
                    delegate.emitter,
                    delegate.mapper,
                    delegate.structuralCodeGraph,
                    delegate.providerManager,
                    delegate.agentModelResolver,
                    delegate.agentProvider,
                    delegate.todoPromptResolver,
                    delegate.mavenRepositoryKnowledge,
                    delegate.clock,
                    delegate.executor,
                    delegate.subagentRegistry,
                    delegate.precisionDurationMarker,
                    delegate.backoffSleeper,
                    null,
                    delegate.quotaStore);
        }

        @Override
        public void runAsync(
                final SubagentRegistry.SubagentState state,
                final SessionStore sessionStore,
                final SnapshotStore snapshotStore,
                final TodoStore todoStore,
                final ToolContext parentContext,
                final BooleanSupplier cancelled) {
            lastState = state;
            startLatch.countDown();
            super.runAsync(state, sessionStore, snapshotStore, todoStore, parentContext, cancelled);
        }

        void awaitStart() throws InterruptedException {
            assertTrue(startLatch.await(5, TimeUnit.SECONDS));
        }

        void awaitCompletion(final SubagentRegistry.SubagentState state) throws InterruptedException {
            state.awaitResult();
        }

        SubagentRegistry.SubagentState lastState() {
            return lastState;
        }
    }

    private SubagentRegistry newRegistry(final CodingAgentConfiguration cfg, final int providerMaxConcurrent) {
        return new SubagentRegistry(cfg, providerManager(providerMaxConcurrent));
    }

    private TestSubagentRunner newRunner(
            final ChatClient client,
            final ToolRegistry registry,
            final SubagentRegistry reg,
            final CodingAgentConfiguration cfg) {
        return newRunner(client, registry, reg, cfg, EXECUTOR);
    }

    private TestSubagentRunner newRunner(
            final ChatClient client,
            final ToolRegistry registry,
            final SubagentRegistry reg,
            final CodingAgentConfiguration cfg,
            final ExecutorService executor) {
        final var mockUsageTracker = new UsageTracker() {
            @Override
            public void record(final Usage usage) {}

            @Override
            public int estimateContext(final List<Message> messages, final int extraToolTokens) {
                return 100;
            }

            @Override
            public double contextPercent(final int estimatedTokens) {
                return 1;
            }

            @Override
            public boolean lastResolutionWasAuto() {
                return false;
            }

            @Override
            public int rememberShrinkOnOverflow() {
                return 1000;
            }
        };
        final var mockContextManager = new ContextManager() {
            @Override
            public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }

            @Override
            public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }
        };

        final var baseRunner = new SubagentRunner(
                client,
                registry,
                directExecutions(),
                cfg,
                null,
                null,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        synchronized (events) {
                            if (event instanceof AgentEvent agentEvent) {
                                events.add(agentEvent);
                            }
                        }
                    }
                },
                mapper,
                new StructuralCodeGraph() {
                    @Override
                    public void ensureReadyAsync(final Path root) {}
                },
                new ProviderManager(cfg, mapper) {
                    @Override
                    public ResolvedModel resolveDefault() {
                        return new ResolvedModel(null, "test-model");
                    }

                    @Override
                    public ProviderConfiguration current() {
                        return null;
                    }
                },
                null, // agent model resolver (falls back to the provider-manager-backed default)
                null,
                new TodoPromptResolver(),
                null,
                Clock.systemUTC(),
                executor,
                reg,
                new PrecisionDurationMarkerProvider(),
                new BackoffSleeper(),
                null, // secret redactor
                null); // quota store
        return new TestSubagentRunner(baseRunner);
    }

    @AfterAll
    static void closeExecutor() {
        EXECUTOR.shutdownNow();
    }

    private CodingAgentConfiguration configuration;

    @Nested
    @FusionSupport(modules = RejectedSchedulingCompletesTheChildAndReleasesCapacityExactlyOnceModule.class)
    class RejectedSchedulingCompletesTheChildAndReleasesCapacityExactlyOnceTests {
        @TempDir
        static Path root;

        @Test
        void rejectedSchedulingCompletesTheChildAndReleasesCapacityExactlyOnce() throws Exception {
            final var cfg = configuration;
            final var reg = newRegistry(cfg, 3);
            final var rejectingExecutor = Executors.newSingleThreadExecutor();
            rejectingExecutor.shutdown();
            final var runner = newRunner(
                    scriptedClient(new ArrayDeque<>(), new ArrayList<>()),
                    emptyToolRegistry(),
                    reg,
                    cfg,
                    rejectingExecutor);

            final var rejected = reg.start("parent", "rejected task").orElseThrow();
            runner.runAsync(
                    rejected,
                    noopStore(),
                    noopSnapshotStore(),
                    noopTodoStore(),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)),
                    () -> false);
            rejected.awaitResult();
            assertEquals(
                    SubagentRegistry.SubagentResult.Status.FAILED,
                    rejected.result().status());
            assertEquals(0, reg.providerActive("default"), "the rejected child released its provider slot");

            final var replacement = reg.start("parent", "replacement").orElseThrow();
            reg.complete(rejected.id(), SubagentRegistry.SubagentResult.error("duplicate completion", ""));
            assertEquals(
                    1, reg.providerActive("default"), "a duplicate completion must not release another child's permit");
            reg.cancel(replacement.id());
            assertEquals(0, reg.providerActive("default"));
        }

        @Test
        void thirdSubagentRejectedWhenAtLimit() {
            final var cfg = configuration;
            final var reg = newRegistry(cfg, 3); // provider max 3 -> N-1 = 2, session cap = 1
            final var budget = new SubagentBudget(1);

            assertTrue(
                    reg.start(new AgentRunSpec("parent", "t1", null, null, null, null, null, null), "default", budget)
                            .admitted());
            assertFalse(
                    reg.start(new AgentRunSpec("parent", "t2", null, null, null, null, null, null), "default", budget)
                            .admitted(),
                    "second should be rejected at limit");
        }
    }

    public static class RejectedSchedulingCompletesTheChildAndReleasesCapacityExactlyOnceModule
            extends ConfigurationTestModule {
        public RejectedSchedulingCompletesTheChildAndReleasesCapacityExactlyOnceModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "yca.storageHome",
                            RejectedSchedulingCompletesTheChildAndReleasesCapacityExactlyOnceTests.root.toString()),
                    Map.entry("yca.subagents.enabled", "true"),
                    Map.entry("yca.subagents.maxConcurrent", "1"),
                    Map.entry("yca.llm.maxConcurrent", "4"),
                    Map.entry("yca.tools.parallelReadonly", "true")));
        }
    }

    @Nested
    @FusionSupport(modules = NestedRunnerSharesBudgetRestrictsToolsAndDeliversToImmediateParentModule.class)
    class NestedRunnerSharesBudgetRestrictsToolsAndDeliversToImmediateParentTests {
        @TempDir
        static Path root;

        @Test
        void nestedRunnerSharesBudgetRestrictsToolsAndDeliversToImmediateParent() throws Exception {
            final var cfg = configuration;
            final var manager = new ProviderManager(cfg, mapper);
            final var reg = new SubagentRegistry(cfg, manager);
            final var tools = new ArrayList<EmbeddedTool>();
            final var toolRegistry = new ToolRegistry(null) {
                @Override
                public List<EmbeddedTool> tools() {
                    return List.copyOf(tools);
                }

                @Override
                public EmbeddedTool tool(final String name) {
                    return tools.stream()
                            .filter(tool -> tool.name().equals(name))
                            .findFirst()
                            .orElse(null);
                }
            };
            final var rounds = new AtomicInteger();
            final var budget = new SubagentBudget(2);
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauses,
                        final Consumer<LlmStreamEvent> deltas,
                        final BooleanSupplier cancelled) {
                    final var round = rounds.incrementAndGet();
                    final var names = request.tools().stream()
                            .map(tool -> tool.function().name())
                            .collect(toSet());
                    if (round == 1) {
                        assertEquals(Set.of("spawn_subagent", "read"), names);
                        assertEquals(1, budget.available());
                        return ScriptedReply.toolCall(
                                        "spawn_subagent",
                                        "{\"task\":\"nested task\",\"model\":\"alt\",\"tools\":[\"read\"]}")
                                .toMessage();
                    }
                    if (round == 2) {
                        assertEquals(Set.of("read"), names);
                        assertEquals(0, budget.available());
                        assertEquals("$default/alt", request.settings().model());
                        return new ScriptedReply("grandchild summary", "stop").toMessage();
                    }
                    assertEquals(3, round);
                    assertTrue(request.messages().stream()
                            .anyMatch(message -> message.content() instanceof final String text
                                    && text.contains("<subagent ")
                                    && text.contains("grandchild summary")));
                    assertEquals(1, budget.available());
                    return new ScriptedReply("child summary", "stop").toMessage();
                }
            };
            // Inline execution makes delivery ordering deterministic while exercising both real child loops.
            try (final var executor = new AbstractExecutorService() {
                private boolean closed;

                @Override
                public void shutdown() {
                    closed = true;
                }

                @Override
                public List<Runnable> shutdownNow() {
                    shutdown();
                    return List.of();
                }

                @Override
                public boolean isShutdown() {
                    return closed;
                }

                @Override
                public boolean isTerminated() {
                    return closed;
                }

                @Override
                public boolean awaitTermination(final long timeout, final TimeUnit unit) {
                    return closed;
                }

                @Override
                public void execute(final Runnable command) {
                    command.run();
                }
            }) {
                final var runner = newRunner(client, toolRegistry, reg, cfg, executor);
                final var spawn = new SpawnSubagentTool(reg, runner, noopStore(), noopSnapshotStore(), cfg, manager);
                tools.add(spawn);
                tools.add(barrierRegistry(new CyclicBarrier(1), "read").tool("read"));
                final var root = "nested-integration-" + UUID.randomUUID();
                final var context = new ToolContext(
                        root,
                        null,
                        null,
                        new PermissionService(cfg, testWorkdir),
                        null,
                        noopTodoStore(),
                        null,
                        null,
                        null,
                        () -> false,
                        ChatSettings.EMPTY.withProviderId("default").withModel("main"),
                        null,
                        testWorkdir,
                        new DisabledSandbox(testWorkdir),
                        0,
                        Set.of("spawn_subagent", "read"),
                        budget,
                        toolRegistry);
                final var started = spawn.execute(Map.of("task", "child task"), context);
                assertTrue(started.success(), started.output());
                final var child = reg.listAll(root).getFirst();
                assertTrue(child.result().success(), String.valueOf(child.result()));
                assertEquals(3, rounds.get());
                assertEquals(2, budget.available());
                final var deliveries = SubagentDeliveryBus.get().drain(root);
                assertEquals(1, deliveries.size());
                assertEquals("child summary", deliveries.getFirst().summary());
                assertTrue(
                        SubagentDeliveryBus.get().drain(child.childSessionId()).isEmpty());
            }
        }
    }

    public static class NestedRunnerSharesBudgetRestrictsToolsAndDeliversToImmediateParentModule
            extends ConfigurationTestModule {
        public NestedRunnerSharesBudgetRestrictsToolsAndDeliversToImmediateParentModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "yca.storageHome",
                            NestedRunnerSharesBudgetRestrictsToolsAndDeliversToImmediateParentTests.root.toString()),
                    Map.entry("yca.subagents.enabled", "true"),
                    Map.entry("yca.subagents.maxConcurrent", "2"),
                    Map.entry("yca.llm.maxConcurrent", "4"),
                    Map.entry("yca.tools.parallelReadonly", "true"),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.model", "main"),
                    Map.entry("yca.provider.providers.0.subagents.maxDepth", "2"),
                    Map.entry("yca.subagents.allowModelOverride", "true"),
                    Map.entry("yca.subagents.includes", "$default/alt")));
        }
    }

    @Nested
    @FusionSupport(modules = QueuedChildUsesAdmittedSettingsInsteadOfParentSettingsAtExecutionModule.class)
    class QueuedChildUsesAdmittedSettingsInsteadOfParentSettingsAtExecutionTests {
        @TempDir
        static Path root;

        @Test
        @Timeout(10)
        void queuedChildUsesAdmittedSettingsInsteadOfParentSettingsAtExecution() throws Exception {
            final var cfg = configuration;
            final var reg = newRegistry(cfg, 4);
            final var captured = new AtomicReference<ChatCompletionRequest>();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauses,
                        final Consumer<LlmStreamEvent> deltas,
                        final BooleanSupplier cancelled) {
                    captured.set(request);
                    deltas.accept(new End("stop", null));
                    return new Message("assistant", "done", null, null, null, null);
                }
            };
            final var release = new CountDownLatch(1);
            try (final var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> {
                    try {
                        release.await();
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                try {
                    final var admitted = new EffectiveAgentModel(
                            new ProviderManager.ResolvedModel(providerManager(4).provider("default"), "approved"),
                            new ChatSettings("default", "$default/approved", "balanced", null),
                            "approved",
                            "$default/approved");
                    final var state = reg.start(new AgentRunSpec(
                                    "parent", "task", null, null, null, null, null, null, "default", null, admitted))
                            .orElseThrow();
                    final var runner = newRunner(client, emptyToolRegistry(), reg, cfg, executor);
                    // The parent now has a different route; the raw spawn model is deliberately null.
                    final var switchedContext = new ToolContext(
                            "parent",
                            null,
                            null,
                            null,
                            null,
                            noopTodoStore(),
                            null,
                            null,
                            null,
                            () -> false,
                            ChatSettings.EMPTY.withProviderId("other").withModel("redirect"),
                            null,
                            testWorkdir,
                            new DisabledSandbox(testWorkdir));
                    runner.runAsync(
                            state, noopStore(), noopSnapshotStore(), noopTodoStore(), switchedContext, () -> false);
                    assertEquals(null, captured.get(), "queued children make no inference requests");
                    release.countDown();
                    final var result = state.awaitResult();
                    assertTrue(result.success(), result.error());
                    assertEquals("$default/approved", captured.get().model());
                    assertEquals("default", captured.get().settings().providerId());
                    assertEquals("balanced", captured.get().settings().variant());
                    assertEquals(
                            new SubagentRegistry.TranscriptIdentity("default", "approved"),
                            reg.identityOf(state.id()).orElseThrow());
                } finally {
                    release.countDown();
                }
            }
        }

        @Test
        void subagentRunsToCompletionAndResultCollected() throws Exception {
            final var cfg = configuration;
            final var reg = newRegistry(cfg, 4);
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("subagent result", "stop")));
            final var runner = newRunner(scriptedClient(replies, requests), emptyToolRegistry(), reg, cfg);
            final var checkTool = new CheckSubagentsTool(reg, mapper);

            final var state = reg.start("parent", "task").orElseThrow();
            runner.runAsync(
                    state,
                    noopStore(),
                    noopSnapshotStore(),
                    noopTodoStore(),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)),
                    () -> false);
            runner.awaitCompletion(state);

            final var collect = checkTool.execute(
                    Map.of("action", "collect", "subagent_id", state.id()),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)));
            assertTrue(collect.success(), collect.output());
            assertTrue(collect.output().contains("subagent result"));
            assertTrue(collect.output().contains("\"completed\":true"));

            // State removed after collect
            assertTrue(reg.get(state.id()).isEmpty());
            // Semaphore released
            assertTrue(reg.start("parent", "t2").isPresent());
        }

        @Test
        void subagentCancellationStopsExecution() throws Exception {
            final var cfg = configuration;
            final var reg = newRegistry(cfg, 4);
            final var releaseLatch = new CountDownLatch(1);
            final var toolReg = cancellableToolRegistry(releaseLatch, "slow_read");

            final var replies = new ArrayDeque<ScriptedReply>();
            replies.add(ScriptedReply.toolCall("slow_read", "{}"));
            replies.add(new ScriptedReply("never reached", "stop"));
            final var requests = new ArrayList<List<Message>>();

            final var runner = newRunner(scriptedClient(replies, requests), toolReg, reg, cfg);
            final var checkTool = new CheckSubagentsTool(reg, mapper);

            final var state = reg.start("parent", "long task").orElseThrow();
            runner.runAsync(
                    state,
                    noopStore(),
                    noopSnapshotStore(),
                    noopTodoStore(),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)),
                    () -> false);
            runner.awaitStart();

            // Tool is now waiting on releaseLatch - cancel it
            final var cancel = checkTool.execute(
                    Map.of("action", "cancel", "subagent_id", state.id()),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)));
            assertTrue(cancel.success(), cancel.output());
            assertTrue(cancel.output().contains("\"cancelled\":true"));

            // Release the latch so the tool can complete (and the subagent can process cancellation)
            releaseLatch.countDown();

            // Semaphore released
            assertTrue(reg.start("parent", "t2").isPresent());
        }

        @Test
        void subagentErrorPropagation() throws Exception {
            final var cfg = configuration;
            final var reg = newRegistry(cfg, 4);
            final var replies = new ArrayDeque<>(
                    List.of(new ScriptedReply("partial", "tool_calls", new RuntimeException("boom!"))));
            final var requests = new ArrayList<List<Message>>();

            final var runner = newRunner(scriptedClient(replies, requests), emptyToolRegistry(), reg, cfg);
            final var checkTool = new CheckSubagentsTool(reg, mapper);

            final var state = reg.start("parent", "failing task").orElseThrow();
            runner.runAsync(
                    state,
                    noopStore(),
                    noopSnapshotStore(),
                    noopTodoStore(),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)),
                    () -> false);
            runner.awaitCompletion(state);

            final var collect = checkTool.execute(
                    Map.of("action", "collect", "subagent_id", state.id()),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)));
            assertFalse(collect.success(), collect.output());
            assertTrue(collect.output().contains("error"));
        }

        @Test
        void parentCancelledStopsSubagent() throws Exception {
            final var cfg = configuration;
            final var reg = newRegistry(cfg, 4);
            final var releaseLatch = new CountDownLatch(1);
            final var toolReg = cancellableToolRegistry(releaseLatch, "slow_read");

            final var replies = new ArrayDeque<ScriptedReply>();
            replies.add(ScriptedReply.toolCall("slow_read", "{}"));
            replies.add(new ScriptedReply("never reached", "stop"));
            final var requests = new ArrayList<List<Message>>();

            final var runner = newRunner(scriptedClient(replies, requests), toolReg, reg, cfg);
            final var checkTool = new CheckSubagentsTool(reg, mapper);

            final var cancelled = new AtomicBoolean(false);
            final var state = reg.start("parent", "task").orElseThrow();
            runner.runAsync(
                    state,
                    noopStore(),
                    noopSnapshotStore(),
                    noopTodoStore(),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)),
                    cancelled::get);
            runner.awaitStart();

            // Tool is waiting on releaseLatch - parent cancels
            cancelled.set(true);

            // Release the latch so the in-flight tool returns right away: the cancellation probe is
            // only read at iteration boundaries, so the loop cannot make progress until this tool
            // completed - without the release the tool would block for its whole 10s timeout and the
            // test would assert the same thing 10s later
            releaseLatch.countDown();

            // Wait for sub-agent to process cancellation
            state.awaitResult();

            // Sub-agent should have been cancelled
            final var collect = checkTool.execute(
                    Map.of("action", "collect", "subagent_id", state.id()),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)));
            assertFalse(collect.success(), collect.output());

            // ... and reported as a cancellation, not as a failure: a cancelled child must never
            // render as a red "Subagent failed" card in the parent transcript
            final var completions = awaitCompletions(10, TimeUnit.SECONDS);
            assertEquals(1, completions.size(), "one completion event expected, got: " + snapshot());
            final var completion = completions.getFirst();
            assertTrue(completion.cancelled(), "a cancelled sub-agent must be flagged as cancelled: " + completion);
            assertFalse(completion.success(), "a cancelled sub-agent did not succeed");
        }

        @Test
        void subagentInheritsSystemPromptAndTools() throws Exception {
            final var cfg = configuration;
            final var reg = newRegistry(cfg, 4);
            final var toolReg = new ToolRegistry(null) {
                @Override
                public List<EmbeddedTool> tools() {
                    return List.of(tool("read"));
                }

                @Override
                public EmbeddedTool tool(final String name) {
                    if ("read".equals(name)) {
                        return new EmbeddedTool() {
                            @Override
                            public String name() {
                                return "read";
                            }

                            @Override
                            public String description(final Path workdir) {
                                return "read";
                            }

                            @Override
                            public ToolCategory category() {
                                return ToolCategory.READ;
                            }

                            @Override
                            public Map<String, Object> schema() {
                                return Map.of();
                            }

                            @Override
                            public boolean readonly() {
                                return true;
                            }

                            @Override
                            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                                return ToolResult.ok("file content");
                            }
                        };
                    }
                    return null;
                }
            };

            final var replies = new ArrayDeque<ScriptedReply>();
            replies.add(ScriptedReply.toolCall("read", "{}"));
            replies.add(new ScriptedReply("done", "stop"));
            final var requests = new ArrayList<List<Message>>();

            final var runner = newRunner(scriptedClient(replies, requests), toolReg, reg, cfg);
            final var checkTool = new CheckSubagentsTool(reg, mapper);

            final var state = reg.start("parent", "read something").orElseThrow();
            runner.runAsync(
                    state,
                    noopStore(),
                    noopSnapshotStore(),
                    noopTodoStore(),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)),
                    () -> false);
            runner.awaitCompletion(state);

            final var collect = checkTool.execute(
                    Map.of("action", "collect", "subagent_id", state.id()),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)));
            assertTrue(collect.success(), collect.output());
        }

        @Test
        void collectWithoutPollWorks() throws Exception {
            final var cfg = configuration;
            final var reg = newRegistry(cfg, 4);
            final var replies = new ArrayDeque<>(List.of(new ScriptedReply("direct collect", "stop")));
            final var requests = new ArrayList<List<Message>>();

            final var runner = newRunner(scriptedClient(replies, requests), emptyToolRegistry(), reg, cfg);
            final var checkTool = new CheckSubagentsTool(reg, mapper);

            final var state = reg.start("parent", "task").orElseThrow();
            runner.runAsync(
                    state,
                    noopStore(),
                    noopSnapshotStore(),
                    noopTodoStore(),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)),
                    () -> false);
            runner.awaitCompletion(state);

            // Collect directly without polling first
            final var collect = checkTool.execute(
                    Map.of("action", "collect", "subagent_id", state.id()),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)));
            assertTrue(collect.success(), collect.output());
            assertTrue(collect.output().contains("direct collect"));
        }
    }

    public static class QueuedChildUsesAdmittedSettingsInsteadOfParentSettingsAtExecutionModule
            extends ConfigurationTestModule {
        public QueuedChildUsesAdmittedSettingsInsteadOfParentSettingsAtExecutionModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "yca.storageHome",
                            QueuedChildUsesAdmittedSettingsInsteadOfParentSettingsAtExecutionTests.root.toString()),
                    Map.entry("yca.subagents.enabled", "true"),
                    Map.entry("yca.subagents.maxConcurrent", "2"),
                    Map.entry("yca.llm.maxConcurrent", "4"),
                    Map.entry("yca.tools.parallelReadonly", "true")));
        }
    }

    @Nested
    @FusionSupport(modules = MultipleSubagentsRunInParallelWithinLimitModule.class)
    class MultipleSubagentsRunInParallelWithinLimitTests {
        @TempDir
        static Path root;

        @Test
        void multipleSubagentsRunInParallelWithinLimit() throws Exception {
            final var cfg = configuration;
            final var reg = newRegistry(cfg, 4); // provider max 4 -> actualMax = min(3, 2) = 2
            final var barrier = new CyclicBarrier(2);
            final var toolReg = barrierRegistry(barrier, "read_a", "read_b");

            final var replies1 = new ArrayDeque<ScriptedReply>();
            replies1.add(ScriptedReply.toolCall("read_a", "{}"));
            replies1.add(new ScriptedReply("done1", "stop"));
            final var replies2 = new ArrayDeque<ScriptedReply>();
            replies2.add(ScriptedReply.toolCall("read_b", "{}"));
            replies2.add(new ScriptedReply("done2", "stop"));

            final var req1 = new ArrayList<List<Message>>();
            final var req2 = new ArrayList<List<Message>>();
            final var runner1 = newRunner(scriptedClient(replies1, req1), toolReg, reg, cfg);
            final var runner2 = newRunner(scriptedClient(replies2, req2), toolReg, reg, cfg);

            final var s1 = reg.start("parent", "t1").orElseThrow();
            final var s2 = reg.start("parent", "t2").orElseThrow();
            runner1.runAsync(
                    s1,
                    noopStore(),
                    noopSnapshotStore(),
                    noopTodoStore(),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)),
                    () -> false);
            runner2.runAsync(
                    s2,
                    noopStore(),
                    noopSnapshotStore(),
                    noopTodoStore(),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)),
                    () -> false);

            // Wait for both tools to execute concurrently (barrier)
            assertTrue(barrier.await(3, TimeUnit.SECONDS) >= 0, "both tools should execute concurrently");

            runner1.awaitCompletion(s1);
            runner2.awaitCompletion(s2);

            final var checkTool = new CheckSubagentsTool(reg, mapper);
            final var c1 = checkTool.execute(
                    Map.of("action", "collect", "subagent_id", s1.id()),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)));
            final var c2 = checkTool.execute(
                    Map.of("action", "collect", "subagent_id", s2.id()),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)));
            assertTrue(c1.success(), c1.output());
            assertTrue(c2.success(), c2.output());
        }
    }

    public static class MultipleSubagentsRunInParallelWithinLimitModule extends ConfigurationTestModule {
        public MultipleSubagentsRunInParallelWithinLimitModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", MultipleSubagentsRunInParallelWithinLimitTests.root.toString()),
                    Map.entry("yca.subagents.enabled", "true"),
                    Map.entry("yca.subagents.maxConcurrent", "2"),
                    Map.entry("yca.llm.maxConcurrent", "4"),
                    Map.entry("yca.tools.parallelReadonly", "true")));
        }
    }

    private static CodingAgentConfiguration withStorageHome(
            final CodingAgentConfiguration baseline, final String storageHome) {
        return new CodingAgentConfiguration(
                storageHome,
                baseline.home(),
                baseline.workingDir(),
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
