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
package io.yupiik.llm.client.spi;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * Result of a stream request: on 2xx a live {@link Flow.Publisher} of SSE byte buffers; otherwise a
 * collected (small) error body for classification.
 *
 * @param statusCode the HTTP status code.
 * @param headers the response headers.
 * @param live the live SSE body publisher for 2xx responses, or {@code null} otherwise.
 * @param errorBody the collected error body for non-2xx responses, or {@code null} otherwise.
 */
public record StreamAttempt(
        int statusCode, HttpHeaders headers, Flow.Publisher<List<ByteBuffer>> live, byte[] errorBody) {

    /**
     * Whether the request was a 2xx with a live stream.
     *
     * @return whether the request was a 2xx with a live stream.
     */
    public boolean ok() {
        return statusCode >= 200 && statusCode <= 299 && live != null;
    }
}
