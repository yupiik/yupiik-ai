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
package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonModel
public record Message(
        String role,
        Object content,
        String name,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonOthers Map<String, Object> others) {

    public static Message system(final String content) {
        return new Message("system", content, null, null, null, null);
    }

    /** Persistent marker name carrying the session's working directory ({@link #projectDirOf}). */
    public static final String PROJECT_MARKER_NAME = "project";

    /**
     * Builds the leading session marker recording the working directory the session belongs to,
     * e.g. {@code /opt/foo/demo}. It is persisted as the first session JSONL entry and never sent
     * to the LLM (all {@code system}-role history messages are dropped at the wire boundary) nor
     * rendered as a transcript turn; it only lets the web home page group sessions by project.
     */
    public static Message projectDir(final String dir) {
        return new Message("system", dir, PROJECT_MARKER_NAME, null, null, null);
    }

    /** @return true when {@code message} is the leading working-directory marker. */
    public static boolean isProjectMarker(final Message message) {
        return message != null && "system".equals(message.role()) && PROJECT_MARKER_NAME.equals(message.name());
    }

    /** @return the working directory recorded by a {@link #projectDir} marker, or {@code null}. */
    public static String projectDirOf(final Message message) {
        return isProjectMarker(message) && message.content() instanceof final String s ? s : null;
    }

    public static Message user(final String content) {
        return new Message("user", content, null, null, null, null);
    }

    public static Message assistant(final String content, final List<ToolCall> toolCalls) {
        return new Message("assistant", content, null, toolCalls, null, null);
    }

    public static Message tool(final String toolCallId, final String content) {
        return new Message("tool", content, null, null, toolCallId, null);
    }

    public static Message tool(final String toolCallId, final String content, final boolean isError) {
        return new Message("tool", content, null, null, toolCallId, isError ? Map.of("is_error", true) : null);
    }

    public static Message toolResult(final String toolCallId, final String content, final String name) {
        return new Message("tool", content, name, null, toolCallId, null);
    }

    /**
     * Builds the call id -> tool name lookup from assistant messages carrying
     * tool calls: legacy sessions do not persist the name on tool results, so
     * rendering can recover it from the matching assistant call.
     */
    public static Map<String, String> toolNames(final List<Message> messages) {
        final var out = new HashMap<String, String>();
        if (messages == null) {
            return out;
        }
        for (final var message : messages) {
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (final var call : message.toolCalls()) {
                if (call != null
                        && call.id() != null
                        && call.function() != null
                        && call.function().name() != null) {
                    out.putIfAbsent(call.id(), call.function().name());
                }
            }
        }
        return out;
    }

    /** Reserved {@code others} key holding the producer identity of a message ({@link Identity}). */
    public static final String IDENTITY_KEY = "yca";

    /**
     * The provider/model/agent identity a user or assistant block was written under.
     * Captured per block at persistence time so replay and template changes can
     * re-render each block's speaker label from what actually produced it. Never
     * sent to the LLM (stripped at every wire boundary).
     */
    public record Identity(String provider, String model, String agent) {}

    /**
     * Returns a copy of {@code message} carrying the producer identity in its
     * {@code others} map (under {@link #IDENTITY_KEY}); null identity members are
     * skipped. The original message is not modified.
     */
    public static Message withIdentity(
            final Message message, final String provider, final String model, final String agent) {
        if (message == null) {
            return null;
        }
        final var identity = new LinkedHashMap<String, Object>();
        if (message.others() != null && message.others().get(IDENTITY_KEY) instanceof Map<?, ?> existing) {
            identity.putAll(existing.entrySet().stream()
                    .collect(
                            LinkedHashMap<String, Object>::new,
                            (collected, entry) -> collected.put(String.valueOf(entry.getKey()), entry.getValue()),
                            Map::putAll));
        }
        putIfPresent(identity, "provider", provider);
        putIfPresent(identity, "model", model);
        putIfPresent(identity, "agent", agent);
        if (identity.isEmpty()) {
            return message;
        }
        final var others = new LinkedHashMap<String, Object>();
        if (message.others() != null) {
            others.putAll(message.others());
        }
        others.put(IDENTITY_KEY, identity);
        return new Message(
                message.role(), message.content(), message.name(), message.toolCalls(), message.toolCallId(), others);
    }

    /** The producer identity stored on {@code message}, or null when none was captured. */
    @SuppressWarnings("unchecked")
    public static Identity identityOf(final Message message) {
        if (message == null || message.others() == null) {
            return null;
        }
        final var raw = message.others().get(IDENTITY_KEY);
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        return new Identity(str(map.get("provider")), str(map.get("model")), str(map.get("agent")));
    }

    private static String str(final Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void putIfPresent(final Map<String, Object> map, final String key, final String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /**
     * Resolves the tool name of a persisted message: prefers the stored name,
     * falls back to the assistant tool call of the same id, {@code null} when
     * the name is unknown.
     */
    public static String toolName(final Message message, final Map<String, String> toolNamesByCallId) {
        if (message == null || message.name() != null) {
            return message == null ? null : message.name();
        }
        return message.toolCallId() == null ? null : toolNamesByCallId.get(message.toolCallId());
    }
}
