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

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.ContextWindowCache;
import io.yupiik.ia.agent.core.context.SubagentUsageTracker;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.InMemoryTodoStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.usage.QuotaSnapshotStore;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

@ApplicationScoped
public class SubagentRunner {
    protected final ChatClient chatClient;
    protected final ToolRegistry toolRegistry;
    protected final ToolExecutionService toolExecutionService;
    protected final CodingAgentConfiguration configuration;
    protected final ContextWindowCache contextWindowCache;
    protected final MemoryStore memoryStore;
    protected final Emitter emitter;
    protected final JsonMapper mapper;
    protected final StructuralCodeGraph structuralCodeGraph;
    protected final ProviderManager providerManager;
    protected final AgentModelResolver agentModelResolver;
    protected final AgentProvider agentProvider;
    protected final TodoPromptResolver todoPromptResolver;
    protected final MavenRepositoryKnowledgeService mavenRepositoryKnowledge;
    protected final Clock clock;
    protected final PrecisionDurationMarkerProvider precisionDurationMarker;
    protected final BackoffSleeper backoffSleeper;
    protected final ExecutorService executor;
    protected final SubagentRegistry subagentRegistry;
    protected final SecretRedactor secretRedactor;
    protected final QuotaSnapshotStore quotaStore;

    public SubagentRunner(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final ContextWindowCache contextWindowCache,
            final MemoryStore memoryStore,
            final Emitter emitter,
            final JsonMapper mapper,
            final StructuralCodeGraph structuralCodeGraph,
            final ProviderManager providerManager,
            final AgentModelResolver agentModelResolver,
            final AgentProvider agentProvider,
            final TodoPromptResolver todoPromptResolver,
            final MavenRepositoryKnowledgeService mavenRepositoryKnowledge,
            final Clock clock,
            final ExecutorService executor,
            final SubagentRegistry subagentRegistry,
            final PrecisionDurationMarkerProvider precisionDurationMarker,
            final BackoffSleeper backoffSleeper,
            final SecretRedactor secretRedactor,
            final QuotaSnapshotStore quotaStore) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.configuration = configuration;
        this.contextWindowCache = contextWindowCache;
        this.memoryStore = memoryStore;
        this.emitter = emitter;
        this.mapper = mapper;
        this.structuralCodeGraph = structuralCodeGraph;
        this.providerManager = providerManager;
        this.agentModelResolver = agentModelResolver;
        this.agentProvider = agentProvider;
        this.todoPromptResolver = todoPromptResolver;
        this.mavenRepositoryKnowledge = mavenRepositoryKnowledge;
        this.clock = clock;
        this.executor = executor;
        this.subagentRegistry = subagentRegistry;
        this.precisionDurationMarker = precisionDurationMarker;
        this.backoffSleeper = backoffSleeper;
        this.secretRedactor = secretRedactor;
        this.quotaStore = quotaStore;
    }

    protected SubagentRunner() {
        this.chatClient = null;
        this.toolRegistry = null;
        this.toolExecutionService = null;
        this.configuration = null;
        this.contextWindowCache = null;
        this.memoryStore = null;
        this.emitter = null;
        this.mapper = null;
        this.structuralCodeGraph = null;
        this.providerManager = null;
        this.agentModelResolver = null;
        this.agentProvider = null;
        this.todoPromptResolver = null;
        this.mavenRepositoryKnowledge = null;
        this.clock = null;
        this.executor = null;
        this.subagentRegistry = null;
        this.precisionDurationMarker = null;
        this.backoffSleeper = null;
        this.secretRedactor = null;
        this.quotaStore = null;
    }

    /**
     * @param parentContext the spawning session's tool context: the sub-agent
     *                      inherits the parent's project directory, effective permission ceiling and feedback gate
     *                      (so a question raised by a sub-agent answers in the
     *                      spawning tab) and its events carry the parent session
     *                      id so they route back to that tab.
     */
    public void runAsync(
            final SubagentRegistry.SubagentState state,
            final SessionStore parentSessionStore,
            final SnapshotStore parentSnapshotStore,
            final TodoStore parentTodoStore,
            final ToolContext parentContext,
            final BooleanSupplier cancelled) {
        final var workdir =
                Objects.requireNonNull(parentContext, "parentContext").projectDir();
        final BooleanSupplier parentCancelled =
                () -> cancelled.getAsBoolean() || parentContext.isCancelled() || parentContext.isCallCancelled();
        if (parentCancelled.getAsBoolean() || state.isCancelled()) {
            subagentRegistry.complete(state.id(), SubagentRegistry.SubagentResult.cancelled(""));
            return;
        }
        final var name = state.name() != null ? state.name() : state.id();
        emitter.emit(new AgentEvent.SubagentStarted(state.task(), state.parentSessionId(), state.id(), name, workdir));
        try {
            executor.execute(() -> {
                try {
                    final var result = runSubagent(state, parentSnapshotStore, parentContext, parentCancelled);
                    subagentRegistry.complete(state.id(), result);
                    emitter.emit(new AgentEvent.SubagentCompleted(
                            result.summary() != null ? result.summary() : result.error(),
                            result.success(),
                            result.cancelled(),
                            state.parentSessionId(),
                            state.id(),
                            name,
                            modelOf(state),
                            workdir));
                    deliverToParent(
                            state,
                            result.summary(),
                            result.success(),
                            result.cancelled(),
                            parentContext.subagentId() != null);
                } catch (final Exception e) {
                    final var message = "Subagent failed: " + e.getMessage();
                    final var wasCancelled = state.isCancelled() || parentCancelled.getAsBoolean();
                    subagentRegistry.complete(
                            state.id(),
                            wasCancelled
                                    ? SubagentRegistry.SubagentResult.cancelled("")
                                    : SubagentRegistry.SubagentResult.error(message, ""));
                    emitter.emit(new AgentEvent.SubagentCompleted(
                            message,
                            false,
                            wasCancelled,
                            state.parentSessionId(),
                            state.id(),
                            name,
                            modelOf(state),
                            workdir));
                    deliverToParent(state, message, false, wasCancelled, parentContext.subagentId() != null);
                }
            });
        } catch (final RejectedExecutionException e) {
            final var message = "Subagent could not be scheduled: " + e.getMessage();
            final var result = SubagentRegistry.SubagentResult.error(message, "");
            subagentRegistry.complete(state.id(), result);
            emitter.emit(new AgentEvent.SubagentCompleted(
                    message, false, false, state.parentSessionId(), state.id(), name, modelOf(state), workdir));
            deliverToParent(state, message, false, false, parentContext.subagentId() != null);
        }
    }

    /** The effective model of a sub-agent once its runner resolved the routing, or {@code null} before that. */
    private String modelOf(final SubagentRegistry.SubagentState state) {
        return subagentRegistry
                .identityOf(state.id())
                .map(SubagentRegistry.TranscriptIdentity::model)
                .orElse(null);
    }

    /**
     * Pushes a finished sub-agent's result to its parent turn through the {@link SubagentDeliveryBus}
     * and emits a {@link AgentEvent.SubagentDelivered} so frontends can surface the delivery. Routing is
     * by the immediate parent's own session id, including when that parent is itself a sub-agent.
     */
    private void deliverToParent(
            final SubagentRegistry.SubagentState state,
            final String summary,
            final boolean success,
            final boolean cancelled,
            final boolean nested) {
        // the AgentLoop.integrateSubagentDeliveries emits the SubagentDelivered event when the parent
        // drains the queue (on its next round); we only publish the payload here.
        SubagentDeliveryBus.get()
                .deliver(
                        state.parentSessionId(),
                        new SubagentDeliveryBus.Delivery(state.id(), state.name(), summary, success, cancelled),
                        nested);
    }

    private SubagentRegistry.SubagentResult runSubagent(
            final SubagentRegistry.SubagentState state,
            final SnapshotStore parentSnapshotStore,
            final ToolContext parentContext,
            final BooleanSupplier cancelled) {
        final var subagentSessionId = state.childSessionId();
        final var subagentName = state.name() != null ? state.name() : state.id();
        final var parentWorkdir =
                Objects.requireNonNull(parentContext, "parentContext").projectDir();
        if (cancelled.getAsBoolean() || state.isCancelled()) {
            return SubagentRegistry.SubagentResult.cancelled("");
        }
        final var effectiveToolRegistry =
                parentContext.toolRegistry() == null ? toolRegistry : parentContext.toolRegistry();
        final var profile =
                state.profile() == null || state.profile().isBlank() ? null : agentProvider.byName(state.profile());
        if (state.profile() != null && profile == null) {
            return SubagentRegistry.SubagentResult.error("Unknown agent profile: " + state.profile(), "");
        }
        final Set<String> profileTools = profile == null ? null : agentProvider.allowedTools(profile.name());
        Set<String> requestedTools = state.allowedTools();
        if (state.parentAllowedTools() != null || profileTools != null || state.allowedTools() != null) {
            final var tools = effectiveToolRegistry == null ? List.<EmbeddedTool>of() : effectiveToolRegistry.tools();
            final var registered = tools.stream().map(EmbeddedTool::name).collect(Collectors.toSet());
            final var available = tools.stream()
                    .filter(tool -> tool.usable(parentWorkdir))
                    .map(EmbeddedTool::name)
                    .collect(Collectors.toSet());
            requestedTools = normalizeRequestedTools(requestedTools, registered, available);
            final var unknown = new TreeSet<String>();
            if (profileTools != null) {
                unknown.addAll(profileTools);
            }
            if (requestedTools != null) {
                unknown.addAll(requestedTools);
            }
            if (state.parentAllowedTools() != null) {
                unknown.addAll(state.parentAllowedTools());
            }
            unknown.removeAll(available);
            if (!unknown.isEmpty()) {
                return SubagentRegistry.SubagentResult.error(
                        "Requested tools are not available: " + String.join(", ", unknown), "");
            }
        }
        final var parentProjection = effectiveToolRegistry == null
                ? Set.<String>of()
                : effectiveToolRegistry
                        .toWireTools(
                                parentWorkdir,
                                tool -> parentContext.permissions() == null
                                        || parentContext.permissions().mode() != AgentMode.PLAN
                                        || parentContext.permissions().permissionMode() == PermissionMode.FULL_YOLO
                                        || tool.category() != ToolCategory.EDIT)
                        .stream()
                        .map(tool -> tool.function().name())
                        .collect(Collectors.toUnmodifiableSet());
        final var effectiveAllowedTools = intersectToolCeilings(
                parentProjection,
                parentContext.allowedTools(),
                state.parentAllowedTools(),
                profileTools,
                requestedTools);

        // Create isolated services for this subagent: explicit instances built from shared infrastructure and
        // child-owned policy/history/todo state. The parent approval UI and usage aggregation are reused.
        final var subagentSessionStore = new InMemorySessionStore(clock);
        final var subagentTodoStore = new InMemoryTodoStore();
        final var parentUsage = parentContext.usage() != null
                ? parentContext.usage()
                : new UsageTracker(providerManager, chatClient, contextWindowCache, clock);
        final var subagentUsageTracker = new SubagentUsageTracker(parentUsage, state.id());
        final var subagentStatusReporter = new StatusReporter();
        final var subagentPermissions = parentContext.permissions() != null
                ? parentContext.permissions().forkForChild()
                : new PermissionService(configuration, emitter, parentWorkdir);

        final var subagentSystemPromptBuilder = new SystemPromptBuilder(
                configuration,
                memoryStore,
                subagentTodoStore,
                subagentPermissions,
                agentProvider,
                providerManager,
                todoPromptResolver,
                mavenRepositoryKnowledge,
                secretRedactor,
                parentWorkdir);

        final var subagentContextView = new ContextView(configuration.compression());
        final var subagentCompactionCoordinator = new CompactionCoordinator(
                configuration,
                chatClient,
                subagentUsageTracker,
                providerManager,
                emitter,
                state.parentSessionId(),
                backoffSleeper,
                parentWorkdir);
        final var subagentContextManager =
                new ContextManager(configuration, subagentUsageTracker, subagentCompactionCoordinator);

        // Borrow the parent boundary: nested agents cannot widen it, and approvals/revocations are shared.
        // Only the root SessionRuntime closes this sandbox.
        final var agentLoop = new AgentLoop(
                chatClient,
                effectiveToolRegistry,
                toolExecutionService,
                configuration,
                subagentPermissions,
                subagentUsageTracker,
                quotaStore,
                subagentContextManager,
                subagentContextView,
                subagentSessionStore,
                subagentSystemPromptBuilder,
                null, // sub-agents never run memory extraction: no user instructions there, and the parent
                // session's extractor (same shared MemoryStore) already captures durable facts surfaced
                // through the sub-agent result; a per-subagent extractor would only add duplicate calls
                subagentStatusReporter,
                emitter,
                mapper,
                structuralCodeGraph,
                providerManager,
                agentProvider,
                clock,
                parentSnapshotStore, // snapshot store can be shared (per-session isolation)
                subagentTodoStore,
                executor,
                state.parentSessionId(),
                state.id(),
                subagentName,
                parentContext.feedback(),
                precisionDurationMarker,
                null,
                null,
                null,
                null,
                null,
                parentWorkdir,
                parentContext.sandbox().approvalView(subagentPermissions));
        agentLoop.subagentBudget(parentContext.subagentBudget());
        SubagentDeliveryBus.get().open(subagentSessionId);
        try {
            // Tool-admitted children use exactly the approved settings, even if the parent changes model
            // while they queue. The fallback is for standalone registry callers without tool admission.
            final var requested = state.model();
            final var baseSettings = agentLoop.chatSettings().inheritFrom(parentContext.settings());
            final var resolver =
                    agentModelResolver == null ? new AgentModelResolver(providerManager) : agentModelResolver;
            final var effectiveModel = state.admittedModel() != null
                    ? state.admittedModel()
                    : resolver.resolve(
                            baseSettings,
                            profile,
                            requested,
                            parentContext.settings() == null || providerManager == null
                                    ? null
                                    : providerManager.provider(parentContext
                                                            .settings()
                                                            .providerId())
                                                    == null
                                            ? null
                                            : providerManager.effectiveSubagentDefault(parentContext.settings()));
            final var subagentSettings = effectiveModel.settings();
            agentLoop.subagentScope(
                    state.spec().subagentScope() != null
                            ? state.spec().subagentScope()
                            : new SubagentScope(
                                    1,
                                    subagentSettings.providerId(),
                                    candidate -> true,
                                    candidate -> false,
                                    cancelled));
            agentLoop.updateChatSettings(subagentSettings);
            // The transcript identity must reflect this child's effective provider/model, even when
            // the UI created the transcript before model resolution finished.
            subagentRegistry.setTranscriptIdentity(
                    state.id(),
                    effectiveModel.resolved() != null
                                    && effectiveModel.resolved().provider() != null
                            ? effectiveModel.resolved().provider().id()
                            : subagentSettings.providerId(),
                    effectiveModel.reference());
            agentLoop.agentModelTuning(profile == null ? null : profile.model());
            agentLoop.skipConfiguredAgentModel(true);
            agentLoop.allowedTools(effectiveAllowedTools);
            final var configuredCap = configuration.subagents() == null
                    ? null
                    : configuration.subagents().maxIterations();
            final var maxIterationsCap =
                    resolveMaxIterationsCap(configuredCap, configuration.llm().maxIterations(), state.maxTurns());
            agentLoop.maxIterationsCap(maxIterationsCap);

            final var fullConversation = new StringBuilder();
            fullConversation
                    .append("System: ")
                    .append(subagentSystemPromptBuilder.build(state.task(), profile))
                    .append("\n\n");
            fullConversation.append("User: ").append(state.task()).append("\n\n");

            final BooleanSupplier subagentCancelled = () -> cancelled.getAsBoolean() || state.isCancelled();
            final var result =
                    agentLoop.run(state.task(), state.profile(), subagentSessionId, List.of(), subagentCancelled);

            fullConversation
                    .append("Assistant: ")
                    .append(result != null ? result : "[no result]")
                    .append("\n");

            // Extract artifacts from subagent's session (file paths from write/edit/apply_patch tools).
            final var artifacts = extractArtifacts(subagentSessionStore, subagentSessionId);
            subagentUsageTracker.mergeToParent();

            if (result == null) {
                return nullRunResult(
                        fullConversation.toString(),
                        state.isCancelled() || cancelled.getAsBoolean(),
                        agentLoop.iterationLimitReached());
            }
            return SubagentRegistry.SubagentResult.success(result, fullConversation.toString(), artifacts);
        } finally {
            agentLoop.close();
            subagentRegistry.cancelByParentSession(subagentSessionId);
            SubagentDeliveryBus.get().close(subagentSessionId);
        }
    }

    private Set<String> normalizeRequestedTools(
            final Set<String> requested, final Set<String> registered, final Set<String> available) {
        if (requested == null) {
            return null;
        }
        // Models can copy the presentation namespace into allowlist arguments. Only alias known,
        // usable targets; real registered names take precedence, even when currently unusable.
        return requested.stream()
                .map(name -> {
                    if (!registered.contains(name) && name.startsWith("functions.")) {
                        final var bareName = name.substring("functions.".length());
                        if (!bareName.startsWith("functions.") && available.contains(bareName)) {
                            return bareName;
                        }
                    }
                    return name;
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    @SafeVarargs
    static Set<String> intersectToolCeilings(final Set<String>... ceilings) {
        HashSet<String> result = null;
        for (final var ceiling : ceilings) {
            if (ceiling == null) {
                continue;
            }
            if (result == null) {
                result = new HashSet<>(ceiling);
            } else {
                result.retainAll(ceiling);
            }
        }
        return result == null ? null : Set.copyOf(result);
    }

    static int resolveMaxIterationsCap(final Integer configuredCap, final int globalCap, final Integer requestedCap) {
        var resolved = configuredCap == null || configuredCap < 1 ? globalCap : configuredCap;
        if (requestedCap != null) {
            resolved = Math.min(resolved, requestedCap);
        }
        return resolved;
    }

    static SubagentRegistry.SubagentResult nullRunResult(
            final String conversation, final boolean cancelled, final boolean iterationLimitReached) {
        if (cancelled) {
            return SubagentRegistry.SubagentResult.cancelled(conversation);
        }
        if (iterationLimitReached) {
            return SubagentRegistry.SubagentResult.budgetExhausted(
                    conversation, "Maximum subagent iteration budget reached");
        }
        return SubagentRegistry.SubagentResult.error("Subagent completed without result", conversation);
    }

    private List<String> extractArtifacts(final SessionStore sessionStore, final String sessionId) {
        final var messages = sessionStore.load(sessionId);
        // tool messages never carry toolCalls(): the calls live on the assistant message that
        // declared them. Only keep calls whose result actually landed (skip interrupted/lost).
        final var landed = messages.stream()
                .filter(m -> "tool".equals(m.role()) && m.toolCallId() != null)
                .map(Message::toolCallId)
                .collect(Collectors.toSet());
        return messages.stream()
                .filter(m -> "assistant".equals(m.role()))
                .filter(m -> m.toolCalls() != null)
                .flatMap(m -> m.toolCalls().stream())
                .filter(tc -> tc.function() != null)
                .filter(tc -> Set.of("write_file", "edit_file", "apply_patch")
                        .contains(tc.function().name()))
                .filter(tc -> landed.contains(tc.id()))
                .map(tc -> {
                    try {
                        final var args =
                                mapper.fromString(Map.class, tc.function().arguments());
                        return args.get("path") instanceof String p ? p : null;
                    } catch (final Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
