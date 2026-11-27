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

/**
 * Single source of truth for the busy-status labels shown by the TUI and the
 * CLI while a turn runs. Both surfaces append the same text after their
 * spinner glyph, so "thinking..." never renders as "◱ thinking" in one place
 * and "🕗 thinking..." in another.
 *
 * <p>Spinner glyph policy (kept per-phase on purpose): clock = first-token
 * wait, square corners = streaming / calling a tool, dots = generic work,
 * bouncing ball = background ops (compaction, code-graph resync).</p>
 */
public final class BusyLabels {
    private BusyLabels() {
        // utility
    }

    /** {@code thinking... (Ns)} with the elapsed seconds, always parenthesised. */
    public static String thinking(final long seconds) {
        return "thinking... (" + seconds + "s)";
    }

    /**
     * {@code thinking...} without any time: for surfaces whose spinner appends its
     * own elapsed counter each frame (the CLI {@code Spinner}), so the label must
     * not bake a time in or the row would render it twice.
     */
    public static String thinking() {
        return "thinking...";
    }

    /** {@code calling <command>} (the square-corner phase). */
    public static String calling(final String command) {
        return "calling " + (command == null ? "" : command);
    }

    /** Generic agent-work label. */
    public static String working() {
        return "agent is working...";
    }

    /** Background compaction label with chunk progress. */
    public static String compacting(final int chunk, final int total) {
        return total > 1 ? "compacting (chunk " + chunk + "/" + total + ")..." : "compacting...";
    }
}
