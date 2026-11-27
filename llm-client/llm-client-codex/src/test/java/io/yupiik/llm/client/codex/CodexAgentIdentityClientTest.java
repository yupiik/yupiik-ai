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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.codex.model.AgentIdentityRecord;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Validates {@link CodexAgentIdentityClient} against the exact Agent Identity wire format used by
 * {@code openai/codex} (see {@code codex-rs/agent-identity/src/lib.rs} and
 * {@code codex-rs/model-provider/src/auth.rs}). A mock auth API on loopback impersonates the
 * {@code /v1/agent/register} and {@code /v1/agent/{id}/task/register} endpoints; the tests assert
 * the request method/path/headers/body and, crucially, that the Ed25519 signatures yca produces
 * actually verify against the ssh public key it advertises - the same guarantee codex relies on.
 */
@ExtendWith(TestJsonMapper.class)
class CodexAgentIdentityClientTest {
    private static final Pattern TASK_REGISTRATION_PATH = Pattern.compile(".*/v1/agent/[^/]+/task/register");

    private HttpServer authApi;
    private String authApiUrl;
    private ExtendedHttpClient client;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final List<Captured> captured = new ArrayList<>();

    /** What the client actually sent, read inside the handler before the exchange is closed. */
    private record Captured(String method, String path, String auth, String body, String xFedramp) {}

