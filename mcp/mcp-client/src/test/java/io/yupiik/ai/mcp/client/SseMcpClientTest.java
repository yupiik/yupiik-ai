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

import static io.yupiik.ai.mcp.client.RemoteMcpTestServer.empty;
import static io.yupiik.ai.mcp.client.RemoteMcpTestServer.event;
import static io.yupiik.ai.mcp.client.RemoteMcpTestServer.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import io.yupiik.ai.mcp.test.TestJsonMapper;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(TestJsonMapper.class)
class SseMcpClientTest {
    @Test
    void concurrentCallsIgnoreUnmatchedIdsAndKeepStructuredContent(final JsonMapper mapper) throws Exception {
        final var stream = new AtomicReference<HttpExchange>();
        final var release = new CountDownLatch(1);
        final var calls = new ConcurrentHashMap<String, Object>();
        final var received = new AtomicInteger();
        final var workers = Executors.newVirtualThreadPerTaskExecutor();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                stream.set(exchange);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                event(exchange, "endpoint", "/messages");
                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
                return;
            }
            final var message = request(mapper, exchange);
            empty(exchange, 202);
            if ("initialize".equals(message.get("method"))) {
                event(
                        stream.get(),
                        "message",
                        "{\"jsonrpc\":\"2.0\",\"id\":" + message.get("id")
                                + ",\"result\":{\"protocolVersion\":\"2024-11-05\"}}");
            } else if ("tools/call".equals(message.get("method"))) {
                calls.put((String) ((Map<?, ?>) message.get("params")).get("name"), message.get("id"));
                if (received.incrementAndGet() == 2) {
                    event(stream.get(), "message", "{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{}}");
                    event(
                            stream.get(),
                            "message",
                            "{\"jsonrpc\":\"2.0\",\"id\":\"" + calls.get("first") + "\",\"result\":{}}");
                    event(
                            stream.get(),
                            "message",
                            "{\"jsonrpc\":\"2.0\",\"id\":" + calls.get("second")
                                    + ",\"result\":{\"content\":[],\"structuredContent\":{\"answer\":42}}}");
                    event(
                            stream.get(),
                            "message",
                            "{\"jsonrpc\":\"2.0\",\"id\":" + calls.get("first")
                                    + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"first result\"}]}}");
                }
            }
        })) {
            final var client = server.sse();
            client.initialize();
            final var first = workers.submit(() -> client.callTool("first", Map.of()));
            final var second = workers.submit(() -> client.callTool("second", Map.of()));
            assertEquals("first result", first.get(5, TimeUnit.SECONDS));
            assertEquals("{\"answer\":42}", second.get(5, TimeUnit.SECONDS));
            assertEquals(2, received.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void persistentGetAdvertisesPostEndpointAndCarriesAllResponses(final JsonMapper mapper) throws Exception {
        final var stream = new AtomicReference<HttpExchange>();
        final var release = new CountDownLatch(1);
        final var pingReply = new CompletableFuture<Map<?, ?>>();
        final var unsupportedReply = new CompletableFuture<Map<?, ?>>();
        final var gets = new AtomicInteger();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
                    assertEquals("Bearer legacy", exchange.getRequestHeaders().getFirst("Authorization"));
                    if ("GET".equals(exchange.getRequestMethod())) {
                        gets.incrementAndGet();
                        stream.set(exchange);
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        event(exchange, "endpoint", "/messages?session=legacy");
                        try {
                            release.await();
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            exchange.close();
                        }
                        return;
                    }
                    assertEquals(
                            "/messages?session=legacy", exchange.getRequestURI().toString());
                    final var message = request(mapper, exchange);
                    if (message.containsKey("result") || message.containsKey("error")) {
                        if ("ping-id".equals(message.get("id"))) {
                            pingReply.complete(message);
                        } else {
                            unsupportedReply.complete(message);
                        }
                        empty(exchange, 202);
                        return;
                    }
                    final Object value;
                    switch (String.valueOf(message.get("method"))) {
                        case "initialize" -> value = Map.of("protocolVersion", "2024-11-05");
                        case "tools/list" -> {
                            assertEquals(
                                    "2024-11-05", exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                            event(
                                    stream.get(),
                                    "message",
                                    "{\"jsonrpc\":\"2.0\",\"id\":\"ping-id\",\"method\":\"ping\"}");
                            event(
                                    stream.get(),
                                    "message",
                                    "{\"jsonrpc\":\"2.0\",\"id\":\"unsupported\",\"method\":\"roots/list\"}");
                            value = Map.of(
                                    "tools", List.of(Map.of("name", "tool", "inputSchema", Map.of("type", "object"))));
                        }
                        case "tools/call" ->
                            value = Map.of("content", List.of(Map.of("type", "text", "text", "legacy result")));
                        default -> {
                            empty(exchange, 202);
                            return;
                        }
                    }
                    empty(exchange, 202);
                    final var json = new String(
                            mapper.toBytes(Map.of("jsonrpc", "2.0", "id", message.get("id"), "result", value)),
                            StandardCharsets.UTF_8);
                    // Multiline data, comments and CRLF are deliberately used throughout this fixture.
                    event(stream.get(), "message", json.replace("\"result\":", "\n\"result\":"));
                });
                final var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            final var client = server.sse();
            worker.submit(() -> {
                        client.initialize();
                        assertEquals("tool", client.listTools().getFirst().name());
                        assertEquals("legacy result", client.callTool("tool", Map.of()));
                        return null;
                    })
                    .get(5, TimeUnit.SECONDS);
            assertEquals(Map.of(), pingReply.get(5, TimeUnit.SECONDS).get("result"));
            assertEquals(
                    -32601,
                    ((Number) ((Map<?, ?>) unsupportedReply
                                            .get(5, TimeUnit.SECONDS)
                                            .get("error"))
                                    .get("code"))
                            .intValue());
            assertEquals(1, gets.get());
            assertEquals(1, release.getCount());
            client.close();
            assertFalse(server.http.isTerminated());
            assertThrows(IOException.class, client::listTools);
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://example.invalid/messages",
                "https://localhost/messages",
                "//example.invalid/x",
                "/messages#fragment",
                "http://user@localhost/messages"
            })
    void rejectsUnsafeAdvertisedEndpoints(final String endpoint, final JsonMapper mapper) throws Exception {
        final var posts = new AtomicInteger();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                event(exchange, "endpoint", endpoint);
                exchange.close();
            } else {
                posts.incrementAndGet();
                empty(exchange, 500);
            }
        })) {
            assertThrows(IOException.class, server.sse()::initialize);
            assertEquals(0, posts.get());
        }
    }

    @Test
    void reportsGetHttpErrorEvenWithAnEmptyBody(final JsonMapper mapper) throws Exception {
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> empty(exchange, 403))) {
            assertEquals(
                    403,
                    assertThrows(McpHttpException.class, server.sse()::initialize)
                            .statusCode());
        }
    }

    @Test
    void closeReleasesPersistentGetWhileAwaitingEndpoint(final JsonMapper mapper) throws Exception {
        final var bodyOpen = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(": waiting\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    bodyOpen.countDown();
                    try {
                        release.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
                final var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            final var client = server.sse();
            final var initializing = worker.submit(() -> {
                client.initialize();
                return null;
            });
            assertTrue(bodyOpen.await(5, TimeUnit.SECONDS));
            client.close();
            assertThrows(ExecutionException.class, () -> initializing.get(5, TimeUnit.SECONDS));
            assertFalse(server.http.isTerminated());
        } finally {
            release.countDown();
        }
    }

    @Test
    void streamEofFailsPendingRequestWithoutWaitingForDeadline(final JsonMapper mapper) throws Exception {
        final var stream = new AtomicReference<HttpExchange>();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
                    if ("GET".equals(exchange.getRequestMethod())) {
                        stream.set(exchange);
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        event(exchange, "endpoint", "/messages");
                    } else {
                        request(mapper, exchange);
                        empty(exchange, 202);
                        stream.get().close();
                    }
                });
                final var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            final var client = server.sse();
            final var result = worker.submit(() -> assertThrows(IOException.class, client::initialize));
            assertTrue(result.get(5, TimeUnit.SECONDS).getMessage().contains("SSE"));
        }
    }
}
