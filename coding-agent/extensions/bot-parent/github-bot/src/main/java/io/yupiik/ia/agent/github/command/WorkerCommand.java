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
package io.yupiik.ia.agent.github.command;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.service.Worker;
import io.yupiik.ia.agent.github.store.TaskStore;

@DefaultScoped
@Command(
        name = {"github", "bot", "worker"},
        description = "Execute isolated pending tasks.")
public class WorkerCommand implements Runnable {
    private final Options options;
    private final TaskStore store;
    private final Worker worker;
    private final NotificationService notifications;

    public WorkerCommand(
            final Options options,
            final TaskStore store,
            final Worker worker,
            final NotificationService notifications) {
        this.options = options;
        this.store = store;
        this.worker = worker;
        this.notifications = notifications;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(defaultValue = "\"\"", documentation = "Optional task id; empty selects the next pending task.")
            String task,

            @Property(documentation = "Worker operation settings.")
            WorkerOptions worker) {}

    @Override
    public void run() {
        worker.run(options.task(), options.worker());
        if (notifications != null) {
            final var failed = store.transaction(state -> {
                final var selected = state.list().stream()
                        .filter(task ->
                                task.state() == TaskState.failed && !Boolean.TRUE.equals(task.failureNotified()))
                        .toList();
                for (final var task : selected) {
                    state.task(task.id()).failureNotified(true);
                }
                return selected;
            });
            for (final var task : failed) {
                notifications.onTurnEnd(
                        task.id(),
                        "GitHub bot task failed",
                        "",
                        "github-bot",
                        false,
                        "Task " + task.id() + " in " + task.repository() + " requires operator attention.");
            }
        }
    }
}
