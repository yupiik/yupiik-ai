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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;

import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.model.CancelTaskRequest;
import io.yupiik.fusion.a2a.model.HttpErrorResponse;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.service.A2AService;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.http.HttpMatcher;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The HTTP+JSON binding of A2A - section 11 of the specification - served under {@code /a2a/v1}:
 * <table>
 *     <caption>Routes</caption>
 *     <tr><td>{@code POST /message:send}</td><td>{@code SendMessage}</td></tr>
 *     <tr><td>{@code POST /message:stream}</td><td>{@code SendStreamingMessage}</td></tr>
 *     <tr><td>{@code GET /tasks}</td><td>{@code ListTasks}</td></tr>
 *     <tr><td>{@code GET /tasks/{id}}</td><td>{@code GetTask}</td></tr>
 *     <tr><td>{@code POST /tasks/{id}:cancel}</td><td>{@code CancelTask}</td></tr>
 *     <tr><td>{@code POST /tasks/{id}:subscribe}</td><td>{@code SubscribeToTask}</td></tr>
 *     <tr><td>{@code POST /tasks/{id}/pushNotificationConfigs}</td><td>{@code CreateTaskPushNotificationConfig}</td></tr>
 *     <tr><td>{@code GET /tasks/{id}/pushNotificationConfigs}</td><td>{@code ListTaskPushNotificationConfigs}</td></tr>
 *     <tr><td>{@code GET /tasks/{id}/pushNotificationConfigs/{configId}}</td><td>{@code GetTaskPushNotificationConfig}</td></tr>
 *     <tr><td>{@code DELETE /tasks/{id}/pushNotificationConfigs/{configId}}</td><td>{@code DeleteTaskPushNotificationConfig}</td></tr>
 *     <tr><td>{@code GET /extendedAgentCard}</td><td>{@code GetExtendedAgentCard}</td></tr>
 * </table>
 * It is a thin adapter on {@link A2AService} - the very same object the JSON-RPC binding calls - so both bindings are
 * functionally equivalent by construction, which is what section 5.1 requires. Only the shapes differ: the parameters
 * come from the path and the query string, the errors are {@code google.rpc.Status} payloads and the streams carry bare
 * {@code StreamResponse} events with no JSON-RPC envelope.
 * <p>
 * Set {@code fusion.a2a.restBinding=false} to stop serving it, the routes then answer {@code 404}.
 *
 * @see A2AJSONRPCProtocol for the JSON-RPC binding.
 */
@ApplicationScoped
public class A2ARestProtocol {
    private final Logger logger = Logger.getLogger(A2ARestProtocol.class.getName());

    private final A2AService service;
    private final JsonMapper jsons;
    private final boolean enabled;

    public A2ARestProtocol(final A2AService service, final JsonMapper jsons, final A2AConfiguration configuration) {
        this.service = service;
        this.jsons = jsons;
        this.enabled = configuration == null || configuration.restBinding();
    }

    @HttpMatcher(
            methods = "POST",
            path = A2AProtocol.REST_PATH + "/message:send",
            pathMatching = HttpMatcher.PathMatching.EXACT)
    public CompletionStage<Response> sendMessage(final Request request) {
        return validated(request, () -> {
            return body(request, SendMessageRequest.class)
                    .thenCompose(payload -> service.forCaller(request).sendMessage(payload, request))
                    .thenApply(this::json);
        });
    }

    @HttpMatcher(
            methods = "POST",
            path = A2AProtocol.REST_PATH + "/message:stream",
            pathMatching = HttpMatcher.PathMatching.EXACT)
    public CompletionStage<Response> streamMessage(final Request request) {
        return validated(request, () -> {
            return body(request, SendMessageRequest.class)
                    .thenApply(payload -> A2AEndpoint.sse(
                            request,
                            service.forCaller(request)
                                    .sendStreamingMessage(payload, request, sink -> A2AStream.plain(sink, jsons))));
        });
    }

    @HttpMatcher(
            methods = "GET",
            path = A2AProtocol.REST_PATH + "/tasks",
            pathMatching = HttpMatcher.PathMatching.EXACT)
    public CompletionStage<Response> listTasks(final Request request) {
        return validated(request, () -> {
            return completedFuture(json(service.forCaller(request)
                    .listTasks(
                            tenant(request),
                            request.parameter("contextId"),
                            taskState(request.parameter("status")),
                            integer(request, "pageSize"),
                            request.parameter("pageToken"),
                            integer(request, "historyLength"),
                            request.parameter("statusTimestampAfter"),
                            bool(request, "includeArtifacts"))));
        });
    }

