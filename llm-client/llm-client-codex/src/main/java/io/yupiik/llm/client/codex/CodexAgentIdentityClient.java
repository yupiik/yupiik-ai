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

import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.codex.model.AgentIdentityRecord;
import io.yupiik.llm.client.codex.model.IdTokenPayload;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;

/**
 * The Codex enterprise/Agent Identity enrollment client, mirroring {@code codex-rs/agent-identity}
 * in {@code openai/codex}. It registers a device identity with the auth API and then signs per-run
 * task registrations and per-request assertions.
 *
 * <p>Two-phase enrollment:</p>
 *
 * <ul>
 *   <li>{@link #registerAgentRuntime(String, Ed25519Util.KeyMaterial, boolean)} performs
 *       {@code POST {authApi}/v1/agent/register} with the ChatGPT access token and the Ed25519 ssh
 *       public key, returning an {@code agent_runtime_id}.</li>
 *   <li>{@link #registerTask(AgentIdentityRecord)} performs
 *       {@code POST {authApi}/v1/agent/{runtime}/task/register} signed with the stored Ed25519 key,
 *       returning the per-run {@code task_id}.</li>
 * </ul>
 *
 * <p>Once enrolled, each ChatGPT backend request is authenticated with an
 * {@code Authorization: AgentAssertion {...}} header produced by
 * {@link #assertionHeader(AgentIdentityRecord)}} (instead of {@code Bearer <access_token>}), plus
 * the {@code ChatGPT-Account-ID} and, for FedRAMP accounts, {@code X-OpenAI-Fedramp: true}.</p>
 */
public class CodexAgentIdentityClient {
    private Duration requestTimeout = Duration.ofMinutes(2);

