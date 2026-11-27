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
package io.yupiik.ia.agent.core.tools.builtin;

import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentModelResolver;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.EffectiveAgentModel;
import io.yupiik.ia.agent.core.agent.SubagentRoutingPolicy;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.SpawnSubagentToolConfiguration;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** On-demand, bounded catalog of agent profiles and configured model routes. */
@ApplicationScoped
public class ListSubagentOptionsTool implements EmbeddedTool {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_OFFSET = 100_000;
    private static final int MAX_DESCRIPTION_LENGTH = 300;

    private final AgentProvider agentProvider;
    private final ProviderManager providerManager;
    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final AgentModelResolver agentModelResolver;

    public ListSubagentOptionsTool(
            final AgentProvider agentProvider,
            final ProviderManager providerManager,
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final AgentModelResolver agentModelResolver) {
        this.agentProvider = agentProvider;
        this.providerManager = providerManager;
        this.configuration = configuration;
        this.mapper = mapper;
        this.agentModelResolver = agentModelResolver;
    }

    public ListSubagentOptionsTool(
            final AgentProvider agentProvider,
            final ProviderManager providerManager,
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper) {
        this(agentProvider, providerManager, configuration, mapper, null);
    }

    protected ListSubagentOptionsTool() {
        this.agentProvider = null;
        this.providerManager = null;
        this.configuration = null;
        this.mapper = null;
        this.agentModelResolver = null;
    }

    @Override
    public String name() {
        return "list_subagent_options";
    }

