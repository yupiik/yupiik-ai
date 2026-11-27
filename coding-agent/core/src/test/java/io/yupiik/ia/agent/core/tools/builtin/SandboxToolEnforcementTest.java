/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.Sandbox;
import io.yupiik.ia.agent.core.sandbox.SandboxPaths;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = ConfigurationTestModule.class)
class SandboxToolEnforcementTest {
    @TempDir
    Path root;

    @Test
    void ordinaryProjectOperationsStillWork() throws IOException {
        try (final var sandbox = new TestSandbox(root)) {
            final var context = ToolContext.empty(root, sandbox);
            assertTrue(new WriteFileTool()
                    .execute(Map.of("path", "ok.txt", "content", "before"), context)
                    .success());
            assertTrue(new ReadFileTool()
                    .execute(Map.of("path", "ok.txt"), context)
                    .output()
                    .contains("before"));
            assertTrue(new EditFileTool()
                    .execute(Map.of("path", "ok.txt", "oldString", "before", "newString", "after"), context)
                    .success());
            assertEquals("after", Files.readString(root.resolve("ok.txt")));
            assertTrue(
                    new ApplyPatchTool().execute(Map.of("patch", """
                    *** Begin Patch
                    *** Add File: nested/new.txt
                    +created
                    *** End Patch
                    """), context).success());
            assertEquals("created\n", Files.readString(root.resolve("nested/new.txt")));
        }
    }

    @Test
    void explicitAbsolutePathsAndMissingTargetsCannotBypassChecks(@TempDir final Path outside) throws IOException {
        Files.writeString(outside.resolve("secret.txt"), "secret");
        try (final var sandbox = new TestSandbox(root)) {
            final var context = ToolContext.empty(root, sandbox);
            assertThrows(
                    SecurityException.class,
                    () -> new ReadFileTool()
                            .execute(
                                    Map.of("path", outside.resolve("secret.txt").toString()), context));
            assertThrows(
                    SecurityException.class,
                    () -> new WriteFileTool()
                            .execute(
                                    Map.of(
                                            "path",
                                            outside.resolve("missing/new.txt").toString(),
                                            "content",
                                            "leak"),
                                    context));
            assertThrows(
                    SecurityException.class,
                    () -> new EditFileTool()
                            .execute(
                                    Map.of(
                                            "path",
                                            outside.resolve("secret.txt").toString(),
                                            "oldString",
                                            "secret",
                                            "newString",
                                            "leak"),
                                    context));
            assertThrows(
                    SecurityException.class,
                    () -> new GlobTool().execute(Map.of("path", outside.toString(), "pattern", "**/*"), context));
            assertThrows(
                    SecurityException.class,
                    () -> new GrepTool().execute(Map.of("path", outside.toString(), "pattern", "secret"), context));
            assertFalse(Files.exists(outside.resolve("missing")));
            assertEquals("secret", Files.readString(outside.resolve("secret.txt")));
        }
    }

    @Test
    void recursiveDiscoveryPrunesDenialsAndSymlinkTargets(@TempDir final Path outside) throws IOException {
        Files.createDirectories(root.resolve("denied"));
        Files.writeString(root.resolve("denied/secret.txt"), "needle private");
        Files.writeString(root.resolve("visible.txt"), "needle public");
        Files.writeString(outside.resolve("external.txt"), "needle external");
        Files.createSymbolicLink(root.resolve("linked.txt"), outside.resolve("external.txt"));
        Files.createSymbolicLink(root.resolve("linked-dir"), outside);
        try (final var sandbox = new TestSandbox(root)) {
            final var context = ToolContext.empty(root, sandbox);
            for (final var result : List.of(
                    new ReadFileTool().execute(Map.of("path", "."), context),
                    new GlobTool().execute(Map.of("pattern", "**/*.txt"), context),
                    new GrepTool().execute(Map.of("pattern", "needle"), context))) {
                assertTrue(result.success(), result.output());
                assertTrue(result.output().contains("visible.txt"), result.output());
                assertFalse(result.output().contains("secret"), result.output());
                assertFalse(result.output().contains("denied"), result.output());
                assertFalse(result.output().contains("linked"), result.output());
                assertFalse(result.output().contains("external"), result.output());
            }
            assertThrows(
                    SecurityException.class, () -> new ReadFileTool().execute(Map.of("path", "linked.txt"), context));
            assertThrows(
                    SecurityException.class,
                    () -> new WriteFileTool()
                            .execute(
                                    Map.of(
                                            "path",
                                            root.resolve("linked-dir/new/deep.txt")
                                                    .toString(),
                                            "content",
                                            "leak"),
                                    context));
            assertFalse(Files.exists(outside.resolve("new")));
        }
    }

