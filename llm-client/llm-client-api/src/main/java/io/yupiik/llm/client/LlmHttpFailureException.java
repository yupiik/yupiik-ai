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
 * Provider rejected the request with an HTTP error. Carries the status code, the body and the
 * {@code retry-after} header as typed fields so resilience layers never have to parse the message
 * string; the message keeps the historical {@code "LLM request failed (<code>): <body> retry-after=<v>"}
 * shape for logs and fallbacks.
 */
public class LlmHttpFailureException extends IOException {

    private final int status;
    private final String body;
    private final String retryAfter;

    /**
     * Creates the failure.
     *
     * @param status the HTTP status code.
     * @param body the response body (may be empty).
     * @param retryAfter the raw {@code retry-after} header value, or null when absent.
     */
    public LlmHttpFailureException(final int status, final String body, final String retryAfter) {
        super("LLM request failed ("
                + status
                + "): "
                + (body == null ? "" : body)
                + (retryAfter == null || retryAfter.isBlank() ? "" : " retry-after=" + retryAfter));
        this.status = status;
        this.body = body == null ? "" : body;
        this.retryAfter = retryAfter;
    }

    /**
     * @return the HTTP status code.
     */
    public int status() {
        return status;
    }

    /**
     * @return the response body, empty when none.
     */
    public String body() {
        return body;
    }

    /**
     * @return the raw {@code retry-after} header value, or null when absent.
     */
    public String retryAfterHeader() {
        return retryAfter;
    }

    /**
     * Whether the status code makes the failure worth retrying: 408, 429 and 5xx are retryable,
     * other 4xx rejections surface immediately.
     *
     * @return whether the failure is retryable.
     */
    public boolean retryableStatus() {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }
}
