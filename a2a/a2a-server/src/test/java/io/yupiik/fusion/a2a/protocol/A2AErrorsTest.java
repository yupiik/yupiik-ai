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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The error catalog of section 5.4: the codes, their HTTP status, their canonical gRPC status and the
 * {@code google.rpc.ErrorInfo} detail each one carries.
 * <p>
 * The mapping is what makes an A2A error actionable on the HTTP+JSON binding: several errors share a status - five of
 * them are {@code 400} - so the {@code reason} of the detail is the only thing telling them apart.
 */
class A2AErrorsTest {
    @Test
    void httpStatuses() {
        assertEquals(404, A2AErrors.httpStatus(A2AErrors.TASK_NOT_FOUND));
        assertEquals(404, A2AErrors.httpStatus(A2AErrors.METHOD_NOT_FOUND));

        for (final var code : List.of(
                A2AErrors.TASK_NOT_CANCELABLE,
                A2AErrors.PUSH_NOTIFICATION_NOT_SUPPORTED,
                A2AErrors.UNSUPPORTED_OPERATION,
                A2AErrors.CONTENT_TYPE_NOT_SUPPORTED,
                A2AErrors.EXTENDED_AGENT_CARD_NOT_CONFIGURED,
                A2AErrors.EXTENSION_SUPPORT_REQUIRED,
                A2AErrors.VERSION_NOT_SUPPORTED,
                A2AErrors.PARSE_ERROR,
                A2AErrors.INVALID_REQUEST,
                A2AErrors.INVALID_PARAMS)) {
            assertEquals(400, A2AErrors.httpStatus(code), () -> "Unexpected status for " + code);
        }

        // an agent answering something unusable is a server side failure, and so is an unknown code
        assertEquals(500, A2AErrors.httpStatus(A2AErrors.INVALID_AGENT_RESPONSE));
        assertEquals(500, A2AErrors.httpStatus(A2AErrors.INTERNAL_ERROR));
        assertEquals(500, A2AErrors.httpStatus(-1));
    }

    @Test
    void grpcStatuses() {
        assertEquals("NOT_FOUND", A2AErrors.status(A2AErrors.TASK_NOT_FOUND));
        assertEquals("NOT_FOUND", A2AErrors.status(A2AErrors.METHOD_NOT_FOUND));

        for (final var code : List.of(
                A2AErrors.TASK_NOT_CANCELABLE, A2AErrors.PUSH_NOTIFICATION_NOT_SUPPORTED,
                A2AErrors.UNSUPPORTED_OPERATION, A2AErrors.EXTENDED_AGENT_CARD_NOT_CONFIGURED,
                A2AErrors.EXTENSION_SUPPORT_REQUIRED, A2AErrors.VERSION_NOT_SUPPORTED)) {
            assertEquals("FAILED_PRECONDITION", A2AErrors.status(code), () -> "Unexpected status for " + code);
        }
        for (final var code : List.of(
                A2AErrors.CONTENT_TYPE_NOT_SUPPORTED,
                A2AErrors.PARSE_ERROR,
                A2AErrors.INVALID_REQUEST,
                A2AErrors.INVALID_PARAMS)) {
            assertEquals("INVALID_ARGUMENT", A2AErrors.status(code), () -> "Unexpected status for " + code);
        }

        assertEquals("INTERNAL", A2AErrors.status(A2AErrors.INVALID_AGENT_RESPONSE));
        assertEquals("INTERNAL", A2AErrors.status(-1));
    }

    @Test
    void taskNotFound() {
        final var error = A2AErrors.taskNotFound("t-1");

        assertEquals(A2AErrors.TASK_NOT_FOUND, error.code());
        assertEquals("Task not found", error.getMessage());
        assertEquals("TASK_NOT_FOUND", reason(error));
        assertEquals("t-1", metadata(error).get("taskId"));
        // a null identifier must not produce a null in the JSON payload
        assertEquals("null", metadata(A2AErrors.taskNotFound(null)).get("taskId"));
    }

    @Test
    void taskNotCancelable() {
        final var error = A2AErrors.taskNotCancelable("t-1", TaskState.TASK_STATE_COMPLETED);

        assertEquals(A2AErrors.TASK_NOT_CANCELABLE, error.code());
        assertEquals("TASK_NOT_CANCELABLE", reason(error));
        assertEquals("TASK_STATE_COMPLETED", metadata(error).get("state"));
    }

