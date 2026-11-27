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

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Streamable HTTP MCP, consuming SSE incrementally rather than buffering until EOF. */
public class HttpMcpClient extends RemoteMcpClient {
    public HttpMcpClient(
            final String name,
            final String url,
            final String apiKey,
            final List<String> headers,
            final long timeoutSeconds,
            final JsonMapper mapper,
            final ExtendedHttpClient client) {
        super(name, url, apiKey, headers, timeoutSeconds, mapper, client);
    }

    @Override
    protected URI postUri() {
        return uri;
    }

    @Override
    protected Object exchange(
            final Operation op, final Map<String, Object> message, final Long id, final boolean initialize)
            throws IOException, InterruptedException {
        final var response = send(
                op,
                builder(uri, op)
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.toBytes(message)))
                        .build());
        try {
            if (initialize) {
                op.check();
                final var session = response.headers().firstValue("mcp-session-id");
                if (session.isPresent()) {
                    final var value = session.orElseThrow();
                    if (value.isEmpty() || value.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
                        throw new IOException("MCP " + name + ": invalid session ID");
                    }
                    sessionId = value;
                }
            }
            if (id == null) {
                return null;
            }
            final var contentType = response.headers()
                    .firstValue("content-type")
                    .orElse("")
                    .split(";", 2)[0]
                    .strip()
                    .toLowerCase(Locale.ROOT);
            if ("text/event-stream".equals(contentType)) {
                final var reader = new McpSseReader(response.body(), MAX_RESPONSE_SIZE);
                McpSseReader.Event event;
                while ((event = reader.next()) != null) {
                    op.check();
                    if (!"message".equals(event.type())) {
                        continue;
                    }
                    final var incoming = decode(event.data());
                    if (!serverMessage(op, incoming)) {
                        // This POST stream belongs to this request, not to other concurrent POSTs.
                        if (sameId(incoming.get("id"), id)) {
                            return result(incoming, id);
                        }
                        throw new IOException("MCP " + name + ": unexpected JSON-RPC response ID");
                    }
                }
                throw new IOException("MCP " + name + ": SSE stream ended before response");
            }
            if (!"application/json".equals(contentType)) {
                throw new IOException("MCP " + name + ": expected JSON or SSE response");
            }
            final var body = response.body().readNBytes(MAX_RESPONSE_SIZE + 1);
            if (body.length > MAX_RESPONSE_SIZE) {
                throw new IOException("MCP " + name + ": JSON response exceeds maximum size");
            }
            return result(decode(new String(body, StandardCharsets.UTF_8)), id);
        } finally {
            op.release(response.body());
        }
    }

    @Override
    protected void terminate(final Operation op) throws IOException, InterruptedException {
        final var response = send(op, builder(uri, op).DELETE().build());
        op.release(response.body());
    }
}
