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

import io.yupiik.llm.client.spi.Dialect;
import java.util.List;
import java.util.Map;

/**
 * Neutral, provider-agnostic chat completion request. Any field shape that only one provider
 * understands rides in {@link #extensions()} and is forwarded verbatim by {@link Dialect}
 * implementations into their wire payload, so the neutral model never needs to grow for a
 * single vendor.
 *
 * @param model target provider model identifier.
 * @param messages ordered conversation turns.
 * @param temperature sampling temperature (null = provider default).
 * @param topP nucleus sampling (null = provider default).
 * @param maxTokens maximum tokens to generate.
 * @param stop stop sequences.
 * @param tools available tools (function calling).
 * @param toolChoice tool selection policy (provider-specific value, e.g. "auto"/"none").
 * @param responseFormat response format / structured output (provider-specific value).
 * @param reasoningEffort reasoning effort hint.
 * @param stream whether to stream the answer.
 * @param store whether the provider may retain the conversation server-side (OpenAI `store`; false = opt-out, null = provider default).
 * @param extensions provider-specific fields merged verbatim into the wire payload.
 */
public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        Double temperature,
        Double topP,
        Integer maxTokens,
        List<String> stop,
        List<LlmTool> tools,
        Object toolChoice,
        Object responseFormat,
        String reasoningEffort,
        Boolean stream,
        Boolean store,
        Map<String, Object> extensions) {

    /** Legacy 12-arg form (no {@code store}); defaults server-side storage to provider default. */
    public LlmRequest(
            final String model,
            final List<LlmMessage> messages,
            final Double temperature,
            final Double topP,
            final Integer maxTokens,
            final List<String> stop,
            final List<LlmTool> tools,
            final Object toolChoice,
            final Object responseFormat,
            final String reasoningEffort,
            final Boolean stream,
            final Map<String, Object> extensions) {
        this(
                model,
                messages,
                temperature,
                topP,
                maxTokens,
                stop,
                tools,
                toolChoice,
                responseFormat,
                reasoningEffort,
                stream,
                null,
                extensions);
    }

    /**
     * A request with no optional fields set.
     *
     * @param model the target model.
     * @param messages the conversation turns.
     * @return the request.
     */
    public static LlmRequest of(final String model, final List<LlmMessage> messages) {
        return new LlmRequest(model, messages, null, null, null, null, null, null, null, null, null, false, Map.of());
    }

    /**
     * A copy of this request with another model.
     *
     * @param model the target model.
     * @return the copy.
     */
    public LlmRequest model(final String model) {
        return new LlmRequest(
                model,
                messages,
                temperature,
                topP,
                maxTokens,
                stop,
                tools,
                toolChoice,
                responseFormat,
                reasoningEffort,
                stream,
                store,
                extensions);
    }

    /**
     * A copy of this request with other messages.
     *
     * @param messages the conversation turns.
     * @return the copy.
     */
    public LlmRequest messages(final List<LlmMessage> messages) {
        return new LlmRequest(
                model,
                messages,
                temperature,
                topP,
                maxTokens,
                stop,
                tools,
                toolChoice,
                responseFormat,
                reasoningEffort,
                stream,
                store,
                extensions);
    }

    /**
     * A copy of this request with the streaming flag set.
     *
     * @param stream whether to stream the answer.
     * @return the copy.
     */
    public LlmRequest stream(final Boolean stream) {
        return new LlmRequest(
                model,
                messages,
                temperature,
                topP,
                maxTokens,
                stop,
                tools,
                toolChoice,
                responseFormat,
                reasoningEffort,
                stream,
                store,
                extensions);
    }

    /**
     * A copy of this request with other extensions.
     *
     * @param extensions the provider-specific extensions.
     * @return the copy.
     */
    public LlmRequest with(final Map<String, Object> extensions) {
        return new LlmRequest(
                model,
                messages,
                temperature,
                topP,
                maxTokens,
                stop,
                tools,
                toolChoice,
                responseFormat,
                reasoningEffort,
                stream,
                store,
                extensions);
    }
}
