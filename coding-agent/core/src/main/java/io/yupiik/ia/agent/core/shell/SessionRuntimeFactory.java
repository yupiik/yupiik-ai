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
package io.yupiik.ia.agent.core.shell;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.MemoryExtractor;
import io.yupiik.ia.agent.core.agent.SubagentBudget;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.agent.TodoPromptResolver;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.ContextWindowCache;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.events.SessionStartedEvent;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.memory.MemoryCompactor;
import io.yupiik.ia.agent.core.memory.MemoryProductionPolicy;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.core.permissions.BashRulesRegistry;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.SessionSandbox;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.session.SessionMetadata;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.usage.QuotaSnapshotStore;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Creates one {@link SessionRuntime} per session (one TUI tab, the CLI session):
 * the single place where the process scoped beans and the session scoped {@code new}
 * instances are assembled. No session state is injected from the container.
 */
@ApplicationScoped
public class SessionRuntimeFactory {
    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    private final CodingAgentConfiguration configuration;
    private final ContextWindowCache contextWindowCache;
    private final SessionStore sessionStore;
    private final MemoryStore memoryStore;
    private final AgentProvider agentProvider;
    private final ProviderManager providerManager;
    private final TodoPromptResolver todoPromptResolver;
    private final MavenRepositoryKnowledgeService mavenRepositoryKnowledge;
    private final StructuralCodeGraph structuralCodeGraph;
    private final SnapshotStore snapshotStore;
    private final JsonMapper mapper;
    private final Emitter emitter;
    private final Clock clock;
    private final PrecisionDurationMarkerProvider precisionDurationMarker;
    private final BackoffSleeper backoffSleeper;
    private final ExecutorService executor;
    private final BashRulesRegistry bashRulesRegistry;
    private final MemoryProductionPolicy memoryProductionPolicy;
    private final QuotaSnapshotStore quotaStore;
    private final SecretRedactor secretRedactor;
    private final NotificationService notificationService;

