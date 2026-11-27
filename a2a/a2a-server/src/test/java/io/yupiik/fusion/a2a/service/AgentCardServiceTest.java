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
package io.yupiik.fusion.a2a.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.fusion.a2a.api.A2AAgentCard;
import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.model.AgentCapabilities;
import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.AgentExtension;
import io.yupiik.fusion.a2a.model.AgentInterface;
import io.yupiik.fusion.a2a.model.SecurityRequirement;
import io.yupiik.fusion.a2a.model.SecurityScheme;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.a2a.signature.CardSigner;
import io.yupiik.fusion.a2a.test.Configurations;
import io.yupiik.fusion.a2a.test.FakeRequest;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * How the published agent card is assembled: configuration for the plain attributes, {@link A2AAgentCard} beans for
 * everything structured, and the interface URLs resolved per request when {@code fusion.a2a.publicUrl} is not set.
 * <p>
 * {@code AgentCardTest} asserts the card of a running agent. What only this level reaches is the configurations a module
 * cannot have two of: no public URL, a tenant, the HTTP+JSON binding turned off, an extended card.
 */
class AgentCardServiceTest {
    @Test
    void bothBindingsAreAdvertised() {
        final var card = cards(Configurations.defaults()).card(null);

        assertEquals(
                List.of(
                        AgentInterface.of("https://agent.example.com/a2a", AgentInterface.JSONRPC, "1.0"),
                        AgentInterface.of("https://agent.example.com/a2a/v1", AgentInterface.HTTP_JSON, "1.0")),
                card.supportedInterfaces());
        assertEquals("test-agent", card.name());
        assertEquals(List.of("text/plain", "application/json"), card.defaultInputModes());
        assertEquals(true, card.capabilities().streaming());
        assertEquals(true, card.capabilities().pushNotifications());
        assertEquals(false, card.capabilities().extendedAgentCard());
        assertNull(card.provider(), "no provider is published when no organization is configured");
        assertNull(card.documentationUrl());
        assertNull(card.iconUrl());
        assertNull(card.signatures());
    }

    @Test
    void theHttpJsonBindingCanBeTurnedOff() {
        final var card =
                cards(Configurations.configuration().restBinding(false).build()).card(null);

        assertEquals(
                List.of(AgentInterface.of("https://agent.example.com/a2a", AgentInterface.JSONRPC, "1.0")),
                card.supportedInterfaces());
    }

    @Test
    void aTrailingSlashIsNotPartOfTheUrl() {
        final var card = cards(Configurations.configuration()
                        .publicUrl("https://agent.example.com/")
                        .build())
                .card(null);

        assertEquals(
                "https://agent.example.com/a2a",
                card.supportedInterfaces().get(0).url());
    }

    @Test
    void withoutAPublicUrlTheInterfacesComeFromTheRequest() {
        final var cards = cards(Configurations.configuration().publicUrl(null).build());

        final var card = cards.card(FakeRequest.of("GET", "/.well-known/agent-card.json")
                .scheme("https")
                .header("Host", "agent.example.org:8443"));

        assertEquals(
                "https://agent.example.org:8443/a2a",
                card.supportedInterfaces().get(0).url());
        assertEquals(
                "https://agent.example.org:8443/a2a/v1",
                card.supportedInterfaces().get(1).url());
    }

    @Test
    void withoutAPublicUrlNorARequestThereIsNothingToAdvertise() {
        // a blank public url is no public url, so the interfaces are resolved per request - and there is no request
        // here
        final var cards = cards(Configurations.configuration().publicUrl("  ").build());

        assertEquals(List.of(), cards.card(null).supportedInterfaces());
        assertEquals("", cards.baseUrl(null));
        // a request without a Host header cannot say more either
        assertEquals("", cards.baseUrl(FakeRequest.of("GET", "/.well-known/agent-card.json")));
        assertEquals(List.of(), cards.card(FakeRequest.of("GET", "/x")).supportedInterfaces());
    }

    @Test
    void theConfiguredUrlWinsOverTheRequest() {
        final var cards = cards(Configurations.defaults());

        assertEquals(
                "https://agent.example.com",
                cards.baseUrl(FakeRequest.of("GET", "/x").scheme("http").header("host", "localhost:8080")));
    }

    @Test
    void aTenantIsAdvertisedOnEveryInterface() {
        final var cards = cards(Configurations.configuration().tenant("blue").build());

        assertEquals("blue", cards.tenant());
        assertEquals(
                List.of("blue", "blue"),
                cards.card(null).supportedInterfaces().stream()
                        .map(AgentInterface::tenant)
                        .toList());
    }

    @Test
    void aBlankTenantIsNoTenant() {
        // there is nothing to route for a single agent server, and an empty string is not an identifier
        assertNull(cards(Configurations.configuration().tenant("  ").build()).tenant());
        assertNull(cards(Configurations.defaults()).tenant());
        assertNull(cards(Configurations.defaults())
                .card(null)
                .supportedInterfaces()
                .get(0)
                .tenant());
    }

