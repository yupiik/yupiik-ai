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
package io.yupiik.ia.agent.core.memory;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session control of whether <em>new</em> durable memory is produced, toggled with
 * {@code /memory enabled=false} (and back on). Turning a session's memory production off only
 * stops the automatic extractor and the {@code memory_write} tool from persisting new facts for
 * that tab; it intentionally does NOT change the system prompt, so the already-injected memory
 * section and reads stay intact. Each toggle defaults to the config-driven behavior (producing).
 *
 * <p>Keyed by the chat {@code sessionId}, so every tab owns its own flag and a {@code /new}
 * (which changes the {@code sessionId}) naturally returns to the configured default. Entries are
 * only created for sessions that explicitly toggled the flag, and are cleared when the session
 * closes (see {@code AgentLoop.close}) so nothing leaks in this application-scoped singleton.</p>
 */
@ApplicationScoped
public class MemoryProductionPolicy {

    private final Map<String, Boolean> bySession = new ConcurrentHashMap<>();

    /** Sets the production flag for a session; {@code null}/{@code enabled} clears the override. */
    public void set(final String sessionId, final boolean enabled) {
        final var key = safe(sessionId);
        if (enabled) {
            bySession.remove(key);
        } else {
            bySession.put(key, Boolean.FALSE);
        }
    }

    /**
     * Whether the session produces new memory. Missing/blank sessions (sub-agents, standalone
     * tool invocations) default to producing, so disabling is never a silent trap on shared paths.
     */
    public boolean enabled(final String sessionId) {
        return bySession.getOrDefault(safe(sessionId), Boolean.TRUE);
    }

    /** Drops the per-session flag; idempotent. Call when a session closes to avoid leaking. */
    public void clear(final String sessionId) {
        bySession.remove(safe(sessionId));
    }

    private static String safe(final String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "" : sessionId;
    }
}