    @Test
    void pushNotificationNotSupported() {
        final var error = A2AErrors.pushNotificationNotSupported();

        assertEquals(A2AErrors.PUSH_NOTIFICATION_NOT_SUPPORTED, error.code());
        assertEquals("PUSH_NOTIFICATION_NOT_SUPPORTED", reason(error));
        // the capability the client should have read in the card first
        assertEquals("pushNotifications", metadata(error).get("capability"));
    }

    @Test
    void unsupportedOperation() {
        final var plain = A2AErrors.unsupportedOperation("Nope");
        assertEquals(A2AErrors.UNSUPPORTED_OPERATION, plain.code());
        assertEquals("Nope", plain.getMessage());
        assertEquals("UNSUPPORTED_OPERATION", reason(plain));

        final var detailed = A2AErrors.unsupportedOperation("Nope", Map.of("capability", "streaming"));
        assertEquals("streaming", metadata(detailed).get("capability"));
    }

    @Test
    void contentTypeNotSupported() {
        final var error =
                A2AErrors.contentTypeNotSupported(List.of("image/png"), List.of("text/plain", "application/json"));

        assertEquals(A2AErrors.CONTENT_TYPE_NOT_SUPPORTED, error.code());
        assertEquals("CONTENT_TYPE_NOT_SUPPORTED", reason(error));
        assertEquals("image/png", metadata(error).get("requested"));
        // the supported ones are listed so the client knows what to ask for next
        assertEquals("text/plain,application/json", metadata(error).get("supported"));
    }

    @Test
    void invalidAgentResponse() {
        final var error = A2AErrors.invalidAgentResponse("The skill answered garbage");

        assertEquals(A2AErrors.INVALID_AGENT_RESPONSE, error.code());
        assertEquals("INVALID_AGENT_RESPONSE", reason(error));
    }

    @Test
    void extendedAgentCardNotConfigured() {
        final var error = A2AErrors.extendedAgentCardNotConfigured();

        assertEquals(A2AErrors.EXTENDED_AGENT_CARD_NOT_CONFIGURED, error.code());
        assertEquals("EXTENDED_AGENT_CARD_NOT_CONFIGURED", reason(error));
    }

    @Test
    void extensionSupportRequired() {
        final var error = A2AErrors.extensionSupportRequired(List.of("https://example.com/a", "https://example.com/b"));

        assertEquals(A2AErrors.EXTENSION_SUPPORT_REQUIRED, error.code());
        assertEquals("EXTENSION_SUPPORT_REQUIRED", reason(error));
        assertEquals(
                "https://example.com/a,https://example.com/b", metadata(error).get("extensions"));
    }

    @Test
    void versionNotSupported() {
        final var error = A2AErrors.versionNotSupported("0.3");

        assertEquals(A2AErrors.VERSION_NOT_SUPPORTED, error.code());
        assertTrue(error.getMessage().contains("0.3"), error::getMessage);
        assertEquals("0.3", metadata(error).get("requested"));
        // and what to send instead, else the caller can only guess
        assertEquals("1.0", metadata(error).get("supportedVersions"));
    }

    @Test
    void invalidParamsCarriesAFieldViolation() {
        final var error = A2AErrors.invalidParams("message.parts", "At least one part is required");

        assertEquals(A2AErrors.INVALID_PARAMS, error.code());
        assertEquals("Invalid parameters", error.getMessage());
        // a BadRequest detail and not an ErrorInfo one: the point is to name the offending field
        final var detail = details(error).get(0);
        assertEquals("type.googleapis.com/google.rpc.BadRequest", detail.get("@type"));
        @SuppressWarnings("unchecked")
        final var violations = (List<Map<String, String>>) detail.get("fieldViolations");
        assertEquals("message.parts", violations.get(0).get("field"));
        assertEquals("At least one part is required", violations.get(0).get("description"));
    }

    @Test
    void everyErrorInfoIsTimestampedAndDomainQualified() {
        final var error = A2AErrors.error(-32050, "Custom", "CUSTOM", Map.of("a", "b"));

        assertEquals(-32050, error.code());
        final var detail = details(error).get(0);
        assertEquals("type.googleapis.com/google.rpc.ErrorInfo", detail.get("@type"));
        assertEquals(A2AErrors.DOMAIN, detail.get("domain"));
        assertEquals("b", metadata(error).get("a"), "the caller metadata is kept");
        assertEquals(null, metadata(error).get("timestamp"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> details(final JsonRpcException error) {
        return (List<Map<String, Object>>) error.data();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> metadata(final JsonRpcException error) {
        return (Map<String, String>) details(error).get(0).get("metadata");
    }

    private static String reason(final JsonRpcException error) {
        return String.valueOf(details(error).get(0).get("reason"));
    }
}
