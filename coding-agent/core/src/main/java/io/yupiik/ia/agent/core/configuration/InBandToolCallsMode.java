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
import java.util.Locale;

/**
 * Whether in-band tool-call markup that some models leak into the text {@code content} channel
 * (DeepSeek-style DSML {@code <|DSML|tool_calls>} blocks, their V3.1/ASCII manglings) is extracted
 * into structured tool calls when the provider did not emit them as native {@code tool_calls}.
 *
 * <p>This is the fallback that lets local backends (llama.cpp / ollama / ninfer style) serve models
 * which write their tool call in the text channel even when structured tools are declared. It only
 * ever runs when the assistant message came back with <em>no</em> native tool calls; structured
 * calls win unconditionally. Extracted markup is stripped from the stored / resent content so the
 * wire and the persistence never show the raw tags.</p>
 */
@JsonModel
public enum InBandToolCallsMode {
    /** Never extract in-band markup: only native {@code tool_calls} are used. */
    DISABLED,
    /** Always extract in-band markup from the text channel when no native call was produced. */
    ENABLED,
    /** Extract only when the provider produced no native tool call (the safe default). */
    AUTO;

    public static InBandToolCallsMode fromConfigurationString(final String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return switch (strip(value)) {
            case "enabled" -> ENABLED;
            case "disabled" -> DISABLED;
            default -> AUTO;
        };
    }

    private static String strip(final String value) {
        return value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
