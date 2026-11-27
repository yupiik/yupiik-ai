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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.SecretRedactionConfiguration;
import java.util.List;

/**
 * A {@link SecretMatcher} fed by {@code yca.secrets.prefixes}: every value starting with any listed
 * prefix and long enough (at least {@code minValueLength}) becomes a same-family fake. An easy way
 * to add credential families ("mycorp_", "int_tok_", ...) without writing Java, complementing
 * {@code knownValues} (exact values) and {@code patterns} (arbitrary regex).
 */
@ApplicationScoped
public class ConfiguredPrefixSecretMatcher implements SecretMatcher {

    private final List<SecretMatcher> entries;

    public ConfiguredPrefixSecretMatcher(final CodingAgentConfiguration configuration) {
        this.entries = build(configuration == null ? null : configuration.secrets());
    }

    protected ConfiguredPrefixSecretMatcher() {
        this.entries = build(null);
    }

    private static List<SecretMatcher> build(final SecretRedactionConfiguration cfg) {
        final var prefixes = cfg == null || cfg.prefixes() == null ? List.<String>of() : cfg.prefixes();
        final var minLen = cfg == null ? 8 : Math.max(1, cfg.minValueLength());
        return prefixes.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(p -> (SecretMatcher) new PrefixSecretMatcher(p, minLen) {
                    @Override
                    public int order() {
                        return 20_000 + prefix.length(); // configured prefixes beat built-ins
                    }
                })
                .toList();
    }

    @Override
    public boolean matches(final String value) {
        if (value == null) {
            return false;
        }
        for (final var e : entries) {
            if (e.matches(value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String replace(final String value) {
        for (final var e : entries) {
            if (e.matches(value)) {
                return e.replace(value);
            }
        }
        return value;
    }

    @Override
    public int order() {
        return 3_000;
    }

    @Override
    public String label() {
        return "configured-prefixes";
    }
}
