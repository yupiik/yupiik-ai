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
package io.yupiik.ia.agent.core.tools;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.codegraph.CodeResolveTool;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.mcp.McpManager;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;

@ApplicationScoped
public class ToolRegistry {
    private final McpManager mcpManager;

    public ToolRegistry(final McpManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    protected ToolRegistry() {
        this.mcpManager = null;
    }

    public List<EmbeddedTool> tools() {
        return mcpManager.tools().stream()
                .sorted(Comparator.comparing(EmbeddedTool::name))
                .toList();
    }

    public EmbeddedTool tool(final String name) {
        return tools().stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    /** Immutable session overlay. Names must be unique; nothing is installed in the application registry. */
    public ToolRegistry withTools(final List<EmbeddedTool> additions) {
        final var merged = new TreeMap<String, EmbeddedTool>();
        for (final var tool : tools()) {
            merged.put(tool.name(), tool);
        }
        for (final var tool : additions) {
            if (merged.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate session tool: " + tool.name());
            }
        }
        final var snapshot = List.copyOf(merged.values());
        return new ToolRegistry() {
            @Override
            public List<EmbeddedTool> tools() {
                return snapshot;
            }
        };
    }

    /**
     * Like {@link #toWireTools()} but keeps only the tools matching {@code filter} once the
     * code-resolve pruning and visibility rules have been applied, before the final
     * {@code usable()} check. The {@code filter} therefore sees the same candidates the main
     * agent sees - restricted subsets such as the read-only tools a /btw answer may call -
     * without ever re-exposing a pruned tool.
     *
     * @param filter the subset predicate applied to the visible, non-pruned tools.
     * @return the wire tool declarations for the matching tools.
     */
    public List<Tool> toWireTools(final Path root) {
        return toWireTools(root, t -> true);
    }

    /** Projects shared tools for one session root without changing registry or tool state. */
    public List<Tool> toWireTools(final Path root, final Predicate<EmbeddedTool> filter) {
        Objects.requireNonNull(root, "workdir");
        if (!root.isAbsolute() || !root.equals(root.normalize())) {
            throw new IllegalArgumentException("workdir must be absolute and normalized: " + root);
        }
        final var all = tools();
        // The merged code_resolve tool subsumes the granular structural/share tools. When it is registered we
        // expose only it (plus on-demand maven_knowledge_content); when registered but no data source is live we
        // hide code_resolve too (and the granular no-op tools), keeping the wire tool list lean. When the merged
        // tool is not present at all, the underlying tool list is left untouched.
        final var resolve = all.stream()
                .filter(t -> t instanceof CodeResolveTool)
                .findFirst()
                .map(CodeResolveTool.class::cast)
                .orElse(null);
        final var stream = resolve == null
                ? all.stream()
                : all.stream().filter(t -> {
                    if (resolve.usable(root)) {
                        return !CodeResolveTool.prunedRichToolNames().contains(t.name());
                    }
                    return !CodeResolveTool.prunedRichToolNames().contains(t.name()) && !(t instanceof CodeResolveTool);
                });
        return stream.filter(filter)
                .filter(t -> t.usable(root))
                .map(t -> Tool.function(t.name(), t.description(root), t.schema()))
                .toList();
    }

    public String describe(final Path root) {
        Objects.requireNonNull(root, "workdir");
        if (!root.isAbsolute() || !root.equals(root.normalize())) {
            throw new IllegalArgumentException("workdir must be absolute and normalized: " + root);
        }
        final var sb = new StringBuilder();
        for (final var t : tools()) {
            if (!t.usable(root)) {
                continue;
            }
            sb.append("- ")
                    .append(t.name())
                    .append(": ")
                    .append(t.description(root))
                    .append('\n');
        }
        return sb.toString();
    }
}
