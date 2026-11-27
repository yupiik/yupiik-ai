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
 * Fires before context compaction starts (in {@code CompactionCoordinator.compact}, before summarization). This is a
 * <em>blockable</em> event: a callback may print {@code {"decision":"forbid","reason":"..."}} to veto the
 * compaction (a checkpoint or in-flight task can then abort it), or {@code {"decision":"allow"}} to let it proceed.
 * Config key {@code yca.hooks.CompactionStarting}.
 */
public record CompactionStartingEvent(EventContext eventContext, HookResult result) implements YcaEvent {

    @Override
    public Object payload() {
        return new Payload(eventContext);
    }

    @Override
    public HookResult result() {
        return result;
    }

    @Override
    public String description() {
        return """
                Fires before context compaction runs. Config key `yca.hooks.CompactionStarting`. Blockable:
                `{"decision":"forbid","reason":"..."}` vetoes the compaction, `{"decision":"allow"}` lets it proceed.

                Input payload (`CompactionStartingEvent.Payload`): `eventContext`.""";
    }

    @JsonModel
    public record Payload(EventContext eventContext) {}
}
