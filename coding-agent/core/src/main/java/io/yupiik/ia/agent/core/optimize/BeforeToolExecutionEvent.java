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
package io.yupiik.ia.agent.core.optimize;

import io.yupiik.ia.agent.core.events.EventContext;
import java.util.Map;

/**
 * Fired just before a tool call executes. Carries the session coordinates (from {@link
 * io.yupiik.ia.agent.core.tools.ToolContext}) so observers - including the trailing hook triggers - can build an
 * {@link EventContext} and attribute the call to the right session/subagent.
 */
public record BeforeToolExecutionEvent(
        String tool, Map<String, Object> args, String sessionId, String subagentId, String subagentName) {

    public BeforeToolExecutionEvent(final String tool, final Map<String, Object> args) {
        this(tool, args, null, null, null);
    }
}
