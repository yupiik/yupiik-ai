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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.spi.Dialect;
import io.yupiik.llm.client.spi.StreamAttempt;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the {@code skipRetries} (cooperative-retry) flag on the five-argument
 * {@link LlmClientResilienceWrapper#chat}: with {@code skipRetries=true} a transient failure
 * (IOException or HTTP 5xx/429) is surfaced immediately - exactly one attempt, no wrapper retry -
 * while the concurrency semaphore still throttles (a second concurrent call is serialized); with
 * {@code skipRetries=false} (the delegated default) the wrapper retries per
 * {@link LlmResilienceConfig#retriesMaxAttempts()}.
 */
@Timeout(15)
class SkipRetriesTest {

    private static LlmResilienceConfig config(final int attempts, final int maxConcurrent) {
        return new LlmResilienceConfig(
                attempts, 0, 0, maxConcurrent, false, true, 300_000L, 1.0d, 900_000L, 3_600_000L, 300_000L);
    }

    @Test
    void skipRetriesSurfacesTransientFailureImmediatelyButStillThrottles() throws Exception {
        final var dial = new AlwaysFailDialect(true, new IOException("LLM request failed (500): boom"));
        // cap = 1: the second cooperative call must be serialized behind the first even though both
        // fail fast - the concurrency throttle keeps working in cooperative mode
        final var wrapper = new LlmClientResilienceWrapper(config(4, 1), null);

        // call 1: the in-flight permit holder (its dialect stage stays open until released)
        final var first = wrapper.chat(dial, null, () -> false, null, true).toCompletableFuture();

        // Admission is non-blocking: call directly so the assertion does not depend on a shared
        // common-pool worker being available to start the second call.
        final var blocked = new CountDownLatch(1);
        try {
            final var second = wrapper.chat(
                            dial,
                            null,
                            () -> false,
                            pause -> {
                                if (pause.reason() != null && pause.reason().contains("concurrency limit reached")) {
                                    blocked.countDown();
                                }
                            },
                            true)
                    .toCompletableFuture();

            assertTrue(blocked.await(5, TimeUnit.SECONDS), "the second call must be throttled on the semaphore");
            assertEquals(
                    1,
                    dial.calls.get(),
                    "while the first call holds the permit, the second must not have reached the dialect");

            // Release the first failure and its permit; each call must surface its own single failure.
            dial.releaseFirst.complete(null);
            final var firstFailure = assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, firstFailure.getCause());
            assertTrue(firstFailure.getCause().getMessage().contains("(500): boom"), "got: " + firstFailure.getCause());

            final var secondFailure = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, secondFailure.getCause());
            assertTrue(
                    secondFailure.getCause().getMessage().contains("(500): boom"), "got: " + secondFailure.getCause());
            assertEquals(
                    2,
                    dial.calls.get(),
                    "each cooperative call must run exactly one attempt: no wrapper retry happened");
        } finally {
            dial.releaseFirst.complete(null);
        }
    }

    @Test
    void skipRetriesFalseRetriesPerMaxAttempts() {
        final var dial = new AlwaysFailDialect(false, new IOException("LLM request failed (500): boom"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 4), null);

        final var failure = assertThrows(
                CompletionException.class,
                () -> wrapper.chat(dial, null, () -> false, null, false)
                        .toCompletableFuture()
                        .join());

        assertTrue(
                failure.getCause() instanceof IOException
                        && failure.getCause().getMessage().contains("(500): boom"),
                "got: " + failure.getCause());
        assertEquals(3, dial.calls.get(), "delegated mode must retry transient failures up to retriesMaxAttempts");
    }

    @Test
    void skipRetriesTrueSurfacesTyped429WithoutRetrying() {
        final var dial = new AlwaysFailDialect(false, new LlmHttpFailureException(429, "boom", null));
        final var wrapper = new LlmClientResilienceWrapper(config(4, 4), null);
        try {
            wrapper.chat(dial, null, () -> false, null, true)
                    .toCompletableFuture()
                    .join();
            fail("a transient 429 in cooperative mode must not be swallowed");
        } catch (final CompletionException expected) {
            assertInstanceOf(LlmHttpFailureException.class, expected.getCause(), "got: " + expected.getCause());
        }
        assertEquals(1, dial.calls.get(), "cooperative mode must not retry a 429");
    }

    /**
     * A {@link Dialect} whose every call fails with the scripted failure, so an accidental wrapper
     * retry is visible in {@link #calls}. When {@code holdFirstFailure} is set, the very first call
     * stays in flight (holding the concurrency permit) until {@link #releaseFirst} completes, so a
     * second concurrent call demonstrably blocks on the semaphore; otherwise every call fails
     * immediately.
     */
    private static final class AlwaysFailDialect implements Dialect {
        private final Throwable[] failures;
        private final AtomicInteger calls = new AtomicInteger();
        private final CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        private final boolean holdFirstFailure;

        private AlwaysFailDialect(final boolean holdFirstFailure, final Throwable... failures) {
            this.holdFirstFailure = holdFirstFailure;
            this.failures = failures;
        }

        @Override
        public String name() {
            return "always-fail";
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request) {
            return chat(request, null);
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
            final var call = calls.getAndIncrement();
            if (holdFirstFailure && call == 0) {
                return releaseFirst.thenCompose(ignored -> failed(failures[0]));
            }
            return failed(failures[Math.min(call, failures.length - 1)]);
        }

        @Override
        public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
                final LlmRequest request, final BooleanSupplier cancelled) {
            return failed(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<LlmModels> models() {
            return CompletableFuture.completedFuture(new LlmModels(List.of()));
        }

        @Override
        public CompletionStage<StreamAttempt> streamRaw(final LlmRequest request, final BooleanSupplier cancelled) {
            return failed(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<HttpResponse<byte[]>> postRaw(final LlmRequest request) {
            return failed(new UnsupportedOperationException());
        }

        private static <A> CompletionStage<A> failed(final Throwable error) {
            final var future = new CompletableFuture<A>();
            future.completeExceptionally(error);
            return future;
        }
    }
}