    @Test
    void absoluteSymlinkDotDotPathsAreRejectedBeforeIo(@TempDir final Path outside) throws IOException {
        Files.createDirectories(outside.resolve("child"));
        Files.writeString(outside.resolve("secret.txt"), "external secret");
        Files.createSymbolicLink(root.resolve("link"), outside.resolve("child"));
        final var ambiguous = root.resolve("link/../secret.txt").toString();
        try (final var sandbox = new TestSandbox(root)) {
            final var context = ToolContext.empty(root, sandbox);
            assertThrows(SecurityException.class, () -> new ReadFileTool().execute(Map.of("path", ambiguous), context));
            assertThrows(
                    SecurityException.class,
                    () -> new WriteFileTool().execute(Map.of("path", ambiguous, "content", "must not write"), context));
            assertThrows(
                    SecurityException.class,
                    () -> new EditFileTool()
                            .execute(
                                    Map.of("path", ambiguous, "oldString", "external", "newString", "changed"),
                                    context));
            assertThrows(
                    SecurityException.class,
                    () -> new GrepTool().execute(Map.of("path", ambiguous, "pattern", "secret"), context));
            assertThrows(
                    SecurityException.class,
                    () -> new GlobTool()
                            .execute(Map.of("path", root.resolve("link/..").toString(), "pattern", "*.txt"), context));
            assertFalse(new SandboxAccessTool()
                    .execute(Map.of("kind", "filesystem", "target", ambiguous), context)
                    .success());
            assertEquals("external secret", Files.readString(outside.resolve("secret.txt")));
        }
    }

    @Test
    void patchDenialPrecedesEveryMutationAndCoversMoveDestination() throws IOException {
        Files.createDirectories(root.resolve("denied"));
        Files.writeString(root.resolve("denied/secret.txt"), "secret\n");
        Files.writeString(root.resolve("source.txt"), "before\n");
        try (final var sandbox = new TestSandbox(root)) {
            final var context = ToolContext.empty(root, sandbox);
            final var result = new ApplyPatchTool().execute(Map.of("patch", """
                    *** Begin Patch
                    *** Add File: innocent.txt
                    +must not be created
                    *** Delete File: denied/secret.txt
                    *** End Patch
                    """), context);
            assertFalse(result.success());
            assertTrue(result.fileMutations().isEmpty());
            assertFalse(Files.exists(root.resolve("innocent.txt")));
            assertEquals("secret\n", Files.readString(root.resolve("denied/secret.txt")));
            final var move = new ApplyPatchTool().execute(Map.of("patch", """
                    *** Begin Patch
                    *** Update File: source.txt
                    *** Move to: denied/moved.txt
                    @@
                    -before
                    +after
                    *** End Patch
                    """), context);
            assertFalse(move.success());
            assertEquals("before\n", Files.readString(root.resolve("source.txt")));
            assertFalse(Files.exists(root.resolve("denied/moved.txt")));
        }
    }

    @Test
    void editAndPatchNeedReadAsWellAsWritePermission() throws IOException {
        Files.writeString(root.resolve("write-only.txt"), "secret");
        try (final var sandbox = new TestSandbox(root)) {
            final var context = ToolContext.empty(root, sandbox);
            assertThrows(
                    SecurityException.class,
                    () -> new EditFileTool()
                            .execute(
                                    Map.of("path", "write-only.txt", "oldString", "secret", "newString", "changed"),
                                    context));
            assertFalse(
                    new ApplyPatchTool().execute(Map.of("patch", """
                    *** Begin Patch
                    *** Update File: write-only.txt
                    @@
                    -secret
                    +changed
                    *** End Patch
                    """), context).success());
            assertTrue(new WriteFileTool()
                    .execute(Map.of("path", "write-only.txt", "content", "overwritten without reading"), context)
                    .success());
            assertEquals("overwritten without reading", Files.readString(root.resolve("write-only.txt")));
        }
    }

    @Test
    void missingReadDoesNotSuggestDeniedNames() {
        try (final var sandbox = new TestSandbox(root)) {
            final var result =
                    new ReadFileTool().execute(Map.of("path", "missing.txt"), ToolContext.empty(root, sandbox));
            assertFalse(result.success());
            assertFalse(result.output().contains("Did you mean"));
        }
    }

    @Test
    void accessRequestsArePreciseAndNeverLaunchCommands(@TempDir final Path outside) {
        try (final var sandbox = new TestSandbox(root)) {
            final var context = ToolContext.empty(root, sandbox);
            final var tool = new SandboxAccessTool();
            assertTrue(tool.execute(Map.of("kind", "filesystem", "target", "extra", "write", true), context)
                    .success());
            assertEquals(
                    new Request("filesystem", root.resolve("extra").toString(), true), sandbox.requests.getFirst());
            assertTrue(tool.execute(Map.of("kind", "network", "target", "example.org"), context)
                    .success());
            assertEquals(new Request("network", "example.org", false), sandbox.requests.getLast());
            assertFalse(tool.execute(Map.of("kind", "filesystem", "target", "denied", "write", true), context)
                    .success());
            assertThrows(SecurityException.class, () -> sandbox.requireWrite(root.resolve("denied/new.txt")));
            assertFalse(tool.execute(Map.of("kind", "network", "target", "example.org", "write", true), context)
                    .success());
            assertFalse(tool.execute(Map.of("kind", "filesystem", "target", "extra", "write", "true"), context)
                    .success());
            assertFalse(tool.execute(Map.of("kind", "integration", "target", "external"), context)
                    .success());
            assertTrue(tool.execute(Map.of("kind", "filesystem", "target", outside.toString()), context)
                    .success());
            assertEquals(3, sandbox.requests.size());
            assertEquals(0, sandbox.launches);
        }
    }

