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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Real local HTTP and legacy SSE MCP peer, owned by its test (not by the MCP client). */
final class RemoteMcp implements AutoCloseable {
    static final String SECRET = "never-expose-remote-credential";

    private final JsonMapper mapper;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CountDownLatch stop = new CountDownLatch(1);

    RemoteMcp(final JsonMapper mapper) throws IOException {
        this.mapper = mapper;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    String url(final String transport) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + transport;
    }

    Map<String, String> headers(final String value) {
        return Map.of("Authorization", "Bearer " + SECRET + ":" + value, "X-Test-Value", value);
    }

    long calls(final String value) {
        return channels.values().stream()
                .filter(c -> value.equals(c.value))
                .flatMap(c -> c.methods.stream())
                .filter("tools/call"::equals)
                .count();
    }

    void assertSessions(final int count) {
        assertNull(failure.get(), () -> "Remote MCP fixture failure: " + failure.get());
        assertEquals(count, channels.size(), "Each session must have a private remote MCP session ID");
        for (final var channel : channels.values()) {
            assertEquals(
                    1, channel.methods.stream().filter("initialize"::equals).count());
            assertTrue(channel.methods.contains("notifications/initialized"));
            assertTrue(channel.methods.contains("tools/list"));
            assertEquals(
                    List.of("initialize", "notifications/initialized", "tools/list"),
                    channel.methods.subList(0, 3),
                    "Discovery must follow the MCP initialization handshake");
        }
    }

    void assertHttpClosed(final int count) {
        assertNull(failure.get(), () -> "Remote MCP fixture failure: " + failure.get());
        assertEquals(
                count,
                channels.values().stream()
                        .flatMap(c -> c.methods.stream())
                        .filter("DELETE"::equals)
                        .count(),
                "Closing/rolling back HTTP sessions must delete each negotiated remote session exactly once");
    }

    private void handle(final HttpExchange exchange) throws IOException {
        try (exchange) {
            // Verify credentials on *every* GET/POST, including the server-supplied SSE POST URL.
            final var value = exchange.getRequestHeaders().getFirst("X-Test-Value");
            assertNotNull(value);
            assertEquals(
                    "Bearer " + SECRET + ":" + value,
                    exchange.getRequestHeaders().getFirst("Authorization"));
            final var path = exchange.getRequestURI().getPath();
            if ("DELETE".equals(exchange.getRequestMethod())) {
                assertEquals("/http", path);
                final var channel = channels.get(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                assertNotNull(channel);
                assertEquals(channel.value, value);
                assertEquals(
                        channel.protocolVersion, exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                channel.methods.add("DELETE");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if ("/sse".equals(path)) {
                assertEquals("GET", exchange.getRequestMethod());
                final var channel = new Channel(value, exchange);
                final var id = UUID.randomUUID().toString();
                channels.put(id, channel);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                channel.event("endpoint", "/messages?sessionId=" + id);
                stop.await(); // Keep the GET event stream open independently of all POST exchanges.
                return;
            }
            assertEquals("POST", exchange.getRequestMethod());
            final var request = (Map<?, ?>) mapper.fromString(
                    Object.class, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("2.0", request.get("jsonrpc"));
            final var method = request.get("method").toString();
            final boolean sse = "/messages".equals(path);
            final Channel channel;
            if (sse) {
                final var query = exchange.getRequestURI().getRawQuery();
                assertTrue(query.startsWith("sessionId="));
                channel = channels.get(query.substring("sessionId=".length()));
            } else if ("initialize".equals(method)) {
                assertEquals("/http", path);
                assertNull(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                final var id = UUID.randomUUID().toString();
                channel = new Channel(value, null);
                channels.put(id, channel);
                exchange.getResponseHeaders().set("Mcp-Session-Id", id);
            } else {
                channel = channels.get(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            }
            assertNotNull(channel, "Requests must reuse their own negotiated MCP session");
            assertEquals(channel.value, value, "MCP sessions/credentials must not cross");
            if (!"initialize".equals(method)) {
                assertEquals(
                        channel.protocolVersion, exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
            }
            channel.methods.add(method);
            if ("notifications/initialized".equals(method)) {
                assertNull(request.get("id"));
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            assertNotNull(request.get("id"));
            final Object result =
                    switch (method) {
                        case "initialize" -> {
                            final var params = (Map<?, ?>) request.get("params");
                            assertInstanceOf(String.class, params.get("protocolVersion"));
                            assertInstanceOf(Map.class, params.get("capabilities"));
                            assertInstanceOf(Map.class, params.get("clientInfo"));
                            channel.protocolVersion =
                                    params.get("protocolVersion").toString();
                            yield Map.of(
                                    "protocolVersion",
                                    params.get("protocolVersion"),
                                    "capabilities",
                                    Map.of("tools", Map.of()),
                                    "serverInfo",
                                    Map.of("name", "remote-test", "version", "1"));
                        }
                        case "tools/list" ->
                            Map.of(
                                    "tools",
                                    List.of(Map.of(
                                            "name",
                                            "echo",
                                            "description",
                                            "Echo session-specific value",
                                            "inputSchema",
                                            Map.of("type", "object"))));
                        case "tools/call" -> {
                            assertTrue(channel.methods.contains("notifications/initialized"));
                            final var params = (Map<?, ?>) request.get("params");
                            assertEquals("echo", params.get("name"));
                            assertInstanceOf(Map.class, params.get("arguments"));
                            yield Map.of(
                                    "content",
                                    List.of(Map.of("type", "text", "text", "MCP_OK:" + value)),
                                    "isError",
                                    false);
                        }
                        default -> throw new AssertionError("Unexpected MCP method: " + method);
                    };
            final var response = mapper.toString(
                    "broken".equals(value) && "tools/list".equals(method)
                            ? Map.of(
                                    "jsonrpc",
                                    "2.0",
                                    "id",
                                    request.get("id"),
                                    "error",
                                    Map.of("code", -32603, "message", "Deliberate discovery failure"))
                            : Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            if (sse) {
                exchange.sendResponseHeaders(202, -1);
                channel.event("message", response);
            } else {
                final var bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        } catch (final Throwable error) {
            if (stop.getCount() != 0) {
                failure.compareAndSet(null, error);
            }
        }
    }

    @Override
    public void close() {
        stop.countDown();
        server.stop(0);
        executor.shutdownNow();
        executor.close();
    }

    private static final class Channel {
        private final String value;
        private final HttpExchange events;
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private final ReentrantLock writeLock = new ReentrantLock();
        private volatile String protocolVersion;

        private Channel(final String value, final HttpExchange events) {
            this.value = value;
            this.events = events;
        }

        private void event(final String type, final String data) throws IOException {
            writeLock.lock();
            try {
                events.getResponseBody()
                        .write(("event: " + type + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                events.getResponseBody().flush();
            } finally {
                writeLock.unlock();
            }
        }
    }
}
