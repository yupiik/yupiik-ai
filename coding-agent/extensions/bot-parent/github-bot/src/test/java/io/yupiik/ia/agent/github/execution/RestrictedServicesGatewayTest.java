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

import static io.yupiik.ia.agent.github.execution.RestrictedServicesGateway.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import io.yupiik.ia.agent.github.support.TestJava;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class RestrictedServicesGatewayTest {
    @TempDir
    private static Path directory;

    private static Path keyStore;
    private static SSLContext trusted;
    private static final String ROUTE = "/context/12345678-1234-1234-1234-123456789abc";
    private static final String INFER = "/inference/12345678-1234-1234-1234-123456789abc/chat/completions";
    private static final String CONTROL = "c".repeat(43);
    private static final String GIT = "g".repeat(43);
    private static final String PROVIDER = "p".repeat(43);
    private static final String BASIC =
            "Basic " + Base64.getEncoder().encodeToString(("context:" + GIT).getBytes(StandardCharsets.US_ASCII));

    @BeforeAll
    static void certificate() throws Exception {
        keyStore = directory.resolve("tls.p12");
        final var process = new ProcessBuilder(TestJava.keytoolCommand(
                        "-genkeypair",
                        "-alias",
                        "leaf",
                        "-keyalg",
                        "EC",
                        "-groupname",
                        "secp256r1",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost",
                        "-validity",
                        "2",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        keyStore.toString(),
                        "-storepass",
                        "test-password",
                        "-keypass",
                        "test-password",
                        "-noprompt"))
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("keytool.log").toFile())
                .start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Bounded certificate generation timed out");
        }
        assertEquals(0, process.exitValue());
        final var store = KeyStore.getInstance("PKCS12");
        try (final var input = Files.newInputStream(keyStore)) {
            store.load(input, "test-password".toCharArray());
        }
        final var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        trusted = SSLContext.getInstance("TLS");
        trusted.init(null, trust.getTrustManagers(), null);
    }

    @Test
    void rpcGitAndInferenceUseSeparateCredentialsAndFixedTargets() throws Exception {
        try (final var fixture = new Fixture();
                final var gateway = new RestrictedServicesGateway(
                        settings(fixture, 20, 5, 1024, 1024),
                        Configuration.of(secrets()),
                        new ProviderBudgetConfiguration(null, null, null));
                final var client = client()) {
            assertTrue(gateway.port() > 0);
            assertEquals(
                    200,
                    send(client, gateway, ROUTE + "/rpc", "Bearer " + CONTROL, "application/json", "{}")
                            .statusCode());
            assertEquals("Bearer " + CONTROL, fixture.contextAuth.get());
            assertEquals(ROUTE + "/rpc", fixture.contextPath.get());
            final var discovery = client.send(
                    HttpRequest.newBuilder(uri(gateway, ROUTE + "/git/view-1.git/info/refs?service=git-upload-pack"))
                            .header("Authorization", BASIC)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, discovery.statusCode());
            assertArrayEquals(Fixture.BINARY, discovery.body());
            assertEquals(BASIC, fixture.contextAuth.get());
            assertEquals(
                    200,
                    send(
                                    client,
                                    gateway,
                                    ROUTE + "/git/view-1.git/git-upload-pack",
                                    BASIC,
                                    "application/x-git-upload-pack-request",
                                    "0000")
                            .statusCode());
            final var response = send(client, gateway, INFER, "Bearer " + PROVIDER, "application/json", "{}");
            assertEquals(200, response.statusCode());
            assertEquals("data: first\n\ndata: [DONE]\n\n", response.body());
            assertEquals("Bearer upstream-private", fixture.inferenceAuth.get());
            assertEquals("/v1/chat/completions", fixture.inferencePath.get());
            assertFalse(response.headers().map().containsKey("set-cookie"));
        }
    }

    @Test
    void rejectsWrongRolesEscapesQueriesAndBrowserBeforeUpstream() throws Exception {
        try (final var fixture = new Fixture();
                final var gateway = new RestrictedServicesGateway(
                        settings(fixture, 20, 5, 1024, 1024),
                        Configuration.of(secrets()),
                        new ProviderBudgetConfiguration(null, null, null));
                final var client = client()) {
            assertEquals(
                    401,
                    send(client, gateway, ROUTE + "/rpc", "Bearer " + PROVIDER, "application/json", "{}")
                            .statusCode());
            assertEquals(
                    401,
                    send(client, gateway, INFER, "Bearer " + CONTROL, "application/json", "{}")
                            .statusCode());
            assertEquals(
                    401,
                    send(
                                    client,
                                    gateway,
                                    ROUTE + "/git/x.git/git-upload-pack",
                                    "Bearer " + CONTROL,
                                    "application/x-git-upload-pack-request",
                                    "0000")
                            .statusCode());
            for (final var path : List.of(
                    ROUTE + "/rpc?x=1",
                    INFER + "?x=1",
                    ROUTE + "/git/x.git/info/refs?service=git-receive-pack",
                    ROUTE + "/rpc/../rpc",
                    ROUTE + "/%72pc",
                    ROUTE.replace("abc", "abd") + "/rpc",
                    ROUTE + "/git/../x.git/git-upload-pack")) {
                assertTrue(
                        send(client, gateway, path, "Bearer " + CONTROL, "application/json", "{}")
                                        .statusCode()
                                >= 400,
                        path);
            }
            assertEquals(
                    415,
                    send(client, gateway, ROUTE + "/rpc", "Bearer " + CONTROL, "text/plain", "{}")
                            .statusCode());
            assertEquals(
                    400,
                    client.send(
                                    HttpRequest.newBuilder(uri(gateway, INFER))
                                            .header("Authorization", "Bearer " + PROVIDER)
                                            .header("Origin", "https://localhost")
                                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            assertEquals(0, fixture.calls.get());
        }
    }

    @Test
    void tlsHostSniFramingAndHeaderLimits() throws Exception {
        try (final var fixture = new Fixture();
                final var gateway = new RestrictedServicesGateway(
                        settings(fixture, 20, 5, 1024, 1024),
                        Configuration.of(secrets()),
                        new ProviderBudgetConfiguration(null, null, null));
                final var untrusted = HttpClient.newHttpClient()) {
            assertThrows(
                    IOException.class,
                    () -> send(untrusted, gateway, INFER, "Bearer " + PROVIDER, "application/json", "{}"));
            assertThrows(SSLHandshakeException.class, () -> {
                try (final var socket = socket(gateway, "other.invalid")) {
                    socket.startHandshake();
                }
            });
            for (final var extra : List.of(
                    "Host: other.invalid:" + gateway.port() + "\r\n",
                    "Authorization: Bearer " + CONTROL + "\r\n",
                    "Content-Length: 0\r\nContent-Length: 1\r\n",
                    "Content-Length: 0\r\nTransfer-Encoding: chunked\r\n",
                    "Upgrade: websocket\r\n",
                    "X-Large: " + "x".repeat(17000) + "\r\n")) {
                final var result = raw(
                        gateway,
                        "POST " + ROUTE + "/rpc HTTP/1.1\r\nHost: localhost:" + gateway.port()
                                + "\r\nAuthorization: Bearer " + CONTROL + "\r\n" + extra
                                + "Connection: close\r\n\r\n");
                assertFalse(result.startsWith("HTTP/1.1 200"));
            }
            assertFalse(raw(
                            gateway,
                            "POST http://localhost:" + gateway.port() + ROUTE + "/rpc HTTP/1.1\r\nHost: localhost:"
                                    + gateway.port() + "\r\nConnection: close\r\n\r\n")
                    .startsWith("HTTP/1.1 200"));
            assertTrue(raw(
                            gateway,
                            "POST " + ROUTE + "/rpc#fragment HTTP/1.1\r\nHost: localhost:" + gateway.port()
                                    + "\r\nAuthorization: Bearer " + CONTROL
                                    + "\r\nContent-Type: application/json\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .startsWith("HTTP/1.1 400"));
            assertEquals(0, fixture.calls.get());
        }
    }

    @Test
    void requestAndResponseBoundsAndAnonymousUpstream() throws Exception {
        try (final var fixture = new Fixture()) {
            final var secrets = new HashMap<>(secrets());
            secrets.remove(YCA_SERVICES_UPSTREAM_TOKEN);
            try (final var gateway = new RestrictedServicesGateway(
                            settings(fixture, 20, 5, 4, 8),
                            Configuration.of(secrets),
                            new ProviderBudgetConfiguration(null, null, null));
                    final var client = client()) {
                assertEquals(
                        413,
                        send(client, gateway, INFER, "Bearer " + PROVIDER, "application/json", "12345")
                                .statusCode());
                assertEquals(
                        413,
                        send(
                                        client,
                                        gateway,
                                        ROUTE + "/rpc",
                                        "Bearer " + CONTROL,
                                        "application/json",
                                        "x".repeat(16385))
                                .statusCode());
                try {
                    assertEquals(
                            413,
                            send(
                                            client,
                                            gateway,
                                            ROUTE + "/git/x.git/git-upload-pack",
                                            BASIC,
                                            "application/x-git-upload-pack-request",
                                            "x".repeat(65537))
                                    .statusCode());
                } catch (final IOException rejectedBeforeBody) {
                    // A large unconsumed body can cause a reset rather than a readable early status.
                }
                assertEquals(0, fixture.calls.get());
                // Known oversized upstream responses fail before downstream headers.
                fixture.large.set(true);
                assertEquals(
                        502,
                        send(client, gateway, INFER, "Bearer " + PROVIDER, "application/json", "{}")
                                .statusCode());
                assertNull(fixture.inferenceAuth.get());
            }
        }
    }

    @Test
    void streamsWithoutWaitingForCompletion() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.stall.set(true);
            try (final var gateway = new RestrictedServicesGateway(
                            settings(fixture, 60, 60, 1024, 1024),
                            Configuration.of(secrets()),
                            new ProviderBudgetConfiguration(null, null, null));
                    final var client = client();
                    final AutoCloseable _ = () -> fixture.release.countDown()) {
                assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                    final var response = client.send(
                            HttpRequest.newBuilder(uri(gateway, INFER))
                                    .header("Authorization", "Bearer " + PROVIDER)
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    try (final var body = response.body()) {
                        assertEquals(200, response.statusCode());
                        assertEquals('d', body.read());
                        assertEquals(1, fixture.release.getCount(), "Upstream completion must still be blocked");
                    }
                });
            }
        }
    }

    @Test
    void cancelsStreamingOnExpiry() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.stall.set(true);
            try (final var gateway = new RestrictedServicesGateway(
                            settings(fixture, 10, 30, 1024, 1024),
                            Configuration.of(secrets()),
                            new ProviderBudgetConfiguration(null, null, null));
                    final var client = client();
                    // Release the stalled upstream before client.close(), including on assertion failure.
                    final AutoCloseable _ = () -> fixture.release.countDown()) {
                final var response = client.send(
                        HttpRequest.newBuilder(uri(gateway, INFER))
                                .header("Authorization", "Bearer " + PROVIDER)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                try (final var body = response.body()) {
                    assertEquals('d', body.read());
                    assertEquals(200, response.statusCode());
                    assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                        try {
                            body.readAllBytes();
                        } catch (final IOException cancelled) {
                            // Premature chunked response termination is expected on expiry.
                        }
                    });
                }
                assertEquals(1, fixture.release.getCount(), "Expiry must cancel without releasing the upstream");
                assertThrows(
                        IOException.class,
                        () -> send(client, gateway, INFER, "Bearer " + PROVIDER, "application/json", "{}"));
            }
        }
    }

    @Test
    void deadlinesCancelAwaitingHeadersAndCloseIsIdempotent() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.noHeaders.set(true);
            try (final var gateway = new RestrictedServicesGateway(
                            settings(fixture, 20, 1, 1024, 1024),
                            Configuration.of(secrets()),
                            new ProviderBudgetConfiguration(null, null, null));
                    final var client = client();
                    final AutoCloseable _ = () -> fixture.release.countDown()) {
                assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
                    try {
                        assertEquals(
                                502,
                                send(client, gateway, INFER, "Bearer " + PROVIDER, "application/json", "{}")
                                        .statusCode());
                    } catch (final IOException cancelled) {
                        // Closing the exchange at the deadline is also a fail-closed outcome.
                    }
                });
                Assertions.assertDoesNotThrow(gateway::close);
                Assertions.assertDoesNotThrow(gateway::close);
            }
        }
    }

    @Test
    void onlyFourConcurrentExchangesAndNeverFollowsRedirects() throws Exception {
        try (final var fixture = new Fixture();
                final var gateway = new RestrictedServicesGateway(
                        settings(fixture, 20, 5, 1024, 1024),
                        Configuration.of(secrets()),
                        new ProviderBudgetConfiguration(null, null, null));
                final var client = client()) {
            fixture.noHeaders.set(true);
            final var pending = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            try {
                for (int i = 0; i < 4; i++) {
                    pending.add(client.sendAsync(
                            HttpRequest.newBuilder(uri(gateway, INFER))
                                    .header("Authorization", "Bearer " + PROVIDER)
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString()));
                }
                assertTrue(fixture.concurrentRequests.await(3, TimeUnit.SECONDS));
                assertEquals(4, fixture.calls.get());
                assertEquals(
                        503,
                        send(client, gateway, INFER, "Bearer " + PROVIDER, "application/json", "{}")
                                .statusCode());
            } finally {
                Assertions.assertDoesNotThrow(gateway::close);
                pending.forEach(future -> future.cancel(true));
                fixture.release.countDown();
            }
        }
        try (final var fixture = new Fixture();
                final var gateway = new RestrictedServicesGateway(
                        settings(fixture, 20, 5, 1024, 1024),
                        Configuration.of(secrets()),
                        new ProviderBudgetConfiguration(null, null, null));
                final var client = client()) {
            fixture.redirect.set(true);
            assertEquals(
                    502,
                    send(client, gateway, INFER, "Bearer " + PROVIDER, "application/json", "{}")
                            .statusCode());
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void checkMainPerformsOnlyPinnedTlsAndFailsClosed() throws Exception {
        final var otherStore = directory.resolve("other.p12");
        final var keytool = new ProcessBuilder(TestJava.keytoolCommand(
                        "-genkeypair",
                        "-alias",
                        "leaf",
                        "-keyalg",
                        "EC",
                        "-groupname",
                        "secp256r1",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost",
                        "-validity",
                        "2",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        otherStore.toString(),
                        "-storepass",
                        "test-password",
                        "-keypass",
                        "test-password",
                        "-noprompt"))
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("other-keytool.log").toFile())
                .start();
        try {
            assertTrue(keytool.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, keytool.exitValue());
        } finally {
            keytool.destroyForcibly();
        }
        try (final var fixture = new Fixture();
                final var gateway = new RestrictedServicesGateway(
                        settings(fixture, 20, 5, 1024, 1024),
                        Configuration.of(secrets()),
                        new ProviderBudgetConfiguration(null, null, null))) {
            final var base = settings(fixture, 20, 5, 1024, 1024);
            final var good = new Settings(
                    "localhost",
                    gateway.port(),
                    keyStore.toString(),
                    "TLS_PASSWORD",
                    ROUTE,
                    base.contextTarget(),
                    INFER,
                    base.inferenceTarget(),
                    20,
                    5,
                    1024,
                    1024);
            assertEquals(0, check(good, "test-password"));
            assertEquals(1, check(good, "wrong-password"));
            assertEquals(
                    1,
                    check(
                            new Settings(
                                    "localhost",
                                    gateway.port(),
                                    otherStore.toString(),
                                    "TLS_PASSWORD",
                                    ROUTE,
                                    base.contextTarget(),
                                    INFER,
                                    base.inferenceTarget(),
                                    20,
                                    5,
                                    1024,
                                    1024),
                            "test-password"));
            assertEquals(
                    1,
                    check(
                            new Settings(
                                    "other.invalid",
                                    gateway.port(),
                                    keyStore.toString(),
                                    "TLS_PASSWORD",
                                    ROUTE,
                                    base.contextTarget(),
                                    INFER,
                                    base.inferenceTarget(),
                                    20,
                                    5,
                                    1024,
                                    1024),
                            "test-password"));
            Assertions.assertDoesNotThrow(gateway::close);
            assertEquals(1, check(good, "test-password"));
            assertEquals(0, fixture.calls.get());
        }
    }

    private static int check(final Settings settings, final String password) throws Exception {
        final var config = Files.createTempFile(directory, "check-", ".json");
        try (final var json = JsonMappers.create(ignored -> Optional.empty())) {
            Files.writeString(config, json.toString(settings));
        }
        final var classpath = System.getProperty("java.class.path");
        final var builder = new ProcessBuilder(TestJava.javaCommand(
                "-XX:ActiveProcessorCount=2",
                "-XX:+UseSerialGC",
                "-Xmx32m",
                "-D" + settings.keyStorePasswordEnv() + "=" + password,
                "-cp",
                classpath,
                RestrictedServicesGateway.class.getName(),
                "--check",
                config.toString()));
        builder.environment().clear();
        // The standalone Fusion configuration must prefer the JVM property to this conflicting environment value.
        builder.environment().put(settings.keyStorePasswordEnv(), "wrong-environment-password");
        final var output = Files.createTempFile(directory, "check-", ".log");
        final var process = builder.redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            // Include cold JVM/PKCS12 initialization under parallel reactor load; socket waits stay 2 seconds.
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals("", Files.readString(output));
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void mainReadsSettingsAndKeepsProcessAliveUntilExpiry() throws Exception {
        try (final var fixture = new Fixture();
                final var json = JsonMappers.create(ignored -> Optional.empty())) {
            final var config = directory.resolve("services.json");
            Files.writeString(config, json.toString(settings(fixture, 1, 1, 1024, 1024)));
            final var classpath = System.getProperty("java.class.path");
            final var builder = new ProcessBuilder(TestJava.javaCommand(
                    "-cp", classpath, RestrictedServicesGateway.class.getName(), "--config", config.toString()));
            builder.environment().putAll(secrets());
            builder.redirectErrorStream(true)
                    .redirectOutput(directory.resolve("main.log").toFile());
            final long start = System.nanoTime();
            final var process = builder.start();
            try {
                assertTrue(process.waitFor(8, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
                assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(900));
            } finally {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void settingsRejectDnsPublicAndAmbiguousTargetsAndRoundTripJson() throws Exception {
        try (final var fixture = new Fixture();
                final var json = JsonMappers.create(ignored -> Optional.empty())) {
            final var good = settings(fixture, 20, 5, 1024, 1024);
            assertEquals(good, json.fromString(Settings.class, json.toString(good)));
            for (final var target : List.of(
                    "http://localhost:80",
                    "http://8.8.8.8",
                    "https://127.0.0.1",
                    "http://127.0.0.1/a",
                    "http://127.0.0.1?x",
                    "http://user@127.0.0.1",
                    "http://127.000.0.1",
                    "http://127.0.0.1:0")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new Settings(
                                "localhost",
                                0,
                                keyStore.toString(),
                                YCA_SERVICES_KEYSTORE_PASSWORD,
                                ROUTE,
                                target,
                                INFER,
                                "http://127.0.0.1/v1/chat/completions",
                                1,
                                1,
                                1,
                                1));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestrictedServicesGateway(
                            good, Configuration.of(Map.of()), new ProviderBudgetConfiguration(null, null, null)));
            final var wrong = new Settings(
                    "other.invalid",
                    0,
                    keyStore.toString(),
                    YCA_SERVICES_KEYSTORE_PASSWORD,
                    ROUTE,
                    good.contextTarget(),
                    INFER,
                    good.inferenceTarget(),
                    1,
                    1,
                    1,
                    1);
            assertThrows(
                    IOException.class,
                    () -> new RestrictedServicesGateway(
                            wrong, Configuration.of(secrets()), new ProviderBudgetConfiguration(null, null, null)));
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .sslContext(trusted)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    private static URI uri(final RestrictedServicesGateway gateway, final String path) {
        return URI.create("https://localhost:" + gateway.port() + path);
    }

    private static HttpResponse<String> send(
            final HttpClient client,
            final RestrictedServicesGateway gateway,
            final String path,
            final String auth,
            final String type,
            final String body)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(gateway, path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", auth)
                        .header("Content-Type", type)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> secrets() {
        return Map.of(
                YCA_CONTEXT_TOKEN,
                CONTROL,
                YCA_CONTEXT_GIT_TOKEN,
                GIT,
                YCA_SERVICES_PROVIDER_TOKEN,
                PROVIDER,
                YCA_SERVICES_UPSTREAM_TOKEN,
                "upstream-private",
                YCA_SERVICES_KEYSTORE_PASSWORD,
                "test-password");
    }

    private static Settings settings(
            final Fixture fixture, final long life, final int timeout, final long request, final long response) {
        return new Settings(
                "localhost",
                0,
                keyStore.toString(),
                YCA_SERVICES_KEYSTORE_PASSWORD,
                ROUTE,
                "http://127.0.0.1:" + fixture.context.getAddress().getPort(),
                INFER,
                "http://127.0.0.1:" + fixture.inference.getAddress().getPort() + "/v1/chat/completions",
                life,
                timeout,
                request,
                response);
    }

    private static SSLSocket socket(final RestrictedServicesGateway gateway, final String sni) throws IOException {
        final var plain = new Socket();
        plain.connect(new InetSocketAddress("127.0.0.1", gateway.port()), 3000);
        final var socket =
                (SSLSocket) trusted.getSocketFactory().createSocket(plain, "localhost", gateway.port(), true);
        socket.setSoTimeout(3000);
        final var parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setServerNames(List.of(new SNIHostName(sni)));
        socket.setSSLParameters(parameters);
        return socket;
    }

    private static String raw(final RestrictedServicesGateway gateway, final String request) throws IOException {
        try (final var socket = socket(gateway, "localhost")) {
            socket.startHandshake();
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            try {
                return new String(socket.getInputStream().readNBytes(64), StandardCharsets.US_ASCII);
            } catch (final IOException rejected) {
                return "";
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private static final byte[] BINARY = {0, 1, 2, 3, (byte) 255};
        private final HttpServer context = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        private final HttpServer inference = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicReference<String> contextAuth = new AtomicReference<>();
        private final AtomicReference<String> inferenceAuth = new AtomicReference<>();
        private final AtomicReference<String> contextPath = new AtomicReference<>();
        private final AtomicReference<String> inferencePath = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean large = new AtomicBoolean();
        private final AtomicBoolean stall = new AtomicBoolean();
        private final AtomicBoolean noHeaders = new AtomicBoolean();
        private final AtomicBoolean redirect = new AtomicBoolean();
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch concurrentRequests = new CountDownLatch(4);

        private Fixture() throws IOException {
            context.setExecutor(executor);
            inference.setExecutor(executor);
            context.createContext("/", exchange -> {
                calls.incrementAndGet();
                contextAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                contextPath.set(exchange.getRequestURI().toString());
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/x-git-upload-pack-result");
                exchange.sendResponseHeaders(200, 0);
                try (exchange;
                        final var output = exchange.getResponseBody()) {
                    for (final byte value : BINARY) {
                        output.write(value);
                        output.flush();
                    }
                }
            });
            inference.createContext("/", exchange -> {
                calls.incrementAndGet();
                concurrentRequests.countDown();
                inferenceAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                inferencePath.set(exchange.getRequestURI().toString());
                exchange.getRequestBody().readAllBytes();
                try (exchange) {
                    if (redirect.get()) {
                        exchange.getResponseHeaders()
                                .set(
                                        "Location",
                                        "http://127.0.0.1:"
                                                + context.getAddress().getPort() + "/redirect");
                        exchange.sendResponseHeaders(302, -1);
                        return;
                    }
                    if (noHeaders.get()) {
                        await();
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.getResponseHeaders().set("Set-Cookie", "private=secret");
                    exchange.sendResponseHeaders(200, large.get() ? 100 : 0);
                    exchange.getResponseBody().write("data: first\n\n".getBytes(StandardCharsets.US_ASCII));
                    exchange.getResponseBody().flush();
                    if (stall.get()) {
                        await();
                    }
                    exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.US_ASCII));
                }
            });
            context.start();
            inference.start();
        }

        private void await() {
            try {
                // Only teardown releases the upstream; natural completion must not masquerade as cancellation.
                release.await();
            } catch (final InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            release.countDown();
            context.stop(0);
            inference.stop(0);
            executor.shutdownNow();
        }
    }
}
