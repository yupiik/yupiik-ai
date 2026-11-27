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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cuts a chunked HTTP connection mid-chunk after a first complete SSE chunk: the client must
 * surface a {@link MidStreamFailureException} carrying the partial stream state instead of an
 * opaque transport error, so the agent loop can replay the request safely.
 */
@ExtendWith(TestJsonMapper.class)
class ChatClientMidStreamTest {

    @TempDir
    static Path storageHome;

    @Nested
    @FusionSupport(modules = HttpModule.class)
    class CancellationBeforeHeadersReturnsPromptlyAndReleasesLateStream {
        @Test
        void cancellationBeforeHeadersReturnsPromptlyAndReleasesLateStream(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration cfg, @Fusion final HttpServer server)
                throws Exception {
            final var received = new CountDownLatch(1);
            final var sendHeaders = new CountDownLatch(1);
            final var cancelled = new AtomicBoolean();
            server.createContext("/", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    received.countDown();
                    try {
                        if (!sendHeaders.await(5, TimeUnit.SECONDS)) {
                            return;
                        }
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    final var body = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            try {
                try (final var http =
                                new HttpClientProducer().httpClient(cfg, new NativeProviderConfiguration(null, null));
                        final var client = new ChatClient(
                                cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC(), http)) {
                    final var result = CompletableFuture.supplyAsync(() -> assertThrows(
                            IOException.class, () -> client.stream(request(), event -> {}, cancelled::get)));
                    try {
                        assertTrue(received.await(5, TimeUnit.SECONDS), "request must be waiting for headers");
                        cancelled.set(true);
                        assertEquals(
                                "Cancelled", result.get(2, TimeUnit.SECONDS).getMessage());
                    } finally {
                        cancelled.set(true);
                        sendHeaders.countDown();
                    }
                    // maxConcurrent=1: this cannot complete if the abandoned late publisher leaks its permit.
                    final var next = CompletableFuture.supplyAsync(() -> {
                        try {
                            return client.stream(request(), event -> {}, () -> false);
                        } catch (final Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                    assertEquals("assistant", next.get(5, TimeUnit.SECONDS).role());
                }
            } finally {
                sendHeaders.countDown();
                server.stop(0);
            }
        }
    }

    @Nested
    @FusionSupport(modules = SocketModule.class)
    class MidStreamDisconnectSurfacesAsMidStreamFailure {
        @Test
        void midStreamDisconnectSurfacesAsMidStreamFailure(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration cfg, @Fusion final ServerSocket server)
                throws Exception {
            final var sse = String.join(
                    "\n",
                    "data:"
                            + " {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"he\"},\"finish_reason\":null}]}",
                    "",
                    "data:"
                            + " {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"llo\"},\"finish_reason\":null}]}",
                    "",
                    "data: [DONE]",
                    "");
            final var sseBytes = sse.getBytes(StandardCharsets.UTF_8);
            final var firstChunkDelivered = new CountDownLatch(2);
            final var serverDone = new CountDownLatch(1);
            try (server) {
                final var serverThread = new Thread(() -> serveOne(server, sseBytes, firstChunkDelivered, serverDone));
                serverThread.start();

                try (final var http =
                                new HttpClientProducer().httpClient(cfg, new NativeProviderConfiguration(null, null));
                        final var client = new ChatClient(
                                cfg, mapper, new ProviderManager(cfg, mapper), Clock.systemUTC(), http)) {
                    final var deltas = new CopyOnWriteArrayList<LlmStreamEvent>();
                    final Consumer<LlmStreamEvent> onDelta = delta -> {
                        if (delta instanceof TextDelta) {
                            firstChunkDelivered.countDown();
                        }
                        deltas.add(delta);
                    };
                    try {
                        final var failure =
                                assertThrows(IOException.class, () -> client.stream(request(), onDelta, () -> false));
                        assertInstanceOf(MidStreamFailureException.class, failure);
                        assertTrue(
                                ((MidStreamFailureException) failure)
                                        .partialText()
                                        .contains("hello"),
                                "the partial text seen before the cut must be preserved");
                    } finally {
                        assertTrue(serverDone.await(5, TimeUnit.SECONDS), "server thread must finish");
                    }
                    assertTrue(
                            deltas.stream().anyMatch(TextDelta.class::isInstance),
                            "deltas delivered before the cut must still reach the consumer");
                }
            }
        }
    }

    private void serveOne(
            final ServerSocket server,
            final byte[] sseBytes,
            final CountDownLatch firstChunkDelivered,
            final CountDownLatch serverDone) {
        try (final Socket socket = server.accept()) {
            final var in = socket.getInputStream();
            final var head = new StringBuilder();
            for (var c = in.read(); c != -1; c = in.read()) {
                head.append((char) c);
                final var asString = head.toString();
                if (asString.endsWith("\r\n\r\n")) {
                    break;
                }
            }
            final var out = socket.getOutputStream();
            out.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: text/event-stream\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("Transfer-Encoding: chunked\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write((Integer.toHexString(sseBytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(sseBytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            // only cut once the client actually consumed the first chunk, otherwise the failure
            // would happen before any fragment and would not be a mid-stream failure
            firstChunkDelivered.await(5, TimeUnit.SECONDS);
            out.write("5\r\nhel".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (final IOException | InterruptedException e) {
            // the client may already be gone, nothing to do
        } finally {
            serverDone.countDown();
        }
    }

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest(
                "test-model", List.of(Message.user("hi")), null, null, null, null, null, null, null, null);
    }

    public static class HttpModule extends ConfigurationTestModule {
        private final HttpServer server;

        public HttpModule() throws IOException {
            this(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
        }

        private HttpModule(final HttpServer server) {
            super(configurationValues(server.getAddress().getPort()));
            this.server = server;
        }

        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.concat(
                    super.beans(),
                    Stream.of(new ProvidedInstanceBean<>(DefaultScoped.class, HttpServer.class, () -> server)));
        }
    }

    public static class SocketModule extends ConfigurationTestModule {
        private final ServerSocket server;

        public SocketModule() throws IOException {
            this(new ServerSocket(0, 1, InetAddress.getByName("localhost")));
        }

        private SocketModule(final ServerSocket server) {
            super(configurationValues(server.getLocalPort()));
            this.server = server;
        }

        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.concat(
                    super.beans(),
                    Stream.of(new ProvidedInstanceBean<>(DefaultScoped.class, ServerSocket.class, () -> server)));
        }
    }

    private static Map<String, String> configurationValues(final int port) {
        return Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "default"),
                Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:" + port + "/v1/chat/completions"),
                Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                Map.entry("yca.provider.providers.0.model", "test-model"),
                Map.entry("yca.provider.providers.0.api", "completions"),
                Map.entry("yca.provider.providers.0.contextWindow", "128000"),
                Map.entry("yca.provider.providers.0.tuning.reasoningEffort", "medium"),
                Map.entry("yca.provider.providers.0.timeoutSeconds", "5"),
                Map.entry("yca.llm.retriesMaxAttempts", "1"),
                Map.entry("yca.llm.retriesBaseDelayMs", "1"),
                Map.entry("yca.llm.rateLimitRequestsPerMinute", "0"),
                Map.entry("yca.llm.maxConcurrent", "1"),
                Map.entry("yca.llm.retryOnContextOverflow", "true"),
                Map.entry("yca.llm.maxIterations", "250"));
    }
}
