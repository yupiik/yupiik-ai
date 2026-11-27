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
import java.util.regex.Pattern;

/**
 * Redacts the credentials embedded in connection URIs (MongoDB, PostgreSQL, MySQL, Redis, JDBC,
 * generic {@code scheme://user:password@host}). The scheme + host are preserved so the LLM can still
 * reason about the target; only the {@code user:password@} authority is replaced, keeping the host.
 */
@ApplicationScoped
public class ConnectionUriSecretMatcher implements SecretMatcher {

    private static final Pattern URI = Pattern.compile(
            "^((?:mongodb\\+srv|mongodb|postgresql|postgres|mysql|redis|rediss|jdbc)(?:\\+srv)?://)([^/@\\n]+)@([^\\n]+)$");

    @Override
    public boolean matches(final String value) {
        return value != null && URI.matcher(value).matches();
    }

    @Override
    public String replace(final String value) {
        final var m = URI.matcher(value);
        if (!m.matches()) {
            return value;
        }
        final var scheme = m.group(1);
        final var authority = m.group(2);
        final var host = m.group(3);
        final var lb = authority.lastIndexOf(':');
        final String masked;
        if (lb < 0) {
            masked = "redacted"; // bare user, no password
        } else {
            masked = authority.substring(0, lb) + ":" + "x".repeat(Math.clamp(authority.length() - lb - 1, 1, 16));
        }
        return scheme + masked + "@" + host;
    }

    @Override
    public int order() {
        return 9_000;
    }

    @Override
    public String label() {
        return "connection-uri";
    }
}
