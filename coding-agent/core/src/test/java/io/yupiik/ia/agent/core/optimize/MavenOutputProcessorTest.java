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
package io.yupiik.ia.agent.core.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = ConfigurationTestModule.class)
class MavenOutputProcessorTest {
    private MavenOutputProcessor processor;

    @BeforeEach
    void init(@Fusion final MavenOutputProcessor processor) {
        this.processor = processor;
    }

    @Test
    void supportsMavenOnly() {
        assertTrue(processor.supports("mvn clean install"));
        assertTrue(processor.supports("mvnd test"));
        assertTrue(processor.supports("cd /opt/dev/project && mvn clean install"));
        assertTrue(processor.supports("MY_FLAG=1 mvn -q test"));
        assertFalse(processor.supports("git status"));
        assertFalse(processor.supports("cd /somewhere && git status"));
    }

    @Test
    void preservesVersionOutput() {
        final var banner = List.of(
                "Apache Maven 4.0.0-rc-7 (6d8f301b38af7399b36b862969d520974944f0b9)",
                "Maven home: /home/yupiik/.sdkman/candidates/maven/current",
                "Java version: 25.0.4, vendor: Eclipse Adoptium, runtime: /home/yupiik/.sdkman/candidates/java/25.0.4-tem",
                "Default locale: fr_FR, platform encoding: UTF-8, time zone: Europe/Paris",
                "OS name: \"linux\", version: \"7.3.0-10-generic\", arch: \"amd64\", family: \"unix\"",
                "Exit code: 0");
        for (final var command : List.of("mvn -v", "mvn --version", "mvnd\t--version", "mvn -q -v")) {
            assertEquals(
                    banner,
                    processor
                            .process(command, banner.stream().map(line -> "\u001B[1m" + line + "\u001B[0m"))
                            .toList(),
                    command);
        }
    }

    @Test
    void preservesHelpOutput() {
        final var help = List.of(
                "",
                "usage: mvn [options] [<goal(s)>] [<phase(s)>]",
                "",
                "Options:",
                " -h,--help                     Display help information",
                " -v,--version                  Display version information",
                " -V,--show-version             Display version information WITHOUT stopping build",
                "Exit code: 0");
        for (final var command : List.of("mvn -h", "mvn --help", "cd /tmp && mvn --help")) {
            assertEquals(
                    help,
                    processor
                            .process(command, help.stream().map(line -> "\u001B[0m" + line))
                            .toList(),
                    command);
        }
    }

    @Test
    void compactsBuildsWithShowVersionAndFlagNearMisses() {
        final var lines = List.of(
                "Apache Maven 4.0.0-rc-5",
                "Maven home: /opt/maven",
                "[INFO] Scanning for projects...",
                "[INFO] Building core 1.0.0-SNAPSHOT",
                "[INFO] BUILD SUCCESS",
                "Exit code: 0");
        for (final var command : List.of(
                "mvn -V verify",
                "mvn --show-version verify",
                "mvn -Dmessage=--version verify",
                "mvn -Dmessage=-v verify",
                "mvn -Dmessage=--help verify",
                "mvn -Dmessage=-h verify",
                "mvn --version-extra verify",
                "mvn --help-extra verify",
                "mvn -verify",
                "mvn -hello")) {
            assertEquals(
                    List.of("[INFO] BUILD SUCCESS", "Exit code: 0"),
                    processor.process(command, lines.stream()).toList(),
                    command);
        }
    }

