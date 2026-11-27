/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.model;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wire model factories and the little logic they carry.
 * <p>
 * Most of it looks obvious, and it is exactly why it is pinned: {@code text}, {@code raw}, {@code url} and {@code data}
 * are the branches of a proto {@code oneof}, so a factory setting one component too many - or the wrong media type -
 * produces a payload another implementation rejects, and nothing else in the code would notice.
 */
class ModelTest {
    @Test
    void textParts() {
        final var plain = Part.text("hello");
        assertEquals("hello", plain.text());
        assertEquals("text/plain", plain.mediaType());
        assertNull(plain.raw());
        assertNull(plain.url());
        assertNull(plain.data());

        final var markdown = Part.text("text/markdown", "# hello");
        assertEquals("# hello", markdown.text());
        assertEquals("text/markdown", markdown.mediaType());
    }

    @Test
    void filePartsAreBase64Encoded() {
        final var inline = Part.raw("application/pdf", "hello".getBytes(UTF_8), "report.pdf");

        // the specification carries an inline file as base64, so the bytes must not leak as is
        assertEquals("aGVsbG8=", inline.raw());
        assertEquals("report.pdf", inline.filename());
        assertEquals("application/pdf", inline.mediaType());
        assertArrayEquals("hello".getBytes(UTF_8), inline.rawBytes());

        final var byReference = Part.url("image/png", "https://example.com/i.png", "i.png");
        assertEquals("https://example.com/i.png", byReference.url());
        assertNull(byReference.raw());
        assertNull(byReference.rawBytes(), "a part carrying no inline file has no bytes to decode");
    }

    @Test
    void dataParts() {
        final var part = Part.data(Map.of("a", 1));

        assertEquals(Map.of("a", 1), part.data());
        assertEquals("application/json", part.mediaType());
        assertNull(part.text());
    }

    @Test
    void messageFactoriesSetTheRole() {
        assertEquals(Role.ROLE_AGENT, Message.agent("hi").role());
        assertEquals(Role.ROLE_USER, Message.user("hi").role());
        assertNotNull(Message.user("hi").messageId(), "the author generates the identifier");
        assertEquals(2, Message.agent("a", "b").parts().size());
    }

    @Test
    void messageText() {
        assertEquals("", new Message(null, null, null, Role.ROLE_USER, null, null, null, null).text());
        assertEquals("", Message.of(Role.ROLE_USER, List.of()).text());
        // the data parts have no text, they must not turn into empty lines
        assertEquals(
                "a\nb",
                Message.of(Role.ROLE_USER, List.of(Part.text("a"), Part.data(Map.of()), Part.text("b")))
                        .text());
    }

    @Test
    void messageData() {
        assertNull(new Message(null, null, null, Role.ROLE_USER, null, null, null, null).data());
        assertNull(Message.of(Role.ROLE_USER, List.of(Part.text("a"))).data());
        // the first data part wins, the text ones are skipped
        assertEquals(
                Map.of("first", true),
                Message.of(
                                Role.ROLE_USER,
                                List.of(
                                        Part.text("a"),
                                        Part.data(Map.of("first", true)),
                                        Part.data(Map.of("second", true))))
                        .data());
    }

    @Test
    void messageBinding() {
        final var message = Message.user("hi");

        final var bound = message.boundTo("c-1", "t-1");
        assertEquals("c-1", bound.contextId());
        assertEquals("t-1", bound.taskId());
        assertEquals(message.messageId(), bound.messageId(), "the identifier of the author is kept");

        // already bound to the very same pair: no copy, so the identity a caller holds is stable
        assertSame(bound, bound.boundTo("c-1", "t-1"));
        assertSame(message, message.boundTo(null, null));

        // a message without an identifier gets one, else two of them could not be told apart in the history
        final var anonymous = new Message(null, null, null, Role.ROLE_AGENT, List.of(Part.text("x")), null, null, null);
        assertNotNull(anonymous.boundTo("c-1", null).messageId());
    }

