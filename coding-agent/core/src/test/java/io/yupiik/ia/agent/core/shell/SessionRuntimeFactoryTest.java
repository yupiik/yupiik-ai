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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.session.SessionStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for the stale session-project-marker fallback: {@link
 * SessionRuntimeFactory#resolveWorkdir} must never hand a dead directory (project moved or
 * deleted since the session was recorded) to the tools, but fall back to the configured working
 * directory so the session still gets a live cwd.
 */
class SessionRuntimeFactoryTest {

    @Nested
    @FusionSupport(modules = StaleRecordedMarkerFallsBackToTheConfiguredWorkdirScenarioModule.class)
    class StaleRecordedMarkerFallsBackToTheConfiguredWorkdirScenario {
        @TempDir
        static Path directory;

        @Test
        void staleRecordedMarkerFallsBackToTheConfiguredWorkdir(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var tmp = StaleRecordedMarkerFallsBackToTheConfiguredWorkdirScenario.directory;
            final var configDir = tmp.resolve("config");
            Files.createDirectories(configDir);
            final var missingMarker =
                    tmp.resolve("gone-project").toAbsolutePath().normalize().toString();

            final var config = configuration;
            final var store = storeRecording(missingMarker);
            final var factory = factory(config, store);

            assertEquals(
                    configDir.toAbsolutePath().normalize(),
                    factory.resolveWorkdir(null, "s1"),
                    "a recorded marker that no longer exists must fall back to the configured working dir");
        }
    }

    @Nested
    @FusionSupport(modules = LiveRecordedMarkerIsKeptScenarioModule.class)
    class LiveRecordedMarkerIsKeptScenario {
        @TempDir
        static Path directory;

        @Test
        void liveRecordedMarkerIsKept(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var tmp = LiveRecordedMarkerIsKeptScenario.directory;
            final var live = tmp.resolve("live");
            Files.createDirectories(live);
            final var configDir = tmp.resolve("config");
            Files.createDirectories(configDir);

            final var config = configuration;
            final var store = storeRecording(live.toAbsolutePath().normalize().toString());
            final var factory = factory(config, store);

            assertEquals(
                    live.toAbsolutePath().normalize(),
                    factory.resolveWorkdir(null, "s1"),
                    "a recorded marker that still exists must keep precedence over the configured dir");
        }
    }

    @Nested
    @FusionSupport(modules = NoRecordFallsBackToTheConfiguredWorkdirScenarioModule.class)
    class NoRecordFallsBackToTheConfiguredWorkdirScenario {
        @TempDir
        static Path directory;

        @Test
        void noRecordFallsBackToTheConfiguredWorkdir(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var tmp = NoRecordFallsBackToTheConfiguredWorkdirScenario.directory;
            final var configDir = tmp.resolve("config");
            Files.createDirectories(configDir);
            final var config = configuration;
            final var factory = factory(config, storeRecording(""));

            assertEquals(
                    configDir.toAbsolutePath().normalize(),
                    factory.resolveWorkdir(null, "s1"),
                    "without a marker the configured working dir must be used");
        }
    }

    @Nested
    @FusionSupport(modules = InvalidExplicitRootNeverFallsBackToAConfiguredDirectoryScenarioModule.class)
    class InvalidExplicitRootNeverFallsBackToAConfiguredDirectoryScenario {
        @TempDir
        static Path directory;

        @Test
        void invalidExplicitRootNeverFallsBackToAConfiguredDirectory(
                @Fusion final CodingAgentConfiguration configuration) {
            final var tmp = InvalidExplicitRootNeverFallsBackToAConfiguredDirectoryScenario.directory;
            final var factory = factory(configuration, storeRecording(tmp.toString()));
            assertThrows(IllegalArgumentException.class, () -> factory.resolveWorkdir(tmp.resolve("missing"), "s1"));
        }
    }

    private static SessionStore storeRecording(final String marker) {
        return new SessionStore() {
            @Override
            public String projectDirOf(final String id) {
                return marker;
            }
        };
    }

    private static SessionRuntimeFactory factory(final CodingAgentConfiguration config, final SessionStore store) {
        return new SessionRuntimeFactory(
                null, null, null, config, null, store, null, null, null, null, null, null, null, null, null, null, null,
                null, // backoff sleeper
                null, null, // memory production policy
                null, // quota store
                null, null, null);
    }

    public static class StaleRecordedMarkerFallsBackToTheConfiguredWorkdirScenarioModule
            extends ConfigurationTestModule {
        public StaleRecordedMarkerFallsBackToTheConfiguredWorkdirScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    StaleRecordedMarkerFallsBackToTheConfiguredWorkdirScenario.directory
                            .resolve("config")
                            .toString()));
        }
    }

    public static class LiveRecordedMarkerIsKeptScenarioModule extends ConfigurationTestModule {
        public LiveRecordedMarkerIsKeptScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    LiveRecordedMarkerIsKeptScenario.directory.resolve("config").toString()));
        }
    }

    public static class NoRecordFallsBackToTheConfiguredWorkdirScenarioModule extends ConfigurationTestModule {
        public NoRecordFallsBackToTheConfiguredWorkdirScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    NoRecordFallsBackToTheConfiguredWorkdirScenario.directory
                            .resolve("config")
                            .toString()));
        }
    }

    public static class InvalidExplicitRootNeverFallsBackToAConfiguredDirectoryScenarioModule
            extends ConfigurationTestModule {
        public InvalidExplicitRootNeverFallsBackToAConfiguredDirectoryScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    InvalidExplicitRootNeverFallsBackToAConfiguredDirectoryScenario.directory.toString()));
        }
    }
}
