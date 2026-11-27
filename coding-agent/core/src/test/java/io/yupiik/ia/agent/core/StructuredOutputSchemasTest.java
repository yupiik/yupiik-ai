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
package io.yupiik.ia.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.MemoryExtractor;
import io.yupiik.ia.agent.core.llm.ChatClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Structured-output schemas must stay valid for strict providers (Anthropic structured outputs in
 * particular): {@code additionalProperties} only applies to objects, so an array entry carrying it
 * makes Anthropic reject the whole request with a 400 {@code json was malformed}. This regression
 * pins the memory-extraction schema that previously violated that rule.
 */
class StructuredOutputSchemasTest {

    @Test
    void factsSchemaHasNoArrayLevelAdditionalProperties() {
        final var schema = MemoryExtractor.factsSchema();
        final var facts = array(schema, "facts");
        assertNotNull(facts, "facts array expected");
        assertFalse(facts.containsKey("additionalProperties"), "array must not carry additionalProperties");
        final var item = asMap(facts.get("items"));
        assertNotNull(item, "array items expected");
        assertEquals(
                Boolean.FALSE, item.get("additionalProperties"), "item objects must keep additionalProperties: false");
        // root object keeps its own additionalProperties: false
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"), "root must keep additionalProperties");
    }

    private static Map<String, Object> array(final Map<String, Object> root, final String name) {
        final var properties = asMap(root.get("properties"));
        return properties == null ? null : asMap(properties.get(name));
    }

    @Test
    void sanitizerIsIdempotentOnTheBuiltInSchemas() {
        // yca's own schemas are already in the Anthropic-accepted subset: the sanitizer must not
        // change them (no added description, no forced properties), so their wire form stays as today
        final var facts = ChatClient.sanitizeSchema(MemoryExtractor.factsSchema());
        Assertions.assertEquals(MemoryExtractor.factsSchema(), facts);
    }

    @Test
    void sanitizerMovesUnsupportedConstraintsToDescriptionAndForcesAdditionalProperties() {
        final var schema = Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "age",
                        Map.of("type", "integer", "minimum", 0, "maximum", 150),
                        "name",
                        Map.of("type", "string", "maxLength", 10, "format", "email")),
                "required",
                List.of("age"));
        final var sanitized = ChatClient.sanitizeSchema(schema);
        // objects gain additionalProperties:false at every level
        assertEquals(Boolean.FALSE, sanitized.get("additionalProperties"), "root object must be closed");
        final var age = asMap(asMap(sanitized.get("properties")).get("age"));
        assertFalse(age.containsKey("minimum"), "unsupported constraint must be dropped from the wire schema");
        assertFalse(age.containsKey("maximum"), "unsupported constraint must be dropped from the wire schema");
        assertTrue(
                age.get("description") instanceof String s && s.contains("minimum") && s.contains("maximum"),
                "dropped constraints must move into the description: " + age.get("description"));
        // a supported format is kept, an unsupported one moves to the description
        final var name = asMap(asMap(sanitized.get("properties")).get("name"));
        Assertions.assertEquals("email", name.get("format"));
        assertTrue(
                name.get("description") instanceof String d && d.contains("maxLength"),
                "maxLength is unsupported and must move to the description: " + name.get("description"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}
