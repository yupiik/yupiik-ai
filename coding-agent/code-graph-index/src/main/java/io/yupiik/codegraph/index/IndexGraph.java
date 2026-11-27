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
package io.yupiik.codegraph.index;

import java.util.List;
import java.util.Map;

/**
 * Framework-free input model for the compact binary code graph index. Written at indexation time by whichever
 * producer (the structural-code-graph source parser or the Maven-repository bytecode analyzer), read back by the
 * shared {@link CodeGraphIndex} reader. Kind and edge-type are stored as {@code int} ordinals so the shared module
 * has no dependency on any framework or enum: each producer maps its own kind/edge-type enum to an ordinal.
 *
 * @param symbols the indexed symbols (nodes)
 * @param edges the typed edges (from FQN to FQN)
 * @param fingerprints optional rel-path {@literal ->} digest map (used by the source graph for freshness)
 * @param builtAt epoch millis of the last (re)index
 */
public record IndexGraph(
        List<IndexSymbol> symbols, List<IndexEdge> edges, Map<String, String> fingerprints, long builtAt) {}
