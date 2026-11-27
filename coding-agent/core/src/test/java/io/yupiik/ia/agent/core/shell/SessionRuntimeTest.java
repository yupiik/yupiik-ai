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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.SessionSandbox;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SessionRuntimeFactory} isolation: every session gets its own
 * permissions, feedback gate, usage tracker, todo list and chat settings, and
 * nothing leaks from one tab into another (the original motivation for the
 * tab work: unrelated processes shared nothing; tabs must share the process
 * budget but nothing conversation-scoped).
 */
@ExtendWith(TestJsonMapper.class)
@FusionSupport(modules = SessionRuntimeTest.SessionConfigurationModule.class)
class SessionRuntimeTest {

    @TempDir
    static Path storageHome;

    @Fusion
    CodingAgentConfiguration configuration;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) throws IOException {
        this.mapper = mapper;
        // The class-owned configuration has a stable path; every test still starts with empty storage.
        try (final var paths = Files.walk(storageHome)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(storageHome)) {
                    Files.delete(path);
                }
            }
        }
    }

    private SessionRuntimeFactory factory() {
        return new SessionRuntimeFactory(
                null,
                null,
                null,
                configuration,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                mapper,
                null,
                Clock.systemUTC(),
                new PrecisionDurationMarkerProvider(),
                new BackoffSleeper(),
                null,
                null,
                null, // memory production policy
                null, // quota store
                null,
                null);
    }

    @Test
    void actualModeSwitchRevokesSandboxGrantsIndependentlyOfToolPermissions() throws Exception {
        for (final var permissionMode : List.of(PermissionMode.ASK, PermissionMode.AUTO_EDIT, PermissionMode.YOLO)) {
            final var permissions = new PermissionService(configuration, storageHome);
            permissions.setPermissionMode(permissionMode);
            permissions.grantSession("unrelated-tool");
            final var decision = new AtomicReference<>(ApprovalGate.Result.ALLOW_SESSION);
            permissions.setGate(request -> decision.get());
            try (final var sandbox = sandbox(permissions)) {
                final var runtime =
                        new SessionRuntime(null, permissions, null, null, null, null, null, null, storageHome, sandbox);
                final var outside = storageHome.resolve("outside");
                final var destination = URI.create("https://8.8.8.8");
                for (final var next : List.of(AgentMode.BUILD, AgentMode.PLAN)) {
                    decision.set(ApprovalGate.Result.ALLOW_SESSION);
                    sandbox.request("filesystem", outside.toString(), true);
                    sandbox.requireNetwork(destination);
                    decision.set(ApprovalGate.Result.DENY);

                    // Reasserting the effective modes preserves capabilities.
                    runtime.permissions().setMode(permissions.mode());
                    permissions.setPermissionMode(permissionMode);
                    sandbox.requireRead(outside);
                    sandbox.requireWrite(outside);
                    sandbox.requireNetwork(destination);

                    permissions.setPermissionMode(permissionMode);
                    runtime.permissions().setMode(next);
                    assertThrows(SecurityException.class, () -> sandbox.requireRead(outside));
                    assertThrows(SecurityException.class, () -> sandbox.requireWrite(outside));
                    assertThrows(SecurityException.class, () -> sandbox.requireNetwork(destination));
                    // Only dynamic sandbox capabilities were removed; configured policy survives.
                    sandbox.requireRead(storageHome.resolve("project"));
                    sandbox.requireNetwork(URI.create("https://1.1.1.1"));
                    assertTrue(permissions.sessionGrants().isEmpty());
                }
            }
        }
    }

    @Test
    void permissionModeSwitchRevokesGrantsAndReenablesConfiguredSandbox() throws Exception {
        final var permissions = new PermissionService(configuration, storageHome);
        final var decision = new AtomicReference<>(ApprovalGate.Result.ALLOW_SESSION);
        permissions.setGate(request -> decision.get());
        try (final var sandbox = sandbox(permissions)) {
            final var runtime =
                    new SessionRuntime(null, permissions, null, null, null, null, null, null, storageHome, sandbox);
            final var outside = storageHome.resolve("outside");
            sandbox.request("filesystem", outside.toString(), true);
            sandbox.request("network", "8.8.8.8:443", false);
            permissions.grantSession("write");
            runtime.permissions().setPermissionMode(PermissionMode.FULL_YOLO);
            assertFalse(sandbox.enabled());
            sandbox.requireWrite(outside);
            sandbox.requireNetwork(URI.create("https://127.0.0.1"));
            decision.set(ApprovalGate.Result.DENY);
            runtime.permissions().setPermissionMode(PermissionMode.YOLO);
            assertTrue(sandbox.enabled());
            assertTrue(permissions.sessionGrants().isEmpty());
            assertThrows(SecurityException.class, () -> sandbox.requireRead(outside));
            assertThrows(SecurityException.class, () -> sandbox.requireNetwork(URI.create("https://8.8.8.8")));
            sandbox.requireRead(storageHome.resolve("project"));
            sandbox.requireNetwork(URI.create("https://1.1.1.1"));
        }
    }

    @Test
    void permissionModeSwitchExpiresPendingSandboxApprovals() throws Exception {
        pendingSandboxApprovalsExpire(true);
    }

    @Test
    void modeSwitchExpiresPendingSandboxApprovals() throws Exception {
        pendingSandboxApprovalsExpire(false);
    }

    private void pendingSandboxApprovalsExpire(final boolean permissionModeChange) throws Exception {
        for (final var result : List.of(ApprovalGate.Result.ALLOW_ONCE, ApprovalGate.Result.ALLOW_SESSION)) {
            for (final var operation : List.of("read", "filesystem", "network", "automatic-network", "launch")) {
                final var permissions = new PermissionService(configuration, storageHome);
                final var entered = new CountDownLatch(1);
                final var release = new CountDownLatch(1);
                permissions.setGate(request -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (final InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    return result;
                });
                try (final var sandbox = sandbox(permissions);
                        final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    final var runtime = new SessionRuntime(
                            null, permissions, null, null, null, null, null, null, storageHome, sandbox);
                    final var target = storageHome.resolve("outside");
                    final var pending = executor.submit(() -> assertThrows(SecurityException.class, () -> {
                        switch (operation) {
                            case "read" -> sandbox.requireRead(target);
                            case "filesystem" -> sandbox.request(operation, target.toString(), true);
                            case "network" -> sandbox.request(operation, "8.8.8.8:443", false);
                            case "automatic-network" -> sandbox.requireNetwork(URI.create("https://8.8.8.8"));
                            default -> sandbox.launch(new ProcessBuilder("never-execute").directory(target.toFile()));
                        }
                    }));
                    entered.await();
                    try {
                        if (permissionModeChange) {
                            runtime.permissions().setPermissionMode(PermissionMode.FULL_YOLO);
                            runtime.permissions().setPermissionMode(PermissionMode.ASK);
                        } else {
                            runtime.permissions().setMode(AgentMode.BUILD);
                        }
                    } finally {
                        release.countDown();
                    }
                    pending.get();
                    permissions.setGate(request -> ApprovalGate.Result.DENY);
                    assertThrows(SecurityException.class, () -> sandbox.requireRead(target));
                    assertThrows(SecurityException.class, () -> sandbox.requireWrite(target));
                    assertThrows(SecurityException.class, () -> sandbox.requireNetwork(URI.create("https://8.8.8.8")));
                }
            }
        }
    }

    private SessionSandbox sandbox(final PermissionService permissions) throws Exception {
        final var project = Files.createDirectories(storageHome.resolve("project"));
        return new SessionSandbox(
                configuration.sandbox(), project, project, permissions, storageHome.resolve("scratch"));
    }

    @Test
    void launchSandboxOverrideIsSessionLocalAndRetainsPolicy() {
        final var factory = factory();
        final var inherited = factory.create("inherited", "inherited", storageHome);
        try {
            final var disabled = factory.create("disabled", "disabled", storageHome, false);
            try {
                final var enabled = factory.create("enabled", "enabled", null, storageHome, true);
                try {
                    assertTrue(inherited.sandbox().enabled());
                    assertFalse(disabled.sandbox().enabled());
                    assertTrue(enabled.sandbox().enabled());
                    assertTrue(configuration.sandbox().enabled(), "launch overrides must not mutate global policy");
                    final var blocked = URI.create("https://8.8.8.8");
                    disabled.sandbox().requireNetwork(blocked);
                    assertThrows(
                            SecurityException.class, () -> enabled.sandbox().requireNetwork(blocked));
                    enabled.sandbox().requireNetwork(URI.create("https://1.1.1.1"));
                    disabled.permissions().setPermissionMode(PermissionMode.FULL_YOLO);
                    disabled.permissions().setPermissionMode(PermissionMode.ASK);
                    assertFalse(disabled.sandbox().enabled(), "mode changes must retain the launch-time override");
                } finally {
                    enabled.close();
                }
            } finally {
                disabled.close();
            }
        } finally {
            inherited.close();
        }
    }

    @Test
    void routeApprovalsAreSessionOwnedAndClearedOnDisposal() {
        final var factory = factory();
        final var first = factory.create("k1", "s1", storageHome);
        final var second = factory.create("k2", "s2", storageHome);
        final var routes = first.permissions().subagentRoutes();
        assertTrue(routes.approve(
                "$private/main", "$external/model", request -> ApprovalGate.Result.ALLOW_SESSION, () -> false));
        assertTrue(routes.allows("$external/model"));
        assertFalse(second.permissions().subagentRoutes().allows("$external/model"));
        first.close();
        assertFalse(routes.allows("$external/model"));
        assertTrue(routes.routes().isEmpty());
        assertFalse(factory.create("k1", "s1", storageHome)
                .permissions()
                .subagentRoutes()
                .allows("$external/model"));
        second.close();
    }

    @Test
    void permissionsAndTodosAreIsolatedPerSession() {
        final var factory = factory();
        final var first = factory.create("k1", "s1", storageHome);
        final var second = factory.create("k2", "s2", storageHome);

        assertNotSame(first.permissions(), second.permissions());
        first.permissions().setMode(AgentMode.BUILD);
        assertEquals(AgentMode.BUILD, first.permissions().mode());
        assertNotSame(second.permissions().mode(), AgentMode.BUILD, "mode of one tab must not leak to the other");

        first.todos().replace(List.of(new TodoItem("only in tab one", "pending")));
        assertEquals(1, first.todos().all().size());
        assertTrue(second.todos().all().isEmpty(), "todos of one tab must not leak to the other");

        assertNotSame(first.usage(), second.usage());
        assertNotSame(first.feedback(), second.feedback());
        assertNotSame(first.loop(), second.loop());
    }

    @Test
    void todosArePersistedPerSessionKey() throws Exception {
        final var factory = factory();
        factory.create("k1", "s1", storageHome).todos().replace(List.of(new TodoItem("step", "pending")));

        final var file = storageHome.resolve("todos-k1.json");
        assertTrue(Files.isRegularFile(file), "the tab todo list is stored under its own key");
        assertFalse(Files.exists(storageHome.resolve("todos-k2.json")));

        // a later runtime for the same key reloads its own list, a different key starts empty
        assertEquals(
                1, factory.create("k1", "s1-again", storageHome).todos().all().size());
        assertTrue(factory.create("k2", "s2", storageHome).todos().all().isEmpty());
    }

    @Test
    void sessionShapedKeysPersistTodosInTheSessionFolder() throws Exception {
        final var factory = factory();
        final var key = "s1a0e47e830a";
        factory.create(key, "s1", storageHome).todos().replace(List.of(new TodoItem("step", "pending")));

        final var file = storageHome.resolve("sessions").resolve(key).resolve("todos.json");
        assertTrue(Files.isRegularFile(file), "a session-shaped key stores todos in the session folder");
        assertFalse(Files.exists(storageHome.resolve("todos-" + key + ".json")), "no legacy root file anymore");

        // a later runtime for the same key reloads its own list
        assertEquals(
                1, factory.create(key, "s1-again", storageHome).todos().all().size());
    }

    @Test
    void chatSettingsArePerSessionAndSharedWithTheLoop() {
        final var factory = factory();
        final var first = factory.create("k1", "s1", storageHome);
        final var second = factory.create("k2", "s2", storageHome);

        assertSame(first.loop().chatSettings(), first.chatSettings(), "the runtime exposes the loop's settings");
        first.updateChatSettings(first.chatSettings().withModel("model-a"));
        assertEquals("model-a", first.chatSettings().model());
        assertNull(second.chatSettings().model(), "model selection of one tab must not leak to the other");
    }

    public static class SessionConfigurationModule extends ConfigurationTestModule {
        public SessionConfigurationModule() {
            super(Map.of(
                    "yca.storageHome", storageHome.toString(),
                    "yca.mode", "PLAN",
                    "yca.sandbox.enabled", "true",
                    "yca.sandbox.filesystem.allowRead",
                            storageHome.resolve("project").toString(),
                    "yca.sandbox.filesystem.allowWrite",
                            storageHome.resolve("project").toString(),
                    "yca.sandbox.filesystem.denyRead", "",
                    "yca.sandbox.filesystem.denyWrite", "",
                    "yca.sandbox.network.allowedDomains", "1.1.1.1:443"));
        }
    }
}
