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
package io.yupiik.ia.agent.core.shell;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.cli.CliDiffRenderer;
import io.yupiik.ia.agent.core.cli.CliVerbosity;
import io.yupiik.ia.agent.core.cli.MarkdownColorizer;
import io.yupiik.ia.agent.core.cli.Spinner;
import io.yupiik.ia.agent.core.cli.StreamTextBuffer;
import io.yupiik.ia.agent.core.cli.Symbols;
import io.yupiik.ia.agent.core.cli.ToolPresenter;
import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import io.yupiik.ia.agent.core.context.ContextCompression;
import io.yupiik.ia.agent.core.context.MarkdownFence;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.util.OpenBrowser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * ANSI terminal frontend shared by the {@code cli} command: streams the answer
 * on stdout through a line colorizer, renders tool activity and notices on
 * stderr, and drives a spinner while the agent is busy. Conversation text is
 * printed incrementally; tool output presentation follows the configured
 * {@link CliVerbosity}.
 */
public class AnsiShellFrontend implements ShellFrontend {
    private static final int DIFF_MAX_LINES = 5;

    private final MarkdownColorizer colorizer;
    private final SecretRedactor secretRedactor;
    private volatile DiffViewMode diffViewMode = DiffViewMode.AUTO;
    private final Symbols symbols;
    private final ToolPresenter presenter;
    private final CliVerbosity verbosity;
    private final boolean tty;
    private final StreamTextBuffer textBuffer;
    private final StringBuilder thinkingText = new StringBuilder();
    private int thinkingEmitted;
    private MarkdownFence.Classifier thinkingCode = new MarkdownFence.Classifier();
    private String thinkingPrefix = "";
    private String answerPrefix = "";
    private final TurnStats stats = new TurnStats();
    private volatile Function<Long, String> feedbackLabel;

    public void feedbackLabel(final Function<Long, String> renderer) {
        feedbackLabel = renderer;
    }

    private volatile ContextCompression compression = new ContextCompression(null);

    private volatile boolean showThinking;
    private volatile ToolOutputVisibility showToolOutput;
    private final ToolInvocations toolCalls = new ToolInvocations();
    /** Whether the dim 'thinking...' header was printed for the current reasoning round. */
    private boolean thinkingRoundOpen;
    /** Whether the last stderr emission (a thought delta) did not end with a newline. */
    private boolean stderrNeedsNewline;
    /**
     * Actor owning the open 'thinking...' round: the subagent id, or {@code null}
     * for the main loop. Spawned sub-agents stream concurrently with the parent
     * turn, so the round is tracked per actor: with a single flag the first
     * voice to stream suppressed the header of the next one.
     */
    private String thinkingRoundOwner;
    /**
     * Actor owning the open (newline-less) stderr line, {@code null} when no
     * line is open. Thought deltas are printed without a trailing newline, so
     * without this two concurrent voices splice their fragments into one
     * garbled line.
     */
    private String stderrLineOwner;

    private Spinner spinner;
    /** Whether the frontend is inside a turn: async events (subagents, parallel tool workers)
     * can still stream after {@link #endTurn()}; they must not (re)start the spinner. */
    private volatile boolean turnActive;

    /**
     * Serializes the spinner lifecycle (start/stop/turn flag flips) between the
     * event threads: {@link #beginSpinner(String)} check-then-act and
     * {@link #endTurn()} used to race, letting a spinner started right after the
     * final stop run forever past the turn. Distinct from {@link #renderLock}:
     * {@link Spinner#stop()} joins the animation worker which needs
     * {@code renderLock} for its frames, so the two locks must never nest.
     */
    private final ReentrantLock spinnerLock = new ReentrantLock();

    private final ArrayDeque<AgentEvent> pendingEvents = new ArrayDeque<>();
    /** Guards {@link #pendingEvents}, {@link #suspended} and the suspension bookkeeping flags. */
    private final ReentrantLock pendingLock = new ReentrantLock();

    private boolean suspended;
    /** Whether the thinking widget was active when output got suspended: restart it on resume. */
    private boolean resumeThinking;
    /** Whether an interaction is pending a trailing newline to leave its card row on resume. */
    private boolean pendingInteractionSeparation;

    /**
     * Serializes every cursor-mutating terminal write between the stderr
     * spinner and the stdout interaction prompts so the two never interleave
     * on the shared physical line. Held for the full spinner frame/erase and
     * for each interaction card + prompt draw.
     */
    private final ReentrantLock renderLock = new ReentrantLock();

