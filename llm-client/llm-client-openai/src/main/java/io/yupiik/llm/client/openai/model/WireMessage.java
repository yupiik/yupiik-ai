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
package io.yupiik.llm.client.openai.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmToolCall;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI /chat/completions wire message (also reused for assistant/tool turns).
 *
 * @param role message role: {@code system}, {@code user}, {@code assistant} or {@code tool}.
 * @param content textual content, {@code null} for pure tool call messages.
 * @param name optional participant name.
 * @param toolCalls tool calls requested by the assistant.
 * @param toolCallId identifier of the tool call this message answers, for {@code tool} roles.
 * @param others unknown wire fields kept as-is (eg. message-level caching hints).
 */
@JsonModel
public record WireMessage(
        String role,
        Object content,
        String name,
        @JsonProperty("tool_calls") List<WireToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonOthers Map<String, Object> others) {

    /**
     * Maps a neutral message to its wire representation.
     *
     * @param message neutral message to map.
     * @return the wire message.
     */
    public static WireMessage from(final LlmMessage message) {
        final var extensions = message.extensions();
        // volatileTail is an agent-loop hint consumed by dialects implementing explicit cache
        // breakpoints, and is_error is an internal failure flag preserved for session round-trips:
        // strip both so neither ever leaks as an unknown field on this wire.
        final Map<String, Object> others;
        if (extensions == null || extensions.isEmpty()) {
            others = null;
        } else {
            final var filtered = new LinkedHashMap<String, Object>();
            extensions.forEach((k, v) -> {
                if (!"volatileTail".equals(k) && !"is_error".equals(k)) {
                    filtered.put(k, v);
                }
            });
            others = filtered.isEmpty() ? null : filtered;
        }
        return new WireMessage(
                message.role(),
                message.content(),
                message.name(),
                toToolCalls(message.toolCalls()),
                message.toolCallId(),
                others);
    }

    /**
     * Maps a wire message back to the neutral model.
     *
     * @param wire wire message to map.
     * @return the neutral message, or {@code null} when the input is null.
     */
    public static LlmMessage toNeutral(final WireMessage wire) {
        if (wire == null) {
            return null;
        }
        final var reasoning = new LinkedHashMap<String, Object>();
        if ("assistant".equals(wire.role()) && wire.others() != null) {
            for (final var key : List.of("reasoning_content", "reasoning")) {
                if (wire.others().get(key) instanceof String text && !text.isBlank()) {
                    reasoning.put(key, text);
                }
            }
        }
        return new LlmMessage(
                wire.role(),
                wire.content(),
                wire.name(),
                toNeutralToolCalls(wire.toolCalls()),
                wire.toolCallId(),
                reasoning.isEmpty() ? null : reasoning);
    }

    private static List<WireToolCall> toToolCalls(final List<LlmToolCall> calls) {
        if (calls == null) {
            return null;
        }
        return calls.stream()
                .map(c -> new WireToolCall(
                        c.id(),
                        c.type(),
                        c.function() == null
                                ? null
                                : new WireFunction(
                                        c.function().name(), c.function().arguments()),
                        c.index()))
                .toList();
    }

    private static List<LlmToolCall> toNeutralToolCalls(final List<WireToolCall> calls) {
        if (calls == null) {
            return null;
        }
        return calls.stream()
                .map(c -> new LlmToolCall(
                        c.id(),
                        c.type(),
                        c.function() == null
                                ? null
                                : new LlmToolCall.FunctionCall(
                                        c.function().name(), c.function().arguments()),
                        c.index()))
                .toList();
    }

    /**
     * Tool call as sent on the wire.
     *
     * @param id tool call identifier.
     * @param type tool call type, usually {@code function}.
     * @param function function name and JSON arguments.
     * @param index tool call index in streaming mode.
     */
    @JsonModel
    public record WireToolCall(String id, String type, WireFunction function, Integer index) {}

    /**
     * Function reference of a tool call.
     *
     * @param name function name.
     * @param arguments JSON arguments.
     */
    @JsonModel
    public record WireFunction(String name, String arguments) {}

    /**
     * Checks the message role.
     *
     * @return whether this message is a tool result, for llm routing checks.
     */
    public boolean isTool() {
        return "tool".equals(role);
    }

    /**
     * Checks the message content.
     *
     * @return whether this message carries assistant tool calls.
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
