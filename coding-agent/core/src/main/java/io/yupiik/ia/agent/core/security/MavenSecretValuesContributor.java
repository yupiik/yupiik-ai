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
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Harvests secret values from the Maven settings file ({@code yca.secrets.maven.settings}): the Maven
 * 4 / Maven 3 ciphertext strings and, when decryptable, the decrypted plaintexts (see
 * {@link MavenSecretService}).
 */
@ApplicationScoped
public class MavenSecretValuesContributor implements SecretValuesContributor {

    private static final Logger LOGGER = Logger.getLogger(MavenSecretValuesContributor.class.getName());

    private final CodingAgentConfiguration configuration;
    private final MavenSecretService mavenSecretService;

    public MavenSecretValuesContributor(
            final CodingAgentConfiguration configuration, final MavenSecretService mavenSecretService) {
        this.configuration = configuration;
        this.mavenSecretService = mavenSecretService;
    }

    @Override
    public Collection<String> values() {
        if (mavenSecretService == null || configuration == null || configuration.secrets() == null) {
            return List.of();
        }
        final var maven = configuration.secrets().maven();
        if (maven == null || maven.settings() == null || maven.settings().isBlank()) {
            return List.of();
        }
        final var settings = SecretPaths.resolve(configuration, maven.settings());
        if (settings == null || !Files.isRegularFile(settings)) {
            return List.of();
        }
        try {
            return mavenSecretService.collectSecretNames(settings);
        } catch (final RuntimeException e) {
            LOGGER.log(Level.FINE, () -> "Cannot harvest Maven settings " + settings + ": " + e.getMessage());
            return List.of();
        }
    }
}