    @Test
    void collapsesBuildOutput() {
        final var out = processor
                .process(
                        "mvn clean install",
                        List.of(
                                "[INFO] Scanning for projects...",
                                "[INFO] Building yca-core 1.0.0-SNAPSHOT",
                                "[INFO] Running io.my.AppTest",
                                "[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0",
                                "[INFO] Running io.my.BrokenTest",
                                "[INFO] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0",
                                "[ERROR] cannot find symbol",
                                "[WARNING] deprecated option",
                                "[INFO] BUILD FAILURE",
                                "[INFO] Total time:  01:02 min")
                                .stream())
                .toList();

        assertEquals("[INFO] BUILD FAILURE", out.get(0));
        assertTrue(out.contains("Errors (1):"));
        assertTrue(out.contains("[ERROR] cannot find symbol"));
        assertTrue(out.contains("Failing tests:"));
        assertTrue(out.stream().anyMatch(l -> l.contains("FAIL io.my.BrokenTest")));
        assertTrue(out.contains("Warnings (1):"));
        assertTrue(out.contains("Passing tests (1):"));
        assertTrue(out.contains("  io.my.AppTest"));
        assertFalse(out.contains("Scanning for projects..."));
        assertFalse(out.contains("Building yca-core"));
    }

    @Test
    void wrapsPassingTestsInsteadOfOneUnboundedLine() {
        final var lines = new ArrayList<String>();
        for (var i = 0; i < 40; i++) {
            lines.add("[INFO] Running io.my.VeryLongTestClassName" + String.format("%04d", i));
            lines.add("[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0");
        }
        lines.add("[INFO] BUILD SUCCESS");
        final var out = processor.process("mvn test", lines.stream()).toList();

        assertTrue(out.stream().anyMatch(l -> l.startsWith("Passing tests (40):")));
        // every rendered line must stay bounded so the generic line cap remains meaningful
        assertTrue(out.stream().filter(l -> l.startsWith("  ")).allMatch(l -> l.length() <= 115));
        assertTrue(out.stream().filter(l -> l.startsWith("  ")).count() > 1, "long lists must wrap on several lines");
    }

    @Test
    void dropsNoiseLines() {
        final var out = processor
                .process(
                        "mvn install",
                        List.of(
                                "[INFO] Downloading from central: https://repo.maven.apache.org/maven2/x.jar",
                                "[INFO] Downloaded from central: https://repo.maven.apache.org/maven2/x.jar",
                                "[INFO] Progress (1): 12 kB",
                                "[INFO]",
                                "[INFO] BUILD SUCCESS")
                                .stream())
                .toList();

        assertEquals(1, out.size());
        assertEquals("[INFO] BUILD SUCCESS", out.get(0));
    }

    @Test
    void collapsesFrameworkStackFrames() {
        final var out = processor
                .process(
                        "mvn test",
                        List.of(
                                "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.0.0:test (default-test) on project core: Execution failed",
                                "[ERROR] at org.junit.jupiter.engine.descriptor.TestMethodTestDescriptor.lambda$invokeTestMethod$7 (TestMethodTestDescriptor.java:196)",
                                "[ERROR] at java.base/java.util.ArrayList.forEach (ArrayList.java:1541)",
                                "[ERROR] at com.example.MyTest.shouldWork (MyTest.java:42)",
                                "[INFO] BUILD FAILURE")
                                .stream())
                .toList();
        assertTrue(out.get(0).startsWith("[INFO] BUILD FAILURE"));
        assertTrue(out.stream().anyMatch(l -> l.contains("com.example.MyTest.shouldWork")));
        assertFalse(out.stream().anyMatch(l -> l.contains("org.junit.jupiter.engine")));
        assertFalse(out.stream().anyMatch(l -> l.contains("java.base")));
    }

    @Test
    void enrichFromSurefireReadsQuietReports(@TempDir final Path temp) throws Exception {
        final var reports = temp.resolve("target/surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(
                reports.resolve("io.example.GoodTest.txt"), "Tests run: 4, Failures: 0, Errors: 0, Skipped: 0");
        Files.writeString(
                reports.resolve("io.example.BadTest.txt"), "Tests run: 2, Failures: 1, Errors: 0, Skipped: 0");

        final var enriched = MavenOutputProcessor.enrichFromSurefire("-q install", List.of("", "BUILD SUCCESS"), temp);

        assertTrue(enriched.stream().anyMatch(l -> l.startsWith("FAIL io.example.BadTest")));
        assertTrue(enriched.stream().anyMatch(l -> l.equals("io.example.GoodTest")));
    }

