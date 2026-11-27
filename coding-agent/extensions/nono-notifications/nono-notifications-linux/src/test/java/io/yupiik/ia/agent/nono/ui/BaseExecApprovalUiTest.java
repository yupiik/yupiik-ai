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
package io.yupiik.ia.agent.nono.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.nono.TestTempDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(15)
class BaseExecApprovalUiTest {
    private Path directory;

    @BeforeEach
    void createTrustedDirectory() throws IOException {
        // /tmp is intentionally rejected by the production ancestor policy.
        directory = TestTempDirs.create(Path.of(System.getProperty("user.home")), ".nono-ui-test-");
    }

    @AfterEach
    void cleanup() throws IOException {
        TestTempDirs.cleanup(directory);
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void expiredDurationsDenyWithoutLaunching(final Adapter adapter) throws Exception {
        final var executable = script("printf 'allow\\n'");
        try (final ApprovalUi ui = adapter.create(executable)) {
            for (final var duration : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(-1))) {
                try (final var handle = ui.present("expired", duration)) {
                    assertFalse(handle.decision().toCompletableFuture().get(1, TimeUnit.SECONDS));
                    Assertions.assertDoesNotThrow(handle::close);
                    Assertions.assertDoesNotThrow(handle::close);
                    assertFalse(handle.decision().toCompletableFuture().get(1, TimeUnit.SECONDS));
                    assertTrue(pids(executable).isEmpty());
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void overflowingDurationsRejectWithoutLaunching(final Adapter adapter) throws Exception {
        final var executable = script("printf 'allow\\n'");
        try (final ApprovalUi ui = adapter.create(executable)) {
            for (final var duration : List.of(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MIN_VALUE))) {
                assertThrows(IllegalArgumentException.class, () -> ui.present("overflow", duration));
                assertTrue(pids(executable).isEmpty());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void missingExecutableDenies(final Adapter adapter) throws Exception {
        final var executable = script("printf 'allow\\n'");
        try (final ApprovalUi ui = adapter.create(executable)) {
            Files.delete(executable);
            try (final var handle = ui.present("missing", Duration.ofSeconds(1))) {
                assertFalse(handle.decision().toCompletableFuture().get(2, TimeUnit.SECONDS));
                assertTrue(pids(executable).isEmpty());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void failedLaunchOfExistingExecutableDenies(final Adapter adapter) throws Exception {
        final var executable = script("printf 'allow\\n'");
        // An executable file can pass trust validation but still fail to launch.
        Files.writeString(executable, "#!" + directory.resolve("missing-interpreter") + "\n");
        try (final ApprovalUi ui = adapter.create(executable);
                final var handle = ui.present("failed launch", Duration.ofSeconds(1))) {
            assertFalse(handle.decision().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(pids(executable).isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void deadlineKillsAndReapsProcess(final Adapter adapter) throws Exception {
        // exec keeps the published PID without a busy loop or a child left behind.
        final var executable = script("exec sleep 30");
        final var clock = new DeadlineClock();
        try (final ApprovalUi ui = adapter.create(executable, clock);
                final var handle = ui.present("test", Duration.ofMillis(500))) {
            final var pid = awaitPids(executable, 1).getFirst();
            // PID publication is the startup handshake: exercise a live process, not
            // merely an expired presentation that never successfully launched.
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(handle.decision().toCompletableFuture().isDone());
            clock.expire();
            assertFalse(handle.decision().toCompletableFuture().get(7, TimeUnit.SECONDS));
            assertReaped(pid);
        } finally {
            clock.expire();
        }
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void handleCloseDeniesAndReapsIdempotently(final Adapter adapter) throws Exception {
        final var executable = script("while :; do :; done");
        try (final ApprovalUi ui = adapter.create(executable);
                final var handle = ui.present("test", Duration.ofSeconds(30))) {
            final var pid = awaitPids(executable, 1).getFirst();
            Assertions.assertDoesNotThrow(handle::close);
            Assertions.assertDoesNotThrow(handle::close);
            assertFalse(handle.decision().toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertReaped(pid);
        }
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void uiCloseDeniesAndReapsAllOutstandingHandlesIdempotently(final Adapter adapter) throws Exception {
        final var executable = script("while :; do :; done");
        try (final ApprovalUi ui = adapter.create(executable);
                final var first = ui.present("first", Duration.ofSeconds(30));
                final var second = ui.present("second", Duration.ofSeconds(30));
                final var third = ui.present("third", Duration.ofSeconds(30))) {
            final var pids = awaitPids(executable, 3);
            Assertions.assertDoesNotThrow(ui::close);
            Assertions.assertDoesNotThrow(ui::close);
            for (final var handle : List.of(first, second, third)) {
                assertFalse(handle.decision().toCompletableFuture().get(1, TimeUnit.SECONDS));
                handle.close();
                assertFalse(handle.decision().toCompletableFuture().get(1, TimeUnit.SECONDS));
            }
            pids.forEach(this::assertReaped);
            assertThrows(IllegalStateException.class, () -> ui.present("closed", Duration.ofSeconds(1)));
        }
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void decisionCallbackCanCloseUiWithoutJoiningItsOwnWorker(final Adapter adapter) throws Exception {
        final var executable = script("while [ ! -f \"$0.release\" ]; do :; done\nprintf 'allow\\n'");
        try (final ApprovalUi ui = adapter.create(executable);
                final var handle = ui.present("test", Duration.ofSeconds(5))) {
            final var pid = awaitPids(executable, 1).getFirst();
            // Register before releasing the process so the callback runs on the completion worker.
            final var closed = handle.decision().thenApply(allowed -> {
                Assertions.assertDoesNotThrow(ui::close);
                return allowed;
            });
            Files.createFile(Path.of(executable + ".release"));
            assertTrue(closed.toCompletableFuture().get(7, TimeUnit.SECONDS));
            assertReaped(pid);
            assertThrows(IllegalStateException.class, () -> ui.present("closed", Duration.ofSeconds(1)));
        }
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void decisionCallbackClosingAnotherUiWaitsForThatUisWorker(final Adapter adapter) throws Exception {
        final var body = "while [ ! -f \"$0.release\" ]; do :; done\nprintf 'allow\\n'";
        final var firstExecutable = Files.move(script(body), directory.resolve("first-approval-test"));
        final var secondExecutable = script(body);
        final var secondCallbackEntered = new CountDownLatch(1);
        final var releaseSecondCallback = new CountDownLatch(1);
        final var closeEntered = new CountDownLatch(1);
        try (final ApprovalUi firstUi = adapter.create(firstExecutable);
                final ApprovalUi secondUi = adapter.create(secondExecutable);
                final var first = firstUi.present("first", Duration.ofSeconds(10));
                final var second = secondUi.present("second", Duration.ofSeconds(10))) {
            final var firstPid = awaitPids(firstExecutable, 1, first, second).getFirst();
            final var secondPid = awaitPids(secondExecutable, 1, first, second).getFirst();
            final var secondCallback = second.decision().thenApply(allowed -> {
                secondCallbackEntered.countDown();
                try {
                    assertTrue(releaseSecondCallback.await(5, TimeUnit.SECONDS));
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return allowed;
            });
            final var closed = first.decision().thenApply(allowed -> {
                closeEntered.countDown();
                Assertions.assertDoesNotThrow(secondUi::close);
                return allowed;
            });
            try {
                Files.createFile(Path.of(secondExecutable + ".release"));
                assertTrue(secondCallbackEntered.await(2, TimeUnit.SECONDS));
                Files.createFile(Path.of(firstExecutable + ".release"));
                assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
                // Another UI's worker scope must not bypass this UI's executor join.
                assertThrows(
                        TimeoutException.class,
                        () -> closed.toCompletableFuture().get(200, TimeUnit.MILLISECONDS));
            } finally {
                releaseSecondCallback.countDown();
            }
            assertTrue(secondCallback.toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertTrue(closed.toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertReaped(firstPid);
            assertReaped(secondPid);
            assertThrows(IllegalStateException.class, () -> secondUi.present("closed", Duration.ofSeconds(1)));
        }
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void preservesMaximumPresentationAndRejectsOversizeWithoutLaunching(final Adapter adapter) throws Exception {
        final var executable = script("printf '%s\\n' \"$@\" > \"$0.args\"; printf 'allow\\n'");
        final var text = "x".repeat(3500);
        try (final ApprovalUi ui = adapter.create(executable)) {
            assertThrows(IllegalArgumentException.class, () -> ui.present(text + "x", Duration.ofSeconds(1)));
            assertTrue(pids(executable).isEmpty());
            try (final var handle = ui.present(text, Duration.ofSeconds(3))) {
                assertTrue(handle.decision().toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals(
                        1L,
                        Files.readAllLines(Path.of(executable + ".args")).stream()
                                .filter(argument -> argument.endsWith(text))
                                .count());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void rejectsUnsafePathsAndResolvesTrustedSymlinks(final Adapter adapter) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> adapter.create(Path.of("relative-executable")));
        assertThrows(IllegalArgumentException.class, () -> adapter.create(directory));
        assertThrows(IllegalArgumentException.class, () -> adapter.create(directory.resolve("missing")));
        final var executable = script("printf 'allow\\n'");
        final var link = Files.createSymbolicLink(directory.resolve("link"), executable);
        for (final var permissions : List.of("rwxrwx---", "rwx---rwx", "rw-------")) {
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString(permissions));
            assertThrows(IllegalArgumentException.class, () -> adapter.create(executable));
            assertThrows(IllegalArgumentException.class, () -> adapter.create(link));
        }
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        for (final var permissions : List.of("rwxrwx---", "rwx---rwx")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(permissions));
            assertThrows(IllegalArgumentException.class, () -> adapter.create(executable));
            assertThrows(IllegalArgumentException.class, () -> adapter.create(link));
        }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        try (final ApprovalUi ui = adapter.create(link);
                final var handle = ui.present("resolved", Duration.ofSeconds(2))) {
            assertTrue(handle.decision().toCompletableFuture().get(3, TimeUnit.SECONDS));
        }
    }

    private Path script(final String body) throws IOException, InterruptedException {
        final var path = directory.resolve("approval-test");
        // Put the PID in the filename so concurrent launches cannot overwrite each other's identity.
        final var content = "#!/bin/sh\n: > \"$0.$$.pid\"\n" + body + "\n";
        // Files.writeString opens without O_CLOEXEC on Linux. Concurrent process launches can
        // inherit its writable descriptor and make exec fail with ETXTBSY even after Java closes it.
        // Only the writer child opens this file; reaping it establishes that the writer is gone.
        final var writer = new ProcessBuilder(
                        "/bin/sh",
                        "-c",
                        "umask 077; printf '%s' \"$2\" > \"$1\"",
                        "approval-fixture",
                        path.toString(),
                        content)
                .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            assertEquals(0, writer.waitFor(), "Could not write approval fixture: " + path);
        } finally {
            writer.destroyForcibly();
            writer.onExit().join();
        }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }

    private List<Long> pids(final Path executable) throws IOException {
        final var prefix = executable.getFileName() + ".";
        try (final var paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".pid"))
                    .map(name -> Long.parseLong(name.substring(prefix.length(), name.length() - 4)))
                    .toList();
        }
    }

    private List<Long> awaitPids(final Path executable, final int count, final ApprovalHandle... handles)
            throws Exception {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final var pids = pids(executable);
            if (pids.size() == count) {
                return pids;
            }
            Thread.sleep(10);
        }
        final List<String> files;
        try (final var paths = Files.list(directory)) {
            files = paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
        final var decisions = Arrays.stream(handles)
                .map(handle -> handle.decision().toCompletableFuture())
                .map(future -> future
                        + (future.isDone() && !future.isCompletedExceptionally()
                                ? " value=" + future.getNow(null)
                                : ""))
                .toList();
        final var processes = pids(executable).stream()
                .map(pid -> pid + " alive="
                        + ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .toList();
        final var children = ProcessHandle.current()
                .children()
                .map(process -> process.pid() + " alive=" + process.isAlive() + " command="
                        + process.info().command().orElse("unknown"))
                .toList();
        throw new AssertionError("Subprocesses did not publish " + count + " PIDs for " + executable + "; decisions="
                + decisions + "; files=" + files + "; processes=" + processes + "; children=" + children);
    }

    private void assertReaped(final long pid) {
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Process still alive: " + pid);
    }

    private static final class DeadlineClock implements LongSupplier {
        private final Thread owner = Thread.currentThread();
        private final CountDownLatch expired = new CountDownLatch(1);

        @Override
        public long getAsLong() {
            if (Thread.currentThread() == owner) {
                return 0;
            }
            // Do not let scheduler load consume the budget before the PID handshake.
            try {
                if (!expired.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Test did not advance the approval deadline");
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return TimeUnit.SECONDS.toNanos(1);
        }

        private void expire() {
            expired.countDown();
        }
    }

    private enum Adapter {
        ZENITY(ZenityApprovalUi::new),
        NOTIFY_SEND(NotifySendApprovalUi::new);

        private final Function<Path, ApprovalUi> factory;

        Adapter(final Function<Path, ApprovalUi> factory) {
            this.factory = factory;
        }

        private ApprovalUi create(final Path executable) {
            return factory.apply(executable);
        }

        private ApprovalUi create(final Path executable, final LongSupplier nanoTime) {
            final var policy = this == ZENITY
                    ? BaseExecApprovalUi.ResultPolicy.EXIT_ZERO
                    : BaseExecApprovalUi.ResultPolicy.ALLOW_ACTION;
            return new BaseExecApprovalUi(executable, policy, nanoTime) {
                @Override
                protected List<String> commandLine(
                        final Path command, final String sanitizedText, final long budgetNanos) {
                    return List.of(command.toString());
                }
            };
        }
    }
}
