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
package io.yupiik.ia.agent.bot.runtime.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.bot.gateway.EgressGateway;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutorConfigurationTest {
    @Test
    void toolOutputIsShownByDefault() {
        assertEquals("shown", configuration("shown", null).showToolOutput());
    }

    @Test
    void normalizesToolOutputVisibility() {
        for (final var visibility : List.of("shown", "collapsed", "hidden")) {
            assertEquals(visibility, configuration(visibility, null).showToolOutput());
        }
        assertEquals("shown", configuration(" SHOW ", null).showToolOutput());
        assertEquals("hidden", configuration("false", null).showToolOutput());
    }

    @Test
    void rejectsInvalidToolOutputVisibility() {
        for (final var value : List.of("", " ", "verbose", "not-a-mode")) {
            final var failure = assertThrows(IllegalArgumentException.class, () -> configuration(value, null));
            assertEquals("executor.showToolOutput must be shown, collapsed or hidden", failure.getMessage());
        }
    }

    @Test
    void unconfiguredDockerDoesNotEnableEitherGateway() {
        final var docker = new ExecutorConfiguration.Docker("docker", "agent", "", null, null, false);
        assertFalse(docker.disableContainerLogs());
        assertNull(docker.services());
        assertNull(docker.egress());
    }

    @Test
    void explicitContainerLogSuppressionPreservesDockerSettings() {
        final var docker = new ExecutorConfiguration.Docker("docker", "agent", "trusted", null, null, true);
        assertTrue(docker.disableContainerLogs());
        assertEquals("trusted", docker.network());
        assertNull(docker.egress());
        assertNull(docker.services());
    }

    @Test
    void servicesDefaultToKnownPublicDependenciesWithoutEnablingTopLevelEgress() {
        final var docker = services(Map.of());
        assertNull(docker.egress());
        final var egress = docker.services().dependencyEgress();
        assertTrue(egress.enabled());
        assertEquals("trusted-services", egress.image());
        assertEquals("127.0.0.1", egress.dns());
        assertEquals(
                List.of(
                        "github.com",
                        "api.github.com",
                        "codeload.github.com",
                        "objects.githubusercontent.com",
                        "raw.githubusercontent.com",
                        "repo.maven.apache.org",
                        "repo1.maven.org",
                        "registry.npmjs.org",
                        "docker.io",
                        "*.docker.io",
                        "production.cloudflare.docker.com",
                        "docker-images-prod.6aa30f8b08e16409b46e0173d6de2f56.r2.cloudflarestorage.com",
                        "cdn.azul.com",
                        "api.azul.com",
                        "*.apache.org"),
                egress.allowedHosts());
    }

    @Test
    void defaultDependencyHostsAllowToolchainsOnlyWithinHostBoundaries() throws Exception {
        final var hosts = services(Map.of()).services().dependencyEgress().allowedHosts();
        try (final var gateway =
                new EgressGateway(new InetSocketAddress("127.0.0.1", 0), hosts, Duration.ofSeconds(2))) {
            for (final var host : hosts) {
                if (!host.startsWith("*.")) {
                    assertTrue(gateway.allowsHost(host), host);
                }
            }
            for (final var host : List.of(
                    "CDN.Azul.COM",
                    "API.Azul.COM",
                    "maven.apache.org",
                    "archive.apache.org",
                    "dlcdn.apache.org",
                    "downloads.apache.org",
                    "a.b.apache.org",
                    "registry-1.docker.io")) {
                assertTrue(gateway.allowsHost(host), host);
            }
            for (final var host : List.of(
                    "azul.com",
                    "other.azul.com",
                    "sub.cdn.azul.com",
                    "sub.api.azul.com",
                    "evilcdn.azul.com",
                    "cdn.azul.com.evil.example",
                    "api.azul.com.evil.example",
                    "apache.org",
                    "evilapache.org",
                    "maven.apache.org.evil.example",
                    "*.apache.org",
                    "a..apache.org",
                    "maven.apache.org.",
                    "maven.apache.org:443",
                    "user@maven.apache.org",
                    "https://cdn.azul.com/zulu/bin/",
                    "cdn.azul.com/zulu/bin/",
                    "archive.apache.org/dist/maven/",
                    "127.0.0.1",
                    "169.254.169.254",
                    "[::1]")) {
                assertFalse(gateway.allowsHost(host), host);
            }
            assertFalse(gateway.allowsHost(null));
        }
    }

    @Test
    void defaultDependencyPolicyRejectsNonConnectRequestsAndNonHttpsPortsWithoutDns() throws Exception {
        try (final var gateway = new EgressGateway(
                new InetSocketAddress("127.0.0.1", 0),
                services(Map.of()).services().dependencyEgress().allowedHosts(),
                Duration.ofSeconds(2))) {
            for (final var request : List.of(
                    "GET https://cdn.azul.com/zulu/bin/ HTTP/1.1\r\n\r\n",
                    "CONNECT api.azul.com:80 HTTP/1.1\r\n\r\n",
                    "CONNECT archive.apache.org:8443 HTTP/1.1\r\n\r\n",
                    "CONNECT apache.org:443 HTTP/1.1\r\n\r\n",
                    "CONNECT cdn.azul.com/zulu/bin/:443 HTTP/1.1\r\n\r\n")) {
                try (final var socket = new Socket("127.0.0.1", gateway.port())) {
                    socket.setSoTimeout(3_000);
                    socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                    assertTrue(
                            new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII)
                                    .startsWith("HTTP/1.1 403"),
                            request);
                }
            }
        }
    }

    @Test
    void dependencyGatewayStillRequiresPublicAddresses() throws Exception {
        for (final var ip :
                List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "::1", "fc00::1")) {
            assertFalse(EgressGateway.isPublicAddress(InetAddress.getByName(ip)), ip);
        }
        assertTrue(EgressGateway.isPublicAddress(InetAddress.getByName("1.1.1.1")));
        assertTrue(EgressGateway.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void explicitHostsReplaceRatherThanExtendDefaults() throws Exception {
        final var egress = services(Map.of("dependencyEgress.allowedHosts", "example.com,*.example.org"))
                .services()
                .dependencyEgress();
        assertTrue(egress.enabled());
        assertEquals(List.of("example.com", "*.example.org"), egress.allowedHosts());
        try (final var gateway = new EgressGateway(
                new InetSocketAddress("127.0.0.1", 0), egress.allowedHosts(), Duration.ofSeconds(2))) {
            assertTrue(gateway.allowsHost("example.com"));
            assertTrue(gateway.allowsHost("repo.example.org"));
            for (final var host : List.of("cdn.azul.com", "api.azul.com", "maven.apache.org", "github.com")) {
                assertFalse(gateway.allowsHost(host), host);
            }
            assertFalse(gateway.allowsHost("example.org"));
        }
    }

    @Test
    void disablingDependenciesRetainsDefaultHosts() {
        final var defaults = services(Map.of()).services().dependencyEgress();
        final var disabled =
                services(Map.of("dependencyEgress.enabled", "false")).services().dependencyEgress();
        assertFalse(disabled.enabled());
        assertEquals(defaults.allowedHosts(), disabled.allowedHosts());
    }

    @Test
    void explicitEmptyHostsDisableRatherThanBeingNormalizedToDefaults() {
        final var docker = services(Map.of("dependencyEgress.allowedHosts", ""));
        assertNotNull(docker.services());
        assertTrue(docker.services().dependencyEgress().allowedHosts().isEmpty());
        assertTrue(docker.services().dependencyEgress().enabled());
    }

    @Test
    void dependencyPolicyIsOperatorConfigurableAndCanBeDisabled() {
        final var egress = services(Map.of(
                        "dependencyEgress.enabled",
                        "false",
                        "dependencyEgress.image",
                        "public-proxy",
                        "dependencyEgress.allowedHosts",
                        "example.com,*.example.org"))
                .services()
                .dependencyEgress();
        assertFalse(egress.enabled());
        assertEquals("public-proxy", egress.image());
        assertEquals(List.of("example.com", "*.example.org"), egress.allowedHosts());
    }

    @Test
    void servicesStillRejectTopLevelEgress() {
        final var services = services(Map.of()).services();
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutorConfiguration.Docker(
                        "docker",
                        "agent",
                        "",
                        new ExecutorConfiguration.Docker.Egress("proxy", List.of("github.com"), "127.0.0.1", true),
                        services,
                        false));
    }

    private ExecutorConfiguration.Docker services(final Map<String, String> overrides) {
        final var dependency = overrides.isEmpty()
                ? null
                : new ExecutorConfiguration.Docker.Egress(
                        overrides.get("dependencyEgress.image"),
                        overrides.containsKey("dependencyEgress.allowedHosts")
                                ? (overrides
                                                .get("dependencyEgress.allowedHosts")
                                                .isEmpty()
                                        ? List.of()
                                        : List.of(overrides
                                                .get("dependencyEgress.allowedHosts")
                                                .split(",")))
                                : null,
                        null,
                        Boolean.parseBoolean(overrides.getOrDefault("dependencyEgress.enabled", "true")));
        return new ExecutorConfiguration.Docker(
                "docker",
                "agent",
                "",
                null,
                new ExecutorConfiguration.Docker.Services(
                        "trusted-services",
                        "trusted-control",
                        "/private/tls.p12",
                        null,
                        null,
                        "http://172.20.0.1:8080/v1",
                        dependency),
                false);
    }

    private ExecutorConfiguration configuration(final String visibility, final ExecutorConfiguration.Docker docker) {
        return new ExecutorConfiguration(
                ExecutorConfiguration.Backend.DOCKER, "", 3600, 30, visibility, docker, null, null, null);
    }
}
