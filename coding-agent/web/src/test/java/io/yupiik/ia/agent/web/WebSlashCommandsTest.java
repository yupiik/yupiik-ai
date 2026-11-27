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
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.cli.CliCommands;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.configuration.MetricsConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellIO;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.shell.btw.BtwSlashCommands;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.CodexLoginCommands;
import io.yupiik.ia.agent.core.shell.slash.CompletionEngine;
import io.yupiik.ia.agent.core.shell.slash.ConfigCommands;
import io.yupiik.ia.agent.core.shell.slash.InspectCommands;
import io.yupiik.ia.agent.core.shell.slash.MaintainCommands;
import io.yupiik.ia.agent.core.shell.slash.PermissionCommands;
import io.yupiik.ia.agent.core.shell.slash.SessionCommands;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.shell.slash.UsageCommands;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestWebJsonMapper.class)
class WebSlashCommandsTest {
    private final List<WebEventDto> events = new ArrayList<>();
    private WebSlashCommands commands;
    private ShellEnv env;
    private SubagentRegistry subagents;

    @BeforeEach
    void setup(final JsonMapper mapper) {
        final var store = new SessionStore() {
            @Override
            public String create() {
                return "new-session";
            }

            @Override
            public List<SessionInfo> list() {
                return List.of(new SessionInfo("saved", 0, 0));
            }
        };
        final var sets = new ArrayList<SlashCommandSet>(List.of(
                new ConfigCommands(null, null, null, null, null, null, null),
                new MaintainCommands(null, null, null),
                new InspectCommands(null),
                new SessionCommands(store, null),
                new CliCommands(null),
                new BtwSlashCommands(null),
                new CodexLoginCommands(null),
                new PermissionCommands(),
                new UsageCommands(null)));
        sets.add(new SlashCommandSet() {
            @Override
            public String title() {
                return "test";
            }

            @Override
            public List<SlashCommand> commands() {
                return List.of(SlashCommand.of(
                        new SlashCommandSpec("/inspect", null, "shared inspection", SlashGroup.INSPECT),
                        (arg, shell) -> CompletableFuture.completedStage(ShellOutcome.transcript("shared result"))));
            }
        });
        final var shared = new SlashRegistry(sets);
        subagents = new SubagentRegistry(null, null) {
            @Override
            public boolean enabled() {
                return true;
            }
        };
        commands = new WebSlashCommands(shared, subagents, store);
        final var broker = new SseBroker(mapper) {
            @Override
            public void broadcastDroppable(final String sessionId, final String frame) {
                broadcast(sessionId, frame);
            }

            @Override
            public void broadcast(final String sessionId, final String frame) {
                assertEquals("parent", sessionId);
                events.add(mapper.fromString(
                        WebEventDto.class,
                        frame.substring(frame.indexOf("data: ") + 6).strip()));
            }
        };
        final var transcript = new ConversationTranscript();
        env = new ShellEnv(
                new WebShellFrontend("parent", broker, transcript, mapper),
                null,
                new ShellSession().sessionId("parent"),
                transcript);
    }

