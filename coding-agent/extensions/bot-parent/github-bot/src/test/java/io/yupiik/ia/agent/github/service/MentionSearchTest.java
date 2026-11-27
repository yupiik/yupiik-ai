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
package io.yupiik.ia.agent.github.service;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.MentionSearchClient;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MentionSearchTest {
    private static final MentionSearchClient.Kind ISSUE = MentionSearchClient.Kind.ISSUE;
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    @TempDir
    Path home;

    @Test
    void oneOrganizationQueryFor47SelectedRepositoriesFiltersAndNormalizesResults() throws Exception {
        try (final var stub = new Stub()) {
            final var selected =
                    IntStream.range(0, 47).mapToObj(i -> "yupiik/repo" + i).toList();
            stub.respond(request -> page(
                    4,
                    null,
                    List.of(node("YUPIIK/REPO3"), node("yupiik/repo3"), node("yupiik/excluded"), node("other/repo"))));
            final var received = new ArrayList<List<String>>();
            assertEquals(5, stub.service("bot", "org:yupiik", NOW).poll(selected, ISSUE, 10, repos -> {
                received.add(repos);
                assertThrows(UnsupportedOperationException.class, repos::clear);
                return 5;
            }));
            assertEquals(List.of(List.of("yupiik/repo3")), received);
            assertEquals(1, stub.requests.size());
            final var query = stub.requests.getFirst().query();
            assertTrue(query.contains("mentions:bot sort:created-asc org:yupiik"));
            assertTrue(query.contains("created:>=1970-01-01T00:00:00Z"));
            assertFalse(query.contains("repo:"));
            assertFalse(query.contains("is:open"));
            assertEquals(
                    1, stub.state().values().stream().filter("search"::equals).count());
            final var stateText = Files.readString(home.resolve("state.json"));
            assertFalse(stateText.contains("private-token"));
            assertFalse(stateText.contains("createdAt"));
            assertFalse(stateText.contains("excluded"));
            try (final var files = Files.list(home)) {
                assertEquals(
                        List.of("state.json", "state.lock"),
                        files.map(path -> path.getFileName().toString())
                                .sorted()
                                .toList());
            }
        }
    }

    @Test
    void oversizedOrganizationSplitsBeforeConsumptionAndReusesChildrenAfterRestart() throws Exception {
        try (final var stub = new Stub()) {
            final var selected = List.of("o/a", "o/b", "o/c", "o/d");
            stub.respond(request -> request.query().contains("org:o")
                    ? page(900, "oversized", List.of(node("o/a")))
                    : page(1, null, List.of(node(request.query().contains("repo:o/a") ? "o/a" : "o/c"))));
            final var received = new ArrayList<List<String>>();
            stub.service("bot", "org:o", NOW).poll(selected, ISSUE, 1, repos -> {
                received.add(repos);
                return 0;
            });
            assertTrue(received.isEmpty(), "A capped probe must not reach the consumer");
            assertEquals("3", stub.field("nextId"));
            assertEquals(2, stub.field("active").split(",").length);
            stub.service("bot", "org:o", NOW).poll(selected, ISSUE, 2, repos -> {
                received.add(repos);
                return 0;
            });
            assertEquals(3, stub.requests.size());
            assertEquals(
                    1,
                    stub.requests.stream()
                            .filter(request -> request.query().contains("org:o"))
                            .count());
            assertEquals(2, received.size());
            for (final var request : stub.requests.subList(1, 3)) {
                assertFalse(request.query().contains("org:o"));
                assertEquals(2, occurrences(request.query(), "repo:"));
                assertTrue(request.query().length() <= 240);
            }
            assertEquals("1", stub.field("leaf/0/totalRequests"));
            assertEquals("split", stub.field("leaf/0/status"));
        }
    }

    @Test
    void budgetOnePersistsRoundRobinAndContinuationThenResetsForFullReplay() throws Exception {
        try (final var stub = new Stub()) {
            final var selected = List.of("a/repo", "b/repo");
            stub.respond(request -> {
                if (request.query().contains("org:a")) {
                    return page(2, request.after() == null ? "a-next" : null, List.of(node("a/repo")));
                }
                return page(1, null, List.of(node("b/repo")));
            });
            final var received = new ArrayList<List<String>>();
            final ToIntFunction<List<String>> consume = repos -> {
                received.add(repos);
                return 1;
            };
            stub.service("bot", "org:a,user:b", NOW).poll(selected, ISSUE, 1, consume);
            assertEquals("a-next", stub.field("leaf/0/after"));
            stub.service("bot", "org:a,user:b", NOW.plusSeconds(10)).poll(selected, ISSUE, 1, consume);
            assertEquals(List.of(List.of("a/repo"), List.of("b/repo")), received);
            stub.service("bot", "org:a,user:b", NOW.plusSeconds(20)).poll(selected, ISSUE, 1, consume);
            assertEquals(stub.requests.getFirst().query(), stub.requests.get(2).query());
            assertEquals("a-next", stub.requests.get(2).after());
            assertNull(stub.field("leaf/0/after"));
            assertEquals("2", stub.field("leaf/0/lastPages"));
            assertEquals("2", stub.field("leaf/0/totalRequests"));
            assertNotNull(stub.field("leaf/0/lastCompletedAt"));
            assertEquals("0", stub.field("leaf/0/seen"));
            stub.service("bot", "org:a,user:b", NOW.plusSeconds(30)).poll(selected, ISSUE, 1, consume);
            stub.service("bot", "org:a,user:b", NOW.plusSeconds(40)).poll(selected, ISSUE, 1, consume);
            assertNull(stub.requests.get(4).after());
            assertEquals(stub.requests.getFirst().query(), stub.requests.get(4).query());
            assertEquals("3", stub.field("leaf/0/totalRequests"));
        }
    }

    @Test
    void createdIntervalsOverlapInclusivelyAndKeepTheFutureTailUnbounded() throws Exception {
        try (final var stub = new Stub()) {
            stub.respond(request -> page(900, null, List.of()));
            final var selected = List.of("o/r");
            stub.service("bot", "", Instant.ofEpochSecond(100)).poll(selected, ISSUE, 1, repos -> fail("Capped page"));
            assertEquals("0", stub.field("leaf/1/from"));
            assertEquals("50", stub.field("leaf/1/until"));
            assertEquals("50", stub.field("leaf/2/from"));
            assertNull(stub.field("leaf/2/until"));
            stub.respond(request -> page(0, null, List.of()));
            stub.service("bot", "", Instant.ofEpochSecond(200)).poll(selected, ISSUE, 2, repos -> 0);
            final var left = stub.requests.get(1).query();
            final var right = stub.requests.get(2).query();
            assertTrue(left.contains("created:>=1970-01-01T00:00:00Z"));
            assertTrue(left.contains("created:<=1970-01-01T00:00:50Z"));
            assertTrue(right.contains("created:>=1970-01-01T00:00:50Z"));
            assertFalse(right.contains("created:<="));
            assertFalse(left.contains("updated:"));
            assertFalse(right.contains("updated:"));
            stub.respond(request -> page(900, null, List.of()));
            stub.service("bot", "", Instant.ofEpochSecond(300)).poll(selected, ISSUE, 1, repos -> fail("Capped page"));
            assertEquals("0", stub.field("leaf/3/from"));
            assertEquals("25", stub.field("leaf/3/until"));
            assertEquals("25", stub.field("leaf/4/from"));
            assertEquals("50", stub.field("leaf/4/until"));
            assertNull(stub.field("leaf/2/until"));
        }
    }

    @Test
    void denseOneSecondRangeUsesPersistentFallbackAtMostOncePerPollEvenWithZeroBudget() throws Exception {
        try (final var stub = new Stub()) {
            stub.respond(request -> page(900, null, List.of()));
            final var selected = List.of("o/r");
            final var received = new ArrayList<List<String>>();
            final ToIntFunction<List<String>> consume = repos -> {
                received.add(repos);
                return 3;
            };
            assertEquals(3, stub.service("bot", "", Instant.ofEpochSecond(1)).poll(selected, ISSUE, 100, consume));
            assertEquals("directfallback", stub.field("leaf/0/status"));
            assertEquals(1, received.size());
            assertEquals(1, stub.requests.size());
            assertEquals(3, stub.service("bot", "", NOW).poll(selected, ISSUE, 0, consume));
            assertEquals(List.of(selected, selected), received);
            assertEquals(1, stub.requests.size());
            assertEquals("1", stub.field("leaf/0/totalRequests"));
        }
    }

    @Test
    void fallbackDecisionSurvivesConsumerFailureWithoutLosingPageCursor() throws Exception {
        try (final var stub = new Stub()) {
            stub.respond(request ->
                    request.after() == null ? page(2, "keep", List.of(node("o/r"))) : page(900, null, List.of()));
            final var service = stub.service("bot", "", Instant.ofEpochSecond(1));
            service.poll(List.of("o/r"), ISSUE, 1, repos -> 0);
            assertThrows(
                    IllegalStateException.class,
                    () -> service.poll(List.of("o/r"), ISSUE, 1, repos -> {
                        throw new IllegalStateException("consumer failed");
                    }));
            assertEquals("keep", stub.field("leaf/0/after"));
            assertEquals("directfallback", stub.field("leaf/0/status"));
            assertEquals(7, stub.service("bot", "", NOW).poll(List.of("o/r"), ISSUE, 0, repos -> 7));
            assertEquals(2, stub.requests.size());
        }
    }

    @Test
    void failedConsumerDoesNotAdvanceCursorOrSuccessfulPageStatistics() throws Exception {
        try (final var stub = new Stub()) {
            stub.respond(request -> page(2, request.after() == null ? "first" : "second", List.of(node("o/r"))));
            final var service = stub.service("bot", "", NOW);
            service.poll(List.of("o/r"), ISSUE, 1, repos -> 0);
            final var before = stub.state();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.poll(List.of("o/r"), ISSUE, 1, repos -> {
                        throw new IllegalArgumentException("feed authorization failed");
                    }));
            assertEquals(before, stub.state());
            stub.service("bot", "", NOW).poll(List.of("o/r"), ISSUE, 1, repos -> 0);
            assertEquals("first", stub.requests.get(1).after());
            assertEquals("first", stub.requests.get(2).after());
            assertEquals("second", stub.field("leaf/0/after"));
        }
    }

    @Test
    void malformedAndPartialGraphqlResponsesNeverAdvanceTheCursor() throws Exception {
        try (final var stub = new Stub()) {
            stub.respond(request -> page(2, "keep", List.of(node("o/r"))));
            final var service = stub.service("bot", "", NOW);
            service.poll(List.of("o/r"), ISSUE, 1, repos -> 0);
            final var before = stub.state();
            final var partial = new LinkedHashMap<>(page(1, null, List.of(node("o/r"))));
            partial.put("errors", List.of(Map.of("message", "private partial error")));
            final var malformedNode = new LinkedHashMap<>(node("o/r"));
            malformedNode.remove("createdAt");
            for (final var response : List.of(
                    partial,
                    Map.of("data", Map.of("search", Map.of("issueCount", 1, "nodes", List.of()))),
                    page(1, "new", List.of(malformedNode)),
                    page(1, "keep", List.of(node("o/r"))),
                    Map.of(
                            "data",
                            Map.of(
                                    "search",
                                    Map.of(
                                            "issueCount",
                                            1,
                                            "nodes",
                                            List.of(node("o/r")),
                                            "pageInfo",
                                            Map.of("hasNextPage", true)))))) {
                stub.respond(request -> response);
                assertThrows(
                        RuntimeException.class,
                        () -> service.poll(List.of("o/r"), ISSUE, 1, repos -> fail("Invalid response")));
                assertEquals(before, stub.state());
                assertEquals("keep", stub.requests.getLast().after());
            }
        }
    }

    @Test
    void changingLoginSelectionApiOrKindStartsAnIndependentPlan() throws Exception {
        try (final var stub = new Stub()) {
            stub.respond(request -> page(2, "next", List.of(node("o/r"))));
            stub.service("bot", "", NOW).poll(List.of("o/r"), ISSUE, 1, repos -> 0);
            stub.service("other-bot", "", NOW).poll(List.of("o/r"), ISSUE, 1, repos -> 0);
            stub.service("bot", "", NOW).poll(List.of("o/r", "o/extra"), ISSUE, 1, repos -> 0);
            final var alternate = stub.configuration("bot", "", stub.base + "/enterprise");
            new MentionSearch(
                            alternate,
                            new TaskStore(alternate, stub.json),
                            stub.github,
                            Clock.fixed(NOW, ZoneOffset.UTC))
                    .poll(List.of("o/r"), ISSUE, 1, repos -> 0);
            stub.respond(request -> page(1, "discussion-next", List.of(node("o/r", "Discussion"))));
            stub.service("bot", "", NOW).poll(List.of("o/r"), MentionSearchClient.Kind.DISCUSSION, 1, repos -> 0);
            assertTrue(stub.requests.stream().allMatch(request -> request.after() == null));
            assertEquals(
                    5,
                    stub.state().keySet().stream()
                            .filter(key -> key.endsWith("/active"))
                            .count());
            assertTrue(stub.requests.getLast().document().contains("type:DISCUSSION"));
            stub.respond(request -> page(2, null, List.of(node("o/r"))));
            stub.service("bot", "", NOW).poll(List.of("o/r"), ISSUE, 1, repos -> 0);
            assertEquals("next", stub.requests.getLast().after());
        }
    }

    @Test
    void repositoryInputOrderDoesNotInvalidateThePlan() throws Exception {
        try (final var stub = new Stub()) {
            stub.respond(request -> page(2, request.after() == null ? "next" : null, List.of(node("o/a"))));
            stub.service("bot", "", NOW).poll(List.of("o/b", "o/a"), ISSUE, 1, repos -> 0);
            stub.service("bot", "", NOW).poll(List.of("o/a", "o/b"), ISSUE, 1, repos -> 0);
            assertEquals("next", stub.requests.getLast().after());
            assertEquals(
                    stub.requests.getFirst().query(), stub.requests.getLast().query());
        }
    }

    @Test
    void emptyPagesCompleteOnceAndADenseLeafCannotStarveItsNeighbor() throws Exception {
        try (final var stub = new Stub()) {
            final var selected = List.of("dense/r", "quiet/r", "zero/r");
            stub.respond(request -> request.query().contains("org:dense")
                    ? page(2, request.after() == null ? "one" : "two", List.of(node("dense/r")))
                    : page(0, null, List.of()));
            final var service = stub.service("bot", "org:dense,org:quiet,org:zero", NOW);
            for (int i = 0; i < 3; i++) {
                service.poll(selected, ISSUE, 1, repos -> 0);
            }
            assertTrue(stub.requests.get(1).query().contains("org:quiet"));
            assertTrue(stub.requests.get(2).query().contains("org:zero"));
            stub.respond(request -> page(0, null, List.of()));
            final int before = stub.requests.size();
            service.poll(selected, ISSUE, 1000, repos -> 0);
            assertEquals(3, stub.requests.size() - before, "Terminal zero-result leaves must not loop");
            assertEquals("2", stub.field("leaf/0/totalRequests"));
        }
    }

    @Test
    void observedTraversalCapSplitsEvenWhenReportedCountIsLow() throws Exception {
        try (final var stub = new Stub()) {
            stub.respond(request -> page(100, "page-" + stub.requests.size(), Collections.nCopies(100, node("o/r"))));
            final var service = stub.service("bot", "", NOW);
            final var consumed = new ArrayList<List<String>>();
            service.poll(List.of("o/r"), ISSUE, 9, repos -> {
                consumed.add(repos);
                return 0;
            });
            assertEquals(9, stub.requests.size());
            assertEquals(8, consumed.size(), "Observed traversal size also splits below the 1000-result cap");
            assertEquals("split", stub.field("leaf/0/status"));
            assertEquals("9", stub.field("leaf/0/totalRequests"));
            assertEquals("3", stub.field("nextId"));
        }
    }

    @Test
    void terminalPageWithAStaleCountStillSplitsAtTheSafetyThreshold() throws Exception {
        try (final var stub = new Stub()) {
            stub.respond(request -> page(
                    100,
                    stub.requests.size() == 9 ? null : "page-" + stub.requests.size(),
                    Collections.nCopies(100, node("o/r"))));
            stub.service("bot", "", NOW).poll(List.of("o/r"), ISSUE, 9, repos -> 0);
            assertEquals("split", stub.field("leaf/0/status"));
            assertEquals("9", stub.field("leaf/0/totalRequests"));
        }
    }

    @Test
    void explicitQueriesAreShortAndNeverUseAnUnselectedOwnerScope() throws Exception {
        try (final var stub = new Stub()) {
            final var selected = IntStream.range(0, 40)
                    .mapToObj(i -> "o/repository-with-a-long-name-" + i)
                    .toList();
            stub.respond(request -> page(0, null, List.of()));
            stub.service("bot", "org:unselected", NOW).poll(selected, ISSUE, 100, repos -> 0);
            final var covered = new HashSet<String>();
            for (final var request : stub.requests) {
                assertTrue(request.query().length() <= 240);
                assertFalse(request.query().contains("org:"));
                for (final var token : request.query().split(" ")) {
                    if (token.startsWith("repo:")) {
                        covered.add(token.substring(5));
                    }
                }
            }
            assertEquals(new HashSet<>(selected), covered);
            assertTrue(stub.requests.size() > 1);
        }
    }

    @ParameterizedTest(name = "{0} leaves, {1} tree nodes")
    @CsvSource({"256,256", "256,511"})
    void leafAndTreeCapsFallBackWithoutDroppingRepositories(final int leafCount, final int treeSize) throws Exception {
        try (final var stub = new Stub()) {
            final var selected =
                    IntStream.range(0, leafCount + 1).mapToObj(i -> "o/r" + i).toList();
            // Start from the durable boundary, not 511 repetitions of the already-tested split path.
            // Keep all real transport, validation, fallback and restart behavior under test.
            stub.service("bot", "org:o", NOW).poll(selected, ISSUE, 0, repos -> fail("No request budget"));
            final var prefix = stub.prefix();
            final var template = stub.state();
            final int firstActive = treeSize - leafCount;
            stub.store.transaction(state -> {
                final var cursors = state.cursors();
                for (int id = 0; id < treeSize; id++) {
                    final var leafPrefix = prefix + "leaf/" + id + "/";
                    template.forEach((key, value) -> {
                        if (key.startsWith(prefix + "leaf/0/")) {
                            cursors.put(leafPrefix + key.substring((prefix + "leaf/0/").length()), value);
                        }
                    });
                    final int index = id - firstActive;
                    cursors.put(leafPrefix + "status", index < 0 ? "split" : "search");
                    if (index >= 0) {
                        cursors.remove(leafPrefix + "scope");
                        cursors.put(
                                leafPrefix + "reposCSV",
                                index == 0 ? selected.get(0) + "," + selected.get(1) : selected.get(index + 1));
                    }
                }
                cursors.put(prefix + "nextId", Integer.toString(treeSize));
                cursors.put(
                        prefix + "active",
                        String.join(
                                ",",
                                IntStream.range(firstActive, treeSize)
                                        .mapToObj(Integer::toString)
                                        .toList()));
                return null;
            });
            stub.respond(request -> page(1000, null, List.of()));
            final var fallbackCalls = new ArrayList<List<String>>();
            stub.service("bot", "org:o", NOW).poll(selected, ISSUE, 1, repos -> {
                fallbackCalls.add(repos);
                return 0;
            });
            assertEquals(List.of(selected.subList(0, 2)), fallbackCalls);
            assertEquals(Integer.toString(treeSize), stub.field("nextId"));
            assertEquals(leafCount, stub.field("active").split(",").length);
            assertEquals("directfallback", stub.field("leaf/" + firstActive + "/status"));
            assertEquals(1, stub.requests.size());
            final var covered = new HashSet<String>();
            final var persisted = stub.state();
            for (int id = firstActive; id < treeSize; id++) {
                covered.addAll(List.of(
                        persisted.get(prefix + "leaf/" + id + "/reposCSV").split(",")));
            }
            assertEquals(new HashSet<>(selected), covered);
            fallbackCalls.clear();
            stub.service("bot", "org:o", NOW).poll(selected, ISSUE, 0, repos -> {
                fallbackCalls.add(repos);
                return 0;
            });
            assertEquals(1, stub.requests.size(), "Restart must reuse persistent fallback without a request");
            assertEquals(List.of(selected.subList(0, 2)), fallbackCalls);
        }
    }

    @Test
    void tooManyInitialGroupsUseBoundedFallbackRatherThanDroppingTheTail() throws Exception {
        try (final var stub = new Stub()) {
            final var selected = IntStream.range(0, 300)
                    .mapToObj(i -> "o/" + "r".repeat(96) + i)
                    .toList();
            stub.respond(request -> page(0, null, List.of()));
            final var received = new ArrayList<String>();
            stub.service("bot", "", NOW).poll(selected, ISSUE, 0, repos -> {
                received.addAll(repos);
                return 0;
            });
            assertEquals(256, stub.field("active").split(",").length);
            assertEquals("256", stub.field("nextId"));
            assertEquals(45, received.size());
            assertTrue(selected.containsAll(received));
            assertTrue(stub.requests.isEmpty());
        }
    }

    @Test
    void corruptPlansFailClosedWithoutRequestsOrStateReplacement() throws Exception {
        try (final var stub = new Stub()) {
            final var service = stub.service("bot", "", NOW);
            service.poll(List.of("o/r"), ISSUE, 0, repos -> 0);
            final var initial = stub.state();
            final var prefix = stub.prefix();
            for (final var corruption : List.of(
                    Map.entry("active", "0,0"),
                    Map.entry("active", "9"),
                    Map.entry("position", "1"),
                    Map.entry("nextId", "512"),
                    Map.entry("leaf/0/from", "-1"),
                    Map.entry("leaf/0/from", "1"),
                    Map.entry("leaf/0/until", "5"),
                    Map.entry("leaf/0/until", "invalid"),
                    Map.entry("leaf/0/reposCSV", "o/other"),
                    Map.entry("leaf/0/scope", "org:other"),
                    Map.entry("leaf/0/after", " "),
                    Map.entry("leaf/0/depth", "65"),
                    Map.entry("leaf/0/status", "ignored"),
                    Map.entry("leaf/0/totalRequests", "-1"),
                    Map.entry("unexpected", "value"))) {
                stub.store.transaction(state -> {
                    state.cursors().clear();
                    state.cursors().putAll(initial);
                    state.cursors().put(prefix + corruption.getKey(), corruption.getValue());
                    return null;
                });
                final var corrupt = stub.state();
                assertThrows(
                        IllegalStateException.class,
                        () -> service.poll(List.of("o/r"), ISSUE, 1, repos -> fail("Corrupt plan")));
                assertEquals(corrupt, stub.state());
                assertTrue(stub.requests.isEmpty());
            }
        }
    }

    @Test
    void invalidInputsAreRejectedBeforeRequestsAndEmptySelectionIsANoOp() throws Exception {
        try (final var stub = new Stub()) {
            final var service = stub.service("bot", "", NOW);
            for (final var repo : List.of("o/r is:open", "o/r\nrepo:secret/x", "o", "o/r/x", "o/..")) {
                assertThrows(IllegalArgumentException.class, () -> service.poll(List.of(repo), ISSUE, 1, repos -> 0));
            }
            for (final var login : List.of("@bot", "bot is:open", "bot\nrepo:o/r", "")) {
                assertThrows(IllegalArgumentException.class, () -> stub.service(login, "", NOW));
            }
            assertThrows(IllegalArgumentException.class, () -> service.poll(List.of("o/r"), ISSUE, -1, repos -> 0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> stub.service("bot", "org:o is:open", NOW).poll(List.of("o/r"), ISSUE, 1, repos -> 0));
            assertEquals(0, service.poll(List.of(), ISSUE, 3, repos -> fail("Empty selection")));
            assertTrue(stub.requests.isEmpty());
            assertTrue(stub.state().isEmpty());
        }
    }

    @Test
    void networkAndConsumerRunOutsideTheDurableStateLock() throws Exception {
        try (final var stub = new Stub();
                final var executor = Executors.newSingleThreadExecutor()) {
            stub.respond(request -> {
                try {
                    executor.submit(() -> stub.store.transaction(
                                    state -> state.cursors().size()))
                            .get(5, TimeUnit.SECONDS);
                    return page(1, null, List.of(node("o/r")));
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertEquals(4, stub.service("bot", "", NOW).poll(List.of("o/r"), ISSUE, 1, repos -> {
                try {
                    executor.submit(() -> stub.store.transaction(
                                    state -> state.cursors().size()))
                            .get(5, TimeUnit.SECONDS);
                    return 4;
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
    }

    private static long occurrences(final String value, final String token) {
        return token.isEmpty()
                ? value.length()
                : (value.length() - value.replace(token, "").length()) / token.length();
    }

    private static Map<String, Object> node(final String repo) {
        return node(repo, "Issue");
    }

    private static Map<String, Object> node(final String repo, final String type) {
        return Map.of(
                "__typename",
                type,
                "number",
                1,
                "createdAt",
                "2020-01-01T00:00:00Z",
                "repository",
                Map.of("nameWithOwner", repo));
    }

    private static Map<String, Object> page(final int count, final String next, final List<?> nodes) {
        final var info = new LinkedHashMap<String, Object>();
        info.put("hasNextPage", next != null);
        info.put("endCursor", next);
        return Map.of(
                "data",
                Map.of(
                        "search",
                        Map.of("issueCount", count, "discussionCount", count, "nodes", nodes, "pageInfo", info)));
    }

    private record Request(String document, String query, String after) {}

    private final class Stub implements AutoCloseable {
        private final JsonMapper json = BotJsonMapper.create(key -> Optional.empty());
        private final AtomicReference<Function<Request, Map<String, ?>>> response =
                new AtomicReference<>(request -> page(0, null, List.of()));
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final HttpServer server;
        private final String base;
        private final GitHubClient github;
        private final TaskStore store;

        private Stub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/graphql", exchange -> {
                try (exchange) {
                    final var wire = json.fromString(
                            GitHubClient.GraphqlRequest.class,
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    final var variables = (Map<?, ?>) wire.variables();
                    final var request =
                            new Request(wire.query(), (String) variables.get("query"), (String) variables.get("after"));
                    requests.add(request);
                    final var bytes =
                            json.toString(response.get().apply(request)).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            github = new GitHubClient(URI.create(base), json, () -> "private-token", Clock.fixed(NOW, ZoneOffset.UTC));
            store = new TaskStore(configuration("bot", "", base), json);
        }

        private GithubBotConfiguration configuration(final String login, final String discovery, final String api) {
            return new GithubBotConfiguration(
                    home.toString(),
                    api,
                    login,
                    0,
                    null,
                    null,
                    new GithubBotConfiguration.Repositories("", discovery, "", "", List.of(), false, false));
        }

        private MentionSearch service(final String login, final String discovery, final Instant now) {
            final var conf = configuration(login, discovery, base);
            return new MentionSearch(conf, new TaskStore(conf, json), github, Clock.fixed(now, ZoneOffset.UTC));
        }

        private void respond(final Function<Request, Map<String, ?>> handler) {
            response.set(handler);
        }

        private Map<String, String> state() {
            return store.transaction(state -> Map.copyOf(state.cursors()));
        }

        private String prefix() {
            return state().keySet().stream()
                    .filter(key -> key.endsWith("/active"))
                    .map(key -> key.substring(0, key.length() - "active".length()))
                    .findFirst()
                    .orElseThrow();
        }

        private String field(final String suffix) {
            return state().get(prefix() + suffix);
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
