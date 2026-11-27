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
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = AdditionalDirectoryClientToolsTest.ConfigurationModule.class)
class AdditionalDirectoryClientToolsTest {
    @TempDir
    static Path home;

    @Fusion
    private CodingAgentConfiguration config;

    @Test
    void additionalRootsAreLexicalContextNotGrantsAndDoNotBypassPlanOrToolCeilings() throws Exception {
        final var root = Files.createDirectory(home.resolve("A"));
        final var extra = Files.createDirectory(home.resolve("B"));
        final var permissions = new PermissionService(config, root);
        permissions.setMode(AgentMode.BUILD);
        permissions.setPermissionMode(PermissionMode.ASK);
        permissions.setGate(request -> ApprovalGate.Result.DENY);
        final var calls = new ArrayList<Map<String, Object>>();
        final var policy = new ArrayList<Map<String, Object>>();
        try (final var adapter = new AcpClientTools(
                "s",
                root,
                Map.of("fs", Map.of("writeTextFile", true)),
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                (method, params, timeout, cancelled, late) -> {
                    calls.add(params);
                    return CompletableFuture.completedFuture(Map.of());
                },
                (context, id) -> {})) {
            adapter.configureAdditionalDirectories(List.of(extra));
            assertTrue(permissions.sessionGrants().isEmpty());
            final var tool = adapter.tools().getFirst();
            final var context = context(root, permissions, null);
            final var args =
                    Map.<String, Object>of("path", extra.resolve("file.txt").toString(), "content", "editor text");
            assertFalse(tool.execute(args, context).success());
            assertTrue(calls.isEmpty(), "Directory context must not approve an edit");
            permissions.setGate(request -> {
                final var checked = request.args();
                policy.add(checked);
                return ApprovalGate.Result.ALLOW_ONCE;
            });
            assertTrue(tool.execute(args, context).success());
            assertEquals(extra.resolve("file.txt").toString(), calls.getLast().get("path"));
            assertTrue(policy.stream()
                    .allMatch(checked -> extra.resolve("file.txt").toString().equals(checked.get("path"))));
            assertTrue(permissions.sessionGrants().isEmpty());
            assertTrue(tool.execute(Map.of("path", "relative.txt", "content", "primary"), context)
                    .success());
            assertEquals(
                    root.resolve("relative.txt").toString(), calls.getLast().get("path"));
            final var sent = calls.size();
            assertFalse(tool.execute(
                            Map.of("path", home.resolve("B-sibling/file.txt").toString(), "content", "no"), context)
                    .success());
            assertFalse(tool.execute(args, context(root, permissions, Set.of("read")))
                    .success());
            permissions.setMode(AgentMode.PLAN);
            permissions.setPermissionMode(PermissionMode.YOLO);
            assertFalse(tool.execute(args, context).success());
            assertEquals(sent, calls.size());
            assertThrows(IllegalStateException.class, () -> adapter.configureAdditionalDirectories(List.of()));
        }
    }

    private ToolContext context(final Path root, final PermissionService permissions, final Set<String> ceiling) {
        return new ToolContext(
                "s",
                null,
                null,
                permissions,
                null,
                null,
                null,
                null,
                null,
                () -> false,
                null,
                () -> false,
                root,
                new DisabledSandbox(root),
                1,
                ceiling,
                null,
                null);
    }

    public static class ConfigurationModule extends AcpConfigurationTestModule {
        public ConfigurationModule() {
            super(Map.of(
                    "yca.home",
                    home.toString(),
                    "yca.storageHome",
                    home.resolve("store").toString()));
        }
    }
}
