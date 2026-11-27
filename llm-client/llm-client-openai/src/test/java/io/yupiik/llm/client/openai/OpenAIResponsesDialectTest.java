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
package io.yupiik.llm.client.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Regression coverage for the base {@link OpenAIResponsesDialect} session-affinity emission. */
@ExtendWith(TestJsonMapper.class)
class OpenAIResponsesDialectTest {
    private HttpServer server;
    private HttpClient delegate;
    private ExtendedHttpClient client;
    private final CopyOnWriteArrayList<String> sessionHeaders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> requestIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        delegate = HttpClient.newBuilder().build();
        client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (client != null) {
            client.close();
            delegate.shutdownNow();
            delegate.close();
            client = null;
            delegate = null;
        }
    }

    private static LlmRequest request(final Map<String, Object> extensions) {
        return new LlmRequest(
                "m",
                List.of(LlmMessage.system("be concise"), LlmMessage.user("hi")),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                true,
                false,
                extensions);
    }

    @Test
    void stalledLiveAndErrorBodiesHaveFiniteBudgets(final JsonMapper mapper) throws Exception {
        final var release = new CountDownLatch(1);
        for (final var status : List.of(200, 503)) {
            server.createContext("/stall/" + status, exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("content-type", "text/event-stream");
                    exchange.sendResponseHeaders(status, 0);
                    exchange.getResponseBody().write(": ready\n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        server.start();
        try {
            for (final var status : List.of(200, 503)) {
                final var dialect = new OpenAIResponsesDialect(
                        mapper,
                        client,
                        new ProviderSpec(
                                "http://localhost:" + server.getAddress().getPort() + "/stall/" + status,
                                "key",
                                Duration.ofMillis(500),
                                Map.of(),
                                null),
                        List.of(),
                        false);
                final var opening =
                        dialect.streamRaw(request(Map.of()), () -> false).toCompletableFuture();
                final CompletableFuture<Void> body = new CompletableFuture<>();
                if (status == 200) {
                    opening.get(3, TimeUnit.SECONDS).live().subscribe(new Flow.Subscriber<>() {
                        @Override
                        public void onSubscribe(final Flow.Subscription subscription) {
                            subscription.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(final List<ByteBuffer> item) {
                            // The prelude is not a completed stream.
                        }

                        @Override
                        public void onError(final Throwable failure) {
                            body.completeExceptionally(failure);
                        }

                        @Override
                        public void onComplete() {
                            body.complete(null);
                        }
                    });
                }
                final var failure = assertThrows(
                        ExecutionException.class, () -> (status == 200 ? body : opening).get(3, TimeUnit.SECONDS));
                assertInstanceOf(HttpTimeoutException.class, failure.getCause());
            }
            delegate.shutdown();
            assertTrue(delegate.awaitTermination(Duration.ofSeconds(2)), "expiry must release the HTTP exchanges");
        } finally {
            release.countDown();
        }
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void codexDefaultEmitsSessionIdFollowingEachRequestBody(final JsonMapper mapper) throws Exception {
        server.createContext("/codex/responses", exchange -> {
            sessionHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("session-id")));
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{}");
        });
        server.start();
        final var port = server.getAddress().getPort();
        final var url = "http://localhost:" + port + "/codex/responses";
        final var dialect = new OpenAIResponsesDialect(
                mapper, client, ProviderSpec.of(url, "key"), List.of("session-id", "prompt_cache_key"), false);
        final var keyA = "11111111-1111-1111-1111-111111111111";
        final var keyB = "22222222-2222-2222-2222-222222222222";
        dialect.chat(request(Map.of("prompt_cache_key", keyA)), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        dialect.chat(request(Map.of("prompt_cache_key", keyA)), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        dialect.chat(request(Map.of("prompt_cache_key", keyB)), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        dialect.chat(request(Map.of()), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        // the stable session-id header follows the body's prompt_cache_key; absent when the body carries none
        assertEquals(List.of(keyA, keyA, keyB, "null"), sessionHeaders);
    }

    @Test
    void openaiResponsesDefaultEmitsXSessionId(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/responses", exchange -> {
            sessionHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("x-session-id")));
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{}");
        });
        server.start();
        final var url = "http://localhost:" + server.getAddress().getPort() + "/v1/responses";
        final var dialect = new OpenAIResponsesDialect(
                mapper, client, ProviderSpec.of(url, "key"), List.of("x-session-id", "prompt_cache_key"), false);
        dialect.chat(request(Map.of("prompt_cache_key", "k1")), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(List.of("k1"), sessionHeaders);
    }

    @Test
    void noHeaderWhenDisabledOrEmptyKeys(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/responses", exchange -> {
            sessionHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("session-id")));
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{}");
        });
        server.start();
        final var url = "http://localhost:" + server.getAddress().getPort() + "/v1/responses";
        final var dialect = new OpenAIResponsesDialect(mapper, client, ProviderSpec.of(url, "key"), List.of(), false);
        dialect.chat(request(Map.of("prompt_cache_key", "k1")), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        // no prompt_cache_key header key configured -> no header, even though the body carries the key
        assertEquals(List.of("null"), sessionHeaders);
    }

    @Test
    void explicitCallerHeaderWinsOverTheDerivedSessionId(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/responses", exchange -> {
            sessionHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("x-session-id")));
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{}");
        });
        server.start();
        final var url = "http://localhost:" + server.getAddress().getPort() + "/v1/responses";
        // a caller-owned static/custom header for the same name must not be clobbered by the dialect
        final var spec =
                new ProviderSpec(url, "key", null, Map.of(), builder -> builder.setHeader("x-session-id", "caller"));
        final var dialect =
                new OpenAIResponsesDialect(mapper, client, spec, List.of("x-session-id", "prompt_cache_key"), false);
        dialect.chat(request(Map.of("prompt_cache_key", "k1")), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(List.of("caller"), sessionHeaders);
    }

    @Test
    void xClientRequestIdVariesPerRequestWhenEnabled(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/responses", exchange -> {
            requestIds.add(String.valueOf(exchange.getRequestHeaders().getFirst("x-client-request-id")));
            sessionHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("session-id")));
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{}");
        });
        server.start();
        final var url = "http://localhost:" + server.getAddress().getPort() + "/v1/responses";
        final var dialect = new OpenAIResponsesDialect(
                mapper, client, ProviderSpec.of(url, "key"), List.of("session-id", "prompt_cache_key"), true);
        dialect.chat(request(Map.of("prompt_cache_key", "k1")), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        dialect.chat(request(Map.of("prompt_cache_key", "k1")), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        // same session key on both, fresh request id on each
        assertEquals(List.of("k1", "k1"), sessionHeaders);
        assertEquals(2, requestIds.size());
        assertNotEquals(requestIds.get(0), requestIds.get(1));
        assertTrue(requestIds.get(0) != null && !requestIds.get(0).isBlank());
    }
}
