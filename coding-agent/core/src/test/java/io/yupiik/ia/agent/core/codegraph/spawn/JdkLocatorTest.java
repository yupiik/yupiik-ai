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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkLocatorTest {

    @TempDir
    Path tempDir;

    /**
     * A locator where each JDK home under {@code roots}/{name} (with a real {@code <name>/bin/java} launcher) is
     * reported by {@code probe} at the given feature level, so selection can be tested without spawning sub-processes.
     */
    private static JdkLocator locatorWith(
            final JdkLocator.Settings settings, final Path roots, final Map<String, Integer> nameToFeature)
            throws Exception {
        final var launchers = new LinkedHashMap<Path, Integer>();
        for (final var entry : nameToFeature.entrySet()) {
            launchers.put(launcher(roots.resolve(entry.getKey()), entry.getValue()), entry.getValue());
        }
        return new JdkLocator(ParserClassSet::resourceStream, settings) {
            @Override
            protected Candidate probe(final Path java) {
                final var n = java.toAbsolutePath().normalize();
                final var feature = launchers.get(n);
                return feature == null ? null : new Candidate(java, feature + 44);
            }

            @Override
            protected List<Path> commonRoots() {
                return roots.getParent() == null ? List.of() : List.of(roots);
            }

            // hermetic by construction: the temp JDKs must win the parser/project selection, so the
            // host machine's JAVA_HOME/PATH (which may carry a JDK >= the parser floor) is neutralized.
            @Override
            protected String env(final String name) {
                return null;
            }
        };
    }

    private static Path launcher(final Path homeDir, final int feature) throws Exception {
        Files.createDirectories(homeDir.resolve("bin"));
        final var bin = homeDir.resolve("bin/java");
        Files.writeString(bin, "#!/bin/sh\nexit 0\n");
        bin.toFile().setExecutable(true);
        return bin;
    }

    private static JdkLocator.Settings settings(final Integer pinned) {
        return new JdkLocator.Settings(null, pinned, Path.of(""));
    }

    @Test
    void cachesSuccessfulProbeAndInvalidatesWhenJdkReleaseChanges() throws Exception {
        final var project = Files.createDirectory(tempDir.resolve("cache-project"));
        final var roots = Files.createDirectory(tempDir.resolve("cache-jdks"));
        final var home = Files.createDirectory(roots.resolve("jdk-25"));
        final var launcherPath = launcher(home, 25);
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"25\"\n");
        final var probes = new AtomicInteger();
        final var settings = new JdkLocator.Settings(null, null, project);
        final Supplier<JdkLocator> factory = () -> new JdkLocator(ParserClassSet::resourceStream, settings) {
            @Override
            protected Candidate probe(final Path java) {
                if (java.toAbsolutePath()
                        .normalize()
                        .equals(launcherPath.toAbsolutePath().normalize())) {
                    probes.incrementAndGet();
                    return new Candidate(java, 69);
                }
                return null;
            }

            @Override
            protected List<Path> commonRoots() {
                return List.of(roots);
            }
        };

        assertNotNull(factory.get().locateJarJdk());
        assertNotNull(factory.get().locateJarJdk());
        assertEquals(1, probes.get(), "second locator should reuse the persisted probe");

        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"25.0.1\"\n");
        assertNotNull(factory.get().locateJarJdk());
        assertEquals(2, probes.get(), "changed JDK release metadata must invalidate the cached probe");
    }

    // --- two-role selection ------------------------------------------------------

    @Test
    void twoRolesSelectIndependentlyForAJava21ProjectWithJava25Jar() throws Exception {
        // parser jar is Java 25 (V1 from the real class resources); project targets 21 via the pin.
        final var roots = tempDir.resolve("jdks");
        final var locator = locatorWith(settings(21), roots, Map.of("j25", 25, "j21", 21));
        final var jarLauncher = launcherPath(roots.resolve("j25"));
        final var projLauncher = launcherPath(roots.resolve("j21"));

        assertEquals(jarLauncher, locator.locateJarJdk(), "parser JDK is the Java 25 one (floor V1=25)");
        assertEquals(projLauncher, locator.locateProjectJdk(), "project JDK is the Java 21 one (floor V2=21)");
        // spawn: project level (21) <= parser level (25) -> the parser JVM covers both.
        assertEquals(jarLauncher, locator.locate());
    }

    @Test
    void jarJdkPrefersExactParserLevelWhileProjectUsesAnother() throws Exception {
        final var roots = tempDir.resolve("jdks");
        final var locator = locatorWith(settings(26), roots, Map.of("j25", 25, "j26", 26));
        assertEquals(
                launcherPath(roots.resolve("j25")),
                locator.locateJarJdk(),
                "parser JDK prefers the exact parser level 25");
        assertEquals(
                launcherPath(roots.resolve("j26")),
                locator.locateProjectJdk(),
                "project JDK (floor 26) picks its own exact 26, independent of the parser floor");
    }

    @Test
    void locateReturnsProjectJdkWhenProjectNewerThanParser() throws Exception {
        final var roots = tempDir.resolve("jdks");
        final var locator = locatorWith(settings(30), roots, Map.of("j25", 25, "j30", 30));
        assertEquals(
                launcherPath(roots.resolve("j30")),
                locator.locate(),
                "spawn uses the newer project JDK 30, which also loads the parser jar");
    }

    @Test
    void returnsNullWhenNoCandidateClearsTheParserFloor() throws Exception {
        final var roots = tempDir.resolve("jdks");
        final var locator = locatorWith(settings(21), roots, Map.of("j21", 21));
        assertNull(locator.locateJarJdk(), "no JDK >= parser level 25 -> no parser JVM even though 21 exists");
        assertNotNull(locator.locateProjectJdk(), "a JDK >= project level 21 still serves the project role");
    }

    @Test
    void picksClosestHigherWhenNoExactMatch() throws Exception {
        final var roots = tempDir.resolve("jdks");
        // parser floor 25, only a 26 and a 28 available: closest higher (26) wins.
        final var locator = locatorWith(settings(21), roots, Map.of("j26", 26, "j28", 28));
        assertEquals(
                launcherPath(roots.resolve("j26")),
                locator.locateJarJdk(),
                "closest higher than the parser floor 25 wins");
    }

    // --- caches -----------------------------------------------------------------

    @Test
    void resolvesTheCandidatePoolOnceAndCaches() throws Exception {
        // hermetic: a single fake JAVA_HOME launcher, no reliance on the machine's JDKs or OS
        final var home = tempDir.resolve("jdk");
        final var bin = launcher(home, 25);
        final var locator =
                new JdkLocator(ParserClassSet::resourceStream, new JdkLocator.Settings(null, null, tempDir)) {
                    final AtomicInteger probes = new AtomicInteger();

                    @Override
                    protected Candidate probe(final Path java) {
                        probes.incrementAndGet();
                        return new Candidate(java, 69);
                    }

                    @Override
                    protected String env(final String name) {
                        return "JAVA_HOME".equals(name) ? home.toString() : null;
                    }

                    @Override
                    protected List<Path> commonRoots() {
                        return List.of();
                    }
                };
        assertEquals(bin, locator.locateJarJdk());
        final int afterFirst = locator.probes.get();
        locator.locateJarJdk();
        locator.locate();
        assertEquals(
                afterFirst,
                locator.probes.get(),
                "the probed candidate pool is resolved once and cached across locate() calls");
        assertEquals(1, locator.probes.get(), "exactly the single hermetic launcher is probed");
    }

    // --- V2 detection -------------------------------------------------------------

    @Test
    void readsMavenCompilerReleaseFromProperties() throws Exception {
        final var project = tempDir.resolve("proj");
        Files.createDirectories(project);
        Files.writeString(
                project.resolve("pom.xml"), "<project><maven.compiler.release>25</maven.compiler.release></project>");
        final var locator = new JdkLocator(null, new JdkLocator.Settings(null, null, project));
        assertEquals(Optional.of(25), locator.projectFeature());
    }

    @Test
    void readsMavenCompilerPluginRelease() throws Exception {
        final var project = tempDir.resolve("proj");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), """
                <project><build><plugins><plugin>
                  <artifactId>maven-compiler-plugin</artifactId>
                  <configuration><release>22</release></configuration>
                </plugin></plugins></build></project>
                """);
        final var locator = new JdkLocator(null, new JdkLocator.Settings(null, null, project));
        assertEquals(Optional.of(22), locator.projectFeature());
    }

    @Test
    void readsMavenCompilerPluginSourceAsFallback() throws Exception {
        final var project = tempDir.resolve("proj");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), """
                <project><build><plugins><plugin>
                  <artifactId>maven-compiler-plugin</artifactId>
                  <configuration><source>17</source><target>17</target></configuration>
                </plugin></plugins></build></project>
                """);
        final var locator = new JdkLocator(null, new JdkLocator.Settings(null, null, project));
        assertEquals(Optional.of(17), locator.projectFeature());
    }

    @Test
    void readsSdkmanrcAndJavaVersion() throws Exception {
        final var project = tempDir.resolve("proj");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".sdkmanrc"), "java = 17.0.9-tem\n");
        assertEquals(
                Optional.of(17), new JdkLocator(null, new JdkLocator.Settings(null, null, project)).projectFeature());

        final var project2 = tempDir.resolve("proj2");
        Files.createDirectories(project2);
        Files.writeString(project2.resolve(".java-version"), "21.0.5\n");
        assertEquals(
                Optional.of(21), new JdkLocator(null, new JdkLocator.Settings(null, null, project2)).projectFeature());
    }

    @Test
    void sdkmanSplitsUnicodeLineBreaksAndDoesNotShareMatcherState() throws Exception {
        final var project = Files.createDirectory(tempDir.resolve("unicode-rc"));
        final var rc = project.resolve(".sdkmanrc");
        Files.writeString(rc, "java = java.home\r\n# comment\u0085java = 1.8.0_392-tem\u2028");
        final var locator = new JdkLocator(null, new JdkLocator.Settings(null, null, project));
        assertEquals(Optional.of(8), locator.projectFeature());
        Files.writeString(rc, "# comment\u2029java = 21.0.5-tem\r\n");
        assertEquals(Optional.of(21), locator.projectFeature());
    }

    @Test
    void compilerTagsKeepLiteralDotsAndReleasePrecedence() throws Exception {
        final var project = Files.createDirectory(tempDir.resolve("compiler-tags"));
        final var pom = project.resolve("pom.xml");
        Files.writeString(
                pom,
                "<project><mavenXcompilerXrelease>99</mavenXcompilerXrelease>"
                        + "<maven.compiler.source>19</maven.compiler.source></project>");
        final var locator = new JdkLocator(null, new JdkLocator.Settings(null, null, project));
        assertEquals(Optional.of(19), locator.projectFeature());
        Files.writeString(
                pom,
                "<project><plugin><artifactId>maven-compiler-plugin</artifactId>\n"
                        + "<configuration>\n<source>17</source>\n<release>23</release>\n</configuration>\n</plugin></project>");
        assertEquals(Optional.of(23), locator.projectFeature());
    }

    @Test
    void pinnedFeatureWinsOverFiles() throws Exception {
        final var project = tempDir.resolve("proj");
        Files.createDirectories(project);
        Files.writeString(
                project.resolve("pom.xml"), "<project><maven.compiler.release>25</maven.compiler.release></project>");
        final var locator = new JdkLocator(null, new JdkLocator.Settings(null, 30, project));
        assertEquals(Optional.of(30), locator.projectFeature());
    }

    // --- Maven toolchains ----------------------------------------------------------

    @Test
    void readsMavenToolchainJdkHomes() throws Exception {
        final var m2 = tempDir.resolve(".m2");
        Files.createDirectories(m2);
        Files.writeString(m2.resolve("toolchains.xml"), """
                <toolchains>
                  <toolchain>
                    <type>jdk</type>
                    <provides><version>21</version></provides>
                    <configuration><jdkHome>/some/jdk21</jdkHome></configuration>
                  </toolchain>
                </toolchains>
                """);
        final var locator = new JdkLocator(null, new JdkLocator.Settings(null, null, tempDir)) {
            @Override
            protected String env(final String name) {
                return "HOME".equals(name) ? tempDir.toString() : null;
            }
        };
        final var homes = locator.toolchainHomes();
        assertEquals(1, homes.size());
        assertEquals(Path.of("/some/jdk21"), homes.get(0).path());
        assertEquals(21 + 44, homes.get(0).classFileMajor());
    }

    // --- windows vs others -----------------------------------------------------------

    @Test
    void launcherNamesAndRootsMatchThePlatform() {
        // control all three platforms via the overridable seams, so no dependence on the real OS
        final JdkLocator linux = platform(false, false);
        assertEquals(List.of("java"), linux.launcherNames());
        assertTrue(linux.commonRoots().contains(Path.of("/usr/lib/jvm")), "linux scans /usr/lib/jvm");
        assertFalse(
                linux.commonRoots().contains(Path.of("/Library/Java/JavaVirtualMachines")),
                "linux must not scan the macOS root");
        assertTrue(
                linux.commonRoots().stream().noneMatch(r -> r.toString().startsWith("C:\\")),
                "linux must not scan Windows roots");

        final JdkLocator mac = platform(false, true);
        assertEquals(List.of("java"), mac.launcherNames());
        assertTrue(
                mac.commonRoots().contains(Path.of("/Library/Java/JavaVirtualMachines")),
                "mac scans its own JavaVirtualMachines root");
        assertFalse(mac.commonRoots().contains(Path.of("/usr/lib/jvm")), "mac must not scan the Linux root");
        assertFalse(mac.commonRoots().contains(Path.of("/opt")), "mac must not scan the Linux /opt root");
        assertTrue(
                mac.commonRoots().stream().noneMatch(r -> r.toString().startsWith("C:\\")),
                "mac must not scan Windows roots");

        final JdkLocator windows = platform(true, false);
        assertEquals(List.of("java", "java.cmd", "java.exe"), windows.launcherNames());
        assertTrue(
                windows.commonRoots().stream().allMatch(r -> r.toString().startsWith("C:\\")),
                "windows only scans windows install roots");
        assertFalse(windows.commonRoots().contains(Path.of("/usr/lib/jvm")), "windows must not scan Linux roots");
        assertFalse(
                windows.commonRoots().contains(Path.of("/Library/Java/JavaVirtualMachines")),
                "windows must not scan the macOS root");
    }

    /** A locator forced onto a given platform with a clean HOME so the roots are fully deterministic. */
    private JdkLocator platform(final boolean windows, final boolean mac) {
        return new JdkLocator(null, new JdkLocator.Settings(null, null, tempDir)) {
            @Override
            protected boolean isWindows() {
                return windows;
            }

            @Override
            protected boolean isMac() {
                return mac;
            }

            @Override
            protected String env(final String name) {
                return null;
            }
        };
    }

    /** Expected launcher path for a JDK home (mirrors addHomeJava's resolution). */
    private static Path launcherPath(final Path jdkHome) {
        return jdkHome.resolve("bin").resolve("java");
    }
}
