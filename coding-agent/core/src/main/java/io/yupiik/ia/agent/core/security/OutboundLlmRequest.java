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

import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import java.util.List;

/**
 * Outbound LLM filter hook (mutable holder emitted by {@code ChatClient} right before a request is
 * serialized). Observers registered via {@code @OnEvent(OutboundLlmRequest.class)} may replace the
 * {@link #messages} list (e.g. redact secrets). The secret redactor installs itself as an observer;
 * custom extensions add their own observers for additional inbound/outbound filters. Observers run
 * in {@code @Order} so the chain is composed.
 */
public class OutboundLlmRequest {

    private List<Message> messages;
    private final ChatSettings settings;
    private final String sessionId;
    private final String callKind;

    public OutboundLlmRequest(final List<Message> messages, final ChatSettings settings, final String sessionId) {
        this(messages, settings, sessionId, "agent");
    }

    public OutboundLlmRequest(
            final List<Message> messages, final ChatSettings settings, final String sessionId, final String callKind) {
        this.messages = messages;
        this.settings = settings;
        this.sessionId = sessionId;
        this.callKind = callKind;
    }

    public List<Message> messages() {
        return messages;
    }

    public void messages(final List<Message> messages) {
        this.messages = messages;
    }

    public ChatSettings settings() {
        return settings;
    }

    public String sessionId() {
        return sessionId;
    }

    /** "agent" for normal turns, "summary"/"memory"/"doc"/"todo" for internal auxiliary calls. */
    public String callKind() {
        return callKind;
    }
}
