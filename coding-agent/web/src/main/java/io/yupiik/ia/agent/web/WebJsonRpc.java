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
package io.yupiik.ia.agent.web;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpc;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpcParam;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.codegraph.GraphModel;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.CompletionEngine;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * JSON-RPC surface of the web UI: thin delegation to the shared core services,
 * exactly the operations the browser pages need. Method names are grouped by
 * resource ({@code sessions.*}, {@code session.*}, {@code tools.*}, ...).
 */
@ApplicationScoped
public class WebJsonRpc {
    private final SessionStore sessionStore;
    private final AgentSessionService agentSessionService;
    private final WebSessionManager sessions;
    private final WebRestoreSessions restoreSessions;
    private final StructuralCodeGraph codeGraph;
    private final McpManager mcpManager;
    private final CodingAgentConfiguration configuration;
    private final SlashRegistry slashRegistry;
    private final ChatClient chatClient;
    private final ProviderManager providerManager;
    private final AgentProvider agentProvider;
    private final WebCommand.WebConf webConf;
    private final SessionRuntimeFactory sessionRuntimeFactory;
    private final Path invocationWorkdir;

    // Fusion application-scope proxies must not execute the real constructor with null dependencies.
    protected WebJsonRpc() {
        this.sessionStore = null;
        this.agentSessionService = null;
        this.sessions = null;
        this.restoreSessions = null;
        this.codeGraph = null;
        this.mcpManager = null;
        this.configuration = null;
        this.slashRegistry = null;
        this.chatClient = null;
        this.providerManager = null;
        this.agentProvider = null;
        this.webConf = null;
        this.sessionRuntimeFactory = null;
        this.invocationWorkdir = null;
    }

    public WebJsonRpc(
            final SessionStore sessionStore,
            final AgentSessionService agentSessionService,
            final WebSessionManager sessions,
            final WebRestoreSessions restoreSessions,
            final StructuralCodeGraph codeGraph,
            final McpManager mcpManager,
            final CodingAgentConfiguration configuration,
            final WebSlashCommands slashRegistry,
            final ChatClient chatClient,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final WebCommand.WebConf webConf,
            final SessionRuntimeFactory sessionRuntimeFactory) {
        this.sessionStore = sessionStore;
        this.agentSessionService = agentSessionService;
        this.sessions = sessions;
        this.restoreSessions = restoreSessions;
        this.codeGraph = codeGraph;
        this.mcpManager = mcpManager;
        this.configuration = configuration;
        this.slashRegistry = slashRegistry == null ? null : slashRegistry.registry();
        this.chatClient = chatClient;
        this.providerManager = providerManager;
        this.agentProvider = agentProvider;
        this.webConf = webConf;
        this.sessionRuntimeFactory = sessionRuntimeFactory;
        this.invocationWorkdir = sessionRuntimeFactory.resolveWorkdir(null, null);
    }

    private CompletionEngine completionEngine(final Path workdir, final ChatSettings settings) {
        return new CompletionEngine(slashRegistry, new ArgCompletion.Env() {
            @Override
            public List<String> models() {
                return modelsOf(chatClient, settings);
            }

            @Override
            public List<String> providers() {
                return providersOf(providerManager);
            }

            @Override
            public List<String> agents() {
                return agentsOf(agentProvider);
            }

            @Override
            public List<String> sessions() {
                return sessionsOf(sessionStore);
            }

            @Override
            public List<String> tools() {
                return toolsOf(mcpManager);
            }

            @Override
            public List<String> servers() {
                return serversOf(mcpManager);
            }

            @Override
            public List<String> variants(final String model) {
                return variantsOf(chatClient, model == null || model.isBlank() ? settings : settings.withModel(model));
            }

            @Override
            public List<String> variants() {
                return variantsOf(chatClient, settings);
            }

            @Override
            public List<String> paths(final String token) {
                return CompletionEngine.pathCandidates(token, workdir);
            }
        });
    }

