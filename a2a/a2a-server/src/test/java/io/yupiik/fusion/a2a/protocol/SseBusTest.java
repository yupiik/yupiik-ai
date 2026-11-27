/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.protocol;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The event stream body: its framing, that publishing before anyone reads loses nothing, and that it completes.
 */
class SseBusTest {
    @Test
    void framing() {
        final var closed = new AtomicInteger();
        final var bus = new SseBus(closed::incrementAndGet);

        // published before the HTTP layer subscribes: the events must still be written, in order
        bus.publish("{\"a\":1}");
        bus.publish("{\"b\":2}");

        final var subscriber = new CollectingSubscriber();
        bus.subscribe(subscriber);
        bus.close();

        assertEquals("""
                        id: 1
                        event: message
                        data: {"a":1}

                        id: 2
                        event: message
                        data: {"b":2}

                        """, subscriber.text());
        assertTrue(subscriber.completed.get(), "the stream must complete so the response ends");
        assertEquals(1, closed.get(), "the stream must be deregistered exactly once");
    }

    @Test
    void keepAliveIsAComment() {
        final var bus = new SseBus(() -> {});
        final var subscriber = new CollectingSubscriber();
        bus.subscribe(subscriber);
        bus.keepAlive();

        // a comment commits the response without being an event a client would try to parse
        assertEquals(": ping\n\n", subscriber.text());
        assertFalse(subscriber.completed.get());
    }

    @Test
    void closingDoesNotDropWhatIsNotWrittenYet() {
        final var bus = new SseBus(() -> {});
        final var subscriber = new CollectingSubscriber();
        subscriber.autoRequest = false; // the HTTP layer has not asked for the next event yet
        bus.subscribe(subscriber);

        bus.publish("{\"artifact\":1}");
        bus.publish("{\"state\":\"COMPLETED\"}");
        bus.close();

        // only the first event could be written, and the stream must not be over: a terminal status is published right
        // before closing, dropping it would leave the caller waiting forever
        assertTrue(subscriber.text().contains("artifact"), subscriber::text);
        assertFalse(subscriber.text().contains("COMPLETED"), subscriber::text);
        assertFalse(subscriber.completed.get(), "the queued events are still owed to the client");

        subscriber.subscription.request(Long.MAX_VALUE);
        assertTrue(subscriber.text().contains("COMPLETED"), subscriber::text);
        assertTrue(subscriber.completed.get(), "and then it completes");
    }

    @Test
    void publishingOnAClosedBusIsIgnored() {
        final var bus = new SseBus(() -> {});
        final var subscriber = new CollectingSubscriber();
        bus.subscribe(subscriber);
        bus.close();

        assertEquals(-1, bus.publish("{\"late\":true}"));
        assertTrue(bus.isClosed());
        assertEquals("", subscriber.text());
        assertTrue(subscriber.completed.get());
    }

    @Test
    void closingTwiceCompletesOnlyOnce() {
        final var closed = new AtomicInteger();
        final var bus = new SseBus(closed::incrementAndGet);
        final var subscriber = new CollectingSubscriber();
        bus.subscribe(subscriber);

        bus.close();
        bus.close();

        // the HTTP layer must not be told twice that the response is over
        assertEquals(1, closed.get());
        assertEquals(1, subscriber.completions.get());
    }

    @Test
    void subscribingToABusWhichIsAlreadyOverCompletesRightAway() {
        final var closed = new AtomicInteger();
        final var bus = new SseBus(closed::incrementAndGet);
        bus.close();

        final var subscriber = new CollectingSubscriber();
        bus.subscribe(subscriber);

        // the task ended before the HTTP layer got round to reading: the response has to be terminated, not left open
        assertTrue(subscriber.completed.get());
        assertEquals("", subscriber.text());
        assertEquals(1, closed.get());
    }

    @Test
    void concurrentPublishersLoseNothing() throws Exception {
        final var closed = new AtomicInteger();
        final var bus = new SseBus(closed::incrementAndGet);
        final var subscriber = new CollectingSubscriber();
        bus.subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);

