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

/**
 * Presentation side of a shell: the only channel commands and actions use to
 * render data. Implementations (ANSI terminal, TamboUI, headless) must be
 * thread-safe: actions can run on worker threads while the frontend drives
 * rendering. Frontends are views - they never own conversation state, they
 * only display what they are given or what shared state exposes.
 */
public interface ShellFrontend {
    /** Transient one-line progress/status update (status bar or inline line). */
    void status(String message);

    /** Durable user-visible non-conversation notice (info, warning or error). */
    void notice(AgentEvent.Notice.Level level, String message);

    /** Appends a markdown fragment to the conversation view. */
    void transcript(String markdown);

    /** Renders an error to the user (stderr for terminal shells). */
    void error(String message);

    /** Reflects the "show thinking" toggle of {@link ShellSession}. */
    void showThinking(boolean value);

    /** Reflects the "tool output visibility" toggle of {@link ShellSession}. */
    void showToolOutput(ToolOutputVisibility value);

    /** Reflects the diff layout selection, independently of tool-output visibility. */
    default void diffViewMode(final DiffViewMode value) {}

    /**
     * Shows a busy/busy-with-label indicator (spinner, status line, ...).
     * Default: no-op, frontends without an activity indicator ignore it.
     */
    default void activity(final String label) {}

    /** Hides the busy indicator. Default: no-op. */
    default void idle() {}

    /**
     * Prepares the display for a running turn (reset buffers, start the
     * activity indicator, ...). Default: no-op.
     */
    default void beginTurn() {}

    /** Closes the turn display (flush buffers, stop the indicator, ...). Default: no-op. */
    default void endTurn() {}

    /**
     * Clears the display area (terminal escape, canvas reset, ...). The
     * conversation state itself is reset by the caller through the transcript.
     * Default: no-op.
     */
    default void clear() {}

    /**
     * Renders a replayed conversation (session resume). The replay content is
     * already appended to the shared transcript by the caller, so
     * transcript-backed frontends (TUI) keep the default no-op while printing
     * frontends (CLI) override this to show it.
     */
    default void onReplay(final String markdown) {}

    /**
     * Asks the frontend to open {@code url} in an external browser. Console frontends (TUI, CLI)
     * open the system browser when their {@code autoOpenBrowser} option is enabled; in-browser
     * frontends (web) keep the default no-op because the URL is already clickable in a tab. This is
     * the channel used for generated login/authorization URLs, called in addition to
     * {@link #transcript(String)} which still surfaces the clickable link for reference.
     *
     * @param url the URL to open.
     */
    default void openExternalUrl(final String url) {}

    /**
     * Renders a streamed agent event. The default routes the generic channels
     * (status, notice, error); richer frontends override to also render
     * streaming text, tool cards, usage, ... Note that shared conversation
     * state is updated by its own observers, this is only about displaying.
     */
    default void onEvent(final AgentEvent event) {
        if (event
                instanceof
                AgentEvent.Status(String message, String sessionId, String subagentId, String subagentName, var _)) {
            status(message);
        } else if (event
                instanceof
                AgentEvent.Pause(
                        var kind,
                        String message,
                        long durationMs,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        var _)) {
            status(message);
        } else if (event
                instanceof
                AgentEvent.Notice(
                        var level,
                        String message,
                        var finishReason,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        Throwable cause,
                        var compaction,
                        var _)) {
            notice(level, message);
        } else if (event
                instanceof
                AgentEvent.Error(String message, String sessionId, String subagentId, String subagentName, var _)) {
            error(message);
        }
    }
}
