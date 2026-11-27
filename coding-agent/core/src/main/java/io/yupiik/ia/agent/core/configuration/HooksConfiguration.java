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
import java.util.List;

/** Root {@code yca.hooks} settings: the external-program hook callbacks bound to events. */
@JsonModel
public record HooksConfiguration(
        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Hook callbacks bound to events. Each registration names an event id (its simple class name,"
                                + " eg. `ToolUsePreEvent`) and an external program that receives the event JSON on"
                                + " stdin and may print a decision JSON on stdout. Events documented on the hooks"
                                + " page; every `yca.hooks` entry is optional and runs concurrently (virtual threads)"
                                + " with the rest of the pipeline when its event fires.")
        List<HookRegistration> hooks) {

    /** All callbacks registered for the given event id, in declaration order. */
    public List<HookRegistration> forEvent(final String eventId) {
        if (hooks == null || eventId == null) {
            return List.of();
        }
        return hooks.stream()
                .filter(h -> h != null && eventId.equals(h.event()))
                .toList();
    }
}
