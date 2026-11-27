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
package io.yupiik.ia.agent.core.tools;

import java.util.List;
import java.util.Map;

public final class Schemas {
    private Schemas() {}

    public static Map<String, Object> object(final Map<String, Object> properties, final String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    public static Map<String, Object> str(final String description) {
        return Map.of("type", "string", "description", description);
    }

    /** A string property with an enum constraint: {@code enum} values are listed in the schema. */
    public static Map<String, Object> strEnum(final String description, final String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }

    public static Map<String, Object> intProp(final String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> bool(final String description) {
        return Map.of("type", "boolean", "description", description);
    }

    public static Map<String, Object> arrayOf(final String description, final Map<String, Object> items) {
        return Map.of("type", "array", "description", description, "items", items);
    }
}
