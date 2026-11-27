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
package io.yupiik.ia.agent.github.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BotTemplatesTest {
    @Test
    void explicitLineBreakSurvivesLoopBoundaryWhitespaceHandling() {
        final var templates = new BotTemplates(Map.of("main", "{{#each values}}{{{this}}}{{newline}}{{/each}}"));
        assertEquals("first\n\nsecond\n", templates.render("main", Map.of("values", List.of("first", "second"))));
    }

    @Test
    void snapshotsSourcesAndRendersLiteralDataWithoutSecondEvaluation() {
        final var sources = new LinkedHashMap<>(Map.of("main", "{{{value}}}"));
        final var templates = new BotTemplates(sources);
        sources.put("main", "changed");
        final var value = "\"quotes\" <tag> & ' {{exec command}} {{{env SECRET}}} {{> missing}}";
        for (int i = 0; i < 3; i++) {
            assertEquals(value, templates.render("main", Map.of("value", value)));
        }
    }

    @Test
    void verifiesFusionEscapingAndConfiguredPartials() {
        final var templates = new BotTemplates(Map.of(
                "main", "{{> details}}|{{value}}|{{{value}}}",
                "details", "{{#if enabled}}value={{{value}}}{{else}}off{{/if}}"));
        final var result = templates.render("main", Map.of("value", "<a>\"&'", "enabled", true));
        assertTrue(result.startsWith("value=<a>\"&'|"), result);
        assertTrue(result.contains("&lt;a&gt;"), result);
        assertTrue(result.endsWith("|<a>\"&'"), result);
        assertEquals("off||", templates.render("main", Map.of("enabled", false)));
    }

    @Test
    void hasNoAmbientApplicationHelpers() {
        final var templates =
                new BotTemplates(Map.of("main", "{{globalconfiguration}}|{{exec}}|{{systemproperty}}|{{env}}"));
        assertEquals("|||", templates.render("main", Map.of()));
    }

    @Test
    void malformedTrustedSourcesFailImmediatelyWithoutSourceDisclosure() {
        for (final var source :
                new String[] {"private prompt {{broken", "{{#if value}}private prompt", "{{> missing}}", "{{> main}}"
                }) {
            final var error =
                    assertThrows(IllegalArgumentException.class, () -> new BotTemplates(Map.of("main", source)));
            assertTrue(error.getMessage().contains("main"));
            assertTrue(error.getMessage().contains("Handlebars"));
            assertFalse(error.getMessage().contains("private prompt"));
            assertNull(error.getCause());
        }
    }

    @Test
    void unknownNameIsActionable() {
        final var templates = new BotTemplates(Map.of("main", "ok"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> templates.render("missing", Map.of()))
                .getMessage()
                .contains("missing"));
    }
}
