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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

@ApplicationScoped
@RootConfiguration("yca.tui")
@JsonModel
public record TuiConfiguration(
        @Property(documentation = "UI theme selection settings.")
        TuiThemeConfiguration theme,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Accent color (hex), used for panel titles and highlights; "
                        + "auto (default) follows the active theme (dark or light) palette.")
        String accent,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Primary color (hex), used for labels and emphasis; "
                        + "auto (default) follows the active theme (dark or light) palette.")
        String primary,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Success color (hex), used for ready state and low context usage; "
                        + "auto (default) follows the active theme (dark or light) palette.")
        String success,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Warning color (hex), used for busy state, approvals and medium context usage; "
                        + "auto (default) follows the active theme (dark or light) palette.")
        String warning,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Error color (hex), used for errors and high context usage; "
                        + "auto (default) follows the active theme (dark or light) palette.")
        String error,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Info color (hex), used for gauges and informational values; "
                        + "auto (default) follows the active theme (dark or light) palette.")
        String info,

        @Property(
                defaultValue = "\"auto\"",
                documentation =
                        "Main text color (hex); auto (default) follows the active theme (dark or light) palette.")
        String text,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Muted text color (hex), used for hints and secondary values; "
                        + "auto (default) follows the active theme (dark or light) palette.")
        String textMuted,

        @Property(
                defaultValue = "-1.0",
                documentation =
                        "Transparency (0..1) of the thinking/reasoning text toward the panel background: 0 is fully opaque, darker prose, "
                                + "higher values fade thoughts out (terminals have no alpha, the color is pre-blended). "
                                + "Use -1 (default) for a theme-aware value derived from thinkingOpacityDark/thinkingOpacityLight, "
                                + "so thoughts read as italic and slightly dimmed but stay clearly legible.")
        double thinkingOpacity,

        @Property(
                defaultValue = "0.22",
                documentation =
                        "Theme-aware default thought fade (see thinkingOpacity) used on the dark theme when thinkingOpacity is auto (-1).")
        double thinkingOpacityDark,

        @Property(
                defaultValue = "0.14",
                documentation =
                        "Theme-aware default thought fade (see thinkingOpacity) used on the light theme when thinkingOpacity is auto (-1).")
        double thinkingOpacityLight,

        @Property(
                defaultValue = "0.35",
                documentation =
                        "Transparency (0..1) of the tool output text toward the panel background: 0 is fully opaque, higher values fade tool output out (terminals have no alpha, the color is pre-blended).")
        double toolOutputOpacity,

        @Property(
                defaultValue = "\"auto\"",
                documentation =
                        "Panel border color (hex); auto (default) follows the active theme (dark or light) palette.")
        String border,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Active/focused panel border color (hex); "
                        + "auto (default) follows the active theme (dark or light) palette.")
        String borderActive,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Panel background color (hex); "
                        + "auto (default) follows the active theme (dark or light) palette.")
        String backgroundPanel,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Bold (**strong**) prose color (hex), or auto to follow the primary color.")
        String strongColor,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Inline code color (hex), or auto to follow the active code theme.")
        String inlineCodeColor,

        @Property(
                defaultValue = "4000L",
                documentation =
                        "How long (milliseconds) a transient toast notice stays visible. Set to 0 to disable toasts.")
        long toastDurationMillis,

        @Property(
                defaultValue = "3500L",
                documentation =
                        "Safety repaint: longest time (milliseconds) the UI can go without a full redraw while idle. "
                                + "Any state change still repaints instantly (on the next tick); this only bounds missed "
                                + "updates so the screen can never stay stale longer than this. Keep strictly below the "
                                + "5s render-stall timeout. Set to 0 to disable the safety repaint.")
        long heartbeatMillis,

        @Property(
                defaultValue = "true",
                documentation =
                        "Persist submitted prompts to <storageHome>/history so they survive restarts. "
                                + "Set to false to disable history persistence entirely (in-memory Up/Down recall for the current session stays active).")
        boolean history,

        @Property(
                defaultValue = "\"unicode\"",
                documentation = "Glyph set: unicode (default) or ascii for terminals with limited font coverage.")
        String glyphs,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Syntax highlighting color theme: auto (follows the UI theme), one-dark or one-light.")
        String codeTheme,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Tool output formatting: auto detects and reformats markdown/JSON/XML/YAML/diffs, "
                        + "off keeps raw output only.")
        String toolOutputFormat,

        @Property(
                documentation = "Optional pricing table used to estimate the session cost, entries formatted "
                        + "`model-substring=in/out` with USD per million tokens, e.g. `gpt-4.1-mini=0.4/1.6`. "
                        + "The first matching entry wins; leave empty to hide cost display.")
        List<String> pricing,

        @Property(documentation = "Conversation transcript retention settings.")
        TuiTranscriptConfiguration transcript,

        @Property(
                documentation =
                        "Per-level colors of the captured log regions (`yca.tui.log.<level>.color`, see TuiLogLevelConfiguration).")
        TuiLogConfiguration log,

        @Property(
                defaultValue = "true",
                documentation =
                        "Hide the terminal hardware cursor while the TUI runs: only the in-app drawn caret is shown "
                                + "on the prompt. Disable to keep the terminal cursor visible next to the drawn one.")
        boolean hideCursor,

        @Property(
                defaultValue = "3",
                documentation =
                        "Rows moved by a single mouse wheel notch, and by Up/Down, in the transcript and in every modal/overlay.")
        int scrollLines,

        @Property(
                defaultValue = "10",
                documentation = "Rows moved by PgUp/PgDn, in the transcript and in every modal/overlay.")
        int scrollPageLines,

        @Property(
                defaultValue = "\"auto\"",
                documentation =
                        "Screen background: auto (default) and blank keep the terminal background touched, "
                                + "which lets a transparent terminal (e.g. Terminator) show through. Set a hex color to paint "
                                + "a translucent veil of that color over the detected terminal background (see backgroundOpacity), "
                                + "so the terminal stays visible underneath. Panels and cards draw their own backgroundPanel-based colors on top.")
        String background,

        @Property(
                defaultValue = "0.8",
                documentation =
                        "Opacity (0..1) of the translucent screen background veil applied when yca.tui.background is an "
                                + "explicit hex color: 0 lets the terminal show through fully, 1 paints the color opaque.")
        double backgroundOpacity,

        @Property(
                defaultValue = "\"auto\"",
                documentation =
                        "Yolo approval color (hex), used for the red 'yolo ⚠' badge in the status bar when the "
                                + "approval mode is set to yolo (danger mode); auto (default) follows the active theme (dark or light) palette.")
        String yolo,

        @Property(
                documentation =
                        "Tool output retention (full outputs kept for the Ctrl+O overlay and tool-card expansion).")
        ToolOutputConfiguration toolOutput,

        @Property(documentation = "Markdown quote and callout colors; auto follows the active palette.")
        MarkdownColors markdown) {

    @JsonModel
    public record MarkdownColors(
            @Property(defaultValue = "\"auto\"", documentation = "Quote rail color; auto follows muted text.")
            String quote,

            @Property(defaultValue = "\"auto\"", documentation = "NOTE callout color; auto follows info.")
            String note,

            @Property(defaultValue = "\"auto\"", documentation = "TIP callout color; auto follows success.")
            String tip,

            @Property(defaultValue = "\"auto\"", documentation = "IMPORTANT callout color; auto follows primary.")
            String important,

            @Property(defaultValue = "\"auto\"", documentation = "WARNING callout color; auto follows warning.")
            String warning,

            @Property(defaultValue = "\"auto\"", documentation = "CAUTION callout color; auto follows error.")
            String caution) {}

    /**
     * Retention limits of the captured tool outputs (see {@link TuiConfiguration#toolOutput()}).
     */
    @JsonModel
    public record ToolOutputConfiguration(
            @Property(
                    defaultValue = "128",
                    documentation =
                            "Maximum number of distinct tool calls kept per tab. Re-recording an existing call (stream chunks, "
                                    + "its finish record) never counts as a new call, so only distinct calls consume a slot.")
            int maxEntries,

            @Property(
                    defaultValue = "262144",
                    documentation =
                            "Maximum characters kept per tool output (256 KiB); longer outputs keep only the tail, flagged as truncated.")
            int maxOutputChars,

            @Property(
                    defaultValue = "0L",
                    documentation =
                            "Maximum total characters kept for all tool outputs of a tab (0 = unbounded, bounded by maxEntries × maxOutputChars "
                                    + "= 32 MiB at the defaults). When exceeded, the oldest tool calls are dropped first until the budget fits.")
            long maxTotalChars) {}
}
