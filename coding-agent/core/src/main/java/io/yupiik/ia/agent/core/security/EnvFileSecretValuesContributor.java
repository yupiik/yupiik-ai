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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Harvests secret values from the configured env/config files ({@code yca.secrets.envFiles}, default
 * {@code .env}, {@code .env.local}, {@code .env.production}): for each {@code KEY=VALUE} / {@code KEY:VALUE}
 * line whose KEY is secret-ish, the VALUE is a secret.
 */
@ApplicationScoped
public class EnvFileSecretValuesContributor implements SecretValuesContributor {

    private final CodingAgentConfiguration configuration;
    private final List<String> envFiles;
    private final SecretNames names;

    public EnvFileSecretValuesContributor(final CodingAgentConfiguration configuration, final SecretNames names) {
        this.configuration = configuration;
        this.names = names;
        this.envFiles = configuration == null || configuration.secrets() == null
                ? List.of()
                : configuration.secrets().envFiles();
    }

    @Override
    public Collection<String> values() {
        if (names == null) {
            return List.of();
        }
        final var out = new ArrayList<String>();
        final var parser = new ValueHarvester(names);
        for (final var file : envFiles) {
            final var path = SecretPaths.resolve(configuration, file);
            if (path != null && Files.isRegularFile(path)) {
                harvestFile(path, out, parser);
            }
        }
        return out;
    }

    private static void harvestFile(final Path path, final Collection<String> out, final ValueHarvester parser) {
        try (final var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                final var value = parser.value(line);
                if (value != null && !value.isBlank()) {
                    out.add(value);
                }
            });
        } catch (final IOException e) {
            // unreadable env file is skipped silently
        }
    }

    /**
     * Tiny line parser for {@code KEY=VALUE} / {@code KEY: VALUE}. Only lines whose key name is
     * secret-ish yield a harvested value (so non-secret config lines are skipped).
     */
    static final class ValueHarvester {
        private static final Pattern ASSIGN = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*(?:=|:|=>)\\s*(.*)$");

        private final SecretNames names;

        private ValueHarvester(final SecretNames names) {
            this.names = names;
        }

        String value(final String line) {
            if (line == null || line.isBlank() || line.strip().startsWith("#")) {
                return null;
            }
            final var m = ASSIGN.matcher(line);
            if (!m.matches()) {
                return null; // not an assignment: nothing to infer a value's relevance from
            }
            final var name = m.group(1);
            if (!names.isSecretName(name)) {
                return null;
            }
            var raw = m.group(2).trim();
            if ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'"))) {
                raw = raw.substring(1, raw.length() - 1);
            }
            return raw;
        }
    }
}
