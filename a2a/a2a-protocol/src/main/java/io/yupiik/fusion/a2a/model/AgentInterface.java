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
 * One way to reach an agent: a URL, a protocol binding and the protocol version served there.
 * <p>
 * An agent can expose the same operations over several bindings, which is what this server does with
 * {@link #JSONRPC} and {@link #HTTP_JSON}.
 *
 * @param tenant          an opaque routing identifier - for a server hosting several agents behind one endpoint - the
 *                        client must echo in the {@code tenant} field of every request. It is {@code null} when there
 *                        is nothing to route.
 * @param url             where this interface is available, it must be absolute.
 * @param protocolBinding {@link #JSONRPC}, {@link #GRPC}, {@link #HTTP_JSON} or the URI of a custom binding.
 * @param protocolVersion the {@code Major.Minor} A2A version served there, {@code 1.0} for example.
 */
@JsonModel
public record AgentInterface(
        @Property(documentation = "Where this interface is available, it must be absolute.")
        String url,

        @Property(documentation = "#JSONRPC, #GRPC, #HTTP_JSON or the URI of a custom binding.")
        String protocolBinding,

        @Property(
                documentation =
                        "An opaque routing identifier - for a server hosting several agents behind one endpoint - the client must echo in the tenant field of every request. It is null when there is nothing to route.")
        String tenant,

        @Property(documentation = "The Major.Minor A2A version served there, 1.0 for example.")
        String protocolVersion) {
    /**
     * JSON-RPC 2.0 over HTTP with Server-Sent Events for the streams, section 9 of the specification.
     */
    public static final String JSONRPC = "JSONRPC";

    /**
     * gRPC, section 10 of the specification. This server does not implement it.
     */
    public static final String GRPC = "GRPC";

    /**
     * Plain HTTP with JSON bodies, section 11 of the specification.
     */
    public static final String HTTP_JSON = "HTTP+JSON";

    public static AgentInterface of(final String url, final String protocolBinding, final String protocolVersion) {
        return new AgentInterface(url, protocolBinding, null, protocolVersion);
    }
}
