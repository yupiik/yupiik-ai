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

import static org.junit.jupiter.api.Assertions.assertSame;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.configuration.PollOptions;
import io.yupiik.ia.agent.github.service.MentionParser;
import io.yupiik.ia.agent.github.service.Poller;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

@FusionSupport(modules = GithubConfigurationTestModule.class)
class PollCommandTest {
    @Fusion
    private PollCommand.Options defaultOptions;

    @Test
    void passesEachCommandsOptionsToTheSharedPoller() {
        final var received = new ArrayList<PollOptions>();
        final var poller =
                new Poller(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new FixConfiguration(false, ""),
                        new MentionParser()) {
                    @Override
                    public int poll(final PollOptions options) {
                        received.add(options);
                        return 0;
                    }
                };
        final var defaults = defaultOptions;
        final var custom = new PollCommand.Options(new PollOptions(
                "2026-01-01T00:00:00Z",
                2,
                new PollOptions.Reviews(false),
                new PollOptions.Discussions(false),
                1,
                new PollOptions.Search(false, 60)));

        new PollCommand(defaults, poller).run();
        new PollCommand(custom, poller).run();
        new PollCommand(defaults, poller).run();

        assertSame(defaults.poll(), received.get(0));
        assertSame(custom.poll(), received.get(1));
        assertSame(defaults.poll(), received.get(2));
    }
}
