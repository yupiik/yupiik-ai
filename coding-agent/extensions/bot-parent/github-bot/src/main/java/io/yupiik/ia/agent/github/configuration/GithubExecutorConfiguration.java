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
package io.yupiik.ia.agent.github.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;

/** GitHub binding of the shared launcher settings, retaining existing operator property names. */
@RootConfiguration("github.executor")
public record GithubExecutorConfiguration(
        @Property(
                defaultValue = "io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration.Backend.DOCKER",
                documentation = "Executor backend: DOCKER, NONO or KUBERNETES.")
        ExecutorConfiguration.Backend backend,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Private attempt root, disjoint from controller state. Kubernetes requires its dedicated attempt PVC, not queue storage.")
        String attemptRoot,

        @Property(defaultValue = "3600", documentation = "Maximum execution duration in seconds.")
        int timeoutSeconds,

        @Property(defaultValue = "30", documentation = "Maximum executor control request duration in seconds.")
        int commandTimeoutSeconds,

        @Property(
                defaultValue = "\"shown\"",
                documentation =
                        "Agent tool visibility: shown, collapsed or hidden. Shown can expose sensitive tool data in execution logs.")
        String showToolOutput,

        @Property(documentation = "Docker launcher settings.")
        ExecutorConfiguration.Docker docker,

        @Property(documentation = "Nono launcher settings.") ExecutorConfiguration.Nono nono,

        @Property(documentation = "Kubernetes launcher and client settings.")
        ExecutorConfiguration.Kubernetes kubernetes,

        @Property(documentation = "Credential relay settings.")
        ExecutorConfiguration.Relay relay) {
    public GithubExecutorConfiguration {
        // Root binding must validate even when no consumer has requested the converted settings yet.
        showToolOutput = new ExecutorConfiguration(
                        backend,
                        attemptRoot,
                        timeoutSeconds,
                        commandTimeoutSeconds,
                        showToolOutput,
                        docker,
                        nono,
                        kubernetes,
                        relay)
                .showToolOutput();
    }

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
