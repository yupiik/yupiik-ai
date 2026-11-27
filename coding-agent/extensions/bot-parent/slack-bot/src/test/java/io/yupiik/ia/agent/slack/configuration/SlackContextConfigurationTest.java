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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.slack.support.SlackConfigurationTestModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SlackContextConfigurationTest {
    @Nested
    @FusionSupport(modules = SlackConfigurationTestModule.class)
    class Defaults {
        @Test
        void generatedDefaultsAreDenyByDefaultAndBounded(@Fusion final SlackContextConfiguration config) {
            assertFalse(config.enabled());
            assertFalse(config.allowSwapBackedTmpfs());
            assertFalse(config.allowContainerLogs());
            assertTrue(config.resolveMentions());
            assertFalse(config.searchEnabled());
            assertEquals(List.of(), config.sourceChannels());
            assertEquals(30, config.lookbackDays());
            assertEquals(32, config.maxMembers());
            assertEquals(12, config.maxRequests());
            assertEquals(20, config.maxResults());
            assertEquals(131072, config.maxResponseBytes());
            assertEquals(20, config.timeoutSeconds());
            assertEquals(2, config.maxConcurrent());
            assertEquals(600, config.actionRetentionSeconds());
        }
    }

    @Test
    void validatesEveryLimitAndAcceptsOnlyExactPublicChannelIds() {
        for (final var channel : List.of("G1", "D1", "C.*", "C1,C2", "c1", "C", "C1 ")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SlackContextConfiguration(
                            false,
                            true,
                            false,
                            List.of(channel),
                            30,
                            32,
                            12,
                            20,
                            131072,
                            20,
                            2,
                            600,
                            false,
                            new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
                            false));
        }
    }

    @Test
    void sourceAllowlistIsAnImmutableSnapshotAndNullDeniesAll() {
        final var sources = new ArrayList<>(List.of("C1"));
        final var config = new SlackContextConfiguration(
                true,
                true,
                true,
                sources,
                30,
                32,
                12,
                20,
                131072,
                20,
                2,
                600,
                false,
                new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
                false);
        sources.clear();
        assertEquals(List.of("C1"), config.sourceChannels());
        assertThrows(
                UnsupportedOperationException.class,
                () -> config.sourceChannels().clear());
        assertEquals(
                List.of(),
                new SlackContextConfiguration(
                                false,
                                true,
                                false,
                                null,
                                30,
                                32,
                                12,
                                20,
                                131072,
                                20,
                                2,
                                600,
                                false,
                                new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
                                false)
                        .sourceChannels());
    }

    @Nested
    @FusionSupport(modules = LogsModule.class)
    class Logs {
        @Test
        void containerLogsRequireExplicitOperatorConfiguration(@Fusion final SlackContextConfiguration config) {
            assertTrue(config.allowContainerLogs());
            assertFalse(config.allowSwapBackedTmpfs());
            assertFalse(config.enabled());
            assertFalse(config.searchEnabled());
        }
    }

    @Nested
    @FusionSupport(modules = SwapModule.class)
    class Swap {
        @Test
        void swapBackedTmpfsRequiresExplicitOperatorConfiguration(@Fusion final SlackContextConfiguration config) {
            assertTrue(config.allowSwapBackedTmpfs());
            assertFalse(config.enabled());
            assertFalse(config.searchEnabled());
        }
    }

    private abstract static class InvalidLimit {
        @Test
        void rejectsInvalidLimit(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var configuration = container.lookup(SlackContextConfiguration.class)) {
                    configuration.instance().enabled();
                }
            });
        }
    }

    @Nested
    @FusionSupport(modules = LookbackModule.class)
    class Lookback extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = MembersModule.class)
    class Members extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = RequestsModule.class)
    class Requests extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = ResultsModule.class)
    class Results extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = BytesModule.class)
    class Bytes extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = TimeoutModule.class)
    class Timeout extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = ConcurrentModule.class)
    class Concurrent extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = RetentionModule.class)
    class Retention extends InvalidLimit {}

    public static class LogsModule extends SlackConfigurationTestModule {
        public LogsModule() {
            super(Map.of("slack.context.allowContainerLogs", "true"));
        }
    }

    public static class SwapModule extends SlackConfigurationTestModule {
        public SwapModule() {
            super(Map.of("slack.context.allowSwapBackedTmpfs", "true"));
        }
    }

    public static class LookbackModule extends SlackConfigurationTestModule {
        public LookbackModule() {
            super(Map.of("slack.context.lookbackDays", "0"));
        }
    }

    public static class MembersModule extends SlackConfigurationTestModule {
        public MembersModule() {
            super(Map.of("slack.context.maxMembers", "0"));
        }
    }

    public static class RequestsModule extends SlackConfigurationTestModule {
        public RequestsModule() {
            super(Map.of("slack.context.maxRequests", "0"));
        }
    }

    public static class ResultsModule extends SlackConfigurationTestModule {
        public ResultsModule() {
            super(Map.of("slack.context.maxResults", "1001"));
        }
    }

    public static class BytesModule extends SlackConfigurationTestModule {
        public BytesModule() {
            super(Map.of("slack.context.maxResponseBytes", "100"));
        }
    }

    public static class TimeoutModule extends SlackConfigurationTestModule {
        public TimeoutModule() {
            super(Map.of("slack.context.timeoutSeconds", "0"));
        }
    }

    public static class ConcurrentModule extends SlackConfigurationTestModule {
        public ConcurrentModule() {
            super(Map.of("slack.context.maxConcurrent", "0"));
        }
    }

    public static class RetentionModule extends SlackConfigurationTestModule {
        public RetentionModule() {
            super(Map.of("slack.context.actionRetentionSeconds", "0"));
        }
    }
}
