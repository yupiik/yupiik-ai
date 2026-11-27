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
import java.util.List;

/**
 * Model-driven context compression (DCP style): the model calls the {@code compress} tool on
 * closed conversation ranges and writes the replacement summary itself, so compression costs no
 * extra LLM call and the model decides what is safe to drop.
 *
 * <p>Compression is a wire-level view: the session transcript is append-only, blocks only rewrite
 * the messages sent to the provider. It is complementary to the automatic compaction which still
 * guards the model window near its hard limit.
 */
@JsonModel
public record CompressConfiguration(
        @Property(
                defaultValue = "true",
                documentation =
                        "Enable the model-driven compress tool, its system-prompt guidance and its usage nudges. When disabled the conversation is only compacted near the model window like opencode core.")
        boolean enabled,

        @Property(
                documentation = "Soft and hard working-context limits (min/max) of the model-driven compression "
                        + "(tokens or a percentage of the context window). Defaults: min 50000, max 100000.")
        ContextLimit contextLimit,

        @Property(
                defaultValue = "15",
                documentation =
                        "Number of iterations since the last user message after which the soft compression nudge may fire (0 only keeps the hard-limit nudge).")
        int iterationNudgeThreshold,

        @Property(
                defaultValue = "5",
                documentation =
                        "Emit the hard-limit nudge at most every this many real LLM request rounds, so a model that ignores it is not spammed on every round. Also sets the post-compression cooldown; context previews do not advance this cadence.")
        int nudgeFrequency,

        @Property(
                defaultValue = "true",
                documentation =
                        "Count the active compressed summaries toward the effective context size and let them extend the hard nudge limit to baseMax + summaryTokens. This changes guidance only: summary tokens still count toward real capacity and the core compaction guard is unchanged.")
        boolean summaryBuffer,

        @Property(
                defaultValue = "false",
                documentation =
                        "Preserve user messages by appending their verbatim text to the summary when a compressed range covers them.")
        boolean protectUserMessages,

        @Property(
                defaultValue = "false",
                documentation =
                        "Append the content of <protect>...</protect> blocks covered by a compressed range to its summary.")
        boolean protectTags,

        @Property(
                defaultValue = "java.util.List.of(\"spawn_subagent\", \"skill\", \"write_todo\")",
                documentation =
                        "Tool names whose outputs are appended verbatim to a compression summary when the compressed range covers them.")
        List<String> protectedTools,

        @Property(
                defaultValue = "false",
                documentation = "Never compress the last turnProtectionTurns user turns (the active work).")
        boolean turnProtection,

        @Property(
                defaultValue = "4",
                documentation =
                        "Number of most recent user turns protected from compression when turnProtection is enabled.")
        int turnProtectionTurns,

        @Property(
                defaultValue = "true",
                documentation = "Deduplicate repeated tool calls (same tool and arguments) when compression runs.")
        boolean deduplicateToolCalls,

        @Property(
                defaultValue = "true",
                documentation =
                        "Strip the arguments of failed tool calls after purgeErrorsTurns turns when compression runs.")
        boolean purgeFailedToolInputs,

        @Property(
                defaultValue = "4",
                documentation =
                        "Number of turns after which failed tool-call arguments are purged by the compression pipeline.")
        int purgeFailedToolInputsTurns) {

    /** The effective soft/hard context limits: the configured record, or the defaults when absent. */
    public ContextLimit resolvedContextLimit() {
        return contextLimit == null ? new ContextLimit(null, null) : contextLimit;
    }
}
