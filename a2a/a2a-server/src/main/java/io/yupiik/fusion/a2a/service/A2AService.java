/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.service;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

import io.yupiik.fusion.a2a.api.A2AExecutor;
import io.yupiik.fusion.a2a.api.AgentRequest;
import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.configuration.A2ADurations;
import io.yupiik.fusion.a2a.model.A2ATimestamps;
import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.ListTaskPushNotificationConfigsResponse;
import io.yupiik.fusion.a2a.model.ListTasksResponse;
import io.yupiik.fusion.a2a.model.SendMessageConfiguration;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.a2a.model.SendMessageResponse;
import io.yupiik.fusion.a2a.model.Task;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.a2a.protocol.A2AProtocol;
import io.yupiik.fusion.a2a.protocol.A2AStream;
import io.yupiik.fusion.a2a.protocol.SseBus;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The A2A operations, independent of any binding.
 * <p>
 * {@link io.yupiik.fusion.a2a.protocol.A2AJSONRPCProtocol} and
 * {@link io.yupiik.fusion.a2a.protocol.A2ARestProtocol} are thin adapters on top of it, which is what makes the two
 * bindings functionally equivalent - as section 5.1 of the specification requires - by construction.
 */
@ApplicationScoped
public class A2AService {
    /** Trusted identity supplied by authentication middleware, never by client metadata. */
    public static final String CALLER_ATTRIBUTE = "io.yupiik.fusion.a2a.caller";

    private final String caller;

    /**
     * Default page size of {@code ListTasks}, as the specification states.
     */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * Maximum page size of {@code ListTasks}, as the specification states.
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * What {@code fusion.a2a.taskTimeout} means when it is unset - or set to something which is not a positive
     * duration.
     */
    private static final Duration DEFAULT_TASK_TIMEOUT = Duration.ofSeconds(300);

    private final Logger logger = Logger.getLogger(A2AService.class.getName());

    private final A2ATasks tasks;
    private final AgentCardService cards;
    private final List<A2AExecutor> executors;
    private final boolean streamingEnabled;
    private final boolean pushEnabled;
    private final Duration taskTimeout;

    public A2AService(
            final A2ATasks tasks,
            final AgentCardService cards,
            final A2AConfiguration configuration,
            final List<A2AExecutor> executors) {
        this.tasks = tasks;
        this.caller = null;
        this.cards = cards;
        this.executors = executors == null ? List.of() : executors;
        this.streamingEnabled = configuration == null || configuration.streaming();
        this.pushEnabled = configuration == null || configuration.pushNotifications();
        // a timeout of zero would answer before the executor even ran, and orTimeout refuses one outright
        this.taskTimeout = configuration == null
                ? DEFAULT_TASK_TIMEOUT
                : A2ADurations.positive(configuration.taskTimeout(), DEFAULT_TASK_TIMEOUT);
    }

    private A2AService(final A2AService source, final String caller) {
        this.tasks = source.tasks;
        this.cards = source.cards;
        this.executors = source.executors;
        this.streamingEnabled = source.streamingEnabled;
        this.pushEnabled = source.pushEnabled;
        this.taskTimeout = source.taskTimeout;
        this.caller = caller;
    }

    /** An immutable caller-scoped view, safe to retain across asynchronous work. */
    public A2AService forCaller(final Request request) {
        final var identity = request == null ? null : request.attribute(CALLER_ATTRIBUTE, String.class);
        if (request != null && request.header("Authorization") != null && (identity == null || identity.isBlank())) {
            throw new JsonRpcException(
                    -32000, "Authentication middleware did not supply an A2A caller identity", null, null);
        }
        return Objects.equals(identity, caller) ? this : new A2AService(this, identity);
    }

    private A2ATask requireTask(final String id) {
        return tasks.find(id)
                .filter(it -> Objects.equals(caller, it.owner()))
                .orElseThrow(() -> A2AErrors.taskNotFound(id));
    }

