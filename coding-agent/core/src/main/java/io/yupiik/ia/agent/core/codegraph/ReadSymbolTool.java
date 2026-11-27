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
package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.codegraph.index.CodeGraphIndex;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.tools.builtin.ReadFileTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Slices the source lines of a single graph symbol (type or method) instead of re-reading the whole file:
 * the graph already knows every symbol's {@code file:start-end} span, so one method costs a few dozen lines
 * of context instead of a 2000-line {@code read}.
 */
@ApplicationScoped
public class ReadSymbolTool implements EmbeddedTool {
    private final StructuralCodeGraph codeGraph;

    public ReadSymbolTool(final StructuralCodeGraph codeGraph) {
        this.codeGraph = codeGraph;
    }

    protected ReadSymbolTool() {
        this.codeGraph = null;
    }

    @Override
    public String name() {
        return "read_symbol";
    }

    @Override
    public String description(final Path workdir) {
        return "Read the source lines of a single symbol (type, interface, enum or method) from the structural code "
                + "graph, identified by fully-qualified name or unique simple name. Much cheaper than read for "
                + "inspecting one type or method: only the symbol span (plus optional context lines) is returned, "
                + "with the same numbered-line format as read.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public boolean readonly() {
        return true;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "symbol",
                                Schemas.str(
                                        "Symbol to read: fully-qualified name (eg. `com.acme.Foo`) or unique "
                                                + "simple name (eg. `Foo`, `Foo#bar`), as reported by code_find_code/code_resolve."),
                        "context", Schemas.intProp("Extra lines before and after the symbol span, default 0, max 50.")),
                "symbol");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext ctx) {
        final var symbol = arg(args, "symbol");
        if (symbol == null || symbol.isBlank()) {
            return ToolResult.error("symbol is required");
        }
        final var context = (int) Math.max(0, Math.min(50, optInt(args, "context", 0)));
        if (codeGraph == null) {
            return ToolResult.error("Structural code graph is not available.");
        }
        final var root = ctx.projectDir();
        final var idx = codeGraph.queryIndex(root);
        if (idx == null) {
            return ToolResult.error("Structural code graph is empty: index the project first.");
        }
        final var resolved = resolve(idx, symbol.trim());
        if (resolved == null) {
            return ToolResult.error("Symbol not found: " + symbol
                    + ". List candidate symbols with the code_find_code tool (substring search) and retry.");
        }
        if (resolved.file() == null || resolved.file().isBlank()) {
            return ToolResult.error("Symbol " + resolved.fqn() + " has no source file in the graph.");
        }
        final var base = root;
        final var path = base == null ? Path.of(resolved.file()) : base.resolve(resolved.file());
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("Source file not found for " + resolved.fqn() + ": " + path);
        }
        final var header = new StringBuilder("symbol ")
                .append(resolved.kind())
                .append(' ')
                .append(resolved.fqn())
                .append(" (")
                .append(resolved.file())
                .append(':')
                .append(resolved.startLine())
                .append('-')
                .append(resolved.endLine())
                .append(")\n");
        if (resolved.signature() != null && !resolved.signature().isBlank()) {
            header.append("signature ").append(resolved.signature()).append('\n');
        }
        final var rendered = ReadFileTool.renderLines(
                path, Math.max(1, resolved.startLine() - context), resolved.endLine() + context);
        if (!rendered.success()) {
            return rendered;
        }
        return ToolResult.ok(header + rendered.output());
    }

    private GraphModel.Symbol resolve(final CodeGraphIndex idx, final String symbol) {
        final var exact = idx.resolve(symbol);
        if (exact != null) {
            return exact;
        }
        // the index stores methods as Class.method while read_symbol accepts Class#method; translate before the
        // fallback
        if (symbol.indexOf('#') >= 0) {
            final var dot = idx.resolve(symbol.replace('#', '.'));
            if (dot != null) {
                return dot;
            }
            // class-aware fallback: enumerate every method whose name sits under the given class prefix so a
            // short prefix (eg. `ModelsResponse.Model#maxTokens`) resolves against the stored fully-qualified
            // FQN; a unique match wins, several matches under the same prefix are reported as not found
            final var hashIdx = symbol.indexOf('#');
            final var classPrefix = symbol.substring(0, hashIdx);
            final var methodName = symbol.substring(hashIdx + 1);
            if (!classPrefix.isEmpty() && !methodName.isEmpty()) {
                final var candidates = idx.find(classPrefix + ".", GraphModel.Kind.METHOD, idx.symbolCount());
                GraphModel.Symbol match = null;
                for (final var cand : candidates) {
                    if (cand.fqn().endsWith("." + methodName)) {
                        if (match != null) {
                            return null; // ambiguous under the given class prefix
                        }
                        match = cand;
                    }
                }
                if (match != null) {
                    return match;
                }
            }
        }
        // strip a possible member suffix (`Foo#bar`) before falling back to the unique simple-name match
        final var name = symbol.indexOf('#') >= 0 ? symbol.substring(symbol.indexOf('#') + 1) : symbol;
        final var simple = name.lastIndexOf('.') >= 0 ? name.substring(name.lastIndexOf('.') + 1) : name;
        final var ord = idx.resolveSuffix(simple);
        return ord < 0 ? null : idx.symbol(ord);
    }

    private String arg(final Map<String, Object> args, final String key) {
        return args.get(key) == null ? null : args.get(key).toString();
    }

    private long optInt(final Map<String, Object> args, final String key, final long dflt) {
        final var v = args.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? dflt : Long.parseLong(v.toString());
        } catch (final NumberFormatException e) {
            return dflt;
        }
    }
}
