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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try") // Explicit close simulates cancellation before fixture teardown.
class LlmRelayProviderBudgetTest {
    @TempDir
    Path directory;

    @Test
    void trustedConfigurationAuthenticatesRejectsSaturationAndReleasesCompletion() throws Exception {
        runChild("complete");
    }

    @Test
    void trustedConfigurationCountsStreamUntilUpstreamEof() throws Exception {
        runChild("stream");
    }

    @Test
    void trustedConfigurationRetainsAmbiguousCancellation() throws Exception {
        runChild("cancel");
    }

    private void runChild(final String scenario) throws Exception {
        Scenario.verify(
                scenario,
                Map.of(
                        ProviderRequestBudget.ROOT,
                        directory.resolve("budget").toString(),
                        ProviderRequestBudget.ID,
                        "account",
                        ProviderRequestBudget.SLOTS,
                        "1"));
    }

    private static final class Scenario {
        private static void verify(final String scenario, final Map<String, String> settings) throws Exception {
            final var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            final var release = new CountDownLatch(1);
            final var forwarded = new AtomicInteger();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                upstream.setExecutor(executor);
                upstream.createContext("/v1/chat/completions", exchange -> {
                    try (exchange) {
                        assertEquals("POST", exchange.getRequestMethod());
                        assertEquals(
                                "Bearer secret", exchange.getRequestHeaders().getFirst("Authorization"));
                        assertArrayEquals(
                                new byte[] {'{', '}'}, exchange.getRequestBody().readAllBytes());
                        forwarded.incrementAndGet();
                        if ("complete".equals(scenario)) {
                            exchange.sendResponseHeaders(422, 1);
                            exchange.getResponseBody().write('x');
                        } else {
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody().write('a');
                            exchange.getResponseBody().flush();
                            try {
                                release.await();
                            } catch (final InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            if ("stream".equals(scenario)) {
                                exchange.getResponseBody().write('b');
                            }
                        }
                    }
                });
                upstream.start();
                final var budget = ProviderRequestBudget.fromConfiguration(new ProviderBudgetConfiguration(
                        settings.get(ProviderRequestBudget.ROOT),
                        settings.get(ProviderRequestBudget.ID),
                        settings.get(ProviderRequestBudget.SLOTS)));
                try (final var relay = new LlmRelay(
                                new InetSocketAddress("127.0.0.1", 0),
                                URI.create("http://127.0.0.1:"
                                        + upstream.getAddress().getPort() + "/v1/chat/completions"),
                                "secret",
                                2,
                                budget);
                        final var client = HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .build()) {
                    switch (scenario) {
                        case "complete" -> {
                            try (final var held = budget.tryAcquire().orElseThrow()) {
                                assertEquals(
                                        401,
                                        client.send(request(relay, "wrong"), HttpResponse.BodyHandlers.discarding())
                                                .statusCode());
                                assertEquals(
                                        429,
                                        client.send(
                                                        request(relay, relay.accessToken()),
                                                        HttpResponse.BodyHandlers.discarding())
                                                .statusCode());
                                assertEquals(0, forwarded.get());
                            }
                            final var response = client.send(
                                    request(relay, relay.accessToken()), HttpResponse.BodyHandlers.ofString());
                            assertEquals(422, response.statusCode());
                            assertEquals("x", response.body());
                            assertAvailable(budget);
                        }
                        case "stream", "cancel" -> {
                            final var response = client.send(
                                    request(relay, relay.accessToken()), HttpResponse.BodyHandlers.ofInputStream());
                            assertEquals(200, response.statusCode());
                            try (final var input = response.body()) {
                                assertEquals('a', input.read());
                                assertTrue(budget.tryAcquire().isEmpty());
                                if ("stream".equals(scenario)) {
                                    assertEquals(
                                            429,
                                            client.send(
                                                            request(relay, relay.accessToken()),
                                                            HttpResponse.BodyHandlers.discarding())
                                                    .statusCode());
                                    release.countDown();
                                    assertArrayEquals(new byte[] {'b'}, input.readAllBytes());
                                    assertAvailable(budget);
                                } else {
                                    // Closing an active relay is not evidence of upstream EOF.
                                    relay.close();
                                    assertTrue(budget.tryAcquire().isEmpty());
                                    assertTrue(ProviderRequestBudget.fromConfiguration(new ProviderBudgetConfiguration(
                                                    settings.get(ProviderRequestBudget.ROOT),
                                                    settings.get(ProviderRequestBudget.ID),
                                                    settings.get(ProviderRequestBudget.SLOTS)))
                                            .tryAcquire()
                                            .isEmpty());
                                }
                            } finally {
                                release.countDown();
                            }
                        }
                        default -> throw new IllegalArgumentException(scenario);
                    }
                    assertEquals(1, forwarded.get());
                } finally {
                    release.countDown();
                    upstream.stop(0);
                }
            }
        }

        private static void assertAvailable(final ProviderRequestBudget budget) throws Exception {
            try (final var available = budget.tryAcquire().orElseThrow()) {
                assertNotNull(available);
            }
        }

        private static HttpRequest request(final LlmRelay relay, final String token) {
            return HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + relay.address().getPort() + "/chat/completions"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
        }
    }
}
