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
package io.yupiik.ia.agent.core.codegraph.index;

import io.yupiik.ia.agent.core.codegraph.GraphModel;
import io.yupiik.ia.agent.core.codegraph.GraphStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Core-side, graph-model typed adapter over the shared, framework-free {@code io.yupiik.codegraph.index.CodeGraphIndex}.
 *
 * <p>The binary format (read/write) lives in the shared {@code code-graph-index} module so the dependency knowledge
 * producer (framework-free {@code maven-repository-knowledge}) can emit the same compact sidecar {@code .bin} files
 * without depending on this module or Fusion. This adapter re-exposes the format through the {@link GraphModel}
 * types the structured-graph tools use, and disks the ordinal-based kind/edge-type coordinates back to their
 * {@link GraphModel.Kind}/{@link GraphModel.EdgeType} enum values.</p>
 */
public final class CodeGraphIndex {

    public enum Direction {
        OUT, // from -> to (callees)
        IN // to -> from (callers)
    }

    private final io.yupiik.codegraph.index.CodeGraphIndex delegate;

    private CodeGraphIndex(final io.yupiik.codegraph.index.CodeGraphIndex delegate) {
        this.delegate = delegate;
    }

    public int symbolCount() {
        return delegate.symbolCount();
    }

    public int edgeCount() {
        return delegate.edgeCount();
    }

    public long builtAt() {
        return delegate.builtAt();
    }

    /** Exact fully-qualified name lookup, O(1); {@code -1} when absent. */
    public int indexOfFqn(final String fqn) {
        return delegate.indexOfFqn(fqn);
    }

    /** The symbol at ordinal {@code ord} (0-based), backed by the shared index. */
    public GraphModel.Symbol symbol(final int ord) {
        return new GraphModel.Symbol(
                delegate.fqn(ord),
                GraphModel.Kind.values()[delegate.kind(ord)],
                delegate.file(ord),
                delegate.startLine(ord),
                delegate.endLine(ord),
                delegate.signature(ord));
    }

    /** Exact name lookup returning the symbol or {@code null}. */
    public GraphModel.Symbol resolve(final String fqn) {
        final var idx = delegate.resolve(fqn);
        return idx < 0 ? null : symbol(idx);
    }

    /**
     * Neighbor symbol ordinals of {@code nodeOrdinal} in the given direction, filtered by {@code types}
     * (empty set = all). O(degree(node)).
     */
    public int[] neighbors(final int nodeOrdinal, final Set<GraphModel.EdgeType> types, final Direction dir) {
        final Set<Integer> typeOrds = new HashSet<>(types.size());
        for (final var t : types) {
            typeOrds.add(t.ordinal());
        }
        return delegate.neighbors(nodeOrdinal, typeOrds, toShared(dir));
    }

    /** Substring search mirroring {@code code_find_code}: match on fqn and simple name, honouring kind/max. */
    public List<GraphModel.Symbol> find(final String query, final GraphModel.Kind kind, final int max) {
        final var ords = delegate.find(query, kind == null ? -1 : kind.ordinal(), max);
        final var result = new ArrayList<GraphModel.Symbol>(ords.length);
        for (final var ord : ords) {
            result.add(symbol(ord));
        }
        return result;
    }

    /** Unique simple-name suffix match ({@code endsWith('.simple')}); {@code -1} when 0 or more than 1. */
    public int resolveSuffix(final String simpleName) {
        return delegate.resolveSuffix(simpleName);
    }

    /** Decode back to the canonical {@link GraphStore} for consumers that are not index-aware. */
    public GraphStore toStore() {
        final var symbols = new ArrayList<GraphModel.Symbol>(delegate.symbolCount());
        for (var i = 0; i < delegate.symbolCount(); i++) {
            symbols.add(symbol(i));
        }
        final var edges = new ArrayList<GraphModel.Edge>(delegate.edgeCount());
        for (var i = 0; i < delegate.edgeCount(); i++) {
            edges.add(graphEdge(i));
        }
        final LinkedHashMap<String, String> fingerprints = new LinkedHashMap<>(delegate.fingerprintCount());
        for (var i = 0; i < delegate.fingerprintCount(); i++) {
            fingerprints.put(delegate.fingerprintRel(i), delegate.fingerprintSha(i));
        }
        return new GraphStore(List.copyOf(symbols), List.copyOf(edges), fingerprints, delegate.builtAt(), List.of());
    }

    private GraphModel.Edge graphEdge(final int i) {
        return new GraphModel.Edge(delegate.edgeFrom(i), delegate.edgeTo(i), edgeType(i));
    }

    private GraphModel.EdgeType edgeType(final int i) {
        return GraphModel.EdgeType.values()[delegate.edgeType(i)];
    }

    private static io.yupiik.codegraph.index.CodeGraphIndex.Direction toShared(final Direction dir) {
        return dir == Direction.IN
                ? io.yupiik.codegraph.index.CodeGraphIndex.Direction.IN
                : io.yupiik.codegraph.index.CodeGraphIndex.Direction.OUT;
    }

    // ----------------------------------------------------------------------------------------------------
    // Loaders (delegating to the shared, framework-free format module)
    // ----------------------------------------------------------------------------------------------------

    /** Heap-load and eagerly materialize every pooled string. */
    public static CodeGraphIndex load(final Path file) throws IOException {
        return new CodeGraphIndex(io.yupiik.codegraph.index.CodeGraphIndex.load(file));
    }

    /** Decode from an in-memory byte array. */
    public static CodeGraphIndex load(final byte[] all) {
        return new CodeGraphIndex(io.yupiik.codegraph.index.CodeGraphIndex.load(all));
    }

    /** Memory-map the file read-only and decode strings lazily (minimal resident working set). */
    public static CodeGraphIndex mmap(final Path file) throws IOException {
        return new CodeGraphIndex(io.yupiik.codegraph.index.CodeGraphIndex.mmap(file));
    }
}
