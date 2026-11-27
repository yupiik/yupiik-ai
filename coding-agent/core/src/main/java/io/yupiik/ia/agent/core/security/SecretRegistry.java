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
package io.yupiik.ia.agent.core.security;

import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.SecretRedactionConfiguration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Holds everything the redactor needs: the injected {@link SecretMatcher} SPI impls (built-in prefix
 * matchers, configured-prefix and regex matchers, extension contributions), the harvested exact
 * secret values collected from the injected {@link SecretValuesContributor}s (process env, env
 * files, Maven settings, known values - each a separate contributor), the exclusion allowlist and
 * the custom-pattern set.
 *
 * <p>The harvest runs once (on {@code Start}, or lazily on first use in headless flows where
 * {@code Start} has not fired): each contributor returns its exact secret values, the registry
 * de-duplicates, applies the min-length filter and sorts longest-first, so filters then match on
 * exact values rather than re-running any logic per message - O(n) and deterministic, never slow.</p>
 */
@ApplicationScoped
public class SecretRegistry {

    private static final Logger LOGGER = Logger.getLogger(SecretRegistry.class.getName());

    private final List<SecretMatcher> matchers;
    private final SecretRedactionConfiguration config;
    private final SecretVault vault;
    private final List<SecretValuesContributor> contributors;

    /** Exact harvested values, longest first (so the most specific substring wins). */
    private volatile List<String> harvestedValues = List.of();

    private final Set<String> runtimeValues = new LinkedHashSet<>();

    private volatile List<Pattern> customPatterns = List.of();
    private volatile List<Pattern> excludes = List.of();
    /** Guard so the harvest runs once (on Start, or lazily on first use when Start did not fire). */
    private final AtomicBoolean scanned = new AtomicBoolean();

    public SecretRegistry(
            final CodingAgentConfiguration configuration,
            final List<SecretMatcher> matchers,
            final SecretVault vault,
            final List<SecretValuesContributor> contributors) {
        this.config = configuration == null ? null : configuration.secrets();
        this.matchers = matchers == null ? List.of() : matchers;
        this.vault = vault;
        this.contributors = contributors == null ? List.of() : contributors;
    }

    protected SecretRegistry() {
        this.config = null;
        this.matchers = List.of();
        this.vault = null;
        this.contributors = List.of();
    }

    /** Run once at container start: collect secret values from every contributor. */
    public void onStart(@OnEvent @Order(Integer.MIN_VALUE) final Start start) {
        scan();
    }

    /** Runs the one-time harvest, first lazily on use if {@code Start} did not fire before the first request. */
    public void ensureScanned() {
        if (scanned.compareAndSet(false, true)) {
            scan();
        }
    }

    public synchronized void scan() {
        final var base = new LinkedHashSet<String>(harvestedValues);
        base.addAll(runtimeValues);
        for (final var contributor : contributors) {
            try {
                base.addAll(contributor.values());
            } catch (final RuntimeException e) {
                LOGGER.log(
                        Level.FINE,
                        () -> "Secret values contributor "
                                + contributor.getClass().getSimpleName() + " failed: " + e.getMessage());
            }
        }
        if (config != null) {
            customPatterns = compile(config.patterns());
            excludes = compile(config.exclude());
        }
        updateHarvest(base);
    }

    /** Adds values obtained at runtime, retaining them across subsequent scans. */
    public synchronized void registerValues(final String... values) {
        ensureScanned();
        for (final var value : values) {
            if (value != null && !value.isBlank() && value.length() >= minLength()) {
                runtimeValues.add(value);
            }
        }
        final var base = new LinkedHashSet<>(harvestedValues);
        base.addAll(runtimeValues);
        updateHarvest(base);
    }

    private void updateHarvest(final Set<String> base) {
        // longest first so substring replacement prefers the exact/longest match
        harvestedValues = base.stream()
                .filter(v -> v != null && !v.isBlank() && v.length() >= minLength())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    private int minLength() {
        return config == null ? 8 : config.minValueLength();
    }

    private static List<Pattern> compile(final List<String> regexes) {
        if (regexes == null || regexes.isEmpty()) {
            return List.of();
        }
        final var out = new ArrayList<Pattern>(regexes.size());
        for (final var r : regexes) {
            if (r != null && !r.isBlank()) {
                try {
                    out.add(Pattern.compile(r));
                } catch (final RuntimeException e) {
                    LOGGER.log(Level.FINE, () -> "Ignoring invalid secret regex '" + r + "': " + e.getMessage());
                }
            }
        }
        return List.copyOf(out);
    }

    public SecretRedactionConfiguration config() {
        return config;
    }

    public SecretVault vault() {
        return vault;
    }

    public List<SecretMatcher> matchers() {
        return matchers;
    }

    /** Custom substring patterns compiled for the current configuration scan, in declaration order. */
    public List<Pattern> customPatterns() {
        ensureScanned();
        return customPatterns;
    }

    /** True when {@code segment} is excluded from redaction by the allowlist. */
    public boolean inExclude(final String segment) {
        if (segment == null || excludes.isEmpty()) {
            return false;
        }
        for (final var p : excludes) {
            if (p.matcher(segment).find()) {
                return true;
            }
        }
        return false;
    }

    /** True when any configured custom regex pattern matches {@code segment}. */
    public boolean matchCustomPattern(final String segment) {
        if (segment == null || customPatterns.isEmpty()) {
            return false;
        }
        for (final var p : customPatterns) {
            if (p.matcher(segment).find()) {
                return true;
            }
        }
        return false;
    }

    public List<String> harvestedValues() {
        ensureScanned();
        return harvestedValues;
    }

    /** Test-only hook: seeds harvested exact values without touching the environment. */
    public synchronized void injectTestHarvest(final List<String> values) {
        scanned.set(true); // tests take over the harvest; do not re-scan the environment
        final var base = new LinkedHashSet<String>(values);
        harvestedValues = base.stream()
                .filter(v -> v != null && !v.isBlank() && v.length() >= minLength())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }
}
