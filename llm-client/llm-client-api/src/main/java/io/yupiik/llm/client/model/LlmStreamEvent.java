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

import java.util.List;
import java.util.Map;

/**
 * Typed streaming event. Unlike a flat "delta" blob this sealed hierarchy lets callers switch
 * exhaustively over the distinct event kinds emitted by the various wire dialects.
 */
public sealed interface LlmStreamEvent
        permits LlmStreamEvent.Start,
                LlmStreamEvent.TextDelta,
                LlmStreamEvent.ReasoningDelta,
                LlmStreamEvent.ReasoningItem,
                LlmStreamEvent.ToolCallDelta,
                LlmStreamEvent.End {

    /**
     * First event of a stream: carries the provider message id / model / creation.
     *
     * @param id optional provider message id.
     * @param model the model that produced the stream.
     * @param created optional creation epoch second.
     */
    record Start(String id, String model, Long created) implements LlmStreamEvent {}

    /**
     * A chunk of generated output text.
     *
     * @param text the generated text fragment.
     */
    record TextDelta(String text) implements LlmStreamEvent {}

    /**
     * A chunk of reasoning / thinking output (renamed per configuration when present).
     *
     * @param text the reasoning text fragment.
     */
    record ReasoningDelta(String text) implements LlmStreamEvent {}

    /**
     * A full completed reasoning item as emitted by the Responses wire. The {@link #encryptedContent}
     * blob is an opaque, server-side-encrypted chain-of-thought fragment (the backend holds the only key):
     * it is <em>not</em> for display, but must be <em>replayed verbatim</em> as a {@code type:reasoning}
     * input item on later turns so the same model recovers its multi-turn reasoning continuity (stateless
     * clients). The {@link #summary} carries the displayable reasoning summaries that also streamed as
     * separate {@link #ReasoningDelta} events.
     *
     * @param id the provider reasoning item id, replayed unchanged.
     * @param summary the displayable summary parts ({@code {type:summary_text,text}} shapes), or empty.
     * @param encryptedContent the opaque replay blob, or empty when the backend streamed plaintext only.
     */
    record ReasoningItem(String id, List<Map<String, Object>> summary, String encryptedContent)
            implements LlmStreamEvent {}

    /**
     * A chunk of tool call arguments, indexed so a multi-tool stream can be reassembled.
     *
     * @param index the index of the tool call within the message.
     * @param id optional provider tool call id.
     * @param name the function name.
     * @param arguments a partial fragment of the JSON arguments.
     */
    record ToolCallDelta(int index, String id, String name, String arguments) implements LlmStreamEvent {}

    /**
     * Terminal event carrying the finish reason and aggregated usage.
     *
     * @param finishReason why the stream stopped.
     * @param usage aggregated token usage.
     * @param errorMessage optional provider error details, absent when not supplied.
     */
    record End(String finishReason, Usage usage, String errorMessage) implements LlmStreamEvent {
        /** Terminal event without provider error details. */
        public End(final String finishReason, final Usage usage) {
            this(finishReason, usage, null);
        }
    }
}
