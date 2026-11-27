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

import io.yupiik.llm.client.model.LlmStreamEvent;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Turns a raw SSE byte-stream {@link Flow.Publisher} into a {@link Flow.Publisher} of
 * {@link LlmStreamEvent}. Each streamed line is fed to a {@link SseParser}; the supplied mapper turns
 * every parsed event into zero or more neutral events, and the supplier provides the terminal End
 * event (or null when the dialect has no surfacing summary).
 *
 * <p>The stream supports cooperative cancellation through the supplied {@link BooleanSupplier}; once
 * it returns true the subscription is cancelled and only an End event is emitted.</p>
 */
public final class Streams {

    private Streams() {
        // no-op utility
    }

    /**
     * Turns a raw SSE byte-stream publisher into a publisher of neutral stream events.
     *
     * @param source the raw SSE byte publisher.
     * @param cancelled cancellation probe checked between frames.
     * @param mapper per-event translation into zero or more neutral events.
     * @param end terminal End event supplier, or null when the dialect has no surfacing summary.
     * @return the publisher of neutral stream events.
     */
    public static Flow.Publisher<LlmStreamEvent> toEvents(
            final Flow.Publisher<List<ByteBuffer>> source,
            final BooleanSupplier cancelled,
            final Function<SseParser.Event, List<LlmStreamEvent>> mapper,
            final Supplier<LlmStreamEvent> end) {
        return toEvents(source, cancelled, mapper, end, null);
    }

    /**
     * Turns a raw SSE byte-stream publisher into a publisher of neutral stream events.
     *
     * @param source the raw SSE byte publisher.
     * @param cancelled cancellation probe checked between frames.
     * @param mapper per-event translation into zero or more neutral events.
     * @param end terminal End event supplier, or null when the dialect has no surfacing summary.
     * @param onRawLine optional tee for every raw SSE line (before parsing), or null; useful to log
     *     the wire, e.g. to observe tool-call fragments on the streaming responses API.
     * @return the publisher of neutral stream events.
     */
    public static Flow.Publisher<LlmStreamEvent> toEvents(
            final Flow.Publisher<List<ByteBuffer>> source,
            final BooleanSupplier cancelled,
            final Function<SseParser.Event, List<LlmStreamEvent>> mapper,
            final Supplier<LlmStreamEvent> end,
            final Consumer<String> onRawLine) {
        return toEvents(source, cancelled, mapper, end, onRawLine, Limits.DEFAULT);
    }

    /**
     * Memory limits for SSE decoding. Defaults allow an 8 MiB raw line, 16 Mi UTF-16
     * payload characters, 32 MiB of buffered transport input, and 4096 mapped events.
     * These are per-subscription limits, not a cap on the total response length.
     *
     * @param lineBytes maximum raw line size before UTF-8 decoding.
     * @param eventCharacters maximum accumulated data payload size in UTF-16 characters.
     * @param bufferedBytes maximum retained transport bytes, including a single input batch.
     * @param mappedEvents maximum pending events returned by a mapper invocation.
     */
    public record Limits(int lineBytes, int eventCharacters, int bufferedBytes, int mappedEvents) {
        public static final Limits DEFAULT = new Limits(8 * 1024 * 1024, 16 * 1024 * 1024, 32 * 1024 * 1024, 4096);

        public Limits {
            if (lineBytes <= 0 || eventCharacters <= 0 || bufferedBytes <= 0 || mappedEvents <= 0) {
                throw new IllegalArgumentException("Stream limits must be positive");
            }
        }
    }

    /** Thrown when a provider stream exceeds a configured decoding limit. */
    public static final class LimitExceededException extends IllegalStateException {
        public LimitExceededException(final String message) {
            super(message);
        }
    }

    /**
     * Converts SSE using explicit finite memory limits. Downstream demand counts mapped events,
     * including the optional final event, rather than transport batches. Only one transport batch
     * is requested at a time; decoding pauses while mapped events await demand. Invalid demand
     * fails the subscription. Limit failures cancel the transport and signal a
     * {@link LimitExceededException} without including provider payloads in its message.
     */
    public static Flow.Publisher<LlmStreamEvent> toEvents(
            final Flow.Publisher<List<ByteBuffer>> source,
            final BooleanSupplier cancelled,
            final Function<SseParser.Event, List<LlmStreamEvent>> mapper,
            final Supplier<LlmStreamEvent> end,
            final Consumer<String> onRawLine,
            final Limits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(limits, "limits");
        return subscriber -> source.subscribe(new Decoder(subscriber, cancelled, mapper, end, onRawLine, limits));
    }

