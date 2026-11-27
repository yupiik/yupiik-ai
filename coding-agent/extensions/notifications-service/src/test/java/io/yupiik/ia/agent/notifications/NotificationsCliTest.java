/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotificationsCliTest {
    private static final String TOKEN = "notification-cli-test-shared-token";

    @Test
    void launcherUsageNeedsNeitherConfigurationNorNativeAccess(@TempDir final Path directory) throws Exception {
        final var help = run(directory, Map.of(), "--help");
        // The managed Fusion version reports usage with an IllegalArgumentException (exit 1).
        assertNotEquals(0, help.exitCode(), help.output());
        assertTrue(help.output().contains("Commands:"), help.output());
        assertTrue(help.output().contains("serve"), help.output());
        assertTrue(help.output().contains("publish"), help.output());
        assertTrue(help.output().contains("notification-service-port"), help.output());
        assertTrue(help.output().contains("notification-service-token"), help.output());
        assertTrue(help.output().contains("notification-service-token-file"), help.output());
        assertTrue(help.output().contains("notification-service-linux-backend"), help.output());
    }

    @Test
    void rejectsInvalidLinuxBackendFromArgvAndEnvironment(@TempDir final Path directory) throws Exception {
        final var argument = run(directory, Map.of(), "publish", "--notification-service-linux-backend", "gtk4");
        final var environment = run(directory, Map.of("NOTIFICATION_SERVICE_LINUX_BACKEND", "gtk4"), "publish");
        for (final var result : List.of(argument, environment)) {
            assertNotEquals(0, result.exitCode(), result.output());
            assertTrue(
                    result.output().contains("notification-service-linux-backend must be one of: auto, glib, gtk3."),
                    result.output());
        }
    }

    @Test
    void linuxBackendOverridesDoNotRequireNativeAccessForPublish(@TempDir final Path directory) throws Exception {
        try (final var container = ConfiguringContainer.of().start();
                final var mapper = container.lookup(JsonMapper.class)) {
            final var unread = new UnreadNotifications();
            final var endpoint = new NotificationEndpoint(mapper.instance());
            try (final var listener = endpoint.listen(new ServiceConfiguration(0, TOKEN, true, null), unread)) {
                for (final var backend : List.of("auto", "glib", "gtk3")) {
                    final var argument = run(
                            directory,
                            Map.of("NOTIFICATION_SERVICE_TOKEN", TOKEN, "YCA_NOTIFICATION_SESSION", backend),
                            "publish",
                            "--notification-service-port",
                            Integer.toString(listener.port()),
                            "--notification-service-linux-backend",
                            backend);
                    assertEquals(0, argument.exitCode(), argument.output());
                    final var environment = run(
                            directory,
                            Map.of(
                                    "NOTIFICATION_SERVICE_TOKEN", TOKEN,
                                    "NOTIFICATION_SERVICE_LINUX_BACKEND", backend,
                                    "YCA_NOTIFICATION_SESSION", backend + "-env"),
                            "publish",
                            "--notification-service-port",
                            Integer.toString(listener.port()));
                    assertEquals(0, environment.exitCode(), environment.output());
                }
                assertEquals(6, unread.read().size());
            }
        }
    }

    @Test
    void publishFailsPromptlyWithoutRunningService(@TempDir final Path directory) throws Exception {
        final int port;
        try (final var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        final var result = run(
                directory,
                Map.of("NOTIFICATION_SERVICE_TOKEN", TOKEN, "YCA_NOTIFICATION_SESSION", "offline-session"),
                "publish",
                "--notification-service-port",
                Integer.toString(port));
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Cannot publish notification"), result.output());
        assertFalse(result.output().contains(TOKEN), result.output());
    }

    @Test
    void tokenFilePublishFailsSafelyWithoutRunningService(@TempDir final Path directory) throws Exception {
        final var tokenFile = Files.writeString(directory.resolve("token"), TOKEN + "\n", StandardCharsets.UTF_8);
        final int port;
        try (final var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        final var result = run(
                directory,
                Map.of("YCA_NOTIFICATION_SESSION", "offline-session"),
                "publish",
                "--notification-service-port",
                Integer.toString(port),
                "--notification-service-token-file",
                tokenFile.toString());
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(
                result.output().contains("Cannot publish notification; ensure the notification service is running."),
                result.output());
        assertFalse(result.output().contains(TOKEN), result.output());
    }

    @Test
    void tokenFilePublisherDeliversWithoutSecretInArgvOrEnvironment(@TempDir final Path directory) throws Exception {
        final var tokenFile = Files.writeString(directory.resolve("token"), TOKEN + "\r\n", StandardCharsets.UTF_8);
        try (final var container = ConfiguringContainer.of().start();
                final var mapper = container.lookup(JsonMapper.class)) {
            final var unread = new UnreadNotifications();
            final var endpoint = new NotificationEndpoint(mapper.instance());
            try (final var listener = endpoint.listen(new ServiceConfiguration(0, TOKEN, true, null), unread)) {
                final var result = run(
                        directory,
                        Map.of("YCA_NOTIFICATION_SESSION", "token-file-session"),
                        "publish",
                        "--notification-service-port",
                        Integer.toString(listener.port()),
                        "--notification-service-token-file",
                        tokenFile.toString());
                assertEquals(0, result.exitCode(), result.output());
                final var notices = unread.read();
                assertEquals(1, notices.size());
                assertEquals("token-file-session", notices.getFirst().session());
                assertFalse(result.output().contains(TOKEN), result.output());
            }
        }
    }

    @Test
    void tokenFileEnvironmentSettingDelivers(@TempDir final Path directory) throws Exception {
        final var tokenFile = Files.writeString(directory.resolve("token"), TOKEN + "\n", StandardCharsets.UTF_8);
        try (final var container = ConfiguringContainer.of().start();
                final var mapper = container.lookup(JsonMapper.class)) {
            final var unread = new UnreadNotifications();
            final var endpoint = new NotificationEndpoint(mapper.instance());
            try (final var listener = endpoint.listen(new ServiceConfiguration(0, TOKEN, true, null), unread)) {
                final var result = run(
                        directory,
                        Map.of(
                                "NOTIFICATION_SERVICE_TOKEN_FILE",
                                tokenFile.toString(),
                                "YCA_NOTIFICATION_SESSION",
                                "token-file-env-session"),
                        "publish",
                        "--notification-service-port",
                        Integer.toString(listener.port()));
                assertEquals(0, result.exitCode(), result.output());
                final var notices = unread.read();
                assertEquals(1, notices.size());
                assertEquals("token-file-env-session", notices.getFirst().session());
                assertFalse(result.output().contains(TOKEN), result.output());
            }
        }
    }

    @Test
    void eventEnvironmentReachesEndpoint(@TempDir final Path directory) throws Exception {
        try (final var container = ConfiguringContainer.of().start();
                final var mapper = container.lookup(JsonMapper.class)) {
            final var unread = new UnreadNotifications();
            final var endpoint = new NotificationEndpoint(mapper.instance());
            try (final var listener = endpoint.listen(new ServiceConfiguration(0, TOKEN, true, null), unread)) {
                final var result = run(
                        directory,
                        Map.of(
                                "NOTIFICATION_SERVICE_TOKEN", TOKEN,
                                "YCA_NOTIFICATION_SESSION", "test-session",
                                "YCA_NOTIFICATION_TITLE", "Test title",
                                "YCA_NOTIFICATION_TYPE", "approval_required",
                                "YCA_NOTIFICATION_SOURCE", "btw",
                                "YCA_NOTIFICATION_TIMESTAMP", "1234",
                                "YCA_NOTIFICATION_BODY", "This must not be stored"),
                        "publish",
                        "--notification-service-port",
                        Integer.toString(listener.port()));
                assertEquals(0, result.exitCode(), result.output());
                final var notices = unread.read();
                assertEquals(1, notices.size());
                final var notice = notices.getFirst();
                assertEquals(1, notice.version());
                assertEquals("test-session", notice.session());
                assertEquals("Test title", notice.title());
                assertEquals("approval_required", notice.type());
                assertEquals("btw", notice.source());
                assertEquals(1234, notice.timestamp());
                assertFalse(mapper.instance().toString(notice).contains("This must not be stored"));
                assertFalse(result.output().contains(TOKEN), result.output());
            }
        }
    }

    @Test
    void acknowledgementEnvironmentClearsSessionAndRejectsDelayedEvents(@TempDir final Path directory)
            throws Exception {
        try (final var container = ConfiguringContainer.of().start();
                final var mapper = container.lookup(JsonMapper.class)) {
            final var unread = new UnreadNotifications();
            final var endpoint = new NotificationEndpoint(mapper.instance());
            unread.publish(new Notice(1, "session", "Main", "turn_end", "main", 1, null));
            unread.publish(new Notice(1, "session", "Btw", "input_required", "btw", 2, null));
            final var newer = new Notice(1, "newer", "Newer", "turn_end", "main", 3, null);
            unread.publish(newer);
            try (final var listener = endpoint.listen(new ServiceConfiguration(0, TOKEN, true, null), unread)) {
                for (final var type : List.of("session_acknowledged", "approval_required")) {
                    final var result = run(
                            directory,
                            Map.of(
                                    "NOTIFICATION_SERVICE_TOKEN",
                                    TOKEN,
                                    "YCA_NOTIFICATION_SESSION",
                                    "session",
                                    "YCA_NOTIFICATION_TYPE",
                                    type,
                                    "YCA_NOTIFICATION_TIMESTAMP",
                                    "2"),
                            "publish",
                            "--notification-service-port",
                            Integer.toString(listener.port()));
                    assertEquals(0, result.exitCode(), result.output());
                    assertEquals(List.of(newer), unread.read());
                }
            }
        }
    }

    @Test
    void missingEventFieldsUseConfigurationDefaults(@TempDir final Path directory) throws Exception {
        try (final var container = ConfiguringContainer.of().start();
                final var mapper = container.lookup(JsonMapper.class)) {
            final var unread = new UnreadNotifications();
            final var endpoint = new NotificationEndpoint(mapper.instance());
            try (final var listener = endpoint.listen(new ServiceConfiguration(0, TOKEN, true, null), unread)) {
                final var result = run(
                        directory,
                        Map.of("NOTIFICATION_SERVICE_TOKEN", TOKEN, "YCA_NOTIFICATION_SESSION", "default-session"),
                        "publish",
                        "--notification-service-port",
                        Integer.toString(listener.port()));
                assertEquals(0, result.exitCode(), result.output());
                final var notices = unread.read();
                assertEquals(1, notices.size());
                assertEquals("", notices.getFirst().title());
                assertEquals("turn_end", notices.getFirst().type());
                assertEquals("main", notices.getFirst().source());
                assertTrue(notices.getFirst().timestamp() > 0);
            }
        }
    }

    private Result run(final Path directory, final Map<String, String> environment, final String... command)
            throws Exception {
        final var java = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString();
        final var args = new ArrayList<>(List.of(
                java,
                "--illegal-native-access=deny",
                "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "io.yupiik.fusion.framework.api.main.CliLauncher"));
        args.addAll(List.of(command));
        final var outputFile = Files.createTempFile(directory, "cli-", ".log");
        final var builder = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(outputFile.toFile());
        builder.environment()
                .keySet()
                .removeIf(key -> key.startsWith("YCA_NOTIFICATION_")
                        || key.startsWith("NOTIFICATION_SERVICE_")
                        || key.startsWith("notification-service")
                        || key.startsWith("yca.notification")
                        || List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
                                .contains(key));
        builder.environment().putAll(environment);
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "CLI did not exit promptly");
            return new Result(process.exitValue(), Files.readString(outputFile, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private record Result(int exitCode, String output) {}
}
