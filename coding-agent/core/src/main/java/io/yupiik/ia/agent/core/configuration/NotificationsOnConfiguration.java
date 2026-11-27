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

/** Independent notification switches, all gated by {@code yca.notifications.enabled}. */
@JsonModel
public record NotificationsOnConfiguration(
        @Property(documentation = "Event notifications: main-turn completion and all permission prompts.")
        NotificationsEventConfiguration event,

        @Property(
                documentation =
                        "Feedback notifications owned by ask_user or /btw; these are independent switches, not event filters.")
        NotificationsToolsConfiguration tools) {
    public NotificationsOnConfiguration {
        event = event == null ? new NotificationsEventConfiguration(null, null) : event;
        tools = tools == null ? new NotificationsToolsConfiguration(null, null) : tools;
    }
}
