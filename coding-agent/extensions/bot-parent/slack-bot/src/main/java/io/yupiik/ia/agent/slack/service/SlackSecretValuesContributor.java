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
package io.yupiik.ia.agent.slack.service;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.security.SecretValuesContributor;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import java.util.ArrayList;
import java.util.Collection;

/** Supplies configured credentials independently of environment-name heuristics. */
@ApplicationScoped
public class SlackSecretValuesContributor implements SecretValuesContributor {
    private final SlackConfiguration conf;
    private final Configuration configuration;

    public SlackSecretValuesContributor(final SlackConfiguration conf, final Configuration configuration) {
        this.conf = conf;
        this.configuration = configuration;
    }

    @Override
    public Collection<String> values() {
        final var values = new ArrayList<String>();
        addConfiguredValue(conf.appTokenEnv(), values);
        addConfiguredValue(conf.botTokenEnv(), values);
        addConfiguredValue("OPENAI_API_KEY", values);
        final var passwordEnv = configuredValue("slack.executor.docker.services.keyStorePasswordEnv");
        addConfiguredValue(passwordEnv == null ? "YCA_SERVICES_KEYSTORE_PASSWORD" : passwordEnv, values);
        // Kubernetes always uses this fixed name, even when Docker overrides it.
        addConfiguredValue("YCA_SERVICES_KEYSTORE_PASSWORD", values);
        return values;
    }

    private void addConfiguredValue(final String name, final Collection<String> values) {
        final var value = configuredValue(name);
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private String configuredValue(final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return configuration.get(name).orElse(null);
        } catch (final RuntimeException ignored) {
            // Best effort per lookup: never log names, values or exception details.
            return null;
        }
    }
}
