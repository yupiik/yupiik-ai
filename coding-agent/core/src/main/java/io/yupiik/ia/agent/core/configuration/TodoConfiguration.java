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
 * Todo list behavior: the guidance injected in the system prompt and what the agent loop does when a
 * turn ends while todos are still open.
 *
 * <p>Todo/plan guidance is primarily tuned per provider ({@code yca.provider.*.prompt.todo});
 * {@link #guidance()} is the global fallback used for the providers that do not set one.
 */
@JsonModel
public record TodoConfiguration(
        @Property(
                documentation =
                        "Global todo and plan guidance, used for every provider that leaves yca.provider.<id>.prompt.todo unset or at 'auto'. Same semantics as the per-provider value: 'auto' (or unset) resolves the guidance tuned for the provider family detected from its base URL, any other non-empty value is injected verbatim as the todo-guidance appendix, and an empty string or the literal 'false' disables the extra guidance entirely.")
        String guidance,

        @Property(
                defaultValue = "false",
                documentation =
                        "When a turn ends text-only while the todo list still has unfinished items (pending or in_progress - a todo is unfinished until it is completed or cancelled), automatically ask the model to continue instead of ending the turn (up to maxContinues continuations per turn, then the turn is ended with the usual warning notice). The continuation is also attempted before giving up on an exhausted iteration window, a truncated reply or an empty completion, so unfinished todos keep the turn going across those mid-turn stops. Applies to build mode, to the top-level turns only (never to sub-agents) and is skipped in a turn where a prompt typed while the loop was running was injected.")
        boolean continueOnUnfinished,

        @Property(
                defaultValue = "25",
                documentation =
                        "Maximum automatic continuations per turn when continueOnUnfinished is enabled. Each continuation costs one LLM round-trip and starts a fresh yca.llm.maxIterations window, so a long turn is bounded by this budget rather than by a single iteration window.")
        int maxContinues,

        @Property(
                documentation =
                        "Instruction sent to the model when continueOnUnfinished fires; empty or unset uses a built-in continuation instruction telling it to act on the next unfinished todo (pending or in_progress, finishing or cancelling the in_progress ones it started) instead of narrating. From the second continuation on, a strategy-change clause is appended. Note: a custom value replaces the whole built-in instruction, so keep the unfinished-todo wording and the finish-or-cancel guidance in your text if you override it.")
        String continuePrompt) {

    /** The continuation instruction to use, or the built-in one when none is configured. */
    public String resolveContinuePrompt(final String builtIn) {
        return continuePrompt == null || continuePrompt.isBlank() ? builtIn : continuePrompt;
    }
}
