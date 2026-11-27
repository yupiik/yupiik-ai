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

import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import java.util.List;
import java.util.Map;

/**
 * The A2A error catalog: the codes of section 5.4 of the specification, their HTTP status and the factories building
 * a {@link JsonRpcException} carrying a {@code google.rpc.ErrorInfo} detail.
 * <p>
 * Errors travel as {@link JsonRpcException} whatever the binding: the JSON-RPC endpoint maps them to a JSON-RPC error
 * object and the HTTP+JSON one to the matching HTTP status thanks to {@link #httpStatus(int)}.
 */
public final class A2AErrors {
    /**
     * The domain of the {@code google.rpc.ErrorInfo} details, i.e. who defines the {@code reason} values.
     */
    public static final String DOMAIN = "a2a-protocol.org";

    private static final String ERROR_INFO = "type.googleapis.com/google.rpc.ErrorInfo";
    private static final String BAD_REQUEST = "type.googleapis.com/google.rpc.BadRequest";

    // A2A specific codes, -32001 to -32099 is the range the specification reserves
    public static final int TASK_NOT_FOUND = -32001;
    public static final int TASK_NOT_CANCELABLE = -32002;
    public static final int PUSH_NOTIFICATION_NOT_SUPPORTED = -32003;
    public static final int UNSUPPORTED_OPERATION = -32004;
    public static final int CONTENT_TYPE_NOT_SUPPORTED = -32005;
    public static final int INVALID_AGENT_RESPONSE = -32006;
    public static final int EXTENDED_AGENT_CARD_NOT_CONFIGURED = -32007;
    public static final int EXTENSION_SUPPORT_REQUIRED = -32008;
    public static final int VERSION_NOT_SUPPORTED = -32009;

    // standard JSON-RPC codes, repeated here so a binding does not have to guess them
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private A2AErrors() {
        // no-op
    }

    /**
     * @param code a JSON-RPC error code.
     * @return the HTTP status the specification maps {@code code} to, {@code 500} for an unknown one.
     */
    public static int httpStatus(final int code) {
        return switch (code) {
            case TASK_NOT_FOUND -> 404;
            case TASK_NOT_CANCELABLE,
                    PUSH_NOTIFICATION_NOT_SUPPORTED,
                    UNSUPPORTED_OPERATION,
                    CONTENT_TYPE_NOT_SUPPORTED,
                    EXTENDED_AGENT_CARD_NOT_CONFIGURED,
                    EXTENSION_SUPPORT_REQUIRED,
                    VERSION_NOT_SUPPORTED,
                    PARSE_ERROR,
                    INVALID_REQUEST,
                    INVALID_PARAMS -> 400;
            case METHOD_NOT_FOUND -> 404;
            default -> 500;
        };
    }

    /**
     * @param code a JSON-RPC error code.
     * @return the canonical gRPC status name the specification maps {@code code} to. The HTTP+JSON binding sends it in
     * its error payload because several A2A errors share an HTTP status.
     */
    public static String status(final int code) {
        return switch (code) {
            case TASK_NOT_FOUND, METHOD_NOT_FOUND -> "NOT_FOUND";
            case TASK_NOT_CANCELABLE,
                    PUSH_NOTIFICATION_NOT_SUPPORTED,
                    UNSUPPORTED_OPERATION,
                    EXTENDED_AGENT_CARD_NOT_CONFIGURED,
                    EXTENSION_SUPPORT_REQUIRED,
                    VERSION_NOT_SUPPORTED -> "FAILED_PRECONDITION";
            case CONTENT_TYPE_NOT_SUPPORTED, PARSE_ERROR, INVALID_REQUEST, INVALID_PARAMS -> "INVALID_ARGUMENT";
            default -> "INTERNAL";
        };
    }

    public static JsonRpcException taskNotFound(final String taskId) {
        return error(TASK_NOT_FOUND, "Task not found", "TASK_NOT_FOUND", Map.of("taskId", String.valueOf(taskId)));
    }

