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
package io.yupiik.ia.agent.slack.service;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.feedback.FeedbackMailbox;
import io.yupiik.ia.agent.bot.runtime.AttemptPreparation;
import io.yupiik.ia.agent.bot.runtime.ResultReader;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmission;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.context.SlackActionRegistry;
import io.yupiik.ia.agent.slack.context.SlackContextAttempt;
import io.yupiik.ia.agent.slack.context.SlackContextStorage;
import io.yupiik.ia.agent.slack.model.SlackJson;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.transport.SlackWebApi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Domain worker. Every runtime intent precedes launch; termination confirmation precedes results or cleanup. */
public final class SlackWorker {
    private static final Pattern USER_MENTION = Pattern.compile("<@([UW][A-Z0-9]{1,63})>");
    private final SlackConfiguration configuration;
    private final ExecutorConfiguration executor;
    private final JsonMapper json;
    private final SlackStore store;
    private final SlackPromptRenderer prompts;
    private final Sandbox sandbox;
    private final SlackFeedback feedback;
    private final SlackWebApi api;
    private final String fingerprint;
    private final HostAdmission admission;
    private final SlackContextConfiguration contextConfiguration;
    private final SlackActionRegistry actions;
    private final SlackContextAttempt.Dispatcher contextDispatcher;
    private final SlackContextStorage contextStorage;

    public SlackWorker(
            final SlackConfiguration configuration,
            final ExecutorConfiguration executor,
            final JsonMapper json,
            final SlackStore store,
            final SlackPromptRenderer prompts,
            final Sandbox sandbox,
            final SlackFeedback feedback,
            final SlackWebApi api,
            final HostAdmission admission,
            final SlackContextConfiguration contextConfiguration,
            final SlackActionRegistry actions,
            final SlackContextAttempt.Dispatcher contextDispatcher,
            final SlackContextStorage contextStorage) {
        this.configuration = configuration;
        this.executor = executor;
        this.json = json;
        this.store = store;
        this.prompts = prompts;
        this.sandbox = sandbox;
        this.feedback = feedback;
        this.api = api;
        this.admission = admission;
        this.contextConfiguration = contextConfiguration;
        this.actions = actions;
        this.contextDispatcher = contextDispatcher;
        this.contextStorage = contextStorage;
        fingerprint = SlackStore.identity("runtime-ownership-v2:" + SlackExecutionPolicy.POLICY + ':' + executor
                + (sandbox.recoveryIdentity().isBlank() ? "" : ':' + sandbox.recoveryIdentity()));
    }

    public void recover() {
        for (final var task : store.list()) {
            if (task.terminal() || task.status() == TaskStatus.queued) {
                continue;
            }
            if (task.runtimeId() == null) {
                update(
                        task.id(),
                        current -> current.transition(TaskStatus.recovery_blocked, System.currentTimeMillis()));
                continue;
            }
            if (!validRuntime(task)) {
                update(
                        task.id(),
                        current -> current.transition(TaskStatus.recovery_blocked, System.currentTimeMillis()));
                continue;
            }
            try {
                sandbox.stop(task.runtimeId());
                // Unknown publication outcomes and stale live question controls are intentionally not
                // resumed.
                update(
                        task.id(),
                        current -> current.feedback(null, TaskStatus.recovery_blocked, System.currentTimeMillis()));
            } catch (final RuntimeException unknown) {
                update(
                        task.id(),
                        current -> current.transition(TaskStatus.recovery_blocked, System.currentTimeMillis()));
            }
        }
    }

    public boolean runOne() {
        try {
            final var admitted = admission.tryAcquire();
            if (admitted.isEmpty()) {
                return false;
            }
            try (final var lease = admitted.orElseThrow()) {
                lease.confirmTermination(); // No launch is possible until execute marks this lease
                // uncertain.
                return runAdmitted(lease);
            }
        } catch (final IOException failure) {
            throw new IllegalStateException("Shared host admission requires reconciliation", failure);
        }
    }

    private boolean runAdmitted(final HostAdmission.Lease lease) {
        final var claimed = store.transaction(tasks -> {
            if (tasks.stream().anyMatch(task -> task.status() == TaskStatus.recovery_blocked)) {
                return null; // Unknown runtimes/publication state require operator reconciliation before
                // new work.
            }
            for (int i = 0; i < tasks.size(); i++) {
                final var task = tasks.get(i);
                if (task.status() == TaskStatus.queued
                        && tasks.stream()
                                .noneMatch(other -> !other.id().equals(task.id())
                                        && other.conversation().equals(task.conversation())
                                        && !other.terminal()
                                        && other.status() != TaskStatus.queued)) {
                    final var next = task.transition(TaskStatus.preparing, System.currentTimeMillis());
                    tasks.set(i, next);
                    return next;
                }
            }
            return null;
        });
        if (claimed == null) {
            return false;
        }
        execute(claimed, lease);
        return true;
    }

