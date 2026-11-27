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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import io.yupiik.ia.agent.github.configuration.ContextConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.context.git.GitReadPolicy;
import io.yupiik.ia.agent.github.context.git.TrustedGitStore;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * One fixed task/attempt authority. Adapters never select upstream URLs, credentials or raw API operations.
 * All Git processes target private worker-owned repositories, never the live agent worktree.
 */
public final class ContextService implements AutoCloseable {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_-]+/[A-Za-z0-9_-][A-Za-z0-9_.-]*");
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");

    private static final Set<String> REQUEST_FIELDS = Set.of(
            "operation",
            "repository",
            "argv",
            "page",
            "depth",
            "tags",
            "operationId",
            "revision",
            "parentId",
            "waitMillis");
    private static final Set<String> DISCUSSIONS = Set.of(
            "pull",
            "files",
            "reviews",
            "reviewComments",
            "issueComments",
            "issue",
            "discussion",
            "discussionComments",
            "discussionReplies");
    private final GitHubClient github;
    private final JsonMapper json;
    private final TaskDocument task;
    private final Clock clock;
    private final Instant deadline;
    private final ContextBudget budget;
    private final ContextConfiguration conf;
    private final WorkerOptions.Source source;
    private final TrustedGitStore git;
    private final TaskDiscussionContext discussions;
    private final GitReadPolicy readPolicy = new GitReadPolicy();
    private final Map<String, Grant> grants = new LinkedHashMap<>();
    private final Map<String, Prepared> generations = new HashMap<>();
    private final Map<String, Operation> operations = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Semaphore preparing = new Semaphore(1);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String primary;
    private final AssessmentBase assessment;

    public ContextService(
            final GitHubClient github,
            final JsonMapper json,
            final TaskDocument task,
            final Clock clock,
            final Instant deadline,
            final ContextBudget budget,
            final ContextConfiguration conf,
            final WorkerOptions.Source source,
            final TrustedGitStore git) {
        this.github = github;
        this.json = json;
        this.task = task;
        this.clock = clock;
        this.deadline = deadline;
        this.budget = budget;
        this.conf = conf;
        this.source = source;
        this.git = git;
        this.discussions = new TaskDiscussionContext(github, task);
        final var upstream = repository(task.repository());
        assessment = AssessmentBase.isAssessment(task) ? AssessmentBase.pin(github, task) : null;
        if (task.kind() == TaskKind.solve) {
            final var head = sha(task.strictSolveBaseSha());
            add(upstream, head, head);
            primary = name(upstream);
        } else if (assessment != null) {
            primary = name(upstream);
            if (!assessment.emptyRepository()) {
                add(upstream, assessment.sha(), assessment.sha());
            }
        } else {
            if (task.kind() != TaskKind.fix && !"pull_request".equals(task.reviewTarget())) {
                throw new IllegalArgumentException("Expected an explicit review target");
            }
            final var pull = github.get(
                    "/repos/" + task.repository() + "/pulls/" + number(task.number()), RestResponses.Pull.class);
            final var base = pull.base();
            final var head = pull.head();
            final var baseRepo = base.repo();
            final var headRepo = head.repo();
            if (!id(upstream).equals(id(baseRepo)) || !name(upstream).equalsIgnoreCase(name(baseRepo))) {
                throw new IllegalArgumentException("PR_REPOSITORY_IDENTITY_CHANGED");
            }
            final var verifiedHead = repository(name(headRepo));
            if (!id(verifiedHead).equals(id(headRepo))) {
                throw new IllegalArgumentException("PR_HEAD_REPOSITORY_IDENTITY_CHANGED");
            }
            final var baseSha = sha(base.sha());
            final var headSha = sha(head.sha());
            if (task.kind() == TaskKind.fix
                    && task.strictSolveBaseSha() != null
                    && !task.strictSolveBaseSha().equals(headSha)) {
                throw new IllegalStateException("PINNED_FIX_HEAD_CHANGED");
            }
            add(upstream, baseSha, baseSha);
            add(verifiedHead, headSha, baseSha, name(upstream));
            primary = name(verifiedHead);
        }
    }

    public String primaryRepository() {
        return primary;
    }

    public Map<String, Object> metadata() {
        check();
        if (assessment != null && assessment.emptyRepository()) {
            return Map.of(
                    "scope",
                    "worker-pinned-snapshot",
                    "taskId",
                    task.id(),
                    "repository",
                    primary,
                    "reviewTarget",
                    task.reviewTarget(),
                    "baseBranch",
                    assessment.branch(),
                    "source",
                    "unavailable",
                    "sourceStatus",
                    "EMPTY_REPOSITORY",
                    "localWorktree",
                    false,
                    "expiresAt",
                    deadline.toString());
        }
        final var grant = grant(null);
        final var metadata = new LinkedHashMap<String, Object>();
        metadata.putAll(Map.of(
                "scope",
                "worker-pinned-snapshot",
                "taskId",
                task.id(),
                "repository",
                grant.repository(),
                "headSha",
                grant.sha(),
                "baseSha",
                grant.baseSha(),
                "expiresAt",
                deadline.toString(),
                "source",
                "lazy",
                "localWorktree",
                false,
                "baseRepository",
                grant.baseRepository()));
        metadata.put("attempt", task.attempts());
        metadata.put("runtime", task.runtimeId());
        metadata.put("sourceType", task.sourceType());
        if (task.kind() == TaskKind.review) {
            metadata.put("reviewTarget", task.reviewTarget());
        }
        if (assessment != null) {
            metadata.put("baseBranch", assessment.branch());
        }
        return Collections.unmodifiableMap(metadata);
    }

    /** HTTP and filesystem adapters enter here and share request and response accounting. */
    public Map<String, Object> call(final Map<String, Object> request) {
        check();
        budget.request();
        if (request == null || !REQUEST_FIELDS.containsAll(request.keySet())) {
            throw new IllegalArgumentException("INVALID_CONTEXT_ARGUMENTS");
        }
        final var operation = text(request.get("operation"), "operation");
        final var repo = optionalText(request.get("repository"));
        final Set<String> arguments =
                switch (operation) {
                    case "git" -> Set.of("operation", "repository", "argv");
                    case "prepare_source" ->
                        Set.of("operation", "repository", "depth", "tags", "revision", "waitMillis");
                    case "operation_status" -> Set.of("operation", "operationId", "waitMillis");
                    case "cancel" -> Set.of("operation", "operationId");
                    case "pull",
                            "files",
                            "reviews",
                            "reviewComments",
                            "issueComments",
                            "issue",
                            "discussion",
                            "discussionComments" -> Set.of("operation", "repository", "page");
                    case "discussionReplies" -> Set.of("operation", "repository", "page", "parentId");
                    case "summary", "repositories", "request_repository" -> Set.of("operation", "repository");
                    default -> throw new IllegalArgumentException("UNKNOWN_CONTEXT_OPERATION");
                };
        if (request.entrySet().stream().anyMatch(it -> it.getValue() != null && !arguments.contains(it.getKey()))) {
            throw new IllegalArgumentException("INVALID_CONTEXT_ARGUMENTS");
        }
        final Object result;
        if (DISCUSSIONS.contains(operation)) {
            if (repo != null && !repo.equalsIgnoreCase(task.repository())) {
                throw new IllegalArgumentException("DISCUSSION_SCOPE_DENIED");
            }
            result = Map.of(
                    "data",
                    discussions.read(operation, integer(request.get("page"), 1), optionalText(request.get("parentId"))),
                    "repository",
                    task.repository(),
                    "observedAt",
                    clock.instant().toString());
        } else {
            result = switch (operation) {
                case "summary" -> metadata();
                case "repositories" -> repositories();
                case "request_repository" -> requestRepository(repo);
                case "prepare_source" ->
                    emptySource(repo)
                            ? Map.of("status", "EMPTY_REPOSITORY", "sourceAvailable", false)
                            : prepare(
                                    grant(repo),
                                    integer(request.get("depth"), 1),
                                    bool(request.get("tags")),
                                    revision(request.get("revision")),
                                    waitMillis(request.get("waitMillis")));
                case "operation_status" ->
                    operation(text(request.get("operationId"), "operationId"), waitMillis(request.get("waitMillis")));
                case "cancel" -> cancel(text(request.get("operationId"), "operationId"));
                case "git" ->
                    emptySource(repo)
                            ? Map.of("status", "EMPTY_REPOSITORY", "sourceAvailable", false)
                            : read(grant(repo), argv(request.get("argv")));
                default -> throw new IllegalArgumentException("UNKNOWN_CONTEXT_OPERATION");
            };
        }
        final var response = Map.<String, Object>of("ok", true, "body", result);
        final int bytes = json.toString(response).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 512 * 1024) {
            throw new IllegalStateException("CONTEXT_RESPONSE_TOO_LARGE");
        }
        budget.response(bytes);
        if (!"cancel".equals(operation)) {
            check();
        }
        return response;
    }

    private Object read(final Grant grant, final List<String> argv) {
        readPolicy.validate(argv);
        final Prepared prepared;
        lock.lock();
        try {
            prepared = generations.values().stream()
                    .filter(it -> it.repository().equals(grant.repository())
                            && it.view().headSha().equals(grant.sha()))
                    .max(Comparator.comparingInt(it -> it.view().depth()))
                    .orElse(null);
        } finally {
            lock.unlock();
        }
        if (prepared == null) {
            return Map.of(
                    "status",
                    "SOURCE_REQUIRED",
                    "hint",
                    "Call github_context prepare_source, then operation_status until complete.");
        }
        try {
            // The store revalidates the original immutable argv immediately before execution.
            // BASE is normalized by the policy only in revision positions, never in grep/format text.
            final var result = git.read(prepared.view(), argv);
            return Map.of(
                    "scope",
                    "worker-pinned-snapshot",
                    "repository",
                    grant.repository(),
                    "headSha",
                    grant.sha(),
                    "generation",
                    prepared.view().id(),
                    "exitCode",
                    result.exitCode(),
                    "stdout",
                    result.stdout(),
                    "truncated",
                    result.truncated(),
                    "historyDepth",
                    prepared.view().depth(),
                    "hint",
                    result.exitCode() == 128
                            ? "Object/history may be outside this shallow generation. Request bounded deeper history or an approved BASE generation; no two-dot fallback is used."
                            : "This is not the agent's edited worktree. Use local Git for status, staged changes and builds.");
        } catch (final IOException failure) {
            throw new IllegalStateException("GIT_READ_UNAVAILABLE");
        }
    }

    private Object prepare(
            final Grant grant, final int depth, final boolean tags, final String revision, final int waitMillis) {
        if (depth < 1 || depth > source.maxDepth()) {
            throw new IllegalArgumentException("SOURCE_DEPTH_OUT_OF_BOUNDS");
        }
        final var id = UUID.randomUUID().toString();
        lock.lock();
        try {
            if (operations.size() >= 32 || !preparing.tryAcquire()) {
                throw new IllegalStateException("SOURCE_PREPARATION_BUSY_OR_LIMITED");
            }
            final var op = new Operation();
            operations.put(id, op);
            op.future = CompletableFuture.runAsync(
                    () -> {
                        try {
                            check();
                            final var fetchRepository =
                                    "BASE".equals(revision) ? grant.baseRepository() : grant.repository();
                            final var expected = grant(grant.repository());
                            final var baseGrant = grant(grant.baseRepository());
                            final var separateBase = !expected.repository().equalsIgnoreCase(baseGrant.repository());
                            if (!expected.id().equals(id(repository(expected.repository())))) {
                                throw new IllegalStateException("REPOSITORY_IDENTITY_CHANGED");
                            }
                            if (separateBase && !baseGrant.id().equals(id(repository(baseGrant.repository())))) {
                                throw new IllegalStateException("REPOSITORY_IDENTITY_CHANGED");
                            }
                            final var view = git.prepare(
                                    "https://github.com/" + fetchRepository + ".git",
                                    "BASE".equals(revision) ? grant.baseSha() : grant.sha(),
                                    depth,
                                    tags,
                                    "BASE".equals(revision) ? null : grant.baseSha(),
                                    "https://github.com/" + baseGrant.repository() + ".git");
                            if (!expected.id().equals(id(repository(expected.repository())))) {
                                throw new IllegalStateException("REPOSITORY_IDENTITY_CHANGED");
                            }
                            if (separateBase && !baseGrant.id().equals(id(repository(baseGrant.repository())))) {
                                throw new IllegalStateException("REPOSITORY_IDENTITY_CHANGED");
                            }
                            check();
                            lock.lock();
                            try {
                                if (!op.cancelled) {
                                    final var result = new Prepared(grant.repository(), view);
                                    generations.put(view.id(), result);
                                    op.result = Map.of(
                                            "repository",
                                            grant.repository(),
                                            "headSha",
                                            view.headSha(),
                                            "depth",
                                            view.depth(),
                                            "generation",
                                            view.id(),
                                            "gitPath",
                                            "git/" + view.id() + ".git",
                                            "revision",
                                            revision);
                                }
                            } finally {
                                lock.unlock();
                            }
                        } catch (final IOException | RuntimeException failure) {
                            op.error = "SOURCE_PREPARATION_FAILED";
                        } finally {
                            op.complete = true;
                            preparing.release();
                        }
                    },
                    executor);
        } finally {
            lock.unlock();
        }
        // Preserve the asynchronous contract unless the caller explicitly opts into waiting.
        if (waitMillis == 0) {
            return Map.of("operationId", id, "status", "pending");
        }
        final var result = new LinkedHashMap<>(operation(id, waitMillis));
        result.put("operationId", id);
        return result;
    }

    private Map<String, Object> operation(final String id, final int waitMillis) {
        final Operation operation;
        lock.lock();
        try {
            operation = operations.get(id);
            if (operation == null) {
                throw new IllegalArgumentException("UNKNOWN_CONTEXT_OPERATION_ID");
            }
        } finally {
            lock.unlock();
        }
        // Waiting must not hold the publication/cancellation lock.
        if (waitMillis > 0 && !operation.complete && !operation.cancelled) {
            final long boundedWait = Math.min(
                    waitMillis,
                    Math.max(0, Duration.between(clock.instant(), deadline).toMillis()));
            try {
                operation.future.get(boundedWait, TimeUnit.MILLISECONDS);
            } catch (final TimeoutException waiting) {
                // A slow preparation remains asynchronous.
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("CONTEXT_WAIT_INTERRUPTED");
            } catch (final ExecutionException failure) {
                throw new IllegalStateException("SOURCE_PREPARATION_FAILED");
            }
            check();
        }
        lock.lock();
        try {
            if (operation.cancelled) {
                return Map.of("status", "cancelled");
            }
            if (!operation.complete) {
                return Map.of("status", "pending");
            }
            if (operation.error != null) {
                return Map.of("status", "failed", "error", operation.error);
            }
            return Map.of("status", "complete", "source", operation.result);
        } finally {
            lock.unlock();
        }
    }

    private Object cancel(final String id) {
        lock.lock();
        try {
            final var operation = operations.get(id);
            if (operation == null) {
                throw new IllegalArgumentException("UNKNOWN_CONTEXT_OPERATION_ID");
            }
            operation.cancelled = true;
            // Cancellation of a preparation revokes the whole attempt service, including admitted Git processes.
            // CompletableFuture.cancel alone does not interrupt an underlying ProcessBuilder process.
            closed.set(true);
            executor.shutdownNow();
            closeGit();
            return Map.of("status", "cancelled");
        } finally {
            lock.unlock();
        }
    }

    private Object repositories() {
        lock.lock();
        try {
            return grants.values().stream()
                    .map(it -> Map.of(
                            "repository",
                            it.repository(),
                            "repositoryId",
                            it.id(),
                            "headSha",
                            it.sha(),
                            "baseSha",
                            it.baseSha(),
                            "primary",
                            it.repository().equals(primary)))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    private Object requestRepository(final String repository) {
        if (repository == null) {
            throw new IllegalArgumentException("REPOSITORY_REQUIRED");
        }
        final var requested = key(repository);
        if (emptySource(repository)) {
            return Map.of("status", "EMPTY_REPOSITORY", "sourceAvailable", false);
        }
        final var allowed = new HashSet<String>();
        for (final var name : conf.repositories().split(",")) {
            if (!name.isBlank()) {
                allowed.add(key(name.trim()));
            }
        }
        lock.lock();
        try {
            if (grants.containsKey(requested)) {
                return Map.of(
                        "status", "granted", "repository", grants.get(requested).repository());
            }
            if (!allowed.contains(requested) || grants.size() >= 16) {
                return Map.of("status", "denied", "reason", "Host policy does not grant this exact repository");
            }
            // Resolve canonical identity and the host-approved default ref once. Caller cannot request arbitrary refs.
            final var metadata = repository(repository);
            final var branch = text(metadata.default_branch(), "default_branch");
            final var commit = github.get(
                    "/repos/" + name(metadata) + "/commits/"
                            + URLEncoder.encode(branch, StandardCharsets.UTF_8).replace("+", "%20"),
                    RestResponses.Commit.class);
            final var sha = sha(commit.sha());
            add(metadata, sha, sha);
            return Map.of(
                    "status", "granted", "repository", name(metadata), "repositoryId", id(metadata), "headSha", sha);
        } finally {
            lock.unlock();
        }
    }

    /** Called only after Git-audience authentication. Unknown generations never become filesystem paths. */
    public TrustedGitStore.HttpResult git(
            final String generation, final String method, final String query, final byte[] body, final String protocol)
            throws IOException {
        check();
        budget.request();
        final Prepared prepared;
        lock.lock();
        try {
            prepared = generations.get(generation);
        } finally {
            lock.unlock();
        }
        if (prepared == null) {
            throw new IllegalArgumentException("UNKNOWN_SOURCE_GENERATION");
        }
        final var result = git.http(prepared.view(), method, query, body, protocol);
        budget.response(result.body().length);
        check();
        return result;
    }

    /** Reserves the file-backed binary response before the transport can expose any bytes. */
    public TrustedGitStore.HttpStream gitStream(
            final String generation, final String method, final String query, final byte[] body, final String protocol)
            throws IOException {
        check();
        budget.request();
        final Prepared prepared;
        lock.lock();
        try {
            prepared = generations.get(generation);
        } finally {
            lock.unlock();
        }
        if (prepared == null) {
            throw new IllegalArgumentException("UNKNOWN_SOURCE_GENERATION");
        }
        final var result = git.httpStream(prepared.view(), method, query, body, protocol);
        try {
            budget.response(result.length());
            check();
            return result;
        } catch (final RuntimeException failure) {
            result.close();
            throw failure;
        }
    }

    private Grant grant(final String repository) {
        lock.lock();
        try {
            final var grant = grants.get(key(repository == null ? primary : repository));
            if (grant == null) {
                throw new IllegalArgumentException(
                        "REPOSITORY_NOT_GRANTED: request an exact host-approved repository first");
            }
            return grant;
        } finally {
            lock.unlock();
        }
    }

    private boolean emptySource(final String repository) {
        return assessment != null
                && assessment.emptyRepository()
                && (repository == null || primary.equalsIgnoreCase(repository));
    }

    private void add(final RestResponses.Repository repository, final String sha, final String baseSha) {
        add(repository, sha, baseSha, name(repository));
    }

    private void add(
            final RestResponses.Repository repository,
            final String sha,
            final String baseSha,
            final String baseRepository) {
        final var name = name(repository);
        grants.put(key(name), new Grant(id(repository), name, sha, baseSha, baseRepository));
    }

    private RestResponses.Repository repository(final String name) {
        key(name);
        final var result = github.get("/repos/" + name, RestResponses.Repository.class);
        if (!name(result).equalsIgnoreCase(name) || Boolean.TRUE.equals(result.archived())) {
            throw new IllegalArgumentException("REPOSITORY_IDENTITY_UNAVAILABLE");
        }
        id(result);
        return result;
    }

    private static String key(final String repository) {
        if (repository == null || !REPOSITORY_NAME.matcher(repository).matches()) {
            throw new IllegalArgumentException("INVALID_REPOSITORY_NAME");
        }
        return repository.toLowerCase(Locale.ROOT);
    }

    private static String id(final RestResponses.Repository value) {
        return Long.toString(number(value.id()));
    }

    private static String name(final RestResponses.Repository value) {
        final var name = text(value.full_name(), "full_name");
        key(name);
        return name;
    }

    private static String sha(final Object value) {
        if (!(value instanceof String sha) || !COMMIT_SHA.matcher(sha).matches()) {
            throw new IllegalArgumentException("INVALID_PINNED_SHA");
        }
        return sha;
    }

    private static long number(final Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("Expected positive integral identity");
        }
        final long number;
        try {
            number = new BigDecimal(value.toString()).longValueExact();
        } catch (final ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Expected positive integral identity");
        }
        if (number < 1) {
            throw new IllegalArgumentException("Expected positive integral identity");
        }
        return number;
    }

    private static int integer(final Object value, final int fallback) {
        final long integer = value == null ? fallback : number(value);
        if (integer > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Context integer exceeds its bound");
        }
        return (int) integer;
    }

    private static boolean bool(final Object value) {
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException("Expected boolean flag");
        }
        return flag;
    }

    private static int waitMillis(final Object value) {
        if (value == null) {
            return 0;
        }
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("CONTEXT_WAIT_OUT_OF_BOUNDS");
        }
        try {
            final int wait = new BigDecimal(value.toString()).intValueExact();
            if (wait >= 0 && wait <= 1000) {
                return wait;
            }
        } catch (final ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("CONTEXT_WAIT_OUT_OF_BOUNDS");
        }
        throw new IllegalArgumentException("CONTEXT_WAIT_OUT_OF_BOUNDS");
    }

    private static String revision(final Object value) {
        final var revision = value == null ? "HEAD" : text(value, "revision");
        if (!Set.of("HEAD", "BASE").contains(revision)) {
            throw new IllegalArgumentException("Only host-pinned HEAD or BASE revisions are allowed");
        }
        return revision;
    }

    private static String optionalText(final Object value) {
        return value == null ? null : text(value, "repository");
    }

    private static String text(final Object value, final String name) {
        if (!(value instanceof String text)
                || text.isBlank()
                || text.length() > 256
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return text;
    }

    private static List<String> argv(final Object value) {
        if (!(value instanceof List<?> raw) || raw.size() > 128) {
            throw new IllegalArgumentException("ARGV_ARRAY_REQUIRED");
        }
        final var result = new ArrayList<String>();
        for (final var arg : raw) {
            if (!(arg instanceof String text)) {
                throw new IllegalArgumentException("ARGV_STRINGS_REQUIRED");
            }
            result.add(text);
        }
        return result;
    }

    private static Map<String, Object> object(final Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected context object");
        }
        final var result = new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> {
            if (k instanceof String key) {
                result.put(key, v);
            }
        });
        return result;
    }

    public void check() {
        if (closed.get() || !clock.instant().isBefore(deadline)) {
            throw new IllegalStateException("CONTEXT_REVOKED_OR_EXPIRED");
        }
    }

    @Override
    public void close() {
        closed.set(true);
        executor.shutdownNow();
        closeGit();
    }

    private void closeGit() {
        try {
            git.close();
        } catch (final IOException failure) {
            throw new UncheckedIOException("Private Git context cleanup failed", failure);
        }
    }

    private record Grant(String id, String repository, String sha, String baseSha, String baseRepository) {}

    private record Prepared(String repository, TrustedGitStore.View view) {}

    private static final class Operation {
        private volatile CompletableFuture<Void> future;
        private volatile boolean complete;
        private volatile boolean cancelled;
        private volatile String error;
        private volatile Object result;
    }
}
