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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class WriteTodoToolTest {
    private CodingAgentConfiguration configuration;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
        storageHome = Path.of(configuration.storageHome());
    }

    @TempDir
    private static Path testWorkdir;

    private Path storageHome;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private TodoStore todoStore() {
        return new TodoStore(configuration, mapper);
    }

    private PermissionService permissions(final AgentMode mode) {
        final var service = new PermissionService(configuration, (configuration).resolveWorkingDir());
        service.setMode(mode);
        return service;
    }

    /** The tool is stateless: the session's todo list and permissions arrive through the context. */
    private ToolContext context(final TodoStore todos, final PermissionService permissions) {
        return new ToolContext(
                "s1",
                null,
                null,
                permissions,
                null,
                todos,
                null,
                null,
                null,
                Cancellations.NEVER,
                null,
                null,
                testWorkdir,
                new DisabledSandbox(testWorkdir));
    }

    @Nested
    @FusionSupport(modules = PlanModeNormalizesNewlyCompletedTodoScenarioModule.class)
    class PlanModeNormalizesNewlyCompletedTodoScenario {
        @TempDir
        static Path directory;

        @Test
        void planModeNormalizesNewlyCompletedTodo() {
            final var store = todoStore();
            final var tool = new WriteTodoTool();
            final var result = tool.execute(
                    Map.of(
                            "todos",
                            List.of(
                                    Map.of("content", "step 1", "status", "completed"),
                                    Map.of("content", "step 2", "status", "pending"))),
                    context(store, permissions(AgentMode.PLAN)));
            assertTrue(result.success(), result.output());
            assertTrue(
                    store.render().contains("[ ] step 1"),
                    "completed new item must be normalized to pending: " + store.render());
            assertTrue(store.render().contains("[ ] step 2"), store.render());
            assertTrue(
                    result.output().contains("kept as pending"),
                    "the tool must report the plan-mode normalization: " + result.output());
        }
    }

    @Nested
    @FusionSupport(modules = PlanModeNormalizesNewlyInProgressTodoScenarioModule.class)
    class PlanModeNormalizesNewlyInProgressTodoScenario {
        @TempDir
        static Path directory;

        @Test
        void planModeNormalizesNewlyInProgressTodo() {
            final var store = todoStore();
            final var tool = new WriteTodoTool();
            final var result = tool.execute(
                    Map.of("todos", List.of(Map.of("content", "step 1", "status", "in_progress"))),
                    context(store, permissions(AgentMode.PLAN)));
            assertTrue(result.success(), result.output());
            assertTrue(
                    store.render().contains("[ ] step 1"),
                    "in_progress new item must be normalized to pending: " + store.render());
            assertTrue(result.output().contains("kept as pending"), result.output());
        }
    }

    @Nested
    @FusionSupport(modules = PlanModeAllowsAllPendingPlanScenarioModule.class)
    class PlanModeAllowsAllPendingPlanScenario {
        @TempDir
        static Path directory;

        @Test
        void planModeAllowsAllPendingPlan() {
            final var store = todoStore();
            final var tool = new WriteTodoTool();
            final var result = tool.execute(
                    Map.of(
                            "todos",
                            List.of(
                                    Map.of("content", "step 1", "status", "pending"),
                                    Map.of("content", "step 2", "status", "pending"))),
                    context(store, permissions(AgentMode.PLAN)));
            assertTrue(result.success(), result.output());
            assertTrue(store.render().contains("[ ] step 1"), store.render());
        }
    }

    @Nested
    @FusionSupport(modules = PlanModeToleratesItemsAlreadyAdvancedInBuildModeScenarioModule.class)
    class PlanModeToleratesItemsAlreadyAdvancedInBuildModeScenario {
        @TempDir
        static Path directory;

        @Test
        void planModeToleratesItemsAlreadyAdvancedInBuildMode() {
            final var store = todoStore();
            store.replace(List.of(new TodoItem("step 1", "completed")));
            final var tool = new WriteTodoTool();
            final var result = tool.execute(
                    Map.of(
                            "todos",
                            List.of(
                                    Map.of("content", "step 1", "status", "completed"),
                                    Map.of("content", "step 2", "status", "pending"))),
                    context(store, permissions(AgentMode.PLAN)));
            assertTrue(result.success(), result.output());
            assertTrue(store.render().contains("[x] step 1"), store.render());
        }
    }

    @Nested
    @FusionSupport(modules = PlanModeKeepsAdvancedItemWhenReemissionContentDriftsScenarioModule.class)
    class PlanModeKeepsAdvancedItemWhenReemissionContentDriftsScenario {
        @TempDir
        static Path directory;

        @Test
        void planModeKeepsAdvancedItemWhenReemissionContentDrifts() {
            // regression: a legitimately-advanced item must survive an otherwise-identical re-emission with
            // cosmetic text drift (trailing whitespace, capitalisation), instead of being thrown back to pending
            // and recreating the "progress then reverts" loop.
            final var store = todoStore();
            store.replace(List.of(new TodoItem("step 1", "completed")));
            final var tool = new WriteTodoTool();
            final var result = tool.execute(
                    Map.of("todos", List.of(Map.of("content", "  Step 1 ", "status", "completed"))),
                    context(store, permissions(AgentMode.PLAN)));
            assertTrue(result.success(), result.output());
            assertTrue(
                    store.render().startsWith("[x] "),
                    "drifted re-emission must keep the advanced status marker: " + store.render());
            assertTrue(
                    store.render().contains("Step 1"),
                    "the drifted content itself must be preserved verbatim: " + store.render());
        }
    }

    @Nested
    @FusionSupport(modules = PlanModeRoundTripDoesNotFlipAnAlreadyAdvancedItemBackToPendingScenarioModule.class)
    class PlanModeRoundTripDoesNotFlipAnAlreadyAdvancedItemBackToPendingScenario {
        @TempDir
        static Path directory;

        @Test
        void planModeRoundTripDoesNotFlipAnAlreadyAdvancedItemBackToPending() {
            // the full loop: seed advanced as a build-mode carry-over, then re-emit the same state through a
            // plan-mode write - the item must stay advanced and the tool must not report a re-normalization.
            final var store = todoStore();
            store.replace(List.of(new TodoItem("step 1", "completed"), new TodoItem("step 2", "pending")));
            final var tool = new WriteTodoTool();
            final var again = tool.execute(
                    Map.of(
                            "todos",
                            List.of(
                                    Map.of("content", "step 1", "status", "completed"),
                                    Map.of("content", "step 2", "status", "pending"))),
                    context(store, permissions(AgentMode.PLAN)));
            assertTrue(again.success(), again.output());
            assertTrue(
                    store.render().contains("[x] step 1"), "round-trip must keep the advanced item: " + store.render());
            assertFalse(
                    again.output().contains("kept as pending"),
                    "no re-normalization after a stable re-emission: " + again.output());
        }
    }

    @Nested
    @FusionSupport(modules = BuildModeAcceptsCompletedTodosScenarioModule.class)
    class BuildModeAcceptsCompletedTodosScenario {
        @TempDir
        static Path directory;

        @Test
        void buildModeAcceptsCompletedTodos() {
            final var store = todoStore();
            final var tool = new WriteTodoTool();
            final var result = tool.execute(
                    Map.of("todos", List.of(Map.of("content", "step 1", "status", "completed"))),
                    context(store, permissions(AgentMode.BUILD)));
            assertTrue(result.success(), result.output());
            assertTrue(store.render().contains("[x] step 1"), store.render());
        }
    }

    @Nested
    @FusionSupport(modules = BuildModeAcceptsCancelledTodosScenarioModule.class)
    class BuildModeAcceptsCancelledTodosScenario {
        @TempDir
        static Path directory;

        @Test
        void buildModeAcceptsCancelledTodos() {
            final var store = todoStore();
            final var tool = new WriteTodoTool();
            final var result = tool.execute(
                    Map.of("todos", List.of(Map.of("content", "step 1", "status", "cancelled"))),
                    context(store, permissions(AgentMode.BUILD)));
            assertTrue(result.success(), result.output());
            assertTrue(store.render().contains("[-] step 1"), store.render());
        }
    }

    @Nested
    @FusionSupport(modules = PlanModeKeepsNewlyCancelledTodoAsScopeDecisionScenarioModule.class)
    class PlanModeKeepsNewlyCancelledTodoAsScopeDecisionScenario {
        @TempDir
        static Path directory;

        @Test
        void planModeKeepsNewlyCancelledTodoAsScopeDecision() {
            final var store = todoStore();
            final var tool = new WriteTodoTool();
            final var result = tool.execute(
                    Map.of(
                            "todos",
                            List.of(
                                    Map.of("content", "step 1", "status", "cancelled"),
                                    Map.of("content", "step 2", "status", "pending"))),
                    context(store, permissions(AgentMode.PLAN)));
            assertTrue(result.success(), result.output());
            assertTrue(
                    store.render().contains("[-] step 1"),
                    "cancelled new item is kept as a scope decision, not normalized: " + store.render());
            assertTrue(store.render().contains("[ ] step 2"), store.render());
        }
    }

    @Nested
    @FusionSupport(modules = PlanModeToleratesItemsAlreadyCancelledInBuildModeScenarioModule.class)
    class PlanModeToleratesItemsAlreadyCancelledInBuildModeScenario {
        @TempDir
        static Path directory;

        @Test
        void planModeToleratesItemsAlreadyCancelledInBuildMode() {
            final var store = todoStore();
            store.replace(List.of(new TodoItem("step 1", "cancelled")));
            final var tool = new WriteTodoTool();
            final var result = tool.execute(
                    Map.of(
                            "todos",
                            List.of(
                                    Map.of("content", "step 1", "status", "cancelled"),
                                    Map.of("content", "step 2", "status", "pending"))),
                    context(store, permissions(AgentMode.PLAN)));
            assertTrue(result.success(), result.output());
            assertTrue(store.render().contains("[-] step 1"), store.render());
        }
    }

    @Nested
    @FusionSupport(modules = RejectsStringTodosWithCleanErrorScenarioModule.class)
    class RejectsStringTodosWithCleanErrorScenario {
        @TempDir
        static Path directory;

        @Test
        void rejectsStringTodosWithCleanError() {
            final var store = todoStore();
            final var tool = new WriteTodoTool();
            final var result =
                    tool.execute(Map.of("todos", "not an array"), context(store, permissions(AgentMode.BUILD)));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains("got String"), result.output());
        }
    }

    @Nested
    @FusionSupport(modules = DescriptionCarriesPlanModeRuleAndParallelBuildStatusesScenarioModule.class)
    class DescriptionCarriesPlanModeRuleAndParallelBuildStatusesScenario {
        @TempDir
        static Path directory;

        @Test
        void descriptionCarriesPlanModeRuleAndParallelBuildStatuses() {
            final var tool = new WriteTodoTool();
            final var description = tool.description(storageHome);
            assertTrue(
                    description.contains("In PLAN mode"),
                    "the description must carry the plan-mode contract (plan is the default mode): " + description);
            assertTrue(
                    description.contains("(the list IS the plan)"),
                    "the description must state that the plan-mode list is the plan: " + description);
            assertTrue(
                    description.contains("new items stay 'pending'"),
                    "the description must state the all-pending plan rule: " + description);
            assertTrue(
                    description.contains("mark in_progress as you start"),
                    "the description must allow in_progress items in build mode: " + description);
            assertFalse(
                    description.contains("exactly one in_progress"),
                    "the description must not carry the relaxed single-in_progress rule anymore: " + description);
        }
    }

    @Nested
    @FusionSupport(modules = SchemaStatusIsAnEnumWithPlanHintScenarioModule.class)
    class SchemaStatusIsAnEnumWithPlanHintScenario {
        @TempDir
        static Path directory;

        @Test
        void schemaStatusIsAnEnumWithPlanHint() {
            final var tool = new WriteTodoTool();
            @SuppressWarnings("unchecked")
            final var todos = (Map<String, Object>) tool.schema().get("properties");
            @SuppressWarnings("unchecked")
            final var items = (Map<String, Object>) ((Map<String, Object>) todos.get("todos")).get("items");
            @SuppressWarnings("unchecked")
            final var status = (Map<String, Object>) ((Map<String, Object>) items.get("properties")).get("status");
            assertEquals("string", status.get("type"));
            assertEquals(List.of("pending", "in_progress", "completed", "cancelled"), status.get("enum"));
            assertTrue(
                    String.valueOf(status.get("description")).contains("PLAN mode new items"),
                    "the status schema must carry the plan-mode hint");
        }
    }

    @Nested
    @FusionSupport(modules = WithoutSessionContextTheToolFailsCleanlyScenarioModule.class)
    class WithoutSessionContextTheToolFailsCleanlyScenario {
        @TempDir
        static Path directory;

        @Test
        void withoutSessionContextTheToolFailsCleanly() {
            final var tool = new WriteTodoTool();
            final var result = tool.execute(
                    Map.of("todos", List.of(Map.of("content", "step 1", "status", "pending"))),
                    ToolContext.empty(storageHome, new DisabledSandbox(storageHome)));
            assertFalse(result.success(), result.output());
            assertTrue(result.output().contains("session"), result.output());
        }
    }

    public static class PlanModeNormalizesNewlyCompletedTodoScenarioModule extends ConfigurationTestModule {
        public PlanModeNormalizesNewlyCompletedTodoScenarioModule() {
            super(Map.of("yca.storageHome", PlanModeNormalizesNewlyCompletedTodoScenario.directory.toString()));
        }
    }

    public static class PlanModeNormalizesNewlyInProgressTodoScenarioModule extends ConfigurationTestModule {
        public PlanModeNormalizesNewlyInProgressTodoScenarioModule() {
            super(Map.of("yca.storageHome", PlanModeNormalizesNewlyInProgressTodoScenario.directory.toString()));
        }
    }

    public static class PlanModeAllowsAllPendingPlanScenarioModule extends ConfigurationTestModule {
        public PlanModeAllowsAllPendingPlanScenarioModule() {
            super(Map.of("yca.storageHome", PlanModeAllowsAllPendingPlanScenario.directory.toString()));
        }
    }

    public static class PlanModeToleratesItemsAlreadyAdvancedInBuildModeScenarioModule extends ConfigurationTestModule {
        public PlanModeToleratesItemsAlreadyAdvancedInBuildModeScenarioModule() {
            super(Map.of(
                    "yca.storageHome", PlanModeToleratesItemsAlreadyAdvancedInBuildModeScenario.directory.toString()));
        }
    }

    public static class PlanModeKeepsAdvancedItemWhenReemissionContentDriftsScenarioModule
            extends ConfigurationTestModule {
        public PlanModeKeepsAdvancedItemWhenReemissionContentDriftsScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    PlanModeKeepsAdvancedItemWhenReemissionContentDriftsScenario.directory.toString()));
        }
    }

    public static class PlanModeRoundTripDoesNotFlipAnAlreadyAdvancedItemBackToPendingScenarioModule
            extends ConfigurationTestModule {
        public PlanModeRoundTripDoesNotFlipAnAlreadyAdvancedItemBackToPendingScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    PlanModeRoundTripDoesNotFlipAnAlreadyAdvancedItemBackToPendingScenario.directory.toString()));
        }
    }

    public static class BuildModeAcceptsCompletedTodosScenarioModule extends ConfigurationTestModule {
        public BuildModeAcceptsCompletedTodosScenarioModule() {
            super(Map.of("yca.storageHome", BuildModeAcceptsCompletedTodosScenario.directory.toString()));
        }
    }

    public static class BuildModeAcceptsCancelledTodosScenarioModule extends ConfigurationTestModule {
        public BuildModeAcceptsCancelledTodosScenarioModule() {
            super(Map.of("yca.storageHome", BuildModeAcceptsCancelledTodosScenario.directory.toString()));
        }
    }

    public static class PlanModeKeepsNewlyCancelledTodoAsScopeDecisionScenarioModule extends ConfigurationTestModule {
        public PlanModeKeepsNewlyCancelledTodoAsScopeDecisionScenarioModule() {
            super(Map.of(
                    "yca.storageHome", PlanModeKeepsNewlyCancelledTodoAsScopeDecisionScenario.directory.toString()));
        }
    }

    public static class PlanModeToleratesItemsAlreadyCancelledInBuildModeScenarioModule
            extends ConfigurationTestModule {
        public PlanModeToleratesItemsAlreadyCancelledInBuildModeScenarioModule() {
            super(Map.of(
                    "yca.storageHome", PlanModeToleratesItemsAlreadyCancelledInBuildModeScenario.directory.toString()));
        }
    }

    public static class RejectsStringTodosWithCleanErrorScenarioModule extends ConfigurationTestModule {
        public RejectsStringTodosWithCleanErrorScenarioModule() {
            super(Map.of("yca.storageHome", RejectsStringTodosWithCleanErrorScenario.directory.toString()));
        }
    }

    public static class DescriptionCarriesPlanModeRuleAndParallelBuildStatusesScenarioModule
            extends ConfigurationTestModule {
        public DescriptionCarriesPlanModeRuleAndParallelBuildStatusesScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    DescriptionCarriesPlanModeRuleAndParallelBuildStatusesScenario.directory.toString()));
        }
    }

    public static class SchemaStatusIsAnEnumWithPlanHintScenarioModule extends ConfigurationTestModule {
        public SchemaStatusIsAnEnumWithPlanHintScenarioModule() {
            super(Map.of("yca.storageHome", SchemaStatusIsAnEnumWithPlanHintScenario.directory.toString()));
        }
    }

    public static class WithoutSessionContextTheToolFailsCleanlyScenarioModule extends ConfigurationTestModule {
        public WithoutSessionContextTheToolFailsCleanlyScenarioModule() {
            super(Map.of("yca.storageHome", WithoutSessionContextTheToolFailsCleanlyScenario.directory.toString()));
        }
    }
}
