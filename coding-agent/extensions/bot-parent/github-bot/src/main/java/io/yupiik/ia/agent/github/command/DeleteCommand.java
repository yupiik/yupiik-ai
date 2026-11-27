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
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.service.BotAudit;
import io.yupiik.ia.agent.github.service.TaskDiagnostics;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = {"github", "bot", "delete"},
        description = "Delete an inactive task, retaining receipts.")
public class DeleteCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(DeleteCommand.class.getName());
    private final Options options;
    private final JsonMapper json;
    private final TaskStore store;
    private final BotAudit audit;
    private final TaskDiagnostics diagnostics;

    public DeleteCommand(
            final Options options,
            final JsonMapper json,
            final TaskStore store,
            final BotAudit audit,
            final TaskDiagnostics diagnostics) {
        this.options = options;
        this.json = json;
        this.store = store;
        this.audit = audit;
        this.diagnostics = diagnostics;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(defaultValue = "\"\"", documentation = "Inactive task id to delete.")
            String task,

            @Property(
                    defaultValue = "false",
                    documentation = "Validate and preview without changing tasks or audit events.")
            boolean dryRun) {}

    @Override
    public void run() {
        final var deleted = store.transaction(state -> {
            final var task = state.task(options.task());
            if (Set.of(TaskState.running, TaskState.publishing).contains(task.state())) {
                throw new IllegalStateException("Cannot delete active or ambiguous publication task");
            }
            if ((task.attemptDirectory() != null)) {
                throw new IllegalStateException("Cannot delete a task with retained attempt data");
            }
            if (!options.dryRun()) {
                try {
                    diagnostics.delete(options.task());
                } catch (final IOException e) {
                    throw new UncheckedIOException("Unable to delete private task diagnostics", e);
                }
                state.tasks().remove(options.task());
            }
            return task.document();
        });
        if (options.dryRun()) {
            LOGGER.info(json.toString(deleted));
        } else {
            audit.record("manual-deleted", deleted);
        }
    }
}
