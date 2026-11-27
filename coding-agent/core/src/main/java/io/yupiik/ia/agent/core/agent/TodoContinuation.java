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

import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * TODO-continuation and unfinished-todo reporting for the agent loop. Owns the stop paths that turn a
 * model-chosen end into an automatic continuation when pending todos remain and {@code
 * yca.todos.continueOnUnfinished} is enabled, plus the notices and transcript entries that make those
 * stops visible. Pure computation: the loop keeps {@code emit} and session-message mutation, this class
 * returns the notices and decisions.
 */
public final class TodoContinuation {
    /**
     * Base instruction sent to the model to continue instead of ending the turn: re-read the todo list
     * and act on the next unfinished item with tools, ending only when every todo is completed/cancelled
     * or genuinely blocked. Deliberately explicit about acting rather than narrating.
     */
    public static final String UNFINISHED_TODO_CONTINUE_PROMPT =
            "You ended your turn while the todo list still has unfinished items (pending or in_progress), so the work you were"
                    + " asked to do is not finished. Do not summarize your progress and do not wait for a new"
                    + " instruction: re-read the todo list in the <session-state> block and continue with the next"
                    + " unfinished item right now, using tools - finish or cancel the in_progress todos you already"
                    + " started before opening new ones. End your turn only when every todo is completed or"
                    + " explicitly cancelled, or when you are genuinely blocked - in that case state exactly what"
                    + " blocks you and stop.";

    /**
     * Variant appended from the second continuation on: a model repeating itself keeps answering with the
     * same progress report, so it is asked to state the blocker instead of trying again.
     */
    public static final String UNFINISHED_TODO_BLOCKED_PROMPT =
            " You already continued several times without closing the remaining todos, so continuing the same way"
                    + " is not working: do not repeat your progress report. Either complete or cancel the unfinished"
                    + " todo with a tool call right now, finish the in_progress todos you started instead of leaving"
                    + " them open, or explicitly tell the user what exactly blocks you (missing information, a"
                    + " failing command, a decision to make) and stop.";

    private final Path workdir;
    private final CodingAgentConfiguration configuration;
    private final Supplier<PermissionService> permissionService;
    private final Supplier<TodoStore> todoStore;
    private final SessionStore sessionStore;
    private final Clock clock;
    private final Supplier<String> currentSessionId;
    private final Supplier<String> subagentId;
    private final Supplier<String> subagentName;

    public TodoContinuation(
            final CodingAgentConfiguration configuration,
            final Supplier<PermissionService> permissionService,
            final Supplier<TodoStore> todoStore,
            final SessionStore sessionStore,
            final Clock clock,
            final Supplier<String> currentSessionId,
            final Supplier<String> subagentId,
            final Supplier<String> subagentName,
            final Path workdir) {
        this.workdir = Objects.requireNonNull(workdir, "workdir");
        this.configuration = configuration;
        this.permissionService = permissionService;
        this.todoStore = todoStore;
        this.sessionStore = sessionStore;
        this.clock = clock;
        this.currentSessionId = currentSessionId;
        this.subagentId = subagentId;
        this.subagentName = subagentName;
    }

    /** How many todos of the list are not completed yet, and how many the list holds. */
    public record PendingTodos(int pending, int total) {}

    /**
     * Computes the pending-todo summary of the turn, or {@code null} when no continuation hint applies:
     * no tools ran, no store, plan mode (pending todos are the expected end state there), an empty list
     * or a fully completed one.
     */
    public PendingTodos unfinishedTodos(final boolean toolsRanThisTurn) {
        if (!toolsRanThisTurn || todoStore.get() == null) {
            return null;
        }
        // in plan mode pending todos are the expected end state: the whole point is to
        // record the plan as todos, so no resume reminder is needed
        if (permissionService.get() != null && permissionService.get().mode() == AgentMode.PLAN) {
            return null;
        }
        final var todos = todoStore.get().all();
        if (todos.isEmpty()) {
            return null;
        }
        final var pending = todos.stream()
                .filter(t -> !"completed".equals(t.status()) && !"cancelled".equals(t.status()))
                .count();
        return pending == 0 ? null : new PendingTodos((int) pending, todos.size());
    }

