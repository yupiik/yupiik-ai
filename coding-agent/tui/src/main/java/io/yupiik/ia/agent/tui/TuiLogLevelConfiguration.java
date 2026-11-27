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
package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * Color configuration for one captured log level ({@code yca.tui.log.<level>.color}).
 * A blank/auto value follows the closest palette accent so a theme change or the
 * light theme remap keeps the log regions consistent.
 */
@JsonModel
public record TuiLogLevelConfiguration(
        @Property(
                defaultValue = "\"auto\"",
                documentation =
                        "Log color (hex) for this level, e.g. red for severe or yellow for warning; "
                                + "auto follows the closest palette accent (error for severe, warning for warning, info otherwise).")
        String color) {}