    @Test
    void theProvider() {
        final var withUrl = cards(Configurations.configuration()
                        .provider("Yupiik", "https://www.yupiik.com")
                        .build())
                .card(null)
                .provider();
        assertEquals("Yupiik", withUrl.organization());
        assertEquals("https://www.yupiik.com", withUrl.url());

        final var withoutUrl = cards(
                        Configurations.configuration().provider("Yupiik", "  ").build())
                .card(null)
                .provider();
        assertNull(withoutUrl.url());

        // the organization is what identifies a provider, so there is nothing to publish without it
        assertNull(cards(Configurations.configuration()
                        .provider("  ", "https://www.yupiik.com")
                        .build())
                .card(null)
                .provider());
    }

    @Test
    void mediaTypesAreCommaSeparated() {
        final var cards = cards(Configurations.configuration()
                .inputModes(" text/plain , , application/json ,")
                .outputModes("text/plain")
                .build());

        // the values are trimmed and the empty ones dropped, so a trailing comma is not a mode
        assertEquals(List.of("text/plain", "application/json"), cards.card(null).defaultInputModes());
        assertEquals(List.of("text/plain"), cards.outputModes());
    }

    @Test
    void anAgentCanDeclareNoMediaTypeAtAll() {
        final var cards = cards(Configurations.configuration()
                .inputModes(null)
                .outputModes("  ")
                .build());

        assertEquals(List.of(), cards.card(null).defaultInputModes());
        // and it then restricts nothing, see A2AServiceTest
        assertEquals(List.of(), cards.outputModes());
    }

    @Test
    void documentationAndIcon() {
        final var card = cards(Configurations.configuration()
                        .documentationUrl("https://docs.example.com")
                        .iconUrl("https://example.com/icon.png")
                        .build())
                .card(null);

        assertEquals("https://docs.example.com", card.documentationUrl());
        assertEquals("https://example.com/icon.png", card.iconUrl());
    }

    @Test
    void customizersAreChained() {
        final var cards = cards(
                Configurations.defaults(),
                card -> withSchemes(card, Map.of("bearer", SecurityScheme.bearer("JWT"))),
                card -> card.withSkills(List.of()),
                card -> null); // an implementation with nothing to say keeps what the previous produced

        final var card = cards.card(null);
        assertEquals(List.of("bearer"), List.copyOf(card.securitySchemes().keySet()));
        assertEquals(List.of(SecurityRequirement.of("bearer")), card.securityRequirements());
    }

    @Test
    void requiredExtensionsAreCollected() {
        final var cards = cards(
                Configurations.defaults(),
                card -> withExtensions(
                        card,
                        List.of(
                                AgentExtension.of("https://example.com/required", "Needed.", true),
                                AgentExtension.of("https://example.com/optional", "Nice to have.", false))));

        // only the required ones are what a client must declare to be served
        assertEquals(List.of("https://example.com/required"), cards.requiredExtensions());
    }

    @Test
    void withoutAnyExtension() {
        assertEquals(List.of(), cards(Configurations.defaults()).requiredExtensions());
        // a customizer is free to drop the capabilities altogether, and that must not break the lookup
        assertEquals(
                List.of(),
                cards(Configurations.defaults(), card -> withCapabilities(card, null))
                        .requiredExtensions());
        assertEquals(
                List.of(),
                cards(
                                Configurations.defaults(),
                                card -> withCapabilities(card, new AgentCapabilities(true, true, null, false)))
                        .requiredExtensions());
    }

    @Test
    void aCustomizerDroppingTheOutputModesRestrictsNothing() {
        final var cards = cards(Configurations.defaults(), AgentCardServiceTest::withoutOutputModes);

        assertEquals(List.of(), cards.outputModes());
    }

    @Test
    void theExtendedCardNeedsTheCapability() {
        final var cards = cards(Configurations.defaults(), extended(card -> card.withSkills(List.of())));

        // a contributor is not enough, the capability has to be turned on too
        final var error = assertThrows(JsonRpcException.class, () -> cards.extendedCard(null));
        assertEquals(A2AErrors.UNSUPPORTED_OPERATION, error.code());
        assertEquals("This agent does not support extended agent cards", error.getMessage());
    }

    @Test
    void theExtendedCardNeedsAContributor() {
        final var cards =
                cards(Configurations.configuration().extendedAgentCard(true).build());

        // the capability is on but nothing contributes, which is a configuration mistake and not an unsupported
        // operation
        final var error = assertThrows(JsonRpcException.class, () -> cards.extendedCard(null));
        assertEquals(A2AErrors.EXTENDED_AGENT_CARD_NOT_CONFIGURED, error.code());
    }