    @Test
    void artifacts() {
        final var text = Artifact.text("greeting", "hello");
        assertNotNull(text.artifactId());
        assertEquals("greeting", text.name());
        assertEquals("hello", text.parts().get(0).text());

        final var data = Artifact.data("report", Map.of("city", "Paris"));
        assertEquals("application/json", data.parts().get(0).mediaType());
        assertEquals(Map.of("city", "Paris"), data.parts().get(0).data());

        // the identifier is what correlates the chunks of a streamed artifact, so it must be settable
        final var renamed = text.withId("chunked");
        assertEquals("chunked", renamed.artifactId());
        assertEquals(text.name(), renamed.name());
        assertEquals(text.parts(), renamed.parts());
    }

    @Test
    void taskStates() {
        assertEquals(
                List.of(
                        TaskState.TASK_STATE_COMPLETED, TaskState.TASK_STATE_FAILED,
                        TaskState.TASK_STATE_CANCELED, TaskState.TASK_STATE_REJECTED),
                Arrays.stream(TaskState.values()).filter(TaskState::isTerminal).toList());
        assertEquals(
                List.of(TaskState.TASK_STATE_INPUT_REQUIRED, TaskState.TASK_STATE_AUTH_REQUIRED),
                Arrays.stream(TaskState.values())
                        .filter(TaskState::isInterrupted)
                        .toList());
        // a blocking SendMessage returns on both, they are the states a task will not leave on its own
        assertEquals(
                6,
                Arrays.stream(TaskState.values()).filter(TaskState::isSettled).count());
        assertFalse(TaskState.TASK_STATE_WORKING.isSettled());
        assertFalse(TaskState.TASK_STATE_SUBMITTED.isSettled());
        assertFalse(TaskState.TASK_STATE_UNSPECIFIED.isSettled());
    }

    @Test
    void streamEventsCarryExactlyOneComponent() {
        final var task = new Task(
                "t",
                "c",
                TaskStatus.of(TaskState.TASK_STATE_WORKING, Instant.parse("2026-08-01T09:32:46Z")),
                null,
                null,
                null);
        final var event = StreamResponse.of(task);
        assertSame(task, event.task());
        assertNull(event.message());
        assertNull(event.statusUpdate());
        assertNull(event.artifactUpdate());

        assertNotNull(StreamResponse.of(Message.agent("hi")).message());
        assertNotNull(StreamResponse.of(new TaskStatusUpdateEvent("t", "c", null, null))
                .statusUpdate());
        assertNotNull(
                StreamResponse.of(new TaskArtifactUpdateEvent("t", "c", Artifact.text("a", "b"), false, true, null))
                        .artifactUpdate());
    }

    @Test
    void onlyASettledStatusOrAMessageEndsAStream() {
        assertTrue(StreamResponse.of(Message.agent("bye")).isLast());
        assertTrue(status(TaskState.TASK_STATE_COMPLETED).isLast());
        // an interrupted task cannot progress without a new request either, so the stream must not stay open
        assertTrue(status(TaskState.TASK_STATE_INPUT_REQUIRED).isLast());
        assertFalse(status(TaskState.TASK_STATE_WORKING).isLast());

        assertFalse(
                StreamResponse.of(new TaskArtifactUpdateEvent("t", "c", Artifact.text("a", "b"), false, false, null))
                        .isLast());
        assertFalse(StreamResponse.of(new TaskStatusUpdateEvent("t", "c", null, null))
                .isLast());
        assertFalse(StreamResponse.of(new TaskStatusUpdateEvent("t", "c", new TaskStatus(null, null, null), null))
                .isLast());
        assertFalse(
                StreamResponse.of(new Task(
                                "t",
                                "c",
                                TaskStatus.of(TaskState.TASK_STATE_COMPLETED, Instant.parse("2026-08-01T09:32:46Z")),
                                null,
                                null,
                                null))
                        .isLast(),
                "the initial task event opens the stream, it never closes it");
    }

    @Test
    void authorizationHeaderValue() {
        assertEquals("Bearer s3cr3t", new AuthenticationInfo("Bearer", "s3cr3t").authorization());
        // a scheme without credentials is legitimate - Negotiate for example - so it is sent alone
        assertEquals("Negotiate", new AuthenticationInfo("Negotiate", null).authorization());
        assertEquals("Negotiate", new AuthenticationInfo("Negotiate", " ").authorization());
        assertNull(new AuthenticationInfo(null, "s3cr3t").authorization(), "there is nothing to send without a scheme");
        assertNull(new AuthenticationInfo(" ", "s3cr3t").authorization());
    }

