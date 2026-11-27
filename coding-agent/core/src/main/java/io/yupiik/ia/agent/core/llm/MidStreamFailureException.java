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
package io.yupiik.ia.agent.core.llm;

import java.io.IOException;
import java.util.List;

/**
 * A streamed LLM reply whose transport broke after content was already delivered (eg. a chunked
 * HTTP connection cut mid-body). Carries the partial content received so far for diagnostics; the
 * agent loop uses the type itself to decide whether the request is worth retrying, as opposed to
 * failures raised before any fragment arrived which are handled by the resilience layer.
 */
public class MidStreamFailureException extends IOException {
    private static final long serialVersionUID = 1L;

    private final String partialText;
    private final transient List<ToolCall> partialToolCalls;

    public MidStreamFailureException(
            final String message,
            final Throwable cause,
            final String partialText,
            final List<ToolCall> partialToolCalls) {
        super(message, cause);
        this.partialText = partialText == null ? "" : partialText;
        this.partialToolCalls = partialToolCalls == null || partialToolCalls.isEmpty() ? null : partialToolCalls;
    }

    /** Text fragments accumulated before the stream broke; empty when none. */
    public String partialText() {
        return partialText;
    }

    /** Tool calls coalesced from the fragments received before the failure; null when none. */
    public List<ToolCall> partialToolCalls() {
        return partialToolCalls;
    }
}
