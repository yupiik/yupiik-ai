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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.context.ContextMeasurement.Payload;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(TestJsonMapper.class)
class ChatClientFlavorTest {
    private static final Pattern REQUIRED_PROPERTIES =
            Pattern.compile("(?s).*\"required\":\\[\"(text|path)\",\"(text|path)\"\\].*");
    private static HttpServer server;
    private ChatClient client;
    private ExtendedHttpClient http;
    private JsonMapper mapper;

    @TempDir
    static Path storageHome;

    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger modelsHits = new AtomicInteger();

    @BeforeEach
    void start(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws IOException {
        this.mapper = mapper;
        modelsHits.set(0);
        server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
        server.createContext("/v1/chat/completions", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            if (body.contains("\"stream\":true")) {
                final var sse = String.join(
                        "\n",
                        "data:"
                                + " {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"he\"},\"finish_reason\":null}]}",
                        "",
                        "data:"
                                + " {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"llo\"},\"finish_reason\":null}]}",
                        "",
                        "data:"
                                + " {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}",
                        "",
                        "data: [DONE]",
                        "");
                respond(exchange, 200, sse, "text/event-stream");
            } else {
                respond(exchange, 200, """
                    {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"hello there"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}\
                    """, "application/json");
            }
        });
        server.createContext("/v1/models", exchange -> {
            modelsHits.incrementAndGet();
            respond(
                    exchange,
                    200,
                    "{\"data\":[{\"id\":\"model-a\",\"owned_by\":\"x\"},{\"id\":\"model-b\",\"owned_by\":\"y\"},"
                            + "{\"id\":\"catalog-model\",\"owned_by\":\"x\",\"default_reasoning_level\":\"high\","
                            + "\"supported_reasoning_levels\":[{\"effort\":\"low\"},{\"effort\":\"high\"},{\"effort\":\"ultra\"}]}]}",
                    "application/json");
        });
        http = new HttpClientProducer().httpClient(configuration, new NativeProviderConfiguration(null, null));
        resetClient(configuration);
    }

    @AfterEach
    void tearDown() {
        try {
            stop();
        } finally {
            if (http != null) {
                http.close();
            }
        }
    }

