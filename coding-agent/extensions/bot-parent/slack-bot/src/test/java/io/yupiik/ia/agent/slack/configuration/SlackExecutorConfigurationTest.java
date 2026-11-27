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
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SlackExecutorConfigurationTest {
    @Nested
    @FusionSupport(modules = SlackConfigurationTestModule.class)
    class Defaults {
        @Test
        void absentOptionalGatewaysStayAbsentAndKubernetesUsesUnprivilegedDefaults(
                @Fusion final SlackExecutorConfiguration configuration) {
            final var executor = configuration.shared();
            assertNull(executor.docker().egress());
            assertNull(executor.docker().services());
            assertEquals(10001L, executor.kubernetes().uid());
            assertEquals(10001L, executor.kubernetes().gid());
            assertFalse(executor.docker().disableContainerLogs());
        }
    }

    @Nested
    @FusionSupport(modules = LogsEnabledModule.class)
    class LogsEnabled {
        @Test
        void explicitFalseKeepsLogsEnabled(@Fusion final SlackExecutorConfiguration configuration) {
            assertFalse(configuration.shared().docker().disableContainerLogs());
        }
    }

    @Nested
    @FusionSupport(modules = LogsDisabledModule.class)
    class LogsDisabled {
        @Test
        void explicitTrueDisablesLogs(@Fusion final SlackExecutorConfiguration configuration) {
            assertTrue(configuration.shared().docker().disableContainerLogs());
        }
    }

    @Nested
    @FusionSupport(modules = EgressModule.class)
    class Egress {
        @Test
        void configuredEgressDefaultsToEnabled(@Fusion final SlackExecutorConfiguration configuration) {
            final var egress = configuration.shared().docker().egress();
            assertNotNull(egress);
            assertTrue(egress.enabled());
        }
    }

    @Nested
    @FusionSupport(modules = ServicesModule.class)
    class Services {
        @Test
        void servicesDependencyEgressDefaultsToEnabled(@Fusion final SlackExecutorConfiguration configuration) {
            final var egress = configuration.shared().docker().services().dependencyEgress();
            assertTrue(egress.enabled());
            assertEquals("trusted-services", egress.image());
            assertTrue(egress.allowedHosts().contains("repo.maven.apache.org"));
        }
    }

    @Nested
    @FusionSupport(modules = DisabledEgressModule.class)
    class DisabledEgress {
        @Test
        void explicitFalseIsNotReplacedByTheEgressDefault(@Fusion final SlackExecutorConfiguration configuration) {
            final var egress = configuration.shared().docker().egress();
            assertNotNull(egress);
            assertFalse(egress.enabled());
        }
    }

    @Nested
    @FusionSupport(modules = DisabledServicesEgressModule.class)
    class DisabledServicesEgress {
        @Test
        void explicitFalseIsNotReplacedByTheServicesDefault(@Fusion final SlackExecutorConfiguration configuration) {
            assertFalse(configuration
                    .shared()
                    .docker()
                    .services()
                    .dependencyEgress()
                    .enabled());
        }
    }

    @Nested
    @FusionSupport(modules = RootModule.class)
    class Root {
        @Test
        void explicitRootIdentifiersAreNotReplacedByUnprivilegedDefaults(
                @Fusion final SlackExecutorConfiguration configuration) {
            final var kubernetes = configuration.shared().kubernetes();
            assertEquals(0L, kubernetes.uid());
            assertEquals(0L, kubernetes.gid());
        }
    }

    private abstract static class InvalidIdentifier {
        @Test
        void malformedIdentifiersStillFailBinding(@Fusion final RuntimeContainer container) {
            assertThrows(NumberFormatException.class, () -> {
                try (final var configuration = container.lookup(SlackExecutorConfiguration.class)) {
                    configuration.instance().shared();
                }
            });
        }
    }

    private abstract static class InvalidServices {
        @Test
        void malformedServicesStillFailValidation(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var configuration = container.lookup(SlackExecutorConfiguration.class)) {
                    configuration.instance().shared();
                }
            });
        }
    }

    @Nested
    @FusionSupport(modules = InvalidUidModule.class)
    class InvalidUid extends InvalidIdentifier {}

    @Nested
    @FusionSupport(modules = OverflowUidModule.class)
    class OverflowUid extends InvalidIdentifier {}

    @Nested
    @FusionSupport(modules = InvalidGidModule.class)
    class InvalidGid extends InvalidIdentifier {}

    @Nested
    @FusionSupport(modules = OverflowGidModule.class)
    class OverflowGid extends InvalidIdentifier {}

    @Nested
    @FusionSupport(modules = MissingServicesSettingsModule.class)
    class MissingServicesSettings extends InvalidServices {}

    @Nested
    @FusionSupport(modules = HttpsTargetModule.class)
    class HttpsTarget extends InvalidServices {}

    @Nested
    @FusionSupport(modules = HostnameTargetModule.class)
    class HostnameTarget extends InvalidServices {}

    @Nested
    @FusionSupport(modules = ConflictingModule.class)
    class Conflicting extends InvalidServices {}

    public static class LogsEnabledModule extends SlackConfigurationTestModule {
        public LogsEnabledModule() {
            super(Map.of("slack.executor.docker.disableContainerLogs", "false"));
        }
    }

    public static class LogsDisabledModule extends SlackConfigurationTestModule {
        public LogsDisabledModule() {
            super(Map.of("slack.executor.docker.disableContainerLogs", "true"));
        }
    }

    public static class EgressModule extends SlackConfigurationTestModule {
        public EgressModule() {
            super(Map.of("slack.executor.docker.egress.image", "trusted-egress"));
        }
    }

    public static class DisabledEgressModule extends SlackConfigurationTestModule {
        public DisabledEgressModule() {
            super(Map.of(
                    "slack.executor.docker.egress.image",
                    "trusted-egress",
                    "slack.executor.docker.egress.enabled",
                    "false"));
        }
    }

    public static class ServicesModule extends SlackConfigurationTestModule {
        public ServicesModule() {
            super(services(Map.of()));
        }
    }

    public static class DisabledServicesEgressModule extends SlackConfigurationTestModule {
        public DisabledServicesEgressModule() {
            super(services(Map.of("slack.executor.docker.services.dependencyEgress.enabled", "false")));
        }
    }

    public static class RootModule extends SlackConfigurationTestModule {
        public RootModule() {
            super(Map.of("slack.executor.kubernetes.uid", "0", "slack.executor.kubernetes.gid", "0"));
        }
    }

    public static class InvalidUidModule extends SlackConfigurationTestModule {
        public InvalidUidModule() {
            super(Map.of("slack.executor.kubernetes.uid", "not-a-number"));
        }
    }

    public static class OverflowUidModule extends SlackConfigurationTestModule {
        public OverflowUidModule() {
            super(Map.of("slack.executor.kubernetes.uid", "9223372036854775808"));
        }
    }

    public static class InvalidGidModule extends SlackConfigurationTestModule {
        public InvalidGidModule() {
            super(Map.of("slack.executor.kubernetes.gid", "not-a-number"));
        }
    }

    public static class OverflowGidModule extends SlackConfigurationTestModule {
        public OverflowGidModule() {
            super(Map.of("slack.executor.kubernetes.gid", "9223372036854775808"));
        }
    }

    public static class MissingServicesSettingsModule extends SlackConfigurationTestModule {
        public MissingServicesSettingsModule() {
            super(Map.of("slack.executor.docker.services.image", "trusted-services"));
        }
    }

    public static class HttpsTargetModule extends SlackConfigurationTestModule {
        public HttpsTargetModule() {
            super(services(Map.of("slack.executor.docker.services.providerTarget", "https://10.0.0.1:8080")));
        }
    }

    public static class HostnameTargetModule extends SlackConfigurationTestModule {
        public HostnameTargetModule() {
            super(services(Map.of("slack.executor.docker.services.providerTarget", "http://provider.example:8080")));
        }
    }

    public static class ConflictingModule extends SlackConfigurationTestModule {
        public ConflictingModule() {
            super(services(Map.of("slack.executor.docker.egress.image", "trusted-egress")));
        }
    }

    private static Map<String, String> services(final Map<String, String> overrides) {
        final var values = new HashMap<>(Map.of(
                "slack.executor.docker.services.image", "trusted-services",
                "slack.executor.docker.services.controlNetwork", "trusted-control",
                "slack.executor.docker.services.keyStore", "/private/services.p12",
                "slack.executor.docker.services.providerTarget", "http://10.0.0.1:8080/v1"));
        values.putAll(overrides);
        return values;
    }
}
