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

import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.PlateauFeedbackMode;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.context.CompactionResult;
import io.yupiik.ia.agent.core.context.ContextCompression;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.events.HookResult;
import io.yupiik.ia.agent.core.events.UserPromptSubmitEvent;
import io.yupiik.ia.agent.core.events.hooks.EventContextBuilder;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.ToolCallPairingRepair;
import io.yupiik.ia.agent.core.security.InboundLlmResponse;
import io.yupiik.ia.agent.core.security.OutboundLlmRequest;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.wiring.SessionAwareLogRecord;
import io.yupiik.llm.client.ContextWindowExceededException;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import io.yupiik.llm.client.model.LlmStreamEvent.ReasoningDelta;
import io.yupiik.llm.client.model.LlmStreamEvent.ReasoningItem;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import io.yupiik.llm.client.model.Usage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Turn-execution runtime: the loop window, the round pipeline and the tool-result landing,
 * moved out of {@link AgentLoop}. Holds a reference to its host loop and reads the loop's
 * collaborators through it. The host owns session state shared across successive turns.
 */
public class AgentTurnExecutor {
    private final AgentLoop host;

    public AgentTurnExecutor(final AgentLoop host) {
        this.host = host;
    }

    private boolean acceptInitialPrompt(final String userMessage, final String sessionId) {
        final var result = new HookResult();
        final var context = new EventContextBuilder()
                .build(
                        host.permissionService,
                        host.chatSettings(),
                        sessionId,
                        host.subagentId,
                        host.subagentName,
                        host.sessionWorkdir);
        host.emitter.emit(new UserPromptSubmitEvent(context, userMessage, "user", result));
        if (result.forbidden()) {
            host.emit(AgentEvent.Notice.warn(
                    "Prompt rejected" + (result.reason() == null ? "" : ": " + result.reason()),
                    host.currentSessionId,
                    host.sessionWorkdir));
            return false;
        }
        return true;
    }

    int appendStateIfChanged(
            final String sessionId, final List<Message> messages, final String prompt, final int toolsTokens) {
        // The tail keeps a stable compact shape across every request so the prefix (system +
        // user + tail) stays byte-identical and provider prompt caches keep hitting; only the
        // todo list (delta of what changed) and the usage line move between requests.
        final var tier = host.contextView == null ? 0 : host.compressionNudgeTier(messages, toolsTokens);
        final var state = "<session-state>\n"
                + host.systemPromptBuilder.sessionStateDelta()
                + host.contextUsageLine(messages, toolsTokens)
                + host.nudgeBlock(messages, toolsTokens)
                + host.compressionRefGuidance(messages, tier)
                + host.snapshotBlock(sessionId)
                + "</session-state>";
        // dedup: skip when the newest state tail already carries exactly this state
        for (var i = messages.size() - 1; i >= 0; i--) {
            final var m = messages.get(i);
            if (AgentLoop.isVolatileTail(m)) {
                if (state.equals(String.valueOf(m.content()))) {
                    return tier; // nothing changed since the last tail: keep the prefix byte-stable
                }
                break;
            }
        }
        messages.add(new Message("user", state, null, null, null, Map.of("volatileTail", true)));
        return tier;
    }

    private void appendToolResult(
            final String sessionId,
            final List<Message> messages,
            final ToolOutcome outcome,
            final AgentLoop.StaleReads staleReads,
            final boolean[] roundInformative,
            final boolean[] roundRefused,
            final Set<String> roundCoverage,
            final boolean[] roundMutatingBash) {
        // a refused doom-loop call is NOT delivered as a failed tool result (that shape reads as "the
        // tool errored, retry it" and feeds the very loop we are stopping): it only nudges the model at
        // the round boundary and surfaces a UI event. So skip the conversation/session mutation entirely.
        if (outcome.refused()) {
            if (roundCoverage != null) {
                roundCoverage.add(AgentLoop.INELIGIBLE_EXPLORATION_MARKER);
            }
            if (roundRefused != null) {
                roundRefused[0] = true;
            }
            return;
        }
        final var raw = outcome.output() == null || outcome.output().isBlank() ? "(no output)" : outcome.output();
        // Stale-read invalidation rides INSIDE this tool result (new content this round, so the
        // provider prefix cache is untouched — no extra message is injected mid-conversation).
        // Computed before the message is appended: onLanded scans messages WITHOUT this result.
        final var stalePaths = staleReads.onLanded(messages, outcome);
        if (!AgentLoop.FILE_READ_TOOLS.contains(outcome.name())
                && !outcome.paths().isEmpty()) {
            host.readCoverage.clear();
            if (roundCoverage != null) {
                roundCoverage.add(AgentLoop.WRITE_MARKER);
            }
        }
        final var output = host.capIngestionOutput(stalePaths.isEmpty() ? raw : raw + staleNote(stalePaths));
        final var isError = !outcome.success() && !outcome.pending();
        messages.add(Message.tool(outcome.callId(), output, isError));
        // a round is informative when at least one result is new (same call, different output) or
        // a write succeeded: used by the no-progress termination (P2). Refused doom-loop calls are
        // explicitly non-informative (their diagnostic output would otherwise look "new"), and never
        // reach the repeat-streak tracking so the refusal stays stable.
        if (outcome.informative()) {
            // novelty is computed ONCE by the atomic record() and drives both the round-informative
            // decision and the doom-loop success streak update (a non-novel success bumps the streak,
            // failure or fresh content resets it). Only executed calls land here, hence on the loop thread.
            final var novel = host.repeats.record(outcome.callId(), outcome.success(), output);
            if (outcome.success() && AgentLoop.FILE_WRITE_TOOLS.contains(outcome.name())) {
                roundInformative[0] = true;
            } else if (roundInformative != null && !roundInformative[0]) {
                roundInformative[0] = novel;
            }
        }
        // Only fully classified, successful exploration batches are eligible. An error, pending
        // result or unclassified operation must not disappear beside a redundant read.
        if (roundCoverage != null
                && (!outcome.success()
                        || outcome.pending()
                        || outcome.coverage().isEmpty())) {
            roundCoverage.add(AgentLoop.INELIGIBLE_EXPLORATION_MARKER);
        }
        // Successful read-only calls contribute coverage; writes reset the round.
        if (outcome.success() && roundCoverage != null) {
            if (AgentLoop.FILE_WRITE_TOOLS.contains(outcome.name())) {
                roundCoverage.add(AgentLoop.WRITE_MARKER);
                // Patch tools can touch several paths (including rename/delete targets). Start
                // fresh conservatively rather than retaining coverage of potentially changed data.
                host.readCoverage.clear();
            } else {
                for (final var key : outcome.coverage()) {
                    final var novel = host.readCoverage.supports(key)
                            ? host.readCoverage.record(key, output)
                            : host.seenFiles.add(key);
                    roundCoverage.add(novel ? key : AgentLoop.REDUNDANT_MARKER + key);
                }
            }
        }
        // mutating-bash detection for the exploration-nudge spiral: a bash command that is NOT a recognized
        // read-only family (empty coverage - eg. builds, mvn, npm, git commit, rm) is real work, so it
        // resets the novel-spiral counter instead of counting as exploration. This applies whether it
        // succeeded or failed (a failing build is still the intended work, not exploration). Read-only
        // bash (git log, cat, ls, grep -r) produces coverage keys and is NOT flagged here, so it keeps
        // counting toward the spiral in both build and plan mode.
        if ("bash".equals(outcome.name())
                && roundMutatingBash != null
                && outcome.coverage().isEmpty()) {
            roundMutatingBash[0] = true;
        }
        if (outcome.persist()) {
            host.sessionStore.append(
                    sessionId,
                    SessionEntry.of(
                            host.clock.millis(),
                            "tool",
                            output,
                            null,
                            outcome.callId(),
                            outcome.name(),
                            // a pending tool is not a failure: the ephemeral "still running" result must
                            // not be pruned as a failed tool input nor flagged as an host.error
                            (!outcome.success() && !outcome.pending()) ? Map.of("is_error", true) : null));
        }
        if (!stalePaths.isEmpty()) {
            host.emit(host.status("invalidated " + stalePaths.size() + " stale read(s) of modified files"));
        }
        host.turnState.toolsRun++;
        host.turnState.toolSumMs += outcome.durationMs();
    }

    /**
     * Per-invocation compaction progress hook: folds this turn's wire tool-schema tokens into the
     * effective-context measurement the progress notice prints, and publishes the exact same
     * calibrated value as a fresh {@link io.yupiik.ia.agent.core.agent.AgentEvent.UsageUpdated} so
     * the frontends' context gauge flips to the figure the notice shows (instead of a stale
     * pre-compaction snapshot).
     */
    private CompactionCoordinator.CompactionProgress compactionProgress(final int toolsTokens) {
        return new CompactionCoordinator.CompactionProgress(
                toolsTokens,
                effective -> host.usageReporter.pushUsageContext(effective, host.requestModel(host.resolvedModel())),
                host.subagentId,
                host.subagentName,
                host.sessionWorkdir);
    }

    private void applyToolDirective(
            final String sessionId,
            final List<Message> messages,
            final ContextView.Directive directive,
            final int toolsTokens,
            final BooleanSupplier cancelled) {
        final var beforeDirectiveContext = host.usageTracker.estimateContext(messages, toolsTokens);
        final var result = "prune".equals(directive.action())
                ? host.contextManager.prune(messages)
                : host.contextManager.compact(
                        messages,
                        directive.force(),
                        directive.focus(),
                        directive.keepLast(),
                        directive.urgent(),
                        cancelled,
                        host.chatSettings,
                        compactionProgress(toolsTokens));
        if (!result.changed()) {
            host.emit(host.notice(
                    AgentEvent.Notice.Level.INFO,
                    "compress " + directive.action() + " had nothing to reclaim; context left untouched."));
            return;
        }
        messages.clear();
        messages.addAll(result.messages());
        if (!"prune".equals(directive.action()) && host.contextView != null) {
            // the whole-context digest supersedes every active host.compression block
            host.contextView.compression().absorbAll();
        }
        host.emit(host.compacted(
                "tool",
                result.mode().name(),
                AgentLoop.compactionTokens(
                        beforeDirectiveContext, host.usageTracker.estimateContext(messages, toolsTokens))));
        persistContext(sessionId, messages);
        host.emit(host.notice(
                AgentEvent.Notice.Level.INFO,
                "Context " + directive.action()
                        + " requested by the model applied ("
                        + result.mode().name().toLowerCase(Locale.ROOT)
                        + ")."));
        host.usageTracker
                .clearAnchor(); // the anchor predates the reduction: fall back to estimates until the next call
        emitUsage(messages, toolsTokens);
    }

    String capIngestionOutput(final String output) {
        final var cap = host.configuration.pruning().ingestionOutputCapChars();
        if (cap <= 0 || output == null || output.length() <= cap) {
            return output;
        }
        final var half = cap / 2;
        return output.substring(0, half)
                + "\n[... output truncated (" + output.length() + " chars), kept head and tail ...]\n"
                + output.substring(output.length() - half);
    }

