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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

@JsonModel
public record CompactionConfiguration(
        @Property(
                defaultValue = "true",
                documentation = "Automatically compact the context when the threshold is reached.")
        boolean enabled,

        @Property(
                defaultValue = "0",
                documentation =
                        "Legacy compaction trigger as a percentage of the usable window; 0 (default) uses the opencode-style buffer trigger instead (window minus max(buffer.percent, buffer.minTokens)). Set to a positive value to restore the historical percentage trigger.")
        int thresholdPercent,

        @Property(
                documentation =
                        "Buffer-mode trigger knobs (percent, minTokens and safetyPercent cap). See {@link BufferConfiguration}.")
        BufferConfiguration buffer,

        @Property(
                documentation =
                        "Automatic-compaction gate budgets (maxAttempts, maxDurationMs). See {@link GateConfiguration}.")
        GateConfiguration gate,

        @Property(
                documentation =
                        "Verbatim recent-tail knobs of compaction (budgetTokens, toolArgumentsCapChars, overflowSpliceChars). See {@link TailConfiguration}.")
        TailConfiguration tail,

        @Property(
                documentation =
                        "Summarizer input-cap knobs (chunkChars, minChunkChars, digestChars, toolOutputChars). See {@link SummarizerConfiguration}.")
        SummarizerConfiguration summarizer,

        @Property(
                documentation =
                        "Speculative compaction knobs (policy, startPercent). See {@link SpeculativeConfiguration}.")
        SpeculativeConfiguration speculative,

        @Property(
                defaultValue = "false",
                documentation =
                        "When a compaction summarizer call exhausts all its retries on a transient 5xx/network failure, "
                                + "retry the summarizer once on the provider's main (conversation) model before giving up "
                                + "and skipping the compaction. Off by default because the main model is usually costlier; "
                                + "a per-provider compaction.fallbackToMainModel value overrides this global default.")
        boolean fallbackToMainModel,

        @Property(
                defaultValue = "2",
                documentation =
                        "Retry budget of the compaction summarizer (the coordinator-owned retry loop). Scoped: this "
                                + "value feeds the compaction retries only; conversation requests keep yca.llm.retriesMaxAttempts. "
                                + "Because the coordinator is the single retry authority (the resilience wrapper skips its "
                                + "own retry loop for summarizer calls), this is the only retry budget compaction pays. "
                                + "The default of 2 means one initial attempt plus one transient retry per chunk (a larger "
                                + "value is a per-operation multiple but still costs one call per attempt); context-sizing "
                                + "failures are recovered by the chunk-halving restart, not by this budget. "
                                + "A per-provider compaction.retriesMaxAttempts overrides it; must be >= 1.")
        int retriesMaxAttempts,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.CompactionLlmMode.LLM",
                documentation =
                        "Compaction LLM mode: \"llm\" (default) uses the LLM summarizer (backgroundModelOverrides.compact, "
                                + "then yca.model.compact, then the session model) to fold old messages into a digest; "
                                + "\"no_llm\" never calls an LLM for compaction - automatic, gate, overflow and manual "
                                + "compactions all run only local strategies (prune, tool-output splice/spill, "
                                + "dedup/supersede/purge when enabled), so a session never pays a summarizer call. The "
                                + "local cascade still targets the same compaction trigger and the gate/overflow safety "
                                + "nets keep working, but history is spliced instead of folded into a digest and grows "
                                + "monotonically - the right trade for large-window providers that want zero background "
                                + "spend. \"auto\" resolves to \"llm\". yca.compaction.enabled=false still wins over "
                                + "this flag. A per-provider providers.<id>.compaction.mode of \"llm\"/\"no_llm\" "
                                + "overrides this global; \"auto\" (the per-provider default) inherits it.")
        CompactionLlmMode mode) {

    /** The effective buffer-mode knobs, or the defaults when unset. */
    public BufferConfiguration resolvedBuffer() {
        return buffer == null
                ? new BufferConfiguration(
                        BufferConfiguration.DEFAULT_PERCENT,
                        BufferConfiguration.DEFAULT_MIN_TOKENS,
                        BufferConfiguration.DEFAULT_SAFETY_PERCENT)
                : buffer;
    }

    /** The effective gate budgets, or the defaults when unset. */
    public GateConfiguration resolvedGate() {
        return gate == null
                ? new GateConfiguration(
                        GateConfiguration.DEFAULT_MAX_ATTEMPTS, GateConfiguration.DEFAULT_MAX_DURATION_MS)
                : gate;
    }

    /** The effective tail knobs, or the defaults when unset. */
    public TailConfiguration resolvedTail() {
        return tail == null
                ? new TailConfiguration(
                        TailConfiguration.DEFAULT_BUDGET_TOKENS,
                        TailConfiguration.DEFAULT_TOOL_ARGUMENTS_CAP_CHARS,
                        TailConfiguration.DEFAULT_OVERFLOW_SPLICE_CHARS)
                : tail;
    }

    /** The effective summarizer caps, or the defaults when unset. */
    public SummarizerConfiguration resolvedSummarizer() {
        return summarizer == null
                ? new SummarizerConfiguration(
                        SummarizerConfiguration.DEFAULT_CHUNK_CHARS,
                        SummarizerConfiguration.DEFAULT_MIN_CHUNK_CHARS,
                        SummarizerConfiguration.DEFAULT_DIGEST_CHARS,
                        SummarizerConfiguration.DEFAULT_TOOL_OUTPUT_CHARS)
                : summarizer;
    }

    /** The effective speculative knobs, or the defaults when unset. */
    public SpeculativeConfiguration resolvedSpeculative() {
        return speculative == null
                ? new SpeculativeConfiguration(
                        SpeculativeConfiguration.DEFAULT_POLICY, SpeculativeConfiguration.DEFAULT_START_PERCENT)
                : speculative;
    }

    public CompactionConfiguration {
        if (retriesMaxAttempts < 1) {
            throw new IllegalArgumentException("retriesMaxAttempts must be at least 1");
        }
    }
}
