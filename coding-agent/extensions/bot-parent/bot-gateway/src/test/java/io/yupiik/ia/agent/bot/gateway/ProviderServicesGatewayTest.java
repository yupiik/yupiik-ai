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
package io.yupiik.ia.agent.bot.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.Headers;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderServicesGatewayTest {
    private static final String ROUTE = "/inference/12345678-1234-1234-1234-123456789abc/chat/completions";
    private static final String AUTH = "Bearer " + "p".repeat(43);

    @Test
    void acceptsOnlyCanonicalOperatorFixedPrivateTargets() {
        for (final var host : List.of("127.0.0.1", "10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.2")) {
            assertDoesNotThrow(() -> settings("http://" + host + ":8080/v1/chat/completions", ROUTE, 4));
        }
        for (final var target : List.of(
                "https://10.0.0.1:8080/v1/chat/completions",
                "http://localhost:8080/v1/chat/completions",
                "http://169.254.169.254:8080/v1",
                "http://172.32.0.1:8080/v1",
                "http://8.8.8.8:8080/v1",
                "http://100.64.0.1:8080/v1",
                "http://0.0.0.0:8080/v1",
                "http://[::1]:8080/v1",
                "http://127.00.0.1:8080/v1",
                "http://127.1:8080/v1",
                "http://2130706433:8080/v1",
                "http://10.0.0.256:8080/v1",
                "http://user@10.0.0.1:8080/v1",
                "http://10.0.0.1/v1",
                "http://10.0.0.1:0/v1",
                "http://10.0.0.1:65536/v1",
                "http://10.0.0.1:8080/",
                "http://10.0.0.1:8080/v1/",
                "http://10.0.0.1:8080/v1?destination=other",
                "http://10.0.0.1:8080/v1#fragment",
                "http://10.0.0.1:8080/v1/../v2",
                "http://10.0.0.1:8080/v1/%2e%2e/v2",
                "http://10.0.0.1:8080//v1")) {
            assertThrows(IllegalArgumentException.class, () -> settings(target, ROUTE, 4), target);
        }
    }

    @Test
    void rejectsMissingOrUnboundedLimitsAndUnsafeTlsConfiguration() {
        for (final var concurrency : List.of(-1, 0, 65)) {
            assertThrows(IllegalArgumentException.class, () -> settings("http://10.0.0.1:80/v1", ROUTE, concurrency));
        }
        for (final var route : List.of("/", "/chat/completions", ROUTE + "/", ROUTE.toUpperCase())) {
            assertThrows(IllegalArgumentException.class, () -> settings("http://10.0.0.1:80/v1", route, 1));
        }
        for (final var env :
                List.of("PATH", "JAVA_TOOL_OPTIONS", "LD_PRELOAD", "DOCKER_HOST", "YCA_SERVICES_PROVIDER_TOKEN")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ProviderServicesGateway.Settings(
                            "services.internal",
                            8443,
                            "/services/tls.p12",
                            env,
                            ROUTE,
                            "http://10.0.0.1:8080/v1/chat/completions",
                            60,
                            5,
                            1024,
                            2048,
                            1));
        }
        for (final var limits : List.of(
                new long[] {0, 5, 1024, 2048},
                new long[] {86401, 5, 1024, 2048},
                new long[] {60, 0, 1024, 2048},
                new long[] {60, 901, 1024, 2048},
                new long[] {60, 5, 0, 2048},
                new long[] {60, 5, 16777217, 2048},
                new long[] {60, 5, 1024, 0},
                new long[] {60, 5, 1024, 16777217})) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ProviderServicesGateway.Settings(
                            "services.internal",
                            8443,
                            "/services/tls.p12",
                            "TLS_PASSWORD",
                            ROUTE,
                            "http://10.0.0.1:8080/v1/chat/completions",
                            limits[0],
                            (int) limits[1],
                            limits[2],
                            limits[3],
                            1));
        }
    }

    @Test
    void fixedRequestPolicyRejectsProxyAndBusinessRoutesWithoutTransportStartup() {
        final var settings = settings("http://10.0.0.1:8080/v1/chat/completions", ROUTE, 4);
        assertEquals(0, settings.reject("POST", URI.create(ROUTE), headers(), 8443, AUTH));
        for (final var route : List.of(
                "/context/12345678-1234-1234-1234-123456789abc/rpc",
                "/context/12345678-1234-1234-1234-123456789abc/git/repo.git/info/refs",
                "/slack/api/chat.postMessage",
                ROUTE + "/",
                ROUTE.replace("chat", "%63hat"),
                "http://10.0.0.2:8080" + ROUTE,
                "//10.0.0.2:8080" + ROUTE,
                ROUTE + "#fragment")) {
            assertEquals(400, settings.reject("POST", URI.create(route), headers(), 8443, AUTH), route);
        }

        for (final var method : List.of("GET", "PUT", "CONNECT", "OPTIONS")) {
            assertEquals(405, settings.reject(method, URI.create(ROUTE), headers(), 8443, AUTH));
        }
        assertEquals(405, settings.reject("POST", URI.create(ROUTE + "?target=other"), headers(), 8443, AUTH));
        final var missing = headers();
        missing.remove("Authorization");
        assertEquals(401, settings.reject("POST", URI.create(ROUTE), missing, 8443, AUTH));
        missing.set("Authorization", "Bearer " + "q".repeat(43));
        assertEquals(401, settings.reject("POST", URI.create(ROUTE), missing, 8443, AUTH));
    }

    @Test
    void responsesPinsOneExactRouteAndRejectsMismatchedOrUnsupportedTargets() {
        final var route = ROUTE.replace("/chat/completions", "/responses");
        final var settings = settings("http://10.0.0.1:8080/v1/responses", route, 1);
        assertEquals(0, settings.reject("POST", URI.create(route), headers(), 8443, AUTH));
        for (final var rejected : List.of(
                ROUTE, route + "/", route + "/compact", route + "/id", route.replace("responses", "%72esponses"))) {
            assertEquals(400, settings.reject("POST", URI.create(rejected), headers(), 8443, AUTH));
        }
        assertEquals(405, settings.reject("POST", URI.create(route + "?target=other"), headers(), 8443, AUTH));
        assertEquals(405, settings.reject("GET", URI.create(route), headers(), 8443, AUTH));
        for (final var target : List.of("/chat/completions", "/messages", "/responses/compact", "/responses/id", "")) {
            assertThrows(IllegalArgumentException.class, () -> settings("http://10.0.0.1:8080/v1" + target, route, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> settings("http://10.0.0.1:8080/v1/responses", ROUTE, 1));
    }

    @Test
    void headerAndBodyFramingPolicyIsBounded() {
        final var settings = settings("http://10.0.0.1:8080/v1/chat/completions", ROUTE, 4);
        for (final var name : List.of("Host", "Authorization", "Content-Type", "Content-Length", "X-Header")) {
            final var headers = headers();
            headers.add(name, "duplicate");
            headers.add(name, "duplicate");
            assertEquals(400, settings.reject("POST", URI.create(ROUTE), headers, 8443, AUTH), name);
        }
        for (final var name : List.of("Origin", "Upgrade", "Expect", "Proxy-Authorization")) {
            final var headers = headers();
            headers.set(name, "value");
            assertEquals(400, settings.reject("POST", URI.create(ROUTE), headers, 8443, AUTH), name);
        }
        for (final var type : List.of("text/plain", "application/x-git-upload-pack-request", "")) {
            final var headers = headers();
            headers.set("Content-Type", type);
            assertEquals(415, settings.reject("POST", URI.create(ROUTE), headers, 8443, AUTH));
        }
        final var headers = headers();
        headers.set("Content-Encoding", "gzip");
        assertEquals(415, settings.reject("POST", URI.create(ROUTE), headers, 8443, AUTH));
        headers.remove("Content-Encoding");
        headers.set("Git-Protocol", "version=2");
        assertEquals(415, settings.reject("POST", URI.create(ROUTE), headers, 8443, AUTH));
        headers.remove("Git-Protocol");
        for (final var length : List.of("1025", "-1", "1,2", "99999999999", "")) {
            headers.set("Content-Length", length);
            assertEquals(413, settings.reject("POST", URI.create(ROUTE), headers, 8443, AUTH));
        }
        headers.set("Content-Length", "1024");
        assertEquals(0, settings.reject("POST", URI.create(ROUTE), headers, 8443, AUTH));
        headers.set("Transfer-Encoding", "chunked");
        assertEquals(400, settings.reject("POST", URI.create(ROUTE), headers, 8443, AUTH));
        headers.remove("Content-Length");
        assertEquals(0, settings.reject("POST", URI.create(ROUTE), headers, 8443, AUTH));
        headers.set("Transfer-Encoding", "gzip");
        assertEquals(400, settings.reject("POST", URI.create(ROUTE), headers, 8443, AUTH));
        headers.remove("Transfer-Encoding");
        headers.set("X-Large", "x".repeat(16384));
        assertEquals(400, settings.reject("POST", URI.create(ROUTE), headers, 8443, AUTH));
        headers.remove("X-Large");
        for (int i = 0; i < 65; i++) {
            headers.set("X-" + i, "value");
        }
        assertEquals(400, settings.reject("POST", URI.create(ROUTE), headers, 8443, AUTH));
    }

    @Test
    void configurationRejectsGithubCapabilityPresenceEvenWhenBlank() {
        final var settings = settings("http://10.0.0.1:8080/v1/chat/completions", ROUTE, 1);
        for (final var key : List.of("YCA_CONTEXT_TOKEN", "YCA_CONTEXT_GIT_TOKEN")) {
            for (final var value : List.of("", "c".repeat(43))) {
                final var rejected = assertThrows(
                        IllegalArgumentException.class,
                        () -> new ProviderServicesGateway(
                                settings,
                                Configuration.of(Map.of(key, value)),
                                new ProviderBudgetConfiguration(null, null, null)));
                assertEquals("GitHub capabilities are not supported", rejected.getMessage());
            }
        }
    }

    @Test
    void invalidCredentialsAreRejectedBeforeOpeningTlsAndDoNotEscapeInDiagnostics() {
        final var settings = settings("http://10.0.0.1:8080/v1/chat/completions", ROUTE, 1);
        final var missing = assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderServicesGateway(
                        settings, Configuration.of(Map.of()), new ProviderBudgetConfiguration(null, null, null)));
        assertEquals("Invalid services credential", missing.getMessage());
        for (final var credential : List.of("secret\r\ninjected: true", "secret value", "s".repeat(4097))) {
            final var invalid = assertThrows(
                    IllegalArgumentException.class,
                    () -> new ProviderServicesGateway(
                            settings,
                            Configuration.of(Map.of(
                                    ProviderServicesGateway.YCA_SERVICES_PROVIDER_TOKEN,
                                    "p".repeat(43),
                                    ProviderServicesGateway.YCA_SERVICES_UPSTREAM_TOKEN,
                                    credential)),
                            new ProviderBudgetConfiguration(null, null, null)));
            assertEquals("Invalid upstream credential", invalid.getMessage());
        }
    }

    @Test
    void settingsJsonRoundTripIncludesExplicitLocalConcurrencyLimit() {
        try (final var json = JsonMappers.create(ignored -> Optional.empty())) {
            final var settings = settings("http://10.0.0.1:8080/v1/chat/completions", ROUTE, 3);
            final var encoded = json.toString(settings);
            assertEquals(settings, json.fromString(ProviderServicesGateway.Settings.class, encoded));
            assertTrue(encoded.contains("\"maxConcurrent\":3"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> json.fromString(
                            ProviderServicesGateway.Settings.class,
                            encoded.replace("\"maxConcurrent\":3", "\"maxConcurrent\":0")));
        }
    }

    private ProviderServicesGateway.Settings settings(final String target, final String route, final int concurrency) {
        return new ProviderServicesGateway.Settings(
                "services.internal",
                8443,
                "/services/tls.p12",
                "TLS_PASSWORD",
                route,
                target,
                60,
                5,
                1024,
                2048,
                concurrency);
    }

    private Headers headers() {
        final var headers = new Headers();
        headers.set("Host", "services.internal:8443");
        headers.set("Authorization", AUTH);
        headers.set("Content-Type", "application/json; charset=utf-8");
        return headers;
    }
}
