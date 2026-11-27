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
import io.yupiik.ia.agent.core.configuration.PlateauFeedbackMode;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.util.function.Supplier;

/**
 * Pure resolution of the turn-loop guard cascades (iteration window cap, exploration-plateau limit,
 * exploration nudge rounds, plateau feedback mode, doom-loop repeat threshold). Each cascade follows
 * the same precedence: per-model behavior &gt; per-provider tuning &gt; global configuration default.
 *
 * <p>Stateless and side-effect free: the caller owns the {@code maxIterationsCap} (read live through
 * the supplier so a sub-agent ceiling set mid-turn applies from the next resolution) and applies the
 * resolved values where they belong.
 */
public class LoopGuards {
    private final ProviderManager providerManager;
    private final CodingAgentConfiguration configuration;
    private final Supplier<Integer> maxIterationsCap;

    public LoopGuards(
            final ProviderManager providerManager,
            final CodingAgentConfiguration configuration,
            final Supplier<Integer> maxIterationsCap) {
        this.providerManager = providerManager;
        this.configuration = configuration;
        this.maxIterationsCap = maxIterationsCap;
    }

    /**
     * Iteration window cap cascade: per-model behavior.maxIterations &gt; per-provider tuning.maxIterations
     * &gt; the global llm.maxIterations default. A negative override (&lt; 0) means an UNBOUNDED window: the
     * turn runs until it genuinely ends (the model finishes, a plateau/no-progress guard stops it,
     * context overflows, or the turn is cancelled). A positive {@code maxIterationsCap} (always set for
     * sub-agents) bounds the window regardless, so unbounded applies only to top-level turns with no cap.
     * The provider/model come from {@code resolved} (the default provider resolved at loop start).
     */
    public int resolvedMaxIterations(final ProviderManager.ResolvedModel resolved) {
        final var cap = maxIterationsCap.get();
        if (cap != null) {
            // sub-agent / hard window cap always bounds; unbounded requires no cap AND a negative override
            return cap;
        }
        final var override = resolved == null || providerManager == null
                ? 0
                : providerManager.maxIterationsOverride(resolved.provider(), resolved.model());
        return override != 0 ? override : configuration.llm().maxIterations();
    }

    /**
     * Exploration-plateau limit cascade: per-model behavior.plateauRounds &gt; per-provider
     * tuning.plateauRounds &gt; the global llm.plateauRounds default (8). A non-zero resolved value wins
     * verbatim: a negative one means nudge-only (the plateau nudges but never hard-stops the turn), a
     * positive one is the nudge + hard-stop limit, and 0 (only reachable via an explicit global
     * llm.plateauRounds=0, since a per-model/provider 0 falls through the chain) disables the guard.
     */
    public int resolvedPlateauRounds(final ProviderManager.ResolvedModel resolved) {
        final var override = resolved == null || providerManager == null
                ? 0
                : providerManager.plateauRounds(resolved.provider(), resolved.model());
        return override != 0 ? override : configuration.llm().plateauRounds();
    }

    /**
     * Exploration-nudge cascade: per-model behavior.explorationNudgeRounds &gt; per-provider
     * tuning.explorationNudgeRounds &gt; the global llm.explorationNudgeRounds default (20). 0 disables
     * the nudge (or falls back to the provider/global when only set to 0 higher up the chain).
     */
    public int resolvedModelExplorationNudgeRounds(final ProviderManager.ResolvedModel resolved) {
        final var override = resolved == null || providerManager == null
                ? 0
                : providerManager.explorationNudgeRounds(resolved.provider(), resolved.model());
        return override > 0 ? override : configuration.llm().explorationNudgeRounds();
    }

    /**
     * Exploration-plateau feedback cascade: per-model behavior.plateauFeedback &gt; per-provider
     * tuning.plateauFeedback &gt; the global llm.plateauFeedback default (nudge_2).
     */
    public PlateauFeedbackMode resolvedPlateauFeedback(final ProviderManager.ResolvedModel resolved) {
        final var override = resolved == null || providerManager == null
                ? null
                : providerManager.plateauFeedback(resolved.provider(), resolved.model());
        return override == null ? configuration.llm().plateauFeedback() : override;
    }

    /**
     * Doom-loop success threshold cascade: per-model behavior.repeatSuccessThreshold &gt; per-provider
     * tuning.repeatSuccessThreshold &gt; the global yca.tools.repeatSuccessThreshold default (3).
     * 0 at the global level disables the guard entirely.
     */
    public int resolvedRepeatSuccessThreshold(final ProviderManager.ResolvedModel resolved) {
        final var override = resolved == null || providerManager == null
                ? 0
                : providerManager.repeatSuccessThreshold(resolved.provider(), resolved.model());
        return override > 0 ? override : configuration.tools().repeatSuccessThreshold();
    }

    /**
     * Round at which the one-shot plateau nudge fires for a mode: {@code NUDGE_2} at {@code limit-2},
     * {@code NUDGE_1} at {@code limit-1}, {@code NONE} or a limit too small for a meaningful early
     * nudge (&le; 2) returns 0 (no nudge, hard stop only).
     */
    public static int plateauFeedbackNudgeAt(final PlateauFeedbackMode mode, final int limit) {
        return switch (mode) {
            case NUDGE_2 -> limit > 2 ? limit - 2 : 0;
            case NUDGE_1 -> limit > 2 ? limit - 1 : 0;
            case NONE -> 0;
        };
    }
}
