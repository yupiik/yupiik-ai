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
package io.yupiik.ia.agent.acp;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent.CompactionPass;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.tools.FileMutation;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Session-local, live ACP v1 projection. The caller serializes calls and wraps each emitted map as
 * {@code params.update} of a {@code session/update} notification. No history is recorded or replayed.
 * The mapper and consumer are borrowed, not closed. There is no file access or background work.
 *
 * <p>Route tool events through {@link #tool}, usage through {@link #usage}, and other events through
 * {@link #event}, with {@link CompactionPass} events from that same AgentEvent stream routed through
 * {@link #compaction(CompactionPass)}. Supply
 * {@code TodoStore.all()} to {@link #plan} after actual store updates (not the
 * requested write_todo arguments, which core can normalize). Call {@link #endTurn} for each finished
 * scope, and {@link #close} when the session is released. Child text/thought streams are deliberately
 * not merged into the parent's conversation; child tool activity retains its coordinates in _meta.
 */
public final class AcpUpdates implements AutoCloseable {
    private final String sessionId;
    private final JsonMapper mapper;
    private final Consumer<Map<String, Object>> output;
    private final boolean notices;
    private final boolean compaction;
    private final Map<String, ToolState> tools = new LinkedHashMap<>();
    private final Map<String, CompactionPass> compactions = new LinkedHashMap<>();
    private String lastFeedback;
    private boolean closed;

    public AcpUpdates(
            final String sessionId,
            final JsonMapper mapper,
            final Map<String, ?> clientCapabilities,
            final Consumer<Map<String, Object>> output) {
        this.sessionId = Objects.requireNonNull(sessionId);
        this.mapper = Objects.requireNonNull(mapper);
        this.output = Objects.requireNonNull(output);
        this.notices = supports(clientCapabilities, "notices");
        this.compaction = supports(clientCapabilities, "compaction");
    }

    /** An explicit non-null object is required at both session and the nested capability. */
    public static boolean supports(final Map<String, ?> clientCapabilities, final String capability) {
        return clientCapabilities != null
                && clientCapabilities.get("session") instanceof Map<?, ?> session
                && session.get(capability) instanceof Map<?, ?>;
    }

    /** Captured display metadata, never permission to read a file. Paths must already be absolute. */
    public record Location(Path path, Long line) {
        public Location {
            requireAbsolute(path);
            if (line != null && (line < 1 || line > 0xffff_ffffL)) {
                throw new IllegalArgumentException("line must be a 1-based unsigned 32-bit integer");
            }
        }
    }

    /**
     * Exact, authorized, complete file contents captured by the executor, not replacement snippets or
     * a patch proposal. A null oldText is legal ONLY for a confirmed new file. Core's current textual
     * ToolFinished output cannot supply these; core mutations arrive through ToolFilesChanged.
     */
    public record FileEdit(Path path, boolean newFile, String oldText, String newText) {
        public FileEdit {
            requireAbsolute(path);
            Objects.requireNonNull(newText, "exact new contents are required");
            if (newFile ? oldText != null : oldText == null) {
                throw new IllegalArgumentException("oldText must be null exactly when a file is confirmed new");
            }
        }
    }

    /**
     * Effective tool root (including a child's workdir), optional known ACP kind, and captured results.
     * Empty locations permit conservative inference for single-path builtins. Ambiguous grep paths,
     * patch headers and shell commands are not parsed. Supply their actual locations from execution.
     * An unknown/custom tool defaults to other; names are opaque and are never substring-classified.
     */
    public record ToolSnapshot(Path root, String kind, List<Location> locations, List<FileEdit> edits) {
        public ToolSnapshot {
            if (root != null) {
                requireAbsolute(root);
            }
            if (kind != null
                    && !List.of(
                                    "read",
                                    "edit",
                                    "delete",
                                    "move",
                                    "search",
                                    "execute",
                                    "think",
                                    "fetch",
                                    "switch_mode",
                                    "other")
                            .contains(kind)) {
                throw new IllegalArgumentException("unknown ACP v1 tool kind: " + kind);
            }
            locations = locations == null ? List.of() : List.copyOf(locations);
            edits = edits == null ? List.of() : List.copyOf(edits);
        }
    }

    /**
     * Uses the server's canonical, session-unique id verbatim, including for permission requests and
     * asynchronous streams. The caller must distinguish turns, children and legacy ordinal=-1 calls;
     * this helper never derives correlation from a thread, tool name, or ordinal. Terminal results can
     * arrive without ToolStarted (core's lookup/argument/permission rejection paths).
     *
     * <p>A YCA pending result means still working, NOT failure and NOT awaiting permission: it maps to
     * in_progress. Its state is retained until a terminal result or endTurn. Current core collect calls
     * can return pending without a later result on that ordinal; the integration must finalize them.
     * Raw core mutation events must use {@link #files} to apply the connection's display policy first.
     */
    public void tool(final String id, final AgentEvent event, final ToolSnapshot snapshot) {
        if (!accepts(event)) {
            return;
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a canonical tool id is required");
        }
        final String name;
        if (event instanceof AgentEvent.ToolStarted started) {
            name = started.name();
        } else if (event instanceof AgentEvent.ToolStream stream) {
            name = stream.name();
        } else if (event instanceof AgentEvent.ToolDiagnostic diagnostic) {
            name = diagnostic.name();
        } else if (event instanceof AgentEvent.ToolFinished finished) {
            name = finished.name();
        } else if (event instanceof AgentEvent.ToolFilesChanged changed) {
            name = changed.name();
        } else {
            throw new IllegalArgumentException("not a tool event");
        }
        final var existing = tools.get(id);
        if (existing != null
                && (!Objects.equals(existing.subagentId, event.subagentId()) || !Objects.equals(existing.name, name))) {
            throw new IllegalArgumentException("canonical tool id already belongs to another tool/scope");
        }
        if (existing != null && event instanceof AgentEvent.ToolStarted) {
            throw new IllegalArgumentException("tool id is already active");
        }
        final var state = existing == null ? new ToolState(name, event.subagentId()) : existing;
        final var update = update(existing == null ? "tool_call" : "tool_call_update");
        update.put("toolCallId", id);
        if (existing == null) {
            update.put("title", name == null || name.isBlank() ? "Tool call" : name);
            if (name != null && !name.isBlank()) {
                update.put("name", name);
            }
            update.put("kind", snapshot != null && snapshot.kind() != null ? snapshot.kind() : kind(name));
        } else if (snapshot != null && snapshot.kind() != null) {
            update.put("kind", snapshot.kind());
        }
        coordinates(update, event);
        if (event instanceof AgentEvent.ToolStarted started) {
            update.put("status", "pending"); // emitted before core's permission gate
            final var input = input(started.args());
            if (input != null) {
                update.put("rawInput", input);
            }
            final var locations = locations(name, input, snapshot);
            if (!locations.isEmpty()) {
                update.put("locations", locations);
            }
            tools.put(id, state);
        } else if (event instanceof AgentEvent.ToolStream stream) {
            update.put("status", "in_progress");
            if (stream.fragment() != null && !stream.fragment().isEmpty()) {
                // BashTool emits display lines, including an initial "$ command", not byte deltas.
                if ("bash".equals(name) && !state.stream.isEmpty()) {
                    state.stream.append('\n');
                }
                state.stream.append(stream.fragment());
            }
            final var content = new ArrayList<Map<String, Object>>();
            content.add(toolText(state.stream.toString()));
            content.addAll(state.fileContent);
            content.addAll(state.diagnostics);
            update.put("content", List.copyOf(content));
            tools.put(id, state);
        } else if (event instanceof AgentEvent.ToolDiagnostic diagnostic) {
            // Display-only content, never rawOutput or the stream/result buffer.
            state.diagnostics.add(toolText(diagnostic.detail()));
            final var content = new ArrayList<Map<String, Object>>();
            if (!state.stream.isEmpty()) {
                content.add(toolText(state.stream.toString()));
            }
            content.addAll(state.fileContent);
            content.addAll(state.diagnostics);
            update.put("content", List.copyOf(content));
            tools.put(id, state);
        } else if (event instanceof AgentEvent.ToolFilesChanged changed) {
            update.put("status", "in_progress");
            for (final var file : changed.files()) {
                final Map<String, Object> location = Map.of("path", file.path().toString());
                if (!state.fileLocations.contains(location)) {
                    state.fileLocations.add(location);
                }
                if (file.content() != null) {
                    state.fileContent.add(diff(
                            file.path(),
                            file.content().oldText(),
                            file.content().newText()));
                }
                // Stable v1 has no deletion flag; empty newText alone cannot distinguish deletion.
                if (file.content() == null || file.kind() == FileMutation.Kind.DELETE) {
                    state.fileContent.add(toolText(switch (file.kind()) {
                                case CREATE -> "Created ";
                                case UPDATE -> "Updated ";
                                case DELETE -> "Deleted ";
                            }
                            + file.path()
                            + (file.content() == null ? " (exact file diff unavailable)." : ".")));
                }
            }
            final var content = new ArrayList<Map<String, Object>>();
            if (!state.stream.isEmpty()) {
                content.add(toolText(state.stream.toString()));
            }
            content.addAll(state.fileContent);
            content.addAll(state.diagnostics);
            update.put("content", List.copyOf(content));
            update.put("locations", List.copyOf(state.fileLocations));
            tools.put(id, state);
        } else if (event instanceof AgentEvent.ToolFinished finished) {
            update.put("status", finished.pending() ? "in_progress" : finished.success() ? "completed" : "failed");
            final var content = new ArrayList<Map<String, Object>>();
            if (finished.output() != null) {
                // Core output is TEXT, even when it happens to look like JSON. It replaces preview.
                update.put("rawOutput", finished.output());
                content.add(toolText(finished.output()));
                state.stream.setLength(0);
                state.stream.append(finished.output());
            } else if (!state.stream.isEmpty()) {
                content.add(toolText(state.stream.toString()));
            }
            if (!finished.pending() && snapshot != null) {
                for (final var edit : snapshot.edits()) {
                    content.add(diff(edit.path(), edit.oldText(), edit.newText()));
                }
            }
            content.addAll(state.fileContent);
            content.addAll(state.diagnostics);
            update.put("content", List.copyOf(content));
            final var locations = new ArrayList<>(state.fileLocations);
            for (final var location : locations(name, null, snapshot)) {
                if (!locations.contains(location)) {
                    locations.add(location);
                }
            }
            if (!locations.isEmpty()) {
                update.put("locations", List.copyOf(locations));
            }
            if (finished.pending()) {
                tools.put(id, state);
            } else {
                tools.remove(id);
            }
        }
        lastFeedback = null;
        emit(update);
    }

    /**
     * Apply the connection's display policy before projecting file data. If redaction changes either
     * side, it is no longer an exact diff: keep only location/text. Redacted paths are omitted, never
     * presented as a different modified file. This data is not model context or conversation history.
     */
    public void files(
            final String id, final AgentEvent.ToolFilesChanged event, final UnaryOperator<String> displayPolicy) {
        if (!accepts(event)) {
            return;
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a canonical tool id is required");
        }
        Objects.requireNonNull(displayPolicy, "displayPolicy");
        final var files = new ArrayList<FileMutation>();
        for (final var file : event.files()) {
            if (!file.path().toString().equals(displayPolicy.apply(file.path().toString()))) {
                continue;
            }
            final var content = file.content();
            final var unchanged = content != null
                    && (content.oldText() == null || content.oldText().equals(displayPolicy.apply(content.oldText())))
                    && content.newText().equals(displayPolicy.apply(content.newText()));
            files.add(new FileMutation(file.path(), file.kind(), unchanged ? content : null));
        }
        if (files.isEmpty()) {
            return;
        }
        tool(
                id,
                new AgentEvent.ToolFilesChanged(
                        displayPolicy.apply(event.name()),
                        files,
                        event.ordinal(),
                        event.sessionId(),
                        event.subagentId(),
                        event.subagentName(),
                        event.workdir()),
                null);
    }

    /** Full replacement, including completed and visibly labelled cancelled history; null means unknown. */
    public void plan(final List<TodoItem> capturedTodos) {
        if (closed || capturedTodos == null) {
            return;
        }
        final var entries = new ArrayList<Map<String, Object>>();
        for (final var todo : capturedTodos) {
            final var cancelled = "cancelled".equals(todo.status());
            entries.add(Map.of(
                    "content",
                    (cancelled ? "[Cancelled] " : "") + Objects.toString(todo.content(), ""),
                    "priority",
                    "medium", // YCA has no priority; do not infer urgency from list position.
                    "status",
                    cancelled || "completed".equals(todo.status())
                            ? "completed"
                            : "in_progress".equals(todo.status()) ? "in_progress" : "pending"));
        }
        emit(Map.of("sessionUpdate", "plan", "entries", List.copyOf(entries)));
    }

    /**
     * ACP requires BOTH used and size. Supply a captured, model-specific full context window, not
     * ContextMeasurement.budgetTokens (a padded estimate) or a maximum reverse-engineered from a
     * percentage. Unknown size falls back to meaningful text. Cumulative billed tokens are never used
     * as the current context. YCA Usage/Quota expose no cumulative spend/currency: cost is omitted.
     */
    public void usage(final AgentEvent.UsageUpdated event, final Long contextWindowTokens) {
        if (!accepts(event) || event.subagentId() != null) {
            return; // a child has its own context window, not the parent's usage gauge
        }
        final var measurement = event.contextMeasurement();
        final var used = event.estimatedContextTokens() >= 0
                ? event.estimatedContextTokens()
                : measurement == null ? -1 : measurement.tokens();
        final var known = used > 0 || (used == 0 && measurement != null);
        final var source = measurement != null && measurement.tokens() == used ? measurement.source() : "estimated";
        final var usage = event.usage();
        if (known && contextWindowTokens != null && contextWindowTokens > 0) {
            final var update = update("usage_update");
            update.put("used", used);
            update.put("size", contextWindowTokens);
            final var metrics = new LinkedHashMap<String, Object>();
            if (source != null) {
                metrics.put("contextSource", source);
            }
            if (usage != null
                    && (usage.promptTokens() > 0
                            || usage.completionTokens() > 0
                            || usage.cacheReadTokens() > 0
                            || usage.cacheWriteTokens() > 0)) {
                if (usage.promptTokens() >= 0) {
                    metrics.put("promptTokens", usage.promptTokens());
                }
                if (usage.completionTokens() >= 0) {
                    metrics.put("completionTokens", usage.completionTokens());
                }
                if (usage.cacheReadTokens() >= 0) {
                    metrics.put("cacheReadTokens", usage.cacheReadTokens());
                }
                if (usage.cacheWriteTokens() >= 0) {
                    metrics.put("cacheWriteTokens", usage.cacheWriteTokens());
                }
            }
            update.put("_meta", Map.of("yca", Collections.unmodifiableMap(metrics)));
            emit(update);
        } else {
            final var text = new StringBuilder();
            if (known) {
                text.append("Context: ")
                        .append(used)
                        .append(" tokens (")
                        .append(Objects.toString(source, "estimated"))
                        .append(").");
            }
            if (usage != null && (usage.promptTokens() > 0 || usage.completionTokens() > 0)) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append("Token usage:");
                if (usage.promptTokens() >= 0) {
                    text.append(' ').append(usage.promptTokens()).append(" input");
                }
                if (usage.completionTokens() >= 0) {
                    text.append(' ').append(usage.completionTokens()).append(" output");
                }
                text.append('.');
            }
            feedback("info", text.toString(), null, event, true);
        }
    }

    /** Live events only. This API never changes the server's prompt result or stop reason. */
    public void event(final AgentEvent event) {
        if (!accepts(event)) {
            return;
        }
        if (event instanceof AgentEvent.ToolStarted
                || event instanceof AgentEvent.ToolStream
                || event instanceof AgentEvent.ToolDiagnostic
                || event instanceof AgentEvent.ToolFinished
                || event instanceof AgentEvent.ToolFilesChanged) {
            throw new IllegalArgumentException("use tool() with the server's canonical tool id");
        } else if (event instanceof AgentEvent.TextDelta delta && event.subagentId() == null) {
            chunk("agent_message_chunk", delta.delta());
        } else if (event instanceof AgentEvent.ThinkingDelta delta && event.subagentId() == null) {
            chunk("agent_thought_chunk", delta.delta());
        } else if (event instanceof AgentEvent.Status status) {
            feedback("info", status.message(), null, event, true);
        } else if (event instanceof AgentEvent.Pause pause) {
            feedback("info", pause.message(), null, event, true);
        } else if (event instanceof AgentEvent.Notice notice) {
            if (notice.compaction() == AgentEvent.Notice.CompactionLifecycle.START
                    || notice.compaction() == AgentEvent.Notice.CompactionLifecycle.COMPLETED) {
                return; // legacy UI indicators are not operation boundaries; compaction() owns those
            }
            // Only the emitted message is public: never serialize cause, stack trace or finishReason.
            feedback(severity(notice.level()), notice.message(), null, event, false);
        } else if (event instanceof AgentEvent.Compacted compacted) {
            // This can merge several reductions (including prune-only work), not one compact pass.
            // Never invent an id or use it to complete an unrelated operation.
            final var tokens = compacted.tokens();
            final var detail = "Context compacted" + (compacted.mode() == null ? "" : " (" + compacted.mode() + ")")
                    + (tokens != null && tokens.before() >= 0 && tokens.after() >= 0
                            ? ": " + tokens.before() + " -> " + tokens.after() + " estimated tokens."
                            : ".");
            feedback("info", detail, null, event, false);
        } else if (event instanceof AgentEvent.UsageUpdated usage) {
            usage(usage, null);
        } else if (event instanceof AgentEvent.Error error) {
            feedback("error", error.message(), null, event, false);
        } else if (event instanceof AgentEvent.SubagentStarted started) {
            feedback("info", "Subagent started", started.task(), event, false);
        } else if (event instanceof AgentEvent.SubagentCompleted completed) {
            endTurn(event.subagentId(), completed.cancelled());
            feedback(
                    completed.success() || completed.cancelled() ? "info" : "warning",
                    completed.cancelled()
                            ? "Subagent cancelled"
                            : completed.success() ? "Subagent completed" : "Subagent failed",
                    completed.summary(),
                    event,
                    false);
        } else if (event instanceof AgentEvent.SubagentDelivered) {
            feedback("info", "Subagent result delivered", null, event, false);
        } else if (event instanceof AgentEvent.TurnCancelled) {
            endTurn(event.subagentId(), true);
        } else if (event instanceof AgentEvent.TurnDone) {
            endTurn(event.subagentId(), false); // streamed content has already been sent
        }
        // FeedbackStarted/LlmRequestCompleted/TurnMetrics are bookkeeping, not additional content.
        // TextReset has no ACP v1 retraction equivalent; the server must handle retry buffering.
    }

    /**
     * Route CompactionPass events from the normal AgentEvent stream here, including outside prompt turns.
     * Subscribe before admitting operations and preserve bus order. Only active passes are retained;
     * unmatched/duplicate terminals are ignored rather than inventing a pass or keeping tombstones.
     * SKIPPED maps to stable v1 completed (there is no skipped wire status), with explicit outcome
     * metadata and truthful text. Public summary is unavailable, so summary/chunks are omitted.
     */
    public void compaction(final CompactionPass pass) {
        if (closed || pass == null || !sessionId.equals(pass.sessionId())) {
            return;
        }
        if (pass.status() == CompactionPass.Status.STARTED) {
            if (compactions.putIfAbsent(pass.id(), pass) != null) {
                return;
            }
        } else {
            final var started = compactions.get(pass.id());
            if (started == null
                    || !Objects.equals(started.subagentId(), pass.subagentId())
                    || !started.workdir().equals(pass.workdir())) {
                return;
            }
            compactions.remove(pass.id()); // before output, which can reenter and start another pass
        }
        if (compaction) {
            emit(compactionUpdate(pass));
        } else {
            final var title =
                    pass.status() == CompactionPass.Status.STARTED ? "Compaction in progress..." : pass.detail();
            feedback(pass.status() == CompactionPass.Status.FAILED ? "warning" : "info", title, null, pass, false);
        }
    }

    private Map<String, Object> compactionUpdate(final CompactionPass pass) {
        final var update = update("compaction_update");
        update.put("compactionId", pass.id());
        update.put(
                "status",
                switch (pass.status()) {
                    case STARTED -> "in_progress";
                    case SUCCEEDED, SKIPPED -> "completed";
                    case FAILED -> "failed";
                    case CANCELLED -> "cancelled";
                });
        final var details = coordinates(pass.subagentId(), pass.subagentName());
        if (pass.status() != CompactionPass.Status.STARTED) {
            details.put("outcome", pass.status().name());
            if (pass.mode() != null) {
                details.put("mode", pass.mode().name());
            }
        }
        if (!details.isEmpty()) {
            update.put("_meta", Map.of("yca", Collections.unmodifiableMap(details)));
        }
        if (pass.status() == CompactionPass.Status.FAILED
                && pass.detail() != null
                && !pass.detail().isBlank()) {
            update.put("error", pass.detail());
        }
        return update;
    }

    /**
     * Releases unresolved state for exactly one scope. Without a core result it is not truthful to
     * mark work successful: unresolved compaction is failed (cancelled only on cancellation),
     * and tools failed with an explicit
     * missing-final-result explanation (stable v1 has no cancelled tool status). This is NOT turn failure.
     */
    public void endTurn(final String subagentId, final boolean cancelled) {
        final var unfinished = new LinkedHashMap<String, ToolState>();
        tools.entrySet().removeIf(entry -> {
            if (Objects.equals(subagentId, entry.getValue().subagentId)) {
                unfinished.put(entry.getKey(), entry.getValue());
                return true;
            }
            return false;
        });
        for (final var pass : List.copyOf(compactions.values())) {
            if (Objects.equals(subagentId, pass.subagentId())) {
                compaction(new CompactionPass(
                        pass.id(),
                        cancelled ? CompactionPass.Status.CANCELLED : CompactionPass.Status.FAILED,
                        null,
                        cancelled
                                ? "Compaction interrupted before its outcome was reported."
                                : "Compaction outcome was not reported before the turn ended.",
                        pass.sessionId(),
                        pass.subagentId(),
                        pass.subagentName(),
                        pass.workdir()));
            }
        }
        lastFeedback = null;
        for (final var entry : unfinished.entrySet()) {
            final var update = update("tool_call_update");
            update.put("toolCallId", entry.getKey());
            update.put("status", "failed");
            final var content = new ArrayList<Map<String, Object>>();
            if (!entry.getValue().stream.isEmpty()) {
                content.add(toolText(entry.getValue().stream.toString()));
            }
            content.addAll(entry.getValue().fileContent);
            content.addAll(entry.getValue().diagnostics);
            content.add(toolText(
                    cancelled
                            ? "Tool interrupted before a final result was reported."
                            : "No final tool result was reported before the turn ended."));
            update.put("content", List.copyOf(content));
            emit(update);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        tools.clear();
        final var unfinished = List.copyOf(compactions.values());
        compactions.clear();
        lastFeedback = null;
        // State is disposed before callbacks; no model/hooks/network are run under a lock here.
        for (final var pass : unfinished) {
            if (compaction) {
                output.accept(Collections.unmodifiableMap(compactionUpdate(new CompactionPass(
                        pass.id(),
                        CompactionPass.Status.CANCELLED,
                        null,
                        null,
                        pass.sessionId(),
                        pass.subagentId(),
                        pass.subagentName(),
                        pass.workdir()))));
            } else {
                final var title = (pass.subagentId() == null
                                ? ""
                                : "[" + Objects.toString(pass.subagentName(), pass.subagentId()) + "] ")
                        + "Compaction cancelled; session closed.";
                if (notices) {
                    final var update = update("notice");
                    update.put("severity", "info");
                    update.put("title", title);
                    final var coordinates = coordinates(pass.subagentId(), pass.subagentName());
                    if (!coordinates.isEmpty()) {
                        update.put("_meta", Map.of("yca", Collections.unmodifiableMap(coordinates)));
                    }
                    output.accept(Collections.unmodifiableMap(update));
                } else {
                    output.accept(Map.of(
                            "sessionUpdate", "agent_message_chunk", "content", text("\n[info] " + title + "\n")));
                }
            }
        }
    }

    private boolean accepts(final AgentEvent event) {
        return !closed && event != null && sessionId.equals(event.sessionId());
    }

    private void chunk(final String type, final String text) {
        if (text != null && !text.isEmpty()) {
            lastFeedback = null;
            emit(Map.of("sessionUpdate", type, "content", text(text)));
        }
    }

    private void feedback(
            final String severity,
            final String title,
            final String description,
            final AgentEvent event,
            final boolean deduplicate) {
        if (title == null || title.isBlank()) {
            return;
        }
        final var prefix = event == null || event.subagentId() == null
                ? ""
                : "[" + Objects.toString(event.subagentName(), event.subagentId()) + "] ";
        final var displayTitle = prefix + title;
        final var key =
                severity + ':' + (event == null ? "" : event.subagentId()) + ':' + displayTitle + ':' + description;
        if (deduplicate && key.equals(lastFeedback)) {
            return;
        }
        if (deduplicate) {
            lastFeedback = key;
        }
        if (notices) {
            final var update = update("notice");
            update.put("severity", severity);
            update.put("title", displayTitle);
            if (description != null && !description.isBlank()) {
                update.put("description", description);
            }
            if (event != null) {
                coordinates(update, event);
            }
            emit(update);
        } else {
            emit(Map.of(
                    "sessionUpdate",
                    "agent_message_chunk",
                    "content",
                    text("\n[" + severity + "] " + displayTitle
                            + (description == null || description.isBlank() ? "" : ": " + description) + "\n")));
        }
    }

    private Object input(final String args) {
        if (args == null) {
            return null;
        }
        try {
            return mapper.fromString(Object.class, args);
        } catch (final RuntimeException invalidJson) {
            return args; // Preserve malformed/legacy input as a legal rawInput JSON string.
        }
    }

    private List<Map<String, Object>> locations(final String name, final Object input, final ToolSnapshot snapshot) {
        final var locations = new ArrayList<Map<String, Object>>();
        if (snapshot != null) {
            for (final var location : snapshot.locations()) {
                final var value = new LinkedHashMap<String, Object>();
                value.put("path", location.path().toString());
                if (location.line() != null) {
                    value.put("line", location.line());
                }
                locations.add(Collections.unmodifiableMap(value));
            }
            for (final var edit : snapshot.edits()) {
                final Map<String, Object> value = Map.of("path", edit.path().toString());
                if (locations.stream()
                        .noneMatch(l -> l.get("path").equals(edit.path().toString()))) {
                    locations.add(value);
                }
            }
        }
        if (!locations.isEmpty()
                || !(input instanceof Map<?, ?> args)
                || !List.of("read", "write_file", "edit_file", "glob").contains(Objects.toString(name, ""))
                || !(args.get("path") instanceof String path)
                || path.isBlank()) {
            return List.copyOf(locations);
        }
        try {
            var resolved = Path.of(path);
            if (!resolved.isAbsolute()) {
                if (snapshot == null || snapshot.root() == null) {
                    return List.of(); // never substitute the process working directory
                }
                resolved = snapshot.root().resolve(resolved).normalize();
            }
            final var location = new LinkedHashMap<String, Object>();
            location.put("path", resolved.toString());
            if ("read".equals(name) && args.get("offset") instanceof Number line) {
                final var value = line.longValue();
                if (value >= 1 && value <= 0xffff_ffffL && line.doubleValue() == value) {
                    location.put("line", value);
                }
            }
            return List.of(Collections.unmodifiableMap(location));
        } catch (final InvalidPathException invalidPath) {
            return List.of(); // rawInput still preserves the rejected argument
        }
    }

    private static String kind(final String name) {
        return switch (Objects.toString(name, "")) {
            case "read", "read_symbol", "maven_knowledge_content", "skill", "code_check_graph" -> "read";
            case "write_file", "edit_file", "apply_patch", "memory_write" -> "edit";
            case "grep",
                    "glob",
                    "code_find_code",
                    "code_resolve",
                    "code_trace_calls",
                    "websearch",
                    "maven_repository_knowledge" -> "search";
            case "webfetch" -> "fetch";
            case "bash" -> "execute";
            case "write_todo", "compress", "list_subagent_options" -> "think";
            default -> "other";
        };
    }

    private static String severity(final AgentEvent.Notice.Level level) {
        return level == AgentEvent.Notice.Level.ERROR
                ? "error"
                : level == AgentEvent.Notice.Level.WARN ? "warning" : "info";
    }

    private static void coordinates(final Map<String, Object> update, final AgentEvent event) {
        final var coordinates = coordinates(event);
        if (!coordinates.isEmpty()) {
            update.put("_meta", Map.of("yca", Collections.unmodifiableMap(coordinates)));
        }
    }

    private static Map<String, Object> coordinates(final AgentEvent event) {
        return coordinates(event.subagentId(), event.subagentName());
    }

    private static Map<String, Object> coordinates(final String subagentId, final String subagentName) {
        final var coordinates = new LinkedHashMap<String, Object>();
        if (subagentId != null) {
            coordinates.put("subagentId", subagentId);
            if (subagentName != null) {
                coordinates.put("subagentName", subagentName);
            }
        }
        return coordinates;
    }

    private static Map<String, Object> text(final String text) {
        return Map.of("type", "text", "text", text);
    }

    private static Map<String, Object> toolText(final String text) {
        return Map.of("type", "content", "content", text(text));
    }

    private static Map<String, Object> diff(final Path path, final String before, final String after) {
        final var diff = new LinkedHashMap<String, Object>();
        diff.put("type", "diff");
        diff.put("path", path.toString());
        diff.put("oldText", before);
        diff.put("newText", after);
        return Collections.unmodifiableMap(diff);
    }

    private static Map<String, Object> update(final String type) {
        final var update = new LinkedHashMap<String, Object>();
        update.put("sessionUpdate", type);
        return update;
    }

    private static void requireAbsolute(final Path path) {
        if (path == null || !path.isAbsolute()) {
            throw new IllegalArgumentException("an absolute captured path is required");
        }
    }

    private void emit(final Map<String, Object> update) {
        if (!closed) {
            output.accept(Collections.unmodifiableMap(new LinkedHashMap<>(update)));
        }
    }

    private static final class ToolState {
        private final String name;
        private final String subagentId;
        private final StringBuilder stream = new StringBuilder();
        private final List<Map<String, Object>> fileContent = new ArrayList<>();
        private final List<Map<String, Object>> diagnostics = new ArrayList<>();
        private final List<Map<String, Object>> fileLocations = new ArrayList<>();

        private ToolState(final String name, final String subagentId) {
            this.name = name;
            this.subagentId = subagentId;
        }
    }
}
