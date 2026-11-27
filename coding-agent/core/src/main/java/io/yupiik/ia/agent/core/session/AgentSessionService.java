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
package io.yupiik.ia.agent.core.session;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.memory.MemoryCompactor;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.PlanFileService;
import io.yupiik.ia.agent.core.todo.PlanTodoSeeder;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Shared implementation of the interactive slash-command operations, so the
 * plain-terminal (CLI) and the terminal UI (TUI) do not duplicate the same
 * logic. This service only computes results and returns them as data records:
 * it never prints, never formats with ANSI or Markdown, and is synchronous.
 * Frontends are responsible for rendering the returned data (spinner,
 * transcript, colours) and for running it off their UI thread when needed.
 */
@ApplicationScoped
public class AgentSessionService {

    private final ChatClient chatClient;
    private final McpManager mcpManager;
    private final AgentProvider agentProvider;
    private final SessionStore sessionStore;
    private final MemoryStore memoryStore;
    private final StructuralCodeGraph structuralCodeGraph;
    private final PlanFileService planFileService;
    private final PlanTodoSeeder planTodoSeeder;
    private final CodingAgentConfiguration configuration;
    private final SnapshotStore snapshotStore;
    private final MemoryCompactor memoryCompactor;

    public AgentSessionService(
            final ChatClient chatClient,
            final McpManager mcpManager,
            final AgentProvider agentProvider,
            final SessionStore sessionStore,
            final MemoryStore memoryStore,
            final StructuralCodeGraph structuralCodeGraph,
            final PlanFileService planFileService,
            final PlanTodoSeeder planTodoSeeder,
            final CodingAgentConfiguration configuration,
            final SnapshotStore snapshotStore,
            final MemoryCompactor memoryCompactor) {
        this.chatClient = chatClient;
        this.mcpManager = mcpManager;
        this.agentProvider = agentProvider;
        this.sessionStore = sessionStore;
        this.memoryStore = memoryStore;
        this.structuralCodeGraph = structuralCodeGraph;
        this.planFileService = planFileService;
        this.planTodoSeeder = planTodoSeeder;
        this.configuration = configuration;
        this.snapshotStore = snapshotStore;
        this.memoryCompactor = memoryCompactor;
    }

    /** Kept for callers that do not wire the compactor (eg. tests). */
    public AgentSessionService(
            final ChatClient chatClient,
            final McpManager mcpManager,
            final AgentProvider agentProvider,
            final SessionStore sessionStore,
            final MemoryStore memoryStore,
            final StructuralCodeGraph structuralCodeGraph,
            final PlanFileService planFileService,
            final PlanTodoSeeder planTodoSeeder,
            final CodingAgentConfiguration configuration,
            final SnapshotStore snapshotStore) {
        this(
                chatClient,
                mcpManager,
                agentProvider,
                sessionStore,
                memoryStore,
                structuralCodeGraph,
                planFileService,
                planTodoSeeder,
                configuration,
                snapshotStore,
                null);
    }

    public record ToolInfo(String name, String description) {}

    public record ServerTools(String server, List<ToolInfo> tools) {}

    public record ToolListing(boolean anyTool, List<ServerTools> servers, int matches) {}

    /**
     * Lists the MCP tools, grouped by server and filtered by the user-provided
     * terms (matched against the lowercase {@code server_tool} key). Only
     * servers with at least one matching tool are returned. {@code anyTool}
     * tells whether the MCP layer reported any server at all, letting the
     * frontend distinguish "no tools available" from "no tool matches".
     */
    public ToolListing tools(final String arg, final Path root) {
        final var filters = new ArrayList<String>();
        for (final var token : arg.split(" ")) {
            final var filter = token.strip().toLowerCase(Locale.ROOT);
            if (!filter.isEmpty()) {
                filters.add(filter);
            }
        }
        final var byServer = new TreeMap<>(mcpManager.toolsByServer());
        final var servers = new ArrayList<ServerTools>();
        var count = 0;
        for (final var server : byServer.entrySet()) {
            final var tools = server.getValue().stream()
                    .sorted(Comparator.comparing(EmbeddedTool::name))
                    .filter(tool -> filters.stream().allMatch(filter -> {
                        final var rootKey = (server.getKey() + '_' + tool.name()).toLowerCase(Locale.ROOT);
                        return rootKey.contains(filter);
                    }))
                    .map(tool -> new ToolInfo(tool.name(), toolDescription(server.getKey(), tool, root)))
                    .toList();
            if (tools.isEmpty()) {
                continue;
            }
            servers.add(new ServerTools(server.getKey(), tools));
            count += tools.size();
        }
        return new ToolListing(!byServer.isEmpty(), servers, count);
    }

