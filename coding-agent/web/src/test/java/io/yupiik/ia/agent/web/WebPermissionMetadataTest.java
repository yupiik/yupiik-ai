/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.SessionSandbox;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(TestWebJsonMapper.class)
@FusionSupport(modules = WebConfigurationTestModule.class)
class WebPermissionMetadataTest {
    @TempDir
    Path workdir;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void metadataTracksEffectiveSandboxStateAcrossPermissionChanges(
            final boolean enabled, final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
        final var permissions = new PermissionService(configuration, workdir);
        final var configured = configuration.sandbox();
        try (final var sandbox = new SessionSandbox(
                        new SandboxConfiguration(
                                enabled,
                                configured.helper(),
                                configured.filesystem(),
                                configured.network(),
                                configured.trustedIntegrations(),
                                configured.macOs(),
                                configured.environment(),
                                configured.unix()),
                        workdir,
                        workdir,
                        permissions,
                        workdir.resolve("scratch"));
                final var manager =
                        new WebSessionManager(configuration, null, null, null, null, null, null, null, mapper, null) {
                            private final SessionRuntime runtime = new SessionRuntime(
                                    new AgentLoop(workdir, sandbox) {
                                        @Override
                                        public ChatSettings chatSettings() {
                                            return ChatSettings.EMPTY;
                                        }
                                    },
                                    permissions,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    workdir,
                                    sandbox);

                            @Override
                            public SessionRuntime runtime(final String id) {
                                return runtime;
                            }

                            @Override
                            public void close() {
                                try {
                                    runtime.close();
                                } finally {
                                    super.close();
                                }
                            }
                        }) {
            final var service =
                    new AgentSessionService(null, null, null, null, null, null, null, null, configuration, null);
            final var rpc = new WebJsonRpc(
                    null,
                    service,
                    manager,
                    null,
                    null,
                    new McpManager(configuration, mapper, List.of(), null, null),
                    configuration,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new SessionRuntimeFactory() {
                        @Override
                        public Path resolveWorkdir(final Path explicitWorkdir, final String resumedSessionId) {
                            return workdir;
                        }
                    });
            for (final var mode : List.of("ask", "auto_edit", "yolo", "full_yolo", "yolo", "ask")) {
                assertEquals(Map.of("permissionMode", mode), rpc.setPermissions("s", mode));
                final var metadata = rpc.sessionMetadata("s");
                assertEquals("s", metadata.sessionId());
                assertEquals(workdir.toString(), metadata.cwd());
                assertEquals(
                        List.of(new WebJsonRpc.McpDto("yca", "NOT_STARTED", "in-process", null)),
                        metadata.mcpServers());
                assertEquals(List.of(), metadata.modelCandidates());
                assertEquals(List.of(), metadata.providerCandidates());
                assertEquals(mode, metadata.permissionMode());
                assertEquals(enabled && !"full_yolo".equals(mode), metadata.sandboxEnabled());
                final var json = mapper.toString(metadata);
                assertTrue(json.contains("\"sandboxEnabled\":" + metadata.sandboxEnabled()), json);
                assertTrue(json.contains("\"permissionMode\":\"" + mode + "\""), json);
                assertFalse(json.contains("FULL_YOLO"), json);
            }
        }
    }
}
