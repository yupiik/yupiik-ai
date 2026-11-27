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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.PollOptions;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Search selects repositories, never grants authority or substitutes for the actual feeds. */
class PollerSearchTest {
    private static final Instant START = Instant.parse("2026-11-13T12:00:00Z");

    @TempDir
    Path home;

    @Test
    void emptySearchAfterRestartUsesOneRequestAndKeepsTheOriginalSince() throws Exception {
        try (final var fixture = new Fixture()) {
            assertEquals(0, fixture.poll(START));
            assertEquals(6, fixture.paths.size());
            assertTrue(fixture.searches.isEmpty());
            assertEquals(
                    START.toString(),
                    fixture.store.transaction(state -> state.cursors().get("initialSince")));
            assertTrue(Files.isRegularFile(home.resolve("state.json")));

            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(60)));
            assertEquals(List.of("/graphql"), fixture.paths);
            assertEquals(1, fixture.searches.size());
            final var search = fixture.searches.getFirst();
            assertFalse(search.discussions());
            assertNull(search.after());
            assertFalse(search.query().contains("is:open"), "Closed issues must remain candidates");
            assertEquals(
                    START.toString(),
                    fixture.store.transaction(state -> state.cursors().get("initialSince")));
        }
    }

    @Test
    void emptySearchWithDiscussionsUsesExactlyOneQueryPerKind() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.discussions = true;
            assertEquals(0, fixture.poll(START));
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(60)));
            assertEquals(2, fixture.paths.size());
            assertEquals(
                    1, fixture.searches.stream().filter(SearchCall::discussions).count());
            assertEquals(
                    1, fixture.searches.stream().filter(it -> !it.discussions()).count());
            assertTrue(fixture.graphql.stream().allMatch(it -> it.query().startsWith("query MentionCandidates")));
        }
    }

    @Test
    void candidatesScanAllRepositoryFeedsButCannotSelectExcludedRepositories() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.reviews = true;
            assertEquals(0, fixture.poll(START));
            fixture.issueCandidates = List.of(
                    candidate("Issue", "owner/repo", 12),
                    candidate("PullRequest", "owner/repo", 99),
                    candidate("Issue", "malicious/outside", 12),
                    candidate("Issue", "owner/excluded", 12));
            fixture.feedItems.put(
                    "/repos/owner/repo/issues/comments",
                    List.of(
                            item(71, 42, "@bot solve a different parent", 700),
                            item(72, 99, "@bot solve unauthorized", 701),
                            item(73, 42, "> @bot solve quoted", 702)));
            fixture.clearCalls();
            assertEquals(1, fixture.poll(START.plusSeconds(60)));
            assertTrue(fixture.paths.containsAll(List.of(
                    "/repos/owner/repo/issues",
                    "/repos/owner/repo/issues/comments",
                    "/repos/owner/repo/pulls/comments")));
            assertEquals(
                    3,
                    fixture.paths.stream()
                            .filter(it -> it.startsWith("/repos/"))
                            .count());
            assertFalse(fixture.paths.stream().anyMatch(it -> it.contains("outside") || it.contains("excluded")));
            final var discovery = fixture.graphql.stream()
                    .filter(it -> it.query().startsWith("query ReviewDiscovery"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("repo", discovery.variables().get("name0"));
            assertFalse(discovery.variables().containsValue("other"));
            final var task = fixture.store.transaction(
                    state -> TaskFixtures.map(state.list().getFirst()));
            assertEquals("owner/repo", task.get("repository"));
            assertEquals("42", task.get("actorId"));
            assertEquals("a different parent", task.get("request"));
            assertEquals("https://github.com/owner/repo/issues/700#issuecomment-71", task.get("reference"));

            // Durable receipts still prevent duplicates if completed tasks have been removed.
            fixture.store.transaction(state -> {
                state.tasks().clear();
                return null;
            });
            assertEquals(0, fixture.poll(START.plusSeconds(120)));
            assertTrue(fixture.store.<Boolean>transaction(state -> state.list().isEmpty()));
        }
    }

    @Test
    void discussionCandidatesSelectDiscussionCollectorsSeparately() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.discussions = true;
            assertEquals(0, fixture.poll(START));
            fixture.issueCandidates = List.of(candidate("Issue", "owner/repo", 12));
            fixture.discussionCandidates = List.of(candidate("Discussion", "owner/other", 25));
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(60)));
            assertEquals(
                    3,
                    fixture.paths.stream()
                            .filter(it -> it.startsWith("/repos/owner/repo/"))
                            .count());
            assertFalse(fixture.paths.stream().anyMatch(it -> it.startsWith("/repos/owner/other/")));
            assertTrue(fixture.graphql.stream()
                    .anyMatch(it -> it.query().startsWith("query DiscussionDiscovery")
                            && "other".equals(it.variables().get("name"))));
            assertFalse(fixture.graphql.stream()
                    .anyMatch(it -> it.query().startsWith("query DiscussionDiscovery")
                            && "repo".equals(it.variables().get("name"))));
        }
    }

    @Test
    void failedCandidateFeedReplaysItsSearchPageAfterRestartBeforeAdvancing() throws Exception {
        try (final var fixture = new Fixture()) {
            assertEquals(0, fixture.poll(START));
            fixture.issueCandidates = List.of(candidate("Issue", "owner/repo", 12));
            fixture.searchNext = "search-next";
            fixture.feedItems.put("/repos/owner/repo/issues", List.of(item(81, 42, "@bot solve once", 12)));
            fixture.failPath = "/repos/owner/repo/pulls/comments";
            fixture.clearCalls();
            assertThrows(RuntimeException.class, () -> fixture.poll(START.plusSeconds(60)));
            final var failedPage = fixture.searches.getFirst();
            assertNull(failedPage.after());
            assertEquals(1, fixture.searches.size(), "The page budget applies before following the search cursor");

            fixture.failPath = "";
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(120)));
            assertEquals(failedPage, fixture.searches.getFirst());
            assertEquals(1, fixture.searches.size());
            assertTrue(fixture.paths.contains("/repos/owner/repo/pulls/comments"));
            assertEquals(
                    1, fixture.store.<Integer>transaction(state -> state.list().size()));
            assertTrue(Files.readString(home.resolve("state.json")).contains("search-next"));

            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(180)));
            assertEquals("search-next", fixture.searches.getFirst().after());
        }
    }

    @Test
    void optionalReviewFailureAlsoPreventsSearchPageCommit() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.reviews = true;
            assertEquals(0, fixture.poll(START));
            fixture.issueCandidates = List.of(candidate("PullRequest", "owner/repo", 12));
            fixture.searchNext = "search-next";
            fixture.failOperation = "query ReviewDiscovery";
            assertThrows(RuntimeException.class, () -> fixture.poll(START.plusSeconds(60)));
            final var failed = fixture.searches.getFirst();
            fixture.failOperation = "";
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(120)));
            assertEquals(failed, fixture.searches.getFirst());
            assertTrue(fixture.graphql.stream().anyMatch(it -> it.query().startsWith("query ReviewDiscovery")));
        }
    }

    @Test
    void periodicDirectReconciliationFindsAnIndexMissedMentionAndSurvivesRestarts() throws Exception {
        try (final var fixture = new Fixture()) {
            assertEquals(0, fixture.poll(START));
            fixture.feedItems.put("/repos/owner/repo/issues", List.of(item(91, 42, "@bot solve index miss", 12)));
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(3599)));
            assertEquals(List.of("/graphql"), fixture.paths);
            fixture.clearCalls();
            assertEquals(1, fixture.poll(START.plusSeconds(3600)));
            assertEquals(6, fixture.paths.size());
            assertTrue(fixture.searches.isEmpty());
            assertEquals(
                    "index miss",
                    fixture.store.transaction(
                            state -> TaskFixtures.map(state.list().getFirst()).get("request")));
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(3601)));
            assertEquals(List.of("/graphql"), fixture.paths);
        }
    }

    @Test
    void failedBaselineMustFinishDirectReconciliationBeforeSearching() throws Exception {
        try (final var fixture = new Fixture()) {
            // Scan sequentially: cancellation of concurrent client requests does not
            // guarantee their server handlers finish before the next poll clears its calls.
            fixture.parallelism = 1;
            fixture.failPath = "/repos/owner/repo/pulls/comments";
            assertThrows(RuntimeException.class, () -> fixture.poll(START));
            fixture.failPath = "";
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(60)));
            assertTrue(fixture.searches.isEmpty());
            assertEquals(6, fixture.paths.size());
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(120)));
            assertEquals(List.of("/graphql"), fixture.paths);
        }
    }

    @Test
    void unfinishedBaselinePagesKeepDirectModeUntilAllContinuationsComplete() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.paginateIssues = true;
            assertEquals(0, fixture.poll(START));
            assertTrue(
                    fixture.store.<Boolean>transaction(state -> state.cursors().containsKey("owner/repo/issues:next")));
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(60)));
            assertTrue(fixture.searches.isEmpty());
            assertTrue(fixture.paths.contains("/continued"));
            assertFalse(
                    fixture.store.<Boolean>transaction(state -> state.cursors().containsKey("owner/repo/issues:next")));
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(120)));
            assertEquals(List.of("/graphql"), fixture.paths);
        }
    }

    @Test
    void activeInlineContinuationIsResumedEvenWhenTheNextSearchMissesItsRepository() throws Exception {
        try (final var fixture = new Fixture()) {
            assertEquals(0, fixture.poll(START));
            fixture.issueCandidates = List.of(candidate("Issue", "owner/repo", 12));
            fixture.paginateIssues = true;
            assertEquals(0, fixture.poll(START.plusSeconds(60)));
            fixture.issueCandidates = List.of();
            fixture.feedItems.put("/continued", List.of(item(101, 42, "@bot solve continuation", 12)));
            fixture.clearCalls();
            assertEquals(1, fixture.poll(START.plusSeconds(120)));
            assertEquals(1, fixture.searches.size());
            assertTrue(fixture.paths.contains("/continued"));
            assertFalse(
                    fixture.store.<Boolean>transaction(state -> state.cursors().containsKey("owner/repo/issues:next")));
        }
    }

    @Test
    void activeNativeReviewContinuationSurvivesAnEmptyCandidateSearch() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.reviews = true;
            assertEquals(0, fixture.poll(START));
            fixture.issueCandidates = List.of(candidate("PullRequest", "owner/repo", 12));
            fixture.showReviewPull = true;
            fixture.paginateReviews = true;
            assertEquals(0, fixture.poll(START.plusSeconds(60)));
            assertTrue(fixture.store.<Boolean>transaction(
                    state -> state.cursors().containsKey("owner/repo/review-summary-graphql/pulls/12/reviews:next")));
            fixture.issueCandidates = List.of();
            fixture.paginateReviews = false;
            fixture.clearCalls();
            assertEquals(1, fixture.poll(START.plusSeconds(120)));
            assertEquals(1, fixture.searches.size());
            final var continuation = fixture.graphql.stream()
                    .filter(it -> it.query().startsWith("query ReviewContinuation"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("review-next", continuation.variables().get("after"));
            assertFalse(fixture.store.<Boolean>transaction(
                    state -> state.cursors().containsKey("owner/repo/review-summary-graphql/pulls/12/reviews:next")));
            assertEquals(
                    "nested review",
                    fixture.store.transaction(
                            state -> TaskFixtures.map(state.list().getFirst()).get("request")));
        }
    }

    @Test
    void failedDiscussionCollectionReplaysItsCandidatePageAfterRestart() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.discussions = true;
            assertEquals(0, fixture.poll(START));
            fixture.discussionCandidates = List.of(candidate("Discussion", "owner/repo", 12));
            fixture.searchNext = "search-next";
            fixture.failOperation = "query DiscussionDiscovery";
            assertThrows(RuntimeException.class, () -> fixture.poll(START.plusSeconds(60)));
            final var failed = fixture.searches.stream()
                    .filter(SearchCall::discussions)
                    .findFirst()
                    .orElseThrow();
            fixture.failOperation = "";
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(120)));
            assertEquals(
                    failed,
                    fixture.searches.stream()
                            .filter(SearchCall::discussions)
                            .findFirst()
                            .orElseThrow());
            assertTrue(fixture.graphql.stream().anyMatch(it -> it.query().startsWith("query DiscussionDiscovery")));
        }
    }

    @Test
    void changedRepositorySelectionRequiresANewDirectReconciliation() throws Exception {
        try (final var fixture = new Fixture()) {
            assertEquals(0, fixture.poll(START));
            fixture.selected = "owner/repo";
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(60)));
            assertTrue(fixture.searches.isEmpty());
            assertEquals(3, fixture.paths.size());
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(120)));
            assertEquals(List.of("/graphql"), fixture.paths);
        }
    }

    @Test
    void changingOptionalCollectorsRequiresANewDirectReconciliation() throws Exception {
        try (final var fixture = new Fixture()) {
            assertEquals(0, fixture.poll(START));
            fixture.reviews = true;
            fixture.discussions = true;
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(60)));
            assertTrue(fixture.searches.isEmpty());
            assertEquals(
                    6,
                    fixture.paths.stream()
                            .filter(it -> it.startsWith("/repos/"))
                            .count());
            assertTrue(fixture.graphql.stream().anyMatch(it -> it.query().startsWith("query ReviewDiscovery")));
            assertTrue(fixture.graphql.stream().anyMatch(it -> it.query().startsWith("query DiscussionDiscovery")));
        }
    }

    @Test
    void disablingSearchRetainsDirectPollingOnEveryInvocation() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.searchEnabled = false;
            assertEquals(0, fixture.poll(START));
            fixture.clearCalls();
            assertEquals(0, fixture.poll(START.plusSeconds(60)));
            assertEquals(6, fixture.paths.size());
            assertTrue(fixture.searches.isEmpty());
        }
    }

    private static Map<String, Object> candidate(final String type, final String repository, final int number) {
        return Map.of(
                "__typename",
                type,
                "repository",
                Map.of("nameWithOwner", repository),
                "number",
                number,
                "createdAt",
                START.minusSeconds(86400).toString());
    }

    private static Map<String, Object> item(final int id, final int actor, final String body, final int number) {
        return Map.of(
                "id",
                id,
                "number",
                number,
                "user",
                Map.of("id", actor, "login", "human"),
                "body",
                body,
                "html_url",
                "https://github.com/owner/repo/issues/" + number + "#issuecomment-" + id,
                "issue_url",
                "https://api.github.com/repos/owner/repo/issues/" + number,
                "updated_at",
                START.toString());
    }

    private static Map<String, Object> review(final int id, final String body) {
        return Map.of(
                "databaseId",
                id,
                "body",
                body,
                "submittedAt",
                START.toString(),
                "state",
                "COMMENTED",
                "url",
                "https://github.com/owner/repo/pull/12#pullrequestreview-" + id,
                "author",
                Map.of("__typename", "User", "login", "human", "databaseId", 42));
    }

    private static Map<String, Object> connection(final List<?> nodes, final String next) {
        final var info = new LinkedHashMap<String, Object>();
        info.put("hasNextPage", next != null);
        info.put("endCursor", next);
        return Map.of("nodes", nodes, "pageInfo", info);
    }

    private record SearchCall(boolean discussions, String query, String after) {}

    private record GraphqlCall(String query, Map<String, Object> variables) {}

    private class Fixture implements AutoCloseable {
        private final JsonMapper json = BotJsonMapper.create(ignored -> Optional.empty());
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<SearchCall> searches = new CopyOnWriteArrayList<>();
        private final List<GraphqlCall> graphql = new CopyOnWriteArrayList<>();
        private final Map<String, List<Map<String, Object>>> feedItems = new ConcurrentHashMap<>();
        private volatile List<Map<String, Object>> issueCandidates = List.of();
        private volatile List<Map<String, Object>> discussionCandidates = List.of();
        private volatile String searchNext;
        private volatile String failPath = "";
        private volatile String failOperation = "";
        private volatile boolean paginateIssues;
        private volatile boolean paginateReviews;
        private volatile boolean showReviewPull;
        private boolean reviews;
        private boolean discussions;
        private boolean searchEnabled = true;
        private int parallelism = 4;
        private String selected = "owner/repo,owner/other";
        private TaskStore store;

        private Fixture() throws IOException {
            server.setExecutor(handlers);
            server.createContext("/", this::handle);
            server.start();
        }

        // Each invocation reopens transport and state, like a fresh polling process.
        private int poll(final Instant instant) {
            final var conf = new GithubBotConfiguration(
                    home.toString(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "bot",
                    0,
                    new GithubBotConfiguration.Auth(
                            GithubBotConfiguration.AuthType.pat, "GH_TOKEN", "", 0, "", "GH_AGENT_TOKEN"),
                    new GithubBotConfiguration.Authorization("42", "", false, 7200, 4096),
                    new GithubBotConfiguration.Repositories(
                            selected, "", "", "", List.of(RepositorySelector.Visibility.values()), false, false));
            store = new TaskStore(conf, json);
            final var options = new PollOptions(
                    "now",
                    1,
                    new PollOptions.Reviews(reviews),
                    new PollOptions.Discussions(discussions),
                    parallelism,
                    new PollOptions.Search(searchEnabled, 3600));
            try (final var github = new GitHubClient(
                    URI.create(conf.api()), json, () -> "test", Clock.fixed(instant, ZoneOffset.UTC))) {
                return BotTestConfiguration.poller(conf, store, github, Clock.fixed(instant, ZoneOffset.UTC), null)
                        .poll(options);
            }
        }

        private void clearCalls() {
            paths.clear();
            searches.clear();
            graphql.clear();
        }

        private void handle(final HttpExchange exchange) throws IOException {
            try {
                final var path = exchange.getRequestURI().getPath();
                paths.add(path);
                if (path.equals(failPath)) {
                    respond(exchange, 400, Map.of("message", "fixture feed failure"));
                    return;
                }
                if ("/graphql".equals(path)) {
                    final var request = TaskStore.map(json.fromString(
                            Object.class,
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    final var query = String.valueOf(request.get("query"));
                    final var variables = TaskStore.map(request.get("variables"));
                    graphql.add(new GraphqlCall(query, variables));
                    if (!failOperation.isEmpty() && query.startsWith(failOperation)) {
                        respond(exchange, 400, Map.of("message", "fixture optional collector failure"));
                        return;
                    }
                    respond(exchange, 200, graphql(query, variables));
                    return;
                }
                if (paginateIssues && "/repos/owner/repo/issues".equals(path)) {
                    exchange.getResponseHeaders()
                            .set(
                                    "Link",
                                    "<http://127.0.0.1:" + server.getAddress().getPort() + "/continued>; rel=\"next\"");
                }
                respond(exchange, 200, feedItems.getOrDefault(path, List.of()));
            } finally {
                exchange.close();
            }
        }

        private Map<String, Object> graphql(final String query, final Map<String, Object> variables) {
            if (query.startsWith("query MentionCandidates")) {
                final var discussion = query.contains("type:DISCUSSION");
                final var after = (String) variables.get("after");
                searches.add(new SearchCall(discussion, (String) variables.get("query"), after));
                final var nodes = after == null ? (discussion ? discussionCandidates : issueCandidates) : List.of();
                final var result =
                        new LinkedHashMap<>(connection(nodes, after == null && !nodes.isEmpty() ? searchNext : null));
                result.put(discussion ? "discussionCount" : "issueCount", nodes.size());
                return Map.of("data", Map.of("search", result));
            }
            if (query.startsWith("query ReviewDiscovery")) {
                final var data = new LinkedHashMap<String, Object>();
                for (int i = 0; variables.containsKey("owner" + i); i++) {
                    final var pulls = showReviewPull && "repo".equals(variables.get("name" + i))
                            ? List.of(Map.of(
                                    "number",
                                    12,
                                    "state",
                                    "OPEN",
                                    "reviews",
                                    connection(
                                            List.of(review(300, "ordinary review")),
                                            paginateReviews ? "review-next" : null)))
                            : List.of();
                    data.put(
                            "r" + i,
                            Map.of(
                                    "nameWithOwner",
                                    variables.get("owner" + i) + "/" + variables.get("name" + i),
                                    "pullRequests",
                                    connection(pulls, null)));
                }
                return Map.of("data", data);
            }
            if (query.startsWith("query ReviewContinuation")) {
                return Map.of(
                        "data",
                        Map.of(
                                "repository",
                                Map.of(
                                        "nameWithOwner",
                                        "owner/repo",
                                        "pullRequest",
                                        Map.of(
                                                "number",
                                                12,
                                                "state",
                                                "OPEN",
                                                "reviews",
                                                connection(List.of(review(301, "@bot solve nested review")), null)))));
            }
            if (query.startsWith("query DiscussionCapability")) {
                return Map.of("data", Map.of("repository", Map.of("hasDiscussionsEnabled", true)));
            }
            if (query.startsWith("query DiscussionDiscovery")) {
                return Map.of("data", Map.of("repository", Map.of("discussions", connection(List.of(), null))));
            }
            throw new IllegalStateException("Unexpected GraphQL operation: " + query);
        }

        private void respond(final HttpExchange exchange, final int status, final Object body) throws IOException {
            final var bytes = json.toString(body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            server.stop(0);
            handlers.close();
            json.close();
        }
    }
}
