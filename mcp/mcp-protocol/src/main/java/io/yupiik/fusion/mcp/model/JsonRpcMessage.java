/*
 * Copyright (c) 2025 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.fusion.mcp.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/** Shared bidirectional JSON-RPC envelope. Transport validation checks field presence before binding. */
@JsonModel
public record JsonRpcMessage(
        @Property(documentation = "Always 2.0.") String jsonrpc,

        @Property(documentation = "String or integer identifier, absent for notifications.")
        Object id,

        @Property(documentation = "The invoked method, absent for responses.")
        String method,

        @Property(documentation = "The method parameters.") Object params,

        @Property(documentation = "The successful result, mutually exclusive with error.")
        Object result,

        @Property(documentation = "The failed result, mutually exclusive with result.")
        Error error) {
    @JsonModel
    public record Error(int code, String message, Object data) {}

    public static JsonRpcMessage notification(final String method, final Object params) {
        return new JsonRpcMessage("2.0", null, method, params, null, null);
    }

    public static JsonRpcMessage request(final long id, final String method, final Object params) {
        return new JsonRpcMessage("2.0", id, method, params, null, null);
    }
}
