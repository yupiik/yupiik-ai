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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GlyphsTest {
    @Test
    void defaultsToUnicodeAndFallsBackOnAscii() {
        assertFalse(Glyphs.of("unicode").ascii());
        assertTrue(Glyphs.of("ascii").ascii());
        assertTrue(Glyphs.of(" ASCII ").ascii());
    }

    @Test
    void turnHeaderGlyphsHaveAsciiFallbacks() {
        final var unicode = Glyphs.of("unicode");
        final var ascii = Glyphs.of("ascii");
        assertEquals("\u2500", unicode.rule());
        assertEquals("-", ascii.rule());
    }

    @Test
    void roleMarkCarriesTheRoleByShape() {
        final var unicode = Glyphs.of("unicode");
        final var ascii = Glyphs.of("ascii");
        // the shape must differ between roles: it is the only signal left once color is gone
        assertEquals("\u258c", unicode.roleMark(true));
        assertEquals("\u2590", unicode.roleMark(false));
        assertEquals(">", ascii.roleMark(true));
        assertEquals("<", ascii.roleMark(false));
    }
}
