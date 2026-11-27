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
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@FusionSupport(modules = AcpConfigurationTestModule.class)
class RemoteMcpTest {
    @Fusion
    private CodingAgentConfiguration configuration;

    @ParameterizedTest
    @EnumSource(McpManager.RemoteTransport.class)
    @Timeout(30)
    void remoteSessionsArePrivateAndDiscoveryFailureReapsEarlierChildren(
            final McpManager.RemoteTransport transport, @TempDir final Path tmp) throws Exception {
        try (final var mapper = CodecsTest.mapper();
                final var http = new HttpClientProducer()
                        .httpClient(configuration, new NativeProviderConfiguration(null, null));
                final var remote = new RemoteMcp(mapper)) {
            final var manager = new McpManager(configuration, mapper, List.of(), http, null);
            final var url = remote.url(transport.name().toLowerCase(Locale.ROOT));
            final var firstPermissions = new PermissionService(configuration, tmp);
            final var secondPermissions = new PermissionService(configuration, tmp);
            firstPermissions.setMode(AgentMode.BUILD);
            secondPermissions.setMode(AgentMode.BUILD);
            final var firstContext = new ToolContext(
                    "first",
                    null,
                    null,
                    firstPermissions,
                    null,
                    null,
                    null,
                    null,
                    null,
                    () -> false,
                    null,
                    () -> false,
                    tmp,
                    new DisabledSandbox(tmp));
            final var secondContext = new ToolContext(
                    "second",
                    null,
                    null,
                    secondPermissions,
                    null,
                    null,
                    null,
                    null,
                    null,
                    () -> false,
                    null,
                    () -> false,
                    tmp,
                    new DisabledSandbox(tmp));
            // Identical name and destination, but private clients, headers, and negotiated session IDs.
            try (final var first = manager.openSession(
                            List.of(new McpManager.RemoteServer("same-name", transport, url, remote.headers("first"))),
                            tmp);
                    final var second = manager.openSession(
                            List.of(new McpManager.RemoteServer("same-name", transport, url, remote.headers("second"))),
                            tmp)) {
                assertEquals(1, first.tools().size());
                assertEquals(1, second.tools().size());
                assertEquals(
                        first.tools().getFirst().name(),
                        second.tools().getFirst().name());
                assertFalse(first.tools().getFirst().readonly(), "Client tools require consent on remote transports");
                assertEquals(
                        "MCP_OK:first",
                        first.tools().getFirst().execute(Map.of(), firstContext).output());
                assertEquals(
                        "MCP_OK:second",
                        second.tools()
                                .getFirst()
                                .execute(Map.of(), secondContext)
                                .output());
                first.close();
                assertEquals(
                        "MCP_OK:second",
                        second.tools()
                                .getFirst()
                                .execute(Map.of(), secondContext)
                                .output(),
                        "Closing one session must not close another session's transport/shared HTTP client");
                assertEquals(1, remote.calls("first"));
                assertEquals(2, remote.calls("second"));
                secondPermissions.setMode(AgentMode.PLAN);
                assertFalse(second.tools()
                        .getFirst()
                        .execute(Map.of(), secondContext)
                        .success());
                assertEquals(2, remote.calls("second"), "PLAN must not invoke the remote server");
                remote.assertSessions(2);
            }
            if (transport == McpManager.RemoteTransport.HTTP) {
                remote.assertHttpClosed(2);
            }

            final var pid = tmp.resolve("rollback-pid");
            try {
                assertThrows(IOException.class, () -> {
                    try (final var unexpected = manager.openSession(
                            List.of(
                                    new McpManager.StdioServer(
                                            "earlier-child",
                                            List.of(
                                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                                            .toString(),
                                                    "-cp",
                                                    System.getProperty("java.class.path"),
                                                    FakeMcp.class.getName(),
                                                    pid.toString()),
                                            List.of("ACP_TEST_VALUE=rollback")),
                                    new McpManager.RemoteServer("broken", transport, url, remote.headers("broken"))),
                            tmp)) {
                        fail("Broken remote discovery unexpectedly succeeded");
                    }
                });
                final var child = Long.parseLong(Files.readString(pid));
                assertFalse(
                        ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false),
                        "Remote discovery failure must roll back already-started stdio children");
                assertEquals(0, remote.calls("broken"));
                remote.assertSessions(3);
                if (transport == McpManager.RemoteTransport.HTTP) {
                    remote.assertHttpClosed(3);
                }
            } finally {
                if (Files.exists(pid)) {
                    ProcessHandle.of(Long.parseLong(Files.readString(pid))).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
        }
    }
}
