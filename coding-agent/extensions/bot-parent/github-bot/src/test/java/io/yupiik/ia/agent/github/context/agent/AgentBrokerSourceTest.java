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
package io.yupiik.ia.agent.github.context.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.github.support.TestJava;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentBrokerSourceTest {
    private static final Pattern REJECTED_GIT_OPERATION =
            Pattern.compile("Broker source operation failed: stage=git_[a-z_]+ code=REJECTED"
                    + " processors=[0-9]+ commonPoolParallelism=[0-9]+\\n");

    @TempDir
    static Path seedTemporary;

    private static String seedHead;

    @BeforeAll
    static void buildSeed() throws Exception {
        seedHead = buildFixture(seedTemporary);
    }

    @Test
    void deadlineAndInheritedPipesDoNotLeaveGitProcessesOrAnUnboundedExecutorClose() throws Exception {
        for (final var mode : List.of("stall", "inherited-pipe")) {
            final var workspace = Files.createDirectory(temporary.resolve(mode));
            final var flags = fixtureFlags(workspace, mode);
            final var started = System.nanoTime();
            final var result = launch(workspace, flags, List.of("init"));
            assertNotEquals(0, result.exit());
            assertTrue(result.error().contains("stage=git_init code=DEADLINE_EXCEEDED"), result.error());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
            assertDead(workspace.resolve("git.pid"));
            assertDead(workspace.resolve("child.pid"));
        }
    }

    @Test
    void transientGitFilesDoNotRejectAnOtherwiseClassifiedOperation() throws Exception {
        final var workspace = Files.createDirectory(temporary.resolve("churn"));
        final var flags = fixtureFlags(workspace, "churn");
        flags.set(flags.indexOf("--timeout") + 1, "10");
        final var result = launch(workspace, flags, List.of("init"));
        assertNotEquals(0, result.exit());
        assertTrue(result.error().contains("stage=git_init code=GIT_EXIT"), result.error());
        assertFalse(result.error().contains(workspace.toString()), result.error());
        assertFalse(result.error().contains(ENDPOINT), result.error());
    }

    @Test
    void failuresHaveFixedClassificationsWithoutGitOutputArgumentsOrCredentials() throws Exception {
        for (final var mode : List.of("exit", "output-limit")) {
            final var workspace = Files.createDirectory(temporary.resolve(mode));
            final var result = launch(workspace, fixtureFlags(workspace, mode), List.of("init"));
            assertNotEquals(0, result.exit());
            assertEquals("", result.output());
            assertTrue(
                    result.error()
                            .contains("stage=git_init code=" + (mode.equals("exit") ? "GIT_EXIT" : "OUTPUT_LIMIT")),
                    result.error());
            assertFalse(result.error().contains("fixture-private-value"));
            assertFalse(result.error().contains(workspace.toString()));
            assertFalse(result.error().contains(ENDPOINT));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stall", "delayed-stall"})
    void interruptionKillsAndReapsGitWhilePreservingTheInterruptFlag(final String mode) throws Exception {
        final var workspace = Files.createDirectory(temporary.resolve("interrupted"));
        final var command = TestJava.javaCommand("-cp", classpath(), InterruptLauncher.class.getName());
        final var flags = fixtureFlags(workspace, mode);
        // Cancellation, not the operation deadline, must end this scenario, including slow startup.
        flags.set(flags.indexOf("--timeout") + 1, "15");
        command.addAll(flags);
        command.add("init");
        final var result = process(workspace, command, "", Map.of());
        assertEquals(0, result.exit(), result.error());
        assertEquals("CANCELLED interrupted=true\n", result.output());
        assertDead(workspace.resolve("git.pid"));
        assertDead(workspace.resolve("child.pid"));
    }

    private List<String> fixtureFlags(final Path workspace, final String mode) throws Exception {
        final var executable = workspace.resolve("git-fixture");
        if (mode.equals("stall") || mode.equals("inherited-pipe")) {
            // These one/two-second deadlines test pipe supervision, not JVM startup.
            // Publish PIDs with shell builtins before blocking, without two nested JVMs
            // that can be killed between creating and writing their PID files on busy CI.
            Files.writeString(
                    executable,
                    "#!/bin/sh\nprintf %s $$ > "
                            + shell(workspace.resolve("git.pid").toString())
                            + "\nsleep 30 &\nprintf %s $! > "
                            + shell(workspace.resolve("child.pid").toString())
                            + "\n: > " + shell(workspace.resolve("child.ready").toString())
                            + (mode.equals("inherited-pipe") ? "\nsleep 0.4\nexit 0\n" : "\nwait\n"));
        } else {
            Files.writeString(
                    executable,
                    "#!/bin/sh\nexec "
                            + TestJava.javaCommand("-cp", classpath(), GitProcessFixture.class.getName()).stream()
                                    .map(AgentBrokerSourceTest::shell)
                                    .collect(Collectors.joining(" "))
                            + " " + mode + " " + shell(workspace.toString()) + " \"$@\"\n");
        }
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        final var flags = new ArrayList<>(flags("a".repeat(40)));
        flags.set(flags.indexOf("--timeout") + 1, mode.equals("stall") ? "2" : "1");
        flags.addAll(List.of("--git", executable.toString()));
        return flags;
    }

    private static String shell(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void assertDead(final Path pidFile) throws Exception {
        final long pid = Long.parseLong(Files.readString(pidFile));
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Process survived: " + pid);
    }

    public static final class GitProcessFixture {
        public static void main(final String... args) throws Exception {
            final var mode = args[0];
            final var workspace = Path.of(args[1]);
            if (mode.equals("child") || mode.equals("delayed-child")) {
                if (mode.equals("delayed-child")) {
                    // Reproduce startup exceeding the old one-second readiness assumption.
                    Thread.sleep(1200);
                }
                Files.writeString(
                        workspace.resolve("child.pid"),
                        Long.toString(ProcessHandle.current().pid()));
                Files.createFile(workspace.resolve("child.ready"));
                Thread.sleep(30_000);
                return;
            }
            Files.writeString(
                    workspace.resolve("git.pid"),
                    Long.toString(ProcessHandle.current().pid()));
            if (mode.equals("churn")) {
                // Git creates and removes lock files and temporary object directories during fetch.
                final var root = Path.of("objects");
                Files.createDirectory(root);
                final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (System.nanoTime() < until) {
                    final var directory = Files.createTempDirectory(root, "incoming-");
                    final var lock = Files.createFile(directory.resolve("pack.lock"));
                    Files.delete(lock);
                    Files.delete(directory);
                }
                System.exit(7);
            }
            if (mode.equals("exit")) {
                System.out.println("fixture-private-value");
                System.err.println("fixture-private-value");
                System.exit(7);
            }
            if (mode.equals("output-limit")) {
                System.out.print("fixture-private-value".repeat(30_000));
                return;
            }
            final var child = new ProcessBuilder(TestJava.javaCommand(
                            "-cp",
                            classpath(),
                            GitProcessFixture.class.getName(),
                            mode.equals("delayed-stall") ? "delayed-child" : "child",
                            workspace.toString()))
                    .inheritIO()
                    .start();
            if (mode.equals("inherited-pipe")) {
                // Parent lives long enough for the supervisor to record its pipe-holding child.
                Thread.sleep(400);
                return;
            }
            child.waitFor();
        }
    }

    public static final class InterruptLauncher {
        public static void main(final String... args) throws Exception {
            final var type = Class.forName(AgentBrokerSource.class.getName() + "$Options");
            final var parse = type.getDeclaredMethod("parse", String[].class);
            parse.setAccessible(true);
            final var options = parse.invoke(null, (Object) args);
            final var constructor =
                    AgentBrokerSource.class.getDeclaredConstructor(type, JsonMapper.class, Configuration.class);
            constructor.setAccessible(true);
            final var run = AgentBrokerSource.class.getDeclaredMethod("run");
            run.setAccessible(true);
            final var result = new AtomicReference<String>();
            try (final var json = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
                final var source = constructor.newInstance(options, json, Configuration.of(Map.of()));
                final var thread = new Thread(() -> {
                    try {
                        run.invoke(source);
                        result.set("unexpected-success");
                    } catch (final Exception failure) {
                        result.set(failure.getCause().getMessage() + " interrupted="
                                + Thread.currentThread().isInterrupted());
                    }
                });
                thread.start();
                try {
                    final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    while (!Files.exists(Path.of("child.ready")) && thread.isAlive() && System.nanoTime() < until) {
                        Thread.sleep(10);
                    }
                    if (!Files.exists(Path.of("child.ready"))) {
                        throw new IllegalStateException("Child did not become ready; worker result=" + result.get());
                    }
                } finally {
                    thread.interrupt();
                    thread.join(4000);
                    if (thread.isAlive()) {
                        throw new IllegalStateException("Cancellation did not finish");
                    }
                }
                System.out.println(result.get());
            }
        }
    }

    @Test
    void refusesCommonDirectoriesAndHttpAlternatesBeforeAnySourceExpansion() throws Exception {
        for (final var name : List.of("commondir", "gitdir", "objects/info/http-alternates")) {
            final var workspace = temporary.resolve(name.replace('/', '-'));
            final var head = fixture(workspace);
            final var metadata = workspace.resolve("repository/.git").resolve(name);
            Files.createDirectories(metadata.getParent());
            Files.writeString(metadata, temporary.toString());
            assertNotEquals(0, cli(workspace, head, "expand", "docs").exit());
            assertFalse(Files.exists(workspace.resolve("repository/docs/guide.txt")));
            assertEquals(temporary.toString(), Files.readString(metadata));
        }
    }

    private static final String ENDPOINT =
            "https://broker.example:8443/context/12345678-1234-1234-1234-123456789abc/rpc";
    private static final String GIT_PATH = "context/12345678-1234-1234-1234-123456789abc/git/grant-1.git";

    @TempDir
    Path temporary;

    @Test
    void actualGitCredentialFillUsesQuotedPackagedHelperAndExactAttemptGeneration() throws Exception {
        final var workspace = temporary.resolve("workspace with 'quotes'");
        final var head = fixture(workspace);
        final var helperClasspath =
                Files.createDirectory(temporary.resolve("classpath with 'quotes'")) + File.pathSeparator + classpath();
        final var generated = process(
                workspace,
                TestJava.javaCommand(
                        "-Dyca.test.parentOnly=private-runtime-value",
                        "-XX:ActiveProcessorCount=2",
                        "-Xmx64m",
                        "-cp",
                        helperClasspath,
                        HelperLauncher.class.getName(),
                        head),
                "",
                Map.of());
        assertEquals(0, generated.exit(), generated.error());
        final var helper = generated.output();
        for (final var runtimeOption :
                List.of("TieredStopAtLevel", "ActiveProcessorCount", "-Xmx", "yca.test.", "private-runtime-value")) {
            assertFalse(helper.contains(runtimeOption), helper);
        }
        final var command = List.of(
                "/usr/bin/git",
                "-c",
                "credential.helper=",
                "-c",
                "credential.helper=" + helper,
                "-c",
                "credential.useHttpPath=" + Boolean.toString(1 < 2),
                "credential",
                "fill");
        final var credential = "protocol=https\nhost=broker.example:8443\npath=" + GIT_PATH + "\n\n";
        final var environment = Map.of(
                "YCA_CONTEXT_GIT_TOKEN",
                "git-only-secret",
                "YCA_CONTEXT_TOKEN",
                "control-only-secret",
                "GH_TOKEN",
                "never-use-github");
        final var valid = process(workspace.resolve("repository"), command, credential, environment);
        assertEquals(0, valid.exit(), valid.error());
        assertTrue(valid.output().contains("username=context\n"));
        assertTrue(valid.output().contains("password=" + environment.get("YCA_CONTEXT_GIT_TOKEN") + "\n"));
        assertFalse(valid.output().contains("control-only-secret"));
        assertFalse(valid.output().contains("never-use-github"));
        // approve/reject must not start Java at all, even with an unavailable helper entry point.
        for (final var notification : List.of("approve", "reject")) {
            final var notifyCommand = new ArrayList<>(command);
            notifyCommand.set(
                    notifyCommand.indexOf("credential.helper=" + helper),
                    "credential.helper=" + helper.replace(AgentBrokerSource.class.getName(), "UnavailableHelper"));
            notifyCommand.set(notifyCommand.size() - 1, notification);
            final var result = process(
                    workspace.resolve("repository"),
                    notifyCommand,
                    credential.stripTrailing() + "\nusername=context\npassword="
                            + environment.get("YCA_CONTEXT_GIT_TOKEN") + "\n\n",
                    environment);
            assertEquals(0, result.exit(), result.error());
            assertEquals("", result.output());
            assertEquals("", result.error());
        }
        for (final var invalid : List.of(
                credential.replace("protocol=https", "protocol=http"),
                credential.replace("broker.example:8443", "evil.example:8443"),
                credential.replace("grant-1.git", "grant-2.git"),
                credential.replace("12345678-1234-1234-1234-123456789abc", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                credential.replace(GIT_PATH, "some/other.git"))) {
            final var result = process(
                    workspace.resolve("repository"),
                    command,
                    invalid,
                    Map.of("YCA_CONTEXT_GIT_TOKEN", "git-only-secret"));
            assertNotEquals(0, result.exit());
            assertFalse((result.output() + result.error()).contains("git-only-secret"));
        }
        try (final var files = Files.walk(workspace)) {
            for (final var path : files.filter(Files::isRegularFile).toList()) {
                assertFalse(
                        new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1).contains("git-only-secret"),
                        path.toString());
            }
        }
        assertFalse(helper.contains("git-only-secret"));
        assertTrue(helper.contains("'"));
    }

    @Test
    void originRequiresLiteralAttemptPrefixAndBoundedGitGrantSuffix() throws Exception {
        final var workspace = temporary.resolve("origin-scope");
        final var head = fixture(workspace);
        // Keep a real main invocation in addition to the batched validation cases.
        final var smoke = cli(workspace, head, "expand", "src");
        assertEquals(0, smoke.exit(), smoke.error());
        matrix(workspace, head, "origin");
    }

    private static void originMatrix(final Path workspace, final String head) throws Exception {
        final var repo = workspace.resolve("repository");
        final var base = ENDPOINT.substring(0, ENDPOINT.lastIndexOf('/') + 1);
        for (final var origin : List.of(base, base + "git/a.git", base + "git/" + "A-9".repeat(26) + "ab.git")) {
            git(repo, "remote", "set-url", "origin", origin);
            assertDoesNotThrow(() -> runSource(flags(head), List.of("expand", "src")), origin);
        }
        for (final var origin : List.of(
                "https://brokerXexample:8443" + base.substring(base.indexOf("/context/")) + "git/grant-1.git",
                base + "git/.git",
                base + "git/" + "a".repeat(81) + ".git",
                base + "git/grant_1.git",
                base + "git/grant-1Xgit",
                base + "git/grant-1.git/extra",
                base + "git/grant-1.git?query",
                base + "git/nested/grant-1.git",
                "git/grant-1.git")) {
            git(repo, "remote", "set-url", "origin", origin);
            assertRejected(flags(head), List.of("expand", "docs"));
            assertFalse(Files.exists(repo.resolve("docs/guide.txt")), origin);
        }
    }

    @Test
    void expandsMonotonicallyWithRealIndependentGitAndPreservesPinnedHead() throws Exception {
        final var workspace = temporary.resolve("expand");
        final var head = fixture(workspace);
        final var repo = workspace.resolve("repository");
        assertFalse(Files.exists(repo.resolve("docs/guide.txt")));
        assertTrue(Files.isDirectory(repo.resolve(".git")));
        assertFalse(Files.exists(repo.resolve(".git/objects/info/alternates")));
        final var expanded = cli(workspace, head, "expand", "docs");
        assertEquals(0, expanded.exit(), expanded.error());
        assertEquals("guide\n", Files.readString(repo.resolve("docs/guide.txt")));
        assertEquals("source\n", Files.readString(repo.resolve("src/code.txt")));
        assertEquals(head, git(repo, "rev-parse", "HEAD").strip());
        assertEquals("", git(repo, "status", "--porcelain"));
        assertEquals(0, cli(workspace, head, "expand", "src").exit());
        assertTrue(Files.exists(repo.resolve("docs/guide.txt")));
        assertEquals(
                0, cli(workspace, head, "expand", ".github", "docs with spaces").exit());
        assertEquals("workflow\n", Files.readString(repo.resolve(".github/workflow.txt")));
        assertEquals("space\n", Files.readString(repo.resolve("docs with spaces/guide.txt")));
        assertEquals(
                ENDPOINT.substring(0, ENDPOINT.lastIndexOf('/') + 1) + "git/grant-1.git",
                git(repo, "remote", "get-url", "origin").strip());
    }

    @Test
    void refusesModifiedUntrackedIgnoredAndSymlinkCollisionsWithoutResetOrCleanup() throws Exception {
        for (final var kind : List.of("modified", "untracked", "ignored", "symlink")) {
            final var workspace = temporary.resolve(kind);
            final var head = fixture(workspace);
            final var repo = workspace.resolve("repository");
            final Path protectedPath;
            switch (kind) {
                case "modified" -> {
                    protectedPath = repo.resolve("src/code.txt");
                    Files.writeString(protectedPath, "agent edit\n");
                }
                case "untracked" -> {
                    protectedPath = repo.resolve("new.txt");
                    Files.writeString(protectedPath, "agent edit\n");
                }
                case "ignored" -> {
                    protectedPath = repo.resolve("ignored.tmp");
                    Files.writeString(protectedPath, "agent edit\n");
                }
                case "symlink" -> {
                    protectedPath = repo.resolve("docs");
                    Files.createSymbolicLink(protectedPath, temporary);
                }
                default -> throw new IllegalStateException();
            }
            final var result = cli(workspace, head, "expand", "docs");
            assertNotEquals(0, result.exit());
            assertTrue(REJECTED_GIT_OPERATION.matcher(result.error()).matches(), result.error());
            if (kind.equals("symlink")) {
                assertTrue(Files.isSymbolicLink(protectedPath));
            } else {
                assertEquals("agent edit\n", Files.readString(protectedPath));
                assertFalse(Files.exists(repo.resolve("docs/guide.txt")));
            }
            assertEquals(head, git(repo, "rev-parse", "HEAD").strip());
        }
    }

    @Test
    void rejectsUnsafeConfigInvalidPathsBoundsAndExistingInitWithoutNetwork() throws Exception {
        final var workspace = temporary.resolve("invalid");
        final var head = fixture(workspace);
        // Exercise main's disk-limit diagnostic too; the child matrix keeps its own real cwd throughout.
        final var diskBound = new ArrayList<>(flags(head));
        diskBound.set(diskBound.indexOf("--max-bytes") + 1, "1");
        final var result = launch(workspace, diskBound, List.of("expand", "docs"));
        assertNotEquals(0, result.exit());
        assertTrue(result.error().contains("code=DISK_LIMIT"), result.error());
        assertEquals("", result.output());
        assertFalse(result.error().contains(workspace.toString()), result.error());
        assertFalse(result.error().contains(ENDPOINT), result.error());
        matrix(workspace, head, "unsafe");
    }

    private static void unsafeMatrix(final Path workspace, final String head) throws Exception {
        final var repo = workspace.resolve("repository");
        for (final var path : List.of("../outside", "/tmp/outside", "src/../docs", "src//nested", ".git", "--stdin")) {
            assertRejected(flags(head), List.of("expand", path));
        }
        assertRejected(flags(head), List.of("deepen", "1000"));
        assertRejected(flags(head), List.of("init"));
        final var diskBound = new ArrayList<>(flags(head));
        diskBound.set(diskBound.indexOf("--max-bytes") + 1, "1");
        assertRejected(diskBound, List.of("expand", "docs"));
        assertFalse(Files.exists(repo.resolve("docs/guide.txt")));
        final var sentinel = workspace.getParent().resolve("must-not-exist");
        git(repo, "config", "filter.evil.smudge", "touch " + sentinel);
        assertRejected(flags(head), List.of("expand", "docs"));
        assertFalse(Files.exists(sentinel));
        assertEquals(head, git(repo, "rev-parse", "HEAD").strip());
    }

    private static void matrix(final Path workspace, final String head, final String name) throws Exception {
        final var result = process(
                workspace,
                TestJava.javaCommand("-cp", classpath(), MatrixLauncher.class.getName(), name, head),
                "",
                Map.of());
        assertEquals(0, result.exit(), result.output() + result.error());
    }

    public static final class MatrixLauncher {
        public static void main(final String... args) throws Exception {
            final var workspace = Path.of("").toAbsolutePath();
            switch (args[0]) {
                case "origin" -> originMatrix(workspace, args[1]);
                case "unsafe" -> unsafeMatrix(workspace, args[1]);
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }

    private static void assertRejected(final List<String> flags, final List<String> args) {
        final var failure =
                assertThrows(InvocationTargetException.class, () -> runSource(flags, args), args.toString());
        // Match main's exception boundary without reproducing its failure classification or formatting.
        assertInstanceOf(Exception.class, failure.getCause());
    }

    private static void runSource(final List<String> flags, final List<String> args) throws Exception {
        final var type = Class.forName(AgentBrokerSource.class.getName() + "$Options");
        final var parse = type.getDeclaredMethod("parse", String[].class);
        parse.setAccessible(true);
        final var arguments = new ArrayList<>(flags);
        arguments.addAll(args);
        final var options = parse.invoke(null, (Object) arguments.toArray(String[]::new));
        final var constructor =
                AgentBrokerSource.class.getDeclaredConstructor(type, JsonMapper.class, Configuration.class);
        constructor.setAccessible(true);
        final var run = AgentBrokerSource.class.getDeclaredMethod("run");
        run.setAccessible(true);
        try (final var json = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            run.invoke(constructor.newInstance(options, json, Configuration.of(Map.of())));
        }
    }

    @Test
    void productionCliRejectsHttpAndInvalidIdentityWithoutCreatingRepository() throws Exception {
        final var workspace = Files.createDirectory(temporary.resolve("options"));
        final var flags = new ArrayList<>(flags("a".repeat(40)));
        flags.set(1, ENDPOINT.replace("https://", "http://"));
        assertNotEquals(0, launch(workspace, flags, List.of("init")).exit());
        assertFalse(Files.exists(workspace.resolve("repository")));
        flags.set(1, ENDPOINT);
        flags.set(3, "../unsafe");
        assertNotEquals(0, launch(workspace, flags, List.of("init")).exit());
        assertFalse(Files.exists(workspace.resolve("repository")));
    }

    private String fixture(final Path workspace) throws IOException {
        // Preserve the complete sparse worktree, index, config, objects, and source identity without
        // sharing files.
        try (final var paths = Files.walk(seedTemporary.resolve("repository"))) {
            for (final var source : paths.toList()) {
                final var target = workspace.resolve(seedTemporary.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        return seedHead;
    }

    @Test
    void fixtureCopiesKeepSparseMetadataAndSourceIdentityIndependentAtQuotedPaths() throws Exception {
        final var first = temporary.resolve("first workspace with 'quotes'");
        final var second = temporary.resolve("second workspace with 'quotes'");
        assertEquals(seedHead, fixture(first));
        assertEquals(seedHead, fixture(second));
        try (final var paths = Files.walk(seedTemporary.resolve("repository"))) {
            for (final var source : paths.filter(Files::isRegularFile).toList()) {
                final var relative = seedTemporary.relativize(source);
                final var original = Files.readAllBytes(source);
                final var firstFile = first.resolve(relative);
                final var secondFile = second.resolve(relative);
                assertFalse(Files.isSameFile(source, firstFile), relative.toString());
                assertFalse(Files.isSameFile(firstFile, secondFile), relative.toString());
                assertArrayEquals(original, Files.readAllBytes(firstFile), relative.toString());
                // Loose Git objects are read-only; make only this private copy writable before mutation.
                Files.setPosixFilePermissions(firstFile, PosixFilePermissions.fromString("rw-------"));
                Files.writeString(firstFile, "copy-only mutation\n");
                assertArrayEquals(original, Files.readAllBytes(source), relative.toString());
                assertArrayEquals(original, Files.readAllBytes(secondFile), relative.toString());
            }
        }
        assertFalse(Files.exists(second.resolve("repository/.git/objects/info/alternates")));
        assertFalse(Files.exists(second.resolve("repository/docs/guide.txt")));
        assertEquals(
                seedHead, git(second.resolve("repository"), "rev-parse", "HEAD").strip());
        assertEquals(0, cli(second, seedHead, "expand", "docs").exit());
        assertEquals("guide\n", Files.readString(second.resolve("repository/docs/guide.txt")));
        assertFalse(Files.exists(seedTemporary.resolve("repository/docs/guide.txt")));
    }

    private static String buildFixture(final Path workspace) throws Exception {
        final var repo = Files.createDirectories(workspace.resolve("repository"));
        git(repo, "init", "--template=", ".");
        Files.createDirectories(repo.resolve("src"));
        Files.createDirectories(repo.resolve("docs"));
        Files.createDirectories(repo.resolve(".github"));
        Files.createDirectories(repo.resolve("docs with spaces"));
        Files.writeString(repo.resolve("src/code.txt"), "source\n");
        Files.writeString(repo.resolve("docs/guide.txt"), "guide\n");
        Files.writeString(repo.resolve(".github/workflow.txt"), "workflow\n");
        Files.writeString(repo.resolve("docs with spaces/guide.txt"), "space\n");
        Files.writeString(repo.resolve(".gitignore"), "ignored.tmp\n");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "fixture");
        final var head = git(repo, "rev-parse", "HEAD").strip();
        git(repo, "remote", "add", "origin", ENDPOINT.substring(0, ENDPOINT.lastIndexOf('/') + 1) + "git/grant-1.git");
        // The CLI itself does not create an upstream remote fetch policy.
        git(repo, "config", "--unset", "remote.origin.fetch");
        git(repo, "sparse-checkout", "init", "--cone");
        git(repo, "sparse-checkout", "set", "src");
        final var properties = new Properties();
        properties.setProperty("endpoint", ENDPOINT);
        properties.setProperty("repository", "owner/repo");
        properties.setProperty("head", head);
        properties.setProperty("depth", "1");
        properties.setProperty("paths", "src");
        properties.setProperty("gitPath", GIT_PATH);
        try (final var out = Files.newOutputStream(repo.resolve(".git/yca-broker.properties"))) {
            properties.store(out, "Test source identity");
        }
        return head;
    }

    public static final class HelperLauncher {
        public static void main(final String... args) throws Exception {
            System.out.print(helper(args[0]));
        }
    }

    private static String helper(final String head) throws Exception {
        final var type = Class.forName(AgentBrokerSource.class.getName() + "$Options");
        final Method parse = type.getDeclaredMethod("parse", String[].class);
        parse.setAccessible(true);
        final var args = new ArrayList<>(flags(head));
        args.add("tags");
        final var options = parse.invoke(null, (Object) args.toArray(String[]::new));
        final Constructor<AgentBrokerSource> constructor =
                AgentBrokerSource.class.getDeclaredConstructor(type, JsonMapper.class, Configuration.class);
        constructor.setAccessible(true);
        try (final var json = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            final var source = constructor.newInstance(options, json, Configuration.of(Map.of()));
            final var helper = AgentBrokerSource.class.getDeclaredMethod("helper");
            helper.setAccessible(true);
            return (String) helper.invoke(source);
        }
    }

    private static List<String> flags(final String head) {
        return List.of(
                "--endpoint",
                ENDPOINT,
                "--repository",
                "owner/repo",
                "--head",
                head,
                "--max-depth",
                "100",
                "--max-bytes",
                "10485760",
                "--timeout",
                "10");
    }

    private static Result cli(final Path cwd, final String head, final String... args) throws Exception {
        return launch(cwd, flags(head), List.of(args));
    }

    private static Result launch(final Path cwd, final List<String> flags, final List<String> args) throws Exception {
        final var command = TestJava.javaCommand("-cp", classpath(), AgentBrokerSource.class.getName());
        command.addAll(flags);
        command.addAll(args);
        return process(cwd, command, "", Map.of());
    }

    private static String classpath() {
        return Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                        .split(File.pathSeparator))
                .map(it -> Path.of(it).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String git(final Path cwd, final String... args) throws Exception {
        final var command = new ArrayList<>(List.of("/usr/bin/git"));
        command.addAll(List.of(args));
        final var result = process(cwd, command, "", Map.of());
        assertEquals(0, result.exit(), result.error());
        return result.output();
    }

    private static Result process(
            final Path cwd, final List<String> command, final String input, final Map<String, String> extra)
            throws Exception {
        final var builder = new ProcessBuilder(command).directory(cwd.toFile());
        builder.environment().clear();
        builder.environment()
                .putAll(Map.of(
                        "PATH",
                        "/usr/bin:/bin",
                        "HOME",
                        cwd.toString(),
                        "GIT_CONFIG_NOSYSTEM",
                        "1",
                        "GIT_CONFIG_GLOBAL",
                        "/dev/null",
                        "GIT_TERMINAL_PROMPT",
                        "0",
                        "LANG",
                        "C"));
        builder.environment().putAll(extra);
        final var process = builder.start();
        final var out = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        final var err = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        try {
            try (final var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Subprocess deadline");
            return new Result(process.exitValue(), out.get(1, TimeUnit.SECONDS), err.get(1, TimeUnit.SECONDS));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static String read(final InputStream input) {
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private record Result(int exit, String output, String error) {}
}
