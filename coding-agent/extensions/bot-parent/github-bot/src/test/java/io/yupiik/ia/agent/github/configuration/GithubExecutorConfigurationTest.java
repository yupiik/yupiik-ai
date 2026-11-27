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
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GithubExecutorConfigurationTest {
    @Nested
    @FusionSupport(modules = DefaultsModule.class)
    class Defaults {
        @Test
        void bindsConfiguration(@Fusion final GithubExecutorConfiguration root) {
            final var executor = root.shared();
            assertNull(executor.docker().egress());
            assertNull(executor.docker().services());
            assertEquals(10001L, executor.kubernetes().uid());
            assertEquals(10001L, executor.kubernetes().gid());
            assertFalse(executor.docker().disableContainerLogs());
        }
    }

    public static class DefaultsModule extends GithubConfigurationTestModule {
        public DefaultsModule() {
            super(Map.of());
        }
    }

    @Nested
    @FusionSupport(modules = ContainerLogsEnabledModule.class)
    class ContainerLogsEnabled {
        @Test
        void bindsConfiguration(@Fusion final GithubExecutorConfiguration root) {
            final var executor = root.shared();
            assertEquals(false, executor.docker().disableContainerLogs());
        }
    }

    public static class ContainerLogsEnabledModule extends GithubConfigurationTestModule {
        public ContainerLogsEnabledModule() {
            super(Map.ofEntries(Map.entry("github.executor.docker.disableContainerLogs", "false")));
        }
    }

    @Nested
    @FusionSupport(modules = ContainerLogsDisabledModule.class)
    class ContainerLogsDisabled {
        @Test
        void bindsConfiguration(@Fusion final GithubExecutorConfiguration root) {
            final var executor = root.shared();
            assertEquals(true, executor.docker().disableContainerLogs());
        }
    }

    public static class ContainerLogsDisabledModule extends GithubConfigurationTestModule {
        public ContainerLogsDisabledModule() {
            super(Map.ofEntries(Map.entry("github.executor.docker.disableContainerLogs", "true")));
        }
    }

    @Nested
    @FusionSupport(modules = EgressDefaultModule.class)
    class EgressDefault {
        @Test
        void bindsConfiguration(@Fusion final GithubExecutorConfiguration root) {
            final var executor = root.shared();
            assertNotNull(executor.docker().egress());
            assertTrue(executor.docker().egress().enabled());
        }
    }

    public static class EgressDefaultModule extends GithubConfigurationTestModule {
        public EgressDefaultModule() {
            super(Map.ofEntries(Map.entry("github.executor.docker.egress.image", "trusted-egress")));
        }
    }

    @Nested
    @FusionSupport(modules = ServicesEgressDefaultModule.class)
    class ServicesEgressDefault {
        @Test
        void bindsConfiguration(@Fusion final GithubExecutorConfiguration root) {
            final var executor = root.shared();
            final var egress = executor.docker().services().dependencyEgress();
            assertTrue(egress.enabled());
            assertEquals("trusted-services", egress.image());
            assertTrue(egress.allowedHosts().contains("repo.maven.apache.org"));
        }
    }

    public static class ServicesEgressDefaultModule extends GithubConfigurationTestModule {
        public ServicesEgressDefaultModule() {
            super(Map.ofEntries(
                    Map.entry("github.executor.docker.services.image", "trusted-services"),
                    Map.entry("github.executor.docker.services.controlNetwork", "trusted-control"),
                    Map.entry("github.executor.docker.services.keyStore", "/private/services.p12"),
                    Map.entry("github.executor.docker.services.providerTarget", "http://10.0.0.1:8080/v1")));
        }
    }

    @Nested
    @FusionSupport(modules = EgressDisabledModule.class)
    class EgressDisabled {
        @Test
        void bindsConfiguration(@Fusion final GithubExecutorConfiguration root) {
            final var executor = root.shared();
            assertNotNull(executor.docker().egress());
            assertFalse(executor.docker().egress().enabled());
        }
    }

    public static class EgressDisabledModule extends GithubConfigurationTestModule {
        public EgressDisabledModule() {
            super(Map.ofEntries(
                    Map.entry("github.executor.docker.egress.image", "trusted-egress"),
                    Map.entry("github.executor.docker.egress.enabled", "false")));
        }
    }

    @Nested
    @FusionSupport(modules = ServicesEgressDisabledModule.class)
    class ServicesEgressDisabled {
        @Test
        void bindsConfiguration(@Fusion final GithubExecutorConfiguration root) {
            final var executor = root.shared();
            assertFalse(executor.docker().services().dependencyEgress().enabled());
        }
    }

    public static class ServicesEgressDisabledModule extends GithubConfigurationTestModule {
        public ServicesEgressDisabledModule() {
            super(Map.ofEntries(
                    Map.entry("github.executor.docker.services.image", "trusted-services"),
                    Map.entry("github.executor.docker.services.controlNetwork", "trusted-control"),
                    Map.entry("github.executor.docker.services.keyStore", "/private/services.p12"),
                    Map.entry("github.executor.docker.services.providerTarget", "http://10.0.0.1:8080/v1"),
                    Map.entry("github.executor.docker.services.dependencyEgress.enabled", "false")));
        }
    }

    @Nested
    @FusionSupport(modules = RootIdentifiersModule.class)
    class RootIdentifiers {
        @Test
        void bindsConfiguration(@Fusion final GithubExecutorConfiguration root) {
            final var executor = root.shared();
            assertEquals(0L, executor.kubernetes().uid());
            assertEquals(0L, executor.kubernetes().gid());
        }
    }

    public static class RootIdentifiersModule extends GithubConfigurationTestModule {
        public RootIdentifiersModule() {
            super(Map.ofEntries(
                    Map.entry("github.executor.kubernetes.uid", "0"),
                    Map.entry("github.executor.kubernetes.gid", "0")));
        }
    }

    @Nested
    @FusionSupport(modules = NonNumericUidModule.class)
    class NonNumericUid {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(NumberFormatException.class, () -> {
                try (final var ignored = container.lookup(GithubExecutorConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class NonNumericUidModule extends GithubConfigurationTestModule {
        public NonNumericUidModule() {
            super(Map.ofEntries(Map.entry("github.executor.kubernetes.uid", "not-a-number")));
        }
    }

    @Nested
    @FusionSupport(modules = OverflowUidModule.class)
    class OverflowUid {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(NumberFormatException.class, () -> {
                try (final var ignored = container.lookup(GithubExecutorConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class OverflowUidModule extends GithubConfigurationTestModule {
        public OverflowUidModule() {
            super(Map.ofEntries(Map.entry("github.executor.kubernetes.uid", "9223372036854775808")));
        }
    }

    @Nested
    @FusionSupport(modules = NonNumericGidModule.class)
    class NonNumericGid {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(NumberFormatException.class, () -> {
                try (final var ignored = container.lookup(GithubExecutorConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class NonNumericGidModule extends GithubConfigurationTestModule {
        public NonNumericGidModule() {
            super(Map.ofEntries(Map.entry("github.executor.kubernetes.gid", "not-a-number")));
        }
    }

    @Nested
    @FusionSupport(modules = OverflowGidModule.class)
    class OverflowGid {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(NumberFormatException.class, () -> {
                try (final var ignored = container.lookup(GithubExecutorConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class OverflowGidModule extends GithubConfigurationTestModule {
        public OverflowGidModule() {
            super(Map.ofEntries(Map.entry("github.executor.kubernetes.gid", "9223372036854775808")));
        }
    }

    @Nested
    @FusionSupport(modules = IncompleteServicesModule.class)
    class IncompleteServices {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(GithubExecutorConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class IncompleteServicesModule extends GithubConfigurationTestModule {
        public IncompleteServicesModule() {
            super(Map.ofEntries(Map.entry("github.executor.docker.services.image", "trusted-services")));
        }
    }

    @Nested
    @FusionSupport(modules = InvalidProviderHttpsModule.class)
    class InvalidProviderHttps {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(GithubExecutorConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class InvalidProviderHttpsModule extends GithubConfigurationTestModule {
        public InvalidProviderHttpsModule() {
            super(Map.ofEntries(
                    Map.entry("github.executor.docker.services.image", "trusted-services"),
                    Map.entry("github.executor.docker.services.controlNetwork", "trusted-control"),
                    Map.entry("github.executor.docker.services.keyStore", "/private/services.p12"),
                    Map.entry("github.executor.docker.services.providerTarget", "https://10.0.0.1:8080")));
        }
    }

    @Nested
    @FusionSupport(modules = InvalidProviderHostnameModule.class)
    class InvalidProviderHostname {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(GithubExecutorConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class InvalidProviderHostnameModule extends GithubConfigurationTestModule {
        public InvalidProviderHostnameModule() {
            super(Map.ofEntries(
                    Map.entry("github.executor.docker.services.image", "trusted-services"),
                    Map.entry("github.executor.docker.services.controlNetwork", "trusted-control"),
                    Map.entry("github.executor.docker.services.keyStore", "/private/services.p12"),
                    Map.entry("github.executor.docker.services.providerTarget", "http://provider.example:8080")));
        }
    }

    @Nested
    @FusionSupport(modules = ConflictingGatewaysModule.class)
    class ConflictingGateways {
        @Test
        void bindsConfiguration(@Fusion final RuntimeContainer container) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (final var ignored = container.lookup(GithubExecutorConfiguration.class)) {
                    fail("Invalid configuration unexpectedly bound "
                            + ignored.instance().getClass().getSimpleName());
                }
            });
        }
    }

    public static class ConflictingGatewaysModule extends GithubConfigurationTestModule {
        public ConflictingGatewaysModule() {
            super(Map.ofEntries(
                    Map.entry("github.executor.docker.services.image", "trusted-services"),
                    Map.entry("github.executor.docker.services.controlNetwork", "trusted-control"),
                    Map.entry("github.executor.docker.services.keyStore", "/private/services.p12"),
                    Map.entry("github.executor.docker.services.providerTarget", "http://10.0.0.1:8080/v1"),
                    Map.entry("github.executor.docker.egress.image", "trusted-egress")));
        }
    }
}
