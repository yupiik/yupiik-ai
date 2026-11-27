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

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.container.configuration.source.EnvironmentSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import io.yupiik.ia.agent.core.llm.ProviderRequestBudget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProviderBudgetConfigurationTest {
    @Nested
    @FusionSupport(modules = DefaultsModule.class)
    class Defaults {
        @Test
        void binds(@Fusion final ProviderBudgetConfiguration configuration) {
            assertEquals(new ProviderBudgetConfiguration(null, null, null), configuration);
        }
    }

    public static class DefaultsModule extends ConfigurationTestModule {
        public DefaultsModule() {
            super(Map.of());
        }
    }

    @Nested
    @FusionSupport(modules = DottedModule.class)
    class Dotted {
        @Test
        void binds(@Fusion final ProviderBudgetConfiguration configuration) {
            assertEquals(new ProviderBudgetConfiguration("/trusted/budget", "account", "2"), configuration);
        }
    }

    public static class DottedModule extends ConfigurationTestModule {
        public DottedModule() {
            super(Map.of(
                    "yca.provider.budget.root",
                    "/trusted/budget",
                    "yca.provider.budget.id",
                    "account",
                    "yca.provider.budget.slots",
                    "2"));
        }
    }

    @Nested
    @FusionSupport(modules = BlankModule.class)
    class Blank {
        @Test
        void binds(@Fusion final ProviderBudgetConfiguration configuration) {
            assertEquals("", configuration.slots());
            assertThrows(IllegalArgumentException.class, () -> ProviderRequestBudget.fromConfiguration(configuration));
        }
    }

    public static class BlankModule extends ConfigurationTestModule {
        public BlankModule() {
            super(Map.of("yca.provider.budget.slots", ""));
        }
    }

    @Nested
    @FusionSupport(modules = NoncanonicalModule.class)
    class Noncanonical {
        @Test
        void binds(@Fusion final ProviderBudgetConfiguration configuration) {
            assertEquals("01", configuration.slots());
            assertThrows(IllegalArgumentException.class, () -> ProviderRequestBudget.fromConfiguration(configuration));
        }
    }

    public static class NoncanonicalModule extends ConfigurationTestModule {
        public NoncanonicalModule() {
            super(Map.of("yca.provider.budget.slots", "01"));
        }
    }

    @Nested
    @FusionSupport(modules = InvalidModule.class)
    class Invalid {
        @Test
        void binds(@Fusion final ProviderBudgetConfiguration configuration) {
            assertEquals("invalid", configuration.slots());
            assertThrows(IllegalArgumentException.class, () -> ProviderRequestBudget.fromConfiguration(configuration));
        }
    }

    public static class InvalidModule extends ConfigurationTestModule {
        public InvalidModule() {
            super(Map.of("yca.provider.budget.slots", "invalid"));
        }
    }

    @Test
    void jsonRoundTripPreservesTrustedSettingsAndAbsentFields() {
        try (final var json = JsonMappers.create(key -> Optional.empty())) {
            final var expected = new ProviderBudgetConfiguration("/trusted/budget", "account", "2");
            assertEquals(expected, json.fromString(ProviderBudgetConfiguration.class, json.toString(expected)));
            assertEquals(
                    new ProviderBudgetConfiguration(null, null, null),
                    json.fromString(ProviderBudgetConfiguration.class, "{}"));
            assertEquals(
                    "01",
                    json.fromString(ProviderBudgetConfiguration.class, "{\"slots\":\"01\"}")
                            .slots());
        }
    }

    @Test
    void privateEnvironmentHandoffBindsToDottedProperties() throws Exception {
        final var classpath = System.getProperty("java.class.path");
        final var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath,
                Child.class.getName());
        builder.environment()
                .putAll(Map.of(
                        ProviderRequestBudget.ROOT, "/trusted/budget",
                        ProviderRequestBudget.ID, "account",
                        ProviderRequestBudget.SLOTS, "2"));
        builder.redirectErrorStream(true);
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            final var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertEquals("/trusted/budget|account|2", output.strip());
        } finally {
            process.destroyForcibly();
        }
    }

    public static class EnvironmentModule extends ConfigurationTestModule {
        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.of(
                    new ProvidedInstanceBean<>(DefaultScoped.class, ConfigurationSource.class, EnvironmentSource::new));
        }
    }

    public static final class Child {
        public static void main(final String[] args) {
            try (final var container = ConfiguringContainer.of()
                            .register(new EnvironmentModule())
                            .start();
                    final var instance = container.lookup(ProviderBudgetConfiguration.class)) {
                final var configuration = instance.instance();
                System.out.println(configuration.root() + "|" + configuration.id() + "|" + configuration.slots());
            }
        }
    }
}
