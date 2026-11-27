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

@JsonModel
public record SessionCleanConfiguration(
        @Property(
                defaultValue = "15",
                documentation =
                        "Remove sessions inactive strictly longer than this many days; nonpositive values disable the age criterion. Automatic housekeeping uses this age alone; explicit maintenance combines enabled age and size criteria with AND.")
        int retentionDays,

        @Property(
                defaultValue = "null",
                documentation =
                        "Optional session size threshold (for example 1M). Total owned regular-file bytes must strictly exceed it, including artifacts and legacy sidecars and counting gzip files at their actual compressed size, without following symbolic links. No enabled criteria means no cleanup; the resolved session and sessions written within the last 24 hours are protected.")
        String biggerThan) {}
