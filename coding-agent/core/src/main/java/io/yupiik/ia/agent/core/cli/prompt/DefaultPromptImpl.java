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
package io.yupiik.ia.agent.core.cli.prompt;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Degraded {@link Prompt} used when the terminal widget stack is not present:
 * it reads plain lines from {@code stdin} and does not offer line editing.
 * <p>
 * On an interactive terminal a single background pump reader owns {@code stdin}
 * (so the REPL can read prompts while a turn streams out) and hands each line
 * to the pending interaction read first, then to the pending turn read; lines
 * typed while nobody waits are kept for the next turn read. Piped/scripted
 * stdin stays fully synchronous, preserving one-turn-per-line semantics.
 * <p>
 * Nothing is closed here: {@code stdin}/{@code stdout} are process-owned and
 * must outlive this bean.
 */
@ApplicationScoped
public class DefaultPromptImpl implements LinedPrompt {
    private final BufferedReader reader;
    private final PrintStream out;
    private final QueuePump pump;

    public DefaultPromptImpl() {
        this(System.in, System.out, System.console() != null);
    }

    DefaultPromptImpl(final InputStream in, final PrintStream out) {
        this(in, out, false);
    }

    DefaultPromptImpl(final InputStream in, final PrintStream out, final boolean queueing) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
        this.pump = queueing ? new QueuePump(reader) : null;
    }

    @Override
    public boolean queuing() {
        return pump != null;
    }

    @Override
    public Optional<String> readLine(final String prompt) throws IOException {
        out.print(prompt);
        out.flush();
        return pump == null ? Optional.ofNullable(reader.readLine()) : pump.awaitTurn();
    }

    @Override
    public Optional<String> readInteractionLine(final String prompt) throws IOException {
        out.print(prompt);
        out.flush();
        return pump == null ? Optional.ofNullable(reader.readLine()) : pump.awaitInteraction();
    }

    @Override
    public Optional<String> pollQueue() {
        return pump == null ? Optional.empty() : pump.pollTurn();
    }

    @Override
    public void wakeAll() {
        if (pump != null) {
            pump.wakeAll();
        }
    }

    /**
     * Full-duplex routing state machine behind the queuing prompt: it decides
     * which line of {@code stdin} goes to which reader. It is thread-free:
     * {@link QueuePump} feeds lines in via {@link #dispatch(String)} while the
     * readers register waiters through {@link #awaitTurn()} /
     * {@link #awaitInteraction()}, and {@code turnBacklog} is what lets
     * type-ahead survive the small windows where no read is armed.
     */
    static final class QueueRouter {
        private final ReentrantLock lock = new ReentrantLock();
        private final ArrayDeque<CompletableFuture<Optional<String>>> turnWaiters = new ArrayDeque<>();
        private final ArrayDeque<CompletableFuture<Optional<String>>> interactionWaiters = new ArrayDeque<>();
        private final ArrayDeque<String> turnBacklog = new ArrayDeque<>();
        private boolean eof;

        /**
         * Registers an interaction waiter without blocking; the returned future
         * is completed by the next dispatched line (or {@code Optional.empty()}
         * on EOF).
         */
        CompletableFuture<Optional<String>> armInteraction() {
            return arm(interactionWaiters);
        }

        /**
         * Registers a turn waiter without blocking or consumes a type-ahead
         * line; the returned future is completed by the next dispatched line,
         * a backlogged line, or {@code Optional.empty()} on EOF.
         */
        CompletableFuture<Optional<String>> armTurn() {
            return arm(turnWaiters);
        }

        Optional<String> awaitTurn() {
            return await(arm(turnWaiters));
        }

        Optional<String> awaitInteraction() {
            return await(arm(interactionWaiters));
        }

        Optional<String> pollTurn() {
            lock.lock();
            try {
                return turnBacklog.isEmpty() ? Optional.empty() : Optional.ofNullable(turnBacklog.pollFirst());
            } finally {
                lock.unlock();
            }
        }

        void wakeAll() {
            ArrayDeque<CompletableFuture<Optional<String>>> all;
            lock.lock();
            try {
                all = new ArrayDeque<>(interactionWaiters);
                all.addAll(turnWaiters);
                interactionWaiters.clear();
                turnWaiters.clear();
            } finally {
                lock.unlock();
            }
            all.forEach(waiter -> waiter.complete(Optional.empty()));
        }

        private CompletableFuture<Optional<String>> arm(final ArrayDeque<CompletableFuture<Optional<String>>> waiters) {
            final var future = new CompletableFuture<Optional<String>>();
            lock.lock();
            try {
                // backlog, eof and registration must be decided atomically: if the
                // pump drains the stream between two separate checks, a turn read
                // could see eof while the backlog still holds the typed lines.
                if (waiters == turnWaiters && !turnBacklog.isEmpty()) {
                    future.complete(Optional.of(turnBacklog.pollFirst()));
                    return future;
                }
                if (eof) {
                    future.complete(Optional.empty());
                    return future;
                }
                waiters.addLast(future);
            } finally {
                lock.unlock();
            }
            return future;
        }

        /**
         * Routes one stdin line (or {@code null} for EOF): the interaction lane
         * wins over the turn lane, and lines nobody wants yet are buffered for
         * the next turn read.
         */
        void dispatch(final String line) {
            final var end = line == null;
            CompletableFuture<Optional<String>> target = null;
            ArrayDeque<CompletableFuture<Optional<String>>> all = null;
            lock.lock();
            try {
                if (end) {
                    eof = true;
                    all = new ArrayDeque<>(interactionWaiters);
                    all.addAll(turnWaiters);
                    interactionWaiters.clear();
                    turnWaiters.clear();
                } else {
                    target = interactionWaiters.pollFirst();
                    if (target == null) {
                        target = turnWaiters.pollFirst();
                    }
                    if (target == null) {
                        turnBacklog.addLast(line);
                    }
                }
            } finally {
                lock.unlock();
            }
            if (target != null) {
                target.complete(Optional.of(line));
            } else if (all != null) {
                all.forEach(waiter -> waiter.complete(Optional.empty()));
            }
        }

        private static Optional<String> await(final CompletableFuture<Optional<String>> future) {
            while (true) {
                try {
                    return future.get(100, TimeUnit.MILLISECONDS);
                } catch (final TimeoutException timeout) {
                    // keep waiting for the pump delivery
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                } catch (final ExecutionException e) {
                    return Optional.empty();
                }
            }
        }
    }

    /**
     * Sole reader of {@code stdin} once started: it hands each line to
     * {@link QueueRouter#dispatch(String)} while the pending reads are waiting.
     */
    private static final class QueuePump {
        private final BufferedReader reader;
        private final QueueRouter router;

        private QueuePump(final BufferedReader reader) {
            this.reader = reader;
            this.router = new QueueRouter();
            Thread.ofVirtual().name("yca-cli-stdin").start(this::pumpLoop);
        }

        private void pumpLoop() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    router.dispatch(line);
                }
                router.dispatch(null);
            } catch (final Exception e) {
                router.dispatch(null);
            }
        }

        private Optional<String> awaitTurn() {
            return router.awaitTurn();
        }

        private Optional<String> awaitInteraction() {
            return router.awaitInteraction();
        }

        private Optional<String> pollTurn() {
            return router.pollTurn();
        }

        private void wakeAll() {
            router.wakeAll();
        }
    }
}
