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

import io.yupiik.fusion.a2a.api.TaskUpdater;
import io.yupiik.fusion.a2a.model.Artifact;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.Role;
import io.yupiik.fusion.a2a.model.SendMessageResponse;
import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.a2a.model.Task;
import io.yupiik.fusion.a2a.model.TaskArtifactUpdateEvent;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.model.TaskStatus;
import io.yupiik.fusion.a2a.model.TaskStatusUpdateEvent;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * The {@link TaskUpdater} implementation: it mutates a {@link A2ATask}, publishes the matching event to the open
 * streams and to the push notification webhooks, and completes the promises the pending {@code SendMessage} waits on.
 * <p>
 * It is created per turn - see {@link A2ATask#startTurn} - or on demand for an out of band update through
 * {@link A2ATasks#updater(String)}.
 */
public class DefaultTaskUpdater implements TaskUpdater {
    private final Logger logger = Logger.getLogger(DefaultTaskUpdater.class.getName());

    private final A2ATask task;
    private final A2ATasks tasks;
    private final PushNotifier push;
    private final A2ATask.Turn turn;
    private final boolean continuation;
    private final AtomicBoolean transitioned = new AtomicBoolean();

    /**
     * @param task         the task to drive.
     * @param tasks        the store, used to drop a task which ends up answering a plain message.
     * @param push         where the events are forwarded as webhook calls.
     * @param turn         the promises to complete.
     * @param continuation {@code false} when the task was created for this very turn, i.e. when it can still be
     *                     discarded in favor of a direct message answer.
     */
    public DefaultTaskUpdater(
            final A2ATask task,
            final A2ATasks tasks,
            final PushNotifier push,
            final A2ATask.Turn turn,
            final boolean continuation) {
        this.task = task;
        this.tasks = tasks;
        this.push = push;
        this.turn = turn;
        this.continuation = continuation;
    }

    @Override
    public String taskId() {
        return task.id();
    }

    @Override
    public String contextId() {
        return task.contextId();
    }

    @Override
    public Task task() {
        return task.snapshot(null, true);
    }

    @Override
    public boolean isDone() {
        return task.status().state().isTerminal();
    }

    /**
     * @return {@code true} once anything was published, i.e. once the task became visible to the client.
     */
    public boolean hasTransitioned() {
        return transitioned.get();
    }

    @Override
    public void respond(final Message message) {
        if (isDone() || !transitioned.compareAndSet(false, true)) {
            throw new IllegalStateException("respond(Message) is only valid before any other transition, task '"
                    + task.id() + "' already moved to " + task.status().state());
        }
        // a task created for this turn only never surfaced, so the client gets a plain message - not even bound to a
        // task id - and nothing is left behind
        final var answer =
                continuation ? asAgentMessage(message) : asAgentMessage(message).boundTo(task.contextId(), null);
        if (continuation) {
            task.addMessage(answer);
        } else {
            tasks.forget(task.id());
        }
        task.publishMessage(answer);
        push.notify(task, StreamResponse.of(answer));
        turn.onSettled(SendMessageResponse.of(answer));
        task.closeStreams();
    }

    @Override
    public void working() {
        status(TaskState.TASK_STATE_WORKING, null);
    }

    @Override
    public void working(final Message message) {
        status(TaskState.TASK_STATE_WORKING, message);
    }

    @Override
    public void artifact(final Artifact artifact) {
        artifact(artifact, false, true);
    }

    @Override
    public void artifact(final Artifact artifact, final boolean append, final boolean lastChunk) {
        if (artifact == null || isDone()) {
            return;
        }
        final var identified = artifact.artifactId() == null
                ? artifact.withId(UUID.randomUUID().toString())
                : artifact;
        transitioned.set(true);
        task.putArtifact(identified, append);
        publish(StreamResponse.of(
                new TaskArtifactUpdateEvent(task.id(), task.contextId(), identified, append, lastChunk, null)));
    }

    @Override
    public void complete(final Artifact... artifacts) {
        for (final var artifact : artifacts) {
            artifact(artifact);
        }
        status(TaskState.TASK_STATE_COMPLETED, null);
    }

    @Override
    public void fail(final String reason) {
        status(TaskState.TASK_STATE_FAILED, Message.agent(reason == null ? "The task failed" : reason));
    }

    @Override
    public void reject(final String reason) {
        status(TaskState.TASK_STATE_REJECTED, Message.agent(reason == null ? "The task was rejected" : reason));
    }

    @Override
    public void cancel() {
        status(TaskState.TASK_STATE_CANCELED, null);
    }

    @Override
    public void inputRequired(final Message message) {
        status(TaskState.TASK_STATE_INPUT_REQUIRED, message);
    }

    @Override
    public void authRequired(final Message message) {
        status(TaskState.TASK_STATE_AUTH_REQUIRED, message);
    }

    @Override
    public void adopt(final Task other) {
        if (other == null) {
            return;
        }
        if (other.artifacts() != null) {
            other.artifacts().forEach(this::artifact);
        }
        if (other.status() != null && other.status().state() != null) {
            status(other.status().state(), other.status().message());
        }
    }

    @Override
    public void status(final TaskState state, final Message message) {
        if (isDone()) {
            // a task which reached a terminal state must not move again, else a client would see events after the
            // stream closed - an interrupted one is resumable, it is what a new turn does
            logger.log(
                    FINE,
                    () -> "Ignoring the transition of task '" + task.id() + "' to " + state + ", it is already "
                            + task.status().state());
            return;
        }
        transitioned.set(true);

        final var statusMessage = message == null ? null : asAgentMessage(message);
        if (statusMessage != null) {
            task.addMessage(statusMessage);
        }
        task.setStatus(new TaskStatus(state, statusMessage, null));

        publish(StreamResponse.of(new TaskStatusUpdateEvent(task.id(), task.contextId(), task.status(), null)));

        if (state.isSettled()) {
            turn.onSettled(SendMessageResponse.of(task.snapshot(null, true)));
            task.closeStreams();
        }
    }

    /**
     * Fails the turn with a protocol error instead of a failed task, which is what an invalid call deserves: the
     * client gets a JSON-RPC error and no task is left behind.
     *
     * @param error the error to report.
     */
    void failWithProtocolError(final JsonRpcException error) {
        if (!continuation) {
            tasks.forget(task.id());
        }
        turn.fail(error);
        task.closeStreams();
    }

    private void publish(final StreamResponse event) {
        final var snapshot = task.snapshot(null, true);
        task.publish(event, snapshot);
        push.notify(task, event);
        turn.onFirstAction(SendMessageResponse.of(snapshot));
    }

    private Message asAgentMessage(final Message message) {
        final var withRole = message.role() == Role.ROLE_AGENT
                ? message
                : new Message(
                        message.messageId(),
                        message.contextId(),
                        message.taskId(),
                        Role.ROLE_AGENT,
                        message.parts() == null ? List.of() : message.parts(),
                        message.metadata(),
                        message.extensions(),
                        message.referenceTaskIds());
        return withRole.boundTo(task.contextId(), task.id());
    }
}
