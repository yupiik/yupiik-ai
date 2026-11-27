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
package io.yupiik.fusion.a2a.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rule 1 of section 8.4.1: "Default values MUST be omitted unless the field is marked as REQUIRED or has the optional
 * keyword".
 * <p>
 * {@link JsonCanonicalizerTest} asserts the worked example the specification gives. What is pinned here is which values
 * count as a Protocol Buffer default in the first place, since getting that wrong changes the signed bytes silently.
 */
class AgentCardPayloadTest {
    private final AgentCardPayload payloads = new AgentCardPayload(null, new JsonCanonicalizer());

    @Test
    void protocolBufferDefaults() {
        assertTrue(payloads.isDefault(null));
        assertTrue(payloads.isDefault(""));
        assertTrue(payloads.isDefault(false));
        assertTrue(payloads.isDefault(0));
        assertTrue(payloads.isDefault(0L));
        assertTrue(payloads.isDefault(0d));
        assertTrue(payloads.isDefault(Map.of()));
        assertTrue(payloads.isDefault(List.of()));
    }

    @Test
    void whatIsNotADefault() {
        assertFalse(payloads.isDefault("a"));
        assertFalse(payloads.isDefault(" "), "a blank string is content, only an empty one is the default");
        assertFalse(payloads.isDefault(true));
        assertFalse(payloads.isDefault(1));
        assertFalse(payloads.isDefault(-1));
        assertFalse(payloads.isDefault(0.5d));
        assertFalse(payloads.isDefault(Map.of("a", 1)));
        assertFalse(payloads.isDefault(List.of(1)));
        // anything the model cannot hold is not a default either, it is simply content
        assertFalse(payloads.isDefault(new Object()));
    }

    @Test
    void aCardMustBeAJsonObject() {
        for (final var structure : List.of("a string", List.of(), 42)) {
            final var error = assertThrows(IllegalArgumentException.class, () -> payloads.prepare(structure));
            assertTrue(error.getMessage().contains("must be a JSON object"), error::getMessage);
        }
        assertThrows(IllegalArgumentException.class, () -> payloads.prepare(null));
    }

    @Test
    void onlyTheTopLevelSignaturesAreExcluded() {
        final var card = new LinkedHashMap<String, Object>();
        card.put("name", "a");
        card.put("signatures", List.of(Map.of("protected", "x")));
        // rule 3 is about the card signatures, so a member which merely shares the name deeper down is content
        card.put(
                "capabilities",
                new LinkedHashMap<>(Map.of(
                        "extensions",
                        List.of(new LinkedHashMap<>(Map.of(
                                "uri",
                                "https://example.com/x",
                                "params",
                                new LinkedHashMap<>(Map.of("signatures", List.of("kept")))))))));

        assertEquals("""
                        {"capabilities":{"extensions":[{"params":{"signatures":["kept"]},"uri":"https://example.com/x"}]},"name":"a"}""", new JsonCanonicalizer().canonicalize(payloads.prepare(card)));
    }

    @Test
    void requiredMembersSurviveTheirDefault() {
        final var card = new LinkedHashMap<String, Object>();
        card.put("name", "");
        card.put("description", "");
        card.put("version", "");
        card.put("supportedInterfaces", List.of());
        card.put("skills", List.of());
        card.put("defaultInputModes", List.of());
        card.put("defaultOutputModes", List.of());
        card.put("capabilities", new LinkedHashMap<>());
        card.put("documentationUrl", "");
        card.put("iconUrl", "");
        // not in the keep list: a plain repeated field and a plain map
        card.put("securityRequirements", List.of());
        card.put("securitySchemes", Map.of());

        final var canonical = new JsonCanonicalizer().canonicalize(payloads.prepare(card));

        assertTrue(canonical.contains("\"name\":\"\""), canonical);
        assertTrue(canonical.contains("\"capabilities\":{}"), canonical);
        assertTrue(canonical.contains("\"documentationUrl\":\"\""), canonical);
        assertFalse(canonical.contains("securityRequirements"), canonical);
        assertFalse(canonical.contains("securitySchemes"), canonical);
    }

    @Test
    void theKeepListIsPathBased() {
        final var card = new LinkedHashMap<String, Object>();
        // capabilities.streaming is optional so a deliberate false is content, and so is a REQUIRED skill member
        card.put("capabilities", new LinkedHashMap<>(Map.of("streaming", false, "unlisted", false)));
        card.put("skills", List.of(new LinkedHashMap<>(Map.of("id", "", "tags", List.of(), "unlisted", ""))));

        assertEquals("""
                        {"capabilities":{"streaming":false},"skills":[{"id":"","tags":[]}]}""", new JsonCanonicalizer().canonicalize(payloads.prepare(card)));
    }

    @Test
    void nullMembersAreDropped() {
        final var card = new LinkedHashMap<String, Object>();
        card.put("name", "a");
        card.put("provider", null);
        // asList and not List.of: a null is a legitimate JSON value, and it must survive inside an array
        card.put("skills", Arrays.asList((Object) null));

        assertEquals(
                "{\"name\":\"a\",\"skills\":[null]}", new JsonCanonicalizer().canonicalize(payloads.prepare(card)));
    }
}
