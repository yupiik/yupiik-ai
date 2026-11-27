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
package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.events.TodoUpdatedEvent;
import io.yupiik.ia.agent.core.events.hooks.EventContextBuilder;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@ApplicationScoped
public class WriteTodoTool implements EmbeddedTool {

    private final Emitter emitter;
    private final EventContextBuilder contextBuilder;

    public WriteTodoTool(final Emitter emitter, final EventContextBuilder contextBuilder) {
        this.emitter = emitter;
        this.contextBuilder = contextBuilder;
    }

    protected WriteTodoTool() { // no-arg ctor for container-less grading/tests
        this.emitter = null;
        this.contextBuilder = null;
    }

    @Override
    public String name() {
        return "write_todo";
    }

    @Override
    public String description(final Path workdir) {
        return "Create and maintain the session's structured todo list (content + status) for multi-step work "
                + "(3+ distinct steps). Status: pending/in_progress/completed/cancelled. In PLAN mode new items "
                + "stay 'pending' (the list IS the plan); in BUILD mode mark in_progress as you start, completed "
                + "only once actually done, cancelled for dropped steps. Completed/cancelled items stay as "
                + "progress history. Skip for single trivial steps.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "todos",
                        Schemas.arrayOf(
                                "The full todo list.",
                                Schemas.object(
                                        Map.of(
                                                "content", Schemas.str("Task description."),
                                                "status",
                                                        Schemas.strEnum(
                                                                "One of pending, in_progress, completed, cancelled. In PLAN mode new items "
                                                                        + "must be 'pending': in_progress/completed are written back as pending "
                                                                        + "automatically, cancelled is kept (scope decision), and items already "
                                                                        + "advanced in a previous build session keep their status.",
                                                                "pending",
                                                                "in_progress",
                                                                "completed",
                                                                "cancelled")),
                                        "content",
                                        "status"))),
                "todos");
    }

    /**
     * Writes into the asking session's todo list: the store and the permission
     * mode come from the context (the tab that started the turn), so each tab
     * keeps its own list and its own plan/build mode.
     */
    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var store = context != null ? context.todos() : null;
        final var perms = context != null ? context.permissions() : null;
        if (store == null) {
            return ToolResult.error("write_todo requires an active session todo list.");
        }
        final var before = store.all();
        if (!(args.get("todos") instanceof List<?> raw)) {
            return ToolResult.error(
                    "todos must be a non empty array of {content, status} (got " + typeName(args.get("todos")) + ")");
        }
        final var todos = TodoStore.from(raw);
        if (todos.isEmpty()) {
            return ToolResult.error("todos must be a non empty array of {content, status}");
        }
        for (final var t : todos) {
            if (!t.isValid()) {
                return ToolResult.error("Invalid todo item: " + t);
            }
        }
        if (perms != null && perms.mode() == AgentMode.PLAN) {
            final var normalized = normalizePlanStatuses(store, todos);
            store.replace(todos);
            emitTodoUpdated(store, before, context);
            final var note = normalized.isEmpty()
                    ? ""
                    : " (plan mode: '" + String.join("', '", normalized)
                            + "' kept as pending - in plan mode you cannot hold an in_progress/completed status; "
                            + "do not re-emit these as in_progress/completed, they stay pending until build mode)";
            return ToolResult.ok("Todo list updated" + note + ":\n" + store.render());
        }
        store.replace(todos);
        emitTodoUpdated(store, before, context);
        return ToolResult.ok("Todo list updated:\n" + store.render());
    }

    /**
     * In PLAN mode the todo list is the plan, not a progress report: items describe future work and must stay
     * 'pending'. Items already advanced in a previous build-mode session keep their status so a plan/build
     * mode switch never loses real progress. New items sent as 'in_progress' or 'completed' are normalized back
     * to 'pending' (the update still succeeds); new 'cancelled' items are kept as-is because they encode a scope
     * decision ("we will not do X") that is meaningful in a plan. Only structurally invalid payloads are rejected.
     *
     * @return the contents of the items that were normalized to 'pending' (empty when nothing changed).
     */
    private List<String> normalizePlanStatuses(final TodoStore store, final List<TodoItem> todos) {
        final var alreadyAdvanced = store.all().stream()
                .map(t -> t.status() == null || "pending".equals(t.status()) ? null : key(t.content(), t.status()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        final var normalized = new ArrayList<String>();
        for (int i = 0; i < todos.size(); i++) {
            final var t = todos.get(i);
            if ("pending".equals(t.status())
                    || "cancelled".equals(t.status())
                    || alreadyAdvanced.contains(key(t.content(), t.status()))) {
                continue;
            }
            normalized.add(t.content());
            todos.set(i, new TodoItem(t.content(), "pending"));
        }
        return normalized;
    }

    /**
     * Content-based identity for the plan-mode preserve guard. The content is trimmed and
     * lower-cased so a legitimately-advanced item survives an otherwise-identical re-emission
     * with cosmetic text drift (surrounding whitespace or capitalisation), which would otherwise
     * be normalized back to 'pending' and recreate the very progress the list had recorded.
     */
    private static String key(final String content, final String status) {
        return (content == null ? "" : content.trim().toLowerCase(Locale.ROOT)) + '|' + status;
    }

    private static String typeName(final Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    private void emitTodoUpdated(final TodoStore store, final List<TodoItem> before, final ToolContext context) {
        if (emitter == null) {
            return;
        }
        final EventContext eventContext;
        if (contextBuilder == null) {
            eventContext = new EventContext(
                    context.sessionId(),
                    context.subagentId(),
                    context.subagentName(),
                    context.projectDir().toString(),
                    null,
                    null,
                    null,
                    null);
        } else {
            eventContext = contextBuilder.build(
                    null,
                    null,
                    context.sessionId(),
                    context.subagentId(),
                    context.subagentName(),
                    context.projectDir());
        }
        emitter.emit(new TodoUpdatedEvent(eventContext, before, store.all()));
    }
}
