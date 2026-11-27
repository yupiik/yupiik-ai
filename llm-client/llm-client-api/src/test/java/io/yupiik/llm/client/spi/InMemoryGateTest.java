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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Pins the admission semantics callers rely on: FIFO, no barging, idempotent release, adaptation. */
@Timeout(20)
class InMemoryGateTest {
    @Test
    void learningNeverWidensAnExistingBound() {
        final var gate = new InMemoryGate(2);
        gate.learnCapacity(1);
        gate.learnCapacity(4);
        final var held = gate.acquire().join();
        assertEquals(null, gate.tryAcquire(), "a later provider signal must not widen a shared cap");
        held.run();
    }

    @Test
    void throwingCancellationProbeDoesNotLeaveADeadHead() throws Exception {
        final var gate = new InMemoryGate(1);
        final var held = gate.acquire().join();
        final var fail = new AtomicBoolean();
        final var waiter = acquireAsync(gate, 5000, () -> {
            if (fail.get()) {
                throw new IllegalStateException("probe failure");
            }
            return false;
        });
        awaitParked(waiter);
        fail.set(true);
        assertInstanceOf(IllegalStateException.class, causeOf(waiter));
        held.run();
        gate.acquire(1000, null).join().run();
    }

    @Test
    void uncontendedAcquireCompletesSynchronously() {
        final var gate = new InMemoryGate(1);
        final var stage = gate.acquire();

        assertTrue(stage.isDone(), "the fast path must not park the caller");
        stage.join().run(); // release
    }

    @Test
    void followsFifoWithoutBarging() throws Exception {
        final var gate = new InMemoryGate(1);
        final var held = gate.acquire().join();

        final var first = acquireAsync(gate);
        awaitParked(first);
        final var second = acquireAsync(gate);
        awaitParked(second);

        held.run();

        // the head of the queue is served first, even though `second` was posted later: no barging
        awaitDone(first);
        assertFalse(second.isDone(), "a newcomer must not barge the queued caller");

        first.join().run();
        awaitDone(second);
        second.join().run();
    }

    @Test
    void releaseIsIdempotent() throws Exception {
        final var gate = new InMemoryGate(1);
        final var release = gate.acquire().join();

        release.run();
        release.run(); // must not inflate the gate above its capacity

        // exactly one slot must be free: a phantom second slot from the double release would admit
        // a second caller here
        final var first = gate.acquire();
        assertTrue(first.isDone(), "the freed slot must be available again");
        final var second = acquireAsync(gate);
        awaitParked(second);
        awaitNoProgress(second); // a phantom slot would have served it right away
        first.join().run();
        awaitDone(second);
        second.join().run();
    }

    @Test
    void cancelProbeEndsTheWaitWithoutLeakingTheSlot() throws Exception {
        final var gate = new InMemoryGate(1);
        final var held = gate.acquire().join();
        final var cancelled = new AtomicBoolean();

        final var queued = acquireAsync(gate, 30_000L, cancelled::get);
        awaitParked(queued);
        cancelled.set(true);

        assertAsyncFailure(queued, "Cancelled");
        held.run();
        final var next = gate.acquire();
        assertTrue(next.isDone(), "the released slot must go to a live caller");
        next.join().run();
    }

    @Test
    void timeoutIsReportedAsItsOwnTypeAndLeavesNoSlot() throws Exception {
        final var gate = new InMemoryGate(1);
        final var held = gate.acquire().join();

        final var timedOut = acquireAsync(gate, 50L, null);
        assertInstanceOf(
                ConcurrencySlotTimeoutException.class,
                causeOf(timedOut),
                "a saturation must be distinguishable from a transport timeout");
        held.run();
        final var next = gate.acquire();
        assertTrue(next.isDone(), "the timed-out waiter must not have consumed a slot");
        next.join().run();
    }