    void checkRoundBudget(
            final String sessionId,
            final List<Message> messages,
            final int toolsTokens,
            final ChatSettings settings,
            final BooleanSupplier cancelled) {
        if (host.contextManager == null) {
            return;
        }
        final var effectiveSettings = settings == null ? host.chatSettings : settings;
        final var effectiveCancelled = cancelled == null ? (BooleanSupplier) () -> false : cancelled;
        final var appliedKinds = new ArrayList<String>(2);
        var appliedModes = new ArrayList<CompactionResult.Mode>(2);
        // raw estimate before the first round reduction, reported on the merged event (same
        // currency as the after-measure below, independent of the anchor clearing in this method)
        final var beforeRoundContext = host.usageTracker.estimateContext(messages, toolsTokens);
        if (host.contextManager.pruneNeeded(messages, toolsTokens)) {
            final var pruned = host.contextManager.prune(messages);
            if (pruned.changed()) {
                messages.clear();
                messages.addAll(pruned.messages());
                appliedKinds.add("round-prune");
                appliedModes.add(pruned.mode());
                persistContext(sessionId, messages);
                host.usageTracker.clearAnchor();
            }
        }
        if (host.contextManager.compactNeeded(messages, toolsTokens)) {
            if (effectiveCancelled.getAsBoolean()) {
                return;
            }
            final var compacted = host.contextManager.compact(
                    messages,
                    false,
                    null,
                    0,
                    false,
                    effectiveCancelled,
                    effectiveSettings,
                    compactionProgress(toolsTokens));
            if (compacted.changed()) {
                messages.clear();
                messages.addAll(compacted.messages());
                appliedKinds.add("round-compact");
                appliedModes.add(compacted.mode());
                persistContext(sessionId, messages);
                host.usageTracker.clearAnchor();
            }
        }
        if (!appliedKinds.isEmpty()) {
            host.emit(host.compacted(
                    appliedKinds,
                    AgentLoop.bestMode(appliedModes),
                    AgentLoop.compactionTokens(
                            beforeRoundContext, host.usageTracker.estimateContext(messages, toolsTokens))));
            emitUsage(messages, toolsTokens);
        }
    }

    private void dispatch(
            final LlmStreamEvent delta,
            final io.yupiik.ia.agent.core.llm.Usage[] usage,
            final String[] finishReason,
            final String[] errorMessage) {
        if (delta instanceof TextDelta(final String text)) {
            if (host.turnState.thinkingActive) {
                host.turnState.thinkingMs +=
                        (host.precisionDurationMarker.now() - host.turnState.thinkingStartNanos) / 1_000_000;
                host.turnState.thinkingActive = false;
            }
            host.dispatchTextDelta(text);
        } else if (delta instanceof ReasoningDelta(final String text)) {
            if (!host.turnState.thinkingActive) {
                host.turnState.thinkingActive = true;
                host.turnState.thinkingStartNanos = host.precisionDurationMarker.now();
            }
            // record that this request produced reasoning: later, a truncation or an empty
            // completion after reasoning means the output budget was spent thinking, and a
            // context-identical retry would only re-derive the same thoughts (P3)
            host.turnState.lastRequestProducedReasoning = true;
            host.streamReasoning.appendThinking(text);
            host.emit(host.thinkingDelta(text));
        } else if (delta instanceof ReasoningItem item) {
            // capture the opaque encrypted_content blob for multi-turn reasoning replay; it never
            // produces visible output (the summary deltas did) and is not re-emitted as a delta
            host.streamReasoning.addReasoningItem(item);
        } else if (delta instanceof End(final String reason, final Usage upper, final String error)) {
            // drain whatever the inline splitter still held for this generation (a trailing
            // inline-code backtick, a fence tail or an unclosed think delimiter split across the
            // last chunks), so no partial answer tail is lost at stream end
            host.dispatchInlineSplitterFlush();
            if (host.turnState.thinkingActive) {
                host.turnState.thinkingMs +=
                        (host.precisionDurationMarker.now() - host.turnState.thinkingStartNanos) / 1_000_000;
                host.turnState.thinkingActive = false;
            }
            if (reason != null) {
                finishReason[0] = reason;
            }
            if (error != null && !error.isBlank()) {
                errorMessage[0] = error;
            }
            // a chunk may carry usage and a choice finish reason on the same stream line, which
            // the dialect splits into two End events; only overwrite usage when this End actually
            // carries a usage payload, otherwise a trailing finish-reason-only End would reset the
            // captured counters back to empty
            if (upper != null) {
                usage[0] = new io.yupiik.ia.agent.core.llm.Usage(
                        Math.toIntExact(upper.promptTokens()),
                        Math.toIntExact(upper.completionTokens()),
                        Math.toIntExact(upper.totalTokens()),
                        upper.cacheRead(),
                        upper.cacheWrite(),
                        Math.toIntExact(upper.reasoning()),
                        upper.generationTime(),
                        Math.toIntExact(upper.contextInput()));
            }
            // NOTE: the logical request counter is NOT incremented here. A dialect may split the wire
            // into two End events (a usage-only End plus a separate finish-reason End), so incrementing
            // per End would double count. The counter is advanced exactly once per host.modelRoundProcessor
            // stream invocation in the process() loop, which also emits the authoritative
            // LlmRequestCompleted event. Usage is recorded once per request after the stream completes
            // (single record point, otherwise requests and tokens are double counted).
        }
    }

    public String doRun(
            final String promptForBuilding,
            final String userMessage,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt,
            final String explicitModel) {
        // a probe is always a real supplier: every downstream use (loop iterations, compaction,
        // streaming, the tool context) dereferences it. A caller holding no interrupt flag passes
        // Cancellations.NEVER; a null is a caller bug and fails here, at the boundary, rather than
        // mid-turn as an "unexpected failure" NPE.
        Objects.requireNonNull(cancelled, "cancelled: a turn must provide a cancellation probe");
        host.currentSessionId = sessionId == null || sessionId.isBlank() ? host.sessionId : sessionId;
        host.currentAgentName = agentName;
        final var agent = (agentName == null || agentName.isBlank() || host.agentProvider == null)
                ? null
                : host.agentProvider.byName(agentName);
        final var contextualSettings = host.chatSettings;
        final var contextualAgentTuning = host.agentModelTuning;
        final var configuredAgentModel = agent == null || agent.model() == null
                ? null
                : host.agentModelResolver.configuredName(agent.model().name());
        final var useProfileModel = configuredAgentModel != null;
        host.emitPhase(AgentEvent.Phase.MODEL_RESOLUTION_START);
        final var effectiveModel = host.agentModelResolver.resolve(
                contextualSettings, host.skipConfiguredAgentModel ? null : agent, explicitModel, null);
        host.emitPhase(AgentEvent.Phase.MODEL_RESOLUTION_END);
        final var runSettings =
                !host.skipConfiguredAgentModel && useProfileModel ? effectiveModel.settings() : contextualSettings;
        final var runTuning =
                !host.skipConfiguredAgentModel && useProfileModel && (explicitModel == null || explicitModel.isBlank())
                        ? agent.model()
                        : contextualAgentTuning;
        host.structuralCodeGraph.ensureReadyAsync(host.sessionWorkdir);
        // fresh per-turn loop-guard state: repeat counts must not leak across turns
        // (the threshold cascade is resolved here, once per turn, like the plateau guards)
        host.repeats = new AgentLoop.ToolRepeats(host.resolvedRepeatSuccessThreshold(host.resolvedModel(runSettings)));
        host.noProgressRounds = 0;
        host.seenFiles.clear();
        host.readCoverage.clear();
        host.plateauRounds = 0;
        host.plateauNudged = false;
        host.explorationRounds = 0;
        host.explorationNudged = false;
        host.doomLoopNudged = false;
        final var messages = new ArrayList<Message>();
        host.emitPhase(AgentEvent.Phase.PROMPT_BUILD_START);
        final var systemPrompt =
                host.systemPromptBuilder.build(promptForBuilding == null ? "" : promptForBuilding, agent)
                        + host.snapshotBlock(sessionId);
        host.emitPhase(AgentEvent.Phase.PROMPT_BUILD_END);
        messages.add(Message.system(systemPrompt));
        if (history != null) {
            // persisted session notices (role "system", e.g. an iteration-cap stop) must never reach
            // the LLM wire: they are display-only markers for the transcript. Everything else
            // (system prompt, user/assistant/tool turns) is replayed verbatim.
            for (final var m : history) {
                if (!"system".equals(m.role())) {
                    messages.add(m);
                }
            }
        }
        if (userMessage != null) {
            host.emitPhase(AgentEvent.Phase.INITIAL_PROMPT_START);
            final var accepted = acceptInitialPrompt(userMessage, sessionId);
            host.emitPhase(AgentEvent.Phase.INITIAL_PROMPT_END);
            if (accepted) {
                messages.add(Message.user(userMessage));
                host.sessionStore.append(
                        sessionId,
                        SessionEntry.of(
                                host.clock.millis(),
                                "user",
                                userMessage,
                                null,
                                null,
                                null,
                                FeedbackTiming.stamp(identityOthers(agentName), "user", host.clock.millis())));
            }
        }
        host.statusReporter.setSink(message -> host.emitter.emit(host.status(message)));
        // surface provider throttles and retry backoffs: without this the UI only shows a
        // spinner while the resilience layer can silently wait up to minutes between attempts.
        // Pauses are transient feedback rendered in the host.status-strip notification slot (right
        // side) without touching the leading agent state; the wait is also accumulated so the
        // throughput metric ignores it.
        host.pauseListener = pause -> {
            host.turnState.llmPausedMs += Math.max(0L, pause.durationMs());
            host.emit(new AgentEvent.Pause(
                    pause.kind() == PauseEvent.Pause.THROTTLE
                            ? AgentEvent.Pause.Kind.THROTTLE
                            : AgentEvent.Pause.Kind.RETRY,
                    pause.message(),
                    pause.durationMs(),
                    host.currentSessionId,
                    host.subagentId,
                    host.subagentName,
                    host.sessionWorkdir));
        };
        // a resume right after an iteration-cap stop is a fresh window: make it visible instead of
        // looking like one seamless turn in the transcript
        if (userMessage == null && history != null) {
            emitResumeAfterCapNotice(history, sessionId);
        }

        try {
            host.usageReporter.attachQuota(runSettings);
            if (host.quotaStore != null) {
                host.quotaStore.activity(host.currentSessionId, true);
            }
            return loop(
                    sessionId,
                    messages,
                    promptForBuilding,
                    cancelled,
                    queuedPrompt,
                    useProfileModel,
                    runSettings,
                    runTuning);
        } catch (final IOException e) {
            if ("Cancelled".equals(e.getMessage())) {
                // user interrupt surfaced through the stream/backoff paths: a normal end,
                // not a failure - the transcript must not render it as an host.error
                host.emit(new AgentEvent.TurnCancelled(
                        host.currentSessionId, host.subagentId, host.subagentName, host.sessionWorkdir));
            } else {
                host.emit(host.error(e.getMessage()));
            }
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            if (cancelled.getAsBoolean()) {
                host.emit(new AgentEvent.TurnCancelled(
                        host.currentSessionId, host.subagentId, host.subagentName, host.sessionWorkdir));
            } else {
                host.emit(host.error("Interrupted"));
            }
            return null;
        } catch (final RuntimeException e) {
            AgentLoop.LOGGER.log(SessionAwareLogRecord.of(
                    AgentLoop.LOGGER, host.currentSessionId, Level.SEVERE, "agent turn failed", e));
            host.emit(host.error("unexpected failure: " + e));
            return null;
        } finally {
            host.feedbackTiming.reset();
            if (host.quotaStore != null) {
                host.quotaStore.activity(host.currentSessionId, false);
            }
            host.pauseListener = null;
            host.statusReporter.setSink(null);
            // run exit invalidates speculation: late workers may still finish remotely but their
            // results are discarded, and the next run starts from a clean speculative state
            if (host.contextManager != null) {
                host.contextManager.shutdownSpeculation();
            }
            // End-of-turn memory: best-effort, asynchronous, only when the turn ended where the
            // user sees it - a real (non-empty) reply. Empty completions, hard stops (no progress,
            // plateau, max iterations), interrupts and uncaught failures all leave
            // lastTurnEndedWithContent false: there is nothing durable to mine (the excerpt gate
            // would filter empty deltas anyway), and extraction while the app is already idle
            // waiting for the next input means the close paths never need to block on it.
            if (host.memoryExtractor != null && host.lastTurnEndedWithContent) {
                // Mode-driven extraction cadence. The prompt prefix is built once per session and
                // never re-read mid-session, so the memory.mode governs only the durability cadence,
                // not what the current turn sees. LAZY extracts only at a natural milestone (all
                // todos done) or session close; FULL keeps the every-N-turns cadence plus the
                // todo-completion flush; AUTO picks LAZY when the memory model is the main model
                // (extraction is then expensive), FULL when it routes to a distinct/cheaper model.
                final var mode = host.configuration.memory().mode();
                final var regimeLazy =
                        switch (mode) {
                            case FULL -> false;
                            case LAZY -> true;
                            case AUTO ->
                                host.providerManager != null && host.providerManager.memoryUsesMainModel(runSettings);
                        };
                final boolean eligible;
                if (regimeLazy) {
                    // LAZY: extract only when all todos are done (empty of pending items;
                    // completed/cancelled allowed). A resume counts as a user turn here too.
                    eligible = userMessage != null && !host.hasPendingTodos();
                } else {
                    // FULL/AUTO-full: cadence + todo-completion gating kept as-is (extract every
                    // extractEveryTurns user turns, or immediately when a todo just finished).
                    if (userMessage != null) {
                        host.turnCount++;
                        final var cfg = host.configuration.memory();
                        final var every = cfg.extractEveryTurns();
                        final var finishedNow = host.finishedTodos();
                        final var todoFlush = cfg.extractOnTodosDone()
                                && !finishedNow.isEmpty()
                                && !host.lastFinishedTodos.containsAll(finishedNow);
                        host.lastFinishedTodos = finishedNow;
                        final var cadence = every > 0 && host.turnCount % every == 0;
                        eligible = cadence || todoFlush;
                    } else {
                        // no user message at all (empty replay): keep the legacy every-turn behavior
                        // so the pending delta is still mined when eligible
                        eligible = true;
                    }
                }
                host.memoryExtractor.asyncExtractNow(messages, runSettings, eligible);
            }
            // OS notification for the turn end, top-level turns only (subagents are covered by the parent
            // turn and must not each fire their own notification). Best-effort and non-blocking: the turned
            // service swallows failures and runs on the shared host.executor, so it cannot slow teardown.
            if (host.notificationService != null && host.subagentId == null) {
                host.emitTurnEndNotification(host.currentSessionId, messages);
            }
        }
    }

