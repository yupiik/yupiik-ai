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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for the bash tree-kill: a command that spawns a foreground child and waits
 * ({@code sh -c 'sleep 30 & echo $! > pid; wait'}) must not leave the child running after the
 * bash tool times out or is interrupted. Uses pid-file polling and a short timeout so the test is
 * fast and never sleeps on the real 30s.
 */
class BashToolProcessTest {
    @TempDir
    private static Path testWorkdir;

    @Test
    void defaultTimeoutIsDocumentedInTheSchema() {
        // The default command timeout must be 120s + 5s headroom so a `sleep 120` is not killed on the
        // exact bound of its own budget. Guard both the schema text (what the model sees) and that the
        // fallback timeout_seconds default is 125.
        final var bash = new BashTool(NOOP, new ProcessService());
        @SuppressWarnings("unchecked")
        final var properties = (Map<String, Object>) bash.schema().get("properties");
        @SuppressWarnings("unchecked")
        final var timeoutSchema = (Map<String, Object>) properties.get("timeout_seconds");
        assertTrue(
                String.valueOf(timeoutSchema.get("description")).contains("default 125"),
                "the schema must document the 125s default: " + timeoutSchema.get("description"));
        assertEquals(
                BashTool.DEFAULT_TIMEOUT_SECONDS, 125, "the documented default and the fallback default must match");
    }

    @Test
    void timeoutKillsTheWholeTree(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var service = new ProcessService();
        service.reapGrace(200);
        final var bash = new BashTool(NOOP, service);
        final var pidFile = tmp.resolve("child.pid");

        final var command = "sh -c 'sleep 30 & echo $! > " + pidFile + "; wait'";
        final var result = bash.execute(Map.of("command", command, "timeout_seconds", "1"), context());
        assertFalse(result.success(), "the command must time out: " + result.output());

        final var childPid = awaitPid(pidFile);
        assertNotNull(childPid, "the child must have been spawned before the timeout");
        awaitGone(childPid);
        assertEquals(0, service.trackedCount(), "no process may stay tracked after the timeout");
    }

    @Test
    void defaultsToTheSessionProjectDir(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var projectDir = tmp.resolve("project");
        Files.createDirectories(projectDir);
        final var sentinel = tmp.resolve("pwd.txt");
        final var bash = new BashTool(NOOP, new ProcessService());
        final var ctx = new ToolContext(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Cancellations.NEVER,
                null,
                null,
                projectDir,
                new DisabledSandbox(projectDir));

        final var result = bash.execute(Map.of("command", "pwd > " + sentinel), ctx);
        assertTrue(result.success(), "the command must run: " + result.output());
        assertEquals(
                projectDir.toAbsolutePath().normalize().toString(),
                Files.readString(sentinel).strip(),
                "without an explicit workdir the session project dir must be the cwd");
    }

    @Test
    void explicitWorkdirWinsOverTheSessionProjectDir(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var projectDir = tmp.resolve("project");
        final var override = tmp.resolve("override");
        Files.createDirectories(projectDir);
        Files.createDirectories(override);
        final var sentinel = tmp.resolve("pwd.txt");
        final var bash = new BashTool(NOOP, new ProcessService());
        final var ctx = new ToolContext(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Cancellations.NEVER,
                null,
                null,
                projectDir,
                new DisabledSandbox(projectDir));

        final var result = bash.execute(Map.of("command", "pwd > " + sentinel, "workdir", override.toString()), ctx);
        assertTrue(result.success(), "the command must run: " + result.output());
        assertEquals(
                override.toAbsolutePath().normalize().toString(),
                Files.readString(sentinel).strip(),
                "an explicit workdir must override the session project dir");
    }

    @Nested
    @FusionSupport(modules = RelativeWorkdirIsResolvedPerSessionNotAgainstConfiguredRootScenarioModule.class)
    class RelativeWorkdirIsResolvedPerSessionNotAgainstConfiguredRootScenario {
        @TempDir
        static Path directory;

        @Test
        void relativeWorkdirIsResolvedPerSessionNotAgainstConfiguredRoot(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var tmp = RelativeWorkdirIsResolvedPerSessionNotAgainstConfiguredRootScenario.directory;
            Assumptions.assumeTrue(
                    !System.getProperty("os.name", "").toLowerCase().contains("win"));
            final var configured =
                    Files.createDirectories(tmp.resolve("configured/nested")).getParent();
            final var first =
                    Files.createDirectories(tmp.resolve("first/nested")).getParent();
            final var second =
                    Files.createDirectories(tmp.resolve("second/nested")).getParent();
            final var bash = new BashTool(NOOP, new ProcessService(), configuration, null);
            for (final var root : new Path[] {first, second, first}) {
                final var result = bash.execute(
                        Map.of("command", "pwd", "workdir", "./nested/../nested"),
                        contextWithProjectDir(root.toString()));
                assertTrue(result.success(), result.output());
                assertTrue(result.output().contains(root.resolve("nested").toString()), result.output());
                assertFalse(result.output().contains(configured.toString()), result.output());
            }
            final var explicit = bash.execute(
                    Map.of("command", "pwd", "workdir", "nested"),
                    ToolContext.empty(configured, new DisabledSandbox(configured)));
            assertTrue(explicit.success(), explicit.output());
            assertTrue(explicit.output().contains(configured.resolve("nested").toString()), explicit.output());
        }
    }

