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
package io.yupiik.ia.agent.github.context.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.ia.agent.github.configuration.AgentContextConfiguration;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ContextClientTest {
    private static final String ROUTE = "/context/12345678-1234-1234-1234-123456789abc/rpc";

    @Test
    void configuredCredentialsAreLookedUpLazilyAndRevalidatedAfterRotation() {
        final var settings = new AgentContextConfiguration(
                true, "https://context.invalid" + ROUTE, "YCA_CONFIG_CONTEXT", "YCA_CONFIG_GIT", "o/r");
        final var values = new HashMap<String, String>();
        final var reads = new AtomicInteger();
        final Configuration config = name -> {
            reads.incrementAndGet();
            return Configuration.of(values).get(name);
        };
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var client = new ContextClient(settings, json, config);
            assertEquals(0, reads.get());
            assertFalse(ContextClient.usable(settings, config));
            values.put("YCA_CONFIG_CONTEXT", "configured-context-secret");
            assertTrue(ContextClient.usable(settings, config));
            for (final var invalid : List.of("", "   ", "secret\rvalue", "secret\nvalue")) {
                values.put("YCA_CONFIG_CONTEXT", invalid);
                assertFalse(ContextClient.usable(settings, config));
                final var before = reads.get();
                final var failure = assertThrows(IOException.class, () -> client.call(Map.of()));
                assertEquals(before + 1, reads.get());
                assertEquals("Context request unavailable.", failure.getMessage());
                assertNull(failure.getCause());
            }
            values.put("YCA_CONFIG_CONTEXT", "rotated-context-secret");
            assertTrue(ContextClient.usable(settings, config));
        }
    }

    @Test
    void configurationFailuresAreUnavailableWithoutLeakingSourceDiagnostics() {
        final var settings = new AgentContextConfiguration(
                true, "https://context.invalid" + ROUTE, "YCA_CONFIG_CONTEXT", "YCA_CONFIG_GIT", "o/r");
        final Configuration config = name -> {
            throw new IllegalStateException("configured-secret-in-source-diagnostic");
        };
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var client = new ContextClient(settings, json, config);
            assertFalse(ContextClient.usable(settings, config));
            final var failure = assertThrows(IOException.class, () -> client.call(Map.of()));
            assertEquals("Context request unavailable.", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test
    void forwardsExactJsonAndUnwrapsStructuredBodyWithoutDiscovery() throws Exception {
        final var count = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            server.createContext(ROUTE, exchange -> {
                count.incrementAndGet();
                assertEquals("Bearer test-only", exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
                assertEquals(
                        Map.of("operation", "git", "argv", List.of("log", "-1")),
                        json.fromString(
                                Object.class,
                                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                final var bytes = "{\"ok\":true,\"body\":{\"status\":\"pending\",\"operationId\":\"abc\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var client = new ContextClient(endpoint(server), json, () -> "test-only");
            assertEquals(0, count.get());
            assertEquals(
                    Map.of("status", "pending", "operationId", "abc"),
                    client.call(Map.of("operation", "git", "argv", List.of("log", "-1"))));
            assertEquals(1, count.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnsafeProductionEndpointsAndOnlyExplicitlyAllowsLoopbackHttp() {
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            for (final var endpoint : List.of(
                    "http://127.0.0.1" + ROUTE,
                    "https://user@host" + ROUTE,
                    "https://host" + ROUTE + "?q=1",
                    "https://host" + ROUTE + "#x",
                    "https://host/rpc",
                    "https://host/context/not-a-uuid/rpc",
                    "https://host/context/12345678-1234-1234-1234-123456789abc/%72pc")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ContextClient(
                                new AgentContextConfiguration(
                                        true, endpoint, "YCA_CONTEXT_TOKEN", "YCA_CONTEXT_GIT_TOKEN", "o/r"),
                                json,
                                ignored -> Optional.empty()));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ContextClient(URI.create("http://example.org" + ROUTE), json, () -> "x"));
            assertDoesNotThrow(() -> new ContextClient(URI.create("http://127.0.0.1" + ROUTE), json, () -> "x"));
        }
    }

    @Test
    void rejectsOversizedMalformedFailureAndRedirectBodiesWithoutLeakingPayload() throws Exception {
        for (final var payload : List.of(
                "{\"ok\":false,\"error\":\"SECRET_REMOTE_PAYLOAD\"}",
                "null",
                "[]",
                "true",
                "{}",
                "{\"body\":{}}",
                "{\"ok\":null,\"body\":{}}",
                "{\"ok\":\"true\",\"body\":{}}",
                "{\"ok\":1,\"body\":{}}",
                "{\"ok\":{},\"body\":{}}",
                "{\"ok\":[],\"body\":{}}",
                "{\"ok\":true}",
                "{\"ok\":true,\"body\":null}",
                "{\"ok\":true,\"body\":\"SECRET_REMOTE_PAYLOAD\"}",
                "{\"ok\":true,\"body\":false}",
                "{\"ok\":true,\"body\":42}",
                "SECRET_REMOTE_PAYLOAD",
                "{\"ok\":true,\"body\":{\"data\":\"" + "x".repeat(512 * 1024) + "\"}}")) {
            final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(ROUTE, exchange -> {
                final var body = payload.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try {
                    exchange.getResponseBody().write(body);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
                final var error = assertThrows(
                        IOException.class,
                        () -> new ContextClient(endpoint(server), json, () -> "x")
                                .call(Map.of("operation", "summary")));
                assertEquals("Context request unavailable.", error.getMessage());
                assertNull(error.getCause());
            } finally {
                server.stop(0);
            }
        }
        final var followed = new AtomicBoolean();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ROUTE, exchange -> {
            exchange.getResponseHeaders().set("Location", "/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/secret", exchange -> {
            followed.set(true);
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            assertThrows(IOException.class, () -> new ContextClient(endpoint(server), json, () -> "x").call(Map.of()));
            assertFalse(followed.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dynamicBodiesAndUnknownEnvelopeFieldsRemainIntact() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var payloads = List.of(
                "{\"ok\":true,\"body\":{}}",
                "{\"ok\":true,\"body\":[]}",
                "{\"ignored\":{\"nested\":[null,true,12]},\"ok\":true,\"body\":{\"data\":[null,{\"value\":false}]}}",
                "{\"body\":[null,true,12,\"value\",[1],{\"nested\":null}],\"ok\":true}");
        final var next = new AtomicInteger();
        server.createContext(ROUTE, exchange -> {
            final var bytes = payloads.get(next.getAndIncrement()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var client = new ContextClient(endpoint(server), json, () -> "test-only");
            for (final var payload : payloads) {
                final var expected = ((Map<?, ?>) json.fromString(Object.class, payload)).get("body");
                assertEquals(
                        expected instanceof List<?> ? Map.of("data", expected) : expected,
                        client.call(Map.of("operation", "summary")));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancellationInterruptsAnInflightBodyNotOnlyHeaderWait() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var cancelled = new AtomicBoolean();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ROUTE, exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty());
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var future = executor.submit(() -> assertThrows(
                    IOException.class,
                    () -> new ContextClient(endpoint(server), json, () -> "x").call(Map.of(), cancelled::get)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            cancelled.set(true);
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> future.get(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void listBodiesRemainStructuredAndMissingCredentialsNeverConnect() throws Exception {
        final var calls = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ROUTE, exchange -> {
            calls.incrementAndGet();
            final var body = "{\"ok\":true,\"body\":[{\"repository\":\"o/r\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            assertThrows(IOException.class, () -> new ContextClient(endpoint(server), json, () -> "").call(Map.of()));
            assertEquals(0, calls.get());
            assertEquals(
                    Map.of("data", List.of(Map.of("repository", "o/r"))),
                    new ContextClient(endpoint(server), json, () -> "x").call(Map.of("operation", "repositories")));
        } finally {
            server.stop(0);
        }
    }

    private static URI endpoint(final HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + ROUTE);
    }
}
