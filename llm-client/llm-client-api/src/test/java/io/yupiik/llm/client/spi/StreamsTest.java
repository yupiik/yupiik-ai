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
import static org.junit.jupiter.api.Assertions.assertSame;

import io.yupiik.llm.client.model.LlmStreamEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StreamsTest {

    @ParameterizedTest
    @ValueSource(strings = {"mapper", "raw", "end", "probe", "subscriber"})
    void callbackFailuresCancelTheSourceAndTerminateOnce(final String stage) {
        final var failure = new IllegalStateException("callback failed");
        final var cancellations = new AtomicInteger();
        final var errors = new ArrayList<Throwable>();
        final var completions = new AtomicInteger();
        final Flow.Publisher<List<ByteBuffer>> source = subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(final long n) {}

                @Override
                public void cancel() {
                    cancellations.incrementAndGet();
                }
            });
            subscriber.onNext(List.of(ByteBuffer.wrap("data: test\n\n".getBytes(StandardCharsets.UTF_8))));
            subscriber.onComplete();
            subscriber.onError(new IllegalStateException("late error"));
        };
        Streams.toEvents(
                        source,
                        () -> {
                            if ("probe".equals(stage)) {
                                throw failure;
                            }
                            return false;
                        },
                        event -> {
                            if ("mapper".equals(stage)) {
                                throw failure;
                            }
                            return List.of(new LlmStreamEvent.TextDelta(event.data()));
                        },
                        () -> {
                            if ("end".equals(stage)) {
                                throw failure;
                            }
                            return new LlmStreamEvent.End("stop", null);
                        },
                        line -> {
                            if ("raw".equals(stage)) {
                                throw failure;
                            }
                        })
                .subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(final Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(final LlmStreamEvent item) {
                        if ("subscriber".equals(stage)) {
                            throw failure;
                        }
                    }

                    @Override
                    public void onError(final Throwable error) {
                        errors.add(error);
                    }

                    @Override
                    public void onComplete() {
                        completions.incrementAndGet();
                    }
                });
        assertEquals(1, cancellations.get());
        assertEquals(0, completions.get());
        assertEquals("subscriber".equals(stage) ? 0 : 1, errors.size());
        if (!errors.isEmpty()) {
            assertSame(failure, errors.getFirst());
        }
    }

    @Test
    void cooperativeCancellationClosesAnOpenSourceAndCompletesOnlyOnce() {
        final var cancel = new AtomicBoolean();
        final var transportCancelled = new AtomicBoolean();
        final var sourceSubscriber = new AtomicReference<Flow.Subscriber<? super List<ByteBuffer>>>();
        final var events = new ArrayList<LlmStreamEvent>();
        final var completions = new AtomicInteger();
        final var errors = new AtomicInteger();
        final Flow.Publisher<List<ByteBuffer>> source = subscriber -> {
            sourceSubscriber.set(subscriber);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(final long n) {}

                @Override
                public void cancel() {
                    transportCancelled.set(true);
                }
            });
        };
        Streams.toEvents(
                        source,
                        cancel::get,
                        event -> List.of(new LlmStreamEvent.TextDelta(event.data())),
                        () -> new LlmStreamEvent.End("cancelled", null))
                .subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(final Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(final LlmStreamEvent item) {
                        events.add(item);
                    }

                    @Override
                    public void onError(final Throwable error) {
                        errors.incrementAndGet();
                    }

                    @Override
                    public void onComplete() {
                        completions.incrementAndGet();
                    }
                });
        cancel.set(true);
        sourceSubscriber.get().onNext(List.of(ByteBuffer.wrap("data: {}".getBytes(StandardCharsets.UTF_8))));
        assertEquals(true, transportCancelled.get());
        assertEquals(1, completions.get());
        assertEquals(List.of(new LlmStreamEvent.End("cancelled", null)), events);
        sourceSubscriber.get().onComplete();
        sourceSubscriber.get().onError(new IllegalStateException("late transport error"));
        assertEquals(1, completions.get());
        assertEquals(0, errors.get());
    }

    @Test
    void downstreamCancellationDiscardsBufferedEventsWithoutTerminalSignals() {
        final var transportCancelled = new AtomicBoolean();
        final var events = new ArrayList<LlmStreamEvent>();
        final var terminals = new AtomicInteger();
        final Flow.Publisher<List<ByteBuffer>> source = subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(final long n) {}

                @Override
                public void cancel() {
                    transportCancelled.set(true);
                }
            });
            subscriber.onNext(
                    List.of(ByteBuffer.wrap("data: first\n\ndata: second\n\n".getBytes(StandardCharsets.UTF_8))));
            subscriber.onComplete();
        };
        Streams.toEvents(
                        source,
                        () -> false,
                        event -> List.of(
                                new LlmStreamEvent.TextDelta(event.data()), new LlmStreamEvent.TextDelta("extra")),
                        () -> new LlmStreamEvent.End("stop", null))
                .subscribe(new Flow.Subscriber<>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(final Flow.Subscription subscription) {
                        this.subscription = subscription;
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(final LlmStreamEvent item) {
                        events.add(item);
                        subscription.cancel();
                    }

                    @Override
                    public void onError(final Throwable error) {
                        terminals.incrementAndGet();
                    }

                    @Override
                    public void onComplete() {
                        terminals.incrementAndGet();
                    }
                });
        assertEquals(true, transportCancelled.get());
        assertEquals(List.of(new LlmStreamEvent.TextDelta("first")), events);
        assertEquals(0, terminals.get());
    }

    @Test
    void cancellingFromEndSuppressesCompletion() {
        for (final var cooperative : List.of(false, true)) {
            final var events = new ArrayList<LlmStreamEvent>();
            final var terminals = new AtomicInteger();
            final var transportCancelled = new AtomicBoolean();
            final Flow.Publisher<List<ByteBuffer>> source = subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(final long n) {}

                    @Override
                    public void cancel() {
                        transportCancelled.set(true);
                    }
                });
                subscriber.onComplete();
                subscriber.onError(new IllegalStateException("late error"));
            };
            Streams.toEvents(source, () -> cooperative, event -> List.of(), () -> new LlmStreamEvent.End("stop", null))
                    .subscribe(new Flow.Subscriber<>() {
                        private Flow.Subscription subscription;

                        @Override
                        public void onSubscribe(final Flow.Subscription subscription) {
                            this.subscription = subscription;
                            subscription.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(final LlmStreamEvent item) {
                            events.add(item);
                            subscription.cancel();
                        }

                        @Override
                        public void onError(final Throwable error) {
                            terminals.incrementAndGet();
                        }

                        @Override
                        public void onComplete() {
                            terminals.incrementAndGet();
                        }
                    });
            assertEquals(List.of(new LlmStreamEvent.End("stop", null)), events);
            assertEquals(true, transportCancelled.get());
            assertEquals(0, terminals.get(), "cooperative=" + cooperative);
        }
    }

    @Test
    void multiByteUtf8SplitAcrossChunksIsDecodedIntact() throws Exception {
        final var full = ("data: héllo → 世界 ünïcödé ✓\r\n\n").getBytes(StandardCharsets.UTF_8);
        // split the SSE stream at arbitrary byte boundaries, including mid multi-byte sequence
        final var chunks = new ArrayList<List<ByteBuffer>>();
        for (var i = 0; i < full.length; i += 4) {
            final var end = Math.min(i + 4, full.length);
            chunks.add(List.of(ByteBuffer.wrap(Arrays.copyOfRange(full, i, end))));
        }

        final var events = collect(chunks, false);

        assertEquals(
                List.of(new LlmStreamEvent.TextDelta("héllo → 世界 ünïcödé ✓"), new LlmStreamEvent.End("stop", null)),
                events);
    }

    @Test
    void crlfLineEndingsAreTrimmed() throws Exception {
        final var chunks = List.of(
                List.of(ByteBuffer.wrap("data: one\r\n\r\n".getBytes(StandardCharsets.UTF_8))),
                List.of(ByteBuffer.wrap("data: two\r\n\r\n".getBytes(StandardCharsets.UTF_8))));
        final var events = collect(chunks, false);
        assertEquals(
                List.of(
                        new LlmStreamEvent.TextDelta("one"),
                        new LlmStreamEvent.TextDelta("two"),
                        new LlmStreamEvent.End("stop", null)),
                events);
    }

    @Test
    void unterminatedTailIsFedToTheParserWithoutDispatch() throws Exception {
        // SSE events only dispatch on a blank line; an unterminated tail is handed to the parser
        // (no crash, no partial mojibake) and the terminal End is still emitted
        final var chunks = List.of(List.of(ByteBuffer.wrap("data: tail".getBytes(StandardCharsets.UTF_8))));
        final var events = collect(chunks, false);
        assertEquals(List.of(new LlmStreamEvent.End("stop", null)), events);
    }

    private static List<LlmStreamEvent> collect(final List<List<ByteBuffer>> chunks, final boolean cancelled)
            throws InterruptedException {
        final var events = new CopyOnWriteArrayList<LlmStreamEvent>();
        final var done = new CountDownLatch(1);
        final SubmissionPublisher<List<ByteBuffer>> source = new SubmissionPublisher<>();
        Streams.toEvents(
                        source,
                        () -> cancelled,
                        event -> event.data() == null ? List.of() : List.of(new LlmStreamEvent.TextDelta(event.data())),
                        () -> new LlmStreamEvent.End("stop", null))
                .subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(final Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(final LlmStreamEvent item) {
                        events.add(item);
                    }

                    @Override
                    public void onError(final Throwable throwable) {
                        done.countDown();
                    }

                    @Override
                    public void onComplete() {
                        done.countDown();
                    }
                });
        chunks.forEach(source::submit);
        source.close();
        if (!done.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("stream did not complete in time, got: " + events);
        }
        return events;
    }
}
