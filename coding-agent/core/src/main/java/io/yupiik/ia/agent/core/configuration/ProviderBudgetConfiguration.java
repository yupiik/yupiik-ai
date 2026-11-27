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
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/** Trusted operator settings, never agent/request input. */
@JsonModel
@RootConfiguration("yca.provider.budget")
public record ProviderBudgetConfiguration(
        @Property(
                documentation =
                        "Absolute normalized shared provider request ledger directory. Absent or blank disables the budget only when id and slots are absent. Never expose to an agent.")
        String root,

        @Property(
                documentation =
                        "Shared provider/account identity, identical across participating trusted forwarding processes. Required when root is set.")
        String id,

        @Property(
                documentation =
                        "Positive canonical decimal slot count, identical across participants. Required when root is set. Kept as text so partial, blank and noncanonical settings fail closed.")
        String slots) {}
