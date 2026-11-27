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

import dev.tamboui.layout.Rect;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.spinner.SpinnerState;
import dev.tamboui.widgets.spinner.SpinnerStyle;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import io.yupiik.ia.agent.core.shell.InterruptCoordinator;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.shell.slash.CompletionEngine;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Mutable per-tab UI state of the TUI command. */
final class UiState {
    /**
     * An armed busy wait phase with the spinner style to render and the wall-clock time it
     * was armed ({@code -1} before the first {@link #waiting} call). The armed time lets the
     * busy label advance "thinking... (Ns)" and friends without depending on the transcript
     * status string (which other event paths may rewrite mid-turn, see {@code busyIndicator}).
     */
    record Wait(String message, SpinnerStyle style, long armedAtMillis) {}

    /** Full-screen expandable view of one captured tool output (Ctrl+O). */
    record ToolOverlay(long ordinal, ToolOutputStore.Entry entry, int scroll, boolean raw) {
        ToolOverlay(final long ordinal, final ToolOutputStore.Entry entry, final int scroll) {
            this(ordinal, entry, scroll, false);
        }

        /** Replaces the entry of a focused overlay, keeping the scroll offset (live running-tool output). */
        ToolOverlay withEntry(final ToolOutputStore.Entry fresh) {
            return new ToolOverlay(ordinal, fresh, scroll, raw);
        }
    }

    /**
     * Persisted detail of the last error (message + optional stack trace) for the Alt+E modal.
     *
     * @param transient_ true when the error comes from a recoverable, self-healing notice (eg. a
     *                   mid-stream retry): such a banner is auto-cleared once the next request
     *                   actually succeeds. Persistent errors (ERROR notices, agent turn failures,
     *                   SEVERE logs) set it to false and stay until dismissed or the next submit.
     */
    record ErrorDetail(String message, String stack, boolean transient_) {}

    /** Screen hit-area of a rendered tool card header (click to collapse/expand, Alt+Up/Down to navigate). */
    record ToolAnchor(long ordinal, Rect header, int row, boolean runMember) {}

    /** Screen hit-area of a tool run header (click to collapse/expand the run). */
    record RunHeader(long id, Rect header, int row) {}

    /** Transcript row of a truncated tool card "click to expand" footer (click opens the full output). */
    record FooterLink(long ordinal, int row) {}

    /** A tool card position in the flat transcript row space (row = top line of the card). */
    record ToolTop(long ordinal, int row, boolean runMember, boolean bordered) {}

    final TuiTranscriptModel model;
    final TuiCommand.ContentLayout contentLayout = new TuiCommand.ContentLayout();
    final Clock clock;
    final TextAreaState input = new TextAreaState();
    volatile String sessionId = "";
    /** Per-session services (loop, permissions, todos, usage...) created when the tab opens. */
    volatile SessionRuntime runtime;

    volatile String title;
    volatile Wait wait;
    volatile String activeAgent = "";
    volatile boolean showThinking;
    volatile DiffViewMode diffViewMode = DiffViewMode.AUTO;
    volatile ToolOutputVisibility showToolActions = ToolOutputVisibility.COLLAPSED;
    volatile boolean showTodos;
    volatile boolean showTodosCollapsed;
    volatile boolean interrupted;

    /** Last URL surfaced via the frontend (e.g. a Codex login); opened on-demand by Alt+O. */
    volatile String lastExternalUrl;

    /** Whether the last turn completed: drives the tab-bar done icon until a new turn starts. */
    volatile boolean turnCompleted;

    volatile long lastRenderTick;
    /**
     * Pending background mutation awaiting its tick paint: set by {@link #runLater(UiState, Runnable)}
     * before the action runs, cleared at the start of {@link #render}. Render-thread only.
     */
    volatile boolean dirty;

    volatile boolean quitArmed;
    volatile CompletableFuture<Boolean> inFlight;
    volatile boolean historyActive;
    volatile boolean inputRoutingPending;
    volatile String historyDraft = "";
    /**
     * Pending approval dialogs (head = currently displayed), FIFO. Both the main agent
     * turn and a /btw side answer can need the user at the same time: each is enqueued
     * by its gate invocation and resolved one after the other, so a late approval never
     * steals the visible dialog of an earlier one.
     */
    final Deque<TuiCommand.Approval> approvals = new ConcurrentLinkedDeque<>();

