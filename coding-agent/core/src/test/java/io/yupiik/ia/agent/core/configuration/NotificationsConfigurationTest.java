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
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NotificationsConfigurationTest {
    @Nested
    @FusionSupport(modules = ConfigurationTestModule.class)
    class Defaults {
        @Test
        void defaultsAreDisabled(@Fusion final CodingAgentConfiguration configuration) {
            final var defaults = configuration.notifications();
            assertFalse(defaults.enabled());
            assertEquals(new NotificationsOnConfiguration(null, null), defaults.on());
        }
    }

    @Nested
    @FusionSupport(modules = PartialModule.class)
    class Partial {
        @Test
        void switchesAreIndependent(@Fusion final CodingAgentConfiguration configuration) {
            final var partial = configuration.notifications();
            assertTrue(partial.enabled());
            assertEquals(
                    new NotificationsEventConfiguration(true, true),
                    partial.on().event());
            assertEquals(
                    new NotificationsToolsConfiguration(false, true),
                    partial.on().tools());
        }
    }

    @Nested
    @FusionSupport(modules = DisabledModule.class)
    class Disabled {
        @Test
        void allSwitchesCanBeDisabled(@Fusion final CodingAgentConfiguration configuration) {
            final var disabled = configuration.notifications().on();
            assertEquals(new NotificationsEventConfiguration(false, false), disabled.event());
            assertEquals(new NotificationsToolsConfiguration(false, false), disabled.tools());
        }
    }

    public static class PartialModule extends ConfigurationTestModule {
        public PartialModule() {
            super(Map.of("yca.notifications.enabled", "true", "yca.notifications.on.tools.ask_user", "false"));
        }
    }

    public static class DisabledModule extends ConfigurationTestModule {
        public DisabledModule() {
            super(Map.of(
                    "yca.notifications.on.event.turnEnd",
                    "false",
                    "yca.notifications.on.event.approval",
                    "false",
                    "yca.notifications.on.tools.ask_user",
                    "false",
                    "yca.notifications.on.tools.btw",
                    "false"));
        }
    }

    @Test
    void jsonDefaultsAndRoundTrip() {
        try (final var json = JsonMappers.create(key -> Optional.empty())) {
            for (final var source : List.of(
                    "{}",
                    "{\"on\":null}",
                    "{\"on\":{}}",
                    "{\"on\":{\"event\":null,\"tools\":null}}",
                    "{\"on\":{\"event\":{},\"tools\":{}}}",
                    "{\"on\":{\"event\":{\"turnEnd\":null,\"approval\":null},\"tools\":{\"ask_user\":null,\"btw\":null}}}")) {
                final var config = json.fromString(NotificationsConfiguration.class, source);
                assertFalse(config.enabled());
                assertEquals(new NotificationsOnConfiguration(null, null), config.on(), source);
            }
            final var config = json.fromString(
                    NotificationsConfiguration.class,
                    "{\"enabled\":true,\"command\":[\"custom\",\"--flag\"],\"on\":{\"tools\":{\"ask_user\":false}}}");
            assertEquals(
                    new NotificationsEventConfiguration(true, true), config.on().event());
            assertEquals(
                    new NotificationsToolsConfiguration(false, true),
                    config.on().tools());
            assertEquals(List.of("custom", "--flag"), config.command());
            assertEquals(config, json.fromString(NotificationsConfiguration.class, json.toString(config)));
            assertEquals(
                    new NotificationsOnConfiguration(
                            new NotificationsEventConfiguration(false, false),
                            new NotificationsToolsConfiguration(false, false)),
                    json.fromString(
                            NotificationsOnConfiguration.class,
                            "{\"event\":{\"turnEnd\":false,\"approval\":false},\"tools\":{\"ask_user\":false,\"btw\":false}}"));
            final var eventOnly =
                    json.fromString(NotificationsOnConfiguration.class, "{\"event\":{\"approval\":false}}");
            assertEquals(new NotificationsEventConfiguration(true, false), eventOnly.event());
            assertEquals(new NotificationsToolsConfiguration(true, true), eventOnly.tools());
            final var btwOnly = json.fromString(NotificationsOnConfiguration.class, "{\"tools\":{\"btw\":false}}");
            assertEquals(new NotificationsEventConfiguration(true, true), btwOnly.event());
            assertEquals(new NotificationsToolsConfiguration(true, false), btwOnly.tools());
        }
    }
}
