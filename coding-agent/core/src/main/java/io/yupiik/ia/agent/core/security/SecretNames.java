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
import io.yupiik.ia.agent.core.configuration.SecretEnvironmentConfiguration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Regex-free detection of "secret-ish" variable / property / key names (env vars, env-file lines).
 * A name is treated as a secret holder when it is made of the usual snake / kebab / dot components
 * and one of those components (or a joined marker) is a known secret keyword such as
 * {@code PASSWORD}, {@code SECRET}, {@code TOKEN}, {@code API_KEY}, {@code CLIENT_SECRET},
 * {@code PRIVATE_KEY}, {@code AUTH}, {@code CREDENTIAL}, {@code PASSWD} or {@code DB_PASSWORD}.
 *
 * <p>The defaults come from {@code yca.secrets.environment.*}: the injected {@link
 * SecretEnvironmentConfiguration} record already carries them (keywords, qualifiers and the
 * known-safe name list), so this bean never re-implements its own defaults - it only consumes the
 * record. {@link #isSecretName} is purely keyword/qualifier-based; {@link #isKnownSafeName} reports
 * whether a name is on the {@code knownSafeValues} allowlist (by default the shell
 * working-directory variables {@code PWD}/{@code OLDPWD}). Callers decide how to apply the
 * allowlist - currently only the process-environment harvest consults it. With no record (null)
 * nothing is classified, so an absent config yields an inert matcher.</p>
 */
@ApplicationScoped
public class SecretNames {

    private final Set<String> keywords;
    private final List<String> qualifiers;
    private final Set<String> knownSafe;

    public SecretNames(final CodingAgentConfiguration configuration) {
        this(makeEnvironment(configuration));
    }

    private SecretNames(final SecretEnvironmentConfiguration environment) {
        this.keywords =
                environment == null || environment.keywords() == null ? Set.of() : Set.copyOf(environment.keywords());
        this.qualifiers = environment == null || environment.qualifiers() == null
                ? List.of()
                : List.copyOf(environment.qualifiers());
        this.knownSafe = environment == null || environment.knownSafeValues() == null
                ? Set.of()
                : Set.copyOf(environment.knownSafeValues());
    }

    private static SecretEnvironmentConfiguration makeEnvironment(final CodingAgentConfiguration configuration) {
        return configuration == null || configuration.secrets() == null
                ? null
                : configuration.secrets().environment();
    }

    protected SecretNames() {
        this.keywords = Set.of();
        this.qualifiers = List.of();
        this.knownSafe = Set.of();
    }

    /** Whether {@code name} is on the known-safe allowlist (exact, case-sensitive match). */
    public boolean isKnownSafeName(final String name) {
        return name != null && knownSafe.contains(name);
    }

    /** Whether {@code name} looks like a credential holder, purely by keyword/qualifier rules. */
    public boolean isSecretName(final String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        final var upper = name.toUpperCase(Locale.ROOT);
        final var upperNoSep = upper.replace("_", "").replace("-", "").replace(".", "");
        for (final var keyword : keywords) {
            if (upperNoSep.contains(keyword)) {
                return true;
            }
        }
        // qualifier+keyword markers (all driven by the configured sets, no hardcoded suffix literals):
        // API_KEY, CLIENT_SECRET, ACCESS_TOKEN, DB_PASSWORD, AUTH_TOKEN, ...
        for (final var q : qualifiers) {
            for (final var keyword : keywords) {
                if (upperNoSep.contains(q + keyword)) {
                    return true;
                }
            }
        }
        return false;
    }
}
