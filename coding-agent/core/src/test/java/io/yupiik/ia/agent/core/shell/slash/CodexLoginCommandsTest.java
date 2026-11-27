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
package io.yupiik.ia.agent.core.shell.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.CodexLoginService;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellFrontend;
import io.yupiik.ia.agent.core.shell.ShellIO;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Wiring tests for the {@code /codex login} slash command: registration and argument validation. */
@ExtendWith(TestJsonMapper.class)
class CodexLoginCommandsTest {

    @Nested
    @FusionSupport(modules = RegistersCodexLoginScenarioModule.class)
    class RegistersCodexLoginScenario {
        @TempDir
        static Path directory;

        @Test
        void registersCodexLogin(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var commands = commands(mapper, configuration);
            final var names =
                    commands.commands().stream().map(c -> c.spec().command()).toList();
            assertTrue(names.contains("codex"), "expected a /codex command, got " + names);
        }
    }

    @Nested
    @FusionSupport(modules = MissingProviderIsAnErrorScenarioModule.class)
    class MissingProviderIsAnErrorScenario {
        @TempDir
        static Path directory;

        @Test
        void missingProviderIsAnError(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var commands = commands(mapper, configuration);
            final var action = commands.commands().get(0).action();
            final CompletionStage<ShellOutcome> result = action.apply("", env());
            final ShellOutcome outcome = result.toCompletableFuture().get();
            assertInstanceOf(ShellOutcome.Error.class, outcome, outcome.toString());
            assertTrue(((ShellOutcome.Error) outcome).message().contains("provider"), outcome.toString());
        }
    }

    @Nested
    @FusionSupport(modules = OptionValueParsingScenarioModule.class)
    class OptionValueParsingScenario {
        @TempDir
        static Path directory;

        @Test
        void optionValueParsing() {
            assertNull(CodexLoginCommands.optionValue(List.of("login", "codex", "--enterprise"), "--workspace"));
            assertEquals(
                    "ws-1",
                    CodexLoginCommands.optionValue(List.of("login", "codex", "--workspace", "ws-1"), "--workspace"));
            assertEquals(
                    "ws-2",
                    CodexLoginCommands.optionValue(
                            List.of("login", "codex", "--enterprise", "--workspace", "ws-2", "--device"),
                            "--workspace"));
        }
    }

    private static CodexLoginCommands commands(final JsonMapper mapper, final CodingAgentConfiguration configuration) {
        return new CodexLoginCommands(new CodexLoginService(mapper, null, configuration));
    }

    private static ShellEnv env() {
        final ShellFrontend frontend = new ShellFrontend() {
            @Override
            public void status(final String message) {}

            @Override
            public void notice(final AgentEvent.Notice.Level level, final String message) {}

            @Override
            public void transcript(final String markdown) {}

            @Override
            public void error(final String message) {}

            @Override
            public void showThinking(final boolean value) {}

            @Override
            public void showToolOutput(final ToolOutputVisibility value) {}
        };
        final ShellIO io = new ShellIO() {
            @Override
            public CompletionStage<Optional<String>> readLine(final String prompt) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletionStage<Boolean> confirm(final String question) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public ApprovalGate approvalGate() {
                return null;
            }

            @Override
            public FeedbackGate feedbackGate() {
                return null;
            }
        };
        return new ShellEnv(frontend, io, new ShellSession(), null);
    }

    public static class RegistersCodexLoginScenarioModule extends ConfigurationTestModule {
        public RegistersCodexLoginScenarioModule() {
            super(Map.of("yca.storageHome", RegistersCodexLoginScenario.directory.toString()));
        }
    }

    public static class MissingProviderIsAnErrorScenarioModule extends ConfigurationTestModule {
        public MissingProviderIsAnErrorScenarioModule() {
            super(Map.of("yca.storageHome", MissingProviderIsAnErrorScenario.directory.toString()));
        }
    }

    public static class OptionValueParsingScenarioModule extends ConfigurationTestModule {
        public OptionValueParsingScenarioModule() {
            super(Map.of("yca.storageHome", OptionValueParsingScenario.directory.toString()));
        }
    }
}
