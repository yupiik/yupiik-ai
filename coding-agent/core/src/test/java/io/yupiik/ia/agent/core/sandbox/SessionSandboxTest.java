/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.SandboxConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxEnvironmentConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxFilesystemConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxHelperConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxMacOsConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxNetworkConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxUnixConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxUnixSocketBridgeConfiguration;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSandboxTest {
    @TempDir
    Path root;

    @Nested
    @FusionSupport(modules = ConfigurationTestModule.class)
    class Defaults {
        @Test
        void additionalHomeSecretsCannotBeReadWrittenOrApproved(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var home = Path.of(System.getProperty("user.home"));
            final var calls = new AtomicInteger();
            final var permissions = permissions(request -> {
                calls.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            // Only path checks: do not create, read, write or snapshot anything in the real home.
            try (final var sandbox =
                    new SessionSandbox(configuration.sandbox(), root, root, permissions, root.resolve("scratch"))) {
                for (final var target : List.of(
                        home.resolve(".m2/settings.xml"),
                        home.resolve(".m2/settings-security.xml"),
                        home.resolve(".yupiik/coding-agent/configuration.json"),
                        home.resolve(".bashrc"))) {
                    assertThrows(SecurityException.class, () -> sandbox.requireRead(target), target.toString());
                    assertThrows(SecurityException.class, () -> sandbox.requireWrite(target), target.toString());
                    assertThrows(
                            SecurityException.class,
                            () -> sandbox.request("filesystem", target.toString(), true),
                            target.toString());
                }
                assertEquals(0, calls.get());
            }
        }
    }

    @Test
    void automaticNetworkApprovalsAreSharedScopedAndRevocable() throws Exception {
        final var calls = new AtomicInteger();
        final var decision = new AtomicReference<>(ApprovalGate.Result.ALLOW_ONCE);
        final var permissions = permissions(request -> {
            assertEquals(ApprovalGate.Origin.SANDBOX, request.origin());
            assertEquals("network", request.args().get("kind"));
            calls.incrementAndGet();
            return decision.get();
        });
        final var destination = URI.create("https://8.8.8.8/path");
        try (final var sandbox = sandbox(permissions, List.of())) {
            sandbox.requireNetwork(destination);
            sandbox.requireNetwork(destination);
            assertEquals(2, calls.get());
            decision.set(ApprovalGate.Result.ALLOW_SESSION);
            final var view = sandbox.approvalView(permissions);
            view.requireNetwork(destination);
            decision.set(ApprovalGate.Result.DENY);
            sandbox.requireNetwork(URI.create("https://8.8.8.8:443/other"));
            sandbox.request("network", "8.8.8.8:443", false);
            assertEquals(3, calls.get());
            assertThrows(SecurityException.class, () -> view.requireNetwork(URI.create("http://8.8.8.8")));
            assertEquals(4, calls.get());
            sandbox.revoke();
            assertThrows(SecurityException.class, () -> view.requireNetwork(destination));
            assertEquals(5, calls.get());
            for (final var target : List.of("https://127.0.0.1", "https://169.254.169.254", "https://10.0.0.1")) {
                assertThrows(SecurityException.class, () -> sandbox.requireNetwork(URI.create(target)));
            }
            assertEquals(5, calls.get(), "Non-public destinations must never prompt");
        }
        try (final var sandbox = sandbox(permissions, List.of())) {
            assertThrows(SecurityException.class, () -> sandbox.requireNetwork(destination));
            assertEquals(6, calls.get(), "A new session must not inherit dynamic grants");
        }
    }

    @Test
    void explicitOnceNetworkGrantIsConsumedByOnlyOneOperation() throws Exception {
        final var calls = new AtomicInteger();
        final var permissions = permissions(request -> {
            calls.incrementAndGet();
            return calls.get() == 1 ? ApprovalGate.Result.ALLOW_ONCE : ApprovalGate.Result.DENY;
        });
        try (final var sandbox = sandbox(permissions, List.of())) {
            sandbox.request("network", "8.8.8.8:443", false);
            sandbox.requireNetwork(URI.create("https://8.8.8.8"));
            assertEquals(1, calls.get());
            assertThrows(SecurityException.class, () -> sandbox.requireNetwork(URI.create("https://8.8.8.8")));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void concurrentNetworkCallsShareOneSessionApproval() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var ready = new CountDownLatch(16);
        final var calls = new AtomicInteger();
        try (final var sandbox = sandbox(
                        permissions(request -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            try {
                                assertTrue(release.await(10, TimeUnit.SECONDS));
                            } catch (final InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(error);
                            }
                            return ApprovalGate.Result.ALLOW_SESSION;
                        }),
                        List.of());
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = new ArrayList<Future<?>>();
            for (var i = 0; i < 16; i++) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    sandbox.requireNetwork(URI.create("https://8.8.8.8"));
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                assertTrue(entered.await(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            for (final var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
            assertEquals(1, calls.get());
        }
    }

    @Test
    void fullYoloBypassesBoundaryAndRestoresConfiguredPolicyAndCommandLifetime() throws Exception {
        final var permissions = permissions(request -> ApprovalGate.Result.DENY);
        try (final var sandbox =
                sandbox(permissions, List.of(root.resolve("secret").toString()))) {
            permissions.setPermissionModeChangeListener(sandbox::revoke);
            permissions.setPermissionMode(PermissionMode.YOLO);
            assertTrue(sandbox.enabled());
            assertThrows(SecurityException.class, () -> sandbox.requireRead(root.resolve("secret")));
            permissions.setPermissionMode(PermissionMode.FULL_YOLO);
            assertFalse(sandbox.enabled());
            sandbox.requireRead(root.resolve("secret"));
            sandbox.requireWrite(Path.of("/etc"));
            sandbox.requireNetwork(URI.create("https://127.0.0.1"));
            sandbox.request("network", "example.com", false);
            try (final var command = sandbox.launch(new ProcessBuilder("sh", "-c", "exit 0"))) {
                assertEquals(0, command.process().waitFor());
            }
            assertThrows(IllegalArgumentException.class, () -> sandbox.request("invalid", "", false));
            final var restricted = sandbox.approvalView(permissions(request -> ApprovalGate.Result.DENY));
            assertTrue(restricted.enabled());
            assertThrows(SecurityException.class, () -> restricted.requireRead(root.resolve("secret")));
            try (final var command =
                    sandbox.launch(new ProcessBuilder("sh", "-c", "sleep 60").directory(root.toFile()))) {
                assertTrue(command.process().isAlive());
                permissions.setPermissionMode(PermissionMode.ASK);
                assertFalse(command.process().isAlive());
            }
            assertTrue(sandbox.enabled());
            assertThrows(SecurityException.class, () -> sandbox.requireRead(root.resolve("secret")));
            assertThrows(SecurityException.class, () -> sandbox.requireRead(root.resolve("outside")));
            sandbox.requireRead(root.resolve("project"));
        }
    }

    @Test
    void onceAccessPromptsAgainWhileSessionAccessIsSharedAndRevocable() throws Exception {
        final var count = new AtomicInteger();
        final var decision = new AtomicReference<>(ApprovalGate.Result.ALLOW_ONCE);
        final var permissions = permissions(request -> {
            assertEquals(ApprovalGate.Origin.SANDBOX, request.origin());
            count.incrementAndGet();
            return decision.get();
        });
        try (final var sandbox = sandbox(permissions, List.of())) {
            final var target = root.resolve("outside");
            sandbox.requireRead(target);
            sandbox.requireRead(target);
            assertEquals(2, count.get());
            decision.set(ApprovalGate.Result.ALLOW_SESSION);
            sandbox.requireRead(target);
            sandbox.requireRead(target);
            assertEquals(3, count.get());
            sandbox.revoke();
            decision.set(ApprovalGate.Result.DENY);
            assertThrows(SecurityException.class, () -> sandbox.requireRead(target));
            assertEquals(4, count.get());
        }
    }

    @Test
    void explicitFilesystemRequestsReuseCoveringSessionGrants() throws Exception {
        final var calls = new AtomicInteger();
        final var decision = new AtomicReference<>(ApprovalGate.Result.ALLOW_SESSION);
        final var permissions = permissions(request -> {
            calls.incrementAndGet();
            return decision.get();
        });
        final var outside = Files.createDirectories(root.resolve("outside"));
        final var secret = outside.resolve("secret");
        try (final var sandbox = sandbox(permissions, List.of(secret.toString()))) {
            sandbox.requireRead(outside);
            decision.set(ApprovalGate.Result.DENY);
            sandbox.request("filesystem", "../outside", false);
            sandbox.request("filesystem", outside.resolve("child").toString(), false);
            final var alias = Files.createSymbolicLink(root.resolve("alias"), outside);
            sandbox.request("filesystem", alias.toString(), false);
            assertEquals(1, calls.get());
            assertThrows(SecurityException.class, () -> sandbox.request("filesystem", secret.toString(), false));
            assertEquals(1, calls.get(), "Hard denies must precede grant reuse");
            assertThrows(SecurityException.class, () -> sandbox.request("filesystem", outside.toString(), true));
            assertThrows(SecurityException.class, () -> sandbox.request("filesystem", root.toString(), false));
            assertThrows(SecurityException.class, () -> sandbox.request("filesystem", outside + "-other", false));
            assertEquals(3, calls.get());

            decision.set(ApprovalGate.Result.ALLOW_SESSION);
            sandbox.request("filesystem", outside.toString(), true);
            decision.set(ApprovalGate.Result.DENY);
            sandbox.request("filesystem", outside.resolve("child").toString(), true);
            sandbox.request("filesystem", outside.resolve("child").toString(), false);
            sandbox.requireWrite(outside.resolve("child"));
            sandbox.requireRead(outside.resolve("child"));
            assertEquals(4, calls.get());
            sandbox.revoke();
            assertThrows(SecurityException.class, () -> sandbox.request("filesystem", outside.toString(), true));
            assertEquals(5, calls.get());
        }
    }

    @Test
    void explicitNetworkRequestsReuseOnlyCoveringSessionGrants() throws Exception {
        final var calls = new AtomicInteger();
        final var decision = new AtomicReference<>(ApprovalGate.Result.ALLOW_SESSION);
        try (final var sandbox = sandbox(
                permissions(request -> {
                    calls.incrementAndGet();
                    return decision.get();
                }),
                List.of())) {
            sandbox.request("network", "EXAMPLE.com", false);
            decision.set(ApprovalGate.Result.DENY);
            sandbox.request("network", "example.com:443", false);
            assertEquals(1, calls.get());
            assertThrows(SecurityException.class, () -> sandbox.request("network", "example.com:80", false));
            assertThrows(SecurityException.class, () -> sandbox.request("network", "*.example.com", false));
            assertThrows(SecurityException.class, () -> sandbox.request("network", "other.example.com", false));
            assertEquals(4, calls.get());
            decision.set(ApprovalGate.Result.ALLOW_SESSION);
            sandbox.request("network", "*.example.com", false);
            decision.set(ApprovalGate.Result.DENY);
            sandbox.request("network", "*.EXAMPLE.com:443", false);
            sandbox.request("network", "api.example.com", false);
            sandbox.request("network", "*.api.example.com", false);
            assertEquals(5, calls.get());
            assertThrows(SecurityException.class, () -> sandbox.request("network", "notexample.com", false));
            sandbox.revoke();
            assertThrows(SecurityException.class, () -> sandbox.request("network", "api.example.com", false));
            assertEquals(7, calls.get());
        }
    }

    @Test
    void repeatedExplicitOnceRequestsStillPrompt() throws Exception {
        final var calls = new AtomicInteger();
        try (final var sandbox = sandbox(
                permissions(request -> {
                    calls.incrementAndGet();
                    return ApprovalGate.Result.ALLOW_ONCE;
                }),
                List.of())) {
            for (final var kind : List.of("filesystem", "network")) {
                final var target =
                        "filesystem".equals(kind) ? root.resolve("outside").toString() : "example.com";
                sandbox.request(kind, target, false);
                sandbox.request(kind, target, false);
            }
            assertEquals(4, calls.get());
        }
    }

    @Test
    void modeChangesExpireRememberedExplicitRequestsAcrossViews() throws Exception {
        final var calls = new AtomicInteger();
        final var decision = new AtomicReference<>(ApprovalGate.Result.ALLOW_SESSION);
        final var permissions = permissions(request -> {
            calls.incrementAndGet();
            return decision.get();
        });
        try (final var sandbox = sandbox(permissions, List.of())) {
            permissions.setPermissionModeChangeListener(sandbox::revoke);
            final var view = sandbox.approvalView(permissions(request -> {
                throw new AssertionError("Existing grants should be shared with borrowed views");
            }));
            final var target = root.resolve("outside").toString();
            sandbox.request("filesystem", target, true);
            sandbox.request("network", "example.com", false);
            view.request("filesystem", target, false);
            view.request("network", "example.com", false);
            permissions.setPermissionMode(PermissionMode.ASK);
            sandbox.request("filesystem", target, true);
            sandbox.request("network", "example.com", false);
            assertEquals(2, calls.get(), "Setting the same effective mode must retain grants");
            permissions.setPermissionMode(PermissionMode.YOLO);
            permissions.setPermissionMode(PermissionMode.ASK);
            decision.set(ApprovalGate.Result.DENY);
            assertThrows(SecurityException.class, () -> sandbox.request("filesystem", target, false));
            assertThrows(SecurityException.class, () -> sandbox.request("network", "example.com", false));
            assertEquals(4, calls.get());
            sandbox.requireRead(root.resolve("project"));
        }
    }

    @Test
    void explicitOnceRequestDoesNotBecomeSessionGrant() throws Exception {
        final var decision = new AtomicReference<>(ApprovalGate.Result.ALLOW_ONCE);
        try (final var sandbox = sandbox(permissions(request -> decision.get()), List.of())) {
            final var target = root.resolve("outside");
            sandbox.request("filesystem", target.toString(), false);
            decision.set(ApprovalGate.Result.DENY);
            assertThrows(SecurityException.class, () -> sandbox.requireRead(target));
            decision.set(ApprovalGate.Result.ALLOW_SESSION);
            sandbox.request("filesystem", target.toString(), true);
            decision.set(ApprovalGate.Result.DENY);
            sandbox.requireWrite(target);
            sandbox.requireRead(target);
            sandbox.revoke();
            assertThrows(SecurityException.class, () -> sandbox.requireWrite(target));
        }
    }

    @Test
    void borrowedViewsRouteApprovalsShareGrantsAndDoNotOwnSession() throws Exception {
        final var mainCalls = new AtomicInteger();
        final var sideCalls = new AtomicInteger();
        final var childCalls = new AtomicInteger();
        try (final var sandbox = sandbox(
                permissions(request -> {
                    mainCalls.incrementAndGet();
                    return ApprovalGate.Result.DENY;
                }),
                List.of(root.resolve("secret").toString()))) {
            final var view = sandbox.approvalView(permissions(request -> {
                sideCalls.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            }));
            final var child = view.approvalView(permissions(request -> {
                childCalls.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            }));
            final var target = root.resolve("outside");
            view.requireRead(target);
            sandbox.requireRead(target);
            child.requireRead(root.resolve("child"));
            assertEquals(0, mainCalls.get());
            assertEquals(1, sideCalls.get());
            assertEquals(1, childCalls.get());
            assertThrows(SecurityException.class, () -> child.requireRead(root.resolve("secret")));
            assertEquals(1, childCalls.get());
            view.close();
            child.close();
            sandbox.requireRead(target);
            child.revoke();
            assertThrows(SecurityException.class, () -> sandbox.requireRead(target));
            assertEquals(1, mainCalls.get());
        }
    }

    @Test
    void revocationExpiresPendingOnceAndSessionApprovalsWithoutHoldingUiLock() throws Exception {
        for (final var result : List.of(ApprovalGate.Result.ALLOW_ONCE, ApprovalGate.Result.ALLOW_SESSION)) {
            for (final var operation : List.of("read", "filesystem", "network", "automatic-network", "launch")) {
                final var entered = new CountDownLatch(1);
                final var release = new CountDownLatch(1);
                try (final var sandbox = sandbox(
                                permissions(request -> {
                                    entered.countDown();
                                    try {
                                        release.await();
                                    } catch (final InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(e);
                                    }
                                    return result;
                                }),
                                List.of());
                        final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    final var pending = executor.submit(() -> assertThrows(SecurityException.class, () -> {
                        final var target = root.resolve("outside");
                        switch (operation) {
                            case "read" -> sandbox.requireRead(target);
                            case "launch" ->
                                sandbox.launch(new ProcessBuilder("never-execute").directory(target.toFile()));
                            case "filesystem" -> sandbox.request(operation, target.toString(), true);
                            case "automatic-network" -> sandbox.requireNetwork(URI.create("https://8.8.8.8"));
                            default -> sandbox.request(operation, "example.com:443", false);
                        }
                    }));
                    entered.await();
                    try {
                        // Would deadlock if launch kept its policy lock while asking the UI.
                        sandbox.revoke();
                    } finally {
                        release.countDown();
                    }
                    pending.get();
                }
            }
        }
    }

    @Test
    void hardDenyAndUnsafeWriteRootsCannotBeApproved() throws Exception {
        final var calls = new AtomicInteger();
        try (final var sandbox = sandbox(
                permissions(request -> {
                    calls.incrementAndGet();
                    return ApprovalGate.Result.ALLOW_SESSION;
                }),
                List.of(root.resolve("secret").toString()))) {
            assertThrows(SecurityException.class, () -> sandbox.requireRead(root.resolve("secret")));
            for (final var target : List.of("/", "/tmp", "/etc", System.getProperty("user.home"))) {
                assertThrows(SecurityException.class, () -> sandbox.request("filesystem", target, true));
            }
            assertEquals(0, calls.get());
        }
        for (final var target : List.of("/", "/tmp", "/etc", System.getProperty("user.home"))) {
            final var configuration = new SandboxConfiguration(
                    true,
                    new SandboxHelperConfiguration(""),
                    new SandboxFilesystemConfiguration(List.of(), List.of(target), List.of(), List.of()),
                    new SandboxNetworkConfiguration(List.of()),
                    List.of(),
                    new SandboxMacOsConfiguration(false),
                    new SandboxEnvironmentConfiguration(List.of(), Map.of()),
                    new SandboxUnixConfiguration(List.of()));
            assertThrows(
                    SecurityException.class,
                    () -> new SessionSandbox(
                            configuration,
                            root,
                            root,
                            permissions(request -> ApprovalGate.Result.DENY),
                            root.resolve("scratch")));
        }
    }

    @Test
    void disabledAndFullYoloLeaveTheHostEnvironmentUntouched() throws Exception {
        for (final var enabled : List.of(false, true)) {
            final var permissions = permissions(request -> ApprovalGate.Result.DENY);
            if (enabled) {
                permissions.setPermissionMode(PermissionMode.FULL_YOLO);
            }
            final var configuration = new SandboxConfiguration(
                    enabled,
                    new SandboxHelperConfiguration(""),
                    new SandboxFilesystemConfiguration(List.of(root.toString()), List.of(), List.of(), List.of()),
                    new SandboxNetworkConfiguration(List.of()),
                    List.of(),
                    new SandboxMacOsConfiguration(false),
                    new SandboxEnvironmentConfiguration(
                            List.of("HOME"), Map.of("TOKEN", "configured", "PATH", "forbidden")),
                    new SandboxUnixConfiguration(
                            List.of(new SandboxUnixSocketBridgeConfiguration("relative-invalid", 3128))));
            try (final var sandbox =
                    new SessionSandbox(configuration, root, root, permissions, root.resolve("scratch"))) {
                final var builder =
                        new ProcessBuilder("/bin/sh", "-c", "printf '%s' \"$TOKEN\"").directory(root.toFile());
                builder.environment().put("TOKEN", "original");
                try (final var command = sandbox.launch(builder)) {
                    assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
                    assertEquals(0, command.process().exitValue());
                    assertEquals(
                            "original",
                            new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
    }

    private PermissionService permissions(final ApprovalGate gate) {
        final var service = new PermissionService(root) {};
        service.setGate(gate);
        return service;
    }

    private SessionSandbox sandbox(final PermissionService permissions, final List<String> denyRead) throws Exception {
        final var project = root.resolve("project");
        Files.createDirectories(project);
        final var configuration = new SandboxConfiguration(
                true,
                new SandboxHelperConfiguration(""),
                new SandboxFilesystemConfiguration(
                        List.of(project.toString()), List.of(project.toString()), denyRead, List.of()),
                new SandboxNetworkConfiguration(List.of()),
                List.of(),
                new SandboxMacOsConfiguration(false),
                new SandboxEnvironmentConfiguration(List.of(), Map.of()),
                new SandboxUnixConfiguration(List.of()));
        return new SessionSandbox(configuration, project, project, permissions, root.resolve("scratch"));
    }
}
