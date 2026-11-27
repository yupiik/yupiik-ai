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
package io.yupiik.ia.agent.core.context;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Session-owned worker slot; run-local candidates never outlive their cancellation scope. */
final class SpeculativeCompactionManager implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(SpeculativeCompactionManager.class.getName());
    private final BiFunction<SpeculativeCompaction.Prepared, BooleanSupplier, SpeculativeCompaction.Candidate> worker;
    private ExecutorService executor;
    private Job job;
    private boolean attempted;

    private static final class Job {
        private final SpeculativeCompaction.Prepared source;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CompletableFuture<SpeculativeCompaction.Candidate> result = new CompletableFuture<>();
        private volatile boolean exited;

        private Job(final SpeculativeCompaction.Prepared source) {
            this.source = source;
        }
    }

    SpeculativeCompactionManager(
            final BiFunction<SpeculativeCompaction.Prepared, BooleanSupplier, SpeculativeCompaction.Candidate> worker) {
        this.worker = worker;
    }

    synchronized boolean canLaunch() {
        return !attempted && (job == null || job.exited);
    }

    synchronized boolean launch(final SpeculativeCompaction.Prepared source, final BooleanSupplier cancelled) {
        if (!canLaunch() || cancelled.getAsBoolean()) {
            return false;
        }
        attempted = true;
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                final var thread = new Thread(r, "yca-speculative-compaction");
                thread.setDaemon(true);
                return thread;
            });
        }
        final var launched = new Job(source);
        job = launched;
        try {
            executor.execute(() -> {
                SpeculativeCompaction.Candidate candidate = null;
                try {
                    candidate = worker.apply(
                            source,
                            () -> launched.cancelled.get()
                                    || cancelled.getAsBoolean()
                                    || Thread.currentThread().isInterrupted());
                } catch (final RuntimeException e) {
                    // a failed speculative compaction is a silently wasted inference (the history is
                    // untouched and the next generation retries); surface it once so it is diagnosable
                    LOGGER.log(Level.WARNING, "Speculative compaction failed; history untouched", e);
                } finally {
                    launched.exited = true;
                    launched.result.complete(launched.cancelled.get() ? null : candidate);
                    LOGGER.fine("Speculative compaction worker exited");
                }
            });
            return true;
        } catch (final RuntimeException e) {
            launched.exited = true;
            launched.result.complete(null);
            LOGGER.log(Level.FINE, "Speculative compaction submission failed", e);
            return false;
        }
    }

    synchronized SpeculativeCompaction.Prepared source() {
        return job == null || job.cancelled.get() ? null : job.source;
    }

    Optional<SpeculativeCompaction.Candidate> take(
            final SpeculativeCompaction.Prepared source, final BooleanSupplier cancelled) {
        final Job current;
        synchronized (this) {
            current = job;
        }
        if (current == null || current.source != source) {
            return Optional.empty();
        }
        try {
            while (!current.cancelled.get() && !cancelled.getAsBoolean()) {
                try {
                    final var candidate = current.result.get(50, TimeUnit.MILLISECONDS);
                    if (cancelled.getAsBoolean() || !current.cancelled.compareAndSet(false, true)) {
                        return Optional.empty();
                    }
                    return Optional.ofNullable(candidate);
                } catch (final TimeoutException e) {
                    // Poll the cancellation probe without cancelling the completion future: only
                    // the worker's finally block proves the client request actually exited.
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
            LOGGER.log(Level.FINE, "Speculative compaction result unavailable", e);
        }
        current.cancelled.set(true);
        return Optional.empty();
    }

    synchronized void invalidate() {
        if (job != null) {
            job.cancelled.set(true);
        }
    }

    /** A real history replacement starts a new generation; an old worker still occupies its slot until exit. */
    synchronized void newGeneration() {
        invalidate();
        attempted = false;
    }

    @Override
    public synchronized void close() {
        newGeneration();
        if (executor != null) {
            // No shutdownNow/future cancellation: neither proves remote inference stopped.
            executor.shutdown();
            executor = null;
        }
    }
}
