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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the provider-specific todo/plan guidance appendix that is injected into the system
 * prompt, mirroring how opencode tunes todo instructions per model family (for example its
 * strong "Task Management" section for Anthropic vs none for others).
 *
 * <p>The resolution is driven by the {@code yca.provider.*.prompt.todo} configuration value:
 * <ul>
 * <li>{@code auto} (the default) picks a canned block from the provider family detected in the
 * base URL, defaulting to the OpenAI block when the family is unknown;</li>
 * <li>any other non-empty value is injected verbatim as the appendix;</li>
 * <li>an empty string or the literal {@code false} disables the extra guidance.</li>
 * </ul>
 *
 * <p>In PLAN mode the canned blocks are swapped for the plan variant: the todo list is the plan
 * itself, so the guidance orders keeping every item 'pending' and re-syncing the list with
 * {@code write_todo} whenever the discussion changes the plan - instead of the build-mode
 * completion-tracking wording.</p>
 */
@ApplicationScoped
public class TodoPromptResolver {

    private record Family(String name, List<String> hints, String block, String planBlock) {
        private boolean matches(final String baseUrl) {
            final var url = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
            for (final var hint : hints) {
                if (url.contains(hint)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final String OPENAI = """
            Task Management:
            Use write_todo for substantial multi-step work; skip a new list for a straightforward single-step task. Keep statuses accurate and mark items completed only when done, including verification, or cancelled when unnecessary. Combine todo updates with substantive tool calls whenever possible instead of spending a separate round on bookkeeping. You may have several in_progress at once when you work in parallel. Completed/cancelled stay in the list as progress history. Consult the todo list in the <session-state> block at the start of each turn and re-check it before ending a turn: pending items are your queue.""";

    private static final String OPENAI_PLAN = """
            Task Management (plan mode):
            The todo list is the plan: record it with write_todo and keep every item 'pending' - statuses only advance in build mode. Whenever a follow-up changes the plan, update the list in the same turn so it always mirrors the plan you just agreed on. Completed and cancelled items recorded during build sessions are the progress history: keep them, never drop them. In this plan-mode session you cannot advance a pending item: do not re-emit it as in_progress/completed, it will be kept pending until build mode. Consult the todo list in the <session-state> block at the start of each turn: it must match the plan you just agreed on.""";

    private static final String ANTHROPIC = """
            Task Management:
            You have access to the write_todo tool to help you manage and plan tasks. Use it VERY frequently: it is EXTREMELY helpful for planning and breaking down complex tasks, and without it you may forget important steps. Mark todos completed as soon as done, cancelled when unnecessary - never drop history. Consult the todo list in the <session-state> block at the start of each turn and re-check it before ending a turn: pending items are your queue.""";

    private static final String ANTHROPIC_PLAN = """
            Task Management (plan mode):
            The todo list IS the plan: use write_todo EXTREMELY frequently to record it and keep it in sync with the discussion. Every item must stay 'pending' - statuses only advance in build mode. Update the list in the same turn whenever the plan changes (scope, steps, approach, trade-offs). Completed/cancelled items from build sessions are the progress history: keep them, never drop them. In this plan-mode session you cannot advance a pending item: do not re-emit it as in_progress/completed, it will be kept pending until build mode. Consult the todo list in the <session-state> block at the start of each turn and re-check it before ending one: it must match the plan you just agreed on.""";

    private static final String GEMINI = """
            Task Management:
            Use the write_todo tool to keep a structured task list for multi-step work. Mark items in_progress as you start them and completed as soon as really done (several may be in_progress in parallel; do not batch completion updates). Mark unnecessary steps cancelled; keep completed/cancelled as history. Consult the todo list in the <session-state> block at the start of each turn and re-check it before ending a turn: pending items are your queue.""";

    private static final String GEMINI_PLAN = """
            Task Management (plan mode):
            Keep the todo list as the structured plan: every item 'pending' - statuses advance only in build mode. Use write_todo to record the plan and update it in the same turn whenever a follow-up changes it (scope, steps, approach, trade-offs). Completed/cancelled items from build sessions are the history: keep them. In this plan-mode session you cannot advance a pending item: do not re-emit it as in_progress/completed, it will be kept pending until build mode. Consult the todo list in the <session-state> block at the start of each turn and re-check it before ending one: it must match the plan you just agreed on.""";

    private static final List<Family> FAMILIES = List.of(
            new Family("anthropic", List.of("anthropic", "claude"), ANTHROPIC, ANTHROPIC_PLAN),
            new Family("gemini", List.of("google", "gemini", "generativelanguage"), GEMINI, GEMINI_PLAN));

    /**
     * Resolves the effective todo-guidance appendix for {@code todoMode} against {@code baseUrl}
     * in build mode (see {@link #resolve(String, String, AgentMode)}).
     *
     * @param todoMode the configured mode ('auto', a verbatim appendix, or blank/'false' to disable).
     * @param baseUrl  the provider base URL used for family detection in 'auto' mode.
     * @return the guidance block to append to the system prompt, or an empty string for none.
     */
    public String resolve(final String todoMode, final String baseUrl) {
        return resolve(todoMode, baseUrl, AgentMode.BUILD);
    }

    /**
     * Resolves the effective todo-guidance appendix for {@code todoMode} against {@code baseUrl},
     * picking the plan variant when {@code mode} is PLAN so the guidance matches the plan semantics
     * (todo list = plan, all items pending, re-sync on discussion changes) instead of the build-mode
     * completion tracking. Verbatim values and the disabled/blank cases are mode-agnostic.
     *
     * @param todoMode the configured mode ('auto', a verbatim appendix, or blank/'false' to disable).
     * @param baseUrl  the provider base URL used for family detection in 'auto' mode.
     * @param mode     the current agent mode, used to pick the plan variant in 'auto' mode.
     * @return the guidance block to append to the system prompt, or an empty string for none.
     */
    public String resolve(final String todoMode, final String baseUrl, final AgentMode mode) {
        final var configured = todoMode == null ? "auto" : todoMode;
        if (configured.isBlank() || configured.trim().equalsIgnoreCase("false")) {
            return "";
        }
        if (!configured.equalsIgnoreCase("auto")) {
            return configured;
        }
        final var plan = mode == AgentMode.PLAN;
        for (final var family : FAMILIES) {
            if (family.matches(baseUrl)) {
                return plan ? family.planBlock() : family.block();
            }
        }
        return plan ? OPENAI_PLAN : OPENAI;
    }
}
