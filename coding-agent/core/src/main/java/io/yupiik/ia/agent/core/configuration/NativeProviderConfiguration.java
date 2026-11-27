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
import io.yupiik.ia.agent.core.llm.ApiFlavor;

@JsonModel
@RootConfiguration("yca.native.provider")
public record NativeProviderConfiguration(
        @Property(
                documentation =
                        "Absolute launcher-prepared Unix socket for the native provider bridge. Unset disables the transport. This is a trusted launcher setting, not a provider destination or a network isolation policy.")
        String socket,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.llm.ApiFlavor.COMPLETIONS",
                documentation =
                        "Launcher-pinned provider API: COMPLETIONS or RESPONSES. Must match the controller bridge.")
        ApiFlavor api) {}
