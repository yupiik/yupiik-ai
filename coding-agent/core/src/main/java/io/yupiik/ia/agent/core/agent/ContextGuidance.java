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

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ContextLimit;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds the context-usage line, the compression/legacy nudge blocks and the compression ref
 * guidance appended to the volatile session-state tail. Owns the nudge cadence state
 * ({@code compressionNudgeRounds}, {@code lastHardCompressionNudgeRound},
 * {@code compressionNudgeCooldownUntil}, {@code lastCompressionSavedTokens}).
 */
public class ContextGuidance {
    private final CodingAgentConfiguration configuration;
    private final Supplier<UsageTracker> usageTracker;
    private final Supplier<ContextView> contextView;
    private final Supplier<ProviderManager> providerManager;
    private final Supplier<ChatSettings> chatSettings;

    private int compressionNudgeRounds;
    private int lastHardCompressionNudgeRound = Integer.MIN_VALUE;
    private int compressionNudgeCooldownUntil = Integer.MIN_VALUE;
    private int lastCompressionSavedTokens = -1;

    public ContextGuidance(
            final CodingAgentConfiguration configuration,
            final Supplier<UsageTracker> usageTracker,
            final Supplier<ContextView> contextView,
            final Supplier<ProviderManager> providerManager,
            final Supplier<ChatSettings> chatSettings) {
        this.configuration = configuration;
        this.usageTracker = usageTracker;
        this.contextView = contextView;
        this.providerManager = providerManager;
        this.chatSettings = chatSettings;
    }

    public int compressionNudgeRounds() {
        return compressionNudgeRounds;
    }

    /** Marks the current round as the last hard-nudge round (called by the loop after the request). */
    public void markHardNudgeRound() {
        this.lastHardCompressionNudgeRound = compressionNudgeRounds;
    }

    /**
     * Unconditional context-usage line rendered in every volatile session-state tail: the model always
     * sees the current usage estimate and the prune/compaction thresholds, whether or not the nudge
     * fires. Kept out of the stable system prefix so prompt caching is unaffected.
     */
    public String contextUsageLine(final List<Message> messages, final int toolsTokens) {
        final var compression = configuration.compression();
        if (compression != null && compression.enabled() && contextView.get() != null) {
            final var window = usageTracker.get().contextWindowTokenCount().value();
            final var targets = contextView.get().compression().targets(window, resolvedContextLimit());
            final var raw = usageTracker.get().effectiveContext(messages, toolsTokens);
            final var effective = raw;
            final var percent = Math.round(usageTracker.get().contextPercent(effective) / 5d) * 5;
            // report the working-context targets exactly as the nudge and the status tool see them,
            // so the line, the nudge and `compress status` never appear to contradict one another.
            return "Context usage: ~" + (int) percent + "% of the usable window (compress at " + targets.baseMin()
                    + " / must at " + targets.effectiveMax() + " tokens).\n";
        }
        final var percent =
                Math.round(usageTracker.get().contextPercent(usageTracker.get().effectiveContext(messages, toolsTokens))
                                / 5d)
                        * 5;
        return "Context usage: ~" + (int) percent + "% of the usable window (prune at "
                + configuration.pruning().thresholdPercent() + "%, compaction at "
                + configuration.compaction().thresholdPercent() + "%).\n";
    }

    /**
     * Context-usage nudge appended to the volatile session state. With compression enabled this
     * follows the working-context model: a soft reminder once the effective size crosses the configured
     * minimum and enough iterations passed since the last user message, then a stronger reminder above
     * the maximum, repeated at most every {@code nudgeFrequency} real LLM rounds. The thresholds are
     * proactive working-context targets, not model capacity, so the wording states that explicitly
     * and never demands whole-session compaction or blocking active work. The legacy prune/compaction
     * nudge remains for the pruning opt-in (disabled by default).
     */
    public String nudgeBlock(final List<Message> messages, final int toolsTokens) {
        final var compression = configuration.compression();
        if (compression == null || !compression.enabled() || contextView.get() == null) {
            return legacyPruningNudge(messages, toolsTokens);
        }
        final var tier = compressionNudgeTier(messages, toolsTokens);
        if (tier == 2) {
            final var raw = usageTracker.get().effectiveContext(messages, toolsTokens);
            final var effective = raw;
            final var targets = contextView
                    .get()
                    .compression()
                    .targets(usageTracker.get().contextWindowTokenCount().value(), resolvedContextLimit());
            final var percent = Math.round(usageTracker.get().contextPercent(effective));
            return "\n<nudge>The working context reached ~" + effective + " tokens, above the "
                    + targets.effectiveMax() + " working-context target (~" + percent + "% of model capacity). "
                    + "This is a proactive working-context target, not an out-of-memory alarm. Compress genuinely "
                    + "closed ranges now if it is safe to do so; keep the active work verbatim and do not use a "
                    + "whole-session compact just to meet this target.</nudge>";
        }
        if (tier == 1) {
            final var raw = usageTracker.get().effectiveContext(messages, toolsTokens);
            final var effective = raw;
            final var targets = contextView
                    .get()
                    .compression()
                    .targets(usageTracker.get().contextWindowTokenCount().value(), resolvedContextLimit());
            return "\n<nudge>Working context is ~" + effective + " tokens (compress at " + targets.baseMin()
                    + " / must at " + targets.effectiveMax() + "). If a chapter is genuinely closed, call "
                    + "`compress` on its exact inclusive range now (see the `compactIds=A-B` boundary"
                    + " metadata on closed user turns, or call `compress` action=status for the exact"
                    + " pairs) and write an exhaustive summary; keep the current task and its recent"
                    + " messages verbatim and do not block on it. Never guess message references.</nudge>";
        }
        return "";
    }

