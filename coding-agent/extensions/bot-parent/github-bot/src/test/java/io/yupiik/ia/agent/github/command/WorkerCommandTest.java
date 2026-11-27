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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.DockerControlConfiguration;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmissionConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.service.Worker;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@FusionSupport(modules = GithubConfigurationTestModule.class)
class WorkerCommandTest {
    @Fusion
    private WorkerCommand.Options defaults;

    @Test
    void forwardsEachCommandsTaskAndOptionsToSharedWorker() {
        final var tasks = new ArrayList<String>();
        final var invocations = new ArrayList<WorkerOptions>();
        final var worker =
                new Worker(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new HostAdmissionConfiguration("", 1, ""),
                        null,
                        Configuration.of(Map.of()),
                        new ProviderBudgetConfiguration(null, null, null),
                        new DockerControlConfiguration("", "")) {
                    @Override
                    public void run(final String task, final WorkerOptions options) {
                        tasks.add(task);
                        invocations.add(options);
                    }
                };
        final var first = defaults.worker();
        final var second = new WorkerOptions(
                0,
                first.parallelism(),
                first.agentConfiguration(),
                "other",
                first.agentCommenting(),
                first.provisionFork(),
                first.cacheRoot(),
                first.cacheGit(),
                first.privateSource(),
                first.source(),
                first.dependencyCache());

        new WorkerCommand(new WorkerCommand.Options("first", first), null, worker, null).run();
        new WorkerCommand(new WorkerCommand.Options("second", second), null, worker, null).run();

        assertEquals(List.of("first", "second"), tasks);
        assertEquals(2, invocations.size());
        assertSame(first, invocations.getFirst());
        assertSame(second, invocations.getLast());
    }
}