    volatile TuiCommand.FeedbackPrompt feedbackPrompt;
    final Deque<TuiCommand.FeedbackPrompt> feedbackQueue = new ArrayDeque<>();
    volatile TuiCommand.PickerPrompt picker;
    volatile TuiCommand.PendingEditPlan editPlan;
    volatile CompletionEngine.Suggestion completion;
    volatile int completionIndex;
    volatile int completionCursor = -1;
    volatile boolean completionNavigated;
    /** Screen hit-area of the completion popup (full box) as drawn last frame; null when no popup. */
    volatile Rect completionRect;
    /**
     * Set when the popup is dismissed explicitly (Esc, Enter accepting a candidate, Tab, a
     * cursor move) so the asynchronous completion warm-up refresh cannot reopen it right after
     * the user closed it; cleared by {@link #liveComplete(UiState)} on the next text edit,
     * which legitimately re-arms live completion.
     */
    volatile boolean completionDismissed;

    final SelectionState selection = new SelectionState();
    volatile String[][] screenGrid = new String[0][0];
    /** The frozen frame a selection copies from, published once per render tick. */
    volatile SelectionState.SelectionFrame selectionFrame;
    /** Per-screen-row copy kinds ({@code HARD} when unknown), published by the transcript render. */
    volatile SelectionState.RowKind[] rowKinds = new SelectionState.RowKind[0];
    /** Copyable chat content columns (role bars and walls excluded), or -1 when unknown. */
    volatile int chatContentLeft = -1;

    volatile int chatContentRight = -1;
    volatile Rect copyZone;
    volatile Rect inputZone;
    /**
     * Visual-row scroll anchor of the prompt input. In follow mode (the default,
     * {@link #inputUserScrolled} is {@code false}) it is recomputed each frame to
     * keep the caret row visible; when the user scrolls the input manually (mouse
     * wheel over a long prompt) it holds the explicit offset and
     * {@code inputUserScrolled} latches so the renderer stops auto-following until
     * the next keystroke re-arms it.
     */
    volatile int inputScroll;
    /** Whether the user scrolled the prompt input manually (mouse wheel). */
    volatile boolean inputUserScrolled;
    /**
     * Content regions of the last rendered frame that a selection may be
     * confined to, in draw order (bottom to top). Read by the event thread
     * to resolve a mouse press, published by the render thread each frame.
     */
    volatile List<Rect> paneZones = List.of();
    /**
     * The copyable interior regions of the boxed chunks drawn last frame, published by
     * the render thread. {@link SelectionState#copy(String[][], List)} uses them to strip
     * box walls and top/bottom rule rows from a selection without quoting frame glyphs,
     * so every bordered widget (code blocks, tool cards, tool runs) copies clean.
     */
    volatile List<SelectionState.CopyBox> copyBoxes = List.of();

    final SpinnerState spinnerState = new SpinnerState();
    volatile String lastTodosRender = "";
    volatile TuiCommand.Toast toast;
    volatile long toastShownAt;
    volatile TuiRunner runner;
    final Deque<String> pendingPrompts = new ConcurrentLinkedDeque<>();

    /**
     * Startup prompts ({@code --initial-prompt}) not yet submitted: each one waits for the
     * previous turn to end so they run one after the other, one turn each, instead of being
     * merged into a running turn like a type-ahead prompt.
     */
    final Deque<String> initialPrompts = new ConcurrentLinkedDeque<>();

    volatile String errorMessage;
    volatile ErrorDetail errorDetail;
    volatile boolean errorOpen;
    volatile int errorScroll;
    volatile String notification;
    /** Last nonempty informational message, retained after the status strip expires. */
    volatile String lastNotification;
    /** Snapshot shown in the notification modal, unaffected by subsequent notices. */
    volatile String notificationDetail;

    volatile int notificationScroll;

    /** Millis when {@link #notification} was set; {@code 0} when none is pending. */
    volatile long notificationShownAt;

