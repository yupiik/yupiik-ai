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

/** Unchecked failure that does not map to an {@link IOException} (e.g. missing dialect, interrupted locally). */
public class LlmClientException extends RuntimeException {

    /**
     * Creates the exception with a message.
     *
     * @param message the failure message.
     */
    public LlmClientException(final String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and a cause.
     *
     * @param message the failure message.
     * @param cause the underlying cause.
     */
    public LlmClientException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
