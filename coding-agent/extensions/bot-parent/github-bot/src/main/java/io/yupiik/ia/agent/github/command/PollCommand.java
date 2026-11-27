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
import io.yupiik.ia.agent.github.configuration.PollOptions;
import io.yupiik.ia.agent.github.service.Poller;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = {"github", "bot", "poll"},
        description = "Poll GitHub mentions into the durable queue.")
public class PollCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(PollCommand.class.getName());
    private final Options options;
    private final Poller poller;

    public PollCommand(final Options options, final Poller poller) {
        this.options = options;
        this.poller = poller;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(documentation = "Polling operation settings.")
            PollOptions poll) {}

    @Override
    public void run() {
        LOGGER.info(String.valueOf(poller.poll(options.poll())));
    }
}