    private String toolDescription(final String server, final EmbeddedTool tool, final Path root) {
        var description =
                tool.description(root) == null ? "" : tool.description(root).strip();
        final var prefix = '[' + server + "] ";
        if (description.startsWith(prefix)) {
            description = description.substring(prefix.length()).strip();
        }
        final var lineBreak = description.indexOf('\n');
        if (lineBreak >= 0) {
            description = description.substring(0, lineBreak).strip();
        }
        return description.isEmpty() || description.equals(tool.name()) ? null : description;
    }

    /** The seed fields are retained for wire compatibility and are always null. */
    public record ModeChange(String message, String seedMessage, String seedError) {}

    /** Whether the typed input matches a resume alias ({@code yca.resumeAliases}). */
    public boolean isResumeAlias(final String input) {
        return configuration.isResumeAlias(input);
    }

    /**
     * Switches the agent working mode without deriving or changing todos.
     * Mode changes are local and do not require an LLM request.
     *
     * <p>Crossing from {@code PLAN} to {@code BUILD} also sets the session
     * approval mode to {@code AUTO_EDIT}: build implies a default posture where
     * file edits apply without prompting (bash still follows the plan/resolved
     * rules or the gate). The override applies unconditionally - even over an
     * explicit {@code /permissions} selection - so the symmetric
     * {@code BUILD -> PLAN} reset (back to the configured default) always
     * restores a clean plan posture. Switching {@code BUILD -> BUILD} keeps the
     * current approval mode untouched.
     */
    public ModeChange setMode(final String arg, final String sessionId, final SessionRuntime runtime) {
        final var previousMode = runtime.permissions().mode();
        final var mode = AgentMode.fromConfigurationString(arg);
        runtime.permissions().setMode(mode);
        if (mode == AgentMode.PLAN) {
            // approvals granted during a build phase must not leak back into planning
            runtime.permissions().clearSessionGrants();
            // session-scoped dynamic bash rule overrides (families allowed during the build phase) do not
            // leak either: switching back to plan restores the configured read-only posture
            runtime.permissions().clearDynamicRules();
            // a session approval mode set during build (yolo/auto_edit) must not silently keep
            // plan building: drop the session override so the mode falls back to the configured
            // default (ASK unless explicitly configured otherwise). An explicit /permissions
            // override in plan remains possible.
            runtime.permissions().setPermissionMode(null);
        } else if (previousMode != AgentMode.BUILD) {
            // plan -> build: build means "apply the work" by default; edits become silent until
            // the next build -> plan round-trip restores the configured default.
            runtime.permissions().setPermissionMode(PermissionMode.AUTO_EDIT);
        }
        if (mode != previousMode && runtime.loop() != null) {
            runtime.loop().notifyModeChanged(mode);
        }
        final var message = "mode: " + mode.name().toLowerCase(Locale.ROOT)
                + (mode == AgentMode.BUILD && previousMode != AgentMode.BUILD ? " - permissions: auto_edit" : "");
        return new ModeChange(message, null, null);
    }

