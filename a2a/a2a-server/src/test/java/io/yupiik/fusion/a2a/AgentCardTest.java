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

import static io.yupiik.fusion.a2a.test.Awaits.await;
import static io.yupiik.fusion.testing.assertion.JsonAsserts.assertJsonEquals;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.client.A2AClient;
import io.yupiik.fusion.a2a.client.A2AClientException;
import io.yupiik.fusion.a2a.model.AgentInterface;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.a2a.protocol.A2AProtocol;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Agent discovery: the card published on the well-known path, its caching headers and the extended one.
 */
@FusionSupport
class AgentCardTest {
    @Test
    void card(@Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var card = await(new A2AClient(agent, http, jsons).agentCard());

        assertEquals("test-agent", card.name());
        assertEquals("An agent used by the tests.", card.description());
        assertEquals("1.0.0", card.version());
        // no skill is declared in this module - the tests drive an executor - so the card advertises none
        assertEquals(List.of(), card.skills());
        assertEquals(List.of("text/plain", "application/json"), card.defaultInputModes());
        assertEquals(
                List.of(
                        AgentInterface.of("https://agent.example.com/a2a", AgentInterface.JSONRPC, "1.0"),
                        AgentInterface.of("https://agent.example.com/a2a/v1", AgentInterface.HTTP_JSON, "1.0")),
                card.supportedInterfaces());
        assertEquals(true, card.capabilities().streaming());
        assertEquals(true, card.capabilities().pushNotifications());
        assertEquals(false, card.capabilities().extendedAgentCard());
    }

    @Test
    void cardIsSpecCompliantJson(@Fusion final URI agent, @Fusion final HttpClient http) throws Exception {
        final var response = http.send(
                HttpRequest.newBuilder(agent.resolve(A2AProtocol.AGENT_CARD_PATH))
                        .GET()
                        .build(),
                ofString());

        assertEquals(200, response.statusCode());
        assertJsonEquals("""
                        {
                          "capabilities": {
                            "extendedAgentCard": false,
                            "pushNotifications": true,
                            "streaming": true
                          },
                          "defaultInputModes": ["text/plain", "application/json"],
                          "defaultOutputModes": ["text/plain", "application/json"],
                          "description": "An agent used by the tests.",
                          "name": "test-agent",
                          "skills": [],
                          "supportedInterfaces": [
                            {
                              "protocolBinding": "JSONRPC",
                              "protocolVersion": "1.0",
                              "url": "https://agent.example.com/a2a"
                            },
                            {
                              "protocolBinding": "HTTP+JSON",
                              "protocolVersion": "1.0",
                              "url": "https://agent.example.com/a2a/v1"
                            }
                          ],
                          "version": "1.0.0"
                        }""", response.body());
    }

    @Test
    void caching(@Fusion final URI agent, @Fusion final HttpClient http) throws Exception {
        final var first = http.send(
                HttpRequest.newBuilder(agent.resolve(A2AProtocol.AGENT_CARD_PATH))
                        .GET()
                        .build(),
                ofString());
        final var etag = first.headers().firstValue("etag").orElseThrow();

        assertEquals("\"1.0.0\"", etag);
        assertTrue(
                first.headers().firstValue("cache-control").orElseThrow().contains("max-age="),
                first.headers().map().toString());

        final var revalidated = http.send(
                HttpRequest.newBuilder(agent.resolve(A2AProtocol.AGENT_CARD_PATH))
                        .GET()
                        .header("if-none-match", etag)
                        .build(),
                ofString());
        assertEquals(304, revalidated.statusCode());
    }

    @Test
    void clientCanBeBuiltFromTheCard(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var discovered = A2AClient.of(await(new A2AClient(agent, http, jsons).agentCard()), http, jsons);

        // the interface declares no tenant so nothing has to be echoed
        assertEquals(null, discovered.tenant());
    }

    @Test
    void extendedCardIsNotSupported(
            @Fusion final URI agent, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        final var client = new A2AClient(agent, http, jsons);

        // the capability is disabled, so it is UnsupportedOperationError and not a missing configuration
        final var error = Assertions.assertThrows(A2AClientException.class, () -> await(client.extendedAgentCard()));
        assertEquals(A2AErrors.UNSUPPORTED_OPERATION, error.code());
        assertEquals("This agent does not support extended agent cards", error.getMessage());
    }
}
