package io.yupiik.ai.mcp.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.mcp.client.MCPClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LegacyClientTimeoutTest {
    @ParameterizedTest
    @ValueSource(strings = {"opening", "reading", "closing"})
    void cancellingTheOpenOrTheReaderReleasesTheExchange(final String mode) throws Exception {
        final var sendHeaders = "reading".equals(mode);
        final var ready = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    if (sendHeaders) {
                        exchange.getResponseHeaders().set("content-type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write(": ready\n\n".getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                    }
                    ready.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            server.start();
            final var client = new MCPClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), http);
            try {
                final var opening = client.openSse().toCompletableFuture();
                assertTrue(ready.await(3, TimeUnit.SECONDS));
                final var pending = sendHeaders
                        ? client.nextMessage(opening.get(3, TimeUnit.SECONDS)).toCompletableFuture()
                        : opening;
                if ("closing".equals(mode)) {
                    client.close();
                    assertTrue(pending.isCancelled());
                } else {
                    assertTrue(pending.cancel(true));
                }
                http.shutdown();
                assertTrue(http.awaitTermination(Duration.ofSeconds(2)), "cancellation must release the HTTP exchange");
                release.countDown();
            } finally {
                client.close();
            }
        } finally {
            release.countDown();
            http.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void delayedHeadersAndStalledSseAreBounded() throws Exception {
        final var release = new CountDownLatch(1);
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
                final var http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .build()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    if (exchange.getRequestURI().getPath().equals("/stream")) {
                        exchange.getResponseHeaders().set("content-type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write(": ready\n\n".getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                    }
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            server.start();
            final var root = "http://127.0.0.1:" + server.getAddress().getPort();
            try (final var delayed = new MCPClient(URI.create(root + "/delayed"), http, null)
                            .session("test")
                            .requestTimeout(Duration.ofMillis(300));
                    final var stream = new MCPClient(URI.create(root + "/stream"), http, null)
                            .session("test")
                            .streamTimeout(Duration.ofMillis(400));
                    final var streamBeforeHeaders =
                            new MCPClient(URI.create(root + "/delayed"), http).streamTimeout(Duration.ofMillis(250))) {
                final var failure = assertThrows(
                        ExecutionException.class,
                        () -> delayed.openSse().toCompletableFuture().get(3, TimeUnit.SECONDS));
                assertInstanceOf(HttpTimeoutException.class, failure.getCause());
                final var streamFailure = assertThrows(
                        ExecutionException.class,
                        () -> streamBeforeHeaders
                                .openSse()
                                .toCompletableFuture()
                                .get(3, TimeUnit.SECONDS));
                assertInstanceOf(HttpTimeoutException.class, streamFailure.getCause());
                final var lines = stream.openSse().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertThrows(
                        ExecutionException.class,
                        () -> stream.nextMessage(lines).toCompletableFuture().get(3, TimeUnit.SECONDS));
                assertThrows(IllegalArgumentException.class, () -> stream.requestTimeout(Duration.ZERO));
                assertThrows(IllegalArgumentException.class, () -> stream.streamTimeout(null));
            }
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
