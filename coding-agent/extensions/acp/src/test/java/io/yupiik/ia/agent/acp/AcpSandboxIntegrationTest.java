/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.SessionSandbox;
import io.yupiik.ia.agent.core.tools.SandboxIntegration;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcpSandboxIntegrationTest {
    private void verifyTrust(final CodingAgentConfiguration config, final boolean trusted, final Path root) {
        final var calls = new AtomicInteger();
        try (final var adapter = new AcpClientTools(
                "session",
                root,
                Map.of("fs", Map.of("readTextFile", true, "writeTextFile", true), "terminal", true),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                (method, params, timeout, cancelled, late) -> {
                    calls.incrementAndGet();
                    assertEquals("fs/read_text_file", method);
                    return CompletableFuture.completedFuture(Map.of("content", "client buffer"));
                },
                (context, id) -> {})) {
            assertEquals(
                    List.of("acp:fs.readTextFile", "acp:fs.writeTextFile", "acp:terminal"),
                    adapter.tools().stream()
                            .map(SandboxIntegration.class::cast)
                            .map(SandboxIntegration::sandboxIntegrationId)
                            .toList());
            final var permissions = new PermissionService(config, root);
            permissions.setMode(AgentMode.BUILD);
            permissions.setPermissionMode(PermissionMode.YOLO);
            final var service = new ToolExecutionService(
                    config,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {}
                    },
                    List.of());
            try (final var sandbox =
                    new SessionSandbox(config.sandbox(), root, root, permissions, root.resolve("scratch"))) {
                final var context = new ToolContext(
                        "session",
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
                        null,
                        root,
                        sandbox);
                final var result = service.execute(adapter.tools().getFirst(), Map.of("path", "buffer.txt"), context);
                assertEquals(trusted, result.success(), result.output());
                assertFalse(service.execute(
                                adapter.tools().get(1), Map.of("path", "buffer.txt", "content", "text"), context)
                        .success());
                assertFalse(service.execute(adapter.tools().get(2), Map.of("command", "echo"), context)
                        .success());
                if (result.success()) {
                    assertEquals("client buffer", result.output());
                    // Trust never bypasses the adapter's independent session/permission checks.
                    assertFalse(service.execute(
                                    adapter.tools().getFirst(),
                                    Map.of("path", "buffer.txt"),
                                    ToolContext.empty(root, sandbox))
                            .success());
                } else {
                    assertTrue(result.output().contains("Sandbox restriction"), result.output());
                }
            }
            assertEquals(trusted ? 1 : 0, calls.get());
        }
    }

    @Nested
    @FusionSupport(modules = UntrustedModule.class)
    class Untrusted {

        @Test
        void trustIsPerClientCapabilityNotToolNameAndNormalPermissionsRemainMandatory(
                @TempDir final Path root, @Fusion final CodingAgentConfiguration config) {
            verifyTrust(config, false, root);
        }
    }

    public static class UntrustedModule extends AcpConfigurationTestModule {
        public UntrustedModule() {
            super(Map.of("yca.sandbox.trustedIntegrations", ""));
        }
    }

    @Nested
    @FusionSupport(modules = ToolNameModule.class)
    class ToolName {

        @Test
        void trustIsPerClientCapabilityNotToolNameAndNormalPermissionsRemainMandatory(
                @TempDir final Path root, @Fusion final CodingAgentConfiguration config) {
            verifyTrust(config, false, root);
        }
    }

    public static class ToolNameModule extends AcpConfigurationTestModule {
        public ToolNameModule() {
            super(Map.of("yca.sandbox.trustedIntegrations", "client_read_text_file"));
        }
    }

    @Nested
    @FusionSupport(modules = WildcardModule.class)
    class Wildcard {

        @Test
        void trustIsPerClientCapabilityNotToolNameAndNormalPermissionsRemainMandatory(
                @TempDir final Path root, @Fusion final CodingAgentConfiguration config) {
            verifyTrust(config, false, root);
        }
    }

    public static class WildcardModule extends AcpConfigurationTestModule {
        public WildcardModule() {
            super(Map.of("yca.sandbox.trustedIntegrations", "acp:*"));
        }
    }

    @Nested
    @FusionSupport(modules = ExactCapabilityModule.class)
    class ExactCapability {

        @Test
        void trustIsPerClientCapabilityNotToolNameAndNormalPermissionsRemainMandatory(
                @TempDir final Path root, @Fusion final CodingAgentConfiguration config) {
            verifyTrust(config, true, root);
        }
    }

    public static class ExactCapabilityModule extends AcpConfigurationTestModule {
        public ExactCapabilityModule() {
            super(Map.of("yca.sandbox.trustedIntegrations", "acp:fs.readTextFile"));
        }
    }
}
