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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.runtime.RuntimeJsonMapper;
import io.yupiik.ia.agent.bot.runtime.TestScript;
import io.yupiik.ia.agent.testing.UnlinkingTempDirDeletionStrategy;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class NonoExecutionBackendTest {
    @TempDir(deletionStrategy = UnlinkingTempDirDeletionStrategy.class)
    Path home;

    @Test
    void grantsOnlyBoundPrivateProviderSocketAndKeepsNetworkBlocked() throws Exception {
        final var attempt = attempt();
        nonRoot(attempt);
        final var privateDirectory = Files.createDirectory(
                home.resolve("provider"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final var socket = privateDirectory.resolve("p");
        final var executable = script("""
                if [ "$1" = --version ]; then printf 'nono 0.81.0'; exit 0; fi
                if [ "$1" = run ]; then printf '%s\n' "$@" > "$0.args"; exit 0; fi
                printf '[{"session_id":"0123456789abcdef","status":"exited","exit_code":0}]'
                """);
        try (final var json = json();
                final var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            final var backend = backend(json, executable, Duration.ofSeconds(1));
            assertEquals(0, backend.execute(attempt, "yca-native", "question", Map.of(), Map.of(), socket));
            final var state = home.resolve("state/yca-native");
            final var profile = map(json.fromString(Object.class, Files.readString(state.resolve("profile.json"))));
            final var filesystem = map(profile.get("filesystem"));
            assertEquals(List.of(socket.toString()), filesystem.get("unix_socket"));
            assertFalse(filesystem.toString().contains(privateDirectory + "]"));
            assertEquals(List.of(home.resolve("runtime").toString()), filesystem.get("read"));
            assertEquals(
                    socket.toString(),
                    map(map(profile.get("environment")).get("set_vars")).get("YCA_NATIVE_PROVIDER_SOCKET"));
            assertEquals(socket.toString(), Files.readString(state.resolve("provider-socket")));
            assertTrue(Files.readAllLines(Path.of(executable + ".args")).contains("--block-net"));
            assertEquals("pathname", map(profile.get("linux")).get("af_unix_mediation"));
            assertEquals(false, map(profile.get("security")).get("capability_elevation"));
        } finally {
            Files.deleteIfExists(socket);
        }
    }

    @Test
    void rejectsUnqualifiedNonoVersionBeforeWritingLaunchIntent() throws Exception {
        final var attempt = attempt();
        nonRoot(attempt);
        final var privateDirectory = Files.createDirectory(
                home.resolve("provider"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final var socket = privateDirectory.resolve("p");
        final var executable = script("printf 'nono 0.80.0'\n");
        try (final var json = json();
                final var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            final var backend = backend(json, executable, Duration.ofSeconds(1));
            assertThrows(
                    IllegalStateException.class,
                    () -> backend.execute(attempt, "yca-native", "question", Map.of(), Map.of(), socket));
            assertFalse(Files.exists(home.resolve("state/yca-native")));
            assertDoesNotThrow(() -> backend.stopProviderOnly("yca-native"));
            assertDoesNotThrow(() -> backend.cleanupProviderOnly("yca-native"));
            final var collision = Files.createDirectory(home.resolve("state/yca-collision"));
            final var retained = Files.writeString(collision.resolve("retain"), "existing runtime state");
            assertThrows(
                    IllegalStateException.class,
                    () -> backend.execute(attempt, "yca-collision", "question", Map.of(), Map.of(), socket));
            assertDoesNotThrow(() -> backend.cleanupProviderOnly("yca-collision"));
            assertTrue(Files.exists(retained), "a pre-existing runtime must never become no-launch cleanup proof");
        } finally {
            Files.deleteIfExists(socket);
        }
    }

    @Test
    void rejectsProtectedAndNullToolEnvironmentBeforeStagingOrLaunch() throws Exception {
        final var attempt = attempt();
        final var executable = script("/usr/bin/touch \"$0.called\"\n");
        try (final var json = json()) {
            final var backend = backend(json, executable, Duration.ofSeconds(1));
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
                assertThrows(
                        IllegalArgumentException.class,
                        () -> backend.execute(attempt, "yca-task", "review", Map.of(), Map.of(key, "must-not-forward")),
                        key);
            }
            final var environment = new HashMap<String, String>();
            environment.put("MAVEN_OPTS", null);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.execute(attempt, "yca-task", "review", Map.of(), environment));
            assertFalse(Files.exists(home.resolve("state/yca-task")));
            assertFalse(Files.exists(Path.of(executable + ".called")));
            assertFalse(Files.exists(attempt.resolve("execution.log")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"shown", "collapsed", "hidden"})
    void stagesRestrictedProfileClearsEnvironmentAndUsesArgumentList(final String showToolOutput) throws Exception {
        final var attempt = attempt();
        nonRoot(attempt);
        final var executable = script("""
            if [ "$1" = run ]; then
              printf '%s\n' "$@" > "$0.args"
              /usr/bin/env > "$0.env"
              printf 'executor output\n'
              exit 7
            fi
            printf '[{"session_id":"0123456789abcdef","status":"exited","exit_code":7}]'
            """);
        try (final var json = json()) {
            final var backend = backend(json, executable, Duration.ofSeconds(3), showToolOutput);
            final var toolEnvironment = Map.of(
                    "MAVEN_OPTS", "-Dmaven.repo.local=" + attempt.resolve("storage/cache/maven"),
                    "npm_config_cache", attempt.resolve("storage/cache/npm").toString(),
                    "NPM_CONFIG_USERCONFIG",
                            attempt.resolve("storage/cache/npmrc").toString(),
                    "GRADLE_USER_HOME", attempt.resolve("storage/cache/gradle").toString(),
                    "PIP_CACHE_DIR", attempt.resolve("storage/cache/pip").toString(),
                    "GOMODCACHE", attempt.resolve("storage/cache/go").toString(),
                    "CARGO_HOME", attempt.resolve("storage/cache/cargo").toString(),
                    "NUGET_PACKAGES", attempt.resolve("storage/cache/nuget").toString());
            assertEquals(
                    7,
                    backend.execute(
                            attempt,
                            "yca-task",
                            "Review /attempt/output/result.json; $(never)",
                            Map.of(
                                    "GH_TOKEN", "agent-secret",
                                    "GITHUB_BOT_FORK_TOKEN", "fork-secret",
                                    "GITHUB_SOURCE_TOKEN", "source-secret",
                                    "YCA_CONTEXT_TOKEN", "context-secret",
                                    "YCA_CONTEXT_GIT_TOKEN", "context-git-secret"),
                            toolEnvironment));
            final var args = Files.readAllLines(Path.of(executable + ".args"));
            final var agentRun = args.indexOf(home.resolve("runtime/agent").toString()) + 1;
            assertTrue(agentRun > 0);
            assertEquals(List.of("run", "--show-tool-output", showToolOutput), args.subList(agentRun, agentRun + 3));
            assertEquals(1, args.stream().filter("--show-tool-output"::equals).count());
            assertEquals("run", args.getFirst());
            assertTrue(args.contains("--profile"));
            assertTrue(args.contains("--no-audit"));
            assertFalse(args.contains("--allow-cwd"));
            assertTrue(args.contains("Review " + attempt + "/output/result.json; $(never)"));
            final var state = home.resolve("state/yca-task");
            final var profile = map(json.fromString(Object.class, Files.readString(state.resolve("profile.json"))));
            final var environment = map(profile.get("environment"));
            final var explicit = map(environment.get("set_vars"));
            toolEnvironment.forEach((key, value) -> assertEquals(value, explicit.get(key)));
            assertEquals(attempt.resolve("storage").toString(), explicit.get("HOME"));
            assertEquals(attempt.resolve("storage/gh").toString(), explicit.get("GH_CONFIG_DIR"));
            assertEquals(attempt.resolve("storage/tmp").toString(), explicit.get("TMPDIR"));
            assertEquals(toolEnvironment.size() + 3, explicit.size());
            assertTrue(((List<?>) environment.get("allow_vars")).stream().noneMatch(toolEnvironment::containsKey));
            assertTrue(((List<?>) environment.get("allow_vars"))
                    .containsAll(List.of(
                            "GH_TOKEN",
                            "GITHUB_BOT_FORK_TOKEN",
                            "GITHUB_SOURCE_TOKEN",
                            "YCA_CONTEXT_TOKEN",
                            "YCA_CONTEXT_GIT_TOKEN")));
            final var inherited = Files.readAllLines(Path.of(executable + ".env"));
            assertTrue(inherited.containsAll(List.of(
                    "GH_TOKEN=agent-secret",
                    "GITHUB_BOT_FORK_TOKEN=fork-secret",
                    "GITHUB_SOURCE_TOKEN=source-secret",
                    "YCA_CONTEXT_TOKEN=context-secret",
                    "YCA_CONTEXT_GIT_TOKEN=context-git-secret")));
            for (final var secret :
                    List.of("agent-secret", "fork-secret", "source-secret", "context-secret", "context-git-secret")) {
                assertFalse(args.toString().contains(secret));
                assertFalse(Files.readString(state.resolve("profile.json")).contains(secret));
                assertFalse(
                        Files.readString(state.resolve("configuration.json")).contains(secret));
                assertFalse(Files.readString(attempt.resolve("execution.log")).contains(secret));
            }
            final var fs = map(profile.get("filesystem"));
            assertTrue(((List<?>) map(profile.get("groups")).get("exclude"))
                    .containsAll(List.of("system_write_linux", "system_read_linux_core", "user_tools")));
            assertEquals(List.of(home.resolve("runtime").toString()), fs.get("read"));
            assertEquals(
                    List.of(
                            attempt.resolve("storage").toString(),
                            attempt.resolve("workspace").toString(),
                            attempt.resolve("output").toString()),
                    fs.get("allow"));
            assertEquals("pathname", map(profile.get("linux")).get("af_unix_mediation"));
            assertEquals(false, map(profile.get("security")).get("capability_elevation"));
            final var staged = Files.readString(state.resolve("configuration.json"));
            assertTrue(staged.contains(attempt + "/workspace"));
            assertFalse(staged.contains("/attempt/"));
            assertEquals(
                    Map.of("result-correction-prompt", "Repair {{{file}}}: {{{validationError}}}"),
                    map(json.fromString(Object.class, staged)).get("run"));
            assertFalse(staged.contains("must-not-forward"));
            assertTrue(Files.readString(attempt.resolve("configuration.json")).contains("/attempt/workspace"));
            for (final var line : inherited) {
                assertTrue(
                        List.of(
                                        "HOME",
                                        "XDG_CONFIG_HOME",
                                        "XDG_STATE_HOME",
                                        "PATH",
                                        "GH_TOKEN",
                                        "GITHUB_BOT_FORK_TOKEN",
                                        "GITHUB_SOURCE_TOKEN",
                                        "YCA_CONTEXT_TOKEN",
                                        "YCA_CONTEXT_GIT_TOKEN",
                                        "PWD")
                                .contains(line.substring(0, line.indexOf('='))),
                        line);
            }
            assertEquals("executor output\n", Files.readString(attempt.resolve("execution.log")));
            assertTrue(Files.exists(state.resolve("intent")));
            assertThrows(
                    IllegalStateException.class,
                    () -> backend.execute(attempt, "yca-task", "again", Map.of(), Map.of()));
        }
    }

    @Test
    void removesPrivateStateOnlyAfterConfirmedExitAndIsRestartIdempotent() throws Exception {
        final var state = Files.createDirectories(home.resolve("state/yca-task"));
        Files.writeString(state.resolve("configuration.json"), "sensitive-provider-key");
        final var executable =
                script("printf '[{\"session_id\":\"0123456789abcdef\",\"status\":\"exited\",\"exit_code\":0}]'\n");
        try (final var json = json()) {
            final var backend = backend(json, executable, Duration.ofSeconds(3));
            backend.cleanupStoppedState("yca-task");
            assertFalse(Files.exists(state));
            backend.cleanupStoppedState("yca-task");
            Files.createDirectory(state);
            Files.writeString(state.resolve("configuration.json"), "preserve-evidence");
            TestScript.write(
                    executable,
                    "#!/bin/sh\n"
                            + "printf '[{\"session_id\":\"0123456789abcdef\",\"status\":\"exited\",\"exit_code\":-1}]'\n");
            assertThrows(IllegalStateException.class, () -> backend.cleanupStoppedState("yca-task"));
            assertEquals("preserve-evidence", Files.readString(state.resolve("configuration.json")));
        }
    }

    @Test
    void recoveryUsesNonoRegistryAndVerifiesTermination() throws Exception {
        Files.createDirectories(home.resolve("state/yca-task"));
        final var executable = script("""
            printf '%s\n' "$*" >> "$0.calls"
            if [ "$1" = stop ]; then
              /usr/bin/touch "$0.stopped"
            elif [ -f "$0.stopped" ]; then
              printf '[{"session_id":"0123456789abcdef","status":"exited","exit_code":143}]'
            else
              printf '[{"session_id":"0123456789abcdef","status":"running","exit_code":null}]'
            fi
            """);
        try (final var json = json()) {
            backend(json, executable, Duration.ofSeconds(3)).stop("yca-task");
            assertEquals(
                    List.of("ps --all --json", "stop 0123456789abcdef --timeout 1", "ps --all --json"),
                    Files.readAllLines(Path.of(executable + ".calls")));
        }
    }

    @Test
    void missingAmbiguousAndReconciledStateNeverAuthorizesCleanup() throws Exception {
        Files.createDirectories(home.resolve("state/yca-task"));
        for (final var response : List.of("[]", "[{},{ }]", "[{\"status\":\"exited\",\"exit_code\":-1}]", "not json")) {
            final var executable = script("printf '%s' '" + response + "'\n");
            try (final var json = json()) {
                assertThrows(
                        RuntimeException.class,
                        () -> backend(json, executable, Duration.ofSeconds(1)).stop("yca-task"));
                assertThrows(
                        RuntimeException.class,
                        () -> backend(json, executable, Duration.ofSeconds(1)).stop("yca-missing"));
            }
        }
    }

    @Test
    void boundsOutputAndFailsClosedOnTimeoutWithoutRegistry() throws Exception {
        final var attempt = attempt();
        nonRoot(attempt);
        final var executable = script("""
            if [ "$1" = run ]; then
              /usr/bin/head -c 9000000 /dev/zero
              exec /bin/sleep 10
            fi
            printf '[]'
            """);
        try (final var json = json()) {
            final var backend = backend(json, executable, Duration.ofMillis(500));
            assertTimeoutPreemptively(
                    Duration.ofSeconds(4),
                    () -> assertThrows(
                            IllegalStateException.class,
                            () -> backend.execute(attempt, "yca-task", "review", Map.of(), Map.of())));
            assertEquals(8L * 1024 * 1024, Files.size(attempt.resolve("execution.log")));
            assertThrows(IllegalStateException.class, () -> backend.stop("yca-task"));
        }
    }

    @Test
    void timeoutAndInterruptionCloseOutputPipeBeforeReturningLogOwnership() throws Exception {
        final var attempt = attempt();
        nonRoot(attempt);
        assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")), "Pipe assertions require Linux procfs");
        for (final boolean interrupt : List.of(false, true)) {
            final var executable = script("""
                if [ "$1" = run ]; then
                  /bin/sleep 60 &
                  printf '%s' "$!" > "$0.child"
                  printf '%s' "$$" > "$0.pid"
                  wait
                fi
                printf '[]'
                """);
            try (final var json = json()) {
                final var backend = backend(json, executable, Duration.ofSeconds(interrupt ? 10 : 1));
                final var result = new CompletableFuture<Boolean>();
                final var worker = Thread.ofPlatform().start(() -> {
                    try {
                        assertThrows(
                                IllegalStateException.class,
                                () -> backend.execute(attempt, "yca-pipe-" + interrupt, "review", Map.of(), Map.of()));
                        result.complete(Thread.currentThread().isInterrupted());
                    } catch (final Throwable e) {
                        result.completeExceptionally(e);
                    }
                });
                ProcessHandle child = null;
                ProcessHandle client = null;
                try {
                    final var pidFile = Path.of(executable + ".pid");
                    assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                        while (!Files.exists(pidFile) || Files.size(pidFile) == 0) {
                            Thread.sleep(10);
                        }
                    });
                    client = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
                            .orElseThrow();
                    child = ProcessHandle.of(Long.parseLong(Files.readString(Path.of(executable + ".child"))))
                            .orElseThrow();
                    final var pipe = Files.readSymbolicLink(Path.of("/proc/" + child.pid() + "/fd/1"));
                    if (interrupt) {
                        worker.interrupt();
                    }
                    assertEquals(interrupt, result.get(4, TimeUnit.SECONDS).booleanValue());
                    assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                        while (hasOpenPipe(pipe)) {
                            Thread.sleep(10);
                        }
                    });
                    assertTrue(child.isAlive(), "An inherited writer must not keep the local reader alive");
                    assertTrue(Files.exists(home.resolve("state/yca-pipe-" + interrupt + "/intent")));
                } finally {
                    worker.interrupt();
                    if (client != null) {
                        client.destroyForcibly();
                    }
                    if (child != null) {
                        child.destroyForcibly();
                    }
                    worker.join(3000);
                    Files.deleteIfExists(attempt.resolve("execution.log"));
                    Files.deleteIfExists(attempt.resolve("storage/tmp"));
                }
            }
        }
    }

    private boolean hasOpenPipe(final Path pipe) throws Exception {
        try (final var descriptors = Files.list(Path.of("/proc/self/fd"))) {
            for (final var descriptor : descriptors.toList()) {
                try {
                    if (pipe.equals(Files.readSymbolicLink(descriptor))) {
                        return true;
                    }
                } catch (final NoSuchFileException ignored) {
                    // Descriptors can close while enumerating them.
                }
            }
        }
        return false;
    }

    @Test
    void rejectsBadCredentialsIdentityAndSymbolicRuntime() throws Exception {
        final var attempt = attempt();
        final var executable = script("/usr/bin/touch \"$0.called\"\n");
        try (final var json = json()) {
            final var backend = backend(json, executable, Duration.ofSeconds(1));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.execute(attempt, "yca-task", "review", Map.of("HOST_TOKEN", "secret"), Map.of()));
            assertThrows(IllegalArgumentException.class, () -> backend.stop("yca-" + "a".repeat(10000)));
            assertThrows(IllegalArgumentException.class, () -> backend.stop("--all"));
            final var link = home.resolve("link");
            Files.createSymbolicLink(link, home.resolve("runtime"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new NonoExecutionBackend(
                            json,
                            executable.toString(),
                            link.resolve("agent"),
                            link,
                            home.resolve("state"),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1),
                            "shown"));
            assertFalse(Files.exists(Path.of(executable + ".called")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "late", "default", "123"})
    void snapshotsFeedbackAndGrantsOnlyNativeMailboxWriteAccess(final String preparation) throws Exception {
        final var attempt = attempt();
        nonRoot(attempt);
        final boolean enabled = !List.of("absent", "late").contains(preparation);
        if (enabled) {
            Files.createDirectory(
                    attempt.resolve("feedback"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        if ("123".equals(preparation)) {
            Files.writeString(attempt.resolve(".feedback-timeout"), "123\n");
            Files.setPosixFilePermissions(
                    attempt.resolve(".feedback-timeout"), PosixFilePermissions.fromString("rw-------"));
        }
        final var executable = script("if [ \"$1\" = run ]; then\n"
                + ("late".equals(preparation) ? "/bin/mkdir -m 700 '" + attempt.resolve("feedback") + "'\n" : "")
                + "printf '%s\\n' \"$@\" > \"$0.args\"\nexit 0\nfi\n"
                + "printf '[{\"session_id\":\"0123456789abcdef\",\"status\":\"exited\",\"exit_code\":0}]'\n");
        try (final var json = json()) {
            assertEquals(
                    0,
                    backend(json, executable, Duration.ofSeconds(3))
                            .execute(attempt, "yca-task", "review", Map.of(), Map.of()));
            final var args = Files.readAllLines(Path.of(executable + ".args"));
            final var profile =
                    map(json.fromString(Object.class, Files.readString(home.resolve("state/yca-task/profile.json"))));
            final var writable = (List<?>) map(profile.get("filesystem")).get("allow");
            assertEquals(enabled, args.contains("--yca-bot-feedback-enabled"));
            assertEquals(enabled, writable.contains(attempt.resolve("feedback").toString()));
            assertEquals(enabled ? 4 : 3, writable.size());
            assertFalse(writable.contains(attempt.toString()));
            assertFalse(writable.contains(attempt.resolve(".feedback-timeout").toString()));
            if (enabled) {
                assertEquals(
                        List.of(
                                "--yca-bot-feedback-enabled",
                                "true",
                                "--yca-bot-feedback-timeoutSeconds",
                                "123".equals(preparation) ? "123" : "600",
                                "--yca-bot-feedback-directory",
                                attempt.resolve("feedback").toString()),
                        args.subList(args.size() - 6, args.size()));
            }
            assertEquals("false", args.get(args.indexOf("--yca-configuration-project-enabled") + 1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "link", "0", "3601", "1x", "999999999999999999999999"})
    void rejectsUnsafePreparedFeedbackBeforeStaging(final String invalid) throws Exception {
        final var attempt = attempt();
        final var feedback = Files.createDirectory(
                attempt.resolve("feedback"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if ("public".equals(invalid)) {
            Files.setPosixFilePermissions(feedback, PosixFilePermissions.fromString("rwxr-xr-x"));
        } else if ("link".equals(invalid)) {
            Files.delete(feedback);
            Files.createSymbolicLink(feedback, home);
        } else {
            Files.writeString(attempt.resolve(".feedback-timeout"), invalid);
            Files.setPosixFilePermissions(
                    attempt.resolve(".feedback-timeout"), PosixFilePermissions.fromString("rw-------"));
        }
        final var executable = script("/usr/bin/touch \"$0.called\"\n");
        try (final var json = json()) {
            final var backend = backend(json, executable, Duration.ofSeconds(3));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.execute(attempt, "yca-task", "review", Map.of(), Map.of()));
            assertFalse(Files.exists(home.resolve("state/yca-task")));
            assertFalse(Files.exists(Path.of(executable + ".called")));
        }
    }

    private Path attempt() throws Exception {
        final var attempt = Files.createDirectory(home.resolve("attempt with spaces"));
        Files.writeString(
                attempt.resolve("configuration.json"),
                "{\"yca\":{\"workingDir\":\"/attempt/workspace\"},\"run\":{"
                        + "\"result-correction-prompt\":\"Repair {{{file}}}: {{{validationError}}}\","
                        + "\"prompt\":\"must-not-forward\",\"result-file\":\"/host/forbidden\"}}");
        for (final var name : List.of("storage", "workspace", "output")) {
            Files.createDirectory(attempt.resolve(name));
        }
        return attempt;
    }

    private void nonRoot(final Path path) throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux"));
        assumeTrue(
                ((Number) Files.getAttribute(path, "unix:uid")).longValue() != 0,
                "Execution deliberately rejects root-owned attempts");
    }

    private NonoExecutionBackend backend(final JsonMapper json, final Path executable, final Duration timeout)
            throws Exception {
        return backend(json, executable, timeout, "shown");
    }

    private NonoExecutionBackend backend(
            final JsonMapper json, final Path executable, final Duration timeout, final String showToolOutput)
            throws Exception {
        Files.createDirectories(home.resolve("runtime/bin"));
        Files.writeString(home.resolve("runtime/agent"), "trusted agent");
        Files.createDirectories(home.resolve("state"));
        Files.setPosixFilePermissions(home.resolve("state"), PosixFilePermissions.fromString("rwx------"));
        return new NonoExecutionBackend(
                json,
                executable.toString(),
                home.resolve("runtime/agent"),
                home.resolve("runtime"),
                home.resolve("state"),
                timeout,
                Duration.ofSeconds(3),
                showToolOutput);
    }

    private Path script(final String body) throws Exception {
        return TestScript.write(Files.createTempDirectory(home, "nono-").resolve("nono.sh"), "#!/bin/sh\n" + body);
    }

    private JsonMapper json() {
        return RuntimeJsonMapper.create(key -> Optional.empty());
    }

    private static Map<?, ?> map(final Object value) {
        return (Map<?, ?>) value;
    }
}
