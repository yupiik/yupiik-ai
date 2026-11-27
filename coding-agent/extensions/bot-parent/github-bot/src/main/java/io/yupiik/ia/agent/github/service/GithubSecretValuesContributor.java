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
package io.yupiik.ia.agent.github.service;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.security.SecretValuesContributor;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;

/** Supplies bot credentials to the shared secret registry, independently of environment-name heuristics. */
@ApplicationScoped
public class GithubSecretValuesContributor implements SecretValuesContributor {
    private final GithubBotConfiguration conf;
    private final Configuration configuration;

    public GithubSecretValuesContributor(final GithubBotConfiguration conf, final Configuration configuration) {
        this.conf = conf;
        this.configuration = configuration;
    }

    @Override
    public Collection<String> values() {
        final var values = new ArrayList<String>();
        addConfiguredValue(conf.auth().tokenEnv(), values);
        addConfiguredValue(conf.auth().agentTokenEnv(), values);
        if (conf.auth().privateKeyFile() != null
                && !conf.auth().privateKeyFile().isBlank()) {
            try {
                final var path = Path.of(conf.auth().privateKeyFile());
                if (Files.isRegularFile(path)) {
                    final var content = Files.readString(path);
                    if (!content.isBlank()) {
                        values.add(content);
                        // Host authentication trims the PEM before using it.
                        final var trimmed = content.trim();
                        if (!trimmed.equals(content)) {
                            values.add(trimmed);
                        }
                    }
                }
            } catch (final IOException | RuntimeException ignored) {
                // Best-effort harvesting: neither paths, contents nor exception messages are logged.
            }
        }
        return values;
    }

    private void addConfiguredValue(final String name, final Collection<String> values) {
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            final var value = configuration.get(name).orElse(null);
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        } catch (final RuntimeException ignored) {
            // Do not let an inaccessible source prevent harvesting the remaining credentials.
        }
    }
}
