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
package io.yupiik.fusion.a2a.api;

import io.yupiik.fusion.a2a.model.Artifact;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.Task;
import io.yupiik.fusion.a2a.model.TaskState;

/**
 * How an {@link A2AExecutor} - or any bean holding a task identifier - drives a task.
 * <p>
 * Every call updates the stored task <em>and</em> publishes the matching event to the open streams and the registered
 * push notification webhooks, so a client sees the progress whatever mechanism it chose.
 * <p>
 * A task must end: call {@link #complete(Artifact...)}, {@link #fail(String)}, {@link #reject(String)},
 * {@link #cancel()}, {@link #inputRequired(Message)} or {@link #authRequired(Message)} - else a blocking
 * {@code SendMessage} waits for {@code fusion.a2a.taskTimeout} and the streams stay open. An executor throwing an
 * exception fails the task automatically.
 */
public interface TaskUpdater {
    /**
     * @return the identifier of the task being updated.
     */
    String taskId();

    /**
     * @return the conversation the task belongs to.
     */
    String contextId();

    /**
     * @return the current snapshot of the task, with its full history and artifacts.
     */
    Task task();

    /**
     * @return {@code true} once the task reached a terminal state, i.e. once it refuses any further transition. An
     * interrupted task - {@code INPUT_REQUIRED}, {@code AUTH_REQUIRED} - is <b>not</b> done: the next message the
     * client sends resumes it.
     */
    boolean isDone();

    /**
     * Answers the client without producing any task output, which is the right thing to do for a small talk exchange
     * or to ask a question before starting to work.
     * <p>
     * When it is the first thing the executor does the task is dropped and {@code SendMessage} answers the message
     * itself; on an existing task the message is only appended to the history.
     *
     * @param message the answer, its role is forced to {@link io.yupiik.fusion.a2a.model.Role#ROLE_AGENT}.
     */
    void respond(Message message);

    /**
     * Moves the task to {@code TASK_STATE_WORKING}.
     */
    void working();

    /**
     * Moves the task to {@code TASK_STATE_WORKING} with a progress message.
     *
     * @param message what is happening, it can be {@code null}.
     */
    void working(Message message);

    /**
     * Publishes an artifact, i.e. an output of the task. It does not end the task.
     *
     * @param artifact the artifact, its {@code artifactId} identifies it for the whole task.
     */
    void artifact(Artifact artifact);

    /**
     * Publishes a chunk of a streamed artifact.
     *
     * @param artifact  the chunk, reuse the same {@code artifactId} across the chunks of one artifact.
     * @param append    {@code true} to append the parts to what was already sent under this identifier.
     * @param lastChunk {@code true} when this is the final chunk.
     */
    void artifact(Artifact artifact, boolean append, boolean lastChunk);

    /**
     * Ends the task successfully.
     *
     * @param artifacts the outputs to publish before completing, they can be omitted when they were already sent with
     *                  {@link #artifact(Artifact)}.
     */
    void complete(Artifact... artifacts);

    /**
     * Ends the task on an error.
     *
     * @param reason why it failed, it is sent as the status message so the caller can read it.
     */
    void fail(String reason);

    /**
     * Ends the task by refusing to do it, which is what to answer when the agent will not even try.
     *
     * @param reason why it is refused.
     */
    void reject(String reason);

    /**
     * Ends the task as canceled, this is what {@code CancelTask} does by default.
     */
    void cancel();

    /**
     * Pauses the task until the client sends another message referencing it.
     *
     * @param message what the agent needs, it should be explicit enough for the caller to answer.
     */
    void inputRequired(Message message);

    /**
     * Pauses the task until the client authenticates.
     *
     * @param message which credentials are needed.
     */
    void authRequired(Message message);

    /**
     * The escape hatch: moves the task to any state.
     *
     * @param state   the new state.
     * @param message the status message, it can be {@code null}.
     */
    void status(TaskState state, Message message);

    /**
     * Replaces the state of the task with the one of {@code task}: its status is applied and its artifacts are
     * published. It is how a skill returning a {@link Task} is honored.
     *
     * @param task the state to adopt, its {@code id} and {@code contextId} are ignored.
     */
    void adopt(Task task);
}
