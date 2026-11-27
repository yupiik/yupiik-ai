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
package io.yupiik.llm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.spi.ConcurrencyGate;
import io.yupiik.llm.client.spi.ConcurrencySlotTimeoutException;
import io.yupiik.llm.client.spi.Dialect;
import io.yupiik.llm.client.spi.InMemoryGate;
import io.yupiik.llm.client.spi.StreamAttempt;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Covers the two defects the gate refactor fixes: a slot must never be held during a retry wait, and
 * an abandoned turn must neither keep queuing nor issue a request.
 */
@Timeout(20)
class LlmClientResilienceWrapperCancellationTest {
    @Test
    void cancellationRacingGrantedAdmissionReturnsExactlyOneSlot() throws Exception {
        final var granted = new CountDownLatch(1);
        final var returnGrant = new CountDownLatch(1);
        final var released = new CountDownLatch(1);
        final var releases = new AtomicInteger();
        final ConcurrencyGate gate = new ConcurrencyGate() {
            @Override
            public CompletableFuture<Runnable> acquire(final long timeoutMs, final BooleanSupplier cancelled) {
                granted.countDown();
                try {
                    if (!returnGrant.await(5, TimeUnit.SECONDS)) {
                        return CompletableFuture.failedFuture(new AssertionError("test did not allow grant return"));
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
                // Cancellation can race the last probe and the caller receiving the grant.
                return CompletableFuture.completedFuture(() -> {
                    releases.incrementAndGet();
                    released.countDown();
                });
            }

            @Override
            public void setCapacity(final int capacity) {}
        };
        final var dial = new FailingOnceDialect(null);
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 1), null, null, null, null, cap -> gate);
        final var result = wrapper.chat(dial, null, null).toCompletableFuture();
        assertTrue(granted.await(5, TimeUnit.SECONDS));
        assertTrue(result.cancel(false));
        returnGrant.countDown();
        assertTrue(released.await(5, TimeUnit.SECONDS));
        assertEquals(1, releases.get());
        assertEquals(0, dial.calls.get());
    }

