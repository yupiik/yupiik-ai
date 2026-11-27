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
package io.yupiik.ia.agent.core.permissions;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Session-owned destination grants, deliberately independent of generic tool permission modes/grants. */
public final class SubagentRouteApprovals {
    private final Set<String> routes = ConcurrentHashMap.newKeySet();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;

    public Set<String> routes() {
        return Set.copyOf(routes);
    }

    public boolean allows(final String route) {
        return !closed && route != null && routes.contains(route);
    }

    /** Serializes consent so simultaneous spawns reuse a session grant, but never an allow-once answer. */
    public boolean approve(
            final String source, final String destination, final ApprovalGate gate, final BooleanSupplier cancelled) {
        if (closed || cancelled.getAsBoolean()) {
            return false;
        }
        try {
            lock.lockInterruptibly();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            if (closed || cancelled.getAsBoolean()) {
                return false;
            }
            if (allows(destination)) {
                return true;
            }
            if (gate == null) {
                return false;
            }
            final ApprovalGate.Result result;
            try {
                result = gate.approve(new ApprovalGate.Request(
                        ApprovalGate.Origin.PERMISSION,
                        "Allow sub-agent model " + destination + "?",
                        "Use " + destination + " instead of " + source
                                + ". The task and project context available to the sub-agent may be sent to this provider."
                                + " This approval is separate from tool permissions.",
                        "subagent_model_route",
                        Map.of("source", source, "destination", destination)));
            } catch (final RuntimeException unavailable) {
                return false;
            }
            if (closed || cancelled.getAsBoolean()) {
                return false;
            }
            if (result == ApprovalGate.Result.ALLOW_SESSION) {
                routes.add(destination);
                if (closed) {
                    routes.clear();
                    return false;
                }
                return true;
            }
            return result == ApprovalGate.Result.ALLOW_ONCE;
        } finally {
            lock.unlock();
        }
    }

    /** Disposal also prevents an in-flight approval from recreating a grant after the tab closes. */
    public void close() {
        closed = true;
        routes.clear();
    }
}
