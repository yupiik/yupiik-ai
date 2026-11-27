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

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.cli.prompt.LinedPrompt;
import io.yupiik.ia.agent.core.cli.prompt.Prompt;
import io.yupiik.ia.agent.core.cli.prompt.PromptInterruptException;
import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.FileSessionStore;
import io.yupiik.ia.agent.core.shell.AnsiShellFrontend;
import io.yupiik.ia.agent.core.shell.AnsiShellIO;
import io.yupiik.ia.agent.core.shell.ConversationCoordinator;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import io.yupiik.ia.agent.core.shell.InterruptCoordinator;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.slash.ShellInputRouting;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.style.ModelStyleService;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.wiring.HackStdIo;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Interactive plain-terminal REPL for yca. Requires only a terminal
 * ({@code stdin}/{@code stdout} lines and optional ANSI color); it is a
 * drop-in alternative to the TamboUI {@code tui} for environments without a
 * real terminal widget layer.
 */
@DefaultScoped
@Command(
        name = "cli",
        description =
                "Interactive plain-terminal yca session (REPL) with optional ANSI colors. All modes accept --sandbox true|false (yca.sandbox.enabled); sandbox policy is independent of tool permissions.")
public class CliCommand implements Runnable {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final CliConf conf;
    private final SessionLifecycle sessionLifecycle;
    private final FileSessionStore sessionStore;
    private final AgentProvider agentProvider;
    private final SessionRuntimeFactory sessionRuntimeFactory;
    private final ChatClient chatClient;
    private final AgentSessionService agentSessionService;
    private final SlashRegistry slashRegistry;
    private final ShellRegistry shellRegistry;
    private final ConversationCoordinator coordinator;
    private final ModelStyleService modelStyleService;
    private final Clock clock;
    private final InterruptCoordinator interruptor;
    private final Prompt prompt;
    private final CodingAgentConfiguration configuration;
    private final ShellSession session = new ShellSession();

    private static final long CLEANUP_AWAIT_NANOS = 1_000_000_000L;

    private static final Logger LOGGER = Logger.getLogger(CliCommand.class.getName());

    private final AtomicBoolean cancelled = new AtomicBoolean();
    /**
     * Whether an inline interaction read (approval, confirm, picker, feedback) owns the
     * terminal: a Ctrl-C there must deny/cancel that interaction, never open the interrupt
     * menu. Guarded by the AnsiShellIO interaction hooks.
     */
    private final AtomicBoolean interactionActive = new AtomicBoolean();
    /**
     * Set by the SIGINT handler when a turn runs and no interactive read is live; the REPL
     * thread in {@link #awaitTurn} picks it up and renders the interrupt menu. Needed because
     * the jline reader is not thread-safe: the menu cannot be painted from the signal thread.
     */
    private final AtomicBoolean menuPending = new AtomicBoolean();

    private final TurnRunner turnRunner;
    private final MarkdownColorizer colorizer;
    private final Symbols symbols;
    private final AnsiShellFrontend frontend;

    private CliUsagePrinter usagePrinter;
    private final AnsiShellIO io;
    /**
     * Interaction gates shared by the AnsiShellIO interactions and the interrupt menu
     * read: they claim the terminal ({@link #interactionActive}) and suspend the turn
     * output so it can never stomp an in-flight question line.
     */
    private final Runnable interactionStart;

    private final Runnable interactionEnd;
    private final SpeakerLabels speakerLabels;
    private final boolean queueing;
    private final Deque<String> pendingPrompts = new ConcurrentLinkedDeque<>();
    private final AtomicInteger activeTurns = new AtomicInteger();
    private final ReentrantLock turnLock = new ReentrantLock();
    private volatile CompletableFuture<Boolean> inFlight;
    private volatile boolean quitRequested;
    private String sessionId;
    private String activeAgent = "";
    private Path contextPath;
    private final CliPromptTemplate promptTemplate;
    private final AtomicBoolean cleaned = new AtomicBoolean();
    /**
     * Line-editing sink for output landing while a prompt is live (jline
     * {@code printAbove}); {@code null} on the plain-stdin backend. Turn-end
     * writes (todos, usage) race the next {@code readLine} and late async
     * events (subagents, memory extraction) can land on it: routing them
     * through the sink keeps the prompt visible and redrawn.
     */
    private final PromptOutput externalOutput;

    private final HackStdIo stdio;

    private final McpManager mcpManager;

