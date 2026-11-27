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
package io.yupiik.ia.agent.core.session;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

/** Allowlisted session preset, stored separately from messages. Never contains permissions or MCP descriptors. */
@JsonModel
public record SessionMetadata(
        int version,
        String provider,
        String model,
        String variant,
        List<Sampling> sampling,
        String agent,
        String mode,
        Instructions systemPrompt) {
    public SessionMetadata {
        sampling = sampling == null ? List.of() : List.copyOf(sampling);
    }

    /** A null value means force-omit, not inherit; a missing field means inherit. */
    @JsonModel
    public record Sampling(String field, Double value) {}

    @JsonModel
    public record Instructions(String mode, List<String> text) {
        public Instructions {
            if ((!"append".equals(mode) && !"override".equals(mode))
                    || text == null
                    || text.isEmpty()
                    || text.size() > 64
                    || text.stream().anyMatch(value -> value == null || value.indexOf('\0') >= 0)
                    || text.stream().mapToLong(String::length).sum() > 8000) {
                throw new IllegalArgumentException("Invalid session instructions");
            }
            text = List.copyOf(text);
        }
    }
}
