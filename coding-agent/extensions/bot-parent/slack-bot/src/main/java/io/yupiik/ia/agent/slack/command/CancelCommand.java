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
package io.yupiik.ia.agent.slack.command;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.store.SlackStore;

@DefaultScoped
@Command(
        name = {"slack", "bot", "cancel"},
        description = "Request cancellation; active sandboxes must confirm termination.")
public class CancelCommand implements Runnable {
    private final TaskOptions options;
    private final SlackStore store;

    public CancelCommand(final TaskOptions options, final SlackStore store) {
        this.options = options;
        this.store = store;
    }

    @Override
    public void run() {
        store.transaction(tasks -> {
            for (int i = 0; i < tasks.size(); i++) {
                final var task = tasks.get(i);
                if (task.id().equals(options.id())) {
                    if (task.status() == TaskStatus.recovery_blocked || task.status() == TaskStatus.publishing) {
                        throw new IllegalStateException("Blocked/publication task requires manual reconciliation");
                    }
                    if (!task.terminal()) {
                        tasks.set(
                                i,
                                task.transition(
                                        task.status() == TaskStatus.queued
                                                ? TaskStatus.cancelled
                                                : TaskStatus.cancel_requested,
                                        System.currentTimeMillis()));
                    }
                    return null;
                }
            }
            throw new IllegalArgumentException("Unknown Slack task");
        });
    }
}
