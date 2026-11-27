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
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;

/**
 * Convenience wrapper around the {@link ArgsConfigSource} that lets CLI arguments drop the
 * {@code yca.} prefix and use dashes instead of dots: {@code --yca.storage.home},
 * {@code --yca.provider.model}, ... can be written {@code --storage-home}, {@code --provider-model},
 * {@code --storage.home}, {@code --provider.model}, ... without any transformation elsewhere.
 *
 * <p>For any requested {@code yca.<key>} it resolves the value from the wrapped args source with, in
 * order: the dotted stripped form ({@code <key>}, from {@code --<key>}), the dashed stripped form
 * ({@code <key>} with {@code .} replaced by {@code -}, from {@code --<key-with-dashes>}), then the
 * prefixed form ({@code yca.<key>}, from {@code --yca.<key>}). Keys outside the {@code yca.}
 * namespace are delegated unchanged.</p>
 */
@DefaultScoped
public class YcaPrefixShortcutConfigurationSource implements ConfigurationSource {
    private static final String PREFIX = "yca.";

    private final ArgsConfigSource delegate;

    public YcaPrefixShortcutConfigurationSource(final ArgsConfigSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public String get(final String key) {
        if (key != null && key.startsWith(PREFIX)) {
            final var stripped = key.substring(PREFIX.length());
            final var strippedValue = delegate.get(stripped);
            if (strippedValue != null) {
                return strippedValue;
            }
            final var dashed = stripped.replace('.', '-');
            if (!dashed.equals(stripped)) {
                final var dashedValue = delegate.get(dashed);
                if (dashedValue != null) {
                    return dashedValue;
                }
            }
            return delegate.get(key);
        }
        return delegate.get(key);
    }
}
