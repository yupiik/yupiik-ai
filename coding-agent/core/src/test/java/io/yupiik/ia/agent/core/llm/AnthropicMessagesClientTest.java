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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the Anthropic wire format through the routed {@link ChatClient} (provider configured
 * with the {@code anthropic} api flavor): headers, streaming, models listing and tool-call mapping.
 */
@ExtendWith(TestJsonMapper.class)
class AnthropicMessagesClientTest {
    private HttpServer server;
    private CodingAgentConfiguration configuration;
    private ChatClient client;
    private ExtendedHttpClient http;
    private JsonMapper mapper;

    @TempDir
    static Path storageHome;

    private final AtomicReference<String> reqHeaders = new AtomicReference<>();
    private final AtomicReference<String> reqBody = new AtomicReference<>();
    private final CopyOnWriteArrayList<String> streamTexts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start(
            final JsonMapper mapper,
            @Fusion final CodingAgentConfiguration configuration,
            @Fusion final HttpServer server)
            throws IOException {
        this.mapper = mapper;
        this.server = server;
        this.configuration = configuration;
        http = new HttpClientProducer().httpClient(configuration, new NativeProviderConfiguration(null, null));
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
        if (http != null) {
            http.close();
        }
    }

    private String base() {
        return "http://localhost:" + server.getAddress().getPort() + "/anthropic/v1/messages";
    }

