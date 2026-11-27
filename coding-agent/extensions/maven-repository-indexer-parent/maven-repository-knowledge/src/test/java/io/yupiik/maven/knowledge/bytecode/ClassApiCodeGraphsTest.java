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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.codegraph.index.CodeGraphIndex;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassApiCodeGraphsTest {

    private static ClassApi api(
            final String fqn,
            final String kind,
            final String superclass,
            final List<String> interfaces,
            final MethodApi... methods) {
        return ClassApi.of(fqn, "public", kind, superclass, interfaces, List.of(), List.of(methods));
    }

    private static CodeGraphIndex load(final byte[] bytes) throws IOException {
        return CodeGraphIndex.load(bytes);
    }

    @Test
    void mapsClassesAndMethodsWithEdges() throws IOException {
        final var bytes = ClassApiCodeGraphs.toBytes(
                List.of(
                        api("com.example.Bar", "interface", null, List.of()),
                        api("com.example.Base", "class", "java.lang.Object", List.of()),
                        api(
                                "com.example.Foo",
                                "class",
                                "com.example.Base",
                                List.of("com.example.Bar"),
                                new MethodApi("run", "void", List.of("java.lang.String"), "public", false))),
                null);
        final var idx = load(bytes);
        assertEquals(4, idx.symbolCount()); // Bar, Base, Foo, Foo.run
        assertTrue(idx.indexOfFqn("com.example.Bar") >= 0);
        assertTrue(idx.indexOfFqn("com.example.Base") >= 0);
        assertTrue(idx.indexOfFqn("com.example.Foo") >= 0);
        assertTrue(idx.indexOfFqn("com.example.Foo.run") >= 0);
        final var foo = idx.indexOfFqn("com.example.Foo");
        assertTrue(idx.neighbors(foo, Set.of(3), CodeGraphIndex.Direction.OUT).length >= 1); // IMPLEMENTS Bar
        assertTrue(idx.neighbors(foo, Set.of(2), CodeGraphIndex.Direction.OUT).length >= 1); // EXTENDS Base
        assertTrue(idx.indexOfFqn("java.lang.Object") < 0); // external FQN is dropped, not a symbol
    }

    @Test
    void excludesPackagePrefix() throws IOException {
        final var bytes = ClassApiCodeGraphs.toBytes(
                List.of(
                        api("com.example.keep.Keep", "class", null, List.of()),
                        api("com.example.drop.Dropped", "class", null, List.of()),
                        api("com.example.drop.Sub", "class", null, List.of())),
                fqn -> !(fqn.equals("com.example.drop") || fqn.startsWith("com.example.drop.")));
        final var idx = load(bytes);
        assertTrue(idx.indexOfFqn("com.example.keep.Keep") >= 0);
        assertEquals(-1, idx.indexOfFqn("com.example.drop.Dropped"));
        assertEquals(-1, idx.indexOfFqn("com.example.drop.Sub"));
    }

    @Test
    void excludesExactFqn() throws IOException {
        final var bytes = ClassApiCodeGraphs.toBytes(
                List.of(api("com.example.A", "class", null, List.of()), api("com.example.B", "class", null, List.of())),
                fqn -> !fqn.equals("com.example.A"));
        final var idx = load(bytes);
        assertEquals(-1, idx.indexOfFqn("com.example.A"));
        assertTrue(idx.indexOfFqn("com.example.B") >= 0);
        assertFalse(load(bytes).symbolCount() < 1);
    }
}
