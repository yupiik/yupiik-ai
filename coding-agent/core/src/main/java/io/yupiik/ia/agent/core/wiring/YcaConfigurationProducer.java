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

import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;

/**
 * Registers the CLI {@link ArgsConfigSource} prefix shortcut and the lazily-loaded global/project
 * {@code .yca} JSON {@link io.yupiik.fusion.framework.api.configuration.ConfigurationSource}.
 */
@DefaultScoped
public class YcaConfigurationProducer {
    private final ArgsConfigSource args;

    public YcaConfigurationProducer(final ArgsConfigSource args) {
        this.args = args;
    }

    public void register(@OnEvent @Order(Integer.MIN_VALUE + 1) final ConfigurationRegistration registration) {
        // first the CLI args (highest priority), wrapped so --provider-model etc. work as --yca-provider-model
        registration.addSource().accept(new YcaPrefixShortcutConfigurationSource(args));

        // The global/project .yca JSON is loaded lazily on first access: the captured registration
        // Configuration is the live one every bean reads from, so by the time a key is requested it is
        // fully settled (all sources registered) and the file locations are resolved through it instead
        // of raw System.* reads. A building flag prevents the internal path lookups from re-entering this
        // source's own build (no recursion / stack overflow).
        registration.addSource().accept(new LazyJsonConfigurationSource(registration.configuration()));
    }
}
