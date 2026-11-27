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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.impl.MapConfigSource;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationImpl;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.SandboxConfigurationInitializer;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = ConfigurationTestModule.class)
class SandboxInitCommandTest {
    @Fusion
    CodingAgentConfiguration injectedConfiguration;

    @Test
    void previewsThenAppliesOnlyToTheGlobalLocation(@TempDir final Path temp) throws Exception {
        final var path = temp.resolve("global/configuration.json");
        final var configuration = new ConfigurationImpl(List.of(new MapConfigSource(
                Map.of("yca.configuration.json", path.toString(), "yca.workingDir", temp.toString()))));
        try (final var mapper = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            final var initializer = new SandboxConfigurationInitializer(mapper, injectedConfiguration);
            new SandboxInitCommand(new SandboxInitCommand.Options(false), configuration, initializer).run();
            assertFalse(Files.exists(path.getParent()));
            new SandboxInitCommand(new SandboxInitCommand.Options(true), configuration, initializer).run();
            assertTrue(Files.isRegularFile(path));
            assertFalse(Files.exists(temp.resolve(".yca/configuration.json")));
        }
    }

    @Test
    void exposesNestedCommandPathAndDocumentsTheSandboxAlias() {
        try (final var container = ConfiguringContainer.of()
                        .register(new FusionModule() {
                            @Override
                            public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                                return (runtime, bean) -> bean.type() instanceof ParameterizedType type
                                        && type.getRawType() == CliCommand.class;
                            }

                            @Override
                            public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                                return (runtime, listener) -> false;
                            }
                        })
                        .start();
                final var discovered = container.lookups(CliCommand.class)) {
            final var command = discovered.instance().stream()
                    .<CliCommand<?>>map(it -> it)
                    .filter(it -> List.of(it.path()).equals(List.of("sandbox", "init")))
                    .findFirst()
                    .orElseThrow();
            assertTrue(command.description().contains("--sandbox true|false"));
            assertEquals(1, command.parameters().size());
            assertTrue(command.parameters().getFirst().description().contains("read-only preview"));
        }
    }
}
