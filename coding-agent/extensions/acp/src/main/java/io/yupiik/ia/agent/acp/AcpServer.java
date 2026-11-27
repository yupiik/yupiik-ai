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

import io.yupiik.ai.acp.protocol.*;
import io.yupiik.ai.acp.server.AcpConnection;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.events.ModelSwitchedEvent;
import io.yupiik.ia.agent.core.events.TodoUpdatedEvent;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.ChatRoutes;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.CodexLoginService;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.security.SecretNames;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.session.SessionMetadata;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.*;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.todo.PlanFileService;
import io.yupiik.ia.agent.core.todo.PlanTodoSeeder;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** One stdio connection, owning all of its sessions and client-supplied MCP processes. */
@DefaultScoped
public class AcpServer {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final JsonMapper mapper;
    private final SessionRuntimeFactory factory;
    private final SessionLifecycle lifecycle;
    private final ShellRegistry shells;
    private final SessionStore store;
    private final TurnRunner turns;
    private final ToolRegistry tools;
    private final McpManager mcp;
    private final AcpSessionOptions options;
    private final SecretRedactor redactor;
    private final SecretNames secretNames;
    private final SlashRegistry slashRegistry;
    private final BtwAnswerer btw;
    private final CodexLoginService login;
    private final MavenRepositoryKnowledgeService maven;
    private final ProviderManager providers;
    private final CodingAgentConfiguration configuration;
    private final PlanFileService planFiles;
    private final PlanTodoSeeder plans;
    private final AcpCommand.Conf conf;

    public AcpServer(
            final JsonMapper mapper,
            final SessionRuntimeFactory factory,
            final SessionLifecycle lifecycle,
            final ShellRegistry shells,
            final SessionStore store,
            final TurnRunner turns,
            final ToolRegistry tools,
            final McpManager mcp,
            final AcpSessionOptions options,
            final SecretRedactor redactor,
            final SecretNames secretNames,
            final List<SlashCommandSet> slashCommands,
            final BtwAnswerer btw,
            final CodexLoginService login,
            final MavenRepositoryKnowledgeService maven,
            final ProviderManager providers,
            final CodingAgentConfiguration configuration,
            final PlanFileService planFiles,
            final PlanTodoSeeder plans,
            final AcpCommand.Conf conf) {
        this.mapper = mapper;
        this.factory = factory;
        this.lifecycle = lifecycle;
        this.shells = shells;
        this.store = store;
        this.turns = turns;
        this.tools = tools;
        this.mcp = mcp;
        this.options = options;
        this.redactor = redactor;
        this.secretNames = secretNames;
        // UI overrides share command names but require their own frontend types. Borrow only
        // reviewed core command sets, without mutating the application registry.
        this.slashRegistry = new SlashRegistry(slashCommands.stream()
                .filter(set -> set.getClass().getPackageName().startsWith("io.yupiik.ia.agent.core."))
                .toList());
        this.btw = btw;
        this.login = login;
        this.maven = maven;
        this.providers = providers;
        this.configuration = configuration;
        this.planFiles = planFiles;
        this.plans = plans;
        this.conf = Objects.requireNonNull(conf, "conf");
    }

    public void run(final InputStream input, final PrintStream output, final ChatSettings settings) {
        run(input, output, settings, conf.sandbox());
    }

    public void run(
            final InputStream input,
            final PrintStream output,
            final ChatSettings settings,
            final Boolean sandboxOverride) {
        try (final var connection = new Connection(output, settings, sandboxOverride)) {
            final var hook = new Thread(connection::close, "yca-acp-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                connection.read(input);
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (final IllegalStateException ignored) {
                    // Shutdown already in progress.
                }
            }
        }
    }

    public void onModelSwitched(@OnEvent final ModelSwitchedEvent event) {
        final var env = shells.active(event.eventContext().sessionId());
        if (env != null
                && event.eventContext().subagentId() == null
                && env.frontend() instanceof Connection.State state) {
            state.enqueue(state::refreshOptions);
        }
    }

    public void onTodos(@OnEvent final TodoUpdatedEvent event) {
        final var env = shells.active(event.eventContext().sessionId());
        if (env != null
                && event.eventContext().subagentId() == null
                && env.frontend() instanceof Connection.State state) {
            state.lock.lock();
            try {
                state.plan();
            } finally {
                state.lock.unlock();
            }
        }
    }

    private final class Connection implements AutoCloseable {
        private final PrintStream output;
        private final ChatSettings settings;
        private final Boolean sandboxOverride;
        private final AcpConnection transport;
        private final JsonMapper nullsMapper;
        private final Map<String, State> sessions = new ConcurrentHashMap<>();
        private final Map<String, Pending> pending = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<Void>> lifecycleCommands = new HashMap<>();
        private final AcpSessionHistory history = new AcpSessionHistory(store, mapper, 50);
        private Map<String, Object> capabilities = Map.of();
        private final AtomicLong ids = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean disconnecting = new AtomicBoolean();
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final ExecutorService cleanup = Executors.newVirtualThreadPerTaskExecutor();
        private volatile long shutdownDeadline;
        private final ScheduledThreadPoolExecutor catalogTimer = new ScheduledThreadPoolExecutor(
                1, Thread.ofVirtual().name("yca-acp-timer").factory());
        private final AcpModelCatalog catalog =
                new AcpModelCatalog(options::discover, workers, catalogTimer, provider -> {
                    sessions.values().forEach(state -> state.enqueue(state::refreshOptions));
                });
        private final Semaphore requests = new Semaphore(32);
        private final Semaphore sessionSlots = new Semaphore(64);
        // Includes bounded late terminal/create callbacks, not just unanswered requests.
        private final Semaphore clientSlots = new Semaphore(256);
        private final ReentrantLock sessionLock = new ReentrantLock();
        private volatile InputStream input;
        private boolean initialized;

        private Connection(final PrintStream output, final ChatSettings settings, final Boolean sandboxOverride) {
            this.output = output;
            this.nullsMapper = mapper.as(JsonMapper.Configuring.class)
                    .orElseThrow()
                    .serializeNulls()
                    .build();
            this.transport = new AcpConnection(mapper, nullsMapper, output, () -> !closed.get());
            this.settings = settings;
            this.sandboxOverride = sandboxOverride;
            catalogTimer.setRemoveOnCancelPolicy(true);
        }

        private void read(final InputStream input) {
            this.input = input;
            try {
                transport.read(input, disconnecting::get, message -> {
                    if (message.method() == null) {
                        if (message.id() instanceof String id) {
                            final var waiting = pending.get(id);
                            if (waiting != null) {
                                waiting.reply(message);
                            }
                        }
                        return;
                    }
                    if ("session/cancel".equals(message.method())) {
                        try {
                            final var state = session(
                                    decode(Session.class, message.params()).sessionId());
                            state.cancel();
                        } catch (final RuntimeException ignored) {
                            // cancel is a notification: never reply, including invalid/unknown sessions.
                        }
                        return;
                    }
                    if (!message.hasId()) {
                        return; // Never answer notifications or execute request methods as notifications.
                    }
                    if ("initialize".equals(message.method())) {
                        try {
                            final var params = decode(Initialize.class, message.params());
                            if (initialized
                                    || params.protocolVersion() == null
                                    || !(message.params() instanceof Map<?, ?> values)
                                    || !AcpConnection.integral(values.get("protocolVersion"), 0, 65535)) {
                                throw new IllegalArgumentException();
                            }
                            initialized = true;
                            capabilities = params.clientCapabilities() == null
                                    ? Map.of()
                                    : Collections.unmodifiableMap(new LinkedHashMap<>(params.clientCapabilities()));
                            result(
                                    message.id(),
                                    new Initialized(
                                            1,
                                            new AgentCapabilities(
                                                    true,
                                                    new PromptCapabilities(false, false, false),
                                                    new McpCapabilities(true, true),
                                                    Map.of(
                                                            "list",
                                                            Map.of(),
                                                            "resume",
                                                            Map.of(),
                                                            "close",
                                                            Map.of(),
                                                            "delete",
                                                            Map.of(),
                                                            "additionalDirectories",
                                                            Map.of(),
                                                            "systemPrompt",
                                                            Map.of("mode", Map.of("override", Map.of())))),
                                            authenticationMethods(),
                                            new Info("yca", "Yupiik Coding Agent", "1.0.0")));
                        } catch (final RuntimeException e) {
                            error(message.id(), -32602, "Invalid initialization parameters");
                        }
                    } else if (!initialized) {
                        error(message.id(), -32000, "Initialize the connection first");
                    } else if (!requests.tryAcquire()) {
                        error(message.id(), -32000, "Too many concurrent requests");
                    } else if ("session/prompt".equals(message.method())
                            || "session/set_mode".equals(message.method())
                            || "session/set_model".equals(message.method())
                            || "session/set_config_option".equals(message.method())) {
                        // Only admission happens on the reader. Hooks, settings changes and prompt startup are
                        // serialized per session off-reader, preserving config -> prompt wire ordering.
                        try {
                            final var state = session(
                                    decode(Session.class, message.params()).sessionId());
                            final var cancelEpoch = state.cancelEpoch.get();
                            state.enqueue(() -> {
                                try {
                                    dispatch(message, cancelEpoch);
                                } finally {
                                    requests.release();
                                }
                            });
                        } catch (final AcpSessionHistory.UnknownSessionException e) {
                            requests.release();
                            error(message.id(), -32002, "Unknown session");
                        } catch (final RuntimeException e) {
                            requests.release();
                            error(message.id(), -32602, "Invalid parameters");
                        }
                    } else if (List.of("session/load", "session/resume", "session/close", "session/delete")
                            .contains(message.method())) {
                        enqueueLifecycle(message);
                    } else {
                        workers.execute(() -> {
                            try {
                                dispatch(message);
                            } finally {
                                requests.release();
                            }
                        });
                    }
                });
            } catch (final IOException e) {
                System.err.println("ACP input closed or invalid framing");
            }
        }

