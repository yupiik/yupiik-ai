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

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@FusionSupport(modules = ConfigurationTestModule.class)
class SessionRemoteValidationTest {
    @Test
    void validatesAllDescriptorsBeforeOpeningAnyConnection(@Fusion final CodingAgentConfiguration configuration)
            throws Exception {
        final var requests = new AtomicInteger();
        final var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/mcp", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        http.start();
        try {
            // Null dependencies are deliberate: validation must finish before using any transport.
            final var manager = new McpManager(configuration, null, List.of(), null, null);
            final var first = new McpManager.RemoteServer(
                    "first",
                    McpManager.RemoteTransport.HTTP,
                    "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp",
                    Map.of());
            final var invalid = new McpManager.StdioServer("invalid", List.of(), List.of());
            assertThrows(
                    IllegalArgumentException.class, () -> manager.openSession(List.of(first, invalid), Path.of(".")));
            assertThrows(
                    IllegalArgumentException.class, () -> manager.openSession(List.of(first, first), Path.of(".")));
            assertEquals(0, requests.get());
        } finally {
            http.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "file:///secret",
                "ftp://localhost/mcp",
                "http://user:secret@localhost/mcp",
                "http://localhost/mcp#fragment",
                "http://localhost:65536/mcp",
                "not a URI"
            })
    void rejectsUnsafeUrlsWithoutEchoingThem(final String url, @Fusion final CodingAgentConfiguration configuration) {
        final var manager = new McpManager(configuration, null, List.of(), null, null);
        final var remote = new McpManager.RemoteServer("server", McpManager.RemoteTransport.HTTP, url, Map.of());
        final var error =
                assertThrows(IllegalArgumentException.class, () -> manager.openSession(List.of(remote), Path.of(".")));
        assertFalse(error.toString().contains("secret"));
        assertFalse(remote.toString().contains(url));
    }

    @Test
    void rejectsCaseInsensitiveDuplicatesAndInjectionWithoutEchoingValues(
            @Fusion final CodingAgentConfiguration configuration) {
        final var manager = new McpManager(configuration, null, List.of(), null, null);
        final var headers = new LinkedHashMap<String, String>();
        headers.put("X-Key", "secret");
        headers.put("x-key", "other-secret");
        for (final var invalid : List.of(
                headers,
                Map.of("X-Key", "secret\r\nBad: value"),
                Map.of("Mcp-Session-Id", "secret"),
                Map.of("Host", "secret"))) {
            final var remote = new McpManager.RemoteServer(
                    "server", McpManager.RemoteTransport.SSE, "http://localhost/mcp", invalid);
            final var error = assertThrows(
                    IllegalArgumentException.class, () -> manager.openSession(List.of(remote), Path.of(".")));
            assertFalse(error.toString().contains("secret"));
            assertFalse(remote.toString().contains("secret"));
        }
    }
}
