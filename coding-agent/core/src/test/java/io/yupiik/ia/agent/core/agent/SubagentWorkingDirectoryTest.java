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

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.events.ToolUsePreEvent;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.InMemoryTodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.tools.builtin.BashTool;
import io.yupiik.ia.agent.core.tools.builtin.ReadFileTool;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent;
import java.nio.file.Files;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
@Timeout(30)
class SubagentWorkingDirectoryTest {
    @TempDir
    private static Path testWorkdir;

    @Nested
    @FusionSupport(modules = WorkingDirectoryModule.class)
    class Configured {
        @TempDir
        static Path tmp;

        @Test
        void childrenInheritSessionRootsWithoutChangingSharedServices(
                final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws Exception {
            Assumptions.assumeTrue(
                    !System.getProperty("os.name", "").toLowerCase().contains("win"));
            final var first = Files.createDirectories(tmp.resolve("first"));
            final var second = Files.createDirectories(tmp.resolve("second"));
            for (final var root : List.of(first, second)) {
                final var name = root.getFileName().toString();
                Files.writeString(root.resolve("AGENTS.md"), "Instructions-only-for-" + name);
                Files.writeString(root.resolve("sentinel.txt"), "File-only-for-" + name);
                final var memory = MemoryStore.projectFile(root);
                Files.createDirectories(memory.getParent());
                Files.writeString(memory, MemoryStore.HEADER + "- Memory-only-for-" + name + "\n");
            }
            final var toolHooks = new ArrayList<ToolUsePreEvent>();
            final var emitter = new Emitter() {
                @Override
                public <T> void emit(final T event) {
                    if (event instanceof final ToolUsePreEvent hook) {
                        toolHooks.add(hook);
                    }
                }
            };
            final var providers = new ProviderManager(configuration, mapper);
            final var registry = new SubagentRegistry(configuration, providers);
            final var requests = new ArrayList<ChatCompletionRequest>();
            final var contexts = new ArrayList<ToolContext>();
            final var outputs = new ArrayList<ToolResult>();
            final var client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    final var step = requests.size() % 4;
                    requests.add(request);
                    if (step == 3) {
                        return new Message("assistant", "done", null, null, null, null);
                    }
                    final var name = step == 2 ? "bash" : "read";
                    final var args =
                            switch (step) {
                                case 0 -> "{\"path\":\"sentinel.txt\"}";
                                case 1 -> "{\"path\":\".yca/memory/MEMORY.md\"}";
                                default -> "{\"command\":\"pwd\",\"workdir\":\".\"}";
                            };
                    return new Message(
                            "assistant",
                            null,
                            null,
                            List.of(new ToolCall("call-" + step, "function", new ToolCall.FunctionCall(name, args), 0)),
                            null,
                            null);
                }
            };
            final List<EmbeddedTool> tools = List.of(
                    new ReadFileTool(configuration),
                    new BashTool(emitter, null, configuration, null),
                    new ReadFileTool(configuration) {
                        @Override
                        public String name() {
                            return "session_read";
                        }

                        @Override
                        public boolean usable(final Path projectRoot) {
                            return first.equals(projectRoot) || second.equals(projectRoot);
                        }
                    });
            final var toolRegistry = new ToolRegistry(null) {
                @Override
                public List<EmbeddedTool> tools() {
                    return tools;
                }
            };
            final var executions = new ToolExecutionService(configuration, emitter, List.of()) {
                @Override
                public ToolResult execute(
                        final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                    contexts.add(context);
                    final var result = super.execute(tool, args, context);
                    outputs.add(result);
                    return result;
                }
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
                        executions,
                        configuration,
                        null,
                        new MemoryStore(configuration),
                        emitter,
                        mapper,
                        graph,
                        providers,
                        null,
                        new AgentProvider(configuration),
                        new TodoPromptResolver(),
                        null,
                        clock,
                        executor,
                        registry,
                        new PrecisionDurationMarkerProvider(),
                        new BackoffSleeper(),
                        null,
                        null);
                // A single shared runner and shared tool instances alternate roots. Also exercise the
                // standalone contexts and roots without parent permissions.
                for (var run = 0; run < 6; run++) {
                    final var root = run == 0 || run == 2 || run == 5 ? second : first;
                    final var parentPermissions = new PermissionService(configuration, emitter, root);
                    parentPermissions.setMode(AgentMode.PLAN);
                    parentPermissions.setPermissionMode(PermissionMode.AUTO_EDIT);
                    parentPermissions.addSessionGrantAnchor("parent-only");
                    final Set<String> parentGrants = Set.copyOf(parentPermissions.sessionGrants());
                    final ToolContext parentContext =
                            switch (run) {
                                case 3 -> ToolContext.empty(root, new DisabledSandbox(root));
                                case 5 -> ToolContext.empty(root, new DisabledSandbox(root));
                                default ->
                                    new ToolContext(
                                            "parent",
                                            null,
                                            null,
                                            parentPermissions,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            () -> false,
                                            null,
                                            null,
                                            root,
                                            new DisabledSandbox(root));
                            };
                    final var allowed = parentContext != null && parentContext.projectDir() != null
                            ? Set.of("read", "bash", "session_read")
                            : Set.of("read", "bash");
                    final var state = registry.start(new AgentRunSpec(
                                    "parent-" + UUID.randomUUID(),
                                    "Inspect the project memory",
                                    null,
                                    null,
                                    null,
                                    6,
                                    allowed,
                                    allowed))
                            .orElseThrow();
                    final var parentStore = new InMemorySessionStore(clock);
                    final var snapshots = new SnapshotStore(configuration);
                    final var todos = new InMemoryTodoStore();
                    if (parentContext == null) {
                        runner.runAsync(
                                state,
                                parentStore,
                                snapshots,
                                todos,
                                ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)),
                                () -> false);
                    } else {
                        runner.runAsync(state, parentStore, snapshots, todos, parentContext, () -> false);
                    }
                    state.awaitResult();
                    assertTrue(state.result().success(), state.result().error());
                    assertEquals((run + 1) * 4, requests.size());
                    assertEquals((run + 1) * 3, outputs.size());
                    assertEquals((run + 1) * 3, toolHooks.size());
                    toolHooks
                            .subList(run * 3, run * 3 + 3)
                            .forEach(hook -> assertEquals(
                                    root.toString(), hook.eventContext().cwd(), hook.toolName()));
                    final var name = root.getFileName().toString();
                    final var other = root.equals(first) ? "second" : "first";
                    final var prompt = requests.get(run * 4).messages().stream()
                            .filter(message -> "system".equals(message.role()))
                            .map(message -> String.valueOf(message.content()))
                            .collect(joining("\n"));
                    assertTrue(prompt.contains("Working directory: " + root), prompt);
                    assertTrue(prompt.contains("Instructions-only-for-" + name), prompt);
                    assertTrue(prompt.contains("Memory-only-for-" + name), prompt);
                    assertFalse(prompt.contains("Instructions-only-for-" + other), prompt);
                    assertFalse(prompt.contains("Memory-only-for-" + other), prompt);
                    assertTrue(state.result().fullConversation().contains("Working directory: " + root));
                    final var results = outputs.subList(run * 3, run * 3 + 3);
                    results.forEach(result -> assertTrue(result.success(), result.output()));
                    assertTrue(results.get(0).output().contains("File-only-for-" + name));
                    assertTrue(results.get(1).output().contains("Memory-only-for-" + name));
                    assertTrue(
                            results.get(2).output().contains(root.toString()),
                            results.get(2).output());
                    final var child = contexts.get(run * 3);
                    assertEquals(root, child.projectDir());
                    assertNotSame(parentPermissions, child.permissions());
                    assertTrue(child.permissions().sessionGrants().isEmpty());
                    if (parentContext != null && parentContext.permissions() != null) {
                        assertEquals(AgentMode.PLAN, child.permissions().mode());
                        assertEquals(
                                PermissionMode.AUTO_EDIT, child.permissions().permissionMode());
                    }
                    child.permissions().addSessionGrantAnchor("child-only");
                    assertTrue(child.permissions().sessionGrants().contains(root + "|bash:child-only"));
                    assertEquals(parentGrants, Set.copyOf(parentPermissions.sessionGrants()));
                    assertEquals(first, configuration.resolveWorkingDir());
                    assertEquals(first, configuration.resolveProjectDir());
                    assertEquals(tools, toolRegistry.tools());
                }
            }
        }
    }

    public static class WorkingDirectoryModule extends ConfigurationTestModule {
        public WorkingDirectoryModule() {
            super(Map.of(
                    "yca.storageHome", Configured.tmp.resolve("storage").toString(),
                    "yca.workingDir", Configured.tmp.resolve("first").toString(),
                    "yca.projectDir", Configured.tmp.resolve("first").toString(),
                    "yca.memory.enabled", "true",
                    "yca.subagents.enabled", "true",
                    "yca.subagents.maxConcurrent", "2",
                    "yca.llm.maxConcurrent", "4"));
        }
    }
}
