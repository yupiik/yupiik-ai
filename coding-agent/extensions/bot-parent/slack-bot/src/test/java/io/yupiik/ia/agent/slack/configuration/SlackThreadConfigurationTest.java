/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

class SlackThreadConfigurationTest {
    @Nested
    @FusionSupport(modules = SlackConfigurationTestModule.class)
    class Defaults {
        @Test
        void nestedDefaultsAreDisabledAndIndependentOfSearch(@Fusion final SlackContextConfiguration config) {
            assertEquals(new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24), config.thread());
            assertFalse(config.enabled());
            assertFalse(config.searchEnabled());
            assertTrue(config.sourceChannels().isEmpty());
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitModule.class)
    class Explicit {
        @Test
        void nestedSettingsDoNotEnableParentOrSearch(@Fusion final SlackContextConfiguration explicit) {
            assertEquals(new SlackThreadConfiguration(true, List.of("C1"), 10, 80, 32768, 2, 12), explicit.thread());
            assertFalse(explicit.enabled(), "Thread policy must not enable parent context");
            assertFalse(explicit.searchEnabled(), "Thread policy must not enable search");
            assertEquals(1000, explicit.maxResults());
        }
    }

    @Test
    void onlyExactPublicAllowlistIsAcceptedAndSnapshotIsImmutable() {
        for (final var channel : List.of("G1", "D1", "C.*", "C1,C2", "c1", "C", "C1 ")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SlackThreadConfiguration(true, List.of(channel), 20, 100, 65536, 3, 24));
        }
        final var channels = new ArrayList<>(List.of("C1"));
        final var config = new SlackThreadConfiguration(true, channels, 20, 100, 65536, 3, 24);
        channels.clear();
        assertEquals(List.of("C1"), config.channels());
        assertThrows(
                UnsupportedOperationException.class, () -> config.channels().clear());
        assertEquals(List.of(), new SlackThreadConfiguration(true, null, 20, 100, 65536, 3, 24).channels());
    }

    @Test
    void initialAndCumulativeUpperBoundsAreAccepted() {
        assertEquals(
                1000, new SlackThreadConfiguration(true, List.of("C1"), 20, 1000, 16777216, 1000, 8760).maxMessages());
    }

    @Test
    void nullNestedPolicyNormalizesToDisabledDefaults() {
        final var config = new SlackContextConfiguration(
                true, true, true, List.of("C1"), 30, 32, 12, 20, 131072, 20, 2, 600, false, null, false);
        assertEquals(new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24), config.thread());
    }

    private abstract static class InvalidLimit {
        @Test
        void initialAndCumulativeLimitsAreSeparatelyBounded(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var config = container.lookup(SlackContextConfiguration.class)) {
                    config.instance().thread();
                }
            });
        }
    }

    @Nested
    @FusionSupport(modules = InitialLowModule.class)
    class InitialLow extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = InitialHighModule.class)
    class InitialHigh extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = MessagesLowModule.class)
    class MessagesLow extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = MessagesHighModule.class)
    class MessagesHigh extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = BytesLowModule.class)
    class BytesLow extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = BytesHighModule.class)
    class BytesHigh extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = RequestsLowModule.class)
    class RequestsLow extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = RequestsHighModule.class)
    class RequestsHigh extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = LookbackLowModule.class)
    class LookbackLow extends InvalidLimit {}

    @Nested
    @FusionSupport(modules = LookbackHighModule.class)
    class LookbackHigh extends InvalidLimit {}

    public static class ExplicitModule extends SlackConfigurationTestModule {
        public ExplicitModule() {
            super(Map.of(
                    "slack.context.thread.enabled", "true",
                    "slack.context.thread.channels", "C1",
                    "slack.context.thread.initialMessages", "10",
                    "slack.context.thread.maxMessages", "80",
                    "slack.context.thread.maxBytes", "32768",
                    "slack.context.thread.maxRequests", "2",
                    "slack.context.thread.lookbackHours", "12",
                    "slack.context.maxResults", "1000"));
        }
    }

    public static class InitialLowModule extends SlackConfigurationTestModule {
        public InitialLowModule() {
            super(Map.of("slack.context.thread.initialMessages", "0"));
        }
    }

    public static class InitialHighModule extends SlackConfigurationTestModule {
        public InitialHighModule() {
            super(Map.of("slack.context.thread.initialMessages", "21"));
        }
    }

    public static class MessagesLowModule extends SlackConfigurationTestModule {
        public MessagesLowModule() {
            super(Map.of("slack.context.thread.maxMessages", "19"));
        }
    }

    public static class MessagesHighModule extends SlackConfigurationTestModule {
        public MessagesHighModule() {
            super(Map.of("slack.context.thread.maxMessages", "1001"));
        }
    }

    public static class BytesLowModule extends SlackConfigurationTestModule {
        public BytesLowModule() {
            super(Map.of("slack.context.thread.maxBytes", "1023"));
        }
    }

    public static class BytesHighModule extends SlackConfigurationTestModule {
        public BytesHighModule() {
            super(Map.of("slack.context.thread.maxBytes", "16777217"));
        }
    }

    public static class RequestsLowModule extends SlackConfigurationTestModule {
        public RequestsLowModule() {
            super(Map.of("slack.context.thread.maxRequests", "0"));
        }
    }

    public static class RequestsHighModule extends SlackConfigurationTestModule {
        public RequestsHighModule() {
            super(Map.of("slack.context.thread.maxRequests", "1001"));
        }
    }

    public static class LookbackLowModule extends SlackConfigurationTestModule {
        public LookbackLowModule() {
            super(Map.of("slack.context.thread.lookbackHours", "0"));
        }
    }

    public static class LookbackHighModule extends SlackConfigurationTestModule {
        public LookbackHighModule() {
            super(Map.of("slack.context.thread.lookbackHours", "8761"));
        }
    }
}