    @Test
    void grantedContinuationRunsOnTheAcquiringThreadNotTheReleasingOne() throws Exception {
        // the releasing thread must never run the caller's work: a slow request started from it
        // would stall every other waiter
        final var gate = new InMemoryGate(1);
        final var held = gate.acquire().join();
        final var acquireThread = new AtomicReference<Thread>();
        final var releaseThread = new AtomicReference<Thread>();

        final var waiter = CompletableFuture.supplyAsync(
                () -> {
                    final var release = gate.acquire().join();
                    acquireThread.set(Thread.currentThread());
                    return release;
                },
                command -> Thread.ofPlatform().name("waiter").start(command));

        awaitNoProgress(waiter);
        final var runnable = new Runnable() {
            @Override
            public void run() {
                releaseThread.set(Thread.currentThread());
                held.run();
            }
        };
        runnable.run();

        final var granted = waiter.get(10, TimeUnit.SECONDS);
        assertEquals("waiter", acquireThread.get().getName(), "the grant belongs to the waiting caller");
        assertNotEquals(acquireThread.get(), releaseThread.get(), "the releaser never runs the granted work");
        granted.run();
    }

    @Test
    void growthWakesWaiters() throws Exception {
        final var gate = new InMemoryGate(1);
        final var held = gate.acquire().join();
        final var queued = acquireAsync(gate);
        awaitParked(queued);
        assertFalse(queued.isDone());

        gate.setCapacity(2);

        awaitDone(queued); // raising the capacity must serve the queued waiter right away
        queued.join().run();
        held.run();
    }

    @Test
    void shrinkDoesNotRevokeOutstandingGrantsAndAppliesToTheNextAdmission() throws Exception {
        final var gate = new InMemoryGate(2);
        final var first = gate.acquire().join();
        final var second = gate.acquire().join();

        gate.setCapacity(1); // both grants keep running

        final var queued = acquireAsync(gate);
        awaitNoProgress(queued);

        first.run();
        awaitNoProgress(queued);

        second.run();
        awaitDone(queued); // the slot is handed over once the in-flight count is back under capacity
        queued.join().run();
    }

    @Test
    void negativeOrZeroCapacityMeansUnbounded() {
        // 0 is the SPI's "no bound" value, not a deadlock: a provider-adapted cap is relaxed back to it
        final var gate = new InMemoryGate(1);
        gate.setCapacity(0);

        for (var i = 0; i < 8; i++) {
            assertTrue(gate.acquire().isDone(), "an unbounded gate admits everyone");
        }
        assertFalse(gate.isSaturated(), "an unbounded gate is never saturated");
    }

    @Test
    void unboundedCapacityAlwaysAdmits() {
        final var gate = new InMemoryGate(1);
        gate.setCapacity(ConcurrencyGate.UNBOUNDED);

        for (var i = 0; i < 64; i++) {
            assertTrue(gate.acquire().isDone(), "an unbounded gate must never park a caller");
        }
    }

    @Test
    void closeFailsPendingWaitersAndRefusesNewOnes() throws Exception {
        final var gate = new InMemoryGate(1);
        gate.acquire().join();
        final var queued = acquireAsync(gate);
        awaitParked(queued);

        gate.close();

        assertInstanceOf(IOException.class, causeOf(queued), "a closed gate is a transient failure");
        final var rejected = gate.acquire();
        assertTrue(rejected.isCompletedExceptionally(), "a closed gate must refuse new callers");
        assertInstanceOf(IOException.class, causeOf(rejected));
    }

