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
package io.yupiik.ia.agent.core.events;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import java.util.Map;

/**
 * Fires right before an action is routed to the human approval gate (in {@code PermissionService.approve}).
 * This is a <em>blockable</em> event: a callback may print {@code {"decision":"forbid","reason":"..."}} to
 * deny the permission request before the interactive modal is shown, or {@code {"decision":"allow"}} to leave the
 * decision to the gate. Config key {@code yca.hooks.PermissionRequested}.
 */
public record PermissionRequestedEvent(
        EventContext eventContext,
        String toolName,
        Map<String, Object> args,
        String detail,
        HookResult result,
        ApprovalGate.Origin origin)
        implements YcaEvent {

    @Override
    public Object payload() {
        return new Payload(eventContext, toolName, args, detail, origin);
    }

    @Override
    public HookResult result() {
        return result;
    }

    @Override
    public String description() {
        return """
                Fires before the interactive approval gate is shown. Config key `yca.hooks.PermissionRequested`.
                Blockable: `{"decision":"forbid","reason":"..."}` denies the request, `{"decision":"allow"}` leaves the
                decision to the gate.

                Input payload (`PermissionRequestedEvent.Payload`): `eventContext`, `toolName`, `args`, `detail`.""";
    }

    @JsonModel
    public record Payload(
            EventContext eventContext,
            String toolName,
            Map<String, Object> args,
            String detail,
            ApprovalGate.Origin origin) {}
}
