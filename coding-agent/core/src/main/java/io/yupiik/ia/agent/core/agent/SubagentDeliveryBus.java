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
package io.yupiik.ia.agent.core.agent;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process push channel that carries a finished sub-agent's result to its <em>parent</em> turn.
 *
 * <p>The spawning {@code AgentLoop} drains its own session-id
 * queue at the round head, right next to the queued-prompt integration, and injects the completion as a
 * {@code user}-role {@code <subagent ...>...</subagent>} message so the parent model's <em>next round</em>
 * automatically sees the finished result. This is what removes the need for the model to poll
 * {@code check_subagents}: the completion is pushed, not fetched.</p>
 *
 * <p>Keyed by the <em>parent</em> session id ({@code SubagentState#parentSessionId()}), which both the
 * spawning loop owns as its {@code currentSessionId}. Nested parents open a lifetime-scoped channel;
 * closing one discards late deliveries. Root-session deliveries arriving between turns are retained
 * until the next turn drains them.</p>
 */
public final class SubagentDeliveryBus {
    private static final SubagentDeliveryBus INSTANCE = new SubagentDeliveryBus();

    private final ConcurrentHashMap<String, Channel> pending = new ConcurrentHashMap<>();

    private record Channel(boolean active, ArrayDeque<Delivery> deliveries) {}

    /** Nested parents have a bounded lifetime: late completions must not recreate their queues. */
    public void open(final String sessionId) {
        pending.put(sessionId, new Channel(true, new ArrayDeque<>()));
    }

    public void close(final String sessionId) {
        pending.remove(sessionId);
    }

    /** A finished sub-agent's outcome, to be injected into the parent context. */
    public record Delivery(String subagentId, String name, String summary, boolean success, boolean cancelled) {
        /** The injected message text, matching opencode's tagged {@code <subagent>} shape. */
        public String message() {
            final var state = cancelled ? "cancelled" : success ? "completed" : "failed";
            final var text = summary == null || summary.isBlank() ? "[no summary]" : summary;
            return "<subagent sessionID=\"" + subagentId + "\" state=\"" + state + "\" description=\""
                    + (name == null ? "" : name.replace("\"", "'")) + "\">\n" + text + "\n</subagent>";
        }
    }

    private SubagentDeliveryBus() {
        // static process-global bus
    }

    public static SubagentDeliveryBus get() {
        return INSTANCE;
    }

    /** Delivers a finished sub-agent result to its parent session (idempotent per subagent id). */
    public void deliver(final String parentSessionId, final Delivery delivery) {
        deliver(parentSessionId, delivery, false);
    }

    public void deliver(final String parentSessionId, final Delivery delivery, final boolean requireActive) {
        if (parentSessionId == null || parentSessionId.isBlank() || delivery == null) {
            return;
        }
        pending.compute(parentSessionId, (k, channel) -> {
            if (requireActive && (channel == null || !channel.active())) {
                return channel;
            }
            final var target = channel == null ? new Channel(false, new ArrayDeque<>()) : channel;
            final var q = target.deliveries();
            // idempotency: a single completion may fan out (e.g. push + a concurrent collect)
            if (q.stream().noneMatch(d -> d.subagentId().equals(delivery.subagentId()))) {
                q.addLast(delivery);
            }
            return target;
        });
    }

    /** Drains and returns the pending deliveries for a parent session (removing them from the queue). */
    public List<Delivery> drain(final String parentSessionId) {
        if (parentSessionId == null || parentSessionId.isBlank()) {
            return List.of();
        }
        final var drained = new AtomicReference<List<Delivery>>(List.of());
        pending.computeIfPresent(parentSessionId, (key, channel) -> {
            drained.set(List.copyOf(channel.deliveries()));
            return channel.active() ? new Channel(true, new ArrayDeque<>()) : null;
        });
        return drained.get();
    }
}
