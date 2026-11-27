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
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single hub between the agent event bus and the running shells. Each command
 * registers its {@link ShellEnv} for the session it drives and unregisters it
 * when it stops. Agent events are routed strictly by their {@code sessionId} -
 * every producer tags its events - so several sessions can run concurrently in
 * one process (one tab each) without their streams crossing. An untagged event
 * or one for an unknown session is dropped defensively: there is no implicit
 * fallback shell. Registration and unregistration are broadcast on the event
 * bus so stateful observers can reset themselves.
 */
@ApplicationScoped
public class ShellRegistry {
    private final Emitter emitter;
    private final Map<String, ShellEnv> shellsBySession = new ConcurrentHashMap<>();
    private volatile SubagentRegistry subagentRegistry;
    private volatile boolean isolateSubagents;

    public ShellRegistry(final Emitter emitter) {
        this(emitter, null);
    }

    public ShellRegistry(final Emitter emitter, final SubagentRegistry subagentRegistry) {
        this.emitter = emitter;
        this.subagentRegistry = subagentRegistry;
    }

    /**
     * Binds the process-wide subagent registry so subagent-tagged events are
     * captured into the per-subagent transcripts the TUI renders. Optional:
     * without it the subagent streams are simply dropped from the parent
     * (which is the pre-existing routing behavior).
     */
    public void bindSubagents(final SubagentRegistry subagentRegistry) {
        this.subagentRegistry = subagentRegistry;
        this.isolateSubagents = true;
    }

    /**
     * @return the shell owning {@code sessionId}, or {@code null} when this
     *         session has no running shell.
     */
    public ShellEnv active(final String sessionId) {
        return sessionId == null ? null : shellsBySession.get(sessionId);
    }

    /** @return the shell owning {@code sessionId} - same strict lookup as {@link #active(String)}. */
    public ShellEnv resolve(final String sessionId) {
        return active(sessionId);
    }

    public void register(final String sessionId, final ShellEnv env) {
        if (sessionId == null) {
            throw new IllegalArgumentException("a shell must be registered under its session id");
        }
        shellsBySession.put(sessionId, env);
        emitter.emit(new ShellRegistered(env));
    }

    public void register(final ShellEnv env) {
        register(sessionIdOf(env), env);
    }

    public void unregister(final String sessionId, final ShellEnv env) {
        if (sessionId != null && shellsBySession.remove(sessionId, env)) {
            emitter.emit(new ShellUnregistered(env));
        }
    }

    public void unregister(final ShellEnv env) {
        unregister(sessionIdOf(env), env);
    }

    /** The sessions currently driven by a shell. */
    public Collection<String> sessions() {
        return List.copyOf(shellsBySession.keySet());
    }

    /** Forwards an agent event to the shell owning its session, no-op when unknown or untagged. */
    public void onEvent(@OnEvent final AgentEvent event) {
        // Capture child streams for /subagents in every shell. The TUI additionally
        // isolates them from the parent tab; CLI/run retain live forwarding.
        // Lifecycle markers always reach the parent.
        final var subagents = subagentRegistry;
        if (subagents != null
                && event.subagentId() != null
                && !(event instanceof AgentEvent.SubagentStarted)
                && !(event instanceof AgentEvent.SubagentCompleted)) {
            subagents.transcriptOf(event.subagentId()).onEvent(event);
            if (isolateSubagents) {
                return;
            }
        }
        final var env = active(event.sessionId());
        if (env != null) {
            env.frontend().onEvent(event);
        }
    }

    private static String sessionIdOf(final ShellEnv env) {
        return env == null || env.session() == null ? null : env.session().sessionId();
    }
}
