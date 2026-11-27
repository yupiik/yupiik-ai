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
import io.yupiik.ia.agent.core.cli.ToolPresenter;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Headless frontend of the {@code run} command. Discrete events (tool
 * lifecycle, notices, errors) go through {@code java.util.logging} so levels
 * and inline formatting are handled by the log manager (yupiik-logging-jul);
 * the two continuous streams stay raw - the answer on stdout (it is the
 * command product) and the reasoning stream on stderr when enabled.
 */
public class RunShellFrontend implements ShellFrontend {
    private static final Logger LOGGER = Logger.getLogger(RunShellFrontend.class.getName());

    private final boolean showThinking;
    private final ToolOutputVisibility showToolOutput;
    private final ToolPresenter presenter;
    private final ToolInvocations toolCalls = new ToolInvocations();
    private boolean stderrNeedsNewline;
    /**
     * Actor owning the open (newline-less) stderr line, {@code null} when none:
     * a spawned sub-agent streams on its own thread while the parent turn keeps
     * going, so a thought delta from a different actor must not be appended to
     * the line the current one left mid-thought.
     */
    private String stderrLineOwner;

    public RunShellFrontend(
            final boolean showThinking, final ToolOutputVisibility showToolOutput, final ToolPresenter presenter) {
        this.presenter = presenter;
        this.showThinking = showThinking;
        this.showToolOutput = showToolOutput;
    }

    @Override
    public void status(final String message) {
        ensureStderrNewline();
        LOGGER.info(message);
    }

    @Override
    public void notice(final AgentEvent.Notice.Level level, final String message) {
        ensureStderrNewline();
        log(
                switch (level) {
                    case ERROR -> Level.SEVERE;
                    case WARN -> Level.WARNING;
                    case INFO -> Level.INFO;
                },
                message);
    }

    @Override
    public void transcript(final String markdown) {
        System.out.print(markdown);
    }

    @Override
    public void error(final String message) {
        ensureStderrNewline();
        LOGGER.severe(message);
    }

    @Override
    public void showThinking(final boolean value) {
        // fixed by the run configuration
    }

    @Override
    public void showToolOutput(final ToolOutputVisibility value) {
        // fixed by the run configuration
    }

