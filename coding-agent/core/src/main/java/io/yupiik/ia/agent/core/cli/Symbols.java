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
package io.yupiik.ia.agent.core.cli;

/**
 * Centralizes the decorative glyphs used in CLI output. Unicode symbols are
 * used by default; switching to ASCII-only output maps every glyph to a
 * portable equivalent so scripts never see non-ASCII characters.
 */
public final class Symbols {
    private static final char[] UNICODE_SPINNER = {'⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'};
    private static final char[] ASCII_SPINNER = {'|', '/', '-', '\\'};

    private final boolean ascii;

    public Symbols(final boolean ascii) {
        this.ascii = ascii;
    }

    public String toolMarker() {
        return ascii ? "* " : "⏺ ";
    }

    public String resultPrefix() {
        return ascii ? "-> " : "↳ ";
    }

    public String infoMarker() {
        return ascii ? "[i] " : "ℹ ";
    }

    public String warningMarker() {
        return ascii ? "[!] " : "⚠ ";
    }

    public String errorMarker() {
        return ascii ? "[x] " : "✖ ";
    }

    public String currentMarker() {
        return ascii ? "*" : "●";
    }

    public String todoInProgress() {
        return ascii ? "[>] " : "[▸] ";
    }

    public String todoCompleted() {
        return ascii ? "[x] " : "[✓] ";
    }

    public String todoCancelled() {
        return ascii ? "[-] " : "[✕] ";
    }

    public String todoPending() {
        return ascii ? "[ ] " : "[·] ";
    }

    public String diffAdd() {
        return ascii ? "+ " : "➕ ";
    }

    public String diffDel() {
        return ascii ? "- " : "➖ ";
    }

    public char[] spinner() {
        return ascii ? ASCII_SPINNER : UNICODE_SPINNER;
    }

    public String boxTop() {
        return ascii ? " +" : " ┌";
    }

    public String boxBottom() {
        return ascii ? " +" : " └";
    }

    public String boxLine() {
        return ascii ? " | " : " │ ";
    }

    public String boxHorizontal() {
        return ascii ? "-" : "─";
    }

    public String boxCorner() {
        return ascii ? "+" : "┐";
    }

    public String boxCornerBottom() {
        return ascii ? "+" : "┘";
    }
}