    @Test
    void securitySchemesAreAOneOf() {
        final var apiKey = APIKeySecurityScheme.header("X-Api-Key");
        assertEquals(APIKeySecurityScheme.Location.header, apiKey.location());
        assertEquals("X-Api-Key", apiKey.name());
        assertSame(apiKey, SecurityScheme.apiKey(apiKey).apiKeySecurityScheme());

        final var httpAuth = new HTTPAuthSecurityScheme(null, "Basic", null);
        assertSame(httpAuth, SecurityScheme.httpAuth(httpAuth).httpAuthSecurityScheme());

        final var oauth2 = new OAuth2SecurityScheme(null, null, "https://example.com/.well-known/oauth");
        assertSame(oauth2, SecurityScheme.oauth2(oauth2).oauth2SecurityScheme());

        final var oidc = new OpenIdConnectSecurityScheme(null, "https://example.com/.well-known/openid-configuration");
        assertSame(oidc, SecurityScheme.openIdConnect(oidc).openIdConnectSecurityScheme());

        final var mtls = new MutualTlsSecurityScheme("client certificates");
        assertSame(mtls, SecurityScheme.mutualTls(mtls).mtlsSecurityScheme());

        final var bearer = SecurityScheme.bearer("JWT");
        assertEquals("Bearer", bearer.httpAuthSecurityScheme().scheme());
        assertEquals("JWT", bearer.httpAuthSecurityScheme().bearerFormat());
        assertNull(bearer.apiKeySecurityScheme(), "exactly one component of the oneof is set");
    }

    @Test
    void oauthFlowsAreAOneOf() {
        final var code = new AuthorizationCodeOAuthFlow(
                "https://example.com/authorize", "https://example.com/token", null, Map.of("read", "Read"), true);
        assertSame(code, OAuthFlows.authorizationCode(code).authorizationCode());
        assertTrue(code.pkceRequired());

        final var credentials = new ClientCredentialsOAuthFlow("https://example.com/token", null, Map.of());
        assertSame(credentials, OAuthFlows.clientCredentials(credentials).clientCredentials());

        final var device =
                new DeviceCodeOAuthFlow("https://example.com/device", "https://example.com/token", null, Map.of());
        assertSame(device, OAuthFlows.deviceCode(device).deviceCode());

        // the deprecated flows have no factory, they are only reachable through the canonical constructor
        final var deprecated = new OAuthFlows(
                null,
                null,
                new ImplicitOAuthFlow("https://example.com/authorize", null, Map.of()),
                new PasswordOAuthFlow("https://example.com/token", null, Map.of()),
                null);
        assertNotNull(deprecated.implicit());
        assertNotNull(deprecated.password());
    }

    @Test
    void protoCannotNestARepeatedFieldInAMapValue() {
        // hence the wrapper: the scopes of a requirement are {"schemes": {"oauth": {"list": [...]}}}
        assertEquals(List.of("read", "write"), StringList.of("read", "write").list());
        assertEquals(
                List.of("read"),
                SecurityRequirement.of("oauth", "read").schemes().get("oauth").list());
        assertEquals(
                List.of(),
                SecurityRequirement.of("bearer").schemes().get("bearer").list());
    }

    @Test
    void extensions() {
        final var extension = AgentExtension.of("https://example.com/x", "What it does.", true);

        assertEquals("https://example.com/x", extension.uri());
        assertTrue(extension.required());
        assertNull(extension.params());
    }

    @Test
    void interfacesAndProvider() {
        final var jsonRpc = AgentInterface.of("https://agent.example.com/a2a", AgentInterface.JSONRPC, "1.0");

        assertEquals("JSONRPC", jsonRpc.protocolBinding());
        assertNull(jsonRpc.tenant(), "there is nothing to route for a single agent server");
        assertEquals("Yupiik", new AgentProvider("https://www.yupiik.com", "Yupiik").organization());
    }

    @Test
    void cardCopies() {
        final var card = card();

        final var interfaces =
                List.of(AgentInterface.of("https://other.example.com/a2a", AgentInterface.JSONRPC, "1.0"));
        final var reinterfaced = card.withInterfaces(interfaces);
        assertEquals(interfaces, reinterfaced.supportedInterfaces());
        assertEquals(card.name(), reinterfaced.name());
        assertEquals(card.skills(), reinterfaced.skills());

        final var skills = List.of(AgentSkill.of("do/it", "Do it", "Does it.", List.of("demo")));
        final var reskilled = card.withSkills(skills);
        assertEquals(skills, reskilled.skills());
        assertEquals(card.supportedInterfaces(), reskilled.supportedInterfaces());
    }

