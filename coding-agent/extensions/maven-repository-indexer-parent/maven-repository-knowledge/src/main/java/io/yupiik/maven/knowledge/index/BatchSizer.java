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
package io.yupiik.maven.knowledge.index;

import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.config.TokenBudget;
import io.yupiik.maven.knowledge.content.ContentAcquired;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Computes the LLM entry-points batches. Three modes, selected by {@code maxBatchChars}:
 * <ul>
 *     <li>{@code -1}: auto - greedily suit the batch to the model budget (context input + estimated JSON
 *         output), falling back to a conservative {@value #DEFAULT_MAX_UNKNOWN_CHARS} character cap when no
 *         {@link TokenBudget} is available.</li>
 *     <li>{@code > 0}: hard character cap on the cumulative input content, alongside {@code batchSize}.</li>
 *     <li>{@code 0}: legacy - GAV-count batching only.</li>
 * </ul>
 * The greedy auto mode under-fills deliberately (conservative margins) so a batch never overflows the context
 * window even though token counts are only estimated.
 */
public final class BatchSizer {
    /** Tokens reserved for the fixed prompt + JSON-Schema frame shared by every entry-points call. */
    private static final int RESERVED_PROMPT_TOKENS = 4096;
    /** Conservative per-artifact output budget (coordinate + a few entry-point class names). */
    private static final int OUTPUT_PER_ARTIFACT_TOKENS = 64;
    /** Per-artifact JSON framing overhead charged on the input side. */
    private static final int FRAMING_PER_ARTIFACT_TOKENS = 8;
    /** Margin both sides, so estimated batches under-fill the real budget. */
    private static final double INPUT_MARGIN = 0.8;

    private static final double OUTPUT_MARGIN = 0.8;

    /** Conservative per-batch cap when the model budget is unknown, in input content characters. */
    private static final int DEFAULT_MAX_UNKNOWN_CHARS = 128_000;

    /**
     * Coarse window sizing: an acquisition window is {@code windowMultiplier * batchSize} GAVs, so the in-memory
     * acquired-content map never exceeds a bounded fraction of the whole cohort at once. {@code batchSize} stays the
     * hard fence for the final per-LLM-call batches (see {@link #batches(List, Map, KnowledgeConfig)}).
     */
    private static final int WINDOW_MULTIPLIER = 64;

    private BatchSizer() {
        // utility
    }

    /**
     * Splits a candidate cohort into coarse acquisition windows so the caller can acquire flat content window by
     * window (evicting each before the next) instead of holding the whole cohort's content in memory.
     *
     * @param toProcess the GAVs to index.
     * @param config    pipeline configuration carrying {@code batchSize}.
     * @return a list of coarse windows (each {@code WINDOW_MULTIPLIER * max(1,batchSize)} GAVs, last one may be smaller).
     */
    public static List<List<Gav>> windows(final List<Gav> toProcess, final KnowledgeConfig config) {
        return count(toProcess, WINDOW_MULTIPLIER * Math.max(1, config.batchSize()));
    }

    /**
     * @param toProcess     GAVs to batch (already passed the immutable-skip filter).
     * @param contentByGav  pre-acquired content, required for {@code -1} and {@code >0} modes.
     * @param config        pipeline configuration carrying {@code maxBatchChars}/{@code batchSize}/{@code tokenBudget}.
     */
    public static List<List<Gav>> batches(
            final List<Gav> toProcess, final Map<Gav, ContentAcquired> contentByGav, final KnowledgeConfig config) {
        if (config.maxBatchChars() == -1
                && config.tokenBudget() != null
                && config.tokenBudget().known()) {
            return greedy(toProcess, contentByGav, config.batchSize(), config.tokenBudget());
        }
        if (config.maxBatchChars() == -1) {
            return charCapped(toProcess, contentByGav, config.batchSize(), DEFAULT_MAX_UNKNOWN_CHARS);
        }
        if (config.maxBatchChars() > 0) {
            return charCapped(toProcess, contentByGav, config.batchSize(), config.maxBatchChars());
        }
        return count(toProcess, config.batchSize());
    }

    private static List<List<Gav>> greedy(
            final List<Gav> gavs,
            final Map<Gav, ContentAcquired> contentByGav,
            final int batchSize,
            final TokenBudget budget) {
        final var outputAllocation = Math.max(1, (int) (budget.outputBudget() * OUTPUT_MARGIN));
        final var inputAllocation = Math.max(
                1, (int) ((budget.contextWindow() - outputAllocation - RESERVED_PROMPT_TOKENS) * INPUT_MARGIN));
        final var batches = new ArrayList<List<Gav>>();
        var batch = new ArrayList<Gav>();
        var inputTokens = 0;
        var outputTokens = 0;
        for (final var gav : gavs) {
            final var content = contentByGav.get(gav);
            final var contentTokens =
                    TokenEstimate.of(content == null || content.content() == null ? "" : content.content());
            final var nextInput = inputTokens + contentTokens + FRAMING_PER_ARTIFACT_TOKENS;
            final var nextOutput = outputTokens + OUTPUT_PER_ARTIFACT_TOKENS;
            if (!batch.isEmpty()
                    && (nextInput > inputAllocation || nextOutput > outputAllocation || batch.size() >= batchSize)) {
                batches.add(List.copyOf(batch));
                batch = new ArrayList<>();
                inputTokens = 0;
                outputTokens = 0;
            }
            batch.add(gav);
            inputTokens += contentTokens + FRAMING_PER_ARTIFACT_TOKENS;
            outputTokens += OUTPUT_PER_ARTIFACT_TOKENS;
        }
        if (!batch.isEmpty()) {
            batches.add(List.copyOf(batch));
        }
        return batches;
    }

    private static List<List<Gav>> charCapped(
            final List<Gav> gavs,
            final Map<Gav, ContentAcquired> contentByGav,
            final int batchSize,
            final int maxChars) {
        final var batches = new ArrayList<List<Gav>>();
        var batch = new ArrayList<Gav>();
        long chars = 0;
        for (final var gav : gavs) {
            batch.add(gav);
            final var content = contentByGav.get(gav);
            chars += content == null || content.content() == null
                    ? 0
                    : content.content().length();
            if (batch.size() >= batchSize || chars >= maxChars) {
                batches.add(List.copyOf(batch));
                batch = new ArrayList<>();
                chars = 0;
            }
        }
        if (!batch.isEmpty()) {
            batches.add(List.copyOf(batch));
        }
        return batches;
    }

    private static List<List<Gav>> count(final List<Gav> gavs, final int batchSize) {
        final var batches = new ArrayList<List<Gav>>();
        final int size = Math.max(1, batchSize);
        for (int i = 0; i < gavs.size(); i += size) {
            batches.add(List.copyOf(gavs.subList(i, Math.min(gavs.size(), i + size))));
        }
        return batches;
    }
}
