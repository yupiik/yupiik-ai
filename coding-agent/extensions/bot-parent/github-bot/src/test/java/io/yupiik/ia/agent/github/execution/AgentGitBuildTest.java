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
package io.yupiik.ia.agent.github.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in offline build regression: requires a Maven executable and cached plugin dependencies. */
@EnabledIfSystemProperty(named = "github.bot.git.maven", matches = ".+")
class AgentGitBuildTest {
    @TempDir
    Path temporary;

    @Test
    void realPinnedShallowCheckoutSupportsGitCommitIdPluginWhereSourceExportFails() throws Exception {
        final var source = Files.createDirectory(temporary.resolve("upstream"));
        run(source, null, "git", "init", "--quiet");
        run(source, null, "git", "config", "user.name", "Build Fixture");
        run(source, null, "git", "config", "user.email", "fixture@example.invalid");
        run(source, null, "git", "config", "uploadpack.allowFilter", "true");
        final var pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>git-build</artifactId><version>1</version>
                  <packaging>pom</packaging>
                  <build><plugins><plugin>
                    <groupId>io.github.git-commit-id</groupId>
                    <artifactId>git-commit-id-maven-plugin</artifactId><version>9.2.0</version>
                    <configuration>
                      <skipPoms>false</skipPoms>
                      <failOnNoGitDirectory>true</failOnNoGitDirectory>
                      <failOnUnableToExtractRepoInfo>true</failOnUnableToExtractRepoInfo>
                      <generateGitPropertiesFile>true</generateGitPropertiesFile>
                      <generateGitPropertiesFilename>${project.basedir}/target/git.properties</generateGitPropertiesFilename>
                      <commitIdGenerationMode>full</commitIdGenerationMode>
                      <includeOnlyProperties><includeOnlyProperty>git.commit.id.full</includeOnlyProperty></includeOnlyProperties>
                    </configuration>
                  </plugin></plugins></build>
                </project>
                """;
        Files.writeString(source.resolve("pom.xml"), pom);
        run(source, null, "git", "add", "pom.xml");
        run(source, null, "git", "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "initial");
        Files.writeString(source.resolve("README.txt"), "Second genuine upstream commit\n");
        run(source, null, "git", "add", "README.txt");
        run(source, null, "git", "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "second");
        final var head = run(source, null, "git", "rev-parse", "HEAD").output().strip();

        final var exported = Files.createDirectory(temporary.resolve("exported"));
        Files.writeString(exported.resolve("pom.xml"), pom);
        final var baseline = maven(exported);
        assertNotEquals(0, baseline.exit(), baseline.output());
        assertTrue(baseline.output().contains(".git"), baseline.output());

        final var attempt = Files.createDirectory(temporary.resolve("attempt"));
        final var workspace = Files.createDirectory(attempt.resolve("workspace"));
        AgentGitSource.stage(
                attempt,
                "acme/build-fixture",
                head,
                new WorkerOptions.Source(WorkerOptions.SourceMode.git, "", 1000, 536870912, 120));
        // The production helper still selects canonical HTTPS; only this test shim redirects it
        // to a real local Git transport so the regression has no GitHub/network dependency.
        final var tools = Files.createDirectory(temporary.resolve("tools"));
        final var realGit =
                run(temporary, null, "sh", "-c", "command -v git").output().strip();
        final var shim = tools.resolve("git");
        Files.writeString(
                shim,
                "#!/bin/sh\nexport GIT_ALLOW_PROTOCOL=file\nexec " + quote(realGit)
                        + " -c "
                        + quote("url." + source.toUri() + ".insteadOf=https://github.com/acme/build-fixture.git")
                        + " \"$@\"\n");
        Files.setPosixFilePermissions(shim, PosixFilePermissions.fromString("rwx------"));
        final var checkout = run(
                workspace,
                tools,
                "sh",
                workspace.resolve("github-source-git.sh").toString(),
                "init");
        assertEquals(0, checkout.exit(), checkout.output());
        final var repository = workspace.resolve("repository");
        assertEquals(
                head, run(repository, null, "git", "rev-parse", "HEAD").output().strip());
        assertTrue(Files.isRegularFile(repository.resolve(".git/shallow")));
        final var build = maven(repository);
        assertEquals(0, build.exit(), build.output());
        final var metadata = new Properties();
        try (final var input = Files.newInputStream(repository.resolve("target/git.properties"))) {
            metadata.load(input);
        }
        assertEquals(head, metadata.getProperty("git.commit.id.full"));
    }

    private Result maven(final Path directory) throws Exception {
        return run(
                directory,
                null,
                System.getProperty("github.bot.git.maven"),
                "--offline",
                "--batch-mode",
                "--no-transfer-progress",
                "io.github.git-commit-id:git-commit-id-maven-plugin:9.2.0:revision");
    }

    private Result run(final Path directory, final Path tools, final String... command) throws Exception {
        final var log = Files.createTempFile(temporary, "command-", ".log");
        final var builder = new ProcessBuilder(new ArrayList<>(List.of(command)))
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("MAVEN_SKIP_RC", "1");
        if (tools != null) {
            builder.environment()
                    .put("PATH", tools + ":" + builder.environment().get("PATH"));
        }
        final var process = builder.start();
        try {
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                throw new IOException("Git metadata fixture timed out");
            }
            final var output = Files.readString(log);
            return new Result(process.exitValue(), output);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private record Result(int exit, String output) {}
}