    @Override
    public synchronized void onEvent(final AgentEvent event) {
        final var prefix = subagentPrefix(event);
        if (event instanceof AgentEvent.FeedbackStarted started) {
            if ("text".equals(started.kind()) || showThinking) {
                ensureStderrNewline();
                final var record = new LogRecord(Level.INFO, prefix + started.kind() + " response started");
                record.setInstant(Instant.ofEpochMilli(started.startedAt()));
                record.setLoggerName(LOGGER.getName());
                LOGGER.log(record);
            }
        } else if (event instanceof final AgentEvent.TextDelta t) {
            System.out.print(prefix + t.delta());
        } else if (event instanceof final AgentEvent.ThinkingDelta t) {
            if (showThinking) {
                // a second voice must not splice into the line the current one left
                // open: close it so both thoughts stay readable
                if (stderrNeedsNewline && !Objects.equals(stderrLineOwner, t.subagentId())) {
                    ensureStderrNewline();
                }
                System.err.print(prefix + t.delta());
                stderrNeedsNewline = !t.delta().endsWith("\n");
                stderrLineOwner = t.subagentId();
            }
        } else if (event instanceof final AgentEvent.ToolStarted t) {
            final var call = toolCalls.start(t);
            if (showToolOutput != ToolOutputVisibility.HIDDEN) {
                ensureStderrNewline();
                LOGGER.info(prefix + call.header() + " started" + call.summary(presenter, showToolOutput));
                if (showToolOutput.shown()) {
                    for (final var line : presenter.request(t.name(), t.args(), Integer.MAX_VALUE)) {
                        LOGGER.info(prefix + "  request: " + line);
                    }
                }
            }
        } else if (event instanceof final AgentEvent.ToolStream t) {
            final var call = toolCalls.find(t.name(), t.ordinal(), t.sessionId(), t.subagentId());
            if (showToolOutput.shown()) {
                ensureStderrNewline();
                for (final var line : call.append(t.fragment())) {
                    LOGGER.info(prefix + call.header() + " output: " + line);
                }
            }
        } else if (event instanceof final AgentEvent.ToolDiagnostic t) {
            ensureStderrNewline();
            final var call = toolCalls.find(t.name(), t.ordinal(), t.sessionId(), t.subagentId());
            // Private display data: bypass JUL and never append to the call's output buffer.
            System.err.println(prefix + call.header() + " diagnostic:\n" + t.detail());
        } else if (event instanceof final AgentEvent.ToolFinished t) {
            final var call = toolCalls.find(t.name(), t.ordinal(), t.sessionId(), t.subagentId());
            final var failure = !t.success() && !t.pending();
            final var execution = t.pending() ? "" : presenter.executionDiagnostic(t.name(), t.output());
            try {
                if (showToolOutput != ToolOutputVisibility.HIDDEN || failure || !execution.isEmpty()) {
                    ensureStderrNewline();
                    if (showToolOutput.shown()) {
                        final var rest = call.flush();
                        if (!rest.isEmpty()) {
                            LOGGER.info(prefix + call.header() + " output: " + rest);
                        }
                    }
                    final var state = t.pending() ? "pending" : t.success() ? "done" : "failed";
                    final var level = !t.success() && !t.pending() ? Level.WARNING : Level.INFO;
                    if (showToolOutput != ToolOutputVisibility.HIDDEN || failure) {
                        log(level, prefix + call.header() + " " + state);
                    }
                    if (!execution.isEmpty()) {
                        log(Level.WARNING, prefix + call.header() + " " + execution);
                    }
                    if (showToolOutput.shown() || failure) {
                        final var remaining = call.remaining(t.output());
                        if (!remaining.isEmpty()) {
                            log(level, prefix + call.header() + " result:\n" + remaining);
                        }
                    }
                }
            } finally {
                toolCalls.remove(call);
            }
        } else if (event instanceof final AgentEvent.Compacted c) {
            ensureStderrNewline();
            final var tokens = c.tokens();
            final var shrink =
                    MetricsFormat.shrink(tokens == null ? 0 : tokens.before(), tokens == null ? 0 : tokens.after());
            LOGGER.info(() -> prefix + "context " + String.join(", ", c.kinds()) + " applied ("
                    + c.mode().toLowerCase(Locale.ROOT) + ")" + (shrink.isEmpty() ? "" : ", " + shrink));
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
            notice(level, prefix + message);
        } else if (event
                instanceof
                AgentEvent.Pause(
                        AgentEvent.Pause.Kind kind,
                        String message,
                        long durationMs,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        var _)) {
            log(kind == AgentEvent.Pause.Kind.RETRY ? Level.WARNING : Level.INFO, prefix + message);
        } else if (event
                instanceof
                AgentEvent.Status(String message, String sessionId, String subagentId, String subagentName, var _)) {
            status(prefix + message);
        } else if (event
                instanceof
                AgentEvent.Error(String message, String sessionId, String subagentId, String subagentName, var _)) {
            error(prefix + message);
        }
    }

    @Override
    public synchronized void endTurn() {
        if (showToolOutput.shown()) {
            for (final var call : toolCalls.active()) {
                final var rest = call.flush();
                if (!rest.isEmpty()) {
                    ensureStderrNewline();
                    LOGGER.info(call.header() + " output: " + rest);
                }
            }
        }
        ensureStderrNewline();
    }

    private void log(final Level level, final String message) {
        LOGGER.log(level, message);
    }

    /**
     * The reasoning stream writes to stderr without a trailing newline; before a
     * discrete event line is emitted, terminate the open thought line so tool/status
     * lines are not glued to the thinking output.
     */
    private void ensureStderrNewline() {
        if (stderrNeedsNewline) {
            System.err.println();
            stderrNeedsNewline = false;
            stderrLineOwner = null;
        }
    }

    private String subagentPrefix(final AgentEvent event) {
        String subagentId = null;
        String subagentName = null;
        if (event instanceof AgentEvent.TextDelta(var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ThinkingDelta(var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ToolStarted(var _, var _, var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ToolStream(var _, var _, var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event
                instanceof
                AgentEvent.ToolFinished(var _, var _, var _, var _, var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ToolDiagnostic t) {
            subagentId = t.subagentId();
            subagentName = t.subagentName();
        } else if (event instanceof AgentEvent.Compacted(var _, var _, var _, String id, String name, var _, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event
                instanceof AgentEvent.Notice(var _, var _, var _, var _, String id, String name, var _, var _, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.Status(var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event
                instanceof
                AgentEvent.Pause(var _, String message, long durationMs, String _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.Error(var _, var _, String id, String name, var _)) {
            subagentId = id;
            subagentName = name;
        }
        if (subagentId != null) {
            final var displayName =
                    subagentName != null ? subagentName : subagentId.substring(0, Math.min(8, subagentId.length()));
            return "[" + displayName + "] ";
        }
        return "";
    }
}
