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
import io.yupiik.ia.agent.github.store.TaskStore;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = {"github", "bot", "retry"},
        description = "Retry an unpublished failed task.")
public class RetryCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(RetryCommand.class.getName());
    private final Options options;
    private final JsonMapper json;
    private final TaskStore store;
    private final BotAudit audit;

    public RetryCommand(final Options options, final JsonMapper json, final TaskStore store, final BotAudit audit) {
        this.options = options;
        this.json = json;
        this.store = store;
        this.audit = audit;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(defaultValue = "\"\"", documentation = "Failed unpublished task id to retry.")
            String task,

            @Property(
                    defaultValue = "false",
                    documentation = "Validate and preview without changing tasks or audit events.")
            boolean dryRun) {}

    @Override
    public void run() {
        final var retried = store.transaction(state -> {
            final var task = state.task(options.task());
            if (task.state() != TaskState.failed) {
                throw new IllegalStateException("Only failed tasks can be retried; reconcile publishing tasks first");
            }
            if ((task.publicationIntent() != null)
                    || (task.strictSolvePublication() != null)
                    || (task.reviewIntent() != null)
                    || (task.attemptDirectory() != null)) {
                throw new IllegalStateException(
                        "Create a new task after published failure; retained attempts require operator" + " cleanup");
            }
            if (!options.dryRun()) {
                task.state(TaskState.pending);
            }
            return task.document();
        });
        if (options.dryRun()) {
            LOGGER.info(json.toString(retried));
        } else {
            audit.record("manual-retried", retried);
        }
    }
}
