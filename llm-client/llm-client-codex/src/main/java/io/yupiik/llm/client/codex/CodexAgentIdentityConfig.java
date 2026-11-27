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
 * Tunable settings for the Codex enterprise/Agent Identity enrollment. Every value defaults to the
 * public OpenAI endpoints so it works out of the box, but all can be overridden (eg. to point at a
 * staging environment or a mock auth API in tests).
 *
 * <p>Mirrors {@code codex-rs/agent-identity} constants in {@code openai/codex}: the auth API base,
 * the JWKS base, the advertised Codex client version for the {@code ABOM}, and the harness id.</p>
 *
 * @param authApiBaseUrl the base URL of the Agent Identity auth API (the {@code /v1/agent/...}
 *     registration endpoints live under {@code {authApiBaseUrl}/v1/agent/...}).
 * @param jwksBaseUrl the base URL whose {@code /wham/agent-identities/jwks} (ChatGPT base) or
 *     {@code /agent-identities/jwks} (auth API) serves the RS256 keys that verify the agent
 *     identity JWT; populated only when the identity came from a JWT (not from a local keypair).
 * @param agentVersion the {@code agent_version} advertised in the {@code ABOM} (whole part of the
 *     Codex client version it mimics).
 * @param agentHarnessId the {@code agent_harness_id} advertised in the {@code ABOM}
 *     ({@code codex-cli} or {@code codex-app}).
 */
public record CodexAgentIdentityConfig(
        String authApiBaseUrl, String jwksBaseUrl, String agentVersion, String agentHarnessId) {

    /**
     * The agent version a fresh enrollment advertises in the {@code ABOM}. It should track the whole
     * part {@link CodexDialect#DEFAULT_CLIENT_VERSION} the model-gating uses (eg. {@code 160} -> the
     * {@code 0.160.x} line), so an inconsistent ABOM does not trigger gating on the backend.
     */
    public static final String DEFAULT_AGENT_VERSION = "0.160.0";

    /** Default public configuration (production). */
    public static final CodexAgentIdentityConfig DEFAULT = new CodexAgentIdentityConfig(
            "https://auth.openai.com/api/accounts", "https://chatgpt.com", DEFAULT_AGENT_VERSION, "codex-cli");

    /** Defaults any {@code null}/blank field to the {@link #DEFAULT} counterpart. */
    public CodexAgentIdentityConfig {
        authApiBaseUrl = authApiBaseUrl == null || authApiBaseUrl.isBlank() ? DEFAULT.authApiBaseUrl : authApiBaseUrl;
        jwksBaseUrl = jwksBaseUrl == null || jwksBaseUrl.isBlank() ? DEFAULT.jwksBaseUrl : jwksBaseUrl;
        agentVersion = agentVersion == null || agentVersion.isBlank() ? DEFAULT.agentVersion : agentVersion;
        agentHarnessId = agentHarnessId == null || agentHarnessId.isBlank() ? DEFAULT.agentHarnessId : agentHarnessId;
    }
}