    private void emitUsage(final List<Message> messages, final int toolsTokens) {
        host.usageReporter.emitUsage(messages, toolsTokens, host.requestModel(host.resolvedModel()));
    }

    private boolean executeToolCalls(
            final String sessionId,
            final List<Message> messages,
            final List<ToolCall> calls,
            final AgentLoop.ToolRepeats repeats,
            final AgentLoop.StaleReads staleReads,
            final int toolsTokens,
            final BooleanSupplier cancelled,
            final ChatSettings settings,
            final boolean[] roundInformative,
            final boolean[] roundRefused,
            final Set<String> roundCoverage,
            final boolean[] roundMutatingBash)
            throws InterruptedException {
        return host.toolCallCoordinator.execute(
                calls,
                cancelled,
                host::isParallelizable,
                host.executor != null && host.parallelReadonlyEnabled(),
                call -> runGuarded(call, repeats, cancelled, settings),
                call -> {
                    // Workers never touch ToolRepeats: register the signature on this loop thread first.
                    repeats.register(call, host::normalizedArguments);
                    return host.executor.submit(() -> host.runTool(call, cancelled, settings));
                },
                outcome -> appendToolResult(
                        sessionId,
                        messages,
                        outcome,
                        staleReads,
                        roundInformative,
                        roundRefused,
                        roundCoverage,
                        roundMutatingBash),
                host::awaitTool,
                () -> checkRoundBudget(sessionId, messages, toolsTokens, settings, cancelled),
                () -> host.precisionDurationMarker.now(),
                elapsedMs -> host.turnState.toolWallMs += elapsedMs,
                () -> roundInformative[0]);
    }

    private Map<String, Object> identityAndThinking(final String agentName) {
        final var identity = identityOthers(agentName);
        final var thinking = host.streamReasoning.thinking().strip();
        final var reasoningItems = toReasoningItemsPayload(host.streamReasoning.reasoningItems());
        if (identity == null && (thinking.isEmpty() || thinking.isBlank()) && reasoningItems.isEmpty()) {
            return null;
        }
        final var others = new LinkedHashMap<String, Object>();
        if (identity != null) {
            others.putAll(identity);
        }
        if (thinking != null && !thinking.isEmpty() && !thinking.isBlank()) {
            // strip echoed compact ref seams from reasoning too, exactly like the answer content
            // (AgentLoop persistence scrub above): a model echoing the injected "@N@" metadata in
            // its reasoning must not persist it either - the web replay rebuilds thinking-delta
            // from this key, so an unclean value would leak straight into a fresh tab.
            others.put(AgentLoop.THINKING_KEY, scrub(thinking));
        }
        if (!reasoningItems.isEmpty()) {
            others.put(AgentLoop.REASONING_ITEMS_KEY, reasoningItems);
        }
        return others;
    }

    private Map<String, Object> identityOthers(final String agentName) {
        try {
            if (host.providerManager == null || host.chatSettings == null) {
                // no provider wiring (unit tests, bootstrapping): no identity to capture
                return null;
            }
            final var effectiveProvider = host.chatSettings.providerId() == null
                    ? host.providerManager.current()
                    : host.providerManager.provider(host.chatSettings.providerId());
            final var provider = effectiveProvider != null && effectiveProvider.id() != null
                    ? effectiveProvider.id()
                    : host.providerManager.currentProviderId();
            String model = null;
            if (host.chatSettings.model() != null && !host.chatSettings.model().isBlank()) {
                model = host.chatSettings.model();
            } else {
                final var current = effectiveProvider != null ? effectiveProvider : host.providerManager.current();
                if (current != null
                        && current.model() != null
                        && !current.model().isBlank()) {
                    model = current.model();
                }
            }
            final var identity = new LinkedHashMap<String, Object>();
            if (provider != null && !provider.isBlank()) {
                identity.put("provider", provider);
            }
            if (model != null && !model.isBlank()) {
                identity.put("model", model);
            }
            final var agent = agentName == null || agentName.isBlank() ? null : agentName;
            if (agent != null) {
                identity.put("agent", agent);
            }
            if (identity.isEmpty()) {
                return null;
            }
            return Map.of(Message.IDENTITY_KEY, identity);
        } catch (final RuntimeException e) {
            // identity capture is best-effort: a provider manager without real wiring
            // (unit tests, bootstrapping) must never break a turn
            return null;
        }
    }

    private boolean integrateQueuedPrompt(
            final String sessionId, final List<Message> messages, final Supplier<String> queuedPrompt) {
        if (queuedPrompt == null) {
            return false;
        }
        final var prompt = queuedPrompt.get();
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        final var text = prompt.strip();
        messages.add(Message.user(text));
        host.sessionStore.append(
                sessionId,
                SessionEntry.of(
                        host.clock.millis(),
                        "user",
                        text,
                        null,
                        null,
                        null,
                        FeedbackTiming.stamp(identityOthers(host.currentAgentName), "user", host.clock.millis())));
        host.emit(host.promptIntegrated(text));
        return true;
    }

    private void integrateSubagentDeliveries(final String sessionId, final List<Message> messages) {
        final var deliveries = SubagentDeliveryBus.get().drain(sessionId);
        if (deliveries.isEmpty()) {
            return;
        }
        for (final var delivery : deliveries) {
            final var text = delivery.message();
            messages.add(Message.user(text));
            host.sessionStore.append(
                    sessionId,
                    SessionEntry.of(
                            host.clock.millis(),
                            "user",
                            text,
                            null,
                            null,
                            null,
                            identityOthers(host.currentAgentName)));
            host.emit(new AgentEvent.SubagentDelivered(
                    delivery.summary(),
                    delivery.success(),
                    delivery.cancelled(),
                    sessionId,
                    delivery.subagentId(),
                    delivery.name(),
                    host.sessionWorkdir));
        }
    }

