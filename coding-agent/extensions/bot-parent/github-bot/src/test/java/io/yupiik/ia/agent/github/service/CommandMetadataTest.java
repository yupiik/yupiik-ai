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
package io.yupiik.ia.agent.github.service;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CommandMetadataTest {
    @Test
    void exposesSeparatePathsAndOnlyOperationSpecificOptions() {
        try (final var container = ConfiguringContainer.of()
                        .register(new FusionModule() {
                            @Override
                            public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                                return (container, bean) -> bean.type() instanceof ParameterizedType type
                                        && type.getRawType() == CliCommand.class;
                            }

                            @Override
                            public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                                return (container, listener) -> false;
                            }
                        })
                        .start();
                final var discovered = container.lookups(CliCommand.class)) {
            final var paths =
                    List.of("poll", "list", "show", "create", "retry", "delete", "purge", "worker", "cleanup");
            final var byPath = discovered.instance().stream()
                    .<CliCommand<?>>map(command -> command)
                    .filter(command -> command.path().length == 3
                            && "github".equals(command.path()[0])
                            && "bot".equals(command.path()[1]))
                    .collect(Collectors.toMap(command -> command.path()[2], command -> command));
            assertEquals(Set.copyOf(paths), byPath.keySet());
            final var commands = paths.stream().map(byPath::get).toList();
            assertEquals(
                    List.of("poll", "list", "show", "create", "retry", "delete", "purge", "worker", "cleanup"),
                    commands.stream().map(command -> command.path()[2]).toList());
            for (final var command : commands) {
                assertEquals("github", command.path()[0]);
                assertEquals("bot", command.path()[1]);
                assertEquals(3, command.path().length);
                assertFalse(command.parameters().stream()
                        .anyMatch(parameter -> parameter.cliName().equals("--action")));
                assertTrue(command.parameters().stream()
                        .noneMatch(parameter -> parameter.cliName().startsWith("--bot-")));
                assertTrue(command.parameters().stream()
                        .allMatch(parameter -> !parameter.description().isBlank()));
            }
            final var list = commands.get(1).parameters().stream()
                    .map(CliCommand.Parameter::cliName)
                    .toList();
            assertEquals(List.of("--filterKind", "--filterRepository", "--filterState"), list);
            assertEquals(
                    List.of("--diagnostics", "--task"),
                    commands.get(2).parameters().stream()
                            .map(CliCommand.Parameter::cliName)
                            .toList());
            assertTrue(commands.get(0).parameters().stream()
                    .anyMatch(parameter -> parameter.cliName().equals("--poll-pageBudget")));
            assertTrue(commands.get(7).parameters().stream()
                    .anyMatch(parameter -> parameter.cliName().equals("--worker-maxRetries")));
            for (final var index : List.of(0, 7)) {
                assertFalse(commands.get(index).parameters().stream()
                        .anyMatch(parameter -> parameter.cliName().equals("--dryRun")));
            }
            assertTrue(commands.get(6).parameters().stream()
                    .anyMatch(parameter -> parameter.cliName().equals("--retentionDays")));
            assertTrue(commands.get(6).parameters().stream()
                    .anyMatch(parameter -> parameter.cliName().equals("--dryRun")));
        }
    }
}
