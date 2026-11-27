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

import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * Theme-aware {@link MarkdownStyles}. Unlike the library defaults (bare bold and a
 * barely-visible gray+dim inline code), strong emphasis gets a colored bold following
 * {@code yca.tui.strongColor} (default: the primary color) and inline code gets a
 * colored bold following {@code yca.tui.inlineCodeColor} (default: the active code
 * theme string tone) with no background chip and the dim modifier dropped, so code
 * matches the normal-text baseline and is told apart by weight, not by a backdrop.
 * Links, list markers and level-1 to level-3 headings are also overridden so the library's raw
 * ANSI defaults (terminal blue/cyan/reversed white) never leak into the theme: level 1 follows
 * the accent, level 2 the success (brand green) tone and level 3 the primary (brand purple) tone.
 *
 * <p>Inside already-colored regions (thinking, tool output) the strong accent and
 * inline code yield to the region tone: they stay bold but take the region
 * foreground, so emphasis never turns bright inside subdued prose.
 *
 * <p>In monochrome mode ({@code NO_COLOR}) every slot resolves to the plain style so
 * markdown decorations never force colors on a limited terminal.
 */
public final class MarkdownTheme {
    private MarkdownTheme() {}

    /**
     * Builds the markdown theme for the current UI state.
     *
     * @param palette         the resolved color palette (theme + monochrome aware)
     * @param codeTheme       the active code syntax theme (auto-resolved by the caller)
     * @param strongColor     configured bold color, {@code auto} (or blank) to follow the primary color
     * @param inlineCodeColor configured inline-code color, {@code auto} (or blank) to follow the code theme
     * @return the styles used by the transcript markdown renderer
     */
    public static MarkdownStyles of(
            final Palette palette, final CodeTheme codeTheme, final String strongColor, final String inlineCodeColor) {
        if (palette.mono()) {
            return monochrome();
        }
        final var strong = resolve(palette.primary(), strongColor);
        // the code theme tones carry no '#', Color.hex accepts both forms
        final var code = resolve(codeTheme.string(), inlineCodeColor);
        return MarkdownStyles.builder()
                .strong(Style.EMPTY.fg(Color.hex(strong)).bold())
                .inlineCode(Style.EMPTY.fg(Color.hex(code)).bold())
                .link(Style.EMPTY.fg(Color.hex(palette.primary())).underlined())
                .listMarker(Style.EMPTY.fg(Color.hex(palette.textMuted())))
                .heading(1, Style.EMPTY.fg(Color.hex(palette.accent())).bold())
                .heading(2, Style.EMPTY.fg(Color.hex(palette.success())).bold())
                .heading(3, Style.EMPTY.fg(Color.hex(palette.primary())).bold())
                .build();
    }

    /** Every slot plain so a NO_COLOR terminal stays free of forced colors. */
    private static MarkdownStyles monochrome() {
        final var builder = MarkdownStyles.builder()
                .strong(Style.EMPTY)
                .emphasis(Style.EMPTY)
                .strikethrough(Style.EMPTY)
                .inlineCode(Style.EMPTY)
                .codeBlock(Style.EMPTY)
                .link(Style.EMPTY)
                .blockquote(Style.EMPTY)
                .listMarker(Style.EMPTY)
                .html(Style.EMPTY)
                .horizontalRule(Style.EMPTY);
        for (int i = 1; i <= 6; i++) {
            builder.heading(i, Style.EMPTY);
        }
        return builder.build();
    }

    private static String resolve(final String auto, final String configured) {
        final var value = configured == null ? "" : configured.strip();
        if (value.isEmpty() || "auto".equalsIgnoreCase(value)) {
            return auto;
        }
        return value;
    }
}