    /**
     * Serializes the event handling between the event threads: a spawned
     * sub-agent streams on its own virtual thread while the parent turn keeps
     * going, and the shared rendering state below ({@code thinkingRoundOpen},
     * {@code stderrNeedsNewline}, the owners above, {@link #textBuffer}) was
     * updated check-then-act. Always acquired before {@code spinnerLock} /
     * {@code renderLock}, never the other way round: it is only taken here, and
     * nothing it waits for takes it back.
     */
    private final ReentrantLock eventLock = new ReentrantLock();

    /**
     * The single write path for every chunk this frontend renders: it routes
     * through jline's {@code printAbove} while a prompt is live and writes to
     * the raw streams otherwise, atomically with the read lifecycle. Never
     * {@code null} - {@code null} is normalized to {@link PromptOutput#raw()}
     * so no caller needs a fallback branch.
     */
    private final PromptOutput promptOutput;

    public ReentrantLock renderLock() {
        return renderLock;
    }

    public AnsiShellFrontend(
            final MarkdownColorizer colorizer,
            final SecretRedactor secretRedactor,
            final Symbols symbols,
            final ToolPresenter presenter,
            final CliVerbosity verbosity,
            final boolean tty,
            final boolean showThinking,
            final ToolOutputVisibility showToolOutput) {
        this(colorizer, secretRedactor, symbols, presenter, verbosity, tty, showThinking, showToolOutput, null);
    }

    public AnsiShellFrontend(
            final MarkdownColorizer colorizer,
            final SecretRedactor secretRedactor,
            final Symbols symbols,
            final ToolPresenter presenter,
            final CliVerbosity verbosity,
            final boolean tty,
            final boolean showThinking,
            final ToolOutputVisibility showToolOutput,
            final PromptOutput promptOutput) {
        this.colorizer = colorizer;
        this.secretRedactor = secretRedactor;
        this.symbols = symbols;
        this.presenter = presenter;
        this.verbosity = verbosity;
        this.tty = tty;
        this.showThinking = showThinking;
        this.showToolOutput = showToolOutput;
        this.promptOutput = promptOutput == null ? PromptOutput.raw() : promptOutput;
        this.textBuffer = new StreamTextBuffer(line -> answerPrefix + colorizer.colorizeLine(line));
    }

    public TurnStats stats() {
        return stats;
    }

    @Override
    public void diffViewMode(final DiffViewMode value) {
        diffViewMode = Objects.requireNonNull(value, "diffViewMode");
    }

    private boolean showDiff() {
        return showToolOutput != ToolOutputVisibility.HIDDEN;
    }

    private String redactDiff(final String text, final String sessionId) {
        if (secretRedactor == null) {
            return text;
        }
        try {
            return secretRedactor.redact(text, sessionId);
        } catch (final RuntimeException unavailable) {
            return text == null ? null : "(redacted content unavailable)";
        }
    }

    private void renderDiff(final List<String> lines, final String language, final String prefix) {
        for (final var line : CliDiffRenderer.render(
                lines, language, diffViewMode, promptOutput.columns() - prefix.length() - 4, tty, colorizer)) {
            printlnErr(prefix + "    " + line);
        }
    }

    private void onFilesChanged(final AgentEvent.ToolFilesChanged event, final String prefix) {
        if (!showDiff()) {
            return;
        }
        stopSpinner();
        closeThinkingRound();
        renderLock.lock();
        try {
            ensureStderrNewline();
            final var files = event.files();
            for (var i = 0; i < Math.min(20, files.size()); i++) {
                final var file = files.get(i);
                final var path = redactDiff(file.path().toString(), event.sessionId());
                // Render metadata through the same control-escaping path as the code.
                renderDiff(List.of("applied " + file.kind().name().toLowerCase(Locale.ROOT) + ": " + path), "", prefix);
                final var content = file.content();
                if (content == null) {
                    renderDiff(List.of("(content unavailable)"), "", prefix);
                } else if ((content.oldText() != null && content.oldText().length() > DiffLayout.MAX_CHARS)
                        || content.newText().length() > DiffLayout.MAX_CHARS) {
                    renderDiff(List.of("(diff omitted: content limit)"), "", prefix);
                } else {
                    try {
                        final var before = redactDiff(content.oldText(), event.sessionId());
                        final var after = redactDiff(content.newText(), event.sessionId());
                        if (!Objects.equals(before, content.oldText()) || !Objects.equals(after, content.newText())) {
                            renderDiff(List.of("(exact diff unavailable: redacted content unavailable)"), "", prefix);
                        } else {
                            renderDiff(DiffLayout.between(before, after), path, prefix);
                        }
                    } catch (final RuntimeException unavailable) {
                        // Redaction failure must not expose raw content or fail a completed write.
                        renderDiff(List.of("(diff unavailable)"), "", prefix);
                    }
                }
            }
            if (files.size() > 20) {
                printlnErr(prefix + "    ... remaining files omitted");
            }
        } finally {
            renderLock.unlock();
        }
    }

