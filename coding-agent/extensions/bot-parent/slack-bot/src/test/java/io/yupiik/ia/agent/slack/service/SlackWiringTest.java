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
package io.yupiik.ia.agent.slack.service;

import static io.yupiik.ia.agent.slack.support.SlackDomainFixture.task;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.wiring.FeedbackGateProducer;
import io.yupiik.ia.agent.slack.command.CancelCommand;
import io.yupiik.ia.agent.slack.command.CleanupCommand;
import io.yupiik.ia.agent.slack.command.ListCommand;
import io.yupiik.ia.agent.slack.command.RetryCommand;
import io.yupiik.ia.agent.slack.command.RunCommand;
import io.yupiik.ia.agent.slack.command.ShowCommand;
import io.yupiik.ia.agent.slack.configuration.AgentSlackContextConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackExecutorConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.context.SlackContextMailbox;
import io.yupiik.ia.agent.slack.model.SlackJson;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.support.SlackConfigurationTestModule;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real generated beans and proxies in extension-owned containers. */
class SlackWiringTest {
    @Nested
    @FusionSupport(modules = DefaultsModule.class)
    class Defaults {
        @TempDir
        static Path root;

        @Test
        void generatedSlackModuleDiscoversReadOnlyContextToolDisabledByDefault(
                @Fusion final RuntimeContainer container) {
            try (final var configuration = container.lookup(AgentSlackContextConfiguration.class);
                    final var tool = container.lookup(EmbeddedTool.class)) {
                assertEquals(
                        AgentSlackContextConfiguration.class,
                        configuration.bean().type());
                assertFalse(configuration.instance().enabled());
                assertEquals(
                        "/attempt/storage/slack-context",
                        configuration.instance().directory());
                assertEquals("", configuration.instance().channel());
                assertEquals(30, configuration.instance().timeoutSeconds());
                assertEquals("slack_context", tool.instance().name());
                assertEquals(ToolCategory.READ, tool.instance().category());
                assertTrue(tool.instance().readonly());
                assertFalse(tool.instance().usable(root));
                assertTrue(tool.instance()
                        .execute(Map.of("operation", "summary"), ToolContext.empty(root, new DisabledSandbox(root)))
                        .error());
            }
            assertFalse(Files.exists(root.resolve("uncreated")));
        }

        @Test
        void assembledMapperDiscoversGeneratedSlackModelCodecs(@Fusion final RuntimeContainer container) {
            try (final var mapper = container.lookup(JsonMapper.class)) {
                assertEquals(
                        Boolean.TRUE,
                        mapper.instance()
                                .fromString(SlackJson.ApiResponse.class, "{\"ok\":true}")
                                .ok());
            }
        }

        @Test
        void defaultConfigurationsBindWithoutInstallationOrCredentials(@Fusion final RuntimeContainer container) {
            try (final var configuration = container.lookup(SlackConfiguration.class);
                    final var prompts = container.lookup(SlackPrompts.class);
                    final var executor = container.lookup(SlackExecutorConfiguration.class)) {
                final var defaults = configuration.instance();
                assertEquals("", defaults.workspace());
                assertEquals("", defaults.botUser());
                assertEquals(List.of(), defaults.channels());
                assertEquals(List.of(), defaults.users());
                assertFalse(defaults.allowChannelMembers());
                assertFalse(defaults.allowSlackConnect());
                assertEquals("SLACK_APP_TOKEN", defaults.appTokenEnv());
                assertEquals("SLACK_BOT_TOKEN", defaults.botTokenEnv());
                assertEquals(root.resolve("uncreated").toString(), defaults.home());
                assertEquals("", defaults.agentConfiguration());
                assertEquals(100, defaults.maxPending());
                assertEquals(4, defaults.maxPendingPerUser());
                assertEquals(16000, defaults.maxInputChars());
                assertEquals(12000, defaults.maxAnswerChars());
                assertEquals(600, defaults.questionTimeoutSeconds());
                assertEquals(2592000, defaults.retentionSeconds());
                assertEquals("Answer", prompts.instance().answerLabel());
                assertEquals("Cancel", prompts.instance().cancelLabel());
                assertEquals("Assistant question", prompts.instance().questionTitle());
                assertTrue(prompts.instance().main().contains("{{{request}}}"));
                assertEquals(
                        ExecutorConfiguration.Backend.DOCKER,
                        executor.instance().backend());
                assertEquals("hidden", executor.instance().showToolOutput());
                assertEquals(3600, executor.instance().timeoutSeconds());
            }
            assertFalse(Files.exists(root.resolve("uncreated")));
        }
    }

