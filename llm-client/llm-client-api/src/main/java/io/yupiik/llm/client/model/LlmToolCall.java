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

/**
 * A tool invocation emitted by the model. {@link FunctionCall#arguments()} holds the raw JSON
 * argument string; a chunked stream may deliver partial fragments via separate
 * {@link io.yupiik.llm.client.model.LlmStreamEvent.ToolCallDelta}s.
 *
 * @param id optional provider tool call id.
 * @param type the tool call type (always {@code function}).
 * @param function the resolved function reference.
 * @param index optional index of the tool call within the message.
 */
public record LlmToolCall(String id, String type, FunctionCall function, Integer index) {

    /**
     * A function tool call without an index.
     *
     * @param id the provider tool call id.
     * @param name the function name.
     * @param arguments the raw JSON arguments.
     * @return the tool call.
     */
    public static LlmToolCall of(final String id, final String name, final String arguments) {
        return new LlmToolCall(id, "function", new FunctionCall(name, arguments), null);
    }

    /**
     * A resolved function reference of a tool call.
     *
     * @param name function name.
     * @param arguments raw JSON arguments.
     */
    public record FunctionCall(String name, String arguments) {}
}
