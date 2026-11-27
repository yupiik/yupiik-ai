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
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

@ApplicationScoped
public class CheckSubagentsTool implements EmbeddedTool {
    /**
     * Default bound on a blocking {@code collect} wait (ms). The model may override per call through
     * the {@code max_wait_ms} arg; a hung sub-agent can never pin the parent turn beyond this (the
     * call returns a {@code pending} result instead).
     */
    private static final long DEFAULT_MAX_WAIT_MS = 30_000L;

    private static final long MAX_WAIT_CAP_MS = 300_000L;

    private final SubagentRegistry subagentRegistry;
    private final JsonMapper mapper;

    public CheckSubagentsTool(final SubagentRegistry subagentRegistry, final JsonMapper mapper) {
        this.subagentRegistry = subagentRegistry;
        this.mapper = mapper;
    }

    protected CheckSubagentsTool() {
        this.subagentRegistry = null;
        this.mapper = null;
    }

    @Override
    public String name() {
        return "check_subagents";
    }

    @Override
    public String description(final Path workdir) {
        return "Monitor and manage spawned sub-agents. "
                + "Actions: 'poll' (list active + status), 'collect' (block up to max_wait_ms, default "
                + DEFAULT_MAX_WAIT_MS / 1000
                + "s, until the sub-agent finishes, then return its summary; returns status='pending' if the wait "
                + "expires), 'cancel' (terminate). "
                + "Prefer 'collect' over 'poll': it waits for the result instead of forcing you to retry.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "action", Schemas.str("Action to perform: 'poll', 'collect', or 'cancel'."),
                        "subagent_id", Schemas.str("Sub-agent ID (required for 'collect' and 'cancel')."),
                        "max_wait_ms",
                                Schemas.str("Max milliseconds to block a 'collect' for a still-running "
                                        + "sub-agent (default " + DEFAULT_MAX_WAIT_MS + ").")),
                "action");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        if (!subagentRegistry.enabled()) {
            return ToolResult.error("Sub-agents are disabled.");
        }
        final var action = arg(args, "action");
        if (action == null || action.isBlank()) {
            return ToolResult.error("action is required (poll, collect, cancel)");
        }

        return switch (action) {
            case "poll" -> poll();
            // collect may block: honour the running turn's cancellation probe so an Esc/interrupt
            // during the wait surfaces as a cancellation of the collect, never as a spurious failure
            case "collect" -> collect(arg(args, "subagent_id"), maxWaitMs(args), context.cancelled());
            case "cancel" -> cancel(arg(args, "subagent_id"));
            default -> ToolResult.error("Invalid action: " + action + " (must be poll, collect, or cancel)");
        };
    }

    private long maxWaitMs(final Map<String, Object> args) {
        final var raw = arg(args, "max_wait_ms");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_WAIT_MS;
        }
        try {
            return Math.min(Math.max(0L, Long.parseLong(raw)), MAX_WAIT_CAP_MS);
        } catch (final NumberFormatException e) {
            return DEFAULT_MAX_WAIT_MS;
        }
    }

    private ToolResult poll() {
        final var active = subagentRegistry.listActive();
        final var list = new ArrayList<Map<String, Object>>();
        for (final var state : active) {
            list.add(Map.of(
                    "id", state.id(),
                    "task", state.task(),
                    "started_at", state.startedAt(),
                    "status", state.isDone() ? (state.isCancelled() ? "cancelled" : "completed") : "running"));
        }
        try {
            return ToolResult.ok(mapper.toString(Map.of("subagents", list)));
        } catch (final RuntimeException e) {
            return ToolResult.error("Failed to serialize result: " + e.getMessage());
        }
    }

    private ToolResult collect(final String subagentId, final long maxWaitMs, final BooleanSupplier cancelled) {
        if (subagentId == null || subagentId.isBlank()) {
            return ToolResult.error("subagent_id is required for collect action");
        }
        final var stateOpt = subagentRegistry.get(subagentId);
        if (stateOpt.isEmpty()) {
            return ToolResult.error("Sub-agent not found: " + subagentId);
        }
        final var state = stateOpt.get();
        // Wait for a still-running sub-agent up to maxWaitMs. The cancellation probe is the *turn*
        // probe (not per-call) so the whole turn's interrupt terminates the wait; we never `cancel`
        // the child here - that is owned by the parent's turn interrupt (cancelByParentSession).
        if (!state.isDone()) {
            final long deadline = System.nanoTime() + maxWaitMs * 1_000_000L;
            while (!state.isDone() && System.nanoTime() < deadline) {
                if (cancelled.getAsBoolean()) {
                    try {
                        return ToolResult.ok(mapper.toString(
                                Map.of("subagent_id", subagentId, "status", "cancelled", "completed", false)));
                    } catch (final RuntimeException e) {
                        return ToolResult.error("Failed to serialize result: " + e.getMessage());
                    }
                }
                sleep(50L);
            }
            if (!state.isDone()) {
                // wait expired: NOT an error - a neutral "still running" the model can act on
                try {
                    return ToolResult.pending(
                            mapper.toString(
                                    Map.of(
                                            "subagent_id",
                                            subagentId,
                                            "status",
                                            "pending",
                                            "waited_ms",
                                            maxWaitMs,
                                            "hint",
                                            "The sub-agent is still running. You will be notified automatically when it finishes; "
                                                    + "you may proceed with other work or call collect again with a longer max_wait_ms.")));
                } catch (final RuntimeException e) {
                    return ToolResult.error("Failed to serialize result: " + e.getMessage());
                }
            }
        }
        // Now try to collect (will remove state)
        final var resultOpt = subagentRegistry.collect(subagentId);
        if (resultOpt.isEmpty()) {
            return ToolResult.error("Sub-agent has no result: " + subagentId);
        }
        return result(resultOpt.get(), subagentId);
    }

    private ToolResult result(final SubagentRegistry.SubagentResult result, final String subagentId) {
        if (result.success()) {
            try {
                return ToolResult.ok(mapper.toString(Map.of(
                        "subagent_id",
                        subagentId,
                        "summary",
                        result.summary(),
                        "artifacts",
                        result.artifacts(),
                        "status",
                        result.status().name().toLowerCase(Locale.ROOT),
                        "completed",
                        true)));
            } catch (final RuntimeException e) {
                return ToolResult.error("Failed to serialize result: " + e.getMessage());
            }
        }
        try {
            return ToolResult.error(mapper.toString(Map.of(
                    "subagent_id",
                    subagentId,
                    "error",
                    result.error(),
                    "status",
                    result.status().name().toLowerCase(Locale.ROOT),
                    "completed",
                    true)));
        } catch (final RuntimeException e) {
            return ToolResult.error("Sub-agent failed: " + result.error());
        }
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ToolResult cancel(final String subagentId) {
        if (subagentId == null || subagentId.isBlank()) {
            return ToolResult.error("subagent_id is required for cancel action");
        }
        final var stateOpt = subagentRegistry.get(subagentId);
        if (stateOpt.isEmpty()) {
            return ToolResult.error("Sub-agent not found: " + subagentId);
        }
        final var state = stateOpt.get();
        if (state.isDone()) {
            try {
                return ToolResult.ok(mapper.toString(
                        Map.of("subagent_id", subagentId, "cancelled", false, "reason", "already completed")));
            } catch (final RuntimeException e) {
                return ToolResult.error("Failed to serialize result: " + e.getMessage());
            }
        }
        subagentRegistry.cancel(subagentId);
        try {
            return ToolResult.ok(mapper.toString(Map.of("subagent_id", subagentId, "cancelled", true)));
        } catch (final RuntimeException e) {
            return ToolResult.error("Failed to serialize result: " + e.getMessage());
        }
    }
}