    public CliCommand(
            final CliConf conf,
            final SessionLifecycle sessionLifecycle,
            final FileSessionStore sessionStore,
            final SessionRuntimeFactory sessionRuntimeFactory,
            final AgentProvider agentProvider,
            final ChatClient chatClient,
            final AgentSessionService agentSessionService,
            final TurnRunner turnRunner,
            final JsonMapper mapper,
            final SlashRegistry slashRegistry,
            final ShellRegistry shellRegistry,
            final ConversationCoordinator coordinator,
            final ModelStyleService modelStyleService,
            final CliPromptTemplate promptTemplate,
            final Prompt prompt,
            final Clock clock,
            final CodingAgentConfiguration configuration,
            final SpeakerLabels speakerLabels,
            final HackStdIo stdio,
            final McpManager mcpManager,
            final SecretRedactor secretRedactor) {
        this.mcpManager = mcpManager;
        this.conf = conf;
        this.sessionLifecycle = sessionLifecycle;
        this.sessionStore = sessionStore;
        this.agentProvider = agentProvider;
        this.sessionRuntimeFactory = sessionRuntimeFactory;
        this.chatClient = chatClient;
        this.agentSessionService = agentSessionService;
        this.turnRunner = turnRunner;
        this.slashRegistry = slashRegistry;
        this.shellRegistry = shellRegistry;
        this.coordinator = coordinator;
        this.modelStyleService = modelStyleService;
        this.promptTemplate = promptTemplate;
        this.prompt = prompt;
        this.clock = clock;
        this.interruptor = new InterruptCoordinator(clock);
        this.configuration = configuration;
        this.speakerLabels = speakerLabels;
        this.stdio = stdio;
        session.metrics(configuration.metrics());
        session.diffViewMode(
                configuration.view() == null || configuration.view().diffViewMode() == null
                        ? DiffViewMode.AUTO
                        : configuration.view().diffViewMode());
        final var parsedToolOutput = ToolOutputVisibility.parse(conf.showToolOutput());
        final var showToolOutput = parsedToolOutput == null ? ToolOutputVisibility.COLLAPSED : parsedToolOutput;
        session.showThinking(conf.showThinking()).showToolOutput(showToolOutput);
        this.colorizer = new MarkdownColorizer(colorEnabled(conf.color()));
        this.symbols = new Symbols(conf.ascii());
        this.externalOutput = prompt.externalOutput() == null ? PromptOutput.raw() : prompt.externalOutput();
        this.frontend = new AnsiShellFrontend(
                colorizer,
                secretRedactor,
                symbols,
                new ToolPresenter(mapper),
                CliVerbosity.parse(conf.presentation()),
                System.console() != null,
                conf.showThinking(),
                showToolOutput,
                externalOutput);
        this.frontend.autoOpenBrowser(conf.autoOpenBrowser());
        this.frontend.diffViewMode(session.diffViewMode());
        this.interactionStart = () -> {
            interactionActive.set(true);
            frontend.suspendOutput();
        };
        this.interactionEnd = () -> {
            interactionActive.set(false);
            frontend.resumeOutput();
        };
        this.io = new AnsiShellIO(
                prompt, symbols, this::onInterrupt, this.interactionStart, this.interactionEnd, frontend.renderLock());
        this.queueing = prompt instanceof LinedPrompt lined && lined.queuing();
    }

    @RootConfiguration("cli")
    public record CliConf(
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
                    defaultValue = "\"\"",
                    documentation =
                            "Name of a custom agent (from yca.agents) active by default. Its prompt is injected on top of the base system prompt.")
            String agent,

            @Property(
                    defaultValue = "true",
                    documentation =
                            "Enable ANSI colors. Color is only emitted when stdout is a terminal and neither NO_COLOR nor TERM=dumb is set.")
            boolean color,

            @Property(
                    defaultValue = "false",
                    documentation =
                            "Force ASCII-only symbols (no Unicode glyphs) in the output, useful for scripts or non-Unicode terminals.")
            boolean ascii,

            @Property(
                    defaultValue = "\"compact\"",
                    documentation =
                            "Tool output verbosity: compact (summaries and live progress), verbose (full dimmed stream) or raw (verbatim, legacy behaviour).")
            String presentation,

            @Property(defaultValue = "false", documentation = "Show the reasoning/thinking stream on stderr.")
            boolean showThinking,

            @Property(
                    defaultValue = "\"collapsed\"",
                    documentation =
                            "Tool output verbosity on stderr: shown (full invocation and output), collapsed (a single "
                                    + "summary line per tool) or hidden (nothing). Accepts the legacy booleans true/false "
                                    + "(true = shown, false = collapsed/off).")
            String showToolOutput,

            @Property(
                    defaultValue = "false",
                    documentation =
                            "Print the context usage (estimated tokens and percent of the context window) on stderr after each turn.")
            boolean showUsage,

            @Property(
                    defaultValue = "true",
                    documentation = "Automatically open the system browser when a login/authorization URL is generated "
                            + "(e.g. /codex login). Disable to only surface the link in the transcript, where "
                            + "it stays clickable (OSC-8 autolink) and fully copyable even when it wraps.")
            boolean autoOpenBrowser,

            @Property(
                    defaultValue = "java.util.List.of()",
                    documentation =
                            "Prompts submitted at startup, in order, through the same path as a typed line: use it to "
                                    + "script a soft resume, e.g. continue re-runs the agent on the persisted history "
                                    + "without sending any message. Slash-prefixed entries use the same command/prompt "
                                    + "routing as interactive input. Values are comma separated; prefix with "
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
         * @param agent default custom agent name
         * @param color enable ANSI colors
         * @param ascii force ASCII-only symbols
         * @param presentation tool output verbosity
         * @param showThinking show the reasoning stream
         * @param showToolOutput tool output verbosity on stderr
         * @param showUsage print the context usage after each turn
         */
        public CliConf(
                final String session,
                final String context,
                final String agent,
                final boolean color,
                final boolean ascii,
                final String presentation,
                final boolean showThinking,
                final String showToolOutput,
                final boolean showUsage) {
            this(
                    session,
                    context,
                    agent,
                    color,
                    ascii,
                    presentation,
                    showThinking,
                    showToolOutput,
                    showUsage,
                    true,
                    List.of(),
                    null);
        }
    }

