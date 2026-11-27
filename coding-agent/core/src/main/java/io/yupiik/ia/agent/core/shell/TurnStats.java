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
package io.yupiik.ia.agent.core.shell;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable per-turn counters of a shell session: latest usage and turn-metrics
 * events plus the number of tools run and the turn start time. Event
 * observers write it, frontends and turn runners only read it.
 * <p>
 * The latest usage/turn-metrics events intentionally survive across turns
 * (they hold the last known values, matching the historical CLI behaviour);
 * only {@link #beginTurn()} resets the per-turn counters.
 */
public class TurnStats {
    private volatile AgentEvent.UsageUpdated lastUsage;
    private volatile AgentEvent.TurnMetrics lastTurnMetrics;
    private final AtomicInteger toolsRun = new AtomicInteger();
    private volatile long turnStartNanos = System.nanoTime();

    /** Resets the per-turn counters (tools run and turn start time). */
    public void beginTurn() {
        toolsRun.set(0);
        turnStartNanos = System.nanoTime();
    }

    /** Resets every counter, including the last known usage and turn metrics. */
    public void reset() {
        lastUsage = null;
        lastTurnMetrics = null;
        toolsRun.set(0);
        turnStartNanos = System.nanoTime();
    }

    public void recordUsage(final AgentEvent.UsageUpdated usage) {
        this.lastUsage = usage;
    }

    public void recordTurnMetrics(final AgentEvent.TurnMetrics metrics) {
        this.lastTurnMetrics = metrics;
    }

    public void incToolsRun() {
        toolsRun.incrementAndGet();
    }

    public AgentEvent.UsageUpdated lastUsage() {
        return lastUsage;
    }

    public AgentEvent.TurnMetrics lastTurnMetrics() {
        return lastTurnMetrics;
    }

    public int toolsRun() {
        return toolsRun.get();
    }

    public long turnStartNanos() {
        return turnStartNanos;
    }
}
