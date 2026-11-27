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
package io.yupiik.ia.agent.core.tools;

import java.util.List;

/**
 * Outcome of a tool call. Initially a plain success/failure boolean, it now carries an explicit
 * tri-state so a tool can report "still working, not an error" (e.g. {@code check_subagents}
 * {@code collect} when the sub-agent has not finished yet). A PENDING result is <em>not</em> an
 * error: the turn keeps running, the model-read output keeps the "still running" text, and the UI
 * renders it muted rather than red. The {@code success()} accessor is kept for backward-compat and
 * returns {@code false} for PENDING, so existing call sites that only distinguish {success, error}
 * keep working and treat PENDING as "not done yet".
 *
 * <p>File mutations are ephemeral, authorized UI data captured at completed mutation boundaries.
 * They are not model-readable output and must not be appended to conversation history. An error
 * can carry mutations when an earlier operation in a multi-file patch completed successfully.</p>
 */
public record ToolResult(Status status, String output, List<FileMutation> fileMutations) {

    public ToolResult {
        fileMutations = List.copyOf(fileMutations);
    }

    public ToolResult(final Status status, final String output) {
        this(status, output, List.of());
    }

    public enum Status {
        OK,
        PENDING,
        ERROR
    }

    /**
     * Backward-compatible constructor mapping the legacy boolean semantics: {@code true -> OK},
     * {@code false -> ERROR}. Callers that used the old {@code (boolean, String)} shape keep their
     * meaning; status-preserving copies must use the {@link #ToolResult(Status, String)} form.
     */
    public ToolResult(final boolean success, final String output) {
        this(success ? Status.OK : Status.ERROR, output);
    }

    public boolean success() {
        return status == Status.OK;
    }

    /** True when the tool is still working and its result is not final yet (not an error). */
    public boolean pending() {
        return status == Status.PENDING;
    }

    public boolean error() {
        return status == Status.ERROR;
    }

    public static ToolResult ok(final String output) {
        return new ToolResult(Status.OK, output);
    }

    public static ToolResult pending(final String output) {
        return new ToolResult(Status.PENDING, output);
    }

    public static ToolResult error(final String output) {
        return new ToolResult(Status.ERROR, output);
    }
}
