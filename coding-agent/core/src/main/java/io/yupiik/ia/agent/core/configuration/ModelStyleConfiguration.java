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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * Display style of a model. When {@code color} and/or {@code bold} are unset the value falls back
 * to the provider tone / the implicit value-based scheme.
 */
@JsonModel
public record ModelStyleConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Optional model display color (hex, eg. #e0a458). When blank the provider base tone is used, then a tone derived from the provider id.")
        String color,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Optional bold override (tri-state): true, false or blank. When blank the implicit rule applies: the first two listed models of a provider are bold.")
        String bold) {

    /** Tri-state bold: null when blank/unset, else the parsed boolean. */
    public Boolean resolvedBold() {
        return bold == null || bold.isBlank() ? null : Boolean.parseBoolean(bold.strip());
    }
}
