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
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.store.SlackStore;

@DefaultScoped
@Command(
        name = {"slack", "bot", "cleanup"},
        description = "Expire terminal task receipts after configured retention; never clean uncertain runtimes.")
public class CleanupCommand implements Runnable {
    private final SlackConfiguration configuration;
    private final SlackStore store;

    @io.yupiik.fusion.framework.build.api.configuration.RootConfiguration("-")
    public record Options() {}

    public CleanupCommand(final Options options, final SlackConfiguration configuration, final SlackStore store) {
        this.configuration = configuration;
        this.store = store;
    }

    @Override
    public void run() {
        final var threshold = System.currentTimeMillis() - Math.max(86400, configuration.retentionSeconds()) * 1000L;
        store.transaction(tasks -> {
            tasks.removeIf(task -> task.terminal() && task.updated() < threshold);
            return null;
        });
    }
}
