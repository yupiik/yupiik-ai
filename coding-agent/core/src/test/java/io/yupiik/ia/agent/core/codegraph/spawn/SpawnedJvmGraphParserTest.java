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
package io.yupiik.ia.agent.core.codegraph.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.codegraph.GraphModel;
import io.yupiik.ia.agent.core.codegraph.JavaGraphParser;
import io.yupiik.ia.agent.core.testutil.JavacWarmup;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpawnedJvmGraphParserTest {

    static {
        JavacWarmup.warm();
    }

    @TempDir
    Path tempDir;

    /** Re-wires the private fields of a freshly constructed parser (test-only; keeps the production class clean). */
    private static SpawnedJvmGraphParser configured(
            final JdkLocator jdkLocator,
            final Path scratchDir,
            final Function<String, InputStream> classResource,
            final long spawnTimeoutSeconds,
            final List<String> classPieces,
            final boolean deferToInProcessJavac) {
        final var parser = new SpawnedJvmGraphParser();
        try {
            set(parser, "jdkLocator", jdkLocator);
            set(parser, "scratchDir", scratchDir);
            set(parser, "classResource", classResource);
            set(parser, "spawnTimeoutSeconds", spawnTimeoutSeconds);
            set(parser, "classPieces", List.copyOf(classPieces));
            set(parser, "hasCompilerInProcess", deferToInProcessJavac);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot wire SpawnedJvmGraphParser test fixture", e);
        }
        return parser;
    }

    private static void set(final SpawnedJvmGraphParser target, final String field, final Object value)
            throws ReflectiveOperationException {
        final var f = SpawnedJvmGraphParser.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void availableIsFalseWhenInProcessCompilerPresent() {
        final var parser = configured(
                new JdkLocator(),
                tempDir.resolve("scratch"),
                p -> null,
                5L,
                ParserJars.classes(),
                true); // defer to in-process javac -> this parser must not engage
        assertFalse(parser.available(), "with an in-process compiler the spawned parser must not engage");
    }

    /** A locator confined to the real {@code JAVA_HOME} JDK (a genuine compiler is needed to spawn), so discovery does
     * not walk every JDK on the machine. Integrational: requires {@code JAVA_HOME} to point at a JDK. */
    private static JdkLocator singleJdkLocator() {
        final var javaHome = System.getenv("JAVA_HOME");
        return new JdkLocator() {
            @Override
            protected String env(final String name) {
                return "JAVA_HOME".equals(name) ? javaHome : null;
            }

            @Override
            protected List<Path> commonRoots() {
                return List.of();
            }
        };
    }

    @Test
    void spawnsJvmAndMatchesInProcessParser() throws IOException {
        final var scratch = tempDir.resolve("scratch");
        final var repoRoot = repoRoot();
        final var jars = ParserJars.classResource(
                repoRoot.resolve("core/target/classes"), repoRoot.resolve("code-graph-index/target/classes"));
        // a real JDK (JAVA_HOME) has a compiler; confine discovery to that single known JDK so the test does not walk
        // or probe every JDK on the machine (hermetic apart from the genuine child JVM needed for the spawn).
        assertNotNull(System.getenv("JAVA_HOME"), "test needs JAVA_HOME pointing at a JDK to spawn");
        final var parser = configured(singleJdkLocator(), scratch, jars, 30L, ParserJars.classes(), false);

        assertTrue(parser.available(), "spawned parser should be available when a JDK is found");

        final var src = tempDir.resolve("demo/src/Demo.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package demo;
                import java.util.List;
                public class Demo {
                    private int x;
                    public void run() { Helper.call(); }
                    public List<String> list() { return null; }
                }
                """);
        final var helper = tempDir.resolve("demo/src/Helper.java");
        Files.writeString(helper, """
                package demo;
                public class Helper {
                    public static void call() {}
                }
                """);

        final var parsed = parser.parseBatch(List.of(src, helper));

        // exact same shape as the in-process javac parser
        final var reference = new JavaGraphParser().parseBatch(List.of(src, helper));
        final var refNames = reference.keySet().stream()
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        final var gotNames = parsed.keySet().stream()
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        assertEquals(refNames, gotNames, "spawned parser must key results by the same files");

        final var refSym = reference.get(src).symbols().stream()
                .map(GraphModel.Symbol::fqn)
                .sorted()
                .toList();
        final var gotSym = parsed.get(src).symbols().stream()
                .map(GraphModel.Symbol::fqn)
                .sorted()
                .toList();
        assertEquals(refSym, gotSym, "spawned parser must produce the same symbols as the in-process javac parser");

        final var refEdges = reference.get(src).edges().stream()
                .map(e -> e.from() + "->" + e.to() + ":" + e.type())
                .sorted()
                .toList();
        final var gotEdges = parsed.get(src).edges().stream()
                .map(e -> e.from() + "->" + e.to() + ":" + e.type())
                .sorted()
                .toList();
        assertEquals(refEdges, gotEdges, "spawned parser must produce the same edges as the in-process javac parser");

        // the Demo type is present
        assertTrue(
                parsed.get(src).symbols().stream().anyMatch(s -> s.fqn().equals("demo.Demo")),
                "expected the Demo type symbol");
        assertNotNull(parsed.get(helper), "helper file must have a model");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recreatesDeletedScratchArtifacts(final boolean deleteDirectory) throws IOException {
        final var scratch = tempDir.resolve("scratch");
        final var repoRoot = repoRoot();
        final var jars = ParserJars.classResource(
                repoRoot.resolve("core/target/classes"), repoRoot.resolve("code-graph-index/target/classes"));
        final var parser = configured(singleJdkLocator(), scratch, jars, 30L, ParserJars.classes(), false);
        final var src = tempDir.resolve("Demo.java");
        Files.writeString(src, "public class Demo {}\n");
        assertTrue(parser.available());
        assertTrue(parser.parse(src).symbols().stream().anyMatch(s -> s.fqn().equals("Demo")));

        Files.delete(scratch.resolve("parser.jar"));
        if (deleteDirectory) {
            Files.delete(scratch.resolve("generate.scgn.lock"));
            Files.delete(scratch);
        }

        assertTrue(parser.parse(src).symbols().stream().anyMatch(s -> s.fqn().equals("Demo")));
        assertTrue(Files.isRegularFile(scratch.resolve("parser.jar")));
        try (final var entries = Files.list(scratch)) {
            assertFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith("scg-")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failingChildOnlyReassemblesMissingArtifactsOnce(final boolean deleteJar) throws Exception {
        final var scratch = tempDir.resolve("scratch");
        final var repoRoot = repoRoot();
        final var jars = ParserJars.classResource(
                repoRoot.resolve("core/target/classes"), repoRoot.resolve("code-graph-index/target/classes"));
        final var resourceReads = new AtomicInteger();
        final var parser = configured(
                singleJdkLocator(),
                scratch,
                path -> {
                    resourceReads.incrementAndGet();
                    return jars.apply(path);
                },
                30L,
                ParserJars.classes(),
                false);
        assertTrue(parser.available());
        final int initialReads = resourceReads.get();
        if (deleteJar) {
            Files.delete(scratch.resolve("parser.jar"));
        }
        set(parser, "javacOptions", List.of("-XX:NotARealJvmOption"));
        final var src = tempDir.resolve("Demo.java");
        Files.writeString(src, "public class Demo {}\n");

        assertTrue(parser.parse(src).symbols().isEmpty());
        assertEquals(initialReads * (deleteJar ? 2 : 1), resourceReads.get());
        assertTrue(Files.isRegularFile(scratch.resolve("parser.jar")));
    }

    @Test
    void returnsEmptyAndStaysDisabledWhenNoJdk() throws IOException {
        // an empty discovery simulates no usable JDK (no JAVA_HOME/PATH/common-root launchers)
        final var locator = new JdkLocator() {
            @Override
            protected String env(final String name) {
                return null;
            }

            @Override
            protected List<Path> commonRoots() {
                return List.of();
            }
        };
        final var parser = configured(locator, tempDir.resolve("scratch2"), p -> null, 5L, ParserJars.classes(), false);
        assertFalse(parser.available(), "no JDK -> available must be false");
        assertFalse(parser.available(), "must stay false for the whole session (cached)");

        final var src = Files.createTempFile(tempDir, "A", ".java");
        final var parsed = parser.parseBatch(List.of(src));
        assertEquals(1, parsed.size());
        assertTrue(parsed.get(src).symbols().isEmpty(), "disabled parser must yield an empty model");
    }

    @Test
    void reportsExtensionsAndJdkNotRequired() {
        final var parser = new SpawnedJvmGraphParser();
        assertEquals(Set.of("java"), parser.extensions());
        assertFalse(parser.jdkRequired());
    }

    @Test
    void regeneratesStaleParserJarWhenBundledClassesChange() throws Exception {
        final var scratch = tempDir.resolve("stale");
        final var repoRoot = repoRoot();
        final var base = ParserJars.classResource(
                repoRoot.resolve("core/target/classes"), repoRoot.resolve("code-graph-index/target/classes"));

        // mutable: flips to "simulated different build" by altering one class's bytes after the first assembly
        final var changed = new boolean[] {false};
        final Function<String, InputStream> resource = path -> {
            final var in = base.apply(path);
            if (in == null || !changed[0]) {
                return in;
            }
            return new SequenceInputStream(in, new ByteArrayInputStream(new byte[] {1}));
        };
        final var jarPath = scratch.resolve("parser.jar");

        // first parser assembles parser.jar fresh
        final var first = configured(singleJdkLocator(), scratch, resource, 5L, ParserJars.classes(), false);
        assertTrue(first.available(), "first assembly must make the parser available");
        assertTrue(Files.isRegularFile(jarPath), "parser.jar must be assembled");
        final String checksum1 = jarChecksum(jarPath);

        // unchanged resources on a shared scratch -> the existing jar is up to date and reused unchanged
        final var second = configured(singleJdkLocator(), scratch, resource, 5L, ParserJars.classes(), false);
        assertTrue(second.available(), "up-to-date jar must be reused");
        assertEquals(checksum1, jarChecksum(jarPath), "an up-to-date jar must NOT be regenerated");

        // simulate the yca binary being rebuilt with different class bytes -> the cached jar is stale -> regenerated
        changed[0] = true;
        final var third = configured(singleJdkLocator(), scratch, resource, 5L, ParserJars.classes(), false);
        assertTrue(third.available(), "after regeneration the parser must still be available");
        assertNotEquals(checksum1, jarChecksum(jarPath), "a different-build resource set must regenerate the jar");
    }

    @Test
    void customJavacOptionsStillRoundTripTheModel(final @TempDir Path tempDir) throws Exception {
        // javacOptions drive the spawned child JVM startup flags. A non-default list (here: replace the 3
        // defaults with a single option) must still spawn a valid child and produce the exact same model.
        final var scratch = tempDir.resolve("scratch");
        final var repoRoot = repoRoot();
        final var jars = ParserJars.classResource(
                repoRoot.resolve("core/target/classes"), repoRoot.resolve("code-graph-index/target/classes"));
        assertNotNull(System.getenv("JAVA_HOME"), "test needs JAVA_HOME pointing at a JDK to spawn");
        final var parser = configured(singleJdkLocator(), scratch, jars, 30L, ParserJars.classes(), false);
        set(parser, "javacOptions", List.of("-Xshare:auto"));

        final var src = tempDir.resolve("demo/src/Demo.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package demo;\npublic class Demo { public void run() {} }\n");

        final var parsed = parser.parseBatch(List.of(src));
        final var reference = new JavaGraphParser().parseBatch(List.of(src));
        assertEquals(
                reference.get(src).symbols().stream()
                        .map(GraphModel.Symbol::fqn)
                        .sorted()
                        .toList(),
                parsed.get(src).symbols().stream()
                        .map(GraphModel.Symbol::fqn)
                        .sorted()
                        .toList(),
                "custom javacOptions must still yield the same symbols");
    }

    private static String jarChecksum(final Path jar) throws IOException {
        try (final var jarFile = new JarFile(jar.toFile())) {
            return jarFile.getManifest().getMainAttributes().getValue("X-Yca-Parser-Checksum");
        }
    }

    /** Resolve the git repo root from the module working dir (handles running from core/ or the root). */
    private static Path repoRoot() {
        var dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("code-graph-index"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("Cannot locate the code-graph-index module root from "
                    + Path.of("").toAbsolutePath());
        }
        return dir;
    }
}
