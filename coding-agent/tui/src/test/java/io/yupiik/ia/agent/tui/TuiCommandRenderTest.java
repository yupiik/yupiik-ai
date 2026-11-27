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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.tui.event.ResizeEvent;
import dev.tamboui.widget.Widget;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.cli.AskUserAnswersFormatter;
import io.yupiik.ia.agent.core.cli.FormatDatePromptHelper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxConfiguration;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.InBandToolCallExtractor;
import io.yupiik.ia.agent.core.llm.MarkupToolCallScanner;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.sandbox.SessionSandbox;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.btw.BtwSlashCommands;
import io.yupiik.ia.agent.core.shell.slash.ConfigCommands;
import io.yupiik.ia.agent.core.shell.slash.InspectCommands;
import io.yupiik.ia.agent.core.shell.slash.MaintainCommands;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.style.ModelStyleService;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.FileMutation;
import io.yupiik.ia.agent.core.wiring.AutoLogConfigurer;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.core.wiring.SessionAwareLogRecord;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Renders the TUI into a {@link HeadlessBackend} and asserts on the visible
 * screen while driving it with dispatched events.
 */
// shares the "io.yupiik-jul-handler" lock with TuiCommandHeadlessTest: both boot a real
// TuiCommand, which hijacks the JVM-wide "io.yupiik" java.util.logging logger and gives up when
// another handler is already installed. See TuiCommand.redirectLoggerToUi.
@ResourceLock("io.yupiik-jul-handler")
@ExtendWith(TestJsonMapper.class)
class TuiCommandRenderTest {
    @TempDir
    private static Path eventWorkdir;

    @TempDir
    private static Path testWorkdir;

    private static final long TIMEOUT_MS = Duration.ofSeconds(5).toMillis();
    private static final Duration TICK_RATE = Duration.ofMillis(2);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(2);
    /**
     * How long {@link #settledCursor} waits for a second, confirming frame after its first
     * sighting. The renderer only repaints on change (no idle tick), so a confirming frame - when
     * the layout is still reflowing - arrives within a few ticks; once the screen is quiescent the
     * awaited state is already stable and paying a long window here would just be dead time.
     */
    private static final long SETTLED_CURSOR_CONFIRM_MS = 100;

    private MockLlmServer llm;
    private MockLlmServer llm2;
    private Function<String, AgentLoop> sessionAgentLoopFactory;
    private Boolean sandboxEnabled;
    private HeadlessBackend backend;
    private long textPollMillis = 50;
    private CountDownLatch quietTextPoll;
    private final AtomicReference<TuiRunner> tuiRef = new AtomicReference<>();
    private Thread uiThread;
    private Duration tickRate = TICK_RATE;
    private final BlockingQueue<String> interactionAlerts = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> interactionAcknowledgements = new LinkedBlockingQueue<>();
    private final NotificationService notifications = new NotificationService(null, null) {
        @Override
        public void onSessionInteraction(final String sessionId) {
            interactionAcknowledgements.add(sessionId);
        }

        @Override
        public void onInputRequired(final String session, final String title, final int count, final String source) {
            interactionAlerts.add(session + ":input:" + count + ":" + source);
        }

        @Override
        public void onApprovalRequired(
                final String session,
                final String title,
                final String tool,
                final String source,
                final ApprovalGate.Origin origin) {
            interactionAlerts.add(session + ":approval:" + tool + ":" + source);
        }
    };

    private JsonMapper formatMapper;
    /** The scenario's injected configuration, shared by the command and its fixture collaborators. */
    private CodingAgentConfiguration tuiConfig;

    private final List<String> sessionAppends = new ArrayList<>();
    private final List<ExtendedHttpClient> httpClients = new ArrayList<>();
    private final List<ChatClient> chatClients = new ArrayList<>();

    private void start(
            final JsonMapper mapper,
            final CodingAgentConfiguration configuration,
            final MockLlmServer primary,
            final MockLlmServer secondary) {
        formatMapper = mapper;
        llm = primary;
        llm2 = secondary;
        llm.answer("hello from mock");
        llm.requests().clear();
        if (llm2 != null) {
            llm2.answer("hello from mock");
            llm2.requests().clear();
        }
        tuiConfig = configuration;
        assertEquals(
                llm.baseUrl(), configuration.provider().providers().getFirst().baseUrl());
        backend = new HeadlessBackend();
    }

    @AfterEach
    void stop() throws InterruptedException {
        final var tui = tuiRef.get();
        if (tui != null && tui.isRunning()) {
            tui.quit();
        }
        if (uiThread != null) {
            uiThread.join(TIMEOUT_MS);
        }
        chatClients.forEach(ChatClient::close);
        httpClients.forEach(ExtendedHttpClient::close);
    }

    private static boolean awaitSubmitted(final AtomicReference<String> received) throws InterruptedException {
        final var deadline = System.nanoTime() + Duration.ofMillis(300).toNanos();
        while (received.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return received.get() != null;
    }

    private static String sid(final ShellRegistry registry) {
        return registry.sessions().iterator().next();
    }

    private record TurnFrame(
            List<Object> chunks,
            List<String> rows,
            List<UiState.ToolTop> toolTops,
            List<UiState.FooterLink> footerLinks,
            List<UiState.RunHeader> runHeaders,
            String lastCodeBlock) {}

    private TurnFrame renderTurnFrame(
            final TuiCommand command, final UiState state, final String source, final int width) throws Exception {
        final var render = TuiCommand.class.getDeclaredMethod(
                "contentChunks",
                List.class,
                String.class,
                int.class,
                UiState.class,
                List.class,
                List.class,
                List.class,
                List.class);
        render.setAccessible(true);
        final var chunks = new ArrayList<Object>();
        final var rows = new ArrayList<String>();
        final var toolTops = new ArrayList<UiState.ToolTop>();
        final var footerLinks = new ArrayList<UiState.FooterLink>();
        final var runHeaders = new ArrayList<UiState.RunHeader>();
        render.invoke(command, chunks, source, width, state, toolTops, footerLinks, runHeaders, rows);
        return new TurnFrame(chunks, rows, toolTops, footerLinks, runHeaders, state.lastCodeBlock);
    }

    private void assertHistoricalChunksSame(final TurnFrame before, final TurnFrame after, final int count) {
        assertEquals(before.chunks().size(), after.chunks().size());
        for (var i = 0; i < count; i++) {
            Assertions.assertSame(before.chunks().get(i), after.chunks().get(i), "historical turn " + i);
        }
    }

    private void clearTurnRenderCaches(final TuiCommand command) throws Exception {
        final var clear = TuiCommand.class.getDeclaredMethod("clearRenderCaches");
        clear.setAccessible(true);
        clear.invoke(command);
    }

    private void assertTurnFrameMatchesCold(
            final TuiCommand command, final UiState state, final String source, final int width, final TurnFrame warm)
            throws Exception {
        clearTurnRenderCaches(command);
        final var cold = renderTurnFrame(command, state, source, width);
        assertEquals(cold.rows(), warm.rows(), "cached and cold search/copy rows");
        assertEquals(cold.toolTops(), warm.toolTops());
        assertEquals(cold.footerLinks(), warm.footerLinks());
        assertEquals(cold.runHeaders(), warm.runHeaders());
        assertEquals(cold.lastCodeBlock(), warm.lastCodeBlock());
        assertEquals(cold.chunks().size(), warm.chunks().size());
        for (var i = 0; i < warm.chunks().size(); i++) {
            for (final var field : List.of("height", "rowKinds", "box")) {
                final var accessor = warm.chunks().get(i).getClass().getDeclaredMethod(field);
                accessor.setAccessible(true);
                final var expected = accessor.invoke(cold.chunks().get(i));
                final var actual = accessor.invoke(warm.chunks().get(i));
                if (actual instanceof Object[] array) {
                    Assertions.assertArrayEquals((Object[]) expected, array, "chunk " + i + ": " + field);
                } else {
                    assertEquals(expected, actual, "chunk " + i + ": " + field);
                }
            }
            final var widget = warm.chunks().get(i).getClass().getDeclaredMethod("widget", int.class);
            widget.setAccessible(true);
            final var height = warm.chunks().get(i).getClass().getDeclaredMethod("height");
            height.setAccessible(true);
            final var area =
                    new Rect(0, 0, width + 2, (int) height.invoke(warm.chunks().get(i)));
            final var expected = Buffer.empty(area);
            final var actual = Buffer.empty(area);
            ((Widget) widget.invoke(cold.chunks().get(i), 0)).render(area, expected);
            ((Widget) widget.invoke(warm.chunks().get(i), 0)).render(area, actual);
            for (var y = 0; y < area.height(); y++) {
                for (var x = 0; x < area.width(); x++) {
                    assertEquals(expected.get(x, y).symbol(), actual.get(x, y).symbol(), "rendered symbol");
                    assertEquals(expected.get(x, y).style(), actual.get(x, y).style(), "rendered style");
                    assertEquals(
                            expected.get(x, y).isContinuation(),
                            actual.get(x, y).isContinuation());
                }
            }
        }
    }

    /** Count of Unicode block fill chars in a row - the extent of the consumed-share bar. */
    private static int blockCount(final String row) {
        int count = 0;
        for (int i = 0; i < row.length(); i++) {
            if (isGaugeBlock(row.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isGaugeBlock(final char c) {
        return c == '\u2588'
                || c == '\u258f'
                || c == '\u258e'
                || c == '\u258d'
                || c == '\u258c'
                || c == '\u258b'
                || c == '\u258a'
                || c == '\u2589';
    }

    private AgentLoop stubAgentLoop() {
        return new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                return "ok";
            }

            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled,
                    final Supplier<String> queuedPrompt) {
                return run(prompt, agentName, sessionId, history, cancelled);
            }
        };
    }

    private TuiCommand command(final ShellRegistry shellRegistry, final AgentLoop agentLoop) {
        return command(shellRegistry, agentLoop, false, new UsageTracker(null, null, null, Clock.systemUTC()));
    }

    private TuiCommand command(
            final ShellRegistry shellRegistry,
            final AgentLoop agentLoop,
            final boolean showThinking,
            final UsageTracker usageTracker) {
        final var mapper = formatMapper;
        return command(shellRegistry, agentLoop, showThinking, usageTracker, new TodoStore(tuiConfig, mapper));
    }

    private TuiCommand command(
            final ShellRegistry shellRegistry,
            final AgentLoop agentLoop,
            final boolean showThinking,
            final UsageTracker usageTracker,
            final TodoStore todoStore) {
        return command(shellRegistry, agentLoop, showThinking, usageTracker, todoStore, Clock.systemUTC());
    }

    private TuiCommand command(
            final ShellRegistry shellRegistry,
            final AgentLoop agentLoop,
            final boolean showThinking,
            final UsageTracker usageTracker,
            final TodoStore todoStore,
            final Clock clock) {
        final var mapper = formatMapper;
        final var configuration = tuiConfig;
        return command(
                shellRegistry,
                agentLoop,
                showThinking,
                usageTracker,
                todoStore,
                clock,
                new SubagentRegistry(configuration, new ProviderManager(configuration, mapper)));
    }

    private TuiCommand command(
            final ShellRegistry shellRegistry,
            final AgentLoop agentLoop,
            final boolean showThinking,
            final UsageTracker usageTracker,
            final TodoStore todoStore,
            final Clock clock,
            final SubagentRegistry subagentRegistry) {
        return command(
                shellRegistry,
                agentLoop,
                showThinking,
                usageTracker,
                todoStore,
                clock,
                subagentRegistry,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    private TuiCommand command(
            final ShellRegistry shellRegistry,
            final AgentLoop agentLoop,
            final boolean showThinking,
            final UsageTracker usageTracker,
            final TodoStore todoStore,
            final Clock clock,
            final SubagentRegistry subagentRegistry,
            final ExecutorService executor) {
        return command(
                shellRegistry,
                agentLoop,
                showThinking,
                usageTracker,
                todoStore,
                clock,
                subagentRegistry,
                executor,
                new SessionStore() {
                    @Override
                    public String create() {
                        return "s-render";
                    }

                    @Override
                    public void append(final String id, final SessionEntry entry) {
                        sessionAppends.add(String.valueOf(entry));
                    }

                    @Override
                    public List<Message> load(final String id) {
                        return List.of();
                    }

                    @Override
                    public void delete(final String id) {
                        // no-op: render tests never delete a real session
                    }
                });
    }

    private TuiCommand command(
            final ShellRegistry shellRegistry,
            final AgentLoop agentLoop,
            final boolean showThinking,
            final UsageTracker usageTracker,
            final TodoStore todoStore,
            final Clock clock,
            final SubagentRegistry subagentRegistry,
            final ExecutorService executor,
            final SessionStore sessionStore) {
        final var mapper = formatMapper;
        final var configuration = tuiConfig;
        final var conf = new TuiCommand.TuiConf("", "", KeyBindingPreset.STANDARD, showThinking, "dark");
        final var tuiConfiguration = new TuiConfiguration(
                null, "#9d7cd8", "#fab283", "#7fd88f", "#f5a742", "#e06c75", "#b896d8", "#eeeeee", "#808080", -1.0,
                0.22, 0.14, 0.85, "#484848", "#606060", "#141414", "auto", "auto", 4000L, 3500L, true, "unicode",
                "auto", "auto", List.of(), null, null, true, 3, 10, "", 0.8, "#ff5555", null, null);
        final var httpClient =
                new HttpClientProducer().httpClient(configuration, new NativeProviderConfiguration(null, null));
        httpClients.add(httpClient);
        final var chatClient =
                new ChatClient(configuration, mapper, new ProviderManager(configuration, mapper), clock, httpClient);
        chatClients.add(chatClient);
        final var mcpManager = new McpManager(configuration, mapper, List.of(), httpClient, new ProcessService());
        final var factory = new TuiRunnerFactory() {
            @Override
            public TuiRunner create(final TuiCommand.TuiConf tuiConf, final boolean hideCursor) throws Exception {
                final var runner = TuiRunner.create(TuiConfig.builder()
                        .backend(backend)
                        .mouseCapture(false)
                        .rawMode(false)
                        .alternateScreen(false)
                        .bracketedPaste(false)
                        .shutdownHook(false)
                        // the tick *is* the latency of every assertion in this class: a mutation
                        // posted from a background thread (runLater) only paints on the next tick,
                        // so each awaitFrame below costs at least one tick. 20ms made the 48 tests
                        // of this class cost seconds for no added coverage - the clock is a
                        // MutableClock, so elapsed-time rendering does not depend on this value.
                        .tickRate(tickRate)
                        // the event loop waits at most pollTimeout between two runs, so this is
                        // also how long quit() takes to be noticed: the default 40ms was paid by
                        // every single test when joining the ui thread
                        .pollTimeout(POLL_TIMEOUT)
                        .build());
                tuiRef.set(runner);
                return runner;
            }
        };
        final var tabManager = new TabManager();
        final var tuiSlashCommands = new TuiSlashCommands(tabManager);
        final var providerManager = new ProviderManager(configuration, mapper);
        // one shared permission service like in production: the TUI and the slash
        // commands must observe the same mode for the gutter to follow /mode
        final var permissionService = new PermissionService(configuration, (configuration).resolveWorkingDir());
        final var agentSessionService = new AgentSessionService(
                chatClient,
                mcpManager,
                new AgentProvider(configuration),
                sessionStore,
                null,
                null,
                null,
                null,
                configuration,
                null);
        final var configCommands = new ConfigCommands(
                agentSessionService,
                chatClient,
                providerManager,
                new AgentProvider(configuration),
                null, // SecretSessionModes
                null, // SecretRedactor
                null); // CodexLoginService
        final var btwAnswerer = new BtwAnswerer(
                chatClient,
                sessionStore,
                configuration,
                Executors.newVirtualThreadPerTaskExecutor(),
                new InBandToolCallExtractor(new MarkupToolCallScanner()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                httpClient,
                null);
        final var mavenKnowledgeService = new MavenRepositoryKnowledgeService(
                configuration, null, Executors.newVirtualThreadPerTaskExecutor(), null);
        // Classification must see the real catalogs, including commands intercepted by TUI modals.
        final var slashRegistry = new SlashRegistry(List.of(
                tuiSlashCommands,
                configCommands,
                new BtwSlashCommands(btwAnswerer),
                new InspectCommands(agentSessionService),
                new MaintainCommands(agentSessionService, mcpManager, mavenKnowledgeService)));
        return new TuiCommand(
                conf,
                tuiConfiguration,
                configuration,
                shellRegistry,
                new SessionRuntimeFactory() {
                    @Override
                    public Path resolveWorkdir(final Path explicitWorkdir, final String resumedSessionId) {
                        return explicitWorkdir == null ? testWorkdir : explicitWorkdir;
                    }

                    @Override
                    public SessionRuntime create(final String sessionId, final Path workdir, final Boolean sandbox) {
                        return new SessionRuntime(
                                sessionAgentLoopFactory == null ? agentLoop : sessionAgentLoopFactory.apply(sessionId),
                                permissionService,
                                new UserFeedbackService(),
                                usageTracker,
                                todoStore,
                                new StatusReporter(),
                                null,
                                null,
                                workdir,
                                sandboxEnabled == null
                                        ? new DisabledSandbox(workdir)
                                        : new SessionSandbox(
                                                new SandboxConfiguration(
                                                        sandboxEnabled,
                                                        configuration.sandbox().helper(),
                                                        configuration.sandbox().filesystem(),
                                                        configuration.sandbox().network(),
                                                        configuration.sandbox().trustedIntegrations(),
                                                        configuration.sandbox().macOs(),
                                                        configuration.sandbox().environment(),
                                                        configuration.sandbox().unix()),
                                                workdir,
                                                workdir,
                                                permissionService,
                                                testWorkdir.resolve("sandbox-scratch")));
                    }
                },
                sessionStore,
                new SessionLifecycle(sessionStore),
                chatClient,
                new ModelStyleService(configuration, chatClient),
                new SpeakerLabels(List.of(new FormatDatePromptHelper(clock)), configuration, chatClient),
                new AutoLogConfigurer(configuration),
                mcpManager,
                executor,
                providerManager,
                new AgentProvider(configuration),
                agentSessionService,
                new TurnRunner(mavenKnowledgeService, Executors.newVirtualThreadPerTaskExecutor(), agentSessionService),
                new ThinkTagSplitter(),
                factory,
                tuiSlashCommands,
                slashRegistry,
                clock,
                new AskUserCardRenderer(new AskUserAnswersFormatter(formatMapper)),
                new ToolOutputFormat(formatMapper),
                btwAnswerer,
                tabManager,
                subagentRegistry,
                new ScrollService(tuiConfiguration),
                null,
                formatMapper,
                notifications,
                null);
    }

    /** Waits until the cell at the coordinate is rendered in reverse video. */
    private void awaitReversed(final int x, final int y) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (!backend.reversed(x, y)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("cell (" + x + "," + y + ") never reversed; last screen:\n" + backend.text());
            }
            assertTrue(
                    backend.awaitFrame(1_000), "no frame received while waiting for reversal at (" + x + "," + y + ")");
        }
    }

    /** Waits until the cell at the coordinate is no longer rendered in reverse video. */
    private void awaitReversedGone(final int x, final int y) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (backend.reversed(x, y)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "cell (" + x + "," + y + ") never un-reversed; last screen:\n" + backend.text());
            }
            assertTrue(
                    backend.awaitFrame(1_000),
                    "no frame received while waiting for un-reversal at (" + x + "," + y + ")");
        }
    }

