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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;

class TuiLogColorTest {
    /** Dark-theme default palette values, see TuiConfiguration. */
    private final Palette palette = new Palette(
            "#9d7cd8", "#fab283", "#7fd88f", "#f5a742", "#e06c75", "#56b6c2", "#eeeeee", "#808080", "#484848",
            "#606060", "#141414", "#ff5555", false);

    @Test
    void absentConfigFallsBackToSemanticPaletteColors() {
        assertEquals("#e06c75", TuiCommand.logLevelColor(palette, null, Level.SEVERE));
        assertEquals("#f5a742", TuiCommand.logLevelColor(palette, null, Level.WARNING));
        assertEquals("#56b6c2", TuiCommand.logLevelColor(palette, null, Level.INFO));
        assertEquals("#56b6c2", TuiCommand.logLevelColor(palette, null, Level.CONFIG));
        // the debug levels never borrow a role color: they stay on the info hue, only dimmed
        assertNotEquals(palette.primary(), TuiCommand.logLevelColor(palette, null, Level.FINE));
        assertNotEquals(palette.accent(), TuiCommand.logLevelColor(palette, null, Level.FINEST));
    }

    @Test
    void onlyWarningsAndAboveRenderBold() {
        assertTrue(TuiCommand.logStrong(Level.SEVERE));
        assertTrue(TuiCommand.logStrong(Level.WARNING));
        assertFalse(TuiCommand.logStrong(Level.INFO));
        assertFalse(TuiCommand.logStrong(Level.CONFIG));
        assertFalse(TuiCommand.logStrong(Level.FINE));
        assertFalse(TuiCommand.logStrong(Level.FINER));
        assertFalse(TuiCommand.logStrong(Level.FINEST));
    }

    @Test
    void debugLevelsFadeProgressiveTowardsThePanelBackground() {
        final var fine = TuiCommand.hexOf(TuiColors.blend(palette.info(), palette.backgroundPanel(), 0.25));
        final var finer = TuiCommand.hexOf(TuiColors.blend(palette.info(), palette.backgroundPanel(), 0.40));
        final var finest = TuiCommand.hexOf(TuiColors.blend(palette.info(), palette.backgroundPanel(), 0.55));
        assertEquals(fine, TuiCommand.logLevelColor(palette, null, Level.FINE));
        assertEquals(finer, TuiCommand.logLevelColor(palette, null, Level.FINER));
        assertEquals(finest, TuiCommand.logLevelColor(palette, null, Level.FINEST));
        // distance to the panel background grows as the level drops, i.e. FINE keeps
        // the most info hue and FINEST fades closest to the background
        assertTrue(distanceToBackground(fine) > distanceToBackground(finer), "FINE stays closer to the info hue");
        assertTrue(distanceToBackground(finer) > distanceToBackground(finest), "FINER stays closer than FINEST");
    }

    @Test
    void configuredHexWinsAndAutoBehavesLikeAuto() {
        final var log = new TuiLogConfiguration(
                new TuiLogLevelConfiguration("#ff0000"),
                new TuiLogLevelConfiguration("#ffd700"),
                new TuiLogLevelConfiguration("#00ffff"),
                null,
                null,
                null,
                null);
        final var severe =
                new TuiLogConfiguration(new TuiLogLevelConfiguration("#ff0000"), null, null, null, null, null, null);
        assertEquals("#ff0000", TuiCommand.logLevelColor(palette, severe, Level.SEVERE));
        assertEquals("#ffd700", TuiCommand.logLevelColor(palette, log, Level.WARNING));
        assertEquals("#00ffff", TuiCommand.logLevelColor(palette, log, Level.INFO));
        // null level configs keep the palette defaults
        assertEquals("#56b6c2", TuiCommand.logLevelColor(palette, log, Level.CONFIG));
        // a configured hex wins even when the sibling levels stay unset
        assertEquals("#ff0000", TuiCommand.logLevelColor(palette, log, Level.SEVERE));
        // an explicit "auto" is the same as not configuring the level
        final var auto =
                new TuiLogConfiguration(new TuiLogLevelConfiguration("auto"), null, null, null, null, null, null);
        assertEquals("#e06c75", TuiCommand.logLevelColor(palette, auto, Level.SEVERE));
        final var blank =
                new TuiLogConfiguration(new TuiLogLevelConfiguration("  "), null, null, null, null, null, null);
        assertEquals("#e06c75", TuiCommand.logLevelColor(palette, blank, Level.SEVERE));
    }

    @Test
    void lightThemeDefaultsFollowTheLightThemePalette() {
        final var light = Palette.from(
                "light",
                new TuiConfiguration(
                        null,
                        "auto",
                        "auto",
                        "auto",
                        "auto",
                        "auto",
                        "auto",
                        "auto",
                        "auto",
                        -1.0,
                        0.22,
                        0.14,
                        0.85,
                        "auto",
                        "auto",
                        "auto",
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
                        "auto",
                        new TuiConfiguration.ToolOutputConfiguration(128, 256 * 1024, 0),
                        null));
        assertEquals("#d1383d", TuiCommand.logLevelColor(light, null, Level.SEVERE));
        assertEquals("#8a621f", TuiCommand.logLevelColor(light, null, Level.WARNING));
        assertEquals("#1f7a88", TuiCommand.logLevelColor(light, null, Level.INFO));
    }

    private int distanceToBackground(final String hex) {
        final var color = TuiColors.parseHex(hex);
        final var bg = TuiColors.parseHex(palette.backgroundPanel());
        int distance = 0;
        for (int i = 0; i < 3; i++) {
            distance += Math.abs(color[i] - bg[i]);
        }
        return distance;
    }
}