    /**
     * True while a compaction lifecycle is in flight (a "Compaction in progress..." notice was
     * seen and no "Compacted:"/"Compaction gate:" result yet): pins the compacting indicator in
     * the ⓘ notification slot, bypassing the TTL, so a long summarizer pass stays visible.
     */
    volatile boolean compactionActive;

    /**
     * Full title of the tab the mouse currently hovers (tab bar tooltip), or
     * {@code null} when the pointer is not over the bar. Set by the event thread
     * on MOVE, cleared on leave/click/scroll; rendered by the render thread as a
     * single-line floating panel under the tab strip.
     */
    volatile String hoverTitle;

    volatile ToolOverlay toolOverlay;

    /**
     * Screen rect of the tool output modal's footer row (the ellipsized raw-args
     * line), recorded by the render thread so the event thread can hit-test a
     * MOVE and surface the full command/args in a hover tooltip. {@code null}
     * while no tool overlay is drawn.
     */
    volatile Rect toolOverlayFooter;

    /**
     * Full command or raw args text the pointer currently hovers in the tool
     * output modal footer, or {@code null}. Rendered by the render thread as a
     * floating chip over the modal; cleared on scroll / press / drag / release.
     */
    volatile String hoverToolCmd;

    /**
     * Per-window quota gauge hit-areas (sidebar), recorded by the render thread each frame
     * so the event thread can hit-test a MOVE and surface the reset time in a hover tooltip.
     * Populated only while the quota gauges are actually drawn ({@code showQuota}); cleared
     * to an empty list on every frame where quota is hidden, so switching to a no-quota
     * provider removes the hit-areas on the next repaint and the tooltip never hovers a bar
     * that is not shown.
     */
    volatile List<QuotaHoverArea> quotaHoverAreas = List.of();

    /**
     * Reset-time caption ("Resets in ...", or "now") of the quota gauge the pointer currently
     * hovers in the sidebar, or {@code null}. Rendered by the render thread as a floating chip
     * below the gauge row; cleared on scroll / press / drag / leave / provider switch.
     */
    volatile String hoverQuotaReset;

    /**
     * Screen rect of the quota gauge the pointer currently hovers (used to anchor the
     * reset tooltip chip below it), or {@code null} when not hovering a quota gauge.
     */
    volatile Rect hoverQuotaRect;

    /**
     * Screen rect of the sidebar context gauge, recorded by the render thread each frame so the
     * event thread can hit-test a MOVE and surface the estimated context size in a hover tooltip.
     * Cleared whenever the gauge is not drawn, so no tooltip appears over a hidden gauge.
     */
    volatile Rect contextGaugeRect;

    /**
     * Caption ("~12.3k tok") shown in the floating chip below the context gauge the pointer
     * currently hovers, or {@code null}. Rendered by the render thread; cleared on
     * scroll / press / drag / leave.
     */
    volatile String hoverContextSize;

    /**
     * Ordinal of the tool card whose header strip (border + title row) the pointer
     * currently hovers, or {@code -1}. Rendered by the render thread as a reversed
     * header row so the click target is visible; cleared on scroll / press.
     */
    volatile long hoverToolCard = -1;

    record QuotaHoverArea(Rect rect, long resetAtEpochSeconds) {}

    volatile BtwOverlay btw;

    volatile ExecutingToolsOverlay executingTools;

    record BtwOverlay(
            long ordinal,
            String question,
            String answer,
            String error,
            boolean running,
            boolean retryable,
            int scroll,
            AtomicBoolean cancelled) {}

    /** The /executing-tools modal: a read-only snapshot of the running tools, scrollable. */
    record ExecutingToolsOverlay(String content, int scroll) {}

    final TranscriptSearch search = new TranscriptSearch();
    volatile boolean searchOpen;
    volatile List<String> flatRows = List.of();
    volatile int maxViewOffset;
    volatile int pendingJumpRow = -1;
    final Set<Long> collapsedTools = new HashSet<>();
    /**
     * Per-run collapse overrides keyed by the run's first tool ordinal; a run with no
     * entry follows the current mode default (SHOWN open, COLLAPSED collapsed). Cleared
     * on Ctrl+L together with {@link #collapsedTools}.
     */
    final Map<Long, Boolean> runCollapsed = new HashMap<>();

