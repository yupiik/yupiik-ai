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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.context.CompactionResult;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.ToolUsePreEvent;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.security.ToolExecutionPrepare;
import io.yupiik.ia.agent.core.session.FileSessionStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolDiagnosticException;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ExtendWith(TestJsonMapper.class)
@Timeout(30)
class ToolDiagnosticIsolationTest {
    private static final String SAFE_MESSAGE = "MCP tool diagnostic_probe failed";
    private static final String UI_DETAIL = "UI_ONLY_DIAGNOSTIC_7e3d: private transport response and stack trace";

    @TempDir
    Path root;

    @Nested
    @FusionSupport(modules = DiagnosticModule.class)
    class Configured {
        @TempDir
        static Path configurationRoot;

        @AfterAll
        static void closeServer() {
            DiagnosticModule.SERVER.close();
        }

        @ParameterizedTest
        @CsvSource({
            "false,specialized",
            "true,specialized",
            "false,runtime",
            "true,runtime",
            "false,prepare",
            "true,prepare",
            "false,started",
            "true,started",
            "false,gate",
            "true,gate",
            "false,outer",
            "true,outer"
        })
        void diagnosticStaysOutOfProviderRequestsReloadedHistoryAndCompaction(
                final boolean failingDiagnosticObserver,
                final String failureStage,
                final JsonMapper mapper,
                @Fusion final CodingAgentConfiguration baseline)
                throws Exception {
            final var safeMessage = "specialized".equals(failureStage) ? SAFE_MESSAGE : "Tool diagnostic_probe failed.";
            final var requests = new CopyOnWriteArrayList<String>();
            final var events = new CopyOnWriteArrayList<AgentEvent>();
            final var executions = new AtomicInteger();
            final var server = DiagnosticModule.SERVER;
            server.handler.set(exchange -> {
                try (exchange) {
                    final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    requests.add(body);
                    final var streaming = Boolean.TRUE.equals(
                            mapper.fromString(Map.class, body).get("stream"));
                    final String response;
                    if (!streaming) {
                        response = """
                            {"id":"summary","choices":[{"index":0,"message":{"role":"assistant","content":"Tool failed."},"finish_reason":"stop"}]}
                            """;
                    } else if (requests.size() == 1) {
                        response = """
                            data: {"id":"tool","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"diagnostic-call","type":"function","function":{"name":"diagnostic_probe","arguments":"{}"}}]},"finish_reason":null}]}

                            data: {"id":"tool","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                            data: [DONE]

                            """;
                    } else {
                        response = """
                            data: {"id":"answer","choices":[{"index":0,"delta":{"role":"assistant","content":"done"},"finish_reason":null}]}

                            data: {"id":"answer","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                            data: [DONE]

                            """;
                    }
                    exchange.getResponseHeaders()
                            .set("Content-Type", streaming ? "text/event-stream" : "application/json");
                    final var bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            try {
                final var config = withStorageHomeWorkingDir(baseline, root.toString(), root.toString());
                final var clock = Clock.systemUTC();
                final var providers = new ProviderManager(config, mapper);
                final var store = new FileSessionStore(config, mapper, clock);
                final var session = store.create();
                final var emitter = new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        if ("prepare".equals(failureStage) && event instanceof ToolExecutionPrepare) {
                            throw new IllegalStateException(UI_DETAIL);
                        }
                        if (("started".equals(failureStage) && event instanceof AgentEvent.ToolStarted)
                                || ("gate".equals(failureStage) && event instanceof ToolUsePreEvent)) {
                            throw new IllegalStateException(UI_DETAIL);
                        }
                        if (event instanceof AgentEvent agentEvent) {
                            events.add(agentEvent);
                        }
                        if (failingDiagnosticObserver && event instanceof AgentEvent.ToolDiagnostic diagnostic) {
                            throw new IllegalStateException(diagnostic.detail());
                        }
                    }
                };
                final var tool = new EmbeddedTool() {
                    @Override
                    public String name() {
                        return "diagnostic_probe";
                    }

                    @Override
                    public String description(final Path workdir) {
                        return "Exercise UI-only tool failure details";
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.AGENT;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        executions.incrementAndGet();
                        if (!"specialized".equals(failureStage)) {
                            throw new IllegalStateException(UI_DETAIL);
                        }
                        throw new ToolDiagnosticException(SAFE_MESSAGE, UI_DETAIL);
                    }
                };
                final var registry = new ToolRegistry(null) {
                    @Override
                    public List<EmbeddedTool> tools() {
                        return List.of(tool);
                    }
                };
                try (final var delegate = HttpClient.newHttpClient();
                        final var http =
                                new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
                        final var client = new ChatClient(config, mapper, providers, clock, http);
                        final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    final var usage = new UsageTracker(providers, client, null, clock);
                    final var coordinator =
                            new CompactionCoordinator(config, client, usage, providers, emitter, session, null, root);
                    final var loop = new AgentLoop(
                            client,
                            registry,
                            new ToolExecutionService(config, emitter, List.of()) {
                                @Override
                                public ToolResult execute(
                                        final EmbeddedTool embeddedTool,
                                        final Map<String, Object> args,
                                        final ToolContext context) {
                                    if ("outer".equals(failureStage)) {
                                        throw new IllegalStateException(UI_DETAIL);
                                    }
                                    return super.execute(embeddedTool, args, context);
                                }
                            },
                            config,
                            new PermissionService(config, root),
                            usage,
                            null,
                            new ContextManager(config, usage, coordinator),
                            new ContextView(),
                            store,
                            new SystemPromptBuilder(root) {
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
                            clock,
                            null,
                            null,
                            executor,
                            session,
                            null,
                            null,
                            null,
                            new PrecisionDurationMarkerProvider(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            root,
                            new DisabledSandbox(root));
                    try {
                        assertEquals("done", loop.run("Run the diagnostic probe", null, session, null, () -> false));
                        assertEquals(
                                List.of("specialized", "runtime").contains(failureStage) ? 1 : 0, executions.get());
                        assertEquals(
                                1,
                                events.stream()
                                        .filter(AgentEvent.ToolFinished.class::isInstance)
                                        .count());
                        assertEquals(
                                2, requests.size(), "the provider must receive the tool failure and finish the turn");
                        assertSafeToolReply(mapper, requests.get(1), safeMessage);

                        final var diagnostic = events.stream()
                                .filter(AgentEvent.ToolDiagnostic.class::isInstance)
                                .map(AgentEvent.ToolDiagnostic.class::cast)
                                .toList();
                        assertEquals(1, diagnostic.size(), "the real tool execution must emit its UI diagnostic");
                        assertTrue(diagnostic.getFirst().detail().contains(UI_DETAIL));
                        assertEquals(tool.name(), diagnostic.getFirst().name());
                        assertEquals(session, diagnostic.getFirst().sessionId());
                        assertEquals(root, diagnostic.getFirst().workdir());
                        final var finished = events.stream()
                                .filter(AgentEvent.ToolFinished.class::isInstance)
                                .map(AgentEvent.ToolFinished.class::cast)
                                .findFirst()
                                .orElseThrow();
                        assertFalse(finished.success());
                        assertEquals(safeMessage, finished.output());
                        assertEquals(finished.ordinal(), diagnostic.getFirst().ordinal());

                        // Reopen the file store, rather than trusting only the in-turn result/history.
                        final var reopened = new FileSessionStore(config, mapper, clock);
                        final var history = reopened.load(session);
                        assertTrue(history.stream()
                                .anyMatch(m -> "tool".equals(m.role()) && safeMessage.equals(m.content())));
                        assertFalse(history.toString().contains(UI_DETAIL));
                        assertEquals(
                                "done",
                                loop.run("What happened on the previous turn?", null, session, history, () -> false));
                        assertEquals(3, requests.size());
                        assertTrue(requests.get(2).contains("What happened on the previous turn?"));
                        assertSafeToolReply(mapper, requests.get(2), safeMessage);

                        // Compact the actual persisted conversation, including the failed tool exchange.
                        final var compactionInput = new ArrayList<Message>();
                        compactionInput.add(Message.system("system prompt"));
                        compactionInput.addAll(reopened.load(session));
                        final var compacted = coordinator.compact(compactionInput, true, null, 2);
                        assertEquals(CompactionResult.Mode.SUMMARY, compacted.mode());
                        assertEquals(4, requests.size(), "a real summarizer provider request must have been captured");
                        assertTrue(
                                requests.get(3).contains(safeMessage),
                                "the failed tool exchange must be in the summary input");
                        assertFalse(compacted.messages().toString().contains(UI_DETAIL));

                        for (final var request : requests) {
                            assertFalse(
                                    request.contains(UI_DETAIL), "UI-only detail must never be sent to the provider");
                        }
                        try (final var files = Files.walk(root)) {
                            for (final var file :
                                    files.filter(Files::isRegularFile).toList()) {
                                assertFalse(
                                        Files.readString(file).contains(UI_DETAIL),
                                        "UI-only detail persisted in " + file);
                            }
                        }
                    } finally {
                        loop.close();
                    }
                }
            } finally {
                server.handler.set(null);
            }
        }
    }

    private void assertSafeToolReply(final JsonMapper mapper, final String body, final String safeMessage) {
        final var request = mapper.fromString(Map.class, body);
        final var messages = (List<?>) request.get("messages");
        final var replies = messages.stream()
                .map(Map.class::cast)
                .filter(m -> "tool".equals(m.get("role")))
                .toList();
        assertEquals(1, replies.size());
        assertEquals("diagnostic-call", replies.getFirst().get("tool_call_id"));
        assertEquals(safeMessage, replies.getFirst().get("content"));
    }

    private static final class ServerFixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<HttpHandler> handler = new AtomicReference<>();

        private ServerFixture() {
            try {
                server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                server.createContext("/v1/chat/completions", exchange -> {
                    final var current = handler.get();
                    if (current == null) {
                        try (exchange) {
                            exchange.sendResponseHeaders(503, -1);
                        }
                    } else {
                        current.handle(exchange);
                    }
                });
                server.start();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    public static class DiagnosticModule extends ConfigurationTestModule {
        private static final ServerFixture SERVER = new ServerFixture();

        public DiagnosticModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", Configured.configurationRoot.toString()),
                    Map.entry("yca.workingDir", Configured.configurationRoot.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry(
                            "yca.provider.providers.0.baseUrl",
                            "http://localhost:" + SERVER.server.getAddress().getPort() + "/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.api", "completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.0.model", "test-model"),
                    Map.entry("yca.provider.providers.0.contextWindow", "128000"),
                    Map.entry("yca.provider.providers.0.outputTokens", "1024"),
                    Map.entry("yca.pruning.enabled", "false"),
                    Map.entry("yca.pruning.keepRecent", "2"),
                    Map.entry("yca.compaction.tail.budgetTokens", "1")));
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
