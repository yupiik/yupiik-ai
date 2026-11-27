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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillToolTest {
    @TempDir
    private static Path testWorkdir;

    @Nested
    @FusionSupport(modules = SharedToolLoadsSameNamedSkillsFromEachSessionRootScenarioModule.class)
    class SharedToolLoadsSameNamedSkillsFromEachSessionRootScenario {
        @TempDir
        static Path temp;

        @Test
        void sharedToolLoadsSameNamedSkillsFromEachSessionRoot(@Fusion final CodingAgentConfiguration configuration)
                throws IOException {
            final var first = temp.resolve("first");
            final var second = temp.resolve("second");
            skill(first.resolve(".opencode/skills"), "shared", "First instructions");
            skill(second.resolve(".opencode/skills"), "shared", "Second instructions");
            final var tool = new SkillTool(configuration);
            assertEquals(
                    ToolResult.ok("First instructions"),
                    tool.execute(Map.of("name", "shared"), ToolContext.empty(first, new DisabledSandbox(first))));
            assertEquals(
                    ToolResult.ok("Second instructions"),
                    tool.execute(Map.of("name", "shared"), ToolContext.empty(second, new DisabledSandbox(second))));
            assertEquals(
                    ToolResult.ok("First instructions"),
                    tool.execute(Map.of("name", "shared"), ToolContext.empty(first, new DisabledSandbox(first))));
        }
    }

    @Nested
    @FusionSupport(modules = MissingSkillListsOnlySessionAndGlobalNamesScenarioModule.class)
    class MissingSkillListsOnlySessionAndGlobalNamesScenario {
        @TempDir
        static Path temp;

        @Test
        void missingSkillListsOnlySessionAndGlobalNames(@Fusion final CodingAgentConfiguration configuration)
                throws IOException {
            final var first = temp.resolve("first");
            final var second = temp.resolve("second");
            skill(first.resolve(".opencode/skills"), "only-first", "First instructions");
            skill(second.resolve(".claude/skills"), "only-second", "Second instructions");
            skill(temp.resolve("storage/skills"), "global", "Global instructions");
            final var tool = new SkillTool(configuration);
            final var inSecond =
                    tool.execute(Map.of("name", "only-first"), ToolContext.empty(second, new DisabledSandbox(second)));
            assertFalse(inSecond.success());
            assertEquals("Skill not found: only-first. Available: only-second, global", inSecond.output());
            final var inFirst =
                    tool.execute(Map.of("name", "only-second"), ToolContext.empty(first, new DisabledSandbox(first)));
            assertFalse(inFirst.success());
            assertEquals("Skill not found: only-second. Available: only-first, global", inFirst.output());
        }
    }

    @Nested
    @FusionSupport(
            modules = ExplicitRootsNeverSelectTheConfiguredProjectAndGlobalSkillsRemainAvailableScenarioModule.class)
    class ExplicitRootsNeverSelectTheConfiguredProjectAndGlobalSkillsRemainAvailableScenario {
        @TempDir
        static Path temp;

        @Test
        void explicitRootsNeverSelectTheConfiguredProjectAndGlobalSkillsRemainAvailable(
                @Fusion final CodingAgentConfiguration configuration) throws IOException {
            final var first = temp.resolve("first");
            final var second = temp.resolve("second");
            skill(first.resolve(".yca/skills"), "local", "Local instructions");
            skill(temp.resolve("home/.agents/skills"), "global", "Global instructions");
            final var tool = new SkillTool(configuration);
            final var args = Map.<String, Object>of("name", "local");
            assertEquals(
                    ToolResult.ok("Local instructions"),
                    tool.execute(args, ToolContext.empty(first, new DisabledSandbox(first))));
            assertFalse(tool.execute(args, ToolContext.empty(second, new DisabledSandbox(second)))
                    .success());
            assertEquals(
                    ToolResult.ok("Global instructions"),
                    tool.execute(Map.of("name", "global"), ToolContext.empty(second, new DisabledSandbox(second))));
            assertEquals(
                    ToolResult.ok("Local instructions"),
                    tool.execute(args, ToolContext.empty(first, new DisabledSandbox(first))));
        }
    }

    @Nested
    @FusionSupport(modules = SharedDescriptionDoesNotDiscoverAnyProjectCatalogScenarioModule.class)
    class SharedDescriptionDoesNotDiscoverAnyProjectCatalogScenario {
        @TempDir
        static Path temp;

        @Test
        void sharedDescriptionDoesNotDiscoverAnyProjectCatalog(@Fusion final CodingAgentConfiguration configuration)
                throws IOException {
            final var first = temp.resolve("first");
            final var second = temp.resolve("second");
            skill(first.resolve(".opencode/skills"), "first-secret-skill", "First private description");
            skill(second.resolve(".opencode/skills"), "second-secret-skill", "Second private description");
            final var tool = new SkillTool(configuration);
            final var description = tool.description(temp);
            assertTrue(description.contains("session"));
            assertFalse(description.contains("first-secret-skill"));
            assertFalse(description.contains("First private description"));
            assertFalse(description.contains("<available_skills>"));
            tool.execute(Map.of("name", "second-secret-skill"), ToolContext.empty(second, new DisabledSandbox(second)));
            assertEquals(description, tool.description(temp));
            assertEquals(description, new SkillTool(configuration).description(second));
        }
    }

    @Nested
    @FusionSupport(modules = DisabledSkillsCannotBeLoadedFromAnyExplicitRootScenarioModule.class)
    class DisabledSkillsCannotBeLoadedFromAnyExplicitRootScenario {
        @TempDir
        static Path temp;

        @Test
        void disabledSkillsCannotBeLoadedFromAnyExplicitRoot(@Fusion final CodingAgentConfiguration configuration)
                throws IOException {
            final var first = temp.resolve("first");
            skill(first.resolve(".opencode/skills"), "local", "Local instructions");
            skill(temp.resolve("storage/skills"), "global", "Global instructions");
            final var tool = new SkillTool(configuration);
            assertTrue(tool.description(temp).contains("No skills are currently enabled"));
            assertFalse(tool.description(temp).contains("<available_skills>"));
            for (final var name : new String[] {"local", "global"}) {
                final var args = Map.<String, Object>of("name", name);
                assertEquals(
                        ToolResult.error("Skills are disabled."),
                        tool.execute(args, ToolContext.empty(temp, new DisabledSandbox(temp))));
                assertEquals(
                        ToolResult.error("Skills are disabled."),
                        tool.execute(args, ToolContext.empty(first, new DisabledSandbox(first))));
                assertEquals(
                        ToolResult.error("Skills are disabled."),
                        new SkillTool(null).execute(args, ToolContext.empty(first, new DisabledSandbox(first))));
            }
        }
    }

    @Nested
    @FusionSupport(modules = NameRemainsRequiredAndCannotEscapeSkillDirectoriesScenarioModule.class)
    class NameRemainsRequiredAndCannotEscapeSkillDirectoriesScenario {
        @TempDir
        static Path temp;

        @Test
        void nameRemainsRequiredAndCannotEscapeSkillDirectories(@Fusion final CodingAgentConfiguration configuration)
                throws IOException {
            final var first = temp.resolve("first");
            skill(first.resolve(".opencode/skills"), "local", "Local instructions");
            final var tool = new SkillTool(configuration);
            assertEquals(
                    ToolResult.error("name is required"),
                    tool.execute(Map.of(), ToolContext.empty(first, new DisabledSandbox(first))));
            assertEquals(
                    ToolResult.error("name is required"),
                    tool.execute(Map.of("name", "  "), ToolContext.empty(first, new DisabledSandbox(first))));
            assertFalse(tool.execute(Map.of("name", "../local"), ToolContext.empty(first, new DisabledSandbox(first)))
                    .success());
        }
    }

    private void skill(final Path directory, final String name, final String content) throws IOException {
        final var file = directory.resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    public static class SharedToolLoadsSameNamedSkillsFromEachSessionRootScenarioModule
            extends ConfigurationTestModule {
        public SharedToolLoadsSameNamedSkillsFromEachSessionRootScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    SharedToolLoadsSameNamedSkillsFromEachSessionRootScenario.temp
                            .resolve("first")
                            .toString(),
                    "yca.home",
                    SharedToolLoadsSameNamedSkillsFromEachSessionRootScenario.temp
                            .resolve("home")
                            .toString(),
                    "yca.storageHome",
                    SharedToolLoadsSameNamedSkillsFromEachSessionRootScenario.temp
                            .resolve("storage")
                            .toString(),
                    "yca.skills.enabled",
                    "true"));
        }
    }

    public static class MissingSkillListsOnlySessionAndGlobalNamesScenarioModule extends ConfigurationTestModule {
        public MissingSkillListsOnlySessionAndGlobalNamesScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    MissingSkillListsOnlySessionAndGlobalNamesScenario.temp
                            .resolve("first")
                            .toString(),
                    "yca.home",
                    MissingSkillListsOnlySessionAndGlobalNamesScenario.temp
                            .resolve("home")
                            .toString(),
                    "yca.storageHome",
                    MissingSkillListsOnlySessionAndGlobalNamesScenario.temp
                            .resolve("storage")
                            .toString(),
                    "yca.skills.enabled",
                    "true"));
        }
    }

    public static class ExplicitRootsNeverSelectTheConfiguredProjectAndGlobalSkillsRemainAvailableScenarioModule
            extends ConfigurationTestModule {
        public ExplicitRootsNeverSelectTheConfiguredProjectAndGlobalSkillsRemainAvailableScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    ExplicitRootsNeverSelectTheConfiguredProjectAndGlobalSkillsRemainAvailableScenario.temp
                            .resolve("first")
                            .toString(),
                    "yca.home",
                    ExplicitRootsNeverSelectTheConfiguredProjectAndGlobalSkillsRemainAvailableScenario.temp
                            .resolve("home")
                            .toString(),
                    "yca.storageHome",
                    ExplicitRootsNeverSelectTheConfiguredProjectAndGlobalSkillsRemainAvailableScenario.temp
                            .resolve("storage")
                            .toString(),
                    "yca.skills.enabled",
                    "true"));
        }
    }

    public static class SharedDescriptionDoesNotDiscoverAnyProjectCatalogScenarioModule
            extends ConfigurationTestModule {
        public SharedDescriptionDoesNotDiscoverAnyProjectCatalogScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    SharedDescriptionDoesNotDiscoverAnyProjectCatalogScenario.temp
                            .resolve("first")
                            .toString(),
                    "yca.home",
                    SharedDescriptionDoesNotDiscoverAnyProjectCatalogScenario.temp
                            .resolve("home")
                            .toString(),
                    "yca.storageHome",
                    SharedDescriptionDoesNotDiscoverAnyProjectCatalogScenario.temp
                            .resolve("storage")
                            .toString(),
                    "yca.skills.enabled",
                    "true"));
        }
    }

    public static class DisabledSkillsCannotBeLoadedFromAnyExplicitRootScenarioModule extends ConfigurationTestModule {
        public DisabledSkillsCannotBeLoadedFromAnyExplicitRootScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    DisabledSkillsCannotBeLoadedFromAnyExplicitRootScenario.temp
                            .resolve("first")
                            .toString(),
                    "yca.home",
                    DisabledSkillsCannotBeLoadedFromAnyExplicitRootScenario.temp
                            .resolve("home")
                            .toString(),
                    "yca.storageHome",
                    DisabledSkillsCannotBeLoadedFromAnyExplicitRootScenario.temp
                            .resolve("storage")
                            .toString(),
                    "yca.skills.enabled",
                    "false"));
        }
    }

    public static class NameRemainsRequiredAndCannotEscapeSkillDirectoriesScenarioModule
            extends ConfigurationTestModule {
        public NameRemainsRequiredAndCannotEscapeSkillDirectoriesScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    NameRemainsRequiredAndCannotEscapeSkillDirectoriesScenario.temp
                            .resolve("first")
                            .toString(),
                    "yca.home",
                    NameRemainsRequiredAndCannotEscapeSkillDirectoriesScenario.temp
                            .resolve("home")
                            .toString(),
                    "yca.storageHome",
                    NameRemainsRequiredAndCannotEscapeSkillDirectoriesScenario.temp
                            .resolve("storage")
                            .toString(),
                    "yca.skills.enabled",
                    "true"));
        }
    }
}
