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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ai.mcp.client.McpClient;
import io.yupiik.ai.mcp.client.McpServerTool;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.ToolUsePreEvent;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.InMemoryTodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
@Timeout(30)
class SubagentToolOverlayTest {
    private static final Pattern OVERLAY_REQUEST = Pattern.compile("(?m)^[AB]:[0-2]$");

    @TempDir
    Path root;

    private final class Fixture implements AutoCloseable {
        private final Clock clock = Clock.systemUTC();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final CodingAgentConfiguration configuration;
        private final ProviderManager providers;
        private final SubagentRegistry children;
        private final AgentProvider profiles;
        private final SubagentRunner runner;
        private final JsonMapper mapper;
        private final Client a = new Client("A");
        private final Client b = new Client("B");
        private final Client app = new Client("app");
        private final AtomicInteger approvals = new AtomicInteger();
        private final List<ChatCompletionRequest> requests = new CopyOnWriteArrayList<>();
        private final List<ToolContext> contexts = new CopyOnWriteArrayList<>();
        private final List<AgentLoop> loops = new ArrayList<>();
        private final Map<String, ToolRegistry> overlays;
        private final ToolExecutionService execution;
        private final Emitter emitter = new Emitter() {
            @Override
            public <T> void emit(final T event) {
                if (event instanceof final ToolUsePreEvent gate) {
                    final var decision = gate.permissions()
                            .check(gate.toolName(), gate.category(), gate.toolInput(), gate.readonly());
                    if (!decision.allowed()) {
                        gate.result().forbid(decision.reason());
                    }
                }
            }
        };
        private final StructuralCodeGraph graph = new StructuralCodeGraph() {
            @Override
            public void ensureReadyAsync(final Path workdir) {}
        };
        private final ChatClient client = new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> pauses,
                    final Consumer<LlmStreamEvent> deltas,
                    final BooleanSupplier cancelled) {
                requests.add(request);
                final var task = task(request);
                final var previous = request.messages().reversed().stream()
                        .takeWhile(message -> !"user".equals(message.role()) || !task.equals(message.content()))
                        .filter(message -> "tool".equals(message.role()))
                        .count();
                if (previous == 0) {
                    if (childEntered != null && request.sessionId().contains("-subagent-")) {
                        childEntered.countDown();
                        try {
                            assertTrue(releaseChild.await(5, TimeUnit.SECONDS));
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                    if (barrier != null && task.endsWith(":0")) {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                        } catch (final Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    final var names = attack
                            ? List.of("client_lookup", "client_edit", "unusable", "app_only", "client_sensitive")
                            : List.of("client_lookup");
                    return calls(names);
                }
                final var depth = Integer.parseInt(task.substring(2));
                if (!attack && previous == 1 && depth < 2) {
                    return Message.assistant(
                            null,
                            List.of(new ToolCall(
                                    "delegate",
                                    "function",
                                    new ToolCall.FunctionCall(
                                            "delegate", "{\"task\":\"" + task.charAt(0) + ":" + (depth + 1) + "\"}"),
                                    0)));
                }
                return Message.assistant("done", null);
            }
        };
        private volatile CyclicBarrier barrier;
        private volatile CountDownLatch childEntered;
        private volatile CountDownLatch releaseChild;
        private boolean attack;

        private Fixture(final JsonMapper mapper, final CodingAgentConfiguration configuration) {
            this.mapper = mapper;
            this.configuration = configuration;
            providers = new ProviderManager(configuration, mapper);
            children = new SubagentRegistry(configuration, providers);
            profiles = new AgentProvider(configuration) {
                @Override
                public List<AgentConfiguration> agents() {
                    return List.of(
                            new AgentConfiguration("limited", "Read only", null, null, List.of("client_lookup")));
                }
            };
            final EmbeddedTool delegate = new EmbeddedTool() {
                @Override
                public String name() {
                    return "delegate";
                }

                @Override
                public String description(final Path workdir) {
                    return "Test direct runner delegation";
                }

                @Override
                public ToolCategory category() {
                    return ToolCategory.AGENT;
                }

                @Override
                public Map<String, Object> schema() {
                    return Map.of("type", "object");
                }

                @Override
                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                    try {
                        final var state = child(context, String.valueOf(args.get("task")), null, null);
                        return state.result().success()
                                ? ToolResult.ok("delegated")
                                : ToolResult.error(state.result().error());
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return ToolResult.error("cancelled");
                    }
                }
            };
            final var shared = new ToolRegistry(null) {
                @Override
                public List<EmbeddedTool> tools() {
                    return List.of(delegate, new ClientTool("app_only", app, ToolCategory.READ, true, true));
                }
            };
            overlays = Map.of("A", overlay(shared, a), "B", overlay(shared, b));
            execution = new ToolExecutionService(configuration, emitter, List.of()) {
                @Override
                public ToolResult execute(
                        final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                    contexts.add(context);
                    return super.execute(tool, args, context);
                }
            };
            runner = new SubagentRunner(
                    client,
                    shared,
                    execution,
                    configuration,
                    null,
                    null,
                    emitter,
                    mapper,
                    graph,
                    providers,
                    null,
                    profiles,
                    new TodoPromptResolver(),
                    null,
                    clock,
                    executor,
                    children,
                    new PrecisionDurationMarkerProvider(),
                    new BackoffSleeper(),
                    null,
                    null);
        }

        private ToolRegistry overlay(final ToolRegistry shared, final Client session) {
            return shared.withTools(List.of(
                    new ClientTool("client_lookup", session, ToolCategory.MCP, true, true),
                    new ClientTool("client_sensitive", session, ToolCategory.MCP, false, true),
                    new ClientTool("client_edit", session, ToolCategory.EDIT, false, true),
                    new ClientTool("unusable", session, ToolCategory.READ, true, false)));
        }

        private PermissionService permissions() {
            final var permissions = new PermissionService(configuration, emitter, root);
            permissions.setMode(AgentMode.PLAN);
            permissions.setGate(request -> {
                approvals.incrementAndGet();
                return ApprovalGate.Result.DENY;
            });
            return permissions;
        }

        private AgentLoop loop(final String marker) {
            final var permissions = permissions();
            final var todos = new InMemoryTodoStore();
            final var usage = new UsageTracker(providers, client, null, clock);
            final var context = new ContextManager(
                    configuration,
                    usage,
                    new CompactionCoordinator(
                            configuration, client, usage, providers, emitter, marker, new BackoffSleeper(), root));
            final var loop = new AgentLoop(
                    client,
                    overlays.get(marker),
                    execution,
                    configuration,
                    permissions,
                    usage,
                    null,
                    context,
                    new ContextView(configuration.compression()),
                    new InMemorySessionStore(clock),
                    new SystemPromptBuilder(
                            configuration,
                            null,
                            todos,
                            permissions,
                            profiles,
                            providers,
                            new TodoPromptResolver(),
                            null,
                            null,
                            root),
                    null,
                    new StatusReporter(),
                    emitter,
                    mapper,
                    graph,
                    providers,
                    profiles,
                    clock,
                    new SnapshotStore(configuration),
                    todos,
                    executor,
                    null,
                    root,
                    new DisabledSandbox(root));
            loop.updateChatSettings(new ChatSettings("default", "model-" + marker, null, null));
            loops.add(loop);
            return loop;
        }

        private SubagentRegistry.SubagentState child(
                final ToolContext parent, final String task, final String profile, final Set<String> tools)
                throws InterruptedException {
            final var state = children.start(
                            new AgentRunSpec("parent-" + UUID.randomUUID(), task, null, profile, null, 6, tools, null))
                    .orElseThrow();
            runner.runAsync(
                    state,
                    new InMemorySessionStore(clock),
                    new SnapshotStore(configuration),
                    new InMemoryTodoStore(),
                    parent,
                    () -> false);
            state.awaitResult();
            return state;
        }

        private String task(final ChatCompletionRequest request) {
            return request.messages().stream()
                    .filter(message -> "user".equals(message.role()))
                    .map(message -> String.valueOf(message.content()))
                    .map(OVERLAY_REQUEST::matcher)
                    .filter(Matcher::find)
                    .map(Matcher::group)
                    .reduce((first, second) -> second)
                    .orElseThrow();
        }

        private Message calls(final List<String> names) {
            return Message.assistant(
                    null,
                    names.stream()
                            .map(name -> new ToolCall(name, "function", new ToolCall.FunctionCall(name, "{}"), 0))
                            .toList());
        }

        @Override
        public void close() {
            loops.forEach(AgentLoop::close);
            executor.close();
            a.close();
            b.close();
            app.close();
            client.close();
        }
    }

