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
package io.yupiik.fusion.a2a.protocol;

import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.AuthenticationInfo;
import io.yupiik.fusion.a2a.model.ListTaskPushNotificationConfigsResponse;
import io.yupiik.fusion.a2a.model.ListTasksResponse;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.SendMessageConfiguration;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.a2a.model.SendMessageResponse;
import io.yupiik.fusion.a2a.model.Task;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.service.A2AService;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpc;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpcParam;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.json.JsonMapper;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * The JSON-RPC binding of A2A - section 9 of the specification - where every operation is a plain Fusion
 * {@code @JsonRpc} method, exactly like the skills of the application, which is what enables the whole JSON-RPC stack
 * - parameter binding, validation, batching, OpenRPC generation - to be reused as is.
 * <p>
 * Method names are PascalCase as the binding requires and the parameters are the attributes of the proto request
 * message, bound by name.
 * <p>
 * The two streaming operations do not return a value: they open an {@link A2AStream}, hand it to the transport through
 * a request attribute and {@link A2AEndpoint} answers a {@code text/event-stream} instead of a JSON body.
 *
 * @see A2ARestProtocol for the HTTP+JSON binding of the same operations.
 * @see A2AEndpoint for the transport concerns: version negotiation, extensions and streams.
 */
@ApplicationScoped
public class A2AJSONRPCProtocol {
    private final A2AService service;
    private final JsonMapper jsons;

    public A2AJSONRPCProtocol(final A2AService service, final JsonMapper jsons) {
        this.service = service;
        this.jsons = jsons;
    }

    @JsonRpc(
            value = "SendMessage",
            documentation = "Sends a message to the agent to create, continue or restart a task.")
    public CompletionStage<SendMessageResponse> sendMessage(
            @JsonRpcParam(documentation = "Routing identifier declared in the agent card interface.")
                    final String tenant,
            @JsonRpcParam(required = true, documentation = "The message to process.") final Message message,
            @JsonRpcParam(
                            documentation =
                                    "How to answer: accepted output modes, history length, webhook, blocking or not.")
                    final SendMessageConfiguration configuration,
            @JsonRpcParam(documentation = "Free form metadata, 'skillId' selects the skill to invoke.")
                    final Map<String, Object> metadata,
            final Request request) {
        return service.forCaller(request)
                .sendMessage(new SendMessageRequest(tenant, message, configuration, metadata), request);
    }

    /**
     * Opens a stream instead of returning a value, see {@link A2AProtocol#STREAM_ATTRIBUTE}.
     */
    @JsonRpc(
            value = "SendStreamingMessage",
            documentation = "Sends a message and streams the task updates as they happen.")
    public void sendStreamingMessage(
            @JsonRpcParam(documentation = "Routing identifier declared in the agent card interface.")
                    final String tenant,
            @JsonRpcParam(required = true, documentation = "The message to process.") final Message message,
            @JsonRpcParam(documentation = "How to answer: accepted output modes, history length, webhook.")
                    final SendMessageConfiguration configuration,
            @JsonRpcParam(documentation = "Free form metadata, 'skillId' selects the skill to invoke.")
                    final Map<String, Object> metadata,
            final Request request) {
        final var stream = service.forCaller(request)
                .sendStreamingMessage(
                        new SendMessageRequest(tenant, message, configuration, metadata), request, framing(request));
        request.setAttribute(A2AProtocol.STREAM_ATTRIBUTE, stream);
    }

    @JsonRpc(value = "GetTask", documentation = "Gets the latest state of a task.")
    public Task getTask(
            @JsonRpcParam(documentation = "Routing identifier declared in the agent card interface.")
                    final String tenant,
            @JsonRpcParam(required = true, documentation = "The task identifier.") final String id,
            @JsonRpcParam(documentation = "How many of the most recent messages to return, 0 for none.")
                    final Integer historyLength,
            final Request request) {
        return service.forCaller(request).getTask(tenant, id, historyLength);
    }

    @JsonRpc(value = "ListTasks", documentation = "Lists the tasks matching a filter, most recently updated first.")
    public ListTasksResponse listTasks(
            @JsonRpcParam(documentation = "Routing identifier declared in the agent card interface.")
                    final String tenant,
            @JsonRpcParam(documentation = "Only the tasks of that conversation.") final String contextId,
            @JsonRpcParam(documentation = "Only the tasks in that state, a TaskState name such as TASK_STATE_WORKING.")
                    final String status,
            @JsonRpcParam(documentation = "How many tasks to return, 50 by default and 100 at most.")
                    final Integer pageSize,
            @JsonRpcParam(documentation = "Cursor returned by a previous call.") final String pageToken,
            @JsonRpcParam(documentation = "How many of the most recent messages to return per task, 0 for none.")
                    final Integer historyLength,
            @JsonRpcParam(documentation = "Only the tasks updated at or after that ISO-8601 instant.")
                    final String statusTimestampAfter,
            @JsonRpcParam(documentation = "Whether to include the artifacts, false by default.")
                    final Boolean includeArtifacts,
            final Request request) {
        return service.forCaller(request)
                .listTasks(
                        tenant,
                        contextId,
                        taskState(status),
                        pageSize,
                        pageToken,
                        historyLength,
                        statusTimestampAfter,
                        includeArtifacts);
    }

