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

import io.yupiik.ai.mcp.client.io.LimitedLineReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** SSE framing only; all JSON payloads are decoded by JsonMapper. */
final class McpSseReader {
    private final BufferedReader reader;
    private final int maximumSize;
    private boolean first = true;

    McpSseReader(final InputStream input, final int maximumSize) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("SSE maximum size must be positive");
        }
        this.maximumSize = maximumSize;
        reader = new LimitedLineReader(new InputStreamReader(input, StandardCharsets.UTF_8), maximumSize);
    }

    Event next() throws IOException {
        String type = "message";
        final var data = new StringBuilder();
        boolean hasData = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (first) {
                first = false;
                if (line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
            }
            if (line.isEmpty()) {
                if (hasData) {
                    return new Event(type.isEmpty() ? "message" : type, data.toString());
                }
                type = "message";
                continue;
            }
            if (line.charAt(0) == ':') {
                continue;
            }
            final var colon = line.indexOf(':');
            final var field = colon < 0 ? line : line.substring(0, colon);
            var value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if ("event".equals(field)) {
                type = value;
            } else if ("data".equals(field)) {
                if ((long) data.length() + value.length() + (hasData ? 1 : 0) > maximumSize) {
                    throw new IOException("MCP SSE event exceeds maximum size");
                }
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
            }
        }
        return null; // An incomplete event is not dispatched.
    }

    record Event(String type, String data) {}
}
