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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.llm.client.codex.CodexAgentIdentityClient;
import io.yupiik.llm.client.codex.CodexAgentIdentityConfig;
import io.yupiik.llm.client.codex.CodexOAuthClient;
import io.yupiik.llm.client.codex.CodexOAuthConfig;
import io.yupiik.llm.client.codex.model.AgentIdentityRecord;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Owns the Codex login flow and token persistence, shared by the {@code /codex login} slash command
 * and the frontends' auto-login-on-first-use. Tokens are written to
 * {@code <storageHome>/providers/<provider>/codex.auth.json}, the exact location {@link
 * CodexAuthTemplateHelper} reads for {@code {{codexAuth}}}, so a session created here is picked up
 * automatically on the next request.
 */
@ApplicationScoped
public class CodexLoginService {

    private final JsonMapper mapper;
    private final ExtendedHttpClient httpClient;
    private final Path providersHome;
    private final CodexSecretRegistration secretRegistration;

    public CodexLoginService(
            final JsonMapper mapper,
            final ExtendedHttpClient httpClient,
            final CodingAgentConfiguration configuration) {
        this(mapper, httpClient, configuration, new CodexSecretRegistration(null));
    }

    public CodexLoginService(
            final JsonMapper mapper,
            final ExtendedHttpClient httpClient,
            final CodingAgentConfiguration configuration,
            final CodexSecretRegistration secretRegistration) {
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.secretRegistration = secretRegistration;
        final String home = configuration == null ? null : configuration.resolveStorageHome();
        final String resolvedHome = home == null || home.isBlank() ? "~/.yupiik/coding-agent" : home;
        this.providersHome = Path.of(resolveHome(resolvedHome)).resolve("providers");
    }

    /** Expands a leading {@code ~} to the user home directory; POSIX-style, best effort. */
    private static String resolveHome(final String path) {
        if (path == null || path.isBlank() || !path.startsWith("~/")) {
            return path;
        }
        final String userHome = System.getProperty("user.home");
        return userHome == null || userHome.isBlank() ? path : userHome + path.substring(1);
    }

    /**
     * Runs the interactive ChatGPT/Codex login for a provider and persists the session. The browser
     * PKCE flow is the default; {@code device} opts into the device-code flow (the CLI's
     * {@code --device-auth} path). Blocks until the user authorizes (or {@code cancelled} returns
     * true).
     *
     * @param provider the provider id to sign in.
     * @param device true to use the device-code flow instead of the browser flow.
     * @param urlSink receives the authorize (or verification) URL to open; the frontend decides how to
     *     surface it (print, TUI modal, or open in a web tab).
     * @param cancelled cancellation probe.
     * @return the authenticated session.
     */
    public CodexTokenStore signIn(
            final String provider,
            final boolean device,
            final Consumer<String> urlSink,
            final Predicate<Boolean> cancelled) {
        return signIn(provider, device, urlSink, cancelled, CodexOAuthConfig.DEFAULT);
    }

    /**
     * Package-private variant with a tunable OAuth configuration, mainly exposed for tests that point
     * at a mock issuer instead of {@link CodexOAuthConfig#DEFAULT}. {@link #signIn(String, boolean,
     * Consumer, Predicate)} delegates here with the default configuration; the login logic, provenance
     * recording and the browser-only api-key exchange are otherwise identical.
     *
     * @param provider the provider id to sign in.
     * @param device true to use the device-code flow instead of the browser flow.
     * @param urlSink receives the authorize (or verification) URL to open.
     * @param cancelled cancellation probe.
     * @param config the tunable OAuth settings (issuer, client id, scopes, callback port).
     * @return the authenticated session.
     */
    CodexTokenStore signIn(
            final String provider,
            final boolean device,
            final Consumer<String> urlSink,
            final Predicate<Boolean> cancelled,
            final CodexOAuthConfig config) {
        final var store = authenticate(device, urlSink, null, cancelled, config);
        save(provider, store, cancelled);
        return store;
    }

