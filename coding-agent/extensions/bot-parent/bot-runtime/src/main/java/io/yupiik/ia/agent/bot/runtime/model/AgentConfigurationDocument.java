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
package io.yupiik.ia.agent.bot.runtime.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import java.util.LinkedHashMap;
import java.util.Map;

/** Trusted YCA configuration can be wrapped in yca or supplied as plain provider settings. */
@JsonModel
public record AgentConfigurationDocument(
        @Property(documentation = "Wrapped YCA configuration.")
        Map<String, Object> yca,

        @JsonOthers @Property(documentation = "Plain YCA configuration settings when unwrapped.")
        Map<String, Object> settings) {
    public Map<String, Object> configuration() {
        return new LinkedHashMap<>(yca == null ? settings == null ? Map.of() : settings : yca);
    }

    /** The only root run setting allowed through the isolated configuration handoff. */
    public Map<String, Object> completionSettings() {
        if (settings != null
                && settings.get("run") instanceof Map<?, ?> run
                && run.get("result-correction-prompt") instanceof String prompt) {
            return Map.of("run", Map.of("result-correction-prompt", prompt));
        }
        return Map.of();
    }
}
