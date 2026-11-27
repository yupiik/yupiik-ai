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
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.service.OrphanCleanup;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = {"github", "bot", "cleanup"},
        description = "Remove old unreferenced attempts that never became launchable, fencing workers first.")
public class CleanupCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(CleanupCommand.class.getName());
    private final Options options;
    private final GithubBotConfiguration conf;
    private final ExecutorConfiguration executor;
    private final JsonMapper json;
    private final TaskStore store;
    private final Clock clock;

    public CleanupCommand(
            final Options options,
            final GithubBotConfiguration conf,
            final ExecutorConfiguration executor,
            final JsonMapper json,
            final TaskStore store,
            final Clock clock) {
        this.options = options;
        this.conf = conf;
        this.executor = executor;
        this.json = json;
        this.store = store;
        this.clock = clock;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    defaultValue = "24",
                    documentation = "Minimum age in hours for unreferenced prepared-only attempts.")
            int minimumAgeHours,

            @Property(
                    defaultValue = "true",
                    documentation = "Preview cleanup without deleting attempts; disable explicitly to delete.")
            boolean dryRun) {}

    @Override
    public void run() {
        if (executor.attemptRoot().isBlank()) {
            throw new IllegalArgumentException(
                    "Cleanup requires github.executor.attemptRoot, never the shared temporary directory");
        }
        final var home = Path.of(conf.home());
        LOGGER.info(json.toString(new OrphanCleanup(store, home, Path.of(executor.attemptRoot()), clock)
                .cleanup(Duration.ofHours(options.minimumAgeHours()), options.dryRun())));
    }
}
