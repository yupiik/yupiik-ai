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

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.command.PollCommand.Options;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.PollOptions;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = GithubConfigurationTestModule.class)
class PollerTest {
    @Fusion
    private Options defaultOptions;

    private static final Pattern ISSUE_PATH = Pattern.compile(".*/issues/[0-9]+$");
    private static final Pattern DISCOVERY_SUCCESS = Pattern.compile(
            "GitHub poll phase=discovery outcome=success durationMillis=\\d+ requests=0 httpMillis=\\d+ pacingMillis=\\d+");
    private static final Pattern MENTIONS_SUCCESS = Pattern.compile(
            "GitHub poll phase=mentions outcome=success durationMillis=\\d+ requests=3 httpMillis=\\d+ pacingMillis=\\d+");
    private static final Pattern REVIEWS_SUCCESS = Pattern.compile(
            "GitHub poll phase=reviews-and-discussions outcome=success durationMillis=\\d+ requests=1 httpMillis=\\d+ pacingMillis=\\d+");
    private static final Pattern TOTAL_SUCCESS = Pattern.compile(
            "GitHub poll phase=total outcome=success durationMillis=\\d+ requests=4 httpMillis=\\d+ pacingMillis=\\d+");
    private static final Pattern TOTAL_FAILURE = Pattern.compile(
            "GitHub poll phase=total outcome=failure durationMillis=\\d+ requests=1 httpMillis=\\d+ pacingMillis=\\d+");

    @TempDir
    Path home;

    private GithubBotConfiguration configuration(final String api, final String ids) {
        return configuration(api, ids, "owner/repo");
    }

    private GithubBotConfiguration configuration(final String api, final String ids, final String repositories) {
        return new GithubBotConfiguration(
                home.toString(),
                api,
                "bot",
                0,
                new GithubBotConfiguration.Auth(
                        GithubBotConfiguration.AuthType.pat, "GH_TOKEN", "", 0, "", "GH_AGENT_TOKEN"),
                new GithubBotConfiguration.Authorization(ids, "", false, 7200, 4096),
                new GithubBotConfiguration.Repositories(
                        repositories, "", "", "", List.of(RepositorySelector.Visibility.values()), false, false));
    }