    public SessionRuntimeFactory(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final ContextWindowCache contextWindowCache,
            final SessionStore sessionStore,
            final MemoryStore memoryStore,
            final AgentProvider agentProvider,
            final ProviderManager providerManager,
            final TodoPromptResolver todoPromptResolver,
            final MavenRepositoryKnowledgeService mavenRepositoryKnowledge,
            final StructuralCodeGraph structuralCodeGraph,
            final SnapshotStore snapshotStore,
            final JsonMapper mapper,
            final Emitter emitter,
            final Clock clock,
            final PrecisionDurationMarkerProvider precisionDurationMarker,
            final BackoffSleeper backoffSleeper,
            final ExecutorService executor,
            final BashRulesRegistry bashRulesRegistry,
            final MemoryProductionPolicy memoryProductionPolicy,
            final QuotaSnapshotStore quotaStore,
            final SecretRedactor secretRedactor,
            final NotificationService notificationService) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.configuration = configuration;
        this.contextWindowCache = contextWindowCache;
        this.sessionStore = sessionStore;
        this.memoryStore = memoryStore;
        this.agentProvider = agentProvider;
        this.providerManager = providerManager;
        this.todoPromptResolver = todoPromptResolver;
        this.mavenRepositoryKnowledge = mavenRepositoryKnowledge;
        this.structuralCodeGraph = structuralCodeGraph;
        this.snapshotStore = snapshotStore;
        this.mapper = mapper;
        this.emitter = emitter;
        this.clock = clock;
        this.precisionDurationMarker = precisionDurationMarker;
        this.backoffSleeper = backoffSleeper;
        this.executor = executor;
        this.bashRulesRegistry = bashRulesRegistry;
        this.memoryProductionPolicy = memoryProductionPolicy;
        this.quotaStore = quotaStore;
        this.secretRedactor = secretRedactor;
        this.notificationService = notificationService;
    }

    /**
     * @param sessionKey the stable per-tab key (todos file + event routing);
     *                   usually the session id at tab creation
     * @param sessionId  the initial chat session id owned by the runtime
     */
    public SessionRuntime create(final String sessionKey, final String sessionId, final Path workdir) {
        return create(sessionKey, sessionId, workdir, null);
    }

    /** Creates a runtime with a launch-time override of sandbox enforcement only. */
    public SessionRuntime create(
            final String sessionKey, final String sessionId, final Path workdir, final Boolean sandboxOverride) {
        return create(sessionKey, sessionId, toolRegistry, workdir, sandboxOverride);
    }

    /** Creates a runtime with an isolated tool registry for this session's loop. */
    public SessionRuntime create(
            final String sessionKey, final String sessionId, final ToolRegistry sessionTools, final Path workdir) {
        return create(sessionKey, sessionId, sessionTools, workdir, null);
    }

    /** Creates a runtime with isolated tools and a launch-time sandbox enforcement override. */
    public SessionRuntime create(
            final String sessionKey,
            final String sessionId,
            final ToolRegistry sessionTools,
            final Path workdir,
            final Boolean sandboxOverride) {
        Objects.requireNonNull(workdir, "workdir");
        if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
            throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
        }
        final var permissions = new PermissionService(configuration, emitter, bashRulesRegistry, workdir);
        final var sandbox = new SessionSandbox(
                configuration.sandbox().withEnabled(sandboxOverride),
                configuration.resolveWorkingDir(),
                workdir,
                permissions,
                SessionSandbox.tempDirFor(configuration.resolveWorkingDir()));
        try {
            final var feedback = new UserFeedbackService();
            final var usage = new UsageTracker(providerManager, chatClient, contextWindowCache, clock);
            final var todos = new TodoStore(configuration, mapper, sessionKey);
            todos.load();
            final var status = new StatusReporter();
            final var contextView = new ContextView(configuration.compression());
            final var compactionCoordinator = new CompactionCoordinator(
                    configuration, chatClient, usage, providerManager, emitter, sessionId, backoffSleeper, workdir);
            final var contextManager = new ContextManager(configuration, usage, compactionCoordinator);
            final var memoryCompactor = new MemoryCompactor(
                    chatClient, configuration, memoryStore, providerManager, usage, emitter, sessionId);
            final var memoryExtractor = new MemoryExtractor(
                    chatClient,
                    configuration,
                    providerManager,
                    memoryStore,
                    mapper,
                    executor,
                    emitter,
                    clock,
                    usage,
                    sessionId,
                    memoryCompactor,
                    memoryProductionPolicy,
                    secretRedactor,
                    workdir);
            final var systemPromptBuilder = new SystemPromptBuilder(
                    configuration,
                    memoryStore,
                    todos,
                    permissions,
                    agentProvider,
                    providerManager,
                    todoPromptResolver,
                    mavenRepositoryKnowledge,
                    secretRedactor,
                    workdir);
            final var loop = new AgentLoop(
                    chatClient,
                    sessionTools,
                    toolExecutionService,
                    configuration,
                    permissions,
                    usage,
                    quotaStore,
                    contextManager,
                    contextView,
                    sessionStore,
                    systemPromptBuilder,
                    memoryExtractor,
                    status,
                    emitter,
                    mapper,
                    structuralCodeGraph,
                    providerManager,
                    agentProvider,
                    clock,
                    snapshotStore,
                    todos,
                    executor,
                    sessionId,
                    null,
                    null,
                    feedback,
                    precisionDurationMarker,
                    null,
                    notificationService,
                    null,
                    null,
                    null,
                    workdir,
                    sandbox);
            // bounded initialization telemetry: mark the runtime wiring window so pre-first-request
            // startup is decomposable (runtime creation -> model resolution -> prompt build -> initial prompt)
            emitPhase(emitter, sessionId, AgentEvent.Phase.RUNTIME_CREATE_START, workdir);
            if (mavenRepositoryKnowledge != null) {
                mavenRepositoryKnowledge.warmUp(workdir);
            }
            // per-session sub-agent admission budget: the yca.subagents.maxConcurrent cap, owned by the
            // session object (dies with it, no registry-side map to clean up). SubagentRunner passes
            // the same budget to child loops, so nesting never creates another pool of permits.
            if (configuration != null && configuration.subagents() != null) {
                loop.subagentBudget(new SubagentBudget(configuration.subagents().maxConcurrent()));
            }
            if (emitter != null) {
                emitter.emit(new SessionStartedEvent(
                        new EventContext(null, null, null, workdir.toString(), null, null, null, null),
                        sessionId,
                        sessionKey));
            }
            emitPhase(emitter, sessionId, AgentEvent.Phase.RUNTIME_CREATE_END, workdir);
            return new SessionRuntime(
                    loop,
                    permissions,
                    feedback,
                    usage,
                    todos,
                    status,
                    contextManager,
                    systemPromptBuilder,
                    memoryProductionPolicy,
                    sessionId,
                    workdir,
                    sandbox);
        } catch (final RuntimeException | Error failure) {
            try {
                sandbox.close();
            } catch (final RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** Convenience: the key and the initial session id are the same value. */
    public SessionRuntime create(final String sessionId, final Path workdir) {
        return create(sessionId, workdir, null);
    }

    /** Convenience with a command-specific sandbox enforcement override. */
    public SessionRuntime create(final String sessionId, final Path workdir, final Boolean sandboxOverride) {
        return create(sessionId, sessionId, workdir, sandboxOverride);
    }

    /** Validate prompt budget/context before a frontend allocates a persistent session or publishes resources. */
    public void validateSessionContext(
            final ChatSettings settings,
            final Path workdir,
            final List<Path> additionalDirectories,
            final SessionMetadata.Instructions instructions) {
        final var builder = new SystemPromptBuilder(
                configuration,
                memoryStore,
                null,
                null,
                agentProvider,
                providerManager,
                todoPromptResolver,
                mavenRepositoryKnowledge,
                secretRedactor,
                workdir);
        builder.attach(settings);
        builder.configureSessionContext(additionalDirectories, instructions);
    }

    /**
     * Resolves frontend input once: explicit cwd, eligible resumed metadata, then the configured default.
     * Relative frontend paths are normalized here, without resolving symlinks.
     * An invalid explicit cwd fails rather than selecting another directory.
     */
    public Path resolveWorkdir(final Path explicitWorkdir, final String resumedSessionId) {
        if (explicitWorkdir == null && sessionStore != null && resumedSessionId != null) {
            final var recorded = sessionStore.projectDirOf(resumedSessionId);
            if (recorded != null && !recorded.isBlank()) {
                try {
                    final var candidate = Path.of(recorded);
                    if (candidate.isAbsolute() && Files.isDirectory(candidate)) {
                        return candidate.normalize();
                    }
                } catch (final InvalidPathException ignored) {
                    // Malformed or stale metadata is ineligible; explicit input never falls back.
                }
            }
        }
        final var root = (explicitWorkdir == null ? configuration.resolveWorkingDir() : explicitWorkdir)
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("workdir is not an existing directory: " + root);
        }
        return root;
    }

    /** For subclasses wiring a fake session (tests). */
    protected SessionRuntimeFactory() {
        this.chatClient = null;
        this.toolRegistry = null;
        this.toolExecutionService = null;
        this.configuration = null;
        this.contextWindowCache = null;
        this.sessionStore = null;
        this.memoryStore = null;
        this.agentProvider = null;
        this.providerManager = null;
        this.todoPromptResolver = null;
        this.mavenRepositoryKnowledge = null;
        this.structuralCodeGraph = null;
        this.snapshotStore = null;
        this.mapper = null;
        this.emitter = null;
        this.clock = null;
        this.backoffSleeper = new BackoffSleeper();
        this.executor = null;
        this.precisionDurationMarker = null;
        this.bashRulesRegistry = null;
        this.memoryProductionPolicy = null;
        this.quotaStore = null;
        this.secretRedactor = null;
        this.notificationService = null;
    }

    private void emitPhase(final Emitter emitter, final String sessionId, final String name, final Path workdir) {
        if (emitter != null) {
            emitter.emit(new AgentEvent.Phase(name, sessionId, workdir));
        }
    }
}
