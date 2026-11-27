package io.yupiik.fusion.a2a.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
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
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

@FusionSupport
class A2AClientTimeoutTest {
    @Test
    void cancellingAStreamReleasesTheHttpExchange(@Fusion final JsonMapper jsons) throws Exception {
        final var ready = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("content-type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(": ready\n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    ready.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            server.start();
            final var client = new A2AClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), http, jsons);
            final var stream = client.sendStreamingMessage(SendMessageRequest.of(Message.user("hi")), ignored -> {})
                    .toCompletableFuture();
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            assertTrue(stream.cancel(true));
            http.shutdown();
            assertTrue(http.awaitTermination(Duration.ofSeconds(2)), "cancellation must release the HTTP exchange");
            release.countDown();
        } finally {
            release.countDown();
            http.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void headersAndStreamingBodiesHaveFiniteBudgets(@Fusion final JsonMapper jsons) throws Exception {
        final var release = new CountDownLatch(1);
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
                final var http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .build()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    if (exchange.getRequestURI().getPath().endsWith("/a2a")) {
                        exchange.getRequestBody().readAllBytes();
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
            final var client = new A2AClient(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()), http, jsons)
                    .requestTimeout(Duration.ofMillis(300))
                    .streamTimeout(Duration.ofMillis(500));
            final var headers = assertThrows(
                    ExecutionException.class,
                    () -> client.agentCard().toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertInstanceOf(HttpTimeoutException.class, headers.getCause());
            final var stream = assertThrows(
                    ExecutionException.class,
                    () -> client.sendStreamingMessage(SendMessageRequest.of(Message.user("hi")), ignored -> {})
                            .toCompletableFuture()
                            .get(3, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, stream.getCause());
            assertThrows(IllegalArgumentException.class, () -> client.requestTimeout(Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> client.streamTimeout(Duration.ofSeconds(-1)));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
