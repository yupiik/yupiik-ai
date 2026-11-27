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
 * Fires when a subagent is spawned (wraps {@code AgentEvent.SubagentStarted}). Observe-only; a callback may print
 * a {@code HookResponse} ack (cosmetic) but cannot block. Config key {@code yca.hooks.SubagentStarted}.
 */
public record SubagentStartedEvent(EventContext eventContext, String task, String subagentId, String subagentName)
        implements YcaEvent {

    @Override
    public Object payload() {
        return new Payload(eventContext, task, subagentId, subagentName);
    }

    @Override
    public HookResult result() {
        return new HookResult();
    }

    @Override
    public String description() {
        return """
                Fires when a subagent is spawned. Config key `yca.hooks.SubagentStarted`.

                Input payload (`SubagentStartedEvent.Payload`): `eventContext`, `task`, `subagentId`, `subagentName`.
                Observe-only; output `context` is cosmetic.""";
    }

    @JsonModel
    public record Payload(EventContext eventContext, String task, String subagentId, String subagentName) {}
}
