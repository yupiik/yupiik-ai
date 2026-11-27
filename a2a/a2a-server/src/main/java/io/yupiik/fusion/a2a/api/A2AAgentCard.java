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
package io.yupiik.fusion.a2a.api;

import io.yupiik.fusion.a2a.model.AgentCard;

/**
 * Customizes the {@link AgentCard} the server publishes.
 * <p>
 * {@link io.yupiik.fusion.a2a.configuration.A2AConfiguration} covers the plain attributes; implement this interface
 * for everything which is structured - the security schemes, the extensions, the signatures - or which has to be
 * computed:
 * <pre>{@code
 * @ApplicationScoped
 * public class MyCard implements A2AAgentCard {
 *     @Override
 *     public AgentCard customize(final AgentCard card) {
 *         return new AgentCard(
 *                 card.name(), card.description(), card.supportedInterfaces(), card.provider(), card.version(),
 *                 card.documentationUrl(), card.capabilities(),
 *                 Map.of("bearer", SecurityScheme.bearer("JWT")),
 *                 List.of(SecurityRequirement.of("bearer")),
 *                 card.defaultInputModes(), card.defaultOutputModes(), card.skills(), card.signatures(),
 *                 card.iconUrl());
 *     }
 * }
 * }</pre>
 * All the implementations are chained in bean priority order ({@code @Order}), each one seeing the result of the
 * previous. The card is built once at startup.
 *
 * @see #extended() to serve a richer card to authenticated callers.
 */
public interface A2AAgentCard {
    /**
     * @param card the card built so far.
     * @return the card to publish.
     */
    AgentCard customize(AgentCard card);

    /**
     * Whether this implementation contributes to the public card or to the authenticated one.
     * <p>
     * The extended card - {@code GetExtendedAgentCard} - starts from the public one, then goes through the
     * implementations returning {@code true} here. Declaring at least one enables the
     * {@code capabilities.extendedAgentCard} flag; without any, the operation answers
     * {@code ExtendedAgentCardNotConfiguredError} when the capability is enabled by configuration.
     *
     * @return {@code true} to only apply to the extended card, {@code false} - the default - for the public one.
     */
    default boolean extended() {
        return false;
    }
}
