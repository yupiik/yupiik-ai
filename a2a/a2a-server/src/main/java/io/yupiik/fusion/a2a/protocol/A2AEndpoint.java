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

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.SEVERE;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.http.HttpMatcher;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.jsonrpc.JsonRpcHandler;
import io.yupiik.fusion.jsonrpc.Response.ErrorResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The {@code POST /a2a} transport of the JSON-RPC binding.
 * <p>
 * The JSON-RPC processing itself is fully delegated to the Fusion {@link JsonRpcHandler} - so an A2A operation is a
 * plain {@code @JsonRpc} method - this endpoint only adds what the A2A transport requires on top of it:
 * <ul>
 *     <li>the {@code A2A-Version} service parameter is validated, an unsupported value answers
 *     {@code VersionNotSupportedError},</li>
 *     <li>the {@code A2A-Extensions} service parameter is parsed and exposed to the executors,</li>
 *     <li>the identifier of the request is exposed so a streaming operation can frame its events with it,</li>
 *     <li>a streaming operation answers a {@code text/event-stream} body instead of a JSON one.</li>
 * </ul>
 * It is registered with a lower {@code @Order} than the Fusion JSON-RPC endpoint so it takes precedence on the shared
 * path - see {@link io.yupiik.fusion.a2a.configuration.JSONRPCEndpointConfiguration}.
 */
@ApplicationScoped
public class A2AEndpoint {
    /**
     * Request attribute the Fusion JSON-RPC stack fills in with the headers a method set with a
     * {@code PartialResponse}, they are copied to the HTTP response.
     */
    private static final String JSONRPC_RESPONSE_HEADERS = "yupiik.jsonrpc.response.headers";

    private static final String JSON_CONTENT_TYPE = "application/json;charset=utf-8";

    private final Logger logger = Logger.getLogger(A2AEndpoint.class.getName());

    private final JsonRpcHandler handler;
    private final JsonMapper jsons;

    public A2AEndpoint(final JsonRpcHandler handler, final JsonMapper jsons) {
        this.handler = handler;
        this.jsons = jsons;
    }

    @Order(900) // < the 1000 of the Fusion JSON-RPC endpoint so this one is picked first on POST /a2a
    @HttpMatcher(methods = "POST", path = A2AProtocol.JSONRPC_PATH, pathMatching = HttpMatcher.PathMatching.EXACT)
    public CompletionStage<Response> handle(final Request request) {
        final var version = A2AHttp.version(request);
        if (!A2AProtocol.isSupported(version)) {
            return completedFuture(error(A2AErrors.versionNotSupported(A2AProtocol.resolve(version))));
        }
        request.setAttribute(A2AProtocol.EXTENSIONS_ATTRIBUTE, extensions(request));
        if (!A2AHttp.hasBody(request)) {
            // reading a body which is not there never completes, so the request would hang until the container gives up
            return completedFuture(parseError());
        }

        try {
            return handler.readRequest(request.fullBody())
                    .thenCompose(payload -> onPayload(payload, request))
                    .exceptionally(err -> onError(A2AErrors.PARSE_ERROR, err));
        } catch (final RuntimeException re) { // deserialization error
            return completedFuture(onError(A2AErrors.PARSE_ERROR, re));
        }
    }

    private CompletionStage<Response> onPayload(final Object payload, final Request request) {
        if (payload == null) { // a "null" body
            return completedFuture(parseError());
        }
        if (payload instanceof List<?> batch) {
            final var streaming = batch.stream()
                    .filter(it -> it instanceof Map<?, ?> map && A2AProtocol.isStreaming(map.get("method")))
                    .findFirst();
            if (streaming.isPresent()) {
                // one response body cannot carry two streams, so a batch must not mix them in
                return completedFuture(jsonRpc(
                        400,
                        handler.createResponse(
                                null, A2AErrors.INVALID_REQUEST, "A streaming method cannot be part of a batch")));
            }
        }
        var streaming = false;
        if (payload instanceof Map<?, ?> map) {
            request.setAttribute(A2AProtocol.REQUEST_ID_ATTRIBUTE, map.get("id"));
            streaming = A2AProtocol.isStreaming(map.get("method"));
        }

        final var isStreaming = streaming;
        return handler.execute(payload, request)
                .thenApply(result -> response(result, request, isStreaming))
                .exceptionally(err -> onError(A2AErrors.INTERNAL_ERROR, err));
    }

    private Response response(final Object payload, final Request request, final boolean streaming) {
        final var stream = request.attribute(A2AProtocol.STREAM_ATTRIBUTE, A2AStream.class);
        if (stream != null) {
            return sse(request, stream);
        }
        if (streaming && payload instanceof io.yupiik.fusion.jsonrpc.Response response && response.error() != null) {
            // the client asked for a stream and gets a JSON body instead, so the status must say it went wrong - it has
            // no other way to tell a failed subscription from an empty stream
            return jsonRpc(A2AErrors.httpStatus(response.error().code()), payload);
        }

        final var builder = Response.of().header("content-type", JSON_CONTENT_TYPE);
        final var extraHeaders = request.attribute(JSONRPC_RESPONSE_HEADERS, Map.class);
        if (extraHeaders != null) {
            @SuppressWarnings("unchecked")
            final var headers = (Map<String, String>) extraHeaders;
            headers.forEach(builder::header);
        }
        if (payload == null) { // a notification, there is nothing to answer
            return builder.status(202).build();
        }
        return builder.status(200).body(jsons.toString(payload)).build();
    }

    /**
     * Answers the {@code text/event-stream} body of a streaming operation.
     * <p>
     * A stream lives until the task ends or the client goes away. Optional servlet hosts must configure
     * their own asynchronous timeout policy; this transport has no servlet dependency.
     */
    static Response sse(final Request request, final A2AStream stream) {
        return Response.of()
                .status(200)
                .header("content-type", "text/event-stream;charset=utf-8")
                .header("cache-control", "no-cache")
                .header("connection", "keep-alive")
                .body(stream.bus())
                .build();
    }

    /**
     * @param request the current request.
     * @return the extension URIs the client declared in {@code A2A-Extensions}, never {@code null}.
     */
    static List<String> extensions(final Request request) {
        final var header = request.header(A2AProtocol.EXTENSIONS_HEADER);
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Stream.of(header.split(","))
                .map(String::strip)
                .filter(it -> !it.isEmpty())
                .toList();
    }

    private Response onError(final int code, final Throwable error) {
        logger.log(SEVERE, error, error::getMessage);
        return jsonRpc(200, handler.createResponse(null, code, error.getMessage()));
    }

    /**
     * @return the JSON-RPC error a body which is not a request deserves. The status is a {@code 200}: the failure is
     * carried by the envelope, which is where a JSON-RPC client reads it.
     */
    private Response parseError() {
        return jsonRpc(200, handler.createResponse(null, A2AErrors.PARSE_ERROR, "Invalid JSON payload"));
    }

    private Response error(final JsonRpcException error) {
        return jsonRpc(
                A2AErrors.httpStatus(error.code()),
                new io.yupiik.fusion.jsonrpc.Response(
                        "2.0", null, null, new ErrorResponse(error.code(), error.getMessage(), error.data())));
    }

    private Response jsonRpc(final int status, final Object payload) {
        return Response.of()
                .status(status)
                .header("content-type", JSON_CONTENT_TYPE)
                .body(jsons.toString(payload))
                .build();
    }
}
