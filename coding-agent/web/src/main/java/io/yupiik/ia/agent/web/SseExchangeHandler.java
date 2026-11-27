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
package io.yupiik.ia.agent.web;

import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.http.server.spi.Endpoint;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves {@code GET /api/stream?session=<id>} as a text/event-stream. The
 * response body is a {@link Flow.Publisher} bridging this connection to
 * {@link SseBroker}: frames pushed by the broker are forwarded to the browser,
 * and the subscription is dropped when the client disconnects (the JRE server
 * unsubscribes on write failure).
 *
 * <p>Right after subscribing, the connection is seeded with a {@code snapshot}
 * frame carrying the persisted session as replayed {@link WebEventDto}s. This
 * both commits the response headers immediately (so the browser's
 * {@code EventSource.onopen} fires and the UI flips to "connected") and renders
 * the existing conversation instead of a blank page - the frontend's
 * {@code snapshot} listener seeds its event log from it.</p>
 *
 * <p>Delivery is asynchronous: broker pushes only enqueue a frame and wake a
 * per-connection writer virtual thread, which drains the queue and hands frames
 * to the JRE streaming adapter (which writes/flushes the socket). A slow
 * browser can therefore never block the agent turn thread that emits the
 * events; its queue is bounded (1024 frames) with a drop-oldest policy biased
 * to trim transient frames (tool stdout, status labels, usage telemetry) before
 * structural ones (tool started/finished, answers, turn boundaries, errors).</p>
 */
public class SseExchangeHandler implements Endpoint {
    private static final Logger LOGGER = Logger.getLogger(SseExchangeHandler.class.getName());

    private final SseBroker broker;
    private final Function<String, List<WebEventDto>> snapshotSource;
    private final BiConsumer<String, SseBroker.SseSubscription> subscriptionRegistrar;
    private final int windowBlocks;

    /**
     * @param broker the per-session SSE fan-out hub
     * @param snapshotSource builds the replay event list for a session id from its persisted
     *               messages; called once per SSE connection
     * @param windowBlocks how many of the most recent replayed events the initial snapshot carries;
     *               older history is fetched on demand via the session.loadEarlier JSON-RPC so a large
     *               session loads tail-first (transcript pagination window) instead of shipping its whole
     *               history in one SSE frame
     * @param subscriptionRegistrar registers the connection and replays outstanding interactive requests
     */
    public SseExchangeHandler(
            final SseBroker broker,
            final Function<String, List<WebEventDto>> snapshotSource,
            final int windowBlocks,
            final BiConsumer<String, SseBroker.SseSubscription> subscriptionRegistrar) {
        this.broker = broker;
        this.snapshotSource = snapshotSource;
        this.subscriptionRegistrar = subscriptionRegistrar;
        this.windowBlocks = Math.max(1, windowBlocks);
    }

    @Override
    public boolean matches(final Request request) {
        return "GET".equals(request.method()) && "/api/stream".equals(request.path());
    }

    @Override
    public CompletionStage<Response> handle(final Request request) {
        final var session = request.parameter("session");
        if (session == null || session.isBlank()) {
            return completedFuture(Response.of()
                    .status(400)
                    .body("{\"error\":\"missing session\"}")
                    .build());
        }
        final var body = new SseBody(session);
        return completedFuture(Response.of()
                .status(200)
                .header("content-type", "text/event-stream")
                .header("cache-control", "no-cache")
                .body(body)
                .build());
    }

    /** Reactive body bridging one SSE connection to the broker; unsubscribes on disconnect/completion. */
    private final class SseBody implements Flow.Publisher<ByteBuffer> {
        private final String sessionId;
        private final AtomicReference<Flow.Subscriber<? super ByteBuffer>> subscriber = new AtomicReference<>();
        private final SseBroker.SseSubscription subscription;

        // -- async, demand-gated delivery -------------------------------------------------------------
        // Broker pushes ONLY enqueue a frame and wake the writer: they never write the socket, so a
        // slow browser (full TCP window, busy JS thread) can never block the agent turn thread that
        // emits the events (which previously stalled the whole turn - finished tools kept "working",
        // usage/metrics frames piled up behind a stuck flush). A per-connection writer virtual thread
        // drains the queue and calls onNext, which the JRE adapter writes+flushes; it requests one
        // frame at a time, so onNext stays serialized and never over-produces.
        private static final int MAX_QUEUED_FRAMES = 1024;

        private final Object monitor = new Object();
        private final ArrayDeque<Frame> queue = new ArrayDeque<>(MAX_QUEUED_FRAMES + 1);
        private long demand;
        private boolean closed;

        /** One queued frame; {@code droppable} frames (tool stdout, status, usage) are trimmed first on overflow. */
        private record Frame(byte[] bytes, boolean droppable) {}

        private SseBody(final String sessionId) {
            this.sessionId = sessionId;
            this.subscription = new SseBroker.SseSubscription() {
                @Override
                public void push(final byte[] bytes) {
                    pushFrame(new Frame(bytes, false));
                }

                @Override
                public void pushDroppable(final byte[] bytes) {
                    pushFrame(new Frame(bytes, true));
                }

                @Override
                public void close() {
                    // release the parked SSE virtual thread on shutdown so JreHttpServer.stop(0)
                    // does not block forever waiting for this exchange to finish
                    teardown();
                }
            };
            // one writer per connection; parked while idle (no frames or no demand), so the agent
            // and broker threads never touch the socket
            Thread.ofVirtual().name("sse-writer-" + sessionId).start(this::writeLoop);
        }

