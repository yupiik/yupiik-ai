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
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import java.util.Map;

/**
 * Fires before every tool call (including bash, which is a regular tool) at the permission-gate location — before
 * the tool runs and before any output message. It merges Claude's {@code PreToolUse} and the yca {@code PreBash}:
 * a {@code bash} call is just a {@code ToolUsePreEvent} with {@code toolName == "bash"}.
 *
 * <p>The wire input is {@link Payload} (see {@link #payload()}); a callback may print a {@code HookResponse} on
 * stdout to veto the call ({@code decision:"forbid"}) or let it proceed ({@code decision:"allow"}). The
 * {@code HookResult} is mutated by the built-in permission observer and the configured hook callbacks; the producer
 * reads it once emission returns and denies the tool when {@code forbidden}.</p>
 *
 * <p>The {@link PermissionService}, {@link ToolCategory} and {@code readonly} are carried on the <em>wrapper</em>
 * (never on the wire payload) so the built-in permission observer can run the existing {@link PermissionService}
 * rules against the producing session's state.</p>
 */
public record ToolUsePreEvent(
        EventContext eventContext,
        String toolName,
        Map<String, Object> toolInput,
        String toolUseId,
        HookResult result,
        PermissionService permissions,
        ToolCategory category,
        boolean readonly)
        implements YcaEvent {

    @Override
    public Object payload() {
        return new Payload(eventContext, toolName, toolInput, toolUseId);
    }

    @Override
    public HookResult result() {
        return result;
    }

    @Override
    public String description() {
        return """
                Fires before any tool call (a `bash` call is `toolName: "bash"`), at the permission gate, before \
                the tool runs and before any output message. Config key: `yca.hooks.ToolUsePreEvent`.

                Input payload (`ToolUsePreEvent.Payload`, on stdin as camelCase JSON):
                - `eventContext`: shared session/agent/cwd/mode/model context
                - `toolName`: tool id (eg. `bash`, `read`)
                - `toolInput`: raw tool arguments as the agent provided them
                - `toolUseId`: call id

                Output (`HookResponse`, on stdout): `{"decision":"allow"}` lets the call proceed, \
                `{"decision":"forbid","reason":"..."}` blocks it (reason surfaces as the denial). Empty/valid-JSON \
                only are ok; invalid JSON, non-zero exit or timeout raise an error and do not block. The built-in \
                `yca.permissions.*` rules run first (lower priority), then the configured callbacks.""";
    }

    /**
     * The serializable input payload of {@link ToolUsePreEvent}: what a callback receives on stdin for a tool call
     * about to run. {@code toolName} is the tool id ("bash", "read", ...) — bash is a regular tool, there is
     * no dedicated bash event. {@code toolInput} is the raw parsed tool arguments as the agent provided them.
     */
    @JsonModel
    public record Payload(
            EventContext eventContext, String toolName, Map<String, Object> toolInput, String toolUseId) {}
}
