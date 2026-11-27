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

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaletteTest {
    static Palette palette() {
        return new Palette(
                "#9d7cd8", "#fab283", "#7fd88f", "#f5a742", "#e06c75", "#b896d8", "#eeeeee", "#808080", "#484848",
                "#606060", "#141414", "#ff5555", true);
    }

    static Palette darkPalette() {
        return new Palette(
                "#9d7cd8", "#fab283", "#7fd88f", "#f5a742", "#e06c75", "#b896d8", "#eeeeee", "#808080", "#484848",
                "#606060", "#141414", "#ff5555", false);
    }

    @Test
    void monoPaletteUsesResetColors() {
        final var palette = palette();
        assertEquals(Color.RESET, palette.color("#123456"));
        assertEquals(Style.EMPTY, palette.style("#123456"));
    }

    @Test
    void colorPaletteResolvesHex() {
        final var palette = darkPalette();
        assertEquals(Color.hex("#9d7cd8"), palette.color("#9d7cd8"));
    }

    @Test
    void themeDefaultsAndAutoValues() {
        final var dark = new Palette(
                "#02ABEA", "#D858FF", "#46F72F", "#fbbf24", "#f87171", "#7ad2f7", "#eef0f3", "#9aa4b2", "#667587",
                "#7ad2f7", "#12141a", "#ff5555", false);
        final var light = new Palette(
                "#056EBB", "#a84fc9", "#2f7a43", "#8a621f", "#d1383d", "#1f7a88", "#24262b", "#626873", "#8995a3",
                "#056EBB", "#ffffff", "#ff5555", false);
        for (final var value : Arrays.asList(null, "", "  ", "auto", "  AuTo  ")) {
            final var colors = new String[12];
            Arrays.fill(colors, value);
            assertEquals(dark, Palette.from("dark", config(colors), false), value);
            assertEquals(light, Palette.from("LIGHT", config(colors), false), value);
        }
        assertEquals(dark, Palette.from("unknown", config(new String[12]), false));
    }

    @Test
    void everyExplicitOverrideWinsOnBothThemes() {
        final var colors = new String[] {
            "#9d7cd8", "#010002", "#010003", "#010004", "#010005", "#010006", "#010007", "#010008", "#010009",
            "#01000a", "#01000b", "#01000c"
        };
        final var expected = new Palette(
                colors[0],
                colors[1],
                colors[2],
                colors[3],
                colors[4],
                colors[5],
                colors[6],
                colors[7],
                colors[8],
                colors[9],
                colors[10],
                colors[11],
                false);
        for (final var theme : List.of("dark", "light")) {
            assertEquals(expected, Palette.from(theme, config(colors), false), theme);
        }
    }

    @Test
    void noColorEnvironmentControlsPalette() throws IOException, InterruptedException {
        for (final var value : Arrays.asList(null, "", "  ", "1")) {
            final var process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    NoColorProbe.class.getName());
            if (value == null) {
                process.environment().remove("NO_COLOR");
            } else {
                process.environment().put("NO_COLOR", value);
            }
            final var child = process.redirectErrorStream(true).start();
            final var output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(0, child.waitFor(), output);
            assertEquals("1".equals(value) ? "true:true:true:true:true" : "false:false:false:false:false", output);
        }
    }

    public static class NoColorProbe {
        static void main(final String[] args) {
            final var palette = Palette.from("light", config(new String[12]));
            System.out.println(Palette.noColorRequested() + ":" + palette.mono() + ":"
                    + palette.color(palette.accent()).equals(Color.RESET) + ":"
                    + palette.bold(palette.accent()).equals(Style.EMPTY) + ":"
                    + palette.blended(palette.accent(), 0.5).equals(Style.EMPTY));
        }
    }

    private static TuiConfiguration config(final String[] colors) {
        return new TuiConfiguration(
                null,
                colors[0],
                colors[1],
                colors[2],
                colors[3],
                colors[4],
                colors[5],
                colors[6],
                colors[7],
                -1.0,
                0.22,
                0.14,
                0.85,
                colors[8],
                colors[9],
                colors[10],
                "auto",
                "auto",
                4000L,
                3500L,
                true,
                "unicode",
                "auto",
                "auto",
                List.of(),
                null,
                null,
                true,
                3,
                10,
                "",
                0.8,
                colors[11],
                null,
                null);
    }
}
