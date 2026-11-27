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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CodingAgentConfigurationTest {
    private static final String HOME = System.getProperty("user.home");

    @Nested
    @FusionSupport(modules = DefaultsModule.class)
    class Defaults {
        @Test
        void providerApiKeyDefaultsToEmpty(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals("", configuration.provider().apiKey());
            assertEquals("", configuration.provider().providers().getFirst().resolveApiKey());
        }

        @Test
        void mcpCallTimeoutDefaultsAreInstanceScoped(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals(60, configuration.mcp().callTimeoutSeconds());
        }

        @Test
        void resolveStorageHomeAndHomeDelegateToExpandHome(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals(HOME + "/.yupiik/coding-agent", configuration.resolveStorageHome());
            assertEquals(HOME, configuration.resolveHome());
        }
    }

    @Nested
    @FusionSupport(modules = ApiKeyModule.class)
    class BoundApiKey {
        @Test
        void providerApiKeyDefaultsAreBoundFromConfiguration(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals("test-default", configuration.provider().apiKey());
            final var provider = configuration.provider().providers().getFirst();
            assertEquals("test-default", provider.apiKey());
            assertEquals("test-default", provider.resolveApiKey());
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitApiKeyModule.class)
    class ExplicitApiKey {
        @Test
        void explicitProviderApiKeysOverrideDefaults(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals("explicit-test-key", configuration.provider().apiKey());
            assertEquals(
                    "explicit-test-key",
                    configuration.provider().providers().getFirst().resolveApiKey());
        }
    }

    @Nested
    @FusionSupport(modules = EmptyApiKeyModule.class)
    class EmptyApiKey {
        @Test
        void emptyProviderApiKeysOverrideDefaults(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals("", configuration.provider().apiKey());
            assertEquals("", configuration.provider().providers().getFirst().resolveApiKey());
        }
    }

    @Nested
    @FusionSupport(modules = FreeProviderModule.class)
    class FreeProvider {
        @Test
        void freeProviderRemainsPublic(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals(
                    "public", configuration.provider().providers().getFirst().resolveApiKey());
        }
    }

    @Nested
    @FusionSupport(modules = TimeoutModule.class)
    class OverriddenTimeout {
        @Test
        void mcpCallTimeoutOverridesAreInstanceScoped(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals(2, configuration.mcp().callTimeoutSeconds());
        }
    }

    @Test
    void surefireDoesNotInheritLocalOpenAiCredential() {
        assertEquals("", System.getenv("OPENAI_API_KEY"));
    }

    @Test
    void expandHomeExpandsLeadingTildePath() {
        assertEquals(HOME + "/.m2/repository", CodingAgentConfiguration.expandHome("~/.m2/repository"));
    }

    @Test
    void expandHomeLeavesOtherValuesUntouched() {
        assertEquals("/opt/app/config", CodingAgentConfiguration.expandHome("/opt/app/config"));
        assertEquals("relative/path", CodingAgentConfiguration.expandHome("relative/path"));
        assertEquals("~", CodingAgentConfiguration.expandHome("~"));
        assertNull(CodingAgentConfiguration.expandHome(null));
    }

    public static class DefaultsModule extends ConfigurationTestModule {
        public DefaultsModule() {
            super(Map.of("yca.provider.providers.length", "1"));
        }
    }

    public static class ApiKeyModule extends ConfigurationTestModule {
        public ApiKeyModule() {
            super(Map.of("OPENAI_API_KEY", "test-default", "yca.provider.providers.length", "1"));
        }
    }

    public static class ExplicitApiKeyModule extends ConfigurationTestModule {
        public ExplicitApiKeyModule() {
            super(Map.of(
                    "OPENAI_API_KEY",
                    "test-default",
                    "yca.provider.apiKey",
                    "explicit-test-key",
                    "yca.provider.providers.length",
                    "1",
                    "yca.provider.providers.0.apiKey",
                    "explicit-test-key"));
        }
    }

    public static class EmptyApiKeyModule extends ConfigurationTestModule {
        public EmptyApiKeyModule() {
            super(Map.of(
                    "OPENAI_API_KEY",
                    "test-default",
                    "yca.provider.apiKey",
                    "",
                    "yca.provider.providers.length",
                    "1",
                    "yca.provider.providers.0.apiKey",
                    ""));
        }
    }

    public static class FreeProviderModule extends ConfigurationTestModule {
        public FreeProviderModule() {
            super(Map.of("yca.provider.providers.length", "1", "yca.provider.providers.0.api", "OPENCODE_FREE"));
        }
    }

    public static class TimeoutModule extends ConfigurationTestModule {
        public TimeoutModule() {
            super(Map.of("yca.mcp.callTimeoutSeconds", "2"));
        }
    }
}
