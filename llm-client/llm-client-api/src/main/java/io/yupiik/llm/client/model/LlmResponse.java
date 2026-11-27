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
 * Neutral chat completion response. At least one choice is expected; providers without a
 * native choices shape (e.g. Anthropic) are normalized into a single {@link LlmChoice}.
 *
 * @param id optional provider message id.
 * @param model model that produced the response.
 * @param created optional creation epoch second.
 * @param choices completion choices.
 * @param usage aggregated token usage.
 * @param extensions provider-specific fields.
 */
public record LlmResponse(
        String id, String model, Long created, List<LlmChoice> choices, Usage usage, Map<String, Object> extensions) {

    /** Defaults {@code choices} and {@code extensions} to empty collections when absent. */
    public LlmResponse {
        choices = choices == null ? List.of() : choices;
        extensions = extensions == null ? Map.of() : extensions;
    }

    /**
     * The first choice.
     *
     * @return the first choice, or null when the provider returned none.
     */
    public LlmChoice first() {
        return choices.isEmpty() ? null : choices.get(0);
    }

    /**
     * The concatenated text of all choices.
     *
     * @return the concatenated text of all choices, empty when absent.
     */
    public String text() {
        final var sb = new StringBuilder();
        for (final var choice : choices) {
            if (choice.message() != null && choice.message().content() instanceof String text) {
                sb.append(text);
            }
        }
        return sb.toString();
    }

    /**
     * A single completion candidate.
     *
     * @param index the choice index.
     * @param message the assistant message.
     * @param finishReason why the completion stopped, may be {@code null}.
     */
    public record LlmChoice(int index, LlmMessage message, String finishReason) {

        /** Defaults {@code message} to an empty assistant message when absent. */
        public LlmChoice {
            message = message == null ? new LlmMessage("assistant", "", null, null, null, Map.of()) : message;
        }
    }
}
