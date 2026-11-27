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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeServiceTest.StubLlm;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.write.KnowledgeWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenKnowledgeContentToolTest {
    private CodingAgentConfiguration configuration;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
        tmp = Path.of(configuration.mavenRepositoryKnowledge().localRepository())
                .getParent();
    }

    private Path tmp;

    @Nested
    @FusionSupport(modules = MavenToolsUseContextRootWithoutChangingStandaloneDefaultScenarioModule.class)
    class MavenToolsUseContextRootWithoutChangingStandaloneDefaultScenario {
        @TempDir
        static Path directory;

        @Test
        void mavenToolsUseContextRootWithoutChangingStandaloneDefault() throws IOException {
            final var m2 = Files.createDirectories(tmp.resolve("m2-tools"));
            final var plain = Files.createDirectories(tmp.resolve("plain"));
            final var project = Files.createDirectories(tmp.resolve("maven"));
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            new KnowledgeWriter(m2).write(Gav.of("g", "a", "1"), "index", "shared artifact knowledge");
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var service = new MavenRepositoryKnowledgeService(configuration, new StubLlm(), executor, null);
                final var content = new MavenKnowledgeContentTool(service);
                final var index = new MavenRepositoryKnowledgeTool(service);
                final var context = new ToolContext(
                        "session",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        () -> false,
                        null,
                        null,
                        project,
                        new DisabledSandbox(project));
                assertTrue(content.execute(Map.of("gav", "g:a:1"), ToolContext.empty(plain, new DisabledSandbox(plain)))
                        .output()
                        .contains("disabled"));
                assertTrue(content.execute(Map.of("gav", "g:a:1"), context)
                        .output()
                        .contains("shared artifact knowledge"));
                assertTrue(index.execute(
                                Map.of("operation", "index"), ToolContext.empty(plain, new DisabledSandbox(plain)))
                        .output()
                        .contains("disabled"));
                assertTrue(index.execute(Map.of("operation", "index"), context)
                        .output()
                        .contains("indexed 0, skipped 0"));
                assertTrue(content.execute(Map.of("gav", "g:a:1"), ToolContext.empty(plain, new DisabledSandbox(plain)))
                        .output()
                        .contains("disabled"));
                assertTrue(index.execute(
                                Map.of("operation", "index"), ToolContext.empty(plain, new DisabledSandbox(plain)))
                        .output()
                        .contains("disabled"));
            }
        }
    }

    @Nested
    @FusionSupport(modules = ReadsIndexByDefaultAndSiblingByShortNameScenarioModule.class)
    class ReadsIndexByDefaultAndSiblingByShortNameScenario {
        @TempDir
        static Path directory;

        @Test
        void readsIndexByDefaultAndSiblingByShortName() throws IOException, InterruptedException {
            final var m2 = Files.createDirectories(tmp.resolve("m2"));
            final var proj = Files.createDirectories(tmp.resolve("proj"));
            Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
            classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");

            final var config = configuration;
            final var executor = Executors.newVirtualThreadPerTaskExecutor();
            final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
            final var tool = new MavenKnowledgeContentTool(service);
            try {
                service.refresh(proj, null, null, null, null);
                final var run = service.currentIndex(proj);
                assertTrue(run.awaitCompletion(5, TimeUnit.SECONDS), "indexing should have completed");

                final var index = tool.execute(
                        Map.of("gav", "g.com:artifact-a:1.0"),
                        ToolContext.empty(config.resolveWorkingDir(), new DisabledSandbox(config.resolveWorkingDir())));
                assertTrue(index.success(), () -> "index read should succeed: " + index.output());
                assertTrue(index.output().contains("## Knowledge"), () -> "expected index content: " + index.output());

                final var api = tool.execute(
                        Map.of("gav", "g.com:artifact-a:1.0", "file", "api"),
                        ToolContext.empty(config.resolveWorkingDir(), new DisabledSandbox(config.resolveWorkingDir())));
                assertTrue(api.success(), () -> "api read should succeed: " + api.output());
                assertTrue(
                        api.output().contains("Artifact API") || api.output().contains("No public API detected."),
                        () -> "expected api content: " + api.output());

                final var missing = tool.execute(
                        Map.of("gav", "g.com:artifact-a:1.0", "file", "nope"),
                        ToolContext.empty(config.resolveWorkingDir(), new DisabledSandbox(config.resolveWorkingDir())));
                assertTrue(missing.success(), () -> "missing read should still return a message: " + missing.output());
                assertTrue(
                        missing.output().contains("no knowledge file"),
                        () -> "expected not-found message: " + missing.output());
            } finally {
                executor.shutdown();
            }
        }
    }

    private void classesOnly(
            final Path m2, final String group, final String artifact, final String version, final String mainClass)
            throws IOException {
        final var dir = m2.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), "<project/>");
        final var jar = dir.resolve(artifact + "-" + version + ".jar");
        try (final var zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry(mainClass.replace('.', '/') + ".class"));
            zos.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            zos.closeEntry();
        }
    }

    public static class MavenToolsUseContextRootWithoutChangingStandaloneDefaultScenarioModule
            extends ConfigurationTestModule {
        public MavenToolsUseContextRootWithoutChangingStandaloneDefaultScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    "~/.yupiik/coding-agent",
                    "yca.workingDir",
                    ".",
                    "yca.projectDir",
                    MavenToolsUseContextRootWithoutChangingStandaloneDefaultScenario.directory
                            .resolve("plain")
                            .toString(),
                    "yca.mavenRepositoryKnowledge.enabled",
                    "true",
                    "yca.mavenRepositoryKnowledge.localRepository",
                    MavenToolsUseContextRootWithoutChangingStandaloneDefaultScenario.directory
                            .resolve("m2-tools")
                            .toString(),
                    "yca.mavenRepositoryKnowledge.concurrency",
                    "2",
                    "yca.mavenRepositoryKnowledge.batchSize",
                    "2"));
        }
    }

    public static class ReadsIndexByDefaultAndSiblingByShortNameScenarioModule extends ConfigurationTestModule {
        public ReadsIndexByDefaultAndSiblingByShortNameScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    "~/.yupiik/coding-agent",
                    "yca.workingDir",
                    ".",
                    "yca.projectDir",
                    ReadsIndexByDefaultAndSiblingByShortNameScenario.directory
                            .resolve("proj")
                            .toString(),
                    "yca.mavenRepositoryKnowledge.enabled",
                    "true",
                    "yca.mavenRepositoryKnowledge.localRepository",
                    ReadsIndexByDefaultAndSiblingByShortNameScenario.directory
                            .resolve("m2")
                            .toString(),
                    "yca.mavenRepositoryKnowledge.concurrency",
                    "2",
                    "yca.mavenRepositoryKnowledge.batchSize",
                    "2"));
        }
    }
}