    private void execute(final SlackTask claimed, final HostAdmission.Lease lease) {
        Path directory = null;
        String runtime = null;
        boolean terminated = false;
        boolean restoreInterrupt = Thread.interrupted();
        Thread executionThread = null;
        SlackContextAttempt context = null;
        SlackActionRegistry.Grant grant = null;
        final var completion = new CompletableFuture<Integer>();
        try {
            if (contextConfiguration.enabled()) {
                grant = actions.claim(claimed, claimed.attempt(), System.currentTimeMillis())
                        .orElse(null);
            }
            if (grant != null) {
                contextStorage.validate(
                        executor,
                        contextConfiguration.allowSwapBackedTmpfs(),
                        contextConfiguration.allowContainerLogs());
            }
            final var root = Path.of(executor.attemptRoot()).toAbsolutePath().normalize();
            directory = new AttemptPreparation(json)
                    .prepare(Path.of(configuration.agentConfiguration()), root, "yca-slack-");
            if (grant == null) {
                Files.createDirectory(
                        directory.resolve("feedback"),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Files.writeString(
                        directory.resolve(".feedback-timeout"),
                        Integer.toString(configuration.questionTimeoutSeconds()),
                        StandardOpenOption.CREATE_NEW);
            } else {
                // SlackFeedback retains question text/options/answers in durable state. Do not enable
                // that transport in transient mode until it too has an in-memory lifecycle.
                contextStorage.sanitize(directory.resolve("configuration.json"), json);
                context = new SlackContextAttempt(
                        directory, json, contextConfiguration, api, grant, Clock.systemUTC(), contextDispatcher);
            }
            final var initialContext = context == null
                    ? Map.<String, Object>of()
                    : context.initialContext(() -> find(claimed.id()).status() != TaskStatus.preparing);
            requireContext(context);
            final var prompt = prompts.render(claimed, store.list(), context != null, initialContext);
            requireContext(context);
            runtime = "yca-slack-" + claimed.id().substring(0, 32) + "-" + (claimed.attempt() + 1);
            final var durable = claimed.running(
                    directory.toString(), runtime, fingerprint, context != null, System.currentTimeMillis());
            update(claimed.id(), current -> {
                if (current.status() != TaskStatus.preparing) {
                    throw new IllegalStateException("Task was cancelled during preparation");
                }
                return durable;
            });
            Files.delete(directory.resolve(".prepared-only"));
            final var attempt = directory;
            final var identity = runtime;
            final var temporaryDirectory = context == null
                    ? null
                    : Files.createDirectory(
                            directory.resolve("tmp"),
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            requireContext(context);
            final var attemptContext = context;
            // A borrowed worker slot is retained while the agent synchronously waits for ask_user.
            lease.markUncertain();
            executionThread = Thread.ofVirtual().name(identity).start(() -> {
                try {
                    requireContext(attemptContext);
                    if (find(claimed.id()).status() != TaskStatus.running) {
                        throw new IllegalStateException("Task no longer owns launch");
                    }
                    completion.complete(sandbox.execute(attempt, identity, prompt, temporaryDirectory));
                } catch (final Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            });
            try (final var mailbox =
                    context == null ? new FeedbackMailbox(directory.resolve("feedback"), json) : null) {
                while (!completion.isDone()) {
                    requireContext(context);
                    final var current = find(claimed.id());
                    if (current.status() == TaskStatus.cancel_requested
                            || current.status() == TaskStatus.recovery_blocked) {
                        if (context != null) {
                            context.close();
                        }
                        quiesce(runtime, executionThread, completion);
                        restoreInterrupt |= Thread.interrupted();
                        terminated = true;
                        if (current.status() != TaskStatus.recovery_blocked) {
                            update(
                                    claimed.id(),
                                    task -> task.feedback(null, TaskStatus.cancelled, System.currentTimeMillis()));
                        }
                        return;
                    }
                    if (mailbox != null) {
                        feedback.poll(claimed.id(), mailbox);
                    }
                    if (context != null) {
                        context.poll();
                    }
                    try {
                        completion.get(25, TimeUnit.MILLISECONDS);
                    } catch (final TimeoutException waiting) {
                        // Poll bounded private feedback only; Slack transport never waits for the model.
                    }
                }
                if (completion.get() != 0) {
                    throw new IllegalStateException("Sandbox execution did not succeed");
                }
            }
            requireContext(context);
            sandbox.stop(runtime);
            terminated = true;
            requireContext(context);
            final var current = find(claimed.id());
            if (current.status() == TaskStatus.cancel_requested) {
                update(claimed.id(), task -> task.feedback(null, TaskStatus.cancelled, System.currentTimeMillis()));
                return;
            }
            final var result = json.fromString(
                    Result.class,
                    ResultReader.read(
                            directory.resolve("output/result.json"), configuration.maxAnswerChars() * 6 + 1024));
            if (result == null) {
                throw new IllegalStateException("Invalid bounded Slack result contract");
            }
            final var answer = result.summary();
            if (!claimed.id().equals(result.taskId())
                    || answer == null
                    || answer.isBlank()
                    || answer.length() > configuration.maxAnswerChars()) {
                throw new IllegalStateException("Invalid bounded Slack result contract");
            }
            final BooleanSupplier selectPublication = () -> store.transaction(tasks -> {
                for (int index = 0; index < tasks.size(); index++) {
                    final var task = tasks.get(index);
                    if (task.id().equals(claimed.id())) {
                        if (task.status() == TaskStatus.cancel_requested) {
                            tasks.set(index, task.feedback(null, TaskStatus.cancelled, System.currentTimeMillis()));
                            return false;
                        }
                        if (!Set.of(TaskStatus.running, TaskStatus.waiting_for_user)
                                .contains(task.status())) {
                            return false;
                        }
                        tasks.set(
                                index,
                                task.feedback(null, TaskStatus.running, System.currentTimeMillis())
                                        .publication(answer, null, TaskStatus.publishing, System.currentTimeMillis()));
                        return true;
                    }
                }
                throw new IllegalStateException("Unknown Slack task");
            });
            final boolean publish;
            if (grant == null) {
                publish = selectPublication.getAsBoolean();
            } else {
                // Only the durable publishing selection is under the grant lock. Revocation
                // callbacks and Slack HTTP must stay outside it. Keep the grant alive until here.
                final var selected = new AtomicBoolean();
                grant.whileActive(() -> selected.set(selectPublication.getAsBoolean()));
                publish = selected.get();
                if (!publish) {
                    requireContext(context);
                }
            }
            if (!publish) {
                return;
            }
            // Publishing is the cancellation point of no return. Still check authority immediately
            // before sending; expiry during the HTTP call cannot retract an accepted message.
            final var message = new SlackJson.PostMessage(
                    claimed.channel(), claimed.thread(), "Assistant response", false, false, answerBlocks(answer));
            requireContext(context);
            try {
                final var response =
                        api.postMessage(message).toCompletableFuture().join();
                final var ts = response.ts();
                if (ts == null || !claimed.channel().equals(response.channel())) {
                    throw new IllegalStateException("Invalid Slack publication receipt");
                }
                update(claimed.id(), task -> task.publication(answer, ts, TaskStatus.done, System.currentTimeMillis()));
            } catch (final RuntimeException ambiguous) {
                update(claimed.id(), task -> task.transition(TaskStatus.recovery_blocked, System.currentTimeMillis()));
            }
        } catch (final Exception failure) {
            if (context != null) {
                context.close();
            }
            if (failure instanceof InterruptedException) {
                restoreInterrupt = true;
            }
            restoreInterrupt |= Thread.interrupted();
            if (runtime == null) {
                update(
                        claimed.id(),
                        task -> task.transition(
                                task.status() == TaskStatus.recovery_blocked
                                        ? TaskStatus.recovery_blocked
                                        : task.status() == TaskStatus.cancel_requested
                                                ? TaskStatus.cancelled
                                                : TaskStatus.failed,
                                System.currentTimeMillis()));
            } else {
                try {
                    quiesce(runtime, executionThread, completion);
                    restoreInterrupt |= Thread.interrupted();
                    terminated = true;
                    update(
                            claimed.id(),
                            task -> task.status() == TaskStatus.recovery_blocked
                                            || task.question() != null
                                                    && Set.of("publishing", "opening")
                                                            .contains(task.question()
                                                                    .state())
                                    ? task.transition(TaskStatus.recovery_blocked, System.currentTimeMillis())
                                    : task.feedback(
                                            null,
                                            task.status() == TaskStatus.cancel_requested
                                                    ? TaskStatus.cancelled
                                                    : TaskStatus.failed,
                                            System.currentTimeMillis()));
                } catch (final RuntimeException unknown) {
                    restoreInterrupt |= Thread.interrupted();
                    update(
                            claimed.id(),
                            task -> task.transition(TaskStatus.recovery_blocked, System.currentTimeMillis()));
                }
            }
        } finally {
            if (context != null) {
                context.close();
            }
            if (grant != null) {
                grant.close();
            }
            restoreInterrupt |= Thread.interrupted();
            if (runtime == null || terminated) {
                lease.confirmTermination();
            }
            if (directory != null && (runtime == null || terminated)) {
                // Context result bodies are transient even when publication is ambiguous. Only
                // bounded durable metadata survives. Unconfirmed runtimes still retain their attempt.
                final var finalState = find(claimed.id());
                if (finalState.contextBearing() || finalState.status() != TaskStatus.recovery_blocked) {
                    try {
                        AttemptPreparation.delete(directory);
                    } catch (final IOException ignored) {
                        // Retention cleanup can retry, but never touch an unconfirmed runtime.
                    }
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @JsonModel
    public record Result(String taskId, String summary) {}

    private void requireContext(final SlackContextAttempt context) {
        if (context != null && !context.authorized()) {
            // Revoke/cancel controller work before the catch path quiesces the runtime.
            context.close();
            throw new IllegalStateException("Slack context authorization ended");
        }
    }

    private void quiesce(final String runtime, final Thread thread, final CompletableFuture<Integer> completion) {
        if (thread == null) {
            sandbox.stop(runtime);
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            thread.interrupt();
            try {
                sandbox.stop(runtime);
            } catch (final RuntimeException notYetCreatedOrUnknown) {
                // A launcher may still be starting. Only the final stop after it exits is authoritative.
            }
            final var settled = completion.handle((result, failure) -> null);
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(executor.commandTimeoutSeconds() + 5L);
            while (!settled.isDone()) {
                try {
                    settled.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (final InterruptedException stopping) {
                    interrupted = true;
                } catch (final ExecutionException | TimeoutException unknown) {
                    throw new IllegalStateException("Sandbox launcher termination is unconfirmed");
                }
            }
            sandbox.stop(runtime);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private List<Map<String, Object>> answerBlocks(final String answer) {
        final var blocks = new ArrayList<Map<String, Object>>();
        // Only exact user mentions become structured elements. All other markup stays literal.
        final var mentions = USER_MENTION.matcher(answer);
        boolean hasNextMention = mentions.find();
        for (int index = 0; index < answer.length(); ) {
            if (blocks.size() == 50) {
                throw new IllegalArgumentException("Answer exceeds Slack's 50-block limit");
            }
            final int start = index;
            int end = Math.min(index + 2900, answer.length());
            if (end < answer.length() && Character.isHighSurrogate(answer.charAt(end - 1))) {
                end--;
            }
            final var elements = new ArrayList<Map<String, Object>>();
            boolean hasUser = false;
            while (hasNextMention && mentions.start() < end) {
                if (mentions.end() > end) {
                    end = mentions.start(); // Never split a user token across blocks.
                    break;
                }
                if (index < mentions.start()) {
                    elements.add(Map.of("type", "text", "text", answer.substring(index, mentions.start())));
                }
                elements.add(Map.of("type", "user", "user_id", mentions.group(1)));
                hasUser = true;
                index = mentions.end();
                hasNextMention = mentions.find();
                // Conservatively bound each section to at most 100 inline elements.
                if (elements.size() >= 98) {
                    end = index;
                    break;
                }
            }
            if (hasUser) {
                if (index < end) {
                    elements.add(Map.of("type", "text", "text", answer.substring(index, end)));
                }
                blocks.add(Map.of(
                        "type",
                        "rich_text",
                        "elements",
                        List.of(Map.of("type", "rich_text_section", "elements", elements))));
            } else {
                blocks.add(Map.of(
                        "type", "section", "text", Map.of("type", "plain_text", "text", answer.substring(start, end))));
            }
            index = end;
        }
        return blocks;
    }

    private boolean validRuntime(final SlackTask task) {
        if (!fingerprint.equals(task.fingerprint())
                || task.attempt() < 1
                || task.attemptPath() == null
                || !task.runtimeId().equals("yca-slack-" + task.id().substring(0, 32) + "-" + task.attempt())) {
            return false;
        }
        try {
            final var path = Path.of(task.attemptPath()).toAbsolutePath().normalize();
            return path.getParent() != null
                    && path.getParent()
                            .equals(Path.of(executor.attemptRoot())
                                    .toAbsolutePath()
                                    .normalize())
                    && path.getFileName().toString().startsWith("yca-slack-");
        } catch (final InvalidPathException malformed) {
            return false;
        }
    }

    private SlackTask find(final String id) {
        return store.list().stream()
                .filter(task -> task.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private void update(final String id, final UnaryOperator<SlackTask> operation) {
        store.transaction(tasks -> {
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).id().equals(id)) {
                    tasks.set(i, operation.apply(tasks.get(i)));
                    return null;
                }
            }
            throw new IllegalStateException("Unknown Slack task");
        });
    }
}
