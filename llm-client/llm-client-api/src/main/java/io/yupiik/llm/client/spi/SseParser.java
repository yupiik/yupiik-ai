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
package io.yupiik.llm.client.spi;

import java.util.List;

/** Minimal, stateful server-sent event parser (one instance per stream). */
public final class SseParser {

    private final StringBuilder data = new StringBuilder();
    private final int maximumCharacters;
    private String event;

    /** Creates a parser with the default finite event limit. */
    public SseParser() {
        this(Streams.Limits.DEFAULT.eventCharacters());
    }

    /** Creates a parser with an explicit event payload limit in UTF-16 characters. */
    public SseParser(final int maximumCharacters) {
        if (maximumCharacters <= 0) {
            throw new IllegalArgumentException("SSE event limit must be positive");
        }
        this.maximumCharacters = maximumCharacters;
    }

    /**
     * Feed a single raw line; returns the (possibly empty) completed events. Pass null for EOF.
     *
     * @param line the raw line, null for EOF.
     * @return the events completed by this line, possibly empty.
     */
    public List<Event> feed(final String line) {
        if (line == null || line.isEmpty()) {
            return dispatch();
        }
        if (line.startsWith(":")) {
            return List.of();
        }
        if (line.startsWith("event:")) {
            event = value(line, "event:".length());
            return List.of();
        }
        if (line.startsWith("data:")) {
            final var value = value(line, "data:".length());
            if ((long) data.length() + value.length() + (data.isEmpty() ? 0 : 1) > maximumCharacters) {
                throw new Streams.LimitExceededException("SSE event exceeds character limit");
            }
            if (data.length() > 0) {
                data.append('\n');
            }
            data.append(value);
        } else if ("data".equals(line)) {
            if (data.length() >= maximumCharacters) {
                throw new Streams.LimitExceededException("SSE event exceeds character limit");
            }
            // per the SSE spec a bare field name with no colon carries an empty payload: treat
            // "data" as "data:" so the (empty-data) event is tracked like any other data line
            if (data.length() > 0) {
                data.append('\n');
            }
        }
        return List.of();
    }

    private List<Event> dispatch() {
        final var payload = data.toString();
        data.setLength(0);
        final var type = event;
        event = null;
        if (payload.isBlank()) {
            return List.of();
        }
        return List.of(new Event(type, payload));
    }

    /**
     * Whether a full event payload is buffered and is a structurally complete JSON document
     * (starts with an object/array and all brackets are balanced). LLM SSE frames are always
     * JSON; a truncated/partial line (a mid-stream cut) can never be balanced and must not be
     * dispatched as a terminal event.
     */
    public boolean hasCompleteJsonPayload() {
        final var payload = data.toString().strip();
        if (payload.isEmpty() || !(payload.startsWith("{") || payload.startsWith("["))) {
            return false;
        }
        var depth = 0;
        var inString = false;
        var escaped = false;
        for (final var c : payload.toCharArray()) {
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> depth++;
                case '}', ']' -> depth--;
                default -> {
                    /* ignore */
                }
            }
            if (depth < 0) {
                return false;
            }
        }
        return depth == 0 && !inString;
    }

    private static String value(final String line, final int offset) {
        var v = line.substring(Math.min(offset, line.length()));
        if (v.startsWith(" ")) {
            v = v.substring(1);
        }
        return v;
    }

    /**
     * A completed SSE event.
     *
     * @param event the event name, may be {@code null}.
     * @param data the event payload.
     */
    public record Event(String event, String data) {}
}
