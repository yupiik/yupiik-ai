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
 * Three-state visibility of rendered tool invocations and outputs.
 *
 * <ol>
 *   <li>{@link #SHOWN}: full tool card/invocation plus its output.</li>
 *   <li>{@link #COLLAPSED}: a condensed summary (name and duration, failures
 *   marked) hiding args, output and the live stream.</li>
 *   <li>{@link #HIDDEN}: nothing is rendered for tools at all.</li>
 * </ol>
 */
public enum ToolOutputVisibility {
    SHOWN,
    COLLAPSED,
    HIDDEN;

    public boolean shown() {
        return this == SHOWN;
    }

    public boolean collapsed() {
        return this == COLLAPSED;
    }

    /**
     * Returns the next state when rotating over the three states following the
     * user-facing order shown -> hidden -> collapsed -> shown (so the collapsed
     * default advances to shown on the first Ctrl+Y).
     */
    public ToolOutputVisibility next() {
        return switch (this) {
            case SHOWN -> HIDDEN;
            case HIDDEN -> COLLAPSED;
            case COLLAPSED -> SHOWN;
        };
    }

    /**
     * Parses a user-facing keyword. Accepts {@code show|shown}, {@code collapse|collapsed},
     * {@code hide|hidden} plus the legacy booleans {@code true|false} (and {@code on|off})
     * so existing callers keep working. Returns {@code null} when nothing matches.
     */
    public static ToolOutputVisibility parse(final String raw) {
        if (raw == null) {
            return null;
        }
        final var value = raw.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "show", "shown", "true", "on" -> SHOWN;
            case "collapse", "collapsed" -> COLLAPSED;
            case "hide", "hidden", "false", "off" -> HIDDEN;
            default -> null;
        };
    }

    /** Maps the legacy boolean flag: {@code true} -> shown, {@code false} -> collapsed. */
    public static ToolOutputVisibility fromBoolean(final boolean value) {
        return value ? SHOWN : COLLAPSED;
    }
}
