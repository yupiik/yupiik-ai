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
package io.yupiik.llm.client.codex;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.LlmHttpFailureException;
import io.yupiik.llm.client.LlmResponseParseException;
import io.yupiik.llm.client.codex.model.CodexModels;
import io.yupiik.llm.client.model.LlmModel;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.openai.OpenAIResponsesDialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import io.yupiik.llm.client.spi.StreamAttempt;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The ChatGPT-subscription Codex backend dialect: the wire format is exactly the OpenAI Responses
 * API (streaming, tool calls, reasoning deltas), so this reuses {@link OpenAIResponsesDialect}
 * unchanged and only adjusts the routing identifier, the chat route and the model catalog source.
 *
 * <p>The chat endpoint is {@code https://chatgpt.com/backend-api/codex/responses} (the base URL is
 * provider-configured as {@code .../codex}). The model catalog is served by the Codex backend at
 * {@code {base}/models?client_version=N} in a different shape than the public OpenAI {@code /v1/models}
 * endpoint: an object with a {@code models} array of catalog entries carrying {@code visibility},
 * {@code supported_in_api} and {@code minimal_client_version}, which this dialect filters before
 * exposing them as {@link LlmModel}s.</p>
 */
public class CodexDialect extends OpenAIResponsesDialect {

    /** Default Codex client version advertised for model gating (whole part of 0.N.x). */
    static final int DEFAULT_CLIENT_VERSION = 160;

    /** The backend error marker for a workspace policy that rejects delegated (3p) OAuth access. */
    private static final String DELEGATED_ACCESS_POLICY = "3p_delegated_access_policy_denied";

    private final int clientVersion;

    /**
     * Explicit override for the encrypted-reasoning {@code include} decision, used only when the provider
     * option is not carried by the {@code ProviderSpec}. Defaults to {@code false} for the Codex flavor:
     * the real ChatGPT backend and many stricter Codex/gov edges reject the {@code include} directive with
     * an unknown-parameter 400, and the common path works with just {@code {"effort","summary"}}.
     */
    private final boolean includeEncryptedReasoning;

    /**
     * Optional 3p auto-upgrade: given the provider, returns the signed {@code AgentAssertion {...}}
     * header value for the retry (it lazily enrolls the enterprise identity on first use). {@code null}
     * (the default) disables the auto-upgrade, in which case the delegated-access 4xx surfaces as-is.
     */
    private final Function<String, String> assertionUpgrader;

    private volatile Consumer<String> unauthorizedRecovery;

    /** Registers recovery for a rejected bearer; called once before retrying an unopened stream. */
    public void setUnauthorizedRecovery(final Consumer<String> recovery) {
        unauthorizedRecovery = recovery;
    }

    /**
     * Creates the dialect advertising the default Codex client version.
     *
     * @param mapper JSON mapper used for wire serialization.
     * @param client HTTP client used for provider calls.
     * @param provider provider coordinates; {@code baseUrl} should end in {@code .../codex/responses}.
     */
    public CodexDialect(final JsonMapper mapper, final ExtendedHttpClient client, final ProviderSpec provider) {
        this(mapper, client, provider, DEFAULT_CLIENT_VERSION);
    }

    /**
     * Creates the dialect with a tunable Codex client version, which gates which models the backend
     * discloses in its catalog ({@code minimal_client_version}).
     *
     * @param mapper JSON mapper used for wire serialization.
     * @param client HTTP client used for provider calls.
     * @param provider provider coordinates; {@code baseUrl} should end in {@code .../codex/responses}.
     * @param clientVersion the whole part of the Codex client version this dialect advertises (eg. 160 for 0.160.x).
     */
    public CodexDialect(
            final JsonMapper mapper,
            final ExtendedHttpClient client,
            final ProviderSpec provider,
            final int clientVersion) {
        this(mapper, client, provider, clientVersion, null);
    }

    /**
     * Creates the dialect with a tunable Codex client version and an optional 3p auto-upgrade callback.
     *
     * @param mapper JSON mapper used for wire serialization.
     * @param client HTTP client used for provider calls.
     * @param provider provider coordinates; {@code baseUrl} should end in {@code .../codex/responses}.
     * @param clientVersion the whole part of the Codex client version this dialect advertises (eg. 160 for 0.160.x).
     * @param assertionUpgrader when non-null, invoked on a {@code 3p_delegated_access_policy_denied}
     *     rejection with the provider id to produce the signed {@code AgentAssertion {...}} header for
     *     a single retry (it lazily enrolls the enterprise identity); {@code null} disables the
     *     auto-upgrade. See {@link #DELEGATED_ACCESS_POLICY}.
     */
    public CodexDialect(
            final JsonMapper mapper,
            final ExtendedHttpClient client,
            final ProviderSpec provider,
            final int clientVersion,
            final Function<String, String> assertionUpgrader) {
        this(mapper, client, provider, clientVersion, assertionUpgrader, false);
    }

