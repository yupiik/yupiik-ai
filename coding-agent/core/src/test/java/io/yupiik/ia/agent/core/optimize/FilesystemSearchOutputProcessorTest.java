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
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@FusionSupport(modules = FilesystemSearchOutputProcessorTest.Settings.class)
class FilesystemSearchOutputProcessorTest {
    private FilesystemSearchOutputProcessor processor;

    @BeforeEach
    void init(@Fusion final CodingAgentConfiguration configuration) {
        processor = new FilesystemSearchOutputProcessor(configuration);
    }

    private List<String> run(final String command, final String... lines) {
        return processor.process(command, Stream.of(lines)).toList();
    }

    @Test
    void supportsFamilies() {
        assertTrue(processor.supports("ls -la"));
        assertTrue(processor.supports("find . -name '*.java'"));
        assertTrue(processor.supports("tree -L 2"));
        assertTrue(processor.supports("du -sh *"));
        assertTrue(processor.supports("grep -rn 'foo' src"));
        assertTrue(processor.supports("rg 'bar' --glob '*.ts'"));
        assertTrue(processor.supports("cd /x && ls"));
        assertFalse(processor.supports("mvn test"));
        assertFalse(processor.supports("git status"));
    }

    @Test
    void compactsLsDroppingTotalAndDots() {
        final var out = run("ls -la", "total 12", ".", "..", "src", "pom.xml", "README.md");
        assertEquals(List.of("src", "pom.xml", "README.md"), out);
    }

    @Test
    void groupsFindByParentWithCounts() {
        final var out =
                run("find . -name '*.txt'", "./a.txt", "./b.txt", "./sub/c.txt", "./sub/d.txt", "./sub/deep/e.txt");
        assertEquals(
                List.of(
                        ". (2):",
                        "  ./a.txt",
                        "  ./b.txt",
                        "./sub (2):",
                        "  ./sub/c.txt",
                        "  ./sub/d.txt",
                        "./sub/deep (1):",
                        "  ./sub/deep/e.txt"),
                out);
    }

    @Test
    void groupsGrepByFileAndTruncatesLongLines() {
        final var out = run(
                "rg -n 'foo' src",
                "src/a.java:3: foo here",
                "src/a.java:12: foo again",
                "src/b.java:1: " + "x".repeat(300));
        assertEquals(
                List.of(
                        "src/a.java:",
                        "  3: foo here",
                        "  12: foo again",
                        "src/b.java:",
                        "  1: " + "x".repeat(200) + "..."),
                out);
    }

    @Test
    void collapsesRepeatedLines() {
        final var out = run("ls", "a", "a", "a", "a", "b");
        assertEquals(List.of("4 x a", "b"), out);
    }

    public static class Settings extends ConfigurationTestModule {
        public Settings() {
            super(Map.of("yca.optimize.maxEntries", "20"));
        }
    }
}
