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
package io.yupiik.ia.agent.github.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.json.JsonException;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GitHubClientTest {
    // Arbitrary transport fixtures, not a GitHub domain response schema.
    private static final Type RESPONSE = new Types.ParameterizedTypeImpl(Map.class, String.class, Object.class);

    @Test
    void requiresExplicitResponseTypesForEveryTransportOperation() {
        assertThrows(NoSuchMethodException.class, () -> GitHubClient.class.getMethod("get", String.class));
        assertThrows(NoSuchMethodException.class, () -> GitHubClient.class.getMethod("page", String.class));
        assertThrows(
                NoSuchMethodException.class,
                () -> GitHubClient.class.getMethod("post", String.class, GitHubClient.MutationRequest.class));
        assertThrows(
                NoSuchMethodException.class, () -> GitHubClient.class.getMethod("graphql", String.class, Map.class));
    }

    @Test
    void typedGetsDecodeRawConditionalBodiesForEachRequestedType() throws Exception {
        final var calls = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                final var bytes = (exchange.getRequestURI().getPath().equals("/list")
                                ? "[{\"sha\":\"abc\"}]"
                                : "{\"sha\":\"abc\",\"tree\":{\"sha\":\"def\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("ETag", "\"raw\"");
                exchange.getResponseHeaders().set("Link", "<" + base + "/next>; rel=\"next\"");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var client =
                        new GitHubClient(URI.create(base), json, () -> "secret", BotTestConfiguration.CLOCK)) {
            final var first = client.page("/commit", RestResponses.Commit.class);
            assertEquals(new RestResponses.Commit("abc", new RestResponses.ObjectId("def")), first.body());
            assertEquals(base + "/next", first.next());
            assertEquals(
                    "{\"sha\":\"abc\",\"tree\":{\"sha\":\"def\"}}".getBytes(StandardCharsets.UTF_8).length,
                    first.responseBytes());
            assertEquals(new RestResponses.ObjectId("abc"), client.get("/commit", RestResponses.ObjectId.class));
            assertEquals(first, client.page("/commit", RestResponses.Commit.class));
            assertEquals(
                    new RestResponses.Commit("abc", new RestResponses.ObjectId("def")),
                    client.get("/commit", RestResponses.Commit.class));
            final var type = new Types.ParameterizedTypeImpl(List.class, RestResponses.ObjectId.class);
            final List<RestResponses.ObjectId> list = client.get("/list", type);
            assertEquals(List.of(new RestResponses.ObjectId("abc")), list);
            assertEquals(
                    list,
                    client.<List<RestResponses.ObjectId>>page("/list", type).body());
            assertEquals(6, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void typedMutationUsesGeneratedCodecAndInvalidSuccessIsNotRetried() throws Exception {
        final var calls = new AtomicInteger();
        final var response = new AtomicReference<>("{\"sha\":\"ack\"}");
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            final var bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "secret",
                        BotTestConfiguration.CLOCK)) {
            assertEquals(
                    new RestResponses.ObjectId("ack"),
                    client.post("/", new GitHubClient.CommentRequest("body"), RestResponses.ObjectId.class));
            response.set("{\"sha\":{\"unexpected\":true}}");
            assertThrows(RuntimeException.class, () -> client.get("/", RestResponses.ObjectId.class));
            assertEquals(2, calls.get());
            response.set("{\"id\":1.5,\"full_name\":\"o/r\"}");
            assertEquals(
                    new BigDecimal("1.5"),
                    client.get("/", RestResponses.Repository.class).id());
            response.set("{\"id\":\"1.5\",\"size\":\"0\"}");
            final var quoted = client.get("/", RestResponses.Repository.class);
            assertEquals("1.5", quoted.id(), "Authority checks must still distinguish quoted numbers");
            assertEquals("0", quoted.size(), "A quoted size must never prove an empty repository");
            response.set("{\"archived\":\"false\"}");
            assertThrows(JsonException.class, () -> client.get("/", RestResponses.Repository.class));
            assertEquals(5, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void injectedClockControlsRelativeAbsoluteAndResetDeadlinesAndRateLimitExpiry() throws Exception {
        final var start = Instant.parse("2035-02-03T04:05:06Z");
        final var deadline = start.plusSeconds(3600);
        for (final var mode : List.of("relative", "http-date", "reset", "transient-http-date")) {
            final var now = new AtomicReference<>(start);
            final var clock = new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(final ZoneId zone) {
                    return Clock.fixed(instant(), zone);
                }

                @Override
                public Instant instant() {
                    return now.get();
                }
            };
            final var calls = new AtomicInteger();
            final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                if (calls.incrementAndGet() == 1) {
                    if (mode.equals("reset")) {
                        exchange.getResponseHeaders().add("X-RateLimit-Remaining", "0");
                        exchange.getResponseHeaders()
                                .add("X-RateLimit-Reset", Long.toString(deadline.getEpochSecond()));
                    } else {
                        exchange.getResponseHeaders()
                                .add(
                                        "Retry-After",
                                        mode.equals("relative")
                                                ? "3600"
                                                : DateTimeFormatter.RFC_1123_DATE_TIME.format(
                                                        deadline.atZone(ZoneOffset.UTC)));
                    }
                    exchange.sendResponseHeaders(mode.equals("transient-http-date") ? 503 : 429, -1);
                } else {
                    final var bytes = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
                exchange.close();
            });
            server.start();
            try (final var json = BotJsonMapper.create(key -> Optional.empty());
                    final var client = new GitHubClient(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            json,
                            () -> "secret",
                            clock)) {
                final var deferred =
                        assertThrows(GitHubClient.DeferredRequestException.class, () -> client.get("/", RESPONSE));
                assertEquals(deadline, deferred.retryAt(), mode);
                if (!mode.equals("transient-http-date")) {
                    final var gated =
                            assertThrows(GitHubClient.DeferredRequestException.class, () -> client.get("/", RESPONSE));
                    assertEquals(deadline, gated.retryAt(), mode);
                    assertEquals(1, calls.get(), mode);
                }
                now.set(deadline);
                assertEquals(Map.of(), client.get("/", RESPONSE), mode);
                assertEquals(2, calls.get(), mode);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void statisticsCountConditionalRequestsRetriesAndFailuresWithoutRequestData() throws Exception {
        final var requests = new AtomicInteger();
        final var retries = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            final var path = exchange.getRequestURI().getPath();
            if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                final int status = path.equals("/failure")
                        ? 404
                        : path.equals("/retry") && retries.incrementAndGet() < 3 ? 500 : 200;
                final var bytes = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("ETag", "etag");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "private-token",
                        BotTestConfiguration.CLOCK)) {
            assertEquals(new GitHubClient.Statistics(0, 0, 0), client.statistics());
            client.get(
                    "/cached?private-query", new Types.ParameterizedTypeImpl(List.class, RestResponses.ObjectId.class));
            client.get(
                    "/cached?private-query", new Types.ParameterizedTypeImpl(List.class, RestResponses.ObjectId.class));
            client.get("/retry", new Types.ParameterizedTypeImpl(List.class, RestResponses.ObjectId.class));
            assertThrows(
                    GitHubClient.HttpException.class,
                    () -> client.get(
                            "/failure", new Types.ParameterizedTypeImpl(List.class, RestResponses.ObjectId.class)));
            final var statistics = client.statistics();
            assertEquals(6, requests.get());
            assertEquals(requests.get(), statistics.requests());
            assertTrue(statistics.httpNanos() > 0);
            assertTrue(statistics.pacingNanos() >= 0);
            assertFalse(statistics.toString().contains("private"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.get(
                            "https://other.example/private",
                            new Types.ParameterizedTypeImpl(List.class, RestResponses.ObjectId.class)));
            assertEquals(statistics, client.statistics(), "Rejected origins must not increment transport counters");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void graphqlBindsVariablesRejectsPartialDataAndNeverReplays() throws Exception {
        final var requests = new AtomicInteger();
        final var received = new AtomicReference<String>();
        final var response = new AtomicReference<>("{\"data\":{\"node\":{\"id\":\"D_1\"}}}");
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/graphql", exchange -> {
            requests.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final var bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "secret",
                        BotTestConfiguration.CLOCK)) {
            final var query = "query($id:ID!){node(id:$id){id}}";
            assertEquals(
                    Map.of("data", Map.of("node", Map.of("id", "D_1"))),
                    client.graphql(query, Map.of("id", "D_1"), RESPONSE));
            final var wire = json.fromString(GitHubClient.GraphqlRequest.class, received.get());
            assertEquals(query, wire.query());
            assertEquals(Map.of("id", "D_1"), wire.variables());
            response.set("{\"data\":{\"node\":null},\"errors\":[{\"message\":\"private detail\"}]}");
            final var failure = assertThrows(
                    IllegalStateException.class, () -> client.graphql(query, Map.of("id", "D_1"), RESPONSE));
            assertFalse(failure.getMessage().contains("private detail"));
            final var variables = new DiscussionClient.Variables(null, null, null, "D_1", null);
            for (final var invalid : List.of(
                    "{\"data\":{\"node\":null},\"errors\":null}",
                    "{\"data\":{\"node\":null},\"errors\":[]}",
                    "{\"data\":null}",
                    "{}")) {
                response.set(invalid);
                assertThrows(IllegalStateException.class, () -> client.discussionGraphql(query, variables));
            }
            assertEquals(6, requests.get());

        } finally {
            server.stop(0);
        }
    }

    @Test
    void closingClientDoesNotCloseInjectedMapper() {
        final var closed = new AtomicInteger();
        try (final var json = new JsonMapperImpl(List.of(), key -> Optional.empty()) {
            @Override
            public void close() {
                closed.incrementAndGet();
                super.close();
            }
        }) {
            final var client = new GitHubClient(
                    URI.create("https://api.github.com"), json, () -> "unused", BotTestConfiguration.CLOCK);
            client.close();
            client.close();
            assertEquals(0, closed.get());
            assertEquals(Map.of("still", "usable"), json.fromString(Object.class, "{\"still\":\"usable\"}"));
        }
        assertEquals(1, closed.get());
    }

    @Test
    void rejectsOversizedFixedAndChunkedResponsesWithoutRetry() throws Exception {
        final var requests = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            final var block = new byte[64 * 1024];
            Arrays.fill(block, (byte) ' ');
            exchange.sendResponseHeaders(
                    200, exchange.getRequestURI().getPath().equals("/fixed") ? 129L * block.length : 0);
            try (exchange) {
                for (int i = 0; i < 129; i++) {
                    exchange.getResponseBody().write(block);
                }
            } catch (final IOException ignored) {
                // Cancellation can close the socket before the stub finishes sending.
            }
        });
        server.start();
        try (final var json = new JsonMapperImpl(List.of(), key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "secret",
                        BotTestConfiguration.CLOCK)) {
            for (final var path : List.of("/fixed", "/chunked")) {
                final var failure = assertThrows(IllegalStateException.class, () -> client.get(path, RESPONSE));
                assertTrue(failure.getMessage().contains("transport byte limit"));
            }
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cacheEvictsByAggregateBytes() throws Exception {
        final var conditional = new AtomicInteger();
        final var body = ("{\"value\":\"" + "x".repeat(4 * 1024 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                conditional.incrementAndGet();
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().set("ETag", "etag");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try (final var json = new JsonMapperImpl(List.of(), key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "secret",
                        BotTestConfiguration.CLOCK)) {
            client.get("/first", RESPONSE);
            client.get("/second", RESPONSE);
            client.get("/second", RESPONSE);
            assertEquals(1, conditional.get());
            client.get("/first", RESPONSE);
            assertEquals(1, conditional.get(), "First large page must have been evicted");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void genericResponsesPreserveWireShapesAndExactNumbers() {
        try (final var json = new JsonMapperImpl(List.of(), key -> Optional.empty())) {
            for (final var source : List.of(
                    "{}",
                    "[]",
                    "null",
                    "true",
                    "false",
                    "\"escaped\\nvalue\"",
                    "9223372036854775808",
                    "{\"items\":[null,true,false,1.5,{\"body\":\"text\"}],\"empty\":{}}")) {
                final var value = json.fromString(Object.class, source);
                assertEquals(json.fromString(Object.class, source), value);
            }
            final var value = json.fromString(Object.class, "{\"id\":9223372036854775808,\"items\":[null,true]}");
            final var object = (Map<?, ?>) value;
            assertEquals(new BigDecimal("9223372036854775808"), object.get("id"));
            assertEquals(Arrays.asList(null, true), object.get("items"));
            assertThrows(RuntimeException.class, () -> json.fromString(Object.class, "{\"a\":"));
        }
    }

    @Test
    void commentRequestUsesGitHubWireShape() throws Exception {
        final var received = new AtomicReference<String>();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/comments", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try (final var json = JsonMappers.create(key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "secret",
                        BotTestConfiguration.CLOCK)) {
            assertNull(client.post("/comments", new GitHubClient.CommentRequest("line\n\"quoted\""), RESPONSE));
            assertEquals("{\"body\":\"line\\n\\\"quoted\\\"\"}", received.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pacesReadsAndWritesAndValidatesInterval() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        final var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (final var json = JsonMappers.create(key -> Optional.empty());
                final var client = new GitHubClient(
                        base, json, () -> "secret", Duration.ofMillis(100), BotTestConfiguration.CLOCK)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GitHubClient(
                            base, json, () -> "secret", Duration.ofMillis(-1), BotTestConfiguration.CLOCK));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GitHubClient(
                            base, json, () -> "secret", Duration.ofSeconds(61), BotTestConfiguration.CLOCK));
            final long start = System.nanoTime();
            client.get("/read", RESPONSE);
            client.post("/write", new GitHubClient.CommentRequest("write"), RESPONSE);
            client.get("/read-again", RESPONSE);
            assertTrue(System.nanoTime() - start >= Duration.ofMillis(200).toNanos());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesPlaintextRemoteCredentialTransportBeforeReadingToken() {
        try (final var json = JsonMappers.create(key -> Optional.empty())) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GitHubClient(
                            URI.create("http://github.example/api"),
                            json,
                            () -> {
                                throw new AssertionError("must not read token");
                            },
                            BotTestConfiguration.CLOCK));
        }
    }

    @Test
    void exposesActualHttpStatusWithoutParsingResponseBody() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final var bytes = "not JSON: GitHub GET returned 404".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(
                    exchange.getRequestURI().getPath().equals("/missing") ? 404 : 403, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (final var json = JsonMappers.create(key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "secret",
                        BotTestConfiguration.CLOCK)) {
            assertEquals(
                    404,
                    assertThrows(GitHubClient.HttpException.class, () -> client.get("/missing", RESPONSE))
                            .status());
            assertEquals(
                    403,
                    assertThrows(GitHubClient.HttpException.class, () -> client.get("/forbidden", RESPONSE))
                            .status());
            assertEquals(
                    403,
                    assertThrows(
                                    GitHubClient.HttpException.class,
                                    () -> client.post(
                                            "/forbidden", new GitHubClient.CommentRequest("forbidden"), RESPONSE))
                            .status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cacheDoesNotAliasEscapedUrlsOrCredentials() throws Exception {
        final var credential = new AtomicReference<>("first");
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var seen = new CopyOnWriteArrayList<String>();
        server.createContext("/", exchange -> {
            seen.add(exchange.getRequestURI().toString() + ":"
                    + exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().add("ETag", "\"cached\"");
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes());
            exchange.close();
        });
        server.start();
        try (final var json = JsonMappers.create(key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> credential.get(),
                        BotTestConfiguration.CLOCK)) {
            client.get("/items?q=%2f", RESPONSE);
            client.get("/items?q=%2F", RESPONSE);
            client.get("/items?q=%2f", RESPONSE);
            credential.set("rotated");
            client.get("/items?q=%2f", RESPONSE);
            assertEquals(
                    List.of("/items?q=%2f:null", "/items?q=%2F:null", "/items?q=%2f:\"cached\"", "/items?q=%2f:null"),
                    seen);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void etagIsPerExactUrlAndRetainsPagination() throws Exception {
        final var calls = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/items", exchange -> {
            calls.incrementAndGet();
            final var page = exchange.getRequestURI().getQuery();
            if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                assertEquals("\"" + page + "\"", exchange.getRequestHeaders().getFirst("If-None-Match"));
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().add("ETag", "\"" + page + "\"");
                exchange.getResponseHeaders().add("Link", "<" + base + "/items?page=2>; rel=\"next\"");
                final var bytes = ("{\"page\":\"" + page + "\"}").getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try (final var json = JsonMappers.create(key -> Optional.empty());
                final var client = new GitHubClient(base, json, () -> "secret", BotTestConfiguration.CLOCK)) {
            final var first = client.page("/items?page=1", RESPONSE);
            assertEquals(first, client.page("/items?page=1", RESPONSE));
            assertEquals(base + "/items?page=2", first.next());
            assertEquals(Map.of("page", "page=2"), client.get(first.next(), RESPONSE));
            assertEquals(3, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundedGetRetriesButNeverPostOrRedirects() throws Exception {
        final var gets = new AtomicInteger();
        final var posts = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/retry", exchange -> {
            final boolean get = exchange.getRequestMethod().equals("GET");
            final int count = (get ? gets : posts).incrementAndGet();
            final int status = get && count == 3 ? 200 : 503;
            exchange.sendResponseHeaders(status, 2);
            exchange.getResponseBody().write("{}".getBytes());
            exchange.close();
        });
        server.createContext("/limit", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "3600");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.createContext("/reset", exchange -> {
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", "0");
            exchange.getResponseHeaders()
                    .add(
                            "X-RateLimit-Reset",
                            Long.toString(BotTestConfiguration.CLOCK
                                    .instant()
                                    .plusSeconds(1800)
                                    .getEpochSecond()));
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/retry");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/link", exchange -> {
            exchange.getResponseHeaders().add("Link", "<https://example.com/leak>; rel=\"next\"");
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("[]".getBytes());
            exchange.close();
        });
        server.start();
        try (final var json = JsonMappers.create(key -> Optional.empty());
                final var client = new GitHubClient(base, json, () -> "secret", BotTestConfiguration.CLOCK)) {
            assertEquals(Map.of(), client.get("/retry", RESPONSE));
            assertEquals(3, gets.get());
            assertThrows(IllegalStateException.class, () -> client.get("/redirect", RESPONSE));
            assertEquals(3, gets.get());
            assertThrows(
                    IllegalStateException.class,
                    () -> client.post("/retry", new GitHubClient.CommentRequest("retry"), RESPONSE));
            assertEquals(1, posts.get());
            final var limit =
                    assertThrows(GitHubClient.DeferredRequestException.class, () -> client.get("/limit", RESPONSE));
            assertEquals(429, limit.status());
            assertEquals(BotTestConfiguration.CLOCK.instant().plusSeconds(3600), limit.retryAt());
            assertThrows(GitHubClient.DeferredRequestException.class, () -> client.get("/reset", RESPONSE));
            assertThrows(IllegalArgumentException.class, () -> client.get("https://example.com/leak", RESPONSE));
            assertThrows(IllegalArgumentException.class, () -> client.get("//example.com/leak", RESPONSE));
            assertThrows(GitHubClient.DeferredRequestException.class, () -> client.get("/link", RESPONSE));
            assertThrows(GitHubClient.DeferredRequestException.class, () -> client.get("/retry", RESPONSE));
            assertEquals(3, gets.get());
        } finally {
            server.stop(0);
        }
    }
}
