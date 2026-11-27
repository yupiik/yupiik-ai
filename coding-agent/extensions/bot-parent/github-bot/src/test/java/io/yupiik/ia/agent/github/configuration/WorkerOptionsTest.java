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
import io.yupiik.ia.agent.github.command.WorkerCommand.Options;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WorkerOptionsTest {
    @Nested
    @FusionSupport(modules = DefaultsModule.class)
    class Defaults {
        @Test
        void bindsConfiguration(@Fusion final Options root) {
            final var worker = root.worker();
            assertEquals(WorkerOptions.SourceMode.export, worker.source().mode());
            assertEquals("", worker.source().tokenEnv());
            assertEquals(1000, worker.source().maxDepth());
            assertEquals(536870912L, worker.source().maxBytes());
            assertEquals(120, worker.source().commandTimeoutSeconds());
            assertTrue(worker.agentCommenting());
            assertFalse(worker.privateSource());
        }
    }

    public static class DefaultsModule extends GithubConfigurationTestModule {
        public DefaultsModule() {
            super(Map.of());
        }
    }

    @Nested
    @FusionSupport(modules = GitSourceModule.class)
    class GitSource {
        @Test
        void bindsConfiguration(@Fusion final Options root) {
            final var worker = root.worker();
            assertEquals(WorkerOptions.SourceMode.git, worker.source().mode());
            assertEquals("READ_ONLY_SOURCE_PAT", worker.source().tokenEnv());
            assertEquals(2000, worker.source().maxDepth());
            assertEquals(1073741824L, worker.source().maxBytes());
            assertEquals(300, worker.source().commandTimeoutSeconds());
            assertTrue(worker.agentCommenting());
        }
    }

    public static class GitSourceModule extends GithubConfigurationTestModule {
        public GitSourceModule() {
            super(Map.ofEntries(
                    Map.entry("worker.source.mode", "git"),
                    Map.entry("worker.source.tokenEnv", "READ_ONLY_SOURCE_PAT"),
                    Map.entry("worker.source.maxDepth", "2000"),
                    Map.entry("worker.source.maxBytes", "1073741824"),
                    Map.entry("worker.source.commandTimeoutSeconds", "300")));
        }
    }

    @Nested
    @FusionSupport(modules = ExportTokenModule.class)
    class ExportToken {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            final var failure = assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(Options.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
            assertFalse(failure.getMessage().contains("READ_ONLY_SOURCE_PAT"));
        }
    }

    public static class ExportTokenModule extends GithubConfigurationTestModule {
        public ExportTokenModule() {
            super(Map.ofEntries(Map.entry("worker.source.tokenEnv", "READ_ONLY_SOURCE_PAT")));
        }
    }

    @Nested
    @FusionSupport(modules = BrokerTokenModule.class)
    class BrokerToken {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            final var failure = assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(Options.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
            assertFalse(failure.getMessage().contains("PRIVATE_READ_PAT"));
        }
    }

    public static class BrokerTokenModule extends GithubConfigurationTestModule {
        public BrokerTokenModule() {
            super(Map.ofEntries(
                    Map.entry("worker.source.mode", "broker"),
                    Map.entry("worker.agentCommenting", "false"),
                    Map.entry("worker.source.tokenEnv", "PRIVATE_READ_PAT")));
        }
    }

    @Nested
    @FusionSupport(modules = ZeroDepthModule.class)
    class ZeroDepth {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(Options.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class ZeroDepthModule extends GithubConfigurationTestModule {
        public ZeroDepthModule() {
            super(Map.ofEntries(Map.entry("worker.source.maxDepth", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = NegativeDepthModule.class)
    class NegativeDepth {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(Options.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class NegativeDepthModule extends GithubConfigurationTestModule {
        public NegativeDepthModule() {
            super(Map.ofEntries(Map.entry("worker.source.maxDepth", "-1")));
        }
    }

    @Nested
    @FusionSupport(modules = ExcessiveDepthModule.class)
    class ExcessiveDepth {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(Options.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class ExcessiveDepthModule extends GithubConfigurationTestModule {
        public ExcessiveDepthModule() {
            super(Map.ofEntries(Map.entry("worker.source.maxDepth", "100001")));
        }
    }

    @Nested
    @FusionSupport(modules = BelowMinimumBytesModule.class)
    class BelowMinimumBytes {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(Options.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class BelowMinimumBytesModule extends GithubConfigurationTestModule {
        public BelowMinimumBytesModule() {
            super(Map.ofEntries(Map.entry("worker.source.maxBytes", "1048575")));
        }
    }

    @Nested
    @FusionSupport(modules = ExcessiveBytesModule.class)
    class ExcessiveBytes {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(Options.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class ExcessiveBytesModule extends GithubConfigurationTestModule {
        public ExcessiveBytesModule() {
            super(Map.ofEntries(Map.entry("worker.source.maxBytes", "17179869185")));
        }
    }

    @Nested
    @FusionSupport(modules = ZeroTimeoutModule.class)
    class ZeroTimeout {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(Options.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class ZeroTimeoutModule extends GithubConfigurationTestModule {
        public ZeroTimeoutModule() {
            super(Map.ofEntries(Map.entry("worker.source.commandTimeoutSeconds", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = NegativeTimeoutModule.class)
    class NegativeTimeout {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(Options.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class NegativeTimeoutModule extends GithubConfigurationTestModule {
        public NegativeTimeoutModule() {
            super(Map.ofEntries(Map.entry("worker.source.commandTimeoutSeconds", "-1")));
        }
    }

    @Nested
    @FusionSupport(modules = ExcessiveTimeoutModule.class)
    class ExcessiveTimeout {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(Options.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class ExcessiveTimeoutModule extends GithubConfigurationTestModule {
        public ExcessiveTimeoutModule() {
            super(Map.ofEntries(Map.entry("worker.source.commandTimeoutSeconds", "3601")));
        }
    }

    @Nested
    @FusionSupport(modules = MinimumLimitsModule.class)
    class MinimumLimits {
        @Test
        void bindsConfiguration(@Fusion final Options root) {
            final var source = root.worker().source();
            assertEquals(1, source.maxDepth());
            assertEquals(1048576L, source.maxBytes());
            assertEquals(1, source.commandTimeoutSeconds());
        }
    }

    public static class MinimumLimitsModule extends GithubConfigurationTestModule {
        public MinimumLimitsModule() {
            super(Map.ofEntries(
                    Map.entry("worker.source.maxDepth", "1"),
                    Map.entry("worker.source.maxBytes", "1048576"),
                    Map.entry("worker.source.commandTimeoutSeconds", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = MaximumLimitsModule.class)
    class MaximumLimits {
        @Test
        void bindsConfiguration(@Fusion final Options root) {
            final var source = root.worker().source();
            assertEquals(100000, source.maxDepth());
            assertEquals(17179869184L, source.maxBytes());
            assertEquals(3600, source.commandTimeoutSeconds());
        }
    }

    public static class MaximumLimitsModule extends GithubConfigurationTestModule {
        public MaximumLimitsModule() {
            super(Map.ofEntries(
                    Map.entry("worker.source.maxDepth", "100000"),
                    Map.entry("worker.source.maxBytes", "17179869184"),
                    Map.entry("worker.source.commandTimeoutSeconds", "3600")));
        }
    }

    @Nested
    @FusionSupport(modules = BrokerModule.class)
    class Broker {
        @Test
        void bindsConfiguration(@Fusion final Options root) {
            final var worker = root.worker();
            assertEquals(WorkerOptions.SourceMode.broker, worker.source().mode());
            assertEquals("", worker.source().tokenEnv());
            assertTrue(worker.agentCommenting()); // The worker must reject this combination, not silently change it.
            assertEquals(536870912L, worker.source().maxBytes());
            assertEquals(1000, worker.source().maxDepth());
        }
    }

    public static class BrokerModule extends GithubConfigurationTestModule {
        public BrokerModule() {
            super(Map.ofEntries(Map.entry("worker.source.mode", "broker")));
        }
    }
}