    String loop(
            final String sessionId,
            final List<Message> messages,
            final String prompt,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt,
            final boolean useProfileModel,
            final ChatSettings runSettings,
            final ModelVariantConfiguration runTuning)
            throws IOException, InterruptedException {
        final var retryOnOverflow = host.configuration.llm().retryOnContextOverflow();
        // iteration window cap cascade: per-model behavior > per-provider tuning > global default;
        // the provider is the session's effective one (per-tab override or process default); only
        // these window caps are computed once here - the model is re-resolved for every request
        // below so a mid-turn /model or /provider switch applies from the next round.
        final var resolved = host.resolvedModel(runSettings);
        final var maxIterations = host.resolvedMaxIterations(resolved);
        final var plateauLimit = host.resolvedPlateauRounds(resolved);
        final var plateauFeedback = host.resolvedPlateauFeedback(resolved);
        final var explorationNudgeRounds = host.resolvedModelExplorationNudgeRounds(resolved);
        final var turnContinuesOnTextEnd = resolved != null
                && host.providerManager != null
                && host.providerManager.turnContinuesOnTextEnd(resolved.provider(), resolved.model());
        final var tools = host.wireTools();
        final var toolsTokens = host.toolTokens(tools);
        host.usageTracker.contextProjection((history, ignoredToolTokens) -> {
            final var selection = useProfileModel && !host.skipConfiguredAgentModel ? runSettings : host.chatSettings;
            final var selectedModel = host.requestModel(host.resolvedModel(selection), selection);
            final var base = new ChatCompletionRequest(
                            selectedModel, history, null, null, null, tools, "auto", null, null, null)
                    .withSessionId(sessionId)
                    .withSettings(selection)
                    .withTuning(runTuning);
            try {
                final var projected = host.contextView == null
                        ? history
                        : host.contextView.compression().project(history, host.compressionBoundaryOnly(base));
                final var preview = new ChatCompletionRequest(
                                selectedModel, projected, null, null, null, tools, "auto", null, null, null)
                        .withSessionId(sessionId)
                        .withSettings(selection)
                        .withTuning(runTuning);
                return host.contextPayload(preview);
            } catch (final IOException e) {
                throw new IllegalStateException("Cannot project the context request", e);
            }
        });
        // snapshot which tools the registry actually serves (unfiltered by mode): plan mode may hide an EDIT
        // tool from this turn's wire list, so a call to a served EDIT tool in plan mode is a hidden-tool call
        // to auto-reject (never gate). Bare/empty test registries simply serve nothing and stay unaffected.
        host.servedToolNames = host.toolRegistry.toWireTools(host.sessionWorkdir).stream()
                .map(t -> t.function().name())
                .collect(Collectors.toSet());
        var overflowRetried = false;
        // turns the pair old assistant tool-call/result history was healed at most once per turn, so a
        // tool-pairing 400 (Azure/litellm "tool_calls must be followed by tool messages") can be repaired
        // and retried once without risking an endless retry loop on a non-tool 400.
        var toolPairingRepaired = false;
        var compactionGateEscalated = false;
        var forcedCompactionRecoveryAttempted = 0;
        var gateStartedAtNanos = 0L;
        var autoNoticeEmitted = false;
        var truncationContinues = 0;
        var emptyCompletionRetries = 0;
        var toolsRanThisTurn = false;
        var toolDirectiveApplied = false;
        // a prompt typed while the loop was running was injected in this turn: the model answered
        // it, so its text-only end answers that message and must not be treated as "stopped early"
        var userInjected = false;
        final var staleReads = new AgentLoop.StaleReads(readsFromHistory(messages));
        // heal interrupted/cancelled history before the first request: an assistant tool_use whose
        // result never landed (turn ended mid-tool via cancel/interrupt/host.error) must be answered by a
        // lost-result marker so Anthropic's tool_use -> tool_result contiguity rule holds on the wire.
        repairInterruptedToolCalls(sessionId, messages);

        // Reset turn-local counters and reasoning flags before starting this run.
        host.turnState.reset();
        final var turnStartNanos = host.precisionDurationMarker.now();
        host.lastTurnEndedWithContent = false;
        // A "window" is one bounded run of the round loop. Every todo auto-continuation starts a fresh
        // window with a new maxIterations budget - yca.todos.maxContinues bounds the number of windows -
        // while empty-completion and truncation retries keep sharing the current window's budget. Without
        // this one long turn would be capped at maxIterations (250 by default) regardless of how many
        // todo continuations remain, making large --todos-maxContinues values unreachable.
        windowLoop:
        while (!cancelled.getAsBoolean()) {
            // An unbounded window (maxIterations < 0) has no iteration budget: it runs until the turn
            // genuinely ends. A positive maxIterations bounds this window; every todo auto-continuation
            // starts a fresh window with a new maxIterations budget.
            roundLoop:
            for (var iteration = 0;
                    (maxIterations < 0 || iteration < maxIterations) && !cancelled.getAsBoolean();
                    iteration++) {
                if (host.contextView != null) {
                    host.contextView.publish(snapshot(messages, toolsTokens));
                    host.contextView.publishMessages(messages);
                }
                if (integrateQueuedPrompt(sessionId, messages, queuedPrompt)) {
                    userInjected = true;
                    continue;
                }
                // a finished sub-agent's result is pushed into this (parent) turn's context here, at the
                // round boundary, so the model's next round sees the child's completion inline.
                integrateSubagentDeliveries(sessionId, messages);
                // user/tool-requested context operations (manage_context, /compact while busy) are
                // applied at the first safe checkpoint of the turn, at most one per turn
                if (!toolDirectiveApplied && host.contextView != null) {
                    final var directive = host.contextView.poll();
                    if (directive != null) {
                        toolDirectiveApplied = true;
                        applyToolDirective(sessionId, messages, directive, toolsTokens, cancelled);
                    }
                }
                // zero-LLM DCP-style strategies (dedup, write superseding, host.error-input purge) run only at
                // the automatic-prune check point: like the prune, they mutate already-sent messages, so
                // running them on every iteration would invalidate the prompt prefix cache for no gain.
                // When they change something the result is persisted and the usage anchor cleared.
                // All reductions of this checkpoint (zero-LLM + prune + LLM compact) are reported as a
                // single merged Compacted event carrying the applied kinds, so the transcript shows one
                // "context <kinds> applied (<mode>)" line instead of one line per strategy.
                var compactedThisTurn = false;
                final var appliedKinds = new ArrayList<String>(4);
                var appliedModes = new ArrayList<CompactionResult.Mode>(2);
                // raw estimate before the first reduction of this checkpoint: reported on the merged
                // Compacted event so the host.notice can show the actual reclaim (estimates, never the
                // anchor-calibrated gauge - the anchor is cleared mid-checkpoint below)
                final var contextBefore = host.usageTracker.estimateContext(messages, toolsTokens);
                final var llmFree = host.contextManager.ignoreLlm(runSettings);
                if (host.contextManager.pruneNeeded(messages, toolsTokens)) {
                    // the DCP-style strategies are the compact-checkpoint's weapon under llmFree
                    // (see below); at the prune checkpoint they would run twice per checkpoint.
                    if (!llmFree) {
                        appliedKinds.addAll(applyZeroLlmStrategies(messages));
                        if (!appliedKinds.isEmpty()) {
                            host.contextManager.resetSpeculation();
                            persistContext(sessionId, messages);
                            host.usageTracker.clearAnchor();
                            appliedModes.add(CompactionResult.Mode.CLEARED);
                        }
                    }
                    final var pruned = host.contextManager.prune(messages);
                    if (pruned.changed()) {
                        messages.clear();
                        messages.addAll(pruned.messages());
                        appliedKinds.add("prune");
                        appliedModes.add(pruned.mode());
                        persistContext(sessionId, messages);
                        compactedThisTurn = true;
                    }
                }
                if (host.contextManager.compactNeeded(messages, toolsTokens)) {
                    final CompactionResult compacted;
                    if (llmFree) {
                        // LLM-free compaction: no speculation (it is LLM-backed by design), and the
                        // structural (dedup/supersede/purge) strategies run here, at the moment they
                        // matter, before the local cascade.
                        final var structural = applyZeroLlmStrategies(messages);
                        if (!structural.isEmpty()) {
                            appliedKinds.addAll(structural);
                            host.contextManager.resetSpeculation();
                            persistContext(sessionId, messages);
                            host.usageTracker.clearAnchor();
                            if (!compactedThisTurn) {
                                appliedModes.add(CompactionResult.Mode.CLEARED);
                            }
                        }
                        compacted = host.contextManager.compact(
                                messages,
                                false,
                                null,
                                0,
                                false,
                                cancelled,
                                runSettings,
                                compactionProgress(toolsTokens));
                    } else {
                        // speculative adoption first: a validated candidate avoids the synchronous
                        // stall; anything it cannot install falls back to the normal path below
                        final var adopted = host.contextManager.adoptSpeculation(
                                messages,
                                toolsTokens,
                                () -> host.closed || cancelled.getAsBoolean(),
                                useProfileModel ? runSettings : host.chatSettings);
                        if (adopted != null && adopted.changed()) {
                            host.emitAdoptedCompaction(adopted);
                        }
                        compacted = adopted != null
                                ? adopted
                                : host.contextManager.compact(
                                        messages,
                                        false,
                                        null,
                                        0,
                                        false,
                                        cancelled,
                                        runSettings,
                                        compactionProgress(toolsTokens));
                    }
                    if (compacted.changed()) {
                        messages.clear();
                        messages.addAll(compacted.messages());
                        appliedKinds.add(llmFree ? "compact-local" : "compact");
                        appliedModes.add(compacted.mode());
                        if (host.contextView != null) {
                            // the digest replaces the history the blocks pointed at: never re-apply them
                            host.contextView.compression().absorbAll();
                        }
                        persistContext(sessionId, messages);
                        compactedThisTurn = true;
                    }
                }
                // The normal tail floor is optimized for retaining recent history. If it leaves the
                // prompt above the trigger, run the compaction gate: a bounded sequence of ever
                // tighter forced passes, an LLM-free local tail spill, then - if the context still
                // cannot get under the trigger and compaction stops making progress - escalate to a
                // pinned host.error and continue (never block the turn forever on a non-compactable
                // context, and never silently limp past the trigger). The gate is deliberately
                // separate from the provider hard-overflow retry: the trigger can be below the
                // usable window, and the gate must not send a request while the prompt is over it.
                // The gate also runs when the checkpoint compact itself made no progress (Mode.NONE):
                // in that case the recovery pass and the local spill get their chance, and if they
                // also reclaim nothing the context is declared non-compactable instead of limping.
                if (!compactionGateEscalated
                        && host.usageTracker.budgetContext(messages, toolsTokens)
                                >= host.contextManager.compactionTriggerTokens()) {
                    final var gate = host.contextManager.resolvedGate(runSettings);
                    // per-session/per-provider overrides win over the global gate budgets (a session or
                    // provider on a flakier route can raise its own gate ceiling without affecting others)
                    final var maxAttempts = gate.maxAttempts();
                    final var maxDurationMs = gate.maxDurationMs();
                    if (gateStartedAtNanos == 0) {
                        gateStartedAtNanos = host.precisionDurationMarker.now();
                    }
                    var consecutiveWeakPasses = 0;
                    var gateBudgetExhausted = false;
                    while (!compactionGateEscalated
                            && !cancelled.getAsBoolean()
                            && host.usageTracker.budgetContext(messages, toolsTokens)
                                    >= host.contextManager.compactionTriggerTokens()
                            && !gateBudgetExhausted) {
                        if (maxAttempts > 0 && forcedCompactionRecoveryAttempted >= maxAttempts) {
                            gateBudgetExhausted = true;
                            break;
                        }
                        if (maxDurationMs > 0
                                && host.precisionDurationMarker.now() - gateStartedAtNanos
                                        >= TimeUnit.MILLISECONDS.toNanos(maxDurationMs)) {
                            gateBudgetExhausted = true;
                            break;
                        }
                        forcedCompactionRecoveryAttempted++;
                        final var effectiveBefore = host.usageTracker.budgetContext(messages, toolsTokens);
                        host.emit(host.status("Compacting... " + (int) host.usageTracker.contextPercent(effectiveBefore)
                                + "% (trigger "
                                + (int) host.usageTracker.contextPercent(host.contextManager.compactionTriggerTokens())
                                + "%)"));
                        // 1) one tighter forced pass (halved tail budget) - LLM-backed
                        final var recovery = host.contextManager.compactForRecovery(
                                messages, cancelled, runSettings, compactionProgress(toolsTokens));
                        if (recovery.changed()) {
                            messages.clear();
                            messages.addAll(recovery.messages());
                            appliedKinds.add("compact-recovery");
                            appliedModes.add(recovery.mode());
                            if (host.contextView != null) {
                                host.contextView.compression().absorbAll();
                            }
                            persistContext(sessionId, messages);
                            compactedThisTurn = true;
                        } else {
                            // 2) no summarizer progress: LLM-free local spill of pre-digest tool
                            // outputs (never touches the digest at index 0) before the next run
                            final var spilled = host.contextManager.localSpill(
                                    messages, host.contextManager.compactionTriggerTokens());
                            if (spilled.changed()) {
                                messages.clear();
                                messages.addAll(spilled.messages());
                                appliedKinds.add("compact-spill");
                                appliedModes.add(spilled.mode());
                                persistContext(sessionId, messages);
                                compactedThisTurn = true;
                            }
                        }
                        if (cancelled.getAsBoolean()) {
                            // the user pressed Esc mid-pass: abort the gate without escalating
                            break;
                        }
                        // progress check: a pass that reclaims nothing (or only a tiny <5% fraction,
                        // twice in a row) means the context cannot be compacted further with the
                        // current strategy - escalate instead of blocking or silently limping. A
                        // genuine strong pass resets the weak-pass counter.
                        final var effectiveAfter = host.usageTracker.budgetContext(messages, toolsTokens);
                        final var reclaimed = effectiveBefore - effectiveAfter;
                        if (reclaimed <= 0) {
                            compactionGateEscalated = true;
                            host.escalateCompactionGate(effectiveAfter);
                        } else if (reclaimed * 100L < effectiveBefore * 5L) {
                            // a weak pass (<5% reclaimed) bumps the counter; only the second
                            // consecutive weak pass escalates. A weak pass must NOT fall through to
                            // the strong-pass reset below, or the counter can never reach two.
                            if (++consecutiveWeakPasses >= 2) {
                                compactionGateEscalated = true;
                                host.escalateCompactionGate(effectiveAfter);
                            }
                        } else {
                            // a genuine strong pass resets the weak-pass counter.
                            consecutiveWeakPasses = 0;
                        }
                        // backoff between gate passes: bounded and interruptible, so Esc aborts the
                        // sleep (interrupted sleep reports false) instead of idling out the budget
                        if (!compactionGateEscalated && !cancelled.getAsBoolean()) {
                            if (!host.backoffSleeper.sleep(
                                    Math.min(1_000L * forcedCompactionRecoveryAttempted, 5_000L))) {
                                break;
                            }
                        }
                    }
                    if (!compactionGateEscalated
                            && !cancelled.getAsBoolean()
                            && gateBudgetExhausted
                            && host.usageTracker.budgetContext(messages, toolsTokens)
                                    >= host.contextManager.compactionTriggerTokens()) {
                        // ran out of attempts or wall-clock budget while still over the trigger
                        compactionGateEscalated = true;
                        host.escalateCompactionGate(host.usageTracker.budgetContext(messages, toolsTokens));
                    }
                }
                if (!appliedKinds.isEmpty()) {
                    final var contextAfter = host.usageTracker.estimateContext(messages, toolsTokens);
                    host.emit(host.compacted(
                            appliedKinds,
                            AgentLoop.bestMode(appliedModes),
                            AgentLoop.compactionTokens(contextBefore, contextAfter)));
                }
                if (compactedThisTurn) {
                    host.contextManager.resetSpeculation();
                    host.usageTracker.clearAnchor();
                    emitUsage(messages, toolsTokens);
                } else if (host.contextManager.speculationEnabled()) {
                    host.contextManager.maybeLaunchSpeculation(
                            messages,
                            toolsTokens,
                            () -> host.closed || cancelled.getAsBoolean(),
                            useProfileModel ? runSettings : host.chatSettings);
                }

                // append-only volatile session state: the tail is its own trailing user message in the
                // working list (deduplicated when unchanged), so every request is the previous request's
                // exact bytes plus the tokens appended since. Nothing previously sent is ever moved or
                // rewritten, so provider-side prefix caches (llama.cpp slot LCP with f_keep ~ 1.0,
                // token-level gateways) keep matching instead of diverging mid-conversation.
                host.advanceCompressionCadence(messages);
                final var nudgeTier = appendStateIfChanged(sessionId, messages, prompt, toolsTokens);
                if (nudgeTier == 2) {
                    host.contextGuidance.markHardNudgeRound();
                }
                // snapshot of what goes on the wire, for /context llm-payload: taken here so it
                // carries the pruned/compacted list and the volatile tail, and copied because the
                // live list keeps growing (messages/tools are immutable, so a shallow copy is enough)
                // resolved (and tracked) per request: the window caps above stay the turn's ones but
                // the selection the provider sees is the session's current one, so a /model run
                // while busy lands on this round instead of only on the next turn
                final var requestSettings =
                        useProfileModel && !host.skipConfiguredAgentModel ? runSettings : host.chatSettings;
                attachSessionSettings(requestSettings);
                final var model = host.requestModel(resolved, requestSettings);
                // secret redaction: let observer(s) (the default SecretRedactor, or custom
                // extensions) transform the outgoing messages before they reach the provider.
                // host.compression is a wire-only view: active blocks replace closed ranges and the
                // stable refs are tagged here, so the transcript itself stays append-only.
                final var compressed = host.contextView == null
                        ? messages
                        : host.contextView.compression().rewrite(messages);
                final var presentationRequest = new ChatCompletionRequest(
                                model, compressed, null, null, null, tools, "auto", null, null, null)
                        .withSessionId(sessionId)
                        .withSettings(requestSettings)
                        .withTuning(runTuning);
                final var wireSource = host.contextView == null
                        ? compressed
                        : host.contextView
                                .compression()
                                .renderRefs(compressed, host.compressionBoundaryOnly(presentationRequest));
                final var outbound = new OutboundLlmRequest(wireSource, requestSettings, sessionId);
                host.emitter.emit(outbound);
                final var wireMessages = outbound.messages();
                host.lastLlmPayload = new AgentLoop.LlmPayload(
                        sessionId, model, List.copyOf(wireMessages), List.copyOf(tools), "auto", toolsTokens);
                final var request = new ChatCompletionRequest(
                                model, wireMessages, null, null, null, tools, "auto", null, null, null)
                        .withSessionId(sessionId)
                        .withSettings(requestSettings)
                        .withTuning(runTuning);
                final var usage = new io.yupiik.ia.agent.core.llm.Usage[] {io.yupiik.ia.agent.core.llm.Usage.EMPTY};
                final var sentPayload = new AtomicReference<>(host.contextPayload(request));
                final var finishReason = new String[] {null};
                final var errorMessage = new String[] {null};
                Message assistant;
                host.emitPhase(AgentEvent.Phase.REQUEST_SENT);
                final long llmStartNanos = host.precisionDurationMarker.now();
                final long pausedBeforeMs = host.turnState.llmPausedMs;
                final long requestActiveMs;
                try {
                    assistant = host.withContextCapture(
                            sentPayload::set,
                            () -> host.modelRoundProcessor.stream(
                                    request,
                                    usage,
                                    cancelled,
                                    host.pauseListener,
                                    delta -> dispatch(delta, usage, finishReason, errorMessage),
                                    new ModelRoundProcessor.Observer() {
                                        @Override
                                        public void begin() {
                                            host.feedbackTiming.reset();
                                            host.turnState.lastRequestProducedReasoning = false;
                                            host.resetInlineSplitter();
                                        }

                                        @Override
                                        public void resetText() {
                                            usage[0] = io.yupiik.ia.agent.core.llm.Usage.EMPTY;
                                            finishReason[0] = null;
                                            errorMessage[0] = null;
                                            // a retry is a fresh generation: rebuild the inline splitter (dropping the
                                            // failed attempt's stale, possibly half-consumed carry) and drop the stale
                                            // partial reasoning text + opaque blobs so nothing leaks into the persisted
                                            // message's thinking / reasoningItems
                                            host.resetInlineSplitter();
                                            host.emit(textReset());
                                        }

                                        @Override
                                        public void retrying(final String message, final Throwable cause) {
                                            host.emit(host.notice(AgentEvent.Notice.Level.WARN, message, null, cause));
                                        }

                                        @Override
                                        public void malformedToolArguments() {
                                            host.emit(
                                                    host.notice(
                                                            AgentEvent.Notice.Level.WARN,
                                                            "Server rejected the request: malformed tool call arguments (parse_error.101). "
                                                                    + "The arguments were kept as-is (repair failed or disabled); remove the "
                                                                    + "corrupted call from the transcript or retry with the repair enabled."));
                                        }

                                        @Override
                                        public void toolCallPairingRejected() {
                                            // Signalled by ModelRoundProcessor when it short-circuits a tool-pairing
                                            // 400
                                            // (instead of wasting the generic retry budget on the same broken history).
                                            // The
                                            // host.notice and the actual history repair are emitted/done by the
                                            // catch(IOException)
                                            // below, which runs on the rethrown 400 and stays the single emission
                                            // point.
                                        }

                                        @Override
                                        public void paused(final long millis) {
                                            host.turnState.llmPausedMs += millis;
                                        }
                                    }));
                    final var wallMs = (host.precisionDurationMarker.now() - llmStartNanos) / 1_000_000;
                    final var pausedMs = Math.max(0L, host.turnState.llmPausedMs - pausedBeforeMs);
                    requestActiveMs = Math.max(0L, wallMs - pausedMs);
                } catch (final ContextWindowExceededException e) {
                    if (!retryOnOverflow || overflowRetried) {
                        throw e;
                    }
                    if (host.usageTracker.lastResolutionWasAuto() && !autoNoticeEmitted) {
                        host.emit(
                                host.notice(
                                        AgentEvent.Notice.Level.INFO,
                                        "Context window auto-detected: no exact figure was available, using a conservative default and learning on overflow."));
                        autoNoticeEmitted = true;
                    }
                    final var shrunken = host.usageTracker.rememberShrinkOnOverflow();
                    host.emit(host.notice(
                            AgentEvent.Notice.Level.INFO,
                            "Context window exceeded; compacting and retrying (assumed window reduced to " + shrunken
                                    + " tokens)."));
                    // LLM-free overflow recovery: give the structural strategies their shot before
                    // the local cascade (the summarizer never runs under ignoreLlm). Persist
                    // immediately so their reclaim survives even if the cascade returns NONE.
                    if (host.contextManager.ignoreLlm(runSettings)
                            && !applyZeroLlmStrategies(messages).isEmpty()) {
                        host.contextManager.resetSpeculation();
                        persistContext(sessionId, messages);
                        host.usageTracker.clearAnchor();
                    }
                    final var beforeOverflowContext = host.usageTracker.estimateContext(messages, toolsTokens);
                    final var compacted = host.contextManager.compactForOverflow(
                            messages, cancelled, runSettings, compactionProgress(toolsTokens));
                    if (compacted.changed() && !compacted.messages().isEmpty()) {
                        messages.clear();
                        messages.addAll(compacted.messages());
                        host.emit(host.compacted(
                                "compact-retry",
                                compacted.mode().name(),
                                AgentLoop.compactionTokens(
                                        beforeOverflowContext,
                                        host.usageTracker.estimateContext(messages, toolsTokens))));
                        persistContext(sessionId, messages);
                        overflowRetried = true;
                        continue;
                    }
                    host.emit(host.notice(
                            AgentEvent.Notice.Level.ERROR,
                            "Context window exceeded and compacting did not free enough context: " + e.getMessage()));
                    throw e;
                } catch (final IOException e) {
                    // Azure / litellm strict validators reject an assistant message whose tool_calls are
                    // not answered by a tool message carrying each tool_call_id ("An assistant message with
                    // 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'. The
                    // following tool_call_ids did not have response messages: ..."), a hard 400 that hits
                    // "sometimes" when an interrupted/cancelled tool round left its result unlanded. Heal the
                    // in-loop history (insert synthetic lost-result markers, exactly like the loop-entry
                    // repair) and retry once; the model then sees an honest interrupted note instead of the
                    // turn dying. Bounded to one repair per turn so a non-tool 400 can never spin. The
                    // signature check is owned by ModelRoundProcessor (single source of truth) and, for the
                    // streaming path, already short-circuits the generic retry budget before it reaches here.
                    if (!toolPairingRepaired && ToolCallPairingRepair.isPairingRejection(e)) {
                        toolPairingRepaired = true;
                        host.emit(
                                host.notice(
                                        AgentEvent.Notice.Level.INFO,
                                        "Provider rejected the request: an assistant tool call had no matching tool result "
                                                + "(interrupted or cancelled tool round). Repairing the history and retrying once."));
                        repairInterruptedToolCalls(sessionId, messages);
                        continue;
                    }
                    throw e;
                } catch (final InterruptedException | RuntimeException e) {
                    throw e;
                } catch (final Exception e) {
                    // CallableOp infers Exception for an operation throwing both IOException and
                    // InterruptedException; all declared checked failures are handled above.
                    throw new IllegalStateException(e);
                }
                host.usageTracker.record(usage[0]);
                // exactly one logical (main) LLM request per stream invocation: this is the undo of the
                // dialect End-fragmentation hazard (two End events for one logical request) and the single
                // authoritative request counter + per-request usage payload for the metrics consumers. It
                // is NOT incremented for the display-only pushUsageQuota/refreshUsage path.
                host.turnState.llmCalls++;
                host.emit(new AgentEvent.LlmRequestCompleted(
                        usage[0],
                        model,
                        host.turnState.llmCalls,
                        host.currentSessionId,
                        host.subagentId,
                        host.subagentName,
                        host.sessionWorkdir));
                host.emitPhase(AgentEvent.Phase.STREAM_COMPLETE);
                // inbound restore: replace provenance fakes in the assistant reply with the real
                // values (only what we emitted outbound; model-invented token shapes stay as-is)
                final var inbound = new InboundLlmResponse(
                        assistant.content() instanceof String s ? s : AgentLoop.str(assistant.content()), sessionId);
                host.emitter.emit(inbound);
                final var restored = inbound.restored();
                if (!restored.equals(AgentLoop.str(assistant.content()))) {
                    assistant = new Message(
                            assistant.role(),
                            restored,
                            assistant.name(),
                            assistant.toolCalls(),
                            assistant.toolCallId(),
                            assistant.others());
                }
                // strip echoed compact ref seams ("@N@", "@bN@", "@blocked@") from the model reply
                // before it reaches the transcript: even when the model violates the "this is
                // injected metadata" instruction, its bracketed hallucination must not persist
                if (assistant.content() instanceof String text) {
                    final var stripped = scrub(text);
                    if (!stripped.equals(text)) {
                        assistant = new Message(
                                assistant.role(),
                                stripped,
                                assistant.name(),
                                assistant.toolCalls(),
                                assistant.toolCallId(),
                                assistant.others());
                    }
                }
                // Request throughput excludes tracked waits and post-stream processing. A request refused
                // because the context window is too small contributes nothing (it is a preflight).
                recordThroughput(assistant, usage[0], requestActiveMs);
                // anchor the hybrid usage on the real prompt tokens of the request we just sent: the
                // estimate at the same moment calibrates later deltas until the next observation
                host.usageTracker.anchor(usage[0].contextInputTokens(), sentPayload.get());
                // Keep the live tool-round history aligned with the persisted/resumed transcript.
                final var retained =
                        new LinkedHashMap<String, Object>(assistant.others() == null ? Map.of() : assistant.others());
                final var identityAndThinking = identityAndThinking(host.currentAgentName);
                if (identityAndThinking != null) {
                    retained.putAll(identityAndThinking);
                }
                // Non-streaming providers must enter the same first-visible capture path.
                if (!host.feedbackTiming.has("thinking")
                        && retained.get(AgentLoop.THINKING_KEY) instanceof String thought
                        && !thought.isBlank()) {
                    host.emit(thinkingDelta(thought));
                }
                if (!host.feedbackTiming.has("text") && assistant.content() instanceof String text && !text.isBlank()) {
                    host.emit(textDelta(text));
                }
                host.feedbackTiming.reconcile(AgentLoop.str(assistant.content()));
                final var timed = host.feedbackTiming.merge(retained);
                assistant = new Message(
                        assistant.role(),
                        assistant.content(),
                        assistant.name(),
                        assistant.toolCalls(),
                        assistant.toolCallId(),
                        timed);
                messages.add(assistant);
                host.sessionStore.append(
                        sessionId,
                        SessionEntry.of(
                                host.clock.millis(),
                                "assistant",
                                AgentLoop.str(assistant.content()),
                                assistant.toolCalls(),
                                null,
                                null,
                                timed));
                emitUsage(messages, toolsTokens);

                // When the reply carries tool calls, do NOT absorb a queued prompt here (before the
                // tools run): injecting a plain user text message between the assistant tool_use and
                // its tool_result blocks would break Anthropic's strict pairing rule ("tool_use ids
                // were found without tool_result blocks immediately after"), a hard 400 on
                // Anthropic/Azure AI Gateway. Fall through to executeToolCalls first so the tool_result
                // blocks stay contiguous; the queued prompt is then absorbed at the next top-of-iteration
                // check (host.integrateQueuedPrompt at the loop head) once the tool round has landed.
                if ((assistant.toolCalls() == null || assistant.toolCalls().isEmpty())
                        && integrateQueuedPrompt(sessionId, messages, queuedPrompt)) {
                    userInjected = true;
                    continue;
                }

                if (assistant.toolCalls() == null || assistant.toolCalls().isEmpty()) {
                    // an interrupt pressed while a continuation (truncation or overflow retry) was
                    // about to re-ask the model must stop here, not spawn another request
                    if (cancelled.getAsBoolean()) {
                        // rush the terminal event out so the shells reset their busy state and flush
                        // the buffered thinking/answer: the other cancel exits (window budget, outer
                        // loop) host.emit TurnCancelled before returning, without it the transcript stays
                        // busy forever and the live thinking tail keeps animating after the interrupt
                        host.emit(new AgentEvent.TurnCancelled(
                                host.currentSessionId, host.subagentId, host.subagentName, host.sessionWorkdir));
                        host.emitTurnMetrics(null, turnStartNanos);
                        return AgentLoop.str(assistant.content());
                    }
                    final var content = AgentLoop.str(assistant.content());
                    final var reason = finishReason[0];
                    if (AgentLoop.isTruncation(reason)) {
                        final var maxTruncationContinues =
                                host.configuration.llm().truncationContinuesMax();
                        if (host.configuration.llm().continueOnTruncation()
                                && truncationContinues < maxTruncationContinues) {
                            truncationContinues++;
                            host.emit(host.notice(
                                    AgentEvent.Notice.Level.WARN,
                                    "Response truncated by the output token limit (finish_reason="
                                            + reason + "); asking the model to continue (" + truncationContinues + '/'
                                            + maxTruncationContinues + ")."));
                            // When the model produced reasoning before the truncation, its whole output
                            // budget was spent thinking: "continue exactly where you stopped" would make
                            // it re-derive the same reasoning from the same context (the thoughts are not
                            // part of the request), hitting the same wall. Jump straight to the
                            // change-strategy prompt instead of the verbatim continue. Without reasoning
                            // the usual progressive escalation (continue, then split) still applies.
                            final var changeStrategy =
                                    host.turnState.lastRequestProducedReasoning || truncationContinues >= 2;
                            messages.add(Message.user(
                                    changeStrategy
                                            ? AgentLoop.TRUNCATION_SPLIT_PROMPT
                                            : AgentLoop.TRUNCATION_CONTINUE_PROMPT));
                            // the reasoning flag was consumed: a retry of this request must not re-trigger
                            // the "spent thinking" branch again on the same generation
                            host.turnState.lastRequestProducedReasoning = false;
                            continue;
                        }
                        if (host.tryTodoAutoContinue(messages, reason, toolsRanThisTurn, userInjected)) {
                            continue windowLoop;
                        }
                        host.emit(host.notice(
                                AgentEvent.Notice.Level.ERROR,
                                "Response truncated by the output token limit (finish_reason="
                                        + reason + "); the reply may be incomplete."
                                        + (host.configuration.llm().continueOnTruncation()
                                                ? " Gave up after " + truncationContinues + " continuation"
                                                        + (truncationContinues == 1 ? "" : "s") + "."
                                                : " Enable yca.llm.continueOnTruncation to auto-continue."),
                                reason));
                        host.emitTurnMetrics(content, turnStartNanos);
                        return content;
                    }
                    if (content.isBlank()) {
                        final var cause = errorMessage[0] == null ? "" : "; provider error: " + errorMessage[0];
                        final var maxRetries = host.configuration.llm().emptyCompletionRetriesMax();
                        if (maxRetries > 0 && emptyCompletionRetries < maxRetries) {
                            emptyCompletionRetries++;
                            host.emit(host.notice(
                                    AgentEvent.Notice.Level.WARN,
                                    "model returned an empty completion (finish_reason=" + reason + "); retrying ("
                                            + emptyCompletionRetries + '/' + maxRetries + ')' + cause));
                            // An empty completion after reasoning means the model burned its whole
                            // output budget thinking (reasoning tokens are not part of the request):
                            // re-asking the identical context would re-derive the same thoughts and
                            // hit the same wall. Ask for a strategy change instead of a blind retry.
                            if (host.turnState.lastRequestProducedReasoning) {
                                host.turnState.lastRequestProducedReasoning = false;
                                messages.add(Message.user(AgentLoop.EMPTY_REASONING_RETRY_PROMPT));
                            }
                            continue;
                        }
                        if (host.tryTodoAutoContinue(messages, reason, toolsRanThisTurn, userInjected)) {
                            continue windowLoop;
                        }
                        host.emit(host.notice(
                                AgentEvent.Notice.Level.ERROR,
                                "model returned an empty completion (finish_reason=" + reason + ')'
                                        + (maxRetries <= 0
                                                ? " (yca.llm.emptyCompletionRetriesMax is 0, no retry)"
                                                : "; gave up after " + emptyCompletionRetries + " retries")
                                        + cause));
                        host.emitTurnMetrics(content, turnStartNanos);
                        return content;
                    }
                    // a real, non-truncated text reply is progress: empty-completion and truncation retries must
                    // not accumulate across productive rounds, only across consecutive stuck replies
                    emptyCompletionRetries = 0;
                    truncationContinues = 0;
                    if (host.tryTodoAutoContinue(messages, reason, toolsRanThisTurn, userInjected)) {
                        continue windowLoop;
                    }
                    final var pending = host.unfinishedTodos(toolsRanThisTurn);
                    // Codex-style round gate: models opted in via behavior.turnContinuesOnTextEnd=true
                    // keep driving a turn that ends text-only while the todo list is unfinished, instead
                    // of ending the turn and waiting for the user to send 'continue'. The model decides
                    // the turn is genuinely over by producing a plain text end once todos are done (or by
                    // hitting the iteration cap / a plateau hard-stop); nothing consumes the
                    // yca.todos.maxContinues budget because this is bounded by the iteration window, not
                    // by todo continuations. Only skipped when a prompt typed while running was injected
                    // (the model just answered that message).
                    if (turnContinuesOnTextEnd && pending != null && !userInjected) {
                        host.emit(
                                host.notice(
                                        AgentEvent.Notice.Level.INFO,
                                        "model ended its turn (finish_reason=" + reason + ") with " + pending.pending()
                                                + '/' + pending.total()
                                                + " todos still unfinished; continuing the round automatically (behavior.turnContinuesOnTextEnd)."));
                        messages.add(Message.user(host.unfinishedTodoContinuePrompt(1)));
                        continue roundLoop;
                    }
                    if (pending != null) {
                        host.emit(host.unfinishedTodosNotice(pending, reason));
                    }
                    host.emitTurnMetrics(content, turnStartNanos);
                    return content;
                }
                // an interrupt pressed while the model was generating must stop here: the flag is
                // only polled at iteration boundaries, so without this check the whole tool batch
                // would still run after the cancellation
                if (cancelled.getAsBoolean()) {
                    break;
                }
                final var roundInformative = new boolean[] {false};
                final var roundRefused = new boolean[] {false};
                final var roundCoverage = new HashSet<String>();
                final var roundMutatingBash = new boolean[] {false};
                executeToolCalls(
                        sessionId,
                        messages,
                        assistant.toolCalls(),
                        host.repeats,
                        staleReads,
                        toolsTokens,
                        cancelled,
                        requestSettings,
                        roundInformative,
                        roundRefused,
                        roundCoverage,
                        roundMutatingBash);
                toolsRanThisTurn = true;
                host.emitPhase(AgentEvent.Phase.TOOL_BATCH_COMPLETE);
                // a doom-loop refusal is surfaced to the model as ONE strategy-change nudge (never as a
                // failed tool result): after the refused round completes, inject it and let the model adapt.
                if (roundRefused[0] && !host.doomLoopNudged) {
                    host.doomLoopNudged = true;
                    host.emit(host.notice(
                            AgentEvent.Notice.Level.INFO,
                            "doom-loop refusal sent to the model: a repeated state-changing/bash call was withheld."));
                    messages.add(Message.user(AgentLoop.DOOM_LOOP_NUDGE_PROMPT));
                    host.plateauRounds = 0;
                    host.explorationRounds = 0;
                    continue roundLoop;
                }
                // a tool round is progress too: a later empty/truncated reply starts its retry budget fresh
                emptyCompletionRetries = 0;
                truncationContinues = 0;
                // no-progress termination (P2): 5 consecutive tool rounds that brought no new
                // information (same calls, same outputs, no write) mean the model is spinning
                if (roundInformative[0]) {
                    host.noProgressRounds = 0;
                } else {
                    // open todos keep the turn going: the todo auto-continue (and the model's own
                    // next round) is the intended escape hatch there, not a hard no-progress stop
                    if (host.unfinishedTodos(toolsRanThisTurn) != null) {
                        host.noProgressRounds = 0;
                    } else {
                        host.noProgressRounds++;
                        if (host.noProgressRounds >= AgentLoop.NO_PROGRESS_ROUNDS_LIMIT) {
                            host.emit(host.error("No progress: the model made " + AgentLoop.NO_PROGRESS_ROUNDS_LIMIT
                                    + " consecutive tool calls returning the same results. Stopping the turn."));
                            host.emitTurnMetrics(null, turnStartNanos);
                            return null;
                        }
                    }
                }
                // exploration-plateau termination (P2): a read-only round re-covering only already-seen
                // files/results (novel call signatures, same targets) is not progress for flash-class
                // models that explore one call per round: stop after the configured plateau rounds,
                // keeping the todo auto-continue as the escape hatch when todos are open
                if (plateauLimit != 0 && !roundCoverage.isEmpty() && AgentLoop.isPlateauRound(roundCoverage)) {
                    if (host.unfinishedTodos(toolsRanThisTurn) != null) {
                        host.plateauRounds = 0;
                    } else {
                        host.plateauRounds++;
                        final var plateauCap = Math.abs(plateauLimit);
                        final var plateauStops = plateauLimit > 0;
                        // one-shot strategy-change nudge (plateauFeedback nudge_1 / nudge_2): before the
                        // hard stop the model gets a chance to adapt instead of losing the whole turn.
                        // In nudge-only mode (plateauLimit < 0) the guard never hard-stops, so the nudge
                        // fires when the streak reaches the cap (abs value) to remind without killing the turn.
                        final int nudgeAt = plateauFeedback == PlateauFeedbackMode.NONE
                                ? 0
                                : plateauStops
                                        ? AgentLoop.plateauFeedbackNudgeAt(plateauFeedback, plateauCap)
                                        : plateauCap;
                        if (nudgeAt > 0 && !host.plateauNudged && host.plateauRounds >= nudgeAt) {
                            host.plateauNudged = true;
                            host.emit(host.notice(
                                    AgentEvent.Notice.Level.INFO,
                                    "exploration-plateau nudge sent to the model (round " + host.plateauRounds + '/'
                                            + plateauCap + (plateauStops ? "" : ",nudge-only") + ")"));
                            messages.add(Message.user(AgentLoop.PLATEAU_NUDGE_PROMPT));
                            continue roundLoop;
                        }
                        if (plateauStops && host.plateauRounds >= plateauCap) {
                            host.persistStopNotice(
                                    sessionId,
                                    "Exploration plateau: the model made " + plateauCap
                                            + " consecutive read-only tool rounds re-covering only already-seen files/results.");
                            host.emit(host.error("Exploration plateau: the model made " + plateauCap
                                    + " consecutive read-only tool rounds re-covering only already-seen files/results."
                                    + " Stopping the turn."));
                            host.emitTurnMetrics(null, turnStartNanos);
                            return null;
                        }
                    }
                } else {
                    host.plateauRounds = 0;
                }
                // exploration nudge (novel-spiral complement to the plateau guard): plateau/doom-loop
                // only catch redundant work; a spiral of NOVEL reads/globs is informative every round
                // and slips through. After llm.explorationNudgeRounds such consecutive rounds without
                // an answer, inject a one-shot wrap-up directive (no hard stop: a genuinely deep task
                // can still continue gathering - the model decides).
                final var readOnlyRound = !roundCoverage.contains(AgentLoop.WRITE_MARKER)
                        && !roundCoverage.contains(AgentLoop.INELIGIBLE_EXPLORATION_MARKER)
                        && !roundCoverage.isEmpty()
                        && !roundMutatingBash[0];
                if (readOnlyRound && roundInformative[0]) {
                    host.explorationRounds++;
                    if (explorationNudgeRounds > 0
                            && !host.explorationNudged
                            && host.explorationRounds >= explorationNudgeRounds) {
                        host.explorationNudged = true;
                        host.emit(host.notice(
                                AgentEvent.Notice.Level.INFO,
                                "exploration nudge sent to the model (round " + host.explorationRounds + ')'));
                        messages.add(Message.user(AgentLoop.EXPLORATION_NUDGE_PROMPT));
                        continue roundLoop;
                    }
                } else {
                    host.explorationRounds = 0;
                }
                if (!toolDirectiveApplied && host.contextView != null) {
                    final var directive = host.contextView.poll();
                    if (directive != null) {
                        toolDirectiveApplied = true;
                        applyToolDirective(sessionId, messages, directive, toolsTokens, cancelled);
                    }
                }
            }
            // the window's iteration budget ran out while the turn was still running: when todos are
            // pending and auto-continuation is allowed, start a fresh window instead of stopping, so a
            // model that keeps calling tools is not capped by the per-window budget alone
            if (cancelled.getAsBoolean()) {
                host.emit(new AgentEvent.TurnCancelled(
                        host.currentSessionId, host.subagentId, host.subagentName, host.sessionWorkdir));
                host.emitTurnMetrics(null, turnStartNanos);
                return null;
            }
            if (host.tryTodoAutoContinue(messages, "max_iterations", toolsRanThisTurn, userInjected)) {
                continue windowLoop;
            }
            // an unbounded window never exhausts its budget: reaching here means the loop was broken
            // out of (eg. context kept filling up), so there is no "iteration cap" to report
            final var capReached = maxIterations > 0;
            host.persistStopNotice(
                    sessionId,
                    capReached
                            ? "Maximum iterations reached (" + maxIterations
                                    + "): turn stopped; resume starts a fresh window."
                            : "Turn ended without an iteration cap; resume continues the remaining work.");
            host.turnState.iterationLimitReached = capReached;
            host.emit(host.error(
                    capReached
                            ? "Maximum iterations reached (" + maxIterations + ")"
                            : "Turn ended before completion"));
            host.emitTurnMetrics(null, turnStartNanos);
            return null;
        }
        host.emit(new AgentEvent.TurnCancelled(
                host.currentSessionId, host.subagentId, host.subagentName, host.sessionWorkdir));
        host.emitTurnMetrics(null, turnStartNanos);
        return null;
    }

