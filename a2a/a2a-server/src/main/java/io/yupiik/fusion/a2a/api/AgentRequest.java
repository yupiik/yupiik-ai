/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.api;

import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.SendMessageConfiguration;
import io.yupiik.fusion.http.server.api.Request;
import java.util.List;
import java.util.Map;

/**
 * What an {@link A2AExecutor} is given: the message to process and everything the transport resolved around it.
 *
 * @param message             the incoming message, already bound to {@code contextId}/{@code taskId}.
 * @param configuration       what the client asked for, it can be {@code null}.
 * @param metadata            the request level metadata, it can be {@code null}.
 * @param tenant              the routing identifier the client echoed, it can be {@code null}.
 * @param contextId           the conversation of the task, always set.
 * @param taskId              the task the executor is working on, always set - even when the executor ends up
 *                            answering a {@link Message} instead, in which case the task is discarded.
 * @param continuation        {@code true} when the message continues an existing task, i.e. it is not the first turn.
 * @param activatedExtensions the extension URIs the client declared in the {@code A2A-Extensions} header.
 * @param httpRequest         the underlying HTTP request, it can be {@code null} when the call did not come from the
 *                            HTTP layer.
 */
public record AgentRequest(
        Message message,
        SendMessageConfiguration configuration,
        Map<String, Object> metadata,
        String tenant,
        String contextId,
        String taskId,
        boolean continuation,
        List<String> activatedExtensions,
        Request httpRequest) {
    /**
     * Metadata key the built-in router reads to know which {@link A2ASkill} to invoke.
     */
    public static final String SKILL_ID = "skillId";

    /**
     * Metadata key carrying the structured arguments of a skill, they are merged with the message {@code data} part.
     */
    public static final String ARGUMENTS = "arguments";

    /**
     * @return all the text parts of the message joined with a new line.
     */
    public String text() {
        return message == null ? "" : message.text();
    }

    /**
     * @return the first {@code data} part of the message, {@code null} when there is none.
     */
    public Object data() {
        return message == null ? null : message.data();
    }

    /**
     * @return the requested skill: the {@code skillId} of the message metadata, else the one of the request metadata,
     * else {@code null}.
     */
    public String skillId() {
        final var fromMessage = message == null ? null : lookup(message.metadata(), SKILL_ID);
        return fromMessage != null ? fromMessage : lookup(metadata, SKILL_ID);
    }

    /**
     * @return the media types the client can handle, an empty list when it did not restrict them.
     */
    public List<String> acceptedOutputModes() {
        return configuration == null || configuration.acceptedOutputModes() == null
                ? List.of()
                : configuration.acceptedOutputModes();
    }

    /**
     * @param uri an extension URI.
     * @return {@code true} when the client declared {@code uri} in the {@code A2A-Extensions} header.
     */
    public boolean isExtensionActivated(final String uri) {
        return activatedExtensions != null && activatedExtensions.contains(uri);
    }

    private static String lookup(final Map<String, Object> metadata, final String key) {
        if (metadata == null) {
            return null;
        }
        final var value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
