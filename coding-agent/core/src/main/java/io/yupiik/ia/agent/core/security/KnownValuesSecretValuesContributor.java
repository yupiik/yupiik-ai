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
import java.util.Collection;
import java.util.List;

/**
 * Harvests the explicitly declared secret values from {@code yca.secrets.knownValues}.
 */
@ApplicationScoped
public class KnownValuesSecretValuesContributor implements SecretValuesContributor {

    private final List<String> knownValues;

    public KnownValuesSecretValuesContributor(final CodingAgentConfiguration configuration) {
        this.knownValues = configuration == null || configuration.secrets() == null
                ? List.of()
                : configuration.secrets().knownValues();
    }

    @Override
    public Collection<String> values() {
        return knownValues == null ? List.of() : knownValues;
    }
}
