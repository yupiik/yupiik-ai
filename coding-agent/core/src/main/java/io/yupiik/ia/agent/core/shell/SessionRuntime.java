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

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.SubagentBudget;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.context.ContextCompression;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.memory.MemoryProductionPolicy;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.Sandbox;
import io.yupiik.ia.agent.core.session.SessionMetadata;
import io.yupiik.ia.agent.core.todo.TodoStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Everything a single session (one TUI tab, the CLI session, a sub-agent) owns:
 * its permission state, feedback gate, usage accounting, todo list, status
 * reporter and agent loop.
 *
 * <p>Instances are created with {@code new} (typically by
 * {@link SessionRuntimeFactory}) and passed by reference - never fetched from a
 * thread-local - so concurrent tabs keep independent state while the process
 * scoped services (chat client, providers, tool registry, session store) stay
 * shared on purpose: they own the single LLM concurrency/rate-limit budget.</p>
 */
public final class SessionRuntime {
    private final AgentLoop loop;
    private final Path workdir;
    private final PermissionService permissions;
    private final UserFeedbackService feedback;
    private final UsageTracker usage;
    private final TodoStore todos;
    private final StatusReporter status;
    private final ContextManager contextManager;
    private final SystemPromptBuilder systemPromptBuilder;
    // the per-session memory-production flag holder + the session id it is keyed by, wired by the
    // SessionRuntimeFactory so a closed session releases its entry (no leak in the singleton)
    private final MemoryProductionPolicy memoryProductionPolicy;
    private final String memoryProductionKey;

    public SessionRuntime(
            final AgentLoop loop,
            final PermissionService permissions,
            final UserFeedbackService feedback,
            final UsageTracker usage,
            final TodoStore todos,
            final StatusReporter status,
            final ContextManager contextManager,
            final SystemPromptBuilder systemPromptBuilder,
            final Path workdir,
            final Sandbox sandbox) {
        this(
                loop,
                permissions,
                feedback,
                usage,
                todos,
                status,
                contextManager,
                systemPromptBuilder,
                null,
                null,
                workdir,
                sandbox);
    }

    SessionRuntime(
            final AgentLoop loop,
            final PermissionService permissions,
            final UserFeedbackService feedback,
            final UsageTracker usage,
            final TodoStore todos,
            final StatusReporter status,
            final ContextManager contextManager,
            final SystemPromptBuilder systemPromptBuilder,
            final MemoryProductionPolicy memoryProductionPolicy,
            final String memoryProductionKey,
            final Path workdir,
            final Sandbox sandbox) {
        this.workdir = Objects.requireNonNull(workdir, "workdir");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.loop = loop;
        this.permissions = permissions;
        permissions.setAgentModeChangeListener(sandbox::revoke);
        permissions.setPermissionModeChangeListener(sandbox::revoke);
        this.feedback = feedback;
        this.usage = usage;
        this.todos = todos;
        this.status = status;
        this.contextManager = contextManager;
        this.systemPromptBuilder = systemPromptBuilder;
        this.memoryProductionPolicy = memoryProductionPolicy;
        this.memoryProductionKey = memoryProductionKey;
    }

    private final Sandbox sandbox;
    private final AtomicBoolean closed = new AtomicBoolean();

    public Sandbox sandbox() {
        return sandbox;
    }

    public Path workdir() {
        return workdir;
    }

    /** Session setup instructions and extra context roots; does not widen this runtime's permission ceiling. */
    public void configureSessionContext(final List<Path> directories, final SessionMetadata.Instructions instructions) {
        if (systemPromptBuilder == null) {
            if (!directories.isEmpty() || instructions != null) {
                throw new IllegalArgumentException("Session context is unavailable");
            }
            return;
        }
        systemPromptBuilder.attach(chatSettings());
        systemPromptBuilder.configureSessionContext(directories, instructions);
    }

    /** The session's own agent loop (turn execution, per-turn metrics). */
    public AgentLoop loop() {
        return loop;
    }

    /** The session's permission state: mode and approval gate. */
    public PermissionService permissions() {
        return permissions;
    }

    /** The session's feedback gate, rendering its questions in its own tab. */
    public UserFeedbackService feedback() {
        return feedback;
    }

    /** The session's own cached quota snapshot; does not trigger a provider refresh. */
    public AgentEvent.UsageUpdated.Quota quotaSnapshot() {
        return loop.cachedQuotaSnapshot();
    }

    /** The session's usage accounting (context anchor, token counters). */
    public UsageTracker usage() {
        return usage;
    }

    /** The session's todo list, persisted under its own key. */
    public TodoStore todos() {
        return todos;
    }

    /** The session's status reporter (optimization notices, indexing progress). */
    public StatusReporter status() {
        return status;
    }

    /** The session's context manager (compaction, context budget). */
    public ContextManager contextManager() {
        return contextManager;
    }

    /** The session's shared context-compression ref source (live refs) for seam-scrub render surfaces. */
    public ContextCompression compression() {
        return loop.compression();
    }

    /** The session's system prompt builder (reflects this session's mode and todos). */
    public SystemPromptBuilder systemPromptBuilder() {
        return systemPromptBuilder;
    }

    /** The session's per-tab provider/model selection (owned by the agent loop). */
    public ChatSettings chatSettings() {
        return loop.chatSettings();
    }

    /**
     * The session's sub-agent admission budget (the {@code yca.subagents.maxConcurrent} per-session
     * cap), owned by the agent loop. Null for loops that cannot spawn children (sub-agent child
     * loops, standalone/btw contexts).
     */
    public SubagentBudget subagentBudget() {
        return loop.subagentBudget();
    }

    /** Applies a new session selection (mid-turn changes take effect from the next round/tool call). */
    public void updateChatSettings(final ChatSettings value) {
        if (systemPromptBuilder != null) {
            systemPromptBuilder.validateChatSettings(value);
        }
        loop.updateChatSettings(value);
    }

    /** Cancellable selection; admission and close checks are owned by the loop, after its hooks. */
    public boolean updateChatSettings(final ChatSettings value, final BooleanSupplier cancelled) {
        if (systemPromptBuilder != null) {
            systemPromptBuilder.validateChatSettings(value);
        }
        return loop.updateChatSettings(value, cancelled);
    }

    /** Attaches the current provider's quota and pushes a UsageUpdated so the TUI/web gauges reflect it
     * even before any turn ran (a fresh tab or an explicit /provider on an already-active provider). */
    public void refreshUsage() {
        loop.refreshUsage();
    }

    /** Releases the session: fires the {@code yca.hooks.SessionEnded} hook once (idempotent like the loop). */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        permissions.subagentRoutes().close();
        try {
            loop.close();
        } finally {
            sandbox.close();
        }
        // release the per-session memory-production flag so the application-scoped policy never leaks
        if (memoryProductionPolicy != null && memoryProductionKey != null && !memoryProductionKey.isBlank()) {
            memoryProductionPolicy.clear(memoryProductionKey);
        }
    }
}