    /**
     * Sets the tool permission level of the session owning {@code runtime}.
     * Returns the resulting permission mode, or {@code null} for an invalid
     * argument (the caller should show the usage hint).
     */
    public PermissionMode setPermissions(final String arg, final SessionRuntime runtime) {
        final var permissions = runtime.permissions();
        final var v = arg == null ? "" : arg.strip();
        if (v.equals("none") || v.equals("safe")) {
            permissions.setPermissionMode(PermissionMode.ASK);
            return PermissionMode.ASK;
        }
        if ("all".equalsIgnoreCase(v) || "unsafe".equalsIgnoreCase(v)) {
            // removed slash values: they used to toggle the (deleted) permit-all shortcut.
            // Use 'yolo' to auto-approve, or configure yca.permissions.mode=all globally.
            return null;
        }
        if (PermissionMode.isValue(v)) {
            final var resolved = PermissionMode.fromConfigurationString(v);
            permissions.setPermissionMode(resolved);
            return resolved;
        }
        return null;
    }

    public record PermissionsInfo(AgentMode agentMode, PermissionMode permissionMode, List<String> sessionGrants) {}

    /**
     * Returns the permission state of the session owning {@code runtime} for
     * the {@code /permissions show} command. Session grants are explicitly
     * session-scoped (in-memory, lost on restart).
     */
    public PermissionsInfo permissionsInfo(final SessionRuntime runtime) {
        final var permissions = runtime.permissions();
        return new PermissionsInfo(permissions.mode(), permissions.permissionMode(), permissions.sessionGrants());
    }

    /**
     * Sets the model of one session, stripping an optional leading {@code $} prefix.
     * Returns the effective model name. The selection lands in the passed
     * {@link ChatSettings} (owned by the shell/tab), never in the shared client.
     * A model change drops any previous variant/sampling override: they belong to
     * the previous model and would otherwise leak into the new one.
     */
    public ChatSettings setModel(final String arg, final ChatSettings settings) {
        final var model = arg.strip();
        final var effective = model.startsWith("$") ? model.substring(1) : model;
        return settings.withModel(effective).withVariant(null).withSampling(null);
    }

    /**
     * Sets the provider of one session, returning the provider id. The selection lands in the passed
     * {@link ChatSettings} (owned by the shell/tab), never in the shared client: each tab can run on
     * its own provider. The process-wide default ({@code yca.provider.defaultProvider} or the first
     * provider) applies to sessions without an override; a blank or {@code "default"} arg clears the
     * override so the session falls back to it. The session override (model/variant/sampling) is
     * provider-scoped, so switching provider resets the per-session model selection and lets the new
     * provider's configured model apply again. {@code chatClient.setProvider} is not called: it would
     * mutate the process-wide provider for every tab.
     */
    private volatile String lastProviderId;

    /**
     * Sets the provider of one session, returning the new selection value (pure; the caller applies
     * it to the session loop). A blank or {@code "default"} arg clears the override so the session
     * falls back to the process default provider. Provider switch resets the per-session model
     * selection (model/variant/sampling are provider-scoped).
     */
    public ChatSettings setProvider(final String arg, final ChatSettings settings) {
        final var id = arg == null ? "" : arg.strip();
        final var next = settings == null
                ? ChatSettings.EMPTY
                : settings.withProviderId(id).withoutModelOverrides();
        if (next.providerId() != null) {
            lastProviderId = next.providerId();
        }
        return next;
    }

    /** The last provider id this session ran on (the effective provider for display), or null. */
    public String lastProviderId() {
        return lastProviderId;
    }

    /**
     * Sets the active custom agent if it exists; returns {@code null} when the
     * agent is unknown (the caller should report it).
     */
    public String setAgent(final String arg) {
        final var name = arg.strip();
        return agentProvider.byName(name) == null ? null : name;
    }

    public record ContextMessage(String role, String firstLine, String content, String toolName) {}

    public record ContextDump(String systemPrompt, String sessionId, List<ContextMessage> messages) {}

    /**
     * Builds the raw context dump: the generated system prompt and the full
     * session messages. The frontend decides how to format and how much of
     * each message to show.
     */
    public ContextDump context(final String sessionId, final SessionRuntime runtime) {
        final var messages = sessionStore.load(sessionId);
        final var toolNamesByCallId = Message.toolNames(messages);
        final var lines = new ArrayList<ContextMessage>(messages.size());
        for (final var message : messages) {
            final var content = String.valueOf(message.content());
            lines.add(new ContextMessage(
                    message.role(), firstLine(content), content, Message.toolName(message, toolNamesByCallId)));
        }
        return new ContextDump(runtime.systemPromptBuilder().build(), sessionId, lines);
    }

