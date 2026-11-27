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

import io.yupiik.fusion.a2a.api.A2AExecutor;
import io.yupiik.fusion.a2a.api.AgentRequest;
import io.yupiik.fusion.a2a.model.A2ATimestamps;
import io.yupiik.fusion.a2a.model.Artifact;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.Part;
import io.yupiik.fusion.a2a.model.SendMessageResponse;
import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.a2a.model.Task;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.model.TaskStatus;
import io.yupiik.fusion.a2a.protocol.A2AStream;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The server side state of a task: its status, the messages exchanged, the artifacts produced, the streams watching it
 * and the webhooks registered on it.
 * <p>
 * {@link io.yupiik.fusion.a2a.model.Task} is the immutable snapshot a client gets, this is the living object. It is
 * only mutated through a {@link io.yupiik.fusion.a2a.api.TaskUpdater} so every change is published to the subscribers.
 *
 * @see A2ATasks which holds them.
 */
public class A2ATask {
    private final Clock clock;
    private final String id;
    private final String contextId;
    private final String owner;
    private final Map<String, Object> metadata;

    private final List<Message> history = new CopyOnWriteArrayList<>();
    private final Map<String, Artifact> artifacts = new LinkedHashMap<>();
    private final Map<String, TaskPushNotificationConfig> pushConfigs = new ConcurrentHashMap<>();
    private final List<A2AStream> streams = new CopyOnWriteArrayList<>();
    private final Lock artifactsLock = new ReentrantLock();
    private final Lock pushLock = new ReentrantLock();

    private CompletableFuture<Void> pushChain = CompletableFuture.completedFuture(null);

    private volatile TaskStatus status;
    private volatile Instant statusInstant;
    private volatile long lastUpdate = System.nanoTime();
    private volatile Turn turn = new Turn();
    private volatile AgentRequest request;
    private volatile A2AExecutor executor;

    public A2ATask(
            final String id,
            final String contextId,
            final Map<String, Object> metadata,
            final String owner,
            final Clock clock) {
        this.clock = clock;
        this.statusInstant = clock.instant();
        this.status = TaskStatus.of(TaskState.TASK_STATE_SUBMITTED, statusInstant);
        this.id = id;
        this.contextId = contextId;
        this.metadata = metadata;
        this.owner = owner;
    }

    public String owner() {
        return owner;
    }

    public String id() {
        return id;
    }

    public String contextId() {
        return contextId;
    }

    public TaskStatus status() {
        return status;
    }

    /**
     * @return when the current status was recorded, it is what {@code ListTasks} sorts and filters on.
     */
    public Instant statusInstant() {
        return statusInstant;
    }

    /**
     * @return the request being processed, {@code null} before the first message is handled.
     */
    public AgentRequest request() {
        return request;
    }

    /**
     * @return the executor handling this task, {@code null} when none took it yet.
     */
    public A2AExecutor executor() {
        return executor;
    }

    /**
     * @return the webhooks to call when this task changes.
     */
    public Collection<TaskPushNotificationConfig> pushConfigs() {
        return pushConfigs.values();
    }

    public Map<String, TaskPushNotificationConfig> pushConfigsById() {
        return pushConfigs;
    }

    /**
     * @return the current turn, i.e. the promises the pending {@code SendMessage} is waiting on.
     */
    public Turn turn() {
        return turn;
    }

    /**
     * Starts a new turn: a fresh pair of promises for the message which is about to be processed.
     *
     * @param request  the message being processed.
     * @param executor who handles it.
     * @return the new turn.
     */
    public Turn startTurn(final AgentRequest request, final A2AExecutor executor) {
        final var previousRequest = this.request;
        final var previousExecutor = this.executor;
        final var previousTurn = this.turn;
        final var next = new Turn();
        next.rollback = () -> {
            if (request != null) {
                history.removeIf(message -> message == request.message());
            }
            if (this.turn == next) {
                this.request = previousRequest;
                this.executor = previousExecutor;
                this.turn = previousTurn;
            }
        };
        if (request != null) {
            addMessage(request.message());
        }
        this.request = request;
        this.executor = executor;
        this.turn = next;
        touch();
        return this.turn;
    }

    void setStatus(final TaskStatus status) {
        this.statusInstant =
                A2ATimestamps.parse(status.timestamp()) instanceof Instant parsed ? parsed : clock.instant();
        this.status =
                status.timestamp() == null ? TaskStatus.of(status.state(), status.message(), statusInstant) : status;
        touch();
    }

    void addMessage(final Message message) {
        history.add(message);
        touch();
    }

    /**
     * Stores an artifact, merging it with the one already registered under the same identifier when {@code append} is
     * set - which is how a streamed artifact is reassembled for the clients which poll instead of streaming.
     */
    void putArtifact(final Artifact artifact, final boolean append) {
        artifactsLock.lock();
        try {
            final var existing = artifacts.get(artifact.artifactId());
            if (!append || existing == null) {
                artifacts.put(artifact.artifactId(), artifact);
                return;
            }
            final var parts = new ArrayList<Part>(existing.parts() == null ? List.of() : existing.parts());
            if (artifact.parts() != null) {
                parts.addAll(artifact.parts());
            }
            artifacts.put(
                    artifact.artifactId(),
                    new Artifact(
                            existing.artifactId(),
                            artifact.name() == null ? existing.name() : artifact.name(),
                            artifact.description() == null ? existing.description() : artifact.description(),
                            parts,
                            artifact.metadata() == null ? existing.metadata() : artifact.metadata(),
                            artifact.extensions() == null ? existing.extensions() : artifact.extensions()));
        } finally {
            artifactsLock.unlock();
            touch();
        }
    }

