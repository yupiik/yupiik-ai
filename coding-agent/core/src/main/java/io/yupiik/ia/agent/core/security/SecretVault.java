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
package io.yupiik.ia.agent.core.security;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Per-session provenance vault: the {@code Map<fake, real>} of every format-preserving replacement
 * the redactor emitted, plus the reverse real -> fake lookup so the same real value maps to the same
 * fake within a session. Inbound, only fake strings present here are restored, so a model-invented
 * token-shaped value passes through untouched (Swival's provenance model).
 *
 * <p>Ephemeral by design: the mappings live in memory only, keyed by session id, and are bounded to
 * {@link #MAX_ENTRIES_PER_SESSION} per session. Nothing is persisted; local storage keeps the fake
 * (never the raw value) so at-rest history holds no plaintext secret.</p>
 */
@ApplicationScoped
public class SecretVault {

    /** Upper bound on retained fake->real mappings per session (guards runaway conversations). */
    public static final int MAX_ENTRIES_PER_SESSION = 4_096;

    private final ConcurrentHashMap<String, BoundedMap> bySession = new ConcurrentHashMap<>();

    /** Remembers that fake (emitted for sessionId) stands for the real value; null-safe. */
    public void record(final String sessionId, final String fake, final String real) {
        if (fake == null || real == null || fake.equals(real)) {
            return;
        }
        bySession.computeIfAbsent(safe(sessionId), _ -> new BoundedMap()).record(fake, real);
    }

    /** The real value behind {@code fake} in this session, or null when not emitted here. */
    public String realOf(final String sessionId, final String fake) {
        if (fake == null) {
            return null;
        }
        final var map = bySession.get(safe(sessionId));
        return map == null ? null : map.fakeToReal.get(fake);
    }

    /** The fake already associated with {@code real} in this session, or null. */
    public String fakeOf(final String sessionId, final String real) {
        if (real == null) {
            return null;
        }
        final var map = bySession.get(safe(sessionId));
        return map == null ? null : map.realToFake.get(real);
    }

    public int count(final String sessionId) {
        final var map = bySession.get(safe(sessionId));
        return map == null ? 0 : map.fakeToReal.size();
    }

    /** Invokes {@code consumer} for every recorded {@code (fake, real)} pair, longest fake first. */
    public void forEachFake(final String sessionId, final BiConsumer<String, String> consumer) {
        final var map = bySession.get(safe(sessionId));
        if (map == null) {
            return;
        }
        map.fakeToReal.entrySet().stream()
                .sorted(Map.Entry.<String, String>comparingByKey().reversed())
                .forEach(e -> consumer.accept(e.getKey(), e.getValue()));
    }

    /** Drops all in-session mappings (session close / /secrets toggle). */
    public void clear(final String sessionId) {
        bySession.remove(safe(sessionId));
    }

    private static String safe(final String sessionId) {
        // For milestone 1 the vault is process-global: yca is a single interactive user per
        // process (CLI/TUI/run and a serialized web use case), so outbound (in ChatClient, which
        // has no session id) and tool-arg restore (where the session id IS known) share the same
        // fake->real mapping. A future multi-session hardening can scope by session id here.
        return "global";
    }

    /** A bounded, insertion-ordered bidirectional map (FIFO eviction over the cap). */
    private static final class BoundedMap {
        private final LinkedHashMap<String, String> fakeToReal = new LinkedHashMap<>(64, 0.75f, true);
        private final Map<String, String> realToFake = new ConcurrentHashMap<>();
        private final AtomicLong seq = new AtomicLong();

        synchronized void record(final String fake, final String real) {
            final var existing = fakeToReal.putIfAbsent(fake, real);
            if (existing == null) {
                realToFake.putIfAbsent(real, fake);
            }
            seq.incrementAndGet();
            while (fakeToReal.size() > MAX_ENTRIES_PER_SESSION) {
                final var eldest = fakeToReal.keySet().iterator().next();
                final var removed = fakeToReal.remove(eldest);
                if (removed != null) {
                    realToFake.remove(removed, eldest);
                }
            }
        }
    }
}