    @Test
    void theExtendedCardStartsFromThePublicOne() {
        final var cards = cards(
                Configurations.configuration().extendedAgentCard(true).build(),
                card -> withSchemes(card, Map.of("bearer", SecurityScheme.bearer("JWT"))),
                extended(card -> withExtensions(
                        card, List.of(AgentExtension.of("https://example.com/members", "Members only.", false)))));

        final var extended = cards.extendedCard(null);
        assertEquals(List.of("bearer"), List.copyOf(extended.securitySchemes().keySet()), "the public content is kept");
        assertEquals(1, extended.capabilities().extensions().size());
        assertEquals(true, extended.capabilities().extendedAgentCard());
        // and the public card is untouched by the extended contributor
        assertNull(cards.card(null).capabilities().extensions());
    }

    @Test
    void theExtendedCardResolvesItsInterfacesToo() {
        final var cards = cards(
                Configurations.configuration()
                        .publicUrl(null)
                        .extendedAgentCard(true)
                        .build(),
                extended(card -> card.withSkills(List.of())));

        final var extended = cards.extendedCard(
                FakeRequest.of("POST", "/a2a").scheme("https").header("host", "agent.example.org"));

        assertEquals(
                "https://agent.example.org/a2a",
                extended.supportedInterfaces().get(0).url());
    }

    @Test
    void anAgentWithoutAnyCustomizer() {
        // the container may hand over no list at all when nothing implements the interface
        final var cards = new AgentCardService(
                Configurations.defaults(),
                new SkillService(null, null, null) {},
                null,
                new CardSigner(null, null, Configurations.defaults()));

        assertEquals("test-agent", cards.card(null).name());
        assertEquals(2, cards.card(null).supportedInterfaces().size());
    }

    @Test
    void aCustomizerDroppingTheInterfacesGetsThemBack() {
        final var cards = cards(Configurations.defaults(), card -> card.withInterfaces(null));

        // an absent list reads like an empty one: the interfaces are resolved again rather than served as null
        final var card = cards.card(FakeRequest.of("GET", "/x").scheme("http").header("host", "localhost:8080"));
        assertEquals(
                List.of(
                        AgentInterface.of("https://agent.example.com/a2a", AgentInterface.JSONRPC, "1.0"),
                        AgentInterface.of("https://agent.example.com/a2a/v1", AgentInterface.HTTP_JSON, "1.0")),
                card.supportedInterfaces(),
                "the configured url still wins over the request");
    }

    @Test
    void theProtectedConstructorIsUsableBySubclasses() {
        // the container subclasses the bean to proxy it, so the no-argument constructor must not blow up
        final var cards = new AgentCardService(null, null, null, null) {};

        assertEquals(List.of(), cards.outputModes());
        assertEquals(List.of(), cards.requiredExtensions());
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    private static AgentCardService cards(final A2AConfiguration configuration, final A2AAgentCard... customizers) {
        // the protected constructor is the no-skill one, and the skills are not what this asserts on
        return new AgentCardService(
                configuration,
                new SkillService(null, null, null) {},
                List.of(customizers),
                new CardSigner(null, null, configuration));
    }

    /**
     * @param delegate what to contribute.
     * @return the same contribution, but on the authenticated card only.
     */
    private static A2AAgentCard extended(final A2AAgentCard delegate) {
        return new A2AAgentCard() {
            @Override
            public AgentCard customize(final AgentCard card) {
                return delegate.customize(card);
            }

            @Override
            public boolean extended() {
                return true;
            }
        };
    }

    private static AgentCard withSchemes(final AgentCard card, final Map<String, SecurityScheme> schemes) {
        return new AgentCard(
                card.name(),
                card.description(),
                card.supportedInterfaces(),
                card.provider(),
                card.version(),
                card.documentationUrl(),
                card.capabilities(),
                schemes,
                List.copyOf(
                        schemes.keySet().stream().map(SecurityRequirement::of).toList()),
                card.defaultInputModes(),
                card.defaultOutputModes(),
                card.skills(),
                card.signatures(),
                card.iconUrl());
    }

    private static AgentCard withExtensions(final AgentCard card, final List<AgentExtension> extensions) {
        final var capabilities = card.capabilities();
        return withCapabilities(
                card,
                new AgentCapabilities(
                        capabilities.streaming(),
                        capabilities.pushNotifications(),
                        extensions,
                        capabilities.extendedAgentCard()));
    }

    private static AgentCard withCapabilities(final AgentCard card, final AgentCapabilities capabilities) {
        return new AgentCard(
                card.name(),
                card.description(),
                card.supportedInterfaces(),
                card.provider(),
                card.version(),
                card.documentationUrl(),
                capabilities,
                card.securitySchemes(),
                card.securityRequirements(),
                card.defaultInputModes(),
                card.defaultOutputModes(),
                card.skills(),
                card.signatures(),
                card.iconUrl());
    }

    private static AgentCard withoutOutputModes(final AgentCard card) {
        return new AgentCard(
                card.name(),
                card.description(),
                card.supportedInterfaces(),
                card.provider(),
                card.version(),
                card.documentationUrl(),
                card.capabilities(),
                card.securitySchemes(),
                card.securityRequirements(),
                card.defaultInputModes(),
                null,
                card.skills(),
                card.signatures(),
                card.iconUrl());
    }
}
