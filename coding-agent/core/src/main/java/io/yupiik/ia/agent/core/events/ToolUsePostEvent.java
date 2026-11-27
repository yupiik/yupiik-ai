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
import java.util.Map;

/**
 * Fires after a tool call resolved, merging {@code PostToolUse} and {@code PostToolUseFailure}: {@code success} is
 * {@code true} on success and {@code false} on failure (with {@code error} carrying the failure detail). Observe-only:
 * a callback may print a {@code HookResponse} ack but it cannot block. Config key {@code yca.hooks.ToolUsePostEvent}.
 *
 * <p>Wraps {@code io.yupiik.ia.agent.core.optimize.AfterToolExecutionEvent} (a {@code ToolExecutionService} bus
 * record that already carries the session coordinates).</p>
 */
public record ToolUsePostEvent(
        EventContext eventContext,
        String toolName,
        Map<String, Object> toolInput,
        String toolUseId,
        boolean success,
        String output,
        String error)
        implements YcaEvent {

    @Override
    public Object payload() {
        return new Payload(eventContext, toolName, toolInput, toolUseId, success, output, error);
    }

    @Override
    public HookResult result() {
        return new HookResult(); // observe-only: no hook can veto a completed tool call
    }

    @Override
    public String description() {
        return """
                Fires after a tool call resolved (success or failure). Config key `yca.hooks.ToolUsePostEvent`.

                Input payload (`ToolUsePostEvent.Payload`, on stdin as camelCase JSON):
                - `eventContext`: shared session/agent/cwd/mode/model context
                - `toolName`: tool id (eg. `bash`, `read`)
                - `toolInput`: the raw tool arguments
                - `toolUseId`: call id
                - `success`: whether the tool call succeeded
                - `output`: the tool text result
                - `error`: failure detail, `null` on success

                Output (`HookResponse`, on stdout): only `context` (free text) is meaningful — observe-only, output
                is cosmetic. Empty/valid JSON are ok; invalid JSON, non-zero exit or timeout raise an error.""";
    }

    /** The serializable input payload of {@link ToolUsePostEvent}. */
    @JsonModel
    public record Payload(
            EventContext eventContext,
            String toolName,
            Map<String, Object> toolInput,
            String toolUseId,
            boolean success,
            String output,
            String error) {}
}
