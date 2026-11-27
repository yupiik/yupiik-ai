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
package io.yupiik.llm.client.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.LlmHttpFailureException;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class CodexDialectTest {
    private HttpServer server;
    private String base;
    private ExtendedHttpClient client;
    private HttpClient delegate;
    private final AtomicReference<String> reqPath = new AtomicReference<>();
    private final AtomicReference<String> reqAuth = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
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
            delegate = null;
            client = null;
        }
    }

    @Test
    void cacheAffinityFollowsEachRequestWithoutLeakingAcrossSessions(final JsonMapper mapper) throws Exception {
        final var sessions = new CopyOnWriteArrayList<String>();
        server.createContext("/codex/responses", exchange -> {
            sessions.add(String.valueOf(exchange.getRequestHeaders().getFirst("session-id")));
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{}");
        });
        final var dialect =
                new CodexDialect(mapper, client, new ProviderSpec(base + "/codex/responses", "key", null, null, null));
        for (final var key : List.of("session-a", "session-b", "session-a")) {
            dialect.chat(request().with(Map.of("prompt_cache_key", key)), () -> false)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
        }
        dialect.chat(request(), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("session-a", "session-b", "session-a", "null"), sessions);
    }

    @Test
    void unauthorizedRecoversAndRendersNewHeaders(final JsonMapper mapper) throws Exception {
        final var token = new AtomicReference<>("old");
        final var calls = new AtomicInteger();
        final var recoveries = new AtomicInteger();
        server.createContext("/codex/responses", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            final var auth = exchange.getRequestHeaders().getFirst("authorization");
            respond(exchange, "Bearer new".equals(auth) ? 200 : 401, "{}");
        });
        final var dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses",
                        null,
                        null,
                        null,
                        builder -> builder.setHeader("authorization", "Bearer " + token.get())));
        dialect.setUnauthorizedRecovery(rejected -> {
            assertEquals("Bearer old", rejected);
            recoveries.incrementAndGet();
            token.set("new");
        });
        dialect.chat(request()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        dialect.chat(request()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(3, calls.get());
        assertEquals(1, recoveries.get());
    }

    @Test
    void unauthorizedRecoveryHasOneRetryBudget(final JsonMapper mapper) {
        final var calls = new AtomicInteger();
        final var recoveries = new AtomicInteger();
        server.createContext("/codex/responses", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 401, "{}");
        });
        final var dialect =
                new CodexDialect(mapper, client, new ProviderSpec(base + "/codex/responses", "key", null, null, null));
        dialect.setUnauthorizedRecovery(rejected -> recoveries.incrementAndGet());
        Assertions.assertThrows(
                CompletionException.class,
                () -> dialect.chat(request()).toCompletableFuture().join());
        assertEquals(2, calls.get());
        assertEquals(1, recoveries.get());
    }

    @Test
    void listsAndFiltersCodexModels(final JsonMapper mapper) throws Exception {
        server.createContext("/codex/models", exchange -> {
            reqPath.set(exchange.getRequestURI().toString());
            reqAuth.set(exchange.getRequestHeaders().getFirst("authorization"));
            respond(
                    exchange,
                    200,
                    "{\"models\":["
                            + "{\"slug\":\"gpt-5.3-codex\",\"display_name\":\"GPT-5.3 Codex\","
                            + "\"visibility\":\"list\",\"supported_in_api\":true,\"minimal_client_version\":[0,1,0],"
                            + "\"context_window\":400000,\"max_context_window\":400000,"
                            + "\"default_reasoning_level\":\"medium\","
                            + "\"supported_reasoning_levels\":["
                            + "{\"effort\":\"low\",\"description\":\"Fast\"},{\"effort\":\"xhigh\"}]},"
                            + "{\"slug\":\"gpt-reserve\",\"display_name\":\"Reserve\","
                            + "\"visibility\":\"hide\",\"supported_in_api\":true,\"minimal_client_version\":[0,1,0]},"
                            + "{\"slug\":\"codex-auto-review\",\"display_name\":\"Review\","
                            + "\"visibility\":\"none\",\"supported_in_api\":true,\"minimal_client_version\":[0,1,0]},"
                            + "{\"slug\":\"gpt-6-gated\",\"display_name\":\"Gated\","
                            + "\"visibility\":\"list\",\"supported_in_api\":true,"
                            + "\"minimal_client_version\":[200,0,0]}"
                            + "]}");
        });

        final CodexDialect dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses",
                        "fallback-key",
                        null,
                        null,
                        // simulates the {{codexAuth}} dynamic header: the resolver injects the access token
                        builder -> builder.setHeader("authorization", "Bearer oauth-live-token")));
        final LlmModels models = dialect.models().toCompletableFuture().get();

        // the path requested the catalog with the client_version
        final String path = reqPath.get();
        assertTrue(path.startsWith("/codex/models?client_version="), "unexpected path " + path);

        // the OAuth bearer set by the headers customizer must be sent (the catalog is authenticated)
        assertEquals("Bearer oauth-live-token", reqAuth.get(), "the codexAuth token must reach the models request");

        // only the selectable, not version-gated model survives
        assertEquals(1, models.data().size());
        assertEquals("gpt-5.3-codex", models.data().get(0).id());
        assertEquals(400_000, models.data().get(0).contextWindow());
        final var attrs = models.data().get(0).others();
        assertEquals("medium", attrs.get("default_reasoning_level"));
        @SuppressWarnings("unchecked")
        final var levels = (List<Map<String, Object>>) attrs.get("supported_reasoning_levels");
        assertEquals(
                List.of("low", "xhigh"),
                levels.stream().map(level -> level.get("effort")).toList());
        assertEquals("Fast", levels.get(0).get("description"));
    }

    @Test
    void streamsSseWithoutEventStreamContentType(final JsonMapper mapper) throws Exception {
        // the Codex backend streams SSE but does NOT set a text/event-stream content-type header,
        // which used to make the 2xx body be buffered as an error ("LLM request failed (200)").
        server.createContext("/codex/responses", exchange -> {
            // deliberately no content-type header: mimics chatgpt.com/backend-api/codex
            final byte[] sse = ("event: response.created\n"
                            + "data: {\"type\":\"response.created\"}\n\n"
                            + "event: response.output_text.delta\n"
                            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\",\"output_index\":0}\n\n"
                            + "event: response.output_text.delta\n"
                            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\" world\",\"output_index\":0}\n\n"
                            + "event: response.completed\n"
                            + "data: {\"type\":\"response.completed\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, sse.length);
            exchange.getResponseBody().write(sse);
            exchange.close();
        });

        final CodexDialect dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses", "key", null, null, b -> b.setHeader("authorization", "Bearer t")));
        final var sb = new StringBuilder();
        final var latch = new CountDownLatch(1);
        final var err = new AtomicReference<Throwable>();
        dialect.stream(request(), () -> false).toCompletableFuture().join().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(LlmStreamEvent e) {
                if (e instanceof LlmStreamEvent.TextDelta(String text)) {
                    sb.append(text);
                }
            }

            @Override
            public void onError(Throwable t) {
                err.set(t);
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });
        latch.await();
        assertEquals("hello world", sb.toString(), "the streamed text must be produced, not buffered as an error");
        Assertions.assertNull(err.get(), "no stream error expected: " + err.get());
    }

    @Test
    void chatForcesStreamTrueOnTheWire(final JsonMapper mapper) throws Exception {
        // background (non-streaming) chat calls must still send stream:true, or the codex backend
        // rejects them with {"detail":"Stream must be set to true"}.
        final AtomicReference<String> reqBody = new AtomicReference<>();
        server.createContext("/codex/responses", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{}");
        });

        final CodexDialect dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses", "key", null, null, b -> b.setHeader("authorization", "Bearer t")));
        dialect.chat(request(), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);

        final var body = reqBody.get();
        Assertions.assertNotNull(body, "no request body was sent to /codex/responses");
        assertTrue(body.contains("\"stream\":true"), "codex chat must send stream:true, got: " + body);
    }

    @Test
    void replaysReasoningItemsWithRequiredSummaryForChatAndStream(final JsonMapper mapper) throws Exception {
        final var bodies = new CopyOnWriteArrayList<String>();
        final var calls = new AtomicInteger();
        server.createContext("/codex/responses", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            calls.incrementAndGet();
            if (body.contains("\"id\":\"rs_missing\",\"type\":\"reasoning\",\"encrypted_content\"")) {
                respond(exchange, 400, "{\"error\":{\"message\":\"Missing required parameter: 'input[0].summary'.\"}}");
                return;
            }
            final var sse =
                    "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n";
            final var bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        final var nullSummary = new LinkedHashMap<String, Object>();
        nullSummary.put("id", "rs_null");
        nullSummary.put("summary", null);
        nullSummary.put("encrypted_content", "blob-null");
        final var items = List.of(
                Map.of("id", "rs_missing", "encrypted_content", "blob-missing"),
                nullSummary,
                Map.of("id", "rs_empty", "summary", List.of(), "encrypted_content", "blob-empty"),
                Map.of(
                        "id", "rs_populated",
                        "summary", List.of(Map.of("type", "summary_text", "text", "summary")),
                        "encrypted_content", "blob-populated"));
        final var request = new LlmRequest(
                "gpt-6-luna",
                List.of(
                        new LlmMessage(
                                "assistant", "previous answer", null, null, null, Map.of("reasoningItems", items)),
                        new LlmMessage("user", "next", null, null, null, null)),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                true,
                Map.of());
        final var dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses", "key", null, null, b -> b.setHeader("authorization", "Bearer t")));

        dialect.chat(request, () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var streamDone = new CountDownLatch(1);
        final var streamError = new AtomicReference<Throwable>();
        dialect.stream(request, () -> false)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
                .subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(final Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(final LlmStreamEvent event) {}

                    @Override
                    public void onError(final Throwable error) {
                        streamError.set(error);
                        streamDone.countDown();
                    }

                    @Override
                    public void onComplete() {
                        streamDone.countDown();
                    }
                });
        Assertions.assertTrue(streamDone.await(5, TimeUnit.SECONDS), "stream did not complete");
        Assertions.assertNull(streamError.get(), "valid reasoning replay should stream successfully");

        assertEquals(2, calls.get(), "both chat and stream should succeed on the first valid request");
        for (final var body : bodies) {
            assertTrue(body.contains("\"id\":\"rs_missing\""), body);
            assertTrue(body.contains("\"summary\":[]"), body);
            assertTrue(body.contains("\"encrypted_content\":\"blob-missing\""), body);
            assertTrue(body.contains("\"id\":\"rs_null\""), body);
            assertTrue(body.contains("\"encrypted_content\":\"blob-null\""), body);
            assertTrue(body.contains("\"id\":\"rs_empty\""), body);
            assertTrue(body.contains("\"encrypted_content\":\"blob-empty\""), body);
            assertTrue(body.contains("\"id\":\"rs_populated\""), body);
            assertTrue(body.contains("\"text\":\"summary\""), body);
            assertTrue(body.contains("\"type\":\"summary_text\""), body);
            assertTrue(body.contains("\"summary\":[{"), body);
            assertTrue(
                    body.indexOf("rs_missing") < body.indexOf("rs_null")
                            && body.indexOf("rs_null") < body.indexOf("rs_empty")
                            && body.indexOf("rs_empty") < body.indexOf("rs_populated"),
                    body);
        }
    }

    @Test
    void reasoningDoesNotRequestEncryptedContentBlobByDefault(final JsonMapper mapper) throws Exception {
        // the Codex flavor defaults includeEncryptedReasoning() to false: the ChatGPT backend and
        // stricter Codex/gov edges reject the reasoning.include directive with an unknown-parameter 400,
        // so the common path keeps just {effort, summary} and does not ask for the encrypted blob.
        final AtomicReference<String> reqBody = new AtomicReference<>();
        server.createContext("/codex/responses", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{}");
        });
        final var request = new LlmRequest(
                "gpt-6-luna",
                List.of(new LlmMessage("user", "hi", null, null, null, null)),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                "medium",
                true,
                Map.of());

        final CodexDialect dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses", "key", null, null, b -> b.setHeader("authorization", "Bearer t")));
        dialect.chat(request, () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);

        final var body = reqBody.get();
        Assertions.assertNotNull(body, "no request body was sent to /codex/responses");
        assertTrue(body.contains("\"effort\":\"medium\""), body);
        assertTrue(body.contains("\"summary\":\"detailed\""), body);
        Assertions.assertFalse(
                body.contains("reasoning.encrypted_content"),
                "codex must NOT request the encrypted reasoning blob by default: " + body);
    }

    @Test
    void reasoningRequestsEncryptedContentBlobWhenOptedIn(final JsonMapper mapper) throws Exception {
        // the reasoning_include provider option re-enables the opaque encrypted-content blob for
        // backends that support it (multi-turn reasoning continuity).
        final AtomicReference<String> reqBody = new AtomicReference<>();
        server.createContext("/codex/responses", exchange -> {
            reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{}");
        });
        final var request = new LlmRequest(
                "gpt-6-luna",
                List.of(new LlmMessage("user", "hi", null, null, null, null)),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                "medium",
                true,
                Map.of());

        // drive the option through the ProviderSpec options map (how ChatClient wires providerSpecificOptions)
        final CodexDialect dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses",
                        "key",
                        null,
                        Map.of(CodexDialect.REASONING_INCLUDE_KEY, true),
                        b -> b.setHeader("authorization", "Bearer t")),
                CodexDialect.DEFAULT_CLIENT_VERSION,
                null);
        dialect.chat(request, () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);

        final var body = reqBody.get();
        Assertions.assertNotNull(body, "no request body was sent to /codex/responses");
        assertTrue(body.contains("\"effort\":\"medium\""), body);
        assertTrue(body.contains("\"summary\":\"detailed\""), body);
        assertTrue(
                body.contains("\"include\":[\"reasoning.encrypted_content\"]"),
                "codex must request the replayable encrypted reasoning blob when opted in: " + body);
    }

    @Test
    void chatCoalescesStreamedSseIntoSingleResponse(final JsonMapper mapper) throws Exception {
        // the codex backend always streams SSE even for single-shot chat; the assembly helper must
        // coalesce text deltas and function_call events into one LlmResponse with tool calls.
        server.createContext("/codex/responses", exchange -> {
            final byte[] sse = ("event: response.created\n"
                            + "data: {\"type\":\"response.created\"}\n\n"
                            + "event: response.output_text.delta\n"
                            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\",\"output_index\":0}\n\n"
                            + "event: response.output_text.delta\n"
                            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\" world\",\"output_index\":0}\n\n"
                            + "event: response.output_item.added\n"
                            + "data: {\"type\":\"response.output_item.added\",\"output_index\":1,"
                            + "\"item\":{\"type\":\"function_call\",\"id\":\"call_1\",\"name\":\"bash\"}}\n\n"
                            + "event: response.function_call_arguments.delta\n"
                            + "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,"
                            + "\"item_id\":\"call_1\",\"delta\":\"{\\\"command\\\":\\\"ls\\\"}\"}\n\n"
                            + "event: response.completed\n"
                            + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\","
                            + "\"usage\":{\"input_tokens\":3,\"output_tokens\":5,\"total_tokens\":8}}}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, sse.length);
            exchange.getResponseBody().write(sse);
            exchange.close();
        });

        final CodexDialect dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses", "key", null, null, b -> b.setHeader("authorization", "Bearer t")));
        final LlmResponse response =
                dialect.chat(request(), () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals("hello world", response.text(), "text deltas must be assembled into a single response");
        final var first = response.first();
        Assertions.assertNotNull(first, "expected at least one choice");
        assertEquals(1, first.message().toolCalls().size(), "the streamed function call must survive assembly");
        final var call = first.message().toolCalls().get(0);
        assertEquals("call_1", call.id());
        assertEquals("bash", call.function().name());
        assertEquals("{\"command\":\"ls\"}", call.function().arguments());
        Assertions.assertNotNull(response.usage(), "usage from the completed event must be kept");
        assertEquals(8L, response.usage().total());
        assertEquals(3L, response.usage().prompt(), "nested response.usage.input_tokens must reach the response");
        assertEquals(5L, response.usage().completion(), "nested response.usage.output_tokens must reach the response");
    }

    @Test
    void delegatedAccessRejectionAutoUpgradesToAgentAssertion(final JsonMapper mapper) throws Exception {
        // the backend rejects the OAuth bearer with 3p_delegated_access_policy_denied (workspace
        // policy) - the dialect must invoke the upgrader (lazy enterprise enrollment) and re-send
        // once with the signed AgentAssertion header, then stream the successful response.
        final AtomicReference<Integer> authCount = new AtomicReference<>(0);
        final AtomicReference<String> firstAuth = new AtomicReference<>();
        final AtomicReference<String> secondAuth = new AtomicReference<>();
        server.createContext("/codex/responses", exchange -> {
            final String auth = exchange.getRequestHeaders().getFirst("authorization");
            if (authCount.getAndUpdate(v -> v + 1) == 0) {
                firstAuth.set(auth);
                // delegated-access workspace policy block
                respond(exchange, 403, "{\"error\":\"3p_delegated_access_policy_denied\"}");
                return;
            }
            secondAuth.set(auth);
            final byte[] sse = ("event: response.created\n"
                            + "data: {\"type\":\"response.created\"}\n\n"
                            + "event: response.output_text.delta\n"
                            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\",\"output_index\":0}\n\n"
                            + "event: response.completed\n"
                            + "data: {\"type\":\"response.completed\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, sse.length);
            exchange.getResponseBody().write(sse);
            exchange.close();
        });

        final AtomicReference<Integer> upgradeCalls = new AtomicReference<>(0);
        final CodexDialect dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses",
                        "key",
                        null,
                        null,
                        b -> b.setHeader("authorization", "Bearer oauth-token")),
                160,
                providerIdIgnored -> {
                    upgradeCalls.getAndUpdate(v -> v + 1);
                    return "AgentAssertion {\"agent_runtime_id\":\"r\",\"task_id\":\"t\"}";
                });

        final var sb = new StringBuilder();
        final var err = new AtomicReference<Throwable>();
        final var latch = new CountDownLatch(1);
        dialect.stream(request(), () -> false).toCompletableFuture().join().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(LlmStreamEvent e) {
                if (e instanceof LlmStreamEvent.TextDelta(String text)) {
                    sb.append(text);
                }
            }

            @Override
            public void onError(Throwable t) {
                err.set(t);
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });
        latch.await();

        assertEquals("Bearer oauth-token", firstAuth.get(), "the original request sends the OAuth bearer");
        assertEquals(
                "AgentAssertion {\"agent_runtime_id\":\"r\",\"task_id\":\"t\"}",
                secondAuth.get(),
                "the retried request must carry the signed AgentAssertion header");
        assertEquals(1, upgradeCalls.get(), "the upgrader must be invoked exactly once");
        assertEquals("ok", sb.toString(), "the successful retried stream is surfaced");
        Assertions.assertNull(err.get(), "no error after the auto-upgrade");
    }

    @Test
    void nonDelegated4xxPassesThroughWithoutUpgrade(final JsonMapper mapper) throws Exception {
        // any failure that is NOT the delegated-access policy block must surface unchanged and must
        // NOT invoke the upgrader (no silent enrollment on unrelated errors)
        final AtomicReference<Integer> authCount = new AtomicReference<>(0);
        server.createContext("/codex/responses", exchange -> {
            authCount.getAndUpdate(v -> v + 1);
            // a plain 4xx: rate-limit style rejection, not the delegated-access block
            respond(exchange, 429, "{\"error\":\"rate_limit_exceeded\"}");
        });

        final AtomicReference<Integer> upgradeCalls = new AtomicReference<>(0);
        final CodexDialect dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses", "key", null, null, b -> b.setHeader("authorization", "Bearer t")),
                160,
                providerIdIgnored -> {
                    upgradeCalls.getAndUpdate(v -> v + 1);
                    return "AgentAssertion {}";
                });

        final AtomicReference<Throwable> stageError = new AtomicReference<>();
        dialect.stream(request(), () -> false)
                .whenComplete((result, error) -> stageError.set(error))
                .handle((result, error) -> null)
                .toCompletableFuture()
                .join();

        assertEquals(0, upgradeCalls.get(), "a non-delegated 4xx must not trigger the upgrader");
        final Throwable cause = stageError.get() instanceof CompletionException ce ? ce.getCause() : stageError.get();
        Assertions.assertInstanceOf(LlmHttpFailureException.class, cause, "" + cause);
        assertEquals(429, ((LlmHttpFailureException) cause).status());
        assertEquals(1, authCount.get(), "only the original request is sent, no silent retry");
    }

    @Test
    void reasoningIncludeRejectionAutoDowngradesWhenOptedIn(final JsonMapper mapper) throws Exception {
        // when a codex provider opted into the encrypted-reasoning include and a strict backend rejects
        // it with an unknown-parameter 400, the dialect must transparently retry once without the include
        // and surface the successful response.
        // opted-in dialect: first request carries the include directive and is rejected, the retry drops it
        final AtomicReference<String> firstBody = new AtomicReference<>();
        final AtomicReference<String> secondBody = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final var request = new LlmRequest(
                "gpt-6-luna",
                List.of(new LlmMessage("user", "hi", null, null, null, null)),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                "medium",
                true,
                Map.of());
        server.createContext("/codex/responses", exchange -> {
            final var b = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (calls.incrementAndGet() == 1) {
                firstBody.set(b);
                respond(
                        exchange,
                        400,
                        "{\"error\":{\"message\":\"Unknown parameter: 'reasoning.include'.\","
                                + "\"type\":\"invalid_request_error\",\"param\":\"reasoning.include\",\"code\":\"unknown_parameter\"}}");
            } else {
                secondBody.set(b);
                respond(exchange, 200, "{}");
            }
        });

        final CodexDialect dialect = new CodexDialect(
                mapper,
                client,
                new ProviderSpec(
                        base + "/codex/responses", "key", null, null, b -> b.setHeader("authorization", "Bearer t")),
                CodexDialect.DEFAULT_CLIENT_VERSION,
                null,
                true);
        final AtomicReference<Throwable> stageError = new AtomicReference<>();
        dialect.chat(request, () -> false)
                .whenComplete((r, e) -> stageError.set(e))
                .handle((r, e) -> null)
                .toCompletableFuture()
                .join();

        assertEquals(2, calls.get(), "the include rejection must be retried once");
        assertTrue(
                firstBody.get().contains("\"include\":[\"reasoning.encrypted_content\"]"),
                "the first request must carry the include directive: " + firstBody.get());
        assertFalse(
                secondBody.get().contains("reasoning.encrypted_content"),
                "the retried request must drop the include directive: " + secondBody.get());
        Assertions.assertNull(stageError.get(), "no error after the auto-downgrade");

        // the backend rejection is memoized: a second chat must NOT send the include directive again
        final var secondStageError = new AtomicReference<Throwable>();
        dialect.chat(request, () -> false)
                .whenComplete((r, e) -> secondStageError.set(e))
                .handle((r, e) -> null)
                .toCompletableFuture()
                .join();
        assertEquals(3, calls.get(), "the memoized rejection must not trigger another include round-trip");
        assertFalse(
                secondBody.get().contains("reasoning.encrypted_content"),
                "the second request must also drop the include directive: " + secondBody.get());
        Assertions.assertNull(secondStageError.get(), "no error on the second request");
    }

    private LlmRequest request() {
        return new LlmRequest(
                "gpt-6-luna",
                List.of(new LlmMessage("user", "hi", null, null, null, null)),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                true,
                Map.of());
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) {
        try {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } finally {
            exchange.close();
        }
    }
}
