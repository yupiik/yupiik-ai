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

import java.util.Locale;

/**
 * Controls how much of the raw tool activity is shown in the CLI.
 * {@code COMPACT} collapses streaming tool output into progress markers and
 * human summaries; {@code VERBOSE} dims the full stream; {@code RAW} prints
 * tool calls and output verbatim (same as the legacy behaviour).
 */
public enum CliVerbosity {
    COMPACT,
    VERBOSE,
    RAW;

    public static CliVerbosity parse(final String value) {
        if (value == null || value.isBlank()) {
            return COMPACT;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "raw" -> RAW;
            case "verbose", "full" -> VERBOSE;
            default -> COMPACT;
        };
    }
}
