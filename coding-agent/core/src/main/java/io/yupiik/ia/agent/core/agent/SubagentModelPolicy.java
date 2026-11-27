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
package io.yupiik.ia.agent.core.agent;

import io.yupiik.ia.agent.core.configuration.SpawnSubagentToolConfiguration;

/**
 * Shared policy gate over the {@link EffectiveAgentModel} resolved once at spawn time. Consumer of
 * the same authoritative tuple used for execution, so the allowlist decision never re-derives the
 * provider or model differently from what actually goes on the wire.
 */
public final class SubagentModelPolicy {
    private SubagentModelPolicy() {}

    public static boolean allows(final SpawnSubagentToolConfiguration policy, final EffectiveAgentModel effective) {
        if (policy == null || effective == null || effective.reference() == null) {
            return true;
        }
        // A model is allowed when either the bare name or its routed $provider/model form is allowed.
        final var model = effective.reference();
        final var routed = effective.routedReference();
        final boolean modelAllowed = policy.allowsModel(model) || (routed != null && policy.allowsModel(routed));
        if (!modelAllowed) {
            return false;
        }
        final var providerId = providerIdOf(effective);
        return policy.allowsProvider(providerId);
    }

    private static String providerIdOf(final EffectiveAgentModel effective) {
        final var settings = effective.settings();
        if (settings != null
                && settings.providerId() != null
                && !settings.providerId().isBlank()) {
            return settings.providerId();
        }
        final var resolved = effective.resolved();
        if (resolved != null && resolved.provider() != null) {
            final var id = resolved.provider().id();
            return id == null || id.isBlank() ? "default" : id;
        }
        return "default";
    }
}
