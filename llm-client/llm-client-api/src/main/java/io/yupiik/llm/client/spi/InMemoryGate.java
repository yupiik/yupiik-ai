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
package io.yupiik.llm.client.spi;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Default {@link ConcurrencyGate}: an in-memory budget, private to this JVM.
 *
 * <p>The capacity, the in-flight count and the waiter queue live under the same monitor, which is
 * the single source of truth: a waiter only takes its slot while it sits at the head of the queue,
 * so the admission order is strict FIFO and a newcomer cannot barge the slot a queued caller was
 * about to get. A waiter takes its slot <em>itself</em>, in the same critical section where it
 * leaves the queue, so a timeout or a cancellation racing a release can neither leak nor
 * double-allocate a slot - and the caller which got the slot is always the thread which starts the
 * request, never the thread which released the previous one.</p>
 */
public final class InMemoryGate implements ConcurrencyGate {
    /** Cadence at which a parked caller re-checks its deadline and the cancellation probe. */
    private static final long WAIT_SLICE_MS = 50L;

    private final Deque<Waiter> waiters = new ArrayDeque<>();
    private int capacity;
    private int inFlight;
    private boolean closed;

    /**
     * Creates a gate.
     *
     * @param capacity the configured slot count, zero or negative for no bound.
     */
    public InMemoryGate(final int capacity) {
        this.capacity = normalize(capacity);
    }

    @Override
    public CompletableFuture<Runnable> acquire(final long timeoutMs, final BooleanSupplier cancelled) {
        final var waiter = new Waiter();
        final var started = System.nanoTime();
        final var timeoutNs = timeoutMs <= 0 ? Long.MAX_VALUE : TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        var queued = false;
        synchronized (this) {
            while (true) {
                if (closed) {
                    return failed(queued, waiter, new IOException("concurrency gate is closed"));
                }
                try {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        return failed(queued, waiter, new IOException("Cancelled"));
                    }
                } catch (final RuntimeException e) {
                    return failed(queued, waiter, e);
                }
                if (inFlight < capacity && waiters.isEmpty()) {
                    // uncontended (or our turn came): take the slot here and now
                    inFlight++;
                    return CompletableFuture.completedFuture(waiter.release());
                }
                if (queued && waiters.peekFirst() == waiter && inFlight < capacity) {
                    waiters.pollFirst();
                    inFlight++;
                    notifyAll();
                    return CompletableFuture.completedFuture(waiter.release());
                }
                final var remainingNs =
                        timeoutNs == Long.MAX_VALUE ? Long.MAX_VALUE : timeoutNs - (System.nanoTime() - started);
                if (remainingNs <= 0) {
                    return failed(
                            queued,
                            waiter,
                            new ConcurrencySlotTimeoutException("no concurrency slot available within " + timeoutMs
                                    + "ms (" + inFlight + " in flight, " + waiters.size() + " waiting)"));
                }
                if (!queued) {
                    waiters.addLast(waiter);
                    queued = true;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, sliceNs(remainingNs));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return failed(queued, waiter, new InterruptedIOException("interrupted while waiting for a slot"));
                }
            }
        }
    }

    @Override
    public Runnable tryAcquire() {
        synchronized (this) {
            if (closed || inFlight >= capacity || !waiters.isEmpty()) {
                return null;
            }
            inFlight++;
            return new Waiter().release();
        }
    }

    /** Removes an abandoning waiter so the slot goes to the next one instead of to a dead caller. */
    private CompletableFuture<Runnable> failed(final boolean queued, final Waiter waiter, final Throwable error) {
        if (queued) {
            waiters.remove(waiter);
            notifyAll();
        }
        final var future = new CompletableFuture<Runnable>();
        future.completeExceptionally(error);
        return future;
    }

    @Override
    public void setCapacity(final int capacity) {
        synchronized (this) {
            this.capacity = normalize(capacity);
            // a shrink only applies to future admissions - an outstanding grant is never revoked (a
            // stream can run for minutes) - which the admission check above implements as is; a
            // growth wakes the parked waiters so they take the new slots right away
            notifyAll();
        }
    }

    @Override
    public void learnCapacity(final int capacity) {
        synchronized (this) {
            this.capacity = Math.min(this.capacity, normalize(capacity));
            notifyAll();
        }
    }

    @Override
    public boolean isSaturated() {
        synchronized (this) {
            return inFlight >= capacity;
        }
    }

    /** Fails the pending waiters and refuses new admissions; idempotent. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            notifyAll();
        }
    }

    private void released() {
        synchronized (this) {
            if (inFlight > 0) {
                inFlight--;
            }
            notifyAll();
        }
    }

    private static int normalize(final int capacity) {
        return capacity <= 0 ? Integer.MAX_VALUE : capacity;
    }

    private static long sliceNs(final long remainingNs) {
        return Math.min(remainingNs, TimeUnit.MILLISECONDS.toNanos(WAIT_SLICE_MS));
    }

    /** One queued caller and its idempotent release action. */
    private final class Waiter {
        private final AtomicBoolean released = new AtomicBoolean();

        private Runnable release() {
            return () -> {
                if (released.compareAndSet(false, true)) {
                    released();
                }
            };
        }
    }
}
