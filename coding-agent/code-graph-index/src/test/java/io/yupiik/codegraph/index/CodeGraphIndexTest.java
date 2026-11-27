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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeGraphIndexTest {

    @TempDir
    Path temp;

    private static IndexGraph sample() {
        return new IndexGraph(
                List.of(
                        // 0: interface RowSet / 1: impl / 2: producer (CALLS->RowSetImpl, USES->RowSet)
                        new IndexSymbol("org.hop.RowSet", 6, "RowSet.java", 1, 5, ""),
                        new IndexSymbol("org.hop.RowSetImpl", 0, "RowSetImpl.java", 1, 5, ""),
                        new IndexSymbol("org.hop.Producer", 0, "Producer.java", 1, 5, "")),
                List.of(
                        new IndexEdge("org.hop.RowSetImpl", "org.hop.RowSet", 3), // IMPLEMENTS
                        new IndexEdge("org.hop.Producer", "org.hop.RowSet", 4), // USES
                        new IndexEdge("org.hop.Producer", "org.hop.RowSetImpl", 0)), // CALLS
                Map.of("Foo.java", "abc"),
                42L);
    }

    @Test
    void roundTripLoadReproducesStore() throws Exception {
        final var p = temp.resolve("index.bin");
        CodeGraphIndexWriter.write(sample(), p);
        final var idx = CodeGraphIndex.load(p);
        assertEquals(3, idx.symbolCount());
        assertEquals(3, idx.edgeCount());
        assertEquals(42, idx.builtAt());
        assertEquals(2, idx.indexOfFqn("org.hop.Producer"));
        assertEquals(1, idx.indexOfFqn("org.hop.RowSetImpl"));
        assertEquals(-1, idx.indexOfFqn("missing"));
        assertEquals("org.hop.RowSet", idx.fqn(0));
        assertEquals(6, idx.kind(0));
        assertEquals("RowSet.java", idx.file(0));
        assertEquals(1, idx.startLine(0));
        assertEquals(5, idx.endLine(0));
        assertEquals(1, idx.fingerprintCount());
        assertEquals("Foo.java", idx.fingerprintRel(0));
        assertEquals("abc", idx.fingerprintSha(0));
    }

    @Test
    void roundTripMmapReproducesStore() throws Exception {
        final var p = temp.resolve("index.bin");
        CodeGraphIndexWriter.write(sample(), p);
        final var idx = CodeGraphIndex.mmap(p);
        assertEquals(3, idx.symbolCount());
        assertEquals(2, idx.indexOfFqn("org.hop.Producer"));
        assertEquals("org.hop.RowSet", idx.fqn(0));
    }

    @Test
    void toBytesMatchesFileBytes() throws Exception {
        final var bytes = CodeGraphIndexWriter.toBytes(sample());
        final var p = temp.resolve("index.bin");
        CodeGraphIndexWriter.write(sample(), p);
        assertArrayEquals(bytes, Files.readAllBytes(p));
    }

    @Test
    void neighborsReflectEdges() throws Exception {
        final var p = temp.resolve("index.bin");
        CodeGraphIndexWriter.write(sample(), p);
        final var idx = CodeGraphIndex.load(p);
        final var out = idx.neighbors(2, Set.of(), CodeGraphIndex.Direction.OUT);
        assertEquals(2, out.length);
        final var in = idx.neighbors(0, Set.of(), CodeGraphIndex.Direction.IN);
        assertEquals(2, in.length);
        final var callsOnly = idx.neighbors(2, Set.of(0), CodeGraphIndex.Direction.OUT);
        assertArrayEquals(new int[] {1}, callsOnly); // RowSetImpl
    }

    @Test
    void findHonorsKindAndLimit() throws Exception {
        final var p = temp.resolve("index.bin");
        CodeGraphIndexWriter.write(sample(), p);
        final var idx = CodeGraphIndex.load(p);
        final var all = idx.find("RowSet", -1, 10);
        assertEquals(2, all.length); // RowSet + RowSetImpl
        final var interfaces = idx.find("RowSet", 6, 10);
        assertArrayEquals(new int[] {0}, interfaces);
        final var limited = idx.find("RowSet", -1, 1);
        assertEquals(1, limited.length);
    }

    @Test
    void resolveSuffixFindsUniqueSimpleName() throws Exception {
        final var p = temp.resolve("index.bin");
        CodeGraphIndexWriter.write(sample(), p);
        final var idx = CodeGraphIndex.load(p);
        assertEquals(2, idx.resolveSuffix("Producer"));
        assertEquals(-1, idx.resolveSuffix("NotThere"));
    }

    @Test
    void selfLoopIsCycleSafe() throws Exception {
        final var g = new IndexGraph(
                List.of(
                        new IndexSymbol("a.Cls", 0, "Cls.java", 1, 5, ""),
                        new IndexSymbol("a.Cls.loop", 1, "Cls.java", 6, 10, "")),
                List.of(
                        new IndexEdge("a.Cls", "a.Cls", 2), // EXTENDS self
                        new IndexEdge("a.Cls.loop", "a.Cls.loop", 0)), // CALLS self
                Map.of(),
                1L);
        final var p = temp.resolve("self.bin");
        CodeGraphIndexWriter.write(g, p);
        final var idx = CodeGraphIndex.load(p);
        final int loopOrd = idx.indexOfFqn("a.Cls.loop");
        assertTrue(loopOrd >= 0);
        assertArrayEquals(new int[] {loopOrd}, idx.neighbors(loopOrd, Set.of(0), CodeGraphIndex.Direction.OUT));
        final int clsOrd = idx.indexOfFqn("a.Cls");
        assertArrayEquals(new int[] {clsOrd}, idx.neighbors(clsOrd, Set.of(2), CodeGraphIndex.Direction.IN));
    }
}
