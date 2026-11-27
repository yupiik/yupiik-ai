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

/**
 * Wire behavior of the secret redaction layer.
 *
 * <p>{@code redact} (default) replaces secrets with format-preserving fakes before the request
 * leaves and restores only provenance-registry values inbound; {@code passthrough} sends raw values
 * (per-session / debugging escape); {@code fail} aborts the outbound LLM call when any secret would
 * go over the wire (the strict "never leak" gate). Inbound restore always runs.</p>
 */
@JsonModel
public enum SecretRedactionMode {
    REDACT,
    PASSTHROUGH,
    FAIL;

    /** User-facing string forms accepted by {@code /secrets} and config, matching {@link #fromConfigurationString}. */
    public static final List<String> VALUES = List.of("redact", "passthrough", "fail");

    private static String strip(final String value) {
        return value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public static SecretRedactionMode fromConfigurationString(final String value) {
        if (value == null || value.isBlank()) {
            return REDACT;
        }
        return switch (strip(value)) {
            case "passthrough", "pass", "none", "off", "raw", "disabled" -> PASSTHROUGH;
            case "fail", "block", "deny" -> FAIL;
            default -> REDACT;
        };
    }
}
