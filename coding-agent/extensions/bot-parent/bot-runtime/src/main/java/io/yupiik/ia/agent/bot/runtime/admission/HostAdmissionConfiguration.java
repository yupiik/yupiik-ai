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
package io.yupiik.ia.agent.bot.runtime.admission;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

@JsonModel
@RootConfiguration("bot.admission")
public record HostAdmissionConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation = "Optional shared controller-only host admission root; blank disables admission.")
        String root,

        @Property(
                defaultValue = "1",
                documentation = "Shared host slot count; must match every participant and the persistent schema.")
        int slots,

        @Property(
                defaultValue = "\"\"",
                documentation = "Explicit trusted controller group for a pre-provisioned setgid root; "
                        + "blank requires private current-user ownership.")
        String trustedGroup) {
    public HostAdmissionConfiguration {
        if (root == null || trustedGroup == null || slots < 1 || slots > 1024) {
            throw new IllegalArgumentException("Invalid host admission configuration (slots must be 1..1024)");
        }
        if (root.isBlank() && !trustedGroup.isBlank()) {
            throw new IllegalArgumentException("A trusted admission group requires a root");
        }
    }
}
