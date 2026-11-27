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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.SecretRedactionMode;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.security.SecretRegistry;
import io.yupiik.ia.agent.core.security.SecretSessionModes;
import io.yupiik.ia.agent.core.security.SecretVault;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.codex.CodexOAuthConfig;
import io.yupiik.llm.client.codex.model.AgentIdentityRecord;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end {@link CodexLoginService} test against a local mock of {@code auth.openai.com}: verifies
 * the login orchestration - provenance recorded in both flows, the api-key token obtained only in the
 * browser flow, and the session persisted to {@code codex.auth.json}. No real external system is hit.
 */
@ExtendWith(TestJsonMapper.class)
@FusionSupport(modules = CodexLoginServiceTest.LoginModule.class)
class CodexLoginServiceTest {
    private static final Pattern REDIRECT_PORT = Pattern.compile("redirect_uri=[^&]*127\\.0\\.0\\.1%3A(\\d+)[^&]*");
    private static final Pattern STATE = Pattern.compile("state=([^&]+)");

    private HttpServer issuer;
    private String issuerUrl;

    @TempDir
    static Path root;

    private Path storage;
    private CodingAgentConfiguration configuration;

    private ExtendedHttpClient client;
    private HttpClient delegate;
    private final AtomicInteger oauthTokenCalls = new AtomicInteger();

