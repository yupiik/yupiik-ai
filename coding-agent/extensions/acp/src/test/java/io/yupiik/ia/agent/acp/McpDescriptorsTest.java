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
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yupiik.ai.acp.protocol.*;
import org.junit.jupiter.api.Test;

class McpDescriptorsTest {
    @Test
    void readsStableAcpRemoteAndStdioDescriptorsFromRawJson() {
        try (final var mapper = CodecsTest.mapper()) {
            final var request = mapper.fromString(NewSession.class, """
                    {
                      "cwd": "/workspace",
                      "mcpServers": [
                        {"name":"stdio","command":"/usr/bin/tool","args":["--stdio"],
                         "env":[{"name":"TOKEN","value":"stdio-secret"}]},
                        {"type":"http","name":"remote","url":"https://tools.example/mcp",
                         "headers":[{"name":"Authorization","value":"Bearer http-secret"}]},
                        {"type":"sse","name":"legacy","url":"https://tools.example/sse",
                         "headers":[{"name":"X-Token","value":"sse-secret"}]}
                      ]
                    }
                    """);
            assertEquals(3, request.mcpServers().size());
            final var stdio = request.mcpServers().getFirst();
            assertNull(stdio.type());
            assertEquals("/usr/bin/tool", stdio.command());
            assertEquals("stdio-secret", stdio.env().getFirst().value());
            final var http = request.mcpServers().get(1);
            assertEquals("http", http.type());
            assertNull(http.command());
            assertEquals("https://tools.example/mcp", http.url());
            assertEquals("Authorization", http.headers().getFirst().name());
            assertEquals("Bearer http-secret", http.headers().getFirst().value());
            final var sse = request.mcpServers().getLast();
            assertEquals("sse", sse.type());
            assertEquals("sse-secret", sse.headers().getFirst().value());
        }
    }
}
