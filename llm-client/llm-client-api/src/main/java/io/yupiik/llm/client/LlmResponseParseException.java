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

/**
 * A provider response could not be decoded (malformed JSON, a stream received on a non-streaming
 * request, or an error envelope inside a 2xx body). Unlike a bare mapper failure this carries the
 * dialect, the endpoint, the HTTP status, the content-type and a bounded body excerpt so the failure
 * is self-describing in logs and status messages. It stays an {@link IOException} so the generic
 * translate paths (which recompute the message from the cause or pass it through) keep working.
 *
 * @param dialect the dialect name ({@code anthropic-messages}, {@code openai-completions}, ...).
 * @param endpoint the provider endpoint URL.
 * @param httpStatus the HTTP status of the response.
 * @param contentType the content-type header of the response, may be {@code null}.
 * @param bodyExcerpt a bounded single-line excerpt of the response body.
 */
public class LlmResponseParseException extends IOException {

    /**
     * Creates the exception.
     *
     * @param dialect the dialect name.
     * @param endpoint the provider endpoint URL.
     * @param httpStatus the HTTP status.
     * @param contentType the content-type header, may be {@code null}.
     * @param bodyExcerpt a bounded single-line body excerpt.
     * @param cause the underlying decoding failure.
     */
    public LlmResponseParseException(
            final String dialect,
            final String endpoint,
            final int httpStatus,
            final String contentType,
            final String bodyExcerpt,
            final Throwable cause) {
        super(
                dialect + ": unparseable response from " + endpoint + " (HTTP " + httpStatus
                        + (contentType == null ? "" : ", " + contentType) + "): " + cause.getMessage()
                        + (bodyExcerpt == null || bodyExcerpt.isBlank() ? "" : " | body: " + bodyExcerpt),
                cause);
    }
}
