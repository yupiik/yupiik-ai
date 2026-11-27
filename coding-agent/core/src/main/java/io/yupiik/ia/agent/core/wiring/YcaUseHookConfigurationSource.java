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
import io.yupiik.fusion.framework.api.scope.DefaultScoped;

/**
 * Forces {@code fusion.launcher.useHook} to {@code true} so the fusion
 * {@code Launcher}/{@code CliLauncher} registers its JVM shutdown hook and
 * gracefully closes the container (and therefore every {@code AutoCloseable}
 * bean) on SIGINT.
 */
@DefaultScoped
public class YcaUseHookConfigurationSource implements ConfigurationSource {
    @Override
    public String get(final String key) {
        return "fusion.launcher.useHook".equals(key) ? "true" : null;
    }
}
