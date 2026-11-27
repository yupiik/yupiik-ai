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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ReviewClientTest {
    @Test
    void batchesRepositoriesAndBindsAllValuesWithoutInterpolatingThem() throws Exception {
        try (final var stub = new Stub()) {
            final var cursor = "untrusted\"}) { mutation { secret } }\n";
            stub.data(Map.of(
                    "r0", repository("OWNER/first", "pullRequests", connection(List.of(pull(7)), true, "pull-next")),
                    "r1", repository("owner/second", "pullRequests", connection(List.of(), false, null))));
            final var result = stub.client.pulls(List.of(
                    new ReviewClient.Discovery("owner/first", cursor),
                    new ReviewClient.Discovery("owner/second", null)));
            assertEquals(1, stub.requests.get());
            assertEquals(7, result.get("owner/first").nodes().getFirst().number());
            assertEquals("pull-next", result.get("owner/first").next());
            assertEquals(new ReviewClient.Page<>(List.of(), null), result.get("owner/second"));
            final var wire = stub.wire();
            final var variables = (Map<?, ?>) wire.variables();
            assertEquals("owner", variables.get("owner0"));
            assertEquals("first", variables.get("name0"));
            assertEquals(cursor, variables.get("after0"));
            assertEquals("second", variables.get("name1"));
            assertNull(variables.get("after1"));
            assertEquals(6, variables.size());
            assertTrue(wire.query().contains("r0:repository(owner:$owner0,name:$name0)"));
            assertTrue(wire.query().contains("r1:repository(owner:$owner1,name:$name1)"));
            assertTrue(wire.query().contains("states:[OPEN],first:10,after:$after0"));
            assertTrue(wire.query().contains("orderBy:{field:UPDATED_AT,direction:DESC}"));
            assertTrue(wire.query().contains("reviews(first:100)"));
            assertTrue(wire.query().contains("databaseId:fullDatabaseId"));
            assertTrue(wire.query().contains("... on Bot { databaseId }"));
            assertFalse(wire.query().contains(cursor));
            assertFalse(wire.query().contains("first\""));
            assertFalse(wire.query().contains("owner/first"));
            assertThrows(UnsupportedOperationException.class, result::clear);
            assertThrows(
                    UnsupportedOperationException.class,
                    result.get("owner/first").nodes()::clear);
            assertThrows(
                    UnsupportedOperationException.class,
                    result.get("owner/first").nodes().getFirst().reviews().nodes()::clear);
        }
    }

    @Test
    void acceptsMaximumBatchAndPageSizesInOneRequest() throws Exception {
        try (final var stub = new Stub()) {
            final var data = new LinkedHashMap<String, Object>();
            final var requests = new ArrayList<ReviewClient.Discovery>();
            final var reviews = IntStream.range(0, 100).mapToObj(i -> review()).toList();
            final var pulls = IntStream.range(1, 11)
                    .mapToObj(i -> {
                        final var pull = pull(i);
                        pull.put("reviews", connection(reviews, false, null));
                        return pull;
                    })
                    .toList();
            for (int i = 0; i < 10; i++) {
                requests.add(new ReviewClient.Discovery("owner/repo" + i, null));
                data.put("r" + i, repository("owner/repo" + i, "pullRequests", connection(pulls, false, null)));
            }
            stub.data(data);
            final var result = stub.client.pulls(requests);
            assertEquals(10, result.size());
            assertEquals(10, result.get("owner/repo9").nodes().size());
            assertEquals(
                    100,
                    result.get("owner/repo9")
                            .nodes()
                            .getFirst()
                            .reviews()
                            .nodes()
                            .size());
            assertEquals(1, stub.requests.get());
        }
    }

    @Test
    void continuationBindsNumberAndCursorAndReturnsCurrentPullState() throws Exception {
        try (final var stub = new Stub()) {
            final var pull = pull(42);
            pull.put("reviews", connection(List.of(review()), true, "next"));
            stub.followup(pull);
            final var result = stub.client.reviews("owner/repo", 42, "previous\"\n");
            assertEquals(42, result.number());
            assertEquals("OPEN", result.state());
            assertEquals("next", result.reviews().next());
            final var wire = stub.wire();
            assertEquals(
                    Map.of("owner", "owner", "name", "repo", "number", new BigDecimal(42), "after", "previous\"\n"),
                    wire.variables());
            assertTrue(wire.query()
                    .contains("repository(owner:$owner,name:$name) { nameWithOwner pullRequest(number:$number)"));
            assertTrue(wire.query().contains("number state reviews(first:100,after:$after)"));
            assertFalse(wire.query().contains("42"));
            assertFalse(wire.query().contains("previous"));
            for (final var state : List.of("CLOSED", "MERGED", "FUTURE_STATE")) {
                pull.put("state", state);
                // Structurally valid JSON, but incomplete reviews must never be interpreted for a closed pull.
                pull.put("reviews", Map.of("nodes", List.of(Map.of("body", "private malformed review body"))));
                stub.followup(pull);
                assertEquals(
                        new ReviewClient.Pull(42, state, new ReviewClient.Page<>(List.of(), null)),
                        stub.client.reviews("owner/repo", 42, null));
                stub.discovery(pull);
                assertTrue(stub.client
                        .pulls(List.of(new ReviewClient.Discovery("owner/repo", null)))
                        .get("owner/repo")
                        .nodes()
                        .getFirst()
                        .reviews()
                        .nodes()
                        .isEmpty());
            }
        }
    }

    @Test
    void parsesUserBotDeletedAndNonUserAuthorsAndPendingTimestamps() throws Exception {
        try (final var stub = new Stub()) {
            final var user = review();
            user.put("databaseId", "9876543210");
            final var bot = review();
            bot.put("author", Map.of("__typename", "Bot", "login", "robot", "databaseId", 456));
            final var botWithoutId = review();
            botWithoutId.put("author", Map.of("__typename", "Bot", "login", "robot"));
            final var deleted = review();
            deleted.put("author", null);
            deleted.put("submittedAt", null);
            final var other = review();
            other.put("author", Map.of("__typename", "Organization", "login", "org", "databaseId", 123));
            final var pull = pull(42);
            pull.put("reviews", connection(List.of(user, bot, botWithoutId, deleted, other), false, null));
            stub.followup(pull);
            final var nodes =
                    stub.client.reviews("owner/repo", 42, null).reviews().nodes();
            assertEquals(
                    new ReviewClient.Actor("123", "human", "User"), nodes.get(0).author());
            assertEquals(
                    new ReviewClient.Actor("456", "robot", "Bot"), nodes.get(1).author());
            assertEquals(
                    new ReviewClient.Actor(null, "robot", "Bot"), nodes.get(2).author());
            assertEquals(
                    new ReviewClient.Actor(null, "", "Deleted"), nodes.get(3).author());
            assertEquals(
                    new ReviewClient.Actor(null, "org", "Organization"),
                    nodes.get(4).author());
            assertEquals("9876543210", nodes.getFirst().id());
            assertEquals("private body", nodes.getFirst().body());
            assertEquals("COMMENTED", nodes.getFirst().state());
            assertEquals(
                    "https://github.com/owner/repo/pull/42#pullrequestreview-9876543210",
                    nodes.getFirst().url());
            assertEquals(Instant.parse("2026-11-13T12:00:00Z"), nodes.getFirst().submittedAt());
            assertNull(nodes.get(3).submittedAt());
        }
    }

    @Test
    void preservesBigIntReviewIdsAndRejectsFractionalActorIdsAtCursorBoundary() throws Exception {
        try (final var stub = new Stub()) {
            final var previous = "a".repeat(4096);
            final var next = "b".repeat(4096);
            final var id = "123456789012345678901234567890";
            for (final var value : List.of(id, new BigDecimal(id))) {
                final var review = review();
                review.put("databaseId", value);
                final var pull = pull(42);
                pull.put("reviews", connection(List.of(review), true, next));
                stub.followup(pull);
                final var page = stub.client.reviews("owner/repo", 42, previous).reviews();
                assertEquals(id, page.nodes().getFirst().id());
                assertEquals(next, page.next());
                assertEquals(previous, ((Map<?, ?>) stub.wire().variables()).get("after"));
            }
            for (final var type : List.of("User", "Bot")) {
                for (final var idValue : List.of(new BigDecimal("123.0"), new BigDecimal("123.5"))) {
                    final var review = review();
                    review.put("author", Map.of("__typename", type, "login", "actor", "databaseId", idValue));
                    final var pull = pull(42);
                    pull.put("reviews", connection(List.of(review), false, null));
                    stub.followup(pull);
                    final var failure = assertThrows(
                            IllegalStateException.class, () -> stub.client.reviews("owner/repo", 42, null));
                    assertEquals("Invalid GitHub GraphQL response", failure.getMessage());
                    assertNull(failure.getCause());
                }
            }
        }
    }

    @Test
    void rejectsInvalidInitialAndNestedConnectionCursorsAndShapes() throws Exception {
        try (final var stub = new Stub()) {
            for (final var nested : List.of(false, true)) {
                final var node = nested ? review() : pull(42);
                final var invalid = new ArrayList<Object>(List.of(
                        "not a connection",
                        Map.of("nodes", List.of(node)),
                        Map.of("pageInfo", Map.of("hasNextPage", false)),
                        Map.of("nodes", "not nodes", "pageInfo", Map.of("hasNextPage", false)),
                        connection(Arrays.asList((Object) null), false, null),
                        connection(List.of("not a node"), false, null),
                        connection(List.of(node), "true", "next"),
                        connection(List.of(node), true, null),
                        connection(List.of(node), true, " "),
                        connection(List.of(node), true, "x".repeat(4097)),
                        connection(List.of(node), true, "prior"),
                        connection(List.of(), true, "next"),
                        connection(Collections.nCopies(nested ? 101 : 11, node), false, null)));
                invalid.add(null);
                invalid.add(Map.of("nodes", List.of(node), "pageInfo", "invalid"));
                invalid.add(Map.of("nodes", List.of(node), "pageInfo", Map.of()));
                for (final var connection : invalid) {
                    final var pull = pull(42);
                    if (nested) {
                        pull.put("reviews", connection);
                        stub.followup(pull);
                        assertThrows(IllegalStateException.class, () -> stub.client.reviews("owner/repo", 42, "prior"));
                        // Initial nested pages have no previous cursor, but still enforce cursor shape.
                        if (connection != null
                                && connection instanceof Map<?, ?> map
                                && map.get("pageInfo") instanceof Map<?, ?> info
                                && "prior".equals(info.get("endCursor"))) {
                            continue;
                        }
                        stub.discovery(pull);
                    } else {
                        stub.data(Map.of("r0", repository("owner/repo", "pullRequests", connection)));
                    }
                    assertThrows(
                            IllegalStateException.class,
                            () -> stub.client.pulls(List.of(new ReviewClient.Discovery("owner/repo", "prior"))));
                }
            }
        }
    }

    @Test
    void rejectsMismatchedIdentitiesMissingAliasesAndNullParents() throws Exception {
        try (final var stub = new Stub()) {
            for (final var data : List.of(
                    Map.of(),
                    Map.of("r0", "not repository"),
                    Map.of("r0", repository("wrong/repo", "pullRequests", connection(List.of(), false, null))),
                    Map.of("r0", Map.of("pullRequests", connection(List.of(), false, null))),
                    Map.of("r0", repository("owner/repo", "pullRequests", null)))) {
                stub.data(data);
                assertThrows(
                        IllegalStateException.class,
                        () -> stub.client.pulls(List.of(new ReviewClient.Discovery("owner/repo", null))));
            }
            final var nullAlias = new LinkedHashMap<String, Object>();
            nullAlias.put("r0", null);
            stub.data(nullAlias);
            assertThrows(
                    IllegalStateException.class,
                    () -> stub.client.pulls(List.of(new ReviewClient.Discovery("owner/repo", null))));
            for (final var node : Arrays.asList(null, "not pull", pull(41))) {
                stub.followup(node);
                assertThrows(IllegalStateException.class, () -> stub.client.reviews("owner/repo", 42, null));
            }
            stub.data(Map.of("repository", repository("wrong/repo", "pullRequest", pull(42))));
            assertThrows(IllegalStateException.class, () -> stub.client.reviews("owner/repo", 42, null));
            stub.data(Map.of());
            assertThrows(IllegalStateException.class, () -> stub.client.reviews("owner/repo", 42, null));
        }
    }

    @Test
    void rejectsMalformedReviewFieldsAndRequiredUserIdentitiesWithoutLeakingContent() throws Exception {
        try (final var stub = new Stub()) {
            for (final var field : List.of("databaseId", "body", "state", "url", "submittedAt", "author")) {
                final List<?> bad =
                        switch (field) {
                            case "databaseId" ->
                                Arrays.asList(null, "0", "-1", "1.5", "1e3", "01", "private", 0, -1, 1.5);
                            case "submittedAt" -> List.of("private invalid timestamp");
                            case "author" ->
                                List.of(
                                        Map.of("__typename", "User", "login", "human"),
                                        Map.of("__typename", "User", "login", "human", "databaseId", 0),
                                        Map.of("__typename", "User", "databaseId", 123),
                                        Map.of("login", "human", "databaseId", 123),
                                        Map.of("__typename", "Bot", "login", "bot", "databaseId", -1));
                            default -> Arrays.asList(null, 123, Map.of());
                        };
                for (final var value : bad) {
                    final var review = review();
                    review.put(field, value);
                    final var pull = pull(42);
                    pull.put("reviews", connection(List.of(review), false, null));
                    stub.followup(pull);
                    final var failure = assertThrows(
                            IllegalStateException.class,
                            () -> stub.client.reviews("owner/repo", 42, null),
                            field + ": " + value);
                    assertFalse(failure.toString().contains("private"));
                    assertNull(failure.getCause());
                }
            }
            for (final var number : Arrays.asList(null, 0, -1, 1.5, 2147483648L)) {
                final var pull = pull(42);
                pull.put("number", number);
                stub.discovery(pull);
                assertThrows(
                        IllegalStateException.class,
                        () -> stub.client.pulls(List.of(new ReviewClient.Discovery("owner/repo", null))));
            }
            final var pull = pull(42);
            pull.put("state", null);
            stub.followup(pull);
            assertThrows(IllegalStateException.class, () -> stub.client.reviews("owner/repo", 42, null));
        }
    }

    @Test
    void failsWholeBatchOnLateInvalidMemberAndPartialErrorsWithoutReplay() throws Exception {
        try (final var stub = new Stub()) {
            final var requests = List.of(
                    new ReviewClient.Discovery("owner/repo", null), new ReviewClient.Discovery("owner/other", null));
            final var data = Map.of(
                    "r0", repository("owner/repo", "pullRequests", connection(List.of(pull(42)), false, null)),
                    "r1", repository("wrong/other", "pullRequests", connection(List.of(), false, null)));
            stub.data(data);
            assertThrows(IllegalStateException.class, () -> stub.client.pulls(requests));
            assertEquals(1, stub.requests.get());
            for (final var errors : Arrays.asList(null, List.of(), List.of(Map.of("message", "private diagnostic")))) {
                final var envelope = new LinkedHashMap<String, Object>();
                envelope.put("data", data);
                envelope.put("errors", errors);
                stub.response.set(stub.json.toString(envelope));
                final var before = stub.requests.get();
                final var failure = assertThrows(IllegalStateException.class, () -> stub.client.pulls(requests));
                assertFalse(failure.toString().contains("private"));
                assertEquals(before + 1, stub.requests.get());
            }
            stub.status.set(503);
            stub.response.set("{\"message\":\"private diagnostic\"}");
            final var before = stub.requests.get();
            assertThrows(IllegalStateException.class, () -> stub.client.reviews("owner/repo", 42, null));
            assertEquals(before + 1, stub.requests.get());
        }
    }

    @Test
    void invalidInputsNeverReachTheNetwork() throws Exception {
        try (final var stub = new Stub()) {
            assertThrows(IllegalArgumentException.class, () -> stub.client.pulls(null));
            assertThrows(IllegalArgumentException.class, () -> stub.client.pulls(List.of()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> stub.client.pulls(Arrays.asList((ReviewClient.Discovery) null)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> stub.client.pulls(IntStream.range(0, 11)
                            .mapToObj(i -> new ReviewClient.Discovery("owner/repo" + i, null))
                            .toList()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> stub.client.pulls(List.of(
                            new ReviewClient.Discovery("owner/repo", null),
                            new ReviewClient.Discovery("OWNER/REPO", null))));
            for (final var repository :
                    Arrays.asList(null, "", "owner", "owner/repo/extra", "owner/repo\"", "owner/repo\n")) {
                assertThrows(IllegalArgumentException.class, () -> stub.client.reviews(repository, 42, null));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> stub.client.pulls(List.of(
                                new ReviewClient.Discovery("owner/valid", null),
                                new ReviewClient.Discovery(repository, null))));
            }
            for (final var cursor : List.of("", " ", "x".repeat(4097))) {
                assertThrows(IllegalArgumentException.class, () -> stub.client.reviews("owner/repo", 42, cursor));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> stub.client.pulls(List.of(
                                new ReviewClient.Discovery("owner/valid", null),
                                new ReviewClient.Discovery("owner/repo", cursor))));
            }
            for (final var number : List.of(0, -1)) {
                assertThrows(IllegalArgumentException.class, () -> stub.client.reviews("owner/repo", number, null));
            }
            assertEquals(0, stub.requests.get());
        }
    }

    private static Map<String, Object> repository(final String name, final String field, final Object value) {
        final var result = new LinkedHashMap<String, Object>();
        result.put("nameWithOwner", name);
        result.put(field, value);
        return result;
    }

    private static Map<String, Object> connection(final List<?> nodes, final Object more, final Object cursor) {
        final var info = new LinkedHashMap<String, Object>();
        info.put("hasNextPage", more);
        info.put("endCursor", cursor);
        return new LinkedHashMap<>(Map.of("nodes", nodes, "pageInfo", info));
    }

    private static Map<String, Object> pull(final int number) {
        return new LinkedHashMap<>(
                Map.of("number", number, "state", "OPEN", "reviews", connection(List.of(review()), false, null)));
    }

    private static Map<String, Object> review() {
        return new LinkedHashMap<>(Map.of(
                "databaseId",
                9876543210L,
                "body",
                "private body",
                "submittedAt",
                "2026-11-13T12:00:00Z",
                "state",
                "COMMENTED",
                "url",
                "https://github.com/owner/repo/pull/42#pullrequestreview-9876543210",
                "author",
                Map.of("__typename", "User", "login", "human", "databaseId", 123)));
    }

    private static final class Stub implements AutoCloseable {
        private final JsonMapper json = BotJsonMapper.create(key -> Optional.empty());
        private final AtomicReference<String> response = new AtomicReference<>("{}");
        private final AtomicReference<String> received = new AtomicReference<>();
        private final AtomicInteger status = new AtomicInteger(200);
        private final AtomicInteger requests = new AtomicInteger();
        private final HttpServer server;
        private final GitHubClient github;
        private final ReviewClient client;

        private Stub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/graphql", exchange -> {
                try (exchange) {
                    requests.incrementAndGet();
                    received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    final var bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            github = new GitHubClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    json,
                    () -> "secret",
                    BotTestConfiguration.CLOCK);
            client = new ReviewClient(github);
        }

        private void data(final Map<?, ?> data) {
            response.set(json.toString(Map.of("data", data)));
        }

        private void discovery(final Object pull) {
            data(Map.of("r0", repository("owner/repo", "pullRequests", connection(List.of(pull), false, null))));
        }

        private void followup(final Object pull) {
            data(Map.of("repository", repository("owner/repo", "pullRequest", pull)));
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