    @Test
    void skillDescriptors() {
        final var skill = AgentSkill.of("do/it", "Do it", "Does it.", List.of("demo"));

        final var described =
                skill.describedBy(List.of("a"), List.of("Do it!"), List.of("text/plain"), List.of("text/plain"));
        assertEquals(List.of("a"), described.tags());
        assertEquals(List.of("Do it!"), described.examples());
        assertEquals("do/it", described.id());

        assertEquals("Doer", skill.named("Doer").name());
        assertEquals("do/it", skill.named("Doer").id(), "the identifier is the method name, renaming must not move it");
    }

    @Test
    void statusIsTimestamped() {
        final var status = TaskStatus.of(TaskState.TASK_STATE_WORKING, Instant.parse("2026-08-01T09:32:46Z"));
        assertEquals(TaskState.TASK_STATE_WORKING, status.state());
        assertNotNull(status.timestamp());
        assertNull(status.message());

        final var explained = TaskStatus.of(
                TaskState.TASK_STATE_FAILED, Message.agent("boom"), Instant.parse("2026-08-01T09:32:46Z"));
        assertEquals("boom", explained.message().text());
    }

    @Test
    void pushConfigurations() {
        final var config = TaskPushNotificationConfig.of("https://client.example.com/hook");
        assertNull(config.id());
        assertNull(config.taskId());

        final var bound = config.boundTo("c-1", "t-1");
        assertEquals("c-1", bound.id());
        assertEquals("t-1", bound.taskId());
        assertEquals(config.url(), bound.url());
    }

    @Test
    void httpErrorPayloadIsAGrpcStatus() {
        final var error = HttpErrorResponse.of(404, "NOT_FOUND", "Task not found", List.of(Map.of("@type", "x")))
                .error();

        assertEquals(404, error.code());
        assertEquals("NOT_FOUND", error.status());
        assertEquals("Task not found", error.message());
        assertEquals(1, error.details().size());
    }

    @Test
    void sendMessageResponseIsAOneOf() {
        final var task = new Task(
                "t",
                "c",
                TaskStatus.of(TaskState.TASK_STATE_WORKING, Instant.parse("2026-08-01T09:32:46Z")),
                null,
                null,
                null);

        assertSame(task, SendMessageResponse.of(task).task());
        assertNull(SendMessageResponse.of(task).message());
        assertNotNull(SendMessageResponse.of(Message.agent("hi")).message());
        assertNull(SendMessageResponse.of(Message.agent("hi")).task());
    }

    @Test
    void jsonRpcStreamEnvelope() {
        final var envelope = JsonRpcStreamMessage.of(42, StreamResponse.of(Message.agent("hi")));

        assertEquals("2.0", envelope.jsonrpc());
        assertEquals(42, envelope.id());
        assertEquals("hi", envelope.result().message().text());
    }

    @Test
    void sendMessageRequestConvenience() {
        final var message = Message.user("hi");
        final var request = SendMessageRequest.of(message);

        assertSame(message, request.message());
        assertNull(request.tenant());
        assertNull(request.configuration());
        assertNull(request.metadata());
    }

    private static StreamResponse status(final TaskState state) {
        return StreamResponse.of(
                new TaskStatusUpdateEvent("t", "c", TaskStatus.of(state, Instant.parse("2026-08-01T09:32:46Z")), null));
    }

    private static AgentCard card() {
        return new AgentCard(
                "agent",
                "An agent.",
                List.of(AgentInterface.of("https://agent.example.com/a2a", AgentInterface.JSONRPC, "1.0")),
                new AgentProvider(null, "Yupiik"),
                "1.0.0",
                null,
                new AgentCapabilities(true, true, null, false),
                Map.of("bearer", SecurityScheme.bearer("JWT")),
                List.of(SecurityRequirement.of("bearer")),
                List.of("text/plain"),
                List.of("text/plain"),
                List.of(),
                null,
                null);
    }
}
