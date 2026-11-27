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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Writer for the {@link CodeGraphIndex} binary format (v1). Called once at indexation time; reads an {@link
 * IndexGraph} and emits a compact, columnar, byte-stable index. Strings are pooled and deduplicated via a
 * {@link LinkedHashMap} so repeated FQNs/signatures/files collapse to a single pool entry.
 *
 * <p>Layout mirrors the reader exactly; see {@link CodeGraphIndex} for the field-by-field spec. Framework-free:
 * kind and edge-type come in as {@code int} ordinals and are written as-is.</p>
 */
public final class CodeGraphIndexWriter {

    private CodeGraphIndexWriter() {
        // static utility
    }

    /**
     * Serialize {@code graph} to {@code target}; written atomically (temp file + ATOMIC_MOVE) so a crash never
     * leaves a truncated index.
     */
    public static void write(final IndexGraph graph, final Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        writeTo(tmp, graph);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Serialize to {@code target} non-atomically (used by the benchmark to avoid tmp churn). */
    public static void writeTo(final Path target, final IndexGraph graph) throws IOException {
        try (final var out = new DataOutputStream(Files.newOutputStream(target))) {
            write(out, graph);
        }
    }

    /** Serialize to an in-memory byte array (useful for tests / in-memory comparisons). */
    public static byte[] toBytes(final IndexGraph graph) {
        final var buf = new ByteArrayOutputStream();
        try (final var out = new DataOutputStream(buf)) {
            write(out, graph);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return buf.toByteArray();
    }

    static void write(final DataOutputStream out, final IndexGraph graph) throws IOException {
        // ---- string pool ----
        final var pool = new LinkedHashMap<String, Integer>();
        final var symbols = graph.symbols();
        final var edges = graph.edges();
        for (final var s : symbols) {
            pool.computeIfAbsent(nul(s.fqn()), k -> 0);
            pool.computeIfAbsent(nul(s.file()), k -> 0);
            pool.computeIfAbsent(nul(s.signature()), k -> 0);
        }
        for (final var e : edges) {
            pool.computeIfAbsent(nul(e.from()), k -> 0);
            pool.computeIfAbsent(nul(e.to()), k -> 0);
        }
        for (final var e : graph.fingerprints().entrySet()) {
            pool.computeIfAbsent(nul(e.getKey()), k -> 0);
            pool.computeIfAbsent(nul(e.getValue()), k -> 0);
        }
        final var ids = new HashMap<String, Integer>();
        final var ordered = new ArrayList<String>();
        var next = 0;
        for (final String s : pool.keySet()) {
            ids.put(s, next);
            ordered.add(s);
            next++;
        }

        out.write('Y');
        out.write('C');
        out.write('A');
        out.write('G');
        out.write(1); // version
        out.writeLong(graph.builtAt());

        out.writeInt(ordered.size());
        for (final String s : ordered) {
            final byte[] bytes = s == null ? new byte[0] : s.getBytes(UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        }

        // ---- symbols ----
        final Map<String, Integer> fqnOrdinal = new HashMap<>(symbols.size());
        for (var i = 0; i < symbols.size(); i++) {
            fqnOrdinal.put(nul(symbols.get(i).fqn()), i);
        }
        out.writeInt(symbols.size());
        for (final var s : symbols) {
            out.writeInt(id(ids, nul(s.fqn())));
            out.writeInt(s.kind());
            out.writeInt(id(ids, nul(s.file())));
            out.writeInt(s.startLine());
            out.writeInt(s.endLine());
            out.writeInt(id(ids, nul(s.signature())));
        }

        // ---- edges ----
        out.writeInt(edges.size());
        for (final var e : edges) {
            out.writeInt(id(ids, nul(e.from())));
            out.writeInt(id(ids, nul(e.to())));
            out.writeInt(e.type());
        }

        // ---- fingerprints ----
        final var fps = graph.fingerprints();
        out.writeInt(fps.size());
        for (final var e : fps.entrySet()) {
            out.writeInt(id(ids, nul(e.getKey())));
            out.writeInt(id(ids, nul(e.getValue())));
        }

        // ---- adjacency (CSR over symbol ordinals) ----
        // Resolve every edge once to (fromOrd, toOrd, typeOrd) in O(E); null pair means the endpoint is not a
        // symbol (external reference) and is dropped from the adjacency (kept only in the edge list). Then bucket
        // per node with growing lists so this whole block is O(n + E), never O(n * E).
        final int n = symbols.size();
        final var outCount = new int[n];
        final var inCount = new int[n];
        final int[][] outEdge = new int[n][], inEdge = new int[n][];
        Arrays.fill(outEdge, new int[0]);
        Arrays.fill(inEdge, new int[0]);
        for (final var e : edges) {
            final Integer fromOrd = fqnOrdinal.get(nul(e.from()));
            final Integer toOrd = fqnOrdinal.get(nul(e.to()));
            if (fromOrd == null || toOrd == null) {
                continue; // edge to/from a non-symbol (external) is not in the adjacency
            }
            final int type = e.type();
            outEdge[fromOrd] = append(outEdge[fromOrd], outCount[fromOrd]++, toOrd, type);
            inEdge[toOrd] = append(inEdge[toOrd], inCount[toOrd]++, fromOrd, type);
        }

        final int[] outFirst = new int[n + 1];
        final int[] outNodeArr =
                new int[IntStream.range(0, n).map(i -> outCount[i]).sum()];
        final int[] outTypeArr = new int[outNodeArr.length];
        var outPos = 0;
        for (var i = 0; i < n; i++) {
            outFirst[i] = outPos;
            final int[] row = outEdge[i];
            int k = 0;
            for (var j = 0; j < outCount[i]; j++, k += 2) {
                outNodeArr[outPos] = row[k];
                outTypeArr[outPos] = row[k + 1];
                outPos++;
            }
        }
        outFirst[n] = outPos;

        final int[] inFirst = new int[n + 1];
        final int[] inNodeArr =
                new int[IntStream.range(0, n).map(i -> inCount[i]).sum()];
        final int[] inTypeArr = new int[inNodeArr.length];
        var inPos = 0;
        for (var i = 0; i < n; i++) {
            inFirst[i] = inPos;
            final int[] row = inEdge[i];
            int k = 0;
            for (var j = 0; j < inCount[i]; j++, k += 2) {
                inNodeArr[inPos] = row[k];
                inTypeArr[inPos] = row[k + 1];
                inPos++;
            }
        }
        inFirst[n] = inPos;

        out.writeInt(outNodeArr.length);
        writeArr(out, outFirst);
        writeArr(out, outNodeArr);
        writeArr(out, outTypeArr);
        out.writeInt(inNodeArr.length);
        writeArr(out, inFirst);
        writeArr(out, inNodeArr);
        writeArr(out, inTypeArr);

        out.flush();
    }

    private static void writeArr(final DataOutputStream out, final int[] arr) throws IOException {
        for (final int v : arr) {
            out.writeInt(v);
        }
    }

    /** Grow-when-full store of an (a, b) pair at index {@code idx} (each entry uses two slots). */
    private static int[] append(final int[] arr, final int idx, final int a, final int b) {
        int[] out = arr;
        final int need = (idx + 1) * 2;
        if (out.length < need) {
            out = Arrays.copyOf(out, Math.max(need, out.length * 2));
        }
        out[idx * 2] = a;
        out[idx * 2 + 1] = b;
        return out;
    }

    private static int id(final Map<String, Integer> ids, final String s) {
        final var v = ids.get(s);
        return v == null ? -1 : v;
    }

    private static String nul(final String s) {
        return s == null ? "" : s;
    }
}
