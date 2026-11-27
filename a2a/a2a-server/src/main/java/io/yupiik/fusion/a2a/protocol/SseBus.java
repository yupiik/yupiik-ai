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
import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.SEVERE;

import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * The {@code text/event-stream} body of an A2A stream, i.e. the queue of the events the server sends to one subscriber.
 * <p>
 * It is a {@link Flow.Publisher} so nothing ever blocks: events are buffered and handed to the HTTP layer as it asks
 * for them, which also means publishing works before the client started reading - an executor which finishes in a
 * microsecond loses nothing.
 */
public class SseBus implements Flow.Publisher<ByteBuffer> {
    private static final ByteBuffer KEEP_ALIVE =
            ByteBuffer.wrap(": ping\n\n".getBytes(UTF_8)).asReadOnlyBuffer();

    private final Logger logger = Logger.getLogger(SseBus.class.getName());

    private final Lock lock = new ReentrantLock();
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong lastEventId = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    // serializes the emission, the HTTP layer calls request(1) from within onNext so drain() is re-entrant
    private final AtomicInteger wip = new AtomicInteger();
    private final Deque<Frame> frames = new ConcurrentLinkedDeque<>();
    private final Runnable onClose;
    private volatile Flow.Subscriber<? super ByteBuffer> subscriber;

    /**
     * @param onClose called once the stream is over, whatever the reason - use it to deregister it from the task.
     */
    public SseBus(final Runnable onClose) {
        this.onClose = onClose;
    }

    /**
     * Queues an event for the client.
     *
     * @param json the already serialized event, it must not contain any raw new line.
     * @return the identifier of the SSE event, {@code -1} when the stream is already over.
     */
    public long publish(final String json) {
        if (closed.get()) {
            logger.log(FINEST, () -> "Dropping an event published on a closed stream");
            return -1;
        }
        final var frame = new Frame(lastEventId.incrementAndGet(), json);
        frames.add(frame);
        drain();
        return frame.id();
    }

    /**
     * Queues a comment, which commits the response and keeps it - and any proxy in between - alive without being an
     * event.
     */
    public void keepAlive() {
        if (closed.get()) {
            return;
        }
        frames.add(Frame.KEEP_ALIVE_FRAME);
        drain();
    }

    /**
     * Completes the stream once everything which was published has been written.
     * <p>
     * The completion itself is left to the drain: closing while the HTTP layer has not asked for the last events yet must
     * not drop them - and the terminal status of a task is precisely the event published just before closing.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        drain();
    }

    /**
     * @return {@code true} once {@link #close()} was called.
     */
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void subscribe(final Flow.Subscriber<? super ByteBuffer> newSubscriber) {
        lock.lock();
        try {
            complete(subscriber); // at most one HTTP response per stream, the last one to connect wins
            subscriber = newSubscriber;
            pending.set(0);
        } finally {
            lock.unlock();
        }

        newSubscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(final long n) {
                if (n <= 0) {
                    newSubscriber.onError(new IllegalArgumentException("Invalid request: " + n));
                    return;
                }
                pending.updateAndGet(
                        p -> p == Long.MAX_VALUE || n == Long.MAX_VALUE || p + n < 0 ? Long.MAX_VALUE : p + n);
                drain();
                if (closed.get() && frames.isEmpty()) {
                    complete(newSubscriber);
                }
            }

            @Override
            public void cancel() {
                closed.set(true);
                forget(newSubscriber);
                onClose.run();
            }
        });

        if (closed.get() && frames.isEmpty()) {
            complete(newSubscriber);
        }
    }

    private void drain() {
        if (wip.getAndIncrement() != 0) { // another thread is emitting, it will see our frame
            return;
        }
        int missed = 1;
        do {
            emitWhilePossible();
            missed = wip.addAndGet(-missed);
        } while (missed != 0);
    }

    private void emitWhilePossible() {
        while (!frames.isEmpty() && subscriber != null && pending.get() > 0) {
            final var frame = frames.pollFirst();
            if (frame == null) { // another thread was faster
                continue;
            }
            if (pending.get() != Long.MAX_VALUE) {
                pending.decrementAndGet();
            }
            emit(frame.toBuffer());
        }
        if (closed.get() && frames.isEmpty()) {
            complete(subscriber);
        }
    }

    private void emit(final ByteBuffer buffer) {
        final var current = subscriber;
        if (current == null) {
            return;
        }
        try {
            current.onNext(buffer);
        } catch (final RuntimeException re) {
            logger.log(SEVERE, re, re::getMessage);
            forget(current);
            try {
                current.onError(re);
            } catch (final RuntimeException nested) {
                logger.log(FINEST, nested, nested::getMessage);
            }
        }
    }

    private void complete(final Flow.Subscriber<? super ByteBuffer> current) {
        if (current == null) {
            return;
        }
        if (!forget(current)) {
            return; // somebody else already completed it
        }
        try {
            current.onComplete();
        } catch (final RuntimeException re) {
            logger.log(FINEST, re, re::getMessage);
        } finally {
            onClose.run();
        }
    }

    private boolean forget(final Flow.Subscriber<? super ByteBuffer> current) {
        lock.lock();
        try {
            if (subscriber != current) {
                return false;
            }
            subscriber = null;
            return true;
        } finally {
            lock.unlock();
        }
    }

    // visible for testing
    List<String> queued() {
        return frames.stream().map(Frame::json).filter(Objects::nonNull).toList();
    }

    private record Frame(long id, String json) {
        private static final Frame KEEP_ALIVE_FRAME = new Frame(-1, null);

        private ByteBuffer toBuffer() {
            if (json == null) {
                return KEEP_ALIVE.duplicate();
            }
            // a serialized A2A event has no raw newline so a single data line is always enough
            return ByteBuffer.wrap(("id: " + id + "\nevent: message\ndata: " + json + "\n\n").getBytes(UTF_8));
        }
    }
}
