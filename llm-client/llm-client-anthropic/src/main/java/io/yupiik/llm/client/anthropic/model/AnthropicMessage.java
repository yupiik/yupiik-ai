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
package io.yupiik.llm.client.anthropic.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.llm.client.model.LlmMessage;

/**
 * Anthropic wire message (role + content as String or list of content blocks).
 *
 * @param role message role: {@code user} or {@code assistant}.
 * @param content textual content or list of content blocks.
 */
@JsonModel
public record AnthropicMessage(String role, Object content) {

    /**
     * Maps a neutral message to its wire representation.
     *
     * @param message neutral message to map.
     * @return the wire message.
     */
    public static AnthropicMessage from(final LlmMessage message) {
        return new AnthropicMessage(message.role(), message.content());
    }

    /**
     * Maps a wire message back to the neutral model.
     *
     * @param wire wire message to map.
     * @return the neutral message, or {@code null} when the input is null.
     */
    public static LlmMessage toNeutral(final AnthropicMessage wire) {
        if (wire == null) {
            return null;
        }
        return new LlmMessage(wire.role(), wire.content(), null, null, null, null);
    }
}
