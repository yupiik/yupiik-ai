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

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;

/**
 * Shared rendering helper for the centered overlay dialogs (approval, toast,
 * feedback, edit plan, pickers). Takes the boxed rectangle plus a title and
 * border color, clears the area, draws the rounded frame and returns the inner
 * drawable region - or null when the box is too small to host content.
 *
 * <p>Keeps the modal dialogs visually consistent (same rounding, borders,
 * title treatment) while removing the repeated Clear + Block boilerplate.</p>
 */
public final class Modal {

    private Modal() {
        // utility
    }

    public static Rect box(final Frame frame, final Rect rect, final String title, final Color borderColor) {
        if (rect == null || rect.isEmpty()) {
            return null;
        }
        frame.renderWidget(Clear.INSTANCE, rect);
        final var block = Block.builder()
                .title(title)
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(borderColor))
                .build();
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        return inner.isEmpty() ? null : inner;
    }

    /** Centered box: width/height, y placed at a fraction (0..1) of the remaining frame height. */
    public static Rect rect(final Frame frame, final int width, final int height, final double yFraction) {
        final var w = Math.clamp(width, 1, frame.width());
        final var h = Math.clamp(height, 1, frame.height());
        final var x = Math.max(0, (frame.width() - w) / 2);
        final var y = Math.max(0, (int) ((frame.height() - h) * yFraction));
        return new Rect(x, y, w, h);
    }
}
