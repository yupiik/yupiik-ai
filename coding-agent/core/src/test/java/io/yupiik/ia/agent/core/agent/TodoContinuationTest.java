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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.TodoConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TodoContinuationTest {
    @TempDir
    private static Path eventWorkdir;

    private static SessionStore noopStore() {
        return new SessionStore() {};
    }

    @BeforeEach
    void bindConfiguration(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
        this.storageHome = Path.of(configuration.resolveStorageHome());
    }

    private Path storageHome;
    private CodingAgentConfiguration configuration;

    @Nested
    @FusionSupport(modules = UnfinishedTodosGatingModule.class)
    class UnfinishedTodosGatingTests {
        @TempDir
        static Path root;

        @Test
        void unfinishedTodosGating() {
            final var todos = new TodoStore(configuration, null) {
                @Override
                public List<TodoItem> all() {
                    return List.of(
                            new TodoItem("a", "in_progress"),
                            new TodoItem("b", "pending"),
                            new TodoItem("c", "completed"));
                }
            };
            final var continuation = new TodoContinuation(
                    configuration,
                    () -> null,
                    () -> todos,
                    noopStore(),
                    Clock.systemUTC(),
                    () -> "s1",
                    () -> null,
                    () -> null,
                    eventWorkdir);

            assertEquals(new TodoContinuation.PendingTodos(2, 3), continuation.unfinishedTodos(true));

            // no tools ran: no continuation hint
            assertNull(continuation.unfinishedTodos(false));
            // no store: no continuation hint
            assertNull(new TodoContinuation(
                            configuration,
                            () -> null,
                            () -> null,
                            noopStore(),
                            Clock.systemUTC(),
                            () -> "s1",
                            () -> null,
                            () -> null,
                            eventWorkdir)
                    .unfinishedTodos(true));
            // fully completed list: nothing pending
            assertNull(new TodoContinuation(
                            configuration,
                            () -> null,
                            () -> new TodoStore(configuration, null) {
                                @Override
                                public List<TodoItem> all() {
                                    return List.of(new TodoItem("done", "completed"));
                                }
                            },
                            noopStore(),
                            Clock.systemUTC(),
                            () -> "s1",
                            () -> null,
                            () -> null,
                            eventWorkdir)
                    .unfinishedTodos(true));
        }

        @Test
        void planModeSkipsTheResumeHint() {
            final Supplier<TodoStore> todos = () -> new TodoStore(configuration, null) {
                @Override
                public List<TodoItem> all() {
                    return List.of(new TodoItem("plan", "pending"));
                }
            };
            final var permissions = new PermissionService(configuration, (configuration).resolveWorkingDir());
            permissions.setMode(AgentMode.PLAN);
            final var continuation = new TodoContinuation(
                    configuration,
                    () -> permissions,
                    todos,
                    noopStore(),
                    Clock.systemUTC(),
                    () -> "s1",
                    () -> null,
                    () -> null,
                    eventWorkdir);
            assertNull(continuation.unfinishedTodos(true));
        }

        @Test
        void stopNoticeIsPersistedThroughTheSessionStore() {
            final var entries = new ArrayList<SessionEntry>();
            final var store = new SessionStore() {
                @Override
                public void append(final String id, final SessionEntry entry) {
                    entries.add(entry);
                }
            };
            final var continuation = new TodoContinuation(
                    configuration,
                    () -> null,
                    () -> null,
                    store,
                    Clock.systemUTC(),
                    () -> "s1",
                    () -> null,
                    () -> null,
                    eventWorkdir);
            continuation.persistStopNotice("s1", "the turn stopped");
            assertEquals(1, entries.size());
            assertEquals("system", entries.get(0).role());
            assertEquals("the turn stopped", entries.get(0).content());
            assertEquals("notice", entries.get(0).name());
        }

        @Test
        void noticeCarriesSessionAndReason() {
            final var continuation = new TodoContinuation(
                    configuration,
                    () -> null,
                    () -> null,
                    noopStore(),
                    Clock.systemUTC(),
                    () -> "s1",
                    () -> "sa",
                    () -> "name",
                    eventWorkdir);
            final var notice =
                    continuation.unfinishedTodosNotice(new TodoContinuation.PendingTodos(2, 5), "stop_sequence");
            assertEquals(AgentEvent.Notice.Level.WARN, notice.level());
            assertEquals("s1", notice.sessionId());
            assertEquals("sa", notice.subagentId());
            assertEquals("name", notice.subagentName());
            assertTrue(notice.message().contains("finish_reason=stop_sequence"));
            assertTrue(notice.message().contains("2/5"));
        }
    }

    public static class UnfinishedTodosGatingModule extends ConfigurationTestModule {
        public UnfinishedTodosGatingModule() {
            super(Map.ofEntries(Map.entry("yca.storageHome", UnfinishedTodosGatingTests.root.toString())));
        }
    }

    @Nested
    @FusionSupport(modules = CanAutoContinueUnfinishedIsConservativeModule.class)
    class CanAutoContinueUnfinishedIsConservativeTests {
        @TempDir
        static Path root;

        @Test
        void canAutoContinueUnfinishedIsConservative() {
            final var continuation = new TodoContinuation(
                    configuration,
                    () -> null,
                    () -> null,
                    noopStore(),
                    Clock.systemUTC(),
                    () -> "s1",
                    () -> null,
                    () -> null,
                    eventWorkdir);
            assertTrue(continuation.canAutoContinueUnfinished(false));
            // a user-injected prompt must not be turned into a continuation
            assertFalse(continuation.canAutoContinueUnfinished(true));
            // a sub-agent never auto-continues on its own todo list
            assertFalse(new TodoContinuation(
                            configuration,
                            () -> null,
                            () -> null,
                            noopStore(),
                            Clock.systemUTC(),
                            () -> "s1",
                            () -> "child",
                            () -> "child-name",
                            eventWorkdir)
                    .canAutoContinueUnfinished(false));
            // the feature must be opted in
            assertFalse(new TodoContinuation(
                            withTodos(configuration, withContinueOnUnfinished(configuration.todos(), false)),
                            () -> null,
                            () -> null,
                            noopStore(),
                            Clock.systemUTC(),
                            () -> "s1",
                            () -> null,
                            () -> null,
                            eventWorkdir)
                    .canAutoContinueUnfinished(false));
        }
    }

    public static class CanAutoContinueUnfinishedIsConservativeModule extends ConfigurationTestModule {
        public CanAutoContinueUnfinishedIsConservativeModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", CanAutoContinueUnfinishedIsConservativeTests.root.toString()),
                    Map.entry("yca.todos.continueOnUnfinished", "true"),
                    Map.entry("yca.todos.maxContinues", "5")));
        }
    }

    @Nested
    @FusionSupport(modules = TryTodoAutoContinueHonorsTheBudgetAndInjectsThePromptModule.class)
    class TryTodoAutoContinueHonorsTheBudgetAndInjectsThePromptTests {
        @TempDir
        static Path root;

        @Test
        void tryTodoAutoContinueHonorsTheBudgetAndInjectsThePrompt() {
            final Supplier<TodoStore> todos = () -> new TodoStore(configuration, null) {
                @Override
                public List<TodoItem> all() {
                    return List.of(new TodoItem("a", "pending"));
                }
            };
            final var messages = new ArrayList<Message>();
            final var notices = new ArrayList<AgentEvent.Notice>();
            final var state = new TurnState();
            final var continuation = new TodoContinuation(
                    configuration,
                    () -> null,
                    todos,
                    noopStore(),
                    Clock.systemUTC(),
                    () -> "s1",
                    () -> null,
                    () -> null,
                    eventWorkdir);

            assertTrue(continuation.tryTodoAutoContinue(messages, "stop", true, false, state, notices::add));
            assertEquals(1, state.unfinishedTodoContinues);
            assertEquals(1, messages.size(), "a continuation user message must be appended");
            assertEquals("user", messages.get(0).role());
            assertInstanceOf(String.class, messages.get(0).content());
            assertTrue(((String) messages.get(0).content()).contains("continue"));
            assertEquals(1, notices.size());
            assertEquals(AgentEvent.Notice.Level.INFO, notices.get(0).level());
            assertTrue(notices.get(0).message().contains("1/2"), notices.get(0).message());

            assertTrue(continuation.tryTodoAutoContinue(messages, "stop", true, false, state, notices::add));
            assertEquals(2, state.unfinishedTodoContinues);
            assertTrue(((String) messages.get(1).content())
                    .contains(TodoContinuation.UNFINISHED_TODO_BLOCKED_PROMPT.strip()));

            // budget exhausted: no third continuation
            assertFalse(continuation.tryTodoAutoContinue(messages, "stop", true, false, state, notices::add));
            assertEquals(2, messages.size(), "no message beyond the budget");
        }
    }

    public static class TryTodoAutoContinueHonorsTheBudgetAndInjectsThePromptModule extends ConfigurationTestModule {
        public TryTodoAutoContinueHonorsTheBudgetAndInjectsThePromptModule() {
            super(Map.ofEntries(
                    Map.entry(
                            "yca.storageHome",
                            TryTodoAutoContinueHonorsTheBudgetAndInjectsThePromptTests.root.toString()),
                    Map.entry("yca.todos.continueOnUnfinished", "true"),
                    Map.entry("yca.todos.maxContinues", "2")));
        }
    }

    @Nested
    @FusionSupport(modules = CustomContinuePromptIsUsedModule.class)
    class CustomContinuePromptIsUsedTests {
        @TempDir
        static Path root;

        @Test
        void customContinuePromptIsUsed() {
            final Supplier<TodoStore> todos = () -> new TodoStore(configuration, null) {
                @Override
                public List<TodoItem> all() {
                    return List.of(new TodoItem("a", "pending"));
                }
            };
            final var messages = new ArrayList<Message>();
            final var notices = new ArrayList<AgentEvent.Notice>();
            final var state = new TurnState();
            final var continuation = new TodoContinuation(
                    configuration,
                    () -> null,
                    todos,
                    noopStore(),
                    Clock.systemUTC(),
                    () -> "s1",
                    () -> null,
                    () -> null,
                    eventWorkdir);
            assertTrue(continuation.tryTodoAutoContinue(messages, "stop", true, false, state, notices::add));
            assertTrue(((String) messages.get(0).content()).contains("keep going please"));
        }
    }

    public static class CustomContinuePromptIsUsedModule extends ConfigurationTestModule {
        public CustomContinuePromptIsUsedModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", CustomContinuePromptIsUsedTests.root.toString()),
                    Map.entry("yca.todos.continueOnUnfinished", "true"),
                    Map.entry("yca.todos.maxContinues", "1"),
                    Map.entry("yca.todos.continuePrompt", "keep going please")));
        }
    }

    private static TodoConfiguration withContinueOnUnfinished(
            final TodoConfiguration baseline, final boolean continueOnUnfinished) {
        return new TodoConfiguration(
                baseline.guidance(), continueOnUnfinished, baseline.maxContinues(), baseline.continuePrompt());
    }

    private static CodingAgentConfiguration withTodos(
            final CodingAgentConfiguration baseline, final TodoConfiguration todos) {
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
                todos,
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
    }
}
