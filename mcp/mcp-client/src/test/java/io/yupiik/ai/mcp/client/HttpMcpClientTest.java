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
package io.yupiik.ai.mcp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.ai.mcp.test.TestJsonMapper;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class HttpMcpClientTest {

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        serverState = null;
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private HttpServer server;
    private HttpMcpClient client;
    private Object serverState;

    private JsonMapper mapper;
    // shared per test class: each per-test client spawned/stopped a selector thread
    private static HttpClient delegate;
    private static ExtendedHttpClient httpClient;

    @BeforeAll
    static void bootClient() {
        delegate = HttpClient.newBuilder().build();
        httpClient = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
    }

    @AfterAll
    static void stopClient() {
        httpClient.close();
        // HttpResponse/completion is done at that point: the graceful shutdown can wait forever on a
        // lingering (SSE/keep-alive) connection of the test server, so abort the selector first.
        delegate.shutdownNow();
        delegate.close();
    }

    @BeforeEach
    void boot(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private void start(final boolean sse) throws IOException, InterruptedException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final var contentType = sse ? "text/event-stream" : "application/json";
            if (body.contains("\"method\":\"initialize\"")) {
                respond(
                        exchange,
                        200,
                        wrap(
                                sse,
                                "{\"jsonrpc\":\"2.0\",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{\"listChanged\":true}},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}},\"id\":1}"),
                        contentType);
            } else if (body.contains("\"method\":\"notifications/initialized\"")) {
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            } else if (body.contains("\"method\":\"tools/list\"")) {
                respond(
                        exchange,
                        200,
                        wrap(
                                sse,
                                "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{\"name\":\"find_docs\",\"description\":\"find docs\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}]},\"id\":2}"),
                        contentType);
            } else if (body.contains("\"method\":\"tools/call\"")) {
                respond(
                        exchange,
                        200,
                        wrap(
                                sse,
                                "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello from server\"}]},\"id\":3}"),
                        contentType);
            } else {
                respond(
                        exchange,
                        500,
                        wrap(sse, "{\"error\":{\"code\":-32601,\"message\":\"method not found\"},\"id\":4}"),
                        contentType);
            }
        });
        server.start();
        client = new HttpMcpClient(
                "test",
                "http://localhost:" + server.getAddress().getPort() + "/mcp",
                "key",
                List.of(),
                10,
                mapper,
                httpClient);
        client.initialize();
    }

    @Test
    void initListAndCallFromSse() throws Exception {
        start(true);
        final var tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("find_docs", tools.get(0).name());
        assertEquals("find docs", tools.get(0).description());
        assertEquals(
                "string",
                ((Map<?, ?>) ((Map<?, ?>) tools.get(0).inputSchema().get("properties")).get("q")).get("type"));
        assertEquals("hello from server", client.callTool("find_docs", Map.of("q", "x")));
    }

    @Test
    void initListAndCallFromPlainJson() throws Exception {
        start(false);
        final var tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("find_docs", tools.get(0).name());
        assertEquals("hello from server", client.callTool("find_docs", Map.of()));
    }

    @Test
    void serverErrorIsReported() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 500, "boom", "text/plain");
        });
        server.start();
        client = new HttpMcpClient(
                "test",
                "http://localhost:" + server.getAddress().getPort() + "/mcp",
                "key",
                List.of(),
                10,
                mapper,
                httpClient);
        final var error = assertThrows(McpHttpException.class, client::initialize);
        assertEquals(500, error.statusCode());
        assertTrue(error.getMessage().contains("500"));
    }

    @Test
    void httpErrorExposesStatusAndRetryAfter() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("retry-after", "120");
            respond(exchange, 429, "{\"error\":\"rate limited\"}", "application/json");
        });
        server.start();
        client = new HttpMcpClient(
                "test",
                "http://localhost:" + server.getAddress().getPort() + "/mcp",
                "key",
                List.of(),
                10,
                mapper,
                httpClient);
        final var error = assertThrows(McpHttpException.class, client::initialize);
        assertEquals(429, error.statusCode());
        assertEquals("[redacted]", error.body());
        assertEquals(120_000L, error.retryAfterMillis().longValue());
    }

    @Test
    void httpErrorWithoutRetryAfterHasNoHint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 404, "gone", "text/plain");
        });
        server.start();
        client = new HttpMcpClient(
                "test",
                "http://localhost:" + server.getAddress().getPort() + "/mcp",
                "key",
                List.of(),
                10,
                mapper,
                httpClient);
        final var error = assertThrows(McpHttpException.class, client::initialize);
        assertEquals(404, error.statusCode());
        assertNull(error.retryAfterMillis());
    }

    @Test
    void negotiatesOlderProtocolVersionWhenNewestRejected() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final var contentType = "application/json";
            if (body.contains("\"method\":\"notifications/initialized\"")) {
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            } else if (body.contains("\"protocolVersion\":\"2024-11-05\"")) {
                respond(
                        exchange,
                        200,
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"t\",\"version\":\"1\"}},\"id\":3}",
                        contentType);
            } else {
                respond(
                        exchange,
                        400,
                        "{\"error\":{\"code\":-32601,\"message\":\"unsupported protocol version\"},\"id\":1}",
                        contentType);
            }
        });
        server.start();
        client = new HttpMcpClient(
                "test",
                "http://localhost:" + server.getAddress().getPort() + "/mcp",
                "key",
                List.of(),
                10,
                mapper,
                httpClient);
        client.initialize();
    }

    private static String wrap(final boolean sse, final String json) {
        return sse ? "event: message\ndata: " + json + "\n\n" : json;
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
