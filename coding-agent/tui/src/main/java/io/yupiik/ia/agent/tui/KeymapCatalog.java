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

import java.util.List;

/**
 * Single source of truth for the interactive key bindings: consumed by the
 * hints bar (short, contextual) and by {@code /shortcuts} (full markdown
 * table) so the two can never drift.
 */
public final class KeymapCatalog {
    /** A documented binding; {@code core} bindings are shown in the default hints bar. */
    public record Binding(String key, String label, String group, boolean core) {}

    private KeymapCatalog() {}

    /** All documented bindings, in display order. */
    public static List<Binding> all() {
        return List.of(
                // input line
                new Binding("Enter", "send prompt", "Input", true),
                new Binding("Shift+Enter", "newline (Alt+Enter works everywhere)", "Input", false),
                new Binding("Tab", "complete", "Input", false),
                new Binding("Up/Down", "history / move", "Input", false),
                new Binding("Ctrl+R", "search history", "Input", true),
                new Binding("Ctrl+Z", "undo", "Input", false),
                new Binding("Alt+Z", "redo", "Input", false),
                new Binding("Ctrl+A/E", "line start/end", "Input", false),
                new Binding("Ctrl+K/U", "kill to end/start", "Input", false),
                new Binding("Ctrl+W", "delete word back", "Input", false),
                new Binding("Alt+Backspace", "delete word back", "Input", false),
                new Binding("Alt+Y", "yank (cycle kills)", "Input", false),
                new Binding("Alt+B/F", "word back/forward", "Input", false),
                new Binding("Shift+Tab", "flip permission mode", "Input", false),
                // view
                new Binding("Ctrl+F", "find in transcript", "View", true),
                new Binding("Ctrl+O", "full tool output", "View", true),
                new Binding("Alt+Up/Down", "prev/next tool call", "View", false),
                new Binding("Alt+K/J", "prev/next tool call (portable)", "View", false),
                new Binding("Alt+C", "copy last code block", "View", false),
                new Binding("Alt+O", "open last surfaced link (e.g. login URL)", "View", false),
                new Binding("PgUp/PgDn", "scroll", "View", false),
                new Binding("Mouse wheel", "scroll (view and overlays)", "View", false),
                new Binding("Ctrl+Home/End", "jump top/bottom", "View", false),
                new Binding("Ctrl+Shift+C", "copy selection", "View", false),
                new Binding("Ctrl+T", "toggle thinking", "View", false),
                new Binding("Ctrl+N", "new session tab", "Tabs", false),
                new Binding("Alt+W / Ctrl+Shift+W", "close current tab", "Tabs", false),
                new Binding("Ctrl+Tab", "next tab", "Tabs", false),
                new Binding("Ctrl+Shift+Tab", "previous tab", "Tabs", false),
                new Binding("Ctrl+PageDown", "next tab (alt)", "Tabs", false),
                new Binding("Ctrl+PageUp", "previous tab (alt)", "Tabs", false),
                new Binding("Alt+1..9", "switch to tab N", "Tabs", false),
                new Binding("Ctrl+Y", "cycle tool blocks (shown/collapsed/hidden)", "View", false),
                new Binding("Ctrl+P", "cycle todos panel", "View", false),
                new Binding("Alt+?", "show all shortcuts", "View", true),
                // session
                new Binding("Ctrl+S", "compact context", "Session", false),
                new Binding("Ctrl+L", "clear conversation", "Session", false),
                new Binding("Ctrl+X", "remove queued prompt", "Session", false),
                new Binding("Alt+E", "show last error", "Session", false),
                new Binding("Alt+I", "show last notification", "Session", false),
                new Binding("Ctrl+C", "interrupt / quit", "Session", true),
                new Binding("Alt+X", "dismiss error banner", "Overlays", false),
                // overlays
                new Binding("Esc", "close / cancel / dismiss error", "Overlays", true),
                new Binding("n/N", "next/previous match", "Overlays", false),
                new Binding("1/2/3", "deny / allow / always", "Overlays", false));
    }

    /** Compact hints while a turn is running. */
    public static List<Binding> busyHints() {
        return bindings("Ctrl+C", "Ctrl+F", "Ctrl+O", "Ctrl+S");
    }

    /** Compact hints while an overlay is open. */
    public static List<Binding> overlayHints() {
        return List.of(
                new Binding("Esc", "close", "Overlays", true),
                new Binding("Wheel/Up/Down", "scroll", "Overlays", true));
    }

    /** Compact hints in the idle state. */
    public static List<Binding> idleHints() {
        return all().stream().filter(Binding::core).toList();
    }

    /** Full markdown table for {@code /shortcuts}. */
    public static String markdown() {
        final var out = new StringBuilder("## Shortcuts\n\n");
        var group = "";
        for (final var binding : all()) {
            if (!binding.group().equals(group)) {
                group = binding.group();
                out.append("\n| Key | ").append(group).append(" |\n|---|---|\n");
            }
            out.append("| `")
                    .append(binding.key())
                    .append("` | ")
                    .append(binding.label())
                    .append(" |\n");
        }
        return out.toString();
    }

    private static List<Binding> bindings(final String... keys) {
        return all().stream()
                .filter(b -> {
                    for (final var key : keys) {
                        if (b.key().equals(key)) {
                            return true;
                        }
                    }
                    return false;
                })
                .toList();
    }
}