    @Nested
    @FusionSupport(modules = MailboxModule.class)
    class Mailbox {
        @TempDir
        static Path root;

        @Test
        void generatedContextConfigurationMakesDiscoveredToolUsableWithHostMailbox(
                @Fusion final RuntimeContainer container) throws Exception {
            final var mailbox = Files.createDirectory(
                    root.resolve("renamed mailbox"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try (final var configuration = container.lookup(AgentSlackContextConfiguration.class);
                    final var tool = container.lookup(EmbeddedTool.class);
                    final var mapper = container.lookup(JsonMapper.class);
                    final var controller = new SlackContextMailbox(mailbox, mapper.instance(), "CBINDING");
                    final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                assertTrue(configuration.instance().enabled());
                assertEquals(mailbox.toString(), configuration.instance().directory());
                assertEquals("CBINDING", configuration.instance().channel());
                assertEquals(3, configuration.instance().timeoutSeconds());
                assertTrue(tool.instance().usable(root));
                final var result = executor.submit(() -> tool.instance()
                        .execute(Map.of("operation", "summary"), ToolContext.empty(root, new DisabledSandbox(root))));
                final var request = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    SlackContextMailbox.Request pending;
                    while ((pending = controller.pending()) == null) {
                        Thread.sleep(10);
                    }
                    return pending;
                });
                assertEquals("CBINDING", request.channel());
                assertEquals(Map.of("operation", "summary"), request.arguments());
                final var response = Map.<String, Object>of("status", "ok", "summary", "host mailbox context");
                controller.respond(request, response);
                final var answer = result.get(2, TimeUnit.SECONDS);
                assertFalse(answer.error());
                assertEquals(response, mapper.instance().fromString(Object.class, answer.output()));
            }
            assertFalse(Files.exists(root.resolve("uncreated")));
        }
    }

    @Nested
    @FusionSupport(modules = LazyLookupsModule.class)
    class LazyLookups {
        @TempDir
        static Path root;

        @Test
        void commandAndApplicationProxyLookupsDoNotOpenStateTokensTemplatesOrBackend(
                @Fusion final RuntimeContainer container) {
            final var state = root.resolve("uncreated");
            // Resolving the renderer must not compile this invalid template.
            for (final var type : List.of(SlackStore.class, SlackAdmission.class, SlackPromptRenderer.class)) {
                try (final var instance = container.lookup(type)) {
                    assertEquals(ApplicationScoped.class, instance.bean().scope());
                    assertNotEquals(type, instance.instance().getClass());
                }
            }
            for (final var type : List.of(
                    RunCommand.class,
                    ListCommand.class,
                    ShowCommand.class,
                    CancelCommand.class,
                    RetryCommand.class,
                    CleanupCommand.class)) {
                try (final var instance = container.lookup(type)) {
                    assertInstanceOf(type, instance.instance());
                }
                assertFalse(Files.exists(state), type.getName());
            }
            assertFalse(Files.exists(state));
        }
    }

    @Nested
    @FusionSupport(modules = TrustedPromptModule.class)
    class TrustedPrompt {
        @TempDir
        static Path root;

        @Test
        void trustedPromptConfigurationRendersThroughTheActualApplicationProxy(
                @Fusion final RuntimeContainer container) {
            try (final var configuration = container.lookup(SlackConfiguration.class);
                    final var renderer = container.lookup(SlackPromptRenderer.class);
                    final var mapper = container.lookup(JsonMapper.class)) {
                assertEquals(List.of("C1", "G2", "C3"), configuration.instance().channels());
                assertEquals(List.of("U1", "W2", "U3"), configuration.instance().users());
                assertEquals(ApplicationScoped.class, renderer.bean().scope());
                assertNotEquals(SlackPromptRenderer.class, renderer.instance().getClass());
                final var current = task("current", "U1", TaskStatus.queued, 100);
                final var previous = task("previous", "U1", TaskStatus.done, 1)
                        .publication("{{untrusted}}", "2.1", TaskStatus.done, 2);
                final var rendered = renderer.instance().render(current, List.of(previous), false, Map.of());
                final var lines = rendered.split("\n");
                assertEquals("trusted-policy", lines[0]);
                assertEquals("trusted-output", lines[1]);
                assertEquals(current.request(), mapper.instance().fromString(String.class, lines[2]));
                assertEquals(
                        List.of(Map.of("request", previous.request(), "answer", "{{untrusted}}")),
                        mapper.instance().fromString(Object.class, lines[3]));
                assertTrue(rendered.contains("exactly taskId and summary"));
                assertTrue(rendered.contains(
                        "taskId must equal " + mapper.instance().toString(current.id())));
            }
            assertFalse(Files.exists(root.resolve("uncreated")));
        }
    }

