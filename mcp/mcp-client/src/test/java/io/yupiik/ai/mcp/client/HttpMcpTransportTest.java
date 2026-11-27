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
import static io.yupiik.ai.mcp.client.RemoteMcpTestServer.json;
import static io.yupiik.ai.mcp.client.RemoteMcpTestServer.request;
import static io.yupiik.ai.mcp.client.RemoteMcpTestServer.result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import io.yupiik.ai.mcp.test.TestJsonMapper;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.client.MCPClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
class HttpMcpTransportTest {
    @ParameterizedTest
    @ValueSource(strings = {"json", "sse", "legacy-sse", "api-json", "api-sse", "api-multiline"})
    void oversizedResponsesFailAndCloseTheTransport(final String mode, final JsonMapper mapper) throws Exception {
        final var disconnected = new CompletableFuture<Void>();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders()
                    .set("Content-Type", mode.endsWith("json") ? "application/json" : "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            final var chunk = ("api-multiline".equals(mode) ? "data: " + "x".repeat(8192) + "\n" : "x".repeat(8192))
                    .getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                // Keep writing beyond the frame budget: the client must close, not drain this response.
                while (!Thread.currentThread().isInterrupted()) {
                    exchange.getResponseBody().write(chunk);
                    exchange.getResponseBody().flush();
                }
            } catch (final IOException expected) {
                disconnected.complete(null);
            }
        })) {
            if (mode.startsWith("api-")) {
                try (final var client = new MCPClient(URI.create(server.url("/mcp")), server.http)) {
                    final var response = "api-json".equals(mode)
                            ? client.post("{}")
                            : client.openSse().thenCompose(client::nextMessage);
                    final var error = assertThrows(
                            ExecutionException.class,
                            () -> response.toCompletableFuture().get(10, TimeUnit.SECONDS));
                    assertFalse(error.getCause() instanceof HttpTimeoutException);
                }
            } else {
                final McpClient client = "legacy-sse".equals(mode) ? server.sse() : server.http();
                final var error = assertThrows(IOException.class, client::initialize);
                assertTrue(error.getMessage().contains("exceeds maximum size"), error::getMessage);
            }
            disconnected.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void consumesMultilineSseBeforeEofAndRepliesToServerRequests(final JsonMapper mapper) throws Exception {
        final var release = new CountDownLatch(1);
        final var replies = new CompletableFuture<Void>();
        final var seen = new AtomicInteger();
        final var stream = new AtomicReference<HttpExchange>();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
                    final var message = request(mapper, exchange);
                    if (message.containsKey("result") || message.containsKey("error")) {
                        final var id = message.get("id");
                        if ("ping-id".equals(id)) {
                            assertEquals(Map.of(), message.get("result"));
                        } else {
                            assertEquals("unsupported", id);
                            assertEquals(-32601, ((Number) ((Map<?, ?>) message.get("error")).get("code")).intValue());
                        }
                        empty(exchange, 202);
                        if (seen.incrementAndGet() == 2) {
                            event(
                                    stream.get(),
                                    "message",
                                    "{\"jsonrpc\":\"2.0\",\n\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}");
                            replies.complete(null);
                        }
                    } else if ("initialize".equals(message.get("method"))) {
                        stream.set(exchange);
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                        exchange.sendResponseHeaders(200, 0);
                        event(exchange, "message", "{\"jsonrpc\":\"2.0\",\"id\":\"ping-id\",\"method\":\"ping\"}");
                        event(
                                exchange,
                                "message",
                                "{\"jsonrpc\":\"2.0\",\"id\":\"unsupported\",\"method\":\"sampling/createMessage\"}");
                        try {
                            release.await();
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            exchange.close();
                        }
                    } else {
                        empty(exchange, 202);
                    }
                });
                final var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            final var client = server.http();
            worker.submit(() -> {
                        client.initialize();
                        return null;
                    })
                    .get(5, TimeUnit.SECONDS);
            replies.get(5, TimeUnit.SECONDS);
            assertEquals(1, release.getCount()); // The server has deliberately not sent EOF.
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"id\":2,\"result\":{}}",
                "{\"jsonrpc\":\"1.0\",\"id\":2,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2.5,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{},\"error\":{\"code\":-1,\"message\":\"bad\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":null}",
                "not-json"
            })
    void rejectsInvalidEnvelopes(final String invalid, final JsonMapper mapper) throws Exception {
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            final var message = request(mapper, exchange);
            if ("initialize".equals(message.get("method"))) {
                result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-06-18"));
            } else if ("tools/list".equals(message.get("method"))) {
                json(exchange, invalid);
            } else {
                empty(exchange, 202);
            }
        })) {
            final var client = server.http();
            client.initialize();
            assertThrows(IOException.class, client::listTools);
        }
    }

    @Test
    void rejectsUnsupportedNegotiatedVersion(final JsonMapper mapper) throws Exception {
        final var attempts = new AtomicInteger();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            final var message = request(mapper, exchange);
            attempts.incrementAndGet();
            result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2099-01-01"));
        })) {
            assertThrows(IOException.class, server.http()::initialize);
            assertEquals(1, attempts.get());
        }
    }

    @Test
    void reportsEmptyHttpFailureRatherThanAcceptingIt(final JsonMapper mapper) throws Exception {
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            exchange.getRequestBody().readAllBytes();
            empty(exchange, 401);
        })) {
            assertEquals(
                    401,
                    assertThrows(McpHttpException.class, server.http()::initialize)
                            .statusCode());
        }
    }

    @Test
    void sendsNegotiatedHeadersPaginatesAndDeletesSessionWithoutClosingSharedClient(final JsonMapper mapper)
            throws Exception {
        final var page = new AtomicInteger();
        final var deleted = new AtomicInteger();
        final var headerFailure = new AtomicReference<String>();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                assertEquals("session", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                deleted.incrementAndGet();
                empty(exchange, 204);
                return;
            }
            final var message = request(mapper, exchange);
            if ("initialize".equals(message.get("method"))) {
                exchange.getResponseHeaders().set("Mcp-Session-Id", "session");
                result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-03-26"));
                return;
            }
            if (!"2025-03-26".equals(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))
                    || !"session".equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))
                    || !"Bearer secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    || !"custom".equals(exchange.getRequestHeaders().getFirst("X-Test"))) {
                headerFailure.set("missing negotiated/configured headers");
            }
            if ("tools/list".equals(message.get("method"))) {
                final var descriptor =
                        Map.of("name", "tool" + page.incrementAndGet(), "inputSchema", Map.of("type", "object"));
                if (page.get() == 1) {
                    result(
                            mapper,
                            exchange,
                            message.get("id"),
                            Map.of("tools", List.of(descriptor), "nextCursor", "next"));
                } else {
                    assertEquals("next", ((Map<?, ?>) message.get("params")).get("cursor"));
                    result(mapper, exchange, message.get("id"), Map.of("tools", List.of(descriptor)));
                }
            } else {
                empty(exchange, 202);
            }
        })) {
            final var client = server.http();
            client.initialize();
            assertEquals(
                    List.of("tool1", "tool2"),
                    client.listTools().stream().map(McpServerTool::name).toList());
            assertEquals(null, headerFailure.get());
            client.close();
            client.close();
            assertEquals(1, deleted.get());
            assertFalse(server.http.isTerminated());
            assertThrows(IOException.class, client::listTools);
        }
    }

    @Test
    void detectsCursorCycles(final JsonMapper mapper) throws Exception {
        final var pages = new AtomicInteger();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            final var message = request(mapper, exchange);
            if ("initialize".equals(message.get("method"))) {
                result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-06-18"));
            } else if ("tools/list".equals(message.get("method"))) {
                pages.incrementAndGet();
                result(mapper, exchange, message.get("id"), Map.of("tools", List.of(), "nextCursor", "cycle"));
            } else {
                empty(exchange, 202);
            }
        })) {
            final var client = server.http();
            client.initialize();
            assertThrows(IOException.class, client::listTools);
            assertEquals(2, pages.get());
        }
    }

    @Test
    void recoversSessionButDoesNotReplayMutatingCall(final JsonMapper mapper) throws Exception {
        final var initializations = new AtomicInteger();
        final var calls = new AtomicInteger();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                empty(exchange, 204);
                return;
            }
            final var message = request(mapper, exchange);
            if ("initialize".equals(message.get("method"))) {
                initializations.incrementAndGet();
                assertEquals(null, exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                exchange.getResponseHeaders().set("Mcp-Session-Id", "session" + initializations.get());
                result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-06-18"));
            } else if ("tools/call".equals(message.get("method"))) {
                calls.incrementAndGet();
                empty(exchange, 404);
            } else {
                empty(exchange, 202);
            }
        })) {
            final var client = server.http();
            client.initialize();
            assertEquals(
                    404,
                    assertThrows(McpHttpException.class, () -> client.callTool("mutate", Map.of()))
                            .statusCode());
            assertEquals(2, initializations.get());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void listSessionRecoveryIsBounded(final JsonMapper mapper) throws Exception {
        final var initializations = new AtomicInteger();
        final var lists = new AtomicInteger();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                empty(exchange, 204);
                return;
            }
            final var message = request(mapper, exchange);
            if ("initialize".equals(message.get("method"))) {
                initializations.incrementAndGet();
                exchange.getResponseHeaders().set("Mcp-Session-Id", "session");
                result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-06-18"));
            } else if ("tools/list".equals(message.get("method"))) {
                lists.incrementAndGet();
                empty(exchange, 404);
            } else {
                empty(exchange, 202);
            }
        })) {
            final var client = server.http();
            client.initialize();
            assertEquals(
                    404, assertThrows(McpHttpException.class, client::listTools).statusCode());
            assertEquals(2, initializations.get());
            assertEquals(2, lists.get());
        }
    }

    @Test
    void toolResponsesHaveNoArbitrarySizeCap(final JsonMapper mapper) throws Exception {
        final var text = "large response ".repeat(100_000);
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            final var message = request(mapper, exchange);
            if ("initialize".equals(message.get("method"))) {
                result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-06-18"));
            } else if ("tools/call".equals(message.get("method"))) {
                result(
                        mapper,
                        exchange,
                        message.get("id"),
                        Map.of("content", List.of(Map.of("type", "text", "text", text))));
            } else {
                empty(exchange, 202);
            }
        })) {
            final var client = server.http();
            client.initialize();
            assertEquals(text, client.callTool("large", Map.of()));
        }
    }

    @Test
    void toolCallsCanRunConcurrently(final JsonMapper mapper) throws Exception {
        final var bothStarted = new CountDownLatch(2);
        final var release = new CountDownLatch(1);
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
                    final var message = request(mapper, exchange);
                    if ("initialize".equals(message.get("method"))) {
                        result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-06-18"));
                    } else if ("tools/call".equals(message.get("method"))) {
                        bothStarted.countDown();
                        try {
                            release.await();
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        result(
                                mapper,
                                exchange,
                                message.get("id"),
                                Map.of("content", List.of(Map.of("type", "text", "text", "done"))));
                    } else {
                        empty(exchange, 202);
                    }
                });
                final var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            final var client = server.http();
            client.initialize();
            final var first = workers.submit(() -> client.callTool("first", Map.of()));
            final var second = workers.submit(() -> client.callTool("second", Map.of()));
            try {
                assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertEquals("done", first.get(5, TimeUnit.SECONDS));
            assertEquals("done", second.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptionOrCloseSendsCancellationAndReleasesOpenBody(final boolean close, final JsonMapper mapper)
            throws Exception {
        final var bodyOpen = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var cancellation = new CompletableFuture<Map<?, ?>>();
        final var failure = new CompletableFuture<Throwable>();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            final var message = request(mapper, exchange);
            if ("initialize".equals(message.get("method"))) {
                result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-06-18"));
            } else if ("tools/call".equals(message.get("method"))) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                bodyOpen.countDown();
                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            } else {
                if ("notifications/cancelled".equals(message.get("method"))) {
                    cancellation.complete(message);
                }
                empty(exchange, 202);
            }
        })) {
            final var client = server.http();
            client.initialize();
            final var caller = Thread.ofVirtual().start(() -> {
                try {
                    client.callTool("mutate", Map.of());
                    failure.complete(new AssertionError("Expected interruption"));
                } catch (final Exception e) {
                    failure.complete(e);
                }
            });
            assertTrue(bodyOpen.await(5, TimeUnit.SECONDS));
            if (close) {
                client.close();
                assertTrue(failure.get(5, TimeUnit.SECONDS) instanceof IOException);
            } else {
                caller.interrupt();
                assertTrue(failure.get(5, TimeUnit.SECONDS) instanceof InterruptedException);
            }
            assertEquals(
                    2,
                    ((Number) ((Map<?, ?>) cancellation.get(5, TimeUnit.SECONDS).get("params")).get("requestId"))
                            .intValue());
            assertFalse(server.http.isTerminated());
        } finally {
            release.countDown();
        }
    }

    @Test
    void closeCancelsAnOperationBlockedReadingItsBody(final JsonMapper mapper) throws Exception {
        final var bodyOpen = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
                    request(mapper, exchange);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(": waiting\n\n".getBytes(StandardCharsets.UTF_8));
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
            final var client = server.http();
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
}
