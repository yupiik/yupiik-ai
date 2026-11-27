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
package io.yupiik.ia.agent.core.llm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class LlmRelayTest {
    private static final String SECRET = "upstream-secret-not-for-clients";

    @ParameterizedTest
    @ValueSource(strings = {"/chat/completions", "/responses"})
    void replacesCredentialsAndStripsHeadersInBothDirections(final String route) throws Exception {
        final var received = new AtomicReference<String>();
        try (final var upstream = new Upstream(exchange -> {
                    assertEquals("/v1" + route, exchange.getRequestURI().toString());
                    assertEquals("POST", exchange.getRequestMethod());
                    assertEquals(
                            List.of("Bearer " + SECRET),
                            exchange.getRequestHeaders().get("Authorization"));
                    assertEquals(
                            "application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
                    assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
                    assertNull(exchange.getRequestHeaders().getFirst("X-Api-Key"));
                    assertNull(exchange.getRequestHeaders().getFirst("X-Target-Url"));
                    received.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
                    exchange.getResponseHeaders().add("Set-Cookie", "private=value");
                    exchange.getResponseHeaders().add("X-Secret", SECRET);
                    respond(exchange, 422, "application/json; charset=utf-8", "{\"error\":\"invalid\"}");
                });
                final var relay = relay(
                        loopback(),
                        URI.create(upstream.uri().toString().replace("/chat/completions", route)),
                        SECRET,
                        2);
                final var client = HttpClient.newHttpClient()) {
            final var response = client.send(
                    request(relay, route)
                            .header("Cookie", "untrusted=value")
                            .header("X-Api-Key", "attacker-key")
                            .header("X-Target-Url", "http://example.org/")
                            .header("Content-Type", "text/plain")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"test\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(422, response.statusCode());
            assertEquals("{\"model\":\"test\"}", received.get());
            assertEquals("{\"error\":\"invalid\"}", response.body());
            assertEquals(
                    "application/json",
                    response.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
            assertTrue(response.headers().firstValue("X-Secret").isEmpty());
            assertFalse(response.body().contains(SECRET));
            assertNotEquals(SECRET, relay.accessToken());
            for (final var rejected : List.of(
                    route.equals("/responses") ? "/chat/completions" : "/responses",
                    route + "/compact",
                    route + "/id",
                    route + "?target=other",
                    route + "/",
                    "/v1" + route)) {
                assertEquals(
                        404,
                        client.send(
                                        request(relay, rejected)
                                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode());
            }
        }
    }

    @Test
    void deniesOtherPathsQueriesMethodsAndCredentialsWithoutContactingUpstream() throws Exception {
        final var calls = new AtomicInteger();
        try (final var upstream = new Upstream(exchange -> {
                    calls.incrementAndGet();
                    respond(exchange, 200, "application/json", "{}");
                });
                final var relay = relay(loopback(), upstream.uri(), SECRET, 1);
                final var other = relay(loopback(), upstream.uri(), SECRET, 1);
                final var client = HttpClient.newHttpClient()) {
            assertNotEquals(relay.accessToken(), other.accessToken());
            for (final var path : List.of(
                    "/",
                    "/v1/chat/completions",
                    "/chat/completions/",
                    "/chat/completions?url=http://example.org",
                    "/chat/../chat/completions",
                    "/chat/%2e%2e/chat/completions",
                    "/chat%2fcompletions")) {
                assertEquals(
                        404,
                        client.send(
                                        request(relay, path)
                                                .POST(HttpRequest.BodyPublishers.noBody())
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode());
            }
            assertEquals(
                    405,
                    client.send(
                                    request(relay, "/chat/completions").GET().build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            for (final var token : List.of("", SECRET, other.accessToken(), relay.accessToken() + "x")) {
                final var builder = HttpRequest.newBuilder(endpoint(relay, "/chat/completions"));
                if (!token.isEmpty()) {
                    builder.header("Authorization", "Bearer " + token);
                }
                assertEquals(
                        401,
                        client.send(
                                        builder.POST(HttpRequest.BodyPublishers.noBody())
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode());
            }
            assertEquals(
                    401,
                    client.send(
                                    request(relay, "/chat/completions")
                                            .header("Authorization", "Bearer attacker")
                                            .POST(HttpRequest.BodyPublishers.noBody())
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void acceptsExactRequestLimitAndRejectsLargerFixedAndChunkedBodies() throws Exception {
        final var calls = new AtomicInteger();
        try (final var upstream = new Upstream(exchange -> {
                    assertEquals(8 * 1024 * 1024, exchange.getRequestBody().readAllBytes().length);
                    calls.incrementAndGet();
                    respond(exchange, 200, "application/json", "{}");
                });
                final var relay = relay(loopback(), upstream.uri(), SECRET, 1);
                final var client = HttpClient.newHttpClient()) {
            assertEquals(
                    200,
                    client.send(
                                    request(relay, "/chat/completions")
                                            .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[8 * 1024 * 1024]))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            // Expect/continue avoids racing a large upload against an early Content-Length rejection.
            assertEquals(
                    413,
                    client.send(
                                    request(relay, "/chat/completions")
                                            .expectContinue(true)
                                            .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[8 * 1024 * 1024 + 1]))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            assertEquals(
                    413,
                    client.send(
                                    request(relay, "/chat/completions")
                                            .POST(HttpRequest.BodyPublishers.ofInputStream(
                                                    () -> new ByteArrayInputStream(new byte[8 * 1024 * 1024 + 1])))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void doesNotFollowRedirectsOrForwardLocation() throws Exception {
        final var calls = new AtomicInteger();
        try (final var upstream = new Upstream(exchange -> {
                    calls.incrementAndGet();
                    exchange.getResponseHeaders().set("Location", "/redirect-target");
                    respond(exchange, 307, "text/html", "redirect");
                });
                final var relay = relay(loopback(), upstream.uri(), SECRET, 1);
                final var client = HttpClient.newHttpClient()) {
            final var response = client.send(
                    request(relay, "/chat/completions")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(307, response.statusCode());
            assertTrue(response.headers().firstValue("Location").isEmpty());
            assertEquals(
                    "application/octet-stream",
                    response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void streamsSseWithoutBufferingAndRejectsConcurrentWork() throws Exception {
        final var release = new CountDownLatch(1);
        try (final var upstream = new Upstream(exchange -> {
                    try (exchange) {
                        exchange.getRequestBody().readAllBytes();
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write("data: first\n\n".getBytes(UTF_8));
                        exchange.getResponseBody().flush();
                        try {
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(UTF_8));
                    }
                });
                final var relay = relay(loopback(), upstream.uri(), SECRET, 1);
                final var client = HttpClient.newHttpClient()) {
            try {
                final var response = client.send(
                        request(relay, "/chat/completions")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                try (final var body = response.body()) {
                    assertEquals(
                            "text/event-stream",
                            response.headers().firstValue("Content-Type").orElseThrow());
                    assertEquals("data: first\n\n", new String(body.readNBytes(13), UTF_8));
                    assertEquals(
                            429,
                            client.send(
                                            request(relay, "/chat/completions")
                                                    .POST(HttpRequest.BodyPublishers.noBody())
                                                    .build(),
                                            HttpResponse.BodyHandlers.discarding())
                                    .statusCode());
                    release.countDown();
                    assertEquals("data: [DONE]\n\n", new String(body.readAllBytes(), UTF_8));
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void clientDisconnectCancelsUpstreamAndReleasesPermit() throws Exception {
        final var disconnected = new CountDownLatch(1);
        final var calls = new AtomicInteger();
        try (final var upstream = new Upstream(exchange -> {
                    if (calls.incrementAndGet() > 1) {
                        respond(exchange, 200, "application/json", "{}");
                        return;
                    }
                    try (exchange) {
                        exchange.getRequestBody().readAllBytes();
                        exchange.sendResponseHeaders(200, 0);
                        final var chunk = new byte[16 * 1024];
                        while (true) {
                            exchange.getResponseBody().write(chunk);
                            exchange.getResponseBody().flush();
                        }
                    } catch (final IOException cancelled) {
                        disconnected.countDown();
                    }
                });
                final var relay = relay(loopback(), upstream.uri(), SECRET, 1);
                final var client = HttpClient.newHttpClient()) {
            final var response = client.send(
                    request(relay, "/chat/completions")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (final var body = response.body()) {
                assertTrue(body.read() >= 0);
            }
            assertTrue(disconnected.await(10, TimeUnit.SECONDS));
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int status;
            do {
                status = client.send(
                                request(relay, "/chat/completions")
                                        .POST(HttpRequest.BodyPublishers.noBody())
                                        .build(),
                                HttpResponse.BodyHandlers.discarding())
                        .statusCode();
                if (status == 429) {
                    Thread.sleep(10);
                }
            } while (status == 429 && System.nanoTime() < deadline);
            assertEquals(200, status);
        }
    }

    @Test
    void upstreamFailureReturnsOnlyGenericStatus() throws Exception {
        try (final var upstream = new Upstream(HttpExchange::close);
                final var relay = relay(loopback(), upstream.uri(), SECRET, 1);
                final var client = HttpClient.newHttpClient()) {
            final var response = client.send(
                    request(relay, "/chat/completions")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(502, response.statusCode());
            assertEquals("", response.body());
            assertFalse(response.headers().toString().contains(SECRET));
        }
    }

    @Test
    void capsStreamingResponse() throws Exception {
        try (final var upstream = new Upstream(exchange -> {
                    try (exchange) {
                        exchange.getRequestBody().readAllBytes();
                        exchange.sendResponseHeaders(200, 0);
                        final var chunk = new byte[64 * 1024];
                        for (int i = 0; i < 2049; i++) {
                            exchange.getResponseBody().write(chunk);
                        }
                    } catch (final IOException cancelled) {
                        // Relay closes upstream at its response cap.
                    }
                });
                final var relay = relay(loopback(), upstream.uri(), SECRET, 1);
                final var client = HttpClient.newHttpClient()) {
            final var response = client.send(
                    request(relay, "/chat/completions")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            long received = 0;
            try (final var body = response.body()) {
                final var buffer = new byte[64 * 1024];
                int count;
                try {
                    while ((count = body.read(buffer)) >= 0) {
                        received += count;
                    }
                } catch (final IOException truncated) {
                    // An aborted chunked response may be reported as an IO failure.
                }
            }
            assertEquals(128L * 1024 * 1024, received);
        }
    }

    @Test
    void closingAbortsActiveUpstreamAndIsIdempotent() throws Exception {
        final var disconnected = new CountDownLatch(1);
        try (final var upstream = new Upstream(exchange -> {
                    try (exchange) {
                        exchange.getRequestBody().readAllBytes();
                        exchange.sendResponseHeaders(200, 0);
                        final var chunk = new byte[16 * 1024];
                        while (true) {
                            exchange.getResponseBody().write(chunk);
                            exchange.getResponseBody().flush();
                        }
                    } catch (final IOException cancelled) {
                        disconnected.countDown();
                    }
                });
                final var client = HttpClient.newHttpClient()) {
            final var relay = relay(loopback(), upstream.uri(), SECRET, 1);
            try {
                final var response = client.send(
                        request(relay, "/chat/completions")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                try (final var body = response.body()) {
                    assertTrue(body.read() >= 0);
                    relay.close();
                    relay.close();
                    assertTrue(disconnected.await(10, TimeUnit.SECONDS));
                }
            } finally {
                relay.close();
            }
        }
    }

    @Test
    void validatesConfigurationWithoutLeakingSecrets() throws Exception {
        for (final var uri : List.of(
                "http://example.org/v1/chat/completions",
                "http://localhost/v1/chat/completions",
                "ftp://127.0.0.1/chat/completions",
                "https://user:password@example.org/chat/completions",
                "https://example.org/chat/completions?key=secret",
                "https://example.org/chat/completions#fragment",
                "https://example.org/a/../chat/completions",
                "https://example.org/%2e%2e/chat/completions",
                "https://example.org/../chat/completions",
                "https://example.org:0/chat/completions",
                "https://example.org:99999/chat/completions")) {
            final var failure =
                    assertThrows(IllegalArgumentException.class, () -> relay(loopback(), URI.create(uri), SECRET, 1));
            assertFalse(failure.getMessage().contains(SECRET));
            assertFalse(failure.getMessage().contains(uri));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> relay(loopback(), URI.create("https://example.org/chat/completions"), "bad\r\nsecret", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> relay(loopback(), URI.create("https://example.org/chat/completions"), SECRET, 0));
        try (final var relay = relay(loopback(), URI.create("https://example.org/v1/chat/completions"), SECRET, 1)) {
            assertNotNull(relay.address());
            assertEquals(43, relay.accessToken().length());
        }
    }

    private static LlmRelay relay(
            final InetSocketAddress address, final URI upstream, final String secret, final int concurrency)
            throws IOException {
        return new LlmRelay(
                address,
                upstream,
                secret,
                concurrency,
                ProviderRequestBudget.fromConfiguration(new ProviderBudgetConfiguration(null, null, null)));
    }

    private static InetSocketAddress loopback() {
        return new InetSocketAddress("127.0.0.1", 0);
    }

    private static URI endpoint(final LlmRelay relay, final String path) {
        return URI.create("http://127.0.0.1:" + relay.address().getPort() + path);
    }

    private static HttpRequest.Builder request(final LlmRelay relay, final String path) {
        return HttpRequest.newBuilder(endpoint(relay, path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + relay.accessToken());
    }

    private static void respond(final HttpExchange exchange, final int status, final String type, final String body)
            throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", type);
            final var bytes = body.getBytes(UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static final class Upstream implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private Upstream(final HttpHandler handler) throws IOException {
            server = HttpServer.create(loopback(), 0);
            server.setExecutor(executor);
            server.createContext("/", handler);
            server.start();
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
