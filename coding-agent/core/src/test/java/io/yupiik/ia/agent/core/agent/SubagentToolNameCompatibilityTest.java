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
package io.yupiik.ia.agent.core.agent;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.InMemoryTodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
@Timeout(20)
class SubagentToolNameCompatibilityTest {
    @TempDir
    private static Path testWorkdir;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Nested
    @FusionSupport(modules = ConfiguredModule.class)
    class Configured {
        @TempDir
        static Path storageHome;

        @Test
        void acceptsBarePrefixedAndMixedNames(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var tools = List.of(tool("read", true), tool("bash", true));
            assertVisible(Set.of("read"), run(configuration, Set.of("read"), null, null, tools));
            assertVisible(Set.of("read"), run(configuration, Set.of("functions.read"), null, null, tools));
            assertVisible(
                    Set.of("read", "bash"),
                    run(configuration, Set.of("read", "functions.read", "functions.bash"), null, null, tools));
        }

        @Test
        void exactRegisteredNamesTakePrecedenceEvenWhenUnusable(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            assertVisible(
                    Set.of("functions.read"),
                    run(
                            configuration,
                            Set.of("functions.read"),
                            null,
                            null,
                            List.of(tool("read", true), tool("functions.read", true))));
            assertRejected(
                    "functions.read",
                    run(
                            configuration,
                            Set.of("functions.read"),
                            null,
                            null,
                            List.of(tool("read", true), tool("functions.read", false))));
        }

        @Test
        void rejectsUnknownUnusableAndUnsupportedAliases(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var tools = List.of(tool("read", true), tool("bash", false));
            for (final var name :
                    List.of("functions.missing", "functions.bash", "other.read", "functions.functions.read")) {
                assertRejected(name, run(configuration, Set.of(name), null, null, tools));
            }
            // A repeated prefix is not an alias even when stripping once would find a registered name.
            assertRejected(
                    "functions.functions.read",
                    run(
                            configuration,
                            Set.of("functions.functions.read"),
                            null,
                            null,
                            List.of(tool("functions.read", true))));
        }

        @Test
        void omittedAndEmptyAllowlistsRemainDistinct(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var tools = List.of(tool("read", true), tool("bash", true));
            assertVisible(Set.of("read", "bash"), run(configuration, null, null, null, tools));
            assertVisible(Set.of(), run(configuration, Set.of(), null, null, tools));
            assertVisible(Set.of(), run(configuration, Set.of("functions.read"), Set.of(), null, tools));
            assertVisible(Set.of(), run(configuration, Set.of("functions.read"), null, Set.of(), tools));
        }

        @Test
        void normalizedRequestsCannotWidenParentOrProfileCeilings(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var tools = List.of(tool("read", true), tool("bash", true));
            final var requested = Set.of("functions.read", "functions.bash");
            assertVisible(Set.of("read"), run(configuration, requested, Set.of("read"), null, tools));
            assertVisible(Set.of("bash"), run(configuration, requested, null, Set.of("bash"), tools));
            assertVisible(Set.of(), run(configuration, requested, Set.of("read"), Set.of("bash"), tools));
            assertVisible(Set.of("read"), run(configuration, requested, Set.of("read", "bash"), Set.of("read"), tools));
        }

        @Test
        void doesNotNormalizeConfiguredOrInheritedCeilings(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var tools = List.of(tool("read", true));
            assertRejected("functions.read", run(configuration, Set.of("read"), Set.of("functions.read"), null, tools));
            assertRejected("functions.read", run(configuration, Set.of("read"), null, Set.of("functions.read"), tools));
        }
    }

    private void assertVisible(final Set<String> expected, final RunOutcome outcome) {
        assertTrue(outcome.result().success(), outcome.result().error());
        assertEquals(1, outcome.requests().size(), "the child must reach the model");
        assertEquals(
                expected,
                outcome.requests().getFirst().tools().stream()
                        .map(t -> t.function().name())
                        .collect(toSet()));
    }

    private void assertRejected(final String name, final RunOutcome outcome) {
        assertFalse(outcome.result().success());
        assertEquals(
                "Requested tools are not available: " + name, outcome.result().error());
        assertTrue(outcome.requests().isEmpty(), "invalid tools must fail before calling the model");
    }

    private RunOutcome run(
            final CodingAgentConfiguration configuration,
            final Set<String> requested,
            final Set<String> parent,
            final Set<String> profileTools,
            final List<EmbeddedTool> tools)
            throws Exception {
        final var providers = new ProviderManager(configuration, mapper);
        final var registry = new SubagentRegistry(configuration, providers);
        final var profile = profileTools == null
                ? null
                : new AgentConfiguration("test-profile", "Test instructions", null, null, List.copyOf(profileTools));
        final var profiles = new AgentProvider(configuration) {
            @Override
            public List<AgentConfiguration> agents() {
                return profile == null ? List.of() : List.of(profile);
            }
        };
        final var requests = new ArrayList<ChatCompletionRequest>();
        final var client = new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled) {
                requests.add(request);
                return new Message("assistant", "done", null, null, null, null);
            }
        };
        final var toolRegistry = new ToolRegistry(null) {
            @Override
            public List<EmbeddedTool> tools() {
                return tools;
            }
        };
        final var emitter = new Emitter() {
            @Override
            public <T> void emit(final T event) {}
        };
        final var graph = new StructuralCodeGraph() {
            @Override
            public void ensureReadyAsync(final Path root) {}
        };
        final var clock = Clock.systemUTC();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var runner = new SubagentRunner(
                    client,
                    toolRegistry,
                    null,
                    configuration,
                    null,
                    null,
                    emitter,
                    mapper,
                    graph,
                    providers,
                    null,
                    profiles,
                    new TodoPromptResolver(),
                    null,
                    clock,
                    executor,
                    registry,
                    new PrecisionDurationMarkerProvider(),
                    new BackoffSleeper(),
                    null,
                    null);
            final var state = registry.start(new AgentRunSpec(
                            "parent-" + UUID.randomUUID(),
                            "task",
                            null,
                            profile == null ? null : profile.name(),
                            null,
                            1,
                            requested,
                            parent))
                    .orElseThrow();
            runner.runAsync(
                    state,
                    new InMemorySessionStore(clock),
                    new SnapshotStore(configuration),
                    new InMemoryTodoStore(),
                    ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)),
                    () -> false);
            state.awaitResult();
            return new RunOutcome(state.result(), List.copyOf(requests));
        }
    }

    private EmbeddedTool tool(final String name, final boolean usable) {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description(final Path workdir) {
                return "Test tool";
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.READ;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            @Override
            public boolean usable(final Path workdir) {
                return usable;
            }

            @Override
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                return ToolResult.ok("done");
            }
        };
    }

    private record RunOutcome(SubagentRegistry.SubagentResult result, List<ChatCompletionRequest> requests) {}

    public static class ConfiguredModule extends ConfigurationTestModule {
        public ConfiguredModule() {
            super(values());
        }

        private static Map<String, String> values() {
            return Map.of(
                    "yca.storageHome", Configured.storageHome.toString(),
                    "yca.subagents.enabled", "true",
                    "yca.subagents.maxConcurrent", "2",
                    "yca.llm.maxConcurrent", "4");
        }
    }
}