    @BeforeEach
    void start() throws IOException {
        authApi = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        authApi.createContext("/", this::captureAndDispatch);
        authApi.start();
        authApiUrl = "http://localhost:" + authApi.getAddress().getPort();
        client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                .setDelegate(HttpClient.newBuilder().build()));
    }

    @AfterEach
    void stop() {
        if (authApi != null) {
            authApi.stop(0);
            authApi = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private void captureAndDispatch(final HttpExchange exchange) throws IOException {
        final var path = exchange.getRequestURI().getPath();
        lastPath.set(path);
        final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        final var headers = exchange.getRequestHeaders();
        captured.add(new Captured(
                exchange.getRequestMethod(),
                path,
                headers.getFirst("authorization"),
                body,
                headers.getFirst("X-OpenAI-Fedramp")));
        if (path.endsWith("/v1/agent/register")) {
            respond(exchange, 200, "{\"agent_runtime_id\":\"runtime-abc123\"}");
        } else if (TASK_REGISTRATION_PATH.matcher(path).matches()) {
            respond(exchange, 200, "{\"task_id\":\"task-1\"}");
        } else {
            respond(exchange, 404, "{}");
        }
    }

    private void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private CodexAgentIdentityClient client(final JsonMapper mapper) {
        return new CodexAgentIdentityClient(
                mapper, client, new CodexAgentIdentityConfig(authApiUrl, authApiUrl, "0.200.0", "codex-cli"));
    }

    @Test
    void registerAgentRuntimeWireFormat(final JsonMapper mapper) throws Exception {
        final var codex = client(mapper);
        final var key = Ed25519Util.generate();

        final String runtimeId = codex.registerAgentRuntime("access-token-1", key, false);

        assertEquals("/v1/agent/register", lastPath.get(), "phase A path");
        final var request = captured.get(0);
        assertEquals("POST", request.method());
        assertEquals("Bearer access-token-1", request.auth());
        final var body = request.body();
        assertTrue(body.contains("\"responsesapi\""), "capabilities must advertise responsesapi");
        assertFalse(body.contains("ttl"), "ttl must be omitted by default (matches codex ttl: None)");
        assertTrue(body.contains("\"agent_public_key\":\"" + key.publicKeySsh()), "ssh key echoed in body");
        assertNotNull(runtimeId);
        assertTrue(runtimeId.startsWith("runtime-"), "agent_runtime_id echoed back: " + runtimeId);
    }

    @Test
    void registerAgentRuntimeSendsFedrampHeader(final JsonMapper mapper) throws Exception {
        final var codex = client(mapper);
        final var key = Ed25519Util.generate();
        codex.registerAgentRuntime("access-token-2", key, true);
        assertEquals("true", captured.get(0).xFedramp());
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerTaskSignsRuntimeIdAndTimestamp(final JsonMapper mapper) throws Exception {
        final var codex = client(mapper);
        final var key = Ed25519Util.generate();
        final String runtimeId = codex.registerAgentRuntime("access-token-3", key, false);

        final AgentIdentityRecord record = new AgentIdentityRecord(
                runtimeId, key.privateKeyPkcs8Base64(), "acc-1", "user-1", "a@b.c", "pro", false, null);
        final var done = codex.registerTask(record);

        final String taskId = done.taskId();
        assertEquals("task-1", taskId, "echoed task id");
        // index 1 = the registerTask exchange (index 0 was registerAgentRuntime)
        final var request = captured.get(1);
        assertEquals("POST", request.method());
        assertEquals("/v1/agent/" + runtimeId + "/task/register", request.path(), "phase B path");

        final Map<String, Object> parsed = mapper.fromString(Map.class, request.body());
        final String timestamp = String.valueOf(parsed.get("timestamp"));
        final String signature = String.valueOf(parsed.get("signature"));
        // the signature must be a valid Ed25519 signature of "<runtime>:<timestamp>" - the same
        // payload codex signs (sign_task_registration_payload)
        assertTrue(
                Ed25519Util.verify(key.privateKeyPkcs8Base64(), runtimeId + ":" + timestamp, signature),
                "task registration signature must verify against the ssh public key");
    }

    @Test
    @SuppressWarnings("unchecked")
    void assertionHeaderSignsRuntimeTaskTimestamp(final JsonMapper mapper) throws Exception {
        final var codex = client(mapper);
        final var key = Ed25519Util.generate();
        final AgentIdentityRecord record = new AgentIdentityRecord(
                "runtime-x", key.privateKeyPkcs8Base64(), "acc-1", "user-1", null, "business", false, "task-9");

        final String header = codex.assertionHeader(record);

        assertTrue(header.startsWith("AgentAssertion "), "header: " + header);
        final String envelope = header.substring("AgentAssertion ".length());
        final Map<String, Object> parsed = mapper.fromString(Map.class, envelope);
        assertEquals("runtime-x", parsed.get("agent_runtime_id"));
        assertEquals("task-9", parsed.get("task_id"));
        final String timestamp = String.valueOf(parsed.get("timestamp"));
        final String signature = String.valueOf(parsed.get("signature"));
        // codex signs "<runtime>:<task_id>:<timestamp>" (sign_agent_assertion_payload)
        assertTrue(
                Ed25519Util.verify(key.privateKeyPkcs8Base64(), "runtime-x:task-9:" + timestamp, signature),
                "assertion signature must verify against the ssh public key");
    }

    @Test
    void assertionHeaderRequiresTaskId(final JsonMapper mapper) {
        final var codex = client(mapper);
        final var key = Ed25519Util.generate();
        final AgentIdentityRecord record = new AgentIdentityRecord(
                "runtime-y", key.privateKeyPkcs8Base64(), "acc-1", "user-1", null, null, false, null);
        assertThrows(IllegalStateException.class, () -> codex.assertionHeader(record));
    }

    @Test
    void enrollmentEndToEndProducesUsableRecord(final JsonMapper mapper) throws Exception {
        final var codex = client(mapper);
        final var key = Ed25519Util.generate();
        final String runtimeId = codex.registerAgentRuntime("access-token-4", key, false);
        final AgentIdentityRecord registered = new AgentIdentityRecord(
                runtimeId, key.privateKeyPkcs8Base64(), "acc-1", "user-1", "a@b.c", "enterprise", true, null);
        final AgentIdentityRecord done = codex.registerTask(registered);

        assertEquals("task-1", done.taskId());
        assertFalse(done.needsTaskRegistration(), "after registration a task id is present");
        assertNotNull(codex.assertionHeader(done));
        assertTrue(done.chatgptAccountIsFedramp());
    }

    @Test
    void enrollUsesDefaultOrganizationWhenNoWorkspacePinned(final JsonMapper mapper) throws Exception {
        // the shared enrollment primitive picks the default org from the id_token when no workspace
        // is pinned (matching codex's optional forced_chatgpt_workspace_id)
        final String jwt = headerDot("{\"email\":\"a@b.c\",\"organizations\":["
                        + "{\"id\":\"default-org\",\"is_default\":true,\"is_active\":true},"
                        + "{\"id\":\"other-org\",\"is_default\":false,\"is_active\":false}]}")
                + ".sig";
        final var codex = client(mapper);
        final var record = codex.enroll("access-token-5", jwt, "fallback-acc", null);
        assertEquals("default-org", record.accountId(), "default org must win over the OAuth fallback");
        assertEquals("task-1", record.taskId(), "enroll must complete the run task registration");
        assertFalse(record.needsTaskRegistration());
        assertFalse(record.needsEnrollment(), "a completed enroll has a runtime id");
        assertEquals(2, captured.size(), "enroll runs register then registerTask");
        assertTrue(captured.get(0).path().endsWith("/v1/agent/register"));
        assertTrue(captured.get(1).path().endsWith("/task/register"));
    }

    @Test
    void enrollPinsForcedWorkspaceAndRejectsNonMember(final JsonMapper mapper) throws Exception {
        final String jwt =
                headerDot("{\"organizations\":[{\"id\":\"member-org\",\"is_default\":false,\"is_active\":false}]}")
                        + ".sig";
        final var codex = client(mapper);
        // a workspace the account belongs to: pinned account id
        final var pinned = codex.enroll("access-token-6", jwt, "fallback", "member-org");
        assertEquals("member-org", pinned.accountId());
        // a workspace the account is NOT a member of: loud failure
        final var e = assertThrows(
                IllegalStateException.class, () -> codex.enroll("access-token-7", jwt, "fallback", "not-member"));
        assertTrue(e.getMessage().contains("not a member"), e.getMessage());
    }

    @Test
    void idTokenClaimsParsing(final JsonMapper mapper) {
        // id_token payload with the namespaced auth claims codex reads
        final String jwt = headerDot("{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acc-9\","
                        + "\"chatgpt_user_id\":\"user-9\",\"chatgpt_plan_type\":\"pro\","
                        + "\"chatgpt_account_is_fedramp\":false},\"email\":\"a@b.c\"}")
                + ".sig";
        final var claims = CodexOAuthClient.parseIdTokenClaims(jwt, mapper);
        assertEquals("acc-9", claims.accountId());
        assertEquals("user-9", claims.chatgptUserId());
        assertEquals("pro", claims.planType());
        assertFalse(claims.chatgptAccountIsFedramp());
        assertEquals("a@b.c", claims.email());
        assertFalse(claims.isWorkspacePlan(), "pro is not a workspace plan");
    }

    @Test
    void fedrampDetectionIsFormattingSensitive(final JsonMapper mapper) {
        // "chatgpt_account_is_fedramp": false WITH a space after the colon must still read as false
        final String jwt = headerDot("{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acc-9\","
                        + "\"chatgpt_user_id\":\"user-9\",\"chatgpt_plan_type\":\"enterprise\","
                        + "\"chatgpt_account_is_fedramp\": false},\"email\":\"a@b.c\"}")
                + ".sig";
        assertFalse(CodexOAuthClient.parseIdTokenClaims(jwt, mapper).chatgptAccountIsFedramp());
    }

    @Test
    void accountIdFallbackPrefersDefaultOrganization(final JsonMapper mapper) {
        // no namespaced auth claim; two orgs, the default one must win over organizations[0]
        final String jwt = headerDot("{\"email\":\"a@b.c\",\"organizations\":["
                        + "{\"id\":\"first-org\",\"is_default\":false,\"is_active\":false},"
                        + "{\"id\":\"default-org\",\"is_default\":true,\"is_active\":true}]}")
                + ".sig";
        assertEquals(
                "default-org", CodexOAuthClient.parseIdTokenClaims(jwt, mapper).accountId());
    }

    @Test
    void accountIdRegionFallsBackToFirstOrganization(final JsonMapper mapper) {
        // no namespaced auth claim and no default/active flag: fall back to the first org
        final String jwt =
                headerDot("{\"organizations\":[{\"id\":\"only-org\",\"is_default\":false,\"is_active\":false}]}")
                        + ".sig";
        assertEquals(
                "only-org", CodexOAuthClient.parseIdTokenClaims(jwt, mapper).accountId());
    }

    @Test
    void workspacePlanDetection(final JsonMapper mapper) {
        final String jwt = headerDot("{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acc-9\","
                        + "\"chatgpt_user_id\":\"user-9\",\"chatgpt_plan_type\":\"enterprise\","
                        + "\"chatgpt_account_is_fedramp\":false}}")
                + ".sig";
        assertTrue(CodexOAuthClient.parseIdTokenClaims(jwt, mapper).isWorkspacePlan());
    }

    private static String headerDot(final String jsonClaims) {
        final var e = Base64.getUrlEncoder().withoutPadding();
        return e.encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "."
                + e.encodeToString(jsonClaims.getBytes(StandardCharsets.UTF_8));
    }
}
