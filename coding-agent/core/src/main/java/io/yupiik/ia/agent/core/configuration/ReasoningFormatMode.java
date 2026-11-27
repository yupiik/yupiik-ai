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
 * The reasoning wire format configuration selector for a model. Each non-AUTO value maps to a
 * {@link ReasoningFormat} preset; AUTO keeps the default (GLM/Qwen whole-line {@code  think}
 * handling) and is the safe fallback for models whose format is unknown.
 */
@JsonModel
public enum ReasoningFormatMode {
    /** The default whole-line {@code  think} handling (GLM/Qwen). */
    AUTO,
    /** GLM/Qwen whole-line reasoning tags. */
    THINK_TAGS,
    /** DeepSeek-R1: reasons with no opening tag until {@code  response}. */
    DEEPSEEK_R1,
    /** Qwen/Nemotron: {@code  think ... response} with an implicit close when the tool channel opens. */
    QWEN;

    public static ReasoningFormatMode fromConfigurationString(final String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return switch (strip(value)) {
            case "think_tags", "thinktag", "think" -> THINK_TAGS;
            case "deepseek_r1", "deepseek" -> DEEPSEEK_R1;
            case "qwen" -> QWEN;
            default -> AUTO;
        };
    }

    /** The descriptor this mode selects; AUTO resolves to the default {@code thinkTags} preset. */
    public ReasoningFormat resolve() {
        return switch (this) {
            case DEEPSEEK_R1 -> ReasoningFormat.deepSeekR1();
            case QWEN -> ReasoningFormat.qwen();
            default -> ReasoningFormat.thinkTags();
        };
    }

    private static String strip(final String value) {
        return value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
