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
package io.yupiik.ia.agent.core.agent;

import io.yupiik.ia.agent.core.tools.ToolResult;
import java.util.List;

/**
 * Outcome of a single tool call, decoupled from message/session mutation so calls can run
 * concurrently while results still land in the conversation in the model's call order.
 * {@code paths} carries session-resolved read paths or confirmed mutation paths, including
 * partial failures. {@code durationMs} is the individual tool execution time.
 */
public record ToolOutcome(
        String callId,
        String name,
        String output,
        boolean success,
        boolean pending,
        boolean persist,
        List<String> paths,
        long durationMs,
        boolean informative,
        List<String> coverage,
        boolean refused) {

    /** A failed attempt without a completed tool result. */
    public static ToolOutcome failed(
            final String callId, final String name, final String output, final boolean persist) {
        return new ToolOutcome(callId, name, output, false, false, persist, List.of(), 0L, true, List.of(), false);
    }

    /** A loop-guard refusal, not counted as new informative work. */
    public static ToolOutcome refused(final String callId, final String name, final String output) {
        return new ToolOutcome(callId, name, output, false, false, false, List.of(), 0L, false, List.of(), true);
    }

    /** A tool result, retaining mutation metadata even when the operation partially failed. */
    public static ToolOutcome executed(
            final String callId,
            final String name,
            final ToolResult result,
            final List<String> paths,
            final long durationMs,
            final List<String> coverage) {
        return new ToolOutcome(
                callId,
                name,
                result.output(),
                result.success(),
                result.pending(),
                true,
                paths,
                durationMs,
                !result.pending(),
                coverage,
                false);
    }
}
