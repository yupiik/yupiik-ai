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
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = AcpConfigurationTestModule.class)
class SessionMcpTest {
    @Fusion
    private CodingAgentConfiguration configuration;

    @Test
    @Timeout(30)
    void paginatesRejectsCollisionsAndRollsBackStartup(@TempDir final Path tmp) throws Exception {
        try (final var mapper = CodecsTest.mapper()) {
            final var manager = new McpManager(configuration, mapper, List.of(), null, null);
            final var pid = tmp.resolve("pid");
            final var descriptor = new McpManager.StdioServer(
                    "air",
                    List.of(
                            Path.of(System.getProperty("java.home"), "bin", "java")
                                    .toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            FakeMcp.class.getName(),
                            pid.toString()),
                    List.of("ACP_TEST_VALUE=value with spaces", "ACP_TEST_PADDING=" + (8 * 1024 * 1024 + 1)));
            long child;
            try (final var owned = manager.openSession(List.of(descriptor), tmp)) {
                child = Long.parseLong(Files.readString(pid));
                assertEquals(2, owned.tools().size(), "second tools/list page must be imported");
                final var permissions = new PermissionService(configuration, tmp);
                permissions.setMode(AgentMode.BUILD);
                final var context = new ToolContext(
                        "air",
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
                        tmp,
                        new DisabledSandbox(tmp));
                final var result = owned.tools().getFirst().execute(Map.of(), context);
                assertTrue(result.success(), result.output());
                final var output = result.output();
                assertTrue(output.startsWith("MCP_OK:value with spaces:" + tmp));
                assertTrue(output.length() > 8 * 1024 * 1024, "large MCP responses must not be capped");
                final var base = new ToolRegistry(null) {
                    @Override
                    public List<EmbeddedTool> tools() {
                        return List.of();
                    }
                };
                assertEquals(2, base.withTools(owned.tools()).tools().size());
                assertTrue(base.tools().isEmpty());
                final var duplicates = new ArrayList<>(owned.tools());
                duplicates.add(owned.tools().getFirst());
                assertThrows(IllegalArgumentException.class, () -> base.withTools(duplicates));
                assertTrue(owned.tools().stream().allMatch(t -> t.name().length() <= 64 && !t.readonly()));
            }
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
            assertThrows(
                    IOException.class,
                    () -> manager.openSession(
                            List.of(
                                    descriptor,
                                    new McpManager.StdioServer(
                                            "broken",
                                            List.of(tmp.resolve("does-not-exist")
                                                    .toString()),
                                            List.of())),
                            tmp));
            final var rolledBack = Long.parseLong(Files.readString(pid));
            assertFalse(ProcessHandle.of(rolledBack).map(ProcessHandle::isAlive).orElse(false));
        }
    }
}