    // an id_token carrying the chatgpt_account_id claim so the account id can be derived
    private static final String ID_TOKEN = "header."
            + Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"https://api.openai.com/auth.chatgpt_account_id\":\"acc-1\"}"
                            .getBytes(StandardCharsets.UTF_8))
            + ".sig";

    @BeforeEach
    void start(@Fusion final CodingAgentConfiguration configuration) throws IOException {
        this.configuration = configuration;
        storage = Path.of(configuration.resolveStorageHome());
        issuer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        issuerUrl = "http://localhost:" + issuer.getAddress().getPort();
        // the device-code endpoints
        issuer.createContext(
                "/api/accounts/deviceauth/usercode",
                exchange -> respond(
                        exchange, 200, "{\"device_auth_id\":\"da-1\",\"user_code\":\"AB12-CD34\",\"interval\":1}"));
        issuer.createContext(
                "/api/accounts/deviceauth/token",
                exchange ->
                        respond(exchange, 200, "{\"authorization_code\":\"thecode\",\"code_verifier\":\"verifier\"}"));
        // the shared token endpoint: dispatch on the grant type
        issuer.createContext("/oauth/token", exchange -> {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            oauthTokenCalls.incrementAndGet();
            if (body.contains("grant_type=refresh_token") || body.contains("\"grant_type\":\"refresh_token\"")) {
                respond(exchange, 200, "{\"access_token\":\"refresh-acc\"}");
            } else if (body.contains("token-exchange") || body.contains("requested_token=openai-api-key")) {
                // the obtain_api_key token-exchange
                respond(exchange, 200, "{\"access_token\":\"openai-api-key-mock\"}");
            } else {
                // the code exchange (browser or device callback)
                respond(
                        exchange,
                        200,
                        "{\"access_token\":\"acc-token\",\"refresh_token\":\"refresh-1\",\"expires_in\":3600,"
                                + "\"id_token\":\"" + ID_TOKEN + "\"}");
            }
        });
        issuer.start();
        delegate = HttpClient.newBuilder().build();
        client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
    }

    @AfterEach
    void stop() {
        if (issuer != null) {
            issuer.stop(0);
            issuer = null;
        }
        if (client != null) {
            client.close();
            delegate.shutdownNow();
            delegate.close();
            delegate = null;
            client = null;
        }
    }

    private CodexLoginService service(final JsonMapper mapper) {
        return new CodexLoginService(mapper, client, configuration);
    }

    public static class LoginModule extends ConfigurationTestModule {
        public LoginModule() {
            super(Map.of(
                    "yca.storageHome",
                    root.resolve(UUID.randomUUID().toString()).toString()));
        }
    }

    private CodexOAuthConfig issuerConfig() {
        return new CodexOAuthConfig(
                issuerUrl,
                "cid-1",
                "openid profile email offline_access api.connectors.read api.connectors.invoke",
                "vurl",
                1460);
    }

    @Test
    void savedSessionsRegisterSecretsAndRetainPreviousValues(final JsonMapper mapper) {
        final var registry = new SecretRegistry(null, List.of(), new SecretVault(), List.of());
        final var modes = new SecretSessionModes();
        modes.set("test", SecretRedactionMode.REDACT);
        final var redactor = new SecretRedactor(registry, modes);
        // Use a temporary storage home, never the real user's credentials.
        final var temporaryService =
                new CodexLoginService(mapper, client, configuration, new CodexSecretRegistration(registry));
        temporaryService.save(
                "test",
                new CodexTokenStore("login-access", "login-refresh", "account-metadata", 1L, "login-id-token")
                        .withApiKey("login-api-key")
                        .withAgentIdentity(new AgentIdentityRecord(
                                "runtime", "private-key-value", "account", "user", null, "plan", false, "task")));
        temporaryService.save(
                "test", new CodexTokenStore("next-access", "next-refresh", "account-metadata", 2L, "next-id-token"));
        registry.scan();
        for (final var value : List.of(
                "login-access",
                "login-refresh",
                "login-id-token",
                "login-api-key",
                "private-key-value",
                "next-access",
                "next-refresh",
                "next-id-token")) {
            assertFalse(redactor.redact("value=" + value, "test").contains(value));
        }
        assertEquals("account-metadata", redactor.redact("account-metadata", "test"));
    }

    @Test
    void deviceLoginRegistersTokensAfterStartup(final JsonMapper mapper) {
        final var registry = new SecretRegistry(null, List.of(), new SecretVault(), List.of());
        final var modes = new SecretSessionModes();
        modes.set("test", SecretRedactionMode.REDACT);
        final var redactor = new SecretRedactor(registry, modes);
        registry.scan();
        final var service = new CodexLoginService(mapper, client, configuration, new CodexSecretRegistration(registry));
        service.signIn("device", true, url -> {}, b -> false, issuerConfig());
        for (final var value : List.of("acc-token", "refresh-1", ID_TOKEN)) {
            assertFalse(redactor.redact("value=" + value, "test").contains(value));
        }
    }

    @Test
    void browserFlowRecordsProvenanceAndApiKey(final JsonMapper mapper) throws Exception {
        final var service = service(mapper);
        final var authorizeUrl = new CompletableFuture<String>();
        // run the browser flow off-thread (it blocks waiting for the callback), then drive the redirect
        final CompletableFuture<CodexTokenStore> result = CompletableFuture.supplyAsync(
                () -> service.signIn("browser-p", false, authorizeUrl::complete, b -> false, issuerConfig()));

        final var url = authorizeUrl.get(5, TimeUnit.SECONDS);
        final var callback = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + portOf(url) + "/auth/callback?state=" + stateOf(url) + "&code=thecode"))
                .GET()
                .build();
        try (final var http = HttpClient.newHttpClient()) {
            http.send(callback, HttpResponse.BodyHandlers.discarding());
        }

        final CodexTokenStore store = result.get(5, TimeUnit.SECONDS);
        assertNotNull(store);
        assertEquals("acc-1", store.accountId());
        // provenance recorded in the browser flow
        assertEquals(issuerUrl, store.issuer());
        assertEquals("cid-1", store.clientId());
        // api key obtained and stored in the browser flow
        assertEquals("openai-api-key-mock", store.openaiApiKey());

        // the session must be persisted and carry the same fields
        final CodexTokenStore saved = mapper.fromBytes(
                CodexTokenStore.class, Files.readAllBytes(storage.resolve("providers/browser-p/codex.auth.json")));
        assertEquals("openai-api-key-mock", saved.openaiApiKey());
        assertEquals(issuerUrl, saved.issuer());
    }

    @Test
    void deviceFlowRecordsProvenanceButNoApiKey(final JsonMapper mapper) throws Exception {
        final var service = service(mapper);
        final CodexTokenStore store = service.signIn("device-p", true, url -> {}, b -> false, issuerConfig());
        assertNotNull(store);
        assertEquals("acc-1", store.accountId());
        // provenance recorded in the device flow
        assertEquals(issuerUrl, store.issuer());
        assertEquals("cid-1", store.clientId());
        // NO api key in the device flow (upstream passes key None there)
        assertNull(store.openaiApiKey());

        final CodexTokenStore saved = mapper.fromBytes(
                CodexTokenStore.class, Files.readAllBytes(storage.resolve("providers/device-p/codex.auth.json")));
        assertEquals(issuerUrl, saved.issuer());
        assertNull(saved.openaiApiKey());
    }

    private static String portOf(final String authorizeUrl) {
        final var uri = URI.create(authorizeUrl);
        final var m = REDIRECT_PORT.matcher(uri.getRawQuery());
        assertTrue(m.find(), "cannot find the port in " + authorizeUrl);
        return m.group(1);
    }

    private static String stateOf(final String authorizeUrl) {
        final var uri = URI.create(authorizeUrl);
        final var m = STATE.matcher(uri.getRawQuery());
        assertTrue(m.find(), "cannot find the state in " + authorizeUrl);
        return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
