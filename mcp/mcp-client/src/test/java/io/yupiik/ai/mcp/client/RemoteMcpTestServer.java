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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RemoteMcpTestServer implements AutoCloseable {
    final HttpServer server;
    final JsonMapper mapper;
    final ExtendedHttpClient http;
    private final HttpClient delegate = HttpClient.newHttpClient();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<McpClient> clients = new ArrayList<>();

    RemoteMcpTestServer(final JsonMapper mapper, final HttpHandler handler) throws IOException {
        this.mapper = mapper;
        http = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", handler);
        server.start();
    }

    String url(final String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    HttpMcpClient http() {
        final var client = new HttpMcpClient("test", url("/mcp"), "secret", List.of("X-Test=custom"), 10, mapper, http);
        clients.add(client);
        return client;
    }

    SseMcpClient sse() {
        final var client =
                new SseMcpClient("test", url("/sse"), List.of("Authorization=Bearer legacy"), 10, mapper, http);
        clients.add(client);
        return client;
    }

    static Map<?, ?> request(final JsonMapper mapper, final HttpExchange exchange) throws IOException {
        return (Map<?, ?>) mapper.fromString(
                Object.class, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    static void result(final JsonMapper mapper, final HttpExchange exchange, final Object id, final Object value)
            throws IOException {
        json(
                exchange,
                new String(
                        mapper.toBytes(Map.of("jsonrpc", "2.0", "id", id, "result", value)), StandardCharsets.UTF_8));
    }

    static void json(final HttpExchange exchange, final String json) throws IOException {
        final var bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static void empty(final HttpExchange exchange, final int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    static void event(final HttpExchange exchange, final String type, final String data) throws IOException {
        exchange.getResponseBody()
                .write((": comment\r\nevent: " + type + "\r\ndata: " + data.replace("\n", "\r\ndata: ") + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    @Override
    public void close() {
        clients.forEach(McpClient::close);
        server.stop(0);
        executor.shutdownNow();
        delegate.shutdownNow();
        http.close();
        delegate.close();
    }
}
