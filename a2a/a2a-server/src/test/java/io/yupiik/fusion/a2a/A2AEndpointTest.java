/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a;

import static io.yupiik.fusion.a2a.test.Bodies.contains;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.a2a.protocol.A2AProtocol;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * The {@code POST /a2a} transport itself: what it does with a payload which is not a valid JSON-RPC request.
 * <p>
 * {@code A2AJSONRPCProtocolTest} covers the operations. These are the bodies a client library never sends and which a
 * transport still has to answer something readable to - a malformed payload must not surface as an empty response or a
 * stack trace.
 */
@FusionSupport
class A2AEndpointTest {
    @Test
    void anInvalidJsonBodyIsAParseError(@Fusion final URI agent, @Fusion final HttpClient http) throws Exception {
        final var response = post(http, agent, "{ not json at all");

        assertEquals(
                200, response.statusCode(), "a JSON-RPC error travels with a 200, the envelope carries the failure");
        contains(response.body(), "\"code\":" + A2AErrors.PARSE_ERROR);
    }

    @Test
    void anEmptyBodyIsAParseError(@Fusion final URI agent, @Fusion final HttpClient http) throws Exception {
        contains(post(http, agent, "").body(), "\"code\":" + A2AErrors.PARSE_ERROR);
    }

    @Test
    void aNullBodyIsAParseError(@Fusion final URI agent, @Fusion final HttpClient http) throws Exception {
        // "null" parses, so it is not a syntax error - but it is not a request either
        contains(post(http, agent, "null").body(), "\"code\":" + A2AErrors.PARSE_ERROR, "Invalid JSON payload");
    }

    @Test
    void aPayloadWhichIsNeitherAnObjectNorAnArray(@Fusion final URI agent, @Fusion final HttpClient http)
            throws Exception {
        contains(post(http, agent, "42").body(), "\"code\":" + A2AErrors.INVALID_REQUEST, "Unknown request type");
    }

    @Test
    void aBatchIsAnswered(@Fusion final URI agent, @Fusion final HttpClient http) throws Exception {
        final var response = post(http, agent, """
                [
                  {"jsonrpc": "2.0", "id": 1, "method": "ListTasks", "params": {}},
                  {"jsonrpc": "2.0", "id": 2, "method": "GetTask", "params": {"id": "made-up"}}
                ]""");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("["), response::body);
        // the second one fails, which must not prevent the first from being answered
        contains(response.body(), "\"id\":1", "\"id\":2", "\"code\":-32001");
    }

    @Test
    void aBatchEntryWhichIsNotAnObject(@Fusion final URI agent, @Fusion final HttpClient http) throws Exception {
        final var response = post(http, agent, """
                [{"jsonrpc": "2.0", "id": 1, "method": "ListTasks", "params": {}}, 42]""");

        contains(response.body(), "Batch requests must be JSON objects");
    }

    @Test
    void aRequestWithoutTheEnvelopeIsRejected(@Fusion final URI agent, @Fusion final HttpClient http) throws Exception {
        contains(post(http, agent, "{\"method\": \"ListTasks\"}").body(), "Missing jsonrpc");
        contains(
                post(http, agent, "{\"jsonrpc\": \"1.0\", \"method\": \"ListTasks\"}")
                        .body(),
                "invalid jsonrpc version");
        contains(post(http, agent, "{\"jsonrpc\": \"2.0\"}").body(), "Missing method");
        contains(post(http, agent, "{\"jsonrpc\": \"2.0\", \"method\": \"\"}").body(), "Empty method");
    }

    @Test
    void anEmptyExtensionsHeaderDeclaresNothing(@Fusion final URI agent, @Fusion final HttpClient http)
            throws Exception {
        final var response = http.send(
                HttpRequest.newBuilder(agent.resolve(A2AProtocol.JSONRPC_PATH))
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"jsonrpc": "2.0", "id": 1, "method": "ListTasks", "params": {}}"""))
                        .header("content-type", "application/json")
                        .header(A2AProtocol.VERSION_HEADER, A2AProtocol.VERSION)
                        .header(A2AProtocol.EXTENSIONS_HEADER, "  ")
                        .build(),
                ofString());

        assertEquals(200, response.statusCode());
        contains(response.body(), "\"result\"");
    }

    @Test
    void theResponseIsJson(@Fusion final URI agent, @Fusion final HttpClient http) throws Exception {
        final var response = post(http, agent, """
                {"jsonrpc": "2.0", "id": 1, "method": "ListTasks", "params": {}}""");

        assertEquals(
                "application/json;charset=utf-8",
                response.headers().firstValue("content-type").orElseThrow());
    }

    private static HttpResponse<String> post(final HttpClient http, final URI agent, final String body)
            throws Exception {
        return http.send(
                HttpRequest.newBuilder(agent.resolve(A2AProtocol.JSONRPC_PATH))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("content-type", "application/json")
                        .header(A2AProtocol.VERSION_HEADER, A2AProtocol.VERSION)
                        .build(),
                ofString());
    }
}
