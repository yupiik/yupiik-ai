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

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-session sub-agent admission budget: the {@code yca.subagents.maxConcurrent} cap of one
 * session applied to the in-flight children it spawned (the "number of sub agents" limit).
 *
 * <p>Owned by the session object (the per-session {@code AgentLoop}, created by
 * {@code SessionRuntimeFactory}) so it dies with the session: no registry-side map to clean up.
 * A single child holds one permit between {@link #tryAcquire()} (admission) and {@link #release()}
 * (child completion/cancellation). The companion per-provider {@code N-1} cap lives in
 * {@link SubagentRegistry} (it is global per instance, keyed by provider id).</p>
 */
public final class SubagentBudget {
    // the effective cap of this budget
    private final int maxConcurrent;
    // null when maxConcurrent <= 0: the budget is unlimited and nothing is ever acquired/released
    private final Semaphore permits;

    public SubagentBudget(final int maxConcurrent) {
        this.maxConcurrent = Math.max(0, maxConcurrent);
        // unlimited (0/negative config) -> no semaphore: tryAcquire always succeeds
        this.permits = this.maxConcurrent > 0 ? new Semaphore(this.maxConcurrent) : null;
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public boolean unlimited() {
        return permits == null;
    }

    /** Acquires one in-flight child permit (atomic). False when the session budget is exhausted. */
    public boolean tryAcquire() {
        return permits == null || permits.tryAcquire();
    }

    /**
     * Releases one in-flight child permit. Safe to call only after a successful
     * {@link #tryAcquire()}; the atomics guard against a spurious over-release.
     */
    public void release() {
        if (permits != null) {
            permits.release();
        }
    }

    /** Current number of permits still available (informational). */
    public int available() {
        return permits == null ? Integer.MAX_VALUE : permits.availablePermits();
    }

    /**
     * Atomic counter mirror of {@code available()} for refusal messages without semaphore coupling:
     * incremented on admit, decremented on release.
     */
    public static final class Counter {
        private final int max;
        private final AtomicInteger value = new AtomicInteger();

        public Counter(final int max) {
            this.max = max;
        }

        public int max() {
            return max;
        }

        /** Reserves one slot; returns false when already at {@code max} (no reservation taken). */
        public boolean tryReserve() {
            while (true) {
                final var current = value.get();
                if (current >= max) {
                    return false;
                }
                if (value.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        public void release() {
            value.decrementAndGet();
        }

        public int get() {
            return value.get();
        }
    }
}
