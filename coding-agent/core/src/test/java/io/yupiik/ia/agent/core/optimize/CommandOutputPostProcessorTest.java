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
import io.yupiik.ia.agent.core.configuration.OptimizeConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for the injected {@link CommandOutputPostProcessor} command-matching/output service. */
@FusionSupport(modules = ConfigurationTestModule.class)
class CommandOutputPostProcessorTest {
    private final CommandOutputPostProcessor service = new CommandOutputPostProcessor();

    @Test
    void commandTokensKeepAsciiWhitespaceAndEmptyInputSemantics() {
        assertEquals("run", service.secondToken("\tgh\t--verbose\nrun\r list \t"));
        assertEquals("list", service.thirdToken("\tgh\t--verbose\nrun\r list \t"));
        assertEquals("", service.secondToken("\t\n"));
        assertEquals("", service.thirdToken("\t\n"));
        assertEquals("", service.secondToken("gh\u2003run"));
        assertEquals("", service.thirdToken("gh\u2003run"));
    }

    @Test
    void normalizesCommands() {
        assertEquals("mvn", service.normalizeCommand("mvn"));
        assertEquals("mvnw", service.normalizeCommand("./mvnw"));
        assertEquals("mvn", service.normalizeCommand("/usr/bin/mvn.cmd"));
        assertEquals("gh", service.normalizeCommand("C:\\tools\\gh.EXE"));
        assertEquals("git", service.normalizeCommand("../git.sh"));
        assertEquals("git.bat", service.normalizeCommand("git.bat"));
        assertEquals("dummy", service.normalizeCommand("/foo/bar/dummy"));
        assertEquals("", service.normalizeCommand(null));
        assertEquals("", service.normalizeCommand("  "));
    }

    @Test
    void matchesAliasesLoosely() {
        assertTrue(service.matchesAlias("./mvnw", Set.of("mvn", "mvnd", "mvnw")));
        assertTrue(service.matchesAlias("MVN.CMD", Set.of("mvn", "mvnd", "mvnw")));
        assertTrue(service.matchesAlias("/usr/local/bin/git.exe", Set.of("git")));
        assertFalse(service.matchesAlias("prettier", Set.of("npm", "pnpm")));
        assertFalse(service.matchesAlias(null, Set.of("git")));
    }

    @Test
    void stripsAnsiAndExtractsTokens() {
        assertEquals("red", service.stripAnsi("\u001B[31mred\u001B[0m"));
        assertEquals("mvn", service.firstToken("mvn -q test"));
        assertEquals("build", service.secondToken("gradle --no-daemon build"));
        assertEquals("", service.secondToken("mvn"));
        assertEquals("stash", service.secondToken("git stash list"));
        assertEquals("view", service.thirdToken("gh pr view 42"));
        assertEquals("list", service.thirdToken("gh run list"));
        assertEquals("", service.thirdToken("gh"));
        assertEquals("", service.thirdToken("gh api"));
        assertEquals("Main.java", service.baseName("/opt/proj/src/Main.java"));
    }

    @Test
    void matchesHeadHandlesPreamble() {
        // plain head (existing behavior)
        assertTrue(service.matchesHead("mvn clean install", "mvn", "mvnd"));
        assertTrue(service.matchesHead("./mvnw test", "mvnw"));
        assertTrue(service.matchesHead("/usr/bin/mvn.cmd -q package", "mvn"));
        assertFalse(service.matchesHead("git status", "mvn", "mvnd"));

        // leading env assignments
        assertTrue(service.matchesHead("MY_FLAG=1 mvn -q test", "mvn", "mvnd"));
        assertTrue(service.matchesHead("HTTP_PROXY=http://x JAVA_HOME=/jdk mvn install", "mvn", "mvnd"));
        assertTrue(service.matchesHead("FOO=1 ./mvnw -q package", "mvnw"));
        // env-prefixed but not an env var (a flag/option) must not be skipped as env
        assertFalse(service.matchesHead("-DskipTests mvn install", "mvn", "mvnd"));

        // leading cd <dir> && segments
        assertTrue(service.matchesHead("cd /opt/dev/project && mvn -q test", "mvn", "mvnd"));
        assertTrue(service.matchesHead("cd /opt/dev/project && git status", "git"));
        assertTrue(service.matchesHead("cd /a && cd /b && mvn install", "mvn", "mvnd"));
        assertTrue(service.matchesHead("cd \"/my path\" && mvn -q test", "mvn", "mvnd"));
        assertTrue(service.matchesHead("cd /a && FOO=1 mvn install", "mvn", "mvnd"));
        assertTrue(service.matchesHead("FOO=1 cd /a && mvn install", "mvn", "mvnd"));

        // cd without the target command afterwards
        assertFalse(service.matchesHead("cd /somewhere", "mvn", "mvnd"));
        assertFalse(service.matchesHead("cd /somewhere &&", "mvn", "mvnd"));

        assertTrue(service.matchesHeadToken("kubectl get pods", "kubectl"));
        assertEquals("mvn", service.headToken("cd /tmp && ENV=1 mvn -q test"));
    }

