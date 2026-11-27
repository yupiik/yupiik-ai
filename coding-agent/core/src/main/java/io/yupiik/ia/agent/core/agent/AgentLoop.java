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

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent.CompactionPass;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ContextLimit;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.PlateauFeedbackMode;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.CompactionResult;
import io.yupiik.ia.agent.core.context.ContextCompression;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextMeasurement;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.TokenEstimator;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.HookResult;
import io.yupiik.ia.agent.core.events.ModelSwitchedEvent;
import io.yupiik.ia.agent.core.events.ModelSwitchingEvent;
import io.yupiik.ia.agent.core.events.SessionEndedEvent;
import io.yupiik.ia.agent.core.events.hooks.EventContextBuilder;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.ToolCallPairingRepair;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.Sandbox;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.usage.QuotaSnapshotStore;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AgentLoop {
    static final Logger LOGGER = Logger.getLogger(AgentLoop.class.getName());

    /** P2: after this many consecutive tool rounds with no new information, the turn is stopped. */
    static final int NO_PROGRESS_ROUNDS_LIMIT = 5;

    /** Exploration-plateau markers used in the per-round coverage set (see {@link #appendToolResult}). */
    static final String WRITE_MARKER = "\u0000write";

    static final String INELIGIBLE_EXPLORATION_MARKER = "\u0000ineligible-exploration";

    static final String REDUNDANT_MARKER = "\u0000seen:";

    /**
     * One-shot nudge injected before the exploration-plateau hard stop (plateauFeedback nudge_1 /
     * nudge_2): the model keeps re-reading already-seen files/results with novel signatures, so it
     * is told to change strategy instead of silently losing the turn at the limit.
     */
    static final String PLATEAU_NUDGE_PROMPT =
            "Your recent tool rounds re-read only files/results already present in this conversation."
                    + " Stop re-reading: use the information you already collected, batch any remaining"
                    + " independent reads or searches into a single reply, and if you are stuck state"
                    + " exactly what information is still missing instead of probing the same targets.";

    /** One-shot nudge sent when the doom-loop guard refused a repeated state-changing/bash call. */
    static final String DOOM_LOOP_NUDGE_PROMPT =
            "You just repeated the same bash/state-changing call with the same arguments, and it returned"
                    + " the same already-known result again. Do not re-issue it: it adds no information."
                    + " Change approach - use a different command or tool, narrow or widen the query, or"
                    + " state exactly what is still missing instead of repeating the call.";

    /**
     * One-shot nudge injected when the model has spent llm.explorationNudgeRounds consecutive
     * informative read-only rounds exploring without answering. Complements the plateau guard
     * (which only catches re-reading already-seen targets): a spiral of novel reads is
     * informative every round, so only a wrap-up directive stops it.
     */
    static final String EXPLORATION_NUDGE_PROMPT =
            "You have spent several consecutive rounds exploring the codebase with read-only tools."
                    + " You have enough context: stop expanding the survey now and deliver the answer."
                    + " Synthesize what you already collected; only call another tool if a single,"
                    + " decisive piece of information is strictly missing.";

    /** Nudge sent to the model after a truncated reply so it resumes instead of restarting. */
    static final String TRUNCATION_CONTINUE_PROMPT =
            "Your previous reply was cut off by the output token limit before you could finish."
                    + " Continue exactly where you stopped; do not repeat content you already produced.";

    /**
     * Nudge used from the second continuation on: repeating the same verbatim continuation hits the
     * same output budget again, so the model is asked to switch strategy instead.
     */
    static final String TRUNCATION_SPLIT_PROMPT =
            "Your previous reply was cut off by the output token limit again. Do not continue the same output verbatim,"
                    + " it will hit the limit a second time. Change strategy: break the remaining work into smaller"
                    + " steps, use tools to write files or produce results in chunks, keep prose minimal and summarize"
                    + " instead of dumping full content.";

    /**
     * Retry nudge for an empty completion (no content, no tool call) that followed a request which
     * produced reasoning tokens: the model spent its whole output budget thinking, so asking it to
     * "retry" the same context would re-derive the same thoughts and hit the same wall. The model is
     * asked to bound its reasoning (or answer without further deliberation) instead of re-thinking.
     */
    static final String EMPTY_REASONING_RETRY_PROMPT =
            "Your previous try produced no answer at all: it looks like the output budget was consumed by the"
                    + " reasoning/thinking phase. Do not re-derive the same reasoning: it will hit the same limit."
                    + " Answer directly now - keep the thinking minimal, or skip it - and use the tools to make"
                    + " concrete progress (or reply with the final answer if the work is done).";

    private final ChatClient chatClient;
    final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    final ToolCallCoordinator toolCallCoordinator;
    final ModelRoundProcessor modelRoundProcessor;
    final CodingAgentConfiguration configuration;
    final PermissionService permissionService;
    final UsageTracker usageTracker;
    final QuotaSnapshotStore quotaStore;
    final ContextManager contextManager;
    final ContextView contextView;
    final SessionStore sessionStore;
    final SystemPromptBuilder systemPromptBuilder;
    final MemoryExtractor memoryExtractor;
    final StatusReporter statusReporter;
    final Emitter emitter;
    final JsonMapper mapper;
    final StructuralCodeGraph structuralCodeGraph;
    final ProviderManager providerManager;
    final AgentProvider agentProvider;
    final AgentModelResolver agentModelResolver;
    final Clock clock;
    final PrecisionDurationMarkerProvider precisionDurationMarker;
    final TurnState turnState = new TurnState();
    private final SnapshotStore snapshotStore;
    final TodoStore todoStore;
    final NotificationService notificationService;
    final ExecutorService executor;
    final UserFeedbackService userFeedbackService;
    final ToolCallPairingRepair pairingRepair;
    final String subagentId;
    final String subagentName;
    /**
     * Interruptible sleep for the compaction gate's backoff cadence (between gate passes) and for the
     * coordination of the {@code /compact urgent} escalation probe. Production sleeps on the real
     * clock; tests substitute a virtual-time instance to exercise the wall-clock budget
     * ({@code gateMaxDurationMs}) without stalling the suite.
     */
    volatile BackoffSleeper backoffSleeper = new BackoffSleeper();
    /** Null means this is a regular agent turn; a non-null set is a subagent execution ceiling. */
    private volatile Set<String> allowedTools;

    private volatile Integer maxIterationsCap;
    /**
     * Session this loop instance belongs to. Events are tagged with it so the
     * shell registry can route each stream to the tab that owns it. A loop
     * created without a session (CLI, legacy wiring) falls back to the
     * sessionId passed to run/resume.
     */
    final String sessionId;
    /**
     * Effective session for the current turn. Equal to {@link #sessionId} for
     * per-session instances; for legacy shared instances (CLI) it is set from
     * the run argument. Event helpers tag with this value.
     */
    volatile String currentSessionId;
    /**
     * The session's resolved working/project directory, set once by
     * {@code SessionRuntimeFactory}. Required and immutable. Injected into every per-turn
     * {@link ToolContext} so file-rooted tools resolve paths against the session's
     * root. A mid-session change rebuilds the runtime rather than mutating this field.
     */
    final Path sessionWorkdir;
    /**
     * The session's sub-agent admission budget (the {@code yca.subagents.maxConcurrent} per-session
     * cap), set once by {@code SessionRuntimeFactory} like {@link #sessionWorkdir}. Null for loops
     * that cannot spawn children (sub-agent child loops, standalone/btw contexts). Threaded into
     * every per-turn {@link ToolContext} so {@code spawn_subagent} can enforce the per-session limit.
     */
    volatile SubagentBudget subagentBudget;
    /** Set when the loop/session is closed so {@link #close()} fires the SessionEnded hook only once. */
    volatile boolean closed;

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    /** True when the last persistContext failed: the on-disk session lags the in-memory one until the next retry. */
    volatile boolean persistDirty;
    /** Effective agent of the current turn (set at {@code doRun} start), used for identity capture. */
    volatile String currentAgentName;
    /**
     * Per-session model selection (model/variant/sampling). Explicitly passed to every
     * {@link ChatClient} call made by this loop's turns so concurrent tabs never share
     * a selection. Plain holder - no scope, no thread-local.
     */
    volatile ChatSettings chatSettings = ChatSettings.EMPTY;

    /**
     * Profile-level model tuning installed by {@code SubagentRunner} when it launches a subagent on a
     * dedicated loop, applied to the wire request alongside the run settings. {@code skipConfiguredAgentModel}
     * tells {@code doRun} not to let the profile's own model replace the already-resolved subagent
     * selection (the profile only contributes its tuning, not its model, on the isolated child loop).
     */
    volatile ModelVariantConfiguration agentModelTuning;

    volatile boolean skipConfiguredAgentModel;
    /**
     * Per-turn pause listener surfacing provider throttles and retry backoffs to the owning
     * tab. It is handed to each {@link ChatClient} call instead of being installed globally:
     * without it the UI would only show a spinner while the resilience layer can silently
     * wait up to minutes between attempts.
     */
    volatile Consumer<PauseEvent> pauseListener;

    final ToolCallExecution toolExecution;

    final LoopGuards loopGuards;

    final TodoContinuation todoContinuation;

    private final HistoryPruningStrategies historyPruning;
    final ContextGuidance contextGuidance;
    final SessionUsageReporter usageReporter;

    final TurnNotificationReporter turnNotificationReporter;

    private final AgentTurnExecutor turnExecutor;

    /**
     * Immutable snapshot of the last request this loop handed to the provider: what the
     * model really receives, which is not what the session store keeps (the leading
     * system message and the volatile session-state tail are never persisted, and the
     * list is rewritten by prune/compaction before every call). Captured for
     * {@code /context llm-payload}. {@code protected} so tests can seed a loop without
     * running a turn.
     *
     * @param sessionId session the request was built for
     * @param model model the request was sent with
     * @param messages the exact message list sent (system prompt included)
     * @param tools the wire tool schemas sent
     * @param toolChoice the tool choice sent
     * @param toolsTokens estimated tokens of {@code tools}, used to report the total footprint
     */
    public record LlmPayload(
            String sessionId,
            String model,
            List<Message> messages,
            List<Tool> tools,
            String toolChoice,
            int toolsTokens) {}

    /** Last captured payload, {@code null} until this session sent its first request. */
    protected volatile LlmPayload lastLlmPayload;

    /**
     * Whether the last completed LLM request produced reasoning tokens (a {@code ReasoningDelta}
     * was observed before its {@code End}): an empty or truncated completion after reasoning means
     * the model spent its whole output budget thinking, and re-asking the same context would only
     * re-derive the same reasoning. Reset before each request, flipped by {@link #dispatch}, read
     * by the truncation/empty-completion branches.
     */

    /**
     * Streaming splitter for models that inline their reasoning in the text channel (GLM/Qwen /
     * deepseek-flash served with {@code think} tags inside {@code content} rather than a dedicated
     * {@code reasoning_content}). Every wire {@code TextDelta} is fed through it and emitted as
     * pre-classified {@link AgentEvent.ThinkingDelta} / {@link AgentEvent.TextDelta} events, so ALL
     * frontends (TUI, web SSE, subagent transcripts) receive the same classification from this one
     * shared parser - the web frontend never has to re-parse. Reset per stream attempt (a retry is a
     * fresh generation). Null-safe no-op for providers that stream reasoning through the dedicated
     * channel (a clean {@code content} passes straight through as visible).
     */
    final StreamReasoningState streamReasoning = new StreamReasoningState();

    /**
     * Reserved {@code others} key on a persisted assistant {@link Message} carrying the reasoning
     * text produced alongside the answer (dedicated channel or inline think-tags). Stored only for
     * replay rendering ({@code WebReplay} reconstructs {@code thinking-delta} events from it); never
     * sent to the LLM - {@code others} is stripped at every wire boundary.
     */
    static final String THINKING_KEY = "thinking";

    /**
     * Reserved {@code others} key on a persisted assistant {@link Message} carrying the opaque reasoning
     * items ({@code id}, {@code encrypted_content}, {@code summary}) for multi-turn reasoning replay.
     * The blob is decrypted only server-side; it lives here so it survives the session store round-trip
     * and is re-emitted as a {@code type:reasoning} input item on the next request. Never sent to the
     * LLM as visible content, and never displayed - {@code others} is stripped at every wire boundary.
     */
    static final String REASONING_ITEMS_KEY = "reasoningItems";

    /** Resets the inline-reasoning splitter for a fresh stream attempt (a retry is a new generation). */
    void resetInlineSplitter() {
        streamReasoning.reset();
    }

    /**
     * Feeds a wire {@code TextDelta} through the inline-reasoning splitter and emits the classified
     * segments as separate {@link AgentEvent.ThinkingDelta} / {@link AgentEvent.TextDelta} events.
     * When the model streams reasoning through a dedicated channel the splitter sees a clean
     * {@code content} and emits a single visible {@code TextDelta} - a no-op aside from the one
     * extra parser pass.
     */
    void dispatchTextDelta(final String text) {
        emitInlineSegments(streamReasoning.feed(text));
    }

    /** Flushes the bounded hold-tail of the inline splitter at stream end so no answer tail is lost. */
    void dispatchInlineSplitterFlush() {
        emitInlineSegments(streamReasoning.flush());
    }

    /**
     * Emits the classified segments from the inline-reasoning splitter as {@code ThinkingDelta} /
     * {@code TextDelta} events, accumulating thinking text. A null element (a defensive guard against an
     * unexpected splitter edge case) is skipped rather than NPE-ing mid-stream, so it can never be
     * misdiagnosed as a transport failure and retried pointlessly.
     */
    void emitInlineSegments(final List<ThinkTagSplitter.Segment> segments) {
        for (final var segment : segments) {
            if (segment == null) {
                continue;
            }
            if (segment.channel() == ThinkTagSplitter.Channel.THINKING) {
                streamReasoning.appendThinking(segment.text());
                emit(thinkingDelta(segment.text()));
            } else {
                emit(textDelta(segment.text()));
            }
        }
    }

    /**
     * Whether the turn just ended on a real, non-empty model reply (a {@code TurnDone} with
     * content was emitted). Set by {@link #emitTurnMetrics} when content is non-null and
     * non-blank; every other terminal path (cancel, interrupt, hard stop, empty completion,
     * error) leaves it false. Gates the end-of-turn memory extraction in {@code doRun}'s
     * finally: only a turn the user actually saw an answer from is worth mining.
     */
    volatile boolean lastTurnEndedWithContent;

    /**
     * Per-session count of completed user turns (incremented in {@code doRun}'s finally when
     * the turn had a real user prompt). Drives the {@code memory.extractEveryTurns} cadence:
     * extraction runs when ({@code turnCount % extractEveryTurns == 0} with
     * {@code extractEveryTurns > 0}) or when a todo completed this turn. Resumed/initial turns
     * without a user message do not advance the counter.
     */
    int turnCount;

    /**
     * Snapshot of the finished-todo set (content of items in completed/cancelled status) at the
     * end of the previous user turn, used to detect that at least one todo transitioned into
     * finished during the current turn (membership diff - mid-turn todo additions cannot fake a
     * completion). Reset to empty on a new session/tab.
     */
    Set<String> lastFinishedTodos = Set.of();

    /**
     * Doom-loop breaker state for the current turn: counts repeated tool calls (normalized args)
     * and remembers the last output per signature for the no-progress termination. Created fresh
     * per turn, mutated on the loop thread only.
     */
    ToolRepeats repeats = new ToolRepeats(0);

    /** Consecutive tool rounds with no new information (reset on any informative round), see P2. */
    int noProgressRounds;

    /**
     * Exploration-plateau breaker state for the current turn: tracks which normalized paths (and
     * bash command fingerprints) the turn already covered with read-only tools, so a model that
     * keeps re-reading the same files/results with novel-but-redundant calls can be stopped instead
     * of burning the iteration window (see the plateauRounds cascade).
     */
    final Set<String> seenFiles = ConcurrentHashMap.newKeySet();

    final ReadCoverage readCoverage = new ReadCoverage();

    /** Consecutive read-only rounds re-covering only already-seen files (reset on any informative round or write). */
    int plateauRounds;

    /** Whether the plateau nudge (plateauFeedback nudge_1/nudge_2) was already injected this turn. */
    boolean plateauNudged;

    /** Whether the one-shot exploration nudge (EXPLORATION_NUDGE_PROMPT) was already injected this turn. */
    boolean explorationNudged;

    /** Consecutive informative read-only rounds this turn without an answer (drives the exploration nudge). */
    int explorationRounds;

    /** Whether the one-shot doom-loop nudge (DOOM_LOOP_NUDGE_PROMPT) was already injected this turn. */
    boolean doomLoopNudged;

    /**
     * Compression-nudge cadence: real LLM request-round counter. Advanced only on actual request
     * rounds inside the run loop (never by a {@code /context} preview), so preview rendering of the
     * volatile tail stays side-effect free and the same decision a real round makes.
     */

    /** The real round of the last hard compression nudge actually placed on the wire. */

    /** Real round before which compression nudges (soft and hard) are suppressed; e.g. right after
     * a successful {@code compress} so the model is not immediately re-hammered. */

    /** Last observed {@code savedTokens} of the compression view, to detect a fresh compress that
     * arms {@link #compressionNudgeCooldownUntil}. {@code -1} means "not yet sampled" so the first
     * round with no saved tokens does not spuriously arm a cooldown. */

    /**
     * The last {@code estimatedContextTokens} a {@link AgentEvent.UsageUpdated} carried, so a
     * quota-only push at provider switch or {@code /usage} preserves the live context gauge instead
     * of resetting it to zero (the push has no message list to recompute context from).
     */

    /**
     * The last {@code contextPercent} a {@link AgentEvent.UsageUpdated} carried (see
     * {@link #lastUsageContextTokens}).
     */

    /**
     * Names of the tools the registry serves in build mode (the unfiltered {@code toWireTools()} list),
     * frozen once per {@code loop}. Plan mode hides EDIT tools from that list; a call to an EDIT tool that
     * IS served (present here) is plan-hidden and rejected at dispatch instead of reaching the approval gate.
     * Tools never served (empty/bare test registries) are not treated as plan-hidden, so they keep executing.
     */
    volatile Set<String> servedToolNames;

    private final Sandbox sandbox;

    /** Borrowed session sandbox. Descendants share approvals and revocations conservatively. */
    public Sandbox sandbox() {
        return sandbox;
    }

    public AgentLoop(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final PermissionService permissionService,
            final UsageTracker usageTracker,
            final QuotaSnapshotStore quotaStore,
            final ContextManager contextManager,
            final ContextView contextView,
            final SessionStore sessionStore,
            final SystemPromptBuilder systemPromptBuilder,
            final MemoryExtractor memoryExtractor,
            final StatusReporter statusReporter,
            final Emitter emitter,
            final JsonMapper mapper,
            final StructuralCodeGraph structuralCodeGraph,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final Clock clock,
            final SnapshotStore snapshotStore,
            final TodoStore todoStore,
            final ExecutorService executor,
            final NotificationService notificationService,
            final Path workdir,
            final Sandbox sandbox) {
        this(
                chatClient,
                toolRegistry,
                toolExecutionService,
                configuration,
                permissionService,
                usageTracker,
                quotaStore,
                contextManager,
                contextView,
                sessionStore,
                systemPromptBuilder,
                memoryExtractor,
                statusReporter,
                emitter,
                mapper,
                structuralCodeGraph,
                providerManager,
                agentProvider,
                clock,
                snapshotStore,
                todoStore,
                executor,
                null,
                null,
                null,
                null,
                new PrecisionDurationMarkerProvider(),
                null,
                notificationService,
                null,
                null,
                null,
                workdir,
                sandbox);
    }

    AgentLoop(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final PermissionService permissionService,
            final UsageTracker usageTracker,
            final QuotaSnapshotStore quotaStore,
            final ContextManager contextManager,
            final ContextView contextView,
            final SessionStore sessionStore,
            final SystemPromptBuilder systemPromptBuilder,
            final MemoryExtractor memoryExtractor,
            final StatusReporter statusReporter,
            final Emitter emitter,
            final JsonMapper mapper,
            final StructuralCodeGraph structuralCodeGraph,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final Clock clock,
            final SnapshotStore snapshotStore,
            final TodoStore todoStore,
            final ExecutorService executor,
            final String subagentId,
            final String subagentName,
            final NotificationService notificationService,
            final Path workdir,
            final Sandbox sandbox) {
        this(
                chatClient,
                toolRegistry,
                toolExecutionService,
                configuration,
                permissionService,
                usageTracker,
                quotaStore,
                contextManager,
                contextView,
                sessionStore,
                systemPromptBuilder,
                memoryExtractor,
                statusReporter,
                emitter,
                mapper,
                structuralCodeGraph,
                providerManager,
                agentProvider,
                clock,
                snapshotStore,
                todoStore,
                executor,
                null,
                subagentId,
                subagentName,
                null,
                new PrecisionDurationMarkerProvider(),
                null,
                notificationService,
                null,
                null,
                null,
                workdir,
                sandbox);
    }

    protected AgentLoop(final Path workdir, final Sandbox sandbox) {
        this(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, workdir,
                sandbox);
    }

    /**
     * Full wiring for a session (tab) instance: the per-session services
     * ({@code permissionService}, {@code usageTracker}, {@code statusReporter},
     * {@code todoStore}, {@code userFeedbackService}) belong to exactly one
     * session and the loop tags every event it emits with {@code sessionId} so
     * the shell registry routes the stream to the owning tab.
     */
    public AgentLoop(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final PermissionService permissionService,
            final UsageTracker usageTracker,
            final QuotaSnapshotStore quotaStore,
            final ContextManager contextManager,
            final ContextView contextView,
            final SessionStore sessionStore,
            final SystemPromptBuilder systemPromptBuilder,
            final MemoryExtractor memoryExtractor,
            final StatusReporter statusReporter,
            final Emitter emitter,
            final JsonMapper mapper,
            final StructuralCodeGraph structuralCodeGraph,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final Clock clock,
            final SnapshotStore snapshotStore,
            final TodoStore todoStore,
            final ExecutorService executor,
            final String sessionId,
            final String subagentId,
            final String subagentName,
            final UserFeedbackService userFeedbackService,
            final PrecisionDurationMarkerProvider precisionDurationMarker,
            final ToolCallPairingRepair pairingRepair,
            final NotificationService notificationService,
            final TodoContinuation todoContinuation,
            final TurnNotificationReporter turnNotificationReporter,
            final AgentTurnExecutor turnExecutor,
            final Path workdir,
            final Sandbox sandbox) {
        this.sessionWorkdir = Objects.requireNonNull(workdir, "workdir");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.toolCallCoordinator = new ToolCallCoordinator();
        this.modelRoundProcessor = new ModelRoundProcessor(chatClient, configuration, precisionDurationMarker);
        this.configuration = configuration;
        this.permissionService = permissionService;
        this.usageTracker = usageTracker;
        this.quotaStore = quotaStore;
        this.contextManager = contextManager;
        this.contextView = contextView;
        this.sessionStore = sessionStore;
        this.systemPromptBuilder = systemPromptBuilder;
        this.memoryExtractor = memoryExtractor;
        this.statusReporter = statusReporter;
        this.emitter = emitter;
        this.mapper = mapper;
        this.historyPruning = new HistoryPruningStrategies(configuration, mapper);
        this.structuralCodeGraph = structuralCodeGraph;
        this.providerManager = providerManager;
        this.agentProvider = agentProvider;
        this.agentModelResolver = new AgentModelResolver(providerManager);
        this.clock = clock;
        this.precisionDurationMarker = precisionDurationMarker;
        this.snapshotStore = snapshotStore;
        this.todoStore = todoStore;
        this.executor = executor;
        this.sessionId = sessionId;
        this.subagentId = subagentId;
        this.subagentName = subagentName;
        this.userFeedbackService = userFeedbackService;
        this.pairingRepair = pairingRepair != null ? pairingRepair : new ToolCallPairingRepair(sessionStore, clock);
        this.notificationService = notificationService;
        this.contextGuidance = new ContextGuidance(
                configuration,
                () -> this.usageTracker,
                () -> this.contextView,
                () -> this.providerManager,
                () -> this.chatSettings);
        this.usageReporter = new SessionUsageReporter(
                usageTracker,
                quotaStore,
                emitter,
                () -> this.currentSessionId,
                this.sessionId,
                () -> this.chatSettings,
                subagentId,
                subagentName,
                sessionWorkdir);
        this.toolExecution = new ToolCallExecution(
                toolRegistry,
                toolExecutionService,
                permissionService,
                mapper,
                emitter,
                precisionDurationMarker,
                configuration,
                userFeedbackService,
                todoStore,
                statusReporter,
                usageTracker,
                contextView,
                () -> this.currentSessionId,
                () -> this.subagentId,
                () -> this.subagentName,
                () -> this.sessionWorkdir,
                () -> this.subagentBudget,
                this::effectiveAllowedTools,
                () -> this.servedToolNames,
                this::chatSettings,
                () -> this.closed,
                sandbox);
        this.todoContinuation = todoContinuation == null
                ? new TodoContinuation(
                        configuration,
                        () -> this.permissionService,
                        () -> this.todoStore,
                        sessionStore,
                        clock,
                        () -> this.currentSessionId,
                        () -> this.subagentId,
                        () -> this.subagentName,
                        sessionWorkdir)
                : todoContinuation;
        this.turnNotificationReporter = turnNotificationReporter == null
                ? new TurnNotificationReporter(
                        turnState,
                        precisionDurationMarker,
                        notificationService,
                        sessionStore,
                        () -> resolvedModel(),
                        () -> this.currentSessionId,
                        () -> this.subagentId,
                        () -> this.subagentName,
                        () -> this.lastTurnEndedWithContent,
                        value -> this.lastTurnEndedWithContent = value,
                        this::emit,
                        sessionWorkdir)
                : turnNotificationReporter;
        this.loopGuards = new LoopGuards(providerManager, configuration, () -> this.maxIterationsCap);
        this.turnExecutor = turnExecutor == null ? new AgentTurnExecutor(this) : turnExecutor;
    }

    /**
     * Installs the sleeper used by the compaction gate's backoff cadence (between gate passes).
     * Tests substitute a virtual-time instance to exercise the wall-clock budget
     * ({@code gateMaxDurationMs}) without stalling the suite; production keeps the default.
     */
    public void backoffSleeper(final BackoffSleeper value) {
        if (value != null) {
            this.backoffSleeper = value;
        }
    }

    /**
     * This loop's per-session model selection. The session runtime hands it to the slash
     * commands and the UI so {@code /model}, {@code /variant} and the sampling overrides
     * mutate only this tab.
     */
    public ChatSettings chatSettings() {
        return chatSettings;
    }

    /** Applies a new selection value (mid-turn changes take effect from the next round/tool call). */
    public void updateChatSettings(final ChatSettings value) {
        updateChatSettings(value, () -> false);
    }

    /**
     * Applies a selection only if its owner has not cancelled at post-hook commit admission.
     * The last predicate read under the lifecycle lock is the admission point. Cancellation
     * after admission does not roll back settings or their already-admitted notifications.
     * The predicate must be nonblocking and must not invoke hooks or acquire frontend locks.
     */
    public boolean updateChatSettings(final ChatSettings value, final BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        final var alt = value == null ? ChatSettings.EMPTY : value;
        final ChatSettings current;
        lifecycleLock.lock();
        try {
            if (closed || cancelled.getAsBoolean()) {
                return false;
            }
            current = chatSettings;
        } finally {
            lifecycleLock.unlock();
        }
        // the model-switch hook is emitted before the swap so a forbid vetoes the switch (the turn keeps the old model)
        if (emitter != null) {
            final var hookResult = new HookResult();
            emitter.emit(new ModelSwitchingEvent(
                    new EventContextBuilder()
                            .build(
                                    permissionService,
                                    current,
                                    currentSessionId,
                                    subagentId,
                                    subagentName,
                                    sessionWorkdir),
                    current == null || current.model() == null ? null : current.model(),
                    alt.model(),
                    alt.variant(),
                    hookResult));
            if (hookResult.forbidden()) {
                if (!closed && !cancelled.getAsBoolean()) {
                    emit(AgentEvent.Notice.warn(
                            "Model switch rejected" + (hookResult.reason() == null ? "" : ": " + hookResult.reason()),
                            currentSessionId,
                            sessionWorkdir));
                }
                return false;
            }
        }
        // Hooks can block or re-enter close(). Commit only while the session is still alive;
        // attaching the local usage selection is part of the same short state transition.
        lifecycleLock.lock();
        try {
            if (closed || cancelled.getAsBoolean()) {
                return false;
            }
            this.chatSettings = alt;
            if (usageTracker != null) {
                usageTracker.attach(alt);
            }
        } finally {
            lifecycleLock.unlock();
        }
        // Never run observer hooks or provider I/O under the lifecycle lock.
        if (emitter != null && !closed) {
            emitter.emit(new ModelSwitchedEvent(
                    new EventContextBuilder()
                            .build(permissionService, alt, currentSessionId, subagentId, subagentName, sessionWorkdir),
                    current == null || current.model() == null ? null : current.model(),
                    alt.model(),
                    alt.variant()));
        }
        // a provider switch must re-attach this session's quota snapshot immediately so the frontend
        // gauge (freshly refreshed at switch, then TTL-governed) shows the *new* provider's usage at
        // switch time instead of keeping the old provider's cached quota until the next turn, and a
        // fully-reached quota is visible. Only a provider change pushes a quota event: a model-only
        // switch reuses the already-attached snapshot and must not clobber the live context gauge.
        if (!closed && providerChanged(current, alt)) {
            if (quotaStore != null) {
                // before the first turn currentSessionId is still null: key the quota on the loop's
                // stable session id (the id doRun will set later), otherwise a switch issued as the
                // very first command would leave the session unattached and the UIs without a gauge
                usageReporter.attachQuota(alt);
            }
            pushUsageQuota();
        }
        return true;
    }

    /** True when the resolved provider id differs between two selections (blank = process default). */
    private static boolean providerChanged(final ChatSettings before, final ChatSettings after) {
        final var a = before == null ? "" : normalizeProvider(before.providerId());
        final var b = after == null ? "" : normalizeProvider(after.providerId());
        return !a.equals(b);
    }

    private static String normalizeProvider(final String id) {
        return id == null || id.isBlank() ? "" : id.strip().toLowerCase(Locale.ROOT);
    }

    /** Sets optional profile-level model tuning for this loop (used by isolated subagent loops). */
    public void agentModelTuning(final ModelVariantConfiguration value) {
        this.agentModelTuning = value;
    }

    /** Prevents a subagent profile from replacing a model explicitly selected for this run. */
    public void skipConfiguredAgentModel(final boolean value) {
        this.skipConfiguredAgentModel = value;
    }

    /**
     * Releases the session and fires the {@code yca.hooks.SessionEnded} hook once. Idempotent: the hook fires for the
     * first call only (a TUI may dispose a tab and later re-close, or the CLI and process shutdown may overlap).
     */
    public void close() {
        lifecycleLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
        } finally {
            lifecycleLock.unlock();
        }
        usageReporter.close();
        if (contextManager != null) {
            contextManager.shutdownSpeculation();
        }
        if (emitter != null) {
            final var id = currentSessionId != null ? currentSessionId : this.sessionId;
            emitter.emit(new SessionEndedEvent(
                    new EventContextBuilder()
                            .build(permissionService, chatSettings(), id, subagentId, subagentName, sessionWorkdir),
                    id,
                    null));
        }
    }

    /** Publishes an applied mode change, including when the session has not run a turn yet. */
    public void notifyModeChanged(final AgentMode mode) {
        if (emitter != null && !closed) {
            emit(AgentEvent.Notice.info(
                    "mode: " + mode.name().toLowerCase(Locale.ROOT),
                    currentSessionId != null ? currentSessionId : sessionId,
                    sessionWorkdir));
        }
    }

    /**
     * The last request sent to the provider for this session, {@code null} when none was
     * sent yet. Backed by {@code /context llm-payload}; the snapshot is immutable so a
     * later prune/compaction of the live list cannot change it.
     */
    public LlmPayload lastLlmPayload() {
        return lastLlmPayload;
    }

    /**
     * Background (auxiliary) LLM spend: calls and tokens that never appear as conversation turns
     * (memory extraction, compaction summarizer, sub-agent fold-in). The middleware measures these
     * too, so exposing them lets the app self-report match the middleware and makes background cost
     * visible instead of hidden. Returns all zeros when no usage tracker is wired.
     */
    public AuxiliaryUsage auxiliaryUsage() {
        if (usageTracker == null) {
            return AuxiliaryUsage.ZERO;
        }
        return new AuxiliaryUsage(
                usageTracker.auxiliaryRequests(),
                usageTracker.auxiliaryPromptTokens(),
                usageTracker.auxiliaryCompletionTokens(),
                usageTracker.auxiliaryCacheReadTokens(),
                usageTracker.auxiliaryFreshTokens());
    }

    /** Background (auxiliary) LLM spend counters, captured read-only for metrics/reporting. */
    public record AuxiliaryUsage(
            long calls, long promptTokens, long completionTokens, long cacheReadTokens, long freshTokens) {
        static final AuxiliaryUsage ZERO = new AuxiliaryUsage(0, 0, 0, 0, 0);
    }

    /**
     * Rebuilds - without calling the provider - the request the next round would carry right now:
     * the same system prefix {@code doRun} assembles, the persisted transcript and a freshly
     * computed volatile tail. It is a preview and not a history: the memory refresh, the context
     * usage line and the nudge block depend on the state at this instant and the persisted
     * transcript has none of them, so {@code /context next-request} says so while
     * {@link #lastLlmPayload()} stays the exact bytes of the last call.
     */
    public LlmPayload previewLlmPayload(final String sessionId) {
        final var tools = wireTools();
        final var toolsTokens = toolTokens(tools);
        final var history = sessionStore.load(sessionId);
        final var prompt = lastUserPrompt(history);
        final var messages = new ArrayList<Message>();
        messages.add(Message.system(systemPromptBuilder.build(prompt, null) + snapshotBlock(sessionId)));
        messages.addAll(history);
        turnExecutor.appendStateIfChanged(sessionId, messages, prompt, toolsTokens);
        final var resolved = resolvedModel();
        return new LlmPayload(
                sessionId,
                resolved == null ? "" : resolved.model(),
                List.copyOf(messages),
                List.copyOf(tools),
                "auto",
                toolsTokens);
    }

    /**
     * Synchronous forced memory extraction of the persisted transcript, used when the session
     * stops (tab close, headless run end). The end-of-turn hook already covered the delta
     * asynchronously while the app was idle; this is normally a watermark no-op and stays as
     * the deterministic last-resort for sessions closed before their first turn ever finished.
     *
     * @return {@code true} when an extraction pass ran, {@code false} when there was nothing new.
     */
    public boolean forceExtractNow(final List<Message> messages, final ChatSettings settings) {
        return memoryExtractor != null && memoryExtractor.forceExtractNow(messages, settings);
    }

    /**
     * Builds the wire tool list for the current call, hiding the file-mutating
     * tools (EDIT category) while the agent runs in PLAN mode: the model then
     * simply cannot call them - the strongest form of "plan must not build".
     */
    List<Tool> wireTools() {
        if (toolRegistry == null) {
            return List.of();
        }
        final var toolCeiling = effectiveAllowedTools();
        final var tools = toolCeiling == null
                ? toolRegistry.toWireTools(sessionWorkdir)
                : toolRegistry.toWireTools(sessionWorkdir, tool -> toolCeiling.contains(tool.name()));
        if (permissionService == null
                || permissionService.mode() != AgentMode.PLAN
                || permissionService.permissionMode() == PermissionMode.FULL_YOLO) {
            return tools;
        }
        return tools.stream()
                .filter(t -> {
                    final var embedded = toolRegistry.tool(t.function().name());
                    return embedded == null || embedded.category() != ToolCategory.EDIT;
                })
                .toList();
    }

    /** Borrowed registry owned by this loop's runtime, never by a child or side question. */
    public ToolRegistry effectiveToolRegistry() {
        if (closed) {
            throw new IllegalStateException("Session is closed");
        }
        return toolRegistry;
    }

    /** Captures the current projection, including profile, run, usability and PLAN restrictions. */
    public Set<String> availableToolNames() {
        if (closed) {
            throw new IllegalStateException("Session is closed");
        }
        return wireTools().stream().map(tool -> tool.function().name()).collect(Collectors.toUnmodifiableSet());
    }

    Set<String> effectiveAllowedTools() {
        final var profileTools = agentProvider == null ? null : agentProvider.allowedTools(currentAgentName);
        final var runTools = allowedTools;
        if (profileTools == null) {
            return runTools;
        }
        if (runTools == null) {
            return profileTools;
        }
        final var intersection = new HashSet<>(profileTools);
        intersection.retainAll(runTools);
        return Set.copyOf(intersection);
    }

    /** Installs a child run's tool ceiling before its first request. Null retains the registry's full set. */
    public void allowedTools(final Set<String> names) {
        this.allowedTools = names == null ? null : Set.copyOf(names);
    }

    /** Sets a positive upper bound for this agent run; model and provider caps may still lower it. */
    public void maxIterationsCap(final int cap) {
        if (cap < 1) {
            throw new IllegalArgumentException("maxIterationsCap must be positive");
        }
        this.maxIterationsCap = cap;
    }

    /** Whether the most recent turn stopped at its resolved iteration cap. */
    public boolean iterationLimitReached() {
        return turnState.iterationLimitReached;
    }

    boolean hasPendingTodos() {
        if (todoStore == null) {
            return false;
        }
        try {
            for (final var item : todoStore.all()) {
                if (!"completed".equals(item.status()) && !"cancelled".equals(item.status())) {
                    return true;
                }
            }
            return false;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /**
     * Content of the finished-todo set for the memory cadence: contents of items whose status is
     * completed or cancelled (membership-based so mid-turn todo additions cannot fake a flush).
     */
    Set<String> finishedTodos() {
        if (todoStore == null) {
            return Set.of();
        }
        try {
            final var done = new HashSet<String>();
            for (final var item : todoStore.all()) {
                if ("completed".equals(item.status()) || "cancelled".equals(item.status())) {
                    done.add(item.content());
                }
            }
            return done;
        } catch (final RuntimeException e) {
            return Set.of();
        }
    }

    /**
     * Content of the last real user message in the history, skipping compaction digests and
     * truncation nudges; {@code null} when the history carries none.
     */
    String lastUserPrompt(final List<Message> history) {
        if (history == null) {
            return null;
        }
        for (var i = history.size() - 1; i >= 0; i--) {
            final var m = history.get(i);
            if (isVolatileTail(m) || !"user".equals(m.role()) || !(m.content() instanceof String content)) {
                continue;
            }
            if (content.contains("<compacted-context>")
                    || content.contains("<pruned-context>")
                    || content.startsWith("... output truncated")
                    || content.startsWith("When a compacted context is provided")) {
                continue;
            }
            return content;
        }
        return null;
    }

    final FeedbackTiming feedbackTiming = new FeedbackTiming();
    private final ReentrantLock feedbackLock = new ReentrantLock();

    void emit(final AgentEvent event) {
        feedbackLock.lock();
        try {
            feedbackTiming.accept(event, clock, emitter::emit);
            emitter.emit(event);
        } finally {
            feedbackLock.unlock();
        }
    }

    /** Emits a task-solving phase marker for the benchmark timeline. */
    void emitPhase(final String name) {
        if (emitter != null) {
            emitter.emit(new AgentEvent.Phase(
                    name,
                    currentSessionId != null ? currentSessionId : this.sessionId,
                    subagentId,
                    subagentName,
                    sessionWorkdir));
        }
    }

    /**
     * The session's sub-agent admission budget, set once by {@code SessionRuntimeFactory}. Null for
     * standalone/btw contexts. Child loops share their root session's budget.
     */
    public void subagentBudget(final SubagentBudget budget) {
        this.subagentBudget = budget;
    }

    /** Installs the immutable ancestry ceiling before running a delegated agent. */
    public void subagentScope(final SubagentScope scope) {
        toolExecution.subagentScope(scope);
    }

    /** The session's sub-agent admission budget, or null when the loop cannot spawn children. */
    public SubagentBudget subagentBudget() {
        return subagentBudget;
    }

    // Event helpers that include the session and subagent info
    public void cancelTool(final long ordinal) {
        toolExecution.cancelTool(ordinal);
    }

    /** Whether the tool call with the given ordinal is still running. */
    public boolean isToolRunning(final long ordinal) {
        return toolExecution.isToolRunning(ordinal);
    }

    /**
     * Runs one tool call (lookup, arguments parsing, permission gate, execution) without touching the
     * conversation. Delegates to the tool-execution pipeline.
     */
    ToolOutcome runTool(final ToolCall call, final BooleanSupplier cancelled, final ChatSettings settings) {
        return toolExecution.runTool(call, cancelled, settings);
    }

    /** Unwraps a parallel tool future, rethrowing the cause as the loop would. */
    ToolOutcome awaitTool(final Future<ToolOutcome> future) throws InterruptedException {
        return toolExecution.awaitTool(future);
    }

    /** Whether a call targets a read-only tool (never state-changing). Reads are owned by the plateau guard. */
    boolean isReadOnly(final ToolCall call) {
        return toolExecution.isReadOnly(call);
    }

    /** A call can join a parallel run only if its tool is known and cannot mutate state. */
    boolean isParallelizable(final ToolCall call) {
        return toolExecution.isParallelizable(call);
    }

    boolean parallelReadonlyEnabled() {
        return toolExecution.parallelReadonlyEnabled();
    }

    AgentEvent.Compacted compacted(final String kind, final String mode) {
        return compacted(List.of(kind), mode, null);
    }

    /** A speculative candidate becomes a public pass only when actually adopted, not while prepared. */
    void emitAdoptedCompaction(final CompactionResult result) {
        if (emitter != null) {
            final var id = "compaction-" + UUID.randomUUID();
            try {
                emitter.emit(new CompactionPass(
                        id,
                        CompactionPass.Status.STARTED,
                        null,
                        null,
                        currentSessionId,
                        subagentId,
                        subagentName,
                        sessionWorkdir));
            } finally {
                emitter.emit(new CompactionPass(
                        id,
                        CompactionPass.Status.SUCCEEDED,
                        result.mode(),
                        "Context compacted (" + result.mode().name() + ").",
                        currentSessionId,
                        subagentId,
                        subagentName,
                        sessionWorkdir));
            }
        }
    }

    AgentEvent.Compacted compacted(
            final String kind, final String mode, final AgentEvent.Compacted.CompactionTokens tokens) {
        return compacted(List.of(kind), mode, tokens);
    }

    AgentEvent.Compacted compacted(final List<String> kinds, final String mode) {
        return compacted(kinds, mode, null);
    }

    AgentEvent.Compacted compacted(
            final List<String> kinds, final String mode, final AgentEvent.Compacted.CompactionTokens tokens) {
        return new AgentEvent.Compacted(
                kinds, mode, currentSessionId, subagentId, subagentName, tokens, sessionWorkdir);
    }

    /** Bounded before/after pair for a Compacted event (raw estimates, never negative). */
    static AgentEvent.Compacted.CompactionTokens compactionTokens(final int before, final int after) {
        return new AgentEvent.Compacted.CompactionTokens(Math.max(0, before), Math.max(0, after));
    }

    /**
     * Picks the most informative mode among the reductions merged into one checkpoint event:
     * {@code SUMMARY} (LLM summarizer ran) {@code > SHRUNK > TRUNCATE > CLEARED}. All the
     * zero-LLM strategies report {@code CLEARED}, so a prune/zero-LLM-only round stays
     * {@code CLEARED} while an LLM compaction makes the event carry {@code SUMMARY}.
     * The enum declaration order is NOT used (NONE then SUMMARY then TRUNCATE then CLEARED
     * then SHRUNK would rank SHRUNK above SUMMARY): the ranking is explicit here.
     */
    private static final Map<CompactionResult.Mode, Integer> MODE_RANK = Map.of(
            CompactionResult.Mode.CLEARED, 0,
            CompactionResult.Mode.TRUNCATE, 1,
            CompactionResult.Mode.SHRUNK, 2,
            CompactionResult.Mode.SUMMARY, 3);

    static String bestMode(final List<CompactionResult.Mode> modes) {
        return modes.stream()
                .max(Comparator.comparingInt(m -> MODE_RANK.getOrDefault(m, -1)))
                .orElse(CompactionResult.Mode.CLEARED)
                .name();
    }

    /**
     * Pinned escalation of the compaction gate: the context could not be brought under the trigger
     * by recovery compaction + local spill within the gate budget (or compaction stopped making
     * progress). The turn is <em>not</em> blocked: after this notice the request proceeds, so a
     * genuinely non-compactable context (single oversized user message, digest-only history) can
     * never hang the loop - the provider hard-overflow retry remains the final safety net.
     */
    void escalateCompactionGate(final int effectiveAfter) {
        final var trigger = contextManager.compactionTriggerTokens();
        final var hardLimit = contextManager.usableWindowTokens();
        emit(AgentEvent.Notice.compactionGateEscalated(
                "Compaction gate: the context cannot be compacted below the trigger "
                        + "(about " + effectiveAfter + " tokens; trigger " + trigger
                        + ", hard limit "
                        + hardLimit
                        + "). Run /compact urgent to force local truncation, or "
                        + "reduce the session size. Continuing and monitoring the provider hard limit.",
                currentSessionId,
                sessionWorkdir));
    }

    AgentEvent.Status status(final String message) {
        return new AgentEvent.Status(message, currentSessionId, subagentId, subagentName, sessionWorkdir);
    }

    AgentEvent.Notice notice(final AgentEvent.Notice.Level level, final String message) {
        return new AgentEvent.Notice(level, message, null, currentSessionId, subagentId, subagentName, sessionWorkdir);
    }

    AgentEvent.Notice notice(final AgentEvent.Notice.Level level, final String message, final String finishReason) {
        return new AgentEvent.Notice(
                level, message, finishReason, currentSessionId, subagentId, subagentName, sessionWorkdir);
    }

    AgentEvent.Notice notice(
            final AgentEvent.Notice.Level level,
            final String message,
            final String finishReason,
            final Throwable cause) {
        return new AgentEvent.Notice(
                level, message, finishReason, currentSessionId, subagentId, subagentName, cause, sessionWorkdir);
    }

    AgentEvent.TurnDone turnDone(final String content) {
        return new AgentEvent.TurnDone(content, currentSessionId, subagentId, subagentName, sessionWorkdir);
    }

    AgentEvent.Error error(final String message) {
        return new AgentEvent.Error(message, currentSessionId, subagentId, subagentName, sessionWorkdir);
    }

    AgentEvent.PromptIntegrated promptIntegrated(final String text) {
        return new AgentEvent.PromptIntegrated(text, currentSessionId, subagentId, subagentName, sessionWorkdir);
    }

    AgentEvent.TurnMetrics turnMetrics(
            final int llmCalls,
            final long thinkingDurationMs,
            final long toolDurationSumMs,
            final long toolDurationWallMs,
            final int toolsRun,
            final long turnDurationMs) {
        return new AgentEvent.TurnMetrics(
                llmCalls,
                thinkingDurationMs,
                toolDurationSumMs,
                toolDurationWallMs,
                toolsRun,
                turnDurationMs,
                new AgentEvent.Throughput(
                        turnState.outputTokens,
                        turnState.reasoningTokens,
                        turnState.llmActiveMs,
                        turnState.llmReportedMs,
                        turnState.reportedOutputTokens,
                        turnState.llmCalls,
                        turnState.reportedRequests,
                        turnState.outputEstimated),
                currentSessionId,
                subagentId,
                subagentName,
                sessionWorkdir);
    }

    AgentEvent.ToolTimingEvent toolTimingEvent(
            final String toolName, final long durationMs, final long wallDurationMs, final boolean parallelBatch) {
        return new AgentEvent.ToolTimingEvent(
                toolName,
                durationMs,
                wallDurationMs,
                parallelBatch,
                currentSessionId,
                subagentId,
                subagentName,
                sessionWorkdir);
    }

    /**
     * Appends the volatile session state (date, todos, memory refresh, context usage, nudge and the
     * compaction snapshot) as its own trailing user message in the working conversation, deduplicated
     * against the newest state tail already present: when the rendered state is byte-identical, nothing
     * is appended. Because the tail is part of the working list and is never moved nor rewritten once
     * sent, every request is the previous request's exact bytes plus the assistant/tool messages
     * and state changes appended since - so provider-side prefix caches (llama.cpp slot LCP, token
     * gateways) keep matching with f_keep ~ 1.0 like opencode instead of diverging mid-conversation.
     */
    static boolean isVolatileTail(final Message message) {
        return message.others() != null && Boolean.TRUE.equals(message.others().get("volatileTail"));
    }

    void emitTurnMetrics(final String content, final long turnStartNanos) {
        turnNotificationReporter.emitTurnMetrics(content, turnStartNanos);
    }

    void emitTurnEndNotification(final String sessionId, final List<Message> messages) {
        turnNotificationReporter.emitTurnEndNotification(sessionId, messages);
    }

    static boolean isTruncation(final String finishReason) {
        return TurnNotificationReporter.isTruncation(finishReason);
    }

    /**
     * Drains the {@link SubagentDeliveryBus} for this parent session and injects each finished
     * sub-agent's result as a {@code user}-role {@code <subagent ...>...</subagent>} message, so the
     * parent model's next round sees the child's completion. Only the parent loop (the one with a null
     * {@code subagentId}) drains: a child AgentLoop shares the same session id but must never receive
     * its own siblings' results. The injected message rides in the in-loop {@code messages} list and is
     * persisted through the same {@code sessionStore} so it survives compaction/pruning and is pruned by
     * {@link #purgeDeliveredSubagents(List)} once old.
     */

    /** The session's shared context-compression ref source (its live refs are published by the loop);
     * null when no {@link ContextView} is wired. */
    public ContextCompression compression() {
        return contextView == null ? null : contextView.compression();
    }

    /** The resolved working-context limits for the session's provider selection, never null. */
    ContextLimit resolvedContextLimit() {
        if (providerManager != null) {
            // Prefer the merged (per-provider over global over default) limits when the provider
            // manager can resolve them. A provider manager without a backing configuration cannot,
            // so fall back to this loop's own global config-sourced limits instead.
            final var fromProvider = providerManager.resolvedContextLimit(chatSettings);
            if (fromProvider != null && (fromProvider.min() != null || fromProvider.max() != null)) {
                return fromProvider;
            }
        }
        final var compression = configuration.compression();
        return compression == null ? new ContextLimit(null, null) : compression.contextLimit();
    }

    /** Scrub via the shared compression when wired, else a disposable scrubber. */

    /** Live usage snapshot published to {@link ContextView} consumers (eg. the manage_context tool). */

    /**
     * Applies a model-requested context operation (manage_context tool) to the live messages:
     * at most one per turn (enforced by the caller), results persisted and surfaced as a
     * {@link AgentEvent.Compacted} event with the {@code tool} kind.
     */
    /**
     * Shell-side entry point for a user-triggered context operation during a turn
     * (e.g. {@code /compact} or {@code /context prune} while the shell is busy): the operation is
     * queued as a directive and applied by the loop at the next safe checkpoint, at most once per
     * turn. Returns {@code false} when no consumer can accept the request (no context view) or when
     * an operation is already pending this turn.
     */
    public boolean requestUserContextOperation(
            final String action, final String focus, final int keepLast, final boolean force) {
        return requestUserContextOperation(action, focus, keepLast, force, false);
    }

    /**
     * {@link #requestUserContextOperation(String, String, int, boolean)} with an {@code urgent}
     * flag propagated to the queued directive (see {@link ContextView.Directive#urgent()}).
     */
    public boolean requestUserContextOperation(
            final String action, final String focus, final int keepLast, final boolean force, final boolean urgent) {
        if (contextView == null) {
            return false;
        }
        return contextView.request(new ContextView.Directive(action, focus, keepLast, "user", force, urgent));
    }

    /** @see HistoryPruningStrategies#applyZeroLlmStrategies(List) */
    List<String> applyZeroLlmStrategies(final List<Message> messages) {
        return historyPruning.applyZeroLlmStrategies(messages);
    }

    String normalizedArguments(final String arguments) {
        return historyPruning.normalizedArguments(arguments);
    }

    /** @see HistoryPruningStrategies#deduplicateToolCalls(List) */
    int deduplicateToolCalls(final List<Message> messages) {
        return historyPruning.deduplicateToolCalls(messages);
    }

    /** @see HistoryPruningStrategies#supersedeOverwrittenWrites(List) */
    int supersedeOverwrittenWrites(final List<Message> messages) {
        return historyPruning.supersedeOverwrittenWrites(messages);
    }

    /** @see HistoryPruningStrategies#purgeFailedToolInputs(List) */
    int purgeFailedToolInputs(final List<Message> messages) {
        return historyPruning.purgeFailedToolInputs(messages);
    }

    /** @see HistoryPruningStrategies#purgeDeliveredSubagents(List) */
    int purgeDeliveredSubagents(final List<Message> messages) {
        return historyPruning.purgeDeliveredSubagents(messages);
    }

    /**
     * Round-level budget guard: after each tool result lands, if the estimated context is above the
     * pruning threshold, prune locally first (zero LLM cost); only if it remains above the
     * compaction threshold, run the LLM compaction. The round continues: the already-landed results
     * stay in the verbatim tail protected by the compaction's keep budget. Each reduction is
     * persisted and clears the usage anchor like any other context reduction.
     */
    void checkRoundBudget(final String sessionId, final List<Message> messages, final int toolsTokens) {
        turnExecutor.checkRoundBudget(sessionId, messages, toolsTokens, null, null);
    }

    /**
     * Round-level budget guard: after each tool result lands, if the estimated context is above the
     * pruning threshold, prune locally first (zero LLM cost); only if it remains above the
     * compaction threshold, run the LLM compaction. The round continues: the already-landed results
     * stay in the verbatim tail protected by the compaction's keep budget. Each reduction is
     * persisted and clears the usage anchor like any other context reduction.
     *
     * @param settings   the request settings to base the compaction request on (the session's
     *                   effective settings when {@code null}), so the model/variant the user picked
     *                   for this turn also drives the compaction call
     * @param cancelled  the current run's cancellation probe, so a cancelled turn does not launch
     *                   an expensive compaction
     */

    /**
     * A turn that ran tools but ends text-only while the todo list still has pending items looks
     * finished to the loop while the work is not: the same predicate drives both the optional
     * auto-continuation (see {@code yca.todos.continueOnUnfinished}) and this notice, so the user
     * can still resume explicitly - or review why the model stopped - once auto-continuation is
     * exhausted. The notice is emitted with the normalized {@code finish_reason} so a rich frontend
     * can tell a deliberate, clean stop (the model chose to end its turn despite pending work) apart
     * from a truncation or an error, which are reported by separate events.
     *
     * @return the pending/total counts, or {@code null} when the todo list does not hold this turn open.
     */
    /**
     * A round is a plateau round when every coverage key it contributed was already seen this turn
     * (marked {@link #REDUNDANT_MARKER}) and no write happened (a write contributes
     * {@link #WRITE_MARKER}). Writes and first-time files/results reset the plateau counter in the
     * caller because such rounds fall in the {@code else} branch.
     */
    static boolean isPlateauRound(final Set<String> roundCoverage) {
        for (final var key : roundCoverage) {
            if (!key.startsWith(REDUNDANT_MARKER)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The session's effective provider + model: the per-tab provider/model selection when set,
     * else the process default one. The model override applies even without a per-tab provider -
     * a plain {@code /model} (no {@code /provider}) must change what goes on the wire, not only
     * the name the UI reads from {@code chatClient.model(settings)}.
     */
    ProviderManager.ResolvedModel resolvedModel() {
        return resolvedModel(chatSettings);
    }

    ProviderManager.ResolvedModel resolvedModel(final ChatSettings settings) {
        if (providerManager == null) {
            return null;
        }
        final var overrideModel = settings.model() == null || settings.model().isBlank() ? null : settings.model();
        if (settings.providerId() != null) {
            final var provider = providerManager.provider(settings.providerId());
            if (provider != null) {
                return new ProviderManager.ResolvedModel(
                        provider, overrideModel != null ? overrideModel : provider.model());
            }
        }
        final var defaultModel = providerManager.resolveDefault();
        if (defaultModel != null) {
            return overrideModel == null
                    ? defaultModel
                    : new ProviderManager.ResolvedModel(defaultModel.provider(), overrideModel);
        }
        final var configModel = configuration == null || configuration.provider() == null
                ? null
                : configuration.provider().model();
        return new ProviderManager.ResolvedModel(
                providerManager.current(), overrideModel != null ? overrideModel : configModel);
    }

    /**
     * The model the next request must carry: the session's selection resolved right now (a
     * mid-turn {@code /model} applies from the next round, not only from the next turn), falling
     * back to the model resolved at window start when this loop has no provider wiring.
     */
    String requestModel(final ProviderManager.ResolvedModel windowModel) {
        final var current = resolvedModel();
        return current == null ? (windowModel == null ? null : windowModel.model()) : current.model();
    }

    String requestModel(final ProviderManager.ResolvedModel windowModel, final ChatSettings settings) {
        final var current = resolvedModel(settings);
        return current == null ? (windowModel == null ? null : windowModel.model()) : current.model();
    }

    boolean compressionBoundaryOnly(final ChatCompletionRequest request) throws IOException {
        return chatClient != null && chatClient.compressionBoundaryOnly(request);
    }

    ContextMeasurement.Payload contextPayload(final ChatCompletionRequest request) throws IOException {
        return chatClient.contextPayload(request, usageTracker);
    }

    <T, X extends Throwable> T withContextCapture(
            final Consumer<ContextMeasurement.Payload> consumer, final ScopedValue.CallableOp<T, X> operation)
            throws X {
        return chatClient.withContextCapture(consumer, operation);
    }

    /**
     * Iteration window cap cascade: per-model behavior.maxIterations > per-provider tuning.maxIterations
     * > the global llm.maxIterations default. A negative override (< 0) means an UNBOUNDED window: the
     * turn runs until it genuinely ends (the model finishes, a plateau/no-progress guard stops it,
     * context overflows, or the turn is cancelled). A positive {@code maxIterationsCap} (always set for
     * sub-agents) bounds the window regardless, so unbounded applies only to top-level turns with no cap.
     * The provider/model come from {@code resolved} (the default provider resolved at loop start).
     */
    int resolvedMaxIterations(final ProviderManager.ResolvedModel resolved) {
        return loopGuards.resolvedMaxIterations(resolved);
    }

    /**
     * Exploration-plateau limit cascade: per-model behavior.plateauRounds > per-provider
     * tuning.plateauRounds > the global llm.plateauRounds default (8). A non-zero resolved value wins
     * verbatim: a negative one means nudge-only (the plateau nudges but never hard-stops the turn), a
     * positive one is the nudge + hard-stop limit, and 0 (only reachable via an explicit global
     * llm.plateauRounds=0, since a per-model/provider 0 falls through the chain) disables the guard.
     */
    int resolvedPlateauRounds(final ProviderManager.ResolvedModel resolved) {
        return loopGuards.resolvedPlateauRounds(resolved);
    }

    /**
     * Exploration-nudge cascade: per-model behavior.explorationNudgeRounds > per-provider
     * tuning.explorationNudgeRounds > the global llm.explorationNudgeRounds default (20). 0 disables
     * the nudge (or falls back to the provider/global when only set to 0 higher up the chain).
     */
    int resolvedModelExplorationNudgeRounds(final ProviderManager.ResolvedModel resolved) {
        return loopGuards.resolvedModelExplorationNudgeRounds(resolved);
    }

    /**
     * Exploration-plateau feedback cascade: per-model behavior.plateauFeedback > per-provider
     * tuning.plateauFeedback > the global llm.plateauFeedback default (nudge_2).
     */
    PlateauFeedbackMode resolvedPlateauFeedback(final ProviderManager.ResolvedModel resolved) {
        return loopGuards.resolvedPlateauFeedback(resolved);
    }

    /**
     * Doom-loop success threshold cascade: per-model behavior.repeatSuccessThreshold > per-provider
     * tuning.repeatSuccessThreshold > the global yca.tools.repeatSuccessThreshold default (3).
     * 0 at the global level disables the guard entirely.
     */
    int resolvedRepeatSuccessThreshold(final ProviderManager.ResolvedModel resolved) {
        return loopGuards.resolvedRepeatSuccessThreshold(resolved);
    }

    /**
     * Round at which the one-shot plateau nudge fires for a mode: {@code NUDGE_2} at {@code limit-2},
     * {@code NUDGE_1} at {@code limit-1}, {@code NONE} or a limit too small for a meaningful early
     * nudge (&le; 2) returns 0 (no nudge, hard stop only).
     */
    static int plateauFeedbackNudgeAt(final PlateauFeedbackMode mode, final int limit) {
        return switch (mode) {
            case NUDGE_2 -> limit > 2 ? limit - 2 : 0;
            case NUDGE_1 -> limit > 2 ? limit - 1 : 0;
            case NONE -> 0;
        };
    }

    TodoContinuation.PendingTodos unfinishedTodos(final boolean toolsRanThisTurn) {
        return todoContinuation.unfinishedTodos(toolsRanThisTurn);
    }

    AgentEvent.Notice unfinishedTodosNotice(final TodoContinuation.PendingTodos pending, final String finishReason) {
        return todoContinuation.unfinishedTodosNotice(pending, finishReason);
    }

    void persistStopNotice(final String sessionId, final String message) {
        todoContinuation.persistStopNotice(sessionId, message);
    }

    int todoMaxContinues() {
        return todoContinuation.todoMaxContinues();
    }

    boolean tryTodoAutoContinue(
            final List<Message> messages,
            final String finishReason,
            final boolean toolsRanThisTurn,
            final boolean userInjected) {
        return todoContinuation.tryTodoAutoContinue(
                messages, finishReason, toolsRanThisTurn, userInjected, turnState, this::emit);
    }

    boolean canAutoContinueUnfinished(final boolean userInjected) {
        return todoContinuation.canAutoContinueUnfinished(userInjected);
    }

    String unfinishedTodoContinuePrompt(final int continuations) {
        return todoContinuation.unfinishedTodoContinuePrompt(continuations);
    }

    /**
     * Outcome of a single tool call, decoupled from message/session mutation so calls can run
     * concurrently while results still land in the conversation in the model's call order.
     * {@code paths} carries the file paths a read or write tool touched, for stale-read invalidation.
     * {@code durationMs} is the individual tool execution time, used for per-turn metrics.
     */

    /**
     * Executes the tool calls of one model reply. Maximal runs of consecutive read-only calls run
     * concurrently on virtual threads (they cannot invalidate each other's inputs), while
     * state-changing tools stay sequential: a read never overtakes a preceding edit issued in the
     * same reply. Message and session order always follow the model's call order.
     */

    /** Head+tail ingestion cap applied to tool outputs before they enter the conversation (in the turn executor). */
    public String capIngestionOutput(final String output) {
        return turnExecutor.capIngestionOutput(output);
    }

    private AgentEvent.TextDelta textDelta(final String delta) {
        return turnExecutor.textDelta(delta);
    }

    AgentEvent.ThinkingDelta thinkingDelta(final String delta) {
        return turnExecutor.thinkingDelta(delta);
    }

    static String unknownToolDiagnostic(final String name) {
        return ToolCallExecution.unknownToolDiagnostic(name);
    }

    /** File tools whose results participate in stale-read invalidation. */
    static final Set<String> FILE_READ_TOOLS = Set.of("read");

    static final Set<String> FILE_WRITE_TOOLS = Set.of("write_file", "edit_file", "apply_patch");

    /** Stable prefix every stale-read note carries; used to skip already-noted reads (see {@link StaleReads}). */
    static final String STALE_READ_MARKER = "[stale read cleared:";

    /**
     * Tracks session-resolved reads superseded by confirmed mutations, including partial failures.
     * Notes are appended to the mutation result without rewriting the earlier conversation prefix.
     * Access is confined to the loop thread; workers only produce outcomes.
     */
    static final class StaleReads {
        private final Map<String, Path> readByCallId;

        StaleReads(final Map<String, Path> readByCallId) {
            this.readByCallId = new LinkedHashMap<>(readByCallId);
        }

        /**
         * Records a {@code read} (by call id), or computes which earlier read paths a
         * confirmed mutation invalidated. Never mutates {@code messages}: the caller folds the
         * returned paths into the write tool's own result message, which is appended right after
         * this call (zero prefix-cache cost — that message is new content this round anyway).
         *
         * @param messages conversation so far, WITHOUT the write result message (not yet appended)
         * @return the stale paths a confirmed mutation superseded, sorted; empty otherwise
         */
        List<Path> onLanded(final List<Message> messages, final ToolOutcome outcome) {
            if (outcome.paths().isEmpty()) {
                return List.of();
            }
            final var touched = new ArrayList<Path>(outcome.paths().size());
            for (final var raw : outcome.paths()) {
                touched.add(Path.of(raw).toAbsolutePath().normalize());
            }
            if (FILE_READ_TOOLS.contains(outcome.name())) {
                if (outcome.success()) {
                    for (final var path : touched) {
                        readByCallId.put(outcome.callId(), path);
                    }
                }
                return List.of();
            }
            // Non-read paths are confirmed mutations, even when a multi-file operation failed later.
            final var written = Set.copyOf(touched);
            final var stale = new LinkedHashSet<Path>();
            for (final var message : messages) {
                if (!"tool".equals(message.role())) {
                    continue;
                }
                final var read = readByCallId.get(message.toolCallId());
                if (read == null || !written.contains(read)) {
                    continue;
                }
                if (String.valueOf(message.content()).contains(STALE_READ_MARKER)) {
                    continue; // already noted
                }
                stale.add(read);
            }
            if (stale.isEmpty()) {
                return List.of();
            }
            final var paths = new ArrayList<>(stale);
            paths.sort(Comparator.naturalOrder());
            return paths;
        }
    }

    /** Rebuilds the {@code callId -> path} index of past {@code read} calls from the conversation. */

    /** Extracts the file paths a file tool touched: the {@code path} arg, or {@code +++} headers for patches. */

    /**
     * Doom-loop breaker: refuses repeated identical tool calls that keep returning the SAME (already-known)
     * successful result, instead of burning another model round-trip on duplicate information. Each signature
     * (tool name + normalized arguments) tracks its own CONSECUTIVE success streak. The FIRST occurrence of a
     * signature is reserved (it only primes comparison state, never counts), so the configured threshold T means
     * "T identical-successful-output calls in a row, refuse the next one". A call counts toward the streak only
     * when it succeeded AND its output carried no new information (identical output, ignoring embedded ISO-8601
     * timestamps). A failing call (legitimate retry) or a same-signature call returning fresh content
     * (polling/progress) resets the streak to zero. A refused call never runs and never resets the streak, so the
     * diagnostic stays stable ("called T times") instead of escalating. Arguments are normalized (JSON keys
     * sorted) so cosmetic spelling variants do not bypass the guard.
     */
    static final class ToolRepeats {
        /** ISO-8601 dates/times, clock times and build-tool durations; deliberately no bare digit runs. */
        private static final Pattern DATE_LIKE = Pattern.compile(
                // ISO-8601 date, optionally with time and zone (2026-10-13T18:00:00+02:00)
                "\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:?\\d{2})?)?"
                        // standalone clock (18:00, 18:00:00, 18:00:00.123) - log prefixes and timestamps
                        + "|\\b\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?\\b"
                        // build-tool durations: "in 5s", "Total time: 1.234 s", "0.5ms", "[1.2s]"
                        + "|\\b\\d{1,4}(?:\\.\\d{1,3})?\\s*(?:ms|s)\\b");

        /** Success streak length that triggers a refusal; {@code <= 0} disables the guard entirely. */
        private final int threshold;

        /** callId -> signature key of the call, so a result can be matched back to its call args. */
        private final Map<String, String> callKeyByCallId = new HashMap<>();
        /** Signature -> per-signature repeat state (last normalized output + consecutive success streak). */
        private final Map<String, State> states = new HashMap<>();
        /** Turn-level tombstone of already-seen normalized outputs (across all signatures/tools). */
        private final Set<String> seenOutputs = new HashSet<>();

        private static final class State {
            String lastOutput; // normalized output, null until the first occurrence is primed
            int streak;
            boolean seen;
        }

        ToolRepeats(final int threshold) {
            this.threshold = threshold;
        }

        /** Replaces embedded ISO-8601 dates/times so two otherwise-identical outputs compare equal. */
        static String normalizeForComparison(final String raw) {
            if (raw == null || raw.isEmpty()) {
                return "";
            }
            return DATE_LIKE.matcher(raw).replaceAll("\u0000");
        }

        /**
         * Atomically records one executed tool result and updates its signature state. Returns whether the
         * result carried NEW information for the round ({@code false} when its normalized output was already
         * produced earlier this turn by any tool - the turn-level tombstone). Per-signature: the success streak
         * used by the doom-loop refusal is bumped for a repeated identical-output success and reset on failure
         * or fresh content; the first successful occurrence seeds it (T means T). Only reached for executed
         * calls: refused/never-run calls skip it so the streak stays stable across refusals.
         */
        boolean record(final String callId, final boolean success, final String output) {
            final var key = callKeyByCallId.remove(callId);
            if (key == null) {
                return true; // never saw the call: always informative
            }
            final var value = normalizeForComparison(output);
            final var state = states.computeIfAbsent(key, k -> new State());
            // per-signature novelty drives the doom-loop success streak (refusal); turn-wide novelty
            // (tombstone across all tools/signatures) drives the no-progress/plateau "informative" signal.
            final boolean signatureNovel = state.seen && !value.equals(state.lastOutput);
            state.lastOutput = value;
            if (!state.seen) {
                // first occurrence: prime the baseline. A success counts as the first identical-output
                // success in the streak (T means T), a first failure only primes and never starts one.
                state.streak = success ? 1 : 0;
            } else if (success && !signatureNovel) {
                state.streak++;
            } else {
                state.streak = 0;
            }
            state.seen = true;
            // tombstone: an output already produced this turn by ANY prior call is not (turn-wise) novel.
            final var turnNovel = seenOutputs.add(value);
            return turnNovel;
        }

        /** The normalized arguments are computed by the owning loop (JSON key sorting lives there). */
        ToolOutcome rejectIfRepeated(final ToolCall call) {
            return rejectIfRepeated(call, null);
        }

        /**
         * Registers the callId -> signature-key mapping for {@code record()} to match the result back.
         * Called for EVERY executed call (read and non-read alike) so the per-signature output/streak
         * tracking in {@link #record(String, boolean, String)} works for reads too (it feeds the
         * no-progress decision). Idempotent: the key is deterministic for a given call.
         */
        void register(final ToolCall call, final Function<String, String> normalizer) {
            final var fn = call.function();
            if (fn == null) {
                return;
            }
            final var name = fn.name() == null ? "" : fn.name();
            final var args = fn.arguments() == null ? "{}" : fn.arguments();
            callKeyByCallId.put(call.id(), name + '\n' + (normalizer == null ? args : normalizer.apply(args)));
        }

        ToolOutcome rejectIfRepeated(final ToolCall call, final Function<String, String> normalizer) {
            if (threshold <= 0) {
                return null; // guard disabled
            }
            register(call, normalizer); // ensure the callId mapping exists for the streak lookup
            final var fn = call.function();
            final var name = fn == null || fn.name() == null ? "" : fn.name();
            final var key = callKeyByCallId.get(call.id());
            final var state = states.get(key);
            if (state == null || state.streak < threshold) {
                return null;
            }
            // a refused call never runs, so record() is never called for it and would leave its
            // callId -> key mapping behind: drop it here to keep ToolRepeats bounded within the turn.
            callKeyByCallId.remove(call.id());
            final var diagnostic = "Tool " + name + " was called " + threshold
                    + " times in a row with the same arguments, each time returning the same already-known result."
                    + " Repeating it adds no information. Change strategy - narrow or widen the query,"
                    + " use a different command, or answer from what you already collected.";
            return ToolOutcome.refused(call.id(), name, diagnostic);
        }
    }

    /** Whether a call targets a read-only tool (never state-changing). Reads are owned by the plateau guard. */

    /**
     * Emits a quota-only {@link AgentEvent.UsageUpdated} (the frontends' single source for the sidebar gauge)
     * with the session's current token metrics. Used at provider/model switch so the TUI and web
     * reflect the new provider's account usage immediately, and by {@code /usage} so a forced
     * refresh reaches the sidebar instead of only the command's table. The context fields are the
     * last values a real turn published (never recomputed from an empty list), so the push refreshes
     * the quota without clobbering the live context gauge.
     */
    public void pushUsageQuota() {
        usageReporter.pushUsageQuota(requestModel(resolvedModel()));
    }

    /**
     * Attaches the session's current provider to the quota store (forcing a live usage fetch when the
     * provider enables Codex usage) and immediately pushes a {@link AgentEvent.UsageUpdated}. Unlike the
     * provider-switch path in {@link #updateChatSettings(ChatSettings)}, this also runs when the
     * provider did not change, so a freshly opened tab/session shows the sidebar gauge before any turn
     * ran. Pre-first-turn safe: the store is keyed on the loop's stable session id and the pushed
     * event carries the same id so {@code ShellRegistry} routes it to the owning shell.
     */
    public void refreshUsage() {
        usageReporter.refreshUsage(requestModel(resolvedModel()));
    }

    /** Reads the per-session cached account quota without causing a refresh. */
    public AgentEvent.UsageUpdated.Quota cachedQuotaSnapshot() {
        return usageReporter.cachedQuotaSnapshot();
    }

    /**
     * Publishes a {@link AgentEvent.UsageUpdated} carrying the given effective context estimate (the
     * same value a compaction-progress notice prints), so the frontends' context gauge flips to the
     * calibrated compaction estimate including the wire tool tokens.
     */
    public void publishUsageContext(final int effective) {
        usageReporter.pushUsageContext(effective, requestModel(resolvedModel()));
    }

    /**
     * Estimates the token contribution of the wire tool schemas currently injected into every request
     * of this session, mirroring what {@link AgentTurnExecutor} computes for a running turn.
     */
    public int estimateWireToolTokens() {
        return toolTokens(wireTools());
    }

    /**
     * Estimates the token contribution of the wire tool schemas injected into every request,
     * which are never part of the message list but consume context window. Falls back to a rough
     * per-tool cost when the JSON mapper is unavailable.
     */
    int toolTokens(final List<?> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        if (mapper != null) {
            try {
                return TokenEstimator.estimate(mapper.toString(tools));
            } catch (final RuntimeException ignored) {
                // fall through to the per-tool approximation
            }
        }
        int total = 0;
        for (final var ignored : tools) {
            total += 300;
        }
        return total;
    }

    static String str(final Object content) {
        return content instanceof String s ? s : content == null ? "" : content.toString();
    }

    /**
     * Appends the per-session snapshot block (active checkpoint label plus retained summaries that
     * must survive compaction) to the system prompt, when any exist.
     */
    /** Unconditional context-usage line rendered in every volatile session-state tail. */
    String contextUsageLine(final List<Message> messages, final int toolsTokens) {
        return contextGuidance.contextUsageLine(messages, toolsTokens);
    }

    /** Context-usage nudge appended to the volatile session state. */
    String nudgeBlock(final List<Message> messages, final int toolsTokens) {
        return contextGuidance.nudgeBlock(messages, toolsTokens);
    }

    /** Bounded, guaranteed-valid user-turn ref guidance (hard tier only). */
    String compressionRefGuidance(final List<Message> messages, final int tier) {
        return contextGuidance.compressionRefGuidance(messages, tier);
    }

    /**
     * Advances the compression-nudge cadence once per real LLM request round and arms the post-compress
     * cooldown when a fresh compression landed since the last round. Must be called on the run-loop
     * thread only (never from a preview), and before the volatile tail is appended so the nudge decision
     * reflects the new round.
     */
    void advanceCompressionCadence(final List<Message> messages) {
        contextGuidance.advanceCompressionCadence(messages);
    }

    int compressionNudgeTier(final List<Message> messages, final int toolsTokens) {
        return contextGuidance.compressionNudgeTier(messages, toolsTokens);
    }

    String snapshotBlock(final String sessionId) {
        if (snapshotStore == null || sessionId == null) {
            return "";
        }
        final var sb = new StringBuilder();
        final var label = snapshotStore.checkpointLabel(sessionId);
        if (label != null && !label.isBlank()) {
            sb.append("\n<snapshot checkpoint=\"").append(label).append("\">\n");
        } else {
            sb.append("\n<snapshot>\n");
        }
        final var retained = snapshotStore.retained(sessionId);
        if (retained == null || retained.isEmpty()) {
            sb.append("checkpoint: ")
                    .append(label == null || label.isEmpty() ? "none" : label)
                    .append('\n');
        } else {
            for (final var r : retained) {
                final var summary = r.summary();
                sb.append("- ")
                        .append(summary == null ? "" : summary.replace("\n", " "))
                        .append('\n');
            }
        }
        sb.append("</snapshot>\n");
        return sb.toString();
    }

    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled) {
        return turnExecutor.run(prompt, agentName, sessionId, history, cancelled);
    }

    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt) {
        return turnExecutor.run(prompt, agentName, sessionId, history, cancelled, queuedPrompt);
    }

    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt,
            final String explicitModel) {
        return turnExecutor.run(prompt, agentName, sessionId, history, cancelled, queuedPrompt, explicitModel);
    }

    public String resume(
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled) {
        return turnExecutor.resume(agentName, sessionId, history, cancelled);
    }

    public String resume(
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt) {
        return turnExecutor.resume(agentName, sessionId, history, cancelled, queuedPrompt);
    }

    public String resume(
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt,
            final String explicitModel) {
        return turnExecutor.resume(agentName, sessionId, history, cancelled, queuedPrompt, explicitModel);
    }
}