    @Test
    void concurrentWaitersAreAllServedExactlyOnce() throws Exception {
        final var gate = new InMemoryGate(2);
        final var threads = 32;
        final var granted = new CountDownLatch(threads);
        final var inFlight = new AtomicInteger();
        final var maxInFlight = new AtomicInteger();
        final var releases = new ArrayList<Runnable>();
        final var failures = new ArrayList<Throwable>();

        final var started = new CountDownLatch(threads);
        final List<Thread> workers = new ArrayList<>(threads);
        for (var i = 0; i < threads; i++) {
            workers.add(Thread.ofPlatform().start(() -> {
                started.countDown();
                try {
                    final var release = gate.acquire().join();
                    synchronized (releases) {
                        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        releases.add(release);
                    }
                    granted.countDown();
                } catch (final Throwable e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                }
            }));
        }
        started.await();

        // serve the waiters wave by wave until everybody got its turn
        for (var guard = 0; guard < 2000 && granted.getCount() > 0; guard++) {
            Thread.sleep(5L);
            final List<Runnable> wave;
            synchronized (releases) {
                wave = List.copyOf(releases);
                releases.clear();
                inFlight.addAndGet(-wave.size());
            }
            wave.forEach(Runnable::run);
        }
        for (final var worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertEquals(0, failures.size(), "no waiter may fail: " + failures);
        assertEquals(0, granted.getCount(), "every waiter must eventually be served");
        assertTrue(maxInFlight.get() <= 2, "the capacity must never be exceeded, was " + maxInFlight.get());
    }

    @Test
    void repeatedTimeoutsNeverLeakASlot() throws Exception {
        // regression: a waiter which races a release must either take the slot or leave the queue,
        // never both. Any leak shrinks the budget permanently and the final acquire blocks.
        final var gate = new InMemoryGate(1);
        for (var round = 0; round < 50; round++) {
            final var held = gate.acquire().join();
            final var timedOut = gate.acquire(1L, null);
            assertTrue(timedOut.isCompletedExceptionally(), "a 1ms deadline must expire, round " + round);
            held.run();

            final var probe = gate.acquire(5_000L, null);
            assertTrue(probe.isDone(), "the slot must still be available, round " + round);
            probe.join().run();
        }
    }

    /** Runs an acquire on its own thread so the test can observe the parking contract. */
    private static CompletableFuture<Runnable> acquireAsync(final InMemoryGate gate) {
        return acquireAsync(gate, 0L, null);
    }

    /** Runs a bounded acquire on its own thread. */
    private static CompletableFuture<Runnable> acquireAsync(
            final InMemoryGate gate, final long timeoutMs, final BooleanSupplier cancelled) {
        final var result = new Acquisition();
        result.thread = Thread.ofPlatform().daemon().name("gate-waiter").unstarted(() -> {
            try {
                result.complete(gate.acquire(timeoutMs, cancelled).join());
            } catch (final Throwable e) {
                result.completeExceptionally(e);
            }
        });
        result.thread.start();
        return result;
    }

    /** Waits for an asynchronously abandoned/woken acquire to fail with {@code message}. */
    private static void assertAsyncFailure(final CompletableFuture<Runnable> future, final String message) {
        final var error = causeOf(future);
        assertEquals(message, error.getMessage());
    }

    private static final class Acquisition extends CompletableFuture<Runnable> {
        private Thread thread;
    }

    /** Observe the blocking SPI from its caller, without exposing production queue internals. */
    private static void awaitParked(final CompletableFuture<Runnable> future) throws Exception {
        final var thread = ((Acquisition) future).thread;
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline && !future.isDone()) {
            Thread.sleep(1L);
        }
        assertEquals(Thread.State.TIMED_WAITING, thread.getState());
        assertFalse(future.isDone());
    }

    /** Asserts a future stays pending for a short while, i.e. the caller really is parked. */
    private static void awaitNoProgress(final CompletableFuture<Runnable> future) {
        try {
            future.get(150L, TimeUnit.MILLISECONDS);
            throw new AssertionError("the acquire must still be waiting");
        } catch (final TimeoutException e) {
            // expected: still parked
        } catch (final Exception e) {
            throw new AssertionError("the acquire failed unexpectedly", e);
        }
    }

    /** Waits for the parked caller to be served: a grant necessarily crosses a thread boundary. */
    private static void awaitDone(final CompletableFuture<Runnable> future) {
        try {
            assertTrue(future.isDone() || !future.isCompletedExceptionally(), "");
            future.get(10, TimeUnit.SECONDS);
        } catch (final ExecutionException e) {
            throw new AssertionError("the acquire failed: " + e.getCause(), e);
        } catch (final Exception e) {
            throw new AssertionError("the acquire was never served", e);
        }
    }

    private static Throwable causeOf(final CompletableFuture<Runnable> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("the future must have failed");
        } catch (final ExecutionException e) {
            return e.getCause();
        } catch (final Exception e) {
            throw new AssertionError("unexpected failure", e);
        }
    }
}
