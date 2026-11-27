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
package io.yupiik.ia.agent.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.HookRegistration;
import io.yupiik.ia.agent.core.configuration.HooksConfiguration;
import io.yupiik.ia.agent.core.events.hooks.CallbackRunner;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the {@link CallbackRunner} wire contract against the dependency-free {@link CallbackFixtureMain}
 * launched via {@code java.home} (so it runs in the IDE without the surefire fork). Covers the input payload on
 * stdin and every ack outcome: valid allow/forbid JSON is respected, empty output is a noop, invalid JSON, a
 * non-zero exit and a timeout all make the callback fail (thrown, surfaced as a non-resulting error).
 */
@ExtendWith(TestJsonMapper.class)
class CallbackRunnerTest {
    @TempDir
    Path testWorkdir;

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final ProcessService PROCESSES = new ProcessService();

    @AfterAll
    static void shutdown() {
        PROCESSES.disposeAll();
        EXECUTOR.shutdown();
    }

    @Test
    void forbidDecisionIsRespected(final JsonMapper mapper) {
        final var runner = runner(mapper, "forbid");
        final var responses = runner.run("ToolUsePreEvent", input().payload(), 1, testWorkdir);
        assertEquals(1, responses.size());
        assertEquals(
                HookResponse.HookResponseDecision.forbid, responses.getFirst().decision());
        assertEquals("blocked by test", responses.getFirst().reason());
    }

    @Test
    void allowDecisionIsRespected(final JsonMapper mapper) {
        final var runner = runner(mapper, "allow");
        final var responses = runner.run("ToolUsePreEvent", input().payload(), 1, testWorkdir);
        assertEquals(1, responses.size());
        assertEquals(
                HookResponse.HookResponseDecision.allow, responses.getFirst().decision());
    }

    @Test
    void emptyOutputIsANoop(final JsonMapper mapper) {
        final var runner = runner(mapper, "empty");
        assertTrue(
                runner.run("ToolUsePreEvent", input().payload(), 1, testWorkdir).isEmpty());
    }

    @Test
    void invalidJsonFailsTheCallback(final JsonMapper mapper) {
        final var runner = runner(mapper, "invalid");
        assertTrue(runner.run("ToolUsePreEvent", input().payload(), 1, testWorkdir)
                .isEmpty()); // failure is logged, no decision applies
    }

    @Test
    void nonZeroExitFailsTheCallback(final JsonMapper mapper) {
        final var runner = runner(mapper, "exit");
        assertTrue(runner.run("ToolUsePreEvent", input().payload(), 1, testWorkdir)
                .isEmpty()); // exit 3 => error, no decision
    }

    @Test
    void timeoutFailsTheCallback(final JsonMapper mapper) {
        final var runner = runner(mapper, "slow", 1);
        final long start = System.nanoTime();
        assertTrue(
                runner.run("ToolUsePreEvent", input().payload(), 1, testWorkdir).isEmpty()); // exceeds the 1s timeout
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 10_000, "timeout must bound the callback, took " + elapsedMs + "ms");
    }

    @Test
    void noConfigMeansNoCallbacks(final JsonMapper mapper) {
        final var runner = new CallbackRunner(new HooksConfiguration(List.of()), EXECUTOR, PROCESSES, mapper);
        assertTrue(
                runner.run("ToolUsePreEvent", input().payload(), 1, testWorkdir).isEmpty());
    }

    @Test
    void inputPayloadTokenizedOnStdin(final JsonMapper mapper, @TempDir final Path tmp) throws Exception {
        final var echo = tmp.resolve("echo.json");
        final var runner = runner(mapper, "forbid", 1, List.of("-Dfixture.echo=" + echo));
        runner.run("ToolUsePreEvent", input().payload(), 1, testWorkdir);
        final var received = Files.readString(echo, StandardCharsets.UTF_8);
        assertTrue(received.contains("\"toolName\"") && received.contains("\"bash\""));
        assertTrue(received.contains("\"eventContext\""));
        assertTrue(received.contains("\"sessionId\""));
    }

    @Test
    void honorsConfiguredWorkingDir(final JsonMapper mapper, @TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var workdir = tmp.resolve("hook-cwd");
        Files.createDirectories(workdir);
        final var sentinel = tmp.resolve("started-in").toString();
        // a trivial callback that just records its cwd (empty stdout => noop) and exits 0
        final var hooks = new HooksConfiguration(List.of(new HookRegistration(
                "ToolUsePreEvent", List.of("sh", "-c", "pwd > " + sentinel), workdir.toString(), 10)));
        final var runner = new CallbackRunner(hooks, EXECUTOR, PROCESSES, mapper);
        runner.run("ToolUsePreEvent", input().payload(), 1, testWorkdir);
        assertEquals(
                workdir.toAbsolutePath().normalize().toString(),
                Files.readString(tmp.resolve("started-in")).strip(),
                "the hook callback must run in its configured dir");
    }

    private CallbackRunner runner(final JsonMapper mapper, final String mode) {
        return runner(mapper, mode, 10);
    }

    private CallbackRunner runner(final JsonMapper mapper, final String mode, final int timeout) {
        return runner(mapper, mode, timeout, List.of());
    }

    private CallbackRunner runner(
            final JsonMapper mapper, final String mode, final int timeout, final List<String> extra) {
        final var javaBin =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final var command = new ArrayList<String>();
        command.add(javaBin);
        command.addAll(extra);
        command.addAll(List.of("-cp", fixtureClasspath(), CallbackFixtureMain.class.getName(), mode));
        final var hooks =
                new HooksConfiguration(List.of(new HookRegistration("ToolUsePreEvent", command, null, timeout)));
        return new CallbackRunner(hooks, EXECUTOR, PROCESSES, mapper);
    }

    /** The directory holding {@link CallbackFixtureMain} (test-classes), derived from its own code source. */
    private static String fixtureClasspath() {
        try {
            return Path.of(CallbackFixtureMain.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .toString();
        } catch (final Exception e) {
            return System.getProperty("java.class.path");
        }
    }

    private static ToolUsePreEvent input() {
        return new ToolUsePreEvent(
                new EventContext("s1", null, null, null, null, null, null, null),
                "bash",
                Map.of("command", "ls", "timeout_seconds", 1),
                "call-1",
                new HookResult(),
                null,
                ToolCategory.BASH,
                false);
    }
}
