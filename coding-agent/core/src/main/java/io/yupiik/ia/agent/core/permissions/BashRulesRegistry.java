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
package io.yupiik.ia.agent.core.permissions;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.BashPermissionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandPermissionConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-scoped owner of the compiled {@link BashRules} caches. The session-scoped
 * {@link PermissionService} keeps the dynamic per-session rule overrides (cleared when switching back
 * to plan and when the session closes); this registry only turns the effective rules into evaluators.
 *
 * <p>The configured rules (the {@code yca.permissions.bash.rules} config, defaults being
 * {@link CommandPermissionConfiguration#defaultRules()}) are compiled <em>once for the app lifecycle</em>
 * and cached as the base evaluator, keyed by the config identity: as soon as the config reference changes
 * (a runtime override via {@link #setConfig(BashPermissionsConfiguration)} or a new
 * {@code CodingAgentConfiguration}) the base is recompiled. Empty override lists return that single base
 * instance, so the steady state (no dynamic rules) costs one compiled evaluator for the whole process.</p>
 *
 * <p>Once a session adds dynamic overrides, the union of the configured rules and the override snapshot
 * is compiled on demand and cached in a bounded {@link ConcurrentHashMap} keyed by the overrides list
 * (value equality: identical override sets share one entry across sessions). The cache never grows past
 * {@code yca.permissions.bash.maxCombined} (default 1024, {@code defaultValue} on the bash configuration)
 * entries: after an insertion the bound is checked and the whole combined cache cleared when exceeded -
 * the cleaning never runs inside
 * {@link ConcurrentHashMap#computeIfAbsent(Object, java.util.function.Function)} (which forbids mutating
 * the map during computation), but right after it returns, so a concurrent request can transiently see the
 * pre-clean state and entries are simply recompiled on demand afterwards.</p>
 *
 * <p>Each combined evaluator is a full {@link BashRules.Compiled}: it carries its own substitution
 * verifier (built-in read-only families ∪ effective rules), so dynamically allowed rules support nested
 * {@code $( ... )} evaluation exactly like configured ones.</p>
 */
@ApplicationScoped
public class BashRulesRegistry {
    /** Fallback bound of the combined cache when no bash config is present at all (tests, CLI without permissions). */
    public static final int DEFAULT_MAX_COMBINED = 1024;

    private final CodingAgentConfiguration configuration;
    /**
     * SPI customizing the automatic permission-anchor selection of a bash command
     * (see {@link PermissionGrantingMatcherService}); injected by the container,
     * empty in tests/direct constructions.
     */
    private final List<PermissionGrantingMatcherService> grantingMatchers;

    private volatile BashPermissionsConfiguration configOverride;
    /** Base evaluator: the configured rules compiled once per config identity. */
    private volatile BashRules.Compiled base;

    private volatile BashPermissionsConfiguration baseSource;
    /** Combined evaluators keyed by the per-session overrides snapshot (value equality dedupes identical sets). */
    private final ConcurrentHashMap<List<CommandPermissionConfiguration>, BashRules.Compiled> combined =
            new ConcurrentHashMap<>();

    public BashRulesRegistry(final CodingAgentConfiguration configuration) {
        this(configuration, List.of());
    }

    public BashRulesRegistry(
            final CodingAgentConfiguration configuration,
            final List<PermissionGrantingMatcherService> grantingMatchers) {
        this.configuration = configuration;
        this.grantingMatchers = grantingMatchers == null ? List.of() : grantingMatchers;
    }

    protected BashRulesRegistry() {
        this.configuration = null;
        this.grantingMatchers = List.of();
    }

    /** The container-injected permission-granting matcher SPI beans (empty when none/not wired). */
    public List<PermissionGrantingMatcherService> grantingMatchers() {
        return grantingMatchers;
    }

    /**
     * Overrides the effective bash rules config at runtime. {@code null} resets to the
     * {@code CodingAgentConfiguration} value. Invalidates the base evaluator (and the combined cache).
     */
    public void setConfig(final BashPermissionsConfiguration config) {
        configOverride = config;
        base = null;
        baseSource = null;
        combined.clear();
    }

    /** The effective bash rules config: the runtime override when set, otherwise the configured one. */
    public BashPermissionsConfiguration config() {
        final var override = configOverride;
        if (override != null) {
            return override;
        }
        final var permissions = configuration == null ? null : configuration.permissions();
        return permissions == null ? null : permissions.bash();
    }

    /**
     * Evaluator for the given per-session overrides. Empty (or null) overrides return the base evaluator
     * (compiled once per config identity); otherwise the union is compiled on demand and cached in the
     * bounded combined cache. Never returns null: with no config and no overrides an empty evaluator covers
     * the gap (the caller's gate then decides).
     */
    public BashRules.Compiled compiled(final List<CommandPermissionConfiguration> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return base();
        }
        final var snapshot = List.copyOf(overrides);
        final var compiled = combined.computeIfAbsent(snapshot, this::compileCombined);
        // the bound check runs after insertion, never inside the computeIfAbsent mapping function
        cleanIfNeeded();
        return compiled;
    }

    /** The base evaluator for the current config, compiled once per config identity. */
    public BashRules.Compiled base() {
        final var current = config();
        final var cachedSource = baseSource;
        final var cached = base;
        if (cached != null && cachedSource == current) {
            return cached;
        }
        synchronized (this) {
            final var again = config();
            final var againSource = baseSource;
            final var againBase = base;
            if (againBase != null && againSource == again) {
                return againBase;
            }
            final var compiled = BashRules.compile(again);
            base = compiled;
            baseSource = again;
            return compiled;
        }
    }

    /** Unions the configured rules with the override snapshot (deduplicated) and compiles them. */
    private BashRules.Compiled compileCombined(final List<CommandPermissionConfiguration> snapshot) {
        final var configured = config();
        final var configuredRules = configured == null || configured.rules() == null
                ? List.<CommandPermissionConfiguration>of()
                : configured.rules();
        final var merged = new ArrayList<CommandPermissionConfiguration>(configuredRules.size() + snapshot.size());
        for (final var rule : configuredRules) {
            if (rule != null && !merged.contains(rule)) {
                merged.add(rule);
            }
        }
        for (final var rule : snapshot) {
            if (rule != null && !merged.contains(rule)) {
                merged.add(rule);
            }
        }
        return BashRules.compile(List.copyOf(merged));
    }

    /** Post-insert bound check (never mutates the map during a {@code computeIfAbsent} computation). */
    private void cleanIfNeeded() {
        final var current = config();
        final var bound = current == null ? DEFAULT_MAX_COMBINED : Math.max(1, current.maxCombined());
        if (combined.size() > bound) {
            combined.clear();
        }
    }

    /** For diagnostics: number of currently cached combined entries (0 when only the base is in use). */
    int combinedSize() {
        return combined.size();
    }
}
