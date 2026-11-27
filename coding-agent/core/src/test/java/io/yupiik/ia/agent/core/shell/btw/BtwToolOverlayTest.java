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
package io.yupiik.ia.agent.core.shell.btw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.PermissionsConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.InBandToolCallExtractor;
import io.yupiik.ia.agent.core.llm.MarkupToolCallScanner;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.ToolCallPairingRepair;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
@Timeout(20)
class BtwToolOverlayTest {
    private Path root;

    @BeforeEach
    void root(@Fusion final CodingAgentConfiguration configuration) {
        root = configuration.resolveWorkingDir();
    }

    @Nested
    @FusionSupport(modules = ExplicitToolsStaySessionLocalAcrossABAAndConcurrentABScenarioModule.class)
    class ExplicitToolsStaySessionLocalAcrossABAAndConcurrentABScenario {
        @TempDir
        static Path directory;

        @Test
        void explicitToolsStaySessionLocalAcrossABAAndConcurrentAB(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            try (final var fixture = new Fixture(mapper, configuration)) {
                for (final var marker : List.of("A", "B", "A")) {
                    assertEquals(
                            "done",
                            fixture.ask(
                                            marker,
                                            fixture.registry(marker, true, true),
                                            Set.of("client_lookup"),
                                            () -> false)
                                    .get(5, TimeUnit.SECONDS)
                                    .text());
                }
                fixture.barrier = new CyclicBarrier(2);
                final var a = fixture.ask("A", fixture.registry("A", true, true), Set.of("client_lookup"), () -> false);
                final var b = fixture.ask("B", fixture.registry("B", true, true), Set.of("client_lookup"), () -> false);
                assertEquals("done", a.get(5, TimeUnit.SECONDS).text());
                assertEquals("done", b.get(5, TimeUnit.SECONDS).text());
                assertEquals(3, fixture.invocations.stream().filter("A"::equals).count());
                assertEquals(2, fixture.invocations.stream().filter("B"::equals).count());
                for (final var request : fixture.requests) {
                    assertEquals(
                            request.sessionId(),
                            request.tools().getFirst().function().parameters().get("title"));
                    assertEquals(
                            "model-" + request.sessionId(), request.settings().model());
                    request.messages().stream()
                            .filter(message -> "tool".equals(message.role()))
                            .forEach(message -> assertEquals("client:" + request.sessionId(), message.content()));
                }
                assertEquals(0, fixture.approvals.get());
                // Legacy explicit-root invocation is genuinely standalone: it uses only the injected app registry.
                fixture.barrier = null;
                fixture.answerer
                        .ask("standalone", "app", null, () -> false, fixture.gate, root, new DisabledSandbox(root))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertEquals("app", fixture.invocations.getLast());
            }
        }
    }

    @Nested
    @FusionSupport(modules = CeilingsReadonlyUsabilityAndDenialsCannotBeWidenedByModelCallsScenarioModule.class)
    class CeilingsReadonlyUsabilityAndDenialsCannotBeWidenedByModelCallsScenario {
        @TempDir
        static Path directory;

