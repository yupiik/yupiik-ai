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
 * Per-level colors of the captured log regions ({@code yca.tui.log.<level>.color}).
 * Every JUL level (SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST) has its
 * own color so each severity keeps a distinct hue next to the user (primary) and
 * assistant (accent) turns.
 */
@JsonModel
public record TuiLogConfiguration(
        @Property(documentation = "SEVERE log color, auto follows the error accent.")
        TuiLogLevelConfiguration severe,

        @Property(documentation = "WARNING log color, auto follows the warning accent.")
        TuiLogLevelConfiguration warning,

        @Property(documentation = "INFO log color, auto follows the info accent.")
        TuiLogLevelConfiguration info,

        @Property(documentation = "CONFIG log color, auto follows the info accent.")
        TuiLogLevelConfiguration config,

        @Property(documentation = "FINE log color, auto follows a dimmed info accent.")
        TuiLogLevelConfiguration fine,

        @Property(documentation = "FINER log color, auto follows a dimmer info accent.")
        TuiLogLevelConfiguration finer,

        @Property(documentation = "FINEST log color, auto follows the dimmest info accent.")
        TuiLogLevelConfiguration finest) {}
