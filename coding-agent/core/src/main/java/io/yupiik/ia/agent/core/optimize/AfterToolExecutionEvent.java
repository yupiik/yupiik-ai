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

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Fired after a tool call executed. {@code success} is {@code true} for a success ({@code PostToolUse}) and
 * {@code false} for a failure ({@code PostToolUseFailure}). Carries the session coordinates (from {@link
 * io.yupiik.ia.agent.core.tools.ToolContext}) so observers can attribute the result to the right session/subagent.
 */
public record AfterToolExecutionEvent(
        String tool,
        boolean success,
        String output,
        String sessionId,
        String subagentId,
        String subagentName,
        Map<String, Object> args,
        Path workdir) {

    public AfterToolExecutionEvent {
        Objects.requireNonNull(workdir, "workdir");
        if (!workdir.isAbsolute() || !workdir.equals(workdir.normalize())) {
            throw new IllegalArgumentException("workdir must be absolute and normalized: " + workdir);
        }
    }
}
