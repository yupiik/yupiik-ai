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
package io.yupiik.ia.agent.core.mcp;

import io.yupiik.ai.mcp.client.McpServerTool;
import io.yupiik.ia.agent.core.tools.SandboxIntegration;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

class McpEmbeddedTool implements SandboxIntegration {
    private static final Pattern UNSAFE_NAME = Pattern.compile("[^A-Za-z0-9_-]");

    private final McpManager manager;
    private final String serverName;
    private final McpServerTool tool;
    private final String qualifiedName;

    McpEmbeddedTool(final McpManager manager, final String serverName, final McpServerTool tool) {
        this.manager = manager;
        this.serverName = serverName;
        this.tool = tool;
        this.qualifiedName = sanitize(serverName) + "_" + sanitize(tool.name());
    }

    private static String sanitize(final String s) {
        return UNSAFE_NAME.matcher(s).replaceAll("_");
    }

    @Override
    public String sandboxIntegrationId() {
        return "mcp:" + serverName;
    }

    @Override
    public String name() {
        return qualifiedName;
    }

    @Override
    public String description(final Path workdir) {
        return "[" + serverName + "] " + (tool.description() == null ? tool.name() : tool.description());
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.MCP;
    }

    @Override
    public Map<String, Object> schema() {
        return tool.inputSchema() == null ? Map.of("type", "object", "properties", Map.of()) : tool.inputSchema();
    }

    @Override
    public boolean readonly() {
        return manager.isReadOnly(serverName, tool.name());
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        try {
            return ToolResult.ok(manager.call(serverName, tool.name(), args));
        } catch (final RuntimeException e) {
            throw manager.failureForUi(serverName, qualifiedName, e);
        }
    }
}