    @Test
    void unwrapsCommandWrappers() {
        // plain command without a wrapper -> unchanged (empty)
        assertEquals(Optional.empty(), service.unwrapCommandWrapper(null));
        assertEquals(Optional.empty(), service.unwrapCommandWrapper("  "));
        assertEquals(Optional.empty(), service.unwrapCommandWrapper("mvn clean install"));
        assertEquals(Optional.empty(), service.unwrapCommandWrapper("./mvnw test"));

        // timeout <duration>
        assertEquals(
                Optional.of("mvn -o spotless:apply 2>&1"),
                service.unwrapCommandWrapper("timeout 120 mvn -o spotless:apply 2>&1"));
        // timeout with a leading flag
        assertEquals(
                Optional.of("git status"), service.unwrapCommandWrapper("timeout --preserve-status 30 git status"));
        // a non-numeric "duration" is not a wrapper -> unchanged
        assertEquals(Optional.empty(), service.unwrapCommandWrapper("timeout nope mvn install"));
        // bare timeout (no command after the duration) -> unchanged
        assertEquals(Optional.empty(), service.unwrapCommandWrapper("timeout 120"));

        // sudo / nohup / command
        assertEquals(Optional.of("mvn -q test"), service.unwrapCommandWrapper("sudo mvn -q test"));
        assertEquals(Optional.of("git fetch"), service.unwrapCommandWrapper("nohup git fetch"));
        assertEquals(Optional.of("./mvnw package"), service.unwrapCommandWrapper("command ./mvnw package"));

        // env with leading assignments
        assertEquals(
                Optional.of("mvn clean install"), service.unwrapCommandWrapper("env FOO=1 BAR=2 mvn clean install"));
        // bare env assignments without the env keyword are handled natively by matchesHead, so there
        // is no wrapper to strip here (empty = leave the command unchanged)
        assertEquals(Optional.empty(), service.unwrapCommandWrapper("FOO=1 mvn clean install"));

        // chained wrappers
        assertEquals(Optional.of("mvn -o apply"), service.unwrapCommandWrapper("sudo timeout 120 mvn -o apply"));
        assertEquals(Optional.of("docker ps"), service.unwrapCommandWrapper("nohup env CI=1 sudo docker ps"));

        // sh/bash -c
        assertEquals(Optional.of("mvn test"), service.unwrapCommandWrapper("sh -c \"mvn test\""));
        assertEquals(Optional.of("git status"), service.unwrapCommandWrapper("bash -c \"git status\""));
        assertEquals(
                Optional.of("mvn -o apply"), service.unwrapCommandWrapper("bash -c \"sudo timeout 120 mvn -o apply\""));
        // a multi-command script inside -c is left to the generic fallback
        assertEquals(Optional.empty(), service.unwrapCommandWrapper("bash -c \"mvn package && git status\""));
        assertEquals(Optional.empty(), service.unwrapCommandWrapper("sh -c \"ls | wc -l\""));

        // cd <dir> && preamble kept consistent with matchesHead
        assertEquals(Optional.of("mvn -q test"), service.unwrapCommandWrapper("cd /opt/proj && sudo mvn -q test"));
    }

    @Test
    void containsFlagDetectsExplicitFlag() {
        assertTrue(service.containsFlag("mvn --debug test", "--debug"));
        assertFalse(service.containsFlag("mvn test --", "--debug"));
    }

    @Test
    void isDisabledMatchesConfiguredNames() {
        final var maven = new OptimizeConfiguration(true, 400, 20, 10, true, List.of("mvn"), null, null);
        assertTrue(service.isDisabled(maven, List.of("mvn", "mvnd", "mvnw")));
        assertFalse(service.isDisabled(maven, List.of("git")));

        final var mvwn = new OptimizeConfiguration(true, 400, 20, 10, true, List.of("./mvnw.cmd"), null, null);
        assertTrue(service.isDisabled(mvwn, List.of("mvn", "mvnd", "mvnw")));

        final var tools = new OptimizeConfiguration(true, 400, 20, 10, true, List.of("bash"), null, null);
        assertTrue(service.isDisabled(tools, List.of("bash")));

        final var none = new OptimizeConfiguration(true, 400, 20, 10, true, List.of(), null, null);
        assertFalse(service.isDisabled(none, List.of("mvn")));
    }

    @Test
    void displayNameDropsFusionSubclass() {
        assertEquals("GenericOutputProcessor", new GenericOutputProcessor(null).displayName());
        assertEquals("Fake", new Fake$FusionSubclass().displayName());
    }

    @Test
    void processorsDeclareCommandSpellings(
            @Fusion final MavenOutputProcessor maven, @Fusion final GradleOutputProcessor gradle) {
        assertEquals(Set.of("mvn", "mvnd", "mvnw"), maven.names());
        assertEquals(Set.of("gradle", "gradlew"), gradle.names());
        assertEquals(Set.of("git"), new GitOutputProcessor().names());
        assertEquals(Set.of("gh"), new GhOutputProcessor().names());
        assertEquals(Set.of("npm", "pnpm", "yarn", "bun"), new NpmOutputProcessor().names());
        assertEquals(Set.of("prettier"), new PrettierOutputProcessor().names());
        assertEquals(Set.of("generic"), new GenericOutputProcessor(null).names());
        assertEquals(
                Set.of("ls", "find", "tree", "du", "grep", "rg", "egrep", "fgrep", "ast-grep"),
                new FilesystemSearchOutputProcessor(null).names());
        assertEquals(Set.of("tsc", "eslint", "biome", "next"), new JsOutputProcessor(null).names());
        assertEquals(Set.of("kubectl"), new KubectlOutputProcessor().names());
        assertEquals(Set.of("docker", "docker-compose", "docker compose"), new DockerOutputProcessor().names());
        assertEquals(Set.of("json"), new JsonOutputProcessor(null, null).names());
    }

    private static class Fake$FusionSubclass extends GenericOutputProcessor {}
}