    /**
     * Creates the dialect with a tunable Codex client version, an optional 3p auto-upgrade callback and an
     * explicit encrypted-reasoning {@code include} decision.
     *
     * @param mapper JSON mapper used for wire serialization.
     * @param client HTTP client used for provider calls.
     * @param provider provider coordinates; {@code baseUrl} should end in {@code .../codex/responses}.
     * @param clientVersion the whole part of the Codex client version this dialect advertises (eg. 160 for 0.160.x).
     * @param assertionUpgrader when non-null, invoked on a {@code 3p_delegated_access_policy_denied}
     *     rejection with the provider id to produce the signed {@code AgentAssertion {...}} header for
     *     a single retry (it lazily enrolls the enterprise identity); {@code null} disables the
     *     auto-upgrade. See {@link #DELEGATED_ACCESS_POLICY}.
     * @param includeEncryptedReasoning whether to request the opaque {@code reasoning.encrypted_content}
     *     blob; defaults to {@code false} for the Codex flavor.
     */
    public CodexDialect(
            final JsonMapper mapper,
            final ExtendedHttpClient client,
            final ProviderSpec provider,
            final int clientVersion,
            final Function<String, String> assertionUpgrader,
            final boolean includeEncryptedReasoning) {
        this(
                mapper,
                client,
                provider,
                clientVersion,
                assertionUpgrader,
                includeEncryptedReasoning,
                List.of("session-id", "prompt_cache_key"),
                false);
    }

    /**
     * Creates the dialect with tunable Codex client version, optional 3p auto-upgrade, an explicit
     * encrypted-reasoning {@code include} decision and the session-affinity knobs.
     *
     * @param mapper JSON mapper used for wire serialization.
     * @param client HTTP client used for provider calls.
     * @param provider provider coordinates; {@code baseUrl} should end in {@code .../codex/responses}.
     * @param clientVersion the whole part of the Codex client version this dialect advertises (eg. 160 for 0.160.x).
     * @param assertionUpgrader when non-null, invoked on a {@code 3p_delegated_access_policy_denied}
     *     rejection with the provider id to produce the signed {@code AgentAssertion {...}} header for
     *     a single retry (it lazily enrolls the enterprise identity); {@code null} disables the
     *     auto-upgrade. See {@link #DELEGATED_ACCESS_POLICY}.
     * @param includeEncryptedReasoning whether to request the opaque {@code reasoning.encrypted_content}
     *     blob; defaults to {@code false} for the Codex flavor.
     * @param enabledKeys the provider's resolved {@code capabilities.openai.sessionId.enabledKeys}; the
     *     codex default {@code ["session-id","prompt_cache_key"]} reproduces the Codex CLI's session
     *     affinity ({@code prompt_cache_key} body field + {@code session-id} header).
     * @param xClientRequestIdEnabled whether to emit a fresh per-request {@code x-client-request-id} marker.
     */
    public CodexDialect(
            final JsonMapper mapper,
            final ExtendedHttpClient client,
            final ProviderSpec provider,
            final int clientVersion,
            final Function<String, String> assertionUpgrader,
            final boolean includeEncryptedReasoning,
            final List<String> enabledKeys,
            final boolean xClientRequestIdEnabled) {
        super(mapper, client, provider, enabledKeys, xClientRequestIdEnabled);
        this.clientVersion = clientVersion <= 0 ? DEFAULT_CLIENT_VERSION : clientVersion;
        this.assertionUpgrader = assertionUpgrader;
        this.includeEncryptedReasoning = includeEncryptedReasoning;
    }

    /** The routing identifier of this dialect. */
    @Override
    public String name() {
        return "codex";
    }

    /** The chat route suffix appended to the provider base URL: the Codex backend serves the Responses API. */
    @Override
    protected String chatRouteSuffix() {
        return "/responses";
    }

    /**
     * The ChatGPT Codex backend only accepts streaming requests ({@code stream:true}); any other
     * call is rejected with {@code {"detail":"Stream must be set to true"}}. Force the wire flag on
     * every request, including background single-shot calls.
     *
     * @return {@code true}, the Codex backend rejects non-streaming requests.
     */
    @Override
    protected boolean forceStream() {
        return true;
    }

