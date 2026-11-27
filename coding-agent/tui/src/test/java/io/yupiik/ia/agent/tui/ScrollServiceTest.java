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

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScrollServiceTest {
    @Test
    void nullConfigurationFallsBackToTheDefaults() {
        final var service = new ScrollService(null);
        assertEquals(-3, service.wheelDelta(MouseEvent.scrollUp(4, 5)));
        assertEquals(3, service.wheelDelta(MouseEvent.scrollDown(4, 5)));
        assertEquals(10, service.pageRows());
    }

    @Test
    void nonScrollMouseEventsHaveNoDelta() {
        final var service = new ScrollService(null);
        assertEquals(0, service.wheelDelta(MouseEvent.press(MouseButton.LEFT, 1, 2)));
        assertEquals(0, service.wheelDelta(null));
    }

    @Test
    void wheelDeltaFollowsTheConfiguredLineCount() {
        final var service = new ScrollService(tuiConfiguration(7, 20));
        assertEquals(-7, service.wheelDelta(MouseEvent.scrollUp(0, 0)));
        assertEquals(7, service.wheelDelta(MouseEvent.scrollDown(0, 0)));
    }

    @Test
    void keyDeltaMapsArrowsAndPageKeys() {
        final var service = new ScrollService(tuiConfiguration(3, 20));
        assertEquals(-1, service.keyDelta(KeyEvent.ofKey(KeyCode.UP)));
        assertEquals(1, service.keyDelta(KeyEvent.ofKey(KeyCode.DOWN)));
        assertEquals(-20, service.keyDelta(KeyEvent.ofKey(KeyCode.PAGE_UP)));
        assertEquals(20, service.keyDelta(KeyEvent.ofKey(KeyCode.PAGE_DOWN)));
    }

    @Test
    void otherKeysAndNullsHaveNoDelta() {
        final var service = new ScrollService(null);
        assertEquals(0, service.keyDelta(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals(0, service.keyDelta(KeyEvent.ofChar('k')));
        assertEquals(0, service.keyDelta(null));
    }

    @Test
    void applyNeverGoesNegative() {
        final var service = new ScrollService(null);
        assertEquals(8, service.apply(5, 3));
        assertEquals(0, service.apply(1, -3));
        assertEquals(0, service.apply(0, -3));
        assertEquals(5, service.apply(5, 0));
    }

    @Test
    void clampBoundsBothEnds() {
        final var service = new ScrollService(null);
        assertEquals(8, service.clamp(5, 3, 10));
        assertEquals(0, service.clamp(1, -3, 10));
        assertEquals(10, service.clamp(8, 5, 10));
        assertEquals(0, service.clamp(0, -1, 0));
        assertEquals(0, service.clamp(0, 1, -4));
    }

    @Test
    void nonPositiveConfiguredValuesFallBackToTheDefaults() {
        final var service = new ScrollService(tuiConfiguration(0, -1));
        assertEquals(-ScrollService.DEFAULT_LINES, service.wheelDelta(MouseEvent.scrollUp(0, 0)));
        assertEquals(ScrollService.DEFAULT_PAGE_LINES, service.pageRows());
    }

    private TuiConfiguration tuiConfiguration(final int scrollLines, final int scrollPageLines) {
        return new TuiConfiguration(
                null,
                "#9d7cd8",
                "#fab283",
                "#7fd88f",
                "#f5a742",
                "#e06c75",
                "#b896d8",
                "#eeeeee",
                "#808080",
                -1.0,
                0.22,
                0.14,
                0.85,
                "#484848",
                "#606060",
                "#141414",
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
                scrollLines,
                scrollPageLines,
                "",
                0.8,
                "#ff5555",
                new TuiConfiguration.ToolOutputConfiguration(128, 256 * 1024, 0),
                null);
    }
}
