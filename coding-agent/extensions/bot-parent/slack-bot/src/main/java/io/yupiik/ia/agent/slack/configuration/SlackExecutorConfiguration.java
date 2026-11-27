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
package io.yupiik.ia.agent.slack.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;

/** Reuses validated backend value types without changing the existing GitHub configuration contract. */
@RootConfiguration("slack.executor")
public record SlackExecutorConfiguration(
        @Property(
                defaultValue = "io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration.Backend.DOCKER",
                documentation = "Sandbox backend.")
        ExecutorConfiguration.Backend backend,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Dedicated private absolute attempt root, disjoint from slack.home, /etc/yca-slack, GitHub"
                                + " state and GitHub attempts.")
        String attemptRoot,

        @Property(defaultValue = "3600", documentation = "Maximum attempt duration seconds.")
        int timeoutSeconds,

        @Property(defaultValue = "30", documentation = "Control command timeout seconds.")
        int commandTimeoutSeconds,

        @Property(
                defaultValue = "\"hidden\"",
                documentation =
                        "Tool output visibility; use hidden for private tool activity. Neither hidden nor collapsed"
                                + " output guarantees secret redaction; logs must never be published to Slack.")
        String showToolOutput,

        @Property(documentation = "Docker settings.") ExecutorConfiguration.Docker docker,
        @Property(documentation = "Nono settings.") ExecutorConfiguration.Nono nono,
        @Property(documentation = "Kubernetes settings.") ExecutorConfiguration.Kubernetes kubernetes,

        @Property(documentation = "Trusted provider relay settings.")
        ExecutorConfiguration.Relay relay) {
    public ExecutorConfiguration shared() {
        return new ExecutorConfiguration(
                backend,
                attemptRoot,
                timeoutSeconds,
                commandTimeoutSeconds,
                showToolOutput,
                docker,
                nono,
                kubernetes,
                relay);
    }
}
