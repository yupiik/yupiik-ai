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
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * A single comment publication with durable write intent, never an uncertain-write replay.
 * Reconciliation inspects at most 20 pages (1000 comments) in the exact destination thread.
 * An incomplete scan fails closed, including before the first write. No transport retries.
 */
@ApplicationScoped
public class DiscussionPublisher {
    private static final List<String> BINDINGS = List.of(
            "sourceType", "repository", "number", "kind", "discussionId", "commentId", "replyToId", "reference");
    private final DiscussionClient discussions;
    private final TaskStore store;
    private final GithubBotConfiguration configuration;

    public DiscussionPublisher(
            final DiscussionClient discussions, final TaskStore store, final GithubBotConfiguration configuration) {
        this.discussions = discussions;
        this.store = store;
        this.configuration = configuration;
    }

    public String publish(final TaskDocument taskDocument, final String body) {
        return publish(taskDocument, body, () -> true);
    }

    public String publish(final TaskDocument taskDocument, final String body, final BooleanSupplier allowWrite) {
        final var task = taskDocument;
        final var id = required(task.id(), "id");
        final var persisted = store.transaction(state -> state.task(id).document());
        checkBindings(task, persisted);
        if (!"discussion".equals(persisted.sourceType())
                || !Set.of(TaskKind.solve, TaskKind.help, TaskKind.review).contains(persisted.kind())
                || (persisted.kind() == TaskKind.review && !"discussion".equals(persisted.reviewTarget()))) {
            throw new IllegalArgumentException("Only discussion solve/help/advisory review publication is supported");
        }
        final var discussion = discussions.discussion(required(persisted.discussionId(), "discussionId"));
        if (!discussion.repository().equalsIgnoreCase(required(persisted.repository(), "repository"))
                || !String.valueOf(discussion.number()).equals(String.valueOf(persisted.number()))) {
            throw new IllegalArgumentException("Task discussion binding mismatch");
        }
        final var commentId = optional(persisted.commentId(), "commentId");
        final var replyToId = optional(persisted.replyToId(), "replyToId");
        if (commentId == null) {
            if (replyToId != null || !discussion.url().equals(required(persisted.reference(), "reference"))) {
                throw new IllegalArgumentException("Root task destination mismatch");
            }
        } else {
            final var origin = discussions.comment(commentId);
            final var expectedThread = origin.replyToId() == null ? origin.id() : origin.replyToId();
            discussion.validate(origin, origin.replyToId());
            if (!expectedThread.equals(replyToId)
                    || !origin.url().equals(required(persisted.reference(), "reference"))) {
                throw new IllegalArgumentException("Task comment/thread binding mismatch");
            }
            // Never trust a persisted thread ID without checking the actual parent node.
            final var parent = commentId.equals(replyToId) ? origin : discussions.comment(replyToId);
            discussion.validate(parent, null);
        }
        final var marker = "<!-- yca-task:" + id + ":discussion -->";
        if ((persisted.discussionPublishIntent() != null) && !marker.equals(persisted.discussionPublishIntent())) {
            throw new IllegalStateException("Invalid prior discussion intent");
        }
        String after = null;
        boolean complete = false;
        for (int pageNumber = 0; pageNumber < 20; pageNumber++) {
            final var page = replyToId == null
                    ? discussions.comments(discussion.id(), after)
                    : discussions.replies(replyToId, after);
            for (final var candidate : page.nodes()) {
                discussion.validate(candidate, replyToId);
                if (authored(candidate, marker)) {
                    remember(id, persisted, candidate.url());
                    return candidate.url();
                }
            }
            if (page.next() == null) {
                complete = true;
                break;
            }
            after = page.next();
        }
        if (!complete) {
            throw new IllegalStateException(
                    "Discussion reconciliation exceeds 1000 comments; operator reconciliation required");
        }
        if ((persisted.discussionPublishIntent() != null)) {
            throw new IllegalStateException("Uncertain prior discussion publication; no replay permitted");
        }
        if (body == null || body.isBlank() || marker.length() + 1 + body.length() > 30000) {
            throw new IllegalArgumentException(
                    "Discussion comment must be nonblank and at most 30000 characters including marker");
        }
        if (!allowWrite.getAsBoolean()) {
            throw new IllegalStateException("Publication blocked: the requesting user is no longer authorized");
        }
        // Atomic claim: concurrent publishers can reconcile, but only one may perform a mutation.
        store.transaction(state -> {
            final var current = state.task(id);
            checkBindings(persisted, current.document());
            if ((current.discussionPublishIntent() != null)) {
                throw new IllegalStateException(
                        "Discussion publication already claimed; reconcile before another write");
            }
            current.discussionPublishIntent(marker);
            return null;
        });
        final var posted = discussions.addComment(discussion.id(), replyToId, marker + "\n" + body);
        discussion.validate(posted, replyToId);
        if (!authored(posted, marker)) {
            throw new IllegalStateException("Unverified discussion mutation result; reconcile without replay");
        }
        remember(id, persisted, posted.url());
        return posted.url();
    }

    private boolean authored(final DiscussionClient.Comment comment, final String marker) {
        return configuration.login() != null
                && configuration.login().equalsIgnoreCase(comment.author().login())
                && Set.of("User", "Bot").contains(comment.author().type())
                && comment.body().contains(marker);
    }

    private void remember(final String id, final TaskDocument expected, final String url) {
        store.transaction(state -> {
            final var current = state.task(id);
            checkBindings(expected, current.document());
            current.discussionPublishIntent("<!-- yca-task:" + id + ":discussion -->");
            current.discussionPublishedUrl(url);
            return null;
        });
    }

    private void checkBindings(final TaskDocument expected, final TaskDocument actual) {
        if (!Objects.equals(expected.reviewTarget(), actual.reviewTarget())) {
            throw new IllegalArgumentException("Task publication binding changed: reviewTarget");
        }
        if (!Objects.equals(expected.sourceType(), actual.sourceType())) {
            throw new IllegalArgumentException("Task publication binding changed: sourceType");
        }
        if (!Objects.equals(expected.repository(), actual.repository())) {
            throw new IllegalArgumentException("Task publication binding changed: repository");
        }
        if (!Objects.equals(expected.number(), actual.number())) {
            throw new IllegalArgumentException("Task publication binding changed: number");
        }
        if (!Objects.equals(expected.kind(), actual.kind())) {
            throw new IllegalArgumentException("Task publication binding changed: kind");
        }
        if (!Objects.equals(expected.discussionId(), actual.discussionId())) {
            throw new IllegalArgumentException("Task publication binding changed: discussionId");
        }
        if (!Objects.equals(expected.commentId(), actual.commentId())) {
            throw new IllegalArgumentException("Task publication binding changed: commentId");
        }
        if (!Objects.equals(expected.replyToId(), actual.replyToId())) {
            throw new IllegalArgumentException("Task publication binding changed: replyToId");
        }
        if (!Objects.equals(expected.reference(), actual.reference())) {
            throw new IllegalArgumentException("Task publication binding changed: reference");
        }
    }

    private String required(final String value, final String key) {
        final var result = optional(value, key);
        if (result == null) {
            throw new IllegalArgumentException("Missing task field: " + key);
        }
        return result;
    }

    private String optional(final String value, final String key) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("Invalid task field: " + key);
        }
        return value;
    }
}
