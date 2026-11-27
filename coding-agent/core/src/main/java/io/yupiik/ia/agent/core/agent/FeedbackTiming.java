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
package io.yupiik.ia.agent.core.agent;

import io.yupiik.ia.agent.core.llm.Message;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Actor-local first-feedback clock and persisted, channel-relative block boundaries. */
public final class FeedbackTiming {
    private List<Map<String, Object>> blocks = new ArrayList<>();
    private String active;
    private int textOffset;
    private int thinkingOffset;

    public synchronized void reset() {
        // Drop the backing array too: an unusually fragmented response must not
        // pin its peak metadata capacity for the lifetime of an idle session.
        blocks = new ArrayList<>();
        active = null;
        textOffset = 0;
        thinkingOffset = 0;
    }

    public synchronized void accept(final AgentEvent event, final Clock clock, final Consumer<AgentEvent> emit) {
        final String kind;
        final String delta;
        if (event instanceof AgentEvent.TextDelta t) {
            kind = "text";
            delta = t.delta();
        } else if (event instanceof AgentEvent.ThinkingDelta t) {
            kind = "thinking";
            delta = t.delta();
        } else {
            if (event instanceof AgentEvent.TextReset
                    || event instanceof AgentEvent.TurnDone
                    || event instanceof AgentEvent.TurnCancelled) {
                reset();
            } else if (event instanceof AgentEvent.ToolStarted || event instanceof AgentEvent.Error) {
                active = null;
            }
            return;
        }
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (!kind.equals(active)) {
            active = kind;
            final var at = clock.millis();
            blocks.add(Map.of("kind", kind, "at", at, "offset", "text".equals(kind) ? textOffset : thinkingOffset));
            emit.accept(new AgentEvent.FeedbackStarted(
                    kind, at, event.sessionId(), event.subagentId(), event.subagentName(), event.workdir()));
        }
        if ("text".equals(kind)) {
            textOffset += delta.length();
        } else {
            thinkingOffset += delta.length();
        }
    }

    public synchronized Map<String, Object> merge(final Map<String, Object> others) {
        final var out = new LinkedHashMap<String, Object>(others == null ? Map.of() : others);
        final var yca = new LinkedHashMap<String, Object>();
        if (out.get(Message.IDENTITY_KEY) instanceof Map<?, ?> existing) {
            yca.putAll(existing.entrySet().stream()
                    .collect(
                            LinkedHashMap<String, Object>::new,
                            (collected, entry) -> collected.put(String.valueOf(entry.getKey()), entry.getValue()),
                            Map::putAll));
        }
        if (!blocks.isEmpty()) {
            yca.put("feedback", List.copyOf(blocks));
        }
        if (!yca.isEmpty()) {
            out.put(Message.IDENTITY_KEY, yca);
        }
        return out;
    }

    public synchronized boolean has(final String kind) {
        return blocks.stream().anyMatch(it -> kind.equals(it.get("kind")));
    }

    /** Align a boundary-sized final suffix using offsets only, never another copy of streamed text. */
    public synchronized void reconcile(final String text) {
        if (text == null || text.length() >= textOffset) {
            return;
        }
        final var shift = textOffset - text.length();
        if (blocks.stream()
                .noneMatch(block ->
                        "text".equals(block.get("kind")) && ((Number) block.get("offset")).intValue() == shift)) {
            return;
        }
        final var updated = new ArrayList<Map<String, Object>>(blocks.size());
        for (final var block : blocks) {
            if (!"text".equals(block.get("kind"))) {
                updated.add(block);
            } else if (((Number) block.get("offset")).intValue() >= shift) {
                updated.add(Map.of(
                        "kind",
                        "text",
                        "at",
                        block.get("at"),
                        "offset",
                        ((Number) block.get("offset")).intValue() - shift));
            }
        }
        blocks.clear();
        blocks.addAll(updated);
    }

    public record Segment(String kind, Long startedAt, String text) {}

    /** Reconstructs visible channel blocks without inventing timestamps for old sessions. */
    public static List<Segment> segments(final Message message, final String text, final String thinking) {
        final var blocks = blocksOf(message);
        if (blocks.isEmpty() || !validOffsets(blocks, "text", text) || !validOffsets(blocks, "thinking", thinking)) {
            return legacySegments(text, thinking);
        }
        final var out = new ArrayList<Segment>(blocks.size());
        int textEnd = text == null ? 0 : text.length();
        int thinkingEnd = thinking == null ? 0 : thinking.length();
        for (int i = blocks.size() - 1; i >= 0; i--) {
            final var block = blocks.get(i);
            final var kind = String.valueOf(block.get("kind"));
            final var source = "text".equals(kind) ? text : "thinking".equals(kind) ? thinking : null;
            if (source == null || !(block.get("offset") instanceof Number offset)) {
                continue;
            }
            final var start = Math.clamp(offset.intValue(), 0, source.length());
            final var end = "text".equals(kind) ? textEnd : thinkingEnd;
            if ("text".equals(kind)) {
                textEnd = start;
            } else {
                thinkingEnd = start;
            }
            if (end > start) {
                out.add(new Segment(
                        kind,
                        block.get("at") instanceof Number at ? at.longValue() : null,
                        source.substring(start, end)));
            }
        }
        Collections.reverse(out);
        return out;
    }

    private static boolean validOffsets(final List<Map<?, ?>> blocks, final String kind, final String source) {
        if (source == null || source.isBlank()) {
            return true;
        }
        int previous = -1;
        for (final var block : blocks) {
            if (!kind.equals(block.get("kind"))) {
                continue;
            }
            if (!(block.get("offset") instanceof Number offset) || !(block.get("at") instanceof Number)) {
                return false;
            }
            final var value = offset.longValue();
            if ((previous == -1 && value != 0) || value < previous || value > source.length()) {
                return false;
            }
            previous = (int) value;
        }
        return previous >= 0;
    }

    private static List<Segment> legacySegments(final String text, final String thinking) {
        final var out = new ArrayList<Segment>();
        if (thinking != null && !thinking.isBlank()) {
            out.add(new Segment("thinking", null, thinking));
        }
        if (text != null && !text.isBlank()) {
            out.add(new Segment("text", null, text));
        }
        return out;
    }

    public static Map<String, Object> stamp(final Map<String, Object> others, final String kind, final long at) {
        final var timing = new FeedbackTiming();
        timing.blocks.add(Map.of("kind", kind, "at", at, "offset", 0));
        return timing.merge(others);
    }

    /** Missing or malformed legacy metadata is unknown, never reconstructed from persistence time. */
    public static Long startedAt(final Message message, final String kind) {
        for (final var block : blocksOf(message)) {
            if (kind.equals(block.get("kind")) && block.get("at") instanceof Number at) {
                return at.longValue();
            }
        }
        return null;
    }

    public static List<Map<?, ?>> blocksOf(final Message message) {
        if (message != null
                && message.others() != null
                && message.others().get(Message.IDENTITY_KEY) instanceof Map<?, ?> yca
                && yca.get("feedback") instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .<Map<?, ?>>map(it -> (Map<?, ?>) it)
                    .toList();
        }
        return List.of();
    }
}
