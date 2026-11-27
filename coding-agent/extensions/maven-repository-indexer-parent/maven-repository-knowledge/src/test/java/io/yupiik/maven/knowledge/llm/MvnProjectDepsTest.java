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
package io.yupiik.maven.knowledge.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MvnProjectDepsTest {
    @Test
    void parsePreservesUnicodeLineBreaksAndAsciiScopeWhitespace() {
        for (final var separator : List.of("\n", "\r", "\r\n", "\u000b", "\f", "\u0085", "\u2028", "\u2029")) {
            final var output = separator + "[INFO] com.ex:compiled:jar:1:compile\t-- module example"
                    + separator + "[INFO] com.ex:provided:jar:1:provided  -- module example"
                    + separator + separator;
            assertEquals(
                    List.of(Gav.of("com.ex", "provided", "1"), Gav.of("com.ex", "compiled", "1")),
                    MvnProjectDeps.parse(output),
                    separator);
        }
        assertEquals(List.of(), MvnProjectDeps.parse(""));
        assertEquals(List.of(), MvnProjectDeps.parse("\r\n\n"));
    }

    @Test
    void parseOrdersProvidedThenCompileThenTestThenOther() {
        final var output = """
                [INFO] --- dependency:3.6.1:list (default-cli) @ app ---
                [INFO] The following files have been resolved:
                [INFO]    org.junit.jupiter:junit-jupiter:jar:5.10.2:test -- module junit.jupiter [auto]
                [INFO]    org.slf4j:slf4j-api:jar:2.0.13:runtime -- module org.slf4j [auto]
                [INFO]    com.ex:lib:jar:1.0:compile -- module com.ex.lib
                [INFO]    javax.servlet:javax.servlet-api:jar:4.0.1:provided -- module javax.servlet.api [auto]
                [INFO] ------------------------------------------------------------------------
                """;
        assertEquals(
                List.of(
                        Gav.of("javax.servlet", "javax.servlet-api", "4.0.1"),
                        Gav.of("com.ex", "lib", "1.0"),
                        Gav.of("org.junit.jupiter", "junit-jupiter", "5.10.2"),
                        Gav.of("org.slf4j", "slf4j-api", "2.0.13")),
                MvnProjectDeps.parse(output));
    }

    @Test
    void parseDedupsKeepingTheMostRelevantScope() {
        final var output = """
                [INFO]    com.ex:lib:jar:1.0:test
                [INFO]    com.ex:lib:jar:1.0:provided
                [INFO]    other:artifact:jar:2.0:compile
                """;
        assertEquals(
                List.of(Gav.of("com.ex", "lib", "1.0"), Gav.of("other", "artifact", "2.0")),
                MvnProjectDeps.parse(output));
    }

    @Test
    void parseFiltersNonJarAndUnknownLines() {
        final var output = """
                [INFO] Scanning for projects...
                [INFO]    com.ex:pom-only:pom:1.0:compile
                [INFO]    not a dependency line at all
                [INFO]    com.ex:kept:jar:1.0:compile
                """;
        assertEquals(List.of(Gav.of("com.ex", "kept", "1.0")), MvnProjectDeps.parse(output));
    }

    @Test
    void resolveReturnsEmptyForNonDirectory(@TempDir final Path tmp) {
        assertEquals(List.of(), MvnProjectDeps.resolve(tmp.resolve("missing"), 1000));
        assertEquals(List.of(), MvnProjectDeps.resolve(null, 1000));
    }

    @Test
    void resolveRunsFakeMavenOnPathAndReturnsItsParsedOutput(@TempDir final Path tmp) {
        // the fake `mvn` (prepended to PATH by surefire) always emits a constant `dependency:list`
        // output; resolution must parse it rather than spawn the real (slow) build tool. provided
        // is ordered before compile (see DependencyScope.order()).
        assertEquals(
                List.of(Gav.of("io.yupiik.fake", "fake-util", "2.0"), Gav.of("io.yupiik.fake", "fake-lib", "1.0")),
                MvnProjectDeps.resolve(tmp, 120_000));
    }

    @Test
    void fingerprintIsStableAndChangesWithBuildFiles(@TempDir final Path tmp) throws Exception {
        final var pom = tmp.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        final var first = MvnProjectDeps.fingerprint(tmp);
        assertEquals(first, MvnProjectDeps.fingerprint(tmp), "fingerprint must be stable for unchanged files");
        Files.writeString(pom, "<project><!-- changed --></project>");
        Assertions.assertNotEquals(
                first, MvnProjectDeps.fingerprint(tmp), "fingerprint must change when a build file changes");
    }

    @Test
    void fingerprintsStayIndependentAcrossProjectsAndConcurrentCalls(@TempDir final Path tmp) throws Exception {
        final var first = Files.createDirectories(tmp.resolve("first"));
        final var second = Files.createDirectories(tmp.resolve("second"));
        Files.writeString(first.resolve("pom.xml"), "<project>first</project>");
        Files.writeString(second.resolve("pom.xml"), "<project>second</project>");
        final var firstHash = MvnProjectDeps.fingerprint(first);
        final var secondHash = MvnProjectDeps.fingerprint(second);
        Assertions.assertNotEquals(firstHash, secondHash);
        assertEquals(firstHash, MvnProjectDeps.fingerprint(first));
        assertEquals(secondHash, MvnProjectDeps.fingerprint(second));
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = new ArrayList<Future<String>>();
            for (int i = 0; i < 20; i++) {
                final var project = i % 2 == 0 ? first : second;
                tasks.add(executor.submit(() -> MvnProjectDeps.fingerprint(project)));
            }
            for (int i = 0; i < tasks.size(); i++) {
                assertEquals(i % 2 == 0 ? firstHash : secondHash, tasks.get(i).get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void resolveUsesCacheWithoutRunningMvn(@TempDir final Path tmp) throws Exception {
        final var project = tmp.resolve("project");
        Files.createDirectories(project);
        // a build file exists so a fingerprint can be computed; mvn would fail (no real pom) - the cache bypasses it
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        final var cached = List.of(Gav.of("com.ex", "cached", "1"), Gav.of("com.ex", "second", "2"));
        // the cache lives under the project tree at .yca/maven.dependency.list with a fingerprint header
        final var cacheFile = project.resolve(".yca").resolve("maven.dependency.list");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(
                cacheFile,
                "# fingerprint=" + MvnProjectDeps.fingerprint(project) + "\ncom.ex:cached:1\ncom.ex:second:2\n");

        assertEquals(cached, MvnProjectDeps.resolve(project, 120_000), "cache hit must skip mvn");
    }

    @Test
    void fingerprintChangeInvalidatesTheCache(@TempDir final Path tmp) throws Exception {
        final var project = tmp.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        // a cache entry for the OLD fingerprint (computed before the change below) -> a mismatch means the
        // stale entry must be ignored and mvn re-run (the fake `mvn` emits a constant dependency:list output)
        final var cacheFile = project.resolve(".yca").resolve("maven.dependency.list");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "# fingerprint=" + MvnProjectDeps.fingerprint(project) + "\ncom.ex:cached:1\n");

        // change a build file so the fingerprint no longer matches the stale cache entry; the stale entry
        // must not be reused - resolution runs the fake `mvn` and returns its constant output instead
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        assertEquals(
                List.of(Gav.of("io.yupiik.fake", "fake-util", "2.0"), Gav.of("io.yupiik.fake", "fake-lib", "1.0")),
                MvnProjectDeps.resolve(project, 120_000),
                "a stale cache entry (different fingerprint) must not be reused");
    }

    /**
     * When the resolution thread is interrupted while {@code mvn} is running, the spawned process
     * must be disposed (tree-kill + reap) and not left to run to completion. Uses a slow local
     * {@code mvnw} and a thread interrupt, so the test is fast and deterministic.
     */
    @Test
    void interruptDisposesTheRunningMaven(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var project = tmp.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        // a real (slow) wrapper: without it the fake `mvn` on PATH would finish before the interrupt
        final var mvnw = project.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\nsleep 30\n");
        mvnw.toFile().setExecutable(true);

        final var service = new ProcessService();
        service.reapGrace(200);
        final var result = new AtomicReference<List<Gav>>();
        final var thread = Thread.ofPlatform()
                .name("mvn-resolve-interrupt-test")
                .start(() -> result.set(MvnProjectDeps.resolve(project, 60_000, null, service)));
        // let the wrapper spawn and get tracked, then interrupt the resolution
        final var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (service.trackedCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, service.trackedCount(), "the spawned mvnw must be tracked before the interrupt");
        thread.interrupt();
        thread.join(5_000);
        assertFalse(thread.isAlive(), "resolution must return promptly after the interrupt");
        assertEquals(0, service.trackedCount(), "the interrupted mvnw must be disposed");
        assertTrue(result.get() == null || result.get().isEmpty(), "a cancelled resolution yields no deps");
    }
}
