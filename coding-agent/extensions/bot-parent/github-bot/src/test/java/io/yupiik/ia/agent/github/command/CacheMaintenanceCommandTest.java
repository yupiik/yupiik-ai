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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.configuration.ContextConfiguration;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheMaintenanceCommandTest {
    @Nested
    @FusionSupport(modules = DisabledModule.class)
    class Disabled {
        @TempDir
        private static Path root;

        @Test
        void disabledCommandDoesNotCreateAnyCacheOrWorkerDirectories(
                @Fusion final CacheMaintenanceCommand.Options options,
                @Fusion final GithubBotConfiguration bot,
                @Fusion final GithubExecutorConfiguration executor,
                @Fusion final ContextConfiguration contexts) {
            command(options, bot, executor, contexts).run();
            assertFalse(Files.exists(root.resolve("cache")));
            assertFalse(Files.exists(root.resolve("state")));
        }

        @Test
        void rejectsUnboundedMaintenanceBudgets(
                @Fusion final CacheMaintenanceCommand.Options options,
                @Fusion final GithubBotConfiguration bot,
                @Fusion final GithubExecutorConfiguration executor,
                @Fusion final ContextConfiguration contexts) {
            for (final var budget : new int[] {0, -1, 100001}) {
                final var command =
                        command(new CacheMaintenanceCommand.Options(options.worker(), budget), bot, executor, contexts);
                assertThrows(IllegalArgumentException.class, command::run);
            }
        }
    }

    @Nested
    @FusionSupport(modules = EnabledModule.class)
    class Enabled {
        @TempDir
        private static Path root;

        @Test
        void enabledCommandRunsOneFiniteSliceWithoutLaunchingAnAgent(
                @Fusion final CacheMaintenanceCommand.Options options,
                @Fusion final GithubBotConfiguration bot,
                @Fusion final GithubExecutorConfiguration executor,
                @Fusion final ContextConfiguration contexts) {
            final var command = command(options, bot, executor, contexts);
            assertDoesNotThrow(command::run);
            assertTrue(Files.isRegularFile(root.resolve("cache/state")));
            assertFalse(Files.exists(root.resolve("state")));
            assertDoesNotThrow(command::run);
        }
    }

    @Nested
    @FusionSupport(modules = OverlapModule.class)
    class Overlap {
        @TempDir
        private static Path root;

        @Test
        void refusesStateAndAttemptStorageOverlapBeforeCacheInitialization(
                @Fusion final CacheMaintenanceCommand.Options options,
                @Fusion final GithubBotConfiguration bot,
                @Fusion final GithubExecutorConfiguration executor,
                @Fusion final ContextConfiguration contexts) {
            final var baseline = options.worker();
            final var cachePolicy = baseline.dependencyCache();
            for (final var cache : new String[] {
                "state", "state/cache", "attempts", "attempts/cache", "contexts/cache", "git-cache/dependencies"
            }) {
                final var policy = new DependencyCacheConfiguration(
                        cachePolicy.mode(),
                        root.resolve(cache).toString(),
                        cachePolicy.scope(),
                        cachePolicy.compatibility(),
                        cachePolicy.presets(),
                        cachePolicy.entries(),
                        cachePolicy.environment(),
                        cachePolicy.files(),
                        cachePolicy.gracefulTimeoutSeconds(),
                        cachePolicy.maintenance(),
                        cachePolicy.limits());
                final var worker = new WorkerOptions(
                        baseline.maxRetries(),
                        baseline.parallelism(),
                        baseline.agentConfiguration(),
                        baseline.forkOwner(),
                        baseline.agentCommenting(),
                        baseline.provisionFork(),
                        baseline.cacheRoot(),
                        baseline.cacheGit(),
                        baseline.privateSource(),
                        baseline.source(),
                        policy);
                final var command =
                        command(new CacheMaintenanceCommand.Options(worker, options.budget()), bot, executor, contexts);
                assertThrows(IllegalArgumentException.class, command::run);
                assertFalse(Files.exists(root.resolve(cache)));
            }
        }
    }

    public static class DisabledModule extends GithubConfigurationTestModule {
        public DisabledModule() {
            super(Map.of(
                    "github.home",
                    Disabled.root.resolve("state").toString(),
                    "worker.dependencyCache.root",
                    Disabled.root.resolve("cache").toString()));
        }
    }

    public static class EnabledModule extends GithubConfigurationTestModule {
        public EnabledModule() {
            super(Map.of(
                    "github.home",
                    Enabled.root.resolve("state").toString(),
                    "worker.dependencyCache.root",
                    Enabled.root.resolve("cache").toString(),
                    "worker.dependencyCache.mode",
                    "READ_WRITE",
                    "budget",
                    "1"));
        }
    }

    public static class OverlapModule extends GithubConfigurationTestModule {
        public OverlapModule() {
            super(Map.of(
                    "github.home",
                    Overlap.root.resolve("state").toString(),
                    "worker.dependencyCache.root",
                    Overlap.root.resolve("cache").toString(),
                    "worker.dependencyCache.mode",
                    "RESTORE_ONLY",
                    "github.executor.attemptRoot",
                    Overlap.root.resolve("attempts").toString(),
                    "github.context.storageRoot",
                    Overlap.root.resolve("contexts").toString(),
                    "worker.cacheRoot",
                    Overlap.root.resolve("git-cache").toString()));
        }
    }

    private CacheMaintenanceCommand command(
            final CacheMaintenanceCommand.Options options,
            final GithubBotConfiguration bot,
            final GithubExecutorConfiguration executor,
            final ContextConfiguration contexts) {
        return new CacheMaintenanceCommand(options, BotTestConfiguration.CLOCK, bot, executor.shared(), contexts);
    }
}
