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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Internal process lifecycle; public adapters select the command and its approval protocol. */
abstract class BaseExecApprovalUi implements ApprovalUi {
    protected enum ResultPolicy {
        EXIT_ZERO,
        ALLOW_ACTION
    }

    private final Path executable;
    private final ResultPolicy resultPolicy;
    private final LongSupplier nanoTime;
    private final ExecutorService executor;
    private final ScopedValue<Boolean> inWorker = ScopedValue.newInstance();
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<Handle> active = new HashSet<>();
    private boolean closed;

    protected BaseExecApprovalUi(final Path executable, final ResultPolicy resultPolicy) {
        this(executable, resultPolicy, System::nanoTime);
    }

    protected BaseExecApprovalUi(final Path executable, final ResultPolicy resultPolicy, final LongSupplier nanoTime) {
        this.executable = trustedExecutable(executable);
        this.resultPolicy = Objects.requireNonNull(resultPolicy, "resultPolicy");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    protected abstract List<String> commandLine(Path executable, String sanitizedText, long budgetNanos);

    @Override
    public final ApprovalHandle present(final String text, final Duration remaining) {
        final var started = nanoTime.getAsLong();
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(text, "text");
        // Apply the coordinator's limit here too: no backend may approve a truncated target.
        if (text.length() > 3500) {
            throw new IllegalArgumentException("Approval presentation exceeds 3500 characters");
        }
        final var sanitized = PlainText.sanitize(text);
        final long budget;
        try {
            budget = Math.min(remaining.toNanos(), Long.MAX_VALUE / 2);
        } catch (final ArithmeticException overflow) {
            throw new IllegalArgumentException("Approval duration is too large", overflow);
        }
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("Approval UI is closed");
            }
            if (budget <= 0 || nanoTime.getAsLong() - started >= budget) {
                return new Handle(null, started, budget);
            }
            final Process process;
            try {
                process = new ProcessBuilder(commandLine(executable, sanitized, budget))
                        .redirectInput(ProcessBuilder.Redirect.from(
                                Path.of("/dev/null").toFile()))
                        .redirectOutput(
                                resultPolicy == ResultPolicy.EXIT_ZERO
                                        ? ProcessBuilder.Redirect.DISCARD
                                        : ProcessBuilder.Redirect.PIPE)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (final IOException unavailable) {
                return new Handle(null, started, budget);
            }
            final var handle = new Handle(process, started, budget);
            active.add(handle);
            executor.execute(() -> ScopedValue.where(inWorker, true).run(handle::await));
            return handle;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public final void close() {
        final Set<Handle> handles;
        lock.lock();
        try {
            closed = true;
            handles = Set.copyOf(active);
        } finally {
            lock.unlock();
        }
        for (final var handle : handles) {
            handle.close();
        }
        executor.shutdown();
        // Completion callbacks can close the UI on its own worker. Never join oneself.
        if (!inWorker.isBound()) {
            executor.close();
        }
    }

    private boolean awaitDecision(final Process process, final long started, final long budget)
            throws IOException, InterruptedException {
        if (resultPolicy == ResultPolicy.EXIT_ZERO) {
            final var left = budget - (nanoTime.getAsLong() - started);
            return left > 0 && process.waitFor(left, TimeUnit.NANOSECONDS) && process.exitValue() == 0;
        }
        // Poll and drain only available bytes: no pipe read can outlive the deadline.
        // One extra byte detects overflow without retaining unbounded subprocess output.
        final var output = new byte[65];
        int size = 0;
        final var input = process.getInputStream();
        while (true) {
            final var exited = !process.isAlive();
            while (input.available() > 0 && size < output.length) {
                final var count = input.read(output, size, Math.min(input.available(), output.length - size));
                if (count < 0) {
                    break;
                }
                size += count;
            }
            final var left = budget - (nanoTime.getAsLong() - started);
            if (size == output.length || left <= 0) {
                return false;
            }
            if (exited) {
                final var action = new String(output, 0, size, StandardCharsets.US_ASCII);
                return process.exitValue() == 0 && (action.equals("allow") || action.equals("allow\n"));
            }
            process.waitFor(Math.min(left, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS);
        }
    }

    private static Path trustedExecutable(final Path executable) {
        Objects.requireNonNull(executable, "executable");
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("Executable must be absolute");
        }
        try {
            final var real = executable.toRealPath();
            if (!Files.isRegularFile(real) || !Files.isExecutable(real)) {
                throw new IllegalArgumentException("Executable must be a regular executable file");
            }
            for (var path = real; path != null; path = path.getParent()) {
                final var permissions = Files.getPosixFilePermissions(path);
                if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw new IllegalArgumentException("Untrusted writable executable path: " + path);
                }
            }
            return real;
        } catch (final IOException | UnsupportedOperationException failure) {
            throw new IllegalArgumentException("Cannot validate executable", failure);
        }
    }

    private final class Handle implements ApprovalHandle {
        private final Process process;
        private final long started;
        private final long budget;
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private final ReentrantLock decisionLock = new ReentrantLock();
        private boolean cancelled;

        private Handle(final Process process, final long started, final long budget) {
            this.process = process;
            this.started = started;
            this.budget = budget;
            if (process == null) {
                result.complete(false);
            }
        }

        @Override
        public CompletionStage<Boolean> decision() {
            return result.minimalCompletionStage();
        }

        private void await() {
            boolean allow = false;
            try {
                allow = awaitDecision(process, started, budget);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (final IOException failure) {
                // A broken output pipe cannot establish approval.
            } finally {
                reap();
            }
            decisionLock.lock();
            try {
                final boolean approved;
                lock.lock();
                try {
                    approved = allow && !cancelled && !closed && nanoTime.getAsLong() - started < budget;
                    active.remove(this);
                } finally {
                    lock.unlock();
                }
                // Decide and deregister under the UI lock; run callbacks outside it.
                result.complete(approved);
            } finally {
                decisionLock.unlock();
            }
        }

        @Override
        public void close() {
            decisionLock.lock();
            try {
                cancelled = true;
            } finally {
                decisionLock.unlock();
            }
            reap();
            result.complete(false);
        }

        private void reap() {
            if (process == null) {
                return;
            }
            process.destroyForcibly();
            boolean interrupted = false;
            while (true) {
                try {
                    process.waitFor();
                    break;
                } catch (final InterruptedException interruption) {
                    interrupted = true;
                }
            }
            try {
                process.getInputStream().close();
            } catch (final IOException ignored) {
                // The process is already reaped; a pipe close failure cannot grant approval.
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
