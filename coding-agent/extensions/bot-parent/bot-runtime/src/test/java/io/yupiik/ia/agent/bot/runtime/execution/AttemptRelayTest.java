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
package io.yupiik.ia.agent.bot.runtime.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.ia.agent.bot.runtime.JsonObjects;
import io.yupiik.ia.agent.bot.runtime.RuntimeJsonMapper;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AttemptRelayTest {
    @TempDir
    Path home;

    @Test
    void rejectsIncompleteInjectedBudgetConfigurationBeforeRewritingAgentSettings() throws Exception {
        try (final var json = RuntimeJsonMapper.create(key -> Optional.empty())) {
            final var configuration = home.resolve("configuration.json");
            final var original = json.toString(Map.of(
                    "yca",
                    Map.of("provider", Map.of("baseUrl", "http://127.0.0.1:8080/v1", "apiKey", "upstream-secret"))));
            Files.writeString(configuration, original);
            final var config = new ProviderBudgetConfiguration(null, "orphan", null);
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var _ = new AttemptRelay(configuration, json, "127.0.0.1", "http://127.0.0.1", config)) {
                    fail("Incomplete injected provider budget must be rejected");
                }
            });
            assertEquals(original, Files.readString(configuration));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/chat/completions", "/responses"})
    void removesProviderSecretsAndOnlyForwardsThroughAttemptToken(final String route) throws Exception {
        final var auth = new AtomicReference<String>();
        final var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1" + route, exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        upstream.start();
        try (final var json = RuntimeJsonMapper.create(key -> Optional.empty());
                final var client = HttpClient.newHttpClient()) {
            final var configuration = home.resolve("configuration.json");
            Files.writeString(
                    configuration,
                    json.toString(Map.of(
                            "yca",
                            Map.of(
                                    "provider",
                                            Map.of(
                                                    "baseUrl",
                                                    "http://127.0.0.1:"
                                                            + upstream.getAddress()
                                                                    .getPort() + "/v1" + route,
                                                    "apiKey",
                                                    "upstream-secret",
                                                    "headers",
                                                    Map.of("other", "secret"),
                                                    "model",
                                                    "test"),
                                    "model", Map.of("apiKey", "alternate-secret")),
                            "run",
                            Map.of(
                                    "result-correction-prompt",
                                    "Repair {{{file}}}: {{{validationError}}}",
                                    "prompt",
                                    "must-not-forward",
                                    "result-file",
                                    "/host/forbidden"))));
            String endpoint;
            final var config = new ProviderBudgetConfiguration(null, null, null);
            try (final var _ = new AttemptRelay(configuration, json, "127.0.0.1", "http://127.0.0.1", config)) {
                final var text = Files.readString(configuration);
                assertFalse(text.contains("upstream-secret"));
                assertFalse(text.contains("alternate-secret"));
                assertFalse(text.contains("headers"));
                assertFalse(text.contains("must-not-forward"));
                assertFalse(text.contains("/host/forbidden"));
                assertEquals(
                        Map.of("result-correction-prompt", "Repair {{{file}}}: {{{validationError}}}"),
                        JsonObjects.map(json.fromString(Object.class, text)).get("run"));
                final var yca = JsonObjects.map(
                        JsonObjects.map(json.fromString(Object.class, text)).get("yca"));
                final var provider = JsonObjects.map(yca.get("provider"));
                assertEquals(route.equals("/responses") ? "RESPONSES" : "COMPLETIONS", provider.get("api"));
                assertTrue(String.valueOf(provider.get("baseUrl")).endsWith(route));
                endpoint = String.valueOf(provider.get("baseUrl"));
                assertEquals(
                        200,
                        client.send(
                                        HttpRequest.newBuilder(URI.create(endpoint))
                                                .header("Authorization", "Bearer " + provider.get("apiKey"))
                                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                assertEquals("Bearer upstream-secret", auth.get());
            }
            assertThrows(
                    IOException.class,
                    () -> client.send(
                            HttpRequest.newBuilder(URI.create(endpoint))
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding()));
        } finally {
            upstream.stop(0);
        }
    }
}
