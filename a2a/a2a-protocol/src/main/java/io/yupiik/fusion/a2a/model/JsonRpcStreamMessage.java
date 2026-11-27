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

/**
 * A frame of a JSON-RPC Server-Sent Events stream: the JSON-RPC binding repeats the envelope of the originating
 * request - so the very same {@code id} - on every event, see section 9.4.2 of the specification.
 * <p>
 * The HTTP+JSON binding sends the bare {@link StreamResponse} instead, without any envelope.
 *
 * @param jsonrpc always {@code 2.0}.
 * @param id      the identifier of the {@code SendStreamingMessage}/{@code SubscribeToTask} request, a string or a
 *                number.
 * @param result  the event.
 */
@JsonModel
public record JsonRpcStreamMessage(
        @Property(documentation = "Always 2.0.") String jsonrpc,

        @Property(
                documentation =
                        "The identifier of the SendStreamingMessage/SubscribeToTask request, a string or a number.")
        Object id,

        @Property(documentation = "The event.") StreamResponse result) {
    public static JsonRpcStreamMessage of(final Object id, final StreamResponse result) {
        return new JsonRpcStreamMessage("2.0", id, result);
    }
}
