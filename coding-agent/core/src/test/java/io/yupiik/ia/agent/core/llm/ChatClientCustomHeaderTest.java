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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.helper.SafeString;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.codex.CodexNotAuthenticatedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that user-registered {@link CustomDynamicHeaderTemplateHelper} beans are injected after
 * the built-in dynamic-header helpers and participate in rendering a provider's
 * {@code dynamicHeaders}. The helper is passed through the full CDI-style constructor, together with
 * the real {@link ExecTemplateHelper}/{@link CodexAuthTemplateHelper}, so the built-ins stay
 * available next to the custom one.
 */
@ExtendWith(TestJsonMapper.class)
class ChatClientCustomHeaderTest {

    private HttpServer server;
    private CodingAgentConfiguration configuration;
    private ChatClient client;
    private ExtendedHttpClient http;
    private HttpClient delegate;
    private final CopyOnWriteArrayList<Map<String, List<String>>> seenHeaders = new CopyOnWriteArrayList<>();

    @TempDir
    static Path tempDir;

    @BeforeEach
    void start(
            final JsonMapper mapper,
            @Fusion final CodingAgentConfiguration configuration,
            @Fusion final HttpServer server)
            throws IOException {
        delegate = HttpClient.newBuilder().build();
        http = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
        this.server = server;
        this.configuration = configuration;
        server.createContext("/v1/chat/completions", exchange -> {
            seenHeaders.add(exchange.getRequestHeaders());
            exchange.getRequestBody().readAllBytes();
            respond(
                    exchange,
                    200,
                    "{\"id\":\"c1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,"
                            + "\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},"
                            + "\"finish_reason\":\"stop\"}]}",
                    "application/json");
        });
        server.createContext(
                "/v1/models",
                exchange -> respond(
                        exchange, 200, "{\"data\":[{\"id\":\"test-model\",\"owned_by\":\"x\"}]}", "application/json"));
        server.start();
        client = chatClient(mapper);
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
            delegate.shutdownNow();
            delegate.close();
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class CustomHeaderHelperIsInjectedAndRendersIntoDynamicHeaders {
        @Test
        void customHeaderHelperIsInjectedAndRendersIntoDynamicHeaders(final JsonMapper mapper) throws Exception {
            final var result = client.chat(new ChatCompletionRequest(
                    "test-model", List.of(Message.user("hi")), null, null, null, null, null, null, null, null));
            assertEquals("hello", result.message().content());
            assertEquals(1, seenHeaders.size(), "exactly one chat request is expected");
            final var headers = seenHeaders.get(0);
            // the static header and the custom dynamic header must both reach the wire
            assertEquals("static-value", headers.get("x-static").get(0));
            assertEquals("hello-arg", headers.get("x-custom").get(0));
            // a dynamic header overrides the default API-key authorization
            assertEquals(1, headers.get("authorization").size());
            assertEquals("Bearer custom-auth-arg", headers.get("authorization").get(0));
        }
    }

    /**
     * A missing Codex session must surface as a {@link CodexNotAuthenticatedException} - not a wrapped
     * {@link IllegalArgumentException} - so the caller (TurnRunner) can trigger the interactive login
     * flow and open the browser. The {@code authorization} dynamic header here resolves through
     * {@code {{codexAgentAssertion "codex"}}}, which throws when no auth file exists in the (empty)
     * storage home.
     */
    @Nested
    @FusionSupport(modules = CodexModule.class)
    class MissingCodexSessionSurfacesAsCodexNotAuthenticated {
        @Test
        void missingCodexSessionSurfacesAsCodexNotAuthenticated(final JsonMapper mapper) throws Exception {
            final var codexClient = chatClient(mapper);
            try (codexClient) {
                final var thrown = assertThrows(
                        CodexNotAuthenticatedException.class,
                        () -> codexClient.chat(new ChatCompletionRequest(
                                "test-model",
                                List.of(Message.user("hi")),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)));
                assertEquals("codex", thrown.provider());
            }
        }
    }

    private ChatClient chatClient(final JsonMapper mapper) {
        return new ChatClient(
                configuration,
                mapper,
                new ProviderManager(configuration, mapper),
                Clock.systemUTC(),
                http,
                new ToolCallArgumentsRepairer(mapper),
                null,
                null,
                new ExecTemplateHelper(),
                new CodexAuthTemplateHelper(mapper, http, configuration),
                List.of(new CustomAuthTemplateHelper(), new CustomAuthHeaderOverride()),
                null);
    }

    private static void respond(
            final HttpExchange exchange, final int status, final String body, final String contentType) {
        try {
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

    /** A sample user helper: returns a value derived from its single argument, wrapped in {@link SafeString}. */
    private static final class CustomAuthTemplateHelper implements CustomDynamicHeaderTemplateHelper {
        @Override
        public String name() {
            return "myHeader";
        }

        @Override
        public Object apply(final HelperContext context) {
            final var arg = context.args().isEmpty()
                    ? "none"
                    : String.valueOf(context.args().get(0));
            return new SafeString("hello-" + arg);
        }
    }

    /** A helper overriding the default API-key authorization header. */
    private static final class CustomAuthHeaderOverride implements CustomDynamicHeaderTemplateHelper {
        @Override
        public String name() {
            return "myAuth";
        }

        @Override
        public Object apply(final HelperContext context) {
            final var arg = context.args().isEmpty()
                    ? "none"
                    : String.valueOf(context.args().get(0));
            return new SafeString("custom-auth-" + arg);
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
            entries.put("yca.storageHome", tempDir.toString());
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
            entries.put(
                    "yca.provider.providers.0.dynamicHeaders",
                    "x-custom: {{myHeader \"arg\"}}\nauthorization: Bearer {{myAuth \"arg\"}}");
            entries.put("yca.provider.providers.0.headers", "x-static: static-value");
            entries.put("yca.llm.retriesMaxAttempts", "1");
            entries.put("yca.llm.retriesBaseDelayMs", "1");
            entries.put("yca.llm.rateLimitRequestsPerMinute", "0");
            entries.put("yca.llm.maxConcurrent", "1");
            return entries;
        }
    }

    public static class CodexModule extends ConfigurationTestModule {
        private final HttpServer server;

        public CodexModule() throws IOException {
            this(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
        }

        private CodexModule(final HttpServer server) {
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
            entries.put("yca.storageHome", tempDir.toString());
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
            entries.put("yca.provider.providers.0.dynamicHeaders", "authorization: {{codexAgentAssertion \"codex\"}}");
            entries.put("yca.provider.providers.0.headers", "x-static: static-value");
            entries.put("yca.llm.retriesMaxAttempts", "1");
            entries.put("yca.llm.retriesBaseDelayMs", "1");
            entries.put("yca.llm.rateLimitRequestsPerMinute", "0");
            entries.put("yca.llm.maxConcurrent", "1");
            return entries;
        }
    }
}
