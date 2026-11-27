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

import io.yupiik.ia.agent.core.configuration.MetricsConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import java.util.Objects;

/**
 * Mutable per-run state shared by all shells: the session coordinates and
 * rendering toggles slash commands change. One instance lives for the
 * duration of a shell execution (a TUI tab, the CLI session) and is carried by
 * {@link ShellEnv}.
 */
public class ShellSession {
    private String sessionId;
    private String activeAgent;
    private volatile boolean showThinking = true;
    private volatile ToolOutputVisibility showToolOutput = ToolOutputVisibility.COLLAPSED;
    private volatile DiffViewMode diffViewMode = DiffViewMode.AUTO;
    private volatile boolean busy;
    private volatile String pendingMode;
    private MetricsConfiguration metrics = new MetricsConfiguration(true, true, MetricsDisplayMode.COMPACT);
    private SessionRuntime runtime;

    /**
     * This shell's session services (permissions, feedback, todos, usage, status, agent loop
     * and model selection), created by {@link SessionRuntimeFactory} and wired by reference:
     * concurrent tabs keep independent state over the shared process services.
     */
    public SessionRuntime runtime() {
        return runtime;
    }

    public ShellSession runtime(final SessionRuntime runtime) {
        this.runtime = runtime;
        return this;
    }

    /**
     * Per-shell model selection, owned by the session's {@link SessionRuntime}
     * (so the agent loop actually runs with it). Null until the runtime is wired.
     */
    public ChatSettings chatSettings() {
        return runtime != null ? runtime.chatSettings() : null;
    }

    /**
     * {@code true} while an agent turn is running; commands that must not run
     * concurrently with a turn guard themselves on it.
     */
    public boolean busy() {
        return busy;
    }

    public ShellSession busy(final boolean busy) {
        this.busy = busy;
        return this;
    }

    /**
     * Mode switch queued while a turn was running: applied by {@link TurnRunner} when the
     * current turn ends, before the next turn can start. Cleared by an explicit
     * {@code /mode} when idle.
     */
    public String pendingMode() {
        return pendingMode;
    }

    public ShellSession pendingMode(final String pendingMode) {
        this.pendingMode = pendingMode;
        return this;
    }

    public String sessionId() {
        return sessionId;
    }

    public ShellSession sessionId(final String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    public String activeAgent() {
        return activeAgent;
    }

    public ShellSession activeAgent(final String activeAgent) {
        this.activeAgent = activeAgent;
        return this;
    }

    public boolean showThinking() {
        return showThinking;
    }

    public ShellSession showThinking(final boolean showThinking) {
        this.showThinking = showThinking;
        return this;
    }

    public ToolOutputVisibility showToolOutput() {
        return showToolOutput;
    }

    public ShellSession showToolOutput(final ToolOutputVisibility showToolOutput) {
        this.showToolOutput = showToolOutput;
        return this;
    }

    public MetricsConfiguration metrics() {
        return metrics;
    }

    public DiffViewMode diffViewMode() {
        return diffViewMode;
    }

    public ShellSession diffViewMode(final DiffViewMode value) {
        this.diffViewMode = Objects.requireNonNull(value, "diffViewMode");
        return this;
    }

    public ShellSession metrics(final MetricsConfiguration metrics) {
        this.metrics = metrics;
        return this;
    }
}
