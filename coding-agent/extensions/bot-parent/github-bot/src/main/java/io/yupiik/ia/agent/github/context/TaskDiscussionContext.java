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
package io.yupiik.ia.agent.github.context;

import io.yupiik.ia.agent.github.client.DiscussionClient;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Transport-independent, fixed-task authority. Text returned by this reader is untrusted GitHub
 * content, not instructions. URL fields and raw transport metadata are never returned or dereferenced.
 * Links embedded in untrusted text remain plain text.
 * The private lock serializes reads (including network I/O); no task-store lock or transaction is used.
 */
public final class TaskDiscussionContext {
    private static final int STRING_LIMIT = 512 * 1024;
    private static final int RESPONSE_LIMIT = 4 * 1024 * 1024;
    private static final int THREAD_LIMIT = 10000;
    private final GitHubClient github;
    private final GithubDiscussionContext rest;
    private final DiscussionClient discussions;
    private final String repository;
    private final long number;
    private final String discussionId;
    private final String invocationId;
    private final String initialParentId;
    private final boolean discussionSource;
    private final boolean pull;
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> parents = new HashSet<>();
    private final Map<String, Map<Integer, String>> continuations = new HashMap<>();
    private DiscussionClient.Discussion validatedDiscussion;

    public TaskDiscussionContext(final GitHubClient github, final TaskDocument task) {
        this.github = Objects.requireNonNull(github);
        Objects.requireNonNull(task);
        if (task.kind() == TaskKind.review
                && !Set.of("issue", "discussion", "pull_request")
                        .contains(task.reviewTarget() == null ? "" : task.reviewTarget())) {
            throw new IllegalArgumentException("Expected an explicit review target");
        }
        if (task.number() == null) {
            throw new IllegalArgumentException("Missing fixed task number");
        }
        repository = task.repository();
        number = task.number();
        rest = new GithubDiscussionContext(github, repository, number);
        discussions = new DiscussionClient(github);
        discussionSource = task.kind() == TaskKind.review
                ? "discussion".equals(task.reviewTarget())
                : "discussion".equals(task.sourceType());
        discussionId = task.discussionId();
        invocationId = task.commentId();
        initialParentId = task.replyToId() == null ? invocationId : task.replyToId();
        if (discussionSource && (discussionId == null || discussionId.isBlank() || discussionId.length() > 512)) {
            throw new IllegalArgumentException("Missing fixed discussion identity");
        }
        pull = !discussionSource
                && (task.kind() == TaskKind.review
                        ? "pull_request".equals(task.reviewTarget())
                        : task.kind() == TaskKind.fix || pullReference(task.reference()));
    }

    public Object read(final String operation, final int page, final String parentId) {
        if (operation == null || page < 1 || page > 10) {
            throw new IllegalArgumentException("INVALID_CONTEXT_PAGE: pages range from 1 through 10");
        }
        if (parentId != null && !"discussionReplies".equals(operation)) {
            throw new IllegalArgumentException("Parent identity is only valid for discussionReplies");
        }
        lock.lock();
        try {
            final Object result;
            switch (operation) {
                case "pull", "files", "reviews", "reviewComments" -> {
                    require(!discussionSource && pull, "Not a pull request task");
                    result = rest.read(operation, page);
                }
                case "issueComments" -> {
                    require(!discussionSource, "REST is unavailable for Discussion tasks");
                    result = rest.read(operation, page);
                }
                case "issue" -> {
                    require(!discussionSource && page == 1, "Issue requires a non-Discussion task and page 1");
                    result = issue();
                }
                case "discussion", "discussionComments", "discussionReplies" -> {
                    require(discussionSource, "Not a Discussion task");
                    if ("discussion".equals(operation)) {
                        require(page == 1, "Discussion is not paginated");
                        result = discussionEntry(discussion());
                    } else {
                        return comments(operation, page, parentId);
                    }
                }
                default -> throw new IllegalArgumentException("UNKNOWN_CONTEXT_OPERATION");
            }
            bounded(result);
            return result;
        } finally {
            lock.unlock();
        }
    }

    private Object issue() {
        final var raw = github.get("/repos/" + repository + "/issues/" + number, RestResponses.Issue.class);
        final var result = new LinkedHashMap<String, Object>();
        result.put("title", raw.title());
        result.put("body", raw.body());
        result.put("state", raw.state());
        result.put("id", raw.id());
        result.put("node_id", raw.node_id());
        for (final var entry : result.entrySet()) {
            final var field = entry.getKey();
            final var value = entry.getValue();
            if ((value == null && !"body".equals(field))
                    || (value != null && !(value instanceof String) && !(value instanceof Number))) {
                throw new IllegalStateException("Malformed issue field");
            }
        }
        return result;
    }

    private DiscussionClient.Discussion discussion() {
        if (validatedDiscussion == null) {
            final var value = discussions.discussion(discussionId);
            if (!repository.equalsIgnoreCase(value.repository())
                    || number != value.number()
                    || !discussionId.equals(value.id())) {
                throw new IllegalStateException("Discussion does not match fixed task");
            }
            bounded(discussionEntry(value));
            validatedDiscussion = value;
        }
        return validatedDiscussion;
    }

