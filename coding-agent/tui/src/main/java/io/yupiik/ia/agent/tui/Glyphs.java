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

/**
 * Terminal glyph set: unicode symbols by default with ASCII fallbacks
 * ({@code tui.glyphs=ascii}) for terminals with limited font coverage.
 */
public final class Glyphs {
    private static final Glyphs UNICODE = new Glyphs(false);
    private static final Glyphs ASCII = new Glyphs(true);

    private final boolean ascii;

    private Glyphs(final boolean ascii) {
        this.ascii = ascii;
    }

    public static Glyphs of(final String mode) {
        return mode != null && "ascii".equalsIgnoreCase(mode.strip()) ? ASCII : UNICODE;
    }

    public boolean ascii() {
        return ascii;
    }

    /** Success marker. */
    public String check() {
        return ascii ? "+" : "✓";
    }

    /** Error marker. */
    public String cross() {
        return ascii ? "x" : "✖";
    }

    /** Running marker. */
    public String running() {
        return ascii ? "o" : "◌";
    }

    /**
     * Tab-bar mode marker: distinct shapes per mode (plan vs build) so the mode
     * stays readable even when NO_COLOR collapses every color to the terminal
     * default (the same concern {@link #roleMark(boolean)} addresses).
     *
     * @param build {@code true} for the heaviest (build) shape
     */
    public String modeMark(final boolean build) {
        return ascii ? (build ? "O" : "o") : (build ? "◆" : "▪");
    }

    /** Accent gutter bar (thick). */
    public String bar() {
        return ascii ? "|" : "┃";
    }

    /** Thought gutter bar (thin). */
    public String thinBar() {
        return ascii ? "|" : "│";
    }

    /**
     * Turn header mark carrying the role by glyph shape rather than color, for mono
     * terminals ({@code NO_COLOR}) where every style collapses to the default and
     * a single neutral mark alone would make both roles look identical.
     *
     * @param user {@code true} for a user turn
     */
    public String roleMark(final boolean user) {
        return ascii ? (user ? ">" : "<") : (user ? "▌" : "▐");
    }

    /** Rule filling the rest of a turn header line, out to the panel edge. */
    public String rule() {
        return ascii ? "-" : "─";
    }

    /** Status dot (MCP servers etc). */
    public String dot() {
        return ascii ? "*" : "●";
    }

    /** Busy caret. */
    public String caret() {
        return ascii ? ">" : "▌";
    }

    /** "N more" continuation marker. */
    public String more() {
        return ascii ? "-> " : "⤷ ";
    }
}
