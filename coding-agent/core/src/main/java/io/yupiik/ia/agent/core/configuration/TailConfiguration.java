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

/**
 * Kept-tail / verbatim-recent-segment knobs of the automatic compaction. The global
 * {@link CompactionConfiguration#tail()} carries the defaults below; the per-provider
 * {@link ProviderTailConfiguration} mirrors the shape with null fields meaning "inherit the global
 * value".
 */
@JsonModel
public record TailConfiguration(
        @Property(
                defaultValue = "0",
                documentation = "Token budget of the verbatim recent tail kept by compaction (0 = auto: 25% of the "
                        + "usable window clamped to [2000, 15000]). The tail is extended or reduced at "
                        + "whole conversation turns and never splits an assistant tool call from its "
                        + "results.")
        int budgetTokens,

        @Property(
                defaultValue = "800",
                documentation = "Character cap (head and tail) applied to tool call arguments when the compaction "
                        + "summarizer serializes assistant history for summarization (0 disables the cap "
                        + "and keeps arguments verbatim). Large arguments (bash scripts, big JSON "
                        + "payloads) dominate the summarizer input and multiply the number of "
                        + "rolling-summary LLM calls.")
        int toolArgumentsCapChars,

        @Property(
                defaultValue = "500",
                documentation =
                        "Character cap (head and tail) applied to kept-tail messages when the compacted context "
                                + "still exceeds the provider window after compaction.")
        int overflowSpliceChars) {

    /** Default tail budget: 0 means auto-derived from the usable window. */
    public static final int DEFAULT_BUDGET_TOKENS = 0;

    /** Default head+tail cap of tool call arguments in chars (800). */
    public static final int DEFAULT_TOOL_ARGUMENTS_CAP_CHARS = 800;

    /** Default head+tail cap of kept-tail messages in chars (500). */
    public static final int DEFAULT_OVERFLOW_SPLICE_CHARS = 500;

    public TailConfiguration {
        if (budgetTokens < 0) {
            throw new IllegalArgumentException("budgetTokens must be zero or positive");
        }
        if (toolArgumentsCapChars < 0) {
            throw new IllegalArgumentException("toolArgumentsCapChars must be zero or positive");
        }
        if (overflowSpliceChars < 0) {
            throw new IllegalArgumentException("overflowSpliceChars must be zero or positive");
        }
    }
}