    private record ClientTool(String name, Client client, ToolCategory category, boolean readonly, boolean available)
            implements EmbeddedTool {
        @Override
        public String description(final Path workdir) {
            return "Client " + client.marker;
        }

        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "object", "title", client.marker);
        }

        @Override
        public boolean usable(final Path workdir) {
            return available;
        }

        @Override
        public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
            return ToolResult.ok(client.callTool(name, args));
        }
    }

    private static final class Client implements McpClient {
        private final String marker;
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private boolean closed;

        private Client(final String marker) {
            this.marker = marker;
        }

        @Override
        public void initialize() {}

        @Override
        public List<McpServerTool> listTools() {
            return List.of();
        }

        @Override
        public String callTool(final String name, final Map<String, Object> args) {
            assertFalse(closed, "delegated work must not close its borrowed MCP client");
            calls.add(name);
            return "client:" + marker;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @BeforeEach
    void bindConfiguration(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration =
                withStorageHomeWorkingDir(configuration, root.resolve("storage").toString(), root.toString());
    }

    private CodingAgentConfiguration configuration;

    @Nested
    @FusionSupport(modules = MainChildAndGrandchildKeepTheirSessionOverlayInABAAndConcurrentABModule.class)
    class MainChildAndGrandchildKeepTheirSessionOverlayInABAAndConcurrentABTests {
        @TempDir
        static Path root;

        @Test
        void mainChildAndGrandchildKeepTheirSessionOverlayInABAAndConcurrentAB(final JsonMapper mapper)
                throws Exception {
            try (final var fixture = new Fixture(mapper, configuration)) {
                final var a = fixture.loop("A");
                final var b = fixture.loop("B");
                assertTrue(a.availableToolNames().contains("client_lookup"));
                assertFalse(a.availableToolNames().contains("client_edit"));
                assertFalse(a.availableToolNames().contains("unusable"));
                for (final var loop : List.of(a, b, a)) {
                    final var marker = loop == a ? "A" : "B";
                    assertEquals("done", loop.run(marker + ":0", null, marker, List.of(), () -> false));
                }
                assertEquals(6, fixture.a.calls.size());
                assertEquals(3, fixture.b.calls.size());
                fixture.barrier = new CyclicBarrier(2);
                final var first = fixture.executor.submit(() -> a.run("A:0", null, "A", List.of(), () -> false));
                final var second = fixture.executor.submit(() -> b.run("B:0", null, "B", List.of(), () -> false));
                assertEquals("done", first.get(10, TimeUnit.SECONDS));
                assertEquals("done", second.get(10, TimeUnit.SECONDS));
                assertEquals(9, fixture.a.calls.size());
                assertEquals(6, fixture.b.calls.size());
                for (final var request : fixture.requests) {
                    final var task = fixture.task(request);
                    final var marker = task.substring(0, 1);
                    final var lookup = request.tools().stream()
                            .filter(tool ->
                                    "client_lookup".equals(tool.function().name()))
                            .findFirst()
                            .orElseThrow();
                    assertEquals(marker, lookup.function().parameters().get("title"));
                    assertEquals("model-" + marker, request.settings().model());
                    assertEquals("default", request.settings().providerId());
                    request.messages().stream()
                            .filter(message -> "tool".equals(message.role()))
                            .filter(message -> String.valueOf(message.content()).startsWith("client:"))
                            .forEach(message -> {
                                final var text = String.valueOf(message.content());
                                assertEquals(
                                        "client:" + marker,
                                        text.lines().findFirst().orElseThrow());
                                assertFalse(text.contains("client:" + ("A".equals(marker) ? "B" : "A")), text);
                            });
                }
                assertFalse(fixture.a.closed);
                assertFalse(fixture.b.closed);
                assertEquals(0, fixture.approvals.get());
                assertSame(fixture.overlays.get("A"), a.effectiveToolRegistry());
            }
        }

        @Test
        void capturedParentCeilingPlanProfileAndRequestedToolsCannotWiden(final JsonMapper mapper) throws Exception {
            try (final var fixture = new Fixture(mapper, configuration)) {
                fixture.attack = true;
                final var permissions = fixture.permissions();
                for (final var ceiling : List.of(Set.of("delegate"), Set.of("client_lookup", "client_edit"))) {
                    final var mutableCeiling = new HashSet<>(ceiling);
                    final var context = new ToolContext(
                            "parent",
                            null,
                            null,
                            permissions,
                            null,
                            null,
                            null,
                            null,
                            null,
                            () -> false,
                            ChatSettings.EMPTY.withProviderId("default").withModel("model-A"),
                            null,
                            root,
                            new DisabledSandbox(root),
                            -1,
                            mutableCeiling,
                            null,
                            fixture.overlays.get("A"));
                    mutableCeiling.clear();
                    assertEquals(ceiling, context.allowedTools());
                    final var from = fixture.requests.size();
                    final var state = fixture.child(
                            context, "A:2", "limited", Set.of("client_lookup", "client_edit", "app_only"));
                    assertTrue(state.result().success(), state.result().error());
                    final var expected = ceiling.contains("client_lookup") ? Set.of("client_lookup") : Set.of();
                    fixture.requests
                            .subList(from, fixture.requests.size())
                            .forEach(request -> assertEquals(
                                    expected,
                                    request.tools().stream()
                                            .map(tool -> tool.function().name())
                                            .collect(toSet())));
                }
                assertEquals(List.of("client_lookup"), fixture.a.calls);
                assertTrue(fixture.app.calls.isEmpty());
                assertEquals(0, fixture.approvals.get(), "hidden tools must never reach an approval gate");
            }
        }

        @Test
        void childCloseAndCancellationLeaveParentUsableButClosedParentCannotDelegate(final JsonMapper mapper)
                throws Exception {
            try (final var fixture = new Fixture(mapper, configuration)) {
                final var parent = fixture.loop("A");
                assertEquals("done", parent.run("A:2", null, "A", List.of(), () -> false));
                final var context = fixture.contexts.getLast();
                final var child = fixture.child(context, "A:2", null, null);
                assertTrue(child.result().success(), child.result().error());
                final var childContext = fixture.contexts.getLast();
                assertTrue(childContext.isCancelled(), "completed child loop must be closed");
                assertFalse(context.isCancelled(), "closing a child must not dispose the parent");
                assertEquals("done", parent.run("A:2", null, "A", List.of(), () -> false));
                final var cancelled = new ToolContext(
                        "parent",
                        null,
                        null,
                        fixture.permissions(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        () -> false,
                        null,
                        () -> true,
                        root,
                        new DisabledSandbox(root),
                        -1,
                        null,
                        null,
                        fixture.overlays.get("A"));
                final var before = fixture.requests.size();
                assertTrue(fixture.child(cancelled, "A:2", null, null).result().cancelled());
                assertEquals(before, fixture.requests.size());
                parent.close();
                assertTrue(context.isCancelled());
                assertTrue(fixture.child(context, "A:2", null, null).result().cancelled());
                assertEquals(before, fixture.requests.size());
                assertThrows(IllegalStateException.class, parent::effectiveToolRegistry);
                assertThrows(IllegalStateException.class, parent::availableToolNames);
                assertFalse(fixture.a.closed);
                assertEquals("client:A", fixture.a.callTool("client_lookup", Map.of()));
            }
        }

        @Test
        void cancellationOfRunningChildDoesNotCancelParentOrCloseItsClient(final JsonMapper mapper) throws Exception {
            try (final var fixture = new Fixture(mapper, configuration)) {
                final var parent = fixture.loop("A");
                final var cancelled = new AtomicBoolean();
                final var context = new ToolContext(
                        "parent",
                        null,
                        null,
                        fixture.permissions(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        cancelled::get,
                        null,
                        null,
                        root,
                        new DisabledSandbox(root),
                        -1,
                        null,
                        null,
                        fixture.overlays.get("A"));
                fixture.childEntered = new CountDownLatch(1);
                fixture.releaseChild = new CountDownLatch(1);
                final var running = fixture.executor.submit(() -> fixture.child(context, "A:2", null, null));
                try {
                    assertTrue(fixture.childEntered.await(5, TimeUnit.SECONDS));
                    cancelled.set(true);
                } finally {
                    fixture.releaseChild.countDown();
                }
                assertTrue(running.get(5, TimeUnit.SECONDS).result().cancelled());
                assertTrue(fixture.a.calls.isEmpty());
                assertEquals("done", parent.run("A:2", null, "A", List.of(), () -> false));
                assertEquals(List.of("client_lookup"), fixture.a.calls);
                assertFalse(fixture.a.closed);
            }
        }

        @Test
        void standaloneContextUsesAppRegistryNotPreviouslyUsedSession(final JsonMapper mapper) throws Exception {
            try (final var fixture = new Fixture(mapper, configuration)) {
                assertEquals("done", fixture.loop("A").run("A:2", null, "A", List.of(), () -> false));
                fixture.attack = true;
                final var state = fixture.child(
                        ToolContext.empty(root, new DisabledSandbox(root)), "A:2", null, Set.of("app_only"));
                assertTrue(state.result().success(), state.result().error());
                assertEquals(List.of("app_only"), fixture.app.calls);
                assertEquals(List.of("client_lookup"), fixture.a.calls);
            }
        }
    }

    public static class MainChildAndGrandchildKeepTheirSessionOverlayInABAAndConcurrentABModule
            extends ConfigurationTestModule {
        public MainChildAndGrandchildKeepTheirSessionOverlayInABAAndConcurrentABModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "yca.storageHome",
                            MainChildAndGrandchildKeepTheirSessionOverlayInABAAndConcurrentABTests.root
                                    .resolve("storage")
                                    .toString()),
                    Map.entry(
                            "yca.workingDir",
                            MainChildAndGrandchildKeepTheirSessionOverlayInABAAndConcurrentABTests.root.toString()),
                    Map.entry("yca.subagents.enabled", "true"),
                    Map.entry("yca.subagents.maxConcurrent", "8"),
                    Map.entry("yca.llm.maxConcurrent", "16"),
                    Map.entry("yca.permissions.allow", "client_lookup"),
                    Map.entry("yca.memory.enabled", "false")));
        }
    }

    @Nested
    @FusionSupport(modules = DeniedMcpAndUnapprovedMcpDoNotExecuteOrGrantAnythingModule.class)
    class DeniedMcpAndUnapprovedMcpDoNotExecuteOrGrantAnythingTests {
        @TempDir
        static Path root;

        @Test
        void deniedMcpAndUnapprovedMcpDoNotExecuteOrGrantAnything(final JsonMapper mapper) throws Exception {
            try (final var fixture = new Fixture(mapper, configuration)) {
                fixture.attack = true;
                final var context = new ToolContext(
                        "parent",
                        null,
                        null,
                        fixture.permissions(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        () -> false,
                        null,
                        null,
                        root,
                        new DisabledSandbox(root),
                        -1,
                        Set.of("client_lookup", "client_sensitive"),
                        null,
                        fixture.overlays.get("A"));
                final var state = fixture.child(context, "A:2", null, null);
                assertTrue(state.result().success(), state.result().error());
                assertTrue(fixture.a.calls.isEmpty());
                assertEquals(1, fixture.approvals.get(), "only the non-readonly, non-denied MCP call may ask");
                assertTrue(context.permissions().sessionGrants().isEmpty());
            }
        }
    }

    public static class DeniedMcpAndUnapprovedMcpDoNotExecuteOrGrantAnythingModule extends ConfigurationTestModule {
        public DeniedMcpAndUnapprovedMcpDoNotExecuteOrGrantAnythingModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "yca.storageHome",
                            DeniedMcpAndUnapprovedMcpDoNotExecuteOrGrantAnythingTests.root
                                    .resolve("storage")
                                    .toString()),
                    Map.entry(
                            "yca.workingDir",
                            DeniedMcpAndUnapprovedMcpDoNotExecuteOrGrantAnythingTests.root.toString()),
                    Map.entry("yca.subagents.enabled", "true"),
                    Map.entry("yca.subagents.maxConcurrent", "8"),
                    Map.entry("yca.llm.maxConcurrent", "16"),
                    Map.entry("yca.permissions.allow", "client_lookup"),
                    Map.entry("yca.memory.enabled", "false"),
                    Map.entry("yca.permissions.deny", "client_lookup")));
        }
    }

    private static CodingAgentConfiguration withStorageHomeWorkingDir(
            final CodingAgentConfiguration baseline, final String storageHome, final String workingDir) {
        return new CodingAgentConfiguration(
                storageHome,
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
