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
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.MetricsFormat;
import io.yupiik.ia.agent.core.shell.RunShellFrontend;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = "run",
        description =
                "Run a single headless prompt and print the answer on stdout. All modes accept --sandbox true|false (yca.sandbox.enabled); sandbox policy is independent of tool permissions.")
public class RunCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(RunCommand.class.getName());
    private static final int MAX_PROMPT_FILE_BYTES = 8 * 1024 * 1024;

    private final RunConf conf;
    private final ShellRegistry shellRegistry;
    private final SessionRuntimeFactory sessionRuntimeFactory;
    private final SessionStore sessionStore;
    private final AgentProvider agentProvider;

    private final McpManager mcpManager;
    private final JsonMapper jsonMapper;
    private final FeedbackGate feedbackGate;

    public RunCommand(
            final RunConf conf,
            final ShellRegistry shellRegistry,
            final SessionRuntimeFactory sessionRuntimeFactory,
            final SessionStore sessionStore,
            final AgentProvider agentProvider,
            final McpManager mcpManager,
            final JsonMapper jsonMapper,
            final FeedbackGate feedbackGate) {
        this.feedbackGate = feedbackGate;
        this.jsonMapper = jsonMapper;
        this.mcpManager = mcpManager;
        this.conf = conf;
        this.shellRegistry = shellRegistry;
        this.sessionRuntimeFactory = sessionRuntimeFactory;
        this.sessionStore = sessionStore;
        this.agentProvider = agentProvider;
    }

    @RootConfiguration("run")
    public record RunConf(
            @Property(defaultValue = "\"\"", documentation = "Prompt to send to the agent; exclusive with prompt-file.")
            String prompt,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Session id to append to; a new session is created when empty.")
            String session,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Name of a custom agent (from yca.agents) to use for this run. Its prompt is injected on top of the base system prompt.")
            String agent,

            @Property(defaultValue = "false", documentation = "Print reasoning deltas on stderr.")
            boolean showThinking,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Agent access mode for this run: build or plan (defaults to the configured yca.mode).")
            String mode,

            @Property(
                    value = "show-tool-output",
                    defaultValue = "\"collapsed\"",
                    documentation =
                            "Tool output presentation: shown (print requested arguments, streams and results), collapsed (log tool "
                                    + "calls only, no output body) or hidden (log nothing). Accepts the legacy booleans "
                                    + "true/false (true = shown, false = collapsed/off).")
            String showToolOutput,

            @Property(
                    value = "metrics-output-file",
                    defaultValue = "\"\"",
                    documentation =
                            "Optional path of a metrics file appended on completion (blank to disable). When set, a "
                                    + "single line is written with elapsed ms, accumulated prompt/completion/total tokens, "
                                    + "last estimated context tokens/percent, compaction and code-graph tool-call counts, "
                                    + "plus drill-down aggregates (LLM call count, peak/sum context, tool activity, "
                                    + "tool time and answer length); useful for headless benchmarks.")
            String metricsOutputFile,

            @Property(
                    value = "metrics-detail-file",
                    defaultValue = "\"\"",
                    documentation =
                            "Optional path of a per-event detail log appended during the run (blank to disable). Each "
                                    + "line is one event with a monotonic tick (ms since JVM start): llm (one per LLM "
                                    + "call, with context tokens/percent), toolStart/toolEnd (with tool duration), "
                                    + "compact and answer (final answer length); enables LLM-latency and context-curve "
                                    + "drill-down for benchmarks.")
            String metricsDetailFile,

            @Property(
                    value = "result-file",
                    defaultValue = "\"\"",
                    documentation = "Optional JSON object artifact required for completion; blank disables validation.")
            String resultFile,

            @Property(
                    value = "result-required-fields",
                    defaultValue = "\"\"",
                    documentation =
                            "Comma-separated required nonblank string fields; | separates alternatives (summary|failure).")
            String resultRequiredFields,

            @Property(
                    value = "result-max-bytes",
                    defaultValue = "1000000",
                    documentation = "Maximum result artifact size in bytes (positive).")
            int resultMaxBytes,

            @Property(
                    value = "result-correction-prompt",
                    defaultValue = "\"Completion artifact validation failed: {{{validationError}}}.\\n"
                            + "Write or correct the JSON object at {{{file}}} (maximum {{maxBytes}} bytes).\\n"
                            + "Required nonblank string fields (each group requires one alternative): {{{requiredFields}}}.\\n"
                            + "Repair only this artifact from the existing task context and actual evidence; do not fabricate results. "
                            + "Do not repeat implementation, tests, commits, pushes, or publication. "
                            + "This is the only correction attempt. A chat answer does not replace the artifact.\"",
                    documentation =
                            "Fusion Handlebars template for the single result artifact correction attempt. "
                                    + "Variables: validationError (diagnostic), file (absolute artifact path), maxBytes (size limit), "
                                    + "requiredFields (formatted list of required field alternatives). No environment or other helpers are available.")
            String resultCorrectionPrompt,

            @Property(
                    value = "prompt-file",
                    defaultValue = "\"\"",
                    documentation =
                            "Private regular UTF-8 prompt file (maximum 8 MiB, no symlinks); exclusive with prompt.")
            String promptFile,

            @Property(
                    defaultValue = "null",
                    documentation = "Enable or disable sandboxing for this session; defaults to yca.sandbox.enabled.")
            Boolean sandbox) {}

    private String readPrompt() {
        final var inline = conf.prompt() != null && !conf.prompt().isBlank();
        final var fromFile = conf.promptFile() != null && !conf.promptFile().isBlank();
        if (inline == fromFile) {
            throw new IllegalArgumentException("Exactly one of prompt and prompt-file is required");
        }
        if (inline) {
            return conf.prompt();
        }
        try {
            final var file = Paths.get(conf.promptFile()).toAbsolutePath().normalize();
            if (!file.toRealPath().equals(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Prompt file must be regular and must not contain symlinks");
            }
            final var permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            if (!permissions.equals(PosixFilePermissions.fromString("rw-------"))
                    && !permissions.equals(PosixFilePermissions.fromString("r--------"))) {
                throw new IllegalArgumentException("Prompt file must be private (0600 or 0400)");
            }
            final byte[] bytes;
            try (final var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_PROMPT_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_PROMPT_FILE_BYTES) {
                throw new IllegalArgumentException("Prompt file exceeds 8 MiB");
            }
            final var prompt = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (prompt.isBlank()) {
                throw new IllegalArgumentException("Prompt file must not be blank");
            }
            return prompt;
        } catch (final IOException | UnsupportedOperationException e) {
            // Do not include file contents or provider-specific diagnostics in startup errors.
            throw new IllegalArgumentException("Cannot read private UTF-8 prompt file");
        }
    }

    @Override
    public void run() {
        final var prompt = readPrompt();
        final var resultContract =
                conf.resultFile() == null || conf.resultFile().isBlank()
                        ? null
                        : new RunResultContract(
                                jsonMapper,
                                Paths.get(conf.resultFile()),
                                conf.resultRequiredFields(),
                                conf.resultMaxBytes(),
                                conf.resultCorrectionPrompt());
        mcpManager.start();
        final var sessionId =
                conf.session() == null || conf.session().isBlank() ? sessionStore.create() : conf.session();
        final var failed = new AtomicBoolean();
        final var lastError = new AtomicReference<String>();
        final var metrics = new Metrics();
        final var started = System.nanoTime();
        final var detailLog = Detail.of(conf.metricsDetailFile());
        final var userCancelled = new AtomicBoolean();
        final var parsedToolOutput = ToolOutputVisibility.parse(conf.showToolOutput());
        final var showToolOutput = parsedToolOutput == null ? ToolOutputVisibility.COLLAPSED : parsedToolOutput;
        final var frontend = new RunShellFrontend(conf.showThinking(), showToolOutput, new ToolPresenter(jsonMapper)) {
            @Override
            public void onEvent(final AgentEvent event) {
                super.onEvent(event);
                metrics.accept(event, started, detailLog);
                if (event instanceof final AgentEvent.Error e) {
                    if ("Cancelled".equals(e.message()) && userCancelled.get()) {
                        return; // explicit user interruption, not a failure
                    }
                    failed.set(true);
                    lastError.set(e.message());
                }
            }
        };
        final var shellSession = new ShellSession().sessionId(sessionId);
        final var workdir = sessionRuntimeFactory.resolveWorkdir(null, sessionId);
        final var runtime = sessionRuntimeFactory.create(sessionId, workdir, conf.sandbox());
        shellSession.runtime(runtime);
        final var shellEnv = new ShellEnv(frontend, null, shellSession, null);
        shellRegistry.register(shellEnv);
        final var permissions = runtime.permissions();
        if (conf.mode() != null && !conf.mode().isBlank()) {
            permissions.setMode(AgentMode.fromConfigurationString(conf.mode()));
        }
        // tolerate PermissionService instances built without a configuration (tests): the runtime
        // still resolves its own default; the warning is operational sugar for real runs only
        try {
            if (permissions.mode() == AgentMode.BUILD && permissions.permissionMode() == PermissionMode.ASK) {
                LOGGER.warning("mode=build with permissions=ask in headless mode: state-changing tools will be "
                        + "auto-denied because there is no interactive approval. Set yca.permissions.mode=auto_edit/yolo "
                        + "to allow unattended runs, or use the TUI.");
            }
        } catch (final RuntimeException ignored) {
            // no configuration backing the permission state: skip the startup warning
        }
        permissions.setGate(request -> {
            final var title =
                    (request.origin() == ApprovalGate.Origin.SANDBOX ? "Sandbox restriction: " : "Tool permission: ")
                            + request.title();
            final var detail = request.detail();
            LOGGER.severe(() -> "denied: " + title
                    + " requires an approval in headless mode (" + detail
                    + "); use an interactive frontend to review this restriction.");
            return ApprovalGate.Result.DENY;
        });
        runtime.feedback().setGate(feedbackGate);
        final var agentName = conf.agent() == null || conf.agent().isBlank() ? null : conf.agent();
        if (agentName != null && agentProvider.byName(agentName) == null) {
            throw new IllegalArgumentException("Unknown agent: '" + agentName + "' (configure one under yca.agents)");
        }
        // abrupt-exit memory backup: the double-Ctrl-C "forced exit" path (System.exit) and SIGKILL
        // bypass the finally below, so persist the pending delta from a shutdown hook, capped so the
        // process exit is never blocked for the provider timeout. The clean path removes it right
        // after the finally runs, so it never double-extracts.
        final var memoryShutdownHook = new Thread(
                () -> {
                    try {
                        if (!sessionStore.isEmpty(sessionId)) {
                            runtime.loop().forceExtractNow(sessionStore.load(sessionId), runtime.chatSettings());
                        }
                    } catch (final RuntimeException e) {
                        LOGGER.log(Level.FINE, "Shutdown memory extraction failed", e);
                    }
                },
                "yca-run-memory-extract");
        Runtime.getRuntime().addShutdownHook(memoryShutdownHook);
        try {
            final var cancelled = installCancelHandler(userCancelled);
            runtime.loop().run(prompt, agentName, sessionId, null, cancelled);
            if (resultContract != null && !failed.get() && !cancelled.getAsBoolean()) {
                final var validationError = resultContract.validationError();
                if (validationError != null) {
                    runtime.loop().maxIterationsCap(3);
                    runtime.loop()
                            .run(
                                    resultContract.correctionPrompt(validationError),
                                    agentName,
                                    sessionId,
                                    sessionStore.load(sessionId),
                                    cancelled);
                    if (!failed.get() && !cancelled.getAsBoolean()) {
                        final var remainingError = resultContract.validationError();
                        if (remainingError != null) {
                            throw new IllegalStateException("run result invalid after correction: " + remainingError);
                        }
                    }
                }
            }
        } finally {
            System.out.flush();
            shellRegistry.unregister(shellEnv);
            runtime.close();
            // headless run end: persist the mined memory delta now, before the JVM exits
            try {
                runtime.loop().forceExtractNow(sessionStore.load(sessionId), runtime.chatSettings());
            } catch (final RuntimeException e) {
                LOGGER.log(Level.FINE, "Forced memory extraction on run end failed", e);
            }
            try {
                Runtime.getRuntime().removeShutdownHook(memoryShutdownHook);
            } catch (final IllegalStateException e) {
                // the JVM is already shutting down (forced-exit path): the hook runs on its own
            }
            detailLog.close();
            writeMetrics(metrics, started, runtime.loop().auxiliaryUsage());
        }
        if (failed.get()) {
            throw new IllegalStateException("run failed: " + lastError.get());
        }
    }

    /**
     * Installs a best-effort SIGINT handler so Ctrl-C cancels the running turn cooperatively instead of
     * killing the JVM mid-tool; a second Ctrl-C within 2s force-exits since cancellation is cooperative.
     * When {@code sun.misc.Signal} is unavailable the cancel supplier simply stays false.
     */
    private BooleanSupplier installCancelHandler(final AtomicBoolean userCancelled) {
        return RunCancelHandler.install(userCancelled);
    }

    private void writeMetrics(final Metrics metrics, final long started, final AgentLoop.AuxiliaryUsage aux) {
        if (conf.metricsOutputFile() == null || conf.metricsOutputFile().isBlank()) {
            return;
        }
        final var elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        final var line = "elapsedMs=" + elapsedMs
                + " promptTokens=" + metrics.promptTokens.get()
                + " completionTokens=" + metrics.completionTokens.get()
                + " totalTokens=" + metrics.totalTokens.get()
                + " cacheReadTokens=" + metrics.cacheReadTokens.get()
                + " cacheWriteTokens=" + metrics.cacheWriteTokens.get()
                + " contextTokens=" + metrics.contextTokens
                + " contextPct=" + metrics.contextPercent
                + " compactions=" + metrics.compactions.get()
                + " codeTools=" + metrics.codeTools.get()
                + " llmCalls=" + metrics.llmCalls.get()
                // background (auxiliary) LLM spend: calls/tokens that never appear as conversation
                // turns (memory extraction, compaction summarizer, sub-agent fold-in) but ARE billed
                // and measured by the middleware. aux* break the background out; total* = in-loop +
                // background so the app self-report matches the middleware.
                + " auxLlmCalls=" + (aux == null ? 0 : aux.calls())
                + " auxPromptTokens=" + (aux == null ? 0 : aux.promptTokens())
                + " auxCompletionTokens=" + (aux == null ? 0 : aux.completionTokens())
                + " auxCacheReadTokens=" + (aux == null ? 0 : aux.cacheReadTokens())
                + " auxFreshTokens=" + (aux == null ? 0 : aux.freshTokens())
                + " totalLlmCalls=" + (metrics.llmCalls.get() + (aux == null ? 0 : aux.calls()))
                + " totalPromptTokens="
                + (metrics.promptTokens.get() + (aux == null ? 0 : aux.promptTokens()))
                + " totalCompletionTokens="
                + (metrics.completionTokens.get() + (aux == null ? 0 : aux.completionTokens()))
                + " maxContext=" + metrics.maxContext
                + " sumContext=" + metrics.sumContext.get()
                + " toolCalls=" + metrics.toolCalls.get()
                + " codeTraceCalls=" + metrics.codeTraceCalls.get()
                + " codeCheckGraph=" + metrics.codeCheckGraph.get()
                + " toolErrors=" + metrics.toolErrors.get()
                + " toolMs=" + metrics.toolMs.get()
                // nonToolElapsedMs = wall time not spent running tools (model/setup overhead).
                // llmMs is kept as an alias of the same value so existing benchmarks/consumers that
                // keyed on the old name keep parsing; it is NOT a distinct "LLM-only" wall measure.
                + " nonToolElapsedMs=" + Math.max(0, elapsedMs - metrics.toolMs.get())
                + " llmMs=" + Math.max(0, elapsedMs - metrics.toolMs.get())
                + " outputTokens=" + metrics.throughput.get().outputTokens()
                + " llmActiveMs=" + metrics.throughput.get().activeMs()
                + " llmReportedMs=" + metrics.throughput.get().reportedMs()
                + " outTokPerSec=" + rate(metrics.throughput.get().tokensPerSecond())
                + " realTokPerSec=" + rate(metrics.throughput.get().reportedTokensPerSecond())
                + throughputAuditFields(metrics.throughput.get())
                + " answerChars=" + metrics.answerChars.get()
                + " model=" + metrics.model;
        try {
            Files.writeString(
                    Paths.get(conf.metricsOutputFile()), line + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            LOGGER.severe(() -> "failed to write metrics to " + conf.metricsOutputFile() + ": " + e.getMessage());
        }
    }

    /**
     * Renders a rate for the machine readable metrics line: always a number so the output stays
     * parsable, {@code 0.0} meaning "not measured".
     *
     * @param value the rate.
     * @return the rate with a single decimal.
     */
    private static String rate(final double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String throughputAuditFields(final AgentEvent.Throughput throughput) {
        return " requestTokPerSec=" + rate(throughput.tokensPerSecond())
                + " serverTokPerSec=" + rate(throughput.reportedTokensPerSecond())
                + " reportedOutputTokens=" + throughput.reportedOutputTokens()
                + " completedRequests=" + throughput.completedRequests()
                + " reportedRequests=" + throughput.reportedRequests()
                + " outputEstimated=" + throughput.outputEstimated();
    }

    private static final class Metrics {
        private final AtomicLong promptTokens = new AtomicLong();
        private final AtomicLong completionTokens = new AtomicLong();
        private final AtomicLong totalTokens = new AtomicLong();
        private final AtomicLong cacheReadTokens = new AtomicLong();
        private final AtomicLong cacheWriteTokens = new AtomicLong();
        private final AtomicInteger compactions = new AtomicInteger();
        private final AtomicInteger codeTools = new AtomicInteger();
        private final AtomicInteger llmCalls = new AtomicInteger();
        private final AtomicLong sumContext = new AtomicLong();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicInteger codeTraceCalls = new AtomicInteger();
        private final AtomicInteger codeCheckGraph = new AtomicInteger();
        private final AtomicInteger toolErrors = new AtomicInteger();
        private final AtomicLong toolMs = new AtomicLong();
        private final AtomicInteger answerChars = new AtomicInteger();
        private final AtomicReference<AgentEvent.Throughput> throughput =
                new AtomicReference<>(AgentEvent.Throughput.EMPTY);
        private final Deque<Long> toolStack = new ArrayDeque<>();
        private final StringBuilder answer = new StringBuilder();
        private volatile long contextTokens;
        private volatile double contextPercent;
        private volatile long maxContext;
        private volatile String model = "";
        private long perCallReasoning;

        void accept(final AgentEvent event, final long started, final Detail detail) {
            // Display-only: UsageUpdated carries the session's current quota/context gauge (including the
            // synthetic refresh events pushed by pushUsageQuota/refreshUsage). It must NOT increment the
            // llmCalls counter or aggregate tokens - only the explicit LlmRequestCompleted event (one per
            // real model request) accounts for logical requests and per-request spend. Otherwise a display
            // refresh is miscounted as a request and cumulative currentUsage totals are double/triple added.
            if (event instanceof final AgentEvent.UsageUpdated u) {
                if (u.model() != null && !u.model().isBlank()) {
                    model = u.model();
                }
                contextTokens = u.estimatedContextTokens();
                contextPercent = u.contextPercent();
                if (u.estimatedContextTokens() > maxContext) {
                    maxContext = u.estimatedContextTokens();
                }
                sumContext.addAndGet(u.estimatedContextTokens());
            } else if (event instanceof final AgentEvent.LlmRequestCompleted c) {
                // one per logical model request: per-request usage, even when the provider reported none
                // (Usage.EMPTY still counts the request - a real zero-usage request must never be skipped).
                llmCalls.incrementAndGet();
                final var usage = c.usage();
                if (usage != null) {
                    promptTokens.addAndGet(Math.max(0, usage.promptTokens()));
                    completionTokens.addAndGet(Math.max(0, usage.completionTokens()));
                    cacheReadTokens.addAndGet(Math.max(0, usage.cacheReadTokens()));
                    cacheWriteTokens.addAndGet(Math.max(0, usage.cacheWriteTokens()));
                    totalTokens.addAndGet(Math.max(0, usage.totalTokens()));
                }
                detail.llm(
                        started,
                        contextTokens,
                        contextPercent,
                        usage == null ? 0 : Math.max(0, usage.promptTokens()),
                        usage == null ? 0 : Math.max(0, usage.completionTokens()),
                        usage == null ? 0 : Math.max(0, usage.cacheReadTokens()),
                        perCallReasoning);
                perCallReasoning = 0;
            } else if (event instanceof final AgentEvent.Compacted c) {
                compactions.incrementAndGet();
                detail.compact(started, String.join(",", c.kinds()), c.mode(), c.tokens());
            } else if (event instanceof final AgentEvent.Phase p) {
                if (p.name() != null) {
                    detail.phase(started, p.name());
                }
            } else if (event instanceof final AgentEvent.ToolStarted t) {
                toolCalls.incrementAndGet();
                if (t.name().startsWith("code_")) {
                    codeTools.incrementAndGet();
                }
                if ("code_trace_calls".equals(t.name())) {
                    codeTraceCalls.incrementAndGet();
                } else if ("code_check_graph".equals(t.name())) {
                    codeCheckGraph.incrementAndGet();
                }
                toolStack.push(System.nanoTime());
                detail.toolStart(started, t.name());
            } else if (event instanceof final AgentEvent.ToolFinished t) {
                if (!t.success()) {
                    toolErrors.incrementAndGet();
                }
                final var start = toolStack.isEmpty() ? null : toolStack.pollFirst();
                if (start != null) {
                    final var ms = (System.nanoTime() - start) / 1_000_000L;
                    toolMs.addAndGet(ms);
                    detail.toolEnd(started, t.name(), ms);
                }
            } else if (event instanceof final AgentEvent.TextDelta t) {
                answer.append(t.delta());
            } else if (event instanceof AgentEvent.TextReset) {
                answer.setLength(0);
            } else if (event instanceof final AgentEvent.ThinkingDelta t) {
                perCallReasoning += t.delta().length();
            } else if (event instanceof final AgentEvent.TurnDone t) {
                answerChars.set(
                        t.content() == null ? answer.length() : t.content().length());
                detail.answer(
                        started,
                        t.content() == null ? answer.length() : t.content().length());
            } else if (event instanceof final AgentEvent.TurnMetrics tm) {
                throughput.set(tm.throughput() == null ? AgentEvent.Throughput.EMPTY : tm.throughput());
                detail.turnMetrics(
                        started,
                        tm.llmCalls(),
                        tm.thinkingDurationMs(),
                        tm.toolDurationSumMs(),
                        tm.toolDurationWallMs(),
                        tm.toolsRun(),
                        tm.turnDurationMs(),
                        tm.throughput());
            }
        }
    }

    /**
     * Optional per-event detail log enabling LLM-latency and context-curve drill-down.
     */
    private record Detail(BufferedWriter writer) implements AutoCloseable {

        static Detail of(final String path) {
            if (path == null || path.isBlank()) {
                return new Detail(null);
            }
            try {
                return new Detail(Files.newBufferedWriter(
                        Paths.get(path),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING));
            } catch (final IOException e) {
                System.err.println("[error] failed to open metrics-detail-file " + path + ": " + e.getMessage());
                return new Detail(null);
            }
        }

        private void write(final String line) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(line);
                writer.write(System.lineSeparator());
            } catch (final IOException e) {
                // non-fatal for the benchmark; drop further detail logging
            }
        }

        void llm(
                final long started,
                final long context,
                final double pct,
                final long prompt,
                final long completion,
                final long cacheRead,
                final long reasoning) {
            write("llm t=" + (System.nanoTime() - started) / 1_000_000L
                    + " context=" + context
                    + " pct=" + pct
                    + " prompt=" + prompt
                    + " completion=" + completion
                    + " cacheRead=" + cacheRead
                    + " reasoningChars=" + reasoning);
        }

        void toolStart(final long started, final String name) {
            write("toolStart t=" + (System.nanoTime() - started) / 1_000_000L + " name=" + name);
        }

        void toolEnd(final long started, final String name, final long ms) {
            write("toolEnd t=" + (System.nanoTime() - started) / 1_000_000L + " name=" + name + " ms=" + ms);
        }

        void phase(final long started, final String name) {
            write("phase t=" + (System.nanoTime() - started) / 1_000_000L + " name=" + name);
        }

        void answer(final long started, final int chars) {
            write("answer t=" + (System.nanoTime() - started) / 1_000_000L + " chars=" + chars);
        }

        void compact(
                final long started,
                final String kind,
                final String mode,
                final AgentEvent.Compacted.CompactionTokens tokens) {
            final var shrink =
                    MetricsFormat.shrink(tokens == null ? 0 : tokens.before(), tokens == null ? 0 : tokens.after());
            write("compact t=" + (System.nanoTime() - started) / 1_000_000L
                    + " kind=" + kind
                    + " mode=" + mode
                    + (shrink.isEmpty() ? "" : " shrink=" + shrink));
        }

        void turnMetrics(
                final long started,
                final int llmCalls,
                final long thinkingMs,
                final long toolSumMs,
                final long toolWallMs,
                final int toolsRun,
                final long turnDurationMs,
                final AgentEvent.Throughput throughput) {
            final var t = throughput == null ? AgentEvent.Throughput.EMPTY : throughput;
            write("turnMetrics t=" + (System.nanoTime() - started) / 1_000_000L
                    + " llmCalls=" + llmCalls
                    + " thinkingMs=" + thinkingMs
                    + " toolSumMs=" + toolSumMs
                    + " toolWallMs=" + toolWallMs
                    + " toolsRun=" + toolsRun
                    + " turnDurationMs=" + turnDurationMs
                    + " outputTokens=" + t.outputTokens()
                    + " llmActiveMs=" + t.activeMs()
                    + " llmReportedMs=" + t.reportedMs()
                    + " tokPerSec=" + rate(t.tokensPerSecond())
                    + " realTokPerSec=" + rate(t.reportedTokensPerSecond())
                    + throughputAuditFields(t));
        }

        @Override
        public void close() {
            if (writer == null) {
                return;
            }
            try {
                writer.close();
            } catch (final IOException e) {
                // ignore
            }
        }
    }
}
