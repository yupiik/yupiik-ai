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
public record NotificationsToolsConfiguration(
        @Property(
                defaultValue = "true",
                documentation =
                        "Notify when main-session structured feedback or a confirmation awaits input. Does not control /btw feedback.")
        Boolean ask_user,

        @Property(
                defaultValue = "true",
                documentation =
                        "Notify when /btw requests feedback. Independent of ask_user; does not control approvals or notify when an answer is ready.")
        Boolean btw) {
    public NotificationsToolsConfiguration {
        ask_user = ask_user == null || ask_user;
        btw = btw == null || btw;
    }
}