    @Test
    void containerInjectsCommandsWithSharedCallbacksAndWebOverrides() {
        final var shared = commands.registry();
        final var store = new SessionStore() {
            @Override
            public String create() {
                return "container-session";
            }
        };
        try (final var container = ConfiguringContainer.of()
                        .register(new FusionModule() {
                            @Override
                            public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                                return (container, bean) -> bean.type() == WebSlashCommands.class;
                            }

                            @Override
                            public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                                return (container, listener) -> false;
                            }
                        })
                        .register(
                                new ProvidedInstanceBean<>(DefaultScoped.class, SlashRegistry.class, () -> shared),
                                new ProvidedInstanceBean<>(
                                        DefaultScoped.class, SubagentRegistry.class, () -> subagents),
                                new ProvidedInstanceBean<>(DefaultScoped.class, SessionStore.class, () -> store))
                        .start();
                final var instance = container.lookup(WebSlashCommands.class)) {
            final var injected = instance.instance();
            assertSame(
                    shared.find("inspect").orElseThrow(),
                    injected.registry().find("inspect").orElseThrow());
            assertEquals(
                    new ShellOutcome.Transcript("shared result"),
                    injected.dispatch("/inspect", env).toCompletableFuture().join());
            events.clear();
            assertInstanceOf(
                    ShellOutcome.None.class,
                    injected.dispatch("/new", env).toCompletableFuture().join());
            assertEquals("open-session", events.getFirst().data().get("command"));
            assertEquals("container-session", events.getFirst().data().get("id"));
            events.clear();
            injected.dispatch("/tabs", env).toCompletableFuture().join();
            assertEquals("tabs", events.getFirst().data().get("command"));
        }
    }

    @Test
    void browserActionsProduceSessionScopedEvents() {
        for (final var name : List.of("tabs", "quit", "exit")) {
            events.clear();
            assertInstanceOf(ShellOutcome.None.class, dispatch("/" + name));
            assertEquals(name, events.getFirst().data().get("command"));
        }
        events.clear();
        dispatch("/theme light");
        assertEquals("light", events.getFirst().data().get("arg"));
        events.clear();
        assertInstanceOf(ShellOutcome.Status.class, dispatch("/theme nonexistent"));
        assertTrue(events.stream().noneMatch(event -> "web-command".equals(event.type())));
    }

    @Test
    void newAndResumeNavigateWithoutMutatingTheLiveSession() {
        dispatch("/new");
        assertEquals("open-session", events.getFirst().data().get("command"));
        assertEquals("new-session", events.getFirst().data().get("id"));
        assertEquals("parent", env.session().sessionId());
        events.clear();
        dispatch("/resume saved");
        assertEquals("saved", events.getFirst().data().get("id"));
        assertEquals("parent", env.session().sessionId());
        events.clear();
        assertInstanceOf(ShellOutcome.Error.class, dispatch("/resume unknown"));
        assertTrue(events.stream().noneMatch(event -> "web-command".equals(event.type())));
    }

    @Test
    void resumePickerPreservesAsciiWhitespaceAndDoesNotBroadenItToUnicode() {
        for (final var selected : List.of(
                "saved",
                "saved ",
                "saved\tTitle with spaces",
                "saved\r\nTitle",
                "saved\fTitle",
                "saved\u000bTitle",
                "saved\u00a0Title",
                "saved\u2003Title",
                " saved",
                "")) {
            events.clear();
            final var io = new ShellIO() {
                @Override
                public CompletionStage<Optional<String>> readLine(final String prompt) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletionStage<Boolean> confirm(final String prompt) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
                    assertEquals("resume session", title);
                    assertEquals(1, options.size());
                    assertTrue(options.getFirst().startsWith("saved  "));
                    return CompletableFuture.completedStage(Optional.of(selected));
                }

                @Override
                public ApprovalGate approvalGate() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public FeedbackGate feedbackGate() {
                    throw new UnsupportedOperationException();
                }
            };
            final var outcome = commands.registry()
                    .find("resume")
                    .orElseThrow()
                    .action()
                    .apply("", new ShellEnv(env.frontend(), io, env.session(), env.transcript()))
                    .toCompletableFuture()
                    .join();
            if (List.of("saved\u00a0Title", "saved\u2003Title", " saved", "").contains(selected)) {
                assertInstanceOf(ShellOutcome.Error.class, outcome);
                assertTrue(events.isEmpty());
            } else {
                assertInstanceOf(ShellOutcome.None.class, outcome);
                assertEquals("saved", events.getFirst().data().get("id"));
            }
            assertEquals("parent", env.session().sessionId());
        }
    }

    @Test
    void subagentsEmptyStateIsAModalEventNotATuiCallbackOrTranscript() {
        assertInstanceOf(ShellOutcome.None.class, dispatch("/subagents"));
        final var event = events.getFirst();
        assertEquals("web-command", event.type());
        assertEquals("subagents", event.data().get("command"));
        assertTrue(String.valueOf(event.data().get("markdown")).contains("No subagents."));
        assertTrue(events.stream().noneMatch(item -> "transcript".equals(item.type())));
    }

    @Test
    void sharedCommandsAndWebHelpUseTheSameCatalogAsCompletion() {
        assertEquals(new ShellOutcome.Transcript("shared result"), dispatch("/inspect"));
        final var help = assertInstanceOf(ShellOutcome.Transcript.class, dispatch("/help"))
                .markdown();
        final var completion = new CompletionEngine(commands.registry(), new ArgCompletion.Env() {});
        for (final var spec : commands.registry().specs()) {
            assertTrue(help.contains(spec.name()), spec.name());
            assertNotNull(commands.registry().find(spec.command()).orElseThrow().action());
            assertTrue(
                    completion
                            .suggest(spec.name(), spec.name().length())
                            .items()
                            .contains(spec.name()),
                    spec.name());
        }
        assertTrue(help.contains("light|dark"));
        assertFalse(help.contains("Ctrl-D"));
        assertFalse(help.contains("/tabs <"));
        final var themes = completion.suggest("/theme ", 7).items();
        assertEquals(List.of("dark", "light"), themes);
        final var shortcuts = assertInstanceOf(ShellOutcome.Transcript.class, dispatch("/shortcuts"))
                .markdown();
        assertTrue(shortcuts.contains("Shift+Enter"));
        assertFalse(shortcuts.contains("Ctrl-D"));
    }

    @Test
    void settingsIncludeEveryDisplayToggleAndClearHasAVisibleEvent() {
        env.session()
                .showThinking(false)
                .showToolOutput(ToolOutputVisibility.HIDDEN)
                .diffViewMode(DiffViewMode.SIMPLE)
                .metrics(new MetricsConfiguration(false, false, MetricsDisplayMode.FULL));
        dispatch("/help");
        assertEquals(
                Map.of(
                        "showThinking",
                        false,
                        "showToolOutput",
                        "hidden",
                        "diffViewMode",
                        "simple",
                        "metricsPerTurn",
                        false,
                        "metricsSession",
                        false,
                        "metricsMode",
                        "full"),
                events.getLast().data());
        ((WebShellFrontend) env.frontend()).clear();
        assertEquals("clear", events.getLast().data().get("command"));
    }

    @Test
    void diffViewDefaultsToAutoAndSlashCommandsSynchronizeSessionAndFrontend() {
        assertEquals("auto", WebSlashCommands.displaySettings(env.session()).get("diffViewMode"));
        for (final var mode : DiffViewMode.values()) {
            events.clear();
            dispatch("/diff-view " + mode.value());
            assertEquals(mode, env.session().diffViewMode());
            assertTrue(events.stream()
                    .anyMatch(event -> "settings".equals(event.type())
                            && Map.of("diffViewMode", mode.value()).equals(event.data())));
            assertEquals(mode.value(), events.getLast().data().get("diffViewMode"));
        }
        events.clear();
        dispatch("/diff-view invalid");
        assertEquals(DiffViewMode.SIMPLE, env.session().diffViewMode());
        assertEquals("simple", events.getLast().data().get("diffViewMode"));
        final var completion = new CompletionEngine(commands.registry(), new ArgCompletion.Env() {});
        assertEquals(
                List.of("auto", "side-by-side", "simple"),
                completion.suggest("/diff-view ", 11).items());
    }

    @Test
    void frontendDiffViewEventsUseWireValuesAndNullDefaultsToAuto() {
        for (final var mode : DiffViewMode.values()) {
            env.frontend().diffViewMode(mode);
            assertEquals("settings", events.getLast().type());
            assertEquals(Map.of("diffViewMode", mode.value()), events.getLast().data());
        }
        env.frontend().diffViewMode(null);
        assertEquals(Map.of("diffViewMode", "auto"), events.getLast().data());
    }

    @Test
    void editPlanShowsTheExportPathBeforeWaitingForReloadConfirmation() {
        final var path = Path.of("plan.md");
        final var service = new AgentSessionService(null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public PlanExport exportPlan(
                    final String sessionId, final String arg, final String title, final SessionRuntime runtime) {
                return new PlanExport(path, "original", null);
            }

            @Override
            public PlanReload reloadPlan(
                    final String sessionId, final Path file, final String original, final SessionRuntime runtime) {
                assertEquals("parent", sessionId);
                assertEquals(path, file);
                assertEquals("original", original);
                return new PlanReload(true, "plan updated", null);
            }
        };
        final var io = new ShellIO() {
            @Override
            public CompletionStage<Optional<String>> readLine(final String prompt) {
                assertTrue(prompt.contains("plan.md"));
                assertTrue(events.stream()
                        .anyMatch(event ->
                                event.message() != null && event.message().contains("plan exported to plan.md")));
                return CompletableFuture.completedStage(Optional.of(""));
            }

            @Override
            public CompletionStage<Boolean> confirm(final String prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ApprovalGate approvalGate() {
                throw new UnsupportedOperationException();
            }

            @Override
            public FeedbackGate feedbackGate() {
                throw new UnsupportedOperationException();
            }
        };
        final var command = new MaintainCommands(service, null, null)
                .commands().stream()
                        .filter(item -> "editplan".equals(item.spec().command()))
                        .findFirst()
                        .orElseThrow();
        final var outcome = command.action()
                .apply("", new ShellEnv(env.frontend(), io, env.session(), env.transcript()))
                .toCompletableFuture()
                .join();
        assertEquals(new ShellOutcome.Status("plan updated"), outcome);
    }

    @Test
    void subagentListAndDetailAreScopedToTheCurrentSession() {
        final var own = subagents.start("parent", "review files", "reviewer").orElseThrow();
        final var other = subagents
                .start("other-session", "private task", "private-child")
                .orElseThrow();
        dispatch("/subagents");
        final var list = String.valueOf(events.getFirst().data().get("markdown"));
        assertTrue(list.contains(own.id()));
        assertFalse(list.contains(other.id()));
        events.clear();
        dispatch("/subagents reviewer");
        assertTrue(String.valueOf(events.getFirst().data().get("markdown")).contains("Conversation"));
        events.clear();
        dispatch("/subagents " + other.id());
        final var denied = String.valueOf(events.getFirst().data().get("markdown"));
        assertFalse(denied.contains("Conversation"));
        assertFalse(denied.contains("private task"));
    }

    private ShellOutcome dispatch(final String text) {
        return commands.dispatch(text, env).toCompletableFuture().join();
    }
}
