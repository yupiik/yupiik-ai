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

/**
 * Fires when an {@code AgentEvent.Notice} is emitted (wraps the notice). Observe-only fan-out to an external sink
 * (log, webhook, push); a callback may print a {@code HookResponse} ack but cannot block. Config key
 * {@code yca.hooks.Notification}.
 */
public record NotificationEvent(EventContext eventContext, String level, String message, String finishReason)
        implements YcaEvent {

    @Override
    public Object payload() {
        return new Payload(eventContext, level, message, finishReason);
    }

    @Override
    public HookResult result() {
        return new HookResult();
    }

    @Override
    public String description() {
        return """
                Fires when an `AgentEvent.Notice` is emitted. Config key `yca.hooks.Notification`.

                Input payload (`NotificationEvent.Payload`): `eventContext`, `level` (INFO/WARN/ERROR), `message`,
                `finishReason`. Observe-only; output `context` is cosmetic.""";
    }

    @JsonModel
    public record Payload(EventContext eventContext, String level, String message, String finishReason) {}
}
