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

import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the machine-only per-symbol code graph to {@code <storeRoot>/.graph/wiring.json}, mirroring Graft's
 * {@code wiring.json}. This is the tier-1 graph: every symbol and every edge, deterministic, no model, no
 * network. Output is sorted (nodes by id, edges by source/relation/target) and carries no timestamps, so
 * rebuilding an unchanged repo produces a byte-identical file and git diffs stay minimal.
 *
 * <p>Modeled on Graft ({@link https://github.com/trailhq/Graft}): written to a hidden subdir because it is
 * machine-only (the agent reaches the wiring data through grep, the per-file cards and the graph tools),
 * and written atomically (temp file + rename) so a killed build can never leave a truncated JSON file.
 */
public class WiringGraphWriter {

    private WiringGraphWriter() {
        // utility
    }

    public static Path pathFor(final Path storeRoot) {
        return storeRoot.resolve(".graph").resolve("wiring.json");
    }

    /**
     * Deterministic, sorted, byte-stable serialization of the per-symbol graph. Returns the written path. The
     * enrichment cache (when supplied) contributes per-symbol summaries, crux and state; a null cache yields a
     * structural-only wiring graph.
     */
    public static Path write(
            final JsonMapper mapper, final Path storeRoot, final GraphStore store, final DocEnricherCache cache) {
        final var path = pathFor(storeRoot);
        try {
            Files.createDirectories(path.getParent());
            final var tmp =
                    path.resolveSibling("wiring.json." + ProcessHandle.current().pid() + ".tmp");
            Files.writeString(tmp, render(mapper, store, cache), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    static String render(final JsonMapper mapper, final GraphStore store, final DocEnricherCache cache) {
        final var nodes = new ArrayList<>(store.symbols());
        nodes.sort(Comparator.comparing(GraphModel.Symbol::fqn));
        final var edges = new ArrayList<>(store.edges());
        edges.sort(Comparator.comparing(GraphModel.Edge::from)
                .thenComparing(GraphModel.Edge::type)
                .thenComparing(GraphModel.Edge::to));

        final var nodeList = new ArrayList<Map<String, Object>>(nodes.size());
        for (final var s : nodes) {
            final var m = new LinkedHashMap<String, Object>();
            final var name = simpleName(s.fqn());
            final var id = s.file() + "#" + name;
            m.put("id", id);
            m.put("name", name);
            m.put("kind", s.kind().name().toLowerCase(Locale.ROOT));
            m.put("path", s.file());
            m.put("span", "L" + s.startLine() + "-" + s.endLine());
            m.put("signature", s.signature());
            final var owner = owner(s.fqn(), s.kind());
            if (owner != null) {
                m.put("owner", owner);
            }
            m.put("origin", "ast");
            m.put("body_hash", symbolBodyHash(s));
            final var summary = cache == null ? null : cache.symbol(cache.symbolKey(s.file(), s.fqn()));
            if (summary != null) {
                m.put("summary", summary.summary());
                m.put("crux", summary.crux());
            }
            m.put("summary_state", summaryState(s, summary));
            nodeList.add(m);
        }
        final var edgeList = new ArrayList<Map<String, Object>>(edges.size());
        for (final var e : edges) {
            final var m = new LinkedHashMap<String, Object>();
            m.put("source", e.from());
            m.put("relation", e.type().name());
            m.put("target", e.to());
            m.put("confidence", "extracted");
            edgeList.add(m);
        }
        final var root = new LinkedHashMap<String, Object>();
        root.put("nodes", nodeList);
        root.put("edges", edgeList);
        return mapper.toString(root).stripTrailing() + "\n";
    }

    private static String simpleName(final String fqn) {
        var name = fqn.substring(fqn.lastIndexOf('.') + 1);
        final var idx = name.lastIndexOf('$');
        if (idx >= 0) {
            name = name.substring(idx + 1);
        }
        return name;
    }

    private static String owner(final String fqn, final GraphModel.Kind kind) {
        if (kind != GraphModel.Kind.FIELD && kind != GraphModel.Kind.METHOD && kind != GraphModel.Kind.CONSTRUCTOR) {
            return null;
        }
        final var idx = fqn.lastIndexOf('.');
        return idx < 0 ? null : fqn.substring(0, idx);
    }

    /**
     * The per-symbol content hash used both as the tier-2 cache key and the wiring {@code body_hash}. Computed
     * from the symbol's own identity (fqn, kind, file, span, signature), so editing a definition changes its
     * hash and marks it {@code stale} until re-enriched.
     */
    private static String symbolBodyHash(final GraphModel.Symbol symbol) {
        return DocEnricherCache.symbolBodyDigest(symbol);
    }

    private static String summaryState(final GraphModel.Symbol symbol, final DocEnricherCache.SymbolEntry entry) {
        if (entry == null) {
            return "pending";
        }
        return entry.digest().equals(DocEnricherCache.symbolBodyDigest(symbol)) ? "ready" : "stale";
    }
}
