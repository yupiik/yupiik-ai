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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.opencode.OpenCodeFreeDialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class OpenCodeFreeDialectTest {
    private static final Pattern SESSION_ID = Pattern.compile("ses_[0-9a-f]{12}[0-9A-Za-z]{14}");

    @Test
    void bufferedToolRoundTripUsesSseAndPrivateAffinity(final JsonMapper mapper) throws Exception {
        final var bodies = new CopyOnWriteArrayList<String>();
        final var auth = new CopyOnWriteArrayList<String>();
        final var sessions = new CopyOnWriteArrayList<String>();
        final var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(exchange.getRequestHeaders().getFirst("authorization"));
            sessions.add(exchange.getRequestHeaders().getFirst("x-opencode-session"));
            final var response = bodies.size() == 1 ? """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read","arguments":"{\\"path\\":"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"README.md\\"}"}}]},"finish_reason":"tool_calls"}]}

                data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}

                data: [DONE]

                """ : """
                data: {"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}

                data: [DONE]

                """;
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            final var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (final var delegate = HttpClient.newHttpClient();
                final var client =
                        new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate))) {
            final var dialect = new OpenCodeFreeDialect(
                    mapper,
                    client,
                    new ProviderSpec(
                            "http://localhost:" + server.getAddress().getPort() + "/chat/completions",
                            "must-not-be-sent",
                            Duration.ofSeconds(10),
                            Map.of(),
                            builder -> builder.setHeader("x-opencode-session", "explicit")));
            final var first = dialect.chat(request(List.of(LlmMessage.user("read README.md"))))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            final var call = first.first().message().toolCalls().getFirst();
            assertEquals("read", call.function().name());
            assertEquals("{\"path\":\"README.md\"}", call.function().arguments());
            assertEquals("tool_calls", first.first().finishReason());
            assertEquals(10, first.usage().totalTokens());
            final var next = dialect.chat(request(List.of(
                            LlmMessage.user("read README.md"),
                            first.first().message(),
                            new LlmMessage("tool", "content", null, null, "call_1", Map.of()))))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertEquals("done", next.text());
            assertEquals(List.of("Bearer public", "Bearer public"), auth);
            assertEquals(List.of("explicit", "explicit"), sessions);
            assertTrue(bodies.stream()
                    .allMatch(body -> body.contains("\"stream\":true") && !body.contains("prompt_cache_key")));
            assertTrue(bodies.get(1).contains("call_1"));
            assertTrue(bodies.get(1).contains("content"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void derivedAffinityAndCatalogRoute(final JsonMapper mapper) throws Exception {
        final var sessions = new CopyOnWriteArrayList<String>();
        final var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/inference/openai/v1/chat/completions", exchange -> {
            sessions.add(exchange.getRequestHeaders().getFirst("x-opencode-session"));
            exchange.getRequestBody().readAllBytes();
            final var bytes =
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/inference/v1/models", exchange -> {
            final var bytes = "{\"data\":[{\"id\":\"deepseek-v4.1-flash-free\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (final var delegate = HttpClient.newHttpClient();
                final var client =
                        new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate))) {
            final var dialect = new OpenCodeFreeDialect(
                    mapper,
                    client,
                    new ProviderSpec(
                            "http://localhost:" + server.getAddress().getPort()
                                    + "/inference/openai/v1/chat/completions",
                            "ignored",
                            Duration.ofSeconds(10),
                            Map.of(),
                            builder -> {}));
            assertEquals(
                    "ok",
                    dialect.chat(request(List.of(LlmMessage.user("hi"))))
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                            .text());
            assertEquals(1, sessions.size());
            assertTrue(SESSION_ID.matcher(sessions.getFirst()).matches());
            assertEquals(
                    "deepseek-v4.1-flash-free",
                    dialect.models()
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                            .data()
                            .getFirst()
                            .id());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void flavorRoutesAreExplicit() {
        assertEquals(ApiFlavor.OPENCODE_FREE, ApiFlavor.fromConfigurationString("opencode-free"));
        assertEquals(
                ApiFlavor.OPENCODE_FREE,
                ChatRoutes.detectFlavor("https://opencode.ai/inference/openai/v1/chat/completions"));
        assertEquals("opencode-free", ApiFlavor.OPENCODE_FREE.dialectName());
        assertEquals(
                "http://localhost/v1/chat/completions",
                ChatRoutes.chatEndpoint("http://localhost/v1", ApiFlavor.OPENCODE_FREE));
    }

    private LlmRequest request(final List<LlmMessage> messages) {
        return new LlmRequest(
                "test",
                messages,
                null,
                null,
                32,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                Map.of("prompt_cache_key", "private-session"));
    }
}