    private static final class Decoder implements Flow.Subscriber<List<ByteBuffer>>, Flow.Subscription {
        private final Flow.Subscriber<? super LlmStreamEvent> downstream;
        private final BooleanSupplier cancelled;
        private final Function<SseParser.Event, List<LlmStreamEvent>> mapper;
        private final Supplier<LlmStreamEvent> end;
        private final Consumer<String> onRawLine;
        private final Limits limits;
        private final SseParser parser;
        private final ConcurrentLinkedQueue<ByteBuffer> input = new ConcurrentLinkedQueue<>();
        private final ArrayDeque<LlmStreamEvent> output = new ArrayDeque<>();
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final AtomicLong demand = new AtomicLong();
        private final AtomicLong buffered = new AtomicLong();
        private final AtomicInteger work = new AtomicInteger();
        private final AtomicBoolean upstreamCancelled = new AtomicBoolean();
        private final AtomicBoolean requested = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile Flow.Subscription upstream;
        private volatile boolean ready;
        private volatile boolean stopped;
        private volatile boolean terminated;
        private volatile boolean done;
        // Only the serialized drain accesses the parser, partial line and output queue.
        private boolean flushed;
        private boolean cooperative;

        private Decoder(
                final Flow.Subscriber<? super LlmStreamEvent> downstream,
                final BooleanSupplier cancelled,
                final Function<SseParser.Event, List<LlmStreamEvent>> mapper,
                final Supplier<LlmStreamEvent> end,
                final Consumer<String> onRawLine,
                final Limits limits) {
            this.downstream = Objects.requireNonNull(downstream, "subscriber");
            this.cancelled = cancelled;
            this.mapper = mapper;
            this.end = end;
            this.onRawLine = onRawLine;
            this.limits = limits;
            this.parser = new SseParser(limits.eventCharacters());
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            if (upstream != null) {
                subscription.cancel();
                return;
            }
            upstream = subscription;
            try {
                downstream.onSubscribe(this);
            } catch (final RuntimeException | Error error) {
                cancel();
                throw error;
            }
            ready = true;
            drain();
        }

        @Override
        public void request(final long count) {
            if (stopped || terminated) {
                return;
            }
            if (count <= 0) {
                fail(new IllegalArgumentException("Demand must be positive"));
                return;
            }
            demand.getAndUpdate(previous -> previous > Long.MAX_VALUE - count ? Long.MAX_VALUE : previous + count);
            drain();
        }

        @Override
        public void cancel() {
            stopped = true;
            try {
                cancelUpstream();
            } finally {
                drain();
            }
        }

        private void cancelUpstream() {
            final var subscription = upstream;
            if (subscription != null && upstreamCancelled.compareAndSet(false, true)) {
                subscription.cancel();
            }
        }

        @Override
        public void onNext(final List<ByteBuffer> buffers) {
            if (stopped || terminated || done) {
                return;
            }
            try {
                long size = 0;
                for (final var buffer : buffers) {
                    size += buffer.remaining();
                    if (size > limits.bufferedBytes()) {
                        throw new LimitExceededException("SSE input batch exceeds buffered byte limit");
                    }
                }
                if (buffered.addAndGet(size) > limits.bufferedBytes()) {
                    buffered.addAndGet(-size);
                    throw new LimitExceededException("SSE input exceeds buffered byte limit");
                }
                for (final var buffer : buffers) {
                    if (buffer.hasRemaining()) {
                        final var copy = new byte[buffer.remaining()];
                        buffer.get(copy);
                        input.add(ByteBuffer.wrap(copy));
                    }
                }
                requested.set(false);
                drain();
            } catch (final RuntimeException error) {
                fail(error);
            }
        }

        @Override
        public void onError(final Throwable error) {
            if (!done && !terminated && !stopped) {
                failure.compareAndSet(null, Objects.requireNonNull(error));
                done = true;
                drain();
            }
        }