    private ChatClient client() {
        return new ChatClient(
                configuration, mapper, new ProviderManager(configuration, mapper), Clock.systemUTC(), http);
    }

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest(
                "deepseek-v4-flash",
                List.of(
                        new Message("system", "be concise", null, null, null, null),
                        new Message("user", "hi", null, null, null, null)),
                null,
                null,
                false,
                null,
                null,
                64,
                null,
                null);
    }

    @Nested
    @FusionSupport(modules = ClaudeModule.class)
    class AutoResolvedClaudeBudgetIsClampedToTheFamilyCeiling {
        @Test
        void autoResolvedClaudeBudgetIsClampedToTheFamilyCeiling() throws Exception {
            server.createContext("/anthropic/v1/messages", exchange -> {
                reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                final var bytes = "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                        .concat("\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            // claude-3-5 with no configured/detected budget: the 32 000 fallback would 400 on the real
            // API (8 192 ceiling), so the auto-resolved budget must be clamped
            client = new ChatClient(
                    configuration, mapper, new ProviderManager(configuration, mapper), Clock.systemUTC(), http);
            client.chat(new ChatCompletionRequest(
                    "claude-3-5-haiku-latest",
                    List.of(new Message("user", "hi", null, null, null, null)),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
            assertTrue(
                    reqBody.get().contains("\"max_tokens\":8192"),
                    "auto-resolved budget must be clamped to the haiku-3.5 ceiling: " + reqBody.get());
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class Claude3GetsNoThinkingFieldAndNewerGetsAClampedBudget {
        @Test
        void claude3GetsNoThinkingFieldAndNewerGetsAClampedBudget() throws Exception {
            server.createContext("/anthropic/v1/messages", exchange -> {
                reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                final var bytes = "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                        .concat("\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            client = client();
            // claude-3-5 does not support extended thinking: no thinking field must be sent
            client.chat(new ChatCompletionRequest(
                            "claude-3-5-haiku-latest",
                            List.of(new Message("user", "hi", null, null, null, null)),
                            null,
                            null,
                            null,
                            null,
                            null,
                            8192,
                            "high",
                            null)
                    .withSettings(ChatSettings.EMPTY));
            assertFalse(
                    reqBody.get().contains("\"thinking\""),
                    "claude-3 must not receive a thinking field: " + reqBody.get());

            // a thinking-capable claude with a small budget gets a clamped-but-valid thinking budget
            client.chat(new ChatCompletionRequest(
                    "claude-sonnet-4-5-latest",
                    List.of(new Message("user", "hi", null, null, null, null)),
                    null,
                    null,
                    null,
                    null,
                    null,
                    2048,
                    "high",
                    null));
            final var body = reqBody.get();
            assertTrue(body.contains("\"thinking\""), "4.5 must receive a thinking field: " + body);
            assertTrue(
                    body.contains("\"budget_tokens\":2047"), "the budget must stay strictly below max_tokens: " + body);
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class ChatBlockingUsesXApiKeyAndAnthropicVersion {
        @Test
        void chatBlockingUsesXApiKeyAndAnthropicVersion() throws Exception {
            server.createContext("/anthropic/v1/messages", exchange -> {
                final var authz = exchange.getRequestHeaders().getFirst("authorization");
                reqHeaders.set("authz=" + authz + "|x-api-key:"
                        + exchange.getRequestHeaders().getFirst("x-api-key") + "|anthropic-version:"
                        + exchange.getRequestHeaders().getFirst("anthropic-version"));
                reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                final var body =
                        "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"deepseek-v4-flash\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}";
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            client = client();
            assertEquals(ApiFlavor.ANTHROPIC, client.flavor());
            final var result = client.chat(request());
            assertEquals("hello", result.message().content());
            assertEquals(6, result.usage().totalTokens());
            final var headers = reqHeaders.get();
            assertEquals("authz=null|x-api-key:sk-test|anthropic-version:2023-06-01", headers);
            final var body = reqBody.get();
            assertTrue(body.contains("\"max_tokens\":64"));
            assertTrue(body.contains("be concise"), body);
            assertTrue(body.contains("cache_control"), "system carries a prompt-cache breakpoint:\n" + body);
            assertTrue(body.contains("\"model\":\"deepseek-v4-flash\""));
        }
    }

    @Nested
    @FusionSupport(modules = AzureModule.class)
    class AzureAiGatewayAnthropicSendsTheReasoningConfigAndSurfacesThinkingTokens {
        @Test
        void azureAiGatewayAnthropicSendsTheReasoningConfigAndSurfacesThinkingTokens() throws Exception {
            // Azure AI Gateway serves the Anthropic messages API over a Bearer-gated /messages endpoint:
            // the reasoning shape (thinking/output_config) is configured through providerSpecificOptions
            // and the provider reports the hidden thinking tokens via
            // output_tokens_details.thinking_tokens.
            server.createContext("/anthropic/v1/messages", exchange -> {
                final var authz = exchange.getRequestHeaders().getFirst("authorization");
                reqHeaders.set("authz=" + authz);
                reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                final var body =
                        "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5-5\","
                                + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"hidden\"},{\"type\":\"text\",\"text\":\"answer\"}],"
                                + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                                + "\"usage\":{\"input_tokens\":57,\"output_tokens\":631,\"output_tokens_details\":{\"thinking_tokens\":360}}}";
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            client = new ChatClient(
                    configuration, mapper, new ProviderManager(configuration, mapper), Clock.systemUTC(), http);
            assertEquals(ApiFlavor.ANTHROPIC, client.flavor());
            final var result = client.chat(new ChatCompletionRequest(
                    "claude-sonnet-5-5",
                    List.of(new Message("user", "hi", null, null, null, null)),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
            assertEquals("answer", result.message().content());
            assertEquals(360, result.usage().reasoningTokens(), "thinking_tokens must reach the neutral usage");
            assertEquals(631, result.usage().completionTokens());
            assertEquals(688, result.usage().totalTokens());
            final var body = reqBody.get();
            assertTrue(body.contains("\"type\":\"adaptive\""), body);
            assertTrue(body.contains("\"display\":\"summarized\""), body);
            assertTrue(body.contains("\"effort\":\"max\""), body);
            assertTrue(body.contains("\"output_config\""), body);
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class StreamEmitsTextAndFinishDeltas {
        @Test
        void streamEmitsTextAndFinishDeltas() throws Exception {
            server.createContext("/anthropic/v1/messages", exchange -> {
                final var sse = String.join(
                        "\n",
                        "event: message_start",
                        "data:"
                                + " {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"deepseek-v4-flash\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}",
                        "",
                        "event: content_block_start",
                        "data:"
                                + " {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                        "",
                        "event: content_block_delta",
                        "data:"
                                + " {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hel\"}}",
                        "",
                        "event: content_block_delta",
                        "data:"
                                + " {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}",
                        "",
                        "event: content_block_stop",
                        "data: {\"type\":\"content_block_stop\",\"index\":0}",
                        "",
                        "event: message_delta",
                        "data:"
                                + " {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}",
                        "",
                        "event: message_stop",
                        "data: {\"type\":\"message_stop\"}",
                        "");
                final var bytes = sse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            client = client();
            final var streamReq = new ChatCompletionRequest(
                    "deepseek-v4-flash",
                    List.of(new Message("user", "hi", null, null, null, null)),
                    null,
                    null,
                    true,
                    null,
                    null,
                    64,
                    null,
                    null);
            final var finish = new AtomicReference<String>();
            final var message = client.stream(
                    streamReq,
                    delta -> {
                        if (delta instanceof TextDelta(final String text)) {
                            streamTexts.add(text);
                        } else if (delta instanceof End end) {
                            finish.set(end.finishReason());
                        }
                    },
                    () -> false);
            assertEquals(List.of("hel", "lo"), streamTexts);
            assertEquals("stop", finish.get());
            assertEquals("hello", message.content());
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class ModelsListsIds {
        @Test
        void modelsListsIds() throws Exception {
            server.createContext("/anthropic/v1/models", exchange -> {
                final var body = "{\"data\":[{\"type\":\"model\",\"id\":\"a-m\",\"display_name\":\"A"
                        + " M\",\"created_at\":\"1970-01-01T00:00:00Z\"},{\"type\":\"model\",\"id\":\"b-m\",\"display_name\":\"B"
                        + " M\",\"created_at\":\"1970-01-01T00:00:00Z\"}]}";
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            client = client();
            final var models = client.models();
            assertEquals(List.of("a-m", "b-m"), models.ids());
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class ModelsReturnsEmptyOnFlavorMissing {
        @Test
        void modelsReturnsEmptyOnFlavorMissing() throws Exception {
            server.createContext("/anthropic/v1/models", exchange -> {
                final var bytes = "not found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            client = client();
            final var models = client.models();
            assertTrue(models.data().isEmpty());
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class ChatWithToolUseMapsToFunctionCall {
        @Test
        void chatWithToolUseMapsToFunctionCall() throws Exception {
            server.createContext("/anthropic/v1/messages", exchange -> {
                final var body =
                        "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"deepseek-v4-flash\","
                                + "\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"read\",\"input\":{\"path\":\"/a\"}}],"
                                + "\"stop_reason\":\"tool_use\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}";
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            client = client();
            final var result = client.chat(request());
            assertNotNull(result.message().toolCalls());
            assertEquals(1, result.message().toolCalls().size());
            final var call = result.message().toolCalls().getFirst();
            assertEquals("t1", call.id());
            assertEquals("read", call.function().name());
            assertEquals("{\"path\":\"/a\"}", call.function().arguments());
        }
    }

    /**
     * The structured-output field is composed for the provider flavor carried by the request's
     * settings (Anthropic {@code output_config}), not the process-default one ({@code response_format}):
     * the session selection must drive the structured wire format, exactly like the plain chat path.
     */
    @Nested
    @FusionSupport(modules = ClientModule.class)
    class ChatStructuredHonorsTheSettingsCarrierFlavor {
        @Test
        void chatStructuredHonorsTheSettingsCarrierFlavor() throws Exception {
            final var reqBody = new AtomicReference<String>();
            server.createContext("/anthropic/v1/messages", exchange -> {
                reqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                final var body =
                        "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"deepseek-v4-flash\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"credentials\\\":[{\\\"id\\\":1}]}\"}],"
                                + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}";
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            client = client();
            final var schema = Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("credentials", Map.of("type", "array", "items", Map.of("type", "string"))),
                    "required",
                    List.of("credentials"));
            final var result = client.chatStructured(
                    request().withSettings(ChatSettings.EMPTY.withProviderId("default")), "credentials", schema);

            assertTrue(result.structured(), "the anthropic provider must honour the JSON Schema request");
            assertEquals(
                    "{\"credentials\":[{\"id\":1}]}", result.result().message().content());
            final var body = reqBody.get();
            assertTrue(body.contains("\"output_config\""), "anthropic structured field expected:\n" + body);
            assertTrue(body.contains("\"json_schema\""), body);
            assertFalse(
                    body.contains("\"response_format\""), "the openai field must not leak into anthropic:\n" + body);
        }
    }

    public static class ClientModule extends ConfigurationTestModule {
        private final HttpServer server;

        public ClientModule() throws IOException {
            this(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
        }

        private ClientModule(final HttpServer server) {
            super(values(server));
            this.server = server;
        }

        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.concat(
                    super.beans(),
                    Stream.of(new ProvidedInstanceBean<>(DefaultScoped.class, HttpServer.class, () -> server)));
        }

        private static Map<String, String> values(final HttpServer server) {
            return Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry(
                            "yca.provider.providers.0.baseUrl",
                            ("http://localhost:" + server.getAddress().getPort() + "/anthropic/v1/messages")),
                    Map.entry("yca.provider.providers.0.apiKey", "sk-test"),
                    Map.entry("yca.provider.providers.0.model", "deepseek-v4-flash"),
                    Map.entry("yca.provider.providers.0.api", "anthropic"),
                    Map.entry("yca.provider.providers.0.contextWindow", "1000000"),
                    Map.entry("yca.provider.providers.0.outputTokens", "-1"),
                    Map.entry("yca.provider.providers.0.timeoutSeconds", "30"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.llm.retriesBaseDelayMs", "1"),
                    Map.entry("yca.llm.rateLimitRequestsPerMinute", "0"),
                    Map.entry("yca.llm.maxConcurrent", "1"),
                    Map.entry("yca.llm.retryOnContextOverflow", "true"),
                    Map.entry("yca.llm.maxIterations", "250"));
        }
    }

    public static class ClaudeModule extends ConfigurationTestModule {
        private final HttpServer server;

        public ClaudeModule() throws IOException {
            this(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
        }

        private ClaudeModule(final HttpServer server) {
            super(values(server));
            this.server = server;
        }

        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.concat(
                    super.beans(),
                    Stream.of(new ProvidedInstanceBean<>(DefaultScoped.class, HttpServer.class, () -> server)));
        }

        private static Map<String, String> values(final HttpServer server) {
            return Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry(
                            "yca.provider.providers.0.baseUrl",
                            ("http://localhost:" + server.getAddress().getPort() + "/anthropic/v1/messages")),
                    Map.entry("yca.provider.providers.0.apiKey", "sk-test"),
                    Map.entry("yca.provider.providers.0.model", "claude-3-5-haiku-latest"),
                    Map.entry("yca.provider.providers.0.api", "anthropic"),
                    Map.entry("yca.provider.providers.0.contextWindow", "1000000"),
                    Map.entry("yca.provider.providers.0.outputTokens", "-1"),
                    Map.entry("yca.provider.providers.0.timeoutSeconds", "30"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.llm.retriesBaseDelayMs", "1"),
                    Map.entry("yca.llm.rateLimitRequestsPerMinute", "0"),
                    Map.entry("yca.llm.maxConcurrent", "1"));
        }
    }

    public static class AzureModule extends ConfigurationTestModule {
        private final HttpServer server;

        public AzureModule() throws IOException {
            this(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
        }

        private AzureModule(final HttpServer server) {
            super(values(server));
            this.server = server;
        }

        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.concat(
                    super.beans(),
                    Stream.of(new ProvidedInstanceBean<>(DefaultScoped.class, HttpServer.class, () -> server)));
        }

        private static Map<String, String> values(final HttpServer server) {
            return Map.ofEntries(
                    Map.entry("yca.storageHome", storageHome.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry(
                            "yca.provider.providers.0.baseUrl",
                            ("http://localhost:" + server.getAddress().getPort() + "/anthropic/v1/messages")),
                    Map.entry("yca.provider.providers.0.apiKey", "sk-test"),
                    Map.entry("yca.provider.providers.0.model", "claude-sonnet-5-5"),
                    Map.entry("yca.provider.providers.0.api", "anthropic"),
                    Map.entry("yca.provider.providers.0.contextWindow", "1000000"),
                    Map.entry("yca.provider.providers.0.timeoutSeconds", "30"),
                    Map.entry(
                            "yca.provider.providers.0.providerSpecificOptions",
                            "{\"thinking\":{\"type\":\"adaptive\",\"display\":\"summarized\"},\"output_config\":{\"effort\":\"max\"}}"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1"),
                    Map.entry("yca.llm.retriesBaseDelayMs", "1"),
                    Map.entry("yca.llm.rateLimitRequestsPerMinute", "0"),
                    Map.entry("yca.llm.maxConcurrent", "1"));
        }
    }
}
