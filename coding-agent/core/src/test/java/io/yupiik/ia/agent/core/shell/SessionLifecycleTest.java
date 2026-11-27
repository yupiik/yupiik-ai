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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandOptionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandPermissionConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandStartMatcherConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.FileSessionStore;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class SessionLifecycleTest {
    private CodingAgentConfiguration configuration;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
        dir = Path.of(configuration.storageHome());
    }

    @TempDir
    private static Path testWorkdir;

    private Path dir;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private SessionStore newStore() {
        return new FileSessionStore(configuration, mapper, Clock.systemUTC());
    }

    private SessionLifecycle newLifecycle(final SessionStore store) {
        return new SessionLifecycle(store);
    }

    private SessionRuntime newRuntime() {
        return new SessionRuntime(
                null,
                new PermissionService(configuration, (configuration).resolveWorkingDir()),
                new UserFeedbackService(),
                null,
                null,
                null,
                null,
                null,
                testWorkdir,
                new DisabledSandbox(testWorkdir));
    }

    @Nested
    @FusionSupport(modules = ResolvesSessionThenContextThenCreatesScenarioModule.class)
    class ResolvesSessionThenContextThenCreatesScenario {
        @TempDir
        static Path directory;

        @Test
        void resolvesSessionThenContextThenCreates() {
            final var store = newStore();
            final var lifecycle = newLifecycle(store);

            assertEquals("s1", lifecycle.resolveSessionId("s1", "ignored.jsonl"));

            final var fromContext = lifecycle.resolveSessionId(" ", "some/dir/bar.jsonl");
            assertEquals("bar", fromContext);

            final var created = lifecycle.resolveSessionId(null, null);
            assertFalse(created == null || created.isBlank());
            assertNotEquals("s1", created);
        }
    }

    @Nested
    @FusionSupport(modules = PrepareContextFileCreatesMissingFileAndSkipsBlankScenarioModule.class)
    class PrepareContextFileCreatesMissingFileAndSkipsBlankScenario {
        @TempDir
        static Path directory;

        @Test
        void prepareContextFileCreatesMissingFileAndSkipsBlank() throws Exception {
            final var lifecycle = newLifecycle(newStore());

            final var target = dir.resolve("nested").resolve("ctx.jsonl");
            lifecycle.prepareContextFile(target, "custom.jsonl");
            assertTrue(Files.isRegularFile(target));
            assertEquals("", Files.readString(target));

            final var untouched = dir.resolve("never.jsonl");
            lifecycle.prepareContextFile(untouched, " ");
            assertFalse(Files.exists(untouched));
        }
    }

    @Nested
    @FusionSupport(modules = InstallAppliesGatesAndReleaseKeepsNonEmptySessionScenarioModule.class)
    class InstallAppliesGatesAndReleaseKeepsNonEmptySessionScenario {
        @TempDir
        static Path directory;

        @Test
        void installAppliesGatesAndReleaseKeepsNonEmptySession() {
            final var store = newStore();
            final var lifecycle = newLifecycle(store);
            final ApprovalGate approval = request -> ApprovalGate.Result.DENY;
            final FeedbackGate feedback = questions -> Map.of();

            final var runtime = newRuntime();
            runtime.permissions()
                    .addDynamicRule(new CommandPermissionConfiguration(
                            new CommandStartMatcherConfiguration(
                                    new CommandOptionsConfiguration(List.of("mvn")), List.of()),
                            PermissionMode.YOLO,
                            null));
            lifecycle.installGates(approval, feedback, runtime);
            assertEquals(approval, runtime.permissions().gate());

            final var sessionId = store.create();
            store.append(sessionId, SessionEntry.of(store.now(), "user", "hello", null, null));
            store.append(sessionId, SessionEntry.of(store.now(), "assistant", "hi there", null, null));
            assertFalse(store.isEmpty(sessionId), "precondition: session holds a real exchange");

            lifecycle.release(sessionId, runtime);
            assertFalse(store.isEmpty(sessionId), "non-empty session must survive");
            assertNull(runtime.permissions().gate());
            assertTrue(
                    runtime.permissions().dynamicRules().isEmpty(),
                    "session-scoped dynamic bash rules must be dropped when the session closes");
        }
    }

    public static class ResolvesSessionThenContextThenCreatesScenarioModule extends ConfigurationTestModule {
        public ResolvesSessionThenContextThenCreatesScenarioModule() {
            super(Map.of("yca.storageHome", ResolvesSessionThenContextThenCreatesScenario.directory.toString()));
        }
    }

    public static class PrepareContextFileCreatesMissingFileAndSkipsBlankScenarioModule
            extends ConfigurationTestModule {
        public PrepareContextFileCreatesMissingFileAndSkipsBlankScenarioModule() {
            super(Map.of(
                    "yca.storageHome", PrepareContextFileCreatesMissingFileAndSkipsBlankScenario.directory.toString()));
        }
    }

    public static class InstallAppliesGatesAndReleaseKeepsNonEmptySessionScenarioModule
            extends ConfigurationTestModule {
        public InstallAppliesGatesAndReleaseKeepsNonEmptySessionScenarioModule() {
            super(Map.of(
                    "yca.storageHome", InstallAppliesGatesAndReleaseKeepsNonEmptySessionScenario.directory.toString()));
        }
    }
}
