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

/** Per-tool tuning for the maven output processor (surefire/failsafe reports, stack traces). */
@JsonModel
public record MavenProcessorOutputConfiguration(
        @Property(
                defaultValue = "true",
                documentation = "Enable the maven processor. When false it behaves as identity (passthrough).")
        boolean enabled,

        @Property(
                defaultValue = "true",
                documentation = "Enrich test summaries from target/surefire-reports and target/failsafe-reports "
                        + "when the build ran quietly (-q) and the report files are fresh.")
        boolean surefireReports) {}
