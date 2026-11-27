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
package io.yupiik.llm.client.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Hardening coverage: multiple system messages and inter-tool narration for the Anthropic dialect. */
@ExtendWith(TestJsonMapper.class)
class AnthropicDialectHardeningTest {

    private HttpServer server;
    private HttpClient delegate;
    private ExtendedHttpClient client;
    private final AtomicReference<String> reqBody = new AtomicReference<>();

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

    @Test
    void multipleSystemMessagesAreConcatenated(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
                            + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));

        final var request = new LlmRequest(
                "m",
                List.of(
                        LlmMessage.system("first instruction"),
                        LlmMessage.system("second instruction"),
                        LlmMessage.user("hi")),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                Map.of());
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(
                reqBody.get().contains("first instruction\\n\\nsecond instruction"),
                "all system messages must reach the wire concatenated, got: " + reqBody.get());
    }

    @Test
    void nonStreamingChatKeepsTextAlongsideToolUse(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/messages",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                                + "\"content\":["
                                + "{\"type\":\"text\",\"text\":\"let me read it\"},"
                                + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"read\",\"input\":{\"path\":\"/a\"}}"
                                + "],\"stop_reason\":\"tool_use\",\"stop_sequence\":null,"
                                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}"));
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));

        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals("let me read it", String.valueOf(response.first().message().content()));
        final var calls = response.first().message().toolCalls();
        assertEquals(1, calls.size());
        assertEquals("read", calls.get(0).function().name());
        assertEquals("tool_calls", response.first().finishReason());
    }

    @Test
    void reconstructedSseKeepsTextAlongsideToolUse(final JsonMapper mapper) throws Exception {
        // a gateway streaming path that emits text before the tool_use: the narration must survive
        // reconstruction (a non-streaming chat against an SSE-returning gateway)
        final var sse = String.join(
                "\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"model\":\"m\",\"id\":\"msg1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"let me check\"}}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"bash\",\"input\":{}}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\\\"ls\\\"}\"}}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "");
        server.createContext("/v1/messages", exchange -> respondSse(exchange, sse));
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));

        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                "let me check",
                String.valueOf(response.first().message().content()),
                "the inter-tool narration must survive SSE reconstruction, got: " + response.first());
        final var calls = response.first().message().toolCalls();
        assertEquals(1, calls.size());
        assertEquals("t1", calls.get(0).id());
        assertEquals("bash", calls.get(0).function().name());
        assertEquals("tool_calls", response.first().finishReason());
    }

    private static LlmRequest request(final boolean stream) {
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
                stream,
                Map.of());
    }

    private String port() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondSse(final HttpExchange exchange, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
