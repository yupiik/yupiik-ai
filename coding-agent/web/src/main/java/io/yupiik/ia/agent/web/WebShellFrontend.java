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
package io.yupiik.ia.agent.web;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import io.yupiik.ia.agent.core.shell.ShellFrontend;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web {@link ShellFrontend}: every agent event (already routed to this session
 * by the shared {@code ShellRegistry}) is appended to the shared transcript and
 * forwarded to the SSE broker as a typed event. The status/usage/metrics are
 * also accumulated so the browser can render the status bar without re-deriving
 * them.
 */
public class WebShellFrontend implements ShellFrontend {
    private final String sessionId;
    private final SseBroker broker;
    private final ConversationTranscript transcript;
    private final JsonMapper mapper;

    public WebShellFrontend(
            final String sessionId,
            final SseBroker broker,
            final ConversationTranscript transcript,
            final JsonMapper mapper) {
        this.sessionId = sessionId;
        this.broker = broker;
        this.transcript = transcript;
        this.mapper = mapper;
    }

    @Override
    public void onEvent(final AgentEvent event) {
        if (event instanceof AgentEvent.ToolDiagnostic || event instanceof AgentEvent.ToolFilesChanged) {
            // Live display only: never fold private diagnostics or file content into a transcript.
            broker.pushAgentEvent(sessionId, event);
            return;
        }
        transcript.onEvent(event);
        if (event instanceof AgentEvent.FeedbackStarted started) {
            final var data =
                    new LinkedHashMap<String, Object>(WebEventDto.of(event).data());
            data.put("speakerLabel", transcript.feedbackLabel(null, false, started.startedAt()));
            broker.broadcast(
                    sessionId,
                    AGENT_EVENT
                            + json(new WebEventDto(
                                    "feedback-started",
                                    sessionId,
                                    event.subagentId(),
                                    event.subagentName(),
                                    started.kind(),
                                    data))
                            + AGENT_END);
        } else {
            broker.pushAgentEvent(sessionId, event);
        }
    }

    private static final String AGENT_EVENT = "event: agent\ndata: ";
    private static final String AGENT_END = "\n\n";

    @Override
    public void status(final String message) {
        broadcastStatus(message, null);
    }

    /**
     * Turn start: the browser must leave the "ready" state and reveal the pause/stop control.
     * Emitted as a {@code status} frame carrying {@code busy: true}; the frontend {@code foldStatus}
     * reads that field to drive the status bar and the Stop button (which otherwise would stay hidden,
     * because the plain AgentLoop {@code Status} events carry no busy flag).
     */
    @Override
    public void beginTurn() {
        broadcastStatus("working", Boolean.TRUE);
    }

    /** Turn end: reset the busy flag so the status bar returns to "ready" (also covers resume/cancel). */
    @Override
    public void endTurn() {
        broadcastStatus("ready", Boolean.FALSE);
    }

    /**
     * Live activity label ("thinking... (Ns)", "calling <command>", ...): transient progress that must
     * not reset the busy flag, so the message is broadcast with an explicit {@code busy} value only when
     * this frontend is the one driving it (it always is, for the web).
     */
    @Override
    public void activity(final String label) {
        broadcastStatus(label, Boolean.TRUE);
    }

    /** Silence the activity indicator without ending the turn's busy state semantics. */
    @Override
    public void idle() {
        broadcastStatus(null, Boolean.FALSE);
    }

    private void broadcastStatus(final String message, final Boolean busy) {
        // a status label is transient progress: droppable when a slow tab's queue overflows, the
        // next status frame supersedes it and the turn's busy flag is re-derivable from the events.
        broker.broadcastDroppable(
                sessionId,
                AGENT_EVENT
                        + json(new WebEventDto(
                                "status",
                                sessionId,
                                null,
                                null,
                                message,
                                busy == null ? Map.of() : Map.of("busy", busy)))
                        + AGENT_END);
    }

    @Override
    public void notice(final AgentEvent.Notice.Level level, final String message) {
        broker.broadcast(
                sessionId,
                AGENT_EVENT
                        + json(new WebEventDto(
                                "notice",
                                sessionId,
                                null,
                                null,
                                message,
                                Map.of("level", level == null ? "INFO" : level.name())))
                        + AGENT_END);
    }

    @Override
    public void transcript(final String markdown) {
        transcript.appendTranscript(markdown);
        broker.broadcast(
                sessionId,
                AGENT_EVENT
                        + json(new WebEventDto("transcript", sessionId, null, null, markdown, Map.of()))
                        + AGENT_END);
    }

    @Override
    public void error(final String message) {
        broker.broadcast(
                sessionId,
                AGENT_EVENT + json(new WebEventDto("error", sessionId, null, null, message, Map.of())) + AGENT_END);
    }

    @Override
    public void showThinking(final boolean value) {
        broker.broadcast(
                sessionId,
                AGENT_EVENT
                        + json(new WebEventDto("settings", sessionId, null, null, null, Map.of("showThinking", value)))
                        + AGENT_END);
    }

    @Override
    public void showToolOutput(final ToolOutputVisibility value) {
        broker.broadcast(
                sessionId,
                AGENT_EVENT
                        + json(new WebEventDto(
                                "settings",
                                sessionId,
                                null,
                                null,
                                null,
                                Map.of(
                                        "showToolOutput",
                                        value == null
                                                ? "collapsed"
                                                : value.name().toLowerCase())))
                        + AGENT_END);
    }

    @Override
    public void diffViewMode(final DiffViewMode value) {
        settings(Map.of("diffViewMode", (value == null ? DiffViewMode.AUTO : value).value()));
    }

    public void settings(final Map<String, Object> values) {
        broker.broadcast(
                sessionId,
                AGENT_EVENT + json(new WebEventDto("settings", sessionId, null, null, null, values)) + AGENT_END);
    }

    public void command(final String name, final Map<String, Object> values) {
        final var data = new LinkedHashMap<String, Object>(values);
        data.put("command", name);
        broker.broadcast(
                sessionId,
                AGENT_EVENT + json(new WebEventDto("web-command", sessionId, null, null, null, data)) + AGENT_END);
    }

    @Override
    public void clear() {
        command("clear", Map.of());
    }

    private String json(final WebEventDto dto) {
        return mapper.toString(dto);
    }
}
