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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.DialectNotSupportedException;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmToolCall;
import io.yupiik.llm.client.model.Usage;
import io.yupiik.llm.client.spi.Dialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class AnthropicDialectTest {
    private static int cacheControls(final String body) {
        return (body.length() - body.replace("cache_control", "").length()) / "cache_control".length();
    }

    private HttpServer server;
    private HttpClient delegate;
    private ExtendedHttpClient client;
    private final AtomicReference<String> reqHeaders = new AtomicReference<>();
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
        if (server != null) { // close the server first so pooled keep-alive connections die before the client
            server.stop(0);
            server = null;
        }
        if (client != null) {
            client.close();
            // HttpResponse/completion is done at that point: the graceful shutdown can wait forever on a
            // lingering (SSE/keep-alive) connection of the test server, so abort the selector first.
            delegate.shutdownNow();
            delegate.close();
            client = null;
            delegate = null;
        }
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

    private String port() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static List<LlmStreamEvent> collect(final Flow.Publisher<LlmStreamEvent> publisher) throws Exception {
        final var events = new CopyOnWriteArrayList<LlmStreamEvent>();
        final var done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(final Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final LlmStreamEvent item) {
                events.add(item);
            }

            @Override
            public void onError(final Throwable throwable) {
                error.set(throwable);
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not complete in time");
        if (error.get() != null) {
            throw new AssertionError("stream errored", error.get());
        }
        return events;
    }

    @Test
    void chatPostsToMessagesWithXApiKeyAndVersion(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/messages", exchange -> {
            reqHeaders.set("x-api-key:"
                    + exchange.getRequestHeaders().getFirst("x-api-key") + "|anthropic-version:"
                    + exchange.getRequestHeaders().getFirst("anthropic-version"));
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1,\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":2}}");
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("x-api-key:sk-test|anthropic-version:2023-06-01", reqHeaders.get());
        assertEquals("hello", response.text());
        assertEquals(6, response.usage().totalTokens());
        assertEquals(5L, response.usage().prompt());
        assertEquals(10L, response.usage().contextInput());
        assertEquals(3L, response.usage().cacheRead());
        assertEquals(2L, response.usage().cacheWrite());
        assertNull(response.usage().generationTimeMs(), "a standard Anthropic payload carries no timing");
        final var body = reqBody.get();
        assertTrue(body.contains("\"max_tokens\":64"));
        assertTrue(body.contains("be concise"), body);
        assertTrue(body.contains("cache_control"), "system must carry a cache breakpoint:\n" + body);
        assertTrue(body.contains("\"model\":\"m\""));
    }

    @Test
    void nonStreamingChatSurfacesTheThinkingTokensBreakdown(final JsonMapper mapper) throws Exception {
        // Anthropic-compatible gateways (eg. Azure AI Gateway) report the hidden thinking tokens in
        // output_tokens_details.thinking_tokens; they must reach the neutral usage.reasoningTokens
        server.createContext(
                "/v1/messages",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                                + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"hidden\"},{\"type\":\"text\",\"text\":\"answer\"}],"
                                + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":8,\"output_tokens_details\":{\"thinking_tokens\":6}}}"));
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("answer", response.text());
        assertEquals(6L, response.usage().reasoning(), "thinking_tokens must surface as reasoning tokens");
        assertEquals(8L, response.usage().completion());
        assertEquals(13L, response.usage().total());
    }

    @Test
    void nonStreamingChatWithoutDetailsReportsZeroReasoning(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/messages",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
                                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}"));
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(0L, response.usage().reasoning(), "no breakdown means no reasoning tokens");
    }

    @Test
    void promptCacheCanBeDisabledViaExtensions(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var request = new LlmRequest(
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
                false,
                Map.of("promptCache", "false"));
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertTrue(body.contains("\"system\":\"be concise\""), "plain string system expected:\n" + body);
        assertFalse(body.contains("cache_control"), body);
        assertFalse(body.contains("promptCache"), "the kill-switch key must not leak to the payload:\n" + body);
    }

    @Test
    void volatileTailMessageSplitsIntoTwoBlocksWithSecondBreakpoint(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var request = new LlmRequest(
                "m",
                List.of(
                        LlmMessage.system("be concise"),
                        new LlmMessage(
                                "user",
                                "prompt\n\n<session-state>\nDate: x\n</session-state>",
                                null,
                                null,
                                null,
                                Map.of("volatileTail", true))),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                false,
                Map.of("anthropicSplitBlocks", true));
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        // system + stable prefix breakpoints, plus automatic caching of the conversation
        assertEquals(3, cacheControls(body), "expected system + stable prefix + automatic caching:\n" + body);
        assertTrue(body.contains("<session-state>\\nDate: x\\n</session-state>"), body);
        assertFalse(body.contains("volatileTail"), "the message flag must not leak to the payload:\n" + body);
    }

    @Test
    void autoSplitBlocksStaysInlineOnGatewayHosts(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var request = new LlmRequest(
                "m",
                List.of(
                        LlmMessage.system("be concise"),
                        new LlmMessage(
                                "user",
                                "prompt\n\n<session-state>\nDate: x\n</session-state>",
                                null,
                                null,
                                null,
                                Map.of("volatileTail", true))),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                false,
                null);
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        // no explicit splitBlocks extension -> no split, tail inline, system + automatic caching
        assertEquals(2, cacheControls(body), "expected system + automatic caching:\n" + body);
        assertTrue(body.contains("\"content\":\"prompt\\n\\n<session-state>\\nDate: x\\n</session-state>\""), body);
    }

    @Test
    void anthropicSplitBlocksFalseKeepsInlineTailAndStripsTheFlag(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\","
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var request = new LlmRequest(
                "m",
                List.of(
                        LlmMessage.system("be concise"),
                        new LlmMessage(
                                "user",
                                "prompt\n\n<session-state>\nDate: x\n</session-state>",
                                null,
                                null,
                                null,
                                Map.of("volatileTail", true))),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                false,
                Map.of("anthropicSplitBlocks", false));
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        // the tail rides inline as a plain string; automatic caching remains enabled
        assertEquals(2, cacheControls(body), "expected system + automatic caching:\n" + body);
        assertTrue(body.contains("\"content\":\"prompt\\n\\n<session-state>\\nDate: x\\n</session-state>\""), body);
        assertFalse(body.contains("anthropicSplitBlocks"), "the flag must not leak to the payload:\n" + body);
    }

    @Test
    void streamEmitsTextDeltasAndEnd(final JsonMapper mapper) throws Exception {
        final var sse = String.join(
                "\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hel\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "");
        server.createContext("/v1/messages", exchange -> respondSse(exchange, sse));
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.TextDelta("hel"),
                        new LlmStreamEvent.TextDelta("lo"),
                        new LlmStreamEvent.End("stop", new Usage(5L, 2L, 7L))),
                events);
    }

    @Test
    void cachedInputSurvivesOutputOnlyFinalDelta(final JsonMapper mapper) throws Exception {
        final var sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"m1","role":"assistant","content":[],"usage":{"input_tokens":5,"cache_read_input_tokens":100,"cache_creation_input_tokens":20,"output_tokens":0}}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

                """;
        server.createContext("/v1/messages", exchange -> respondSse(exchange, sse));
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var events = collect(
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS));
        final var usage = ((LlmStreamEvent.End) events.getLast()).usage();
        assertEquals(5, usage.prompt());
        assertEquals(100, usage.cacheRead());
        assertEquals(20, usage.cacheWrite());
        assertEquals(125, usage.contextInput());
        assertEquals(2, usage.completion());
    }

    @Test
    void streamEmitsToolArgumentDeltasPerOfficialProtocol(final JsonMapper mapper) throws Exception {
        // the real API streams tool JSON as content_block_delta with delta.type=input_json_delta
        final var sse = String.join(
                "\n",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"bash\",\"input\":{}}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"ls\\\"}\"}}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}",
                "",
                "");
        server.createContext("/v1/messages", exchange -> respondSse(exchange, sse));
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.ToolCallDelta(1, "t1", "bash", ""),
                        new LlmStreamEvent.ToolCallDelta(1, null, null, "{\"command\":"),
                        new LlmStreamEvent.ToolCallDelta(1, null, null, "\"ls\"}"),
                        new LlmStreamEvent.End("tool_calls", new Usage(5L, 3L, 8L))),
                events);
    }

    @Test
    void streamEmitsThinkingDeltasAndSurfacesThinkingTokens(final JsonMapper mapper) throws Exception {
        // adaptive/enabled thinking streams thinking_delta frames (hidden chain-of-thought) and the
        // message_delta usage reports the reasoning breakdown via output_tokens_details.thinking_tokens
        final var sse = String.join(
                "\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"let me think\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig\"}}",
                "",
                "event: content_block_stop",
                "data: {\"type\":\"content_block_stop\",\"index\":0}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"answer\"}}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":5,\"output_tokens\":8,\"output_tokens_details\":{\"thinking_tokens\":6}}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "");
        server.createContext("/v1/messages", exchange -> respondSse(exchange, sse));
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.ReasoningDelta("let me think"),
                        new LlmStreamEvent.TextDelta("answer"),
                        new LlmStreamEvent.End("stop", new Usage(5L, 8L, 13L, null, null, 6L, null))),
                events);
    }

    @Test
    void modelsListsIds(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/models",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"data\":[{\"type\":\"model\",\"id\":\"a\",\"display_name\":\"A\"},{\"type\":\"model\",\"id\":\"b\",\"display_name\":\"B\"}]}"));
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmModels models = dialect.models().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a", "b"), models.ids());
    }

    @Test
    void modelsSinglePageHasNoCursorParams(final JsonMapper mapper) throws Exception {
        final var queries = new CopyOnWriteArrayList<String>();
        final var hits = new AtomicInteger();
        server.createContext("/v1/models", exchange -> {
            try {
                hits.incrementAndGet();
                final var query = exchange.getRequestURI().getQuery();
                queries.add(query == null ? "" : query);
                respond(
                        exchange,
                        200,
                        "{\"data\":[{\"type\":\"model\",\"id\":\"a\"},{\"type\":\"model\",\"id\":\"b\"}],\"has_more\":false,\"last_id\":\"b\"}");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmModels models = dialect.models().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a", "b"), models.ids());
        assertEquals(1, hits.get(), "single page must be a single fetch");
        assertTrue(queries.get(0).contains("limit=1000"), "page size must be requested, got query: " + queries.get(0));
        assertTrue(
                !queries.get(0).startsWith("limit=") || !queries.get(0).contains("after_id"),
                "first page must not carry an after_id cursor, got query: " + queries.get(0));
    }

    @Test
    void modelsFollowsCursorAcrossTwoPages(final JsonMapper mapper) throws Exception {
        final var queries = new CopyOnWriteArrayList<String>();
        final var hits = new AtomicInteger();
        server.createContext("/v1/models", exchange -> {
            try {
                final var query = exchange.getRequestURI().getQuery();
                queries.add(query == null ? "" : query);
                final int n = hits.incrementAndGet();
                if (n == 1) {
                    respond(
                            exchange,
                            200,
                            "{\"data\":[{\"type\":\"model\",\"id\":\"a\"},{\"type\":\"model\",\"id\":\"b\"}],\"has_more\":true,\"last_id\":\"b\"}");
                } else {
                    respond(
                            exchange,
                            200,
                            "{\"data\":[{\"type\":\"model\",\"id\":\"c\"},{\"type\":\"model\",\"id\":\"d\"}],\"has_more\":false,\"last_id\":\"d\"}");
                }
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmModels models = dialect.models().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a", "b", "c", "d"), models.ids());
        assertEquals(2, hits.get(), "two pages must be fetched");
        final var page2 = queries.get(1);
        assertTrue(page2.contains("after_id=b"), "page 2 must carry after_id=b cursor, got query: " + page2);
    }

    @Test
    void modelsStopsWhenNextPageIsEmptyDespiteHasMore(final JsonMapper mapper) throws Exception {
        final var hits = new AtomicInteger();
        server.createContext("/v1/models", exchange -> {
            try {
                final int n = hits.incrementAndGet();
                if (n == 1) {
                    respond(
                            exchange,
                            200,
                            "{\"data\":[{\"type\":\"model\",\"id\":\"a\"}],\"has_more\":true,\"last_id\":\"a\"}");
                } else {
                    respond(exchange, 200, "{\"data\":[],\"has_more\":true,\"last_id\":\"a\"}");
                }
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmModels models = dialect.models().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a"), models.ids());
        assertEquals(2, hits.get(), "must stop after the empty next page");
    }

    @Test
    void modelsStopsWhenCursorFailsToAdvance(final JsonMapper mapper) throws Exception {
        final var hits = new AtomicInteger();
        server.createContext("/v1/models", exchange -> {
            try {
                final int n = hits.incrementAndGet();
                final String body =
                        switch (n) {
                            case 1 ->
                                "{\"data\":[{\"type\":\"model\",\"id\":\"a\"}],\"has_more\":true,\"last_id\":\"a\"}";
                            default -> // backend echoes the same cursor instead of advancing
                                "{\"data\":[{\"type\":\"model\",\"id\":\"a\"}],\"has_more\":true,\"last_id\":\"a\"}";
                        };
                respond(exchange, 200, body);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmModels models = dialect.models().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a"), models.ids());
        assertEquals(2, hits.get(), "must stop when the cursor does not advance");
    }

    @Test
    void modelsDeduplicatesAcrossPagesKeepingOrder(final JsonMapper mapper) throws Exception {
        final var hits = new AtomicInteger();
        server.createContext("/v1/models", exchange -> {
            try {
                final int n = hits.incrementAndGet();
                if (n == 1) {
                    respond(
                            exchange,
                            200,
                            "{\"data\":[{\"type\":\"model\",\"id\":\"a\"},{\"type\":\"model\",\"id\":\"b\"}],\"has_more\":true,\"last_id\":\"b\"}");
                } else {
                    // page 2 overlaps page 1 on "b" and "a"
                    respond(
                            exchange,
                            200,
                            "{\"data\":[{\"type\":\"model\",\"id\":\"b\"},{\"type\":\"model\",\"id\":\"c\"},{\"type\":\"model\",\"id\":\"a\"}],\"has_more\":false,\"last_id\":\"a\"}");
                }
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmModels models = dialect.models().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a", "b", "c"), models.ids(), "duplicates must be dropped keeping fetched order");
        assertEquals(2, hits.get());
    }

    @Test
    void modelsMaps404ToDialectNotSupported(final JsonMapper mapper) {
        server.createContext("/v1/models", exchange -> {
            try {
                respond(exchange, 404, "not found");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var completion = dialect.models().toCompletableFuture();
        final var failure = failureOf(completion).orElseThrow();
        assertInstanceOf(
                DialectNotSupportedException.class,
                failure,
                () -> "expected DialectNotSupportedException but got " + failure);
    }

    @Test
    void chatMaps404ToDialectNotSupported(final JsonMapper mapper) {
        server.createContext("/v1/messages", exchange -> {
            try {
                respond(exchange, 404, "not found");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var completion = dialect.chat(request(false)).toCompletableFuture();
        final var failure = failureOf(completion).orElseThrow();
        assertInstanceOf(
                DialectNotSupportedException.class,
                failure,
                () -> "expected DialectNotSupportedException but got " + failure);
    }

    @Test
    void mapsToolUseToFunctionCall(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/messages",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                                + "\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"read\",\"input\":{\"path\":\"/a\"}}],"
                                + "\"stop_reason\":\"tool_use\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}"));
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var calls = response.first().message().toolCalls();
        assertEquals(1, calls.size());
        assertEquals("read", calls.get(0).function().name());
        assertEquals("{\"path\":\"/a\"}", calls.get(0).function().arguments());
    }

    @Test
    void exposesNameForRouting(final JsonMapper mapper) {
        final Dialect dialect = new AnthropicDialect(mapper, client, ProviderSpec.of("http://x", "k"));
        assertEquals("anthropic-messages", dialect.name());
    }

    @Test
    void chatReconstructsCompletionFromSseBody(final JsonMapper mapper) throws Exception {
        // an Anthropic-compatible gateway that streams unconditionally (Azure AI Foundry, captured
        // 2026-10-08): chat() must reconstruct the completion from the SSE frames, not fail on 'event:'
        final var sse = String.join(
                "\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-sonnet-5-5\",\"id\":\"msg_011CfpewfcKbiNrxAKGN2bXG\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}             }",
                "",
                "event: ping",
                "data: {\"type\": \"ping\"}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}         }",
                "",
                "event: content_block_stop",
                "data: {\"type\":\"content_block_stop\",\"index\":0     }",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":18,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0,\"output_tokens\":4}     }",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"        }",
                "");
        server.createContext("/v1/messages", exchange -> respondSse(exchange, sse));
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        // text reconstructed from the frames, finish reason mapped, usage carried
        final var choice = response.first();
        assertEquals("ok", String.valueOf(choice.message().content()));
        assertEquals("stop", choice.finishReason());
        assertEquals(18L, response.usage().prompt());
        assertEquals(4L, response.usage().completion());
        assertEquals(22L, response.usage().total());
        // id/model surfaced from message_start (optional but useful for usage tracking)
        assertEquals("claude-sonnet-5-5", response.model());
        assertEquals("msg_011CfpewfcKbiNrxAKGN2bXG", response.id());
    }

    @Test
    void chatReconstructsToolUseFromSseBody(final JsonMapper mapper) throws Exception {
        // the gateway streaming path for tool_use: content_block_start + input_json_delta frames
        final var sse = String.join(
                "\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"model\":\"m\",\"id\":\"msg1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"bash\",\"input\":{}}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"ls\\\"}\"}}",
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
        final var calls = response.first().message().toolCalls();
        assertEquals(1, calls.size());
        assertEquals("t1", calls.get(0).id());
        assertEquals("bash", calls.get(0).function().name());
        assertEquals("{\"command\":\"ls\"}", calls.get(0).function().arguments());
        assertEquals("tool_calls", response.first().finishReason());
    }

    @Test
    void chatFailsWithContextOnEmptySseBody(final JsonMapper mapper) throws Exception {
        // a stream without any usable frame must produce a contextual error, never an empty completion
        server.createContext(
                "/v1/messages", exchange -> respondSse(exchange, "event: ping\ndata: {\"type\": \"ping\"}\n"));
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        final var completion = dialect.chat(request(false)).toCompletableFuture();
        final var failure = failureOf(completion).orElseThrow();
        assertTrue(
                failure.getMessage() != null
                        && failure.getMessage().contains("provider answered with a stream for a non-streaming request"),
                () -> "expected the contextual stream error but got: " + failure);
    }

    @Test
    void stripsDanglingToolUseInterruptedByPlainUserMessage(final JsonMapper mapper) throws Exception {
        // corrupt history: an assistant tool_use whose result never landed because a plain user text
        // (e.g. a queued prompt) was interposed. Without sanitizing, this would make the gateway reject
        // the request with "tool_use ids were found without tool_result blocks immediately after".
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        dialect.chat(new LlmRequest(
                        "m",
                        List.of(
                                LlmMessage.user("do it"),
                                LlmMessage.assistant("", List.of(LlmToolCall.of("t1", "grep", "{}"))),
                                LlmMessage.user("follow up!")),
                        null,
                        null,
                        64,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        Map.of()))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertFalse(body.contains("\"type\":\"tool_use\""), "the dangling tool_use must be stripped:\n" + body);
        assertFalse(body.contains("tool_use"), "no tool_use block may reach the wire:\n" + body);
        assertTrue(body.contains("follow up!"), "the interposed user text is preserved:\n" + body);
    }

    @Test
    void fineLogsWhenSanitizerDropsDanglingToolUse(final JsonMapper mapper) throws Exception {
        // dropped blocks must be observable, not silent: repairing in the agent loop is the primary
        // path, this wire backstop logs a FINE so lost context is never invisible.
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var records = new ArrayList<LogRecord>();
        final var logger = Logger.getLogger(AnthropicDialect.class.getName());
        final var previousLevel = logger.getLevel();
        final var previousUseParent = logger.getUseParentHandlers();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        logger.setLevel(Level.FINE);
        logger.setUseParentHandlers(false);
        try {
            final var dialect =
                    new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
            dialect.chat(new LlmRequest(
                            "m",
                            List.of(
                                    LlmMessage.user("do it"),
                                    LlmMessage.assistant("", List.of(LlmToolCall.of("t1", "grep", "{}"))),
                                    LlmMessage.user("follow up!")),
                            null,
                            null,
                            64,
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            Map.of()))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            final var fines = records.stream()
                    .filter(record -> record.getLevel() == Level.FINE)
                    .collect(Collectors.toList());
            assertFalse(fines.isEmpty(), "a FINE must be logged when the sanitizer drops a dangling tool_use");
            assertTrue(
                    fines.stream()
                            .anyMatch(record ->
                                    String.valueOf(record.getMessage()).contains("t1")),
                    "the FINE must name the dropped tool call id:\n" + fines);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParent);
        }
    }

    @Test
    void keepsValidContiguousToolUseToolResultPair(final JsonMapper mapper) throws Exception {
        // a valid history: the assistant tool_use is immediately answered by its tool_result
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        dialect.chat(new LlmRequest(
                        "m",
                        List.of(
                                LlmMessage.user("do it"),
                                LlmMessage.assistant("", List.of(LlmToolCall.of("t1", "grep", "{}"))),
                                LlmMessage.tool("t1", "found")),
                        null,
                        null,
                        64,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        Map.of()))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertTrue(body.contains("\"type\":\"tool_use\""), "the matched tool_use must be preserved:\n" + body);
        assertTrue(body.contains("\"id\":\"t1\""), "the matched tool_use id must be preserved:\n" + body);
        assertTrue(body.contains("\"type\":\"tool_result\""), "the matched tool_result must be preserved:\n" + body);
        assertTrue(
                body.contains("\"tool_use_id\":\"t1\""),
                "the matched tool_result must reference its tool_use:\n" + body);
    }

    @Test
    void dropsOrphanToolResultWithoutPreceedingToolUse(final JsonMapper mapper) throws Exception {
        // corruption the other way: a tool_result with no immediately-preceding assistant tool_use
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        dialect.chat(new LlmRequest(
                        "m",
                        List.of(LlmMessage.user("do it"), LlmMessage.tool("orphan", "stale result")),
                        null,
                        null,
                        64,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        Map.of()))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertFalse(body.contains("tool_result"), "the orphan tool_result must be dropped:\n" + body);
        assertTrue(body.contains("do it"), "the user message is preserved:\n" + body);
    }

    @Test
    void boundingDoesNotDropOlderPairedBurstButStillCatchesTailDangle(final JsonMapper mapper) throws Exception {
        // the pairing check is bounded to the live tail: an older, already-completed round (pair a) is
        // emitted as-is even though it lies before the most recent user prompt, while a dangling tool_use
        // in the live tail (b, no result) is still stripped.
        server.createContext("/v1/messages", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        });
        server.start();
        final var dialect = new AnthropicDialect(mapper, client, ProviderSpec.of(port() + "/v1/messages", "sk-test"));
        dialect.chat(new LlmRequest(
                        "m",
                        List.of(
                                LlmMessage.user("first"),
                                LlmMessage.assistant("", List.of(LlmToolCall.of("a", "grep", "{}"))),
                                LlmMessage.tool("a", "found"),
                                LlmMessage.user("second"),
                                LlmMessage.assistant("", List.of(LlmToolCall.of("b", "grep", "{}")))),
                        null,
                        null,
                        64,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        Map.of()))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertTrue(
                body.contains("\"id\":\"a\""),
                "the older completed pair's tool_use must be preserved even though it precedes the latest prompt:\n"
                        + body);
        assertTrue(body.contains("\"tool_use_id\":\"a\""), "the older pair's result must be preserved:\n" + body);
        assertFalse(body.contains("\"id\":\"b\""), "the live-tail dangling tool_use must still be stripped:\n" + body);
    }

    private static <T> Optional<Throwable> failureOf(final CompletableFuture<T> future) {
        try {
            future.join();
            return Optional.empty();
        } catch (final CompletionException e) {
            return Optional.of(e.getCause());
        }
    }
}