    private Object comments(final String operation, final int page, final String parentId) {
        final var replies = "discussionReplies".equals(operation);
        if (replies) {
            require(
                    parentId != null
                            && (parents.contains(parentId)
                                    || (invocationId != null && parentId.equals(initialParentId))),
                    "Unobserved discussion comment parent");
        }
        final var key = replies ? parentId : "";
        final var observed = continuations.get(key);
        require(
                page == 1 || (observed != null && observed.containsKey(page - 1) && observed.get(page - 1) != null),
                "Previous connection page must be observed first");
        final var after = page == 1 ? null : observed.get(page - 1);
        final var fixed = discussion();
        if (replies && !parents.contains(parentId)) {
            observeInitialThread(fixed);
        }
        final var response = replies ? discussions.replies(parentId, after) : discussions.comments(discussionId, after);
        final var newlyObserved = new HashSet<String>();
        final var entries = response.nodes().stream()
                .map(comment -> {
                    fixed.validate(comment, replies ? parentId : null);
                    if (!replies) {
                        newlyObserved.add(comment.id());
                    }
                    return commentEntry(comment);
                })
                .toList();
        final var result = Map.of(
                "entries",
                entries,
                "page",
                page,
                "complete",
                response.next() == null,
                "pageLimitReached",
                page == 10 && response.next() != null);
        bounded(result);
        if (response.next() != null
                && (response.next().length() > STRING_LIMIT
                        || (observed != null
                                && observed.containsValue(response.next())
                                && !Objects.equals(observed.get(page), response.next())))) {
            throw new IllegalStateException("Invalid repeated connection cursor");
        }
        if (observed != null && observed.containsKey(page) && !Objects.equals(observed.get(page), response.next())) {
            throw new IllegalStateException("Connection changed; continuation not advanced");
        }
        newlyObserved.removeAll(parents);
        if (parents.size() + newlyObserved.size() > THREAD_LIMIT) {
            throw new IllegalStateException("Discussion thread identity limit reached");
        }
        // Commit only after the entire response, identity relationships and bounds have been validated.
        continuations.computeIfAbsent(key, ignored -> new HashMap<>()).put(page, response.next());
        parents.addAll(newlyObserved);
        return result;
    }

    private void observeInitialThread(final DiscussionClient.Discussion fixed) {
        final var parent = discussions.comment(initialParentId);
        fixed.validate(parent, null);
        bounded(commentEntry(parent));
        if (!invocationId.equals(initialParentId)) {
            final var invocation = discussions.comment(invocationId);
            fixed.validate(invocation, initialParentId);
            bounded(commentEntry(invocation));
        }
        if (parents.size() >= THREAD_LIMIT) {
            throw new IllegalStateException("Discussion thread identity limit reached");
        }
        parents.add(initialParentId);
    }

    private Map<String, Object> commentEntry(final DiscussionClient.Comment comment) {
        final var entry = new LinkedHashMap<String, Object>();
        entry.put("id", comment.id());
        entry.put("databaseId", comment.databaseId());
        entry.put("discussionId", comment.discussionId());
        entry.put("replyToId", comment.replyToId());
        entry.put("body", comment.body());
        entry.put("updatedAt", comment.updatedAt().toString());
        entry.put("author", author(comment.author()));
        return entry;
    }

    private Map<String, Object> discussionEntry(final DiscussionClient.Discussion value) {
        return Map.of(
                "id",
                value.id(),
                "repository",
                value.repository(),
                "number",
                value.number(),
                "title",
                value.title(),
                "body",
                value.body(),
                "updatedAt",
                value.updatedAt().toString(),
                "author",
                author(value.author()));
    }

    private Map<String, Object> author(final DiscussionClient.Actor value) {
        final var result = new LinkedHashMap<String, Object>();
        result.put("type", value.type());
        result.put("login", value.login());
        result.put("databaseId", value.databaseId());
        return result;
    }

    private boolean pullReference(final String reference) {
        if (reference == null) {
            return false;
        }
        try {
            final var uri = URI.create(reference);
            return ("/" + repository + "/pull/" + number).equals(uri.getRawPath());
        } catch (final IllegalArgumentException invalid) {
            return false;
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void bounded(final Object value) {
        if (size(value) > RESPONSE_LIMIT) {
            throw new IllegalStateException("Context response exceeds size limit");
        }
    }

    // Conservative JSON-size upper bound, including escaping, without building a second response.
    private static long size(final Object value) {
        if (value instanceof String text) {
            final var bytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > STRING_LIMIT) {
                throw new IllegalStateException("Context string exceeds size limit");
            }
            return 2L + 6L * bytes;
        }
        if (value instanceof Map<?, ?> map) {
            long total = 2;
            for (final var entry : map.entrySet()) {
                total += size(entry.getKey()) + size(entry.getValue()) + 2;
            }
            return total;
        }
        if (value instanceof List<?> list) {
            long total = 2;
            for (final var entry : list) {
                total += size(entry) + 1;
            }
            return total;
        }
        if (value instanceof Number number) {
            return size(number.toString());
        }
        return value == null ? 4 : 5;
    }
}