    /**
     * The Codex flavor defaults to {@code false}: the ChatGPT backend and stricter Codex/gov edges reject
     * the {@code include} directive with an unknown-parameter 400, and the common path works with just
     * {@code {"effort","summary"}}. Overridable per provider through the constructor flag (the
     * {@code reasoning_include} provider option).
     *
     * @return {@code false} by default, or the per-provider override.
     */
    @Override
    protected boolean includeEncryptedReasoning() {
        if (provider.options() != null
                && !provider.options().isEmpty()
                && provider.options().get(REASONING_INCLUDE_KEY) instanceof Boolean b) {
            return b;
        }
        return includeEncryptedReasoning;
    }

    /**
     * The Codex backend never serves a plain single-shot JSON body - it always streams SSE. Run the
     * generated stream through {@link #assemble(LlmRequest, BooleanSupplier)} so background
     * (non-streaming) chat callers get a single {@link LlmResponse} back.
     *
     * @param request the request to run as a single-shot completion.
     * @return a stage completing with the assembled single-shot response.
     */
    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request) {
        return chat(request, () -> false);
    }

    /**
     * The Codex backend never serves a plain single-shot JSON body - it always streams SSE. Run the
     * generated stream through {@link #assemble(LlmRequest, BooleanSupplier)} so background
     * (non-streaming) chat callers get a single {@link LlmResponse} back.
     *
     * @param request the request to run as a single-shot completion.
     * @param cancelled cancellation probe checked between frames.
     * @return a stage completing with the assembled single-shot response.
     */
    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
        return assemble(request, cancelled == null ? () -> false : cancelled);
    }

    /**
     * The Codex (chatgpt.com) backend streams SSE without a {@code text/event-stream} content-type
     * header, so the base {@code openStream} classification (which requires that header) would buffer
     * the whole body as an error and report "LLM request failed (200): event: response.created". For
     * codex, any 2xx to the streaming endpoint is a live SSE stream by definition.
     */
    @Override
    protected CompletionStage<StreamAttempt> openStream(final byte[] payload, final BooleanSupplier cancelled) {
        return openStream(payload, cancelled, true);
    }

    private CompletionStage<StreamAttempt> openStream(
            final byte[] payload, final BooleanSupplier cancelled, final boolean allowRecovery) {
        try {
            final var request = http(payload);
            return client.sendAsync(request, streamingBodyHandler())
                    .thenCompose(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() <= 299) {
                            return CompletableFuture.completedFuture(new StreamAttempt(
                                    response.statusCode(), response.headers(), response.body(), null));
                        }
                        return collect(response.body())
                                .thenApply(body ->
                                        new StreamAttempt(response.statusCode(), response.headers(), null, body));
                    })
                    .thenCompose(attempt -> {
                        final var recovery = unauthorizedRecovery;
                        final var authorization =
                                request.headers().firstValue("authorization").orElse("");
                        if (allowRecovery
                                && attempt.statusCode() == 401
                                && recovery != null
                                && authorization.startsWith("Bearer ")
                                && !cancelled.getAsBoolean()) {
                            recovery.accept(authorization);
                            return openStream(payload, cancelled, false);
                        }
                        return isDelegatedAccessBlocked(attempt)
                                ? retryWithAgentAssertion(payload, cancelled)
                                : CompletableFuture.completedFuture(attempt);
                    });
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    /** True when the failed stream attempt is the delegated-access (3p) workspace-policy rejection. */
    private boolean isDelegatedAccessBlocked(final StreamAttempt attempt) {
        if (attempt.statusCode() < 400
                || assertionUpgrader == null
                || attempt.errorBody() == null
                || attempt.errorBody().length == 0) {
            return false;
        }
        final var body = new String(attempt.errorBody(), StandardCharsets.UTF_8);
        return body.contains(DELEGATED_ACCESS_POLICY);
    }

    /**
     * Recovers from a {@code 3p_delegated_access_policy_denied} rejection: lazily enrolls the
     * enterprise (Agent Identity) identity through the injected upgrader and re-sends the exact same
     * request once, replacing the bearer authorization with the signed {@code AgentAssertion} header.
     * The failure happened before any SSE event was produced, so re-sending the stream payload is safe.
     * If the upgrader itself fails, the original 4xx (the truthful cause) surfaces unchanged.
     */
    private CompletionStage<StreamAttempt> retryWithAgentAssertion(
            final byte[] payload, final BooleanSupplier cancelled) {
        final String assertion;
        try {
            // the upgrader closes over the provider id in core's resolver
            assertion = assertionUpgrader.apply(provider.baseUrl());
        } catch (final RuntimeException e) {
            return failed(new LlmHttpFailureException(403, DELEGATED_ACCESS_POLICY, null));
        }
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create(provider.baseUrl()))
                .timeout(provider.timeout())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        authenticate(builder);
        provider.headersCustomizer().accept(builder);
        // the assertion must win over the rendered {{codexAuth}}/{{codexAgentAssertion}} dynamic header:
        // apply it last so the enterprise signature replaces the rejected bearer
        builder.setHeader("authorization", assertion);
        try {
            return client.sendAsync(builder.build(), streamingBodyHandler()).thenCompose(response -> {
                if (response.statusCode() >= 200 && response.statusCode() <= 299) {
                    return CompletableFuture.completedFuture(
                            new StreamAttempt(response.statusCode(), response.headers(), response.body(), null));
                }
                return collect(response.body())
                        .thenApply(body -> new StreamAttempt(response.statusCode(), response.headers(), null, body));
            });
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    /**
     * Lists the models the authenticated Codex account may use: fetches the chatgpt.com model catalog
     * and keeps only entries that are selectable (visible, API-backed) and usable with the advertised
     * client version. Costs are zero because the subscription is plan-included.
     *
     * @return the filtered, normalized model listing.
     */
    @Override
    public CompletionStage<LlmModels> models() {
        try {
            return client.sendAsync(codexModelsRequest(), HttpResponse.BodyHandlers.ofString())
                    .thenCompose(response -> {
                        try {
                            return CompletableFuture.completedFuture(
                                    toLlmModels(parseResponse(CodexModels.class, response)));
                        } catch (final LlmResponseParseException e) {
                            return failed(e);
                        }
                    });
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    private HttpRequest codexModelsRequest() {
        final var endpoint = modelsEndpoint();
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(provider.timeout())
                .header("accept", "application/json")
                .GET();
        // the codexAuth bearer (dynamic header) + the static account-id header are applied by the
        // customizer, and authenticate() adds the API-key fallback; without these the catalog call
        // would go out unauthenticated and be rejected by the ChatGPT backend
        authenticate(builder);
        provider.headersCustomizer().accept(builder);
        return builder.build();
    }

    /** Derives the Codex models catalog endpoint from the chat base URL (strip {@code /responses}).
     *  The {@code client_version} must be a full {@code major.minor.patch} string (the backend rejects
     *  a bare integer with "Invalid client_version format"), so the configured whole part is expanded. */
    private String modelsEndpoint() {
        final var base = provider.baseUrl() == null ? "" : provider.baseUrl();
        final var stripped =
                base.endsWith("/responses") ? base.substring(0, base.length() - "/responses".length()) : base;
        final var withoutSlash = stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
        return withoutSlash + "/models?client_version=0." + clientVersion + ".0";
    }

    /** Normalizes the Codex catalog into {@link LlmModels}, filtering to selectable and usable entries. */
    private LlmModels toLlmModels(final CodexModels catalog) {
        final var models = new ArrayList<LlmModel>();
        final var list = catalog == null ? List.<CodexModels.CodexModel>of() : catalog.models();
        if (list != null) {
            for (final var m : list) {
                if (m == null || !m.selectable() || !m.usableWith(clientVersion)) {
                    continue;
                }
                final var attrs = new LinkedHashMap<String, Object>();
                attrs.put("slug", m.slug());
                attrs.put("display_name", m.displayName());
                if (m.contextWindow() != null) {
                    attrs.put("context_window", m.contextWindow());
                }
                if (m.maxContextWindow() != null) {
                    attrs.put("max_context_window", m.maxContextWindow());
                }
                if (m.defaultReasoningLevel() != null
                        && !m.defaultReasoningLevel().isBlank()) {
                    attrs.put("default_reasoning_level", m.defaultReasoningLevel());
                }
                if (m.supportedReasoningLevels() != null
                        && !m.supportedReasoningLevels().isEmpty()) {
                    final var levels = new ArrayList<LinkedHashMap<String, Object>>();
                    for (final var level : m.supportedReasoningLevels()) {
                        if (level == null
                                || level.effort() == null
                                || level.effort().isBlank()) {
                            continue;
                        }
                        final var attrsLevel = new LinkedHashMap<String, Object>();
                        attrsLevel.put("effort", level.effort());
                        if (level.description() != null && !level.description().isBlank()) {
                            attrsLevel.put("description", level.description());
                        }
                        levels.add(attrsLevel);
                    }
                    if (!levels.isEmpty()) {
                        attrs.put("supported_reasoning_levels", levels);
                    }
                }
                models.add(new LlmModel(m.id(), null, null, m.displayName(), attrs));
            }
        }
        return new LlmModels(models);
    }
}
