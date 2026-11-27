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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.codegraph.index.CodeGraphIndex;
import io.yupiik.ia.agent.core.cli.CodeGraphIndexCommand.CodeGraphIndexConf;
import io.yupiik.ia.agent.core.testutil.JavacWarmup;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class CodeGraphIndexCommandTest {

    static {
        // shares the single per-JVM javac warmup with the other compiler-hungry tests
        JavacWarmup.warm();
    }

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private PrintStream previousOut;

    @BeforeEach
    void captureOut() {
        previousOut = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(previousOut);
    }

    private String source(final String pkg, final String className, final String body) {
        return "package " + pkg + "; public class " + className + " { " + body + " }";
    }

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
        final var result =
                JavacWarmup.compiler().getTask(null, null, null, List.of("-d", classes.toString()), null, files);
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
    void buildsIndexFromJar() throws IOException {
        final var jar = compileAndJar(Map.of(
                "com.example.keep.Keeper",
                source("com.example.keep", "Keeper", "public String hello() { return \"hi\"; }")));
        final var output = tmp.resolve("out.bin");
        new CodeGraphIndexCommand(new CodeGraphIndexConf(jar.toString(), output.toString(), "")).run();
        assertTrue(Files.isRegularFile(output), "output index must exist");
        final var idx = CodeGraphIndex.load(output);
        assertTrue(idx.indexOfFqn("com.example.keep.Keeper") >= 0);
        assertTrue(idx.indexOfFqn("com.example.keep.Keeper.hello") >= 0);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Wrote"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains(output.toString()));
    }

    @Test
    void excludesPackagePrefix() throws IOException {
        final var jar = compileAndJar(Map.of(
                "com.example.keep.Keeper", source("com.example.keep", "Keeper", "public void a() {}"),
                "com.example.internal.Hidden", source("com.example.internal", "Hidden", "public void b() {}"),
                "com.example.internal.Sub", source("com.example.internal", "Sub", "public void c() {}")));
        final var output = tmp.resolve("out.bin");
        new CodeGraphIndexCommand(new CodeGraphIndexConf(jar.toString(), output.toString(), "com.example.internal"))
                .run();
        final var idx = CodeGraphIndex.load(output);
        assertTrue(idx.indexOfFqn("com.example.keep.Keeper") >= 0);
        assertEquals(-1, idx.indexOfFqn("com.example.internal.Hidden"));
        assertEquals(-1, idx.indexOfFqn("com.example.internal.Sub"));
    }

    @Test
    void failsWhenJarMissing() {
        final var output = tmp.resolve("out.bin");
        assertThrows(
                IllegalArgumentException.class,
                () -> new CodeGraphIndexCommand(new CodeGraphIndexConf(
                                tmp.resolve("missing.jar").toString(), output.toString(), ""))
                        .run());
    }
}