    public record CompactionApplied(boolean applied, String message, String error) {}

    /**
     * Compacts the context window of a persisted session. Returns nothing to
     * compact when empty, and reports a fallback/error when the summarizer
     * could not run (so the frontend never shows a false success).
     */
    public CompactionApplied compact(final String sessionId, final SessionRuntime runtime) {
        return compact(sessionId, false, runtime);
    }

    /**
     * Compacts the context window of a persisted session. {@code force} bypasses the
     * within-budget guard so compaction runs even below the thresholds. The budget comes
     * from the session's own context manager. Returns nothing to compact when empty, and
     * reports a fallback/error when the summarizer could not run (so the frontend never
     * shows a false success).
     */
    public CompactionApplied compact(final String sessionId, final boolean force, final SessionRuntime runtime) {
        return compact(sessionId, force, false, 0, runtime);
    }

    /**
     * {@link #compact(String, boolean, SessionRuntime)} with an {@code urgent} flag (falls back to a
     * destructive local truncation when the summarizer fails transiently, instead of skipping the
     * pass - see {@code ContextManager.compact(..., urgent, ...)}) and a {@code keepLast} verbatim
     * tail floor override. Used by the {@code /compact [force] [urgent] [keep=<n>]} slash command.
     */
    public CompactionApplied compact(
            final String sessionId,
            final boolean force,
            final boolean urgent,
            final int keepLast,
            final SessionRuntime runtime) {
        final var history = sessionStore.load(sessionId);
        if (history.isEmpty()) {
            return new CompactionApplied(false, "nothing to compact", null);
        }
        final var result = runtime.contextManager()
                .compact(
                        history,
                        force,
                        null,
                        keepLast,
                        urgent,
                        () -> false,
                        runtime.chatSettings(),
                        new CompactionCoordinator.CompactionProgress(
                                runtime.loop().estimateWireToolTokens(),
                                effective -> runtime.loop().publishUsageContext(effective),
                                null,
                                null,
                                runtime.workdir()));
        if (!result.changed()) {
            return new CompactionApplied(false, "context already within budget", null);
        }
        sessionStore.replace(sessionId, result.messages());
        return switch (result.mode()) {
            case SUMMARY -> new CompactionApplied(true, "context compacted (summarized)", null);
            case CLEARED -> new CompactionApplied(true, "old tool outputs cleared", null);
            case SHRUNK -> new CompactionApplied(true, "verbose tool outputs shrunk", null);
            case TRUNCATE ->
                new CompactionApplied(true, "context truncated locally (summarizer failed)", result.error());
            case NONE -> new CompactionApplied(false, "context already within budget", null);
        };
    }

    /**
     * Prunes the context window of a persisted session (local, no LLM call). Returns nothing to
     * prune when empty or when no old tool output is worth clearing.
     */
    public CompactionApplied prune(final String sessionId, final SessionRuntime runtime) {
        final var history = sessionStore.load(sessionId);
        if (history.isEmpty()) {
            return new CompactionApplied(false, "nothing to prune", null);
        }
        final var result = runtime.contextManager().prune(history);
        if (!result.changed()) {
            return new CompactionApplied(false, "nothing to prune", null);
        }
        sessionStore.replace(sessionId, result.messages());
        return new CompactionApplied(
                true, "prune applied (" + result.mode().name().toLowerCase(Locale.ROOT) + ")", null);
    }

    public record SessionInfo(String id, Path path) {}

    /**
     * Returns the current session identifier and its on-disk location.
     */
    public SessionInfo sessionInfo(final String sessionId) {
        return new SessionInfo(sessionId, sessionStore.path(sessionId));
    }

    /**
     * Rewrites the current session to disk from its persisted transcript. Every
     * append is already synchronous, so this is mostly a defensive flush that
     * guarantees a clean, fully-written session file.
     */
    public String flush(final String sessionId) {
        final var history = sessionStore.load(sessionId);
        if (history.isEmpty()) {
            return "nothing to flush";
        }
        sessionStore.replace(sessionId, history);
        return "session flushed";
    }

