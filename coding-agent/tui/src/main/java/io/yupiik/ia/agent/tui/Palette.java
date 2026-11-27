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
import dev.tamboui.style.Style;

/**
 * Resolved color palette. Each slot follows the active named theme ("dark" or
 * "light", see {@code tui.theme} and the persisted /theme switch) unless the
 * matching {@code yca.tui.*} property is explicitly set to a hex color: a
 * blank or {@code auto} value resolves to the theme value, any other value is
 * used verbatim, so per-color overrides always win over the theme.
 *
 * <p>When the {@code NO_COLOR} environment variable is set (any non-empty
 * value, see https://no-color.org/), the palette turns monochrome: every
 * style helper resolves to the terminal default so the UI stays usable on
 * limited terminals and for screen-reader pipelines.
 */
public record Palette(
        String accent,
        String primary,
        String success,
        String warning,
        String error,
        String info,
        String text,
        String textMuted,
        String border,
        String borderActive,
        String backgroundPanel,
        String yolo,
        boolean mono) {

    /** Doc-aligned dark palette (yupiik minisite surface/brand tones). */
    private static final Palette DARK = new Palette(
            "#02ABEA", "#D858FF", "#46F72F", "#fbbf24", "#f87171", "#7ad2f7", "#eef0f3", "#9aa4b2", "#667587",
            "#7ad2f7", "#12141a", "#ff5555", false);

    /** Doc-aligned light palette (darker WCAG-safe brand variants on white). */
    private static final Palette LIGHT = new Palette(
            "#056EBB", "#a84fc9", "#2f7a43", "#8a621f", "#d1383d", "#1f7a88", "#24262b", "#626873", "#8995a3",
            "#056EBB", "#ffffff", "#ff5555", false);

    public static Palette from(final String theme, final TuiConfiguration c) {
        return from(theme, c, noColorRequested());
    }

    public static Palette from(final String theme, final TuiConfiguration c, final boolean mono) {
        final var builtin = "light".equalsIgnoreCase(theme) ? LIGHT : DARK;
        return new Palette(
                resolve(builtin.accent(), c.accent()),
                resolve(builtin.primary(), c.primary()),
                resolve(builtin.success(), c.success()),
                resolve(builtin.warning(), c.warning()),
                resolve(builtin.error(), c.error()),
                resolve(builtin.info(), c.info()),
                resolve(builtin.text(), c.text()),
                resolve(builtin.textMuted(), c.textMuted()),
                resolve(builtin.border(), c.border()),
                resolve(builtin.borderActive(), c.borderActive()),
                resolve(builtin.backgroundPanel(), c.backgroundPanel()),
                resolve(builtin.yolo(), c.yolo()),
                mono);
    }

    /** @return the theme value when the configured slot is blank or {@code auto}, the configured hex otherwise. */
    private static String resolve(final String themeValue, final String configured) {
        final var value = configured == null ? "" : configured.strip();
        if (value.isEmpty() || "auto".equalsIgnoreCase(value)) {
            return themeValue;
        }
        return value;
    }

    /** @return true when NO_COLOR is set to a non-empty value. */
    public static boolean noColorRequested() {
        final var value = System.getenv("NO_COLOR");
        return value != null && !value.isBlank();
    }

    /** @return the resolved color, or the terminal default in mono mode. */
    public Color color(final String hex) {
        return mono ? Color.RESET : Color.hex(hex);
    }

    /** @return a foreground style, or the plain style in mono mode. */
    public Style style(final String hex) {
        return mono ? Style.EMPTY : Style.EMPTY.fg(Color.hex(hex));
    }

    /** @return a bold foreground style, or the plain style in mono mode. */
    public Style bold(final String hex) {
        return mono ? Style.EMPTY : Style.EMPTY.fg(Color.hex(hex)).bold();
    }

    /** @return the main text style. */
    /** @return the muted (secondary) text style. */
    public Style muted() {
        return style(textMuted);
    }

    /** @return an opacity-blended foreground style over the panel background. */
    public Style blended(final String hex, final double opacity) {
        return mono ? Style.EMPTY : Style.EMPTY.fg(TuiColors.blend(hex, backgroundPanel, opacity));
    }
}