    volatile List<ToolAnchor> toolAnchors = List.of();
    volatile List<ToolAnchor> toolFooterLinks = List.of();
    /** Rendered run header hit-areas (click to toggle the run), populated each frame. */
    volatile List<RunHeader> runHeaders = List.of();
    /** Wall-clock millis when the current live thinking stream started, -1 when idle. */
    volatile long thinkingStartedAtMillis = -1;

    final List<Rect> queueRects = new ArrayList<>();
    volatile boolean shortcutsOpen;
    volatile int shortcutsScroll;
    volatile Rect shortcutRect;
    /**
     * Screen rect of the leading ✖ glyph in the error banner (the click-to-dismiss target),
     * recorded by the render thread so the event thread can hit-test a press and clear the
     * error. {@code null} while no error banner is drawn.
     */
    volatile Rect errorCloseRect;
    /** Subagents overlay: null when closed, the detail id when showing one subagent, empty for the list. */
    volatile String subagentsOverlay = null;

    volatile int subagentsScroll;
    volatile String lastCodeBlock;
    /** Pending tool args per name (FIFO), mirroring the core transcript's running tools. */
    final Map<String, ArrayDeque<String>> pendingToolArgs = new HashMap<>();
    /**
     * Ordinals of the started-not-finished tools per name (FIFO), aligned with
     * {@link #pendingToolArgs}: the running block parsed by {@link ToolCard} carries
     * the (#ordinal) emitted by the core transcript, and the renderer falls back to
     * this queue when the header has no suffix yet, so the running card is always
     * anchored (clickable running-tool overlay) even before the first marked header.
     */
    final Map<String, ArrayDeque<Long>> pendingToolOrdinals = new HashMap<>();

    volatile long focusedToolOrdinal = -1;
    volatile int toolNavIndex = -1;
    volatile boolean yankArmed;
    volatile int lastYankLen;

    void pushToast(final String message, final ToastLevel level) {
        toast = new TuiCommand.Toast(message, level);
        toastShownAt = clock.millis();
    }

    void clearToast() {
        toast = null;
    }

    UiState(final ThinkTagSplitter thinkTags, final Clock clock) {
        this(thinkTags, clock, null);
    }

    UiState(
            final ThinkTagSplitter thinkTags,
            final Clock clock,
            final TuiConfiguration.ToolOutputConfiguration toolOutput) {
        this.model = new TuiTranscriptModel(thinkTags);
        this.clock = clock;
        this.session = new ShellSession();
        final var limits =
                toolOutput == null ? new TuiConfiguration.ToolOutputConfiguration(128, 256 * 1024, 0) : toolOutput;
        this.toolOutputs = new ToolOutputStore(limits.maxEntries(), limits.maxOutputChars(), limits.maxTotalChars());
        this.killRing = new KillRing();
        this.undoStack = new UndoStack();
        this.interruptor = new InterruptCoordinator(clock, true);
    }

    /** Per-tab shell state: session id, agent, view flags, busy and metrics. */
    ShellSession session;

    /** Captured tool outputs of this tab (Ctrl+O overlay, copy from tool cards). */
    final ToolOutputStore toolOutputs;

    /** Ctrl+U/Ctrl+K last-deleted text ring. */
    final KillRing killRing;

    /** Undo/redo stack of the input text. */
    final UndoStack undoStack;

    /** Ctrl+C press window: interrupt vs quit. */
    final InterruptCoordinator interruptor;

    /** Monotonic ordinal for this tab's /btw overlays. */
    final AtomicLong btwOrdinal = new AtomicLong();

    /** Pending readLine future, only set while the input is not owned by a turn. */
    volatile CompletableFuture<Optional<String>> pendingLine;

    /** Per-session services of this tab; wired once the runtime is created at startup. */
    SessionRuntime runtime() {
        return runtime;
    }

    /** Wires the per-session services of this tab. */
    void runtime(final SessionRuntime runtime) {
        this.runtime = runtime;
    }
}
