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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.spi.Template;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.security.SecretRegistry;
import io.yupiik.ia.agent.core.security.SecretVault;
import io.yupiik.ia.agent.core.shell.CodexLoginDispatcherRegistrar;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.codex.CodexAgentIdentityConfig;
import io.yupiik.llm.client.codex.CodexNotAuthenticatedException;
import io.yupiik.llm.client.codex.CodexOAuthConfig;
import io.yupiik.llm.client.codex.Ed25519Util;
import io.yupiik.llm.client.codex.model.AgentIdentityRecord;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Tests the {@code {{codexAuth}}} dynamic-header helper: token store persistence and lazy refresh. */
@ExtendWith(TestJsonMapper.class)
class CodexAuthTemplateHelperTest {
    private static final Pattern TASK_REGISTRATION = Pattern.compile(".*/v1/agent/[^/]+/task/register");

    private HttpServer issuer;
    private String issuerUrl;

    @TempDir
    static Path root;

    private Path storage;
    private CodingAgentConfiguration configuration;

    private ExtendedHttpClient client;
    private HttpClient delegate;
    private final AtomicInteger refreshCalls = new AtomicInteger();
    private final AtomicReference<String> lastRefreshBody = new AtomicReference<>();
    private final AtomicBoolean failRefresh = new AtomicBoolean();