    private CodexTokenStore authenticate(
            final boolean device,
            final Consumer<String> urlSink,
            final Consumer<DeviceChallenge> deviceSink,
            final Predicate<Boolean> cancelled,
            final CodexOAuthConfig config) {
        checkCancelled(cancelled);
        final var oauth = new CodexOAuthClient(mapper, httpClient, config);
        CodexTokenStore store;
        if (device) {
            final var challenge = oauth.deviceCode();
            checkCancelled(cancelled);
            if (deviceSink != null) {
                deviceSink.accept(new DeviceChallenge(challenge.verificationUrl(), challenge.userCode()));
            }
            if (urlSink != null) {
                urlSink.accept(challenge.prompt());
            }
            store = oauth.poll(challenge, cancelled);
        } else {
            store = oauth.browserFlow(urlSink, cancelled);
        }
        checkCancelled(cancelled);
        // record the OAuth authority (provenance) at login, in BOTH flows (mirrors upstream
        // persist_tokens_async which always writes issuer/client_id; the device flow only omits the
        // api key below). Do this BEFORE obtainApiKey so the id token is read from the annotated store.
        store = store.withProvenance(config.issuer(), config.clientId());
        if (!device) {
            // exchange the id token for an API-key token (upstream obtain_api_key); only the browser
            // flow does this (the device flow passes no key upstream), and the failure is tolerated.
            String apiKey;
            try {
                apiKey = oauth.obtainApiKey(store.idToken());
            } catch (final RuntimeException ignored) {
                // non-fatal: the login still succeeds without the api-key token (upstream .ok())
                apiKey = null;
            }
            checkCancelled(cancelled);
            if (apiKey != null && !apiKey.isBlank()) {
                store = store.withApiKey(apiKey);
            }
        }
        checkCancelled(cancelled);
        return store;
    }

    /**
     * Runs the ChatGPT/Codex login for a provider and additionally enrolls the account in the
     * enterprise Agent Identity flow ({@code /codex login <provider> --enterprise}). This is the path
     * for managed/company (workspace) plans that the plain OAuth browser/device flows cannot reach.
     *
     * <p>It reuses {@link #signIn} to obtain the OAuth session, then, with that access token as the
     * anchor, registers a fresh Ed25519 device identity with the auth API
     * ({@code /v1/agent/register}), registers a run task ({@code /v1/agent/{id}/task/register}), and
     * persists the resulting {@link AgentIdentityRecord} next to the OAuth tokens so requests are
     * authenticated with a signed {@code AgentAssertion} header. This is the eager/forced variant: the
     * same enrollment happens lazily on first {@code {{codexAgentAssertion}} use for a plain session.</p>
     *
     * @param provider the provider id to sign in.
     * @param device true to use the device-code flow instead of the browser flow.
     * @param urlSink receives the authorize (or verification) URL to open.
     * @param cancelled cancellation probe.
     * @return the authenticated session carrying the Agent Identity registration.
     */
    public CodexTokenStore signInAgent(
            final String provider,
            final boolean device,
            final Consumer<String> urlSink,
            final Predicate<Boolean> cancelled) {
        return signInAgent(provider, device, urlSink, cancelled, null);
    }

    /**
     * Like {@link #signInAgent(String, boolean, Consumer, Predicate)}, but pins the enrollment to a
     * specific workspace/organization id. When {@code forcedWorkspaceId} is non-blank it must belong
     * to the signed-in account (the id token lists the organizations): the enrollment uses that id as
     * the account id, and fails loudly if the account is not a member - so a user in several
     * workspaces can pick the enterprise one instead of silently using the default. When it is blank
     * (the common case), enrollment uses the account's default organization, matching codex.
     *
     * @param provider the provider id to sign in.
     * @param device true to use the device-code flow instead of the browser flow.
     * @param urlSink receives the authorize (or verification) URL to open.
     * @param cancelled cancellation probe.
     * @param forcedWorkspaceId an organization/workspace id to pin, or null/blank for the default.
     * @return the authenticated session carrying the Agent Identity registration.
     */
    public CodexTokenStore signInAgent(
            final String provider,
            final boolean device,
            final Consumer<String> urlSink,
            final Predicate<Boolean> cancelled,
            final String forcedWorkspaceId) {
        final CodexTokenStore oauthSession = signIn(provider, device, urlSink, cancelled);
        return enrollNow(provider, oauthSession, forcedWorkspaceId, cancelled);
    }

