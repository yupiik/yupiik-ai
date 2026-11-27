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
package io.yupiik.fusion.a2a.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * The lifecycle states of a {@link Task}.
 * <p>
 * A task ends either in a <em>terminal</em> state - it will never change again - or waits in an
 * <em>interrupted</em> state until the client sends another message referencing it.
 * <p>
 * IMPORTANT: the constant names are the proto ones because ProtoJSON serializes an enum with its proto name, see
 * {@link io.yupiik.fusion.a2a.model}.
 */
@JsonModel
public enum TaskState {
    /**
     * Unknown or indeterminate state.
     */
    TASK_STATE_UNSPECIFIED,

    /**
     * The task was accepted but is not being processed yet.
     */
    TASK_STATE_SUBMITTED,

    /**
     * The task is being processed.
     */
    TASK_STATE_WORKING,

    /**
     * The task succeeded, this is terminal.
     */
    TASK_STATE_COMPLETED,

    /**
     * The task failed, this is terminal.
     */
    TASK_STATE_FAILED,

    /**
     * The task was canceled, this is terminal.
     */
    TASK_STATE_CANCELED,

    /**
     * The agent needs another message to go on, this is interrupted.
     */
    TASK_STATE_INPUT_REQUIRED,

    /**
     * The agent decided not to perform the task, this is terminal.
     */
    TASK_STATE_REJECTED,

    /**
     * The agent needs credentials to go on, this is interrupted.
     */
    TASK_STATE_AUTH_REQUIRED;

    /**
     * @return {@code true} when the task reached its end: it accepts no more message and any stream must be closed.
     */
    public boolean isTerminal() {
        return this == TASK_STATE_COMPLETED
                || this == TASK_STATE_FAILED
                || this == TASK_STATE_CANCELED
                || this == TASK_STATE_REJECTED;
    }

    /**
     * @return {@code true} when the task is paused waiting for the client, i.e. it is neither running nor terminal.
     */
    public boolean isInterrupted() {
        return this == TASK_STATE_INPUT_REQUIRED || this == TASK_STATE_AUTH_REQUIRED;
    }

    /**
     * @return {@code true} when a blocking {@code SendMessage} can return, i.e. the task will not progress on its own.
     */
    public boolean isSettled() {
        return isTerminal() || isInterrupted();
    }
}
