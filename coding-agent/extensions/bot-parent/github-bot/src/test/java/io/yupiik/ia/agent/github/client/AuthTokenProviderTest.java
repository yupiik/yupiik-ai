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
package io.yupiik.ia.agent.github.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.service.GithubServices;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthTokenProviderTest {
    @TempDir
    static Path appHome;

    private static HttpServer patServer;
    private static HttpServer appServer;

    // Arbitrary transport fixtures, not a GitHub domain response schema.
    private static final Type RESPONSE = new Types.ParameterizedTypeImpl(Map.class, String.class, Object.class);
    // Share only the key material; providers, signatures, clocks, and servers remain test-local.
    private static final KeyPair KEY_PAIR = newKeyPair();

    @Nested
    @FusionSupport(modules = PatSource.class)
    class HostPat {
        @Fusion
        private GithubBotConfiguration injectedConfiguration;

        @Test
        void hostPatUsesConfiguredCredentialsLazilyAndObservesRotation() throws Exception {
            final var authorization = new AtomicReference<String>();
            final var reads = new AtomicInteger();
            final var values = new HashMap<String, String>();
            final var server = patServer;
            server.createContext("/probe", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                final var body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            values.put("github.api", "http://127.0.0.1:" + server.getAddress().getPort());
            values.put("github.auth.type", "pat");
            values.put("github.auth.tokenEnv", "YCA_CONFIG_PAT");
            values.put("github.requestIntervalMillis", "0");
            final var conf = injectedConfiguration;
            final Configuration config = name -> {
                reads.incrementAndGet();
                return Configuration.of(values).get(name);
            };
            try (final var json = BotJsonMapper.create(ignored -> Optional.empty());
                    final var client = new GithubServices().client(conf, json, BotTestConfiguration.CLOCK, config)) {
                assertEquals(0, reads.get());
                values.put("YCA_CONFIG_PAT", "configured-pat-first");
                client.get("/probe", RESPONSE);
                assertEquals("Bearer configured-pat-first", authorization.get());
                values.put("YCA_CONFIG_PAT", "configured-pat-rotated");
                client.get("/probe", RESPONSE);
                assertEquals("Bearer configured-pat-rotated", authorization.get());
                assertTrue(reads.get() >= 2);
            } finally {
                server.stop(0);
            }
        }
    }

    private static KeyPair newKeyPair() {
        try {
            final var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (final GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    void refreshesRejectedInstallationTokenForGetButNeverRetriesPost() throws Exception {
        final var issued = new AtomicInteger();
        final var reads = new AtomicInteger();
        final var writes = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app/installations/42/access_tokens", exchange -> {
            final var body = ("{\"token\":\"token-" + issued.incrementAndGet() + "\",\"expires_at\":\""
                            + BotTestConfiguration.CLOCK.instant().plusSeconds(3600) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/resource", exchange -> {
            exchange.getRequestBody().readAllBytes();
            final int status;
            if ("POST".equals(exchange.getRequestMethod())) {
                writes.incrementAndGet();
                status = 401;
            } else {
                reads.incrementAndGet();
                status = "Bearer token-2".equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401;
            }
            final var body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        final var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var provider = AuthTokenProvider.app(
                        base, json, "app", 42, KEY_PAIR.getPrivate(), BotTestConfiguration.CLOCK);
                final var client = new GitHubClient(base, json, provider, BotTestConfiguration.CLOCK)) {
            client.get("/resource", RESPONSE);
            assertEquals(2, issued.get());
            assertEquals(2, reads.get());
            assertThrows(
                    GitHubClient.HttpException.class,
                    () -> client.post("/resource", new GitHubClient.CommentRequest("resource"), RESPONSE));
            assertEquals(1, writes.get());
            assertEquals(2, issued.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void patRotates() {
        final var value = new AtomicReference<>("first");
        try (final var provider = AuthTokenProvider.pat(value::get)) {
            assertEquals("first", provider.get());
            value.set("second");
            assertEquals("second", provider.get());
            value.set("");
            assertThrows(IllegalStateException.class, provider::get);
        }
    }

    @Test
    void appSignsAndCachesUntilActualExpiry() throws Exception {
        for (final var source : List.of("explicit", "repository", "organization")) {
            final var now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
            final var clock = new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(final ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now.get();
                }
            };
            final var calls = new AtomicInteger();
            final var lookups = new AtomicInteger();
            final var jwt = new AtomicReference<String>();
            final var lookupJwt = new AtomicReference<String>();
            final var lookupMethod = new AtomicReference<String>();
            final var tokenMethod = new AtomicReference<String>();
            final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    source.equals("repository") ? "/repos/acme/project/installation" : "/orgs/acme/installation",
                    exchange -> {
                        lookups.incrementAndGet();
                        lookupJwt.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        lookupMethod.set(exchange.getRequestMethod());
                        final var body = "{\"id\":42}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.createContext("/app/installations/42/access_tokens", exchange -> {
                jwt.set(exchange.getRequestHeaders().getFirst("Authorization").substring("Bearer ".length()));
                tokenMethod.set(exchange.getRequestMethod());
                final int count = calls.incrementAndGet();
                final var body = ("{\"token\":\"installation-" + count + "\",\"expires_at\":\""
                                + now.get().plusSeconds(120) + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            final var base =
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            try (final var json = BotJsonMapper.create(key -> Optional.empty());
                    final var provider =
                            switch (source) {
                                case "repository" ->
                                    AuthTokenProvider.appForRepository(
                                            base, json, "app-id", "acme/project", KEY_PAIR.getPrivate(), clock);
                                case "organization" ->
                                    AuthTokenProvider.appForOrganization(
                                            base, json, "app-id", "acme", KEY_PAIR.getPrivate(), clock);
                                default ->
                                    AuthTokenProvider.app(base, json, "app-id", 42, KEY_PAIR.getPrivate(), clock);
                            }) {
                assertEquals(0, lookups.get());
                assertEquals("installation-1", provider.get());
                assertEquals("installation-1", provider.get());
                assertEquals(1, calls.get());
                assertEquals("POST", tokenMethod.get());
                assertEquals(
                        new AuthTokenProvider.JwtHeader("RS256", "JWT"),
                        json.fromString(
                                AuthTokenProvider.JwtHeader.class,
                                new String(
                                        Base64.getUrlDecoder().decode(jwt.get().split("\\.")[0]),
                                        StandardCharsets.UTF_8)));
                if (!source.equals("explicit")) {
                    assertEquals("GET", lookupMethod.get());
                    assertEquals("Bearer " + jwt.get(), lookupJwt.get());
                }
                final var parts = jwt.get().split("\\.");
                final var verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(KEY_PAIR.getPublic());
                verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
                final var payload = json.fromString(
                        AuthTokenProvider.JwtClaims.class,
                        new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
                assertEquals("app-id", payload.iss());
                assertEquals(now.get().minusSeconds(60).getEpochSecond(), payload.iat());
                assertEquals(now.get().plusSeconds(540).getEpochSecond(), payload.exp());
                now.set(now.get().plusSeconds(61));
                assertEquals("installation-2", provider.get());
                assertEquals(2, calls.get());
                assertEquals(source.equals("explicit") ? 0 : 1, lookups.get());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void lookupFailsClosedForInvalidIdsAndHttpErrors() throws Exception {
        final var key = KEY_PAIR.getPrivate();
        final var response = new AtomicReference<>("{}");
        final var status = new AtomicInteger(200);
        final var tokens = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orgs/acme/installation", exchange -> {
            final var body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/app/installations/", exchange -> {
            tokens.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(name -> Optional.empty())) {
            final var base =
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            for (final var body : List.of(
                    "{}",
                    "[]",
                    "{\"id\":null}",
                    "{\"id\":\"42\"}",
                    "{\"id\":0}",
                    "{\"id\":-1}",
                    "{\"id\":1.5}",
                    "{\"id\":9223372036854775808}")) {
                response.set(body);
                try (final var provider = AuthTokenProvider.appForOrganization(
                        base, json, "app-id", "acme", key, BotTestConfiguration.CLOCK)) {
                    final var failure = assertThrows(IllegalStateException.class, provider::get, body);
                    assertTrue(failure.getMessage().contains("positive integer id"));
                }
            }
            for (final var code : List.of(401, 403, 404)) {
                status.set(code);
                try (final var provider = AuthTokenProvider.appForOrganization(
                        base, json, "app-id", "acme", key, BotTestConfiguration.CLOCK)) {
                    assertThrows(GitHubClient.HttpException.class, provider::get);
                }
            }
            assertEquals(0, tokens.get());
        } finally {
            server.stop(0);
        }
    }

    @Nested
    @FusionSupport(modules = AppSource.class)
    class AppInstallations {
        @Fusion
        private GithubBotConfiguration injectedConfiguration;

        @Test
        void runtimeSelectsOnlyUnambiguousInstallationSources() throws Exception {
            final var directory = appHome;
            final var key = KEY_PAIR.getPrivate();
            final var keyFile = directory.resolve("app.pem");
            Files.writeString(
                    keyFile,
                    "-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(key.getEncoded())
                            + "\n-----END " + "PRIVATE KEY-----\n");
            final var lookup = new AtomicReference<String>();
            final var lookups = new AtomicInteger();
            final var server = appServer;
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                final String response;
                if (path.endsWith("/installation")) {
                    lookups.incrementAndGet();
                    lookup.set(path);
                    response = "{\"id\":42}";
                } else if (path.equals("/app/installations/42/access_tokens")) {
                    response = "{\"token\":\"installation\",\"expires_at\":\"2026-01-01T01:00:00Z\"}";
                } else {
                    response = "{}";
                }
                final var body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try (final var json = BotJsonMapper.create(name -> Optional.empty())) {
                final var values = new HashMap<>(Map.of(
                        "github.auth.type",
                        "app",
                        "github.auth.appId",
                        "app-id",
                        "github.auth.privateKeyFile",
                        keyFile.toString(),
                        "github.api",
                        "http://127.0.0.1:" + server.getAddress().getPort()));
                final var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
                for (final var selection : List.of(
                        List.of(" acme/project ", "", "/repos/acme/project/installation"),
                        List.of("", " org:acme ", "/orgs/acme/installation"))) {
                    values.put("github.repositories.explicit", selection.get(0));
                    values.put("github.repositories.discovery", selection.get(1));
                    final var conf =
                            installationConfiguration(injectedConfiguration, selection.get(0), selection.get(1), 0);
                    try (final var runtime = new GithubServices()
                            .client(conf, json, clock, name -> Optional.ofNullable(values.get(name)))) {
                        runtime.get("/probe", RESPONSE);
                        assertEquals(selection.get(2), lookup.get());
                    }
                }
                for (final var selection : List.of(
                        List.of("", ""),
                        List.of("acme/one,acme/two", ""),
                        List.of("acme/*", ""),
                        List.of("acme/proj?ct", ""),
                        List.of("acme/[project]", ""),
                        List.of("acme", ""),
                        List.of("acme/project", "org:acme"),
                        List.of("", "org:acme,org:other"),
                        List.of("", "user:acme"),
                        List.of("", "org:*"),
                        List.of("acme/project,", ""))) {
                    values.put("github.repositories.explicit", selection.get(0));
                    values.put("github.repositories.discovery", selection.get(1));
                    final var conf =
                            installationConfiguration(injectedConfiguration, selection.get(0), selection.get(1), 0);
                    final var failure = assertThrows(
                            IllegalArgumentException.class,
                            () -> new GithubServices()
                                    .client(conf, json, clock, name -> Optional.ofNullable(values.get(name))));
                    assertTrue(failure.getMessage().contains("github.auth.installationId=0"));
                }
                values.put("github.auth.installationId", "42");
                values.put("github.repositories.explicit", "acme/one,acme/two");
                values.put("github.repositories.discovery", "org:acme,org:other");
                final var explicit =
                        installationConfiguration(injectedConfiguration, "acme/one,acme/two", "org:acme,org:other", 42);
                try (final var runtime = new GithubServices()
                        .client(explicit, json, clock, name -> Optional.ofNullable(values.get(name)))) {
                    runtime.get("/probe", RESPONSE);
                }
                assertEquals(2, lookups.get());
                values.put("github.auth.installationId", "-1");
                final var negative =
                        installationConfiguration(injectedConfiguration, "acme/one,acme/two", "org:acme,org:other", -1);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new GithubServices()
                                .client(negative, json, clock, name -> Optional.ofNullable(values.get(name))));
            } finally {
                server.stop(0);
            }
        }
    }

    private static GithubBotConfiguration installationConfiguration(
            final GithubBotConfiguration baseline,
            final String explicit,
            final String discovery,
            final long installationId) {
        final var auth = baseline.auth();
        final var repositories = baseline.repositories();
        return new GithubBotConfiguration(
                baseline.home(),
                baseline.api(),
                baseline.login(),
                baseline.requestIntervalMillis(),
                new GithubBotConfiguration.Auth(
                        auth.type(),
                        auth.tokenEnv(),
                        auth.appId(),
                        installationId,
                        auth.privateKeyFile(),
                        auth.agentTokenEnv()),
                baseline.authorization(),
                new GithubBotConfiguration.Repositories(
                        explicit,
                        discovery,
                        repositories.includes(),
                        repositories.excludes(),
                        repositories.visibility(),
                        repositories.excludeArchived(),
                        repositories.excludeForks()));
    }

    public static class PatSource extends GithubConfigurationTestModule {
        public PatSource() {
            super(settings());
        }

        private static Map<String, String> settings() {
            patServer = newServer();
            return Map.of(
                    "github.api",
                    "http://127.0.0.1:" + patServer.getAddress().getPort(),
                    "github.auth.type",
                    "pat",
                    "github.auth.tokenEnv",
                    "YCA_CONFIG_PAT",
                    "github.requestIntervalMillis",
                    "0");
        }
    }

    public static class AppSource extends GithubConfigurationTestModule {
        public AppSource() {
            super(settings());
        }

        private static Map<String, String> settings() {
            appServer = newServer();
            return Map.of(
                    "github.api",
                    "http://127.0.0.1:" + appServer.getAddress().getPort(),
                    "github.auth.type",
                    "app",
                    "github.auth.appId",
                    "app-id",
                    "github.auth.privateKeyFile",
                    appHome.resolve("app.pem").toString());
        }
    }

    private static HttpServer newServer() {
        try {
            return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
