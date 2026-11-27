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
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationInjectionTest {
    @Nested
    @FusionSupport(modules = EnabledModule.class)
    class Enabled {
        @Test
        void receivesItsOwnConfiguration(@Fusion final CodingAgentConfiguration configuration) {
            assertTrue(configuration.sandbox().enabled());
            assertEquals("enabled-test", configuration.storageHome());
        }
    }

    @Nested
    @FusionSupport(modules = DisabledModule.class)
    class Disabled {
        @Test
        void receivesItsOwnConfiguration(@Fusion final CodingAgentConfiguration configuration) {
            assertFalse(configuration.sandbox().enabled());
            assertEquals("disabled-test", configuration.storageHome());
        }
    }

    @Nested
    @FusionSupport(modules = TemporaryDirectoryModule.class)
    class WithTemporaryDirectory {
        @TempDir
        static Path directory;

        @Test
        void receivesTheJunitDirectory(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals(directory.toString(), configuration.storageHome());
        }
    }

    public static class TemporaryDirectoryModule extends ConfigurationTestModule {
        public TemporaryDirectoryModule() {
            super(Map.of("yca.storageHome", WithTemporaryDirectory.directory.toString()));
        }
    }

    public static class EnabledModule extends ConfigurationTestModule {
        public EnabledModule() {
            super(Map.of("yca.storageHome", "enabled-test", "yca.sandbox.enabled", "true"));
        }
    }

    public static class DisabledModule extends ConfigurationTestModule {
        public DisabledModule() {
            super(Map.of("yca.storageHome", "disabled-test", "yca.sandbox.enabled", "false"));
        }
    }
}
