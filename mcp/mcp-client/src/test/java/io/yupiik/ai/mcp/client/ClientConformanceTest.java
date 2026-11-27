package io.yupiik.ai.mcp.client;

import static io.yupiik.ai.mcp.client.RemoteMcpTestServer.empty;
import static io.yupiik.ai.mcp.client.RemoteMcpTestServer.request;
import static io.yupiik.ai.mcp.client.RemoteMcpTestServer.result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.ai.mcp.test.TestJsonMapper;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.client.MCPClient;
import io.yupiik.fusion.mcp.model.ListResourcesResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Mandatory version/header and framing regressions using the client artifact alone, without server classes. */
@ExtendWith(TestJsonMapper.class)
class ClientConformanceTest {
    @Test
    void closingAsyncClientUnblocksPendingSseRead(final JsonMapper mapper) throws Exception {
        final var release = new CountDownLatch(1);
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(": pending\n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
                final var http = HttpClient.newHttpClient();
                final var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            final var client = new MCPClient(URI.create(server.url("/mcp")), http, mapper);
            try {
                final var lines = client.openSse().toCompletableFuture().get(5, TimeUnit.SECONDS);
                final var waiting = client.nextMessage(lines).toCompletableFuture();
                assertFalse(waiting.isDone());
                worker.submit(client::close).get(5, TimeUnit.SECONDS);
                assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                client.close();
            }
        }
    }

    @Test
    void genericFailuresKeepCredentialsOutOfDiagnosticDetail(final JsonMapper mapper) throws Exception {
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            final var message = request(mapper, exchange);
            if ("initialize".equals(message.get("method"))) {
                result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-06-18"));
            } else if ("resources/list".equals(message.get("method"))) {
                RemoteMcpTestServer.json(
                        exchange,
                        "{\"jsonrpc\":\"2.0\",\"id\":" + message.get("id")
                                + ",\"error\":{\"code\":-32603,\"message\":\"secret\"}}");
            } else {
                empty(exchange, 202);
            }
        })) {
            final var client = server.http();
            client.initialize();
            final var failure = assertThrows(
                    McpFailure.class, () -> client.call("resources/list", Map.of(), ListResourcesResponse.class));
            assertFalse(failure.detail().contains("secret"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-11-25", "2025-06-18", "2025-03-26"})
    void asyncClientSendsNegotiatedVersionOnNotificationsCallsAndDelete(final String version, final JsonMapper mapper)
            throws Exception {
        final var subsequent = new AtomicInteger();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
                    if ("DELETE".equals(exchange.getRequestMethod())) {
                        assertEquals(version, exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                        subsequent.incrementAndGet();
                        empty(exchange, 204);
                        return;
                    }
                    final var message = request(mapper, exchange);
                    if ("initialize".equals(message.get("method"))) {
                        exchange.getResponseHeaders().set("Mcp-Session-Id", "example-session");
                        result(
                                mapper,
                                exchange,
                                message.get("id"),
                                Map.of(
                                        "protocolVersion",
                                        version,
                                        "capabilities",
                                        Map.of(),
                                        "serverInfo",
                                        Map.of("name", "fixture", "version", "1")));
                    } else {
                        assertEquals(version, exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                        assertEquals(
                                "example-session", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                        subsequent.incrementAndGet();
                        if (message.containsKey("id")) {
                            result(mapper, exchange, message.get("id"), Map.of());
                        } else {
                            empty(exchange, 202);
                        }
                    }
                });
                final var http = HttpClient.newHttpClient()) {
            try (final var client = new MCPClient(URI.create(server.url("/mcp")), http)) {
                client.initialize().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(
                        200,
                        client.call(2, "ping", "{}")
                                .toCompletableFuture()
                                .get(5, TimeUnit.SECONDS)
                                .statusCode());
            }
            assertEquals(3, subsequent.get());
        }
    }

    @Test
    void asyncClientRejectsFailedHandshakeWithoutInitializedNotification(final JsonMapper mapper) throws Exception {
        final var requests = new AtomicInteger();
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
                    requests.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    empty(exchange, 401);
                });
                final var http = HttpClient.newHttpClient();
                final var client = new MCPClient(URI.create(server.url("/mcp")), http)) {
            assertThrows(
                    ExecutionException.class,
                    () -> client.initialize().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, requests.get());
        }
    }

    @Test
    void asyncSseReaderJoinsAllDataLinesAndIgnoresComments(final JsonMapper mapper) throws Exception {
        try (final var http = HttpClient.newHttpClient();
                final var client = new MCPClient(URI.create("http://localhost:1/mcp"), http, mapper)) {
            assertEquals(
                    "{\n\"method\":\"notifications/tools/list_changed\"\n}",
                    client.nextMessage(List.of(
                                            ": heartbeat",
                                            "event: message",
                                            "data: {",
                                            "data:\"method\":\"notifications/tools/list_changed\"",
                                            "data: }",
                                            "")
                                    .iterator())
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void genericClientReadsSharedResourceModelWithoutServerDependency(final JsonMapper mapper) throws Exception {
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            final var message = request(mapper, exchange);
            if ("initialize".equals(message.get("method"))) {
                result(
                        mapper,
                        exchange,
                        message.get("id"),
                        Map.of(
                                "protocolVersion",
                                "2025-06-18",
                                "capabilities",
                                Map.of("resources", Map.of()),
                                "serverInfo",
                                Map.of("name", "fixture", "version", "1")));
            } else if ("resources/list".equals(message.get("method"))) {
                result(
                        mapper,
                        exchange,
                        message.get("id"),
                        Map.of("resources", List.of(Map.of("uri", "example://one", "name", "one"))));
            } else {
                empty(exchange, 202);
            }
        })) {
            final var client = server.http();
            client.initialize();
            final var resources = client.call("resources/list", Map.of(), ListResourcesResponse.class);
            assertEquals("example://one", resources.resources().getFirst().uri());
        }
    }
}
