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

import java.util.Locale;

/**
 * Token colors (hex, without the leading {@code #}) for the syntax
 * highlighter, theme-aware: {@code yca.tui.codeTheme=auto} resolves to
 * one-dark on the dark theme and one-light on the light theme.
 */
public record CodeTheme(
        String string,
        String comment,
        String number,
        String keyword,
        String type,
        String tag,
        String attr,
        String annotation) {
    public static final CodeTheme ONE_DARK =
            new CodeTheme("98c379", "7f848e", "d19a66", "c678dd", "61afef", "e06c75", "56b6c2", "c586c0");
    public static final CodeTheme ONE_LIGHT =
            new CodeTheme("2f7d33", "a0a1a7", "986801", "a626a4", "4078f2", "e45649", "0184bc", "008080");

    /**
     * @param name       configured value: auto, one-dark or one-light
     * @param lightTheme true when the UI theme is "light"
     */
    public static CodeTheme parse(final String name, final boolean lightTheme) {
        final var normalized = name == null ? "auto" : name.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "one-dark", "one_dark", "dark" -> ONE_DARK;
            case "one-light", "one_light", "light" -> ONE_LIGHT;
            default -> lightTheme ? ONE_LIGHT : ONE_DARK;
        };
    }
}