    /** Rebinds the seam-scrub source once the owning session runtime is available (live refs). */
    public void bindCompression(final ContextCompression value) {
        this.compression = value != null ? value : new ContextCompression(null);
        // the line renderer shares the same evidence source, so a leaked glued marker
        // ("@Ent...10632") is stripped from streamed answer text as well, not only the thinking
        this.textBuffer.bindCompression(this.compression);
        // Fallback for a model that still guesses a stale ref despite the nudge
        // guidance: when the compress tool skips ranges because their refs are not in the live
        // conversation, surface the same bounded, guaranteed-valid closed-turn pairs on stderr so
        // the operator (and the user) can see the real refs without growing the persisted transcript.
        this.compression.onUnknownRefs(refs -> printErr(compressionRefFallbackLine(refs)));
    }

    /** Bounded closed-turn pairs for the compress unknown-ref fallback, mirroring the tail guidance. */
    private String compressionRefFallbackLine(final List<ContextCompression.Range> refs) {
        final var count = this.compression.compressibleClosedTurnCount();
        if (refs == null || refs.isEmpty()) {
            return "compress: refs are not in the live conversation; no structurally eligible historical ranges."
                    + " Continue the current task; do not guess references.\n";
        }
        final var sb = new StringBuilder("compress: refs are not in the live conversation; valid inclusive ranges: ");
        for (var i = 0; i < refs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(refs.get(i).startId()).append('-').append(refs.get(i).endId());
        }
        final var omitted = count - refs.size();
        if (omitted > 0) {
            sb.append(" (+").append(omitted).append(" omitted)");
        }
        sb.append(" - use exact startId/endId only for genuinely finished work.\n");
        return sb.toString();
    }

    public MarkdownColorizer colorizer() {
        return colorizer;
    }

    /**
     * Whether generated login/authorization URLs should be auto-opened in the system browser.
     * Defaults to {@code true}; set from the owning command's {@code autoOpenBrowser} option.
     */
    private volatile boolean autoOpenBrowser = true;

    /** Sets whether generated URLs are auto-opened in the system browser. */
    public void autoOpenBrowser(final boolean value) {
        this.autoOpenBrowser = value;
    }

    /** Whether generated URLs are auto-opened in the system browser. */
    public boolean autoOpenBrowser() {
        return autoOpenBrowser;
    }

    @Override
    public void openExternalUrl(final String url) {
        if (autoOpenBrowser) {
            OpenBrowser.open(url);
        }
    }

    public Symbols symbols() {
        return symbols;
    }

    public boolean showThinking() {
        return showThinking;
    }

    public ToolOutputVisibility showToolOutput() {
        return showToolOutput;
    }

    @Override
    public void showThinking(final boolean value) {
        if (this.showThinking != value) {
            this.showThinking = value;
            if (!value) {
                // hiding the reasoning while a thought line is open would glue the
                // next stderr line to it: terminate the open line under the event lock
                eventLock.lock();
                try {
                    ensureStderrNewline();
                } finally {
                    eventLock.unlock();
                }
            }
        }
    }

    @Override
    public void showToolOutput(final ToolOutputVisibility value) {
        this.showToolOutput = value;
    }

    @Override
    public void status(final String message) {
        stopSpinner();
        ensureStderrNewline();
        printlnErr(MarkdownColorizer.status(symbols.infoMarker() + " " + message));
    }

    @Override
    public void notice(final AgentEvent.Notice.Level level, final String message) {
        stopSpinner();
        final var marker =
                switch (level) {
                    case ERROR -> symbols.errorMarker();
                    case WARN -> symbols.warningMarker();
                    case INFO -> symbols.infoMarker();
                };
        ensureStderrNewline();
        printlnErr(MarkdownColorizer.status(marker + " " + message));
    }

    @Override
    public void transcript(final String markdown) {
        // speaker-aware replay: thought and tool regions stay visibly subordinate
        // to assistant/user output the same way the live stream is dimmed
        final var sb = new StringBuilder();
        for (final var region : ConversationTranscript.regions(markdown)) {
            final var rendered = colorize(region.text());
            if (!colorizer.isEnabled()) {
                sb.append(rendered);
            } else if (region.thought()) {
                sb.append(MarkdownColorizer.thinkChunk(rendered));
            } else if (region.tool()) {
                sb.append(MarkdownColorizer.dimChunk(rendered));
            } else if (region.log()) {
                // log regions are TUI-only (captured while the TUI runs);
                // render them plain if a headless frontend ever sees one
                sb.append(rendered);
            } else {
                sb.append(rendered);
            }
        }
        print(sb.toString());
    }

