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

import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.codegraph.index.CodeGraphIndex;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.events.SessionStartedEvent;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.FileSessionStore;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link WebSessionManager#submit}: every accepted prompt (fresh submit on an idle
 * session, and a prompt queued behind a running turn) must surface immediately to the live SSE
 * subscribers as a {@code user} agent event - the exact type {@link WebReplay} emits for a persisted
 * user message - so the browser transcript renders the prompt live instead of only after a reload.
 * Slash commands are never broadcast as user turns.
 */
@ExtendWith(TestWebJsonMapper.class)
@FusionSupport(modules = WebSessionManagerPromptTest.SessionModule.class)
class WebSessionManagerPromptTest {

    @TempDir
    static Path storageHome;

    public static class SessionModule extends WebConfigurationTestModule {
        public SessionModule() {
            super(Map.of(
                    "yca.storageHome", storageHome.toString(),
                    "yca.home", storageHome.resolve("home").toString(),
                    "yca.view.diffViewMode", "SIMPLE",
                    "yca.workingDir", storageHome.resolve("launch").toString(),
                    "yca.projectDir", storageHome.resolve("server-project").toString()));
        }
    }

    private JsonMapper mapper;
    private WebSessionManager manager;
    private FakeSseBroker broker;
    private FakeTurnRunner turnRunner;

    @Fusion
    CodingAgentConfiguration configuration;