    /**
     * Bounded, guaranteed-valid closed-turn pairs appended to the volatile session-state tail next
     * to either active compression nudge. The eligible set only contains fully-closed historical turns
     * whose inclusive {@code (startId, endId)} references pass the {@code compress} validation
     * without any snapping (the current open turn, protected recent turns, volatile tails, digests
     * and active-block overlaps are all excluded), so a flash-class model stops guessing stale refs
     * and compresses on the first attempt. Silent when no tier fires;
     * an empty eligible set surfaces an explicit non-blocking "continue working" escalation instead
     * of a bare list, and the case repeats deterministically every hard nudge rather than silently
     * vanishing (the feedback-loop failure documented in PR #207).
     */
    public String compressionRefGuidance(final List<Message> messages, final int tier) {
        final var compression = configuration.compression();
        if (compression == null || !compression.enabled() || contextView.get() == null || tier < 1) {
            return "";
        }
        final var ranges = contextView.get().compression().compressibleClosedTurns();
        if (ranges.isEmpty()) {
            return "\ncompressible ranges: none - no structurally eligible historical ranges are available"
                    + " to summarize right now. Continue the current task; do not guess message"
                    + " references or run a whole-session compact just to meet this working-context"
                    + " target. The automatic model-window compaction still guards capacity.";
        }
        final var sb = new StringBuilder("\ncompressible inclusive ranges: ");
        for (var i = 0; i < ranges.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            final var range = ranges.get(i);
            sb.append(range.startId()).append('-').append(range.endId());
        }
        final var omitted = contextView.get().compression().compressibleClosedTurnCount() - ranges.size();
        if (omitted > 0) {
            sb.append(" (+").append(omitted).append(" omitted)");
        }
        sb.append(" - to summarize a genuinely closed historical turn, call `compress` with its"
                + " exact startId/endId and an exhaustive summary; otherwise continue the current task"
                + " and keep its recent messages verbatim.");
        return sb.toString();
    }

    /** Legacy prune/compaction nudge for the pruning opt-in (disabled by default). */
    public String legacyPruningNudge(final List<Message> messages, final int toolsTokens) {
        final var p = configuration.pruning();
        final var threshold = p.nudgeThresholdPercent();
        if (threshold <= 0) {
            return "";
        }
        final var sinceLastUser = iterationsSinceLastUser(messages);
        if (sinceLastUser < p.nudgeIterationThreshold()) {
            return "";
        }
        final var tokens = usageTracker.get().effectiveContext(messages, toolsTokens);
        if (tokens < minNudgeTokens()) {
            return "";
        }
        final var percent = (int) Math.round(usageTracker.get().contextPercent(tokens));
        if (percent < threshold) {
            return "";
        }
        final var urgent = percent >= p.thresholdPercent();
        final var template = urgent ? p.nudgeUrgentTemplate() : p.nudgeTemplate();
        final var resolved = template == null || template.isBlank()
                ? urgent
                        ? "Context is at ~%USAGE%% and above the prune threshold (%PRUNE%%). Do not start new work - %ACTIONS% now to avoid the hard compaction wall."
                        : "Context is at ~%USAGE%% of the usable window. Prefer wrapping this step up, or compact soon."
                : template;
        return "\n<nudge>"
                + resolved.replace("%USAGE%", String.valueOf(percent))
                        .replace("%PRUNE%", String.valueOf(p.thresholdPercent()))
                        .replace(
                                "%COMPACT%",
                                String.valueOf(configuration.compaction().thresholdPercent()))
                        .replace("%ACTIONS%", manageContextActions())
                + "</nudge>";
    }