    @Test
    void preservesSpotlessDiagnosticAndExitCodeOnFailure() {
        final var spotlessLine = "[INFO] Spotless.Java is keeping 4 files clean - 2 need changes to be clean, "
                + "2 were already clean, 0 were skipped because caching determined they "
                + "were already clean";
        final var out = processor
                .process(
                        "mvn verify",
                        List.of(
                                "[INFO] Scanning for projects...",
                                "[INFO] Loaded .mvn/maven.config",
                                "[INFO] Building core 1.0.0-SNAPSHOT",
                                "[INFO] --- maven-resources-plugin:3.2.0:resources (default-resources) @ core ---",
                                "[INFO] --- maven-compiler-plugin:3.8.1:compile (default-compile) @ core ---",
                                "[INFO] Compiling 12 source files to .../target/classes",
                                "[INFO] --- spotless-maven-plugin:2.39.0:check (check) @ core ---",
                                spotlessLine,
                                "[ERROR] BUILD FAILURE",
                                "[INFO] Total time:  42.127 s",
                                "[INFO] ------------------------------------------------------------",
                                "Exit code: 1")
                                .stream())
                .toList();

        // the failure itself
        assertTrue(out.contains("[ERROR] BUILD FAILURE"));
        // the actionable Spotless reason survives (not treated as noise) with the full trailing detail
        assertTrue(out.stream().map(String::strip).anyMatch(spotlessLine::equals));
        // Wrapper-added execution status survives alongside the Maven diagnostics.
        assertTrue(out.contains("Exit code: 1"));
        // routine noise is dropped
        assertFalse(out.stream().anyMatch(l -> l.contains("Scanning for projects")));
        assertFalse(out.stream().anyMatch(l -> l.contains("Loaded .mvn/maven.config")));
        assertFalse(out.stream().anyMatch(l -> l.contains("Building core")));
        assertFalse(out.stream().anyMatch(l -> l.contains("resources-plugin")));
        assertFalse(out.stream().anyMatch(l -> l.contains("compiler-plugin")));
        assertFalse(out.stream().anyMatch(l -> l.contains("Total time")));
        assertFalse(out.stream().anyMatch(l -> l.startsWith("[INFO] ---")));
    }

    @Test
    void dropsSuccessfulSpotlessChatter() {
        final var out = processor
                .process(
                        "mvn verify",
                        List.of(
                                "[INFO] --- spotless-maven-plugin:2.39.0:check (check) @ core ---",
                                "[INFO] Spotless.Java is keeping 12 files clean - 0 need changes to be clean, "
                                        + "0 were already clean, 12 were skipped because caching determined they "
                                        + "were already clean",
                                "[INFO] BUILD SUCCESS")
                                .stream())
                .toList();

        assertEquals("[INFO] BUILD SUCCESS", out.get(0));
        assertEquals(1, out.size());
    }

    @Test
    void preservesSuccessfulExitCode() {
        final var out = processor
                .process(
                        "mvn clean install",
                        List.of(
                                "[INFO] Scanning for projects...",
                                "[INFO] BUILD SUCCESS",
                                "[INFO] Total time:  5.213 s",
                                "Exit code: 0")
                                .stream())
                .toList();

        assertTrue(out.contains("[INFO] BUILD SUCCESS"));
        assertTrue(out.contains("Exit code: 0"));
        assertFalse(out.stream().anyMatch(l -> l.contains("Scanning for projects")));
        assertFalse(out.stream().anyMatch(l -> l.contains("Total time")));
    }

    @Test
    void enrichFromSurefireSkipsWhenAlreadyVerbose() {
        final var out = MavenOutputProcessor.enrichFromSurefire(
                "-q install", List.of("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", "BUILD SUCCESS"));
        assertEquals(2, out.size());
    }
}
