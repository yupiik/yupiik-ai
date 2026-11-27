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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.a2a.model.Part;
import io.yupiik.fusion.a2a.model.Role;
import io.yupiik.fusion.a2a.model.SendMessageConfiguration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What an executor reads off its request.
 * <p>
 * These accessors are the whole API surface an agent author sees, and they all have to survive the absent halves of the
 * protocol: a message with no metadata, a request with no configuration, a call which did not come from HTTP.
 */
class AgentRequestTest {
    @Test
    void textAndData() {
        final var request = request(
                Message.of(Role.ROLE_USER, List.of(Part.text("hello"), Part.data(Map.of("city", "Paris")))),
                null,
                null);

        assertEquals("hello", request.text());
        assertEquals(Map.of("city", "Paris"), request.data());
    }

    @Test
    void withoutAnyMessage() {
        // A2AService rejects such a request, but an executor reached out of band must not blow up on it either
        final var request = request(null, null, null);

        assertEquals("", request.text());
        assertNull(request.data());
        assertNull(request.skillId());
    }

    @Test
    void theSkillComesFromTheMessageMetadataFirst() {
        final var message = new Message(
                "m-1",
                null,
                null,
                Role.ROLE_USER,
                List.of(Part.text("hi")),
                Map.of(AgentRequest.SKILL_ID, "demo/greet"),
                null,
                null);

        // the message wins: it is the one the client attached the routing to
        assertEquals(
                "demo/greet",
                request(message, Map.of(AgentRequest.SKILL_ID, "demo/other"), null)
                        .skillId());
        assertEquals("demo/greet", request(message, null, null).skillId());
    }

    @Test
    void theSkillFallsBackToTheRequestMetadata() {
        assertEquals(
                "demo/report",
                request(Message.user("hi"), Map.of(AgentRequest.SKILL_ID, "demo/report"), null)
                        .skillId());
    }

    @Test
    void withoutAnySkill() {
        assertNull(request(Message.user("hi"), null, null).skillId());
        assertNull(request(Message.user("hi"), Map.of("other", "x"), null).skillId());

        // a null value is not a skill either, and reaches String#valueOf if it is not guarded
        final var metadata = new HashMap<String, Object>();
        metadata.put(AgentRequest.SKILL_ID, null);
        assertNull(request(Message.user("hi"), metadata, null).skillId());
    }

    @Test
    void aNonStringSkillIdIsCoerced() {
        // metadata is free form JSON, so nothing guarantees the client sent a string
        assertEquals(
                "42",
                request(Message.user("hi"), Map.of(AgentRequest.SKILL_ID, 42), null)
                        .skillId());
    }

    @Test
    void acceptedOutputModes() {
        assertEquals(List.of(), request(Message.user("hi"), null, null).acceptedOutputModes());
        assertEquals(
                List.of(),
                request(Message.user("hi"), null, new SendMessageConfiguration(null, null, null, false))
                        .acceptedOutputModes(),
                "an unset list must read as 'the client did not restrict anything', not as a null");
        assertEquals(
                List.of("text/plain"),
                request(
                                Message.user("hi"),
                                null,
                                new SendMessageConfiguration(List.of("text/plain"), null, null, false))
                        .acceptedOutputModes());
    }

    @Test
    void activatedExtensions() {
        final var uri = "https://example.com/x";

        assertTrue(new AgentRequest(Message.user("hi"), null, null, null, "c-1", "t-1", false, List.of(uri), null)
                .isExtensionActivated(uri));
        assertFalse(new AgentRequest(Message.user("hi"), null, null, null, "c-1", "t-1", false, List.of(), null)
                .isExtensionActivated(uri));
        // the transport always fills the list in, but an out of band request carries none
        assertFalse(new AgentRequest(Message.user("hi"), null, null, null, "c-1", "t-1", false, null, null)
                .isExtensionActivated(uri));
    }

    private static AgentRequest request(
            final Message message, final Map<String, Object> metadata, final SendMessageConfiguration configuration) {
        return new AgentRequest(message, configuration, metadata, null, "c-1", "t-1", false, List.of(), null);
    }
}
