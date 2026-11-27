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
package io.yupiik.ia.agent.core.context;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent.CompactionPass;
import io.yupiik.ia.agent.core.configuration.BufferConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CompactionLlmMode;
import io.yupiik.ia.agent.core.configuration.GateConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderCompactionConfiguration;
import io.yupiik.ia.agent.core.context.CompactionResult.Mode;
import io.yupiik.ia.agent.core.context.SpeculativeCompaction.Candidate;
import io.yupiik.ia.agent.core.events.CompactionStartingEvent;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.events.HookResult;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatRoutes;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.RetryMode;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.core.wiring.SessionAwareLogRecord;
import io.yupiik.llm.client.ContextWindowExceededException;
import io.yupiik.llm.client.LlmHttpFailureException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Context reduction strategies, never failing silently.
 *
 * <p>{@link #compact(List, boolean, String, int)} summarizes the older conversation with an LLM
 * using a structured template, keeping a turn-aligned, token-budgeted verbatim tail that never
 * splits an assistant tool call from its results, re-anchoring on the previous summary when one
 * exists and replaying the last user message when the tail does not cover it. The summarizer
 * input is chunked to fit the summarizer model: the chunk cap derives from the usable window (or
 * an explicit {@code yca.compaction.summarizerChunkChars}), and when a call fails because the
 * input is too large the cap is halved down to the configured floor and compaction retried. When
 * the summarizer eventually fails it degrades to a deterministic local truncation.
 *
 * <p>{@link #prune(List)} is a local, LLM-free reduction of old tool outputs (the way to reclaim
 * bulk space cheaply): recent turns and a token budget of recent tool outputs are protected, then
 * old verbose tool outputs are first spliced down to their head and tail (the shrink rung) and only
 * cleared to a short marker when the volume still clears a minimum, so the operation never churns
 * the history for a tiny gain.
 */
public class CompactionCoordinator {
    private static final Logger LOGGER = Logger.getLogger(CompactionCoordinator.class.getName());

    /** Replacement content of tool outputs cleared by {@link #prune(List)}. */
    public static final String PRUNE_CLEARED_MARKER = "[old tool result cleared]";

    /** Replacement content of failed tool outputs cleared by {@link #prune(List)}: keeps the failure visible. */
    public static final String PRUNE_CLEARED_FAILED_MARKER = "[old tool result cleared - failed]";

    /** Fragment of the elision marker inserted by {@link #splice(String, int)}; detects already spliced outputs. */
    private static final String SPLICE_MARKER_FRAGMENT = " chars elided ...]";

    private static final String COMPACTED_MARKER = "<compacted-context>";

    /**
     * Guidance attached right after the digest so the consuming model treats the compacted context
     * as historical state instead of re-running or re-verifying what is already described in it
     * (small models in particular tend to loop on the same thoughts after a compaction without it).
     * Deliberately placed after {@code </compacted-context>} so {@link #previousSummary} parsing and
     * every digest detector (all keyed off the wrapper prefix) stay unaffected.
     */
    private static final String DIGEST_GUIDANCE = """
            When a compacted context is provided, treat it as historical state.
            Do not reload, reconstruct, or repeat actions already described in it.
            Continue from the state it represents; only call tools when needed for the current task.""";

    /** Required output structure of the summarizer, kept stable so repeated compactions can merge cleanly. */
    private static final String SUMMARY_TEMPLATE = """
            Output this markdown structure exactly, keeping every section even when empty:
            ## Objective
            ## Important Details
            ## Work State
            ### Completed
            ### Active
            ### Blocked
            ## Next Move
            ## Relevant Files""";

    /**
     * The default {@code max_tokens} for a summarizer call when the compact model's tuning leaves it
     * unset (0/-1/null sentinel). A finite, small cap keeps the wire completion cheap on a cheap
     * summarizer model while still comfortably covering the digest output. It is independent of the
     * character-based digest caps ({@code digestChars} bounds retained text, not generated tokens); an
     * explicit {@code yca.model.compact.maxTokens} still wins. Note the shared reasoning/output budget
     * on reasoning models means this bounds the total completion (reasoning + digest), and a truncated
     * completion is accepted as-is (the digest is folded and clamped locally regardless).
     */
    static final int DEFAULT_SUMMARIZER_MAX_TOKENS = 8192;

    /**
     * A bounded input view of the summarizable region {@code [from, boundary)}: the previous digest
     * state (every recognized whole-message {@code <compacted-context>} body, newest last) and the
     * remaining ordinary event messages {@code [from, boundary)} with those digest messages filtered
     * out. This makes repeated compactions incremental ("previous state + new events") instead of
     * re-sending the entire original transcript or stacking digest texts.
     *
     * <p>Only a complete whole-message user-role digest (content starting with {@code <compacted-context>}
     * ending with a matching {@code </compacted-context>} whose only trailing text is whitespace or the
     * {@link #DIGEST_GUIDANCE}) is treated as digest state. Anything else - tool/assistant messages,
     * prose, quoted lines, malformed or unclosed wrappers, an inline (not whole-message) digest - stays
     * an ordinary event so real history is never silently dropped on an unproven coverage assumption.
     * The prior state is also only ever <em>one</em> combined block, so a previously published digest is
     * not duplicated downstream.
     */
    private record SummaryInput(String priorState, List<Message> events) {}

    /** The close marker used to recognize a whole digest message (see {@link #isDigestMessage(Message)}). */
    private static final String DIGEST_CLOSE = "</compacted-context>";

    private final CodingAgentConfiguration configuration;
    private final Path workdir;
    private final ChatClient chatClient;
    private final UsageTracker usageTracker;
    private final ProviderManager providerManager;
    private final Emitter emitter;
    private final String sessionId;
    private final BackoffSleeper backoffSleeper;
    private volatile boolean warnedCompactModel;

    public CompactionCoordinator(
            final CodingAgentConfiguration configuration,
            final ChatClient chatClient,
            final UsageTracker usageTracker,
            final ProviderManager providerManager,
            final Emitter emitter,
            final String sessionId,
            final BackoffSleeper backoffSleeper,
            final Path workdir) {
        this.workdir = Objects.requireNonNull(workdir, "workdir");
        this.configuration = configuration;
        this.chatClient = chatClient;
        this.usageTracker = usageTracker;
        this.providerManager = providerManager;
        this.emitter = emitter;
        this.sessionId = sessionId;
        this.backoffSleeper = backoffSleeper;
    }

    /**
     * Reduces old tool outputs locally (no LLM call): the most recent turns and a budget of the
     * most recent tool outputs are protected, then old verbose tool outputs are first spliced to
     * their head and tail ({@code yca.pruning.shrinkOutputChars}) and only cleared to a marker when
     * the prunable volume still clears {@code yca.pruning.minPruneTokens}, otherwise nothing happens.
     */
    public CompactionResult prune(final List<Message> messages) {
        final var p = configuration.pruning();
        final var leadingSystem = leadingSystemMessages(messages);
        final var from = leadingSystem.size();
        var boundary = splitBoundary(messages, from, Math.max(2, p.keepRecent()), false);
        if (boundary <= from) {
            return new CompactionResult(messages, Mode.NONE, "");
        }
        // protect the most recent whole turns: the boundary can only move back (protect more)
        final var protectTurns = Math.max(1, p.protectTurns());
        var seenTurns = 0;
        for (var i = messages.size() - 1; i >= from && seenTurns < protectTurns; i--) {
            if ("user".equals(messages.get(i).role()) && ++seenTurns == protectTurns) {
                boundary = Math.min(boundary, i);
            }
        }

        final var shrinkCap = p.shrinkOutputChars();
        final var toClear = new ArrayList<Integer>();
        final var toShrink = new ArrayList<Integer>();
        var protectedTokens = 0;
        var prunableTokens = 0;
        var shrinkableTokens = 0;
        for (var i = boundary - 1; i >= from; i--) {
            final var m = messages.get(i);
            if (!"tool".equals(m.role())) {
                continue;
            }
            final var content = str(m.content());
            if (PRUNE_CLEARED_MARKER.equals(content)) {
                continue;
            }
            final var tokens = TokenEstimator.estimate(content);
            if (protectedTokens < p.protectRecentTokens()) {
                protectedTokens += tokens;
                continue;
            }
            toClear.add(i);
            prunableTokens += tokens;
            if (shrinkCap > 0 && content.length() > shrinkCap && !content.contains(SPLICE_MARKER_FRAGMENT)) {
                toShrink.add(i);
                shrinkableTokens += Math.max(0, tokens - TokenEstimator.estimate(splice(content, shrinkCap)));
            }
        }
        if (shrinkableTokens >= p.minPruneTokens()) {
            final var out = new ArrayList<Message>(messages);
            for (final var i : toShrink) {
                final var m = messages.get(i);
                out.set(
                        i,
                        new Message(
                                m.role(),
                                splice(str(m.content()), shrinkCap),
                                m.name(),
                                m.toolCalls(),
                                m.toolCallId(),
                                m.others()));
            }
            final var shrunkCount = toShrink.size();
            final var reclaimedTokens = shrinkableTokens;
            LOGGER.fine(() -> "prune shrunk " + shrunkCount + " tool output(s) (~" + reclaimedTokens + " tokens)");
            return new CompactionResult(List.copyOf(out), Mode.SHRUNK, "");
        }
        if (prunableTokens < p.minPruneTokens()) {
            return new CompactionResult(messages, Mode.NONE, "");
        }
        final var out = new ArrayList<Message>(messages);
        for (final var i : toClear) {
            final var cleared = messages.get(i);
            // keep the failure semantics visible: a cleared error must not look like a success
            final var failed = cleared.others() != null
                    && Boolean.TRUE.equals(cleared.others().get("is_error"));
            out.set(
                    i,
                    new Message(
                            "tool",
                            failed ? PRUNE_CLEARED_FAILED_MARKER : PRUNE_CLEARED_MARKER,
                            null,
                            null,
                            cleared.toolCallId(),
                            failed ? Map.of("is_error", true) : null));
        }
        final var clearedCount = toClear.size();
        final var clearedTokens = prunableTokens;
        LOGGER.fine(() -> "prune cleared " + clearedCount + " tool output(s) (~" + clearedTokens + " tokens)");
        return new CompactionResult(List.copyOf(out), Mode.CLEARED, "");
    }

    /**
     * Compacts older messages into a structured summary, retrying then degrading to a local
     * truncation. Never returns the input unchanged as a silent no-op.
     *
     * @param force whether to force compaction even below the thresholds (tighter keep floors and a
     *              halved tail budget, as used by overflow recovery)
     * @param focus optional hint folded into the summarizer prompt (model-managed compaction)
     * @param keepLastOverride optional minimum number of recent messages to keep verbatim (0 = default)
     */
    public CompactionResult compact(
            final List<Message> messages, final boolean force, final String focus, final int keepLastOverride) {
        // no interrupt flag on this path (a synchronous /compact, a tool directive): the
        // cancellation probe is a real never-true supplier rather than null
        return compact(messages, force, focus, keepLastOverride, Cancellations.NEVER, ChatSettings.EMPTY);
    }

    /** Captures immutable source and resolved summarizer inputs on the foreground thread. */
    public Optional<SpeculativeCompaction.Prepared> prepareSpeculation(
            final List<Message> messages, final ChatSettings settings, final int extraToolTokens) {
        final var cutoff = eligibleCutoff(messages);
        final var from = leadingSystemMessages(messages).size();
        if (cutoff <= from) {
            return Optional.empty();
        }
        try {
            final var prefix = SpeculativeCompaction.snapshot(messages.subList(0, cutoff));
            final var routed = settings == null ? ChatSettings.EMPTY : settings;
            final var model = compactModelReference(routed);
            final var template = summaryRequest(model, configSystemPrompt(), "", routed);
            final var chunkCap = effectiveChunkCap(model);
            return Optional.of(new SpeculativeCompaction.Prepared(
                    from,
                    prefix,
                    previousSummary(prefix),
                    routed,
                    template,
                    chunkCap,
                    effectiveDigestCap(model),
                    Math.max(1, summarizerMinChunkChars()),
                    usageTracker == null ? 0 : usageTracker.estimateContext(messages, extraToolTokens)));
        } catch (final IllegalArgumentException e) {
            LOGGER.fine("Skipping speculative compaction: unsupported snapshot payload");
            return Optional.empty();
        }
    }

    /** Re-evaluates normal retention, moving backwards rather than splitting a tool round. */
    public int eligibleCutoff(final List<Message> messages) {
        final var from = leadingSystemMessages(messages).size();
        var boundary = splitBoundary(
                messages, from, Math.max(2, configuration.pruning().keepRecent()), false);
        boundary = Math.min(boundary, messages.size());
        while (boundary > from && !safeBoundary(messages, boundary)) {
            boundary--;
        }
        return boundary;
    }

    private static boolean safeBoundary(final List<Message> messages, final int cutoff) {
        if (cutoff < messages.size() && "tool".equals(messages.get(cutoff).role())) {
            return false;
        }
        final var outstanding = new HashSet<String>();
        for (var i = 0; i < cutoff; i++) {
            final var message = messages.get(i);
            if (message.toolCalls() != null) {
                for (final var call : message.toolCalls()) {
                    if (call == null || call.id() == null || !outstanding.add(call.id())) {
                        return false;
                    }
                }
            }
            if ("tool".equals(message.role()) && !outstanding.remove(message.toolCallId())) {
                return false;
            }
        }
        return outstanding.isEmpty();
    }

    /** Runs the blockable hook only on the foreground thread. */
    public boolean hookVetoed() {
        return hookVetoed(null);
    }

    private boolean hookVetoed(final CompactionProgress progress) {
        if (emitter == null) {
            return false;
        }
        final var hook = new HookResult();
        emitter.emit(new CompactionStartingEvent(
                new EventContext(
                        sessionId,
                        progress == null ? null : progress.subagentId(),
                        progress == null ? null : progress.subagentName(),
                        (progress == null ? workdir : progress.workdir()).toString(),
                        null,
                        null,
                        null,
                        null),
                hook));
        return hook.forbidden();
    }

    /** Pure computation with auxiliary accounting, but no foreground events or truncation fallback. */
    public SpeculativeCompaction.Candidate generateSpeculativeSummary(
            final SpeculativeCompaction.Prepared source, final BooleanSupplier cancelled) {
        final var started = System.nanoTime();
        var chunkCap = source.chunkCap();
        var calls = 0;
        // Reuse the same bounded incremental input as the foreground path: the captured prefix snapshot
        // is kept full (prefix/cutoff/compatibility validation still compare the exact source), but the
        // summarizer only sees the post-digest event delta prefixed by the combined prior state - so a
        // speculative candidate and a foreground summary are generated from identical input semantics.
        final var input = summaryInput(source.prefix().subList(source.leadingSystemCount(), source.cutoff()));
        while (!cancelled.getAsBoolean()) {
            final var chunks = chunkCompact(input.events(), chunkCap);
            final var outcome = summarizeReducing(
                    "speculative compaction",
                    configSystemPrompt(),
                    chunks,
                    input.priorState(),
                    null,
                    cancelled,
                    source.settings(),
                    true,
                    source.requestTemplate(),
                    source.digestCap());
            calls += outcome.calls();
            if (cancelled.getAsBoolean()) {
                return null;
            }
            if (!outcome.summary().isBlank()) {
                return new Candidate(
                        source, outcome.summary(), calls, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            if (!outcome.sizeError() || chunkCap <= source.minimumChunkCap()) {
                return null;
            }
            chunkCap = Math.max(source.minimumChunkCap(), chunkCap / 2);
        }
        return null;
    }

    /** Prefix and routing validation before waiting and again immediately before installation. */
    public boolean compatibleSpeculation(
            final SpeculativeCompaction.Prepared source, final List<Message> current, final ChatSettings settings) {
        if (!source.settings().equals(settings == null ? ChatSettings.EMPTY : settings)
                || current.size() < source.cutoff()
                || eligibleCutoff(current) < source.cutoff()
                || !safeBoundary(current, source.cutoff())) {
            return false;
        }
        try {
            return source.prefix().equals(SpeculativeCompaction.snapshot(current.subList(0, source.cutoff())));
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    /** Assembles against the current tail; oversized candidates are rejected, never destructively fitted. */
    public SpeculativeCompaction.Adoption validateAndAssemble(
            final SpeculativeCompaction.Candidate candidate,
            final List<Message> current,
            final int currentEligibleCutoff,
            final int extraToolTokens) {
        final var source = candidate.source();
        SpeculativeCompaction.Rejection rejection = null;
        if (currentEligibleCutoff < source.cutoff()) {
            rejection = SpeculativeCompaction.Rejection.RETENTION_SHRUNK;
        } else if (!compatibleSpeculation(source, current, source.settings())) {
            rejection = SpeculativeCompaction.Rejection.SOURCE_CHANGED;
        } else if (candidate.summary() == null || candidate.summary().isBlank()) {
            rejection = SpeculativeCompaction.Rejection.EMPTY_SUMMARY;
        }
        if (rejection != null) {
            return new SpeculativeCompaction.Adoption(false, null, rejection);
        }
        final var out = assembleSummary(current, source.leadingSystemCount(), source.cutoff(), candidate.summary());
        if (usageTracker == null
                || (long) usageTracker.estimateContext(out) + extraToolTokens > usageTracker.usableWindow()) {
            return new SpeculativeCompaction.Adoption(false, null, SpeculativeCompaction.Rejection.TOO_LARGE);
        }
        if (usageTracker.estimateContext(out) >= usageTracker.estimateContext(current)) {
            return new SpeculativeCompaction.Adoption(false, null, SpeculativeCompaction.Rejection.NO_REDUCTION);
        }
        return new SpeculativeCompaction.Adoption(true, new CompactionResult(List.copyOf(out), Mode.SUMMARY, ""), null);
    }

    private static List<Message> assembleSummary(
            final List<Message> messages, final int from, final int boundary, final String summary) {
        final var out = new ArrayList<Message>();
        out.addAll(messages.subList(0, from));
        // Replace the summarizable region with ONE new digest carrying exactly the merged replacement
        // state. The prior digest state is not re-prepended here: it was fed to the summarizer as the
        // <prior-summary> input and folded by it, so a separate "Previous context:" concatenation would
        // only duplicate text that already lives in the generated summary. The summarizable region is
        // dropped (folded away), so in the digest-only dead-end case repeated /compact force actually
        // reclaims the tool-churn middle, and the new digest sits exactly at `from`, keeping the
        // digestIndex protection of ensureUnderWindow aligned with the real digest position.
        out.add(Message.user(COMPACTED_MARKER + "\n" + summary + "\n" + DIGEST_CLOSE + "\n\n" + DIGEST_GUIDANCE));
        out.addAll(subagentBlocks(messages.subList(from, boundary)));
        out.addAll(messages.subList(boundary, messages.size()));
        final var lastUser = lastUserMessageIndex(messages);
        if (lastUser >= 0 && lastUser < boundary && !out.contains(messages.get(lastUser))) {
            out.add(Message.user(str(messages.get(lastUser).content())));
        }
        return out;
    }

    /**
     * Combines the recognized whole-message {@code <compacted-context>} digest bodies of a region into a
     * single prior-state block (newest last) and returns the remaining ordinary events, with those digest
     * messages removed. Repeated compactions then only re-summarize the delta instead of re-sending the
     * entire transcript or stacking digest texts.
     */
    private static SummaryInput summaryInput(final List<Message> region) {
        final var prior = new StringBuilder();
        final var events = new ArrayList<Message>();
        for (final var m : region) {
            if (isDigestMessage(m)) {
                final var body = digestBody(str(m.content()));
                if (body != null && !body.isBlank()) {
                    if (!prior.isEmpty()) {
                        prior.append('\n');
                    }
                    prior.append(body);
                }
            } else {
                events.add(m);
            }
        }
        return new SummaryInput(prior.isEmpty() ? null : prior.toString(), events);
    }

    /**
     * Whether a message is a <em>complete whole-message</em> digest: a user-role message whose content
     * starts with {@code <compacted-context>} and whose only trailing text after a matching
     * {@code </compacted-context>} is whitespace or the {@link #DIGEST_GUIDANCE}. Anything else - a
     * tool/assistant/prose message, a quoted or inline digest, a malformed or unclosed wrapper - is an
     * ordinary event so real history is never silently dropped on an unproven coverage assumption.
     * Display projections use the same recognition rule to omit internal digests without hiding
     * quoted examples or changing persisted model context.
     */
    public static boolean isDigestMessage(final Message m) {
        if (!"user".equals(m.role()) || !(m.content() instanceof String s)) {
            return false;
        }
        if (!s.startsWith(COMPACTED_MARKER)) {
            return false;
        }
        final var close = s.indexOf(DIGEST_CLOSE);
        if (close < 0) {
            return false; // unclosed wrapper: preserve as an ordinary event
        }
        final var after = s.substring(close + DIGEST_CLOSE.length()).strip();
        return after.isEmpty() || after.equals(DIGEST_GUIDANCE.strip());
    }

    /** The body of a {@code <compacted-context>} message (between the markers), or null when malformed. */
    private static String digestBody(final String content) {
        final var start = content.indexOf(COMPACTED_MARKER);
        final var bodyStart = content.indexOf('\n', start);
        final var end = content.indexOf(DIGEST_CLOSE, start);
        if (bodyStart < 0 || end < 0 || end <= bodyStart) {
            return null;
        }
        return content.substring(bodyStart + 1, end).strip();
    }

    /**
     * Index of a previous {@code <compacted-context>} digest message within {@code [from, boundary)}.
     * When the region's only real user content is that digest (no other user turn), returning it lets
     * {@link #assembleSummary} fold the new summary into the previous one instead of stacking digests.
     * Returns -1 when there is no digest, or when a real (non-digest) user message exists in the region.
     */
    private static int digestMessageIndex(final List<Message> messages, final int from, final int boundary) {
        var digest = -1;
        for (var i = from; i < boundary; i++) {
            final var m = messages.get(i);
            if (!"user".equals(m.role()) || !(m.content() instanceof String s)) {
                continue;
            }
            if (s.startsWith(COMPACTED_MARKER)) {
                if (digest < 0) {
                    digest = i;
                }
                continue;
            }
            if (s.startsWith("<pruned-context>") || s.startsWith("Your previous reply")) {
                continue; // structural markers, not a real user turn
            }
            return -1; // a real user message in the region: normal stacking path
        }
        return digest;
    }

    /**
     * @param settings the requesting session's selection (model/provider for the summarizer); null or
     *                 empty means the process default provider and model resolve the compact model
     */
    public CompactionResult compact(
            final List<Message> messages,
            final boolean force,
            final String focus,
            final int keepLastOverride,
            final BooleanSupplier cancelled,
            final ChatSettings settings) {
        return compact(messages, force, focus, keepLastOverride, false, cancelled, settings);
    }

    /**
     * The per-invocation progress hook of a compaction: the wire tool-schema token estimate to fold
     * into the effective-context measurement the progress notice prints, plus an optional consumer
     * that receives the exact same calibrated estimate so the caller can publish it as a fresh
     * {@link io.yupiik.ia.agent.core.agent.AgentEvent.UsageUpdated} (keeping the frontends' context
     * gauge aligned with the notice). {@code null} keeps the legacy behavior: the notice computes the
     * estimate with zero extra tokens and no usage publication happens.
     *
     * @param extraToolTokens token contribution of the wire tool schemas injected into every request
     * @param tokensPublished receives the effective estimate used by the notice, right before it is
     *                        emitted; may be null to publish nothing
     * @param subagentId the invocation's child id, or null for the parent
     * @param subagentName the child's display name, or null for the parent
     * @param workdir required immutable, absolute, normalized invocation root (including child overrides)
     */
    public record CompactionProgress(
            int extraToolTokens, IntConsumer tokensPublished, String subagentId, String subagentName, Path workdir) {
        public CompactionProgress {
            Objects.requireNonNull(workdir, "workdir");
            if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
                throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
            }
        }
    }

    /**
     * Compacts a context, with an {@code urgent} flag: a manual, user-requested compaction that
     * trades the non-destructive contract for guaranteed progress. When the summarizer fails
     * transiently (after all retries and the optional main-model fallback), a normal compaction
     * skips the pass and keeps the raw context; an urgent one falls back to the destructive local
     * truncation instead, so a user explicitly asking to reclaim context near the window edge is
     * never left stuck above the budget by a flaky provider.
     */
    public CompactionResult compact(
            final List<Message> messages,
            final boolean force,
            final String focus,
            final int keepLastOverride,
            final boolean urgent,
            final BooleanSupplier cancelled,
            final ChatSettings settings) {
        return compact(messages, force, focus, keepLastOverride, urgent, cancelled, settings, null);
    }

    /**
     * {@link #compact(List, boolean, String, int, BooleanSupplier, ChatSettings)} with a
     * {@link CompactionProgress} hook: when the summarizer pass actually starts, the effective
     * context (including the caller's wire tool tokens) is computed once, published through the
     * hook and printed by the progress notice, so the frontends' gauge and the notice stay aligned.
     */
    public CompactionResult compact(
            final List<Message> messages,
            final boolean force,
            final String focus,
            final int keepLastOverride,
            final boolean urgent,
            final BooleanSupplier cancelled,
            final ChatSettings settings,
            final CompactionProgress progress) {
        Objects.requireNonNull(cancelled, "cancelled");
        final var id = "compaction-" + UUID.randomUUID();
        var status = CompactionPass.Status.FAILED;
        var mode = Mode.NONE;
        var detail = "Compaction failed; context left unchanged.";
        try {
            emitPass(id, CompactionPass.Status.STARTED, null, null, progress);
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                status = CompactionPass.Status.CANCELLED;
                detail = "Compaction cancelled; context left unchanged.";
                return new CompactionResult(messages, Mode.NONE, "cancelled");
            }
            if (hookVetoed(progress)) {
                status = CompactionPass.Status.SKIPPED;
                detail = "Compaction skipped by hook; context left unchanged.";
                return new CompactionResult(messages, Mode.NONE, "compaction vetoed by hook");
            }
            final var result =
                    compactPass(messages, force, focus, keepLastOverride, urgent, cancelled, settings, progress);
            mode = result.mode();
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                status = CompactionPass.Status.CANCELLED;
                detail = "Compaction cancelled; context left unchanged.";
                mode = Mode.NONE;
                return new CompactionResult(messages, Mode.NONE, "cancelled");
            }
            if (result.changed()) {
                status = CompactionPass.Status.SUCCEEDED;
                detail = "Context compacted (" + result.mode().name() + ").";
            } else if (result.error() == null || result.error().isBlank()) {
                status = CompactionPass.Status.SKIPPED;
                detail = "Compaction skipped; nothing to reclaim.";
            }
            return result;
        } catch (final RuntimeException | Error failure) {
            if (failure instanceof CancellationException
                    || Thread.currentThread().isInterrupted()) {
                status = CompactionPass.Status.CANCELLED;
                detail = "Compaction cancelled; context left unchanged.";
            }
            throw failure;
        } finally {
            emitPass(id, status, mode, detail, progress);
        }
    }

    private void emitPass(
            final String id,
            final CompactionPass.Status status,
            final Mode mode,
            final String detail,
            final CompactionProgress progress) {
        if (emitter != null) {
            emitter.emit(new CompactionPass(
                    id,
                    status,
                    mode,
                    detail,
                    sessionId,
                    progress == null ? null : progress.subagentId(),
                    progress == null ? null : progress.subagentName(),
                    progress == null ? workdir : progress.workdir()));
        }
    }

    private CompactionResult compactPass(
            final List<Message> messages,
            final boolean force,
            final String focus,
            final int keepLastOverride,
            final boolean urgent,
            final BooleanSupplier cancelled,
            final ChatSettings settings,
            final CompactionProgress progress) {
        final var p = configuration.pruning();
        final var keepFloor =
                Math.max(2, keepLastOverride > 0 ? keepLastOverride : force ? p.keepRecent() / 2 : p.keepRecent());
        final var leadingSystem = leadingSystemMessages(messages);
        final var from = leadingSystem.size();
        final var boundary = splitBoundary(messages, from, keepFloor, force);
        if (boundary <= from) {
            return new CompactionResult(messages, Mode.NONE, "");
        }
        if (cancelled.getAsBoolean()) {
            return new CompactionResult(messages, Mode.NONE, "cancelled");
        }
        // LLM-free compaction: the caller (global or per-provider config) opted compaction out of
        // any summarizer call. Run the local cascade (prune + splice spill, tight only for
        // urgent/force) instead of the summarizer path below; same trigger/window budgets, same
        // gate and overflow safety nets keep working because the result lands under the same target.
        if (ignoreLlm(settings)) {
            return localCompact(messages, force, urgent, settings);
        }
        final var summaryInput = summaryInput(messages.subList(from, boundary));
        final var compactCfg = configuration.compaction();
        var chunkCap = effectiveChunkCap(compactModelReference(settings));
        var halvings = 0;
        var lastSizeError = (String) null;
        // Nothing new to summarize: a repeated compaction over an already-fully-digested region (only a
        // recognized digest, no ordinary events after it) would call the summarizer to reproduce what is
        // already folded. Skip the paid call and keep raw messages; the within-budget/trigger logic stays
        // untouched so a genuinely oversized tail still reaches local truncation below.
        if (summaryInput.events().isEmpty()) {
            return new CompactionResult(messages, Mode.NONE, "");
        }
        // user-visible lifecycle feedback: an INFO notice right before the first summarizer call, so
        // the UI can pin a "compacting..." indicator while the (potentially slow) LLM pass runs
        if (usageTracker != null) {
            // effectiveContext is the same calibrated measurement as the usage gauge
            // (anchored to the last real provider-billed prompt tokens when available), so the
            // notice no longer over-reports against the gauge with the raw conservative upper
            // bound; without an anchor it falls back to that raw estimate automatically. The wire
            // tool schemas are never part of the message list but consume context window, so the
            // per-invocation progress hook folds them in; the same int is then published through
            // tokensPublished (if any) so the gauge flips to the exact figure printed here.
            final var effective =
                    usageTracker.effectiveContext(messages, progress == null ? 0 : progress.extraToolTokens());
            if (progress != null && progress.tokensPublished() != null) {
                progress.tokensPublished().accept(effective);
            }
            emit(AgentEvent.Notice.compactionStart(
                    "Compaction in progress... (\u2248" + effective + " estimated tokens; "
                            + usageTracker.lastContextMeasurement().source() + ")",
                    sessionId,
                    workdir));
        }
        Outcome outcome;
        while (true) {
            final var chunks = chunkCompact(summaryInput.events(), chunkCap);
            outcome = summarizeReducing(
                    "compaction",
                    configSystemPrompt(),
                    chunks,
                    summaryInput.priorState(),
                    focus,
                    chunkCap,
                    cancelled,
                    settings,
                    false);
            if (!outcome.sizeError()) {
                break;
            }
            // a size-caused failure means the chunk cap is too optimistic for the effective model
            // window: halve it down to the floor and restart from the prior summary (the partial
            // digest was built at the wrong granularity and is discarded)
            final var floor = Math.max(1, compactCfg.resolvedSummarizer().minChunkChars());
            lastSizeError = outcome.error();
            if (chunkCap <= floor) {
                break;
            }
            halvings++;
            chunkCap = Math.max(floor, chunkCap / 2);
            emit(new AgentEvent.Notice(
                    AgentEvent.Notice.Level.WARN,
                    "Compaction exceeded the summarizer context window; retrying with chunks of ~" + chunkCap
                            + " chars.",
                    sessionId,
                    workdir));
        }
        // Cancellation is not summarizer failure and must never trigger destructive truncation.
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return new CompactionResult(messages, Mode.NONE, "cancelled");
        }
        if (!outcome.summary().isBlank()) {
            List<Message> out = assembleSummary(messages, from, boundary, outcome.summary());
            final var digestIndex = leadingSystem.size();
            // an automatic compaction must land BELOW the trigger (real headroom), not just under the
            // hard usable window; urgent/forced compactions only need to fit the window
            out = ensureUnderWindow(
                    out, urgent || force ? usableWindowTokens() : compactionTriggerTokens(), force, digestIndex);
            if (halvings == 0
                    && configuration.compaction().resolvedSummarizer().chunkChars() == 0
                    && compactName(configuration.model().compact()) == null) {
                emit(AgentEvent.Notice.compacted(
                        "Compacted the context in " + (outcome.calls() >= 1 ? outcome.calls() : 1)
                                + " summarizer call(s) using a window-derived chunk size.",
                        sessionId,
                        workdir));
            }
            // success feedback: the percentage reclaimed, or the token delta when the gain is below
            // one point (percentages would round to a no-op "0% -> 0%" readout)
            if (usageTracker != null) {
                final var afterEstimate = usageTracker.estimateContext(out);
                final var beforePercent = usageTracker.contextPercent(usageTracker.estimateContext(messages));
                final var afterPercent = usageTracker.contextPercent(afterEstimate);
                final var deltaTokens = usageTracker.estimateContext(messages) - afterEstimate;
                final String compactedMessage;
                if (deltaTokens > 0 && Math.abs(beforePercent - afterPercent) < 1.0) {
                    compactedMessage = "Compacted: reclaimed ~" + deltaTokens + " tokens";
                } else {
                    compactedMessage = "Compacted: context " + Math.round(beforePercent) + "% -> "
                            + Math.round(afterPercent) + "%";
                }
                emit(AgentEvent.Notice.compacted(compactedMessage, sessionId, workdir));
            }
            return new CompactionResult(out, Mode.SUMMARY, "");
        }
        // a transient summarizer failure (5xx/network after all retries and the optional main-model
        // fallback) must not destructively truncate a heavy context: skip the pass and keep the raw
        // messages so the next automatic compaction can retry from a clean state. An urgent
        // user-requested compaction opts out of this contract and falls through to the destructive
        // truncation below so it always reclaims context.
        if (outcome.transientFailure() && !outcome.sizeError() && !urgent) {
            final var failureMessage =
                    outcome.error() == null || outcome.error().isBlank() ? "Summary failed" : outcome.error();
            LOGGER.log(SessionAwareLogRecord.of(
                    LOGGER,
                    sessionId,
                    Level.WARNING,
                    failureMessage + "; compaction skipped, context kept raw (will retry next pass)"));
            emit(new AgentEvent.Notice(
                    AgentEvent.Notice.Level.WARN,
                    failureMessage + "; compaction skipped, context kept raw (will retry next pass). Run /compact "
                            + "to retry now, or /compact urgent to force local truncation.",
                    sessionId,
                    workdir));
            return new CompactionResult(messages, Mode.NONE, failureMessage + " (compaction skipped)");
        }
        final var truncated = localTruncate(messages, from, boundary);
        final var keptTruncated = ensureUnderWindow(
                truncated, urgent || force ? usableWindowTokens() : compactionTriggerTokens(), force, -1);
        final var failureMessage = (outcome.error() == null || outcome.error().isBlank()
                        ? (lastSizeError != null ? lastSizeError : "Summary failed")
                        : outcome.error())
                + (halvings > 0 ? " after " + halvings + " chunk-size reduction(s)" : "");
        LOGGER.log(SessionAwareLogRecord.of(
                LOGGER,
                sessionId,
                Level.WARNING,
                failureMessage.isBlank() ? "compaction failed; truncated locally" : failureMessage));
        emit(new AgentEvent.Notice(
                AgentEvent.Notice.Level.WARN,
                (failureMessage.isBlank() ? "Summary failed; context truncated locally." : failureMessage)
                        + " Run /compact to retry the summarizer, or /compact urgent to force truncation.",
                sessionId,
                workdir));
        if (keptTruncated.equals(messages)) {
            return new CompactionResult(messages, Mode.NONE, failureMessage);
        }
        return new CompactionResult(keptTruncated, Mode.TRUNCATE, failureMessage);
    }

    /**
     * The LLM-free compaction cascade (used when {@link #ignoreLlm(ChatSettings)} is true): a bounded
     * sequence of zero-LLM strategies, each reusing the existing reduction machinery, targeting the
     * same budget as the summarizer path (the compaction trigger for automatic passes, the usable
     * window for urgent/overflow passes). Never calls the summarizer.
     *
     * <p>Pass 1 is the {@link #prune(List)} of old tool outputs (shrink to {@code shrinkOutputChars},
     * clear to the {@code <pruned-context>} marker once {@code minPruneTokens} are reclaimable) - the
     * best token-per-information ratio. Pass 2 is the {@link #localSpill(List, int)} head+tail splice
     * of every over-cap message toward the target - the workhorse. Pass 3 is a tighter splice (halved
     * cap) only when {@code urgent} or {@code force} (overflow recovery/tightening), mirroring the
     * tight digest cap of the summarizer path. The mode is the best of the applied passes via the
     * {@code CLEARED < TRUNCATE < SHRUNK < SUMMARY} ranking (a summary is never produced, so SUMMARY
     * is unreachable here).
     */
    private CompactionResult localCompact(
            final List<Message> messages, final boolean force, final boolean urgent, final ChatSettings settings) {
        final var target = urgent || force ? usableWindowTokens() : compactionTriggerTokens();
        final var applied = new ArrayList<String>(3);
        var best = Mode.NONE;
        var list = messages;
        // Pass 1: old tool-output prune. prune() recomputes its own boundary from keepRecent; keep it
        // conservative here (same protectTurns/keepRecent as an automatic prune) so the structural
        // passes that already ran at the checkpoint are not undone by pruning recent turns.
        final var pruned = prune(list);
        if (pruned.changed()) {
            list = pruned.messages();
            applied.add("prune");
            best = pruned.mode();
        }
        if (usageTracker != null && usageTracker.estimateContext(list) > target) {
            // Pass 2: the spill workhorse toward the target.
            final var spilled = localSpill(list, target);
            if (spilled.changed()) {
                list = spilled.messages();
                applied.add("spill");
                best = spilled.mode();
            }
            // Final guarantee, mirroring the summarizer path: whatever the passes left, fit the
            // target (tight = halved splice cap for urgent/overflow passes, exactly like the
            // summarizer path's tight digest cap). Nothing to do when already under target.
            final var fitted = ensureUnderWindow(list, target, urgent || force, -1);
            if (fitted != list) {
                list = fitted;
                applied.add("tighten");
                best = Mode.SHRUNK;
            }
        }
        if (applied.isEmpty()) {
            return new CompactionResult(messages, Mode.NONE, "");
        }
        final var label = "local-only compaction: " + String.join(", ", applied);
        if (usageTracker != null) {
            final var estimate = list;
            LOGGER.fine(() -> label + " (" + usageTracker.estimateContext(estimate) + " est. tokens)");
        }
        return new CompactionResult(list, best, label);
    }

    /**
     * First index of the verbatim tail: at least {@code keepFloor} recent messages, adjusted so the
     * tail never starts on tool results orphaned from their assistant tool call, then extended
     * backwards at whole user-turn boundaries while they fit the tail token budget (halved when
     * {@code halveBudget} is set, ie. overflow recovery).
     */
    private int splitBoundary(
            final List<Message> messages, final int from, final int keepFloor, final boolean halveBudget) {
        final var size = messages.size();
        if (size <= from + keepFloor + 2) {
            // Digest-only dead-end rescue: when the whole non-system region is a previous
            // <compacted-context> digest followed by a long tool-churn turn (no real user turn to
            // summarize against), the strict budget walk would return `from` (Mode.NONE) forever and
            // repeated /compact force can never reclaim the tool-churn middle. Cut at the first
            // safeBoundary tool-call/result pair so the fold logic in assembleSummary merges the new
            // summary into the previous digest. Only applies when a digest is actually present: any
            // other small history stays a genuine no-op (Mode.NONE untouched).
            if (digestMessageIndex(messages, from, size) >= 0) {
                return fallbackSafeBoundary(messages, from, keepFloor);
            }
            return from;
        }
        var b = size - keepFloor;
        while (b < size && "tool".equals(messages.get(b).role())) {
            b++;
        }
        if (usageTracker == null) {
            return b;
        }
        final var budget = halveBudget ? tailBudgetTokens() / 2 : tailBudgetTokens();
        var acc = 0;
        while (b > from) {
            var t = b - 1;
            while (t > from && !"user".equals(messages.get(t).role())) {
                t--;
            }
            final var turnTokens = usageTracker.estimateContext(messages.subList(t, b));
            if (acc + turnTokens > budget) {
                break;
            }
            acc += turnTokens;
            b = t;
            if (acc >= budget) {
                break;
            }
        }
        return Math.max(from, b);
    }

    /**
     * Digest-only dead-end rescue: when the strict budget walk cannot move the boundary past
     * {@code from} (only a previous {@code <compacted-context>} digest then a long tool-churn turn),
     * cut at the first safeBoundary tool-call/result pair so repeated {@code /compact} (force)
     * actually reduces the tool-churn middle instead of returning {@link Mode#NONE} forever. The
     * fold logic in {@link #assembleSummary} then merges the new digest into the previous one.
     */
    private int fallbackSafeBoundary(final List<Message> messages, final int from, final int keepFloor) {
        final var size = messages.size();
        if (size <= from + 2) {
            return from; // too small to cut anything
        }
        var boundary = size - keepFloor;
        if (boundary <= from) {
            boundary = from + 1;
        }
        while (boundary < size && !safeBoundary(messages, boundary)) {
            boundary++;
        }
        if (boundary >= size || boundary <= from) {
            return from; // no safe pair in the whole list: genuinely non-compactable
        }
        // at least the pair [from, boundary) is summarizable (the digest or the tool round)
        return boundary;
    }

    private int tailBudgetTokens() {
        final var configured = configuration.compaction().resolvedTail().budgetTokens();
        if (configured > 0) {
            return configured;
        }
        final var usable = usageTracker == null ? 32_000 : usageTracker.usableWindow();
        return Math.clamp(usable / 4, 2_000, 15_000);
    }

    /**
     * Summary text of the latest {@code <compacted-context>} message in the list, to re-anchor a
     * repeated compaction on instead of blindly re-summarizing from scratch; {@code null} when none.
     */
    private static String previousSummary(final List<Message> messages) {
        for (var i = messages.size() - 1; i >= 1; i--) {
            final var content = messages.get(i).content() instanceof String s ? s : null;
            if (content == null) {
                continue;
            }
            final var start = content.indexOf(COMPACTED_MARKER);
            if (start < 0) {
                continue;
            }
            final var bodyStart = content.indexOf('\n', start);
            final var end = content.indexOf("</compacted-context>", start);
            if (bodyStart < 0 || end < 0 || end <= bodyStart) {
                continue;
            }
            return content.substring(bodyStart + 1, end).strip();
        }
        return null;
    }

    /**
     * The delivered {@code <subagent ...>...</subagent>} user messages in a region, in order. Kept
     * verbatim through compaction so a sub-agent's outcome survives the summarizer (a high-recall
     * anchor: never paraphrased away, only pruned by {@code purgeDeliveredSubagents} once old).
     */
    private static List<Message> subagentBlocks(final List<Message> region) {
        final var out = new ArrayList<Message>();
        for (final var m : region) {
            final var content = m.content() instanceof String s ? s : null;
            if (m.role() != null && "user".equals(m.role()) && content != null && content.startsWith("<subagent ")) {
                out.add(m);
            }
        }
        return out;
    }

    /** Index of the last real user message (skipping compaction digests and truncation nudges), or -1. */
    private static int lastUserMessageIndex(final List<Message> messages) {
        for (var i = messages.size() - 1; i >= 0; i--) {
            final var m = messages.get(i);
            if (!"user".equals(m.role()) || !(m.content() instanceof String s)) {
                continue;
            }
            if (s.startsWith(COMPACTED_MARKER)
                    || s.startsWith("<pruned-context>")
                    || s.startsWith("<subagent ")
                    || (m.others() != null && Boolean.TRUE.equals(m.others().get("volatileTail")))
                    || s.startsWith("Your previous reply")) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /**
     * Guarantees the compacted message list fits under a target budget. The kept raw messages are
     * normally preserved verbatim; when the kept tail alone is heavy or the target is small, it is
     * progressively spilled: tool outputs are capped, retried until the list is within budget but at least
     * once shrunk. The {@code digestIndex} message (the generated summary) is never spoken to, so the
     * compacted context survives the size-fitting unchanged. This closes the gap where compaction
     * "succeeded" but the output still overflowed.
     *
     * @param targetTokens the budget the returned list must not exceed. Automatic compactions pass the
     *                     compaction trigger (so one pass buys real headroom below the wall and the
     *                     once-per-turn recovery rarely needs to fire); urgent/overflow compactions pass
     *                     the usable window (the hard input limit).
     */
    private List<Message> ensureUnderWindow(
            final List<Message> out, final int targetTokens, final boolean tight, final int digestIndex) {
        if (usageTracker == null) {
            return out;
        }
        var list = out;
        final var cap = tight ? effectiveSpliceChars() / 2 : effectiveSpliceChars();
        for (var guard = 0; guard < 8; guard++) {
            if (usageTracker.estimateContext(list) <= targetTokens) {
                break;
            }
            final var next = shrinkTail(list, cap, digestIndex);
            if (next == list) {
                break;
            }
            list = next;
        }
        return list;
    }

    /**
     * Centrally absorbs overflow: any message whose content exceeds {@code cap} is head+tail
     * spliced. All leading system messages, the latest real user request and the generated digest
     * ({@code digestIndex}, when present and &gt; 0) are never touched. Returns the same
     * list when nothing changed.
     */
    private List<Message> shrinkTail(final List<Message> list, final int cap, final int digestIndex) {
        var changed = false;
        final var out = new ArrayList<Message>();
        final var lastUser = lastUserMessageIndex(list);
        var leadingSystem = true;
        for (var i = 0; i < list.size(); i++) {
            final var m = list.get(i);
            leadingSystem = leadingSystem && "system".equals(m.role());
            if (leadingSystem || i == digestIndex || i == lastUser) {
                out.add(m);
                continue;
            }
            final var content = m.content() instanceof String s ? s : String.valueOf(m.content());
            if (content.length() > cap) {
                final var spliced = splice(content, cap);
                out.add(new Message(m.role(), spliced, m.name(), m.toolCalls(), m.toolCallId(), m.others()));
                changed = true;
            } else {
                out.add(m);
            }
        }
        return changed ? out : list;
    }

    /** Keeps the head and tail of a long string, eliding the middle. */
    private static String splice(final String content, final int cap) {
        if (content.length() <= cap) {
            return content;
        }
        final var half = cap / 2;
        return content.substring(0, half) + "\n[... " + (content.length() - cap) + " chars elided ...]\n"
                + content.substring(content.length() - half);
    }

    /** Renders a message for the summarizer input, capping tool outputs at the configured summarizer tool-output cap. */
    private String renderForSummary(final Message m) {
        final var cap = effectiveToolOutputChars();
        final var content = str(m.content());
        final var truncated = content.length() > cap ? content.substring(0, cap) + " [truncated]" : content;
        if ("tool".equals(m.role())) {
            final var failed =
                    m.others() != null && Boolean.TRUE.equals(m.others().get("is_error"));
            return (failed ? "[Tool result FAILED]: " : "[Tool result]: ") + truncated;
        }
        if ("assistant".equals(m.role())
                && m.toolCalls() != null
                && !m.toolCalls().isEmpty()) {
            final var sb = new StringBuilder("[Assistant]: ").append(truncated);
            for (final var tc : m.toolCalls()) {
                if (tc.function() != null) {
                    sb.append("\n[Assistant tool call]: ")
                            .append(tc.function().name())
                            .append('(')
                            .append(capArguments(tc.function().arguments()))
                            .append(')');
                }
            }
            return sb.toString();
        }
        return '[' + m.role() + "] " + truncated;
    }

    /**
     * Head+tail splice of a tool call argument blob, honoring the configured cap
     * ({@code yca.compaction.toolArgumentsCapChars}, 0 = no cap). The full arguments are never
     * serialized into the summarizer input: they dominate it (a single bash command can carry tens
     * of thousands of characters) and would multiply the rolling-summary LLM calls for no digest
     * value - the exact payload stays available in the verbatim tail for recent calls.
     */
    private String capArguments(final String arguments) {
        final var cap = configuration.compaction().resolvedTail().toolArgumentsCapChars();
        if (cap <= 0 || arguments == null || arguments.isBlank() || arguments.length() <= cap) {
            return arguments;
        }
        return splice(arguments, cap);
    }

    /** Flattens old compact messages into chunks, each bounded by the effective chunk cap. */
    List<String> chunkCompact(final List<Message> old, final int chunkCap) {
        final var chunks = new ArrayList<String>();
        final var current = new StringBuilder();
        for (final var m : old) {
            final var line = renderForSummary(m) + '\n';
            if (line.length() > chunkCap) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.add(line);
            } else if (current.length() + line.length() > chunkCap) {
                chunks.add(current.toString());
                current.setLength(0);
                current.append(line);
            } else {
                current.append(line);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /**
     * Summarizes chunked history with a rolling digest, retrying each call with backoff.
     * When multiple chunks must be summarized, each chunk (including the running digest) is passed
     * separately (the digest never exceeds {@code digestCap}) so no single call ever exceeds the
     * compact model's input window. A single chunk is a single LLM call: the common large-window case.
     */
    private Outcome summarizeReducing(
            final String label,
            final String systemPrompt,
            final List<String> chunks,
            final String priorSummary,
            final String focus,
            final int chunkCap,
            final BooleanSupplier cancelled,
            final ChatSettings settings,
            final boolean quiet) {
        return summarizeReducing(
                label,
                systemPrompt,
                chunks,
                priorSummary,
                focus,
                cancelled,
                settings,
                quiet,
                summaryRequest(compactModelReference(settings), systemPrompt, "", settings),
                effectiveDigestCap(compactModelReference(settings)));
    }

    private ChatCompletionRequest summaryRequest(
            final String model, final String systemPrompt, final String prompt, final ChatSettings settings) {
        final var routed = settings == null ? ChatSettings.EMPTY : settings;
        final var tuning = providerManager == null ? null : providerManager.resolvedBackgroundTuning(routed, "compact");
        var request = new ChatCompletionRequest(
                        model,
                        List.of(Message.system(systemPrompt), Message.user(prompt)),
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null)
                .withSessionId(sessionId)
                .withSettings(routed);
        // Apply the resolved compact tuning first (put-if-absent) so any explicit operator value wins,
        // then default only the fields the tuning left unset.
        request = request.withTuning(tuning);
        if (request.maxTokens() == null) {
            // A finite default output budget when nothing configured one. Independent of the
            // character-based digest caps (digestChars bounds retained text, not generated tokens).
            request = new ChatCompletionRequest(
                    request.model(),
                    request.messages(),
                    request.temperature(),
                    request.topP(),
                    request.stream(),
                    request.tools(),
                    request.toolChoice(),
                    DEFAULT_SUMMARIZER_MAX_TOKENS,
                    request.reasoningEffort(),
                    request.others(),
                    request.sessionId(),
                    request.settings());
        }
        if ((request.reasoningEffort() == null || request.reasoningEffort().isBlank())
                && request.others() != null
                && request.others().containsKey("reasoning_effort")) {
            // a raw extra payload already carries an effort; leave it untouched
        } else if (request.reasoningEffort() == null
                || request.reasoningEffort().isBlank()) {
            // Default the reasoning effort to "low" ONLY for a Codex-backed summarizer target; any
            // explicit tuning/variant/config effort already won above. Non-Codex APIs are normalized
            // back to null (a blank effort carries no meaning on the wire) and keep their own defaults -
            // an unsupported effort field must never be forced on the wire.
            if (isCodexTarget(model, routed)) {
                request = new ChatCompletionRequest(
                        request.model(),
                        request.messages(),
                        request.temperature(),
                        request.topP(),
                        request.stream(),
                        request.tools(),
                        request.toolChoice(),
                        request.maxTokens(),
                        "low",
                        request.others(),
                        request.sessionId(),
                        request.settings());
            } else if (request.reasoningEffort() != null) {
                request = new ChatCompletionRequest(
                        request.model(),
                        request.messages(),
                        request.temperature(),
                        request.topP(),
                        request.stream(),
                        request.tools(),
                        request.toolChoice(),
                        request.maxTokens(),
                        null,
                        request.others(),
                        request.sessionId(),
                        request.settings());
            }
        }
        return request;
    }

    /**
     * Whether the summarizer target routes through the Codex dialect. Resolves the compact model
     * reference (explicit or the session's routing) to its provider and reports whether that provider's
     * API/route is {@link ApiFlavor#CODEX}. Falls back to false (never force an effort) when the target
     * cannot be resolved to a provider - so only a concretely Codex-backed summarizer gets the default.
     */
    private boolean isCodexTarget(final String model, final ChatSettings routed) {
        if (providerManager == null) {
            return false;
        }
        final var resolved = providerManager.resolve(model);
        final var provider = resolved.provider();
        if (provider == null) {
            return false;
        }
        final var configured = provider.api();
        if (configured != null && configured != ApiFlavor.AUTO) {
            return configured == ApiFlavor.CODEX;
        }
        return ChatRoutes.detectFlavor(provider.baseUrl()) == ApiFlavor.CODEX;
    }

    private Outcome summarizeReducing(
            final String label,
            final String systemPrompt,
            final List<String> chunks,
            final String priorSummary,
            final String focus,
            final BooleanSupplier cancelled,
            final ChatSettings settings,
            final boolean quiet,
            final ChatCompletionRequest template,
            final int digestCap) {
        final var summary = new StringBuilder();
        var calls = 0;
        if (!quiet) {
            warnOnceWhenNoCompactModel();
            emit(new AgentEvent.Status(label + " (chunk 1/" + chunks.size() + ")...", sessionId, null, null, workdir));
        }
        for (var c = 0; c < chunks.size(); c++) {
            if (cancelled.getAsBoolean()) {
                return new Outcome("", label + " cancelled", calls, false, false);
            }
            final var prompt = chunkPrompt(chunks, c, summary, priorSummary, focus, digestCap);
            String part = null;
            String error = null;
            for (var attempt = 0; attempt < retries(settings); attempt++) {
                try {
                    if (cancelled.getAsBoolean()) {
                        return new Outcome("", label + " cancelled", calls, false, false);
                    }
                    final var request = new ChatCompletionRequest(
                            template.model(),
                            List.of(Message.system(systemPrompt), Message.user(prompt)),
                            template.temperature(),
                            template.topP(),
                            false,
                            null,
                            template.toolChoice(),
                            template.maxTokens(),
                            template.reasoningEffort(),
                            template.others(),
                            template.sessionId(),
                            template.settings());
                    final var res = chatClient.chat(
                            request.withRetryMode(RetryMode.COOPERATIVE),
                            pause -> {
                                if (!quiet) {
                                    emit(new AgentEvent.Pause(
                                            pause.kind() == PauseEvent.Pause.THROTTLE
                                                    ? AgentEvent.Pause.Kind.THROTTLE
                                                    : AgentEvent.Pause.Kind.RETRY,
                                            label + ": " + pause.message(),
                                            pause.durationMs(),
                                            sessionId,
                                            workdir));
                                }
                            },
                            cancelled);
                    calls++;
                    if (usageTracker != null) {
                        usageTracker.recordAuxiliary(res.usage());
                    }
                    part = res.message() == null ? "" : str(res.message().content());
                    if (part != null && !part.isBlank()) {
                        break;
                    }
                    part = null;
                } catch (final ContextWindowExceededException e) {
                    // the chunk (+ rolling digest + template) is larger than the model's real window:
                    // signal the caller to halve the chunk cap and retry from scratch
                    return new Outcome("", label + " summarizer input exceeds the context window", calls, true, false);
                } catch (final IOException e) {
                    if (isSizeCaused(e)) {
                        // a server-side "context window exceeded / max_tokens" rejection (400/413) is the
                        // HTTP mirror of ContextWindowExceededException: the chunk is too large for the
                        // model, so signal the caller to halve the chunk cap rather than burn the retry
                        // budget and skip keep-raw on a deterministic sizing problem
                        return new Outcome(
                                "", label + " summarizer input exceeds the context window", calls, true, false);
                    }
                    error = label + " summarizer failed: " + e.getMessage();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Outcome("", label + " interrupted", calls, false, false);
                }
                if (attempt + 1 < retries(settings)) {
                    if (cancelled.getAsBoolean()) {
                        return new Outcome("", label + " cancelled", calls, false, false);
                    }
                    final var delay = backoff(attempt, settings);
                    if (!quiet) {
                        emit(new AgentEvent.Pause(
                                AgentEvent.Pause.Kind.RETRY,
                                label + " failed, retrying in ~" + (delay / 1000) + "s...",
                                delay,
                                sessionId,
                                workdir));
                    }
                    backoffSleeper.sleep(delay);
                }
            }
            if (part == null || part.isBlank()) {
                if (error == null) {
                    error = label + " produced no summary; truncated locally";
                }
                // a transient summarizer failure (5xx/network after all retries) with the main-model
                // fallback enabled: retry once on the provider's conversation model before giving up
                if (fallbackToMainModel(settings)) {
                    final var mainModel = mainModelReference(settings);
                    if (mainModel != null && !mainModel.equals(template.model())) {
                        if (!quiet) {
                            emit(new AgentEvent.Status(
                                    label + ": summarizer unavailable, retrying on " + mainModel + "...",
                                    sessionId,
                                    null,
                                    null,
                                    workdir));
                        }
                        try {
                            // rebuild the request for the main model instead of copying the compact
                            // model's template: summaryRequest re-resolves the model reference, applies
                            // the conversation-model tuning (a dedicated compact model's tuning would
                            // otherwise size maxTokens/topP/reasoning for the smaller model) and keeps
                            // the session identity (settings/session-id) on the fallback call
                            final var fallbackRequest = summaryRequest(mainModel, systemPrompt, prompt, settings);
                            final var res = chatClient.chat(
                                    fallbackRequest.withRetryMode(RetryMode.COOPERATIVE), null, cancelled);
                            calls++;
                            if (usageTracker != null) {
                                usageTracker.recordAuxiliary(res.usage());
                            }
                            final var fallbackPart = res.message() == null
                                    ? ""
                                    : str(res.message().content());
                            if (fallbackPart != null && !fallbackPart.isBlank()) {
                                // replace, never append: each chunk's successful result is the authoritative
                                // merged replacement state (the summarizer already had the running state in
                                // its input), so the digest reflects the LATEST merge, not an accumulation
                                replaceBounded(summary, fallbackPart, digestCap);
                                if (!quiet && c + 1 < chunks.size()) {
                                    emit(new AgentEvent.Status(
                                            label + " (chunk " + (c + 2) + "/" + chunks.size() + ")...",
                                            sessionId,
                                            null,
                                            null,
                                            workdir));
                                }
                                continue;
                            }
                        } catch (final ContextWindowExceededException e) {
                            // the main model is not smaller than the compact model: a window error here
                            // means the chunk is genuinely too large for any model, treat as size error
                            return new Outcome(
                                    "", label + " summarizer input exceeds the context window", calls, true, false);
                        } catch (final IOException e) {
                            if (isSizeCaused(e)) {
                                // the main model has a (possibly smaller) window too: a sizing rejection
                                // here is deterministic and must halve the chunk cap, not fall through to
                                // the transient skip
                                return new Outcome(
                                        "", label + " summarizer input exceeds the context window", calls, true, false);
                            }
                            error = label + " summarizer failed also on " + mainModel + ": " + e.getMessage();
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return new Outcome("", label + " interrupted", calls, false, false);
                        }
                    }
                }
                return new Outcome("", error, calls, false, true);
            }
            // replace, never append: each chunk's successful result is the authoritative merged
            // replacement state (the running summary was already in the chunk's input), so the digest
            // reflects the LATEST merge instead of accumulating stale lines across chunks
            replaceBounded(summary, part, digestCap);
            if (!quiet && c + 1 < chunks.size()) {
                emit(new AgentEvent.Status(
                        label + " (chunk " + (c + 2) + "/" + chunks.size() + ")...", sessionId, null, null, workdir));
            }
        }
        return new Outcome(summary.toString(), "", calls, false, false);
    }

    /**
     * Replaces the running digest with a single part, clamped to {@code maxChars}. The rolling state is
     * the latest summarizer output (each chunk's merge already had the running state in its input, so a
     * multi-layer accumulation only re-appends stale lines); a too-long part is truncated head-first.
     */
    private static void replaceBounded(final StringBuilder summary, final String part, final int maxChars) {
        summary.setLength(0);
        if (part != null && !part.isBlank()) {
            summary.append(part, 0, Math.min(part.length(), maxChars));
        }
    }

    private static String chunkPrompt(
            final List<String> chunks,
            final int index,
            final StringBuilder rollingSummary,
            final String priorSummary,
            final String focus,
            final int digestCap) {
        final var sb = new StringBuilder();
        if (rollingSummary.isEmpty() && priorSummary != null && !priorSummary.isBlank()) {
            sb.append("Below is the previous summary of this conversation, followed by more conversation.")
                    .append(" Update the previous summary with the new information: the conversation wins on")
                    .append(" conflicts, move finished Active items to Completed, and keep the same structure.\n\n")
                    .append("<prior-summary>\n")
                    .append(priorSummary)
                    .append("\n</prior-summary>\n\nConversation:\n");
        } else if (rollingSummary.isEmpty()) {
            sb.append("Create a new summary of the conversation below.");
        } else {
            final var summaryText = rollingSummary.length() > digestCap
                    ? rollingSummary.substring(rollingSummary.length() - digestCap)
                    : rollingSummary.toString();
            sb.append(
                            "Below is a summary of earlier context, followed by more conversation. Merge them into one concise, lossless summary. Preserve key decisions, file paths, commands, results, current state and next steps.\n\nEarlier context summary:\n")
                    .append("<summary>")
                    .append(summaryText)
                    .append("</summary>");
        }
        sb.append(chunks.get(index));
        if (index + 1 < chunks.size()) {
            sb.append("\n(this is part ")
                    .append(index + 1)
                    .append(" of ")
                    .append(chunks.size())
                    .append("; continue merging)");
        }
        if (focus != null && !focus.isBlank()) {
            sb.append("\n\nExtra focus requested for this summary: ").append(focus.strip());
        }
        return sb.toString();
    }

    /**
     * The stable summarizer system prompt. It carries the full static instruction AND the
     * {@link #SUMMARY_TEMPLATE}, so every summarizer call shares the same byte-identical prefix (a
     * provider-side prompt-cache-friendly invariant) while the user message carries only the varying
     * prior state + new events + focus + chunk metadata. Deliberately stable across calls: no runtime
     * values are injected here.
     */
    private static String configSystemPrompt() {
        return "You are a context summarization agent. Summarize the conversation for another coding agent so it"
                + " can continue the work seamlessly. Do not continue the conversation and never reply to it:"
                + " only output the structured summary. Preserve exact file paths, symbols, commands, error strings"
                + " and URLs. Do not mention the summary process or that context was compacted.\n\n"
                + SUMMARY_TEMPLATE;
    }

    /**
     * Outcome of a summarization run: the generated summary, an error/fallback note, the number of
     * LLM calls spent (for the success INFO feedback) and whether the failure was size-caused (a
     * {@link ContextWindowExceededException}), which triggers the chunk-cap halving restart.
     */
    private record Outcome(String summary, String error, int calls, boolean sizeError, boolean transientFailure) {}

    /**
     * Whether an {@link IOException} from the summarizer call is a context-sizing rejection rather
     * than a transient infrastructure failure. Server-side window rejections surface as
     * {@link LlmHttpFailureException} with a 400/413 status and a body naming a context-length or
     * token budget ("context_length_exceeded", "maximum context length", "max_tokens", "please
     * reduce your prompt", ...). These are deterministic: the chunk is too large for the model's
     * window, so they must drive the chunk-cap halving restart just like a client-side
     * {@link ContextWindowExceededException} - not be retried and then skipped keep-raw, which is
     * what left a heavy context stuck above its budget when the compact model refused oversized
     * chunks.
     */
    private static boolean isSizeCaused(final IOException e) {
        if (!(e instanceof final LlmHttpFailureException httpFailure)) {
            return false;
        }
        final var status = httpFailure.status();
        if (status != 400 && status != 413) {
            return false;
        }
        final var body = httpFailure.body();
        if (body == null || body.isBlank()) {
            return false;
        }
        final var lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("context_length")
                || lower.contains("context length")
                || lower.contains("context window")
                || lower.contains("max_tokens")
                || lower.contains("max tokens")
                || lower.contains("too large")
                || lower.contains("reduce your prompt");
    }

    /** The consecutive leading {@code system}-role entries: the web project marker (if any) and the compaction marker. */
    private static List<Message> leadingSystemMessages(final List<Message> messages) {
        var i = 0;
        while (i < messages.size() && "system".equals(messages.get(i).role())) {
            i++;
        }
        return messages.subList(0, i);
    }

    private List<Message> localTruncate(final List<Message> messages, final int from, final int to) {
        final var out = new ArrayList<Message>();
        // keep every leading structural marker (compaction marker + web project marker) verbatim
        out.addAll(leadingSystemMessages(messages));
        final var cap = configuration.pruning().toolOutputMaxChars();
        for (final var m : messages.subList(from, to)) {
            final var content = m.content() instanceof String s ? s : String.valueOf(m.content());
            out.add(new Message(
                    m.role(),
                    content.length() > cap ? splice(content, cap) : content,
                    m.name(),
                    m.toolCalls(),
                    m.toolCallId(),
                    m.others()));
        }
        out.addAll(messages.subList(to, messages.size()));
        return out;
    }

    private int retries(final ChatSettings settings) {
        final var overrides = compactionOverrides(settings);
        final var configured = overrides != null && overrides.retriesMaxAttempts() > 0
                // per-provider compaction.retriesMaxAttempts wins
                ? overrides.retriesMaxAttempts()
                : configuration.compaction() != null
                                && configuration.compaction().retriesMaxAttempts() > 0
                        // scoped global yca.compaction.retriesMaxAttempts (conversation keeps
                        // yca.llm.retriesMaxAttempts)
                        ? configuration.compaction().retriesMaxAttempts()
                        : (configuration.llm() == null ? 1 : configuration.llm().retriesMaxAttempts());
        return Math.max(1, configured);
    }

    /** The per-provider compaction block for the session's provider, or null when absent/unresolvable. */
    private ProviderCompactionConfiguration compactionOverrides(final ChatSettings settings) {
        if (providerManager == null) {
            return null;
        }
        final var providerId = settings == null ? null : settings.providerId();
        final var provider = providerId == null || providerId.isBlank()
                ? providerManager.current()
                : providerManager.provider(providerId);
        return provider == null ? null : provider.compaction();
    }

    /**
     * The merged buffer-mode trigger knobs for the session's provider (per-provider override over
     * global over default), or the global group when no provider manager is wired.
     */
    public BufferConfiguration resolvedBuffer(final ChatSettings settings) {
        if (providerManager != null) {
            return providerManager.resolvedBuffer(settings);
        }
        return configuration == null || configuration.compaction() == null
                ? new BufferConfiguration(
                        BufferConfiguration.DEFAULT_PERCENT,
                        BufferConfiguration.DEFAULT_MIN_TOKENS,
                        BufferConfiguration.DEFAULT_SAFETY_PERCENT)
                : configuration.compaction().resolvedBuffer();
    }

    /**
     * The merged compaction-gate budgets for the session's provider (per-provider override over
     * global over default), or the global group when no provider manager is wired.
     */
    public GateConfiguration resolvedGate(final ChatSettings settings) {
        if (providerManager != null) {
            return providerManager.resolvedGate(settings);
        }
        return configuration == null || configuration.compaction() == null
                ? new GateConfiguration(
                        GateConfiguration.DEFAULT_MAX_ATTEMPTS, GateConfiguration.DEFAULT_MAX_DURATION_MS)
                : configuration.compaction().resolvedGate();
    }

    private boolean compactionConfigured() {
        return configuration != null && configuration.compaction() != null;
    }

    private int summarizerChunkChars() {
        return compactionConfigured()
                ? configuration.compaction().resolvedSummarizer().chunkChars()
                : 0;
    }

    private int summarizerMinChunkChars() {
        return compactionConfigured()
                ? configuration.compaction().resolvedSummarizer().minChunkChars()
                : 12_000;
    }

    private int summarizerDigestChars() {
        return compactionConfigured()
                ? configuration.compaction().resolvedSummarizer().digestChars()
                : 0;
    }

    private int summarizerToolOutputChars() {
        return compactionConfigured()
                ? configuration.compaction().resolvedSummarizer().toolOutputChars()
                : 2_000;
    }

    private int overflowSpliceChars() {
        return compactionConfigured()
                ? configuration.compaction().resolvedTail().overflowSpliceChars()
                : 500;
    }

    /**
     * The effective chunk cap for a single summarizer call: an explicit
     * {@code yca.compaction.summarizerChunkChars} wins; otherwise a quarter of the compact model's
     * usable window (in chars, ~4 chars/token), floored at the configured minimum so small models
     * stay safe. No upper ceiling: the fraction itself is self-bounding (a quarter of the window can
     * never exceed the window), and a too-optimistic estimate is recovered by the halving retry.
     */
    int effectiveChunkCap() {
        return effectiveChunkCap(null);
    }

    int effectiveChunkCap(final String compactModel) {
        final var explicit = summarizerChunkChars();
        if (explicit > 0) {
            return explicit;
        }
        final var floor = Math.max(1, summarizerMinChunkChars());
        if (usageTracker == null) {
            return floor;
        }
        // size against the summarizer model's own usable window (a smaller background model than the
        // conversation model must not drive a guaranteed summarizer overflow), falling back to the
        // conversation model's window when no dedicated summarizer model is configured
        final var usable = usageTracker.usableWindowForModel(compactModel);
        // ~4 chars per token: the classic code/JSON average; conservative (window is already
        // reduced by the output reserve), and a too-large cap is recovered by the halving retry
        final var derived = (usable / 4L) * 4L;
        return Math.clamp(derived, floor, Integer.MAX_VALUE);
    }

    /**
     * The effective rolling-digest cap: an explicit {@code yca.compaction.summarizerDigestChars}
     * wins; otherwise half the chunk cap (the merge call then carries at most ~1.5x a chunk, well
     * inside the window for any chunk that fits).
     */
    int effectiveDigestCap() {
        return effectiveDigestCap(null);
    }

    int effectiveDigestCap(final String compactModel) {
        final var explicit = summarizerDigestChars();
        if (explicit > 0) {
            return explicit;
        }
        return Math.max(1, effectiveChunkCap(compactModel) / 2);
    }

    /** The per-tool-output cap used when the history is serialized into the summarizer input. */
    int effectiveToolOutputChars() {
        final var cap = summarizerToolOutputChars();
        return cap > 0 ? cap : Integer.MAX_VALUE;
    }

    /** The head+tail cap applied to kept-tail messages when the compacted context still overflows. */
    int effectiveSpliceChars() {
        final var cap = overflowSpliceChars();
        return cap > 0 ? cap : Integer.MAX_VALUE;
    }

    /**
     * LLM-free local reduction toward a target: head+tail splices the pre-digest tool outputs so the
     * estimate drops toward {@code targetTokens}. Never touches the system prompt (index 0), the
     * digest, or the kept-tail semantics. Returns a {@link Mode#SHRUNK} result when something changed,
     * {@link Mode#NONE} otherwise.
     */
    public CompactionResult localSpill(final List<Message> messages, final int targetTokens) {
        if (usageTracker == null || messages == null || messages.isEmpty()) {
            return new CompactionResult(messages == null ? List.of() : messages, Mode.NONE, "");
        }
        final var out = List.copyOf(messages);
        final var digestIndex = leadingSystemMessages(messages).size();
        final var spilled = shrinkTail(out, effectiveSpliceChars(), digestIndex);
        if (spilled == out) {
            return new CompactionResult(out, Mode.NONE, "");
        }
        // if a single splice pass is not enough, keep going (bounded) toward the target
        var list = spilled;
        for (var guard = 0; guard < 8 && usageTracker.estimateContext(list) > targetTokens; guard++) {
            final var next = shrinkTail(list, effectiveSpliceChars(), digestIndex);
            if (next == list) {
                break;
            }
            list = next;
        }
        return new CompactionResult(list, Mode.SHRUNK, "local spill");
    }

    /**
     * The automatic-compaction trigger in estimated prompt tokens: the opencode-style buffer trigger
     * capped at a safety fraction of the usable window (see {@link ContextBudget#compactionTrigger(int, int, int, int, int)}).
     * Mirrors {@code ContextManager#compactionTriggerTokens()} so both surfaces agree; falls back to the
     * usable window when compaction is disabled or the window is unknown.
     */
    int compactionTriggerTokens() {
        if (usageTracker == null) {
            return Integer.MAX_VALUE;
        }
        final var c = configuration.compaction();
        if (c == null || !c.enabled()) {
            return usageTracker.usableWindow();
        }
        if (c.thresholdPercent() > 0) {
            return (int) Math.ceil(usageTracker.usableWindow() * c.thresholdPercent() / 100.0);
        }
        return ContextBudget.compactionTrigger(
                usageTracker.contextWindowTokenCount().value(),
                c.resolvedBuffer().percent(),
                c.resolvedBuffer().minTokens(),
                usageTracker.usableWindow(),
                c.resolvedBuffer().safetyPercent());
    }

    /** The hard usable window (context minus completion headroom); never below the minimum usable window. */
    int usableWindowTokens() {
        return usageTracker == null ? Integer.MAX_VALUE : usageTracker.usableWindow();
    }

    /**
     * Exponential backoff with jitter, additionally dilated by the configured requests-per-minute
     * cap so compaction retries never hammer a rate-limited provider faster than the operator asked.
     */
    private long backoff(final int attempt, final ChatSettings settings) {
        final var overrides = compactionOverrides(settings);
        final var base = overrides != null && overrides.retriesBaseDelayMs() > 0
                ? overrides.retriesBaseDelayMs()
                : (configuration.llm() != null ? configuration.llm().retriesBaseDelayMs() : 500L);
        var exp = base * (1L << Math.min(attempt, 5));
        final var rateLimit = overrides != null && overrides.rateLimitRequestsPerMinute() > 0
                ? overrides.rateLimitRequestsPerMinute()
                : (configuration.llm() != null ? configuration.llm().rateLimitRequestsPerMinute() : 0);
        if (rateLimit > 0) {
            final var perRequestMs = 60_000L / rateLimit;
            exp = Math.max(exp, perRequestMs);
        }
        return exp + ThreadLocalRandom.current().nextLong(Math.max(1, exp / 4));
    }

    /**
     * Resolves the summarizer model reference for a compaction pass: the effective provider's
     * {@code backgroundModelOverrides.compact} (blank = its own default model, "inherit" = the
     * global chain) wins, then the global {@code yca.model.compact}, then the session selection
     * routes the call (its provider, and its model when the session overrides it). Null means
     * "no explicit model": the request settings alone resolve it.
     */
    private String compactModelReference(final ChatSettings settings) {
        if (providerManager != null && configuration != null) {
            final var resolved = providerManager.resolvedCompact(settings);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        } else if (configuration != null
                && configuration.model() != null
                && compactName(configuration.model().compact()) != null) {
            // no provider manager (test wiring): still honor the global compact reference
            return compactName(configuration.model().compact());
        }
        // no background model resolved: use the session's provider/model (kept empty when the
        // selection carries no model override: the effective model of the session applies)
        if (settings == null) {
            return null;
        }
        final var providerId = settings.providerId();
        final var model = settings.model();
        if (model == null || model.isBlank()) {
            return providerId == null ? null : "$" + providerId + "/";
        }
        return (providerId == null ? "" : "$" + providerId + "/") + model;
    }

    /**
     * Whether compaction must never call an LLM for the session's provider: the per-provider
     * {@code compaction.mode} wins when explicitly {@code llm}/{@code no_llm}, else the global
     * {@code yca.compaction.mode} (whose {@code auto} resolves to the built-in {@code llm}).
     * Delegates to {@link ProviderManager#resolvedIgnoreLlm(ChatSettings)} so both surfaces agree.
     */
    boolean ignoreLlm(final ChatSettings settings) {
        if (providerManager != null) {
            return providerManager.resolvedIgnoreLlm(settings);
        }
        final var mode = configuration == null || configuration.compaction() == null
                ? CompactionLlmMode.AUTO
                : configuration.compaction().mode();
        return mode == CompactionLlmMode.NO_LLM;
    }

    /**
     * Whether a transient summarizer failure should retry once on the provider's main model: the
     * global {@code yca.compaction.fallbackToMainModel} default, overridden by the per-provider
     * {@code compaction.fallbackToMainModel} when set. Never falls back for context-size errors
     * (those are handled by the chunk-halving loop) and only makes sense when a dedicated compact
     * model is configured (otherwise the summarizer already runs on the main model).
     */
    private boolean fallbackToMainModel(final ChatSettings settings) {
        final var overrides = compactionOverrides(settings);
        if (overrides != null && overrides.fallbackToMainModel() != null) {
            return overrides.fallbackToMainModel();
        }
        return compactionConfigured() && configuration.compaction().fallbackToMainModel();
    }

    /** The session's main (conversation) model reference, or null when unresolvable. */
    private String mainModelReference(final ChatSettings settings) {
        if (providerManager == null) {
            return null;
        }
        final var routed = settings == null ? ChatSettings.EMPTY : settings;
        final var providerId = routed.providerId();
        final var provider = providerId == null || providerId.isBlank()
                ? providerManager.current()
                : providerManager.provider(providerId);
        if (provider == null || provider.model() == null || provider.model().isBlank()) {
            return null;
        }
        return "$" + (provider.id() == null || provider.id().isBlank() ? "default" : provider.id()) + "/"
                + provider.model();
    }

    /**
     * Surfaces, once per session, that no dedicated compact model is configured: compaction
     * summarizer calls then run on the (usually flagship) conversation model, which works but
     * costs more than a cheap summarizer would. A log hint is enough - a wrong hard-coded
     * default would break compaction entirely, so the fallback stays the current model.
     */
    private void warnOnceWhenNoCompactModel() {
        if (warnedCompactModel || configuration == null) {
            return;
        }
        warnedCompactModel = true;
        final var compact = configuration.model() == null
                ? null
                : compactName(configuration.model().compact());
        if (compact == null) {
            LOGGER.info("No yca.model.compact configured: context compaction summarizes with the current "
                    + "conversation model. Set yca.model.compact to a cheaper model to reduce background spend.");
        }
    }

    private void emit(final AgentEvent event) {
        if (emitter != null) {
            emitter.emit(event);
        }
    }

    private static String str(final Object content) {
        return content instanceof String s ? s : content == null ? "" : content.toString();
    }

    /** The model name carried by a background compact {@link ModelVariantConfiguration}, or null when unset. */
    private static String compactName(final ModelVariantConfiguration v) {
        return v == null || v.name() == null || v.name().isBlank()
                ? null
                : v.name().strip();
    }
}
