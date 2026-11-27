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
import java.util.List;
import java.util.Locale;

/**
 * Matches HTTP authorization headers and generic secret key/value assignments embedded in text:
 * {@code Authorization: Bearer <token>}, {@code Authorization: Basic <b64-capable>},
 * {@code client_secret=...}, {@code access_token=...}, {@code api_key=...} / {@code apikey=...} /
 * {@code api-key=...}, {@code password=...} / {@code passwd=...} / {@code DB_PASSWORD=...}. Only the
 * assigned value is replaced, reusing {@link ShapePreservingSecretMatcher#replace}.
 *
 * <p>Deliberately regex-free: detection uses lowercase {@code contains} on a fixed scheme/key-name
 * word list and an {@code indexOf} on the assignment separator ({@code =} or {@code :}), so scanning
 * stays O(len) and cannot over-match a substring inside an unrelated token the way a {@code find()}
 * regex could.</p>
 */
@ApplicationScoped
public class AuthAssignmentSecretMatcher extends ShapePreservingSecretMatcher {

    /** Authorization scheme words (lowercase). */
    private static final List<String> SCHEMES = List.of("bearer", "basic");

    /** Secret-name markers looked for (case-insensitive contains) in an assignment key. */
    private static final List<String> ASSIGN_MARKERS = List.of(
            "client_secret",
            "access_token",
            "refresh_token",
            "api_key",
            "apikey",
            "api-key",
            "auth_token",
            "password",
            "passwd",
            "db_password",
            "secret",
            "credential");

    @Override
    public boolean matches(final String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        final var lower = value.toLowerCase(Locale.ROOT);
        return schemeOffset(lower) >= 0 || isAssignment(value);
    }

    @Override
    protected int bodyStart(final String value) {
        final var lower = value.toLowerCase(Locale.ROOT);
        final var scheme = schemeOffset(lower);
        if (scheme >= 0) {
            return scheme; // preserve "Authorization: Bearer " and regenerate the token
        }
        final var sep = separator(value);
        return sep < 0 ? value.length() : sep + 1;
    }

    @Override
    public int order() {
        return 2_000;
    }

    @Override
    public String label() {
        return "auth-assignment";
    }

    /**
     * @return the start index (in the original) of the token after a "{@code scheme} " call, or -1.
     */
    private static int schemeOffset(final String lower) {
        for (final var scheme : SCHEMES) {
            int idx = lower.indexOf(scheme);
            while (idx >= 0) {
                // the scheme must start at a non-word boundary and be followed by whitespace
                if ((idx == 0 || !isWordChar(lower.charAt(idx - 1)))
                        && idx + scheme.length() < lower.length()
                        && Character.isWhitespace(lower.charAt(idx + scheme.length()))) {
                    return idx + scheme.length() + 1;
                }
                idx = lower.indexOf(scheme, idx + 1);
            }
        }
        return -1;
    }

    private static boolean isAssignment(final String value) {
        final var sep = separator(value);
        if (sep <= 0) {
            return false; // no separator, or nothing before it
        }
        final var key = value.substring(0, sep).trim().toLowerCase(Locale.ROOT);
        for (final var marker : ASSIGN_MARKERS) {
            if (key.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** The first {@code =} or {@code :} separator index, or -1. */
    private static int separator(final String value) {
        final var eq = value.indexOf('=');
        final var colon = value.indexOf(':');
        final var eqOk = eq >= 0 ? eq : Integer.MAX_VALUE;
        final var colonOk = colon >= 0 ? colon : Integer.MAX_VALUE;
        final var min = Math.min(eqOk, colonOk);
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private static boolean isWordChar(final char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}
