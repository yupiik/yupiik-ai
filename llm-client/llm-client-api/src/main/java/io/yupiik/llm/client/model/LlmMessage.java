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
package io.yupiik.llm.client.model;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A single conversation turn. {@code content} is either a plain string or a list of content
 * part maps (OpenAI-style {@code {"type":..., "text":...}} blocks), which keeps multimodal
 * inputs representable without polluting the neutral model.
 *
 * @param role "system" | "user" | "assistant" | "tool".
 * @param content textual or structured content.
 * @param name optional participant name.
 * @param toolCalls tool calls emitted by the assistant.
 * @param toolCallId id of the tool call this message answers.
 * @param extensions provider-specific fields.
 */
public record LlmMessage(
        String role,
        Object content,
        String name,
        List<LlmToolCall> toolCalls,
        String toolCallId,
        Map<String, Object> extensions) {

    /** Defaults {@code extensions} to an empty map when absent. */
    public LlmMessage {
        extensions = extensions == null ? Map.of() : extensions;
    }

    /**
     * A system message.
     *
     * @param content the message content.
     * @return the system message.
     */
    public static LlmMessage system(final String content) {
        return new LlmMessage("system", content, null, null, null, Map.of());
    }

    /**
     * A user message.
     *
     * @param content the message content.
     * @return the user message.
     */
    public static LlmMessage user(final String content) {
        return new LlmMessage("user", content, null, null, null, Map.of());
    }

    /**
     * An assistant message, optionally carrying the tool calls it emitted.
     *
     * @param content the message content.
     * @param toolCalls the emitted tool calls, may be {@code null}.
     * @return the assistant message.
     */
    public static LlmMessage assistant(final String content, final List<LlmToolCall> toolCalls) {
        return new LlmMessage("assistant", content, null, toolCalls, null, Map.of());
    }

    /**
     * A tool result message answering a tool call.
     *
     * @param toolCallId the id of the answered tool call.
     * @param content the tool result content.
     * @return the tool message.
     */
    public static LlmMessage tool(final String toolCallId, final String content) {
        return new LlmMessage("tool", content, null, null, toolCallId, Map.of());
    }

    /**
     * Upper bound on the assistant tool call ids tracked as awaiting a contiguous result.
     * A single corrupted or oversized assistant tools block can never blow up the pending set.
     */
    static final int MAX_PENDING_TOOL_IDS = 256;

    /**
     * The ids emitted by assistant {@code tool_calls} blocks anywhere in {@code messages}, in source order.
     */
    static Set<String> assistantToolCallIds(final List<LlmMessage> messages) {
        final var seen = new HashSet<String>();
        for (final var message : messages) {
            if (message != null && "assistant".equals(message.role()) && message.toolCalls() != null) {
                for (final var call : message.toolCalls()) {
                    if (call != null && call.id() != null) {
                        seen.add(call.id());
                    }
                }
            }
        }
        return seen;
    }

    /**
     * The set of assistant tool call ids that ARE validly paired on the wire, using the strict
     * contiguous rule both OpenAI-compatible and Anthropic gateways enforce: an assistant
     * {@code tool_calls} id is "paired" only when a {@code tool}-role result carrying it appears in
     * the CONTIGUOUS run of {@code tool} messages immediately following that assistant message
     * (before any non-tool message: a plain user/system/assistant text interposed between the
     * assistant tool_calls and its results breaks the pairing for every still-pending id).
     *
     * <p>This is the single source of truth for that contiguity rule, shared by the wire
     * backstops (which strip unmatched assistant calls / orphan results) and the agent-loop history
     * repair (which inserts an honest lost-result marker for each unmatched id). Keeping the matcher
     * central prevents those layers from disagreeing about what "paired" means - the exact mismatch
     * that let a non-contiguous pairing slip through to Azure/litellm and 400.</p>
     *
     * @param messages the neutral messages; {@code null} yields an empty set.
     * @return the assistant tool call ids whose result landed contiguously.
     */
    public static Set<String> matchedToolCallIds(final List<LlmMessage> messages) {
        final var paired = new HashSet<String>();
        final var pending = new HashSet<String>();
        if (messages != null) {
            for (final var message : messages) {
                if (message == null) {
                    continue;
                }
                if ("assistant".equals(message.role())) {
                    pending.clear();
                    if (message.toolCalls() != null) {
                        for (final var call : message.toolCalls()) {
                            if (call != null && call.id() != null && pending.size() < MAX_PENDING_TOOL_IDS) {
                                pending.add(call.id());
                            }
                        }
                    }
                } else if ("tool".equals(message.role())) {
                    if (message.toolCallId() != null && pending.remove(message.toolCallId())) {
                        paired.add(message.toolCallId());
                    }
                } else {
                    pending.clear();
                }
            }
        }
        return paired;
    }

    /**
     * The assistant tool call ids that are NOT validly paired on the wire: the ids emitted by an
     * assistant {@code tool_calls} block whose result never landed contiguously (the call is an
     * orphan, or a plain user/system/assistant text message was interposed before its result). Uses
     * the same strict contiguous rule as {@link #matchedToolCallIds}, so the two always agree.
     *
     * @param messages the neutral messages; {@code null} yields an empty set.
     * @return the assistant tool call ids whose result never landed contiguously.
     */
    public static Set<String> unmatchedToolCallIds(final List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Set.of();
        }
        final var unmatched = new HashSet<>(assistantToolCallIds(messages));
        unmatched.removeAll(matchedToolCallIds(messages));
        return unmatched;
    }
}
