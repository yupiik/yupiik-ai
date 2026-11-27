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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** No real command, request timeout, sleep, executor, or process/global property mutation is used. */
@Timeout(5)
class AcpClientToolsTest {
    @TempDir
    static Path configurationHome;

    private static final String READ = "client_read_text_file";
    private static final String WRITE = "client_write_text_file";
    private static final String TERMINAL = "client_terminal";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(2);
    private static final BooleanSupplier NEVER = () -> false;
    private static final Map<String, Object> ALL =
            Map.of("fs", Map.of("readTextFile", true, "writeTextFile", true), "terminal", true);

    private void assertTerminalInvalidAndReleased(
            final Path root, final Channel channel, final CodingAgentConfiguration configuration) {
        try (final var adapter = adapter("s", root, ALL, channel)) {
            assertTrue(tool(adapter, TERMINAL)
                    .execute(
                            Map.of("command", "runner"),
                            context("s", root, permissions(root, configuration), NEVER, NEVER, null))
                    .error());
            assertEquals(1, channel.calls("terminal/release").size());
        }
        assertEquals(1, channel.calls("terminal/release").size());
    }

    private AcpClientTools adapter(
            final String session, final Path root, final Map<String, ?> capabilities, final Channel channel) {
        return new AcpClientTools(
                session, root, capabilities, REQUEST_TIMEOUT, CLEANUP_TIMEOUT, channel, (context, id) -> {
                    channel.embeddedContext.set(context);
                    channel.events.add("embed:" + id);
                });
    }

    private PermissionService permissions(final Path root, final CodingAgentConfiguration configuration) {
        final var permissions = new PermissionService(configuration, root);
        permissions.setMode(AgentMode.BUILD);
        permissions.setPermissionMode(PermissionMode.YOLO);
        return permissions;
    }

    private ToolContext context(
            final String session,
            final Path root,
            final PermissionService permissions,
            final BooleanSupplier cancelled,
            final BooleanSupplier callCancelled,
            final Set<String> ceiling) {
        return new ToolContext(
                session,
                null,
                null,
                permissions,
                null,
                null,
                null,
                null,
                null,
                cancelled,
                null,
                callCancelled,
                root,
                new DisabledSandbox(root),
                42,
                ceiling,
                null,
                null);
    }

    private EmbeddedTool tool(final AcpClientTools adapter, final String name) {
        return adapter.tools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private EmbeddedTool localTool(final String name) {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description(final Path root) {
                return "local sentinel";
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
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                return ToolResult.ok("local");
            }
        };
    }

