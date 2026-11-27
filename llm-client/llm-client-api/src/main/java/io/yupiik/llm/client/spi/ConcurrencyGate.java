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

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Admission control for the concurrent requests of one provider: the budget the
 * {@code maxConcurrent} knob configures.
 *
 * <p>The historical implementation was a {@link java.util.concurrent.Semaphore} private to one
 * {@code LlmClientResilienceWrapper}, hence a budget per object graph of a single JVM. That is
 * wrong for account-level limits - a {@code 429} {@code concurrency_limit_exceeded} advertising
 * {@code "limit":2} applies to the whole account, not to one process - so admission is abstracted
 * here and an implementation may share the budget with other processes (see the file-lease based
 * gate of the core module).</p>
 *
 * <h2>Contract</h2>
 * <ul>
 * <li>{@link #acquire(long, BooleanSupplier)} is a <em>bounded blocking</em> call: it may park the
 *     calling thread until a slot is free, the timeout elapses or the cancellation probe flips. The
 *     wrapper runs contended admission and per-minute throttling on virtual threads, keeping its
 *     asynchronous API non-blocking. An implementation must never start the caller's work from its
 *     own thread.</li>
 * <li>the returned future is always <em>already completed</em>: with the release action, or
 *     exceptionally with a {@link ConcurrencySlotTimeoutException} (deadline), an {@code IOException}
 *     carrying {@code Cancelled} (probe), or an {@code IOException} when the gate is closed. It is a
 *     {@link CompletableFuture} for direct inspection and joining; cancelling this already completed
 *     future cannot cancel a wait or return a grant. Use the probe to abandon a pending wait and run
 *     the release action to return a grant.</li>
 * <li>the grant is owned by the caller from the returned action on: the {@link Runnable} is the
 *     release action and must be idempotent - running it twice, or from two threads, must release
 *     exactly one slot.</li>
 * <li>a release must be callable from <em>any</em> thread (a stream releases its slot from the
 *     subscriber thread), so implementations must not use an owner-bound lock for the slot itself
 *     (a {@code ReentrantLock} would throw, a {@code Semaphore} or a condition variable is fine).</li>
 * </ul>
 */
public interface ConcurrencyGate extends AutoCloseable {
    /** Schedules no bound at all: a caller which waits forever. */
    long WAIT_FOREVER = 0L;

    /**
     * No capacity bound: every caller is admitted immediately.
     *
     * <p>Distinct from "one slot": a provider-adapted cap must be relaxable back to an unlimited
     * budget, and an implementation must not try to materialise {@code Integer.MAX_VALUE} leases.</p>
     */
    int UNBOUNDED = 0;

    /**
     * Requests one slot, blocking the calling thread until it is granted.
     *
     * @return an already completed future: the idempotent release action on success, an exception on
     *     timeout, cancellation or shutdown.
     */
    default CompletableFuture<Runnable> acquire() {
        return acquire(WAIT_FOREVER, null);
    }

    /**
     * Requests one slot, blocking the calling thread for at most {@code timeoutMs}.
     *
     * @param timeoutMs maximum wait in milliseconds, {@link #WAIT_FOREVER} (0) or negative to wait forever.
     * @param cancelled cancellation probe polled while waiting, may be {@code null}.
     * @return an already completed future: the idempotent release action on success, an exception on
     *     timeout, cancellation or shutdown.
     */
    CompletableFuture<Runnable> acquire(long timeoutMs, BooleanSupplier cancelled);

    /**
     * Takes a slot right now, without ever parking the caller.
     *
     * <p>This is the fast path: an uncontended admission must not hop threads, so the caller's request
     * starts on the caller's thread exactly like a request which needs no admission control at all.
     * Only a contended admission goes through {@link #acquire(long, BooleanSupplier)}.</p>
     *
     * @return the idempotent release action, or {@code null} when a slot is not immediately available.
     */
    default Runnable tryAcquire() {
        return null;
    }

    /**
     * Sets the usable capacity of this process. Above the configured value this is the caller relaxing
     * a temporary adaptation, below it a provider announcing a smaller limit. Purely in-memory: an
     * implementation sharing its budget with peers must not let this local set overwrite what a peer
     * learned (see {@link #learnCapacity(int)}).
     *
     * <p>Grants already handed out are never revoked - a stream can run for minutes and must not be
     * preempted - so a shrink only applies to future admissions (the freed slots are absorbed until
     * the outstanding grants are back below the new capacity), while a growth re-attempts the queued
     * waiters immediately.</p>
     *
     * @param capacity the new usable capacity, {@link #UNBOUNDED} ({@code 0}) or negative for no bound.
     */
    void setCapacity(int capacity);

    /**
     * Applies a capacity a provider announced (typically the {@code "limit": N} of a
     * {@code 429 concurrency_limit_exceeded} response).
     *
     * <p>Unlike {@link #setCapacity(int)}, a shared implementation may persist this value for its
     * peers and for the next restarts, since it is a fact about the account and not a local policy.
     * Learning must not widen an existing smaller learned or configured bound. The default
     * implementation delegates to {@code setCapacity}; shared implementations must override it to
     * merge with their existing bound atomically.</p>
     *
     * @param capacity the announced capacity, clamped by the implementation.
     */
    default void learnCapacity(final int capacity) {
        setCapacity(capacity);
    }

    /**
     * Whether every slot is currently taken, so a caller can tell its user it is about to wait before
     * blocking.
     *
     * @return {@code true} when an {@link #acquire(long, BooleanSupplier)} is expected to block.
     */
    default boolean isSaturated() {
        return false;
    }

    /**
     * Refuses new admissions and fails the pending waiters; idempotent. The default implementation
     * does nothing, for a gate which has no resource to release.
     */
    @Override
    default void close() {}
}
