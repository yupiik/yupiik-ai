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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.lang.reflect.ParameterizedType;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class TuiSandboxBindingTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"true", "false", "typo"})
    void bindsNullableSandboxOverrideAndExposesHelp(final String sandbox) {
        final Map<String, String> values = sandbox == null ? Map.of() : Map.of("tui.sandbox", sandbox);
        try (final var container = ConfiguringContainer.of()
                        .register(new ProvidedInstanceBean<>(
                                DefaultScoped.class,
                                Configuration.class,
                                () -> key -> Optional.ofNullable(values.get(key))))
                        .register(new FusionModule() {
                            @Override
                            public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                                return (runtime, bean) -> bean instanceof ProvidedInstanceBean<?>
                                        || bean.type() == TuiCommand.TuiConf.class
                                        || bean.type() instanceof ParameterizedType type
                                                && type.getRawType() == CliCommand.class;
                            }

                            @Override
                            public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                                return (runtime, listener) -> false;
                            }
                        })
                        .start();
                final var conf = container.lookup(TuiCommand.TuiConf.class);
                final var commands = container.lookups(CliCommand.class)) {
            final Boolean expected = sandbox == null ? null : Boolean.parseBoolean(sandbox);
            assertEquals(expected, conf.instance().sandbox());
            final var command = commands.instance().stream()
                    .<CliCommand<?>>map(it -> it)
                    .filter(it -> "tui".equals(it.name()))
                    .findFirst()
                    .orElseThrow();
            final var parameter = command.parameters().stream()
                    .filter(it -> "tui.sandbox".equals(it.configName()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("null", parameter.defaultValue());
            assertEquals("--tui-sandbox", parameter.cliName());
            assertEquals(parameter.cliName(), command.keyMapper().apply(parameter.configName()));
            assertTrue(parameter.description().contains("yca.sandbox.enabled"));
        }
    }

    @Test
    void convenienceConstructorInheritsSandboxConfiguration() {
        assertNull(new TuiCommand.TuiConf("", "", KeyBindingPreset.STANDARD, false, "dark").sandbox());
    }
}