    /** Turn-stopped warning notice listing the unfinished todos, for the UI and the transcript. */
    public AgentEvent.Notice unfinishedTodosNotice(final PendingTodos pending, final String finishReason) {
        return new AgentEvent.Notice(
                AgentEvent.Notice.Level.WARN,
                "model ended its turn (finish_reason=" + finishReason + ") with " + pending.pending() + '/'
                        + pending.total()
                        + " todos still unfinished - send 'continue' to resume, or review why it stopped",
                finishReason,
                currentSessionId.get(),
                subagentId.get(),
                subagentName.get(),
                workdir);
    }

    /**
     * Persists a turn-stopped notice (role "system") so the transcript can render it on replay and a
     * later resume can detect that the previous turn did not end naturally. These entries are display-only.
     */
    public void persistStopNotice(final String sessionId, final String message) {
        sessionStore.append(sessionId, SessionEntry.of(clock.millis(), "system", message, null, null, "notice"));
    }

    /** Continuations allowed per turn ({@code yca.todos.maxContinues}), zero when not configured. */
    public int todoMaxContinues() {
        return configuration.todos() == null ? 0 : configuration.todos().maxContinues();
    }

    /**
     * Turns a stop the model did not really choose - an empty completion, a truncation, a window whose
     * iteration budget ran out, or a plain text-only end - into a todo auto-continuation when pending
     * todos and {@code yca.todos.continueOnUnfinished} hold. Shared by every stop path so the
     * continuation budget ({@code yca.todos.maxContinues}) and the strategy-change prompt are honored
     * uniformly. Returns whether the caller must start a fresh iteration window ({@code continue} on
     * the labelled {@code windowLoop}).
     */
    public boolean tryTodoAutoContinue(
            final List<Message> messages,
            final String finishReason,
            final boolean toolsRanThisTurn,
            final boolean userInjected,
            final TurnState turnState,
            final Consumer<AgentEvent.Notice> emit) {
        final var pending = unfinishedTodos(toolsRanThisTurn);
        final var maxContinues = todoMaxContinues();
        if (pending == null
                || !canAutoContinueUnfinished(userInjected)
                || turnState.unfinishedTodoContinues >= maxContinues) {
            return false;
        }
        turnState.unfinishedTodoContinues++;
        emit.accept(notice(
                AgentEvent.Notice.Level.INFO,
                "model ended its turn (finish_reason=" + finishReason + ") with " + pending.pending() + '/'
                        + pending.total() + " todos still unfinished; continuing automatically ("
                        + turnState.unfinishedTodoContinues + '/' + maxContinues + ")."));
        messages.add(Message.user(unfinishedTodoContinuePrompt(turnState.unfinishedTodoContinues)));
        return true;
    }

    /**
     * Whether a text-only end with pending todos may be turned into a continuation. Opt-in through
     * {@code yca.todos.continueOnUnfinished} and deliberately conservative, because the model ending
     * its turn is legitimate in three cases: a sub-agent reporting back to its parent (its todo list
     * is its own), a turn carrying a prompt the user typed while the loop was running (the model just
     * answered that message) and a positive continuation budget that must be honored as a stop.
     */
    public boolean canAutoContinueUnfinished(final boolean userInjected) {
        return !userInjected
                && subagentId.get() == null
                && configuration.todos() != null
                && configuration.todos().continueOnUnfinished()
                && todoMaxContinues() > 0;
    }

    /**
     * The instruction sent to the model to continue instead of ending the turn, from the second
     * continuation on enriched with a strategy-change clause.
     */
    public String unfinishedTodoContinuePrompt(final int continuations) {
        final var configured = configuration.todos() == null
                ? null
                : configuration.todos().resolveContinuePrompt(UNFINISHED_TODO_CONTINUE_PROMPT);
        final var base = configured == null || configured.isBlank() ? UNFINISHED_TODO_CONTINUE_PROMPT : configured;
        return continuations >= 2 ? base + UNFINISHED_TODO_BLOCKED_PROMPT : base;
    }

    private AgentEvent.Notice notice(final AgentEvent.Notice.Level level, final String message) {
        return new AgentEvent.Notice(
                level, message, null, currentSessionId.get(), subagentId.get(), subagentName.get(), workdir);
    }
}
