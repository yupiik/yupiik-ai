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
import io.yupiik.llm.client.openai.model.WireMessage;
import io.yupiik.llm.client.spi.Dialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(TestJsonMapper.class)
class OpenAIDialectTest {
    @Test
    void bufferedMessageRetainsOnlyRecognizedTextReasoning() {
        final var wire = new WireMessage(
                "assistant",
                "answer",
                null,
                null,
                null,
                Map.of("reasoning_content", "thought", "reasoning", "alias", "unknown", "do not forward"));
        final var neutral = WireMessage.toNeutral(wire);
        assertEquals(Map.of("reasoning_content", "thought", "reasoning", "alias"), neutral.extensions());
        assertEquals("answer", neutral.content());
    }

    private HttpServer server;
    private HttpClient delegate;
    private ExtendedHttpClient client;
    private final AtomicReference<String> reqHeaders = new AtomicReference<>();
    private final AtomicReference<String> reqBody = new AtomicReference<>();
    private final CopyOnWriteArrayList<String> streamTexts = new CopyOnWriteArrayList<>();

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
        return request(stream, Map.of());
    }

    private static LlmRequest request(final boolean stream, final Map<String, Object> extensions) {
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
                false,
                extensions);
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        respond(exchange, status, "application/json", body);
    }

    private static void respond(
            final HttpExchange exchange, final int status, final String contentType, final String body)
            throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
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
    void completionsDialectPostsToChatCompletions(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            reqHeaders.set(exchange.getRequestHeaders().getFirst("authorization"));
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                            + "\"completion_tokens\":1,\"total_tokens\":4}}");
        });
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("Bearer sk-test", reqHeaders.get());
        assertEquals("hello", response.text());
        assertEquals(4, response.usage().totalTokens());
        final var body = reqBody.get();
        assertTrue(body.contains("\"model\":\"m\""));
        assertTrue(body.contains("\"max_tokens\":64"), body);
    }

    @Test
    void completionsDialectSendsRenderedDynamicHeaders(final JsonMapper mapper) throws Exception {
        // the dynamic header is a {{exec}} template: the wire must carry the rendered stdout
        // (with the exec_options cache warming so a second request reuses it)
        server.createContext("/v1/chat/completions", exchange -> {
            final var auth = exchange.getRequestHeaders().getFirst("authorization");
            if (reqHeaders.compareAndSet(null, auth)) {
                reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            respond(
                    exchange,
                    200,
                    "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                            + "\"completion_tokens\":1,\"total_tokens\":4}}");
        });
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                new ProviderSpec(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions",
                        "sk-test",
                        Duration.ofSeconds(5),
                        Map.of(),
                        builder -> builder.setHeader("authorization", "Bearer tok-abc")));
        // the customizer is applied on each request (chat POST here)
        dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("Bearer tok-abc", reqHeaders.get());
    }

    @Test
    void completionsDialectReadsTheProviderSideGenerationTime(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                                + "\"completion_tokens\":100,\"total_tokens\":103,\"generation_time\":2.5}}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                2_500L, response.usage().generationTime(), "a gateway generation_time in seconds is normalized to ms");
    }

    @Test
    void completionsDialectDerivesTheGenerationTimeFromAReportedRate(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                                + "\"completion_tokens\":100,\"total_tokens\":103,\"tokens_per_second\":50}}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(2_000L, response.usage().generationTime(), "100 tokens at 50 tok/s is 2s of generation");
    }

    @Test
    void completionsDialectHasNoGenerationTimeWhenTheProviderReportsNone(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                                + "\"completion_tokens\":1,\"total_tokens\":4}}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertNull(
                response.usage().generationTimeMs(),
                "a standard payload carries no timing: the client measures instead");
    }

    @Test
    void completionsDialectReadsTheReasoningTokensFromTheOutputBreakdown(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                                + "\"completion_tokens\":100,\"total_tokens\":103,"
                                + "\"completion_tokens_details\":{\"reasoning_tokens\":40}}}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                40L,
                response.usage().reasoning(),
                "the completion_tokens_details.reasoning_tokens breakdown must reach the neutral usage");
        assertEquals(100, response.usage().completion(), "reasoning tokens stay inside the completion aggregate");
    }

    @Test
    void completionsDialectReadsTheReasoningTokensFromANonStandardTopLevelField(final JsonMapper mapper)
            throws Exception {
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                                + "\"completion_tokens\":100,\"total_tokens\":103,\"reasoning_tokens\":25}}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                25L,
                response.usage().reasoning(),
                "a non-standard top-level reasoning_tokens field is the fallback when no breakdown object exists");
    }

    @Test
    void responsesDialectReadsTheReasoningTokensFromTheOutputBreakdown(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/responses",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"r1\",\"model\":\"m\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}],\"usage\":{\"input_tokens\":3,"
                                + "\"output_tokens\":100,\"total_tokens\":103,"
                                + "\"output_tokens_details\":{\"reasoning_tokens\":30}}}"));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var response = dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                30L,
                response.usage().reasoning(),
                "the output_tokens_details.reasoning_tokens breakdown must reach the neutral usage");
    }

    @Test
    void completionsDialectStreamsTextDeltas(final JsonMapper mapper) throws Exception {
        final var sse = String.join(
                "\n",
                "data: {\"id\":\"s1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hel\"},"
                        + "\"finish_reason\":null}]}",
                "",
                "data: {\"id\":\"s1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},"
                        + "\"finish_reason\":\"stop\"}]}",
                "",
                "data: [DONE]",
                "");
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.TextDelta("hel"),
                        new LlmStreamEvent.TextDelta("lo"),
                        new LlmStreamEvent.End("stop", null)),
                events);
    }

    @Test
    void completionsStreamAsksForIncludedUsage(final JsonMapper mapper) throws Exception {
        final var sse = String.join(
                "\n",
                "data: {\"id\":\"s1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},"
                        + "\"finish_reason\":\"stop\"}]}",
                "",
                "data: {\"id\":\"s1\",\"model\":\"m\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,"
                        + "\"completion_tokens\":1,\"total_tokens\":4}}",
                "",
                "data: [DONE]",
                "");
        server.createContext("/v1/chat/completions", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "text/event-stream", sse);
        });
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertTrue(reqBody.get().contains("\"stream_options\""), reqBody.get());
        assertTrue(reqBody.get().contains("\"include_usage\":true"), reqBody.get());
        assertTrue(
                events.stream().anyMatch(e -> e instanceof LlmStreamEvent.End end && end.usage() != null),
                "usage chunk must surface an End with usage, got: " + events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"content\":\"\"}", "null"})
    void completionsStreamKeepsUsageWithPlaceholderChoice(final String delta, final JsonMapper mapper)
            throws Exception {
        final var sse = String.join(
                "\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"},\"finish_reason\":null}],"
                        + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":1,\"total_tokens\":12}}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}],"
                        + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":16,\"total_tokens\":27,"
                        + "\"completion_tokens_details\":{\"reasoning_tokens\":6}}}",
                "",
                "data: [DONE]",
                "");
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(
                List.of(
                        new LlmStreamEvent.TextDelta("OK"),
                        new LlmStreamEvent.End("stop", null),
                        new LlmStreamEvent.End(null, new Usage(11L, 16L, 27L, null, null, 6L, null))),
                collect(publisher),
                "only the final usage must be emitted, even with a placeholder choice");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"role\":\"assistant\"}",
                "{\"content\":\"hello\"}",
                "{\"reasoning_content\":\"thinking\"}",
                "{\"reasoning\":\"thinking\"}",
                "{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"test\",\"arguments\":\"{}\"}}]}"
            })
    void completionsStreamIgnoresIntermediateUsage(final String delta, final JsonMapper mapper) throws Exception {
        final var sse = String.join(
                "\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}],"
                        + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":1,\"total_tokens\":12}}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "",
                "data: [DONE]",
                "");
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(
                List.of(new LlmStreamEvent.End("stop", null)),
                collect(publisher).stream()
                        .filter(LlmStreamEvent.End.class::isInstance)
                        .toList(),
                "intermediate usage must not create a spurious terminal event");
    }

    @Test
    void completionsStreamUsageCanBeDisabledOrOverridden(final JsonMapper mapper) throws Exception {
        final var sse = String.join(
                "\n",
                "data: {\"id\":\"s1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},"
                        + "\"finish_reason\":\"stop\"}]}",
                "",
                "data: [DONE]",
                "");
        server.createContext("/v1/chat/completions", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (reqBody.get().contains("\"stream\":true")) {
                respond(exchange, 200, "text/event-stream", sse);
            } else {
                respond(
                        exchange,
                        200,
                        "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,"
                                + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
            }
        });
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        dialect.stream(request(true, Map.of("includeUsage", false)), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertFalse(reqBody.get().contains("stream_options"), "disabled by includeUsage=false: " + reqBody.get());
        assertFalse(reqBody.get().contains("includeUsage"), "control key must not leak: " + reqBody.get());

        dialect.stream(request(true, Map.of("stream_options", Map.of("include_usage", false))), () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertTrue(
                reqBody.get().contains("\"include_usage\":false"),
                "explicit stream_options must win: " + reqBody.get());

        dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(reqBody.get().contains("stream_options"), "non-stream must not ask for usage: " + reqBody.get());
    }

    @Test
    void completionsDialectListsModelsFromDerivedEndpoint(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/models",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"data\":[{\"id\":\"a\",\"owned_by\":\"x\"},{\"id\":\"b\",\"owned_by\":\"y\"}]}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final LlmModels models = dialect.models().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("a", "b"), models.ids());
    }

    @Test
    void completionsDialectMaps404ToDialectNotSupported(final JsonMapper mapper) {
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 404, "not found"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var completion = dialect.chat(request(false)).toCompletableFuture();
        final var failure = failureOf(completion).orElseThrow();
        assertInstanceOf(
                DialectNotSupportedException.class,
                failure,
                () -> "expected DialectNotSupportedException but got " + failure);
        assertTrue(
                failure.getMessage().contains("not found"),
                () -> "expected the server body to surface but got: " + failure.getMessage());
    }

    @Test
    void completionsDialectSurfacesProviderErrorBodyOnFlavorMissing(final JsonMapper mapper) {
        server.createContext(
                "/v1/chat/completions",
                exchange ->
                        respond(exchange, 404, "{\"error\":{\"message\":\"Model ' deepseek-v4-flash' not found\"}}"));
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var completion = dialect.chat(request(false)).toCompletableFuture();
        final var failure = failureOf(completion).orElseThrow();
        assertInstanceOf(
                DialectNotSupportedException.class,
                failure,
                () -> "expected DialectNotSupportedException but got " + failure);
        assertTrue(
                failure.getMessage().contains("Model ' deepseek-v4-flash' not found"),
                () -> "expected the provider error body to surface but got: " + failure.getMessage());
    }

    private static Optional<Throwable> failureOf(final CompletableFuture<LlmResponse> future) {
        try {
            future.join();
            return Optional.empty();
        } catch (final CompletionException e) {
            return Optional.of(e.getCause());
        }
    }

    @Test
    void responsesDialectPostsToResponses(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/responses", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"r1\",\"model\":\"m\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                            + "\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}],\"usage\":{}}");
        });
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("hello", response.text());
        assertTrue(reqBody.get().contains("\"max_output_tokens\":64"), reqBody.get());
    }

    @Test
    void responsesDialectRequestsReasoningSummary(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/responses", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"id\":\"r1\",\"model\":\"m\",\"output\":[],\"usage\":{}}");
        });
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        // reasoningEffort is the 10th component of LlmRequest (before stream/extensions)
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
                "low",
                false,
                false,
                Map.of());
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        // 'summary' is what makes official OpenAI stream reasoning deltas at all; 'detailed' forces
        // gpt-6*-class backends to actually emit the displayable summary parts
        assertTrue(reqBody.get().contains("\"summary\":\"detailed\""), reqBody.get());
        assertTrue(reqBody.get().contains("\"effort\":\"low\""), reqBody.get());
        // every Responses dialect now requests the opaque encrypted_content blob, so stateless clients can
        // replay it across turns for reasoning continuity (advisory on backends that do not support it)
        assertTrue(
                reqBody.get().contains("\"include\":[\"reasoning.encrypted_content\"]"),
                "responses must request the replayable encrypted reasoning blob: " + reqBody.get());
    }

    @Test
    void responsesDialectKeepsAllNonStreamToolCalls(final JsonMapper mapper) throws Exception {
        server.createContext(
                "/v1/responses",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"r1\",\"model\":\"m\",\"output\":["
                                + "{\"type\":\"function_call\",\"id\":\"f1\",\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"},"
                                + "{\"type\":\"function_call\",\"id\":\"f2\",\"name\":\"grep\",\"arguments\":\"{\\\"pattern\\\":\\\"x\\\"}\"}],"
                                + "\"usage\":{}}"));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var calls = response.first().message().toolCalls();
        assertEquals(2, calls.size(), () -> "both parallel calls must survive, got " + calls);
        assertEquals("read", calls.get(0).function().name());
        assertEquals("grep", calls.get(1).function().name());
        assertEquals("{\"path\":\"a\"}", calls.get(0).function().arguments());
        assertEquals("{\"pattern\":\"x\"}", calls.get(1).function().arguments());
    }

    @Test
    void responsesDialectMarksFailedToolOutputAsIncompleteWithoutCustomIsError(final JsonMapper mapper)
            throws Exception {
        server.createContext("/v1/responses", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"id\":\"r1\",\"model\":\"m\",\"output\":[],\"usage\":{}}");
        });
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var request = new LlmRequest(
                "m",
                List.of(new LlmMessage("tool", "command failed: boom", null, null, "t1", Map.of("is_error", true))),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                Map.of());
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        // the custom internal flag must never leak onto the wire (strict backends like codexi
        // reject 'input[N].is_error'); the standard responses 'status' carries the failure instead
        final var body = reqBody.get();
        assertFalse(body.contains("is_error"), "custom is_error leaked onto the responses wire: " + body);
        assertTrue(body.contains("\"output\":\"command failed: boom\""), body);
        assertTrue(body.contains("\"status\":\"incomplete\""), "failed tool output must be marked incomplete: " + body);
    }

    @Test
    void completionsDialectStripsDanglingToolCallWhenInterposedUserBreaksPairing(final JsonMapper mapper)
            throws Exception {
        // OpenAI-compatible backends (OpenAI, sglang, Azure, litellm, ...) reject an assistant tool_calls with
        // no matching tool response. Here a plain user
        // message is interposed between the tool_calls and its (non-existent) result - the dangling call
        // must NOT reach the wire, while the interposed user text is preserved.
        server.createContext("/v1/chat/completions", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
                            + "\"completion_tokens\":1,\"total_tokens\":2}}");
        });
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var request = new LlmRequest(
                "m",
                List.of(
                        LlmMessage.user("do it"),
                        LlmMessage.assistant("", List.of(LlmToolCall.of("call_1", "grep", "{}"))),
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
                false,
                Map.of());
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertFalse(body.contains("call_1"), "the dangling tool_calls entry must not reach the wire:\n" + body);
        assertTrue(body.contains("follow up!"), "the interposed user text must be preserved:\n" + body);
    }

    @Test
    void completionsDialectKeepsValidContiguousToolCallAndResultPair(final JsonMapper mapper) throws Exception {
        // a valid history: assistant tool_calls immediately followed by its tool message - both must stay.
        server.createContext("/v1/chat/completions", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
                            + "\"completion_tokens\":1,\"total_tokens\":2}}");
        });
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var request = new LlmRequest(
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
                false,
                Map.of());
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertTrue(body.contains("\"tool_calls\""), "the matched tool_calls must be preserved:\n" + body);
        assertTrue(body.contains("\"id\":\"t1\""), "the matched tool call id must be preserved:\n" + body);
        assertTrue(
                body.contains("\"tool_call_id\":\"t1\""), "the matched tool result must reference the call:\n" + body);
    }

    @Test
    void completionsDialectDropsOrphanToolResultWithoutPreceedingToolCall(final JsonMapper mapper) throws Exception {
        // a tool message that answers no assistant tool call in this history is an ORPHAN: Azure/litellm
        // reject it ("messages with role 'tool' must be a response to a preceding message with 'tool_calls'"),
        // so the backstop must drop it instead of letting it 400 the whole request.
        server.createContext("/v1/chat/completions", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
                            + "\"completion_tokens\":1,\"total_tokens\":2}}");
        });
        server.start();
        final var dialect = new OpenAICompletionsDialect(
                mapper,
                client,
                ProviderSpec.of(
                        "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
        final var request = new LlmRequest(
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
                false,
                Map.of());
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertFalse(body.contains("\"tool_call_id\":\"orphan\""), "the orphan tool result must be dropped:\n" + body);
        assertTrue(body.contains("do it"), "the user prompt must be preserved:\n" + body);
    }

    @Test
    void responsesDialectStripsDanglingFunctionCallWhenInterposedUserBreaksPairing(final JsonMapper mapper)
            throws Exception {
        // the Responses wire has the same strict pairing: a function_call answered by no function_call_output
        // (a plain user message interposed instead) must be stripped, the user text preserved.
        server.createContext("/v1/responses", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"id\":\"r1\",\"model\":\"m\",\"output\":[],\"usage\":{}}");
        });
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var request = new LlmRequest(
                "m",
                List.of(
                        LlmMessage.user("do it"),
                        LlmMessage.assistant("", List.of(LlmToolCall.of("call_2", "grep", "{}"))),
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
                false,
                Map.of());
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertFalse(body.contains("\"call_id\":\"call_2\""), "the dangling function_call must be stripped:\n" + body);
        assertTrue(body.contains("follow up!"), "the interposed user text must be preserved:\n" + body);
    }

    @Test
    void responsesDialectStripsOlderDanglingCallOverLaterValidTurns(final JsonMapper mapper) throws Exception {
        // Regression for the full-history backstop: an unanswered function_call FOLLOWED by several later
        // completed tool rounds was previously trusted (only the live tail was verified), so the stale call
        // shipped to the backend and 400'd. The whole serialized history must be validated: the old dangling
        // call_old is stripped while the later valid call/result pairing survives.
        server.createContext("/v1/responses", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"id\":\"r1\",\"model\":\"m\",\"output\":[],\"usage\":{}}");
        });
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var request = new LlmRequest(
                "m",
                List.of(
                        LlmMessage.user("start"),
                        LlmMessage.assistant("", List.of(LlmToolCall.of("call_old", "grep", "{}"))),
                        // an interposed user message breaks call_old's contiguity; later turns are valid
                        LlmMessage.user("next turn"),
                        LlmMessage.assistant("", List.of(LlmToolCall.of("call_ok", "read", "{}"))),
                        LlmMessage.tool("call_ok", "result")),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                Map.of());
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertFalse(
                body.contains("\"call_id\":\"call_old\""),
                "the OLD dangling function_call must be stripped from the full history:\n" + body);
        assertTrue(
                body.contains("\"call_id\":\"call_ok\""),
                "the later valid call/result pairing must be preserved:\n" + body);
        assertTrue(
                body.contains("\"type\":\"function_call_output\""),
                "the later valid tool result must still be emitted:\n" + body);
    }

    @Test
    void responsesDialectLoadsNonNullCallIdWhenPresent(final JsonMapper mapper) throws Exception {
        // Responses function_call items carry BOTH item.id (fc_...) and call_id (call_...); the neutral
        // tool-call id must be the call_id (the value used to pair function_call_output), not the item id.
        server.createContext(
                "/v1/responses",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"r1\",\"model\":\"m\",\"output\":["
                                + "{\"type\":\"function_call\",\"id\":\"fc_x\",\"call_id\":\"call_x\","
                                + "\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"}],"
                                + "\"usage\":{}}"));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var calls = response.first().message().toolCalls();
        assertEquals(1, calls.size());
        assertEquals("call_x", calls.get(0).id(), "the neutral id must be the call_id, not the item id");
        assertEquals("read", calls.get(0).function().name());
    }

    @Test
    void responsesDialectRoundTripPairsCallIdOnReplay(final JsonMapper mapper) throws Exception {
        // Full tool round trip against a mock backend: the /responses call returns a function_call whose
        // call_id differs from its item id, and the NEXT request must replay the call and its result both
        // using the call_id so the backend sees a valid function_call/function_call_output pairing.
        final var first = new AtomicReference<Boolean>(null); // null=first, then true once first served
        server.createContext("/v1/responses", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (first.compareAndSet(null, Boolean.TRUE)) {
                reqBody.set(body);
                respond(
                        exchange,
                        200,
                        "{\"id\":\"r1\",\"model\":\"m\",\"output\":["
                                + "{\"type\":\"function_call\",\"id\":\"fc_z\",\"call_id\":\"call_z\","
                                + "\"name\":\"grep\",\"arguments\":\"{\\\"pattern\\\":\\\"x\\\"}\"}],"
                                + "\"usage\":{}}");
            } else {
                // second (replay) request: carry the just-returned call and its result
                reqBody.set(body);
                respond(exchange, 200, "{\"id\":\"r2\",\"model\":\"m\",\"output\":[],\"usage\":{}}");
            }
        });
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final LlmResponse response =
                dialect.chat(request(false)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var calls = response.first().message().toolCalls();
        assertEquals("call_z", calls.get(0).id(), "the returned call must use the call_id");

        // replay the call + its result in a follow-up request
        final var replayRequest = new LlmRequest(
                "m",
                List.of(
                        LlmMessage.user("do it again"),
                        LlmMessage.assistant("", calls),
                        LlmMessage.tool("call_z", "answer")),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                Map.of());
        dialect.chat(replayRequest).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertTrue(
                body.contains("\"call_id\":\"call_z\""),
                "the replayed function_call must reference call_z, not the item id:\n" + body);
    }

    @Test
    void responsesDialectStripsOldDanglingCallAcrossLargeHistory(final JsonMapper mapper) throws Exception {
        // Deterministic large-history correctness: thousands of valid tool rounds plus one OLD dangling call
        // at the head. The full-history block-local sanitizer must strip the stale call (which the former
        // live-tail-only backstop trusted) while preserving every later valid round - a single linear pass
        // with bounded per-block state, not an O(history) set build per request.
        server.createContext("/v1/responses", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"id\":\"r1\",\"model\":\"m\",\"output\":[],\"usage\":{}}");
        });
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));

        final var messages = new ArrayList<LlmMessage>(4010);
        messages.add(LlmMessage.user("start"));
        messages.add(LlmMessage.assistant("", List.of(LlmToolCall.of("old_dangling", "grep", "{}"))));
        messages.add(LlmMessage.user("broken interposed"));
        for (var i = 0; i < 2000; i++) {
            messages.add(LlmMessage.assistant("", List.of(LlmToolCall.of("t" + i, "read", "{}"))));
            messages.add(LlmMessage.tool("t" + i, "r" + i));
        }
        final var request =
                new LlmRequest("m", messages, null, null, 64, null, null, null, null, null, false, false, Map.of());
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        assertFalse(
                body.contains("\"call_id\":\"old_dangling\""),
                "the OLD dangling call at the head of a large history must be stripped:\n"
                        + (body.length() > 300 ? body.substring(0, 300) : body));
        assertTrue(
                body.contains("\"call_id\":\"t1999\""),
                "the last valid round must survive the full-history sanitizer:\n"
                        + (body.length() > 500 ? body.substring(body.length() - 300) : body));
        assertTrue(
                body.contains("\"call_id\":\"t0\""),
                "the first valid round must survive the full-history sanitizer:\n"
                        + (body.length() > 500 ? body.substring(0, 500) : body));
    }

    @Test
    void fineLogsWhenSanitizerDropsDanglingToolCall(final JsonMapper mapper) throws Exception {
        // dropped tool blocks must be observable, not silent - FINE-log each dropped id (mirror AnthropicDialect).
        server.createContext("/v1/chat/completions", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(
                    exchange,
                    200,
                    "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
                            + "\"completion_tokens\":1,\"total_tokens\":2}}");
        });
        server.start();
        final var records = new ArrayList<LogRecord>();
        final var logger = Logger.getLogger(OpenAICompletionsDialect.class.getName());
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
            final var dialect = new OpenAICompletionsDialect(
                    mapper,
                    client,
                    ProviderSpec.of(
                            "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions", "sk-test"));
            final var request = new LlmRequest(
                    "m",
                    List.of(
                            LlmMessage.user("do it"),
                            LlmMessage.assistant("", List.of(LlmToolCall.of("call_3", "grep", "{}"))),
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
                    false,
                    Map.of());
            dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
            final var fines = records.stream()
                    .filter(record -> record.getLevel() == Level.FINE)
                    .toList();
            assertFalse(fines.isEmpty(), "a FINE must be logged when the sanitizer drops a dangling tool call");
            assertTrue(
                    fines.stream()
                            .anyMatch(record ->
                                    String.valueOf(record.getMessage()).contains("call_3")),
                    "the FINE must name the dropped tool call id:\n" + fines);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParent);
        }
    }

    @Test
    void responsesDialectStreamsTextDeltas(final JsonMapper mapper) throws Exception {
        final var sse = String.join(
                "\n",
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"i\",\"output_index\":0,\"delta\":\"hel\"}",
                "",
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"i\",\"output_index\":0,\"delta\":\"lo\"}",
                "",
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"model\":\"m\",\"output\":[]}}",
                "",
                "");
        server.createContext("/v1/responses", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.TextDelta("hel"),
                        new LlmStreamEvent.TextDelta("lo"),
                        new LlmStreamEvent.End("stop", Usage.EMPTY)),
                events);
    }

    @Test
    void responsesDialectReadsNestedResponseUsage(final JsonMapper mapper) throws Exception {
        // the official Responses API (and the Codex backend) nest usage under response.usage on the
        // terminal event, not at the event root; the dialect must surface those counts instead of EMPTY
        final var sse = String.join(
                "\n",
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"i\",\"output_index\":0,\"delta\":\"hi\"}",
                "",
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"model\":\"m\",\"output\":[],"
                        + "\"usage\":{\"input_tokens\":3,\"output_tokens\":5,\"total_tokens\":8,"
                        + "\"input_tokens_details\":{\"cached_tokens\":2},"
                        + "\"output_tokens_details\":{\"reasoning_tokens\":1}}}}",
                "",
                "");
        server.createContext("/v1/responses", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        final var end = events.stream()
                .filter(LlmStreamEvent.End.class::isInstance)
                .map(LlmStreamEvent.End.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("stop", end.finishReason());
        final var usage = end.usage();
        Assertions.assertNotNull(usage, "nested response.usage must reach the End event");
        assertEquals(3L, usage.prompt(), "nested response.usage must reach the End event");
        assertEquals(5L, usage.completion());
        assertEquals(8L, usage.total());
        assertEquals(2L, usage.cacheRead());
        assertEquals(1L, usage.reasoning());
    }

    @Test
    void responsesIncompleteWithMaxOutputTokensMapsToTruncation(final JsonMapper mapper) throws Exception {
        // a genuine output-token exhaustion (response.incomplete + incomplete_details.reason=max_output_tokens)
        // must still surface as the truncation finish reason so the agent loop retries it via continueOnTruncation
        final var sse = String.join(
                "\n",
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"i\",\"output_index\":0,\"delta\":\"par\"}",
                "",
                "event: response.incomplete",
                "data: {\"type\":\"response.incomplete\",\"response\":{\"id\":\"r1\",\"model\":\"m\",\"output\":[],"
                        + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}",
                "",
                "");
        server.createContext("/v1/responses", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        final var end = events.stream()
                .filter(LlmStreamEvent.End.class::isInstance)
                .map(LlmStreamEvent.End.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("length", end.finishReason());
    }

    @Test
    void responsesIncompleteWithContentFilterIsNotTruncation(final JsonMapper mapper) throws Exception {
        // a safety-filter cutoff is NOT output-token exhaustion: it must surface verbatim (content_filter),
        // not as finish_reason=length, so the loop does not burn the truncation budget retrying it
        final var sse = String.join(
                "\n",
                "event: response.incomplete",
                "data: {\"type\":\"response.incomplete\",\"response\":{\"id\":\"r1\",\"model\":\"m\",\"output\":[],"
                        + "\"incomplete_details\":{\"reason\":\"content_filter\"}}}",
                "",
                "");
        server.createContext("/v1/responses", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        final var end = events.stream()
                .filter(LlmStreamEvent.End.class::isInstance)
                .map(LlmStreamEvent.End.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("content_filter", end.finishReason());
    }

    @Test
    void responsesFailedIsNotMappedToTruncation(final JsonMapper mapper) throws Exception {
        // a backend/API failure (response.failed / response.error / error) is NOT output-token exhaustion:
        // it must surface as "error" so the loop does not retry it as a truncation continuation
        final var sse = String.join(
                "\n",
                "event: response.failed",
                "data: {\"type\":\"response.failed\",\"response\":{\"id\":\"r1\",\"model\":\"m\",\"output\":[],"
                        + "\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}",
                "",
                "");
        server.createContext("/v1/responses", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        final var end = events.stream()
                .filter(LlmStreamEvent.End.class::isInstance)
                .map(LlmStreamEvent.End.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("error", end.finishReason());
        assertEquals("server_error: boom", end.errorMessage());
    }

    @Test
    void responsesStreamErrorDetailsAreOptional(final JsonMapper mapper) throws Exception {
        final var payload = new AtomicReference<String>();
        server.createContext("/v1/responses", exchange -> respond(exchange, 200, "text/event-stream", payload.get()));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var errors = List.of(
                "{\"message\":\"boom\",\"code\":\"server_error\"}",
                "{\"message\":\"boom\"}",
                "{\"code\":\"server_error\"}",
                "{}",
                "null",
                "[]",
                "42",
                "\"unexpected\"",
                "{\"message\":null,\"code\":null}",
                "{\"message\":\"  \",\"code\":\"\"}",
                "{\"message\":{},\"code\":[]}");
        for (final var type : List.of("response.failed", "response.error", "error")) {
            for (final var error : errors) {
                final var details = "\"error\":" + error;
                final var body = "response.failed".equals(type) ? "\"response\":{" + details + "}" : details;
                payload.set("event: " + type + "\ndata: {\"type\":\"" + type + "\"," + body + "}\n\n");
                final var publisher = dialect.stream(request(true), () -> false)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                final var end = collect(publisher).stream()
                        .filter(LlmStreamEvent.End.class::isInstance)
                        .map(LlmStreamEvent.End.class::cast)
                        .findFirst()
                        .orElseThrow();
                assertEquals("error", end.finishReason());
                final var expected =
                        switch (errors.indexOf(error)) {
                            case 0 -> "server_error: boom";
                            case 1 -> "boom";
                            case 2 -> "server_error";
                            default -> null;
                        };
                assertEquals(expected, end.errorMessage(), type + ": " + error);
                final var assembled = dialect.assemble(request(false), () -> false)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertEquals("error", assembled.first().finishReason());
                assertEquals(
                        expected == null ? Map.of() : Map.of("error", Map.of("message", expected)),
                        assembled.extensions());
            }
        }
        // Some gateways put message/code directly on the error event.
        payload.set("event: error\ndata: {\"type\":\"error\",\"message\":\"boom\",\"code\":\"server_error\"}\n\n");
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var end = collect(publisher).stream()
                .filter(LlmStreamEvent.End.class::isInstance)
                .map(LlmStreamEvent.End.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("server_error: boom", end.errorMessage());
    }

    @Test
    void responsesDialectEmitsReasoningItemForEncryptedBlob(final JsonMapper mapper) throws Exception {
        // the Codex backend mints an opaque encrypted_content blob on the reasoning item; the dialect
        // surfaces it as a ReasoningItem so the loop can persist and replay it (never displayed)
        final var sse = String.join(
                "\n",
                "event: response.output_item.done",
                "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"reasoning\","
                        + "\"id\":\"rs_1\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"let me think\"}],"
                        + "\"encrypted_content\":\"gAAAAEncryptedBlob\"}}",
                "",
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"i\",\"output_index\":0,\"delta\":\"done\"}",
                "",
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"model\":\"m\",\"output\":[]}}",
                "",
                "");
        server.createContext("/v1/responses", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        final var reasoning = events.stream()
                .filter(LlmStreamEvent.ReasoningItem.class::isInstance)
                .map(LlmStreamEvent.ReasoningItem.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("rs_1", reasoning.id());
        assertEquals("gAAAAEncryptedBlob", reasoning.encryptedContent());
        assertEquals(1, reasoning.summary().size());
    }

    @Test
    void responsesDialectReplaysPersistedReasoningItems(final JsonMapper mapper) throws Exception {
        server.createContext("/v1/responses", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "application/json", "{\"id\":\"r1\",\"model\":\"m\",\"output\":[],\"usage\":{}}");
        });
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var request = new LlmRequest(
                "m",
                List.of(
                        LlmMessage.user("hi"),
                        new LlmMessage(
                                "assistant",
                                "answer",
                                null,
                                null,
                                null,
                                Map.of(
                                        "reasoningItems",
                                        List.of(
                                                Map.of("id", "rs_missing", "encrypted_content", "gAAAA missing"),
                                                Map.of(
                                                        "id", "rs_empty",
                                                        "encrypted_content", "gAAAA empty",
                                                        "summary", List.of()),
                                                Map.of(
                                                        "id", "rs_populated",
                                                        "encrypted_content", "gAAAA populated",
                                                        "summary",
                                                                List.of(Map.of(
                                                                        "type", "summary_text", "text", "s"))))))),
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                Map.of());
        dialect.chat(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var body = reqBody.get();
        final var wire = mapper.fromBytes(Map.class, body.getBytes(StandardCharsets.UTF_8));
        final var input = new String(mapper.toBytes(wire.get("input")), StandardCharsets.UTF_8);
        assertTrue(
                input.contains(
                        "\"id\":\"rs_missing\",\"type\":\"reasoning\",\"summary\":[],\"encrypted_content\":\"gAAAA missing\""),
                input);
        assertTrue(
                input.contains(
                        "\"id\":\"rs_empty\",\"type\":\"reasoning\",\"summary\":[],\"encrypted_content\":\"gAAAA empty\""),
                input);
        assertTrue(input.contains("\"id\":\"rs_populated\""), input);
        assertTrue(input.contains("\"summary\":[{"), input);
        assertTrue(input.contains("\"text\":\"s\""), input);
        assertTrue(input.contains("\"type\":\"summary_text\""), input);
        assertTrue(
                input.indexOf("rs_missing") < input.indexOf("rs_empty")
                        && input.indexOf("rs_empty") < input.indexOf("rs_populated"),
                input);
        assertTrue(input.contains("\"type\":\"message\""), input);
    }

    @Test
    void responsesDialectStreamsReasoningSummaryDeltas(final JsonMapper mapper) throws Exception {
        // gateways commonly stream only the reasoning summary, not the full reasoning text
        final var sse = String.join(
                "\n",
                "event: response.reasoning_summary_text.delta",
                "data: {\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"i\",\"delta\":\"let me \"}",
                "",
                "event: response.reasoning_summary_text.delta",
                "data: {\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"i\",\"delta\":\"think\"}",
                "",
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"i\",\"output_index\":0,\"delta\":\"hi\"}",
                "",
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"model\":\"m\",\"output\":[]}}",
                "",
                "");
        server.createContext("/v1/responses", exchange -> respond(exchange, 200, "text/event-stream", sse));
        server.start();
        final var dialect = new OpenAIResponsesDialect(
                mapper,
                client,
                ProviderSpec.of("http://localhost:" + server.getAddress().getPort() + "/v1/responses", "sk-test"));
        final var publisher =
                dialect.stream(request(true), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var events = collect(publisher);
        assertEquals(
                List.of(
                        new LlmStreamEvent.ReasoningDelta("let me "),
                        new LlmStreamEvent.ReasoningDelta("think"),
                        new LlmStreamEvent.TextDelta("hi"),
                        new LlmStreamEvent.End("stop", Usage.EMPTY)),
                events);
    }

    @Test
    void namesMatchRoutingKeys(final JsonMapper mapper) {
        final Dialect completions = new OpenAICompletionsDialect(mapper, client, ProviderSpec.of("http://x", "k"));
        final Dialect responses = new OpenAIResponsesDialect(mapper, client, ProviderSpec.of("http://x", "k"));
        assertEquals("openai-completions", completions.name());
        assertEquals("openai-responses", responses.name());
    }
}
