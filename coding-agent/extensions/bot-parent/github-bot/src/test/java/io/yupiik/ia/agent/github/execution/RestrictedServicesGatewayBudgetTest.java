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
package io.yupiik.ia.agent.github.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.core.llm.ProviderRequestBudget;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

// Real-TLS integration: cold fixtures can exceed 500 ms; retain the 5-second timeout.
@Timeout(5)
@SuppressWarnings("try") // Explicit close exercises gateway disconnect/shutdown before fixture teardown.
class RestrictedServicesGatewayBudgetTest {
    private static final String CONTEXT = "/context/12345678-1234-1234-1234-123456789abc";
    private static final String INFERENCE = "/inference/12345678-1234-1234-1234-123456789abc/chat/completions";

    @TempDir
    Path directory;

    @Test
    void inferenceOnlyUsesTrustedSharedBudgetWhileContextAndGitRemainAvailable() throws Exception {
        final var keyStore = directory.resolve("tls.p12");
        try (final var input = getClass().getResourceAsStream("provider-budget.p12")) {
            Files.copy(input, keyStore);
        }
        final var store = KeyStore.getInstance("PKCS12");
        try (final var input = Files.newInputStream(keyStore)) {
            store.load(input, "changeit".toCharArray());
        }
        final var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        final var tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);
        final var settings = Map.of(
                ProviderRequestBudget.ROOT,
                directory.resolve("budget").toString(),
                ProviderRequestBudget.ID,
                "account",
                ProviderRequestBudget.SLOTS,
                "1",
                RestrictedServicesGateway.YCA_SERVICES_PROVIDER_TOKEN,
                "p".repeat(43),
                RestrictedServicesGateway.YCA_SERVICES_UPSTREAM_TOKEN,
                "configured-upstream-token",
                RestrictedServicesGateway.YCA_CONTEXT_TOKEN,
                "c".repeat(43),
                RestrictedServicesGateway.YCA_CONTEXT_GIT_TOKEN,
                "g".repeat(43),
                RestrictedServicesGateway.YCA_SERVICES_KEYSTORE_PASSWORD,
                "changeit");
        final var budgetConfiguration = new ProviderBudgetConfiguration(
                settings.get(ProviderRequestBudget.ROOT),
                settings.get(ProviderRequestBudget.ID),
                settings.get(ProviderRequestBudget.SLOTS));
        final var budget = ProviderRequestBudget.fromConfiguration(budgetConfiguration);
        final var release = new CountDownLatch(1);
        final var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            upstream.setExecutor(executor);
            upstream.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/chat")) {
                    assertEquals(
                            "Bearer configured-upstream-token",
                            exchange.getRequestHeaders().getFirst("Authorization"));
                }
                exchange.getRequestBody().readAllBytes();
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('a');
                    exchange.getResponseBody().flush();
                    if (exchange.getRequestURI().getPath().equals("/chat")) {
                        try {
                            release.await();
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            });
            upstream.start();
            final var base = "http://127.0.0.1:" + upstream.getAddress().getPort();
            final var policy = new RestrictedServicesGateway.Settings(
                    "localhost",
                    0,
                    keyStore.toString(),
                    RestrictedServicesGateway.YCA_SERVICES_KEYSTORE_PASSWORD,
                    CONTEXT,
                    base,
                    INFERENCE,
                    base + "/chat",
                    60,
                    5,
                    1024,
                    1024);
            try (final var gateway =
                            new RestrictedServicesGateway(policy, Configuration.of(settings), budgetConfiguration);
                    final var client = HttpClient.newBuilder()
                            .sslContext(tls)
                            .version(HttpClient.Version.HTTP_1_1)
                            .build()) {
                try (final var held = budget.tryAcquire().orElseThrow()) {
                    assertEquals(
                            401,
                            client.send(
                                            request(gateway, INFERENCE, "Bearer wrong"),
                                            HttpResponse.BodyHandlers.discarding())
                                    .statusCode());
                    assertEquals(
                            429,
                            client.send(
                                            request(gateway, INFERENCE, "Bearer " + "p".repeat(43)),
                                            HttpResponse.BodyHandlers.discarding())
                                    .statusCode());
                    assertEquals(
                            200,
                            client.send(
                                            request(gateway, CONTEXT + "/rpc", "Bearer " + "c".repeat(43)),
                                            HttpResponse.BodyHandlers.ofString())
                                    .statusCode());
                    final var gitAuth = "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(("context:" + "g".repeat(43)).getBytes(StandardCharsets.US_ASCII));
                    assertEquals(
                            200,
                            client.send(
                                            HttpRequest.newBuilder(
                                                            URI.create(
                                                                    "https://localhost:" + gateway.port() + CONTEXT
                                                                            + "/git/view.git/info/refs?service=git-upload-pack"))
                                                    .header("Authorization", gitAuth)
                                                    .GET()
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .statusCode());
                    assertTrue(budget.tryAcquire().isEmpty());
                }
                final var streaming = client.send(
                        request(gateway, INFERENCE, "Bearer " + "p".repeat(43)),
                        HttpResponse.BodyHandlers.ofInputStream());
                try (final var input = streaming.body()) {
                    assertEquals('a', input.read());
                    assertTrue(budget.tryAcquire().isEmpty());
                    gateway.close();
                    assertTrue(budget.tryAcquire().isEmpty());
                }
                assertTrue(ProviderRequestBudget.fromConfiguration(budgetConfiguration)
                        .tryAcquire()
                        .isEmpty());
            } finally {
                release.countDown();
                upstream.stop(0);
            }
        }
    }

    private static HttpRequest request(
            final RestrictedServicesGateway gateway, final String path, final String authorization) {
        return HttpRequest.newBuilder(URI.create("https://localhost:" + gateway.port() + path))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
    }
}
