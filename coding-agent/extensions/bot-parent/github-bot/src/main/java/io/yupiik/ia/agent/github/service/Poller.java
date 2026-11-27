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

import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.MentionSearchClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.PollOptions;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.store.MutableTask;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Polling never calls a model. Identity checks precede durable enqueue. */
@ApplicationScoped
public class Poller {

    private static final Logger LOGGER = Logger.getLogger(Poller.class.getName());
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final MentionParser mentionParser;
    private final GithubBotConfiguration conf;
    private final TaskStore store;
    private final GitHubClient github;
    private final Clock clock;
    private final BotAudit audit;
    private final TeamAuthorization authorization;
    private final ReviewSummaryPoller reviews;
    private final DiscussionPoller discussions;
    private final RepositoryDiscovery discovery;
    private final FixConfiguration fix;
    private final FixRequestAuthority fixRequests;

    /** The optional audit sink is shared with the review-summary poller. */
    public Poller(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit,
            final TeamAuthorization authorization,
            final ReviewSummaryPoller reviews,
            final DiscussionPoller discussions,
            final RepositoryDiscovery discovery,
            final FixConfiguration fix,
            final MentionParser mentionParser) {
        this.mentionParser = mentionParser;
        this.conf = conf;
        this.store = store;
        this.github = github;
        this.clock = clock;
        this.audit = audit;
        this.authorization = authorization;
        this.reviews = reviews;
        this.discussions = discussions;
        this.discovery = discovery;
        this.fix = fix;
        this.fixRequests = new FixRequestAuthority(github, fix);
    }

