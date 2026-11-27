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
package io.yupiik.ia.agent.bot.runtime.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.yupiik.ia.agent.bot.runtime.ExecutionPolicy;
import io.yupiik.ia.agent.bot.runtime.TestScript;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.testing.UnlinkingTempDirDeletionStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DockerExecutionBackendTest {
    @TempDir(deletionStrategy = UnlinkingTempDirDeletionStrategy.class)
    Path home;

    @Test
    void rejectsOptionShapedImagesAtConstructionWithoutLaunchingDocker() {
        final var executable = home.resolve("must-not-run-docker").toString();
        for (final var image :
                List.of("--privileged", "--network=host", "-v", "-trusted-image", " image", "image --privileged")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DockerExecutionBackend(
                            ExecutionPolicies.CONTEXT,
                            executable,
                            image,
                            Duration.ofSeconds(1),
                            "",
                            null,
                            null,
                            "hidden",
                            false),
                    image);
        }
        assertDoesNotThrow(() -> new DockerExecutionBackend(
                ExecutionPolicies.CONTEXT,
                executable,
                "registry.example:5000/team/agent:trusted-1",
                Duration.ofSeconds(1),
                "",
                null,
                null,
                "hidden",
                false));
        assertFalse(Files.exists(home.resolve("execution.log")));
    }

    @Test
    void rejectsProtectedAndNullToolEnvironmentBeforeAnyLaunch() throws Exception {
        final var executable = script("touch \"$0.called\"\n");
        final var backend = backend(executable);
        for (final var key : List.of(
                "HOME",
                "PATH",
                "GH_CONFIG_DIR",
                "TMPDIR",
                "GH_TOKEN",
                "GITHUB_TOKEN",
                "GITHUB_BOT_FORK_TOKEN",
                "GITHUB_SOURCE_TOKEN",
                "YCA_CONTEXT_TOKEN",
                "YCA_CONTEXT_GIT_TOKEN",
                "YCA_BROKER_URL",
                "YCA_BROKER_TOKEN",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS",
                "DOCKER_HOST",
                "DOCKER_CONFIG",
                "LD_PRELOAD",
                "LD_LIBRARY_PATH")) {
            final var environment = Map.of(key, "must-not-forward");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.execute(home, "yca-task", "review", Map.of(), environment),
                    key);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.execute(home, "yca-task", "review", Map.of(), environment, null, Map.of(), null),
                    key);
        }
        final var environment = new HashMap<String, String>();
        environment.put("MAVEN_OPTS", null);
        assertThrows(
                IllegalArgumentException.class,
                () -> backend.execute(home, "yca-task", "review", Map.of(), environment));
        assertFalse(Files.exists(Path.of(executable + ".called")));
        assertFalse(Files.exists(home.resolve("execution.log")));
    }

    @Test
    void localDaemonProbeInspectsEffectiveContextWithSanitizedEnvironment() throws Exception {
        final var executable = script("""
        printf '%s\\n' "$@" > "$0.args"
        env > "$0.env"
        printf 'ignored diagnostic with credentials\\n' >&2
        printf 'unix:///run/user/10001/docker.sock\\n'
        """);
        backend(executable).requireLocalDaemon();
        assertEquals(
                List.of("context", "inspect", "--format", "{{(index .Endpoints \"docker\").Host}}"),
                Files.readAllLines(Path.of(executable + ".args")));
        final var actual = new HashMap<String, String>();
        for (final var entry : Files.readAllLines(Path.of(executable + ".env"))) {
            final int equals = entry.indexOf('=');
            if (!entry.startsWith("PWD=")) {
                actual.put(entry.substring(0, equals), entry.substring(equals + 1));
            }
        }
        assertEquals(
                DockerControlEnvironment.sanitize(new ProcessBuilder("unused")).environment(), actual);
    }

    @Test
    void localDaemonProbeRejectsStoredRemoteAndMalformedEndpoints() throws Exception {
        for (final var endpoint : List.of(
                "ssh://operator@private-host",
                "tcp://private-host:2376",
                "",
                "<no value>",
                "unix://",
                "unix:/run/docker.sock",
                "unix://remote/run/docker.sock",
                "unix://user@remote/run/docker.sock",
                "unix:///run/docker.sock?query",
                "unix:///run/docker.sock#fragment",
                "unix:///run/%00docker.sock",
                "unix:///bad%zz",
                "unix:///run/docker.sock\nunix:///another.sock")) {
            final var executable = script("printf '%s\\n' '" + endpoint + "'\n");
            final var error = assertThrows(
                    IllegalStateException.class, () -> backend(executable).requireLocalDaemon());
            assertEquals("Broker mode requires a declared local Docker Unix socket", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test
    void localDaemonProbeRejectsNonzeroAndOversizedOutput() throws Exception {
        for (final var body : List.of(
                "printf 'unix:///run/docker.sock\\n'\nprintf 'secret diagnostic' >&2\nexit 1\n",
                "printf '%s' 'unix:///" + "a".repeat(2048) + "'\n")) {
            final var executable = script(body);
            assertThrows(IllegalStateException.class, () -> backend(executable).requireLocalDaemon());
        }
    }

    @Test
    void localDaemonProbeTimeoutKillsAndReapsClientAndDescendant() throws Exception {
        final var executable = script("""
        printf '%s' "$$" > "$0.pid"
        sleep 60 &
        printf '%s' "$!" > "$0.child"
        wait
        """);
        try {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(4),
                    () -> assertThrows(
                            IllegalStateException.class,
                            () -> new DockerExecutionBackend(
                                            ExecutionPolicies.CONTEXT,
                                            executable.toString(),
                                            "trusted-image",
                                            Duration.ofMillis(500),
                                            "",
                                            null,
                                            null,
                                            "shown",
                                            false)
                                    .requireLocalDaemon()));
            for (final var suffix : List.of(".pid", ".child")) {
                final long pid = Long.parseLong(Files.readString(Path.of(executable + suffix)));
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            }
        } finally {
            for (final var suffix : List.of(".child", ".pid")) {
                final var file = Path.of(executable + suffix);
                if (Files.exists(file)) {
                    ProcessHandle.of(Long.parseLong(Files.readString(file))).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
        }
    }

    @Test
    void timeoutReturnsEvenWhenRemoteTerminationFails() throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("if [ \"$1\" = run ]; then exec sleep 60; fi\nprintf 'unknown\\n'\n");
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> new DockerExecutionBackend(
                                        ExecutionPolicies.CONTEXT,
                                        executable.toString(),
                                        "trusted-image",
                                        Duration.ofMillis(50),
                                        "",
                                        null,
                                        null,
                                        "shown",
                                        false)
                                .execute(directory, "yca-timeout-stop-failure", "review", Map.of(), Map.of())));
    }

    @Test
    void passesConfiguredNetworkAsSingleArgument() throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("printf '%s\\n' \"$@\" > \"$0.args\"\nexit 0\n");
        assertEquals(
                0,
                new DockerExecutionBackend(
                                ExecutionPolicies.CONTEXT,
                                executable.toString(),
                                "trusted-image",
                                Duration.ofSeconds(5),
                                "restricted-egress",
                                null,
                                null,
                                "shown",
                                false)
                        .execute(directory, "yca-network-1", "review", Map.of(), Map.of()));
        final var args = Files.readAllLines(Path.of(executable + ".args"));
        assertEquals("restricted-egress", args.get(args.indexOf("--network") + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend(
                        ExecutionPolicies.CONTEXT,
                        "docker",
                        "image",
                        Duration.ofSeconds(1),
                        "bad network",
                        null,
                        null,
                        "shown",
                        false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"shown", "collapsed", "hidden"})
    void executesConcreteBackendWithIsolatedMountsArgumentsAndCredentials(final String showToolOutput)
            throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("""
        printf '%s\\n' "$@" > "$0.args"
        printf '%s\\n' "$GH_TOKEN" "$GITHUB_BOT_FORK_TOKEN" "$GITHUB_SOURCE_TOKEN" "$YCA_CONTEXT_TOKEN" "$YCA_CONTEXT_GIT_TOKEN" "$HOME" > "$0.environment"
        env > "$0.env"
        printf 'executor output\\n'
        printf 'executor error\\n' >&2
        exit 7
        """);
        final var prompt = "Review this; $(touch must-not-exist) 'quoted'";
        final var toolEnvironment = Map.of(
                "MAVEN_OPTS", "-Dmaven.repo.local=/attempt/storage/cache/maven -Dliteral=$(never)",
                "npm_config_cache", "/attempt/storage/cache/npm",
                "NPM_CONFIG_USERCONFIG", "/attempt/storage/cache/npmrc",
                "GRADLE_USER_HOME", "/attempt/storage/cache/gradle",
                "PIP_CACHE_DIR", "/attempt/storage/cache/pip",
                "GOMODCACHE", "/attempt/storage/cache/go",
                "CARGO_HOME", "/attempt/storage/cache/cargo",
                "NUGET_PACKAGES", "/attempt/storage/cache/nuget");
        assertEquals(
                7,
                new DockerExecutionBackend(
                                ExecutionPolicies.CONTEXT,
                                executable.toString(),
                                "trusted-image",
                                Duration.ofSeconds(5),
                                "",
                                null,
                                null,
                                showToolOutput,
                                false)
                        .execute(
                                directory,
                                "yca-task-1",
                                prompt,
                                Map.of(
                                        "GH_TOKEN", "agent-secret",
                                        "GITHUB_BOT_FORK_TOKEN", "fork-secret",
                                        "GITHUB_SOURCE_TOKEN", "source-secret",
                                        "YCA_CONTEXT_TOKEN", "context-secret",
                                        "YCA_CONTEXT_GIT_TOKEN", "context-git-secret"),
                                toolEnvironment));
        final var uid = ((Number) Files.getAttribute(directory, "unix:uid")).intValue();
        final var gid = ((Number) Files.getAttribute(directory, "unix:gid")).intValue();
        final var args = Files.readAllLines(Path.of(executable + ".args"));
        final var expectedPrefix = List.of(
                "run",
                "--rm",
                "--name",
                "yca-task-1",
                "--init",
                "--user",
                uid + ":" + gid,
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--pids-limit=512",
                "--memory=4g",
                "--cpus=2",
                "--read-only",
                "--tmpfs",
                "/tmp:rw,nosuid,nodev,size=1g");
        assertEquals(expectedPrefix, args.subList(0, expectedPrefix.size()));
        for (final var name : List.of("configuration.json", "storage", "workspace", "output")) {
            final var mount = "type=bind,src=" + directory.resolve(name) + ",dst=/attempt/" + name
                    + ("configuration.json".equals(name) ? ",readonly" : "");
            assertTrue(args.contains(mount), mount);
        }
        assertEquals(4, args.stream().filter("--mount"::equals).count());
        toolEnvironment.forEach((key, value) -> {
            assertEquals("--env", args.get(args.indexOf(key + "=" + value) - 1));
            assertFalse(args.contains(key), "Tool environment must not be inherited by name");
        });
        assertTrue(args.containsAll(List.of(
                "HOME=/attempt/storage",
                "GH_CONFIG_DIR=/attempt/storage/gh",
                "--workdir",
                "/attempt/workspace",
                "GH_TOKEN",
                "GITHUB_BOT_FORK_TOKEN",
                "GITHUB_SOURCE_TOKEN",
                "YCA_CONTEXT_TOKEN",
                "YCA_CONTEXT_GIT_TOKEN")));
        for (final var key : List.of(
                "GH_TOKEN",
                "GITHUB_BOT_FORK_TOKEN",
                "GITHUB_SOURCE_TOKEN",
                "YCA_CONTEXT_TOKEN",
                "YCA_CONTEXT_GIT_TOKEN")) {
            assertEquals("--env", args.get(args.indexOf(key) - 1));
        }
        assertEquals(
                List.of(
                        "trusted-image",
                        "run",
                        "--show-tool-output",
                        showToolOutput,
                        "--yca-configuration-json",
                        "/attempt/configuration.json",
                        "--yca-configuration-project-enabled",
                        "false",
                        "--yca-storage-home",
                        "/attempt/storage",
                        "--yca-workingDir",
                        "/attempt/workspace",
                        "--result-file",
                        "/attempt/output/result.json",
                        "--result-required-fields",
                        "taskId,summary|failure",
                        "--result-max-bytes",
                        "6291456",
                        "--prompt",
                        prompt),
                args.subList(args.size() - 20, args.size()));
        for (final var secret :
                List.of("agent-secret", "fork-secret", "source-secret", "context-secret", "context-git-secret")) {
            assertFalse(args.toString().contains(secret));
            assertFalse(Files.readString(directory.resolve("execution.log")).contains(secret));
            assertFalse(
                    Files.readString(directory.resolve("configuration.json")).contains(secret));
        }
        final var environment = Files.readAllLines(Path.of(executable + ".environment"));
        assertEquals("agent-secret", environment.get(0));
        assertEquals("fork-secret", environment.get(1));
        assertEquals("source-secret", environment.get(2));
        assertEquals("context-secret", environment.get(3));
        assertEquals("context-git-secret", environment.get(4));
        // The CLI receives host credentials only in its environment, not in command arguments.
        final var allowed = List.of(
                "PATH",
                "HOME",
                "DOCKER_HOST",
                "DOCKER_CONTEXT",
                "DOCKER_CONFIG",
                "DOCKER_TLS",
                "DOCKER_TLS_VERIFY",
                "DOCKER_CERT_PATH",
                "DOCKER_API_VERSION",
                "XDG_RUNTIME_DIR",
                "XDG_CONFIG_HOME",
                "XDG_DATA_HOME",
                "XDG_STATE_HOME",
                "SSH_AUTH_SOCK",
                "GH_TOKEN",
                "GITHUB_BOT_FORK_TOKEN",
                "GITHUB_SOURCE_TOKEN",
                "YCA_CONTEXT_TOKEN",
                "YCA_CONTEXT_GIT_TOKEN",
                "PWD"); // POSIX shells can synthesize PWD.
        for (final var entry : Files.readAllLines(Path.of(executable + ".env"))) {
            assertTrue(
                    allowed.contains(entry.substring(0, entry.indexOf('='))), entry.substring(0, entry.indexOf('=')));
        }
        assertEquals("executor output\nexecutor error\n", Files.readString(directory.resolve("execution.log")));
    }

    @Test
    void boundsRetainedLogWhileDrainingOutputToCompletion() throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("head -c 8396800 /dev/zero\nexit 0\n");
        assertEquals(0, backend(executable).execute(directory, "yca-task-2", "review", Map.of(), Map.of()));
        assertEquals(8L * 1024 * 1024, Files.size(directory.resolve("execution.log")));
    }

    @Test
    void timeoutInspectsAndTerminatesRuntimeWithoutRetryingExecution() throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("""
        printf '%s\\n' "$1" >> "$0.calls"
        if [ "$1" = run ]; then
          exec sleep 5
        fi
        printf 'fixture.context|%s|agent|false\\n' "$last"
        """);
        final var error = assertThrows(
                IllegalStateException.class,
                () -> new DockerExecutionBackend(
                                ExecutionPolicies.CONTEXT,
                                executable.toString(),
                                "trusted-image",
                                Duration.ofSeconds(1),
                                "",
                                null,
                                null,
                                "shown",
                                false)
                        .execute(directory, "yca-timeout-1", "review", Map.of(), Map.of()));
        assertEquals("Execution timeout", error.getMessage());
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertEquals("run", calls.getFirst());
        assertEquals(1, calls.stream().filter("run"::equals).count());
        assertTrue(calls.size() >= 2);
        assertTrue(calls.subList(1, calls.size()).stream().allMatch("container"::equals));
    }

    @Test
    void rejectsUnsupportedCredentialsBeforeInvokingExecutable() throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("touch \"$0.invoked\"\n");
        assertThrows(
                IllegalArgumentException.class,
                () -> backend(executable)
                        .execute(directory, "yca-task-3", "review", Map.of("HOST_TOKEN", "secret"), Map.of()));
        assertFalse(Files.exists(Path.of(executable + ".invoked")));
    }

    @Test
    void refusesRootOwnedAttemptBeforeInvokingExecutable() throws Exception {
        final var directory = attempt();
        assumeTrue(
                ((Number) Files.getAttribute(directory, "unix:uid")).intValue() == 0,
                "Root ownership guard is applicable only to root-owned attempts");
        final var executable = script("touch \"$0.invoked\"\n");
        final var error = assertThrows(
                IllegalStateException.class,
                () -> backend(executable).execute(directory, "yca-task-4", "review", Map.of(), Map.of()));
        assertTrue(error.getMessage().contains("non-root"));
        assertFalse(Files.exists(Path.of(executable + ".invoked")));
    }

    @Test
    void stopInspectsThenKillsOnlyRunningContainer() throws Exception {
        final var executable = script("""
        printf '%s\\n' "$*" >> "$0.calls"
        env > "$0.$1.env"
        if [ "$1" = container ]; then
          printf 'fixture.context|%s|agent|true\\n' "$last"
        fi
        """);
        backend(executable).stop("yca-task-5");
        assertEquals(
                List.of(agentInspection("yca-task-5"), "kill yca-task-5"),
                Files.readAllLines(Path.of(executable + ".calls")));
        final var expected =
                DockerControlEnvironment.sanitize(new ProcessBuilder("unused")).environment();
        for (final var phase : List.of("container", "kill")) {
            final var actual = new HashMap<String, String>();
            for (final var entry : Files.readAllLines(Path.of(executable + "." + phase + ".env"))) {
                final int equals = entry.indexOf('=');
                if (!entry.startsWith("PWD=")) {
                    actual.put(entry.substring(0, equals), entry.substring(equals + 1));
                }
            }
            assertEquals(expected, actual, phase);
        }
    }

    @Test
    void stopAcceptsMissingOrStoppedContainersButRejectsUncertainInspection() throws Exception {
        for (final var response : List.of(
                "printf 'fixture.context|yca-task-6|agent|false\\n'\n",
                "printf 'Error: No such object: yca-task-6\\n'\nexit 1\n",
                "printf 'Error response from daemon: No such container: yca-task-6\\n'\nexit 1\n")) {
            final var executable = script("printf '%s\\n' \"$*\" >> \"$0.calls\"\n" + response);
            backend(executable).stop("yca-task-6");
            assertEquals(List.of(agentInspection("yca-task-6")), Files.readAllLines(Path.of(executable + ".calls")));
        }
        final var unavailable = script("printf 'Cannot connect to Docker daemon\\n'\nexit 1\n");
        assertThrows(IllegalStateException.class, () -> backend(unavailable).stop("yca-task-6"));
        final var unexpected = script("printf 'unexpected\\n'\n");
        assertThrows(IllegalStateException.class, () -> backend(unexpected).stop("yca-task-6"));
    }

    @Test
    void missingContainerMustNameExactRuntimeAndNotHideDaemonFailures() throws Exception {
        for (final var response : List.of(
                "dial tcp: lookup docker: no such host",
                "No such object",
                "Error: No such object: yca-other",
                "Error: No such object: yca-task-6-extra",
                "Error: No such object: yca-task-6" + " ".repeat(4096) + "daemon failure",
                "Error: No such object: yca-task-6\nCannot connect to Docker daemon")) {
            final var executable = script("printf '%s\\n' '" + response + "'\nexit 1\n");
            final var error = assertThrows(
                    IllegalStateException.class, () -> backend(executable).stop("yca-task-6"), response);
            assertTrue(error.getMessage().contains("uncertain"));
        }
    }

    @Test
    void interruptionTerminatesLocalInspectAndKillClientsButDoesNotConfirmRemoteExit() throws Exception {
        for (final var phase : List.of("container", "kill")) {
            final var executable = script("""
          if [ "$1" = '%s' ]; then
            printf '%%s' "$$" > "$0.pid"
            exec /bin/sleep 60
          fi
          printf 'fixture.context|%%s|agent|true\\n' "$last"
          """.formatted(phase));
            final var result = new CompletableFuture<Boolean>();
            final var worker = Thread.ofPlatform().start(() -> {
                try {
                    assertThrows(
                            IllegalStateException.class,
                            () -> backend(executable).stop("yca-interrupted"));
                    result.complete(Thread.currentThread().isInterrupted());
                } catch (final Throwable e) {
                    result.completeExceptionally(e);
                }
            });
            ProcessHandle client = null;
            try {
                final var pid = Path.of(executable + ".pid");
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    while (!Files.exists(pid) || Files.size(pid) == 0) {
                        Thread.sleep(10);
                    }
                });
                client = ProcessHandle.of(Long.parseLong(Files.readString(pid))).orElseThrow();
                worker.interrupt();
                assertTrue(result.get(3, TimeUnit.SECONDS), "Interrupt status must be restored");
                assertFalse(client.isAlive(), "The local Docker client must be terminated");
            } finally {
                worker.interrupt();
                if (client != null) {
                    client.destroyForcibly();
                }
                worker.join(3000);
            }
        }
    }

    @Test
    void stopRejectsInvalidIdentityAndFailedKill() throws Exception {
        final var executable = script("""
        printf '%s\\n' "$*" >> "$0.calls"
        if [ "$1" = container ]; then
          printf 'fixture.context|%s|agent|true\\n' "$last"
        else
          exit 1
        fi
        """);
        assertThrows(IllegalArgumentException.class, () -> backend(executable).stop("not-a-runtime"));
        assertFalse(Files.exists(Path.of(executable + ".calls")));
        assertThrows(IllegalStateException.class, () -> backend(executable).stop("yca-task-7"));
        assertEquals(2, Files.readAllLines(Path.of(executable + ".calls")).size());
    }

    @Test
    void requiresExecutableImageAndPositiveBudget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend(
                        ExecutionPolicies.CONTEXT,
                        " ",
                        "image",
                        Duration.ofSeconds(1),
                        "",
                        null,
                        null,
                        "shown",
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend(
                        ExecutionPolicies.CONTEXT,
                        "docker",
                        "",
                        Duration.ofSeconds(1),
                        "",
                        null,
                        null,
                        "shown",
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend(
                        ExecutionPolicies.CONTEXT, "docker", "image", Duration.ZERO, "", null, null, "shown", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend(
                        ExecutionPolicies.CONTEXT,
                        "docker",
                        "image",
                        Duration.ofSeconds(-1),
                        "",
                        null,
                        null,
                        "shown",
                        false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "1", "3600"})
    void mountsOnlyPreparedFeedbackAndPassesTrustedTimeout(final String timeout) throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        Files.createDirectory(
                directory.resolve("feedback"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!"default".equals(timeout)) {
            Files.writeString(directory.resolve(".feedback-timeout"), timeout + "\n");
            Files.setPosixFilePermissions(
                    directory.resolve(".feedback-timeout"), PosixFilePermissions.fromString("rw-------"));
        }
        final var executable = script("printf '%s\\n' \"$@\" > \"$0.args\"\n");
        assertEquals(0, backend(executable).execute(directory, "yca-feedback", "review", Map.of(), Map.of()));
        final var args = Files.readAllLines(Path.of(executable + ".args"));
        assertTrue(args.contains("type=bind,src=" + directory.resolve("feedback") + ",dst=/attempt/feedback"));
        assertEquals(
                List.of(
                        "--yca-bot-feedback-enabled",
                        "true",
                        "--yca-bot-feedback-timeoutSeconds",
                        "default".equals(timeout) ? "600" : timeout),
                args.subList(args.size() - 4, args.size()));
        assertEquals("false", args.get(args.indexOf("--yca-configuration-project-enabled") + 1));
        assertFalse(args.toString().contains(".feedback-timeout"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "0",
                "-1",
                "3601",
                "1x",
                "999999999999999999999999",
                "link",
                "public",
                "file",
                "timeout-link",
                "timeout-public"
            })
    void rejectsUnsafeFeedbackBeforeLaunch(final String invalid) throws Exception {
        final var directory = attempt();
        final var feedback = directory.resolve("feedback");
        Files.createDirectory(
                feedback, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        switch (invalid) {
            case "link" -> {
                Files.delete(feedback);
                Files.createSymbolicLink(feedback, home);
            }
            case "public" -> Files.setPosixFilePermissions(feedback, PosixFilePermissions.fromString("rwxr-xr-x"));
            case "file" -> {
                Files.delete(feedback);
                Files.writeString(feedback, "not a mailbox");
            }
            case "timeout-link" ->
                Files.createSymbolicLink(
                        directory.resolve(".feedback-timeout"), directory.resolve("configuration.json"));
            case "timeout-public" -> {
                Files.writeString(directory.resolve(".feedback-timeout"), "600");
                Files.setPosixFilePermissions(
                        directory.resolve(".feedback-timeout"), PosixFilePermissions.fromString("rw-rw-rw-"));
            }
            default -> {
                Files.writeString(directory.resolve(".feedback-timeout"), invalid);
                Files.setPosixFilePermissions(
                        directory.resolve(".feedback-timeout"), PosixFilePermissions.fromString("rw-------"));
            }
        }
        final var executable = script("touch \"$0.called\"\n");
        assertThrows(
                IllegalArgumentException.class,
                () -> backend(executable).execute(directory, "yca-feedback", "review", Map.of(), Map.of()));
        assertFalse(Files.exists(Path.of(executable + ".called")));
    }

    @Test
    void feedbackCreatedByLaunchedProcessNeverEnablesIt() throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable =
                script("mkdir -m 700 '" + directory.resolve("feedback") + "'\nprintf '%s\\n' \"$@\" > \"$0.args\"\n");
        assertEquals(0, backend(executable).execute(directory, "yca-late-feedback", "review", Map.of(), Map.of()));
        assertTrue(Files.isDirectory(directory.resolve("feedback")));
        final var args = Files.readAllLines(Path.of(executable + ".args"));
        assertFalse(args.contains("--yca-bot-feedback-enabled"));
        assertFalse(args.stream().anyMatch(argument -> argument.contains("dst=/attempt/feedback")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void containerLogSuppressionOnlyChangesAgentLogDriver(final boolean disableContainerLogs) throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("printf '%s\\n' \"$@\" > \"$0.args\"\n" + "env > \"$0.env\"\n");
        final var backend = new DockerExecutionBackend(
                ExecutionPolicies.CONTEXT,
                executable.toString(),
                "trusted-image",
                Duration.ofSeconds(5),
                "trusted-network",
                null,
                null,
                "hidden",
                disableContainerLogs);
        assertEquals(
                0,
                backend.execute(
                        directory,
                        "yca-logs",
                        "review",
                        Map.of("GH_TOKEN", "credential"),
                        Map.of("MAVEN_OPTS", "-Xmx256m")));
        final var args = Files.readAllLines(Path.of(executable + ".args"));
        assertEquals(
                disableContainerLogs ? 1L : 0L,
                args.stream().filter("--log-driver=none"::equals).count());
        if (disableContainerLogs) {
            assertTrue(args.indexOf("--log-driver=none") < args.indexOf("trusted-image"));
        }
        assertTrue(args.containsAll(ExecutionPolicies.CONTEXT.labels("yca-logs", "agent")));
        assertEquals("trusted-network", args.get(args.indexOf("--network") + 1));
        assertTrue(args.containsAll(List.of(
                "--read-only",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "/tmp:rw,nosuid,nodev,size=1g",
                "HOME=/attempt/storage",
                "MAVEN_OPTS=-Xmx256m")));
        for (final var name : List.of("configuration.json", "storage", "workspace", "output")) {
            assertTrue(args.stream().anyMatch(arg -> arg.contains("dst=/attempt/" + name)));
        }
        assertFalse(args.contains("credential"));
        assertTrue(Files.readAllLines(Path.of(executable + ".env")).contains("GH_TOKEN=credential"));
        assertEquals("hidden", args.get(args.indexOf("--show-tool-output") + 1));
        assertTrue(Files.exists(directory.resolve("execution.log")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "explicit-null", "bound"})
    void temporaryMountIsExplicitAndDefaultsStayUnchanged(final String mode) throws Exception {
        final var prompt = "B1-private-prompt-canary\n$(not-shell-expanded) ' \"";
        final var directory = attempt();
        assumeNonRoot(directory);
        // Neither a context-shaped identity nor the presence of this directory selects a mount policy.
        final var temporary = Files.createDirectory(
                directory.resolve("arbitrary-name"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final var executable =
                script(("bound".equals(mode) ? "test -f '" + directory.resolve("prompt.txt") + "' || exit 9\n" : "")
                        + "printf '%s\\n' \"$@\" > \"$0.args\"\n");
        final var backend = backend(executable);
        assertEquals(
                0,
                "default".equals(mode)
                        ? backend.execute(directory, "yca-slack-context", prompt, Map.of(), Map.of())
                        : backend.execute(
                                directory,
                                "yca-slack-context",
                                prompt,
                                Map.of(),
                                Map.of(),
                                null,
                                Map.of(),
                                "bound".equals(mode) ? temporary : null));
        final var args = Files.readAllLines(Path.of(executable + ".args"));
        final var bind = "type=bind,src=" + temporary + ",dst=/tmp";
        if ("bound".equals(mode)) {
            assertFalse(String.join("\n", args).contains("B1-private-prompt-canary"));
            assertFalse(args.contains("--prompt"));
            assertEquals("/attempt/prompt.txt", args.get(args.indexOf("--prompt-file") + 1));
            assertTrue(args.contains(
                    "type=bind,src=" + directory.resolve("prompt.txt") + ",dst=/attempt/prompt.txt,readonly"));
            assertEquals(prompt, Files.readString(directory.resolve("prompt.txt")));
            assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(directory.resolve("prompt.txt")));
            assertTrue(args.contains("type=bind,src=" + temporary + ",dst=/dev/shm"));
            assertFalse(args.contains("--tmpfs"));
            assertFalse(args.contains("/tmp:rw,nosuid,nodev,size=1g"));
            assertEquals(1, args.stream().filter(bind::equals).count());
            assertEquals("--mount", args.get(args.indexOf(bind) - 1));
        } else {
            assertTrue(String.join("\n", args).contains(prompt));
            assertTrue(args.contains("--prompt"));
            assertFalse(args.contains("--prompt-file"));
            assertFalse(Files.exists(directory.resolve("prompt.txt")));
            assertEquals(1, args.stream().filter("--tmpfs"::equals).count());
            assertEquals("/tmp:rw,nosuid,nodev,size=1g", args.get(args.indexOf("--tmpfs") + 1));
            assertFalse(args.contains(bind));
        }
        assertTrue(args.containsAll(List.of("--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void contextPromptMustBeFreshBeforeLaunch(final boolean symlink) throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var temporary = Files.createDirectory(
                directory.resolve("temporary"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final var existing = Files.writeString(directory.resolve("existing"), "unchanged");
        if (symlink) {
            Files.createSymbolicLink(directory.resolve("prompt.txt"), existing);
        } else {
            Files.writeString(directory.resolve("prompt.txt"), "unchanged");
        }
        final var executable = script("touch \"$0.called\"\n");
        assertThrows(
                IllegalStateException.class,
                () -> backend(executable)
                        .execute(
                                directory,
                                "yca-private-prompt",
                                "new private body",
                                Map.of(),
                                Map.of(),
                                null,
                                Map.of(),
                                temporary));
        assertFalse(Files.exists(Path.of(executable + ".called")));
        assertEquals("unchanged", Files.readString(existing));
        assertEquals("unchanged", Files.readString(directory.resolve("prompt.txt")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "outside",
                "nested",
                "link",
                "internal-link",
                "ancestor-link",
                "public",
                "special-mode",
                "file",
                "missing",
                "traversal",
                "comma"
            })
    void rejectsUnsafeTemporaryDirectoryBeforeAnyDockerCommand(final String invalid) throws Exception {
        final var directory = attempt();
        final var temporary = Files.createDirectory(
                directory.resolve("temporary"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path supplied = temporary;
        Path attempt = directory;
        switch (invalid) {
            case "outside" ->
                supplied = Files.createDirectory(
                        home.resolve("outside"),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            case "nested" -> supplied = Files.createDirectory(temporary.resolve("nested"));
            case "link" -> {
                Files.delete(temporary);
                Files.createSymbolicLink(temporary, home);
            }
            case "internal-link" -> supplied = Files.createSymbolicLink(directory.resolve("tmp-link"), temporary);
            case "ancestor-link" -> {
                attempt = Files.createSymbolicLink(home.resolve("attempt-link"), directory);
                supplied = attempt.resolve("temporary");
            }
            case "public" -> Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwxr-x---"));
            case "special-mode" -> Files.setAttribute(temporary, "unix:mode", 01700);
            case "file" -> {
                Files.delete(temporary);
                Files.writeString(temporary, "not a directory");
            }
            case "missing" -> Files.delete(temporary);
            case "traversal" -> supplied = temporary.resolve("..").resolve("temporary");
            case "comma" -> supplied = Files.move(temporary, directory.resolve("tmp,unsafe"));
            default -> fail(invalid);
        }
        final var executable = script("touch \"$0.called\"\n");
        final var target = supplied;
        final var attemptPath = attempt;
        // Egress would create resources before agent launch if temporary validation were too late.
        final var backend = new DockerExecutionBackend(
                ExecutionPolicies.CONTEXT,
                executable.toString(),
                "trusted-image",
                Duration.ofSeconds(5),
                "",
                new ExecutorConfiguration.Docker.Egress("gateway", List.of("github.com"), "127.0.0.1"),
                null,
                "shown",
                false);
        assertThrows(
                IllegalArgumentException.class,
                () -> backend.execute(
                        attemptPath, "yca-unsafe-tmp", "review", Map.of(), Map.of(), null, Map.of(), target));
        assertFalse(Files.exists(Path.of(executable + ".called")));
        assertFalse(Files.exists(directory.resolve("execution.log")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"settings", "tls"})
    void temporaryMountCannotExposeServicesSettingsOrTls(final String exposed) throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var temporary = Files.createDirectory(
                directory.resolve("temporary"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final var settings =
                Files.writeString(("settings".equals(exposed) ? temporary : home).resolve("settings.json"), "{}");
        final var tls = Files.writeString(("tls".equals(exposed) ? temporary : home).resolve("tls.p12"), "tls");
        final var executable = script("touch \"$0.called\"\n");
        final var services = new ExecutorConfiguration.Docker.Services(
                "gateway",
                "trusted-control",
                tls.toString(),
                "YCA_SERVICES_KEYSTORE_PASSWORD",
                "attempt-services.yca.internal",
                "http://172.20.0.1:8080/v1",
                null);
        final var backend = new DockerExecutionBackend(
                ExecutionPolicies.CONTEXT,
                executable.toString(),
                "trusted-image",
                Duration.ofSeconds(5),
                "",
                null,
                services,
                "shown",
                false);
        assertThrows(
                IllegalArgumentException.class,
                () -> backend.execute(
                        directory,
                        "yca-private-tmp",
                        "review",
                        Map.of(),
                        Map.of(),
                        settings,
                        Map.of("YCA_SERVICES_KEYSTORE_PASSWORD", "secret"),
                        temporary));
        assertFalse(Files.exists(Path.of(executable + ".called")));
        assertFalse(Files.exists(directory.resolve("execution.log")));
    }

    private Path attempt() throws Exception {
        final var directory = Files.createTempDirectory(home, "attempt-");
        Files.writeString(directory.resolve("configuration.json"), "{}");
        for (final var name : List.of("storage", "workspace", "output")) {
            Files.createDirectory(directory.resolve(name));
        }
        return directory;
    }

    private void assumeNonRoot(final Path directory) throws Exception {
        assumeTrue(
                ((Number) Files.getAttribute(directory, "unix:uid")).intValue() != 0,
                "The production executor deliberately rejects root-owned attempts");
    }

    private Path script(final String body) throws Exception {
        final var executable = Files.createTempDirectory(home, "fake-docker-").resolve("docker.sh");
        TestScript.write(executable, """
        #!/bin/sh
        for last; do :; done
        if [ "$2" = inspect ]; then
          case "$last" in
                *-services|*-egress) printf 'Error: No such container: %s\\n' "$last"; exit 1 ;;
                *-services-net|*-external) printf 'Error: No such network: %s\\n' "$last"; exit 1 ;;
          esac
        fi
        """ + body);
        return executable;
    }

    private DockerExecutionBackend backend(final Path executable) {
        return new DockerExecutionBackend(
                ExecutionPolicies.CONTEXT,
                executable.toString(),
                "trusted-image",
                Duration.ofSeconds(5),
                "",
                null,
                null,
                "shown",
                false);
    }

    private String agentInspection(final String runtime) {
        return "container inspect --format " + ExecutionPolicy.inspectFormat(false) + "|{{.State.Running}} " + runtime;
    }

    @Test
    void explicitPolicyLabelsAgentsAndRejectsForeignOwnersRegardlessOfRuntimePrefix() throws Exception {
        for (final var policy : List.of(ExecutionPolicies.CONTEXT, ExecutionPolicies.PROVIDER)) {
            for (final var runtime : List.of("yca-arbitrary-42", "yca-slack-misleading", "yca-github-misleading")) {
                final var directory = attempt();
                assumeNonRoot(directory);
                final var executable = script("printf '%s\\n' \"$@\" > \"$0.args\"\n");
                final var backend = new DockerExecutionBackend(
                        policy,
                        executable.toString(),
                        "trusted-image",
                        Duration.ofSeconds(5),
                        "",
                        null,
                        null,
                        "shown",
                        false);
                assertEquals(0, backend.execute(directory, runtime, "review", Map.of(), Map.of()));
                final var args = Files.readAllLines(Path.of(executable + ".args"));
                assertTrue(args.containsAll(policy.labels(runtime, "agent")));
                for (final var state : List.of("true", "false")) {
                    final var other = policy == ExecutionPolicies.CONTEXT
                            ? ExecutionPolicies.PROVIDER
                            : ExecutionPolicies.CONTEXT;
                    final var collision = script("printf '%s\\n' \"$*\" >> \"$0.calls\"\n" + "printf '"
                            + other.ownership(runtime, "agent") + "|" + state + "\\n'\n");
                    final var foreign = new DockerExecutionBackend(
                            policy,
                            collision.toString(),
                            "trusted-image",
                            Duration.ofSeconds(5),
                            "",
                            null,
                            null,
                            "shown",
                            false);
                    assertThrows(IllegalStateException.class, () -> foreign.stop(runtime));
                    assertEquals(List.of(agentInspection(runtime)), Files.readAllLines(Path.of(collision + ".calls")));
                }
            }
        }
    }
}
