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
package io.yupiik.maven.knowledge.content;

import java.io.IOException;

/**
 * Minimal bounded webfetch callback supplied by the embedding runtime, keeping the knowledge module
 * free of any HTTP client dependency. Implementations must enforce the bounded-fetch rate limits.
 */
@FunctionalInterface
public interface WebFetch {

    /**
     * Fetch {@code url} and return up to {@code maxBytes} bytes of text content.
     *
     * @throws IOException when the fetch fails (unreachable, unsupported type, too large, ...).
     */
    String fetch(String url, long timeoutMs, int maxBytes) throws IOException;
}