    /**
     * {@code SendMessage}: hands the message to an executor and answers either the task tracking it or a direct
     * message.
     *
     * @param request the message and how to answer it.
     * @param http    the underlying HTTP request, it can be {@code null}.
     * @return the answer, once the task settled - or as soon as it exists when
     * {@link SendMessageConfiguration#returnImmediately()} is set.
     */
    public CompletionStage<SendMessageResponse> sendMessage(final SendMessageRequest request, final Request http) {
        final var exchange = start(request, http);
        final var turn = exchange.turn();
        final var immediate =
                request.configuration() != null && request.configuration().returnImmediately();
        final var promise = immediate ? turn.firstAction() : turn.settled();
        final var historyLength = historyLength(request.configuration());

        run(exchange, true);

        if (immediate && !promise.isDone()) {
            return CompletableFuture.completedFuture(
                    SendMessageResponse.of(exchange.task().snapshot(historyLength, true)));
        }
        return promise.copy() // so the timeout below never pollutes the state of the task itself
                .orTimeout(taskTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((response, error) -> {
                    if (response != null) {
                        return truncate(response, historyLength);
                    }
                    final var cause = A2AProtocol.unwrap(error);
                    if (cause instanceof JsonRpcException jre) {
                        throw jre;
                    }
                    // A blocking SendMessage cannot successfully return a nonterminal, noninterrupted task.
                    // Keep the running task observable, but fail this request rather than changing its contract.
                    throw new JsonRpcException(
                            A2AErrors.INTERNAL_ERROR,
                            cause instanceof TimeoutException
                                    ? "SendMessage did not complete within the configured task timeout"
                                    : "SendMessage execution failed",
                            Map.of("taskId", exchange.task().id()),
                            cause);
                });
    }

    /**
     * {@code SendStreamingMessage}: same as {@link #sendMessage} but the events are pushed as they happen.
     *
     * @param request the message and how to answer it.
     * @param http    the underlying HTTP request, it can be {@code null}.
     * @param framing how to frame the events, it depends on the binding.
     * @return the stream to answer with, it is already registered on the task so nothing is lost.
     */
    public A2AStream sendStreamingMessage(
            final SendMessageRequest request, final Request http, final Function<SseBus, A2AStream> framing) {
        requireStreaming();
        final var exchange = start(request, http);
        final var stream = tasks.newStream(exchange.task(), framing);
        run(exchange, false);
        return stream;
    }

    /**
     * {@code SubscribeToTask}: watches a task which is already running.
     *
     * @param tenant  the routing identifier the client echoed.
     * @param id      the task to watch.
     * @param framing how to frame the events.
     * @return the stream to answer with, its first event is the current state of the task.
     */
    public A2AStream subscribeToTask(final String tenant, final String id, final Function<SseBus, A2AStream> framing) {
        requireStreaming();
        validateTenant(tenant);
        final var task = requireTask(id);
        final var state = task.status().state();
        if (state.isTerminal()) {
            throw A2AErrors.unsupportedOperation(
                    "Task '" + id + "' already reached a terminal state, there is nothing left to stream",
                    Map.of("taskId", id, "state", String.valueOf(state)));
        }
        final var stream = tasks.newStream(task, framing);
        stream.emitInitial(task.snapshot(null, true));
        return stream;
    }

    /**
     * {@code GetTask}: the current state of a task.
     */
    public Task getTask(final String tenant, final String id, final Integer historyLength) {
        validateTenant(tenant);
        validateHistoryLength(historyLength);
        return requireTask(id).snapshot(historyLength, true);
    }

    /**
     * {@code CancelTask}: asks the executor to stop and answers the updated task.
     *
     * @throws JsonRpcException {@code TaskNotCancelableError} when the task already ended.
     */
    public Task cancelTask(final String tenant, final String id, final Map<String, Object> metadata) {
        validateTenant(tenant);
        final var task = requireTask(id);
        final var state = task.status().state();
        if (state.isTerminal()) {
            throw A2AErrors.taskNotCancelable(id, state);
        }

        final var updater = tasks.updaterFor(task);
        final var executor = task.executor();
        final var request = task.request();
        if (executor != null && request != null) {
            try {
                executor.cancel(request, updater);
            } catch (final RuntimeException re) {
                logger.log(WARNING, re, () -> "Executor failed to cancel task '" + id + "': " + re.getMessage());
            }
        }
        if (!task.status().state().isTerminal()) { // the executor did not conclude, do it for it
            updater.cancel();
        }
        return task.snapshot(null, true);
    }

    /**
     * {@code ListTasks}: the tasks matching a filter, most recently updated first, with cursor pagination.
     *
     * @param tenant               the routing identifier the client echoed.
     * @param contextId            only the tasks of that conversation, {@code null} for all of them.
     * @param status               only the tasks in that state, {@code null} for all of them.
     * @param pageSize             how many to return, clamped to {@value #MAX_PAGE_SIZE}.
     * @param pageToken            the cursor of a previous call.
     * @param historyLength        how much history to include in each task.
     * @param statusTimestampAfter only the tasks updated at or after that ISO-8601 instant.
     * @param includeArtifacts     whether to include the outputs, they are omitted by default.
     */
    public ListTasksResponse listTasks(
            final String tenant,
            final String contextId,
            final TaskState status,
            final Integer pageSize,
            final String pageToken,
            final Integer historyLength,
            final String statusTimestampAfter,
            final Boolean includeArtifacts) {
        validateTenant(tenant);
        validateHistoryLength(historyLength);
        final var size = validatedPageSize(pageSize);
        final Instant after;
        try {
            after = A2ATimestamps.parse(statusTimestampAfter);
        } catch (final IllegalArgumentException iae) {
            throw A2AErrors.invalidParams("statusTimestampAfter", iae.getMessage());
        }

        final var matching = tasks.tasks().stream()
                .filter(it -> Objects.equals(caller, it.owner()))
                .filter(it -> contextId == null || contextId.isBlank() || contextId.equals(it.contextId()))
                .filter(it -> status == null
                        || status == TaskState.TASK_STATE_UNSPECIFIED
                        || status == it.status().state())
                .filter(it -> after == null || !it.statusInstant().isBefore(after))
                // the specification requires the most recently updated first
                .map(it -> new TaskEntry(it, it.statusInstant()))
                .sorted(Comparator.comparing(TaskEntry::updated)
                        .reversed()
                        .thenComparing(it -> it.task().id()))
                .toList();

        final var cursor = taskCursor(pageToken);
        final var remaining = matching.stream()
                .filter(it -> cursor == null
                        || it.updated().isBefore(cursor.updated())
                        || it.updated().equals(cursor.updated())
                                && it.task().id().compareTo(cursor.id()) > 0)
                .toList();
        final var selected = remaining.stream().limit(size).toList();
        final var page = selected.stream()
                .map(it -> it.task().snapshot(historyLength, Boolean.TRUE.equals(includeArtifacts)))
                .toList();

        return new ListTasksResponse(
                page,
                // the specification requires an empty string - not null - on the last page
                selected.size() < remaining.size() ? encodeTaskCursor(selected.getLast()) : "",
                size,
                matching.size());
    }

    /**
     * {@code CreateTaskPushNotificationConfig}: registers a webhook on a task.
     *
     * @param config the webhook, its {@code id} is generated when it is not provided.
     * @param taskId the task to watch, it wins over {@link TaskPushNotificationConfig#taskId()} when set - the
     *               HTTP+JSON binding carries it in the path.
     */
    public TaskPushNotificationConfig createPushConfig(final TaskPushNotificationConfig config, final String taskId) {
        requirePushNotifications();
        if (config == null) {
            throw A2AErrors.invalidParams("config", "A push notification configuration is required");
        }
        validateTenant(config.tenant());
        if (config.url() == null || config.url().isBlank()) {
            throw A2AErrors.invalidParams("url", "A push notification url is required");
        }
        final var target = taskId != null && !taskId.isBlank() ? taskId : config.taskId();
        final var task = requireTask(target);
        final var stored = config.boundTo(
                config.id() == null || config.id().isBlank() ? UUID.randomUUID().toString() : config.id(), task.id());
        task.pushConfigsById().put(stored.id(), stored);
        return stored;
    }

    /**
     * {@code GetTaskPushNotificationConfig}.
     */
    public TaskPushNotificationConfig getPushConfig(final String tenant, final String taskId, final String id) {
        requirePushNotifications();
        validateTenant(tenant);
        final var task = requireTask(taskId);
        final var config = task.pushConfigsById().get(id);
        if (config == null) {
            // the specification maps a missing configuration on TaskNotFoundError too
            throw A2AErrors.taskNotFound(taskId + '/' + id);
        }
        return config;
    }

    /**
     * {@code ListTaskPushNotificationConfigs}.
     */
    public ListTaskPushNotificationConfigsResponse listPushConfigs(
            final String tenant, final String taskId, final Integer pageSize, final String pageToken) {
        requirePushNotifications();
        validateTenant(tenant);
        final var all = List.copyOf(requireTask(taskId).pushConfigs());
        final var size = validatedPageSize(pageSize);
        final var from = Math.min(decodeCursor(pageToken), all.size());
        final var to = Math.min(from + size, all.size());
        return new ListTaskPushNotificationConfigsResponse(
                all.subList(from, to), to < all.size() ? encodeCursor(to) : "");
    }

    /**
     * {@code DeleteTaskPushNotificationConfig}, it is idempotent.
     */
    public void deletePushConfig(final String tenant, final String taskId, final String id) {
        requirePushNotifications();
        validateTenant(tenant);
        requireTask(taskId).pushConfigsById().remove(id);
    }

    /**
     * {@code GetExtendedAgentCard}.
     */
    public AgentCard extendedAgentCard(final String tenant, final Request http) {
        validateTenant(tenant);
        return cards.extendedCard(http);
    }

    /**
     * Resolves the target task, picks the executor and opens a turn.
     */
    private Exchange start(final SendMessageRequest request, final Request http) {
        if (request == null || request.message() == null) {
            throw A2AErrors.invalidParams("message", "A message is required");
        }
        final var message = request.message();
        if (message.messageId() == null || message.messageId().isBlank()) {
            throw A2AErrors.invalidParams("message.messageId", "A message identifier is required");
        }
        if (message.role() == null || "ROLE_UNSPECIFIED".equals(message.role().name())) {
            throw A2AErrors.invalidParams("message.role", "A message role is required");
        }
        if (message.parts() == null || message.parts().isEmpty()) {
            throw A2AErrors.invalidParams("message.parts", "At least one part is required");
        }
        if (message.parts().stream().anyMatch(it -> it == null || !it.hasSingleContent())) {
            throw A2AErrors.invalidParams("message.parts", "Every part must contain exactly one content branch");
        }
        validateHistoryLength(historyLength(request.configuration()));
        validateTenant(request.tenant());
        validateOutputModes(request.configuration());
        final var activatedExtensions = activatedExtensions(http);
        validateExtensions(activatedExtensions);

        final boolean continuation;
        final A2ATask task;
        if (message.taskId() != null && !message.taskId().isBlank()) {
            task = requireTask(message.taskId());
            if (message.contextId() != null
                    && !message.contextId().isBlank()
                    && !Objects.equals(message.contextId(), task.contextId())) {
                throw A2AErrors.invalidParams(
                        "message.contextId", "It does not match the context of task '" + task.id() + "'");
            }
            if (task.status().state().isTerminal()) {
                throw A2AErrors.unsupportedOperation(
                        "Task '" + task.id() + "' already ended and cannot accept further messages",
                        Map.of(
                                "taskId",
                                task.id(),
                                "state",
                                String.valueOf(task.status().state())));
            }
            continuation = true;
        } else {
            final var contextId =
                    message.contextId() == null || message.contextId().isBlank()
                            ? UUID.randomUUID().toString()
                            : message.contextId();
            task = tasks.create(contextId, request.metadata(), caller);
            continuation = false;
        }

        final var bound = message.boundTo(task.contextId(), task.id());

        final var agentRequest = new AgentRequest(
                bound,
                request.configuration(),
                request.metadata(),
                request.tenant(),
                task.contextId(),
                task.id(),
                continuation,
                activatedExtensions,
                http);

        try {
            final var executor = executors.stream()
                    .filter(it -> accepts(it, agentRequest))
                    .findFirst()
                    .orElseThrow(() -> A2AErrors.unsupportedOperation("No executor accepted the message"));

            // Validate and register the webhook before the message or its turn becomes observable.
            TaskPushNotificationConfig previousPush = null;
            TaskPushNotificationConfig registeredPush = null;
            if (request.configuration() != null && request.configuration().taskPushNotificationConfig() != null) {
                final var config = request.configuration().taskPushNotificationConfig();
                previousPush =
                        config.id() == null ? null : task.pushConfigsById().get(config.id());
                registeredPush = createPushConfig(config, task.id());
            }

            final var turn = task.startTurn(agentRequest, executor);
            return new Exchange(task, agentRequest, executor, turn, continuation, previousPush, registeredPush);
        } catch (final RuntimeException re) {
            if (!continuation) {
                tasks.forget(task.id());
            }
            throw re;
        }
    }

    /**
     * Hands the message to the executor. Nothing is awaited: the executor answers a stage and the outcome is reported
     * through the {@link DefaultTaskUpdater}, which is what the caller is really waiting on.
     *
     * @param protocolErrors when {@code true} a {@link JsonRpcException} raised before any transition is reported as a
     *                       protocol error instead of a failed task. It is only possible while the response is not
     *                       committed yet, i.e. not for a stream.
     */
    private void run(final Exchange exchange, final boolean protocolErrors) {
        final var updater = new DefaultTaskUpdater(
                exchange.task(), tasks, pushNotifier(), exchange.turn(), exchange.continuation());
        final CompletionStage<Void> execution;
        try {
            execution = exchange.executor().execute(exchange.request(), updater);
        } catch (final RuntimeException re) { // it failed before even starting
            onExecutorError(exchange, updater, re, protocolErrors);
            return;
        }
        if (execution == null) {
            onExecutorError(
                    exchange,
                    updater,
                    new IllegalStateException(
                            "Executor " + exchange.executor().getClass().getName() + " answered null"),
                    protocolErrors);
            return;
        }
        execution.whenComplete((ok, error) -> {
            if (error != null) {
                onExecutorError(exchange, updater, error, protocolErrors);
            } else if (!updater.hasTransitioned()) {
                onExecutorError(
                        exchange,
                        updater,
                        new IllegalStateException(
                                "Executor " + exchange.executor().getClass().getName() + " did not report anything"),
                        protocolErrors);
            }
        });
    }

    private void onExecutorError(
            final Exchange exchange,
            final DefaultTaskUpdater updater,
            final Throwable error,
            final boolean protocolErrors) {
        final var cause = A2AProtocol.unwrap(error);
        if (protocolErrors && cause instanceof JsonRpcException jre && !updater.hasTransitioned()) {
            // the call itself was invalid, so this is not a task failure: no task is left behind
            logger.log(FINE, jre, () -> "Rejecting the message: " + jre.getMessage());
            if (exchange.continuation()) {
                final var registered = exchange.registeredPush();
                if (registered != null) {
                    exchange.task()
                            .pushConfigsById()
                            .compute(
                                    registered.id(),
                                    (id, current) -> current == registered ? exchange.previousPush() : current);
                }
                exchange.turn().rollback();
            }
            updater.failWithProtocolError(jre);
            return;
        }
        logger.log(WARNING, cause, () -> "Task '" + exchange.task().id() + "' failed: " + cause.getMessage());
        try {
            updater.fail(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        } catch (final RuntimeException re) {
            logger.log(WARNING, re, () -> "Can't fail task '" + exchange.task().id() + "': " + re.getMessage());
        }
    }

    private boolean accepts(final A2AExecutor executor, final AgentRequest request) {
        try {
            return executor.accept(request);
        } catch (final RuntimeException re) {
            logger.log(
                    WARNING,
                    re,
                    () -> "Executor " + executor.getClass().getName() + " failed to accept: " + re.getMessage());
            return false;
        }
    }

    private PushNotifier pushNotifier() {
        return tasks.pushNotifier();
    }

    private SendMessageResponse truncate(final SendMessageResponse response, final Integer historyLength) {
        if (historyLength == null || response.task() == null) {
            return response;
        }
        final var task = response.task();
        final var history = task.history();
        if (historyLength <= 0) {
            return SendMessageResponse.of(
                    new Task(task.id(), task.contextId(), task.status(), task.artifacts(), null, task.metadata()));
        }
        if (history.size() <= historyLength) {
            return response;
        }
        return SendMessageResponse.of(new Task(
                task.id(),
                task.contextId(),
                task.status(),
                task.artifacts(),
                history.subList(history.size() - historyLength, history.size()),
                task.metadata()));
    }

    private void requireStreaming() {
        if (!streamingEnabled) {
            throw A2AErrors.unsupportedOperation(
                    "This agent does not support streaming", Map.of("capability", "streaming"));
        }
    }

    private void requirePushNotifications() {
        if (!pushEnabled) {
            throw A2AErrors.pushNotificationNotSupported();
        }
    }

    private void validateTenant(final String tenant) {
        final var expected = cards.tenant();
        if (expected == null) {
            return; // nothing to route, any tenant - including none - is fine
        }
        if (!expected.equals(tenant)) {
            throw A2AErrors.invalidParams("tenant", "This interface expects the tenant declared in its agent card");
        }
    }

    private void validateExtensions(final List<String> activated) {
        final var missing = cards.requiredExtensions().stream()
                .filter(it -> !activated.contains(it))
                .toList();
        if (!missing.isEmpty()) {
            throw A2AErrors.extensionSupportRequired(missing);
        }
    }

    private void validateOutputModes(final SendMessageConfiguration configuration) {
        if (configuration == null
                || configuration.acceptedOutputModes() == null
                || configuration.acceptedOutputModes().isEmpty()) {
            return;
        }
        final var supported = cards.outputModes();
        if (supported.isEmpty()) {
            return; // the agent did not declare anything so it does not claim to restrict its outputs
        }
        final var accepted = configuration.acceptedOutputModes();
        if (accepted.stream().noneMatch(it -> supported.stream().anyMatch(mode -> matches(it, mode)))) {
            throw A2AErrors.contentTypeNotSupported(accepted, supported);
        }
    }

    /**
     * @param accepted a media type the client accepts, wildcards included.
     * @param supported a media type the agent produces.
     * @return {@code true} when {@code accepted} covers {@code supported}.
     */
    private static boolean matches(final String accepted, final String supported) {
        if (accepted == null || supported == null) {
            return false;
        }
        final var pattern = accepted.strip();
        if ("*".equals(pattern) || "*/*".equals(pattern) || pattern.equalsIgnoreCase(supported)) {
            return true;
        }
        final var star = pattern.indexOf("/*");
        return star > 0 && star == pattern.length() - 2 && supported.regionMatches(true, 0, pattern, 0, star + 1);
    }

    @SuppressWarnings("unchecked")
    private static List<String> activatedExtensions(final Request http) {
        if (http == null) {
            return List.of();
        }
        final var attribute = http.attribute(A2AProtocol.EXTENSIONS_ATTRIBUTE, List.class);
        return attribute == null ? List.of() : (List<String>) attribute;
    }

    private static Integer historyLength(final SendMessageConfiguration configuration) {
        return configuration == null ? null : configuration.historyLength();
    }

    private static void validateHistoryLength(final Integer length) {
        if (length != null && length < 0) {
            throw A2AErrors.invalidParams("historyLength", "It must be nonnegative");
        }
    }

    private static int validatedPageSize(final Integer size) {
        if (size != null && (size < 1 || size > MAX_PAGE_SIZE)) {
            throw A2AErrors.invalidParams("pageSize", "It must be between 1 and 100");
        }
        return size == null ? DEFAULT_PAGE_SIZE : size;
    }

    private record TaskEntry(A2ATask task, Instant updated) {}

    private record TaskCursor(Instant updated, String id) {}

    private static TaskCursor taskCursor(final String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            final var decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            final var separator = decoded.indexOf('|');
            final var timestamp = Instant.parse(decoded.substring(0, separator));
            final var id = UUID.fromString(decoded.substring(separator + 1)).toString();
            return new TaskCursor(timestamp, id);
        } catch (final RuntimeException invalid) {
            throw A2AErrors.invalidParams("pageToken", "It is not a task cursor this agent issued");
        }
    }

    private static String encodeTaskCursor(final TaskEntry entry) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((entry.updated() + "|" + entry.task().id()).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeCursor(final String pageToken) {
        if (pageToken == null || pageToken.isBlank()) {
            return 0;
        }
        try {
            final var decoded = new String(Base64.getUrlDecoder().decode(pageToken.strip()), StandardCharsets.UTF_8);
            final var value = Integer.parseInt(decoded);
            if (value < 0) {
                throw new IllegalArgumentException("Negative cursor");
            }
            return value;
        } catch (final RuntimeException re) {
            throw A2AErrors.invalidParams("pageToken", "It is not a cursor this agent issued");
        }
    }

    private static String encodeCursor(final int offset) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A message being processed: the task it targets, the request the executor sees and the promises to complete.
     */
    private record Exchange(
            A2ATask task,
            AgentRequest request,
            A2AExecutor executor,
            A2ATask.Turn turn,
            boolean continuation,
            TaskPushNotificationConfig previousPush,
            TaskPushNotificationConfig registeredPush) {}
}
