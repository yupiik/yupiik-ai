package io.yupiik.ai.http.server;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.http.server.api.WebServer;
import io.yupiik.fusion.http.server.api.WebServer.Configuration;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OwnedOrBorrowedHttpServerTest {
    @Test
    void fallbackUsesTypedConfigurationAndClosesItsListener() throws Exception {
        final var host = new OwnedOrBorrowedHttpServer(
                Optional.empty(), new HttpHostingConfiguration("127.0.0.1", 0), List.of());
        final var port = host.port();
        try (host;
                final var client = HttpClient.newHttpClient()) {
            assertEquals("127.0.0.1", host.host());
            assertTrue(port > 0);
            assertEquals(
                    404,
                    client.send(
                                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/missing"))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
        }
        try (final var client = HttpClient.newHttpClient()) {
            assertThrows(
                    IOException.class,
                    () -> client.send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding()));
        }
    }

    @Test
    void injectedServerIsBorrowedAndConfigurationIsNotRead() {
        final var awaited = new AtomicInteger();
        final var closed = new AtomicInteger();
        final var configuration = (Configuration) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Configuration.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "port" -> 4321;
                    case "host" -> "borrowed";
                    default -> null;
                });
        final var external = (WebServer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {WebServer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "configuration" -> configuration;
                    case "await" -> {
                        awaited.incrementAndGet();
                        yield null;
                    }
                    case "close" -> {
                        closed.incrementAndGet();
                        yield null;
                    }
                    default -> null;
                });
        try (final var host = new OwnedOrBorrowedHttpServer(Optional.of(external), null, List.of())) {
            assertEquals(4321, host.port());
            assertEquals("borrowed", host.host());
            host.await();
        }
        assertEquals(1, awaited.get());
        assertEquals(0, closed.get());
    }
}
