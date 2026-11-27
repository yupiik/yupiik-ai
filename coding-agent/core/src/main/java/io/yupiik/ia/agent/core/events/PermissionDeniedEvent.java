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
 * Fires when a permission request is denied (missing gate, user refusal, or a blocking
 * {@link PermissionRequestedEvent} hook). Observe-only; a callback may print a {@code HookResponse} ack
 * (cosmetic) but cannot change the denial. Config key {@code yca.hooks.PermissionDenied}.
 */
public record PermissionDeniedEvent(
        EventContext eventContext, String toolName, Map<String, Object> args, String reason, ApprovalGate.Origin origin)
        implements YcaEvent {

    @Override
    public Object payload() {
        return new Payload(eventContext, toolName, args, reason, origin);
    }

    @Override
    public HookResult result() {
        return new HookResult();
    }

    @Override
    public String description() {
        return """
                Fires when a permission request is denied. Config key `yca.hooks.PermissionDenied`.
                Observe-only; output `context` is cosmetic.

                Input payload (`PermissionDeniedEvent.Payload`): `eventContext`, `toolName`, `args`, `reason`.""";
    }

    @JsonModel
    public record Payload(
            EventContext eventContext,
            String toolName,
            Map<String, Object> args,
            String reason,
            ApprovalGate.Origin origin) {}
}
