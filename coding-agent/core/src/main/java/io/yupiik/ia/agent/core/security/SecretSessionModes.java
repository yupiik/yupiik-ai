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
import io.yupiik.ia.agent.core.configuration.SecretRedactionMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session {@link SecretRedactionMode} overrides, set with {@code /secrets redact|passthrough|
 * fail}. The {@link SecretRedactor} consults these first and falls back to the global
 * {@code yca.secrets.mode} default. Applies to tui / cli / run / web since they share the slash
 * command registry and the session-id keyed vault.
 */
@ApplicationScoped
public class SecretSessionModes {

    private final Map<String, SecretRedactionMode> bySession = new ConcurrentHashMap<>();

    public SecretRedactionMode set(final String sessionId, final SecretRedactionMode mode) {
        if (mode == null) {
            return bySession.remove(safe(sessionId));
        }
        final var previous = bySession.put(safe(sessionId), mode);
        return previous;
    }

    public SecretRedactionMode of(final String sessionId) {
        return bySession.get(safe(sessionId));
    }

    public SecretRedactionMode ofOrDefault(final String sessionId, final SecretRedactionMode fallback) {
        final var v = of(sessionId);
        return v == null ? fallback : v;
    }

    public void clear(final String sessionId) {
        bySession.remove(safe(sessionId));
    }

    private static String safe(final String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "global" : sessionId;
    }
}