        private void dispatch(final Envelope request) {
            dispatch(request, -1);
        }

        private List<AuthMethod> authenticationMethods() {
            if (login == null
                    || providers == null
                    || !(capabilities.get("auth") instanceof Map<?, ?> auth)
                    || !Boolean.TRUE.equals(auth.get("terminal"))) {
                return List.of();
            }
            final var provider = providers.provider(options.providerId(settings));
            if (provider == null
                    || (provider.api() != ApiFlavor.CODEX
                            && ChatRoutes.detectFlavor(provider.baseUrl()) != ApiFlavor.CODEX)) {
                return List.of();
            }
            return List.of(new AuthMethod(
                    "codex-terminal",
                    "Sign in to Codex",
                    "Run the configured YCA invocation in a private terminal, then reconnect.",
                    "terminal",
                    List.of("--login", "true")));
        }

        private void dispatch(final Envelope request, final long cancelEpoch) {
            try {
                switch (request.method()) {
                    case "session/new" -> {
                        validateLifecycle(request);
                        final var created = open(null, decode(NewSession.class, request.params()), request.params());
                        final var state = sessions.get(created.sessionId());
                        result(request.id(), created);
                        if (state != null) {
                            state.ready.complete(null);
                            state.initialUpdates();
                            state.discover();
                        }
                    }
                    case "session/load", "session/resume" -> {
                        validateLifecycle(request);
                        final var params = decode(LoadSession.class, request.params());
                        final var id = AcpSessionHistory.validateSessionId(params.sessionId());
                        history.requireKnown(id);
                        if (sessions.containsKey(id)) {
                            throw new IllegalArgumentException("Session already active");
                        }
                        final var created =
                                open(id, new NewSession(params.cwd(), params.mcpServers()), request.params());
                        final var state = session(id);
                        boolean loaded = false;
                        try {
                            if ("session/load".equals(request.method())) {
                                final var replay = history.replay(
                                        id,
                                        fields -> send(Envelope.of("2.0", null, "session/update", fields, null, null)));
                                if (!replay.issues().isEmpty()) {
                                    state.publicNotice(
                                            "warning",
                                            "Some stored internal or unsupported history is unavailable for replay.");
                                }
                            }
                            result(request.id(), new Loaded(created.modes(), created.configOptions()));
                            loaded = true;
                        } finally {
                            state.ready.complete(null);
                            if (!loaded) {
                                closeSession(id, false);
                            }
                        }
                        state.initialUpdates();
                        state.discover();
                    }
                    case "session/list" -> {
                        final var params = decode(ListSessions.class, request.params());
                        result(request.id(), history.list(params.cwd(), params.cursor()));
                    }
                    case "session/close", "session/delete" -> {
                        final var id = AcpSessionHistory.validateSessionId(
                                decode(Session.class, request.params()).sessionId());
                        closeSession(id, "session/delete".equals(request.method()));
                        result(request.id(), new Empty());
                    }
                    case "session/prompt" -> prompt(request.id(), decode(Prompt.class, request.params()), cancelEpoch);
                    case "session/set_mode" -> {
                        final var params = decode(SetMode.class, request.params());
                        final var state = session(params.sessionId());
                        state.configure("mode", params.modeId());
                        result(request.id(), new Empty());
                    }
                    case "session/set_config_option" -> {
                        final var params = decode(SetConfig.class, request.params());
                        final var state = session(params.sessionId());
                        final var snapshot = state.configure(params.configId(), params.value());
                        result(request.id(), new ConfigResult(snapshot));
                    }
                    case "session/set_model" -> {
                        final var params = decode(SetModel.class, request.params());
                        session(params.sessionId()).configure("model", params.modelId());
                        result(request.id(), new Empty());
                    }
                    default -> error(request.id(), -32601, "Method not found");
                }
            } catch (final AcpSessionHistory.UnknownSessionException e) {
                error(request.id(), -32002, "Unknown session");
            } catch (final AcpSessionOptions.InvalidStoredSelectionException e) {
                error(request.id(), -32602, e.getMessage());
            } catch (final IllegalArgumentException | NullPointerException e) {
                error(request.id(), -32602, "Invalid parameters, unsupported lifecycle fields, or session busy");
            } catch (final Exception e) {
                // Launch environments and untrusted server diagnostics must never enter protocol/history/logs.
                error(
                        request.id(),
                        -32603,
                        "Agent operation failed; check provider configuration and MCP availability");
            }
        }

        private void validateLifecycle(final Envelope request) {
            if (!(request.params() instanceof Map<?, ?> fields) || !(fields.get("cwd") instanceof String)) {
                throw new IllegalArgumentException("Invalid lifecycle parameters");
            }
            if (fields.get("mcpServers") == null && "session/resume".equals(request.method())) {
                return;
            }
            if (!(fields.get("mcpServers") instanceof List<?> servers)) {
                throw new IllegalArgumentException("Invalid MCP server list");
            }
            for (final var server : servers) {
                if (!(server instanceof Map<?, ?> descriptor) || !(descriptor.get("name") instanceof String)) {
                    throw new IllegalArgumentException("Invalid MCP descriptor");
                }
                for (final var key : List.of("type", "command", "url")) {
                    if (descriptor.get(key) != null && !(descriptor.get(key) instanceof String)) {
                        throw new IllegalArgumentException("Invalid MCP descriptor");
                    }
                }
                if (descriptor.get("args") != null
                        && (!(descriptor.get("args") instanceof List<?> args)
                                || args.stream().anyMatch(arg -> !(arg instanceof String)))) {
                    throw new IllegalArgumentException("Invalid MCP arguments");
                }
                for (final var key : List.of("env", "headers")) {
                    if (descriptor.get(key) == null) {
                        continue;
                    }
                    if (!(descriptor.get(key) instanceof List<?> entries)
                            || entries.stream()
                                    .anyMatch(entry -> !(entry instanceof Map<?, ?> pair)
                                            || !(pair.get("name") instanceof String)
                                            || !(pair.get("value") instanceof String))) {
                        throw new IllegalArgumentException("Invalid MCP descriptor");
                    }
                }
            }
        }

        private void enqueueLifecycle(final Envelope message) {
            try {
                final var id = AcpSessionHistory.validateSessionId(
                        decode(Session.class, message.params()).sessionId());
                sessionLock.lock();
                try {
                    final var previous = lifecycleCommands.getOrDefault(id, CompletableFuture.completedFuture(null));
                    final var next = previous.handle((unused, failure) -> null)
                            .thenRunAsync(
                                    () -> {
                                        try {
                                            dispatch(message);
                                        } finally {
                                            requests.release();
                                        }
                                    },
                                    workers);
                    lifecycleCommands.put(id, next);
                    next.whenComplete((unused, failure) -> {
                        sessionLock.lock();
                        try {
                            lifecycleCommands.remove(id, next);
                        } finally {
                            sessionLock.unlock();
                        }
                    });
                } finally {
                    sessionLock.unlock();
                }
            } catch (final RuntimeException invalid) {
                requests.release();
                error(message.id(), -32602, "Invalid lifecycle parameters");
            }
        }

