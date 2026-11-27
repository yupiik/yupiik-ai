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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.testutil.JavacWarmup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaGraphParserTest {

    static {
        // shares the single per-JVM javac warmup with the other compiler-hungry tests
        JavacWarmup.warm();
    }

    @TempDir
    Path tempDir;

    @Test
    void parsesTypesMethodsAndCallEdges() throws IOException {
        final var parser = new JavaGraphParser();
        assertTrue(parser.available(), "expected a JDK with javac on the test environment");
        assertEquals(Set.of("java"), parser.extensions());
        assertTrue(parser.jdkRequired());

        final var src = tempDir.resolve("A.java");
        Files.writeString(src, """
                package sample;
                import java.util.List;
                public class A {
                    private int x;
                    public void run() { BHelper.helper(); }
                    public List<String> list() { return null; }
                }
                """);
        final var model = parser.parse(src);
        assertEquals("java", model.language());

        final var typeNames = model.symbols().stream()
                .filter(s -> s.kind() == GraphModel.Kind.TYPE)
                .map(GraphModel.Symbol::fqn)
                .toList();
        assertTrue(typeNames.contains("sample.A"), "expected the type symbol, got " + typeNames);

        final var methodNames = model.symbols().stream()
                .filter(s -> s.kind() == GraphModel.Kind.METHOD)
                .map(GraphModel.Symbol::fqn)
                .toList();
        assertTrue(methodNames.contains("sample.A.run"), "expected method symbol, got " + methodNames);

        final var edgeTypes = model.edges().stream().map(GraphModel.Edge::type).toList();
        assertTrue(edgeTypes.contains(GraphModel.EdgeType.CALLS), "expected a CALLS edge, got " + edgeTypes);
    }

    @Test
    void declaresNoEdgesForFieldParameterAndLocalTypes() throws IOException {
        final var parser = new JavaGraphParser();

        final var src = tempDir.resolve("Contact.java");
        Files.writeString(src, """
                package sample;
                public class Contact {
                    private int id;
                    private double score;
                    private boolean active;
                    private String name;
                    private long timestamp;
                    public String label(String prefix) { return prefix + name; }
                }
                """);
        final var model = parser.parse(src);

        // Field, parameter and local variable types are receiver-typing hints only (graft's
        // collectBindings contract) - they must never become edges, so primitives and java.lang
        // types cannot leak as junk USES edges.
        assertTrue(model.edges().isEmpty(), "expected no edges from declaration types, got " + model.edges());
    }

    @Test
    void heritageKeepsExternalRawNames() throws IOException {
        final var parser = new JavaGraphParser();

        final var src = tempDir.resolve("Child.java");
        Files.writeString(src, """
                package sample;
                class Child extends java.awt.Frame implements java.io.ExternalMarker {
                }
                """);
        final var model = parser.parse(src);

        // An unresolved/external base or interface keeps its raw qualified name as the target
        // (graft's 'external type' contract) instead of being dropped or fabricated.
        assertTrue(
                model.edges().stream()
                        .anyMatch(e -> e.type() == GraphModel.EdgeType.EXTENDS && e.to().equals("java.awt.Frame")),
                "expected EXTENDS edge keeping the qualified external name, got " + model.edges());
        assertTrue(
                model.edges().stream()
                        .anyMatch(e ->
                                e.type() == GraphModel.EdgeType.IMPLEMENTS && e.to().equals("java.io.ExternalMarker")),
                "expected IMPLEMENTS edge keeping the qualified external name, got " + model.edges());
    }

    @Test
    void constructionEmitsCallOnlyForInPackageTypes() throws IOException {
        final var parser = new JavaGraphParser();

        final var src = tempDir.resolve("Factory.java");
        Files.writeString(src, """
                package sample;
                public class Factory {
                    private Widget widget;
                    public Object make() {
                        widget = new Widget();
                        return new String();
                    }
                }
                """);
        final var model = parser.parse(src);

        // new Widget() (in-package type) is a CALLS edge; new String() (java.lang) is dropped.
        assertTrue(
                model.edges().stream()
                        .anyMatch(e -> e.type() == GraphModel.EdgeType.CALLS && e.to().equals("sample.Widget")),
                "expected CALLS edge to the in-package constructed type, got " + model.edges());
        assertTrue(
                model.edges().stream()
                        .noneMatch(e -> e.type() == GraphModel.EdgeType.CALLS && e.to().contains("String")),
                "expected no CALLS edge for java.lang construction, got " + model.edges());
    }

    @Test
    void parsesImportedTypesNotOnClasspath() throws IOException {
        final var parser = new JavaGraphParser();

        final var src = tempDir.resolve("UsesService.java");
        Files.writeString(src, """
                package sample;
                import not.existing.Service;
                public class UsesService {
                    public Service newService() { return new Service(); }
                }
                """);
        final var model = parser.parse(src);

        final var importEdges = model.edges().stream()
                .filter(e -> e.type() == GraphModel.EdgeType.IMPORTS)
                .map(GraphModel.Edge::to)
                .toList();
        assertTrue(
                importEdges.contains("not.existing.Service"),
                "expected the off-classpath type to be seen as imported, got " + importEdges);

        // new Service() is an off-classpath import - drop the construction edge (graft drops
        // unresolved construction); the IMPORTS edge already records the dependency.
        assertTrue(
                model.edges().stream()
                        .noneMatch(e -> e.type() == GraphModel.EdgeType.CALLS && e.to().contains("Service")),
                "expected off-classpath construction to be dropped, got " + model.edges());
    }

    @Test
    void collapsesRepeatedCallsToSameTarget() throws IOException {
        final var parser = new JavaGraphParser();

        final var src = tempDir.resolve("Repeated.java");
        Files.writeString(src, """
                package sample;
                import static sample.Helper.invoke;
                public class Repeated {
                    public void run() {
                        invoke();
                        invoke();
                        invoke();
                    }
                }
                """);
        final var model = parser.parse(src);

        // Multiple call sites to the same target from the same owner collapse into a single
        // edge (graft's model), so the markdown tree has exactly one --CALLS--> per pair.
        final var callEdges = model.edges().stream()
                .filter(e -> e.type() == GraphModel.EdgeType.CALLS)
                .toList();
        assertEquals(1, callEdges.size(), "expected exactly one CALLS edge for repeated invocations, got " + callEdges);
    }
}
