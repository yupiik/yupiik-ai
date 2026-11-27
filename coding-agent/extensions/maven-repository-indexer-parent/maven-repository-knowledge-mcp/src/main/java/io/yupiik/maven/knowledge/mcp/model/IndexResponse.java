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
package io.yupiik.maven.knowledge.mcp.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

/**
 * Result of running the indexer through MCP.
 */
@JsonModel
public record IndexResponse(
        @Property(documentation = "How many artifacts were (re)indexed.")
        int indexed,

        @Property(documentation = "How many artifacts were skipped (immutable-skip or no content).")
        int skipped,

        @Property(documentation = "Per-artifact outcomes.") List<Item> items) {

    @JsonModel
    public record Item(
            @Property(documentation = "The groupId:artifactId:version coordinate.")
            String gav,

            @Property(documentation = "True when indexed, false when skipped.")
            boolean indexed,

            @Property(documentation = "Human readable reason.")
            String reason) {}
}