    private void persistContext(final String sessionId, final List<Message> messages) {
        if (host.sessionStore == null || sessionId == null || messages.isEmpty()) {
            return;
        }
        try {
            host.sessionStore.replace(
                    sessionId,
                    (messages.get(0).role().equals("system") ? messages.subList(1, messages.size()) : messages)
                            .stream().filter(m -> !AgentLoop.isVolatileTail(m)).toList());
            host.persistDirty = false;
        } catch (final RuntimeException e) {
            host.persistDirty = true; // retried at the next persistContext / compaction point
            AgentLoop.LOGGER.log(SessionAwareLogRecord.of(
                    AgentLoop.LOGGER,
                    sessionId,
                    Level.WARNING,
                    "Could not persist the compacted session " + sessionId,
                    e));
            host.emit(host.notice(
                    AgentEvent.Notice.Level.WARN, "Compacted context could not be persisted: " + e.getMessage()));
        }
    }

    private Map<String, Path> readsFromHistory(final List<Message> messages) {
        if (messages.isEmpty()) {
            return Map.of();
        }
        Map<String, Path> out = null;
        // a read whose result never landed (interrupted/cancelled turn) or was replaced by a
        // lost-result marker is not a live read: index only reads with a present tool result
        final var landedResults = new HashSet<String>();
        for (final var message : messages) {
            if ("tool".equals(message.role()) && message.toolCallId() != null) {
                final var content = String.valueOf(message.content());
                if (!content.startsWith(
                        ToolCallPairingRepair.TOOL_RESULT_LOST_MARKER)) { // host.repairInterruptedToolCalls markers
                    landedResults.add(message.toolCallId());
                }
            }
        }
        for (final var message : messages) {
            if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
                continue;
            }
            for (final var call : message.toolCalls()) {
                final var fn = call.function();
                if (fn == null
                        || !"read".equals(fn.name())
                        || fn.arguments() == null
                        || !landedResults.contains(call.id())) {
                    continue;
                }
                try {
                    final var parsed = host.mapper.fromString(Object.class, fn.arguments());
                    if (parsed instanceof final Map<?, ?> m
                            && m.get("path") instanceof final String p
                            && !p.isBlank()) {
                        if (out == null) {
                            out = new LinkedHashMap<>();
                        }
                        out.put(
                                call.id(),
                                host.sessionWorkdir
                                        .resolve(Path.of(p))
                                        .toAbsolutePath()
                                        .normalize());
                    }
                } catch (final RuntimeException ignored) {
                    // unreadable arguments in history are simply not indexed
                }
            }
        }
        return out == null ? Map.of() : out;
    }

    private void recordThroughput(
            final Message assistant, final io.yupiik.ia.agent.core.llm.Usage usage, final long activeMs) {
        host.turnNotificationReporter.recordThroughput(assistant, usage, activeMs);
    }

    private void repairInterruptedToolCalls(final String sessionId, final List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        final var result = host.pairingRepair.repairAndPersist(sessionId, messages);
        if (result.changed()) {
            host.emit(host.status("repaired " + result.healed() + " interrupted tool call(s) whose result never landed"
                    + (result.droppedOrphans() == 0
                            ? ""
                            : ", dropped " + result.droppedOrphans() + " orphan tool response(s)")));
        }
    }

    private ToolOutcome runGuarded(
            final ToolCall call,
            final AgentLoop.ToolRepeats repeats,
            final BooleanSupplier cancelled,
            final ChatSettings settings) {
        // register the callId -> signature mapping on the loop thread (before any dispatch) so record()
        // can match the result back for reads too, feeding the per-signature no-progress decision.
        repeats.register(call, host::normalizedArguments);
        // the doom-loop guard targets state-changing/bash calls only: read-only rounds are owned by
        // the exploration-plateau guard (semantic re-coverage), so a read is never signature-refused here.
        if (host.isReadOnly(call)) {
            return host.runTool(call, cancelled, settings);
        }
        final var repeated = repeats.rejectIfRepeated(call, host::normalizedArguments);
        if (repeated != null) {
            host.emit(toolFinished(repeated.name(), repeated.output(), false, false, -1));
            return repeated;
        }
        return host.runTool(call, cancelled, settings);
    }

    private String scrub(final String text) {
        final var c = host.compression();
        return c == null ? new ContextCompression(null).stripEchoedRefs(text) : c.stripEchoedRefs(text);
    }

    private ContextView.Snapshot snapshot(final List<Message> messages, final int toolsTokens) {
        final var effective = host.usageTracker.effectiveContext(messages, toolsTokens);
        return new ContextView.Snapshot(
                messages.size(),
                effective,
                host.usageTracker.contextWindowTokenCount().value(),
                host.usageTracker.usableWindow(),
                host.usageTracker.contextPercent(effective),
                host.configuration.pruning().enabled()
                        ? host.configuration.pruning().thresholdPercent()
                        : Integer.MAX_VALUE,
                host.configuration.compaction().enabled()
                        ? host.configuration.compaction().thresholdPercent()
                        : Integer.MAX_VALUE);
    }

    private static String staleNote(final List<Path> paths) {
        return "\n[stale read cleared: "
                + String.join(", ", paths.stream().map(Path::toString).toList())
                + " - don't re-read now; re-read only before a future edit]";
    }

    private List<Map<String, Object>> toReasoningItemsPayload(final List<ReasoningItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        final var out = new ArrayList<Map<String, Object>>(items.size());
        for (final var item : items) {
            final var map = new LinkedHashMap<String, Object>();
            if (item.id() != null && !item.id().isEmpty()) {
                map.put("id", item.id());
            }
            if (item.summary() != null && !item.summary().isEmpty()) {
                map.put("summary", item.summary());
            }
            if (item.encryptedContent() != null && !item.encryptedContent().isEmpty()) {
                map.put("encrypted_content", item.encryptedContent());
            }
            if (!map.isEmpty()) {
                out.add(map);
            }
        }
        return out;
    }

    private AgentEvent.ToolFinished toolFinished(
            final String name, final String output, final boolean success, final boolean pending, final long ordinal) {
        return new AgentEvent.ToolFinished(
                name,
                output,
                success,
                pending,
                ordinal,
                host.currentSessionId,
                host.subagentId,
                host.subagentName,
                host.sessionWorkdir);
    }

    void attachSessionSettings(final ChatSettings settings) {
        if (host.usageTracker != null) {
            host.usageTracker.attach(settings);
        }
        if (host.quotaStore != null) {
            host.usageReporter.attachQuota(settings);
        }
    }

    /**
     * Re-runs the agent loop on the existing history without adding any user message: nothing is
     * appended to the conversation and nothing is persisted as input, so resuming costs zero extra
     * prompt tokens. The last real user message (when found) still drives prompt building (memory
     * ranking, todo hints) so the rebuilt system prompt stays relevant to the ongoing task.
     */
    void emitResumeAfterCapNotice(final List<Message> history, final String sessionId) {
        if (history == null || history.isEmpty() || host.currentSessionId == null) {
            return;
        }
        final var last = history.get(history.size() - 1);
        if ("system".equals(last.role())
                && last.content() instanceof final String content
                && content.startsWith("Maximum iterations reached")) {
            host.emit(host.notice(
                    AgentEvent.Notice.Level.INFO,
                    "resuming after an iteration-cap stop: this turn starts with a fresh window."));
        }
    }

    /**
     * Whether the todo store still has at least one pending item (not completed/cancelled). The
     * LAZY memory regime extracts only when this is false - a natural milestone with no open task.
     * An empty or absent todo list (or a store that throws) reports no pending items.
     *
     * public boolean requestUserContextOperation(
     * final String action, final String focus, final int keepLast, final boolean force, final boolean urgent) {
     * if (host.contextView == null) {
     * return false;
     * }
     * return host.contextView.request(new ContextView.Directive(action, focus, keepLast, "user", force, urgent));
     * }
     *
     * /**
     * Persists an in-loop context reduction to the session store (without the leading system
     * prompt, which is regenerated every turn) so the next turn reloads the compacted history
     * instead of re-compacting the same full transcript. Failures never break the turn.
     */
    /**
     * Builds the {@code others} map carrying the producer identity (provider, effective model,
     * agent) of the block about to be persisted. The identity is captured at write time so a
     * replay or a template change can re-render the block's speaker label from what actually
     * produced it. Never sent to the LLM (stripped at wire boundaries).
     */

    /**
     * The {@code others} map persisted with an assistant message: the producer identity (as
     * {@link #identityOthers}) merged with the accumulated reasoning text (under {@link #THINKING_KEY}),
     * so a later replay can re-render the thought blocks. The identity members are captured at write
     * time; the thinking is whatever streamed for this request. Never sent to the LLM ({@code others}
     * is stripped at every wire boundary). Returns {@code null} when there is nothing to persist.
     */

    /**
     * Converts the captured reasoning items into a JSON-serializable payload for the session store
     * (each entry carries {@code id}, {@code summary} and {@code encrypted_content}). Returns an empty
     * list when none were captured.
     */

    /**
     * Zero-LLM context strategies run at the same point as the automatic prune (start of an
     * iteration), in a single non-incremental transform:
     * <ol>
     *   <li>deduplicate identical tool calls (same tool, same normalized arguments) keeping the latest,
     *       protected tools excluded;</li>
     *   <li>supersede write inputs overwritten by a later read of the same file (opt-in);</li>
     *   <li>purge the (potentially large) inputs of failed tool calls after a number of turns.</li>
     * </ol>
     * Each applied strategy reports its {@code Compacted} kind through the returned list (the caller
     * merges it with the automatic prune/compact of the same checkpoint into a single event). The
     * conversation list is mutated in place because the loop owns it and keeps the call/result
     * pairing intact.
     *
     * @return the kinds of the strategies that changed the history, in execution order
     */
    List<String> applyZeroLlmStrategies(final List<Message> messages) {
        return host.applyZeroLlmStrategies(messages);
    }

    public String resume(
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled) {
        return resume(agentName, sessionId, history, cancelled, null);
    }

    /**
     * Same as {@link #resume(String, String, List, BooleanSupplier)} but polls
     * {@code queuedPrompt} so prompts typed while the resume runs are injected.
     */
    public String resume(
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt) {
        return doRun(host.lastUserPrompt(history), null, agentName, sessionId, history, cancelled, queuedPrompt, null);
    }

    public String resume(
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt,
            final String explicitModel) {
        return doRun(
                host.lastUserPrompt(history),
                null,
                agentName,
                sessionId,
                history,
                cancelled,
                queuedPrompt,
                explicitModel);
    }

    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled) {
        return run(prompt, agentName, sessionId, history, cancelled, null, null);
    }

    /**
     * @param queuedPrompt source of a prompt queued while this turn runs; when
     *                      non-null the loop polls it between iterations and after
     *                      each assistant reply, appending it as the next user
     *                      message so the queued prompt is handled by the very
     *                      same turn instead of waiting for it to finish.
     */
    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt) {
        return run(prompt, agentName, sessionId, history, cancelled, queuedPrompt, null);
    }

    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt,
            final String explicitModel) {
        if (host.notificationService != null && host.subagentId == null) {
            host.notificationService.onSessionInteraction(sessionId);
        }
        return doRun(prompt, prompt, agentName, sessionId, history, cancelled, queuedPrompt, explicitModel);
    }

    /** The per-session provider/model selection rides the session's {@link ChatSettings}; make the tracker see it. */
    AgentEvent.TextDelta textDelta(final String delta) {
        return new AgentEvent.TextDelta(
                delta, host.currentSessionId, host.subagentId, host.subagentName, host.sessionWorkdir);
    }

    AgentEvent.TextReset textReset() {
        return new AgentEvent.TextReset(host.currentSessionId, host.subagentId, host.subagentName, host.sessionWorkdir);
    }

    AgentEvent.ThinkingDelta thinkingDelta(final String delta) {
        return new AgentEvent.ThinkingDelta(
                delta, host.currentSessionId, host.subagentId, host.subagentName, host.sessionWorkdir);
    }
}
