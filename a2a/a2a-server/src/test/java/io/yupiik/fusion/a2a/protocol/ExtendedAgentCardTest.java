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
package io.yupiik.fusion.a2a.protocol;

import static io.yupiik.fusion.a2a.test.Awaits.await;
import static io.yupiik.fusion.a2a.test.Bodies.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.fusion.a2a.api.A2AAgentCard;
import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.SecurityRequirement;
import io.yupiik.fusion.a2a.model.SecurityScheme;
import io.yupiik.fusion.a2a.service.A2AService;
import io.yupiik.fusion.a2a.test.Agents;
import io.yupiik.fusion.a2a.test.Configurations;
import io.yupiik.fusion.a2a.test.FakeRequest;
import io.yupiik.fusion.a2a.test.Responses;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code GetExtendedAgentCard} answering a card, on both bindings.
 * <p>
 * {@code AgentCardTest} and {@code A2ARestRoutingTest} cover the refusals a default agent answers. Serving one needs
 * {@code fusion.a2a.extendedAgentCard=true} <em>and</em> a contributor, which is a second configuration the module
 * cannot start a container for - so the two thin adapters are driven directly.
 */
@FusionSupport
class ExtendedAgentCardTest {
    @Test
    void theJsonRpcBinding(@Fusion final JsonMapper jsons) {
        final var card = new A2AJSONRPCProtocol(service(), jsons).getExtendedAgentCard(null, null);

        assertEquals("test-agent", card.name());
        assertEquals(List.of("bearer"), List.copyOf(card.securitySchemes().keySet()));
        assertEquals(true, card.capabilities().extendedAgentCard());
    }

    @Test
    void theHttpJsonBinding(@Fusion final JsonMapper jsons) {
        final var binding = new A2ARestProtocol(service(), jsons, configuration());

        final var response = await(binding.extendedAgentCard(
                FakeRequest.of("GET", "/a2a/v1/extendedAgentCard").header("a2a-version", "1.0")));

        assertEquals(200, response.status());
        assertEquals(A2AProtocol.MEDIA_TYPE + ";charset=utf-8", Responses.header(response, "content-type"));
        contains(Responses.body(response), "\"name\":\"test-agent\"", "\"bearer\"", "\"extendedAgentCard\":true");
    }

    private static A2AService service() {
        return Agents.agent(configuration(), List.of(new A2AAgentCard() {
                    @Override
                    public AgentCard customize(final AgentCard card) {
                        return new AgentCard(
                                card.name(),
                                card.description(),
                                card.supportedInterfaces(),
                                card.provider(),
                                card.version(),
                                card.documentationUrl(),
                                card.capabilities(),
                                Map.of("bearer", SecurityScheme.bearer("JWT")),
                                List.of(SecurityRequirement.of("bearer")),
                                card.defaultInputModes(),
                                card.defaultOutputModes(),
                                card.skills(),
                                card.signatures(),
                                card.iconUrl());
                    }

                    @Override
                    public boolean extended() {
                        return true; // the schemes are only told to a caller which is already authenticated
                    }
                }))
                .service();
    }

    private static A2AConfiguration configuration() {
        return Configurations.configuration().extendedAgentCard(true).build();
    }
}
