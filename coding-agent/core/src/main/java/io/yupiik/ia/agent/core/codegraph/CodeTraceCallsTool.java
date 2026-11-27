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
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CodeTraceCallsTool implements EmbeddedTool {
    private final StructuralCodeGraph codeGraph;

    public CodeTraceCallsTool(final StructuralCodeGraph codeGraph) {
        this.codeGraph = codeGraph;
    }

    protected CodeTraceCallsTool() {
        this.codeGraph = null;
    }

    @Override
    public String name() {
        return "code_trace_calls";
    }

    @Override
    public String description(final Path workdir) {
        return "Trace relations of a symbol in the structural code graph (breadth-first, optional depth limit): "
                + "callers/callees via calls, implementors (edgeTypes=implements), extensions (extends), usages (uses) "
                + "or imports. Default follows calls; narrow with the edgeTypes parameter (eg. edgeTypes=implements). "
                + "Returns an indented tree of symbols with their file locations. The trace is complete and "
                + "authoritative for the traced relation: do not grep or re-read the referenced sources simply to "
                + "confirm membership, only read a file if you need method/body detail beyond the trace.";
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
                                        "Symbol to trace, eg. `io.yupiik.ia.agent.core.tools.ToolExecutionService`."),
                        "direction",
                                Schemas.str(
                                        "`callers` (who calls it), `callees` (what it calls) or `both`, default `both`."),
                        "edgeTypes",
                                Schemas.str(
                                        "Comma-separated relation types to follow, default `calls`. Accepted: calls, "
                                                + "implements, extends, uses, imports, all. Eg. `implements` finds "
                                                + "implementors of an interface."),
                        "depth",
                                Schemas.intProp(
                                        "Maximum traversal depth. Unlimited (0) by default; set a positive value to bound it."),
                        "max", Schemas.intProp("Maximum number of edges to report, default 80.")),
                "symbol");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext ctx) {
        final var symbol = arg(args, "symbol");
        if (symbol == null || symbol.isBlank()) {
            return ToolResult.error("symbol is required");
        }
        final var direction = arg(args, "direction");
        final var edgeTypes = parseEdgeTypes(arg(args, "edgeTypes"));
        final var depth = optInt(args, "depth", 0);
        final var max = Math.clamp(optInt(args, "max", 80), 1, 500);
        final var idx = codeGraph.queryIndex(ctx.projectDir());
        if (idx == null || idx.symbolCount() == 0) {
            return ToolResult.ok("Structural code graph is empty.");
        }
        final var dir = direction == null ? "both" : direction.toLowerCase(Locale.ROOT);
        final var targetOrd = resolveOrd(symbol, idx);
        if (targetOrd < 0) {
            return ToolResult.error("Symbol not found: " + symbol + ". List candidate symbols with the code_find_code "
                    + "tool, then retrace using the fully-qualified name.");
        }
        // Terminal verdict for concrete-class implements trace (mirrors CodeResolveTool)
        if (edgeTypes.equals(EnumSet.of(GraphModel.EdgeType.IMPLEMENTS))) {
            final var targetSymbol = idx.symbol(targetOrd);
            if (targetSymbol.kind() != GraphModel.Kind.INTERFACE) {
                final var suggestion = ImplementorsSuggestion.suggest(idx, targetOrd);
                final var ctSb = new StringBuilder();
                ctSb.append(symbol)
                        .append(" is a ")
                        .append(targetSymbol.kind().name().toLowerCase(Locale.ROOT))
                        .append(", not an interface, so nothing implements it directly");
                if (suggestion != null) {
                    ctSb.append("; ").append(suggestion).append('\n');
                } else {
                    ctSb.append("; trace the interfaces it references (e.g. its field/method return types) "
                            + "for implementors.\n");
                }
                return ToolResult.ok(ctSb.toString());
            }
        }
        final var wantOut = dir.equals("callees") || dir.equals("both");
        final var wantIn = dir.equals("callers") || dir.equals("both");

        final var sb = new StringBuilder();
        var count = 0;
        final var visited = new HashSet<Integer>();
        final Deque<int[]> queue = new ArrayDeque<>(); // {ordinal, level}
        queue.add(new int[] {targetOrd, 0});
        visited.add(targetOrd);
        while (!queue.isEmpty() && count < max) {
            final var cur = queue.poll();
            final int ord = cur[0];
            final int level = cur[1];
            if (level > 0) {
                final var sym = idx.symbol(ord);
                sb.repeat("  ", level - 1)
                        .append("- ")
                        .append(sym.fqn())
                        .append(" (")
                        .append(sym.kind().name().toLowerCase(Locale.ROOT))
                        .append(")")
                        .append(" [")
                        .append(sym.file())
                        .append(':')
                        .append(sym.startLine())
                        .append('-')
                        .append(sym.endLine())
                        .append("]\n");
                count++;
                if (count >= max) {
                    break;
                }
            }
            if (depth > 0 && level >= depth) {
                continue;
            }
            var neighbors = wantOut ? idx.neighbors(ord, edgeTypes, CodeGraphIndex.Direction.OUT) : new int[0];
            if (wantIn) {
                final var in = idx.neighbors(ord, edgeTypes, CodeGraphIndex.Direction.IN);
                if (neighbors.length == 0) {
                    neighbors = in;
                } else if (in.length > 0) {
                    neighbors = Arrays.copyOf(neighbors, neighbors.length + in.length);
                    System.arraycopy(in, 0, neighbors, neighbors.length - in.length, in.length);
                }
            }
            for (final var n : neighbors) {
                if (visited.add(n)) {
                    queue.add(new int[] {n, level + 1});
                }
            }
        }
        if (count >= max) {
            sb.append("(truncated to ").append(max).append(" edges; raise max and narrow edgeTypes for more)\n");
        }
        return ToolResult.ok(sb.toString());
    }

    /** Resolve {@code symbol} to a symbol ordinal in {@code idx}: exact FQN first, then a unique simple-name suffix. */
    private int resolveOrd(final String symbol, final CodeGraphIndex idx) {
        final var exact = idx.indexOfFqn(symbol);
        if (exact >= 0) {
            return exact;
        }
        // the index stores methods as Class.method; translate the Class#method notation before the suffix fallback
        if (symbol.indexOf('#') >= 0) {
            final int dot = idx.indexOfFqn(symbol.replace('#', '.'));
            if (dot >= 0) {
                return dot;
            }
        }
        return idx.resolveSuffix(symbol);
    }

    private Set<GraphModel.EdgeType> parseEdgeTypes(final String raw) {
        if (raw == null || raw.isBlank()) {
            return EnumSet.of(GraphModel.EdgeType.CALLS);
        }
        final var result = new HashSet<GraphModel.EdgeType>();
        for (final var token : raw.split(",")) {
            if (token.isBlank()) {
                continue;
            }
            final var t = token.trim().toUpperCase(Locale.ROOT);
            if ("ALL".equals(t)) {
                return EnumSet.allOf(GraphModel.EdgeType.class);
            }
            try {
                result.add(GraphModel.EdgeType.valueOf(t));
            } catch (final IllegalArgumentException ignored) {
                // ignore unknown relation types so a typo does not abort the trace
            }
        }
        return result.isEmpty() ? EnumSet.of(GraphModel.EdgeType.CALLS) : result;
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
