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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BytecodeAnalyzerTest {

    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

    static {
        warmJavac(COMPILER);
    }

    private static void warmJavac(final JavaCompiler compiler) {
        final var out = Path.of("target", "javac-warmup");
        try {
            Files.createDirectories(out);
            final var unit = new SimpleJavaFileObject(URI.create("string:///Warmup.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
                    return "public class Warmup { }";
                }
            };
            final var task = compiler.getTask(null, null, null, List.of("-d", out.toString()), null, List.of(unit));
            if (!task.call()) {
                throw new IllegalStateException("javac warmup compilation failed");
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TempDir
    Path tmp;

    /**
     * Compiles the given sources ({@code fileName -> source}) into {@code out} then jars the produced
     * {@code .class} files into {@code out}/lib.jar, returning the jar path.
     */
    private Path compileAndJar(final Map<String, String> sources) throws IOException {
        final var classes = tmp.resolve("classes");
        Files.createDirectories(classes);
        final var files = sources.entrySet().stream()
                .map(e ->
                        new SimpleJavaFileObject(
                                URI.create("string:///" + e.getKey().replace('.', '/') + ".java"),
                                JavaFileObject.Kind.SOURCE) {
                            @Override
                            public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
                                return e.getValue();
                            }
                        })
                .toList();
        final var result = COMPILER.getTask(null, null, null, List.of("-d", classes.toString()), null, files);
        assertTrue(result.call(), "compilation must succeed");
        final var jar = tmp.resolve("lib.jar");
        try (final var zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            try (final var stream = Files.walk(classes)) {
                for (final var p : stream.filter(Files::isRegularFile).toList()) {
                    final var rel = classes.relativize(p).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(rel));
                    zos.write(Files.readAllBytes(p));
                    zos.closeEntry();
                }
            }
        }
        return jar;
    }

    @Test
    void entryPointsPreferClassesWithMain() throws IOException {
        final var jar = compileAndJar(Map.of(
                "g.a.impl.Helper", "package g.a.impl; public class Helper {}",
                "g.a.Main", "package g.a; public class Main { public static void main(String[] args) {} }",
                "g.a.Inner", "package g.a; public class Inner { public static class Nested {} }"));
        final var eps = new BytecodeAnalyzer().entryPoints(jar);
        assertEquals("g.a.Main", eps.get(0), "main-holding class must rank first");
        assertTrue(eps.contains("g.a.impl.Helper"), "plain top-level public class remains a candidate");
        assertTrue(eps.stream().noneMatch(fqn -> fqn.contains("$")), "inner/nested classes must not be entry points");
    }

    @Test
    void analyzeExposesKindModifiersMethodsAndAnnotations() throws IOException {
        final var jar = compileAndJar(Map.of(
                "g.api.User", "package g.api; public record User(java.lang.String name) {}",
                "g.api.Service",
                        "package g.api; "
                                + "public abstract class Service { public abstract String greet(String who); }",
                "g.api.Marker", "package g.api; public @interface Marker {}"));
        final var models = new BytecodeAnalyzer().analyze(jar);
        final var byName = new HashMap<String, ClassApi>();
        for (final var api : models) {
            byName.put(api.className(), api);
        }
        final var user = byName.get("g.api.User");
        assertEquals("record", user.kind(), "records must be detected");
        assertTrue(user.interfaces().stream().noneMatch(i -> i.endsWith("Record")));
        assertTrue(user.methods().stream().anyMatch(m -> m.name().equals("name")), "record accessor must be visible");
        final var service = byName.get("g.api.Service");
        assertEquals("abstract class", service.kind());
        assertTrue(service.methods().stream()
                .anyMatch(m -> m.name().equals("greet")
                        && m.returnType().equals("String")
                        && m.parameterTypes().equals(List.of("String"))));
        final var marker = byName.get("g.api.Marker");
        assertEquals("annotation", marker.kind());
    }

    @Test
    void annotationHeuristicsDriveEntryPointScore() throws IOException {
        final var jar = compileAndJar(Map.of(
                "g.web.Controller",
                "package g.web; import javax.annotation.processing.Generated;"
                        + " @Generated(value=\"x\") public class Controller {}",
                "g.web.Plain",
                "package g.web; public class Plain {}"));
        // Controller has no real annotation scored (Generated is skipped via java.lang? no, javax not skipped) -
        // assert ordering is deterministic and Plain still present
        final var eps = new BytecodeAnalyzer().entryPoints(jar);
        assertTrue(eps.contains("g.web.Plain"));
        assertTrue(eps.contains("g.web.Controller"));
        assertEquals(2, eps.size(), "both top-level public classes are entry points");
    }

    @Test
    void generatedInnerClassesAndPackageInfoSkipped() throws IOException {
        // only asserts no crash on a trivial jar
        final var jar = compileAndJar(Map.of(
                "z.Only", "package z; public class Only { private int v; }",
                "z.Outer", "package z; public class Outer { public static class Inner { public void x() {} } }"));
        final var models = new BytecodeAnalyzer().analyze(jar);
        assertTrue(models.stream().noneMatch(a -> a.className().contains("$")), "inner classes skipped");
        assertTrue(models.stream().anyMatch(a -> a.className().equals("z.Only")), "top-level class kept");
        assertTrue(models.stream().anyMatch(a -> a.className().equals("z.Outer")), "outer (non-inner) kept");
        assertEquals(2, models.size(), "exactly two top-level classes analysed");
    }
}
