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

import dev.tamboui.style.Color;

public final class TuiColors {
    private TuiColors() {}

    /**
     * Solid color looking like a transparent {@code fg} over {@code bg}: terminals have no
     * alpha so the color is pre-blended, {@code opacity} being the transparency level
     * (0 keeps the foreground as-is, 1 collapses it into the background).
     */
    public static Color blend(final String fgHex, final String bgHex, final double opacity) {
        final var fg = parseHex(fgHex);
        final var bg = parseHex(bgHex);
        return Color.rgb(
                (int) Math.round(fg[0] * (1 - opacity) + bg[0] * opacity),
                (int) Math.round(fg[1] * (1 - opacity) + bg[1] * opacity),
                (int) Math.round(fg[2] * (1 - opacity) + bg[2] * opacity));
    }

    public static int[] parseHex(final String hex) {
        final var clean = hex.strip();
        final var start = clean.startsWith("#") ? 1 : 0;
        return new int[] {
            Integer.parseInt(clean.substring(start, start + 2), 16),
            Integer.parseInt(clean.substring(start + 2, start + 4), 16),
            Integer.parseInt(clean.substring(start + 4, start + 6), 16)
        };
    }
}
