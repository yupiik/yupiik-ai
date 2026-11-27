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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.httpserver.jre.JreHttpServer;
import io.yupiik.fusion.httpserver.jre.JreHttpServerConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end tests of the SSE session stream: snapshot seeding and per-session fan-out. */
@ExtendWith(TestWebJsonMapper.class)
class SseExchangeHandlerTest {
    @TempDir
    private static Path eventWorkdir;

    private static HttpClient client;

    private JreHttpServer server;

    /** The broker backing the current {@link #server}; closed in {@link #stop()} before the server, so each SSE
     * exchange completes deterministically (SseBroker.close() -> subscription.close() -> teardown()) instead of
     * burning JreHttpServer's 2s stop-grace per live connection. */
    private SseBroker broker;

    /** Live SSE client connections opened by {@link #stream(String)}; cancelled in {@link #stop()}. */
    private final List<CompletableFuture<?>> liveReaders = new ArrayList<>();

    @BeforeAll
    static void warm() {
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @AfterEach
    void stop() {
        // first close every SSE subscription from the SERVER side (SseBroker.close() -> subscription.close()
        // -> teardown() -> subscriber.onComplete()), which un-parks each SseBody writeLoop so the in-flight
        // exchange completes and JreHttpServer.stop(SHUTDOWN_GRACE_SECONDS=2) returns in milliseconds instead
        // of burning a full 2s grace per connection (the exchange is a parked Flow.Subscriber that never
        // completes on its own). This is the same deterministic shutdown path the container lifecycle uses.
        if (broker != null) {
            broker.close();
        }
        // client side: cancel any still-open SSE reader connections (no-op once the server teardown unblocked
        // the read), purely so the client HttpClient is not left holding sockets.
        for (final var reader : liveReaders) {
            if (!reader.isDone()) {
                reader.cancel(true);
            }
        }
        if (server != null) {
            server.close();
        }
    }

    private JreHttpServer start(final SseBroker broker) {
        return start(
                broker,
                1024,
                id -> List.of(
                        new WebEventDto("user", id, null, null, "hello", Map.of()),
                        new WebEventDto("text-delta", id, null, null, "world", Map.of())));
    }

    private JreHttpServer start(
            final SseBroker broker, final int windowBlocks, final Function<String, List<WebEventDto>> snapshotSource) {
        this.broker = broker;
        final var instance = new JreHttpServer(
                new JreHttpServerConfiguration(null, 0, true),
                List.of(new SseExchangeHandler(broker, snapshotSource, windowBlocks, broker::subscribe)));
        instance.start();
        server = instance;
        return instance;
    }

    private static String url(final JreHttpServer s, final String path) {
        return "http://127.0.0.1:" + s.port() + path;
    }

    private Queue<String> stream(final String url) {
        return stream(url, _ -> {});
    }

    private Queue<String> stream(final String url, final Consumer<String> onLine) {
        final var lines = new ConcurrentLinkedQueue<String>();
        // sendAsync gives us a handle we can cancel in stop() to close a still-open SSE connection; a
        // synchronous client.send() would block forever and leave the server's stop(2) grace to time it out.
        final var future = client.sendAsync(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofLines());
        liveReaders.add(future);
        final var reader = new Thread(
                () -> {
                    try {
                        final var resp = future.join();
                        try (final var body = resp.body()) {
                            body.forEach(l -> {
                                if (!l.isBlank()) {
                                    lines.add(l);
                                    onLine.accept(l);
                                }
                            });
                        }
                    } catch (final Exception e) {
                        lines.add("!error:" + e);
                    }
                },
                "sse-test-reader");
        reader.setDaemon(true);
        reader.start();
        return lines;
    }

    @Test
    void replacingADisconnectedSubscriptionDoesNotLoseTheNewTab(final JsonMapper mapper) throws Exception {
        final var broker = new SseBroker(mapper);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attempt = 0; attempt < 100; attempt++) {
                final SseBroker.SseSubscription oldTab = _ -> {};
                final var frames = new ConcurrentLinkedQueue<String>();
                final SseBroker.SseSubscription newTab = bytes -> frames.add(new String(bytes, StandardCharsets.UTF_8));
                broker.subscribe("s1", oldTab);
                final var start = new CountDownLatch(1);
                final var disconnect = executor.submit(() -> {
                    start.await();
                    broker.unsubscribe("s1", oldTab);
                    return true;
                });
                final var connect = executor.submit(() -> {
                    start.await();
                    broker.subscribe("s1", newTab);
                    return true;
                });
                start.countDown();
                disconnect.get(5, TimeUnit.SECONDS);
                connect.get(5, TimeUnit.SECONDS);
                broker.pushRpc("s1", Map.of("id", attempt));
                assertEquals(1, frames.size(), "new tab must stay registered when the old tab disconnects");
                broker.unsubscribe("s1", newTab);
            }
        } finally {
            broker.close();
        }
    }

    @Test
    void snapshotFrameIsSentImmediatelyOnConnect(final JsonMapper mapper) throws Exception {
        final var broker = new SseBroker(mapper);
        final var s = start(broker);
        final var frames = stream(url(s, "/api/stream?session=s1"));

        await(() -> framesPeek(frames).contains("event: snapshot"), 3, TimeUnit.SECONDS);
        // the snapshot must be the very first event, and carry the replayed events payload
        final var firstEvent = firstEvent(frames);
        assertEquals("event: snapshot", firstEvent);
        assertTrue(
                framesPeek(frames).stream().anyMatch(l -> l.startsWith("data:") && l.contains("\"events\"")),
                "snapshot should carry an events array, got: " + framesPeek(frames));
    }

    @Test
    void aThrowingSnapshotSourceStillDeliversASnapshotFrame(final JsonMapper mapper) throws Exception {
        // regression: when the snapshot generator throws, the connection used to be left open-but-silent
        // (the exception escaped SseBody.subscribe before any frame was pushed), which the browser saw as
        // an endless "Loading conversation…". It must instead deliver a valid empty snapshot so the client's
        // loader clears, and tag the payload so the frontend can surface the error.
        final var broker = new SseBroker(mapper);
        final var s = start(broker, 1024, id -> {
            throw new IllegalStateException("boom: cannot replay " + id);
        });
        final var frames = stream(url(s, "/api/stream?session=s1"));

        await(() -> framesPeek(frames).contains("event: snapshot"), 3, TimeUnit.SECONDS);
        final var data = framesPeek(frames).stream()
                .filter(l -> l.startsWith("data:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no snapshot data line, got: " + framesPeek(frames)));
        assertTrue(data.contains("\"events\""), "empty snapshot must still carry an events array, got: " + data);
        assertTrue(data.contains("\"snapshotError\""), "failure must be tagged for the frontend, got: " + data);
    }

    @Test
    void snapshotIsTailSlicedAndCarriesTheTotal(final JsonMapper mapper) throws Exception {
        final var broker = new SseBroker(mapper);
        // a 5-event history with a 2-event window must seed only the last 2 events but report total=5 so
        // the client knows session.loadEarlier can fetch the rest (tail-first pagination).
        final var s = start(
                broker,
                2,
                id -> List.of(
                        new WebEventDto("user", id, null, null, "m1", Map.of()),
                        new WebEventDto("text-delta", id, null, null, "m2", Map.of()),
                        new WebEventDto("user", id, null, null, "m3", Map.of()),
                        new WebEventDto("text-delta", id, null, null, "m4", Map.of()),
                        new WebEventDto("text-delta", id, null, null, "m5", Map.of())));
        final var frames = stream(url(s, "/api/stream?session=s1"));

        await(
                () -> framesPeek(frames).stream().anyMatch(l -> l.startsWith("data:") && l.contains("\"total\"")),
                3,
                TimeUnit.SECONDS);
        // the snapshot carries exactly the tail window and the authoritative total
        final var data = framesPeek(frames).stream()
                .filter(l -> l.startsWith("data:"))
                .findFirst()
                .orElseThrow();
        assertTrue(data.contains("\"total\":5"), "expected the total count in the snapshot, got: " + data);
        assertFalse(data.contains("m1"), "tail-first snapshot must not ship m1, got: " + data);
        assertTrue(data.contains("m4") && data.contains("m5"), "snapshot should carry the tail m4/m5, got: " + data);
    }

    @Test
    void agentFramesAreFanOutPerSessionAndFollowTheSnapshot(final JsonMapper mapper) throws Exception {
        final var broker = new SseBroker(mapper);
        final var s = start(broker);
        final var sessionA = stream(url(s, "/api/stream?session=A"));
        final var sessionA2 = stream(url(s, "/api/stream?session=A"));
        final var sessionB = stream(url(s, "/api/stream?session=B"));

        await(
                () -> firstEvent(sessionA) != null && firstEvent(sessionA2) != null && firstEvent(sessionB) != null,
                3,
                TimeUnit.SECONDS);

        // push one agent event to each session after the consumer is subscribed
        broker.pushAgentEvent("A", new AgentEvent.Status("running", "A", null, null, eventWorkdir));
        broker.pushAgentEvent("B", new AgentEvent.Status("boosted", "B", null, null, eventWorkdir));

        await(() -> framesPeek(sessionA).stream().anyMatch(l -> l.contains("running")), 3, TimeUnit.SECONDS);
        await(() -> framesPeek(sessionB).stream().anyMatch(l -> l.contains("boosted")), 3, TimeUnit.SECONDS);
        // session A's two tabs both see their own event, and neither sees B's event
        assertTrue(
                framesPeek(sessionA).stream().anyMatch(l -> l.contains("\"sessionId\":\"A\"")),
                "session A should receive its own event, got: " + framesPeek(sessionA));
        assertTrue(
                framesPeek(sessionA2).stream().anyMatch(l -> l.contains("\"sessionId\":\"A\"")),
                "second A tab should also receive the A event, got: " + framesPeek(sessionA2));
        assertTrue(
                framesPeek(sessionB).stream().anyMatch(l -> l.contains("\"sessionId\":\"B\"")),
                "session B should receive its own event, got: " + framesPeek(sessionB));
        assertTrue(
                framesPeek(sessionA).stream().noneMatch(l -> l.contains("\"sessionId\":\"B\"")),
                "session A must not receive B's event, got: " + framesPeek(sessionA));
    }

    @Test
    void concurrentPushesDoNotDropTheConnection(final JsonMapper mapper) throws Exception {
        final var broker = new SseBroker(mapper);
        final var s = start(broker);
        final var drained = new CountDownLatch(1);
        // a real SSE connection driven by JreHttpServer's demand-gated subscriber
        final var tab = stream(url(s, "/api/stream?session=c1"), line -> {
            if (line.equals("data: {\"id\":\"concurrent-pushes-drained\"}")) {
                drained.countDown();
            }
        });
        await(() -> firstEvent(tab) != null, 3, TimeUnit.SECONDS);

        // hammer the connection with concurrent pushes from several threads, mixing live agent
        // events with the keep-alive ping (broadcastAll) - exactly the situation that used to race
        // on exchange.getResponseBody() (two threads writing/flushing the same stream -> IOException
        // -> the SSE connection dropped and the browser "refreshed"/reconnected on idle).
        final int threads = 8, per = 100;
        final var start = new CountDownLatch(1);
        final var pushers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int ft = t;
            pushers[t] = new Thread(
                    () -> {
                        try {
                            start.await();
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        for (int i = 0; i < per; i++) {
                            // every 10th push is the keep-alive ping broadcast, the rest a live event
                            if ((ft + i) % 10 == 0) {
                                broker.broadcastAll(SseBroker.pingFrame());
                            } else {
                                broker.pushAgentEvent(
                                        "c1",
                                        new AgentEvent.Status("evt-" + ft + "-" + i, "c1", null, null, eventWorkdir));
                            }
                        }
                    },
                    "push-" + t);
            pushers[t].start();
        }
        start.countDown();
        for (final Thread p : pushers) {
            p.join();
        }
        // All pushers have finished enqueueing. The serialized FIFO writer sends this non-agent
        // marker after their frames, and the reader records each line before releasing the latch.
        // A temporary plateau in the frame count is not evidence that either drain has finished.
        broker.pushRpc("c1", Map.of("id", "concurrent-pushes-drained"));
        assertTrue(drained.await(3, TimeUnit.SECONDS), "connection must deliver the trailing drain marker");

        final var agentFrames = framesPeek(tab).stream()
                .filter(l -> l.startsWith("event: agent"))
                .count();
        final int floor = threads * per / 2; // robust lower bound regardless of interleaving
        assertTrue(
                agentFrames >= floor,
                "connection must survive concurrent push and deliver most frames, got " + agentFrames
                        + " agent frames");
    }

    @Test
    void slowConsumerNeverBlocksTheEmitterAndNeverLosesStructuralFrames(final JsonMapper mapper) throws Exception {
        final var broker = new SseBroker(mapper);
        final var s = start(broker);
        final var tab = stream(url(s, "/api/stream?session=slow"));
        await(() -> firstEvent(tab) != null, 3, TimeUnit.SECONDS);

        // burst well above the 1024-frame connection queue while the reader is still catching up:
        // 1100 droppable status frames - these may be trimmed on overflow, and above all must not
        // block the emit thread (the bug class where a slow browser froze the whole turn).
        final var burstStart = System.nanoTime();
        for (int i = 0; i < 1100; i++) {
            broker.pushAgentEvent("slow", new AgentEvent.Status("fill-" + i, "slow", null, null, eventWorkdir));
        }
        final var burstNanos = System.nanoTime() - burstStart;
        // each push is a plain enqueue: 1100 pushes must complete far faster than any socket write
        // (generous bound - the sync-drain implementation would block here and blow this)
        assertTrue(
                burstNanos < TimeUnit.SECONDS.toNanos(5),
                "1100 pushes took " + TimeUnit.NANOSECONDS.toMillis(burstNanos) + "ms - the emitter was blocked");

        // a structural pair pushed AFTER the burst must always be delivered (never dropped):
        // the finished frame is what flips a tool card out of "working", so losing it is the bug.
        broker.pushAgentEvent("slow", new AgentEvent.ToolStarted("read", "{}", 1, "slow", null, null, eventWorkdir));
        broker.pushAgentEvent(
                "slow", new AgentEvent.ToolFinished("read", "done", true, "slow", null, null, eventWorkdir));

        await(() -> framesPeek(tab).stream().anyMatch(l -> l.contains("read")), 5, TimeUnit.SECONDS);
        assertTrue(
                framesPeek(tab).stream().anyMatch(l -> l.contains("\"tool-finished\"") && l.contains("read")),
                "structural tool-finished frame must survive the burst, got: " + framesPeek(tab));
    }

    @Test
    void missingSessionParamReturns400(final JsonMapper mapper) throws Exception {
        final var broker = new SseBroker(mapper);
        final var s = start(broker);
        final var resp = client.send(
                HttpRequest.newBuilder(URI.create(url(s, "/api/stream"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode());
    }

    @Test
    void replayMapsPersistedMessages() {
        final var messages = List.of(
                Message.projectDir("/tmp/proj"),
                Message.user("watch this"),
                Message.assistant(
                        "thinking\nresponse",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                new ToolCall.FunctionCall("read", "{\"path\":\"src/Main.java\"}"),
                                null))),
                Message.toolResult("call-1", "final answer", "read"),
                Message.user("ok"));

        final var events = WebReplay.replay("sess", messages);
        // project marker and any system notices are skipped; user/assistant/tool replay
        assertEquals(5, events.size());
        assertEquals("user", events.get(0).type());
        assertEquals("watch this", events.get(0).message());
        assertEquals("text-delta", events.get(1).type());
        assertEquals("thinking\nresponse", events.get(1).message());
        assertEquals("tool-started", events.get(2).type());
        assertEquals("read", events.get(2).message());
        // the persisted tool-call arguments must survive the replay so the frontend can build its
        // command/path summary (bash/read cards) - was previously dropped as an empty map.
        assertEquals("{\"path\":\"src/Main.java\"}", ((Map<?, ?>) events.get(2).data()).get("args"));
        assertEquals("tool-finished", events.get(3).type());
        assertEquals("read", events.get(3).message());
        assertEquals("final answer", ((Map<?, ?>) events.get(3).data()).get("output"));
        assertEquals("user", events.get(4).type());
        assertEquals("ok", events.get(4).message());
    }

    @Test
    void replayDropsOrphanToolStartedWhenTheTurnWasInterrupted() {
        // the agent persists the assistant message (with its tool_calls) BEFORE the tool runs; a turn
        // cancelled between the two leaves a persisted read call with no tool result. Replaying it
        // as a tool-started with no tool-finished would render a card stuck "working" forever, so the
        // orphan started must be dropped entirely.
        final var messages = List.of(
                Message.projectDir("/tmp/proj"),
                Message.user("read it"),
                // assistant emitted read but the turn was cancelled before the tool result persisted
                Message.assistant(
                        "reading",
                        List.of(new ToolCall(
                                "call-orphan",
                                "function",
                                new ToolCall.FunctionCall("read", "{\"path\":\"src/Main.java\"}"),
                                null))),
                // no Message.toolResult for call-orphan
                Message.user("never mind"));

        final var events = WebReplay.replay("sess", messages);
        // user + text-delta + user = 3; the orphan read must NOT add a tool-started (no card stuck)
        assertEquals(3, events.size());
        assertTrue(
                events.stream().noneMatch(e -> "tool-started".equals(e.type())),
                "orphan tool-started must be dropped so no card sticks 'working', got: " + events);
        assertTrue(
                events.stream().allMatch(e -> !"tool-finished".equals(e.type())),
                "no tool-finished should be emitted without a matching result");
    }

    @Test
    void replayKeepsStartedForACompletedCallAndSkipsTheOrphanAfterIt() {
        // a completed read followed by an interrupted one: only the completed call is rendered.
        final var messages = List.of(
                Message.user("go"),
                Message.assistant(
                        "two calls",
                        List.of(
                                new ToolCall(
                                        "call-a",
                                        "function",
                                        new ToolCall.FunctionCall("read", "{\"path\":\"a\"}"),
                                        null),
                                new ToolCall(
                                        "call-b",
                                        "function",
                                        new ToolCall.FunctionCall("read", "{\"path\":\"b\"}"),
                                        null))),
                // only call-a has a result (call-b was interrupted)
                Message.toolResult("call-a", "content-a", "read"),
                Message.user("done"));

        final var events = WebReplay.replay("sess", messages);
        final var started =
                events.stream().filter(e -> "tool-started".equals(e.type())).toList();
        final var finished =
                events.stream().filter(e -> "tool-finished".equals(e.type())).toList();
        assertEquals(1, started.size(), "only the completed call should render a started card, got: " + started);
        assertEquals(1, finished.size());
        assertEquals("read", started.get(0).message());
        assertEquals("read", finished.get(0).message());
    }

    @Test
    void replayCarriesTheBashCommandSoItsSummarySurvives() {
        // a bash call persisted with its command: replaying it as a tool-started must forward the
        // command as the args string, otherwise the frontend collapses the card to the bare tool name
        // (summarizeArgs gets no 'command'), which is the "bash has no summary text" symptom on reload.
        final var messages = List.of(
                Message.user("build it"),
                Message.assistant(
                        "building",
                        List.of(new ToolCall(
                                "call-1",
                                "function",
                                new ToolCall.FunctionCall("bash", "{\"command\":\"mvn test\"}"),
                                null))),
                Message.toolResult("call-1", "BUILD SUCCESS", "bash"),
                Message.user("thanks"));

        final var events = WebReplay.replay("sess", messages);
        final var started =
                events.stream().filter(e -> "tool-started".equals(e.type())).toList();
        assertEquals(1, started.size());
        assertEquals("bash", started.get(0).message());
        assertEquals(
                "{\"command\":\"mvn test\"}",
                ((Map<?, ?>) started.get(0).data()).get("args"),
                "replayed bash tool must carry its command so the card shows a summary");
    }

    /** First non-{@code data:} event header, or {@code null} until the first event line arrives. */
    private static String firstEvent(final Queue<String> frames) {
        return framesPeek(frames).stream()
                .filter(l -> l.startsWith("event: "))
                .findFirst()
                .orElse(null);
    }

    /** Snapshot of the queue (peek all) for assertions without consuming it. */
    private static List<String> framesPeek(final Queue<String> frames) {
        return List.copyOf(frames);
    }

    private static void await(final BooleanSupplier condition, final long timeout, final TimeUnit unit)
            throws InterruptedException {
        final var deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
