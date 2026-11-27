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
 * Tuning defaults of a model (or of a provider when used at the provider level). When a facet is
 * unset the value falls back to the provider default, then to the implicit per-family scheme.
 * Highly inspired from the {code tuning} concept of opencode.
 */
@JsonModel
public record ModelTuningConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Default reasoning effort hint for this model (eg. low, medium or high). Applied only when neither the request nor the providerSpecificOptions already carries one (put-if-absent) and no selected variant sets it - the reasoningEffort of a selected variant (or of the defaultVariant preset) wins over this fallback. Blank is ignored.")
        String reasoningEffort,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.RepairToolCallArgumentsMode.AUTO",
                documentation =
                        "Whether tool call arguments emitted by this model are repaired (best effort) before being exposed and stored. AUTO repairs for OpenAI-compatible or undetermined API flavors and for Qwen 27B variants (the effective model contains 'qwen' and '27b', case-insensitive); otherwise it is disabled; ENABLED always repairs; DISABLED never repairs. This fixes llama.cpp/ollama style backends that reject arguments carrying unescaped control characters or a truncated tail with an HTTP 500 (\"Failed to parse tool call arguments as JSON\") under a heavy context. The provider-level tuning value is used when this per-model facet is absent or AUTO.")
        RepairToolCallArgumentsMode repairToolCallArguments,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.InBandToolCallsMode.AUTO",
                documentation =
                        "Whether in-band tool-call markup that the model leaks into the text content channel is extracted into structured tool calls when the provider emitted no native tool_calls. AUTO (the default) extracts only when no native tool call was produced; ENABLED always attempts extraction from the text channel; DISABLED never does. Structured tool_calls always win over in-band markup regardless of this knob. Extracted markup is stripped from the stored/resent content. This is the fallback for local backends (llama.cpp/ollama/ninfer) serving models that write DSML-style tool calls in the text channel even when structured tools are declared.")
        InBandToolCallsMode inBandToolCalls,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.ReasoningFormatMode.AUTO",
                documentation =
                        "How this model embeds its reasoning in the text content channel, so the parser can strip it before persistence / replay. AUTO (the default) keeps the GLM/Qwen whole-line `think` handling; DEEPSEEK_R1 treats the output as reasoning from the start until the ` response` marker (no opening tag); QWEN strips ` think ... response` blocks and ends reasoning at an unclosed `<tool_call>`. Models that send reasoning through a dedicated channel (reasoning_content/thinking) are not affected: those never enter the text content.")
        ReasoningFormatMode reasoningFormat,

        @Property(
                defaultValue = "false",
                documentation =
                        "When a truncated completion produced only reasoning and no visible content, surface the reasoning tail as the answer instead of dropping it. Off (the default) is the safe behaviour: an all-reasoning, truncated turn yields an empty completion. On is a remedy for models that spend their whole output budget thinking and then hit the token cap. A nullable Boolean lets a per-model value override (including an explicit false) the provider-level one.")
        Boolean forceNonEmptyContent,

        @Property(
                documentation =
                        "Default output token budget override for this model (put-if-absent too). Unset or zero/negative keeps the provider outputTokens default.")
        Integer outputTokens,

        @Property(
                documentation =
                        "Default system prompt maximum character budget override for this model (put-if-absent too). Unset or zero/negative keeps the provider tuning, then the global llm default, then a value derived from the provider context window (big windows keep ~32k, small windows shrink).")
        Integer systemPromptMaxChars,

        @Property(
                documentation =
                        "Maximum number of consecutive read-only tool rounds that re-cover only already-seen files/results before the turn is stopped with an 'Exploration plateau' error. Negative keeps the provider tuning, then the global llm.plateauRounds default (8); zero disables the guard entirely; a negative resolved value (explicit per-model/provider < 0) disables the hard-stop so the plateau only nudges and never terminates the turn (nudge-only mode).")
        Integer plateauRounds,

        @Property(
                documentation =
                        "Iteration window cap override for this model (LLM round-trips per turn window). Unset or zero/negative keeps the provider tuning, then the global llm.maxIterations (250). A negative value makes the window UNBOUNDED (the turn runs until it genuinely ends, bounded only by the other guards); zero falls through the chain.")
        Integer maxIterations,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.PlateauFeedbackMode.NUDGE_2",
                documentation =
                        "Feedback strategy when the exploration-plateau guard approaches its limit: NUDGE_2 nudges the model once at limit-2, NUDGE_1 at limit-1 (both then hard-stop at the limit), NONE never nudges. Unset keeps the provider tuning, then the global llm.plateauFeedback default (nudge_2).")
        PlateauFeedbackMode plateauFeedback,

        @Property(
                documentation =
                        "Maximum number of consecutive informative read-only exploration rounds (novel reads/globs, no answer) after which a one-shot 'wrap up and answer' nudge is injected for this model. Unset/zero/negative keeps the provider tuning, then the global llm.explorationNudgeRounds default (20). Zero disables the nudge.")
        Integer explorationNudgeRounds,

        @Property(
                documentation =
                        "Whether a non-truncated text-only reply that leaves the todo list unfinished continues the turn automatically instead of ending it and waiting for the user to send 'continue' (Codex-style 'a turn continues while the last output was a tool call or the task is unfinished' round semantics). A nullable Boolean lets a per-model value override (including an explicit false) the provider-level one. Default absent/null keeps today's behavior: a text-only end with unfinished todos surfaces the 'send continue' banner and pauses. Set true only for capable models (eg. codex, deepseek-r1) that reliably keep driving tools; small models (eg. qwen) should keep it unset or false so they can stop and hand control back.")
        Boolean turnContinuesOnTextEnd,

        @Property(
                documentation =
                        "Doom-loop success threshold override for this model: the maximum number of identical tool calls "
                                + "(same tool + same normalized arguments) whose result succeeded and carried no new "
                                + "information (identical output, ignoring embedded dates/timestamps) before the next one "
                                + "is refused with a strategy-change directive. A failing call is never counted (it is a "
                                + "legitimate retry), and an interleaved failure or productive round resets the streak. "
                                + "Unset or zero/negative keeps the provider tuning, then the global yca.tools.repeatSuccessThreshold"
                                + " default (3). Zero at the global level disables the guard entirely.")
        Integer repeatSuccessThreshold,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Named tuning presets (variants) selectable at runtime via the /model command. Unset defers to the provider-level variants, then to the implicit per-family scheme.")
        List<ModelVariantConfiguration> variants,

        @Property(
                documentation = "Name of a variant from the `variants` list applied by default when the session has no "
                        + "explicit variant selection (eg. /model <model> <variant> or /variant). Blank keeps the "
                        + "implicit default behavior. When the name matches no configured or implicit variant, a "
                        + "warning is logged and the implicit default applies - a dangling reference never fails "
                        + "or blocks a request. Unlike the reasoningEffort scalar fallback (a single-facet "
                        + "default), this selects the whole preset: the variant's facets (reasoningEffort, "
                        + "temperature, top_p, top_k, max_tokens, extra) apply put-if-absent and win over the "
                        + "tuning-level reasoningEffort; that fallback only applies when the selected variant "
                        + "leaves the facet unset or when no defaultVariant is configured at all.")
        String defaultVariant,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Optional raw JSON object merged (put-if-absent) into the payload of every request for this model, over the provider-level one. Empty is ignored.")
        String providerSpecificOptions,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.BoundaryOnlyRefMode.AUTO",
                documentation =
                        "Compression reference presentation: AUTO and ENABLED expose compactIds=A-B on eligible historical turns for all models and variants; DISABLED retains legacy per-message tags. An explicit model setting overrides the provider setting; AUTO inherits the provider setting. Resolved independently for each session request.")
        BoundaryOnlyRefMode compressionBoundaryOnly) {
    public ModelTuningConfiguration(
            final String reasoningEffort,
            final RepairToolCallArgumentsMode repairToolCallArguments,
            final InBandToolCallsMode inBandToolCalls,
            final ReasoningFormatMode reasoningFormat,
            final Boolean forceNonEmptyContent,
            final Integer outputTokens,
            final Integer systemPromptMaxChars,
            final Integer plateauRounds,
            final Integer maxIterations,
            final PlateauFeedbackMode plateauFeedback,
            final Integer explorationNudgeRounds,
            final Boolean turnContinuesOnTextEnd,
            final Integer repeatSuccessThreshold,
            final List<ModelVariantConfiguration> variants,
            final String defaultVariant,
            final String providerSpecificOptions) {
        this(
                reasoningEffort,
                repairToolCallArguments,
                inBandToolCalls,
                reasoningFormat,
                forceNonEmptyContent,
                outputTokens,
                systemPromptMaxChars,
                plateauRounds,
                maxIterations,
                plateauFeedback,
                explorationNudgeRounds,
                turnContinuesOnTextEnd,
                repeatSuccessThreshold,
                variants,
                defaultVariant,
                providerSpecificOptions,
                BoundaryOnlyRefMode.AUTO);
    }
}
