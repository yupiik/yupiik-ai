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
package io.yupiik.ia.agent.core.context;

import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable exact-prefix inputs and results. Only the foreground checkpoint installs results. */
public final class SpeculativeCompaction {
    private SpeculativeCompaction() {}

    public record Prepared(
            int leadingSystemCount,
            List<Message> prefix,
            String priorSummary,
            ChatSettings settings,
            ChatCompletionRequest requestTemplate,
            int chunkCap,
            int digestCap,
            int minimumChunkCap,
            int capturedTokens) {
        public int cutoff() {
            return prefix.size();
        }
    }

    public record Candidate(Prepared source, String summary, int summarizerCalls, long elapsedMillis) {}

    public enum Rejection {
        SOURCE_CHANGED,
        RETENTION_SHRUNK,
        TOOL_BOUNDARY,
        EMPTY_SUMMARY,
        TOO_LARGE,
        NO_REDUCTION
    }

    public record Adoption(boolean adopted, CompactionResult result, Rejection rejection) {}

    /** Deep copies JSON-shaped payloads, preserving nulls and every tool-call field. Unknown objects fail closed. */
    static List<Message> snapshot(final List<Message> messages) {
        final var result = new ArrayList<Message>(messages.size());
        for (final var m : messages) {
            @SuppressWarnings("unchecked")
            final var others = (Map<String, Object>) freeze(m.others());
            result.add(new Message(
                    m.role(),
                    freeze(m.content()),
                    m.name(),
                    m.toolCalls() == null ? null : Collections.unmodifiableList(new ArrayList<>(m.toolCalls())),
                    m.toolCallId(),
                    others));
        }
        return List.copyOf(result);
    }

    private static Object freeze(final Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof List<?> list) {
            final var copy = new ArrayList<Object>(list.size());
            for (final var item : list) {
                copy.add(freeze(item));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?> map) {
            final var copy = new LinkedHashMap<String, Object>();
            for (final var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Unsupported snapshot map key");
                }
                copy.put(key, freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        throw new IllegalArgumentException("Unsupported snapshot payload");
    }
}
