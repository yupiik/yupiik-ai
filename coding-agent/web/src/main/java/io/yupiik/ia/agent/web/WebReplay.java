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

import io.yupiik.ia.agent.core.agent.FeedbackTiming;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.shell.SubagentDeliveryView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a session's persisted {@link Message}s as the {@code events} list of a {@code snapshot}
 * SSE frame, so a fresh browser tab seeds its transcript with the existing conversation. The frontend
 * {@code EventLog} reducer understands the emitted event types: {@code user}, {@code text-delta},
 * {@code tool-started} and {@code tool-finished}.
 *
 * <p>System-role messages and whole-message user-role compaction digests are skipped: internal
 * context must not be displayed as human conversation turns. Stored messages are left intact.</p>
 */
final class WebReplay {
    private WebReplay() {
        // static utility
    }

    /**
     * @param sessionId the session whose messages are replayed (used as the DTO {@code sessionId})
     * @param messages  the persisted conversation, in order
     * @return replayed {@link WebEventDto}s the browser can render
     */
    static List<WebEventDto> replay(final String sessionId, final List<Message> messages) {
        return replay(sessionId, messages, null);
    }

    static List<WebEventDto> replay(final String sessionId, final List<Message> messages, final SpeakerLabels labels) {
        final var out = new ArrayList<WebEventDto>();
        if (messages == null || messages.isEmpty()) {
            return out;
        }
        final var toolNames = Message.toolNames(messages);
        // per-tool-name count of persisted tool RESULTS (the `tool` role messages that carry real output).
        // The agent persists the assistant message (with its tool_calls) BEFORE the tool actually runs, so
        // an interrupted/cancelled turn can leave an assistant tool call with no matching result in the
        // store. Replaying that as a `tool-started` with no `tool-finished` renders a card stuck "working"
        // forever. We therefore cap the number of started cards per name to the number of finished results,
        // consumed FIFO so each result pairs with the earliest same-name started - matching how the
        // frontend resolves cards by name-deque. A started whose name has no (or no remaining) result is
        // dropped entirely: an aborted tool carries no output worth showing, mirroring the live view.
        final var finishedCounts = new HashMap<String, Integer>();
        for (final var message : messages) {
            if (message == null || !"tool".equals(message.role())) {
                continue;
            }
            final var name = Message.toolName(message, toolNames);
            // legacy sessions may store the tool name as the tool result's callId; Message.toolName already
            // recovers the real name, so the count is keyed by the resolved name (never null for a tool msg).
            finishedCounts.merge(name == null ? "tool" : name, 1, Integer::sum);
        }
        // a cumulative per-call ordinal so replayed tool lifecycle events dedup/correlate exactly like the
        // live stream's: each replayed started card gets its own ordinal and its finished carries the same
        // one (the live runner stamps ordinals from a runtime counter, so a reconnect mid-turn may not
        // rematch an in-flight call; replayed history is always ordered and completed, so the cumulative
        // counter is exact for what is rendered).
        int ordinal = 1;
        for (final var message : messages) {
            if (Message.isProjectMarker(message) || CompactionCoordinator.isDigestMessage(message)) {
                continue;
            }
            final var role = message.role();
            if ("system".equals(role)) {
                continue; // notices / compaction markers: display-only
            }
            if ("user".equals(role)) {
                final var text = str(message.content());
                final var delivery = SubagentDeliveryView.parse(text);
                if (delivery != null) {
                    final var id = delivery.subagentId();
                    final var state = delivery.state();
                    final var name = delivery.name();
                    out.add(new WebEventDto(
                            "subagent-completed",
                            sessionId,
                            id,
                            name,
                            delivery.summary(),
                            Map.of(
                                    "subagentId",
                                    id,
                                    "subagentName",
                                    name,
                                    "success",
                                    "completed".equals(state),
                                    "cancelled",
                                    "cancelled".equals(state))));
                } else {
                    out.add(new WebEventDto(
                            "user",
                            sessionId,
                            null,
                            null,
                            text,
                            feedbackData(message, "user", FeedbackTiming.startedAt(message, "user"), labels)));
                }
                continue;
            }
            if ("assistant".equals(role)) {
                final var text = str(message.content());
                // reasoning persisted with the answer (see AgentLoop.THINKING_KEY) is replayed as a
                // thinking-delta BEFORE the answer's text-delta, so the reducer groups it as a thought
                // block above the answer - otherwise past thoughts vanish on reload (TUI keeps them in
                // its transcript, the web rebuilds from persisted messages only).
                final var thinking = thinkingOf(message);
                for (final var segment : FeedbackTiming.segments(message, text, thinking)) {
                    out.add(new WebEventDto(
                            "thinking".equals(segment.kind()) ? "thinking-delta" : "text-delta",
                            sessionId,
                            null,
                            null,
                            segment.text(),
                            feedbackData(message, segment.kind(), segment.startedAt(), labels)));
                }
                if (message.toolCalls() != null) {
                    for (final var call : message.toolCalls()) {
                        if (call == null
                                || call.function() == null
                                || call.function().name() == null) {
                            continue;
                        }
                        // only emit a started card while a matching finished result is still available for
                        // this name; otherwise the call was persisted but never completed (interrupted turn)
                        final var remaining =
                                finishedCounts.getOrDefault(call.function().name(), 0);
                        if (remaining <= 0) {
                            continue; // orphan tool call: skip its started so no card ever sticks "working"
                        }
                        finishedCounts.put(call.function().name(), remaining - 1);
                        out.add(new WebEventDto(
                                "tool-started",
                                sessionId,
                                null,
                                null,
                                call.function().name(),
                                Map.of(
                                        "args",
                                        // replayed cards must show their command/path summary exactly like
                                        // the live stream (AgentLoop emits the raw arguments JSON); the
                                        // persisted tool call retains them, so forward them instead of the
                                        // empty map that left bash/read cards with no summary text.
                                        call.function().arguments() == null
                                                ? "{}"
                                                : call.function().arguments(),
                                        "ordinal",
                                        ordinal++)));
                    }
                }
                continue;
            }
            if ("tool".equals(role)) {
                final var name = Message.toolName(message, toolNames);
                final var success = message.others() == null
                        || !Boolean.TRUE.equals(message.others().get("is_error"));
                out.add(new WebEventDto(
                        "tool-finished",
                        sessionId,
                        null,
                        null,
                        name == null ? "tool" : name,
                        Map.of(
                                "output",
                                str(message.content()),
                                "success",
                                success,
                                "ordinal",
                                // a tool result follows its started call in persisted order, so the finished
                                // reuses the last assigned ordinal; Math.max(1,...) keeps it valid when the
                                // very first completed call's started was dropped by the orphan cap above
                                Math.max(1, ordinal - 1))));
            }
        }
        return out;
    }

    private static Map<String, Object> feedbackData(
            final Message message, final String kind, final Long at, final SpeakerLabels labels) {
        final var data = new LinkedHashMap<String, Object>();
        if (at != null) {
            data.put("startedAt", at);
            data.put("feedbackStart", true);
        }
        if (labels != null) {
            final var identity = Message.identityOf(message);
            final var ctx = new SpeakerLabels.Ctx(
                            identity == null ? null : identity.model(),
                            identity == null ? null : identity.provider(),
                            identity == null ? null : identity.agent(),
                            null,
                            null,
                            null)
                    .at(at);
            data.put("speakerLabel", "user".equals(kind) ? labels.userLabel(ctx) : labels.assistantLabel(ctx));
        }
        return data;
    }

    private static String str(final Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** The reasoning text persisted on an assistant message under {@code AgentLoop.THINKING_KEY}, or null. */
    private static String thinkingOf(final Message message) {
        if (message == null || message.others() == null) {
            return null;
        }
        final var raw = message.others().get("thinking");
        return raw == null ? null : String.valueOf(raw);
    }
}
