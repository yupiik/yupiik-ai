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
package io.yupiik.llm.client.opencode;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmTool;
import io.yupiik.llm.client.model.LlmToolCall;
import io.yupiik.llm.client.openai.OpenAICompletionsDialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class OpenCodeFreeDialectTest {
    private static final Pattern SESSION_ID = Pattern.compile("ses_[0-9a-f]{12}[0-9A-Za-z]{14}");
    private static final Pattern PROJECT_HASH = Pattern.compile("[0-9a-f]{64}");
    private static final String TOOL_RESPONSE = """
        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read","arguments":"{\\"path\\":"}}]}}]}

        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"note.txt\\"}"}}]},"finish_reason":"tool_calls"}]}

        data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}

        data: [DONE]

        """;
    private static final String TEXT_RESPONSE = """
        data: {"choices":[{"delta":{"reasoning_content":"Read "}}]}

        data: {"choices":[{"delta":{"reasoning_content":"the file.","content":"file "}}]}

        data: {"choices":[{"delta":{"content":"received"},"finish_reason":"stop"}]}

        data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":5,"total_tokens":16}}

        data: [DONE]

        """;

    @Test
    void environmentShimIsProviderSpecificAndPreservesInstructions(final JsonMapper mapper) throws Exception {
        final var nativeText =
                "You are yca.\nEnvironment:\n- Working directory: /tmp/session-project\nKeep edits small.";
        final var existing = "You are yca.\n<env>\n  Working directory: /tmp/existing\n</env>";
        try (final var fixture = new Fixture(
                        List.of(TEXT_RESPONSE, TEXT_RESPONSE, TEXT_RESPONSE, TEXT_RESPONSE, TEXT_RESPONSE));
                final var transport = fixture.client();
                final var client =
                        new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(transport))) {
            final var provider = fixture.provider(builder -> {});
            final var dialect = new OpenCodeFreeDialect(mapper, client, provider);
            final var input = request(List.of(LlmMessage.system(nativeText), LlmMessage.user("hi")));
            dialect.chat(input).toCompletableFuture().get(10, TimeUnit.SECONDS);
            dialect.chat(request(List.of(LlmMessage.system(existing), LlmMessage.user("hi"))))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            dialect.chat(request(List.of(LlmMessage.user("hi"))))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            collect(new OpenCodeFreeDialect(mapper, client, provider), input);
            collect(new OpenAICompletionsDialect(mapper, client, provider), input);
            final var expected = nativeText + "\n\n<env>\n  Working directory: /tmp/session-project\n</env>";
            for (final int index : List.of(0, 3)) {
                final var body =
                        mapper.fromString(Map.class, fixture.requests.get(index).body());
                final var messages = assertInstanceOf(List.class, body.get("messages"));
                assertEquals(
                        expected,
                        assertInstanceOf(Map.class, messages.getFirst()).get("content"));
                assertEquals(2, messages.size());
            }
            final var second =
                    mapper.fromString(Map.class, fixture.requests.get(1).body());
            assertEquals(existing, ((Map<?, ?>) ((List<?>) second.get("messages")).getFirst()).get("content"));
            final var third =
                    mapper.fromString(Map.class, fixture.requests.get(2).body());
            final var messages = (List<?>) third.get("messages");
            assertEquals(
                    "<env>\n  Working directory: " + Path.of("").toAbsolutePath() + "\n</env>",
                    ((Map<?, ?>) messages.getFirst()).get("content"));
            assertEquals("hi", ((Map<?, ?>) messages.getLast()).get("content"));
            assertEquals(nativeText, input.messages().getFirst().content());
            final var nativeBody =
                    mapper.fromString(Map.class, fixture.requests.get(4).body());
            assertEquals(nativeText, ((Map<?, ?>) ((List<?>) nativeBody.get("messages")).getFirst()).get("content"));
        }
    }

    @Test
    void bufferedToolRoundTrip(final JsonMapper mapper, @TempDir final Path directory) throws Exception {
        roundTrip(mapper, directory, false);
    }

    @Test
    void injectedClientIsClosedByItsOwner(final JsonMapper mapper) {
        final var closed = new AtomicInteger();
        try (final var transport = new OpenCodeHttpClient()) {
            try (final var http =
                    new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(transport))) {
                http.onClose(ignored -> closed.incrementAndGet());
                try (final var dialect = new OpenCodeFreeDialect(
                        mapper,
                        http,
                        new ProviderSpec(
                                "https://localhost/inference/openai/v1/chat/completions",
                                "public",
                                Duration.ofSeconds(10),
                                Map.of(),
                                _ -> {}))) {
                    assertEquals("opencode-free", dialect.name());
                    assertEquals(0, closed.get());
                }
                assertEquals(0, closed.get(), "the dialect must not close the caller-owned HTTP client");
                assertFalse(transport.isTerminated(), "the dialect must not close the caller-owned transport");
            }
            assertEquals(1, closed.get(), "the owner must close the HTTP wrapper");
        }
    }

    @Test
    void largeMalformedPromptsAndDirectoryProjectAffinity(final JsonMapper mapper) throws Exception {
        final var malformed = "<env>".repeat(80_000) + "x Working directory: ignored\n".repeat(20_000)
                + "- Working directory: /tmp/project-a/../project-a\r\n";
        final var prompts = List.of(
                malformed,
                "<env>\n Working directory: /tmp/project-a\n</env>",
                "- Working directory: /tmp/project-b\n",
                "no environment markers".repeat(20_000));
        try (final var fixture = new Fixture(Collections.nCopies(5, TEXT_RESPONSE));
                final var transport = fixture.client();
                final var client =
                        new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(transport))) {
            final var dialect = new OpenCodeFreeDialect(mapper, client, fixture.provider(builder -> {}));
            for (final var prompt : prompts) {
                Assertions.assertTimeoutPreemptively(
                        Duration.ofSeconds(10),
                        () -> dialect.chat(request(List.of(LlmMessage.system(prompt), LlmMessage.user("hi"))))
                                .toCompletableFuture()
                                .get(8, TimeUnit.SECONDS));
            }
            final var first = fixture.requests.get(0).headers().get("x-opencode-project");
            assertEquals(first, fixture.requests.get(1).headers().get("x-opencode-project"));
            assertNotEquals(first, fixture.requests.get(2).headers().get("x-opencode-project"));
            assertFalse(first.contains("project-a"));
            final var body =
                    mapper.fromString(Map.class, fixture.requests.getFirst().body());
            assertEquals(
                    malformed + "\n\n<env>\n  Working directory: /tmp/project-a/../project-a\n</env>",
                    ((Map<?, ?>) ((List<?>) body.get("messages")).getFirst()).get("content"));
            final var overridden = new OpenCodeFreeDialect(
                    mapper,
                    client,
                    fixture.provider(builder -> builder.setHeader("x-opencode-project", "explicit-project")));
            // Closing an injected dialect leaves the shared client usable by other dialects.
            dialect.close();
            overridden
                    .chat(request(List.of(LlmMessage.user("hi"))))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertEquals(
                    "explicit-project", fixture.requests.getLast().headers().get("x-opencode-project"));
        }
    }

    @Test
    void streamingToolRoundTrip(final JsonMapper mapper, @TempDir final Path directory) throws Exception {
        roundTrip(mapper, directory, true);
    }

    private void roundTrip(final JsonMapper mapper, final Path directory, final boolean streaming) throws Exception {
        Files.writeString(directory.resolve("note.txt"), "local tool result");
        try (final var fixture = new Fixture(List.of(TOOL_RESPONSE, TEXT_RESPONSE));
                final var transport = fixture.client();
                final var client =
                        new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(transport))) {
            final var dialect = new OpenCodeFreeDialect(mapper, client, fixture.provider(builder -> {}));
            assertEquals("opencode-free", dialect.name());
            final var user = LlmMessage.user("read note.txt");
            final LlmMessage assistant;
            if (streaming) {
                final var events = collect(dialect, request(List.of(user)));
                final var deltas = events.stream()
                        .filter(LlmStreamEvent.ToolCallDelta.class::isInstance)
                        .map(LlmStreamEvent.ToolCallDelta.class::cast)
                        .toList();
                assertEquals(2, deltas.size());
                assertEquals(0, deltas.getFirst().index());
                assertEquals(0, deltas.getLast().index());
                assertEquals("call_1", deltas.getFirst().id());
                assertEquals("read", deltas.getFirst().name());
                assertEquals("{\"path\":", deltas.getFirst().arguments());
                assertEquals("\"note.txt\"}", deltas.getLast().arguments());
                assertEnd(events, "tool_calls", 10);
                assistant = LlmMessage.assistant(
                        "",
                        List.of(LlmToolCall.of(
                                deltas.getFirst().id(),
                                deltas.getFirst().name(),
                                deltas.stream()
                                        .map(LlmStreamEvent.ToolCallDelta::arguments)
                                        .collect(joining()))));
            } else {
                final var response = dialect.chat(request(List.of(user)))
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                assertEquals("tool_calls", response.first().finishReason());
                assertEquals(10, response.usage().totalTokens());
                assistant = response.first().message();
            }
            assertEquals(1, assistant.toolCalls().size());
            final var call = assistant.toolCalls().getFirst();
            assertEquals("call_1", call.id());
            assertEquals("read", call.function().name());
            assertEquals("{\"path\":\"note.txt\"}", call.function().arguments());
            final var arguments = mapper.fromString(Map.class, call.function().arguments());
            final var path = directory.resolve((String) arguments.get("path"));
            assertEquals(directory.resolve("note.txt"), path);
            final var result = Files.readString(path);
            final var next = request(List.of(user, assistant, LlmMessage.tool(call.id(), result)));
            if (streaming) {
                final var events = collect(dialect, next);
                assertEquals(
                        List.of(new LlmStreamEvent.TextDelta("file "), new LlmStreamEvent.TextDelta("received")),
                        events.stream()
                                .filter(LlmStreamEvent.TextDelta.class::isInstance)
                                .toList());
                assertEquals(
                        List.of(
                                new LlmStreamEvent.ReasoningDelta("Read "),
                                new LlmStreamEvent.ReasoningDelta("the file.")),
                        events.stream()
                                .filter(LlmStreamEvent.ReasoningDelta.class::isInstance)
                                .toList());
                assertEnd(events, "stop", 16);
            } else {
                final var response = dialect.chat(next).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals("file received", response.text());
                assertEquals("stop", response.first().finishReason());
                assertEquals(
                        "Read the file.",
                        response.first().message().extensions().get("reasoning_content"));
                assertEquals(11, response.usage().promptTokens());
                assertEquals(5, response.usage().completionTokens());
                assertEquals(16, response.usage().totalTokens());
            }
            assertEquals(2, fixture.requests.size());
            for (final var captured : fixture.requests) {
                assertEquals("POST", captured.method());
                assertEquals("/inference/openai/v1/chat/completions", captured.path());
                assertEquals(List.of("Bearer public"), captured.authorization());
                assertEquals(1, captured.sessions().size());
                assertTrue(SESSION_ID.matcher(captured.sessions().getFirst()).matches());
                assertEquals(fixture.requests.getFirst().sessions(), captured.sessions());
                assertEquals("*/*", captured.accept());
                assertEquals("opencode/latest/2.2.3/cli", captured.headers().get("user-agent"));
                assertEquals("cli", captured.headers().get("x-opencode-client"));
                assertTrue(PROJECT_HASH
                        .matcher(captured.headers().get("x-opencode-project"))
                        .matches());
                assertEquals(
                        fixture.requests.getFirst().headers().get("x-opencode-project"),
                        captured.headers().get("x-opencode-project"));
                for (final var name : List.of("x-opencode-session-id", "x-session-affinity", "x-session-id")) {
                    assertEquals(
                            captured.sessions().getFirst(), captured.headers().get(name));
                }
                assertFalse(captured.body().contains("configured-secret"));
                assertFalse(captured.body().contains("private-session"));
                final var body = mapper.fromString(Map.class, captured.body());
                assertEquals(Boolean.TRUE, body.get("stream"));
                assertFalse(body.containsKey("prompt_cache_key"));
            }
            final var body =
                    mapper.fromString(Map.class, fixture.requests.getLast().body());
            final var messages = assertInstanceOf(List.class, body.get("messages"));
            assertEquals(4, messages.size());
            assertEquals(
                    "system", assertInstanceOf(Map.class, messages.getFirst()).get("role"));
            final var wireAssistant = assertInstanceOf(Map.class, messages.get(2));
            assertEquals("assistant", wireAssistant.get("role"));
            final var calls = assertInstanceOf(List.class, wireAssistant.get("tool_calls"));
            final var wireCall = assertInstanceOf(Map.class, calls.getFirst());
            assertEquals("call_1", wireCall.get("id"));
            final var function = assertInstanceOf(Map.class, wireCall.get("function"));
            assertEquals("read", function.get("name"));
            assertEquals(call.function().arguments(), function.get("arguments"));
            final var tool = assertInstanceOf(Map.class, messages.get(3));
            assertEquals("tool", tool.get("role"));
            assertEquals("call_1", tool.get("tool_call_id"));
            assertEquals(result, tool.get("content"));
        }
    }

    @Test
    void catalogRouteAndExplicitHeadersOverrideDefaults(final JsonMapper mapper) throws Exception {
        try (final var fixture = new Fixture(List.of(TEXT_RESPONSE));
                final var transport = fixture.client();
                final var client =
                        new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(transport))) {
            final var dialect = new OpenCodeFreeDialect(mapper, client, fixture.provider(builder -> {
                builder.setHeader("authorization", "Bearer explicit");
                builder.setHeader("x-opencode-session", "explicit-session");
            }));
            assertEquals(
                    "file received",
                    dialect.chat(request(List.of(LlmMessage.user("hi"))))
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                            .text());
            assertEquals(
                    "free-model",
                    dialect.models()
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                            .data()
                            .getFirst()
                            .id());
            assertEquals(2, fixture.requests.size());
            final var chat = fixture.requests.getFirst();
            assertEquals(List.of("Bearer explicit"), chat.authorization());
            assertEquals(List.of("explicit-session"), chat.sessions());
            assertFalse(chat.body().contains("prompt_cache_key"));
            final var catalog = fixture.requests.getLast();
            assertEquals("GET", catalog.method());
            assertEquals("/inference/v1/models", catalog.path());
            assertEquals(List.of("Bearer explicit"), catalog.authorization());
            assertEquals(List.of("explicit-session"), catalog.sessions());
            assertEquals("", catalog.body());
        }
    }

    private LlmRequest request(final List<LlmMessage> messages) {
        return new LlmRequest(
                "free-model",
                messages,
                null,
                null,
                32,
                null,
                List.of(LlmTool.of(
                        "read",
                        "Read a local text file",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("path", Map.of("type", "string")),
                                "required", List.of("path")))),
                null,
                null,
                null,
                false,
                false,
                Map.of("prompt_cache_key", "private-session"));
    }

    private List<LlmStreamEvent> collect(final OpenAICompletionsDialect dialect, final LlmRequest request)
            throws Exception {
        final var completed = new CompletableFuture<List<LlmStreamEvent>>();
        dialect.stream(request, () -> false)
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS)
                .subscribe(new Flow.Subscriber<>() {
                    private final List<LlmStreamEvent> events = new ArrayList<>();

                    @Override
                    public void onSubscribe(final Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(final LlmStreamEvent event) {
                        events.add(event);
                    }

                    @Override
                    public void onError(final Throwable error) {
                        completed.completeExceptionally(error);
                    }

                    @Override
                    public void onComplete() {
                        completed.complete(List.copyOf(events));
                    }
                });
        return completed.get(10, TimeUnit.SECONDS);
    }

    private void assertEnd(final List<LlmStreamEvent> events, final String reason, final int tokens) {
        assertTrue(events.stream()
                .anyMatch(event -> event instanceof LlmStreamEvent.End end && reason.equals(end.finishReason())));
        assertTrue(events.stream()
                .anyMatch(event -> event instanceof LlmStreamEvent.End end
                        && end.usage() != null
                        && end.usage().totalTokens() == tokens));
    }

    private record Captured(
            String method,
            String path,
            List<String> authorization,
            List<String> sessions,
            String accept,
            String body,
            Map<String, String> headers) {}

    private static final class Fixture implements AutoCloseable {
        private final KeyStore store;
        private final HttpsServer server;
        private final List<Captured> requests = new CopyOnWriteArrayList<>();

        private Fixture(final List<String> responses) throws Exception {
            store = KeyStore.getInstance("PKCS12");
            try (final var input = OpenCodeFreeDialectTest.class.getResourceAsStream("localhost.p12")) {
                assertNotNull(input, "module-local TLS fixture");
                store.load(input, "changeit".toCharArray());
            }
            final var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, "changeit".toCharArray());
            final var context = SSLContext.getInstance("TLSv1.3");
            context.init(keys.getKeyManagers(), null, null);
            server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(context));
            server.createContext("/", exchange -> {
                try (exchange) {
                    final var headers = exchange.getRequestHeaders();
                    final var path = exchange.getRequestURI().getPath();
                    requests.add(new Captured(
                            exchange.getRequestMethod(),
                            path,
                            List.copyOf(headers.getOrDefault("authorization", List.of())),
                            List.copyOf(headers.getOrDefault("x-opencode-session", List.of())),
                            headers.getFirst("accept"),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                            headers.entrySet().stream()
                                    .collect(java.util.stream.Collectors.toMap(
                                            e -> e.getKey().toLowerCase(java.util.Locale.ROOT),
                                            e -> e.getValue().getFirst()))));
                    final boolean catalog = path.equals("/inference/v1/models");
                    final var response = catalog
                            ? "{\"data\":[{\"id\":\"free-model\",\"object\":\"model\"}]}"
                            : responses.get(requests.size() - 1);
                    exchange.getResponseHeaders()
                            .set("content-type", catalog ? "application/json" : "text/event-stream");
                    final var bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        private OpenCodeHttpClient client() {
            return new OpenCodeHttpClient(Duration.ofSeconds(5), store, null, null);
        }

        private ProviderSpec provider(final Consumer<java.net.http.HttpRequest.Builder> customizer) {
            return new ProviderSpec(
                    "https://localhost:" + server.getAddress().getPort() + "/inference/openai/v1/chat/completions",
                    "configured-secret",
                    Duration.ofSeconds(10),
                    Map.of(),
                    customizer);
        }

        @Override
        public void close() throws IOException {
            server.stop(0);
        }
    }
}
