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
public record LlmConfiguration(
        @Property(defaultValue = "3", documentation = "Maximum retry attempts for 429/5xx and network errors.")
        int retriesMaxAttempts,

        @Property(
                defaultValue = "500L",
                documentation = "Base delay in milliseconds of the exponential backoff (with jitter).")
        long retriesBaseDelayMs,

        @Property(
                defaultValue = "0",
                documentation = "Client side requests per minute throttle. 0 disables the throttle.")
        int rateLimitRequestsPerMinute,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Maximum number of concurrent in-flight LLM requests for a provider. 0 or negative (the default) is unlimited; a per-provider maxConcurrent overrides this value, and when no limit is configured the concurrency is adapted from the provider 429 concurrency_limit_exceeded responses.")
        int maxConcurrent,

        @Property(
                defaultValue = "300000L",
                documentation =
                        "Maximum wait honored from a provider retry-after header or concurrency-limit response, in milliseconds (default 5 minutes). The provider value is used when below this cap.")
        long retryAfterMaxWaitMs,

        @Property(
                defaultValue = "3600000L",
                documentation =
                        "For a provider 429 'usage_limit_reached' error, the recovery-window threshold in milliseconds (default 1 hour): when the JSON body reports the quota resets beyond this (via limit_window_minutes or resets_at), the error is treated as non-retryable instead of being retried, since the quota will not clear in retry-seconds.")
        long usageLimitRetryThresholdMs,

        @Property(
                defaultValue = "900000L",
                documentation =
                        "Cooldown in milliseconds before relaxing a concurrency limit adapted from a provider 429 concurrency_limit_exceeded response back to the configured cap (default 15 minutes).")
        long concurrencyAdaptationMs,

        @Property(
                defaultValue = "300000L",
                documentation =
                        "Maximum time in milliseconds a request may wait for a free concurrency slot before failing with a retryable timeout (default 5 minutes; 0 or negative waits forever). It bounds a permanently saturated budget - another yca process holding every slot, or a suspended one - instead of hanging silently.")
        long concurrencyAcquireTimeoutMs,

        @Property(
                defaultValue = "true",
                documentation =
                        "On a provider 'context length exceeded' error, compact the context and retry the request once.")
        boolean retryOnContextOverflow,

        @Property(
                defaultValue = "250",
                documentation =
                        "Maximum number of iterations (LLM round-trips) in a single turn; each response carrying tool calls consumes one. Positive bounds the iteration window; a negative value makes the window UNBOUNDED - the turn runs until it genuinely ends (the model finishes, a plateau or no-progress guard stops it, the context overflows, or the turn is cancelled), bounded only by the other guards and by trust in the model. The turn is stopped with an error when a positive budget is exceeded, unless pending todos can auto-continue (yca.todos.continueOnUnfinished) - each todo continuation starts a fresh window with this same budget. A per-provider tuning or per-model behavior maxIterations wins over this value; a positive sub-agent cap always bounds the window regardless.")
        int maxIterations,

        @Property(
                defaultValue = "8",
                documentation =
                        "Maximum number of consecutive read-only tool rounds that re-cover only already-seen files/results (an 'exploration plateau') before the turn is stopped with an error, for the models that do not set a per-model behavior or per-provider tuning plateauRounds. 0 disables the guard entirely; a negative value enables the nudge but disables the hard-stop (nudge-only, the turn is never terminated on exploration).")
        int plateauRounds,

        @Property(
                defaultValue = "20",
                documentation =
                        "Consecutive informative read-only exploration rounds without an answer after which a one-shot 'wrap up and answer' nudge is injected. Complements the plateau guard: a spiral of NOVEL reads/globs (each round fresh, so never 'redundant' and never a plateau) still gets told to stop expanding and synthesize. No hard stop - the model can still gather if the nudge is wrong. 0 disables the nudge.")
        int explorationNudgeRounds,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.PlateauFeedbackMode.NUDGE_2",
                documentation =
                        "Plateau feedback strategy for the models that do not set a per-model behavior or per-provider tuning plateauFeedback: NUDGE_2 nudges once at limit-2, NUDGE_1 at limit-1 (both then hard-stop at the limit), NONE never nudges.")
        PlateauFeedbackMode plateauFeedback,

        @Property(
                defaultValue = "true",
                documentation =
                        "When a reply ends truncated by the output token limit (finish_reason=length) without tool calls, automatically ask the model to continue instead of ending the turn (up to truncationContinuesMax continuations, then the turn falls through to the todo auto-continue when pending todos remain, or ends with an error notice).")
        boolean continueOnTruncation,

        @Property(
                defaultValue = "3",
                documentation =
                        "Maximum automatic continuations when a reply is truncated by the output token limit (finish_reason=length); from the second continuation the model is asked to change strategy (smaller steps, tools to chunk output) instead of continuing verbatim.")
        int truncationContinuesMax,

        @Property(
                defaultValue = "2",
                documentation =
                        "Maximum automatic retries when a reply is empty (no content and no tool call, eg. a model that returned reasoning then stopped). Each retry asks the model again instead of ending the turn with a blank answer; 0 disables the retry. Retries happen before the todo auto-continue (yca.todos.continueOnUnfinished) and only count consecutive empty replies (a tool round or a real text reply resets the budget); once the budget is exhausted the turn falls through to the todo auto-continue when pending todos remain, otherwise it ends with an error notice.")
        int emptyCompletionRetriesMax,

        @Property(
                defaultValue = "2",
                documentation =
                        "Maximum retries of a streamed LLM request that broke mid-stream (transport failure after partial content was received, eg. a dropped chunked HTTP connection). Each retry restarts the full request after an exponential backoff; 0 ends the turn on the first mid-stream failure.")
        int streamRetriesMaxAttempts,

        @Property(
                defaultValue = "16000",
                documentation =
                        "Global system prompt maximum character budget. The resolved cap is: per-model behavior > per-provider tuning > this value > a value derived from the provider context window (big windows keep ~16k with this default, small ones shrink to fit the input budget).")
        int systemPromptMaxChars) {}
