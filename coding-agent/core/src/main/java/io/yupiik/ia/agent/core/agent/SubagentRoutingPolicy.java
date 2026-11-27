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

import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderSubagentConfiguration;
import io.yupiik.ia.agent.core.configuration.SubagentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.util.List;

/** One session snapshot shared by catalog filtering and spawn admission; explicit session routes never follow another tab. */
public final class SubagentRoutingPolicy {
    private final CodingAgentConfiguration configuration;
    private final ProviderManager providers;
    private final AgentModelResolver resolver;
    private final ToolContext context;
    private final EffectiveAgentModel parent;
    private final SubagentConfiguration global;
    private final ProviderSubagentConfiguration provider;

    public SubagentRoutingPolicy(
            final CodingAgentConfiguration configuration,
            final ProviderManager providers,
            final AgentModelResolver resolver,
            final ToolContext context) {
        this.configuration = configuration;
        this.providers = providers;
        this.resolver = resolver == null ? new AgentModelResolver(providers) : resolver;
        this.context = context;
        final var settings = context == null ? null : context.settings();
        if (providers == null || settings == null) {
            throw new IllegalArgumentException("Sub-agent routing requires the active session provider/model.");
        }
        final String reference;
        if (settings.model() != null && settings.model().startsWith("$")) {
            reference = settings.model();
        } else {
            // A null provider in ChatSettings means the session follows the process default, not an
            // unknown provider (the UI setter also uses "default" to clear its override).
            final var source =
                    settings.providerId() == null ? providers.current() : providers.provider(settings.providerId());
            if (source == null
                    || settings.providerId() != null && !settings.providerId().equals(source.id())) {
                throw new IllegalArgumentException("Sub-agent routing requires the active session provider/model.");
            }
            final var model =
                    settings.model() == null || settings.model().isBlank() ? source.model() : settings.model();
            final var id = source.id() == null || source.id().isBlank() ? "default" : source.id();
            reference = "$" + id + "/" + (model == null ? "" : model);
        }
        parent = resolve(reference);
        global = configuration.subagents();
        provider = parent.resolved().provider().subagents();
    }

    public EffectiveAgentModel parent() {
        return parent;
    }

    public int maxDepth() {
        return provider == null ? 1 : provider.maxDepth();
    }

    /** The admitted route is allowed even when its approval was only valid for one spawn. */
    public SubagentScope childScope(final EffectiveAgentModel admitted) {
        final var inherited = context.subagentScope();
        return new SubagentScope(
                inherited == null ? 1 : inherited.depth() + 1,
                admitted.settings().providerId(),
                this::hardAllowed,
                candidate -> candidate.routedReference().equals(admitted.routedReference()) || available(candidate),
                () -> context.isCancelled() || context.isCallCancelled());
    }

    private boolean overridesEnabled() {
        return provider != null && provider.allowModelOverride() != null
                ? provider.allowModelOverride()
                : global != null && global.allowModelOverride();
    }

    public EffectiveAgentModel select(final AgentConfiguration profile, final String explicit) {
        final var profileModel = profile == null || profile.model() == null
                ? null
                : resolver.configuredName(profile.model().name());
        if (explicit != null && !explicit.isBlank()) {
            return resolve(explicit.strip());
        }
        if (profileModel != null) {
            return sameProvider(resolve(profileModel));
        }
        return defaultModel();
    }

    public EffectiveAgentModel defaultModel() {
        final var selected = provider != null && provider.defaultModel() != null
                ? provider.defaultModel()
                : global == null ? null : global.defaultModel();
        return selected == null || selected.isBlank() ? parent : sameProvider(resolve(selected.strip()));
    }

    private EffectiveAgentModel sameProvider(final EffectiveAgentModel effective) {
        if (!parent.settings().providerId().equals(effective.settings().providerId())) {
            throw new IllegalArgumentException(
                    "Implicit cross-provider sub-agent routing is disabled; explicitly select $provider/model.");
        }
        return effective;
    }

