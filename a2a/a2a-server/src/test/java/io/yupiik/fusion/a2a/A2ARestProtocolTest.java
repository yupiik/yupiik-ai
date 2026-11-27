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
package io.yupiik.fusion.a2a;

import static io.yupiik.fusion.a2a.test.Awaits.await;
import static io.yupiik.fusion.a2a.test.Bodies.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.client.A2AClient;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.a2a.model.SendMessageResponse;
import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.protocol.A2AProtocol;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The HTTP+JSON binding: the same operations reached with plain HTTP, which must behave exactly like the JSON-RPC one -
 * section 5.1 of the specification.
 */
@FusionSupport
class A2ARestProtocolTest {
    @Test
    void sendMessage(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var response =
                await(client.rest("POST", "/message:send", SendMessageRequest.of(Message.user("hello rest"))));

        assertEquals(200, response.statusCode());
        assertTrue(
                response.headers().firstValue("content-type").orElseThrow().startsWith(A2AProtocol.MEDIA_TYPE),
                response.headers().map()::toString);
        final var payload = jsons.fromString(SendMessageResponse.class, response.body());
        assertEquals(TaskState.TASK_STATE_COMPLETED, payload.task().status().state());
        assertEquals(
                "hello rest", payload.task().artifacts().get(0).parts().get(0).text());
    }

    @Test
    void getTask(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var created = await(client.sendMessage("hello")).task();

        final var response = await(client.rest("GET", "/tasks/" + created.id() + "?historyLength=0", null));
        assertEquals(200, response.statusCode());
        contains(response.body(), created.id(), "TASK_STATE_COMPLETED");
        assertTrue(!response.body().contains("\"history\""), response.body());
    }

    @Test
    void listTasks(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var created = await(client.sendMessage("hello")).task();

        final var response =
                await(client.rest("GET", "/tasks?contextId=" + created.contextId() + "&includeArtifacts=true", null));
        assertEquals(200, response.statusCode());
        contains(response.body(), created.id(), "\"totalSize\":1", "\"nextPageToken\":\"\"", "\"artifacts\"");
    }

    @Test
    void cancelTask(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var created = await(client.sendMessage("input:waiting")).task();

        // the body is optional on a cancel, only the path matters
        final var response = await(client.rest("POST", "/tasks/" + created.id() + ":cancel", null));
        assertEquals(200, response.statusCode());
        contains(response.body(), "TASK_STATE_CANCELED");
    }

    @Test
    void pushNotificationConfigs(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var task = await(client.sendMessage("input:waiting")).task();
        final var path = "/tasks/" + task.id() + "/pushNotificationConfigs";

        final var created =
                await(client.rest("POST", path, TaskPushNotificationConfig.of("https://client.example.com/hook")));
        assertEquals(200, created.statusCode());
        final var config = jsons.fromString(TaskPushNotificationConfig.class, created.body());
        assertNotNull(config.id());
        assertEquals(task.id(), config.taskId());

        assertEquals(200, await(client.rest("GET", path, null)).statusCode());
        contains(await(client.rest("GET", path, null)).body(), config.id());

        assertEquals(
                200, await(client.rest("GET", path + '/' + config.id(), null)).statusCode());
        assertEquals(
                200,
                await(client.rest("DELETE", path + '/' + config.id(), null)).statusCode());
        assertTrue(!await(client.rest("GET", path, null)).body().contains(config.id()));
    }

    @Test
    void errorPayloadIsAGrpcStatus(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var response = await(new A2AClient(agent, http, jsons).rest("GET", "/tasks/i-made-it-up", null));

        assertEquals(404, response.statusCode());
        // section 11.6: a google.rpc.Status with an ErrorInfo detail telling which A2A error it is
        contains(
                response.body(),
                "\"code\":404",
                "\"status\":\"NOT_FOUND\"",
                "\"message\":\"Task not found\"",
                "type.googleapis.com/google.rpc.ErrorInfo",
                "\"reason\":\"TASK_NOT_FOUND\"",
                "\"domain\":\"a2a-protocol.org\"");
    }

    @Test
    void unknownRoute(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        assertEquals(
                404,
                await(new A2AClient(agent, http, jsons).rest("GET", "/nope", null))
                        .statusCode());
    }

    @Test
    void versionIsEnforcedOnThisBindingToo(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        // no version at all resolves to 0.3, which this agent does not serve
        final var missing =
                await(new A2AClient(agent, http, jsons).version(null).rest("GET", "/tasks", null));
        assertEquals(400, missing.statusCode());
        contains(missing.body(), "\"reason\":\"VERSION_NOT_SUPPORTED\"", "0.3");

        // and the query parameter form is honored, here on a GET where it is the natural place to put it
        assertEquals(
                200,
                await(new A2AClient(agent, http, jsons).version(null).rest("GET", "/tasks?A2A-Version=1.0", null))
                        .statusCode());
    }

    @Test
    void streaming(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var events = new CopyOnWriteArrayList<StreamResponse>();
        await(new A2AClient(agent, http, jsons)
                .restStreaming("/message:stream", SendMessageRequest.of(Message.user("chunks:2")), events::add));

        // the HTTP+JSON binding sends bare StreamResponse events, with no JSON-RPC envelope
        assertNotNull(events.get(0).task());
        assertEquals(
                2,
                events.stream()
                        .map(StreamResponse::artifactUpdate)
                        .filter(Objects::nonNull)
                        .count());
        assertEquals(
                TaskState.TASK_STATE_COMPLETED,
                events.stream()
                        .map(StreamResponse::statusUpdate)
                        .filter(Objects::nonNull)
                        .map(it -> it.status().state())
                        .toList()
                        .getLast());
    }

    @Test
    void subscribeUsesPost(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var task = await(client.sendMessage("hello")).task();

        // the task is terminal, so the subscription is refused - which is enough to prove the route is wired
        final var response = await(client.rest("POST", "/tasks/" + task.id() + ":subscribe", null));
        assertEquals(400, response.statusCode());
        contains(response.body(), "\"reason\":\"UNSUPPORTED_OPERATION\"");
    }
}
