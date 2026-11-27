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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.spi.Dialect;
import io.yupiik.llm.client.spi.StreamAttempt;
import io.yupiik.llm.client.spi.Streams;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class LlmClientResilienceWrapperTest {

    private static LlmResilienceConfig config(final int attempts, final long baseDelayMs, final int maxConcurrent) {
        return new LlmResilienceConfig(
                attempts, baseDelayMs, 0, maxConcurrent, false, true, 300_000L, 1.0d, 900_000L, 3_600_000L, 300_000L);
    }

    private static LlmResilienceConfig config(
            final int attempts, final long baseDelayMs, final int maxConcurrent, final double waitScale) {
        return new LlmResilienceConfig(
                attempts,
                baseDelayMs,
                0,
                maxConcurrent,
                false,
                true,
                300_000L,
                waitScale,
                900_000L,
                3_600_000L,
                300_000L);
    }

    @Test
    void retryAfterSecondsParsesSecondsAndIgnoresTrailingJunk() {
        final var e = new IOException("LLM request failed (429): {\"error\":{\"message\":\"boom\"}} retry-after=2,");
        assertEquals(2L, LlmClientResilienceWrapper.retryAfterSeconds(e));
    }

    @Test
    void retryAfterSecondsParsesHttpDate() {
        final var later = ZonedDateTime.now().plusMinutes(5).withNano(0);
        final var e = new IOException("LLM request failed (429): {\"error\":{}} retry-after="
                + DateTimeFormatter.RFC_1123_DATE_TIME.format(later));
        final var seconds = LlmClientResilienceWrapper.retryAfterSeconds(e);
        assertNotNull(seconds);
        assertTrue(seconds >= 240 && seconds <= 360, "unexpected delay: " + seconds);
    }

    @Test
    void retryAfterSecondsReturnsNullForMissingAndMalformed() {
        assertNull(LlmClientResilienceWrapper.retryAfterSeconds(new IOException("LLM request failed (400): oops")));
        assertNull(LlmClientResilienceWrapper.retryAfterSeconds(
                new IOException("LLM request failed (429): oops retry-after=")));
        assertNull(LlmClientResilienceWrapper.retryAfterSeconds(new IOException("no marker in here")));
        assertNull(LlmClientResilienceWrapper.retryAfterSeconds(null));
    }

    @Test
    void concurrencyLimitDetectionExtractsTheAnnouncedLimit() {
        final var e = new IOException(
                "LLM request failed (429): {\"error\":{\"code\":\"concurrency_limit_exceeded\",\"message\":"
                        + "\"Too many concurrent requests (limit: 2)\",\"limit\":2,\"role\":\"pro\"}} retry-after=1");
        assertTrue(LlmClientResilienceWrapper.isConcurrencyLimit(e));
        assertEquals(2, LlmClientResilienceWrapper.concurrencyLimit(e));
        assertNull(LlmClientResilienceWrapper.concurrencyLimit(new IOException("LLM request failed (429): plain")));
    }

    @Test
    void concurrencyLimitEmitsAPauseWhileBlocked() throws Exception {
        final var firstStarted = new CountDownLatch(1);
        final var releaseFirst = new CompletableFuture<LlmResponse>();
        final var count = new AtomicInteger();
        final var dial = new ScriptedDialect() {
            @Override
            public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
                if (count.getAndIncrement() == 0) {
                    firstStarted.countDown();
                    return releaseFirst;
                }
                return CompletableFuture.completedFuture(response());
            }
        };
        final var pauseSeen = new CountDownLatch(1);
        final var pauses = new CopyOnWriteArrayList<PauseEvent>();
        final var wrapper = new LlmClientResilienceWrapper(config(1, 0, 1), pause -> {
            pauses.add(pause);
            if (pause.reason() != null && pause.reason().contains("concurrency limit reached")) {
                pauseSeen.countDown();
            }
        });

        final var first = wrapper.chat(dial, null, () -> false);
        try {
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "first request must start");
            // Admission is asynchronous: no common-pool task is needed to start a waiting call.
            final var second = wrapper.chat(dial, null, () -> false).toCompletableFuture();
            assertTrue(
                    pauseSeen.await(5, TimeUnit.SECONDS),
                    "a blocked request must surface a concurrency pause, got: " + pauses);
            releaseFirst.complete(response());
            assertEquals(
                    "ok", first.toCompletableFuture().get(5, TimeUnit.SECONDS).text());
            assertEquals("ok", second.get(5, TimeUnit.SECONDS).text());
        } finally {
            releaseFirst.complete(response());
        }
    }

    @Test
    void cancelledStreamReleasesItsConcurrencyPermit() throws Exception {
        // a stream that never emits and never completes on its own: the permit is only ever
        // released when the subscriber cancels it (an Esc-interrupt), exercising the cancel()
        // release and proving the concurrency slot is reclaimed rather than leaked.
        final Dialect holdOpen = new Dialect() {
            @Override
            public String name() {
                return "hold-open";
            }

            @Override
            public CompletionStage<LlmResponse> chat(final LlmRequest request) {
                return chat(request, null);
            }

            @Override
            public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
                return CompletableFuture.completedFuture(response());
            }

            @Override
            public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
                    final LlmRequest request, final BooleanSupplier cancelled) {
                return CompletableFuture.completedFuture(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(final long n) {
                        // intentionally deliver nothing
                    }

                    @Override
                    public void cancel() {
                        // the source aborts; only the wrapper's cancel-release frees the permit
                    }
                }));
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
        };
        // cap = 1: if the cancelled stream leaked its permit, the next request blocks forever
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 1));
        final var subscribed = new CountDownLatch(1);
        final var heldSubscription = new AtomicReference<Flow.Subscription>();
        wrapper.stream(holdOpen, null, () -> false)
                .toCompletableFuture()
                .thenAccept(publisher -> publisher.subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(final Flow.Subscription subscription) {
                        heldSubscription.set(subscription);
                        subscribed.countDown();
                    }

                    @Override
                    public void onNext(final LlmStreamEvent item) {}

                    @Override
                    public void onError(final Throwable throwable) {}

                    @Override
                    public void onComplete() {}
                }));
        assertTrue(subscribed.await(5, TimeUnit.SECONDS), "stream must have subscribed");
        // cancel mid-stream, before any terminal signal -> this is the permit-release path under test
        heldSubscription.get().cancel();
        // the permit must be back: with cap=1 a leaked permit would hang the next request
        assertEquals(
                "ok",
                wrapper.chat(holdOpen, null, () -> false)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
                        .text());
    }

    @Test
    void retryAfterIsHonoredAndSurfacedInThePauseReason() {
        final var dial = new ScriptedDialect(new IOException("LLM request failed (429): {\"error\":{}} retry-after=2"));
        final var pauses = new CopyOnWriteArrayList<PauseEvent>();
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 4, 0.0001d), pauses::add);

        final var response =
                wrapper.chat(dial, null, () -> false).toCompletableFuture().join();

        assertEquals("ok", response.text());
        assertTrue(
                pauses.stream()
                        .anyMatch(p -> p.reason().contains("retry-after=2s")
                                && p.reason().contains("wait")),
                "the retry notice must surface the honored delay, got: " + pauses);
    }

    @Test
    void concurrencyLimitWithoutRetryAfterWaitsADefault() {
        final var dial = new ScriptedDialect(
                new IOException("LLM request failed (429): {\"error\":{\"code\":\"concurrency_limit_exceeded\","
                        + "\"message\":\"Too many concurrent requests\",\"limit\":2}}"));
        final var pauses = new CopyOnWriteArrayList<PauseEvent>();
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 4, 0.0001d), pauses::add);

        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());

        // the floor is deliberately jittered (75%..150% of the default) so several processes do not
        // retry in lockstep: the assertion must follow the documented range, not the nominal value
        final var nominal = LlmClientResilienceWrapper.CONCURRENCY_LIMIT_DEFAULT_WAIT_MS;
        assertTrue(
                pauses.stream()
                        .anyMatch(p -> p.reason().contains("concurrency-limit")
                                && p.waitMs() >= nominal * 3 / 4
                                && p.waitMs() <= nominal * 3 / 2),
                "the retry notice must surface a jittered wait and why it waits, got: " + pauses);
        assertTrue(
                pauses.stream()
                        .anyMatch(p -> p.reason() != null && p.reason().startsWith("concurrency limit 2 detected")),
                "an explicit concurrency adaptation notice must be emitted, got: " + pauses);
    }

    @Test
    void concurrencyLimitDuplicatedWhileGateActiveEmitsNoticeOnce() {
        // a 429 storm while the pool is already capped at the announced limit must not
        // re-emit the "capping concurrent requests" notice on every occurrence
        final var dial = new ScriptedDialect(
                new IOException("LLM request failed (429): {\"error\":{\"code\":\"concurrency_limit_exceeded\","
                        + "\"message\":\"Too many concurrent requests\",\"limit\":2}}"));
        final var pauses = new CopyOnWriteArrayList<PauseEvent>();
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 4, 0.0001d), pauses::add);

        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());
        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());

        assertEquals(
                1,
                pauses.stream()
                        .filter(p -> p.reason() != null && p.reason().startsWith("concurrency limit 2 detected"))
                        .count(),
                "the adaptation notice must be emitted once per provider+limit while the gate is active, got: "
                        + pauses);
    }

    @Test
    void retryAfterSecondsReadsTheTypedHeader() {
        assertEquals(7L, LlmClientResilienceWrapper.retryAfterSeconds(new LlmHttpFailureException(429, "boom", "7")));
        assertNull(LlmClientResilienceWrapper.retryAfterSeconds(new LlmHttpFailureException(429, "boom", null)));
    }

    @Test
    void typedFailuresAreClassifiedFromTheStatusWithoutMessageParsing() {
        final var typed429 = new LlmHttpFailureException(429, "boom", null);
        final var typed400 = new LlmHttpFailureException(400, "boom", null);
        final var dial = new ScriptedDialect(typed429);
        final var wrapper = new LlmClientResilienceWrapper(config(2, 0, 4), null);
        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());
        assertEquals(2, dial.calls.get(), "typed 429 must be retried");

        final var dial400 = new ScriptedDialect(typed400);
        final var wrapper400 = new LlmClientResilienceWrapper(config(3, 0, 4), null);
        assertThrows(
                CompletionException.class,
                () -> wrapper400
                        .chat(dial400, null, () -> false)
                        .toCompletableFuture()
                        .join());
        assertEquals(1, dial400.calls.get(), "typed 400 must not be retried");
    }

    @Test
    void usageLimitReachedWithLongWindowIsNotRetried() {
        final var body = "{\"error\":{\"type\":\"usage_limit_reached\",\"limit_window_minutes\":43200,"
                + "\"resets_in_seconds\":2500000,\"resets_at\":9999999999}}";
        final var dial = new ScriptedDialect(new LlmHttpFailureException(429, body, null));
        final var wrapper = new LlmClientResilienceWrapper(
                config(3, 0, 4), null, null, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new MiniJsonMapper());

        final var failure = assertThrows(
                CompletionException.class,
                () -> wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertTrue(
                failure.getCause() instanceof LlmHttpFailureException
                        && failure.getCause().getMessage().contains("usage_limit_reached"),
                "got: " + failure.getCause());
        assertEquals(1, dial.calls.get(), "a long usage-limit window must not be retried");
    }

    @Test
    void usageLimitReachedWithDistantResetIsNotRetried() {
        // no limit_window_minutes, but resets_at is epochs beyond the 1h threshold (1h after epoch now)
        final var body =
                "{\"error\":{\"type\":\"usage_limit_reached\",\"resets_in_seconds\":3600000," + "\"resets_at\":7200}}";
        final var dial = new ScriptedDialect(new LlmHttpFailureException(429, body, null));
        final var wrapper = new LlmClientResilienceWrapper(
                config(3, 0, 4), null, null, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new MiniJsonMapper());

        final var failure = assertThrows(
                CompletionException.class,
                () -> wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertEquals(1, dial.calls.get(), "a resets_at beyond the threshold must not be retried");
    }

    @Test
    void usageLimitReachedWithShortWindowIsRetried() {
        // resets_at 30s after now: within the 1h threshold, must keep the normal retry behavior
        final var body = "{\"error\":{\"type\":\"usage_limit_reached\",\"resets_in_seconds\":30,\"resets_at\":30}}";
        final var dial = new ScriptedDialect(
                new LlmHttpFailureException(429, body, null),
                new LlmHttpFailureException(429, body, null),
                new LlmHttpFailureException(429, body, null));
        final var wrapper = new LlmClientResilienceWrapper(
                config(3, 0, 4), null, null, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new MiniJsonMapper());

        assertThrows(
                CompletionException.class,
                () -> wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertEquals(3, dial.calls.get(), "a short usage-limit window must still be retried");
    }

    @Test
    void usageLimitReachedWithUnparseableBodyIsRetried() {
        // unparseable JSON: the soft parse must fail closed (keep the normal retry behavior)
        final var dial = new ScriptedDialect(
                new LlmHttpFailureException(429, "{\"error\": oops-not-json", null),
                new LlmHttpFailureException(429, "{\"error\": oops-not-json", null),
                new LlmHttpFailureException(429, "{\"error\": oops-not-json", null));
        final var wrapper = new LlmClientResilienceWrapper(
                config(3, 0, 4), null, null, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new MiniJsonMapper());

        assertThrows(
                CompletionException.class,
                () -> wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertEquals(3, dial.calls.get(), "an unparseable 429 body must still be retried");
    }

    @Test
    void nonUsageLimit429IsRetriedEvenWithMapper() {
        final var body = "{\"error\":{\"type\":\"rate_limit_exceeded\"}}";
        final var dial = new ScriptedDialect(new LlmHttpFailureException(429, body, null));
        final var wrapper = new LlmClientResilienceWrapper(
                config(2, 0, 4), null, null, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new MiniJsonMapper());

        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join()
                        .text());
        assertEquals(2, dial.calls.get(), "a rate_limit_exceeded 429 must still be retried");
    }

    @Test
    void http400IsNotRetried() {
        final var dial = new ScriptedDialect(new IOException("LLM request failed (400): bad request"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        final var failure = assertThrows(
                CompletionException.class,
                () -> wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertTrue(
                failure.getCause() instanceof IOException
                        && failure.getCause().getMessage().contains("(400): bad request"),
                "got: " + failure.getCause());
        assertEquals(1, dial.calls.get(), "a 400 must not be retried");
    }

    @Test
    void retriesStopAtMaxAttempts() {
        final var dial = new ScriptedDialect(
                new IOException("LLM request failed (500): boom"),
                new IOException("LLM request failed (500): boom"),
                new IOException("LLM request failed (500): boom"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        final var failure = assertThrows(
                CompletionException.class,
                () -> wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertEquals(3, dial.calls.get(), "3 attempts max then give up");
        assertTrue(
                failure.getCause() instanceof IOException
                        && failure.getCause().getMessage().contains("LLM request failed (500): boom"),
                "got: " + failure.getCause());
    }

    @Test
    void unlimitedConcurrencyRunsThrough() throws Exception {
        final var dial = new ScriptedDialect();
        final var wrapper = new LlmClientResilienceWrapper(config(1, 0, -1), null);

        assertEquals(
                "ok",
                wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
                        .text());
        assertEquals(1, dial.calls.get());
    }

    @Test
    void streamSetupTransportFailureIsRetried() throws Exception {
        // a raw transport failure before any fragment (the JDK surfaces it as
        // "HTTP/1.1 header parser received no bytes") is transient: like chat(),
        // the stream setup must be retried instead of surfacing after the first attempt
        final var dial = new ScriptedDialect(new IOException("HTTP/1.1 header parser received no bytes"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        wrapper.stream(dial, null, () -> false).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(2, dial.calls.get(), "a transport stream-setup failure must be retried once");
    }

    @Test
    void streamSetupRetriesStopAtMaxAttempts() {
        final var dial = new ScriptedDialect(
                new IOException("HTTP/1.1 header parser received no bytes"),
                new IOException("HTTP/1.1 header parser received no bytes"),
                new IOException("HTTP/1.1 header parser received no bytes"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        final var failure = assertThrows(
                CompletionException.class,
                () -> wrapper.stream(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertEquals(3, dial.calls.get(), "3 attempts max then give up");
        assertTrue(
                failure.getCause() instanceof IOException
                        && failure.getCause().getMessage().contains("header parser received no bytes"),
                "got: " + failure.getCause());
    }

    @Test
    void streamSetupHttp400IsNotRetried() {
        final var dial = new ScriptedDialect(new IOException("LLM request failed (400): bad request"));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        final var failure = assertThrows(
                CompletionException.class,
                () -> wrapper.stream(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertEquals(1, dial.calls.get(), "a 400 must not be retried");
        assertTrue(
                failure.getCause() instanceof IOException
                        && failure.getCause().getMessage().contains("(400): bad request"),
                "got: " + failure.getCause());
    }

    @Test
    void streamSetupTyped400IsNotRetried() {
        final var dial = new ScriptedDialect(new LlmHttpFailureException(400, "boom", null));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);

        final var failure = assertThrows(
                CompletionException.class,
                () -> wrapper.stream(dial, null, () -> false)
                        .toCompletableFuture()
                        .join());

        assertEquals(1, dial.calls.get(), "a typed 400 must not be retried");
        assertInstanceOf(LlmHttpFailureException.class, failure.getCause(), "got: " + failure.getCause());
    }

    @Test
    void cancelDuringRetryBackoffAbortsTheWaitWithoutReRequesting() throws Exception {
        final var dial = new ScriptedDialect(new LlmHttpFailureException(429, "boom", "30")); // 30s retry-after
        try (final var scheduler = cancellationScheduler()) {
            final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null, scheduler);
            final var cancelled = new AtomicBoolean();
            final var future = wrapper.chat(dial, null, cancelled::get);
            // flip the probe far before the 30s wait elapses: the scheduler poll ends the wait early
            cancelled.set(true);
            final var failure = assertThrows(
                    ExecutionException.class, () -> future.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(
                    failure.getCause() instanceof IOException
                            && "Cancelled".equals(failure.getCause().getMessage()),
                    "got: " + failure.getCause());
            assertEquals(1, dial.calls.get(), "the cancelled wait must not re-request");
        }
    }

    @Test
    void cancelDuringStreamSetupBackoffAbortsTheWaitWithoutReRequesting() throws Exception {
        final var dial = new ScriptedDialect(new LlmHttpFailureException(429, "boom", "30"));
        try (final var scheduler = cancellationScheduler()) {
            final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null, scheduler);
            final var cancelled = new AtomicBoolean();
            final var future = wrapper.stream(dial, null, cancelled::get);
            cancelled.set(true);
            final var failure = assertThrows(
                    ExecutionException.class, () -> future.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(
                    failure.getCause() instanceof IOException
                            && "Cancelled".equals(failure.getCause().getMessage()),
                    "got: " + failure.getCause());
            assertEquals(1, dial.calls.get(), "the cancelled stream-setup wait must not re-request");
        }
    }

    private static ScheduledThreadPoolExecutor cancellationScheduler() {
        final var scheduler = new ScheduledThreadPoolExecutor(1);
        // The assertions must complete before teardown. Do not drain the 30s retry timer on close,
        // including when a failed cancellation assertion leaves the timer pending.
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Test
    void cancelAlreadySetWhenFailureLandsSkipsTheWait() throws Exception {
        final var cancelled = new AtomicBoolean();
        final var calls = new AtomicInteger();
        // the abort lands while the failed attempt is being handled (not before: an already
        // cancelled turn never reaches the provider, see LlmClientResilienceWrapperCancellationTest)
        final Dialect dial = new ScriptedDialect(new LlmHttpFailureException(429, "boom", "30")) {
            @Override
            public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier probe) {
                calls.incrementAndGet();
                cancelled.set(true);
                return super.chat(request, probe);
            }
        };
        try (final var scheduler = Executors.newScheduledThreadPool(1)) {
            final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null, scheduler);
            final var failure = assertThrows(
                    ExecutionException.class,
                    () -> wrapper.chat(dial, null, cancelled::get)
                            .toCompletableFuture()
                            .get(2, TimeUnit.SECONDS));
            assertTrue(
                    failure.getCause() instanceof IOException
                            && "Cancelled".equals(failure.getCause().getMessage()),
                    "got: " + failure.getCause());
            assertEquals(1, calls.get(), "a canceled turn must not retry");
        }
    }

    @Test
    void cancelAlreadySetWhenFailureLandsSkipsTheWaitWithoutInjectedScheduler() throws Exception {
        // regression: without an injected scheduler the wait is a plain delayed executor and
        // the probe is not polled mid-wait, so an already-set cancel used to park the whole
        // retry-after (30s) before the post-wait check could see it
        final var cancelled = new AtomicBoolean();
        final var calls = new AtomicInteger();
        final Dialect dial = new ScriptedDialect(new LlmHttpFailureException(429, "boom", "30")) {
            @Override
            public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier probe) {
                calls.incrementAndGet();
                cancelled.set(true);
                return super.chat(request, probe);
            }
        };
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);
        final var failure = assertThrows(
                ExecutionException.class,
                () -> wrapper.chat(dial, null, cancelled::get)
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS));
        assertTrue(
                failure.getCause() instanceof IOException
                        && "Cancelled".equals(failure.getCause().getMessage()),
                "got: " + failure.getCause());
        assertEquals(1, calls.get(), "a canceled turn must not retry");
    }

    @Test
    void skipRetriesSurfacesTheFailureAfterExactlyOneAttempt() throws Exception {
        // a cooperative caller (the compaction coordinator) owns the retry loop: the wrapper must
        // keep acquiring the permit but perform exactly ONE attempt on a retryable 5xx instead of
        // the configured 3.
        final var dial = new ScriptedDialect(new LlmHttpFailureException(500, "boom", null));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);
        final var failure = assertThrows(
                ExecutionException.class,
                () -> wrapper.chat(dial, null, () -> false, null, true)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
        assertInstanceOf(LlmHttpFailureException.class, failure.getCause(), "got: " + failure.getCause());
        assertEquals(1, dial.calls.get(), "skipRetries must surface the failure immediately, without wrapper retries");
    }

    @Test
    void withoutSkipRetriesTheWrapperRetriesUpToTheConfiguredBudget() throws Exception {
        // control case: an always-failing retryable 5xx with the same config is retried by the
        // wrapper itself (3 attempts) when the caller does not own the retry loop.
        final var dial = new ScriptedDialect(
                new LlmHttpFailureException(500, "boom", null),
                new LlmHttpFailureException(500, "boom", null),
                new LlmHttpFailureException(500, "boom", null));
        final var wrapper = new LlmClientResilienceWrapper(config(3, 0, 4), null);
        final var failure = assertThrows(
                ExecutionException.class,
                () -> wrapper.chat(dial, null, () -> false)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
        assertInstanceOf(LlmHttpFailureException.class, failure.getCause(), "got: " + failure.getCause());
        assertEquals(
                3,
                dial.calls.get(),
                "without skipRetries the wrapper must burn its own 3-attempt retry budget, got " + dial.calls);
    }

    @Test
    void skipRetriesStillAppliesTheConcurrencyPermit() throws Exception {
        // even with skipRetries=true the wrapper is not a pass-through: the concurrency permit
        // (maxConcurrent=1) serializes two cooperative calls, proving the throttle loop still runs.
        final var entered = new AtomicInteger();
        final var firstStarted = new CountDownLatch(1);
        final var pauseSeen = new CountDownLatch(1);
        final var release = new CompletableFuture<LlmResponse>();
        final var dial = new ScriptedDialect() {
            @Override
            public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
                final var call = entered.incrementAndGet();
                if (call == 1) {
                    firstStarted.countDown();
                    return release;
                }
                return CompletableFuture.completedFuture(response());
            }
        };
        final var wrapper = new LlmClientResilienceWrapper(config(1, 0, 1), pause -> {
            if (pause.reason() != null && pause.reason().contains("concurrency limit reached")) {
                pauseSeen.countDown();
            }
        });
        final var first = wrapper.chat(dial, null, () -> false, null, true);
        try {
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "first request must start");
            final var second = wrapper.chat(dial, null, () -> false, null, true).toCompletableFuture();
            assertTrue(pauseSeen.await(5, TimeUnit.SECONDS), "the second cooperative call must reach the permit gate");
            assertEquals(
                    1,
                    entered.get(),
                    "the second cooperative call must block on the concurrency permit, not enter the dialect");
            release.complete(response());
            assertEquals(
                    "ok", first.toCompletableFuture().get(5, TimeUnit.SECONDS).text());
            assertEquals("ok", second.get(5, TimeUnit.SECONDS).text());
            assertEquals(2, entered.get(), "both cooperative calls must eventually run once the permit is released");
        } finally {
            release.complete(response());
        }
    }

    private static LlmResponse response() {
        return new LlmResponse(
                "id",
                "model",
                0L,
                List.of(new LlmResponse.LlmChoice(
                        0, new LlmMessage("assistant", "ok", null, null, null, null), "stop")),
                null,
                null);
    }

    /**
     * Minimal {@link JsonMapper} test double: parses the tiny Codex-style error body
     * {@code {"error":{"key":value,"key":number}}} into a {@code Map<String,Object>}. Covers only
     * what the resilience wrapper reads (type, limit_window_minutes, resets_at); anything else
     * throws so the soft-parse "ignore the body" path can be exercised.
     */
    @SuppressWarnings("unchecked")
    private static final class MiniJsonMapper implements JsonMapper {
        @Override
        public <A> A fromString(final Class<A> type, final String body) {
            return (A) parseBody(body);
        }

        @Override
        public <A> A fromString(final Type type, final String body) {
            return (A) parseBody(body);
        }

        @Override
        public <A> A fromBytes(final Class<A> type, final byte[] bytes) {
            return (A) parseBody(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public <A> A fromBytes(final Type type, final byte[] bytes) {
            return (A) parseBody(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public <A> A read(final Type type, final Reader reader) {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public <A> A read(final Class<A> type, final Reader reader) {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public <A> String toString(final A value) {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public <A> void write(final A value, final Writer writer) {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public <A> byte[] toBytes(final A value) {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public void close() {
            // no-op
        }

        private static Object parseBody(final String source) {
            final var map = new LinkedHashMap<String, Object>();
            var i = source.indexOf('{');
            while ((i = source.indexOf('"', i)) >= 0) {
                final var keyEnd = source.indexOf('"', i + 1);
                if (keyEnd < 0) {
                    break;
                }
                final var key = source.substring(i + 1, keyEnd);
                final var colon = source.indexOf(':', keyEnd);
                if (colon < 0) {
                    break;
                }
                var v = colon + 1;
                while (v < source.length() && Character.isWhitespace(source.charAt(v))) {
                    v++;
                }
                map.put(key, parseValue(source, v));
                i = colon + 1;
            }
            return map;
        }

        private static Object parseValue(final String source, final int start) {
            if (start >= source.length()) {
                throw new IllegalArgumentException("empty value");
            }
            final var c = source.charAt(start);
            if (c == '"') {
                final var end = source.indexOf('"', start + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated string");
                }
                return source.substring(start + 1, end);
            }
            if (c == '{') {
                return parseBody(source.substring(start));
            }
            // number: consume digits (and optional sign)
            int end = start;
            while (end < source.length()
                    && (Character.isDigit(source.charAt(end))
                            || source.charAt(end) == '-'
                            || source.charAt(end) == '.')) {
                end++;
            }
            return Long.parseLong(source.substring(start, end));
        }
    }

    private static class ScriptedDialect implements Dialect {
        private final Throwable[] failures;
        private final AtomicInteger calls = new AtomicInteger();

        private ScriptedDialect(final Throwable... failures) {
            this.failures = failures;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request) {
            return chat(request, null);
        }

        @Override
        public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
            final var call = calls.getAndIncrement();
            return call < failures.length ? failed(failures[call]) : CompletableFuture.completedFuture(response());
        }

        @Override
        public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
                final LlmRequest request, final BooleanSupplier cancelled) {
            final var call = calls.getAndIncrement();
            if (call < failures.length) {
                return failed(failures[call]);
            }
            // success: an SSE stream of a single "ok" fragment then end
            final var buffers = ByteBuffer.wrap(
                    "data: {\"id\":\"1\",\"model\":\"m\",\"choices\":[]}\n\n".getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(Streams.toEvents(
                    subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(final long n) {
                            subscriber.onNext(List.of(buffers));
                            subscriber.onComplete();
                        }

                        @Override
                        public void cancel() {
                            // no-op
                        }
                    }),
                    () -> false,
                    event -> List.of(new LlmStreamEvent.TextDelta("ok")),
                    () -> new LlmStreamEvent.End("stop", null)));
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
