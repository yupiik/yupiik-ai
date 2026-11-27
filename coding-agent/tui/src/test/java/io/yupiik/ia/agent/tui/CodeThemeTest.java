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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodeThemeTest {
    @Test
    void autoFollowsTheme() {
        assertEquals(CodeTheme.ONE_DARK, CodeTheme.parse("auto", false));
        assertEquals(CodeTheme.ONE_LIGHT, CodeTheme.parse("auto", true));
    }

    @Test
    void explicitThemeWinsOverAuto() {
        assertEquals(CodeTheme.ONE_LIGHT, CodeTheme.parse("one-light", false));
        assertEquals(CodeTheme.ONE_DARK, CodeTheme.parse("one-dark", true));
    }

    @Test
    void unknownNameFallsBackToAuto() {
        assertEquals(CodeTheme.ONE_DARK, CodeTheme.parse("solarized", false));
        assertEquals(CodeTheme.ONE_LIGHT, CodeTheme.parse("", true));
    }

    @Test
    void themesDiffer() {
        assertNotEquals(CodeTheme.ONE_DARK, CodeTheme.ONE_LIGHT);
    }

    @Test
    void tokensPopulated() {
        for (final var hex : new String[] {
            CodeTheme.ONE_DARK.keyword(),
            CodeTheme.ONE_DARK.string(),
            CodeTheme.ONE_DARK.comment(),
            CodeTheme.ONE_DARK.number(),
            CodeTheme.ONE_DARK.type(),
            CodeTheme.ONE_DARK.tag(),
            CodeTheme.ONE_DARK.attr(),
            CodeTheme.ONE_DARK.annotation()
        }) {
            assertTrue(hex != null && !hex.isBlank(), "missing token color");
        }
    }
}