    private void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            for (final var path : List.of(
                    "/v1/responses",
                    "/v1/chat/completions",
                    "/v1/models",
                    "/v1/messages",
                    "/target/responses",
                    "/target/chat/completions")) {
                try {
                    server.removeContext(path);
                } catch (final IllegalArgumentException ignored) {
                    // This test did not install a handler for this route.
                }
            }
        }
    }

    private void resetClient(final CodingAgentConfiguration configuration) {
        if (client != null) {
            client.close();
        }
        client = new ChatClient(configuration, mapper, providerManager(configuration), Clock.systemUTC(), http);
    }

    private ProviderManager providerManager(final CodingAgentConfiguration configuration) {
        return new ProviderManager(configuration, mapper);
    }

    private static void respond(
            final HttpExchange exchange, final int status, final String body, final String contentType) {
        try {
            exchange.getRequestBody().readAllBytes();
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ChatCompletionRequest request() {
        return request("test-model");
    }

    private ChatCompletionRequest request(final String model) {
        return new ChatCompletionRequest(
                model, List.of(Message.user("hi")), null, null, null, null, null, null, null, null);
    }

    private Map<?, ?> anthropicCacheRequest(final CodingAgentConfiguration config) throws Exception {
        server.createContext("/v1/messages", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                {"id":"m1","type":"message","role":"assistant","model":"test-model","content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":1}}
                """, "application/json");
        });
        client.close();

        client = new ChatClient(config, mapper, new ProviderManager(config, mapper), Clock.systemUTC(), http);
        final var result = client.chat(new ChatCompletionRequest(
                "$claude-whatever/test-model",
                List.of(Message.system("Stable instructions"), Message.user("hi")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
        assertEquals("hello", result.message().content());
        assertEquals(ApiFlavor.ANTHROPIC, client.flavor());
        assertEquals(1, bodies.size());
        return (Map<?, ?>) mapper.fromString(Object.class, bodies.getLast());
    }

    private static String targetApiPath(final String api) {
        return "responses".equals(api) ? "responses" : "chat/completions";
    }

    private static void structuredRoutingResponse(final HttpExchange exchange, final String api) {
        respond(
                exchange,
                200,
                "responses".equals(api)
                        ? "{\"id\":\"r1\",\"model\":\"test-model\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"routed"
                                + " answer\"}]}]}"
                        : "{\"id\":\"c1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"routed"
                                + " answer\"},\"finish_reason\":\"stop\"}]}",
                "application/json");
    }

    private static int occurrences(final String body, final String needle) {
        var count = 0;
        for (var idx = body.indexOf(needle); idx >= 0; idx = body.indexOf(needle, idx + needle.length())) {
            count++;
        }
        return count;
    }

    /** A tiny controllable clock so the structured-rejection TTL can be advanced in tests. */
    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(final long millis) {
            this.millis = millis;
        }

        void advance(final long deltaMillis) {
            this.millis += deltaMillis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    // Bind once before nested Fusion containers initialize their application-scoped roots.
    // Tests replace handlers, not endpoints, so the ConfigurationSource never needs to change.
    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    public static class SourceModule extends ConfigurationTestModule {
        public SourceModule() {
            this(Map.of());
        }

        protected SourceModule(final Map<String, String> extra) {
            super(defaults(extra));
        }

        private static Map<String, String> defaults(final Map<String, String> extra) {
            final var entries = new HashMap<String, String>();
            entries.put("yca.storageHome", storageHome.toString());
            entries.put("yca.provider.providers.length", "1");
            entries.put("yca.provider.providers.0.id", "default");
            entries.put(
                    "yca.provider.providers.0.baseUrl",
                    "http://localhost:" + server.getAddress().getPort() + "/v1");
            entries.put("yca.provider.providers.0.apiKey", "test-key");
            entries.put("yca.provider.providers.0.model", "test-model");
            entries.put("yca.provider.providers.0.api", "auto");
            entries.put("yca.provider.providers.0.contextWindow", "128000");
            entries.put("yca.provider.providers.0.timeoutSeconds", "5");
            entries.put("yca.llm.retriesMaxAttempts", "1");
            entries.put("yca.llm.retriesBaseDelayMs", "1");
            entries.put("yca.llm.rateLimitRequestsPerMinute", "0");
            entries.put("yca.llm.maxConcurrent", "1");
            entries.put("yca.llm.retryOnContextOverflow", "true");
            entries.put("yca.llm.maxIterations", "250");
            entries.putAll(extra);
            return entries;
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitDefaultRouteDoesNotFollowALaterProcessProviderSwitchModule.class)
    class ExplicitDefaultRouteDoesNotFollowALaterProcessProviderSwitch {
        @Test
        void explicitDefaultRouteDoesNotFollowALaterProcessProviderSwitch(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var providers = new ProviderManager(configuration, mapper);
            providers.setProvider("external");
            try (final var routed = new ChatClient(configuration, mapper, providers, Clock.systemUTC(), http)) {
                final var result = routed.chat(request("$default/test-model")
                        .withSettings(new ChatSettings("default", "$default/test-model", null, null)));
                assertEquals("hello there", result.message().content());
                assertTrue(bodies.getLast().contains("\"model\":\"test-model\""));
                assertEquals("external", providers.currentProviderId(), "routing never mutates process selection");
            }
        }
    }

    public static class ExplicitDefaultRouteDoesNotFollowALaterProcessProviderSwitchModule extends SourceModule {
        public ExplicitDefaultRouteDoesNotFollowALaterProcessProviderSwitchModule() {
            super(Map.of(
                    "yca.provider.providers.length", "2",
                    "yca.provider.providers.1.id", "external",
                    "yca.provider.providers.1.baseUrl",
                            "http://localhost:" + server.getAddress().getPort() + "/forbidden",
                    "yca.provider.providers.1.model", "test-model"));
        }
    }

    @Nested
    @FusionSupport(modules = SourceModule.class)
    class Defaults {
        @Test
        void autoFlavorFallsBackToCompletions(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var result = client.chat(request());
            assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
            assertEquals("hello there", result.message().content());
            assertEquals(3, result.usage().promptTokens());
            assertEquals(5, result.usage().totalTokens());
        }

        @Test
        void localProjectionIncludesInstructionsToolsAndCapturesFallbackRoute(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var tracker = new UsageTracker(providerManager(configuration), client, null, Clock.systemUTC());
            final var request = new ChatCompletionRequest(
                    "test-model",
                    List.of(Message.system("Keep the instructions"), Message.user("hi")),
                    null,
                    null,
                    null,
                    List.of(Tool.function("read", "Read a file", Map.of("type", "object"))),
                    "auto",
                    null,
                    null,
                    null);
            final var before = client.contextPayload(request, tracker);
            assertTrue(before.messages().stream().anyMatch(m -> m.contains("Keep the instructions")));
            assertTrue(before.toolsKey().contains("Read a file"));
            assertTrue(bodies.isEmpty(), "local projection must not send a generation request");
            final var captured = new AtomicReference<Payload>();
            client.withContextCapture(captured::set, () -> client.stream(request, _ -> {}, () -> false));
            assertTrue(captured.get().modelKey().contains("COMPLETIONS"));
            assertEquals(client.contextPayload(request, tracker), captured.get());
            assertEquals(1, bodies.size(), "capture must not add generation calls");
        }

        @Test
        void contextCaptureRestoresNestedBindingsAndClearsAfterFailure(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var outer = new CopyOnWriteArrayList<String>();
            final var inner = new CopyOnWriteArrayList<String>();
            client.withContextCapture(payload -> outer.add(payload.modelKey()), () -> {
                client.chat(request("outer-before"));
                assertThrows(
                        IOException.class,
                        () -> client.withContextCapture(payload -> inner.add(payload.modelKey()), () -> {
                            client.chat(request("inner"));
                            throw new IOException("capture failure");
                        }));
                client.chat(request("outer-after"));
                return null;
            });
            client.chat(request("unbound"));
            assertEquals(2, outer.size());
            assertTrue(outer.getFirst().contains("outer-before"));
            assertTrue(outer.getLast().contains("outer-after"));
            assertEquals(1, inner.size());
            assertTrue(inner.getFirst().contains("inner"));

            assertThrows(
                    IOException.class,
                    () -> client.withContextCapture(payload -> outer.add(payload.modelKey()), () -> {
                        throw new IOException("outer failure");
                    }));
            client.chat(request("after-failure"));
            assertEquals(2, outer.size(), "an exceptional scope must not retain its capture");
        }

        @Test
        void contextCaptureIsIsolatedAcrossConcurrentVirtualThreads(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var first = new CopyOnWriteArrayList<String>();
            final var second = new CopyOnWriteArrayList<String>();
            final var bound = new CountDownLatch(2);
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var one = executor.submit(() ->
                        client.withContextCapture(payload -> first.add(payload.modelKey()), () -> {
                            bound.countDown();
                            assertTrue(bound.await(5, TimeUnit.SECONDS));
                            return client.chat(request("first-model"));
                        }));
                final var two = executor.submit(() ->
                        client.withContextCapture(payload -> second.add(payload.modelKey()), () -> {
                            bound.countDown();
                            assertTrue(bound.await(5, TimeUnit.SECONDS));
                            return client.chat(request("second-model"));
                        }));
                one.get(10, TimeUnit.SECONDS);
                two.get(10, TimeUnit.SECONDS);
            }
            assertEquals(1, first.size());
            assertEquals(1, second.size());
            assertTrue(first.getFirst().contains("first-model"));
            assertTrue(second.getFirst().contains("second-model"));
        }

        @Test
        void contextCaptureIsIsolatedBetweenClients(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var captured = new AtomicInteger();
            try (final var other =
                    new ChatClient(configuration, mapper, providerManager(configuration), Clock.systemUTC(), http)) {
                client.withContextCapture(payload -> captured.incrementAndGet(), () -> other.chat(request()));
            }
            assertEquals(0, captured.get());
        }

        @Test
        void streamParsesDeltas(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var deltas = new CopyOnWriteArrayList<LlmStreamEvent>();
            final var message = client.stream(request(), deltas::add);
            assertEquals("hello", message.content());
            assertEquals(2, deltas.stream().filter(TextDelta.class::isInstance).count());
            assertTrue(deltas.stream()
                    .filter(End.class::isInstance)
                    .map(End.class::cast)
                    .anyMatch(d -> d.usage() != null && d.usage().totalTokens() == 5));
            assertTrue(deltas.stream()
                    .filter(End.class::isInstance)
                    .map(End.class::cast)
                    .anyMatch(d -> "stop".equals(d.finishReason())));
            assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
        }

        @Test
        void chatStructuredWithNullOthersIgnoresMissingProviderOptions(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var result = client.chatStructured(request(), "todo_steps", Map.of("type", "object"));
            assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
            assertTrue(result.structured());
            assertEquals("hello there", result.result().message().content());
        }

        @Test
        void chatStructuredKeepsStrictFieldForAlreadyStrictSchemas(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            // an already-conformant strict schema must be returned unchanged (the normalizer is
            // idempotent)
            final var schema = Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("gav", Map.of("type", "string")),
                    "required",
                    List.of("gav"),
                    "additionalProperties",
                    false);
            final var normalized = ChatClient.strictSchema(schema);
            assertEquals(schema, normalized, "idempotency: a strict schema must come back unchanged");
            assertEquals(List.of("gav"), normalized.get("required"));
        }

        @Test
        void chatStructuredDegradesOnceOnSchemaRejectionAndRemembersItPerModel(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            stop();
            final var structuredHits = new AtomicInteger();
            server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
            server.createContext("/v1/chat/completions", exchange -> {
                final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (body.contains("response_format") || body.contains("json_schema")) {
                    structuredHits.incrementAndGet();
                    respond(
                            exchange,
                            400,
                            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"json"
                                    + " was malformed\"}}",
                            "application/json");
                } else {
                    respond(exchange, 200, """
                        {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"plain answer"},"finish_reason":"stop"}]}\
                        """, "application/json");
                }
            });
            resetClient(configuration);

            // first call: the structured attempt is rejected (one request), then the plain fallback
            // succeeds
            final var first = client.chatStructured(request(), "todo_steps", Map.of("type", "object"));
            assertFalse(first.structured(), "the schema rejection must degrade to the plain request");
            assertEquals("plain answer", first.result().message().content());
            assertEquals(1, structuredHits.get(), "the rejection must not be retried within the call");

            // second call on the same (provider, model, schema): the rejection is remembered, no
            // structured
            // request
            final var second = client.chatStructured(request(), "todo_steps", Map.of("type", "object"));
            assertFalse(second.structured());
            assertEquals(1, structuredHits.get(), "the rejection must be memoized per (provider, model, schema)");

            // a different schema is not poisoned by that verdict
            final var other = client.chatStructured(request(), "memory_facts", Map.of("type", "object"));
            assertFalse(other.structured());
            assertEquals(2, structuredHits.get(), "another schema must still try the structured request once");
        }

        @Test
        void chatStructuredNInferNoConstrainedOutputDegradesOnceAndRemembers(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            // a gateway that cannot provide constrained output (eg. yupiik's local NInfer backend)
            // rejects
            // the response_format with a 400 'response_format_not_supported ... requires constrained
            // output,
            // which NInfer cannot guarantee; only {"type":"text"} is available'. That must be recognised
            // as
            // a
            // structured-output rejection: degrade once to the plain request (not a hard IOException) and
            // memoize it per (provider, model, schema) so subsequent passes skip the structured
            // round-trip.
            stop();
            final var structuredHits = new AtomicInteger();
            final var plainHits = new AtomicInteger();
            server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
            server.createContext("/v1/chat/completions", exchange -> {
                final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (body.contains("response_format") || body.contains("json_schema")) {
                    structuredHits.incrementAndGet();
                    respond(
                            exchange,
                            400,
                            "{\"error\":{\"code\":\"response_format_not_supported\",\"message\":\"this"
                                    + " response_format requires constrained output, which NInfer cannot guarantee;"
                                    + " only {\\\"type\\\":\\\"text\\\"} is"
                                    + " available\",\"param\":\"response_format\",\"type\":\"invalid_request_error\"}}",
                            "application/json");
                } else {
                    plainHits.incrementAndGet();
                    respond(exchange, 200, """
                        {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"plain answer"},"finish_reason":"stop"}]}\
                        """, "application/json");
                }
            });
            resetClient(configuration);

            // first call: the constrained-output 400 must degrade once to the plain request, not re-throw
            final var first = client.chatStructured(request(), "memory_facts", Map.of("type", "object"));
            assertFalse(first.structured(), "NInfer's constrained-output refusal must degrade to the plain path");
            assertEquals("plain answer", first.result().message().content());
            assertEquals(1, structuredHits.get());
            assertEquals(1, plainHits.get());

            // second call on the same (provider, model, schema): memoized, structured request skipped
            final var second = client.chatStructured(request(), "memory_facts", Map.of("type", "object"));
            assertFalse(second.structured());
            assertEquals(1, structuredHits.get(), "the rejection must be memoized per (provider, model, schema)");
            assertEquals(2, plainHits.get());
        }

        @Test
        void chatStructuredSurfacesNonSchema400WithoutFallingBack(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            stop();
            final var hits = new AtomicInteger();
            server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
            server.createContext("/v1/chat/completions", exchange -> {
                hits.incrementAndGet();
                respond(
                        exchange,
                        400,
                        "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens:"
                                + " 4096 > 1024, which is the maximum allowed\"}}",
                        "application/json");
            });
            resetClient(configuration);

            // a non-schema 400 (thinking/max_tokens config) must surface as-is, not be hidden by a
            // fallback
            final var failure = assertThrows(
                    IOException.class, () -> client.chatStructured(request(), "todo_steps", Map.of("type", "object")));
            assertEquals(1, hits.get(), "a non-schema 400 must not trigger a fallback request");
            assertTrue(failure.getMessage().contains("LLM request failed (400)"), failure.getMessage());
        }

        @Test
        void chatStructuredBodyParse400IsNotTreatedAsSchemaRejection(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            // freeinference rejects a request body it cannot validate (eg. max_tokens:-1) with exactly
            // "Invalid JSON or schema in request body" - the word 'schema' is a false positive for the
            // structured-output JSON-Schema rejection markers. It must NOT degrade to a plain fallback:
            // the plain request reuses the same invalid body and would fail identically, wasting a
            // round-trip.
            // The 400 surfaces directly.
            stop();
            final var hits = new AtomicInteger();
            server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
            server.createContext("/v1/chat/completions", exchange -> {
                hits.incrementAndGet();
                respond(
                        exchange,
                        400,
                        "{\"error\":{\"type\":\"unknown\",\"message\":\"Invalid JSON or schema in request"
                                + " body\",\"code\":400}}",
                        "application/json");
            });
            resetClient(configuration);

            final var failure = assertThrows(
                    IOException.class,
                    () -> client.chatStructured(request(), "memory_facts", Map.of("type", "object")));
            assertEquals(1, hits.get(), "a body-JSON-parse 400 must not trigger a plain fallback request");
            assertTrue(failure.getMessage().contains("LLM request failed (400)"), failure.getMessage());
        }

        @Test
        void modelsListsIds(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            assertEquals(
                    List.of("catalog-model", "model-a", "model-b"),
                    client.models().ids());
        }

        @Test
        void catalogReasoningLevelsDriveVariantsAndDefaultEffort(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var variants = client.variants("catalog-model");
            assertEquals(
                    List.of("low", "high", "ultra"),
                    variants.stream().map(ModelVariantConfiguration::name).toList());
            assertNull(variants.get(0).extra(), "completions variants use the dedicated reasoning_effort field");

            assertEquals(
                    "hello there",
                    client.chat(request("catalog-model"), () -> false).message().content());
            assertTrue(
                    bodies.getLast().contains("\"reasoning_effort\":\"high\""),
                    "the catalog default_reasoning_level must be sent: " + bodies.getLast());
        }

        @Test
        void modelsWithoutReasoningCatalogKeepTheHeuristics(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals(
                    List.of("low", "medium", "high"),
                    client.variants("model-a").stream()
                            .map(ModelVariantConfiguration::name)
                            .toList());
        }

        @Test
        void unconfiguredBudgetIsNotSentForCompletions(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            assertEquals("hello there", client.chat(request()).message().content());
            assertTrue(bodies.getLast().contains("\"model\":\"test-model\""), bodies.getLast());
            assertFalse(bodies.getLast().contains("\"max_tokens\""), bodies.getLast());
        }

        @Test
        void inMemorySamplingOverrideSetsTemperatureTopPAndTopK(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);
            final var settings = ChatSettings.EMPTY.withSampling(ChatClient.SamplingOverride.of(Map.of(
                    ChatClient.SamplingField.TEMPERATURE, Optional.of(0.3d),
                    ChatClient.SamplingField.TOP_P, Optional.of(0.85d),
                    ChatClient.SamplingField.TOP_K, Optional.of(32d))));

            assertEquals(
                    "hello there",
                    client.chat(request().withSettings(settings), () -> false)
                            .message()
                            .content());
            final var body = bodies.getLast();
            assertEquals(1, occurrences(body, "\"temperature\":0.3"), body);
            assertEquals(1, occurrences(body, "\"top_p\":0.85"), body);
            assertEquals(1, occurrences(body, "\"top_k\":32"), body);
        }

        @Test
        void implicitVariantReflectsInlineSamplingOverrides(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);
            final var settings = ChatSettings.EMPTY.withSampling(
                    ChatClient.SamplingOverride.of(Map.of(ChatClient.SamplingField.TEMPERATURE, Optional.of(0.25d))));

            final var implicit = client.implicitVariant(settings);
            // test-model has no implicit default; the explicit override surfaces through it
            if (implicit != null) {
                assertEquals(0.25, implicit.temperature(), 0.0);
            }
            assertNotNull(client.sampling(settings));
            client.setProvider("default");
            assertNotNull(client.sampling(settings), "provider change must not clear a session's sampling overrides");
        }

        @Test
        void setModelClearsInMemorySamplingOverrides(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);
            final var settings = ChatSettings.EMPTY.withSampling(
                    ChatClient.SamplingOverride.of(Map.of(ChatClient.SamplingField.TOP_K, Optional.of(16d))));
            assertNotNull(client.sampling(settings));
            assertNull(
                    client.sampling(settings.withModel("other-model")),
                    "model change must clear the session's sampling overrides");
        }

        @Test
        void modelsAreCachedAcrossCallsAndRefreshedOnProviderChange(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            assertEquals(
                    List.of("catalog-model", "model-a", "model-b"),
                    client.models().ids());
            assertEquals(1, modelsHits.get(), "the /v1/models endpoint must be hit once");

            assertEquals(client.models().ids(), client.models().ids());
            assertEquals(1, modelsHits.get(), "repeated calls must reuse the cached model list");

            client.setProvider("default");
            assertEquals(
                    List.of("catalog-model", "model-a", "model-b"),
                    client.models().ids());
            assertEquals(2, modelsHits.get(), "changing the provider must refresh the cached model list");
        }

        @Test
        void chatStructuredRejectionMemoExpiresAndIsRetried(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            // the structured-output rejection memoization must be time-bounded: a provider/model that
            // failed once (a transient 400, or a short outage) must be retried after the TTL instead of
            // being permanently degraded to the plain path - the memory extraction structured call is
            // the main consumer and would otherwise silently lose its JSON mode forever.
            stop();
            final var clock = new MutableClock(1_000_000_000L);
            final var structHits = new AtomicInteger();
            final var plainHits = new AtomicInteger();
            server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
            server.createContext("/v1/chat/completions", exchange -> {
                final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (body.contains("response_format") || body.contains("json_schema")) {
                    structHits.incrementAndGet();
                    respond(exchange, 400, "{\"error\":{\"message\":\"json was malformed\"}}", "application/json");
                } else {
                    plainHits.incrementAndGet();
                    respond(exchange, 200, """
                        {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"plain"},"finish_reason":"stop"}]}\
                        """, "application/json");
                }
            });
            client = new ChatClient(configuration, mapper, new ProviderManager(configuration, mapper), clock, http);

            // first call: structured rejected -> degrades once to plain, remembered
            final var first = client.chatStructured(request(), "todo_steps", Map.of("type", "object"));
            assertFalse(first.structured());
            assertEquals(1, structHits.get());
            assertEquals(1, plainHits.get());

            // while the memo is fresh, the structured path is skipped (straight to plain)
            final var second = client.chatStructured(request(), "todo_steps", Map.of("type", "object"));
            assertFalse(second.structured());
            assertEquals(1, structHits.get(), "memoized rejection must skip the structured request");
            assertEquals(2, plainHits.get());

            // after the TTL the structured path is retried (and rejected again, degrading once more)
            clock.advance(20 * 60 * 1000L);
            final var third = client.chatStructured(request(), "todo_steps", Map.of("type", "object"));
            assertFalse(third.structured());
            assertEquals(2, structHits.get(), "the expiration must re-enable the structured attempt");
            assertEquals(3, plainHits.get());
        }
    }

    @Nested
    @FusionSupport(modules = AnthropicCacheDefaultDoesNotDependOnProviderIdModule.class)
    class AnthropicCacheDefaultDoesNotDependOnProviderId {
        @Test
        void anthropicCacheDefaultDoesNotDependOnProviderId(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var body = anthropicCacheRequest(configuration);
            assertEquals(Map.of("type", "ephemeral"), body.get("cache_control"));
            final var system = (Map<?, ?>) ((List<?>) body.get("system")).getFirst();
            assertEquals(Map.of("type", "ephemeral"), system.get("cache_control"));
            assertFalse(body.containsKey("promptCache"));
        }
    }

    public static class AnthropicCacheDefaultDoesNotDependOnProviderIdModule extends SourceModule {
        public AnthropicCacheDefaultDoesNotDependOnProviderIdModule() {
            super(Map.of(
                    "yca.provider.providers.0.id",
                    "claude-whatever",
                    "yca.provider.providers.0.api",
                    "anthropic",
                    "yca.provider.providers.0.baseUrl",
                    "http://localhost:" + server.getAddress().getPort() + "/v1/messages",
                    "yca.provider.providers.0.outputTokens",
                    "64",
                    "yca.provider.providers.0.providerSpecificOptions",
                    "{}"));
        }
    }

    @Nested
    @FusionSupport(modules = AnthropicProviderOptionsCanDisablePromptCacheModule.class)
    class AnthropicProviderOptionsCanDisablePromptCache {
        @Test
        void anthropicProviderOptionsCanDisablePromptCache(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var body = anthropicCacheRequest(configuration);
            assertFalse(bodies.getLast().contains("\"cache_control\""));
            assertFalse(body.containsKey("promptCache"));
        }
    }

    public static class AnthropicProviderOptionsCanDisablePromptCacheModule extends SourceModule {
        public AnthropicProviderOptionsCanDisablePromptCacheModule() {
            super(Map.of(
                    "yca.provider.providers.0.id",
                    "claude-whatever",
                    "yca.provider.providers.0.api",
                    "anthropic",
                    "yca.provider.providers.0.baseUrl",
                    "http://localhost:" + server.getAddress().getPort() + "/v1/messages",
                    "yca.provider.providers.0.outputTokens",
                    "64",
                    "yca.provider.providers.0.providerSpecificOptions",
                    """
                    {"promptCache":false}
                    """));
        }
    }

    @Nested
    @FusionSupport(modules = AnthropicProviderOptionsCanCacheOnlySystemModule.class)
    class AnthropicProviderOptionsCanCacheOnlySystem {
        @Test
        void anthropicProviderOptionsCanCacheOnlySystem(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var body = anthropicCacheRequest(configuration);
            assertFalse(body.containsKey("cache_control"));
            final var system = (Map<?, ?>) ((List<?>) body.get("system")).getFirst();
            assertEquals(Map.of("type", "ephemeral"), system.get("cache_control"));
            assertFalse(mapper.toString(body.get("messages")).contains("\"cache_control\""));
            assertFalse(body.containsKey("promptCache"));
        }
    }

    public static class AnthropicProviderOptionsCanCacheOnlySystemModule extends SourceModule {
        public AnthropicProviderOptionsCanCacheOnlySystemModule() {
            super(Map.of(
                    "yca.provider.providers.0.id",
                    "claude-whatever",
                    "yca.provider.providers.0.api",
                    "anthropic",
                    "yca.provider.providers.0.baseUrl",
                    "http://localhost:" + server.getAddress().getPort() + "/v1/messages",
                    "yca.provider.providers.0.outputTokens",
                    "64",
                    "yca.provider.providers.0.providerSpecificOptions",
                    """
                    {"promptCache":"system"}
                    """));
        }
    }

    @Nested
    @FusionSupport(modules = AnthropicProviderOptionsPreserveCacheTtlModule.class)
    class AnthropicProviderOptionsPreserveCacheTtl {
        @Test
        void anthropicProviderOptionsPreserveCacheTtl(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var body = anthropicCacheRequest(configuration);
            final var control = Map.of("type", "ephemeral", "ttl", "1h");
            assertEquals(control, body.get("cache_control"));
            final var system = (Map<?, ?>) ((List<?>) body.get("system")).getFirst();
            assertEquals(control, system.get("cache_control"));
        }
    }

    public static class AnthropicProviderOptionsPreserveCacheTtlModule extends SourceModule {
        public AnthropicProviderOptionsPreserveCacheTtlModule() {
            super(Map.of(
                    "yca.provider.providers.0.id",
                    "claude-whatever",
                    "yca.provider.providers.0.api",
                    "anthropic",
                    "yca.provider.providers.0.baseUrl",
                    "http://localhost:" + server.getAddress().getPort() + "/v1/messages",
                    "yca.provider.providers.0.outputTokens",
                    "64",
                    "yca.provider.providers.0.providerSpecificOptions",
                    """
                    {"cache_control":{"type":"ephemeral","ttl":"1h"}}
                    """));
        }
    }

    @Nested
    @FusionSupport(modules = OpenAiDoesNotAcquireAnthropicCacheDefaultModule.class)
    class OpenAiDoesNotAcquireAnthropicCacheDefault {
        @Test
        void openAiDoesNotAcquireAnthropicCacheDefault(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            client.close();
            final var config = configuration;
            client = new ChatClient(config, mapper, new ProviderManager(config, mapper), Clock.systemUTC(), http);
            client.chat(new ChatCompletionRequest(
                    "$claude-whatever/test-model",
                    List.of(Message.system("Stable instructions"), Message.user("hi")),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
            assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
            assertFalse(bodies.getLast().contains("\"cache_control\""));
            assertFalse(bodies.getLast().contains("\"promptCache\""));
        }
    }

    public static class OpenAiDoesNotAcquireAnthropicCacheDefaultModule extends SourceModule {
        public OpenAiDoesNotAcquireAnthropicCacheDefaultModule() {
            super(Map.of(
                    "yca.provider.providers.0.id", "claude-whatever",
                    "yca.provider.providers.0.api", "completions"));
        }
    }

    @Nested
    @FusionSupport(modules = ReasoningReplayFalseModule.class)
    class ReasoningReplayFalse {
        @Test
        void reasoningReplayUsesDestinationCapabilityAndPayloadEstimates(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var history = new ChatCompletionRequest(
                    "test-model",
                    List.of(
                            Message.user("hi"),
                            new Message(
                                    "assistant", "answer", null, null, null, Map.of("thinking", "retained thought"))),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            final var enabled = false;

            client.close();
            final var config = configuration;
            client = new ChatClient(config, mapper, new ProviderManager(config, mapper), Clock.systemUTC(), http);
            final var payload = client.contextPayload(history, null);
            assertEquals(enabled, payload.messages().stream().anyMatch(m -> m.contains("retained thought")));
            client.chat(history);
            assertEquals(enabled, bodies.getLast().contains("\"reasoning_content\":\"retained thought\""));
            client.stream(history, _ -> {}, () -> false);
            assertEquals(enabled, bodies.getLast().contains("\"reasoning_content\":\"retained thought\""));
        }
    }

    public static class ReasoningReplayFalseModule extends SourceModule {
        public ReasoningReplayFalseModule() {
            super(Map.of(
                    "yca.provider.providers.0.api",
                    "completions",
                    "yca.provider.providers.0.capabilities.openai.replayReasoning",
                    "false"));
        }
    }

    @Nested
    @FusionSupport(modules = ReasoningReplayTrueModule.class)
    class ReasoningReplayTrue {
        @Test
        void reasoningReplayUsesDestinationCapabilityAndPayloadEstimates(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var history = new ChatCompletionRequest(
                    "test-model",
                    List.of(
                            Message.user("hi"),
                            new Message(
                                    "assistant", "answer", null, null, null, Map.of("thinking", "retained thought"))),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            final var enabled = true;

            client.close();
            final var config = configuration;
            client = new ChatClient(config, mapper, new ProviderManager(config, mapper), Clock.systemUTC(), http);
            final var payload = client.contextPayload(history, null);
            assertEquals(enabled, payload.messages().stream().anyMatch(m -> m.contains("retained thought")));
            client.chat(history);
            assertEquals(enabled, bodies.getLast().contains("\"reasoning_content\":\"retained thought\""));
            client.stream(history, _ -> {}, () -> false);
            assertEquals(enabled, bodies.getLast().contains("\"reasoning_content\":\"retained thought\""));
        }
    }

    public static class ReasoningReplayTrueModule extends SourceModule {
        public ReasoningReplayTrueModule() {
            super(Map.of(
                    "yca.provider.providers.0.api",
                    "completions",
                    "yca.provider.providers.0.capabilities.openai.replayReasoning",
                    "true"));
        }
    }

    @Nested
    @FusionSupport(modules = ResponsesReasoningReplayModule.class)
    class ResponsesReasoningReplay {
        @Test
        void reasoningReplayUsesDestinationCapabilityAndPayloadEstimates(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var history = new ChatCompletionRequest(
                    "test-model",
                    List.of(
                            Message.user("hi"),
                            new Message(
                                    "assistant", "answer", null, null, null, Map.of("thinking", "retained thought"))),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            client.close();
            final var config = configuration;
            client = new ChatClient(config, mapper, new ProviderManager(config, mapper), Clock.systemUTC(), http);
            assertFalse(client.contextPayload(history, null).messages().stream()
                    .anyMatch(m -> m.contains("retained thought")));
        }
    }

    public static class ResponsesReasoningReplayModule extends SourceModule {
        public ResponsesReasoningReplayModule() {
            super(Map.of(
                    "yca.provider.providers.0.api",
                    "responses",
                    "yca.provider.providers.0.capabilities.openai.replayReasoning",
                    "true"));
        }
    }

    @Nested
    @FusionSupport(modules = SessionFlavorHonorsPerSessionProviderOverrideModule.class)
    class SessionFlavorHonorsPerSessionProviderOverride {
        @Test
        void sessionFlavorHonorsPerSessionProviderOverride(@Fusion final CodingAgentConfiguration configuration) {
            // the sidebar flavor row must follow a per-session provider override (e.g. after /provider),
            // not always reflect the process-default provider. The no-arg flavor() reads the default
            // provider (bare /v1 auto -> completions) while flavor(settings) resolves the override.
            resetClient(configuration);

            // no settings -> the process-default provider's flavor
            assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
            // per-session override -> the override provider's flavor, matching model()/providerId()
            assertEquals(ApiFlavor.RESPONSES, client.flavor(new ChatSettings("responses-provider", null, null, null)));
            assertEquals(
                    "responses-provider", client.providerId(new ChatSettings("responses-provider", null, null, null)));
        }
    }

    public static class SessionFlavorHonorsPerSessionProviderOverrideModule extends SourceModule {
        public SessionFlavorHonorsPerSessionProviderOverrideModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.length", "2"),
                    Map.entry("yca.provider.providers.0.api", "completions"),
                    Map.entry("yca.provider.providers.1.id", "responses-provider"),
                    Map.entry(
                            "yca.provider.providers.1.baseUrl",
                            "http://localhost:" + server.getAddress().getPort() + "/v1"),
                    Map.entry("yca.provider.providers.1.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.1.model", "test-model"),
                    Map.entry("yca.provider.providers.1.api", "responses")));
        }
    }

    @Nested
    @FusionSupport(modules = BareV1AutoPrefersCompletionsWithoutProbingResponsesModule.class)
    class BareV1AutoPrefersCompletionsWithoutProbingResponses {
        @Test
        void bareV1AutoPrefersCompletionsWithoutProbingResponses(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            stop();
            server.createContext("/v1/responses", exchange -> {
                throw new IllegalStateException("responses endpoint must not be probed for a bare /v1 base URL");
            });
            server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, """
                {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}\
                """, "application/json"));
            resetClient(configuration);

            assertEquals("ok", client.chat(request()).message().content());
            assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
        }
    }

    public static class BareV1AutoPrefersCompletionsWithoutProbingResponsesModule extends SourceModule {
        public BareV1AutoPrefersCompletionsWithoutProbingResponsesModule() {
            super(Map.of());
        }
    }

    @Nested
    @FusionSupport(modules = StructuredRouteToResponsesModule.class)
    class StructuredRouteToResponses {
        @Test
        void chatStructuredRoutesExplicitCrossProviderReferencesAndRetainsSettings(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var targetApi = "responses";

            final var providers = new ProviderManager(configuration, mapper);
            final var targetBodies = new CopyOnWriteArrayList<String>();
            server.createContext("/target/" + targetApiPath(targetApi), exchange -> {
                targetBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                structuredRoutingResponse(exchange, targetApi);
            });
            try (final var routed = new ChatClient(configuration, mapper, providers, Clock.systemUTC(), http)) {
                final var settings = new ChatSettings(
                        "default",
                        "unused-model",
                        "careful",
                        new ChatClient.SamplingOverride(Map.of(ChatClient.SamplingField.TOP_P, Optional.of(0.42))));
                final var request = new ChatCompletionRequest(
                        "$external/test-model",
                        List.of(Message.system("keep system"), Message.user("keep user")),
                        0.23,
                        null,
                        true,
                        List.of(Tool.function("retained_tool", "keep description", Map.of("type", "object"))),
                        "none",
                        321,
                        "low",
                        Map.of("custom_payload", Map.of("keep", "value")),
                        "structured-session",
                        settings);
                final var result = routed.chatStructured(request, "routing", Map.of("type", "object"));
                assertTrue(result.structured());
                assertEquals("routed answer", result.result().message().content());
                assertEquals(1, targetBodies.size(), "only the explicit provider must receive the call");
                final var body = targetBodies.getFirst();
                assertTrue(body.contains("\"model\":\"test-model\""), body);
                assertTrue(body.contains("keep system"), body);
                assertTrue(body.contains("keep user"), body);
                assertTrue(body.contains("\"temperature\":0.23"), body);
                assertTrue(body.contains("\"top_p\":0.42"), body);
                assertTrue(body.contains("\"variant_marker\":\"retained\""), body);
                assertTrue(body.contains("\"custom_payload\":{\"keep\":\"value\"}"), body);
                assertTrue(body.contains("\"tool_choice\":\"none\""), body);
                assertTrue(body.contains("retained_tool"), body);
                assertTrue(body.contains("keep description"), body);
                assertTrue(body.contains("321"), body);
                assertTrue(body.contains("low"), body);
                assertFalse(body.contains("\"stream\":true"), body);
                assertEquals("responses".equals(targetApi), body.contains("\"text\":{\"format\":"), body);
                assertEquals("completions".equals(targetApi), body.contains("\"response_format\":"), body);
                assertFalse(body.contains("structured-session"), "session attribution stays internal");
                assertFalse(body.contains("unused-model"), "settings stay internal");
                assertEquals(settings, request.settings(), "routing must not mutate the caller's selection");
                assertEquals("structured-session", request.sessionId());
                assertEquals("default", providers.currentProviderId());
                assertTrue(bodies.isEmpty(), "the process-default provider must not receive the call");
            }
            server.removeContext("/target/" + targetApiPath(targetApi));
        }

        @Test
        void chatStructuredRoutedFallbackAndRejectionCacheStayOnTheirProvider(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var targetBodies = new CopyOnWriteArrayList<String>();
            server.createContext("/target/responses", exchange -> {
                final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                targetBodies.add(body);
                if (body.contains("json_schema")) {
                    respond(exchange, 400, "{\"error\":{\"message\":\"unsupported schema\"}}", "application/json");
                } else {
                    structuredRoutingResponse(exchange, "responses");
                }
            });
            final var providers = new ProviderManager(configuration, mapper);
            try (final var routed = new ChatClient(configuration, mapper, providers, Clock.systemUTC(), http)) {
                final var settings = new ChatSettings(
                        "default",
                        null,
                        "careful",
                        new ChatClient.SamplingOverride(Map.of(ChatClient.SamplingField.TOP_P, Optional.of(0.42))));
                final var explicit = request("$external/test-model").withSettings(settings);
                final var schema = Map.<String, Object>of("type", "object");
                assertFalse(routed.chatStructured(explicit, "routing", schema).structured());
                assertEquals(2, targetBodies.size(), "one structured attempt and one routed plain fallback");
                assertTrue(targetBodies.getFirst().contains("json_schema"));
                assertFalse(targetBodies.getLast().contains("json_schema"));
                assertFalse(routed.chatStructured(explicit, "routing", schema).structured());
                assertEquals(3, targetBodies.size(), "the explicit route must reuse its own rejection memo");
                assertFalse(routed.chatStructured(
                                request("test-model").withSettings(settings.withProviderId("external")),
                                "routing",
                                schema)
                        .structured());
                assertEquals(4, targetBodies.size(), "the same route via settings shares its rejection memo");
                for (final var body : targetBodies) {
                    assertTrue(body.contains("\"top_p\":0.42"), body);
                    assertTrue(body.contains("\"variant_marker\":\"retained\""), body);
                }
                assertTrue(
                        routed.chatStructured(request(), "routing", schema).structured(),
                        "same model and schema on the default provider must not inherit the rejection");
                assertEquals(1, bodies.size());
                assertTrue(bodies.getFirst().contains("response_format"));
                assertEquals("default", providers.currentProviderId());
            }
        }

        @Test
        void chatStructuredRouted503DoesNotFallBackOrPoisonRejectionCache(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var targetBodies = new CopyOnWriteArrayList<String>();
            server.createContext("/target/responses", exchange -> {
                targetBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (targetBodies.size() == 1) {
                    respond(exchange, 503, "{\"error\":{\"message\":\"temporarily unavailable\"}}", "application/json");
                } else {
                    structuredRoutingResponse(exchange, "responses");
                }
            });
            try (final var routed = new ChatClient(
                    configuration, mapper, new ProviderManager(configuration, mapper), Clock.systemUTC(), http)) {
                final var request = request("$external/test-model");
                final var schema = Map.<String, Object>of("type", "object");
                final var failure =
                        assertThrows(IOException.class, () -> routed.chatStructured(request, "routing", schema));
                assertTrue(failure.getMessage().contains("503"), failure.getMessage());
                assertEquals(1, targetBodies.size(), "503 must not trigger a plain fallback");
                assertTrue(routed.chatStructured(request, "routing", schema).structured());
                assertEquals(2, targetBodies.size());
                assertTrue(
                        targetBodies.stream().allMatch(body -> body.contains("json_schema")),
                        "a transient failure must not memoize a schema rejection");
                assertTrue(bodies.isEmpty());
            }
        }
    }

    public static class StructuredRouteToResponsesModule extends SourceModule {
        public StructuredRouteToResponsesModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.length", "2"),
                    Map.entry("yca.provider.providers.0.api", "completions"),
                    Map.entry("yca.provider.providers.1.id", "external"),
                    Map.entry(
                            "yca.provider.providers.1.baseUrl",
                            "http://localhost:" + server.getAddress().getPort() + "/target"),
                    Map.entry("yca.provider.providers.1.model", "test-model"),
                    Map.entry("yca.provider.providers.1.api", "responses"),
                    Map.entry("yca.provider.providers.1.tuning.variants.length", "1"),
                    Map.entry("yca.provider.providers.1.tuning.variants.0.name", "careful"),
                    Map.entry(
                            "yca.provider.providers.1.tuning.variants.0.extra", "{\"variant_marker\":\"retained\"}")));
        }
    }

    @Nested
    @FusionSupport(modules = StructuredRouteToCompletionsModule.class)
    class StructuredRouteToCompletions {
        @Test
        void chatStructuredRoutesExplicitCrossProviderReferencesAndRetainsSettings(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var targetApi = "completions";

            final var providers = new ProviderManager(configuration, mapper);
            final var targetBodies = new CopyOnWriteArrayList<String>();
            server.createContext("/target/" + targetApiPath(targetApi), exchange -> {
                targetBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                structuredRoutingResponse(exchange, targetApi);
            });
            try (final var routed = new ChatClient(configuration, mapper, providers, Clock.systemUTC(), http)) {
                final var settings = new ChatSettings(
                        "default",
                        "unused-model",
                        "careful",
                        new ChatClient.SamplingOverride(Map.of(ChatClient.SamplingField.TOP_P, Optional.of(0.42))));
                final var request = new ChatCompletionRequest(
                        "$external/test-model",
                        List.of(Message.system("keep system"), Message.user("keep user")),
                        0.23,
                        null,
                        true,
                        List.of(Tool.function("retained_tool", "keep description", Map.of("type", "object"))),
                        "none",
                        321,
                        "low",
                        Map.of("custom_payload", Map.of("keep", "value")),
                        "structured-session",
                        settings);
                final var result = routed.chatStructured(request, "routing", Map.of("type", "object"));
                assertTrue(result.structured());
                assertEquals("routed answer", result.result().message().content());
                assertEquals(1, targetBodies.size(), "only the explicit provider must receive the call");
                final var body = targetBodies.getFirst();
                assertTrue(body.contains("\"model\":\"test-model\""), body);
                assertTrue(body.contains("keep system"), body);
                assertTrue(body.contains("keep user"), body);
                assertTrue(body.contains("\"temperature\":0.23"), body);
                assertTrue(body.contains("\"top_p\":0.42"), body);
                assertTrue(body.contains("\"variant_marker\":\"retained\""), body);
                assertTrue(body.contains("\"custom_payload\":{\"keep\":\"value\"}"), body);
                assertTrue(body.contains("\"tool_choice\":\"none\""), body);
                assertTrue(body.contains("retained_tool"), body);
                assertTrue(body.contains("keep description"), body);
                assertTrue(body.contains("321"), body);
                assertTrue(body.contains("low"), body);
                assertFalse(body.contains("\"stream\":true"), body);
                assertEquals("responses".equals(targetApi), body.contains("\"text\":{\"format\":"), body);
                assertEquals("completions".equals(targetApi), body.contains("\"response_format\":"), body);
                assertFalse(body.contains("structured-session"), "session attribution stays internal");
                assertFalse(body.contains("unused-model"), "settings stay internal");
                assertEquals(settings, request.settings(), "routing must not mutate the caller's selection");
                assertEquals("structured-session", request.sessionId());
                assertEquals("default", providers.currentProviderId());
                assertTrue(bodies.isEmpty(), "the process-default provider must not receive the call");
            }
            server.removeContext("/target/" + targetApiPath(targetApi));
        }
    }

    public static class StructuredRouteToCompletionsModule extends SourceModule {
        public StructuredRouteToCompletionsModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.length", "2"),
                    Map.entry("yca.provider.providers.0.api", "responses"),
                    Map.entry("yca.provider.providers.1.id", "external"),
                    Map.entry(
                            "yca.provider.providers.1.baseUrl",
                            "http://localhost:" + server.getAddress().getPort() + "/target"),
                    Map.entry("yca.provider.providers.1.model", "test-model"),
                    Map.entry("yca.provider.providers.1.api", "completions"),
                    Map.entry("yca.provider.providers.1.tuning.variants.length", "1"),
                    Map.entry("yca.provider.providers.1.tuning.variants.0.name", "careful"),
                    Map.entry(
                            "yca.provider.providers.1.tuning.variants.0.extra", "{\"variant_marker\":\"retained\"}")));
        }
    }

    @Nested
    @FusionSupport(modules = StrictResponsesModule.class)
    class StrictResponses {
        @Test
        void chatStructuredUsesStrictTextFormatOnResponsesWire(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            // RESPONSES and CODEX share the Responses wire: the structured field must be text.format with
            // an OpenAI-strict schema (not the chat-completions response_format that codex rejects), and
            // every property must be required (optional ones widened to a nullable type).
            final var schema = Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "facts",
                            Map.of(
                                    "type",
                                    "array",
                                    "items",
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "text", Map.of("type", "string"),
                                                    "path", Map.of("type", "string")),
                                            "required",
                                            List.of("text")))),
                    "required",
                    List.of("facts"));
            final var api = "responses";

            stop();
            server.createContext("/v1/responses", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, """
                    {"id":"r1","model":"test-model","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"{\\"facts\\":[]}"}],"usage":{}}]}\
                    """, "application/json");
            });
            resetClient(configuration);

            final var result = client.chatStructured(request(), "memory_facts", schema);
            assertTrue(result.structured(), api + " must produce a structured result");
            assertEquals(ApiFlavor.valueOf(api.toUpperCase()), client.flavor(), api);
            final var body = bodies.getLast();
            // the Responses wire uses text.format, never the chat-completions response_format field
            assertTrue(body.contains("\"text\":{\"format\":"), api + " must use text.format: " + body);
            assertTrue(body.contains("\"type\":\"json_schema\""), api + " body: " + body);
            assertFalse(body.contains("\"response_format\""), api + " must not send response_format: " + body);
            // strict mode on, and the schema normalized: additionalProperties:false + every property
            // required
            assertTrue(body.contains("\"strict\":true"), api + " body: " + body);
            assertTrue(body.contains("\"additionalProperties\":false"), api + " body: " + body);
            assertTrue(body.contains("\"required\":[\"facts\"]"), api + " top object required: " + body);
            // the optional 'path' property is widened to a nullable type and listed in the item's
            // required
            assertTrue(
                    body.contains("\"path\":{\"type\":[\"string\",\"null\"]}"),
                    api + " optional property must widen to a nullable type: " + body);
            // every item property must be required (order of the properties map is not deterministic)
            assertTrue(
                    REQUIRED_PROPERTIES.matcher(body).matches()
                            || body.contains("\"required\":[\"path\",\"text\"]")
                            || body.contains("\"required\":[\"text\",\"path\"]"),
                    api + " all item properties must be required: " + body);
        }
    }

    public static class StrictResponsesModule extends SourceModule {
        public StrictResponsesModule() {
            super(Map.of("yca.provider.providers.0.api", "responses"));
        }
    }

    @Nested
    @FusionSupport(modules = StrictCodexModule.class)
    class StrictCodex {
        @Test
        void chatStructuredUsesStrictTextFormatOnResponsesWire(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            // RESPONSES and CODEX share the Responses wire: the structured field must be text.format with
            // an OpenAI-strict schema (not the chat-completions response_format that codex rejects), and
            // every property must be required (optional ones widened to a nullable type).
            final var schema = Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "facts",
                            Map.of(
                                    "type",
                                    "array",
                                    "items",
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "text", Map.of("type", "string"),
                                                    "path", Map.of("type", "string")),
                                            "required",
                                            List.of("text")))),
                    "required",
                    List.of("facts"));
            final var api = "codex";

            stop();
            server.createContext("/v1/responses", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, """
                    {"id":"r1","model":"test-model","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"{\\"facts\\":[]}"}],"usage":{}}]}\
                    """, "application/json");
            });
            resetClient(configuration);

            final var result = client.chatStructured(request(), "memory_facts", schema);
            assertTrue(result.structured(), api + " must produce a structured result");
            assertEquals(ApiFlavor.valueOf(api.toUpperCase()), client.flavor(), api);
            final var body = bodies.getLast();
            // the Responses wire uses text.format, never the chat-completions response_format field
            assertTrue(body.contains("\"text\":{\"format\":"), api + " must use text.format: " + body);
            assertTrue(body.contains("\"type\":\"json_schema\""), api + " body: " + body);
            assertFalse(body.contains("\"response_format\""), api + " must not send response_format: " + body);
            // strict mode on, and the schema normalized: additionalProperties:false + every property
            // required
            assertTrue(body.contains("\"strict\":true"), api + " body: " + body);
            assertTrue(body.contains("\"additionalProperties\":false"), api + " body: " + body);
            assertTrue(body.contains("\"required\":[\"facts\"]"), api + " top object required: " + body);
            // the optional 'path' property is widened to a nullable type and listed in the item's
            // required
            assertTrue(
                    body.contains("\"path\":{\"type\":[\"string\",\"null\"]}"),
                    api + " optional property must widen to a nullable type: " + body);
            // every item property must be required (order of the properties map is not deterministic)
            assertTrue(
                    REQUIRED_PROPERTIES.matcher(body).matches()
                            || body.contains("\"required\":[\"path\",\"text\"]")
                            || body.contains("\"required\":[\"text\",\"path\"]"),
                    api + " all item properties must be required: " + body);
        }

        @Test
        void codexDefaultsToMediumReasoningEffortOnTheWire(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            stop();
            server.createContext("/v1/responses", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                final byte[] sse = ("event: response.created\n"
                                + "data: {\"type\":\"response.created\"}\n\n"
                                + "event: response.output_text.delta\n"
                                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\",\"output_index\":0}\n\n"
                                + "event: response.completed\n"
                                + "data: {\"type\":\"response.completed\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("content-type", "text/event-stream");
                exchange.sendResponseHeaders(200, sse.length);
                try (final var out = exchange.getResponseBody()) {
                    out.write(sse);
                }
            });
            // a codex-flavored provider (api=codex routes to the Codex dialect on the /responses route)
            resetClient(configuration);

            assertEquals("hello", client.chat(request()).message().content());
            final var body = bodies.getLast();
            // codex must actually ask for reasoning so the backend streams a thought block
            assertTrue(
                    body.contains("\"effort\":\"medium\""),
                    "codex must default the reasoning effort to medium: " + body);
            assertTrue(body.contains("\"summary\":\"detailed\""), "codex must request the reasoning summary: " + body);
        }

        @Test
        void codexDropsStaleEncryptedReasoningAndRetries(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            stop();
            final AtomicInteger codexCalls = new AtomicInteger();
            server.createContext("/v1/responses", exchange -> {
                final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                bodies.add(body);
                if (codexCalls.incrementAndGet() == 1) {
                    // the backend rejects the replayed blob: its encryption keys rotated on the server
                    exchange.getResponseHeaders().set("content-type", "application/json");
                    final byte[] err =
                            "{\"error\":{\"message\":\"invalid_encrypted_content\"}}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, err.length);
                    try (final var out = exchange.getResponseBody()) {
                        out.write(err);
                    }
                } else {
                    final byte[] sse = ("event: response.created\n"
                                    + "data: {\"type\":\"response.created\"}\n\n"
                                    + "event: response.output_text.delta\n"
                                    + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\",\"output_index\":0}\n\n"
                                    + "event: response.completed\n"
                                    + "data: {\"type\":\"response.completed\"}\n\n")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("content-type", "text/event-stream");
                    exchange.sendResponseHeaders(200, sse.length);
                    try (final var out = exchange.getResponseBody()) {
                        out.write(sse);
                    }
                }
            });
            resetClient(configuration);

            // a conversation carrying an assistant message with a persisted opaque reasoning blob
            final var history = List.of(
                    Message.user("hi"),
                    new Message(
                            "assistant",
                            "earlier answer",
                            null,
                            null,
                            null,
                            Map.of(
                                    "reasoningItems",
                                    List.of(Map.of("id", "rs_1", "encrypted_content", "gAAAAStaleBlob")))));
            final var request =
                    new ChatCompletionRequest("test-model", history, null, null, null, null, null, null, null, null);
            assertEquals("ok", client.chat(request).message().content());

            // first request carried the stale blob, the retry dropped it and succeeded
            assertEquals(2, bodies.size(), "the invalid_encrypted_content rejection must retry once");
            assertTrue(bodies.get(0).contains("gAAAAStaleBlob"), "first attempt must carry the blob: " + bodies.get(0));
            assertFalse(bodies.get(1).contains("gAAAAStaleBlob"), "retry must drop the stale blob: " + bodies.get(1));
        }
    }

    public static class StrictCodexModule extends SourceModule {
        public StrictCodexModule() {
            super(Map.of("yca.provider.providers.0.api", "codex"));
        }
    }

    @Nested
    @FusionSupport(modules = ChatStructuredStrictifiesSchemaOnCompletionsWireModule.class)
    class ChatStructuredStrictifiesSchemaOnCompletionsWire {
        @Test
        void chatStructuredStrictifiesSchemaOnCompletionsWire(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            // the chat-completions response_format wire enforces OpenAI strict-mode schemas (every
            // property
            // in
            // required, optional ones widened to a nullable type) - a strict validator (eg. Azure /
            // litellm)
            // rejects the raw caller schema with a 400 e.g. 'required ... Missing p'. The COMPLETIONS
            // branch
            // must therefore normalize with strictSchema() like RESPONSES/CODEX.
            final var schema = Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "facts",
                            Map.of(
                                    "type",
                                    "array",
                                    "items",
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "text", Map.of("type", "string"),
                                                    "path", Map.of("type", "string")),
                                            "required",
                                            List.of("text")))),
                    "required",
                    List.of("facts"));
            stop();
            server.createContext("/v1/chat/completions", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, """
                    {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"{\\"facts\\":[]}"},"finish_reason":"stop"}],"usage":{}}\
                    """, "application/json");
            });
            resetClient(configuration);

            final var result = client.chatStructured(request(), "memory_facts", schema);
            assertTrue(result.structured(), "completions must produce a structured result");
            assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
            final var body = bodies.getLast();
            // the completions wire carries response_format with an OpenAI-strict schema
            assertTrue(body.contains("\"response_format\""), "completions must use response_format: " + body);
            assertTrue(body.contains("\"type\":\"json_schema\""), "completions body: " + body);
            assertTrue(body.contains("\"strict\":true"), "completions body: " + body);
            // top object and items normalized: additionalProperties:false + every property required
            assertTrue(body.contains("\"additionalProperties\":false"), "completions body: " + body);
            assertTrue(body.contains("\"required\":[\"facts\"]"), "completions top object required: " + body);
            // the optional 'path' property is widened to a nullable type and listed in the item's
            // required
            assertTrue(
                    body.contains("\"path\":{\"type\":[\"string\",\"null\"]}"),
                    "completions optional property must widen to a nullable type: " + body);
            assertTrue(
                    body.contains("\"required\":[\"path\",\"text\"]")
                            || body.contains("\"required\":[\"text\",\"path\"]"),
                    "completions all item properties must be required: " + body);
        }
    }

    public static class ChatStructuredStrictifiesSchemaOnCompletionsWireModule extends SourceModule {
        public ChatStructuredStrictifiesSchemaOnCompletionsWireModule() {
            super(Map.of("yca.provider.providers.0.api", "completions"));
        }
    }

    @Nested
    @FusionSupport(modules = ReservedProviderOptionsNeverReachTheWireModule.class)
    class ReservedProviderOptionsNeverReachTheWire {
        @Test
        void reservedProviderOptionsNeverReachTheWire(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            // a free-form option (providerSpecificOptions/model tuning/variant extras) must not duplicate
            // a protocol field: the encoder flattens extras last, so a 'stream' key would flip every call
            resetClient(configuration);

            final var result = client.chat(request());
            assertEquals("hello there", result.message().content());
            final var body = bodies.get(bodies.size() - 1);
            assertEquals(1, occurrences(body, "\"stream\""), "exactly one stream field expected: " + body);
            assertTrue(body.contains("\"stream\":false"), "the typed stream value must win: " + body);
            assertEquals(0, occurrences(body, "\"hijacked\""), "the reserved model key must be dropped: " + body);
            assertTrue(body.contains("\"temperature\":0.5"), "non-reserved options still flow through: " + body);
        }
    }

    public static class ReservedProviderOptionsNeverReachTheWireModule extends SourceModule {
        public ReservedProviderOptionsNeverReachTheWireModule() {
            super(Map.of(
                    "yca.provider.providers.0.providerSpecificOptions",
                    "{\"stream\":true,\"model\":\"hijacked\",\"temperature\":0.5}"));
        }
    }

    @Nested
    @FusionSupport(modules = CodexInternalOptionsNoLongerReservedInProviderSpecificOptionsModule.class)
    class CodexInternalOptionsNoLongerReservedInProviderSpecificOptions {
        @Test
        void codexInternalOptionsNoLongerReservedInProviderSpecificOptions(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            // after the migration these internal options live in capabilities.codex; a
            // codex_client_version
            // or reasoning_include key placed in providerSpecificOptions is now an ordinary wire option
            // (no longer silently dropped), while unrelated options still flow through
            resetClient(configuration);

            final var result = client.chat(request());
            assertEquals("hello there", result.message().content());
            final var body = bodies.get(bodies.size() - 1);
            assertEquals(1, occurrences(body, "codex_client_version"), "now a plain wire option: " + body);
            assertTrue(body.contains("\"codex_client_version\":210"), body);
            assertTrue(body.contains("\"reasoning_include\":true"), body);
            assertTrue(body.contains("\"temperature\":0.5"), "unrelated options still flow through: " + body);
        }
    }

    public static class CodexInternalOptionsNoLongerReservedInProviderSpecificOptionsModule extends SourceModule {
        public CodexInternalOptionsNoLongerReservedInProviderSpecificOptionsModule() {
            super(Map.of(
                    "yca.provider.providers.0.providerSpecificOptions",
                    "{\"codex_client_version\":210,\"reasoning_include\":true,\"temperature\":0.5}"));
        }
    }

    @Nested
    @FusionSupport(modules = CodexCapabilitiesNeverLeakToTheWireModule.class)
    class CodexCapabilitiesNeverLeakToTheWire {
        @Test
        void codexCapabilitiesNeverLeakToTheWire(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            // capabilities.codex.clientVersion and includeReasoning are consumed for model gating and the
            // encrypted-reasoning include; they are internal and must never be emitted as wire fields
            resetClient(configuration);

            final var result = client.chat(request());
            assertEquals("hello there", result.message().content());
            final var body = bodies.get(bodies.size() - 1);
            assertEquals(0, occurrences(body, "codex_client_version"), "capabilities never leak to the wire: " + body);
            assertEquals(0, occurrences(body, "capabilities"), "capabilities config never leaks to the wire: " + body);
            assertEquals(0, occurrences(body, "codex"), "no codex-only option leaks on a plain completion: " + body);
            assertTrue(body.contains("\"model\":\"test-model\""), "the plain request body is preserved: " + body);
        }
    }

    public static class CodexCapabilitiesNeverLeakToTheWireModule extends SourceModule {
        public CodexCapabilitiesNeverLeakToTheWireModule() {
            super(Map.of(
                    "yca.provider.providers.0.capabilities.codex.clientVersion", "210",
                    "yca.provider.providers.0.capabilities.codex.includeReasoning", "true"));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderReasoningConfigFullyOverridesTheVariantExtrasModule.class)
    class ProviderReasoningConfigFullyOverridesTheVariantExtras {
        @Test
        void providerReasoningConfigFullyOverridesTheVariantExtras(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            // an explicitly configured reasoning shape (thinking/output_config) must win verbatim over
            // the
            // selected variant extras: the variant must not fill in missing sub-keys (e.g. a 'display')
            resetClient(configuration);

            final var settings = ChatSettings.EMPTY.withVariant("adaptive-high");
            assertEquals(
                    "hello there",
                    client.chat(request().withSettings(settings), () -> false)
                            .message()
                            .content());
            final var body = bodies.get(bodies.size() - 1);
            assertTrue(body.contains("\"type\":\"adaptive\""), body);
            assertTrue(body.contains("\"output_config\""), body);
            assertTrue(
                    body.contains("\"effort\":\"max\""), "the user effort must win over the variant's high: " + body);
            assertFalse(body.contains("\"display\":\"summarized\""), "the variant's display must not leak in: " + body);
        }
    }

    public static class ProviderReasoningConfigFullyOverridesTheVariantExtrasModule extends SourceModule {
        public ProviderReasoningConfigFullyOverridesTheVariantExtrasModule() {
            super(
                    Map.of(
                            "yca.provider.providers.0.providerSpecificOptions",
                            "{\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"max\"}}",
                            "yca.provider.providers.0.tuning.variants.length",
                            "1",
                            "yca.provider.providers.0.tuning.variants.0.name",
                            "adaptive-high",
                            "yca.provider.providers.0.tuning.variants.0.extra",
                            "{\"thinking\":{\"type\":\"adaptive\",\"display\":\"summarized\"},\"output_config\":{\"effort\":\"high\"}}"));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredVariantsAndEffortWinOverTheCatalogModule.class)
    class ConfiguredVariantsAndEffortWinOverTheCatalog {
        @Test
        void configuredVariantsAndEffortWinOverTheCatalog(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);

            assertEquals(
                    List.of("custom"),
                    client.variants("catalog-model").stream()
                            .map(ModelVariantConfiguration::name)
                            .toList());
            client.chat(request("catalog-model"), () -> false);
            assertTrue(
                    bodies.getLast().contains("\"reasoning_effort\":\"low\""),
                    "the configured effort must win over the catalog default: " + bodies.getLast());

            client.chat(request("catalog-model").withSettings(ChatSettings.EMPTY.withVariant("custom")), () -> false);
            assertTrue(
                    bodies.getLast().contains("\"reasoning_effort\":\"medium\""),
                    "the selected configured variant must win: " + bodies.getLast());
        }
    }

    public static class ConfiguredVariantsAndEffortWinOverTheCatalogModule extends SourceModule {
        public ConfiguredVariantsAndEffortWinOverTheCatalogModule() {
            super(Map.of(
                    "yca.provider.providers.0.tuning.reasoningEffort",
                    "low",
                    "yca.provider.providers.0.tuning.variants.length",
                    "1",
                    "yca.provider.providers.0.tuning.variants.0.name",
                    "custom",
                    "yca.provider.providers.0.tuning.variants.0.reasoningEffort",
                    "medium"));
        }
    }

    @Nested
    @FusionSupport(modules = CatalogFetchFailureFallsBackToTheHeuristicsModule.class)
    class CatalogFetchFailureFallsBackToTheHeuristics {
        @Test
        void catalogFetchFailureFallsBackToTheHeuristics(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);

            assertEquals(
                    List.of("low", "medium", "high"),
                    client.variants("model-a").stream()
                            .map(ModelVariantConfiguration::name)
                            .toList());
        }
    }

    public static class CatalogFetchFailureFallsBackToTheHeuristicsModule extends SourceModule {
        public CatalogFetchFailureFallsBackToTheHeuristicsModule() {
            super(Map.of(
                    "yca.provider.providers.0.baseUrl",
                    "http://localhost:1/v1",
                    "yca.provider.providers.0.timeoutSeconds",
                    "1"));
        }
    }

    @Nested
    @FusionSupport(modules = CodexBaseUrlIsDetectedByAutoFlavorModule.class)
    class CodexBaseUrlIsDetectedByAutoFlavor {
        @Test
        void codexBaseUrlIsDetectedByAutoFlavor(@Fusion final CodingAgentConfiguration configuration) {
            resetClient(configuration);

            // a .../codex base URL resolves to the CODEX flavor without any network probe
            assertEquals(ApiFlavor.CODEX, client.flavor());
            // the chat endpoint derived from .../codex carries the /responses route
            assertEquals(
                    "https://chatgpt.com/backend-api/codex/responses",
                    ChatRoutes.chatEndpoint("https://chatgpt.com/backend-api/codex", ApiFlavor.CODEX));
        }
    }

    public static class CodexBaseUrlIsDetectedByAutoFlavorModule extends SourceModule {
        public CodexBaseUrlIsDetectedByAutoFlavorModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.0.baseUrl", "https://chatgpt.com/backend-api/codex"),
                    Map.entry("yca.provider.providers.0.apiKey", ""),
                    Map.entry("yca.provider.providers.0.model", "gpt-5.3-codex"),
                    Map.entry("yca.provider.providers.0.contextWindow", "400000")));
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitCompletionsFlavorDoesNotProbeResponsesModule.class)
    class ExplicitCompletionsFlavorDoesNotProbeResponses {
        @Test
        void explicitCompletionsFlavorDoesNotProbeResponses(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            stop();
            server.createContext("/v1/responses", exchange -> {
                throw new IllegalStateException("responses endpoint must not be called");
            });
            server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, """
                {"id":"c1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}\
                """, "application/json"));
            resetClient(configuration);

            assertEquals("ok", client.chat(request()).message().content());
            assertEquals(ApiFlavor.COMPLETIONS, client.flavor());
        }
    }

    public static class ExplicitCompletionsFlavorDoesNotProbeResponsesModule extends SourceModule {
        public ExplicitCompletionsFlavorDoesNotProbeResponsesModule() {
            super(Map.ofEntries(Map.entry("yca.provider.providers.0.api", "completions")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredOutputTokensIsSentAsMaxTokensModule.class)
    class ConfiguredOutputTokensIsSentAsMaxTokens {
        @Test
        void configuredOutputTokensIsSentAsMaxTokens(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);

            assertEquals("hello there", client.chat(request()).message().content());
            assertTrue(bodies.getLast().contains("\"max_tokens\":1234"), bodies.getLast());
        }
    }

    public static class ConfiguredOutputTokensIsSentAsMaxTokensModule extends SourceModule {
        public ConfiguredOutputTokensIsSentAsMaxTokensModule() {
            super(Map.of("yca.provider.providers.0.outputTokens", "1234"));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredOutputTokensIsSentAsMaxOutputTokensForResponsesModule.class)
    class ConfiguredOutputTokensIsSentAsMaxOutputTokensForResponses {
        @Test
        void configuredOutputTokensIsSentAsMaxOutputTokensForResponses(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            stop();
            server.createContext("/v1/responses", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, """
                    {"id":"r1","model":"test-model","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hello there"}]}],"usage":{}}\
                    """, "application/json");
            });
            resetClient(configuration);

            assertEquals("hello there", client.chat(request()).message().content());
            assertEquals(ApiFlavor.RESPONSES, client.flavor());
            assertTrue(bodies.getLast().contains("\"max_output_tokens\":1234"), bodies.getLast());
        }
    }

    public static class ConfiguredOutputTokensIsSentAsMaxOutputTokensForResponsesModule extends SourceModule {
        public ConfiguredOutputTokensIsSentAsMaxOutputTokensForResponsesModule() {
            super(Map.of(
                    "yca.provider.providers.0.api", "responses",
                    "yca.provider.providers.0.outputTokens", "1234"));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderSpecificTemperatureIsPromotedAndSentExactlyOnceModule.class)
    class ProviderSpecificTemperatureIsPromotedAndSentExactlyOnce {
        @Test
        void providerSpecificTemperatureIsPromotedAndSentExactlyOnce(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            resetClient(configuration);

            assertEquals("hello there", client.chat(request()).message().content());
            assertEquals(1, occurrences(bodies.getLast(), "\"temperature\":0.2"), bodies.getLast());
        }

        @Test
        void extrasTemperatureIsPromotedOnTheStreamPath(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);

            client.stream(request(), delta -> {
                // no-op consumer: the assertion is on the wire body below
            });
            assertEquals(1, occurrences(bodies.getLast(), "\"temperature\":0.2"), bodies.getLast());
        }
    }

    public static class ProviderSpecificTemperatureIsPromotedAndSentExactlyOnceModule extends SourceModule {
        public ProviderSpecificTemperatureIsPromotedAndSentExactlyOnceModule() {
            super(Map.of("yca.provider.providers.0.providerSpecificOptions", "{\"temperature\":0.2}"));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderSpecificMaxTokensIsPromotedWithoutDuplicateWireKeysModule.class)
    class ProviderSpecificMaxTokensIsPromotedWithoutDuplicateWireKeys {
        @Test
        void providerSpecificMaxTokensIsPromotedWithoutDuplicateWireKeys(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            resetClient(configuration);

            assertEquals("hello there", client.chat(request()).message().content());
            assertEquals(1, occurrences(bodies.getLast(), "\"max_tokens\":777"), bodies.getLast());
        }
    }

    public static class ProviderSpecificMaxTokensIsPromotedWithoutDuplicateWireKeysModule extends SourceModule {
        public ProviderSpecificMaxTokensIsPromotedWithoutDuplicateWireKeysModule() {
            super(Map.of("yca.provider.providers.0.providerSpecificOptions", "{\"max_tokens\":777}"));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderSpecificTemperatureIsPromotedForResponsesFlavorModule.class)
    class ProviderSpecificTemperatureIsPromotedForResponsesFlavor {
        @Test
        void providerSpecificTemperatureIsPromotedForResponsesFlavor(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            stop();
            server.createContext("/v1/responses", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, """
                    {"id":"r1","model":"test-model","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hello there"}]}],"usage":{}}\
                    """, "application/json");
            });
            resetClient(configuration);

            assertEquals("hello there", client.chat(request()).message().content());
            assertEquals(1, occurrences(bodies.getLast(), "\"temperature\":0.2"), bodies.getLast());
        }
    }

    public static class ProviderSpecificTemperatureIsPromotedForResponsesFlavorModule extends SourceModule {
        public ProviderSpecificTemperatureIsPromotedForResponsesFlavorModule() {
            super(Map.of(
                    "yca.provider.providers.0.api", "responses",
                    "yca.provider.providers.0.providerSpecificOptions", "{\"temperature\":0.2}"));
        }
    }

    @Nested
    @FusionSupport(modules = ProviderModelLeadingSpaceIsTrimmedOffTheWireModule.class)
    class ProviderModelLeadingSpaceIsTrimmedOffTheWire {
        @Test
        void providerModelLeadingSpaceIsTrimmedOffTheWire(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);

            assertEquals("hello there", client.chat(request(null)).message().content());
            assertTrue(bodies.getLast().contains("\"model\":\"deepseek-v4-flash\""), bodies.getLast());
            assertFalse(bodies.getLast().contains("\"model\":\" deepseek-v4-flash\""), bodies.getLast());
        }
    }

    public static class ProviderModelLeadingSpaceIsTrimmedOffTheWireModule extends SourceModule {
        public ProviderModelLeadingSpaceIsTrimmedOffTheWireModule() {
            super(Map.of("yca.provider.providers.0.model", " deepseek-v4-flash"));
        }
    }

    @Nested
    @FusionSupport(modules = SelectedVariantAppliesTemperatureTopPAndTopKModule.class)
    class SelectedVariantAppliesTemperatureTopPAndTopK {
        @Test
        void selectedVariantAppliesTemperatureTopPAndTopK(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);
            final var settings = ChatSettings.EMPTY.withVariant("balanced");
            assertEquals(1, client.variants(settings).size());

            assertEquals(
                    "hello there",
                    client.chat(request().withSettings(settings), () -> false)
                            .message()
                            .content());
            assertEquals("balanced", client.variant(settings));
            final var body = bodies.getLast();
            assertEquals(1, occurrences(body, "\"temperature\":0.7"), body);
            assertEquals(1, occurrences(body, "\"top_p\":0.9"), body);
            assertEquals(1, occurrences(body, "\"top_k\":40"), body);
        }
    }

    public static class SelectedVariantAppliesTemperatureTopPAndTopKModule extends SourceModule {
        public SelectedVariantAppliesTemperatureTopPAndTopKModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.0.tuning.variants.length", "1"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.name", "balanced"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.temperature", "0.7"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.top_p", "0.9"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.top_k", "40")));
        }
    }

    @Nested
    @FusionSupport(modules = UnknownVariantNameIsIgnoredAndImplicitDefaultsApplyModule.class)
    class UnknownVariantNameIsIgnoredAndImplicitDefaultsApply {
        @Test
        void unknownVariantNameIsIgnoredAndImplicitDefaultsApply(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);
            final var settings = ChatSettings.EMPTY.withVariant("missing");
            assertEquals(
                    "hello there",
                    client.chat(request().withSettings(settings), () -> false)
                            .message()
                            .content());
            assertEquals("missing", client.variant(settings));
            final var body = bodies.getLast();
            // no configured variant was applied and the model has no implicit default
            assertFalse(body.contains("\"temperature\""), body);
        }

        @Test
        void noneSamplingOverrideForcesOmissionFromTheWireEvenWithAVariant(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            resetClient(configuration);
            final var settings = ChatSettings.EMPTY
                    .withVariant("balanced")
                    .withSampling(ChatClient.SamplingOverride.of(
                            Map.of(ChatClient.SamplingField.TEMPERATURE, Optional.empty())));

            assertEquals(
                    "hello there",
                    client.chat(request().withSettings(settings), () -> false)
                            .message()
                            .content());
            final var body = bodies.getLast();
            assertFalse(body.contains("\"temperature\""), body);
        }
    }

    public static class UnknownVariantNameIsIgnoredAndImplicitDefaultsApplyModule extends SourceModule {
        public UnknownVariantNameIsIgnoredAndImplicitDefaultsApplyModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.0.tuning.variants.length", "1"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.name", "balanced"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.temperature", "0.7")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredDefaultVariantAppliesWhenNoSessionVariantModule.class)
    class ConfiguredDefaultVariantAppliesWhenNoSessionVariant {
        @Test
        void configuredDefaultVariantAppliesWhenNoSessionVariant(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);

            assertEquals(
                    "hello there",
                    client.chat(request().withSettings(ChatSettings.EMPTY), () -> false)
                            .message()
                            .content());
            final var body = bodies.getLast();
            assertEquals(1, occurrences(body, "\"temperature\":0.7"), body);
            // the display helper surfaces the resolved default variant, not the process default
            assertEquals("balanced", client.resolvedVariant(ChatSettings.EMPTY));
            assertNull(client.variant(ChatSettings.EMPTY), "no explicit session selection exists");
        }
    }

    public static class ConfiguredDefaultVariantAppliesWhenNoSessionVariantModule extends SourceModule {
        public ConfiguredDefaultVariantAppliesWhenNoSessionVariantModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.0.tuning.variants.length", "1"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.name", "balanced"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.temperature", "0.7"),
                    Map.entry("yca.provider.providers.0.tuning.defaultVariant", "balanced")));
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitSessionVariantWinsOverConfiguredDefaultModule.class)
    class ExplicitSessionVariantWinsOverConfiguredDefault {
        @Test
        void explicitSessionVariantWinsOverConfiguredDefault(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);
            final var settings = ChatSettings.EMPTY.withVariant("fast");

            assertEquals(
                    "hello there",
                    client.chat(request().withSettings(settings), () -> false)
                            .message()
                            .content());
            final var body = bodies.getLast();
            assertEquals(1, occurrences(body, "\"temperature\":0.3"), body);
            assertFalse(body.contains("\"temperature\":0.7"), body);
            assertEquals("fast", client.resolvedVariant(settings), "explicit selection wins in the display helper too");
        }
    }

    public static class ExplicitSessionVariantWinsOverConfiguredDefaultModule extends SourceModule {
        public ExplicitSessionVariantWinsOverConfiguredDefaultModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.0.tuning.variants.length", "2"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.name", "balanced"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.temperature", "0.7"),
                    Map.entry("yca.provider.providers.0.tuning.variants.1.name", "fast"),
                    Map.entry("yca.provider.providers.0.tuning.variants.1.temperature", "0.3"),
                    Map.entry("yca.provider.providers.0.tuning.defaultVariant", "balanced")));
        }
    }

    @Nested
    @FusionSupport(modules = DanglingDefaultVariantFallsBackToImplicitModule.class)
    class DanglingDefaultVariantFallsBackToImplicit {
        @Test
        void danglingDefaultVariantFallsBackToImplicit(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);

            assertEquals(
                    "hello there",
                    client.chat(request().withSettings(ChatSettings.EMPTY), () -> false)
                            .message()
                            .content());
            final var body = bodies.getLast();
            // the dangling default matches no variant: the implicit defaults apply, never a failure
            assertFalse(body.contains("\"temperature\""), body);
            assertNull(client.resolvedVariant(ChatSettings.EMPTY));
        }
    }

    public static class DanglingDefaultVariantFallsBackToImplicitModule extends SourceModule {
        public DanglingDefaultVariantFallsBackToImplicitModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.0.tuning.variants.length", "1"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.name", "balanced"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.temperature", "0.7"),
                    Map.entry("yca.provider.providers.0.tuning.defaultVariant", "missing")));
        }
    }

    @Nested
    @FusionSupport(modules = SetProviderKeepsThePerSessionVariantSelectionModule.class)
    class SetProviderKeepsThePerSessionVariantSelection {
        @Test
        void setProviderKeepsThePerSessionVariantSelection(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);
            final var settings = ChatSettings.EMPTY.withProviderId("other").withVariant("balanced");
            assertEquals("balanced", client.variant(settings));
            assertEquals("other", client.providerId(settings), "the per-session provider override must be effective");
            // a second session without an override falls back to the process default provider
            final var otherSession = ChatSettings.EMPTY;
            assertEquals("default", client.providerId(otherSession));
        }
    }

    public static class SetProviderKeepsThePerSessionVariantSelectionModule extends SourceModule {
        public SetProviderKeepsThePerSessionVariantSelectionModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.length", "2"),
                    Map.entry("yca.provider.providers.1.id", "other"),
                    Map.entry(
                            "yca.provider.providers.1.baseUrl",
                            "http://localhost:" + server.getAddress().getPort() + "/v1"),
                    Map.entry("yca.provider.providers.1.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.1.model", "test-model"),
                    Map.entry("yca.provider.providers.1.api", "auto"),
                    Map.entry("yca.provider.providers.0.tuning.variants.length", "1"),
                    Map.entry("yca.provider.providers.0.tuning.variants.0.name", "balanced")));
        }
    }

    @Nested
    @FusionSupport(modules = TwoSessionsResolveTheirOwnProviderModelAndFlavorModule.class)
    class TwoSessionsResolveTheirOwnProviderModelAndFlavor {
        @Test
        void twoSessionsResolveTheirOwnProviderModelAndFlavor(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);

            // tab A: no override -> process default provider "default" and its model
            final var tabA = ChatSettings.EMPTY;
            assertEquals("test-model", client.model(tabA), "no override uses the process default provider model");
            // tab B: explicit provider override -> its model, not the process default's
            final var tabB = ChatSettings.EMPTY.withProviderId("other");
            assertEquals("tab-b-model", client.model(tabB), "the override selects the other provider's model");
            assertEquals(
                    "other",
                    client.currentProvider(tabB).id(),
                    "the effective provider of tab B is its override, not the process default");

            // both requests hit the shared wire endpoint with their own model/body: a null request model
            // is resolved from the session's provider, so tab A carries the default model and tab B the
            // overridden provider's model
            assertEquals(
                    "hello there",
                    client.chat(request(null).withSettings(tabA), () -> false)
                            .message()
                            .content());
            assertEquals(
                    "hello there",
                    client.chat(request(null).withSettings(tabB), () -> false)
                            .message()
                            .content());
            final var bodiesSnapshot = bodies.stream().toList();
            assertTrue(
                    bodiesSnapshot.stream().anyMatch(b -> b.contains("\"model\":\"test-model\"")),
                    "tab A's request carries the default provider model");
            assertTrue(
                    bodiesSnapshot.stream().anyMatch(b -> b.contains("\"model\":\"tab-b-model\"")),
                    "tab B's request carries the overridden provider model");
        }
    }

    public static class TwoSessionsResolveTheirOwnProviderModelAndFlavorModule extends SourceModule {
        public TwoSessionsResolveTheirOwnProviderModelAndFlavorModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.length", "2"),
                    Map.entry("yca.provider.providers.1.id", "other"),
                    Map.entry(
                            "yca.provider.providers.1.baseUrl",
                            "http://localhost:" + server.getAddress().getPort() + "/v1"),
                    Map.entry("yca.provider.providers.1.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.1.model", "tab-b-model"),
                    Map.entry("yca.provider.providers.1.api", "auto")));
        }
    }

    @Nested
    @FusionSupport(modules = BudgetAndFlavorArePerSessionProviderModule.class)
    class BudgetAndFlavorArePerSessionProvider {
        @Test
        void budgetAndFlavorArePerSessionProvider(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            // provider "default" resolves to completions via AUTO probing of /v1/responses (404),
            // and has no explicit output budget -> no budget is force-sent for OpenAI flavors
            resetClient(configuration);

            // tab A (process default, no outputTokens) -> no max_tokens forced on the wire
            final var tabA = ChatSettings.EMPTY;
            assertEquals(
                    "hello there",
                    client.chat(request().withSettings(tabA), () -> false)
                            .message()
                            .content());
            final var bodyA = bodies.getLast();
            assertFalse(bodyA.contains("\"max_tokens\""), bodyA);

            // tab B (override with outputTokens=4321) -> 4321 is injected for its requests only
            final var tabB = ChatSettings.EMPTY.withProviderId("other");
            assertEquals(
                    "hello there",
                    client.chat(request().withSettings(tabB), () -> false)
                            .message()
                            .content());
            final var bodyB = bodies.getLast();
            assertTrue(bodyB.contains("\"max_tokens\":4321"), bodyB);
        }
    }

    public static class BudgetAndFlavorArePerSessionProviderModule extends SourceModule {
        public BudgetAndFlavorArePerSessionProviderModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.length", "2"),
                    Map.entry("yca.provider.providers.1.id", "other"),
                    Map.entry(
                            "yca.provider.providers.1.baseUrl",
                            "http://localhost:" + server.getAddress().getPort() + "/v1"),
                    Map.entry("yca.provider.providers.1.apiKey", "test-key"),
                    Map.entry("yca.provider.providers.1.model", "tab-b-model"),
                    Map.entry("yca.provider.providers.1.api", "auto"),
                    Map.entry("yca.provider.providers.1.outputTokens", "4321")));
        }
    }

    @Nested
    @FusionSupport(modules = Gpt5DoesNotForceAnImplicitReasoningEffortOnTheWireModule.class)
    class Gpt5DoesNotForceAnImplicitReasoningEffortOnTheWire {
        @Test
        void gpt5DoesNotForceAnImplicitReasoningEffortOnTheWire(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);

            assertEquals("hello there", client.chat(request(null)).message().content());
            assertEquals(
                    0,
                    occurrences(bodies.getLast(), "\"reasoning_effort\""),
                    "no implicit reasoning effort must reach the wire (provider default applies, opencode"
                            + " parity): "
                            + bodies.getLast());
        }
    }

    public static class Gpt5DoesNotForceAnImplicitReasoningEffortOnTheWireModule extends SourceModule {
        public Gpt5DoesNotForceAnImplicitReasoningEffortOnTheWireModule() {
            super(Map.of("yca.provider.providers.0.model", "gpt-5"));
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitRequestModelWinsOverTheSessionSelectionModule.class)
    class ExplicitRequestModelWinsOverTheSessionSelection {
        @Test
        void explicitRequestModelWinsOverTheSessionSelection(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);

            // an explicit request model wins over both the settings selection and the provider model
            final var settings = ChatSettings.EMPTY.withProviderId("other").withModel("settings-model");
            assertEquals(
                    "hello there",
                    client.chat(request("request-model").withSettings(settings), () -> false)
                            .message()
                            .content());
            assertTrue(bodies.getLast().contains("\"model\":\"request-model\""), bodies.getLast());
        }

        @Test
        void dollarRefInRequestModelRoutesToThatProvider(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            resetClient(configuration);

            // a $provider/model reference in the request model routes the request to that provider
            assertEquals(
                    "hello there",
                    client.chat(request("$other/model-x"), () -> false)
                            .message()
                            .content());
            assertTrue(bodies.getLast().contains("\"model\":\"model-x\""), bodies.getLast());
            assertFalse(bodies.getLast().contains("default-model"), bodies.getLast());
        }
    }

    public static class ExplicitRequestModelWinsOverTheSessionSelectionModule extends SourceModule {
        public ExplicitRequestModelWinsOverTheSessionSelectionModule() {
            super(Map.of(
                    "yca.provider.providers.0.model", "default-model",
                    "yca.provider.providers.1.baseUrl",
                            "http://localhost:" + server.getAddress().getPort() + "/v1",
                    "yca.provider.providers.1.apiKey", "test-key",
                    "yca.provider.providers.1.model", "other-model"));
        }
    }
}
