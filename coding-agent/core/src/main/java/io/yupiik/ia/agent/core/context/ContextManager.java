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

import io.yupiik.ia.agent.core.configuration.BufferConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CompactionLlmMode;
import io.yupiik.ia.agent.core.configuration.GateConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Triggers for context pruning/compaction and thin facades over the
 * {@link CompactionCoordinator}. The coordinator owns the retry/degrade logic
 * and honest-result reporting so compaction never fails silently.
 */
public class ContextManager {
    private static final Logger LOGGER = Logger.getLogger(ContextManager.class.getName());

    private final CodingAgentConfiguration configuration;
    private final UsageTracker usageTracker;
    private final CompactionCoordinator coordinator;

    public ContextManager(
            final CodingAgentConfiguration configuration,
            final UsageTracker usageTracker,
            final CompactionCoordinator coordinator) {
        this.configuration = configuration;
        this.usageTracker = usageTracker;
        this.coordinator = coordinator;
    }

    protected ContextManager() {
        this.configuration = null;
        this.usageTracker = null;
        this.coordinator = null;
    }

    public boolean pruneNeeded(final List<Message> messages) {
        return pruneNeeded(messages, 0);
    }

    public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
        final var p = configuration.pruning();
        return p.enabled()
                && usageTracker.contextPercent(usageTracker.budgetContext(messages, extraToolTokens))
                        >= p.thresholdPercent();
    }

    public boolean compactNeeded(final List<Message> messages) {
        return compactNeeded(messages, 0);
    }

    public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
        final var c = configuration.compaction();
        if (!c.enabled()) {
            return false;
        }
        final var effective = usageTracker.budgetContext(messages, extraToolTokens);
        if (c.thresholdPercent() > 0) {
            return usageTracker.contextPercent(effective) >= c.thresholdPercent();
        }
        return effective >= compactionTriggerTokens();
    }

    /**
     * The prompt token count that triggers the next automatic compaction: the legacy percentage of the
     * usable window when {@code thresholdPercent > 0}, else the opencode-style buffer trigger capped so
     * it never lies above a safety fraction of the usable window. A large declared output cap would
     * otherwise push the buffer trigger beyond what the input may actually occupy; without the safety
     * fraction the trigger would clamp exactly onto the usable window and let the context gauge cross
     * 100% without ever tripping an automatic compaction. Also used by the anti-thrash notice and the
     * usage status so every surface reports the same wall.
     *
     * @return the trigger in estimated prompt tokens, or {@link Integer#MAX_VALUE} when compaction is disabled.
     */
    public int compactionTriggerTokens() {
        if (configuration == null || usageTracker == null) {
            return Integer.MAX_VALUE;
        }
        final var c = configuration.compaction();
        if (!c.enabled()) {
            return Integer.MAX_VALUE;
        }
        if (c.thresholdPercent() > 0) {
            return (int) Math.ceil(usageTracker.usableWindow() * c.thresholdPercent() / 100.0);
        }
        final var b = c.resolvedBuffer();
        return ContextBudget.compactionTrigger(
                usageTracker.contextWindowTokenCount().value(),
                b.percent(),
                b.minTokens(),
                usageTracker.usableWindow(),
                b.safetyPercent());
    }

    /** Hard prompt-input limit, excluding output headroom and including external tool-schema tokens at call sites. */
    public int usableWindowTokens() {
        return usageTracker == null ? Integer.MAX_VALUE : usageTracker.usableWindow();
    }

    /**
     * The automatic-compaction trigger for the session's provider selection: same computation as
     * {@link #compactionTriggerTokens()} but honors a per-provider {@code compaction.buffer.safetyPercent}
     * override when configured.
     */
    public int compactionTriggerTokens(final ChatSettings settings) {
        if (configuration == null || usageTracker == null) {
            return Integer.MAX_VALUE;
        }
        final var c = configuration.compaction();
        if (c == null || !c.enabled()) {
            return usageTracker.usableWindow();
        }
        if (c.thresholdPercent() > 0) {
            return (int) Math.ceil(usageTracker.usableWindow() * c.thresholdPercent() / 100.0);
        }
        final var b = coordinator == null ? c.resolvedBuffer() : coordinator.resolvedBuffer(settings);
        return ContextBudget.compactionTrigger(
                usageTracker.contextWindowTokenCount().value(),
                b.percent(),
                b.minTokens(),
                usageTracker.usableWindow(),
                b.safetyPercent());
    }

    /** The per-provider merged compaction-gate budgets for the session, or the global/defaults when unset. */
    public GateConfiguration resolvedGate(final ChatSettings settings) {
        if (coordinator != null) {
            return coordinator.resolvedGate(settings);
        }
        return configuration != null && configuration.compaction() != null
                ? configuration.compaction().resolvedGate()
                : new GateConfiguration(
                        GateConfiguration.DEFAULT_MAX_ATTEMPTS, GateConfiguration.DEFAULT_MAX_DURATION_MS);
    }

    /** Whether compaction must never call an LLM for the session's provider selection (global + per-provider merge). */
    public boolean ignoreLlm(final ChatSettings settings) {
        return coordinator != null && coordinator.ignoreLlm(settings);
    }

    /** The per-provider merged buffer-mode trigger knobs for the session, or the global/defaults when unset. */
    public BufferConfiguration resolvedBuffer(final ChatSettings settings) {
        if (coordinator != null) {
            return coordinator.resolvedBuffer(settings);
        }
        return configuration != null && configuration.compaction() != null
                ? configuration.compaction().resolvedBuffer()
                : new BufferConfiguration(
                        BufferConfiguration.DEFAULT_PERCENT,
                        BufferConfiguration.DEFAULT_MIN_TOKENS,
                        BufferConfiguration.DEFAULT_SAFETY_PERCENT);
    }

    public CompactionResult prune(final List<Message> messages) {
        resetSpeculation();
        return coordinator.prune(messages);
    }

    public CompactionResult compact(final List<Message> messages) {
        resetSpeculation();
        return coordinator.compact(messages, false, null, 0);
    }

    /**
     * Explicit {@code /compact force}: runs compaction even when the context is still within
     * budget, using the tighter (halved tail) floors so short histories compact too.
     */
    public CompactionResult compact(final List<Message> messages, final boolean force) {
        resetSpeculation();
        return coordinator.compact(messages, force, null, 0);
    }

    /**
     * Model-requested compaction: an optional focus hint is folded into the summarizer prompt and an
     * optional {@code keepLastOverride} raises the verbatim tail floor.
     */
    public CompactionResult compact(final List<Message> messages, final String focus, final int keepLastOverride) {
        resetSpeculation();
        return coordinator.compact(messages, false, focus, keepLastOverride);
    }

    /**
     * Explicit compaction with a {@code force} flag (bypasses the within-budget guard so compaction
     * runs even below the thresholds), an optional focus hint folded into the summarizer prompt and an
     * optional {@code keepLastOverride} raising the verbatim tail floor.
     */
    public CompactionResult compact(
            final List<Message> messages, final boolean force, final String focus, final int keepLastOverride) {
        resetSpeculation();
        return coordinator.compact(messages, force, focus, keepLastOverride);
    }

    public CompactionResult compact(
            final List<Message> messages,
            final boolean force,
            final String focus,
            final int keepLastOverride,
            final BooleanSupplier cancelled) {
        resetSpeculation();
        return coordinator.compact(messages, force, focus, keepLastOverride, cancelled, ChatSettings.EMPTY);
    }

    /** {@link #compact(List, boolean, String, int, BooleanSupplier)} for the session's model selection. */
    public CompactionResult compact(
            final List<Message> messages,
            final boolean force,
            final String focus,
            final int keepLastOverride,
            final BooleanSupplier cancelled,
            final ChatSettings settings) {
        resetSpeculation();
        return coordinator.compact(messages, force, focus, keepLastOverride, cancelled, settings);
    }

    /**
     * {@link #compact(List, boolean, String, int, BooleanSupplier, ChatSettings)} with an urgent
     * flag: a manual user-requested compaction that falls back to a destructive local truncation
     * when the summarizer fails transiently, instead of skipping the pass and keeping the raw
     * context. Opt-in only (the {code /compact urgent} slash command); automatic compaction never
     * uses it so the non-destructive contract is preserved.
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
     * {@link #compact(List, boolean, String, int, boolean, BooleanSupplier, ChatSettings)} with a
     * per-invocation {@link CompactionCoordinator.CompactionProgress} hook: the same calibrated
     * effective-context estimate (including the wire tool tokens) is published through the hook and
     * printed by the progress notice, keeping the frontends' context gauge aligned with the notice.
     */
    public CompactionResult compact(
            final List<Message> messages,
            final boolean force,
            final String focus,
            final int keepLastOverride,
            final boolean urgent,
            final BooleanSupplier cancelled,
            final ChatSettings settings,
            final CompactionCoordinator.CompactionProgress progress) {
        if (coordinator == null) {
            return new CompactionResult(messages, CompactionResult.Mode.NONE, "");
        }
        resetSpeculation();
        return coordinator.compact(messages, force, focus, keepLastOverride, urgent, cancelled, settings, progress);
    }

    /** One tighter, bounded retry after automatic compaction leaves usage above its trigger. */
    public CompactionResult compactForRecovery(
            final List<Message> messages, final BooleanSupplier cancelled, final ChatSettings settings) {
        return compact(messages, true, null, 0, cancelled, settings);
    }

    /**
     * {@link #compactForRecovery(List, BooleanSupplier, ChatSettings)} with the per-invocation
     * {@link CompactionCoordinator.CompactionProgress} hook (see the 8-parameter
     * {@link #compact(List, boolean, String, int, boolean, BooleanSupplier, ChatSettings, CompactionCoordinator.CompactionProgress)}).
     */
    public CompactionResult compactForRecovery(
            final List<Message> messages,
            final BooleanSupplier cancelled,
            final ChatSettings settings,
            final CompactionCoordinator.CompactionProgress progress) {
        // without a progress hook, keep the 3-param variant as the single overridable seam (test
        // doubles, subclasses without a coordinator); with progress, thread it to the coordinator
        return progress == null
                ? compactForRecovery(messages, cancelled, settings)
                : compact(messages, true, null, 0, false, cancelled, settings, progress);
    }

    /**
     * LLM-free local spill toward a token target (the compaction gate's [no-LM] reduction step):
     * splices pre-digest tool outputs so the estimate drops toward {@code targetTokens}; never touches
     * the system prompt or the digest. Delegates to {@link CompactionCoordinator#localSpill}.
     */
    public CompactionResult localSpill(final List<Message> messages, final int targetTokens) {
        resetSpeculation();
        if (coordinator == null) {
            return new CompactionResult(messages, CompactionResult.Mode.NONE, "");
        }
        return coordinator.localSpill(messages, targetTokens);
    }

    public CompactionResult compactForOverflow(final List<Message> messages) {
        resetSpeculation();
        return coordinator.compact(messages, true, null, 0, Cancellations.NEVER, ChatSettings.EMPTY);
    }

    public CompactionResult compactForOverflow(final List<Message> messages, final BooleanSupplier cancelled) {
        resetSpeculation();
        return coordinator.compact(messages, true, null, 0, cancelled, ChatSettings.EMPTY);
    }

    /** {@link #compactForOverflow(List, BooleanSupplier)} for the session's model selection. */
    public CompactionResult compactForOverflow(
            final List<Message> messages, final BooleanSupplier cancelled, final ChatSettings settings) {
        resetSpeculation();
        return coordinator.compact(messages, true, null, 0, cancelled, settings);
    }

    /**
     * {@link #compactForOverflow(List, BooleanSupplier, ChatSettings)} with the per-invocation
     * {@link CompactionCoordinator.CompactionProgress} hook (see the 8-parameter
     * {@link #compact(List, boolean, String, int, boolean, BooleanSupplier, ChatSettings, CompactionCoordinator.CompactionProgress)}).
     */
    public CompactionResult compactForOverflow(
            final List<Message> messages,
            final BooleanSupplier cancelled,
            final ChatSettings settings,
            final CompactionCoordinator.CompactionProgress progress) {
        // without a progress hook, keep the 3-param variant as the single overridable seam (test
        // doubles, subclasses without a coordinator); with progress, thread it to the coordinator
        return progress == null
                ? compactForOverflow(messages, cancelled, settings)
                : compact(messages, true, null, 0, false, cancelled, settings, progress);
    }

    private volatile SpeculativeCompactionManager speculation;

    public boolean speculationEnabled() {
        return configuration != null
                && configuration.compaction() != null
                && configuration.compaction().enabled()
                && configuration.compaction().mode() != CompactionLlmMode.NO_LLM
                && configuration.compaction().resolvedSpeculative().concurrent();
    }

    public int speculativeWatermarkTokens() {
        final var trigger = compactionTriggerTokens();
        return trigger == Integer.MAX_VALUE
                ? trigger
                : (int) ((long) trigger
                        * configuration.compaction().resolvedSpeculative().startPercent()
                        / 100);
    }

    /** Best-effort capture; this path is a strict no-op while disabled. */
    public boolean maybeLaunchSpeculation(
            final List<Message> messages,
            final int extraToolTokens,
            final BooleanSupplier cancelled,
            final ChatSettings settings) {
        if (!speculationEnabled()
                || coordinator == null
                || coordinator.ignoreLlm(settings)
                || cancelled.getAsBoolean()
                || usageTracker == null) {
            return false;
        }
        try {
            final var effective = usageTracker.effectiveContext(messages, extraToolTokens);
            if (effective < speculativeWatermarkTokens() || effective >= compactionTriggerTokens()) {
                return false;
            }
            final var manager = manager();
            if (!manager.canLaunch()) {
                return false;
            }
            final var source = coordinator.prepareSpeculation(messages, settings, extraToolTokens);
            if (source.isEmpty() || coordinator.hookVetoed() || cancelled.getAsBoolean()) {
                return false;
            }
            final var launched = manager.launch(source.get(), cancelled);
            if (launched) {
                LOGGER.fine(() -> "Speculative compaction launched, estimated tokens="
                        + source.get().capturedTokens());
            }
            return launched;
        } catch (final RuntimeException e) {
            LOGGER.log(Level.FINE, "Speculative compaction preparation failed; continuing foreground", e);
            return false;
        }
    }

    /** Null means use synchronous compaction; NONE means cancellation/hook veto, without a bypass retry. */
    public CompactionResult adoptSpeculation(
            final List<Message> messages,
            final int extraToolTokens,
            final BooleanSupplier cancelled,
            final ChatSettings settings) {
        final var manager = speculation;
        if (manager == null || manager.source() == null) {
            return null;
        }
        if (coordinator.ignoreLlm(settings)) {
            manager.invalidate();
            return null;
        }
        if (cancelled.getAsBoolean()) {
            manager.invalidate();
            return new CompactionResult(messages, CompactionResult.Mode.NONE, "cancelled");
        }
        final var source = manager.source();
        if (!speculationEnabled() || !coordinator.compatibleSpeculation(source, messages, settings)) {
            manager.invalidate();
            return null;
        }
        final var started = System.nanoTime();
        final var candidate = manager.take(source, cancelled).orElse(null);
        LOGGER.fine(
                () -> "Speculative compaction wait ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return new CompactionResult(messages, CompactionResult.Mode.NONE, "cancelled");
        }
        if (candidate == null || !coordinator.compatibleSpeculation(source, messages, settings)) {
            return null;
        }
        if (coordinator.hookVetoed()) {
            return new CompactionResult(messages, CompactionResult.Mode.NONE, "compaction vetoed by hook");
        }
        final var adoption = coordinator.validateAndAssemble(
                candidate, messages, coordinator.eligibleCutoff(messages), extraToolTokens);
        if (!adoption.adopted()) {
            LOGGER.fine(() -> "Speculative candidate rejected: " + adoption.rejection());
            return null;
        }
        LOGGER.fine(() -> "Speculative compaction adopted, calls=" + candidate.summarizerCalls() + ", generation ms="
                + candidate.elapsedMillis());
        return adoption.result();
    }

    /** Invalidate before a history rewrite or another compaction operation. */
    public void resetSpeculation() {
        final var manager = speculation;
        if (manager != null) {
            manager.newGeneration();
        }
    }

    /** Ends the run without treating cancelled client futures as proof that inference stopped. */
    public void shutdownSpeculation() {
        final var manager = speculation;
        if (manager != null) {
            manager.close();
        }
    }

    private synchronized SpeculativeCompactionManager manager() {
        if (speculation == null) {
            speculation = new SpeculativeCompactionManager(coordinator::generateSpeculativeSummary);
        }
        return speculation;
    }
}
