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
package io.yupiik.ia.agent.core.security;

/**
 * Inbound LLM response hook (mutable holder emitted by {@code ChatClient} after a model response is
 * received). The secret redactor observer replaces provenance-registry fakes back to the real values
 * in the assistant text before it is surfaced to the user / persisted. Custom observers may do the
 * same for their own placeholders. Only registry-tracked values are restored.
 */
public class InboundLlmResponse {

    private final StringBuilder content;
    private final String sessionId;
    private final String assistantText;

    public InboundLlmResponse(final String assistantText, final String sessionId) {
        this.assistantText = assistantText;
        this.content = new StringBuilder(assistantText == null ? "" : assistantText);
        this.sessionId = sessionId;
    }

    public StringBuilder content() {
        return content;
    }

    public String sessionId() {
        return sessionId;
    }

    /** The original assistant text this holder was created from. */
    public String assistantText() {
        return assistantText;
    }

    /** Restored text to use as the surfaced/persisted assistant message. */
    public String restored() {
        return content.toString();
    }
}