    @Override
    public void run() {
        mcpManager.start();
        installInterruptHandler();
        // from here on this command owns the terminal: every writer (ours, the JUL root
        // handlers, a dependency) is funnelled through the prompt aware sink, so a log line
        // or a stray print landing between two turns can no longer overwrite the row jline
        // already painted and leave the prompt invisible until the next keystroke
        stdio.streamTarget(externalOutput);
        sessionId = sessionLifecycle.resolveSessionId(conf.session(), conf.context());
        contextPath = sessionStore.path(sessionId);
        try {
            sessionLifecycle.prepareContextFile(contextPath, conf.context());
        } catch (final IOException e) {
            println(MarkdownColorizer.error("failed to prepare context file " + contextPath + ": " + e.getMessage()));
        }
        println(MarkdownColorizer.info("session " + sessionId + " - context file: " + contextPath));
        // the banner reads the session runtime (permission mode), wire it before printing
        final var workdir = sessionRuntimeFactory.resolveWorkdir(null, sessionId);
        final var runtime = sessionRuntimeFactory.create(sessionId, workdir, conf.sandbox());
        session.runtime(runtime);
        frontend.bindCompression(runtime.compression());
        printBanner();
        if (conf.agent() != null && !conf.agent().isBlank()) {
            if (agentProvider.byName(conf.agent()) == null) {
                throw new IllegalArgumentException(
                        "unknown agent: " + conf.agent() + " (configure one under yca.agents)");
            }
            activeAgent = conf.agent();
        }
        sessionLifecycle.installGates(io.approvalGate(), io.feedbackGate(), runtime);
        session.sessionId(sessionId).activeAgent(activeAgent);
        shellRegistry.register(env());
        final var shutdownHook = new Thread(this::shutdownCleanup, "yca-cli-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            submitInitialPrompts();
            repLoop();
        } finally {
            cleanup();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (final IllegalStateException ignored) {
                // the JVM is already shutting down
            }
        }
    }

    /**
     * Best-effort cleanup, safe to run several times: both the normal exit path
     * (finally) and the JVM shutdown hook share it.
     */
    private void cleanup() {
        requestQuit();
        turnLock.lock();
        try {
            cleanupSession();
        } finally {
            turnLock.unlock();
        }
    }

