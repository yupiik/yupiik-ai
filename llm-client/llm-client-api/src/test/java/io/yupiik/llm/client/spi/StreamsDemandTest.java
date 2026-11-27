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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.yupiik.llm.client.model.LlmStreamEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StreamsDemandTest {
    @Test
    void demandIsMeasuredInEventsIncludingEnd() {
        final var requests = new AtomicInteger();
        final var cancellations = new AtomicInteger();
        final var receiver = new Receiver();
        Streams.toEvents(
                        source("data: first\n\ndata: second\n\n", requests, cancellations),
                        () -> false,
                        event -> List.of(
                                new LlmStreamEvent.TextDelta(event.data()), new LlmStreamEvent.TextDelta("extra")),
                        () -> new LlmStreamEvent.End("stop", null))
                .subscribe(receiver);
        assertEquals(0, requests.get());
        assertEquals(0, receiver.events.size());
        for (int count = 1; count <= 5; count++) {
            receiver.subscription.request(1);
            assertEquals(count, receiver.events.size());
            assertEquals(count == 5 ? 1 : 0, receiver.completed);
        }
        assertEquals(1, requests.get());
        assertEquals(0, cancellations.get());
    }

    @Test
    void reentrantRequestsDoNotRecurseIntoTheTransport() {
        final var requests = new AtomicInteger();
        final var receiver = new Receiver();
        receiver.reentrant = true;
        Streams.toEvents(
                        source("data: a\n\n".repeat(10000), requests, new AtomicInteger()),
                        () -> false,
                        event -> List.of(new LlmStreamEvent.TextDelta(event.data())),
                        () -> null)
                .subscribe(receiver);
        receiver.subscription.request(1);
        assertEquals(10000, receiver.events.size());
        assertEquals(1, receiver.completed);
    }

    @Test
    void invalidDemandTerminatesWithoutDemand() {
        final var cancellations = new AtomicInteger();
        final var receiver = new Receiver();
        Streams.toEvents(source("", new AtomicInteger(), cancellations), () -> false, event -> List.of(), () -> null)
                .subscribe(receiver);
        receiver.subscription.request(0);
        receiver.subscription.request(-1);
        assertEquals(1, cancellations.get());
        assertEquals(1, receiver.errors.size());
        assertInstanceOf(IllegalArgumentException.class, receiver.errors.getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {"line", "event", "input", "mapped"})
    void limitsCancelAndFailOnce(final String kind) {
        final var limits = new Streams.Limits(
                "line".equals(kind) ? 8 : 128,
                "event".equals(kind) ? 4 : 128,
                "input".equals(kind) ? 8 : 256,
                "mapped".equals(kind) ? 1 : 4);
        final var cancellations = new AtomicInteger();
        final var receiver = new Receiver();
        Streams.toEvents(
                        source("data: abc\ndata: def\n\n", new AtomicInteger(), cancellations),
                        () -> false,
                        event -> List.of(new LlmStreamEvent.TextDelta("a"), new LlmStreamEvent.TextDelta("b")),
                        () -> null,
                        null,
                        limits)
                .subscribe(receiver);
        receiver.subscription.request(Long.MAX_VALUE);
        assertEquals(1, cancellations.get());
        assertEquals(1, receiver.errors.size());
        assertInstanceOf(Streams.LimitExceededException.class, receiver.errors.getFirst());
        assertEquals(0, receiver.completed);
    }

    @Test
    void concurrentRequestsAreSerializedAndCounted() throws Exception {
        final var receiver = new Receiver();
        Streams.toEvents(
                        source("data: a\n\n".repeat(800), new AtomicInteger(), new AtomicInteger()),
                        () -> false,
                        event -> List.of(new LlmStreamEvent.TextDelta(event.data())),
                        () -> null)
                .subscribe(receiver);
        try (final var executor = Executors.newFixedThreadPool(8)) {
            final var futures = new ArrayList<Future<?>>();
            for (int thread = 0; thread < 8; thread++) {
                futures.add(executor.submit(() -> {
                    for (int request = 0; request < 100; request++) {
                        receiver.subscription.request(1);
                    }
                }));
            }
            for (final var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(800, receiver.events.size());
        assertEquals(1, receiver.completed);
        assertEquals(List.of(), receiver.errors);
    }

    @Test
    void emptyMappingsDoNotConsumeDemandOrRecurse() {
        final Flow.Publisher<List<ByteBuffer>> source = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private int remaining = 10000;

            @Override
            public void request(final long count) {
                assertEquals(1, count);
                subscriber.onNext(List.of(ByteBuffer.wrap("data: ignored\n\n".getBytes(StandardCharsets.UTF_8))));
                if (--remaining == 0) {
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {}
        });
        final var receiver = new Receiver();
        Streams.toEvents(source, () -> false, event -> List.of(), () -> new LlmStreamEvent.End("stop", null))
                .subscribe(receiver);
        receiver.subscription.request(1);
        assertEquals(List.of(new LlmStreamEvent.End("stop", null)), receiver.events);
        assertEquals(1, receiver.completed);
    }

    private Flow.Publisher<List<ByteBuffer>> source(
            final String text, final AtomicInteger requests, final AtomicInteger cancellations) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean sent;

            @Override
            public void request(final long count) {
                requests.incrementAndGet();
                assertEquals(1, count);
                if (!sent) {
                    sent = true;
                    subscriber.onNext(List.of(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8))));
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                cancellations.incrementAndGet();
            }
        });
    }

    private static final class Receiver implements Flow.Subscriber<LlmStreamEvent> {
        private final List<LlmStreamEvent> events = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();
        private Flow.Subscription subscription;
        private int completed;
        private boolean reentrant;

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(final LlmStreamEvent event) {
            events.add(event);
            if (reentrant) {
                subscription.request(1);
            }
        }

        @Override
        public void onError(final Throwable error) {
            errors.add(error);
        }

        @Override
        public void onComplete() {
            completed++;
        }
    }
}