    @JsonRpc(
            value = "sessions.list",
            documentation = "Lists the persisted sessions with id, title, modifiedAt and size.")
    public List<SessionSummary> listSessions(
            @JsonRpcParam(documentation = "Optional case-insensitive filter on title or id.") final String query,
            @JsonRpcParam(
                            documentation =
                                    "When set and non-blank, keeps only sessions whose recorded project matches; blank/absent returns every session "
                                            + "(the home page passes an empty string to list all and group by working directory itself).")
                    final String project) {
        final var needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        final var projectScope = project == null || project.isBlank() ? "" : project;
        return sessionStore.list().stream()
                .map(info -> new SessionSummary(
                        info.id(), title(info.id()), info.modifiedAt(), info.sizeBytes(), projectOf(info.id())))
                .filter(s -> projectScope.isEmpty()
                        || s.project() == null
                        || s.project().isBlank()
                        || sameProject(s.project(), projectScope))
                .filter(s -> needle.isEmpty()
                        || s.title() != null
                                && s.title().toLowerCase(Locale.ROOT).contains(needle)
                        || s.id().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    @JsonRpc(
            value = "web.config",
            documentation =
                    "Returns the web command's tunable frontend settings (e.g. the transcript pagination window size).")
    public Map<String, Object> webConfig() {
        return Map.of("windowBlocks", webConf.windowBlocks());
    }

    @JsonRpc(
            value = "session.loadEarlier",
            documentation =
                    "Returns an earlier slice of a session's replayed transcript events, for tail-first snapshot"
                            + " pagination. excludeFromTail is how many of the most recent events the client already holds"
                            + " (the snapshot tail plus anything loaded so far); the returned list is the chunk of up to"
                            + " windowBlocks events immediately before that, plus the new cumulative loaded count the"
                            + " client should treat as the refreshed cursor. The full replay is cached per session and"
                            + " only rebuilt when the persisted message count changes, so paginating a long session is"
                            + " O(window) per page after the first.")
    public Map<String, Object> loadEarlier(
            @JsonRpcParam(required = true, documentation = "Session id.") final String sessionId,
            @JsonRpcParam(
                            required = true,
                            documentation =
                                    "How many of the most recent replayed events are already loaded client-side (exclusive cursor).")
                    final int excludeFromTail) {
        final var events = replayedCache(sessionId);
        final var total = events.size();
        final var end = Math.max(0, total - excludeFromTail);
        final var from = Math.max(0, end - webConf.windowBlocks());
        final var slice = events.subList(from, end);
        return Map.of("events", slice, "loaded", total - from);
    }

    /** Full replay of a session's persisted history, cached per session id and invalidated when the store grows. */
    private List<WebEventDto> replayedCache(final String sessionId) {
        final var current = sessionStore.count(sessionId);
        final var cached = replayCache.get(sessionId);
        if (cached != null && cached.count() == current) {
            return cached.events();
        }
        final var events = sessions.replay(sessionId, sessionStore.load(sessionId));
        // bound the cache: a session dropped from the map is simply rebuilt on its next pagination click
        if (replayCache.size() > 64) {
            replayCache.clear();
        }
        replayCache.put(sessionId, new CachedReplay(current, events));
        return events;
    }

    private record CachedReplay(int count, List<WebEventDto> events) {}

    private final ConcurrentHashMap<String, CachedReplay> replayCache = new ConcurrentHashMap<>();

    @JsonRpc(value = "sessions.create", documentation = "Creates a new empty session and returns its id.")
    public Map<String, String> createSession() {
        return Map.of("id", sessionStore.create());
    }

    @JsonRpc(
            value = "sessions.restore",
            documentation = "Returns the session ids the web command was configured to restore out-of-the-box "
                    + "(its --session option(s)), filtered to the ones that actually exist - a stale or "
                    + "deleted id is never handed back, so the UI opens a tab only for live sessions. Returns "
                    + "an empty list when the command started without --session.")
    public List<String> restoreSessions() {
        final var existing = sessionStore.list();
        if (existing == null || existing.isEmpty()) {
            return List.of();
        }
        final var ids = new HashSet<String>();
        ids.addAll(existing.stream().map(i -> i.id()).toList());
        return restoreSessions.sessions().stream()
                .filter(ids::contains)
                .distinct()
                .toList();
    }

    @JsonRpc(value = "sessions.currentProject", documentation = "Returns the current working/project directory.")
    public Map<String, String> currentProject() {
        return Map.of("project", invocationWorkdir.toString());
    }

    @JsonRpc(value = "sessions.get", documentation = "Returns the full message list of a session.")
    public List<Message> getSession(@JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        return sessionStore.load(id);
    }

    @JsonRpc(value = "sessions.delete", documentation = "Deletes a session.")
    public Map<String, Boolean> deleteSession(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        sessions.detach(id);
        sessionStore.delete(id);
        return Map.of("deleted", true);
    }

    @JsonRpc(
            value = "session.prompt",
            documentation = "Submits a prompt to the agent for a session; the turn runs asynchronously.")
    public Map<String, Boolean> submitPrompt(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id,
            @JsonRpcParam(required = true, documentation = "The prompt to send.") final String prompt,
            @JsonRpcParam(documentation = "Optional custom agent name (yca.agents).") final String agent) {
        // Install the default before creating a runtime so its tools and prompt resolve the same
        // project as the persisted marker. markProject preserves an existing/restored session root.
        final var workdir = sessions.isAttached(id) ? sessions.runtime(id).workdir() : invocationWorkdir;
        sessionStore.markProject(id, workdir.toString());
        sessions.attach(id);
        sessions.submit(id, prompt, blankToNull(agent));
        return Map.of("submitted", true);
    }

    @JsonRpc(
            value = "session.input",
            documentation = "Routes composer input, returning accepted, correct, or cancel after any routing picker.")
    public CompletableFuture<InputResult> input(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id,
            @JsonRpcParam(required = true, documentation = "The composer input.") final String text) {
        sessions.attach(id);
        return sessions.input(id, text).thenApply(InputResult::new);
    }

    @JsonRpc(
            value = "session.resume",
            documentation = "Resumes the last turn of a session (no new prompt); runs asynchronously.")
    public Map<String, Boolean> resumeSession(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        sessions.attach(id);
        sessions.resume(id);
        return Map.of("submitted", true);
    }

    @JsonRpc(value = "session.cancel", documentation = "Cancels the running turn of a session.")
    public Map<String, Boolean> cancelSession(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        sessions.cancel(id);
        return Map.of("cancelled", true);
    }

    @JsonRpc(
            value = "session.cancelAll",
            documentation = "Cancels the running turn of a session and drops its queued prompts (the web's"
                    + " \"close tab\" equivalent of the TUI's closeTab). The session stays attached and resumable.")
    public Map<String, Boolean> cancelAllSession(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        sessions.cancelAll(id);
        return Map.of("cancelled", true);
    }

    @JsonRpc(
            value = "session.slash",
            documentation = "Dispatches a slash command (e.g. /model, /permissions yolo, /mode build) without"
                    + " starting a turn; interactive commands open a picker modal the browser answers. Blocks"
                    + " until the command settled so the caller can immediately read the resulting state."
                    + " Returns true when the command was dispatched.")
    public Map<String, Boolean> dispatchSlash(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id,
            @JsonRpcParam(required = true, documentation = "The full slash command line, e.g. '/mode build'.")
                    final String text) {
        sessions.attach(id);
        final var ok = sessions.slash(id, text).toCompletableFuture().join();
        return Map.of("dispatched", ok == null || ok);
    }

    @JsonRpc(
            value = "session.btwClear",
            documentation = "Clears the current /btw side question of a session: cancels an in-flight answer"
                    + " and removes the btw pill + modal (the StatusBar 'x'). Returns true when there was an"
                    + " active btw to clear.")
    public Map<String, Boolean> clearBtw(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        sessions.attach(id);
        final var had = sessions.btwState(id) != null;
        sessions.clearBtw(id);
        return Map.of("cleared", had);
    }

    @JsonRpc(value = "tools.list", documentation = "Lists the available MCP tools, grouped by server.")
    public ToolListingDto tools(
            @JsonRpcParam(documentation = "Space-separated lowercase filters matched against server_tool keys.")
                    final String query) {
        return toDto(agentSessionService.tools(query == null ? "" : query, invocationWorkdir));
    }

    @JsonRpc(value = "memory.get", documentation = "Reads the global and project memory and their resolved file paths.")
    public MemoryDto memory() {
        final var content = agentSessionService.memory(invocationWorkdir);
        return new MemoryDto(
                content.global(),
                content.project(),
                content.globalPath(),
                content.projectPath(),
                content.globalExists(),
                content.projectExists());
    }

    @JsonRpc(value = "mode.get", documentation = "Returns the agent mode of a session.")
    public Map<String, String> mode(@JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        return Map.of("mode", lowerEnum(sessions.runtime(id).permissions().mode()));
    }

    @JsonRpc(
            value = "session.queuedCount",
            documentation = "Returns the number of prompts currently queued for a running turn of a session.")
    public Map<String, Integer> queuedCount(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        return Map.of("count", sessions.queuedPromptCount(id));
    }

    @JsonRpc(
            value = "session.queuedList",
            documentation = "Lists the prompts currently queued for a running turn, oldest first.")
    public QueuedListDto queuedList(@JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        return new QueuedListDto(sessions.queuedPrompts(id));
    }

    @JsonRpc(
            value = "session.queuedRemove",
            documentation = "Removes a queued prompt by index (0 = oldest, the next one the turn would pick up).")
    public Map<String, Boolean> queuedRemove(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id,
            @JsonRpcParam(required = true, documentation = "Index of the queued prompt to drop.") final int index) {
        return Map.of("removed", sessions.removeQueuedPrompt(id, index));
    }

    @JsonRpc(value = "mode.set", documentation = "Switches the agent mode of a session (build|plan).")
    public ModeChangeDto setMode(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id,
            @JsonRpcParam(required = true, documentation = "build or plan.") final String mode) {
        final var changed = agentSessionService.setMode(mode, id, sessions.runtime(id));
        return new ModeChangeDto(changed.message(), changed.seedMessage(), changed.seedError());
    }

    @JsonRpc(value = "permissions.get", documentation = "Returns the permission state of a session.")
    public PermissionsDto permissions(@JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        final var info = agentSessionService.permissionsInfo(sessions.runtime(id));
        return new PermissionsDto(lowerEnum(info.agentMode()), lowerEnum(info.permissionMode()), info.sessionGrants());
    }

    @JsonRpc(
            value = "permissions.set",
            documentation = "Sets the tool permission level of a session (ask|auto_edit|yolo|full_yolo).")
    public Map<String, String> setPermissions(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id,
            @JsonRpcParam(required = true, documentation = "ask, auto_edit, yolo or full_yolo (not recommended).")
                    final String mode) {
        final var resolved = agentSessionService.setPermissions(mode, sessions.runtime(id));
        return Map.of("permissionMode", lowerEnum(resolved));
    }

    @JsonRpc(value = "snapshot.status", documentation = "Reports the snapshot/checkpoint state of a session.")
    public Map<String, String> snapshotStatus(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        return Map.of("status", agentSessionService.snapshot("status", id));
    }

    @JsonRpc(value = "session.todos", documentation = "Lists the todo items of a session.")
    public List<TodoDto> sessionTodos(@JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        final var todos = sessions.runtime(id).todos() == null
                ? null
                : sessions.runtime(id).todos().all();
        if (todos == null || todos.isEmpty()) {
            return List.of();
        }
        return todos.stream().map(t -> new TodoDto(t.content(), t.status())).toList();
    }

    @JsonRpc(
            value = "session.setProject",
            documentation = "Changes a session's working/project directory and restarts its runtime on it.")
    public Map<String, Object> setProject(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id,
            @JsonRpcParam(required = true, documentation = "Absolute working directory path.") final String dir) {
        if (dir == null || dir.isBlank()) {
            return Map.of("ok", false, "error", "directory is required");
        }
        final Path normalized;
        try {
            normalized = sessionRuntimeFactory.resolveWorkdir(Path.of(dir), null);
        } catch (final IllegalArgumentException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
        // persist the new marker (replaceProject handles the case where one already exists) ...
        sessionStore.replaceProject(id, normalized.toString());
        // ... then tear down and reattach so the next turn resolves tools/system prompt against the new root.
        sessions.replace(id);
        return Map.of("ok", true, "project", normalized.toString());
    }

    @JsonRpc(value = "session.gitBranch", documentation = "Returns the git branch of a session's working directory.")
    public Map<String, String> sessionGitBranch(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        // a session has its own recorded project (set via session.setProject / recorded on submit) which
        // may differ from the process-level project dir; report the branch of THAT dir, not the global cwd.
        return Map.of("branch", gitBranchOf(sessions.runtime(id).workdir().toString()));
    }

    @JsonRpc(
            value = "session.metadata",
            documentation =
                    "Returns the TUI-style right-pane metadata of a session: model, provider, flavor, working dir, "
                            + "git branch, agent + permission mode, MCP server statuses and the model/provider "
                            + "candidates for the pickers.")
    public SessionMetadataDto sessionMetadata(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        final var runtime = sessions.runtime(id);
        final var chatSettings = runtime.chatSettings();
        final var dir = runtime.workdir().toString();
        final var info = agentSessionService.permissionsInfo(runtime);
        return new SessionMetadataDto(
                id,
                chatClient == null ? "" : safe(() -> chatClient.model(chatSettings)),
                chatClient == null
                        ? ""
                        : safe(() -> chatClient.flavor(chatSettings).name()),
                chatClient == null ? "" : safe(() -> chatClient.providerId(chatSettings)),
                dir,
                gitBranchOf(dir),
                lowerEnum(info.agentMode()),
                lowerEnum(info.permissionMode()),
                runtime.sandbox().enabled(),
                mcpStatus(),
                modelsOf(chatClient, chatSettings),
                providersOf(providerManager));
    }

    @JsonRpc(value = "model.list", documentation = "Lists the models available from the current provider.")
    public List<String> modelList(@JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        // model candidates must follow the session's effective provider (which may be a per-session
        // override), matching how session.metadata builds the picker - not the process-default provider.
        return modelsOf(chatClient, sessions.runtime(id).chatSettings());
    }

    @JsonRpc(value = "model.set", documentation = "Changes the model of a session; returns the effective model name.")
    public Map<String, String> setModel(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id,
            @JsonRpcParam(required = true, documentation = "Model name (an optional leading $ is stripped).")
                    final String model) {
        final var runtime = sessions.runtime(id);
        final var updated = agentSessionService.setModel(model, runtime.chatSettings());
        runtime.updateChatSettings(updated);
        final var effective =
                updated == null || updated.model() == null || updated.model().isBlank() ? model : updated.model();
        return Map.of("model", effective);
    }

    @JsonRpc(value = "provider.list", documentation = "Lists the configured provider names.")
    public List<String> providerList(@JsonRpcParam(required = true, documentation = "Session id.") final String id) {
        return providersOf(providerManager);
    }

    @JsonRpc(
            value = "provider.set",
            documentation = "Changes the provider of a session, resetting its model selection; blank or \"default\" "
                    + "restores the process default provider.")
    public Map<String, String> setProvider(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id,
            @JsonRpcParam(required = true, documentation = "Provider name (blank or \"default\" resets to default).")
                    final String provider) {
        final var runtime = sessions.runtime(id);
        final var updated = agentSessionService.setProvider(provider, runtime.chatSettings());
        runtime.updateChatSettings(updated);
        return Map.of("provider", updated == null || updated.providerId() == null ? "" : updated.providerId());
    }

    @JsonRpc(
            value = "session.resolve",
            documentation =
                    "Resolves a pending interactive request (approval/ask/question) pushed over SSE. The frontend "
                            + "invokes this with the request id from the rpc frame and the browser's answer when the "
                            + "user approves/declines or picks an option.")
    public Map<String, Object> resolveRequest(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id,
            @JsonRpcParam(required = true, documentation = "The pending request id from the SSE rpc frame.")
                    final String requestId,
            @JsonRpcParam(
                            documentation =
                                    "The resolution: true/false for approvals, a string for asks, null (or absent) to cancel. "
                                            + "Optional because null is a valid cancel resolution and fusion's required "
                                            + "flag treats null as missing, which would reject the cancel path.")
                    final Object result) {
        return Map.of("resolved", sessions.resolve(id, requestId, result));
    }

    private static String safe(final Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (final RuntimeException e) {
            return "";
        }
    }

    /** Enum discriminator rendered as the lowercase value the web UI compares against. */
    private static String lowerEnum(final Enum<?> value) {
        return value == null ? "" : value.name().toLowerCase(Locale.ROOT);
    }

    /** Current git branch of a directory, read from its {@code .git/HEAD}; empty when not a repo. */
    private static String gitBranchOf(final String dir) {
        if (dir == null || dir.isBlank()) {
            return "";
        }
        try {
            final var head = Path.of(dir, ".git", "HEAD");
            if (!Files.isRegularFile(head)) {
                return "";
            }
            final var content = Files.readString(head).strip();
            if (content.startsWith("ref:")) {
                final var ref = content.substring(4).strip();
                return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
            }
            // detached HEAD: return the bare sha (like GitBranchPoller)
            return content.length() > 12 ? content.substring(0, 12) : content;
        } catch (final IOException e) {
            return "";
        }
    }

    private ToolListingDto toDto(final AgentSessionService.ToolListing listing) {
        final var servers = listing.servers().stream()
                .map(s -> new ToolListingDto.ServerDto(
                        s.server(),
                        s.tools().stream()
                                .map(t -> new ToolListingDto.ToolDto(t.name(), t.description()))
                                .toList()))
                .toList();
        return new ToolListingDto(listing.anyTool(), servers, listing.matches());
    }

    @JsonRpc(value = "mcp.status", documentation = "Lists the MCP servers and their connection state.")
    public List<McpDto> mcpStatus() {
        return mcpManager.serverStatuses().stream()
                .map(s -> {
                    if ("yca".equals(s.name())
                            && configuration.tools().builtin().enabled()) {
                        return new McpDto(s.name(), String.valueOf(s.state()), "in-process", null);
                    }
                    final var server =
                            configuration.mcp() == null || configuration.mcp().servers() == null
                                    ? null
                                    : configuration.mcp().servers().stream()
                                            .filter(candidate -> s.name().equals(candidate.name()))
                                            .findFirst()
                                            .orElse(null);
                    final var http = server != null
                            && server.url() != null
                            && !server.url().isBlank();
                    final var transport = http
                            ? "http"
                            : server != null
                                            && server.command() != null
                                            && !server.command().isBlank()
                                    ? "stdio"
                                    : null;
                    return new McpDto(
                            s.name(), String.valueOf(s.state()), transport, http ? displayMcpUrl(server.url()) : null);
                })
                .toList();
    }

    // Never return raw configuration on parse failure, nor authentication material in user-info,
    // query parameters or fragments. Keep the configured raw path (including percent encoding).
    private static String displayMcpUrl(final String url) {
        try {
            final var uri = URI.create(url);
            if (uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return null;
            }
            final var authority = uri.getRawAuthority();
            return uri.getScheme() + "://"
                    + (uri.getRawUserInfo() == null ? "" : "[redacted]@")
                    + authority.substring(authority.lastIndexOf('@') + 1)
                    + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?[redacted]")
                    + (uri.getRawFragment() == null ? "" : "#[redacted]");
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    @JsonRpc(value = "mcp.reset", documentation = "Resets one MCP server (or all when no name is given).")
    public Map<String, Boolean> mcpReset(
            @JsonRpcParam(documentation = "Server name to reset; blank resets every server.") final String name) {
        if (name == null || name.isBlank()) {
            mcpManager.reset();
        } else {
            mcpManager.reset(name);
        }
        return Map.of("reset", true);
    }

    @JsonRpc(
            value = "slash.list",
            documentation = "Lists the slash command catalog with name, usage, group and description.")
    public List<SlashCommandCatalog> slashList() {
        return slashRegistry.specs().stream()
                .map(spec -> new SlashCommandCatalog(
                        spec.name(),
                        spec.command(),
                        spec.usage(),
                        spec.group().name().toLowerCase(Locale.ROOT),
                        spec.description()))
                .toList();
    }

    @JsonRpc(
            value = "slash.complete",
            documentation = "Completes a slash command (or its argument) at the given cursor position.")
    public List<CompletionSuggestion> slashComplete(
            @JsonRpcParam(required = true, documentation = "Session id.") final String id,
            @JsonRpcParam(required = true, documentation = "Full prompt text.") final String text,
            @JsonRpcParam(required = true, documentation = "Cursor column into text.") final int cursor) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("session id is required");
        }
        final var runtime = sessions.runtime(id);
        final var engine = completionEngine(runtime.workdir(), runtime.chatSettings());
        final var prompt = text == null ? "" : text;
        final var suggestion = engine.suggest(prompt, Math.min(prompt.length(), Math.max(0, cursor)));
        return List.of(new CompletionSuggestion(
                suggestion.items() == null ? List.of() : suggestion.items(),
                suggestion.replaceStart(),
                suggestion.replaceEnd(),
                suggestion.label() == null ? "" : suggestion.label()));
    }

    @JsonRpc(value = "codeGraph.status", documentation = "Reports the structural code graph freshness and statistics.")
    public Map<String, Object> codeGraphStatus() {
        final var text = codeGraph.freshness(invocationWorkdir);
        final var enabled = !text.startsWith("disabled");
        final var parts = new LinkedHashMap<String, Object>();
        parts.put("enabled", enabled);
        parts.put("status", text);
        return parts;
    }

    @JsonRpc(
            value = "codeGraph.resync",
            documentation = "Forces a resync of the structural code graph. With the "
                    + "optional session project root the refresh targets that per-session root's cached index instead of the "
                    + "default project's persisted+watched graph.")
    public Map<String, String> codeGraphResync(
            @JsonRpcParam(documentation = "Optional session project root to resync (defaults to the process project).")
                    final String root) {
        final var workdir = root == null || root.isBlank()
                ? invocationWorkdir
                : sessionRuntimeFactory.resolveWorkdir(Path.of(root), null);
        return Map.of("status", codeGraph.resync(workdir));
    }

    @JsonRpc(
            value = "codeGraph.resolve",
            documentation = "Searches the structural code graph for a symbol and returns structured hits.")
    public CodeGraphHits codeGraphResolve(
            @JsonRpcParam(required = true, documentation = "Symbol or substring to search (exact fqn or simple name).")
                    final String symbol,
            @JsonRpcParam(documentation = "Max results, default 20.") final Integer max) {
        final var limit = max == null || max < 1 ? 20 : Math.min(max, 200);
        final var workdir = invocationWorkdir;
        final var result = new ArrayList<Map<String, Object>>();
        if (codeGraph.enabled()) {
            codeGraph.ensureRefreshedReady(workdir);
            final var index = codeGraph.queryIndex(workdir);
            if (index != null) {
                final var resolved = index.resolve(symbol);
                if (resolved != null) {
                    result.add(symbolHit(resolved));
                }
                for (final var found : index.find(symbol, null, limit)) {
                    result.add(symbolHit(found));
                }
            }
        }
        return new CodeGraphHits(result);
    }

    private Map<String, Object> symbolHit(final GraphModel.Symbol symbol) {
        final var map = new LinkedHashMap<String, Object>();
        map.put("fqn", symbol.fqn());
        map.put("kind", String.valueOf(symbol.kind()));
        map.put("file", symbol.file());
        map.put("startLine", symbol.startLine());
        map.put("endLine", symbol.endLine());
        map.put("signature", symbol.signature());
        return map;
    }

    // ---- slash completion value sources (mirror the TUI SlashCommandCompleter) ----

    /**
     * Models for the <em>session's</em> effective provider. Passing the session's {@code chatSettings}
     * (rather than none) makes {@link ChatClient#models} resolve and cache the model list against the
     * per-session provider override, so the sidebar's candidates follow a {@code /provider} switch
     * instead of always reflecting the process-default provider.
     */
    private static List<String> modelsOf(final ChatClient chatClient, final ChatSettings settings) {
        if (chatClient == null) {
            return List.of();
        }
        try {
            final var res = chatClient.models(settings);
            final var ids = res.ids();
            return ids == null ? List.of() : ids;
        } catch (final Exception e) {
            return List.of();
        }
    }

    private static List<String> providersOf(final ProviderManager providerManager) {
        if (providerManager == null || providerManager.providers() == null) {
            return List.of();
        }
        try {
            return providerManager.providers().stream()
                    .map(ProviderConfiguration::id)
                    .toList();
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> agentsOf(final AgentProvider agentProvider) {
        if (agentProvider == null || agentProvider.agents() == null) {
            return List.of();
        }
        try {
            return agentProvider.agents().stream().map(AgentConfiguration::name).toList();
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> sessionsOf(final SessionStore sessionStore) {
        if (sessionStore == null) {
            return List.of();
        }
        try {
            final var sessions = sessionStore.list();
            if (sessions == null || sessions.isEmpty()) {
                return List.of();
            }
            return sessions.stream().map(SessionStore.SessionInfo::id).toList();
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> toolsOf(final McpManager mcpManager) {
        if (mcpManager == null) {
            return List.of();
        }
        try {
            final var byServer = mcpManager.toolsByServer();
            if (byServer == null || byServer.isEmpty()) {
                return List.of();
            }
            return byServer.values().stream()
                    .flatMap(List::stream)
                    .map(EmbeddedTool::name)
                    .distinct()
                    .sorted()
                    .toList();
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> serversOf(final McpManager mcpManager) {
        if (mcpManager == null) {
            return List.of();
        }
        try {
            return mcpManager.serverStatuses().stream()
                    .map(status -> status.name())
                    .toList();
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> variantsOf(final ChatClient chatClient, final ChatSettings settings) {
        try {
            if (chatClient == null) {
                return List.of();
            }
            final var variants = chatClient.variants(settings);
            final var hasDefault = chatClient.implicitVariant(settings) != null;
            final var size = (variants == null ? 0 : variants.size()) + (hasDefault ? 1 : 0);
            if (size == 0) {
                return List.of();
            }
            final var names = new ArrayList<String>(size);
            if (hasDefault) {
                names.add("default");
            }
            if (variants != null) {
                for (final var variant : variants) {
                    names.add(variant.name());
                }
            }
            return names;
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private String projectOf(final String id) {
        // cheap first-line peek (file-backed), fallback to the slow full read for other stores
        final var dir = sessionStore.projectDirOf(id);
        return dir == null || dir.isBlank() ? "" : dir;
    }

    private static boolean sameProject(final String a, final String b) {
        if (a.equals(b)) {
            return true;
        }
        final var normalizedA = a.endsWith("/") ? a.substring(0, a.length() - 1) : a;
        final var normalizedB = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        return normalizedA.equals(normalizedB);
    }

    private String title(String id) {
        final var summary = sessionStore.summary(id);
        return summary == null || summary.isBlank() ? id : summarize(summary);
    }

    private static String summarize(final String text) {
        final var nl = text.indexOf('\n');
        final var first = nl < 0 ? text : text.substring(0, nl);
        return first.length() > 80 ? first.substring(0, 80) + "…" : first;
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @JsonModel
    public record InputResult(String disposition) {}

    /** Lightweight session summary for the sessions list. */
    @JsonModel
    public record SessionSummary(String id, String title, long modifiedAt, long sizeBytes, String project) {}

    @JsonModel
    public record ToolListingDto(boolean anyTool, List<ServerDto> servers, int matches) {
        @JsonModel
        public record ServerDto(String server, List<ToolDto> tools) {}

        @JsonModel
        public record ToolDto(String name, String description) {}
    }

    @JsonModel
    public record MemoryDto(
            String global,
            String project,
            String globalPath,
            String projectPath,
            boolean globalExists,
            boolean projectExists) {}

    @JsonModel
    public record ModeChangeDto(String message, String seedMessage, String seedError) {}

    @JsonModel
    public record PermissionsDto(String agentMode, String permissionMode, List<String> sessionGrants) {}

    @JsonModel
    public record CodeGraphHits(List<Map<String, Object>> hits) {}

    @JsonModel
    public record McpDto(String name, String state, String transport, String url) {}

    @JsonModel
    public record SlashCommandCatalog(String name, String command, String usage, String group, String description) {}

    @JsonModel
    public record CompletionSuggestion(List<String> items, int replaceStart, int replaceEnd, String label) {}

    @JsonModel
    public record TodoDto(String content, String status) {}

    @JsonModel
    public record QueuedListDto(List<String> prompts) {}

    @JsonModel
    public record SessionMetadataDto(
            String sessionId,
            String model,
            String flavor,
            String provider,
            String cwd,
            String gitBranch,
            String mode,
            String permissionMode,
            boolean sandboxEnabled,
            List<McpDto> mcpServers,
            List<String> modelCandidates,
            List<String> providerCandidates) {}
}
