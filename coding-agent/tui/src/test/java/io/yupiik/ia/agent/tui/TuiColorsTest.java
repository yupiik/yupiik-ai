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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tamboui.style.Color;
import org.junit.jupiter.api.Test;

class TuiColorsTest {
    @Test
    void parseHexHandlesHashPrefix() {
        assertArrayEquals(new int[] {157, 124, 216}, TuiColors.parseHex("#9d7cd8"));
        assertArrayEquals(new int[] {157, 124, 216}, TuiColors.parseHex("9d7cd8"));
    }

    @Test
    void parseHexIsStrict() {
        assertThrows(RuntimeException.class, () -> TuiColors.parseHex("#fff"));
        assertThrows(RuntimeException.class, () -> TuiColors.parseHex("not-a-color"));
        assertThrows(NullPointerException.class, () -> TuiColors.parseHex(null));
    }

    @Test
    void blendWithFullOpacityCollapsesIntoBackground() {
        final var blended = TuiColors.blend("#e06c75", "#141414", 1.0);
        assertEquals(0x14, blended.toRgb().r());
        assertEquals(0x14, blended.toRgb().g());
        assertEquals(0x14, blended.toRgb().b());
    }

    @Test
    void blendWithZeroOpacityKeepsForeground() {
        final var blended = TuiColors.blend("#e06c75", "#141414", 0.0);
        assertEquals(0xe0, blended.toRgb().r());
        assertEquals(0x6c, blended.toRgb().g());
        assertEquals(0x75, blended.toRgb().b());
    }

    @Test
    void blendMidwayBetweenBlackAndWhiteIsGray() {
        final var blended = TuiColors.blend("#ffffff", "#000000", 0.5);
        final var rgb = blended.toRgb();
        assertEquals(Color.rgb(128, 128, 128).toRgb(), rgb);
    }
}