    private void cleanupSession() {
        if (!cleaned.compareAndSet(false, true)) {
            return;
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("[yca-shutdown] cleanup: started");
        }
        final var start = System.nanoTime();
        // Emit the session line while stdout/stderr are still owned and alive: on the JVM
        // shutdown-hook path (SIGTERM / Ctrl-C without an installed signal handler) the
        // terminal can start closing concurrently, and a late C-f line on a torn-down stream
        // is silently lost. Written to stderr (not stdout) because stderr conventionally
        // survives longer when stdout is redirected, piped or closed, and it is flushed by
        // the sink. Doing this first also guarantees no teardown step can race it out.
        writeLineErr(MarkdownColorizer.info(sessionContextLine()));
        // give the terminal back before the remaining closing lines so they go straight out
        if (stdio.streamTarget() == externalOutput) {
            stdio.streamTarget(null);
        }
        frontend.idle();
        shellRegistry.unregister(env());
        disposeRuntime(sessionId, session.runtime());
        System.out.flush();
        System.err.flush();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(() -> "[yca-shutdown] cleanup: done in " + (System.nanoTime() - start) / 1_000_000L + " ms");
        }
    }

    /**
     * Invoked on JVM shutdown (SIGTERM, or Ctrl-C when the signal handler is not
     * installed): set the flags so an in-flight turn unwinds, then clean up.
     */
    private void shutdownCleanup() {
        cancelled.set(true);
        if (session.busy()) {
            // give the in-flight turn a short window to unwind and flush its
            // session entries before we tear the gates down
            final var deadline = System.nanoTime() + CLEANUP_AWAIT_NANOS;
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("[yca-shutdown] in-flight turn busy: waiting up to " + CLEANUP_AWAIT_NANOS / 1_000_000L
                        + " ms for it to unwind");
            }
            while (session.busy() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(25);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (LOGGER.isLoggable(Level.FINE)) {
                final var stayedBusy = session.busy();
                LOGGER.fine("[yca-shutdown] turn unwind "
                        + (stayedBusy ? "still busy after the grace window; tearing gates down anyway" : "completed")
                        + " (" + (System.nanoTime() - deadline + CLEANUP_AWAIT_NANOS) / 1_000_000L + " ms)");
            }
        }
        quitRequested = true;
        cleanup();
    }

    private boolean colorEnabled(final boolean configured) {
        return configured && MarkdownColorizer.autoEnabled();
    }

    /**
     * Context-rich prompt line: reveals the agent mode (critical for a code
     * writing agent - plan vs build), the active model and the effective
     * permission posture (ask / auto / yolo / all). The rendering delegates to
     * the configurable handlebars template ({@code yca.prompt.template}) which
     * colors the mode and the permission badge when ANSI color is enabled and
     * falls back to plain text otherwise.
     */
    private String promptLine() {
        final var mode = session.runtime().permissions().mode().name().toLowerCase(Locale.ROOT);
        final var info = agentSessionService.permissionsInfo(session.runtime());
        final var usage = frontend.stats().lastUsage();
        final var model = chatClient.model(session.chatSettings());
        final var modelData = new LinkedHashMap<String, Object>();
        modelData.put("name", model);
        modelData.put("contextPercentage", usage == null ? 0 : (int) Math.round(usage.contextPercent()));
        modelData.put("contextTokens", usage == null ? 0 : usage.estimatedContextTokens());
        modelData.put("contextWindow", session.runtime().usage().usableWindow());

        final var data = new LinkedHashMap<String, Object>();
        data.put("mode", mode);
        data.put("modelName", model);
        data.put("permission", permissionLabel(info.permissionMode()));
        data.put("agent", activeAgent);
        data.put("model", modelData);
        data.put("prune", configuration.pruning().thresholdPercent());
        data.put("compact", configuration.compaction().thresholdPercent());
        data.put("color", colorizer.isEnabled());
        data.put("colors", promptColors(model));
        return promptTemplate.render(data);
    }

    private Map<String, String> promptColors(final String model) {
        if (!colorizer.isEnabled()) {
            return Map.of();
        }
        final var colors = new LinkedHashMap<>(configuration.prompt().colors());
        colors.put(
                "model",
                modelStyleService
                        .resolve(chatClient.currentProvider(session.chatSettings()), model)
                        .ansiPrefix());
        return colors;
    }

    private String styledModel(final String model) {
        final var style = modelStyleService.resolve(chatClient.currentProvider(session.chatSettings()), model);
        return style.ansiPrefix() + model + "\u001b[0m";
    }

    private String permissionLabel(final PermissionMode mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case YOLO -> "yolo";
            case FULL_YOLO -> "full_yolo";
            case AUTO_EDIT -> "auto";
            default -> "ask";
        };
    }

    private void printBanner() {
        final var sb = new StringBuilder();
        sb.append(MarkdownColorizer.bold(symbols.toolMarker() + "yca"))
                .append(" · ")
                .append(session.runtime().permissions().mode().name().toLowerCase(Locale.ROOT))
                .append(" mode · session ")
                .append(sessionId);
        final var model = chatClient.model(session.chatSettings());
        if (model != null && !model.isBlank()) {
            sb.append(" · ").append(colorizer.isEnabled() ? styledModel(model) : model);
        }
        sb.append(
                " · type /help for commands · 'go' resumes the last turn · ctrl-c opens the interrupt menu (cancel turn / exit)");
        println(MarkdownColorizer.status(sb.toString()));
    }

    private void repLoop() {
        try {
            while (true) {
                if (queueing && turnActive()) {
                    if (awaitAndRouteWhileBusy()) {
                        if (quitRequested) {
                            break;
                        }
                        continue;
                    }
                    break;
                }
                println(dividingRule());
                final Optional<String> line;
                try {
                    line = awaitLine(promptLine());
                } catch (final PromptInterruptException e) {
                    onInterrupt();
                    if (quitRequested) {
                        break;
                    }
                    continue;
                }
                if (line.isEmpty() || quitRequested) {
                    break;
                }
                final var text = line.get();
                if (text.isBlank()) {
                    continue;
                }
                if (!runLine(text)) {
                    break;
                }
            }
        } catch (final IOException e) {
            println(MarkdownColorizer.error("cli read failed: " + e.getMessage()));
        }
    }

    /**
     * Resolves one non-blank user line before changing the transcript or prompt queue.
     *
     * @return {@code false} when the shell should exit.
     */
    private boolean runLine(final String text) {
        return routeLine(text, false) != InputOutcome.QUIT;
    }

    private enum InputOutcome {
        ACCEPTED,
        RETRY,
        QUIT
    }

    private InputOutcome routeLine(final String text, final boolean busy) {
        if (text.strip().equals("exit")) {
            requestQuit();
            println(MarkdownColorizer.status("bye"));
            return InputOutcome.QUIT;
        }
        final ShellInputRouting.Decision decision;
        try {
            decision = ShellInputRouting.resolve(text, slashRegistry, io)
                    .toCompletableFuture()
                    .join();
        } catch (final CompletionException e) {
            final var cause = e.getCause() == null ? e : e.getCause();
            if (!(cause instanceof PromptInterruptException)) {
                frontend.error(cause.getMessage());
            }
            return quitRequested ? InputOutcome.QUIT : InputOutcome.RETRY;
        }
        switch (decision) {
            case COMMAND -> slash(text.strip());
            case PROMPT -> {
                // Already resolved: neither queued prompts nor confirmed slash text are classified again.
                if (busy) {
                    pendingPrompts.addLast(text);
                    println(MarkdownColorizer.status("queued prompt (" + pendingPrompts.size()
                            + " pending) - integrated into the running turn at the next checkpoint"));
                    drainQueueIfIdle();
                } else {
                    runInput(text);
                    println();
                }
            }
            case CORRECT -> {
                // Prompt/ShellIO expose no editable-draft setter. Recall remains owned by the
                // line-editing backend; do not add another history or record picker answers here.
                println(MarkdownColorizer.status("Use /help to find a command, then correct and re-enter the input. "
                        + "The draft cannot be restored here; re-enter the exact original input to try again:"));
                println(text);
                return InputOutcome.RETRY;
            }
            case CANCEL -> {
                // No transcript, queue or LLM writes.
                return InputOutcome.RETRY;
            }
        }
        return quitRequested ? InputOutcome.QUIT : InputOutcome.ACCEPTED;
    }

    /**
     * Submits the {@code --initial-prompt} entries before the REPL takes over, in order, through
     * the very same dispatch as a typed line (see {@link #runLine(String)}): a resume alias such
     * as {@code continue} restarts the loop on the persisted history without sending any message,
     * slash-prefixed entries use the shared routing decision. Entries are sequential, so in queueing mode each turn
     * waits for the previous one to complete instead of being integrated into it as a typed-ahead
     * prompt; only entries that actually start a turn have a new future to await.
     */
    private void submitInitialPrompts() {
        final var prompts = conf.initialPrompt();
        if (prompts == null || prompts.isEmpty()) {
            return;
        }
        for (final var prompt : prompts) {
            if (prompt == null || prompt.isBlank()) {
                continue;
            }
            final var previous = inFlight;
            if (routeLine(prompt, false) != InputOutcome.ACCEPTED) {
                break;
            }
            if (queueing) {
                final var future = inFlight;
                if (future != null && future != previous) {
                    awaitTurn(future);
                }
            }
        }
    }

    /**
     * Runs one line of user input: a resume alias ({@code continue}, ...) re-runs the agent loop on
     * the persisted history, anything else is a regular (possibly queued) turn.
     */
    private void runInput(final String text) {
        if (agentSessionService.isResumeAlias(text)) {
            runResume();
        } else {
            runTurn(text);
        }
    }

    /**
     * While a turn runs the REPL keeps reading silently; every resolved prompt
     * is queued and integrated by the running turn at its next checkpoint,
     * mirroring the TUI's prompt queue.
     *
     * @return {@code false} when the REPL should exit.
     */
    private boolean awaitAndRouteWhileBusy() throws IOException {
        final Optional<String> line;
        try {
            line = awaitLine("");
        } catch (final PromptInterruptException e) {
            if (quitRequested) {
                return false;
            }
            // Ctrl-C while waiting: render the interrupt menu (or fall back to the legacy
            // immediate-cancel when the backend raises the exception without arming the menu)
            if (menuPending.compareAndSet(true, false)) {
                promptInterruptMenu();
            } else {
                onInterrupt();
            }
            return !quitRequested;
        }
        if (quitRequested) {
            return false;
        }
        // a Ctrl-C woke the turn-lane read (wakeAll -> empty line): render the menu, keep looping
        if (menuPending.compareAndSet(true, false)) {
            promptInterruptMenu();
            return !quitRequested;
        }
        if (line.isEmpty()) {
            return false;
        }
        final var text = line.get();
        if (text.isBlank()) {
            return true;
        }
        return routeLine(text, true) != InputOutcome.QUIT;
    }

    private boolean turnActive() {
        return activeTurns.get() > 0 || session.busy();
    }

    /** Joins the (already completed for terminal IO) asynchronous read of the shell. */
    private Optional<String> awaitLine(final String promptText) throws IOException {
        try {
            return io.readLine(promptText).toCompletableFuture().join();
        } catch (final CompletionException e) {
            final var cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof PromptInterruptException interrupt) {
                throw interrupt;
            }
            throw cause instanceof IOException io ? io : new IOException(cause);
        }
    }

    private void runTurn(final String input) {
        if (queueing) {
            pendingPrompts.addFirst(input);
            drainQueueIfIdle();
            return;
        }
        startPromptTurn(input);
    }

    private void startPromptTurn(final String text) {
        final var override = agentSessionService.agentOverride(text);
        final var agentName =
                override.agentName() != null ? override.agentName() : (activeAgent.isBlank() ? null : activeAgent);
        startTurn(override.prompt(), agentName, "prompt not sent (maven knowledge index still running)");
    }

    /**
     * Starts the next queued prompt as a fresh turn, but only when the CLI is
     * otherwise idle (the running turn already injects the queue directly).
     */
    private void drainQueueIfIdle() {
        turnLock.lock();
        try {
            if (quitRequested) {
                // a quit drops the queue: nothing may start a new turn while leaving
                pendingPrompts.clear();
                return;
            }
            if (!pendingPrompts.isEmpty() && !turnActive() && (inFlight == null || inFlight.isDone())) {
                final var next = pendingPrompts.pollFirst();
                if (next != null) {
                    startPromptTurn(next);
                }
            }
        } finally {
            turnLock.unlock();
        }
    }

    /**
     * Resume turn: re-runs the agent loop on the persisted history without sending any message,
     * so continuing an interrupted or truncated turn costs zero extra prompt tokens.
     */
    private void runResume() {
        startTurn(
                null,
                activeAgent.isBlank() ? null : activeAgent,
                "resume skipped (maven knowledge index still running)");
    }

    /**
     * Runs one turn through the shared {@link TurnRunner} (prompt {@code null}
     * means resume). In queueing mode it returns immediately so the REPL keeps
     * reading lines (which the running turn integrates); otherwise it blocks
     * until completion, keeping the CLI REPL synchronous by nature.
     */
    private void startTurn(final String prompt, final String agentName, final String blockedMessage) {
        final CompletableFuture<Boolean> future;
        turnLock.lock();
        try {
            if (quitRequested) {
                // Never restart a turn once quitting: resetting cancellation could pin the exit.
                pendingPrompts.clear();
                return;
            }
            cancelled.set(false);
            final var runtime = session.runtime();
            final var turnSessionId = sessionId;
            final var todosBefore = runtime.todos().all();
            activeTurns.incrementAndGet();
            try {
                future = turnRunner
                        .submit(new TurnRunner.TurnRequest(
                                runtime.loop(),
                                env(),
                                turnSessionId,
                                agentName,
                                prompt,
                                () -> sessionStore.load(turnSessionId),
                                cancelled::get,
                                queueing ? () -> pendingPrompts.pollFirst() : null))
                        .whenComplete((ran, error) -> {
                            turnLock.lock();
                            try {
                                if (Boolean.FALSE.equals(ran)) {
                                    println(MarkdownColorizer.status(blockedMessage));
                                }
                                printTodosIfChanged(todosBefore);
                                printUsage();
                            } finally {
                                activeTurns.decrementAndGet();
                                turnLock.unlock();
                            }
                            if (queueing) {
                                drainQueueIfIdle();
                            }
                        })
                        .toCompletableFuture();
            } catch (final RuntimeException e) {
                activeTurns.decrementAndGet();
                throw e;
            }
            inFlight = future;
        } finally {
            turnLock.unlock();
        }
        if (!queueing) {
            awaitTurn(future);
        }
    }

    /**
     * Blocks until the turn completes so the divider and the next prompt are
     * painted the moment the turn future is done. While waiting, a pending
     * interrupt menu (armed by Ctrl-C on the signal thread) is rendered on this
     * REPL thread and answered; this is what makes "interrupt without exiting"
     * work even on the jline backend whose reader cannot be polled.
     * <p>
     * Once a quit is requested (menu option {@code 2}, {@code exit}, double
     * Ctrl-C) the menu is never rendered again and the turn gets a bounded
     * grace period to unwind before the process is force-exited: quitting must
     * terminate the CLI even when the turn hangs (a stuck LLM call must not be
     * able to pin the REPL in this loop forever).
     */
    private void awaitTurn(final CompletableFuture<Boolean> future) {
        awaitTurnLoop(
                future,
                menuPending,
                () -> quitRequested,
                this::promptInterruptMenu,
                () -> forceQuit("turn did not stop - exiting anyway"),
                CLEANUP_AWAIT_NANOS);
    }

    /**
     * The wait loop behind {@link #awaitTurn(CompletableFuture)}, extracted with
     * plain collaborators so the quit semantics are unit-testable without
     * booting the whole CLI:
     * <ul>
     *     <li>while not quitting, a pending menu is rendered;</li>
     *     <li>a quit stops menu rendering immediately and starts a grace clock;</li>
     *     <li>when the grace elapses with the future still incomplete, {@code forceQuit} runs;</li>
     *     <li>the future completing always ends the loop quietly.</li>
     * </ul>
     *
     * @param future the in-flight turn completion.
     * @param menuPending the armed-interrupt flag cleared by rendering the menu.
     * @param quitRequested probe of the quit state (read on this thread).
     * @param renderMenu renders and answers the interrupt menu.
     * @param forceQuit tears the CLI down when the turn outlives the grace period.
     * @param quitGraceNanos how long the turn may keep running after a quit request.
     */
    static void awaitTurnLoop(
            final CompletableFuture<Boolean> future,
            final AtomicBoolean menuPending,
            final BooleanSupplier quitRequested,
            final Runnable renderMenu,
            final Runnable forceQuit,
            final long quitGraceNanos) {
        var quitSeen = false;
        var quitAtNanos = 0L;
        while (!future.isDone()) {
            final var quitting = quitRequested.getAsBoolean();
            if (quitting && !quitSeen) {
                quitSeen = true;
                quitAtNanos = System.nanoTime();
            }
            if (!quitting && menuPending.compareAndSet(true, false)) {
                renderMenu.run();
                continue;
            }
            try {
                future.get(100, TimeUnit.MILLISECONDS);
            } catch (final ExecutionException e) {
                throw new CompletionException(e.getCause() == null ? e : e.getCause());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (final TimeoutException e) {
                // keep polling for the interrupt menu and the completion
            }
            if (quitSeen && !future.isDone() && System.nanoTime() - quitAtNanos >= quitGraceNanos) {
                forceQuit.run();
                return;
            }
        }
    }

    /**
     * Prints the todo list once at the end of the turn, on stdout, when the
     * turn changed it; an unchanged list stays silent.
     */
    private void printTodosIfChanged(final List<TodoItem> before) {
        usagePrinter().printTodosIfChanged(before);
    }

    private void printUsage() {
        usagePrinter().printUsage();
    }

    private CliUsagePrinter usagePrinter() {
        if (usagePrinter == null) {
            usagePrinter = new CliUsagePrinter(
                    conf, session, frontend, symbols, line -> println(line), () -> println(), this::writeLineErr);
        }
        return usagePrinter;
    }

    /** The environment shared actions run against; the transcript is the shared one. */
    private ShellEnv env() {
        refreshLabels();
        return new ShellEnv(frontend, io, session, coordinator.transcript());
    }

    /**
     * Renders the current speaker labels (from {@code yca.labels}) into the shared
     * transcript before each interaction. Labels change with the active model, provider
     * and agent, so they are refreshed at every turn boundary: already-written blocks
     * keep the label they were written with, the next ones use the fresh render.
     */
    private void refreshLabels() {
        refreshLabels(session, coordinator.transcript());
    }

    private void refreshLabels(final ShellSession target, final ConversationTranscript transcript) {
        final var ctx = new SpeakerLabels.Ctx(
                chatClient.model(target.chatSettings()),
                chatClient.providerId(target.chatSettings()),
                target.activeAgent(),
                target.runtime() == null
                        ? null
                        : String.valueOf(target.runtime().permissions().mode()),
                target.sessionId(),
                null);
        transcript.setLabels(speakerLabels.userLabel(ctx), speakerLabels.assistantLabel(ctx));
        transcript.setFeedbackLabelResolver((identity, user, at) -> {
            final var captured = identity == null
                    ? ctx.at(at)
                    : new SpeakerLabels.Ctx(identity.model(), identity.provider(), identity.agent(), null, null, null)
                            .at(at);
            return user ? speakerLabels.userLabel(captured) : speakerLabels.assistantLabel(captured);
        });
        frontend.feedbackLabel(at -> speakerLabels.assistantLabel(ctx.at(at)));
        transcript.setLabelResolver((identity, user) -> {
            final var ictx =
                    new SpeakerLabels.Ctx(identity.model(), identity.provider(), identity.agent(), null, null, null);
            return user ? speakerLabels.userLabel(ictx) : speakerLabels.assistantLabel(ictx);
        });
    }

    private void slash(final String text) {
        final var command = WHITESPACE.split(text.substring(1), 2)[0].toLowerCase(Locale.ROOT);
        if ("new".equals(command) || "resume".equals(command)) {
            turnLock.lock();
            try {
                if (turnActive() || (inFlight != null && !inFlight.isDone()) || !pendingPrompts.isEmpty()) {
                    frontend.error("cannot switch sessions while a turn or queued prompt is pending");
                    return;
                }
                if (!quitRequested && !cleaned.get()) {
                    switchSession(text, "new".equals(command));
                }
            } finally {
                turnLock.unlock();
            }
            return;
        }
        final ShellOutcome outcome;
        try {
            outcome = slashRegistry.dispatch(text, env()).toCompletableFuture().join();
        } catch (final CompletionException e) {
            final var cause = e.getCause();
            if (cause instanceof PromptInterruptException) {
                onInterrupt();
                return;
            }
            frontend.error(cause == null ? e.getMessage() : cause.getMessage());
            return;
        }
        this.sessionId = session.sessionId();
        this.activeAgent = session.activeAgent() == null ? "" : session.activeAgent();
        outcome.applyTo(frontend);
        if (outcome instanceof ShellOutcome.Quit) {
            println(MarkdownColorizer.status("bye"));
            requestQuit();
        }
    }

    /** Stage the slash action without changing the live session or its transcript on failure. */
    private void switchSession(final String text, final boolean fresh) {
        // The action selects a target id; no selection means a cancelled picker, not a same-id resume.
        final var candidate = new ShellSession()
                .activeAgent(activeAgent)
                .runtime(session.runtime())
                .showThinking(session.showThinking())
                .showToolOutput(session.showToolOutput())
                .diffViewMode(session.diffViewMode())
                .metrics(session.metrics());
        final var transcript = new ConversationTranscript();
        refreshLabels(session, transcript);
        try {
            final var outcome = slashRegistry
                    .dispatch(text, new ShellEnv(frontend, io, candidate, transcript))
                    .toCompletableFuture()
                    .join();
            final var nextId = candidate.sessionId();
            if (outcome instanceof ShellOutcome.Error || nextId == null) {
                outcome.applyTo(frontend);
                return;
            }
            if (sessionId.equals(nextId)) {
                coordinator.transcript().reset();
                coordinator.transcript().appendTranscript(transcript.markdown("", ""));
                outcome.applyTo(frontend);
                return;
            }
            if (!fresh
                    && sessionStore.list().stream().noneMatch(info -> info.id().equals(nextId))) {
                throw new IllegalArgumentException("unknown session: " + nextId);
            }
            final var nextPath = sessionStore.path(nextId);
            // A fresh session stays in the active directory, even after resuming a different project.
            // Resume resolves eligible persisted metadata, otherwise the actual invocation default.
            final var root = sessionRuntimeFactory.resolveWorkdir(
                    fresh ? session.runtime().workdir() : null, fresh ? null : nextId);
            final var next = sessionRuntimeFactory.create(nextId, root, conf.sandbox());
            try {
                sessionLifecycle.installGates(io.approvalGate(), io.feedbackGate(), next);
                if (fresh) {
                    sessionStore.markProject(nextId, root.toString());
                }
            } catch (final RuntimeException e) {
                disposeRuntime(null, next);
                throw e;
            }
            // Unregister by the old id while the old session still owns its runtime.
            shellRegistry.unregister(env());
            disposeRuntime(sessionId, session.runtime());
            session.runtime(next)
                    .sessionId(nextId)
                    .activeAgent(candidate.activeAgent())
                    .pendingMode(null);
            sessionId = nextId;
            activeAgent = candidate.activeAgent() == null ? "" : candidate.activeAgent();
            contextPath = nextPath;
            sessionStore.keepSession(nextId);
            frontend.bindCompression(next.compression());
            frontend.stats().reset();
            shellRegistry.register(env());
            // Registration resets shared conversation state; publish the staged replay afterwards.
            coordinator.reset();
            coordinator.transcript().appendTranscript(transcript.markdown("", ""));
            outcome.applyTo(frontend);
        } catch (final RuntimeException e) {
            // /new allocates storage before root/runtime creation. Apply the existing empty-session
            // cleanup policy if that allocation never became the active session.
            if (fresh && candidate.sessionId() != null && !candidate.sessionId().equals(sessionId)) {
                try {
                    if (sessionStore.isEmpty(candidate.sessionId())) {
                        sessionStore.delete(candidate.sessionId());
                    }
                } catch (final RuntimeException cleanupFailure) {
                    LOGGER.log(Level.FINE, "Failed to remove an aborted CLI session", cleanupFailure);
                }
            }
            final var cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof PromptInterruptException) {
                onInterrupt();
            } else {
                frontend.error("session switch failed: " + cause.getMessage());
            }
        } finally {
            refreshLabels();
        }
    }

    /** Own only the per-session runtime; process-scoped services are borrowed. */
    private void disposeRuntime(final String id, final SessionRuntime runtime) {
        try {
            if (id != null) {
                runtime.loop().forceExtractNow(sessionStore.load(id), runtime.chatSettings());
            }
        } catch (final RuntimeException e) {
            LOGGER.log(Level.FINE, "Forced memory extraction on CLI session end failed", e);
        } finally {
            try {
                sessionLifecycle.release(id, runtime);
            } catch (final RuntimeException e) {
                LOGGER.log(Level.WARNING, "CLI session release failed", e);
            } finally {
                try {
                    runtime.close();
                } catch (final RuntimeException e) {
                    LOGGER.log(Level.WARNING, "CLI runtime close failed", e);
                }
            }
        }
    }

    private void installInterruptHandler() {
        try {
            // fired reflectively so the build does not depend on the internal
            // sun.misc package; when unavailable the portable shutdown hook covers Ctrl-C.
            final var signal = Class.forName("sun.misc.Signal");
            final var handler = Class.forName("sun.misc.SignalHandler");
            final var handle = signal.getMethod("handle", signal, handler);
            final var ctor = signal.getConstructor(String.class);
            final var interruptHandler = Proxy.newProxyInstance(
                    CliCommand.class.getClassLoader(), new Class<?>[] {handler}, (_, method, _) -> {
                        if (method.getName().equals("handle")) {
                            onInterrupt();
                        }
                        return null;
                    });
            handle.invoke(null, ctor.newInstance("INT"), interruptHandler);
        } catch (final Throwable ignored) {
            // signal handling is best-effort; the shutdown hook remains the portable fallback
        }
    }

    private void onInterrupt() {
        if (quitRequested) {
            // quit already requested (menu "2", exit, double ctrl-c): a new Ctrl-C must
            // not re-open a menu which cannot do anything anymore, it forces the exit
            forceQuit("bye");
            return;
        }
        // while a turn runs with no interactive read live, SIGINT arms the menu; the menu is
        // rendered on the REPL thread (the signal handler must not touch the jline reader)
        if (session.busy() && !interactionActive.get()) {
            menuPending.set(true);
            // queueing mode: the REPL is blocked in a turn-lane read; wake it so it can render
            // the menu. Non-queueing (jline) awaits the turn future and polls menuPending itself.
            wakePrompt();
            return;
        }
        switch (interruptor.interrupt(session.busy())) {
            case INTERRUPT -> {
                cancelled.set(true);
                writeLineErr(
                        MarkdownColorizer.status("interrupting the current turn (ctrl-c again once idle to quit)..."));
            }
            case ARM_QUIT ->
                println(MarkdownColorizer.status("press Ctrl+C again within 2s to quit (Ctrl-D exits too)"));
            case QUIT -> {
                requestQuit();
                println(MarkdownColorizer.status("bye"));
            }
        }
    }

    /**
     * Flags the quit, cancels the in-flight turn, drops every queued prompt (nothing
     * may start a new turn once quit is requested) and wakes a waiting read. Shared by
     * the interrupt menu, {@code exit}, {@code /exit} and the interrupt coordinator so
     * all exit paths behave the same.
     */
    private void requestQuit() {
        quitRequested = true;
        cancelled.set(true);
        pendingPrompts.clear();
        wakePrompt();
    }

    /**
     * Tears the CLI down without waiting any longer: the turn is cancelled, queued
     * prompts are dropped and the JVM exits with the SIGINT status (130), the
     * registered shutdown hook running the usual cleanup. Used when the turn outlives
     * the post-quit grace period and when Ctrl-C is pressed after a quit request -
     * mirroring the headless {@code RunCommand} exit so quitting is always deterministic.
     */
    private void forceQuit(final String reason) {
        requestQuit();
        println(MarkdownColorizer.status(reason));
        System.exit(130);
    }

    /** What an interrupt menu answer asks for; see {@link #menuChoice(String)}. */
    enum MenuChoice {
        CANCEL,
        QUIT,
        NONE
    }

    /**
     * Parses an interrupt menu answer: {@code 1/c/cancel} cancels the current turn,
     * {@code 2/e/exit/q} quits the CLI, anything else (including an empty line or a
     * Ctrl-C that cancelled the read) keeps the turn running. Case and surrounding
     * whitespace are ignored; {@code null} is an empty answer.
     */
    static MenuChoice menuChoice(final String answer) {
        return switch (answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT)) {
            case "1", "c", "cancel" -> MenuChoice.CANCEL;
            case "2", "e", "exit", "q" -> MenuChoice.QUIT;
            default -> MenuChoice.NONE;
        };
    }

    /**
     * The session line emitted when the CLI exits (see {@link #cleanup()}): the id and, when a
     * context file was actually created, its path. Routed to stderr at teardown time, so a closed
     * or detached stdout cannot drop it.
     */
    private String sessionContextLine() {
        return sessionId != null && contextPath != null && Files.exists(contextPath)
                ? "session " + sessionId + " - context file: " + contextPath
                : "session " + sessionId + " (empty; no context file was created)";
    }

    /**
     * Renders and answers the Ctrl-C interrupt menu: cancel the current turn (stays in the
     * REPL) or exit. The read claims the terminal through the interaction gates (output
     * suspended, {@link #interactionActive} set) so turn output cannot stomp the question
     * and a Ctrl-C landing while the menu reads is routed to the interrupt coordinator
     * instead of re-arming the menu.
     */
    private void promptInterruptMenu() {
        final String raw;
        interactionStart.run();
        frontend.renderLock().lock();
        try {
            raw = interactionLine(MarkdownColorizer.accent("What do you want to do? [1=cancel current turn, 2=exit] "))
                    .orElse("");
        } catch (final PromptInterruptException e) {
            println(MarkdownColorizer.status("interrupt cancelled"));
            return;
        } catch (final IOException e) {
            println(MarkdownColorizer.status("interrupt cancelled"));
            return;
        } finally {
            frontend.renderLock().unlock();
            interactionEnd.run();
        }
        switch (menuChoice(raw)) {
            case CANCEL -> {
                cancelled.set(true);
                println(MarkdownColorizer.status("interrupting the current turn..."));
            }
            case QUIT -> {
                requestQuit();
                println(MarkdownColorizer.status("bye"));
            }
            case NONE -> println(MarkdownColorizer.status("no change - turn keeps running"));
        }
    }

    /** Reads one line destined for an inline interaction (higher priority than a turn prompt). */
    private Optional<String> interactionLine(final String promptText) throws IOException {
        return prompt instanceof LinedPrompt lined
                ? lined.readInteractionLine(promptText)
                : prompt.readLine(promptText);
    }

    private void wakePrompt() {
        if (prompt instanceof LinedPrompt lined) {
            lined.wakeAll();
        }
    }

    /**
     * A thin divider rule printed before each user prompt so consecutive
     * turns remain visually separated across the scrolling stdout output.
     */
    private String dividingRule() {
        return MarkdownColorizer.dim(symbols.boxHorizontal().repeat(80));
    }

    private void println() {
        writeLine("\n");
    }

    private void println(final String text) {
        writeLine(text + "\n");
    }

    /**
     * Routes a completed line through the single prompt write path. Turn-end
     * output (todos, usage, dividers) and late async events race the next
     * prompt; the sink decides - atomically with the read lifecycle - between a
     * raw stdout write and a {@code printAbove} plus prompt repaint, so the
     * prompt stays visible without the user having to press a key for it to
     * reappear.
     */
    private void writeLine(final String line) {
        externalOutput.print(line);
    }

    /** Same as {@link #writeLine(String)} but on stderr (usage/metrics lines). */
    private void writeLineErr(final String line) {
        externalOutput.printErr(line + "\n");
    }
}
