/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.core.tools.builtin;

import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.nio.file.Path;
import java.util.Map;

/** Explicit capability request, not a command retry or an alternative permission gate. */
@ApplicationScoped
public class SandboxAccessTool implements EmbeddedTool {
    @Override
    public String name() {
        return "sandbox_access";
    }

    @Override
    public String description(final Path workdir) {
        return "Request explicit sandbox access to a filesystem path or network domain. "
                + "Filesystem paths resolve against the session workdir; write defaults to false. "
                + "Configured denials cannot be overridden. This only requests access: it never retries Bash "
                + "or any failed operation, and normal tool permissions still apply. "
                + "It does not authorize external MCP/client integrations.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "kind", Schemas.strEnum("Capability type.", "filesystem", "network"),
                        "target", Schemas.str("Filesystem path or network domain (not a URL)."),
                        "write",
                                Schemas.bool(
                                        "Request filesystem write access; default false. Not applicable to network.")),
                "kind",
                "target");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var kind = arg(args, "kind");
        final var target = arg(args, "target");
        if (!"filesystem".equals(kind) && !"network".equals(kind)) {
            return ToolResult.error("kind must be filesystem or network");
        }
        if (target == null || target.isBlank()) {
            return ToolResult.error("target is required");
        }
        if (args.containsKey("write") && !(args.get("write") instanceof Boolean)) {
            return ToolResult.error("write must be a boolean");
        }
        final var write = Boolean.TRUE.equals(args.get("write"));
        if ("network".equals(kind) && write) {
            return ToolResult.error("write is only applicable to filesystem access");
        }
        if (!context.sandbox().enabled()) {
            return ToolResult.error("Sandbox is disabled; no sandbox capability was requested");
        }
        try {
            final var path = "filesystem".equals(kind) ? context.resolvePath(target) : null;
            if (path != null) {
                SandboxFiles.requireNormalized(context, path);
            }
            final var resolved = path == null ? target : path.toString();
            context.sandbox().request(kind, resolved, write);
            return ToolResult.ok("Sandbox access request completed. No command was executed or retried; "
                    + "subsequent operations remain subject to sandbox restrictions and tool permissions.");
        } catch (final IllegalArgumentException | SecurityException failure) {
            return ToolResult.error("Sandbox access request refused: " + failure.getMessage());
        }
    }
}
