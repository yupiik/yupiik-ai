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
 * Per-provider speculative-compaction override. Every field is nullable: a value below zero (the
 * default) inherits the global {@link SpeculativeConfiguration} value of the {@link CompactionConfiguration}.
 * See {@link SpeculativeConfiguration} for the field meaning.
 */
@JsonModel
public record ProviderSpeculativeConfiguration(
        @Property(
                documentation =
                        "Per-provider speculative compaction policy; null inherits the global yca.compaction.speculative.policy.")
        String policy,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Per-provider speculative start percent; a negative value (the default) inherits the global yca.compaction.speculative.startPercent.")
        Integer startPercent) {

    public ProviderSpeculativeConfiguration {
        if (policy != null && !"off".equals(policy) && !"concurrent".equals(policy)) {
            throw new IllegalArgumentException("speculative policy must be off or concurrent");
        }
        if (startPercent != null && startPercent >= 0 && (startPercent < 1 || startPercent > 99)) {
            throw new IllegalArgumentException("speculative startPercent must be between 1 and 99");
        }
    }
}