    @HttpMatcher(
            methods = "GET",
            path = A2AProtocol.REST_PATH + "/extendedAgentCard",
            pathMatching = HttpMatcher.PathMatching.EXACT)
    public CompletionStage<Response> extendedAgentCard(final Request request) {
        return validated(request, () -> {
            return completedFuture(json(service.forCaller(request).extendedAgentCard(tenant(request), request)));
        });
    }

    @HttpMatcher(
            methods = "GET",
            path = A2AProtocol.REST_PATH + "/tasks/[^/:]+",
            pathMatching = HttpMatcher.PathMatching.REGEX)
    public CompletionStage<Response> getTask(final Request request) {
        return validated(request, () -> {
            return completedFuture(json(service.forCaller(request)
                    .getTask(tenant(request), taskId(request), integer(request, "historyLength"))));
        });
    }

    @HttpMatcher(
            methods = "POST",
            path = A2AProtocol.REST_PATH + "/tasks/[^/:]+:cancel",
            pathMatching = HttpMatcher.PathMatching.REGEX)
    public CompletionStage<Response> cancelTask(final Request request) {
        return validated(request, () -> {
            return body(request, CancelTaskRequest.class, new CancelTaskRequest(null, null, null))
                    .thenApply(payload -> json(service.forCaller(request)
                            .cancelTask(tenantOr(request, payload.tenant()), taskId(request), payload.metadata())));
        });
    }

    @HttpMatcher(
            methods = "POST",
            path = A2AProtocol.REST_PATH + "/tasks/[^/:]+:subscribe",
            pathMatching = HttpMatcher.PathMatching.REGEX)
    public CompletionStage<Response> subscribeToTask(final Request request) {
        return validated(request, () -> {
            return completedFuture(A2AEndpoint.sse(
                    request,
                    service.forCaller(request)
                            .subscribeToTask(tenant(request), taskId(request), sink -> A2AStream.plain(sink, jsons))));
        });
    }

    @HttpMatcher(
            methods = "POST",
            path = A2AProtocol.REST_PATH + "/tasks/[^/:]+/pushNotificationConfigs",
            pathMatching = HttpMatcher.PathMatching.REGEX)
    public CompletionStage<Response> createPushConfig(final Request request) {
        return validated(request, () -> {
            return body(request, TaskPushNotificationConfig.class)
                    .thenApply(payload -> json(service.forCaller(request).createPushConfig(payload, taskId(request))));
        });
    }

    @HttpMatcher(
            methods = "GET",
            path = A2AProtocol.REST_PATH + "/tasks/[^/:]+/pushNotificationConfigs",
            pathMatching = HttpMatcher.PathMatching.REGEX)
    public CompletionStage<Response> listPushConfigs(final Request request) {
        return validated(request, () -> {
            return completedFuture(json(service.forCaller(request)
                    .listPushConfigs(
                            tenant(request),
                            taskId(request),
                            integer(request, "pageSize"),
                            request.parameter("pageToken"))));
        });
    }

    @HttpMatcher(
            methods = "GET",
            path = A2AProtocol.REST_PATH + "/tasks/[^/:]+/pushNotificationConfigs/[^/]+",
            pathMatching = HttpMatcher.PathMatching.REGEX)
    public CompletionStage<Response> getPushConfig(final Request request) {
        return validated(request, () -> {
            return completedFuture(json(
                    service.forCaller(request).getPushConfig(tenant(request), taskId(request), configId(request))));
        });
    }

    @HttpMatcher(
            methods = "DELETE",
            path = A2AProtocol.REST_PATH + "/tasks/[^/:]+/pushNotificationConfigs/[^/]+",
            pathMatching = HttpMatcher.PathMatching.REGEX)
    public CompletionStage<Response> deletePushConfig(final Request request) {
        return validated(request, () -> {
            service.forCaller(request).deletePushConfig(tenant(request), taskId(request), configId(request));
            return completedFuture(json(Map.of()));
        });
    }

