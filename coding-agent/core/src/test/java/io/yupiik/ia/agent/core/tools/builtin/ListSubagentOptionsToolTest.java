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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class ListSubagentOptionsToolTest {
    private CodingAgentConfiguration configuration;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    @TempDir
    private static Path testWorkdir;

    private JsonMapper mapper;

    private static Map<String, String> configurationValues(
            final Path storageHome, final Map<String, String> overrides) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.model", "parent-model");
        entries.put("yca.provider.providers.0.models.length", "2");
        entries.put("yca.provider.providers.0.models.0.model", "parent-model");
        entries.put("yca.provider.providers.0.models.1.model", "review-model");
        entries.put("yca.agents.length", "2");
        entries.put("yca.agents.0.name", "reviewer");
        entries.put("yca.agents.0.description", "Reviews code for bugs and regressions.");
        entries.put("yca.agents.0.prompt", "Full review instructions that should not appear in the catalog.");
        entries.put("yca.agents.0.model.name", "review-model");
        entries.put("yca.agents.1.name", "tester");
        entries.put("yca.agents.1.description", "Creates tests.");
        entries.put("yca.agents.1.prompt", "Write tests.");
        entries.put("yca.tools.spawnSubagent.allowedAgents", "reviewer");
        entries.put("yca.subagents.allowModelOverride", "true");
        entries.putAll(overrides);
        return entries;
    }

    private ListSubagentOptionsTool tool(final CodingAgentConfiguration config) {
        final var manager = new ProviderManager(config, mapper);
        return new ListSubagentOptionsTool(new AgentProvider(config), manager, config, mapper);
    }

    private ToolResult execute(final ListSubagentOptionsTool tool, final Map<String, Object> args) {
        return tool.execute(
                args,
                new ToolContext(
                        "s1",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        () -> false,
                        ChatSettings.EMPTY.withProviderId("default").withModel("parent-model"),
                        null,
                        testWorkdir,
                        new DisabledSandbox(testWorkdir)));
    }

    @Nested
    @FusionSupport(modules = ListsCompactFilteredProfilesAndModelsOnDemandScenarioModule.class)
    class ListsCompactFilteredProfilesAndModelsOnDemandScenario {
        @TempDir
        static Path directory;

        @Test
        void listsCompactFilteredProfilesAndModelsOnDemand(final JsonMapper mapper) {
            ListSubagentOptionsToolTest.this.mapper = mapper;
            final var result = execute(tool(configuration), Map.of("kind", "all", "limit", 50));

            assertTrue(result.success(), result.output());
            assertTrue(result.output().contains("reviewer"), result.output());
            assertTrue(result.output().contains("Reviews code for bugs"), result.output());
            assertTrue(result.output().contains("review-model"), result.output());
            assertFalse(result.output().contains("Full review instructions"), result.output());
            assertFalse(result.output().contains("tester"), result.output());
        }
    }

    @Nested
    @FusionSupport(modules = DiscoveryDoesNotRequireModelOverrideOrAuthorizeAlternativesScenarioModule.class)
    class DiscoveryDoesNotRequireModelOverrideOrAuthorizeAlternativesScenario {
        @TempDir
        static Path directory;

        @Test
        void discoveryDoesNotRequireModelOverrideOrAuthorizeAlternatives(final JsonMapper mapper) {
            ListSubagentOptionsToolTest.this.mapper = mapper;
            final var tool = tool(configuration);
            final var alternative = execute(tool, Map.of("kind", "models", "query", "review-model"));
            assertTrue(alternative.success(), alternative.output());
            assertTrue(alternative.output().contains("$default/review-model"), alternative.output());
            assertTrue(alternative.output().contains("\"default\":false"), alternative.output());
            assertTrue(alternative.output().contains("\"authorized\":false"), alternative.output());
            assertTrue(alternative.output().contains("\"requires_approval\":true"), alternative.output());

            final var parent = execute(tool, Map.of("kind", "models", "query", "parent-model"));
            assertTrue(parent.output().contains("\"default\":true"), parent.output());
            assertTrue(parent.output().contains("\"authorized\":true"), parent.output());
            assertTrue(parent.output().contains("\"requires_approval\":false"), parent.output());
        }
    }

    @Nested
    @FusionSupport(modules = BoundsPagesAndRejectsInvalidFiltersScenarioModule.class)
    class BoundsPagesAndRejectsInvalidFiltersScenario {
        @TempDir
        static Path directory;

        @Test
        void boundsPagesAndRejectsInvalidFilters(final JsonMapper mapper) {
            ListSubagentOptionsToolTest.this.mapper = mapper;
            final var tool = tool(configuration);
            final ToolResult page = execute(tool, Map.of("kind", "models", "limit", 100));
            assertTrue(page.success(), page.output());
            assertTrue(page.output().contains("\"limit\":50"), page.output());

            final var invalid = execute(tool, Map.of("kind", "not-a-kind"));
            assertFalse(invalid.success());
        }
    }

    @Nested
    @FusionSupport(modules = RejectsInvalidPaginationScenarioModule.class)
    class RejectsInvalidPaginationScenario {
        @TempDir
        static Path directory;

        @Test
        void rejectsInvalidPagination(final JsonMapper mapper) {
            ListSubagentOptionsToolTest.this.mapper = mapper;
            final var tool = tool(configuration);
            for (final var invalid : List.of(1.5d, Double.NaN, Double.POSITIVE_INFINITY, -1, Long.MAX_VALUE)) {
                final var result = execute(tool, Map.of("offset", invalid));
                assertFalse(result.success(), "pagination value should be rejected: " + invalid);
            }
            assertFalse(execute(tool, Map.of("offset", 100_001)).success());
        }
    }

    @Nested
    @FusionSupport(modules = CatalogRunsTheSameModelPolicyAsSpawnScenarioModule.class)
    class CatalogRunsTheSameModelPolicyAsSpawnScenario {
        @TempDir
        static Path directory;

        @Test
        void catalogRunsTheSameModelPolicyAsSpawn(final JsonMapper mapper) {
            ListSubagentOptionsToolTest.this.mapper = mapper;
            // restrict allowed models to one the reviewer does not use: its routed $default/review-model
            // reference is not allowed, so the profile row must be filtered out of the catalog, mirroring
            // the spawn-time model gate.
            final var cfg = configuration;
            final var result = execute(tool(cfg), Map.of("kind", "agents"));
            assertTrue(result.success(), result.output());
            assertFalse(result.output().contains("reviewer"), result.output());
        }
    }

    public static class ListsCompactFilteredProfilesAndModelsOnDemandScenarioModule extends ConfigurationTestModule {
        public ListsCompactFilteredProfilesAndModelsOnDemandScenarioModule() {
            super(configurationValues(ListsCompactFilteredProfilesAndModelsOnDemandScenario.directory, Map.of()));
        }
    }

    public static class DiscoveryDoesNotRequireModelOverrideOrAuthorizeAlternativesScenarioModule
            extends ConfigurationTestModule {
        public DiscoveryDoesNotRequireModelOverrideOrAuthorizeAlternativesScenarioModule() {
            super(configurationValues(
                    DiscoveryDoesNotRequireModelOverrideOrAuthorizeAlternativesScenario.directory,
                    Map.of("yca.subagents.allowModelOverride", "false")));
        }
    }

    public static class BoundsPagesAndRejectsInvalidFiltersScenarioModule extends ConfigurationTestModule {
        public BoundsPagesAndRejectsInvalidFiltersScenarioModule() {
            super(configurationValues(BoundsPagesAndRejectsInvalidFiltersScenario.directory, Map.of()));
        }
    }

    public static class RejectsInvalidPaginationScenarioModule extends ConfigurationTestModule {
        public RejectsInvalidPaginationScenarioModule() {
            super(configurationValues(RejectsInvalidPaginationScenario.directory, Map.of()));
        }
    }

    public static class CatalogRunsTheSameModelPolicyAsSpawnScenarioModule extends ConfigurationTestModule {
        public CatalogRunsTheSameModelPolicyAsSpawnScenarioModule() {
            super(configurationValues(
                    CatalogRunsTheSameModelPolicyAsSpawnScenario.directory,
                    Map.of("yca.tools.spawnSubagent.allowedModels", "$default/parent-model")));
        }
    }
}