        private void closeSession(final String id, final boolean delete) throws Exception {
            final var state = sessions.get(id);
            if (state != null) {
                state.closing.set(true);
                state.clientTools.close();
                state.cancel();
                state.closeMcp();
                state.drainAndClose(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                if (!state.released.isDone()) {
                    // Keep the closing runtime reserved. A retry may delete only after it really stops writing.
                    throw new IllegalStateException("Session is still draining");
                }
            }
            if (delete) {
                store.delete(id); // hard delete, after the last owned runtime has stopped
            }
        }

        private Created open(final String id, final NewSession params, final Object raw) throws Exception {
            if (closed.get() || disconnecting.get()) {
                throw new IllegalArgumentException("Connection closed");
            }
            final SessionMetadata preset;
            final ChatSettings selection;
            try {
                preset = id == null ? null : store.metadata(id);
                selection = preset == null ? options.pin(settings) : options.restore(preset);
            } catch (final RuntimeException invalid) {
                throw new AcpSessionOptions.InvalidStoredSelectionException();
            }
            final var setup = options.setup(raw, preset);
            if (!setup.additionalDirectories().isEmpty() || setup.systemPrompt() != null) {
                factory.validateSessionContext(
                        selection, setup.cwd(), setup.additionalDirectories(), setup.systemPrompt());
            }
            final var servers = sessionServers(params);
            if (!sessionSlots.tryAcquire()) {
                throw new IllegalArgumentException("Too many sessions");
            }
            boolean published = false;
            try {
                final var created = createReserved(id, setup, preset, selection, servers);
                published = true;
                return created;
            } finally {
                if (!published) {
                    sessionSlots.release();
                }
            }
        }

        private List<McpManager.SessionServer> sessionServers(final NewSession params) {
            if (params.mcpServers() == null) {
                return List.of();
            }
            final var servers = new ArrayList<McpManager.SessionServer>();
            for (final var server : params.mcpServers()) {
                if (server == null) {
                    throw new IllegalArgumentException("Invalid MCP server");
                }
                if ("http".equals(server.type()) || "sse".equals(server.type())) {
                    if (server.command() != null || server.args() != null || server.env() != null) {
                        throw new IllegalArgumentException("Invalid remote MCP descriptor");
                    }
                    final var headers = new LinkedHashMap<String, String>();
                    final var headerNames = new HashSet<String>();
                    if (server.headers() != null) {
                        for (final var header : server.headers()) {
                            if (header == null
                                    || header.name() == null
                                    || header.value() == null
                                    || !headerNames.add(header.name().toLowerCase(Locale.ROOT))) {
                                throw new IllegalArgumentException("Invalid or duplicate MCP header");
                            }
                            headers.put(header.name(), header.value());
                        }
                    }
                    servers.add(new McpManager.RemoteServer(
                            server.name(),
                            "http".equals(server.type())
                                    ? McpManager.RemoteTransport.HTTP
                                    : McpManager.RemoteTransport.SSE,
                            server.url(),
                            headers));
                    continue;
                }
                if ((server.type() != null && !"stdio".equals(server.type()))
                        || server.command() == null
                        || server.command().isBlank()
                        || server.url() != null
                        || server.headers() != null) {
                    throw new IllegalArgumentException("Invalid stdio MCP descriptor");
                }
                final var command = new ArrayList<String>();
                command.add(server.command());
                if (server.args() != null) {
                    command.addAll(server.args());
                }
                final var env = new ArrayList<String>();
                final var names = new HashSet<String>();
                if (server.env() != null) {
                    for (final var variable : server.env()) {
                        if (variable == null
                                || variable.name() == null
                                || variable.name().isBlank()
                                || variable.name().contains("=")
                                || variable.name().contains("\0")
                                || variable.value() == null
                                || variable.value().contains("\0")
                                || !names.add(variable.name())) {
                            throw new IllegalArgumentException();
                        }
                        env.add(variable.name() + "=" + variable.value());
                    }
                }
                servers.add(new McpManager.StdioServer(server.name(), command, env));
            }
            return List.copyOf(servers);
        }

        private Created createReserved(
                final String restoredId,
                final AcpSessionOptions.Setup setup,
                final SessionMetadata preset,
                final ChatSettings selection,
                final List<McpManager.SessionServer> servers)
                throws Exception {
            final var cwd = setup.cwd();
            final var owned = mcp.openSession(servers, cwd);
            String id = restoredId;
            SessionRuntime runtime = null;
            State state = null;
            AcpClientTools clientTools = null;
            try {
                mcp.start();
                if (id == null) {
                    id = lifecycle.resolveSessionId(null, null);
                    store.markProject(id, cwd.toString());
                }
                final var holder = new AtomicReference<State>();
                clientTools = new AcpClientTools(
                        id,
                        cwd,
                        capabilities,
                        Duration.ofMinutes(2),
                        Duration.ofSeconds(2),
                        (method, fields, timeout, cancelled, late) ->
                                requestClient(holder.get(), method, fields, timeout, cancelled, late),
                        (context, terminalId) ->
                                holder.get().terminal(context.subagentId(), context.ordinal(), terminalId));
                clientTools.configureAdditionalDirectories(setup.additionalDirectories());
                final var registry = tools.withTools(owned.tools()).withTools(clientTools.tools());
                runtime = factory.create(id, id, registry, cwd, sandboxOverride);
                runtime.updateChatSettings(selection);
                runtime.configureSessionContext(setup.additionalDirectories(), setup.systemPrompt());
                if (preset != null) {
                    runtime.permissions().setMode("plan".equals(preset.mode()) ? AgentMode.PLAN : AgentMode.BUILD);
                }
                runtime.permissions().setPermissionMode(PermissionMode.ASK);
                state = new State(id, runtime, owned, clientTools, setup.systemPrompt());
                if (preset != null) {
                    state.env.session().activeAgent(preset.agent());
                }
                holder.set(state);
                lifecycle.installGates(state.approvalGate(), state.feedbackGate(), runtime);
                runtime.permissions().setPermissionMode(PermissionMode.ASK);
                sessionLock.lock();
                try {
                    if (closed.get() || disconnecting.get() || sessions.containsKey(id)) {
                        throw new IllegalStateException("Connection closed");
                    }
                    shells.register(id, state.env);
                    sessions.put(id, state);
                    state.slotOwned.set(true);
                } finally {
                    sessionLock.unlock();
                }
                state.persistSelection();
                return new Created(
                        id,
                        new Modes(
                                state.describedMode,
                                List.of(
                                        new Mode("plan", "Plan", "Read-only planning"),
                                        new Mode("build", "Build", "Implement with tool approvals"))),
                        state.advertised);
            } catch (final Exception e) {
                if (state != null) {
                    state.slotOwned.set(false); // open() still owns rollback of this capacity reservation.
                    state.close(false);
                } else {
                    if (clientTools != null) {
                        clientTools.close();
                    }
                    closeMcp(owned);
                    if (runtime != null) {
                        runtime.close();
                    }
                    if (runtime != null) {
                        lifecycle.release(null, runtime);
                    }
                }
                if (restoredId == null && id != null) {
                    store.delete(id); // creation rollback, including the pre-factory root marker
                }
                throw e;
            }
        }

        private void prompt(final Object requestId, final Prompt params, final long cancelEpoch) throws Exception {
            final var state = session(params.sessionId());
            if (params.prompt() == null) {
                throw new IllegalArgumentException();
            }
            final var text = new StringBuilder();
            for (final var block : params.prompt()) {
                if ("text".equals(block.type()) && block.text() != null) {
                    text.append(block.text());
                } else if ("resource_link".equals(block.type()) && block.uri() != null && block.name() != null) {
                    // Resource links are context references, never an instruction to bypass file permissions.
                    text.append("\nResource: ")
                            .append(block.name() == null ? "" : block.name())
                            .append(" <")
                            .append(block.uri())
                            .append(">\n");
                    if (block.description() != null) {
                        text.append(block.description()).append('\n');
                    }
                } else {
                    throw new IllegalArgumentException();
                }
            }
            final var textual = params.prompt().stream().allMatch(block -> "text".equals(block.type()));
            if (!textual && text.toString().stripLeading().startsWith("/")) {
                throw new IllegalArgumentException("Command attachments are unsupported");
            }
            state.lock.lock();
            try {
                if (cancelEpoch != state.cancelEpoch.get()) {
                    result(requestId, new PromptResult(StopReason.cancelled));
                    return;
                }
                if (state.active || closed.get() || state.disposed.get() || state.closing.get()) {
                    throw new IllegalArgumentException();
                }
                state.active = true;
                state.finished = new CompletableFuture<>();
                state.cancelled.set(false);
                state.stop = "end_turn";
                state.failure = false;
                state.failureMessage = null;
                state.turn++;
                state.closeAfterPrompt = false;
                state.buffered.clear();
            } finally {
                state.lock.unlock();
            }
            try {
                final var command = state.slash.dispatch(text.toString(), state.env);
                final CompletionStage<Boolean> operation;
                if (command.isPresent()) {
                    // Keep the returned operation until the actual work settles. Cancelling a wrapper future
                    // would falsely report it drained while e.g. BtwAnswerer still owns the provider request.
                    operation = command.get().thenApply(outcome -> {
                        state.lock.lock();
                        try {
                            if (!state.cancelled.get()) {
                                outcome.applyTo(state);
                                state.closeAfterPrompt = outcome instanceof ShellOutcome.Quit;
                                if (state.closeAfterPrompt) {
                                    state.closing.set(true);
                                }
                                state.refreshOptions();
                                state.plan();
                            }
                            return true;
                        } finally {
                            state.lock.unlock();
                        }
                    });
                } else if (text.isEmpty()) {
                    operation = CompletableFuture.completedFuture(true);
                } else {
                    operation = turns.submit(new TurnRunner.TurnRequest(
                            state.runtime.loop(),
                            state.env,
                            state.id,
                            state.env.session().activeAgent(),
                            text.toString(),
                            () -> store.load(state.id),
                            () -> state.cancelled.get() || cancelEpoch != state.cancelEpoch.get()));
                }
                operation.whenComplete((ran, failure) -> {
                    state.lock.lock();
                    try {
                        try {
                            state.persistSelection(); // Includes failed/cancelled slash work after its hooks settle.
                        } catch (final RuntimeException persistenceFailure) {
                            state.failure = true;
                            state.failureMessage = "Session preset could not be persisted";
                        }
                        if (failure != null && !state.cancelled.get()) {
                            state.error("Agent turn failed; check provider configuration and availability");
                        }
                        if (state.cancelled.get()) {
                            state.buffered.clear();
                        } else {
                            state.flushText();
                        }
                        state.updates.endTurn(null, state.cancelled.get());
                        state.runningToolIds.clear();
                        state.legacyTools.clear();
                        if (state.cancelled.get()) {
                            result(requestId, new PromptResult(StopReason.cancelled));
                        } else if (command.isEmpty() && state.runtime.loop().iterationLimitReached()) {
                            result(requestId, new PromptResult(StopReason.max_turn_requests));
                        } else if (failure != null || !Boolean.TRUE.equals(ran) || state.failure) {
                            error(
                                    requestId,
                                    -32603,
                                    state.failureMessage == null
                                            ? "Agent turn could not complete"
                                            : state.failureMessage);
                        } else {
                            result(requestId, new PromptResult(StopReason.valueOf(state.stop)));
                        }
                    } finally {
                        state.active = false;
                        state.finished.complete(null);
                        state.lock.unlock();
                    }
                    if (state.closeAfterPrompt) {
                        workers.execute(() -> {
                            try {
                                state.clientTools.close();
                                state.cancel();
                                state.closeMcp();
                                state.drainAndClose(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                            } catch (final Exception ignored) {
                                // A still-draining session stays reserved; never close another session/server.
                            }
                        });
                    }
                });
            } catch (final RuntimeException e) {
                state.lock.lock();
                try {
                    state.active = false;
                    state.finished.complete(null);
                } finally {
                    state.lock.unlock();
                }
                throw e;
            }
        }

        private State session(final String id) {
            AcpSessionHistory.validateSessionId(id);
            final var state = sessions.get(id);
            if (state == null || state.closing.get()) {
                throw new AcpSessionHistory.UnknownSessionException();
            }
            return state;
        }

        /** The only agent->client request transport; all callers borrow its lifecycle and timers. */
        private CompletionStage<Object> requestClient(
                final State state,
                final String method,
                final Map<String, Object> params,
                final Duration timeout,
                final BooleanSupplier cancelled,
                final Consumer<Object> lateResult) {
            final var cleaning = "terminal/kill".equals(method) || "terminal/release".equals(method);
            if (state == null
                    || !state.id.equals(params.get("sessionId"))
                    || closed.get()
                    || (!cleaning && disconnecting.get())
                    || (!cleaning
                            && (sessions.get(state.id) != state
                                    || state.closing.get()
                                    || state.disposed.get()
                                    || cancelled.getAsBoolean()))) {
                return CompletableFuture.failedFuture(new CancellationException("ACP session unavailable"));
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero() || !clientSlots.tryAcquire()) {
                return CompletableFuture.failedFuture(new IllegalStateException("ACP client request unavailable"));
            }
            final var id = "client-" + ids.incrementAndGet();
            final var waiting =
                    new Pending(id, state, method, cancelled, lateResult, System.nanoTime() + timeout.toNanos());
            pending.put(id, waiting);
            try {
                waiting.monitor = catalogTimer.scheduleWithFixedDelay(waiting::check, 0, 25, TimeUnit.MILLISECONDS);
                waiting.answer.whenComplete((value, failure) -> waiting.completed(failure));
                // Serialize scope validation + request publication with immediate session cancellation.
                state.lock.lock();
                try {
                    if (closed.get()
                            || (!cleaning
                                    && (state.cancelled.get() || state.closing.get() || cancelled.getAsBoolean()))) {
                        waiting.answer.cancel(true);
                    } else {
                        send(Envelope.of("2.0", id, method, params, null, null));
                    }
                } finally {
                    state.lock.unlock();
                }
            } catch (final RuntimeException failure) {
                waiting.answer.completeExceptionally(new IOException("ACP client request unavailable"));
                waiting.evict();
            }
            return waiting.answer;
        }

        private final class Pending {
            private final String id;
            private final State state;
            private final String method;
            private final BooleanSupplier cancelled;
            private final Consumer<Object> lateResult;
            private final long deadline;
            private final CompletableFuture<Object> answer = new CompletableFuture<>();
            private final AtomicBoolean evicted = new AtomicBoolean();
            private final AtomicBoolean received = new AtomicBoolean();
            private final AtomicBoolean completing = new AtomicBoolean();
            private volatile ScheduledFuture<?> monitor;
            private volatile ScheduledFuture<?> expiry;

            private Pending(
                    final String id,
                    final State state,
                    final String method,
                    final BooleanSupplier cancelled,
                    final Consumer<Object> lateResult,
                    final long deadline) {
                this.id = id;
                this.state = state;
                this.method = method;
                this.cancelled = cancelled;
                this.lateResult = lateResult;
                this.deadline = deadline;
            }

            private void check() {
                if (answer.isDone() || received.get()) {
                    return;
                }
                try {
                    if (closed.get() || cancelled.getAsBoolean()) {
                        abort();
                    } else if (System.nanoTime() - deadline >= 0) {
                        completeOffReader(() -> answer.completeExceptionally(
                                new TimeoutException("ACP client request deadline exceeded")));
                    }
                } catch (final RuntimeException invalid) {
                    completeOffReader(
                            () -> answer.completeExceptionally(new IOException("ACP client request unavailable")));
                }
            }

            private void abort() {
                completeOffReader(() -> answer.cancel(true));
            }

            private void completeOffReader(final Runnable operation) {
                if (completing.compareAndSet(false, true)) {
                    try {
                        workers.execute(operation);
                    } catch (final RejectedExecutionException disconnected) {
                        answer.cancel(true);
                        evict();
                    }
                }
            }

            private void completed(final Throwable failure) {
                final var task = monitor;
                if (task != null) {
                    task.cancel(false);
                }
                if (failure != null && "terminal/create".equals(method) && !received.get() && !closed.get()) {
                    try {
                        expiry = catalogTimer.schedule(this::evict, 2, TimeUnit.SECONDS);
                        if (evicted.get()) {
                            expiry.cancel(false);
                        }
                    } catch (final RejectedExecutionException disconnected) {
                        evict();
                    }
                } else {
                    evict();
                }
            }

            private void reply(final Envelope response) {
                if (!received.compareAndSet(false, true)) {
                    return;
                }
                try {
                    workers.execute(() -> deliver(response));
                } catch (final RejectedExecutionException disconnected) {
                    answer.cancel(true);
                    evict();
                }
            }

            private void deliver(final Envelope response) {
                if (response.error() != null) {
                    // Client errors may contain credentials or arbitrary data. Never propagate their text/cause.
                    answer.completeExceptionally(new IOException("ACP client rejected the request"));
                } else if (!answer.complete(response.result()) && "terminal/create".equals(method)) {
                    try {
                        lateResult.accept(response.result());
                    } catch (final RuntimeException ignored) {
                        // Best-effort cleanup never revives a cancelled operation.
                    }
                }
                evict();
            }

            private void evict() {
                if (evicted.compareAndSet(false, true)) {
                    pending.remove(id, this);
                    final var task = monitor;
                    if (task != null) {
                        task.cancel(false);
                    }
                    final var late = expiry;
                    if (late != null) {
                        late.cancel(false);
                    }
                    clientSlots.release();
                }
            }
        }

        private Map<String, Object> fields(final Object value) {
            final var decoded = mapper.fromString(Object.class, mapper.toString(value));
            if (!(decoded instanceof Map<?, ?>)) {
                throw new IllegalArgumentException();
            }
            @SuppressWarnings("unchecked")
            final var fields = (Map<String, Object>) decoded;
            return fields;
        }

        private <T> T decode(final Class<T> type, final Object value) {
            if (value == null) {
                throw new IllegalArgumentException();
            }
            // A nested map uses the generic JSON object codec. Root homogeneous maps instead
            // infer a scalar codec, which quotes parsed BigDecimals (e.g. protocolVersion alone).
            final var array = mapper.toString(List.of(value));
            return mapper.fromString(type, array.substring(1, array.length() - 1));
        }

        private void result(final Object id, final Object result) {
            send(Envelope.of("2.0", id, null, null, result, null));
        }

        private void error(final Object id, final int code, final String message) {
            send(new ErrorResponse("2.0", id, RpcError.of(code, message)));
        }

        private void send(final Object message) {
            if (!transport.send(message)) {
                Thread.ofVirtual().name("acp-broken-pipe").start(() -> {
                    try {
                        if (input != null) {
                            input.close();
                        }
                    } catch (final IOException ignored) {
                        // Reader will observe the closed connection.
                    }
                    close();
                });
            }
        }

        @Override
        public void close() {
            final List<State> snapshot;
            sessionLock.lock();
            try {
                if (!disconnecting.compareAndSet(false, true)) {
                    return;
                }
                shutdownDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                snapshot = List.copyOf(sessions.values());
                sessions.clear();
            } finally {
                sessionLock.unlock();
            }
            snapshot.forEach(state -> state.clientTools.close());
            closed.set(true); // best-effort terminal cleanup was sent before closing its channel
            pending.values().forEach(p -> {
                p.answer.completeExceptionally(new IOException("Disconnected"));
                p.evict();
            });
            // Stop creation/configuration immediately, not after MCP or provider shutdown.
            workers.shutdownNow();
            catalog.close();
            catalogTimer.shutdownNow();
            snapshot.forEach(State::cancel);
            snapshot.forEach(state -> {
                // SessionTools.close is serial. Each client is independently owned here, so
                // a stuck transport must not prevent the other transports from starting close.
                state.closeMcp();
                cleanup.execute(() -> state.drainAndClose(shutdownDeadline));
            });
            cleanup.shutdown();
            try {
                cleanup.awaitTermination(Math.max(0, shutdownDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                workers.awaitTermination(Math.max(0, shutdownDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                cleanup.shutdownNow();
                nullsMapper.close();
            }
        }

        private void closeMcp(final McpManager.SessionTools owned) {
            for (final var client : owned.clients()) {
                try {
                    cleanup.execute(client::close);
                } catch (final RejectedExecutionException rejected) {
                    // A creation worker can publish its resources after the deadline. Its
                    // rollback still owns every client, independently of any stuck closer.
                    Thread.ofVirtual().name("yca-acp-late-mcp-close").start(client::close);
                }
            }
        }

        private final class State implements ShellFrontend, ShellIO {
            private final String id;
            private final SessionRuntime runtime;
            private final McpManager.SessionTools owned;
            private final AcpClientTools clientTools;
            private final SessionMetadata.Instructions systemPrompt;
            private final ReentrantLock metadataLock = new ReentrantLock();
            private SessionMetadata persistedSelection;
            private final AcpElicitation elicitation;
            private final AcpUpdates updates;
            private final AcpSlashCommands slash;
            private final ShellEnv env;
            private final ReentrantLock lock = new ReentrantLock();
            private final Map<Long, String> runningToolIds = new ConcurrentHashMap<>();
            private final AtomicBoolean cancelled = new AtomicBoolean();
            private final AtomicBoolean disposed = new AtomicBoolean();
            private final CompletableFuture<Void> released = new CompletableFuture<>();
            private final AtomicBoolean closing = new AtomicBoolean();
            private final AtomicBoolean slotOwned = new AtomicBoolean();
            private final AtomicBoolean mcpClosed = new AtomicBoolean();
            private final AtomicLong cancelEpoch = new AtomicLong();
            private final ReentrantLock commandLock = new ReentrantLock();
            private final CompletableFuture<Void> ready = new CompletableFuture<>();
            private CompletableFuture<Void> commands = ready;
            private volatile List<ConfigOption> advertised;
            private ChatSettings describedSettings;
            private String describedMode;
            private List<String> describedModels;
            private final Map<String, LinkedHashSet<String>> knownModels = new HashMap<>();
            private volatile CompletableFuture<Void> finished = CompletableFuture.completedFuture(null);
            private volatile boolean active;
            private long turn;
            private String stop = "end_turn";
            private volatile boolean failure;
            private String failureMessage;
            private volatile boolean closeAfterPrompt;
            // Only one prompt can own work. These are the actual handles, not cancellable stage views.
            private CodexLoginService.LoginAttempt loginAttempt;
            private MavenRepositoryKnowledgeService.IndexRun indexRun;
            private Thread preparingIndex;
            private final List<ChunkUpdate> buffered = new ArrayList<>();
            private final Map<String, String> legacyTools = new HashMap<>();
            private List<TodoItem> lastPlan;
            private volatile boolean thinking = true;
            private volatile ToolOutputVisibility toolOutput = ToolOutputVisibility.COLLAPSED;

            private State(
                    final String id,
                    final SessionRuntime runtime,
                    final McpManager.SessionTools owned,
                    final AcpClientTools clientTools,
                    final SessionMetadata.Instructions systemPrompt) {
                this.id = id;
                this.runtime = runtime;
                this.owned = owned;
                this.clientTools = clientTools;
                this.systemPrompt = systemPrompt;
                updates = new AcpUpdates(id, mapper, capabilities, this::projectUpdate);
                elicitation = new AcpElicitation(
                                id,
                                null,
                                AcpElicitation.supportsForm(capabilities),
                                secretNames,
                                Duration.ofMinutes(2),
                                (method, form, timeout) -> {
                                    final var source = requestClient(
                                                    this, method, fields(form), timeout, cancelled::get, ignored -> {})
                                            .toCompletableFuture();
                                    final var result = new CompletableFuture<AcpElicitation.Reply>();
                                    source.whenComplete((value, failure) -> {
                                        if (failure != null) {
                                            result.completeExceptionally(failure);
                                        } else {
                                            try {
                                                result.complete(decode(AcpElicitation.Reply.class, value));
                                            } catch (final RuntimeException malformed) {
                                                result.completeExceptionally(
                                                        new IOException("Invalid ACP elicitation response"));
                                            }
                                        }
                                    });
                                    result.whenComplete((value, failure) -> {
                                        if (result.isCancelled()) {
                                            source.cancel(true);
                                        }
                                    });
                                    return result;
                                })
                        .withSensitiveInputs(conf.elicitationSensitiveMarkers(), conf.elicitationSensitiveWords());
                env = new ShellEnv(
                        this, this, new ShellSession().sessionId(id).runtime(runtime), new ConversationTranscript());
                slash = new AcpSlashCommands(
                        slashRegistry,
                        new AcpSlashCommands.Routes(
                                false,
                                true,
                                true,
                                false,
                                btw == null ? null : this::sideQuestion,
                                this::provider,
                                login == null ? null : this::codex,
                                maven == null ? null : this::refreshMaven,
                                this::inspectTools,
                                this::inspectMcp,
                                this::editPlan));
                describedSettings = runtime.chatSettings();
                describedMode = options.mode(runtime);
                describedModels = availableModels(describedSettings);
                advertised = options.describe(describedSettings, describedMode, describedModels);
            }

            private void enqueue(final Runnable command) {
                commandLock.lock();
                try {
                    commands = commands.handle((ignored, failure) -> null)
                            .thenRunAsync(
                                    () -> {
                                        command.run(); // dispatch must answer queued requests even after session close
                                    },
                                    workers);
                } finally {
                    commandLock.unlock();
                }
            }

            private List<ConfigOption> configure(final String id, final Object value) {
                // The command queue is the mutation boundary; never hold the event lock while hooks run.
                if (closed.get() || disposed.get() || closing.get()) {
                    throw new IllegalStateException("Session closed");
                }
                options.apply(this.id, runtime, advertised, id, value);
                return refreshOptions(); // Read back the actual settings, including a hook veto.
            }

            private void discover() {
                if (!closed.get() && !disposed.get()) {
                    catalog.request(options.providerId(runtime.chatSettings()));
                }
            }

            private List<String> availableModels(final ChatSettings settings) {
                final var provider = options.providerId(settings);
                final var known = knownModels.computeIfAbsent(provider, ignored -> new LinkedHashSet<>());
                // Keep concrete CLI/agent selections available after selecting another model or provider.
                if (settings.model() != null) {
                    known.add(settings.model());
                }
                known.addAll(catalog.models(provider));
                return List.copyOf(known);
            }

            private List<ConfigOption> refreshOptions() {
                if (closed.get() || disposed.get()) {
                    return advertised;
                }
                persistSelection();
                final List<ConfigOption> snapshot;
                lock.lock();
                try {
                    if (closed.get() || disposed.get() || !ready.isDone()) {
                        return advertised;
                    }
                    final var settings = runtime.chatSettings();
                    final var mode = options.mode(runtime);
                    final var models = availableModels(settings);
                    if (!Objects.equals(settings, describedSettings)
                            || !mode.equals(describedMode)
                            || !models.equals(describedModels)) {
                        final var next = options.describe(settings, mode, models);
                        if (!mode.equals(describedMode)) {
                            // Mode changes from either legacy requests, selectors or the agent share one projection.
                            // Permission observers may run hooks: never call them while holding the event lock.
                            enqueue(() -> {
                                if (!disposed.get()) {
                                    runtime.permissions().setPermissionMode(PermissionMode.ASK);
                                }
                            });
                            update(new ModeUpdate("current_mode_update", mode));
                        }
                        describedSettings = settings;
                        describedMode = mode;
                        describedModels = models;
                        if (!next.equals(advertised)) {
                            advertised = next;
                            update(new ConfigUpdate("config_option_update", next));
                        }
                    }
                    snapshot = advertised;
                } finally {
                    lock.unlock();
                }
                discover();
                return snapshot;
            }

            private void persistSelection() {
                metadataLock.lock();
                try {
                    if (!slotOwned.get()) {
                        return;
                    }
                    final var actual = options.snapshot(runtime, env.session().activeAgent(), systemPrompt);
                    if (!actual.equals(persistedSelection) && store.metadata(id, actual)) {
                        persistedSelection = actual;
                    }
                } finally {
                    metadataLock.unlock();
                }
            }

            private void cancel() {
                // Admission check and reset must be atomic with cancellation. Otherwise a cancel
                // between queue admission and prompt startup can be erased by cancelled.set(false).
                lock.lock();
                try {
                    cancelEpoch.incrementAndGet();
                    cancelled.set(true);
                    if (loginAttempt != null) {
                        loginAttempt.cancel();
                    }
                    if (indexRun != null) {
                        indexRun.cancel();
                    }
                    if (preparingIndex != null) {
                        preparingIndex.interrupt();
                    }
                } finally {
                    lock.unlock();
                }
                pending.values().forEach(waiting -> {
                    if (waiting.state == this
                            && !List.of("terminal/kill", "terminal/release").contains(waiting.method)) {
                        waiting.abort();
                    }
                });
            }

            private void closeMcp() {
                if (mcpClosed.compareAndSet(false, true)) {
                    Connection.this.closeMcp(owned);
                }
            }

            private void drainAndClose(final long deadline) {
                ready.complete(null);
                final CompletableFuture<Void> drained;
                commandLock.lock();
                try {
                    // Read finished only after command startup/configuration has stopped.
                    drained = commands.handle((ignored, failure) -> null).thenCompose(ignored -> finished);
                } finally {
                    commandLock.unlock();
                }
                boolean extract = false;
                try {
                    drained.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    extract = System.nanoTime() < deadline;
                } catch (final Exception ignored) {
                    // Never close a runtime underneath an uncooperative turn or hook. Its
                    // eventual completion still owns disposal, but no longer runs extraction.
                    drained.handle((unused, failure) -> null).thenRun(() -> close(false));
                    return;
                }
                close(extract);
            }

            private void close(final boolean extract) {
                lock.lock();
                try {
                    if (disposed.get()) {
                        return;
                    }
                    closing.set(true);
                    try {
                        // Project unresolved idle/child passes while the output owner is still alive.
                        // After disposal update() intentionally rejects all late notifications.
                        updates.close();
                    } catch (final RuntimeException ignored) {
                        // Broken output must not prevent resource disposal.
                    } finally {
                        disposed.set(true);
                    }
                } finally {
                    lock.unlock();
                }
                ready.complete(null);
                closing.set(true);
                clientTools.close();
                cancel();
                closeMcp();
                shells.unregister(id, env);
                try {
                    persistSelection(); // Drained commands/hooks are authoritative, including an empty session.
                    if (extract && !Thread.currentThread().isInterrupted()) {
                        final var loop = runtime.loop();
                        loop.forceExtractNow(store.load(id), loop.chatSettings());
                    }
                } finally {
                    try {
                        runtime.close();
                    } finally {
                        try {
                            lifecycle.release(null, runtime); // close must not implicitly delete empty stored sessions
                        } finally {
                            clientTools.close();
                            buffered.clear();
                            legacyTools.clear();
                            knownModels.clear();
                            sessions.remove(id, this);
                            if (slotOwned.compareAndSet(true, false)) {
                                sessionSlots.release();
                            }
                            released.complete(null);
                        }
                    }
                }
            }

            private String toolId(final String subagent, final long ordinal) {
                return turn + ":" + (subagent == null ? "main" : subagent) + ":" + ordinal;
            }

            private String canonical(final String subagent, final long ordinal, final String name) {
                if (ordinal >= 0) {
                    return toolId(subagent, ordinal);
                }
                return legacyTools.computeIfAbsent(
                        (subagent == null ? "main" : subagent) + ":" + name,
                        ignored -> "legacy-" + ids.incrementAndGet());
            }

            private void initialUpdates() {
                update(slash.update());
                lock.lock();
                try {
                    plan();
                } finally {
                    lock.unlock();
                }
            }

            private void plan() {
                if (!disposed.get() && !closing.get() && runtime.todos() != null) {
                    final var snapshot = runtime.todos().all();
                    if (!Objects.equals(lastPlan, snapshot)) {
                        lastPlan = List.copyOf(snapshot);
                        updates.plan(lastPlan);
                    }
                }
            }

            private void projectUpdate(final Map<String, Object> value) {
                if ("tool_call_update".equals(value.get("sessionUpdate"))
                        || "tool_call".equals(value.get("sessionUpdate"))) {
                    if (toolOutput == ToolOutputVisibility.HIDDEN) {
                        return;
                    }
                    if (toolOutput == ToolOutputVisibility.COLLAPSED) {
                        final var filtered = new LinkedHashMap<>(value);
                        filtered.remove("content");
                        filtered.remove("rawOutput");
                        filtered.remove("rawInput");
                        update(filtered);
                        return;
                    }
                }
                update(value);
            }

            private void terminal(final String subagent, final long ordinal, final String terminalId) {
                lock.lock();
                try {
                    if (!active || cancelled.get() || closing.get()) {
                        return;
                    }
                    projectUpdate(Map.of(
                            "sessionUpdate",
                            "tool_call_update",
                            "toolCallId",
                            canonical(subagent, ordinal, "client_terminal"),
                            "kind",
                            "execute",
                            "status",
                            "in_progress",
                            "content",
                            List.of(Map.of("type", "terminal", "terminalId", terminalId))));
                } finally {
                    lock.unlock();
                }
            }

            private void publicNotice(final String severity, final String text) {
                final var safe = safe(text);
                if (AcpUpdates.supports(capabilities, "notices")) {
                    update(Map.of("sessionUpdate", "notice", "severity", severity, "title", safe));
                } else {
                    update(new ChunkUpdate("agent_message_chunk", Content.text("\n[" + severity + "] " + safe + "\n")));
                }
            }

            private String safe(final String text) {
                return redactor == null ? text : redactor.redact(text, id);
            }

            private void flushText() {
                buffered.forEach(this::update);
                buffered.clear();
            }

            private CompletionStage<ShellOutcome> sideQuestion(final String question, final ShellEnv ignored) {
                if (question.isBlank()) {
                    return CompletableFuture.completedFuture(ShellOutcome.status("usage: /btw <question>"));
                }
                final var epoch = cancelEpoch.get();
                return btw.ask(
                                question,
                                id,
                                runtime.usage(),
                                () -> cancelled.get() || closing.get() || closed.get() || epoch != cancelEpoch.get(),
                                approvalGate(),
                                runtime.chatSettings(),
                                runtime.systemPromptBuilder(),
                                runtime.feedback(),
                                runtime.workdir(),
                                runtime.loop().effectiveToolRegistry(),
                                runtime.loop().availableToolNames(),
                                runtime.sandbox())
                        .thenApply(answer -> ShellOutcome.transcript("\n\n**btw**\n\n" + answer.text() + "\n"));
            }

            private CompletionStage<ShellOutcome> provider(final String arg, final ShellEnv ignored) {
                if (arg.isBlank()) {
                    final var values = advertised.getFirst().options().stream()
                            .map(ConfigValue::value)
                            .toList();
                    return pick("Provider", values)
                            .thenComposeAsync(
                                    selected -> selected.isEmpty()
                                            ? CompletableFuture.completedFuture(
                                                    ShellOutcome.status("provider picker cancelled"))
                                            : selectProvider(selected.get()),
                                    workers);
                }
                return selectProvider(arg);
            }

            private CompletionStage<ShellOutcome> inspectTools(final String arg, final ShellEnv ignored) {
                return toolListing(arg, false);
            }

            private CompletionStage<ShellOutcome> toolListing(final String filter, final boolean onlyMcp) {
                final var ceiling = runtime.loop().availableToolNames();
                final var rows = runtime.loop().effectiveToolRegistry().tools().stream()
                        .filter(tool -> ceiling.contains(tool.name()))
                        .filter(tool -> !onlyMcp || tool.category() == ToolCategory.MCP)
                        .filter(tool -> filter.isBlank()
                                || tool.name().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))
                        // Descriptions/schema can contain untrusted URLs, headers and connection configuration.
                        .map(tool -> List.of(
                                SlashRegistry.cell(tool.name()),
                                tool.category().name().toLowerCase(Locale.ROOT),
                                tool.readonly() ? "read-only" : "approval required"))
                        .toList();
                return CompletableFuture.completedFuture(
                        ShellOutcome.transcript((onlyMcp ? "**Session MCP tools**" : "**Session tools**") + "\n\n"
                                + SlashRegistry.table(List.of("tool", "category", "access"), rows)));
            }

            private CompletionStage<ShellOutcome> inspectMcp(final String arg, final ShellEnv ignored) {
                if (arg.isBlank() || "status".equalsIgnoreCase(arg)) {
                    return toolListing("", true);
                }
                final var parts = WHITESPACE.split(arg.strip(), 2);
                if (!"reset".equalsIgnoreCase(parts[0])) {
                    return CompletableFuture.completedFuture(
                            ShellOutcome.status("usage: /mcp status|reset [configured-core-server]"));
                }
                try {
                    if (parts.length == 2) {
                        // Session clients are borrowed here. Only the application manager may reset its own clients.
                        if (mcp.serverStatuses().stream().noneMatch(status -> parts[1].equals(status.name()))) {
                            return CompletableFuture.completedFuture(
                                    ShellOutcome.error("Session MCP servers cannot be reset; reopen this session."));
                        }
                        mcp.reset(parts[1]);
                    } else {
                        mcp.reset();
                    }
                    return CompletableFuture.completedFuture(
                            ShellOutcome.status("Configured core MCP servers reset; session MCP clients unchanged."));
                } catch (final RuntimeException failure) {
                    return CompletableFuture.completedFuture(ShellOutcome.error("MCP reset failed."));
                }
            }

            private CompletionStage<ShellOutcome> editPlan(final String arg, final ShellEnv ignored) {
                if (plans == null || planFiles == null || configuration == null) {
                    return CompletableFuture.completedFuture(ShellOutcome.error("Plan editing is unavailable."));
                }
                if (runtime.permissions().mode() != AgentMode.PLAN) {
                    return CompletableFuture.completedFuture(
                            ShellOutcome.status("editplan is only available in plan mode (run /mode plan)"));
                }
                final var plan = plans.plan(id);
                if (plan == null || plan.isBlank()) {
                    return CompletableFuture.completedFuture(
                            ShellOutcome.status("no plan to edit yet - produce a plan first"));
                }
                final var path = arg.isBlank()
                        ? planFiles
                                .defaultPath(Path.of(configuration.resolveStorageHome()), "plan", id)
                                .toAbsolutePath()
                                .normalize()
                        : runtime.workdir().resolve(arg).normalize();
                final var original = planFiles.render(
                        plan,
                        runtime.todos() == null ? List.of() : runtime.todos().all());
                final var fs = capabilities.get("fs") instanceof Map<?, ?> values ? values : Map.of();
                final var clientRead = Boolean.TRUE.equals(fs.get("readTextFile"));
                final var clientWrite = Boolean.TRUE.equals(fs.get("writeTextFile"));
                if (clientRead != clientWrite) {
                    return CompletableFuture.completedFuture(
                            ShellOutcome.error(
                                    "Plan editing requires both client file read/write capabilities, or neither for saved server files."));
                }
                final var epoch = cancelEpoch.get();
                return confirm("Export the current plan to " + path
                                + "? This replaces existing file/buffer content. Save or back up your edits first.")
                        .thenComposeAsync(
                                accepted -> {
                                    if (!accepted || !live(epoch)) {
                                        return CompletableFuture.completedFuture(
                                                ShellOutcome.status("plan edit cancelled"));
                                    }
                                    final CompletionStage<?> written;
                                    if (clientWrite) {
                                        written = requestClient(
                                                this,
                                                "fs/write_text_file",
                                                Map.of("sessionId", id, "path", path.toString(), "content", original),
                                                Duration.ofMinutes(2),
                                                () -> !live(epoch),
                                                unused -> {});
                                    } else {
                                        lock.lock();
                                        try {
                                            if (!live(epoch)) {
                                                return CompletableFuture.completedFuture(ShellOutcome.none());
                                            }
                                            if (path.getParent() != null) {
                                                Files.createDirectories(path.getParent());
                                            }
                                            Files.writeString(path, original);
                                            written = CompletableFuture.completedFuture(null);
                                        } catch (final IOException failure) {
                                            return CompletableFuture.completedFuture(
                                                    ShellOutcome.error("Plan export failed."));
                                        } finally {
                                            lock.unlock();
                                        }
                                    }
                                    return written.thenCompose(unused -> {
                                                if (!live(epoch)) {
                                                    return CompletableFuture.completedFuture(false);
                                                }
                                                status("Plan exported to " + path
                                                        + ". Edit the actual file, then confirm reload.");
                                                return confirm("Reload your edited plan from " + path + "?");
                                            })
                                            .thenComposeAsync(
                                                    reload -> {
                                                        if (!reload || !live(epoch)) {
                                                            return CompletableFuture.completedFuture(
                                                                    ShellOutcome.status("plan edit cancelled"));
                                                        }
                                                        final CompletionStage<String> content;
                                                        if (clientRead) {
                                                            content = requestClient(
                                                                            this,
                                                                            "fs/read_text_file",
                                                                            Map.of(
                                                                                    "sessionId",
                                                                                    id,
                                                                                    "path",
                                                                                    path.toString()),
                                                                            Duration.ofMinutes(2),
                                                                            () -> !live(epoch),
                                                                            unused -> {})
                                                                    .thenApply(value -> {
                                                                        if (!(value instanceof Map<?, ?> reply)
                                                                                || !(reply.get("content")
                                                                                        instanceof String text)) {
                                                                            throw new IllegalArgumentException(
                                                                                    "Invalid file response");
                                                                        }
                                                                        return text;
                                                                    });
                                                        } else {
                                                            try {
                                                                content = CompletableFuture.completedFuture(
                                                                        Files.readString(path));
                                                            } catch (final IOException failure) {
                                                                return CompletableFuture.completedFuture(
                                                                        ShellOutcome.error("Plan reload failed."));
                                                            }
                                                        }
                                                        return content.thenApplyAsync(
                                                                edited -> {
                                                                    lock.lock();
                                                                    try {
                                                                        if (!live(epoch)) {
                                                                            return ShellOutcome.none();
                                                                        }
                                                                        return ShellOutcome.status(
                                                                                original.equals(edited)
                                                                                        ? "no changes in the plan (nothing to reload)"
                                                                                        : planFiles.reload(
                                                                                                id,
                                                                                                planFiles.parse(edited),
                                                                                                runtime.todos()));
                                                                    } finally {
                                                                        lock.unlock();
                                                                    }
                                                                },
                                                                workers);
                                                    },
                                                    workers);
                                },
                                workers)
                        .exceptionally(failure -> ShellOutcome.error("Plan editing failed or was cancelled."));
            }

            private CompletionStage<ShellOutcome> selectProvider(final String selected) {
                final var epoch = cancelEpoch.get();
                try {
                    if (!live(epoch)) {
                        return CompletableFuture.completedFuture(ShellOutcome.none());
                    }
                    final var before = options.providerId(runtime.chatSettings());
                    options.apply(id, runtime, advertised, "provider", selected, () -> !live(epoch));
                    if (live(epoch)) {
                        refreshOptions();
                    }
                    final var actual = options.providerId(runtime.chatSettings());
                    if (!live(epoch)) {
                        return CompletableFuture.completedFuture(ShellOutcome.none());
                    }
                    if (before.equals(actual)) {
                        runtime.refreshUsage();
                    }
                    // A vetoed switch must not log in the rejected provider or rewrite the readback.
                    if (actual.equals(selected)
                            && codexProvider(actual)
                            && login != null
                            && login.load(actual) == null) {
                        return signIn(new CodexLoginService.LoginRequest(actual, false, false, null), epoch);
                    }
                    return CompletableFuture.completedFuture(ShellOutcome.status("Provider: " + actual));
                } catch (final RuntimeException invalid) {
                    return CompletableFuture.completedFuture(ShellOutcome.error(
                            "Provider selection or authentication failed; check configured providers."));
                }
            }

            private boolean codexProvider(final String id) {
                final var provider = providers == null ? null : providers.provider(id);
                return provider != null
                        && (provider.api() == ApiFlavor.CODEX
                                || ChatRoutes.detectFlavor(provider.baseUrl()) == ApiFlavor.CODEX);
            }

            private boolean live(final long epoch) {
                return active
                        && !cancelled.get()
                        && !closing.get()
                        && !disposed.get()
                        && !closed.get()
                        && !disconnecting.get()
                        && epoch == cancelEpoch.get();
            }

            @Override
            public BooleanSupplier settingsUpdateCancellation() {
                final var epoch = cancelEpoch.get();
                return () -> !live(epoch);
            }

            private CompletionStage<ShellOutcome> codex(final String arg, final ShellEnv ignored) {
                final var tokens = arg.isBlank() ? List.<String>of() : List.of(WHITESPACE.split(arg.strip()));
                var operation = "login";
                var start = 0;
                if (!tokens.isEmpty()
                        && Set.of("login", "enroll", "status", "logout")
                                .contains(tokens.getFirst().toLowerCase(Locale.ROOT))) {
                    operation = tokens.getFirst().toLowerCase(Locale.ROOT);
                    start = 1;
                }
                String provider = null;
                String workspace = null;
                boolean device = false;
                boolean enterprise = "enroll".equals(operation);
                for (int i = start; i < tokens.size(); i++) {
                    final var token = tokens.get(i);
                    switch (token.toLowerCase(Locale.ROOT)) {
                        case "--device" -> device = true;
                        case "--enterprise" -> enterprise = true;
                        case "--workspace" -> {
                            if (workspace != null
                                    || i + 1 == tokens.size()
                                    || tokens.get(i + 1).startsWith("--")) {
                                return codexUsage();
                            }
                            workspace = tokens.get(++i);
                        }
                        default -> {
                            if (token.startsWith("--") || provider != null) {
                                return codexUsage();
                            }
                            provider = token;
                        }
                    }
                }
                if (provider == null) {
                    provider = options.providerId(runtime.chatSettings());
                }
                if (!codexProvider(provider) || (workspace != null && !enterprise)) {
                    return codexUsage();
                }
                if ("logout".equals(operation)) {
                    // Core exposes no credential-removal operation. Never invent a global ACP logout.
                    return CompletableFuture.completedFuture(ShellOutcome.error(
                            "Codex logout is unavailable: the configured authentication service has no logout API."));
                }
                if ("status".equals(operation)) {
                    if (device || enterprise || workspace != null) {
                        return codexUsage();
                    }
                    try {
                        return CompletableFuture.completedFuture(ShellOutcome.status(
                                login.load(provider) == null
                                        ? "Codex: not signed in"
                                        : "Codex: saved credentials available"));
                    } catch (final RuntimeException failure) {
                        return CompletableFuture.completedFuture(ShellOutcome.error("Codex status could not be read."));
                    }
                }
                try {
                    return signIn(
                            new CodexLoginService.LoginRequest(provider, device, enterprise, workspace),
                            cancelEpoch.get());
                } catch (final RuntimeException failure) {
                    return CompletableFuture.completedFuture(ShellOutcome.error("Codex login could not start."));
                }
            }

            private CompletionStage<ShellOutcome> codexUsage() {
                return CompletableFuture.completedFuture(ShellOutcome.status(
                        "usage: /codex login [provider] [--device] [--enterprise] [--workspace <org-id>]; "
                                + "/codex enroll [provider] [--device] [--workspace <org-id>]; /codex status [provider]. "
                                + "Only configured Codex providers are supported; logout is unavailable in core."));
            }

            private CompletionStage<ShellOutcome> signIn(
                    final CodexLoginService.LoginRequest request, final long epoch) {
                final var attempt = login.newAttempt(request, new CodexLoginService.LoginListener() {
                    @Override
                    public void status(final CodexLoginService.LoginStatus status) {
                        authInstruction(epoch, "Codex: " + status.name().toLowerCase(Locale.ROOT));
                    }

                    @Override
                    public void browser(final String url) {
                        authInstruction(
                                epoch, "Authorize Codex in your browser:\n" + url + "\nWaiting for authorization.");
                    }

                    @Override
                    public void device(final CodexLoginService.DeviceChallenge challenge) {
                        authInstruction(
                                epoch,
                                "Authorize Codex at:\n" + challenge.verificationUrl() + "\nUser code: "
                                        + challenge.userCode() + "\nWaiting for authorization.");
                    }
                });
                lock.lock();
                try {
                    loginAttempt = attempt; // Retain BEFORE scheduling, including cancellation before start.
                    if (!live(epoch)) {
                        attempt.cancel();
                    }
                } finally {
                    lock.unlock();
                }
                attempt.start(workers);
                return attempt.drained()
                        .thenCompose(unused -> attempt.completion())
                        .handle((result, failure) -> {
                            lock.lock();
                            try {
                                if (loginAttempt == attempt) {
                                    loginAttempt = null;
                                }
                                if (!live(epoch)) {
                                    return ShellOutcome.none();
                                }
                                if (failure == null
                                        && result.state() == CodexLoginService.LoginState.SUCCEEDED
                                        && result.credentialsCommitted()) {
                                    return ShellOutcome.status("Codex login complete.");
                                }
                                return ShellOutcome.error("Codex login failed or was cancelled.");
                            } finally {
                                lock.unlock();
                            }
                        });
            }

            private void authInstruction(final long epoch, final String text) {
                lock.lock();
                try {
                    if (live(epoch)) {
                        // Private user-facing instruction only: never a log, history entry or provider context.
                        update(new ChunkUpdate("agent_message_chunk", Content.text(text + "\n")));
                        if (output.checkError()) {
                            throw new IllegalStateException("Authentication instruction delivery failed");
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }

            private CompletionStage<ShellOutcome> refreshMaven(final String arg, final ShellEnv ignored) {
                final var parts = WHITESPACE.split(arg.strip(), 2);
                final var gav = parts.length == 2 ? parts[1].strip() : "";
                final var epoch = cancelEpoch.get();
                final var settings = runtime.chatSettings();
                final MavenRepositoryKnowledgeService.IndexRun job;
                lock.lock();
                try {
                    if (!live(epoch)) {
                        return CompletableFuture.completedFuture(ShellOutcome.none());
                    }
                    preparingIndex = Thread.currentThread();
                } finally {
                    lock.unlock();
                }
                try {
                    // Dependency discovery can block. This is an owned command worker, never the reader.
                    job = maven.refreshJob(
                            runtime.workdir(),
                            gav,
                            message -> {
                                lock.lock();
                                try {
                                    if (live(epoch)) {
                                        status(message);
                                    }
                                } finally {
                                    lock.unlock();
                                }
                            },
                            null,
                            settings);
                } catch (final RuntimeException failure) {
                    return CompletableFuture.completedFuture(
                            live(epoch)
                                    ? ShellOutcome.error("Maven knowledge refresh could not start.")
                                    : ShellOutcome.none());
                } finally {
                    lock.lock();
                    try {
                        preparingIndex = null; // Same guard as interrupt: never interrupt a reused worker.
                    } finally {
                        lock.unlock();
                    }
                }
                boolean deliveryFailed = false;
                lock.lock();
                try {
                    indexRun = job;
                    if (!live(epoch)) {
                        job.cancel(); // Discovery can ignore interruption; fence late handle publication.
                    } else {
                        try {
                            job.messages().forEach(this::status);
                        } catch (final RuntimeException failure) {
                            deliveryFailed = true;
                            job.cancel(); // Even a failed live callback must retain ownership until real drain.
                        }
                    }
                } finally {
                    lock.unlock();
                }
                final var failed = deliveryFailed;
                return job.completion().handle((summary, failure) -> {
                    lock.lock();
                    try {
                        if (indexRun == job) {
                            indexRun = null;
                        }
                        if (!live(epoch)) {
                            return ShellOutcome.none();
                        }
                        return failure == null && !failed
                                ? ShellOutcome.status(summary)
                                : ShellOutcome.error("Maven knowledge refresh failed or was cancelled.");
                    } finally {
                        lock.unlock();
                    }
                });
            }

            private void update(final Object update) {
                if (!closed.get() && !disposed.get()) {
                    send(Envelope.of("2.0", null, "session/update", new UpdateParams(id, update), null, null));
                }
            }

            @Override
            public void onEvent(final AgentEvent event) {
                lock.lock();
                try {
                    if (disposed.get() || closed.get()) {
                        return;
                    }
                    // Fusion delivery is synchronous: terminal events emitted by an operation are
                    // projected before its completion/endTurn, even after cancellation or close intent.
                    if (!disposed.get() && event instanceof AgentEvent.CompactionPass pass) {
                        updates.compaction(pass);
                        return;
                    }
                    if (event.subagentId() == null) {
                        refreshOptions();
                    }
                    if (!disposed.get() && !closing.get() && event instanceof AgentEvent.UsageUpdated usage) {
                        updates.usage(
                                usage,
                                event.subagentId() == null ? options.contextWindow(runtime.chatSettings()) : null);
                        return;
                    }
                    if (!active || cancelled.get() || closed.get()) {
                        return;
                    }
                    // Stable v1 cannot retract chunks. Keep retry-prone text/thoughts until TurnDone;
                    // TextReset discards this attempt. Tools/notices remain live and independently ID-addressed.
                    if (event instanceof AgentEvent.TextDelta delta && event.subagentId() == null) {
                        buffered.add(new ChunkUpdate("agent_message_chunk", Content.text(safe(delta.delta()))));
                    } else if (event instanceof AgentEvent.ThinkingDelta delta && event.subagentId() == null) {
                        if (thinking) {
                            buffered.add(new ChunkUpdate("agent_thought_chunk", Content.text(safe(delta.delta()))));
                        }
                    } else if (event instanceof AgentEvent.TextReset && event.subagentId() == null) {
                        buffered.clear();
                    } else if (event instanceof AgentEvent.TurnDone && event.subagentId() == null) {
                        flushText();
                        updates.event(event);
                    } else if (event instanceof AgentEvent.ToolStarted tool) {
                        final var canonical = canonical(tool.subagentId(), tool.ordinal(), tool.name());
                        runningToolIds.put(Thread.currentThread().threadId(), canonical);
                        updates.tool(
                                canonical,
                                event,
                                new AcpUpdates.ToolSnapshot(event.workdir(), null, List.of(), List.of()));
                    } else if (event instanceof AgentEvent.ToolStream tool) {
                        updates.tool(canonical(tool.subagentId(), tool.ordinal(), tool.name()), event, null);
                    } else if (event instanceof AgentEvent.ToolDiagnostic tool) {
                        updates.tool(canonical(tool.subagentId(), tool.ordinal(), tool.name()), event, null);
                    } else if (event instanceof AgentEvent.ToolFilesChanged tool) {
                        updates.files(canonical(tool.subagentId(), tool.ordinal(), tool.name()), tool, this::safe);
                    } else if (event instanceof AgentEvent.ToolFinished tool) {
                        runningToolIds.remove(Thread.currentThread().threadId());
                        updates.tool(
                                canonical(tool.subagentId(), tool.ordinal(), tool.name()),
                                event,
                                new AcpUpdates.ToolSnapshot(event.workdir(), null, List.of(), List.of()));
                        if (tool.ordinal() < 0) {
                            legacyTools.remove(
                                    (tool.subagentId() == null ? "main" : tool.subagentId()) + ":" + tool.name());
                        }
                        if (event.subagentId() == null) {
                            plan();
                        }
                    } else if (event instanceof AgentEvent.Error error && event.subagentId() == null) {
                        error(error.message());
                    } else if (event instanceof AgentEvent.Notice notice && event.subagentId() == null) {
                        updates.event(event); // ERROR severity alone is not a failed prompt turn.
                        if (notice.finishReason() != null) {
                            stop = switch (notice.finishReason()) {
                                case "length", "max_tokens" -> "max_tokens";
                                case "max_turns", "max_iterations" -> "max_turn_requests";
                                case "content_filter", "refusal" -> "refusal";
                                default -> stop;
                            };
                        }
                    } else {
                        updates.event(event);
                    }
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public ApprovalGate approvalGate() {
                return request -> {
                    final var title = (request.origin() == ApprovalGate.Origin.SANDBOX
                                    ? "Sandbox restriction: "
                                    : "Tool permission: ")
                            + request.title();
                    final var name = request.toolName();
                    final var args = request.args();
                    if (!active
                            || cancelled.get()
                            || closed.get()
                            || (name != null
                                    && name.startsWith("client_")
                                    && runtime.permissions().mode() == AgentMode.PLAN)) {
                        return ApprovalGate.Result.DENY;
                    }
                    CompletableFuture<Object> answer = null;
                    try {
                        final var permission = new Permission(
                                id,
                                new ToolCall(
                                        runningToolIds.getOrDefault(
                                                Thread.currentThread().threadId(), "approval-" + ids.incrementAndGet()),
                                        safe(title),
                                        ToolKind.other,
                                        ToolStatus.pending,
                                        fields(mapper.fromString(Object.class, safe(mapper.toString(args))))),
                                List.of(
                                        new PermissionOption(
                                                "allow_once", "Allow once", PermissionOptionKind.allow_once),
                                        new PermissionOption(
                                                "allow_session",
                                                "Allow for this session only",
                                                PermissionOptionKind.allow_always),
                                        new PermissionOption(
                                                "reject_once", "Reject", PermissionOptionKind.reject_once)));
                        answer = requestClient(
                                        this,
                                        "session/request_permission",
                                        fields(permission),
                                        Duration.ofMinutes(2),
                                        cancelled::get,
                                        ignored -> {})
                                .toCompletableFuture();
                        final var response = answer.get();
                        if (cancelled.get() || response == null) {
                            return ApprovalGate.Result.DENY;
                        }
                        final var outcome =
                                decode(PermissionResult.class, response).outcome();
                        if (outcome == null || !"selected".equals(outcome.outcome()) || outcome.optionId() == null) {
                            return ApprovalGate.Result.DENY;
                        }
                        return switch (outcome.optionId()) {
                            case "allow_once" -> ApprovalGate.Result.ALLOW_ONCE;
                            case "allow_session" -> ApprovalGate.Result.ALLOW_SESSION;
                            default -> ApprovalGate.Result.DENY;
                        };
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return ApprovalGate.Result.DENY;
                    } catch (final Exception e) {
                        return ApprovalGate.Result.DENY;
                    } finally {
                        if (answer != null && !answer.isDone()) {
                            answer.cancel(true);
                        }
                    }
                };
            }

            @Override
            public FeedbackGate feedbackGate() {
                return questions -> {
                    if (!AcpElicitation.supportsForm(capabilities)) {
                        publicNotice(
                                "warning",
                                "This client does not support nonsecret form questions; the question was not approved.");
                    }
                    return elicitation.ask(questions);
                };
            }

            @Override
            public CompletionStage<Optional<String>> readLine(final String prompt) {
                publicNotice(
                        "warning",
                        "Generic input cannot be verified as nonsecret. Use configured authentication outside ACP; credentials are never requested in forms.");
                return elicitation.readLine(prompt, false);
            }

            @Override
            public CompletionStage<Boolean> confirm(final String question) {
                return elicitation.confirm(question);
            }

            @Override
            public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
                return elicitation.pick(title, options);
            }

            @Override
            public void status(final String message) {
                if (message != null && !message.isBlank() && !closing.get()) {
                    publicNotice("info", message);
                }
            }

            @Override
            public void notice(final AgentEvent.Notice.Level level, final String message) {
                if (message != null && !message.isBlank() && !closing.get()) {
                    publicNotice(level.name().toLowerCase(Locale.ROOT), message);
                }
            }

            @Override
            public void transcript(final String markdown) {
                lock.lock();
                try {
                    if (active && !cancelled.get()) {
                        update(new ChunkUpdate("agent_message_chunk", Content.text(markdown)));
                    }
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public void error(final String message) {
                lock.lock();
                try {
                    if (!active || cancelled.get() || closed.get()) {
                        return;
                    }
                    failure = true;
                    final var detail = message == null || message.isBlank() ? "Agent turn failed" : message;
                    failureMessage = safe(detail);
                    update(new ChunkUpdate("agent_message_chunk", Content.text("\n\nError: " + failureMessage + "\n")));
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public void showThinking(final boolean value) {
                lock.lock();
                try {
                    thinking = value;
                    if (!value) {
                        buffered.removeIf(chunk -> "agent_thought_chunk".equals(chunk.sessionUpdate()));
                    }
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public void showToolOutput(final ToolOutputVisibility value) {
                toolOutput = Objects.requireNonNull(value);
            }
        }
    }
}
