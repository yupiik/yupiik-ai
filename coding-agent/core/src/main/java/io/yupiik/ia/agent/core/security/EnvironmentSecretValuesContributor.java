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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Harvests secret values from the process environment ({@link System#getenv()}) when
 * {@code yca.secrets.environment.harvestEnvironment} is on: the VALUE of every variable whose NAME is
 * secret-ish (see {@link SecretNames}) and not on the known-safe allowlist (default {@code PWD}/
 * {@code OLDPWD}) is a secret.
 */
@ApplicationScoped
public class EnvironmentSecretValuesContributor implements SecretValuesContributor {

    private final boolean enabled;
    private final SecretNames names;

    public EnvironmentSecretValuesContributor(final CodingAgentConfiguration configuration, final SecretNames names) {
        this.names = names;
        this.enabled = configuration == null
                || configuration.secrets() == null
                || configuration.secrets().environment() == null
                || configuration.secrets().environment().harvestEnvironment();
    }

    protected EnvironmentSecretValuesContributor() {
        this.enabled = false;
        this.names = null;
    }

    @Override
    public Collection<String> values() {
        if (!enabled) {
            return List.of();
        }
        final var out = new ArrayList<String>();
        for (final var e : System.getenv().entrySet()) {
            final var key = e.getKey();
            if (!names.isKnownSafeName(key)
                    && names.isSecretName(key)
                    && e.getValue() != null
                    && !e.getValue().isBlank()) {
                out.add(e.getValue());
            }
        }
        return out;
    }
}
