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
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MentionSearchClientTest {
    @Test
    void bindsUntrustedValuesAndReturnsIssueAndPullCandidatesWithUncappedCount() throws Exception {
        try (final var stub = new Stub()) {
            final var query = "mentions:someone \"}) { mutation { secret } }\n";
            final var after = "cursor\"}) { private }\n";
            stub.search(connection(List.of(candidate("Issue"), candidate("PullRequest")), 2401, true, "next"));
            final var page = stub.client.search(query, MentionSearchClient.Kind.ISSUE, after);
            assertEquals(2401, page.count());
            assertEquals("next", page.next());
            assertEquals(
                    List.of("Issue", "PullRequest"),
                    page.nodes().stream()
                            .map(MentionSearchClient.Candidate::type)
                            .toList());
            assertEquals(
                    new MentionSearchClient.Candidate("owner/repo", 42, "Issue", Instant.parse("2026-11-13T12:00:00Z")),
                    page.nodes().getFirst());
            final var wire = stub.wire();
            assertEquals(Map.of("query", query, "after", after), wire.variables());
            assertEquals(
                    "query MentionCandidates($query:String!,$after:String) {"
                            + " search(query:$query,type:ISSUE,first:100,after:$after) { issueCount nodes { __typename"
                            + " ... on Issue { number createdAt repository { nameWithOwner } }"
                            + " ... on PullRequest { number createdAt repository { nameWithOwner } }"
                            + " } pageInfo { hasNextPage endCursor } } }",
                    wire.query());
            assertFalse(wire.query().contains(query));
            assertFalse(wire.query().contains(after));
            assertFalse(wire.query().contains("body"));
            assertThrows(UnsupportedOperationException.class, page.nodes()::clear);
            assertEquals(1, stub.requests.get());
        }
    }

    @Test
    void usesDiscussionOnlyFragmentAndBindsNullCursor() throws Exception {
        try (final var stub = new Stub()) {
            stub.search(connection(List.of(candidate("Discussion")), 1500, false, "terminal"));
            final var page = stub.client.search("mentions:someone", MentionSearchClient.Kind.DISCUSSION, null);
            assertEquals(1500, page.count());
            assertNull(page.next());
            assertEquals("Discussion", page.nodes().getFirst().type());
            final var wire = stub.wire();
            final var variables = (Map<?, ?>) wire.variables();
            assertEquals(2, variables.size());
            assertEquals("mentions:someone", variables.get("query"));
            assertTrue(variables.containsKey("after"));
            assertNull(variables.get("after"));
            assertEquals(
                    "query MentionCandidates($query:String!,$after:String) {"
                            + " search(query:$query,type:DISCUSSION,first:100,after:$after) { discussionCount nodes { __typename"
                            + " ... on Discussion { number createdAt repository { nameWithOwner } }"
                            + " } pageInfo { hasNextPage endCursor } } }",
                    wire.query());
        }
    }

    @Test
    void acceptsBoundarySizesAndNonSnapshotTerminalCountsAndDefensivelyCopiesPages() throws Exception {
        try (final var stub = new Stub()) {
            stub.search(connection(
                    Collections.nCopies(100, candidate("Issue")), Integer.MAX_VALUE, true, "n".repeat(4096)));
            final var page = stub.client.search("q".repeat(2048), MentionSearchClient.Kind.ISSUE, "a".repeat(4096));
            assertEquals(100, page.nodes().size());
            assertEquals(Integer.MAX_VALUE, page.count());
            assertEquals(4096, page.next().length());
            for (final var count : List.of(0, 1250)) {
                stub.search(connection(List.of(), count, false, null));
                assertEquals(
                        new MentionSearchClient.Page(List.of(), null, count),
                        stub.client.search("q", MentionSearchClient.Kind.ISSUE, null));
            }
            // Counts can change between requests; do not treat them as an exact snapshot.
            stub.search(connection(List.of(candidate("Issue")), 0, false, null));
            assertEquals(
                    1,
                    stub.client
                            .search("q", MentionSearchClient.Kind.ISSUE, "previous")
                            .nodes()
                            .size());
            final var mutable = new ArrayList<>(page.nodes());
            final var copy = new MentionSearchClient.Page(mutable, null, 100);
            mutable.clear();
            assertEquals(100, copy.nodes().size());
            assertThrows(UnsupportedOperationException.class, copy.nodes()::clear);
        }
    }

    @Test
    void acceptsExactNumericIntegersWithoutTruncatingFractions() throws Exception {
        try (final var stub = new Stub()) {
            for (final var kind : MentionSearchClient.Kind.values()) {
                final var type = kind == MentionSearchClient.Kind.ISSUE ? "Issue" : "Discussion";
                final var node = candidate(type);
                node.put("number", new BigDecimal("42.000"));
                stub.search(connection(List.of(node), new BigDecimal("2401.000"), false, null));
                final var page = stub.client.search("q", kind, null);
                assertEquals(2401, page.count());
                assertEquals(42, page.nodes().getFirst().number());
                for (final var value : List.of(new BigDecimal("42.0000000001"))) {
                    node.put("number", value);
                    stub.search(connection(List.of(node), 2401, false, null));
                    rejected(stub, kind, null);
                }
                for (final var value : List.of(new BigDecimal("2401.0000000001"))) {
                    stub.search(connection(List.of(candidate(type)), value, false, null));
                    rejected(stub, kind, null);
                }
            }
        }
    }

    @Test
    void rejectsMalformedCountsAndInitialCountSmallerThanNodes() throws Exception {
        try (final var stub = new Stub()) {
            for (final var kind : MentionSearchClient.Kind.values()) {
                final var type = kind == MentionSearchClient.Kind.ISSUE ? "Issue" : "Discussion";
                for (final var count : Arrays.asList(null, -1, 0, true, Map.of(), new BigDecimal("1.5"), 2147483648L)) {
                    stub.search(connection(List.of(candidate(type)), count, false, null));
                    rejected(stub, kind, null);
                }
                final var missing = connection(List.of(candidate(type)), 1, false, null);
                missing.remove(kind == MentionSearchClient.Kind.ISSUE ? "issueCount" : "discussionCount");
                stub.search(missing);
                rejected(stub, kind, null);
            }
        }
    }

    @Test
    void rejectsMalformedConnectionsAndPaginationWithoutLeakingContent() throws Exception {
        try (final var stub = new Stub()) {
            final var node = candidate("Issue");
            final var invalid = new ArrayList<Object>(Arrays.asList(
                    null,
                    "private malformed connection",
                    Map.of(),
                    connection(null, 1, false, null),
                    connection("private nodes", 1, false, null),
                    connection(Arrays.asList((Object) null), 1, false, null),
                    connection(List.of("private node"), 1, false, null),
                    connection(Collections.nCopies(101, node), 101, false, null),
                    connection(List.of(node), 1, null, null),
                    connection(List.of(node), 1, "true", "next"),
                    connection(List.of(node), 1, true, null),
                    connection(List.of(node), 1, true, " "),
                    connection(List.of(node), 1, true, "x".repeat(4097)),
                    connection(List.of(node), 1, true, "prior"),
                    connection(List.of(), 1, true, "next")));
            for (final var info : Arrays.asList(null, "private info", Map.of())) {
                final var value = connection(List.of(node), 1, false, null);
                value.put("pageInfo", info);
                invalid.add(value);
            }
            for (final var value : invalid) {
                stub.search(value);
                rejected(stub, MentionSearchClient.Kind.ISSUE, "prior");
            }
            stub.response.set("{\"data\":{}}");
            rejected(stub, MentionSearchClient.Kind.ISSUE, null);
        }
    }

    @Test
    void validatesEveryCandidateFieldAndRejectsWrongKinds() throws Exception {
        try (final var stub = new Stub()) {
            for (final var kind : MentionSearchClient.Kind.values()) {
                final var type = kind == MentionSearchClient.Kind.ISSUE ? "Issue" : "Discussion";
                for (final var field : List.of("__typename", "number", "createdAt", "repository")) {
                    final List<?> invalid =
                            switch (field) {
                                case "__typename" ->
                                    Arrays.asList(
                                            null,
                                            42,
                                            "private unknown",
                                            kind == MentionSearchClient.Kind.ISSUE ? "Discussion" : "Issue",
                                            kind == MentionSearchClient.Kind.ISSUE ? "Discussion" : "PullRequest");
                                case "number" -> Arrays.asList(null, 0, -1, new BigDecimal("1.5"), 2147483648L, true);
                                case "createdAt" -> Arrays.asList(null, 42, "private date", "2026-11-13");
                                default ->
                                    Arrays.asList(
                                            null,
                                            "private repository",
                                            Map.of(),
                                            Map.of("nameWithOwner", 42),
                                            Map.of("nameWithOwner", "owner"),
                                            Map.of("nameWithOwner", "owner/repo/extra"),
                                            Map.of("nameWithOwner", "owner/repo\n"),
                                            Map.of("nameWithOwner", "/repo"));
                            };
                    for (final var value : invalid) {
                        final var node = candidate(type);
                        node.put(field, value);
                        // A valid prefix must not hide a malformed later node.
                        stub.search(connection(List.of(candidate(type), node), 2, false, null));
                        rejected(stub, kind, null);
                    }
                    final var missing = candidate(type);
                    missing.remove(field);
                    stub.search(connection(List.of(missing), 1, false, null));
                    rejected(stub, kind, null);
                }
            }
        }
    }

    @Test
    void partialErrorsAreNeverReturnedOrRetriedEvenWithValidData() throws Exception {
        try (final var stub = new Stub()) {
            for (final var errors : Arrays.asList(null, List.of(), List.of(Map.of("message", "private diagnostic")))) {
                final var envelope = new LinkedHashMap<String, Object>();
                envelope.put("data", Map.of("search", connection(List.of(candidate("Issue")), 1, false, null)));
                envelope.put("errors", errors);
                stub.response.set(stub.json.toString(envelope));
                rejected(stub, MentionSearchClient.Kind.ISSUE, null);
            }
        }
    }

    @Test
    void invalidInputNeverReachesTheNetwork() throws Exception {
        try (final var stub = new Stub()) {
            for (final var query : Arrays.asList(null, "", " \n", "q".repeat(2049))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> stub.client.search(query, MentionSearchClient.Kind.ISSUE, null));
            }
            for (final var cursor : List.of("", " \n", "a".repeat(4097))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> stub.client.search("q", MentionSearchClient.Kind.ISSUE, cursor));
            }
            assertThrows(IllegalArgumentException.class, () -> stub.client.search("q", null, null));
            assertEquals(0, stub.requests.get());
        }
    }

    private static void rejected(final Stub stub, final MentionSearchClient.Kind kind, final String after) {
        final var before = stub.requests.get();
        final var failure =
                assertThrows(IllegalStateException.class, () -> stub.client.search("private query", kind, after));
        assertFalse(failure.toString().contains("private"));
        assertNull(failure.getCause());
        assertEquals(before + 1, stub.requests.get());
    }

    private static Map<String, Object> candidate(final String type) {
        return new LinkedHashMap<>(Map.of(
                "__typename",
                type,
                "number",
                42,
                "createdAt",
                "2026-11-13T12:00:00Z",
                "repository",
                Map.of("nameWithOwner", "owner/repo")));
    }

    private static Map<String, Object> connection(
            final Object nodes, final Object count, final Object more, final Object cursor) {
        final var info = new LinkedHashMap<String, Object>();
        info.put("hasNextPage", more);
        info.put("endCursor", cursor);
        final var result = new LinkedHashMap<String, Object>();
        result.put("nodes", nodes);
        result.put("issueCount", count);
        result.put("discussionCount", count);
        result.put("pageInfo", info);
        return result;
    }

    private static final class Stub implements AutoCloseable {
        private final JsonMapper json = BotJsonMapper.create(key -> Optional.empty());
        private final AtomicReference<String> response = new AtomicReference<>("{}");
        private final AtomicReference<String> received = new AtomicReference<>();
        private final AtomicInteger requests = new AtomicInteger();
        private final HttpServer server;
        private final GitHubClient github;
        private final MentionSearchClient client;

        private Stub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/graphql", exchange -> {
                try (exchange) {
                    requests.incrementAndGet();
                    received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    final var bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            github = new GitHubClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    json,
                    () -> "secret",
                    BotTestConfiguration.CLOCK);
            client = new MentionSearchClient(github);
        }

        private void search(final Object connection) {
            final var data = new LinkedHashMap<String, Object>();
            data.put("search", connection);
            response.set(json.toString(Map.of("data", data)));
        }

        private GitHubClient.GraphqlRequest wire() {
            return json.fromString(GitHubClient.GraphqlRequest.class, received.get());
        }

        @Override
        public void close() {
            try {
                github.close();
            } finally {
                server.stop(0);
                json.close();
            }
        }
    }
}
