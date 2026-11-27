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
package io.yupiik.ia.agent.core.mcp;

import static io.yupiik.ia.agent.core.mcp.RemoteMcpTestServer.empty;
import static io.yupiik.ia.agent.core.mcp.RemoteMcpTestServer.event;
import static io.yupiik.ia.agent.core.mcp.RemoteMcpTestServer.json;
import static io.yupiik.ia.agent.core.mcp.RemoteMcpTestServer.request;
import static io.yupiik.ia.agent.core.mcp.RemoteMcpTestServer.result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.ai.mcp.client.HttpMcpClient;
import io.yupiik.ai.mcp.client.McpFailure;
import io.yupiik.ai.mcp.client.McpHttpException;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(TestJsonMapper.class)
class RemoteMcpValidationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void toolAndRpcErrorsHaveSanitizedUiOnlyDetail(final boolean rpc, final JsonMapper mapper) throws Exception {
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
                    final var message = request(mapper, exchange);
                    if ("initialize".equals(message.get("method"))) {
                        result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-06-18"));
                    } else if ("tools/call".equals(message.get("method"))) {
                        final var detail = "Quota exceeded: secret custom legacy \u001b[31mred\u001b[0m";
                        if (rpc) {
                            json(
                                    exchange,
                                    mapper.toString(Map.of(
                                            "jsonrpc",
                                            "2.0",
                                            "id",
                                            message.get("id"),
                                            "error",
                                            Map.of("code", -32001, "message", detail))));
                        } else {
                            result(
                                    mapper,
                                    exchange,
                                    message.get("id"),
                                    Map.of(
                                            "isError",
                                            true,
                                            "content",
                                            List.of(Map.of("type", "text", "text", detail))));
                        }
                    } else {
                        empty(exchange, 202);
                    }
                });
                final var client = new HttpMcpClient(
                        "test",
                        server.url("/mcp"),
                        "secret",
                        List.of("X-Test=custom", "Authorization=Bearer legacy"),
                        10,
                        mapper,
                        server.http)) {
            client.initialize();
            final var failure = assertThrows(McpFailure.class, () -> client.callTool("test", Map.of()));
            assertEquals(
                    (rpc ? "JSON-RPC error -32001: " : "") + "Quota exceeded: [redacted] [redacted] [redacted] red",
                    failure.detail());
            final var ui = McpDiagnostics.forUi("test", failure);
            assertEquals(failure.detail(), ui.detail());
            assertEquals("MCP tool test failed", ui.getMessage());
            assertFalse(failure.toString().contains("Quota exceeded"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void structuredContentFallbackAndTextPrecedence(final boolean sse, final JsonMapper mapper) throws Exception {
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            final var message = request(mapper, exchange);
            if ("initialize".equals(message.get("method"))) {
                result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-06-18"));
            } else if ("tools/call".equals(message.get("method"))) {
                final var text = "text".equals(((Map<?, ?>) message.get("params")).get("name"))
                        ? "[{\"type\":\"text\",\"text\":\"one\"},{\"type\":\"text\",\"text\":\"two\"}]"
                        : "[]";
                final var reply = "{\"jsonrpc\":\"2.0\",\"id\":" + message.get("id") + ",\"result\":{\"content\":"
                        + text + ",\"structuredContent\":{\"answer\":42}}}";
                if (sse) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    event(exchange, "", reply); // Empty event names dispatch as "message".
                    exchange.close();
                } else {
                    json(exchange, reply);
                }
            } else {
                empty(exchange, 202);
            }
        })) {
            final var client = server.http();
            client.initialize();
            assertEquals("{\"answer\":42}", client.callTool("structured", Map.of()));
            assertEquals("one\ntwo", client.callTool("text", Map.of()));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"tools\":[{\"name\":\"bad\",\"inputSchema\":[]}]}",
                "{\"tools\":[{\"name\":\"bad\",\"description\":42,\"inputSchema\":{\"type\":\"object\"}}]}",
                "{\"tools\":[{\"name\":\"bad\",\"inputSchema\":{\"type\":\"array\"}}]}",
                "{\"tools\":[{\"name\":\"bad\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":\"secret-value\"}}}]}",
                "{\"content\":null}",
                "{\"content\":[null]}",
                "{\"content\":[{\"type\":\"text\",\"text\":42}]}",
                "{\"content\":[],\"isError\":\"false\"}",
                "{\"content\":[],\"structuredContent\":[]}"
            })
    void malformedPayloadsAreRejectedBeforeTypedBinding(final String payload, final JsonMapper mapper)
            throws Exception {
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            final var message = request(mapper, exchange);
            if ("initialize".equals(message.get("method"))) {
                result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-06-18"));
            } else if (message.containsKey("id")) {
                json(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + message.get("id") + ",\"result\":" + payload + "}");
            } else {
                empty(exchange, 202);
            }
        })) {
            final var client = server.http();
            client.initialize();
            final var failure = payload.contains("\"tools\"")
                    ? assertThrows(IOException.class, client::listTools)
                    : assertThrows(IOException.class, () -> client.callTool("invalid", Map.of()));
            assertRedacted(failure);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32603,\"message\":\"secret-value\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"secret-value\"}]}}",
                "{\"secret-value\":invalid-json}"
            })
    void serverErrorsNeverExposeUntrustedValuesOrParserCauses(final String reply, final JsonMapper mapper)
            throws Exception {
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            final var message = request(mapper, exchange);
            if ("initialize".equals(message.get("method"))) {
                result(mapper, exchange, message.get("id"), Map.of("protocolVersion", "2025-06-18"));
            } else if (message.containsKey("id")) {
                json(exchange, reply);
            } else {
                empty(exchange, 202);
            }
        })) {
            final var client = server.http();
            client.initialize();
            assertRedacted(assertThrows(IOException.class, () -> client.callTool("test", Map.of())));
        }
    }

    @Test
    void httpErrorsPreserveStatusAndRetryHintWithoutEchoedCredentials(final JsonMapper mapper) throws Exception {
        try (final var server = new RemoteMcpTestServer(mapper, exchange -> {
            request(mapper, exchange);
            exchange.getResponseHeaders().set("Retry-After", "2");
            final var body = "secret-value".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        })) {
            final var failure = assertThrows(McpHttpException.class, server.http()::initialize);
            assertEquals(429, failure.statusCode());
            assertEquals(2000L, failure.retryAfterMillis().longValue());
            assertEquals("[redacted]", failure.body());
            assertRedacted(failure);
        }
    }

    private void assertRedacted(final Throwable failure) {
        try (final var trace = new StringWriter();
                final var printer = new PrintWriter(trace)) {
            failure.printStackTrace(printer);
            assertFalse(trace.toString().contains("secret-value"), trace.toString());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