    @Test
    void webToolsRejectPrivateDestinationsEvenAfterNetworkGrant() {
        try (final var sandbox = new TestSandbox(root)) {
            final var context = ToolContext.empty(root, sandbox);
            sandbox.request("network", "127.0.0.1:80", false);
            // The ambient client is absent: sandboxed fetch must never touch it.
            for (final var url : List.of("http://127.0.0.1/", "http://169.254.169.254/")) {
                final var fetch = new WebFetchTool(null, null).execute(Map.of("url", url), context);
                assertFalse(fetch.success());
                assertTrue(fetch.output().contains("non-public"), fetch.output());
            }
            assertEquals(2, sandbox.networkChecks);
            final var search = new WebSearchTool().execute(Map.of("query", "anything"), context);
            assertFalse(search.success());
            assertTrue(search.output().contains("disabled"), search.output());
        }
    }

    @Test
    void unadaptedKnownToolsAndExternalToolsAreDeniedBeforeExecution(
            @Fusion final CodingAgentConfiguration configuration) {
        try (final var sandbox = new TestSandbox(root)) {
            final var context = ToolContext.empty(root, sandbox);
            final var ran = new AtomicBoolean();
            final var external = new EmbeddedTool() {
                @Override
                public String name() {
                    return "read";
                }

                @Override
                public String description(final Path workdir) {
                    return "Claims to be safe";
                }

                @Override
                public ToolCategory category() {
                    return ToolCategory.READ;
                }

                @Override
                public Map<String, Object> schema() {
                    return Map.of();
                }

                @Override
                public boolean readonly() {
                    return true;
                }

                @Override
                public ToolResult execute(final Map<String, Object> args, final ToolContext ctx) {
                    ran.set(true);
                    return ToolResult.ok("must not run");
                }
            };
            final var service = new ToolExecutionService(configuration, null, List.of());
            for (final var tool : List.of(
                    external,
                    new SkillTool(null),
                    new MavenKnowledgeContentTool(),
                    new MavenRepositoryKnowledgeTool(null),
                    new MemoryWriteTool(null))) {
                final var result = service.execute(tool, Map.of(), context);
                assertFalse(result.success(), tool.name());
                assertTrue(result.output().contains("Sandbox restriction"), result.output());
                assertTrue(result.output().contains("exact opt-in"), result.output());
            }
            assertFalse(ran.get());
        }
    }

    private record Request(String kind, String target, boolean write) {}

    /** Real path rule matching with deterministic, synchronous capability requests and no subprocess backend. */
    private static final class TestSandbox implements Sandbox {
        private final Path root;
        private final SandboxPaths paths;
        private final List<Request> requests = new ArrayList<>();
        private int networkChecks;
        private int launches;

        private TestSandbox(final Path root) {
            this.root = root;
            paths = new SandboxPaths(
                    List.of(root.toString()),
                    List.of(root.toString()),
                    List.of(
                            root.resolve("denied").toString(),
                            root.resolve("write-only.txt").toString()),
                    List.of(root.resolve("denied").toString()),
                    root,
                    root,
                    root);
        }

        @Override
        public Sandbox approvalView(final PermissionService permissions) {
            return this;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Path tempDir() {
            return root;
        }

        @Override
        public void requireRead(final Path path) {
            if (!paths.allowed(path, false)) {
                throw new SecurityException("Sandbox read denied: " + path);
            }
        }

        @Override
        public void requireWrite(final Path path) {
            // Keep read/write authorization independent, including a genuine write-only fixture.
            if (path.equals(root.resolve("write-only.txt"))) {
                return;
            }
            if (!paths.allowed(path, true)) {
                throw new SecurityException("Sandbox write denied: " + path);
            }
        }

        @Override
        public void requireNetwork(final URI uri) {
            networkChecks++;
        }

        @Override
        public void request(final String kind, final String target, final boolean write) {
            if ("filesystem".equals(kind) && paths.denied(Path.of(target), write)) {
                throw new SecurityException("Configured deny cannot be overridden");
            }
            requests.add(new Request(kind, target, write));
        }

        @Override
        public Command launch(final ProcessBuilder builder) {
            launches++;
            throw new AssertionError("Capability requests must not launch commands");
        }

        @Override
        public void revoke() {
            requests.clear();
        }

        @Override
        public void close() {}
    }
}
