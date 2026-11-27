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

import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import java.util.Locale;

/**
 * Shared Host/Origin validation for every web endpoint (JSON-RPC, SSE, static assets).
 *
 * <p>The server is a local agent control panel, so both checks are loopback-only:
 * <ul>
 *   <li>{@link #isValidHost(String, String)} - the {@code Host} header must be a loopback form
 *       ({@code localhost}, {@code 127.0.0.1} or any {@code 127.x.x.x}, {@code ::1}, optionally
 *       bracketed and optionally with a port), or the exact host the user explicitly bound with
 *       {@code --host}. Anything else is a DNS-rebinding attempt and rejected with 403.</li>
 *   <li>{@link #isValidOrigin(String)} - a present {@code Origin} header must be {@code null}
 *       (string, sent by some browsers on sandboxed origins), missing (curl, non-browser), or a
 *       loopback {@code http(s)://localhost|127.0.0.1|[::1][:port]}. A foreign origin would allow
 *       a hostile website to drive the local agent (CSRF), so it is rejected with 403.</li>
 * </ul>
 */
final class HttpOriginGuard {
    private HttpOriginGuard() {
        // no-op
    }

    /** 403 response body for rejected requests. */
    static final String FORBIDDEN_BODY = "forbidden";

    /**
     * Rejects a request whose {@code Host} header is not loopback (or the explicitly bound host)
     * and whose {@code Origin} header, when present, is not a loopback origin.
     *
     * @param request      the incoming request
     * @param boundHost    the host the server was told to bind to ({@code --host}), may be null/blank
     * @return a 403 {@link Response} when the request must be rejected, {@code null} to let it through
     */
    static Response rejectIfForeign(final Request request, final String boundHost) {
        final var host = request.header("host");
        if (!isValidHost(host, boundHost)) {
            return Response.of().status(403).body(FORBIDDEN_BODY).build();
        }
        final var origin = request.header("origin");
        if (origin != null && !origin.isBlank() && !isValidOrigin(origin)) {
            return Response.of().status(403).body(FORBIDDEN_BODY).build();
        }
        return null;
    }

    /**
     * @param hostValue the raw {@code Host} header value (may include a port)
     * @param boundHost the host explicitly bound via {@code --host}; also accepted when non-blank
     * @return {@code true} when the host is a loopback form or equals the bound host
     */
    static boolean isValidHost(final String hostValue, final String boundHost) {
        if (hostValue == null || hostValue.isBlank()) {
            return false;
        }
        final var host = hostWithoutPort(hostValue.trim());
        if (isLoopbackHostLiteral(host)) {
            return true;
        }
        final var bound = boundHost == null ? null : hostWithoutPort(boundHost.trim());
        return bound != null && !bound.isBlank() && host.equalsIgnoreCase(bound);
    }

    /**
     * @param origin the raw {@code Origin} header value
     * @return {@code true} when the origin is the literal {@code null}, or a loopback
     *         {@code http(s)://localhost|127.0.0.1|[::1]} with an optional port
     */
    static boolean isValidOrigin(final String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        final var value = origin.trim();
        if (value.equalsIgnoreCase("null")) {
            return true;
        }
        final int schemeEnd;
        if (value.regionMatches(true, 0, "http://", 0, 7)) {
            schemeEnd = 7;
        } else if (value.regionMatches(true, 0, "https://", 0, 8)) {
            schemeEnd = 8;
        } else {
            return false;
        }
        final var hostPort = value.substring(schemeEnd);
        final var host = hostWithoutPort(hostPort);
        return !host.isEmpty() && isLoopbackHostLiteral(host);
    }

    /** Strips an optional {@code :port} suffix (IPv6 brackets are preserved by the caller). */
    private static String hostWithoutPort(final String value) {
        final var trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            final var close = trimmed.indexOf(']');
            return close < 0 ? trimmed : trimmed.substring(1, close); // bracketed IPv6: [::1]:6060 -> ::1
        }
        final var colon = trimmed.indexOf(':');
        if (colon == trimmed.length() - 1) {
            return trimmed; // trailing ':' = empty port, not a valid host:port form
        }
        return colon < 0 ? trimmed : trimmed.substring(0, colon);
    }

    /** Whether {@code host} is localhost, 127.0.0.1, any 127.x.x.x, or ::1 (case-insensitive). */
    private static boolean isLoopbackHostLiteral(final String host) {
        if (host == null || host.isEmpty() || host.indexOf('/') >= 0) {
            return false;
        }
        final var lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.equals("::1")) {
            return true;
        }
        if (lower.equals("127.0.0.1")) {
            return true;
        }
        if (lower.startsWith("127.")) {
            return isIpv4(lower);
        }
        return false;
    }

    private static boolean isIpv4(final String value) {
        final var parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (final var part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }
}
