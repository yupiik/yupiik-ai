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

import java.util.Collection;

/**
 * SPI for sources of exact secret values harvested at scan time. Implementations are injected into
 * the {@link SecretRegistry} as {@code List<SecretValuesContributor>} (fusion aggregates every
 * {@code @ApplicationScoped} bean of this type) and each contributes the local secret values it
 * knows (environment variables, env files, Maven settings, explicit known values, ...). This keeps
 * {@link SecretRegistry#scan()} generic - it collects from contributors instead of hardcoding each
 * source - and lets custom deployments add bespoke secret sources without touching core.
 */
public interface SecretValuesContributor {

    /**
     * @return the exact secret values this contributor knows. Values are de-duplicated and
     *     min-length filtered by the registry; callers must not emit secrets to logs.
     */
    Collection<String> values();
}