    public EffectiveAgentModel resolve(final String reference) {
        if (reference == null
                || reference.isBlank()
                || reference.startsWith("$") && (reference.indexOf('/') <= 1 || reference.endsWith("/"))) {
            throw new IllegalArgumentException("Unknown or incomplete sub-agent model route.");
        }
        if (reference.startsWith("$")) {
            final var id = reference.substring(1, reference.indexOf('/'));
            if (providers.providers().stream()
                    .noneMatch(
                            p -> id.equals(p.id()) || "default".equals(id) && (p.id() == null || p.id().isBlank()))) {
                throw new IllegalArgumentException("Unknown or incomplete sub-agent model route.");
            }
        }
        // Bare sub-agent names never search another provider's catalog.
        final var pinned =
                reference.startsWith("$") ? reference : "$" + parent.settings().providerId() + "/" + reference;
        final var effective = resolver.resolve(context.settings(), null, pinned, null);
        if (effective.resolved() == null
                || effective.resolved().provider() == null
                || effective.reference() == null
                || effective.reference().isBlank()) {
            throw new IllegalArgumentException("Unknown or incomplete sub-agent model route.");
        }
        // Keep both concrete provider settings and the explicit wire route, including a provider
        // literally named "default", rather than the UI setter's reset-to-process-default sentinel.
        final var settings = effective.settings();
        final var id = effective.resolved().provider().id();
        return new EffectiveAgentModel(
                effective.resolved(),
                new ChatSettings(
                        id == null || id.isBlank() ? "default" : id,
                        effective.routedReference(),
                        settings.variant(),
                        settings.sampling()),
                effective.reference(),
                effective.routedReference());
    }

    /** Administrative ceilings cannot be relaxed by interactive consent or a session grant. */
    private boolean hardAllowed(final EffectiveAgentModel effective) {
        final var route = effective.routedReference();
        final var tools =
                configuration.tools() == null ? null : configuration.tools().spawnSubagent();
        final var destination = effective.resolved().provider().subagents();
        return (context.subagentId() == null
                        || context.subagentScope() != null
                                && context.subagentScope().allows(effective))
                && SubagentModelPolicy.allows(tools, effective)
                && !contains(global == null ? null : global.excludes(), route)
                && !contains(provider == null ? null : provider.excludes(), route)
                && !contains(destination == null ? null : destination.excludes(), route);
    }

    /** Discovery is provider-scoped, not an execution grant. */
    public boolean visible(final EffectiveAgentModel effective, final String providerFilter) {
        final var scope = providerFilter == null || providerFilter.isBlank()
                ? parent.settings().providerId()
                : providerFilter;
        return scope.equals(effective.settings().providerId()) && hardAllowed(effective);
    }

    public boolean isDefault(final EffectiveAgentModel effective) {
        try {
            return defaultModel().routedReference().equals(effective.routedReference());
        } catch (final IllegalArgumentException invalidDefault) {
            // An invalid default must not authorize the parent route or prevent explicit recovery.
            return false;
        }
    }

    /** Financial authorization is separate from catalog visibility and generic tool/YOLO permissions. */
    public boolean available(final EffectiveAgentModel effective) {
        if (!hardAllowed(effective)) {
            return false;
        }
        final var route = effective.routedReference();
        if (context.permissions() != null
                && context.permissions().subagentRoutes().allows(route)) {
            return true;
        }
        if (context.subagentScope() != null && !context.subagentScope().authorized(effective)) {
            return false;
        }
        if (isDefault(effective)) {
            return true;
        }
        final var includes = provider != null && provider.includes() != null
                ? provider.includes()
                : global == null ? null : global.includes();
        return overridesEnabled() && contains(includes, route);
    }

    public boolean admit(final EffectiveAgentModel effective, final boolean explicit) {
        if (context.isCancelled()
                || !hardAllowed(effective)
                || !explicit
                        && !parent.settings()
                                .providerId()
                                .equals(effective.settings().providerId())) {
            return false;
        }
        return available(effective)
                || explicit
                        && context.permissions() != null
                        && context.permissions()
                                .subagentRoutes()
                                .approve(
                                        parent.routedReference(),
                                        effective.routedReference(),
                                        context.permissions().gate(),
                                        context::isCancelled);
    }

    private static boolean contains(final List<String> routes, final String route) {
        return routes != null && routes.contains(route);
    }
}
