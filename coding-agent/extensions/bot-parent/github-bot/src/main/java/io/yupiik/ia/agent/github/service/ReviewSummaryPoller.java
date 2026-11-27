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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.ReviewClient;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.PollOptions;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.store.MutableTask;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Opt-in native review-summary mentions; polling never calls a model.
 *
 * <p>GraphQL batches open-pull discovery and the first review pages across repositories. Each
 * discovery connection and each open PR's reviews have independent durable continuations and at
 * most pageBudget processed pages per traversal. A discovery page advances only after its review
 * pages have succeeded. Closed PRs, including the legacy retained catalog, are not revisited;
 * a reopened PR becomes eligible through discovery again. Network requests never hold a task-store
 * transaction; receipts outlive tasks and REST-to-GraphQL cursor migration.
 *
 * <p>GitHub connections are mutable, not snapshots. Completed traversals restart from page
 * one, retaining initialSince minus five minutes to catch pending reviews submitted later on open
 * PRs. Review backlogs progress when their discovery page is revisited. Repeated traversals recover
 * shifted entries once listings stabilize; mentions on PRs closed before discovery are not covered.
 */
@ApplicationScoped
public class ReviewSummaryPoller {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final MentionParser mentionParser;
    private final GithubBotConfiguration conf;
    private final TaskStore store;
    private final ReviewClient reviews;
    private final Clock clock;
    private final BotAudit audit;
    private final TeamAuthorization authorization;
    private final FixConfiguration fix;
    private final FixRequestAuthority fixRequests;

    /** A null audit sink preserves polling without audit output. */
    public ReviewSummaryPoller(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit,
            final TeamAuthorization authorization,
            final FixConfiguration fix,
            final MentionParser mentionParser) {
        this.mentionParser = mentionParser;
        this.conf = conf;
        this.store = store;
        // Fusion creates the lazy subclass with null constructor arguments, before injecting the real instance.
        this.reviews = github == null ? null : new ReviewClient(github);
        this.clock = clock;
        this.audit = audit;
        this.authorization = authorization;
        this.fix = fix;
        this.fixRequests = new FixRequestAuthority(github, fix);
    }

