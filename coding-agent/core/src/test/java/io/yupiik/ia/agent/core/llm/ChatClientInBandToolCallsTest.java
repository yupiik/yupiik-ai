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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
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
import io.yupiik.llm.client.model.LlmStreamEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
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
 * Verifies the in-band tool-call markup hoist: when a provider streams/non-streams an assistant
 * message whose {@code content} channel carries DSML-style tool-call markup and no native
 * {@code tool_calls}, the main client path extracts the calls into the returned message and strips
 * the markup from the stored content.
 */
@ExtendWith(TestJsonMapper.class)
class ChatClientInBandToolCallsTest {

    private HttpServer server;
    private ChatClient client;
    private ExtendedHttpClient http;
    private JsonMapper mapper;

    @TempDir
    static Path storageHome;

    /** The SSE body / JSON body handed back by the fake server for the current test. */
    private final AtomicReference<byte[]> chatResponse = new AtomicReference<>();

    @BeforeEach
    void start(
            final JsonMapper mapper,
            @Fusion final CodingAgentConfiguration configuration,
            @Fusion final HttpServer server)
            throws IOException {
        this.mapper = mapper;
        this.server = server;
        server.createContext("/v1/responses", exchange -> respond(exchange, 404, "not found", "text/plain"));
        server.createContext(
                "/v1/chat/completions",
                exchange -> respond(
                        exchange, 200, new String(chatResponse.get(), StandardCharsets.UTF_8), "text/event-stream"));
        server.createContext(
                "/v1/models",
                exchange -> respond(
                        exchange, 200, "{\"data\":[{\"id\":\"test-model\",\"owned_by\":\"x\"}]}", "application/json"));
        server.start();
        http = new HttpClientProducer().httpClient(configuration, new NativeProviderConfiguration(null, null));
        client = new ChatClient(
                configuration, mapper, new ProviderManager(configuration, mapper), Clock.systemUTC(), http);
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

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest(
                "test-model", List.of(Message.user("hi")), null, null, null, null, null, null, null, null);
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

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class HoistsInBandMarkupFromStreamContent {
        @Test
        void hoistsInBandMarkupFromStreamContent() throws Exception {
            chatResponse.set(String.join(
                            "\n",
                            "data:"
                                    + " {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Let"
                                    + " me read it.\\n"
                                    + "\"},\"finish_reason\":null}]}",
                            "",
                            "data:"
                                    + " {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"<\uFF5CDSML\uFF5Ctool_calls>\\n"
                                    + "<\uFF5CDSML\uFF5Cinvoke name=read>\\n"
                                    + "<\uFF5CDSML\uFF5Cparameter name=path"
                                    + " string=true>src/App.java</\uFF5CDSML\uFF5Cparameter>\\n"
                                    + "</\uFF5CDSML\uFF5Cinvoke>\\n"
                                    + "</\uFF5CDSML\uFF5Ctool_calls>\"},\"finish_reason\":null}]}",
                            "",
                            "data:"
                                    + " {\"id\":\"1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                            "",
                            "data: [DONE]",
                            "")
                    .getBytes(StandardCharsets.UTF_8));
            final var deltas = new CopyOnWriteArrayList<LlmStreamEvent>();
            final var message = client.stream(request(), deltas::add);
            assertEquals("Let me read it.", message.content(), "the DSML markup must be stripped from the content");
            assertEquals(1, message.toolCalls().size(), "the in-band call must be extracted into toolCalls");
            assertEquals("read", message.toolCalls().get(0).function().name());
            assertEquals(
                    "{\"path\":\"src/App.java\"}",
                    message.toolCalls().get(0).function().arguments());
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class HoistsInBandMarkupFromNonStreamContent {
        @Test
        void hoistsInBandMarkupFromNonStreamContent() throws Exception {
            chatResponse.set(
                    ("{\"id\":\"c1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Let"
                                    + " me read it.\\n"
                                    + "<\uFF5CDSML\uFF5Ctool_calls>\\n"
                                    + "<\uFF5CDSML\uFF5Cinvoke name=grep>\\n"
                                    + "<\uFF5CDSML\uFF5Cparameter name=pattern"
                                    + " string=true>foo</\uFF5CDSML\uFF5Cparameter>\\n"
                                    + "</\uFF5CDSML\uFF5Cinvoke>\\n"
                                    + "</\uFF5CDSML\uFF5Ctool_calls>\"},\"finish_reason\":\"stop\"}]}")
                            .getBytes(StandardCharsets.UTF_8));
            final var result = client.chat(request());
            final var message = result.message();
            assertEquals("Let me read it.", message.content(), "the DSML markup must be stripped from the content");
            assertEquals(1, message.toolCalls().size(), "the in-band call must be extracted into toolCalls");
            assertEquals("grep", message.toolCalls().get(0).function().name());
            assertEquals(
                    "{\"pattern\":\"foo\"}",
                    message.toolCalls().get(0).function().arguments());
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class RepairsNativeToolArgumentsAndHandlesNull {
        @Test
        void repairsNativeToolArgumentsAndHandlesNull() throws Exception {
            chatResponse.set(
                    ("{\"id\":\"c1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"a\\\"}"
                                    + " trailing\"}},"
                                    + "{\"id\":\"call2\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":null}}]},\"finish_reason\":\"tool_calls\"}]}")
                            .getBytes(StandardCharsets.UTF_8));
            final var calls = client.chat(request()).message().toolCalls();
            assertEquals("{\"path\":\"a\"}", calls.get(0).function().arguments());
            assertNull(calls.get(1).function().arguments());
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class RepairsHistoricalArgumentsAfterGatewayRejection {
        @Test
        void repairsHistoricalArgumentsAfterGatewayRejection() throws Exception {
            final var bodies = new CopyOnWriteArrayList<String>();
            server.removeContext("/v1/chat/completions");
            server.createContext("/v1/chat/completions", exchange -> {
                final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                bodies.add(body);
                if (bodies.size() == 1) {
                    respond(
                            exchange,
                            400,
                            "{\"message\":\"messages[1].tool_calls[0].function.arguments must be a valid JSON"
                                    + " object string: trailing characters\"}",
                            "application/json");
                } else {
                    respond(
                            exchange,
                            200,
                            "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
                            "application/json");
                }
            });
            final var call = new ToolCall(
                    "call1", "function", new ToolCall.FunctionCall("read", "{\"path\":\"a\"} trailing"), null);
            final var messages =
                    List.of(Message.user("hi"), Message.assistant(null, List.of(call)), Message.tool("call1", "ok"));
            final var request =
                    new ChatCompletionRequest("test-model", messages, null, null, null, null, null, null, null, null);
            assertEquals("ok", client.chat(request).message().content());
            assertEquals(2, bodies.size());
            assertTrue(bodies.get(0).contains("trailing"));
            assertFalse(bodies.get(1).contains("trailing"));
            assertEquals("{\"path\":\"a\"} trailing", call.function().arguments());
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class CleanProsePassesThroughUntouched {
        @Test
        void cleanProsePassesThroughUntouched() throws Exception {
            final var prose = "This is a plain answer with no tool markup whatsoever.";
            chatResponse.set(
                    ("{\"id\":\"c1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                                    + prose + "\"},\"finish_reason\":\"stop\"}]}")
                            .getBytes(StandardCharsets.UTF_8));
            final var message = client.chat(request()).message();
            assertEquals(prose, message.content(), "a clean prose answer must be left unchanged");
            assertNull(message.toolCalls(), "no in-band markup means no tool calls");
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
            final var entries = new HashMap<String, String>();
            entries.put("yca.storageHome", storageHome.toString());
            entries.put("yca.provider.providers.length", "1");
            entries.put("yca.provider.providers.0.id", "default");
            entries.put(
                    "yca.provider.providers.0.baseUrl",
                    "http://localhost:" + server.getAddress().getPort() + "/v1");
            entries.put("yca.provider.providers.0.apiKey", "test-key");
            entries.put("yca.provider.providers.0.model", "test-model");
            entries.put("yca.provider.providers.0.api", "completions");
            entries.put("yca.provider.providers.0.contextWindow", "128000");
            entries.put("yca.provider.providers.0.timeoutSeconds", "5");
            entries.put("yca.llm.retriesMaxAttempts", "1");
            entries.put("yca.llm.retriesBaseDelayMs", "1");
            entries.put("yca.llm.rateLimitRequestsPerMinute", "0");
            entries.put("yca.llm.maxConcurrent", "1");
            return entries;
        }
    }
}