    @Test
    void reviewsUseParentMetadataRatherThanReferencePathsAndLeaveOtherKindsAlone() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var requests = new CopyOnWriteArrayList<String>();
            final var actor = Map.of("id", 42, "login", "human", "type", "User");
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                requests.add(path);
                final Object body =
                        switch (path) {
                            case "/repos/owner/repo/issues" ->
                                List.of(
                                        Map.of(
                                                "id",
                                                1,
                                                "user",
                                                actor,
                                                "body",
                                                "@bot review description PR",
                                                "html_url",
                                                "https://github.com/owner/repo/issues/11",
                                                "pull_request",
                                                Map.of()),
                                        Map.of(
                                                "id",
                                                2,
                                                "user",
                                                actor,
                                                "body",
                                                "@bot review description issue",
                                                "html_url",
                                                "https://github.com/owner/repo/pull/12"));
                            case "/repos/owner/repo/issues/comments" ->
                                List.of(
                                        Map.of(
                                                "id",
                                                3,
                                                "user",
                                                actor,
                                                "body",
                                                "@bot review comment PR",
                                                "html_url",
                                                "https://github.com/owner/repo/issues/13#issuecomment-3"),
                                        Map.of(
                                                "id",
                                                4,
                                                "user",
                                                actor,
                                                "body",
                                                "@bot review comment issue",
                                                "html_url",
                                                "https://github.com/owner/repo/pull/14#issuecomment-4"),
                                        Map.of(
                                                "id",
                                                5,
                                                "user",
                                                actor,
                                                "body",
                                                "@bot help unchanged",
                                                "html_url",
                                                "https://github.com/owner/repo/issues/15#issuecomment-5"),
                                        Map.of(
                                                "id",
                                                6,
                                                "user",
                                                actor,
                                                "body",
                                                "@bot solve unchanged",
                                                "html_url",
                                                "https://github.com/owner/repo/pull/16#issuecomment-6"));
                            case "/repos/owner/repo/pulls/comments" ->
                                List.of(Map.of(
                                        "id",
                                        7,
                                        "user",
                                        actor,
                                        "body",
                                        "@bot review inline",
                                        "html_url",
                                        "https://github.com/owner/repo/pull/17#discussion_r7"));
                            case "/repos/owner/repo/issues/13", "/repos/owner/repo/issues/17" ->
                                Map.of("pull_request", Map.of());
                            case "/repos/owner/repo/issues/14" -> Map.of("number", 14);
                            default -> Map.of("message", "Unexpected request");
                        };
                final var bytes = json.toString(body).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(
                        body instanceof Map<?, ?> map && map.containsKey("message") ? 404 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var store = new TaskStore(conf, json);
            final var options = new PollOptions(
                    defaultOptions.poll().since(),
                    defaultOptions.poll().pageBudget(),
                    defaultOptions.poll().reviews(),
                    defaultOptions.poll().discussions(),
                    defaultOptions.poll().parallelism(),
                    new PollOptions.Search(false, defaultOptions.poll().search().reconciliationIntervalSeconds()));
            try (final var github =
                    new GitHubClient(URI.create(conf.api()), json, () -> "test", BotTestConfiguration.CLOCK)) {
                final var poller = BotTestConfiguration.poller(conf, store, github, BotTestConfiguration.CLOCK, null);
                assertEquals(7, poller.poll(options));
                final var targets = store.transaction(state -> state.list()).stream()
                        .filter(task -> task.kind() == TaskKind.review)
                        .collect(Collectors.toMap(task -> task.number(), task -> task.reviewTarget()));
                assertEquals(
                        Map.of(
                                11L,
                                "pull_request",
                                12L,
                                "issue",
                                13L,
                                "pull_request",
                                14L,
                                "issue",
                                17L,
                                "pull_request"),
                        targets);
                assertTrue(store.transaction(state -> state.list()).stream()
                        .filter(task -> task.kind() != TaskKind.review)
                        .allMatch(task -> task.reviewTarget() == null));
                assertEquals(
                        Set.of(
                                "/repos/owner/repo/issues/13",
                                "/repos/owner/repo/issues/14",
                                "/repos/owner/repo/issues/17"),
                        requests.stream()
                                .filter(path -> ISSUE_PATH.matcher(path).matches())
                                .collect(toSet()));
                assertEquals(0, poller.poll(options));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fixesRequireOptInAndPullRequestContextWithoutConvertingSolve() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var actor = Map.of("id", 42, "login", "human", "type", "User");
            final var sha = new AtomicReference<>("a".repeat(40));
            final var metadataRequests = new AtomicInteger();
            final var failMetadata = new AtomicBoolean(true);
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                if (path.equals("/repos/owner/repo/pulls/12")
                        || path.equals("/repos/owner/repo")
                        || path.startsWith("/repos/human/fork")) {
                    metadataRequests.incrementAndGet();
                    final var body =
                            switch (path) {
                                case "/repos/owner/repo/pulls/12" ->
                                    BotTestConfiguration.fixPull("owner/repo", sha.get());
                                case "/repos/owner/repo" -> BotTestConfiguration.fixRepository("owner/repo", "BASE");
                                case "/repos/human/fork" -> BotTestConfiguration.fixRepository("human/fork", "HEAD");
                                default -> BotTestConfiguration.fixRef(sha.get());
                            };
                    final var bytes = json.toString(body).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(failMetadata.get() ? 403 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                    return;
                }
                final var body = path.endsWith("/issues")
                        ? List.of(
                                Map.of(
                                        "id",
                                        1,
                                        "user",
                                        actor,
                                        "body",
                                        "@bot fix issue",
                                        "html_url",
                                        "https://github.com/owner/repo/issues/12"),
                                Map.of(
                                        "id",
                                        2,
                                        "user",
                                        actor,
                                        "body",
                                        "@bot fix description",
                                        "html_url",
                                        "https://github.com/owner/repo/pull/12",
                                        "pull_request",
                                        Map.of()),
                                Map.of(
                                        "id",
                                        3,
                                        "user",
                                        actor,
                                        "body",
                                        "@bot fix unconfirmed",
                                        "html_url",
                                        "https://github.com/owner/repo/pull/13"),
                                Map.of(
                                        "id",
                                        4,
                                        "user",
                                        actor,
                                        "body",
                                        "@bot solve unchanged",
                                        "html_url",
                                        "https://github.com/owner/repo/pull/14",
                                        "pull_request",
                                        Map.of()))
                        : path.endsWith("/issues/comments")
                                ? List.of(
                                        Map.of(
                                                "id",
                                                5,
                                                "user",
                                                actor,
                                                "body",
                                                "@bot fix issue comment",
                                                "html_url",
                                                "https://github.com/owner/repo/issues/12#issuecomment-5"),
                                        Map.of(
                                                "id",
                                                6,
                                                "user",
                                                actor,
                                                "body",
                                                "@bot fix PR comment",
                                                "html_url",
                                                "https://github.com/owner/repo/pull/12#issuecomment-6"))
                                : List.of(Map.of(
                                        "id",
                                        7,
                                        "user",
                                        actor,
                                        "body",
                                        "@bot fix inline",
                                        "html_url",
                                        "https://github.com/owner/repo/pull/12#discussion_r7"));
                final var bytes = json.toString(body).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var store = new TaskStore(conf, json);
            final var options = new PollOptions(
                    defaultOptions.poll().since(),
                    defaultOptions.poll().pageBudget(),
                    defaultOptions.poll().reviews(),
                    defaultOptions.poll().discussions(),
                    defaultOptions.poll().parallelism(),
                    new PollOptions.Search(false, defaultOptions.poll().search().reconciliationIntervalSeconds()));
            try (final var github =
                    new GitHubClient(URI.create(conf.api()), json, () -> "test", BotTestConfiguration.CLOCK)) {
                assertEquals(
                        1,
                        BotTestConfiguration.poller(conf, store, github, BotTestConfiguration.CLOCK, null)
                                .poll(options));
                assertEquals(
                        TaskKind.solve,
                        store.transaction(state -> state.list().getFirst().kind()));
                final var completedCursor =
                        store.transaction(state -> state.cursors().get("owner/repo/issues"));
                final var enabled = new Poller(
                        conf,
                        store,
                        github,
                        BotTestConfiguration.CLOCK,
                        null,
                        new TeamAuthorization(github, conf, BotTestConfiguration.CLOCK),
                        BotTestConfiguration.reviewSummaryPoller(conf, store, github, BotTestConfiguration.CLOCK, null),
                        BotTestConfiguration.discussionPoller(conf, store, github, BotTestConfiguration.CLOCK, null),
                        new RepositoryDiscovery(conf, github, BotTestConfiguration.CLOCK),
                        new FixConfiguration(true, "human/fork"),
                        new MentionParser());
                assertThrows(GitHubClient.HttpException.class, () -> enabled.poll(options));
                assertEquals(1, store.transaction(state -> state.list()).size(), "Failed pin creates no fix task");
                assertEquals(1, store.transaction(state -> state.receipts()).size(), "Failed pin consumes no receipt");
                assertEquals(
                        completedCursor,
                        store.transaction(state -> state.cursors().get("owner/repo/issues")));
                failMetadata.set(false);
                assertEquals(3, enabled.poll(options));
                final var requestsAfterEnqueue = metadataRequests.get();
                assertEquals(0, enabled.poll(options));
                final var tasks = store.transaction(state -> state.list());
                assertEquals(requestsAfterEnqueue, metadataRequests.get(), "Receipts avoid repeat metadata GETs");
                assertEquals(
                        3,
                        tasks.stream()
                                .filter(task -> task.kind() == TaskKind.fix)
                                .count());
                for (final var task : tasks) {
                    if (task.kind() == TaskKind.fix) {
                        final var pinned = TaskStore.map(task.recovery().get("fixTarget"));
                        assertEquals("a".repeat(40), pinned.get("headSha"));
                        assertEquals("HEAD", pinned.get("repositoryId"));
                        assertEquals("42", task.actorId());
                    }
                }
                final var queued = tasks.stream()
                        .filter(task -> task.kind() == TaskKind.fix)
                        .findFirst()
                        .orElseThrow();
                sha.set("b".repeat(40));
                final var publisher = new FixPublisher(github, store, json, new FixConfiguration(true, "human/fork"));
                assertThrows(IllegalStateException.class, () -> publisher.prepare(queued));
                assertThrows(IllegalStateException.class, () -> publisher.pinRequest(queued));
                final var durable =
                        store.transaction(state -> state.task(queued.id()).document());
                assertEquals(
                        "a".repeat(40),
                        TaskStore.map(durable.recovery().get("fixTarget")).get("headSha"),
                        "A head change between enqueue and execution must not repin");
                assertTrue(tasks.stream().allMatch(task -> task.reference().contains("/pull/")));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundsRepositoryPollingAtTwoOverlappingHttpRequests() throws Exception {
        assertRepositoryPollingConcurrency(2);
    }

    @Test
    void parallelismOnePreservesSerialRepositoryAndEndpointOrder() throws Exception {
        assertRepositoryPollingConcurrency(1);
    }

    private void assertRepositoryPollingConcurrency(final int parallelism) throws Exception {
        final var paths = new CopyOnWriteArrayList<String>();
        final var active = new AtomicInteger();
        final var maximum = new AtomicInteger();
        final var overlap = new CountDownLatch(2);
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var handlers = Executors.newVirtualThreadPerTaskExecutor();
                final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                paths.add(path);
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    try {
                        if (parallelism == 2 && path.endsWith("/issues")) {
                            overlap.countDown();
                            if (!overlap.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Repository HTTP responses did not overlap");
                            }
                        }
                        Thread.sleep(40);
                    } finally {
                        // Count withheld responses, not server cleanup after the client receives the body.
                        active.decrementAndGet();
                    }
                    final var repository = path.split("/")[3];
                    final var body = path.endsWith("/issues")
                            ? json.toString(List.of(Map.of(
                                    "id",
                                    1,
                                    "user",
                                    Map.of("id", 42, "login", "human"),
                                    "body",
                                    "@bot solve fix it",
                                    "html_url",
                                    "https://github.com/owner/" + repository + "/issues/12")))
                            : "[]";
                    final var bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (final InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                final var conf = configuration(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "42",
                        "owner/first,owner/second,owner/third");
                final var store = new TaskStore(conf, json);
                try (final var github =
                        new GitHubClient(URI.create(conf.api()), json, () -> "test", BotTestConfiguration.CLOCK)) {
                    final var invocationOptions = new PollOptions(
                            "now",
                            1,
                            new PollOptions.Reviews(false),
                            new PollOptions.Discussions(false),
                            parallelism,
                            new PollOptions.Search(false, 3600));
                    final var poller = BotTestConfiguration.poller(conf, store, github, Clock.systemUTC(), null);
                    assertEquals(3, poller.poll(invocationOptions));
                    assertEquals(parallelism, maximum.get());
                    assertEquals(9, paths.size());
                    if (parallelism == 1) {
                        assertEquals(
                                List.of(
                                        "/repos/owner/first/issues",
                                        "/repos/owner/first/issues/comments",
                                        "/repos/owner/first/pulls/comments",
                                        "/repos/owner/second/issues",
                                        "/repos/owner/second/issues/comments",
                                        "/repos/owner/second/pulls/comments",
                                        "/repos/owner/third/issues",
                                        "/repos/owner/third/issues/comments",
                                        "/repos/owner/third/pulls/comments"),
                                paths);
                    }
                    final var tasks = store.transaction(state ->
                            state.list().stream().map(TaskFixtures::map).toList());
                    assertEquals(3, tasks.size());
                    assertEquals(
                            Set.of("owner/first", "owner/second", "owner/third"),
                            tasks.stream().map(task -> task.get("repository")).collect(toSet()));
                    assertEquals(
                            1,
                            tasks.stream()
                                    .map(task -> task.get("batchId"))
                                    .distinct()
                                    .count());
                    assertEquals(0, poller.poll(invocationOptions));
                    assertEquals(
                            tasks,
                            store.transaction(state ->
                                    state.list().stream().map(TaskFixtures::map).toList()));
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void invalidParallelismFailsBeforeHttp() throws Exception {
        final var requests = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("[]".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty());
                final var github = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "test",
                        BotTestConfiguration.CLOCK)) {
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            for (final var invalid : List.of(Integer.MIN_VALUE, -1, 0, 17, Integer.MAX_VALUE)) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> BotTestConfiguration.poller(
                                        conf, new TaskStore(conf, json), github, Clock.systemUTC(), null)
                                .poll(new PollOptions(
                                        "now",
                                        1,
                                        new PollOptions.Reviews(false),
                                        new PollOptions.Discussions(false),
                                        invalid,
                                        new PollOptions.Search(false, 3600))));
            }
            assertEquals(0, requests.get());
            assertEquals(0, github.statistics().requests());
            final var defaults = defaultOptions.poll();
            assertEquals(4, defaults.parallelism());
            assertTrue(defaults.search().enabled());
            assertEquals(3600, defaults.search().reconciliationIntervalSeconds());
            assertEquals(
                    16,
                    new PollOptions(
                                    "now",
                                    1,
                                    new PollOptions.Reviews(false),
                                    new PollOptions.Discussions(false),
                                    16,
                                    new PollOptions.Search(false, 3600))
                            .parallelism());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void repositoryFailureCancelsSlowSiblingAndReplaysWithoutTaskLossOrDuplicates() throws Exception {
        final var slowRequest = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var fail = new AtomicBoolean(true);
        final var paths = new CopyOnWriteArrayList<String>();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var handlers = Executors.newVirtualThreadPerTaskExecutor();
                final var callers = Executors.newVirtualThreadPerTaskExecutor();
                final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                paths.add(path);
                try {
                    if (fail.get() && path.equals("/repos/owner/first/issues/comments")) {
                        slowRequest.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Slow response was not released");
                        }
                    }
                    final var failing = fail.get() && path.equals("/repos/owner/second/issues");
                    if (failing && !slowRequest.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Sibling never reached its slow request");
                    }
                    final var repository = path.split("/")[3];
                    final var body = path.endsWith("/issues")
                            ? json.toString(List.of(Map.of(
                                    "id",
                                    1,
                                    "user",
                                    Map.of("id", 42, "login", "human"),
                                    "body",
                                    "@bot solve fix it",
                                    "html_url",
                                    "https://github.com/owner/" + repository + "/issues/12")))
                            : "[]";
                    final var bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(failing ? 400 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (final InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                final var conf = configuration(
                        "http://127.0.0.1:" + server.getAddress().getPort(), "42", "owner/first,owner/second");
                final var store = new TaskStore(conf, json);
                final var options = new PollOptions(
                        "now",
                        1,
                        new PollOptions.Reviews(false),
                        new PollOptions.Discussions(false),
                        2,
                        new PollOptions.Search(false, 3600));
                try (final var github =
                        new GitHubClient(URI.create(conf.api()), json, () -> "test", BotTestConfiguration.CLOCK)) {
                    final var failed = callers.submit(
                            () -> BotTestConfiguration.poller(conf, store, github, Clock.systemUTC(), null)
                                    .poll(options));
                    assertTrue(slowRequest.await(5, TimeUnit.SECONDS));
                    final var error = assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
                    assertInstanceOf(GitHubClient.HttpException.class, error.getCause());
                    // The poll must return while the server still withholds the sibling response.
                    assertEquals(1, release.getCount());
                    assertFalse(paths.contains("/repos/owner/first/pulls/comments"));
                    assertFalse(store.<Boolean>transaction(
                            state -> state.cursors().containsKey("owner/first/issues/comments")));
                    assertFalse(
                            store.<Boolean>transaction(state -> state.cursors().containsKey("owner/second/issues")));
                    final var persisted = store.transaction(state ->
                            state.list().stream().map(TaskFixtures::map).toList());
                    assertEquals(1, persisted.size());
                    assertEquals("owner/first", persisted.getFirst().get("repository"));
                    fail.set(false);
                    release.countDown();
                    // Reconstruct the poller/store to exercise durable replay, not in-memory deduplication.
                    final var restarted = new TaskStore(conf, json);
                    final var invocationOptions = options;
                    final var poller = BotTestConfiguration.poller(conf, restarted, github, Clock.systemUTC(), null);
                    assertEquals(1, poller.poll(invocationOptions));
                    assertEquals(0, poller.poll(invocationOptions));
                    final var tasks = restarted.transaction(state ->
                            state.list().stream().map(TaskFixtures::map).toList());
                    assertEquals(2, tasks.size());
                    assertTrue(tasks.contains(persisted.getFirst()));
                    assertEquals(
                            Set.of("owner/first", "owner/second"),
                            tasks.stream().map(task -> task.get("repository")).collect(toSet()));
                }
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }

    @Test
    void interruptedPollCancelsRepositoryRequestsAndPreservesInterruptStatus() throws Exception {
        final var entered = new CountDownLatch(2);
        final var release = new CountDownLatch(1);
        final var interrupted = new AtomicBoolean();
        final var failure = new AtomicReference<RuntimeException>();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var handlers = Executors.newVirtualThreadPerTaskExecutor();
                final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Interrupted poll did not finish");
                    }
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write("[]".getBytes(StandardCharsets.UTF_8));
                } catch (final InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                final var conf = configuration(
                        "http://127.0.0.1:" + server.getAddress().getPort(), "42", "owner/first,owner/second");
                try (final var github =
                        new GitHubClient(URI.create(conf.api()), json, () -> "test", BotTestConfiguration.CLOCK)) {
                    final var invocationOptions = new PollOptions(
                            "now",
                            1,
                            new PollOptions.Reviews(false),
                            new PollOptions.Discussions(false),
                            2,
                            new PollOptions.Search(false, 3600));
                    final var poller = BotTestConfiguration.poller(
                            conf, new TaskStore(conf, json), github, Clock.systemUTC(), null);
                    final var caller = Thread.ofVirtual().start(() -> {
                        try {
                            poller.poll(invocationOptions);
                        } catch (final RuntimeException error) {
                            failure.set(error);
                        } finally {
                            interrupted.set(Thread.currentThread().isInterrupted());
                        }
                    });
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        caller.interrupt();
                        caller.join(5_000);
                        assertFalse(caller.isAlive(), "Interrupted poll waited for withheld HTTP responses");
                        assertInstanceOf(IllegalStateException.class, failure.get());
                        assertInstanceOf(
                                InterruptedException.class, failure.get().getCause());
                        assertTrue(interrupted.get());
                        assertEquals(1, release.getCount());
                    } finally {
                        caller.interrupt();
                        release.countDown();
                        caller.join(5_000);
                    }
                }
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }

    @Test
    void logsPerPhaseTransportCountsAcrossRestartsAndOnFailure() throws Exception {
        final var messages = new CopyOnWriteArrayList<String>();
        final var testThread = Thread.currentThread().threadId();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                if (record.getLongThreadID() == testThread
                        && record.getMessage().startsWith("GitHub poll phase=")) {
                    messages.add(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        final var logger = Logger.getLogger(Poller.class.getName());
        final var fail = new AtomicBoolean();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final var body = "/graphql".equals(exchange.getRequestURI().getPath()) ? """
                      {"data":{"r0":{"nameWithOwner":"owner/repo","pullRequests":{
                        "nodes":[{"number":12,"state":"OPEN","reviews":{
                          "nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}],
                        "pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
                      """ : "[]";
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail.get() ? 400 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        logger.addHandler(handler);
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var options = new PollOptions(
                    "now",
                    20,
                    new PollOptions.Reviews(true),
                    new PollOptions.Discussions(false),
                    4,
                    new PollOptions.Search(false, 3600));
            for (int run = 0; run < 2; run++) {
                messages.clear();
                try (final var github = new GitHubClient(
                        URI.create(conf.api()), json, () -> "private-token", BotTestConfiguration.CLOCK)) {
                    assertEquals(
                            0,
                            BotTestConfiguration.poller(
                                            conf, new TaskStore(conf, json), github, Clock.systemUTC(), null)
                                    .poll(options));
                    assertEquals(4, github.statistics().requests());
                }
                assertEquals(4, messages.size());
                assertTrue(messages.stream()
                        .anyMatch(message -> DISCOVERY_SUCCESS.matcher(message).matches()));
                assertTrue(messages.stream()
                        .anyMatch(message -> MENTIONS_SUCCESS.matcher(message).matches()));
                assertTrue(messages.stream()
                        .anyMatch(message -> REVIEWS_SUCCESS.matcher(message).matches()));
                assertTrue(messages.stream()
                        .anyMatch(message -> TOTAL_SUCCESS.matcher(message).matches()));
            }
            messages.clear();
            fail.set(true);
            try (final var github =
                    new GitHubClient(URI.create(conf.api()), json, () -> "private-token", BotTestConfiguration.CLOCK)) {
                assertThrows(
                        GitHubClient.HttpException.class,
                        () -> BotTestConfiguration.poller(
                                        conf, new TaskStore(conf, json), github, Clock.systemUTC(), null)
                                .poll(options));
                assertEquals(1, github.statistics().requests());
            }
            assertEquals(3, messages.size());
            assertTrue(messages.stream()
                    .anyMatch(message -> TOTAL_FAILURE.matcher(message).matches()));
            assertTrue(messages.stream()
                    .noneMatch(message -> message.contains("private-token") || message.contains("since=")));
        } finally {
            logger.removeHandler(handler);
            server.stop(0);
        }
    }

    @Test
    void optionalPollersRunConcurrentlyOnVirtualThreads() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final var bytes = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(ignored -> Optional.empty());
                final var github = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "test",
                        BotTestConfiguration.CLOCK)) {
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var store = new TaskStore(conf, json);
            final var options = new Options(new PollOptions(
                    "now",
                    1,
                    new PollOptions.Reviews(true),
                    new PollOptions.Discussions(true),
                    4,
                    new PollOptions.Search(false, 3600)));
            final var started = new CountDownLatch(2);
            final Runnable rendezvous = () -> {
                assertTrue(Thread.currentThread().isVirtual());
                started.countDown();
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS), "optional pollers did not overlap");
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            };
            final var reviews =
                    new ReviewSummaryPoller(
                            conf,
                            store,
                            github,
                            Clock.systemUTC(),
                            null,
                            null,
                            new FixConfiguration(false, ""),
                            new MentionParser()) {
                        @Override
                        public int poll(final List<String> repositories, final PollOptions actualOptions) {
                            assertSame(options.poll(), actualOptions);
                            rendezvous.run();
                            return 2;
                        }
                    };
            final var discussions =
                    new DiscussionPoller(
                            conf, store, github, Clock.systemUTC(), null, null, null, new MentionParser()) {
                        @Override
                        public int poll(final List<String> repositories, final PollOptions actualOptions) {
                            assertSame(options.poll(), actualOptions);
                            rendezvous.run();
                            return 3;
                        }
                    };
            final var poller = new Poller(
                    conf,
                    store,
                    github,
                    Clock.systemUTC(),
                    null,
                    new TeamAuthorization(github, conf, Clock.systemUTC()),
                    reviews,
                    discussions,
                    new RepositoryDiscovery(conf, github, Clock.systemUTC()),
                    new FixConfiguration(false, ""),
                    new MentionParser());
            assertEquals(5, poller.poll(options.poll()));
            final var disabled = new PollOptions(
                    "now",
                    2,
                    new PollOptions.Reviews(false),
                    new PollOptions.Discussions(false),
                    1,
                    new PollOptions.Search(false, 3600));
            assertEquals(0, poller.poll(disabled));
            assertEquals(5, poller.poll(options.poll()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void authorizesImmutableIdsAndDeduplicatesAfterDeletion() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                final var body = path.endsWith("issues/comments")
                        ? json.toString(List.of(
                                Map.of(
                                        "id",
                                        1,
                                        "user",
                                        Map.of("id", 42, "login", "human", "type", "User"),
                                        "body",
                                        "@bot solve fix it",
                                        "html_url",
                                        "https://github.com/owner/repo/issues/12#issuecomment-1"),
                                Map.of(
                                        "id",
                                        2,
                                        "user",
                                        Map.of("id", 77, "login", "human", "type", "User"),
                                        "body",
                                        "@bot solve do not run",
                                        "html_url",
                                        "https://github.com/owner/repo/issues/12#issuecomment-2"),
                                Map.of(
                                        "id",
                                        3,
                                        "user",
                                        Map.of("id", 77, "login", "human", "type", "User"),
                                        "body",
                                        "ordinary sensitive nonmention"),
                                Map.of(
                                        "id",
                                        4,
                                        "user",
                                        Map.of("id", 77, "login", "human", "type", "User"),
                                        "body",
                                        "`@bot solve hidden mention`"),
                                Map.of(
                                        "id",
                                        5,
                                        "user",
                                        Map.of("id", 42, "login", "human", "type", "User"),
                                        "body",
                                        "<!-- yca-task:previous-task -->\nMention @bot review on a pull request",
                                        "html_url",
                                        "https://github.com/owner/repo/issues/12#issuecomment-5")))
                        : "[]";
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            try (final var github =
                    new GitHubClient(URI.create(conf.api()), json, () -> "test", BotTestConfiguration.CLOCK)) {
                final var invocationOptions = new PollOptions(
                        "now",
                        1,
                        new PollOptions.Reviews(false),
                        new PollOptions.Discussions(false),
                        4,
                        new PollOptions.Search(false, 3600));
                final var poller = BotTestConfiguration.poller(
                        conf,
                        store,
                        github,
                        Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC),
                        new BotAudit(BotTestConfiguration.home(home), json, BotTestConfiguration.CLOCK));
                assertEquals(1, poller.poll(invocationOptions));
                assertEquals(
                        1,
                        store.transaction(state -> state.list().stream()
                                        .map(TaskFixtures::map)
                                        .toList())
                                .size());
                final var id = store.transaction(state ->
                                state.list().stream().map(TaskFixtures::map).toList())
                        .getFirst()
                        .get("id");
                store.transaction(state -> {
                    state.tasks().clear();
                    return null;
                });
                assertEquals(0, poller.poll(invocationOptions));
                final var events = Files.readAllLines(home.resolve("audit.jsonl")).stream()
                        .map(line -> TaskStore.map(json.fromString(Object.class, line)))
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
                assertEquals(2, denied.size()); // One visible unauthorized mention per scan, not nonmentions.
                for (final var event : denied) {
                    assertEquals(Set.of("at", "event", "actorId", "repository"), event.keySet());
                    assertEquals("77", String.valueOf(event.get("actorId")));
                }
                assertEquals(3, events.size());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptyAllowlistDeniesBeforeNetwork() {
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var github = new GitHubClient(
                        URI.create("http://127.0.0.1:1"), json, () -> "test", BotTestConfiguration.CLOCK)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> BotTestConfiguration.poller(
                                    configuration("http://127.0.0.1:1", ""),
                                    new TaskStore(BotTestConfiguration.home(home), json),
                                    github,
                                    Clock.systemUTC(),
                                    null)
                            .poll(new PollOptions(
                                    "now",
                                    1,
                                    new PollOptions.Reviews(false),
                                    new PollOptions.Discussions(false),
                                    4,
                                    new PollOptions.Search(false, 3600))));
        }
    }

    @Test
    void pageBudgetDoesNotAdvancePastBacklog() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders()
                        .set("Link", "<http://127.0.0.1:" + server.getAddress().getPort() + "/page2>; rel=\"next\"");
                final var bytes = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            try (final var github =
                    new GitHubClient(URI.create(conf.api()), json, () -> "test", BotTestConfiguration.CLOCK)) {
                BotTestConfiguration.poller(conf, store, github, Clock.systemUTC(), null)
                        .poll(new PollOptions(
                                "now",
                                1,
                                new PollOptions.Reviews(false),
                                new PollOptions.Discussions(false),
                                4,
                                new PollOptions.Search(false, 3600)));
            }
            assertTrue(store.<Boolean>transaction(state -> !state.cursors().containsKey("owner/repo/issues")));
            assertTrue(store.<Boolean>transaction(state -> state.cursors().containsKey("owner/repo/issues:next")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resumesNextPagesAndReplaysEditsBeforeAdvancingWatermark() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var phase = new AtomicInteger();
        final var pages = new CopyOnWriteArrayList<String>();
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                pages.add(path);
                final var isIssues = path.equals("/repos/owner/repo/issues");
                final var body = isIssues && phase.get() == 2
                        ? json.toString(List.of(Map.of(
                                "id",
                                9,
                                "user",
                                Map.of("id", 42, "login", "human"),
                                "body",
                                "@bot solve edited earlier page",
                                "html_url",
                                "https://github.com/owner/repo/issues/12")))
                        : "[]";
                if (isIssues) {
                    exchange.getResponseHeaders()
                            .set(
                                    "Link",
                                    "<http://127.0.0.1:" + server.getAddress().getPort() + "/page2>; rel=\"next\"");
                }
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var first = Instant.parse("2026-11-11T12:00:00Z");
            try (final var github =
                    new GitHubClient(URI.create(conf.api()), json, () -> "test", BotTestConfiguration.CLOCK)) {
                assertEquals(
                        0,
                        BotTestConfiguration.poller(conf, store, github, Clock.fixed(first, ZoneOffset.UTC), null)
                                .poll(new PollOptions(
                                        "now",
                                        1,
                                        new PollOptions.Reviews(false),
                                        new PollOptions.Discussions(false),
                                        4,
                                        new PollOptions.Search(false, 3600))));
                phase.set(1);
                assertEquals(
                        0,
                        BotTestConfiguration.poller(
                                        conf, store, github, Clock.fixed(first.plusSeconds(600), ZoneOffset.UTC), null)
                                .poll(new PollOptions(
                                        "now",
                                        1,
                                        new PollOptions.Reviews(false),
                                        new PollOptions.Discussions(false),
                                        4,
                                        new PollOptions.Search(false, 3600))));
                assertTrue(pages.contains("/page2"));
                assertFalse(store.<Boolean>transaction(state -> state.cursors().containsKey("owner/repo/issues")));
                phase.set(2);
                assertEquals(
                        1,
                        BotTestConfiguration.poller(
                                        conf, store, github, Clock.fixed(first.plusSeconds(1200), ZoneOffset.UTC), null)
                                .poll(new PollOptions(
                                        "now",
                                        1,
                                        new PollOptions.Reviews(false),
                                        new PollOptions.Discussions(false),
                                        4,
                                        new PollOptions.Search(false, 3600))));
                assertEquals(
                        first.toString(),
                        store.transaction(state -> state.cursors().get("initialSince")));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serializesPollersWithoutBlockingWorkerTransactions() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var requests = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var executor = Executors.newFixedThreadPool(3);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for worker transaction");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("[]".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            try (final var github =
                    new GitHubClient(URI.create(conf.api()), json, () -> "test", BotTestConfiguration.CLOCK)) {
                final var first =
                        executor.submit(() -> BotTestConfiguration.poller(conf, store, github, Clock.systemUTC(), null)
                                .poll(new PollOptions(
                                        "now",
                                        1,
                                        new PollOptions.Reviews(false),
                                        new PollOptions.Discussions(false),
                                        4,
                                        new PollOptions.Search(false, 3600))));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                final var second =
                        executor.submit(() -> BotTestConfiguration.poller(conf, store, github, Clock.systemUTC(), null)
                                .poll(new PollOptions(
                                        "now",
                                        1,
                                        new PollOptions.Reviews(false),
                                        new PollOptions.Discussions(false),
                                        4,
                                        new PollOptions.Search(false, 3600))));
                assertEquals(
                        "worker",
                        executor.submit(() -> store.transaction(state -> "worker"))
                                .get(5, TimeUnit.SECONDS));
                assertEquals(1, requests.get());
                assertFalse(second.isDone());
                release.countDown();
                assertEquals(0, first.get(10, TimeUnit.SECONDS));
                assertEquals(0, second.get(10, TimeUnit.SECONDS));
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }
}
