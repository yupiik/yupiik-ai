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
import io.yupiik.ia.agent.github.service.BotMaintenance;
import java.time.Duration;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = {"github", "bot", "purge"},
        description = "Purge expired clean terminal tasks.")
public class PurgeCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(PurgeCommand.class.getName());
    private final Options options;
    private final BotMaintenance maintenance;

    public PurgeCommand(final Options options, final BotMaintenance maintenance) {
        this.options = options;
        this.maintenance = maintenance;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    defaultValue = "7",
                    documentation = "Terminal task retention in days; receipts are retained indefinitely.")
            int retentionDays,

            @Property(defaultValue = "false", documentation = "Preview without changing tasks or receipts.")
            boolean dryRun) {}

    @Override
    public void run() {
        LOGGER.info(String.valueOf(maintenance.purge(Duration.ofDays(options.retentionDays()), options.dryRun())));
    }
}
