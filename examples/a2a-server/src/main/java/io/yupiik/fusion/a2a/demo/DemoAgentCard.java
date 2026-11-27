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
package io.yupiik.fusion.a2a.demo;

import io.yupiik.fusion.a2a.api.A2AAgentCard;
import io.yupiik.fusion.a2a.model.AgentCapabilities;
import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.AgentExtension;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.List;

/**
 * Adds to the card what configuration cannot express: here an optional extension.
 * <p>
 * This is also where security schemes and signatures would go - see
 * {@link io.yupiik.fusion.a2a.model.SecurityScheme#bearer(String)} and
 * {@link io.yupiik.fusion.a2a.model.AgentCardSignature}. Nothing is declared here so the demo stays callable without
 * credentials.
 */
@ApplicationScoped
public class DemoAgentCard implements A2AAgentCard {
    /**
     * An extension a client may activate with the {@code A2A-Extensions} header. It is not {@code required} so a client
     * ignoring it is served as usual.
     */
    public static final String TRACE_EXTENSION = "https://yupiik.io/a2a/extensions/demo-trace/v1";

    @Override
    public AgentCard customize(final AgentCard card) {
        final var capabilities = card.capabilities();
        return new AgentCard(
                card.name(),
                card.description(),
                card.supportedInterfaces(),
                card.provider(),
                card.version(),
                card.documentationUrl(),
                new AgentCapabilities(
                        capabilities.streaming(),
                        capabilities.pushNotifications(),
                        List.of(AgentExtension.of(
                                TRACE_EXTENSION, "Echoes the activated extensions in the task metadata.", false)),
                        capabilities.extendedAgentCard()),
                card.securitySchemes(),
                card.securityRequirements(),
                card.defaultInputModes(),
                card.defaultOutputModes(),
                card.skills(),
                card.signatures(),
                card.iconUrl());
    }
}
