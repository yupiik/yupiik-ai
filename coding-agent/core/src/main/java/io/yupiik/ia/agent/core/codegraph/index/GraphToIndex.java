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

import io.yupiik.codegraph.index.IndexEdge;
import io.yupiik.codegraph.index.IndexGraph;
import io.yupiik.codegraph.index.IndexSymbol;
import io.yupiik.ia.agent.core.codegraph.GraphStore;

/**
 * Single source of truth for bridging the core {@link GraphStore} (graph-model typed) into the shared,
 * framework-free {@link IndexGraph} binary coordinates used by the {@code code-graph-index} format.
 *
 * <p>Both the in-process indexer and the spawned-JVM parser helper must use the exact same ordinal mapping
 * (kind/edge-type enum {@code ordinal()}, file as the symbol's relative path), so a graph produced by one side is
 * byte-identical when produced by the other. This class centralises that bridge so the two cannot drift.</p>
 */
public final class GraphToIndex {

    private GraphToIndex() {
        // static utility
    }

    /** Bridge a {@link GraphStore} into the shared, framework-free {@link IndexGraph} binary format. */
    public static IndexGraph toIndex(final GraphStore store) {
        return new IndexGraph(
                store.symbols().stream()
                        .map(s -> new IndexSymbol(
                                s.fqn(), s.kind().ordinal(), s.file(), s.startLine(), s.endLine(), s.signature()))
                        .toList(),
                store.edges().stream()
                        .map(e -> new IndexEdge(e.from(), e.to(), e.type().ordinal()))
                        .toList(),
                store.fingerprints(),
                store.builtAt());
    }
}
