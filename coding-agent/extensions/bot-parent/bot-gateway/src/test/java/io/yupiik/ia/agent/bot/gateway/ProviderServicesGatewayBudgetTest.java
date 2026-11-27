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
package io.yupiik.ia.agent.bot.gateway;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(5)
@TestMethodOrder(MethodOrderer.MethodName.class)
@SuppressWarnings("try") // Explicit close exercises gateway disconnect/shutdown before fixture teardown.
class ProviderServicesGatewayBudgetTest {
    private static final String ROUTE = "/inference/12345678-1234-1234-1234-123456789abc/chat/completions";

    @TempDir
    Path directory;

    /* Real TLS/JDK integration: cold crypto and handshake setup can exceed 500 ms; retain the 5-second timeout. */
    @Test
    void completedUpstreamErrorResponseReleasesBudgetAtEof() throws Exception {
        try (final var fixture = new Fixture(directory, false);
                final var gateway = new ProviderServicesGateway(
                        fixture.policy, Configuration.of(fixture.settings), budgetConfiguration(fixture.settings));
                final var client = HttpClient.newBuilder()
                        .sslContext(fixture.tls)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build()) {
            assertEquals(
                    422,
                    client.send(request(gateway, "p".repeat(43)), HttpResponse.BodyHandlers.ofString())
                            .statusCode());
            // Closing the downstream body exposes EOF before the handler's durable completion.
            // Wait for that asynchronous cleanup, retaining the successful acquisition for close.
            final long deadline = System.nanoTime() + Duration.ofMillis(400).toNanos();
            var acquired = fixture.budget.tryAcquire();
            while (acquired.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(1);
                acquired = fixture.budget.tryAcquire();
            }
            assertTrue(acquired.isPresent(), "Completed upstream response did not release its budget slot");
            try (final var available = acquired.orElseThrow()) {
                assertNotNull(available);
            }
        }
    }

    @Test
    void streamsShareTrustedBudgetAndCancellationRetainsDebt() throws Exception {
        try (final var fixture = new Fixture(directory, true);
                final var first = new ProviderServicesGateway(
                        fixture.policy, Configuration.of(fixture.settings), budgetConfiguration(fixture.settings));
                final var second = new ProviderServicesGateway(
                        fixture.policy, Configuration.of(fixture.settings), budgetConfiguration(fixture.settings));
                final var client = HttpClient.newBuilder()
                        .sslContext(fixture.tls)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build()) {
            try (final var held = fixture.budget.tryAcquire().orElseThrow()) {
                assertEquals(
                        401,
                        client.send(request(first, "wrong"), HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                assertEquals(
                        429,
                        client.send(request(second, "p".repeat(43)), HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                assertEquals(0, fixture.calls.get());
            }
            final var streaming =
                    client.send(request(first, "p".repeat(43)), HttpResponse.BodyHandlers.ofInputStream());
            try (final var input = streaming.body()) {
                assertEquals('a', input.read());
                assertTrue(fixture.budget.tryAcquire().isEmpty());
                assertEquals(
                        429,
                        client.send(request(second, "p".repeat(43)), HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                first.close();
                assertTrue(fixture.budget.tryAcquire().isEmpty());
            }
            assertTrue(ProviderRequestBudget.fromConfiguration(budgetConfiguration(fixture.settings))
                    .tryAcquire()
                    .isEmpty());
        }
    }

    private static ProviderBudgetConfiguration budgetConfiguration(final Map<String, String> settings) {
        return new ProviderBudgetConfiguration(
                settings.get(ProviderRequestBudget.ROOT),
                settings.get(ProviderRequestBudget.ID),
                settings.get(ProviderRequestBudget.SLOTS));
    }

    private static HttpRequest request(final ProviderServicesGateway gateway, final String token) {
        return HttpRequest.newBuilder(URI.create("https://localhost:" + gateway.port() + ROUTE))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
    }

    private static final class Fixture implements AutoCloseable {
        private final SSLContext tls;
        private final Map<String, String> settings;
        private final ProviderRequestBudget budget;
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch release = new CountDownLatch(1);
        private final HttpServer upstream;
        private final ProviderServicesGateway.Settings policy;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private Fixture(final Path directory, final boolean stall) throws Exception {
            // Existing localhost test key/leaf, re-encoded with low test-only PKCS12 iteration counts.
            // Fixtures expire in October 2036. No key generation or JVM property mutation in tests.
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
            tls = SSLContext.getInstance("TLS");
            tls.init(null, trust.getTrustManagers(), null);
            settings = Map.of(
                    ProviderRequestBudget.ROOT,
                    directory.resolve("budget").toString(),
                    ProviderRequestBudget.ID,
                    "account",
                    ProviderRequestBudget.SLOTS,
                    "1",
                    ProviderServicesGateway.YCA_SERVICES_PROVIDER_TOKEN,
                    "p".repeat(43),
                    ProviderServicesGateway.YCA_SERVICES_UPSTREAM_TOKEN,
                    "configured-upstream-token",
                    ProviderServicesGateway.YCA_SERVICES_KEYSTORE_PASSWORD,
                    "changeit");
            budget = ProviderRequestBudget.fromConfiguration(budgetConfiguration(settings));
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.setExecutor(executor);
            upstream.createContext("/chat/completions", exchange -> {
                assertEquals(
                        "Bearer configured-upstream-token",
                        exchange.getRequestHeaders().getFirst("Authorization"));
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                try (exchange) {
                    exchange.sendResponseHeaders(422, 0);
                    exchange.getResponseBody().write('a');
                    exchange.getResponseBody().flush();
                    if (stall) {
                        try {
                            release.await();
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            });
            upstream.start();
            policy = new ProviderServicesGateway.Settings(
                    "localhost",
                    0,
                    keyStore.toString(),
                    ProviderServicesGateway.YCA_SERVICES_KEYSTORE_PASSWORD,
                    ROUTE,
                    "http://127.0.0.1:" + upstream.getAddress().getPort() + "/chat/completions",
                    60,
                    5,
                    1024,
                    1024,
                    4);
        }

        @Override
        public void close() {
            release.countDown();
            upstream.stop(0);
            executor.shutdownNow();
            executor.close();
        }
    }
}