    @JsonRpc(value = "CancelTask", documentation = "Cancels a task which is still in progress.")
    public Task cancelTask(
            @JsonRpcParam(documentation = "Routing identifier declared in the agent card interface.")
                    final String tenant,
            @JsonRpcParam(required = true, documentation = "The task identifier.") final String id,
            @JsonRpcParam(documentation = "Free form metadata.") final Map<String, Object> metadata,
            final Request request) {
        return service.forCaller(request).cancelTask(tenant, id, metadata);
    }

    /**
     * Opens a stream instead of returning a value, see {@link A2AProtocol#STREAM_ATTRIBUTE}.
     */
    @JsonRpc(
            value = "SubscribeToTask",
            documentation = "Streams the updates of a task which is not in a terminal state.")
    public void subscribeToTask(
            @JsonRpcParam(documentation = "Routing identifier declared in the agent card interface.")
                    final String tenant,
            @JsonRpcParam(required = true, documentation = "The task identifier.") final String id,
            final Request request) {
        request.setAttribute(
                A2AProtocol.STREAM_ATTRIBUTE, service.forCaller(request).subscribeToTask(tenant, id, framing(request)));
    }

    @JsonRpc(
            value = "CreateTaskPushNotificationConfig",
            documentation = "Registers a webhook called when a task changes.")
    public TaskPushNotificationConfig createTaskPushNotificationConfig(
            @JsonRpcParam(documentation = "Routing identifier declared in the agent card interface.")
                    final String tenant,
            @JsonRpcParam(documentation = "The configuration identifier, generated when absent.") final String id,
            @JsonRpcParam(required = true, documentation = "The task to watch.") final String taskId,
            @JsonRpcParam(required = true, documentation = "Where to POST the events.") final String url,
            @JsonRpcParam(documentation = "Token echoed in the X-A2A-Notification-Token header.") final String token,
            @JsonRpcParam(documentation = "How to authenticate the webhook call.")
                    final AuthenticationInfo authentication,
            final Request request) {
        return service.forCaller(request)
                .createPushConfig(
                        new TaskPushNotificationConfig(tenant, id, taskId, url, token, authentication), taskId);
    }

    @JsonRpc(value = "GetTaskPushNotificationConfig", documentation = "Gets a webhook registered on a task.")
    public TaskPushNotificationConfig getTaskPushNotificationConfig(
            @JsonRpcParam(documentation = "Routing identifier declared in the agent card interface.")
                    final String tenant,
            @JsonRpcParam(required = true, documentation = "The task the webhook is registered on.")
                    final String taskId,
            @JsonRpcParam(required = true, documentation = "The configuration identifier.") final String id,
            final Request request) {
        return service.forCaller(request).getPushConfig(tenant, taskId, id);
    }

    @JsonRpc(value = "ListTaskPushNotificationConfigs", documentation = "Lists the webhooks registered on a task.")
    public ListTaskPushNotificationConfigsResponse listTaskPushNotificationConfigs(
            @JsonRpcParam(documentation = "Routing identifier declared in the agent card interface.")
                    final String tenant,
            @JsonRpcParam(required = true, documentation = "The task the webhooks are registered on.")
                    final String taskId,
            @JsonRpcParam(documentation = "How many configurations to return.") final Integer pageSize,
            @JsonRpcParam(documentation = "Cursor returned by a previous call.") final String pageToken,
            final Request request) {
        return service.forCaller(request).listPushConfigs(tenant, taskId, pageSize, pageToken);
    }

    /**
     * The proto answers {@code google.protobuf.Empty}, i.e. an empty object, so an empty map is returned rather than
     * nothing - a {@code void} method would be a JSON-RPC notification and answer no result at all.
     */
    @JsonRpc(value = "DeleteTaskPushNotificationConfig", documentation = "Removes a webhook registered on a task.")
    public Map<String, Object> deleteTaskPushNotificationConfig(
            @JsonRpcParam(documentation = "Routing identifier declared in the agent card interface.")
                    final String tenant,
            @JsonRpcParam(required = true, documentation = "The task the webhook is registered on.")
                    final String taskId,
            @JsonRpcParam(required = true, documentation = "The configuration identifier.") final String id,
            final Request request) {
        service.forCaller(request).deletePushConfig(tenant, taskId, id);
        return Map.of();
    }

    @JsonRpc(
            value = "GetExtendedAgentCard",
            documentation = "Gets the agent card an authenticated caller is entitled to.")
    public AgentCard getExtendedAgentCard(
            @JsonRpcParam(documentation = "Routing identifier declared in the agent card interface.")
                    final String tenant,
            final Request request) {
        return service.forCaller(request).extendedAgentCard(tenant, request);
    }

    private Function<SseBus, A2AStream> framing(final Request request) {
        final var id = request.attribute(A2AProtocol.REQUEST_ID_ATTRIBUTE, Object.class);
        return sink -> A2AStream.jsonRpc(sink, jsons, id);
    }

    private static TaskState taskState(final String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TaskState.valueOf(status.strip());
        } catch (final IllegalArgumentException iae) {
            throw A2AErrors.invalidParams(
                    "status", "Unknown task state '" + status + "', it must be a TASK_STATE_* name");
        }
    }
}
