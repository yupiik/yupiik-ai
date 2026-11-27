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

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.Locale;

/** Diff layout selection, independent of tool-output disclosure. */
@JsonModel
public enum DiffViewMode {
    AUTO("auto"),
    SIDE_BY_SIDE("side-by-side"),
    SIMPLE("simple");

    private final String value;

    DiffViewMode(final String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static DiffViewMode fromConfigurationString(final String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        final var parsed = parse(raw.replace('_', '-'));
        if (parsed == null) {
            throw new IllegalArgumentException("Unsupported diff view mode: " + raw);
        }
        return parsed;
    }

    /** Returns null for null or unsupported input. */
    public static DiffViewMode parse(final String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "side-by-side" -> SIDE_BY_SIDE;
            case "simple" -> SIMPLE;
            default -> null;
        };
    }
}