    @Nested
    @FusionSupport(modules = MissingRelativeExplicitWorkdirDoesNotFallBackScenarioModule.class)
    class MissingRelativeExplicitWorkdirDoesNotFallBackScenario {
        @TempDir
        static Path directory;

        @Test
        void missingRelativeExplicitWorkdirDoesNotFallBack(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var tmp = MissingRelativeExplicitWorkdirDoesNotFallBackScenario.directory;
            Assumptions.assumeTrue(
                    !System.getProperty("os.name", "").toLowerCase().contains("win"));
            final var configured =
                    Files.createDirectories(tmp.resolve("configured/nested")).getParent();
            final var project = Files.createDirectories(tmp.resolve("project"));
            final var bash = new BashTool(NOOP, new ProcessService(), configuration, null);
            final var result = bash.execute(
                    Map.of("command", "pwd", "workdir", "nested"), contextWithProjectDir(project.toString()));
            assertFalse(result.success(), result.output());
            assertEquals("workdir '" + project.resolve("nested") + "' does not exist", result.output());
            final var stale = bash.execute(
                    Map.of("command", "pwd", "workdir", "nested"),
                    contextWithProjectDir(tmp.resolve("gone").toString()));
            assertFalse(stale.success(), stale.output());
            assertEquals("workdir '" + tmp.resolve("gone/nested") + "' does not exist", stale.output());
        }
    }

    @Test
    void missingExplicitWorkdirReturnsAnActionableError() {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var bash = new BashTool(NOOP, new ProcessService());
        final var missing = Path.of("/definitely/not/here/" + System.nanoTime());
        final var result = bash.execute(Map.of("command", "pwd", "workdir", missing.toString()), context());
        assertFalse(result.success(), "a missing explicit workdir must be rejected: " + result.output());
        assertTrue(
                result.output().contains(missing.toString()),
                "the error must name the missing path: " + result.output());
    }

    @Nested
    @FusionSupport(modules = MissingSessionDirectoryDoesNotFallBackToConfiguredRootScenarioModule.class)
    class MissingSessionDirectoryDoesNotFallBackToConfiguredRootScenario {
        @TempDir
        static Path directory;

