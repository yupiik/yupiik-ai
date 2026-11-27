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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

/**
 * The single, authoritative model choice for an agent run (sub-agent spawn, profile-selected main
 * run, options catalog): explicit model, then profile model, then provider default, then the
 * inherited contextual selection. Every downstream consumer resolves the same tuple so preflight
 * policy and actual wire selection can never diverge.
 *
 * <p>Routing of the selected reference follows the documented contract: {@code $provider/model}
 * selects that provider explicitly; a bare name is pinned to whichever configured provider
 * declares it, then to the session's provider, then to the process current provider.
 * Sub-agent admission uses {@link SubagentRoutingPolicy} to pin bare names to the active
 * session provider before calling this resolver; main-agent routing is unchanged.</p>
 */
@ApplicationScoped
public class AgentModelResolver {
    private final ProviderManager providerManager;

    public AgentModelResolver(final ProviderManager providerManager) {
        this.providerManager = providerManager;
    }

    public EffectiveAgentModel resolve(
            final ChatSettings inherited,
            final AgentConfiguration profile,
            final String explicit,
            final String providerDefault) {
        final var base = inherited == null ? ChatSettings.EMPTY : inherited;
        final var profileModel = profile == null || profile.model() == null
                ? null
                : configuredName(profile.model().name());
        final var selected =
                first(configuredName(explicit), profileModel, configuredName(providerDefault), base.model());
        if (selected == null) {
            return new EffectiveAgentModel(null, base, null, null);
        }
        return route(selected, base);
    }

    /**
     * The configured model name of a {@link io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration},
     * or {@code null} when it is unset/blank or the implicit {@code "default"} placeholder. Centralizes
     * the "is this really configured?" interpretation so no caller re-inlines the {@code "default"} literal.
     */
    public String configuredName(final String name) {
        if (name == null) {
            return null;
        }
        final var value = name.strip();
        return value.isBlank() || "default".equalsIgnoreCase(value) ? null : value;
    }

    /**
     * Routes a selected reference to the concrete (provider, model) tuple: {@code $provider/model}
     * explicitly; a bare name is pinned to whichever configured provider declares it, else to the
     * inheriting session's provider, else to the process current provider.
     */
    private EffectiveAgentModel route(final String reference, final ChatSettings base) {
        if (providerManager == null) {
            // no provider manager (tests / minimal runtime): keep the bare reference on the base settings
            return new EffectiveAgentModel(null, base.withModel(reference), reference, reference);
        }
        final ProviderManager.ResolvedModel resolved;
        if (reference.startsWith("$")) {
            resolved = providerManager.resolve(reference);
        } else {
            final var routable = providerManager.toRoutedReference(reference);
            if (routable.startsWith("$")) {
                resolved = providerManager.resolve(routable);
            } else if (base.providerId() != null && providerManager.provider(base.providerId()) != null) {
                resolved = new ProviderManager.ResolvedModel(providerManager.provider(base.providerId()), reference);
            } else {
                resolved = providerManager.resolve(reference);
            }
        }
        final var provider = resolved == null ? null : resolved.provider();
        final var providerId =
                provider == null || provider.id() == null || provider.id().isBlank() ? "default" : provider.id();
        final var model =
                resolved == null || resolved.model() == null || resolved.model().isBlank()
                        ? (provider == null || provider.model() == null ? reference : provider.model())
                        : resolved.model();
        final var target =
                ChatSettings.EMPTY.withProviderId(providerId).withModel(model).inheritFrom(base);
        return new EffectiveAgentModel(
                resolved, target, model, "$".concat(providerId).concat("/").concat(model));
    }

    private static String first(final String... candidates) {
        for (final var candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return null;
    }
}