    /**
     * @param historyLength   how many of the most recent messages to include: {@code null} means all, {@code 0} means
     *                        none - and the attribute is then omitted as the specification requires.
     * @param includeArtifacts whether to include the outputs, {@code ListTasks} defaults to {@code false} to keep the
     *                        payload small.
     * @return the snapshot to send to a client.
     */
    public Task snapshot(final Integer historyLength, final boolean includeArtifacts) {
        return new Task(id, contextId, status, includeArtifacts ? artifacts() : null, history(historyLength), metadata);
    }

    /**
     * @return the outputs produced so far, in the order they were published.
     */
    public List<Artifact> artifacts() {
        artifactsLock.lock();
        try {
            return List.copyOf(artifacts.values());
        } finally {
            artifactsLock.unlock();
        }
    }

    private List<Message> history(final Integer historyLength) {
        if (historyLength == null) {
            return List.copyOf(history);
        }
        if (historyLength <= 0) {
            return null; // the attribute must be omitted, not sent empty
        }
        final var all = List.copyOf(history);
        return all.size() <= historyLength ? all : all.subList(all.size() - historyLength, all.size());
    }

    void addStream(final A2AStream stream) {
        streams.add(stream);
    }

    void removeStream(final A2AStream stream) {
        streams.remove(stream);
    }

    /**
     * @return the open streams watching this task.
     */
    public List<A2AStream> streams() {
        return List.copyOf(streams);
    }

    /**
     * Broadcasts an event to all the streams, prefixing it with the initial {@code Task} event on the streams which
     * did not get it yet - the specification requires a task stream to begin with the task itself.
     *
     * @param event    what happened.
     * @param snapshot the current state of the task, used for the initial event.
     */
    void publish(final StreamResponse event, final Task snapshot) {
        streams.forEach(it -> {
            it.emitInitial(snapshot);
            it.emit(event);
        });
        streams.removeIf(A2AStream::isClosed);
    }

    /**
     * Broadcasts a direct message answer: no initial {@code Task} event, the stream carries exactly one message and
     * closes.
     */
    void publishMessage(final Message message) {
        streams.forEach(it -> {
            it.markStarted();
            it.emit(StreamResponse.of(message));
        });
        streams.removeIf(A2AStream::isClosed);
    }

    void closeStreams() {
        streams.forEach(A2AStream::close);
        streams.clear();
    }

    /**
     * Queues a webhook delivery behind the ones already in flight for this task.
     * <p>
     * Events are only meaningful in order - an artifact then the completion which refers to it - and HTTP calls started
     * concurrently would arrive in any order, so they are chained. A failed delivery does not stop the next one.
     *
     * @param call what sends the notification.
     * @return a stage completing when {@code call} is done.
     */
    CompletableFuture<Void> serializePush(final Supplier<CompletionStage<Void>> call) {
        pushLock.lock();
        try {
            pushChain = pushChain
                    .handle((ok, ko) -> null)
                    .thenCompose(ignored -> call.get())
                    .toCompletableFuture();
            return pushChain;
        } finally {
            pushLock.unlock();
        }
    }

    void touch() {
        lastUpdate = System.nanoTime();
    }

    /**
     * @param retention the maximum idle duration.
     * @return {@code true} when the task was not touched for more than {@code retention}.
     */
    public boolean isExpired(final Duration retention) {
        return !retention.isZero() && !retention.isNegative() && System.nanoTime() - lastUpdate > retention.toNanos();
    }

    /**
     * Releases the task: the streams are closed and any pending {@code SendMessage} fails.
     */
    public void close() {
        closeStreams();
        turn.fail(new JsonRpcException(-32603, "Task '" + id + "' was dropped"));
    }

    /**
     * The promises of one message being processed: {@code firstAction} is what a non-blocking {@code SendMessage}
     * waits for and {@code settled} what a blocking one waits for.
     * <p>
     * A new one is created for every turn so a second message on the same task is not answered with the result of the
     * first.
     */
    public static final class Turn {
        private final CompletableFuture<SendMessageResponse> firstAction = new CompletableFuture<>();
        private final CompletableFuture<SendMessageResponse> settled = new CompletableFuture<>();
        private Runnable rollback;

        void rollback() {
            final var action = rollback;
            rollback = null;
            if (action != null) {
                action.run();
            }
        }

        public CompletableFuture<SendMessageResponse> firstAction() {
            return firstAction;
        }

        public CompletableFuture<SendMessageResponse> settled() {
            return settled;
        }

        void onFirstAction(final SendMessageResponse response) {
            rollback = null;
            firstAction.complete(response);
        }

        void onSettled(final SendMessageResponse response) {
            rollback = null;
            firstAction.complete(response);
            settled.complete(response);
        }

        void fail(final Throwable error) {
            firstAction.completeExceptionally(error);
            settled.completeExceptionally(error);
        }
    }
}
