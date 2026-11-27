/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.SessionSandbox;
import io.yupiik.ia.agent.core.tools.SandboxIntegration;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class ClientMcpSandboxIntegrationTest {
    private void clientServerIsTrustedByDefault(
            final JsonMapper mapper, final Path root, final CodingAgentConfiguration config) throws Exception {
        final var calls = new AtomicInteger();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            final var request = RemoteMcpTestServer.request(mapper, exchange);
            switch (String.valueOf(request.get("method"))) {
                case "initialize" ->
                    RemoteMcpTestServer.result(
                            mapper,
                            exchange,
                            request.get("id"),
                            Map.of(
                                    "protocolVersion",
                                    "2025-06-18",
                                    "capabilities",
                                    Map.of(),
                                    "serverInfo",
                                    Map.of("name", "test", "version", "1")));
                case "tools/list" ->
                    RemoteMcpTestServer.result(
                            mapper,
                            exchange,
                            request.get("id"),
                            Map.of(
                                    "tools",
                                    List.of(Map.of("name", "lookup", "inputSchema", Map.of("type", "object")))));
                case "tools/call" -> {
                    calls.incrementAndGet();
                    RemoteMcpTestServer.result(
                            mapper,
                            exchange,
                            request.get("id"),
                            Map.of("content", List.of(Map.of("type", "text", "text", "client result"))));
                }
                default -> RemoteMcpTestServer.empty(exchange, 202);
            }
        })) {

            try (final var http =
                            new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null));
                    final var manager = new McpManager(config, mapper, List.of(), http, new ProcessService());
                    final var session = manager.openSession(
                            List.of(new McpManager.RemoteServer(
                                    "docs", McpManager.RemoteTransport.HTTP, server.url("/mcp"), Map.of())),
                            root);
                    final var sandbox = new SessionSandbox(
                            config.sandbox(),
                            root,
                            root,
                            new PermissionService(config, root),
                            root.resolve("scratch"))) {
                final var tool = session.tools().getFirst();
                assertEquals("client-mcp:docs", ((SandboxIntegration) tool).sandboxIntegrationId());
                final var service = new ToolExecutionService(
                        config,
                        new Emitter() {
                            @Override
                            public <T> void emit(final T event) {}
                        },
                        List.of());
                final var result = service.execute(tool, Map.of(), ToolContext.empty(root, sandbox));
                assertFalse(result.success());
                // Missing permissions deliberately stops the accepted path before making a client call.
                assertFalse(result.output().contains("Sandbox restriction"));
                assertEquals("Client MCP tools are unavailable in PLAN mode", result.output());
                final var permissions = new PermissionService(config, root);
                permissions.setMode(AgentMode.BUILD);
                final var context = new ToolContext(
                        null,
                        null,
                        null,
                        permissions,
                        null,
                        null,
                        null,
                        null,
                        null,
                        () -> false,
                        null,
                        () -> false,
                        root,
                        sandbox);
                assertTrue(service.execute(tool, Map.of(), context).success());
                permissions.setMode(AgentMode.PLAN);
                assertFalse(service.execute(tool, Map.of(), context).success());
                permissions.setPermissionMode(PermissionMode.FULL_YOLO);
                assertTrue(tool.execute(Map.of(), context).success());
            }
            assertEquals(2, calls.get());
        }
    }

    @Nested
    @FusionSupport(modules = ConfigurationTestModule.class)
    class ConfiguredServer {
        @Test
        void permitsClientExecutionButRetainsPlanGuard(
                final JsonMapper mapper, @TempDir final Path root, @Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            clientServerIsTrustedByDefault(mapper, root, configuration);
        }
    }

    @Nested
    @FusionSupport(modules = ClientServerModule.class)
    class ClientServer {
        @Test
        void permitsClientExecutionButRetainsPlanGuard(
                final JsonMapper mapper, @TempDir final Path root, @Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            clientServerIsTrustedByDefault(mapper, root, configuration);
        }
    }

    public static class ClientServerModule extends ConfigurationTestModule {
        public ClientServerModule() {
            super(Map.of("yca.sandbox.trustedIntegrations", "client-mcp:docs"));
        }
    }
}
