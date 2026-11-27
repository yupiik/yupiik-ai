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

import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentModelResolver;
import io.yupiik.ia.agent.core.agent.AgentRunSpec;
import io.yupiik.ia.agent.core.agent.EffectiveAgentModel;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.agent.SubagentRoutingPolicy;
import io.yupiik.ia.agent.core.agent.SubagentRunner;
import io.yupiik.ia.agent.core.agent.SubagentScope;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class SpawnSubagentTool implements EmbeddedTool {
    private final SubagentRegistry subagentRegistry;
    private final SubagentRunner subagentRunner;
    private final SessionStore sessionStore;
    private final SnapshotStore snapshotStore;
    private final CodingAgentConfiguration configuration;
    private final ProviderManager providerManager;
    private final AgentModelResolver agentModelResolver;

    public SpawnSubagentTool(
            final SubagentRegistry subagentRegistry,
            final SubagentRunner subagentRunner,
            final SessionStore sessionStore,
            final SnapshotStore snapshotStore,
            final CodingAgentConfiguration configuration,
            final ProviderManager providerManager,
            final AgentModelResolver agentModelResolver) {
        this.subagentRegistry = subagentRegistry;
        this.subagentRunner = subagentRunner;
        this.sessionStore = sessionStore;
        this.snapshotStore = snapshotStore;
        this.configuration = configuration;
        this.providerManager = providerManager;
        this.agentModelResolver = agentModelResolver;
    }

    public SpawnSubagentTool(
            final SubagentRegistry subagentRegistry,
            final SubagentRunner subagentRunner,
            final SessionStore sessionStore,
            final SnapshotStore snapshotStore,
            final CodingAgentConfiguration configuration,
            final ProviderManager providerManager) {
        this(subagentRegistry, subagentRunner, sessionStore, snapshotStore, configuration, providerManager, null);
    }

    public SpawnSubagentTool(
            final SubagentRegistry subagentRegistry,
            final SubagentRunner subagentRunner,
            final SessionStore sessionStore,
            final SnapshotStore snapshotStore,
            final CodingAgentConfiguration configuration) {
        this(subagentRegistry, subagentRunner, sessionStore, snapshotStore, configuration, null, null);
    }

    protected SpawnSubagentTool() {
        this.subagentRegistry = null;
        this.subagentRunner = null;
        this.sessionStore = null;
        this.snapshotStore = null;
        this.configuration = null;
        this.providerManager = null;
        this.agentModelResolver = null;
    }

    @Override
    public String name() {
        return "spawn_subagent";
    }

    @Override
    public String description(final Path workdir) {
        return "Launch a sub-agent to work on a task in parallel with isolated state. "
                + "It inherits the parent's project context and permission ceiling, and has its own "
                + "conversation history, permission grants, todo list, and snapshots. Optional agent profiles "
                + "and the tools allowlist can further restrict which tools the child may use. Use list_subagent_options "
                + "to discover configured profiles and model routes when needed. Delegate clear, independent tasks "
                + "with a concrete expected result, then collect the result with check_subagents (collect waits until "
                + "the sub-agent finishes and returns its summary). "
                + "Do NOT sleep, poll, or repeatedly check on a sub-agent's progress: you will be notified automatically "
                + "when it finishes. Avoid working on the same files or topics the sub-agent is using while it runs, and "
                + "work on non-overlapping tasks or summarize to the user what you launched. "
                + "max_turns sets a positive upper bound on the child's model round-trips; a non-positive or omitted "
                + "max_turns disables the per-spawn override so the configured subagent/global/provider limit applies. "
                + "Configured subagent and provider/model limits may lower a positive value. Returns a subagent_id for tracking.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "task", Schemas.str("The task description for the sub-agent to execute."),
                        "name", Schemas.str("Optional name for the sub-agent (for display/logging)."),
                        "agent", Schemas.str("Optional named agent profile whose prompt and tool restrictions apply."),
                        "model",
                                Schemas.str(
                                        "Optional model (or $provider/model) routing this sub-agent to a specific "
                                                + "model/provider instead of inheriting the parent session's selection or the "
                                                + "provider's subagents.defaultModel. Bare names stay on the active session provider; "
                                                + "cross-provider routing requires explicit $provider/model. The effective default or "
                                                + "enabled exact includes authorize routes; otherwise dedicated user approval is required "
                                                + "even in YOLO. Discovery/profile selection does not authorize execution; hard excludes cannot be overridden."),
                        "max_turns",
                                Schemas.intProp(
                                        "Maximum model round-trips for the sub-agent; a non-positive or omitted value means no per-spawn override, so the configured subagent/global/provider limit applies."),
                        "tools",
                                Schemas.arrayOf(
                                        "Optional allowlist of bare tool names (for example, read or bash, not functions.read) "
                                                + "for the sub-agent; an empty list allows no tools.",
                                        Schemas.str("Tool name"))),
                "task");
    }

    /**
     * The sub-agent inherits the spawning session: its parent todo store and
     * parent session id come from the context so a sub-agent spawned in tab B
     * never sees tab A's state.
     */
    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var parentTodos = context != null ? context.todos() : null;
        if (parentTodos == null) {
            return ToolResult.error("spawn_subagent requires an active session todo list.");
        }
        if (!subagentRegistry.enabled()) {
            return ToolResult.error("Sub-agents are disabled (yca.subagents.enabled=false or concurrency limit is 0).");
        }
        final var task = arg(args, "task");
        if (task == null || task.isBlank()) {
            return ToolResult.error("task is required");
        }
        final var name = arg(args, "name");
        final var profile = arg(args, "agent");
        final var model = arg(args, "model");
        final var profileName = profile == null || profile.isBlank() ? null : profile.strip();
        final var selectedProfile = profileName == null || configuration.agents() == null
                ? null
                : configuration.agents().stream()
                        .filter(a -> profileName.equals(
                                a.name() == null ? null : a.name().strip()))
                        .findFirst()
                        .orElse(null);
        if (profileName != null && selectedProfile == null) {
            return ToolResult.error("Unknown agent profile: " + profileName);
        }
        final var normalizedModel = model == null ? null : model.strip();
        final var policy =
                configuration.tools() == null ? null : configuration.tools().spawnSubagent();
        if (policy != null && profileName != null && !policy.allowsAgent(profileName)) {
            return ToolResult.error("Agent profile is not available for subagents: " + profileName);
        }
        final EffectiveAgentModel effective;
        final SubagentScope scope;
        try {
            final var routing = new SubagentRoutingPolicy(configuration, providerManager, agentModelResolver, context);
            if (context.subagentId() != null
                    && (context.subagentScope() == null
                            || context.subagentScope().depth() >= routing.maxDepth())) {
                return ToolResult.error("Provider subagents.maxDepth reached (" + routing.maxDepth()
                        + "). Nested sub-agent spawn refused.");
            }
            effective = routing.select(selectedProfile, normalizedModel);
            if (context.subagentId() != null
                    && !routing.parent()
                            .settings()
                            .providerId()
                            .equals(effective.settings().providerId())) {
                return ToolResult.error("Nested sub-agents must stay on their parent's provider.");
            }
            if (!routing.admit(effective, normalizedModel != null && !normalizedModel.isBlank())) {
                return ToolResult.error(
                        "Model is not available for subagents or user approval was not granted: "
                                + effective.routedReference()
                                + ". Use the configured default or an authorized option from list_subagent_options. "
                                + "Only an explicit model request can ask for destination approval; hard denies cannot be overridden.");
            }
            scope = routing.childScope(effective);
        } catch (final IllegalArgumentException invalid) {
            return ToolResult.error(invalid.getMessage());
        }
        // a non-positive or missing max_turns disables the per-spawn override: the child falls back to the
        // configured yca.subagents.maxIterations / global / provider default (null = no per-run cap)
        final var maxTurnsObj = args.get("max_turns");
        final Integer maxTurns = maxTurnsObj instanceof Number n && n.intValue() > 0 ? n.intValue() : null;
        final Set<String> allowedTools = args.get("tools") instanceof List<?> list
                ? list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .collect(Collectors.toUnmodifiableSet())
                : args.containsKey("tools") ? Set.of() : null;
        if (context.isCancelled()) {
            return ToolResult.error("Sub-agent spawn cancelled before admission.");
        }
        final var start = subagentRegistry.start(
                new AgentRunSpec(
                        context.sessionId(),
                        task,
                        name,
                        profileName,
                        normalizedModel,
                        maxTurns,
                        allowedTools,
                        context.allowedTools(),
                        effective.resolved().provider().id(),
                        context.subagentBudget(),
                        effective,
                        scope),
                effective.resolved().provider().id(),
                context.subagentBudget());
        if (!start.admitted()) {
            return ToolResult.error(refusalMessage(start.reason(), effective));
        }
        final var state = start.state();

        // the parent's probe, so an interrupt (Ctrl+C) or a cancelled turn stops the child too:
        // it would otherwise keep burning tokens on an abandoned task. SubagentRunner also ORs the
        // registry latch, so an explicit cancel_subagent keeps working.
        subagentRunner.runAsync(state, sessionStore, snapshotStore, parentTodos, context, context::isCancelled);

        final var displayName = state.name() != null ? state.name() : state.id();
        return ToolResult.ok("Sub-agent started: " + displayName + " (task: " + task + ")");
    }

    /**
     * Actionable hint for the LLM explaining which of the two admission limits refused the spawn,
     * naming the limiting factor (the target provider's N-1 cap or the session's sub-agent cap).
     */
    private String refusalMessage(final SubagentRegistry.RefusalReason reason, final EffectiveAgentModel effective) {
        final var providerId = effective == null || effective.settings() == null
                ? null
                : effective.settings().providerId();
        return switch (reason) {
            case DISABLED -> "Sub-agents are disabled (yca.subagents.enabled=false or concurrency limit is 0).";
            case PARENT_CANCELLED -> "Sub-agent spawn cancelled: the parent is no longer running.";
            case PROVIDER_FORBIDDEN ->
                "Cannot start sub-agent on provider '" + providerId
                        + "': its maxConcurrent is 1, reserving the only slot for the main session. Route the task"
                        + " to another permitted destination or raise the provider's maxConcurrent."
                        + " Alternative destinations still require routing permission or user consent.";
            case PROVIDER_FULL ->
                "Cannot start sub-agent on provider '" + providerId
                        + "': it already runs its sub-agent cap (maxConcurrent - 1 concurrent children). Wait for one"
                        + " to finish or use another permitted destination.";
            case SESSION_FULL ->
                "Cannot start sub-agent: this session already reached its"
                        + " yca.subagents.maxConcurrent sub-agents. Wait for one to complete before spawning another.";
        };
    }
}