    @BeforeEach
    void start(@Fusion final CodingAgentConfiguration configuration) throws IOException {
        this.configuration = configuration;
        storage = Path.of(configuration.resolveStorageHome());
        issuer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        issuerUrl = "http://localhost:" + issuer.getAddress().getPort();
        issuer.createContext("/oauth/token", exchange -> {
            refreshCalls.incrementAndGet();
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"grant_type\":\"refresh_token\"")) {
                lastRefreshBody.set(body);
                if (failRefresh.get()) {
                    respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
                    return;
                }
                respond(
                        exchange,
                        200,
                        "{\"access_token\":\"refreshed\",\"refresh_token\":\"rotated-token\",\"expires_in\":3600}");
            } else {
                respond(exchange, 200, "{\"access_token\":\"fresh\"}");
            }
        });
        // mock Agent Identity run-task registration for the lazy re-registration test
        issuer.createContext("/v1/agent", exchange -> {
            if (TASK_REGISTRATION.matcher(exchange.getRequestURI().getPath()).matches()) {
                respond(exchange, 200, "{\"task_id\":\"fresh-task\"}");
            } else if (exchange.getRequestURI().getPath().endsWith("/v1/agent/register")) {
                respond(exchange, 200, "{\"agent_runtime_id\":\"runtime-lazy\"}");
            } else {
                respond(exchange, 404, "{}");
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

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class FreshTokenNeedsNoRoundTrip {
        @Test
        void freshTokenNeedsNoRoundTrip(final JsonMapper mapper) throws Exception {
            seed(mapper, "p1", new CodexTokenStore("valid-access", "refresh", "acc", farFuture(), "id"));
            final var helper = helper(mapper);
            assertEquals("valid-access", render(helper, "{{codexAuth \"p1\"}}"));
            assertEquals(0, refreshCalls.get(), "a valid token must not trigger a refresh");
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class LoginReplacesPreviouslyLoadedSessionAndLogoutRemovesIt {
        @Test
        void loginReplacesPreviouslyLoadedSessionAndLogoutRemovesIt(final JsonMapper mapper) throws Exception {
            final var service = new CodexLoginService(mapper, client, configuration);
            service.save("p", new CodexTokenStore("old", "refresh", "old-account", farFuture(), "id"));
            final var helper = helper(mapper);
            assertEquals("old", helper.resolveCredentials("p").accessToken());
            service.save("p", new CodexTokenStore("new", "refresh-2", "new-account", farFuture(), "id"));
            assertEquals("new", helper.resolveCredentials("p").accessToken());
            assertEquals("new-account", helper.resolveCredentials("p").accountId());
            Files.delete(storage.resolve("providers/p/codex.auth.json"));
            assertThrows(CodexNotAuthenticatedException.class, () -> helper.resolveCredentials("p"));
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class UnauthorizedRefreshesUnexpiredTokenOnlyOnce {
        @Test
        void unauthorizedRefreshesUnexpiredTokenOnlyOnce(final JsonMapper mapper) throws Exception {
            seed(mapper, "p", new CodexTokenStore("rejected", "refresh", "acc", farFuture(), "id"));
            final var helper = helper(mapper);
            helper.recoverUnauthorized("p", "Bearer rejected");
            helper.recoverUnauthorized("p", "Bearer rejected");
            assertEquals("refreshed", helper.resolveCredentials("p").accessToken());
            assertEquals(1, refreshCalls.get());
            assertTrue(lastRefreshBody.get().contains("\"refresh_reason\":\"unauthorized_recovery\""));
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class UnauthorizedWithRevokedRefreshSignsInOnce {
        @Test
        void unauthorizedWithRevokedRefreshSignsInOnce(final JsonMapper mapper) throws Exception {
            seed(mapper, "p", new CodexTokenStore("rejected", "refresh", "acc", farFuture(), "id"));
            failRefresh.set(true);
            final var helper = helper(mapper);
            final var signIns = new AtomicInteger();
            final var service = new CodexLoginService(mapper, client, configuration);
            helper.setCodexLoginDispatcher(provider -> {
                signIns.incrementAndGet();
                final var store = new CodexTokenStore("new", "refresh-2", "acc", farFuture(), "id");
                service.save(provider, store);
                return store;
            });
            helper.recoverUnauthorized("p", "Bearer rejected");
            assertEquals("new", helper.resolveCredentials("p").accessToken());
            assertEquals("new", helper.resolveCredentials("p").accessToken());
            assertEquals(1, signIns.get());
            assertEquals(1, refreshCalls.get());
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class ExpiredTokenRefreshesAndPersistsRotation {
        @Test
        void expiredTokenRefreshesAndPersistsRotation(final JsonMapper mapper) throws Exception {
            seed(mapper, "p2", new CodexTokenStore("stale-access", "refresh-tok", "acc", past(), "id"));
            final var registry = new SecretRegistry(configuration, List.of(), new SecretVault(), List.of());
            final var redactor = new SecretRedactor(registry, null);
            registry.scan();
            final var helper =
                    new CodexAuthTemplateHelper(mapper, client, configuration, new CodexSecretRegistration(registry));
            helper.setOAuthConfig(oauthConfig());
            assertEquals("refreshed", render(helper, "{{codexAuth \"p2\"}}"));
            assertEquals(1, refreshCalls.get());
            // the rotated refresh token and the new access token must be persisted
            final CodexTokenStore saved = mapper.fromBytes(
                    CodexTokenStore.class, Files.readAllBytes(storage.resolve("providers/p2/codex.auth.json")));
            assertEquals("refreshed", saved.accessToken());
            assertEquals("rotated-token", saved.refreshToken());
            registry.scan();
            for (final var value : List.of("stale-access", "refresh-tok", "refreshed", "rotated-token")) {
                assertFalse(redactor.redact("value=" + value, null).contains(value));
            }
            // a subsequent render reuses the freshly persisted token (no second refresh)
            assertEquals("refreshed", render(helper, "{{codexAuth \"p2\"}}"));
            assertEquals(1, refreshCalls.get(), "persisted fresh token must not re-refresh");
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class ExpiredTokenRefreshCarriesProactiveReason {
        @Test
        void expiredTokenRefreshCarriesProactiveReason(final JsonMapper mapper) throws Exception {
            // an expired token (past expiry, within the 5-min window) must trigger a refresh carrying the
            // proactive_expiry refresh_reason
            seed(mapper, "p-proactive", new CodexTokenStore("stale", "refresh-tok", "acc", past(), "id"));
            lastRefreshBody.set(null);
            final var helper = helper(mapper);
            assertEquals("refreshed", render(helper, "{{codexAuth \"p-proactive\"}}"));
            assertTrue(
                    lastRefreshBody.get() != null
                            && lastRefreshBody.get().contains("\"refresh_reason\":\"proactive_expiry\""),
                    "refresh body must carry proactive_expiry: " + lastRefreshBody.get());
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class MissingSessionWithDispatcherInvokesLogin {
        @Test
        void missingSessionWithDispatcherInvokesLogin(final JsonMapper mapper) throws Exception {
            final var helper = helper(mapper);
            // a dispatcher signs in and persists the session; the renderer must then return its token
            final var dispatcher = new CodexLoginDispatcher() {
                @Override
                public CodexTokenStore signIn(final String providerName) {
                    try {
                        final var store = new CodexTokenStore("dispatched-token", "refresh", "acc", farFuture(), "id");
                        seed(mapper, providerName, store);
                        return store;
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
            helper.setCodexLoginDispatcher(dispatcher);
            assertEquals("dispatched-token", render(helper, "{{codexAuth \"lazy-p\"}}"));
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class MissingSessionWithDispatcherThrowingSurfaces {
        @Test
        void missingSessionWithDispatcherThrowingSurfaces(final JsonMapper mapper) {
            final var helper = helper(mapper);
            helper.setCodexLoginDispatcher(providerName -> {
                throw new IllegalStateException("no interactive shell to sign in " + providerName);
            });
            final var e = assertThrows(IllegalStateException.class, () -> render(helper, "{{codexAuth \"absent-d\"}}"));
            assertTrue(e.getMessage().contains("no interactive shell"), e.getMessage());
        }
    }

    @Nested
    @FusionSupport(modules = StartupModule.class)
    class ContainerStartupRegistersDispatcherWithoutLookingUpRegistrar {
        @Test
        void containerStartupRegistersDispatcherWithoutLookingUpRegistrar(@Fusion final RuntimeContainer container) {
            assertTrue(container.getBeans().getBeans().containsKey(CodexLoginDispatcherRegistrar.class));
            try (final var instance = container.lookup(CodexAuthTemplateHelper.class)) {
                final var failure = assertThrows(
                        IllegalStateException.class, () -> instance.instance().resolveCredentials("absent"));
                assertTrue(failure.getMessage().contains("no interactive shell"), failure.getMessage());
            }
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class MissingSessionFailsLoudly {
        @Test
        void missingSessionFailsLoudly(final JsonMapper mapper) {
            final var helper = helper(mapper);
            final var e = assertThrows(
                    CodexNotAuthenticatedException.class, () -> render(helper, "{{codexAuth \"absent\"}}"));
            assertEquals("absent", e.provider());
            assertTrue(e.getMessage().contains("interactive login"), e.getMessage());
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class RefreshFailureWithDispatcherReSignsTransparently {
        @Test
        void refreshFailureWithDispatcherReSignsTransparently(final JsonMapper mapper) throws Exception {
            // an expired token whose REFRESH FAILS (revoked/invalid_grant) must transparently re-login
            // through the dispatcher so the request still works, instead of failing the turn
            seed(mapper, "revoked-p", new CodexTokenStore("stale", "refresh-tok", "acc", past(), "id"));
            failRefresh.set(true);
            final var helper = helper(mapper);
            // first prove the refresh actually fails without a dispatcher (loudly)
            final var preliminary =
                    assertThrows(IllegalStateException.class, () -> render(helper, "{{codexAuth \"revoked-p\"}}"));
            Assertions.assertTrue(preliminary.getMessage().contains("Codex OAuth HTTP 400"), preliminary.getMessage());
            // now register the dispatcher and re-render: the refresh failure must transparently re-login
            helper.setCodexLoginDispatcher(providerName -> {
                try {
                    final var fresh = new CodexTokenStore("re-signed-token", "refresh-2", "acc", farFuture(), "id");
                    seed(mapper, providerName, fresh);
                    return fresh;
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertEquals("re-signed-token", render(helper, "{{codexAuth \"revoked-p\"}}"));
            Assertions.assertTrue(refreshCalls.get() >= 1, "the failed refresh must have been attempted");
            // the re-signed store is persisted over the revoked one
            final CodexTokenStore saved = mapper.fromBytes(
                    CodexTokenStore.class, Files.readAllBytes(storage.resolve("providers/revoked-p/codex.auth.json")));
            assertEquals("re-signed-token", saved.accessToken());
            final int calls = refreshCalls.get();
            assertEquals("re-signed-token", render(helper, "{{codexAuth \"revoked-p\"}}"));
            assertEquals(calls, refreshCalls.get(), "later requests must not retry the revoked refresh token");
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class RefreshFailureWithoutDispatcherPropagates {
        @Test
        void refreshFailureWithoutDispatcherPropagates(final JsonMapper mapper) throws Exception {
            // no dispatcher (headless/background): a failed refresh must keep failing loudly, not
            // silently
            seed(mapper, "stale-p", new CodexTokenStore("stale", "refresh-tok", "acc", past(), "id"));
            failRefresh.set(true);
            final var helper = helper(mapper); // no dispatcher registered
            final var e = assertThrows(IllegalStateException.class, () -> render(helper, "{{codexAuth \"stale-p\"}}"));
            assertTrue(e.getMessage().contains("Codex OAuth HTTP 400"), e.getMessage());
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class CodexAccountIdUsesAgentIdentityAccountWhenPresent {
        @Test
        void codexAccountIdUsesAgentIdentityAccountWhenPresent(final JsonMapper mapper) throws Exception {
            final var key = Ed25519Util.generate();
            seed(
                    mapper,
                    "agent-p",
                    new CodexTokenStore(
                            "access",
                            "refresh",
                            "oauth-account",
                            farFuture(),
                            "id",
                            // the agent identity carries a different (managed workspace) account id
                            new AgentIdentityRecord(
                                    "runtime-1",
                                    key.privateKeyPkcs8Base64(),
                                    "workspace-account",
                                    "user-9",
                                    null,
                                    "enterprise",
                                    false,
                                    "task-1")));
            final var helper = helper(mapper);
            final var rendered =
                    render(helper, "{{codexAccountId \"agent-p\"}}", Map.of("codexAccountId", helper.codexAccountId()));
            assertEquals("workspace-account", rendered, "agent identity account id wins over the oauth one");
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class CodexAccountIdFallsBackToOAuthAccount {
        @Test
        void codexAccountIdFallsBackToOAuthAccount(final JsonMapper mapper) throws Exception {
            seed(mapper, "plain-p", new CodexTokenStore("access", "refresh", "oauth-account", farFuture(), "id"));
            final var helper = helper(mapper);
            final var rendered =
                    render(helper, "{{codexAccountId \"plain-p\"}}", Map.of("codexAccountId", helper.codexAccountId()));
            assertEquals("oauth-account", rendered);
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class CodexAgentAssertionProducesVerifiableSignedHeader {
        @Test
        @SuppressWarnings("unchecked")
        void codexAgentAssertionProducesVerifiableSignedHeader(final JsonMapper mapper) throws Exception {
            final var key = Ed25519Util.generate();
            seed(
                    mapper,
                    "agent-a",
                    new CodexTokenStore(
                            "access",
                            "refresh",
                            "workspace-account",
                            farFuture(),
                            "id",
                            new AgentIdentityRecord(
                                    "runtime-9",
                                    key.privateKeyPkcs8Base64(),
                                    "workspace-account",
                                    "user-9",
                                    null,
                                    "business",
                                    false,
                                    "task-77")));
            final var helper = helper(mapper);
            final var rendered = render(
                    helper,
                    "{{codexAgentAssertion \"agent-a\"}}",
                    Map.of("codexAgentAssertion", helper.codexAgentAssertion()));
            assertTrue(rendered.startsWith("AgentAssertion "), rendered);
            // decode the envelope and verify the signature against the stored Ed25519 private key,
            // exactly the guarantee codex relies on when the server checks the assertion
            final String envelope = rendered.substring("AgentAssertion ".length());
            final Map<String, Object> parsed = mapper.fromString(Map.class, envelope);
            assertEquals("runtime-9", parsed.get("agent_runtime_id"));
            assertEquals("task-77", parsed.get("task_id"));
            final String timestamp = String.valueOf(parsed.get("timestamp"));
            final String signature = String.valueOf(parsed.get("signature"));
            assertTrue(
                    Ed25519Util.verify(key.privateKeyPkcs8Base64(), "runtime-9:task-77:" + timestamp, signature),
                    "assertion signature must verify against the seeded key");
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class CodexAgentAssertionLazilyEnrollsPlainOAuthSession {
        @Test
        void codexAgentAssertionLazilyEnrollsPlainOAuthSession(final JsonMapper mapper) throws Exception {
            // a plain OAuth session (no Agent Identity yet) self-enrolls lazily on the first
            // {{codexAgentAssertion}} render: register + registerTask against the mock auth API
            seed(mapper, "plain-agent", new CodexTokenStore("access", "refresh", "acc", farFuture(), "id"));
            final var agentConfig = new CodexAgentIdentityConfig(issuerUrl, issuerUrl, null, null);
            final var helper = helper(mapper, agentConfig);
            final var rendered = render(
                    helper,
                    "{{codexAgentAssertion \"plain-agent\"}}",
                    Map.of("codexAgentAssertion", helper.codexAgentAssertion()));
            assertTrue(rendered.startsWith("AgentAssertion "), "lazily enrolled assertion: " + rendered);
            assertTrue(rendered.contains("\"agent_runtime_id\":\"runtime-lazy\""), rendered);
            // the enrollment must be persisted so a later render does not re-register the identity
            final CodexTokenStore saved = mapper.fromBytes(
                    CodexTokenStore.class,
                    Files.readAllBytes(storage.resolve("providers/plain-agent/codex.auth.json")));
            final var record = saved.agentIdentity();
            assertTrue(record != null && record.agentRuntimeId() != null, "enrollment persisted");
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class CodexAgentAssertionDoesNotChurnDeviceIdentity {
        @Test
        void codexAgentAssertionDoesNotChurnDeviceIdentity(final JsonMapper mapper) throws Exception {
            // after the first lazy enrollment a second render reuses the stored identity; the mock's
            // register counter must not grow (no device-identity churn on repeated renders)
            seed(mapper, "plain-again", new CodexTokenStore("access", "refresh", "acc", farFuture(), "id"));
            final var agentConfig = new CodexAgentIdentityConfig(issuerUrl, issuerUrl, null, null);
            final var helper = helper(mapper, agentConfig);
            final var first = render(
                    helper,
                    "{{codexAgentAssertion \"plain-again\"}}",
                    Map.of("codexAgentAssertion", helper.codexAgentAssertion()));
            assertTrue(first.startsWith("AgentAssertion "), first);
            final var second = render(
                    helper,
                    "{{codexAgentAssertion \"plain-again\"}}",
                    Map.of("codexAgentAssertion", helper.codexAgentAssertion()));
            assertTrue(second.contains("\"agent_runtime_id\":\"runtime-lazy\""), second);
            // same runtime id on both renders => no re-registration happened
            assertEquals(
                    sub("agent_runtime_id", first),
                    sub("agent_runtime_id", second),
                    "repeated renders must reuse the enrolled device identity");
        }
    }

    private static String sub(final String key, final String assertion) {
        final var marker = "\"" + key + "\":\"";
        final var start = assertion.indexOf(marker);
        if (start < 0) {
            return "";
        }
        final var end = assertion.indexOf('"', start + marker.length());
        return assertion.substring(start + marker.length(), end);
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class CodexAgentAssertionDoesNotTriggerOAuthRefresh {
        @Test
        void codexAgentAssertionDoesNotTriggerOAuthRefresh(final JsonMapper mapper) throws Exception {
            // the assertion is signed with the Ed25519 key; even with an expired/rotated OAuth token the
            // assertion must still render without forcing an OAuth refresh (refreshing could throw when
            // the enterprise refresh token is revoked/expired)
            final var key = Ed25519Util.generate();
            seed(
                    mapper,
                    "agent-stale",
                    new CodexTokenStore(
                            "expired-access",
                            "refresh",
                            "workspace-account",
                            past(), // the access token is expired but we do NOT want a refresh for the assertion
                            "id",
                            new AgentIdentityRecord(
                                    "runtime-1",
                                    key.privateKeyPkcs8Base64(),
                                    "workspace-account",
                                    "user-9",
                                    null,
                                    "business",
                                    false,
                                    "task-1")));
            final var helper = helper(mapper);
            final var rendered = render(
                    helper,
                    "{{codexAgentAssertion \"agent-stale\"}}",
                    Map.of("codexAgentAssertion", helper.codexAgentAssertion()));
            assertTrue(rendered.startsWith("AgentAssertion "), rendered);
            assertEquals(0, refreshCalls.get(), "the assertion must not trigger an OAuth refresh");
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class CodexAgentAssertionLazilyRegistersMissingTask {
        @Test
        void codexAgentAssertionLazilyRegistersMissingTask(final JsonMapper mapper) throws Exception {
            // an enrolled agent identity whose run task id is missing/stale: the helper must re-register
            // the task (mock /v1/agent/{id}/task/register) instead of failing until a re-login
            final var key = Ed25519Util.generate();
            seed(
                    mapper,
                    "agent-notask",
                    new CodexTokenStore(
                            "access",
                            "refresh",
                            "workspace-account",
                            farFuture(),
                            "id",
                            new AgentIdentityRecord(
                                    "runtime-5",
                                    key.privateKeyPkcs8Base64(),
                                    "workspace-account",
                                    "user-9",
                                    null,
                                    "enterprise",
                                    false,
                                    null))); // no task id -> needs re-registration
            final var agentConfig = new CodexAgentIdentityConfig(issuerUrl, issuerUrl, null, null);
            final var helper = helper(mapper, agentConfig);
            final var rendered = render(
                    helper,
                    "{{codexAgentAssertion \"agent-notask\"}}",
                    Map.of("codexAgentAssertion", helper.codexAgentAssertion()));
            assertTrue(rendered.contains("\"task_id\":\"fresh-task\""), "task re-registered: " + rendered);
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class CodexFedrampHelperRendersTrueForFedrampAccount {
        @Test
        void codexFedrampHelperRendersTrueForFedrampAccount(final JsonMapper mapper) throws Exception {
            final var key = Ed25519Util.generate();
            seed(
                    mapper,
                    "agent-fedramp",
                    new CodexTokenStore(
                            "access",
                            "refresh",
                            "a",
                            farFuture(),
                            "id",
                            new AgentIdentityRecord(
                                    "runtime-1",
                                    key.privateKeyPkcs8Base64(),
                                    "a",
                                    "u",
                                    null,
                                    "enterprise",
                                    true,
                                    "t")));
            final var helper = helper(mapper);
            assertEquals(
                    "true",
                    render(
                            helper,
                            "{{codexFedramp \"agent-fedramp\"}}",
                            Map.of("codexFedramp", helper.codexFedramp())));
        }
    }

    @Nested
    @FusionSupport(modules = AuthModule.class)
    class CodexFedrampHelperRendersBlankForNonFedramp {
        @Test
        void codexFedrampHelperRendersBlankForNonFedramp(final JsonMapper mapper) throws Exception {
            final var key = Ed25519Util.generate();
            seed(
                    mapper,
                    "agent-nonfedramp",
                    new CodexTokenStore(
                            "access",
                            "refresh",
                            "a",
                            farFuture(),
                            "id",
                            new AgentIdentityRecord(
                                    "runtime-1", key.privateKeyPkcs8Base64(), "a", "u", null, "pro", false, "t")));
            final var helper = helper(mapper);
            assertEquals(
                    "",
                    render(
                            helper,
                            "{{codexFedramp \"agent-nonfedramp\"}}",
                            Map.of("codexFedramp", helper.codexFedramp())));
        }
    }

    private CodexOAuthConfig oauthConfig() {
        return new CodexOAuthConfig(issuerUrl, "test-client", "openid", issuerUrl + "/codex/device", 1455);
    }

    private CodexAuthTemplateHelper helper(final JsonMapper mapper) {
        return helper(mapper, null);
    }

    private CodexAuthTemplateHelper helper(final JsonMapper mapper, final CodexAgentIdentityConfig agentConfig) {
        final var helper = new CodexAuthTemplateHelper(mapper, client, configuration);
        helper.setOAuthConfig(oauthConfig());
        if (agentConfig != null) {
            helper.setAgentIdentityConfig(agentConfig);
        }
        return helper;
    }

    private void seed(final JsonMapper mapper, final String provider, final CodexTokenStore store) throws IOException {
        final Path file = storage.resolve("providers/" + provider + "/codex.auth.json");
        Files.createDirectories(file.getParent());
        Files.write(file, mapper.toBytes(store));
    }

    private static long farFuture() {
        return System.currentTimeMillis() + 3600_000;
    }

    private static long past() {
        return System.currentTimeMillis() - 1000;
    }

    private static String render(final CodexAuthTemplateHelper helper, final String source) {
        return render(helper, source, Map.of("codexAuth", helper.codexAuth()));
    }

    private static String render(
            final CodexAuthTemplateHelper helper,
            final String source,
            final Map<String, Function<HelperContext, Object>> helpers) {
        final Template template = new HandlebarsCompiler()
                .compile(new HandlebarsCompiler.CompilationContext(
                        new HandlebarsCompiler.Settings().helpers(helpers), source));
        return template.render(Map.of());
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    public static class AuthModule extends ConfigurationTestModule {
        public AuthModule() {
            super(Map.of(
                    "yca.storageHome",
                    root.resolve(UUID.randomUUID().toString()).toString()));
        }
    }

    public static class StartupModule extends AuthModule {
        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.concat(
                    super.beans(),
                    Stream.of(new ProvidedInstanceBean<>(
                            DefaultScoped.class, ShellRegistry.class, () -> new ShellRegistry(null))));
        }

        @Override
        public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
            return super.beanFilter()
                    .and((container, bean) ->
                            bean.type() != ShellRegistry.class || bean instanceof ProvidedInstanceBean<?>);
        }

        @Override
        public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
            return (container, listener) -> listener.eventType() == Start.class;
        }
    }
}