    @Override
    public void error(final String message) {
        stopSpinner();
        ensureStderrNewline();
        printlnErr(MarkdownColorizer.error("[error] " + message));
    }

    @Override
    public void activity(final String label) {
        beginSpinner(label);
    }

    @Override
    public void idle() {
        stopSpinner();
    }

    /**
     * Stops rendering events until {@link #resumeOutput()}: keeps interaction
     * boxes (questions, approvals) visible instead of having spinner frames
     * and thinking output bury them.
     */
    public void suspendOutput() {
        pendingLock.lock();
        try {
            suspended = true;
            resumeThinking = thinkingRoundOpen || spinnerRunning();
            pendingInteractionSeparation = true;
        } finally {
            pendingLock.unlock();
        }
        ensureStderrNewline();
        stopSpinner();
    }

    /** Resumes rendering and replays the events buffered while suspended. */
    public void resumeOutput() {
        final List<AgentEvent> queued;
        final boolean turnEnded;
        final boolean separate;
        pendingLock.lock();
        try {
            queued = List.copyOf(pendingEvents);
            pendingEvents.clear();
            suspended = false;
            turnEnded = queued.stream()
                    .anyMatch(e -> e instanceof AgentEvent.TurnDone || e instanceof AgentEvent.TurnCancelled);
            separate = pendingInteractionSeparation;
            pendingInteractionSeparation = false;
        } finally {
            pendingLock.unlock();
        }
        if (tty && separate) {
            println();
        }
        queued.forEach(this::onEvent);
        // Restart the thinking spinner only when the turn is still genuinely
        // active and no buffered event ended it: an unconditional restart here
        // would otherwise leave the spinner spinning forever over the freshly
        // drawn next prompt once the interaction finished.
        if (resumeThinking) {
            resumeThinking = false;
            if (!showThinking && !turnEnded && turnActive) {
                beginSpinner(BusyLabels.thinking());
            }
        }
    }