    public int poll(final List<String> repositories, final PollOptions options) {
        if (!options.reviews().enabled()) {
            return 0;
        }
        if (options.pageBudget() <= 0) {
            throw new IllegalArgumentException("Positive page budget required");
        }
        final Set<String> allowed = Arrays.stream(conf.authorization().userIds().split(","))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .collect(Collectors.toSet());
        if (allowed.isEmpty() && !conf.authorization().allowRepositoryWriters()) {
            throw new IllegalStateException("No authorized immutable user IDs configured; polling denied");
        }
        final var repos = List.copyOf(repositories).stream().distinct().toList();
        for (final var repo : repos) {
            if (!REPOSITORY_NAME.matcher(repo).matches()) {
                throw new IllegalArgumentException("Invalid explicit repository");
            }
        }
        store.initialize();
        final var path = Path.of(conf.home()).toAbsolutePath().normalize().resolve("review-summary-poll.lock");
        final var local = LOCKS.computeIfAbsent(path, ignored -> new ReentrantLock());
        local.lock();
        try (final var channel = FileChannel.open(
                        path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                final var lock = channel.lock()) {
            final var since = Instant.parse(store.transaction(state -> String.valueOf(state.cursors()
                            .computeIfAbsent(
                                    "initialSince",
                                    ignored -> "now".equals(options.since())
                                            ? clock.instant().toString()
                                            : Instant.parse(options.since()).toString()))))
                    .minusSeconds(300);
            final var batch = UUID.randomUUID().toString();
            final var batches = new ArrayList<List<String>>();
            for (int i = 0; i < repos.size(); i += ReviewClient.BATCH_SIZE) {
                batches.add(repos.subList(i, Math.min(i + ReviewClient.BATCH_SIZE, repos.size())));
            }
            return PollingTasks.sum(options.parallelism(), batches, group -> scanBatch(group, since, batch, options));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            local.unlock();
        }
    }

    private int scanBatch(
            final List<String> repositories, final Instant since, final String batch, final PollOptions options) {
        // REST continuation URLs cannot be used as GraphQL cursors. Start a conservative traversal
        // in a separate namespace while retaining initialSince and the transport-independent receipts.
        var pending = store.transaction(state -> {
            for (final var repo : repositories) {
                state.cursors()
                        .keySet()
                        .removeIf(key -> key.equals(repo + "/review-summary-pulls:next")
                                || key.equals(repo + "/review-summary-open-pulls:next")
                                || (key.startsWith(repo + "/pulls/")
                                        && (key.endsWith("/reviews:known") || key.endsWith("/reviews:next"))));
            }
            return repositories.stream()
                    .map(repo ->
                            new ReviewClient.Discovery(repo, state.cursors().get(discoveryKey(repo) + ":next")))
                    .toList();
        });
        int created = 0;
        for (int pages = 0; !pending.isEmpty() && pages < options.pageBudget(); pages++) {
            // The client validates every alias before exposing any page, including partial errors.
            final var result = reviews.pulls(pending);
            final var remaining = new ArrayList<ReviewClient.Discovery>();
            for (final var request : pending) {
                final var repo = request.repository();
                final var page = result.get(repo);
                for (final var pull : page.nodes()) {
                    if ("OPEN".equals(pull.state())) {
                        created += reviews(repo, pull, since, batch, options);
                    }
                }
                // Failed review requests keep this discovery page eligible for replay.
                store.transaction(state -> {
                    saveNext(state, discoveryKey(repo), page.next());
                    return null;
                });
                if (page.next() != null) {
                    remaining.add(new ReviewClient.Discovery(repo, page.next()));
                }
            }
            pending = remaining;
        }
        return created;
    }

    private int reviews(
            final String repo,
            final ReviewClient.Pull initial,
            final Instant since,
            final String batch,
            final PollOptions options) {
        final int number = initial.number();
        final var key = repo + "/review-summary-graphql/pulls/" + number + "/reviews";
        final var after = store.transaction(state -> state.cursors().get(key + ":next"));
        // A resumed backlog must not spend its budget replaying the embedded first page. It has a
        // separate cursor, and progresses when this discovery page is revisited, even at budget=1.
        var pull = after == null ? initial : reviews.reviews(repo, number, after);
        int created = 0;
        for (int pages = 0; pages < options.pageBudget(); pages++) {
            if (!"OPEN".equals(pull.state())) {
                store.transaction(state -> {
                    saveNext(state, key, null);
                    return null;
                });
                break;
            }
            final var page = pull.reviews();
            for (final var item : page.nodes()) {
                if ("PENDING".equals(item.state())
                        || "DRAFT".equals(item.state())
                        || item.submittedAt() == null
                        || item.submittedAt().isBefore(since)) {
                    continue;
                }
                final var user = item.author();
                final var parsed = mentionParser.parse(item.body(), conf.login());
                if (parsed.isEmpty()) {
                    continue;
                }
                if ("fix".equals(parsed.orElseThrow().kind()) && !fix.enabled()) {
                    continue;
                }
                if (!"User".equals(user.type()) || conf.login().equalsIgnoreCase(user.login())) {
                    auditDenied(repo, number, user);
                    continue;
                }
                final var id = item.id();
                final var receipt = repo + "/reviews/" + id;
                if (store.transaction(state -> state.receipts().containsKey(receipt))) {
                    continue;
                }
                if (!authorization.allows(repo, user.id(), user.login())) {
                    auditDenied(repo, number, user);
                    continue;
                }
                final var ref = item.url();
                validateReference(ref, repo, number, id);
                final var invocation = parsed.orElseThrow();
                final var task = MutableTask.create();
                task.repository(repo);
                task.number((long) number);
                task.reference(ref);
                task.kind(TaskKind.valueOf(invocation.kind()));
                task.request(invocation.request());
                task.actorId(user.id());
                task.batchId(batch);
                task.createdAt(clock.instant().toString());
                task.sourceType("review_summary");
                if (task.kind() == TaskKind.fix) {
                    task.recovery(Map.of("fixTarget", fixRequests.pinRequest(task.document())));
                }
                final var taskId = store.transaction(state -> {
                    if (state.receipts().containsKey(receipt)) {
                        return null;
                    }
                    return state.enqueue(receipt, task.document());
                });
                if (taskId != null) {
                    created++;
                    if (audit != null) {
                        task.id(taskId);
                        audit.record("enqueued", task.document());
                    }
                }
            }
            store.transaction(state -> {
                saveNext(state, key, page.next());
                return null;
            });
            if (page.next() == null || pages + 1 >= options.pageBudget()) {
                break;
            }
            pull = reviews.reviews(repo, number, page.next());
        }
        return created;
    }

    private void auditDenied(final String repo, final int number, final ReviewClient.Actor user) {
        if (audit != null) {
            final var identifiers = MutableTask.create();
            identifiers.repository(repo);
            identifiers.number((long) number);
            if (user.id() != null) {
                identifiers.actorId(user.id());
            }
            audit.record("auth-denied", identifiers.document());
        }
    }

    private String discoveryKey(final String repo) {
        return repo + "/review-summary-graphql/pulls";
    }

    private void saveNext(final TaskStore.State state, final String key, final String next) {
        if (next == null) {
            state.cursors().remove(key + ":next");
        } else {
            state.cursors().put(key + ":next", next);
        }
    }

    private void validateReference(final String ref, final String repo, final int number, final String id) {
        final var uri = URI.create(ref);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !("github.com".equalsIgnoreCase(uri.getHost())
                        || URI.create(conf.api()).getHost().equalsIgnoreCase(uri.getHost()))
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getQuery() != null
                || !uri.getRawPath().equalsIgnoreCase("/" + repo + "/pull/" + number)
                || !("pullrequestreview-" + id).equals(uri.getRawFragment())) {
            throw new IllegalStateException("Unexpected review reference");
        }
    }
}