        // several executors reporting on the same task at once: the frames are a concurrent deque, so the drain has to
        // cope with another thread having taken the one it just saw
        final var threads = 8;
        final var perThread = 200;
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final var id = t;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        bus.publish("{\"t\":" + id + ",\"i\":" + i + "}");
                    }
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "the publishers did not finish");
        bus.close();

        assertEquals(threads * perThread, subscriber.chunks.size(), "every event is owed to the client exactly once");
        assertTrue(subscriber.completed.get());
    }

    @Test
    void cancellingWhileEventsAreBeingWrittenIsSafe() throws Exception {
        // a client going away in the middle of a burst: the write and the cancel race, and neither may throw
        for (int round = 0; round < 200; round++) {
            final var bus = new SseBus(() -> {});
            final var subscriber = new CollectingSubscriber();
            bus.subscribe(subscriber);
            subscriber.subscription.request(Long.MAX_VALUE);

            final var start = new CountDownLatch(1);
            final var done = new CountDownLatch(2);
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        bus.publish("{\"i\":" + i + "}");
                    }
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    subscriber.subscription.cancel();
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            start.countDown();

            assertTrue(done.await(30, TimeUnit.SECONDS), "the race did not settle");
            assertTrue(bus.isClosed(), "a canceled subscription ends the stream");
        }
    }

    @Test
    void keepAliveOnAClosedBusIsIgnored() {
        final var bus = new SseBus(() -> {});
        final var subscriber = new CollectingSubscriber();
        bus.subscribe(subscriber);
        bus.close();

        bus.keepAlive();

        assertEquals("", subscriber.text());
    }

    @Test
    void aSecondSubscriberTakesOver() {
        final var closed = new AtomicInteger();
        final var bus = new SseBus(closed::incrementAndGet);
        final var first = new CollectingSubscriber();
        final var second = new CollectingSubscriber();

        bus.subscribe(first);
        bus.subscribe(second);
        bus.publish("{\"a\":1}");

        // at most one HTTP response per stream: the last one to connect wins and the first is completed
        assertTrue(first.completed.get(), "the replaced subscriber must be released");
        assertEquals("", first.text());
        assertTrue(second.text().contains("{\"a\":1}"), second::text);
        assertEquals(1, closed.get());
    }

    @Test
    void cancellingEndsTheStream() {
        final var closed = new AtomicInteger();
        final var bus = new SseBus(closed::incrementAndGet);
        final var subscriber = new CollectingSubscriber();
        bus.subscribe(subscriber);

        subscriber.subscription.cancel();

        // the client went away: the stream is over and the task must stop publishing to it
        assertTrue(bus.isClosed());
        assertEquals(1, closed.get());
        assertEquals(-1, bus.publish("{\"late\":true}"));
        assertFalse(subscriber.completed.get(), "a canceled subscription must not be completed on top");
    }

    @Test
    void anInvalidRequestIsReportedToTheSubscriber() {
        final var bus = new SseBus(() -> {});
        final var subscriber = new CollectingSubscriber();
        subscriber.autoRequest = false;
        bus.subscribe(subscriber);

        subscriber.subscription.request(0);
        assertTrue(
                subscriber.error.get() instanceof IllegalArgumentException,
                () -> String.valueOf(subscriber.error.get()));

        subscriber.subscription.request(-1);
        assertTrue(subscriber.error.get() instanceof IllegalArgumentException);
    }

    @Test
    void unboundedDemandDoesNotOverflow() {
        final var bus = new SseBus(() -> {});
        final var subscriber = new CollectingSubscriber();
        subscriber.autoRequest = false;
        bus.subscribe(subscriber);

        // Long.MAX_VALUE means "no limit", and adding to it - or overflowing into it - must stay unbounded
        subscriber.subscription.request(Long.MAX_VALUE);
        subscriber.subscription.request(1);
        subscriber.subscription.request(Long.MAX_VALUE);
        for (int i = 0; i < 3; i++) {
            bus.publish("{\"i\":" + i + "}");
        }

        assertEquals(3, subscriber.chunks.size(), subscriber::text);
    }

    @Test
    void demandOverflowsToUnbounded() {
        final var bus = new SseBus(() -> {});
        final var subscriber = new CollectingSubscriber();
        subscriber.autoRequest = false;
        bus.subscribe(subscriber);

        final var half = Long.MAX_VALUE / 2 + 10;
        subscriber.subscription.request(half);
        subscriber.subscription.request(half); // the sum wraps, it must saturate to unbounded instead
        bus.publish("{\"a\":1}");

        assertEquals(1, subscriber.chunks.size(), subscriber::text);
    }

    @Test
    void aFailingSubscriberIsDropped() {
        final var closed = new AtomicInteger();
        final var bus = new SseBus(closed::incrementAndGet);
        final var subscriber = new ThrowingSubscriber();
        bus.subscribe(subscriber);

        bus.publish("{\"a\":1}");

        // the HTTP layer broke - the client is gone - so the stream is forgotten and the failure reported
        assertTrue(
                subscriber.error.get() instanceof IllegalStateException, () -> String.valueOf(subscriber.error.get()));
        bus.publish("{\"b\":2}"); // nothing to write to anymore, and it must not throw
        bus.close();
        assertEquals(0, closed.get(), "the subscriber was already released, there is nothing left to complete");
    }

    @Test
    void aSubscriberFailingOnBothCallbacksIsStillDropped() {
        final var bus = new SseBus(() -> {});
        final var subscriber = new ThrowingSubscriber();
        subscriber.failOnError = true;
        bus.subscribe(subscriber);

        // a subscriber throwing from onError too must not propagate out of publish
        bus.publish("{\"a\":1}");
    }

    @Test
    void aSubscriberFailingOnCompleteIsStillReleased() {
        final var closed = new AtomicInteger();
        final var bus = new SseBus(closed::incrementAndGet);
        final var subscriber = new ThrowingSubscriber();
        subscriber.failOnNext = false;
        subscriber.failOnComplete = true;
        bus.subscribe(subscriber);

        bus.close();

        assertEquals(1, closed.get(), "the stream must be deregistered even when the subscriber throws on completion");
    }

    @Test
    void queuedEventsAreVisibleBeforeTheyAreWritten() {
        final var bus = new SseBus(() -> {});

        bus.publish("{\"a\":1}");
        bus.keepAlive();
        bus.publish("{\"b\":2}");

        // the keep-alive comment is not an event, so it is not part of what is owed to the client
        assertEquals(List.of("{\"a\":1}", "{\"b\":2}"), bus.queued());
    }

    @Test
    void eventIdentifiersAreSequential() {
        final var bus = new SseBus(() -> {});

        assertEquals(1, bus.publish("{\"a\":1}"));
        assertEquals(2, bus.publish("{\"b\":2}"));
        bus.keepAlive();
        assertEquals(3, bus.publish("{\"c\":3}"), "a comment does not consume an identifier");
    }

    @Test
    void dropsAreTraced() {
        final var logger = Logger.getLogger(SseBus.class.getName());
        final var level = logger.getLevel();
        logger.setLevel(Level.FINEST); // the drop is logged at FINEST, so the message is only built when asked for
        try {
            final var bus = new SseBus(() -> {});
            bus.close();

            assertEquals(-1, bus.publish("{\"late\":true}"));
        } finally {
            logger.setLevel(level);
        }
    }

    private static class CollectingSubscriber implements Flow.Subscriber<ByteBuffer> {
        final List<String> chunks = new ArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicInteger completions = new AtomicInteger();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        Flow.Subscription subscription;
        boolean autoRequest = true;

        String text() {
            return String.join("", chunks);
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(final ByteBuffer item) {
            chunks.add(UTF_8.decode(item).toString());
            if (autoRequest) {
                subscription.request(1);
            }
        }

        @Override
        public void onError(final Throwable throwable) {
            error.set(throwable);
        }

        @Override
        public void onComplete() {
            completed.set(true);
            completions.incrementAndGet();
        }
    }

    /**
     * What a broken HTTP layer looks like: the container aborted the response and the write throws.
     */
    private static final class ThrowingSubscriber extends CollectingSubscriber {
        private boolean failOnNext = true;
        private boolean failOnError;
        private boolean failOnComplete;

        @Override
        public void onNext(final ByteBuffer item) {
            if (failOnNext) {
                throw new IllegalStateException("the client is gone");
            }
            super.onNext(item);
        }

        @Override
        public void onError(final Throwable throwable) {
            super.onError(throwable);
            if (failOnError) {
                throw new IllegalStateException("and it cannot even be told about it");
            }
        }

        @Override
        public void onComplete() {
            super.onComplete();
            if (failOnComplete) {
                throw new IllegalStateException("nor that it is over");
            }
        }
    }
}