    /**
     * Pure (side-effect-free) compression nudge decision for the current round: {@code 0} = none,
     * {@code 1} = soft reminder, {@code 2} = strong reminder. Reads the current cadence state but
     * mutates nothing, so a {@code /context} preview and a real round see the same decision and
     * rendering stays pure.
     */
    public int compressionNudgeTier(final List<Message> messages, final int toolsTokens) {
        final var compression = configuration.compression();
        if (compression == null || !compression.enabled() || contextView.get() == null) {
            return 0;
        }
        final var targets = contextView
                .get()
                .compression()
                .targets(usageTracker.get().contextWindowTokenCount().value(), resolvedContextLimit());
        if (targets.baseMin() <= 0 && targets.baseMax() <= 0) {
            return 0;
        }
        // bounded cooldown right after a successful compress: do not re-hammer the model for a few
        // rounds while it still reaps the benefit (the hard tier otherwise restarts its cadence).
        if (compressionNudgeRounds < compressionNudgeCooldownUntil) {
            return 0;
        }
        final var sinceLastUser = iterationsSinceLastUser(messages);
        final var raw = usageTracker.get().effectiveContext(messages, toolsTokens);
        final var effective = raw;
        // strong tier: above the (buffered) working target, cadenced on REAL rounds so a tool batch
        // that appends many messages cannot make it fire erratically, and spaced at nudgeFrequency.
        if (targets.effectiveMax() > 0
                && effective >= targets.effectiveMax()
                && (lastHardCompressionNudgeRound == Integer.MIN_VALUE
                        || compression.nudgeFrequency() <= 0
                        || compressionNudgeRounds - lastHardCompressionNudgeRound >= compression.nudgeFrequency())) {
            return 2;
        }
        if (targets.baseMin() > 0
                && effective >= targets.baseMin()
                && sinceLastUser >= compression.iterationNudgeThreshold()) {
            return 1;
        }
        return 0;
    }

    /**
     * Advances the compression-nudge cadence once per real LLM request round and arms the post-compress
     * cooldown when a fresh compression landed since the last round. Must be called on the run-loop
     * thread only (never from a preview), and before the volatile tail is appended so the nudge decision
     * reflects the new round.
     */
    public void advanceCompressionCadence(final List<Message> messages) {
        compressionNudgeRounds++;
        final var compression = configuration.compression();
        if (contextView.get() == null || compression == null || !compression.enabled()) {
            return;
        }
        final var saved = contextView.get().compression().savedTokens(messages);
        if (lastCompressionSavedTokens >= 0 && saved > lastCompressionSavedTokens) {
            // a fresh compress reclaimed context: breathe for nudgeFrequency real rounds
            compressionNudgeCooldownUntil = compressionNudgeRounds + Math.max(1, compression.nudgeFrequency());
        }
        lastCompressionSavedTokens = saved;
    }

    /** The resolved working-context limits for the session's provider selection, never null. */
    private ContextLimit resolvedContextLimit() {
        if (providerManager.get() != null) {
            // Prefer the merged (per-provider over global over default) limits when the provider
            // manager can resolve them. A provider manager without a backing configuration cannot,
            // so fall back to this loop's own global config-sourced limits instead.
            final var fromProvider = providerManager.get().resolvedContextLimit(chatSettings.get());
            if (fromProvider != null && (fromProvider.min() != null || fromProvider.max() != null)) {
                return fromProvider;
            }
        }
        final var compression = configuration.compression();
        return compression == null ? new ContextLimit(null, null) : compression.contextLimit();
    }

    public int iterationsSinceLastUser(final List<Message> messages) {
        var sinceLastUser = 0;
        for (var i = messages.size() - 1; i >= 0; i--) {
            if (isVolatileTail(messages.get(i))) {
                continue; // synthetic session-state tail: not a user turn and not a real iteration
            }
            if ("user".equals(messages.get(i).role())) {
                break;
            }
            sinceLastUser++;
        }
        return sinceLastUser;
    }

    private static boolean isVolatileTail(final Message message) {
        return message.others() != null && Boolean.TRUE.equals(message.others().get("volatileTail"));
    }

    /**
     * Effective absolute-token floor below which the nudge is suppressed: the smaller of 90% of the
     * usable window and {@code nudgeMinTokens}, so small-window providers are only nudged close to the
     * compaction wall while large ones get an absolute cap; 0 disables the floor (the percentage then
     * remains the only usage gate).
     */
    public int minNudgeTokens() {
        final var configured = configuration.pruning().nudgeMinTokens();
        if (configured <= 0) {
            return 0;
        }
        return (int) Math.min(usageTracker.get().usableWindow() * 0.9, configured);
    }

    public String manageContextActions() {
        return configuration.compression() != null
                        && configuration.compression().enabled()
                        && (configuration.tools() == null
                                || configuration.tools().manageContext() == null
                                || configuration.tools().manageContext().enabled())
                ? "run compress with action=prune or action=compact"
                : "interrupt with a short summary of the remaining steps";
    }
}
