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
package io.yupiik.ai.mcp.client;

import java.io.IOException;

/**
 * HTTP-level failure of an MCP request: keeps the status code, body and Retry-After hint so callers
 * can react to rate limiting (429) or authentication failures (401) without string matching.
 *
 * <p>Extends {@link IOException} so existing MCP callers (McpManager and friends) are unaffected.</p>
 */
public class McpHttpException extends IOException {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final int statusCode;
    private final String body;
    private final Long retryAfterMillis;

    public McpHttpException(final String name, final int statusCode, final String body, final Long retryAfterMillis) {
        super("MCP " + name + " HTTP " + statusCode + (body == null || body.isBlank() ? "" : ": " + body));
        this.name = name;
        this.statusCode = statusCode;
        this.body = body;
        this.retryAfterMillis = retryAfterMillis;
    }

    /** MCP server name used for the failing request. */
    public String name() {
        return name;
    }

    public int statusCode() {
        return statusCode;
    }

    public String body() {
        return body;
    }

    /** Cooldown hint parsed from a Retry-After header (delay-seconds or HTTP-date), null when absent or unparsable. */
    public Long retryAfterMillis() {
        return retryAfterMillis;
    }
}
