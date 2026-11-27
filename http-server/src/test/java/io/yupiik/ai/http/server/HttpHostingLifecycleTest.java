package io.yupiik.ai.http.server;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.http.server.api.WebServer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HttpHostingLifecycleTest {
    @Test
    void fallbackBindsEphemeralPortAndReleasesAwaiter() throws Exception {
        final var server = new OwnedOrBorrowedHttpServer(
                Optional.empty(), new HttpHostingConfiguration("127.0.0.1", 0), List.of());
        try (final var workers = Executors.newVirtualThreadPerTaskExecutor();
                final var http = HttpClient.newHttpClient()) {
            assertTrue(server.port() > 0);
            assertEquals("127.0.0.1", server.host());
            assertEquals(
                    404,
                    http.send(
                                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()))
                                            .timeout(Duration.ofSeconds(5))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            final var waiting = workers.submit(server::await);
            server.close();
            waiting.get(5, TimeUnit.SECONDS);
            server.close();
        } finally {
            server.close();
        }
    }

    @Test
    void externallyOwnedServerIsPreferredAndNeverClosed() {
        final var awaited = new AtomicBoolean();
        final var external = new WebServer() {
            @Override
            public void close() {
                fail("Borrowed server must not be closed");
            }

            @Override
            public void await() {
                awaited.set(true);
            }

            @Override
            public WebServer.Configuration configuration() {
                return new ExternalConfiguration();
            }
        };
        try (final var host = new OwnedOrBorrowedHttpServer(Optional.of(external), null, List.of())) {
            assertEquals(2468, host.port());
            assertEquals("host", host.host());
            host.await();
            assertTrue(awaited.get());
        }
    }

    @Test
    void failedBindCleansUp() throws Exception {
        try (final var socket = new ServerSocket(0)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> new OwnedOrBorrowedHttpServer(
                            Optional.empty(),
                            new HttpHostingConfiguration("127.0.0.1", socket.getLocalPort()),
                            List.of()));
        }
    }

    @Test
    void interruptedAwaitRestoresInterrupt() throws Exception {
        try (final var server = new OwnedOrBorrowedHttpServer(
                Optional.empty(), new HttpHostingConfiguration("127.0.0.1", 0), List.of())) {
            final var thread = Thread.ofVirtual().start(() -> {
                Thread.currentThread().interrupt();
                server.await();
                assertTrue(Thread.currentThread().isInterrupted());
            });
            thread.join(Duration.ofSeconds(5));
            assertFalse(thread.isAlive());
        }
    }

    private static final class ExternalConfiguration implements WebServer.Configuration {
        @Override
        public WebServer.Configuration fusionServletMapping(final String value) {
            return this;
        }

        @Override
        public WebServer.Configuration utf8Setup(final boolean value) {
            return this;
        }

        @Override
        public WebServer.Configuration base(final String value) {
            return this;
        }

        @Override
        public WebServer.Configuration port(final int value) {
            return this;
        }

        @Override
        public WebServer.Configuration host(final String value) {
            return this;
        }

        @Override
        public WebServer.Configuration accessLogPattern(final String value) {
            return this;
        }

        @Override
        public String host() {
            return "host";
        }

        @Override
        public int port() {
            return 2468;
        }
    }
}
