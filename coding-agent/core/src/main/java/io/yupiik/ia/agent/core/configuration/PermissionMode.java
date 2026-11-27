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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;
import java.util.Locale;

@JsonModel
public enum PermissionMode {
    ASK,
    AUTO_EDIT,
    YOLO,
    FULL_YOLO;

    /** User-facing string forms accepted by /permissions and CLI parsing, matching {@link #fromConfigurationString}. */
    public static final List<String> VALUES = List.of("ask", "auto_edit", "yolo", "full_yolo");

    public static boolean isValue(final String value) {
        return value != null && (fromConfigurationString(value) != ASK || "ask".equals(strip(value)));
    }

    private static String strip(final String value) {
        return value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public static PermissionMode fromConfigurationString(final String value) {
        if (value == null || value.isBlank()) {
            return ASK;
        }
        return switch (strip(value)) {
            case "auto_edit", "autoedit" -> AUTO_EDIT;
            case "yolo", "all" -> YOLO;
            case "full_yolo" -> FULL_YOLO;
            default -> ASK;
        };
    }
}
