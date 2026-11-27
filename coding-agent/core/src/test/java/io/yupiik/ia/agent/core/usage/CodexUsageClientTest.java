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
package io.yupiik.ia.agent.core.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.CodexAuthTemplateHelper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.codex.CodexOAuthConfig;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Tests the Codex usage quota client: endpoint path, auth headers and payload decoding. */
@ExtendWith(TestJsonMapper.class)
@FusionSupport(modules = CodexUsageClientTest.UsageModule.class)
class CodexUsageClientTest {

    private HttpServer server;
    private String serverUrl;

    @TempDir
    static Path root;

    private Path storage;
    private CodingAgentConfiguration configuration;

    private ExtendedHttpClient httpClient;
    private HttpClient delegate;
    private final CopyOnWriteArrayList<String[]> requestHeaders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start(@Fusion final CodingAgentConfiguration configuration) throws IOException {
        this.configuration = configuration;
        storage = Path.of(configuration.resolveStorageHome());
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverUrl = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            requestHeaders.add(new String[] {
                exchange.getRequestHeaders().getFirst("authorization"),
                exchange.getRequestHeaders().getFirst("ChatGPT-Account-Id"),
            });
            final String path = exchange.getRequestURI().getPath();
            if (path.contains("unauthorized")) {
                respond(exchange, 401, "{\"detail\":\"invalid token\"}");
                return;
            }
            respond(exchange, 200, """
                    {"plan_type":"plus",
                     "rate_limit":{"primary_window":{"used_percent":42.5,"limit_window_seconds":18000,"reset_at":1700000000},
                                  "secondary_window":{"used_percent":12,"limit_window_seconds":604800}},
                     "additional_rate_limits":[{"name":"code-review","rate_limit":{"used_percent":5,"limit_window_seconds":604800}}],
                     "credits":{"has_credits":true,"balance":"128.5"},
                     "ordinary_usage_allowed":true}
                    """);
        });
        server.start();
        delegate = HttpClient.newBuilder().build();
        httpClient = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (httpClient != null) {
            httpClient.close();
            delegate.shutdownNow();
            delegate.close();
            delegate = null;
            httpClient = null;
        }
    }

    @Test
    void decodesWhamUsageWithAuthHeaders(final JsonMapper mapper) throws IOException {
        seed(mapper, "codex", new CodexTokenStore("access-1", "refresh", "acc-1", farFuture(), "id"));
        final var client = new CodexUsageClient(mapper, httpClient, helper(mapper));
        final var snapshot = client.fetch(serverUrl, "codex");

        assertEquals("plus", snapshot.planType());
        assertEquals(3, snapshot.rateLimits().size(), "primary + secondary + additional windows");
        final var primary = snapshot.rateLimits().get(0);
        assertEquals("primary", primary.kind());
        assertEquals(42.5, primary.usedPercent(), 0.001);
        assertEquals(18000, primary.limitWindowSeconds());
        assertEquals(1700000000L, primary.resetAt());
        final var additional = snapshot.rateLimits().get(2);
        assertEquals("additional", additional.kind());
        assertEquals("code-review", additional.label());
        assertEquals("128.5", snapshot.credits().balance());

        final var headers = requestHeaders.get(0);
        assertEquals("Bearer access-1", headers[0], "Bearer access token must be sent");
        assertEquals("acc-1", headers[1], "ChatGPT-Account-Id must be sent");
    }

    @Test
    void maps401ToNeedsLogin(final JsonMapper mapper) throws IOException {
        seed(mapper, "unauthorized", new CodexTokenStore("access-2", "refresh", "acc-2", farFuture(), "id"));
        final var client = new CodexUsageClient(mapper, httpClient, helper(mapper));
        assertThrows(
                CodexUsageClient.NeedsLoginException.class,
                () -> client.fetch(serverUrl + "/unauthorized", "unauthorized"));
    }

    private CodexAuthTemplateHelper helper(final JsonMapper mapper) {
        final var helper = new CodexAuthTemplateHelper(mapper, httpClient, configuration);
        helper.setOAuthConfig(
                new CodexOAuthConfig(serverUrl, "test-client", "openid", serverUrl + "/codex/device", 1455));
        return helper;
    }

    public static class UsageModule extends ConfigurationTestModule {
        public UsageModule() {
            super(Map.of(
                    "yca.storageHome",
                    root.resolve(UUID.randomUUID().toString()).toString()));
        }
    }

    private void seed(final JsonMapper mapper, final String provider, final CodexTokenStore store) throws IOException {
        final Path file = storage.resolve("providers/" + provider + "/codex.auth.json");
        Files.createDirectories(file.getParent());
        Files.write(file, mapper.toBytes(store));
    }

    private static long farFuture() {
        return System.currentTimeMillis() + 3_600_000;
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (final var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