    private SessionRuntimeFactory runtimeFactory;
    private SessionStore sessionStore;
    private final AtomicInteger defaultRootResolutions = new AtomicInteger();
    private final Queue<SessionStartedEvent> started = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setup(final JsonMapper mapper) throws Exception {
        this.mapper = mapper;
        this.broker = new FakeSseBroker(mapper);
        this.turnRunner = new FakeTurnRunner();

        try (final var paths = Files.walk(storageHome)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(storageHome)) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(storageHome.resolve("launch"));
        Files.createDirectories(storageHome.resolve("server-project"));
        sessionStore = new FileSessionStore(configuration, mapper, Clock.systemUTC());
        runtimeFactory =
                new SessionRuntimeFactory(
                        null,
                        null,
                        null,
                        configuration,
                        null,
                        sessionStore,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        this.mapper,
                        new Emitter() {
                            @Override
                            public <T> void emit(final T event) {
                                if (event instanceof final SessionStartedEvent start) {
                                    started.add(start);
                                }
                            }
                        },
                        Clock.systemUTC(),
                        new PrecisionDurationMarkerProvider(),
                        new BackoffSleeper(),
                        null,
                        null,
                        null, // memory production policy
                        null, // quota store
                        null, // SecretRedactor
                        null) { // NotificationService
                    @Override
                    public Path resolveWorkdir(final Path explicitWorkdir, final String resumedSessionId) {
                        if (explicitWorkdir == null && resumedSessionId == null) {
                            defaultRootResolutions.incrementAndGet();
                        }
                        return super.resolveWorkdir(explicitWorkdir, resumedSessionId);
                    }
                };
        this.manager = new WebSessionManager(
                configuration,
                runtimeFactory,
                new NoopShellRegistry(),
                new WebSlashCommands(new SlashRegistry() {}, null, sessionStore),
                turnRunner,
                sessionStore,
                new SessionLifecycle(sessionStore),
                broker,
                this.mapper,
                new BtwAnswerer(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @AfterEach
    void close() {
        try {
            manager.close();
        } finally {
            broker.close();
        }
    }

    @Test
    void promptRecordsNewProjectBeforeRuntimeAttachAndPreservesRestoredRoots() throws Exception {
        final var restored = Files.createDirectories(storageHome.resolve("restored"));
        sessionStore.markProject("old", restored.toString());
        final var rpc = new WebJsonRpc(
                sessionStore,
                null,
                manager,
                null,
                null,
                null,
                configuration,
                null,
                null,
                null,
                null,
                null,
                runtimeFactory);

        rpc.submitPrompt("new", "new session", null);
        rpc.submitPrompt("old", "restored session", null);
        rpc.submitPrompt("new", "another new prompt", null);

        final var serverProject = configuration.resolveWorkingDir();
        assertEquals(serverProject.toString(), sessionStore.projectDirOf("new"));
        assertEquals(restored.toString(), sessionStore.projectDirOf("old"));
        assertPromptRoot("new", serverProject, restored);
        assertPromptRoot("old", restored, serverProject);
        assertEquals(
                Map.of("new", serverProject.toString(), "old", restored.toString()),
                started.stream()
                        .collect(toMap(
                                SessionStartedEvent::sessionId,
                                event -> event.eventContext().cwd())));
    }

    @Test
    void staleRestoredProjectFallsBackWithoutRewritingItsMarker() {
        final var missing = storageHome.resolve("missing");
        sessionStore.markProject("old", missing.toString());
        final var rpc = new WebJsonRpc(
                sessionStore,
                null,
                manager,
                null,
                null,
                null,
                configuration,
                null,
                null,
                null,
                null,
                null,
                runtimeFactory);

        rpc.submitPrompt("old", "continue", null);

        assertEquals(missing.toString(), sessionStore.projectDirOf("old"));
        assertPromptRoot("old", configuration.resolveWorkingDir(), missing);
    }

    @Test
    void setProjectRebuildsOnlyTheChangedSession() throws Exception {
        final var first = Files.createDirectories(storageHome.resolve("first"));
        final var second = Files.createDirectories(storageHome.resolve("second"));
        final var third = Files.createDirectories(storageHome.resolve("third"));
        sessionStore.markProject("a", first.toString());
        sessionStore.markProject("b", second.toString());
        final var a = manager.attach("a");
        final var b = manager.attach("b");
        final var rpc = new WebJsonRpc(
                sessionStore,
                null,
                manager,
                null,
                null,
                null,
                configuration,
                null,
                null,
                null,
                null,
                null,
                runtimeFactory);

        assertEquals(
                Map.of("ok", true, "project", third.toString()),
                rpc.setProject("a", third.resolve("..").resolve("third").toString()));

        assertTrue(a != manager.attach("a"), "changing projects must replace the runtime");
        assertTrue(b == manager.attach("b"), "another session must keep its runtime");
        assertPromptRoot("a", third, first);
        assertPromptRoot("b", second, third);
    }

    private void assertPromptRoot(final String id, final Path expected, final Path excluded) {
        final var prompt = manager.runtime(id).systemPromptBuilder().build();
        assertTrue(prompt.contains("- Working directory: " + expected + "\n"), prompt);
        assertTrue(!prompt.contains("- Working directory: " + excluded + "\n"), prompt);
    }

    @Test
    void invalidProjectDoesNotReplaceMarkerRuntimeOrSessionSettings() throws Exception {
        final var root = Files.createDirectories(storageHome.resolve("valid"));
        final var file = Files.writeString(storageHome.resolve("not-a-directory"), "file");
        sessionStore.markProject("a", root.toString());
        final var attached = manager.attach("a");
        final var settings = new ChatSettings("provider-a", "selected", null, null);
        attached.runtime().updateChatSettings(settings);
        final var rpc = completionRpc(null, new SlashRegistry(List.of()));

        for (final var invalid : new String[] {
            null,
            "",
            " ",
            "invalid\0root",
            file.toString(),
            storageHome.resolve("missing").toString()
        }) {
            assertEquals(false, rpc.setProject("a", invalid).get("ok"));
            assertEquals(root.toString(), sessionStore.projectDirOf("a"));
            assertSame(attached, manager.attach("a"));
            assertEquals(root, attached.runtime().workdir());
            assertEquals(settings, attached.runtime().chatSettings());
        }
    }

    @Test
    void appOperationsUseOneResolvedInvocationRootAndPreserveGlobalSessionListings() throws Exception {
        final var invocationRoot = configuration.resolveWorkingDir();
        final var other = Files.createDirectories(storageHome.resolve("other-project"));
        sessionStore.markProject("other", other.toString());
        final var calls = new ArrayList<Path>();
        final var service =
                new AgentSessionService(
                        null, null, null, sessionStore, null, null, null, null, configuration, null, null) {
                    @Override
                    public ToolListing tools(final String query, final Path root) {
                        calls.add(root);
                        return new ToolListing(
                                true, List.of(new ServerTools("global", List.of(new ToolInfo("tool", query)))), 1);
                    }

                    @Override
                    public MemoryContent memory(final Path root) {
                        calls.add(root);
                        return new MemoryContent(
                                "global",
                                "project",
                                "global-path",
                                root.resolve("memory").toString(),
                                true,
                                false);
                    }
                };
        try (final var graph = new StructuralCodeGraph() {
            @Override
            public String freshness(final Path root) {
                calls.add(root);
                return "ready";
            }

            @Override
            public String resync(final Path root) {
                calls.add(root);
                return "refreshed";
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void ensureRefreshedReady(final Path root) {
                calls.add(root);
            }

            @Override
            public CodeGraphIndex queryIndex(final Path root) {
                calls.add(root);
                return null;
            }
        }) {
            final var rpc = new WebJsonRpc(
                    sessionStore,
                    service,
                    manager,
                    null,
                    graph,
                    null,
                    configuration,
                    null,
                    null,
                    null,
                    null,
                    null,
                    runtimeFactory);
            assertFalse(
                    invocationRoot.equals(configuration.resolveProjectDir()),
                    "the project setting must not mask the invocation root");
            assertEquals(Map.of("project", invocationRoot.toString()), rpc.currentProject());
            assertEquals("global", rpc.tools("filter").servers().getFirst().server());
            assertEquals("global", rpc.memory().global());
            assertEquals(Map.of("enabled", true, "status", "ready"), rpc.codeGraphStatus());
            assertEquals(Map.of("status", "refreshed"), rpc.codeGraphResync(null));
            assertTrue(rpc.codeGraphResolve("Symbol", 1).hits().isEmpty());
            assertEquals(
                    List.of(
                            invocationRoot,
                            invocationRoot,
                            invocationRoot,
                            invocationRoot,
                            invocationRoot,
                            invocationRoot),
                    calls);
            assertEquals(Map.of("status", "refreshed"), rpc.codeGraphResync(other.toString()));
            assertEquals(other, calls.getLast());

            rpc.submitPrompt("new", "prompt", null);
            assertEquals(invocationRoot.toString(), sessionStore.projectDirOf("new"));
            assertEquals(invocationRoot, manager.runtime("new").workdir());
            assertEquals(other, manager.runtime("other").workdir());
            assertEquals(Map.of("project", invocationRoot.toString()), rpc.currentProject());
            assertEquals(
                    Map.of("new", invocationRoot.toString(), "other", other.toString()),
                    rpc.listSessions(null, "").stream()
                            .collect(toMap(WebJsonRpc.SessionSummary::id, WebJsonRpc.SessionSummary::project)));
            assertEquals(1, defaultRootResolutions.get(), "downstream app operations must not resolve defaults again");
        }
    }

    @Test
    void completionsFollowRuntimeFilesModelsAndVariantsAcrossABA() throws Exception {
        final var a = completionSession("a");
        final var b = completionSession("b");
        final var registry = new SlashRegistry(List.of(() -> List.of(
                completionCommand("context", ArgCompletion.PATH),
                completionCommand("model", ArgCompletion.from("models", ArgCompletion.Env::models)),
                completionCommand("variant", ArgCompletion.from("variants", ArgCompletion.Env::variants)),
                completionCommand("typedvariant", ArgCompletion.from("variants", env -> env.variants("typed"))))));
        try (final var client = new CompletionChatClient()) {
            final var rpc = completionRpc(client, registry);
            // A marker can change independently of a live runtime; completions must use the runtime.
            sessionStore.replaceProject("a", b.toString());
            for (final var id : List.of("a", "b", "a")) {
                assertEquals(
                        List.of("entry-" + id),
                        rpc.slashComplete(id, "/context entry", 14).getFirst().items());
                assertEquals(
                        List.of(id + "-selected"),
                        rpc.slashComplete(id, "/model ", 7).getFirst().items());
                assertEquals(
                        List.of(id + "-selected-variant", "default"),
                        rpc.slashComplete(id, "/variant ", 9).getFirst().items());
                assertEquals(
                        List.of(id + "-typed-variant", "default"),
                        rpc.slashComplete(id, "/typedvariant ", 14).getFirst().items());
            }
            assertEquals(a, manager.runtime("a").workdir());
            assertThrows(IllegalArgumentException.class, () -> rpc.slashComplete(null, "/model ", 7));
            assertThrows(IllegalArgumentException.class, () -> rpc.slashComplete(" ", "/model ", 7));
        }
    }

    @Test
    void slashCompletionWireRequiresSessionIdentity() throws Exception {
        completionSession("a");
        final var registry =
                new SlashRegistry(List.of(() -> List.of(completionCommand("context", ArgCompletion.PATH))));
        final var rpc = completionRpc(null, registry);
        try (final var bindings = WebBindings.create(rpc, mapper);
                final var methods = bindings.lookups(JsonRpcMethod.class)) {
            final var method = methods.instance().stream()
                    .filter(candidate -> candidate.name().equals("slash.complete"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    rpc.slashComplete("a", "/context entry", 14),
                    method.invoke(new JsonRpcMethod.Context(
                                    null, Map.of("id", "a", "text", "/context entry", "cursor", 14)))
                            .toCompletableFuture()
                            .join());
            assertThrows(
                    RuntimeException.class,
                    () -> method.invoke(new JsonRpcMethod.Context(null, Map.of("text", "/context entry", "cursor", 14)))
                            .toCompletableFuture()
                            .join());
        }
    }

    @Test
    void concurrentCompletionsCaptureEachRuntimeRootAndSettingsBeforeCompletionRuns() throws Exception {
        completionSession("a");
        completionSession("b");
        final var entered = new CountDownLatch(2);
        final var release = new CountDownLatch(1);
        final var registry = new SlashRegistry(List.of(() -> List.of(completionCommand("probe", (arg, token, env) -> {
            entered.countDown();
            await(release);
            final var items = new ArrayList<>(env.models());
            items.addAll(env.paths(token));
            return ArgCompletion.Result.of("probe", items);
        }))));
        try (final var client = new CompletionChatClient();
                final var executor = Executors.newFixedThreadPool(2)) {
            final var rpc = completionRpc(client, registry);
            final var a = executor.submit(() -> rpc.slashComplete("a", "/probe entry", 12));
            final var b = executor.submit(() -> rpc.slashComplete("b", "/probe entry", 12));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS), "both requests must be in flight");
                manager.runtime("a").updateChatSettings(new ChatSettings("changed", "new", null, null));
                manager.runtime("b").updateChatSettings(new ChatSettings("changed", "other", null, null));
            } finally {
                release.countDown();
            }
            assertEquals(
                    List.of("a-selected", "entry-a"),
                    a.get(5, TimeUnit.SECONDS).getFirst().items());
            assertEquals(
                    List.of("b-selected", "entry-b"),
                    b.get(5, TimeUnit.SECONDS).getFirst().items());
            assertEquals(
                    List.of("changed-new", "entry-a"),
                    rpc.slashComplete("a", "/probe entry", 12).getFirst().items());
        }
    }

    private Path completionSession(final String id) throws Exception {
        final var root = Files.createDirectories(storageHome.resolve("completion-" + id));
        Files.writeString(root.resolve("entry-" + id), id);
        sessionStore.markProject(id, root.toString());
        manager.runtime(id).updateChatSettings(new ChatSettings(id, "selected", null, null));
        return root;
    }

    private WebJsonRpc completionRpc(final ChatClient client, final SlashRegistry registry) {
        return new WebJsonRpc(
                sessionStore,
                null,
                manager,
                null,
                null,
                null,
                configuration,
                new WebSlashCommands(registry, null, sessionStore),
                client,
                null,
                null,
                null,
                runtimeFactory);
    }

    private static SlashCommand completionCommand(final String name, final ArgCompletion completion) {
        return SlashCommand.of(
                new SlashCommandSpec("/" + name, null, "complete " + name, SlashGroup.OTHER),
                completion,
                (arg, env) -> CompletableFuture.completedStage(ShellOutcome.none()));
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "completion was not released");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class CompletionChatClient extends ChatClient {
        @Override
        public ModelsResponse models(final ChatSettings settings) {
            return new ModelsResponse(
                    List.of(new ModelsResponse.Model(settings.providerId() + "-" + settings.model(), null, Map.of())));
        }

        @Override
        public List<ModelVariantConfiguration> variants(final ChatSettings settings) {
            return List.of(new ModelVariantConfiguration(
                    settings.providerId() + "-" + settings.model() + "-variant", null, null, null, null, null, null));
        }

        @Override
        public ModelVariantConfiguration implicitVariant(final ChatSettings settings) {
            return new ModelVariantConfiguration("default", 1.0, null, null, null, null, null);
        }
    }

    @Test
    void freshSubmitBroadcastsAUserEventForThePrompt() {
        manager.attach("s1");

        manager.submit("s1", "hello there", null);

        final var frame = broker.frames.poll();
        assertNotNull(frame, "a user frame must be broadcast for a fresh submit");
        assertTrue(frame.contains("\"type\":\"user\""), frame);
        assertTrue(frame.contains("\"message\":\"hello there\""), frame);
        // the prompt was handed to the turn runner (not queued)
        assertEquals("hello there", turnRunner.lastPrompt);
        assertEquals(0, manager.queuedPromptCount("s1"));
    }

    @Test
    void queuedSubmitBroadcastsAUserEventAndEnqueuesInsteadOfStartingATurn() {
        // mark the session busy (simulating a running turn)
        manager.attach("s1").env().session().busy(true);

        manager.submit("s1", "follow up meanwhile", null);

        final var frame = broker.frames.poll();
        assertNotNull(frame, "a user frame must be broadcast for a queued submit");
        assertTrue(frame.contains("\"type\":\"user\""), frame);
        assertTrue(frame.contains("\"message\":\"follow up meanwhile\""), frame);
        // the turn runner was NOT invoked: the prompt is queued for the next checkpoint
        assertNull(turnRunner.lastPrompt);
        assertEquals(1, manager.queuedPromptCount("s1"));
    }

    @Test
    void resumeDoesNotBroadcastAUserEvent() {
        manager.attach("s1");

        // prompt == null means resume: no new user turn, so no user broadcast
        final var future = manager.submit("s1", null, null);
        assertEquals(Boolean.TRUE, future.join());
        assertEquals(0, broker.frames.size(), "resume must not broadcast a user event");
        // resume reaches the turn runner with a null prompt (does not start a fresh user turn)
        assertNull(turnRunner.lastPrompt);
    }

    @Test
    void slashCommandIsNotBroadcastAsAUserEvent() {
        manager.attach("s1");
        manager.submit("s1", "/help", null).join();
        // slash commands route to the lifecycle/slash path, never as a user turn
        assertTrue(
                broker.frames.stream().noneMatch(frame -> frame.contains("\"type\":\"user\"")),
                "slash commands must not broadcast a user event");
    }

    @Test
    void slashPathsArePromptsForBothSubmitAndComposer() {
        manager.attach("s1");
        assertTrue(manager.submit("s1", "/tmp/file.txt inspect", "reviewer").join());
        assertEquals("/tmp/file.txt inspect", turnRunner.lastPrompt);
        assertEquals("reviewer", turnRunner.lastAgent);
        assertEquals("accepted", manager.input("s1", "/help/topic inspect").join());
        assertEquals("/help/topic inspect", turnRunner.lastPrompt);
        assertTrue(broker.requests.isEmpty());
    }

    @Test
    void composerDispatchesKnownCommandsWithoutSubmittingAPrompt() {
        manager.attach("s1");
        assertEquals("accepted", manager.input("s1", "/tabs").join());
        assertNull(turnRunner.lastPrompt);
        assertTrue(broker.frames.stream().noneMatch(frame -> frame.contains("\"type\":\"user\"")));
        assertTrue(broker.frames.stream().anyMatch(frame -> frame.contains("\"command\":\"tabs\"")));
    }

    @Test
    void composerWaitsForRoutingBeforeQueueingAndDoesNotReclassifyAcceptedInput() {
        manager.attach("s1").env().session().busy(true);
        final var future = manager.input("s1", "/unknown inspect");
        assertFalse(future.isDone());
        assertEquals(0, manager.queuedPromptCount("s1"));
        assertTrue(broker.frames.stream().noneMatch(frame -> frame.contains("\"type\":\"user\"")));

        final var request = (Map<?, ?>) broker.requests.remove();
        final var params = (Map<?, ?>) request.get("params");
        final var options = (List<?>) params.get("options");
        assertTrue(manager.resolve("s1", String.valueOf(request.get("id")), options.getFirst()));
        assertEquals("accepted", future.join());
        assertEquals(List.of("/unknown inspect"), manager.queuedPrompts("s1"));
        assertNull(turnRunner.lastPrompt);
        assertTrue(broker.requests.isEmpty(), "accepted input must bypass a second routing picker");
    }

    @Test
    void correctionAndCancellationDoNotMutatePromptState() {
        manager.attach("s1").env().session().busy(true);
        final var correction = manager.input("s1", "/unknown");
        final var request = (Map<?, ?>) broker.requests.remove();
        final var options = (List<?>) ((Map<?, ?>) request.get("params")).get("options");
        manager.resolve("s1", String.valueOf(request.get("id")), options.get(1));
        assertEquals("correct", correction.join());

        final var cancellation = manager.input("s1", "/unknown");
        final var cancelRequest = (Map<?, ?>) broker.requests.remove();
        manager.resolve("s1", String.valueOf(cancelRequest.get("id")), null);
        assertEquals("cancel", cancellation.join());
        assertEquals(0, manager.queuedPromptCount("s1"));
        assertNull(turnRunner.lastPrompt);
        assertTrue(broker.frames.stream().noneMatch(frame -> frame.contains("\"type\":\"user\"")));
    }

    @Test
    void submitAlsoWaitsForAmbiguousRoutingAndPreservesAgent() {
        manager.attach("s1");
        final var future = manager.submit("s1", "/unknown", "reviewer");
        assertFalse(future.isDone());
        assertNull(turnRunner.lastPrompt);
        final var request = (Map<?, ?>) broker.requests.remove();
        final var options = (List<?>) ((Map<?, ?>) request.get("params")).get("options");
        manager.resolve("s1", String.valueOf(request.get("id")), options.getFirst());
        assertTrue(future.join());
        assertEquals("/unknown", turnRunner.lastPrompt);
        assertEquals("reviewer", turnRunner.lastAgent);
        assertTrue(broker.requests.isEmpty());
    }

    @Test
    void composerAcceptsWithoutWaitingForTheAgentTurn() {
        manager.attach("s1");
        turnRunner.result = new CompletableFuture<>();
        final var input = manager.input("s1", "inspect this");
        assertTrue(input.isDone());
        assertEquals("accepted", input.join());
        assertEquals("inspect this", turnRunner.lastPrompt);
        assertFalse(turnRunner.result.isDone());
        turnRunner.result.complete(true);
    }

    @Test
    void explicitSlashEndpointRemainsStrict() {
        manager.attach("s1");
        manager.slash("s1", "/unknown").join();
        assertTrue(broker.requests.isEmpty(), "explicit slash dispatch must not ask how to route input");
        assertNull(turnRunner.lastPrompt);
        assertEquals(0, manager.queuedPromptCount("s1"));
        assertTrue(broker.frames.stream().noneMatch(frame -> frame.contains("\"type\":\"user\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "correct", "cancel"})
    void inputRpcReturnsRoutingDisposition(final String disposition) throws Exception {
        final var rpc = new WebJsonRpc(
                sessionStore,
                null,
                manager,
                null,
                null,
                null,
                configuration,
                null,
                null,
                null,
                null,
                null,
                runtimeFactory);
        try (final var bindings = WebBindings.create(rpc, mapper);
                final var methods = bindings.lookups(JsonRpcMethod.class)) {
            final var method = methods.instance().stream()
                    .filter(candidate -> "session.input".equals(candidate.name()))
                    .findFirst()
                    .orElseThrow();
            final var result = method.invoke(new JsonRpcMethod.Context(null, Map.of("id", "s1", "text", "/unknown")))
                    .toCompletableFuture();
            assertFalse(result.isDone());
            final var request = (Map<?, ?>) broker.requests.remove();
            final var options = (List<?>) ((Map<?, ?>) request.get("params")).get("options");
            final var selection =
                    switch (disposition) {
                        case "accepted" -> options.getFirst();
                        case "correct" -> options.get(1);
                        default -> null;
                    };
            manager.resolve("s1", String.valueOf(request.get("id")), selection);
            final var response = result.get(5, TimeUnit.SECONDS);
            assertEquals(new WebJsonRpc.InputResult(disposition), response);
            assertEquals(mapper.toString(Map.of("disposition", disposition)), mapper.toString(response));
            assertEquals("accepted".equals(disposition) ? "/unknown" : null, turnRunner.lastPrompt);
        }
    }

    @Test
    void reconnectSnapshotRestoresDisplaySettings() {
        final var session = manager.attach("s1").env().session();
        assertEquals(DiffViewMode.SIMPLE, session.diffViewMode());
        assertEquals(
                "simple",
                manager.snapshotFor("s1").stream()
                        .filter(event -> "settings".equals(event.type()))
                        .findFirst()
                        .orElseThrow()
                        .data()
                        .get("diffViewMode"));
        session.diffViewMode(DiffViewMode.SIDE_BY_SIDE);
        session.showThinking(false).showToolOutput(ToolOutputVisibility.HIDDEN);
        final var settings = manager.snapshotFor("s1").stream()
                .filter(event -> "settings".equals(event.type()))
                .findFirst()
                .orElseThrow()
                .data();
        assertEquals(false, settings.get("showThinking"));
        assertEquals("hidden", settings.get("showToolOutput"));
        assertEquals("side-by-side", settings.get("diffViewMode"));
        assertEquals(true, settings.get("metricsPerTurn"));
        assertEquals("compact", settings.get("metricsMode"));
    }

    private static final class NoopShellRegistry extends ShellRegistry {
        private NoopShellRegistry() {
            super(null);
        }

        @Override
        public void register(final String sessionId, final ShellEnv env) {
            // no-op
        }

        @Override
        public void unregister(final String sessionId, final ShellEnv env) {
            // no-op
        }
    }

    /** Captures every {@link #broadcast} frame for assertions. */
    private static final class FakeSseBroker extends SseBroker {
        private final Queue<String> frames = new ConcurrentLinkedQueue<>();
        private final Queue<Object> requests = new ConcurrentLinkedQueue<>();

        private FakeSseBroker(final JsonMapper mapper) {
            super(mapper);
        }

        @Override
        public void broadcast(final String sessionId, final String frame) {
            frames.add(frame);
        }

        @Override
        public void pushRpc(final String sessionId, final Object request) {
            requests.add(request);
        }
    }

    /** Records the latest {@link TurnRequest} prompt without running a real turn. */
    private static final class FakeTurnRunner extends TurnRunner {
        private volatile String lastPrompt;
        private volatile String lastAgent;
        private CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);

        private FakeTurnRunner() {
            super();
        }

        @Override
        public CompletionStage<Boolean> submit(final TurnRequest request) {
            this.lastPrompt = request.prompt();
            this.lastAgent = request.agentName();
            return result;
        }
    }
}