    public int poll(final PollOptions options) {
        if (options.pageBudget() <= 0) {
            throw new IllegalArgumentException("Positive page budget required");
        }
        store.initialize();
        final var path = Path.of(conf.home()).toAbsolutePath().normalize().resolve("poll.lock");
        final var local = LOCKS.computeIfAbsent(path, ignored -> new ReentrantLock());
        local.lock();
        try (final var channel = FileChannel.open(
                        path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                final var lock = channel.lock()) {
            return measured("total", () -> scan(options));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            local.unlock();
        }
    }

    private <T> T measured(final String phase, final Supplier<T> operation) {
        final var started = System.nanoTime();
        final var before = github.statistics();
        boolean success = false;
        try {
            final var result = operation.get();
            success = true;
            return result;
        } finally {
            final var after = github.statistics();
            LOGGER.info("GitHub poll phase=" + phase + " outcome=" + (success ? "success" : "failure")
                    + " durationMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + " requests=" + (after.requests() - before.requests())
                    + " httpMillis=" + TimeUnit.NANOSECONDS.toMillis(after.httpNanos() - before.httpNanos())
                    + " pacingMillis=" + TimeUnit.NANOSECONDS.toMillis(after.pacingNanos() - before.pacingNanos()));
        }
    }

    private List<String> repositories() {
        return discovery.repositories();
    }

    private int scan(final PollOptions options) {
        final Set<String> allowed = Arrays.stream(conf.authorization().userIds().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        if (allowed.isEmpty() && !conf.authorization().allowRepositoryWriters()) {
            throw new IllegalStateException("No authorized immutable user IDs configured; polling denied");
        }
        final var selected = measured("discovery", this::repositories);
        LOGGER.info(() -> "GitHub poll repositories=" + selected.size()
                + " reviewsEnabled=" + options.reviews().enabled()
                + " discussionsEnabled=" + options.discussions().enabled()
                + " searchEnabled=" + options.search().enabled());
        if (!options.search().enabled()) {
            return direct(options, selected);
        }

        // A quiet search must not move the initial lower bound to the time of the first later hit.
        final var started = clock.instant();
        store.transaction(state -> {
            final var initial = "now".equals(options.since())
                    ? started.toString()
                    : Instant.parse(options.since()).toString();
            state.cursors().putIfAbsent("initialSince", initial);
            if (options.discussions().enabled()) {
                state.cursors().putIfAbsent("discussion:initialSince", initial);
            }
            return null;
        });
        final var key = reconciliationKey(options, selected);
        final var reconcile = store.transaction(state -> {
            final var completed = state.cursors().get(key + ":completed");
            return state.cursors().containsKey(key + ":running")
                    || completed == null
                    || started.isBefore(Instant.parse(completed))
                    || !started.isBefore(Instant.parse(completed)
                            .plusSeconds(options.search().reconciliationIntervalSeconds()));
        });
        if (reconcile) {
            // Leave this marker on failure or bounded incomplete scans: the next invocation must
            // resume directly even when the search index has no matching parent yet.
            store.transaction(state -> {
                state.cursors().putIfAbsent(key + ":running", started.toString());
                return null;
            });
            final var created = direct(options, selected);
            if (pending(options, selected, false).isEmpty()
                    && pending(options, selected, true).isEmpty()) {
                store.transaction(state -> {
                    state.cursors().put(key + ":completed", clock.instant().toString());
                    state.cursors().remove(key + ":running");
                    return null;
                });
            }
            return created;
        }
        return search(options, selected);
    }

    private int direct(final PollOptions options, final List<String> selected) {
        final var created = measured("mentions", () -> scanMentions(options, selected));
        return measured("reviews-and-discussions", () -> scanOptional(options, selected, created));
    }

    private int search(final PollOptions options, final List<String> selected) {
        final var search = new MentionSearch(conf, store, github, clock);
        final var visited = new HashSet<String>();
        final var issueCreated = measured("search-mentions-and-reviews", () -> {
            // Acknowledged candidate pages can leave independent feed/review continuations. Resume
            // those first, even if the parent has since disappeared from search or was edited.
            int created = scanIssueCandidates(options, pending(options, selected, false), visited);
            created += search.poll(
                    selected,
                    MentionSearchClient.Kind.ISSUE,
                    options.pageBudget(),
                    repos -> scanIssueCandidates(options, repos, visited));
            return created;
        });
        if (!options.discussions().enabled()) {
            return issueCreated;
        }
        return issueCreated
                + measured("search-discussions", () -> {
                    final var discussionVisited = new HashSet<String>();
                    int created =
                            scanDiscussionCandidates(options, pending(options, selected, true), discussionVisited);
                    created += search.poll(
                            selected,
                            MentionSearchClient.Kind.DISCUSSION,
                            options.pageBudget(),
                            repos -> scanDiscussionCandidates(options, repos, discussionVisited));
                    return created;
                });
    }

    private int scanIssueCandidates(
            final PollOptions options, final List<String> candidates, final Set<String> visited) {
        final var selected = candidates.stream().filter(visited::add).toList();
        if (selected.isEmpty()) {
            return 0;
        }
        final var created = scanMentions(options, selected);
        return created + (options.reviews().enabled() ? reviews.poll(selected, options) : 0);
    }

    private int scanDiscussionCandidates(
            final PollOptions options, final List<String> candidates, final Set<String> visited) {
        final var selected = candidates.stream().filter(visited::add).toList();
        return selected.isEmpty() ? 0 : discussions.poll(selected, options);
    }

    private List<String> pending(final PollOptions options, final List<String> selected, final boolean discussion) {
        if (discussion && !options.discussions().enabled()) {
            return List.of();
        }
        return store.transaction(state -> selected.stream()
                .filter(repo -> state.cursors().keySet().stream().anyMatch(key -> {
                    if (!key.startsWith(repo + "/") || !key.endsWith(":next")) {
                        return false;
                    }
                    if (discussion) {
                        return key.startsWith(repo + "/discussion:");
                    }
                    return key.equals(repo + "/issues:next")
                            || key.equals(repo + "/issues/comments:next")
                            || key.equals(repo + "/pulls/comments:next")
                            || (options.reviews().enabled() && key.startsWith(repo + "/review-summary-graphql/"));
                }))
                .toList());
    }

    private String reconciliationKey(final PollOptions options, final List<String> selected) {
        final var scope = conf.api() + "\n" + conf.login().toLowerCase(Locale.ROOT) + "\n"
                + options.reviews().enabled() + ":" + options.discussions().enabled() + "\n"
                + selected.stream()
                        .map(repo -> repo.toLowerCase(Locale.ROOT))
                        .distinct()
                        .sorted()
                        .collect(Collectors.joining("\n"));
        try {
            return "mention-search/reconciliation/v1/"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(scope.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private int scanMentions(final PollOptions options, final List<String> selected) {
        final var batch = UUID.randomUUID().toString();
        return PollingTasks.sum(options.parallelism(), selected, repo -> scanMentions(options, repo, batch));
    }

    private int scanMentions(final PollOptions options, final String repo, final String batch) {
        int created = 0;
        for (final var endpoint : List.of("issues", "issues/comments", "pulls/comments")) {
            final var key = repo + "/" + endpoint;
            final var start = clock.instant();
            final var cursors = store.transaction(state -> {
                // Persist 'now' once, not once per restarted scan or endpoint.
                final var initial = state.cursors()
                        .computeIfAbsent(
                                "initialSince",
                                ignored -> "now".equals(options.since())
                                        ? start.toString()
                                        : Instant.parse(options.since()).toString());
                final var snapshot = new LinkedHashMap<>(state.cursors());
                snapshot.putIfAbsent(key, initial);
                return snapshot;
            });
            final var since = Instant.parse(String.valueOf(cursors.get(key))).minusSeconds(300);
            final var savedNext = cursors.get(key + ":next");
            final var scanStart = String.valueOf(cursors.getOrDefault(key + ":scanStart", start.toString()));
            String next = savedNext == null
                    ? "/repos/" + repo + "/" + endpoint + "?per_page=100&sort=updated&direction=asc&since="
                            + URLEncoder.encode(since.toString(), StandardCharsets.UTF_8)
                            + (endpoint.equals("issues") ? "&state=all" : "")
                    : String.valueOf(cursors.getOrDefault(key + ":overlap", savedNext));
            // Replay the preceding page in addition to the budget, covering page-boundary shifts.
            final int budget = options.pageBudget() + (cursors.containsKey(key + ":overlap") ? 1 : 0);
            String overlap = next;
            int pages = 0;
            while (next != null && pages++ < budget) {
                overlap = next;
                final var page = github.<List<RestResponses.FeedItem>>page(
                        next, new Types.ParameterizedTypeImpl(List.class, RestResponses.FeedItem.class));
                final var items = page.body();
                if (items == null) {
                    throw new IllegalStateException("Expected GitHub list");
                }
                for (final var item : items) {
                    final var user = item.user() == null ? new RestResponses.FeedUser(null, null, null) : item.user();
                    final var parsed =
                            mentionParser.parse(String.valueOf(item.body() == null ? "" : item.body()), conf.login());
                    if (parsed.isEmpty()) {
                        continue;
                    }
                    if (conf.login().equalsIgnoreCase(String.valueOf(user.login()))
                            || "Bot".equals(user.type())
                            || !authorization.allows(repo, user.id(), String.valueOf(user.login()))) {
                        if (audit != null) {
                            final var identifiers = MutableTask.create();
                            identifiers.repository(repo);
                            if (user.id() != null) {
                                identifiers.actorId(String.valueOf(user.id()));
                            }
                            audit.record("auth-denied", identifiers.document());
                        }
                        continue;
                    }
                    final var invocation = parsed.orElseThrow();
                    final var ref = String.valueOf(item.html_url());
                    final var uri = URI.create(ref);
                    final var parts = uri.getPath().split("/");
                    if (parts.length < 5 || !(parts[3].equals("issues") || parts[3].equals("pull"))) {
                        throw new IllegalStateException("Unexpected event reference");
                    }
                    final var number = Integer.parseInt(parts[4]);
                    if ("fix".equals(invocation.kind())
                            && (!fix.enabled()
                                    || !parts[3].equals("pull")
                                    || number <= 0
                                    || !"https".equalsIgnoreCase(uri.getScheme())
                                    || !("github.com".equalsIgnoreCase(uri.getHost())
                                            || (conf.api() != null
                                                    && URI.create(conf.api())
                                                            .getHost()
                                                            .equalsIgnoreCase(uri.getHost())))
                                    || uri.getUserInfo() != null
                                    || (uri.getPort() != -1 && uri.getPort() != 443)
                                    || uri.getQuery() != null
                                    || !uri.getRawPath().equalsIgnoreCase("/" + repo + "/pull/" + number)
                                    || (endpoint.equals("issues") && item.pull_request() == null))) {
                        continue;
                    }
                    final var task = MutableTask.create();
                    task.repository(repo);
                    task.number((long) number);
                    task.reference(ref);
                    task.kind(TaskKind.valueOf(invocation.kind()));
                    if (task.kind() == TaskKind.review) {
                        final boolean pull = endpoint.equals("issues")
                                ? item.pull_request() != null
                                : github.get("/repos/" + repo + "/issues/" + number, RestResponses.Issue.class)
                                                .pull_request()
                                        != null;
                        task.reviewTarget(pull ? "pull_request" : "issue");
                    }
                    task.request(invocation.request());
                    task.actorId(String.valueOf(user.id()));
                    task.batchId(batch);
                    task.createdAt(clock.instant().toString());
                    final var receipt = key + ":" + item.id();
                    if (task.kind() == TaskKind.fix) {
                        if (store.transaction(state -> state.receipts().containsKey(receipt))) {
                            continue;
                        }
                        final var recovery = new LinkedHashMap<String, Object>();
                        recovery.put("fixTarget", fixRequests.pinRequest(task.document()));
                        task.recovery(recovery);
                    }
                    final var id = store.transaction(state -> {
                        if (state.receipts().containsKey(receipt)) {
                            return null;
                        }
                        return state.enqueue(receipt, task.document());
                    });
                    if (id != null) {
                        created++;
                        if (audit != null) {
                            task.id(id);
                            audit.record("enqueued", task.document());
                        }
                    }
                }
                next = page.next();
            }
            // A capped/failed scan must retain its completed watermark. Receipts absorb replay.
            if (next == null) {
                store.transaction(state -> {
                    final var old =
                            Instant.parse(String.valueOf(state.cursors().getOrDefault(key, Instant.EPOCH.toString())));
                    final var completed = Instant.parse(scanStart);
                    // Offset pagination is mutable. A resumed traversal cannot prove that earlier
                    // pages did not shrink; retain the lower bound and replay on the next traversal.
                    if (savedNext == null && completed.isAfter(old)) {
                        state.cursors().put(key, completed.toString());
                    }
                    state.cursors().remove(key + ":next");
                    state.cursors().remove(key + ":scanStart");
                    state.cursors().remove(key + ":overlap");
                    return null;
                });
            } else {
                final var continuation = next;
                final var replay = overlap;
                store.transaction(state -> {
                    state.cursors().put(key + ":next", continuation);
                    state.cursors().put(key + ":overlap", replay);
                    state.cursors().put(key + ":scanStart", scanStart);
                    return null;
                });
            }
        }
        return created;
    }

    private int scanOptional(final PollOptions options, final List<String> selected, final int created) {
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var reviewScan =
                    executor.submit(() -> options.reviews().enabled() ? reviews.poll(selected, options) : 0);
            final var discussionScan =
                    executor.submit(() -> options.discussions().enabled() ? discussions.poll(selected, options) : 0);
            try {
                return created + reviewScan.get() + discussionScan.get();
            } catch (final InterruptedException error) {
                reviewScan.cancel(true);
                discussionScan.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Polling interrupted", error);
            } catch (final ExecutionException error) {
                if (error.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw new IllegalStateException("Polling failed", error.getCause());
            }
        }
    }
}
