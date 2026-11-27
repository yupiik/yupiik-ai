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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.github.command.PollCommand;
import io.yupiik.ia.agent.github.command.PurgeCommand;
import io.yupiik.ia.agent.github.command.WorkerCommand;
import io.yupiik.ia.agent.github.service.RepositorySelector;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GithubBotConfigurationTest {
    @Nested
    @FusionSupport(modules = DefaultsModule.class)
    class Defaults {
        @Test
        void bindsConfiguration(
                @Fusion final GithubBotConfiguration shared,
                @Fusion final GithubExecutorConfiguration executorRoot,
                @Fusion final PollCommand.Options options,
                @Fusion final WorkerCommand.Options worker,
                @Fusion final PurgeCommand.Options purge) {
            final var executor = executorRoot.shared();
            assertEquals(GithubBotConfiguration.AuthType.pat, shared.auth().type());
            assertEquals("GH_TOKEN", shared.auth().tokenEnv());
            assertEquals("GH_AGENT_TOKEN", shared.auth().agentTokenEnv());
            assertEquals("", shared.authorization().userIds());
            assertTrue(shared.authorization().allowRepositoryWriters());
            assertEquals(7200, shared.authorization().permissionsCacheTtlSeconds());
            assertEquals(4096, shared.authorization().permissionsCacheMaxEntries());
            assertEquals(
                    List.of(RepositorySelector.Visibility.values()),
                    shared.repositories().visibility());
            assertEquals(ExecutorConfiguration.Backend.DOCKER, executor.backend());
            assertEquals("docker", executor.docker().executable());
            assertNull(executor.docker().egress(), "Absent nested configuration must not enable the gateway");
            assertEquals("nono", executor.nono().executable());
            assertEquals("yca-attempts", executor.kubernetes().claim());
            assertEquals("", executor.relay().bind());
            assertEquals("now", options.poll().since());
            assertEquals(20, options.poll().pageBudget());
            assertFalse(options.poll().reviews().enabled());
            assertEquals(2, worker.worker().maxRetries());
            assertEquals(1, worker.worker().parallelism());
            assertEquals(7, purge.retentionDays());
        }
    }

    public static class DefaultsModule extends GithubConfigurationTestModule {
        public DefaultsModule() {
            super(Map.of());
        }
    }

    @Nested
    @FusionSupport(modules = ConfiguredSubgraphsModule.class)
    class ConfiguredSubgraphs {
        @Test
        void bindsConfiguration(
                @Fusion final GithubBotConfiguration shared,
                @Fusion final GithubExecutorConfiguration executorRoot,
                @Fusion final PollCommand.Options options,
                @Fusion final WorkerCommand.Options worker,
                @Fusion final PurgeCommand.Options purge) {
            final var executor = executorRoot.shared();
            assertEquals(GithubBotConfiguration.AuthType.app, shared.auth().type());
            assertEquals("123", shared.auth().appId());
            assertEquals(456, shared.auth().installationId());
            assertEquals("/private/key.pem", shared.auth().privateKeyFile());
            assertEquals("42", shared.authorization().userIds());
            assertEquals("acme/dev", shared.authorization().teams());
            assertFalse(shared.authorization().allowRepositoryWriters());
            assertEquals(60, shared.authorization().permissionsCacheTtlSeconds());
            assertEquals(16, shared.authorization().permissionsCacheMaxEntries());
            assertEquals("acme/repo", shared.repositories().explicit());
            assertEquals("org:acme", shared.repositories().discovery());
            assertEquals("acme/*", shared.repositories().includes());
            assertTrue(shared.repositories().excludeForks());
            assertEquals(ExecutorConfiguration.Backend.KUBERNETES, executor.backend());
            assertEquals("/bin/docker", executor.docker().executable());
            assertEquals("/runtime", executor.nono().runtimeRoot());
            assertEquals("https://cluster.example", executor.kubernetes().master());
            assertEquals("/private/token", executor.kubernetes().token());
            assertEquals("/private/ca.pem", executor.kubernetes().certificates());
            assertEquals("key-pem", executor.kubernetes().privateKey());
            assertEquals("cert-pem", executor.kubernetes().privateKeyCertificate());
            assertEquals("/private/kube.json", executor.kubernetes().kubeconfig());
            assertEquals(1234, executor.kubernetes().uid());
            assertEquals(5678, executor.kubernetes().gid());
            assertTrue(executor.kubernetes().skipTls());
            assertEquals("127.0.0.1", executor.relay().bind());
            assertEquals("2026-01-01T00:00:00Z", options.poll().since());
            assertEquals(3, options.poll().pageBudget());
            assertTrue(options.poll().reviews().enabled());
            assertEquals(4, worker.worker().parallelism());
            assertEquals(5, worker.worker().maxRetries());
            assertEquals("/cache", worker.worker().cacheRoot());
            assertEquals(10, purge.retentionDays());
        }
    }

    public static class ConfiguredSubgraphsModule extends GithubConfigurationTestModule {
        public ConfiguredSubgraphsModule() {
            super(Map.ofEntries(
                    Map.entry("github.auth.type", "app"),
                    Map.entry("github.auth.appId", "123"),
                    Map.entry("github.auth.installationId", "456"),
                    Map.entry("github.auth.privateKeyFile", "/private/key.pem"),
                    Map.entry("github.authorization.userIds", "42"),
                    Map.entry("github.authorization.teams", "acme/dev"),
                    Map.entry("github.authorization.allowRepositoryWriters", "false"),
                    Map.entry("github.authorization.permissionsCacheTtlSeconds", "60"),
                    Map.entry("github.authorization.permissionsCacheMaxEntries", "16"),
                    Map.entry("github.repositories.explicit", "acme/repo"),
                    Map.entry("github.repositories.discovery", "org:acme"),
                    Map.entry("github.repositories.includes", "acme/*"),
                    Map.entry("github.repositories.excludeForks", "true"),
                    Map.entry("github.executor.backend", "KUBERNETES"),
                    Map.entry("github.executor.docker.executable", "/bin/docker"),
                    Map.entry("github.executor.nono.runtimeRoot", "/runtime"),
                    Map.entry("github.executor.kubernetes.master", "https://cluster.example"),
                    Map.entry("github.executor.kubernetes.token", "/private/token"),
                    Map.entry("github.executor.kubernetes.certificates", "/private/ca.pem"),
                    Map.entry("github.executor.kubernetes.privateKey", "key-pem"),
                    Map.entry("github.executor.kubernetes.privateKeyCertificate", "cert-pem"),
                    Map.entry("github.executor.kubernetes.kubeconfig", "/private/kube.json"),
                    Map.entry("github.executor.kubernetes.uid", "1234"),
                    Map.entry("github.executor.kubernetes.gid", "5678"),
                    Map.entry("github.executor.kubernetes.skipTls", "true"),
                    Map.entry("github.executor.relay.bind", "127.0.0.1"),
                    Map.entry("poll.since", "2026-01-01T00:00:00Z"),
                    Map.entry("poll.pageBudget", "3"),
                    Map.entry("poll.reviews.enabled", "true"),
                    Map.entry("worker.parallelism", "4"),
                    Map.entry("worker.maxRetries", "5"),
                    Map.entry("worker.cacheRoot", "/cache"),
                    Map.entry("retentionDays", "10")));
        }
    }

    @Nested
    @FusionSupport(modules = NegativeCacheTtlModule.class)
    class NegativeCacheTtl {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(GithubBotConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GithubBotConfiguration.Authorization("", "", true, -1, 4096));
        }
    }

    public static class NegativeCacheTtlModule extends GithubConfigurationTestModule {
        public NegativeCacheTtlModule() {
            super(Map.ofEntries(Map.entry("github.authorization.permissionsCacheTtlSeconds", "-1")));
        }
    }

    @Nested
    @FusionSupport(modules = NegativeCacheCapacityModule.class)
    class NegativeCacheCapacity {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(GithubBotConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GithubBotConfiguration.Authorization("", "", true, 7200, -1));
        }
    }

    public static class NegativeCacheCapacityModule extends GithubConfigurationTestModule {
        public NegativeCacheCapacityModule() {
            super(Map.ofEntries(Map.entry("github.authorization.permissionsCacheMaxEntries", "-1")));
        }
    }

    @Nested
    @FusionSupport(modules = ZeroCacheCapacityModule.class)
    class ZeroCacheCapacity {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(GithubBotConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GithubBotConfiguration.Authorization("", "", true, 7200, 0));
        }
    }

    public static class ZeroCacheCapacityModule extends GithubConfigurationTestModule {
        public ZeroCacheCapacityModule() {
            super(Map.ofEntries(Map.entry("github.authorization.permissionsCacheMaxEntries", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = TooLargeCacheCapacityModule.class)
    class TooLargeCacheCapacity {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(GithubBotConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GithubBotConfiguration.Authorization("", "", true, 7200, 65537));
        }
    }

    public static class TooLargeCacheCapacityModule extends GithubConfigurationTestModule {
        public TooLargeCacheCapacityModule() {
            super(Map.ofEntries(Map.entry("github.authorization.permissionsCacheMaxEntries", "65537")));
        }
    }

    @Nested
    @FusionSupport(modules = IntegerMaximumCacheCapacityModule.class)
    class IntegerMaximumCacheCapacity {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(GithubBotConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GithubBotConfiguration.Authorization("", "", true, 7200, 2147483647));
        }
    }

    public static class IntegerMaximumCacheCapacityModule extends GithubConfigurationTestModule {
        public IntegerMaximumCacheCapacityModule() {
            super(Map.ofEntries(Map.entry("github.authorization.permissionsCacheMaxEntries", "2147483647")));
        }
    }

    @Nested
    @FusionSupport(modules = MinimumCacheCapacityModule.class)
    class MinimumCacheCapacity {
        @Test
        void bindsConfiguration(@Fusion final GithubBotConfiguration shared) {
            assertEquals(1, shared.authorization().permissionsCacheMaxEntries());
        }
    }

    public static class MinimumCacheCapacityModule extends GithubConfigurationTestModule {
        public MinimumCacheCapacityModule() {
            super(Map.ofEntries(Map.entry("github.authorization.permissionsCacheMaxEntries", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = MaximumCacheCapacityModule.class)
    class MaximumCacheCapacity {
        @Test
        void bindsConfiguration(@Fusion final GithubBotConfiguration shared) {
            assertEquals(65536, shared.authorization().permissionsCacheMaxEntries());
        }
    }

    public static class MaximumCacheCapacityModule extends GithubConfigurationTestModule {
        public MaximumCacheCapacityModule() {
            super(Map.ofEntries(Map.entry("github.authorization.permissionsCacheMaxEntries", "65536")));
        }
    }

    @Nested
    @FusionSupport(modules = ZeroCacheTtlModule.class)
    class ZeroCacheTtl {
        @Test
        void bindsConfiguration(@Fusion final GithubBotConfiguration shared) {
            assertEquals(0, shared.authorization().permissionsCacheTtlSeconds());
        }
    }

    public static class ZeroCacheTtlModule extends GithubConfigurationTestModule {
        public ZeroCacheTtlModule() {
            super(Map.ofEntries(Map.entry("github.authorization.permissionsCacheTtlSeconds", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = LegacyKeysModule.class)
    class LegacyKeys {
        @Test
        void bindsConfiguration(
                @Fusion final GithubBotConfiguration shared,
                @Fusion final GithubExecutorConfiguration executorRoot,
                @Fusion final PollCommand.Options options,
                @Fusion final WorkerCommand.Options worker,
                @Fusion final PurgeCommand.Options purge) {
            final var executor = executorRoot.shared();
            assertEquals(1, worker.worker().parallelism());
            assertEquals(7, purge.retentionDays());
            assertFalse(options.poll().reviews().enabled());
            assertEquals("yca", executor.kubernetes().namespace());
        }
    }

    public static class LegacyKeysModule extends GithubConfigurationTestModule {
        public LegacyKeysModule() {
            super(Map.ofEntries(
                    Map.entry("github.parallelism", "9"),
                    Map.entry("github.pollReviews", "true"),
                    Map.entry("github.executor.namespace", "legacy"),
                    Map.entry("bot.worker.parallelism", "8"),
                    Map.entry("bot.poll.pollReviews", "true"),
                    Map.entry("bot.purge.retentionDays", "99")));
        }
    }
}
