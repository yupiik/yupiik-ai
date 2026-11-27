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
package io.yupiik.ia.agent.core.wiring;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;

/**
 * Chains two {@link ConfigurationSource}s with first-wins-non-null semantics: a requested key is
 * resolved from {@code first} when present, otherwise from {@code second}.
 *
 * <p>This backs the project-local {@code .yca/configuration.json} {@code $inherit} feature: the
 * project file is the {@code first} source and, when it declares {@code "$inherit": true}, the
 * global configuration file acts as a low-priority {@code second} source so keys the project file
 * does not define fall through to the inherited base. Because Fusion resolves a key from the first
 * source that carries it, per-leaf override, per-field inheritance on list elements and list
 * truncation via {@code length} all follow from plain ordering with no merge logic here.</p>
 *
 * <p>The {@code second} source is intentionally a plain source (no recursion): inheritance is
 * single-level only, which keeps the semantics predictable and avoids inheritance cycles.</p>
 */
public class CombinedConfigurationSource implements ConfigurationSource {
    private final ConfigurationSource first;
    private final ConfigurationSource second;

    public CombinedConfigurationSource(final ConfigurationSource first, final ConfigurationSource second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public String get(final String key) {
        final var value = first.get(key);
        return value == null ? second.get(key) : value;
    }
}
