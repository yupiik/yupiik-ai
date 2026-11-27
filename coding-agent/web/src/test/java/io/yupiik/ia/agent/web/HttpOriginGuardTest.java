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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.http.server.api.Body;
import io.yupiik.fusion.http.server.api.Cookie;
import io.yupiik.fusion.http.server.api.Request;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Unit tests of the shared loopback-only Host/Origin validation. */
class HttpOriginGuardTest {

    @Test
    void loopbackHostsAreAccepted() {
        assertTrue(HttpOriginGuard.isValidHost("localhost", null));
        assertTrue(HttpOriginGuard.isValidHost("localhost:6060", null));
        assertTrue(HttpOriginGuard.isValidHost("127.0.0.1", null));
        assertTrue(HttpOriginGuard.isValidHost("127.0.0.1:6060", null));
        assertTrue(HttpOriginGuard.isValidHost("127.8.9.10:1234", null));
        assertTrue(HttpOriginGuard.isValidHost("[::1]", null));
        assertTrue(HttpOriginGuard.isValidHost("[::1]:6060", null));
    }

    @Test
    void foreignHostsAreRejected() {
        assertFalse(HttpOriginGuard.isValidHost("example.com", null));
        assertFalse(HttpOriginGuard.isValidHost("evil.com:6060", null));
        assertFalse(HttpOriginGuard.isValidHost("10.0.0.1", null));
        assertFalse(HttpOriginGuard.isValidHost("192.168.1.1:80", null));
        assertFalse(HttpOriginGuard.isValidHost("::ffff:10.0.0.1", null));
        assertFalse(HttpOriginGuard.isValidHost("", null));
        assertFalse(HttpOriginGuard.isValidHost(null, null));
        // 127. with non-octet garbage must not pass the IPv4 shape check
        assertFalse(HttpOriginGuard.isValidHost("127.0.0", null));
        assertFalse(HttpOriginGuard.isValidHost("127.0.0.1.5", null));
        assertFalse(HttpOriginGuard.isValidHost("127.0.0.a", null));
    }

    @Test
    void explicitlyBoundHostIsAccepted() {
        assertTrue(HttpOriginGuard.isValidHost("myhost:6060", "myhost"));
        assertTrue(HttpOriginGuard.isValidHost("myhost", "myhost"));
        // the bound host is compared case-insensitively and without its port
        assertTrue(HttpOriginGuard.isValidHost("MYHOST:6060", "myhost"));
        // but a foreign host is still rejected even when a bound host is configured
        assertFalse(HttpOriginGuard.isValidHost("evil.com", "myhost"));
    }

    @Test
    void loopbackOriginsAreAccepted() {
        assertTrue(HttpOriginGuard.isValidOrigin("http://localhost"));
        assertTrue(HttpOriginGuard.isValidOrigin("http://localhost:6060"));
        assertTrue(HttpOriginGuard.isValidOrigin("https://localhost:6060"));
        assertTrue(HttpOriginGuard.isValidOrigin("http://127.0.0.1:6060"));
        assertTrue(HttpOriginGuard.isValidOrigin("http://[::1]:6060"));
        assertTrue(HttpOriginGuard.isValidOrigin("http://[::1]"));
        assertTrue(HttpOriginGuard.isValidOrigin("null")); // sandboxed/opaque origins
        assertTrue(HttpOriginGuard.isValidOrigin("NULL"));
    }

    @Test
    void foreignOriginsAreRejected() {
        assertFalse(HttpOriginGuard.isValidOrigin("http://evil.com"));
        assertFalse(HttpOriginGuard.isValidOrigin("https://evil.com:443"));
        assertFalse(HttpOriginGuard.isValidOrigin("http://10.0.0.1"));
        assertFalse(HttpOriginGuard.isValidOrigin("ftp://localhost"));
        assertFalse(HttpOriginGuard.isValidOrigin("localhost")); // no scheme
        assertFalse(HttpOriginGuard.isValidOrigin(""));
        assertFalse(HttpOriginGuard.isValidOrigin(null));
    }

    @Test
    void portOnlyHostFormIsRejected() {
        // "localhost:" (empty port) and ":6060" (no host) are not valid host forms
        assertFalse(HttpOriginGuard.isValidHost(":6060", null));
        assertFalse(HttpOriginGuard.isValidHost("localhost:", null));
    }

    @Test
    void missingOrNullOriginIsAcceptable() {
        assertNull(HttpOriginGuard.rejectIfForeign(mockRequest("127.0.0.1:6060", null), null));
        // a request with a foreign Origin AND a loopback Host must be rejected
        final var rejected = HttpOriginGuard.rejectIfForeign(mockRequest("127.0.0.1:6060", "http://evil.com"), null);
        assertTrue(rejected != null && rejected.status() == 403);
    }

    private static Request mockRequest(final String host, final String origin) {
        return new Request() {
            @Override
            public String scheme() {
                return "http";
            }

            @Override
            public String method() {
                return "GET";
            }

            @Override
            public String path() {
                return "/";
            }

            @Override
            public String query() {
                return null;
            }

            @Override
            public Body fullBody() {
                return null;
            }

            @Override
            public Stream<Cookie> cookies() {
                return Stream.empty();
            }

            @Override
            public String parameter(final String name) {
                return null;
            }

            @Override
            public Map<String, String[]> parameters() {
                return Map.of();
            }

            @Override
            public String header(final String name) {
                if ("host".equalsIgnoreCase(name)) {
                    return host;
                }
                if ("origin".equalsIgnoreCase(name)) {
                    return origin;
                }
                return null;
            }

            @Override
            public Map<String, List<String>> headers() {
                return Map.of();
            }

            @Override
            public <T> T attribute(final String key, final Class<T> type) {
                return null;
            }

            @Override
            public <T> void setAttribute(final String key, final T value) {}
        };
    }
}
