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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

/**
 * The error payload of the HTTP+JSON binding: the JSON representation of {@code google.rpc.Status}, as section 11.6 of
 * the specification requires.
 * <p>
 * The JSON-RPC binding uses the JSON-RPC error object instead, with the same {@code details} array.
 *
 * @param error the failure.
 */
@JsonModel
public record HttpErrorResponse(
        @Property(documentation = "The failure.") Error error) {
    public static HttpErrorResponse of(
            final int code, final String status, final String message, final List<Object> details) {
        return new HttpErrorResponse(new Error(code, status, message, details));
    }

    /**
     * @param code    the HTTP status code.
     * @param status  the canonical gRPC status name, {@code NOT_FOUND} for example - it is what tells two A2A errors
     *                sharing an HTTP status apart, together with the {@code ErrorInfo} detail.
     * @param message the human readable message.
     * @param details the structured details, each one carrying a {@code @type} key.
     */
    @JsonModel
    public record Error(
            @Property(documentation = "The HTTP status code.")
            int code,

            @Property(
                    documentation =
                            "The canonical gRPC status name, NOT_FOUND for example - it is what tells two A2A errors sharing an HTTP status apart, together with the ErrorInfo detail.")
            String status,

            @Property(documentation = "The human readable message.")
            String message,

            @Property(documentation = "The structured details, each one carrying a @type key.")
            List<Object> details) {}
}
