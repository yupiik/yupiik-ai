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
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.ContextConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.execution.cache.DependencyCache;
import io.yupiik.ia.agent.github.execution.cache.DependencyCacheProvisioning;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.logging.Logger;

/** Finite command suitable for host timers and a dispatcher-only Kubernetes CronJob. */
@DefaultScoped
@Command(
        name = {"github", "bot", "cache", "maintain"},
        description = "Run a bounded dependency-cache maintenance slice; no effect when caching is OFF.")
public class CacheMaintenanceCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(CacheMaintenanceCommand.class.getName());
    private final Options options;
    private final Clock clock;
    private final GithubBotConfiguration configuration;
    private final ExecutorConfiguration execution;
    private final ContextConfiguration contexts;

    public CacheMaintenanceCommand(
            final Options options,
            final Clock clock,
            final GithubBotConfiguration configuration,
            final ExecutorConfiguration execution,
            final ContextConfiguration contexts) {
        this.options = options;
        this.clock = clock;
        this.configuration = configuration;
        this.execution = execution;
        this.contexts = contexts;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(documentation = "The same trusted worker policy used for execution.")
            WorkerOptions worker,

            @Property(defaultValue = "1024", documentation = "Maximum maintenance records processed per invocation.")
            int budget) {}

    @Override
    public void run() {
        if (options.budget() < 1 || options.budget() > 100000) {
            throw new IllegalArgumentException("Cache maintenance budget must be between 1 and 100000");
        }
        final var policy = options.worker().dependencyCache();
        if (policy == null || !policy.enabled()) {
            return;
        }
        final var protectedRoots = new ArrayList<Path>();
        protectedRoots.add(Path.of(configuration.home()));
        if (!options.worker().cacheRoot().isBlank()) {
            protectedRoots.add(Path.of(options.worker().cacheRoot()));
        }
        if (!execution.attemptRoot().isBlank()) {
            protectedRoots.add(Path.of(execution.attemptRoot()));
        }
        if (!contexts.storageRoot().isBlank()) {
            protectedRoots.add(Path.of(contexts.storageRoot()));
        }
        if (execution.backend() == ExecutorConfiguration.Backend.NONO
                && !execution.nono().runtimeRoot().isBlank()) {
            protectedRoots.add(Path.of(execution.nono().runtimeRoot()));
        }
        DependencyCacheProvisioning.validatePrivateRoot(Path.of(policy.root()), protectedRoots);
        try {
            final var result = new DependencyCache(policy, clock).maintain(options.budget());
            LOGGER.info("Dependency cache maintenance: epoch=" + result.epoch()
                    + ", chargedBytes=" + result.chargedBytes() + ", processed=" + result.processed()
                    + ", busy=" + result.busy());
        } catch (final IOException failure) {
            throw new IllegalStateException("Dependency cache maintenance failed", failure);
        }
    }
}
