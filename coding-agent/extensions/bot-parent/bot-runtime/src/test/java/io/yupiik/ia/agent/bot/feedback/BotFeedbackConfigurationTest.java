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
package io.yupiik.ia.agent.bot.feedback;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BotFeedbackConfigurationTest {
    @Nested
    @FusionSupport(modules = BotFeedbackTestModule.class)
    class Defaults {
        @Test
        void defaultsDisableFeedbackWithTenMinuteTimeoutAndDefaultMailbox(
                @Fusion final BotFeedbackConfiguration configuration) {
            assertFalse(configuration.enabled());
            assertEquals(600, configuration.timeoutSeconds());
            assertNull(configuration.directory());
        }
    }

    @Nested
    @FusionSupport(modules = CustomModule.class)
    class Custom {
        @Test
        void bindsExistingPropertyNames(@Fusion final BotFeedbackConfiguration configuration) {
            assertEquals(new BotFeedbackConfiguration(true, 42, "/custom/feedback"), configuration);
        }

        @Test
        void jsonRoundTripAndValidation(@Fusion final BotFeedbackConfiguration configuration) {
            try (final var json = JsonMappers.create(key -> Optional.empty())) {
                assertEquals(
                        configuration, json.fromString(BotFeedbackConfiguration.class, json.toString(configuration)));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> json.fromString(
                                BotFeedbackConfiguration.class, "{\"enabled\":false,\"timeoutSeconds\":0}"));
            }
        }
    }

    @Test
    void rejectsOutOfRangeTimeoutsEvenWhenDisabled() {
        for (final var enabled : new boolean[] {false, true}) {
            for (final var timeout : new int[] {Integer.MIN_VALUE, -1, 0, 3601, Integer.MAX_VALUE}) {
                final var error = assertThrows(
                        IllegalArgumentException.class, () -> new BotFeedbackConfiguration(enabled, timeout, null));
                assertEquals(
                        "yca.bot.feedback.timeoutSeconds must be between 1 and 3600 inclusive", error.getMessage());
            }
        }
    }

    @Test
    void acceptsInclusiveTimeoutBounds() {
        for (final var timeout : new int[] {1, 3600}) {
            assertEquals(timeout, new BotFeedbackConfiguration(true, timeout, null).timeoutSeconds());
        }
    }

    abstract class InvalidTimeout {
        @Test
        void rejectsInvalidBoundTimeout(final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var configuration = container.lookup(BotFeedbackConfiguration.class)) {
                    configuration.instance();
                }
            });
        }
    }

    @Nested
    @FusionSupport(modules = MinDisabledModule.class)
    class MinDisabled extends InvalidTimeout {}

    @Nested
    @FusionSupport(modules = MinEnabledModule.class)
    class MinEnabled extends InvalidTimeout {}

    @Nested
    @FusionSupport(modules = NegativeDisabledModule.class)
    class NegativeDisabled extends InvalidTimeout {}

    @Nested
    @FusionSupport(modules = NegativeEnabledModule.class)
    class NegativeEnabled extends InvalidTimeout {}

    @Nested
    @FusionSupport(modules = ZeroDisabledModule.class)
    class ZeroDisabled extends InvalidTimeout {}

    @Nested
    @FusionSupport(modules = ZeroEnabledModule.class)
    class ZeroEnabled extends InvalidTimeout {}

    @Nested
    @FusionSupport(modules = AboveDisabledModule.class)
    class AboveDisabled extends InvalidTimeout {}

    @Nested
    @FusionSupport(modules = AboveEnabledModule.class)
    class AboveEnabled extends InvalidTimeout {}

    @Nested
    @FusionSupport(modules = MaxDisabledModule.class)
    class MaxDisabled extends InvalidTimeout {}

    @Nested
    @FusionSupport(modules = MaxEnabledModule.class)
    class MaxEnabled extends InvalidTimeout {}

    @Nested
    @FusionSupport(modules = NonnumericDisabledModule.class)
    class NonnumericDisabled extends InvalidTimeout {}

    @Nested
    @FusionSupport(modules = NonnumericEnabledModule.class)
    class NonnumericEnabled extends InvalidTimeout {}

    @Nested
    @FusionSupport(modules = LowerModule.class)
    class LowerBound {
        @Test
        void acceptsLowerBound(@Fusion final BotFeedbackConfiguration configuration) {
            assertEquals(1, configuration.timeoutSeconds());
        }
    }

    @Nested
    @FusionSupport(modules = UpperModule.class)
    class UpperBound {
        @Test
        void acceptsUpperBound(@Fusion final BotFeedbackConfiguration configuration) {
            assertEquals(3600, configuration.timeoutSeconds());
        }
    }

    public static class CustomModule extends BotFeedbackTestModule {
        public CustomModule() {
            super(Map.of(
                    "yca.bot.feedback.enabled",
                    "true",
                    "yca.bot.feedback.timeoutSeconds",
                    "42",
                    "yca.bot.feedback.directory",
                    "/custom/feedback"));
        }
    }

    private static class TimeoutModule extends BotFeedbackTestModule {
        private TimeoutModule(final boolean enabled, final String timeout) {
            super(Map.of(
                    "yca.bot.feedback.enabled", Boolean.toString(enabled), "yca.bot.feedback.timeoutSeconds", timeout));
        }
    }

    public static class MinDisabledModule extends TimeoutModule {
        public MinDisabledModule() {
            super(false, Integer.toString(Integer.MIN_VALUE));
        }
    }

    public static class MinEnabledModule extends TimeoutModule {
        public MinEnabledModule() {
            super(true, Integer.toString(Integer.MIN_VALUE));
        }
    }

    public static class NegativeDisabledModule extends TimeoutModule {
        public NegativeDisabledModule() {
            super(false, "-1");
        }
    }

    public static class NegativeEnabledModule extends TimeoutModule {
        public NegativeEnabledModule() {
            super(true, "-1");
        }
    }

    public static class ZeroDisabledModule extends TimeoutModule {
        public ZeroDisabledModule() {
            super(false, "0");
        }
    }

    public static class ZeroEnabledModule extends TimeoutModule {
        public ZeroEnabledModule() {
            super(true, "0");
        }
    }

    public static class AboveDisabledModule extends TimeoutModule {
        public AboveDisabledModule() {
            super(false, "3601");
        }
    }

    public static class AboveEnabledModule extends TimeoutModule {
        public AboveEnabledModule() {
            super(true, "3601");
        }
    }

    public static class MaxDisabledModule extends TimeoutModule {
        public MaxDisabledModule() {
            super(false, Integer.toString(Integer.MAX_VALUE));
        }
    }

    public static class MaxEnabledModule extends TimeoutModule {
        public MaxEnabledModule() {
            super(true, Integer.toString(Integer.MAX_VALUE));
        }
    }

    public static class NonnumericDisabledModule extends TimeoutModule {
        public NonnumericDisabledModule() {
            super(false, "garbage");
        }
    }

    public static class NonnumericEnabledModule extends TimeoutModule {
        public NonnumericEnabledModule() {
            super(true, "garbage");
        }
    }

    public static class LowerModule extends TimeoutModule {
        public LowerModule() {
            super(false, "1");
        }
    }

    public static class UpperModule extends TimeoutModule {
        public UpperModule() {
            super(false, "3600");
        }
    }
}