        @Test
        void ceilingsReadonlyUsabilityAndDenialsCannotBeWidenedByModelCalls(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            for (var variant = 0; variant < 5; variant++) {
                final var denied = variant == 3;
                try (final var fixture = new Fixture(mapper, denied ? denyLookup(configuration) : configuration)) {
                    final var registry = variant == 4 ? null : fixture.registry("A", variant != 1, variant != 2);
                    final var ceiling = variant == 0 ? Set.<String>of() : Set.of("client_lookup");
                    assertEquals(
                            "done",
                            fixture.ask("A", registry, ceiling, () -> false)
                                    .get(5, TimeUnit.SECONDS)
                                    .text());
                    assertTrue(
                            fixture.invocations.isEmpty(),
                            "a hallucinated unavailable or denied call must never execute");
                    assertEquals(
                            0,
                            fixture.approvals.get(),
                            "readonly side requests must not auto-approve unavailable tools");
                    assertEquals(
                            denied ? 1 : 0, fixture.requests.getFirst().tools().size());
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = ToolLessSchemaUsesExplicitOverlayWithoutExecutingAnyCallScenarioModule.class)
    class ToolLessSchemaUsesExplicitOverlayWithoutExecutingAnyCallScenario {
        @TempDir
        static Path directory;

        @Test
        void toolLessSchemaUsesExplicitOverlayWithoutExecutingAnyCall(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            try (final var fixture = new Fixture(mapper, configuration)) {
                final var answer = fixture.ask(
                                "A", fixture.registry("A", true, true), Set.of("client_lookup"), () -> false)
                        .get(5, TimeUnit.SECONDS);
                assertTrue(answer.retryable(), "the model's tool-only answer is not executed in tool-less mode");
                assertEquals(1, fixture.requests.size());
                assertEquals(
                        "A",
                        fixture.requests
                                .getFirst()
                                .tools()
                                .getFirst()
                                .function()
                                .parameters()
                                .get("title"));
                assertTrue(fixture.invocations.isEmpty());
                assertEquals(0, fixture.approvals.get());
            }
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitSessionApiRequiresAbsoluteNormalizedWorkdirScenarioModule.class)
    class ExplicitSessionApiRequiresAbsoluteNormalizedWorkdirScenario {
        @TempDir
        static Path directory;

        @Test
        void explicitSessionApiRequiresAbsoluteNormalizedWorkdir(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            try (final var fixture = new Fixture(mapper, configuration)) {
                final var registry = fixture.registry("A", true, true);
                assertThrows(
                        NullPointerException.class,
                        () -> fixture.answerer.ask(
                                "lookup",
                                "A",
                                null,
                                () -> false,
                                fixture.gate,
                                null,
                                null,
                                null,
                                null,
                                registry,
                                Set.of(),
                                new DisabledSandbox(null)));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.answerer.ask(
                                "lookup",
                                "A",
                                null,
                                () -> false,
                                fixture.gate,
                                null,
                                null,
                                null,
                                Path.of("relative"),
                                registry,
                                Set.of(),
                                new DisabledSandbox(Path.of("relative"))));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.answerer.ask(
                                "lookup",
                                "A",
                                null,
                                () -> false,
                                fixture.gate,
                                null,
                                null,
                                null,
                                root.resolve(".."),
                                registry,
                                Set.of(),
                                new DisabledSandbox(root.resolve(".."))));
                assertThrows(
                        NullPointerException.class,
                        () -> fixture.answerer.ask(
                                "lookup",
                                "A",
                                null,
                                () -> false,
                                fixture.gate,
                                null,
                                null,
                                null,
                                root,
                                registry,
                                null,
                                new DisabledSandbox(root)));
                assertTrue(fixture.requests.isEmpty());
            }
        }
    }

    @Nested
    @FusionSupport(modules = CapturedCeilingIsImmutableAndCancellationDoesNotDisposeBorrowedToolsScenarioModule.class)
    class CapturedCeilingIsImmutableAndCancellationDoesNotDisposeBorrowedToolsScenario {
        @TempDir
        static Path directory;

        @Test
        void capturedCeilingIsImmutableAndCancellationDoesNotDisposeBorrowedTools(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            try (final var fixture = new Fixture(mapper, configuration)) {
                fixture.entered = new CountDownLatch(1);
                fixture.release = new CountDownLatch(1);
                final var ceiling = new HashSet<>(Set.of("client_lookup"));
                final var registry = fixture.registry("A", true, true);
                final var first = fixture.ask("A", registry, ceiling, () -> false);
                try {
                    assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
                    ceiling.clear();
                } finally {
                    fixture.release.countDown();
                }
                assertEquals("done", first.get(5, TimeUnit.SECONDS).text());
                assertEquals(List.of("A"), fixture.invocations);

                fixture.entered = new CountDownLatch(1);
                fixture.release = new CountDownLatch(1);
                final var cancelled = new AtomicBoolean();
                final var second =
                        fixture.ask("B", fixture.registry("B", true, true), Set.of("client_lookup"), cancelled::get);
                try {
                    assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
                    cancelled.set(true);
                } finally {
                    fixture.release.countDown();
                }
                final var error = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
                assertInstanceOf(InterruptedException.class, error.getCause());
                assertEquals(List.of("A"), fixture.invocations);
                assertEquals(
                        "client:A",
                        registry.tool("client_lookup")
                                .execute(Map.of(), ToolContext.empty(root, new DisabledSandbox(root)))
                                .output());
            }
        }
    }

    @Nested
    @FusionSupport(modules = LiveShellCommandBorrowsTheRuntimeOverlayAndCeilingScenarioModule.class)
    class LiveShellCommandBorrowsTheRuntimeOverlayAndCeilingScenario {
        @TempDir
        static Path directory;

        @Test
        void liveShellCommandBorrowsTheRuntimeOverlayAndCeiling(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            try (final var fixture = new Fixture(mapper, configuration)) {
                for (final var marker : List.of("A", "B", "A")) {
                    final var registry = fixture.registry(marker, true, true);
                    final var settings =
                            ChatSettings.EMPTY.withProviderId("default").withModel("model-" + marker);
                    final var loop = new AgentLoop(root, new DisabledSandbox(root)) {
                        @Override
                        public ToolRegistry effectiveToolRegistry() {
                            return registry;
                        }

                        @Override
                        public Set<String> availableToolNames() {
                            return Set.of("client_lookup");
                        }

                        @Override
                        public ChatSettings chatSettings() {
                            return settings;
                        }

                        @Override
                        public void close() {}
                    };
                    final var permissions = new PermissionService(configuration, root);
                    final var runtime = new SessionRuntime(
                            loop, permissions, null, null, null, null, null, null, root, new DisabledSandbox(root));
                    final var env = new ShellEnv(
                            null, null, new ShellSession().sessionId(marker).runtime(runtime), null);
                    try {
                        final var outcome = new BtwSlashCommands(fixture.answerer)
                                .commands()
                                .getFirst()
                                .action()
                                .apply("lookup " + marker, env)
                                .toCompletableFuture()
                                .get(5, TimeUnit.SECONDS);
                        assertInstanceOf(ShellOutcome.Transcript.class, outcome);
                        if (fixture.invocations.size() == 3) {
                            fixture.entered = new CountDownLatch(1);
                            fixture.release = new CountDownLatch(1);
                            final var pending = new BtwSlashCommands(fixture.answerer)
                                    .commands()
                                    .getFirst()
                                    .action()
                                    .apply("blocked lookup", env)
                                    .toCompletableFuture();
                            try {
                                assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
                                assertTrue(pending.cancel(true));
                            } finally {
                                fixture.release.countDown();
                            }
                            fixture.executor.shutdown();
                            assertTrue(fixture.executor.awaitTermination(5, TimeUnit.SECONDS));
                        }
                    } finally {
                        runtime.close();
                    }
                }
                assertEquals(
                        List.of("A", "B", "A"), fixture.invocations, "cancelled side work must not execute a tool");
                assertTrue(fixture.requests.stream()
                        .noneMatch(request -> request.tools().stream()
                                .anyMatch(tool ->
                                        tool.function().parameters().toString().contains("app"))));
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final List<String> invocations = new CopyOnWriteArrayList<>();
        private final List<ChatCompletionRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger approvals = new AtomicInteger();
        private final ApprovalGate gate = request -> {
            approvals.incrementAndGet();
            return ApprovalGate.Result.DENY;
        };
        private final BtwAnswerer answerer;
        private volatile CyclicBarrier barrier;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;
        private final ChatClient client = new ChatClient() {
            @Override
            public ChatResult chat(final ChatCompletionRequest request, final BooleanSupplier cancelled)
                    throws InterruptedException {
                requests.add(request);
                if (request.messages().stream().noneMatch(message -> "tool".equals(message.role()))) {
                    if (barrier != null) {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                        } catch (final Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    if (entered != null) {
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    }
                    return new ChatResult(
                            Message.assistant(
                                    null,
                                    List.of(new ToolCall(
                                            "lookup",
                                            "function",
                                            new ToolCall.FunctionCall("client_lookup", "{}"),
                                            0))),
                            null);
                }
                return new ChatResult(Message.assistant("done", null), null);
            }
        };

        private Fixture(final JsonMapper mapper, final CodingAgentConfiguration configuration) {
            final var clock = Clock.systemUTC();
            final var store = new InMemorySessionStore(clock);
            final var emitter = new Emitter() {
                @Override
                public <T> void emit(final T event) {}
            };
            answerer = new BtwAnswerer(
                    client,
                    store,
                    configuration,
                    executor,
                    new InBandToolCallExtractor(new MarkupToolCallScanner()),
                    registry("app", true, true),
                    new ToolExecutionService(configuration, emitter, List.of()),
                    mapper,
                    null,
                    clock,
                    emitter,
                    null,
                    new ToolCallPairingRepair(store, clock),
                    null,
                    null);
        }

        private ToolRegistry registry(final String marker, final boolean readonly, final boolean usable) {
            final EmbeddedTool tool = new EmbeddedTool() {
                @Override
                public String name() {
                    return "client_lookup";
                }

                @Override
                public String description(final Path workdir) {
                    return "Session client " + marker;
                }

                @Override
                public ToolCategory category() {
                    return ToolCategory.MCP;
                }

                @Override
                public boolean readonly() {
                    return readonly;
                }

                @Override
                public boolean usable(final Path workdir) {
                    return usable;
                }

                @Override
                public Map<String, Object> schema() {
                    return Map.of("type", "object", "title", marker);
                }

                @Override
                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                    assertEquals(root, context.projectDir());
                    if (context.toolRegistry() != null) {
                        assertSame(
                                this,
                                context.toolRegistry().tool(name()),
                                "tool context must carry the exact borrowed overlay");
                    }
                    invocations.add(marker);
                    return ToolResult.ok("client:" + marker);
                }
            };
            return new ToolRegistry(null) {
                @Override
                public List<EmbeddedTool> tools() {
                    return List.of(tool);
                }
            }.withTools(List.of());
        }

        private CompletableFuture<BtwAnswerer.BtwAnswer> ask(
                final String marker,
                final ToolRegistry registry,
                final Set<String> ceiling,
                final BooleanSupplier cancelled) {
            return answerer.ask(
                            "lookup " + marker,
                            marker,
                            null,
                            cancelled,
                            gate,
                            ChatSettings.EMPTY.withProviderId("default").withModel("model-" + marker),
                            null,
                            null,
                            root,
                            registry,
                            ceiling,
                            new DisabledSandbox(root))
                    .toCompletableFuture();
        }

        @Override
        public void close() {
            executor.close();
            answerer.release();
            client.close();
        }
    }

    private CodingAgentConfiguration denyLookup(final CodingAgentConfiguration baseline) {
        return new CodingAgentConfiguration(
                baseline.storageHome(),
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
                new PermissionsConfiguration(
                        baseline.permissions().mode(),
                        baseline.permissions().allow(),
                        List.of("client_lookup"),
                        baseline.permissions().bash()),
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

    public static class ExplicitToolsStaySessionLocalAcrossABAAndConcurrentABScenarioModule
            extends ConfigurationTestModule {
        public ExplicitToolsStaySessionLocalAcrossABAAndConcurrentABScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    ExplicitToolsStaySessionLocalAcrossABAAndConcurrentABScenario.directory
                            .resolve("storage")
                            .toString(),
                    "yca.workingDir",
                    ExplicitToolsStaySessionLocalAcrossABAAndConcurrentABScenario.directory.toString(),
                    "yca.slashcommands.btw.tools",
                    "true"));
        }
    }

    public static class CeilingsReadonlyUsabilityAndDenialsCannotBeWidenedByModelCallsScenarioModule
            extends ConfigurationTestModule {
        public CeilingsReadonlyUsabilityAndDenialsCannotBeWidenedByModelCallsScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    CeilingsReadonlyUsabilityAndDenialsCannotBeWidenedByModelCallsScenario.directory
                            .resolve("storage")
                            .toString(),
                    "yca.workingDir",
                    CeilingsReadonlyUsabilityAndDenialsCannotBeWidenedByModelCallsScenario.directory.toString(),
                    "yca.slashcommands.btw.tools",
                    "true"));
        }
    }

