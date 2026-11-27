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

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

/**
 * Turns a raw input event into a signed scroll delta and applies it to a scroll offset.
 * <p>
 * Every scrollable view of the TUI (the transcript and each modal/overlay) goes through this
 * service so a wheel notch, <kbd>Up</kbd>/<kbd>Down</kbd> and <kbd>PgUp</kbd>/<kbd>PgDn</kbd>
 * move by the same amount everywhere, and so a single place defines the clamping rules.
 * Before this existed, each overlay hand-rolled its own arithmetic with a different page size
 * (5, 8, 10 or 20 rows) and only some of them honoured the mouse wheel.
 * <p>
 * Stateless and cheap to call on every event.
 */
@ApplicationScoped
public class ScrollService {
    /** Number of rows moved by a single mouse wheel notch, and by <kbd>Up</kbd>/<kbd>Down</kbd> respectively. */
    public static final int DEFAULT_LINES = 3;

    /** Number of rows moved by <kbd>PgUp</kbd>/<kbd>PgDn</kbd>. */
    public static final int DEFAULT_PAGE_LINES = 10;

    private final int lines;
    private final int pageLines;

    /**
     * @param configuration the TUI configuration, {@code null} is tolerated (headless wiring) and falls back to the defaults
     */
    public ScrollService(final TuiConfiguration configuration) {
        this.lines = positive(configuration == null ? 0 : configuration.scrollLines(), DEFAULT_LINES);
        this.pageLines = positive(configuration == null ? 0 : configuration.scrollPageLines(), DEFAULT_PAGE_LINES);
    }

    private static int positive(final int value, final int fallback) {
        return value > 0 ? value : fallback;
    }

    /**
     * @param event the raw event, may be {@code null}
     * @return {@code -lines} for a wheel-up, {@code +lines} for a wheel-down, {@code 0} for any other event
     */
    public int wheelDelta(final MouseEvent event) {
        if (event == null || !event.isScroll()) {
            return 0;
        }
        return event.kind() == MouseEventKind.SCROLL_UP ? -lines : lines;
    }

    /**
     * Raw key codes, not the binding driven {@code KeyEvent#isUp()}: several overlays own a text
     * filter (the picker, the feedback prompt, the search bar) and a binding driven check would
     * swallow characters such as <kbd>j</kbd>/<kbd>k</kbd> there.
     *
     * @param key the raw event, may be {@code null}
     * @return a negative value to scroll up, a positive one to scroll down, {@code 0} when the key does not scroll
     */
    public int keyDelta(final KeyEvent key) {
        if (key == null) {
            return 0;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            return -pageLines;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            return pageLines;
        }
        if (key.isKey(KeyCode.UP)) {
            return -1;
        }
        if (key.isKey(KeyCode.DOWN)) {
            return 1;
        }
        return 0;
    }

    /**
     * Applies a delta when the reachable offset is only known at render time, which is the case for
     * every overlay whose body is clipped to the frame: the renderer clamps the final value, this
     * only guarantees the offset never goes negative.
     *
     * @param current the current scroll offset
     * @param delta the signed delta to apply
     * @return the new offset, at least {@code 0}
     */
    public int apply(final int current, final int delta) {
        return Math.max(0, current + delta);
    }

    /**
     * @param current the current scroll offset
     * @param delta the signed delta to apply
     * @param max the highest reachable offset, negative or zero meaning "nothing to scroll"
     * @return the new offset, always within {@code [0, max]}
     */
    public int clamp(final int current, final int delta, final int max) {
        return Math.clamp(current + delta, 0, Math.max(0, max));
    }

    /**
     * @return the number of rows a single <kbd>PgUp</kbd>/<kbd>PgDn</kbd> moves
     */
    public int pageRows() {
        return pageLines;
    }
}
