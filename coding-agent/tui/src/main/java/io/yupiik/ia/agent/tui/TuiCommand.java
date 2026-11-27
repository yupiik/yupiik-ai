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

import static java.util.stream.Collectors.joining;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Padding;
import dev.tamboui.layout.Rect;
import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widget.Widget;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderSet;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.checkbox.CheckboxState;
import dev.tamboui.widgets.gauge.Gauge;
import dev.tamboui.widgets.input.TextArea;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.spinner.Spinner;
import dev.tamboui.widgets.spinner.SpinnerFrameSet;
import dev.tamboui.widgets.spinner.SpinnerState;
import dev.tamboui.widgets.spinner.SpinnerStyle;
import dev.tamboui.widgets.tabs.Tabs;
import dev.tamboui.widgets.tabs.TabsState;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.cli.ToolPresenter;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.BusyLabels;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.DiffLayout;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import io.yupiik.ia.agent.core.shell.DurationFormat;
import io.yupiik.ia.agent.core.shell.InterruptCoordinator;
import io.yupiik.ia.agent.core.shell.MetricsFormat;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellFrontend;
import io.yupiik.ia.agent.core.shell.ShellIO;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.CompletionEngine;
import io.yupiik.ia.agent.core.shell.slash.ShellInputRouting;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.style.ModelStyleService;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.FileMutation;
import io.yupiik.ia.agent.core.util.OpenBrowser;
import io.yupiik.ia.agent.core.wiring.AutoLogConfigurer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.core.wiring.SessionAwareLogRecord;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;

@DefaultScoped
@Command(
        name = "tui",
        description =
                "Interactive terminal UI session with the yca coding agent. All modes accept --sandbox true|false (yca.sandbox.enabled); sandbox policy is independent of tool permissions.")
public class TuiCommand implements Runnable {
    private static final long DEFAULT_MAX_HISTORY_CHARACTERS = 10_000_000L;
    private static final String WELCOME = "# yca\n\nType a prompt and press Enter. `/help` lists the slash commands.";
    /**
     * The current directory the process was launched from, read once so the sidebar, path shortening and git
     * lookups stay consistent. {@code user.dir} is the JVM bootstrap property holding the launcher's working
     * directory (the OS cwd captured at exec, projected through the same filesystem root as
     * {@code Path.of("").toAbsolutePath()}), so it is the contextual "where I ran yca" directory the TUI
     * should surface. Read once to keep all three consumers on the same value.
     */
    private static final String WORKING_DIRECTORY = System.getProperty("user.dir");

    /**
     * Constant spinner placeholder handed to {@code ConversationTranscript.markdown()}
     * for the memoized chunks. The running-tool header only needs a non-empty marker:
     * {@link ToolCard} detects a live tool from the {@code " running "} token, it never
     * displays this character (the card draws {@code glyphs.running()} instead). Passing
     * a fixed marker keeps the {@code ContentKey} stable across ticks while a tool runs,
     * so a busy frame with unchanged content reuses the previous chunk render instead of
     * re-parsing the whole transcript because the spinning character changed. The real,
     * animated spinners (thinking..., calling, status strip) live outside this memo.
     */
    private static final String RUNNING_TOOL_MARKER = "|";

    private static final Logger LOG = Logger.getLogger(TuiCommand.class.getName());

    private final TuiConf conf;
    private final TuiConfiguration tuiConfiguration;
    private final CodingAgentConfiguration configuration;
    private final SessionRuntimeFactory sessionRuntimeFactory;
    private final ShellRegistry shellRegistry;
    private final SessionStore sessionStore;
    private final SessionLifecycle sessionLifecycle;
    private final ChatClient chatClient;
    private final ModelStyleService modelStyleService;
    private final SpeakerLabels speakerLabels;
    private final AutoLogConfigurer autoLogConfigurer;
    private final McpManager mcpManager;
    private final ExecutorService executor;
    private final ProviderManager providerManager;
    private final AgentProvider agentProvider;
    private final AgentSessionService agentSessionService;
    private final SubagentRegistry subagentRegistry;
    private final ScrollService scrollService;
    private final TurnRunner turnRunner;
    private final ThinkTagSplitter thinkTags;
    private final TuiRunnerFactory tuiRunnerFactory;
    /** Safety repaint: max idle time (ms) without a full redraw when nothing changed (0 disables it). */
    private final long heartbeatMillis;
    /** Open tabs, one per concurrent session; index 0 is the original tab. */
    private final TabManager tabManager;
    /** Header hit-areas of the rendered tab bar, one per tab (empty while the bar is hidden). */
    private final List<Rect> tabRects = new CopyOnWriteArrayList<>();

    private final List<Rect> tabCloseRects = new CopyOnWriteArrayList<>();
    private volatile Rect tabAddRect;
    /**
     * Content regions registered by the current frame's renderers, bottom to top.
     * Render-thread only, published to {@link UiState#paneZones} at the end of the frame.
     */
    private final List<Rect> frameZones = new ArrayList<>();

    private volatile Style thoughtStyle;
    private volatile Style thoughtCodeStyle;
    private volatile Style thoughtBarStyle;
    private volatile Color thoughtBg;
    private volatile Color toolBg;
    private volatile Style toolStyle;
    private volatile Style logStyle;
    /** Optional full-viewport background fill (blank config keeps the terminal background). */
    private volatile Color screenBackground;

    private final TranscriptRenderer transcriptRenderer = new TranscriptRenderer();
    private volatile Palette palette;
    private volatile MarkdownStyles markdownStyles;
    /** Role-aware markdown for turn bodies so each speaker's bold prose keeps its own color. */
    private volatile MarkdownStyles userMarkdownStyles;

    private volatile MarkdownStyles assistantMarkdownStyles;
    private volatile String theme = "dark";
    private final CompletionEngine completionEngine;
    private final PromptHistory promptHistory;
    private volatile long completionGeneration;
    private final CompletionCache completionCache = new CompletionCache(this::recomputePendingCompletion);
    private volatile String cachedGitBranch;
    private volatile long cachedGitBranchAt;
    private final TuiSlashCommands slashCommands;
    private final SlashRegistry slashRegistry;
    private final Clock clock;
    /** Polls the git branch off the render thread; {@code null} in render-only tests (on-demand fallback). */
    private final GitBranchPoller gitBranchPoller;

    private final SyntaxHighlighter syntaxHighlighter;
    private final Glyphs glyphs;
    // single-entry render memo: when neither the transcript markdown nor the
    // mutable render inputs changed, the previous frame's chunks are reused
    // verbatim (only the spinner/animation parts are re-rendered each frame)
    private final ReentrantLock contentLock = new ReentrantLock();
    private volatile ContentKey lastContentKey;
    private volatile ContentResult lastContent;
    private volatile long layoutGeneration;
    // bounded per-region render-unit cache: a stable region (thought, log or plain prose) is
    // rendered once per (kind, text, width, theme) key and its chunks/rows are reused across
    // frames; only the growing tail region re-renders each tick. Render-thread confined (like
    // the live-thinking memo), so no lock; bounded both by entry count and by an approximate
    // retained weight (see regionUnit), and oversized regions bypass the cache entirely.
    private static final int MAX_REGION_UNIT_CACHE_ENTRIES = 256;
    private static final long MAX_REGION_UNIT_CACHE_WEIGHT = 8L * 1024 * 1024;
    private static final int MAX_REGION_UNIT_SOURCE_CHARS = 512 * 1024;
    private final Map<RegionUnitKey, ContentPart> regionUnitCache = new LinkedHashMap<>(16, 0.75f, true);
    private long regionUnitCacheWeight;
    // A replay can be one giant prose region that bypasses both outer caches. Cache complete
    // speaker turns (including their open suffix and header) at the existing rendering boundary.
    // Admission never evicts a live entry: an over-budget sequential history must not thrash
    // the entire cache on every frame. Unvisited entries are released at the end of the frame.
    private static final int MAX_TURN_UNIT_CACHE_ENTRIES = 4096;
    private static final long MAX_TURN_UNIT_CACHE_WEIGHT = 8L * 1024 * 1024;
    private final Map<TurnUnitKey, CachedTurnUnit> turnUnitCache = new LinkedHashMap<>();
    private long turnUnitCacheWeight;
    private long turnUnitFrame;
    private final ToolBodyCache toolBodyCache = new ToolBodyCache();
    private TuiTranscriptModel renderOwner;
    private String renderSessionId;
    private ToolOverlayLayout toolOverlayLayout;

    private record ToolOverlayLayout(
            ToolOutputStore.Entry entry,
            int width,
            boolean raw,
            DiffViewMode mode,
            List<FileMutation> files,
            Text text) {}
    // incremental live-thinking memo: the flushed part of the growing stream is
    // rendered once per stable prefix and the last (still open) line re-rendered
    private String lastLiveThoughtPrefix = "";
    private int lastLiveThoughtWidth = -1;
    private Style lastLiveThoughtStyle;
    private List<Line> lastLiveThoughtLines;
    private final CostEstimator costEstimator;
    private final boolean persistHistory;
    private final AskUserCardRenderer askUserCardRenderer;
    private final SecretRedactor secretRedactor;
    private final DiffRenderer diffRenderer = new DiffRenderer();
    private final ToolArgsPreview toolArgsPreview;
    private final ToolOutputFormat toolOutputFormat;
    private final BtwAnswerer btwAnswerer;
    private final NotificationService notifications;
    private final boolean formatToolOutput;
    private volatile CodeTheme codeTheme = CodeTheme.ONE_DARK;

    private static final int MAX_RESUME_LIST = 25;
    private static final int RESUME_SUMMARY_WIDTH = 80;
    /** Inline preview lines kept in a tool card; the rest lives behind the expand link / Ctrl+O. */
    private static final int MAX_INLINE_TOOL_LINES = 3;
    /** TTL for the git branch filesystem lookup so the render thread does not stat/log each frame. */
    private static final long GIT_BRANCH_CACHE_TTL_MILLIS = 3_000;
    /** If the render thread has not ticked for this long while an approval/feedback gate waits, fail closed. */
    private static final long RENDER_STALL_TIMEOUT_MILLIS = 5_000;

    /**
     * Fallback safety-repaint interval when no TUI configuration resolves a value (rendering-only tests).
     * Mirrors {@code yca.tui.heartbeatMillis}; see {@link TuiConfiguration#heartbeatMillis()}.
     */
    private static final long DEFAULT_HEARTBEAT_RENDER_INTERVAL_MILLIS = 3_500;

    private static final Pattern SPLIT_COMMAND = Pattern.compile("\\s+");

    public TuiCommand(
            final TuiConf conf,
            final TuiConfiguration tuiConfiguration,
            final CodingAgentConfiguration configuration,
            final ShellRegistry shellRegistry,
            final SessionRuntimeFactory sessionRuntimeFactory,
            final SessionStore sessionStore,
            final SessionLifecycle sessionLifecycle,
            final ChatClient chatClient,
            final ModelStyleService modelStyleService,
            final SpeakerLabels speakerLabels,
            final AutoLogConfigurer autoLogConfigurer,
            final McpManager mcpManager,
            final ExecutorService executor,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final AgentSessionService agentSessionService,
            final TurnRunner turnRunner,
            final ThinkTagSplitter thinkTags,
            final TuiRunnerFactory tuiRunnerFactory,
            final TuiSlashCommands slashCommands,
            final SlashRegistry slashRegistry,
            final Clock clock,
            final AskUserCardRenderer askUserCardRenderer,
            final ToolOutputFormat toolOutputFormat,
            final BtwAnswerer btwAnswerer,
            final TabManager tabManager,
            final SubagentRegistry subagentRegistry,
            final ScrollService scrollService,
            final ScheduledExecutorService scheduler,
            final JsonMapper mapper,
            final SecretRedactor secretRedactor) {
        this(
                conf,
                tuiConfiguration,
                configuration,
                shellRegistry,
                sessionRuntimeFactory,
                sessionStore,
                sessionLifecycle,
                chatClient,
                modelStyleService,
                speakerLabels,
                autoLogConfigurer,
                mcpManager,
                executor,
                providerManager,
                agentProvider,
                agentSessionService,
                turnRunner,
                thinkTags,
                tuiRunnerFactory,
                slashCommands,
                slashRegistry,
                clock,
                askUserCardRenderer,
                toolOutputFormat,
                btwAnswerer,
                tabManager,
                subagentRegistry,
                scrollService,
                scheduler,
                mapper,
                null,
                secretRedactor);
    }

    public TuiCommand(
            final TuiConf conf,
            final TuiConfiguration tuiConfiguration,
            final CodingAgentConfiguration configuration,
            final ShellRegistry shellRegistry,
            final SessionRuntimeFactory sessionRuntimeFactory,
            final SessionStore sessionStore,
            final SessionLifecycle sessionLifecycle,
            final ChatClient chatClient,
            final ModelStyleService modelStyleService,
            final SpeakerLabels speakerLabels,
            final AutoLogConfigurer autoLogConfigurer,
            final McpManager mcpManager,
            final ExecutorService executor,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final AgentSessionService agentSessionService,
            final TurnRunner turnRunner,
            final ThinkTagSplitter thinkTags,
            final TuiRunnerFactory tuiRunnerFactory,
            final TuiSlashCommands slashCommands,
            final SlashRegistry slashRegistry,
            final Clock clock,
            final AskUserCardRenderer askUserCardRenderer,
            final ToolOutputFormat toolOutputFormat,
            final BtwAnswerer btwAnswerer,
            final TabManager tabManager,
            final SubagentRegistry subagentRegistry,
            final ScrollService scrollService,
            final ScheduledExecutorService scheduler,
            final JsonMapper mapper,
            final NotificationService notifications,
            final SecretRedactor secretRedactor) {
        this.secretRedactor = secretRedactor;
        this.notifications = notifications;
        this.conf = conf;
        this.tuiConfiguration = tuiConfiguration;
        this.configuration = configuration;
        this.shellRegistry = shellRegistry;
        this.sessionRuntimeFactory = sessionRuntimeFactory;
        this.sessionStore = sessionStore;
        this.sessionLifecycle = sessionLifecycle;
        this.chatClient = chatClient;
        this.modelStyleService = modelStyleService;
        this.speakerLabels = speakerLabels;
        this.autoLogConfigurer = autoLogConfigurer;
        this.mcpManager = mcpManager;
        this.executor = executor;
        this.providerManager = providerManager;
        this.agentProvider = agentProvider;
        this.agentSessionService = agentSessionService;
        this.turnRunner = turnRunner;
        this.thinkTags = thinkTags;
        this.tuiRunnerFactory = tuiRunnerFactory;
        this.heartbeatMillis = tuiConfiguration == null
                ? DEFAULT_HEARTBEAT_RENDER_INTERVAL_MILLIS
                : tuiConfiguration.heartbeatMillis();
        this.slashCommands = slashCommands;
        this.slashRegistry = slashRegistry;
        this.gitBranchPoller = scheduler == null ? null : new GitBranchPoller(scheduler, this::onBranchChanged);
        this.tabManager = tabManager;
        this.clock = clock;
        this.askUserCardRenderer = askUserCardRenderer;
        this.toolOutputFormat = toolOutputFormat;
        this.btwAnswerer = btwAnswerer;
        this.subagentRegistry = subagentRegistry;
        this.scrollService = scrollService == null ? new ScrollService(null) : scrollService;
        this.formatToolOutput = !"off".equalsIgnoreCase(tuiConfiguration.toolOutputFormat());
        this.syntaxHighlighter = new SyntaxHighlighter().maxLineLength(configuration.maxLineLength());
        this.toolArgsPreview =
                new ToolArgsPreview(toolOutputFormat, syntaxHighlighter, diffRenderer, new ToolPresenter(mapper));
        this.palette = Palette.from(conf.theme(), tuiConfiguration);
        this.glyphs = Glyphs.of(tuiConfiguration.glyphs());
        this.costEstimator = CostEstimator.parse(tuiConfiguration.pricing());
        this.persistHistory = tuiConfiguration.history();
        this.completionEngine = new CompletionEngine(slashRegistry, new ArgCompletion.Env() {
            @Override
            public List<String> models() {
                ensureModelsLoaded();
                final var current = active();
                return completionCache.models(providerIdOf(current));
            }

            @Override
            public List<String> providers() {
                return providerManager == null || providerManager.providers() == null
                        ? List.of()
                        : providerManager.providers().stream()
                                .map(ProviderConfiguration::id)
                                .toList();
            }

            @Override
            public List<String> agents() {
                return agentProvider == null || agentProvider.agents() == null
                        ? List.of()
                        : agentProvider.agents().stream()
                                .map(AgentConfiguration::name)
                                .toList();
            }

            @Override
            public List<String> sessions() {
                ensureSessionsLoaded();
                return completionCache.sessions();
            }

            @Override
            public List<String> tools() {
                ensureToolsLoaded();
                return completionCache.tools();
            }

            @Override
            public List<String> subagents() {
                final var state = active();
                return subagentRegistry == null || state == null
                        ? List.of()
                        : subagentRegistry.completionCandidates(state.sessionId);
            }

            @Override
            public List<String> servers() {
                ensureServersLoaded();
                return completionCache.servers();
            }

            @Override
            public List<String> variants(final String model) {
                ensureVariantsLoaded(model);
                final var current = active();
                return completionCache.variants(providerIdOf(current), model);
            }

            @Override
            public List<String> variants() {
                final var current = active();
                final var model = chatClient == null
                        ? null
                        : chatClient.model(current == null ? null : current.session.chatSettings());
                ensureVariantsLoaded(model);
                return current == null ? List.of() : completionCache.variants(providerIdOf(current), model);
            }

            @Override
            public List<String> paths(final String token) {
                final var current = active();
                return current == null
                        ? List.of()
                        : CompletionEngine.pathCandidates(
                                token, current.runtime().workdir());
            }
        });
        this.promptHistory = new PromptHistory();
        final var configuredTheme = tuiConfiguration == null || tuiConfiguration.theme() == null
                ? null
                : tuiConfiguration.theme().name();
        final var persisted = persistedTheme();
        String theme = null;
        if (configuredTheme != null && !configuredTheme.isBlank()) {
            final var stripped = configuredTheme.strip();
            if ("auto".equalsIgnoreCase(stripped)) {
                theme = ThemeDetector.DETECTOR.get().orElse(null); // best-effort OS preference
            } else {
                theme = stripped;
            }
        }
        applyTheme(theme != null ? theme : persisted == null ? conf.theme() : persisted);
    }

    @RootConfiguration("tui")
    public record TuiConf(
            @Property(
                    defaultValue = "\"\"",
                    documentation = "Resume an existing session by id; a new session is created when empty.")
            String session,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Session context file (a yca session JSONL). Its file name provides the session id to resume or create.")
            String context,

            @Property(
                    defaultValue = "io.yupiik.ia.agent.tui.KeyBindingPreset.STANDARD",
                    documentation = "Key binding preset: STANDARD, VIM, EMACS, INTELLIJ or VSCODE.")
            KeyBindingPreset bindings,

            @Property(defaultValue = "false", documentation = "Show the reasoning/thinking stream by default.")
            boolean showThinking,

            @Property(
                    defaultValue = "true",
                    documentation = "Automatically open the system browser when a login/authorization URL is generated "
                            + "(e.g. /codex login). Disable to only surface the link in the transcript, where "
                            + "it stays clickable (OSC-8 autolink) and fully copyable even when it wraps.")
            boolean autoOpenBrowser,

            @Property(defaultValue = "\"dark\"", documentation = "UI theme: dark or light.")
            String theme,

            @Property(
                    defaultValue = "java.util.List.of()",
                    documentation =
                            "Prompts submitted at startup, in order, through the same path as a typed line: use it to "
                                    + "script a soft resume, e.g. continue re-runs the agent on the persisted history "
                                    + "without sending any message. A slash-prefixed entry is dispatched as a usual "
                                    + "slash command. Values are comma separated; prefix with "
                                    + "sep=<char>, to split on another single character when a value itself contains "
                                    + "commas.")
            List<String> initialPrompt,

            @Property(
                    defaultValue = "null",
                    documentation = "Enable or disable sandboxing for this session; defaults to yca.sandbox.enabled.")
            Boolean sandbox) {

        /**
         * Convenience constructor for the common case: no prompt injected at startup.
         *
         * @param session resume an existing session id
         * @param context session context file
         * @param bindings key binding preset
         * @param showThinking show the reasoning stream
         * @param theme UI theme
         */
        public TuiConf(
                final String session,
                final String context,
                final KeyBindingPreset bindings,
                final boolean showThinking,
                final String theme) {
            this(session, context, bindings, showThinking, true, theme, List.of(), null);
        }
    }

    private Path themeStoragePath() {
        final var root = Path.of(configuration.resolveStorageHome()).toAbsolutePath();
        return root.resolve("theme");
    }

    private Path historyStoragePath() {
        final var root = Path.of(configuration.resolveStorageHome()).toAbsolutePath();
        return root.resolve("history");
    }

    private String persistedTheme() {
        final var file = themeStoragePath();
        try {
            if (Files.isRegularFile(file)) {
                final var value = Files.readString(file).strip().toLowerCase(Locale.ROOT);
                if ("light".equals(value) || "dark".equals(value)) {
                    return value;
                }
            }
        } catch (final IOException e) {
            // ignore and fall back to the configured theme
        }
        return null;
    }

    private void persistTheme(final String theme) {
        final var file = themeStoragePath();
        try {
            Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
            Files.writeString(file, theme);
        } catch (final IOException e) {
            // best-effort persistence; it must never break a command
        }
    }

    private void applyTheme(final String theme) {
        clearRenderCaches();
        final var normalized = "light".equalsIgnoreCase(theme) ? "light" : "dark";
        this.theme = normalized;
        this.palette = Palette.from(normalized, tuiConfiguration);
        this.transcriptRenderer.theme(this.palette, tuiConfiguration.markdown(), glyphs.ascii());
        this.codeTheme = CodeTheme.parse(tuiConfiguration.codeTheme(), "light".equals(normalized));
        this.syntaxHighlighter.theme(codeTheme);
        // opacity is the transparency level toward the panel background (0 opaque,
        // 1 invisible). A negative configured value means "auto": use a theme-aware
        // default so thoughts stay clearly readable (dark prose slightly dimmed toward
        // the panel, light prose almost untouched) while still reading as secondary.
        final var configuredOpacity = tuiConfiguration.thinkingOpacity();
        final var lightTheme = "light".equals(normalized);
        final var defaultOpacity =
                lightTheme ? tuiConfiguration.thinkingOpacityLight() : tuiConfiguration.thinkingOpacityDark();
        final var opacity = Math.clamp(configuredOpacity >= 0 ? configuredOpacity : defaultOpacity, 0.0, 0.95);
        this.thoughtStyle = palette.blended(palette.text(), opacity).italic().dim();
        // code blocks inside thoughts keep their token colors but are laid out bold (matching
        // the inline-code weight; the italic-only layer is gone so highest-prominence text is
        // never italicized away)
        this.thoughtCodeStyle = Style.EMPTY.bold();
        // categorical thought/output separation: reasoning sits on a subtle full-width
        // info-tinted panel (terminals have no alpha, a pre-blended bg IS the tint)
        this.thoughtBg = palette.mono() ? null : TuiColors.blend(palette.info(), palette.backgroundPanel(), 0.9);
        this.thoughtBarStyle = palette.blended(palette.info(), 0.15);
        // single tool cards get a neutral background tint derived from the default background
        // so streamed command output reads as a distinct block (mono terminals skip it);
        // the collapsible tool-run container deliberately inherits the transcript background
        this.toolBg = palette.mono() ? null : TuiColors.blend(palette.text(), palette.backgroundPanel(), 0.95);
        final var toolOpacity = Math.clamp(tuiConfiguration.toolOutputOpacity(), 0.05, 0.95);
        this.toolStyle = palette.blended(palette.text(), toolOpacity);
        // captured log body text keeps the readable text tone (bold for WARNING+,
        // see logStrong(Level)); the per-level color lives on the thin left bar and
        // the [LEVEL] token - no background tint for captured logs
        this.logStyle = palette.blended(palette.text(), opacity);
        // the markdown theme follows the same palette/code-theme switch so /theme (and
        // NO_COLOR) restyles bold and inline code too
        this.markdownStyles = MarkdownTheme.of(
                this.palette, this.codeTheme, tuiConfiguration.strongColor(), tuiConfiguration.inlineCodeColor());
        // each speaker keeps its own color end to end: bold prose follows the role color
        // unless the user pinned an explicit strong color (then both roles share it)
        final var strongColor = tuiConfiguration.strongColor();
        final var roleStrong = strongColor == null || strongColor.isBlank() || "auto".equals(strongColor);
        this.userMarkdownStyles = MarkdownTheme.of(
                this.palette,
                this.codeTheme,
                roleStrong ? palette.primary() : strongColor,
                tuiConfiguration.inlineCodeColor());
        this.assistantMarkdownStyles = MarkdownTheme.of(
                this.palette,
                this.codeTheme,
                roleStrong ? palette.accent() : strongColor,
                tuiConfiguration.inlineCodeColor());
        // screen background: auto and blank keep the terminal background (real transparency),
        // an explicit hex paints a translucent veil of that color over the detected terminal
        // background so the terminal stays visible underneath (mono and NO_COLOR skip colors)
        final var configuredBackground = tuiConfiguration.background();
        if (palette.mono()
                || configuredBackground == null
                || configuredBackground.isBlank()
                || "auto".equalsIgnoreCase(configuredBackground.strip())) {
            // auto and blank keep the terminal background (real transparency), mono skips colors
            this.screenBackground = null;
        } else {
            // an explicit hex paints a translucent veil of that color over the detected terminal
            // background, so the terminal stays visible underneath (backgroundOpacity)
            this.screenBackground = TuiColors.blend(
                    configuredBackground.strip(),
                    ThemeDetector.terminalBackground(),
                    Math.clamp(tuiConfiguration.backgroundOpacity(), 0.0, 1.0));
        }
    }

    @Override
    public void run() {
        mcpManager.start();
        slashCommands.bind(arg -> {
            final var current = active();
            if (current != null) {
                // Slash actions execute on a worker; theme changes also invalidate
                // render-thread-confined caches and must run between frames.
                runLater(current, () -> themeOutcome(arg, current));
            }
            return ShellOutcome.none();
        });
        slashCommands.bindSubagents(this::openSubagents);
        shellRegistry.bindSubagents(subagentRegistry);
        if (persistHistory) {
            this.promptHistory.replaceWith(PromptHistory.load(historyStoragePath(), promptHistory.capacity()));
        }
        // the sessions touched by this run are named once the terminal is released (see the
        // logOpenedSessions call after the try-with-resources below)
        List<UiState> openStates;
        try (final var tui = tuiRunnerFactory.create(conf, tuiConfiguration.hideCursor())) {
            // the manager is application-scoped: clear any state left by a previous run
            tabManager.reset();
            final var initialSessionId = sessionLifecycle.resolveSessionId(conf.session(), conf.context());
            if (conf.context() != null && !conf.context().isBlank()) {
                final var contextPath = sessionStore.path(initialSessionId);
                try {
                    sessionLifecycle.prepareContextFile(contextPath, conf.context());
                } catch (final IOException e) {
                    LOG.severe(() -> "failed to prepare context file " + contextPath + ": " + e.getMessage());
                }
            }
            newTab(tui, initialSessionId);
            submitInitialPrompts();
            if (gitBranchPoller != null) {
                gitBranchPoller.start();
            }
            // while the TUI owns the terminal, stderr logging would corrupt the screen:
            // route app and JLine INFO+ records into the content view (WARNING+ also into
            // the status strip, SEVERE into the Alt+E error modal). The inline root handler
            // installed by AutoLogConfigurer is detached first so nothing prints to the raw
            // terminal, and re-attached on exit so headless/CLI runs keep their inline logs.
            final var rootLogger = Logger.getLogger("");
            final var inlineRootHandler = autoLogConfigurer.installedInlineHandler();
            if (inlineRootHandler != null) {
                rootLogger.removeHandler(inlineRootHandler);
            }
            final var rootFeedback = redirectLoggerToUi("", tui);
            final var julFeedback = redirectLoggerToUi("io.yupiik", tui);
            final var jlineFeedback = redirectLoggerToUi("org.jline", tui);
            // yca enables ESC[?1003h (any-motion mouse tracking, see TuiRunnerFactory) on top
            // of tamboui's own mouse modes. TamboUI's cleanup() disables only ?1000/?1002/
            // ?1006/?1015, so on its own the terminal would keep reporting every mouse move
            // after the TUI exits and the shell would print the SGR reports as garbage.
            // Restore ?1003l on both exit paths: after the runner's close below (clean exit)
            // and from the JVM shutdown hook (SIGTERM / Ctrl-C when the signal handler is not
            // installed), mirroring CliCommand's shutdown-cleanup pattern. The same hook also
            // surfaces the open persisted sessions on the abrupt path: the clean-exit final
            // block (and its logOpenedSessions call) never runs when the JVM is killed, so
            // without this the user would not see where their session went.
            final var shutdownHook = new Thread(
                    () -> {
                        try {
                            tui.backend().writeRaw(TuiRunnerFactory.MOUSE_MOTION_DISABLE);
                            tui.backend().flush();
                        } catch (final IOException e) {
                            // best-effort restoration on the abrupt path, never mask the shutdown
                        }
                        logPersistedSessions(tabManager.list());
                    },
                    "yca-tui-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                final var repaint = new DragRepaint();
                tui.run(repaint::handle, repaint::render);
            } finally {
                // quitting the app tears every tab down: interrupt each running turn, wait
                // for the in-flight futures and release the per-session resources
                final var teardownStart = System.nanoTime();
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine("[yca-shutdown] TUI teardown: started with "
                            + tabManager.list().size() + " open tab(s)");
                }
                openStates = List.copyOf(tabManager.list());
                for (final var state : openStates) {
                    state.interrupted = true;
                    awaitInFlight(state);
                    disposeTab(state);
                }
                if (jlineFeedback != null) {
                    jlineFeedback.run();
                }
                if (julFeedback != null) {
                    julFeedback.run();
                }
                if (rootFeedback != null) {
                    rootFeedback.run();
                }
                if (inlineRootHandler != null) {
                    rootLogger.addHandler(inlineRootHandler);
                }
                if (persistHistory) {
                    promptHistory.save(historyStoragePath());
                }
                if (gitBranchPoller != null) {
                    gitBranchPoller.close();
                }
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(() -> "[yca-shutdown] TUI teardown: closing shared executor after "
                            + (System.nanoTime() - teardownStart) / 1_000_000L + " ms");
                }
                executor.shutdown();
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(() -> "[yca-shutdown] TUI teardown: done ("
                            + (System.nanoTime() - teardownStart) / 1_000_000L + " ms)");
                }
                // clean-exit path of the ?1003l restore: the event loop has ended, so write
                // the disable here while the terminal is still ours, then drop the shutdown
                // hook (which exists only for the abrupt path). The try-with-resources closes
                // the runner right after this block; tamboui's cleanup() then disables its own
                // modes (?1000/?1002/?1006/?1015) after our write.
                try {
                    tui.backend().writeRaw(TuiRunnerFactory.MOUSE_MOTION_DISABLE);
                    tui.backend().flush();
                } catch (final IOException | RuntimeException e) {
                    // best-effort restoration, never mask the normal teardown
                }
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (final IllegalStateException e) {
                    // the JVM is already shutting down: the hook runs or ran on its own
                }
            }
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        // the terminal is released here: tui.close() restored the main screen, so the lines
        // below reach the real terminal instead of the alternate-screen buffer the TUI left
        logOpenedSessions(openStates);
    }

    /** The tab currently rendered and receiving the keyboard. */
    private UiState active() {
        return tabManager.active();
    }

    /**
     * Updates every drag immediately, but paints queued drag updates together instead of
     * rendering the whole scene per mouse report. Confined to one runner's render thread.
     * A normal queued event requests the paint so the runner retains its error handling,
     * post-render processors and debug overlay; no queue draining or direct drawing is needed.
     */
    private final class DragRepaint {
        private long generation;
        private boolean pending;

        private boolean handle(final Event event, final TuiRunner runner) {
            if (event instanceof final DragRepaintEvent repaint) {
                return pending && repaint.generation() == generation;
            }
            final var redraw = onKey(event, runner, active());
            if (event instanceof final MouseEvent mouse
                    && mouse.kind() == MouseEventKind.DRAG
                    && mouse.isLeftButton()) {
                if (redraw && !pending) {
                    pending = true;
                    runner.dispatch(new DragRepaintEvent(generation));
                }
                return false;
            }
            // Preserve input boundaries, including events that normally need no frame.
            return redraw || pending;
        }

        private void render(final Frame frame) {
            // Resize/debug frames can bypass handle(). Any frame satisfies the pending paint.
            pending = false;
            generation++;
            TuiCommand.this.render(frame, active());
        }
    }

    private record DragRepaintEvent(long generation) implements Event {}

    /**
     * Names the sessions this run touched so the user can resume the right one afterwards. Called
     * only once the terminal is released (the TUI close has restored the main screen): each
     * persisted session is logged with its tab title, single-session runs included. Empty sessions
     * (nothing beyond an initial prompt) were deleted by {@link #disposeTab(UiState)} and skipped.
     */
    private void logOpenedSessions(final List<UiState> openStates) {
        logPersistedSessions(openStates);
    }

    /**
     * Logs each persisted, non-empty session among the given tabs. Shared by the clean-exit path
     * (after {@code tui.close()} restored the main screen) and the JVM shutdown hook (SIGTERM /
     * Ctrl-C, where the clean-exit block never runs but the incremental session store is already
     * durable, so only the summary line would otherwise be lost). The summary always goes through
     * the JUL {@code LOG.info} ({@code io.yupiik...}), not raw stdout, so it survives a detached or
     * closing stdout.
     */
    private void logPersistedSessions(final List<UiState> tabs) {
        for (final var state : tabs) {
            if (state.sessionId != null && !sessionStore.isEmpty(state.sessionId)) {
                LOG.info(() -> "session " + state.sessionId + " (" + tabTitle(state) + ")");
            }
        }
    }

    /**
     * Submits the {@code --initial-prompt} entries right after the first tab is opened, in order,
     * through the very same path as a typed line (see {@link #submit(String, TuiRunner, UiState, boolean)}):
     * a resume alias such as {@code continue} restarts the loop on the persisted history without
     * sending any message, a slash command is dispatched as usual and anything else is sent as a
     * user prompt. Each entry gets its own turn: the following ones wait for the turn to end
     * ({@link #submitNextInitialPrompt}) instead of being merged into the running turn as a
     * type-ahead prompt. Once the last one is in, the UI is back to its regular interactive input.
     */
    private void submitInitialPrompts() {
        final var prompts = conf.initialPrompt();
        final var state = active();
        if (prompts == null || prompts.isEmpty() || state == null) {
            return;
        }
        for (final var prompt : prompts) {
            if (prompt != null && !prompt.isBlank()) {
                state.initialPrompts.addLast(prompt.strip());
            }
        }
        runLater(state, () -> submitNextInitialPrompt(state));
    }

    /**
     * Submits the next queued {@code --initial-prompt} entry on the render thread, if any is left
     * and no turn is running.
     */
    private void submitNextInitialPrompt(final UiState state) {
        if (state.model.busy() || state.interrupted || state.inputRoutingPending) {
            return;
        }
        final var next = state.initialPrompts.pollFirst();
        if (next != null) {
            submit(next, state.runner, state, true);
        }
    }

    private void activate(final int index) {
        tabManager.activate(index);
        // force an immediate repaint of the newly-active tab: activation alone schedules a single
        // render, but a busy-but-inert turn (between tool rounds) has nothing else pushing a redraw,
        // so without this the returned-to tab can briefly show a stale idle frame before the next
        // tick/heartbeat. Mark dirty so the pending mutation path repaints it right away.
        final var state = tabManager.get(index);
        if (state != null) {
            state.dirty = true;
        }
    }

    private void cycleTab(final int delta) {
        tabManager.cycle(delta);
    }

    /**
     * Opens a new tab: a fresh session (new id, new persisted store entry) with its own
     * transcript, runtime (permissions, todos, usage) and shell registration. The new tab
     * becomes the active one; turns of the previous tabs keep running in the background.
     */
    private UiState newTab(final TuiRunner tui) {
        return newTab(tui, sessionStore.create());
    }

    /**
     * Opens a new tab on an explicit session: when {@code sessionId} was passed at
     * launch (or comes from a context file) the tab starts on that session and its
     * persisted history is replayed into the model instead of a blank conversation,
     * mirroring {@code /resume}. A brand-new id behaves exactly like {@link #newTab(TuiRunner)}.
     */
    private UiState newTab(final TuiRunner tui, final String sessionId) {
        final var state = new UiState(thinkTags, clock, tuiConfiguration.toolOutput());
        state.diffViewMode =
                configuration.view() == null || configuration.view().diffViewMode() == null
                        ? DiffViewMode.AUTO
                        : configuration.view().diffViewMode();
        state.session.diffViewMode(state.diffViewMode);
        state.sessionId = sessionId;
        state.showThinking = conf.showThinking();
        state.runner = tui;
        state.session.sessionId(state.sessionId).activeAgent(state.activeAgent);
        final var workdir = sessionRuntimeFactory.resolveWorkdir(null, state.sessionId);
        final var runtime = sessionRuntimeFactory.create(state.sessionId, workdir, conf.sandbox());
        state.runtime(runtime);
        state.session.runtime(runtime);
        state.model.bindCompression(runtime.compression());
        final var transcriptConf = tuiConfiguration.transcript();
        state.model.reset();
        state.model.maxHistoryCharacters(
                transcriptConf == null || transcriptConf.maxHistoryCharacters() <= 0
                        ? DEFAULT_MAX_HISTORY_CHARACTERS
                        : transcriptConf.maxHistoryCharacters());
        state.model.replay(sessionStore.load(state.sessionId));
        if (state.title == null || state.title.isBlank()) {
            final var summary = sessionStore.summary(state.sessionId);
            if (summary != null && !summary.isBlank()) {
                state.title = TuiInputSupport.firstLine(summary);
            }
        }
        runtime.permissions().setGate(gate(tui, state));
        runtime.feedback().setGate(feedbackGate(tui, state));
        refreshLabels(state);
        state.model.status("ready");
        shellRegistry.register(env(tui, state));
        tabManager.add(state);
        // a new tab changed the resume-candidate set: the sessions slice must be re-fetched lazily
        completionCache.refreshSessions();
        return state;
    }

    /**
     * Releases a tab: unregisters its shell, drops the per-session gates and deletes the
     * session when it never got any content. Memory extraction is NOT run here: the
     * end-of-turn hook already extracted the delta asynchronously while the app was idle
     * waiting for the user, so a close-time forced pass would be a watermark no-op - and
     * when the user exits right after a turn, waiting for a synchronous extraction would
     * block the quit. Does not wait for turns: the caller does.
     */
    private void disposeTab(final UiState state) {
        clearRenderCaches();
        state.toolOutputs.clear();
        state.toolOverlay = null;
        final var runner = state.runner;
        if (runner != null) {
            shellRegistry.unregister(env(runner, state));
        }
        final var runtime = state.runtime();
        if (runtime != null) {
            runtime.permissions().setGate(null);
            runtime.feedback().setGate(null);
            runtime.close();
        }
        if (state.sessionId != null && sessionStore.isEmpty(state.sessionId)) {
            sessionStore.delete(state.sessionId);
        }
        tabManager.remove(state);
        // a closed tab changed the resume-candidate set: the sessions slice re-fetches lazily
        completionCache.refreshSessions();
    }

    /**
     * Closes a tab after interrupting its turn. Closing the last tab quits the application.
     *
     * @return {@code true} when the event is fully handled
     */
    private boolean closeTab(final UiState state, final TuiRunner tui) {
        state.interrupted = true;
        awaitInFlight(state);
        disposeTab(state);
        if (tabManager.isEmpty()) {
            tui.quit();
            return true;
        }
        activate(tabManager.activeTab());
        return true;
    }

    /**
     * {@code /resume} switched the tab to another session: the per-session world (runtime,
     * todo file, loop session id) is rebuilt and the shell is re-registered under the new id.
     */
    private void rebind(final UiState state) {
        final var tui = state.runner;
        if (tui != null) {
            shellRegistry.unregister(env(tui, state));
        }
        final var workdir = sessionRuntimeFactory.resolveWorkdir(null, state.sessionId);
        final var runtime = sessionRuntimeFactory.create(state.sessionId, workdir, conf.sandbox());
        state.runtime(runtime);
        state.session.runtime(runtime);
        state.model.bindCompression(runtime.compression());
        if (tui != null) {
            runtime.permissions().setGate(gate(tui, state));
            runtime.feedback().setGate(feedbackGate(tui, state));
            shellRegistry.register(env(tui, state));
        }
    }

    /**
     * While the TUI owns the terminal, stderr logging would corrupt the screen:
     * detach the parent handlers of the given logger and route INFO+ records into
     * the active tab content view as log regions (see
     * {@link ConversationTranscript#LOG_BEGIN}); WARNING+ records also reach the
     * status-strip notification and SEVERE the Alt+E error modal. The emitting
     * logger's effective level is respected like a logging framework would
     * ({@code isLoggable}), so a logger level raise downgrades to drop records
     * below it. Returns a restore callback, or null when the logger already
     * carries custom handlers.
     */
    private Runnable redirectLoggerToUi(final String name, final TuiRunner tui) {
        final var logger = Logger.getLogger(name);
        final var previousUseParentHandlers = logger.getUseParentHandlers();
        if (logger.getHandlers().length > 0 || !previousUseParentHandlers) {
            return null;
        }
        logger.setUseParentHandlers(false);
        final Handler handler = new Handler() {
            {
                setLevel(Level.INFO);
            }

            @Override
            public void publish(final LogRecord record) {
                final var level = record.getLevel();
                if (level.intValue() < Level.INFO.intValue()) {
                    return;
                }
                if (!Logger.getLogger(record.getLoggerName()).isLoggable(level)) {
                    return;
                }
                final var message = MESSAGE_FORMATTER.formatMessage(record);
                // a session-attributed record (emitted by the turn path with the session id in scope)
                // must reach the owning tab even when it is not the active one - a background tab's
                // stream failure or compaction warning must never land in the focused tab's content.
                // Records without a session (infrastructure loggers: code graph, MCP, HTTP client)
                // keep the historical active-tab fallback.
                final var sessionId = record instanceof SessionAwareLogRecord s ? s.sessionId() : null;
                tui.runLater(() -> {
                    final var target = resolveLogTarget(sessionId);
                    if (target == null) {
                        return;
                    }
                    target.dirty = true;
                    appendLogRegion(
                            target,
                            level,
                            record.getLoggerName(),
                            message,
                            record.getInstant().toEpochMilli());
                    if (level.intValue() >= Level.WARNING.intValue()) {
                        TuiCommand.notify(target, message);
                        if (level.intValue() >= Level.SEVERE.intValue()) {
                            modelError(target, message);
                        }
                    }
                });
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        return () -> {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(previousUseParentHandlers);
        };
    }

    /** Formats records captured by {@link #redirectLoggerToUi} (JUL placeholder substitution). */
    private static final SimpleFormatter MESSAGE_FORMATTER = new SimpleFormatter();

    /**
     * The tab a captured log record must be rendered into: the tab owning
     * {@code sessionId} when the record is session-attributed, else the active
     * tab (historical global-log fallback). Unknown/vanished sessions also fall
     * back to the active tab so a stale attribution can never drop a message.
     * Render-thread only; {@link TabManager#list()} is a snapshot read.
     */
    private UiState resolveLogTarget(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return active();
        }
        for (final var state : tabManager.list()) {
            if (sessionId.equals(state.sessionId)) {
                return state;
            }
        }
        return active();
    }

    private static final String AGENT_LOGGER_PREFIX = "io.yupiik.ia.agent.";

    private static String shortLoggerName(final String loggerName) {
        return loggerName != null
                        && loggerName.startsWith(AGENT_LOGGER_PREFIX)
                        && loggerName.length() > AGENT_LOGGER_PREFIX.length()
                ? loggerName.substring(AGENT_LOGGER_PREFIX.length())
                : loggerName;
    }

    /**
     * Wraps one captured record between the {@code LOG_BEGIN}/{@code LOG_END}
     * markers so the renderer styles it as its own region: the first line holds
     * the rendered log block label (handlebars, {@code yca.labels.logsTemplate}),
     * then the level and shortened logger name, then the formatted message. Live
     * only: the block is not persisted into the {@link io.yupiik.ia.agent.core.llm.Message}
     * history and is dropped by a {@code /resume} replay, like any ephemeral
     * transcript content.
     */
    private void appendLogRegion(
            final UiState target, final Level level, final String loggerName, final String message, final long at) {
        final var settings = target.session.chatSettings();
        final var mode = target.session.runtime() == null
                ? null
                : String.valueOf(target.session.runtime().permissions().mode());
        final var ctx = new SpeakerLabels.Ctx(
                chatClient == null ? null : chatClient.model(settings),
                chatClient == null ? null : chatClient.providerId(settings),
                target.activeAgent,
                mode,
                target.sessionId,
                null);
        target.model.appendTranscript(ConversationTranscript.LOG_BEGIN + "\n"
                + speakerLabels.logsLabel(ctx.at(at)) + "\n"
                + level.getName() + " " + shortLoggerName(loggerName) + "\n"
                + message + "\n"
                + ConversationTranscript.LOG_END);
    }

    private ApprovalGate gate(final TuiRunner tui, final UiState state) {
        return gate(tui, state, 0);
    }

    private ApprovalGate gate(final TuiRunner tui, final UiState state, final long btwOrdinal) {
        return request -> {
            final var title =
                    (request.origin() == ApprovalGate.Origin.SANDBOX ? "Sandbox restriction: " : "Tool permission: ")
                            + request.title();
            final var detail = request.detail();
            final var toolName = request.toolName();
            final var latch = new CountDownLatch(1);
            final var outcome = new AtomicReference<ApprovalGate.Result>();
            tui.runLater(() -> {
                if (state.interrupted || !btwActive(state, btwOrdinal) || latch.getCount() == 0) {
                    latch.countDown();
                    return;
                }
                state.dirty = true;
                state.approvals.add(new Approval(title, detail, latch, outcome, btwOrdinal));
                if (notifications != null) {
                    notifications.onApprovalRequired(
                            state.sessionId, state.title, toolName, btwOrdinal == 0 ? "main" : "btw", request.origin());
                }
            });
            try {
                while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                    if (state.interrupted || !btwActive(state, btwOrdinal)) {
                        return ApprovalGate.Result.DENY;
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return ApprovalGate.Result.DENY;
            } finally {
                latch.countDown();
                tui.runLater(() -> {
                    state.approvals.removeIf(a -> a.latch() == latch);
                    state.dirty = true;
                });
            }
            final var r = outcome.get();
            return r == null ? ApprovalGate.Result.DENY : r;
        };
    }

    /**
     * A gate owned by the given /btw overlay: pending approvals are tagged with the overlay
     * ordinal so closing the btw modal can deny its pending approvals without touching an
     * approval requested by the main turn.
     */
    private ApprovalGate btwGate(final TuiRunner tui, final UiState state, final long btwOrdinal) {
        return gate(tui, state, btwOrdinal);
    }

    private static boolean btwActive(final UiState state, final long ordinal) {
        return ordinal == 0
                || (state.btw != null
                        && state.btw.ordinal() == ordinal
                        && !state.btw.cancelled().get());
    }

    private FeedbackGate feedbackGate(final TuiRunner tui, final UiState state) {
        return feedbackGate(tui, state, 0);
    }

    private FeedbackGate feedbackGate(final TuiRunner tui, final UiState state, final long btwOrdinal) {
        return questions -> {
            final var latch = new CountDownLatch(1);
            final var answers = new ConcurrentHashMap<String, Object>();
            final var answered = new AtomicBoolean();
            tui.runLater(() -> {
                if (state.interrupted || !btwActive(state, btwOrdinal) || latch.getCount() == 0) {
                    latch.countDown();
                    return;
                }
                state.dirty = true;
                final var prompt = new FeedbackPrompt(questions, latch, answers, answered);
                prompt.btwOrdinal = btwOrdinal;
                if (state.feedbackPrompt == null) {
                    state.feedbackPrompt = prompt;
                } else {
                    state.feedbackQueue.addLast(prompt);
                }
                if (notifications != null) {
                    notifications.onInputRequired(
                            state.sessionId, state.title, questions.size(), btwOrdinal == 0 ? "main" : "btw");
                }
            });
            try {
                while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                    // Unpainted background tabs legitimately have stale render ticks.
                    if (state.interrupted
                            || !btwActive(state, btwOrdinal)
                            || (tabManager.active() == state && renderStalled(state))) {
                        return null;
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                latch.countDown();
                tui.runLater(() -> {
                    state.dirty = true;
                    if (state.feedbackPrompt != null && state.feedbackPrompt.latch() == latch) {
                        state.feedbackPrompt = state.feedbackQueue.pollFirst();
                    }
                    state.feedbackQueue.removeIf(p -> p.latch() == latch);
                });
            }
            return answered.get() ? answers : null;
        };
    }

    /** True when the render/UI thread has not produced a frame for {@link RENDER_STALL_TIMEOUT_MILLIS}. */
    private boolean renderStalled(final UiState state) {
        final var last = state.lastRenderTick;
        return last != 0 && clock.millis() - last >= RENDER_STALL_TIMEOUT_MILLIS;
    }

    private boolean onFeedbackKey(final Event event, final TuiRunner runner, final UiState state) {
        final var prompt = state.feedbackPrompt;
        if (prompt == null) {
            return false;
        }
        if (event instanceof final MouseEvent mouse) {
            return onFeedbackMouse(mouse, state);
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        final var tabCount = prompt.questions().size() + 1;
        final var isConfirmTab = prompt.tab() == prompt.questions().size();
        final var qIndex = isConfirmTab ? 0 : prompt.tab();
        final var fq = prompt.questions().get(qIndex);
        if (isConfirmTab) {
            if (key.isConfirm()) {
                confirmAnswers(state, prompt);
            } else if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
                prompt.tab(Math.max(0, prompt.questions().size() - 1));
            } else if (key.isKey(KeyCode.BACKSPACE)) {
                prompt.tab(Math.max(0, prompt.questions().size() - 1));
            } else if (key.isLeft() || (key.hasCtrl() && key.isCharIgnoreCase('t'))) {
                prompt.tab(prompt.questions().size() - 1);
            } else if (key.isKey(KeyCode.PAGE_UP)) {
                scrollFeedbackQuestion(prompt, -scrollService.pageRows());
            } else if (key.isKey(KeyCode.PAGE_DOWN)) {
                scrollFeedbackQuestion(prompt, scrollService.pageRows());
            }
            return true;
        }
        if (prompt.focusInput()) {
            if (key.isConfirm()) {
                prompt.focusInput(false);
            } else if (key.isKey(KeyCode.TAB)) {
                prompt.focusInput(false);
            } else if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
                cancelFeedback(state);
            } else {
                handleTextInput(fq.ownAnswer(), key);
            }
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('t')) {
            prompt.tab((prompt.tab() + 1) % tabCount);
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            scrollFeedbackQuestion(prompt, -scrollService.pageRows());
            return true;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            scrollFeedbackQuestion(prompt, scrollService.pageRows());
            return true;
        }
        switch (key.code()) {
            case LEFT, BACKSPACE -> toggleTab(prompt, false);
            case RIGHT, ENTER -> toggleTab(prompt, true);
            case UP -> moveOption(prompt, qIndex, -1);
            case DOWN -> moveOption(prompt, qIndex, 1);
            case TAB -> prompt.focusInput(true);
            case CHAR -> {
                if (key.codePoint() == ' ') {
                    if (fq.question.multiple()) {
                        activateCheckbox(fq, prompt.optionsFocus());
                    } else {
                        fq.selectedIndex(prompt.optionsFocus());
                        if (prompt.optionsFocus() == optionCount(fq) && optionCount(fq) > 0) {
                            prompt.focusInput(true);
                        }
                    }
                }
            }
            case ESCAPE -> cancelFeedback(state);
            default -> {}
        }
        return true;
    }

    private boolean onFeedbackMouse(final MouseEvent mouse, final UiState state) {
        final var prompt = state.feedbackPrompt;
        if (prompt == null || !(mouse.isPress() || mouse.isClick()) || !mouse.isLeftButton()) {
            return false;
        }
        final var x = mouse.x();
        final var y = mouse.y();
        final var tabs = prompt.tabsRect();
        if (tabs != null && tabs.contains(x, y)) {
            final var per = Math.max(1, tabs.width() / (prompt.questions().size() + 1));
            final var idx = (x - tabs.x()) / per;
            if (idx >= 0 && idx <= prompt.questions().size()) {
                prompt.tab(idx);
            }
            return true;
        }
        if (prompt.tab() < prompt.questions().size()) {
            final var fq = prompt.questions().get(prompt.tab());
            for (final var row : fq.optionRects()) {
                if (row != null && row.rect().contains(x, y)) {
                    final var i = row.option();
                    if (fq.question.multiple()) {
                        activateCheckbox(fq, i);
                    } else {
                        fq.selectedIndex(i);
                        if (i == optionCount(fq)) {
                            prompt.focusInput(true);
                        }
                    }
                    return true;
                }
            }
        }
        return true;
    }

    private static void toggleTab(final FeedbackPrompt prompt, final boolean forward) {
        TuiFeedbackSupport.toggleTab(prompt, forward);
    }

    private static void scrollFeedbackQuestion(final FeedbackPrompt prompt, final int delta) {
        TuiFeedbackSupport.scrollFeedbackQuestion(prompt, delta);
    }

    private static void moveOption(final FeedbackPrompt prompt, final int qIndex, final int delta) {
        TuiFeedbackSupport.moveOption(prompt, qIndex, delta);
    }

    private static int optionCount(final FeedbackQuestion fq) {
        return TuiFeedbackSupport.optionCount(fq);
    }

    private static boolean ownEntrySelected(final FeedbackQuestion fq) {
        return TuiFeedbackSupport.ownEntrySelected(fq);
    }

    private static void activateCheckbox(final FeedbackQuestion fq, final int idx) {
        TuiFeedbackSupport.activateCheckbox(fq, idx);
    }

    private void handleTextInput(final TextAreaState area, final KeyEvent key) {
        switch (key.code()) {
            case BACKSPACE -> area.deleteBackward();
            case DELETE -> area.deleteForward();
            case LEFT -> area.moveCursorLeft();
            case RIGHT -> area.moveCursorRight();
            case UP -> area.moveCursorUp();
            case DOWN -> area.moveCursorDown();
            case HOME -> area.moveCursorToLineStart();
            case END -> area.moveCursorToLineEnd();
            case CHAR -> {
                if (!key.hasCtrl() && !key.hasAlt()) {
                    final var cp = key.codePoint();
                    area.insert(
                            cp > Character.MAX_VALUE ? new String(Character.toChars(cp)) : String.valueOf((char) cp));
                }
            }
            default -> {}
        }
    }

    private void cancelFeedback(final UiState state) {
        final var prompt = state.feedbackPrompt;
        if (prompt != null) {
            prompt.answered().set(false);
            prompt.latch.countDown();
            state.feedbackPrompt = state.feedbackQueue.pollFirst();
        }
    }

    /** The {@code (<file>:<start>-<end>)} reference {@code read_symbol} prints in its output header. */
    private static final Pattern READ_SYMBOL_FILE = Pattern.compile("\\(([^()\\s]+(?::[^()\\s]+)*):\\d+(?:-\\d+)?\\)");
    /** A {@code read}/{@code read_symbol} body line: {@code "%6d\t"} gutter followed by content. */
    private static final Pattern NUMBERED_OUTPUT_LINE = Pattern.compile("^(\\s*\\d+\\t)(.*)$");

    /**
     * Interrupts the running turn: sets the flag (the loop stops the stream, kills in-flight tools,
     * aborts sub-agents and unwinds by emitting TurnCancelled) and drops everything still scheduled:
     * queued prompts typed while the turn ran and the remaining scripted initial prompts. Without
     * this, the TurnDone/TurnCancelled handler auto-submits the next queued prompt and the work
     * the user just interrupted restarts immediately.
     */
    private void interruptTurn(final UiState state) {
        state.interrupted = true;
        closeBtw(state);
        if (subagentRegistry != null) {
            subagentRegistry.cancelByParentSession(state.sessionId);
        }
        if (state.model.busy() || state.session.busy()) {
            waiting(state, "interrupting...", SpinnerStyle.CLOCK);
        }
        final var queued = state.pendingPrompts.size();
        state.pendingPrompts.clear();
        final var scheduled = state.initialPrompts.size();
        state.initialPrompts.clear();
        if (queued + scheduled > 0) {
            notify(
                    state,
                    (queued + scheduled) + " pending prompt" + ((queued + scheduled) == 1 ? "" : "s") + " cancelled");
        }
    }

    private void confirmAnswers(final UiState state, final FeedbackPrompt prompt) {
        final var answers = prompt.answers();
        for (final var fq : prompt.questions()) {
            final var own = fq.ownAnswer().text().strip();
            final var opts = optionCount(fq);
            if (fq.question.multiple()) {
                final var values = new ArrayList<String>();
                for (int i = 0; i < fq.boxes().length && i < opts; i++) {
                    if (fq.boxes()[i].isChecked()) {
                        values.add(fq.question.options().get(i));
                    }
                }
                if (fq.ownBox().isChecked() && !own.isEmpty()) {
                    values.add(own);
                }
                answers.put(fq.question.id(), values);
            } else {
                final var selected = fq.selectedIndex() >= 0 && fq.selectedIndex() < opts
                        ? fq.question.options().get(fq.selectedIndex())
                        : null;
                // a question with no predefined options only has the free-text field: the answer
                // is the typed text (opts == 0), never a (null) selected option
                final var value = opts == 0 ? own : fq.selectedIndex() == opts && !own.isEmpty() ? own : selected;
                answers.put(fq.question.id(), value);
            }
        }
        prompt.answered().set(true);
        acknowledgeInteraction(state);
        prompt.latch.countDown();
        state.feedbackPrompt = state.feedbackQueue.pollFirst();
    }

    private void onEvent(final AgentEvent event, final UiState state) {
        if (event instanceof AgentEvent.ToolFilesChanged changed) {
            state.toolOutputs.recordFiles(
                    changed.ordinal(),
                    changed.files(),
                    text -> secretRedactor == null ? text : secretRedactor.redact(text, state.sessionId));
            return;
        }
        if (event instanceof AgentEvent.ToolDiagnostic diagnostic) {
            // Keep private failure detail in the in-memory error modal only, not the transcript
            // or ToolOutputStore (which is populated from model-visible tool results).
            final var scope = diagnostic.subagentId() == null
                    ? ""
                    : "[" + (diagnostic.subagentName() == null ? diagnostic.subagentId() : diagnostic.subagentName())
                            + "] ";
            modelError(
                    state,
                    scope + diagnostic.name()
                            + (diagnostic.ordinal() > 0 ? " #" + diagnostic.ordinal() : "")
                            + " diagnostic:\n" + diagnostic.detail());
            return;
        }
        if (event instanceof AgentEvent.Status status) {
            // informational progress (output optimization, graph refresh, provider pauses,
            // stale-read invalidations...): appended to the trailing notification slot instead
            // of replacing the leading agent state, which must stay short and stable
            notify(state, status.message());
            return;
        }
        if (event instanceof AgentEvent.Pause pause) {
            // transient provider-imposed delay (throttle/retry backoff): trailing right-slot
            // feedback only - the leading status stays on "thinking"/"working", and the message
            // stays until the next pause or the TTL expiry. The renderer already adds the ⓘ
            // glyph and the warning tint to the notification slot.
            notify(state, pause.message());
            return;
        }
        if (event instanceof AgentEvent.PromptIntegrated) {
            // the queued prompt already landed in the transcript (**You** block): keep the
            // leading agent state stable (spinner/status) and surface the integration in the
            // trailing notification slot, with the count of prompts still waiting
            final var leading = state.model.status();
            state.model.onEvent(event);
            state.model.status(leading);
            final var n = state.pendingPrompts.size();
            notify(state, n == 0 ? "queued prompt integrated" : (n + " queued prompts still pending"));
            return;
        }
        if (event instanceof AgentEvent.Error error) {
            // a failed turn (eg. transport error with all retries exhausted) must land in the
            // red trailing error slot (right, Alt+E for details), never in the leading status
            // text which stays on the short busy/ready state: recording it before the model
            // event lets the shared transcript keep the "error: ..." line for history while
            // the status strip shows it as an error (clearError runs on the next submit).
            modelError(state, error.message());
            state.model.onEvent(event);
            state.model.status("ready");
        } else if (event instanceof AgentEvent.Notice notice
                && (notice.level() == AgentEvent.Notice.Level.ERROR
                        || notice.level() == AgentEvent.Notice.Level.WARN)) {
            // ERROR/WARN notices (truncation give-up, compaction exhaustion, memory extraction
            // failure, model/prompt rejection...) are failures the user must not miss: render them in
            // the trailing error slot (same as the web renders WARN prominently in red), keeping the
            // leading agent state (working) untouched. WARNs were previously only a muted inline
            // transcript line, which made background failures (eg. memory extraction) easy to miss.
            //
            // An ERROR-level notice is a permanent failure the user must acknowledge, so it pins the
            // banner until dismissed (Alt+X / Esc / clicking the ✖) or the next submit. A WARN notice,
            // by contrast, is often a recoverable, self-healing condition (a mid-stream retry, an
            // empty-completion retry, a compaction nudge...) that may resolve on the next request:
            // it is recorded as a transient error so a subsequent successful LlmRequestCompleted
            // auto-clears it instead of leaving a stale, unclosable banner on screen.
            final var transient_ = notice.level() == AgentEvent.Notice.Level.WARN;
            handleCompactionNotice(state, notice);
            modelError(state, notice.message(), notice.cause(), transient_);
            final var leading = state.model.status();
            state.model.onEvent(event);
            state.model.status(leading);
        } else {
            // a successful main LLM request proves the recoverable (transient) error is healed: if the
            // banner came from a retry/self-healing WARN notice, drop it now so it does not linger for
            // the rest of the session. Persistent errors (ERROR notices, turn failures) are never
            // auto-cleared here.
            if (event instanceof AgentEvent.LlmRequestCompleted
                    && state.errorMessage != null
                    && state.errorDetail != null
                    && state.errorDetail.transient_()) {
                clearError(state);
            }
            if (event instanceof AgentEvent.Notice compactionNotice) {
                handleCompactionNotice(state, compactionNotice);
                if (compactionNotice.compaction() == AgentEvent.Notice.CompactionLifecycle.NONE) {
                    notify(state, compactionNotice.message());
                } else if (compactionNotice.compaction() == AgentEvent.Notice.CompactionLifecycle.START) {
                    state.lastNotification = compactionNotice.message();
                }
            }
            state.model.onEvent(event);
        }
        if (event instanceof AgentEvent.ToolStarted started) {
            state.spinnerState.reset();
            state.pendingToolArgs
                    .computeIfAbsent(started.name(), n -> new ArrayDeque<>())
                    .addLast(started.args());
            // reserve the ordinal this call will be stamped with so the running tool
            // opens its overlay immediately; the ToolFinished event overwrites it
            final var ordinal = started.ordinal() > 0 ? started.ordinal() : state.model.toolCallCount() + 1;
            state.pendingToolOrdinals
                    .computeIfAbsent(started.name(), n -> new ArrayDeque<>())
                    .addLast(ordinal);
            // the renderer resolves the running card's ordinal from the command label
            // (a running card's ToolCard.name holds the command, not the tool name)
            final var cmd = commandOfArgs(started.args());
            if (cmd != null && !cmd.isBlank() && !cmd.equals(started.name())) {
                state.pendingToolOrdinals
                        .computeIfAbsent(cmd, n -> new ArrayDeque<>())
                        .addLast(ordinal);
            }
            state.toolOutputs.record(
                    ordinal, new ToolOutputStore.Entry(started.name(), started.args(), "", false, -1L));
        } else if (event instanceof AgentEvent.ToolStream stream) {
            // a running tool's output streams into the store so the running-tool overlay
            // (opened by clicking the live card) shows the live tail, not a blank body
            final var deque = state.pendingToolOrdinals.get(stream.name());
            if (stream.ordinal() > 0 || (deque != null && !deque.isEmpty())) {
                final var ordinal = stream.ordinal() > 0 ? stream.ordinal() : deque.peekLast();
                state.toolOutputs.append(ordinal, stream.fragment());
            }
        } else if (event instanceof AgentEvent.ToolFinished finished) {
            // the transcript just stamped this call with ordinal #toolCallCount: keep the
            // full uncapped output around so the Ctrl+O overlay can expand the truncated card
            final var args = state.pendingToolArgs.get(finished.name());
            final var ordinals = state.pendingToolOrdinals.get(finished.name());
            final var stored = finished.ordinal() > 0 ? state.toolOutputs.get(finished.ordinal()) : null;
            final var argumentIndex = finished.ordinal() > 0
                    ? ordinals == null ? -1 : new ArrayList<>(ordinals).indexOf(finished.ordinal())
                    : 0;
            // A finish can arrive out of order: its ordinal's snapshot, not the name FIFO,
            // owns the request. The positional fallback also handles an evicted snapshot.
            String queuedArgs = null;
            if (args != null && argumentIndex >= 0) {
                final var iterator = args.iterator();
                for (var index = 0; iterator.hasNext(); index++) {
                    final var candidate = iterator.next();
                    if (index == argumentIndex) {
                        queuedArgs = candidate;
                        iterator.remove();
                        break;
                    }
                }
            }
            final var argsValue = stored != null ? stored.args() : queuedArgs == null ? "" : queuedArgs;
            if (args != null && args.isEmpty()) {
                state.pendingToolArgs.remove(finished.name());
            }
            // The runner stamps the same authoritative ordinal on both ToolStarted and ToolFinished
            // (a per-session atomic): prefer it so a same-name overlap (a subagent streaming the same
            // tool, or parallel batches) can never record the finish under the wrong reserved ordinal
            // and leave another call stuck "executing". Only fall back to the name deque / toolCallCount
            // for legacy events that carry no ordinal (mirrors ConversationTranscript and the web reducer).
            final long ordinal;
            if (finished.ordinal() > 0) {
                ordinal = finished.ordinal();
                if (ordinals != null) {
                    ordinals.remove(ordinal);
                }
            } else {
                ordinal = ordinals == null || ordinals.isEmpty() ? state.model.toolCallCount() : ordinals.pollFirst();
            }
            if (ordinals != null && ordinals.isEmpty()) {
                state.pendingToolOrdinals.remove(finished.name());
            }
            // drop the command-keyed alias (same ordinal) so the renderer fallback
            // cannot resurrect a finished card as if it were still running
            final var cmd = commandOfArgs(argsValue == null ? "" : argsValue);
            if (cmd != null && !cmd.isBlank() && !cmd.equals(finished.name())) {
                final var alias = state.pendingToolOrdinals.get(cmd);
                if (alias != null) {
                    alias.remove(ordinal);
                    if (alias.isEmpty()) {
                        state.pendingToolOrdinals.remove(cmd);
                    }
                }
            }
            state.toolOutputs.record(
                    ordinal,
                    new ToolOutputStore.Entry(
                            finished.name(),
                            argsValue == null ? "" : argsValue,
                            finished.output(),
                            !finished.success(),
                            finished.pending(),
                            0));
            // an open Ctrl+O overlay bound to this call must leave the running state too: only the
            // ToolStream path re-arms it today, so a finish with no stream in between would leave the
            // modal stuck on the running entry ("pending… (executing)") even after the card below
            // turned done. Mirror the ToolStream swap (the store now holds the non-running finish),
            // preserving the scroll offset.
            if (state.toolOverlay != null && state.toolOverlay.ordinal() == ordinal) {
                state.toolOverlay = state.toolOverlay.withEntry(state.toolOutputs.get(ordinal));
            }
        } else if (event instanceof AgentEvent.TurnDone || event instanceof AgentEvent.TurnCancelled) {
            // tab icon: the last turn finished (done or cancelled) until a new one starts;
            // a cancellation is not an error, only the end of the current turn
            state.turnCompleted = true;
            state.wait = null;
            // a compaction lifecycle can never outlive its turn: a turn that ends without a
            // "Compacted:"/"Compaction gate:" notice (cancel, hard interrupt) must clear the
            // pinned compacting indicator, otherwise the ⓘ slot would stay stuck forever.
            if (state.compactionActive) {
                state.compactionActive = false;
                state.notification = null;
                state.notificationShownAt = 0;
            }
            // the interrupt dropped the queues (see interruptTurn): nothing may auto-submit after
            // a cancel, even a prompt re-queued in the race window
            if (!state.interrupted) {
                if (!state.pendingPrompts.isEmpty()) {
                    final var runner = state.runner;
                    if (runner != null && !state.model.busy()) {
                        final var next = state.pendingPrompts.removeFirst();
                        runLater(state, () -> submitPrompt(next, state, false));
                    }
                }
                // startup prompts run one turn each: the next one starts now that this turn ended
                if (!state.initialPrompts.isEmpty() && state.runner != null && !state.model.busy()) {
                    runLater(state, () -> submitNextInitialPrompt(state));
                }
            }
        }
    }

    /**
     * True when a periodic tick may need a repaint even though no input event fired:
     * every animated region (spinner, "thinking Xs" counter, tool progress, queued prompts),
     * a live toast (its expiry changes the frame), or a pending background mutation that was
     * posted through {@link #runLater(UiState, Runnable)} and still awaits its paint.
     * Anything else ticks as cheaply as possible: the handler returns false and the frame
     * is left untouched until input or a state change forces one.
     */
    private boolean needsTickRender(final UiState state) {
        return state.model.busy()
                || state.model.runningTool() != null
                || activeWait(state) != null
                || state.model.thinkingLength() > 0
                || !state.pendingPrompts.isEmpty()
                || !state.initialPrompts.isEmpty()
                || state.toast != null
                || state.dirty;
    }

    /**
     * Posts a UI mutation from a background thread and marks the tab dirty so the next tick
     * paints it: the TamboUI runner executes the action on the render thread without an
     * immediate redraw, the tick right after carries the paint (exactly the pre-optimization
     * cadence, but only while a mutation is actually pending).
     */
    private void runLater(final UiState state, final Runnable action) {
        state.runner.runLater(() -> {
            state.dirty = true;
            action.run();
        });
    }

    private boolean onKey(final Event event, final TuiRunner runner, final UiState state) {
        if (event instanceof final PasteEvent paste) {
            if (state.notificationDetail != null) {
                return true;
            }
            // normalize CRLF/CR so multiline pastes render and submit cleanly
            final var text = paste.text().replace("\r\n", "\n").replace("\r", "\n");
            if (state.feedbackPrompt != null) {
                final var area = feedbackInput(state.feedbackPrompt);
                area.insert(text);
                return true;
            }
            beforeEdit(state);
            state.input.insert(text);
            return true;
        }
        if (event instanceof TickEvent) {
            // idle heartbeat: only repaint when something could have changed since the last
            // frame (busy/animations, a running-mutation painted via runLater, a live toast),
            // or when the safety repaint interval expired to bound any missed update
            return needsTickRender(state)
                    || (heartbeatMillis > 0 && clock.millis() - state.lastRenderTick >= heartbeatMillis);
        }
        if (event instanceof final MouseEvent wheel && wheel.isScroll()) {
            return scrollTopOverlay(wheel, state);
        }
        if (event instanceof final MouseEvent move && move.kind() == MouseEventKind.MOVE) {
            // a MOVE can drive the tab-bar title tooltip, the tool-output modal footer
            // tooltip, or the sidebar quota-gauge reset tooltip; these are pure hover
            // states with no fallthrough. repaint when any hover state changed (a moved
            // pointer over a footer/sidebar must schedule a redraw or the chip never appears)
            final var footerHovered = state.toolOverlay != null && onToolFooterHover(move, state);
            return footerHovered
                    | onTabHover(move, state)
                    | onQuotaHover(move, state)
                    | onContextHover(move, state)
                    | (state.toolOverlay == null && onToolCardHover(move, state));
        }
        if (state.toolOverlay != null) {
            return onToolOverlayEvent(event, runner, state);
        }
        if (tabManager.isListOpen()) {
            return onTabsListKey(event);
        }
        if (state.shortcutsOpen) {
            return onShortcutsKey(event, state);
        }
        if (state.subagentsOverlay != null) {
            return onSubagentsKey(event, state);
        }
        if (state.searchOpen) {
            return onSearchKey(event, state);
        }
        if (state.errorDetail != null && state.errorOpen) {
            return onErrorKey(event, runner, state);
        }
        if (state.notificationDetail != null) {
            return onNotificationKey(event, runner, state);
        }
        if (state.editPlan != null) {
            if (event instanceof final MouseEvent editMouse) {
                return onEditPlanMouse(editMouse, state);
            }
            if (event instanceof final KeyEvent editKey) {
                return onEditPlanKey(editKey, runner, state);
            }
            return false;
        }
        if (state.feedbackPrompt != null) {
            return onFeedbackKey(event, runner, state);
        }
        if (state.picker != null) {
            if (event instanceof PasteEvent) {
                return true;
            }
            if (event instanceof final MouseEvent pickMouse) {
                return onPickerMouse(pickMouse, state);
            }
            if (event instanceof final KeyEvent pickKey) {
                if (!state.picker.confirm(pickKey)) {
                    state.picker.stroke(pickKey, scrollService);
                }
            }
            return true;
        }
        if (event instanceof final MouseEvent mouse) {
            // the wheel is already routed to the topmost scrollable view by scrollTopOverlay
            if (mouse.isPress() && mouse.isLeftButton()) {
                // a press anywhere dismisses the tab tooltip (the click either acts or lands elsewhere)
                if (state.hoverTitle != null) {
                    state.hoverTitle = null;
                }
                state.hoverQuotaReset = null;
                state.hoverQuotaRect = null;
                state.hoverContextSize = null;
                state.hoverToolCard = -1L;
                // tab close zones first: they are sub-rects of the tab cells
                for (var i = 0; i < tabCloseRects.size(); i++) {
                    if (i < tabManager.size() && tabCloseRects.get(i).contains(mouse.x(), mouse.y())) {
                        state.selection.clear();
                        closeTab(tabManager.get(i), runner);
                        return true;
                    }
                }
                // the "+" cell at the end of the bar opens a new tab
                if (tabAddRect != null && tabAddRect.contains(mouse.x(), mouse.y())) {
                    state.selection.clear();
                    newTab(runner);
                    return true;
                }
                // tab strip: clicking a tab switches to it
                for (var i = 0; i < tabRects.size(); i++) {
                    if (tabRects.get(i).contains(mouse.x(), mouse.y())) {
                        state.selection.clear();
                        activate(i);
                        return true;
                    }
                }
                // shortcuts appearance: clicking the "?" opens the full list
                if (state.shortcutRect != null && state.shortcutRect.contains(mouse.x(), mouse.y())) {
                    state.selection.clear();
                    state.shortcutsOpen = !state.shortcutsOpen;
                    state.shortcutsScroll = 0;
                    return true;
                }
                // error banner: clicking its leading "✖ " dismisses the error
                if (state.errorCloseRect != null && state.errorCloseRect.contains(mouse.x(), mouse.y())) {
                    state.selection.clear();
                    clearError(state);
                    return true;
                }
                // queued prompts: clicking a queued row removes it
                for (var i = 0; i < state.queueRects.size(); i++) {
                    if (state.queueRects.get(i).contains(mouse.x(), mouse.y())) {
                        state.selection.clear();
                        removeQueued(state, i);
                        return true;
                    }
                }
                // run headers: clicking toggles that run (collapse/expand)
                for (final var h : state.runHeaders) {
                    if (h.header().contains(mouse.x(), mouse.y())) {
                        state.selection.clear();
                        final var key = h.id();
                        final var current = state.runCollapsed.getOrDefault(
                                key, state.showToolActions == ToolOutputVisibility.COLLAPSED);
                        state.runCollapsed.put(key, !current);
                        return true;
                    }
                }
                // truncated tool cards: clicking the "click to expand" footer opens the full output
                for (final var link : state.toolFooterLinks) {
                    if (link.ordinal() >= 0 && link.header().contains(mouse.x(), mouse.y())) {
                        state.selection.clear();
                        openToolOverlay(state, link.ordinal());
                        return true;
                    }
                }
                // tool card headers: clicking toggles that card's collapse
                // (a running card opens the live overlay instead: it has nothing to collapse).
                // Collapsed cards opened through a run group (dim one-liner members) always
                // open the full output - they are clicks on completed cards with no expansion
                // left to do, and matching the old collapsed-mode behavior of opening the
                // overlay rather than toggling state on a line that has no state to toggle.
                for (final var anchor : state.toolAnchors) {
                    if (anchor.ordinal() >= 0 && anchor.header().contains(mouse.x(), mouse.y())) {
                        state.selection.clear();
                        final var runningEntry = state.toolOutputs.get(anchor.ordinal());
                        if (runningEntry != null && runningEntry.running()) {
                            openToolOverlay(state, anchor.ordinal());
                        } else if (anchor.runMember()) {
                            // a member rendered inside a run box has no per-card collapse
                            // state of its own: clicking it always opens the full output
                            openToolOverlay(state, anchor.ordinal());
                        } else if (state.showToolActions == ToolOutputVisibility.COLLAPSED) {
                            // condensed mode: the dim one-liner itself opens the full output
                            openToolOverlay(state, anchor.ordinal());
                        } else if (state.collapsedTools.contains(anchor.ordinal())) {
                            // per-card collapsed (was a dim one-liner): open the full output
                            openToolOverlay(state, anchor.ordinal());
                        } else if (state.showToolActions == ToolOutputVisibility.SHOWN) {
                            // fully rendered card: the header collapses the card itself
                            state.collapsedTools.add(anchor.ordinal());
                        }
                        return true;
                    }
                }
                // completion popup: a left click selects the clicked candidate row
                // (the popup is a modal overlay, so it consumes the press entirely)
                if (state.completionRect != null && state.completionRect.contains(mouse.x(), mouse.y())) {
                    final var clicked = completionItemAt(state, mouse.x(), mouse.y());
                    if (clicked >= 0) {
                        state.completionIndex = clicked;
                        state.completionNavigated = true;
                        state.dirty = true;
                    }
                    return true;
                }
                // prompt input: a left click positions the edit caret (the selection
                // press below still anchors the drag so text selection keeps working)
                if (state.inputZone != null && state.inputZone.contains(mouse.x(), mouse.y())) {
                    dismissCompletion(state);
                    final var caret = inputCaretAt(state, mouse.x(), mouse.y());
                    applyCursor(state, caret.row(), caret.col());
                }
                state.selection.press(mouse.x(), mouse.y(), state.selectionFrame, copyZones(state));
                return true;
            }
            if (mouse.kind() == MouseEventKind.DRAG && mouse.isLeftButton()) {
                state.selection.drag(mouse.x(), mouse.y());
                return true;
            }
            if (mouse.isRelease() && mouse.isLeftButton()) {
                copySelection(runner, state);
                return true;
            }
            if (mouse.isPress()) {
                state.selection.clear();
                return true;
            }
            return false;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (!state.approvals.isEmpty()) {
            final var approval = state.approvals.peek();
            if (key.isConfirm()) {
                acknowledgeInteraction(state);
                final var ok = approval.perform();
                state.approvals.poll();
                notify(state, ok ? "approved" : "denied");
            } else if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
                acknowledgeInteraction(state);
                approval.perform();
                state.approvals.poll();
                notify(state, "denied");
            } else if (key.isKey(KeyCode.LEFT)) {
                approval.action(approval.action() - 1);
            } else if (key.isKey(KeyCode.RIGHT) || key.isKey(KeyCode.TAB)) {
                approval.action(approval.action() + 1);
            } else if (key.isChar('1')) {
                approval.action(0);
            } else if (key.isChar('2')) {
                approval.action(1);
            } else if (key.isChar('3')) {
                approval.action(2);
            } else {
                approval.detailScroll(scrollService.apply(approval.detailScroll(), scrollService.keyDelta(key)));
            }
            return true;
        }
        if (state.btw != null) {
            return onBtwKey(key, runner, state);
        }
        if (state.executingTools != null) {
            onExecutingToolsKey(key, state);
            return true;
        }
        if (key.hasCtrl() && key.hasShift() && key.isCharIgnoreCase('c')) {
            copySelection(runner, state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('n')) {
            newTab(runner);
            return true;
        }
        if (key.hasAlt() && !key.hasCtrl() && key.isCharIgnoreCase('w')) {
            return closeTab(state, runner);
        }
        // fallback: many terminals intercept Ctrl+Shift+W as a window close, Alt+W is the safe one
        if (key.hasCtrl() && key.hasShift() && key.isCharIgnoreCase('w')) {
            return closeTab(state, runner);
        }
        if (key.hasCtrl() && !key.hasShift() && (key.isKey(KeyCode.TAB) || key.isKey(KeyCode.PAGE_DOWN))) {
            cycleTab(1);
            return true;
        }
        if (key.hasCtrl() && (key.hasShift() && key.isKey(KeyCode.TAB) || key.isKey(KeyCode.PAGE_UP))) {
            cycleTab(-1);
            return true;
        }
        if (key.hasAlt() && !key.hasCtrl() && key.character() >= '1' && key.character() <= '9') {
            final var index = Character.digit(key.character(), 10) - 1;
            if (index < tabManager.size()) {
                activate(index);
            }
            return true;
        }
        if (key.isCtrlC()) {
            if (state.interruptor.interrupt(state.model.busy()) == InterruptCoordinator.Decision.INTERRUPT) {
                state.quitArmed = true;
                interruptTurn(state);
                waiting(state, "interrupting... press Ctrl+C again to quit", SpinnerStyle.CLOCK);
            } else {
                state.quitArmed = false;
                // quitting interrupts every tab, not only the visible one
                for (final var tab : tabManager.list()) {
                    tab.interrupted = true;
                    awaitInFlight(tab);
                }
                runner.quit();
            }
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('t')) {
            state.showThinking = !state.showThinking;
            // UI state is authoritative for the TUI; the /thinking command routes through
            // the frontend which reflects the same toggle, so no session mirror is kept here
            notify(state, "thinking blocks " + (state.showThinking ? "shown" : "hidden"));
            return true;
        }
        if (key.hasAlt() && key.isChar('?')) {
            state.shortcutsOpen = !state.shortcutsOpen;
            state.shortcutsScroll = 0;
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('o')) {
            final var url = state.lastExternalUrl;
            if (url == null || url.isBlank()) {
                notify(state, "no link to open yet");
            } else {
                OpenBrowser.open(url);
                notify(state, "opening link...");
            }
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('p')) {
            if (state.showTodos && !state.showTodosCollapsed) {
                state.showTodosCollapsed = true;
                notify(state, "todos collapsed");
            } else if (state.showTodos) {
                state.showTodos = false;
                state.showTodosCollapsed = false;
                notify(state, "todos hidden");
            } else {
                state.showTodos = true;
                notify(state, "todos shown");
            }
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('s')) {
            compact(runner, state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('l')) {
            state.model.clearConversation();
            state.toolNavIndex = -1;
            state.focusedToolOrdinal = -1;
            state.collapsedTools.clear();
            state.runCollapsed.clear();
            notify(state, "transcript cleared");
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('y')) {
            state.showToolActions = state.showToolActions.next();
            // keep the session flag in sync so /tool-output (no arg) reports the rendered state
            state.session.showToolOutput(state.showToolActions);
            notify(state, "tool blocks " + state.showToolActions.name().toLowerCase(Locale.ROOT));
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('f')) {
            openSearch(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('o')) {
            openToolOverlay(
                    state,
                    state.focusedToolOrdinal >= 0 ? state.focusedToolOrdinal : state.toolOutputs.latestOrdinal());
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('e') && state.errorDetail != null) {
            state.errorOpen = !state.errorOpen;
            state.errorScroll = 0;
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('i') && state.lastNotification != null) {
            state.notificationDetail = state.lastNotification;
            state.notificationScroll = 0;
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('x') && state.errorDetail != null) {
            clearError(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('x') && !state.pendingPrompts.isEmpty()) {
            removeQueued(state, state.pendingPrompts.size() - 1);
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('c')) {
            copyLastCodeBlock(runner, state);
            return true;
        }
        // Alt+Up/Down plus Alt+K/Alt+J: alt+arrows are not delivered by every
        // terminal (macOS Terminal, rxvt) so the letters are the portable fallback
        if (key.hasAlt() && (key.isKey(KeyCode.UP) || key.isCharIgnoreCase('k'))) {
            navigateTools(state, -1);
            return true;
        }
        if (key.hasAlt() && (key.isKey(KeyCode.DOWN) || key.isCharIgnoreCase('j'))) {
            navigateTools(state, 1);
            return true;
        }
        if (key.hasCtrl() && !key.hasShift() && key.isCharIgnoreCase('z')) {
            final var previous = state.undoStack.undo(state.input.text());
            if (previous != null) {
                state.input.setText(previous);
                positionCursorAtEnd(state);
            }
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('z')) {
            final var next = state.undoStack.redo(state.input.text());
            if (next != null) {
                state.input.setText(next);
                positionCursorAtEnd(state);
            }
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('y')) {
            yank(state);
            return true;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
            final var pending = state.pendingLine;
            if (pending != null) {
                state.input.clear();
                state.pendingLine = null;
                pending.complete(Optional.empty());
                return true;
            }
            if (state.completion != null) {
                // a completion popup is a modal-ish overlay: first Esc dismisses
                // it without touching the prompt or the selection, so a second
                // Esc can cancel/submit normally (pendingLine, selection, busy)
                dismissCompletion(state);
                return true;
            }
            if (state.selection.hasSelection()) {
                state.selection.clear();
                return true;
            }
            // A passive error banner must not swallow the active session's stop key.
            if (state.model.busy() || state.session.busy()) {
                interruptTurn(state);
            } else if (state.errorDetail != null) {
                clearError(state);
            } else {
                interruptTurn(state);
            }
            return true;
        }
        if (key.isPageUp()) {
            scrollTranscript(state, -scrollService.pageRows());
            return true;
        }
        if (key.isPageDown()) {
            scrollTranscript(state, scrollService.pageRows());
            return true;
        }
        if (key.hasCtrl() && key.isKey(KeyCode.HOME)) {
            state.model.anchorRow(0);
            state.model.userScrolled(true);
            return true;
        }
        if (key.hasCtrl() && key.isKey(KeyCode.END)) {
            state.model.anchorRow(state.maxViewOffset);
            state.model.userScrolled(false);
            return true;
        }
        if (key.hasShift() && key.isKey(KeyCode.TAB)) {
            // shift+tab flips plan/build like /mode (tab stays on completion)
            slash(
                    "/mode " + (state.session.runtime().permissions().mode() == AgentMode.BUILD ? "plan" : "build"),
                    runner,
                    state);
            return true;
        }
        if (key.isKey(KeyCode.TAB)) {
            // completion is a modal-ish overlay: Tab applies the highlighted
            // candidate and keeps the popup open (idempotent); it never leaks
            // to permission approvals (which live in the overlay chain above)
            tabCompletion(state);
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('b')) {
            moveCursorWord(state, false);
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('f')) {
            moveCursorWord(state, true);
            return true;
        }
        if (key.hasCtrl() && key.isKey(KeyCode.LEFT)) {
            moveCursorWord(state, false);
            return true;
        }
        if (key.hasCtrl() && key.isKey(KeyCode.RIGHT)) {
            moveCursorWord(state, true);
            return true;
        }
        if (key.hasAlt() && !key.hasShift() && key.isKey(KeyCode.BACKSPACE)) {
            deleteWordBackward(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('a')) {
            state.input.moveCursorToLineStart();
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('e')) {
            state.input.moveCursorToLineEnd();
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('k')) {
            killLineForward(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('u')) {
            killLineBackward(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('w')) {
            deleteWordBackward(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('r')) {
            openHistoryPicker(state);
            return true;
        }
        if (key.isKey(KeyCode.UP)) {
            handleLineOrHistory(state, true);
            return true;
        }
        if (key.isKey(KeyCode.DOWN)) {
            handleLineOrHistory(state, false);
            return true;
        }
        if (key.isKey(KeyCode.ENTER) && (key.hasShift() || key.hasAlt())) {
            // Shift+Enter (when the terminal reports the modifier) or the portable
            // Alt+Enter: insert a hard newline instead of submitting. Plain Enter
            // (no modifier) still submits through the isConfirm() branch below.
            beforeEdit(state);
            state.input.insert("\n");
            return true;
        }
        if (key.isConfirm() && !key.hasShift() && !key.hasAlt()) {
            final var pending = state.pendingLine;
            if (pending != null) {
                final var answer = state.input.text();
                state.input.clear();
                state.pendingLine = null;
                acknowledgeInteraction(state);
                pending.complete(Optional.of(answer));
                return true;
            }
            if (state.completion != null && !state.completion.empty()) {
                // a completion popup with candidates is a modal-ish overlay: Enter
                // accepts the highlighted candidate instead of submitting the line
                // (Esc dismisses the popup first, a second Enter then submits). The
                // user may also keep typing to refine the candidates.
                //
                // But a no-op completion (the highlighted candidate is textually
                // identical to the span it would replace, e.g. a fully typed command)
                // must let Enter submit instead of silently eating the keystroke:
                // otherwise a fully typed "/editplan" needs a confusing second Enter.
                if (completionWouldChangeInput(state)) {
                    applyCompletion(state);
                    dismissCompletion(state);
                    return true;
                }
            }
            final var text = state.input.text().strip();
            state.input.clear();
            if (!text.isEmpty()) {
                submit(text, runner, state, true);
            }
            return true;
        }
        switch (key.code()) {
            case ENTER -> {
                beforeEdit(state);
                state.input.insert("\n");
            }
            case BACKSPACE -> {
                beforeEdit(state);
                state.input.deleteBackward();
                liveComplete(state);
            }
            case DELETE -> {
                beforeEdit(state);
                state.input.deleteForward();
                liveComplete(state);
            }
            case LEFT -> {
                dismissCompletion(state);
                state.input.moveCursorLeft();
            }
            case RIGHT -> {
                dismissCompletion(state);
                state.input.moveCursorRight();
            }
            case HOME -> {
                dismissCompletion(state);
                state.input.moveCursorToLineStart();
            }
            case END -> {
                dismissCompletion(state);
                state.input.moveCursorToLineEnd();
            }
            case CHAR -> {
                if (!key.hasCtrl()) {
                    final var cp = key.codePoint();
                    beforeEdit(state);
                    state.input.insert(
                            cp > Character.MAX_VALUE ? new String(Character.toChars(cp)) : String.valueOf((char) cp));
                    liveComplete(state);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Copies the current selection to the system clipboard over OSC 52. The
     * write is scheduled on the render thread so it never interleaves with a
     * frame draw. Single-character selections (plain clicks) are ignored to
     * avoid accidental clipboard overwrites.
     */
    private void copySelection(final TuiRunner runner, final UiState state) {
        final var text = state.selection.copy();
        if (text == null || text.length() <= 1 || text.isBlank()) {
            return;
        }
        // Selection rows are already rendered/sanitized. Their code delimiters are gone,
        // so applying the Markdown-source scrub here would delete literal code content.
        copyText(runner, state, text);
    }

    /**
     * The panes a selection may be confined to, most specific first, so a press
     * resolves to the visible thing the user actually clicked:
     * <ol>
     *     <li>the overlay dialogs and floating panels of the last frame, most
     *     recently drawn first (a toast outranks the modal it covers),</li>
     *     <li>the prompt input,</li>
     *     <li>the conversation body.</li>
     * </ol>
     * The selection engine clips to whichever pane holds the press and keeps it
     * for the whole drag, so overflowing past a dialog's bottom edge stops at
     * the dialog instead of copying the content drawn underneath it.
     */
    private static List<Rect> copyZones(final UiState state) {
        final var panes = state.paneZones;
        final var zones = new ArrayList<Rect>(panes.size() + 2);
        for (var i = panes.size() - 1; i >= 0; i--) {
            zones.add(panes.get(i));
        }
        zones.add(state.inputZone);
        zones.add(state.copyZone);
        return zones;
    }

    /**
     * Schedules a clipboard write for the given text (used by selection, code and error
     * copies): system-level channels first ({@link Clipboard} ladder), then OSC 52 which
     * still covers ssh/tmux terminals. The toast reports which channel actually won.
     */
    private void copyText(final TuiRunner runner, final UiState state, final String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        final var via = Clipboard.copy(text, null);
        final var payload = SelectionState.osc52Payload(text);
        final var copied = text.length();
        final var notificationCopy = state.notificationDetail != null;
        runLater(state, () -> {
            try {
                runner.backend().writeRaw(payload);
                final var channel = via == null ? " (OSC 52)" : " via " + via;
                state.pushToast("copied " + copied + " characters" + channel, ToastLevel.SUCCESS);
                if (!notificationCopy) {
                    notify(state, "copied " + copied + " characters" + channel);
                }
            } catch (final IOException e) {
                state.pushToast("clipboard copy failed: " + e.getMessage(), ToastLevel.ERROR);
                if (!notificationCopy) {
                    notify(state, "clipboard copy failed: " + e.getMessage());
                }
            }
        });
    }

    private void copyLastCodeBlock(final TuiRunner runner, final UiState state) {
        final var code = state.lastCodeBlock;
        if (code == null || code.isBlank()) {
            notify(state, "no code block to copy yet");
            return;
        }
        copyText(runner, state, code);
    }

    private void submit(final String text, final TuiRunner runner, final UiState state, final boolean pinToBottom) {
        acknowledgeInteraction(state);
        dismissCompletion(state);
        state.inputRoutingPending = true;
        final var routing = ShellInputRouting.resolve(text, slashRegistry, io(runner, state))
                .toCompletableFuture();
        // Ordinary input and known commands already resolve on the UI thread. Do not defer
        // their dispatch behind subsequent key events (which can start new completion lookups).
        final var resolvedOnUiThread = routing.isDone();
        routing.whenComplete((decision, error) -> {
            final Runnable apply = () -> {
                state.inputRoutingPending = false;
                if (error != null) {
                    state.input.setText(text);
                    positionCursorAtEnd(state);
                    state.initialPrompts.clear();
                    notify(state, "input selection failed - please submit again");
                    return;
                }
                switch (decision) {
                    case COMMAND -> {
                        clearError(state);
                        slash(text, runner, state);
                    }
                    case PROMPT -> {
                        clearError(state);
                        promptHistory.add(text);
                        submitPrompt(text, state, pinToBottom);
                    }
                    case CORRECT, CANCEL -> {
                        state.input.setText(text);
                        positionCursorAtEnd(state);
                        // A startup correction must not release subsequent scripted entries.
                        state.initialPrompts.clear();
                        if (decision == ShellInputRouting.Decision.CORRECT) {
                            notify(state, "fix the command and submit again (see /help for commands)");
                        }
                    }
                }
            };
            if (resolvedOnUiThread) {
                apply.run();
            } else {
                runLater(state, apply);
            }
        });
    }

    private void acknowledgeInteraction(final UiState state) {
        if (notifications != null) {
            notifications.onSessionInteraction(state.sessionId);
        }
    }

    /** Already accepted input: queued prompts must never go through slash classification again. */
    private void submitPrompt(final String text, final UiState state, final boolean pinToBottom) {
        if (state.model.busy()) {
            state.pendingPrompts.addLast(text);
            final var n = state.pendingPrompts.size();
            notify(
                    state,
                    "queued prompt (" + n + " pending) - it will be picked up as soon as the agent hits a checkpoint");
            state.input.clear();
            return;
        }
        if (agentSessionService.isResumeAlias(text)) {
            startResumeTurn(state);
            return;
        }
        if (state.title == null || state.title.isBlank()) {
            state.title = TuiInputSupport.firstLine(text);
        }
        state.model.startUserTurn(text, pinToBottom);
        state.interrupted = false;
        waiting(state, BusyLabels.thinking(0), SpinnerStyle.CLOCK);
        final var override = agentSessionService.agentOverride(text);
        final var agentName = override.agentName() != null ? override.agentName() : state.activeAgent;
        final var prompt = override.prompt();
        startTurn(state, agentName, prompt);
    }

    /**
     * Resume turn: re-runs the agent loop on the persisted history without sending any message
     * (zero extra prompt tokens), e.g. to continue after a truncated reply or a cancel.
     */
    private void startResumeTurn(final UiState state) {
        notify(state, "resuming previous turn");
        state.interrupted = false;
        waiting(state, BusyLabels.thinking(0), SpinnerStyle.CLOCK);
        startTurn(state, state.activeAgent, null);
    }

    private void startTurn(final UiState state, final String agentName, final String prompt) {
        state.turnCompleted = false;
        state.inFlight = turnRunner
                .submit(new TurnRunner.TurnRequest(
                        state.session.runtime().loop(),
                        env(state.runner, state),
                        state.sessionId,
                        agentName,
                        prompt,
                        () -> sessionStore.load(state.sessionId),
                        () -> state.interrupted,
                        () -> state.pendingPrompts.pollFirst()))
                .toCompletableFuture();
        state.inFlight.thenAccept(ran -> {
            if (ran == null || !ran) {
                state.wait = null;
                notify(state, "maven knowledge index still running - prompt not sent");
            }
        });
    }

    /**
     * Awaits an in-flight turn so it can finish flushing its session entries
     * before the UI quits. Returns quickly when nothing is running or the run
     * is already complete.
     */
    private static void awaitInFlight(final UiState state) {
        final var inFlight = state.inFlight;
        if (inFlight == null || inFlight.isDone()) {
            return;
        }
        state.interrupted = true;
        try {
            inFlight.get(1, TimeUnit.SECONDS);
        } catch (final Exception e) {
            // best-effort: interrupt is set and the turn unwinds on its own
        }
    }

    private void slash(final String text, final TuiRunner runner, final UiState state) {
        // Includes modal-only commands and shortcut-generated commands, before any early return.
        promptHistory.add(text);
        final var parts = SPLIT_COMMAND.split(text.substring(1), 2);
        if ("btw".equals(parts[0].toLowerCase(Locale.ROOT))) {
            btw(parts.length > 1 ? parts[1].strip() : "", runner, state);
            return;
        }
        if ("executing-tools".equals(parts[0].toLowerCase(Locale.ROOT))) {
            // read-only snapshot shown in its own modal overlay (only-modal, like /btw): never lands
            // in the transcript and never renders as a transient status toast
            openExecutingTools(state);
            return;
        }
        // the user prompt is transcribed as plain markdown (no backticks): wrapping it in
        // an inline-code span made slash commands look exactly like `code`; plain text (under
        // the You bar) stays visually distinct from code tokens
        state.model.appendTranscript("\n\n---\n\n" + state.model.userMarker() + "\n\n" + text + "\n");
        // the TUI keeps its modal-driven plan editor (Enter/Esc handling needs the widget stack)
        if ("editplan".equals(parts[0].toLowerCase(Locale.ROOT))) {
            editPlan(parts.length > 1 ? parts[1].strip() : "", runner, state);
            return;
        }
        executor.execute(() -> {
            try {
                final var outcome = slashRegistry
                        .dispatch(text, env(runner, state))
                        .toCompletableFuture()
                        .join();
                runLater(state, () -> apply(outcome, runner, state));
            } catch (final RuntimeException e) {
                final var cause = e.getCause() == null ? e : e.getCause();
                runLater(state, () -> modelError(state, "command failed: " + cause.getMessage()));
                // a failed queued command must not stall the following --initial-prompt entries
                runLater(state, () -> submitNextInitialPrompt(state));
            }
        });
    }

    private void btw(final String question, final TuiRunner runner, final UiState state) {
        if (question.isBlank()) {
            notify(state, "usage: /btw <question>");
            return;
        }
        // the typed /btw path refuses while one is already open; the Retry key re-asks in place instead.
        if (state.btw != null) {
            notify(state, "a btw answer is already open - close it first (Esc)");
            return;
        }
        askBtw(question, runner, state);
    }

    /** (Re-)asks a /btw question, resetting the single-slot overlay to a fresh running state. */
    private void askBtw(final String question, final TuiRunner runner, final UiState state) {
        final var ordinal = state.btwOrdinal.incrementAndGet();
        final var cancelled = new AtomicBoolean(false);
        state.btw = new UiState.BtwOverlay(ordinal, question, "", null, true, false, 0, cancelled);
        final var sessionId = state.session.sessionId();
        final var runtime = state.session.runtime();
        btwAnswerer
                .ask(
                        question,
                        sessionId == null ? "" : sessionId,
                        runtime.usage(),
                        () -> cancelled.get() || state.session.runtime() != runtime,
                        btwGate(runner, state, ordinal),
                        runtime.chatSettings(),
                        runtime.systemPromptBuilder(),
                        btwFeedback(runner, state, ordinal),
                        runtime.workdir(),
                        runtime.loop().effectiveToolRegistry(),
                        runtime.loop().availableToolNames(),
                        runtime.sandbox())
                .whenComplete((answer, error) ->
                        runLater(state, () -> onBtwAnswer(state, ordinal, answer, error, cancelled)));
    }

    private void onBtwAnswer(
            final UiState state,
            final long ordinal,
            final BtwAnswerer.BtwAnswer answer,
            final Throwable error,
            final AtomicBoolean cancelled) {
        if (state.btw == null || state.btw.ordinal() != ordinal) {
            return;
        }
        if (cancelled.get()) {
            state.btw = null;
            return;
        }
        final var question = state.btw.question();
        if (error != null) {
            final var cause = error.getCause() == null ? error : error.getCause();
            final var message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            state.btw = new UiState.BtwOverlay(ordinal, question, "", message, false, false, 0, cancelled);
            return;
        }
        final var answerText =
                answer == null || answer.text() == null || answer.text().isBlank() ? "(no answer)" : answer.text();
        state.btw = new UiState.BtwOverlay(
                ordinal, question, answerText, null, false, answer != null && answer.retryable(), 0, cancelled);
    }

    private void closeBtw(final UiState state) {
        final var btw = state.btw;
        state.btw = null;
        if (btw != null) {
            btw.cancelled().set(true);
            state.feedbackQueue.removeIf(prompt -> {
                if (prompt.btwOrdinal == btw.ordinal()) {
                    prompt.latch.countDown();
                    return true;
                }
                return false;
            });
            if (state.feedbackPrompt != null && state.feedbackPrompt.btwOrdinal == btw.ordinal()) {
                cancelFeedback(state);
            }
            // deny any approval the btw loop is still waiting on so its virtual thread
            // wakes up instead of blocking forever; approvals from the main turn are kept.
            final var it = state.approvals.iterator();
            while (it.hasNext()) {
                final var approval = it.next();
                if (approval.btwOrdinal() == btw.ordinal()) {
                    approval.deny();
                    it.remove();
                }
            }
        }
    }

    private UserFeedbackService btwFeedback(final TuiRunner runner, final UiState state, final long ordinal) {
        final var service = new UserFeedbackService();
        service.setGate(feedbackGate(runner, state, ordinal));
        return service;
    }

    private boolean onBtwKey(final KeyEvent key, final TuiRunner runner, final UiState state) {
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE) || key.isConfirm() || key.isChar('q')) {
            closeBtw(state);
            return true;
        }
        // a settled, retryable btw failure: re-ask the same question in place.
        if ((key.isChar('r') || key.isCharIgnoreCase('R'))
                && state.btw != null
                && !state.btw.running()
                && state.btw.retryable()) {
            askBtw(state.btw.question(), runner, state);
            return true;
        }
        final var delta = scrollService.keyDelta(key);
        if (delta != 0) {
            state.btw = withBtwScroll(state.btw, delta);
        }
        return true;
    }

    private UiState.BtwOverlay withBtwScroll(final UiState.BtwOverlay btw, final int delta) {
        return new UiState.BtwOverlay(
                btw.ordinal(),
                btw.question(),
                btw.answer(),
                btw.error(),
                btw.running(),
                btw.retryable(),
                scrollService.apply(btw.scroll(), delta),
                btw.cancelled());
    }

    /** Opens the /executing-tools modal overlay from the current running-tools snapshot. */
    private void openExecutingTools(final UiState state) {
        final var running = state.model.runningToolsSnapshot();
        state.executingTools = new UiState.ExecutingToolsOverlay(executingToolsMarkdown(running), 0);
    }

    private static String executingToolsMarkdown(final List<ConversationTranscript.RunningToolInfo> running) {
        if (running.isEmpty()) {
            return "(no tool executing)";
        }
        final var rows = running.stream()
                .map(tool -> {
                    final var command =
                            tool.command() == null || tool.command().isBlank() ? tool.name() : tool.command();
                    final var label = tool.ordinal() > 0 ? "#" + tool.ordinal() : "";
                    return List.of(
                            SlashRegistry.cell(tool.name()), SlashRegistry.cell(command), SlashRegistry.cell(label));
                })
                .toList();
        return SlashRegistry.table(List.of("tool", "command", "ordinal"), rows);
    }

    private void closeExecutingTools(final UiState state) {
        state.executingTools = null;
    }

    private void onExecutingToolsKey(final KeyEvent key, final UiState state) {
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE) || key.isConfirm() || key.isChar('q')) {
            closeExecutingTools(state);
            return;
        }
        final var delta = scrollService.keyDelta(key);
        if (delta != 0) {
            state.executingTools = new UiState.ExecutingToolsOverlay(
                    state.executingTools.content(), scrollService.apply(state.executingTools.scroll(), delta));
        }
    }

    private void apply(final ShellOutcome outcome, final TuiRunner runner, final UiState state) {
        switch (outcome) {
            case ShellOutcome.None none -> {}
            case ShellOutcome.Status(String message) -> notify(state, message);
            case ShellOutcome.Transcript(String markdown) -> state.model.appendTranscript(markdown);
            case ShellOutcome.Error(String message) -> modelError(state, message);
            case ShellOutcome.Quit quit -> {
                awaitInFlight(state);
                runner.quit();
            }
        }
        syncSession(state);
        // The next input can be typed while a configuration command is still running.
        // Refresh against its settled settings even if the old-provider lookup was empty.
        if (!(outcome instanceof ShellOutcome.Quit) && active() == state) {
            recomputePendingCompletion();
        }
        // a queued initial prompt may be a slash command: unlike a turn it never emits TurnDone,
        // so the next entry is released here once the command outcome has been applied
        if (!(outcome instanceof ShellOutcome.Quit)
                && !state.initialPrompts.isEmpty()
                && state.runner != null
                && !state.model.busy()) {
            runLater(state, () -> submitNextInitialPrompt(state));
        }
    }

    private void syncSession(final UiState state) {
        if (state.session.sessionId() != null && !state.session.sessionId().isBlank()) {
            final var switched = !state.session.sessionId().equals(state.sessionId);
            state.sessionId = state.session.sessionId();
            if (switched) {
                state.toolOutputs.clear();
                state.toolOverlay = null;
                state.pendingToolArgs.clear();
                state.pendingToolOrdinals.clear();
                state.collapsedTools.clear();
                state.runCollapsed.clear();
                clearRenderCaches();
                // /resume (or any other session switch) replaced the session: the per-session
                // world is keyed by session id so it has to be rebuilt
                rebind(state);
            }
        }
        if (state.session.activeAgent() != null) {
            state.activeAgent = state.session.activeAgent();
        }
    }

    private ShellEnv env(final TuiRunner runner, final UiState state) {
        refreshLabels(state);
        return new ShellEnv(frontend(runner, state), io(runner, state), state.session, state.model);
    }

    /**
     * Re-renders the speaker labels from {@code yca.labels} into the current transcript,
     * using the effective model (per-session override included), provider, active agent,
     * mode and session id. Called at every turn boundary (tab creation, dispatch) so a
     * model/provider/agent switch is picked up by the next written block; already-written
     * blocks keep the label they were written with.
     */
    private void refreshLabels(final UiState state) {
        final var settings = state.session.chatSettings();
        final var mode = state.session.runtime() == null
                ? null
                : String.valueOf(state.session.runtime().permissions().mode());
        final var ctx = new SpeakerLabels.Ctx(
                chatClient == null ? null : chatClient.model(settings),
                chatClient == null ? null : chatClient.providerId(settings),
                state.activeAgent,
                mode,
                state.sessionId,
                null);
        state.model.setLabels(speakerLabels.userLabel(ctx), speakerLabels.assistantLabel(ctx));
        state.model.setFeedbackLabelResolver((identity, user, at) -> {
            final var captured = identity == null
                    ? ctx.at(at)
                    : new SpeakerLabels.Ctx(identity.model(), identity.provider(), identity.agent(), null, null, null)
                            .at(at);
            return user ? speakerLabels.userLabel(captured) : speakerLabels.assistantLabel(captured);
        });
        state.model.setLabelResolver((identity, user) -> {
            final var ictx =
                    new SpeakerLabels.Ctx(identity.model(), identity.provider(), identity.agent(), null, null, null);
            return user ? speakerLabels.userLabel(ictx) : speakerLabels.assistantLabel(ictx);
        });
    }

    private ShellFrontend frontend(final TuiRunner runner, final UiState state) {
        return new ShellFrontend() {
            @Override
            public void onEvent(final AgentEvent event) {
                runLater(state, () -> TuiCommand.this.onEvent(event, state));
            }

            @Override
            public void status(final String message) {
                runLater(state, () -> TuiCommand.notify(state, message));
            }

            @Override
            public void notice(final AgentEvent.Notice.Level level, final String message) {
                runLater(state, () -> TuiCommand.notify(state, message));
            }

            @Override
            public void transcript(final String markdown) {
                runLater(state, () -> state.model.appendTranscript(markdown));
            }

            @Override
            public void openExternalUrl(final String url) {
                // remember the URL so Alt+O can re-open it even if the auto-open was dismissed
                state.lastExternalUrl = url;
                // open the system browser from the IO thread (fast, best-effort); the user-editable
                // toggle lives on the TUI command options (tui.autoOpenBrowser)
                if (conf.autoOpenBrowser()) {
                    OpenBrowser.open(url);
                }
            }

            @Override
            public void error(final String message) {
                runLater(state, () -> modelError(state, message));
            }

            @Override
            public void showThinking(final boolean value) {
                runLater(state, () -> state.showThinking = value);
            }

            @Override
            public void diffViewMode(final DiffViewMode value) {
                runLater(state, () -> state.diffViewMode = value);
            }

            @Override
            public void showToolOutput(final ToolOutputVisibility value) {
                runLater(state, () -> state.showToolActions = value);
            }

            @Override
            public void clear() {
                runLater(state, () -> {
                    clearRenderCaches();
                    state.toolOutputs.clear();
                    state.toolOverlay = null;
                });
            }

            @Override
            public void onReplay(final String markdown) {
                runLater(state, () -> {
                    clearRenderCaches();
                    state.toolOutputs.clear();
                    state.toolOverlay = null;
                });
            }
        };
    }

    private ShellIO io(final TuiRunner runner, final UiState state) {
        return new ShellIO() {
            @Override
            public CompletionStage<Optional<String>> readLine(final String prompt) {
                focus(state, runner);
                final var future = new CompletableFuture<Optional<String>>();
                state.pendingLine = future;
                return future;
            }

            @Override
            public CompletionStage<Boolean> confirm(final String question) {
                focus(state, runner);
                final var answers = state.session
                        .runtime()
                        .feedback()
                        .ask(List.of(new Question("confirm", question, List.of("Yes", "No"), false)));
                return CompletableFuture.completedStage(
                        answers != null && "Yes".equalsIgnoreCase(String.valueOf(answers.get("confirm"))));
            }

            @Override
            public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
                focus(state, runner);
                final var future = new CompletableFuture<Optional<String>>();
                runLater(
                        state,
                        () -> openPicker(
                                state,
                                title,
                                options,
                                0,
                                value -> future.complete(Optional.of(value)),
                                () -> future.complete(Optional.empty())));
                return future;
            }

            @Override
            public CompletionStage<Optional<String>> pick(
                    final String title, final List<String> options, final int pageSize) {
                focus(state, runner);
                final var future = new CompletableFuture<Optional<String>>();
                runLater(
                        state,
                        () -> openPicker(
                                state,
                                title,
                                options,
                                pageSize,
                                value -> future.complete(Optional.of(value)),
                                () -> future.complete(Optional.empty())));
                return future;
            }

            @Override
            public ApprovalGate approvalGate() {
                focus(state, runner);
                return gate(runner, state);
            }

            @Override
            public FeedbackGate feedbackGate() {
                focus(state, runner);
                return TuiCommand.this.feedbackGate(runner, state);
            }
        };
    }

    /**
     * Brings the tab owning an interaction (input line, picker, approval, question) to the
     * front: such requests can come from a background tab's turn and the user has to see
     * the prompt to answer it.
     */
    private void focus(final UiState state, final TuiRunner runner) {
        final var index = tabManager.indexOf(state);
        if (index >= 0 && index != tabManager.activeTab()) {
            runLater(state, () -> activate(index));
        }
    }

    private ShellOutcome themeOutcome(final String arg, final UiState state) {
        final var current = isPaletteLight() ? "light" : "dark";
        if (arg == null || arg.isBlank()) {
            notify(state, "theme: " + current + " (/theme light|dark to switch)");
            return ShellOutcome.none();
        }
        final var next = arg.strip().toLowerCase(Locale.ROOT);
        if (!"light".equals(next) && !"dark".equals(next)) {
            notify(state, "usage: /theme light|dark (current: " + current + ")");
            return ShellOutcome.none();
        }
        applyTheme(next);
        persistTheme(next);
        notify(state, "theme: " + next);
        return ShellOutcome.none();
    }

    private boolean isPaletteLight() {
        return "light".equals(theme);
    }

    private void applyCompletion(final UiState state) {
        final var suggestion = state.completion;
        if (suggestion == null || suggestion.empty()) {
            return;
        }
        final var items = suggestion.items();
        // the *highlighted* candidate: applyCompletion never advances the highlight -
        // that is Up/Down's job (cycleCompletion). Tab repeatedly applies the same row
        // (idempotent), so the applied text always matches what is highlighted.
        final var choice = items.get(state.completionIndex % items.size());
        replaceInput(state, suggestion, choice);
        state.completionIndex = state.completionIndex % items.size(); // keep the applied row selected
    }

    private void replaceInput(
            final UiState state, final CompletionEngine.Suggestion suggestion, final String replacement) {
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var start = suggestion.replaceStart();
        final var end = suggestion.replaceEnd();
        // a degenerate span (start > end, or out of the current line) can only
        // duplicate text on re-application (e.g. a stale suggestion applied after
        // the input moved on): refuse to mutate the line instead of producing
        // "yoyolo"-style duplicates. start == end is legitimate: the current segment
        // is empty (caret at a trailing space), so the candidate is a pure insertion.
        if (start < 0 || end > line.length() || start > end) {
            return;
        }
        final var newValue = line.substring(0, start) + replacement + line.substring(end);
        setInputLine(state, row, newValue);
        // position the cursor just after the inserted replacement range
        applyCursor(state, row, start + replacement.length());
        state.completionCursor = state.input.cursorCol();
    }

    /**
     * Whether applying the currently highlighted completion candidate would actually change the
     * input line. A no-op completion (the candidate is textually identical to the span it would
     * replace, e.g. a fully typed command like {@code /editplan}) should not hijack Enter: it has
     * nothing to complete, so Enter must fall through to the normal submit path.
     */
    private boolean completionWouldChangeInput(final UiState state) {
        final var suggestion = state.completion;
        if (suggestion == null || suggestion.empty()) {
            return false;
        }
        final var items = suggestion.items();
        if (items.isEmpty()) {
            return false;
        }
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var start = suggestion.replaceStart();
        final var end = suggestion.replaceEnd();
        if (start < 0 || end > line.length() || start > end) {
            // a degenerate span would be refused by replaceInput too; nothing to apply
            return false;
        }
        final var choice = items.get(state.completionIndex % items.size());
        return !(start == 0 && end == line.length() && choice.equals(line))
                && !line.substring(0, start)
                        .concat(choice)
                        .concat(line.substring(end))
                        .equals(line);
    }

    private void cycleCompletion(final UiState state, final boolean forward) {
        final var suggestion = state.completion;
        if (suggestion == null || suggestion.empty()) {
            return;
        }
        final var size = suggestion.items().size();
        if (forward) {
            state.completionIndex = (state.completionIndex + 1) % size;
        } else {
            state.completionIndex = (state.completionIndex + size - 1) % size;
        }
        state.completionNavigated = true;
        // the popup highlight is not an animated region: mark the tab dirty so the next
        // tick repaints the moved selection even when no other state changed
        state.dirty = true;
    }

    private void dismissCompletion(final UiState state) {
        // invalidate any in-flight live-completion result so a late one cannot resurrect the popup
        // after an explicit dismissal (Esc, Enter accepting a candidate, Tab, a cursor move)
        ++completionGeneration;
        state.completionDismissed = true;
        state.completion = null;
        state.completionCursor = -1;
        state.completionIndex = 0;
        state.completionNavigated = false;
        state.completionRect = null;
    }

    /**
     * Tab on the prompt line: with a candidate popup open, applies the *highlighted*
     * candidate then dismisses the popup, so a further keystroke (or a second Tab)
     * recomputes the suggestion against the new line instead of re-applying the
     * previous candidate over a stale span (that would duplicate text: Tab on
     * "/permissions yo" -> "/permissions yolo", a second Tab re-applying the old
     * "[13,15)" span would produce "/permissions yoyolo"). Without a popup yet (live
     * completion resolving asynchronously), computes the suggestion synchronously,
     * applies the first candidate and dismisses the popup too - a single Tab never
     * leaves a stale suggestion open. The applied text always matches the highlighted
     * row, and the highlight never advances by itself.
     */
    private void tabCompletion(final UiState state) {
        if (state.completion != null && !state.completion.empty()) {
            applyCompletion(state);
            dismissCompletion(state);
            state.dirty = true;
            return;
        }
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var cursor = state.input.cursorCol();
        final var suggestion = completionEngine.suggest(line, cursor);
        if (suggestion.empty()) {
            dismissCompletion(state);
            return;
        }
        state.completion = suggestion;
        state.completionCursor = cursor;
        state.completionIndex = 0;
        state.completionNavigated = false;
        applyCompletion(state);
        dismissCompletion(state);
        state.dirty = true;
    }

    /**
     * Live completion keeps the popup in sync with what is typed, so slash
     * commands and their parameters offer candidates without explicitly
     * pressing Tab. The popup opens for "/" prefixes and "@agent" tokens.
     * The suggestion lookup (which may resolve the model list over the
     * network) runs on the shared executor so it never blocks the UI/render
     * thread; results are applied only when still relevant (generation +
     * input unchanged) to avoid stale popups while typing fast.
     */
    private void liveComplete(final UiState state) {
        final var line = state.input.getLine(state.input.cursorRow());
        final var cursor = state.input.cursorCol();
        final var gen = ++completionGeneration;
        // a fresh edit re-arms the popup after an explicit dismissal (the warm-up reopen checks
        // the flag before calling this method, so it can never re-arm itself this way)
        state.completionDismissed = false;
        if (!line.startsWith("/") && !tokenAt(line, cursor).startsWith("@")) {
            dismissCompletion(state);
            return;
        }
        final var reqLine = line;
        final var reqCursor = cursor;
        executor.execute(() -> {
            final var suggestion = completionEngine.suggest(reqLine, reqCursor);
            applyCompletionResult(state, gen, reqLine, reqCursor, suggestion);
        });
    }

    /**
     * Applies a live-completion result on the render thread so the popup is painted on the next
     * tick even when no other event would repaint it (a bare mutation from the executor thread
     * had to wait for an unrelated render, which made the popup flush appear and disappear
     * non-deterministically). The relevance guards run at apply time so a dismissal that raced
     * with the computation still wins.
     */
    private void applyCompletionResult(
            final UiState state,
            final long gen,
            final String reqLine,
            final int reqCursor,
            final CompletionEngine.Suggestion suggestion) {
        final Runnable mutation = () -> {
            if (gen != completionGeneration || !completionInputMatches(state, reqLine, reqCursor)) {
                return;
            }
            if (suggestion.empty()) {
                state.completion = null;
                state.completionCursor = -1;
                state.completionRect = null;
                return;
            }
            // keep the highlight when the user typed a char that did not change the
            // candidate set (e.g. continuing to type the same token): resetting the
            // index on every keystroke would discard an explicit Up/Down selection.
            final var previous = state.completion;
            final var sameList = previous != null
                    && previous.items().equals(suggestion.items())
                    && previous.replaceStart() == suggestion.replaceStart()
                    && previous.replaceEnd() == suggestion.replaceEnd();
            state.completion = suggestion;
            state.completionCursor = reqCursor;
            if (!sameList) {
                state.completionIndex = 0;
                state.completionNavigated = false;
            }
        };
        if (state.runner == null) {
            mutation.run();
        } else {
            runLater(state, mutation);
        }
    }

    /**
     * Whether the current input still equals the completion request that was issued, so a
     * result computed on the executor thread can be safely applied to the live UI state.
     */
    private boolean completionInputMatches(final UiState state, final String line, final int cursor) {
        return line.equals(state.input.getLine(state.input.cursorRow())) && cursor == state.input.cursorCol();
    }

    /** @return the whitespace-delimited token the cursor sits in. */
    private static String tokenAt(final String line, final int cursor) {
        final var end = Math.min(cursor, line.length());
        var start = end;
        while (start > 0 && !Character.isWhitespace(line.charAt(start - 1))) {
            start--;
        }
        return line.substring(start, end);
    }

    /** Asynchronously reloads the completion value cache so render/onKey never does network or IO. */
    /**
     * Resolves the provider id of the active tab (or the process default when there is no
     * active tab yet). Every provider-scoped completion slice (models, variants) is keyed by
     * this value, so each tab's provider gets its own lazily-fetched list.
     */
    private String providerIdOf(final UiState state) {
        return chatClient == null
                ? ""
                : chatClient.providerId(state == null || state.session == null ? null : state.session.chatSettings());
    }

    /** Lazily loads the tools slice (application-scoped) on first need. */
    private void ensureToolsLoaded() {
        if (mcpManager == null || !completionCache.beginToolsLoad()) {
            return;
        }
        executor.execute(() -> {
            try {
                final var byServer = mcpManager.toolsByServer();
                if (byServer == null || byServer.isEmpty()) {
                    completionCache.tools(List.of());
                } else {
                    completionCache.tools(byServer.values().stream()
                            .flatMap(List::stream)
                            .map(EmbeddedTool::name)
                            .distinct()
                            .sorted()
                            .toList());
                }
            } catch (final RuntimeException e) {
                LOG.log(Level.SEVERE, "tools completion source failed; keeping previous cache", e);
            } finally {
                completionCache.endToolsLoad();
            }
        });
    }

    /** Lazily loads the MCP servers slice (application-scoped) on first need. */
    private void ensureServersLoaded() {
        if (mcpManager == null || !completionCache.beginServersLoad()) {
            return;
        }
        executor.execute(() -> {
            try {
                completionCache.servers(mcpManager.serverStatuses().stream()
                        .map(status -> status.name())
                        .toList());
            } catch (final RuntimeException e) {
                LOG.log(Level.SEVERE, "mcp servers completion source failed; keeping previous cache", e);
            } finally {
                completionCache.endServersLoad();
            }
        });
    }

    /** Lazily loads the sessions slice (application-scoped) on first need. */
    private void ensureSessionsLoaded() {
        if (sessionStore == null || !completionCache.beginSessionsLoad()) {
            return;
        }
        executor.execute(() -> {
            try {
                // most recent first regardless of the store implementation
                final var sessions = sessionStore.list().stream()
                        .sorted(Comparator.comparingLong(SessionStore.SessionInfo::modifiedAt)
                                .reversed())
                        .toList();
                completionCache.sessions(
                        sessions.stream().map(SessionStore.SessionInfo::id).toList());
            } catch (final RuntimeException e) {
                LOG.log(Level.SEVERE, "sessions completion source failed; keeping previous cache", e);
            } finally {
                completionCache.endSessionsLoad();
            }
        });
    }

    /** Lazily loads the models slice of the active tab's provider on first need. */
    private void ensureModelsLoaded() {
        final var activeState = active();
        final var providerId = providerIdOf(activeState);
        if (chatClient == null || !completionCache.beginModelsLoad(providerId)) {
            return;
        }
        executor.execute(() -> {
            try {
                final var settings =
                        activeState == null || activeState.session == null ? null : activeState.session.chatSettings();
                final var ids = chatClient.models(settings).ids();
                completionCache.models(providerId, ids == null ? List.of() : List.copyOf(ids));
            } catch (final Exception e) {
                LOG.log(Level.SEVERE, "model list completion source failed; keeping previous cache", e);
            } finally {
                completionCache.endModelsLoad(providerId);
            }
        });
    }

    /** Lazily loads the variants slice for one model against the active tab's provider on first need. */
    private void ensureVariantsLoaded(final String model) {
        final var activeState = active();
        final var providerId = providerIdOf(activeState);
        if (chatClient == null || !completionCache.beginVariantsLoad(providerId, model)) {
            return;
        }
        executor.execute(() -> {
            try {
                final var settings =
                        activeState == null || activeState.session == null ? null : activeState.session.chatSettings();
                final var selected = new ArrayList<String>();
                if (chatClient.implicitVariant(settings) != null) {
                    selected.add("default");
                }
                final var raw = chatClient.variants(settings, model);
                if (raw != null) {
                    for (final var v : raw) {
                        selected.add(v.name());
                    }
                }
                completionCache.variants(providerId, model, selected);
            } catch (final Exception e) {
                LOG.log(Level.SEVERE, "variants completion source failed for model " + model, e);
            } finally {
                completionCache.endVariantsLoad(providerId, model);
            }
        });
    }

    /**
     * Recompute after a lazy completion slice lands, including when an old popup is still
     * visible and an empty result is waiting to be applied. Queue the refresh on the render
     * thread so it invalidates those in-flight results. Explicit dismissal still wins, and
     * applyCompletionResult preserves the highlight when the candidate list is unchanged.
     */
    private void recomputePendingCompletion() {
        final var activeState = active();
        if (activeState == null) {
            return;
        }
        runLater(activeState, () -> {
            final var line = activeState.input.getLine(activeState.input.cursorRow());
            final var cursor = activeState.input.cursorCol();
            if (!activeState.completionDismissed
                    && (line.startsWith("/") || tokenAt(line, cursor).startsWith("@"))) {
                liveComplete(activeState);
            }
        });
    }

    private void moveCursorWord(final UiState state, final boolean forward) {
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var col = state.input.cursorCol();
        final var target = forward ? TuiInputSupport.nextWord(line, col) : TuiInputSupport.previousWord(line, col);
        applyCursor(state, row, target);
    }

    /** Moves the cursor to an absolute (row, col) cell of the multiline buffer. */
    private static void applyCursor(final UiState state, final int row, final int col) {
        state.input.moveCursorToStart();
        for (var i = 0; i < row; i++) {
            state.input.moveCursorDown();
        }
        for (var i = 0; i < col; i++) {
            state.input.moveCursorRight();
        }
    }

    /** Replaces the given row's content, leaving the other lines of the buffer untouched. */
    private void setInputLine(final UiState state, final int row, final String newLine) {
        final var lines = new ArrayList<String>(Math.max(1, state.input.lineCount()));
        for (var i = 0; i < state.input.lineCount(); i++) {
            lines.add(i == row ? newLine : state.input.getLine(i));
        }
        state.input.setText(String.join("\n", lines));
    }

    /**
     * Routes a wheel event to the scrollable view the user is actually looking at, walking the same
     * topmost-wins priority as {@link #onKey(Event, TuiRunner, UiState)}.
     * <p>
     * This has to happen before the overlay chain because the approval and "behind the scenes"
     * dialogs are dispatched after the {@code KeyEvent} guard of {@code onKey}: a wheel event over
     * them used to fall through to the main view and scroll the conversation hidden behind the modal.
     *
     * @return always {@code true}, the wheel is consumed even by overlays with nothing to scroll so
     *         it can never leak to the transcript while a dialog is up
     */
    /**
     * Mouse motion over the tab bar: shows the full tab title as a tooltip below
     * the strip, or clears it when the pointer leaves the bar. Only repaints when
     * the hovered tab actually changed (a MOVE elsewhere returns {@code false}),
     * so mouse motion never causes idle repaints by itself.
     */
    private boolean onTabHover(final MouseEvent move, final UiState state) {
        final var index = tabIndexAt(move.x(), move.y());
        final var hovered = index < 0 ? null : tabManager.get(index);
        final var next = hovered == null ? null : tabTitle(hovered);
        final var current = state.hoverTitle;
        if (Objects.equals(current, next)) {
            return false;
        }
        state.hoverTitle = next;
        return true;
    }

    /**
     * Mouse motion over the tool output modal footer: hovering the ellipsized raw-args
     * line surfaces the full args JSON as a floating chip ({@link UiState#hoverToolCmd}),
     * cleared when the pointer leaves the footer row. The rendered footer rect is
     * consulted so a MOVE outside the modal (or over the tab strip while the modal is
     * open) never toggles anything.
     */
    private boolean onToolFooterHover(final MouseEvent move, final UiState state) {
        final var footer = state.toolOverlayFooter;
        final var args =
                state.toolOverlay == null ? null : state.toolOverlay.entry().args();
        final var next =
                footer != null && args != null && !args.isBlank() && footer.contains(move.x(), move.y()) ? args : null;
        if (Objects.equals(state.hoverToolCmd, next)) {
            return false;
        }
        state.hoverToolCmd = next;
        return true;
    }

    /**
     * Mouse motion over a sidebar quota gauge: surfaces the reset time ("Resets in ...",
     * or "now") in a floating chip below the hovered gauge row, cleared when the pointer
     * leaves the bar. Only repaints when the hovered gauge (and thus the caption) changed.
     * Hit-testing uses the current-frame {@link UiState#quotaHoverAreas}, which are cleared
     * whenever the quota gauges are not drawn, so no tooltip appears over a hidden bar
     * (e.g. after switching to a provider that exposes no quota).
     */
    private boolean onQuotaHover(final MouseEvent move, final UiState state) {
        String next = null;
        Rect nextRect = null;
        for (final var area : state.quotaHoverAreas) {
            if (area.rect().contains(move.x(), move.y())) {
                next = formatQuotaReset(area.resetAtEpochSeconds());
                nextRect = area.rect();
                break;
            }
        }
        if (Objects.equals(state.hoverQuotaReset, next)) {
            return false;
        }
        state.hoverQuotaReset = next;
        state.hoverQuotaRect = nextRect;
        return true;
    }

    /**
     * Mouse motion over the sidebar context gauge: surfaces the estimated context size in a
     * floating chip below the gauge row, cleared when the pointer leaves the gauge. Only
     * repaints when the hover state changed. Hit-testing uses the current-frame
     * {@link UiState#contextGaugeRect}, which is cleared whenever the gauge is not drawn.
     */
    /**
     * Mouse motion over a tool card header strip in the transcript: records the hovered
     * card ordinal and its screen rect so the render thread can reverse-video the header
     * row and make the click target visible. Only repaints when the hovered card changed.
     * Hit-testing uses the current-frame {@link UiState#toolAnchors}, cleared whenever the
     * transcript does not show that card, so a hover never sticks to a scrolled-out card.
     */
    private boolean onToolCardHover(final MouseEvent move, final UiState state) {
        long next = -1L;
        for (final var anchor : state.toolAnchors) {
            if (anchor.ordinal() >= 0 && anchor.header().contains(move.x(), move.y())) {
                next = anchor.ordinal();
                break;
            }
        }
        if (state.hoverToolCard == next) {
            return false;
        }
        state.hoverToolCard = next;
        return true;
    }

    private boolean onContextHover(final MouseEvent move, final UiState state) {
        final var hovered = state.contextGaugeRect != null && state.contextGaugeRect.contains(move.x(), move.y());
        String next = null;
        if (hovered) {
            final var est = state.model.estimatedContext();
            final var pct = state.model.contextPercent();
            // surface "current / max": the usable window is recovered as the inverse of
            // contextPercent = 100 * current / usableWindow. Before any window is resolved
            // (pct <= 0) only the current estimate is shown, never a division by zero.
            if (pct > 0) {
                final var max = Math.round(100.0 * est / pct);
                next = "~" + formatTokens(est) + " / " + formatTokens(max) + " tok";
            } else {
                next = "~" + formatTokens(est) + " tok";
            }
            final var measurement = state.model.contextMeasurement();
            if (measurement != null) {
                next += " (" + measurement.source();
                if (measurement.lastInputTokens() > 0) {
                    next += "; last input " + formatTokens(measurement.lastInputTokens());
                }
                if (measurement.pendingTokens() > 0) {
                    next += "; pending ~" + formatTokens(measurement.pendingTokens());
                }
                next += ")";
            }
        }
        if (Objects.equals(state.hoverContextSize, next)) {
            return false;
        }
        state.hoverContextSize = next;
        return true;
    }

    /** Index of the tab whose bar cell contains the given coordinates, or {@code -1}. */
    private int tabIndexAt(final int x, final int y) {
        for (var i = 0; i < tabRects.size(); i++) {
            if (tabRects.get(i).contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    private boolean scrollTopOverlay(final MouseEvent wheel, final UiState state) {
        final var delta = scrollService.wheelDelta(wheel);
        if (delta == 0) {
            return false;
        }
        // scrolling anywhere dismisses the tab tooltip: the pointer is acting, not hovering
        if (state.hoverTitle != null) {
            state.hoverTitle = null;
        }
        state.hoverQuotaReset = null;
        state.hoverQuotaRect = null;
        state.hoverContextSize = null;
        state.hoverToolCard = -1L;
        if (state.toolOverlay != null) {
            scrollToolOverlay(state, delta);
        } else if (tabManager.isListOpen()) {
            tabManager.listScroll(scrollService.apply(tabManager.listScroll(), delta));
        } else if (state.shortcutsOpen) {
            state.shortcutsScroll = scrollService.apply(state.shortcutsScroll, delta);
        } else if (state.subagentsOverlay != null) {
            state.subagentsScroll = scrollService.apply(state.subagentsScroll, delta);
        } else if (state.searchOpen) {
            // single line search bar: nothing to scroll
        } else if (state.errorDetail != null && state.errorOpen) {
            state.errorScroll = scrollService.apply(state.errorScroll, delta);
        } else if (state.notificationDetail != null) {
            state.notificationScroll = scrollService.apply(state.notificationScroll, delta);
        } else if (state.editPlan != null) {
            // two buttons, no scrollable body
        } else if (state.feedbackPrompt != null) {
            final var prompt = state.feedbackPrompt;
            // when the question/summary overflows the capped modal, the wheel scrolls it (its
            // tail must stay reachable); otherwise it keeps the legacy behaviour of scrolling
            // through the option selection
            if (prompt.questionMaxScroll() > 0) {
                prompt.questionScroll(scrollService.apply(prompt.questionScroll(), delta));
            } else if (prompt.tab() < prompt.questions().size()) {
                moveOption(prompt, prompt.tab(), delta);
            }
        } else if (state.picker != null) {
            state.picker.move(delta);
        } else if (!state.approvals.isEmpty()) {
            final var approval = state.approvals.peek();
            approval.detailScroll(scrollService.apply(approval.detailScroll(), delta));
        } else if (state.btw != null) {
            state.btw = withBtwScroll(state.btw, delta);
        } else if (state.executingTools != null) {
            state.executingTools = new UiState.ExecutingToolsOverlay(
                    state.executingTools.content(), scrollService.apply(state.executingTools.scroll(), delta));
        } else if (state.inputZone != null
                && state.inputZone.contains(wheel.x(), wheel.y())
                && visualRows(state.input, state.inputZone.width()) > state.inputZone.height()) {
            // wheel over the prompt input scrolls the input itself when its content spills
            // past the (capped) input height, instead of falling through to the transcript
            inputScrollBy(state, delta, state.inputZone.width(), state.inputZone.height());
        } else {
            scrollTranscript(state, delta);
        }
        return true;
    }

    /** Scrolls the conversation, keeping the "user browsed" flag in sync so auto-follow re-arms at the bottom. */
    private void scrollTranscript(final UiState state, final int delta) {
        if (delta == 0) {
            return;
        }
        state.model.anchorRow(scrollService.clamp(state.model.anchorRow(), delta, state.maxViewOffset));
        state.model.userScrolled(state.model.anchorRow() < state.maxViewOffset);
    }

    /**
     * Up/Down on the prompt line: when a completion popup is active the arrows
     * cycle its matches; otherwise (idle, single logical line) they recall the
     * prompt history; in all other cases the cursor moves across lines.
     */
    private void handleLineOrHistory(final UiState state, final boolean up) {
        if (state.completion != null && !state.completion.empty()) {
            // the popup lists candidates top-down above the prompt: Down moves to the next
            // candidate, Up back to the previous one (the highlight only; Enter applies)
            cycleCompletion(state, !up);
            return;
        }
        if (state.model.busy()) {
            moveCursorVisual(state, up);
            return;
        }
        final var inner = state.inputZone;
        if (inner != null && inner.width() > 0) {
            // GUI-editor motion: vertical moves cross the soft-wrapped visual rows
            // of the current input (same display column, clamped), only falling
            // through to history recall at the very first/last visual row
            final var grid = visualGrid(state.input, inner.width());
            final var caretVisual = visualRowOfCaret(state.input, inner.width());
            if (up) {
                if (caretVisual > 0) {
                    moveCursorToVisual(state, grid, caretVisual - 1);
                    return;
                }
            } else if (caretVisual < grid.size() - 1) {
                moveCursorToVisual(state, grid, caretVisual + 1);
                return;
            }
        }
        final var lastLine = Math.max(0, state.input.lineCount() - 1);
        final var atBoundary = up ? state.input.cursorRow() == 0 : state.input.cursorRow() >= lastLine;
        if (!atBoundary) {
            // plain multiline editing: move the cursor across lines
            moveCursor(state, up);
            return;
        }
        if (!historyRecallable()) {
            moveCursor(state, up);
            return;
        }
        if (!state.historyActive) {
            state.historyActive = true;
            state.historyDraft = state.input.text();
        }
        final var recalled =
                up ? promptHistory.olderPush(state.historyDraft) : promptHistory.newerPull(state.historyDraft);
        if (recalled != null) {
            beforeEdit(state);
            state.input.setText(recalled);
            positionCursorAtEnd(state);
        }
        if (!up && !promptHistory.navigating()) {
            state.historyActive = false;
            state.historyDraft = "";
        }
    }

    private boolean historyRecallable() {
        return promptHistory.navigating() || promptHistory.size() > 0;
    }

    /**
     * Recalls prompt history regardless of cursor position (bound to Ctrl+R for
     * an older entry and Ctrl+Up/Ctrl+Down for older/newer).
     */
    private void recallHistory(final UiState state, final boolean older) {
        if (!historyRecallable()) {
            return;
        }
        if (!state.historyActive) {
            state.historyActive = true;
            state.historyDraft = state.input.text();
        }
        final var recalled =
                older ? promptHistory.olderPush(state.historyDraft) : promptHistory.newerPull(state.historyDraft);
        if (recalled != null) {
            beforeEdit(state);
            state.input.setText(recalled);
            positionCursorAtEnd(state);
        }
        if (!older && !promptHistory.navigating()) {
            state.historyActive = false;
            state.historyDraft = "";
        }
    }

    private void moveCursor(final UiState state, final boolean up) {
        inputArmFollow(state);
        if (up) {
            state.input.moveCursorUp();
        } else {
            state.input.moveCursorDown();
        }
    }

    /**
     * Busy-mode vertical motion: crosses soft-wrapped visual rows like a GUI
     * editor and does nothing at the input boundaries (no history recall while
     * the agent is streaming).
     */
    private void moveCursorVisual(final UiState state, final boolean up) {
        inputArmFollow(state);
        final var inner = state.inputZone;
        if (inner == null || inner.width() <= 0) {
            moveCursor(state, up);
            return;
        }
        final var grid = visualGrid(state.input, inner.width());
        final var caretVisual = visualRowOfCaret(state.input, inner.width());
        if (up) {
            if (caretVisual > 0) {
                moveCursorToVisual(state, grid, caretVisual - 1);
            }
        } else if (caretVisual < grid.size() - 1) {
            moveCursorToVisual(state, grid, caretVisual + 1);
        }
    }

    private void positionCursorAtEnd(final UiState state) {
        state.input.moveCursorToEnd();
    }

    /** Snapshots the input before a mutating edit (undo) and ends any yank sequence. */
    private void beforeEdit(final UiState state) {
        state.undoStack.push(state.input.text());
        state.yankArmed = false;
        state.lastYankLen = 0;
        inputArmFollow(state);
    }

    private void deleteWordBackward(final UiState state) {
        beforeEdit(state);
        final var row = state.input.cursorRow();
        final var col = state.input.cursorCol();
        final var line = state.input.getLine(row);
        final var start = TuiInputSupport.previousWord(line, col);
        if (start < col) {
            state.killRing.kill(line.substring(start, col));
            setInputLine(state, row, line.substring(0, start) + line.substring(col));
            applyCursor(state, row, start);
            return;
        }
        if (row == 0) {
            return;
        }
        final var prevLine = state.input.getLine(row - 1);
        final var prevEnd = prevLine.length();
        final var prevStart = TuiInputSupport.previousWord(prevLine, prevEnd);
        if (prevStart < prevEnd) {
            state.killRing.kill(prevLine.substring(prevStart, prevEnd));
            final var remaining = prevLine.substring(0, prevStart);
            if (remaining.isEmpty()) {
                setBufferWithoutRow(state, row - 1);
                applyCursor(state, row - 1, 0);
            } else {
                setInputLine(state, row - 1, remaining);
                applyCursor(state, row - 1, prevStart);
            }
        } else {
            setInputLine(state, row - 1, prevLine + line);
            applyCursor(state, row - 1, prevEnd);
        }
    }

    /** Removes the given row from the buffer, shifting the following lines up. */
    private void setBufferWithoutRow(final UiState state, final int dropRow) {
        final var lines = new ArrayList<String>(Math.max(1, state.input.lineCount() - 1));
        for (var i = 0; i < state.input.lineCount(); i++) {
            if (i != dropRow) {
                lines.add(state.input.getLine(i));
            }
        }
        state.input.setText(String.join("\n", lines));
    }

    private void killLineForward(final UiState state) {
        beforeEdit(state);
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var col = state.input.cursorCol();
        state.killRing.kill(line.substring(col));
        setInputLine(state, row, line.substring(0, col));
    }

    private void killLineBackward(final UiState state) {
        beforeEdit(state);
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var col = state.input.cursorCol();
        state.killRing.kill(line.substring(0, col));
        setInputLine(state, row, line.substring(col));
        applyCursor(state, row, 0);
    }

    /**
     * Yanks (re-inserts) the most recent kill; consecutive Alt+Y presses cycle
     * through the kill ring, replacing the previously yanked text.
     */
    private void yank(final UiState state) {
        final var text = state.yankArmed ? state.killRing.yankPop() : state.killRing.yank();
        if (text == null || text.isEmpty()) {
            return;
        }
        if (state.yankArmed) {
            for (var i = 0; i < state.lastYankLen; i++) {
                state.input.deleteBackward();
            }
        }
        state.input.insert(text);
        state.lastYankLen = text.length();
        state.yankArmed = true;
    }

    private void editPlan(final String arg, final TuiRunner runner, final UiState state) {
        if (state.model.busy()) {
            notify(state, "editplan is not available while busy");
            return;
        }
        final var title = state.title == null || state.title.isBlank() ? "plan" : state.title;
        final var export = agentSessionService.exportPlan(state.sessionId, arg, title, state.runtime());
        if (export.error() != null) {
            notify(state, export.error());
            return;
        }
        state.editPlan = new PendingEditPlan(export.path(), export.original(), null, null);
        notify(
                state,
                "plan exported to " + export.path()
                        + " - edit it, save, then press Enter to reload (Esc or click Cancel to abort)");
        state.model.appendTranscript("\n\n> plan exported to `"
                + export.path()
                + "` - edit it, save, then press Enter to reload, or Esc to abort\n");
    }

    private boolean onEditPlanKey(final KeyEvent key, final TuiRunner runner, final UiState state) {
        if (key.isConfirm()) {
            reloadPlan(runner, state);
        } else if (key.isCancel() || key.isKey(KeyCode.ESCAPE) || key.isKey(KeyCode.BACKSPACE)) {
            abortEditPlan(state);
        }
        return true;
    }

    private boolean onEditPlanMouse(final MouseEvent mouse, final UiState state) {
        if (!(mouse.isPress() || mouse.isClick()) || !mouse.isLeftButton()) {
            return true;
        }
        final var pending = state.editPlan;
        if (pending == null) {
            return true;
        }
        final var x = mouse.x();
        final var y = mouse.y();
        if (pending.confirmRect() != null && pending.confirmRect().contains(x, y)) {
            reloadPlan(null, state);
        } else if (pending.cancelRect() != null && pending.cancelRect().contains(x, y)) {
            abortEditPlan(state);
        }
        return true;
    }

    private void reloadPlan(final TuiRunner runner, final UiState state) {
        final var pending = state.editPlan;
        if (pending == null) {
            return;
        }
        final Path path = pending.path();
        final var reload =
                agentSessionService.reloadPlan(state.sessionId, path, pending.original(), state.session.runtime());
        if (reload.error() != null) {
            if (("plan file not found: " + path).equals(reload.error())) {
                notify(state, "plan file not found: " + path + " (still waiting - edit and press Enter)");
                return;
            }
            notify(state, reload.error());
            return;
        }
        if (reload.changed()) {
            state.editPlan = null;
        }
        notify(state, reload.message());
    }

    private void abortEditPlan(final UiState state) {
        state.editPlan = null;
        notify(state, "plan edit cancelled");
    }

    private void renderEditPlan(final Frame frame, final UiState state) {
        final var pending = state.editPlan;
        final var accent = Color.hex(palette.accent());
        final var warning = Color.hex(palette.warning());
        final var text = Color.hex(palette.text());
        final var muted = Color.hex(palette.textMuted());
        final var width = Math.min(Math.max(60, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(10, frame.height() / 3), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.25);
        final var inner = modal(frame, rect, " edit plan ", warning);
        if (inner == null) {
            return;
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.fill(), Constraint.length(1))
                .split(inner);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "Edit the file below in your preferred editor, save, then return here.",
                                Style.EMPTY.fg(text).bold()))))
                        .build(),
                splits.get(0));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                ellipsize(
                                        pending.path().toString(), splits.get(1).width()),
                                Style.EMPTY.fg(muted)))))
                        .build(),
                splits.get(1));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.raw(
                                        "Use `# Plan` for the plan text and `# Todos` for the todo list (one `- [ ]` / `- [>]` / `- [x]` item per line).")
                                .style(Style.EMPTY.fg(muted)))))
                        .build(),
                splits.get(2));
        final var footer = splits.get(3);
        final var per = Math.max(1, footer.width() / 2);
        final var confirmRect = new Rect(footer.x(), footer.y(), per, footer.height());
        final var cancelRect = new Rect(footer.x() + per, footer.y(), footer.width() - per, footer.height());
        pending.confirmRect(confirmRect);
        pending.cancelRect(cancelRect);
        final var confirmLine =
                new Rect(confirmRect.x() + 1, confirmRect.y(), confirmRect.width() - 2, confirmRect.height());
        final var cancelLine =
                new Rect(cancelRect.x() + 1, cancelRect.y(), cancelRect.width() - 2, cancelRect.height());
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "[ Confirm (Enter) ]", Style.EMPTY.fg(accent).bold()))))
                        .build(),
                confirmLine);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "[ Cancel (Esc) ]", Style.EMPTY.fg(text).bold()))))
                        .build(),
                cancelLine);
    }

    private void compact(final TuiRunner runner, final UiState state) {
        if (state.model.busy()) {
            // deferred: the loop applies the operation at the next safe checkpoint, at most one per turn
            final var queued = state.session.runtime().loop().requestUserContextOperation("compact", null, 0, false);
            notify(
                    state,
                    queued
                            ? "compaction scheduled - applied at the next agent checkpoint"
                            : "a context operation is already pending this turn");
            return;
        }
        waiting(state, BusyLabels.compacting(1, 1), SpinnerStyle.BOUNCING_BALL);
        executor.execute(() -> {
            try {
                final var result = agentSessionService.compact(state.sessionId, state.runtime());
                runLater(state, () -> {
                    notify(state, result.message());
                    if (result.applied()) {
                        if (result.error() != null) {
                            state.model.appendTranscript("\n\n> ⚠ " + result.message() + ": " + result.error() + "\n");
                        } else {
                            state.model.appendTranscript("\n\n> " + result.message() + "\n");
                        }
                    } else {
                        state.model.appendTranscript("\n\n> " + result.message() + "\n");
                    }
                });
            } catch (final RuntimeException e) {
                runLater(state, () -> modelError(state, "compaction failed: " + e.getMessage(), e));
            }
        });
    }

    private void render(final Frame frame, final UiState state) {
        if (state.toolOverlay == null) {
            if (toolOverlayLayout != null) {
                toolBodyCache.clear();
            }
            toolOverlayLayout = null;
        }
        state.lastRenderTick = clock.millis();
        state.dirty = false;
        frameZones.clear();
        if (screenBackground != null) {
            // paint the configured viewport background before any widget so blank screen areas
            // match the palette; widgets with their own background draw over it
            frame.buffer().fill(frame.area(), new Cell(" ", Style.EMPTY.bg(screenBackground)));
        }
        // a busy-but-inert turn (mid-round wait: no tool, no reasoning stream) still needs its
        // spinner to advance: the gate must not rely solely on runningTool/activeWait/thinkingLength,
        // which can all be cold between tool rounds or after a status divergence (see needsTickRender)
        if (state.model.busy()
                || state.model.runningTool() != null
                || activeWait(state) != null
                || state.model.thinkingLength() > 0) {
            state.spinnerState.advance();
        }
        // thinking timer lifecycle: armed on the first frame with live reasoning and
        // cleared as soon as it stops, independent of busy/runningTool so both the
        // in-pane indicator and the pinned footer read a correct elapsed time even
        // while a tool runs and across consecutive turns
        if (state.model.thinkingLength() > 0) {
            if (state.thinkingStartedAtMillis < 0) {
                state.thinkingStartedAtMillis = clock.millis();
            }
        } else {
            state.thinkingStartedAtMillis = -1;
        }
        state.model.trim();
        if (state.toast != null
                && tuiConfiguration.toastDurationMillis() > 0
                && clock.millis() - state.toastShownAt > tuiConfiguration.toastDurationMillis()) {
            state.clearToast();
        }
        final var todosRender = state.session.runtime().todos().render();
        if (!todosRender.equals(state.lastTodosRender)) {
            state.lastTodosRender = todosRender;
            if (!todosRender.isBlank() && !"(empty)".equals(todosRender) && !state.showTodos) {
                state.showTodos = true;
            }
        }
        // responsive: drop the sidebar on narrow terminals to give the chat room
        final var screenWidth = frame.width();
        final var narrow = screenWidth < 68;
        final var sidebarWidth = narrow ? 0 : (screenWidth < 95 ? 22 : 28);
        // the prompt grows with its content (multiline pastes) up to a sane maximum,
        // and queued prompts get a compact strip right above it
        final var inputWidth = Math.max(20, screenWidth - sidebarWidth - 2 - 2 - 2);
        final var inputRows = Math.min(8, 2 + visualRows(state.input, inputWidth));
        final var queueRows = Math.min(3, state.pendingPrompts.size());
        final var bottomConstraints = new ArrayList<Constraint>();
        bottomConstraints.add(Constraint.fill());
        if (queueRows > 0) {
            bottomConstraints.add(Constraint.length(queueRows));
        }
        bottomConstraints.add(Constraint.length(inputRows));
        bottomConstraints.add(Constraint.length(1));
        bottomConstraints.add(Constraint.length(statusStripRows(state)));
        // the tab strip is always present: with a single tab it carries the
        // "+" affordance; with several, the active one is highlighted and
        // background tabs keep a busy marker so a running turn stays visible
        final var rootConstraints = new ArrayList<Constraint>(bottomConstraints.size() + 1);
        rootConstraints.add(Constraint.length(1));
        rootConstraints.addAll(bottomConstraints);
        final var root = Layout.vertical()
                .constraints(rootConstraints.toArray(Constraint[]::new))
                .split(frame.area());
        renderTabBar(root.getFirst(), frame);
        final var body = Layout.horizontal()
                .constraints(Constraint.fill(), Constraint.length(sidebarWidth))
                .split(root.get(1));
        if (!narrow) {
            renderSidebar(body.get(1), frame, state);
            frameZones.add(body.get(1));
        }
        var chat = accentGutter(body.get(0), frame);
        if (state.showTodos) {
            final var withTodos = Layout.horizontal()
                    .constraints(Constraint.fill(), Constraint.length(state.showTodosCollapsed ? 14 : 34))
                    .split(chat);
            chat = withTodos.get(0);
            renderTodos(withTodos.get(1), frame, state, todosRender);
            frameZones.add(withTodos.get(1));
        }
        // thinking streams inline in the conversation panel (dimmed, thin left bar),
        // gated by showThinking, so no dedicated stacked panel is needed anymore
        renderTranscript(chat, frame, state);
        // the copy-safe chat zone covers the mode gutter (column 0) and the per-region
        // role bar (every line starts with the 2-cell "│ "/"┃ " bar, the pane wall is
        // gone) so ANY press on a chat-area column resolves to the chat pane instead of
        // falling back to whole-frame copy; the chrome columns themselves are excluded
        // by chatContentLeft/chatContentRight published with the frame.
        state.copyZone = new Rect(chat.x() - 1, chat.y(), Math.max(1, chat.width() + 1), Math.max(1, chat.height()));
        var bottomIndex = 2;
        if (queueRows > 0) {
            renderQueue(root.get(bottomIndex++), frame, state);
            frameZones.add(root.get(bottomIndex - 1));
        }
        renderInput(root.get(bottomIndex), frame, state);
        renderCompletionPopup(root.get(bottomIndex), frame, state);
        bottomIndex++;
        renderCalling(root.get(bottomIndex++), frame, state);
        frameZones.add(root.get(bottomIndex - 1));
        renderStatus(root.get(bottomIndex), frame, state, narrow);
        frameZones.add(root.get(bottomIndex));
        if (state.hoverTitle != null && !state.hoverTitle.isBlank()) {
            renderTabTooltip(frame, state);
        }
        if (state.hoverQuotaReset != null && !state.hoverQuotaReset.isBlank()) {
            renderQuotaTooltip(frame, state);
        }
        renderContextTooltip(frame, state);
        if (state.searchOpen) {
            renderSearchBar(frame, state);
        }
        if (state.shortcutsOpen) {
            renderShortcutsOverlay(frame, state);
        }
        if (state.subagentsOverlay != null) {
            renderSubagentsOverlay(frame, state);
        }
        if (tabManager.isListOpen()) {
            renderTabsListOverlay(frame);
        }
        if (state.picker != null) {
            renderPicker(frame, state);
        }
        if (!state.approvals.isEmpty()) {
            renderApproval(frame, state);
        }
        if (state.feedbackPrompt != null) {
            renderFeedbackPrompt(frame, state);
        }
        if (state.editPlan != null) {
            renderEditPlan(frame, state);
        }
        if (state.toolOverlay != null) {
            renderToolOverlay(frame, state);
            if (state.hoverToolCmd != null && !state.hoverToolCmd.isBlank()) {
                renderToolOverlayTooltip(frame, state);
            }
        }
        if (state.errorDetail != null && state.errorOpen) {
            renderErrorDetail(frame, state);
        }
        if (state.notificationDetail != null) {
            renderNotificationDetail(frame, state);
        }
        // /btw is hidden while an approval is pending: the approval must stay on top and keep
        // its sole focus, and the overlay state survives so it reappears once resolved.
        if (state.approvals.isEmpty() && state.feedbackPrompt == null && state.btw != null) {
            renderBtw(frame, state);
        }
        if (state.executingTools != null) {
            renderExecutingTools(frame, state);
        }
        renderToast(frame, state);
        // published before the highlight so both passes resolve a press against
        // the very same pane set; the frame freezes grid + boxes + row kinds together
        state.paneZones = List.copyOf(frameZones);
        state.screenGrid = SelectionState.snapshot(frame.buffer());
        state.selectionFrame = new SelectionState.SelectionFrame(
                state.screenGrid,
                state.copyBoxes,
                state.rowKinds,
                state.chatContentLeft,
                state.chatContentRight,
                state.copyZone);
        state.selection.highlight(frame.buffer());
    }

    /**
     * Draws a centered dialog and registers its content area as a selection
     * {@link #frameZones pane}, so a drag started on the dialog is confined to
     * it instead of falling through to whatever the dialog covers (the
     * conversation behind it, the prompt, the whole frame...).
     * <p>
     * Panes are registered in draw order and {@link #copyZones(UiState)} picks
     * the first match, so a later (topmost) dialog wins.
     *
     * @return the inner drawable area, or null when the box is too small to host content
     */
    private Rect modal(final Frame frame, final Rect rect, final String title, final Color borderColor) {
        final var inner = Modal.box(frame, rect, title, borderColor);
        if (inner != null) {
            frameZones.add(inner);
        }
        return inner;
    }

    private Style modelNameStyle(final ChatSettings settings, final String model) {
        final var style = modelStyleService.resolve(
                chatClient.currentProvider(settings), model, !"light".equalsIgnoreCase(theme));
        final var base = Style.EMPTY.fg(Color.hex(style.hex()));
        return style.bold() ? base.bold() : base;
    }

    /**
     * Renders the session tab strip: one cell per open tab, the active one in the
     * accent color, a spinner-like dot while a background turn runs. The hit rects
     * are kept so a mouse press can switch tabs.
     */
    private void renderTabBar(final Rect rect, final Frame frame) {
        tabRects.clear();
        tabCloseRects.clear();
        final var open = tabManager.list();
        if (open.isEmpty() || rect.isEmpty()) {
            return;
        }
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var activeStyle = Style.EMPTY.fg(Color.hex(palette.accent())).bold();
        // reserve 2 columns for the trailing "+" affordance so it always fits,
        // even with a single open tab
        final var width = Math.max(4, Math.min(28, (rect.width() - 2) / open.size() - 1));
        var x = rect.x();
        for (var i = 0; i < open.size(); i++) {
            final var state = open.get(i);
            final var selected = i == tabManager.activeTab();
            // the close marker reserves its columns before the title is ellipsized, so it never truncates
            final var closeable = width >= 6;
            // the leading mode mark (▪ plan / ◆ build) is tinted with that tab's own
            // mode color, so every tab - background ones included - shows its mode
            final var mark = glyphs.modeMark(tabBuildMode(state));
            final var body = " " + tabStatusIcon(state) + " " + mark + " " + tabTitle(state) + " ";
            // a padded label always fills the cell exactly: Paragraph aligns by
            // width, so a shorter line would drift away from the close zone
            final var title = closeable ? ellipsize(body, width - 2) : ellipsize(body, width);
            final var label = closeable ? title + " ".repeat(width - 2 - title.length()) + glyphs.cross() + " " : title;
            final var cell = new Rect(x, rect.y(), Math.min(width, rect.x() + rect.width() - x), rect.height());
            if (cell.width() > 0) {
                tabRects.add(cell);
                if (closeable) {
                    tabCloseRects.add(new Rect(cell.x() + cell.width() - 2, cell.y(), 2, cell.height()));
                }
                // the mode mark keeps its own hue even on the active (accent-bold) title;
                // when the cell is too narrow for the mark to survive the ellipsis, the
                // whole label falls back to a single plain span
                final var markIdx = label.indexOf(mark);
                final var cellStyle = selected ? activeStyle : muted;
                if (markIdx >= 0) {
                    frame.renderWidget(
                            Paragraph.builder()
                                    .text(Text.from(Line.from(
                                            Span.styled(label.substring(0, markIdx), cellStyle),
                                            Span.styled(mark, Style.EMPTY.fg(tabModeColor(state))),
                                            Span.styled(label.substring(markIdx + mark.length()), cellStyle))))
                                    .build(),
                            cell);
                } else {
                    frame.renderWidget(
                            Paragraph.builder()
                                    .text(Text.from(Line.from(Span.styled(label, cellStyle))))
                                    .build(),
                            cell);
                }
                x += width;
            }
        }
        // trailing "+" affordance: clicking it opens a new tab
        tabAddRect = x + 2 <= rect.x() + rect.width() ? new Rect(x, rect.y(), 2, rect.height()) : null;
        if (tabAddRect != null) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(" +", muted))))
                            .build(),
                    tabAddRect);
        }
    }

    /**
     * The tab-bar status icon, most urgent first: a pending approval, an agent
     * question, a running turn, the last completed turn, then plain ready.
     * Symbols come from the {@link Glyphs} catalog so limited terminals can
     * fall back to ASCII ({@code tui.glyphs=ascii}).
     */
    private String tabStatusIcon(final UiState state) {
        if (!state.approvals.isEmpty()) {
            return "!";
        }
        if (state.feedbackPrompt != null) {
            return "?";
        }
        if (state.model.busy() || state.wait != null) {
            return glyphs.running();
        }
        if (state.turnCompleted) {
            return glyphs.check();
        }
        return "-";
    }

    private String tabTitle(final UiState state) {
        final var title = state.title == null || state.title.isBlank() ? null : state.title.strip();
        final var agent = state.session.activeAgent();
        return title != null ? title : agent != null && !agent.isBlank() ? agent : "new session";
    }

    private void renderSidebar(final Rect rect, final Frame frame, final UiState state) {
        final var title =
                state.title == null || state.title.isBlank() ? " yca " : " " + ellipsize(state.title, 24) + " ";
        final var block = block(title);
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        final var width = inner.width();
        final var label = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var value = Style.EMPTY.fg(Color.hex(palette.text()));
        final var branch = gitBranch();
        final var quotaWindows = state.model.quotaWindows().stream()
                .sorted(Comparator.comparingLong(
                        window -> window.windowSeconds() == null ? 0L : window.windowSeconds()))
                .limit(2)
                .toList();
        final var showQuota = state.model.quotaVisible() && !quotaWindows.isEmpty() && inner.height() > 2;
        final var quotaRows = showQuota ? quotaWindows.size() : 0;
        final var rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(quotaRows + 1), Constraint.length(1))
                .split(inner);
        final var text = new ArrayList<Line>();
        text.add(Line.from(
                Span.styled("yca", Style.EMPTY.fg(Color.hex(palette.accent())).bold()),
                Span.styled(" coding agent", label)));
        text.add(Line.empty());
        final var model = chatClient.model(state.session.chatSettings());
        text.add(Line.from(
                Span.styled("model ", label),
                Span.styled(ellipsize(model, width - 6), modelNameStyle(state.session.chatSettings(), model))));
        text.add(Line.from(
                Span.styled("provider ", label),
                Span.styled(
                        ellipsize(
                                chatClient.providerId(state.session == null ? null : state.session.chatSettings()),
                                width - 9),
                        value)));
        text.add(Line.from(
                Span.styled("agent ", label),
                Span.styled(ellipsize(state.activeAgent.isBlank() ? "-" : state.activeAgent, width - 7), value)));
        text.add(Line.from(
                Span.styled("flavor ", label),
                Span.styled(
                        ellipsize(
                                chatClient
                                        .flavor(state.session.chatSettings())
                                        .name()
                                        .toLowerCase(Locale.ROOT),
                                width - 7),
                        value)));
        text.add(Line.from(
                Span.styled("cwd ", label), Span.styled(ellipsizeLeading(workingDirectory(), width - 4), value)));
        if (branch != null) {
            text.add(Line.from(Span.styled("branch ", label), Span.styled(ellipsize(branch, width - 7), value)));
        }
        text.add(Line.from(Span.styled("session ", label), Span.styled(ellipsize(state.sessionId, width - 8), value)));
        // the mode and permissions each get their own row: mode is always visible with
        // the mode-colored value, permissions shows the approval mode and sandbox risk.
        final var permissions = state.session.runtime().permissions();
        text.add(Line.from(
                Span.styled("mode ", label),
                Span.styled(permissions.mode().name().toLowerCase(Locale.ROOT), Style.EMPTY.fg(modeColor()))));
        final var permissionMode = permissions.permissionMode().name().toLowerCase(Locale.ROOT);
        final var fullYolo = permissions.permissionMode() == PermissionMode.FULL_YOLO;
        final var sandboxEnabled = state.session.runtime().sandbox().enabled();
        final var unattended = fullYolo || permissions.permissionMode() == PermissionMode.YOLO;
        final var permissionColor = fullYolo || !sandboxEnabled ? palette.yolo() : palette.warning();
        text.add(Line.from(
                Span.styled("permissions ", label),
                Span.styled(
                        unattended ? permissionMode + " ⚠" : permissionMode,
                        unattended ? Style.EMPTY.fg(Color.hex(permissionColor)).bold() : value)));
        text.add(Line.from(
                Span.styled("sandbox ", label),
                Span.styled(
                        fullYolo ? "bypassed" : sandboxEnabled ? "enabled" : "disabled",
                        Style.EMPTY.fg(Color.hex(sandboxEnabled ? palette.success() : palette.yolo())))));
        if (fullYolo) {
            text.add(Line.from(Span.styled("not recommended", Style.EMPTY.fg(Color.hex(palette.yolo())))));
        }
        text.add(Line.from(Span.styled("servers ", label)));
        for (final var status : mcpManager.serverStatuses()) {
            final var dotColor =
                    switch (status.state()) {
                        case ENABLED -> Color.hex(palette.success());
                        case DISABLED, NOT_STARTED -> Color.hex(palette.textMuted());
                        case PENDING -> Color.hex(palette.warning());
                        case FAILED -> Color.hex(palette.error());
                    };
            text.add(Line.from(
                    Span.styled(glyphs.dot() + " ", Style.EMPTY.fg(dotColor)),
                    Span.styled(ellipsize(status.name(), width - 2), value)));
        }
        text.add(Line.empty());
        text.add(Line.from(
                Span.styled("tokens in  ", label), Span.styled(formatTokens(state.model.contextInputTokens()), value)));
        final var cached = state.model.cacheReadTokens();
        final var fullInputTokens = state.model.contextInputTokens();
        if (cached > 0 && fullInputTokens > 0) {
            text.add(Line.from(
                    Span.styled("cached     ", label),
                    Span.styled(
                            formatTokens(cached) + " (" + Math.round(cached * 100.0 / fullInputTokens) + "%)", value)));
        }
        text.add(Line.from(
                Span.styled("tokens out ", label), Span.styled(formatTokens(state.model.completionTokens()), value)));
        text.add(Line.from(
                Span.styled("context ~  ", label),
                Span.styled(
                        formatTokens(state.model.estimatedContext()) + " tok (" + state.model.contextPercent() + "%)",
                        value)));
        // Full provider-reported input for a matching anchor, including cached input.
        // Without a matching anchor the estimate can still use learned calibration.
        if (state.model.lastRealPromptTokens() > 0) {
            text.add(Line.from(
                    Span.styled("last real  ", label),
                    Span.styled(formatTokens(state.model.lastRealPromptTokens()) + " tok", value)));
        }
        if (state.session.runtime().usage().requests() > 0) {
            text.add(Line.from(
                    Span.styled("llm calls  ", label),
                    Span.styled(Long.toString(state.session.runtime().usage().requests()), value)));
            // optional session cost estimate, only shown when pricing is configured
            final var cost = costEstimator.cost(model, state.model.promptTokens(), state.model.completionTokens());
            cost.ifPresent(c -> text.add(Line.from(
                    Span.styled("cost ~     ", label), Span.styled(String.format(Locale.ROOT, "$%.4f", c), value))));
        }
        // context appliances (dedup/prune/compact/...) as side feedback, right above the gauge:
        // the bottom status strip is reserved for the leading status + notices/errors and must
        // never be clobbered by a "context dedup done" line — the appliance lives here instead.
        final var appliances = state.model.appliances();
        if (!appliances.isEmpty()) {
            final var app = appliances.getFirst();
            final var appLabel = cleanupLabel(app.kinds(), app.mode()) + reclaimPercent(app.tokens());
            text.add(Line.from(
                    Span.styled("cleanup ", label),
                    Span.styled(
                            ellipsize(appLabel, Math.max(8, width - 8)), Style.EMPTY.fg(Color.hex(palette.info())))));
        }
        final var lines = Text.from(text.toArray(Line[]::new));
        final var textRect = rows.getFirst();
        final var overflow = Math.max(0, lines.height() - textRect.height());
        frame.renderWidget(Paragraph.builder().text(lines).scroll(overflow).build(), textRect);
        // Account usage shares the sidebar width with context, rather than overflowing a text row.
        // The hover hit-areas are populated/cleared under this exact showQuota gate so they only
        // ever track the frames where a gauge is actually drawn (no tooltip after a provider switch).
        final var hoverAreas = new ArrayList<UiState.QuotaHoverArea>();
        if (showQuota && !rows.get(1).isEmpty()) {
            for (var i = 0; i < quotaWindows.size() && i < rows.get(1).height(); i++) {
                final var window = quotaWindows.get(i);
                final Double used = window.usedPercent() == null ? null : Math.clamp(window.usedPercent(), 0, 100);
                final var kind = compactWindowLabel(window);
                final var pctText = used == null ? "?" : Math.round(used) + "%";
                final Color color = Color.hex(
                        used == null ? "#777777" : used >= 90 ? "#ef4444" : used >= 70 ? "#f59e0b" : "#3b82f6");
                final var rowRect = new Rect(
                        rows.get(1).x(), rows.get(1).y() + i, rows.get(1).width(), 1);
                frame.renderWidget(
                        Paragraph.builder()
                                .text(Text.from(
                                        quotaWindowRow(rows.get(1).width(), kind, pctText, used, color, label, value)))
                                .build(),
                        rowRect);
                final var reset = window.resetAtEpochSeconds();
                if (reset != null && reset > 0) {
                    hoverAreas.add(new UiState.QuotaHoverArea(rowRect, reset));
                }
            }
        }
        state.quotaHoverAreas = showQuota ? List.copyOf(hoverAreas) : List.of();

        // the hover hit-area is only recorded while the gauge is actually drawn (no tooltip after
        // a provider switch or resize): cleared on every frame where the gauge is absent
        state.contextGaugeRect = null;
        if (!rows.get(2).isEmpty()) {
            final var percent = Math.clamp(state.model.contextPercent(), 0, 100);
            final var compactionThreshold = compactionThreshold();
            final var pruningThreshold = pruningThreshold();
            final var gaugeColor = Color.hex(
                    percent >= compactionThreshold
                            ? palette.error()
                            : percent >= pruningThreshold ? palette.warning() : palette.info());
            frame.renderWidget(
                    Gauge.builder()
                            .percent(percent)
                            .label(Span.raw(percent + "% context"))
                            .gaugeColor(gaugeColor)
                            .build(),
                    rows.get(2));
            state.contextGaugeRect = rows.get(2);
        }
    }

    static String cleanupLabel(final List<String> kinds, final String mode) {
        final var priorities =
                List.of("summarized", "truncated", "trimmed", "deduplicated", "superseded", "errors purged");
        final var labels = new ArrayList<>(kinds.stream()
                .map(kind -> switch (kind) {
                    case "round-compact", "compact", "compact-retry" -> "summarized";
                    case "round-prune", "prune", "tool" -> "trimmed";
                    case "dedup" -> "deduplicated";
                    case "supersede" -> "superseded";
                    case "purge-errors" -> "errors purged";
                    default -> "context updated";
                })
                .toList());
        if ("TRUNCATE".equalsIgnoreCase(mode)) {
            labels.add("truncated");
        }
        return priorities.stream()
                .filter(labels::contains)
                .findFirst()
                .orElse(labels.isEmpty() ? "context updated" : labels.getFirst());
    }

    /**
     * Compact percent-of-reclaimed-context suffix for the sidebar appliance, e.g. {@code " -83%"}
     * when the compaction brought the context from 513K to 87K. Empty when the event carried no
     * usable token pair or nothing was reclaimed.
     */
    static String reclaimPercent(final AgentEvent.Compacted.CompactionTokens tokens) {
        if (tokens == null || tokens.before() <= 0 || tokens.after() < 0 || tokens.after() >= tokens.before()) {
            return "";
        }
        final var percent = (int) Math.round((tokens.before() - tokens.after()) * 100.0 / tokens.before());
        return percent <= 0 ? "" : " -" + percent + "%";
    }

    /**
     * Compact, numeric-only label for a quota window, e.g. 5h, 7d, 30m, so the gauge
     * label stays short and leaves the horizontal bar visible. Unlike displayLabel() it
     * never uses a named form (so a weekly window is "7d" not "weekly"); unnamed windows
     * fall back to their label or "quota".
     */
    private static String compactWindowLabel(final AgentEvent.UsageUpdated.Quota.Window window) {
        return TuiQuotaRender.compactWindowLabel(window);
    }

    private static Line quotaWindowRow(
            final int width,
            final String kind,
            final String pct,
            final Double used,
            final Color barColor,
            final Style labelStyle,
            final Style valueStyle) {
        return TuiQuotaRender.quotaWindowRow(width, kind, pct, used, barColor, labelStyle, valueStyle);
    }

    private static String formatQuotaReset(final long resetAt) {
        return TuiQuotaRender.formatQuotaReset(resetAt);
    }

    private void renderTranscript(final Rect rect, final Frame frame, final UiState state) {
        if (renderOwner != state.model || !Objects.equals(renderSessionId, state.sessionId)) {
            // Each tab owns its compact layout. Switching tabs must not force a replay.
            renderOwner = state.model;
            renderSessionId = state.sessionId;
        }
        final var block = conversationBlock();
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        // the conversation pane is open on the left: the pane wall is replaced by the
        // per-region role bar, so the content starts one column earlier than block.inner()
        final var content = new Rect(inner.x() - 1, inner.y(), inner.width() + 1, inner.height());
        final var laidOut = layoutContent(state.model.markdown(RUNNING_TOOL_MARKER, WELCOME), content.width(), state);
        final var tailChunks = new ArrayList<RenderedChunk>();
        final var tailRows = new ArrayList<String>();
        appendLiveChunks(tailChunks, content.width(), state, tailRows);
        final var chunks = concat(laidOut.chunks(), tailChunks);
        final var toolTops = laidOut.toolTops();
        final var footerLinks = laidOut.footerLinks();
        final var runHeaders = laidOut.runHeaders();
        state.flatRows = concat(laidOut.rows(), tailRows);
        final var total = laidOut.index().height()
                + tailChunks.stream().mapToInt(RenderedChunk::height).sum();
        final var maxViewOffset = Math.max(0, total - Math.max(1, inner.height()));
        state.maxViewOffset = maxViewOffset;
        state.model.maxViewOffset(maxViewOffset);
        if (state.pendingJumpRow >= 0) {
            // jump (search match or Alt+Up/Down tool navigation): bring the target row
            // into view with a third of a screen of context above it
            state.model.anchorRow(Math.clamp(state.pendingJumpRow - Math.max(1, inner.height() / 3), 0, maxViewOffset));
            // an explicit jump counts as user navigation: pin the view there
            state.model.userScrolled(state.model.anchorRow() < maxViewOffset);
            state.pendingJumpRow = -1;
        } else if (!state.model.userScrolled()) {
            // auto-follow: glue the view to the live tail. the anchored row may re-pin
            // to maxViewOffset as the bottom grows while streaming
            state.model.anchorRow(maxViewOffset);
        } else {
            // user browsing: keep the absolute anchor stable even as content grows below;
            // clamp if the content shrank, and reaching the bottom re-arms auto-follow
            state.model.anchorRow(Math.min(state.model.anchorRow(), maxViewOffset));
            if (state.model.anchorRow() >= maxViewOffset) {
                state.model.userScrolled(false);
            }
        }
        final var viewOffset = state.model.anchorRow();
        // expose tool card header hit areas for mouse clicks and Alt+Up/Down navigation.
        // rows are absolute transcript rows (not screen rows) so every card gets an
        // anchor even when scrolled out of view: navigation must reach off-screen cards
        final var anchors = new ArrayList<UiState.ToolAnchor>();
        for (final var top : toolTops) {
            // a lone bordered card reserves its own top-border row, so its header text row
            // (top.row()) sits one row below the block top: extend the clickable strip over
            // both rows so clicking the border or the header text hits the same card
            final var stripTop = top.bordered() ? top.row() - 1 : top.row();
            final var stripH = top.bordered() ? 2 : 1;
            anchors.add(new UiState.ToolAnchor(
                    top.ordinal(),
                    new Rect(content.x(), inner.y() + stripTop - viewOffset, content.width(), stripH),
                    top.row(),
                    top.runMember()));
        }
        state.toolAnchors = anchors;
        // run headers: absolute transcript row per run, converted to a screen hit-area
        final var convertedRunHeaders = new ArrayList<UiState.RunHeader>();
        for (final var h : runHeaders) {
            final var y = inner.y() + h.row() - viewOffset;
            convertedRunHeaders.add(new UiState.RunHeader(
                    h.id(), new Rect(content.x(), y, h.header().width(), 1), h.row()));
            // collapsed run headers participate in Alt+Up/Down tool navigation as a single
            // stop: a negative ordinal marks the run header (never a real card ordinal)
            anchors.add(new UiState.ToolAnchor(
                    -h.id(), new Rect(content.x(), y, h.header().width(), 1), h.row(), false));
        }
        state.runHeaders = convertedRunHeaders;
        // "click to expand" links of truncated tool cards: screen hit-areas, only when on screen
        // (these are click targets, unlike anchors, so visibility filtering is correct here)
        final var links = new ArrayList<UiState.ToolAnchor>();
        for (final var link : footerLinks) {
            final var y = inner.y() + link.row() - viewOffset;
            if (y >= inner.y() && y < inner.y() + inner.height()) {
                links.add(new UiState.ToolAnchor(
                        link.ordinal(), new Rect(content.x(), y, content.width(), 1), link.row(), false));
            }
        }
        state.toolFooterLinks = links;
        final var firstChunk = laidOut.index().firstVisible(viewOffset);
        var chunkTop = laidOut.index().row(firstChunk);
        final var copyBoxes = new ArrayList<SelectionState.CopyBox>();
        final var kinds = new SelectionState.RowKind[frame.height()];
        Arrays.fill(kinds, SelectionState.RowKind.HARD);
        for (var chunkIndex = firstChunk; chunkIndex < chunks.size(); chunkIndex++) {
            final var chunk = chunks.get(chunkIndex);
            final var top = chunkTop - viewOffset;
            if (top >= inner.height()) {
                break;
            }
            chunkTop += chunk.height();
            final var skip = Math.max(0, -top);
            final var visibleTop = Math.max(0, top);
            final var visibleH = Math.min(chunk.height() - skip, Math.max(0, inner.height() - visibleTop));
            if (visibleH <= 0) {
                continue;
            }
            final var sub = new Rect(content.x(), inner.y() + visibleTop, content.width(), visibleH);
            frame.renderWidget(chunk.widget(skip), sub);
            if (chunk.boxed()) {
                // the box rect is resolved from the chunk's unclipped absolute frame
                // (not the clipped sub rect) so a box scrolled partly off-screen never
                // mislabels a visible content row as a top/bottom border
                copyBoxes.add(
                        copyBox(new Rect(content.x(), inner.y() + top, content.width(), chunk.height()), chunk.box()));
            }
            // publish per-screen-row copy kinds for this chunk's visible slice, keyed by
            // absolute screen row so a scroll mid-drag cannot shift a kind onto another
            // line. Boxed chunks have no kinds (the CopyBox geometry already handles their
            // interior; a 1-cell rule row is never copied).
            final var localKinds = chunk.rowKinds();
            if (localKinds != null && localKinds.length > 0 && !chunk.boxed()) {
                for (var r = Math.max(0, skip); r < Math.min(chunk.height(), skip + visibleH); r++) {
                    final var screenY = inner.y() + visibleTop + (r - skip);
                    if (screenY >= 0 && screenY < kinds.length) {
                        kinds[screenY] = localKinds[Math.min(r, localKinds.length - 1)];
                    }
                }
            }
        }
        state.copyBoxes = List.copyOf(copyBoxes);
        state.rowKinds = kinds;
        // the chat content columns: role bars at content.x()/content.x()+1 are excluded on the
        // left, the conversation right wall (content.right()-1) on the right
        state.chatContentLeft = content.x() + 2;
        state.chatContentRight = content.right() - 2;
        // search match highlighting: reversed strips over matched rows, same buffer
        // trick as the scrollbar indicator (no span surgery needed)
        if (state.hoverToolCard >= 0) {
            // hovered card header: reverse-video the whole clickable strip so the
            // click target is visible before the user presses. The rect is resolved
            // from the current-frame anchors (never a stale coordinate): the transcript
            // can auto-scroll between the MOVE and this render.
            for (final var anchor : state.toolAnchors) {
                if (anchor.ordinal() != state.hoverToolCard) {
                    continue;
                }
                final var r = anchor.header();
                final var visY = Math.max(inner.y(), r.y());
                final var visBottom = Math.min(inner.y() + inner.height(), r.y() + r.height());
                if (visBottom > visY) {
                    frame.buffer()
                            .setStyle(
                                    new Rect(content.x(), visY, content.width(), visBottom - visY),
                                    Style.EMPTY.reversed());
                }
                break;
            }
        }
        if (state.search.active() && !state.search.matches().isEmpty()) {
            final var matches = new HashSet<>(state.search.matches());
            var top = 0;
            for (final var chunk : chunks) {
                for (var r = 0; r < chunk.height(); r++) {
                    if (!matches.contains(top + r)) {
                        continue;
                    }
                    final var y = inner.y() + top + r - viewOffset;
                    if (y >= inner.y() && y < inner.y() + inner.height()) {
                        frame.buffer().setStyle(new Rect(content.x(), y, content.width(), 1), Style.EMPTY.reversed());
                    }
                }
                top += chunk.height();
            }
        }
        int x = inner.x() + inner.width() - 1;
        if (maxViewOffset > 0) {
            final var ratio = Math.clamp(1.0 - (double) viewOffset / maxViewOffset, 0.0, 1.0);
            final var indH = Math.max(1, (int) Math.round(inner.height() * ratio));
            final var indY = inner.y() + inner.height() - indH;
            frame.buffer().setStyle(new Rect(x, indY, 1, indH), Style.EMPTY.reversed());
        }
        if (state.model.busy() && state.spinnerState.tick() % 2 == 0 && inner.height() > 0) {
            final var caretRect = new Rect(x, inner.y() + inner.height() - 1, 1, 1);
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(
                                    Span.styled(glyphs.caret(), Style.EMPTY.fg(Color.hex(palette.accent()))))))
                            .build(),
                    caretRect);
        }
    }

    private record RenderedChunk(
            int height, IntFunction<Widget> supplier, BoxChrome box, SelectionState.RowKind[] rowKinds) {
        RenderedChunk(final int height, final IntFunction<Widget> supplier, final BoxChrome box) {
            this(height, supplier, box, null);
        }

        Widget widget(final int skip) {
            return supplier.apply(skip);
        }

        boolean boxed() {
            return box != null;
        }
    }

    /** Constant-time view: spinner frames must not copy the historical row/chunk lists. */
    private static <T> List<T> concat(final List<T> head, final List<T> tail) {
        if (tail.isEmpty()) {
            return head;
        }
        return new AbstractList<>() {
            @Override
            public T get(final int index) {
                return index < head.size() ? head.get(index) : tail.get(index - head.size());
            }

            @Override
            public int size() {
                return head.size() + tail.size();
            }
        };
    }

    /**
     * The block chrome of a boxed chunk: the columns/rows the surrounding block
     * (borders plus padding) contributes on each side, so the transcript render
     * can publish the exact content region for selection copy. {@code null} for
     * unbordered chunks (prose paragraphs, thoughts, the collapsed tool line).
     * Every boxed chunk here uses symmetric chrome, so half the block's total
     * chrome falls on each side.
     */
    private record BoxChrome(int left, int right, int top, int bottom) {
        static BoxChrome symmetric(final Block block) {
            final var h = block.horizontalChrome();
            final var v = block.verticalChrome();
            return new BoxChrome(h / 2, h / 2, v / 2, v / 2);
        }
    }

    /**
     * The copyable interior of one boxed chunk as drawn on screen: the content
     * columns (walls and padding already excluded) and the exact rule rows that
     * are the box's top and bottom borders. Built from the chunk's unclipped
     * absolute rect so a partially scrolled box never mislabels a visible
     * content row as a border; selection copy only visits on-screen rows anyway.
     */
    private static SelectionState.CopyBox copyBox(final Rect outer, final BoxChrome chrome) {
        final var left = outer.x() + chrome.left();
        final var right = outer.right() - chrome.right() - 1;
        final var top = outer.y() + chrome.top();
        final var bottom = outer.bottom() - chrome.bottom() - 1;
        return new SelectionState.CopyBox(
                left,
                right,
                top,
                bottom,
                chrome.top() > 0 ? outer.y() : -1,
                chrome.bottom() > 0 ? outer.bottom() - 1 : -1);
    }

    /** Everything that, when unchanged, lets a frame reuse the previous chunk render. */
    private record ContentKey(
            String md,
            int width,
            boolean showThinking,
            ToolOutputVisibility showToolActions,
            DiffViewMode diffViewMode,
            long toolOutputs,
            int collapsedToolsHash,
            int runStateHash,
            int paletteId,
            int stylesId,
            String userLabel,
            String assistantLabel) {}

    private record ContentResult(
            List<RenderedChunk> chunks,
            List<String> rows,
            List<UiState.ToolTop> toolTops,
            List<UiState.FooterLink> footerLinks,
            List<UiState.RunHeader> runHeaders,
            String lastCodeBlock,
            TranscriptLayout<RenderedChunk> index) {}

    /** Per-tab compact layout ownership; shared with UiState, not a widget cache. */
    static final class ContentLayout {
        private long generation = -1;
        private ContentKey key;
        private ContentResult result;
        private LiveThoughtKey liveThoughtKey;
        private ContentPart liveThought;
        private final Map<TurnUnitKey, ContentPart> turns = new LinkedHashMap<>();
        private final Map<RegionUnitKey, ContentPart> regions = new LinkedHashMap<>();
        private final Map<CodeUnitKey, ContentPart> code = new LinkedHashMap<>();
        private final Map<ToolUnitKey, ToolPart> tools = new LinkedHashMap<>();
        private final Set<TurnUnitKey> liveTurns = new HashSet<>();
        private final Set<RegionUnitKey> liveRegions = new HashSet<>();
        private final Set<CodeUnitKey> liveCode = new HashSet<>();
        private final Set<ToolUnitKey> liveTools = new HashSet<>();

        private void clear(final long generation) {
            this.generation = generation;
            key = null;
            result = null;
            liveThoughtKey = null;
            liveThought = null;
            turns.clear();
            regions.clear();
            code.clear();
            tools.clear();
            liveTurns.clear();
            liveRegions.clear();
            liveCode.clear();
            liveTools.clear();
        }
    }

    /** The content-side outputs of rendering one transcript region. */
    private record ContentPart(List<RenderedChunk> chunks, List<String> rows, String lastCodeBlock) {
        static final ContentPart EMPTY = new ContentPart(List.of(), List.of(), null);
    }

    private record CodeUnitKey(String language, String source, int width, Palette palette) {}

    private record LiveThoughtKey(String source, int width, Style style, Palette palette) {}

    private record ToolUnitKey(
            List<ToolRun.Member> members,
            Map<Long, ToolOutputStore.Entry> outputs,
            int width,
            Palette palette,
            ToolOutputVisibility visibility,
            DiffViewMode mode,
            long captureRevision,
            boolean collapsed,
            Set<Long> collapsedMembers) {}

    private record ToolPart(
            ContentPart content,
            List<UiState.ToolTop> tops,
            List<UiState.FooterLink> footers,
            List<UiState.RunHeader> headers) {}

    private void clearRenderCaches() {
        contentLock.lock();
        try {
            lastContentKey = null;
            lastContent = null;
            layoutGeneration++;
        } finally {
            contentLock.unlock();
        }
        regionUnitCache.clear();
        regionUnitCacheWeight = 0;
        turnUnitCache.clear();
        turnUnitCacheWeight = 0;
        turnUnitFrame = 0;
        toolBodyCache.clear();
        toolOverlayLayout = null;
        transcriptRenderer.clear();
        syntaxHighlighter.clearCaches();
        if (toolOutputFormat != null) {
            toolOutputFormat.clearCaches();
        }
        lastLiveThoughtPrefix = "";
        lastLiveThoughtLines = null;
        renderOwner = null;
        renderSessionId = null;
    }

    /**
     * Cache key of one rendered region: it must cover every input the render depends on.
     * Kind plus source text identify the region content; {@code width} changes the wrap;
     * {@code paletteId} covers theme switches (applyTheme() replaces palette, styles and
     * code theme together, so the palette instance identity is a complete theme token);
     * {@code markdownStylesId} covers any style object replacement outside a palette swap
     * (the markdown styles are also replaced by applyTheme(), so either token alone would
     * do - keeping both is cheap and makes the dependency explicit).
     */
    private record RegionUnitKey(
            String kind,
            String text,
            int width,
            int paletteId,
            int markdownStylesId,
            String userLabel,
            String assistantLabel) {}

    private record TurnUnitKey(
            TuiTranscriptModel.TurnSegment turn,
            int width,
            Palette palette,
            MarkdownStyles styles,
            String headerLabel) {}

    private record CachedTurnUnit(ContentPart part, long weight, long frame) {}

    /**
     * Renders one stable region (thought / log / plain prose) through a bounded content-keyed
     * cache. A stable region's text (and the key) only changes when its content changed, so a
     * streaming frame reuses every stable region's rendered chunks/rows and only the growing
     * tail region re-renders - instead of re-parsing the whole transcript with the markdown
     * engine, re-wrapping every line and re-allocating every span on each TextDelta tick. Tool
     * regions are deliberately NOT cached: they flow through the existing run-grouping path so
     * run borders, live cards, collapse state, tool tops/footer links and their absolute rows
     * keep exactly the current semantics.
     *
     * <p>The cache is render-thread confined (like the live-thinking memo), bounded both by
     * entry count and by a retained-weight approximation, and oversized regions bypass it
     * entirely so a giant tool capture never pins a multi-megabyte widget.</p>
     */
    private ContentPart regionUnit(final String kind, final String text, final int width, final UiState state) {
        if (text == null || text.isEmpty()) {
            return ContentPart.EMPTY;
        }
        if ("prose".equals(kind)) {
            final var chunks = new ArrayList<RenderedChunk>();
            final var rows = new ArrayList<String>();
            final var before = state.lastCodeBlock;
            state.lastCodeBlock = null;
            appendChunks(chunks, text, width, state, rows);
            final var code = state.lastCodeBlock;
            if (code == null) {
                state.lastCodeBlock = before;
            }
            return new ContentPart(List.copyOf(chunks), List.copyOf(rows), code);
        }
        final var key = new RegionUnitKey(
                kind,
                text,
                width,
                System.identityHashCode(palette),
                System.identityHashCode(markdownStyles),
                state.model.userLabel(),
                state.model.assistantLabel());
        state.contentLayout.liveRegions.add(key);
        final var layoutPart = state.contentLayout.regions.get(key);
        if (layoutPart != null) {
            if (layoutPart.lastCodeBlock() != null) {
                state.lastCodeBlock = layoutPart.lastCodeBlock();
            }
            return layoutPart;
        }
        final var cacheable = text.length() <= MAX_REGION_UNIT_SOURCE_CHARS;
        final var cached = cacheable ? regionUnitCache.get(key) : null;
        if (cached != null) {
            // the cached render may have set the "last code block" copy target; later regions
            // (rendered after this unit, top to bottom) override it, so restoring here keeps
            // the value correct frame after frame without re-rendering the unit
            if (cached.lastCodeBlock() != null) {
                state.lastCodeBlock = cached.lastCodeBlock();
            }
            return cached;
        }
        final var local = new ArrayList<RenderedChunk>();
        final var rows = new ArrayList<String>();
        final var codeBefore = state.lastCodeBlock;
        state.lastCodeBlock = null;
        switch (kind) {
            case "thought" -> addThoughtChunk(local, thoughtLines(text, width), rows);
            case "log" -> addMarkdownChunk(local, logLines(text, width), rows);
            case "prose" -> appendChunks(local, text, width, state, rows);
            default -> throw new IllegalArgumentException("unknown region kind: " + kind);
        }
        final var lastCodeBlock = state.lastCodeBlock;
        if (lastCodeBlock == null) {
            state.lastCodeBlock = codeBefore;
        }
        if (local.isEmpty()) {
            return ContentPart.EMPTY;
        }
        final var part = new ContentPart(List.copyOf(local), List.copyOf(rows), lastCodeBlock);
        state.contentLayout.regions.put(key, part);
        final var weight = regionWeight(key, part);
        if (!cacheable || weight > MAX_REGION_UNIT_CACHE_WEIGHT) {
            return part;
        }
        regionUnitCache.put(key, part);
        regionUnitCacheWeight += weight;
        while (regionUnitCache.size() > MAX_REGION_UNIT_CACHE_ENTRIES
                || regionUnitCacheWeight > MAX_REGION_UNIT_CACHE_WEIGHT) {
            final var eldest = regionUnitCache.entrySet().iterator().next();
            regionUnitCacheWeight -= regionWeight(eldest.getKey(), eldest.getValue());
            regionUnitCache.remove(eldest.getKey());
        }
        return part;
    }

    // Conservative accounting for source, rows, spans and widget closures; not a heap-size claim.
    private static long regionWeight(final RegionUnitKey key, final ContentPart part) {
        return contentPartWeight(key.text(), part);
    }

    private static long contentPartWeight(final String source, final ContentPart part) {
        var weight = 256L + 2L * source.length() + 256L * part.chunks().size();
        for (final var row : part.rows()) {
            weight += 256L + 64L * row.length();
        }
        return weight;
    }

    /** Appends one rendered region unit to the frame accumulators (rows are copied verbatim). */
    private static void appendPart(final List<RenderedChunk> out, final List<String> rows, final ContentPart part) {
        if (part == null || part.chunks().isEmpty()) {
            return;
        }
        out.addAll(part.chunks());
        rows.addAll(part.rows());
    }

    private void appendLiveChunks(
            final List<RenderedChunk> out, final int width, final UiState state, final List<String> rows) {
        final var contentWidth = Math.max(1, width - 2); // room for the per-region left bar
        // Live thought content is not spinner-sensitive: only the indicator below is.
        // Keep its compact layout independently so a stalled large thought is cheap too.
        if (state.showThinking && state.model.thinkingLength() > 0) {
            final var body = state.model.thinkingText().stripLeading();
            final var live = body.isEmpty() ? "" : "**" + state.model.thinkingLabel() + "**\n\n" + body;
            if (!live.isEmpty()) {
                final var key = new LiveThoughtKey(live, contentWidth, thoughtStyle, palette);
                final var layout = state.contentLayout;
                if (!key.equals(layout.liveThoughtKey)) {
                    final var chunks = new ArrayList<RenderedChunk>();
                    final var textRows = new ArrayList<String>();
                    addThoughtChunk(chunks, liveThoughtLines(live, contentWidth), textRows);
                    layout.liveThoughtKey = key;
                    layout.liveThought = new ContentPart(List.copyOf(chunks), List.copyOf(textRows), null);
                }
                appendPart(out, rows, layout.liveThought);
            }
        } else {
            state.contentLayout.liveThoughtKey = null;
            state.contentLayout.liveThought = null;
        }
        // in-pane activity indicator: shown even when thoughts are hidden so a
        // reasoning round never looks like a hang
        if (state.model.thinkingLength() > 0) {
            addThinkingIndicator(out, state, rows);
        }
    }

    /** Reuses unchanged frames and bounded rendered regions; tools keep their grouping path. */
    private void contentChunks(
            final List<RenderedChunk> out,
            final String md,
            final int width,
            final UiState state,
            final List<UiState.ToolTop> toolTops,
            final List<UiState.FooterLink> footerLinks,
            final List<UiState.RunHeader> runHeaders,
            final List<String> rows) {
        final var result = layoutContent(md, width, state);
        out.addAll(result.chunks());
        rows.addAll(result.rows());
        toolTops.addAll(result.toolTops());
        footerLinks.addAll(result.footerLinks());
        runHeaders.addAll(result.runHeaders());
    }

    private ContentResult layoutContent(final String md, final int width, final UiState state) {
        final var layout = state.contentLayout;
        if (layout.generation != layoutGeneration) {
            layout.clear(layoutGeneration);
        }
        final var key = new ContentKey(
                md,
                width,
                state.showThinking,
                state.showToolActions,
                state.diffViewMode,
                state.toolOutputs.revision(),
                state.collapsedTools.hashCode(),
                state.runCollapsed.hashCode(),
                System.identityHashCode(palette),
                System.identityHashCode(markdownStyles),
                state.model.userLabel(),
                state.model.assistantLabel());
        final ContentResult cached;
        contentLock.lock();
        try {
            cached = key.equals(layout.key) ? layout.result : null;
        } finally {
            contentLock.unlock();
        }
        if (cached != null) {
            state.lastCodeBlock = cached.lastCodeBlock();
            return cached;
        }
        turnUnitFrame++;
        layout.liveTurns.clear();
        layout.liveRegions.clear();
        layout.liveCode.clear();
        layout.liveTools.clear();
        final var contentWidth = Math.max(1, width - 2); // room for the per-region left bar
        final var local = new ArrayList<RenderedChunk>();
        final var localRows = new ArrayList<String>();
        final var localRunHeaders = new ArrayList<UiState.RunHeader>();
        final var localToolTops = new ArrayList<UiState.ToolTop>();
        final var localFooterLinks = new ArrayList<UiState.FooterLink>();
        final var pendingRuns = new ArrayList<ToolRun.Builder>();
        final Runnable flushRuns = () -> {
            // flush once and drop: without the clear, every flushed run stayed in the
            // list, later tool regions kept extending it (one mega-run per session) and
            // every subsequent boundary re-rendered it with extra members - duplicated
            // headers sharing the same run id (opening one opened "all" the copies) and
            // overlapping full-width header rects stealing clicks from member cards
            for (final var builder : pendingRuns) {
                addToolRunChunk(
                        local,
                        builder.build(),
                        width,
                        state,
                        localToolTops,
                        localFooterLinks,
                        localRunHeaders,
                        localRows);
            }
            pendingRuns.clear();
        };
        final var regions = TuiTranscriptModel.regions(md);
        final var toolSources = new HashSet<String>();
        if (state.toolOverlay != null) {
            final var overlayEntry = state.toolOverlay.entry();
            toolSources.add(stripLeadingCommandEcho(overlayEntry.output(), commandArgOf(overlayEntry.args())));
        }
        // Keep only sources present in this frame: streaming revisions and trimmed or
        // switched-away history must not accumulate in the cache.
        final var sources = new HashSet<String>();
        for (final var region : regions) {
            sources.add(region.text());
        }
        regionUnitCache.keySet().removeIf(k -> !sources.contains(k.text()));
        regionUnitCacheWeight = regionUnitCache.entrySet().stream()
                .mapToLong(e -> regionWeight(e.getKey(), e.getValue()))
                .sum();
        state.lastCodeBlock = null;
        for (final var region : regions) {
            if (region.thought()) {
                flushRuns.run();
                if (!state.showThinking) {
                    continue;
                }
                final var part = regionUnit("thought", region.text(), contentWidth, state);
                appendPart(local, localRows, part);
            } else if (region.tool()) {
                final var parsed = ToolCard.parse(region.text());
                toolSources.add(parsed.output());
                if (parsed.running()) {
                    final var full = state.toolOutputs.get(parsed.ordinal());
                    toolSources.add(stripLeadingCommandEcho(
                            parsed.output(), full == null ? parsed.name() : commandArgOf(full.args())));
                }
                final var card = parsed.withCapturedStatus(state.toolOutputs.get(parsed.ordinal()));
                if (!card.running() && !pendingRuns.isEmpty()) {
                    pendingRuns.get(pendingRuns.size() - 1).add(new ToolRun.Member(card, region.text()));
                } else {
                    pendingRuns.add(ToolRun.builder(new ToolRun.Member(card, region.text())));
                }
            } else if (region.log()) {
                flushRuns.run();
                final var part = regionUnit("log", region.text(), contentWidth, state);
                appendPart(local, localRows, part);
            } else if (!region.text().isBlank()) {
                flushRuns.run();
                final var part = regionUnit("prose", region.text(), contentWidth, state);
                appendPart(local, localRows, part);
            }
        }
        flushRuns.run();
        final var turns = turnUnitCache.values().iterator();
        while (turns.hasNext()) {
            final var turn = turns.next();
            if (turn.frame() != turnUnitFrame) {
                turnUnitCacheWeight -= turn.weight();
                turns.remove();
            }
        }
        toolBodyCache.retainSources(toolSources);
        final var result = new ContentResult(
                List.copyOf(local),
                List.copyOf(localRows),
                List.copyOf(localToolTops),
                List.copyOf(localFooterLinks),
                List.copyOf(localRunHeaders),
                state.lastCodeBlock,
                new TranscriptLayout<>(local, RenderedChunk::height));
        layout.turns.keySet().retainAll(layout.liveTurns);
        layout.regions.keySet().retainAll(layout.liveRegions);
        layout.code.keySet().retainAll(layout.liveCode);
        layout.tools.keySet().retainAll(layout.liveTools);
        contentLock.lock();
        try {
            // Only compact pre-wrapped text/style runs and row metadata live here.
            // Rich widgets are viewport-local, so cache admission must not discard layout.
            layout.key = key;
            layout.result = result;
            lastContentKey = key;
            lastContent = result;
        } finally {
            contentLock.unlock();
        }
        return result;
    }

    /**
     * Renders a thought region (flushed or live) italicized behind the thin bar. Prose is
     * rendered by the markdown engine on the italic thought base; fenced code blocks are
     * split and syntax-highlighted like normal output, then every token span is laid out
     * bold (keeping its token color) so code stays readable and colour-coded inside thoughts.
     * The flushed marker text carries a bold `**Thinking**` header mimicking a speaker heading -
     * it is stripped and replaced by the lowercase panel label below.
     */
    private List<Line> thoughtLines(final String text, final int width) {
        var source = text.stripLeading();
        var heading = "thinking";
        if (source.startsWith("**Thinking**")) {
            source = source.substring("**Thinking**".length()).stripLeading();
        } else if (source.startsWith("**")) {
            final var end = source.indexOf("**\n", 2);
            if (end > 2 && source.substring(2, end).endsWith(" - Thinking")) {
                heading = source.substring(2, end);
                source = source.substring(end + 2).stripLeading();
            }
        }
        final var lines = new ArrayList<Line>();
        lines.addAll(
                withBar(transcriptRenderer.renderRegion(heading, width, thoughtStyle, markdownStyles), thoughtBar()));
        appendThoughtBody(lines, source, width);
        return lines;
    }

    /**
     * Renders a captured log region inline, as plain text: the first row carries the
     * rendered block label (see {@link #appendLogRegion}) then the level and shortened
     * logger name as bracket groups, and the message on the same row - so a log reads
     * as one compact timeline entry instead of a header plus body. The label and logger
     * groups are muted, the level group is bold in its level color, and the message
     * keeps the shared {@link #logStyle} (bold for WARNING and above, see
     * {@link #logStrong}). A blank label (timestamp disabled) omits its group. The
     * message remainder (wrapped continuation or fenced blocks) is rendered on following
     * rows by {@link #appendLogBody}; there is no background tint, only the thin
     * level-colored left bar and the level token carry the level tone.
     */
    private List<Line> logLines(final String text, final int width) {
        var source = text.stripLeading();
        final var nl = source.indexOf('\n');
        final var label = nl < 0 ? source : source.substring(0, nl).strip();
        source = nl < 0 ? "" : source.substring(nl + 1).stripLeading();
        final var level = logLevel(source);
        final var strong = logStrong(level);
        final var bar = Span.styled("│ ", palette.blended(logLevelColor(palette, tuiConfiguration.log(), level), 0.25));
        final var lines = new ArrayList<Line>();
        // the body head is "<LEVEL> <shortLogger>", the message starts on the next line
        final var headEnd = source.indexOf('\n');
        final var head = (headEnd < 0 ? source : source.substring(0, headEnd)).strip();
        var message = headEnd < 0 ? "" : source.substring(headEnd + 1).stripLeading();
        final var space = head.indexOf(' ');
        final var levelToken = space < 0 ? "INFO" : head.substring(0, space).strip();
        final var logger = space < 0 ? "" : head.substring(space + 1).strip();
        if (space < 0 && !head.isBlank()) {
            // no "<LEVEL> <shortLogger>" head: the whole body is the message
            message = message.isBlank() ? head : head + "\n" + message;
        }
        final var meta = new ArrayList<Span>();
        if (!label.isBlank()) {
            // the rendered logsTemplate label already carries its own brackets
            // (default "[{{formatDate \"HH:mm:ss\"}}]"): render it verbatim instead
            // of wrapping it again, or "[18:41:41]" becomes "[[18:41:41]]"
            meta.add(Span.styled(label, palette.muted()));
        }
        if (!levelToken.isBlank()) {
            meta.add(Span.styled(
                    "[" + levelToken + "]", palette.bold(logLevelColor(palette, tuiConfiguration.log(), level))));
        }
        if (!logger.isBlank()) {
            meta.add(Span.styled("[" + logger + "]", palette.muted()));
        }
        final var messageEnd = message.indexOf('\n');
        final var firstMessage = messageEnd < 0 ? message : message.substring(0, messageEnd);
        final var messageTail =
                messageEnd < 0 ? "" : message.substring(messageEnd + 1).stripLeading();
        final var messageStyle = strong ? logStyle.bold() : logStyle;
        final var row = new ArrayList<Span>();
        row.add(bar);
        row.addAll(meta);
        if (!firstMessage.isBlank()) {
            // one separating space between the "[<LEVEL>][<shortLogger>]" group and the
            // message, so a log reads "...StructuralCodeGraph] CodeGraph loadIndex..."
            final var separator = Span.styled(" ", messageStyle);
            final var metaWidth =
                    meta.stream().mapToInt(s -> s.content().length()).sum()
                            + separator.content().length();
            final var barWidth = bar.content().length();
            final var wrapped = MarkdownLines.wrapCode(
                    List.of(Line.from(Span.styled(firstMessage, messageStyle))),
                    Math.max(1, width - barWidth - metaWidth));
            row.add(separator);
            row.addAll(wrapped.get(0).spans());
            for (var i = 1; i < wrapped.size(); i++) {
                lines.add(Line.from(bar).append(wrapped.get(i)));
            }
        }
        lines.add(0, Line.from(row));
        appendLogBody(lines, messageTail, width, bar, messageStyle);
        return lines;
    }

    /**
     * Extracts the {@link Level} name from the body's first line (the
     * {@link #appendLogRegion} format is {@code <LEVEL> <shortLogger>}): styling
     * falls back to INFO when the level is missing or not parseable.
     */
    private static Level logLevel(final String body) {
        final var first = body.indexOf('\n');
        final var head = (first < 0 ? body : body.substring(0, first)).stripLeading();
        final var space = head.indexOf(' ');
        final var token = space < 0 ? head : head.substring(0, space);
        if (token == null || token.isBlank()) {
            return Level.INFO;
        }
        try {
            return Level.parse(token);
        } catch (final IllegalArgumentException parseError) {
            return Level.INFO;
        }
    }

    /**
     * Whether a level renders its log body bold: only WARNING and above, so severe
     * rows stand out from the plain text of INFO and the debug levels. Kept
     * package-private so the mapping is unit-testable without a TUI.
     */
    static boolean logStrong(final Level level) {
        return level.intValue() >= Level.WARNING.intValue();
    }

    /**
     * Resolves the log color for a level: the configured {@code color} hex wins,
     * blank/auto falls back to the closest semantic palette accent. Kept
     * package-private so the per-level mapping is unit-testable without a TUI.
     *
     * @return a hex string ready for the palette blending helpers
     */
    static String logLevelColor(final Palette palette, final TuiLogConfiguration log, final Level level) {
        final var cfg = levelConfig(log, level);
        final var configured =
                cfg == null || cfg.color() == null ? null : cfg.color().strip();
        if (configured != null && !configured.isBlank() && !"auto".equals(configured)) {
            return configured;
        }
        final var raw = level.intValue();
        if (raw >= Level.SEVERE.intValue()) {
            return palette.error();
        }
        if (raw >= Level.WARNING.intValue()) {
            return palette.warning();
        }
        if (raw >= Level.CONFIG.intValue()) {
            return palette.info();
        }
        final var dim = raw >= Level.FINE.intValue() ? 0.25 : raw >= Level.FINER.intValue() ? 0.40 : 0.55;
        return hexOf(TuiColors.blend(palette.info(), palette.backgroundPanel(), dim));
    }

    /** Level-tone config entry for a level, or null when the config (or the level) is absent. */
    private static TuiLogLevelConfiguration levelConfig(final TuiLogConfiguration log, final Level level) {
        if (log == null) {
            return null;
        }
        final var raw = level.intValue();
        if (raw >= Level.SEVERE.intValue()) {
            return log.severe();
        }
        if (raw >= Level.WARNING.intValue()) {
            return log.warning();
        }
        if (raw >= Level.INFO.intValue()) {
            return log.info();
        }
        if (raw >= Level.CONFIG.intValue()) {
            return log.config();
        }
        if (raw >= Level.FINE.intValue()) {
            return log.fine();
        }
        if (raw >= Level.FINER.intValue()) {
            return log.finer();
        }
        return log.finest();
    }

    /** Renders a color back to {@code #rrggbb} for the palette blending helpers. */
    static String hexOf(final Color color) {
        final var rgb = color.toRgb();
        return "#%02x%02x%02x".formatted(rgb.r(), rgb.g(), rgb.b());
    }

    /**
     * Renders one log body into existing output lines, mirroring
     * {@link #appendThoughtBody} so fenced blocks in a log message keep their
     * code layout but on the plain log bar and non-italic style: there is no
     * background tint, only the level-colored left bar, and the prose keeps the
     * message style (bold for WARNING+ levels, see {@link #logStrong}).
     */
    private void appendLogBody(
            final List<Line> lines, final String source, final int width, final Span bar, final Style messageStyle) {
        final var body = new ArrayList<Line>();
        for (final var seg : syntaxHighlighter.split(source)) {
            final List<Line> rendered;
            if (seg.type() == SyntaxHighlighter.Segment.Type.CODE) {
                rendered = withBar(
                        MarkdownLines.wrapCode(
                                boldedCode(syntaxHighlighter.highlightLines(seg.language(), seg.text())), width),
                        bar);
            } else if (!seg.text().isBlank()) {
                rendered =
                        withBar(transcriptRenderer.renderRegion(seg.text(), width, messageStyle, markdownStyles), bar);
            } else {
                continue;
            }
            TranscriptRenderer.appendBlocks(body, rendered, true);
        }
        lines.addAll(body);
    }

    /**
     * Renders one thought source region (prose or fenced code) into existing output lines.
     * <p>
     * Fenced blocks are split out of the prose and highlighted separately, so consecutive segments
     * are independent block groups: one blank row is kept between them, exactly like the sibling
     * blocks of a single markdown document get from the markdown walk. Without it a fenced block
     * glues to the paragraph above it, which is what made thoughts read denser than they are.
     */
    private void appendThoughtBody(final List<Line> lines, final String source, final int width) {
        final var body = new ArrayList<Line>();
        for (final var seg : syntaxHighlighter.split(source)) {
            final List<Line> rendered;
            if (seg.type() == SyntaxHighlighter.Segment.Type.CODE) {
                rendered = withBar(
                        MarkdownLines.wrapCode(
                                boldedCode(syntaxHighlighter.highlightLines(seg.language(), seg.text())), width),
                        thoughtBar());
            } else if (!seg.text().isBlank()) {
                rendered = withBar(
                        transcriptRenderer.renderRegion(seg.text(), width, thoughtStyle, markdownStyles), thoughtBar());
            } else {
                continue;
            }
            TranscriptRenderer.appendBlocks(body, rendered, true);
        }
        lines.addAll(body);
    }

    /**
     * Renders the live (still streaming) thinking tail. The growing text is cut at the last
     * safe (non-fence) newline: the stable prefix is rendered once and memoized, and only the
     * still-open suffix is re-rendered each tick. Falls back to the plain whole-region render
     * until the stream produced a first complete line.
     */
    private List<Line> liveThoughtLines(final String text, final int width) {
        var live = text.stripLeading();
        if (live.startsWith("**Thinking**")) {
            live = live.substring("**Thinking**".length()).stripLeading();
        }
        final var cut = transcriptRenderer.lastSafeNewline(live);
        if (cut == 0) {
            return thoughtLines(live, width);
        }
        final var prefix = live.substring(0, cut);
        final var suffix = live.substring(cut);
        if (prefix.length() > 64 * 1024) {
            // Render oversized sources but never pin their source/layout in a second memo.
            lastLiveThoughtPrefix = "";
            lastLiveThoughtLines = null;
            return thoughtLines(live, width);
        }
        if (lastLiveThoughtLines == null
                || !prefix.equals(lastLiveThoughtPrefix)
                || width != lastLiveThoughtWidth
                || thoughtStyle != lastLiveThoughtStyle) {
            lastLiveThoughtPrefix = prefix;
            lastLiveThoughtWidth = width;
            lastLiveThoughtStyle = thoughtStyle;
            lastLiveThoughtLines = thoughtLines(prefix, width);
        }
        final var out = new ArrayList<Line>(lastLiveThoughtLines.size() + 16);
        out.addAll(lastLiveThoughtLines);
        // the memoized prefix and the still-open suffix are two independent renders: a paragraph
        // separator that falls on the cut would be lost, so it is restored from the source
        final var suffixLines = new ArrayList<Line>();
        appendThoughtBody(suffixLines, suffix, width);
        TranscriptRenderer.appendBlocks(
                out, suffixLines, TranscriptRenderer.boundaryBlanks(prefix, suffix) > 0, !suffix.isBlank());
        return out;
    }

    /** Applies the thought code style (bold) to every span of a highlighted code block, preserving token colors. */
    private List<Line> boldedCode(final List<Line> lines) {
        final var out = new ArrayList<Line>(lines.size());
        for (final var line : lines) {
            final var spans = new ArrayList<Span>(line.spans().size());
            for (final var span : line.spans()) {
                spans.add(span.patchStyle(thoughtCodeStyle));
            }
            out.add(Line.from(spans));
        }
        return out;
    }

    /**
     * Renders a final answer body as rendered chunks (a fenced block is split out of the prose and
     * boxed on its own chunk).
     * <p>
     * Consecutive segments are independent block groups: a blank chunk is inserted between them,
     * exactly like the sibling blocks of a single markdown document get from the markdown walk.
     * Without it a fenced block glues to the paragraph above it, and since the answer body is
     * streamed the same way a thought is, a paragraph separator can also fall on a chunk cut.
     */
    private void appendChunks(
            final List<RenderedChunk> out,
            final String md,
            final int width,
            final UiState state,
            final List<String> rows) {
        var anySegment = false;
        for (final var seg : syntaxHighlighter.split(md)) {
            if (anySegment && !rows.isEmpty()) {
                // blank row between two block groups: a turn header already brings its own spacer
                addMarkdownChunk(out, List.of(Line.empty()), rows);
                anySegment = false;
            }
            if (seg.type() == SyntaxHighlighter.Segment.Type.TEXT) {
                for (final var turn : TuiTranscriptModel.splitTurns(
                        seg.text(), state.model.userMarker(), state.model.assistantMarker())) {
                    if (turn.text().isBlank()) {
                        continue;
                    }
                    appendPart(out, rows, turnUnit(turn, width, state));
                    anySegment = true;
                }
            } else {
                anySegment = false;
                final var lang = seg.language();
                final var key = new CodeUnitKey(lang, seg.text(), width, palette);
                state.contentLayout.liveCode.add(key);
                final var cached = state.contentLayout.code.get(key);
                if (cached != null) {
                    appendPart(out, rows, cached);
                    state.lastCodeBlock = cached.lastCodeBlock();
                    anySegment = true;
                    continue;
                }
                final var text = syntaxHighlighter.highlight(lang, seg.text());
                // wrap at box inner width minus the code block's horizontal padding
                final var wrapped = MarkdownLines.wrapCodeWithKinds(text.lines(), Math.max(1, width - 4));
                final var wrappedText = new CompactText(wrapped.lines());
                final var boxed = codeBlock(lang);
                final var innerH = Math.max(wrappedText.rows().size(), 1);
                state.lastCodeBlock = seg.text();
                rows.addAll(wrappedText.rows());
                // boxed chunks get no row kinds (the CopyBox geometry handles their interior);
                // only the border rows are excluded from kinds and never copied anyway
                final var chunk = new RenderedChunk(
                        innerH + 2,
                        skip -> wrappedText.widget(Math.min(innerH - 1, skip), boxed, null),
                        BoxChrome.symmetric(boxed));
                out.add(chunk);
                state.contentLayout.code.put(key, new ContentPart(List.of(chunk), wrappedText.rows(), seg.text()));
                anySegment = true;
            }
        }
    }

    private void addMarkdownChunk(final List<RenderedChunk> out, final List<Line> lines, final List<String> rows) {
        addMarkdownChunk(out, lines, null, rows);
    }

    private ContentPart turnUnit(final TuiTranscriptModel.TurnSegment turn, final int width, final UiState state) {
        final var roleMarkdown = turn.user() ? userMarkdownStyles : assistantMarkdownStyles;
        final var headerLabel = !turn.labelled()
                ? ""
                : turn.label() == null || turn.label().isBlank()
                        ? (turn.user() ? state.model.userLabel() : state.model.assistantLabel())
                        : turn.label();
        final var key = new TurnUnitKey(turn, width, palette, roleMarkdown, headerLabel);
        state.contentLayout.liveTurns.add(key);
        final var laidOut = state.contentLayout.turns.get(key);
        if (laidOut != null) {
            final var admitted = turnUnitCache.get(key);
            if (admitted != null) {
                turnUnitCache.put(key, new CachedTurnUnit(admitted.part(), admitted.weight(), turnUnitFrame));
            }
            return laidOut;
        }
        final var cached = turnUnitCache.get(key);
        if (cached != null) {
            turnUnitCache.put(key, new CachedTurnUnit(cached.part(), cached.weight(), turnUnitFrame));
            state.contentLayout.turns.put(key, cached.part());
            return cached.part();
        }
        final var markerEnd = turn.labelled() ? turn.text().indexOf('\n') : -1;
        final var body = markerEnd < 0
                ? turn.text()
                : turn.text().substring(markerEnd + 1).stripLeading();
        // A single streamed answer can itself exceed the widget memo budget. Preserve
        // its committed compact prefix using exactly the renderer's fence/table/quote
        // boundary, rather than reparsing that prefix on every token delta.
        if (body.length() > 64 * 1024) {
            final var safe = transcriptRenderer.lastSafeNewline(body);
            if (safe > 0 && safe < body.length()) {
                final var prefixText = turn.text().substring(0, turn.text().length() - body.length() + safe);
                final var prefix = turnUnit(
                        new TuiTranscriptModel.TurnSegment(prefixText, turn.user(), turn.labelled(), turn.label()),
                        width,
                        state);
                final var suffix = body.substring(safe);
                final var rendered = transcriptRenderer.renderIndependent(suffix, width, Style.EMPTY, roleMarkdown);
                final var bar = turn.user() ? userBar() : outputBar();
                final var lines = new ArrayList<Line>();
                final var kinds = new ArrayList<SelectionState.RowKind>();
                final var blank = (TranscriptRenderer.boundaryBlanks(body.substring(0, safe), suffix) > 0
                                && !rendered.lines().isEmpty())
                        || (!suffix.isBlank() && rendered.lines().isEmpty());
                final var last = prefix.rows().isEmpty() ? "" : prefix.rows().getLast();
                if (blank
                        && !last.substring(Math.min(last.length(), bar.content().length()))
                                .isBlank()) {
                    lines.add(Line.from(bar));
                    kinds.add(SelectionState.RowKind.HARD);
                }
                lines.addAll(withBar(rendered.lines(), bar));
                kinds.addAll(rendered.kinds());
                if (lines.isEmpty()) {
                    return prefix;
                }
                final var tail = new ArrayList<RenderedChunk>();
                final var rows = new ArrayList<String>();
                addMarkdownChunk(tail, lines, kinds, rows);
                return new ContentPart(concat(prefix.chunks(), tail), concat(prefix.rows(), rows), null);
            }
        }
        // Keep the original miss path: the marker is drawn as a role-colored header, while
        // the complete body (including any unterminated line) goes through the same renderer.
        final var bar = turn.user() ? userBar() : outputBar();
        final var rendered = transcriptRenderer.renderRegionWithKinds(body, width, Style.EMPTY, roleMarkdown);
        final var lines = new ArrayList<>(withBar(rendered.lines(), bar));
        final var kinds = new ArrayList<>(rendered.kinds());
        if (turn.labelled()) {
            lines.add(0, Line.from(bar).append(turnHeader(turn.user(), width, headerLabel)));
            kinds.add(0, SelectionState.RowKind.HEADER);
            lines.add(0, Line.from(bar));
            kinds.add(0, SelectionState.RowKind.HARD);
        }
        final var chunks = new ArrayList<RenderedChunk>();
        final var rows = new ArrayList<String>();
        addMarkdownChunk(chunks, lines, kinds, rows);
        final var part = new ContentPart(List.copyOf(chunks), List.copyOf(rows), null);
        state.contentLayout.turns.put(key, part);
        if (turn.text().length() <= MAX_REGION_UNIT_SOURCE_CHARS
                && turnUnitCache.size() < MAX_TURN_UNIT_CACHE_ENTRIES) {
            final var weight = contentPartWeight(turn.text(), part);
            if (weight <= MAX_TURN_UNIT_CACHE_WEIGHT - turnUnitCacheWeight) {
                turnUnitCache.put(key, new CachedTurnUnit(part, weight, turnUnitFrame));
                turnUnitCacheWeight += weight;
            }
        }
        return part;
    }

    private void addMarkdownChunk(
            final List<RenderedChunk> out,
            final List<Line> lines,
            final List<SelectionState.RowKind> kinds,
            final List<String> rows) {
        final var innerH = Math.max(lines.size(), 1);
        final var text = new CompactText(lines);
        rows.addAll(text.rows());
        out.add(new RenderedChunk(
                innerH,
                skip -> text.widget(Math.min(innerH - 1, skip), null, null),
                null,
                kinds == null || kinds.isEmpty() ? null : kinds.toArray(SelectionState.RowKind[]::new)));
    }

    /** Concatenated span contents of a line (used for transcript search). */
    private static String plainText(final Line line) {
        final var sb = new StringBuilder();
        for (final var span : line.spans()) {
            sb.append(span.content());
        }
        return sb.toString();
    }

    /**
     * Like {@link #addMarkdownChunk} but paints the thought panel: a subtle full-width
     * info background tint (pre-blended solid color - terminals have no alpha) that
     * categorically separates reasoning from conversation output.
     */
    private void addThoughtChunk(final List<RenderedChunk> out, final List<Line> lines, final List<String> rows) {
        final var innerH = Math.max(lines.size(), 1);
        final var text = new CompactText(lines);
        rows.addAll(text.rows());
        out.add(new RenderedChunk(innerH, skip -> text.widget(Math.min(innerH - 1, skip), null, thoughtBg), null));
    }

    /**
     * Renders a finished-tool run as one bordered, collapsible group. In {@code HIDDEN}
     * mode nothing is rendered; the whole run (header + members) lives inside a single
     * rounded {@link Block} so the grouping reads like a container again, with the header
     * as the block title. The default state follows the current output mode
     * ({@code SHOWN} open, {@code COLLAPSED} collapsed) unless the user toggled this run
     * explicitly ({@link UiState#runCollapsed}): a collapsed run renders only the header
     * row (chevron + summary), an expanded run renders the members below it (one-liners
     * under {@code COLLAPSED}, full cards under {@code SHOWN}). Clicking the header flips
     * the per-run override, which now visibly shows/hides the member lines in every mode.
     */
    private void addToolRunChunk(
            final List<RenderedChunk> out,
            final ToolRun run,
            final int width,
            final UiState state,
            final List<UiState.ToolTop> toolTops,
            final List<UiState.FooterLink> footerLinks,
            final List<UiState.RunHeader> runHeaders,
            final List<String> rows) {
        if (run == null || run.members().isEmpty()) {
            return;
        }
        final var outputs = new LinkedHashMap<Long, ToolOutputStore.Entry>();
        final var collapsed = new HashSet<Long>();
        for (final var member : run.members()) {
            final var ordinal = member.card().ordinal();
            final var entry = state.toolOutputs.get(ordinal);
            if (entry != null) {
                outputs.put(ordinal, entry);
            }
            if (state.collapsedTools.contains(ordinal)) {
                collapsed.add(ordinal);
            }
        }
        final var key = new ToolUnitKey(
                run.members(),
                outputs,
                width,
                palette,
                state.showToolActions,
                state.diffViewMode,
                state.toolOutputs.revision(),
                state.runCollapsed.getOrDefault(run.id(), state.showToolActions == ToolOutputVisibility.COLLAPSED),
                collapsed);
        state.contentLayout.liveTools.add(key);
        var part = state.contentLayout.tools.get(key);
        if (part == null
                || run.members().stream().anyMatch(member -> member.card().ordinal() < 0)) {
            final var chunks = new ArrayList<RenderedChunk>();
            final var localRows = new ArrayList<String>();
            final var tops = new ArrayList<UiState.ToolTop>();
            final var footers = new ArrayList<UiState.FooterLink>();
            final var headers = new ArrayList<UiState.RunHeader>();
            renderToolRunChunk(chunks, run, width, state, tops, footers, headers, localRows);
            part = new ToolPart(
                    new ContentPart(List.copyOf(chunks), List.copyOf(localRows), null),
                    List.copyOf(tops),
                    List.copyOf(footers),
                    List.copyOf(headers));
            state.contentLayout.tools.put(key, part);
        }
        final var top = out.stream().mapToInt(RenderedChunk::height).sum();
        appendPart(out, rows, part.content());
        for (final var anchor : part.tops()) {
            toolTops.add(
                    new UiState.ToolTop(anchor.ordinal(), top + anchor.row(), anchor.runMember(), anchor.bordered()));
        }
        for (final var anchor : part.footers()) {
            footerLinks.add(new UiState.FooterLink(anchor.ordinal(), top + anchor.row()));
        }
        for (final var anchor : part.headers()) {
            final var rect = anchor.header();
            runHeaders.add(new UiState.RunHeader(
                    anchor.id(), new Rect(rect.x(), top + rect.y(), rect.width(), rect.height()), top + anchor.row()));
        }
    }

    private void renderToolRunChunk(
            final List<RenderedChunk> out,
            final ToolRun run,
            final int width,
            final UiState state,
            final List<UiState.ToolTop> toolTops,
            final List<UiState.FooterLink> footerLinks,
            final List<UiState.RunHeader> runHeaders,
            final List<String> rows) {
        if (state.showToolActions == ToolOutputVisibility.HIDDEN
                || run == null
                || run.members().isEmpty()) {
            return;
        }
        if (run.size() == 1) {
            // a lone tool keeps its historical rendering (no run header): the grouping is
            // only meaningful for bursts of consecutive calls
            final var member = run.members().get(0);
            addToolCardChunk(out, member.regionText(), width, state, toolTops, footerLinks, rows);
            return;
        }
        final var collapsed = runCollapsedState(run, state);
        final int top = out.stream().mapToInt(RenderedChunk::height).sum();
        // the whole run is ONE chunk: the box plus every inner line (header first). The
        // collapsed/expanded content differs visually in every mode - collapsing shows only
        // the header row, expanding reveals the member lines under it - so the toggle is
        // always observable, unlike the previous "one dim line per member in both states"
        // dead end where the chevron flipped state but the frame was byte-identical.
        // Row accounting: absolute row 0 of the chunk is the box top border, so the first
        // content row is at top+1 and the chunk height is inner lines + 2 (borders).
        final var innerW = Math.max(1, width - 2);
        final var lines = new ArrayList<Line>();
        lines.add(runHeaderLine(run, collapsed));
        rows.add(plainText(lines.get(0)));
        runHeaders.add(new UiState.RunHeader(run.id(), new Rect(0, top + 1, width, 1), top + 1));
        var y = top + 2;
        if (!collapsed && state.showToolActions == ToolOutputVisibility.COLLAPSED) {
            // opened under COLLAPSED mode: one dim line per member, indented under the
            // header. Every member line is its own click target opening the full output
            // overlay (only the run header keeps the collapse/expand toggle).
            for (final var member : run.members()) {
                final var card = member.card();
                final var line = indentLine(hiddenToolLine(card), "  ");
                lines.add(line);
                rows.add(plainText(line));
                if (card.ordinal() >= 0) {
                    toolTops.add(new UiState.ToolTop(card.ordinal(), y, true, false));
                }
                y++;
                for (final var diffLine : toolDiffLines(card, state, Math.max(1, innerW - 2))) {
                    final var indented = indentLine(diffLine, "  ");
                    lines.add(indented);
                    rows.add(plainText(indented));
                    y++;
                }
            }
        } else if (!collapsed && state.showToolActions == ToolOutputVisibility.SHOWN) {
            // SHOWN mode with an open run: the member cards render inside the box, as plain
            // indented lines under the header (no nested bordered blocks: one outer container
            // reads as the group, each member keeps its icon + command + output preview).
            for (final var member : run.members()) {
                final var card = member.card();
                var first = true;
                for (final var cardLine : toolRunCardLines(card, Math.max(1, innerW - 2), state)) {
                    final var line = indentLine(cardLine, "  ");
                    lines.add(line);
                    rows.add(plainText(line));
                    if (first && card.ordinal() >= 0) {
                        // anchor the card on its header row: clicking it opens the full-output overlay
                        toolTops.add(new UiState.ToolTop(card.ordinal(), y, true, false));
                    }
                    first = false;
                    y++;
                }
            }
        }
        if (collapsed) {
            for (final var member : run.members()) {
                for (final var diffLine : toolDiffLines(member.card(), state, Math.max(1, innerW - 2))) {
                    final var line = indentLine(diffLine, "  ");
                    lines.add(line);
                    rows.add(plainText(line));
                }
            }
        }
        final var text = new CompactText(lines);
        final var innerH = Math.max(text.rows().size(), 1);
        final var box = runBlock(run);
        out.add(new RenderedChunk(
                innerH + 2, skip -> text.widget(Math.min(innerH - 1, skip), box, null), BoxChrome.symmetric(box)));
    }

    /**
     * The inner lines of one member card rendered inside an expanded run box (SHOWN mode):
     * header (icon + name + ordinal + args) then the short output preview, so the container
     * keeps the compact card feel without nesting a second bordered box. No footer link here:
     * the whole card header is clickable and opens the full-output overlay.
     */
    private List<Line> toolRunCardLines(final ToolCard card, final int innerW, final UiState state) {
        final var statusColor =
                palette.color(card.pending() ? palette.warning() : card.failed() ? palette.error() : palette.success());
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var lines = new ArrayList<Line>();
        final var headerSpans = new ArrayList<Span>();
        headerSpans.add(Span.styled(
                (card.pending()
                                ? glyphs.running()
                                : card.failed() ? glyphs.cross() : card.running() ? glyphs.running() : glyphs.check())
                        + " ",
                Style.EMPTY.fg(statusColor).bold()));
        headerSpans.add(Span.styled(card.name(), Style.EMPTY.bold().fg(Color.hex(palette.text()))));
        if (card.ordinal() >= 0) {
            headerSpans.add(Span.styled(" #" + card.ordinal(), muted));
        }
        if (!card.args().isBlank()) {
            headerSpans.add(Span.styled((card.pending() || card.failed() ? "  " : " ") + card.args(), muted));
        }
        lines.add(Line.from(headerSpans.toArray(Span[]::new)));
        lines.addAll(toolDiffLines(card, state, innerW));
        if (!card.output().isBlank() && !card.isDiff(diffRenderer)) {
            final var outputLines = toolBodyLines(
                    card.name(),
                    card.keyCommand(),
                    null,
                    card.output(),
                    innerW,
                    card.isDiff(diffRenderer),
                    MAX_INLINE_TOOL_LINES + 1,
                    state.diffViewMode);
            lines.addAll(new ArrayList<>(outputLines.subList(0, Math.min(outputLines.size(), MAX_INLINE_TOOL_LINES))));
        }
        return lines;
    }

    /** True when a run is collapsed: explicit override first, else the mode default. */
    private boolean runCollapsedState(final ToolRun run, final UiState state) {
        return state.runCollapsed.getOrDefault(run.id(), state.showToolActions == ToolOutputVisibility.COLLAPSED);
    }

    /**
     * The box wrapping a whole tool run (header + members): rounded borders tinted with
     * the run status (error when any member failed, success otherwise) over the
     * transcript background - no interior fill, unlike single tool cards, so the group
     * reads as one container instead of a bare header over loose children without
     * adding a second tinted plane to the transcript. No title: the first content row
     * (chevron + summary) stays the run's clickable header, exactly like a lone card.
     */
    private Block runBlock(final ToolRun run) {
        return Block.builder()
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(palette.color(run.failures() > 0 ? palette.error() : palette.success()))
                .build();
    }

    /** The always-visible run header row (first content line inside the run box): chevron + summary + details. */
    private Line runHeaderLine(final ToolRun run, final boolean collapsed) {
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var chevron = collapsed ? "▶ " : "▼ ";
        final var spans = new ArrayList<Span>();
        spans.add(
                Span.styled(chevron, Style.EMPTY.fg(Color.hex(palette.accent())).bold()));
        spans.add(Span.styled(summary(run), muted));
        final var failures = run.failures();
        if (failures > 0) {
            spans.add(Span.styled(
                    " · " + failures + " failed",
                    Style.EMPTY.fg(Color.hex(palette.error())).bold()));
        }
        spans.add(Span.styled(" · " + DurationFormat.humanize(run.elapsedMs()), muted));
        return Line.from(spans.toArray(Span[]::new));
    }

    /** Short run summary: {@code 5 tools · grep ×3 · read ×2}. */
    private String summary(final ToolRun run) {
        final var counts = run.commandCounts();
        final var sb = new StringBuilder(run.size() == 1 ? "1 tool" : run.size() + " tools");
        counts.forEach((cmd, n) -> sb.append(" · ").append(cmd).append(n > 1 ? " ×" + n : ""));
        return sb.toString();
    }

    /** Indents every line of a {@link Line} by the given prefix. */
    private Line indentLine(final Line line, final String prefix) {
        final var spans = line.spans();
        final var out = new ArrayList<Span>(spans.size() + 1);
        out.add(Span.styled(prefix, Style.EMPTY));
        out.addAll(spans);
        return Line.from(out.toArray(Span[]::new));
    }

    /** One-row in-pane "thinking..." activity line shown while a reasoning round runs. */
    private void addThinkingIndicator(final List<RenderedChunk> out, final UiState state, final List<String> rows) {
        final var secs = state.thinkingStartedAtMillis < 0
                ? 0
                : Math.max(0, (clock.millis() - state.thinkingStartedAtMillis) / 1000);
        final var line =
                Line.from(thoughtBar(), Span.styled(spinnerPrefix(state) + BusyLabels.thinking(secs), thoughtBarStyle));
        addThoughtChunk(out, List.of(line), rows);
    }

    /** Renders a finished tool action as a bordered "card" with a status icon and diff-aware output. */
    private void addToolCardChunk(
            final List<RenderedChunk> out,
            final String regionText,
            final int width,
            final UiState state,
            final List<UiState.ToolTop> toolTops,
            final List<UiState.FooterLink> footerLinks,
            final List<String> rows) {
        var card = ToolCard.parse(regionText);
        card = card.withCapturedStatus(state.toolOutputs.get(card.ordinal()));
        if (card.running() && card.ordinal() < 0) {
            // first render before a (#n) header exists: resolve from the started-not-
            // finished FIFO so the live card is still anchored (clickable overlay).
            // the card name is the command when the tool carries one (bash) and the
            // tool name otherwise (read); the FIFO holds both keys
            final var ordinals = state.pendingToolOrdinals.get(card.name());
            if (ordinals != null && !ordinals.isEmpty()) {
                card = new ToolCard(
                        card.name(), card.args(), card.output(), false, false, 0, true, ordinals.peekLast());
            }
        }
        if (state.showToolActions == ToolOutputVisibility.HIDDEN) {
            // hidden mode (Ctrl+Y): render nothing at all for tool blocks
            return;
        }
        final var collapsed = card.ordinal() >= 0 && state.collapsedTools.contains(card.ordinal());
        if (state.showToolActions == ToolOutputVisibility.COLLAPSED || collapsed) {
            // collapsed mode (Ctrl+Y) or per-card collapse: keep a single dim summary line
            final var top = out.stream().mapToInt(RenderedChunk::height).sum();
            // the running card is clickable too: its ordinal is stamped by the runner
            // (or resolved by the renderer), so the running-tool overlay opens on click
            toolTops.add(new UiState.ToolTop(card.ordinal(), top, false, false));
            final var line = hiddenToolLine(card);
            rows.add(plainText(line));
            final var compactLines = new ArrayList<Line>();
            compactLines.add(line);
            compactLines.addAll(toolDiffLines(card, state, Math.max(1, width)));
            final var compact = new CompactText(compactLines);
            rows.addAll(compactLines.subList(1, compactLines.size()).stream()
                    .map(TuiCommand::plainText)
                    .toList());
            out.add(new RenderedChunk(compactLines.size(), skip -> compact.widget(skip, null, null), null));
            return;
        }
        final var top = out.stream().mapToInt(RenderedChunk::height).sum();
        // lone bordered card: the header TEXT row is at top+1 (the block reserves its own
        // top-border row), so anchor there - stamping `top` put the 1-row click target on
        // the border row and clicks on the visible header text missed the anchor entirely.
        // `bordered` also makes the renderer extend the clickable strip over both rows.
        toolTops.add(new UiState.ToolTop(card.ordinal(), top + 1, false, true));
        final var statusColor =
                palette.color(card.pending() ? palette.warning() : card.failed() ? palette.error() : palette.success());
        final var blockBuilder = Block.builder()
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(statusColor)
                .title(blockTitle(card));
        if (toolBg != null) {
            blockBuilder.background(toolBg);
        }
        final var block = blockBuilder.build();
        final var innerW = Math.max(1, width - 2);
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var lines = new ArrayList<Line>();
        final var headerSpans = new ArrayList<Span>();
        final var icon = card.pending()
                ? glyphs.running()
                : card.failed() ? glyphs.cross() : card.running() ? glyphs.running() : glyphs.check();
        headerSpans.add(Span.styled(icon + " ", Style.EMPTY.fg(statusColor).bold()));
        headerSpans.add(Span.styled(card.name(), Style.EMPTY.bold().fg(Color.hex(palette.text()))));
        if (card.ordinal() >= 0) {
            headerSpans.add(Span.styled(" #" + card.ordinal(), muted));
        }
        if (!card.args().isBlank()) {
            headerSpans.add(Span.styled((card.pending() || card.failed() ? "  " : " ") + card.args(), muted));
        }
        lines.add(Line.from(headerSpans.toArray(Span[]::new)));
        final var full = card.ordinal() >= 0 ? state.toolOutputs.get(card.ordinal()) : null;
        final var diffs = toolDiffLines(card, state, innerW);
        lines.addAll(diffs);
        rows.addAll(diffs.stream().map(TuiCommand::plainText).toList());
        if (!card.output().isBlank() && !card.isDiff(diffRenderer)) {
            lines.add(Line.empty());
            // a running bash card repeats the command in two places: its header carries it
            // as the card name (the "<command> running" header), and BashTool also streams
            // "$ <command>" as the first body line - drop that echo so the body does not
            // repeat what the header already shows, mirroring the Ctrl+O overlay fix
            var body = card.output();
            if (card.running() && body != null) {
                final var rawCommand = full != null ? commandArgOf(full.args()) : card.name();
                body = stripLeadingCommandEcho(body, rawCommand);
            }
            // ask_user answers read better as key: value pairs than raw JSON
            var outputLines = toolBodyLines(
                    card.name(),
                    card.keyCommand(),
                    null,
                    body,
                    innerW,
                    card.isDiff(diffRenderer),
                    MAX_INLINE_TOOL_LINES + 1,
                    state.diffViewMode);
            // keep the card compact: a short inline preview, the rest lives behind
            // the "click to expand" footer / Ctrl+O overlay
            final var clipped = outputLines.size() > MAX_INLINE_TOOL_LINES;
            if (clipped) {
                outputLines = new ArrayList<>(outputLines.subList(0, MAX_INLINE_TOOL_LINES));
            }
            for (final var line : outputLines) {
                rows.add(plainText(line));
            }
            lines.addAll(outputLines);
            // point at the full output when the preview was cut (line cap or transcript cap)
            final var transcriptCapped =
                    full != null && full.output().length() > card.output().length();
            if (clipped || transcriptCapped) {
                // link-styled footer: click it (or Ctrl+O) to expand the full output
                lines.add(Line.from(Span.styled(
                        glyphs.more() + " click to expand full output (Ctrl+O) ",
                        Style.EMPTY.fg(Color.hex(palette.accent())).underlined())));
                rows.add(glyphs.more() + " click to expand full output (Ctrl+O) ");
                // content starts one row below the card top border
                footerLinks.add(new UiState.FooterLink(card.ordinal(), top + 1 + lines.size() - 1));
            }
        }
        final var text = new CompactText(lines);
        final var innerH = Math.max(text.rows().size(), 1);
        out.add(new RenderedChunk(
                innerH + 2, skip -> text.widget(Math.min(innerH - 1, skip), block, null), BoxChrome.symmetric(block)));
    }

    private List<Line> toolDiffLines(final ToolCard card, final UiState state, final int width) {
        final var files = state.toolOutputs.files(card.ordinal());
        if (!files.isEmpty()) {
            return appliedDiffLines(files, width, state.diffViewMode);
        }
        final var entry = state.toolOutputs.get(card.ordinal());
        if (entry != null && ("edit_file".equals(entry.name()) || "apply_patch".equals(entry.name()))) {
            final var preview =
                    toolArgsPreview.diffPreview(entry.name(), entry.args(), width, palette, state.diffViewMode);
            return preview == null ? List.of() : preview;
        }
        if (card.isDiff(diffRenderer)) {
            final var lines = new ArrayList<Line>();
            lines.add(Line.from(Span.styled("Result diff", palette.bold(palette.primary()))));
            lines.addAll(diffRenderer.render(
                    card.output(),
                    width,
                    palette,
                    state.diffViewMode,
                    syntaxHighlighter,
                    diffLanguage(card.keyCommand())));
            return lines;
        }
        return List.of();
    }

    private List<Line> appliedDiffLines(final List<FileMutation> files, final int width, final DiffViewMode mode) {
        final var lines = new ArrayList<Line>();
        lines.add(Line.from(Span.styled("Applied changes", palette.bold(palette.primary()))));
        for (final var file : files) {
            lines.add(Line.from(Span.styled(file.kind().name() + " " + file.path(), palette.muted())));
            final var content = file.content();
            if (content == null) {
                lines.add(Line.from(Span.styled("Exact applied diff unavailable", palette.muted())));
                continue;
            }
            final var before = content.oldText() == null ? "" : content.oldText();
            final var after = content.newText();
            final var patch = new StringBuilder("--- ")
                    .append(file.kind() == FileMutation.Kind.CREATE ? "/dev/null" : "a/" + file.path())
                    .append("\n+++ ")
                    .append(file.kind() == FileMutation.Kind.DELETE ? "/dev/null" : "b/" + file.path())
                    .append('\n')
                    .append(String.join("\n", DiffLayout.between(before, after)));
            lines.addAll(diffRenderer.render(
                    patch.toString(),
                    width,
                    palette,
                    mode,
                    syntaxHighlighter,
                    SyntaxHighlighter.guessLanguage(file.path().toString())));
        }
        return lines;
    }

    private static String diffLanguage(final String path) {
        return path == null ? null : SyntaxHighlighter.guessLanguage(path);
    }

    private String blockTitle(final ToolCard card) {
        if (card.pending()) {
            return " pending ";
        }
        if (card.failed()) {
            return " failed ";
        }
        if (card.running()) {
            return " running ";
        }
        final var duration = card.elapsedMs() > 0 ? DurationFormat.humanize(card.elapsedMs()) : "done";
        return card.ordinal() >= 0 ? " #" + card.ordinal() + " - " + duration + " " : " " + duration + " ";
    }

    /** Single dim summary line shown for a tool action when tool blocks are hidden. */
    private Line hiddenToolLine(final ToolCard card) {
        return hiddenToolLine(card, palette, glyphs);
    }

    /**
     * Pure form of {@link #hiddenToolLine(ToolCard)} so the collapsed-run rendering
     * can be asserted without a live TUI.
     */
    static String hiddenToolLineText(final ToolCard card, final Palette palette, final Glyphs glyphs) {
        return hiddenToolLine(card, palette, glyphs).spans().stream()
                .map(Span::content)
                .collect(joining());
    }

    /** Internal shared implementation of the collapsed one-liner. */
    static Line hiddenToolLine(final ToolCard card, final Palette palette, final Glyphs glyphs) {
        final var statusColor =
                palette.color(card.pending() ? palette.warning() : card.failed() ? palette.error() : palette.success());
        final var label = new StringBuilder();
        if (card.running()) {
            // live cards carry the raw command as name: show its significant part
            label.append(condensedCommand(card));
        } else {
            label.append(card.name());
            if (card.ordinal() >= 0) {
                label.append(" #").append(card.ordinal());
            }
            final var key = condensedCommand(card);
            if (!key.isBlank()) {
                label.append(" - ").append(key);
            }
        }
        if (card.pending()) {
            label.append(" - pending");
        } else if (card.running()) {
            label.append(" - running...");
        } else if (card.elapsedMs() > 0) {
            label.append(" - ").append(DurationFormat.humanize(card.elapsedMs()));
        }
        // a pending tool is not a failure: no "- failed" suffix, neutral glyph
        if (card.pending()) {
            // keep neutral, no suffix
        } else if (card.failed()) {
            label.append(" - failed");
        }
        return Line.from(
                Span.styled(
                        (card.pending()
                                        ? glyphs.running()
                                        : card.failed()
                                                ? glyphs.cross()
                                                : card.running() ? glyphs.running() : glyphs.check())
                                + " ",
                        Style.EMPTY.fg(statusColor).bold()),
                Span.styled(label.toString(), Style.EMPTY.fg(Color.hex(palette.textMuted()))));
    }

    /** Pure, static form of {@link #condensedCommand(ToolCard)}. */
    static String condensedCommand(final ToolCard card) {
        if (!card.running()
                && !card.failed()
                && ("read".equals(card.name())
                        || "write_file".equals(card.name())
                        || "edit_file".equals(card.name()))) {
            return card.keyCommand(TuiCommand::shortenToRelative);
        }
        return card.keyCommand();
    }

    /** Converts an absolute path under the working directory into a cwd-relative one; passthrough otherwise. */
    private static String shortenToRelative(final String path) {
        try {
            final var cwd = Path.of(WORKING_DIRECTORY).toAbsolutePath().normalize();
            final var candidate = Path.of(path).toAbsolutePath().normalize();
            if (candidate.startsWith(cwd)) {
                return cwd.relativize(candidate).toString();
            }
        } catch (final RuntimeException e) {
            // not a path: keep the raw significant command
        }
        return path;
    }

    private List<Line> toolOutputLines(final String output, final int innerW, final boolean diff) {
        final var add = Style.EMPTY.fg(Color.hex(palette.success()));
        final var del = Style.EMPTY.fg(Color.hex(palette.error()));
        final var meta = Style.EMPTY.fg(Color.hex(palette.accent()));
        final var styled = new ArrayList<Line>();
        for (final var raw : output.split("\n")) {
            final var trimmed = raw.stripLeading();
            final Style style;
            if (diff && trimmed.startsWith("+")) {
                style = add;
            } else if (diff && trimmed.startsWith("-")) {
                style = del;
            } else if (diff && trimmed.startsWith("@@")) {
                style = meta;
            } else {
                style = toolStyle;
            }
            styled.add(Line.from(Span.styled(raw, style)));
        }
        return MarkdownLines.wrapCode(styled, innerW);
    }

    private Block codeBlock(final String lang) {
        return Block.builder()
                .title(lang == null || lang.isBlank() ? "" : " " + lang + " ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(palette.color(palette.border()))
                .padding(Padding.symmetric(0, 2))
                .build();
    }

    /**
     * Bold accent bar prefixing assistant output lines so the main thread of the
     * conversation reads distinctly from user turns (primary bar), thoughts (thin info
     * bar) and tool cards (status-colored borders).
     */
    private Span outputBar() {
        return Span.styled(glyphs.bar() + " ", palette.bold(palette.accent()));
    }

    /** Bold primary bar prefixing user ("You") turns. */
    private Span userBar() {
        return Span.styled(glyphs.bar() + " ", palette.bold(palette.primary()));
    }

    /**
     * Thin bar prefixing reasoning/thought lines, visually secondary to the output bars:
     * clearly visible info hue (mono: plain) so the thought panel reads as one block.
     */
    private Span thoughtBar() {
        return Span.styled(glyphs.thinBar() + " ", thoughtBarStyle);
    }

    /**
     * Header line opening a speaker turn: a role-colored label followed by a rule running
     * to the panel edge. The rule is what makes the block boundary survive scrolling back
     * through a long transcript - a bare label at the text indent reads as another line of
     * prose. Both spans share the role color so the header reads as one unit; the role
     * glyph survives only in mono terminals as a shape fallback (colors collapse to the
     * terminal default, so the bar and the rule would no longer carry the role).
     *
     * @param user {@code true} for a user turn, {@code false} for an assistant turn
     * @param width content width available to the header (the role bar is already excluded)
     * @param label the speaker label to display, e.g. {@code [10:11:26] rmannibucau}
     */
    private Line turnHeader(final boolean user, final int width, final String label) {
        final var color = user ? palette.primary() : palette.accent();
        final var head = (palette.mono() ? glyphs.roleMark(user) + " " : "") + label;
        // the rule fills whatever the label left, never overflowing the panel
        final var rule = glyphs.rule().repeat(Math.max(0, width - head.length() - 1));
        return Line.from(Span.styled(head, palette.bold(color)), Span.styled(" " + rule, palette.style(color)));
    }

    private List<Line> withBar(final List<Line> lines, final Span bar) {
        final var out = new ArrayList<Line>(lines.size());
        for (final var line : lines) {
            out.add(line.isEmpty() ? Line.from(bar) : Line.from(bar).append(line));
        }
        return out;
    }

    private void renderTodos(final Rect rect, final Frame frame, final UiState state, final String todosRender) {
        final var block = Block.builder()
                .title(" todos " + (state.showTodosCollapsed ? " (Ctrl+P to expand) " : " (Ctrl+P to collapse) "))
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(palette.style(palette.accent()))
                .build();
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        final var render = todosRender;
        final var text = Text.from(render.isBlank() || "(empty)".equals(render) ? "(no todos)" : render);
        final var overflow = Math.max(0, text.height() - inner.height());
        frame.renderWidget(Paragraph.builder().text(text).scroll(overflow).build(), inner);
    }

    private void renderInput(final Rect rect, final Frame frame, final UiState state) {
        final var withGutter = Layout.horizontal()
                .constraints(Constraint.length(2), Constraint.fill())
                .split(rect);
        frame.renderWidget(modeGutter(), withGutter.get(0));
        final var block = activeBlock(" prompt ");
        frame.renderWidget(block, withGutter.get(1));
        final var inner = block.inner(withGutter.get(1));
        state.inputZone = inner;
        if (inner.isEmpty()) {
            return;
        }
        final var width = inner.width();
        if (state.input.text().isEmpty()) {
            final var style = Style.EMPTY.fg(Color.hex(palette.textMuted()));
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(
                                    state.model.busy() ? "waiting for the agent..." : "type a prompt, Enter to send",
                                    style))))
                            .overflow(Overflow.CLIP)
                            .build(),
                    inner);
            return;
        }
        // soft-wrap each logical line to display width; vertical rows follow the
        // wrapped fragments so long pasted lines grow the text area instead of
        // scrolling horizontally
        final var visual = new ArrayList<Line>();
        final var cursor = new int[] {-1, -1};
        for (var row = 0; row < state.input.lineCount(); row++) {
            final var fragment = wrappedFragments(state.input.getLine(row), width);
            // cursorCol is relative to this row's logical line, so the wrapped
            // fragment offsets reset at each row; track the char offset accumulated
            // so far to place the reversed-cursor on the fragment that actually
            // holds the logical column
            var charStart = 0;
            for (var f = 0; f < fragment.size(); f++) {
                final var frag = fragment.get(f);
                visual.add(Line.from(Span.styled(frag, textInputStyle())));
                if (row == state.input.cursorRow() && cursor[0] < 0) {
                    final var col = state.input.cursorCol();
                    final var fragEnd = charStart + frag.length();
                    if (col <= fragEnd || f == fragment.size() - 1) {
                        cursor[0] = visual.size() - 1;
                        final var within = Math.max(0, Math.min(col, fragEnd) - charStart);
                        // the caret column is relative to the fragment: each wrapped
                        // fragment is rendered as a new visual line at the same left
                        // edge, so the accumulated display offset must not shift it
                        cursor[1] = CharWidth.of(frag.substring(0, within));
                    }
                    charStart = fragEnd;
                }
            }
        }
        final var visible = inner.height();
        // keep the cursor row within the viewport: once the prompt grows past
        // the input height (multi-line or soft-wrapped), scroll follows the caret
        // so the line being edited is never clipped out of view (see inputFollowScroll)
        final var scroll = inputFollowScroll(state, width, visible);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(visual.stream().skip(scroll).toList().toArray(Line[]::new)))
                        .overflow(Overflow.CLIP)
                        .build(),
                inner);
        // cursor: within-fragment column, clamped to width; a caret at the very end
        // of a full fragment (col == width) lands on the padding cell next to the
        // border and stays visible
        final var rel = cursor[0] - scroll;
        if (rel >= 0 && rel < visible && cursor[1] <= width) {
            frame.buffer().setStyle(new Rect(inner.x() + cursor[1], inner.y() + rel, 1, 1), Style.EMPTY.reversed());
        }
    }

    private Style textInputStyle() {
        return Style.EMPTY.fg(Color.hex(palette.text()));
    }

    private static List<String> wrappedFragments(final String line, final int width) {
        if (width <= 0 || line.isEmpty()) {
            return List.of(line);
        }
        final var out = new ArrayList<String>();
        var rem = line;
        while (CharWidth.of(rem) > width) {
            final var piece = CharWidth.substringByWidth(rem, width);
            out.add(piece);
            rem = rem.substring(CharWidth.of(piece) == 0 ? 1 : piece.length());
        }
        out.add(rem);
        return out;
    }

    /** Number of visual (wrapped) rows the current input needs for the given inner width. */
    private static int visualRows(final TextAreaState area, final int width) {
        if (width <= 0) {
            return Math.max(1, area.lineCount());
        }
        var rows = 0;
        for (var i = 0; i < area.lineCount(); i++) {
            rows += wrappedFragments(area.getLine(i), width).size();
        }
        return Math.max(1, rows);
    }

    /** One soft-wrapped fragment of the input buffer plus its position in the logical line. */
    private record VisualRow(int row, int charStart, int displayStart, String fragment) {}

    /** Absolute buffer position of the edit caret after a click inside the prompt input. */
    private record CaretAt(int row, int col) {}

    /**
     * Flattens the input buffer into its visual rows, one entry per soft-wrapped
     * fragment (in rendering order), so click mapping and vertical caret motion
     * use the exact same geometry as {@link #renderInput(Rect, Frame, UiState)}.
     */
    private static List<VisualRow> visualGrid(final TextAreaState area, final int width) {
        final var grid = new ArrayList<VisualRow>();
        for (var row = 0; row < area.lineCount(); row++) {
            final var fragments = wrappedFragments(area.getLine(row), width);
            var charStart = 0;
            var displayStart = 0;
            for (final var fragment : fragments) {
                grid.add(new VisualRow(row, charStart, displayStart, fragment));
                charStart += fragment.length();
                displayStart += CharWidth.of(fragment);
            }
        }
        return grid;
    }

    /**
     * Global visual-row index of the fragment holding the caret, using the same
     * {@code col <= fragEnd || last fragment} boundary rule as the renderer.
     */
    private static int visualRowOfCaret(final TextAreaState area, final int width) {
        var visual = 0;
        for (var row = 0; row < area.lineCount(); row++) {
            final var line = area.getLine(row);
            final var fragments = wrappedFragments(line, width);
            if (row == area.cursorRow()) {
                final var col = area.cursorCol();
                var start = 0;
                for (var f = 0; f < fragments.size(); f++) {
                    if (col <= start + fragments.get(f).length() || f == fragments.size() - 1) {
                        return visual + f;
                    }
                    start += fragments.get(f).length();
                }
                return visual;
            }
            visual += fragments.size();
        }
        return visual;
    }

    /** Display column of the caret measured from the start of its logical line. */
    private static int caretDisplayCol(final TextAreaState area) {
        final var line = area.getLine(area.cursorRow());
        return CharWidth.of(line.substring(0, Math.min(area.cursorCol(), line.length())));
    }

    /**
     * Resolves the vertical scroll offset to render for the prompt input.
     * <p>
     * In <em>follow</em> mode (the default) it keeps the caret's visual row inside
     * the viewport regardless of how long the prompt grows, so the line being typed
     * is never clipped out of view once the input exceeds its height (multi-line or
     * soft-wrapped content). The effective scroll is stored back into
     * {@code state.inputScroll} so a later manual scroll starts from a coherent
     * position.
     * <p>
     * When {@code state.inputUserScrolled} is set (the user scrolled the input
     * manually via the mouse wheel) the persisted anchor is returned verbatim - the
     * caret may sit above the viewport, exactly like scrolling a chat transcript.
     * The next edit or caret move re-arms follow mode.
     * <p>
     * Both {@link #renderInput(Rect, Frame, UiState)} and
     * {@link #inputCaretAt(UiState, int, int)} resolve through here so the drawn
     * content and the click mapping always use the same geometry.
     */
    private static int inputFollowScroll(final UiState state, final int width, final int visible) {
        final var area = state.input;
        final int maxScroll = Math.max(0, visualRows(area, width) - visible);
        if (state.inputUserScrolled) {
            return Math.clamp(state.inputScroll, 0, maxScroll);
        }
        final int caretVisual = visualRowOfCaret(area, width);
        var scroll = Math.clamp(state.inputScroll, 0, maxScroll);
        if (caretVisual >= 0) {
            if (caretVisual < scroll) {
                scroll = caretVisual;
            } else if (caretVisual >= scroll + visible) {
                scroll = caretVisual - visible + 1;
            }
        }
        scroll = Math.clamp(scroll, 0, maxScroll);
        state.inputScroll = scroll;
        return scroll;
    }

    /**
     * Re-arms follow mode for the prompt input after a manual scroll: called on any
     * text edit ({@code beforeEdit}) and caret navigation so the caret is brought
     * back into view the next time it is rendered.
     */
    private static void inputArmFollow(final UiState state) {
        state.inputUserScrolled = false;
    }

    /**
     * Scrolls the prompt input by {@code delta} visual rows (positive = down) and
     * latches manual-scroll mode (used by the mouse wheel over the input). Reaching
     * the very bottom turns follow mode back on so the caret re-appears, matching the
     * transcript's behaviour.
     */
    private static void inputScrollBy(final UiState state, final int delta, final int width, final int visible) {
        final int maxScroll = Math.max(0, visualRows(state.input, width) - visible);
        if (maxScroll <= 0) {
            return;
        }
        state.inputScroll = Math.clamp(state.inputScroll + delta, 0, maxScroll);
        state.inputUserScrolled = !(delta > 0 && state.inputScroll >= maxScroll);
    }

    /**
     * Moves the caret to the given visual row, preserving the current display
     * column (clamped to the target fragment, like a GUI editor).
     */
    private static void moveCursorToVisual(final UiState state, final List<VisualRow> grid, final int targetVisual) {
        final var target = grid.get(targetVisual);
        final var dc = caretDisplayCol(state.input);
        final var within =
                Math.max(0, TuiInputSupport.columnAtDisplayWidth(target.fragment(), dc - target.displayStart()));
        applyCursor(
                state,
                target.row(),
                target.charStart() + Math.min(within, target.fragment().length()));
    }

    /** Maps a screen point inside the input zone to the absolute (row, col) of the edit caret. */
    private static CaretAt inputCaretAt(final UiState state, final int mx, final int my) {
        final var inner = state.inputZone;
        final var width = inner.width();
        final var grid = visualGrid(state.input, width);
        final var visible = inner.height();
        // same follow-last logic as renderInput so a click maps to the same geometry that is drawn
        final var scroll = inputFollowScroll(state, width, visible);
        final var visualIndex = (my - inner.y()) + scroll;
        if (visualIndex >= grid.size()) {
            final var last = grid.get(grid.size() - 1);
            return new CaretAt(last.row(), last.charStart() + last.fragment().length());
        }
        final var target = grid.get(visualIndex);
        final var within = TuiInputSupport.columnAtDisplayWidth(target.fragment(), mx - inner.x());
        return new CaretAt(target.row(), target.charStart() + within);
    }

    /**
     * Maps a screen point inside the completion popup to a candidate index, or -1 when the
     * point is on the border/title (the click should then just dismiss/ignore). The popup
     * draws {@code items} inside a rounded block with a 1-row border; the index is
     * {@code (y - rect.y - 1) + start} where {@code start} is the scroll window offset.
     */
    private static int completionItemAt(final UiState state, final int mx, final int my) {
        final var rect = state.completionRect;
        final var suggestion = state.completion;
        if (rect == null || suggestion == null || suggestion.empty()) {
            return -1;
        }
        final var items = suggestion.items();
        final var visible = Math.min(items.size(), 8);
        final var selected = state.completionIndex % items.size();
        final var start = Math.max(0, Math.min(selected - visible + 1, items.size() - visible));
        final var row = my - rect.y() - 1; // minus the top border
        if (row < 0 || row >= visible) {
            return -1;
        }
        final var index = start + row;
        return index >= 0 && index < items.size() ? index : -1;
    }

    /**
     * Renders the completion candidate popup just above the prompt line when a
     * {@code /command} or parameter completion is active. The currently cycled
     * match is highlighted among {@code items}; when the list overflows the popup
     * (8 rows max), the highlighted row is kept visible by scrolling the window,
     * like the {@code PickerPrompt} list. The popup is anchored horizontally at
     * the caret's display column so multi-word lines (e.g. {@code /model glm})
     * show candidates under the token being completed, not at the line's left edge.
     */
    private void renderCompletionPopup(final Rect inputRect, final Frame frame, final UiState state) {
        final var suggestion = state.completion;
        if (suggestion == null || suggestion.empty()) {
            return;
        }
        final var items = suggestion.items();
        final var visible = Math.min(items.size(), 8);
        // scroll window: keep the highlighted row visible, like a list picker
        final var selected = state.completionIndex % items.size();
        final var start = Math.max(0, Math.min(selected - visible + 1, items.size() - visible));
        final var width = Math.clamp(frame.width() - 4, 20, 46);
        final var height = visible + 2; // border + items
        // anchor at the caret's display column (clamped to the frame), not the input's left edge
        final var caretCol = inputRect.x() + caretDisplayCol(state.input);
        final var x = Math.max(0, Math.min(caretCol, frame.width() - width));
        final var y = Math.max(0, inputRect.y() - height);
        final var rect = new Rect(x, y, width, height);
        state.completionRect = rect;
        frame.renderWidget(Clear.INSTANCE, rect);
        final var accent = Style.EMPTY.fg(Color.hex(palette.accent()));
        final var textStyle = Style.EMPTY.fg(Color.hex(palette.text()));
        final var block = Block.builder()
                .title(" " + (suggestion.label().isBlank() ? "complete" : suggestion.label()) + " ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(accent)
                .build();
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        // the popup is drawn after the input, so registering it last makes it the
        // topmost pane a press can resolve to (the full block rect, so clicks on the
        // border still resolve to the popup and are handled by itemAt below)
        frameZones.add(rect);
        final var lines = new ArrayList<Line>();
        // the active row reads as a highlighted selection: accent bold foreground on a subtle
        // accent-tinted backdrop (padding the row so the backdrop spans the whole popup width)
        final var selectedStyle = Style.EMPTY
                .fg(Color.hex(palette.accent()))
                .bold()
                .bg(TuiColors.blend(palette.accent(), palette.backgroundPanel(), 0.85));
        for (var i = 0; i < visible; i++) {
            final var itemIndex = start + i;
            final var selectedRow = itemIndex == selected;
            final var item = items.get(itemIndex);
            final var style = selectedRow ? selectedStyle : textStyle;
            final var marker = selectedRow ? "> " : "  ";
            if (selectedRow) {
                final var content = marker + item;
                lines.add(Line.from(Span.styled(
                        content + " ".repeat(Math.max(0, inner.width() - content.length())), style)));
            } else {
                lines.add(Line.from(Span.styled(marker + item, style)));
            }
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(lines.toArray(Line[]::new)))
                        .overflow(Overflow.CLIP)
                        .build(),
                inner);
    }

    private void renderCalling(final Rect rect, final Frame frame, final UiState state) {
        final var accent = Style.EMPTY.fg(Color.hex(palette.accent()));
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var hint = "esc to interrupt";
        final var halves = Layout.horizontal()
                .constraints(Constraint.fill(), Constraint.length(hint.length() + 1))
                .split(rect);
        if (state.model.busy()) {
            if (state.model.runningTool() != null) {
                final var name = state.model.runningToolCommand() == null
                                || state.model.runningToolCommand().isBlank()
                        ? state.model.runningTool()
                        : state.model.runningToolCommand();
                final var line =
                        Line.from(Span.styled(spinnerPrefix(state) + BusyLabels.calling(ellipsize(name, 60)), accent));
                frame.renderWidget(
                        Paragraph.builder()
                                .text(Text.from(line))
                                .overflow(Overflow.ELLIPSIS)
                                .build(),
                        halves.getFirst());
            } else {
                final var busy = busyIndicator(state);
                final var line = busy == null
                        ? Line.from(Span.styled(BusyLabels.working(), muted))
                        : Line.from(Span.styled(
                                spinnerPrefix(state) + busy.getValue(),
                                state.model.thinkingLength() > 0 ? palette.blended(palette.info(), 0.35) : muted));
                frame.renderWidget(
                        Paragraph.builder()
                                .text(Text.from(line))
                                .overflow(Overflow.ELLIPSIS)
                                .build(),
                        halves.getFirst());
            }
            final var hintLine = Line.from(Span.styled(hint, muted));
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(hintLine))
                            .overflow(Overflow.CLIP)
                            .build(),
                    halves.get(1));
        }
    }

    private void renderStatus(final Rect rect, final Frame frame, final UiState state, final boolean narrow) {
        // reset the error-banner close zone each frame: it is meaningful only while a banner is drawn below
        state.errorCloseRect = null;
        final var statusColor = Color.hex(
                activeError(state) ? palette.error() : (state.model.busy() ? palette.warning() : palette.success()));
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var parts = new ArrayList<Span>();
        final var busy = busyIndicator(state);
        // The strip must never read blank while the agent is active or not finished. The animated
        // phase label (calling <tool> / thinking (Ns) / agent is working...) lives on the call line
        // directly above this strip (renderCalling), so instead of repeating that exact text here
        // we paint a steady, short work word that never duplicates it; the leading column then
        // stays aligned with the idle "ready" text. Idle (finished) renders the transcript status.
        if (busy == null) {
            parts.add(Span.styled(" " + state.model.status() + "  ", Style.EMPTY.fg(statusColor)));
        } else {
            parts.add(Span.styled("working  ", Style.EMPTY.fg(statusColor)));
        }
        final var statusModel = chatClient.model(state.session.chatSettings());
        final var activeModel = ellipsize(statusModel, 40);
        final var activeVariant = chatClient.resolvedVariant(state.session.chatSettings());
        final var variantSuffix =
                activeVariant == null || activeVariant.isBlank() ? " (default)" : " [" + activeVariant + "]";
        parts.add(Span.styled(modeWord() + " ", Style.EMPTY.fg(modeColor())));
        parts.add(Span.styled(
                activeModel + variantSuffix + "  ",
                modelNameStyle(state.session == null ? null : state.session.chatSettings(), statusModel)));
        parts.add(Span.styled(state.activeAgent.isBlank() ? "" : "agent " + state.activeAgent + "  ", muted));
        // narrow terminals have no sidebar: keep the essential context numbers in the strip
        if (narrow) {
            parts.add(Span.styled("  ctx " + state.model.contextPercent() + "%", muted));
            final var cached = state.model.cacheReadTokens();
            final var fullInputTokens = state.model.contextInputTokens();
            final var cachedSuffix = cached > 0 && fullInputTokens > 0
                    ? " cached " + formatTokens(cached) + " (" + Math.round(cached * 100.0 / fullInputTokens) + "%)"
                    : "";
            parts.add(Span.styled(
                    "  in " + formatTokens(fullInputTokens) + " out " + formatTokens(state.model.completionTokens())
                            + cachedSuffix,
                    muted));
        }
        if (conf.bindings() == KeyBindingPreset.VIM) {
            parts.add(Span.styled("  [vim insert]  ", muted));
        }
        // Per-turn metrics (live from the session so /config metrics applies immediately)
        final var metrics = state.session.metrics();
        if (metrics.showPerTurn() && state.model.turnLlmCalls() > 0) {
            final var mode = metrics.displayMode();
            final var duration = DurationFormat.humanize(state.model.turnDurationMs());
            // empty when the throughput cannot be measured: the segment is then simply skipped
            final var tokPerSec = MetricsFormat.tokPerSec(state.model.turnThroughput());
            final var throughput = tokPerSec.isEmpty() ? "" : " · " + tokPerSec;
            if (mode == MetricsDisplayMode.COMPACT) {
                parts.add(Span.styled(
                        " · " + state.model.turnLlmCalls() + " LLM ("
                                + DurationFormat.humanize(state.model.turnThinkingMs()) + ")"
                                + throughput + " · " + state.model.turnToolsRun() + " tools ("
                                + DurationFormat.humanize(state.model.turnToolSumMs()) + "/"
                                + DurationFormat.humanize(state.model.turnToolWallMs()) + ") · " + duration
                                + "  ",
                        muted));
            } else if (mode == MetricsDisplayMode.FULL) {
                final var turnThroughput = state.model.turnThroughput();
                final var reasoning = turnThroughput != null && turnThroughput.reasoningTokensPerSecond() > 0
                        ? String.format(
                                Locale.ROOT,
                                " · reasoning/request %.1f tok/s",
                                turnThroughput.reasoningTokensPerSecond())
                        : "";
                parts.add(Span.styled(
                        " · " + state.model.turnLlmCalls() + " LLM ("
                                + DurationFormat.humanize(state.model.turnThinkingMs()) + " think)"
                                + throughput + reasoning + " · " + state.model.turnToolsRun() + " tools ("
                                + DurationFormat.humanize(state.model.turnToolSumMs()) + " sum/"
                                + DurationFormat.humanize(state.model.turnToolWallMs())
                                + " wall) · " + duration + "  ",
                        muted));
            }
        }
        if (metrics.showSessionTotals() && state.session.runtime().usage().requests() > 0) {
            parts.add(
                    Span.styled(" · session: " + state.session.runtime().usage().requests() + " LLM calls  ", muted));
        }
        final var usage_ = state.session.runtime().usage();
        if (metrics.showSessionTotals() && usage_ != null && usage_.optimizedOutputs() > 0) {
            parts.add(Span.styled(
                    " · optimized " + usage_.optimizedOutputs() + " (~"
                            + MetricsFormat.tokens(usage_.optimizedTokensSaved())
                            + " tokens saved"
                            + (metrics.displayMode() == MetricsDisplayMode.FULL
                                    ? ", " + usage_.optimizedCharsSaved() + " chars"
                                    : "")
                            + ")  ",
                    muted));
        }
        // the status strip reserves a right edge for the clickable "?" shortcuts affordance
        final var helpText = "  [Alt+?] ";
        final var helpW = Math.min(helpText.length(), Math.max(1, rect.width()));
        final var statusW = Math.max(1, rect.width() - helpW);
        final var notifW = state.notification == null
                        || state.notification.isBlank()
                        || (!state.compactionActive
                                && System.currentTimeMillis() - state.notificationShownAt > NOTIFICATION_TTL_MS)
                ? 0
                : CharWidth.of("  ⓘ " + state.notification);
        final var totalW = new int[] {parts.stream().mapToInt(Span::width).sum()};
        // flexible informational messages (code graph refreshes, clipboard feedback, queue
        // notices, provider retries...) get priority over the trailing per-turn metrics when
        // they contend for the row: a retry/failure notice must never vanish behind metrics.
        // When the notice needs more room than the metrics would, the metrics are clipped and
        // the notice fully shown; the metrics already live in the sidebar, the notice does not.
        if (notifW > 0 && totalW[0] + notifW > statusW) {
            // drop trailing segments (metrics, session totals) until the notice fits, keeping the
            // leading state (model, permissions, agent, narrow ctx/io) intact
            while (parts.size() > 4 && totalW[0] + notifW > statusW) {
                final var removed = parts.remove(parts.size() - 1);
                totalW[0] -= removed.width();
            }
        }
        if (notifW > 0) {
            final var notifColor = activeError(state) ? palette.error() : palette.warning();
            final var notifPrefix = "  ⓘ ";
            final var remaining = statusW - totalW[0] - CharWidth.of(notifPrefix) - 2; // trailing "  "
            parts.add(Span.styled(
                    notifPrefix + ellipsize(state.notification, Math.max(8, remaining)) + "  ",
                    Style.EMPTY.fg(Color.hex(notifColor))));
        }
        // errors trail the metrics in error red: clipped with an ellipsis to the remaining row width so
        // the message never wraps nor pushes model/metrics out (full text + stack live behind Alt+E)
        if (state.errorMessage != null) {
            final var fixedWidth = parts.stream().mapToInt(Span::width).sum();
            final var errPrefix = "  ✗ ";
            final var remaining = statusW - fixedWidth - CharWidth.of(errPrefix) - 2; // trailing "  "
            parts.add(Span.styled(
                    errPrefix + ellipsize(state.errorMessage, Math.max(8, remaining)) + "  ",
                    Style.EMPTY.fg(Color.hex(palette.error()))));
        }
        final var line = Line.from(parts.toArray(Span[]::new));
        final var statusSplit = helpW >= rect.width()
                ? List.of(rect)
                : List.of(new Rect(rect.x(), rect.y(), statusW, 1), new Rect(rect.x() + statusW, rect.y(), helpW, 1));
        state.shortcutRect = statusSplit.size() == 2 ? statusSplit.get(1) : null;
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(line))
                        .overflow(Overflow.WRAP_WORD)
                        .build(),
                statusSplit.getFirst());
        if (state.shortcutRect != null) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(
                                    helpText,
                                    Style.EMPTY.fg(Color.hex(palette.accent())).bold()))))
                            .overflow(Overflow.CLIP)
                            .build(),
                    state.shortcutRect);
        }
        final var percent = state.model.contextPercent();
        final var compactionThreshold = compactionThreshold();
        if (percent >= compactionThreshold) {
            final var banner = Line.from(Span.styled(
                    "⚠  context at " + percent + "% (will compact at " + compactionThreshold
                            + "%) - consider /compact  ",
                    Style.EMPTY.fg(Color.hex(palette.warning()))));
            // the strip grows a row for this warning: draw it below the status line, not over it
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(banner))
                            .overflow(Overflow.CLIP)
                            .build(),
                    new Rect(rect.x(), rect.y() + rect.height() - 1, rect.width(), 1));
        }
        if (state.errorMessage != null) {
            final var err = Line.from(Span.styled(
                    glyphs.cross() + "  " + state.errorMessage + "  (Alt+E for details) ",
                    Style.EMPTY.fg(Color.hex(palette.error()))));
            // the boxed banner lives below the single-row status line (row 0) so it never covers it:
            // the strip already grows a row for the status line on top of the box's own 3 rows
            final var bannerRect = new Rect(rect.x(), rect.y() + 1, rect.width(), Math.max(1, rect.height() - 1));
            final var errBlock = Block.builder()
                    .borders(Borders.ALL)
                    .borderType(BorderType.ROUNDED)
                    .borderColor(Color.hex(palette.error()))
                    .build();
            frame.renderWidget(errBlock, bannerRect);
            final var inner = errBlock.inner(bannerRect);
            if (!inner.isEmpty()) {
                // the leading "✖ " glyph is the click-to-dismiss target: register its hit rect so a press
                // on the banner's close marker clears the error instead of starting a selection
                state.errorCloseRect = new Rect(inner.x(), inner.y(), CharWidth.of(glyphs.cross()) + 1, inner.height());
                frame.renderWidget(
                        Paragraph.builder()
                                .text(Text.from(err))
                                .overflow(Overflow.ELLIPSIS)
                                .build(),
                        inner);
            }
        }
    }

    /** Floating find bar popup shown while Ctrl+F search is open. */
    private void renderSearchBar(final Frame frame, final UiState state) {
        final var width = Math.min(66, frame.width() - 4);
        final var height = Math.min(3, frame.height());
        final var rect = Modal.rect(frame, width, height, 0.72);
        final var inner = modal(frame, rect, " find ", Color.hex(palette.accent()));
        if (inner == null) {
            return;
        }
        final var counter = state.search.active()
                ? "  "
                        + (state.search.count() == 0
                                ? "no match"
                                : state.search.currentMatch() + "/" + state.search.count())
                : "";
        final var hints = Line.from(
                Span.styled(state.search.query() + "_", Style.EMPTY.fg(Color.hex(palette.text()))),
                Span.styled(
                        counter + "  Enter/Ctrl+N next  Ctrl+P prev  Esc close",
                        Style.EMPTY.fg(Color.hex(palette.textMuted()))));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(hints))
                        .overflow(Overflow.ELLIPSIS)
                        .build(),
                inner);
    }

    private void openSearch(final UiState state) {
        state.searchOpen = true;
        state.search.recompute(state.flatRows);
    }

    /** Key handling while the shortcuts overlay is open. */
    private boolean onShortcutsKey(final Event event, final UiState state) {
        if (event instanceof final MouseEvent mouse) {
            // the wheel is routed to the topmost scrollable view by scrollTopOverlay
            return true;
        }
        if (event instanceof PasteEvent) {
            return true;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('?')) {
            state.shortcutsOpen = false;
            return true;
        }
        // the overlay is taller than the terminal on small screens, so it scrolls too
        if (key.hasAlt() && key.isCharIgnoreCase('u')) {
            state.shortcutsScroll = scrollService.apply(state.shortcutsScroll, -scrollService.pageRows());
        } else if (key.hasAlt() && key.isCharIgnoreCase('d')) {
            state.shortcutsScroll = scrollService.apply(state.shortcutsScroll, scrollService.pageRows());
        } else {
            state.shortcutsScroll = scrollService.apply(state.shortcutsScroll, scrollService.keyDelta(key));
        }
        return true;
    }

    /** Key handling while the "/tabs" list overlay is open. */
    private boolean onTabsListKey(final Event event) {
        if (event instanceof final MouseEvent mouse) {
            // the wheel is routed to the topmost scrollable view by scrollTopOverlay
            return true;
        }
        if (event instanceof PasteEvent) {
            return true;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
            tabManager.closeList();
            return true;
        }
        if (key.isKey(KeyCode.UP)
                || key.isKey(KeyCode.DOWN)
                || key.isKey(KeyCode.PAGE_UP)
                || key.isKey(KeyCode.PAGE_DOWN)) {
            // the tab titles can overflow the overlay height
            tabManager.listScroll(scrollService.apply(tabManager.listScroll(), scrollService.keyDelta(key)));
        } else if (!key.hasAlt() && !key.hasCtrl() && key.character() >= '0' && key.character() <= '9') {
            final var index = key.character() == '0' ? 9 : Character.digit(key.character(), 10) - 1;
            if (index < tabManager.size()) {
                tabManager.closeList();
                activate(index);
            }
            return true;
        }
        return true;
    }

    /** Scrollable modal listing every documented key binding, opened by the "?" affordance. */
    private void renderShortcutsOverlay(final Frame frame, final UiState state) {
        final var width = Math.min(Math.max(64, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 4 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.12);
        final var inner = modal(frame, rect, " shortcuts ", Color.hex(palette.accent()));
        if (inner == null) {
            return;
        }
        final var title = Style.EMPTY.fg(Color.hex(palette.accent())).bold();
        final var key = Style.EMPTY.fg(Color.hex(palette.text())).bold();
        final var label = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var rows = new ArrayList<Line>();
        var group = "";
        for (final var binding : KeymapCatalog.all()) {
            if (!binding.group().equals(group)) {
                group = binding.group();
                rows.add(Line.empty());
                rows.add(Line.from(Span.styled(group, title)));
            }
            rows.add(Line.from(Span.styled("  " + binding.key(), key), Span.styled(" - " + binding.label(), label)));
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = Text.from(rows.toArray(Line[]::new));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        state.shortcutsScroll = Math.clamp(state.shortcutsScroll, 0, maxScroll);
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(state.shortcutsScroll)
                            .build(),
                    splits.getFirst());
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "  Wheel/Up/Down scroll  Esc close  ",
                                Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    /** Modal listing every open tab with its full title; opened by "/tabs" (or an arg to switch directly). */
    private void renderTabTooltip(final Frame frame, final UiState state) {
        final var title = state.hoverTitle;
        if (title == null || title.isBlank()) {
            return;
        }
        // the tooltip floats on the first transcript row right under the tab strip:
        // a single-line chip so the full title is readable without reserving layout
        final var width = Math.max(1, Math.min(frame.area().width() - 2, title.length() + 4));
        final var inner = new Rect(1, 1, width, 1);
        final var tooltipBg =
                palette.mono() ? null : TuiColors.blend(palette.borderActive(), palette.backgroundPanel(), 0.85);
        final var tooltipStyle = palette.mono()
                ? Style.EMPTY
                : Style.EMPTY.fg(Color.hex(palette.text())).bg(tooltipBg);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(" " + title + " ", tooltipStyle))))
                        .overflow(Overflow.CLIP)
                        .build(),
                inner);
    }

    /**
     * Floating chip just below a hovered sidebar quota gauge: shows the gauge's reset time
     * ("Resets in ...", or "Resets now"). Anchored under the hovered gauge row so it reads
     * without covering the account-share bar, clamped to the frame so it never escapes the
     * viewport (the sidebar is bottom-left, so the chip grows upward when near the bottom edge).
     */
    private void renderQuotaTooltip(final Frame frame, final UiState state) {
        final var caption = state.hoverQuotaReset;
        final var rect = state.hoverQuotaRect;
        if (caption == null || caption.isBlank() || rect == null) {
            return;
        }
        final var text = "Resets " + caption;
        final var width = Math.max(1, Math.min(frame.area().width() - 2, text.length() + 4));
        final var tooltipBg =
                palette.mono() ? null : TuiColors.blend(palette.borderActive(), palette.backgroundPanel(), 0.85);
        final var tooltipStyle = palette.mono()
                ? Style.EMPTY
                : Style.EMPTY.fg(Color.hex(palette.text())).bg(tooltipBg);
        final var x = Math.max(0, Math.min(frame.area().width() - width, rect.x()));
        final var y = Math.max(1, Math.min(frame.height() - 1, rect.y() + rect.height()));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(" " + text + " ", tooltipStyle))))
                        .overflow(Overflow.CLIP)
                        .build(),
                new Rect(x, y, width, 1));
    }

    /**
     * Floating chip over the tool output modal footer: shows the full (raw) tool args
     * JSON the footer only ellipsizes. A multi-line wrapped chip anchored just above
     * the footer row so it reads without covering the output the user is looking at;
     * clamped to the frame so a very wide/tall payload never escapes the viewport.
     */
    /**
     * Floating chip just below a hovered sidebar context gauge: shows the estimated context
     * size in a compact k/M form ("~12.3k tok"). Anchored under the hovered gauge row and
     * clamped to the frame like the quota reset tooltip (the sidebar is bottom-left, so the
     * chip grows upward when near the bottom edge).
     */
    private void renderContextTooltip(final Frame frame, final UiState state) {
        final var caption = state.hoverContextSize;
        final var rect = state.contextGaugeRect;
        if (caption == null || caption.isBlank() || rect == null) {
            return;
        }
        final var width = Math.max(1, Math.min(frame.area().width() - 2, caption.length() + 4));
        final var tooltipBg =
                palette.mono() ? null : TuiColors.blend(palette.borderActive(), palette.backgroundPanel(), 0.85);
        final var tooltipStyle = palette.mono()
                ? Style.EMPTY
                : Style.EMPTY.fg(Color.hex(palette.text())).bg(tooltipBg);
        final var x = Math.max(0, Math.min(frame.area().width() - width, rect.x()));
        final var y = Math.max(1, Math.min(frame.height() - 1, rect.y() + rect.height()));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(" " + caption + " ", tooltipStyle))))
                        .overflow(Overflow.CLIP)
                        .build(),
                new Rect(x, y, width, 1));
    }

    private void renderToolOverlayTooltip(final Frame frame, final UiState state) {
        final var cmd = state.hoverToolCmd;
        final var footer = state.toolOverlayFooter;
        if (cmd == null || cmd.isBlank() || footer == null) {
            return;
        }
        final var wrapWidth =
                Math.max(10, Math.min(footer.width() - 2, Math.min(frame.area().width() - 4, 120)));
        final var lines = MarkdownLines.wrapCode(List.of(Line.from(Span.styled(cmd, Style.EMPTY))), wrapWidth);
        final var height = Math.min(lines.size(), Math.max(1, frame.height() - 2));
        final var tooltipBg =
                palette.mono() ? null : TuiColors.blend(palette.borderActive(), palette.backgroundPanel(), 0.85);
        final var tooltipStyle = palette.mono()
                ? Style.EMPTY
                : Style.EMPTY.fg(Color.hex(palette.text())).bg(tooltipBg);
        final var wrapped = new ArrayList<Line>();
        for (var i = 0; i < height; i++) {
            final var line = lines.get(i);
            final var styled = line.spans().stream()
                    .map(s -> Span.styled(s.content(), tooltipStyle))
                    .toList();
            wrapped.add(Line.from(styled.toArray(Span[]::new)));
        }
        // anchor just above the footer row, aligned to the footer's left edge, clamped
        // so the chip stays fully on-screen
        final var x = Math.max(0, Math.min(frame.area().width() - wrapWidth, footer.x()));
        final var y = Math.max(1, footer.y() - height - 1);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(wrapped.toArray(Line[]::new)))
                        .overflow(Overflow.CLIP)
                        .build(),
                new Rect(x, y, wrapWidth, height));
    }

    private void renderTabsListOverlay(final Frame frame) {
        final var width = Math.min(Math.max(64, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(10, frame.height() * 4 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.12);
        final var inner = modal(frame, rect, " tabs ", Color.hex(palette.accent()));
        if (inner == null) {
            return;
        }
        final var activeIndex = tabManager.activeTab();
        final var accent = Style.EMPTY.fg(Color.hex(palette.accent())).bold();
        final var textStyle = Style.EMPTY.fg(Color.hex(palette.text()));
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var rows = new ArrayList<Line>();
        final var open = tabManager.list();
        for (int i = 0; i < open.size(); i++) {
            final var tab = open.get(i);
            final var isActive = i == activeIndex;
            final var index = Span.styled(String.format("%2d ", i + 1), isActive ? accent : muted);
            final var icon = Span.styled(tabStatusIcon(tab) + " ", isActive ? accent : muted);
            // the mode badge (same mark/color contract as the tab bar) sits between the status icon and the full title
            final var modeMark = Span.styled(glyphs.modeMark(tabBuildMode(tab)), Style.EMPTY.fg(tabModeColor(tab)));
            final var modeLabel =
                    Span.styled(" " + (tabBuildMode(tab) ? "build" : "plan") + "  ", isActive ? accent : muted);
            final var title = Span.styled(tabTitle(tab), isActive ? accent : textStyle);
            final var marker = isActive ? Span.styled("  (active)", accent) : Span.styled("", muted);
            rows.add(Line.from(index, icon, modeMark, modeLabel, title, marker));
        }
        if (rows.isEmpty()) {
            rows.add(Line.from(Span.styled("no open tabs", muted)));
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = Text.from(rows.toArray(Line[]::new));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        tabManager.listScroll(Math.clamp(tabManager.listScroll(), 0, maxScroll));
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(tabManager.listScroll())
                            .build(),
                    splits.getFirst());
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "  1-9 switch  Wheel/Up/Down scroll  Esc close  ",
                                Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    /** Opens the subagents overlay: no arg = the list, an id or unique name = detail. */
    private ShellOutcome openSubagents(final String arg) {
        final var state = active();
        if (state == null) {
            return ShellOutcome.none();
        }
        final var reference = arg == null ? "" : arg.strip();
        var id = "";
        if (!reference.isEmpty()) {
            final var matches = subagentRegistry.resolve(state.sessionId, reference);
            if (matches.size() != 1) {
                return ShellOutcome.status(subagentRegistry.selectionError(reference, matches));
            }
            id = matches.getFirst().id();
        }
        state.subagentsOverlay = id;
        state.subagentsScroll = 0;
        return ShellOutcome.none();
    }

    /** Key handling while the subagents overlay is open. */
    private boolean onSubagentsKey(final Event event, final UiState state) {
        if (event instanceof final MouseEvent mouse) {
            // the wheel is routed to the topmost scrollable view by scrollTopOverlay
            return true;
        }
        if (event instanceof PasteEvent) {
            return true;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
            state.subagentsOverlay = null;
            return true;
        }
        if ((key.isKey(KeyCode.UP)
                || key.isKey(KeyCode.DOWN)
                || key.isKey(KeyCode.PAGE_UP)
                || key.isKey(KeyCode.PAGE_DOWN))) {
            state.subagentsScroll = scrollService.apply(state.subagentsScroll, scrollService.keyDelta(key));
        } else if ((key.isLeft() || key.isKey(KeyCode.BACKSPACE)) && !state.subagentsOverlay.isEmpty()) {
            state.subagentsOverlay = "";
            state.subagentsScroll = 0;
        }
        return true;
    }

    /** Modal showing the session subagents; with an id, the live transcript of one of them. */
    private void renderSubagentsOverlay(final Frame frame, final UiState state) {
        final var width = Math.min(Math.max(64, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 4 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.12);
        final var detail = !state.subagentsOverlay.isEmpty();
        final var inner = modal(
                frame,
                rect,
                detail ? " subagents - " + state.subagentsOverlay : " subagents ",
                Color.hex(palette.accent()));
        if (inner == null) {
            return;
        }
        final var accent = Style.EMPTY.fg(Color.hex(palette.accent())).bold();
        final var textStyle = Style.EMPTY.fg(Color.hex(palette.text()));
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var rows = new ArrayList<Line>();
        if (detail) {
            final var captured = subagentRegistry.transcriptOf(state.subagentsOverlay);
            final var subOpt = subagentRegistry.get(state.subagentsOverlay);
            // a finished sub-agent gets a report card above the live capture: effective model,
            // colored status, artifacts and the final summary rendered as markdown
            if (subOpt.isPresent() && subOpt.get().isDone() && subOpt.get().result() != null) {
                final var sub = subOpt.get();
                final var result = sub.result();
                subagentRegistry
                        .identityOf(state.subagentsOverlay)
                        .map(SubagentRegistry.TranscriptIdentity::model)
                        .filter(m -> m != null && !m.isBlank())
                        .ifPresent(m -> rows.add(Line.from(Span.styled("model: " + m, muted))));
                final var statusStyle =
                        switch (result.status()) {
                            case SUCCEEDED -> Style.EMPTY.fg(Color.hex(palette.success()));
                            case CANCELLED -> muted;
                            case BUDGET_EXHAUSTED -> Style.EMPTY.fg(Color.hex(palette.warning()));
                            case FAILED -> Style.EMPTY.fg(Color.hex(palette.error()));
                        };
                rows.add(Line.from(Span.styled(
                        "status: "
                                + result.status()
                                        .name()
                                        .toLowerCase(Locale.ROOT)
                                        .replace('_', ' '),
                        statusStyle)));
                if (!result.artifacts().isEmpty()) {
                    rows.add(Line.from(Span.styled("artifacts: " + String.join(", ", result.artifacts()), muted)));
                }
                rows.add(Line.empty());
                final var summary = result.summary() != null ? result.summary() : result.error();
                if (summary != null && !summary.isBlank()) {
                    final var contentWidth = Math.max(1, inner.width() - 2);
                    appendModalRegion(rows, summary, contentWidth, textStyle);
                    rows.add(Line.empty());
                }
                rows.add(Line.from(Span.styled("--- full transcript ---", muted)));
                rows.add(Line.empty());
            }
            subOpt.filter(s -> !(s.isDone() && s.result() != null))
                    .map(SubagentRegistry.SubagentState::model)
                    .filter(m -> m != null && !m.isBlank())
                    .ifPresent(m -> rows.add(Line.from(Span.styled("model: " + m, muted))));
            final var md = captured.markdown("|", "");
            if (md.isBlank()) {
                rows.add(Line.from(Span.styled("*still starting...*", muted)));
            } else {
                // the per-subagent capture is a transcript like the main pane: split it into
                // thought/tool/plain regions instead of rendering the raw marker markdown
                // (markers are opaque to the markdown renderer and would show literally)
                final var contentWidth = Math.max(1, inner.width() - 2);
                for (final var region : TuiTranscriptModel.regions(md)) {
                    if (region.thought()) {
                        if (state.showThinking) {
                            rows.addAll(thoughtLines(region.text(), contentWidth));
                        }
                    } else if (region.tool()) {
                        if (state.showToolActions != ToolOutputVisibility.HIDDEN) {
                            appendModalRegion(rows, region.text(), contentWidth, textStyle);
                        }
                    } else if (region.log()) {
                        // captured logs never originate from a subagent transcript; render
                        // them plain if one ever shows up here
                        appendModalRegion(rows, region.text(), contentWidth, textStyle);
                    } else if (!region.text().isBlank()) {
                        appendModalRegion(rows, region.text(), contentWidth, textStyle);
                    }
                }
            }
        } else {
            final var active = active();
            final var list = subagentRegistry.listAll(active == null ? null : active.sessionId);
            if (list.isEmpty()) {
                rows.add(Line.from(Span.styled("no subagents for this session", muted)));
            }
            for (final var sub : list) {
                final var label =
                        sub.name() == null || sub.name().isBlank() ? sub.id() : sub.name() + "  (" + sub.id() + ")";
                final var status = sub.isDone() ? (sub.isCancelled() ? "cancelled" : "completed") : "running";
                final var effective = subagentRegistry
                        .identityOf(sub.id())
                        .map(SubagentRegistry.TranscriptIdentity::model)
                        .filter(m -> m != null && !m.isBlank())
                        .orElseGet(() -> sub.model() == null || sub.model().isBlank() ? "" : sub.model());
                final var model = effective.isBlank() ? "" : "  [" + effective + "]";
                rows.add(Line.from(
                        Span.styled(label, accent),
                        Span.styled(
                                "  [" + status + "]",
                                sub.isDone()
                                        ? (sub.isCancelled()
                                                ? muted
                                                : sub.result() != null
                                                                && sub.result().success()
                                                        ? Style.EMPTY.fg(Color.hex(palette.success()))
                                                        : Style.EMPTY.fg(Color.hex(palette.error())))
                                        : accent),
                        Span.styled(model, muted),
                        Span.styled("  " + TuiInputSupport.firstLine(summarize(sub)), muted)));
            }
            rows.add(Line.empty());
            rows.add(Line.from(Span.styled("  /subagents <id|name> to open one subagent transcript", muted)));
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = Text.from(rows.toArray(Line[]::new));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        state.subagentsScroll = Math.clamp(state.subagentsScroll, 0, maxScroll);
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(state.subagentsScroll)
                            .build(),
                    splits.getFirst());
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "  Wheel/Up/Down scroll  Esc close  ",
                                Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    private static String summarize(final SubagentRegistry.SubagentState sub) {
        final var result = sub.result();
        if (result == null) {
            return sub.task() == null ? "" : sub.task();
        }
        final var summary = result.summary() != null ? result.summary() : result.error();
        return summary == null || summary.isBlank() ? (sub.task() == null ? "" : sub.task()) : summary;
    }

    /**
     * Renders one non-thought subagent-region body (prose or fenced code) into plain modal lines.
     * <p>
     * As for thoughts, consecutive segments are independent block groups (a fenced block is split
     * out of the prose and highlighted separately) so one blank row is kept between them, like the
     * markdown walk does for the sibling blocks of a single document.
     */
    private void appendModalRegion(final List<Line> rows, final String text, final int width, final Style base) {
        final var body = new ArrayList<Line>();
        for (final var seg : syntaxHighlighter.split(text)) {
            final List<Line> rendered;
            if (seg.type() == SyntaxHighlighter.Segment.Type.CODE) {
                rendered = MarkdownLines.wrapCode(
                        syntaxHighlighter.highlightLines(seg.language(), seg.text()), Math.max(1, width - 4));
            } else if (!seg.text().isBlank()) {
                rendered = transcriptRenderer.renderRegion(seg.text(), width, base, markdownStyles);
            } else {
                continue;
            }
            TranscriptRenderer.appendBlocks(body, rendered, true);
        }
        rows.addAll(body);
    }

    private void renderBtw(final Frame frame, final UiState state) {
        final var btw = state.btw;
        final var width = Math.min(Math.max(64, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 3 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.12);
        final var inner = modal(frame, rect, " btw ", Color.hex(palette.accent()));
        if (inner == null) {
            return;
        }
        final var lines = new ArrayList<Line>();
        lines.add(Line.from(Span.styled("Q: " + btw.question(), Style.EMPTY.fg(Color.hex(palette.textMuted())))));
        lines.add(Line.empty());
        if (btw.running()) {
            lines.add(Line.from(Span.styled("thinking...", Style.EMPTY.fg(Color.hex(palette.textMuted())))));
        } else if (btw.error() != null) {
            lines.add(Line.from(Span.styled("failed: " + btw.error(), Style.EMPTY.fg(Color.hex(palette.error())))));
        } else {
            lines.addAll(transcriptRenderer.renderRegion(
                    state.model.compression().stripEchoedRefs(btw.answer()),
                    inner.width(),
                    Style.EMPTY,
                    markdownStyles));
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = Text.from(lines.toArray(Line[]::new));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        final var scroll = Math.clamp(btw.scroll(), 0, maxScroll);
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(scroll)
                            .build(),
                    splits.getFirst());
        }
        final var footer = btw.running()
                ? "  Esc cancel"
                : btw.retryable()
                        ? "  r Retry   Wheel/Up/Down scroll  Esc close  "
                        : "  Wheel/Up/Down scroll  Esc close  ";
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(footer, Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    /** Renders the /executing-tools modal overlay: a centered box with the running-tools table (scrollable). */
    private void renderExecutingTools(final Frame frame, final UiState state) {
        final var overlay = state.executingTools;
        final var width = Math.min(Math.max(64, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 3 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.12);
        final var inner = modal(frame, rect, " executing-tools ", Color.hex(palette.accent()));
        if (inner == null) {
            return;
        }
        final var lines = new ArrayList<>(
                transcriptRenderer.renderRegion(overlay.content(), inner.width(), Style.EMPTY, markdownStyles));
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = Text.from(lines.toArray(Line[]::new));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        final var scroll = Math.clamp(overlay.scroll(), 0, maxScroll);
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(scroll)
                            .build(),
                    splits.getFirst());
        }
        final var footer = "  Wheel/Up/Down scroll  Esc close  ";
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(footer, Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    private void closeSearch(final UiState state) {
        state.searchOpen = false;
        state.search.clear();
        state.pendingJumpRow = -1;
    }

    private void jumpToCurrentMatch(final UiState state) {
        if (state.search.currentRow() >= 0) {
            state.pendingJumpRow = state.search.currentRow();
        }
    }

    /** Key handling while the Ctrl+F find bar is open (the hints row doubles as the search input). */
    private boolean onSearchKey(final Event event, final UiState state) {
        if (event instanceof final PasteEvent paste) {
            state.search.query(state.search.query() + paste.text().replace("\n", " "));
            state.search.recompute(state.flatRows);
            jumpToCurrentMatch(state);
            return true;
        }
        if (event instanceof MouseEvent) {
            return true;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE) || (key.hasCtrl() && key.isCharIgnoreCase('f'))) {
            closeSearch(state);
            return true;
        }
        if (key.isConfirm() || (key.hasCtrl() && key.isCharIgnoreCase('n'))) {
            state.search.next();
            jumpToCurrentMatch(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('p')) {
            state.search.previous();
            jumpToCurrentMatch(state);
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE)) {
            final var q = state.search.query();
            if (!q.isEmpty()) {
                state.search.query(q.substring(0, q.offsetByCodePoints(q.length(), -1)));
                state.search.recompute(state.flatRows);
            }
            return true;
        }
        if (!key.hasCtrl() && !key.hasAlt() && key.codePoint() >= 32) {
            final var cp = key.codePoint();
            state.search.query(state.search.query()
                    + (cp > Character.MAX_VALUE ? new String(Character.toChars(cp)) : String.valueOf((char) cp)));
            state.search.recompute(state.flatRows);
            jumpToCurrentMatch(state);
            return true;
        }
        return true;
    }

    private void openToolOverlay(final UiState state, final long ordinal) {
        if (ordinal < 0) {
            notify(state, "no tool output captured yet - Ctrl+O opens the latest tool call");
            return;
        }
        final var entry = state.toolOutputs.get(ordinal);
        if (entry == null) {
            notify(
                    state,
                    ordinal <= state.toolOutputs.maxOrdinalSeen()
                            ? "tool #" + ordinal
                                    + " output was evicted to bound memory (oldest tool outputs are dropped first)"
                            : "tool #" + ordinal + " output is no longer in memory");
            return;
        }
        state.focusedToolOrdinal = ordinal;
        state.toolOverlay = new UiState.ToolOverlay(ordinal, entry, 0);
    }

    private void scrollToolOverlay(final UiState state, final int delta) {
        state.hoverToolCmd = null;
        state.toolOverlay = new UiState.ToolOverlay(
                state.toolOverlay.ordinal(),
                state.toolOverlay.entry(),
                Math.max(0, state.toolOverlay.scroll() + delta),
                state.toolOverlay.raw());
    }

    /** Key/mouse handling while the full tool output overlay is open. */
    private boolean onToolOverlayEvent(final Event event, final TuiRunner runner, final UiState state) {
        if (event instanceof final MouseEvent mouse) {
            return onModalMouse(mouse, runner, state);
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel()
                || key.isKey(KeyCode.ESCAPE)
                || key.isCharIgnoreCase('q')
                || (key.hasCtrl() && key.isCharIgnoreCase('o'))) {
            final var entry = state.toolOverlay.entry();
            final var cancel = key.isCancel() || key.isKey(KeyCode.ESCAPE);
            // Esc while a tool is still running cancels that tool (the overlay closes and
            // the running card shows the aborted result); the turn itself keeps running.
            // Ctrl+O/q only ever close the modal.
            if (cancel && entry.running() && state.session != null && state.session.runtime() != null) {
                final var runtime = state.session.runtime();
                runtime.loop().cancelTool(state.toolOverlay.ordinal());
            }
            state.toolOverlay = null;
            state.toolOverlayFooter = null;
            state.hoverToolCmd = null;
            state.hoverToolCard = -1L;
            return true;
        }
        if (key.hasCtrl() && key.hasShift() && key.isCharIgnoreCase('c')) {
            copySelection(runner, state);
            return true;
        }
        if (key.isCharIgnoreCase('c')) {
            copyText(runner, state, state.toolOverlay.entry().output());
            return true;
        }
        if (key.isCharIgnoreCase('r')
                && "skill".equals(state.toolOverlay.entry().name())) {
            final var overlay = state.toolOverlay;
            state.toolOverlay = new UiState.ToolOverlay(overlay.ordinal(), overlay.entry(), 0, !overlay.raw());
            state.selection.clear();
            return true;
        }
        scrollToolOverlay(state, scrollService.keyDelta(key));
        return true;
    }

    /**
     * Mouse handling shared by the modal dialogs (tool output, error details).
     * <p>
     * The wheel never reaches here - {@link #scrollTopOverlay(MouseEvent, UiState)} routed
     * it to the topmost scrollable view first - so the remaining events are only the
     * selection gestures. They are forwarded to the global selection engine instead of
     * being dropped: {@link #modal(dev.tamboui.terminal.Frame, Rect, String, Color)}
     * registers the dialog area as a copy zone precisely so a drag started on it stays
     * confined to the dialog, which is what makes its content selectable and copyable.
     */
    private boolean onModalMouse(final MouseEvent mouse, final TuiRunner runner, final UiState state) {
        if (mouse.isScroll()) {
            return true;
        }
        // any press/drag/release steals the pointer for selection: drop the hover tooltip
        // so it cannot overlap the drag selection being drawn
        state.hoverToolCmd = null;
        state.hoverQuotaReset = null;
        state.hoverQuotaRect = null;
        state.hoverContextSize = null;
        if (mouse.isPress() && mouse.isLeftButton()) {
            state.selection.press(mouse.x(), mouse.y(), state.selectionFrame, copyZones(state));
            return true;
        }
        if (mouse.kind() == MouseEventKind.DRAG && mouse.isLeftButton()) {
            state.selection.drag(mouse.x(), mouse.y());
            return true;
        }
        if (mouse.isRelease() && mouse.isLeftButton()) {
            copySelection(runner, state);
            return true;
        }
        if (mouse.isPress()) {
            state.selection.clear();
            return true;
        }
        return true;
    }

    /**
     * Shared renderer for tool output bodies (inline cards and the Ctrl+O overlay):
     * ask_user answers first, then format detection (diff/JSON/XML/code/yaml/markdown),
     * always falling back to the historical rendering on any failure.
     *
     * @param command the command that produced the output, used to color it as code
     *                ({@code null} when unknown, eg. for write_file/edit_file)
     */
    private List<Line> toolBodyLines(
            final String name,
            final String command,
            final String args,
            final String output,
            final int width,
            final boolean isDiff,
            final DiffViewMode mode) {
        return toolBodyLines(name, command, args, output, width, isDiff, Integer.MAX_VALUE, mode);
    }

    private List<Line> toolBodyLines(
            final String name,
            final String command,
            final String args,
            final String output,
            final int width,
            final boolean isDiff,
            final int limit,
            final DiffViewMode mode) {
        final var key = new ToolBodyCache.Key(name, command, args, output, width, isDiff, limit, mode);
        return new ArrayList<>(toolBodyCache.get(key, () -> {
            final var lines = uncachedToolBodyLines(name, command, args, output, width, isDiff, limit, mode);
            return lines.size() > limit ? lines.subList(0, limit) : lines;
        }));
    }

    private List<Line> uncachedToolBodyLines(
            final String name,
            final String command,
            final String args,
            final String output,
            final int width,
            final boolean isDiff,
            final int limit,
            final DiffViewMode mode) {
        final var body = output == null || (args == null ? output.isBlank() : output.isEmpty())
                ? List.<Line>of()
                : renderToolBody(name, command, output, width, isDiff, limit, args != null, mode);
        if (args == null || args.isBlank()) {
            return new ArrayList<>(body);
        }
        // the Ctrl+O overlay enriches the historical rendering with a structured
        // preview of the meaningful arguments (file content / diff / patch)
        final List<Line> preview;
        try {
            preview = toolArgsPreview.preview(name, args, width, palette, mode);
        } catch (final RuntimeException e) {
            final var fallback = new ArrayList<Line>();
            fallback.add(Line.from(Span.styled("Request", palette.bold(palette.primary()))));
            fallback.addAll(plainToolPreview(args, width, limit));
            if (!body.isEmpty()) {
                fallback.add(Line.from(Span.styled("Result", palette.bold(palette.primary()))));
                fallback.addAll(body);
            }
            return fallback;
        }
        if (preview == null || preview.isEmpty()) {
            return new ArrayList<>(body);
        }
        final var all = new ArrayList<Line>(preview.size() + body.size() + 1);
        all.add(Line.from(Span.styled("Request", palette.bold(palette.primary()))));
        all.addAll(preview);
        if (!body.isEmpty()) {
            all.add(Line.empty());
            all.add(Line.from(Span.styled("Result", palette.bold(palette.primary()))));
            all.addAll(body);
        }
        return all;
    }

    private List<Line> renderToolBody(
            final String name,
            final String command,
            final String output,
            final int width,
            final boolean isDiff,
            final int limit,
            final boolean fullOutput,
            final DiffViewMode mode) {
        final var answers = "ask_user".equals(name) ? askUserCardRenderer.render(output, palette) : null;
        if (answers != null) {
            return answers;
        }
        try {
            if (isDiff || diffRenderer.looksLikeDiff(output)) {
                return diffRenderer.render(output, width, palette, mode, syntaxHighlighter, diffLanguage(command));
            }
            if (!formatToolOutput) {
                return isDiff && !fullOutput
                        ? diffRenderer.render(output, width, palette, mode, syntaxHighlighter, diffLanguage(command))
                        : plainToolPreview(output, width, limit);
            }
            // read/read_symbol print files with a "%6d\t" line-number prefix: the file to
            // highlight is the path the tool read (card/overlay command) or, for read_symbol,
            // the "path:start-end" reference in the output header. When it resolves, the
            // numbered renderer keeps the numbers as a muted gutter and colors the content.
            final var readLanguage = readOutputLanguage(name, command, output);
            if (readLanguage != null) {
                return toolNumberedCodeLines(
                        previewSource(output, Integer.MAX_VALUE, limit), width, readLanguage, limit);
            }
            final var language = ToolOutputLanguage.guess(output, command);
            return switch (toolOutputFormat.detect(output, language)) {
                case DIFF ->
                    diffRenderer.render(output, width, palette, mode, syntaxHighlighter, diffLanguage(command));
                case JSON ->
                    fullOutput
                            ? MarkdownLines.wrapCode(
                                    syntaxHighlighter.highlightLines("json", toolOutputFormat.prettyJson(output)),
                                    Math.max(1, width))
                            : syntaxHighlighter.highlightLines("json", toolOutputFormat.prettyJson(output));
                case XML ->
                    fullOutput
                            ? MarkdownLines.wrapCode(
                                    syntaxHighlighter.highlightLines("xml", toolOutputFormat.prettyXml(output)),
                                    Math.max(1, width))
                            : syntaxHighlighter.highlightLines("xml", toolOutputFormat.prettyXml(output));
                case CODE ->
                    MarkdownLines.wrapCode(
                            syntaxHighlighter.highlightLines(language, previewSource(output, Integer.MAX_VALUE, limit)),
                            Math.max(1, width),
                            limit);
                case YAML -> syntaxHighlighter.highlightLines("yaml", previewSource(output, Integer.MAX_VALUE, limit));
                case MARKDOWN -> transcriptRenderer.renderRegion(output, width, toolStyle, markdownStyles);
                case TEXT -> plainToolPreview(output, width, limit);
            };
        } catch (final RuntimeException e) {
            return isDiff && !fullOutput
                    ? diffRenderer.render(output, width, palette, mode, syntaxHighlighter, diffLanguage(command))
                    : plainToolPreview(output, width, limit);
        }
    }

    private List<Line> plainToolPreview(final String output, final int width, final int limit) {
        if (limit == Integer.MAX_VALUE) {
            return toolOutputLines(output, width, false);
        }
        final var source = previewSource(output, width, limit);
        final var lines = new ArrayList<Line>();
        final var parts = source.split("\n", source.length() < output.length() ? -1 : 0);
        for (final var line : parts) {
            lines.add(Line.from(Span.styled(line, toolStyle)));
        }
        return MarkdownLines.wrapCode(lines, Math.max(1, width), limit);
    }

    /** Bounds work before plain/code wrapping; structured document formats keep their full parse. */
    private static String previewSource(final String output, final int width, final int limit) {
        if (limit == Integer.MAX_VALUE) {
            return output;
        }
        var from = 0;
        var rows = 0L;
        final var columns = Math.max(1, width);
        while (from < output.length()) {
            final var newline = output.indexOf('\n', from);
            final var end = newline < 0 ? output.length() : newline;
            final var available = (long) (limit - rows) * columns;
            if (end - from >= available) {
                return output.substring(0, from + (int) available);
            }
            rows += Math.max(1L, ((long) end - from + columns - 1) / columns);
            if (rows >= limit || newline < 0) {
                return output.substring(0, end);
            }
            from = end + 1;
        }
        return output;
    }

    /**
     * Language of the file a {@code read}/{@code read_symbol} output displays, or {@code null}
     * when the output is not a numbered file body (directory listings, errors, unknown paths).
     */
    private String readOutputLanguage(final String name, final String command, final String output) {
        if (!"read".equals(name) && !"read_symbol".equals(name)) {
            return null;
        }
        if ("read".equals(name)) {
            // card command and overlay command both carry the path (keyCommand/commandOfArgs)
            return SyntaxHighlighter.guessLanguage(command);
        }
        // read_symbol prints "symbol <kind> <fqn> (<file>:<start>-<end>)": the file reference
        // lives in the output header, not in the (symbol-only) tool arguments
        final var header = output == null ? "" : output.lines().findFirst().orElse("");
        final var matcher = READ_SYMBOL_FILE.matcher(header);
        return matcher.find() ? SyntaxHighlighter.guessLanguage(matcher.group(1)) : null;
    }

    /**
     * Renders the numbered body of {@code read}/{@code read_symbol}: each {@code "%6d\t"} prefix
     * becomes a muted gutter span (so the number survives syntax coloring) and the content is
     * tokenized in {@code language}. Header/trailer lines without a number (the "symbol ..."
     * header, "Read directory ...", "{@code ... more lines below}" markers) stay plain, exactly
     * like {@link #toolOutputLines(String, int, boolean)} would render them.
     */
    private List<Line> toolNumberedCodeLines(
            final String output, final int width, final String language, final int limit) {
        final var gutter = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var lines = new ArrayList<Line>();
        for (final var raw : output.split("\n", -1)) {
            final var matcher = NUMBERED_OUTPUT_LINE.matcher(raw);
            if (!matcher.matches()) {
                lines.add(Line.from(Span.styled(raw, toolStyle)));
                continue;
            }
            final var content = matcher.group(2);
            final var spans = new ArrayList<Span>();
            spans.add(Span.styled(matcher.group(1), gutter));
            if (!content.isEmpty()) {
                spans.addAll(syntaxHighlighter
                        .highlightLines(language, content)
                        .getFirst()
                        .spans());
            }
            lines.add(Line.from(spans.toArray(Span[]::new)));
        }
        return MarkdownLines.wrapCode(lines, Math.max(1, width), limit);
    }

    /**
     * The command a recorded tool call ran, extracted from its raw arguments JSON so the
     * overlay can color the output as code ({@link ToolOutputLanguage}). Null when the
     * tool has no command (write_file, edit_file...) or the payload is unreadable.
     */
    private String toolCommandOf(final ToolOutputStore.Entry entry) {
        if (entry.args() == null || entry.args().isBlank()) {
            return null;
        }
        return commandOfArgs(entry.args());
    }

    /** Command extraction shared by the running-tool FIFO alias and the overlay. */
    private String commandOfArgs(final String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank() || "{}".equals(rawArgs.strip())) {
            return null;
        }
        // mirror ConversationTranscript.toolCommand(): any of command/path/workdir
        // can be the live label, so the command-keyed alias matches the running header
        final var sb = new StringBuilder();
        try {
            final var args = toolOutputFormat.parseArguments(rawArgs);
            if (args != null) {
                for (final var key : List.of("command", "path", "workdir")) {
                    if (args.get(key) instanceof final String value && !value.isBlank()) {
                        sb.append(value).append(' ');
                    }
                }
            }
        } catch (final RuntimeException e) {
            // not JSON: no command hint, the output signals then have to speak
        }
        final var cmd = sb.toString().strip();
        return cmd.isEmpty() ? null : cmd;
    }

    /**
     * The raw {@code command} argument of a tool call, exactly what BashTool echoes as
     * its leading stream line ({@code "$ " + command}), so the overlay can drop that
     * echo once it prepends its own command header. Null when the tool has no command.
     */
    private String commandArgOf(final String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank() || "{}".equals(rawArgs.strip())) {
            return null;
        }
        try {
            final var args = toolOutputFormat.parseArguments(rawArgs);
            if (args != null && args.get("command") instanceof final String command && !command.isBlank()) {
                return command;
            }
        } catch (final RuntimeException e) {
            // not JSON: no echo to strip
        }
        return null;
    }

    /**
     * Removes a leading {@code "$ <command>"} line (the echo BashTool streams before any
     * real output) from a running tool's accumulated output, so the modal body does not
     * repeat the command that its bold header already shows. Only the very first line is
     * touched: a command genuinely echoed mid-output is left as-is.
     */
    private String stripLeadingCommandEcho(final String output, final String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank() || output == null || output.isEmpty()) {
            return output;
        }
        final var prefix = "$ " + rawCommand;
        var rest = output.stripLeading();
        if (!rest.startsWith(prefix)) {
            return output;
        }
        rest = rest.substring(prefix.length());
        if (rest.startsWith("\r\n")) {
            rest = rest.substring(2);
        } else if (rest.startsWith("\n") || rest.startsWith("\r")) {
            rest = rest.substring(1);
        }
        return rest.stripLeading();
    }

    private void renderToolOverlay(final Frame frame, final UiState state) {
        final var latestEntry = state.toolOutputs.get(state.toolOverlay.ordinal());
        if (latestEntry != null && latestEntry != state.toolOverlay.entry()) {
            state.toolOverlay = state.toolOverlay.withEntry(latestEntry);
        }
        final var entry = state.toolOverlay.entry();
        final var width = Math.min(Math.max(70, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(14, frame.height() * 4 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.1);
        final var running = entry.running();
        final var glyph =
                running || entry.pending() ? glyphs.running() : entry.failed() ? glyphs.cross() : glyphs.check();
        final var title = " "
                + glyph + " "
                + entry.name()
                + (running ? " - running " : entry.pending() ? " - pending " : " - full output ");
        final var inner = modal(
                frame,
                rect,
                title,
                Color.hex(
                        running
                                ? palette.textMuted()
                                : entry.pending()
                                        ? palette.warning()
                                        : entry.failed() ? palette.error() : palette.success()));
        if (inner == null) {
            return;
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = toolOverlayText(
                entry,
                inner.width(),
                state.toolOverlay.raw(),
                state.diffViewMode,
                state.toolOutputs.files(state.toolOverlay.ordinal()));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        final var overlay = state.toolOverlay;
        state.toolOverlay = new UiState.ToolOverlay(
                overlay.ordinal(), overlay.entry(), Math.clamp(overlay.scroll(), 0, maxScroll), overlay.raw());
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(state.toolOverlay.scroll())
                            .build(),
                    splits.getFirst());
        }
        final var hint = ("skill".equals(entry.name()) ? (state.toolOverlay.raw() ? "r rendered | " : "r raw | ") : "")
                + "Ctrl+O/q/Esc close | Wheel/Up/Down scroll | c copy output | drag or Ctrl+Shift+C copies the selection ";
        final var footerRect = splits.get(1);
        state.toolOverlayFooter = footerRect;
        final var footer = " "
                + (entry.args() == null || entry.args().isBlank()
                        ? ""
                        : ellipsize(entry.args(), Math.max(1, inner.width() - hint.length() - 3)) + "  ")
                + hint;
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(footer, Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                footerRect);
    }

    private Text toolOverlayText(
            final ToolOutputStore.Entry entry,
            final int width,
            final boolean raw,
            final DiffViewMode mode,
            final List<FileMutation> files) {
        if (toolOverlayLayout != null
                && toolOverlayLayout.entry() == entry
                && toolOverlayLayout.width() == width
                && toolOverlayLayout.raw() == raw
                && toolOverlayLayout.mode() == mode
                && toolOverlayLayout.files().equals(files)) {
            return toolOverlayLayout.text();
        }
        final var command = toolCommandOf(entry);
        // Bash echoes its command too; the modal already leads with the complete command header.
        final var output = stripLeadingCommandEcho(entry.output(), commandArgOf(entry.args()));
        final List<Line> body;
        if ("skill".equals(entry.name())) {
            // Skill instructions are a known document, not source to infer from its front matter or code examples.
            body = raw || !formatToolOutput || entry.failed() || entry.pending() || entry.running()
                    ? new ArrayList<>(plainToolPreview(entry.output(), width, Integer.MAX_VALUE))
                    : skillDocumentLines(entry.output(), width);
        } else {
            body = toolBodyLines(
                    entry.name(), command, files.isEmpty() ? entry.args() : null, output, width, false, mode);
        }
        if (!files.isEmpty()) {
            body.addAll(0, appliedDiffLines(files, width, mode));
        }
        if (command != null && !command.isBlank()) {
            body.addAll(0, toolCommandHeader(command, width));
        }
        if (entry.running()) {
            body.add(Line.from(Span.styled("pending… (executing)", Style.EMPTY.fg(Color.hex(palette.textMuted())))));
        }
        final var text = Text.from(body.toArray(Line[]::new));
        // The single overlay slot has the same source/layout budget as the body cache.
        long weight = 2L
                        * files.stream()
                                .mapToLong(file -> file.content() == null
                                        ? 0
                                        : (file.content().oldText() == null
                                                        ? 0L
                                                        : file.content()
                                                                .oldText()
                                                                .length())
                                                + file.content().newText().length())
                                .sum()
                + 2L
                        * (entry.output().length()
                                + (entry.args() == null ? 0L : entry.args().length()));
        for (final var line : body) {
            weight += 64;
            for (final var span : line.spans()) {
                weight +=
                        96L + 2L * (span.content() == null ? 0 : span.content().length());
            }
        }
        toolOverlayLayout =
                weight <= 4L * 1024 * 1024 ? new ToolOverlayLayout(entry, width, raw, mode, files, text) : null;
        return text;
    }

    private List<Line> skillDocumentLines(final String output, final int width) {
        final var document = SkillDocument.parse(output);
        final var lines = new ArrayList<Line>();
        if (document.metadata() != null) {
            lines.add(Line.from(Span.styled("Skill metadata", palette.bold(palette.primary()))));
            lines.addAll(MarkdownLines.wrapCode(
                    syntaxHighlighter.highlightLines("yaml", document.metadata()), Math.max(1, width)));
            lines.add(Line.empty());
        }
        lines.addAll(transcriptRenderer.renderRegion(document.body(), width, toolStyle, markdownStyles));
        return lines;
    }

    /**
     * The header leading the tool output modal: the full command (from the tool
     * args) on its own wrapped lines prefixed by a shell {@code $} prompt, so a long
     * invocation stays fully readable before the args preview and the output. Nothing
     * is ellipsized here - the modal body already scrolls, so wrapping off the edge
     * is the only truncation.
     */
    private List<Line> toolCommandHeader(final String command, final int width) {
        final var marker =
                Span.styled("$ ", Style.EMPTY.fg(Color.hex(palette.textMuted())).bold());
        final var prompt =
                Span.styled(command, Style.EMPTY.fg(Color.hex(palette.text())).bold());
        return new ArrayList<>(MarkdownLines.wrapCode(List.of(Line.from(marker, prompt)), Math.max(8, width)));
    }

    /** Key handling while the error detail modal (Alt+E) is open. */
    private boolean onErrorKey(final Event event, final TuiRunner runner, final UiState state) {
        if (event instanceof PasteEvent) {
            return true;
        }
        if (event instanceof final MouseEvent mouse) {
            return onModalMouse(mouse, runner, state);
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel()
                || key.isKey(KeyCode.ESCAPE)
                || key.isCharIgnoreCase('q')
                || (key.hasAlt() && key.isCharIgnoreCase('e'))) {
            state.errorOpen = false;
            return true;
        }
        if (key.hasCtrl() && key.hasShift() && key.isCharIgnoreCase('c')) {
            copySelection(runner, state);
            return true;
        }
        if (key.isCharIgnoreCase('c')) {
            final var detail = state.errorDetail;
            copyText(
                    runner,
                    state,
                    detail.stack() == null ? detail.message() : detail.message() + "\n" + detail.stack());
            return true;
        }
        state.errorScroll = scrollService.apply(state.errorScroll, scrollService.keyDelta(key));
        return true;
    }

    /** Scrollable modal with the last error message and (when available) its stack trace. */
    private void renderErrorDetail(final Frame frame, final UiState state) {
        final var detail = state.errorDetail;
        final var width = Math.min(Math.max(70, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 3 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.15);
        final var inner = modal(frame, rect, " error details ", Color.hex(palette.error()));
        if (inner == null) {
            return;
        }
        final var lines = new ArrayList<Line>();
        for (final var row : detail.message().split("\n", -1)) {
            lines.addAll(MarkdownLines.wrapCode(
                    List.of(Line.from(Span.styled(
                            row, Style.EMPTY.fg(Color.hex(palette.error())).bold()))),
                    Math.max(1, inner.width())));
        }
        lines.add(Line.empty());
        if (detail.stack() != null && !detail.stack().isBlank()) {
            for (final var l : detail.stack().split("\n")) {
                lines.addAll(MarkdownLines.wrapCode(
                        List.of(Line.from(Span.styled(l, Style.EMPTY.fg(Color.hex(palette.textMuted()))))),
                        Math.max(1, inner.width())));
            }
        } else {
            lines.add(Line.from(Span.styled("(no stack trace)", Style.EMPTY.fg(Color.hex(palette.textMuted())))));
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = Text.from(lines.toArray(Line[]::new));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        state.errorScroll = Math.clamp(state.errorScroll, 0, maxScroll);
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(state.errorScroll)
                            .build(),
                    splits.getFirst());
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                " Esc close | Wheel/Up/Down scroll | c copy | drag or Ctrl+Shift+C copies the selection ",
                                Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    /** Key handling for the retained informational notification. */
    private boolean onNotificationKey(final Event event, final TuiRunner runner, final UiState state) {
        if (event instanceof PasteEvent) {
            return true;
        }
        if (event instanceof final MouseEvent mouse) {
            return onModalMouse(mouse, runner, state);
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel()
                || key.isKey(KeyCode.ESCAPE)
                || key.isCharIgnoreCase('q')
                || (key.hasAlt() && key.isCharIgnoreCase('i'))) {
            state.notificationDetail = null;
        } else if (key.hasCtrl() && key.hasShift() && key.isCharIgnoreCase('c')) {
            copySelection(runner, state);
        } else if (key.isCharIgnoreCase('c')) {
            copyText(runner, state, state.notificationDetail);
        } else {
            state.notificationScroll = scrollService.apply(state.notificationScroll, scrollService.keyDelta(key));
        }
        return true;
    }

    private void renderNotificationDetail(final Frame frame, final UiState state) {
        final var width = Math.min(Math.max(70, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 3 / 5), frame.height());
        final var inner =
                modal(frame, Modal.rect(frame, width, height, 0.15), " notification ", palette.color(palette.info()));
        if (inner == null) {
            return;
        }
        final var lines = new ArrayList<Line>();
        for (final var row : state.notificationDetail.split("\n", -1)) {
            lines.addAll(MarkdownLines.wrapCode(
                    List.of(Line.from(Span.styled(row, palette.style(palette.text())))), Math.max(1, inner.width())));
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        state.notificationScroll = Math.clamp(
                state.notificationScroll,
                0,
                Math.max(0, lines.size() - Math.max(1, splits.getFirst().height())));
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(lines))
                            .overflow(Overflow.CLIP)
                            .scroll(state.notificationScroll)
                            .build(),
                    splits.getFirst());
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                " Esc close | Wheel/Up/Down scroll | c copy | Ctrl+Shift+C copy selection ",
                                palette.style(palette.textMuted())))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    /** Ctrl+R: fuzzy-searchable prompt history picker (falls back to cycling when empty). */
    private void openHistoryPicker(final UiState state) {
        final var entries = promptHistory.entries();
        if (entries.isEmpty()) {
            recallHistory(state, true);
            return;
        }
        openPicker(
                state,
                "prompt history",
                entries,
                0,
                selected -> {
                    beforeEdit(state);
                    state.input.setText(selected);
                    positionCursorAtEnd(state);
                    dismissCompletion(state);
                    notify(state, "recalled from history");
                },
                () -> notify(state, "history picker cancelled"));
    }

    /** Alt+Up/Alt+Down (or Alt+K/Alt+J): jump to the previous/next tool card in the transcript. */
    private void navigateTools(final UiState state, final int direction) {
        // include run headers (negative ordinals: -runId) so a collapsed run is a single stop
        final var anchors = state.toolAnchors.stream()
                .filter(a -> a.ordinal() >= 0 || a.ordinal() < 0 && a.row() >= 0)
                .toList();
        if (anchors.isEmpty()) {
            state.toolNavIndex = -1;
            state.focusedToolOrdinal = -1;
            notify(state, "no tool cards yet");
            return;
        }
        // resolve the focused ordinal against the fresh anchor list first so navigation
        // survives re-renders (anchors are rebuilt each frame from the transcript layout)
        final var idx = nextToolAnchorIndex(
                anchors.stream().map(UiState.ToolAnchor::ordinal).toList(),
                state.focusedToolOrdinal,
                state.toolNavIndex,
                direction);
        state.toolNavIndex = idx;
        final var anchor = anchors.get(idx);
        state.focusedToolOrdinal = anchor.ordinal();
        state.pendingJumpRow = anchor.row();
        if (anchor.ordinal() < 0) {
            // -runId: the run header stop
            final var runId = -anchor.ordinal();
            final var collapsed =
                    state.runCollapsed.getOrDefault(runId, state.showToolActions == ToolOutputVisibility.COLLAPSED);
            notify(state, "tool run #" + runId + (collapsed ? " (collapsed - click chevron to expand)" : ""));
            return;
        }
        notify(
                state,
                "tool #" + anchor.ordinal()
                        + (state.collapsedTools.contains(anchor.ordinal()) ? " (collapsed - click to expand)" : ""));
    }

    /**
     * Pure navigation resolver over tool card ordinals (in transcript order). Returns the
     * index to focus for a step in {@code direction} (-1 up, 1 down), wrapping around the
     * ends. {@code focusedOrdinal} wins over {@code fallbackIndex} when still present so
     * navigation stays stable across re-renders; -1 is returned only when there is nothing.
     */
    static int nextToolAnchorIndex(
            final List<Long> ordinals, final long focusedOrdinal, final int fallbackIndex, final int direction) {
        if (ordinals.isEmpty()) {
            return -1;
        }
        var idx = -1;
        for (var i = 0; i < ordinals.size(); i++) {
            if (ordinals.get(i) == focusedOrdinal) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            idx = fallbackIndex >= 0 && fallbackIndex < ordinals.size() ? fallbackIndex : -1;
        }
        if (idx < 0) {
            return direction > 0 ? 0 : ordinals.size() - 1;
        }
        return Math.floorMod(idx + direction, ordinals.size());
    }

    /** Removes the queued prompt at the given index (Ctrl+X pops the most recent one). */
    private static void removeQueued(final UiState state, final int index) {
        final var items = new ArrayList<>(state.pendingPrompts);
        if (index < 0 || index >= items.size()) {
            return;
        }
        final var removed = items.remove(index);
        state.pendingPrompts.clear();
        state.pendingPrompts.addAll(items);
        notify(state, "removed queued prompt (" + items.size() + " still pending)");
        if (!removed.isBlank()) {
            final var message = "removed: " + TuiInputSupport.firstLine(removed);
            state.pushToast(message, ToastLevel.INFO);
            state.lastNotification = "removed queued prompt (" + items.size() + " still pending)\nremoved: " + removed;
        }
    }

    /** Compact strip above the prompt listing queued prompts (click a row or Ctrl+X to remove). */
    private void renderQueue(final Rect rect, final Frame frame, final UiState state) {
        state.queueRects.clear();
        final var items = List.copyOf(state.pendingPrompts);
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var accent = Style.EMPTY.fg(Color.hex(palette.primary())).bold();
        for (var i = 0; i < rect.height() && i < items.size(); i++) {
            final var lineRect = new Rect(rect.x(), rect.y() + i, rect.width(), 1);
            state.queueRects.add(lineRect);
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(
                                    Span.styled(" " + glyphs.bar() + " queued ", accent),
                                    Span.styled(
                                            ellipsize(
                                                    TuiInputSupport.firstLine(items.get(i)),
                                                    Math.max(1, rect.width() - 24)),
                                            muted),
                                    Span.styled("  (Ctrl+X removes)", muted))))
                            .overflow(Overflow.CLIP)
                            .build(),
                    lineRect);
        }
    }

    private void renderToast(final Frame frame, final UiState state) {
        final var toast = state.toast;
        if (toast == null || tuiConfiguration.toastDurationMillis() <= 0) {
            return;
        }
        final var color =
                switch (toast.level()) {
                    case SUCCESS -> palette.success();
                    case ERROR -> palette.error();
                    case WARNING -> palette.warning();
                    case INFO -> palette.info();
                };
        final var accent = palette.style(color);
        final var width = Math.min(frame.width(), Math.max(16, Math.min(72, CharWidth.of(toast.message()) + 4)));
        final var rows = new ArrayList<Line>();
        for (final var row : toast.message().split("\n", -1)) {
            rows.addAll(MarkdownLines.wrapCode(List.of(Line.from(Span.styled(row, accent))), Math.max(1, width - 2)));
        }
        final var height = Math.min(frame.height(), Math.max(3, rows.size() + 2));
        final var rect = new Rect(
                Math.max(0, (frame.width() - width) / 2), Math.max(0, frame.height() - height - 3), width, height);
        final var inner =
                modal(frame, rect, " " + toast.level().name().toLowerCase(Locale.ROOT) + " ", palette.color(color));
        if (inner == null) {
            return;
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(rows))
                        .overflow(Overflow.ELLIPSIS)
                        .build(),
                inner);
    }

    /**
     * Shared busy spinner per tab: one cached {@link Spinner} widget per style (ascii
     * frame sets when {@code tui.glyphs=ascii}) driven by the single per-tab
     * {@link SpinnerState} advanced once per frame. All busy lines (pinned progress,
     * in-pane thinking indicator, status strip) render through this helper so two
     * different spinners (e.g. the first-token {@code CLOCK} and the streaming
     * {@code SQUARE_CORNERS}) can never animate on screen at the same time.
     */
    private final ConcurrentHashMap<SpinnerStyle, Spinner> spinnerCache = new ConcurrentHashMap<>();

    private Spinner spinnerOf(final SpinnerStyle style) {
        return spinnerCache.computeIfAbsent(style, key -> {
            final var builder = Spinner.builder();
            if (glyphs.ascii()) {
                builder.frameSet(
                        switch (key) {
                            case CLOCK -> SpinnerFrameSet.of("|", "/", "-", "\\");
                            case SQUARE_CORNERS -> SpinnerFrameSet.of("+", "x", "*", "x");
                            case DOTS -> SpinnerFrameSet.of(".", "o", "O", "o");
                            case BOUNCING_BALL -> SpinnerFrameSet.of(".", "o", "O", "o");
                            default -> SpinnerFrameSet.of("|", "/", "-", "\\");
                        });
            } else {
                builder.spinnerStyle(key);
            }
            return builder.build();
        });
    }

    /** Leading busy glyph + separating space for the given phase, from the shared spinner. */
    private String spinnerPrefix(final UiState state) {
        final var spinner = spinnerOf(SpinnerStyle.SQUARE_CORNERS);
        final var maxWidth = spinner.maxFrameWidth();
        final var frame = spinner.frameSet().frame((int) state.spinnerState.tick());
        // reserve the widest frame's columns so the label never shifts as frames cycle
        // (a 2-column CLOCK emoji next to 1-column box frames)
        return frame + " ".repeat(Math.max(0, maxWidth - CharWidth.of(frame))) + " ";
    }

    /**
     * Single busy-phase derivation shared by every busy line (status strip, pinned bottom
     * line, in-pane thinking indicator): a running tool, then live reasoning, then the armed
     * wait, then generic work. Priority matters: while thought deltas stream the first-token
     * CLOCK wait must yield to the SQUARE_CORNERS thinking phase so two spinners can never
     * animate at once.
     *
     * @return the {@code (spinner style, label)} of the current busy phase, or {@code null}
     *     when the agent is not busy
     */
    private Map.Entry<SpinnerStyle, String> busyIndicator(final UiState state) {
        if (state.model.runningTool() != null) {
            final var name = state.model.runningToolCommand() == null
                            || state.model.runningToolCommand().isBlank()
                    ? state.model.runningTool()
                    : state.model.runningToolCommand();
            return Map.entry(SpinnerStyle.SQUARE_CORNERS, BusyLabels.calling(ellipsize(name, 60)));
        }
        if (state.model.thinkingLength() > 0) {
            final var secs = Math.max(0, (clock.millis() - state.thinkingStartedAtMillis) / 1000);
            return Map.entry(SpinnerStyle.SQUARE_CORNERS, BusyLabels.thinking(secs));
        }
        final var wait = activeWait(state);
        if (wait != null) {
            return Map.entry(wait, waitLabel(state));
        }
        if (state.model.busy()) {
            return Map.entry(SpinnerStyle.DOTS, BusyLabels.working());
        }
        return null;
    }

    /**
     * Live label for the armed wait phase. For the first-token CLOCK wait this renders
     * {@link BusyLabels#thinking(long)} with the elapsed seconds since the wait was armed, so a
     * slow/backoff wait visibly ticks up instead of staying frozen on the submit-time
     * {@code "thinking... (0s)"}. The label no longer depends on {@code state.model.status()},
     * which other event paths can overwrite mid-turn (dropping the spinner entirely before this
     * fix); it is driven purely by the wait's own armed-at clock.
     */
    private String waitLabel(final UiState state) {
        final var wait = state.wait;
        if (wait == null
                || wait.style() != SpinnerStyle.CLOCK
                || wait.armedAtMillis() < 0
                // only the first-token "thinking..." wait gets the elapsed counter; the other
                // CLOCK waits (an interrupt in progress) carry their own fixed message
                || !wait.message().startsWith("thinking...")) {
            return wait == null ? "" : wait.message();
        }
        final var secs = Math.max(0, (clock.millis() - wait.armedAtMillis()) / 1000);
        return BusyLabels.thinking(secs);
    }

    private static void waiting(final UiState state, final String message, final SpinnerStyle style) {
        state.model.status(message);
        state.wait = new UiState.Wait(message, style, state.clock.millis());
    }

    /**
     * The spinner style of the currently armed wait phase, or {@code null} when none is active.
     * A CLOCK wait armed while a turn runs is the first-token "thinking... (Ns)" phase: it must
     * survive mid-turn status rewrites (a retryable-error recovery, a queued prompt, ... change
     * the transcript status text). Gating it on that text let any such rewrite silently drop the
     * wait spinner while the turn was still waiting on the network — the very "looks idle but is
     * busy" bug — so while {@code busy()} is true we keep the CLOCK wait regardless of the status
     * string. The other waits (background resync/compact, SQUARE_CORNERS/BOUNCING_BALL) still
     * require the status to reflect them, so a completed background op drops its spinner exactly
     * as before.
     */
    private static SpinnerStyle activeWait(final UiState state) {
        final var wait = state.wait;
        if (wait == null) {
            return null;
        }
        if (state.model.busy() && wait.style() == SpinnerStyle.CLOCK) {
            return wait.style();
        }
        return wait.message().equals(state.model.status()) ? wait.style() : null;
    }

    private static void notify(final UiState state, final String message) {
        state.notification = message;
        state.notificationShownAt = System.currentTimeMillis();
        if (message != null && !message.isBlank()) {
            state.lastNotification = message;
        }
    }

    /**
     * Tracks the compaction lifecycle on the notification slot. A "Compaction in progress..." INFO
     * notice arms the indicator and pins it in the ⓘ slot, bypassing the 5s TTL while the summarizer
     * runs; the "Compacted: ..." result notice (INFO) clears it and shows the outcome with the normal
     * TTL. A "Compaction gate: ..." escalation is an ERROR notice: its full message already lands in
     * the red ✗ banner, so the ⓘ slot is only cleared (a long ⓘ duplicate would crowd the ✗ out of
     * the status row). Everything else (WARN/ERROR banner paths) is left alone.
     */
    private static void handleCompactionNotice(final UiState state, final AgentEvent.Notice notice) {
        switch (notice.compaction()) {
            case START -> {
                state.compactionActive = true;
                notify(state, "compacting...");
            }
            case GATE_ESCALATED -> {
                state.compactionActive = false;
                notify(state, null);
            }
            case COMPLETED -> {
                state.compactionActive = false;
                notify(state, notice.message());
            }
            case NONE -> {
                // not a compaction lifecycle notice: leave the indicator untouched
            }
        }
    }

    /** Status-strip notifications expire after a few seconds so they don't linger as stale noise. */
    private static final long NOTIFICATION_TTL_MS = 5_000L;

    /** True while an error recorded via {@link #modelError} is awaiting acknowledgment. */
    private static boolean activeError(final UiState state) {
        return state.errorMessage != null;
    }

    private static void modelError(final UiState state, final String message) {
        modelError(state, message, null);
    }

    /** Records a persistent error: persisted detail (message + stack) openable with Alt+E; the status strip shows it in red. */
    private static void modelError(final UiState state, final String message, final Throwable error) {
        modelError(state, message, error, false);
    }

    /**
     * Records an error: persisted detail (message + stack) openable with Alt+E; the status strip shows it
     * in red. When {@code transient_} is true the banner is auto-cleared once the next request succeeds
     * (recoverable retry notices), otherwise it stays until dismissed (Alt+X / Esc / clicking the ✖) or
     * the next submit - see {@code onEvent}.
     */
    private static void modelError(
            final UiState state, final String message, final Throwable error, final boolean transient_) {
        state.errorMessage = message;
        state.errorDetail = new UiState.ErrorDetail(message, error == null ? null : stackTraceOf(error), transient_);
        state.errorScroll = 0;
    }

    private static String stackTraceOf(final Throwable error) {
        final var sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static void clearError(final UiState state) {
        state.errorMessage = null;
        state.errorDetail = null;
        state.errorOpen = false;
    }

    private void renderApproval(final Frame frame, final UiState state) {
        final var approval = state.approvals.peek();
        final var width = Math.min(Math.max(60, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 2 / 3), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.2);
        final var warning = Color.hex(palette.warning());
        final var inner = modal(frame, rect, " approval required ", warning);
        if (inner == null) {
            return;
        }
        final var titleStyle = Style.EMPTY.fg(warning).bold();
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(ellipsize(approval.title(), inner.width()), titleStyle))))
                        .build(),
                new Rect(inner.x(), inner.y(), inner.width(), 1));
        final var actionsY = inner.y() + Math.max(1, inner.height() - 1);
        final var bodyHeight = Math.max(1, actionsY - inner.y() - 1);
        renderApprovalDetail(frame, approval, new Rect(inner.x(), inner.y() + 1, inner.width(), bodyHeight));
        renderApprovalActions(frame, approval, new Rect(inner.x(), actionsY, inner.width(), 1));
    }

    private void renderApprovalDetail(final Frame frame, final Approval approval, final Rect rect) {
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var lines = detailLines(approval.detail());
        if (lines.isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled("(no additional detail)", muted))))
                            .build(),
                    rect);
            return;
        }
        final var scroll = approval.detailScroll();
        final var total = lines.size();
        for (int row = 0; row < rect.height(); row++) {
            final var idx = scroll + row;
            if (idx >= total) {
                break;
            }
            final var line = lines.get(idx);
            final var style =
                    idx == 0 ? Style.EMPTY.fg(Color.hex(palette.text())).bold() : muted;
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(ellipsize(line, rect.width()), style))))
                            .build(),
                    new Rect(rect.x(), rect.y() + row, rect.width(), 1));
        }
        final var visible = Math.min(total, rect.height());
        if (scroll + visible < total) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(
                                    Line.from(Span.styled("⤷ " + (total - scroll - visible) + " more - PgDn", muted))))
                            .build(),
                    new Rect(rect.x(), rect.y() + rect.height() - 1, rect.width(), 1));
        }
    }

    private void renderApprovalActions(final Frame frame, final Approval approval, final Rect rect) {
        final var actions = Approval.Action.values();
        final var accent = Color.hex(palette.accent());
        final var muted = Color.hex(palette.textMuted());
        final var textColor = Color.hex(palette.text());
        final var spans = new ArrayList<Span>(actions.length);
        for (int i = 0; i < actions.length; i++) {
            final var label = "[" + (i + 1) + "] " + actions[i].label();
            final var selected = i == approval.action();
            if (selected) {
                spans.add(Span.styled(" " + label + " ", Style.EMPTY.fg(accent).bold()));
            } else {
                spans.add(Span.styled(" " + label + " ", i == 0 ? Style.EMPTY.fg(textColor) : Style.EMPTY.fg(muted)));
            }
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(spans.toArray(new Span[0]))))
                        .build(),
                rect);
    }

    private static List<String> detailLines(final String detail) {
        if (detail == null || detail.isBlank()) {
            return List.of();
        }
        return detail.strip()
                .lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty())
                .toList();
    }

    private void openPicker(
            final UiState state,
            final String title,
            final List<String> items,
            final int pageSize,
            final Consumer<String> onSelect,
            final Runnable onCancel) {
        state.picker = new PickerPrompt(
                title,
                items,
                pageSize,
                selected -> {
                    state.picker = null;
                    onSelect.accept(selected);
                },
                () -> {
                    state.picker = null;
                    onCancel.run();
                });
    }

    private void renderPicker(final Frame frame, final UiState state) {
        final var picker = state.picker;
        final var accent = Color.hex(palette.accent());
        final var textColor = Color.hex(palette.text());
        final var muted = Color.hex(palette.textMuted());
        final var width = Math.min(Math.max(50, frame.width() * 3 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 2 / 3), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.2);
        final var inner = modal(frame, rect, " " + picker.title + " ", accent);
        if (inner == null) {
            return;
        }
        // filter line
        final var filterRect = new Rect(inner.x(), inner.y(), inner.width(), 1);
        final var filterLabel = "filter: ";
        final var filterText = filterLabel + picker.filter + (picker.filter.length() > 0 ? "" : "_");
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(
                                Span.styled(filterLabel, Style.EMPTY.fg(muted)),
                                Span.styled(
                                        picker.filter.toString(),
                                        filterRect.width() <= filterLabel.length()
                                                ? Style.EMPTY.fg(textColor)
                                                : Style.EMPTY.fg(accent).bold()))))
                        .overflow(Overflow.CLIP)
                        .build(),
                filterRect);
        // items
        final var listRect = new Rect(inner.x(), inner.y() + 1, inner.width(), Math.max(1, inner.height() - 2));
        renderPickerList(frame, picker, listRect, textColor, muted, accent);
        // footer hint
        final var footerRect = new Rect(inner.x(), inner.y() + Math.max(1, inner.height() - 1), inner.width(), 1);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "type to filter | Up/Down move | Enter select | Esc cancel", Style.EMPTY.fg(muted)))))
                        .overflow(Overflow.CLIP)
                        .build(),
                footerRect);
    }

    private void renderPickerList(
            final Frame frame,
            final PickerPrompt picker,
            final Rect rect,
            final Color textColor,
            final Color muted,
            final Color accent) {
        final var visible = picker.visible();
        final var rows = Math.max(1, rect.height());
        final var total = visible.size();
        picker.prepareList(rect);
        // paged mode: show one pageSize-page, and let the selection stay within it (PgUp/PgDn
        // or the page header navigate across pages). Non-paged mode keeps the historical viewport.
        final var pageSize = picker.pageSize();
        final int from;
        int to;
        if (pageSize > 0) {
            final var page = picker.page();
            final var pageStart = Math.min(page * pageSize, total);
            final var pageEnd = Math.min(total, pageStart + pageSize);
            // a viewport inside the page: a page may hold more rows than the modal shows, so keep
            // the selection visible while never rendering past the current page's last row
            final var pageRows = pageEnd - pageStart;
            final var maxScroll = Math.max(0, pageRows - rows);
            final var scroll = Math.min(Math.max(0, picker.selected() - pageStart - rows / 2), Math.max(0, maxScroll));
            from = Math.min(pageStart + scroll, total);
            to = Math.min(pageEnd, from + rows);
        } else {
            final var maxScroll = Math.max(0, total - rows);
            final var scroll = Math.min(Math.max(0, picker.selected() - rows / 2), Math.max(0, maxScroll));
            from = Math.min(scroll, total);
            to = Math.min(total, scroll + rows);
        }
        for (int row = from; row < to; row++) {
            final var lineRect = new Rect(rect.x(), rect.y() + (row - from), rect.width(), 1);
            picker.recordItem(lineRect, visible.get(row));
            final var selected = row == picker.selected();
            final var value = visible.get(row);
            final var marker = selected ? "▸ " : "  ";
            final var style = selected ? Style.EMPTY.fg(accent).bold() : Style.EMPTY.fg(textColor);
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(
                                    Span.styled(marker, selected ? style : Style.EMPTY.fg(muted)),
                                    Span.styled(ellipsize(value, Math.max(1, rect.width() - 2)), style))))
                            .overflow(Overflow.CLIP)
                            .build(),
                    lineRect);
        }
        // page / scroll hint when the list overflows the window or the page
        if (total > (pageSize > 0 ? pageSize : rows) && total > 0) {
            final var page = picker.page() + 1;
            final var pages = picker.pageCount();
            final var hint = pageSize > 0 ? "page " + page + "/" + pages : (from + 1) + "/" + total;
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled("▴ " + hint + " ▾", Style.EMPTY.fg(muted)))))
                            .build(),
                    new Rect(
                            rect.x() + rect.width() - Math.max(12, hint.length() + 5),
                            rect.y() + Math.max(0, rect.height() - 1),
                            Math.max(12, hint.length() + 5),
                            1));
        }
        if (total == 0) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled("(no matches)", Style.EMPTY.fg(muted)))))
                            .build(),
                    rect);
        }
    }

    private boolean onPickerMouse(final MouseEvent mouse, final UiState state) {
        final var picker = state.picker;
        if (picker == null) {
            return true;
        }
        if (!(mouse.isPress() || mouse.isClick()) || !mouse.isLeftButton()) {
            return true;
        }
        final var value = picker.itemAt(mouse.x(), mouse.y());
        if (value != null) {
            picker.onSelect().accept(value);
            state.picker = null;
        }
        return true;
    }

    private void renderFeedbackPrompt(final Frame frame, final UiState state) {
        final var prompt = state.feedbackPrompt;
        final var textStyle = Style.EMPTY.fg(Color.hex(palette.text()));
        final var warning = Color.hex(palette.warning());
        final var width = Math.min(Math.max(60, frame.width() * 4 / 5), frame.width());
        final var innerWidth = Math.max(1, width - 2);
        final var isConfirmTab = prompt.tab() >= prompt.questions().size();
        final var qIndex = !isConfirmTab ? prompt.tab() : 0;
        final var fq = prompt.questions().get(qIndex);

        // The question is word-wrapped with a display-width aware wrap (so CJK/wide glyphs count
        // their real 2 terminal cells, unlike the length-based wrapProse). The modal then pre-sizes
        // to the WIDEST tab - question rows + every option's wrapped rows + the whole confirm
        // summary - so switching tabs never clips and long question/option sets don't pop a scroll
        // window the moment the user lands on them (they only scroll when the 4/5 frame cap bites).
        final var questionLines = TuiInputSupport.wrapProseWidth(fq.question.prompt(), innerWidth);
        final var questionRows = isConfirmTab ? 1 : Math.max(1, questionLines.size());
        var widestNeed = questionRows;
        final var optionWidth = Math.max(1, innerWidth - 2);
        for (int i = 0; i < prompt.questions().size(); i++) {
            final var question = prompt.questions().get(i).question;
            final var qr = Math.max(
                    1,
                    TuiInputSupport.wrapProseWidth(question.prompt(), innerWidth)
                            .size());
            var or = 0;
            if (question.options() != null) {
                for (final var opt : question.options()) {
                    or += MarkdownLines.wrapCode(List.of(Line.from(Span.raw(opt))), optionWidth)
                            .size();
                }
            }
            widestNeed = Math.max(widestNeed, qr + or);
        }
        final var summaryLines = feedbackSummaryLines(prompt, innerWidth);
        widestNeed = Math.max(widestNeed, summaryLines.size());
        // ceiling raised 3/4 -> 4/5 (matching the tool-output overlay) and floor 12 -> 14
        final var height = Math.min(
                Math.max(14, 8 + prompt.questions().size() * 3 + widestNeed), Math.max(14, frame.height() * 4 / 5));
        final var rect = Modal.rect(frame, width, height, 0.25);
        final var inner = modal(frame, rect, " user request ", Color.hex(palette.accent()));
        if (inner == null || inner.height() < 4) {
            return;
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.fill())
                .split(inner);
        final var tabsRect = splits.get(0);
        prompt.tabsRect(tabsRect);
        final var bodyRect = splits.get(1);
        buildTabs(frame.buffer(), prompt, tabsRect);

        // the modal height is capped at 4/5 of the frame, so on small terminals the question
        // area may have to give rows back, but the input block (3 rows) must never shrink
        // below its natural height
        final var questionArea = Math.min(questionRows, Math.max(1, bodyRect.height() - 3));
        final var content = Layout.vertical()
                .constraints(Constraint.length(questionArea), Constraint.fill(), Constraint.length(3))
                .split(bodyRect);

        // the scrollable body: the wrapped question on question tabs, the whole confirm summary on
        // the confirm tab. Unlike the previous Overflow.CLIP (which silently dropped anything past
        // the visible rows), it scrolls with the wheel / PgUp / PgDn and shows a n/total affordance,
        // so content that overflows the capped modal is never an unreachable tail.
        final var promptStyle =
                isConfirmTab ? textStyle : Style.EMPTY.fg(warning).bold();
        final List<Line> scrollLines;
        final Rect scrollRect;
        if (isConfirmTab) {
            scrollLines = summaryLines;
            scrollRect = content.get(1);
        } else {
            scrollLines = new ArrayList<>(questionLines.size());
            for (final var wrapped : questionLines) {
                scrollLines.add(Line.from(Span.styled(wrapped, promptStyle)));
            }
            scrollRect = content.get(0);
        }
        final var scrollAreaRows = Math.max(1, scrollRect.height());
        final var maxScroll = Math.max(0, scrollLines.size() - scrollAreaRows);
        prompt.questionMaxScroll(maxScroll);
        final var clamped = Math.min(prompt.questionScroll(), maxScroll);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(scrollLines.toArray(Line[]::new)))
                        .overflow(Overflow.CLIP)
                        .scroll(clamped)
                        .build(),
                scrollRect);
        if (scrollLines.size() > scrollAreaRows) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(
                                    (clamped > 0 ? "▲ " : "  ") + (clamped + 1) + "/" + scrollLines.size()
                                            + (clamped + scrollAreaRows < scrollLines.size() ? " ▼" : "  "),
                                    Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                            .build(),
                    new Rect(scrollRect.x(), scrollRect.y() + Math.max(0, scrollRect.height() - 1), width / 3, 1));
        }

        if (!isConfirmTab) {
            renderFeedbackOptions(frame, content.get(1), fq, prompt);
        }
        final var inputRect = content.get(2);
        final var inputBlock = Block.builder()
                .title(" your own answer ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(Color.hex(prompt.focusInput() ? palette.borderActive() : palette.border()))
                .build();
        frame.renderWidget(inputBlock, inputRect);
        final var inputInner = inputBlock.inner(inputRect);
        if (!inputInner.isEmpty()) {
            final var area = TextArea.builder()
                    .placeholder(
                            ownEntrySelected(fq)
                                    ? "type your answer here" + (prompt.focusInput() ? " (Tab to exit)" : "")
                                    : "or type your own answer"
                                            + (prompt.focusInput() ? " (Tab to exit)" : " (Tab to enter)"))
                    .build();
            area.renderWithCursor(inputInner, frame.buffer(), fq.ownAnswer(), frame);
        }
    }

    private void buildTabs(final Buffer buffer, final FeedbackPrompt prompt, final Rect tabsRect) {
        final var tabs = Tabs.from(buildTitles(prompt));
        tabs.render(tabsRect, buffer, new TabsState(prompt.tab()));
    }

    private String[] buildTitles(final FeedbackPrompt prompt) {
        final var titles = new String[prompt.questions().size() + 1];
        for (int i = 0; i < prompt.questions().size(); i++) {
            titles[i] = (i + 1) + ":" + word(prompt.questions().get(i).question.prompt());
        }
        titles[prompt.questions().size()] = "confirm";
        return titles;
    }

    private static String word(final String prompt) {
        final var w = prompt.strip();
        final var idx = w.indexOf(" ");
        final var first = (idx < 0 ? w : w.substring(0, idx)).trim();
        // a single-word question (eg. a long code line) must not overflow the tab bar
        return first.length() > 13 ? first.substring(0, 13) + "..." : first;
    }

    private void renderFeedbackOptions(
            final Frame frame, final Rect rect, final FeedbackQuestion fq, final FeedbackPrompt prompt) {
        final var accent = Color.hex(palette.accent());
        final var muted = Color.hex(palette.textMuted());
        final var textColor = Color.hex(palette.text());
        final var options = fq.question.options();
        final var opts = options == null ? 0 : options.size();
        fq.optionRects().clear();
        if (opts == 0) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(
                                    "(no predefined options - use 'your own answer' below)", Style.EMPTY.fg(muted)))))
                            .build(),
                    rect);
            return;
        }
        if (!fq.question.multiple()) {
            fq.selectedIndex(Math.clamp(fq.selectedIndex(), 0, opts));
        } else {
            prompt.optionsFocus(Math.clamp(prompt.optionsFocus(), 0, opts));
        }
        // windowed rendering over visual rows: each option is wrapped to the panel
        // width (character-exact, so long option texts stay fully readable), and the
        // window keeps the focused logical option visible, scrolling only when there
        // are more visual rows (wrapped options + the own-answer row) than the panel
        // can show at once
        final var optionWidth = Math.max(1, rect.width() - 2);
        final var wrapped = new ArrayList<List<Line>>(opts);
        final var optionStarts = new int[opts + 1];
        var totalRows = 0;
        for (int i = 0; i < opts; i++) {
            optionStarts[i] = totalRows;
            final var lines = wrappedOptionLines(fq, i, optionWidth, accent, muted, textColor);
            wrapped.add(lines);
            totalRows += lines.size();
        }
        optionStarts[opts] = totalRows;
        totalRows += 1; // the own-answer pseudo-option
        final var rows = Math.max(1, rect.height());
        final var focused = fq.question.multiple() ? prompt.optionsFocus() : Math.max(fq.selectedIndex(), 0);
        final var maxScroll = Math.max(0, totalRows - rows);
        final var scroll = Math.min(Math.max(0, optionStarts[Math.min(focused, opts)] - rows / 2), maxScroll);
        final var from = Math.min(scroll, totalRows);
        final var to = Math.min(totalRows, scroll + rows);
        var visual = 0;
        for (int i = 0; i < opts; i++) {
            for (final var line : wrapped.get(i)) {
                if (visual >= from && visual < to) {
                    final var lineRect = new Rect(rect.x(), rect.y() + (visual - from), rect.width(), 1);
                    fq.optionRects().add(new FeedbackOptionRow(lineRect, i));
                    frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), lineRect);
                }
                visual++;
            }
        }
        if (visual >= from && visual < to) {
            final var lineRect = new Rect(rect.x(), rect.y() + (visual - from), rect.width(), 1);
            fq.optionRects().add(new FeedbackOptionRow(lineRect, opts));
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(buildOwnAnswerLine(fq, opts, accent, muted, textColor))))
                            .build(),
                    lineRect);
        }
        if (totalRows > rows) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(
                                    (scroll > 0 ? "▲ " : "  ") + (scroll + 1) + "/" + totalRows
                                            + (scroll + rows < totalRows ? " ▼" : "  "),
                                    Style.EMPTY.fg(muted)))))
                            .build(),
                    new Rect(rect.x(), rect.y() + Math.max(0, rect.height() - 1), rect.width() / 2, 1));
        }
    }

    /**
     * Wraps one real option to the given width, marker on the first row only; the
     * continuation rows carry the same text style so a selected/checked option
     * highlights across its whole wrapped block. Character-exact hard split via
     * {@link MarkdownLines#wrapCode}: no option text is ever dropped.
     */
    private List<Line> wrappedOptionLines(
            final FeedbackQuestion fq,
            final int idx,
            final int width,
            final Color accent,
            final Color muted,
            final Color textColor) {
        final var option = fq.question.options().get(idx);
        final var wrapped = MarkdownLines.wrapCode(List.of(Line.from(Span.raw(option))), Math.max(1, width));
        final var out = new ArrayList<Line>(wrapped.size());
        for (int r = 0; r < wrapped.size(); r++) {
            final var text = wrapped.get(r).rawContent();
            if (r == 0) {
                if (fq.question.multiple()) {
                    final var checked = fq.boxes()[idx].isChecked();
                    out.add(Line.from(
                            Span.raw(checked ? "▣ " : "▢ ")
                                    .style(checked ? Style.EMPTY.fg(accent) : Style.EMPTY.fg(muted)),
                            Span.raw(text).style(Style.EMPTY.fg(textColor))));
                } else {
                    final var selected = fq.selectedIndex() == idx;
                    out.add(Line.from(
                            Span.raw(selected ? "◉ " : "○ ")
                                    .style(selected ? Style.EMPTY.fg(accent) : Style.EMPTY.fg(muted)),
                            Span.raw(text)
                                    .style(selected ? Style.EMPTY.fg(accent).bold() : Style.EMPTY.fg(textColor))));
                }
            } else {
                final var rowStyle = fq.question.multiple()
                        ? Style.EMPTY.fg(textColor)
                        : fq.selectedIndex() == idx ? Style.EMPTY.fg(accent).bold() : Style.EMPTY.fg(textColor);
                out.add(Line.from(Span.raw(text).style(rowStyle)));
            }
        }
        return out;
    }

    /**
     * The trailing "Type your own answer..." pseudo-option (logical index {@code opts}).
     * Kept as a single row so the free-text entry never disappears behind the scroll window.
     */
    private Span[] buildOwnAnswerLine(
            final FeedbackQuestion fq, final int idx, final Color accent, final Color muted, final Color textColor) {
        if (fq.question.multiple()) {
            final var checked = fq.ownBox().isChecked();
            return new Span[] {
                Span.raw(checked ? "▣ " : "▢ ").style(checked ? Style.EMPTY.fg(accent) : Style.EMPTY.fg(muted)),
                Span.raw("Type your own answer...").style(Style.EMPTY.fg(checked ? accent : textColor))
            };
        }
        final var selected = fq.selectedIndex() == idx;
        return new Span[] {
            Span.raw("✎ ").style(Style.EMPTY.fg(selected ? accent : muted)),
            Span.raw("Type your own answer...")
                    .style(selected ? Style.EMPTY.fg(accent).bold() : Style.EMPTY.fg(textColor))
        };
    }

    /**
     * Builds the confirm-tab summary lines. Every question (bold accent) is followed by its
     * selection(s) (indented, plain text) - all word-wrapped with the display-width aware wrap so
     * the produced row count matches the terminal render and long options/answers (including CJK or
     * code snippets) stay fully visible instead of being clipped. The leading instruction line makes
     * the returned list self-contained (it counts toward the modal height budget).
     */
    private List<Line> feedbackSummaryLines(final FeedbackPrompt prompt, final int width) {
        final var w = Math.max(1, width);
        final var lines = new ArrayList<Line>();
        lines.add(Line.from(Span.styled(
                "Review your answers below, then press Enter to confirm, Backspace to go back and edit.",
                Style.EMPTY.fg(Color.hex(palette.text())))));
        for (final var fq : prompt.questions()) {
            for (final var wrapped : TuiInputSupport.wrapProseWidth(fq.question.prompt(), w)) {
                lines.add(Line.from(Span.styled(
                        wrapped, Style.EMPTY.fg(Color.hex(palette.accent())).bold())));
            }
            final var own = fq.ownAnswer().text().strip();
            final var opts = optionCount(fq);
            var any = false;
            if (fq.question.multiple()) {
                for (int i = 0; i < fq.boxes().length && i < opts; i++) {
                    if (fq.boxes()[i].isChecked()) {
                        for (final var l : TuiInputSupport.wrapProseWidth(
                                "  - " + fq.question.options().get(i), w)) {
                            lines.add(Line.from(Span.styled(l, Style.EMPTY.fg(Color.hex(palette.text())))));
                        }
                        any = true;
                    }
                }
                if (fq.ownBox().isChecked() && !own.isEmpty()) {
                    for (final var l : TuiInputSupport.wrapProseWidth("  - " + own, w)) {
                        lines.add(Line.from(Span.styled(l, Style.EMPTY.fg(Color.hex(palette.text())))));
                    }
                    any = true;
                }
            } else {
                final var selected = fq.selectedIndex() >= 0 && fq.selectedIndex() < opts
                        ? fq.question.options().get(fq.selectedIndex())
                        : null;
                // a no-options question answers from the free-text field, not a (null) option
                final var value = opts == 0 ? own : fq.selectedIndex() == opts && !own.isEmpty() ? own : selected;
                if (value != null) {
                    for (final var l : TuiInputSupport.wrapProseWidth("  - " + value, w)) {
                        lines.add(Line.from(Span.styled(l, Style.EMPTY.fg(Color.hex(palette.text())))));
                    }
                    any = true;
                }
            }
            if (!any) {
                lines.add(Line.from(Span.styled("  - (no selection)", Style.EMPTY.fg(Color.hex(palette.textMuted())))));
            }
            lines.add(Line.empty());
        }
        return lines;
    }

    /**
     * Left gutter of the agent mode: heavy bold accent bar in {@code build},
     * thin warning bar in {@code plan}, red when permissions are {@code all}.
     * The bar symbol itself switches (thin vs heavy) so the mode change is
     * unmistakable on every terminal.
     */
    private Block modeGutter() {
        final var build = active().session.runtime().permissions().mode() == AgentMode.BUILD;
        return Block.builder()
                .borders(Borders.LEFT_ONLY)
                .borderStyle(build ? Style.EMPTY.fg(modeColor()).bold() : Style.EMPTY.fg(modeColor()))
                .customBorderSet(
                        BorderSet.builder().leftVertical(build ? "┃" : "│").build())
                .build();
    }

    private Rect accentGutter(final Rect rect, final Frame frame) {
        final var parts = Layout.horizontal()
                .constraints(Constraint.length(1), Constraint.fill())
                .split(rect);
        frame.renderWidget(modeGutter(), parts.get(0));
        return parts.get(1);
    }

    private Block block(final String title) {
        return Block.builder()
                .title(title)
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(Color.hex(palette.border()))
                .build();
    }

    /**
     * Conversation panel: rounded frame "open" on the left - the pane keeps its corners
     * and its top/right/bottom walls, but draws no left wall. The per-region role bar
     * replaces it, so the transcript reads as one changing left bar (mode gutter, then
     * role bar) instead of a pane border next to a separator.
     */
    private Block conversationBlock() {
        return Block.builder()
                .title(" conversation ")
                .borders(EnumSet.of(Borders.TOP, Borders.RIGHT, Borders.BOTTOM, Borders.LEFT))
                .customBorderSet(BorderSet.builder()
                        .horizontal("─")
                        .rightVertical("│")
                        .topLeft("╭")
                        .topRight("╮")
                        .bottomLeft("╰")
                        .bottomRight("╯")
                        .build())
                .borderColor(Color.hex(palette.border()))
                .build();
    }

    private Block activeBlock(final String title) {
        return Block.builder()
                .title(title)
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(Color.hex(palette.borderActive()))
                .build();
    }

    private boolean planMode() {
        return active().session.runtime().permissions().mode() == AgentMode.PLAN;
    }

    private String modeName() {
        return active().session.runtime().permissions().mode().name().toLowerCase(Locale.ROOT);
    }

    /** Mode word shown in the status strip. */
    private String modeWord() {
        return modeName();
    }

    /** Whether the given tab's session runs in build mode (its own permissions, not the active tab's). */
    private boolean tabBuildMode(final UiState state) {
        final var runtime = state.session == null ? null : state.session.runtime();
        return runtime != null
                && runtime.permissions() != null
                && runtime.permissions().mode() == AgentMode.BUILD;
    }

    /** The mode badge color of a specific tab: plan uses the warning tone, build the accent one. */
    private Color tabModeColor(final UiState state) {
        return Color.hex(tabBuildMode(state) ? palette.accent() : palette.warning());
    }

    private Color modeColor() {
        return Color.hex(planMode() ? palette.warning() : palette.accent());
    }

    private static String ellipsize(final String text, final int max) {
        return TuiTextRender.ellipsize(text, max);
    }

    private static String ellipsizeLeading(final String text, final int max) {
        return TuiTextRender.ellipsizeLeading(text, max);
    }

    private static String formatTokens(final long value) {
        if (value < 1_000) {
            return String.valueOf(value);
        }
        if (value < 1_000_000) {
            final var k = value / 1000.0;
            return (k > 9 ? String.format("%.0fk", k) : String.format("%.1fk", k)).replace(",", ".");
        }
        final var m = value / 1_000_000.0;
        return (m > 9 ? String.format("%.0fM", m) : String.format("%.1fM", m)).replace(",", ".");
    }

    /**
     * The compaction threshold in %. A non-positive threshold means buffer mode (compaction fires
     * when the prompt approaches the model window minus a token buffer), which cannot be expressed
     * as a percentage: the percent warning banner is then disabled (returns 100 so it never fires)
     * and the token-level trigger stays visible in the agent context line and compress tool status.
     * Falls back to 80 when configuration is absent (rendering tests).
     */
    private int compactionThreshold() {
        if (configuration == null || configuration.compaction() == null) {
            return 80;
        }
        final var percent = configuration.compaction().thresholdPercent();
        return percent > 0 ? percent : 100;
    }

    /**
     * Height of the status strip: 4 rows while a boxed error is shown (1 status line + the 3-row
     * error banner below it, so the model/metrics stay visible), 2 rows when the context compaction
     * warning banner is active (it occupies the strip's last row), 1 row otherwise.
     */
    private int statusStripRows(final UiState state) {
        if (state.errorMessage != null) {
            return 4;
        }
        return state.model.contextPercent() >= compactionThreshold() ? 2 : 1;
    }

    /** The pruning threshold in %, falling back to a safe default when configuration is absent (rendering tests). */
    private int pruningThreshold() {
        return configuration == null || configuration.pruning() == null
                ? 65
                : configuration.pruning().thresholdPercent();
    }

    private String workingDirectory() {
        final var dir = Path.of(WORKING_DIRECTORY).toAbsolutePath();
        final var home = Path.of(System.getProperty("user.home")).toAbsolutePath();
        return dir.startsWith(home) ? "~/" + home.relativize(dir) : dir.toString();
    }

    /**
     * Branch shown in the sidebar. When the shared scheduler runs a {@link GitBranchPoller} the value comes
     * from its last tick (no disk access on the render thread, the poller repaints on change); otherwise the
     * historical on-demand read with a short TTL is kept for render-only tests.
     */
    private String gitBranch() {
        final var poller = gitBranchPoller;
        if (poller != null) {
            return poller.branch();
        }
        final var now = clock.millis();
        final var cachedAt = cachedGitBranchAt;
        if (cachedGitBranch != null && now - cachedAt < GIT_BRANCH_CACHE_TTL_MILLIS) {
            return cachedGitBranch;
        }
        try {
            final var head = GitBranchPoller.resolveHead(WORKING_DIRECTORY);
            final var branch = head != null && Files.isRegularFile(head)
                    ? GitBranchPoller.branchOf(Files.readString(head).strip())
                    : null;
            cachedGitBranch = branch;
            cachedGitBranchAt = now;
            return branch;
        } catch (final IOException e) {
            cachedGitBranch = null;
            cachedGitBranchAt = now;
            return null;
        }
    }

    /** Called by the branch poller (off the render thread) once the branch actually changed. */
    private void onBranchChanged(final String branch) {
        final var state = active();
        if (state != null) {
            runLater(state, () -> LOG.fine(() -> "branch changed to " + branch));
        }
    }

    static final class Approval {
        enum Action {
            DENY("Deny  [Esc]"),
            ALLOW("Allow  [Enter]"),
            ALWAYS("Always (session)");

            private final String label;

            Action(final String label) {
                this.label = label;
            }

            private String label() {
                return label;
            }
        }

        private final String title;
        private final String detail;
        private final CountDownLatch latch;
        private final AtomicReference<ApprovalGate.Result> outcome;
        /** The /btw overlay that owns this approval, or -1 for a main-turn approval. */
        private final long btwOrdinal;

        private int action;
        private int detailScroll;

        private Approval(
                final String title,
                final String detail,
                final CountDownLatch latch,
                final AtomicReference<ApprovalGate.Result> outcome) {
            this(title, detail, latch, outcome, -1);
        }

        private Approval(
                final String title,
                final String detail,
                final CountDownLatch latch,
                final AtomicReference<ApprovalGate.Result> outcome,
                final long btwOrdinal) {
            this.title = title;
            this.detail = detail;
            this.latch = latch;
            this.outcome = outcome;
            this.btwOrdinal = btwOrdinal;
            this.action = 0; // default-deny: focus Deny first
        }

        private String title() {
            return title;
        }

        private String detail() {
            return detail;
        }

        private CountDownLatch latch() {
            return latch;
        }

        private int action() {
            return action;
        }

        private void action(final int action) {
            this.action = Math.floorMod(action, Action.values().length);
        }

        private int detailScroll() {
            return detailScroll;
        }

        private void detailScroll(final int detailScroll) {
            this.detailScroll = Math.max(0, detailScroll);
        }

        private long btwOrdinal() {
            return btwOrdinal;
        }

        /** Resolves the approval to a plain Deny without user interaction (e.g. when /btw is closed while pending). */
        private void deny() {
            outcome.set(ApprovalGate.Result.DENY);
            latch.countDown();
        }

        /** Resolves and performs the currently focused action, releasing the gate latch. Returns true when the call is allowed. */
        private boolean perform() {
            final var result =
                    switch (Action.values()[action]) {
                        case ALLOW -> ApprovalGate.Result.ALLOW_ONCE;
                        case ALWAYS -> ApprovalGate.Result.ALLOW_SESSION;
                        default -> ApprovalGate.Result.DENY;
                    };
            outcome.set(result);
            latch.countDown();
            return result != ApprovalGate.Result.DENY;
        }
    }

    static final class PickerPrompt {
        private final String title;
        private final List<String> items;
        private final int pageSize;
        private final StringBuilder filter = new StringBuilder();
        private final Consumer<String> onSelect;
        private final Runnable onCancel;
        private final List<Rect> itemRects = new ArrayList<>();
        private final List<String> renderedValues = new ArrayList<>();
        private int selected;

        private PickerPrompt(
                final String title,
                final List<String> items,
                final int pageSize,
                final Consumer<String> onSelect,
                final Runnable onCancel) {
            this.title = title;
            this.items = new ArrayList<>(items == null ? List.of() : items);
            this.pageSize = pageSize > 0 ? pageSize : 0;
            this.onSelect = onSelect;
            this.onCancel = onCancel;
        }

        private Consumer<String> onSelect() {
            return onSelect;
        }

        private int selected() {
            return selected;
        }

        private int pageSize() {
            return pageSize;
        }

        private void prepareList(final Rect rect) {
            itemRects.clear();
            renderedValues.clear();
        }

        private void recordItem(final Rect rect, final String value) {
            itemRects.add(rect);
            renderedValues.add(value);
        }

        /** @return the rendered value at the mouse position, or null when no item was hit. */
        private String itemAt(final int x, final int y) {
            for (int i = 0; i < itemRects.size(); i++) {
                if (itemRects.get(i).contains(x, y)) {
                    return renderedValues.get(i);
                }
            }
            return null;
        }

        private List<String> visible() {
            final var f = filter.toString().toLowerCase(Locale.ROOT);
            if (f.isEmpty()) {
                return items;
            }
            final var out = new ArrayList<String>();
            for (final var it : items) {
                if (it != null && it.toLowerCase(Locale.ROOT).contains(f)) {
                    out.add(it);
                }
            }
            return out;
        }

        private void filterAppend(final char c) {
            filter.append(c);
            selected = 0;
        }

        private void filterBackspace() {
            if (filter.length() > 0) {
                filter.deleteCharAt(filter.length() - 1);
                selected = 0;
            }
        }

        private void filterClear() {
            filter.setLength(0);
            selected = 0;
        }

        /** Moves the selection one row; the renderer keeps the selected row inside the visible window. */
        private void move(final int delta) {
            final var v = visible();
            if (v.isEmpty()) {
                return;
            }
            selected = Math.clamp(selected + delta, 0, v.size() - 1);
        }

        /** Moves the selection by a whole page ({@link #pageSize} rows), keeping global page boundaries (no-op when paging is off). */
        private void movePage(final int pageDelta) {
            if (pageSize <= 0) {
                return;
            }
            final var v = visible();
            if (v.isEmpty()) {
                return;
            }
            selected = Math.clamp(selected + (long) pageDelta * pageSize, 0, v.size() - 1);
        }

        /** @return the 0-based page that holds the current selection (0 when no paging). */
        private int page() {
            return pageSize <= 0 ? 0 : selected / pageSize;
        }

        /** @return the number of pages for the current (filtered) list (0 when empty). */
        private int pageCount() {
            final var size = visible().size();
            if (size == 0) {
                return 0;
            }
            return pageSize <= 0 ? 1 : (size + pageSize - 1) / pageSize;
        }

        private void stroke(final KeyEvent key, final ScrollService scroll) {
            if (key.isKey(KeyCode.UP)) {
                move(-1);
            } else if (key.isKey(KeyCode.DOWN)) {
                move(1);
            } else if (key.isKey(KeyCode.HOME)) {
                selected = 0;
            } else if (key.isKey(KeyCode.END)) {
                final var v = visible();
                selected = v.isEmpty() ? 0 : v.size() - 1;
            } else if (key.isKey(KeyCode.PAGE_UP) || key.isKey(KeyCode.PAGE_DOWN)) {
                if (pageSize > 0) {
                    movePage(key.isKey(KeyCode.PAGE_UP) ? -1 : 1);
                } else {
                    move(scroll.keyDelta(key));
                }
            } else if (key.hasCtrl() && key.isCharIgnoreCase('u')) {
                filterClear();
            } else if (key.isKey(KeyCode.BACKSPACE)) {
                filterBackspace();
            } else {
                final var cp = key.codePoint();
                if (cp >= 32 && cp != 127) {
                    filterAppend((char) cp);
                }
            }
        }

        private boolean confirm(final KeyEvent key) {
            if (key.isConfirm()) {
                final var v = visible();
                if (!v.isEmpty() && selected < v.size() && onSelect != null) {
                    onSelect.accept(v.get(selected));
                }
                return true;
            }
            if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
                if (onCancel != null) {
                    onCancel.run();
                }
                return true;
            }
            return false;
        }
    }

    static final class PendingEditPlan {
        private final Path path;
        private final String original;
        private volatile Rect confirmRect;
        private volatile Rect cancelRect;

        private PendingEditPlan(final Path path, final String original, final Rect confirmRect, final Rect cancelRect) {
            this.path = path;
            this.original = original;
            this.confirmRect = confirmRect;
            this.cancelRect = cancelRect;
        }

        private Path path() {
            return path;
        }

        private String original() {
            return original;
        }

        private Rect confirmRect() {
            return confirmRect;
        }

        private void confirmRect(final Rect confirmRect) {
            this.confirmRect = confirmRect;
        }

        private Rect cancelRect() {
            return cancelRect;
        }

        private void cancelRect(final Rect cancelRect) {
            this.cancelRect = cancelRect;
        }
    }

    static final class FeedbackPrompt {
        long btwOrdinal;
        final List<FeedbackQuestion> questions;
        final CountDownLatch latch;
        final Map<String, Object> answers;
        final AtomicBoolean answered;
        int tab;
        int optionsFocus;
        boolean focusInput;
        Rect tabsRect;
        /** Scroll offset of the (column-wrapped) question/prompt or confirm summary, reset on every tab change. */
        int questionScroll;
        /** Overflow (rows beyond the visible question area) computed at the last render; used to route the wheel. */
        int questionMaxScroll;

        FeedbackPrompt(
                final List<Question> raw,
                final CountDownLatch latch,
                final Map<String, Object> answers,
                final AtomicBoolean answered) {
            this.questions = new ArrayList<>(raw.size());
            for (final var q : raw) {
                this.questions.add(new FeedbackQuestion(q));
            }
            this.latch = latch;
            this.answers = answers;
            this.answered = answered;
        }

        List<FeedbackQuestion> questions() {
            return questions;
        }

        CountDownLatch latch() {
            return latch;
        }

        Map<String, Object> answers() {
            return answers;
        }

        AtomicBoolean answered() {
            return answered;
        }

        int tab() {
            return tab;
        }

        void tab(final int tab) {
            this.tab = Math.max(0, tab);
            // a new tab shows a different question/summary, so its scroll must restart from the top
            this.questionScroll = 0;
        }

        int optionsFocus() {
            return optionsFocus;
        }

        void optionsFocus(final int optionsFocus) {
            this.optionsFocus = Math.max(0, optionsFocus);
        }

        boolean focusInput() {
            return focusInput;
        }

        void focusInput(final boolean focusInput) {
            this.focusInput = focusInput;
        }

        Rect tabsRect() {
            return tabsRect;
        }

        void tabsRect(final Rect tabsRect) {
            this.tabsRect = tabsRect;
        }

        int questionScroll() {
            return questionScroll;
        }

        void questionScroll(final int questionScroll) {
            this.questionScroll = Math.max(0, questionScroll);
        }

        int questionMaxScroll() {
            return questionMaxScroll;
        }

        void questionMaxScroll(final int questionMaxScroll) {
            this.questionMaxScroll = Math.max(0, questionMaxScroll);
        }
    }

    static final class FeedbackQuestion {
        final Question question;
        final int[] selectedIndex = {0};
        final CheckboxState[] boxes;
        final CheckboxState ownBox = new CheckboxState();
        final TextAreaState ownAnswer = new TextAreaState();
        final List<FeedbackOptionRow> optionRects = new ArrayList<>();

        FeedbackQuestion(final Question question) {
            this.question = question;
            if (question.multiple() && question.options() != null) {
                this.boxes = new CheckboxState[question.options().size()];
                for (int i = 0; i < boxes.length; i++) {
                    boxes[i] = new CheckboxState();
                }
            } else {
                this.boxes = new CheckboxState[0];
            }
        }

        Question question() {
            return question;
        }

        CheckboxState[] boxes() {
            return boxes;
        }

        CheckboxState ownBox() {
            return ownBox;
        }

        TextAreaState ownAnswer() {
            return ownAnswer;
        }

        List<FeedbackOptionRow> optionRects() {
            return optionRects;
        }

        int selectedIndex() {
            return selectedIndex[0];
        }

        void selectedIndex(final int index) {
            selectedIndex[0] = Math.max(0, index);
        }
    }

    /** One visible option row: the screen rect plus the logical option index it belongs to. */
    record FeedbackOptionRow(Rect rect, int option) {}

    private static TextAreaState feedbackInput(final FeedbackPrompt prompt) {
        final var idx = prompt.tab() >= prompt.questions().size() ? 0 : prompt.tab();
        return prompt.questions().get(idx).ownAnswer();
    }

    record Toast(String message, ToastLevel level) {}
}
