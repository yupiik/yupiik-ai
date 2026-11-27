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
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import java.time.Instant;

/**
 * Sidebar quota rendering: the compact window label, the single-row Unicode-block gauge bar and
 * the "time until reset" caption. Pure string/line building - no state and no I/O.
 */
final class TuiQuotaRender {
    private TuiQuotaRender() {
        // utility
    }

    static String compactWindowLabel(final AgentEvent.UsageUpdated.Quota.Window window) {
        final var seconds = window.windowSeconds();
        if (seconds != null && seconds > 0) {
            if (seconds % 86400 == 0) {
                return seconds / 86400 + "d";
            }
            if (seconds % 3600 == 0) {
                return seconds / 3600 + "h";
            }
            if (seconds % 60 == 0) {
                return seconds / 60 + "m";
            }
            return seconds + "s";
        }
        final var label = window.label();
        return label == null || label.isBlank() ? "quota" : label;
    }

    // Unicode block fill chars mirror the Gauge widget's sub-cell rendering; the partial
    // blocks (▏..▉) give a smooth fill edge between 0 and full (█).
    private static final char QUOTA_BLOCK_FULL = '█';
    private static final char[] QUOTA_BLOCK_PARTIAL = {'▏', '▎', '▍', '▌', '▋', '▊', '▉'};

    /**
     * Renders a single account-quota window into one sidebar row with the labels around the
     * bar, mirroring the web panel: the compact window kind on the left, the consumed {@code used}
     * percentage on the right, and a Unicode-block fill bar across the middle. Unlike the Gauge
     * widget (which only centers its label) this places both labels at the row edges so the bar,
     * sized by {@code used} (0-100; {@code null} = unknown), stays visible between them.
     */
    static Line quotaWindowRow(
            final int width,
            final String kind,
            final String pct,
            final Double used,
            final Color barColor,
            final Style labelStyle,
            final Style valueStyle) {
        final var kindText = TuiTextRender.ellipsize(kind, Math.max(1, width - pct.length() - 2));
        final var barStart = kindText.length() + 1; // one space after the kind
        final var barEnd = Math.max(barStart, width - pct.length());
        final var barWidth = barEnd - barStart;
        final var bar = new StringBuilder(barWidth);
        if (barWidth > 0 && used != null) {
            final var filled = (int) Math.round(barWidth * used / 100.0);
            int x;
            for (x = 0; x < Math.min(filled, barWidth); x++) {
                bar.append(QUOTA_BLOCK_FULL);
            }
            if (filled < barWidth) {
                final var frac = barWidth * used / 100.0 - filled;
                if (frac > 0 && x < barWidth) {
                    bar.append(QUOTA_BLOCK_PARTIAL[Math.min(QUOTA_BLOCK_PARTIAL.length - 1, (int) (frac * 8))]);
                    x++;
                }
            }
            while (x < barWidth) {
                bar.append(' ');
                x++;
            }
        } else {
            bar.append(" ".repeat(Math.max(0, barWidth)));
        }
        final var barStyle = Style.EMPTY.fg(barColor);
        return Line.from(
                Span.styled(kindText, labelStyle),
                Span.styled(bar.toString(), barStyle),
                Span.styled(" " + pct, valueStyle));
    }

    /**
     * Compact "time until reset" caption from an epoch seconds reset deadline, e.g. "now",
     * "in 2d 3h", "in 2h 15m", "in 10m". {@code resetAt <= 0} (already at/over the deadline)
     * yields "now".
     */
    static String formatQuotaReset(final long resetAt) {
        final var seconds = Math.max(0, resetAt - Instant.now().getEpochSecond());
        if (seconds == 0) {
            return "now";
        }
        if (seconds >= 86400) {
            return "in " + (seconds / 86400) + "d " + (seconds % 86400 / 3600) + "h";
        }
        if (seconds >= 3600) {
            return "in " + (seconds / 3600) + "h " + (seconds % 3600 / 60) + "m";
        }
        return "in " + Math.max(1, (seconds + 59) / 60) + "m";
    }
}
