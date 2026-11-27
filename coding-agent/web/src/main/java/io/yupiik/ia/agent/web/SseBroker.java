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

import io.yupiik.fusion.framework.api.lifecycle.Stop;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.todo.TodoItem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session SSE fan-out hub. Every connected browser tab for a given session
 * is tracked as a {@link SseSubscription}; agent events (already routed by the
 * shared {@code ShellRegistry} to the session's frontend) are flattened to
 * {@link WebEventDto} and written to every subscriber of that session. The
 * subscription is a {@link java.util.concurrent.Flow.Publisher} so the JRE
 * server streams frames reactively and unsubscribes on client disconnect.
 */
@ApplicationScoped
public class SseBroker {
    private final ConcurrentHashMap<String, Set<SseSubscription>> subscriptions = new ConcurrentHashMap<>();
    private final JsonMapper mapper;
    private final SecretRedactor redactor;

    public SseBroker(final JsonMapper mapper) {
        this(mapper, null);
    }

    public SseBroker(final JsonMapper mapper, final SecretRedactor redactor) {
        this.mapper = mapper;
        this.redactor = redactor;
    }

    /** @return the set of live subscriptions for a session, or a fresh empty set. */
    public Set<SseSubscription> subscriptions(final String sessionId) {
        return subscriptions.computeIfAbsent(sessionId, _ -> ConcurrentHashMap.newKeySet());
    }

    /** Registers a subscription to receive frames of {@code sessionId}. */
    public void subscribe(final String sessionId, final SseSubscription subscription) {
        subscriptions.compute(sessionId, (_, existing) -> {
            final var bySession = existing == null ? ConcurrentHashMap.<SseSubscription>newKeySet() : existing;
            bySession.add(subscription);
            return bySession;
        });
    }

    /** Removes a subscription; drops the empty per-session set. */
    public void unsubscribe(final String sessionId, final SseSubscription subscription) {
        subscriptions.computeIfPresent(sessionId, (_, bySession) -> {
            bySession.remove(subscription);
            return bySession.isEmpty() ? null : bySession;
        });
    }

    /** Broadcasts a JSON-RPC server-initiated request (approval or question) to a session. */
    public void pushRpc(final String sessionId, final Object request) {
        broadcastRaw(sessionId, frame("rpc", request), false);
    }

    /** Encodes an outstanding RPC for replay to a single reconnecting subscription. */
    public byte[] rpcFrame(final Object request) {
        return frame("rpc", request).getBytes(StandardCharsets.UTF_8);
    }

    /** Writes a live {@code snapshot} frame carrying the session bootstrap payload. */
    public void pushSnapshot(final String sessionId, final Object snapshot) {
        broadcastRaw(sessionId, frame("snapshot", snapshot), false);
    }

    /**
     * Encodes a {@code snapshot} frame (header + payload) without broadcasting it. A single
     * connection seeds its own history with exactly these bytes when it subscribes, so a fresh
     * tab replays the conversation without resetting other tabs already attached to the session.
     *
     * @param snapshot the snapshot payload ({@code {events:[...]}})
     * @return the raw SSE frame bytes
     */
    public byte[] snapshotFrame(final Object snapshot) {
        return frame("snapshot", snapshot).getBytes(StandardCharsets.UTF_8);
    }

    /** Flattens an agent event and broadcasts it to the session. */
    public void pushAgentEvent(final String sessionId, final AgentEvent event) {
        broadcastRaw(
                sessionId,
                frame(
                        "agent",
                        WebEventDto.of(event, text -> redactor == null ? text : redactor.redact(text, sessionId))),
                isDroppable(event));
    }

    /**
     * Transient frames safe to trim on a slow consumer: live tool stdout, status labels and
     * usage telemetry are superseded/derivable - the structural events (tool started/finished,
     * text deltas, turn boundaries, errors, metrics) are never dropped so the receiver always
     * has a consistent conversation graph.
     */
    private static boolean isDroppable(final AgentEvent event) {
        return event instanceof AgentEvent.ToolStream
                || event instanceof AgentEvent.Status
                || event instanceof AgentEvent.UsageUpdated;
    }

    /** Broadcasts a raw {@code agent} frame (already-encoded DTO) to the session. */
    public void broadcast(final String sessionId, final String frame) {
        broadcastRaw(sessionId, frame, false);
    }

    /**
     * Broadcasts a {@code todo-updated} frame to a session after the {@code write_todo} tool changed
     * its list. A no-op when the session has no live subscriber (CLI/TUI runs are untouched). The
     * frame is structural (the receiver should reload the sidebar todos), never trimmed.
     */
    public void broadcastTodoUpdated(final String sessionId, final List<TodoItem> todos) {
        final var items = todos == null
                ? List.<Map<String, Object>>of()
                : todos.stream()
                        .map(t -> Map.<String, Object>of(
                                "content", t.content() == null ? "" : t.content(),
                                "status", t.status() == null ? "" : t.status()))
                        .toList();
        broadcastRaw(
                sessionId,
                frame("agent", new WebEventDto("todo-updated", sessionId, null, null, "", Map.of("todos", items))),
                false);
    }

    /**
     * Broadcasts a raw frame that may be dropped when a slow tab's queue is full: transient
     * progress (live tool stdout, status labels, usage telemetry) is safe to trim, structural
     * events (tool started/finished, answers, turn boundaries, errors) never are - the receiver
     * can always rebuild coherent state from a later snapshot.
     *
     * @param droppable when {@code true} the frame is a candidate for the drop-oldest policy
     *                  when the receiver's queue overflows
     */
    public void broadcastDroppable(final String sessionId, final String frame) {
        broadcastRaw(sessionId, frame, true);
    }

    /**
     * Broadcasts a frame to every session's subscribers (e.g. the keep-alive ping): a ping is a
     * droppable heartbeat - a slow tab's queue may trim it, the connection stays alive regardless.
     */
    public void broadcastAll(final String frame) {
        for (final var bySession : Set.copyOf(subscriptions.keySet())) {
            broadcastRaw(bySession, frame, true);
        }
    }

    /**
     * Tears down every live subscription so {@link io.yupiik.fusion.httpserver.jre.JreHttpServer#close()}
     * (a {@code stop(0)} that waits for in-flight requests) returns instead of blocking on SSE virtual
     * threads parked waiting for frames. Called from {@code WebCommand.close()} <em>before</em>
     * {@code http.close()}: each subscription's {@link SseSubscription#close()} completes the underlying
     * {@link java.util.concurrent.Flow.Subscriber}, unblocks the exchange and lets the JVM exit promptly
     * on Ctrl+C. Idempotent.
     */
    public void close() {
        for (final var bySession : Set.copyOf(subscriptions.values())) {
            for (final var subscriber : Set.copyOf(bySession)) {
                try {
                    subscriber.close();
                } catch (final RuntimeException ignored) {
                    // a broken subscription must never block the shutdown
                }
            }
        }
        subscriptions.clear();
    }

    /**
     * Container-lifecycle hook: releases every SSE exchange as part of the {@code Stop} event so a
     * graceful close (fusion {@code fusion.launcher.useHook} shutdown hook or the normal
     * {@code CliAwaiter} container close) tears down the SSE virtual threads <em>before</em> the
     * shared virtual-thread executor closes last ({@code @Destroy(order = Integer.MAX_VALUE)} in
     * {@code ExecutorServiceProducer}). Without it the executor's graceful close would block on the
     * SSE subscribers parked waiting for a frame. Default {@code @Order} (before the executor's
     * max-order close); idempotent with {@link WebCommand#close()}.
     */
    public void onStop(@OnEvent final Stop stop) {
        close();
    }

    /**
     * Builds a well-formed SSE frame. The {@code event:} line names the type and the payload is
     * written on a {@code data:} line - only text on {@code data:} lines becomes the listener's
     * {@code event.data}, so omitting the prefix would make the browser deliver an empty payload
     * (the frontend's {@code JSON.parse(event.data)} would throw and the event be dropped silently).
     */
    private String frame(final String event, final Object payload) {
        return "event: " + event + "\ndata: " + json(payload) + "\n\n";
    }

    private void broadcastRaw(final String sessionId, final String frame, final boolean droppable) {
        final var bySession = subscriptions.get(sessionId);
        if (bySession == null || bySession.isEmpty()) {
            return;
        }
        final var bytes = frame.getBytes(StandardCharsets.UTF_8);
        for (final var subscriber : Set.copyOf(bySession)) {
            try {
                if (droppable) {
                    subscriber.pushDroppable(bytes);
                } else {
                    subscriber.push(bytes);
                }
            } catch (final RuntimeException e) {
                // a dead subscription: drop it and keep the others alive
                unsubscribe(sessionId, subscriber);
            }
        }
    }

    /** Builds a keep-alive comment frame; SSE comments start with a colon and are ignored by clients. */
    public static String pingFrame() {
        return ": ping\n\n";
    }

    private String json(final Object value) {
        return mapper.toString(value);
    }

    /** A tracked browser subscription that pushes raw frame bytes into the response body. */
    public interface SseSubscription {
        /** Writes one raw frame (bytes include headers and blank line) to the response stream. */
        void push(byte[] frame);

        /**
         * Writes a raw frame that may be dropped when this subscription's queue is full
         * (transient progress only - see {@link #broadcastDroppable(String, String)}).
         */
        default void pushDroppable(final byte[] frame) {
            push(frame);
        }

        /**
         * Stops this subscription and releases any writer it owns (e.g. completes the underlying
         * {@link java.util.concurrent.Flow.Subscriber} so a JRE server {@code stop(0)} does not block on
         * the parked SSE virtual thread). Called by {@link SseBroker#close()} on Ctrl+C shutdown.
         */
        default void close() {}
    }
}