    @Test
    void cancellingReturnedFutureAbandonsBlockingAdmission() throws Exception {
        final var delegate = new InMemoryGate(1);
        final var held = delegate.acquire().join();
        final var entered = new CountDownLatch(1);
        final var abandoned = new CountDownLatch(1);
        final ConcurrencyGate gate = new ConcurrencyGate() {
            @Override
            public CompletableFuture<Runnable> acquire(final long timeoutMs, final BooleanSupplier cancelled) {
                entered.countDown();
                try {
                    return delegate.acquire(timeoutMs, cancelled);
                } finally {
                    abandoned.countDown();
                }
            }

            @Override
            public void setCapacity(final int capacity) {
                delegate.setCapacity(capacity);
            }
        };
        final var dial = new FailingOnceDialect(null);
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 1), null, null, null, null, cap -> gate);
        final var queued = wrapper.chat(dial, null, null).toCompletableFuture();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(queued.cancel(false));
        assertTrue(abandoned.await(5, TimeUnit.SECONDS), "cancellation must reach the blocking SPI");
        held.run();
        delegate.acquire(1000, null).join().run();
        assertEquals(0, dial.calls.get());
    }

    @Test
    void modelsAdmissionIsAsyncBoundedAndSynchronousFailureReleases() throws Exception {
        final var gate = new InMemoryGate(1);
        final var held = gate.acquire().join();
        final var calls = new AtomicInteger();
        final var dial = new FailingOnceDialect(null) {
            @Override
            public CompletionStage<LlmModels> models() {
                calls.incrementAndGet();
                throw new IllegalStateException("catalog failure");
            }
        };
        final var wrapper = new LlmClientResilienceWrapper(
                new LlmResilienceConfig(5, 0, 0, 1, false, false, 300_000, 1, 900_000, 3_600_000, 100),
                null,
                null,
                null,
                null,
                cap -> gate);
        final var queued = wrapper.models(dial).toCompletableFuture();
        assertFalse(queued.isDone(), "models must return before blocking admission finishes");
        assertInstanceOf(
                ConcurrencySlotTimeoutException.class,
                assertThrows(ExecutionException.class, () -> queued.get(5, TimeUnit.SECONDS))
                        .getCause());
        assertEquals(0, calls.get());
        held.run();
        final var failed = wrapper.models(dial).toCompletableFuture();
        assertInstanceOf(
                IllegalStateException.class,
                assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS))
                        .getCause());
        gate.acquire(1000, null).join().run();
        assertEquals(1, calls.get());
    }

    @Test
    void rateThrottleDoesNotParkCallerAndCanBeCancelled() throws Exception {
        final var dial = new FailingOnceDialect(null);
        final var wrapper = new LlmClientResilienceWrapper(
                new LlmResilienceConfig(3, 0, 1, 1, false, false, 300_000, 1, 900_000, 3_600_000, 100), null);
        wrapper.chat(dial, null, null).toCompletableFuture().get(5, TimeUnit.SECONDS);
        final var cancelled = new AtomicBoolean();
        final var queued = wrapper.chat(dial, null, cancelled::get).toCompletableFuture();
        assertFalse(queued.isDone());
        cancelled.set(true);
        assertInstanceOf(
                IOException.class,
                assertThrows(ExecutionException.class, () -> queued.get(5, TimeUnit.SECONDS))
                        .getCause());
        assertEquals(1, dial.calls.get());
    }

    @Test
    void cancelledStreamSetupCancelsLatePublisherBeforeReturningSlot() throws Exception {
        final var gate = new InMemoryGate(1);
        final var setup = new CompletableFuture<Flow.Publisher<LlmStreamEvent>>();
        final var upstreamCancelled = new AtomicBoolean();
        final var dial = new FailingOnceDialect(null) {
            @Override
            public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
                    final LlmRequest request, final BooleanSupplier cancelled) {
                return setup;
            }
        };
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 1), null, null, null, null, cap -> gate);
        final var result = wrapper.stream(dial, null, null).toCompletableFuture();
        assertTrue(result.cancel(false));
        assertNull(gate.tryAcquire(), "setup still owns the slot until upstream is torn down");
        setup.complete(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(final long n) {}

            @Override
            public void cancel() {
                assertNull(gate.tryAcquire(), "cancel upstream before returning its slot");
                upstreamCancelled.set(true);
            }
        }));
        assertTrue(upstreamCancelled.get());
        gate.acquire(1000, null).join().run();
    }

    private static LlmResilienceConfig config(final int attempts, final long baseDelayMs, final int maxConcurrent) {
        return new LlmResilienceConfig(
                attempts, baseDelayMs, 0, maxConcurrent, false, true, 300_000L, 0.0001d, 900_000L, 3_600_000L, 60_000L);
    }

    @Test
    void retryWaitDoesNotHoldTheSlot() throws Exception {
        // cap 1 with a retry-after=1 producer: during the ~1s backoff the only slot must already be
        // free, so a concurrent request is served meanwhile (the old implementation parked on the
        // slot for the whole wait and the second request only started after the retry completed)
        final var slow = new FailingOnceDialect(new LlmHttpFailureException(429, "boom", "1"));
        final var quick = new FailingOnceDialect(null);
        final var wrapper = new LlmClientResilienceWrapper(
                new LlmResilienceConfig(2, 0, 0, 1, false, true, 300_000L, 1.0d, 900_000L, 3_600_000L, 60_000L), null);

        final var retrying = wrapper.chat(slow, null, () -> false).toCompletableFuture();
        // wait for the failure to be handled, i.e. for the wrapper to be inside its backoff
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (slow.calls.get() < 1 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(slow.calls.get() >= 1, "the first attempt must have failed");

        final var startedAt = System.nanoTime();
        assertEquals(
                "ok",
                wrapper.chat(quick, null, () -> false)
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS)
                        .text());
        final var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue(
                elapsedMs < 900, "the concurrent request must not queue behind the backoff, took " + elapsedMs + "ms");

        assertEquals("ok", retrying.get(10, TimeUnit.SECONDS).text());
    }

    @Test
    void cancelledWhileQueuedNeverCallsTheProvider() {
        final var dial = new BlockingDialect(new CompletableFuture<>(), new AtomicBoolean());
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 1), null);

        // fill the only slot with a request which never completes
        wrapper.chat(dial, null, () -> false);

        final var cancelled = new AtomicBoolean();
        final var queued = wrapper.chat(dial, null, cancelled::get).toCompletableFuture();
        assertFalse(queued.isDone(), "the second request must be queued behind the slot holder");

        cancelled.set(true);
        // the queued turn gives up: it must fail as cancelled and never reach the provider
        final var failure = assertThrows(ExecutionException.class, () -> queued.get(10, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals("Cancelled", failure.getCause().getMessage());
    }

    @Test
    void timeoutFailsWithItsOwnTypeAndLeavesNoSlot() throws Exception {
        final var dial = new BlockingDialect(new CompletableFuture<>(), new AtomicBoolean());
        final var wrapper = new LlmClientResilienceWrapper(
                new LlmResilienceConfig(5, 0, 0, 1, false, false, 300_000L, 1.0d, 900_000L, 3_600_000L, 100L), null);

        wrapper.chat(dial, null, () -> false); // holds the only slot, forever

        final var queued = wrapper.chat(
                        new BlockingDialect(new CompletableFuture<>(), new AtomicBoolean()), null, () -> false)
                .toCompletableFuture();
        final var failure = assertThrows(ExecutionException.class, () -> queued.get(10, TimeUnit.SECONDS));
        // a saturated budget is not a transport timeout: it has its own type, so the retry loop does
        // not multiply the admission deadline by the attempt count
        assertInstanceOf(ConcurrencySlotTimeoutException.class, failure.getCause(), "got: " + failure.getCause());
        assertFalse(
                failure.getCause().getMessage().toLowerCase().contains("timed out"),
                "the message must not look like a transient transport timeout");
    }

    @Test
    void unlimitedWrapperNeverInflatesALazilyCreatedGate() throws Exception {
        // no gate configured: the permit is a no-op, and the first 429 lazily creates a bounded gate;
        // the request which never held a slot must not release into it
        final var gate = new InMemoryGate(1);
        final var dialect = new FailingOnceDialect(new IOException(
                "LLM request failed (429): {\"error\":{\"code\":\"concurrency_limit_exceeded\",\"limit\":1}}"));
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 0), null, null, null, null, capacity -> gate);

        assertEquals(
                "ok",
                wrapper.chat(dialect, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());

        // the lazily-created gate still has exactly one slot: a phantom release would have made room
        // for a second concurrent admission
        final var first = gate.tryAcquire();
        assertTrue(first != null, "the only slot must be free");
        assertNull(gate.tryAcquire(), "a phantom release would have inflated the gate");
        first.run();
        final var second = gate.tryAcquire();
        assertTrue(second != null, "the released slot is available again");
        second.run();
    }

    @Test
    void acquireHonoursTheConfiguredDeadlineAndLeavesTheGateUsable() {
        // the SPI contract: acquire(timeout) never blocks forever, and a timed-out caller leaves no
        // trace behind - the slot it never got is still available to the next caller
        final var gate = new InMemoryGate(1);
        final var held = gate.acquire().join();

        final var failure = assertThrows(
                ExecutionException.class, () -> gate.acquire(50L, null).get(10, TimeUnit.SECONDS));
        assertInstanceOf(ConcurrencySlotTimeoutException.class, failure.getCause());

        held.run();
        final var next = gate.acquire(1000L, null);
        assertTrue(next.isDone(), "the timed-out waiter must not have consumed a slot");
        next.join().run();
    }

    private static LlmResponse response(final String text) {
        return new LlmResponse(
                "id",
                "model",
                0L,
                List.of(new LlmResponse.LlmChoice(
                        0, new LlmMessage("assistant", text, null, null, null, null), "stop")),
                null,
                null);
    }

    /** Fails once, then answers: the wrapper pays one retry wait in between. */
    private static class FailingOnceDialect implements Dialect {
        private final Throwable failure;
        private final AtomicInteger calls = new AtomicInteger();

        private FailingOnceDialect(final Throwable failure) {
            this.failure = failure;
        }

        @Override
        public String name() {
            return "failing-once";
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request) {
            return chat(request, null);
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
            final var call = calls.getAndIncrement();
            if (call == 0 && failure != null) {
                final var failed = new CompletableFuture<LlmResponse>();
                failed.completeExceptionally(failure);
                return failed;
            }
            return CompletableFuture.completedFuture(response("ok"));
        }

        @Override
        public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
                final LlmRequest request, final BooleanSupplier cancelled) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<LlmModels> models() {
            return CompletableFuture.completedFuture(new LlmModels(List.of()));
        }

        @Override
        public CompletionStage<StreamAttempt> streamRaw(final LlmRequest request, final BooleanSupplier cancelled) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<HttpResponse<byte[]>> postRaw(final LlmRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }

    /** Never answers until {@code backlog} completes; flags when it was entered. */
    private static class BlockingDialect implements Dialect {
        private final CompletableFuture<LlmResponse> backlog;
        private final AtomicBoolean released;

        private BlockingDialect(final CompletableFuture<LlmResponse> backlog, final AtomicBoolean released) {
            this.backlog = backlog;
            this.released = released;
        }

        @Override
        public String name() {
            return "blocking";
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request) {
            return chat(request, null);
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
            return released.get() ? CompletableFuture.completedFuture(response("released")) : backlog;
        }

        @Override
        public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
                final LlmRequest request, final BooleanSupplier cancelled) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<LlmModels> models() {
            return CompletableFuture.completedFuture(new LlmModels(List.of()));
        }

        @Override
        public CompletionStage<StreamAttempt> streamRaw(final LlmRequest request, final BooleanSupplier cancelled) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<HttpResponse<byte[]>> postRaw(final LlmRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
