/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
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
package io.yupiik.ai.acp.protocol;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonModel
public record Envelope(
        String jsonrpc,
        String method,
        Object params,
        RpcError error,
        @JsonOthers Map<String, Object> fields) {
    /** Construct a wire envelope without changing the record's deserialization constructor. */
    public static Envelope of(
            final String jsonrpc,
            final Object id,
            final String method,
            final Object params,
            final Object result,
            final RpcError error) {
        return new Envelope(jsonrpc, method, params, error, envelopeFields(id, method, result, error));
    }

    public Object id() {
        return fields == null ? null : fields.get("id");
    }

    public boolean hasId() {
        return fields != null && fields.containsKey("id");
    }

    public Object result() {
        return fields == null ? null : fields.get("result");
    }

    private static Map<String, Object> envelopeFields(
            final Object id, final String method, final Object result, final RpcError error) {
        final var fields = new LinkedHashMap<String, Object>();
        if (id != null || method == null) {
            fields.put("id", id);
        }
        if (method == null && error == null) {
            fields.put("result", result);
        }
        return fields;
    }
}
