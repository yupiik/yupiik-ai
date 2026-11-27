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
import java.util.List;
import java.util.Objects;

/** Catalog and routing ceilings for subagent selection. Empty lists leave the corresponding choice unrestricted. */
@JsonModel
public record SpawnSubagentToolConfiguration(
        @Property(documentation = "Optional allowlist of named agent profiles available to subagents.")
        List<String> allowedAgents,

        @Property(documentation = "Optional allowlist of provider ids whose models are available to subagents.")
        List<String> allowedProviders,

        @Property(
                documentation =
                        "Optional allowlist of model references (bare ids or $provider/model) available to subagents.")
        List<String> allowedModels) {

    public boolean allowsAgent(final String agent) {
        return allowedAgents == null || allowedAgents.isEmpty() || containsNormalized(allowedAgents, agent);
    }

    public boolean allowsProvider(final String provider) {
        return allowedProviders == null || allowedProviders.isEmpty() || containsNormalized(allowedProviders, provider);
    }

    public boolean allowsModel(final String model) {
        return allowedModels == null || allowedModels.isEmpty() || containsNormalized(allowedModels, model);
    }

    private static boolean containsNormalized(final List<String> values, final String value) {
        if (value == null) {
            return false;
        }
        final var normalized = value.strip();
        return values.stream().filter(Objects::nonNull).map(String::strip).anyMatch(normalized::equals);
    }
}
