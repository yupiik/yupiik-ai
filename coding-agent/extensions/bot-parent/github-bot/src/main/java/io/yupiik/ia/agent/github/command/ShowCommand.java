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
import io.yupiik.ia.agent.github.service.TaskDiagnostics;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = {"github", "bot", "show"},
        description = "Show one durable task.")
public class ShowCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ShowCommand.class.getName());
    private final Options options;
    private final JsonMapper json;
    private final TaskStore store;
    private final TaskDiagnostics diagnostics;

    public ShowCommand(
            final Options options, final JsonMapper json, final TaskStore store, final TaskDiagnostics diagnostics) {
        this.options = options;
        this.json = json;
        this.store = store;
        this.diagnostics = diagnostics;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(defaultValue = "\"\"", documentation = "Task id to show.")
            String task,

            @Property(
                    defaultValue = "false",
                    documentation =
                            "Explicitly print private failure messages and log tails; may contain secrets. Do not send this output to public logs.")
            boolean diagnostics) {}

    @Override
    public void run() {
        final var task = store.transaction(state -> state.task(options.task()).document());
        if (options.diagnostics()) {
            // Intentional CLI-only output: do not forward private evidence to inherited JUL handlers.
            try {
                System.out.println(diagnostics.read(options.task()));
            } catch (final IOException e) {
                throw new UncheckedIOException("Unable to read private task diagnostics", e);
            }
        } else {
            LOGGER.info(json.toString(task));
        }
    }
}
