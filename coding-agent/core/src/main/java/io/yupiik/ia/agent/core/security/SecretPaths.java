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

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.nio.file.Path;

/**
 * Resolves a configured relative file path (env file, Maven settings) against the process working
 * directory, expanding a leading {@code ~/} to the user home. Shared by the {@link
 * SecretValuesContributor}s so path handling is not duplicated.
 */
public final class SecretPaths {

    private SecretPaths() {}

    public static Path resolve(final CodingAgentConfiguration configuration, final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final var p = Path.of(CodingAgentConfiguration.expandHome(value));
        if (p.isAbsolute()) {
            return p.normalize();
        }
        final var base = configuration == null
                ? Path.of(".").toAbsolutePath().normalize()
                : configuration.resolveWorkingDir().toAbsolutePath().normalize();
        return base.resolve(p).normalize();
    }
}
