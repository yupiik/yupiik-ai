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
package io.yupiik.ia.agent.core.shell.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.memory.MemoryCompactor;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the /tools and /executing-tools inspection commands against a seeded transcript. */
class InspectCommandsTest {
    @TempDir
    private static Path eventWorkdir;

    @TempDir
    private static Path testWorkdir;

    @Test
    void executingToolsRendersEachRunningToolAndItsCommand() {
        final var transcript = new ConversationTranscript();
        // two in-flight tools: one bash command, one plain tool (no command in args)
        transcript.onEvent(
                new AgentEvent.ToolStarted("bash", "{\"command\":\"mvn test\"}", 1, "s1", null, null, eventWorkdir));
        transcript.onEvent(new AgentEvent.ToolStarted("grep", "{}", 2, "s1", null, null, eventWorkdir));
        final var env = new ShellEnv(null, null, new ShellSession().sessionId("s1"), transcript);

        final var outcome = command(env, "/executing-tools");
        final var content = content(outcome);
        assertTrue(content.contains("mvn test"), "bash command must be shown: " + content);
        assertTrue(content.contains("grep"), "plain tool name must be shown: " + content);
    }

    @Test
    void executingToolsWithNothingRunningEmitsDurableTranscript() {
        // an empty list must be a durable Transcript (not a Status toast): on the TUI/web a status is a
        // transient, easy-to-miss notification while a Transcript is always visible and surfaces the modal.
        final var env = new ShellEnv(null, null, new ShellSession().sessionId("s1"), new ConversationTranscript());
        final var outcome = command(env, "/executing-tools");
        final var content = content(outcome);
        assertInstanceOf(
                ShellOutcome.Transcript.class, outcome, "empty list must be a transcript, not a status: " + outcome);
        assertTrue(content.startsWith("**Executing tools**"), "must keep the executing-tools marker: " + content);
        assertTrue(content.contains("(no tool executing)"), "must say no tool is executing: " + content);
    }

    @Nested
    @FusionSupport(modules = MemoryRootsModule.class)
    class MemoryRoots {
        @TempDir
        static Path directory;

        @Test
        void memoryShowAndCompactUseSessionRoot(
                @Fusion final CodingAgentConfiguration config, @TempDir final Path second) throws Exception {
            final var first = Path.of(config.workingDir());
            final var store = new MemoryStore(config);
            Files.createDirectories(store.globalFile().getParent());
            Files.writeString(store.globalFile(), "- shared fact\n");
            Files.createDirectories(MemoryStore.projectFile(first).getParent());
            Files.createDirectories(MemoryStore.projectFile(second).getParent());
            Files.writeString(MemoryStore.projectFile(first), "- first fact\n");
            Files.writeString(MemoryStore.projectFile(second), "- second fact\n- another fact\n");
            final var compacted = new ArrayList<Path>();
            final var compactor = new MemoryCompactor(null, null, null, null, null) {
                @Override
                public String compactNow(final Path root) {
                    compacted.add(root);
                    return "compacted " + root;
                }

                @Override
                public CompactionState state(final Path root) {
                    return new CompactionState(1, root.equals(first) ? 101 : 202, 20);
                }
            };
            final var commands = new InspectCommands(
                    new AgentSessionService(null, null, null, null, store, null, null, null, config, null, compactor));
            for (final var root : List.of(first, second)) {
                final var builder = new SystemPromptBuilder(testWorkdir) {
                    @Override
                    public Path effectiveWorkdir() {
                        return root;
                    }
                };
                final var env = new ShellEnv(
                        null,
                        null,
                        new ShellSession()
                                .sessionId("s1")
                                .runtime(new SessionRuntime(
                                        null,
                                        new PermissionService(config, root),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        builder,
                                        root,
                                        new DisabledSandbox(root))),
                        new ConversationTranscript());
                final var shown = content(command(commands, env, "/memory show"));
                final var project = Files.readString(MemoryStore.projectFile(root));
                assertTrue(shown.contains(project));
                assertTrue(shown.contains("- shared fact"));
                assertTrue(shown.contains(
                        "-- project: " + project.length() + " chars, " + (root.equals(first) ? 1 : 2) + " bullets"));
                assertTrue(shown.contains(root.equals(first) ? "before=101" : "before=202"));
                assertFalse(shown.contains(root.equals(first) ? "second fact" : "first fact"));
                assertTrue(content(command(commands, env, "/memory compact")).contains("compacted " + root));
            }
            assertEquals(List.of(first, second), compacted);
        }
    }

    @Test
    void memoryWithoutBuilderUsesExplicitRuntimeRoot() {
        final var calls = new ArrayList<String>();
        final var service = new AgentSessionService(null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public MemoryContent memory(final Path root) {
                assertEquals(testWorkdir, root);
                calls.add("show");
                return new MemoryContent("legacy global", "legacy project", "g", "p", true, true);
            }

            @Override
            public MemoryStats memoryStats(final Path root) {
                assertEquals(testWorkdir, root);
                calls.add("stats");
                return new MemoryStats(13, 0, 14, 0, -1, null);
            }

            @Override
            public String compactMemory(final Path root) {
                assertEquals(testWorkdir, root);
                calls.add("compact");
                return "legacy compaction";
            }
        };
        final var commands = new InspectCommands(service);
        final var session = new ShellSession()
                .sessionId("s1")
                .runtime(new SessionRuntime(
                        null,
                        new PermissionService(testWorkdir) {},
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        testWorkdir,
                        new DisabledSandbox(testWorkdir)));
        final var env = new ShellEnv(null, null, session, new ConversationTranscript());
        for (var i = 0; i < 2; i++) {
            final var shown = content(command(commands, env, "/memory"));
            assertTrue(shown.contains("legacy project"));
            assertTrue(shown.contains("-- project: 14 chars, 0 bullets"));
            assertTrue(content(command(commands, env, "/memory compact")).contains("legacy compaction"));
            session.runtime(new SessionRuntime(
                    null,
                    new PermissionService(testWorkdir) {},
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    testWorkdir,
                    new DisabledSandbox(testWorkdir)));
        }
        assertEquals(List.of("stats", "show", "compact", "stats", "show", "compact"), calls);
    }

    private static ShellOutcome command(final ShellEnv env, final String text) {
        return command(new InspectCommands(null), env, text);
    }

    private static ShellOutcome command(final InspectCommands commands, final ShellEnv env, final String text) {
        final var running = commands.commands().stream()
                .filter(c -> text.startsWith(c.spec().name() + " ")
                        || text.equals(c.spec().name()))
                .findFirst()
                .orElseThrow();
        final var arg = text.strip();
        final var idx = arg.indexOf(' ');
        return running.action()
                .apply(idx < 0 ? null : arg.substring(idx + 1).strip(), env)
                .toCompletableFuture()
                .join();
    }

    private static String content(final ShellOutcome outcome) {
        if (outcome instanceof ShellOutcome.Status(String message)) {
            return message;
        }
        if (outcome instanceof ShellOutcome.Transcript(String markdown)) {
            return markdown;
        }
        if (outcome instanceof ShellOutcome.Error(String message)) {
            return message;
        }
        return String.valueOf(outcome);
    }

    public static class MemoryRootsModule extends ConfigurationTestModule {
        public MemoryRootsModule() {
            super(Map.of(
                    "yca.storageHome",
                    MemoryRoots.directory.toString(),
                    "yca.workingDir",
                    MemoryRoots.directory.resolve("first").toString()));
        }
    }
}
