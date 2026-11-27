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

import io.yupiik.fusion.a2a.api.A2AAgentCard;
import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.model.AgentCapabilities;
import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.AgentInterface;
import io.yupiik.fusion.a2a.model.AgentProvider;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.a2a.protocol.A2AProtocol;
import io.yupiik.fusion.a2a.signature.CardSigner;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.http.server.api.Request;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds the {@link AgentCard} the server publishes.
 * <p>
 * The plain attributes come from {@link A2AConfiguration}, the skills from {@link SkillService} and everything
 * structured - security schemes, extensions, signatures - from the {@link A2AAgentCard} beans. The card is assembled
 * once at startup; only the interface URLs are resolved per request when {@code fusion.a2a.publicUrl} is not set.
 */
@ApplicationScoped
public class AgentCardService {
    private final A2AConfiguration configuration;
    private final AgentCard base;
    private final AgentCard extendedBase;
    private final boolean extendedConfigured;
    private final List<String> outputModes;
    private final List<String> requiredExtensions;

    public AgentCardService(
            final A2AConfiguration configuration,
            final SkillService skills,
            final List<A2AAgentCard> customizers,
            final CardSigner signer) {
        if (configuration == null) { // generated lazy proxy
            this.configuration = null;
            this.base = null;
            this.extendedBase = null;
            this.extendedConfigured = false;
            this.outputModes = List.of();
            this.requiredExtensions = List.of();
            return;
        }

        this.configuration = configuration;
        final var all = customizers == null ? List.<A2AAgentCard>of() : customizers;

        if (signer.isEnabled() && blankToNull(configuration.publicUrl()) == null) {
            // without it the interface urls are resolved from each request, so the signed bytes would not be the bytes
            // served and no caller could ever verify the card
            throw new IllegalStateException(
                    "fusion.a2a.signatureKey is set but fusion.a2a.publicUrl is not: a signed agent card must advertise "
                            + "fixed interface urls, else the signature covers content which changes per request");
        }

        final var raw = new AgentCard(
                configuration.name(),
                configuration.description(),
                interfaces(configuration.publicUrl()),
                provider(configuration),
                configuration.version(),
                blankToNull(configuration.documentationUrl()),
                new AgentCapabilities(
                        configuration.streaming(),
                        configuration.pushNotifications(),
                        null,
                        configuration.extendedAgentCard()),
                null,
                null,
                modes(configuration.defaultInputModes()),
                modes(configuration.defaultOutputModes()),
                skills.skills(),
                null,
                blankToNull(configuration.iconUrl()));

        // signing comes last: the customizers must not be able to change the content after it was covered
        this.base = signer.sign(
                customize(raw, all.stream().filter(it -> !it.extended()).toList()));
        this.extendedConfigured = all.stream().anyMatch(A2AAgentCard::extended);
        this.extendedBase = extendedConfigured
                ? signer.sign(customize(
                        base, all.stream().filter(A2AAgentCard::extended).toList()))
                : null;
        this.outputModes = base.defaultOutputModes() == null ? List.of() : base.defaultOutputModes();
        this.requiredExtensions =
                base.capabilities() == null || base.capabilities().extensions() == null
                        ? List.of()
                        : base.capabilities().extensions().stream()
                                .filter(it -> it.required())
                                .map(it -> it.uri())
                                .toList();
    }

    /**
     * @param request the request fetching the card, it is used to derive the interface URLs when
     *                {@code fusion.a2a.publicUrl} is not set.
     * @return the public card.
     */
    public AgentCard card(final Request request) {
        return resolve(base, request);
    }

    /**
     * @param request the request fetching the card.
     * @return the card an authenticated caller gets.
     * @throws io.yupiik.fusion.jsonrpc.JsonRpcException {@code UnsupportedOperationError} when the capability is
     *                                                   disabled, {@code ExtendedAgentCardNotConfiguredError} when it
     *                                                   is enabled but no {@link A2AAgentCard#extended()} bean
     *                                                   contributes anything.
     */
    public AgentCard extendedCard(final Request request) {
        if (!configuration.extendedAgentCard()) {
            throw A2AErrors.unsupportedOperation("This agent does not support extended agent cards");
        }
        if (!extendedConfigured) {
            throw A2AErrors.extendedAgentCardNotConfigured();
        }
        return resolve(extendedBase, request);
    }

    /**
     * @return the media types the agent can produce, i.e. what an {@code acceptedOutputModes} is validated against.
     */
    public List<String> outputModes() {
        return outputModes;
    }

    /**
     * @return the URIs of the extensions a client must declare to be served.
     */
    public List<String> requiredExtensions() {
        return requiredExtensions;
    }

    /**
     * @return the routing identifier clients must echo, {@code null} when the server hosts a single agent.
     */
    public String tenant() {
        return blankToNull(configuration.tenant());
    }

    private AgentCard resolve(final AgentCard card, final Request request) {
        if (card.supportedInterfaces() != null && !card.supportedInterfaces().isEmpty()) {
            return card;
        }
        return card.withInterfaces(interfaces(baseUrl(request)));
    }

    /**
     * @param request the current request.
     * @return the base URL to advertise: the configured one, else the scheme and {@code Host} of the request.
     */
    public String baseUrl(final Request request) {
        final var configured = blankToNull(configuration.publicUrl());
        if (configured != null) {
            return trimTrailingSlash(configured);
        }
        if (request == null) {
            return "";
        }
        final var host = request.header("host");
        return host == null ? "" : request.scheme() + "://" + host;
    }

    private List<AgentInterface> interfaces(final String baseUrl) {
        final var url = blankToNull(baseUrl);
        if (url == null) {
            return List.of(); // resolved per request
        }
        final var root = trimTrailingSlash(url);
        final var tenant = tenant();
        final var all = new ArrayList<AgentInterface>(2);
        all.add(new AgentInterface(
                root + A2AProtocol.JSONRPC_PATH, AgentInterface.JSONRPC, tenant, A2AProtocol.VERSION));
        if (configuration.restBinding()) {
            all.add(new AgentInterface(
                    root + A2AProtocol.REST_PATH, AgentInterface.HTTP_JSON, tenant, A2AProtocol.VERSION));
        }
        return List.copyOf(all);
    }

    private static AgentCard customize(final AgentCard card, final List<A2AAgentCard> customizers) {
        var current = card;
        for (final var customizer : customizers) {
            final var customized = customizer.customize(current);
            if (customized != null) {
                current = customized;
            }
        }
        return current;
    }

    private static AgentProvider provider(final A2AConfiguration configuration) {
        final var organization = blankToNull(configuration.providerOrganization());
        return organization == null ? null : new AgentProvider(blankToNull(configuration.providerUrl()), organization);
    }

    private static List<String> modes(final String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return List.of();
        }
        return Stream.of(commaSeparated.split(","))
                .map(String::strip)
                .filter(it -> !it.isEmpty())
                .toList();
    }

    private static String trimTrailingSlash(final String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