    /**
     * Eagerly enrolls an existing OAuth session in the enterprise Agent Identity flow and persists the
     * result. Used by the forced {@code /codex login --enterprise} path; the shared enrollment logic
     * lives on {@link CodexAgentIdentityClient#enroll}. {@code forcedWorkspaceId} is optional - when
     * blank the enrollment uses the account's default organization.
     *
     * @param provider the provider id.
     * @param oauthSession the already-signed-in OAuth session.
     * @param forcedWorkspaceId an organization/workspace id to pin, or null/blank for the default.
     * @return the session carrying the Agent Identity registration.
     */
    public CodexTokenStore enrollNow(
            final String provider, final CodexTokenStore oauthSession, final String forcedWorkspaceId) {
        return enrollNow(provider, oauthSession, forcedWorkspaceId, null);
    }

    /** Cancellation-aware enrollment; an already committed OAuth session is not rolled back. */
    public CodexTokenStore enrollNow(
            final String provider,
            final CodexTokenStore oauthSession,
            final String forcedWorkspaceId,
            final Predicate<Boolean> cancelled) {
        final var enrolled = enroll(oauthSession, forcedWorkspaceId, cancelled);
        save(provider, enrolled, cancelled);
        return enrolled;
    }

    private CodexTokenStore enroll(
            final CodexTokenStore oauthSession, final String forcedWorkspaceId, final Predicate<Boolean> cancelled) {
        checkCancelled(cancelled);
        if (oauthSession.accessToken() == null || oauthSession.accessToken().isBlank()) {
            throw new IllegalStateException("ChatGPT/Codex login succeeded but produced no access token");
        }
        final var agentIdentity = new CodexAgentIdentityClient(mapper, httpClient, CodexAgentIdentityConfig.DEFAULT);
        final var record = agentIdentity.enroll(
                oauthSession.accessToken(),
                oauthSession.idToken(),
                oauthSession.accountId(),
                forcedWorkspaceId,
                cancelled);
        checkCancelled(cancelled);
        return oauthSession.withAgentIdentity(record);
    }