        @Test
        void missingSessionDirectoryDoesNotFallBackToConfiguredRoot(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var tmp = MissingSessionDirectoryDoesNotFallBackToConfiguredRootScenario.directory;
            Assumptions.assumeTrue(
                    !System.getProperty("os.name", "").toLowerCase().contains("win"));
            final var configDir = Files.createDirectories(tmp.resolve("config"));
            final var bash = new BashTool(NOOP, new ProcessService(), configuration);
            final var missingProject = tmp.resolve("gone");
            final var result = bash.execute(Map.of("command", "pwd"), contextWithProjectDir(missingProject.toString()));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains(missingProject.toString()), result.output());
        }
    }

    @Nested
    @FusionSupport(modules = MissingSessionDirectoryDoesNotFallBackToProcessRootScenarioModule.class)
    class MissingSessionDirectoryDoesNotFallBackToProcessRootScenario {
        @TempDir
        static Path directory;

        @Test
        void missingSessionDirectoryDoesNotFallBackToProcessRoot(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var tmp = MissingSessionDirectoryDoesNotFallBackToProcessRootScenario.directory;
            Assumptions.assumeTrue(
                    !System.getProperty("os.name", "").toLowerCase().contains("win"));
            final var configDir = Files.createDirectories(tmp.resolve("config"));
            final var bash = new BashTool(NOOP, new ProcessService(), configuration);
            final var missingProject = tmp.resolve("gone");
            final var result = bash.execute(Map.of("command", "pwd"), contextWithProjectDir(missingProject.toString()));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains(missingProject.toString()), result.output());
        }
    }

    @Test
    void bashIsmsWorkUnderAutoResolution(@TempDir final Path tmp) throws Exception {
        // The tool resolves to real bash by default (auto), so bash-only syntax must work rather than being
        // rejected by a POSIX /bin/sh (dash): PIPESTATUS is the reported regression.
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        Assumptions.assumeTrue(
                Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash")),
                "needs a real bash so auto resolves to it");
        final var bash = new BashTool(NOOP, new ProcessService());
        final var result = bash.execute(
                Map.of("command", "false | true; echo code=${PIPESTATUS[0]}", "workdir", tmp.toString()), context());
        assertTrue(result.success(), "the bash-ism command must run: " + result.output());
        assertTrue(result.output().contains("code=1"), "PIPESTATUS[0] must be 1 (false|true), got: " + result.output());
    }

    @Nested
    @FusionSupport(modules = PinnedShStillRunsPosixCommandsScenarioModule.class)
    class PinnedShStillRunsPosixCommandsScenario {
        @TempDir
        static Path directory;

        @Test
        void pinnedShStillRunsPosixCommands(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var tmp = PinnedShStillRunsPosixCommandsScenario.directory;
            // Pinning to sh is honored: the configured escape hatch keeps strict-POSIX /bin/sh behavior.
            Assumptions.assumeTrue(
                    !System.getProperty("os.name", "").toLowerCase().contains("win"));
            Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs /bin/sh (POSIX)");
            final var config = configuration;
            final var bash = new BashTool(NOOP, new ProcessService(), config);
            final var result = bash.execute(Map.of("command", "echo ok", "workdir", tmp.toString()), context());
            assertTrue(result.success(), "a pinned-sh command must run: " + result.output());
            assertTrue(result.output().contains("ok"), "echo output expected under pinned sh: " + result.output());
        }
    }

    @Nested
    @FusionSupport(modules = MissingPinnedShellFallsBackToShWithANoticeScenarioModule.class)
    class MissingPinnedShellFallsBackToShWithANoticeScenario {
        @TempDir
        static Path directory;

        @Test
        void missingPinnedShellFallsBackToShWithANotice(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var tmp = MissingPinnedShellFallsBackToShWithANoticeScenario.directory;
            // An unavailable pinned shell cannot hard-fail the tool: it degrades to /bin/sh and reports the deviation
            // so the model can see the command did not run under the requested shell.
            Assumptions.assumeTrue(
                    !System.getProperty("os.name", "").toLowerCase().contains("win"));
            Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs /bin/sh (POSIX)");
            final var config = configuration;
            final var bash = new BashTool(NOOP, new ProcessService(), config);
            final var result = bash.execute(Map.of("command", "echo hi", "workdir", tmp.toString()), context());
            assertTrue(result.success(), "the fallback command must still run: " + result.output());
            assertTrue(result.output().contains("hi"), "fallback output expected: " + result.output());
            assertTrue(
                    result.output().contains("not found") && result.output().contains("yca.tools.shell"),
                    "the missing-pin deviation must be reported: " + result.output());
        }
    }

    private static ToolContext contextWithProjectDir(final String projectDir) {
        final var dir = Path.of(projectDir).toAbsolutePath().normalize();
        return new ToolContext(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Cancellations.NEVER,
                null,
                null,
                dir,
                new DisabledSandbox(dir));
    }

    private static ToolContext context() {
        return ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir));
    }

    private static Long awaitPid(final Path pidFile) throws Exception {
        final var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                final var raw = Files.readString(pidFile).trim();
                if (!raw.isEmpty()) {
                    return Long.parseLong(raw);
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    private static void awaitGone(final long pid) throws InterruptedException {
        final var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("bash child " + pid + " survived the tree kill");
    }

    private static final Emitter NOOP = new Emitter() {
        @Override
        public <T> void emit(final T event) {
            // no-op
        }
    };

    public static class RelativeWorkdirIsResolvedPerSessionNotAgainstConfiguredRootScenarioModule
            extends ConfigurationTestModule {
        public RelativeWorkdirIsResolvedPerSessionNotAgainstConfiguredRootScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    RelativeWorkdirIsResolvedPerSessionNotAgainstConfiguredRootScenario.directory
                            .resolve("configured")
                            .toString()));
        }
    }

    public static class MissingRelativeExplicitWorkdirDoesNotFallBackScenarioModule extends ConfigurationTestModule {
        public MissingRelativeExplicitWorkdirDoesNotFallBackScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    MissingRelativeExplicitWorkdirDoesNotFallBackScenario.directory
                            .resolve("configured")
                            .toString()));
        }
    }

    public static class MissingSessionDirectoryDoesNotFallBackToConfiguredRootScenarioModule
            extends ConfigurationTestModule {
        public MissingSessionDirectoryDoesNotFallBackToConfiguredRootScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    MissingSessionDirectoryDoesNotFallBackToConfiguredRootScenario.directory
                            .resolve("config")
                            .toString()));
        }
    }

    public static class MissingSessionDirectoryDoesNotFallBackToProcessRootScenarioModule
            extends ConfigurationTestModule {
        public MissingSessionDirectoryDoesNotFallBackToProcessRootScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    MissingSessionDirectoryDoesNotFallBackToProcessRootScenario.directory
                            .resolve("config")
                            .toString()));
        }
    }

    public static class PinnedShStillRunsPosixCommandsScenarioModule extends ConfigurationTestModule {
        public PinnedShStillRunsPosixCommandsScenarioModule() {
            super(Map.of("yca.tools.shell", "sh"));
        }
    }

    public static class MissingPinnedShellFallsBackToShWithANoticeScenarioModule extends ConfigurationTestModule {
        public MissingPinnedShellFallsBackToShWithANoticeScenarioModule() {
            super(Map.of("yca.tools.shell", "/nope/nope"));
        }
    }
}