        @Override
        public void onComplete() {
            if (!done && !terminated && !stopped) {
                done = true;
                drain();
            }
        }

        private void fail(final RuntimeException error) {
            if (!terminated && !stopped) {
                failure.compareAndSet(null, error);
                try {
                    cancelUpstream();
                } catch (final RuntimeException cancellationFailure) {
                    error.addSuppressed(cancellationFailure);
                }
                drain();
            }
        }

        private void drain() {
            if (!ready || work.getAndIncrement() != 0) {
                return;
            }
            int missed = 1;
            do {
                try {
                    advance();
                } catch (final RuntimeException error) {
                    fail(error);
                } catch (final Error error) {
                    stopped = true;
                    clear();
                    cancelUpstream();
                    throw error;
                }
                missed = work.addAndGet(-missed);
            } while (missed != 0);
        }

        private void advance() {
            while (true) {
                if (stopped || terminated) {
                    clear();
                    return;
                }
                final var error = failure.get();
                if (error != null) {
                    terminated = true;
                    clear();
                    downstream.onError(error);
                    return;
                }
                if (!cooperative && cancelled != null && cancelled.getAsBoolean()) {
                    cooperative = true;
                    cancelUpstream();
                    clear();
                    done = true;
                    finish();
                }
                if (!output.isEmpty()) {
                    if (demand.get() == 0) {
                        return;
                    }
                    demand.getAndUpdate(value -> value == Long.MAX_VALUE ? value : value - 1);
                    final var event = output.removeFirst();
                    try {
                        downstream.onNext(event);
                    } catch (final RuntimeException | Error brokenSubscriber) {
                        stopped = true;
                        clear();
                        cancelUpstream();
                        throw brokenSubscriber;
                    }
                    continue;
                }
                if (input.isEmpty() && done) {
                    if (!flushed) {
                        if (line.size() > 0) {
                            rawLine();
                        }
                        if (parser.hasCompleteJsonPayload()) {
                            map(parser.feed(null));
                        }
                        finish();
                        continue;
                    }
                    terminated = true;
                    clear();
                    downstream.onComplete();
                    return;
                }
                if (demand.get() == 0) {
                    return;
                }
                final var buffer = input.peek();
                if (buffer != null) {
                    while (buffer.hasRemaining() && output.isEmpty()) {
                        final var value = buffer.get();
                        if (value == '\n') {
                            rawLine();
                        } else {
                            if (line.size() >= limits.lineBytes()) {
                                throw new LimitExceededException("SSE line exceeds byte limit");
                            }
                            line.write(value);
                        }
                    }
                    if (!buffer.hasRemaining()) {
                        input.remove();
                        buffered.addAndGet(-buffer.capacity());
                    }
                    continue;
                }
                if (!requested.compareAndSet(false, true)) {
                    return;
                }
                upstream.request(1);
                if (input.isEmpty() && !done && failure.get() == null) {
                    return;
                }
            }
        }

        private void rawLine() {
            var value = line.toString(StandardCharsets.UTF_8);
            line.reset();
            if (value.endsWith("\r")) {
                value = value.substring(0, value.length() - 1);
            }
            if (onRawLine != null) {
                onRawLine.accept(value);
            }
            map(parser.feed(value));
        }

        private void map(final List<SseParser.Event> events) {
            for (final var event : events) {
                final var mapped = mapper.apply(event);
                if (mapped != null) {
                    if ((long) output.size() + mapped.size() > limits.mappedEvents()) {
                        throw new LimitExceededException("SSE mapping exceeds pending event limit");
                    }
                    output.addAll(mapped);
                }
            }
        }

        private void finish() {
            flushed = true;
            final var event = end == null ? null : end.get();
            if (event != null) {
                if (output.size() >= limits.mappedEvents()) {
                    throw new LimitExceededException("SSE mapping exceeds pending event limit");
                }
                output.add(event);
            }
        }

        private void clear() {
            ByteBuffer buffer;
            while ((buffer = input.poll()) != null) {
                buffered.addAndGet(-buffer.capacity());
            }
            line.reset();
            output.clear();
        }
    }
}
