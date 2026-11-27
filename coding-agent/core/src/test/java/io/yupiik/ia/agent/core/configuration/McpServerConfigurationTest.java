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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class McpServerConfigurationTest {
    @Nested
    @FusionSupport(modules = ServerModule.class)
    class Defaults {
        @Test
        void propertyDefaultsAreLoaded(@Fusion final CodingAgentConfiguration configuration) {
            final var server = configuration.mcp().servers().getFirst();
            assertSame(McpServerConfiguration.DEFAULT_AVOID_ON_ERROR_PATTERNS, server.avoidOnErrorPatterns());
            assertEquals(2, server.avoidOnErrorPatterns().size());
        }
    }

    @Nested
    @FusionSupport(modules = ReplacementModule.class)
    class Replacements {
        @Test
        void flatConfigurationReplacesDefaultPatterns(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals(
                    List.of("(?i)credits exhausted", "explicit account suspension"),
                    configuration.mcp().servers().getFirst().avoidOnErrorPatterns());
        }
    }

    @Nested
    @FusionSupport(modules = SeparatorModule.class)
    class CustomSeparator {
        @Test
        void flatConfigurationSupportsCustomSeparator(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals(
                    List.of("(?i)quota reached after [0-9]{1,3} requests", "(?i)credits exhausted"),
                    configuration.mcp().servers().getFirst().avoidOnErrorPatterns());
        }
    }

    @Nested
    @FusionSupport(modules = EmptyModule.class)
    class EmptyPatterns {
        @Test
        void emptyListDisablesPatterns(@Fusion final CodingAgentConfiguration configuration) {
            assertTrue(configuration
                    .mcp()
                    .servers()
                    .getFirst()
                    .avoidOnErrorPatterns()
                    .isEmpty());
        }
    }

    @Nested
    @FusionSupport(modules = EqualDefaultsModule.class)
    class EqualDefaults {
        @Test
        void equalDefaultsReuseConstant(@Fusion final CodingAgentConfiguration configuration) {
            assertSame(
                    McpServerConfiguration.DEFAULT_AVOID_ON_ERROR_PATTERNS,
                    configuration.mcp().servers().getFirst().avoidOnErrorPatterns());
        }
    }

    @Test
    void jsonOmittedAndNullUseDefaultsButEmptyDisablesAndCustomReplaces(final JsonMapper mapper) {
        for (final var json : List.of("{\"name\":\"docs\"}", "{\"name\":\"docs\",\"avoidOnErrorPatterns\":null}")) {
            assertSame(
                    McpServerConfiguration.DEFAULT_AVOID_ON_ERROR_PATTERNS,
                    mapper.fromString(McpServerConfiguration.class, json).avoidOnErrorPatterns());
        }
        assertTrue(mapper.fromString(McpServerConfiguration.class, "{\"name\":\"docs\",\"avoidOnErrorPatterns\":[]}")
                .avoidOnErrorPatterns()
                .isEmpty());
        assertEquals(
                List.of("credits exhausted"),
                mapper.fromString(
                                McpServerConfiguration.class,
                                "{\"name\":\"docs\",\"avoidOnErrorPatterns\":[\"credits exhausted\"]}")
                        .avoidOnErrorPatterns());
    }

    public static class ServerModule extends ConfigurationTestModule {
        public ServerModule() {
            this(null);
        }

        protected ServerModule(final String patterns) {
            super(
                    patterns == null
                            ? Map.of("yca.mcp.servers.length", "1", "yca.mcp.servers.0.name", "docs")
                            : Map.of(
                                    "yca.mcp.servers.length",
                                    "1",
                                    "yca.mcp.servers.0.name",
                                    "docs",
                                    "yca.mcp.servers.0.avoidOnErrorPatterns",
                                    patterns));
        }
    }

    public static class ReplacementModule extends ServerModule {
        public ReplacementModule() {
            super("(?i)credits exhausted,explicit account suspension");
        }
    }

    public static class SeparatorModule extends ServerModule {
        public SeparatorModule() {
            super("sep=;,(?i)quota reached after [0-9]{1,3} requests;(?i)credits exhausted");
        }
    }

    public static class EmptyModule extends ServerModule {
        public EmptyModule() {
            super("");
        }
    }

    public static class EqualDefaultsModule extends ServerModule {
        public EqualDefaultsModule() {
            super("sep=;," + String.join(";", McpServerConfiguration.DEFAULT_AVOID_ON_ERROR_PATTERNS));
        }
    }
}
