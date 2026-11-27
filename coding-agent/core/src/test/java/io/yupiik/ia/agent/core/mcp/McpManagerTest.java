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
package io.yupiik.ia.agent.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.BuiltinToolsConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.McpConfiguration;
import io.yupiik.ia.agent.core.configuration.McpServerConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ToolsConfiguration;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer.SessionCapableClient;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(TestJsonMapper.class)
@FusionSupport(modules = ConfigurationTestModule.class)
class McpManagerTest {
    private CodingAgentConfiguration baseline;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        baseline = configuration;
    }

    @Test
    void startupOverrideCanUseConstructorWithoutConfiguration() {
        try (final var manager = new McpManager(null, null, List.of(), null, null) {
            @Override
            public void start() {
                // Test doubles supply their own tools instead of configured servers.
            }
        }) {
            assertTrue(manager.tools().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(10)
    void configuredTimeoutBoundsBothStdioManagerPaths(final boolean session, @TempDir final Path tmp) throws Exception {
        final var command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                AdversarialMcpServer.class.getName(),
                "silent-initialize",
                tmp.toString());
        final var config = configuration(
                List.of(new McpServerConfiguration(
                        "silent",
                        command.getFirst(),
                        command.subList(1, command.size()),
                        List.of(),
                        null,
                        null,
                        null,
                        List.of(),
                        true,
                        List.of())),
                1);
        final var launched = new AtomicReference<Process>();
        final var processes = new ProcessService() {
            @Override
            public Process track(final Process process) {
                launched.set(process);
                return super.track(process);
            }
        };
        final var manager = new McpManager(config, mapper, List.of(), httpClient, processes);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                if (session) {
                    assertThrows(
                            IOException.class,
                            () -> manager.openSession(
                                    List.of(new McpManager.StdioServer("silent", command, List.of())), tmp));
                } else {
                    manager.start();
                    assertEquals(
                            List.of(new McpManager.ServerStatus("silent", McpManager.ServerState.FAILED)),
                            manager.serverStatuses());
                    assertTrue(manager.tools().isEmpty());
                }
            });
            launched.get().onExit().get(3, TimeUnit.SECONDS);
            assertFalse(launched.get().isAlive());
            assertEquals(0, processes.trackedCount());
        } finally {
            manager.close();
        }
    }

    private HttpServer server;

    private JsonMapper mapper;
    // shared per test class: each per-test client spawned/stopped a selector thread
    private static SessionCapableClient httpClient;

    @BeforeAll
    static void bootClient(@Fusion final CodingAgentConfiguration configuration) {
        httpClient = new HttpClientProducer().httpClient(configuration, new NativeProviderConfiguration(null, null));
    }

    @AfterAll
    static void stopClient() {
        httpClient.close();
    }

    @BeforeEach
    void boot(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void startResetAndCall() throws Exception {
        final var initializations = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"method\":\"initialize\"")) {
                initializations.incrementAndGet();
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{\"listChanged\":true}},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}},\"id\":1}",
                        "application/json");
            } else if (body.contains("\"method\":\"notifications/initialized\"")) {
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            } else if (body.contains("\"method\":\"tools/list\"")) {
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"find_docs\",\"description\":\"find"
                                + " docs\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}]},\"id\":2}",
                        "application/json");
            } else if (body.contains("\"method\":\"tools/call\"")) {
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello from"
                                + " server\"}]},\"id\":3}",
                        "application/json");
            } else {
                respond(
                        exchange,
                        500,
                        "{\"error\":{\"code\":-32601,\"message\":\"method not found\"},\"id\":4}",
                        "application/json");
            }
        });
        server.start();

        final var url = "http://localhost:" + server.getAddress().getPort() + "/mcp";
        final var manager = new McpManager(
                configuration(List.of(
                        new McpServerConfiguration(
                                "test", null, List.of(), List.of(), null, url, "key", List.of(), true, List.of()),
                        new McpServerConfiguration(
                                "disabled",
                                null,
                                List.of(),
                                List.of(),
                                null,
                                "http://localhost:1",
                                null,
                                List.of(),
                                false,
                                List.of()))),
                mapper,
                List.of(),
                httpClient,
                new ProcessService());

        manager.start();
        assertEquals(1, initializations.get());
        assertEquals(1, manager.tools().size());
        assertEquals("test_find_docs", manager.tools().get(0).name());
        assertEquals("hello from server", manager.call("test", "find_docs", Map.of("q", "x")));
        assertTrue(
                manager.serverStatuses().contains(new McpManager.ServerStatus("test", McpManager.ServerState.ENABLED)));
        assertTrue(manager.serverStatuses()
                .contains(new McpManager.ServerStatus("disabled", McpManager.ServerState.DISABLED)));

        manager.reset("test");
        assertEquals(2, initializations.get());
        assertEquals(1, manager.tools().size(), "reset should re-import the server tools");
        assertEquals("hello from server", manager.call("test", "find_docs", Map.of("q", "x")));
        assertTrue(
                manager.serverStatuses().contains(new McpManager.ServerStatus("test", McpManager.ServerState.ENABLED)));

        manager.reset();
        assertEquals(3, initializations.get());
        assertEquals(1, manager.tools().size());

        final var error = assertThrows(IllegalArgumentException.class, () -> manager.reset("does-not-exist"));
        assertTrue(error.getMessage().contains("does-not-exist"));
        final var disabled = assertThrows(IllegalArgumentException.class, () -> manager.reset("disabled"));
        assertTrue(disabled.getMessage().contains("disabled"));
    }

    @Test
    void secondStartMustNotReconnectTheServers() throws Exception {
        // Regression for the native duplicate-logs issue: the GraalVM build can resolve the shared
        // McpManager twice (two bean instances) or fire start() twice, which used to reconnect every
        // MCP server and duplicate the "ready" startup logs. A re-entrant start() must be a no-op.
        final var initializations = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"method\":\"initialize\"")) {
                initializations.incrementAndGet();
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}},\"id\":1}",
                        "application/json");
            } else if (body.contains("\"method\":\"tools/list\"")) {
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"find_docs\",\"description\":\"find"
                                + " docs\",\"inputSchema\":{\"type\":\"object\"}}]},\"id\":2}",
                        "application/json");
            } else {
                respond(exchange, 200, "{}", "application/json");
            }
        });
        server.start();

        final var url = "http://localhost:" + server.getAddress().getPort() + "/mcp";
        final var manager = new McpManager(
                configuration(List.of(new McpServerConfiguration(
                        "test", null, List.of(), List.of(), null, url, "key", List.of(), true, List.of()))),
                mapper,
                List.of(),
                httpClient,
                new ProcessService());

        manager.start();
        final var firstTools = manager.tools();
        manager.start(); // re-entrant: must not reconnect
        assertEquals(1, initializations.get(), "a second start() must not re-initialize the server");
        assertEquals(1, manager.tools().size());
        assertEquals(firstTools, manager.tools());
    }

    @Test
    void startIsNonBlockingButFirstUseJoins() throws Exception {
        // The @ApplicationScoped (app path) McpManager runs remote handshakes asynchronously on the
        // injected executor so container startup is never blocked. start() must return before the
        // server has answered, while the first real use (tools()/call()) and close() join the
        // in-flight handshake.
        final var initializeGate = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"method\":\"initialize\"")) {
                try {
                    if (!initializeGate.await(5, TimeUnit.SECONDS)) {
                        respond(exchange, 500, "{}", "application/json");
                        return;
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    respond(exchange, 500, "{}", "application/json");
                    return;
                }
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}},\"id\":1}",
                        "application/json");
            } else if (body.contains("\"method\":\"tools/list\"")) {
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"find_docs\",\"description\":\"find"
                                + " docs\",\"inputSchema\":{\"type\":\"object\"}}]},\"id\":2}",
                        "application/json");
            } else {
                respond(exchange, 200, "{}", "application/json");
            }
        });
        server.start();

        final var url = "http://localhost:" + server.getAddress().getPort() + "/mcp";
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var manager = new McpManager(
                    configuration(List.of(new McpServerConfiguration(
                            "test", null, List.of(), List.of(), null, url, "key", List.of(), true, List.of()))),
                    mapper,
                    List.of(),
                    httpClient,
                    new ProcessService(),
                    executor);

            // start() must return while the initialize handshake is still blocked on the gate
            manager.start();
            assertTrue(true, "start() must not block on the MCP handshake (returned while handshake in flight)");

            // first real use joins: tools() must wait for the handshake, so it only returns once the
            // server answered (we already held start() non-blocking; now release the gate and observe tools())
            assertTrue(
                    manager.serverStatuses()
                            .contains(new McpManager.ServerStatus("test", McpManager.ServerState.PENDING)),
                    "a server whose handshake has not completed is reported as PENDING");
            initializeGate.countDown();
            assertEquals(1, manager.tools().size(), "tools() must join the pending handshake");
            assertEquals("test_find_docs", manager.tools().get(0).name());
            assertTrue(
                    manager.serverStatuses()
                            .contains(new McpManager.ServerStatus("test", McpManager.ServerState.ENABLED)),
                    "once joined the server reports ENABLED");
            manager.close();
        }
    }

    @Test
    void builtinsAreServedInProcessWithoutMcpWrapping(@TempDir final Path root) {
        final var base = baseline;
        final var manager = new McpManager(
                base,
                mapper,
                List.of(new EmbeddedTool() {
                    @Override
                    public String name() {
                        return "probe";
                    }

                    @Override
                    public String description(final Path workdir) {
                        return "probe tool";
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of("type", "object");
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.READ;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        return ToolResult.error("File not found: nope.txt. Did you mean: nope.md?");
                    }
                }),
                httpClient,
                new ProcessService());

        manager.start();
        final var tools = manager.tools();
        assertEquals(1, tools.size());
        assertEquals("probe", tools.get(0).name());
        final var result = tools.get(0).execute(Map.of(), ToolContext.empty(root, new DisabledSandbox(root)));
        assertFalse(result.success());
        assertEquals("File not found: nope.txt. Did you mean: nope.md?", result.output());
        assertTrue(
                manager.serverStatuses().contains(new McpManager.ServerStatus("yca", McpManager.ServerState.ENABLED)));
        assertEquals(
                List.of("probe"),
                manager.toolsByServer().get("yca").stream()
                        .map(EmbeddedTool::name)
                        .toList());
    }

    @ParameterizedTest
    @CsvSource({"tools,false", "tools,true", "toolsByServer,false", "toolsByServer,true", "call,false", "call,true"})
    void firstUseStartsLazily(final String operation, final boolean async) throws Exception {
        final var initializations = new AtomicInteger();
        bootProbeServer(initializations, new CountDownLatch(0));
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var manager = probeManager(async ? executor : null);
            try {
                assertEquals(
                        List.of(new McpManager.ServerStatus("test", McpManager.ServerState.NOT_STARTED)),
                        manager.serverStatuses());
                assertEquals(0, initializations.get());
                switch (operation) {
                    case "tools" ->
                        assertEquals("test_probe", manager.tools().getFirst().name());
                    case "toolsByServer" ->
                        assertEquals(
                                "test_probe",
                                manager.toolsByServer().get("test").getFirst().name());
                    case "call" -> assertEquals("probe result", manager.call("test", "probe", Map.of()));
                    default -> throw new IllegalArgumentException(operation);
                }
                manager.start();
                assertEquals(1, manager.tools().size());
                assertEquals(1, initializations.get(), "explicit start after lazy use must be a no-op");
            } finally {
                manager.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentStartAndFirstUseInitializeOnlyOnce(final boolean async) throws Exception {
        final var initializations = new AtomicInteger();
        final var initializeGate = new CountDownLatch(1);
        bootProbeServer(initializations, initializeGate);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var manager = probeManager(async ? executor : null);
            final var ready = new CountDownLatch(24);
            final var go = new CountDownLatch(1);
            final var tasks = new ArrayList<Future<?>>();
            try {
                for (int i = 0; i < 24; i++) {
                    final var operation = i % 4;
                    tasks.add(executor.submit(() -> {
                        ready.countDown();
                        assertTrue(go.await(5, TimeUnit.SECONDS));
                        switch (operation) {
                            case 0 -> manager.start();
                            case 1 -> assertEquals(1, manager.tools().size());
                            case 2 ->
                                assertEquals(
                                        1, manager.toolsByServer().get("test").size());
                            case 3 -> assertEquals("probe result", manager.call("test", "probe", Map.of()));
                            default -> throw new IllegalStateException();
                        }
                        return null;
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                go.countDown();
                initializeGate.countDown();
                for (final var task : tasks) {
                    task.get(10, TimeUnit.SECONDS);
                }
                assertEquals(1, initializations.get());
                manager.reset();
                assertEquals(2, initializations.get(), "reset must still restart synchronously");
                assertEquals(1, manager.tools().size());
                assertEquals(2, initializations.get());
            } finally {
                go.countDown();
                initializeGate.countDown();
                manager.close();
            }
        }
    }

    @Test
    void unusedManagerStatusAndCloseDoNotStartServers() throws Exception {
        final var initializations = new AtomicInteger();
        bootProbeServer(initializations, new CountDownLatch(0));
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var manager = probeManager(executor);
            assertEquals(
                    List.of(new McpManager.ServerStatus("test", McpManager.ServerState.NOT_STARTED)),
                    manager.serverStatuses());
            manager.close();
            assertEquals(0, initializations.get());
        }
        final var local = new McpManager(baseline, mapper, List.of(), httpClient, null);
        assertEquals(
                List.of(new McpManager.ServerStatus("yca", McpManager.ServerState.NOT_STARTED)),
                local.serverStatuses());
        local.close();
    }

    private McpManager probeManager(final ExecutorService executor) {
        return new McpManager(
                configuration(List.of(new McpServerConfiguration(
                        "test",
                        null,
                        List.of(),
                        List.of(),
                        null,
                        "http://localhost:" + server.getAddress().getPort() + "/mcp",
                        null,
                        List.of(),
                        true,
                        List.of()))),
                mapper,
                List.of(),
                httpClient,
                new ProcessService(),
                executor);
    }

    private void bootProbeServer(final AtomicInteger initializations, final CountDownLatch initializeGate)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"method\":\"initialize\"")) {
                initializations.incrementAndGet();
                try {
                    if (!initializeGate.await(5, TimeUnit.SECONDS)) {
                        respond(exchange, 500, "{}", "application/json");
                        return;
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    respond(exchange, 500, "{}", "application/json");
                    return;
                }
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}},\"id\":1}",
                        "application/json");
            } else if (body.contains("\"method\":\"tools/list\"")) {
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"probe\",\"description\":\"probe\",\"inputSchema\":{\"type\":\"object\"}}]},\"id\":2}",
                        "application/json");
            } else if (body.contains("\"method\":\"tools/call\"")) {
                respond(
                        exchange,
                        200,
                        mapper.toString(Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                ((Map<?, ?>) mapper.fromString(Object.class, body)).get("id"),
                                "result",
                                Map.of("content", List.of(Map.of("type", "text", "text", "probe result"))))),
                        "application/json");
            } else {
                respond(exchange, 200, "{}", "application/json");
            }
        });
        server.start();
    }

    private CodingAgentConfiguration configuration(final List<McpServerConfiguration> servers) {
        return configuration(servers, 10);
    }

    private CodingAgentConfiguration configuration(final List<McpServerConfiguration> servers, final int timeout) {
        final var base = baseline;
        return new CodingAgentConfiguration(
                base.storageHome(),
                base.home(),
                base.workingDir(),
                base.projectDir(),
                base.provider(),
                base.model(),
                base.llm(),
                base.compaction(),
                base.pruning(),
                base.compression(),
                base.memory(),
                base.todos(),
                base.optimize(),
                base.permissions(),
                base.sandbox(),
                new McpConfiguration(true, servers, timeout),
                base.mavenRepositoryKnowledge(),
                base.structuralCodeGraph(),
                base.sessions(),
                base.skills(),
                base.agents(),
                base.mode(),
                base.httpClient(),
                new ToolsConfiguration(
                        new BuiltinToolsConfiguration(
                                false,
                                base.tools().builtin().enabledTools(),
                                base.tools().builtin().disabledTools()),
                        base.tools().spawnSubagent(),
                        base.tools().websearch(),
                        base.tools().manageContext(),
                        base.tools().parallelReadonly(),
                        base.tools().maxOutputChars(),
                        base.tools().repeatSuccessThreshold(),
                        base.tools().alwaysSuggestMavenKnowledge(),
                        base.tools().shell(),
                        base.tools().bashOverrides()),
                base.subagents(),
                base.schedulerThreads(),
                new MetricsConfiguration(true, true, MetricsDisplayMode.COMPACT),
                base.maxLineLength(),
                base.resumeAliases(),
                base.slashcommands(),
                base.prompt(),
                base.labels(),
                base.logging(),
                base.hooks(),
                base.notifications(),
                null, // secrets (default redaction)
                null);
    }

    private static void respond(
            final HttpExchange exchange, final int status, final String body, final String contentType) {
        try {
            exchange.getRequestBody().readAllBytes();
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
