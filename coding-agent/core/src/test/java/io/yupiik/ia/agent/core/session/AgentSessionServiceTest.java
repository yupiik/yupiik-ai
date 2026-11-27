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
package io.yupiik.ia.agent.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandOptionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandPermissionConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandStartMatcherConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.context.CompactionResult;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.ToolCall.FunctionCall;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.memory.MemoryCompactor;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.todo.PlanFileService;
import io.yupiik.ia.agent.core.todo.PlanTodoSeeder;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSessionServiceTest {
    private CodingAgentConfiguration baseline;

    @BeforeEach
    void configure(@Fusion final CodingAgentConfiguration configuration) {
        baseline = configuration;
    }

    @TempDir
    private static Path testWorkdir;

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ToolsListsAndGroupsByServer {
        @Test
        void toolsListsAndGroupsByServer() {
            final var service = service(new McpManager() {
                @Override
                public Map<String, List<EmbeddedTool>> toolsByServer() {
                    return Map.of(
                            "fs",
                                    List.of(
                                            tool("read", "[fs] Read a file\n\nlong"),
                                            tool("write_file", "[fs] Write a file")),
                            "code", List.of(tool("find_symbol", "[code] Find a symbol")));
                }
            });

            final var listing = service.tools("", testWorkdir);

            assertTrue(listing.anyTool());
            assertEquals(3, listing.matches());
            assertEquals(
                    List.of("code", "fs"),
                    listing.servers().stream()
                            .map(AgentSessionService.ServerTools::server)
                            .toList());
            final var fs = listing.servers().stream()
                    .filter(s -> s.server().equals("fs"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    List.of("read", "write_file"),
                    fs.tools().stream().map(AgentSessionService.ToolInfo::name).toList());
            assertEquals("Read a file", fs.tools().get(0).description());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ToolsFiltersByServerAndToolKey {
        @Test
        void toolsFiltersByServerAndToolKey() {
            final var service = service(new McpManager() {
                @Override
                public Map<String, List<EmbeddedTool>> toolsByServer() {
                    return Map.of("fs", List.of(tool("read", "Read a file"), tool("write_file", "Write a file")));
                }
            });

            assertEquals(1, service.tools("fs read", testWorkdir).matches());
            assertEquals(0, service.tools("nomatch", testWorkdir).matches());
            final var filtered = service.tools("write", testWorkdir);
            assertEquals(1, filtered.servers().get(0).tools().size());
            assertEquals("write_file", filtered.servers().get(0).tools().get(0).name());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ToolsNoToolsYieldEmptyListing {
        @Test
        void toolsNoToolsYieldEmptyListing() {
            final var service = service(new McpManager() {
                @Override
                public Map<String, List<EmbeddedTool>> toolsByServer() {
                    return Map.of();
                }
            });

            final var listing = service.tools("", testWorkdir);
            assertFalse(listing.anyTool());
            assertTrue(listing.servers().isEmpty());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class PermissionsParseModesNoneAndInvalid {
        @Test
        void permissionsParseModesNoneAndInvalid() {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            final var service = service(permissions);

            assertEquals(PermissionMode.ASK, service.setPermissions("none", runtime(permissions)));
            assertEquals(PermissionMode.ASK, service.setPermissions("safe", runtime(permissions)));
            assertEquals(PermissionMode.YOLO, service.setPermissions("yolo", runtime(permissions)));
            assertNull(service.setPermissions("all", runtime(permissions)), "'all' is no longer a slash value");
            assertNull(service.setPermissions("unsafe", runtime(permissions)), "'unsafe' is no longer a slash value");
            assertNull(service.setPermissions("", runtime(permissions)));
            assertNull(service.setPermissions("bogus", runtime(permissions)));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetPermissionsUpdatesService {
        @Test
        void setPermissionsUpdatesService() {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            final var service = service(permissions);

            assertEquals(PermissionMode.YOLO, service.setPermissions("yolo", runtime(permissions)));
            assertEquals(PermissionMode.YOLO, permissions.permissionMode());
            assertEquals(PermissionMode.ASK, service.setPermissions("none", runtime(permissions)));
            assertEquals(PermissionMode.ASK, permissions.permissionMode());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetPermissionsRoutesPermissionModes {
        @Test
        void setPermissionsRoutesPermissionModes() {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            final var service = service(permissions);

            assertEquals(PermissionMode.YOLO, service.setPermissions("yolo", runtime(permissions)));
            assertEquals(PermissionMode.YOLO, permissions.permissionMode());

            assertEquals(PermissionMode.AUTO_EDIT, service.setPermissions("auto_edit", runtime(permissions)));
            assertEquals(PermissionMode.AUTO_EDIT, permissions.permissionMode());

            assertEquals(PermissionMode.ASK, service.setPermissions("ask", runtime(permissions)));
            assertEquals(PermissionMode.ASK, permissions.permissionMode());
            assertNull(service.setPermissions("bogus", runtime(permissions)));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class PermissionsInfoExposesStateAndSessionGrants {
        @Test
        void permissionsInfoExposesStateAndSessionGrants() {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            permissions.setMode(AgentMode.BUILD);
            permissions.setPermissionMode(PermissionMode.ASK);
            permissions.grantSession("bash", Map.of("command", "mvn install"));
            final var service = service(permissions);

            final var info = service.permissionsInfo(runtime(permissions));
            assertEquals(AgentMode.BUILD, info.agentMode());
            assertEquals(PermissionMode.ASK, info.permissionMode());
            assertEquals(1, info.sessionGrants().size());
            assertTrue(info.sessionGrants().stream().anyMatch(g -> g.contains("|bash:mvn")));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetModeClearsSessionGrantsWhenBackToPlan {
        @Test
        void setModeClearsSessionGrantsWhenBackToPlan() {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            final var service = service(permissions);
            permissions.setMode(AgentMode.BUILD);
            permissions.grantSession("bash", Map.of("command", "mvn install"));
            assertEquals(1, permissions.sessionGrants().size());
            permissions.addDynamicRule(new CommandPermissionConfiguration(
                    new CommandStartMatcherConfiguration(new CommandOptionsConfiguration(List.of("mvn")), List.of()),
                    PermissionMode.YOLO,
                    null));
            assertEquals(1, permissions.dynamicRules().size());

            service.setMode("plan", "s1", runtime(permissions));

            assertTrue(permissions.sessionGrants().isEmpty(), "build approvals must not leak back into plan mode");
            assertTrue(
                    permissions.dynamicRules().isEmpty(), "build-time dynamic rules must not leak back into plan mode");
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetModeKeepsSessionGrantsWhenStayingInBuild {
        @Test
        void setModeKeepsSessionGrantsWhenStayingInBuild() {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            final var service = service(permissions);
            permissions.setMode(AgentMode.BUILD);
            permissions.grantSession("bash", Map.of("command", "mvn install"));

            service.setMode("build", "s1", runtime(permissions));

            assertEquals(1, permissions.sessionGrants().size());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetModeClearsPermissionModeOverrideWhenBackToPlan {
        @Test
        void setModeClearsPermissionModeOverrideWhenBackToPlan() {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            final var service = service(permissions);
            final var runtime = runtime(permissions);
            service.setPermissions("yolo", runtime);
            assertEquals(PermissionMode.YOLO, permissions.permissionMode());

            service.setMode("plan", "s1", runtime);

            assertEquals(
                    PermissionMode.ASK,
                    permissions.permissionMode(),
                    "a build approval mode must not silently keep plan mode building");
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetModeKeepsPermissionModeOverrideWhenStayingInBuild {
        @Test
        void setModeKeepsPermissionModeOverrideWhenStayingInBuild() {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            final var service = service(permissions);
            final var runtime = runtime(permissions);
            permissions.setMode(AgentMode.BUILD); // a true build -> build switch, not plan -> build
            service.setPermissions("yolo", runtime);

            service.setMode("build", "s1", runtime);

            assertEquals(PermissionMode.YOLO, permissions.permissionMode());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ModeSwitchesPreserveTodosWithoutAuxiliaryRequests {
        @Test
        void modeSwitchesPreserveTodosWithoutAuxiliaryRequests() {
            final var client = new ChatClient() {
                @Override
                public ChatResult chat(final ChatCompletionRequest request) {
                    throw new AssertionError("Mode changes must not request completions");
                }

                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request,
                        final String schemaName,
                        final Map<String, Object> schema) {
                    throw new AssertionError("Mode changes must not derive todos");
                }
            };
            final var plans = new PlanTodoSeeder() {
                @Override
                public String plan(final String sessionId) {
                    throw new AssertionError("Mode changes must not inspect assistant plans");
                }
            };
            try (client) {
                final var service =
                        new AgentSessionService(client, null, null, null, null, null, null, plans, cfg(), null);
                for (final var original : List.of(
                        List.<TodoItem>of(),
                        List.of(
                                new TodoItem("existing task", "pending"),
                                new TodoItem("finished task", "completed")))) {
                    final var todos = new TodoStore() {
                        @Override
                        public List<TodoItem> all() {
                            return original;
                        }

                        @Override
                        public List<TodoItem> replace(final List<TodoItem> items) {
                            throw new AssertionError("Mode changes must preserve todos");
                        }
                    };
                    final var permissions = new PermissionService(cfg(), cfg().resolveWorkingDir());
                    permissions.setMode(AgentMode.PLAN);
                    final var runtime = runtime(permissions, todos, null);

                    final var entering = service.setMode("build", "s1", runtime);
                    assertEquals("mode: build - permissions: auto_edit", entering.message());
                    assertNull(entering.seedMessage());
                    assertNull(entering.seedError());
                    assertEquals(AgentMode.BUILD, permissions.mode());
                    assertEquals(PermissionMode.AUTO_EDIT, permissions.permissionMode());
                    assertEquals(original, todos.all());

                    permissions.setPermissionMode(PermissionMode.YOLO);
                    final var staying = service.setMode("build", "s1", runtime);
                    assertEquals("mode: build", staying.message());
                    assertNull(staying.seedMessage());
                    assertNull(staying.seedError());
                    assertEquals(PermissionMode.YOLO, permissions.permissionMode());
                    assertEquals(original, todos.all());

                    final var planning = service.setMode("plan", "s1", runtime);
                    assertEquals("mode: plan", planning.message());
                    assertNull(planning.seedMessage());
                    assertNull(planning.seedError());
                    assertEquals(AgentMode.PLAN, permissions.mode());
                    assertEquals(PermissionMode.ASK, permissions.permissionMode());
                    assertEquals(original, todos.all());
                }
            }
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetModeDefaultsApprovalToAutoEditWhenEnteringBuild {
        @Test
        void setModeDefaultsApprovalToAutoEditWhenEnteringBuild() {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            final var service = service(permissions);
            final var runtime = runtime(permissions);
            assertEquals(PermissionMode.ASK, permissions.permissionMode());

            final var change = service.setMode("build", "s1", runtime);

            assertEquals(
                    PermissionMode.AUTO_EDIT, permissions.permissionMode(), "plan -> build must default to auto_edit");
            assertTrue(change.message().contains("permissions: auto_edit"), change.message());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetModeOverwritesExplicitOverrideWhenEnteringBuildFromPlan {
        @Test
        void setModeOverwritesExplicitOverrideWhenEnteringBuildFromPlan() {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            final var service = service(permissions);
            final var runtime = runtime(permissions);
            service.setPermissions("yolo", runtime);
            assertEquals(PermissionMode.YOLO, permissions.permissionMode());

            service.setMode("build", "s1", runtime);

            assertEquals(
                    PermissionMode.AUTO_EDIT,
                    permissions.permissionMode(),
                    "the plan -> build default must win even over an explicit session override");
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetModelStripsDollarPrefix {
        @Test
        void setModelStripsDollarPrefix() {
            final var client = chatClient();
            final var service = service(client);
            final var settings = ChatSettings.EMPTY;

            final var updated = service.setModel("$my-model", settings);
            assertEquals("my-model", updated.model());
            assertNull(settings.model(), "pure: the passed value is unchanged");
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetProviderWritesOnlyTheSessionSettings {
        @Test
        void setProviderWritesOnlyTheSessionSettings() {
            final var client = chatClient(); // no ProviderManager: calling chatClient.setProvider would fail
            final var service = service(client);
            final var settings = ChatSettings.EMPTY;

            final var ollama = service.setProvider("ollama", settings);
            assertEquals("ollama", ollama.providerId(), "the selection lands in the returned value");
            assertNull(settings.providerId(), "pure: the passed value is unchanged");

            // "default" / blank clear the override so the session falls back to the process default
            assertNull(service.setProvider("default", settings).providerId());
            assertEquals("openai", service.setProvider("openai", settings).providerId());
            assertNull(service.setProvider("  ", settings).providerId());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetProviderResetsTheProviderScopedModelSelection {
        @Test
        void setProviderResetsTheProviderScopedModelSelection() {
            final var service = service(chatClient());
            final var settings = ChatSettings.EMPTY.withModel("qwen3-coder").withVariant("balanced");

            final var updated = service.setProvider("ollama", settings);

            assertEquals("ollama", updated.providerId());
            assertNull(updated.model(), "model/variant/sampling are provider-scoped and must reset on switch");
            assertNull(updated.variant());
            assertEquals("qwen3-coder", settings.model(), "pure: the passed value is unchanged");
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetModelClearsVariantAndSamplingOverrides {
        @Test
        void setModelClearsVariantAndSamplingOverrides() {
            final var service = service(chatClient());
            final var settings = ChatSettings.EMPTY
                    .withVariant("fast")
                    .withSampling(new ChatClient.SamplingOverride(
                            Map.of(ChatClient.SamplingField.TEMPERATURE, Optional.of(0.2))));

            final var updated = service.setModel("$gpt-5", settings);

            assertEquals("gpt-5", updated.model());
            assertNull(updated.variant(), "a model change must drop the variant of the previous model");
            assertNull(updated.sampling(), "a model change must drop the sampling overrides of the previous model");
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class AgentOverrideResolvesKnownAndKeepsUnknownAndPlainText {
        @Test
        void agentOverrideResolvesKnownAndKeepsUnknownAndPlainText() {
            final var service = service(agentProvider("coder"), agentsConfig("coder"));

            final var known = service.agentOverride("@coder do the thing");
            assertEquals("coder", known.agentName());
            assertEquals("do the thing", known.prompt());

            final var unknown = service.agentOverride("@nope do the thing");
            assertNull(unknown.agentName());
            assertEquals("@nope do the thing", unknown.prompt());

            final var noSpace = service.agentOverride("@coder");
            assertNull(noSpace.agentName());
            assertEquals("@coder", noSpace.prompt());

            final var plain = service.agentOverride("plain prompt");
            assertNull(plain.agentName());
            assertEquals("plain prompt", plain.prompt());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SetAgentRejectsUnknown {
        @Test
        void setAgentRejectsUnknown() {
            final var service = service(agentProvider("coder"), agentsConfig("coder"));

            assertEquals("coder", service.setAgent("coder"));
            assertNull(service.setAgent("missing"));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class FirstLineStatic {
        @Test
        void firstLineStatic() {
            assertEquals("", AgentSessionService.firstLine(null));
            assertEquals("", AgentSessionService.firstLine(""));
            assertEquals("first", AgentSessionService.firstLine("first\nsecond"));
            assertEquals("no newline", AgentSessionService.firstLine("no newline"));
            assertEquals("first line", AgentSessionService.firstLine("first line\nsecond line\nthird"));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ExportPlanErrorsOutsidePlanMode {
        @Test
        void exportPlanErrorsOutsidePlanMode() {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            permissions.setMode(AgentMode.BUILD);
            final var service = service(permissions);

            final var export = service.exportPlan("s1", "", "plan", runtime(permissions, null, null));

            assertNotNull(export.error());
            assertTrue(export.error().contains("plan mode"));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ExportPlanErrorsWhenNoPlanYet {
        @Test
        void exportPlanErrorsWhenNoPlanYet() {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            permissions.setMode(AgentMode.PLAN);
            final var seeder = new PlanTodoSeeder() {
                @Override
                public String plan(final String sessionId) {
                    return null;
                }
            };
            final var service = service(seeder, new PlanFileService(null), cfg());

            final var export = service.exportPlan("s1", "", "plan", runtime(permissions, null, null));

            assertNotNull(export.error());
            assertTrue(export.error().contains("no plan"));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ExportPlanWritesPathInPlanMode {
        @Test
        void exportPlanWritesPathInPlanMode(@TempDir final Path dir) throws Exception {
            final var permissions = new PermissionService(cfg(), (cfg()).resolveWorkingDir());
            permissions.setMode(AgentMode.PLAN);
            final var seeder = new PlanTodoSeeder() {
                @Override
                public String plan(final String sessionId) {
                    return "the plan content";
                }
            };
            final var fileService = new PlanFileService(null) {
                @Override
                public Path defaultPath(final Path storageHome, final String title, final String sessionId) {
                    return storageHome.resolve(title + ".plan.md");
                }

                @Override
                public String render(final String plan, final List<TodoItem> todos) {
                    return plan + "\n\n## Todos\n" + todos.size();
                }
            };
            final var config = cfg(dir.toString(), AgentMode.PLAN);
            final var todoStore = new TodoStore() {
                @Override
                public List<TodoItem> all() {
                    return List.of(new TodoItem("task one", "pending"));
                }
            };
            final var service = service(seeder, fileService, config);

            final var export = service.exportPlan("s1", "", "my title", runtime(permissions, todoStore, null));

            assertNull(export.error());
            assertNotNull(export.path());
            assertTrue(Files.isRegularFile(export.path()));
            assertEquals(dir.resolve("my title.plan.md"), export.path());
            assertEquals("the plan content\n\n## Todos\n1", export.original());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ReloadPlanReportsUnchangedAndChanged {
        @Test
        void reloadPlanReportsUnchangedAndChanged(@TempDir final Path dir) throws Exception {
            final var planPath = dir.resolve("plan.md");
            Files.writeString(planPath, "same content");
            final var fileService = new PlanFileService(null) {
                @Override
                public PlanFileService.PlanFile parse(final String content) {
                    return new PlanFileService.PlanFile(content, List.of(new TodoItem("a task", "pending")));
                }

                @Override
                public String reload(
                        final String sessionId, final PlanFileService.PlanFile parsed, final TodoStore todoStore) {
                    return "plan reloaded with " + parsed.todos().size() + " tasks";
                }
            };
            final var service = service(fileService, cfg(dir.toString(), null));

            final var unchanged = service.reloadPlan(
                    "s1", planPath, "same content", runtime(new PermissionService(cfg(), (cfg()).resolveWorkingDir())));
            assertFalse(unchanged.changed());
            assertNull(unchanged.error());
            assertEquals("no changes in the plan (nothing to reload)", unchanged.message());

            Files.writeString(planPath, "edited content");
            final var changed = service.reloadPlan(
                    "s1", planPath, "same content", runtime(new PermissionService(cfg(), (cfg()).resolveWorkingDir())));
            assertTrue(changed.changed());
            assertNull(changed.error());
            assertEquals("plan reloaded with 1 tasks", changed.message());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ReloadPlanMissingFileReportsError {
        @Test
        void reloadPlanMissingFileReportsError() {
            final var service = service(new PlanFileService(null), cfg());

            final var reload = service.reloadPlan(
                    "s1",
                    Path.of("/no/such/plan.md"),
                    "original",
                    runtime(new PermissionService(cfg(), (cfg()).resolveWorkingDir())));

            assertNotNull(reload.error());
            assertTrue(reload.error().contains("plan file not found"));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class MemoryReadsProjectAndGlobal {
        @Test
        void memoryReadsProjectAndGlobal(@TempDir final Path storageHome, @TempDir final Path projectRoot)
                throws Exception {
            Files.createDirectories(storageHome.resolve("memory"));
            Files.createDirectories(projectRoot.resolve(".yca").resolve("memory"));
            Files.writeString(storageHome.resolve("memory").resolve("MEMORY.md"), "global notes");
            Files.writeString(projectRoot.resolve(".yca").resolve("memory").resolve("MEMORY.md"), "project notes");

            final var config = new CodingAgentConfiguration(
                    storageHome.toString(),
                    baseline.home(),
                    projectRoot.toString(),
                    baseline.projectDir(),
                    baseline.provider(),
                    baseline.model(),
                    baseline.llm(),
                    baseline.compaction(),
                    baseline.pruning(),
                    baseline.compression(),
                    baseline.memory(),
                    baseline.todos(),
                    baseline.optimize(),
                    baseline.permissions(),
                    baseline.sandbox(),
                    baseline.mcp(),
                    baseline.mavenRepositoryKnowledge(),
                    baseline.structuralCodeGraph(),
                    baseline.sessions(),
                    baseline.skills(),
                    baseline.agents(),
                    baseline.mode(),
                    baseline.httpClient(),
                    baseline.tools(),
                    baseline.subagents(),
                    baseline.schedulerThreads(),
                    baseline.metrics(),
                    baseline.maxLineLength(),
                    baseline.resumeAliases(),
                    baseline.slashcommands(),
                    baseline.prompt(),
                    baseline.labels(),
                    baseline.logging(),
                    baseline.hooks(),
                    baseline.notifications(),
                    baseline.secrets(),
                    null);
            final var memoryStore = new MemoryStore(config);
            final var service =
                    new AgentSessionService(null, null, null, null, memoryStore, null, null, null, config, null);

            final var memory = service.memory(config.resolveWorkingDir());

            assertEquals("global notes", memory.global());
            assertEquals("project notes", memory.project());
            assertEquals(storageHome.resolve("memory").resolve("MEMORY.md").toString(), memory.globalPath());
            assertEquals(
                    projectRoot
                            .resolve(".yca")
                            .resolve("memory")
                            .resolve("MEMORY.md")
                            .toString(),
                    memory.projectPath());
            assertTrue(memory.globalExists());
            assertTrue(memory.projectExists());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class MemoryAndStatsUseSelectedRoot {
        @Test
        void memoryAndStatsUseSelectedRoot(
                @TempDir final Path storageHome, @TempDir final Path configuredRoot, @TempDir final Path sessionRoot)
                throws Exception {
            final var config = new CodingAgentConfiguration(
                    storageHome.toString(),
                    baseline.home(),
                    configuredRoot.toString(),
                    baseline.projectDir(),
                    baseline.provider(),
                    baseline.model(),
                    baseline.llm(),
                    baseline.compaction(),
                    baseline.pruning(),
                    baseline.compression(),
                    baseline.memory(),
                    baseline.todos(),
                    baseline.optimize(),
                    baseline.permissions(),
                    baseline.sandbox(),
                    baseline.mcp(),
                    baseline.mavenRepositoryKnowledge(),
                    baseline.structuralCodeGraph(),
                    baseline.sessions(),
                    baseline.skills(),
                    baseline.agents(),
                    baseline.mode(),
                    baseline.httpClient(),
                    baseline.tools(),
                    baseline.subagents(),
                    baseline.schedulerThreads(),
                    baseline.metrics(),
                    baseline.maxLineLength(),
                    baseline.resumeAliases(),
                    baseline.slashcommands(),
                    baseline.prompt(),
                    baseline.labels(),
                    baseline.logging(),
                    baseline.hooks(),
                    baseline.notifications(),
                    baseline.secrets(),
                    null);
            final var store = new MemoryStore(config);
            Files.createDirectories(store.globalFile().getParent());
            Files.writeString(store.globalFile(), "- shared\n");
            final var configuredFile = MemoryStore.projectFile(configuredRoot);
            final var sessionFile = MemoryStore.projectFile(sessionRoot);
            Files.createDirectories(configuredFile.getParent());
            Files.createDirectories(sessionFile.getParent());
            Files.writeString(configuredFile, "- configured\n");
            Files.writeString(sessionFile, "- session one\n- session two\n");
            Files.writeString(configuredFile.resolveSibling("compaction.txt"), "timestamp=11\nbefore=100\nafter=30\n");
            Files.writeString(sessionFile.resolveSibling("compaction.txt"), "timestamp=22\nbefore=200\nafter=40\n");
            final var compactor = new MemoryCompactor(null, config, store, null, null);
            final var service =
                    new AgentSessionService(null, null, null, null, store, null, null, null, config, null, compactor);

            for (final var root : List.of(configuredRoot, sessionRoot)) {
                final var memory = service.memory(root);
                final var stats = service.memoryStats(root);
                assertEquals(Files.readString(MemoryStore.projectFile(root)), memory.project());
                assertEquals(MemoryStore.projectFile(root).toString(), memory.projectPath());
                assertTrue(memory.projectExists());
                assertEquals("- shared\n", memory.global());
                assertEquals(store.globalFile().toString(), memory.globalPath());
                assertTrue(memory.globalExists());
                assertEquals(memory.global().length(), stats.globalChars());
                assertEquals(1, stats.globalBullets());
                assertEquals(memory.project().length(), stats.projectChars());
                assertEquals(root.equals(configuredRoot) ? 1 : 2, stats.projectBullets());
                assertEquals(
                        root.equals(configuredRoot) ? 11 : 22,
                        stats.lastCompaction().timestamp());
            }
            assertEquals(service.memory(configuredRoot), service.memory(config.resolveWorkingDir()));
            assertThrows(NullPointerException.class, () -> service.memory(null));
            assertEquals(1, service.memoryStats(config.resolveWorkingDir()).projectBullets());
            assertThrows(NullPointerException.class, () -> service.memoryStats(null));
            assertEquals(
                    11, service.memoryStats(configuredRoot).lastCompaction().timestamp());
            final var missing = service.memory(sessionRoot.resolve("missing"));
            assertEquals("", missing.project());
            assertFalse(missing.projectExists());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class MemoryReadsExplicitRootWithoutConfiguredStoreFallback {
        @Test
        void memoryReadsExplicitRootWithoutConfiguredStoreFallback(@TempDir final Path root) throws Exception {
            final var file = MemoryStore.projectFile(root);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "- explicit\n");
            final var config = new CodingAgentConfiguration(
                    root.resolve("storage").toString(),
                    baseline.home(),
                    root.resolve("configured").toString(),
                    baseline.projectDir(),
                    baseline.provider(),
                    baseline.model(),
                    baseline.llm(),
                    baseline.compaction(),
                    baseline.pruning(),
                    baseline.compression(),
                    baseline.memory(),
                    baseline.todos(),
                    baseline.optimize(),
                    baseline.permissions(),
                    baseline.sandbox(),
                    baseline.mcp(),
                    baseline.mavenRepositoryKnowledge(),
                    baseline.structuralCodeGraph(),
                    baseline.sessions(),
                    baseline.skills(),
                    baseline.agents(),
                    baseline.mode(),
                    baseline.httpClient(),
                    baseline.tools(),
                    baseline.subagents(),
                    baseline.schedulerThreads(),
                    baseline.metrics(),
                    baseline.maxLineLength(),
                    baseline.resumeAliases(),
                    baseline.slashcommands(),
                    baseline.prompt(),
                    baseline.labels(),
                    baseline.logging(),
                    baseline.hooks(),
                    baseline.notifications(),
                    baseline.secrets(),
                    null);
            final var service = new AgentSessionService(
                    null, null, null, null, new MemoryStore(config), null, null, null, config, null);
            assertEquals("- explicit\n", service.memory(root).project());
            assertEquals(file.toString(), service.memory(root).projectPath());
            assertTrue(service.memory(root).projectExists());
            assertEquals(1, service.memoryStats(root).projectBullets());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ManualCompactionForwardsExplicitRoot {
        @Test
        void manualCompactionForwardsExplicitRoot(@TempDir final Path root) {
            final var targets = new ArrayList<Path>();
            final var compactor = new MemoryCompactor(null, null, null, null, null) {
                @Override
                public String compactNow(final Path target) {
                    targets.add(target);
                    return "selected root";
                }
            };
            final var service =
                    new AgentSessionService(null, null, null, null, null, null, null, null, null, null, compactor);
            assertEquals("selected root", service.compactMemory(root));
            assertEquals(List.of(root), targets);
            assertThrows(NullPointerException.class, () -> service.compactMemory(null));
            final var unavailable =
                    new AgentSessionService(null, null, null, null, null, null, null, null, null, null, null);
            assertEquals("memory compaction is not available", unavailable.compactMemory(root));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class NullRootRetainsServiceOverrides {
        @Test
        void nullRootRetainsServiceOverrides() {
            final var content = new AgentSessionService.MemoryContent("global", "project", "g", "p", true, true);
            final var stats = new AgentSessionService.MemoryStats(6, 0, 7, 0, -1, null);
            final var service =
                    new AgentSessionService(null, null, null, null, null, null, null, null, null, null, null) {
                        @Override
                        public MemoryContent memory(final Path root) {
                            return content;
                        }

                        @Override
                        public MemoryStats memoryStats(final Path root) {
                            return stats;
                        }

                        @Override
                        public String compactMemory(final Path root) {
                            return "override";
                        }
                    };
            assertEquals(content, service.memory(null));
            assertEquals(stats, service.memoryStats(null));
            assertEquals("override", service.compactMemory(null));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ContextResolvesToolNameFromAssistantCall {
        @Test
        void contextResolvesToolNameFromAssistantCall() {
            final var store = new SessionStore() {
                @Override
                public List<Message> load(final String id) {
                    return List.of(
                            Message.user("hi"),
                            Message.assistant(
                                    "reading",
                                    List.of(new ToolCall(
                                            "call-1",
                                            "function",
                                            new FunctionCall("read", "{\"path\":\"a.txt\"}"),
                                            null))),
                            Message.tool("call-1", "file body"));
                }
            };
            final var service = service(store);

            final var dump = service.context(
                    "s1", runtime(new PermissionService(cfg(), (cfg()).resolveWorkingDir()), null, promptBuilder()));

            assertEquals(3, dump.messages().size());
            assertNull(dump.messages().get(0).toolName());
            assertNull(dump.messages().get(1).toolName());
            assertEquals("read", dump.messages().get(2).toolName());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ContextKeepsStoredToolName {
        @Test
        void contextKeepsStoredToolName() {
            final var store = new SessionStore() {
                @Override
                public List<Message> load(final String id) {
                    return List.of(
                            Message.assistant(
                                    "reading",
                                    List.of(new ToolCall("call-1", "function", new FunctionCall("grep", "{}"), null))),
                            Message.toolResult("call-1", "1 match(es)", "grep"));
                }
            };
            final var service = service(store);

            final var dump = service.context(
                    "s1", runtime(new PermissionService(cfg(), (cfg()).resolveWorkingDir()), null, promptBuilder()));

            assertEquals("grep", dump.messages().get(1).toolName());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class CompactReturnsNullForEmptyHistory {
        @Test
        void compactReturnsNullForEmptyHistory() {
            final var store = new SessionStore() {
                @Override
                public List<Message> load(final String id) {
                    return List.of();
                }
            };
            final var service = service(store);

            final var result = service.compact(
                    "s1", runtime(new PermissionService(cfg(), (cfg()).resolveWorkingDir()), null, null));
            assertNotNull(result);
            assertFalse(result.applied());
            assertEquals("nothing to compact", result.message());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class CompactPropagatesForceUrgentAndKeepLastToTheContextManager {
        @Test
        void compactPropagatesForceUrgentAndKeepLastToTheContextManager() {
            final var captured = new ArrayList<Object[]>();
            final var contextManager = new ContextManager() {
                @Override
                public CompactionResult compact(
                        final List<Message> messages,
                        final boolean force,
                        final String focus,
                        final int keepLastOverride,
                        final boolean urgent,
                        final BooleanSupplier cancelled,
                        final ChatSettings settings,
                        final CompactionCoordinator.CompactionProgress progress) {
                    captured.add(new Object[] {force, focus, keepLastOverride, urgent});
                    return new CompactionResult(messages, CompactionResult.Mode.SUMMARY, "");
                }
            };
            final var store = new SessionStore() {
                @Override
                public List<Message> load(final String id) {
                    return List.of(Message.user("hi"));
                }
            };
            final var service = service(store);
            final var runtime = new SessionRuntime(
                    new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                        @Override
                        public ChatSettings chatSettings() {
                            return ChatSettings.EMPTY;
                        }
                    },
                    new PermissionService(cfg(), (cfg()).resolveWorkingDir()),
                    null,
                    null,
                    null,
                    null,
                    contextManager,
                    promptBuilder(),
                    testWorkdir,
                    new DisabledSandbox(testWorkdir));
            final var result = service.compact("s1", true, true, 7, runtime);
            assertTrue(result.applied());
            assertEquals(1, captured.size());
            assertEquals(Boolean.TRUE, captured.get(0)[0], "force must reach the context manager");
            assertNull(captured.get(0)[1], "no focus hint from the slash command");
            assertEquals(7, captured.get(0)[2], "keep=<n> must reach the context manager");
            assertEquals(Boolean.TRUE, captured.get(0)[3], "urgent must reach the context manager");
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class SessionInfoReportsIdAndPath {
        @Test
        void sessionInfoReportsIdAndPath() {
            final var service = service(new SessionStore() {
                @Override
                public Path path(final String id) {
                    return Path.of("/sessions/" + id + ".jsonl");
                }
            });

            final var info = service.sessionInfo("s1");
            assertEquals("s1", info.id());
            assertEquals(Path.of("/sessions/s1.jsonl"), info.path());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class FlushRewritesLoadedTranscript {
        @Test
        void flushRewritesLoadedTranscript() {
            final var rewritten = new boolean[1];
            final var store = new SessionStore() {
                @Override
                public List<Message> load(final String id) {
                    return List.of(Message.user("hi"));
                }

                @Override
                public void replace(final String id, final List<Message> messages) {
                    rewritten[0] = true;
                }
            };
            final var service = service(store);

            assertEquals("session flushed", service.flush("s1"));
            assertTrue(rewritten[0]);
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class FlushReportsNothingForEmptyHistory {
        @Test
        void flushReportsNothingForEmptyHistory() {
            final var service = service(new SessionStore() {
                @Override
                public List<Message> load(final String id) {
                    return List.of();
                }
            });

            assertEquals("nothing to flush", service.flush("s1"));
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class HousekeepingDelegatesToStoreWithEmptyDeletion {
        @Test
        void housekeepingDelegatesToStoreWithEmptyDeletion() {
            final var store = new SessionStore() {
                @Override
                public SessionStore.HousekeepingReport housekeeping(final boolean deleteEmpty) {
                    return new SessionStore.HousekeepingReport(2, 3, 4);
                }
            };
            final var service = service(store);

            final var report = service.housekeeping();
            assertEquals(2, report.compressed());
            assertEquals(3, report.deleted());
            assertEquals(4, report.kept());
        }
    }

    @Nested
    @FusionSupport(modules = SessionModule.class)
    class ResyncGraphPropagatesFailure {
        @Test
        void resyncGraphPropagatesFailure() {
            final var graph = new StructuralCodeGraph() {
                @Override
                public String resync(final Path root) {
                    throw new IllegalStateException("boom");
                }
            };
            final var service = service(graph);

            assertThrows(RuntimeException.class, () -> service.resyncGraph(testWorkdir));
        }
    }

    private AgentSessionService service(final PermissionService permissions) {
        return new AgentSessionService(null, null, null, null, null, null, null, null, cfg(), null);
    }

    private AgentSessionService service(final McpManager mcpManager) {
        return new AgentSessionService(null, mcpManager, null, null, null, null, null, null, cfg(), null);
    }

    private AgentSessionService service(final ChatClient chatClient) {
        return new AgentSessionService(chatClient, null, null, null, null, null, null, null, cfg(), null);
    }

    private AgentSessionService service(final AgentProvider agentProvider, final CodingAgentConfiguration config) {
        return new AgentSessionService(null, null, agentProvider, null, null, null, null, null, config, null);
    }

    private AgentSessionService service(final SessionStore store) {
        return new AgentSessionService(null, null, null, store, null, null, null, null, cfg(), null);
    }

    private AgentSessionService service(final StructuralCodeGraph graph) {
        return new AgentSessionService(null, null, null, null, null, graph, null, null, cfg(), null);
    }

    private AgentSessionService service(final PlanFileService planFileService, final CodingAgentConfiguration config) {
        return new AgentSessionService(null, null, null, null, null, null, planFileService, null, config, null);
    }

    private AgentSessionService service(
            final PlanTodoSeeder seeder, final PlanFileService planFileService, final CodingAgentConfiguration config) {
        return new AgentSessionService(null, null, null, null, null, null, planFileService, seeder, config, null);
    }

    private static SessionRuntime runtime(final PermissionService permissions) {
        return new SessionRuntime(
                null, permissions, null, null, null, null, null, null, testWorkdir, new DisabledSandbox(testWorkdir));
    }

    private static SessionRuntime runtime(
            final PermissionService permissions, final TodoStore todos, final SystemPromptBuilder promptBuilder) {
        return new SessionRuntime(
                null,
                permissions,
                null,
                null,
                todos,
                null,
                null,
                promptBuilder,
                testWorkdir,
                new DisabledSandbox(testWorkdir));
    }

    private static SystemPromptBuilder promptBuilder() {
        return new SystemPromptBuilder(testWorkdir) {
            @Override
            public String build() {
                return "<system>";
            }
        };
    }

    private CodingAgentConfiguration cfg() {
        return config(null, null);
    }

    private CodingAgentConfiguration cfg(final String storageHome, final AgentMode mode) {
        return config(storageHome, mode);
    }

    private CodingAgentConfiguration config(final String storageHome, final AgentMode mode) {
        return new CodingAgentConfiguration(
                storageHome == null ? baseline.storageHome() : storageHome,
                baseline.home(),
                baseline.workingDir(),
                baseline.projectDir(),
                baseline.provider(),
                baseline.model(),
                baseline.llm(),
                baseline.compaction(),
                baseline.pruning(),
                baseline.compression(),
                baseline.memory(),
                baseline.todos(),
                baseline.optimize(),
                baseline.permissions(),
                baseline.sandbox(),
                baseline.mcp(),
                baseline.mavenRepositoryKnowledge(),
                baseline.structuralCodeGraph(),
                baseline.sessions(),
                baseline.skills(),
                baseline.agents(),
                mode == null ? baseline.mode() : mode,
                baseline.httpClient(),
                baseline.tools(),
                baseline.subagents(),
                baseline.schedulerThreads(),
                baseline.metrics(),
                baseline.maxLineLength(),
                baseline.resumeAliases(),
                baseline.slashcommands(),
                baseline.prompt(),
                baseline.labels(),
                baseline.logging(),
                baseline.hooks(),
                baseline.notifications(),
                baseline.secrets(),
                null);
    }

    private CodingAgentConfiguration agentsConfig(final String... names) {
        final var agents = Arrays.stream(names)
                .map(name -> new AgentConfiguration(name, "<prompt>"))
                .toList();
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                baseline.workingDir(),
                baseline.projectDir(),
                baseline.provider(),
                baseline.model(),
                baseline.llm(),
                baseline.compaction(),
                baseline.pruning(),
                baseline.compression(),
                baseline.memory(),
                baseline.todos(),
                baseline.optimize(),
                baseline.permissions(),
                baseline.sandbox(),
                baseline.mcp(),
                baseline.mavenRepositoryKnowledge(),
                baseline.structuralCodeGraph(),
                baseline.sessions(),
                baseline.skills(),
                agents,
                baseline.mode(),
                baseline.httpClient(),
                baseline.tools(),
                baseline.subagents(),
                baseline.schedulerThreads(),
                baseline.metrics(),
                baseline.maxLineLength(),
                baseline.resumeAliases(),
                baseline.slashcommands(),
                baseline.prompt(),
                baseline.labels(),
                baseline.logging(),
                baseline.hooks(),
                baseline.notifications(),
                baseline.secrets(),
                null);
    }

    private AgentProvider agentProvider(final String... names) {
        return new AgentProvider(agentsConfig(names));
    }

    private static ChatClient chatClient() {
        return new ChatClient() {};
    }

    private static EmbeddedTool tool(final String name, final String description) {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description(final Path workdir) {
                return description;
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.READ;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                return ToolResult.ok(null);
            }
        };
    }

    public static class SessionModule extends ConfigurationTestModule {
        public SessionModule() {
            super(Map.of("yca.storageHome", testWorkdir.resolve("storage").toString()));
        }
    }
}
