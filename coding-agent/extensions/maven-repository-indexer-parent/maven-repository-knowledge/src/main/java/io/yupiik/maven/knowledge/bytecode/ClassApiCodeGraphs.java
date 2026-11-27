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
package io.yupiik.maven.knowledge.bytecode;

import io.yupiik.codegraph.index.CodeGraphIndexWriter;
import io.yupiik.codegraph.index.IndexEdge;
import io.yupiik.codegraph.index.IndexGraph;
import io.yupiik.codegraph.index.IndexSymbol;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Builds a compact binary code graph ({@code code-graph-index} format) from a list of {@link ClassApi} derived via
 * the JDK Class-File API. Framework-free so it can be reused by both the indexer sidecar and standalone CLI commands.
 *
 * <p>Kind/type ordinals follow the shared scheme ({@code TYPE=0, METHOD=1, ENUM=4, INTERFACE=6, ANNOTATION=7};
 * {@code EXTENDS=2, IMPLEMENTS=3}). Entries whose class fails the optional {@code classFilter} are skipped entirely;
 * edges whose endpoint is not a symbol are dropped by the writer's CSR pass.
 */
public final class ClassApiCodeGraphs {

    private ClassApiCodeGraphs() {}

    /**
     * @param apis        the class surface, typically from {@link BytecodeAnalyzer#analyze(java.nio.file.Path)}.
     * @param classFilter inclusion predicate for a class FQN, or {@code null}/{@code x -> true} to keep everything.
     * @return the binary payload ready to be persisted with {@link CodeGraphIndexWriter#write}.
     */
    public static byte[] toBytes(final List<ClassApi> apis, final Predicate<String> classFilter) {
        return CodeGraphIndexWriter.toBytes(indexGraph(apis, classFilter));
    }

    /**
     * @param apis        the class surface, typically from {@link BytecodeAnalyzer#analyze(java.nio.file.Path)}.
     * @param classFilter inclusion predicate for a class FQN, or {@code null} to keep everything.
     * @return the in-memory {@link IndexGraph}.
     */
    public static IndexGraph indexGraph(final List<ClassApi> apis, final Predicate<String> classFilter) {
        final var include = classFilter == null ? (Predicate<String>) x -> true : classFilter;
        final var symbols = new ArrayList<IndexSymbol>();
        final var edges = new ArrayList<IndexEdge>();
        for (final var api : apis) {
            if (!include.test(api.className())) {
                continue;
            }
            symbols.add(new IndexSymbol(api.className(), kindOrdinal(api.kind()), "", 0, 0, ""));
            final var className = api.className();
            for (final var method : api.methods()) {
                final var sig =
                        method.name() + "(" + String.join(", ", method.parameterTypes()) + ") : " + method.returnType();
                symbols.add(new IndexSymbol(className + "." + method.name(), 1, className, 0, 0, sig));
            }
            if (api.superclass() != null) {
                edges.add(new IndexEdge(className, api.superclass(), 2));
            }
            for (final var iface : api.interfaces()) {
                edges.add(new IndexEdge(className, iface, 3));
            }
        }
        return new IndexGraph(List.copyOf(symbols), List.copyOf(edges), Map.of(), System.currentTimeMillis());
    }

    /**
     * Maps a JAR entry/class FQN {@code kind} to the shared ordinal ({@code TYPE=0, METHOD=1, ENUM=4, INTERFACE=6,
     * ANNOTATION=7}). Unknown/default kinds (class/record/abstract class) map to {@code TYPE}.
     */
    private static int kindOrdinal(final String kind) {
        return switch (kind) {
            case "interface" -> 6;
            case "enum" -> 4;
            case "annotation" -> 7;
            default -> 0; // class / record / abstract class
        };
    }
}
