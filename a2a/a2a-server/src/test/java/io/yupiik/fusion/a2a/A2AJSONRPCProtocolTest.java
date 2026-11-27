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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.client.A2AClient;
import io.yupiik.fusion.a2a.client.A2AClientException;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.Part;
import io.yupiik.fusion.a2a.model.Role;
import io.yupiik.fusion.a2a.model.SendMessageConfiguration;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The JSON-RPC binding: the eleven operations, their results and the errors the specification mandates.
 */
@FusionSupport
class A2AJSONRPCProtocolTest {
    @Test
    void sendMessage(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var response = await(new A2AClient(agent, http, jsons).sendMessage("hello fusion"));

        assertNull(response.message());
        final var task = response.task();
        assertNotNull(task.id());
        assertNotNull(task.contextId());
        // SendMessage is blocking by default so the task already settled and carries its outputs
        assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state());
        assertEquals(1, task.artifacts().size());
        assertEquals("echo", task.artifacts().get(0).name());
        assertEquals("hello fusion", task.artifacts().get(0).parts().get(0).text());
        // the incoming message is in the history, bound to the task
        assertEquals(1, task.history().size());
        assertEquals(task.id(), task.history().get(0).taskId());
        assertEquals(Role.ROLE_USER, task.history().get(0).role());
    }

    @Test
    void sendMessageAnsweringAMessage(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var response = await(client.sendMessage("message:no task needed"));

        // an executor answering a message leaves no task behind at all
        assertNull(response.task());
        assertEquals(Role.ROLE_AGENT, response.message().role());
        assertEquals("no task needed", response.message().text());
        assertNull(response.message().taskId());
        assertNotNull(response.message().contextId());
    }

    @Test
    void returnImmediatelyDoesNotWait(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var response = await(client.sendMessage(new SendMessageRequest(
                null, Message.user("slow"), new SendMessageConfiguration(null, null, null, true), null)));

        final var task = response.task();
        assertTrue(
                !task.status().state().isSettled(),
                () -> "Unexpected state " + task.status().state());
        assertEquals(
                TaskState.TASK_STATE_CANCELED,
                await(client.cancelTask(task.id())).status().state());
    }

    @Test
    void getTask(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var created = await(client.sendMessage("hello")).task();

        final var fetched = await(client.getTask(created.id(), null));
        assertEquals(created.id(), fetched.id());
        assertEquals(TaskState.TASK_STATE_COMPLETED, fetched.status().state());

        // historyLength=0 asks for no history at all, the attribute must be omitted and not sent empty
        assertNull(await(client.getTask(created.id(), 0)).history());
    }

    @Test
    void getUnknownTask(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);

        final var error = assertThrows(A2AClientException.class, () -> await(client.getTask("i-made-it-up", null)));
        assertEquals(A2AErrors.TASK_NOT_FOUND, error.code());
        assertEquals("Task not found", error.getMessage());
        // the specification asks for a google.rpc.ErrorInfo detail so two errors sharing a status can be told apart
        assertTrue(String.valueOf(error.details()).contains("TASK_NOT_FOUND"), () -> String.valueOf(error.details()));
    }

    @Test
    void inputRequiredThenContinue(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var first = await(client.sendMessage("input:which city?")).task();

        assertEquals(TaskState.TASK_STATE_INPUT_REQUIRED, first.status().state());
        assertEquals("which city?", first.status().message().text());

        // the client answers on the same task, which continues it instead of creating a new one
        final var second = await(client.sendMessage(SendMessageRequest.of(new Message(
                        UUID.randomUUID().toString(),
                        first.contextId(),
                        first.id(),
                        Role.ROLE_USER,
                        List.of(Part.text("Paris")),
                        null,
                        null,
                        null))))
                .task();

        assertEquals(first.id(), second.id());
        assertEquals(TaskState.TASK_STATE_COMPLETED, second.status().state());
        assertEquals("Paris", second.artifacts().get(0).parts().get(0).text());
    }

    @Test
    void mismatchingContextIsRejected(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var task = await(client.sendMessage("input:more")).task();

        final var error = assertThrows(
                A2AClientException.class,
                () -> await(client.sendMessage(SendMessageRequest.of(new Message(
                        UUID.randomUUID().toString(),
                        "another-context",
                        task.id(),
                        Role.ROLE_USER,
                        List.of(Part.text("hi")),
                        null,
                        null,
                        null)))));
        assertEquals(A2AErrors.INVALID_PARAMS, error.code());
    }

    @Test
    void terminalTaskRefusesMoreMessages(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var task = await(client.sendMessage("hello")).task();

        final var error = assertThrows(
                A2AClientException.class,
                () -> await(client.sendMessage(SendMessageRequest.of(new Message(
                        UUID.randomUUID().toString(),
                        task.contextId(),
                        task.id(),
                        Role.ROLE_USER,
                        List.of(Part.text("more")),
                        null,
                        null,
                        null)))));
        assertEquals(A2AErrors.UNSUPPORTED_OPERATION, error.code());
    }

    @Test
    void failingExecutorFailsTheTask(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var task = await(new A2AClient(agent, http, jsons).sendMessage("fail:the backend is down"))
                .task();

        // an exception is not a protocol error: the caller gets a task it can read the reason from
        assertEquals(TaskState.TASK_STATE_FAILED, task.status().state());
        assertEquals("the backend is down", task.status().message().text());
    }

    @Test
    void messageWithoutPartIsInvalid(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var response = await(new A2AClient(agent, http, jsons).raw("SendMessage", """
                {"message": {"messageId": "m-1", "role": "ROLE_USER", "parts": []}}"""));

        contains(response.body(), "\"code\":-32602", "At least one part is required", "message.parts");
    }

    @Test
    void listTasks(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var context = "list-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            await(client.sendMessage(SendMessageRequest.of(new Message(
                    UUID.randomUUID().toString(),
                    context,
                    null,
                    Role.ROLE_USER,
                    List.of(Part.text("task-" + i)),
                    null,
                    null,
                    null))));
        }

        final var all = await(client.listTasks(context, null, null, null));
        assertEquals(3, all.totalSize());
        assertEquals(3, all.tasks().size());
        assertEquals("", all.nextPageToken(), "the last page must carry an empty token, not null");
        // artifacts are omitted by default to keep the payload small
        assertNull(all.tasks().get(0).artifacts());

        final var firstPage = await(client.listTasks(context, null, 2, null));
        assertEquals(2, firstPage.tasks().size());
        assertTrue(!firstPage.nextPageToken().isEmpty(), "a cursor is expected");

        final var secondPage = await(client.listTasks(context, null, 2, firstPage.nextPageToken()));
        assertEquals(1, secondPage.tasks().size());
        assertEquals("", secondPage.nextPageToken());

        assertEquals(
                3,
                await(client.listTasks(context, TaskState.TASK_STATE_COMPLETED, null, null))
                        .totalSize());
        assertEquals(
                0,
                await(client.listTasks(context, TaskState.TASK_STATE_WORKING, null, null))
                        .totalSize());
    }

    @Test
    void listTasksRejectsAStatusWhichIsNotATaskState(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);

        final var response = await(client.raw("ListTasks", "{\"status\": \"RUNNING\"}"));
        contains(response.body(), "\"code\":-32602", "\"field\":\"status\"", "TASK_STATE_*");

        // a blank one is not a filter, so it is not a failure either
        contains(await(client.raw("ListTasks", "{\"status\": \"  \"}")).body(), "\"result\"");
        contains(
                await(client.raw("ListTasks", "{\"status\": \"TASK_STATE_WORKING\"}"))
                        .body(),
                "\"result\"");
    }

    @Test
    void cancelTerminalTask(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var task = await(client.sendMessage("hello")).task();

        final var error = assertThrows(A2AClientException.class, () -> await(client.cancelTask(task.id())));
        assertEquals(A2AErrors.TASK_NOT_CANCELABLE, error.code());
    }

    @Test
    void pushNotificationConfigs(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);
        final var task = await(client.sendMessage("input:waiting")).task();

        final var created = await(client.createTaskPushNotificationConfig(
                new TaskPushNotificationConfig(null, null, task.id(), "https://client.example.com/hook", "t-1", null)));
        assertNotNull(created.id(), "the server must generate an identifier");
        assertEquals(task.id(), created.taskId());

        assertEquals(created, await(client.getTaskPushNotificationConfig(task.id(), created.id())));
        assertEquals(
                List.of(created),
                await(client.listTaskPushNotificationConfigs(task.id())).configs());

        await(client.deleteTaskPushNotificationConfig(task.id(), created.id()));
        assertEquals(
                List.of(),
                await(client.listTaskPushNotificationConfigs(task.id())).configs());

        // deleting twice is fine, the operation is idempotent
        await(client.deleteTaskPushNotificationConfig(task.id(), created.id()));
    }

    @Test
    void unsupportedVersion(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons).version("0.3");

        final var error = assertThrows(A2AClientException.class, () -> await(client.getTask("whatever", null)));
        assertEquals(A2AErrors.VERSION_NOT_SUPPORTED, error.code());
        assertTrue(String.valueOf(error.details()).contains("1.0"), () -> String.valueOf(error.details()));
    }

    @Test
    void missingVersionMeansZeroThree(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        // section 3.6.2: "Agents MUST interpret empty value as 0.3 version", which this agent does not serve
        final var client = new A2AClient(agent, http, jsons).version(null);

        final var error = assertThrows(A2AClientException.class, () -> await(client.getTask("whatever", null)));
        assertEquals(A2AErrors.VERSION_NOT_SUPPORTED, error.code());
        contains(String.valueOf(error.details()), "0.3", "1.0");
    }

    @Test
    void versionCanBeAQueryParameter(@Fusion final URI agent, @Fusion final HttpClient http) throws Exception {
        // section 3.6.1: "Clients MAY provide the A2A-Version as a request parameter instead of a header"
        assertEquals(200, jsonRpc(http, agent, "?A2A-Version=1.0", null).statusCode());

        // and an unsupported one must be caught there too, it used to slip through
        final var unsupported = jsonRpc(http, agent, "?A2A-Version=0.9", null);
        contains(unsupported.body(), "\"code\":-32009", "0.9");

        // the header wins when both are set, so a proxy adding a parameter cannot downgrade a caller
        assertEquals(200, jsonRpc(http, agent, "?A2A-Version=0.9", "1.0").statusCode());
    }

    @Test
    void streamingCannotBeBatched(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var response = await(new A2AClient(agent, http, jsons).rawBody("""
                [
                  {"jsonrpc": "2.0", "id": 1, "method": "GetTask", "params": {"id": "x"}},
                  {"jsonrpc": "2.0", "id": 2, "method": "SendStreamingMessage", "params": {}}
                ]"""));

        assertEquals(400, response.statusCode());
        contains(response.body(), "A streaming method cannot be part of a batch");
    }

    @Test
    void unknownMethod(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var response = await(new A2AClient(agent, http, jsons).raw("SendMessages", "{}"));

        contains(response.body(), "\"code\":-32601");
    }

    @Test
    void unsupportedOutputMode(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);

        final var error = assertThrows(
                A2AClientException.class,
                () -> await(client.sendMessage(new SendMessageRequest(
                        null,
                        Message.user("hello"),
                        new SendMessageConfiguration(List.of("image/png"), null, null, false),
                        null))));
        assertEquals(A2AErrors.CONTENT_TYPE_NOT_SUPPORTED, error.code());
    }

    @Test
    void wildcardOutputModeIsAccepted(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var response = await(new A2AClient(agent, http, jsons)
                .sendMessage(new SendMessageRequest(
                        null,
                        Message.user("hello"),
                        new SendMessageConfiguration(List.of("text/*"), null, null, false),
                        null)));

        assertEquals(TaskState.TASK_STATE_COMPLETED, response.task().status().state());
    }

    /**
     * The client always sends the header, so the version service parameter is exercised with a hand built request.
     */
    private static HttpResponse<String> jsonRpc(
            final HttpClient http, final URI agent, final String query, final String versionHeader) throws Exception {
        final var builder = HttpRequest.newBuilder(agent.resolve("/a2a" + query))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc": "2.0", "id": 1, "method": "ListTasks", "params": {}}"""))
                .header("content-type", "application/json");
        if (versionHeader != null) {
            builder.header("a2a-version", versionHeader);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
