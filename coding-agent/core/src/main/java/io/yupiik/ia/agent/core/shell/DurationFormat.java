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
package io.yupiik.ia.agent.core.shell;

import java.util.Locale;

/**
 * Duration rendering for the terminal UI (status strips, usage lines, tool cards, /metrics):
 * turns unreadable milliseconds into a compact moment.js-like form. Purely presentational - the
 * machine-readable outputs (run metrics files, transcript tool blocks, details log) keep raw
 * milliseconds.
 */
public final class DurationFormat {
    private DurationFormat() {
        // no-op.
    }

    /**
     * Formats a duration in milliseconds using the two most significant units, integers below the
     * minute then one decimal for sub-minute seconds: {@code 850ms}, {@code 1.5s}, {@code 12.3s},
     * {@code 3m 25s}, {@code 8h 20m}, {@code 2d 3h}. The lower unit is dropped when zero
     * ({@code 120000} -> {@code 2m}) and negative values are clamped to {@code 0ms}.
     *
     * @param millis the duration in milliseconds.
     * @return the humanized duration.
     */
    public static String humanize(final long millis) {
        final long ms = Math.max(0, millis);
        if (ms < 1_000) {
            return ms + "ms";
        }
        final long totalSeconds = ms / 1_000;
        if (totalSeconds < 60) {
            final var seconds = String.format(Locale.ROOT, "%.1f", ms / 1000.0);
            return (seconds.endsWith(".0") ? seconds.substring(0, seconds.length() - 2) : seconds) + "s";
        }
        final long totalMinutes = totalSeconds / 60;
        if (totalMinutes < 60) {
            final var seconds = totalSeconds % 60;
            return seconds == 0 ? totalMinutes + "m" : totalMinutes + "m " + seconds + "s";
        }
        final long totalHours = totalMinutes / 60;
        if (totalHours < 24) {
            final var minutes = totalMinutes % 60;
            return minutes == 0 ? totalHours + "h" : totalHours + "h " + minutes + "m";
        }
        final long days = totalHours / 24;
        final var hours = totalHours % 24;
        return hours == 0 ? days + "d" : days + "d " + hours + "h";
    }
}
