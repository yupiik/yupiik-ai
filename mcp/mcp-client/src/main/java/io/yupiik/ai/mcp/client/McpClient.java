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
package io.yupiik.ai.mcp.client;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface McpClient extends AutoCloseable {

    List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    void initialize() throws IOException, InterruptedException;

    List<McpServerTool> listTools() throws IOException, InterruptedException;

    String callTool(String name, Map<String, Object> arguments) throws IOException, InterruptedException;

    /**
     * Invokes any negotiated MCP request using the shared protocol codecs, without replaying failures.
     * Applications remain responsible for capability negotiation and feature-specific policy.
     * Tool-only adapters may retain the original three-operation contract.
     */
    default <T> T call(final String method, final Map<String, Object> params, final Class<T> resultType)
            throws IOException, InterruptedException {
        throw new IOException("Generic MCP calls are not supported by this adapter");
    }

    @Override
    void close();
}
