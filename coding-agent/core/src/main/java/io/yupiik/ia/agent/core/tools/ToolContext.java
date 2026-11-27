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
package io.yupiik.ia.agent.core.tools;

import io.yupiik.ia.agent.core.agent.SubagentBudget;
import io.yupiik.ia.agent.core.agent.SubagentScope;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.Sandbox;
import io.yupiik.ia.agent.core.todo.TodoStore;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The per-session services a tool may need, passed explicitly down the call
 * chain (never looked up from a thread-local). Tools execute synchronously on
 * the turn thread, so the context the {@code AgentLoop} was created with is
 * simply forwarded to
 * {@link EmbeddedTool#execute(java.util.Map, ToolContext)}.
 *
 * <p>Project-sensitive tools always resolve against the required session root.</p>
 *
 * @param sessionId      the session (tab) owning the running turn
 * @param subagentId     the subagent id when the turn runs in a subagent, else null
 * @param subagentName   the subagent display name when in a subagent, else null
 * @param permissions    the asking session's permission state (its own mode)
 * @param feedback       the asking session's feedback service (its gate renders in that tab)
 * @param todos          the asking session's todo list
 * @param statusReporter the asking session's status reporter (optimization notices land in that tab)
 * @param usage          the asking session's usage tracker (subagent usage merges into it)
 * @param contextView    the asking session's context view (status/compact requests route to that tab)
 * @param cancelled      cancellation probe of the running turn; a tool may poll it and stop its
 *                       work early (killing a subprocess for example). Never {@code null}: a
 *                       context no turn owns passes {@link Cancellations#NEVER}.
 * @param settings       the asking session's chat settings (provider/model selection); may be null
 * @param callCancelled  per-call cancellation probe: distinct from {@code cancelled} (the turn probe)
 *                       so a UI can cancel the running tool alone while the turn keeps going. Never
 *                       {@code null}: standalone contexts report "not cancelled".
 * @param allowedTools   effective tool ceiling for the current agent; null means the registry and permission policy
 *                       determine availability.
 * @param toolRegistry   borrowed registry of the owning loop, including session tools; null only for standalone
 *                       contexts using the application's default registry. Never closed by delegated work.
 * @param sandbox        required borrowed session boundary; descendants share approvals and revocations
 * @param subagentScope  immutable delegation ancestry and route ceiling; null for root or standalone contexts
 */
public record ToolContext(
        String sessionId,
        String subagentId,
        String subagentName,
        PermissionService permissions,
        UserFeedbackService feedback,
        TodoStore todos,
        StatusReporter statusReporter,
        UsageTracker usage,
        ContextView contextView,
        BooleanSupplier cancelled,
        ChatSettings settings,
        BooleanSupplier callCancelled,
        // required absolute normalized per-session working/project directory the tools resolve file paths against
        Path projectDir,
        Sandbox sandbox,
        // the per-call tool ordinal (the TUI/UI card number) when a turn owns this call; -1 when the
        // context is not tied to a specific running tool call (standalone/btw invocations). Tools that
        // emit their own streaming fragments (e.g. BashTool's ToolStream) surface it so every fragment
        // correlates to the exact started call, never to a same-named sibling.
        long ordinal,
        // effective tool ceiling of the current agent, propagated to prevent delegation from widening access
        Set<String> allowedTools,
        // the spawning session's sub-agent admission budget (per-session cap); null when the context
        // belongs to no session-capable owner (standalone/btw invocations); shared by descendants
        SubagentBudget subagentBudget,
        ToolRegistry toolRegistry,
        SubagentScope subagentScope) {

    /** Standalone contexts without delegation ancestry. */
    public ToolContext(
            final String sessionId,
            final String subagentId,
            final String subagentName,
            final PermissionService permissions,
            final UserFeedbackService feedback,
            final TodoStore todos,
            final StatusReporter statusReporter,
            final UsageTracker usage,
            final ContextView contextView,
            final BooleanSupplier cancelled,
            final ChatSettings settings,
            final BooleanSupplier callCancelled,
            final Path projectDir,
            final Sandbox sandbox,
            final long ordinal,
            final Set<String> allowedTools,
            final SubagentBudget subagentBudget,
            final ToolRegistry toolRegistry) {
        this(
                sessionId,
                subagentId,
                subagentName,
                permissions,
                feedback,
                todos,
                statusReporter,
                usage,
                contextView,
                cancelled,
                settings,
                callCancelled,
                projectDir,
                sandbox,
                ordinal,
                allowedTools,
                subagentBudget,
                toolRegistry,
                null);
    }

    /** Convenience: a full context (per-session services + settings + project dir) for a non-tool context. */
    public ToolContext(
            final String sessionId,
            final String subagentId,
            final String subagentName,
            final PermissionService permissions,
            final UserFeedbackService feedback,
            final TodoStore todos,
            final StatusReporter statusReporter,
            final UsageTracker usage,
            final ContextView contextView,
            final BooleanSupplier cancelled,
            final ChatSettings settings,
            final BooleanSupplier callCancelled,
            final Path projectDir,
            final Sandbox sandbox) {
        this(
                sessionId,
                subagentId,
                subagentName,
                permissions,
                feedback,
                todos,
                statusReporter,
                usage,
                contextView,
                cancelled,
                settings,
                callCancelled,
                projectDir,
                sandbox,
                -1,
                null,
                null,
                null);
    }

    /** Convenience: a full context plus the session sub-agent budget (spawn-capable sessions). */
    public ToolContext(
            final String sessionId,
            final String subagentId,
            final String subagentName,
            final PermissionService permissions,
            final UserFeedbackService feedback,
            final TodoStore todos,
            final StatusReporter statusReporter,
            final UsageTracker usage,
            final ContextView contextView,
            final BooleanSupplier cancelled,
            final ChatSettings settings,
            final BooleanSupplier callCancelled,
            final Path projectDir,
            final Sandbox sandbox,
            final SubagentBudget subagentBudget) {
        this(
                sessionId,
                subagentId,
                subagentName,
                permissions,
                feedback,
                todos,
                statusReporter,
                usage,
                contextView,
                cancelled,
                settings,
                callCancelled,
                projectDir,
                sandbox,
                -1,
                null,
                subagentBudget,
                null);
    }

    public ToolContext {
        Objects.requireNonNull(projectDir, "workdir");
        Objects.requireNonNull(sandbox, "sandbox");
        if (!projectDir.isAbsolute() || !projectDir.equals(projectDir.normalize())) {
            throw new IllegalArgumentException("workdir must be absolute and normalized: " + projectDir);
        }
        // a probe is always a real supplier: polling a null one used to surface as an
        // "unexpected failure" NullPointerException in the middle of a turn
        cancelled = Objects.requireNonNull(cancelled, "cancelled");
        callCancelled = callCancelled == null ? cancelled : callCancelled;
        allowedTools = allowedTools == null ? null : Set.copyOf(allowedTools);
    }

    /**
     * A context without any session-scoped service but rooted on a session project directory,
     * matching how a real session on a non-default project root forwards its {@code projectDir}
     * to every tool. Standalone tools exercise their per-session path resolution through this.
     */
    public static ToolContext empty(final Path projectDir, final Sandbox sandbox) {
        return new ToolContext(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Cancellations.NEVER,
                null,
                null,
                projectDir,
                sandbox,
                -1,
                null,
                null,
                null);
    }

    /** Cancellation probe, never {@code null}: standalone contexts report "not cancelled". */
    public boolean isCancelled() {
        return cancelled.getAsBoolean();
    }

    /** Per-call cancellation probe: when it flips, only this tool call should abort. */
    public boolean isCallCancelled() {
        return callCancelled.getAsBoolean();
    }

    /**
     * Resolves a file-path tool argument against this session's root. Absolute paths pass through
     * untouched; relative paths are anchored on the required {@link #projectDir()}. A session rooted on
     * project B writes {@code ./src/X.java} into B, not into the directory the process was launched
     * from - the cross-root split that only {@code bash}/{@code apply_patch} honoured before.
     */
    public Path resolvePath(final String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("path is required");
        }
        final var path = Path.of(raw);
        return path.isAbsolute() ? path : projectDir.resolve(path).normalize();
    }

    /**
     * Whether a relative {@code raw} argument was anchored on this session's {@link #projectDir()}
     * (as opposed to an absolute path). When true, a tool that
     * resolves paths through {@link #resolvePath(String)} is acting on the session root, and
     * may surface that to the caller so the re-rooting is visible rather than silent.
     */
    public boolean reRoutedToSessionRoot(final String raw) {
        return !Path.of(raw).isAbsolute();
    }
}