    @Override
    public String description(final Path workdir) {
        return "Search configured agent profiles and model routes for spawn_subagent. "
                + "By default list non-blacklisted models on the active session provider; an explicit provider filter "
                + "selects another provider's catalog without authorizing execution. Entries report default, authorized, "
                + "and requires_approval. Explicit unauthorized model choices need dedicated user approval, even in YOLO. "
                + "Pass kind (agents, models, or all) and a small limit to keep the reply cheap; filter by a "
                + "provider id or a query substring when you already know what to look for. Call only when you "
                + "need to choose or inspect options; results are bounded and paginated.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of(
                "kind", Schemas.str("Options to list: agents, models, or all (default: all)."),
                "query",
                        Schemas.str(
                                "Optional case-insensitive substring to match names, descriptions, model ids, or providers."),
                "provider",
                        Schemas.str(
                                "Provider catalog to inspect (default: active session provider); not execution approval."),
                "offset", Schemas.intProp("Zero-based result offset for pagination (default: 0)."),
                "limit", Schemas.intProp("Maximum results to return (default: 20, hard maximum: 50).")));
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var kind = arg(args, "kind");
        final var normalizedKind =
                kind == null || kind.isBlank() ? "all" : kind.strip().toLowerCase(Locale.ROOT);
        if (!List.of("all", "agents", "models").contains(normalizedKind)) {
            return ToolResult.error("kind must be agents, models, or all");
        }
        final var query = arg(args, "query");
        final var provider = arg(args, "provider");
        final int offset;
        final int limit;
        try {
            offset = intArg(args.get("offset"), 0);
            limit = intArg(args.get("limit"), DEFAULT_LIMIT);
        } catch (final IllegalArgumentException invalid) {
            return ToolResult.error("offset and limit must be non-negative integers");
        }
        if (offset > MAX_OFFSET) {
            return ToolResult.error("offset must be between 0 and " + MAX_OFFSET);
        }
        if (limit < 1) {
            return ToolResult.error("limit must be a positive integer between 1 and " + MAX_LIMIT);
        }
        final List<Map<String, Object>> entries;
        try {
            final var routing = new SubagentRoutingPolicy(configuration, providerManager, agentModelResolver, context);
            entries = entries(normalizedKind, query, provider, context, routing);
        } catch (final IllegalArgumentException invalid) {
            return ToolResult.error(invalid.getMessage());
        }
        final var page =
                entries.stream().skip(offset).limit(Math.min(limit, MAX_LIMIT)).toList();
        final var result = new LinkedHashMap<String, Object>();
        result.put("options", page);
        result.put("total", entries.size());
        result.put("offset", offset);
        result.put("limit", Math.min(limit, MAX_LIMIT));
        if (offset + page.size() < entries.size()) {
            result.put("next_offset", offset + page.size());
        }
        try {
            return ToolResult.ok(mapper.toString(result));
        } catch (final RuntimeException e) {
            return ToolResult.error("Failed to serialize catalog: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> entries(
            final String kind,
            final String query,
            final String providerFilter,
            final ToolContext context,
            final SubagentRoutingPolicy routing) {
        final var policy = policy();
        final var search = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        final var entries = new ArrayList<Map<String, Object>>();
        final var includeAgents =
                ("all".equals(kind) && (providerFilter == null || providerFilter.isBlank())) || "agents".equals(kind);
        if (includeAgents) {
            for (final var agent : agentProvider.agents()) {
                if (!policy.allowsAgent(agent.name())) {
                    continue;
                }
                final var description = compactDescription(agent.description());
                final EffectiveAgentModel effective;
                try {
                    effective = routing.select(agent, null);
                } catch (final IllegalArgumentException invalidRoute) {
                    continue;
                }
                final var model = effective.routedReference();
                if (!routing.visible(effective, providerFilter)) {
                    continue;
                }
                final var haystack = (agent.name() + " " + description).toLowerCase(Locale.ROOT);
                if (!search.isEmpty() && !haystack.contains(search)) {
                    continue;
                }
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("kind", "agent");
                entry.put("name", agent.name());
                if (description != null) {
                    entry.put("description", description);
                }
                if (model != null) {
                    entry.put("model", model);
                }
                authorization(entry, routing, effective);
                entries.add(entry);
            }
        }
        if ("all".equals(kind) || "models".equals(kind)) {
            final var references = new LinkedHashSet<String>();
            references.add(routing.parent().routedReference());
            try {
                references.add(routing.defaultModel().routedReference());
            } catch (final IllegalArgumentException invalidDefault) {
                // A stale or cross-provider default does not hide the session provider's catalog.
            }
            if (context.permissions() != null) {
                references.addAll(context.permissions().subagentRoutes().routes());
            }
            for (final var provider : providerManager.providers()) {
                final var id = provider.id() == null || provider.id().isBlank() ? "default" : provider.id();
                if (provider.model() != null && !provider.model().isBlank()) {
                    references.add("$" + id + "/" + provider.model());
                }
                if (provider.models() != null) {
                    references.addAll(provider.models().stream()
                            .filter(m ->
                                    m != null && m.model() != null && !m.model().isBlank())
                            .map(m -> "$" + id + "/" + m.model())
                            .toList());
                }
            }
            for (final var reference : references) {
                try {
                    addModel(entries, routing, search, providerFilter, routing.resolve(reference));
                } catch (final IllegalArgumentException invalidRoute) {
                    // Stale configuration/grants never disclose an unresolvable route.
                }
            }
        }
        entries.sort(Comparator.comparing((Map<String, Object> it) -> String.valueOf(it.get("kind")))
                .thenComparing(it -> String.valueOf(it.get("name"))));
        return entries;
    }

    private void addModel(
            final List<Map<String, Object>> entries,
            final SubagentRoutingPolicy routing,
            final String search,
            final String providerFilter,
            final EffectiveAgentModel effective) {
        if (!routing.visible(effective, providerFilter)) {
            return;
        }
        final var providerId = effective.settings().providerId();
        final var model = effective.reference();
        final var reference = effective.routedReference();
        final var haystack = (providerId + " " + model + " " + reference).toLowerCase(Locale.ROOT);
        if (!search.isEmpty() && !haystack.contains(search)) {
            return;
        }
        final var entry = new LinkedHashMap<String, Object>();
        entry.put("kind", "model");
        entry.put("name", model);
        entry.put("provider", providerId);
        entry.put("reference", reference);
        authorization(entry, routing, effective);
        entries.add(entry);
    }

    private void authorization(
            final Map<String, Object> entry, final SubagentRoutingPolicy routing, final EffectiveAgentModel effective) {
        final var authorized = routing.available(effective);
        entry.put("default", routing.isDefault(effective));
        entry.put("authorized", authorized);
        entry.put("requires_approval", !authorized);
    }

    private SpawnSubagentToolConfiguration policy() {
        final var configured =
                configuration.tools() == null ? null : configuration.tools().spawnSubagent();
        return configured == null ? new SpawnSubagentToolConfiguration(null, null, null) : configured;
    }

    private static String compactDescription(final String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        final var value = description.strip();
        return value.length() <= MAX_DESCRIPTION_LENGTH
                ? value
                : value.substring(0, MAX_DESCRIPTION_LENGTH - 3) + "...";
    }

    private static int intArg(final Object value, final int fallback) {
        if (!(value instanceof Number number)) {
            return fallback;
        }
        final long numeric;
        try {
            if (number instanceof BigDecimal decimal) {
                numeric = decimal.longValueExact();
            } else if (number instanceof BigInteger integer) {
                numeric = integer.longValueExact();
            } else if (number instanceof Byte
                    || number instanceof Short
                    || number instanceof Integer
                    || number instanceof Long) {
                numeric = number.longValue();
            } else {
                final var doubleValue = number.doubleValue();
                if (!Double.isFinite(doubleValue)
                        || doubleValue != Math.rint(doubleValue)
                        || doubleValue < 0
                        || doubleValue > Integer.MAX_VALUE) {
                    throw new ArithmeticException("not a bounded integer");
                }
                numeric = (long) doubleValue;
            }
        } catch (final ArithmeticException invalid) {
            throw new IllegalArgumentException("pagination values must be non-negative integers");
        }
        if (numeric < 0 || numeric > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("pagination values must be non-negative integers");
        }
        return (int) numeric;
    }
}