    /** Counts non-overlapping occurrences of {@code token} on the current screen. */
    private int occurrences(final String token) {
        final var text = backend.text();
        var count = 0;
        var from = 0;
        while (true) {
            final var idx = text.indexOf(token, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + token.length();
        }
    }

    /** Waits until {@code token} appears exactly {@code expected} times on screen. */
    private void awaitOccurrences(final String token, final int expected) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (occurrences(token) != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected " + expected + " occurrence(s) of '" + token + "' within "
                        + TIMEOUT_MS + "ms; last screen:\n" + backend.text());
            }
            assertTrue(backend.awaitFrame(1_000), "no frame received while waiting for '" + token + "' ×" + expected);
        }
    }

    /** True when a *second* distinct row carries the token below the given row. */
    private boolean secondHeaderIsBelow(final int firstRow, final String token) throws InterruptedException {
        while (true) {
            final var lines = backend.text().split("\n");
            for (var y = firstRow + 1; y < lines.length; y++) {
                if (lines[y].contains(token)) {
                    return true;
                }
            }
            assertTrue(
                    backend.awaitFrame(1_000),
                    "no frame received while waiting for a second '" + token + "' below " + firstRow + "\n"
                            + backend.text());
        }
    }

    /** The row of the first occurrence of the token strictly below {@code aboveRow}. */
    private int rowOfBelow(final int aboveRow, final String token) throws InterruptedException {
        while (true) {
            final var lines = backend.text().split("\n");
            for (var y = aboveRow + 1; y < lines.length; y++) {
                if (lines[y].contains(token)) {
                    return y;
                }
            }
            assertTrue(
                    backend.awaitFrame(1_000), "no frame received while waiting for '" + token + "' below " + aboveRow);
        }
    }

    private ShellRegistry startDragSelection(final boolean modal) throws Exception {
        // No automatic ticks: the assertions below count only input/request-triggered renders.
        tickRate = Duration.ofHours(1);
        final var registry = testRegistry();
        final var command = command(registry, stubAgentLoop());
        uiThread = new Thread(command, "tui-drag-repaint-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");
        if (modal) {
            registry.onEvent(new AgentEvent.ToolStarted("bash", "{}", sid(registry), eventWorkdir));
            registry.onEvent(new AgentEvent.ToolFinished(
                    "bash", "abcdefghijklmnopqrstuvwxyz", true, sid(registry), eventWorkdir));
        } else {
            registry.onEvent(
                    new AgentEvent.TextDelta("```text\nabcdefghijklmnopqrstuvwxyz\n```", sid(registry), eventWorkdir));
            registry.onEvent(
                    new AgentEvent.TurnDone("```text\nabcdefghijklmnopqrstuvwxyz\n```", sid(registry), eventWorkdir));
        }
        // Registry updates queue UI work. This explicit input paints it without an automatic tick.
        tuiRef.get().dispatch(new PasteEvent(""));
        if (modal) {
            awaitText("✓ bash");
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ bash")));
            awaitText("full output");
        }
        awaitText("abcdefghijklmnopqrstuvwxyz");
        return registry;
    }

    private long dragBatch(final Consumer<TuiRunner> events) throws InterruptedException {
        final var runner = tuiRef.get();
        final var completed = new CountDownLatch(1);
        final var frames = new AtomicLong();
        runner.runLater(() -> {
            final var before = backend.frames();
            events.accept(runner);
            // The first fence is ahead of repaint requests appended while handling the inputs.
            // The second is behind those requests, so completion also observes their frames.
            runner.runLater(() -> runner.runLater(() -> {
                frames.set(backend.frames() - before);
                completed.countDown();
            }));
        });
        assertTrue(completed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "drag batch did not drain");
        return frames.get();
    }

    private boolean anyRowStartsWith(final String prefix) {
        for (final var line : backend.text().split("\n")) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyRowContaining(final String needle) {
        for (final var line : backend.text().split("\n")) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private void awaitRowStartingWith(final String prefix) throws InterruptedException {
        while (!anyRowStartsWith(prefix)) {
            assertTrue(
                    backend.awaitFrame(1_000),
                    "no frame received while waiting for a row starting with '" + prefix + "'");
        }
    }

    private void awaitNoRowStartingWith(final String prefix) throws InterruptedException {
        while (anyRowStartsWith(prefix)) {
            assertTrue(
                    backend.awaitFrame(1_000),
                    "no frame received while waiting for the '" + prefix + "' gutter to clear");
        }
    }

    private static UsageTracker newUsageTracker() {
        return new UsageTracker(null, null, null, Clock.systemUTC());
    }

    private static Class<?> nestedClass(final String simpleName) throws ClassNotFoundException {
        return Class.forName("io.yupiik.ia.agent.tui.TuiCommand$" + simpleName);
    }

    /**
     * Builds a {@code TuiCommand.FeedbackPrompt} for {@code raw} and attaches it to the active
     * tab's UiState on the render thread (mirror of {@code TuiCommand.feedbackGate}: the prompt
     * uses a never-awaited latch and a throwaway answers map, then gets painted by the next tick
     * because {@code runLater} marks the tab dirty). The tab is set to {@code tab}.
     */
    private static Object attachFeedbackPrompt(final TuiCommand command, final List<Question> raw, final int tab)
            throws Exception {
        final var ctor = nestedClass("FeedbackPrompt")
                .getDeclaredConstructor(List.class, CountDownLatch.class, Map.class, AtomicBoolean.class);
        ctor.setAccessible(true);
        final var prompt = ctor.newInstance(raw, new CountDownLatch(0), new ConcurrentHashMap<>(), new AtomicBoolean());
        final var tabMethod = nestedClass("FeedbackPrompt").getDeclaredMethod("tab", int.class);
        tabMethod.setAccessible(true);
        tabMethod.invoke(prompt, tab);

        final var tabManagerField = TuiCommand.class.getDeclaredField("tabManager");
        tabManagerField.setAccessible(true);
        final var tabManager = tabManagerField.get(command);
        final var activeMethod = tabManager.getClass().getMethod("active");
        final var state = activeMethod.invoke(tabManager);
        final var stateClass = state.getClass();
        final var promptField = stateClass.getDeclaredField("feedbackPrompt");
        promptField.setAccessible(true);
        final var dirtyField = stateClass.getDeclaredField("dirty");
        dirtyField.setAccessible(true);
        final var runnerField = stateClass.getDeclaredField("runner");
        runnerField.setAccessible(true);
        final var tui = runnerField.get(state);
        tui.getClass().getMethod("runLater", Runnable.class).invoke(tui, (Runnable) () -> {
            try {
                promptField.set(state, prompt);
                dirtyField.set(state, true);
            } catch (final IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        });
        return prompt;
    }

    private void quit() throws InterruptedException {
        final var tui = tuiRef.get();
        if (tui != null && tui.isRunning()) {
            tui.quit();
        }
        uiThread.join(TIMEOUT_MS);
        assertFalse(uiThread.isAlive(), "ui thread still alive after quit");
    }

    private void awaitTui() throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (tuiRef.get() == null) {
            assertTrue(System.nanoTime() < deadline, "tui runner was not created in time");
            backend.awaitFrame(50);
        }
    }

    private void awaitText(final String expected) throws InterruptedException {
        awaitTextWithin(expected, TIMEOUT_MS);
    }

    /** Arms a fresh quiet-boundary handshake for each scheduled mutation. */
    private Callable<Void> afterQuietTextPoll(final Runnable mutation) {
        final var quiet = new CountDownLatch(1);
        quietTextPoll = quiet;
        return () -> {
            assertTrue(quiet.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "text helper never crossed a quiet poll");
            mutation.run();
            return null;
        };
    }

    private void awaitTextFrame() throws InterruptedException {
        if (!backend.awaitFrame(textPollMillis) && quietTextPoll != null) {
            quietTextPoll.countDown();
        }
    }

    /** Waits for stale text to leave the screen, allowing frames from before the mutation. */
    private void awaitNoText(final String unexpected) throws InterruptedException {
        awaitTextGoneWithin(unexpected, 2_000);
    }

    /**
     * Waits for the screen condition until the overall deadline. Frame notifications are only
     * wakeups: unchanged screens do not repaint, and a quiet interval is not a failure.
     */
    private void awaitTextWithin(final String expected, final long timeoutMillis) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        var screen = backend.text();
        while (!screen.contains(expected)) {
            final var snapshot = screen;
            assertTrue(
                    System.nanoTime() < deadline,
                    () -> "'" + expected + "' did not appear within " + timeoutMillis + " ms; last screen:\n"
                            + snapshot);
            awaitTextFrame();
            screen = backend.text();
        }
    }

    private void awaitTextGoneWithin(final String expected, final long timeoutMillis) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        var screen = backend.text();
        while (screen.contains(expected)) {
            final var snapshot = screen;
            assertTrue(
                    System.nanoTime() < deadline,
                    () -> "'" + expected + "' did not clear within " + timeoutMillis + " ms; last screen:\n"
                            + snapshot);
            awaitTextFrame();
            screen = backend.text();
        }
    }

    private void awaitTextGone(final String expected) throws InterruptedException {
        awaitTextGoneWithin(expected, TIMEOUT_MS);
    }

    /** Polls until the bottommost reversed-video cell sits at {@code expectedColumn} (row-agnostic). */
    private int[] awaitCursorAt(final int expectedColumn) throws InterruptedException {
        return settledCursor(c -> c.col() == expectedColumn, "cursor at column " + expectedColumn);
    }

    /** Polls until the bottommost reversed-video cell sits at {@code expectedColumn} on row {@code expectedRow}. */
    private int[] awaitCursorAt(final int expectedColumn, final int expectedRow) throws InterruptedException {
        return settledCursor(
                c -> c.col() == expectedColumn && c.row() == expectedRow,
                "cursor at column " + expectedColumn + ", row " + expectedRow);
    }

    /** Bottommost reversed-video cell, which is the prompt input cursor. */
    private int[] awaitCursorCell() throws InterruptedException {
        return settledCursor(c -> true, "the reversed cursor cell");
    }

    /**
     * Polls the prompt cursor until a state satisfying {@code accept} has been stable across two
     * consecutive frames, so a transient mid-reflow frame (the prompt re-wrapping by a row while an
     * asynchronous refresh delays the next render) cannot satisfy the wait: the first matching frame
     * only arms the wait, the frame after it confirms the layout settled. When the backend goes
     * quiescent (no more frames), the last observed state is accepted as-is, mirroring the previous
     * behavior where an already-correct-but-not-repainted state still passed.
     */
    private int[] settledCursor(final Predicate<HeadlessBackend.Cursor> accept, final String what)
            throws InterruptedException {
        var prev = HeadlessBackend.Cursor.NONE;
        while (true) {
            final var cur = backend.cursor();
            if (cur.equals(prev) && accept.test(cur)) {
                return new int[] {cur.col(), cur.row()};
            }
            if (accept.test(cur) && cur != HeadlessBackend.Cursor.NONE) {
                prev = cur; // first sighting: require a second identical frame
                if (!backend.awaitFrame(SETTLED_CURSOR_CONFIRM_MS)) {
                    return new int[] {cur.col(), cur.row()}; // settled: no more frames coming
                }
                continue;
            }
            prev = HeadlessBackend.Cursor.NONE; // state moved: restart the two-frame criterion
            if (!backend.awaitFrame(1_000)) {
                assertTrue(accept.test(cur), "no frame received while waiting for " + what);
                return new int[] {cur.col(), cur.row()};
            }
        }
    }

    /** Screen column where {@code token} first appears on the lowest row that contains it. */
    private int columnOfLineContaining(final String token) throws InterruptedException {
        while (true) {
            final var lines = backend.text().split("\n");
            var row = -1;
            for (var y = lines.length - 1; y >= 0; y--) {
                if (lines[y].contains(token)) {
                    row = y;
                    break;
                }
            }
            if (row >= 0) {
                final var idx = lines[row].indexOf(token);
                if (idx >= 0) {
                    return idx;
                }
            }
            assertTrue(backend.awaitFrame(1_000), "no frame received while waiting for '" + token + "'");
        }
    }

    /** Screen row index of the first row containing {@code token}, without waiting for a frame. */
    private int rowIndex(final String token) {
        final var lines = backend.text().split("\n");
        for (var y = 0; y < lines.length; y++) {
            if (lines[y].contains(token)) {
                return y;
            }
        }
        return -1;
    }

    /** Number of times {@code token} appears across the whole screen text. */
    private static int countOccurrences(final String text, final String token) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }

    /** Screen row index of the first row containing {@code token}. */
    private int rowOf(final String token) throws InterruptedException {
        while (true) {
            final var lines = backend.text().split("\n");
            for (var y = 0; y < lines.length; y++) {
                if (lines[y].contains(token)) {
                    return y;
                }
            }
            assertTrue(backend.awaitFrame(1_000), "no frame received while waiting for '" + token + "'");
        }
    }

    /**
     * Asserts the two rows are separated by exactly one separator row (the block separator
     * markdown renders). The row content is not checked beyond the gap: every screen row also
     * carries the right-hand info panel, so a spacer row still shows its text.
     */
    private void assertBlankRowBetween(final String before, final String after) throws InterruptedException {
        final var first = rowOf(before);
        final var second = rowOf(after);
        assertTrue(
                second > first, "'" + after + "' must come after '" + before + "' (" + first + " then " + second + ")");
        Assertions.assertEquals(
                first + 2,
                second,
                () -> "expected one blank row between '" + before + "' and '" + after + "', screen was:\n"
                        + backend.text());
    }

    private static ShellRegistry testRegistry() {
        return new ShellRegistry(new Emitter() {
            @Override
            public <T> void emit(final T event) {
                // no-op: events are dispatched through ShellRegistry.onEvent in tests
            }
        });
    }

    /** Clock the test controls explicitly so elapsed-time rendering is deterministic. */
    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(final long initialMillis) {
            this.millis = new AtomicLong(initialMillis);
        }

        void advance(final long ms) {
            millis.addAndGet(ms);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    /**
     * An executor that can hold submitted tasks and release them on demand. Used to land a
     * live-completion result after an explicit dismissal deterministically instead of relying on a
     * timing race. Submissions received while not holding run on a virtual thread, like the
     * production executor.
     */
    private static final class HoldingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicBoolean holding = new AtomicBoolean(false);
        private final BlockingQueue<Runnable> held = new LinkedBlockingQueue<>();

        @Override
        public void execute(final Runnable command) {
            if (holding.get()) {
                held.add(command);
            } else {
                delegate.execute(command);
            }
        }

        /** Routes subsequent submissions to the held queue instead of running them. */
        void hold() {
            holding.set(true);
        }

        /** Runs one task without releasing any work it submits in turn. */
        void runNextHeld() throws InterruptedException {
            takeNextHeld().run();
        }

        private Runnable takeNextHeld() throws InterruptedException {
            final var task = held.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertNotNull(task, "no held task arrived");
            return task;
        }

        /**
         * Runs the held tasks synchronously on the caller thread so every resulting UI mutation is
         * enqueued before the caller dispatches a repaint-triggering key, then accepts submissions
         * again.
         */
        void releaseHeld() {
            holding.set(false);
            final var pending = List.copyOf(held);
            held.clear();
            pending.forEach(Runnable::run);
        }

        @Override
        public void shutdown() {
            delegate.shutdownNow();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    @Nested
    @FusionSupport(modules = DefaultProviderModule.class)
    class DefaultProvider {
        @TempDir
        private static Path storage;

        private static MockLlmServer primary;

        @BeforeEach
        void setup(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            assertEquals(storage.toString(), configuration.storageHome());
            assertEquals(1, configuration.provider().providers().size());
            assertEquals(
                    "default", configuration.provider().providers().getFirst().id());
            assertEquals(1, configuration.llm().maxConcurrent());
            start(mapper, configuration, primary, null);
        }

        @AfterAll
        static void closeServers() {
            if (primary != null) {
                primary.close();
            }
        }

        @Test
        @SuppressWarnings("unchecked")
        void capturedThoughtHeadingIsIdenticalInLiveAndFlushedLayouts() throws Exception {
            final var command = command(null, null);
            final var thought = TuiCommand.class.getDeclaredMethod("thoughtLines", String.class, int.class);
            thought.setAccessible(true);
            final var live = TuiCommand.class.getDeclaredMethod("liveThoughtLines", String.class, int.class);
            live.setAccessible(true);
            final var source = "**[10:11:12] test-model - Thinking**\n\nA captured thought\nwith a second line";
            final var flushed = (List<Line>) thought.invoke(command, source, 80);
            final var streaming = (List<Line>) live.invoke(command, source, 80);
            final var flushedText = flushed.stream()
                    .map(line ->
                            line.spans().stream().map(span -> span.content()).collect(Collectors.joining()))
                    .collect(Collectors.joining("\n"));
            final var streamingText = streaming.stream()
                    .map(line ->
                            line.spans().stream().map(span -> span.content()).collect(Collectors.joining()))
                    .collect(Collectors.joining("\n"));
            assertTrue(flushedText.contains("[10:11:12] test-model - Thinking"), flushedText);
            assertTrue(streamingText.contains("[10:11:12] test-model - Thinking"), streamingText);
            assertTrue(flushedText.contains("A captured thought"), flushedText);
            assertTrue(streamingText.contains("A captured thought"), streamingText);
        }

        @Test
        void textWaitsAllowQuietIntervalsBeforePromptChanges() throws Exception {
            textPollMillis = 20;
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-render-delayed-input");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            while (backend.awaitFrame(20)) {
                // Drain startup notifications so only the delayed mutation can wake the wait.
            }

            try (final var scheduler = Executors.newSingleThreadScheduledExecutor()) {
                for (var wait = 0; wait < 3; wait++) {
                    // Exercise both presence helpers, then all three absence helpers.
                    // Every mutation waits for an actual quiet poll, even on a loaded runner.
                    final var paste = scheduler.schedule(
                            afterQuietTextPoll(() -> tuiRef.get().dispatch(new PasteEvent("delayed-render-marker"))),
                            3 * textPollMillis,
                            TimeUnit.MILLISECONDS);
                    if (wait == 0) {
                        awaitText("delayed-render-marker");
                    } else {
                        awaitTextWithin("delayed-render-marker", TIMEOUT_MS);
                    }
                    paste.get();
                    while (backend.awaitFrame(20)) {
                        // Discard frames from the completed mutation.
                    }
                    final var clear = scheduler.schedule(
                            afterQuietTextPoll(() -> tuiRef.get().dispatch(KeyEvent.ofChar('u', KeyModifiers.CTRL))),
                            3 * textPollMillis,
                            TimeUnit.MILLISECONDS);
                    if (wait == 0) {
                        awaitTextGone("delayed-render-marker");
                    } else if (wait == 1) {
                        awaitTextGoneWithin("delayed-render-marker", TIMEOUT_MS);
                    } else {
                        awaitNoText("delayed-render-marker");
                    }
                    clear.get();
                }
            }
            quit();
        }

        @Test
        void shiftEnterInsertsNewlineInsteadOfSubmitting() throws Exception {
            final var shellRegistry = testRegistry();
            final var received = new AtomicReference<String>();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    received.set(prompt);
                    shellRegistry.onEvent(
                            new AgentEvent.TurnDone("done:" + prompt.lines().count(), sessionId, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-shift-enter-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("line one"));
            awaitText("line one");
            // Shift+Enter inserts a hard newline and keeps the prompt editable
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.SHIFT));
            tuiRef.get().dispatch(new PasteEvent("line two"));
            awaitText("line two");
            assertFalse(awaitSubmitted(received), "Shift+Enter must not submit the prompt");

            // plain Enter still submits, and the newline survived in the single prompt
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("done:2");
            Assertions.assertEquals("line one\nline two", received.get());

            quit();
        }

        @Test
        void typingBracketedPromptPreservesEveryCharacter() throws Exception {
            // regression: "[mermaid]" typed into the prompt must never lose its leading bracket
            // (the old interface-level SgrTailGuard ate "[m" as a mouse-tail shape; the mouse
            // junk is now fixed at the byte level by MouseReportCoalescingBackend, so the input
            // path is a plain insert and bracket text always survives)
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-bracket-typing-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            for (final var c : "[mermaid]".toCharArray()) {
                tuiRef.get().dispatch(KeyEvent.ofChar(c));
            }
            awaitText("[mermaid]");

            quit();
        }

        @Test
        void altEnterInsertsNewlineInsteadOfSubmitting() throws Exception {
            final var shellRegistry = testRegistry();
            final var received = new AtomicReference<String>();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    received.set(prompt);
                    shellRegistry.onEvent(
                            new AgentEvent.TurnDone("done:" + prompt.lines().count(), sessionId, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-alt-enter-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("a"));
            awaitText("a");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.ALT));
            tuiRef.get().dispatch(new PasteEvent("b"));
            awaitText("b");
            assertFalse(awaitSubmitted(received), "Alt+Enter must not submit the prompt");

            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("done:2");
            Assertions.assertEquals("a\nb", received.get());

            quit();
        }

        @Test
        void longPromptKeepsTheTypedLineVisible() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-long-prompt-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a prompt far taller than the input block (capped ~8 rows): without the
            // follow fix the bottom lines (where the caret lives) were clipped out of view
            final var lines =
                    IntStream.range(0, 15).mapToObj(i -> "prompt line " + i).collect(Collectors.joining("\n"));
            tuiRef.get().dispatch(new PasteEvent(lines));
            // the very last line contains the caret and must stay visible
            awaitText("prompt line 13");
            awaitText("prompt line 14");

            quit();
        }

        @Test
        void wheelOverLongPromptScrollsTheInputInsteadOfTheTranscript() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-wheel-input-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // content far taller than the input block (capped ~8 rows)
            final var lines =
                    IntStream.range(0, 20).mapToObj(i -> "long input line " + i).collect(Collectors.joining("\n"));
            tuiRef.get().dispatch(new PasteEvent(lines));
            awaitText("long input line 19"); // bottom (follow anchored to the caret)

            // scroll up *over the input*: the transcript must not move, the input must page up
            final var inputY = HeadlessBackend.HEIGHT - 8; // above the calling/status strips: inside the input block
            tuiRef.get().dispatch(MouseEvent.scrollUp(HeadlessBackend.WIDTH / 2, inputY));
            // a single wheel notch moves 3 rows: anchor 12 -> 9, so lines 9..16 remain, 19 is clipped
            awaitText("long input line 12");
            awaitTextGone("long input line 19");
            assertFalse(
                    backend.text().contains("long input line 7"),
                    "wheel up over the input scrolled the input, not the transcript:\n" + backend.text());
            assertFalse(
                    backend.text().contains("long input line 8"),
                    "wheel up should keep the input scrolled to its new top:\n" + backend.text());

            quit();
        }

        @Test
        void rendersTurnHeadersWithRule() throws Exception {
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    shellRegistry.onEvent(new AgentEvent.TextDelta("hello from stub", sessionId, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TurnDone("hello from stub", sessionId, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(
                    shellRegistry,
                    agentLoop,
                    false,
                    newUsageTracker(),
                    new TodoStore(tuiConfig, formatMapper),
                    new MutableClock(0));
            uiThread = new Thread(command, "tui-render-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("say hi"));
            awaitText("say hi");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("hello from stub");
            awaitText("ready");

            // a labelled turn opens with a role-colored label and a rule running to the panel
            // edge; this is what keeps the user/assistant boundary readable while scrolling.
            // the default user label is the OS user name ("You" when unavailable) and the
            // stamp is pinned to a fixed clock (MutableClock epoch 0, UTC) so the header is
            // deterministic: "[00:00:00]" instead of a real wall-clock second that could tick
            // between the render and the assertion and make the exact-match headers flaky.
            final var userName = System.getProperty("user.name");
            final var userLabel = userName == null || userName.isBlank() ? "You" : userName;
            final var stamp = "[00:00:00] ";
            assertTrue(
                    anyRowContaining(stamp + userLabel + " \u2500"),
                    "the user turn header and rule should be rendered:\n" + backend.text());
            assertTrue(
                    anyRowContaining(stamp + "default::test-model \u2500"),
                    "the assistant turn header and rule should be rendered:\n" + backend.text());
            // the header shares the role bar with the body and the pane has no left wall
            // anymore: gutter (screen column 0) then a single role bar, then the header
            assertTrue(
                    anyRowContaining("\u2503 " + stamp),
                    "the turn header must keep the continuous role bar:\n" + backend.text());
            awaitRowStartingWith("\u2502\u2503 " + stamp);
            // the marker is consumed as a boundary: with a fresh render label moving on (live
            // clock) the older marker would otherwise leak into the body as a bold line and drag
            // the user turn styling onto the assistant palette
            assertFalse(
                    anyRowContaining("**["), "the speaker marker must not leak into the turn body:\n" + backend.text());

            quit();
        }

        @Test
        void echoedSeamFragmentsNeverRenderInTranscriptFrames() throws Exception {
            // streaming a seam echo across deltas (with a tool call in between) must never show the
            // anchor or a partial fragment on any frame: the transcript scrubs at its render chokepoint
            final var shellRegistry = testRegistry();
            final var sessionId = new AtomicReference<String>();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionIdArg,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    sessionId.set(sessionIdArg);
                    final var sid = sessionIdArg;
                    shellRegistry.onEvent(new AgentEvent.TextDelta("Let me commit.\n\n@", sid, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TextDelta("1", sid, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TextDelta("@", sid, eventWorkdir));
                    shellRegistry.onEvent(
                            new AgentEvent.ToolStarted("bash", "{\"command\":\"git status\"}", sid, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "clean", true, sid, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TextDelta("done with the turn", sid, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TurnDone("done with the turn", sid, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-seam-frame-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("say hi"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));

            awaitText("done with the turn");
            awaitText("ready");
            // the seam must be scrubbed from the final screen (whole token) and no partial
            // fragment ("@1" or a lone "@") may appear anywhere on the pane
            final var text = backend.text();
            assertFalse(text.contains("@1"), "seam fragment must not render: " + text);
            assertFalse(text.contains("@1@"), "echoed seam must not render: " + text);
            assertTrue(text.contains("Let me commit."), text);
            assertTrue(text.contains("done with the turn"), text);
            assertTrue(text.contains("git status"), text);

            quit();
        }

        @Test
        void incompleteSeamFragmentsNeverRenderInTranscriptFrames() throws Exception {
            // like echoedSeamFragmentsNeverRenderInTranscriptFrames but the model never closes the
            // tag: bare anchors (@129, then @1291 fully on its own line) with a tool call in between
            // must never render, even though they are frozen into the durable transcript mid-block
            final var shellRegistry = testRegistry();
            final var sessionId = new AtomicReference<String>();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionIdArg,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    sessionId.set(sessionIdArg);
                    final var sid = sessionIdArg;
                    shellRegistry.onEvent(new AgentEvent.TextDelta(
                            "Good picture forming. Let me look at the tests.\n\n@129", sid, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TextDelta("0", sid, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TextDelta("\n\n@1291", sid, eventWorkdir));
                    shellRegistry.onEvent(
                            new AgentEvent.ToolStarted("bash", "{\"command\":\"git status\"}", sid, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "clean", true, sid, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TextDelta("done with the turn", sid, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TurnDone("done with the turn", sid, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-incomplete-seam-frame-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            tuiRef.get().dispatch(new PasteEvent("say hi"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("done with the turn");
            awaitText("ready");
            final var text = backend.text();
            assertFalse(text.contains("@129"), "anchor fragment must not render: " + text);
            assertFalse(text.contains("@1290"), "anchor fragment must not render: " + text);
            assertFalse(text.contains("@1291"), "anchor fragment must not render: " + text);
            assertTrue(text.contains("Good picture forming."), text);
            assertTrue(text.contains("done with the turn"), text);
            assertTrue(text.contains("git status"), text);
            quit();
        }

        @Test
        void appliedCapturesFollowDisclosureMatrixAndInvalidateRunAndOverlayLayouts() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            assertEquals(DiffViewMode.AUTO, state.diffViewMode);
            final var onEvent = TuiCommand.class.getDeclaredMethod("onEvent", AgentEvent.class, UiState.class);
            onEvent.setAccessible(true);
            final var args = "{\"path\":\"file.java\",\"oldString\":\"requested-old\",\"newString\":\"requested-new\"}";
            onEvent.invoke(
                    command, new AgentEvent.ToolStarted("edit_file", args, 1, "", null, null, eventWorkdir), state);
            final var files = List.of(new FileMutation(
                    eventWorkdir.resolve("file.java"),
                    FileMutation.Kind.UPDATE,
                    new FileMutation.Content(
                            "common-before\nactual-before\ncommon-after\n",
                            "common-before\nactual-after\ncommon-after\n")));
            onEvent.invoke(
                    command,
                    new AgentEvent.ToolFilesChanged("edit_file", files, 1, "", null, null, eventWorkdir),
                    state);
            onEvent.invoke(
                    command,
                    new AgentEvent.ToolFinished(
                            "edit_file",
                            "failure summary\nresult-details",
                            false,
                            false,
                            1,
                            "",
                            null,
                            null,
                            eventWorkdir),
                    state);
            assertEquals(files, state.toolOutputs.files(1));
            final var one = state.model.markdown("You", "Assistant");
            onEvent.invoke(command, new AgentEvent.ToolStarted("read", "{}", 2, "", null, null, eventWorkdir), state);
            onEvent.invoke(
                    command,
                    new AgentEvent.ToolFinished("read", "other-result", true, false, 2, "", null, null, eventWorkdir),
                    state);
            final var two = state.model.markdown("You", "Assistant");
            for (final var source : List.of(one, two)) {
                for (final var visibility : ToolOutputVisibility.values()) {
                    for (final var mode : DiffViewMode.values()) {
                        state.showToolActions = visibility;
                        state.diffViewMode = mode;
                        state.runCollapsed.clear();
                        final var frame = renderTurnFrame(command, state, source, 120);
                        final var text = String.join("\n", frame.rows());
                        final var visible = visibility != ToolOutputVisibility.HIDDEN;
                        assertEquals(visible, text.contains("Applied changes"), visibility + "/" + mode + ": " + text);
                        assertEquals(visible, text.contains("actual-before"), text);
                        assertFalse(text.contains(" - common-before"), text);
                        assertFalse(text.contains(" + common-before"), text);
                        assertFalse(text.contains("requested-new"), text);
                        if (visibility == ToolOutputVisibility.COLLAPSED) {
                            assertFalse(text.contains("result-details"), text);
                        }
                        if (source.equals(two) && visibility == ToolOutputVisibility.COLLAPSED) {
                            state.runCollapsed.put(frame.runHeaders().getFirst().id(), false);
                            assertTrue(String.join(
                                            "\n",
                                            renderTurnFrame(command, state, source, 120)
                                                    .rows())
                                    .contains("actual-after"));
                        }
                        if (visibility == ToolOutputVisibility.SHOWN) {
                            if (source.equals(one)) {
                                state.collapsedTools.add(1L);
                            } else {
                                state.runCollapsed.put(
                                        frame.runHeaders().getFirst().id(), true);
                            }
                            final var collapsedText = String.join(
                                    "\n",
                                    renderTurnFrame(command, state, source, 120).rows());
                            assertTrue(collapsedText.contains("actual-after"), mode + ": " + collapsedText);
                            assertFalse(collapsedText.contains("result-details"), collapsedText);
                            state.collapsedTools.clear();
                        }
                    }
                }
            }
            state.showToolActions = ToolOutputVisibility.SHOWN;
            state.runCollapsed.clear();
            state.diffViewMode = DiffViewMode.SIMPLE;
            final var simple = renderTurnFrame(command, state, two, 120);
            state.diffViewMode = DiffViewMode.SIDE_BY_SIDE;
            final var split = renderTurnFrame(command, state, two, 120);
            assertFalse(simple.rows().equals(split.rows()));
            final var resized = renderTurnFrame(command, state, two, 60);
            assertFalse(split.rows().equals(resized.rows()));
            final var overlay = TuiCommand.class.getDeclaredMethod(
                    "toolOverlayText",
                    ToolOutputStore.Entry.class,
                    int.class,
                    boolean.class,
                    DiffViewMode.class,
                    List.class);
            overlay.setAccessible(true);
            state.showToolActions = ToolOutputVisibility.HIDDEN;
            final var entry = state.toolOutputs.get(1);
            final var first = (Text) overlay.invoke(command, entry, 120, false, DiffViewMode.SIMPLE, files);
            Assertions.assertSame(first, overlay.invoke(command, entry, 120, false, DiffViewMode.SIMPLE, files));
            Assertions.assertNotSame(
                    first, overlay.invoke(command, entry, 120, false, DiffViewMode.SIDE_BY_SIDE, files));
            Assertions.assertNotSame(first, overlay.invoke(command, entry, 60, false, DiffViewMode.SIMPLE, files));
            assertTrue(first.lines().stream()
                    .flatMap(line -> line.spans().stream())
                    .anyMatch(span -> span.content().contains("actual-after")));
            assertFalse(first.lines().stream()
                    .flatMap(line -> line.spans().stream())
                    .anyMatch(span -> span.content().contains("requested-new")));
        }

        @Test
        void missingCapturesRemainRequestedAndUnavailableCapturesNeverInventAppliedContent() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            state.showToolActions = ToolOutputVisibility.COLLAPSED;
            state.diffViewMode = DiffViewMode.SIMPLE;
            final var onEvent = TuiCommand.class.getDeclaredMethod("onEvent", AgentEvent.class, UiState.class);
            onEvent.setAccessible(true);
            final var args = "{\"path\":\"absent.txt\",\"oldString\":\"before\",\"newString\":\"requested-only\"}";
            onEvent.invoke(
                    command, new AgentEvent.ToolStarted("edit_file", args, 1, "", null, null, eventWorkdir), state);
            onEvent.invoke(
                    command,
                    new AgentEvent.ToolFinished("edit_file", "done", true, false, 1, "", null, null, eventWorkdir),
                    state);
            final var source = state.model.markdown("You", "Assistant");
            final var requested = String.join(
                    "\n", renderTurnFrame(command, state, source, 100).rows());
            assertTrue(requested.contains("Requested changes"), requested);
            assertTrue(requested.contains("requested-only"), requested);
            assertFalse(requested.contains("Applied changes"), requested);
            final var unavailable =
                    List.of(new FileMutation(eventWorkdir.resolve("absent.txt"), FileMutation.Kind.UPDATE, null));
            onEvent.invoke(
                    command,
                    new AgentEvent.ToolFilesChanged("write_file", unavailable, 1, "", null, null, eventWorkdir),
                    state);
            final var applied = String.join(
                    "\n", renderTurnFrame(command, state, source, 100).rows());
            assertTrue(applied.contains("Exact applied diff unavailable"), applied);
            assertFalse(applied.contains("requested-only"), applied);
            final var overlay = TuiCommand.class.getDeclaredMethod(
                    "toolOverlayText",
                    ToolOutputStore.Entry.class,
                    int.class,
                    boolean.class,
                    DiffViewMode.class,
                    List.class);
            overlay.setAccessible(true);
            final var files = List.of(
                    new FileMutation(
                            eventWorkdir.resolve("created.txt"),
                            FileMutation.Kind.CREATE,
                            new FileMutation.Content(null, "created-content")),
                    new FileMutation(
                            eventWorkdir.resolve("deleted.txt"),
                            FileMutation.Kind.DELETE,
                            new FileMutation.Content("deleted-content", "")));
            final var text =
                    (Text) overlay.invoke(command, state.toolOutputs.get(1), 100, false, DiffViewMode.SIMPLE, files);
            final var plain = text.lines().stream()
                    .flatMap(line -> line.spans().stream())
                    .map(span -> span.content())
                    .reduce("", String::concat);
            assertTrue(plain.contains("CREATE"), plain);
            assertTrue(plain.contains("DELETE"), plain);
            assertTrue(plain.contains("/dev/null"), plain);
            assertTrue(plain.contains("created-content"), plain);
            assertTrue(plain.contains("deleted-content"), plain);
            assertFalse(plain.contains("requested-only"), plain);
        }

        @Test
        void toolPreviewMatchesFullBodyAndOverlayReusesLayout() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var body = TuiCommand.class.getDeclaredMethod(
                    "toolBodyLines",
                    String.class,
                    String.class,
                    String.class,
                    String.class,
                    int.class,
                    boolean.class,
                    int.class,
                    DiffViewMode.class);
            body.setAccessible(true);
            for (final var fixture : List.of(
                    List.of("bash", "unknown-program", "a\n\n\n\nb"),
                    List.of("bash", "unknown-program", "0123456789".repeat(100) + "\nline two\nline three\nlast"),
                    List.of("read", "Example.java", "     1\tpublic class Example {\n     2\t  int n = 42;\n     3\t}"),
                    List.of("bash", "python", "print('a fairly long text with words')\n# comment\nprint('end')"),
                    List.of("bash", "unknown-program", "{\"a\": [1, 2, 3], \"b\": true}"),
                    List.of("bash", "unknown-program", "# Heading\n\n```java\nint n = 1;\n```"))) {
                final var full = (List<Line>) body.invoke(
                        command,
                        fixture.get(0),
                        fixture.get(1),
                        null,
                        fixture.get(2),
                        15,
                        false,
                        Integer.MAX_VALUE,
                        DiffViewMode.SIMPLE);
                final var preview = (List<Line>) body.invoke(
                        command,
                        fixture.get(0),
                        fixture.get(1),
                        null,
                        fixture.get(2),
                        15,
                        false,
                        4,
                        DiffViewMode.SIMPLE);
                assertEquals(full.subList(0, Math.min(4, full.size())), preview, fixture.get(1));
            }
            final var overlay = TuiCommand.class.getDeclaredMethod(
                    "toolOverlayText",
                    ToolOutputStore.Entry.class,
                    int.class,
                    boolean.class,
                    DiffViewMode.class,
                    List.class);
            overlay.setAccessible(true);
            final var entry = new ToolOutputStore.Entry("bash", "{\"command\":\"echo hello\"}", "hello", false, 1);
            final var first = overlay.invoke(command, entry, 60, false, DiffViewMode.SIMPLE, List.of());
            Assertions.assertSame(first, overlay.invoke(command, entry, 60, false, DiffViewMode.SIMPLE, List.of()));
            Assertions.assertNotSame(first, overlay.invoke(command, entry, 30, false, DiffViewMode.SIMPLE, List.of()));
            final var clear = TuiCommand.class.getDeclaredMethod("clearRenderCaches");
            clear.setAccessible(true);
            clear.invoke(command);
            Assertions.assertNotSame(first, overlay.invoke(command, entry, 60, false, DiffViewMode.SIMPLE, List.of()));
        }

        @Test
        void skillDetailsRenderDocumentAndKeepRawSource() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var overlay = TuiCommand.class.getDeclaredMethod(
                    "toolOverlayText",
                    ToolOutputStore.Entry.class,
                    int.class,
                    boolean.class,
                    DiffViewMode.class,
                    List.class);
            overlay.setAccessible(true);
            for (final var prefix : List.of("", "---\nname: example\ndescription: Example skill\n---\n")) {
                final var source = prefix + "# Instructions\n\nUse **careful changes** and `tests`.\n";
                final var entry = new ToolOutputStore.Entry("skill", "{\"name\":\"example\"}", source, false, 1);
                final var rendered = (Text) overlay.invoke(command, entry, 100, false, DiffViewMode.SIMPLE, List.of());
                final var text = rendered.lines().stream()
                        .flatMap(line -> line.spans().stream())
                        .map(span -> span.content())
                        .reduce("", String::concat);
                assertTrue(text.contains("Instructions"), text);
                assertTrue(text.contains("careful changes"), text);
                assertFalse(text.contains("**careful changes**"), text);
                assertFalse(text.contains("# Instructions"), text);
                assertEquals(!prefix.isEmpty(), text.contains("Skill metadata"));
                final var raw = (Text) overlay.invoke(command, entry, 100, true, DiffViewMode.SIMPLE, List.of());
                final var rawText = raw.lines().stream()
                        .flatMap(line -> line.spans().stream())
                        .map(span -> span.content())
                        .reduce("", String::concat);
                assertTrue(rawText.contains("# Instructions"), rawText);
                assertTrue(rawText.contains("**careful changes**"), rawText);
                assertEquals(source, entry.output());
                Assertions.assertSame(raw, overlay.invoke(command, entry, 100, true, DiffViewMode.SIMPLE, List.of()));
                Assertions.assertNotSame(
                        raw, overlay.invoke(command, entry, 100, false, DiffViewMode.SIMPLE, List.of()));
            }
            for (final var unfinished : List.of(
                    new ToolOutputStore.Entry("skill", "{}", "Skill not found: **example**", true, 1),
                    new ToolOutputStore.Entry("skill", "{}", "Waiting for **example**", false, true, 1),
                    new ToolOutputStore.Entry("skill", "{}", "Loading **example**", false, -1))) {
                final var diagnostic =
                        (Text) overlay.invoke(command, unfinished, 100, false, DiffViewMode.SIMPLE, List.of());
                assertTrue(diagnostic.lines().stream()
                        .flatMap(line -> line.spans().stream())
                        .anyMatch(span -> span.content().contains("**example**")));
            }
        }

        @Test
        void skillModalTogglesRawSourceAndCopiesOriginal() throws Exception {
            final var registry = testRegistry();
            final var command = command(registry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-skill-document-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            final var source = "---\nname: example\n---\n# Skill instructions\n\nUse **careful changes**.\n";
            registry.onEvent(
                    new AgentEvent.ToolStarted("skill", "{\"name\":\"example\"}", sid(registry), eventWorkdir));
            registry.onEvent(new AgentEvent.ToolFinished("skill", source, true, sid(registry), eventWorkdir));
            awaitText("✓ skill");
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ skill")));
            awaitText("full output");
            awaitText("Skill metadata");
            awaitText("Use careful changes.");
            tuiRef.get().dispatch(KeyEvent.ofChar('r'));
            awaitText("Use **careful changes**.");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.DOWN));
            awaitText("Use **careful changes**.");
            tuiRef.get().dispatch(KeyEvent.ofChar('r'));
            awaitText("Use careful changes.");
            tuiRef.get().dispatch(KeyEvent.ofChar('c'));
            final var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!backend.raw().contains(SelectionState.osc52Payload(source)) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(backend.raw().contains(SelectionState.osc52Payload(source)), backend.raw());
            quit();
        }

        @Test
        void toolLayoutsDropObsoleteRevisionsAndSessionSwitchClearsCaptures() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            final var chunks = TuiCommand.class.getDeclaredMethod(
                    "contentChunks",
                    List.class,
                    String.class,
                    int.class,
                    UiState.class,
                    List.class,
                    List.class,
                    List.class,
                    List.class);
            chunks.setAccessible(true);
            final var cache = TuiCommand.class.getDeclaredField("toolBodyCache");
            cache.setAccessible(true);
            final var entries = ToolBodyCache.class.getDeclaredField("entries");
            entries.setAccessible(true);
            for (var revision = 0; revision < 3; revision++) {
                final var output = "revision " + revision;
                final var markdown = ConversationTranscript.TOOL_BEGIN + "\n`bash (#1)`\n\n```text\n" + output
                        + "\n```\n" + ConversationTranscript.TOOL_END;
                chunks.invoke(
                        command,
                        new ArrayList<>(),
                        markdown,
                        60,
                        state,
                        new ArrayList<>(),
                        new ArrayList<>(),
                        new ArrayList<>(),
                        new ArrayList<>());
                final var retained = (Map<ToolBodyCache.Key, ?>) entries.get(cache.get(command));
                assertTrue(
                        retained.keySet().stream().allMatch(key -> key.output().contains(output)));
            }
            chunks.invoke(
                    command,
                    new ArrayList<>(),
                    "trimmed",
                    60,
                    state,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>());
            assertTrue(((Map<?, ?>) entries.get(cache.get(command))).isEmpty());
            state.sessionId = "previous";
            state.session.sessionId("new-session");
            final var capture = new ToolOutputStore.Entry("bash", "{}", "old session", false, 1);
            state.toolOutputs.record(1, capture);
            state.toolOverlay = new UiState.ToolOverlay(1, capture, 0);
            final var sync = TuiCommand.class.getDeclaredMethod("syncSession", UiState.class);
            sync.setAccessible(true);
            sync.invoke(command, state);
            assertEquals(0, state.toolOutputs.size());
            Assertions.assertNull(state.toolOverlay);
        }

        @Test
        void fullToolRequestsSurviveEmptyFailedAndMalformedResultsWithoutExpandingInline() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var body = TuiCommand.class.getDeclaredMethod(
                    "toolBodyLines",
                    String.class,
                    String.class,
                    String.class,
                    String.class,
                    int.class,
                    boolean.class,
                    int.class,
                    DiffViewMode.class);
            body.setAccessible(true);
            for (final var fixture : List.of(
                    List.of("write_file", "{\"path\":\"a.txt\",\"content\":\"pending payload\"}", ""),
                    List.of(
                            "edit_file",
                            "{\"path\":\"a\",\"oldString\":\"before\",\"newString\":\"after\"}",
                            "permission denied"),
                    List.of("mcp_external", "{\"nested\":{\"items\":[1,2]}}", ""),
                    List.of("bash", "malformed raw request", "execution failed"),
                    List.of("mcp_external", "{}", "   "))) {
                final var full = (List<Line>) body.invoke(
                        command,
                        fixture.get(0),
                        null,
                        fixture.get(1),
                        fixture.get(2),
                        100,
                        false,
                        Integer.MAX_VALUE,
                        DiffViewMode.SIMPLE);
                final var text = full.stream()
                        .flatMap(line -> line.spans().stream())
                        .map(span -> span.content())
                        .reduce("", String::concat);
                assertTrue(text.contains("Request"), text);
                if (!fixture.get(2).isEmpty()) {
                    assertTrue(text.contains("Result"), text);
                    assertTrue(text.contains(fixture.get(2)), text);
                    assertTrue(text.indexOf("Request") < text.indexOf("Result"), text);
                }
                final var inline = (List<Line>) body.invoke(
                        command, fixture.get(0), null, null, fixture.get(2), 100, false, 3, DiffViewMode.SIMPLE);
                final var inlineText = inline.stream()
                        .flatMap(line -> line.spans().stream())
                        .map(span -> span.content())
                        .reduce("", String::concat);
                assertFalse(inlineText.contains("Requested changes"), inlineText);
                if (fixture.get(2).isEmpty()) {
                    assertTrue(inline.isEmpty());
                }
            }
        }

        @Test
        void toolDiagnosticLivesOnlyInErrorModal() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            final var onEvent = TuiCommand.class.getDeclaredMethod("onEvent", AgentEvent.class, UiState.class);
            onEvent.setAccessible(true);
            onEvent.invoke(
                    command,
                    new AgentEvent.ToolDiagnostic(
                            "mcp_example", "private failure\nsecond line", 42, "s", "child", "Worker", eventWorkdir),
                    state);
            assertTrue(state.errorMessage.contains("[Worker] mcp_example #42"));
            assertTrue(state.errorDetail.message().contains("private failure\nsecond line"));
            assertFalse(state.model.markdown("You", "Assistant").contains("private failure"));
            assertNull(state.toolOutputs.get(42));
        }

        @Test
        void sameNameToolsFinishingInReverseKeepTheirOwnArgumentsAndCleanAliases() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            final var onEvent = TuiCommand.class.getDeclaredMethod("onEvent", AgentEvent.class, UiState.class);
            onEvent.setAccessible(true);
            final var firstArgs = "{\"command\":\"echo first\"}";
            final var secondArgs = "{\"command\":\"echo second\"}";
            onEvent.invoke(
                    command, new AgentEvent.ToolStarted("bash", firstArgs, 10, "", null, null, eventWorkdir), state);
            onEvent.invoke(
                    command, new AgentEvent.ToolStarted("bash", secondArgs, 11, "", null, null, eventWorkdir), state);
            onEvent.invoke(
                    command,
                    new AgentEvent.ToolFinished("bash", "second", true, false, 11, "", null, null, eventWorkdir),
                    state);
            assertEquals(secondArgs, state.toolOutputs.get(11).args());
            assertEquals(firstArgs, state.toolOutputs.get(10).args());
            assertTrue(state.toolOutputs.get(10).running());
            assertEquals(List.of(firstArgs), new ArrayList<>(state.pendingToolArgs.get("bash")));
            assertEquals(List.of(10L), new ArrayList<>(state.pendingToolOrdinals.get("bash")));
            assertFalse(state.pendingToolOrdinals.containsKey("echo second"));
            onEvent.invoke(
                    command,
                    new AgentEvent.ToolFinished("bash", "first", true, false, 10, "", null, null, eventWorkdir),
                    state);
            assertEquals(firstArgs, state.toolOutputs.get(10).args());
            assertEquals("first", state.toolOutputs.get(10).output());
            assertEquals("second", state.toolOutputs.get(11).output());
            assertTrue(state.pendingToolArgs.isEmpty());
            assertTrue(state.pendingToolOrdinals.isEmpty());
        }

        @Test
        void runningEmptyOutputModalShowsRequestAndModalDiffsWrapWithoutInlineChanges() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var overlay = TuiCommand.class.getDeclaredMethod(
                    "toolOverlayText",
                    ToolOutputStore.Entry.class,
                    int.class,
                    boolean.class,
                    DiffViewMode.class,
                    List.class);
            overlay.setAccessible(true);
            final var args = "{\"path\":\"a.txt\",\"content\":\"draft body\"}";
            final var running = (Text) overlay.invoke(
                    command,
                    new ToolOutputStore.Entry("write_file", args, "", false, -1),
                    80,
                    false,
                    DiffViewMode.SIMPLE,
                    List.of());
            final var text = running.lines().stream()
                    .flatMap(line -> line.spans().stream())
                    .map(span -> span.content())
                    .reduce("", String::concat);
            assertTrue(text.contains("Requested changes"), text);
            assertTrue(text.contains("draft body"), text);
            assertTrue(text.contains("executing"), text);

            final var longLine = "0123456789".repeat(40);
            final var patch = "--- a/a\n+++ b/a\n@@ -1 +1 @@\n-old\n+" + longLine;
            final var full = (Text) overlay.invoke(
                    command,
                    new ToolOutputStore.Entry("apply_patch", "{\"patch\":\"not executed\"}", patch, true, 0),
                    30,
                    false,
                    DiffViewMode.SIMPLE,
                    List.of());
            final var fullText = full.lines().stream()
                    .flatMap(line -> line.spans().stream())
                    .map(span -> span.content())
                    .reduce("", String::concat);
            assertTrue(fullText.replace(" ", "").contains(longLine), fullText);
            final var body = TuiCommand.class.getDeclaredMethod(
                    "toolBodyLines",
                    String.class,
                    String.class,
                    String.class,
                    String.class,
                    int.class,
                    boolean.class,
                    int.class,
                    DiffViewMode.class);
            body.setAccessible(true);
            final var inline = (List<Line>) body.invoke(
                    command, "apply_patch", null, null, patch, 30, true, Integer.MAX_VALUE, DiffViewMode.SIMPLE);
            assertTrue(inline.stream()
                    .allMatch(line -> line.spans().stream()
                                    .map(span -> span.content())
                                    .reduce("", String::concat)
                                    .length()
                            <= 30));
        }

        @Test
        void legacyToolFinishesStillConsumeArgumentsAndAliasesInStartOrder() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            final var onEvent = TuiCommand.class.getDeclaredMethod("onEvent", AgentEvent.class, UiState.class);
            onEvent.setAccessible(true);
            final var args = "{\"command\":\"echo legacy\"}";
            onEvent.invoke(command, new AgentEvent.ToolStarted("bash", args, "", eventWorkdir), state);
            onEvent.invoke(command, new AgentEvent.ToolFinished("bash", "legacy", true, "", eventWorkdir), state);
            assertEquals(
                    args, state.toolOutputs.get(state.model.toolCallCount()).args());
            assertTrue(state.pendingToolArgs.isEmpty());
            assertTrue(state.pendingToolOrdinals.isEmpty());
        }

        @Test
        void regionCachePreservesOversizedContentAndOriginalWidths() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            final var unit = TuiCommand.class.getDeclaredMethod(
                    "regionUnit", String.class, String.class, int.class, UiState.class);
            unit.setAccessible(true);
            for (final var kind : List.of("thought", "log")) {
                final var source = "one two three four five six seven eight nine ten";
                final var direct = TuiCommand.class.getDeclaredMethod(kind + "Lines", String.class, int.class);
                direct.setAccessible(true);
                final var expected = (List<?>) direct.invoke(command, source, 25);
                final var part = unit.invoke(command, kind, source, 25, state);
                final var rows = part.getClass().getDeclaredMethod("rows");
                rows.setAccessible(true);
                assertEquals(expected.size(), ((List<?>) rows.invoke(part)).size(), kind + " wrapping changed");
            }
            final var oversized = "x".repeat(512 * 1024 + 1);
            final var part = unit.invoke(command, "thought", oversized, 80, state);
            final var rows = part.getClass().getDeclaredMethod("rows");
            rows.setAccessible(true);
            assertFalse(((List<?>) rows.invoke(part)).isEmpty(), "oversized content must render uncached");
        }

        @Test
        void regionCacheReusesStableChunksAndDropsStreamingRevisions() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            state.showThinking = true;
            final var render = TuiCommand.class.getDeclaredMethod(
                    "contentChunks",
                    List.class,
                    String.class,
                    int.class,
                    UiState.class,
                    List.class,
                    List.class,
                    List.class,
                    List.class);
            render.setAccessible(true);
            final var cacheField = TuiCommand.class.getDeclaredField("regionUnitCache");
            cacheField.setAccessible(true);
            final var stable =
                    ConversationTranscript.THOUGHT_BEGIN + "stable thought" + ConversationTranscript.THOUGHT_END;
            Object previous = null;
            for (var i = 0; i < 20; i++) {
                final var chunks = new ArrayList<>();
                final var rows = new ArrayList<>();
                render.invoke(
                        command,
                        chunks,
                        stable + "\nanswer " + i,
                        60,
                        state,
                        new ArrayList<>(),
                        new ArrayList<>(),
                        new ArrayList<>(),
                        rows);
                assertTrue(rows.toString().contains("stable thought"));
                if (previous != null) {
                    Assertions.assertSame(previous, chunks.getFirst());
                }
                previous = chunks.getFirst();
                assertTrue(((Map<?, ?>) cacheField.get(command)).size() <= 2, "old streaming revisions retained");
            }
            final var code = "```java\nint answer = 42;\n```";
            render.invoke(
                    command,
                    new ArrayList<>(),
                    code,
                    60,
                    state,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>());
            final var target = state.lastCodeBlock;
            assertTrue(target.contains("int answer = 42;"));
            state.lastCodeBlock = "wrong tab";
            render.invoke(
                    command,
                    new ArrayList<>(),
                    code,
                    60,
                    state,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>());
            assertEquals(target, state.lastCodeBlock, "full frame cache must restore the code copy target");
            render.invoke(
                    command,
                    new ArrayList<>(),
                    "reset",
                    60,
                    state,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>());
            Assertions.assertNull(state.lastCodeBlock);
            assertTrue(((Map<?, ?>) cacheField.get(command)).size() <= 1, "reset must release old regions");
        }

        @Test
        void cachedRegionsMatchColdRowsAndSelectionKinds() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var cold = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            final var coldState = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            state.showThinking = true;
            coldState.showThinking = true;
            final var render = TuiCommand.class.getDeclaredMethod(
                    "contentChunks",
                    List.class,
                    String.class,
                    int.class,
                    UiState.class,
                    List.class,
                    List.class,
                    List.class,
                    List.class);
            render.setAccessible(true);
            final var key = TuiCommand.class.getDeclaredField("lastContentKey");
            key.setAccessible(true);
            final var cache = TuiCommand.class.getDeclaredField("regionUnitCache");
            cache.setAccessible(true);
            final var thought = ConversationTranscript.THOUGHT_BEGIN
                    + "thought with **bold** and `code`\n\n```java\nint n = 1;\n```"
                    + ConversationTranscript.THOUGHT_END;
            for (final var tail : List.of(
                    "paragraph",
                    "paragraph\n\nnext",
                    "| a | b |\n|---|---|\n| c | d |",
                    "```text\n@123@\n```",
                    "[link]: https://example.com")) {
                final var rows = new ArrayList<>();
                final var coldRows = new ArrayList<>();
                final var chunks = new ArrayList<>();
                final var coldChunks = new ArrayList<>();
                ((Map<?, ?>) cache.get(cold)).clear();
                key.set(cold, null);
                render.invoke(
                        command,
                        chunks,
                        thought + tail,
                        42,
                        state,
                        new ArrayList<>(),
                        new ArrayList<>(),
                        new ArrayList<>(),
                        rows);
                render.invoke(
                        cold,
                        coldChunks,
                        thought + tail,
                        42,
                        coldState,
                        new ArrayList<>(),
                        new ArrayList<>(),
                        new ArrayList<>(),
                        coldRows);
                assertEquals(coldRows, rows, tail);
                assertEquals(coldChunks.size(), chunks.size(), tail);
                for (var i = 0; i < chunks.size(); i++) {
                    for (final var field : List.of("height", "rowKinds", "box")) {
                        final var accessor = chunks.get(i).getClass().getDeclaredMethod(field);
                        accessor.setAccessible(true);
                        final var actual = accessor.invoke(chunks.get(i));
                        final var expected = accessor.invoke(coldChunks.get(i));
                        if (actual instanceof Object[] array) {
                            Assertions.assertArrayEquals((Object[]) expected, array);
                        } else {
                            assertEquals(expected, actual, tail + ": " + field);
                        }
                    }
                }
                assertEquals(coldState.lastCodeBlock, state.lastCodeBlock);
            }
        }

        @Test
        void completeTurnsReuseChunksAcrossLargeReplayAndCumulativeStreaming() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            // 400 distinct turns fit the turn budget, but four copies make both the enclosing
            // prose region and full frame too expensive to cache. A 256-entry LRU thrashes here.
            final var messages = new ArrayList<String>();
            for (var copy = 0; copy < 4; copy++) {
                for (var i = 0; i < 400; i++) {
                    messages.add((i % 2 == 0 ? "**You**" : "**Assistant**") + "\n\n" + "turn-%03d ".formatted(i)
                            + "word ".repeat(42));
                }
            }
            final var replay = String.join("\n\n", messages);
            assertTrue(replay.length() > 370_000, "fixture must defeat whole-region retention by weight");
            var previous = renderTurnFrame(command, state, replay, 48);
            assertEquals(1600, previous.chunks().size(), "one chunk per plain speaker turn");
            final var unchanged = renderTurnFrame(command, state, replay, 48);
            assertHistoricalChunksSame(previous, unchanged, 1600);

            var streaming = replay;
            for (var revision = 0; revision < 4; revision++) {
                streaming += " appended-" + revision;
                final var next = renderTurnFrame(command, state, streaming, 48);
                assertHistoricalChunksSame(previous, next, 1599);
                Assertions.assertNotSame(
                        previous.chunks().getLast(),
                        next.chunks().getLast(),
                        "the growing final turn must not replay an earlier revision");
                previous = next;
            }

            // An edit near the beginning must not discard the unchanged suffix or rely on a
            // cached prefix. This also checks that identical bodies in other turns survive.
            messages.set(5, messages.get(5) + " edited");
            final var edited = renderTurnFrame(command, state, String.join("\n\n", messages), 48);
            for (var i = 0; i < 1599; i++) {
                if (i == 5) {
                    Assertions.assertNotSame(
                            previous.chunks().get(i), edited.chunks().get(i));
                } else {
                    Assertions.assertSame(
                            previous.chunks().get(i), edited.chunks().get(i), "turn " + i);
                }
            }
            assertTurnFrameMatchesCold(command, state, String.join("\n\n", messages), 48, edited);
        }

        @Test
        void manySmallPersistedMessagesReplayAsOneOversizedProseRegion() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            final var messages = new ArrayList<Message>();
            for (var i = 0; i < 1600; i++) {
                messages.add(Message.assistant("A persisted **answer** with `code_value`. ".repeat(6), List.of()));
            }
            final var source = state.model.replay(messages);
            assertTrue(source.length() > 400_000);
            assertEquals(1, TuiTranscriptModel.regions(source).size(), "small messages aggregate on resume");
            final var first = renderTurnFrame(command, state, source, 80);
            final var unchanged = renderTurnFrame(command, state, source, 80);
            assertHistoricalChunksSame(first, unchanged, first.chunks().size());
            final var growing = renderTurnFrame(command, state, source + " live continuation", 80);
            assertHistoricalChunksSame(unchanged, growing, unchanged.chunks().size() - 1);
            assertTurnFrameMatchesCold(command, state, source + " live continuation", 80, growing);
        }

        @Test
        void turnReusePreservesRoleBoundariesMarkdownCopyAndInvalidation() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            final var source = "unlabelled introduction\n\n"
                    + "---\n\n"
                    + "**You**\n\n"
                    + "same **bold** _italic_ `inline` body\n\n"
                    + "**Assistant**\n\n"
                    + "same **bold** _italic_ `inline` body\n\n"
                    + "> quoted **You** is not a speaker boundary\n\n"
                    + "| name | value |\n"
                    + "| --- | --- |\n"
                    + "| one | two |\n\n"
                    + "```java\n"
                    + "// **You** inside a fence\n"
                    + "int answer = 42;\n"
                    + "```\n\n"
                    + "**You**\n\n"
                    + "last prompt\n\n"
                    + "**Assistant**\n\n"
                    + "streaming answer";
            var frame = renderTurnFrame(command, state, source, 48);
            assertTrue(frame.lastCodeBlock().contains("int answer = 42;"));
            Assertions.assertNotSame(
                    frame.chunks().get(1),
                    frame.chunks().get(2),
                    "equal body text does not make different roles interchangeable");
            assertTrue(frame.rows().stream().anyMatch(row -> row.contains("unlabelled introduction")));
            assertTrue(frame.rows().stream().anyMatch(row -> row.contains("one")));
            assertTurnFrameMatchesCold(command, state, source, 48, frame);

            frame = renderTurnFrame(command, state, source, 48);
            state.lastCodeBlock = "another tab's code";
            final var appended = renderTurnFrame(command, state, source + " grows", 48);
            Assertions.assertSame(
                    frame.chunks().getFirst(), appended.chunks().getFirst(), "reuse a complete styled turn");
            assertEquals(frame.lastCodeBlock(), appended.lastCodeBlock(), "cached history restores code copy target");
            // Fence splitting and its separator rows retain their existing semantics; compare
            // output/kinds with a cold render rather than inventing different turn boundaries.
            assertTurnFrameMatchesCold(command, state, source + " grows", 48, appended);

            frame = renderTurnFrame(command, state, source, 48);
            final var narrow = renderTurnFrame(command, state, source, 30);
            Assertions.assertNotSame(frame.chunks().getFirst(), narrow.chunks().getFirst(), "width invalidates turns");
            assertTurnFrameMatchesCold(command, state, source, 30, narrow);

            frame = renderTurnFrame(command, state, source, 48);
            state.model.setLabels("Human", "Agent");
            final var relabelled = renderTurnFrame(command, state, source, 48);
            Assertions.assertNotSame(
                    frame.chunks().getFirst(),
                    relabelled.chunks().getFirst(),
                    "marker configuration edits invalidate cached segmentation");
            assertTurnFrameMatchesCold(command, state, source, 48, relabelled);

            state.model.setLabels("You", "Assistant");
            frame = renderTurnFrame(command, state, source, 48);
            final var applyTheme = TuiCommand.class.getDeclaredMethod("applyTheme", String.class);
            applyTheme.setAccessible(true);
            applyTheme.invoke(command, "light");
            final var light = renderTurnFrame(command, state, source, 48);
            Assertions.assertNotSame(frame.chunks().getFirst(), light.chunks().getFirst(), "theme invalidates turns");
            assertTurnFrameMatchesCold(command, state, source, 48, light);

            final var reset = renderTurnFrame(command, state, "**Assistant**\n\nno code remains", 48);
            Assertions.assertNull(reset.lastCodeBlock(), "history removal must release the code copy target");
        }

        @Test
        void turnCacheRemainsBoundedWhileStreamingAndReleasesRemovedHistory() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            // Retained memory has no visible output equivalent, so inspect only the two
            // accounting fields, not cache keys or the implementation of admission/eviction.
            final var cacheField = TuiCommand.class.getDeclaredField("turnUnitCache");
            cacheField.setAccessible(true);
            final var weightField = TuiCommand.class.getDeclaredField("turnUnitCacheWeight");
            weightField.setAccessible(true);
            for (final var bodyLength : List.of(1, 300)) {
                final var history = new StringBuilder();
                for (var i = 0; i < (bodyLength == 1 ? 4300 : 1100); i++) {
                    history.append("**Assistant**\n\nentry-")
                            .append(i)
                            .append(' ')
                            .append("word ".repeat(bodyLength / 5 + 1))
                            .append("\n\n");
                }
                var source = history.toString();
                var previous = renderTurnFrame(command, state, source, 48);
                for (var revision = 0; revision < 3; revision++) {
                    final var cache = (Map<?, ?>) cacheField.get(command);
                    final var weight = ((Number) weightField.get(command)).longValue();
                    assertFalse(cache.isEmpty(), "bounded admission must still retain some turns");
                    assertTrue(cache.size() <= 4096, "entry limit exceeded: " + cache.size());
                    assertTrue(weight > 0 && weight <= 8L * 1024 * 1024, "weight limit exceeded: " + weight);
                    source += " streaming-" + revision;
                    final var next = renderTurnFrame(command, state, source, 48);
                    Assertions.assertSame(
                            previous.chunks().getFirst(),
                            next.chunks().getFirst(),
                            "a saturated frame must not evict its admitted historical turns on every replay");
                    previous = next;
                }
                final var reset = renderTurnFrame(command, state, "**You**\n\nreplacement history", 48);
                assertTrue(((Map<?, ?>) cacheField.get(command)).size() <= 1, "removed history must be pruned");
                final var retainedWeight = ((Number) weightField.get(command)).longValue();
                assertTrue(retainedWeight >= 0 && retainedWeight < 16_384, "removed history weight must be released");
                assertTurnFrameMatchesCold(command, state, "**You**\n\nreplacement history", 48, reset);
                clearTurnRenderCaches(command);
                assertTrue(((Map<?, ?>) cacheField.get(command)).isEmpty(), "explicit clear must release all turns");
                assertEquals(0L, ((Number) weightField.get(command)).longValue());
            }
        }

        @Test
        void compactLayoutSurvivesExhaustedWidgetMemoAdmission() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            // Simulate an exhausted memo with a tiny fixture instead of putting the 109M-token
            // benchmark in the suite. Layout correctness/work must not depend on admission.
            for (final var field : List.of("turnUnitCacheWeight", "regionUnitCacheWeight")) {
                final var weight = TuiCommand.class.getDeclaredField(field);
                weight.setAccessible(true);
                weight.setLong(command, 8L * 1024 * 1024);
            }
            final var history = new StringBuilder();
            for (var i = 0; i < 24; i++) {
                history.append("**Assistant**\n\nturn-").append(i).append(" **word** text\n\n");
            }
            final var source = history.toString();
            final var first = renderTurnFrame(command, state, source, 40);
            assertEquals(24, first.chunks().size());
            final var render =
                    TuiCommand.class.getDeclaredMethod("layoutContent", String.class, int.class, UiState.class);
            render.setAccessible(true);
            final var layout = render.invoke(command, source, 40, state);
            for (var tick = 0; tick < 10; tick++) {
                Assertions.assertSame(layout, render.invoke(command, source, 40, state), "unchanged busy frame");
            }
            final var growing = renderTurnFrame(command, state, source + " continuation", 40);
            assertHistoricalChunksSame(first, growing, 23);
            Assertions.assertNotSame(first.chunks().getLast(), growing.chunks().getLast());
            assertTurnFrameMatchesCold(command, state, source + " continuation", 40, growing);
        }

        @Test
        void compactLayoutsBelongToTabsAndTrackExistingToolUpdates() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var first = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            final var second = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            final var render =
                    TuiCommand.class.getDeclaredMethod("layoutContent", String.class, int.class, UiState.class);
            render.setAccessible(true);
            final var source = "**Assistant**\n\nanswer";
            final var a = render.invoke(command, source, 40, first);
            final var b = render.invoke(command, source, 40, second);
            Assertions.assertNotSame(a, b);
            Assertions.assertSame(a, render.invoke(command, source, 40, first));
            Assertions.assertSame(b, render.invoke(command, source, 40, second));
            final var narrow = render.invoke(command, source, 20, first);
            Assertions.assertNotSame(a, narrow);
            first.showThinking = !first.showThinking;
            Assertions.assertNotSame(narrow, render.invoke(command, source, 20, first));

            first.showToolActions = ToolOutputVisibility.SHOWN;
            final var tool =
                    ConversationTranscript.TOOL_BEGIN + "\n`read (#1)`\n\npreview\n" + ConversationTranscript.TOOL_END;
            first.toolOutputs.record(1, new ToolOutputStore.Entry("read", "", "preview", false, 0));
            final var before = renderTurnFrame(command, first, tool, 40);
            first.toolOutputs.record(1, new ToolOutputStore.Entry("read", "", "preview", true, 0));
            final var after = renderTurnFrame(command, first, tool, 40);
            Assertions.assertNotSame(
                    before.chunks().getFirst(), after.chunks().getFirst(), "same ordinal changed status");
            assertTurnFrameMatchesCold(command, first, tool, 40, after);
        }

        @Test
        void stableCodeLayoutIsNotRehighlightedOnTailUpdates() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            final var source = "```java\nint answer = 42;\n```\n\n**Assistant**\n\ntail";
            final var first = renderTurnFrame(command, state, source, 40);
            final var next = renderTurnFrame(command, state, source + " grows", 40);
            Assertions.assertSame(first.chunks().getFirst(), next.chunks().getFirst());
            assertEquals(first.lastCodeBlock(), next.lastCodeBlock());
            assertTurnFrameMatchesCold(command, state, source + " grows", 40, next);
        }

        @Test
        void oversizedSingleTurnKeepsCommittedCompactPrefixWhileTableTailChanges() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            final var prefix = ("word ".repeat(100) + "\n\n").repeat(132);
            final var source = prefix + "| name | value |\n| --- | --- |\n| first | 1 |";
            final var first = renderTurnFrame(command, state, source, 40);
            final var extended = source + "\n| second | 2 |";
            final var next = renderTurnFrame(command, state, extended, 40);
            Assertions.assertSame(first.chunks().getFirst(), next.chunks().getFirst(), "committed prefix reparsed");
            assertTurnFrameMatchesCold(command, state, extended, 40, next);
            final var reset = renderTurnFrame(command, state, "reset", 40);
            assertEquals(1, reset.chunks().size());
            for (final var field : List.of("turns", "regions", "code", "tools")) {
                final var parts = state.contentLayout.getClass().getDeclaredField(field);
                parts.setAccessible(true);
                assertTrue(
                        ((Map<?, ?>) parts.get(state.contentLayout)).size() <= 1, "removed history retained: " + field);
            }
        }

        @Test
        void busyLiveThoughtReusesContentButNotTheIndicator() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            state.showThinking = true;
            state.model.onEvent(new AgentEvent.ThinkingDelta("pondering", null, eventWorkdir));
            final var render = TuiCommand.class.getDeclaredMethod(
                    "appendLiveChunks", List.class, int.class, UiState.class, List.class);
            render.setAccessible(true);
            final var first = new ArrayList<>();
            final var next = new ArrayList<>();
            render.invoke(command, first, 40, state, new ArrayList<>());
            render.invoke(command, next, 40, state, new ArrayList<>());
            assertEquals(2, first.size());
            Assertions.assertSame(first.getFirst(), next.getFirst());
            Assertions.assertNotSame(first.getLast(), next.getLast());
            state.model.onEvent(new AgentEvent.ThinkingDelta(" more", null, eventWorkdir));
            final var changed = new ArrayList<>();
            render.invoke(command, changed, 40, state, new ArrayList<>());
            Assertions.assertNotSame(first.getFirst(), changed.getFirst());
        }

        @Test
        void historicalToolLayoutSurvivesProseTailUpdates() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            state.showToolActions = ToolOutputVisibility.SHOWN;
            final var source = ConversationTranscript.TOOL_BEGIN + "\n`read (#1)`\n\npreview\n"
                    + ConversationTranscript.TOOL_END + "\n\n**Assistant**\n\nanswer";
            final var first = renderTurnFrame(command, state, source, 40);
            final var next = renderTurnFrame(command, state, source + " more", 40);
            Assertions.assertSame(first.chunks().getFirst(), next.chunks().getFirst());
            assertTurnFrameMatchesCold(command, state, source + " more", 40, next);
        }

        @Test
        void streamingParagraphBreakAcrossFlushBoundaryIsPreserved() throws Exception {
            // Text flushed around a tool boundary must remain visible as the answer resumes.
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    shellRegistry.onEvent(new AgentEvent.TextDelta("first paragraph", sessionId, eventWorkdir));
                    shellRegistry.onEvent(
                            new AgentEvent.ToolStarted("bash", "{\"command\":\"tool run\"}", sessionId, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "done", true, sessionId, eventWorkdir));
                    // the pre-tool text was flushed into the durable head; the answer resumes streaming
                    shellRegistry.onEvent(new AgentEvent.TextDelta("\n\nsecond paragraph", sessionId, eventWorkdir));
                    shellRegistry.onEvent(
                            new AgentEvent.TurnDone("first paragraph\n\nsecond paragraph", sessionId, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-flush-boundary-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("say hi"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("first paragraph");
            awaitText("second paragraph");
            awaitText("ready");

            final var text = backend.text();
            // both paragraphs render, and the tool output between them is present
            assertTrue(text.contains("first paragraph"), text);
            assertTrue(text.contains("second paragraph"), text);
            assertTrue(text.contains("tool run"), text);
            quit();
        }

        @Test
        void trimDropsRenderedHeadWithoutStaleChunks() throws Exception {
            // render a long transcript, then trim() the head: the frame must drop the cut content
            // and keep rendering the surviving tail (the region-memo must be invalidated on the
            // new head instance instead of replaying stale rows).
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    // enough history that a small maxHistoryCharacters trims the head
                    shellRegistry.onEvent(new AgentEvent.TextDelta("trimmable head content", sessionId, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TurnDone("trimmable head content", sessionId, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-trim-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("say hi"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("trimmable head content");
            awaitText("ready");

            // shrink the history budget: the render loop calls trim() each frame, so the next
            // repaint must drop the cut head and keep only the surviving tail
            final var sessionId = shellRegistry.sessions().iterator().next();
            final var transcript = shellRegistry.active(sessionId).transcript();
            transcript.maxHistoryCharacters(8);
            // force a repaint deterministically (a key event always renders): the render loop
            // applies trim() on the frame it draws
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.LEFT));
            awaitTextGoneWithin("trimmable head content", 4_000);
            quit();
        }

        @Test
        void rendersSidebarAndAnswer() throws Exception {
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    shellRegistry.onEvent(new AgentEvent.TextDelta("hello from stub", sessionId, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TurnDone("hello from stub", sessionId, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-render-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("test-model");

            tuiRef.get().dispatch(new PasteEvent("say hi"));
            awaitText("say hi");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("hello from stub");
            awaitText("ready");

            quit();
        }

        @Test
        void sidebarReflectsModelChangedViaSlashCommand() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-sidebar-model-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            // sidebar starts with the provider-configured model
            awaitText("model test-model");
            // no defaultVariant configured for the test fixture model: the strip marks the implicit
            // default
            awaitText("(default)");

            // /model overrides the per-session model: the sidebar must follow it
            tuiRef.get().dispatch(new PasteEvent("/model other-model"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("model other-model");

            quit();
        }

        @ParameterizedTest
        @CsvSource({"120, 40", "90, 24", "90, 12"})
        void sidebarUsageGaugeFitsAboveContext(final int width, final int height) throws Exception {
            backend = new HeadlessBackend(width, height);
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-sidebar-usage-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("% context");
            awaitTextGone("%/quota");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            for (final var percent : new int[] {-12, 42, 125}) {
                frontend.onEvent(new AgentEvent.UsageUpdated(
                        new Usage(0, 0, 0),
                        72000,
                        18,
                        0,
                        -1,
                        null,
                        sessionId,
                        null,
                        null,
                        new AgentEvent.UsageUpdated.Quota(
                                true, true, "a-very-long-codex-plan-name", percent, 0, 0, null, null, false),
                        eventWorkdir));
                final var usedPercent = Math.round(Math.clamp(percent, 0, 100));
                final var usageRowMarker = usedPercent + "%"; // fallback kind row renders "quota <bar> N%"
                awaitText(usageRowMarker);
                awaitText("18% context");
                final var rows = backend.text().lines().toList();
                final var usageRow = rows.stream()
                        .filter(r -> r.contains("quota") && r.contains(usageRowMarker))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("missing quota label: " + backend.text()));
                assertTrue(usageRow.contains(usageRowMarker), backend.text());
                assertTrue(rows.stream().anyMatch(r -> r.contains("18% context")), backend.text());
                assertTrue(
                        usageRow.endsWith("\u2502"), "usage must not overwrite the sidebar border:\n" + backend.text());
                assertFalse(usageRow.contains("a-very-long-codex-plan-name"), backend.text());
            }

            // Unsupported providers hide the quota even if enabled and a plan was supplied.
            frontend.onEvent(new AgentEvent.UsageUpdated(
                    new Usage(0, 0, 0),
                    72000,
                    18,
                    0,
                    -1,
                    null,
                    sessionId,
                    null,
                    null,
                    new AgentEvent.UsageUpdated.Quota(true, false, "plus", 42, 0, 0, null, null, false),
                    eventWorkdir));
            awaitTextGone("%/quota");
            assertTrue(backend.text().lines().toList().stream().anyMatch(r -> r.contains("18% context")));

            // Existing visibility is based on supported quota data, not the refresh toggle.
            frontend.onEvent(new AgentEvent.UsageUpdated(
                    new Usage(0, 0, 0),
                    72000,
                    18,
                    0,
                    -1,
                    null,
                    sessionId,
                    null,
                    null,
                    new AgentEvent.UsageUpdated.Quota(false, true, "plus", 42, 0, 0, null, null, false),
                    eventWorkdir));
            awaitText("42%");
            frontend.onEvent(new AgentEvent.UsageUpdated(new Usage(0, 0, 0), 72000, 18, sessionId, eventWorkdir));
            awaitTextGone("%/quota");
            assertTrue(backend.text().lines().toList().stream().anyMatch(r -> r.contains("18% context")));
            quit();
        }

        @Test
        void sidebarRendersBothQuotaWindowsWithProportionalFill() throws Exception {
            backend = new HeadlessBackend(120, 40);
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-sidebar-dual-quota-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("% context");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            // two rate-limit windows: a 5h window with heavy usage (90% consumed) and a weekly
            // window with light usage (10% consumed). The renderer sorts windows by duration
            // ascending, so the 5h window must appear above the weekly one.
            final var fiveHours = 5 * 3600L;
            final var week = 7 * 86400L;
            final var windows = List.of(
                    new AgentEvent.UsageUpdated.Quota.Window("PRIMARY", "primary", 90.0, fiveHours, 1_705_000_000L),
                    new AgentEvent.UsageUpdated.Quota.Window("SECONDARY", "secondary", 10.0, week, 1_710_000_000L));
            frontend.onEvent(new AgentEvent.UsageUpdated(
                    new Usage(0, 0, 0),
                    72000,
                    18,
                    0,
                    -1,
                    null,
                    sessionId,
                    null,
                    null,
                    new AgentEvent.UsageUpdated.Quota(
                            true, true, "plus", 90.0, fiveHours, 1_705_000_000L, "primary", null, false, windows),
                    eventWorkdir));

            awaitText("18% context");

            final var rows = backend.text().lines().toList();
            // each window renders as one row with the kind label on the left, the consumed
            // percentage on the right and a block fill bar sizing the consumed share between them.
            final var fiveHourRow = rows.stream()
                    .filter(r -> r.contains("5h") && r.contains("90%"))
                    .findFirst()
                    .orElseThrow();
            final var weeklyRow = rows.stream()
                    .filter(r -> r.contains("7d") && r.contains("10%"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(fiveHourRow.contains("90%"), "5h window should surface the consumed 90%:\n" + fiveHourRow);
            assertTrue(weeklyRow.contains("10%"), "weekly window should surface the consumed 10%:\n" + weeklyRow);
            // the 5h window has heavily used (90% consumed, near-full bar); the weekly window is
            // lightly used (10% consumed, short bar). Fill must scale with the consumed share.
            final var fiveFill = blockCount(fiveHourRow);
            final var weekFill = blockCount(weeklyRow);
            assertTrue(
                    fiveFill > weekFill,
                    "fill extent must scale with the consumed share (5h=" + fiveFill + " weekly=" + weekFill + "):\n"
                            + backend.text());
            // the two windows share the same window kind width and bar region, so their consumed
            // fills are directly comparable: 90% consumed must fill ~9x the 10% window.
            assertTrue(
                    fiveFill >= weekFill * 4L && weekFill >= 1,
                    "90% consumed should fill most of the bar while 10% fills only a short one (5h="
                            + fiveFill
                            + " weekly=" + weekFill + "):\n"
                            + backend.text());
            // both windows are sorted by duration so 5h sits above weekly in the sidebar
            assertTrue(rows.indexOf(fiveHourRow) < rows.indexOf(weeklyRow), backend.text());
            assertTrue(
                    rows.indexOf(weeklyRow)
                            < rows.indexOf(rows.stream()
                                    .filter(r -> r.contains("18% context"))
                                    .findFirst()
                                    .orElseThrow()),
                    backend.text());
            quit();
        }

        /**
         * A fully-reached quota (usedPercent=100) must stay visible in the sidebar and render
         * the consumed "100%", never being dropped or hidden - the whole point of surfacing usage
         * at switch time.
         */
        @Test
        void sidebarRendersReachedQuotaAsVisible() throws Exception {
            backend = new HeadlessBackend(120, 40);
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-sidebar-reached-quota-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("% context");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            final var fiveHours = 5 * 3600L;
            frontend.onEvent(new AgentEvent.UsageUpdated(
                    new Usage(0, 0, 0),
                    72000,
                    18,
                    0,
                    -1,
                    null,
                    sessionId,
                    null,
                    null,
                    new AgentEvent.UsageUpdated.Quota(
                            true,
                            true,
                            "plus",
                            100.0,
                            fiveHours,
                            1_705_000_000L,
                            "primary",
                            "0",
                            false,
                            List.of(new AgentEvent.UsageUpdated.Quota.Window(
                                    "PRIMARY", "primary", 100.0, fiveHours, 1_705_000_000L))),
                    eventWorkdir));
            awaitText("100%");
            awaitText("18% context");
            final var rows = backend.text().lines().toList();
            assertTrue(
                    rows.stream().anyMatch(r -> r.contains("5h") && r.contains("100%")),
                    "a fully-reached quota must render its consumed 100% instead of being hidden:\n" + backend.text());
            quit();
        }

        /** Hovering a sidebar quota gauge surfaces its reset time as a floating chip; hiding the quota removes the chip. */
        @Test
        void sidebarContextHoverShowsSizeTooltip() throws Exception {
            backend = new HeadlessBackend(120, 40);
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-sidebar-context-tooltip-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("% context");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            // a non-zero context estimate so the chip shows a k/M-formatted size
            frontend.onEvent(new AgentEvent.UsageUpdated(new Usage(0, 0, 0), 72_000, 18, sessionId, eventWorkdir));
            awaitText("18% context");

            // locate the context gauge row from the rendered text and hover it: the size chip appears
            final var lines = backend.text().split("\n");
            int gaugeY = -1;
            int gaugeX = -1;
            for (var i = 0; i < lines.length; i++) {
                final var col = lines[i].indexOf("% context");
                if (col >= 0) {
                    gaugeY = i;
                    gaugeX = col; // the whole row is a hover area
                    break;
                }
            }
            assertTrue(gaugeY >= 0, "context gauge row must be visible:\n" + backend.text());
            tuiRef.get().dispatch(MouseEvent.move(gaugeX, gaugeY));
            awaitTextWithin("~72k / 400k tok", 2_000);
            assertTrue(
                    backend.text().contains("~72k / 400k tok"),
                    "chip must show the current/max k/M token estimate:\n" + backend.text());

            // moving the pointer away dismisses the chip
            tuiRef.get().dispatch(MouseEvent.move(5, 20));
            awaitTextGoneWithin("~72k / 400k tok", 2_000);
            quit();
        }

        /**
         * Hovering the context gauge before any usable window is resolved (contextPercent = 0) shows
         * only the current estimate, never a division-by-zero " / " suffix.
         */
        @Test
        void sidebarContextHoverWithZeroPercentShowsCurrentOnly() throws Exception {
            backend = new HeadlessBackend(120, 40);
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-sidebar-context-tooltip-zero-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("% context");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            // a non-zero context estimate but a still-unresolved window (contextPercent = 0)
            frontend.onEvent(new AgentEvent.UsageUpdated(new Usage(0, 0, 0), 72_000, 0, sessionId, eventWorkdir));
            awaitText("0% context");

            // locate the context gauge row from the rendered text and hover it
            final var lines = backend.text().split("\n");
            int gaugeY = -1;
            int gaugeX = -1;
            for (var i = 0; i < lines.length; i++) {
                final var col = lines[i].indexOf("% context");
                if (col >= 0) {
                    gaugeY = i;
                    gaugeX = col;
                    break;
                }
            }
            assertTrue(gaugeY >= 0, "context gauge row must be visible:\n" + backend.text());
            tuiRef.get().dispatch(MouseEvent.move(gaugeX, gaugeY));
            awaitTextWithin("~72k tok", 2_000);
            assertTrue(
                    backend.text().contains("~72k tok"),
                    "chip must show the current estimate only when the window is unresolved:\n" + backend.text());
            assertFalse(
                    backend.text().contains(" / 0 "),
                    "no division-by-zero ' / 0 ' suffix when contextPercent is 0:\n" + backend.text());

            quit();
        }

        @Test
        void sidebarQuotaHoverShowsResetTooltipAndHidesWithProvider() throws Exception {
            backend = new HeadlessBackend(120, 40);
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-sidebar-quota-tooltip-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("% context");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            final var fiveHours = 5 * 3600L;
            // Keep the deadline inside the 100-day bucket rather than on its boundary;
            // formatQuotaReset reads the real clock on each render.
            final var resetAt = Instant.now().getEpochSecond() + 100 * 86_400L + 12 * 3_600L;
            final var window = new AgentEvent.UsageUpdated.Quota.Window("PRIMARY", "primary", 90.0, fiveHours, resetAt);
            frontend.onEvent(new AgentEvent.UsageUpdated(
                    new Usage(0, 0, 0),
                    72000,
                    18,
                    0,
                    -1,
                    null,
                    sessionId,
                    null,
                    null,
                    new AgentEvent.UsageUpdated.Quota(
                            true, true, "plus", 90.0, fiveHours, resetAt, "primary", null, false, List.of(window)),
                    eventWorkdir));
            awaitText("5h");

            // locate the gauge row from the rendered text and hover it: the reset chip appears
            final var lines = backend.text().split("\n");
            int gaugeY = -1;
            int gaugeX = -1;
            for (var i = 0; i < lines.length; i++) {
                final var col = lines[i].indexOf("5h");
                if (col >= 0) {
                    gaugeY = i;
                    gaugeX = col; // the whole row is a hover area
                    break;
                }
            }
            assertTrue(gaugeY >= 0, "quota gauge row must be visible:\n" + backend.text());
            tuiRef.get().dispatch(MouseEvent.move(gaugeX, gaugeY));
            awaitTextWithin("Resets in ", 2_000);

            // moving the pointer away dismisses the chip
            tuiRef.get().dispatch(MouseEvent.move(5, 20));
            awaitTextGoneWithin("Resets in ", 2_000);

            // provider switch: a no-quota UsageUpdated hides the bars; re-hovering yields no chip
            frontend.onEvent(new AgentEvent.UsageUpdated(
                    new Usage(0, 0, 0),
                    72000,
                    18,
                    0,
                    -1,
                    null,
                    sessionId,
                    null,
                    null,
                    new AgentEvent.UsageUpdated.Quota(
                            true, false, "plus", 10.0, fiveHours, resetAt, "primary", null, false, List.of(window)),
                    eventWorkdir));
            awaitTextGoneWithin("5h", 2_000);
            tuiRef.get().dispatch(MouseEvent.move(gaugeX, gaugeY));
            awaitTextGoneWithin("Resets in ", 2_000);
            quit();
        }

        @Test
        void sidebarLastRealRowAppearsOnlyWhenAnchored() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-sidebar-lastreal-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("test-model");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();

            // no anchor yet: the "last real" row must be absent and "context ~" stays
            frontend.onEvent(new AgentEvent.UsageUpdated(new Usage(0, 0, 0), 72000, 7.0, "s1", eventWorkdir));
            awaitText("context ~");
            awaitTextGone("last real");

            // anchored: the row appears with the provider-billed prompt tokens of the last request
            frontend.onEvent(new AgentEvent.UsageUpdated(
                    new Usage(68432, 0, 0),
                    72000,
                    7.0,
                    68432,
                    0.95,
                    null,
                    "s1",
                    null,
                    null,
                    AgentEvent.UsageUpdated.Quota.NONE,
                    eventWorkdir));
            awaitText("last real  68k tok");

            quit();
        }

        @ParameterizedTest
        @CsvSource({"5,100000,80", "0,125000,100"})
        void wideSidebarShowsFullInputAndCachePercentage(
                final int rawInput, final long cachedInput, final int cachedPercent) throws Exception {
            backend = new HeadlessBackend(120, 40);
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-sidebar-full-input-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            shellRegistry.onEvent(new AgentEvent.UsageUpdated(
                    new Usage(rawInput, 10, rawInput + 10, cachedInput, 0, 0, 0, 125000),
                    125000,
                    50,
                    sid(shellRegistry),
                    eventWorkdir));
            awaitText("tokens in  125k");
            awaitText("cached     " + (cachedInput == 100000 ? "100k" : "125k") + " (" + cachedPercent + "%)");
            awaitText("tokens out 10");

            quit();
        }

        @ParameterizedTest
        @CsvSource({"5,100000,80", "0,125000,100"})
        void narrowStatusStripShowsFullInputAndCachePercentage(
                final int rawInput, final long cachedInput, final int cachedPercent) throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-status-full-input-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            state.sessionId = sid(shellRegistry);
            state.session = shellRegistry.active(state.sessionId).session();
            final var onEvent = TuiCommand.class.getDeclaredMethod("onEvent", AgentEvent.class, UiState.class);
            onEvent.setAccessible(true);
            onEvent.invoke(
                    command,
                    new AgentEvent.UsageUpdated(
                            new Usage(rawInput, 10, rawInput + 10, cachedInput, 0, 0, 0, 125000),
                            125000,
                            50,
                            state.sessionId,
                            eventWorkdir),
                    state);

            // Exercise the narrow strip on a roomy buffer so trailing cache metrics are not clipped.
            final var area = new Rect(0, 0, 200, 1);
            final var buffer = Buffer.empty(area);
            final var render = TuiCommand.class.getDeclaredMethod(
                    "renderStatus", Rect.class, Frame.class, UiState.class, boolean.class);
            render.setAccessible(true);
            render.invoke(command, area, Frame.forTesting(buffer), state, true);
            final var text = new StringBuilder();
            for (var x = 0; x < area.width(); x++) {
                text.append(buffer.get(x, 0).symbol());
            }
            assertTrue(
                    text.toString()
                            .contains("in 125k out 10 cached " + (cachedInput == 100000 ? "100k" : "125k") + " ("
                                    + cachedPercent + "%)"),
                    text.toString());

            quit();
        }

        @Test
        void statusStripShowsEstimatedTokenSavingsByDefault() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-optimization-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            state.sessionId = sid(shellRegistry);
            state.session = shellRegistry.active(state.sessionId).session();
            state.session.runtime().usage().recordOptimization(10_000, 1_000, 3_100);
            final var area = new Rect(0, 0, 240, 1);
            final var buffer = Buffer.empty(area);
            final var render = TuiCommand.class.getDeclaredMethod(
                    "renderStatus", Rect.class, Frame.class, UiState.class, boolean.class);
            render.setAccessible(true);
            render.invoke(command, area, Frame.forTesting(buffer), state, true);
            final var text = new StringBuilder();
            for (var x = 0; x < area.width(); x++) {
                text.append(buffer.get(x, 0).symbol());
            }
            assertTrue(text.toString().contains("optimized 1 (~3.1k tokens saved)"), text.toString());
            assertFalse(text.toString().contains("chars"), text.toString());
            quit();
        }

        @Test
        void slashHelpListsCommandsInTranscript() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-help-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("/help"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("show the keyboard shortcuts");

            quit();
        }

        @Test
        void executingToolsShowsRunningToolInModalOverlay() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-executing-tools-running-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a tool is genuinely in flight (tracked by the transcript model, most-recent-first)
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"sleep 10\"}", sid(shellRegistry), eventWorkdir));

            tuiRef.get().dispatch(new PasteEvent("/executing-tools"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            // the modal overlay is open with the running tool's command
            awaitTextWithin("sleep 10", TIMEOUT_MS);
            awaitTextWithin("executing-tools", TIMEOUT_MS);

            // Esc closes the modal
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));

            quit();
        }

        @Test
        void executingToolsWithNoneRunningShowsEmptyModal() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-executing-tools-empty-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("/executing-tools"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            // the empty-case modal is visible (a durable overlay, not a transient status toast)
            awaitTextWithin("no tool executing", TIMEOUT_MS);

            quit();
        }

        @Test
        void wideStatusMessagesRenderInLastPositionOfStatusStrip() throws Exception {
            // /theme (no arg) produces a wide informational message routed to the trailing
            // notification channel rather than replacing the agent state at the strip head
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-status-notification-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("/theme"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("ⓘ"); // notification glyph present in the status strip

            quit();
        }

        @Test
        void agentStatusEventsAppendNotificationAndNeverReplaceAgentState() throws Exception {
            // AgentEvent.Status carries informational progress (output optimization, graph
            // refresh...): it must land in the trailing notification slot, ellipsized when too
            // long, while the leading agent state (ready) stays untouched
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-agent-status-notification-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            final var sessionId = shellRegistry.sessions().iterator().next();
            shellRegistry
                    .active(sessionId)
                    .frontend()
                    .onEvent(new AgentEvent.Status("MARKER" + "x".repeat(300) + "TAIL", sessionId, eventWorkdir));

            awaitText("ⓘ");
            awaitText("MARKER");
            awaitText("..."); // clipped to the leading row width
            awaitTextGone("TAIL");
            awaitText("ready"); // agent state still first and untouched

            quit();
        }

        @Test
        void notificationModalDoesNotPasteIntoPrompt() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            state.input.setText("existing draft");
            state.notificationDetail = "notification details";
            final var onKey = TuiCommand.class.getDeclaredMethod("onKey", Event.class, TuiRunner.class, UiState.class);
            onKey.setAccessible(true);

            assertEquals(true, onKey.invoke(command, new PasteEvent("unexpected text"), null, state));
            assertEquals("existing draft", state.input.text());
            assertEquals("notification details", state.notificationDetail);
        }

        @Test
        void removedQueuedPromptRetainsAllLinesInNotification() throws Exception {
            final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            state.pendingPrompts.add("remaining prompt");
            state.pendingPrompts.add("first line\nsecond line\nlast line");
            final var removeQueued = TuiCommand.class.getDeclaredMethod("removeQueued", UiState.class, int.class);
            removeQueued.setAccessible(true);

            removeQueued.invoke(null, state, 1);

            assertEquals(List.of("remaining prompt"), List.copyOf(state.pendingPrompts));
            assertEquals("removed queued prompt (1 still pending)", state.notification);
            assertEquals(
                    "removed queued prompt (1 still pending)\nremoved: first line\nsecond line\nlast line",
                    state.lastNotification);
        }

        @Test
        void notificationModalRetainsAndScrollsFullMessage() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-notification-detail-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            frontend.onEvent(new AgentEvent.Status(
                    "NOTICEHEAD\n" + "line of detail\n".repeat(30) + "NOTICETAIL", sessionId, eventWorkdir));
            awaitText("NOTICEHEAD");
            // Age the fixture on the UI thread instead of sleeping through the status-strip TTL.
            final var tabManagerField = TuiCommand.class.getDeclaredField("tabManager");
            tabManagerField.setAccessible(true);
            final var tabs = (TabManager) tabManagerField.get(command);
            tuiRef.get().runLater(() -> {
                final var state = tabs.active();
                state.notificationShownAt = System.currentTimeMillis() - 5_100;
                state.dirty = true;
            });
            awaitTextGone("NOTICEHEAD");
            tuiRef.get().dispatch(KeyEvent.ofChar('i', KeyModifiers.ALT));
            awaitText("notification");
            awaitText("NOTICEHEAD");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_DOWN));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_DOWN));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_DOWN));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_DOWN));
            awaitText("NOTICETAIL");
            frontend.onEvent(new AgentEvent.Status("NEWNOTICE", sessionId, eventWorkdir));
            awaitText("NOTICETAIL"); // open modal keeps its original snapshot
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            tuiRef.get().dispatch(KeyEvent.ofChar('i', KeyModifiers.ALT));
            awaitText("NEWNOTICE");
            quit();
        }

        @Test
        void infoNoticeAndLongErrorAreFullyReadable() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-notice-detail-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            frontend.onEvent(new AgentEvent.Notice(
                    AgentEvent.Notice.Level.INFO,
                    "INFOHEAD " + "x".repeat(180) + " INFOTAIL",
                    sessionId,
                    eventWorkdir));
            tuiRef.get().dispatch(KeyEvent.ofChar('i', KeyModifiers.ALT));
            awaitText("INFOTAIL");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            frontend.onEvent(new AgentEvent.Notice(
                    AgentEvent.Notice.Level.ERROR,
                    "ERRORHEAD " + "x".repeat(180) + " ERRORTAIL",
                    sessionId,
                    eventWorkdir));
            awaitText("ERRORHEAD");
            tuiRef.get().dispatch(KeyEvent.ofChar('e', KeyModifiers.ALT));
            awaitText("ERRORTAIL");
            quit();
        }

        @Test
        void completionArrowsMoveHighlightAndEnterAppliesIt() throws Exception {
            // Up/Down on an open completion popup must move the highlight (not apply-and-reset
            // to the first candidate), and Enter must then accept the highlighted candidate
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-completion-arrows-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // type "/model m" - the live completion popup opens with the mock models (m01..m30)
            for (final var c : "/model m".toCharArray()) {
                tuiRef.get().dispatch(KeyEvent.ofChar(c));
            }
            awaitText("> m01"); // first candidate highlighted

            // Down moves the highlight to the second candidate without touching the input
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.DOWN));
            awaitText("> m02");
            awaitText("/model m"); // the line is unchanged: arrows only cycle the highlight

            // Enter accepts the highlighted candidate into the line
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("/model m02");

            quit();
        }

        @Test
        void completionTabAppliesHighlightedAndIsIdempotent() throws Exception {
            // Tab on an open completion popup applies the *highlighted* candidate and
            // dismisses the popup; a second Tab recomputes against the new line (rather
            // than re-applying a stale span, which would duplicate the token - the
            // "yoyolo" bug) and is idempotent when the token is complete: with "/model
            // m01" typed, m01 still matches itself and the line stays unchanged.
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-completion-tab-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            for (final var c : "/model m0".toCharArray()) {
                tuiRef.get().dispatch(KeyEvent.ofChar(c));
            }
            awaitText("> m01");

            // Tab applies the highlighted candidate and the line becomes the full model
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB));
            awaitText("/model m01");

            // the caret lands right after the inserted token, not before it
            final var modelLineCol = columnOfLineContaining("/model m01");
            awaitCursorAt(modelLineCol + "/model m01".length());

            // a second Tab must not swap to m02 nor duplicate the token: it recomputes
            // against the applied line and stays idempotent
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB));
            awaitText("/model m01");
            assertFalse(backend.text().contains("/model m02"), "second Tab must not apply m02:\n" + backend.text());

            quit();
        }

        @Test
        void completionTabOnEmptySegmentInsertsHighlightedCandidate() throws Exception {
            // Regression: with the caret in an EMPTY argument segment (e.g. "/model " plus a trailing
            // space, so the cursor sits in a zero-width token), TAB must apply the highlighted candidate
            // instead of refusing the degenerate span and dismissing the popup without entering anything.
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-completion-empty-tab-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // "/model" then a space opens the argument popup with an empty current segment
            for (final var c : "/model ".toCharArray()) {
                tuiRef.get().dispatch(KeyEvent.ofChar(c));
            }
            awaitText("> m01"); // the popup lists the models, first highlighted

            // TAB applies the highlighted candidate into the empty segment at the caret
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB));
            awaitText("/model m01");

            // the caret lands right after the inserted token, not before it
            final var modelLineCol = columnOfLineContaining("/model m01");
            awaitCursorAt(modelLineCol + "/model m01".length());

            // a second TAB stays idempotent (no duplicate, no swap)
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB));
            awaitText("/model m01");
            assertFalse(backend.text().contains("/model m02"), "second Tab must not apply m02:\n" + backend.text());

            quit();
        }

        @Test
        void completionEscDismissesAndSecondEnterSubmits() throws Exception {
            // Esc on an open popup dismisses it without touching the line; with the popup
            // gone, Enter then submits the raw line (instead of accepting a candidate)
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-completion-esc-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            for (final var c : "/model m".toCharArray()) {
                tuiRef.get().dispatch(KeyEvent.ofChar(c));
            }
            awaitText("> m01");

            // Esc dismisses the popup: the candidates disappear, the line stays
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("> m01");
            awaitText("/model m"); // the raw line is untouched

            // Enter now submits the raw line (there is no popup to accept)
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("model: m"); // /model m executed (no candidate accepted)

            quit();
        }

        @Test
        void completionDismissedByEscIsNotResurrectedByALateResult() throws Exception {
            // Regression: a live-completion result that lands after an explicit Esc dismissal must be
            // discarded, never reopen the popup. Otherwise Enter right after Esc would accept a stale
            // candidate instead of submitting the raw line (the intermittent flake that made
            // completionEscDismissesAndSecondEnterSubmits fail on loaded machines). The late result is
            // produced deterministically here by holding the completion tasks and releasing them after
            // the dismissal.
            final var executor = new HoldingExecutor();
            final var command = command(
                    testRegistry(),
                    stubAgentLoop(),
                    false,
                    newUsageTracker(),
                    new TodoStore(tuiConfig, formatMapper),
                    Clock.systemUTC(),
                    new SubagentRegistry(tuiConfig, new ProviderManager(tuiConfig, formatMapper)),
                    executor);
            uiThread = new Thread(command, "tui-completion-late-result-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            for (final var c : "/model m".toCharArray()) {
                tuiRef.get().dispatch(KeyEvent.ofChar(c));
            }
            awaitText("> m01");

            // hold the next completion results: type then erase a char so the held result matches the
            // current input ("/model m") - exactly the shape of a result that lands a moment too late
            executor.hold();
            tuiRef.get().dispatch(KeyEvent.ofChar('x'));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.BACKSPACE));

            // Esc dismisses the popup; only then do the held results land
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("> m01");
            executor.releaseHeld();

            // let the late results be applied and repainted: a rejected result still marks the
            // state dirty, so frames keep flowing and a resurrected popup would be drawn here
            final var settleDeadline =
                    System.nanoTime() + Duration.ofMillis(500).toNanos();
            while (System.nanoTime() < settleDeadline) {
                backend.awaitFrame(50);
            }
            assertFalse(
                    backend.text().contains("> m01"),
                    () -> "an Esc-dismissed completion popup must not be resurrected by a late result:\n"
                            + backend.text());

            quit();
        }

        @Test
        void completionCacheArrivalBeforeEmptyResultStillOpensPopup() throws Exception {
            final var executor = new HoldingExecutor();
            final var command = command(
                    testRegistry(),
                    stubAgentLoop(),
                    false,
                    newUsageTracker(),
                    new TodoStore(tuiConfig, formatMapper),
                    Clock.systemUTC(),
                    new SubagentRegistry(tuiConfig, new ProviderManager(tuiConfig, formatMapper)),
                    executor);
            uiThread = new Thread(command, "tui-completion-cache-arrival-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            tuiRef.get().dispatch(new PasteEvent("/mo"));
            tuiRef.get().dispatch(KeyEvent.ofChar('d'));
            awaitText("> /mode");

            executor.hold();
            tuiRef.get().dispatch(new PasteEvent("el m"));
            tuiRef.get().dispatch(KeyEvent.ofChar('0'));
            awaitText("/model m0");
            final var renderBlocked = new CountDownLatch(1);
            final var releaseRender = new CountDownLatch(1);
            tuiRef.get().runLater(() -> {
                renderBlocked.countDown();
                try {
                    assertTrue(releaseRender.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "render was not released");
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            try {
                assertTrue(renderBlocked.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "render did not pause");
                // Compute an empty suggestion and enqueue its application. Then populate the model
                // cache while the old slash-command popup is still visible, before that empty result
                // can clear it. The cache notification must not be lost against the old popup.
                executor.runNextHeld();
                executor.runNextHeld();
            } finally {
                releaseRender.countDown();
                executor.releaseHeld();
            }
            awaitText("> m01");
            quit();
        }

        @Test
        void completionViewportScrollsSelectionIntoView() throws Exception {
            // a model list longer than the 8-row popup must scroll the window so the
            // highlighted candidate stays visible when navigating far enough
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-completion-viewport-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // "m0" matches m01..m09 (9 candidates > the 8-row window)
            for (final var c : "/model m0".toCharArray()) {
                tuiRef.get().dispatch(KeyEvent.ofChar(c));
            }
            awaitText("> m01");

            // navigate down far enough to need scrolling: m09 is off the initial window
            for (int i = 0; i < 8; i++) {
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.DOWN));
            }
            awaitText("> m09"); // the highlighted row must still be rendered (scrolled into view)

            // Enter accepts the scrolled-to candidate
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("/model m09");

            quit();
        }

        @Test
        void acceptedAmbiguousQueuedPromptIsNotClassifiedAgainAfterTurnDone() throws Exception {
            final var shellRegistry = testRegistry();
            final var releaseFirstTurn = new CompletableFuture<Void>();
            final var received = new LinkedBlockingQueue<String>();
            final var literal = "/modle queued literal input";
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    received.add(prompt);
                    if ("hold first turn".equals(prompt)) {
                        releaseFirstTurn.join();
                        // Deliberately do not consume queuedPrompt: exercise the TUI's TurnDone fallback.
                        shellRegistry.onEvent(new AgentEvent.TurnDone("first turn finished", sessionId, eventWorkdir));
                    } else {
                        shellRegistry.onEvent(
                                new AgentEvent.TextDelta("queued literal delivered", sessionId, eventWorkdir));
                        shellRegistry.onEvent(
                                new AgentEvent.TurnDone("queued literal delivered", sessionId, eventWorkdir));
                    }
                    return "ok";
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-queued-routing-test");
            uiThread.setDaemon(true);
            uiThread.start();
            try {
                awaitTui();
                awaitText("yca coding agent");
                tuiRef.get().dispatch(new PasteEvent("hold first turn"));
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
                assertEquals("hold first turn", received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));

                tuiRef.get().dispatch(new PasteEvent(literal));
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
                awaitText("Send as normal input");
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
                awaitTextGone("Send as normal input");
                awaitText("queued prompt (1 pending)");
                assertTrue(received.isEmpty(), "accepting while busy must enqueue, not start a concurrent turn");

                releaseFirstTurn.complete(null);
                awaitText("queued literal delivered");
                awaitText("ready");
                assertEquals(literal, received.poll(), "the accepted text must reach the loop verbatim");
                assertTrue(received.isEmpty(), "the queued input must start exactly one turn");
                assertFalse(backend.text().contains("Send as normal input"));
            } finally {
                releaseFirstTurn.complete(null);
                quit();
            }
        }

        @Test
        void promptIntegratedUpdatesTheQueuedPromptNotification() throws Exception {
            // PromptIntegrated (a queued prompt picked up by the running turn) must land in the
            // trailing notification slot with the remaining queue count, not in the leading status
            final var shellRegistry = testRegistry();
            final var turnStarted = new CountDownLatch(1);
            final var releaseTurn = new CountDownLatch(1);
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    try {
                        turnStarted.countDown();
                        releaseTurn.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-prompt-integrated-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // start a live turn, then queue a second prompt while it runs
            tuiRef.get().dispatch(new PasteEvent("first"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            assertTrue(
                    turnStarted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the stub turn did not start before the timeout");

            tuiRef.get().dispatch(new PasteEvent("follow up"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("queued prompt (1 pending)");

            // the loop picks the queued prompt up: the notification must reflect the integration
            // (remaining count) while the leading agent state stays untouched - with the stub turn
            // parked the queue still holds the prompt, so the remaining-count form is expected
            final var sessionId = sid(shellRegistry);
            shellRegistry
                    .active(sessionId)
                    .frontend()
                    .onEvent(new AgentEvent.PromptIntegrated("follow up", sessionId, eventWorkdir));
            awaitText("1 queued prompts still pending");
            awaitText("thinking..."); // the leading agent state was not replaced by the integration notice

            releaseTurn.countDown();
            quit();
        }

        @Test
        void severeLogErrorRendersRedInBannerAndStatusStripAndKeepsStatusText() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-severe-error-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("ready");

            // the capture handler routes a SEVERE record on the "io.yupiik" logger into the content
            // view, the status-strip notification (WARNING+) and the Alt+E error modal (SEVERE)
            Logger.getLogger("io.yupiik").severe("LLM request failed (429) retry-after=1");

            // the content view log region carries the level/logger bracket groups and the message
            // on the same row, with one separating space: "[SEVERE][io.yupiik] LLM request failed ..."
            awaitText("[SEVERE][io.yupiik] LLM request failed (429) retry-after=1");
            awaitText("LLM request failed (429) retry-after=1");
            // the status-strip notification carries the message (red while an error is active)
            awaitText("ⓘ LLM request failed (429) retry-after=1");
            // the boxed banner below the status line shows the full message (double space after the
            // glyph)
            awaitText("✖  LLM request failed (429) retry-after=1  (Alt+E for details)");
            // the far-left status text must keep the normal "ready", not be replaced by the error
            awaitText("ready");

            // the error banner must be painted in the error red (#e06c75)
            final var col = columnOfLineContaining("✖  LLM request failed");
            final var row = rowOf("✖  LLM request failed");
            final var rgb = backend.fg(col, row).map(c -> c.toRgb()).orElse(null);
            assertTrue(
                    rgb != null && rgb.r() == 0xe0 && rgb.g() == 0x6c && rgb.b() == 0x75,
                    "expected the error banner to be painted in the error red, fg=" + backend.fg(col, row));

            quit();
        }

        @Test
        void agentTurnErrorRendersRedRightSlotAndKeepsLeftStatusReady() throws Exception {
            // AgentEvent.Error (a failed turn with all retries exhausted, eg. the JDK transport
            // error "HTTP/1.1 header parser received no bytes") must land in the trailing red
            // error slot (right, Alt+E for details), never replace the leading status text
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-agent-error-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("ready");

            final var sessionId = shellRegistry.sessions().iterator().next();
            shellRegistry
                    .active(sessionId)
                    .frontend()
                    .onEvent(new AgentEvent.Error("HTTP/1.1 header parser received no bytes", sessionId, eventWorkdir));

            // the trailing red segment carries the message (single space after the cross glyph);
            // the boxed banner below uses a double space, so this matches only the trailing one
            awaitText("✗ HTTP/1.1 header parser received no bytes");
            // the far-left status text must keep the normal "ready", not be replaced by the error
            awaitText("ready");

            // the error segment must be painted in the error red (#e06c75)
            final var col = columnOfLineContaining("✗ HTTP/1.1 header parser");
            final var row = rowOf("✗ HTTP/1.1 header parser");
            final var rgb = backend.fg(col, row).map(c -> c.toRgb()).orElse(null);
            assertTrue(
                    rgb != null && rgb.r() == 0xe0 && rgb.g() == 0x6c && rgb.b() == 0x75,
                    "expected the error segment to be painted in the error red, fg=" + backend.fg(col, row));

            quit();
        }

        @Test
        void agentErrorNoticeRendersRedRightSlotAndKeepsLeftStatusUntouched() throws Exception {
            // ERROR notices (truncation give-up, compaction exhaustion...) are failures too: they
            // must reach the red trailing slot while the leading agent state stays untouched
            // ("ready" when idle, "working" mid-turn)
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-agent-error-notice-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("ready");

            final var sessionId = shellRegistry.sessions().iterator().next();
            shellRegistry
                    .active(sessionId)
                    .frontend()
                    .onEvent(new AgentEvent.Notice(
                            AgentEvent.Notice.Level.ERROR,
                            "Response truncated by the output token limit (finish_reason=length)",
                            sessionId,
                            eventWorkdir));

            awaitText("✗ Response truncated by the output token limit");
            awaitText("ready");

            quit();
        }

        @Test
        void errorModalShowsAndCopiesTheNoticeCauseStackTrace() throws Exception {
            // the retry path now carries the underlying Throwable on the WARN notice; opening the
            // Alt+E modal must render the full stack trace (not "(no stack trace)") so it can be read
            // and copied - the `c` key copies message + stack to the clipboard
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-error-stack-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("ready");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var cause = new IOException("Index 2 out of bounds for length 2");
            shellRegistry
                    .active(sessionId)
                    .frontend()
                    .onEvent(new AgentEvent.Notice(
                            AgentEvent.Notice.Level.WARN,
                            "Stream interrupted (Index 2 out of bounds for length 2); retrying (1/3).",
                            null,
                            sessionId,
                            null,
                            null,
                            cause,
                            eventWorkdir));

            awaitText("✗ Stream interrupted (Index 2 out of bounds for length 2)");
            awaitText("ready");

            // open the Alt+E error detail modal and assert the stack frame is rendered
            tuiRef.get().dispatch(KeyEvent.ofChar('e', KeyModifiers.ALT));
            awaitText("java.io.IOException");

            quit();
        }

        @Test
        void transientWarnNoticeAutoClearsOnNextSuccessfulRequest() throws Exception {
            // a recoverable WARN notice (mid-stream retry) pins the banner, but a subsequent successful
            // LlmRequestCompleted proves the retry healed it: the banner must not linger for the session
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-warn-transient-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("ready");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            frontend.onEvent(new AgentEvent.Notice(
                    AgentEvent.Notice.Level.WARN,
                    "Stream interrupted (broken pipe); retrying (1/3).",
                    null,
                    sessionId,
                    null,
                    null,
                    new IOException("broken pipe"),
                    eventWorkdir));

            awaitText("✗ Stream interrupted (broken pipe)");

            // the retry then succeeds: the banner must clear itself
            frontend.onEvent(
                    new AgentEvent.LlmRequestCompleted(new Usage(0, 0, 0), "test-model", 1, sessionId, eventWorkdir));
            awaitNoText("✗ Stream interrupted (broken pipe)");
            awaitText("ready");

            quit();
        }

        @Test
        void errorNoticeStaysPinnedUntilDismissed() throws Exception {
            // an ERROR-level notice (a real give-up) must stay pinned even when a later request succeeds:
            // only an explicit dismiss (Alt+X, Esc, clicking the ✖) or the next submit clears it
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-error-pinned-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("ready");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            frontend.onEvent(new AgentEvent.Notice(
                    AgentEvent.Notice.Level.ERROR,
                    "model returned an empty completion (finish_reason=stop); gave up.",
                    null,
                    sessionId,
                    null,
                    null,
                    null,
                    eventWorkdir));

            awaitText("✗ model returned an empty completion");

            // a successful request must NOT clear a persistent error
            frontend.onEvent(
                    new AgentEvent.LlmRequestCompleted(new Usage(0, 0, 0), "test-model", 1, sessionId, eventWorkdir));
            awaitText("✗ model returned an empty completion");

            // Alt+X dismisses it
            tuiRef.get().dispatch(KeyEvent.ofChar('x', KeyModifiers.ALT));
            awaitNoText("✗ model returned an empty completion");

            quit();
        }

        @Test
        void errorBannerCloseXIsClickable() throws Exception {
            // the leading "✖ " of the banner is a click target: a left press dismisses the error
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-error-x-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("ready");

            final var sessionId = shellRegistry.sessions().iterator().next();
            shellRegistry
                    .active(sessionId)
                    .frontend()
                    .onEvent(new AgentEvent.Notice(
                            AgentEvent.Notice.Level.WARN,
                            "Stream interrupted (broken pipe); retrying (1/3).",
                            null,
                            sessionId,
                            null,
                            null,
                            new IOException("broken pipe"),
                            eventWorkdir));

            awaitText("✗ Stream interrupted (broken pipe)");
            // the status-slot trailing line carries "✗ Stream interrupted"; the boxed banner is 2 rows
            // below it (the status line, the box's top border, then the banner text row which holds the
            // clickable "✖" at the box content col). Locate the banner text row and its ✖ column.
            final var statusRow = rowOf("✗ Stream interrupted");
            final var bannerRow = statusRow + 2;
            final var lines = backend.text().split("\n");
            final var bannerCol = lines[bannerRow].indexOf("✖");

            // a press on the banner's ✖ itself clears the error
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, bannerCol, bannerRow));
            awaitTextGone("✗ Stream interrupted (broken pipe)");

            // re-pin: a press further into the banner body (not on the ✖) must NOT dismiss it
            shellRegistry
                    .active(sessionId)
                    .frontend()
                    .onEvent(new AgentEvent.Notice(
                            AgentEvent.Notice.Level.WARN,
                            "Stream interrupted (broken pipe); retrying (2/3).",
                            null,
                            sessionId,
                            null,
                            null,
                            new IOException("broken pipe"),
                            eventWorkdir));
            awaitText("✗ Stream interrupted (broken pipe)");
            final var row2 = rowOf("✗ Stream interrupted");
            final var col2 = lines[row2 + 2].indexOf("✖");
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, col2 + 8, row2 + 2));
            awaitText("✗ Stream interrupted (broken pipe)");

            quit();
        }

        @Test
        void escDismissesErrorBannerInIdleState() throws Exception {
            // with nothing more specific open, Esc also clears a lingering error banner
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-error-esc-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("ready");

            final var sessionId = shellRegistry.sessions().iterator().next();
            shellRegistry
                    .active(sessionId)
                    .frontend()
                    .onEvent(new AgentEvent.Notice(
                            AgentEvent.Notice.Level.ERROR,
                            "mode switch failed: boom",
                            null,
                            sessionId,
                            null,
                            null,
                            null,
                            eventWorkdir));

            awaitText("✗ mode switch failed: boom");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitNoText("✗ mode switch failed: boom");
            awaitText("ready");

            quit();
        }

        @Test
        void pauseRendersInTrailingInfoSlotAndLeavesLeadingStatusUntouched() throws Exception {
            // provider-imposed delays (throttle/retry backoff) are transient side feedback: they must
            // land in the trailing ⓘ notification slot (right side) and never replace the leading
            // "ready"/"working" state on the left
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-pause-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("ready");

            final var sessionId = shellRegistry.sessions().iterator().next();
            shellRegistry
                    .active(sessionId)
                    .frontend()
                    .onEvent(new AgentEvent.Pause(
                            AgentEvent.Pause.Kind.RETRY,
                            "LLM call failed, retrying in ~0s (attempt 2/3)",
                            2000,
                            sessionId,
                            eventWorkdir));

            // the ⓘ notification slot carries the message; the leading status stays "ready"
            awaitText("ⓘ LLM call failed, retrying in ~0s (attempt 2/3)");
            awaitText("ready");
            // the ⓘ sits to the right of the leading status column
            final var infoCol = columnOfLineContaining("ⓘ LLM call failed");
            final var readyCol = columnOfLineContaining("ready");
            assertTrue(infoCol > readyCol, "the pause notification must render to the right of the leading status");

            quit();
        }

        @Test
        void clearRestoresWelcomeScreen() throws Exception {
            final var shellRegistry = testRegistry();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    shellRegistry.onEvent(new AgentEvent.TurnDone("some answer", sessionId, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-clear-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("hi"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("some answer");

            tuiRef.get().dispatch(KeyEvent.ofChar('l', KeyModifiers.CTRL));
            awaitText("Type a prompt and press Enter");

            quit();
        }

        @Test
        void btwAnswersInModalWithoutEchoOrPersistence() throws Exception {
            llm.answer("side answer");
            final var shellRegistry = testRegistry();
            // the stub turn parks on releaseTurn once it signaled turnStarted, so the
            // /btw modal is exercised over a deterministically-live turn instead of a sleep
            final var turnStarted = new CountDownLatch(1);
            final var releaseTurn = new CountDownLatch(1);
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    try {
                        turnStarted.countDown();
                        releaseTurn.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    shellRegistry.onEvent(new AgentEvent.TextDelta("hello from stub", sessionId, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TurnDone("hello from stub", sessionId, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-btw-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("say hi"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            assertTrue(
                    turnStarted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the stub turn did not start before the timeout");
            // the stub turn is parked on releaseTurn: ask the side question while it runs
            tuiRef.get().dispatch(new PasteEvent("/btw what is this"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("Q: what is this");
            awaitText("side answer");

            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("Q: what is this");
            assertTrue(sessionAppends.isEmpty(), "btw must never write to the session store");
            awaitTextGone("`/btw");

            releaseTurn.countDown();
            awaitText("hello from stub");

            quit();
        }

        @Test
        void feedbackModalShowsFullQuestionAndWrapsLongOptions() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-feedback-modal-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a fenced code block long enough that the old fixed 6-row question area
            // would have hidden its tail: the modal must grow to show every line
            final var codeLines = new ArrayList<String>();
            codeLines.add("```java");
            for (int i = 1; i <= 10; i++) {
                codeLines.add("line " + i + ": final var value = compute(" + i + ");");
            }
            codeLines.add("```");
            final var longQuestion = String.join("\n", codeLines);
            // a single option long enough to wrap within the panel width
            final var longOption = "option-" + "with-a-very-long-description-that-definitely-wraps-".repeat(3) + "tail";
            attachFeedbackPrompt(command, List.of(new Question("q1", longQuestion, List.of(longOption), false)), 0);

            // the last code line (line 10) is visible, and the trailing ``` fence too
            awaitText("line 10: final var value = compute(10);");
            awaitText("```");
            // the long option is present (wrapped), and its tail survives the wrap
            awaitText("tail");
            // the own-answer pseudo-option still renders after the wrapped option
            awaitText("Type your own answer...");

            // the tabs (first word of each question) stay bounded: the long single-word
            // marker must not overflow the tab bar
            assertTrue(
                    backend.text().stripTrailing().split("\n", -1).length <= HeadlessBackend.HEIGHT,
                    "no extra rows may appear beyond the screen:\n" + backend.text());

            quit();
        }

        @Test
        void feedbackOptionsScrollWindowKeepsFocusVisible() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-feedback-scroll-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a long wrapped question pushes the modal to its 4/5 height cap even on a tall frame, so
            // the options panel stays small: 8 options + the own-answer row overflow it and moving
            // focus must scroll the window to keep the focused option visible
            final var options = new ArrayList<String>();
            for (int i = 0; i < 8; i++) {
                options.add("option " + i + " choice");
            }
            attachFeedbackPrompt(command, List.of(new Question("q", "pick ".repeat(400), options, false)), 0);
            awaitText("option 0 choice");

            for (int i = 0; i < 6; i++) {
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.DOWN));
            }
            // the focused option stays visible and the window is scrolled (indicator + top row gone)
            awaitText("option 6 choice");
            assertTrue(
                    backend.text().contains("▲"), "the scroll indicator must show once scrolled:\n" + backend.text());
            assertFalse(
                    backend.text().contains("option 0 choice"), "the top must scroll out of view:\n" + backend.text());

            quit();
        }

        @Test
        void feedbackModalGrowsToFitManyOptionsOnTallFrame() throws Exception {
            backend = new HeadlessBackend(120, 40);
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-feedback-presize-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // 12 options would previously overflow the fixed panel and pop a scroll window; with
            // the content pre-sizing the modal grows so every option is visible at once
            final var options = new ArrayList<String>();
            for (int i = 0; i < 12; i++) {
                options.add("option " + i);
            }
            attachFeedbackPrompt(command, List.of(new Question("q", "pick one", options, false)), 0);
            awaitText("option 0");
            awaitText("option 11");
            awaitText("Type your own answer...");
            // no scroll window anywhere: on a tall frame the modal grows instead of scrolling
            assertFalse(
                    backend.text().contains("▲"), "a tall frame must grow the modal, not scroll:\n" + backend.text());

            quit();
        }

        @Test
        void feedbackQuestionScrollsWhenCappedInsteadOfClipping() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-feedback-qscroll-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a question far longer than the capped modal (4/5 of the frame) can show must be reachable
            // by scrolling, not silently clipped (the old Overflow.CLIP dropped everything past the
            // visible rows)
            final var longQuestion = new StringBuilder();
            for (int i = 1; i <= 80; i++) {
                longQuestion.append("row ").append(String.format("%02d", i)).append(" content\n");
            }
            final var trimmed = longQuestion.toString().stripTrailing();
            attachFeedbackPrompt(command, List.of(new Question("q", trimmed, List.of("a", "b"), false)), 0);
            awaitText("row 01 content");
            // the tail is not visible yet and the n/total affordance confirms there is more
            assertFalse(
                    backend.text().contains("row 80 content"),
                    "the tail must be clipped before scrolling:\n" + backend.text());
            awaitText("▼");

            // scrolling down (multi-page) reveals the deep tail and scrolls the top out of view,
            // replacing the previous silent unreachable clip (the exact last line may sit under the
            // scroll affordance, exactly like the options panel's bottom-row indicator)
            for (int i = 0; i < 6; i++) {
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_DOWN));
            }
            awaitText("row 76 content");
            assertTrue(backend.text().contains("row 78 content"), "deep tail reachable:\n" + backend.text());
            assertTrue(backend.text().contains("▲"), "scrolled state must be indicated:\n" + backend.text());
            awaitNoText("row 01 content");

            quit();
        }

        @Test
        void btwRetryRetriesAnEmptyAnswerInModal() throws Exception {
            // an empty /btw answer (with completion tokens) becomes a retryable failure surfaced in the
            // modal
            llm.answer("");
            final var shellRegistry = testRegistry();
            final var turnStarted = new CountDownLatch(1);
            final var releaseTurn = new CountDownLatch(1);
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    try {
                        turnStarted.countDown();
                        releaseTurn.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    shellRegistry.onEvent(new AgentEvent.TextDelta("hello from stub", sessionId, eventWorkdir));
                    shellRegistry.onEvent(new AgentEvent.TurnDone("hello from stub", sessionId, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-btw-retry-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("say hi"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            assertTrue(
                    turnStarted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the stub turn did not start before the timeout");
            tuiRef.get().dispatch(new PasteEvent("/btw what is this"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("Q: what is this");
            // the empty answer surfaces a retryable failure with the Retry affordance in the modal footer
            awaitText("r Retry");

            llm.answer("the retried answer");
            tuiRef.get().dispatch(KeyEvent.ofChar('r'));
            awaitText("Q: what is this");
            awaitText("the retried answer");
            assertTrue(sessionAppends.isEmpty(), "btw retry must never write to the session store");

            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("Q: what is this");
            releaseTurn.countDown();
            awaitText("hello from stub");
            quit();
        }

        @Test
        void compactionNoticesPinAndReleaseTheCompactingNotificationSlot() throws Exception {
            // the compaction lifecycle feedback (A): a "Compaction in progress..." INFO notice must pin
            // "ⓘ compacting..." in the notification slot bypassing the 5s TTL, and the "Compacted: ..."
            // result notice must clear it and show the outcome with the normal TTL.
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-compaction-notice-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("ready");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            frontend.onEvent(AgentEvent.Notice.compactionStart(
                    "Compaction in progress... (\u2248 51234 estimated tokens)", sessionId, eventWorkdir));

            awaitText("ⓘ compacting...");

            // the result notice replaces the pinned indicator with the outcome
            frontend.onEvent(AgentEvent.Notice.compacted("Compacted: context 68% -> 41%", sessionId, eventWorkdir));
            awaitText("ⓘ Compacted: context 68% -> 41%");
            awaitTextGone("ⓘ compacting...");

            quit();
        }

        @Test
        void compactionGateNoticeClearsTheCompactingIndicator() throws Exception {
            // an escalation (Compaction gate:) is a result notice too: it must clear the pinned
            // indicator and surface the gate message; it must not linger pinned afterwards.
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-compaction-gate-notice-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("ready");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            frontend.onEvent(AgentEvent.Notice.compactionStart(
                    "Compaction in progress... (\u2248 51234 estimated tokens)", sessionId, eventWorkdir));
            awaitText("ⓘ compacting...");

            frontend.onEvent(AgentEvent.Notice.compactionGateEscalated(
                    "Compaction gate: the context cannot be compacted below the trigger. Run /compact urgent "
                            + "to force local truncation, or reduce the session size. Continuing and monitoring "
                            + "the provider hard limit.",
                    sessionId,
                    eventWorkdir));
            awaitText("✗ Compaction gate: the context cannot be compacted below the trigger");
            awaitTextGone("ⓘ compacting...");

            quit();
        }

        @Test
        void cancelledTurnClearsThePinnedCompactingIndicator() throws Exception {
            // a turn that ends without a result notice (cancel/interrupt) must clear the pinned
            // indicator:
            // it can never outlive its turn
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-compaction-cancel-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("ready");

            final var sessionId = shellRegistry.sessions().iterator().next();
            final var frontend = shellRegistry.active(sessionId).frontend();
            frontend.onEvent(AgentEvent.Notice.compactionStart(
                    "Compaction in progress... (\u2248 51234 estimated tokens)", sessionId, eventWorkdir));
            awaitText("ⓘ compacting...");

            frontend.onEvent(new AgentEvent.TurnCancelled(sessionId, eventWorkdir));
            awaitTextGone("ⓘ compacting...");

            quit();
        }

        @Test
        void thinkingStreamsInlineAndCtrlTTogglesItBack() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-thinking-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // live thinking renders inline in the conversation panel (no dedicated panel)
            shellRegistry.onEvent(new AgentEvent.ThinkingDelta("pondering", sid(shellRegistry), eventWorkdir));
            awaitText("pondering");

            tuiRef.get().dispatch(KeyEvent.ofChar('t', KeyModifiers.CTRL));
            awaitTextGone("pondering");
            tuiRef.get().dispatch(KeyEvent.ofChar('t', KeyModifiers.CTRL));
            awaitText("pondering");

            quit();
        }

        @Test
        void thinkingShowsOneSpinnerFamilyAcrossStripAndPinnedLine() throws Exception {
            // regression: the status strip picked CLOCK (the first-token wait armed at turn start)
            // while the pinned/in-pane thinking line hard-coded SQUARE_CORNERS, so a reasoning
            // round animated two different spinners at once (🕐 + ◰). All busy lines must render
            // through the same shared SpinnerState so only one family is ever on screen.
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-thinking-single-spinner-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // submit a real prompt so the first-token CLOCK wait is armed, then stream thinking:
            // the strip must switch to the same SQUARE_CORNERS family as the pinned/in-pane line
            tuiRef.get().dispatch(new PasteEvent("say hi"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            shellRegistry.onEvent(
                    new AgentEvent.ThinkingDelta("pondering same spinner", sid(shellRegistry), eventWorkdir));

            awaitText("thinking... (0s)");
            // both busy lines must show the SQUARE_CORNERS family: the clock frames (U+1F550-1F55B)
            // must never share the screen with a square-corner frame. The spinner advances with the
            // tick, so polling for a square frame and then checking a single grab is racy: assert
            // the invariant on the very frame that shows a square.
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            var squareFrame = (String) null;
            while (squareFrame == null && System.nanoTime() < deadline) {
                final var text = backend.text();
                if (text.contains("◰") || text.contains("◳") || text.contains("◲") || text.contains("◱")) {
                    squareFrame = text;
                } else {
                    backend.awaitFrame(1_000);
                }
            }
            assertNotNull(squareFrame, "expected the square-corner thinking spinner on screen:\n" + backend.text());
            final var hasClock = squareFrame.contains("\ud83d\udd50")
                    || squareFrame.contains("\ud83d\udd51")
                    || squareFrame.contains("\ud83d\udd52")
                    || squareFrame.contains("\ud83d\udd53")
                    || squareFrame.contains("\ud83d\udd54")
                    || squareFrame.contains("\ud83d\udd55")
                    || squareFrame.contains("\ud83d\udd56")
                    || squareFrame.contains("\ud83d\udd57")
                    || squareFrame.contains("\ud83d\udd58")
                    || squareFrame.contains("\ud83d\udd59")
                    || squareFrame.contains("\ud83d\udd5a")
                    || squareFrame.contains("\ud83d\udd5b");
            assertFalse(hasClock, "the CLOCK spinner must not animate while SQUARE_CORNERS is shown:\n" + squareFrame);

            quit();
        }

        @Test
        void thinkingTimerAdvancesWhileToolRunning() throws Exception {
            final var clock = new MutableClock(0);
            final var shellRegistry = testRegistry();
            final var command = command(
                    shellRegistry,
                    stubAgentLoop(),
                    true,
                    newUsageTracker(),
                    new TodoStore(tuiConfig, formatMapper),
                    clock);
            uiThread = new Thread(command, "tui-thinking-tool-timer-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // reasoning deltas can stream while a tool is running (subagent output, parallel
            // batches): the timer must arm on the first indicator frame even though the
            // pinned footer is busy showing the tool line
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"mvn test\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ThinkingDelta("pondering while building", sid(shellRegistry), eventWorkdir));
            awaitTextWithin("thinking... (0s)", TIMEOUT_MS);

            clock.advance(5_000);
            awaitTextWithin("thinking... (5s)", TIMEOUT_MS);

            quit();
        }

        @Test
        void statusStripStaysNonBlankAndDoesNotRepeatTheCallLineLabel() throws Exception {
            // The strip must NEVER read blank while the agent is active or not finished. The animated
            // phase label (calling <tool> / thinking (Ns) / agent is working...) lives only on the call
            // line (renderCalling) right above the strip; the strip carries a steady "working" word so
            // it is never blank AND never duplicates the exact busy label shown on the call line.
            final var shellRegistry = testRegistry();
            final var turnStarted = new CountDownLatch(1);
            final var releaseTurn = new CountDownLatch(1);
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    try {
                        turnStarted.countDown();
                        releaseTurn.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop, true, newUsageTracker());
            uiThread = new Thread(command, "tui-status-bar-dedup-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // start a parked turn so the agent is busy, then fire a tool start to drive the call line
            tuiRef.get().dispatch(new PasteEvent("first"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            assertTrue(
                    turnStarted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the stub turn did not start before the timeout");
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"mvn test\"}", sid(shellRegistry), eventWorkdir));

            // the status strip is the bottom row; the call line is the row directly above it
            final var stripRow = HeadlessBackend.HEIGHT - 1;
            final var callRow = HeadlessBackend.HEIGHT - 2;
            awaitText("calling mvn test");
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                final var lines = backend.text().split("\n");
                if (lines[callRow].contains("calling mvn test")) {
                    assertFalse(
                            lines[stripRow].contains("calling mvn test"),
                            "the always-visible status strip must not repeat the busy label shown on the call"
                                    + " line:\n"
                                    + backend.text());
                    assertTrue(
                            lines[stripRow].contains("working"),
                            "the always-visible status strip must never be blank while the agent is active"
                                    + " (expected a steady 'working' word):\n"
                                    + backend.text());
                    releaseTurn.countDown();
                    quit();
                    return;
                }
                assertTrue(
                        backend.awaitFrame(1_000), "no frame while waiting for the busy call line:\n" + backend.text());
            }
            releaseTurn.countDown();
            fail("the call line never showed the calling label:\n" + backend.text());
        }

        @Test
        void firstTokenWaitCounterAdvancesWhileBusyInert() throws Exception {
            // regression: a busy-but-inert turn (turn running, no tool, no reasoning stream yet) used to
            // drop the busy animation because needsTickRender/spinnerState.advance were gated on
            // runningTool/activeWait/thinkingLength but NOT on busy. A slow provider waiting for the
            // first
            // token therefore showed a frozen "thinking... (0s)" that never advanced and, on the 3.5s
            // idle
            // heartbeat, no longer animated at all — looking like nothing was happening. The wait now has
            // its own armed-at clock and renders a live elapsed "thinking... (Ns)" counter regardless of
            // whether any event has arrived.
            final var clock = new MutableClock(0);
            final var shellRegistry = testRegistry();
            final var turnStarted = new CountDownLatch(1);
            final var releaseTurn = new CountDownLatch(1);
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    try {
                        turnStarted.countDown();
                        releaseTurn.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(
                    shellRegistry, agentLoop, true, newUsageTracker(), new TodoStore(tuiConfig, formatMapper), clock);
            uiThread = new Thread(command, "tui-first-token-inert-timer-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // start a parked turn: the busy line must show the first-token CLOCK wait immediately
            tuiRef.get().dispatch(new PasteEvent("first"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            assertTrue(
                    turnStarted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the stub turn did not start before the timeout");

            // no tool, no thinking event: the wait is inert, but the elapsed counter must still advance
            awaitTextWithin("thinking... (0s)", TIMEOUT_MS);
            clock.advance(5_000);
            awaitTextWithin("thinking... (5s)", TIMEOUT_MS);
            clock.advance(6_000);
            awaitTextWithin("thinking... (11s)", TIMEOUT_MS);

            releaseTurn.countDown();
            quit();
        }

        @Test
        void thinkingTimerRestartsOnEachTurn() throws Exception {
            final var clock = new MutableClock(0);
            final var shellRegistry = testRegistry();
            final var command = command(
                    shellRegistry,
                    stubAgentLoop(),
                    true,
                    newUsageTracker(),
                    new TodoStore(tuiConfig, formatMapper),
                    clock);
            uiThread = new Thread(command, "tui-thinking-timer-reset-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            shellRegistry.onEvent(new AgentEvent.ThinkingDelta("first round", sid(shellRegistry), eventWorkdir));
            awaitTextWithin("thinking... (0s)", TIMEOUT_MS);
            clock.advance(30_000);
            awaitTextWithin("thinking... (30s)", TIMEOUT_MS);

            // the turn ends while no idle frame may ever catch the reset condition: the
            // next thinking round must restart from zero, not resume the stale timer
            shellRegistry.onEvent(new AgentEvent.TurnDone("answer", sid(shellRegistry), eventWorkdir));
            awaitText("answer");
            awaitTextGoneWithin("thinking... (30s)", TIMEOUT_MS);

            clock.advance(5_000);
            shellRegistry.onEvent(new AgentEvent.ThinkingDelta("second round", sid(shellRegistry), eventWorkdir));
            awaitTextWithin("thinking... (0s)", TIMEOUT_MS);
            clock.advance(2_000);
            awaitTextWithin("thinking... (2s)", TIMEOUT_MS);

            quit();
        }

        @Test
        void thinkingRegionsRenderFencedCodeInline() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-thinking-code-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // reasoning containing a fenced block must survive the split/highlight path and
            // render the code text inline (it used to fall through as plain prose)
            shellRegistry.onEvent(new AgentEvent.ThinkingDelta(
                    "let's inspect\n```java\npublic int x() {\n return 1;\n}\n```", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.TurnDone("answer", sid(shellRegistry), eventWorkdir));
            awaitText("public int x()");

            quit();
        }

        @Test
        void streamedThoughtsKeepTheBlankLineBetweenParagraphs() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-thinking-blank-line-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a paragraph separator streamed while the thought is still open must survive on screen:
            // the live tail is cut at a newline and the two halves parsed independently, so without
            // restoring the separator the paragraphs render glued (which is what made thoughts read
            // dense)
            shellRegistry.onEvent(new AgentEvent.ThinkingDelta("first paragraph", sid(shellRegistry), eventWorkdir));
            awaitText("first paragraph");
            shellRegistry.onEvent(new AgentEvent.ThinkingDelta("\n\nsecond", sid(shellRegistry), eventWorkdir));
            awaitText("second");
            shellRegistry.onEvent(new AgentEvent.ThinkingDelta(" paragraph", sid(shellRegistry), eventWorkdir));
            awaitText("second paragraph");
            // the flushed render (the live region is replaced by the memoized one) must keep it too
            shellRegistry.onEvent(new AgentEvent.TurnDone("answer", sid(shellRegistry), eventWorkdir));
            awaitText("answer");
            assertBlankRowBetween("first paragraph", "second paragraph");

            quit();
        }

        @Test
        void streamedThoughtsKeepTheBlankLineAroundAFence() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-thinking-blank-fence-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a fenced block is split out of the prose and highlighted on its own: it must not glue to
            // the paragraph above it, and must not leave a phantom row after it either
            shellRegistry.onEvent(new AgentEvent.ThinkingDelta(
                    "let's inspect\n\n```java\npublic int x() {\n return 1;\n}\n```",
                    sid(shellRegistry),
                    eventWorkdir));
            awaitText("public int x()");
            shellRegistry.onEvent(new AgentEvent.TurnDone("answer", sid(shellRegistry), eventWorkdir));
            awaitText("answer");
            assertBlankRowBetween("let's inspect", "public int x()");

            quit();
        }

        @Test
        void assistantAnswerRendersFencedCodeIndentedAndHighlighted() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-answer-code-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a fenced java block inside a final assistant ANSWER must be split, boxed and
            // syntax-highlighted like it is inside a thought: indentation kept + keyword colored.
            final var code = """
          public static native BiPredicate declaredType(Object value);

          public BiPredicate declaredType() {
              return (BiPredicate) field.get(null);
          }
          """;
            shellRegistry.onEvent(new AgentEvent.TurnDone(
                    "here is the method:\n```java\n" + code + "```", sid(shellRegistry), eventWorkdir));

            // the code reached rendering (not dropped) and its body indentation survived
            awaitText("public static native BiPredicate declaredType");
            awaitText("    return (BiPredicate) field.get(null);");

            // a java keyword is coloured for real (not plain prose) when the CODE path is taken
            final var col = columnOfLineContaining("public BiPredicate declaredType()");
            final var row = rowOf("public BiPredicate declaredType()");
            assertTrue(
                    col >= 0 && row >= 0 && backend.fg(col, row).isPresent(),
                    "expected the 'public' keyword to be syntax-coloured on the row containing it, fg="
                            + backend.fg(col, row));

            quit();
        }

        @Test
        void collapsedTodosStillRenderTheList() throws Exception {
            final var shellRegistry = testRegistry();
            // a store whose render() yields a real todo so the panel auto-shows (expanded)
            final var todoStore = new TodoStore(tuiConfig, formatMapper) {
                @Override
                public String render() {
                    return "[> ] fix header padding";
                }
            };
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker(), todoStore);
            uiThread = new Thread(command, "tui-todos-collapse-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitText("fix header padding");

            // collapsed still renders the list in the narrow column
            tuiRef.get().dispatch(KeyEvent.ofChar('p', KeyModifiers.CTRL));
            awaitText("fix header padding");

            // collapsed -> hidden drops the list
            tuiRef.get().dispatch(KeyEvent.ofChar('p', KeyModifiers.CTRL));
            awaitTextGone("fix header padding");

            // hidden -> expanded brings it back
            tuiRef.get().dispatch(KeyEvent.ofChar('p', KeyModifiers.CTRL));
            awaitText("fix header padding");

            quit();
        }

        @Test
        void thinkingSlashCommandTogglesFlushedThoughts() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-thinking-slash-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a flushed thought region sits in the transcript
            shellRegistry.onEvent(new AgentEvent.ThinkingDelta("secret reasoning", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.TurnDone("answer", sid(shellRegistry), eventWorkdir));
            awaitText("secret reasoning");

            tuiRef.get().dispatch(new PasteEvent("/thinking hide"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitTextGone("secret reasoning");

            tuiRef.get().dispatch(new PasteEvent("/thinking show"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("secret reasoning");

            quit();
        }

        @Test
        void toolOutputCondensedByDefaultAndCtrlYCyclesThreeStates() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-output-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"cd /tmp && ls -la\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "file.txt", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("bash", "file.txt", true, sid(shellRegistry), eventWorkdir));
            // collapsed by default: the dim one-liner carries the significant command, not the output
            awaitText("✓ bash");
            awaitText("ls -la");
            awaitTextGone("file.txt");

            tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
            awaitText("file.txt"); // shown: full card restored with args and output

            tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
            awaitTextGone("file.txt"); // hidden: nothing rendered any more
            awaitTextGone("✓ bash");
            awaitTextGone("ls -la");

            tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
            awaitText("✓ bash"); // collapsed again
            awaitText("ls -la");
            awaitTextGone("file.txt");

            quit();
        }

        @Test
        void toolOutputSlashCommandHidesAndCollapses() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-output-slash-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "secret-out", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("bash", "secret-out", true, sid(shellRegistry), eventWorkdir));
            awaitText("✓ bash"); // collapsed default

            tuiRef.get().dispatch(new PasteEvent("/tool-output hide"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitTextGone("✓ bash");
            awaitTextGone("ls -la");

            tuiRef.get().dispatch(new PasteEvent("/tool-output collapse"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("✓ bash");
            awaitTextGone("secret-out");

            quit();
        }

        @Test
        void readCardRendersNumberedJavaOutputWithHighlightedContent() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-read-highlight-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // finished read of a .java file: the numbered body must render with the content
            // syntax-highlighted (the card falls into the special read/read_symbol path)
            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "read", "{\"path\":\"src/Foo.java\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolFinished(
                    "read",
                    " 1\tpublic class Foo {\n 2\t  private int x;\n 3\t}\n",
                    true,
                    sid(shellRegistry),
                    eventWorkdir));

            awaitText("✓ read");
            // collapsed by default: expand to reveal the body
            tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
            awaitText("public class Foo");
            awaitText("private int x");

            // the line-number gutter precedes the highlighted content on the same row
            final var row = rowOf("public class Foo");
            final var line = backend.text().split("\n")[row];
            assertTrue(line.contains("1 public class Foo"), "line-number gutter precedes the content: " + line);
            // the keyword tokenizer colored "public" with the theme keyword color, proving the
            // CODE branch ran (and that it is not the muted gutter color)
            final var column = columnOfLineContaining("public");
            assertEquals(
                    Color.hex(CodeTheme.ONE_DARK.keyword()),
                    backend.fg(column, row).orElse(null),
                    "the highlighted 'public' must carry the keyword color");

            quit();
        }

        @Test
        void readSymbolCardHighlightsFromTheOutputHeaderFileReference() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-read-symbol-highlight-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "read_symbol", "{\"symbol\":\"Foo#bar\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolFinished(
                    "read_symbol",
                    "symbol method Foo#bar (src/Foo.java:12-14)\n"
                            + " 1\tpublic void bar() {\n"
                            + " 2\t  return;\n"
                            + " 3\t}\n",
                    true,
                    sid(shellRegistry),
                    eventWorkdir));

            // the finished card is collapsed by default (no args in the header: the summary
            // shows only the name); expand it to reveal the header + numbered body
            awaitText("✓ read_symbol");
            tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
            awaitText("symbol method Foo#bar");
            awaitText("public void bar()");
            final var row = rowOf("public void bar()");
            final var column = columnOfLineContaining("public");
            assertEquals(
                    Color.hex(CodeTheme.ONE_DARK.keyword()),
                    backend.fg(column, row).orElse(null),
                    "read_symbol output must be highlighted from its header file reference");

            quit();
        }

        @Test
        void toolRunGroupsConsecutiveFinishedCallsIntoOneCollapsibleHeader() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-run-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // two consecutive finished calls (grep then read) become one run
            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"grep x src\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "hit", true, sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("read", "{\"path\":\"a.java\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("read", "class A {}", true, sid(shellRegistry), eventWorkdir));

            // collapsed by default in COLLAPSED mode: only the run header survives (the header
            // carries the command names in its summary, but member outputs are hidden)
            awaitText("2 tools");
            awaitTextGone("class A {}");

            // clicking the run header expands the members (COLLAPSED open shows one-liners, not outputs)
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("2 tools")));
            awaitText("✓ read #2 - a.java");
            awaitText("✓ bash #1 - grep x src");

            // re-clicking the header collapses it back: the member lines disappear again
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("2 tools")));
            awaitTextGone("✓ read #2 - a.java");
            awaitTextGone("✓ bash #1 - grep x src");
            awaitText("2 tools");

            quit();
        }

        @Test
        void toolRunsAreIndependentPerUserTurnAndToggleIndependently() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-runs-independent-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // burst A: two consecutive finished calls
            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"grep x src\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "hit", true, sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("read", "{\"path\":\"a.java\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("read", "class A {}", true, sid(shellRegistry), eventWorkdir));

            // a user turn separates the bursts: the runs must not merge into one mega-run
            shellRegistry.onEvent(new AgentEvent.PromptIntegrated("second question", sid(shellRegistry), eventWorkdir));

            // burst B: another two consecutive finished calls
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("bash", "total 8", true, sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("read", "{\"path\":\"b.java\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("read", "class B {}", true, sid(shellRegistry), eventWorkdir));

            // both runs are collapsed by default in COLLAPSED mode: two independent run headers,
            // and no member line is rendered while a run is collapsed (the toggle is observable)
            final var firstHeader = rowOf("2 tools");
            awaitText("2 tools");
            assertTrue(secondHeaderIsBelow(firstHeader, "2 tools"), "expected two separate run headers");
            awaitTextGone("✓ bash #1 - grep x src");
            awaitTextGone("✓ read #2 - a.java");
            awaitTextGone("✓ bash #3 - ls -la");
            awaitTextGone("✓ read #4 - b.java");
            // full outputs stay hidden while the runs are collapsed
            awaitTextGone("total 8");
            awaitTextGone("class A {}");
            awaitTextGone("class B {}");
            // both headers are collapsed (▶ chevron): nothing is expanded yet
            assertEquals(2, occurrences("▶ 2 tools"));
            assertEquals(0, occurrences("▼ 2 tools"));

            // opening run A reveals its member one-liners (and only A's)
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, firstHeader));
            awaitOccurrences("▼ 2 tools", 1);
            assertEquals(1, occurrences("▶ 2 tools"));
            awaitText("✓ bash #1 - grep x src");
            awaitText("✓ read #2 - a.java");
            // run B stayed collapsed: its members are still hidden
            awaitTextGone("✓ bash #3 - ls -la");
            awaitTextGone("✓ read #4 - b.java");

            // clicking an expanded run's member one-liner opens that tool's full-output modal
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ read #2 - a.java")));
            awaitText("full output");
            awaitText("class A {}");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("full output");

            // re-clicking the first header collapses it back: its members disappear again
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, firstHeader));
            awaitOccurrences("▼ 2 tools", 0);
            assertEquals(2, occurrences("▶ 2 tools"));
            awaitTextGone("✓ bash #1 - grep x src");
            awaitTextGone("✓ read #2 - a.java");

            // expanding the second run works even though the first is collapsed
            final var secondHeader = rowOfBelow(firstHeader, "2 tools");
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, secondHeader));
            awaitOccurrences("▼ 2 tools", 1);
            assertEquals(1, occurrences("▶ 2 tools"));
            awaitText("✓ bash #3 - ls -la");
            awaitText("✓ read #4 - b.java");
            awaitTextGone("✓ bash #1 - grep x src");
            awaitTextGone("✓ read #2 - a.java");

            // a member click opens the modal (run B open: the one-liner members are clickable
            // under COLLAPSED mode)
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ bash #3 - ls -la")));
            awaitText("full output");
            awaitText("total 8");

            quit();
        }

        @Test
        void toolRunContainerHasNoBackgroundWhileLoneCardsKeepTheTint() throws Exception {
            // the collapsible run container inherits the transcript background (no interior
            // fill), while a lone tool card keeps its neutral background tint - this is the
            // visual contract for the run-box change, asserted on the rendered cells
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-run-bg-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // two consecutive finished calls group into one collapsible run (the box renders
            // in every visibility mode; SHOWN keeps it open with its member cards)
            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"grep x src\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "hit", true, sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("read", "{\"path\":\"a.java\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("read", "class A {}", true, sid(shellRegistry), eventWorkdir));
            awaitText("2 tools");

            // SHOWN opens the run: the member cards render inside the box
            tuiRef.get().dispatch(new PasteEvent("/tool-output show"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("a.java");

            // the box spans the full chat body: its top border sits one row above the header
            // (rowOf is the first row containing the token), the interior starts inside the
            // rounded border (x=3, one column past the left border)
            final var runTop = rowOf("2 tools") - 1;
            assertTrue(backend.bg(3, runTop + 1).isEmpty(), "run box interior must not be tinted:\n" + backend.text());
            assertTrue(backend.bg(10, runTop + 1).isEmpty(), "run box interior must not be tinted:\n" + backend.text());

            // a turn boundary flushes the run; the next lone tool renders as a single tinted card
            shellRegistry.onEvent(new AgentEvent.TurnDone("wrapped up", sid(shellRegistry), eventWorkdir));
            awaitText("wrapped up");
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("bash", "total 8", true, sid(shellRegistry), eventWorkdir));
            awaitText("ls -la");
            final var header = rowOf("ls -la"); // first content row, right below the card top border
            final var expected = TuiColors.blend("#eeeeee", "#141414", 0.95).toRgb();
            final var actual = backend.bg(3, header).orElseThrow().toRgb();
            assertEquals(expected, actual, "lone tool card must keep its background tint:\n" + backend.text());

            quit();
        }

        @ParameterizedTest
        @CsvSource({"false", "true"})
        void clickingLoneShownCardHeaderCollapsesAndBorderRowClickAlsoHits(final boolean clickBorder) throws Exception {
            // regression: the lone bordered card's click anchor used to sit on the block's
            // top-border row, so clicking the visible header text did nothing until a resize
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-lone-card-click-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("bash", "total 8", true, sid(shellRegistry), eventWorkdir));
            tuiRef.get().dispatch(new PasteEvent("/tool-output show"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            // see hoveringLoneShownCardHeaderReversesTheHeaderRow: "ls -la" also matches the
            // collapsed line, so await the SHOWN-only card title before reading the row
            awaitOccurrences("#1 - ", 1);
            awaitText("total 8");
            final var clickRow = rowOf(clickBorder ? "#1 - " : "ls -la");
            // Both the visible header and its top border collapse the card to a dim one-liner.
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 5, clickRow));
            // Await the collapsed summary itself: absence of output alone must not let the
            // next click use the expanded card's old header row while a render is pending.
            awaitText("#1 - ls -la");
            awaitOccurrences("#1 - ", 1);
            awaitTextGone("total 8");
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 5, rowOf("#1 - ls -la")));
            awaitText("full output");
            awaitText("total 8");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("full output");
            quit();
        }

        @Test
        void hoveringLoneShownCardHeaderReversesTheHeaderRow() throws Exception {
            // hover feedback: the clickable strip (border + header row) is reversed while hovered
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-lone-card-hover-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("bash", "total 8", true, sid(shellRegistry), eventWorkdir));
            tuiRef.get().dispatch(new PasteEvent("/tool-output show"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            // the SHOWN card title (" #1 - 4ms") only exists once the bordered card replaced
            // the dim one-liner: "ls -la" alone also matches the collapsed line, so awaiting
            // it could race with the re-render and read a stale row
            awaitOccurrences("#1 - ", 1);
            final var borderRow = rowOf("#1 - ");
            final var headerRow = rowOf("ls -la");
            assertFalse(backend.reversed(5, headerRow), "header must not be reversed before hover:\n" + backend.text());
            assertFalse(backend.reversed(5, borderRow), "border must not be reversed before hover:\n" + backend.text());
            tuiRef.get().dispatch(MouseEvent.move(5, headerRow));
            awaitReversed(5, headerRow);
            if (!backend.reversed(5, borderRow)) {
                final var sb = new StringBuilder("headerRow=" + headerRow + " borderRow=" + borderRow + " reversed:");
                for (var y = 0; y < 30; y++) {
                    for (var x = 0; x < 92; x++) {
                        if (backend.reversed(x, y)) {
                            sb.append(" (").append(x).append(",").append(y).append(")");
                        }
                    }
                }
                throw new AssertionError(sb + "\n" + backend.text());
            }
            assertTrue(backend.reversed(5, borderRow), "border row must be reversed on hover:\n" + backend.text());
            // moving away (below the card, in the transcript's empty area) clears the highlight
            tuiRef.get().dispatch(MouseEvent.move(5, borderRow + 6));
            awaitReversedGone(5, headerRow);
            quit();
        }

        @Test
        void clickingRunningShownCardBodyOpensLiveOverlay() throws Exception {
            // a running card in SHOWN mode: clicking anywhere in the card (not just the
            // header strip) opens the live running-tool overlay
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-running-card-click-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            tuiRef.get().dispatch(new PasteEvent("/tool-output show"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"sleep 30\"}", sid(shellRegistry), eventWorkdir));
            awaitText("sleep 30");
            final var headerRow = rowOf("sleep 30");
            // click the header strip of the running card: opens the live overlay
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 5, headerRow));
            awaitText("- running ");
            awaitText("sleep 30");
            quit();
        }

        @Test
        void clickingCondensedToolLineOpensFullOutput() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-click-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "out-a\nout-b", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("bash", "out-a\nout-b", true, sid(shellRegistry), eventWorkdir));
            awaitText("✓ bash"); // condensed dim line

            // clicking the dim line opens the full-output modal even while blocks are hidden
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ bash")));
            awaitText("out-a");
            awaitText("full output");

            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("full output");
            awaitTextGone("out-a"); // back to the condensed line, cards stay hidden

            quit();
        }

        /**
         * A python command printing a piped table (eg. {@code tabulate}) used to be detected as
         * markdown and rendered as a bordered grid, prefixing every line with a vertical bar. The
         * output must stay one row per source line.
         */
        @Test
        void toolOverlayKeepsPipedOutputLinesVerbatim() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-pipes-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            final var output = """
          | name | age |
          |------|-----|
          | bob  | 42  |
          - bob is 42
          """;
            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"python3 report.py\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolStream("bash", output, sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", output, true, sid(shellRegistry), eventWorkdir));
            awaitText("✓ bash");

            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ bash")));
            awaitText("full output");
            awaitText("| name | age |");
            awaitText("|------|-----|");
            awaitText("| bob  | 42  |");

            // no grid borders were injected around the piped rows
            assertFalse(backend.text().contains("│ bob"), backend.text());
            assertFalse(backend.text().contains("┌"), backend.text());
            assertTrue(rowIndex("| name | age |") < rowIndex("|------|-----|"));
            assertTrue(rowIndex("|------|-----|") < rowIndex("| bob  | 42  |"));

            quit();
        }

        @Test
        void codeSelectionPreservesLiteralSeam() throws Exception {
            final var registry = testRegistry();
            final var command = command(registry, stubAgentLoop());
            uiThread = new Thread(command, "tui-code-seam-copy-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            registry.onEvent(new AgentEvent.TextDelta("```text\n@123@\n```", sid(registry), eventWorkdir));
            registry.onEvent(new AgentEvent.TurnDone("```text\n@123@\n```", sid(registry), eventWorkdir));
            awaitText("@123@");
            final var row = rowOf("@123@");
            final var from = columnOfLineContaining("@123@");
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, from, row));
            tuiRef.get().dispatch(MouseEvent.drag(MouseButton.LEFT, from + 4, row));
            tuiRef.get().dispatch(MouseEvent.release(MouseButton.LEFT, from + 4, row));
            final var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!backend.raw().contains(SelectionState.osc52Payload("@123@")) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(backend.raw().contains(SelectionState.osc52Payload("@123@")), backend.raw());
            quit();
        }

        @ParameterizedTest
        @CsvSource({"false", "true"})
        void dragBurstCoalescesRepaints(final boolean modal) throws Exception {
            startDragSelection(modal);
            final var row = rowOf("abcdefghijklmnopqrstuvwxyz");
            final var from = columnOfLineContaining("abcdefghijklmnopqrstuvwxyz");
            final var frames = dragBatch(runner -> {
                runner.dispatch(MouseEvent.press(MouseButton.LEFT, from, row));
                for (int i = 0; i < 2000; i++) {
                    runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 2 + i % 8, row));
                }
                runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 7, row));
            });
            assertEquals(2, frames, "one press frame and one final drag frame");
            assertTrue(backend.reversed(from + 7, row), backend.text());
            assertFalse(backend.reversed(from + 8, row), backend.text());
            quit();
        }

        @Test
        void isolatedDragPaintsWithoutTickOrNextInput() throws Exception {
            startDragSelection(false);
            final var row = rowOf("abcdefghijklmnopqrstuvwxyz");
            final var from = columnOfLineContaining("abcdefghijklmnopqrstuvwxyz");
            assertEquals(1, dragBatch(runner -> runner.dispatch(MouseEvent.press(MouseButton.LEFT, from, row))));
            assertEquals(1, dragBatch(runner -> runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 5, row))));
            assertTrue(backend.reversed(from + 5, row), backend.text());
            assertFalse(backend.reversed(from + 6, row), backend.text());
            assertEquals(0, dragBatch(runner -> runner.dispatch(new Event() {})), "no lingering repaint request");
            quit();
        }

        @Test
        void resizeInvalidatesOldRepaintWithoutLosingNewDrag() throws Exception {
            startDragSelection(false);
            final var row = rowOf("abcdefghijklmnopqrstuvwxyz");
            final var from = columnOfLineContaining("abcdefghijklmnopqrstuvwxyz");
            final var frames = dragBatch(runner -> {
                runner.dispatch(MouseEvent.press(MouseButton.LEFT, from, row));
                runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 3, row));
                // Resize is rendered by TuiRunner itself, without calling the application's handler.
                runner.dispatch(ResizeEvent.of(HeadlessBackend.WIDTH, HeadlessBackend.HEIGHT));
                runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 7, row));
            });
            assertEquals(3, frames, "press, resize, and the newer drag repaint; the old request must be inert");
            assertTrue(backend.reversed(from + 7, row), backend.text());
            assertFalse(backend.reversed(from + 8, row), backend.text());
            quit();
        }

        @ParameterizedTest
        @CsvSource({"false", "true"})
        void dragBurstReleaseCopiesLatestCoordinates(final boolean modal) throws Exception {
            startDragSelection(modal);
            final var row = rowOf("abcdefghijklmnopqrstuvwxyz");
            final var from = columnOfLineContaining("abcdefghijklmnopqrstuvwxyz");
            final var frames = dragBatch(runner -> {
                runner.dispatch(MouseEvent.press(MouseButton.LEFT, from, row));
                for (int i = 0; i < 2000; i++) {
                    runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 2 + i % 8, row));
                }
                runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 7, row));
                runner.dispatch(MouseEvent.release(MouseButton.LEFT, from + 7, row));
            });
            assertEquals(2, frames, "release renders the latest selection and satisfies the pending repaint");
            assertTrue(backend.raw().contains(SelectionState.osc52Payload("abcdefgh")), backend.raw());
            quit();
        }

        @Test
        void dragRepaintPreservesNoopInputBoundary() throws Exception {
            startDragSelection(false);
            final var row = rowOf("abcdefghijklmnopqrstuvwxyz");
            final var from = columnOfLineContaining("abcdefghijklmnopqrstuvwxyz");
            assertEquals(3, dragBatch(runner -> {
                runner.dispatch(MouseEvent.press(MouseButton.LEFT, from, row));
                runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 3, row));
                runner.dispatch(new Event() {}); // normally returns false: still a paint boundary
                runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 7, row));
            }));
            assertTrue(backend.reversed(from + 7, row), backend.text());
            assertFalse(backend.reversed(from + 8, row), backend.text());
            quit();
        }

        @ParameterizedTest
        @CsvSource({"false", "true"})
        void dragRepaintPreservesSeparateGestures(final boolean modal) throws Exception {
            startDragSelection(modal);
            final var row = rowOf("abcdefghijklmnopqrstuvwxyz");
            final var from = columnOfLineContaining("abcdefghijklmnopqrstuvwxyz");
            assertEquals(4, dragBatch(runner -> {
                runner.dispatch(MouseEvent.press(MouseButton.LEFT, from, row));
                runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 3, row));
                runner.dispatch(MouseEvent.release(MouseButton.LEFT, from + 3, row));
                runner.dispatch(MouseEvent.press(MouseButton.LEFT, from + 10, row));
                runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 13, row));
                runner.dispatch(MouseEvent.release(MouseButton.LEFT, from + 13, row));
            }));
            final var raw = backend.raw();
            final var first = raw.indexOf(SelectionState.osc52Payload("abcd"));
            final var second = raw.indexOf(SelectionState.osc52Payload("klmn"));
            assertTrue(first >= 0 && second > first, raw);
            quit();
        }

        @Test
        void dragRepaintPreservesPasteKeyAndScrollOrder() throws Exception {
            startDragSelection(false);
            final var row = rowOf("abcdefghijklmnopqrstuvwxyz");
            final var from = columnOfLineContaining("abcdefghijklmnopqrstuvwxyz");
            dragBatch(runner -> {
                runner.dispatch(MouseEvent.press(MouseButton.LEFT, from, row));
                runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 3, row));
                runner.dispatch(new PasteEvent("DRAG_ORDER_AB"));
                runner.dispatch(KeyEvent.ofKey(KeyCode.LEFT));
                runner.dispatch(MouseEvent.scrollDown(from, row));
                runner.dispatch(KeyEvent.ofChar('X'));
            });
            assertTrue(backend.text().contains("DRAG_ORDER_AXB"), backend.text());
            quit();
        }

        @Test
        void dragRepaintUsesActiveTab() throws Exception {
            startDragSelection(false);
            final var row = rowOf("abcdefghijklmnopqrstuvwxyz");
            final var from = columnOfLineContaining("abcdefghijklmnopqrstuvwxyz");
            dragBatch(runner -> {
                runner.dispatch(MouseEvent.press(MouseButton.LEFT, from, row));
                runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 3, row));
                runner.dispatch(KeyEvent.ofChar('n', KeyModifiers.CTRL));
                runner.dispatch(new PasteEvent("NEW_TAB_AFTER_DRAG"));
            });
            assertTrue(backend.text().contains("NEW_TAB_AFTER_DRAG"), backend.text());
            assertFalse(backend.text().contains("abcdefghijklmnopqrstuvwxyz"), backend.text());
            dragBatch(runner -> runner.dispatch(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.CTRL)));
            assertTrue(backend.text().contains("abcdefghijklmnopqrstuvwxyz"), backend.text());
            quit();
        }

        @Test
        void dragRepaintIncludesQueuedBackgroundUpdates() throws Exception {
            final var registry = startDragSelection(false);
            final var row = rowOf("abcdefghijklmnopqrstuvwxyz");
            final var from = columnOfLineContaining("abcdefghijklmnopqrstuvwxyz");
            assertEquals(2, dragBatch(runner -> {
                runner.dispatch(MouseEvent.press(MouseButton.LEFT, from, row));
                runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 3, row));
                registry.onEvent(new AgentEvent.Status("BACKGROUND_DURING_DRAG", sid(registry), eventWorkdir));
            }));
            assertTrue(backend.text().contains("BACKGROUND_DURING_DRAG"), backend.text());
            quit();
        }

        @Test
        void dragRepaintDoesNotPreventShutdown() throws Exception {
            startDragSelection(false);
            final var row = rowOf("abcdefghijklmnopqrstuvwxyz");
            final var from = columnOfLineContaining("abcdefghijklmnopqrstuvwxyz");
            final var stopped = new CountDownLatch(1);
            final var runner = tuiRef.get();
            runner.runLater(() -> {
                runner.dispatch(MouseEvent.press(MouseButton.LEFT, from, row));
                runner.dispatch(MouseEvent.drag(MouseButton.LEFT, from + 3, row));
                // This is ahead of the repaint token appended by the drag handler.
                runner.runLater(() -> {
                    runner.quit();
                    stopped.countDown();
                });
            });
            assertTrue(stopped.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            quit();
        }

        /** The modal registers its own copy zone: a drag started inside it selects (and copies) it. */
        @Test
        void toolOverlayDragSelectionCopies() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-copy-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"cat out.txt\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "copy-me-line", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("bash", "copy-me-line", true, sid(shellRegistry), eventWorkdir));
            awaitText("✓ bash");

            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ bash")));
            awaitText("full output");
            awaitText("copy-me-line");

            final var row = rowOf("copy-me-line");
            final var from = columnOfLineContaining("copy-me-line");
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, from, row));
            tuiRef.get().dispatch(MouseEvent.drag(MouseButton.LEFT, from + 5, row));
            tuiRef.get().dispatch(MouseEvent.release(MouseButton.LEFT, from + 5, row));

            // the release is what copies: OSC 52 carries the dragged span of the modal body
            final var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!backend.raw().contains(SelectionState.osc52Payload("copy-m")) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(
                    backend.raw().contains(SelectionState.osc52Payload("copy-m")),
                    "expected the drag to copy the selected modal text over OSC 52, raw=" + backend.raw());

            quit();
        }

        @Test
        void runningToolStreamIsHiddenWithToolBlocks() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-running-tool-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"tail -f log\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "streamed line", sid(shellRegistry), eventWorkdir));
            awaitTextGone("streamed line"); // condensed default hides the live stream
            awaitText("tail -f log"); // dim one-liner with the significant command
            awaitText("running...");

            // clicking the running card opens the live running-tool overlay immediately
            // (no need to wait for ToolFinished): title carries "running", body streams.
            // NB: target the topmost "tail -f log" row (the card), not the status bar
            // which also shows the command while the tool runs
            final var row = rowOf("tail -f log");
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, row));
            tuiRef.get().dispatch(MouseEvent.release(MouseButton.LEFT, 2, row));
            awaitText("bash - running"); // overlay title marks the call as running
            awaitText("pending"); // pending marker in the overlay body
            awaitText("streamed line"); // the ToolStream fragment reached the overlay body

            // a later stream chunk keeps the open modal live
            shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "second line", sid(shellRegistry), eventWorkdir));
            awaitText("second line");

            // Esc closes the running overlay (no tool cancel: the loop is the stub)
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("bash - running ");
            awaitText("running..."); // back to the condensed one-liner

            tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
            awaitText("streamed line"); // cards shown: live stream visible

            quit();
        }

        @Test
        void openToolOverlayLeavesRunningStateOnToolFinished() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-overlay-finish-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"mvn -q test\"}", 7, sid(shellRegistry), null, null, eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolStream("bash", "compiling...", 7, sid(shellRegistry), null, null, eventWorkdir));
            awaitText("running...");
            final var row = rowOf("mvn -q test");
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, row));
            tuiRef.get().dispatch(MouseEvent.release(MouseButton.LEFT, 2, row));
            awaitText("bash - running"); // overlay open, marked running
            awaitText("pending"); // pending marker in the overlay body
            awaitText("compiling..."); // the ToolStream fragment reached the overlay body

            // the tool finishes while the overlay is still open: the modal must leave the
            // running state (regression: it used to keep the stale running entry forever,
            // because only ToolStream re-armed the overlay, never ToolFinished)
            shellRegistry.onEvent(new AgentEvent.ToolFinished(
                    "bash", "Exit code: 1", false, false, 7, sid(shellRegistry), null, null, eventWorkdir));
            awaitTextGone("pending"); // overlay no longer pending
            awaitTextGone("bash - running");
            awaitText("Exit code: 1"); // finished output visible in the (now closed-state) overlay

            quit();
        }

        @Test
        void turnAndSessionMetricsShowInStatusBar() throws Exception {
            final var shellRegistry = testRegistry();
            final var usageTracker = newUsageTracker();
            usageTracker.record(new Usage(10, 5, 15, 0, 0));
            final var command = command(shellRegistry, stubAgentLoop(), true, usageTracker);
            uiThread = new Thread(command, "tui-metrics-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            shellRegistry.onEvent(
                    new AgentEvent.TurnMetrics(3, 120, 400, 500, 2, 1500, sid(shellRegistry), eventWorkdir));
            awaitText("3 LLM");
            awaitText("1.5s");
            awaitText("session: 1 LLM calls");

            quit();
        }

        @Test
        void outputThroughputShowsNextToTheTurnMetrics() throws Exception {
            final var shellRegistry = testRegistry();
            final var usageTracker = newUsageTracker();
            usageTracker.record(new Usage(10, 5, 15, 0, 0));
            final var command = command(shellRegistry, stubAgentLoop(), true, usageTracker);
            uiThread = new Thread(command, "tui-tps-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            shellRegistry.onEvent(new AgentEvent.TurnMetrics(
                    3,
                    120,
                    400,
                    500,
                    2,
                    1500,
                    new AgentEvent.Throughput(150, 0, 3000, 0, 0, 3, 0, false),
                    sid(shellRegistry),
                    eventWorkdir));
            awaitText("3 LLM");
            awaitText("request 50.0 tok/s");

            shellRegistry.onEvent(new AgentEvent.TurnMetrics(
                    3,
                    120,
                    400,
                    500,
                    2,
                    1500,
                    new AgentEvent.Throughput(150, 0, 3000, 0, 0, 3, 0, true),
                    sid(shellRegistry),
                    eventWorkdir));
            awaitText("request ~50.0 tok/s");

            // a turn whose throughput cannot be measured shows no segment at all
            shellRegistry.onEvent(
                    new AgentEvent.TurnMetrics(3, 120, 400, 500, 2, 1500, sid(shellRegistry), eventWorkdir));
            awaitNoText("tok/s");

            quit();
        }

        @Test
        void serverSideThroughputIsShownNextToTheMeasuredOne() throws Exception {
            final var shellRegistry = testRegistry();
            final var usageTracker = newUsageTracker();
            usageTracker.record(new Usage(10, 5, 15, 0, 0));
            final var command = command(shellRegistry, stubAgentLoop(), true, usageTracker);
            uiThread = new Thread(command, "tui-real-tps-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            shellRegistry.onEvent(new AgentEvent.TurnMetrics(
                    3,
                    120,
                    400,
                    500,
                    2,
                    1500,
                    new AgentEvent.Throughput(150, 0, 3000, 4000, 150, 3, 3, false),
                    sid(shellRegistry),
                    eventWorkdir));
            awaitText("request 50.0 tok/s · server 37.5 tok/s");

            shellRegistry.onEvent(new AgentEvent.TurnMetrics(
                    3,
                    120,
                    400,
                    500,
                    2,
                    1500,
                    new AgentEvent.Throughput(150, 0, 3000, 2000, 80, 3, 1, false),
                    sid(shellRegistry),
                    eventWorkdir));
            awaitText("request 50.0 tok/s · server 40.0 tok/s (partial)");

            quit();
        }

        @Test
        void multilinePasteStaysInPromptUntilSubmitted() throws Exception {
            final var shellRegistry = testRegistry();
            final var received = new AtomicReference<String>();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    received.set(prompt);
                    shellRegistry.onEvent(
                            new AgentEvent.TurnDone("lines:" + prompt.lines().count(), sessionId, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop, true, newUsageTracker());
            uiThread = new Thread(command, "tui-multiline-paste-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("line one\nline two"));
            awaitText("line two"); // both lines visible in the grown prompt

            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("lines:2"); // submitted as a single prompt
            Assertions.assertEquals("line one\nline two", received.get());

            quit();
        }

        @Test
        void tabsRouteEventsPerSessionAndShowStatusIcons() throws Exception {
            final var shellRegistry = testRegistry();
            final var lastSid = new AtomicReference<String>();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    lastSid.set(sessionId);
                    shellRegistry.onEvent(new AgentEvent.TurnDone("ran:" + sessionId, sessionId, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop, true, newUsageTracker());
            uiThread = new Thread(command, "tui-tabs-routing-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // second tab becomes active on Ctrl+N - the bar appears with a close marker per tab
            tuiRef.get().dispatch(KeyEvent.ofChar('n', KeyModifiers.CTRL));
            awaitTextWithin(Glyphs.of("unicode").cross() + " ", 2_000);

            // a turn on tab two renders only in tab two's transcript
            tuiRef.get().dispatch(new PasteEvent("hi tab two"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitTextWithin("ran:", 2_000);
            final var secondSid = lastSid.get();

            // switch back to tab one: tab two's transcript must not leak into it
            // (the tab bar still shows tab two's title - "hi tab two" - on every tab, by design)
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.CTRL));
            awaitTextGone("ran:");

            // an event for the background tab two is routed to its own transcript, never the visible one
            shellRegistry.onEvent(
                    new AgentEvent.Notice(AgentEvent.Notice.Level.INFO, "bg-for-two", secondSid, eventWorkdir));
            awaitTextGone("bg-for-two");

            // back on tab two: the background text arrived while hidden, and the bar shows the done icon
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.CTRL));
            awaitTextWithin("bg-for-two", 2_000);
            awaitTextWithin(" " + Glyphs.of("unicode").check() + " ", 2_000);
            awaitTextWithin(" +", 2_000); // the trailing new-tab affordance is on the bar

            quit();
        }

        @Test
        void tabBarShowsPerTabModeBadge() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tab-mode-badge-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // plan by default: the bar carries the plan mark in the warning tone
            final var planMark = Glyphs.of("unicode").modeMark(false);
            final var buildMark = Glyphs.of("unicode").modeMark(true);
            awaitText(planMark);
            var col = columnOfLineContaining(planMark);
            var row = rowOf(planMark);
            var fg = backend.fg(col, row).orElseThrow().toRgb();
            assertEquals(0xf5, fg.r(), "plan badge red channel");
            assertEquals(0xa7, fg.g(), "plan badge green channel");
            assertEquals(0x42, fg.b(), "plan badge blue channel");

            // switch to build: the badge flips to the heavy shape and the accent tone
            tuiRef.get().dispatch(new PasteEvent("/mode build"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText(buildMark);
            col = columnOfLineContaining(buildMark);
            row = rowOf(buildMark);
            fg = backend.fg(col, row).orElseThrow().toRgb();
            assertEquals(0x9d, fg.r(), "build badge red channel");
            assertEquals(0x7c, fg.g(), "build badge green channel");
            assertEquals(0xd8, fg.b(), "build badge blue channel");

            // back to plan: the plan tone returns
            tuiRef.get().dispatch(new PasteEvent("/mode plan"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText(planMark);
            fg = backend.fg(columnOfLineContaining(planMark), rowOf(planMark))
                    .orElseThrow()
                    .toRgb();
            assertEquals(0xf5, fg.r(), "plan badge red channel after switching back");

            quit();
        }

        @Test
        void tabHoverShowsFullTitleTooltip() throws Exception {
            final var shellRegistry = testRegistry();
            final var lastSid = new AtomicReference<String>();
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    lastSid.set(sessionId);
                    shellRegistry.onEvent(new AgentEvent.TurnDone("ran:" + sessionId, sessionId, eventWorkdir));
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop, true, newUsageTracker());
            uiThread = new Thread(command, "tui-tab-tooltip-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a second tab whose title is long enough to be ellipsized in the bar
            final var fullTitle = "a session title long enough that the tab cell must ellipsize it away from view";
            tuiRef.get().dispatch(KeyEvent.ofChar('n', KeyModifiers.CTRL));
            awaitTextWithin(Glyphs.of("unicode").cross() + " ", 2_000);
            tuiRef.get().dispatch(new PasteEvent(fullTitle));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitTextWithin("ran:", 2_000); // the turn completed: tab two's title is now fullTitle

            // back on tab one: the bar still shows the ellipsized tab-two title (not the full one)
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.CTRL));
            awaitTextGone("ran:");
            final var prefix = fullTitle.substring(0, 18); // ellipsized prefix visible on the bar
            final var bar = backend.text().split("\n")[0];
            final var titleCol = bar.indexOf(prefix);
            assertTrue(titleCol >= 0, "the tab bar must contain the ellipsized tab-two title:\n" + backend.text());

            // hover the tab-two cell: the floating tooltip shows the full title
            tuiRef.get().dispatch(MouseEvent.move(titleCol, 0));
            awaitTextWithin(fullTitle, 2_000);

            // move the pointer out of the bar: the tooltip disappears
            tuiRef.get().dispatch(MouseEvent.move(5, 20));
            awaitTextGoneWithin(fullTitle, 2_000);

            quit();
        }

        @Test
        void singleTabShowsAddButtonAndCloseMarker() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-single-tab-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // with a single tab the strip still renders: the "+" affordance lets the
            // user open a second session and each tab exposes its close marker
            Assertions.assertTrue(
                    backend.text().contains(" +"), "single tab must show the add button, got:\n" + backend.text());
            Assertions.assertTrue(
                    backend.text().contains(Glyphs.of("unicode").cross() + " "),
                    "tab must show the close marker, got:\n" + backend.text());

            quit();
        }

        @Test
        void longPromptSoftWrapsInsteadOfClipping() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-softwrap-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            final var tail = "--soft-wrap-tail";
            final var longLine = "wrap".repeat(30) + tail;
            tuiRef.get().dispatch(new PasteEvent(longLine));
            // the tail is far beyond one line of the 120-col prompt: it is only visible
            // once the long line wraps onto a subsequent visual row rather than clipping
            awaitText(tail);

            quit();
        }

        @Test
        void promptCursorTrackColumnInsteadOfFixingToZero() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-cursor-col-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("hello"));
            awaitText("hello");
            // the prompt interior starts at the column of the first typed char; the
            // cursor used to always be pinned to that start column by the disabled
            // visual-column computation, so it never followed the caret
            final var promptStart = columnOfLineContaining("hello");
            final var atEnd = awaitCursorCell();
            Assertions.assertTrue(
                    atEnd[0] > promptStart,
                    "cursor must sit past the start column after typing; start=" + promptStart + " cursor=" + atEnd[0]);

            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.HOME));
            // settle on the exact home position: with the row also required to match, a transient
            // mid-reflow frame (prompt re-wrapping a row while an async refresh delays the next
            // render) cannot satisfy the wait at a stale row
            final var atHome = awaitCursorAt(promptStart, atEnd[1]);
            Assertions.assertEquals(
                    promptStart, atHome[0], "HOME must move the cursor to the first character of the line");
            Assertions.assertEquals(atEnd[1], atHome[1], "HOME must not change the cursor row");

            quit();
        }

        @Test
        void promptCursorTracksColumnInsideAsoftWrappedFragment() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-cursor-softwrap-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            final var longLine = "wrap".repeat(30);
            tuiRef.get().dispatch(new PasteEvent(longLine));
            awaitText("wrap"); // rendered (wrapped across visual rows) in the prompt
            final var promptStart = columnOfLineContaining("wrap");

            // move to the line start, then a few columns right, so the caret sits in
            // the middle of the first wrapped fragment; it must land beyond the
            // fragment-start column rather than being reset to it
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.HOME));
            for (var i = 0; i < 5; i++) {
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.RIGHT));
            }
            final var moved = awaitCursorAt(promptStart + 5);
            Assertions.assertTrue(
                    moved[0] > promptStart,
                    "cursor must sit inside the wrapped fragment, not pinned to its start; start=" + promptStart
                            + " cursor=" + moved[0]);

            quit();
        }

        @Test
        void mouseClickPositionsThePromptCursor() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-mouse-click-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // type a line, bring the caret home, then click on the 3rd character cell:
            // the caret must jump to the clicked cell, not stay at its previous spot
            tuiRef.get().dispatch(new PasteEvent("hello"));
            awaitText("hello");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.HOME));
            final var promptStart = columnOfLineContaining("hello");
            final var row = rowOf("hello");
            awaitCursorAt(promptStart);

            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, promptStart + 2, row));
            final var clicked = awaitCursorAt(promptStart + 2);
            Assertions.assertEquals(row, clicked[1], "clicking the prompt must not change the prompt row");

            // the caret really moved: a typed char lands at the click position
            tuiRef.get().dispatch(KeyEvent.ofChar('X'));
            awaitText("heXllo");

            quit();
        }

        @Test
        void upMovesAcrossWrappedFragmentsLikeAGuiEditor() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-wrap-up-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // 120 chars wrap onto a second visual fragment row of the prompt
            final var longLine = "wrap".repeat(30);
            tuiRef.get().dispatch(new PasteEvent(longLine));
            awaitText("wrap");
            final var promptStart = columnOfLineContaining("wrap");
            final var firstWrapRow = rowOf("wrap");
            final var wrappedRow = firstWrapRow + 1;

            // END places the caret at the end of the wrapped fragment; the draw fix
            // makes that caret visible on the wrapped row instead of off-screen
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.END));
            final var endCell = awaitCursorCell();
            Assertions.assertEquals(wrappedRow, endCell[1], "END must put the caret on the wrapped fragment row");
            Assertions.assertTrue(endCell[0] > promptStart, "END must put the caret inside the wrapped fragment");

            // UP moves to the previous visual fragment row, preserving the display
            // column clamped to the fragment end (GUI-editor behavior)
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.UP));
            var upCell = backend.lastReversed();
            while (upCell == null || upCell[1] != firstWrapRow) {
                assertTrue(
                        backend.awaitFrame(1_000),
                        "no frame received while waiting for the caret to move to the previous fragment row");
                upCell = backend.lastReversed();
            }
            Assertions.assertTrue(upCell[0] > promptStart, "UP must keep the caret inside the previous fragment");

            // the caret is really at the fragment boundary: a typed char lands there
            tuiRef.get().dispatch(KeyEvent.ofChar('X'));
            awaitText("Xwrap");

            quit();
        }

        @Test
        void mouseClickOnWideCharacterLandsOnItsStart() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-mouse-wide-char-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // "中" occupies two display cells; clicking either of them must place the
            // caret at the wide char start (a caret cannot sit inside a wide char)
            tuiRef.get().dispatch(new PasteEvent("a中b"));
            awaitText("a中b");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.HOME));
            final var promptStart = columnOfLineContaining("a中b");
            final var row = rowOf("a中b");

            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, promptStart + 1, row));
            final var firstCell = awaitCursorAt(promptStart + 1);
            Assertions.assertEquals(row, firstCell[1], "click must stay on the prompt row");

            // a typed char lands before the wide char -> proves the caret sits at its start
            tuiRef.get().dispatch(KeyEvent.ofChar('X'));
            awaitText("aX中b");

            quit();
        }

        @Test
        void ctrlWOnEmptyPromptDoesNotCrash() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-ctrlw-empty-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // ctrl+w used to throw StringIndexOutOfBoundsException on an empty prompt
            // (previousWord charAt(0) on a length-0 line); it must be a plain no-op.
            tuiRef.get().dispatch(KeyEvent.ofChar('w', KeyModifiers.CTRL));
            awaitText("yca coding agent"); // still rendering -> no crash

            quit();
        }

        @Test
        void ctrlWAtLineStartKillsPreviousLineLastWord() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-ctrlw-linejoin-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // two-line prompt; cursor sits on the last line after pasting
            tuiRef.get().dispatch(new PasteEvent("foo bar\nbaz"));
            awaitText("baz");
            // HOME moves the cursor to the start of the last line (col 0)
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.HOME));
            awaitCursorAt(columnOfLineContaining("baz"));
            // ctrl+w at a line start must kill the previous line's last word ("bar")
            tuiRef.get().dispatch(KeyEvent.ofChar('w', KeyModifiers.CTRL));
            awaitTextGone("foo bar");

            quit();
        }

        @Test
        void shortcutOverlayOpensWithAltQuestion() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-shortcuts-overlay-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // plain "?" must type into the prompt, not open the overlay
            tuiRef.get().dispatch(KeyEvent.ofChar('?'));
            awaitText("?");

            // the overlay toggle is reserved to Alt+?
            tuiRef.get().dispatch(KeyEvent.ofChar('?', KeyModifiers.ALT));
            awaitText("Up/Down scroll"); // overlay footer: only rendered while the popup is open
            awaitText("send prompt"); // first binding listed in the Input group

            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("Up/Down scroll");

            quit();
        }

        @Test
        void guttersFollowAgentMode() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-mode-gutter-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            // the default permissions state is "ask" (the mode the sidebar shows): the gutter (screen
            // column 0) stays
            // thin
            awaitNoRowStartingWith("┃");
            // the sidebar model line is a plain labeled row; the mode sits in its own row below
            awaitText("model test-model");
            awaitText("permissions ask");

            // the sidebar always shows "mode plan" (its own row), so sync on the
            // mode row before probing the color
            awaitText("mode plan");
            final var col = columnOfLineContaining("mode plan") + 5; // skip the muted "mode " label
            final var row = rowOf("mode plan");
            final var rgb = backend.fg(col, row).map(c -> c.toRgb()).orElse(null);
            assertTrue(
                    rgb != null && rgb.r() == 0xf5 && rgb.g() == 0xa7 && rgb.b() == 0x42,
                    "expected the plan mode value to be painted in the warning (plan) color, fg="
                            + backend.fg(col, row));

            tuiRef.get().dispatch(new PasteEvent("/mode build"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitRowStartingWith("┃"); // heavy bar on the prompt + global gutters
            awaitText("mode build");

            tuiRef.get().dispatch(new PasteEvent("/mode plan"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitNoRowStartingWith("┃");
            awaitText("mode plan");

            quit();
        }

        @ParameterizedTest
        @CsvSource({
            "true,yolo,245,167,66", "false,yolo,255,85,85",
            "true,full_yolo,255,85,85", "false,full_yolo,255,85,85"
        })
        void permissionWarningsFollowSandboxState(
                final boolean enabled, final String mode, final int red, final int green, final int blue)
                throws Exception {
            sandboxEnabled = enabled;
            final var command = command(testRegistry(), stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-permission-warning-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("permissions ask");
            awaitText(enabled ? "sandbox enabled" : "sandbox disabled");
            assertEquals(rowOf("permissions ask") + 1, rowOf(enabled ? "sandbox enabled" : "sandbox disabled"));
            tuiRef.get().dispatch(new PasteEvent("/permissions " + mode));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            final var text = "permissions " + mode + " ⚠";
            awaitText(text);
            final var col = columnOfLineContaining(text) + "permissions ".length();
            final var rgb = backend.fg(col, rowOf(text)).orElseThrow().toRgb();
            assertEquals(red, rgb.r());
            assertEquals(green, rgb.g());
            assertEquals(blue, rgb.b());
            final var sandboxText =
                    "full_yolo".equals(mode) ? "sandbox bypassed" : enabled ? "sandbox enabled" : "sandbox disabled";
            awaitText(sandboxText);
            assertEquals(rowOf(text) + 1, rowOf(sandboxText));
            if ("full_yolo".equals(mode)) {
                awaitText("not recommended");
            }
            tuiRef.get().dispatch(new PasteEvent("/permissions ask"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("permissions ask");
            awaitTextGone("not recommended");
            awaitText(enabled ? "sandbox enabled" : "sandbox disabled");
            awaitTextGone("sandbox bypassed");
            quit();
        }

        @Test
        void fullYoloIsOfferedByPermissionCompletion() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-full-yolo-completion-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("permissions ask");
            for (final var c : "/permissions full_".toCharArray()) {
                tuiRef.get().dispatch(KeyEvent.ofChar(c));
            }
            awaitText("> full_yolo");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB));
            awaitText("/permissions full_yolo");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("permissions full_yolo ⚠");
            awaitText("not recommended");
            quit();
        }

        @Test
        void shiftTabTogglesAgentMode() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-shift-tab-mode-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            awaitNoRowStartingWith("┃"); // plan by default

            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT));
            awaitRowStartingWith("┃"); // build

            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT));
            awaitNoRowStartingWith("┃"); // back to plan

            quit();
        }

        @Test
        void toolOverlayShowsFormattedPreviewForEditFile() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-overlay-preview-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // an edit_file tool call whose transcript card is short: Ctrl+O must
            // surface the structured arguments preview (the synthetic diff)
            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "edit_file",
                    "{\"path\":\"README.md\",\"oldString\":\"old\",\"newString\":\"new\"}",
                    sid(shellRegistry),
                    eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("edit_file", "patched", true, sid(shellRegistry), eventWorkdir));
            awaitText("edit_file"); // condensed card rendered
            awaitTextGone("-old"); // the diff preview only exists inside the overlay

            tuiRef.get().dispatch(KeyEvent.ofChar('o', KeyModifiers.CTRL));
            awaitText("full output");
            awaitText(" - old"); // DiffRenderer renders changed lines as " <tag> <text>"
            awaitText(" + new");

            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("full output");

            quit();
        }

        @Test
        void subagentMarkersRenderInParentAndOverlayListsEmpty() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-subagents-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // spawn/done markers fold into the parent transcript, no inline child stream
            final var sessionId = sid(shellRegistry);
            shellRegistry.onEvent(
                    new AgentEvent.SubagentStarted("do the thing", sessionId, "sub-1", "child", eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ThinkingDelta("child reasoning", sessionId, "sub-1", "child", eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.SubagentCompleted("done", true, sessionId, "sub-1", "child", eventWorkdir));
            awaitText("subagent child started: do the thing");
            awaitTextGone("child reasoning");

            // no subagents registered in this shell registry -> the list is empty
            tuiRef.get().dispatch(new PasteEvent("/subagents"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("no subagents for this session");
            awaitText("Esc close");

            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("no subagents for this session");

            quit();
        }

        @Test
        void modelPickerScrollsBeyondTheVisibleWindow() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-picker-scroll-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // /model without an argument opens the interactive picker on the mock's 30 models
            tuiRef.get().dispatch(new PasteEvent("/model"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("type to filter | Up/Down move | Enter select | Esc cancel");
            awaitText("m01");
            awaitText("▴ 1/30 ▾"); // the list overflows the picker window: the hint shows the viewport

            // moving the selection past the window scrolls it: the top entry leaves the
            // viewport while the focused entry below it becomes visible
            for (int i = 0; i < 23; i++) {
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.DOWN));
            }
            awaitTextGone("m01");
            awaitText("m23");
            awaitText("/30 ▾"); // the scroll hint still tracks the 30-entry list

            // Esc cancels the wizard and closes the picker
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("type to filter | Up/Down move | Enter select | Esc cancel");

            quit();
        }

        @Test
        void modelPickerWheelScrollsTheList() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-picker-wheel-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("/model"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("type to filter | Up/Down move | Enter select | Esc cancel");
            awaitText("m01");
            awaitText("▴ 1/30 ▾");

            // one wheel notch scrolls by yca.tui.scrollLines (3): 8 notches = 24 rows
            for (int i = 0; i < 8; i++) {
                tuiRef.get().dispatch(MouseEvent.scrollDown(10, 10));
            }
            awaitTextGone("m01");
            awaitText("m24");
            awaitText("/30 ▾"); // the scroll hint still tracks the 30-entry list

            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("type to filter | Up/Down move | Enter select | Esc cancel");

            quit();
        }

        @Test
        void wheelScrollsTheToolOutputOverlayNotTheTranscript() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-wheel-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a conversation long enough to overflow the 40-row screen: a wheel leaked to the
            // transcript would be visible (the top row would leave the viewport)
            final var toolOutput =
                    IntStream.range(0, 60).mapToObj(i -> "tool line " + i).collect(Collectors.joining("\n"));
            final var transcript =
                    IntStream.range(0, 60).mapToObj(i -> "transcript line " + i).collect(Collectors.joining("\n"));
            shellRegistry.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolStream("bash", toolOutput, sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolFinished("bash", toolOutput, true, sid(shellRegistry), eventWorkdir));
            awaitTextWithin("✓ bash", TIMEOUT_MS); // condensed tool block

            // the transcript now overflows the screen: TurnDone appends and auto-follows to the
            // bottom, away from the tool block and the first transcript line
            shellRegistry.onEvent(new AgentEvent.TurnDone(transcript, sid(shellRegistry), eventWorkdir));
            awaitTextWithin("transcript line 59", TIMEOUT_MS);

            // prove the transcript really scrolls, otherwise the "does not move" guard below is void:
            // scrolled to the very top, the clickable tool block is visible again
            for (int i = 0; i < 5; i++) {
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_UP));
            }
            awaitText("transcript line 0");
            awaitText("✓ bash"); // the block is above the transcript lines: both at the top
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_DOWN));
            awaitTextGone("transcript line 0");
            for (int i = 0; i < 5; i++) {
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_UP));
            }
            awaitText("transcript line 0");

            // open the tool output overlay: a wheel during a modal must scroll the modal only
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ bash")));
            awaitText("tool line 0");
            awaitText("full output");
            for (int i = 0; i < 5; i++) {
                tuiRef.get().dispatch(MouseEvent.scrollDown(10, 10));
            }
            // the modal scrolled ...
            awaitTextGone("tool line 0");

            // ... and the transcript did not: a leaked wheel would have moved it down by 15 rows,
            // so once the modal is closed the first transcript line must still be on screen
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("full output");
            awaitText("transcript line 0");

            quit();
        }

        @Test
        void toolOverlayLeadsWithTheFullCommand() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-cmd-header-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a long bash invocation: long enough that the ellipsized footer used to hide its tail
            final var longCommand = "mvn install -DskipTests -Dmaven.javadoc.skip=true -Dspotless.check.skip=true"
                    + " -Dunique=ZZZTAIL -pl tui -am";
            final var output = "build finished\n";
            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"" + longCommand + "\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolStream("bash", output, sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", output, true, sid(shellRegistry), eventWorkdir));
            awaitTextWithin("✓ bash", TIMEOUT_MS); // condensed tool block

            // open the full output overlay: the command must lead the body, never ellipsized
            // (it is line-wrapped off the modal edge, so its tail lands on a following body row
            // instead of being truncated)
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ bash")));
            awaitTextWithin("full output", TIMEOUT_MS);
            awaitText("$ mvn install");
            // the command tail is fully visible on a wrapped body row - exactly what the
            // old ellipsized footer dropped
            awaitText("-pl tui -am");
            final var cmdStart = rowOf("$ mvn install");
            final var cmdTail = rowOf("-pl tui -am");
            final var outRow = rowOf("build finished");
            assertTrue(cmdTail > cmdStart, "the wrapped command tail must come after its head");
            assertTrue(outRow > cmdTail, "the output preview must come after the command");

            quit();
        }

        @Test
        void toolOverlayRunningBashLeadsWithTheCommandOnce() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-cmd-once-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // BashTool streams "$ <command>" as its first output line; the modal must not
            // repeat it below the bold command header (which already leads the body with it).
            final var cmd = "mvn install";
            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"" + cmd + "\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "$ " + cmd, sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolStream("bash", "build finished", sid(shellRegistry), eventWorkdir));

            // open the running-tool overlay (Ctrl+O): a live bash call with the echoed command
            tuiRef.get().dispatch(KeyEvent.ofChar('o', KeyModifiers.CTRL));
            awaitTextWithin("bash - running", TIMEOUT_MS);
            awaitText("$ " + cmd); // the bold header leads the body
            awaitText("build finished");

            // the echoed "$ <command>" line BashTool streamed first is dropped, so the
            // command is rendered exactly once (the header), never twice at the top
            assertEquals(
                    1,
                    countOccurrences(backend.text(), "$ " + cmd),
                    () -> "the running bash command must appear exactly once in the modal:\n" + backend.text());

            quit();
        }

        @Test
        void runningBashCardShowsTheCommandOnceNotEchoedFirstInTheBody() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-running-card-echo-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // BashTool streams "$ <command>" as its first output line; the running inline card
            // already carries the command as its header name, so the body must not re-echo it.
            final var cmd = "mvn install";
            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"" + cmd + "\"}", sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "$ " + cmd, sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(
                    new AgentEvent.ToolStream("bash", "build finished", sid(shellRegistry), eventWorkdir));
            awaitText("mvn install"); // the running card (or collapsed one-liner) shows the command

            // show full cards (default is collapsed): the running card body must not repeat the
            // command echo that its header already shows
            tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
            awaitText("build finished"); // real output visible in the shown card body
            // the header still carries the command (no "$ " prefix), but the echoed "$ mvn install"
            // line must be gone from the body - the command is rendered exactly once, in the header
            awaitText("mvn install");
            awaitTextGone("$ " + cmd);

            quit();
        }

        @Test
        void hoverOverToolOverlayFooterRevealsTheFullArgs() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-tool-footer-tooltip-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // the command is short enough that the whole args JSON fits the tooltip's single
            // tooltip line, but long enough that the footer ellipsizes it. the JSON closing
            // ("-pl tui -am\"}") is the tooltip-only marker: the footer ellipsizes to the head,
            // and the modal body renders the extracted command value without the JSON wrapper
            final var longCommand = "mvn install -DskipTests -Dtail=TAILTOKEN123 -pl tui -am";
            final var rawArgs = "{\"command\":\"" + longCommand + "\"}";
            final var output = "ok\n";
            shellRegistry.onEvent(new AgentEvent.ToolStarted("bash", rawArgs, sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolStream("bash", output, sid(shellRegistry), eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", output, true, sid(shellRegistry), eventWorkdir));
            awaitTextWithin("✓ bash", TIMEOUT_MS);

            // open the overlay: the footer (holding the ellipsized args + the hint) is not hovered yet
            tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ bash")));
            awaitTextWithin("Ctrl+O/q/Esc close", TIMEOUT_MS);
            final var footerRow = rowOf("Ctrl+O/q/Esc close");
            // locate the modal's top-left corner (the body title row), so the hover lands just
            // inside the modal footer rather than on the transcript wall sharing the same row
            final var titleRow = rowOf("full output");
            final var titleLine = backend.text().split("\n")[titleRow];
            final var modalLeft = titleLine.indexOf("╭");
            assertTrue(modalLeft >= 0, "the modal title row must show its rounded corner");
            final var footerCol = modalLeft + 2; // just inside the modal footer
            assertFalse(
                    backend.text().contains("-pl tui -am\"}"),
                    "the JSON closing must be hidden until the footer is hovered");

            // hovering the footer surfaces the full args, including the JSON closing the footer dropped
            tuiRef.get().dispatch(MouseEvent.move(footerCol, footerRow));
            awaitTextWithin("-pl tui -am\"}", 2_000);

            // moving the pointer away dismisses the tooltip
            tuiRef.get().dispatch(MouseEvent.move(footerCol, Math.max(0, footerRow - 8)));
            awaitTextGoneWithin("-pl tui -am\"}", 2_000);

            // scrolling the modal also dismisses it
            tuiRef.get().dispatch(MouseEvent.move(footerCol, footerRow));
            awaitTextWithin("-pl tui -am\"}", 2_000);
            tuiRef.get().dispatch(MouseEvent.scrollDown(10, 10));
            awaitTextGoneWithin("-pl tui -am\"}", 2_000);

            quit();
        }

        @Test
        void btwWheelScrollsItsDetailNotTheTranscript() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
            uiThread = new Thread(command, "tui-btw-wheel-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a conversation long enough to overflow the 40-row screen: a wheel leaked to the
            // transcript would be visible (the top row would leave the viewport)
            final var transcript =
                    IntStream.range(0, 60).mapToObj(i -> "transcript line " + i).collect(Collectors.joining("\n"));
            shellRegistry.onEvent(new AgentEvent.TurnDone(transcript, sid(shellRegistry), eventWorkdir));
            awaitTextWithin("transcript line 59", TIMEOUT_MS); // appended, auto-followed to the bottom

            // bring the first transcript line back on screen: it must stay put while wheeling
            for (int i = 0; i < 5; i++) {
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_UP));
            }
            awaitText("transcript line 0");

            // a tall btw answer makes the modal scrollable, with a unique marker on its first line
            llm.answer("FIRSTMARKER\n\n"
                    + IntStream.range(0, 40)
                            .mapToObj(i -> "btw detail line " + i)
                            .collect(Collectors.joining("\n\n")));
            tuiRef.get().dispatch(new PasteEvent("/btw why?"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText(" btw "); // modal title
            awaitText("Q: why?");
            awaitTextWithin("FIRSTMARKER", TIMEOUT_MS);

            // wheeling must scroll the btw modal, never the transcript hidden behind it
            for (int i = 0; i < 4; i++) {
                tuiRef.get().dispatch(MouseEvent.scrollDown(10, 10));
            }
            awaitTextGone("FIRSTMARKER"); // the detail scrolled: its first line left the window

            // the btw modal covered the first transcript rows, so the transcript guard runs after it
            // closes: a leaked wheel would have moved it down by 12 rows
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone(" btw ");
            awaitText("transcript line 0");

            quit();
        }

        @Test
        void escInterruptsTheRunningTurn() throws Exception {
            final var shellRegistry = testRegistry();
            // A blocked tool must be woken by cancellation, without a test-side release.
            final var turnStarted = new CountDownLatch(1);
            final var turnStopped = new CountDownLatch(1);
            final var agentLoop = new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    try {
                        turnStarted.countDown();
                        new CountDownLatch(1).await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (cancelled.getAsBoolean()) {
                        shellRegistry.onEvent(new AgentEvent.TurnCancelled(sessionId, eventWorkdir));
                        turnStopped.countDown();
                    }
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId, history, cancelled);
                }
            };
            final var command = command(shellRegistry, agentLoop);
            uiThread = new Thread(command, "tui-esc-interrupt-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("say hi"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            assertTrue(
                    turnStarted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "the stub turn did not start before the timeout");

            final var sessionId = shellRegistry.sessions().iterator().next();
            shellRegistry
                    .active(sessionId)
                    .frontend()
                    .onEvent(new AgentEvent.Notice(
                            AgentEvent.Notice.Level.ERROR,
                            "passive error must not swallow stop",
                            null,
                            sessionId,
                            null,
                            null,
                            null,
                            eventWorkdir));
            awaitText("passive error must not swallow stop");

            // A passive banner must not consume Esc; the real blocked turn must stop.
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            assertTrue(turnStopped.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "Esc must stop the active turn");
            awaitTextGone("interrupting...");
            awaitText("cancelled");

            quit();
        }

        /**
         * Deterministic teardown: quits the runner directly instead of relying on a
         * Ctrl+C key event, which overlays (e.g. the /subagents detail modal) can
         * swallow before the global quit handler sees it. Also asserts the UI thread
         * actually stopped so a missed quit fails fast instead of silently burning
         * the join timeout.
         */
        @Test
        void cleanQuitDisablesTheAnyMotionMouseMode() throws Exception {
            final var shellRegistry = testRegistry();
            final var command = command(shellRegistry, stubAgentLoop());
            uiThread = new Thread(command, "tui-mouse-restore-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            quit();

            // the clean-exit path must send ESC[?1003l (any-motion mouse tracking) - the mode
            // yca enables itself on top of tamboui's ?1000h/?1002h/?1015h/?1006h capture, which
            // tamboui's own cleanup() disables but ?1003 is not part of. Without it the terminal
            // would keep reporting every mouse move after the TUI exits.
            assertTrue(
                    backend.raw().contains("\u001b[?1003l"), "expected ESC[?1003l on clean exit, raw=" + backend.raw());

            // and the shutdown hook must be gone so a normal quit never races a JVM shutdown
            assertTrue(
                    Thread.getAllStackTraces().keySet().stream()
                            .noneMatch(t -> t.getName().equals("yca-tui-mouse-restore")),
                    "mouse-restore shutdown hook must be removed on clean exit");
        }

        @Test
        void interactionAnswersAcknowledgeBeforeReleasingGates() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-notification-acknowledgements");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            final var tabsField = TuiCommand.class.getDeclaredField("tabManager");
            tabsField.setAccessible(true);
            final var state = ((TabManager) tabsField.get(command)).active();
            try (final var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                final var input = workers.submit(() -> {
                    final var answer = state.session
                            .runtime()
                            .feedback()
                            .ask(List.of(new Question("q", "Choose one", List.of("a", "b"), false)));
                    assertEquals(
                            state.sessionId,
                            interactionAcknowledgements.poll(),
                            "acknowledge before releasing feedback");
                    return answer;
                });
                assertEquals(state.sessionId + ":input:1:main", interactionAlerts.poll(5, TimeUnit.SECONDS));
                awaitText("Choose one");
                assertTrue(interactionAcknowledgements.isEmpty(), "rendering is not an acknowledgement");
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
                assertEquals(Map.of("q", "a"), input.get(5, TimeUnit.SECONDS));

                for (final var key : List.of(KeyCode.ENTER, KeyCode.ESCAPE)) {
                    final var approval = workers.submit(() -> {
                        final var answer = state.session
                                .runtime()
                                .permissions()
                                .gate()
                                .approve(new ApprovalGate.Request(
                                        ApprovalGate.Origin.PERMISSION, "Approve action", "detail", "bash", Map.of()));
                        assertEquals(
                                state.sessionId,
                                interactionAcknowledgements.poll(),
                                "acknowledge before releasing approval");
                        return answer;
                    });
                    assertEquals(state.sessionId + ":approval:bash:main", interactionAlerts.poll(5, TimeUnit.SECONDS));
                    awaitText("Approve action");
                    tuiRef.get().dispatch(KeyEvent.ofKey(key));
                    assertEquals(ApprovalGate.Result.DENY, approval.get(5, TimeUnit.SECONDS));
                }
            }
            assertTrue(interactionAcknowledgements.isEmpty());
            quit();
        }

        @Test
        void queuedSubmissionAcknowledgesWithoutWaitingForTheTurn() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-queued-notification-acknowledgement");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            final var tabsField = TuiCommand.class.getDeclaredField("tabManager");
            tabsField.setAccessible(true);
            final var state = ((TabManager) tabsField.get(command)).active();
            final var ready = new CompletableFuture<Void>();
            tuiRef.get().runLater(() -> {
                state.model.startUserTurn("running", false);
                ready.complete(null);
            });
            ready.get(5, TimeUnit.SECONDS);
            tuiRef.get().dispatch(new PasteEvent("queued follow-up"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            assertEquals(state.sessionId, interactionAcknowledgements.poll(5, TimeUnit.SECONDS));
            awaitText("queued prompt (1 pending)");
            assertTrue(interactionAcknowledgements.isEmpty(), "queued prompt rendering must not acknowledge again");
            quit();
        }

        @Test
        void interactionNotificationsQueueFeedbackAndCancelOnlyBtwOwner() throws Exception {
            final var registry = testRegistry();
            final var command = command(registry, stubAgentLoop());
            uiThread = new Thread(command, "tui-notifications-ownership");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            final var tabsField = TuiCommand.class.getDeclaredField("tabManager");
            tabsField.setAccessible(true);
            final var state = ((TabManager) tabsField.get(command)).active();
            final var ready = new CompletableFuture<Void>();
            tuiRef.get().runLater(() -> {
                state.btw = new UiState.BtwOverlay(42, "side", "", null, true, false, 0, new AtomicBoolean());
                ready.complete(null);
            });
            ready.get(5, TimeUnit.SECONDS);
            final var gateMethod =
                    TuiCommand.class.getDeclaredMethod("feedbackGate", TuiRunner.class, UiState.class, long.class);
            gateMethod.setAccessible(true);
            final var sideGate = (FeedbackGate) gateMethod.invoke(command, tuiRef.get(), state, 42L);
            try (final var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                final var main = workers.submit(() -> state.session
                        .runtime()
                        .feedback()
                        .ask(List.of(new Question("m", "Main question", List.of(), false))));
                assertEquals(state.sessionId + ":input:1:main", interactionAlerts.poll(5, TimeUnit.SECONDS));
                final var side = workers.submit(
                        () -> sideGate.ask(List.of(new Question("b", "Side question", List.of(), false))));
                assertEquals(state.sessionId + ":input:1:btw", interactionAlerts.poll(5, TimeUnit.SECONDS));
                final var approvalMethod =
                        TuiCommand.class.getDeclaredMethod("btwGate", TuiRunner.class, UiState.class, long.class);
                approvalMethod.setAccessible(true);
                final var approvalGate = (ApprovalGate) approvalMethod.invoke(command, tuiRef.get(), state, 42L);
                final var approval = workers.submit(() -> approvalGate.approve(new ApprovalGate.Request(
                        ApprovalGate.Origin.PERMISSION, "Private title", "Private arguments", "bash", Map.of())));
                assertEquals(state.sessionId + ":approval:bash:btw", interactionAlerts.poll(5, TimeUnit.SECONDS));
                awaitText("Main question");
                assertTrue(interactionAlerts.isEmpty(), "rendering must not emit duplicate alerts");
                final var closed = new CompletableFuture<Void>();
                final var close = TuiCommand.class.getDeclaredMethod("closeBtw", UiState.class);
                close.setAccessible(true);
                tuiRef.get().runLater(() -> {
                    try {
                        close.invoke(command, state);
                        closed.complete(null);
                    } catch (final ReflectiveOperationException e) {
                        closed.completeExceptionally(e);
                    }
                });
                closed.get(5, TimeUnit.SECONDS);
                assertNull(side.get(5, TimeUnit.SECONDS));
                assertEquals(ApprovalGate.Result.DENY, approval.get(5, TimeUnit.SECONDS));
                assertFalse(main.isDone(), "closing side question must retain main feedback");
                awaitText("Main question");
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
                assertNull(main.get(5, TimeUnit.SECONDS));
                assertTrue(interactionAlerts.isEmpty());
            }
            quit();
        }

        @Test
        void backgroundInteractionAlertsUseRequesterAndCancelledPublicationIsSilent() throws Exception {
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-notifications-background");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            final var requester = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
            requester.sessionId = "background-session";
            requester.title = "Background";
            final var method = TuiCommand.class.getDeclaredMethod("feedbackGate", TuiRunner.class, UiState.class);
            method.setAccessible(true);
            final var gate = (FeedbackGate) method.invoke(command, tuiRef.get(), requester);
            try (final var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                final var input =
                        workers.submit(() -> gate.ask(List.of(new Question("q", "Hidden", List.of(), false))));
                assertEquals("background-session:input:1:main", interactionAlerts.poll(5, TimeUnit.SECONDS));
                requester.interrupted = true;
                assertNull(input.get(5, TimeUnit.SECONDS));
                assertNull(gate.ask(List.of(new Question("stale", "Stale", List.of(), false))));
                assertTrue(interactionAlerts.isEmpty());
            }
            quit();
        }

        /**
         * A session-aware log record for a background tab must land in that tab's content, not in the
         * active one; an untagged record keeps the historical active-tab fallback. Regression for the
         * multi-session TUI: JUL records are JVM-wide and carry no session id, so without the
         * attribution a background tab's stream failure / compaction warning was rendered into whatever
         * tab was focused.
         */
        @Test
        void sessionAttributedLogLandsInItsOwnTabNotTheActiveOne() throws Exception {
            final var ids = new AtomicLong();
            final var shellRegistry = testRegistry();
            final var command = command(
                    shellRegistry,
                    stubAgentLoop(),
                    false,
                    new UsageTracker(null, null, null, Clock.systemUTC()),
                    new TodoStore(tuiConfig, formatMapper),
                    Clock.systemUTC(),
                    new SubagentRegistry(tuiConfig, new ProviderManager(tuiConfig, formatMapper)),
                    Executors.newVirtualThreadPerTaskExecutor(),
                    new SessionStore() {
                        @Override
                        public String create() {
                            return "tab-" + ids.incrementAndGet();
                        }

                        @Override
                        public void append(final String id, final SessionEntry entry) {
                            sessionAppends.add(String.valueOf(entry));
                        }

                        @Override
                        public List<Message> load(final String id) {
                            return List.of();
                        }

                        @Override
                        public void delete(final String id) {
                            // no-op
                        }
                    });
            uiThread = new Thread(command, "tui-session-log-routing-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // tab 1 = "tab-1" (created on boot), tab 2 = "tab-2" (Ctrl+N); focus switches to tab 2
            tuiRef.get().dispatch(KeyEvent.ofChar('n', KeyModifiers.CTRL));
            awaitText(Glyphs.of("unicode").cross() + " "); // the tab bar shows both tabs

            // a WARNING attributed to tab 1 (the background tab) while tab 2 is active
            final var logger = Logger.getLogger("io.yupiik.ia.agent.core.llm.ChatClient");
            logger.log(SessionAwareLogRecord.of(logger, "tab-1", Level.WARNING, "background stream failed for tab-1"));
            // it must NOT appear on the active tab (tab 2)
            Thread.sleep(300); // give the runLater + render a chance to paint the wrong way
            assertFalse(
                    backend.text().contains("background stream failed for tab-1"),
                    "a background tab's log must not render on the active tab:\n" + backend.text());

            // switch back to tab 1 (Ctrl+Tab cycles to the previous tab): the log must be there
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.CTRL));
            awaitText("background stream failed for tab-1");
            awaitText("WARNING"); // the log region header carries the level (appendLogRegion)

            // an untagged record keeps the historical active-tab fallback: emit it while tab 1 is active
            logger.log(Level.INFO, "global infra log");
            awaitText("global infra log");

            quit();
        }

        @Test
        void autoDiffDefaultAppliesToStartupAndNewTabs() throws Exception {
            assertDiffDefaultForStartupAndNewTab(DiffViewMode.AUTO);
        }

        @Test
        void tuiConfDefaultsAutoOpenBrowserToTrue() {
            final var conf = new TuiCommand.TuiConf("", "", KeyBindingPreset.STANDARD, false, "dark");
            assertTrue(conf.autoOpenBrowser(), "tui.autoOpenBrowser must default to true");
        }
    }

    private void assertDiffDefaultForStartupAndNewTab(final DiffViewMode expected) throws Exception {
        final var command = command(testRegistry(), stubAgentLoop());
        uiThread = new Thread(command, "tui-diff-default-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");
        final var tabsField = TuiCommand.class.getDeclaredField("tabManager");
        tabsField.setAccessible(true);
        final var tabs = (TabManager) tabsField.get(command);
        final var first = tabs.active();
        assertEquals(expected, first.diffViewMode);
        assertEquals(expected, first.session.diffViewMode());

        // A tab-local override must not become the default of subsequently opened tabs.
        final var override = expected == DiffViewMode.SIMPLE ? DiffViewMode.SIDE_BY_SIDE : DiffViewMode.SIMPLE;
        final var changed = new CompletableFuture<Void>();
        tuiRef.get().runLater(() -> {
            first.diffViewMode = override;
            first.session.diffViewMode(override);
            changed.complete(null);
        });
        changed.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        tuiRef.get().dispatch(KeyEvent.ofChar('n', KeyModifiers.CTRL));
        // The close marker is already visible with one tab; wait for Ctrl+N's state transition.
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (tabs.size() != 2 || tabs.active() == first) {
            assertTrue(
                    System.nanoTime() < deadline,
                    () -> "second tab did not become active; tab count: " + tabs.size() + "; last screen:\n"
                            + backend.text());
            awaitTextFrame();
        }
        assertEquals(2, tabs.size());
        final var second = tabs.active();
        Assertions.assertNotSame(first, second);
        assertEquals(expected, second.diffViewMode);
        assertEquals(expected, second.session.diffViewMode());
        assertEquals(override, first.diffViewMode);
        assertEquals(override, first.session.diffViewMode());
        quit();
    }

    @Nested
    @FusionSupport(modules = ConfiguredDiffModule.class)
    class ConfiguredDiff {
        @TempDir
        private static Path storage;

        private static MockLlmServer primary;

        @BeforeEach
        void setup(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            assertEquals(DiffViewMode.SIMPLE, configuration.view().diffViewMode());
            start(mapper, configuration, primary, null);
        }

        @AfterAll
        static void closeServers() {
            if (primary != null) {
                primary.close();
            }
        }

        @Test
        void configuredDiffDefaultAppliesToStartupAndNewTabs() throws Exception {
            assertDiffDefaultForStartupAndNewTab(DiffViewMode.SIMPLE);
        }
    }

    public static class ConfiguredDiffModule extends TuiConfigurationTestModule {
        public ConfiguredDiffModule() throws IOException {
            super(properties());
        }

        private static Map<String, String> properties() throws IOException {
            assertNotNull(ConfiguredDiff.storage, "JUnit must initialize the scenario directory before Fusion");
            ConfiguredDiff.primary = new MockLlmServer();
            return Map.of(
                    "yca.storageHome", ConfiguredDiff.storage.toString(),
                    "yca.provider.providers.0.baseUrl", ConfiguredDiff.primary.baseUrl(),
                    "yca.view.diffViewMode", "SIMPLE");
        }
    }

    public static class DefaultProviderModule extends TuiConfigurationTestModule {
        public DefaultProviderModule() throws IOException {
            super(properties());
        }

        private static Map<String, String> properties() throws IOException {
            assertNotNull(DefaultProvider.storage, "JUnit must initialize the scenario directory before Fusion");
            DefaultProvider.primary = new MockLlmServer();
            return Map.of(
                    "yca.storageHome", DefaultProvider.storage.toString(),
                    "yca.provider.providers.0.baseUrl", DefaultProvider.primary.baseUrl());
        }
    }

    @Nested
    @FusionSupport(modules = SubagentsModule.class)
    class Subagents {
        @TempDir
        private static Path storage;

        private static MockLlmServer primary;

        @BeforeEach
        void setup(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            assertEquals(storage.toString(), configuration.storageHome());
            assertTrue(configuration.subagents().enabled());
            assertEquals(4, configuration.subagents().maxConcurrent());
            assertEquals(4, configuration.llm().maxConcurrent());
            start(mapper, configuration, primary, null);
        }

        @AfterAll
        static void closeServers() {
            if (primary != null) {
                primary.close();
            }
        }

        @Test
        void subagentDetailModalRendersRegionsAndHonorsThinkingToggle() throws Exception {
            final var shellRegistry = testRegistry();
            final var mapper = formatMapper;
            final var configuration = tuiConfig;
            final var subagentRegistry =
                    new SubagentRegistry(configuration, new ProviderManager(configuration, mapper));
            final var command = command(
                    shellRegistry,
                    stubAgentLoop(),
                    true,
                    newUsageTracker(),
                    new TodoStore(configuration, mapper),
                    Clock.systemUTC(),
                    subagentRegistry);
            uiThread = new Thread(command, "tui-subagents-detail-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a real tracked subagent with a multiword name and captured stream
            final var sessionId = sid(shellRegistry);
            final var sub = subagentRegistry
                    .start(sessionId, "do the thing", "code reviewer")
                    .orElseThrow();
            shellRegistry.onEvent(
                    new AgentEvent.ThinkingDelta("kid thinks", sessionId, sub.id(), "child", eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.TextDelta("kid answer", sessionId, sub.id(), "child", eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolStarted(
                    "grep", "{\"q\":\"x\"}", -1L, sessionId, sub.id(), "child", eventWorkdir));
            shellRegistry.onEvent(new AgentEvent.ToolFinished(
                    "grep", "match line", true, sessionId, sub.id(), "child", eventWorkdir));

            // the detail view renders the captured regions - never the raw yca markers
            tuiRef.get().dispatch(new PasteEvent("/subagents code rev"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("kid thinks");
            awaitText("kid answer");
            awaitText("match line");
            awaitNoText("<<<yca:");

            // Ctrl+T hides the reasoning in the modal too; the overlay consumes every
            // key while open, so the toggle happens with the modal closed and reopened
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            tuiRef.get().dispatch(KeyEvent.ofChar('t', KeyModifiers.CTRL));
            tuiRef.get().dispatch(new PasteEvent("/subagents " + sub.id()));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitNoText("kid thinks");
            awaitText("kid answer");

            // ... and it comes back once thinking is shown again
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            tuiRef.get().dispatch(KeyEvent.ofChar('t', KeyModifiers.CTRL));
            tuiRef.get().dispatch(new PasteEvent("/subagents " + sub.id()));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("kid thinks");

            quit();
        }

        @Test
        void subagentCompletedDetailRendersReportCard() throws Exception {
            final var shellRegistry = testRegistry();
            final var mapper = formatMapper;
            final var configuration = tuiConfig;
            final var subagentRegistry =
                    new SubagentRegistry(configuration, new ProviderManager(configuration, mapper));
            final var command = command(
                    shellRegistry,
                    stubAgentLoop(),
                    true,
                    newUsageTracker(),
                    new TodoStore(configuration, mapper),
                    Clock.systemUTC(),
                    subagentRegistry);
            uiThread = new Thread(command, "tui-subagents-completed-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // a completed tracked subagent with an effective identity, artifacts and a fence summary
            final var sessionId = sid(shellRegistry);
            final var sub =
                    subagentRegistry.start(sessionId, "do the thing", "child").orElseThrow();
            subagentRegistry.setTranscriptIdentity(sub.id(), "provider-x", "gpt-5.5");
            final var summary = "## Findings\n- item one\n\n```java\nvar x = 1;\n```\n";
            subagentRegistry.complete(
                    sub.id(), SubagentRegistry.SubagentResult.success(summary, "full conv", List.of("a.java")));
            shellRegistry.onEvent(new AgentEvent.SubagentCompleted(
                    summary, true, false, sessionId, sub.id(), "child", "gpt-5.5", eventWorkdir));

            // the detail view renders the report card above the live capture
            tuiRef.get().dispatch(new PasteEvent("/subagents " + sub.id()));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("model: gpt-5.5");
            awaitText("status: succeeded");
            awaitText("artifacts: a.java");
            awaitText("Findings");
            awaitText("item one");
            awaitText("var x = 1;");
            awaitText("full transcript");

            quit();
        }
    }

    public static class SubagentsModule extends TuiConfigurationTestModule {
        public SubagentsModule() throws IOException {
            super(properties());
        }

        private static Map<String, String> properties() throws IOException {
            assertNotNull(Subagents.storage, "JUnit must initialize the scenario directory before Fusion");
            Subagents.primary = new MockLlmServer();
            return Map.of(
                    "yca.storageHome", Subagents.storage.toString(),
                    "yca.provider.providers.0.baseUrl", Subagents.primary.baseUrl(),
                    "yca.llm.maxConcurrent", "4",
                    "yca.subagents.enabled", "true",
                    "yca.subagents.maxConcurrent", "4");
        }
    }

    @Nested
    @FusionSupport(modules = TwoProvidersModule.class)
    class TwoProviders {
        @TempDir
        private static Path storage;

        private static MockLlmServer primary;
        private static MockLlmServer secondary;

        @BeforeEach
        void setup(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
            assertEquals(storage.toString(), configuration.storageHome());
            assertEquals(
                    List.of("prov0", "prov1"),
                    configuration.provider().providers().stream()
                            .map(provider -> provider.id())
                            .toList());
            assertEquals(
                    secondary.baseUrl(),
                    configuration.provider().providers().get(1).baseUrl());
            start(mapper, configuration, primary, secondary);
        }

        @AfterAll
        static void closeServers() {
            if (primary != null) {
                primary.close();
            }
            if (secondary != null) {
                secondary.close();
            }
        }

        @Test
        void providerChangeRefreshesCompletionTypedBeforeTheCommandSettles() throws Exception {
            final var executor = new HoldingExecutor();
            final var command = command(
                    testRegistry(),
                    stubAgentLoop(),
                    false,
                    newUsageTracker(),
                    new TodoStore(tuiConfig, formatMapper),
                    Clock.systemUTC(),
                    new SubagentRegistry(tuiConfig, new ProviderManager(tuiConfig, formatMapper)),
                    executor);
            uiThread = new Thread(command, "tui-provider-change-pending-completion-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("/model "));
            tuiRef.get().dispatch(KeyEvent.ofChar('m'));
            awaitText("> m01"); // warm prov0's model cache
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            tuiRef.get().dispatch(KeyEvent.ofChar('u', KeyModifiers.CTRL));

            executor.hold();
            try {
                tuiRef.get().dispatch(new PasteEvent("/provider prov1"));
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
                awaitText("/provider prov1"); // echoed before the command's background action runs
                final var providerChange = executor.takeNextHeld();

                tuiRef.get().dispatch(new PasteEvent("/model "));
                tuiRef.get().dispatch(KeyEvent.ofChar('b'));
                awaitText("/model b");
                // Compute the next input against prov0, where no model matches "b".
                // The actual provider command is kept out of this queue until after that lookup.
                executor.releaseHeld();
                providerChange.run();
                awaitText("> b01"); // no further key event should be needed to refresh for prov1
            } finally {
                executor.releaseHeld();
            }
            quit();
        }

        @Test
        void tabSwitchLoadsModelsForTheActiveSessionProvider() throws Exception {
            // Regression: model suggestions follow the active session's provider, even after a tab
            // with a different provider has populated its own model list.
            sessionAgentLoopFactory = sessionId -> new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    return "ok";
                }
            };
            final var ids = new AtomicLong();
            final var command = command(
                    testRegistry(),
                    stubAgentLoop(),
                    false,
                    newUsageTracker(),
                    new TodoStore(tuiConfig, formatMapper),
                    Clock.systemUTC(),
                    new SubagentRegistry(tuiConfig, new ProviderManager(tuiConfig, formatMapper)),
                    Executors.newVirtualThreadPerTaskExecutor(),
                    new SessionStore() {
                        @Override
                        public String create() {
                            return "tab-" + ids.incrementAndGet();
                        }

                        @Override
                        public void append(final String id, final SessionEntry entry) {
                            sessionAppends.add(String.valueOf(entry));
                        }

                        @Override
                        public List<Message> load(final String id) {
                            return List.of();
                        }

                        @Override
                        public void delete(final String id) {
                            // no-op
                        }
                    });
            uiThread = new Thread(command, "tui-tab-provider-completion-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // tab 1 (active, tab-1) runs on prov0 (the default): /model completes with prov0's models
            // (m01..)
            for (final var c : "/model m".toCharArray()) {
                tuiRef.get().dispatch(KeyEvent.ofChar(c));
            }
            awaitText("> m01"); // prov0's list
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));

            // Create tab two, switch it to prov1, and prove its /model completion is isolated.
            tuiRef.get().dispatch(KeyEvent.ofChar('n', KeyModifiers.CTRL));
            awaitTextWithin("session tab-2", 2_000);
            tuiRef.get().dispatch(new PasteEvent("/provider prov1"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE)); // dismiss live completion before submitting
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitTextWithin("provider prov1", 2_000);
            tuiRef.get().dispatch(KeyEvent.ofChar('u', KeyModifiers.CTRL));
            for (final var c : "/model b".toCharArray()) {
                tuiRef.get().dispatch(KeyEvent.ofChar(c));
            }
            awaitTextWithin("> b01", 2_000);
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));

            // Return to tab one. Model candidates must follow the active session's original provider.
            tuiRef.get().dispatch(KeyEvent.ofChar('1', KeyModifiers.ALT));
            awaitTextWithin("session tab-1", 2_000);
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            tuiRef.get().dispatch(KeyEvent.ofChar('u', KeyModifiers.CTRL));
            for (final var c : "/model m".toCharArray()) {
                tuiRef.get().dispatch(KeyEvent.ofChar(c));
            }
            awaitTextWithin("> m01", 2_000);
            awaitNoText("> b01");

            quit();
        }

        @Test
        void btwUsesTheTabChatSettingsModelAndProvider() throws Exception {
            // Regression: /btw must run on the active tab's per-session model/provider selection,
            // not on the process default (prov0) provider. The tab is seeded with prov1/b03 so
            // prov0 (llm) and prov1 (llm2) disagree on both the model and the answering server.
            llm.answer("default-provider answer");
            llm2.answer("tab-provider answer");
            final var settings = new AtomicReference<ChatSettings>(ChatSettings.EMPTY);
            sessionAgentLoopFactory = sessionId -> new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId2,
                        final List<Message> history,
                        final BooleanSupplier cancelled) {
                    return "ok";
                }

                @Override
                public String run(
                        final String prompt,
                        final String agentName,
                        final String sessionId2,
                        final List<Message> history,
                        final BooleanSupplier cancelled,
                        final Supplier<String> queuedPrompt) {
                    return run(prompt, agentName, sessionId2, history, cancelled);
                }

                @Override
                public ChatSettings chatSettings() {
                    return settings.get();
                }

                @Override
                public void updateChatSettings(final ChatSettings value) {
                    settings.set(value);
                }
            };
            final var command = command(testRegistry(), stubAgentLoop());
            uiThread = new Thread(command, "tui-btw-tab-settings-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // seed the active tab's per-session selection: prov1 with model b03 (like /provider + /model)
            settings.set(ChatSettings.EMPTY.withProviderId("prov1").withModel("b03"));

            tuiRef.get().dispatch(new PasteEvent("/btw what is this"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("Q: what is this");
            awaitText("tab-provider answer");

            final var sentToTabProvider = llm2.requests().stream()
                    .filter(it -> it.contains("what is this"))
                    .toList();
            assertFalse(sentToTabProvider.isEmpty(), "/btw must reach the tab's provider (prov1/llm2)");
            assertTrue(
                    sentToTabProvider.stream().anyMatch(it -> it.contains("\"model\":\"b03\"")),
                    "/btw must send the tab's model (b03), got: " + sentToTabProvider);

            quit();
        }
    }

    public static class TwoProvidersModule extends TuiConfigurationTestModule {
        public TwoProvidersModule() throws IOException {
            super(properties());
        }

        private static Map<String, String> properties() throws IOException {
            assertNotNull(TwoProviders.storage, "JUnit must initialize the scenario directory before Fusion");
            TwoProviders.primary = new MockLlmServer();
            TwoProviders.secondary = new MockLlmServer(List.of("b01", "b02", "b03", "b04", "b05"));
            return Map.of(
                    "yca.storageHome", TwoProviders.storage.toString(),
                    "yca.provider.providers.0.baseUrl", TwoProviders.primary.baseUrl(),
                    "yca.provider.providers.length", "2",
                    "yca.provider.providers.0.id", "prov0",
                    "yca.provider.providers.1.id", "prov1",
                    "yca.provider.providers.1.baseUrl", TwoProviders.secondary.baseUrl(),
                    "yca.provider.providers.1.apiKey", "test-key",
                    "yca.provider.providers.1.model", "test-model");
        }
    }
}
