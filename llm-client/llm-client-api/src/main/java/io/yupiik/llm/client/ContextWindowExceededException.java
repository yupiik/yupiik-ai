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
package io.yupiik.llm.client;

import java.io.IOException;
import java.util.Locale;

/**
 * Raised when the upstream provider rejects a request because the prompt (and/or output) exceeds
 * the model's real context window. Distinct from other 4xx errors so callers can recover (compact
 * and retry) instead of failing.
 */
public class ContextWindowExceededException extends IOException {

    /** Creates the exception with a generic message. */
    public ContextWindowExceededException() {
        super("context window exceeded");
    }

    /**
     * Heuristic: does this provider error mean the context window was exceeded?
     *
     * @param statusCode the HTTP status code.
     * @param message the provider error message, may be {@code null}.
     * @return whether the error indicates a context window overflow.
     */
    public static boolean isCause(final int statusCode, final String message) {
        if (statusCode < 400 || message == null || message.isBlank()) {
            return false;
        }
        final var m = message.toLowerCase(Locale.ROOT);
        return m.contains("context length")
                || m.contains("context_length")
                || m.contains("maximum context")
                || m.contains("too many tokens")
                || m.contains("exceeds the model")
                || m.contains("reduce the length");
    }
}
