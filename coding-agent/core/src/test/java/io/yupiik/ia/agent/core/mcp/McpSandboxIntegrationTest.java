/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ai.mcp.client.McpServerTool;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.SessionSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = ConfigurationTestModule.class)
class McpSandboxIntegrationTest {
    @Test
    void configuredMcpIsTrustedByDefaultAndPreservesRawServerIdentity(
            @TempDir final Path root, @Fusion final CodingAgentConfiguration config) {
        final var calls = new AtomicInteger();
        try (final var manager = new McpManager() {
            @Override
            public String call(final String server, final String tool, final Map<String, Object> args) {
                calls.incrementAndGet();
                return server + ":" + tool;
            }
        }) {
            final var description = new McpServerTool("lookup", "", Map.of());
            final var trusted = new McpEmbeddedTool(manager, "docs-api", description);
            final var collision = new McpEmbeddedTool(manager, "docs.api", description);
            // '-' is not sanitized; '.' is. Use two distinct raw names which both contain unsafe chars.
            final var first = new McpEmbeddedTool(manager, "docs.api", description);
            final var second = new McpEmbeddedTool(manager, "docs/api", description);
            assertEquals(first.name(), second.name());
            assertEquals("mcp:docs.api", first.sandboxIntegrationId());
            final var service = new ToolExecutionService(
                    config,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {}
                    },
                    List.of());
            try (final var sandbox = new SessionSandbox(
                    config.sandbox(), root, root, new PermissionService(root) {}, root.resolve("scratch"))) {
                final var context = ToolContext.empty(root, sandbox);
                assertTrue(service.execute(first, Map.of(), context).success());
                assertTrue(service.execute(second, Map.of(), context).success());
                assertTrue(service.execute(trusted, Map.of(), context).success());
                assertTrue(service.execute(collision, Map.of(), context).success());
                assertTrue(service.execute(
                                new McpEmbeddedTool(manager, "docs.api", new McpServerTool("other", "", Map.of())),
                                Map.of(),
                                context)
                        .success());
                assertEquals(5, calls.get(), "MCP servers need no extra sandbox trust entry");
            }
        }
    }
}