    @Nested
    @FusionSupport(modules = FeedbackModule.class)
    class Feedback {
        @TempDir
        static Path root;

        @Test
        void botFeedbackGateWinsOverCoreFallbackAndDisabledFeedbackCancelsWithoutMailbox(
                @Fusion final RuntimeContainer container) {
            final var mailbox = root.resolve("uncreated-mailbox");
            try (final var gate = container.lookup(FeedbackGate.class)) {
                assertEquals(FeedbackGate.class, gate.bean().type());
                assertEquals(2000, gate.bean().priority());
                final var candidates = container.getBeans().getBeans().get(FeedbackGate.class);
                assertEquals(2, candidates.size());
                final var fallback = candidates.stream()
                        .filter(bean -> bean.priority() == 1000)
                        .findFirst()
                        .orElseThrow();
                assertTrue(gate.bean().priority() > fallback.priority());
                assertNull(gate.instance().ask(List.of(new Question("choice", "Choose", List.of("yes", "no"), false))));
                assertFalse(Files.exists(mailbox));
            }
            assertFalse(Files.exists(mailbox));
            assertFalse(Files.exists(root.resolve("uncreated")));
        }
    }

    public static class DefaultsModule extends WiringModule {
        public DefaultsModule() {
            super(Defaults.root, Map.of());
        }
    }

    public static class MailboxModule extends WiringModule {
        public MailboxModule() {
            super(
                    Mailbox.root,
                    Map.of(
                            "slackContext.enabled", "true",
                            "slackContext.directory",
                                    Mailbox.root.resolve("renamed mailbox").toString(),
                            "slackContext.channel", "CBINDING",
                            "slackContext.timeoutSeconds", "3"));
        }
    }

    public static class LazyLookupsModule extends WiringModule {
        public LazyLookupsModule() {
            super(
                    LazyLookups.root,
                    Map.of(
                            "id",
                            "a".repeat(64),
                            "slack.agentConfiguration",
                            LazyLookups.root.resolve("missing-agent.json").toString(),
                            "slack.appTokenEnv",
                            "YCA_WIRING_TEST_MISSING_APP_TOKEN",
                            "slack.botTokenEnv",
                            "YCA_WIRING_TEST_MISSING_BOT_TOKEN",
                            "slack.executor.docker.executable",
                            LazyLookups.root.resolve("missing-docker").toString(),
                            "slack.prompts.main",
                            "{{#each missing}}"));
        }
    }

    public static class TrustedPromptModule extends WiringModule {
        public TrustedPromptModule() {
            super(
                    TrustedPrompt.root,
                    Map.of(
                            "slack.channels", " C1, G2, ,C3 ",
                            "slack.users", "sep=; U1; W2; ;U3 ",
                            "slack.prompts.policy", "trusted-policy",
                            "slack.prompts.output", "trusted-output",
                            "slack.prompts.main", "{{{policy}}}\n{{{output}}}\n{{{request}}}\n{{{context}}}"));
        }
    }

    public static class FeedbackModule extends WiringModule {
        public FeedbackModule() {
            super(
                    Feedback.root,
                    Map.of(
                            "yca.bot.feedback.enabled", "false",
                            "yca.bot.feedback.directory",
                                    Feedback.root.resolve("uncreated-mailbox").toString(),
                            "yca.bot.feedback.timeoutSeconds", "600"));
        }
    }

    private abstract static class WiringModule extends SlackConfigurationTestModule {
        private WiringModule(final Path root, final Map<String, String> overrides) {
            super(values(root, overrides));
        }

        private static Map<String, String> values(final Path root, final Map<String, String> overrides) {
            final var values = new HashMap<String, String>();
            values.put("slack.home", root.resolve("uncreated").toString());
            values.putAll(overrides);
            return values;
        }

        @Override
        public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
            return super.beanFilter().and((container, bean) -> {
                final var raw = bean.type() instanceof ParameterizedType parameterized
                        ? parameterized.getRawType()
                        : bean.type();
                return raw instanceof Class<?> type
                        && (!type.getName().startsWith("io.yupiik.ia.agent.core.")
                                || type == FeedbackGateProducer.class
                                || type == FeedbackGate.class
                                || type == ProviderBudgetConfiguration.class);
            });
        }
    }
}