    /** Renders a markdown block on stdout, colorizing each line when enabled. */
    private String colorize(final String markdown) {
        final var lines = markdown.split("\n", -1);
        final var sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(colorizer.colorizeLine(lines[i]));
        }
        return sb.toString();
    }

    /**
     * Prepares a new turn: resets per-turn stats and the streaming buffer,
     * stops any leftover spinner and starts the "thinking" one.
     */
    @Override
    public void beginTurn() {
        stats.beginTurn();
        textBuffer.reset();
        closeThinkingRound();
        stderrNeedsNewline = false;
        stderrLineOwner = null;
        stopSpinner();
        spinnerLock.lock();
        try {
            turnActive = true;
        } finally {
            spinnerLock.unlock();
        }
        if (!showThinking) {
            beginSpinner(BusyLabels.thinking());
        }
    }

    /**
     * Finalizes a turn: stops the spinner, flushes the streamed answer buffer
     * to stdout and flushes both streams.
     */
    @Override
    public void endTurn() {
        closeThinkingRound();
        // a thinking round may have left an open newline-less stderr line: the
        // trailing metrics/usage/prompt must not glue onto it
        ensureStderrNewline();
        // Flip the turn flag BEFORE stopping: the flag write and any racing
        // beginSpinner are serialized by spinnerLock, so a spinner started by
        // a late event thread either fails the turnActive check or is seen and
        // stopped by the stopSpinner call below. Stopping first (previous
        // order) let a spinner started in between spin forever past the turn.
        spinnerLock.lock();
        try {
            turnActive = false;
        } finally {
            spinnerLock.unlock();
        }
        stopSpinner();
        eventLock.lock();
        try {
            if (showToolOutput.shown() && verbosity != CliVerbosity.COMPACT) {
                for (final var call : toolCalls.active()) {
                    final var rest = call.flush();
                    if (!rest.isEmpty()) {
                        printlnErr(call.header() + " output: " + rest);
                    }
                }
            }
        } finally {
            eventLock.unlock();
        }
        print(textBuffer.flush());
        System.out.flush();
        System.err.flush();
    }

    @Override
    public void onEvent(final AgentEvent event) {
        pendingLock.lock();
        try {
            if (suspended) {
                pendingEvents.add(event);
                return;
            }
        } finally {
            pendingLock.unlock();
        }
        // one event at a time: the parent turn and its sub-agents emit concurrently
        eventLock.lock();
        try {
            renderEvent(event, subagentPrefix(event));
        } finally {
            eventLock.unlock();
        }
    }

    private void renderEvent(final AgentEvent event, final String prefix) {
        if (event instanceof AgentEvent.FeedbackStarted started) {
            if (feedbackLabel != null && ("text".equals(started.kind()) || showThinking)) {
                stopSpinner();
                print(textBuffer.flush());
                textBuffer.reset();
                closeThinkingRound();
                final var heading = prefix + feedbackLabel.apply(started.startedAt());
                if ("thinking".equals(started.kind())) {
                    printlnErr(MarkdownColorizer.thinking(heading));
                } else {
                    print("\n" + colorizer.colorizeLine("**" + heading + "**") + "\n");
                }
            }
        } else if (event
                instanceof
                AgentEvent.TextDelta(String delta, String sessionId, String subagentId, String subagentName, var _)) {
            stopSpinner();
            closeThinkingRound();
            if (!answerPrefix.equals(prefix)) {
                print(textBuffer.flush());
                textBuffer.reset();
                answerPrefix = prefix;
            }
            print(textBuffer.append(delta));
        } else if (event
                instanceof AgentEvent.TextReset(String sessionId, String subagentId, String subagentName, var _)) {
            textBuffer.reset();
            closeThinkingRound();
        } else if (event
                instanceof
                AgentEvent.ThinkingDelta(
                        String delta,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        var _)) {
            if (showThinking) {
                // a second voice must not splice into the line the current one left
                // open: close it so both thoughts stay readable
                if (stderrNeedsNewline && !Objects.equals(stderrLineOwner, subagentId)) {
                    ensureStderrNewline();
                }
                if (!thinkingRoundOpen || !Objects.equals(thinkingRoundOwner, subagentId)) {
                    closeThinkingRound();
                    thinkingRoundOpen = true;
                    thinkingRoundOwner = subagentId;
                    thinkingPrefix = prefix;
                    printlnErr(MarkdownColorizer.thinking(prefix + "thinking..."));
                }
                stopSpinner();
                thinkingText.append(delta);
                renderThinking(false);
                stderrLineOwner = subagentId;
            } else {
                beginSpinner(BusyLabels.thinking());
            }
        } else if (event instanceof final AgentEvent.ToolStarted t) {
            stopSpinner();
            closeThinkingRound();
            onToolStarted(t);
        } else if (event instanceof final AgentEvent.ToolFilesChanged t) {
            onFilesChanged(t, prefix);
        } else if (event instanceof final AgentEvent.ToolStream t) {
            final var call = toolCalls.find(t.name(), t.ordinal(), t.sessionId(), t.subagentId());
            if (showToolOutput.shown() && verbosity != CliVerbosity.COMPACT) {
                stopSpinner();
                ensureStderrNewline();
                for (final var line : call.append(t.fragment())) {
                    printlnErr(prefix + "  " + call.header() + " output: " + line);
                }
            }
        } else if (event instanceof final AgentEvent.ToolDiagnostic t) {
            stopSpinner();
            closeThinkingRound();
            ensureStderrNewline();
            final var call = toolCalls.find(t.name(), t.ordinal(), t.sessionId(), t.subagentId());
            printlnErr(prefix + call.header() + " diagnostic:\n" + t.detail());
        } else if (event instanceof final AgentEvent.ToolFinished t) {
            stats.incToolsRun();
            stopSpinner();
            onToolFinished(t);
            if (!showThinking) {
                beginSpinner(prefix + "thinking");
            }
        } else if (event
                instanceof
                AgentEvent.Compacted(
                        List<String> kinds,
                        String mode,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        AgentEvent.Compacted.CompactionTokens tokens,
                        var _)) {
            stopSpinner();
            ensureStderrNewline();
            final var shrink =
                    MetricsFormat.shrink(tokens == null ? 0 : tokens.before(), tokens == null ? 0 : tokens.after());
            printlnErr(MarkdownColorizer.status(prefix + symbols.infoMarker() + " context " + String.join(", ", kinds)
                    + " applied (" + mode.toLowerCase(Locale.ROOT) + ")"
                    + (shrink.isEmpty() ? "" : ", " + shrink)));
        } else if (event
                instanceof
                AgentEvent.Notice(
                        var level,
                        String message,
                        var finishReason,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        Throwable cause,
                        var compaction,
                        var _)) {
            notice(level, prefix + message);
        } else if (event
                instanceof
                AgentEvent.Pause(
                        AgentEvent.Pause.Kind kind,
                        String message,
                        long durationMs,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        var _)) {
            stopSpinner();
            ensureStderrNewline();
            printlnErr(MarkdownColorizer.status(
                    prefix + (kind == AgentEvent.Pause.Kind.RETRY ? symbols.warningMarker() : symbols.infoMarker())
                            + " " + message));
        } else if (event
                instanceof
                AgentEvent.Status(String message, String sessionId, String subagentId, String subagentName, var _)) {
            status(prefix + message);
        } else if (event
                instanceof
                AgentEvent.SubagentStarted(
                        String task,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        var _,
                        var _)) {
            stopSpinner();
            closeThinkingRound();
            printlnErr(
                    MarkdownColorizer.status(prefix + "[subagent] started: " + ConversationTranscript.firstLine(task)));
        } else if (event
                instanceof
                AgentEvent.SubagentCompleted(
                        String summary,
                        boolean success,
                        boolean cancelled,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        String model,
                        var _)) {
            stopSpinner();
            closeThinkingRound();
            final var state = success ? "completed" : cancelled ? "cancelled" : "failed";
            final var modelSuffix = model == null || model.isBlank() ? "" : " (" + model + ")";
            final var detail =
                    summary == null || summary.isBlank() ? "" : " — " + ConversationTranscript.firstLine(summary);
            final var line = prefix + "[subagent] " + state + modelSuffix + detail;
            if (success) {
                printlnErr(MarkdownColorizer.success(line));
            } else if (cancelled) {
                printlnErr(MarkdownColorizer.dim(line));
            } else {
                printlnErr(MarkdownColorizer.error(line));
            }
        } else if (event
                instanceof
                AgentEvent.Error(String message, String sessionId, String subagentId, String subagentName, var _)) {
            error(prefix + message);
        } else if (event instanceof final AgentEvent.UsageUpdated u) {
            stats.recordUsage(u);
        } else if (event instanceof final AgentEvent.TurnMetrics tm) {
            stats.recordTurnMetrics(tm);
        } else if (event instanceof AgentEvent.TurnDone) {
            // natural terminator for the current actor (main turn or async subagent/tool
            // worker): a hidden-thinking spinner left running by a late event would otherwise
            // keep spinning forever once the turn ended
            stopSpinner();
            ensureStderrNewline();
        } else if (event instanceof AgentEvent.TurnCancelled) {
            // user interrupt: same clean close as a normal end, without an error line
            stopSpinner();
            ensureStderrNewline();
        }
    }

    private String subagentPrefix(final AgentEvent event) {
        String subagentId = null;
        String subagentName = null;
        if (event instanceof AgentEvent.TextDelta(var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.TextReset(var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ThinkingDelta(var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ToolStarted(var _, var _, var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ToolStream(var _, var _, var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event
                instanceof
                AgentEvent.ToolFinished(var _, var _, var _, var _, var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ToolDiagnostic t) {
            subagentId = t.subagentId();
            subagentName = t.subagentName();
        } else if (event instanceof AgentEvent.ToolFilesChanged t) {
            subagentId = t.subagentId();
            subagentName = t.subagentName();
        } else if (event instanceof AgentEvent.Compacted(var _, var _, var _, String id, String name, var _, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event
                instanceof AgentEvent.Notice(var _, var _, var _, var _, String id, String name, var _, var _, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.Status(var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.SubagentStarted(var _, var _, String id, String name, var _, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event
                instanceof
                AgentEvent.SubagentCompleted(var _, var _, var _, var _, String id, String name, var _, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.Error(var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event
                instanceof
                AgentEvent.Pause(var _, String message, long durationMs, String _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event
                instanceof
                AgentEvent.TurnMetrics(
                        var _,
                        var _,
                        var _,
                        var _,
                        var _,
                        var _,
                        var _,
                        var _,
                        String id,
                        String name,
                        var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.TurnDone(var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event
                instanceof
                AgentEvent.ToolTimingEvent(var _, var _, var _, var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        }
        if (subagentId != null) {
            final var displayName =
                    subagentName != null ? subagentName : subagentId.substring(0, Math.min(8, subagentId.length()));
            return "[" + displayName + "] ";
        }
        return "";
    }

    private void onToolStarted(final AgentEvent.ToolStarted t) {
        final var call = toolCalls.start(t);
        if (showToolOutput == ToolOutputVisibility.HIDDEN) {
            return;
        }
        final var prefix = subagentPrefix(t);
        renderLock.lock();
        try {
            ensureStderrNewline();
            printlnErr(MarkdownColorizer.bold(
                    prefix + call.header() + " started" + call.summary(presenter, showToolOutput)));
            final var preview = showDiff() && call.args() != null && call.args().length() <= DiffLayout.MAX_CHARS
                    ? presenter.diff(
                            t.name(),
                            redactDiff(call.args(), t.sessionId()),
                            verbosity == CliVerbosity.COMPACT ? DIFF_MAX_LINES : DiffLayout.MAX_OUTPUT_LINES)
                    : List.<String>of();
            if (!preview.isEmpty()) {
                printlnErr(prefix + "  request preview (not applied):");
                renderDiff(preview, "", prefix);
            }
            if (showToolOutput.shown()) {
                if (!preview.isEmpty() && verbosity != CliVerbosity.RAW) {
                    return;
                }
                printlnErr(prefix + "  request:");
                if (verbosity == CliVerbosity.RAW) {
                    printlnErr(prefix + "    " + t.args());
                } else {
                    final var limit = verbosity == CliVerbosity.COMPACT ? DIFF_MAX_LINES : Integer.MAX_VALUE;
                    final var details = presenter.request(t.name(), call.args(), limit);
                    for (final var line : details) {
                        printlnErr(prefix + "    "
                                + (line.startsWith("+")
                                        ? MarkdownColorizer.diffAdd(line)
                                        : line.startsWith("-") ? MarkdownColorizer.diffDel(line) : line));
                    }
                }
            }
        } finally {
            renderLock.unlock();
        }
    }

    private void onToolFinished(final AgentEvent.ToolFinished t) {
        final var call = toolCalls.find(t.name(), t.ordinal(), t.sessionId(), t.subagentId());
        final var prefix = subagentPrefix(t);
        final var failure = !t.success() && !t.pending();
        final var execution = t.pending() ? "" : presenter.executionDiagnostic(t.name(), t.output());
        try {
            if (showToolOutput == ToolOutputVisibility.HIDDEN && !failure && execution.isEmpty()) {
                return;
            }
            renderLock.lock();
            try {
                ensureStderrNewline();
                if (showToolOutput.shown() && verbosity != CliVerbosity.COMPACT) {
                    final var rest = call.flush();
                    if (!rest.isEmpty()) {
                        printlnErr(prefix + "  " + call.header() + " output: " + rest);
                    }
                }
                final var state = t.pending() ? "pending" : t.success() ? "done" : "failed";
                final var summary = prefix + call.header() + " " + state;
                if (showToolOutput != ToolOutputVisibility.HIDDEN || failure) {
                    printlnErr(
                            t.pending()
                                    ? MarkdownColorizer.status(summary)
                                    : t.success()
                                            ? MarkdownColorizer.success(summary)
                                            : MarkdownColorizer.error(summary));
                }
                if (!execution.isEmpty()) {
                    printlnErr(MarkdownColorizer.warning(prefix + call.header() + " " + execution));
                }
                final var patchOutput = t.output() != null
                        && (t.output().startsWith("--- ")
                                || t.output().startsWith("diff --git ")
                                || t.output().startsWith("*** Begin Patch"));
                final var patch = showDiff() && patchOutput;
                if (patch) {
                    printlnErr(prefix + "  result patch (reported output, not verified):");
                    renderDiff(
                            t.output().length() <= DiffLayout.MAX_CHARS
                                    ? List.of(redactDiff(t.output(), t.sessionId())
                                            .split("\n", -1))
                                    : List.of("(diff omitted: content limit)"),
                            "",
                            prefix);
                }
                if (!patch
                        && !(patchOutput && showToolOutput == ToolOutputVisibility.HIDDEN)
                        && (showToolOutput.shown() || failure)) {
                    final var output = call.remaining(t.output());
                    if (!output.isEmpty()) {
                        printlnErr(prefix + "  result:");
                        final var lines = output.split("\n", -1);
                        final var limit = !failure && verbosity == CliVerbosity.COMPACT ? DIFF_MAX_LINES : lines.length;
                        for (var i = 0; i < Math.min(limit, lines.length); i++) {
                            printlnErr(prefix + "    " + lines[i]);
                        }
                        if (lines.length > limit) {
                            printlnErr(prefix + "    ... " + (lines.length - limit) + " more lines");
                        }
                    }
                }
            } finally {
                renderLock.unlock();
            }
        } finally {
            toolCalls.remove(call);
        }
    }

    /**
     * Colored, current-state rendering of the todo list: green check for
     * completed, yellow arrow for in-progress, dim dot for pending, plus a
     * done/total progress line.
     */
    public List<String> renderTodos(final List<TodoItem> todos) {
        final var out = new ArrayList<String>();
        if (todos.isEmpty()) {
            out.add(MarkdownColorizer.status("(no todos)"));
            return out;
        }
        var done = 0;
        for (final var item : todos) {
            switch (item.status() == null ? "" : item.status()) {
                case "in_progress" -> out.add(MarkdownColorizer.warning(symbols.todoInProgress() + item.content()));
                case "completed" -> {
                    out.add(MarkdownColorizer.diffAdd(symbols.todoCompleted() + item.content()));
                    done++;
                }
                case "cancelled" -> out.add(MarkdownColorizer.dim(symbols.todoCancelled() + item.content()));
                default -> out.add(MarkdownColorizer.dim(symbols.todoPending() + item.content()));
            }
        }
        out.add(MarkdownColorizer.status(done + "/" + todos.size() + " done"));
        return out;
    }

    /**
     * Whether a spinner is currently armed, read under {@link #spinnerLock}
     * so callers observe a consistent lifecycle state.
     */
    boolean spinnerRunning() {
        spinnerLock.lock();
        try {
            return spinner != null;
        } finally {
            spinnerLock.unlock();
        }
    }

    /**
     * Starts the spinner when none is running; a thinking stream can call this
     * once per token, so reusing the active spinner avoids leaking one thread
     * (and one elapsed timer) per delta. Atomic with {@link #stopSpinner()}
     * and the {@code turnActive} flips so a spinner can never be armed after
     * the turn ended.
     */
    private void beginSpinner(final String label) {
        if (!tty) {
            return;
        }
        spinnerLock.lock();
        try {
            if (!turnActive || spinner != null) {
                return;
            }
            spinner = new Spinner(symbols, promptOutput, tty, renderLock);
            spinner.start(label);
        } finally {
            spinnerLock.unlock();
        }
    }

    private void stopSpinner() {
        final Spinner current;
        spinnerLock.lock();
        try {
            current = spinner;
            spinner = null;
        } finally {
            spinnerLock.unlock();
        }
        if (current != null) {
            // join + erase outside spinnerLock: stop() waits for the animation
            // worker, whose frames need renderLock - never nest the two locks
            current.stop();
        }
    }

    /**
     * Thoughts stream continuously without a trailing newline; before printing a
     * discrete line on stderr, terminate the open thought line so it is not glued
     * to the following tool/status line.
     */
    private void ensureStderrNewline() {
        if (stderrNeedsNewline) {
            printlnErr("");
            stderrNeedsNewline = false;
            stderrLineOwner = null;
        }
    }

    /** Closes the current reasoning round: the next delta opens a fresh 'thinking...' header. */
    private void closeThinkingRound() {
        if (thinkingRoundOpen) {
            renderThinking(true);
            ensureStderrNewline();
        }
        thinkingText.setLength(0);
        thinkingEmitted = 0;
        thinkingCode = new MarkdownFence.Classifier();
        thinkingRoundOpen = false;
        thinkingRoundOwner = null;
    }

    private void renderThinking(final boolean flush) {
        var newline = thinkingText.indexOf("\n");
        while (newline >= 0) {
            final var line = thinkingText.substring(0, newline);
            final var protectedLine = thinkingCode.classify(line, 0, line.length());
            emitThinking(protectedLine ? line : compression.withholdDanglingPrefix(line));
            printErr(MarkdownColorizer.thinking("\n"));
            stderrNeedsNewline = false;
            thinkingText.delete(0, newline + 1);
            thinkingEmitted = 0;
            newline = thinkingText.indexOf("\n");
        }
        final var raw = thinkingText.toString();
        // Outside code, defer an ambiguous @ suffix until its logical line is complete.
        // Retain only the current line, never a growing copy of the whole reasoning round.
        final var at = raw.indexOf('@');
        final var source = !flush && at >= 0 ? "" : raw;
        emitThinking(thinkingCode.inCode() ? source : compression.withholdDanglingPrefix(source));
    }

    private void emitThinking(final String view) {
        if (view.length() > thinkingEmitted) {
            final var addition = view.substring(thinkingEmitted);
            printErr(MarkdownColorizer.thinking((thinkingEmitted == 0 ? thinkingPrefix : "") + addition));
            stderrNeedsNewline = !addition.endsWith("\n");
            stderrLineOwner = thinkingRoundOwner;
            thinkingEmitted = view.length();
        }
    }

    /** Renders a blank line on stdout through the prompt sink. */
    private void println() {
        print("\n");
    }

    /**
     * Renders on stdout through the single {@link PromptOutput} write path: the
     * sink itself - not this class - decides between a raw write and a
     * {@code printAbove} plus prompt repaint, atomically with the read
     * lifecycle. Choosing here (a former {@code if (output.active()) ... else
     * raw} split) is a check-then-act race: a late sub-agent, the async memory
     * extraction or the turn cleanup could land on the row jline had just
     * painted, leaving the prompt invisible until the next keystroke.
     */
    private void print(final String text) {
        promptOutput.print(text);
    }

    /** Renders on stderr through the single {@link PromptOutput} write path. */
    private void printErr(final String text) {
        promptOutput.printErr(text);
    }

    /** Renders a line on stderr through the single {@link PromptOutput} write path. */
    private void printlnErr(final String text) {
        promptOutput.printErr(text + "\n");
    }
}