    private static void await(final CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(1, TimeUnit.SECONDS), "worker failed to reach deterministic rendezvous");
    }

    private record Call(
            String method,
            Map<String, Object> params,
            Duration timeout,
            BooleanSupplier cancelled,
            Consumer<Object> lateResult,
            CompletableFuture<Object> result) {}

    private static final class Channel implements AcpClientTools.Requests, AutoCloseable {
        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final Map<String, Consumer<Call>> routes = new ConcurrentHashMap<>();
        private final AtomicInteger terminalIds = new AtomicInteger();
        private final AtomicReference<ToolContext> embeddedContext = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public CompletionStage<Object> request(
                final String method,
                final Map<String, Object> params,
                final Duration timeout,
                final BooleanSupplier cancelled,
                final Consumer<Object> lateResult) {
            final var call = new Call(method, params, timeout, cancelled, lateResult, new CompletableFuture<>());
            calls.add(call);
            events.add(method);
            final var route = routes.get(method);
            if (route != null) {
                route.accept(call);
            } else {
                call.result.complete(
                        switch (method) {
                            case "fs/read_text_file" -> Map.of("content", "client buffer");
                            case "terminal/create" -> Map.of("terminalId", "term-" + terminalIds.incrementAndGet());
                            case "terminal/wait_for_exit" -> Map.of("exitCode", 0);
                            case "terminal/output" ->
                                Map.of(
                                        "output",
                                        "client output",
                                        "truncated",
                                        false,
                                        "exitStatus",
                                        Map.of("exitCode", 0));
                            default -> Map.of();
                        });
            }
            return call.result;
        }

        private List<String> methods() {
            return calls.stream().map(Call::method).toList();
        }

        private List<Call> calls(final String method) {
            return calls.stream().filter(call -> call.method.equals(method)).toList();
        }

        private Call call(final String method) {
            return calls(method).getFirst();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class Worker implements AutoCloseable {
        private final CompletableFuture<ToolResult> completed = new CompletableFuture<>();
        private final AtomicBoolean interrupted = new AtomicBoolean();
        private final Thread thread;

        private Worker(final Supplier<ToolResult> task) {
            thread = Thread.ofVirtual().start(() -> {
                try {
                    final var result = task.get();
                    interrupted.set(Thread.currentThread().isInterrupted());
                    completed.complete(result);
                } catch (final Throwable failure) {
                    completed.completeExceptionally(failure);
                }
            });
        }

        private ToolResult result() throws Exception {
            return completed.get(1, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws InterruptedException {
            thread.interrupt();
            assertTrue(thread.join(Duration.ofSeconds(1)), "tool worker did not terminate");
        }
    }

    private void verifyDeniedName(
            final Path root, final String name, final CodingAgentConfiguration deniedConfiguration) {
        final var channel = new Channel();
        final var permissions = permissions(root, deniedConfiguration);
        final var context = context("s", root, permissions, NEVER, NEVER, null);
        try (final var adapter = adapter("s", root, ALL, channel)) {
            final var kind = name.equals("read") || name.equals(READ)
                    ? READ
                    : name.equals("write_file") || name.equals(WRITE) ? WRITE : TERMINAL;
            final var args = kind.equals(READ)
                    ? Map.<String, Object>of("path", "a")
                    : kind.equals(WRITE)
                            ? Map.<String, Object>of("path", "a", "content", "x")
                            : Map.<String, Object>of("command", "runner", "args", List.of("--help"));
            assertTrue(tool(adapter, kind).execute(args, context).error(), name);
            assertTrue(channel.calls.isEmpty(), name);
        }
    }

    @Nested
    @FusionSupport(modules = ReadDeniedModule.class)
    class ReadDenied {

        @Test
        void deniesBeforeCallingClient(
                @TempDir final Path root, @Fusion final CodingAgentConfiguration deniedConfiguration) {
            verifyDeniedName(root, "read", deniedConfiguration);
        }
    }

    public static class ReadDeniedModule extends ConfigurationModule {
        public ReadDeniedModule() {
            super(Map.of("yca.permissions.deny", "read"));
        }
    }

    @Nested
    @FusionSupport(modules = ClientReadDeniedModule.class)
    class ClientReadDenied {

        @Test
        void deniesBeforeCallingClient(
                @TempDir final Path root, @Fusion final CodingAgentConfiguration deniedConfiguration) {
            verifyDeniedName(root, "client_read_text_file", deniedConfiguration);
        }
    }

    public static class ClientReadDeniedModule extends ConfigurationModule {
        public ClientReadDeniedModule() {
            super(Map.of("yca.permissions.deny", "client_read_text_file"));
        }
    }

    @Nested
    @FusionSupport(modules = WriteDeniedModule.class)
    class WriteDenied {

        @Test
        void deniesBeforeCallingClient(
                @TempDir final Path root, @Fusion final CodingAgentConfiguration deniedConfiguration) {
            verifyDeniedName(root, "write_file", deniedConfiguration);
        }
    }

    public static class WriteDeniedModule extends ConfigurationModule {
        public WriteDeniedModule() {
            super(Map.of("yca.permissions.deny", "write_file"));
        }
    }

    @Nested
    @FusionSupport(modules = ClientWriteDeniedModule.class)
    class ClientWriteDenied {

        @Test
        void deniesBeforeCallingClient(
                @TempDir final Path root, @Fusion final CodingAgentConfiguration deniedConfiguration) {
            verifyDeniedName(root, "client_write_text_file", deniedConfiguration);
        }
    }

    public static class ClientWriteDeniedModule extends ConfigurationModule {
        public ClientWriteDeniedModule() {
            super(Map.of("yca.permissions.deny", "client_write_text_file"));
        }
    }

    @Nested
    @FusionSupport(modules = BashDeniedModule.class)
    class BashDenied {

        @Test
        void deniesBeforeCallingClient(
                @TempDir final Path root, @Fusion final CodingAgentConfiguration deniedConfiguration) {
            verifyDeniedName(root, "bash", deniedConfiguration);
        }
    }

    public static class BashDeniedModule extends ConfigurationModule {
        public BashDeniedModule() {
            super(Map.of("yca.permissions.deny", "bash"));
        }
    }

    @Nested
    @FusionSupport(modules = ClientTerminalDeniedModule.class)
    class ClientTerminalDenied {

        @Test
        void deniesBeforeCallingClient(
                @TempDir final Path root, @Fusion final CodingAgentConfiguration deniedConfiguration) {
            verifyDeniedName(root, "client_terminal", deniedConfiguration);
        }
    }

    public static class ClientTerminalDeniedModule extends ConfigurationModule {
        public ClientTerminalDeniedModule() {
            super(Map.of("yca.permissions.deny", "client_terminal"));
        }
    }

    @Nested
    @FusionSupport(modules = CommandDeniedModule.class)
    class CommandDenied {

        @Test
        void deniesFullCommand(@TempDir final Path root, @Fusion final CodingAgentConfiguration deniedConfiguration) {
            final var channel = new Channel();
            final var permissions = permissions(root, deniedConfiguration);
            try (final var adapter = adapter("s", root, ALL, channel)) {
                final var result = tool(adapter, TERMINAL)
                        .execute(
                                Map.of("command", "git", "args", List.of("push", "origin")),
                                context("s", root, permissions, NEVER, NEVER, null));
                assertTrue(result.error());
                assertTrue(channel.calls.isEmpty());
            }
        }
    }

    public static class CommandDeniedModule extends ConfigurationModule {
        public CommandDeniedModule() {
            super(Map.of("yca.permissions.deny", "bash:git push*"));
        }
    }

    public static class ConfigurationModule extends AcpConfigurationTestModule {
        public ConfigurationModule() {
            this(Map.of());
        }

        protected ConfigurationModule(final Map<String, String> overrides) {
            super(values(overrides));
        }

        private static Map<String, String> values(final Map<String, String> overrides) {
            final var values = new HashMap<String, String>();
            values.put("yca.workingDir", configurationHome.toString());
            values.put("yca.projectDir", configurationHome.toString());
            values.put("yca.storageHome", configurationHome.resolve("storage").toString());
            values.put("yca.sandbox.enabled", "false");
            values.putAll(overrides);
            return values;
        }
    }

    @Nested
    @FusionSupport(modules = AcpClientToolsTest.ConfigurationModule.class)
    class Defaults {
        @Fusion
        private CodingAgentConfiguration configuration;

        @Fusion
        private AcpCommand.Conf commandOptions;

        @Test
        void eachCapabilityMustIndependentlyBeLiteralTrue(@TempDir final Path root) {
            final var absent = new Object();
            final var flags = Arrays.asList(absent, null, false, true, "true", 1, Map.of(), List.of());
            final var channel = new Channel();
            for (final var read : flags) {
                for (final var write : flags) {
                    for (final var terminal : flags) {
                        final var fs = new HashMap<String, Object>();
                        final var caps = new HashMap<String, Object>();
                        if (read != absent) {
                            fs.put("readTextFile", read);
                        }
                        if (write != absent) {
                            fs.put("writeTextFile", write);
                        }
                        if (terminal != absent) {
                            caps.put("terminal", terminal);
                        }
                        caps.put("fs", fs);
                        final var expected = new ArrayList<String>();
                        if (Boolean.TRUE.equals(read)) {
                            expected.add(READ);
                        }
                        if (Boolean.TRUE.equals(write)) {
                            expected.add(WRITE);
                        }
                        if (Boolean.TRUE.equals(terminal)) {
                            expected.add(TERMINAL);
                        }
                        try (final var adapter = adapter("s", root, caps, channel)) {
                            assertEquals(
                                    expected,
                                    adapter.tools().stream()
                                            .map(EmbeddedTool::name)
                                            .toList());
                        }
                    }
                }
            }
            for (final var caps : Arrays.<Map<String, ?>>asList(
                    null,
                    Map.of(),
                    Collections.singletonMap("fs", null),
                    Map.of("fs", true),
                    Map.of("fs", "{}"),
                    Map.of("fs", List.of()),
                    Map.of("readTextFile", true),
                    Map.of("mcp", ALL))) {
                try (final var adapter = adapter("s", root, caps, channel)) {
                    assertTrue(adapter.tools().isEmpty());
                }
            }
            assertTrue(channel.calls.isEmpty());
        }

        @Test
        void capabilitySnapshotDoesNotChangeAndToolsOverlayDoesNotShadowLocals(@TempDir final Path root) {
            final var fs = new HashMap<String, Object>(Map.of("readTextFile", true));
            final var caps = new HashMap<String, Object>(Map.of("fs", fs));
            final var channel = new Channel();
            try (final var adapter = adapter("s", root, caps, channel)) {
                fs.put("readTextFile", false);
                fs.put("writeTextFile", true);
                caps.put("terminal", true);
                assertEquals(
                        List.of(READ),
                        adapter.tools().stream().map(EmbeddedTool::name).toList());
                final var local = localTool("read");
                final var base = new ToolRegistry(null) {
                    @Override
                    public List<EmbeddedTool> tools() {
                        return List.of(local);
                    }
                };
                final var overlay = base.withTools(adapter.tools());
                assertSame(local, overlay.tool("read"));
                assertSame(adapter.tools().getFirst(), overlay.tool(READ));
                assertEquals(List.of(local), base.tools());
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> adapter.tools().clear());
                assertTrue(tool(adapter, READ).readonly());
                assertEquals(ToolCategory.READ, tool(adapter, READ).category());
                assertTrue(tool(adapter, READ).description(root).contains("unsaved editor buffers"));
                assertFalse(tool(adapter, READ).usable(root.resolve("other")));
            }
            assertTrue(channel.calls.isEmpty());
        }

        @Test
        void requiresResolvedRootAndBoundedPositiveTimeouts(@TempDir final Path root) {
            final var channel = new Channel();
            assertThrows(NullPointerException.class, () -> adapter("s", null, ALL, channel));
            assertThrows(IllegalArgumentException.class, () -> adapter("s", Path.of("relative"), ALL, channel));
            assertThrows(IllegalArgumentException.class, () -> adapter("s", root.resolve("a/.."), ALL, channel));
            assertThrows(IllegalArgumentException.class, () -> adapter(" ", root, ALL, channel));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AcpClientTools(
                            "s", root, ALL, Duration.ZERO, CLEANUP_TIMEOUT, channel, (context, id) -> {}));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AcpClientTools(
                            "s", root, ALL, REQUEST_TIMEOUT, Duration.ofSeconds(6), channel, (context, id) -> {}));
            assertTrue(channel.calls.isEmpty());
        }

        @Test
        void readsClientBuffersForExactSessionRootsAndUsesOneBasedLineLimit(@TempDir final Path root)
                throws IOException {
            final var channel = new Channel();
            final var a = Files.createDirectories(root.resolve("A"));
            final var b = Files.createDirectories(root.resolve("B"));
            Files.writeString(a.resolve("buffer.txt"), "saved disk A");
            Files.writeString(b.resolve("buffer.txt"), "saved disk B");
            channel.routes.put(
                    "fs/read_text_file",
                    call -> call.result.complete(
                            Map.of("content", "unsaved for " + call.params.get("sessionId") + "\n")));
            try (final var first = adapter("A", a, ALL, channel);
                    final var second = adapter("B", b, ALL, channel)) {
                for (final var session : List.of("A", "B")) {
                    final var dir = session.equals("A") ? a : b;
                    final var adapter = session.equals("A") ? first : second;
                    final var context = context(session, dir, permissions(dir, configuration), NEVER, NEVER, null);
                    final var result = tool(adapter, READ)
                            .execute(
                                    Map.of("path", "sub/../buffer.txt", "line", new BigDecimal("2.0"), "limit", 3L),
                                    context);
                    assertEquals("unsaved for " + session + "\n", result.output());
                    assertTrue(result.success());
                    final var call = channel.calls.getLast();
                    assertEquals("fs/read_text_file", call.method);
                    assertEquals(
                            Map.of(
                                    "sessionId",
                                    session,
                                    "path",
                                    dir.resolve("buffer.txt").toString(),
                                    "line",
                                    2,
                                    "limit",
                                    3),
                            call.params);
                    assertEquals(REQUEST_TIMEOUT, call.timeout);
                }
            }
            assertEquals("saved disk A", Files.readString(a.resolve("buffer.txt")));
            assertEquals("saved disk B", Files.readString(b.resolve("buffer.txt")));
        }

        @Test
        void writesAndTerminalsKeepSessionAndRootOnSharedConnection(@TempDir final Path root) {
            final var channel = new Channel();
            final var firstRoot = root.resolve("client-only-A");
            final var secondRoot = root.resolve("client-only-B");
            try (final var first = adapter("A", firstRoot, ALL, channel);
                    final var second = adapter("B", secondRoot, ALL, channel)) {
                for (final var session : List.of("A", "B")) {
                    final var dir = session.equals("A") ? firstRoot : secondRoot;
                    final var adapter = session.equals("A") ? first : second;
                    final var context = context(session, dir, permissions(dir, configuration), NEVER, NEVER, null);
                    assertTrue(tool(adapter, WRITE)
                            .execute(Map.of("path", "a.txt", "content", session), context)
                            .success());
                    final var write = channel.calls.getLast();
                    assertEquals(session, write.params.get("sessionId"));
                    assertEquals(dir.resolve("a.txt").toString(), write.params.get("path"));
                    assertTrue(tool(adapter, TERMINAL)
                            .execute(Map.of("command", "client-only-runner", "cwd", "sub"), context)
                            .success());
                    final var create = channel.calls("terminal/create").getLast();
                    assertEquals(session, create.params.get("sessionId"));
                    assertEquals(dir.resolve("sub").toString(), create.params.get("cwd"));
                    final var release = channel.calls("terminal/release").getLast();
                    assertEquals(session, release.params.get("sessionId"));
                    assertSame(context, channel.embeddedContext.get());
                }
            }
            assertEquals(2, channel.calls("terminal/release").size());
            assertFalse(channel.closed.get());
        }

        @Test
        void writesOnlyThroughClientIncludingEmptyTextAndNeverFallsBackToDisk(@TempDir final Path root)
                throws Exception {
            final var channel = new Channel();
            final var file = root.resolve("buffer.txt");
            Files.writeString(file, "saved contents");
            final var context = context("s", root, permissions(root, configuration), NEVER, NEVER, null);
            try (final var adapter = adapter("s", root, ALL, channel)) {
                for (final var text : List.of("", "unsaved \"text\"\n\u00e9\0")) {
                    final var result =
                            tool(adapter, WRITE).execute(Map.of("path", "buffer.txt", "content", text), context);
                    assertTrue(result.success(), result.output());
                    assertEquals(
                            Map.of("sessionId", "s", "path", file.toString(), "content", text),
                            channel.calls.getLast().params);
                    assertEquals("saved contents", Files.readString(file));
                }
                channel.routes.put(
                        "fs/write_text_file",
                        call -> call.result.completeExceptionally(
                                new IOException("secret write rejection password=do-not-show")));
                final var rejected =
                        tool(adapter, WRITE).execute(Map.of("path", "buffer.txt", "content", "new"), context);
                assertTrue(rejected.error());
                assertFalse(rejected.output().contains("password"));
                assertEquals("saved contents", Files.readString(file));
                channel.routes.put(
                        "fs/read_text_file", call -> call.result.completeExceptionally(new CancellationException()));
                assertTrue(tool(adapter, READ)
                        .execute(Map.of("path", "buffer.txt"), context)
                        .error());
                assertEquals("saved contents", Files.readString(file));
            }
        }

        @Test
        void rejectsCrossSessionRootMissingPermissionsEscapesAndCeilingsBeforeRequests(@TempDir final Path root) {
            final var channel = new Channel();
            final var permissions = permissions(root, configuration);
            final var valid = context("s", root, permissions, NEVER, NEVER, null);
            try (final var adapter = adapter("s", root, ALL, channel)) {
                final var read = tool(adapter, READ);
                for (final var context : List.of(
                        context("other", root, permissions, NEVER, NEVER, null),
                        context("s", root.resolve("other"), permissions, NEVER, NEVER, null),
                        context("s", root, null, NEVER, NEVER, null),
                        context("s", root, permissions, NEVER, NEVER, Set.of("read")),
                        context("s", root, permissions, () -> true, NEVER, null),
                        context("s", root, permissions, NEVER, () -> true, null))) {
                    assertTrue(read.execute(Map.of("path", "a"), context).error());
                }
                assertTrue(read.execute(Map.of("path", "a"), null).error());
                for (final var path :
                        List.of("../escape", root.resolveSibling("outside").toString())) {
                    assertTrue(read.execute(Map.of("path", path), valid).error());
                    assertTrue(tool(adapter, WRITE)
                            .execute(Map.of("path", path, "content", "x"), valid)
                            .error());
                    assertTrue(tool(adapter, TERMINAL)
                            .execute(Map.of("command", "runner", "cwd", path), valid)
                            .error());
                }
            }
            assertTrue(channel.calls.isEmpty());
        }

        @Test
        void planDeniesWritesAndTerminalsEvenWithYoloButPermitsClientReads(@TempDir final Path root) {
            final var channel = new Channel();
            final var permissions = permissions(root, configuration);
            permissions.setMode(AgentMode.PLAN);
            final var context = context("s", root, permissions, NEVER, NEVER, null);
            try (final var adapter = adapter("s", root, ALL, channel)) {
                assertTrue(tool(adapter, WRITE)
                        .execute(Map.of("path", "a", "content", "x"), context)
                        .error());
                assertTrue(tool(adapter, TERMINAL)
                        .execute(Map.of("command", "git", "args", List.of("status")), context)
                        .error());
                assertTrue(channel.calls.isEmpty());
                assertTrue(tool(adapter, READ)
                        .execute(Map.of("path", "a"), context)
                        .success());
                assertEquals(List.of("fs/read_text_file"), channel.methods());
                permissions.setPermissionMode(PermissionMode.FULL_YOLO);
                assertTrue(tool(adapter, WRITE)
                        .execute(Map.of("path", "a", "content", "x"), context)
                        .success());
                // FULL_YOLO changes permission checks, not argument validation.
                final var calls = channel.calls.size();
                assertTrue(tool(adapter, WRITE)
                        .execute(Map.of("path", "a", "content", "x", "unknown", true), context)
                        .error());
                assertEquals(calls, channel.calls.size());
            }
        }

        @Test
        void approvalCannotLaunchWorkAfterItChangesSessionToPlan(@TempDir final Path root) {
            final var channel = new Channel();
            final var permissions = permissions(root, configuration);
            permissions.setPermissionMode(PermissionMode.ASK);
            permissions.setGate(request -> {
                permissions.setMode(AgentMode.PLAN);
                return ApprovalGate.Result.ALLOW_ONCE;
            });
            try (final var adapter = adapter("s", root, ALL, channel)) {
                final var result = tool(adapter, WRITE)
                        .execute(
                                Map.of("path", "a", "content", "x"),
                                context("s", root, permissions, NEVER, NEVER, null));
                assertTrue(result.error());
                assertTrue(channel.calls.isEmpty());
            }
        }

        @Test
        void fullStructuredCommandAndCwdReachPermissionGateWithoutChangingWireArguments(@TempDir final Path root) {
            final var channel = new Channel();
            final var permissions = permissions(root, configuration);
            permissions.setPermissionMode(PermissionMode.ASK);
            final var checked = new ArrayList<Map<String, Object>>();
            permissions.setGate(request -> {
                final var args = request.args();
                checked.add(args);
                return ApprovalGate.Result.ALLOW_ONCE;
            });
            final var arguments = List.of(
                    "space here",
                    "quote'\"",
                    "$(not-a-substitution)",
                    "--token=private-marker",
                    "",
                    "tab\there",
                    "line\nbreak",
                    "\u00e9",
                    "\ud83d\ude00",
                    "abc_09./-",
                    "a".repeat(4096));
            try (final var adapter = adapter("s", root, ALL, channel)) {
                final var result = tool(adapter, TERMINAL)
                        .execute(
                                Map.of("command", "/client-only/runner", "args", arguments, "cwd", "sub/.."),
                                context("s", root, permissions, NEVER, NEVER, null));
                assertTrue(result.success(), result.output());
                assertEquals(2, checked.size());
                for (final var args : checked) {
                    assertEquals(arguments, args.get("args"));
                    assertEquals("/client-only/runner", args.get("executable"));
                    assertEquals(root.toString(), args.get("cwd"));
                    assertEquals(root.toString(), args.get("workdir"));
                    assertTrue(((String) args.get("command")).contains("'space here'"));
                    assertTrue(((String) args.get("command")).contains("'$(not-a-substitution)'"));
                    assertEquals(
                            "/client-only/runner 'space here' 'quote'\"'\"'\"' '$(not-a-substitution)'"
                                    + " '--token=private-marker' '' 'tab\there' 'line\n"
                                    + "break' '\u00e9' '\ud83d\ude00' abc_09./- "
                                    + "a".repeat(4096),
                            args.get("command"));
                }
                assertEquals(
                        "/client-only/runner",
                        channel.call("terminal/create").params.get("command"));
                assertEquals(arguments, channel.call("terminal/create").params.get("args"));
                assertFalse(result.output().contains("private-marker"));
                assertFalse(channel.events.toString().contains("private-marker"));
            }
        }

        @Test
        void validatesArgumentsWithoutIntegralStringOrFloatingPointCoercion(@TempDir final Path root) {
            final var channel = new Channel();
            final var context = context("s", root, permissions(root, configuration), NEVER, NEVER, null);
            try (final var adapter = adapter("s", root, ALL, channel)) {
                for (final var value : Arrays.asList(
                        null,
                        "1",
                        true,
                        0,
                        -1,
                        1.0d,
                        new BigDecimal("1.1"),
                        BigInteger.ONE.shiftLeft(64),
                        Long.MAX_VALUE,
                        List.of(1))) {
                    for (final var key : List.of("line", "limit")) {
                        final var args = new HashMap<String, Object>();
                        args.put("path", "a");
                        args.put(key, value);
                        assertTrue(tool(adapter, READ).execute(args, context).error(), key + ": " + value);
                    }
                }
                for (final var args : List.<Map<String, Object>>of(
                        Map.of("command", "runner", "args", "--help"),
                        Map.of("command", "runner", "args", List.of(1)),
                        Map.of("command", "runner", "args", List.of("nul\0argument")),
                        Map.of("command", "runner", "env", List.of(Map.of("name", "X", "value", "Y"))),
                        Map.of("command", "runner", "timeout_seconds", 0),
                        Map.of("command", "runner", "timeout_seconds", "1"),
                        Map.of("command", "runner", "timeout_seconds", 3601),
                        Map.of("command", "runner", "outputByteLimit", 1048577),
                        Map.of("command", "runner", "cwd", false),
                        Map.of("command", true))) {
                    assertTrue(tool(adapter, TERMINAL).execute(args, context).error(), args.toString());
                }
                assertTrue(tool(adapter, READ)
                        .execute(Map.of("path", true), context)
                        .error());
                assertTrue(tool(adapter, READ)
                        .execute(Map.of("path", "a", "offset", 1), context)
                        .error());
                assertTrue(tool(adapter, WRITE)
                        .execute(Map.of("path", "a", "content", 1), context)
                        .error());
                assertTrue(tool(adapter, WRITE)
                        .execute(Map.of("path", "a"), context)
                        .error());
                assertTrue(tool(adapter, READ).execute(null, context).error());
            }
            assertTrue(channel.calls.isEmpty());
        }

        @Test
        void responseValidationUsesJsonMapperDecodedValuesAndPreservesText(@TempDir final Path root) {
            final var channel = new Channel();
            final var context = context("s", root, permissions(root, configuration), NEVER, NEVER, null);
            try (final var mapper = new JsonMapperImpl(List.of(), key -> Optional.empty());
                    final var adapter = adapter("s", root, ALL, channel)) {
                channel.routes.put(
                        "fs/read_text_file",
                        call -> call.result.complete(
                                mapper.fromString(Object.class, "{\"content\":\"unsaved\\n\\\"text\\\"\\u00e9\"}")));
                assertEquals(
                        "unsaved\n\"text\"\u00e9",
                        tool(adapter, READ)
                                .execute(Map.of("path", "a"), context)
                                .output());
                assertEquals(
                        mapper.fromString(
                                Object.class,
                                mapper.toString(Map.of(
                                        "sessionId",
                                        "s",
                                        "path",
                                        root.resolve("a").toString(),
                                        "line",
                                        1,
                                        "limit",
                                        2000))),
                        mapper.fromString(Object.class, mapper.toString(channel.call("fs/read_text_file").params)));
                for (final var json : List.of(
                        "null",
                        "[]",
                        "true",
                        "\"text\"",
                        "{}",
                        "{\"content\":null}",
                        "{\"content\":1}",
                        "{\"content\":[]}")) {
                    channel.routes.put(
                            "fs/read_text_file", call -> call.result.complete(mapper.fromString(Object.class, json)));
                    assertTrue(
                            tool(adapter, READ)
                                    .execute(Map.of("path", "a"), context)
                                    .error(),
                            json);
                }
                for (final var json : List.of("null", "[]", "true", "\"ok\"", "{\"content\":\"unexpected\"}")) {
                    channel.routes.put(
                            "fs/write_text_file", call -> call.result.complete(mapper.fromString(Object.class, json)));
                    assertTrue(
                            tool(adapter, WRITE)
                                    .execute(Map.of("path", "a", "content", "x"), context)
                                    .error(),
                            json);
                }
            }
        }

        @Test
        void terminalSuccessEmbedsThenWaitsOutputsAndReleasesWithoutEchoingCommand(@TempDir final Path root) {
            final var channel = new Channel();
            final var context = context("s", root, permissions(root, configuration), NEVER, NEVER, null);
            try (final var adapter = adapter("s", root, ALL, channel)) {
                final var result = tool(adapter, TERMINAL)
                        .execute(
                                Map.of(
                                        "command",
                                        "not-on-local-machine",
                                        "args",
                                        List.of("--token=secret-argument"),
                                        "timeout_seconds",
                                        19,
                                        "outputByteLimit",
                                        4096),
                                context);
                assertTrue(result.success(), result.output());
                assertEquals("client output\nExit code: 0", result.output());
                assertEquals(
                        List.of(
                                "terminal/create",
                                "embed:term-1",
                                "terminal/wait_for_exit",
                                "terminal/output",
                                "terminal/release"),
                        channel.events);
                assertSame(context, channel.embeddedContext.get());
                assertEquals(42, channel.embeddedContext.get().ordinal());
                assertEquals(
                        Map.of(
                                "sessionId",
                                "s",
                                "command",
                                "not-on-local-machine",
                                "args",
                                List.of("--token=secret-argument"),
                                "cwd",
                                root.toString(),
                                "outputByteLimit",
                                4096),
                        channel.call("terminal/create").params);
                for (final var call : channel.calls) {
                    assertEquals("s", call.params.get("sessionId"));
                    if (!call.method.equals("terminal/create")) {
                        assertEquals("term-1", call.params.get("terminalId"));
                    }
                    assertFalse(call.timeout.isZero());
                    assertFalse(call.timeout.isNegative());
                    assertTrue(call.timeout.compareTo(Duration.ofSeconds(19)) <= 0);
                }
                final var release = channel.call("terminal/release");
                assertEquals(CLEANUP_TIMEOUT, release.timeout);
                assertFalse(release.cancelled.getAsBoolean());
            }
            // Already released terminals must not be retained and cleaned again on session close.
            assertEquals(1, channel.calls("terminal/release").size());
            assertFalse(channel.closed.get());
        }

        @Test
        void nonzeroAndSignalExitStillGetOutputAndAlwaysRelease(@TempDir final Path root) {
            for (final var status : List.<Map<String, Object>>of(Map.of("exitCode", 7), Map.of("signal", "SIGTERM"))) {
                final var channel = new Channel();
                channel.routes.put("terminal/wait_for_exit", call -> call.result.complete(status));
                channel.routes.put(
                        "terminal/output",
                        call -> call.result.complete(
                                Map.of("output", "failure output", "truncated", true, "exitStatus", status)));
                try (final var adapter = adapter("s", root, ALL, channel)) {
                    final var result = tool(adapter, TERMINAL)
                            .execute(
                                    Map.of("command", "runner"),
                                    context("s", root, permissions(root, configuration), NEVER, NEVER, null));
                    assertTrue(result.error());
                    assertTrue(result.output().contains("failure output"));
                    assertTrue(result.output().contains("truncated"));
                    assertEquals(1, channel.calls("terminal/output").size());
                    assertEquals(1, channel.calls("terminal/release").size());
                }
                assertEquals(1, channel.calls("terminal/release").size());
            }
        }

        @Test
        void validatesTerminalCreateShapesAndCleansAnyPublishedValidId(@TempDir final Path root) {
            final var invalid = Arrays.asList(
                    null,
                    true,
                    "term-1",
                    List.of(),
                    Map.of(),
                    Map.of("terminalId", 1),
                    Map.of("terminalId", ""),
                    Map.of("terminalId", List.of("term-1")),
                    Map.of("terminalId", "bad\0id"));
            for (final var response : invalid) {
                final var channel = new Channel();
                channel.routes.put("terminal/create", call -> call.result.complete(response));
                try (final var adapter = adapter("s", root, ALL, channel)) {
                    assertTrue(tool(adapter, TERMINAL)
                            .execute(
                                    Map.of("command", "runner"),
                                    context("s", root, permissions(root, configuration), NEVER, NEVER, null))
                            .error());
                    assertEquals(List.of("terminal/create"), channel.methods());
                }
            }
            final var channel = new Channel();
            channel.routes.put(
                    "terminal/create", call -> call.result.complete(Map.of("terminalId", "valid-id", 1, true)));
            try (final var adapter = adapter("s", root, ALL, channel)) {
                assertTrue(tool(adapter, TERMINAL)
                        .execute(
                                Map.of("command", "runner"),
                                context("s", root, permissions(root, configuration), NEVER, NEVER, null))
                        .error());
                assertEquals(List.of("terminal/create", "terminal/kill", "terminal/release"), channel.methods());
            }
        }

        @Test
        void malformedWaitAndOutputNeverBypassTerminalRelease(@TempDir final Path root) {
            for (final var response : Arrays.asList(
                    null,
                    false,
                    List.of(),
                    "0",
                    Map.of("exitCode", "0"),
                    Map.of("exitCode", 0.0d),
                    Map.of("exitCode", new BigDecimal("0.1")),
                    Map.of("exitCode", Long.MAX_VALUE),
                    Map.of("signal", false))) {
                final var channel = new Channel();
                channel.routes.put("terminal/wait_for_exit", call -> call.result.complete(response));
                assertTerminalInvalidAndReleased(root, channel, configuration);
                assertTrue(channel.calls("terminal/output").isEmpty());
            }
            for (final var response : Arrays.asList(
                    null,
                    true,
                    List.of(),
                    "output",
                    Map.of(),
                    Map.of("output", true, "truncated", false),
                    Map.of("output", "x", "truncated", "false"),
                    Map.of("output", "x", "truncated", false, "exitStatus", false),
                    Map.of("output", "x", "truncated", false, "exitStatus", Map.of("exitCode", "0")),
                    Map.of("output", "x", "truncated", false, "exitStatus", Map.of("exitCode", 3)))) {
                final var channel = new Channel();
                channel.routes.put("terminal/output", call -> call.result.complete(response));
                assertTerminalInvalidAndReleased(root, channel, configuration);
            }
        }

        @Test
        void exactIntegralExitValuesFromJsonMapperAreAcceptedAndNullableFieldsAreNotCoerced(@TempDir final Path root) {
            final var channel = new Channel();
            try (final var mapper = new JsonMapperImpl(List.of(), key -> Optional.empty());
                    final var adapter = adapter("s", root, ALL, channel)) {
                channel.routes.put(
                        "terminal/wait_for_exit",
                        call -> call.result.complete(
                                mapper.fromString(Object.class, "{\"exitCode\":0.0,\"signal\":null}")));
                channel.routes.put(
                        "terminal/output",
                        call -> call.result.complete(mapper.fromString(
                                Object.class,
                                "{\"output\":\"\",\"truncated\":false,\"exitStatus\":{\"exitCode\":0}}")));
                assertTrue(tool(adapter, TERMINAL)
                        .execute(
                                Map.of("command", "runner"),
                                context("s", root, permissions(root, configuration), NEVER, NEVER, null))
                        .success());
            }
        }

        @Test
        void requestFailuresAndTimeoutsDoNotLeakExceptionsOrSkipCleanup(@TempDir final Path root) {
            for (final var method : List.of("terminal/create", "terminal/wait_for_exit", "terminal/output")) {
                for (final var failure : List.of(
                        new IOException("password=secret-exception"),
                        new TimeoutException("token=secret-exception"),
                        new CancellationException("secret-exception"))) {
                    final var channel = new Channel();
                    channel.routes.put(method, call -> call.result.completeExceptionally(failure));
                    try (final var adapter = adapter("s", root, ALL, channel)) {
                        final var result = tool(adapter, TERMINAL)
                                .execute(
                                        Map.of("command", "runner"),
                                        context("s", root, permissions(root, configuration), NEVER, NEVER, null));
                        assertTrue(result.error());
                        assertFalse(result.output().contains("secret-exception"));
                        if (failure instanceof TimeoutException) {
                            assertTrue(result.output().contains("timed out"));
                        }
                        if (!method.equals("terminal/create")) {
                            assertEquals(1, channel.calls("terminal/kill").size());
                            assertEquals(1, channel.calls("terminal/release").size());
                        }
                    }
                }
            }
        }

        @Test
        void callAndTurnCancellationUseConnectionProbeAndCleanupIgnoresCancellation(@TempDir final Path root)
                throws Exception {
            for (final var cancelTurn : List.of(false, true)) {
                final var channel = new Channel();
                final var turn = new AtomicBoolean();
                final var call = new AtomicBoolean();
                final var waiting = new CountDownLatch(1);
                channel.routes.put("terminal/wait_for_exit", request -> waiting.countDown());
                final var context = context("s", root, permissions(root, configuration), turn::get, call::get, null);
                try (final var adapter = adapter("s", root, ALL, channel);
                        final var worker = new Worker(
                                () -> tool(adapter, TERMINAL).execute(Map.of("command", "runner"), context))) {
                    await(waiting);
                    (cancelTurn ? turn : call).set(true);
                    final var wait = channel.call("terminal/wait_for_exit");
                    assertTrue(wait.cancelled.getAsBoolean());
                    // The fake connection, not a timer in the adapter, publishes cancellation.
                    wait.result.completeExceptionally(new CancellationException());
                    assertTrue(worker.result().error());
                    assertTrue(channel.calls("terminal/output").isEmpty());
                    for (final var method : List.of("terminal/kill", "terminal/release")) {
                        assertFalse(channel.call(method).cancelled.getAsBoolean());
                        assertEquals(CLEANUP_TIMEOUT, channel.call(method).timeout);
                    }
                    final var count = channel.calls.size();
                    assertTrue(tool(adapter, TERMINAL)
                            .execute(Map.of("command", "runner"), context)
                            .error());
                    assertEquals(count, channel.calls.size());
                }
            }
        }

        @Test
        void interruptionRestoresFlagAndKillsAndReleasesWithoutWaitingOnReader(@TempDir final Path root)
                throws Exception {
            final var channel = new Channel();
            final var waiting = new CountDownLatch(1);
            channel.routes.put("terminal/wait_for_exit", call -> waiting.countDown());
            try (final var adapter = adapter("s", root, ALL, channel);
                    final var worker = new Worker(() -> tool(adapter, TERMINAL)
                            .execute(
                                    Map.of("command", "runner"),
                                    context("s", root, permissions(root, configuration), NEVER, NEVER, null)))) {
                await(waiting);
                worker.thread.interrupt();
                assertTrue(worker.result().error());
                assertTrue(worker.interrupted.get());
                assertEquals(1, channel.calls("terminal/kill").size());
                assertEquals(1, channel.calls("terminal/release").size());
            }
        }

        @Test
        void lateCreateAfterCloseOrTimeoutIsKilledReleasedAndNeverEmbedded(@TempDir final Path root) throws Exception {
            for (final var close : List.of(false, true)) {
                final var channel = new Channel();
                final var creating = new CountDownLatch(1);
                channel.routes.put("terminal/create", call -> creating.countDown());
                try (final var adapter = adapter("s", root, ALL, channel);
                        final var worker = new Worker(() -> tool(adapter, TERMINAL)
                                .execute(
                                        Map.of("command", "runner"),
                                        context("s", root, permissions(root, configuration), NEVER, NEVER, null)))) {
                    await(creating);
                    final var create = channel.call("terminal/create");
                    if (close) {
                        adapter.close();
                        assertTrue(worker.result().error());
                        // A still-pending stage may publish a normal success after session close.
                        create.result.complete(Map.of("terminalId", "late"));
                    } else {
                        create.result.completeExceptionally(new TimeoutException());
                        assertTrue(worker.result().error());
                        // An already-failed stage needs the explicit bounded connection late-result hook.
                        create.lateResult.accept(Map.of("terminalId", "late"));
                    }
                    assertEquals(List.of("terminal/create", "terminal/kill", "terminal/release"), channel.methods());
                    assertEquals("late", channel.call("terminal/release").params.get("terminalId"));
                    assertNull(channel.embeddedContext.get());
                    create.lateResult.accept(Map.of("terminalId", "late"));
                    assertEquals(1, channel.calls("terminal/release").size());
                }
            }
        }

        @Test
        void cancellationBeforeCreateReplyStillReleasesLatePublishedTerminal(@TempDir final Path root)
                throws Exception {
            final var channel = new Channel();
            final var cancelled = new AtomicBoolean();
            final var creating = new CountDownLatch(1);
            channel.routes.put("terminal/create", call -> creating.countDown());
            final var context = context("s", root, permissions(root, configuration), cancelled::get, NEVER, null);
            try (final var adapter = adapter("s", root, ALL, channel);
                    final var worker =
                            new Worker(() -> tool(adapter, TERMINAL).execute(Map.of("command", "runner"), context))) {
                await(creating);
                cancelled.set(true);
                channel.call("terminal/create").result.complete(Map.of("terminalId", "cancelled-create"));
                assertTrue(worker.result().error());
                assertEquals(List.of("terminal/create", "terminal/kill", "terminal/release"), channel.methods());
                assertNull(channel.embeddedContext.get());
            }
        }

        @Test
        void closeCleansAllTerminalsIndependentlyAndNeverClosesSharedResources(@TempDir final Path root)
                throws Exception {
            final var channel = new Channel();
            final var waiting = new CountDownLatch(2);
            channel.routes.put("terminal/wait_for_exit", call -> waiting.countDown());
            channel.routes.put("terminal/kill", call -> {}); // deliberately never completes
            channel.routes.put("terminal/release", call -> {}); // independent bounded connection futures
            final var context = context("s", root, permissions(root, configuration), NEVER, NEVER, null);
            try (final var mapper = new JsonMapperImpl(List.of(), key -> Optional.empty());
                    final var adapter = adapter("s", root, ALL, channel);
                    final var first =
                            new Worker(() -> tool(adapter, TERMINAL).execute(Map.of("command", "one"), context));
                    final var second =
                            new Worker(() -> tool(adapter, TERMINAL).execute(Map.of("command", "two"), context))) {
                await(waiting);
                adapter.close();
                assertTrue(first.result().error());
                assertTrue(second.result().error());
                assertEquals(
                        Set.of("term-1", "term-2"),
                        channel.calls("terminal/release").stream()
                                .map(call -> call.params.get("terminalId"))
                                .collect(Collectors.toSet()));
                assertEquals(2, channel.calls("terminal/kill").size());
                assertEquals(2, channel.calls("terminal/release").size());
                adapter.close();
                assertEquals(2, channel.calls("terminal/release").size());
                assertFalse(channel.closed.get());
                assertNotNull(mapper.toString(Map.of("still", "usable")));
                assertFalse(tool(adapter, TERMINAL).usable(root));
                final var count = channel.calls.size();
                assertTrue(tool(adapter, TERMINAL)
                        .execute(Map.of("command", "three"), context)
                        .error());
                assertEquals(count, channel.calls.size());
                channel.calls("terminal/release")
                        .forEach(call -> call.result.completeExceptionally(new TimeoutException()));
                channel.calls("terminal/kill")
                        .forEach(call -> call.result.completeExceptionally(new TimeoutException()));
                channel.calls("terminal/wait_for_exit")
                        .forEach(call -> call.result.completeExceptionally(new CancellationException()));
            }
        }

        @Test
        void synchronousDisconnectOnKillCannotPreventReleaseAttempt(@TempDir final Path root) throws Exception {
            final var channel = new Channel();
            final var waiting = new CountDownLatch(1);
            channel.routes.put("terminal/wait_for_exit", call -> waiting.countDown());
            channel.routes.put("terminal/kill", call -> {
                throw new IllegalStateException("secret disconnect detail");
            });
            channel.routes.put("terminal/release", call -> {
                throw new IllegalStateException("secret disconnect detail");
            });
            try (final var adapter = adapter("s", root, ALL, channel);
                    final var worker = new Worker(() -> tool(adapter, TERMINAL)
                            .execute(
                                    Map.of("command", "runner"),
                                    context("s", root, permissions(root, configuration), NEVER, NEVER, null)))) {
                await(waiting);
                channel.call("terminal/wait_for_exit")
                        .result
                        .completeExceptionally(new IOException("secret transport"));
                final var result = worker.result();
                assertTrue(result.error());
                assertFalse(result.output().contains("secret"));
                assertEquals(1, channel.calls("terminal/kill").size());
                assertEquals(1, channel.calls("terminal/release").size());
            }
        }

        @Test
        void updateFailureCannotLeaveTerminalRunningOrLaunchWait(@TempDir final Path root) {
            final var channel = new Channel();
            try (final var adapter =
                    new AcpClientTools("s", root, ALL, REQUEST_TIMEOUT, CLEANUP_TIMEOUT, channel, (context, id) -> {
                        throw new IllegalStateException("token=secret-update");
                    })) {
                final var result = tool(adapter, TERMINAL)
                        .execute(
                                Map.of("command", "runner"),
                                context("s", root, permissions(root, configuration), NEVER, NEVER, null));
                assertTrue(result.error());
                assertFalse(result.output().contains("secret-update"));
                assertEquals(List.of("terminal/create", "terminal/kill", "terminal/release"), channel.methods());
            }
        }

        @Test
        void closeDuringEmbeddingCannotLaunchWait(@TempDir final Path root) {
            final var channel = new Channel();
            final var reference = new AtomicReference<AcpClientTools>();
            try (final var adapter = new AcpClientTools(
                    "s",
                    root,
                    ALL,
                    REQUEST_TIMEOUT,
                    CLEANUP_TIMEOUT,
                    channel,
                    (context, id) -> reference.get().close())) {
                reference.set(adapter);
                assertTrue(tool(adapter, TERMINAL)
                        .execute(
                                Map.of("command", "runner"),
                                context("s", root, permissions(root, configuration), NEVER, NEVER, null))
                        .error());
                assertEquals(List.of("terminal/create", "terminal/kill", "terminal/release"), channel.methods());
            }
        }

        @Test
        void closingPendingFilesystemRequestUnblocksWorkerWithoutWaitingOrLocalFallback(@TempDir final Path root)
                throws Exception {
            final var channel = new Channel();
            final var reading = new CountDownLatch(1);
            channel.routes.put("fs/read_text_file", call -> reading.countDown());
            try (final var adapter = adapter("s", root, ALL, channel);
                    final var worker = new Worker(() -> tool(adapter, READ)
                            .execute(
                                    Map.of("path", "a"),
                                    context("s", root, permissions(root, configuration), NEVER, NEVER, null)))) {
                await(reading);
                adapter.close();
                assertTrue(worker.result().error());
                assertTrue(channel.call("fs/read_text_file").cancelled.getAsBoolean());
                channel.call("fs/read_text_file").result.complete(Map.of("content", "too late"));
                assertEquals(List.of("fs/read_text_file"), channel.methods());
            }
        }
    }
}
