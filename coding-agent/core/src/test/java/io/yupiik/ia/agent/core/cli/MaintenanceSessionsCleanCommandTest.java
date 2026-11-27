/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.session.FileSessionStore;
import io.yupiik.ia.agent.core.session.SessionCleanPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaintenanceSessionsCleanCommandTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void registersNestedCommandAndExactDashedOptions(@TempDir final Path temp) throws Exception {
        withCommand(temp, Map.of(), (configuration, store, command) -> {
            assertEquals("maintenance/sessions/clean", command.name());
            assertEquals(List.of("maintenance", "sessions", "clean"), List.of(command.path()));
            assertEquals(
                    List.of("--bigger-than", "--dry-run", "--older-than"),
                    command.parameters().stream()
                            .map(CliCommand.Parameter::cliName)
                            .sorted()
                            .toList());
            for (final var parameter : command.parameters()) {
                assertEquals("--" + parameter.configName(), parameter.cliName());
                assertEquals(parameter.cliName(), command.keyMapper().apply(parameter.configName()));
                assertFalse(parameter.description().isBlank());
            }
            assertEquals(
                    "false",
                    command.parameters().stream()
                            .filter(parameter -> "--dry-run".equals(parameter.cliName()))
                            .findFirst()
                            .orElseThrow()
                            .defaultValue());
        });
    }

    @Test
    void generatedOptionsMapBothCriteriaAndDryRun(@TempDir final Path temp) throws Exception {
        withCommand(temp, Map.of(), (configuration, store, command) -> {
            final var selected = session(store, "selected", Duration.ofDays(4), 101);
            final var ageBoundary = session(store, "age-boundary", Duration.ofDays(3), 101);
            final var sizeBoundary = session(store, "size-boundary", Duration.ofDays(4), 100);
            final var tooYoung = session(store, "too-young", Duration.ofDays(2), 101);
            final var tooSmall = session(store, "too-small", Duration.ofDays(4), 99);
            final var options = Map.of("--older-than", "3d", "--bigger-than", "100B", "--dry-run", "true");

            execute(command, options);

            assertEquals(101, Files.size(selected));
            assertEquals("x".repeat(101), Files.readString(selected));
            assertEquals(
                    FileTime.fromMillis(CLOCK.millis() - Duration.ofDays(4).toMillis()),
                    Files.getLastModifiedTime(selected));
            for (final var file : List.of(ageBoundary, sizeBoundary, tooYoung, tooSmall)) {
                assertTrue(Files.isRegularFile(file));
            }

            execute(command, Map.of("--older-than", "3d", "--bigger-than", "100B", "--dry-run", "false"));

            assertFalse(Files.exists(selected));
            assertFalse(Files.exists(selected.getParent()));
            for (final var file : List.of(ageBoundary, sizeBoundary, tooYoung, tooSmall)) {
                assertTrue(Files.isRegularFile(file));
            }
        });
    }

    @Test
    void ageOnlyCliCriteriaReplaceConfiguredSizeAsASet(@TempDir final Path temp) throws Exception {
        withCommand(
                temp,
                Map.of("yca.sessions.clean.retentionDays", "30", "yca.sessions.clean.biggerThan", "1M"),
                (configuration, store, command) -> {
                    final var oldSmall = session(store, "old-small", Duration.ofDays(4), 1);
                    final var youngLarge = session(store, "young-large", Duration.ofDays(2), 1000001);

                    execute(command, Map.of("--older-than", "3d"));

                    assertFalse(Files.exists(oldSmall));
                    assertTrue(Files.isRegularFile(youngLarge));
                });
    }

    @Test
    void sizeOnlyCliCriteriaReplaceConfiguredAgeAsASet(@TempDir final Path temp) throws Exception {
        withCommand(
                temp,
                Map.of("yca.sessions.clean.retentionDays", "30", "yca.sessions.clean.biggerThan", "1M"),
                (configuration, store, command) -> {
                    final var youngLarge = session(store, "young-large", Duration.ofDays(2), 101);
                    final var oldSmall = session(store, "old-small", Duration.ofDays(40), 100);

                    execute(command, Map.of("--bigger-than", "100B"));

                    assertFalse(Files.exists(youngLarge));
                    assertTrue(Files.isRegularFile(oldSmall));
                });
    }

    @Test
    void noCliCriteriaUseBothConfiguredCriteria(@TempDir final Path temp) throws Exception {
        withCommand(
                temp,
                Map.of("yca.sessions.clean.retentionDays", "3", "yca.sessions.clean.biggerThan", "100B"),
                (configuration, store, command) -> {
                    assertEquals(
                            new SessionCleanPolicy(Duration.ofDays(3), 100L),
                            SessionCleanPolicy.resolve(configuration.sessions().clean(), null, null));
                    final var selected = session(store, "selected", Duration.ofDays(4), 101);
                    final var oldSmall = session(store, "old-small", Duration.ofDays(4), 100);
                    final var youngLarge = session(store, "young-large", Duration.ofDays(2), 101);

                    execute(command, Map.of());

                    assertFalse(Files.exists(selected));
                    assertTrue(Files.isRegularFile(oldSmall));
                    assertTrue(Files.isRegularFile(youngLarge));
                });
    }

    @Test
    void disabledConfigurationDoesNotDeleteAnything(@TempDir final Path temp) throws Exception {
        withCommand(temp, Map.of("yca.sessions.clean.retentionDays", "0"), (configuration, store, command) -> {
            final var old = session(store, "old", Duration.ofDays(40), 1024);

            execute(command, Map.of());

            assertTrue(Files.isRegularFile(old));
            assertEquals(1024, Files.size(old));
        });
    }

    @Test
    void enabledCriteriaWithNoMatchKeepAllFiles(@TempDir final Path temp) throws Exception {
        withCommand(temp, Map.of(), (configuration, store, command) -> {
            final var oldSmall = session(store, "old-small", Duration.ofDays(40), 100);
            final var youngLarge = session(store, "young-large", Duration.ofDays(2), 101);

            execute(command, Map.of("--older-than", "3d", "--bigger-than", "100B"));

            assertTrue(Files.isRegularFile(oldSmall));
            assertTrue(Files.isRegularFile(youngLarge));
        });
    }

    @Test
    void invalidCliCriteriaDoNotFallBackToDefaultsOrDeleteFiles(@TempDir final Path temp) throws Exception {
        withCommand(temp, Map.of(), (configuration, store, command) -> {
            final var old = session(store, "old", Duration.ofDays(40), 101);
            for (final var options : List.of(
                    Map.of("--older-than", ""), Map.of("--older-than", "0d"),
                    Map.of("--bigger-than", "invalid"), Map.of("--bigger-than", "9223372036854775807T"))) {
                assertThrows(IllegalArgumentException.class, () -> execute(command, options));
                assertTrue(Files.isRegularFile(old));
            }
        });
    }

    @Test
    void oldRetentionKeyIsIgnoredAndDefaultRemainsFifteenDays(@TempDir final Path temp) throws Exception {
        withCommand(temp, Map.of("yca.sessions.retentionDays", "1"), (configuration, store, command) -> {
            assertEquals(15, configuration.sessions().clean().retentionDays());
            assertEquals(
                    new SessionCleanPolicy(Duration.ofDays(15), null),
                    SessionCleanPolicy.resolve(configuration.sessions().clean(), null, null));
            final var withinDefault = session(store, "within-default", Duration.ofDays(3), 1);
            final var boundary = session(store, "boundary", Duration.ofDays(15), 1);
            final var expired = session(store, "expired", Duration.ofDays(16), 1);

            execute(command, Map.of());

            assertTrue(Files.isRegularFile(withinDefault));
            assertTrue(Files.isRegularFile(boundary));
            assertFalse(Files.exists(expired));
        });
    }

    @Test
    void movedRetentionKeyWinsEvenWhenOldKeyIsPresent(@TempDir final Path temp) throws Exception {
        withCommand(
                temp,
                Map.of("yca.sessions.retentionDays", "1", "yca.sessions.clean.retentionDays", "3"),
                (configuration, store, command) -> {
                    assertEquals(3, configuration.sessions().clean().retentionDays());
                    final var boundary = session(store, "boundary", Duration.ofDays(3), 1);
                    final var expired = session(store, "expired", Duration.ofDays(4), 1);

                    execute(command, Map.of());

                    assertTrue(Files.isRegularFile(boundary));
                    assertFalse(Files.exists(expired));
                });
    }

    @Test
    void isolatedContainerDoesNotRunStartupCleanup(@TempDir final Path temp) throws Exception {
        final var existing = temp.resolve("sessions/existing/existing.jsonl");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "existing session");
        Files.setLastModifiedTime(
                existing,
                FileTime.fromMillis(CLOCK.millis() - Duration.ofDays(40).toMillis()));

        withCommand(temp, Map.of(), (configuration, store, command) -> {
            assertTrue(configuration.sessions().startupCleanup());
            assertTrue(Files.isRegularFile(existing));

            execute(command, Map.of("--dry-run", "true"));

            assertEquals("existing session", Files.readString(existing));
        });
    }

    @Test
    void normalStartupCanDeleteBeforeExplicitDryRun(@TempDir final Path temp) throws Exception {
        withCommand(temp, Map.of("yca.sessions.compressDays", "0"), (configuration, store, command) -> {
            final var expired = session(store, "expired", Duration.ofDays(40), 101);
            final var latest = session(store, "latest", Duration.ofDays(2), 101);

            store.onStart(null);
            execute(command, Map.of("--older-than", "90d", "--dry-run", "true"));

            assertFalse(Files.exists(expired));
            assertTrue(Files.exists(latest));
        });
    }

    private static Path session(final FileSessionStore store, final String id, final Duration age, final int bytes)
            throws Exception {
        final var file = store.path(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x".repeat(bytes));
        Files.setLastModifiedTime(file, FileTime.fromMillis(CLOCK.millis() - age.toMillis()));
        return file;
    }

    private static void execute(final CliCommand<?> command, final Map<String, String> options) {
        final Configuration configuration =
                key -> Optional.ofNullable(options.get(command.keyMapper().apply(key)));
        try (final var instance = command.create(configuration, new ArrayList<>())) {
            instance.instance().run();
        }
    }

    private static void withCommand(final Path temp, final Map<String, String> overrides, final Scenario scenario)
            throws Exception {
        final var values = new HashMap<>(overrides);
        values.put("yca.storageHome", temp.toString());
        values.put("yca.workingDir", temp.toString());
        try (final var mapper = new JsonMapperImpl(List.of(), ignored -> Optional.empty());
                final var container = ConfiguringContainer.of()
                        .register(new IsolatedModule(values, mapper))
                        .start();
                final var configuration = container.lookup(CodingAgentConfiguration.class);
                final var store = container.lookup(FileSessionStore.class);
                final var discovered = container.lookups(CliCommand.class)) {
            final var commands = discovered.instance().stream()
                    .<CliCommand<?>>map(it -> it)
                    .filter(it -> List.of(it.path()).equals(List.of("maintenance", "sessions", "clean")))
                    .toList();
            assertEquals(1, commands.size());
            scenario.run(configuration.instance(), store.instance(), commands.getFirst());
        }
    }

    @FunctionalInterface
    private interface Scenario {
        void run(CodingAgentConfiguration configuration, FileSessionStore store, CliCommand<?> command)
                throws Exception;
    }

    private static class IsolatedModule implements FusionModule {
        private final Map<String, String> values;
        private final JsonMapper mapper;

        private IsolatedModule(final Map<String, String> values, final JsonMapper mapper) {
            this.values = Map.copyOf(values);
            this.mapper = mapper;
        }

        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.of(
                    new ProvidedInstanceBean<>(DefaultScoped.class, ConfigurationSource.class, () -> values::get),
                    new ProvidedInstanceBean<>(DefaultScoped.class, JsonMapper.class, () -> mapper),
                    new ProvidedInstanceBean<>(DefaultScoped.class, Clock.class, () -> CLOCK));
        }

        @Override
        public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
            return (container, bean) -> bean instanceof ProvidedInstanceBean<?>
                    || !(bean.type() instanceof Class<?> type)
                    || (!ConfigurationSource.class.isAssignableFrom(type)
                            && !Clock.class.isAssignableFrom(type)
                            && !JsonMapper.class.isAssignableFrom(type));
        }

        @Override
        public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
            return (container, listener) -> false;
        }
    }
}