    /** Sets the finite HTTP request budget, including OAuth token exchange (never retried here). */
    public CodexAgentIdentityClient requestTimeout(final Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("A positive finite timeout is required");
        }
        timeout.toNanos();
        this.requestTimeout = timeout;
        return this;
    }

    /** The {@code capabilities} the identity advertises (the Responses API). */
    private static final List<String> RESPONSES_CAPABILITY = List.of("responsesapi");

    private final JsonMapper mapper;
    private final ExtendedHttpClient client;
    private final CodexAgentIdentityConfig config;

    public CodexAgentIdentityClient(final JsonMapper mapper, final ExtendedHttpClient client) {
        this(mapper, client, CodexAgentIdentityConfig.DEFAULT);
    }

    /**
     * Creates a client with a custom configuration (mainly for tests and for proxying through a
     * regional/staging auth API). Defaults to {@link CodexAgentIdentityConfig#DEFAULT}.
     *
     * @param config the tunable Agent Identity settings.
     */
    public CodexAgentIdentityClient(
            final JsonMapper mapper, final ExtendedHttpClient client, final CodexAgentIdentityConfig config) {
        this.mapper = mapper;
        this.client = client;
        this.config = config == null ? CodexAgentIdentityConfig.DEFAULT : config;
    }

    /** The effective Agent Identity configuration. */
    public CodexAgentIdentityConfig config() {
        return config;
    }

    /** A freshly generated Ed25519 key material (private key + ssh public key). */
    public Ed25519Util.KeyMaterial generateKey() {
        return Ed25519Util.generate();
    }

    /**
     * Registers a fresh device identity with the auth API (phase A): the Ed25519 ssh public key is
     * handed over together with an {@link AgentBillOfMaterials} and the {@code responsesapi}
     * capability, authenticated with the ChatGPT access token. Retries transient (HTTP 429 / 5xx)
     * failures a bounded number of times, like codex.
     *
     * @param accessToken the ChatGPT OAuth access token.
     * @param key the freshly generated Ed25519 key material.
     * @param fedramp whether the account routes through the FedRAMP edge.
     * @return the {@code agent_runtime_id} assigned by the auth API.
     */
    public String registerAgentRuntime(
            final String accessToken, final Ed25519Util.KeyMaterial key, final boolean fedramp) {
        return registerAgentRuntime(accessToken, key, fedramp, null);
    }

    /** Cancellation-aware registration, including retry boundaries. */
    public String registerAgentRuntime(
            final String accessToken,
            final Ed25519Util.KeyMaterial key,
            final boolean fedramp,
            final Predicate<Boolean> cancelled) {
        checkCancelled(cancelled);
        final var body = mapper.toString(new RegisterAgentRequest(
                new AgentBillOfMaterials(config.agentVersion(), config.agentHarnessId(), runningLocation()),
                key.publicKeySsh(),
                RESPONSES_CAPABILITY,
                null));
        final var request = HttpRequest.newBuilder()
                .timeout(requestTimeout)
                .uri(URI.create(config.authApiBaseUrl() + "/v1/agent/register"))
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .header("authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8));
        if (fedramp) {
            request.header("X-OpenAI-Fedramp", "true");
        }
        var last = (HttpResponse<String>) null;
        for (int attempt = 0; attempt < 3; attempt++) {
            checkCancelled(cancelled);
            last = send(request.build());
            checkCancelled(cancelled);
            if (last.statusCode() >= 200 && last.statusCode() < 300) {
                final var parsed = parse(last.body());
                final var runtimeId = string(parsed, "agent_runtime_id");
                if (runtimeId == null || runtimeId.isBlank()) {
                    throw new IllegalStateException(
                            "Agent identity registration omitted agent_runtime_id: " + last.body());
                }
                return runtimeId;
            }
            if (!retryable(last.statusCode())) {
                throw new IllegalStateException(
                        "Agent identity registration failed with HTTP " + last.statusCode() + ": " + last.body());
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(500L * (attempt + 1));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Agent identity enrollment cancelled");
                }
            }
        }
        throw new IllegalStateException(
                "Agent identity registration failed after retries: HTTP " + last.statusCode() + ": " + last.body());
    }

    /**
     * Registers (or re-registers) the per-run task (phase B):
     * {@code POST {authApi}/v1/agent/{runtime}/task/register} signed with the Ed25519 key, returning
     * the per-run {@code task_id} (or decrypting an encrypted one - not needed for the common path).
     *
     * @param record the identity record; its {@code agentPrivateKeyPkcs8Base64} and
     *     {@code agentRuntimeId} are required.
     * @return a copy of the record carrying the fresh task id.
     */
    public AgentIdentityRecord registerTask(final AgentIdentityRecord record) {
        return registerTask(record, null);
    }

    /** Cancellation-aware task registration. */
    public AgentIdentityRecord registerTask(final AgentIdentityRecord record, final Predicate<Boolean> cancelled) {
        checkCancelled(cancelled);
        if (record == null
                || record.agentPrivateKeyPkcs8Base64() == null
                || record.agentPrivateKeyPkcs8Base64().isBlank()) {
            throw new IllegalArgumentException("Agent identity record needs a private key to register a task");
        }
        if (record.agentRuntimeId() == null || record.agentRuntimeId().isBlank()) {
            throw new IllegalArgumentException("Agent identity record needs an agent_runtime_id to register a task");
        }
        final var timestamp = timestamp();
        final var signature =
                Ed25519Util.sign(record.agentPrivateKeyPkcs8Base64(), record.agentRuntimeId() + ":" + timestamp);
        final var body = mapper.toString(new RegisterTaskRequest(signature, timestamp));
        checkCancelled(cancelled);
        final var response = send(HttpRequest.newBuilder()
                .timeout(requestTimeout)
                .uri(URI.create(config.authApiBaseUrl() + "/v1/agent/" + record.agentRuntimeId() + "/task/register"))
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
                .build());
        checkCancelled(cancelled);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Agent task registration failed with HTTP " + response.statusCode() + ": " + response.body());
        }
        final var parsed = parse(response.body());
        final var taskId = string(parsed, "task_id") != null ? string(parsed, "task_id") : string(parsed, "taskId");
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("Agent task registration omitted a task id: " + response.body());
        }
        return record.withTaskId(taskId);
    }

    /**
     * Runs the complete two-phase enterprise enrollment for a ChatGPT/Codex session: derives the
     * account routing metadata from the id_token (defaulting to the default organization when no
     * workspace is pinned, mirroring codex's {@code forced_chatgpt_workspace_id} being optional),
     * generates a fresh Ed25519 key, registers the device identity (agent runtime) with the auth API,
     * then registers a run task and returns the resulting durable {@link AgentIdentityRecord}.
     *
     * <p>This is the shared primitive used both by the lazy request-time enrollment ({@code
     * io.yupiik.ia.agent.core.llm.CodexAuthTemplateHelper}) and by the eager {@code /codex login
     * --enterprise} force path.</p>
     *
     * @param accessToken the ChatGPT OAuth access token used to authenticate the identity registration.
     * @param idToken the raw id token (bearer of the account metadata claims); may be null/blank.
     * @param fallbackAccountId the account id to use when the id token does not carry one.
     * @param forcedWorkspaceId an organization/workspace id to pin the enrollment to, or null/blank to
     *     use the account's default organization.
     * @return the newly enrolled identity record.
     */
    public AgentIdentityRecord enroll(
            final String accessToken,
            final String idToken,
            final String fallbackAccountId,
            final String forcedWorkspaceId) {
        return enroll(accessToken, idToken, fallbackAccountId, forcedWorkspaceId, null);
    }

    /**
     * Cancellation-aware two-phase enrollment. Requests run on the calling thread so its owner can
     * interrupt real transport work; a transport ignoring interruption is checked again on return.
     * Remote registration already accepted by the issuer cannot be rolled back here.
     */
    public AgentIdentityRecord enroll(
            final String accessToken,
            final String idToken,
            final String fallbackAccountId,
            final String forcedWorkspaceId,
            final Predicate<Boolean> cancelled) {
        checkCancelled(cancelled);
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("ChatGPT/Codex login produced no access token; cannot enroll");
        }
        final var claims =
                idToken == null || idToken.isBlank() ? null : CodexOAuthClient.parseIdTokenClaims(idToken, mapper);
        String accountId = claims != null && claims.accountId() != null ? claims.accountId() : fallbackAccountId;
        if (forcedWorkspaceId != null && !forcedWorkspaceId.isBlank()) {
            if (!hasOrganization(idToken, forcedWorkspaceId)) {
                throw new IllegalStateException("Account is not a member of workspace '" + forcedWorkspaceId
                        + "'; cannot pin the enterprise enrollment to it");
            }
            accountId = forcedWorkspaceId;
        }
        final String chatgptUserId = claims == null ? null : claims.chatgptUserId();
        final String planType = claims == null ? null : claims.planType();
        final boolean fedramp = claims != null && claims.chatgptAccountIsFedramp();

        checkCancelled(cancelled);
        final var key = Ed25519Util.generate();
        final String runtimeId = registerAgentRuntime(accessToken, key, fedramp, cancelled);
        checkCancelled(cancelled);
        final AgentIdentityRecord record = registerTask(
                new AgentIdentityRecord(
                        runtimeId,
                        key.privateKeyPkcs8Base64(),
                        accountId,
                        chatgptUserId,
                        claims == null ? null : claims.email(),
                        planType,
                        fedramp,
                        null),
                cancelled);
        checkCancelled(cancelled);
        if (record.needsTaskRegistration()) {
            throw new IllegalStateException("Agent identity enrollment produced no run task id");
        }
        return record;
    }

    private static void checkCancelled(final Predicate<Boolean> cancelled) {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.test(Boolean.TRUE))) {
            throw new CancellationException("Agent identity enrollment cancelled");
        }
    }

    /** True when the id-token payload lists {@code workspaceId} among its organizations. */
    private boolean hasOrganization(final String idToken, final String workspaceId) {
        if (idToken == null || idToken.isBlank()) {
            return false;
        }
        try {
            final var parts = idToken.split("\\.");
            if (parts.length < 2) {
                return false;
            }
            final byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            final IdTokenPayload payload =
                    mapper.fromString(IdTokenPayload.class, new String(decoded, StandardCharsets.UTF_8));
            return payload != null
                    && payload.organizations() != null
                    && payload.organizations().stream().anyMatch(org -> workspaceId.equals(org.id()));
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /**
     * Builds the {@code Authorization} header value for a per-request codex backend call:
     * {@code AgentAssertion {"agent_runtime_id":...,"task_id":...,"timestamp":...,"signature":...}}.
     * See {@code codex-rs/model-provider/src/auth.rs AuthProvider::add_auth_headers}.
     *
     * @param record the enrolled identity; must carry a task id.
     */
    public String assertionHeader(final AgentIdentityRecord record) {
        if (record == null || record.taskId() == null || record.taskId().isBlank()) {
            throw new IllegalStateException("No run task id; register the agent run task before asserting");
        }
        final var timestamp = timestamp();
        final var signature = Ed25519Util.sign(
                record.agentPrivateKeyPkcs8Base64(), record.agentRuntimeId() + ":" + record.taskId() + ":" + timestamp);
        final Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("agent_runtime_id", record.agentRuntimeId());
        envelope.put("task_id", record.taskId());
        envelope.put("timestamp", timestamp);
        envelope.put("signature", signature);
        return "AgentAssertion " + mapper.toString(envelope);
    }

    /** The {@code running_location} of the {@code ABOM}: {@code "<harness>-<os>"}. */
    private String runningLocation() {
        return config.agentHarnessId() + "-"
                + System.getProperty("os.name", "unknown")
                        .toLowerCase(Locale.ROOT)
                        .replace(' ', '_');
    }

    private static String timestamp() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
    }

    private static boolean retryable(final int status) {
        return status == 429 || status >= 500;
    }

    // ----- HTTP and JSON plumbing (mirrors CodexOAuthClient) -----

    private HttpResponse<String> send(final HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException("Agent identity request failed: " + e.getMessage(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Agent identity request", e);
        } catch (final CompletionException e) {
            final var cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Agent identity request failed: " + cause.getMessage(), cause);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(final String body) {
        final Object o = mapper.fromString(Map.class, body);
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static String string(final Map<String, Object> map, final String key) {
        final Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    /** The {@code abom} payload of the agent registration request. */
    @JsonModel
    public record AgentBillOfMaterials(String agent_version, String agent_harness_id, String running_location) {}

    /** The {@code POST /v1/agent/register} body. */
    @JsonModel
    public record RegisterAgentRequest(
            AgentBillOfMaterials abom, String agent_public_key, List<String> capabilities, Long ttl) {}

    /** The {@code POST /v1/agent/{id}/task/register} body. */
    @JsonModel
    public record RegisterTaskRequest(String signature, String timestamp) {}
}
