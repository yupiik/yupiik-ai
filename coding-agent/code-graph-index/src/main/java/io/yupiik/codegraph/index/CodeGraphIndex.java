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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Read side of the compact columnar binary code graph index written by {@link CodeGraphIndexWriter}.
 * Framework-free: kind and edge-type are plain {@code int} ordinals, so neither the source parser nor the
 * bytecode analyzer needs this module to know their enums.
 *
 * <p>Layout (v1), all big-endian, length-prefixed UTF-8 strings:
 * <pre>
 *   magic "YCAG", version=1, long builtAt
 *   int stringCount; then per string: int len, bytes   (the pool, index 0..N-1)
 *   int symbolCount S; then per symbol: ints (fqn, kind, file, startLine, endLine, sig)
 *   int edgeCount  E; then per edge:    ints (from, to, type)
 *   int fpCount    F; then per entry:   ints (rel, sha)                      [fingerprint map]
 *   int outTotal;      outFirst[S+1], outNode[outTotal], outType[outTotal]   [CSR, from-&gt;to]
 *   int inTotal;       inFirst[S+1],  inNode[inTotal],  inType[inTotal]      [CSR, to-&gt;from]
 * </pre>
 * Strings are pooled and referenced by integer id, so repeated FQNs/signatures collapse to one copy: compact on
 * disk and on decode. {@link #indexOfFqn} is O(1) via an open-addressing hash table and {@link #neighbors} is
 * O(degree) via the CSR rows.</p>
 *
 * <p>Two loaders share the same accessors:</p>
 * <ul>
 *   <li>{@link #load(Path)}/{@link #load(byte[])} - eagerly materialize the whole index on the heap.</li>
 *   <li>{@link #mmap(Path)} - keep the file read-only mapped, minimising the resident working set for very large
 *       dependency indexes.</li>
 * </ul>
 */
public final class CodeGraphIndex {

    public enum Direction {
        OUT, // from -> to (callees)
        IN // to -> from (callers)
    }

    private final StringPool pool;
    private final int[] symFqn;
    private final int[] symKind;
    private final int[] symFile;
    private final int[] symStart;
    private final int[] symEnd;
    private final int[] symSig;
    private final int[] edgeFrom;
    private final int[] edgeTo;
    private final int[] edgeType;
    private final int[] fpRel;
    private final int[] fpSha;
    private final int[] outFirst;
    private final int[] outNode;
    private final int[] outType;
    private final int[] inFirst;
    private final int[] inNode;
    private final int[] inType;
    private final int[] fqnTable;
    private final long builtAt;
    // lazily built lowercase projections used only by find(); avoids re-lowercasing every symbol per call
    private volatile String[] findFqnLower;
    private volatile String[] findSimpleLower;

    private CodeGraphIndex(
            final StringPool pool,
            final int[] symFqn,
            final int[] symKind,
            final int[] symFile,
            final int[] symStart,
            final int[] symEnd,
            final int[] symSig,
            final int[] edgeFrom,
            final int[] edgeTo,
            final int[] edgeType,
            final int[] fpRel,
            final int[] fpSha,
            final int[] outFirst,
            final int[] outNode,
            final int[] outType,
            final int[] inFirst,
            final int[] inNode,
            final int[] inType,
            final long builtAt) {
        this.pool = pool;
        this.symFqn = symFqn;
        this.symKind = symKind;
        this.symFile = symFile;
        this.symStart = symStart;
        this.symEnd = symEnd;
        this.symSig = symSig;
        this.edgeFrom = edgeFrom;
        this.edgeTo = edgeTo;
        this.edgeType = edgeType;
        this.fpRel = fpRel;
        this.fpSha = fpSha;
        this.outFirst = outFirst;
        this.outNode = outNode;
        this.outType = outType;
        this.inFirst = inFirst;
        this.inNode = inNode;
        this.inType = inType;
        this.fqnTable = buildFqnTable(pool, symFqn);
        this.builtAt = builtAt;
    }

    public int symbolCount() {
        return symFqn.length;
    }

    public int edgeCount() {
        return edgeFrom.length;
    }

    public long builtAt() {
        return builtAt;
    }

    // ---- per-edge accessors (ordinal based, no framework types) ----

    /** Fully-qualified name of the source endpoint of edge {@code i} (0-based). */
    public String edgeFrom(final int i) {
        return pool.get(edgeFrom[i]);
    }

    /** Fully-qualified name of the target endpoint of edge {@code i} (0-based). */
    public String edgeTo(final int i) {
        return pool.get(edgeTo[i]);
    }

    /** Edge-type ordinal of edge {@code i} (0-based), interpreted by the producer. */
    public int edgeType(final int i) {
        return edgeType[i];
    }

    // ---- per-symbol accessors (ordinal based, no framework types) ----

    public String fqn(final int ord) {
        return pool.get(symFqn[ord]);
    }

    public String file(final int ord) {
        return pool.get(symFile[ord]);
    }

    public int kind(final int ord) {
        return symKind[ord];
    }

    public int startLine(final int ord) {
        return symStart[ord];
    }

    public int endLine(final int ord) {
        return symEnd[ord];
    }

    public String signature(final int ord) {
        return pool.get(symSig[ord]);
    }

    // ---- fingerprints ----

    public int fingerprintCount() {
        return fpRel.length;
    }

    public String fingerprintRel(final int ord) {
        return pool.get(fpRel[ord]);
    }

    public String fingerprintSha(final int ord) {
        return pool.get(fpSha[ord]);
    }

    /** Exact fully-qualified name lookup, O(1); {@code -1} when absent. */
    public int indexOfFqn(final String fqn) {
        int slot = fqn.hashCode() & (fqnTable.length - 1);
        while (true) {
            final int stored = fqnTable[slot];
            if (stored == 0) {
                return -1;
            }
            if (pool.get(symFqn[stored - 1]).equals(fqn)) {
                return stored - 1;
            }
            slot = (slot + 1) & (fqnTable.length - 1);
        }
    }

    /** Exact name lookup returning the symbol ordinal or {@code -1}. */
    public int resolve(final String fqn) {
        return indexOfFqn(fqn);
    }

    /**
     * Neighbor symbol ordinals of {@code nodeOrdinal} in the given direction, filtered by {@code typeOrds}
     * (empty set = all). O(degree(node)).
     */
    public int[] neighbors(final int nodeOrdinal, final Set<Integer> typeOrds, final Direction dir) {
        final int[] first = dir == Direction.IN ? inFirst : outFirst;
        final int[] node = dir == Direction.IN ? inNode : outNode;
        final int[] type = dir == Direction.IN ? inType : outType;
        final int start = first[nodeOrdinal];
        final int end = first[nodeOrdinal + 1];
        int[] out = null;
        var size = 0;
        for (var i = start; i < end; i++) {
            if (!typeOrds.isEmpty() && !typeOrds.contains(type[i])) {
                continue;
            }
            if (out == null) {
                out = new int[end - start];
            }
            out[size++] = node[i];
        }
        return out == null ? new int[0] : (size == out.length ? out : Arrays.copyOf(out, size));
    }

    /**
     * Substring search mirroring {@code code_find_code}: match on fqn and simple name, honouring kind/max.
     * {@code kindOrd < 0} disables the kind filter.
     */
    public int[] find(final String query, final int kindOrd, final int max) {
        final var q = query.toLowerCase(Locale.ROOT);
        final String[] fqnLower = findFqnLower();
        final String[] simpleLower = findSimpleLower();
        final var result = new ArrayList<Integer>(Math.min(Math.max(max, 1), symFqn.length));
        for (var i = 0; i < symFqn.length && result.size() < max; i++) {
            if (kindOrd >= 0 && symKind[i] != kindOrd) {
                continue;
            }
            if (fqnLower[i].contains(q) || simpleLower[i].contains(q)) {
                result.add(i);
            }
        }
        final int[] out = new int[result.size()];
        for (var i = 0; i < result.size(); i++) {
            out[i] = result.get(i);
        }
        return out;
    }

    private String[] findFqnLower() {
        String[] cached = findFqnLower;
        if (cached == null) {
            synchronized (this) {
                if ((cached = findFqnLower) == null) {
                    cached = new String[symFqn.length];
                    for (var i = 0; i < symFqn.length; i++) {
                        cached[i] = pool.get(symFqn[i]).toLowerCase(Locale.ROOT);
                    }
                    findFqnLower = cached;
                }
            }
        }
        return cached;
    }

    private String[] findSimpleLower() {
        String[] cached = findSimpleLower;
        if (cached == null) {
            synchronized (this) {
                if ((cached = findSimpleLower) == null) {
                    cached = new String[symFqn.length];
                    for (var i = 0; i < symFqn.length; i++) {
                        cached[i] = simple(pool.get(symFqn[i])).toLowerCase(Locale.ROOT);
                    }
                    findSimpleLower = cached;
                }
            }
        }
        return cached;
    }

    /** Unique simple-name suffix match ({@code endsWith('.simple')}); {@code -1} when 0 or more than 1. */
    public int resolveSuffix(final String simpleName) {
        int found = -1;
        for (var i = 0; i < symFqn.length; i++) {
            if (pool.get(symFqn[i]).endsWith('.' + simpleName)) {
                if (found >= 0) {
                    return -1;
                }
                found = i;
            }
        }
        return found;
    }

    private static String simple(final String fqn) {
        final var dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    // ----------------------------------------------------------------------------------------------------
    // Loaders
    // ----------------------------------------------------------------------------------------------------

    /** Heap-load and eagerly materialize every pooled string. */
    public static CodeGraphIndex load(final Path file) throws IOException {
        final byte[] all;
        try (final var ch = FileChannel.open(file, StandardOpenOption.READ)) {
            final var size = (int) ch.size();
            final var buf = ByteBuffer.allocate(size);
            int read = 0;
            while (read < size) {
                final var n = ch.read(buf);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            all = buf.array();
        }
        return decode(ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN));
    }

    /** Decode from an in-memory byte array (e.g. {@link CodeGraphIndexWriter#toBytes}). */
    public static CodeGraphIndex load(final byte[] all) {
        return decode(ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN));
    }

    /** Memory-map the file read-only and decode (minimal resident working set). */
    public static CodeGraphIndex mmap(final Path file) throws IOException {
        try (final var ch = FileChannel.open(file, StandardOpenOption.READ)) {
            final MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            return decode(buf.order(ByteOrder.BIG_ENDIAN));
        }
    }

    private static CodeGraphIndex decode(final ByteBuffer buf) {
        if (buf.get() != 'Y' || buf.get() != 'C' || buf.get() != 'A' || buf.get() != 'G') {
            throw new IllegalArgumentException("Not a YCA code graph index (bad magic)");
        }
        if (buf.get() != 1) {
            throw new IllegalArgumentException("Unsupported code graph index version");
        }
        final long builtAt = buf.getLong();
        final int stringCount = buf.getInt();
        final String[] strings = new String[stringCount];
        for (var i = 0; i < stringCount; i++) {
            final int len = buf.getInt();
            final byte[] bytes = new byte[len];
            buf.get(bytes);
            strings[i] = new String(bytes, StandardCharsets.UTF_8);
        }
        final StringPool pool = new StringPool(strings);

        final int symCount = buf.getInt();
        final int[] symFqn = new int[symCount];
        final int[] symKind = new int[symCount];
        final int[] symFile = new int[symCount];
        final int[] symStart = new int[symCount];
        final int[] symEnd = new int[symCount];
        final int[] symSig = new int[symCount];
        for (var i = 0; i < symCount; i++) {
            symFqn[i] = buf.getInt();
            symKind[i] = buf.getInt();
            symFile[i] = buf.getInt();
            symStart[i] = buf.getInt();
            symEnd[i] = buf.getInt();
            symSig[i] = buf.getInt();
        }

        final int edgeCount = buf.getInt();
        final int[] edgeFrom = new int[edgeCount];
        final int[] edgeTo = new int[edgeCount];
        final int[] edgeType = new int[edgeCount];
        for (var i = 0; i < edgeCount; i++) {
            edgeFrom[i] = buf.getInt();
            edgeTo[i] = buf.getInt();
            edgeType[i] = buf.getInt();
        }

        final int fpCount = buf.getInt();
        final int[] fpRel = new int[fpCount];
        final int[] fpSha = new int[fpCount];
        for (var i = 0; i < fpCount; i++) {
            fpRel[i] = buf.getInt();
            fpSha[i] = buf.getInt();
        }

        final int outTotal = buf.getInt();
        final int[] outFirst = ints(buf, symCount + 1);
        final int[] outNode = ints(buf, outTotal);
        final int[] outType = ints(buf, outTotal);

        final int inTotal = buf.getInt();
        final int[] inFirst = ints(buf, symCount + 1);
        final int[] inNode = ints(buf, inTotal);
        final int[] inType = ints(buf, inTotal);

        return new CodeGraphIndex(
                pool, symFqn, symKind, symFile, symStart, symEnd, symSig, edgeFrom, edgeTo, edgeType, fpRel, fpSha,
                outFirst, outNode, outType, inFirst, inNode, inType, builtAt);
    }

    private static int[] ints(final ByteBuffer buf, final int count) {
        final int[] out = new int[count];
        for (var i = 0; i < count; i++) {
            out[i] = buf.getInt();
        }
        return out;
    }

    private static int[] buildFqnTable(final StringPool pool, final int[] symFqn) {
        final int cap = nextPow2(Math.max(1, symFqn.length * 2));
        final int[] table = new int[cap]; // 0 = empty; stores symbol ordinal+1
        for (var ord = 0; ord < symFqn.length; ord++) {
            final String s = pool.get(symFqn[ord]);
            int slot = s.hashCode() & (cap - 1);
            while (table[slot] != 0) {
                slot = (slot + 1) & (cap - 1);
            }
            table[slot] = ord + 1;
        }
        return table;
    }

    private static int nextPow2(final int v) {
        return 1 << (32 - Integer.numberOfLeadingZeros(Math.max(1, v - 1)));
    }

    /** Minimal pool holding already-decoded strings. */
    static final class StringPool {
        private final String[] strings;

        StringPool(final String[] strings) {
            this.strings = strings;
        }

        String get(final int id) {
            if (id < 0 || id >= strings.length) {
                return "";
            }
            return strings[id];
        }
    }
}