    /** The token store for a provider, or {@code null} when no session exists yet. */
    public CodexTokenStore load(final String provider) {
        final Path file = codexAuthFile(provider);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            final var store = mapper.fromBytes(CodexTokenStore.class, Files.readAllBytes(file));
            secretRegistration.register(store);
            return store;
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot read the Codex auth file " + file + ": " + e.getMessage(), e);
        }
    }

    /** Persists a session atomically (temp file + move, owner-only perms). */
    public void save(final String provider, final CodexTokenStore store) {
        save(provider, store, null);
    }

    private void save(final String provider, final CodexTokenStore store, final Predicate<Boolean> cancelled) {
        checkCancelled(cancelled);
        secretRegistration.register(store);
        final var file = codexAuthFile(provider);
        final var tmp = stage(file, store);
        try {
            checkCancelled(cancelled);
            replace(tmp, file);
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot persist the Codex session to " + file + ": " + e.getMessage(), e);
        } finally {
            deleteStaged(tmp);
        }
    }

    private Path stage(final Path file, final CodexTokenStore store) {
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());
            // Unique, owner-only staging files also permit concurrent services targeting one store.
            tmp = Files.createTempFile(file.getParent(), "codex.auth-", ".tmp");
            restrictToOwner(tmp);
            Files.write(tmp, mapper.toBytes(store));
            return tmp;
        } catch (final IOException | RuntimeException | Error e) {
            deleteStaged(tmp);
            throw new IllegalStateException("Cannot stage the Codex session", e);
        }
    }

    private void replace(final Path tmp, final Path file) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException unsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteStaged(final Path tmp) {
        if (tmp != null) {
            try {
                Files.deleteIfExists(tmp);
            } catch (final IOException ignored) {
                // Best effort, like permission restriction on non-POSIX file systems.
            }
        }
    }

    private static void checkCancelled(final Predicate<Boolean> cancelled) {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.test(Boolean.TRUE))) {
            throw new CancellationException("Codex login cancelled");
        }
    }

    /** Nonsecret routing data only: never pass an OAuth device_auth_id to a frontend. */
    public record DeviceChallenge(String verificationUrl, String userCode) {}

    public enum LoginStatus {
        AUTHORIZING,
        ENROLLING,
        SAVING
    }

    public enum LoginState {
        CREATED,
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    /** Contains no credentials or provider exception text, including on failure. */
    public record LoginResult(LoginState state, boolean credentialsCommitted) {}

    public record LoginRequest(String provider, boolean device, boolean enterprise, String forcedWorkspaceId) {
        public LoginRequest {
            if (provider == null
                    || provider.isBlank()
                    || provider.equals(".")
                    || provider.equals("..")
                    || provider.contains("/")
                    || provider.contains("\\")) {
                throw new IllegalArgumentException("Invalid Codex provider identifier");
            }
        }
    }

    /**
     * Nonblocking frontend callbacks, invoked on the attempt worker, never under its commit guard.
     * Browser URLs carry OAuth state: route them to the user, not logs. Device URL and code are separate.
     * No passwords, tokens or generic secret-input forms are requested by this API.
     */
    public interface LoginListener {
        default void status(final LoginStatus status) {}

        default void browser(final String authorizeUrl) {}

        default void device(final DeviceChallenge challenge) {}
    }

    /**
     * Creates an unstarted, caller-owned attempt. Retain it in the owning session until {@link
     * LoginAttempt#drained()} settles; the service keeps no attempt registry. Cancellation before
     * {@link LoginAttempt#start(ExecutorService)} prevents all startup work.
     */
    public LoginAttempt newAttempt(final LoginRequest request, final LoginListener listener) {
        return new LoginAttempt(Objects.requireNonNull(request), listener);
    }

    /**
     * A single login, not a job framework. close/cancel request interruption, but do not close the
     * shared HTTP client or executor. completion reports the chosen outcome; drained reports actual
     * worker/resource release (even if an external SDK ignores interruption). Callbacks admitted
     * before cancel wins can finish before drained, but no further callbacks are admitted.
     */
    public final class LoginAttempt implements AutoCloseable {
        private final ReentrantLock guard = new ReentrantLock();
        private final CompletableFuture<LoginResult> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> drained = new CompletableFuture<>();
        private volatile LoginState state = LoginState.CREATED;
        private LoginRequest request;
        private LoginListener listener;
        private Thread worker;

        private LoginAttempt(final LoginRequest request, final LoginListener listener) {
            this.request = request;
            this.listener = listener;
        }

        /** Uses an application-owned executor; never submits to the detached common pool. */
        public LoginAttempt start(final ExecutorService executor) {
            Objects.requireNonNull(executor);
            guard.lock();
            try {
                if (state == LoginState.CANCELLED) {
                    return this;
                }
                if (state != LoginState.CREATED) {
                    throw new IllegalStateException("Codex login already started");
                }
                state = LoginState.QUEUED;
            } finally {
                guard.unlock();
            }
            try {
                executor.execute(this::run);
            } catch (final RuntimeException | Error ignored) {
                finish();
            }
            return this;
        }

        public LoginState state() {
            return state;
        }

        public CompletionStage<LoginResult> completion() {
            return completion.minimalCompletionStage();
        }

        public CompletionStage<Void> drained() {
            return drained.minimalCompletionStage();
        }

        /** Returns true only if cancellation wins before the durable commit/terminal outcome. */
        public boolean cancel() {
            final boolean idle;
            guard.lock();
            try {
                if (terminal()) {
                    return false;
                }
                state = LoginState.CANCELLED;
                idle = worker == null;
                listener = null;
                request = null;
                if (!idle) {
                    // Inside the guard so a pooled thread cannot be interrupted after release/reuse.
                    worker.interrupt();
                }
            } finally {
                guard.unlock();
            }
            if (idle) {
                // Queued runnables can only become no-ops after this transition.
                drained.complete(null);
            }
            completion.complete(new LoginResult(LoginState.CANCELLED, false));
            return true;
        }

        /** Requests cancellation; await drained() separately before disposing the callback owner. */
        @Override
        public void close() {
            cancel();
        }

        private boolean terminal() {
            return state == LoginState.SUCCEEDED || state == LoginState.FAILED || state == LoginState.CANCELLED;
        }

        private void notifyListener(final Consumer<LoginListener> callback) {
            final LoginListener target;
            guard.lock();
            try {
                checkActive();
                target = listener;
            } finally {
                guard.unlock();
            }
            if (target != null) {
                callback.accept(target);
            }
        }

        private void checkActive() {
            checkCancelled(b -> state == LoginState.CANCELLED);
        }

        private void run() {
            final LoginRequest login;
            guard.lock();
            try {
                if (state != LoginState.QUEUED) {
                    return;
                }
                worker = Thread.currentThread();
                state = LoginState.RUNNING;
                login = request;
            } finally {
                guard.unlock();
            }
            Path tmp = null;
            try {
                checkActive();
                notifyListener(l -> l.status(LoginStatus.AUTHORIZING));
                var store = authenticate(
                        login.device(),
                        login.device() ? null : url -> notifyListener(l -> l.browser(url)),
                        challenge -> notifyListener(l -> l.device(challenge)),
                        b -> state == LoginState.CANCELLED,
                        CodexOAuthConfig.DEFAULT);
                if (login.enterprise()) {
                    notifyListener(l -> l.status(LoginStatus.ENROLLING));
                    store = enroll(store, login.forcedWorkspaceId(), b -> state == LoginState.CANCELLED);
                }
                notifyListener(l -> l.status(LoginStatus.SAVING));
                checkActive();
                secretRegistration.register(store);
                final var file = codexAuthFile(login.provider());
                tmp = stage(file, store);
                guard.lock();
                try {
                    checkActive();
                    // Only the final replacement is guarded, not OAuth, enrollment, JSON or hooks.
                    replace(tmp, file);
                    state = LoginState.SUCCEEDED;
                } finally {
                    guard.unlock();
                }
            } catch (final IOException | RuntimeException | Error ignored) {
                // Never publish provider bodies, exception messages/causes, credentials or hook errors.
            } finally {
                try {
                    deleteStaged(tmp);
                } finally {
                    finish();
                }
            }
        }

        private void finish() {
            final LoginResult result;
            guard.lock();
            try {
                if (!terminal()) {
                    state = LoginState.FAILED;
                }
                result = new LoginResult(state, state == LoginState.SUCCEEDED);
                worker = null;
                request = null;
                listener = null;
            } finally {
                guard.unlock();
            }
            // Separate from outcome cancellation; never use Future.cancel as evidence of drainage.
            drained.complete(null);
            completion.complete(result);
        }
    }

    /** Restricts the auth file to owner-only reads/writes (POSIX 0600); best effort on non-POSIX. */
    private static void restrictToOwner(final Path path) {
        try {
            Files.setPosixFilePermissions(
                    path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (final UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem: no-op
        }
    }

    private Path codexAuthFile(final String provider) {
        return providersHome.resolve(provider).resolve("codex.auth.json");
    }
}
