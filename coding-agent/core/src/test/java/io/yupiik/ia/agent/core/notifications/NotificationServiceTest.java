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
package io.yupiik.ia.agent.core.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.OS.LINUX;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.NotificationsConfiguration;
import io.yupiik.ia.agent.core.configuration.NotificationsEventConfiguration;
import io.yupiik.ia.agent.core.configuration.NotificationsOnConfiguration;
import io.yupiik.ia.agent.core.configuration.NotificationsToolsConfiguration;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class NotificationServiceTest {

    /** A synchronous executor: execute() runs the task inline so deliveries are observable immediately. */
    private static ExecutorService executor() {
        return new ExecutorService() {
            @Override
            public void execute(final Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {}

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(final long timeout, final TimeUnit unit) {
                return true;
            }

            @Override
            public <T> Future<T> submit(final Callable<T> task) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> Future<T> submit(final Runnable task, final T result) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Future<?> submit(final Runnable task) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> List<Future<T>> invokeAll(
                    final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T invokeAny(final Collection<? extends Callable<T>> tasks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T invokeAny(
                    final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Captures each delivery: the argv and the env, plus the returned process. */
    private record Delivery(List<String> argv, Map<String, String> env) {}

    /** A fake process whose waitFor returns immediately. */
    private static final class FakeProcess extends Process {
        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            // no-op
        }
    }

    @Nested
    @FusionSupport(modules = CustomSinkWithFlagSources.class)
    class CustomSinkWithFlag {
        @Test
        void sessionInteractionAcknowledgesCustomSinkWithOrderedEventTimestamps(
                @Fusion final CodingAgentConfiguration configuration) {
            final var deliveries = new ArrayList<Delivery>();
            final var service = new NotificationService(configuration, executor(), (argv, env) -> {
                deliveries.add(new Delivery(argv, env));
                return new FakeProcess();
            });
            service.onInputRequired("s1", "title", 1, "btw");
            service.onSessionInteraction("s1");
            service.onTurnEnd("s1", "title", "model", "provider", true, "reply");
            assertEquals(3, deliveries.size());
            final var ack = deliveries.get(1);
            assertEquals(List.of("custom", "--flag"), ack.argv());
            assertEquals("session_acknowledged", ack.env().get(NotificationService.ENV_TYPE));
            assertEquals("s1", ack.env().get(NotificationService.ENV_SESSION));
            assertEquals("", ack.env().get(NotificationService.ENV_BODY));
            assertEquals("", ack.env().get(NotificationService.ENV_SUCCESS));
            final var before = Long.parseLong(deliveries.getFirst().env().get(NotificationService.ENV_TIMESTAMP));
            final var acknowledged = Long.parseLong(ack.env().get(NotificationService.ENV_TIMESTAMP));
            final var after = Long.parseLong(deliveries.getLast().env().get(NotificationService.ENV_TIMESTAMP));
            assertTrue(before < acknowledged);
            assertTrue(acknowledged < after);
            service.onSessionInteraction(null);
            service.onSessionInteraction(" ");
            assertEquals(3, deliveries.size());
        }

        @Test
        void interactionsHaveCompactPrivatePayloadsAndNoOutcome(@Fusion final CodingAgentConfiguration configuration) {
            final var deliveries = new ArrayList<Delivery>();
            final var service = new NotificationService(configuration, executor(), (argv, env) -> {
                deliveries.add(new Delivery(argv, env));
                return new FakeProcess();
            });
            service.onInputRequired("s1", "  My\n title\t ", 2, "main");
            service.onApprovalRequired("s2", "Review", "bash", "btw", ApprovalGate.Origin.PERMISSION);
            assertEquals(List.of("custom", "--flag"), deliveries.getFirst().argv());
            final var input = deliveries.getFirst().env();
            assertEquals("s1 - My title", input.get(NotificationService.ENV_TITLE));
            assertEquals(
                    "Input required: 2 questions waiting.\nReturn to YCA to answer.",
                    input.get(NotificationService.ENV_BODY));
            assertEquals("input_required", input.get(NotificationService.ENV_TYPE));
            assertEquals("main", input.get(NotificationService.ENV_SOURCE));
            assertEquals("", input.get(NotificationService.ENV_TOOL));
            for (final var delivery : deliveries) {
                assertEquals("", delivery.env().get(NotificationService.ENV_SUCCESS));
                assertEquals("", delivery.env().get(NotificationService.ENV_MODEL));
                assertEquals("", delivery.env().get(NotificationService.ENV_PROVIDER));
            }
            final var approval = deliveries.getLast().env();
            assertEquals(
                    "/btw: Tool permission approval required for bash.\nReturn to YCA to review.",
                    approval.get(NotificationService.ENV_BODY));
            assertEquals("bash", approval.get(NotificationService.ENV_TOOL));
            assertEquals("btw", approval.get(NotificationService.ENV_SOURCE));
        }
    }

    public static class CustomSinkWithFlagSources extends ConfigurationTestModule {
        public CustomSinkWithFlagSources() {
            super(Map.of("yca.notifications.enabled", "true", "yca.notifications.command", "custom,--flag"));
        }
    }

    @Nested
    @FusionSupport(modules = DefaultsSources.class)
    class Defaults {
        @Test
        void sessionAcknowledgementRespectsMasterSwitchButNotEventFiltersAndDoesNotPopup(
                @Fusion final CodingAgentConfiguration configuration) {
            final var skipped = new ArrayList<Delivery>();
            for (final var notifications : List.of(
                    new NotificationsConfiguration(
                            false,
                            List.of("custom"),
                            configuration.notifications().on()),
                    new NotificationsConfiguration(
                            true, null, configuration.notifications().on()))) {
                final var service = new NotificationService(
                        withNotifications(configuration, notifications), executor(), (argv, env) -> {
                            skipped.add(new Delivery(argv, env));
                            return new FakeProcess();
                        });
                service.onSessionInteraction("s1");
            }
            assertTrue(skipped.isEmpty(), "Acknowledgements must not create popups or run while disabled");
            final var deliveries = new ArrayList<Delivery>();
            final var service = new NotificationService(
                    withNotifications(
                            configuration,
                            new NotificationsConfiguration(
                                    true,
                                    List.of("custom"),
                                    new NotificationsOnConfiguration(
                                            new NotificationsEventConfiguration(false, false),
                                            new NotificationsToolsConfiguration(false, false)))),
                    executor(),
                    (argv, env) -> {
                        deliveries.add(new Delivery(argv, env));
                        return new FakeProcess();
                    });
            service.onSessionInteraction("s1");
            assertEquals(1, deliveries.size());
        }

        @Test
        void eventSwitchesAreIndependentAndMasterSwitchWins(@Fusion final CodingAgentConfiguration configuration) {
            for (int mask = 0; mask < 32; mask++) {
                final var deliveries = new ArrayList<Delivery>();
                final var enabled = (mask & 16) != 0;
                final var config = withNotifications(
                        configuration,
                        new NotificationsConfiguration(
                                enabled,
                                List.of("custom"),
                                new NotificationsOnConfiguration(
                                        new NotificationsEventConfiguration((mask & 8) != 0, (mask & 4) != 0),
                                        new NotificationsToolsConfiguration((mask & 2) != 0, (mask & 1) != 0))));
                final var service = new NotificationService(config, executor(), (argv, env) -> {
                    deliveries.add(new Delivery(argv, env));
                    return new FakeProcess();
                });
                service.onTurnEnd("s", "title", "model", "provider", true, "reply");
                service.onInputRequired("s", "title", 2, "main");
                service.onInputRequired("s", "title", 1, "btw");
                service.onApprovalRequired("s", "title", "bash", "main", ApprovalGate.Origin.PERMISSION);
                service.onApprovalRequired("s", "title", "bash", "btw", ApprovalGate.Origin.PERMISSION);
                assertEquals(
                        enabled ? Integer.bitCount(mask & 15) + ((mask & 4) != 0 ? 1 : 0) : 0,
                        deliveries.size(),
                        "mask=" + mask);
                final var events = deliveries.stream()
                        .map(d -> d.env().get(NotificationService.ENV_TYPE) + ":"
                                + d.env().getOrDefault(NotificationService.ENV_SOURCE, ""))
                        .toList();
                assertEquals(enabled && (mask & 8) != 0, events.contains("turn_end:"), "turn end mask=" + mask);
                assertEquals(
                        enabled && (mask & 2) != 0, events.contains("input_required:main"), "main input mask=" + mask);
                assertEquals(
                        enabled && (mask & 1) != 0, events.contains("input_required:btw"), "btw input mask=" + mask);
                assertEquals(
                        enabled && (mask & 4) != 0,
                        events.contains("approval_required:main"),
                        "main approval mask=" + mask);
                assertEquals(
                        enabled && (mask & 4) != 0,
                        events.contains("approval_required:btw"),
                        "btw approval mask=" + mask);
            }
        }
    }

    public static class DefaultsSources extends ConfigurationTestModule {
        public DefaultsSources() {
            super(Map.of());
        }
    }

    @Nested
    @FusionSupport(modules = CustomSinkSources.class)
    class CustomSink {
        @Test
        void approvalOriginAndMainBtwSourceRemainIndependent(@Fusion final CodingAgentConfiguration configuration) {
            final var deliveries = new ArrayList<Delivery>();
            final var service = new NotificationService(configuration, executor(), (argv, env) -> {
                deliveries.add(new Delivery(argv, env));
                return new FakeProcess();
            });
            for (final var source : List.of("main", "btw")) {
                for (final var origin : ApprovalGate.Origin.values()) {
                    service.onApprovalRequired("s", "Title", "sandbox", source, origin);
                    final var env = deliveries.getLast().env();
                    assertEquals(source, env.get(NotificationService.ENV_SOURCE));
                    assertEquals(origin.name(), env.get(NotificationService.ENV_ORIGIN));
                    assertTrue(env.get(NotificationService.ENV_BODY)
                            .contains(
                                    origin == ApprovalGate.Origin.SANDBOX
                                            ? "Sandbox approval"
                                            : "Tool permission approval"));
                }
            }
        }

        @Test
        void sanitizesAsciiControlsWithoutBroadeningUnicodeWhitespaceAndKeepsLabelLimits(
                @Fusion final CodingAgentConfiguration configuration) {
            final var deliveries = new ArrayList<Delivery>();
            final var service = new NotificationService(configuration, executor(), (argv, env) -> {
                deliveries.add(new Delivery(argv, env));
                return new FakeProcess();
            });
            service.onApprovalRequired(
                    "s",
                    "\u2003alpha\u0000\t\n\u007fbeta\u00a0gamma\u2003delta\u2003",
                    "\ttool\r\n\u000b\f name\t",
                    "main",
                    ApprovalGate.Origin.PERMISSION);
            final var first = deliveries.getFirst().env();
            assertEquals("s - alpha beta\u00a0gamma\u2003delta", first.get(NotificationService.ENV_TITLE));
            assertEquals("tool name", first.get(NotificationService.ENV_TOOL));

            service.onApprovalRequired("s", "x".repeat(121), "y".repeat(65), "main", ApprovalGate.Origin.PERMISSION);
            final var last = deliveries.getLast().env();
            assertEquals("s - " + "x".repeat(120), last.get(NotificationService.ENV_TITLE));
            assertEquals("y".repeat(64), last.get(NotificationService.ENV_TOOL));
        }
    }

    public static class CustomSinkSources extends ConfigurationTestModule {
        public CustomSinkSources() {
            super(Map.of("yca.notifications.enabled", "true", "yca.notifications.command", "custom"));
        }
    }

    @Nested
    @FusionSupport(modules = DesktopSinkSources.class)
    class DesktopSink {
        @Test
        @EnabledOnOs(LINUX)
        void interactionLabelsAreBoundedAndDesktopMarkupEscaped(
                @Fusion final CodingAgentConfiguration configuration, @TempDir final Path tempDir) {
            final var ref = new AtomicReference<Delivery>();
            final var service = new NotificationService(
                    configuration,
                    executor(),
                    (argv, env) -> {
                        ref.set(new Delivery(argv, env));
                        return new FakeProcess();
                    },
                    tempDir);
            service.onApprovalRequired(
                    "s", "<b>&\n" + "x".repeat(200), "<tool>&", "btw", ApprovalGate.Origin.PERMISSION);
            final var delivery = ref.get();
            assertTrue(delivery.argv().contains("dialog-warning"));
            assertTrue(delivery.argv().get(delivery.argv().size() - 2).startsWith("s - &lt;b&gt;&amp; "));
            assertTrue(delivery.argv().getLast().contains("&lt;tool&gt;&amp;"));
            assertTrue(delivery.env().get(NotificationService.ENV_TITLE).length() <= 124);
        }

        @Test
        void deliveryIsAsynchronousAndFailuresDoNotReachCaller(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var release = new CountDownLatch(1);
            final var delivered = new CountDownLatch(1);
            try (final var worker = Executors.newSingleThreadExecutor()) {
                worker.execute(() -> {
                    try {
                        release.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                final var service = new NotificationService(configuration, worker, (argv, env) -> {
                    delivered.countDown();
                    throw new IllegalStateException("unavailable desktop");
                });
                try {
                    service.onInputRequired("s", "title", 1, "main");
                    assertEquals(1, delivered.getCount(), "delivery must be queued, not run on the caller");
                } finally {
                    release.countDown();
                }
                assertTrue(delivered.await(5, TimeUnit.SECONDS));
            }
            final var stopped = Executors.newSingleThreadExecutor();
            stopped.shutdown();
            final var service = new NotificationService(configuration, stopped, (argv, env) -> {
                throw new AssertionError("must not execute");
            });
            service.onApprovalRequired("s", "title", "bash", "main", ApprovalGate.Origin.PERMISSION);
        }

        @Test
        @EnabledOnOs(LINUX)
        void defaultSinkAppendsIconTitleAndBodyAndSetsEnv(
                @Fusion final CodingAgentConfiguration configuration, @TempDir final Path tempDir) {
            final var deliveryRef = new AtomicReference<Delivery>();
            final var exec = (NotificationService.ExecFn) (argv, env) -> {
                deliveryRef.set(new Delivery(argv, env));
                return new FakeProcess();
            };
            final var config = configuration;
            final var service = new NotificationService(config, executor(), exec, tempDir);
            service.onTurnEnd("s1", "t1", "m1", "p1", true, "b1");
            final var delivery = deliveryRef.get();
            // Linux default sink (notify-send): --app-name=yca, -h x-canonical-private-synchronous, -i <icon>, -n
            // <appIcon>, <title>, <body>.
            final var appIcon = tempDir.resolve("yca.png");
            assertTrue(Files.isRegularFile(appIcon), "app icon must be materialized as a file in the temp dir");
            assertEquals(
                    List.of(
                            "notify-send",
                            "--app-name=yca",
                            "-h",
                            "string:x-canonical-private-synchronous:s1",
                            "-i",
                            "emblem-default",
                            "-n",
                            appIcon.toString(),
                            "t1",
                            "b1"),
                    delivery.argv());
            assertEquals("s1", delivery.env().get(NotificationService.ENV_SESSION));
            assertEquals("t1", delivery.env().get(NotificationService.ENV_TITLE));
            assertEquals("m1", delivery.env().get(NotificationService.ENV_MODEL));
            assertEquals("p1", delivery.env().get(NotificationService.ENV_PROVIDER));
            assertEquals("true", delivery.env().get(NotificationService.ENV_SUCCESS));
            assertEquals("b1", delivery.env().get(NotificationService.ENV_BODY));
        }

        @Test
        @EnabledOnOs(LINUX)
        void defaultSinkUsesErrorIconOnFailure(
                @Fusion final CodingAgentConfiguration configuration, @TempDir final Path tempDir) {
            final var deliveryRef = new AtomicReference<Delivery>();
            final var exec = (NotificationService.ExecFn) (argv, env) -> {
                deliveryRef.set(new Delivery(argv, env));
                return new FakeProcess();
            };
            final var config = configuration;
            final var service = new NotificationService(config, executor(), exec, tempDir);
            service.onTurnEnd("s2", "t2", "m2", "p2", false, "b2");
            final var delivery = deliveryRef.get();
            final var appIcon = tempDir.resolve("yca.png");
            assertEquals(
                    List.of(
                            "notify-send",
                            "--app-name=yca",
                            "-h",
                            "string:x-canonical-private-synchronous:s2",
                            "-i",
                            "dialog-error",
                            "-n",
                            appIcon.toString(),
                            "t2",
                            "b2"),
                    delivery.argv());
            assertEquals("false", delivery.env().get(NotificationService.ENV_SUCCESS));
            assertEquals("b2", delivery.env().get(NotificationService.ENV_BODY));
        }

        @Test
        @EnabledOnOs(LINUX)
        void defaultSinkFallsBackToYcaReplacesIdWhenSessionIsNull(
                @Fusion final CodingAgentConfiguration configuration, @TempDir final Path tempDir) {
            final var deliveryRef = new AtomicReference<Delivery>();
            final var exec = (NotificationService.ExecFn) (argv, env) -> {
                deliveryRef.set(new Delivery(argv, env));
                return new FakeProcess();
            };
            final var config = configuration;
            final var service = new NotificationService(config, executor(), exec, tempDir);
            service.onTurnEnd(null, "t3", "m3", "p3", true, "b3");
            final var delivery = deliveryRef.get();
            final var appIcon = tempDir.resolve("yca.png");
            // a null session id collapses under the fixed "yca" key, and -n is still applied.
            assertEquals(
                    List.of(
                            "notify-send",
                            "--app-name=yca",
                            "-h",
                            "string:x-canonical-private-synchronous:yca",
                            "-i",
                            "emblem-default",
                            "-n",
                            appIcon.toString(),
                            "t3",
                            "b3"),
                    delivery.argv());
        }
    }

    public static class DesktopSinkSources extends ConfigurationTestModule {
        public DesktopSinkSources() {
            super(Map.of("yca.notifications.enabled", "true"));
        }
    }

    @Nested
    @FusionSupport(modules = DisabledSources.class)
    class Disabled {
        @Test
        void disabledIsNoOp(@Fusion final CodingAgentConfiguration configuration) {
            final var deliveries = new ArrayList<Delivery>();
            final var exec = (NotificationService.ExecFn) (argv, env) -> {
                deliveries.add(new Delivery(argv, env));
                return new FakeProcess();
            };
            final var config = configuration;
            final var service = new NotificationService(config, executor(), exec);
            service.onTurnEnd("s1", "title", "model", "provider", true, "body");
            assertTrue(deliveries.isEmpty(), "disabled notifications must not run the command");
        }
    }

    public static class DisabledSources extends ConfigurationTestModule {
        public DisabledSources() {
            super(Map.of(
                    "yca.notifications.enabled", "false",
                    "yca.notifications.command", "echo"));
        }
    }

    @Nested
    @FusionSupport(modules = RemoteCommandSources.class)
    class RemoteCommand {
        @Test
        void customCommandKeepsArgvAndSetsEnv(@Fusion final CodingAgentConfiguration configuration) {
            final var deliveryRef = new AtomicReference<Delivery>();
            final var exec = (NotificationService.ExecFn) (argv, env) -> {
                deliveryRef.set(new Delivery(argv, env));
                return new FakeProcess();
            };
            final var config = configuration;
            final var service = new NotificationService(config, executor(), exec);
            service.onTurnEnd("s9", "t9", "m9", "p9", false, "b9");
            final var delivery = deliveryRef.get();
            // the custom command argv must stay exactly as configured (env-only payload)
            assertEquals(List.of("remote-notify", "--flag"), delivery.argv());
            assertEquals("s9", delivery.env().get(NotificationService.ENV_SESSION));
            assertEquals("false", delivery.env().get(NotificationService.ENV_SUCCESS));
            assertEquals("b9", delivery.env().get(NotificationService.ENV_BODY));
        }
    }

    public static class RemoteCommandSources extends ConfigurationTestModule {
        public RemoteCommandSources() {
            super(Map.of(
                    "yca.notifications.enabled",
                    "true",
                    "yca.notifications.command",
                    String.join(",", "remote-notify", "--flag")));
        }
    }

    @Nested
    @FusionSupport(modules = FailingCommandSources.class)
    class FailingCommand {
        @Test
        void neverThrowsWhenExecFails(@Fusion final CodingAgentConfiguration configuration) {
            final var exec = (NotificationService.ExecFn) (argv, env) -> {
                throw new IllegalStateException("boom");
            };
            final var config = configuration;
            final var service = new NotificationService(config, null, exec);
            // must not throw even with no executor and a failing command
            service.onTurnEnd("s1", "t1", "m1", "p1", true, "b1");
        }
    }

    public static class FailingCommandSources extends ConfigurationTestModule {
        public FailingCommandSources() {
            super(Map.of(
                    "yca.notifications.enabled", "true",
                    "yca.notifications.command", "echo"));
        }
    }

    private static CodingAgentConfiguration withNotifications(
            final CodingAgentConfiguration baseline, final NotificationsConfiguration notifications) {
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                baseline.workingDir(),
                baseline.projectDir(),
                baseline.provider(),
                baseline.model(),
                baseline.llm(),
                baseline.compaction(),
                baseline.pruning(),
                baseline.compression(),
                baseline.memory(),
                baseline.todos(),
                baseline.optimize(),
                baseline.permissions(),
                baseline.sandbox(),
                baseline.mcp(),
                baseline.mavenRepositoryKnowledge(),
                baseline.structuralCodeGraph(),
                baseline.sessions(),
                baseline.skills(),
                baseline.agents(),
                baseline.mode(),
                baseline.httpClient(),
                baseline.tools(),
                baseline.subagents(),
                baseline.schedulerThreads(),
                baseline.metrics(),
                baseline.maxLineLength(),
                baseline.resumeAliases(),
                baseline.slashcommands(),
                baseline.prompt(),
                baseline.labels(),
                baseline.logging(),
                baseline.hooks(),
                notifications,
                baseline.secrets(),
                null);
    }
}
