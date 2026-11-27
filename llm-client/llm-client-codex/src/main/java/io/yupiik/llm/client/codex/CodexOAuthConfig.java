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

/**
 * Tunable Codex OAuth settings. Every value defaults to the public ChatGPT/Codex endpoints so the
 * client works out of the box, but each can be overridden to point at a proxy/gateway (eg. a
 * self-hosted or regional issuer) without recompiling the client.
 *
 * @param issuer the OAuth issuer base URL (token endpoint, authorize endpoint, device endpoints).
 * @param clientId the public OAuth client id.
 * @param scopes the OpenID scopes requested; {@code offline_access} is what issues a refresh token.
 * @param verificationUrl the URL shown to the user to enter their device code.
 * @param callbackPort the loopback port the PKCE callback server binds for the browser flow.
 */
public record CodexOAuthConfig(
        String issuer, String clientId, String scopes, String verificationUrl, int callbackPort) {

    /** The default public configuration. */
    public static final CodexOAuthConfig DEFAULT = new CodexOAuthConfig(
            "https://auth.openai.com",
            "app_EMoamEEZ73f0CkXaXp7hrann",
            "openid profile email offline_access",
            "https://auth.openai.com/codex/device",
            1_455);

    /** Defaults any {@code null}/blank or endian port field to the {@link #DEFAULT} counterpart. */
    public CodexOAuthConfig {
        issuer = issuer == null || issuer.isBlank() ? DEFAULT.issuer : issuer;
        clientId = clientId == null || clientId.isBlank() ? DEFAULT.clientId : clientId;
        scopes = scopes == null || scopes.isBlank() ? DEFAULT.scopes : scopes;
        verificationUrl =
                verificationUrl == null || verificationUrl.isBlank() ? DEFAULT.verificationUrl : verificationUrl;
        callbackPort = callbackPort <= 0 || callbackPort > 65535 ? DEFAULT.callbackPort : callbackPort;
    }
}
