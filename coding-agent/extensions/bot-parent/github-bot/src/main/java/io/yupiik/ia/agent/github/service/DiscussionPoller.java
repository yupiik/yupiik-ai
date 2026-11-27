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
import io.yupiik.ia.agent.github.client.DiscussionClient;
import io.yupiik.ia.agent.github.client.GitHubClient;
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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Opt-in discussion mentions. Discovery, every discussion's comments, and every top-level
 * comment's replies have independent durable continuations. Each connection gets at most
 * min(pageBudget, 20) pages of 50 nodes per poll. There is intentionally no global request bound:
 * every retained discussion/thread is visited, so a large discovery backlog cannot starve replies.
 * Completed traversals restart at the beginning; the initial lower bound never advances.
 * Receipts survive task deletion. Network operations never hold a store transaction.
 */
@ApplicationScoped
public class DiscussionPoller {
    private static final Logger LOGGER = Logger.getLogger(DiscussionPoller.class.getName());
    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]*");
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final MentionParser mentionParser;
    private final GithubBotConfiguration conf;
    private final TaskStore store;
    private final GitHubClient github;
    private final Clock clock;
    private final BotAudit audit;
    private final TeamAuthorization authorization;
    private final DiscussionClient discussions;

    public DiscussionPoller(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit,
            final TeamAuthorization authorization,
            final DiscussionClient discussions,
            final MentionParser mentionParser) {
        this.mentionParser = mentionParser;
        this.conf = conf;
        this.store = store;
        this.github = github;
        this.clock = clock;
        this.audit = audit;
        this.authorization = authorization;
        this.discussions = discussions;
    }

    public int poll(final List<String> repos, final PollOptions options) {
        if (!options.discussions().enabled()) {
            return 0;
        }
        if (options.pageBudget() <= 0) {
            throw new IllegalArgumentException("Positive page budget required");
        }
        final var allowed = Arrays.stream(conf.authorization().userIds().split(","))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .collect(Collectors.toSet());
        if ((allowed.isEmpty() && !conf.authorization().allowRepositoryWriters())
                || allowed.stream().anyMatch(it -> !POSITIVE_ID.matcher(it).matches())) {
            throw new IllegalStateException("Authorized immutable numeric user IDs required");
        }
        final var selected = List.copyOf(repos).stream().distinct().toList();
        for (final var repo : selected) {
            if (!REPOSITORY_NAME.matcher(repo).matches()) {
                throw new IllegalArgumentException("Expected owner/repository");
            }
        }
        store.initialize();
        final var path = Path.of(conf.home()).toAbsolutePath().normalize().resolve("discussion-poll.lock");
        final var local = LOCKS.computeIfAbsent(path, ignored -> new ReentrantLock());
        local.lock();
        try (final var channel = FileChannel.open(
                        path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                final var lock = channel.lock()) {
            final var since = Instant.parse(store.transaction(state -> String.valueOf(state.cursors()
                            .computeIfAbsent(
                                    "discussion:initialSince",
                                    ignored -> "now".equals(options.since())
                                            ? clock.instant().toString()
                                            : Instant.parse(options.since()).toString()))))
                    .minusSeconds(300);
            final var batch = UUID.randomUUID().toString();
            int created = 0;
            for (final var repo : selected) {
                if (!discussions.enabled(repo)) {
                    LOGGER.info(() -> "Discussions are not enabled for " + repo + "; skipping discussion polling");
                    continue;
                }
                discover(repo, options);
                for (final var id : known(repo + "/discussion:known/")) {
                    final var discussion = discussions.discussion(id);
                    validateRepository(repo, discussion);
                    created += enqueue(discussion, null, since, batch);
                    created += comments(discussion, since, batch, options);
                    for (final var commentId : known(repo + "/discussion:threads/" + id + "/")) {
                        created += replies(discussion, commentId, since, batch, options);
                    }
                }
            }
            return created;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            local.unlock();
        }
    }

    private void discover(final String repo, final PollOptions options) {
        final var key = repo + "/discussion:discovery";
        String after = continuation(key);
        for (int pageNumber = 0; pageNumber < budget(options); pageNumber++) {
            final var page = discussions.discussions(repo, after);
            for (final var discussion : page.nodes()) {
                validateRepository(repo, discussion);
            }
            store.transaction(state -> {
                for (final var discussion : page.nodes()) {
                    state.cursors().put(repo + "/discussion:known/" + discussion.id(), discussion.id());
                }
                saveNext(state, key, page.next());
                return null;
            });
            if (page.next() == null) {
                break;
            }
            after = page.next();
        }
    }

    private int comments(
            final DiscussionClient.Discussion discussion,
            final Instant since,
            final String batch,
            final PollOptions options) {
        final var key = discussion.repository() + "/discussion:comments/" + discussion.id();
        String after = continuation(key);
        int created = 0;
        for (int pageNumber = 0; pageNumber < budget(options); pageNumber++) {
            final var page = discussions.comments(discussion.id(), after);
            for (final var comment : page.nodes()) {
                discussion.validate(comment, null);
                created += enqueue(discussion, comment, since, batch);
            }
            // Register threads before advancing the comments cursor, even when reply reads later fail.
            store.transaction(state -> {
                for (final var comment : page.nodes()) {
                    state.cursors()
                            .put(
                                    discussion.repository() + "/discussion:threads/" + discussion.id() + "/"
                                            + comment.id(),
                                    comment.id());
                }
                saveNext(state, key, page.next());
                return null;
            });
            if (page.next() == null) {
                break;
            }
            after = page.next();
        }
        return created;
    }

    private int replies(
            final DiscussionClient.Discussion discussion,
            final String commentId,
            final Instant since,
            final String batch,
            final PollOptions options) {
        final var key = discussion.repository() + "/discussion:replies/" + commentId;
        String after = continuation(key);
        int created = 0;
        for (int pageNumber = 0; pageNumber < budget(options); pageNumber++) {
            final var page = discussions.replies(commentId, after);
            for (final var reply : page.nodes()) {
                discussion.validate(reply, commentId);
                created += enqueue(discussion, reply, since, batch);
            }
            store.transaction(state -> {
                saveNext(state, key, page.next());
                return null;
            });
            if (page.next() == null) {
                break;
            }
            after = page.next();
        }
        return created;
    }

    private int enqueue(
            final DiscussionClient.Discussion discussion,
            final DiscussionClient.Comment comment,
            final Instant since,
            final String batch) {
        final var body = comment == null ? discussion.body() : comment.body();
        final var updated = comment == null ? discussion.updatedAt() : comment.updatedAt();
        final var parsed = mentionParser.parse(body, conf.login());
        if (updated.isBefore(since)
                || parsed.isEmpty()
                || !Set.of("help", "solve", "review")
                        .contains(parsed.orElseThrow().kind())) {
            return 0;
        }
        final var actor = comment == null ? discussion.author() : comment.author();
        if (!"User".equals(actor.type())
                || actor.databaseId() == null
                || conf.login().equalsIgnoreCase(actor.login())
                || !authorization.allows(discussion.repository(), actor.databaseId(), actor.login())) {
            if (audit != null) {
                final var event = MutableTask.create();
                event.repository(discussion.repository());
                event.number((long) discussion.number());
                if (actor.databaseId() != null) {
                    event.actorId(actor.databaseId());
                }
                audit.record("auth-denied", event.document());
            }
            return 0;
        }
        final var receipt = discussion.repository() + "/discussion:invocation/"
                + (comment == null ? discussion.id() : comment.id());
        final var invocation = parsed.orElseThrow();
        final var task = MutableTask.create();
        task.repository(discussion.repository());
        task.number((long) discussion.number());
        task.reference(comment == null ? discussion.url() : comment.url());
        task.sourceType("discussion");
        task.discussionId(discussion.id());
        if (comment != null) {
            task.commentId(comment.id());
            task.replyToId(comment.replyToId() == null ? comment.id() : comment.replyToId());
        }
        task.discussionTitle(discussion.title());
        task.discussionBody(discussion.body());
        task.discussionContext("Discussion: " + discussion.title() + "\n" + discussion.url() + "\n\n"
                + discussion.body() + (comment == null ? "" : "\n\nInvocation comment:\n" + body));
        task.kind(TaskKind.valueOf(invocation.kind()));
        if (task.kind() == TaskKind.review) {
            task.reviewTarget("discussion");
        }
        task.request(invocation.request());
        task.actorId(actor.databaseId());
        task.batchId(batch);
        task.createdAt(clock.instant().toString());
        final var id = store.transaction(
                state -> state.receipts().containsKey(receipt) ? null : state.enqueue(receipt, task.document()));
        if (id == null) {
            return 0;
        }
        if (audit != null) {
            task.id(id);
            audit.record("enqueued", task.document());
        }
        return 1;
    }

    private void validateRepository(final String repo, final DiscussionClient.Discussion discussion) {
        final var host = URI.create(discussion.url()).getHost();
        if (!repo.equalsIgnoreCase(discussion.repository())
                || !("github.com".equalsIgnoreCase(host)
                        || URI.create(conf.api()).getHost().equalsIgnoreCase(host))) {
            throw new IllegalStateException("Discussion repository or host mismatch");
        }
    }

    private List<String> known(final String prefix) {
        return store.transaction(state -> state.cursors().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(entry -> String.valueOf(entry.getValue()))
                .sorted()
                .toList());
    }

    private int budget(final PollOptions options) {
        return Math.min(options.pageBudget(), 20);
    }

    private String continuation(final String key) {
        return store.transaction(state -> (String) state.cursors().get(key + ":next"));
    }

    private void saveNext(final TaskStore.State state, final String key, final String next) {
        if (next == null) {
            state.cursors().remove(key + ":next");
        } else {
            state.cursors().put(key + ":next", next);
        }
    }
}
