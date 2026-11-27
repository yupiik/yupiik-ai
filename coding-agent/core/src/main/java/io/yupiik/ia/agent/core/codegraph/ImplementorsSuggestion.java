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

import io.yupiik.ia.agent.core.codegraph.index.CodeGraphIndex;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Produces a "did you mean" hint when a hierarchy trace lands on a concrete class instead of an interface:
 * walks the class's direct subclasses (EXTENDS edges into the target) and tallies the interfaces they implement,
 * so the model can retrace the intended interface in one call. JDK noise interfaces (Serializable, Cloneable, etc.)
 * are excluded from the tally.
 */
final class ImplementorsSuggestion {

    private static final Set<String> NOISE_INTERFACES = Set.of(
            "java.lang.Cloneable",
            "java.io.Serializable",
            "java.lang.Comparable",
            "java.io.Closeable",
            "java.lang.AutoCloseable",
            "java.lang.Iterable",
            "java.lang.Readable");

    private ImplementorsSuggestion() {}

    /**
     * Walk direct subclasses of the concrete class at {@code targetOrd} and tally the interfaces they implement.
     * Returns a human-readable suggestion string or {@code null} when nothing useful is found.
     */
    static String suggest(final CodeGraphIndex idx, final int targetOrd) {
        final var subclasses =
                idx.neighbors(targetOrd, EnumSet.of(GraphModel.EdgeType.EXTENDS), CodeGraphIndex.Direction.IN);
        if (subclasses.length == 0) {
            return null;
        }
        final Map<String, Integer> tally = new HashMap<>();
        for (final var sub : subclasses) {
            final var impls =
                    idx.neighbors(sub, EnumSet.of(GraphModel.EdgeType.IMPLEMENTS), CodeGraphIndex.Direction.OUT);
            for (final var impl : impls) {
                final var fqn = idx.symbol(impl).fqn();
                if (NOISE_INTERFACES.contains(fqn)) {
                    continue;
                }
                tally.merge(fqn, 1, Integer::sum);
            }
        }
        if (tally.isEmpty()) {
            return null;
        }
        String best = null;
        int bestCount = 0;
        for (final var e : tally.entrySet()) {
            if (best == null
                    || e.getValue() > bestCount
                    || (e.getValue() == bestCount && e.getKey().length() > best.length())) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        if (best == null) {
            return null;
        }
        return bestCount
                + " of its " + subclasses.length
                + " direct subclass(es) implement " + best
                + " - retrace with symbol=" + best
                + " (edgeTypes=implements) for the implementor list";
    }
}
