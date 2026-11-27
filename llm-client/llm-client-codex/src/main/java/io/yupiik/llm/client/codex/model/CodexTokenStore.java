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
package io.yupiik.llm.client.codex.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

/**
 * The cached Codex OAuth session, largely mirroring the shape of the Codex CLI
 * {@code ~/.codex/auth.json} {@code tokens} object so the values can be interchanged (the values
 * are never read back from the CLI file though: each app persists its own copy under its storage
 * home). The access token is short lived (about an hour); the refresh token may rotate on every
 * refresh and must be persisted each time.
 *
 * <p>When the account is signed into through the enterprise/Agent Identity flow, an optional
 * {@link AgentIdentityRecord} is attached ({@code agentIdentity}). Its presence flips the per-request
 * authentication from {@code Authorization: Bearer <access_token>} to a signed
 * {@code Authorization: AgentAssertion {...}} header (see the {@code {{codexAgentAssertion}}}
 * helper).</p>
 *
 * <p>An optional {@code openaiApiKey}, obtained at login through the token-exchange grant (the
 * upstream {@code obtain_api_key}), is stored alongside as inert metadata for parity/telemetry: a
 * regular ChatGPT-oauth session continues to authenticate with the OAuth access token (mirroring
 * codex, where {@code openai_api_key} only reaches the request bearer in the separate {@code ApiKey}
 * auth mode). {@code issuer}/{@code clientId} record the OAuth authority at login so a later refresh
 * routes to the same endpoint (provenance), and {@code lastRefreshMillis} tracks the last successful
 * refresh for the {@code scheduled} refresh reason.</p>
 *
 * @param accessToken the short-lived ChatGPT account access token.
 * @param refreshToken the longer-lived refresh token (rotated on refresh).
 * @param accountId the {@code chatgpt_account_id} extracted from the id_token claim.
 * @param expiresAt epoch millis when the access token expires.
 * @param idToken the raw id token (bearer of the account id claim); kept for re-deriving the
 *     account id without a round trip.
 * @param agentIdentity the optional enterprise Agent Identity registration, or {@code null} when the
 *     account is only signed in through the regular ChatGPT OAuth flow.
 * @param openaiApiKey the optional API-key-style token exchanged at login; inert for a regular
 *     ChatGPT-oauth session (never used as the request bearer).
 * @param issuer the OAuth issuer (auth authority) recorded at login, or {@code null}.
 * @param clientId the OAuth client id recorded at login, or {@code null}.
 * @param lastRefreshMillis epoch millis of the last successful refresh, or {@code null} when unknown.
 */
@JsonModel
public record CodexTokenStore(
        String accessToken,
        String refreshToken,
        String accountId,
        Long expiresAt,
        String idToken,
        @JsonProperty("agent_identity") AgentIdentityRecord agentIdentity,
        @JsonProperty("openai_api_key") String openaiApiKey,
        String issuer,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("last_refresh") Long lastRefreshMillis) {

    /** Creates a store without an Agent Identity registration (the regular ChatGPT OAuth flow). */
    public CodexTokenStore(
            final String accessToken,
            final String refreshToken,
            final String accountId,
            final Long expiresAt,
            final String idToken) {
        this(accessToken, refreshToken, accountId, expiresAt, idToken, null);
    }

    /** Creates a store with an Agent Identity registration (the enterprise flow). */
    public CodexTokenStore(
            final String accessToken,
            final String refreshToken,
            final String accountId,
            final Long expiresAt,
            final String idToken,
            final AgentIdentityRecord agentIdentity) {
        this(accessToken, refreshToken, accountId, expiresAt, idToken, agentIdentity, null, null, null, null);
    }

    /** Copy of this store with a different (or null) Agent Identity registration. */
    public CodexTokenStore withAgentIdentity(final AgentIdentityRecord agentIdentity) {
        return new CodexTokenStore(
                accessToken,
                refreshToken,
                accountId,
                expiresAt,
                idToken,
                agentIdentity,
                openaiApiKey,
                issuer,
                clientId,
                lastRefreshMillis);
    }

    /** Copy of this store with the exchanged API-key token set (or cleared). */
    public CodexTokenStore withApiKey(final String openaiApiKey) {
        return new CodexTokenStore(
                accessToken,
                refreshToken,
                accountId,
                expiresAt,
                idToken,
                agentIdentity,
                openaiApiKey,
                issuer,
                clientId,
                lastRefreshMillis);
    }

    /** Copy of this store recording the OAuth issuer/client id at login (provenance). */
    public CodexTokenStore withProvenance(final String issuer, final String clientId) {
        return new CodexTokenStore(
                accessToken,
                refreshToken,
                accountId,
                expiresAt,
                idToken,
                agentIdentity,
                openaiApiKey,
                issuer,
                clientId,
                lastRefreshMillis);
    }

    /** Copy of this store with a refreshed/rotated access token (and optional new id token). */
    public CodexTokenStore withTokens(
            final String newAccessToken, final String newRefreshToken, final String newIdToken) {
        return new CodexTokenStore(
                newAccessToken,
                newRefreshToken == null || newRefreshToken.isBlank() ? refreshToken : newRefreshToken,
                accountId,
                expiresAt,
                newIdToken == null || newIdToken.isBlank() ? idToken : newIdToken,
                agentIdentity,
                openaiApiKey,
                issuer,
                clientId,
                lastRefreshMillis);
    }

    /** Copy of this store with the refreshed tokens, the new access-token expiry and a fresh last-refresh timestamp. */
    public CodexTokenStore withRefreshed(
            final String newAccessToken,
            final String newRefreshToken,
            final String newIdToken,
            final String newOpenaiApiKey,
            final Long newExpiresAt,
            final boolean retainProvenance) {
        return new CodexTokenStore(
                newAccessToken,
                newRefreshToken == null || newRefreshToken.isBlank() ? refreshToken : newRefreshToken,
                accountId,
                newExpiresAt,
                newIdToken == null || newIdToken.isBlank() ? idToken : newIdToken,
                agentIdentity,
                newOpenaiApiKey == null || newOpenaiApiKey.isBlank() ? openaiApiKey : newOpenaiApiKey,
                retainProvenance ? issuer : null,
                retainProvenance ? clientId : null,
                System.currentTimeMillis());
    }

    /** True when the access token is absent or already expired (with a small clock-skew guard). */
    public boolean expired(final long now) {
        return accessToken == null || accessToken.isBlank() || expiresAt == null || expiresAt <= now + 60_000;
    }

    /** True when a refresh token is present, ie. this session can be refreshed without re-login. */
    public boolean refreshable() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    /** True when this session carries an enterprise Agent Identity registration. */
    public boolean hasAgentIdentity() {
        return agentIdentity != null;
    }
}