    public static JsonRpcException taskNotCancelable(final String taskId, final TaskState state) {
        return error(
                TASK_NOT_CANCELABLE,
                "Task is not in a cancelable state",
                "TASK_NOT_CANCELABLE",
                Map.of("taskId", String.valueOf(taskId), "state", String.valueOf(state)));
    }

    public static JsonRpcException pushNotificationNotSupported() {
        return error(
                PUSH_NOTIFICATION_NOT_SUPPORTED,
                "Push notifications are not supported by this agent",
                "PUSH_NOTIFICATION_NOT_SUPPORTED",
                Map.of("capability", "pushNotifications"));
    }

    public static JsonRpcException unsupportedOperation(final String message) {
        return error(UNSUPPORTED_OPERATION, message, "UNSUPPORTED_OPERATION", Map.of());
    }

    public static JsonRpcException unsupportedOperation(final String message, final Map<String, String> metadata) {
        return error(UNSUPPORTED_OPERATION, message, "UNSUPPORTED_OPERATION", metadata);
    }

    public static JsonRpcException contentTypeNotSupported(final List<String> requested, final List<String> supported) {
        return error(
                CONTENT_TYPE_NOT_SUPPORTED,
                "None of the accepted output modes is supported",
                "CONTENT_TYPE_NOT_SUPPORTED",
                Map.of("requested", String.join(",", requested), "supported", String.join(",", supported)));
    }

    public static JsonRpcException invalidAgentResponse(final String message) {
        return error(INVALID_AGENT_RESPONSE, message, "INVALID_AGENT_RESPONSE", Map.of());
    }

    public static JsonRpcException extendedAgentCardNotConfigured() {
        return error(
                EXTENDED_AGENT_CARD_NOT_CONFIGURED,
                "No extended agent card is configured",
                "EXTENDED_AGENT_CARD_NOT_CONFIGURED",
                Map.of());
    }

    public static JsonRpcException extensionSupportRequired(final List<String> uris) {
        return error(
                EXTENSION_SUPPORT_REQUIRED,
                "The client must declare the required extensions",
                "EXTENSION_SUPPORT_REQUIRED",
                Map.of("extensions", String.join(",", uris)));
    }

    /**
     * @param requested the <em>resolved</em> version, see {@link A2AProtocol#resolve(String)} - a request carrying none
     *                  asks for {@link A2AProtocol#ASSUMED_VERSION}, and saying so is what tells the caller what to fix.
     */
    public static JsonRpcException versionNotSupported(final String requested) {
        return error(
                VERSION_NOT_SUPPORTED,
                "The requested A2A protocol version " + requested + " is not supported by this agent",
                "VERSION_NOT_SUPPORTED",
                Map.of(
                        "requested",
                        String.valueOf(requested),
                        "supportedVersions",
                        String.join(",", A2AProtocol.SUPPORTED_VERSIONS)));
    }

    /**
     * @param field       the request attribute which is invalid, {@code message.parts} for example.
     * @param description why it is invalid.
     * @return an {@code InvalidParamsError} carrying a {@code google.rpc.BadRequest} field violation.
     */
    public static JsonRpcException invalidParams(final String field, final String description) {
        return new JsonRpcException(
                INVALID_PARAMS,
                "Invalid parameters",
                List.of(Map.of(
                        "@type",
                        BAD_REQUEST,
                        "fieldViolations",
                        List.of(Map.of("field", field, "description", description)))),
                null);
    }

    /**
     * @param code     the JSON-RPC error code.
     * @param message  the human readable message.
     * @param reason   the {@code google.rpc.ErrorInfo} reason, {@code SCREAMING_SNAKE_CASE}.
     * @param metadata the contextual attributes to attach.
     * @return the exception to throw, its {@code data} is the array of details the specification asks for.
     */
    public static JsonRpcException error(
            final int code, final String message, final String reason, final Map<String, String> metadata) {
        return new JsonRpcException(
                code,
                message,
                List.of(Map.of("@type", ERROR_INFO, "reason", reason, "domain", DOMAIN, "metadata", metadata)),
                null);
    }
}
