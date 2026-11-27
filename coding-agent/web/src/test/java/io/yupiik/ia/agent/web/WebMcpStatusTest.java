/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestWebJsonMapper.class)
class WebMcpStatusTest {
    @TempDir
    Path home;

    @Nested
    @FusionSupport(modules = StatusModule.class)
    class ConfiguredTransports {
        @Test
        void reportsConfiguredTransportsWithoutStartingServersOrExposingSecrets(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            final var manager = new McpManager(configuration, null, List.of(), null, null);
            final var rpc = rpc(configuration, manager);
            final var statuses = rpc.mcpStatus();
            assertEquals(
                    List.of(
                            new WebJsonRpc.McpDto("yca", "NOT_STARTED", "in-process", null),
                            new WebJsonRpc.McpDto(
                                    "remote",
                                    "NOT_STARTED",
                                    "http",
                                    "https://[redacted]@mcp.example.test:8443/custom%20path/mcp?[redacted]#[redacted]"),
                            new WebJsonRpc.McpDto("local", "DISABLED", "stdio", null),
                            new WebJsonRpc.McpDto("plain", "NOT_STARTED", "http", "http://[::1]:8080/custom/mcp"),
                            new WebJsonRpc.McpDto("unconfigured", "NOT_STARTED", null, null)),
                    statuses);
            final var json = mapper.toString(statuses);
            for (final var secret : List.of(
                    "user",
                    "password",
                    "query-secret",
                    "fragment-secret",
                    "api-secret",
                    "header-secret",
                    "command-secret",
                    "arg-secret",
                    "env-secret",
                    "dir-secret")) {
                assertFalse(json.contains(secret), json);
            }
            assertEquals(statuses, rpc.mcpStatus()); // A status read must not initialize clients.
        }
    }

    @Nested
    @FusionSupport(modules = InvalidUrlsModule.class)
    class InvalidUrls {
        @Test
        void omitsMalformedOrUnsupportedUrlsRatherThanFallingBackToRawConfiguration(
                @Fusion final CodingAgentConfiguration configuration) {
            final var manager = new McpManager(configuration, null, List.of(), null, null);
            final var statuses = rpc(configuration, manager).mcpStatus();
            assertEquals(configuration.mcp().servers().size() + 1, statuses.size());
            for (var index = 0; index < configuration.mcp().servers().size(); index++) {
                final var remote = statuses.get(index + 1);
                assertEquals("http", remote.transport());
                assertNull(
                        remote.url(), configuration.mcp().servers().get(index).url());
            }
        }
    }

    @Nested
    @FusionSupport(modules = WebConfigurationTestModule.class)
    class Reset {
        @Test
        void resetStillDelegatesNamedAndAllRequests(@Fusion final CodingAgentConfiguration configuration) {
            final var calls = new ArrayList<String>();
            final var manager = new McpManager(configuration, null, List.of(), null, null) {
                @Override
                public void reset() {
                    calls.add("all");
                }

                @Override
                public void reset(final String name) {
                    calls.add(name);
                }
            };
            final var rpc = rpc(configuration, manager);
            assertEquals(Map.of("reset", true), rpc.mcpReset("remote"));
            rpc.mcpReset(null);
            rpc.mcpReset("");
            rpc.mcpReset("  ");
            assertEquals(List.of("remote", "all", "all", "all"), calls);
        }
    }

    public static class StatusModule extends WebConfigurationTestModule {
        public StatusModule() {
            super(Map.ofEntries(
                    Map.entry("yca.tools.builtin.enabled", "true"),
                    Map.entry("yca.mcp.servers.length", "4"),
                    Map.entry("yca.mcp.servers.0.name", "remote"),
                    Map.entry(
                            "yca.mcp.servers.0.url",
                            "https://user:password@mcp.example.test:8443/custom%20path/mcp?token=query-secret#fragment-secret"),
                    Map.entry("yca.mcp.servers.0.apiKey", "api-secret"),
                    Map.entry("yca.mcp.servers.0.headers", "Authorization=header-secret"),
                    Map.entry("yca.mcp.servers.0.command", "ignored-command-secret"),
                    Map.entry("yca.mcp.servers.1.name", "local"),
                    Map.entry("yca.mcp.servers.1.command", "command-secret"),
                    Map.entry("yca.mcp.servers.1.args", "--token,arg-secret"),
                    Map.entry("yca.mcp.servers.1.env", "TOKEN=env-secret"),
                    Map.entry("yca.mcp.servers.1.dir", "/dir-secret"),
                    Map.entry("yca.mcp.servers.1.enabled", "false"),
                    Map.entry("yca.mcp.servers.2.name", "plain"),
                    Map.entry("yca.mcp.servers.2.url", "http://[::1]:8080/custom/mcp"),
                    Map.entry("yca.mcp.servers.3.name", "unconfigured")));
        }
    }

    public static class InvalidUrlsModule extends WebConfigurationTestModule {
        public InvalidUrlsModule() {
            super(urls());
        }

        private static Map<String, String> urls() {
            final var urls = List.of(
                    "https://user:invalid secret@example.test/mcp",
                    "https://user:secret@/mcp",
                    "file:///secret",
                    "javascript:secret",
                    "https://example.test/mcp?token=%broken");
            final var values = new HashMap<String, String>();
            values.put("yca.tools.builtin.enabled", "true");
            values.put("yca.mcp.servers.length", Integer.toString(urls.size()));
            for (var index = 0; index < urls.size(); index++) {
                values.put("yca.mcp.servers." + index + ".name", "remote-" + index);
                values.put("yca.mcp.servers." + index + ".url", urls.get(index));
            }
            return values;
        }
    }

    private WebJsonRpc rpc(final CodingAgentConfiguration configuration, final McpManager manager) {
        return new WebJsonRpc(
                null,
                null,
                null,
                null,
                null,
                manager,
                configuration,
                null,
                null,
                null,
                null,
                null,
                new SessionRuntimeFactory() {
                    @Override
                    public Path resolveWorkdir(final Path explicitWorkdir, final String resumedSessionId) {
                        return home;
                    }
                });
    }
}
