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
import java.util.Map;

/**
 * The parameters of {@code SendMessage} and {@code SendStreamingMessage}.
 * <p>
 * In the JSON-RPC binding these are the {@code params} attributes - the Fusion JSON-RPC stack binds them to the
 * arguments of {@link io.yupiik.fusion.a2a.protocol.A2AJSONRPCProtocol} methods so this record is only used by the
 * HTTP+JSON binding, where it <em>is</em> the request body, and by
 * {@link io.yupiik.fusion.a2a.client.A2AClient}.
 *
 * @param tenant        the opaque routing identifier of the {@link AgentInterface} the client selected.
 * @param message       the message to process.
 * @param configuration how to answer.
 * @param metadata      free form metadata.
 */
@JsonModel
public record SendMessageRequest(
        @Property(documentation = "The opaque routing identifier of the AgentInterface the client selected.")
        String tenant,

        @Property(documentation = "The message to process.") Message message,
        @Property(documentation = "How to answer.") SendMessageConfiguration configuration,
        @Property(documentation = "Free form metadata.") Map<String, Object> metadata) {
    public static SendMessageRequest of(final Message message) {
        return new SendMessageRequest(null, message, null, null);
    }
}