    public static class ToolLessSchemaUsesExplicitOverlayWithoutExecutingAnyCallScenarioModule
            extends ConfigurationTestModule {
        public ToolLessSchemaUsesExplicitOverlayWithoutExecutingAnyCallScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    ToolLessSchemaUsesExplicitOverlayWithoutExecutingAnyCallScenario.directory
                            .resolve("storage")
                            .toString(),
                    "yca.workingDir",
                    ToolLessSchemaUsesExplicitOverlayWithoutExecutingAnyCallScenario.directory.toString(),
                    "yca.slashcommands.btw.tools",
                    "false"));
        }
    }

    public static class ExplicitSessionApiRequiresAbsoluteNormalizedWorkdirScenarioModule
            extends ConfigurationTestModule {
        public ExplicitSessionApiRequiresAbsoluteNormalizedWorkdirScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    ExplicitSessionApiRequiresAbsoluteNormalizedWorkdirScenario.directory
                            .resolve("storage")
                            .toString(),
                    "yca.workingDir",
                    ExplicitSessionApiRequiresAbsoluteNormalizedWorkdirScenario.directory.toString(),
                    "yca.slashcommands.btw.tools",
                    "true"));
        }
    }

    public static class CapturedCeilingIsImmutableAndCancellationDoesNotDisposeBorrowedToolsScenarioModule
            extends ConfigurationTestModule {
        public CapturedCeilingIsImmutableAndCancellationDoesNotDisposeBorrowedToolsScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    CapturedCeilingIsImmutableAndCancellationDoesNotDisposeBorrowedToolsScenario.directory
                            .resolve("storage")
                            .toString(),
                    "yca.workingDir",
                    CapturedCeilingIsImmutableAndCancellationDoesNotDisposeBorrowedToolsScenario.directory.toString(),
                    "yca.slashcommands.btw.tools",
                    "true"));
        }
    }

    public static class LiveShellCommandBorrowsTheRuntimeOverlayAndCeilingScenarioModule
            extends ConfigurationTestModule {
        public LiveShellCommandBorrowsTheRuntimeOverlayAndCeilingScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    LiveShellCommandBorrowsTheRuntimeOverlayAndCeilingScenario.directory
                            .resolve("storage")
                            .toString(),
                    "yca.workingDir",
                    LiveShellCommandBorrowsTheRuntimeOverlayAndCeilingScenario.directory.toString(),
                    "yca.slashcommands.btw.tools",
                    "true"));
        }
    }
}