        /** Enqueues one frame (never blocks, never writes); wakes the writer. */
        private void pushFrame(final Frame frame) {
            synchronized (monitor) {
                if (closed) {
                    return;
                }
                if (queue.size() >= MAX_QUEUED_FRAMES) {
                    dropOldestDroppable();
                }
                queue.addLast(frame);
                monitor.notifyAll();
            }
        }

        /**
         * Drop-oldest with a tail-preserving bias: the first droppable frame from the head is removed,
         * so structural frames (tool started/finished, answers, turn boundaries...) are kept as long as
         * possible. When the queue only holds structural frames the head is dropped anyway - a dead tab
         * is bounded either way, and it rebuilds coherent state from the snapshot on reconnect.
         */
        private void dropOldestDroppable() {
            final var it = queue.iterator();
            while (it.hasNext()) {
                if (it.next().droppable()) {
                    it.remove();
                    return;
                }
            }
            queue.pollFirst();
        }

        /** Serialized delivery loop: polls a frame and hands it to the subscriber outside the monitor. */
        private void writeLoop() {
            while (true) {
                final Frame frame;
                synchronized (monitor) {
                    while (!closed && (queue.isEmpty() || demand == 0 || subscriber.get() == null)) {
                        try {
                            monitor.wait();
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    if (closed) {
                        return;
                    }
                    frame = queue.pollFirst();
                    demand--;
                }
                try {
                    subscriber.get().onNext(ByteBuffer.wrap(frame.bytes()));
                } catch (final RuntimeException e) {
                    // a dead/writing-failed connection: abandon it so the broker keeps the others alive
                    synchronized (monitor) {
                        teardownInternal();
                    }
                    return;
                }
            }
        }

        @Override
        public void subscribe(final Flow.Subscriber<? super ByteBuffer> s) {
            if (!subscriber.compareAndSet(null, s)) {
                s.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(final long n) {}

                    @Override
                    public void cancel() {}
                });
                s.onError(new IllegalStateException("already subscribed"));
                return;
            }
            s.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(final long n) {
                    if (n <= 0) {
                        return;
                    }
                    synchronized (monitor) {
                        if (closed) {
                            return;
                        }
                        demand = saturatedAdd(demand, n);
                        monitor.notifyAll();
                    }
                }

                @Override
                public void cancel() {
                    teardown();
                }
            });
            // seed THIS connection with the persisted conversation BEFORE registering with the broker:
            // enqueueing the snapshot first guarantees it stays the first frame of the queue even with
            // the async writer - any agent frame broadcast after registration lands behind it, so the
            // frontend always seeds history before live events. Pushed directly to this subscription,
            // never broadcast, so other tabs keep their live log. snapshotPayload never throws: on any
            // failure it logs loudly and returns an empty snapshot, so the exchange is always seeded and
            // can never be left open-but-silent (which surfaced to the browser as an endless "Loading
            // conversation…" when the snapshot generation threw after onSubscribe).
            subscription.push(broker.snapshotFrame(snapshotPayload(sessionId)));
            subscriptionRegistrar.accept(sessionId, subscription);
        }

        /** Builds the tail-first snapshot payload: the last {@code windowBlocks} events plus a cursor hint. */
        private Map<String, Object> snapshotPayload(final String sessionId) {
            final List<WebEventDto> events;
            try {
                events = snapshotSource.apply(sessionId);
            } catch (final RuntimeException | Error e) {
                // Surface loudly instead of leaving the SSE exchange open-but-silent: a snapshot
                // generator failure must never hang the client on "Loading conversation…". We still
                // tell the browser a (empty) snapshot arrived - so its loader clears and chat unlocks -
                // and tag the payload so the frontend can render an explicit error notice if it wants.
                LOGGER.log(Level.SEVERE, "snapshot generation failed for session " + sessionId, e);
                return Map.of("events", List.<WebEventDto>of(), "total", 0, "snapshotError", String.valueOf(e));
            }
            final var size = events.size();
            final var from = Math.max(0, size - windowBlocks);
            final var tail = events.subList(from, size);
            return Map.of(
                    "events", tail,
                    // total replayed event count so the client knows how much older history remains and
                    // can call session.loadEarlier with the right cursor.
                    "total", size);
        }

        private void teardown() {
            synchronized (monitor) {
                closed = true;
                queue.clear();
                teardownInternal();
                monitor.notifyAll();
            }
        }

        /** Must be called while holding {@link #monitor}; idempotent. */
        private void teardownInternal() {
            broker.unsubscribe(sessionId, subscription);
            final var s = subscriber.getAndSet(null);
            if (s != null) {
                try {
                    s.onComplete();
                } catch (final RuntimeException ignored) {
                    // the server is already tearing the exchange down; never let a completion clog shutdown
                }
            }
        }

        private static long saturatedAdd(final long current, final long toAdd) {
            final long sum = current + toAdd;
            return sum < 0 ? Long.MAX_VALUE : sum; // overflow-guard; Long.MAX_VALUE means unbounded
        }
    }
}