    public record HousekeepingReport(int compressed, int deleted, int kept) {}

    /**
     * Runs the session housekeeping (retention cleanup, compression, empty
     * session removal) and returns the resulting report.
     */
    public HousekeepingReport housekeeping() {
        final var report = sessionStore.housekeeping(true);
        return new HousekeepingReport(report.compressed(), report.deleted(), report.kept());
    }

    /**
     * Resyncs the structural code graph index. Throws when the resync fails so
     * the caller can surface the error.
     */
    public String resyncGraph(final Path root) {
        return structuralCodeGraph.resync(root);
    }

    public record PlanExport(Path path, String original, String error) {}

    /**
     * Exports the current plan (plan mode only) to a file, returning the path
     * and the exact content written. The mode and the todo list are read from
     * the session owning {@code runtime}. The {@code error} member is set when
     * the export cannot happen.
     */
    public PlanExport exportPlan(
            final String sessionId, final String arg, final String titleFallback, final SessionRuntime runtime) {
        if (runtime.permissions().mode() != AgentMode.PLAN) {
            return new PlanExport(null, null, "editplan is only available in plan mode (run /mode plan)");
        }
        final var plan = planTodoSeeder.plan(sessionId);
        if (plan == null || plan.isBlank()) {
            return new PlanExport(null, null, "no plan to edit yet - produce a plan first");
        }
        final var path = (arg == null || arg.isBlank())
                ? planFileService.defaultPath(
                        Path.of(configuration.resolveStorageHome()),
                        titleFallback == null ? "" : titleFallback,
                        sessionId)
                : runtime.workdir().resolve(arg).normalize();
        final var content = planFileService.render(plan, runtime.todos().all());
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content);
            return new PlanExport(path, content, null);
        } catch (final IOException e) {
            return new PlanExport(path, null, "editplan failed to write " + path + ": " + e.getMessage());
        }
    }

    public record PlanReload(boolean changed, String message, String error) {}

    /**
     * Reloads an edited plan file: when its content changed, the parsed plan
     * and todos are applied to the session. Returns the resulting status
     * message, or {@code changed=false} when the file was not modified.
     */
    public PlanReload reloadPlan(
            final String sessionId, final Path path, final String original, final SessionRuntime runtime) {
        if (!Files.isRegularFile(path)) {
            return new PlanReload(false, null, "plan file not found: " + path);
        }
        final String edited;
        try {
            edited = Files.readString(path);
        } catch (final IOException e) {
            return new PlanReload(false, null, "editplan failed to read " + path + ": " + e.getMessage());
        }
        if (edited.equals(original)) {
            return new PlanReload(false, "no changes in the plan (nothing to reload)", null);
        }
        final var parsed = planFileService.parse(edited);
        final var applied = planFileService.reload(sessionId, parsed, runtime.todos());
        return new PlanReload(true, applied, null);
    }

    public record MemoryContent(
            String global,
            String project,
            String globalPath,
            String projectPath,
            boolean globalExists,
            boolean projectExists) {}

    /**
     * Reads global and project memory. Empty strings mean "no memory". Also exposes the resolved
     * file paths and whether each file exists, so UIs can show exactly where the memory is read
     * from and distinguish "no facts yet" from "file not found".
     */
    /** Reads project memory from the session root, retaining the configured global memory. */
    public MemoryContent memory(final Path root) {
        Objects.requireNonNull(root, "workdir");
        final var globalFile = memoryStore.globalFile();
        final var projectFile = MemoryStore.projectFile(root);
        return new MemoryContent(
                memoryStore.readGlobal(),
                memoryStore.readProject(root),
                globalFile.toAbsolutePath().normalize().toString(),
                projectFile.toAbsolutePath().normalize().toString(),
                Files.isRegularFile(globalFile),
                Files.isRegularFile(projectFile));
    }

    public record MemoryStats(
            int globalChars,
            int globalBullets,
            int projectChars,
            int projectBullets,
            long injectedBudget,
            MemoryCompactor.CompactionState lastCompaction) {}

    /**
     * Aggregated memory statistics for the inspection command: sizes, bullet
     * counts, the effective injected budget and the last compaction state.
     */
    /** Returns statistics and compaction state for the selected session root. */
    public MemoryStats memoryStats(final Path root) {
        Objects.requireNonNull(root, "workdir");
        final var global = memoryStore.readGlobal();
        final var project = memoryStore.readProject(root);
        return new MemoryStats(
                global.length(),
                (int) global.lines().filter(l -> l.strip().startsWith("-")).count(),
                project.length(),
                (int) project.lines().filter(l -> l.strip().startsWith("-")).count(),
                -1,
                memoryCompactor == null ? null : memoryCompactor.state(root));
    }

    /**
     * Runs the memory compaction synchronously (manual {@code /memory compact}).
     * Returns the status message from the compactor, or a hint when the compactor
     * is not wired.
     */
    /** Compacts project memory at the selected session root. */
    public String compactMemory(final Path root) {
        Objects.requireNonNull(root, "workdir");
        return memoryCompactor == null ? "memory compaction is not available" : memoryCompactor.compactNow(root);
    }

    public record AgentOverride(String agentName, String prompt) {}

    /**
     * Parses an optional leading {@code @agent} prompt override. When the name
     * does not match a configured agent the whole original text is kept as the
     * prompt and no agent is selected.
     */
    public AgentOverride agentOverride(final String text) {
        final var stripped = text == null ? "" : text.strip();
        if (stripped.startsWith("@")) {
            final var space = stripped.indexOf(' ');
            if (space > 1) {
                final var name = stripped.substring(1, space).strip();
                final var prompt = stripped.substring(space + 1).strip();
                return agentProvider.byName(name) != null
                        ? new AgentOverride(name, prompt)
                        : new AgentOverride(null, text);
            }
        }
        return new AgentOverride(null, text);
    }

    public record SnapshotStatus(String checkpoint, List<String> summaries) {}

    /**
     * {@code save [label]} sets an explicit checkpoint; {@code cancel} clears it; {@code status}
     * reports the active checkpoint and retained summaries. Completion returns a short status message.
     */
    public String snapshot(final String arg, final String sessionId) {
        final var trimmed = arg == null ? "" : arg.strip();
        final var lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("save")) {
            final var label = trimmed.substring("save".length()).strip();
            final var cp = snapshotStore.save(sessionId, label.isBlank() ? null : label);
            return "checkpoint saved: " + cp.label();
        }
        if (lower.equals("cancel")) {
            snapshotStore.cancel(sessionId);
            return "checkpoint cancelled";
        }
        if (lower.startsWith("restore")) {
            final var summary = trimmed.substring("restore".length()).strip();
            final var retained = snapshotStore.restore(sessionId, summary.isBlank() ? "manual snapshot" : summary);
            return retained.summary().isBlank()
                    ? "snapshot state cleared (no summary provided)"
                    : "snapshot recorded (summary injected in the system prompt each turn)";
        }
        // status (default)
        final var checkpoint = snapshotStore.checkpointLabel(sessionId);
        final var summaries =
                snapshotStore.retained(sessionId).stream().map(r -> r.summary()).toList();
        final var cp = checkpoint == null ? "(none)" : checkpoint;
        return "checkpoint: " + cp
                + (summaries.isEmpty()
                        ? " - no retained summaries"
                        : " - " + summaries.size() + " retained summary(ies)");
    }

    /**
     * The retained snapshot summaries to inject into the system prompt for a session, most recent last.
     */
    public List<SnapshotStore.Retained> snapshotSummaries(final String sessionId) {
        return snapshotStore.retained(sessionId);
    }

    /**
     * First, non-empty line of a text, collapsed to a single line.
     */
    public static String firstLine(final String text) {
        if (text == null) {
            return "";
        }
        final var nl = text.indexOf('\n');
        return (nl < 0 ? text : text.substring(0, nl)).strip();
    }
}