    private CompletionStage<Response> validated(
            final Request request, final Supplier<CompletionStage<Response>> action) {
        if (!enabled) {
            return completedFuture(notFound());
        }
        final var version = A2AHttp.version(request);
        if (!A2AProtocol.isSupported(version)) {
            return completedFuture(error(A2AErrors.versionNotSupported(A2AProtocol.resolve(version))));
        }
        request.setAttribute(A2AProtocol.EXTENSIONS_ATTRIBUTE, A2AEndpoint.extensions(request));
        try {
            return action.get().exceptionally(this::onAsyncError);
        } catch (final RuntimeException error) {
            return completedFuture(onAsyncError(error));
        }
    }

    private static String taskId(final Request request) {
        final var segment = path(request).substring("/tasks/".length()).split("/", 2)[0];
        final var colon = segment.lastIndexOf(':');
        return decode(colon < 0 ? segment : segment.substring(0, colon));
    }

    private static String configId(final Request request) {
        return decode(request.path().substring(request.path().lastIndexOf('/') + 1));
    }

    private <T> CompletionStage<T> body(final Request request, final Class<T> type) {
        if (!A2AHttp.hasBody(request)) {
            throw A2AErrors.invalidParams("body", "A JSON body is required");
        }
        return request.fullBody().string().thenApply(payload -> {
            if (payload == null || payload.isBlank()) {
                throw A2AErrors.invalidParams("body", "A JSON body is required");
            }
            return read(type, payload);
        });
    }

    private <T> CompletionStage<T> body(final Request request, final Class<T> type, final T fallback) {
        if (!A2AHttp.hasBody(request)) {
            return completedFuture(fallback);
        }
        return request.fullBody()
                .string()
                .thenApply(payload -> payload == null || payload.isBlank() ? fallback : read(type, payload));
    }

    private <T> T read(final Class<T> type, final String payload) {
        try {
            return jsons.fromString(type, payload);
        } catch (final RuntimeException re) {
            throw A2AErrors.error(A2AErrors.PARSE_ERROR, "Invalid JSON payload", "INVALID_ARGUMENT", Map.of());
        }
    }

    private Response onAsyncError(final Throwable error) {
        final var cause = A2AProtocol.unwrap(error);
        if (cause instanceof JsonRpcException jre) {
            logger.log(FINE, jre, jre::getMessage);
            return error(jre);
        }
        logger.log(SEVERE, cause, cause::getMessage);
        return error(
                A2AErrors.error(A2AErrors.INTERNAL_ERROR, String.valueOf(cause.getMessage()), "INTERNAL", Map.of()));
    }

    private Response json(final Object payload) {
        return Response.of()
                .status(200)
                .header("content-type", A2AProtocol.MEDIA_TYPE + ";charset=utf-8")
                .body(jsons.toString(payload))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Response error(final JsonRpcException error) {
        final var status = A2AErrors.httpStatus(error.code());
        return Response.of()
                .status(status)
                .header("content-type", A2AProtocol.MEDIA_TYPE + ";charset=utf-8")
                .body(jsons.toString(HttpErrorResponse.of(
                        status,
                        A2AErrors.status(error.code()),
                        error.getMessage(),
                        error.data() instanceof List<?> details ? (List<Object>) details : List.of())))
                .build();
    }

    private Response notFound() {
        return Response.of()
                .status(404)
                .header("content-type", A2AProtocol.MEDIA_TYPE + ";charset=utf-8")
                .body(jsons.toString(HttpErrorResponse.of(404, "NOT_FOUND", "Unknown A2A route", List.of())))
                .build();
    }

    private static String path(final Request request) {
        final var path = request.path();
        final var index = path.indexOf(A2AProtocol.REST_PATH);
        final var relative = index < 0 ? path : path.substring(index + A2AProtocol.REST_PATH.length());
        return relative.isEmpty() ? "/" : relative;
    }

    private static String tenant(final Request request) {
        return request.parameter("tenant");
    }

    private static String tenantOr(final Request request, final String fromBody) {
        return fromBody != null && !fromBody.isBlank() ? fromBody : tenant(request);
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

    private static Integer integer(final Request request, final String name) {
        final var value = request.parameter(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (final NumberFormatException nfe) {
            throw A2AErrors.invalidParams(name, "It must be a number, got '" + value + '\'');
        }
    }

    private static Boolean bool(final Request request, final String name) {
        final var value = request.parameter(name);
        return value == null || value.isBlank() ? null : Boolean.valueOf(value.strip());
    }

    private static String decode(final String segment) {
        return URLDecoder.decode(segment, UTF_8);
    }
}
