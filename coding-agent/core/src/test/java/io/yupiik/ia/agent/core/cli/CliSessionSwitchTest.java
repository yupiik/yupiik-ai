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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.MemoryExtractor;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.cli.prompt.LinedPrompt;
import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.PermissionsChangedEvent;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.FileSessionStore;
import io.yupiik.ia.agent.core.shell.ConversationCoordinator;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellRegistered;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.ShellUnregistered;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.slash.SessionCommands;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.todo.InMemoryTodoStore;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.builtin.ReadFileTool;
import io.yupiik.ia.agent.core.wiring.HackStdIo;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Isolated // The CLI installs a process-wide SIGINT handler; each run restores it below.
@ExtendWith(TestJsonMapper.class)
@FusionSupport(modules = CliSessionSwitchTest.SessionModule.class)
class CliSessionSwitchTest {
    @TempDir
    private static Path home;

    @Fusion
    CodingAgentConfiguration configuration;

    @BeforeEach
    void resetStorage() throws IOException {
        try (final var paths = Files.walk(home)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(home)) {
                    Files.delete(path);
                }
            }
        }
    }

    public static class SessionModule extends ConfigurationTestModule {
        public SessionModule() {
            super(Map.of(
                    "yca.storageHome", home.resolve("storage").toString(),
                    "yca.workingDir", home.resolve("invocation").toString(),
                    "yca.projectDir", home.resolve("invocation").toString(),
                    "yca.memory.enabled", "true",
                    "yca.memory.maxChars", "8000"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"correct", "cancel"})
    void rejectedStartupCommandStopsTheScriptWithoutSendingLaterEntries(final String choice, final JsonMapper mapper)
            throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        f.initialPrompts = List.of("/hlep", "must not be submitted");
        if ("correct".equals(choice)) {
            f.answerOption("Mistyped command");
        } else {
            f.interactions.add(() -> "c");
        }
        f.input.add(() -> {
            assertTrue(f.requests.isEmpty());
            return "exit";
        });
        f.run();
        assertTrue(f.requests.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tmp/file.txt", "//server/share", "/hello/world", "/tmp/file.txt /help"})
    void slashPathsArePromptsWithoutAnInteraction(final String text, final JsonMapper mapper) throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        f.line(text);
        f.run();

        assertEquals(
                List.of(text),
                f.requests.stream().map(TurnRunner.TurnRequest::prompt).toList());
        f.assertDisposed();
    }

    @Test
    void onlyTheFirstTokenDeterminesSlashRouting(final JsonMapper mapper) throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        f.line("/help /tmp/file.txt");
        f.run();

        assertTrue(f.requests.isEmpty());
        // CLI command results are rendered by AnsiShellFrontend, not added to conversation history.
        assertTrue(f.output.toString().contains("/session-flush"), f.output::toString);
    }

    @Test
    void confirmedSlashPromptIsNotClassifiedAgain(final JsonMapper mapper) throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        f.line("/hlep");
        f.answerOption("Send as normal input");
        f.run();

        assertEquals(
                List.of("/hlep"),
                f.requests.stream().map(TurnRunner.TurnRequest::prompt).toList());
        assertTrue(f.interactions.isEmpty(), "the single picker answer must be consumed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"correct", "cancel"})
    void correctingOrCancellingIsLocalAndPreservesTheOriginalInput(final String choice, final JsonMapper mapper)
            throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        final var original = "  /hlep  keep this draft  ";
        final var before = new String[1];
        f.input.add(() -> {
            before[0] = f.coordinator.transcript().markdown("", "");
            return original;
        });
        if ("correct".equals(choice)) {
            f.answerOption("Mistyped command");
        } else {
            f.interactions.add(() -> "c");
        }
        f.input.add(() -> {
            assertEquals(before[0], f.coordinator.transcript().markdown("", ""));
            assertTrue(f.requests.isEmpty());
            assertEquals(List.of(Message.projectDir(f.alpha.toString()), Message.user("history-A")), f.store.load("A"));
            if ("correct".equals(choice)) {
                assertTrue(f.output.toString().contains("re-enter the exact original input"));
                assertTrue(f.output.toString().contains(original));
            }
            return "exit";
        });
        f.run();
        assertTrue(f.requests.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"path", "prompt", "correct", "cancel"})
    void busyRoutingChangesTheQueueOnlyForPrompts(final String choice, final JsonMapper mapper) throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        f.deferred = new CompletableFuture<>();
        f.line("running");
        final var text = "path".equals(choice) ? "/tmp/file.txt" : "/hlep";
        final var before = new String[1];
        f.input.add(() -> {
            before[0] = f.coordinator.transcript().markdown("", "");
            return text;
        });
        if ("prompt".equals(choice) || "correct".equals(choice)) {
            f.answerOption("prompt".equals(choice) ? "Send as normal input" : "Mistyped command");
        } else if ("cancel".equals(choice)) {
            f.interactions.add(() -> "c");
        }
        f.input.add(() -> {
            assertEquals(1, f.requests.size());
            final var queued = f.requests.getFirst().queuedPrompt();
            if ("path".equals(choice) || "prompt".equals(choice)) {
                assertEquals(text, queued.get());
            } else {
                assertEquals(before[0], f.coordinator.transcript().markdown("", ""));
            }
            assertNull(queued.get());
            f.deferred.complete(true);
            f.deferred = null;
            return "exit";
        });
        f.run();
        assertEquals(1, f.requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tmp/file.txt", "/hlep"})
    void initialSlashPromptWaitsForItsActualTurnBeforeTheNextEntry(final String text, final JsonMapper mapper)
            throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        final var awaited = new AtomicBoolean();
        // Completing on get makes the wait observable, with no worker, sleeps or timeout waits.
        final var turn = new CompletableFuture<Boolean>() {
            @Override
            public <U> CompletableFuture<U> newIncompleteFuture() {
                return new CompletableFuture<>() {
                    @Override
                    public U get(final long timeout, final TimeUnit unit)
                            throws InterruptedException, ExecutionException, TimeoutException {
                        awaited.set(true);
                        f.deferred = null;
                        completeTurn();
                        return super.get(timeout, unit);
                    }
                };
            }

            private void completeTurn() {
                complete(true);
            }
        };
        f.deferred = turn;
        f.initialPrompts = List.of(text, "/help", "next");
        if ("/hlep".equals(text)) {
            f.answerOption("Send as normal input");
        }
        f.input.add(() -> {
            assertTrue(awaited.get());
            assertEquals(
                    List.of(text, "next"),
                    f.requests.stream().map(TurnRunner.TurnRequest::prompt).toList());
            return "exit";
        });
        f.run();
        assertTrue(awaited.get());
    }

    @Test
    void resumeABARebindsTurnsToolsPromptsHooksMemoryAndRouting(final JsonMapper mapper) throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        f.line("first");
        f.line("/resume B");
        f.input.add(() -> {
            assertEquals(List.of("B"), List.copyOf(f.registry.sessions()));
            assertNull(f.registry.active("A"));
            assertEquals(f.beta, f.registry.active("B").session().runtime().workdir());
            assertTrue(f.coordinator.transcript().markdown("", "").contains("history-B"));
            assertFalse(f.coordinator.transcript().markdown("", "").contains("history-A"));
            assertEquals(1, f.created.getFirst().closed);
            f.registry.onEvent(AgentEvent.Notice.info("retired-A", "A", f.alpha));
            assertFalse(f.output.toString().contains("retired-A"));
            return "second";
        });
        f.input.add(() -> {
            f.coordinator.transcript().appendTranscript("local-only-before-replay");
            return "/resume B"; // Same-id replay must not rebuild B's runtime.
        });
        f.input.add(() -> {
            assertEquals(2, f.created.size());
            assertFalse(f.coordinator.transcript().markdown("", "").contains("local-only-before-replay"));
            return "/resume A";
        });
        f.line("third");
        f.run();

        assertEquals(
                List.of(f.alpha, f.beta, f.alpha),
                f.turns.stream().map(Turn::root).toList());
        assertEquals(
                List.of("A", "B", "A"),
                f.requests.stream().map(TurnRunner.TurnRequest::sessionId).toList());
        for (int i = 0; i < f.turns.size(); i++) {
            final var turn = f.turns.get(i);
            final var label = i == 1 ? "beta" : "alpha";
            assertTrue(turn.systemPrompt().contains("rules-" + label), turn.systemPrompt());
            assertTrue(turn.toolOutput().contains("file-" + label), turn.toolOutput());
            assertTrue(turn.history().stream().anyMatch(m -> ("history-" + turn.id()).equals(m.content())));
            assertTrue(f.hooks.stream()
                    .anyMatch(e -> e.eventContext().cwd().equals(turn.root().toString())));
        }

        // Even a late history supplier is bound to its submitted session, not the latest CLI alias.
        assertTrue(f.requests.get(1).history().get().stream().anyMatch(m -> "history-B".equals(m.content())));
        assertTrue(f.memory.readProject(f.alpha).contains("remembered-A"));
        assertFalse(f.memory.readProject(f.alpha).contains("remembered-B"));
        assertTrue(f.memory.readProject(f.beta).contains("remembered-B"));
        assertFalse(f.memory.readProject(f.beta).contains("remembered-A"));
        assertFalse(Files.exists(MemoryStore.projectFile(f.invocation)));
        assertTrue(f.output.toString().contains("event-A"));
        assertTrue(f.output.toString().contains("event-B"));
        assertNotSame(f.created.get(0).runtime, f.created.get(2).runtime);
        assertEquals(3, f.rootsResolved, "resolve once per runtime, not again per turn or same-id resume");
        f.assertDisposed();
        assertTrue(f.output.toString().contains("session A - context file: " + f.store.path("A")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void launchSandboxOverrideSurvivesResumeAndNew(final boolean sandbox, final JsonMapper mapper) throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        f.sandboxOverride = sandbox;
        f.line("/resume B");
        f.line("/new");
        f.run();
        assertEquals(List.of(sandbox, sandbox, sandbox), f.sandboxOverrides);
        f.assertDisposed();
    }

    @Test
    void newUsesActiveRootAndFreshPermissionsGatesAndSessionState(final JsonMapper mapper) throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        f.line("/resume B");
        f.input.add(() -> {
            final var old = f.registry.active("B").session();
            old.runtime().permissions().grantSession("bash");
            old.pendingMode("build");
            assertFalse(old.runtime().permissions().sessionGrants().isEmpty());
            return "/new";
        });
        f.input.add(() -> {
            assertNull(f.registry.active("B"));
            final var active = f.registry.active("new-1").session();
            assertEquals(f.beta, active.runtime().workdir());
            assertEquals(f.beta.toString(), f.store.projectDirOf("new-1"));
            assertTrue(active.runtime().permissions().sessionGrants().isEmpty());
            assertNotNull(active.runtime().permissions().gate());
            assertNull(active.pendingMode());
            assertEquals("", active.activeAgent());
            assertFalse(f.coordinator.transcript().markdown("", "").contains("history-B"));
            assertEquals(1, f.created.get(1).closed);
            return "fresh turn";
        });
        f.run();
        assertEquals(List.of(f.beta), f.turns.stream().map(Turn::root).toList());
        assertEquals("new-1", f.turns.getFirst().id());
        assertEquals(3, f.rootsResolved);
        assertTrue(f.turns.getFirst().toolOutput().contains("file-beta"));
        assertTrue(f.output.toString().contains("session new-1 - context file: " + f.store.path("new-1")));
        f.assertDisposed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "root", "create", "gates"})
    void failedResumeLeavesTheOriginalSessionRuntimeTranscriptAndRouteIntact(
            final String failure, final JsonMapper mapper) throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        f.failRoot = "root".equals(failure);
        f.failCreate = "create".equals(failure);
        f.failGates = "gates".equals(failure);
        f.line("first");
        f.input.add(() -> {
            f.coordinator.transcript().appendTranscript("keep-the-live-transcript");
            return "/resume " + ("missing".equals(failure) ? "absent" : "B");
        });
        f.input.add(() -> {
            assertEquals(List.of("A"), List.copyOf(f.registry.sessions()));
            final var active = f.registry.active("A").session();
            assertEquals("A", active.sessionId());
            assertSame(f.created.getFirst().runtime, active.runtime());
            assertNotNull(active.runtime().permissions().gate());
            assertTrue(f.coordinator.transcript().markdown("", "").contains("keep-the-live-transcript"));
            assertEquals(0, f.created.getFirst().closed);
            return "still A";
        });
        f.run();
        assertEquals(List.of(f.alpha, f.alpha), f.turns.stream().map(Turn::root).toList());
        assertTrue(f.output.toString().contains("session switch failed"), f.output.toString());
        assertTrue(f.output.toString().contains("session A"));
        f.assertDisposed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "create", "gates", "marker"})
    void failedNewDoesNotRetireTheLiveRuntimeOrLeaveAnEmptySession(final String failure, final JsonMapper mapper)
            throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        f.failRoot = "root".equals(failure);
        f.failCreate = "create".equals(failure);
        f.failGates = "gates".equals(failure);
        f.failMarker = "marker".equals(failure);
        f.line("/new");
        f.input.add(() -> {
            assertEquals(List.of("A"), List.copyOf(f.registry.sessions()));
            assertSame(
                    f.created.getFirst().runtime,
                    f.registry.active("A").session().runtime());
            assertEquals(0, f.created.getFirst().closed);
            assertTrue(f.store.list().stream().noneMatch(info -> "new-1".equals(info.id())));
            assertFalse(Files.exists(f.store.path("new-1")));
            return "still A";
        });
        f.run();
        assertEquals(List.of(f.alpha), f.turns.stream().map(Turn::root).toList());
        assertTrue(f.output.toString().contains("session switch failed"));
        f.assertDisposed();
    }

    @Test
    void legacyAndStaleResumeMetadataUseInvocationDefaultNotPreviousRuntime(final JsonMapper mapper) throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        f.store.replace("legacy", List.of(Message.user("legacy history")));
        f.store.replace(
                "stale", List.of(Message.projectDir(home.resolve("gone").toString()), Message.user("stale history")));
        f.line("/resume B");
        f.line("/resume legacy");
        f.line("legacy turn");
        f.line("/resume B");
        f.line("/resume stale");
        f.line("stale turn");
        f.run();
        assertEquals(
                List.of(f.invocation, f.invocation),
                f.turns.stream().map(Turn::root).toList());
        assertTrue(f.turns.stream().allMatch(t -> t.toolOutput().contains("file-invocation")));
        f.assertDisposed();
    }

    @Test
    void pendingTurnAndQueuedPromptCannotSwitchAndCancellationStillTargetsSubmittedTurn(final JsonMapper mapper)
            throws Exception {
        final var f = new Fixture(home, mapper, configuration);
        f.deferred = new CompletableFuture<>();
        f.line("running A");
        f.line("/new");
        f.line("/resume B");
        f.input.add(() -> {
            // The same request has now entered the running-turn phase after its initial gate.
            f.requests.getFirst().env().session().busy(true);
            return "queued A";
        });
        f.line("/resume B");
        f.input.add(() -> {
            assertEquals(1, f.created.size());
            assertEquals(0, f.created.getFirst().closed);
            assertEquals("A", f.requests.getFirst().env().session().sessionId());
            assertEquals(
                    f.alpha, f.requests.getFirst().env().session().runtime().workdir());
            assertEquals("queued A", f.requests.getFirst().queuedPrompt().get());
            assertNull(f.requests.getFirst().queuedPrompt().get());
            assertFalse(f.requests.getFirst().cancelled().getAsBoolean());
            f.requests.getFirst().env().session().busy(false);
            f.deferred.complete(true);
            f.deferred = null;
            return "/resume B";
        });
        f.input.add(() -> {
            assertEquals(List.of("B"), List.copyOf(f.registry.sessions()));
            assertEquals(1, f.created.getFirst().closed);
            f.deferred = new CompletableFuture<>();
            return "running B";
        });
        f.line("/exit");
        f.run();
        assertTrue(f.requests.getLast().cancelled().getAsBoolean());
        assertTrue(f.requests.getFirst().history().get().stream().anyMatch(m -> "history-A".equals(m.content())));
        assertTrue(f.output.toString().contains("cannot switch sessions while a turn or queued prompt is pending"));
        f.assertDisposed();
    }

    private record Turn(String id, Path root, String systemPrompt, String toolOutput, List<Message> history) {}

    private static final class Created {
        private SessionRuntime runtime;
        private int closed;
        private int extracted;
    }

    private static final class Fixture {
        private final Path invocation;
        private final Path alpha;
        private final Path beta;
        private final CodingAgentConfiguration configuration;
        private final JsonMapper mapper;
        private final Clock clock = Clock.systemUTC();
        private final ConversationCoordinator coordinator = new ConversationCoordinator();
        private final List<PermissionsChangedEvent> hooks = new ArrayList<>();
        private final List<Created> created = new ArrayList<>();
        private final List<Turn> turns = new ArrayList<>();
        private final List<TurnRunner.TurnRequest> requests = new ArrayList<>();
        private final Deque<Supplier<String>> input = new ArrayDeque<>();
        private final Deque<Supplier<String>> interactions = new ArrayDeque<>();
        private List<String> initialPrompts = List.of();
        private Boolean sandboxOverride;
        private final List<Boolean> sandboxOverrides = new ArrayList<>();
        private final StringBuilder output = new StringBuilder();
        private final FileSessionStore store;
        private final MemoryStore memory;
        private final ShellRegistry registry;
        private final Emitter emitter;
        private boolean failRoot;
        private boolean failCreate;
        private boolean failGates;
        private boolean failMarker;
        private boolean chatClosed;
        private boolean mcpClosed;
        private int rootsResolved;
        private CompletableFuture<Boolean> deferred;

        private Fixture(final Path home, final JsonMapper mapper, final CodingAgentConfiguration configuration)
                throws Exception {
            this.mapper = mapper;
            this.configuration = configuration;
            invocation = project(home.resolve("invocation"), "invocation");
            alpha = project(home.resolve("alpha"), "alpha");
            beta = project(home.resolve("beta"), "beta");
            store = new FileSessionStore() {
                private final Map<String, List<Message>> messages = new LinkedHashMap<>();
                private int next;

                @Override
                public String create() {
                    final var id = "new-" + ++next;
                    replace(id, List.of());
                    return id;
                }

                @Override
                public List<Message> load(final String id) {
                    return messages.getOrDefault(id, List.of());
                }

                @Override
                public void replace(final String id, final List<Message> value) {
                    if (failMarker && id.startsWith("new-") && !value.isEmpty()) {
                        throw new IllegalStateException("marker failed");
                    }
                    messages.put(id, List.copyOf(value));
                    try {
                        Files.createDirectories(path(id).getParent());
                        Files.writeString(path(id), "fixture session");
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public List<SessionInfo> list() {
                    return messages.keySet().stream()
                            .map(id -> new SessionInfo(id, 0, 0))
                            .toList();
                }

                @Override
                public Path path(final String id) {
                    return home.resolve("storage").resolve(id + ".jsonl");
                }

                @Override
                public String projectDirOf(final String id) {
                    final var history = load(id);
                    return history.isEmpty() ? "" : Message.projectDirOf(history.getFirst());
                }

                @Override
                public boolean isEmpty(final String id) {
                    return load(id).isEmpty();
                }

                @Override
                public void delete(final String id) {
                    messages.remove(id);
                    try {
                        Files.deleteIfExists(path(id));
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            };
            store.replace("A", List.of(Message.projectDir(alpha.toString()), Message.user("history-A")));
            store.replace("B", List.of(Message.projectDir(beta.toString()), Message.user("history-B")));
            memory = new MemoryStore(configuration);
            emitter = new Emitter() {
                @Override
                public <T> void emit(final T event) {
                    if (event instanceof ShellRegistered registered) {
                        coordinator.onRegistered(registered);
                    } else if (event instanceof ShellUnregistered unregistered) {
                        coordinator.onUnregistered(unregistered);
                    } else if (event instanceof PermissionsChangedEvent changed) {
                        hooks.add(changed);
                    }
                }
            };
            registry = new ShellRegistry(emitter);
        }

        private static Path project(final Path root, final String label) throws Exception {
            Files.createDirectories(root);
            Files.writeString(root.resolve("AGENTS.md"), "rules-" + label);
            Files.writeString(root.resolve("root.txt"), "file-" + label);
            return root;
        }

        private void line(final String line) {
            input.add(() -> line);
        }

        private void answerOption(final String label) {
            interactions.add(() -> output.toString()
                    .lines()
                    .filter(line -> !line.isEmpty() && Character.isDigit(line.charAt(0)))
                    .filter(line -> line.contains(label))
                    .reduce((previous, next) -> next)
                    .map(line -> line.substring(0, line.indexOf('.')))
                    .orElseThrow(() -> new AssertionError("No picker option for " + label + ": " + output)));
        }

        @SuppressWarnings("try")
        private void run() throws Exception {
            final var chat = new ChatClient() {
                @Override
                public String model(final ChatSettings settings) {
                    return "cli-root-switch-exact";
                }

                @Override
                public String providerId(final ChatSettings settings) {
                    return "test";
                }

                @Override
                public StructuredResult chatStructured(
                        final ChatCompletionRequest request,
                        final String schemaName,
                        final Map<String, Object> schema) {
                    return new StructuredResult(
                            new ChatResult(
                                    Message.assistant(
                                            "{\"facts\":[{\"text\":\"remembered-" + request.sessionId()
                                                    + "\",\"scope\":\"project\"}]}",
                                            null),
                                    new Usage(0, 0, 0)),
                            true);
                }

                @Override
                public void close() {
                    chatClosed = true;
                }
            };
            final var agents = new AgentProvider(configuration);
            final var mcp = new McpManager() {
                @Override
                public void start() {}

                @Override
                public void close() {
                    mcpClosed = true;
                }
            };
            final var service = new AgentSessionService(
                    chat, mcp, agents, store, memory, null, null, null, configuration, null, null);
            final var factory =
                    new SessionRuntimeFactory(
                            chat,
                            null,
                            null,
                            configuration,
                            null,
                            store,
                            memory,
                            agents,
                            null,
                            null,
                            null,
                            null,
                            null,
                            mapper,
                            emitter,
                            clock,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null) {
                        @Override
                        public Path resolveWorkdir(final Path explicitWorkdir, final String resumedSessionId) {
                            rootsResolved++;
                            if (failRoot && !created.isEmpty()) {
                                throw new IllegalArgumentException("root failed");
                            }
                            return super.resolveWorkdir(explicitWorkdir, resumedSessionId);
                        }

                        @Override
                        public SessionRuntime create(final String id, final Path root, final Boolean sandbox) {
                            sandboxOverrides.add(sandbox);
                            if (failCreate && !created.isEmpty()) {
                                throw new IllegalStateException("create failed");
                            }
                            final var owned = new Created();
                            final var permissions =
                                    new PermissionService(Fixture.this.configuration, Fixture.this.emitter, root);
                            final var todos = new InMemoryTodoStore();
                            final var builder = new SystemPromptBuilder(
                                    Fixture.this.configuration,
                                    memory,
                                    todos,
                                    permissions,
                                    agents,
                                    null,
                                    null,
                                    null,
                                    null,
                                    root);
                            final var extractor = new MemoryExtractor(
                                    chat,
                                    Fixture.this.configuration,
                                    null,
                                    memory,
                                    Fixture.this.mapper,
                                    null,
                                    Fixture.this.emitter,
                                    Fixture.this.clock,
                                    null,
                                    id,
                                    null,
                                    null,
                                    null,
                                    root);
                            final var loop = new AgentLoop(root, new DisabledSandbox(root)) {
                                @Override
                                public String run(
                                        final String prompt,
                                        final String agent,
                                        final String sessionId,
                                        final List<Message> history,
                                        final BooleanSupplier cancelled) {
                                    assertEquals(id, sessionId);
                                    assertEquals("cli-root-switch-exact", chat.model(chatSettings()));
                                    assertEquals(0, owned.closed, "no turn may use a disposed runtime");
                                    permissions.setMode(AgentMode.BUILD);
                                    final var context = new ToolContext(
                                            sessionId,
                                            null,
                                            null,
                                            permissions,
                                            owned.runtime.feedback(),
                                            todos,
                                            owned.runtime.status(),
                                            owned.runtime.usage(),
                                            null,
                                            cancelled,
                                            chatSettings(),
                                            () -> false,
                                            root,
                                            new DisabledSandbox(root));
                                    final var result = new ReadFileTool(Fixture.this.configuration)
                                            .execute(Map.of("path", "root.txt"), context);
                                    assertTrue(result.success(), result.output());
                                    turns.add(
                                            new Turn(id, root, builder.build(prompt, null), result.output(), history));
                                    registry.onEvent(AgentEvent.Notice.info("event-" + id, id, root));
                                    return "done";
                                }

                                @Override
                                public boolean forceExtractNow(
                                        final List<Message> messages, final ChatSettings settings) {
                                    owned.extracted++;
                                    return extractor.forceExtractNow(messages, settings);
                                }

                                @Override
                                public void close() {
                                    owned.closed++;
                                }
                            };
                            owned.runtime = new SessionRuntime(
                                    loop,
                                    permissions,
                                    new UserFeedbackService(),
                                    new UsageTracker(null, null, null, Fixture.this.clock),
                                    todos,
                                    new StatusReporter(),
                                    null,
                                    builder,
                                    root,
                                    new DisabledSandbox(root));
                            created.add(owned);
                            return owned.runtime;
                        }
                    };
            final var lifecycle = new SessionLifecycle(store) {
                @Override
                public void installGates(
                        final ApprovalGate approval, final FeedbackGate feedback, final SessionRuntime runtime) {
                    super.installGates(approval, feedback, runtime);
                    if (failGates && created.size() > 1) {
                        throw new IllegalStateException("gates failed");
                    }
                }
            };
            final var runner = new TurnRunner() {
                @Override
                public CompletionStage<Boolean> submit(final TurnRequest request) {
                    requests.add(request);
                    // An incomplete future models the pre-busy indexing/approval window as well as an active turn.
                    if (deferred != null) {
                        return deferred;
                    }
                    request.loop()
                            .run(
                                    request.prompt(),
                                    request.agentName(),
                                    request.sessionId(),
                                    request.history().get(),
                                    request.cancelled());
                    return CompletableFuture.completedFuture(true);
                }
            };
            final var sink = new PromptOutput() {
                @Override
                public boolean active() {
                    return false;
                }

                @Override
                public void print(final String text) {
                    output.append(text);
                }
            };
            final var prompt = new LinedPrompt() {
                @Override
                public Optional<String> readLine(final String text) {
                    return input.isEmpty()
                            ? Optional.empty()
                            : Optional.of(input.removeFirst().get());
                }

                @Override
                public Optional<String> readInteractionLine(final String text) {
                    if (interactions.isEmpty()) {
                        throw new AssertionError("unexpected inline interaction: " + text);
                    }
                    return Optional.of(interactions.removeFirst().get());
                }

                @Override
                public Optional<String> pollQueue() {
                    return Optional.empty();
                }

                @Override
                public boolean queuing() {
                    return true;
                }

                @Override
                public void wakeAll() {}

                @Override
                public PromptOutput externalOutput() {
                    return sink;
                }
            };
            final var signalType = Class.forName("sun.misc.Signal");
            final var handlerType = Class.forName("sun.misc.SignalHandler");
            final var signal = signalType.getConstructor(String.class).newInstance("INT");
            final var handle = signalType.getMethod("handle", signalType, handlerType);
            final var previous =
                    handle.invoke(null, signal, handlerType.getField("SIG_DFL").get(null));
            try (final AutoCloseable restore = () -> handle.invoke(null, signal, previous)) {
                new CliCommand(
                                new CliCommand.CliConf(
                                        "A",
                                        "",
                                        "",
                                        false,
                                        true,
                                        "compact",
                                        false,
                                        "hidden",
                                        false,
                                        true,
                                        initialPrompts,
                                        sandboxOverride),
                                lifecycle,
                                store,
                                factory,
                                agents,
                                chat,
                                service,
                                runner,
                                mapper,
                                new SlashRegistry(List.of(new SessionCommands(store, service, mapper))),
                                registry,
                                coordinator,
                                null,
                                new CliPromptTemplate() {
                                    @Override
                                    public String render(final Map<String, Object> data) {
                                        return "prompt> ";
                                    }
                                },
                                prompt,
                                clock,
                                configuration,
                                new SpeakerLabels(List.of(new FormatDatePromptHelper(clock)), configuration, chat),
                                new HackStdIo(),
                                mcp,
                                null)
                        .run();
            }
        }

        private void assertDisposed() {
            assertTrue(registry.sessions().isEmpty());
            for (final var owned : created) {
                assertEquals(1, owned.closed, "each owned runtime must close once");
                assertTrue(owned.extracted <= 1, "close-time extraction must not replay a disposed runtime");
                assertNull(owned.runtime.permissions().gate());
            }
            assertFalse(chatClosed, "the CLI borrows the global chat client");
            assertFalse(mcpClosed, "the CLI borrows the global MCP manager");
        }
    }
}
