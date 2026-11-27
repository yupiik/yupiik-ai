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
package io.yupiik.fusion.a2a.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;
import java.util.Map;

/**
 * The self describing manifest of an agent: who it is, what it can do and how to talk to it.
 * <p>
 * It is served on {@code /.well-known/agent-card.json} - see
 * {@link io.yupiik.fusion.a2a.protocol.AgentCardEndpoint} - and built from
 * {@link io.yupiik.fusion.a2a.configuration.A2AConfiguration}, the {@code @A2ASkill} methods and the
 * {@link io.yupiik.fusion.a2a.api.A2ASkills} beans by
 * {@link io.yupiik.fusion.a2a.service.AgentCardService}.
 *
 * @param name                 a human readable agent name.
 * @param description          what the agent does, this is what another agent reads to decide to delegate to it.
 * @param supportedInterfaces  the endpoints the agent answers on, ordered by preference - the first one is preferred.
 * @param provider             who runs the agent.
 * @param version             the agent version, it is also used as the {@code ETag} of the card.
 * @param documentationUrl     where to read more.
 * @param capabilities         the optional features the agent supports.
 * @param securitySchemes      the authentication schemes by name.
 * @param securityRequirements which schemes - and scopes - are needed to contact the agent.
 * @param defaultInputModes    the media types the agent accepts, they can be overridden per skill.
 * @param defaultOutputModes   the media types the agent produces, they can be overridden per skill.
 * @param skills               what the agent can do.
 * @param signatures           the JWS signatures of this card, if any.
 * @param iconUrl              an icon for the agent.
 */
@JsonModel
public record AgentCard(
        @Property(documentation = "A human readable agent name.")
        String name,

        @Property(documentation = "What the agent does, this is what another agent reads to decide to delegate to it.")
        String description,

        @Property(
                documentation =
                        "The endpoints the agent answers on, ordered by preference - the first one is preferred.")
        List<AgentInterface> supportedInterfaces,

        @Property(documentation = "Who runs the agent.") AgentProvider provider,

        @Property(documentation = "The agent version, it is also used as the ETag of the card.")
        String version,

        @Property(documentation = "Where to read more.") String documentationUrl,

        @Property(documentation = "The optional features the agent supports.")
        AgentCapabilities capabilities,

        @Property(documentation = "The authentication schemes by name.")
        Map<String, SecurityScheme> securitySchemes,

        @Property(documentation = "Which schemes - and scopes - are needed to contact the agent.")
        List<SecurityRequirement> securityRequirements,

        @Property(documentation = "The media types the agent accepts, they can be overridden per skill.")
        List<String> defaultInputModes,

        @Property(documentation = "The media types the agent produces, they can be overridden per skill.")
        List<String> defaultOutputModes,

        @Property(documentation = "What the agent can do.") List<AgentSkill> skills,

        @Property(documentation = "The JWS signatures of this card, if any.")
        List<AgentCardSignature> signatures,

        @Property(documentation = "An icon for the agent.") String iconUrl) {
    /**
     * @param supportedInterfaces the interfaces to advertise.
     * @return a copy of this card exposing {@code supportedInterfaces}, which is how the endpoint URLs are resolved
     * against the request when they are not configured.
     */
    public AgentCard withInterfaces(final List<AgentInterface> supportedInterfaces) {
        return new AgentCard(
                name,
                description,
                supportedInterfaces,
                provider,
                version,
                documentationUrl,
                capabilities,
                securitySchemes,
                securityRequirements,
                defaultInputModes,
                defaultOutputModes,
                skills,
                signatures,
                iconUrl);
    }

    /**
     * @param skills the skills to advertise.
     * @return a copy of this card exposing {@code skills}.
     */
    public AgentCard withSkills(final List<AgentSkill> skills) {
        return new AgentCard(
                name,
                description,
                supportedInterfaces,
                provider,
                version,
                documentationUrl,
                capabilities,
                securitySchemes,
                securityRequirements,
                defaultInputModes,
                defaultOutputModes,
                skills,
                signatures,
                iconUrl);
    }
}
