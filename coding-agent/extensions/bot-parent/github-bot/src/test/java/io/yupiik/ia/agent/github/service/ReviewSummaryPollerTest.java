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
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.command.PollCommand;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.PollOptions;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD) // The class owns one stable configuration and its fixture paths/transport.
@FusionSupport(modules = ReviewSummaryPollerTest.Source.class)
class ReviewSummaryPollerTest {
    @Fusion
    private GithubBotConfiguration injectedConfiguration;

    @Fusion
    private PollCommand.Options injectedOptions;

    private static HttpServer apiServer;
    private static ExecutorService apiThreads;

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    @TempDir
    Path home;

    @Test
    void closedPullsAndLegacyCatalogAreNotFetchedAcrossRestart() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12), Map.of("number", 13, "state", "closed"));
            fixture.reviews.put(
                    12,
                    List.of(changed(1, "submitted_at", NOW.minusSeconds(3600).toString())));
            fixture.reviews.put(13, List.of(changed(2, "html_url", reference(13, 2))));
            fixture.store.transaction(state -> {
                state.cursors()
                        .put(
                                "owner/repo/review-summary-pulls:next",
                                fixture.api() + "/repos/owner/repo/pulls?state=all&per_page=100&page=2");
                state.cursors().put("owner/repo/pulls/12/reviews:known", "true");
                state.cursors().put("owner/repo/pulls/13/reviews:known", "true");
                state.cursors().put("owner/repo/review-summary-open-pulls:next", "/obsolete-rest-discovery");
                state.cursors().put("owner/repo/pulls/12/reviews:next", "/obsolete-rest-reviews");
                return null;
            });
            final var conf = fixture.conf(Map.of("pageBudget", 20));
            assertEquals(0, fixture.poll(conf));
            final var firstRequests = List.copyOf(fixture.requests);
            assertEquals(1, firstRequests.size(), "Open pulls and their first review pages share one request");
            assertTrue(fixture.queries.getFirst().contains("states:[OPEN]"));
            assertTrue(firstRequests.stream().allMatch("/graphql"::equals));
            fixture.requests.clear();
            fixture.queries.clear();

            try (final var github = new GitHubClient(
                    URI.create(fixture.api()), fixture.json, () -> "test", BotTestConfiguration.CLOCK)) {
                assertEquals(
                        0,
                        BotTestConfiguration.reviewSummaryPoller(
                                        conf.shared(),
                                        new TaskStore(BotTestConfiguration.home(home), fixture.json),
                                        github,
                                        fixed(NOW.plusSeconds(86400)),
                                        null)
                                .poll(List.of("owner/repo"), conf.poll()));
            }
            assertEquals(firstRequests, fixture.requests);
            assertTrue(fixture.store.<Boolean>transaction(state -> state.cursors().keySet().stream()
                    .noneMatch(key ->
                            key.endsWith("/reviews:known") || key.equals("owner/repo/review-summary-pulls:next"))));
            assertFalse(fixture.store.<Boolean>transaction(
                    state -> state.cursors().containsKey("owner/repo/review-summary-open-pulls:next")));
            assertFalse(fixture.store.<Boolean>transaction(
                    state -> state.cursors().containsKey("owner/repo/pulls/12/reviews:next")));
            assertEquals(
                    NOW.toString(),
                    fixture.store.transaction(state -> state.cursors().get("initialSince")));
        }
    }

    @Test
    void closedPullsDoNotAccumulateRequestsAndReopenedPullsBecomeEligibleAgain() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12), Map.of("number", 13), Map.of("number", 14));
            final var conf = fixture.conf(Map.of("pageBudget", 1));
            for (int page = 0; page < 3; page++) {
                fixture.requests.clear();
                fixture.queries.clear();
                assertEquals(0, fixture.poll(conf));
                assertEquals(1, fixture.requests.size(), "Only PRs on the current open discovery page are scanned");
            }
            fixture.pulls = List.of(
                    Map.of("number", 12, "state", "closed"),
                    Map.of("number", 13, "state", "closed"),
                    Map.of("number", 14, "state", "closed"));
            fixture.requests.clear();
            fixture.queries.clear();
            assertEquals(0, fixture.poll(conf));
            assertEquals(1, fixture.requests.size(), "No review calls remain once all PRs close");
            fixture.pulls = List.of(Map.of("number", 13));
            fixture.reviews.put(13, List.of(changed(1, "html_url", reference(13, 1))));
            fixture.requests.clear();
            fixture.queries.clear();
            assertEquals(1, fixture.poll(conf));
            assertEquals(1, fixture.requests.size());
            assertEquals(0, fixture.poll(conf), "Reopening must not bypass invocation receipts");
        }
    }

    @Test
    void skipsClosedOrUnknownStateEvenIfOpenListingReturnsThem() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.returnClosedPulls = true;
            fixture.pulls = List.of(
                    Map.of("number", 12, "state", "closed"),
                    Map.of("number", 13, "state", "unknown"),
                    Map.of("number", 14));
            fixture.reviews.put(12, List.of(review(1)));
            fixture.reviews.put(14, List.of(changed(2, "html_url", reference(14, 2))));
            assertEquals(1, fixture.poll(fixture.conf(Map.of("pageBudget", 20))));
            assertEquals(3, fixture.requests.size(), "Three discovery pages include their eligible review pages");
            assertTrue(fixture.requests.stream()
                    .noneMatch(path -> path.contains("/pulls/12/") || path.contains("/pulls/13/")));
        }
    }

    @Test
    void overlapsOpenReviewRequestsAcrossRepositoriesWithinConfiguredBound() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviewOverlap = new CountDownLatch(2);
            final var conf = fixture.conf(Map.of("parallelism", 2));
            assertEquals(
                    0,
                    BotTestConfiguration.reviewSummaryPoller(
                                    conf.shared(), fixture.store, fixture.github, fixed(NOW), null)
                            .poll(
                                    IntStream.range(0, 21)
                                            .mapToObj(i -> "owner/repo" + i)
                                            .toList(),
                                    conf.poll()));
            assertFalse(fixture.overlapTimedOut.get(), "Independent review batches did not overlap");
            assertEquals(2, fixture.maximumReviews.get());
            assertEquals(3, fixture.requests.size());
        }
    }

    @Test
    void defaultOptOutAndFailClosedValidationNeverCallNetwork() throws Exception {
        try (final var fixture = new Fixture()) {
            final var defaults = fixture.conf(Map.of("pollReviews", false));
            assertFalse(defaults.poll().reviews().enabled());
            assertEquals(0, fixture.poll(defaults));
            assertThrows(IllegalStateException.class, () -> fixture.poll(fixture.conf(Map.of("userIds", ""))));
            assertThrows(IllegalArgumentException.class, () -> fixture.poll(fixture.conf(Map.of("pageBudget", 0))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> BotTestConfiguration.reviewSummaryPoller(
                                    fixture.conf(Map.of()).shared(), fixture.store, fixture.github, fixed(NOW), null)
                            .poll(
                                    List.of("owner/repo/../other"),
                                    fixture.conf(Map.of()).poll()));
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void onlySubmittedAuthorizedHumanMentionsEnqueueAndReceiptsSurviveTaskDeletion() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12, "state", "open"));
            final var missingTime = review(2);
            missingTime.remove("submitted_at");
            fixture.reviews.put(
                    12,
                    List.of(
                            review(1),
                            missingTime,
                            changed(3, "state", "PENDING"),
                            changed(4, "submitted_at", NOW.minusSeconds(301).toString()),
                            changed(5, "user", Map.of("id", 77, "login", "human", "type", "User")),
                            changed(6, "user", Map.of("id", 42, "login", "BoT", "type", "User")),
                            changed(7, "user", Map.of("id", 42, "login", "human", "type", "Bot")),
                            changed(8, "body", "No invocation"),
                            changed(9, "submitted_at", NOW.minusSeconds(300).toString()),
                            changed(10, "user", null),
                            changed(11, "state", "DRAFT"),
                            changed(12, "body", "<!-- yca-task:previous-task:review -->\n@bot review follow up")));
            assertEquals(2, fixture.poll(fixture.conf(Map.of("pageBudget", 20))));
            final var tasks = fixture.store.transaction(
                    state -> state.list().stream().map(TaskFixtures::map).toList());
            assertEquals(2, tasks.size());
            final var task = tasks.getFirst();
            assertEquals("owner/repo", task.get("repository"));
            assertEquals(12, ((Number) task.get("number")).intValue());
            assertEquals("review", task.get("kind"));
            assertEquals("check this", task.get("request"));
            assertEquals("https://github.com/owner/repo/pull/12#pullrequestreview-1", task.get("reference"));
            assertEquals("42", String.valueOf(task.get("actorId")));
            fixture.store.transaction(state -> {
                assertTrue(state.receipts().containsKey("owner/repo/reviews/1"));
                state.tasks().clear();
                return null;
            });
            // New store/client/poller instances exercise durable state, not an in-memory deduplication set.
            try (final var github = new GitHubClient(
                    URI.create(fixture.api()), fixture.json, () -> "test", BotTestConfiguration.CLOCK)) {
                assertEquals(
                        0,
                        BotTestConfiguration.reviewSummaryPoller(
                                        fixture.conf(Map.of("pageBudget", 20)).shared(),
                                        new TaskStore(BotTestConfiguration.home(home), fixture.json),
                                        github,
                                        fixed(NOW),
                                        null)
                                .poll(
                                        List.of("owner/repo"),
                                        fixture.conf(Map.of("pageBudget", 20)).poll()));
            }
            assertTrue(fixture.store
                    .transaction(state ->
                            state.list().stream().map(TaskFixtures::map).toList())
                    .isEmpty());
            assertTrue(fixture.queries.getFirst().contains("states:[OPEN]"));
            assertTrue(fixture.queries.getFirst().contains("orderBy:{field:UPDATED_AT,direction:DESC}"));
        }
    }

    @Test
    void auditsEnqueueOnceAcrossReceiptReplayAndOnlyVisibleDenials() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(
                    12,
                    List.of(
                            review(1),
                            changed(2, "user", Map.of("id", 77, "login", "human", "type", "User")),
                            changed(3, "body", "ordinary sensitive nonmention"),
                            changed(4, "body", "`@bot review hidden mention`")));
            final var conf = fixture.conf(Map.of("pageBudget", 20));
            final var audit = new BotAudit(BotTestConfiguration.home(home), fixture.json, BotTestConfiguration.CLOCK);
            // Poller must pass the same sink into its internally created review-summary poller.
            assertEquals(
                    1,
                    BotTestConfiguration.poller(conf.shared(), fixture.store, fixture.github, fixed(NOW), audit)
                            .poll(conf.poll()));
            final var id = fixture.store
                    .transaction(state ->
                            state.list().stream().map(TaskFixtures::map).toList())
                    .getFirst()
                    .get("id");
            fixture.store.transaction(state -> {
                state.tasks().clear();
                return null;
            });
            assertEquals(
                    0,
                    BotTestConfiguration.reviewSummaryPoller(
                                    conf.shared(),
                                    new TaskStore(BotTestConfiguration.home(home), fixture.json),
                                    fixture.github,
                                    fixed(NOW),
                                    audit)
                            .poll(List.of("owner/repo"), conf.poll()));
            final var events = Files.readAllLines(home.resolve("audit.jsonl")).stream()
                    .map(line -> TaskStore.map(fixture.json.fromString(Object.class, line)))
                    .toList();
            final var enqueued = events.stream()
                    .filter(event -> "enqueued".equals(event.get("event")))
                    .toList();
            assertEquals(1, enqueued.size());
            assertEquals(id, enqueued.getFirst().get("id"));
            assertEquals("42", String.valueOf(enqueued.getFirst().get("actorId")));
            assertEquals("owner/repo", enqueued.getFirst().get("repository"));
            assertEquals(12, ((Number) enqueued.getFirst().get("number")).intValue());
            assertEquals(
                    Set.of("at", "event", "id", "actorId", "repository", "number"),
                    enqueued.getFirst().keySet());
            final var denied = events.stream()
                    .filter(event -> "auth-denied".equals(event.get("event")))
                    .toList();
            assertEquals(2, denied.size());
            for (final var event : denied) {
                assertEquals(Set.of("at", "event", "actorId", "repository", "number"), event.keySet());
                assertEquals("77", String.valueOf(event.get("actorId")));
            }
            assertEquals(3, events.size());
        }
    }

    @Test
    void resumesIndependentLargePullAndReviewBacklogsWithStrictPerEndpointBudget() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12), Map.of("number", 13), Map.of("number", 14));
            fixture.reviews.put(12, List.of(review(1), review(2), review(3), review(4)));
            fixture.reviews.put(13, List.of(changed(5, "html_url", reference(13, 5))));
            fixture.reviews.put(14, List.of(changed(6, "html_url", reference(14, 6))));
            final var conf = fixture.conf(Map.of("pageBudget", 1));
            for (int scan = 0; scan < 10; scan++) {
                fixture.requests.clear();
                fixture.queries.clear();
                fixture.poll(conf);
                assertTrue(fixture.requests.size() <= 2, "One discovery page, with at most one resumed review page");
                assertEquals(
                        1,
                        fixture.queries.stream()
                                .filter(query -> query.contains("pullRequests("))
                                .count());
            }
            assertEquals(
                    6,
                    fixture.store
                            .transaction(state ->
                                    state.list().stream().map(TaskFixtures::map).toList())
                            .size());
            // A conservative new traversal finds entries moved to earlier offsets.
            fixture.pulls =
                    List.of(Map.of("number", 15), Map.of("number", 12), Map.of("number", 13), Map.of("number", 14));
            fixture.reviews.put(15, List.of(changed(7, "html_url", reference(15, 7))));
            fixture.reviews.put(12, List.of(review(8), review(1), review(2), review(3), review(4)));
            for (int i = 0; i < 10; i++) {
                fixture.poll(conf);
            }
            assertEquals(
                    8,
                    fixture.store
                            .transaction(state ->
                                    state.list().stream().map(TaskFixtures::map).toList())
                            .size());
            assertEquals(
                    NOW.toString(),
                    fixture.store.transaction(state -> state.cursors().get("initialSince")));
        }
    }

    @Test
    void revisitsPendingReviewsAfterSubmission() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(12, List.of(changed(1, "state", "PENDING")));
            assertEquals(0, fixture.poll(fixture.conf(Map.of())));
            fixture.reviews.put(12, List.of(review(1)));
            assertEquals(1, fixture.poll(fixture.conf(Map.of())));
        }
    }

    @Test
    void failuresDoNotLoseDiscoveryOrAdvanceFailedReviewPage() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12), Map.of("number", 13));
            fixture.reviews.put(12, List.of(review(1), review(2), review(3)));
            fixture.reviews.put(13, List.of(changed(4, "html_url", reference(13, 4))));
            final var conf = fixture.conf(Map.of());
            assertEquals(1, fixture.poll(conf));
            final var continuation = fixture.store.transaction(state -> Map.copyOf(state.cursors()));
            fixture.failReview.set(true);
            assertThrows(GitHubClient.HttpException.class, () -> fixture.poll(conf));
            assertEquals(continuation, fixture.store.transaction(state -> state.cursors()));
            fixture.failReview.set(false);
            assertEquals(1, fixture.poll(conf));
            assertEquals(1, fixture.poll(conf));
            fixture.poll(conf);
            fixture.poll(conf);
            assertEquals(
                    4,
                    fixture.store
                            .transaction(state ->
                                    state.list().stream().map(TaskFixtures::map).toList())
                            .size());
        }
    }

    @Test
    void initialSinceIsPersistedAcrossRestartsAndExplicitBackfillIsHonored() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(
                    12,
                    List.of(changed(1, "submitted_at", NOW.minusSeconds(3600).toString())));
            final var initial = NOW.minusSeconds(3600).toString();
            assertEquals(1, fixture.poll(fixture.conf(Map.of("since", initial))));
            fixture.reviews.put(
                    12,
                    List.of(changed(2, "submitted_at", NOW.minusSeconds(3600).toString())));
            assertEquals(
                    1,
                    BotTestConfiguration.reviewSummaryPoller(
                                    fixture.conf(Map.of("since", "now")).shared(),
                                    new TaskStore(BotTestConfiguration.home(home), fixture.json),
                                    fixture.github,
                                    fixed(NOW.plusSeconds(86400)),
                                    null)
                            .poll(
                                    List.of("owner/repo"),
                                    fixture.conf(Map.of("since", "now")).poll()));
            assertEquals(
                    initial, fixture.store.transaction(state -> state.cursors().get("initialSince")));
        }
    }

    @Test
    void nowIsCapturedOnceEvenWhenTheFirstTraversalHasNoPulls() throws Exception {
        try (final var fixture = new Fixture()) {
            assertEquals(0, fixture.poll(fixture.conf(Map.of())));
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(12, List.of(review(1)));
            assertEquals(
                    1,
                    BotTestConfiguration.reviewSummaryPoller(
                                    fixture.conf(Map.of()).shared(),
                                    fixture.store,
                                    fixture.github,
                                    fixed(NOW.plusSeconds(86400)),
                                    null)
                            .poll(List.of("owner/repo"), fixture.conf(Map.of()).poll()));
            assertEquals(
                    NOW.toString(),
                    fixture.store.transaction(state -> state.cursors().get("initialSince")));
        }
    }

    @Test
    void rejectsReferencesOutsideThePolledPullAndRepository() throws Exception {
        for (final var ref : List.of(
                "https://evil.example/owner/repo/pull/12#pullrequestreview-1",
                "http://github.com/owner/repo/pull/12#pullrequestreview-1",
                "https://github.com/other/repo/pull/12#pullrequestreview-1",
                "https://github.com/owner/repo/pull/13#pullrequestreview-1",
                "https://github.com/owner/repo/issues/12#pullrequestreview-1",
                "https://github.com/owner/repo/pull/12#pullrequestreview-2",
                "https://github.com/owner/repo/pull/12#issuecomment-1",
                "https://github.com/owner/repo/pull/12/extra#pullrequestreview-1")) {
            try (final var fixture = new Fixture()) {
                fixture.pulls = List.of(Map.of("number", 12));
                fixture.reviews.put(12, List.of(changed(1, "html_url", ref)));
                assertThrows(IllegalStateException.class, () -> fixture.poll(fixture.conf(Map.of())));
                assertTrue(fixture.store
                        .transaction(state ->
                                state.list().stream().map(TaskFixtures::map).toList())
                        .isEmpty());
            }
        }
    }

    @Test
    void teamRestrictionUsesImmutableIdentityAndFailsClosed() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(12, List.of(review(1)));
            final var conf = fixture.conf(Map.of("userIds", "", "teams", "owner/team", "allowRepositoryWriters", true));
            fixture.membership = "pending";
            assertEquals(
                    0,
                    BotTestConfiguration.reviewSummaryPoller(
                                    conf.shared(),
                                    fixture.store,
                                    fixture.github,
                                    fixed(NOW),
                                    new BotAudit(
                                            BotTestConfiguration.home(home), fixture.json, BotTestConfiguration.CLOCK))
                            .poll(List.of("owner/repo"), conf.poll()));
            final var events = Files.readAllLines(home.resolve("audit.jsonl"));
            assertEquals(1, events.size());
            final var denied = TaskStore.map(fixture.json.fromString(Object.class, events.getFirst()));
            assertEquals("auth-denied", denied.get("event"));
            assertEquals(Set.of("at", "event", "actorId", "repository", "number"), denied.keySet());
            assertEquals("42", String.valueOf(denied.get("actorId")));
            fixture.membership = "active";
            fixture.resolvedLogin = "renamed";
            assertEquals(0, fixture.poll(conf));
            fixture.resolvedLogin = "human";
            fixture.failTeam.set(true);
            assertEquals(0, fixture.poll(conf), "Missing token capability must deny without throwing");
            assertTrue(fixture.store
                    .transaction(state ->
                            state.list().stream().map(TaskFixtures::map).toList())
                    .isEmpty());
            fixture.failTeam.set(false);
            assertEquals(1, fixture.poll(conf));
            assertTrue(fixture.requests.contains("/user/42"));
            assertTrue(fixture.requests.contains("/orgs/owner/teams/team/memberships/human"));
        }
    }

    @Test
    void batchesFortySevenRepositoriesIntoFiveRequestsIncludingReviewBodies() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pullPageSize = 10;
            fixture.reviewPageSize = 100;
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(12, List.of(changed(1, "body", "ordinary review")));
            final var conf = fixture.conf(Map.of("parallelism", 1));
            assertEquals(
                    0,
                    BotTestConfiguration.reviewSummaryPoller(
                                    conf.shared(), fixture.store, fixture.github, fixed(NOW), null)
                            .poll(
                                    IntStream.range(0, 47)
                                            .mapToObj(i -> "owner/repo" + i)
                                            .toList(),
                                    conf.poll()));
            assertEquals(5, fixture.requests.size());
            assertTrue(fixture.requests.stream().allMatch("/graphql"::equals));
            assertTrue(fixture.queries.stream().allMatch(query -> query.contains("reviews(first:100)")));
        }
    }

    @Test
    void partialDataAndMalformedLateAliasLeaveCursorsAndTasksUntouched() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12), Map.of("number", 13));
            fixture.reviews.put(12, List.of(review(1), review(2)));
            final var conf = fixture.conf(Map.of());
            assertEquals(1, fixture.poll(conf));
            final var before = Files.readString(home.resolve("state.json"));
            fixture.partialErrors = true;
            assertThrows(IllegalStateException.class, () -> fixture.poll(conf));
            assertEquals(before, Files.readString(home.resolve("state.json")));
            fixture.partialErrors = false;
            fixture.malformedSecondAlias = true;
            assertThrows(
                    IllegalStateException.class,
                    () -> BotTestConfiguration.reviewSummaryPoller(
                                    conf.shared(), fixture.store, fixture.github, fixed(NOW), null)
                            .poll(List.of("owner/repo", "owner/other"), conf.poll()));
            assertEquals(before, Files.readString(home.resolve("state.json")));
            fixture.malformedSecondAlias = false;
            fixture.poll(conf);
            assertEquals(1, fixture.poll(conf), "Failed batch must not lose the saved review backlog");
            assertEquals(
                    2, fixture.store.<Integer>transaction(state -> state.list().size()));
        }
    }

    @Test
    void failedNestedPageKeepsDiscoveryEligibleAndResumesAfterRestartWithoutDuplicates() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(12, List.of(review(1), review(2), review(3)));
            fixture.failReviewPage = true;
            final var conf = fixture.conf(Map.of("pageBudget", 20));
            assertThrows(GitHubClient.HttpException.class, () -> fixture.poll(conf));
            assertEquals(2, fixture.requests.size(), "GraphQL POST must not be automatically replayed");
            assertEquals(
                    1, fixture.store.<Integer>transaction(state -> state.list().size()));
            final var cursors = fixture.store.transaction(state -> Map.copyOf(state.cursors()));
            assertFalse(cursors.containsKey("owner/repo/review-summary-graphql/pulls:next"));
            assertEquals("cursor-1", cursors.get("owner/repo/review-summary-graphql/pulls/12/reviews:next"));
            fixture.failReviewPage = false;
            try (final var github = new GitHubClient(
                    URI.create(fixture.api()), fixture.json, () -> "test", BotTestConfiguration.CLOCK)) {
                assertEquals(
                        2,
                        BotTestConfiguration.reviewSummaryPoller(
                                        conf.shared(),
                                        new TaskStore(BotTestConfiguration.home(home), fixture.json),
                                        github,
                                        fixed(NOW),
                                        null)
                                .poll(List.of("owner/repo"), conf.poll()));
            }
            assertEquals(
                    3, fixture.store.<Integer>transaction(state -> state.list().size()));
            assertEquals(0, fixture.poll(conf));
        }
    }

    @Test
    void closureDuringReviewContinuationStopsScanningUntilReopened() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(12, List.of(review(1), review(2)));
            fixture.closeDuringReview = true;
            final var conf = fixture.conf(Map.of("pageBudget", 20));
            assertEquals(1, fixture.poll(conf));
            assertEquals(2, fixture.requests.size());
            assertFalse(fixture.store.<Boolean>transaction(
                    state -> state.cursors().containsKey("owner/repo/review-summary-graphql/pulls/12/reviews:next")));
            fixture.closeDuringReview = false;
            assertEquals(1, fixture.poll(conf));
            assertEquals(0, fixture.poll(conf));
        }
    }

    @Test
    void completedTraversalDetectsMentionsEditedIntoExistingReviewBodies() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(12, List.of(changed(1, "body", "ordinary review")));
            assertEquals(0, fixture.poll(fixture.conf(Map.of())));
            fixture.reviews.put(12, List.of(review(1)));
            assertEquals(1, fixture.poll(fixture.conf(Map.of())));
            assertEquals(0, fixture.poll(fixture.conf(Map.of())));
        }
    }

    @Test
    void nativeReviewFixRequiresExplicitOptIn() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(12, List.of(changed(1, "body", "@bot fix review findings")));
            final var conf = fixture.conf(Map.of());
            assertEquals(0, fixture.poll(conf));
            assertTrue(fixture.store.transaction(state -> state.list()).isEmpty());
            final var enabled = new ReviewSummaryPoller(
                    conf.shared(),
                    fixture.store,
                    fixture.github,
                    fixed(NOW),
                    null,
                    new TeamAuthorization(fixture.github, conf.shared(), fixed(NOW)),
                    new FixConfiguration(true, "human/fork"),
                    new MentionParser());
            fixture.failFixMetadata = true;
            assertThrows(GitHubClient.HttpException.class, () -> enabled.poll(List.of("owner/repo"), conf.poll()));
            assertTrue(fixture.store.transaction(state -> state.list()).isEmpty());
            assertTrue(fixture.store.transaction(state -> state.receipts()).isEmpty());
            assertFalse(fixture.store
                    .transaction(state -> state.cursors())
                    .containsKey("owner/repo/review-summary-graphql/pulls/12/reviews:next"));
            fixture.failFixMetadata = false;
            assertEquals(1, enabled.poll(List.of("owner/repo"), conf.poll()));
            final var task = fixture.store.transaction(state -> state.list().getFirst());
            assertEquals("review_summary", task.sourceType());
            assertEquals("42", task.actorId());
            assertEquals(
                    "a".repeat(40),
                    TaskStore.map(task.recovery().get("fixTarget")).get("headSha"));
            final var pinnedRequests = fixture.requests.stream()
                    .filter(path -> !path.equals("/graphql"))
                    .count();
            assertEquals(
                    TaskKind.fix,
                    fixture.store.transaction(state -> state.list().getFirst().kind()));
            fixture.failFixMetadata = true;
            assertEquals(0, enabled.poll(List.of("owner/repo"), conf.poll()));
            assertEquals(
                    pinnedRequests,
                    fixture.requests.stream()
                            .filter(path -> !path.equals("/graphql"))
                            .count(),
                    "An existing receipt must not resolve a new head");
        }
    }

    private static Clock fixed(final Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static String reference(final int number, final int id) {
        return "https://github.com/owner/repo/pull/" + number + "#pullrequestreview-" + id;
    }

    private static Map<String, Object> review(final int id) {
        return new LinkedHashMap<>(Map.of(
                "id",
                id,
                "state",
                "COMMENTED",
                "submitted_at",
                NOW.toString(),
                "user",
                Map.of("id", 42, "login", "human", "type", "User"),
                "body",
                "@bot review check this",
                "html_url",
                reference(12, id)));
    }

    private static Map<String, Object> changed(final int id, final String key, final Object value) {
        final var review = review(id);
        review.put(key, value);
        return review;
    }

    private record Settings(GithubBotConfiguration shared, PollOptions poll) {}

    private final class Fixture implements AutoCloseable {
        private final JsonMapper json = BotJsonMapper.create(key -> Optional.empty());
        private final HttpServer server;
        private final AtomicInteger activeReviews = new AtomicInteger();
        private final AtomicInteger maximumReviews = new AtomicInteger();
        private final AtomicBoolean overlapTimedOut = new AtomicBoolean();
        private CountDownLatch reviewOverlap;
        private final GitHubClient github;
        private final TaskStore store = new TaskStore(BotTestConfiguration.home(home), json);
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final List<String> queries = new CopyOnWriteArrayList<>();
        private final Map<Integer, List<Map<String, Object>>> reviews = new LinkedHashMap<>();
        private final AtomicBoolean failReview = new AtomicBoolean();
        private boolean failReviewPage;
        private boolean failFixMetadata;
        private boolean partialErrors;
        private boolean closeDuringReview;
        private boolean malformedSecondAlias;
        private int pullPageSize = 1;
        private int reviewPageSize = 1;
        private final AtomicBoolean failTeam = new AtomicBoolean();
        private boolean returnClosedPulls;
        private List<Map<String, Object>> pulls = List.of();
        private String membership = "active";
        private String resolvedLogin = "human";

        private Fixture() throws IOException {
            server = apiServer;
            server.createContext("/", this::respond);
            github = new GitHubClient(URI.create(api()), json, () -> "test", BotTestConfiguration.CLOCK);
        }

        private String api() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private int poll(final Settings conf) {
            return BotTestConfiguration.reviewSummaryPoller(conf.shared(), store, github, fixed(NOW), null)
                    .poll(List.of("owner/repo"), conf.poll());
        }

        private Settings conf(final Map<String, Object> overrides) {
            final var baseline = injectedConfiguration;
            final var authorization = baseline.authorization();
            final var poll = injectedOptions.poll();
            return new Settings(
                    new GithubBotConfiguration(
                            home.toString(),
                            api(),
                            baseline.login(),
                            baseline.requestIntervalMillis(),
                            baseline.auth(),
                            new GithubBotConfiguration.Authorization(
                                    (String) overrides.getOrDefault("userIds", authorization.userIds()),
                                    (String) overrides.getOrDefault("teams", authorization.teams()),
                                    (boolean) overrides.getOrDefault(
                                            "allowRepositoryWriters", authorization.allowRepositoryWriters()),
                                    authorization.permissionsCacheTtlSeconds(),
                                    authorization.permissionsCacheMaxEntries()),
                            baseline.repositories()),
                    new PollOptions(
                            (String) overrides.getOrDefault("since", poll.since()),
                            ((Number) overrides.getOrDefault("pageBudget", poll.pageBudget())).intValue(),
                            new PollOptions.Reviews((boolean) overrides.getOrDefault(
                                    "pollReviews", poll.reviews().enabled())),
                            poll.discussions(),
                            ((Number) overrides.getOrDefault("parallelism", poll.parallelism())).intValue(),
                            poll.search()));
        }

        private void respond(final HttpExchange exchange) throws IOException {
            try (exchange) {
                final var path = exchange.getRequestURI().getPath();
                requests.add(exchange.getRequestURI().toString());
                Object body;
                int status = 200;
                if (path.equals("/repos/owner/repo/pulls/12")
                        || path.equals("/repos/owner/repo")
                        || path.startsWith("/repos/human/fork")) {
                    status = failFixMetadata ? 403 : 200;
                    body = switch (path) {
                        case "/repos/owner/repo/pulls/12" -> BotTestConfiguration.fixPull("owner/repo", "a".repeat(40));
                        case "/repos/owner/repo" -> BotTestConfiguration.fixRepository("owner/repo", "BASE");
                        case "/repos/human/fork" -> BotTestConfiguration.fixRepository("human/fork", "HEAD");
                        default -> BotTestConfiguration.fixRef("a".repeat(40));
                    };
                } else if (path.equals("/user/42")) {
                    body = Map.of("id", 42, "login", resolvedLogin);
                } else if (path.contains("/collaborators/")) {
                    body = Map.of("permission", "write", "user", Map.of("id", 42, "login", resolvedLogin));
                } else if (path.startsWith("/orgs/")) {
                    status = failTeam.get() ? 403 : 200;
                    body = Map.of("state", membership);
                } else if (path.equals("/graphql")) {
                    overlap();
                    final var request = json.fromString(
                            GitHubClient.GraphqlRequest.class,
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    queries.add(request.query());
                    final var variables = TaskStore.map(request.variables());
                    final boolean discovery = request.query().contains("pullRequests(");
                    status = failReview.get() || (!discovery && failReviewPage) ? 403 : 200;
                    final var data = new LinkedHashMap<String, Object>();
                    if (discovery) {
                        for (int i = 0; variables.containsKey("owner" + i); i++) {
                            final var repo = variables.get("owner" + i) + "/" + variables.get("name" + i);
                            final var nodes = pulls.stream()
                                    .filter(pull ->
                                            returnClosedPulls || "open".equals(pull.getOrDefault("state", "open")))
                                    .map(pull -> graphPull(pull, null))
                                    .toList();
                            data.put(
                                    "r" + i,
                                    Map.of(
                                            "nameWithOwner",
                                            repo,
                                            "pullRequests",
                                            connection(nodes, (String) variables.get("after" + i), pullPageSize)));
                        }
                        if (malformedSecondAlias) {
                            data.put("r1", Map.of("nameWithOwner", "wrong/repository"));
                        }
                    } else {
                        final int number = ((Number) variables.get("number")).intValue();
                        final var pull = new LinkedHashMap<>(pulls.stream()
                                .filter(item -> ((Number) item.get("number")).intValue() == number)
                                .findFirst()
                                .orElseThrow());
                        if (closeDuringReview) {
                            pull.put("state", "closed");
                        }
                        data.put(
                                "repository",
                                Map.of(
                                        "nameWithOwner",
                                        variables.get("owner") + "/" + variables.get("name"),
                                        "pullRequest",
                                        graphPull(pull, (String) variables.get("after"))));
                    }
                    body = partialErrors
                            ? Map.of("data", data, "errors", List.of(Map.of("message", "private")))
                            : Map.of("data", data);
                } else {
                    // The ordinary issue and inline-comment feeds have no entries in this fixture.
                    body = List.of();
                }
                final var bytes = json.toString(body).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        private void overlap() throws IOException {
            if (reviewOverlap == null) {
                return;
            }
            maximumReviews.accumulateAndGet(activeReviews.incrementAndGet(), Math::max);
            try {
                reviewOverlap.countDown();
                if (!reviewOverlap.await(5, TimeUnit.SECONDS)) {
                    overlapTimedOut.set(true);
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            } finally {
                activeReviews.decrementAndGet();
            }
        }

        private Map<String, Object> graphPull(final Map<String, Object> pull, final String after) {
            final var number = ((Number) pull.get("number")).intValue();
            final var nodes = reviews.getOrDefault(number, List.of()).stream()
                    .map(item -> {
                        final var node = new LinkedHashMap<String, Object>();
                        node.put("databaseId", item.get("id"));
                        node.put("body", item.get("body"));
                        node.put("state", item.get("state"));
                        node.put("submittedAt", item.get("submitted_at"));
                        node.put("url", item.get("html_url"));
                        final var user = item.get("user");
                        node.put(
                                "author",
                                user == null
                                        ? null
                                        : Map.of(
                                                "__typename",
                                                        TaskStore.map(user).get("type"),
                                                "login", TaskStore.map(user).get("login"),
                                                "databaseId",
                                                        TaskStore.map(user).get("id")));
                        return node;
                    })
                    .toList();
            return Map.of(
                    "number",
                    number,
                    "state",
                    pull.getOrDefault("state", "open").toString().toUpperCase(Locale.ROOT),
                    "reviews",
                    connection(nodes, after, reviewPageSize));
        }

        private Map<String, Object> connection(final List<?> items, final String after, final int size) {
            final int offset = Math.min(after == null ? 0 : Integer.parseInt(after.substring(7)), items.size());
            final int end = Math.min(offset + size, items.size());
            final var info = new LinkedHashMap<String, Object>();
            info.put("hasNextPage", end < items.size());
            info.put("endCursor", end < items.size() ? "cursor-" + end : null);
            return Map.of("nodes", items.subList(offset, end), "pageInfo", info);
        }

        @Override
        public void close() {
            github.close();
            server.removeContext("/");
            json.close();
        }
    }

    @AfterAll
    static void stopApiServer() {
        if (apiServer != null) {
            apiServer.stop(0);
        }
        if (apiThreads != null) {
            apiThreads.close();
        }
    }

    public static class Source extends GithubConfigurationTestModule {
        public Source() {
            super(settings());
        }

        private static Map<String, String> settings() {
            try {
                apiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                apiThreads = Executors.newVirtualThreadPerTaskExecutor();
                apiServer.setExecutor(apiThreads);
                apiServer.start();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            return Map.of(
                    "github.api",
                    "http://127.0.0.1:" + apiServer.getAddress().getPort(),
                    "github.login",
                    "bot",
                    "github.authorization.userIds",
                    "42",
                    "github.authorization.allowRepositoryWriters",
                    "false",
                    "github.repositories.explicit",
                    "owner/repo",
                    "poll.pageBudget",
                    "1",
                    "poll.reviews.enabled",
                    "true");
        }
    }
}
