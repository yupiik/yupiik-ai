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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonMappersTest {
    @Test
    void selectedModelsRoundTripWithoutGeneratedClassReferences() {
        final var expected = new ProviderBudgetConfiguration("/trusted/budget", "account", "2");
        try (final var mapper = JsonMappers.forModels(Configuration.of(Map.of()), ProviderBudgetConfiguration.class)) {
            assertEquals(expected, mapper.fromString(ProviderBudgetConfiguration.class, mapper.toString(expected)));
            assertEquals(Map.of("value", "plain"), mapper.fromString(Map.class, "{\"value\":\"plain\"}"));
            assertThrows(RuntimeException.class, () -> mapper.fromString(ToolCall.class, "{}"));
        }
        try (final var mapper = JsonMappers.create(Configuration.of(Map.of()))) {
            assertEquals(expected, mapper.fromString(ProviderBudgetConfiguration.class, mapper.toString(expected)));
        }
    }
}
