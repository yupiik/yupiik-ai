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

import io.yupiik.ai.acp.protocol.*;
import io.yupiik.ai.mcp.client.McpClient;
import io.yupiik.ai.mcp.client.McpServerTool;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.MemoryExtractor;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.memory.MemoryProductionPolicy;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.security.SecretNames;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.*;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
class SessionLifecycleTest {

    @TempDir
    private static Path eventWorkdir;

    @TempDir
    private static Path testWorkdir;

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static McpClient closer(final Runnable close) {
        return new McpClient() {
            @Override
            public void initialize() {}

            @Override
            public List<McpServerTool> listTools() {
                return List.of();
            }

            @Override
            public String callTool(final String name, final Map<String, Object> arguments) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                close.run();
            }
        };
    }

    private class Fixture implements AutoCloseable {
        private final JsonMapper mapper = CodecsTest.mapper();
        private final OptionTestSupport optionSupport;
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger disposed = new AtomicInteger();
        private final AtomicInteger released = new AtomicInteger();
        private final PipedInputStream input = new PipedInputStream(65536);
        private final PrintWriter writer = new PrintWriter(new PipedOutputStream(input), true);
        private final BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        private final PrintStream output = new PrintStream(OutputStream.nullOutputStream()) {
            @Override
            public void println(final String line) {
                responses.add(line);
            }
        };
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final Path cwd;
        private Function<String, AgentLoop> loopFactory;
        private Function<Path, McpManager.SessionTools> mcpSessions;
        private Function<TurnRunner.TurnRequest, CompletionStage<Boolean>> turnHandler;
        private Thread reader;

        private Fixture(
                final CodingAgentConfiguration configuration,
                final AcpCommand.Conf commandOptions,
                final Path cwd,
                final SessionStore suppliedStore,
                final Consumer<String> beforeCreate,
                final ShellRegistry suppliedShells,
                final Consumer<Fixture> initialize)
                throws IOException {
            optionSupport = new OptionTestSupport(
                    configuration,
                    commandOptions,
                    mapper,
                    settings -> CompletableFuture.completedFuture(new ModelsResponse(List.of())));
            this.cwd = cwd;
            if (initialize != null) {
                initialize.accept(this);
            }
            final var store = suppliedStore == null
                    ? new SessionStore() {
                        @Override
                        public String create() {
                            return "s" + created.incrementAndGet();
                        }
                    }
                    : suppliedStore;
            final var lifecycle = new SessionLifecycle(store) {
                @Override
                public void release(final String id, final SessionRuntime runtime) {
                    released.incrementAndGet();
                    super.release(id, runtime);
                }
            };
            final var shells = suppliedShells == null
                    ? new ShellRegistry(new Emitter() {
                        @Override
                        public <T> void emit(final T event) {}
                    })
                    : suppliedShells;
            final var factory = new SessionRuntimeFactory() {
                @Override
                public Path resolveWorkdir(final Path explicitWorkdir, final String resumedSessionId) {
                    return Objects.requireNonNull(explicitWorkdir, "workdir");
                }

                @Override
                public SessionRuntime create(
                        final String key,
                        final String id,
                        final ToolRegistry tools,
                        final Path workdir,
                        final Boolean sandboxOverride) {
                    if (beforeCreate != null) {
                        beforeCreate.accept(id);
                    }
                    final var loop = new AgentLoop(workdir, new DisabledSandbox(workdir)) {
                        private ChatSettings selection = ChatSettings.EMPTY;

                        @Override
                        public ChatSettings chatSettings() {
                            return selection;
                        }

                        @Override
                        public void updateChatSettings(final ChatSettings settings) {
                            selection = settings;
                        }

                        @Override
                        public boolean iterationLimitReached() {
                            return false;
                        }

                        @Override
                        public void close() {
                            disposed.incrementAndGet();
                        }
                    };
                    return new SessionRuntime(
                            loopFactory == null ? loop : loopFactory.apply(id),
                            new PermissionService(configuration, workdir),
                            new UserFeedbackService(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            workdir,
                            new DisabledSandbox(workdir));
                }
            };
            final var mcp = new McpManager(null, mapper, List.of(), null, null) {
                @Override
                public void start() {}

                @Override
                public SessionTools openSession(final List<? extends SessionServer> servers, final Path root) {
                    return mcpSessions == null ? new SessionTools(List.of(), List.of()) : mcpSessions.apply(root);
                }
            };
            final var turns = new TurnRunner(null, null, null) {
                @Override
                public CompletionStage<Boolean> submit(final TurnRequest request) {
                    if (turnHandler != null) {
                        return turnHandler.apply(request);
                    }
                    final var frontend = request.env().frontend();
                    frontend.onEvent(new AgentEvent.TextDelta(
                            "child-text", request.sessionId(), "child", "child", eventWorkdir));
                    frontend.onEvent(new AgentEvent.ThinkingDelta(
                            "child-thinking", request.sessionId(), "child", "child", eventWorkdir));
                    frontend.onEvent(new AgentEvent.TextDelta("parent", request.sessionId(), eventWorkdir));
                    frontend.onEvent(new AgentEvent.Notice(
                            AgentEvent.Notice.Level.ERROR, "truncated", "length", request.sessionId(), eventWorkdir));
                    return CompletableFuture.completedFuture(true);
                }
            };
            final var server = new AcpServer(
                    mapper,
                    factory,
                    lifecycle,
                    shells,
                    store,
                    turns,
                    new ToolRegistry(mcp),
                    mcp,
                    optionSupport.options,
                    null,
                    new SecretNames(optionSupport.configuration),
                    List.of(),
                    null,
                    null,
                    null,
                    optionSupport.providers,
                    optionSupport.configuration,
                    null,
                    null,
                    optionSupport.commandOptions);
            reader = Thread.ofVirtual().start(() -> {
                try {
                    server.run(input, output, ChatSettings.EMPTY);
                    done.complete(null);
                } catch (final Throwable e) {
                    done.completeExceptionally(e);
                }
            });
            writer.println(
                    "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"clientCapabilities\":{}}}");
            assertNotNull(response().result());
        }

        private void sendNew(final int id) {
            sendNew(id, cwd);
        }

        private void sendNew(final int id, final Path root) {
            writer.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"session/new\",\"params\":"
                    + mapper.toString(Map.of("cwd", root.toString(), "mcpServers", List.of())) + "}");
        }

        private void sendPrompt(final int id, final String session) {
            writer.println(
                    "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"session/prompt\",\"params\":{\"sessionId\":\""
                            + session + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"go\"}]}}");
        }

        private Envelope response() throws IOException {
            try {
                Envelope message;
                do {
                    final var line = responses.poll(10, TimeUnit.SECONDS);
                    assertNotNull(line, "Expected ACP response");
                    message = mapper.fromString(Envelope.class, line);
                    // Catalog is a newly supported session-level notification, not a request response or turn chunk.
                } while ("session/update".equals(message.method())
                        && ((Map<?, ?>) ((Map<?, ?>) message.params()).get("update"))
                                .get("sessionUpdate")
                                .equals("available_commands_update"));
                return message;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        @Override
        public void close() throws Exception {
            writer.close();
            try {
                done.get(10, TimeUnit.SECONDS);
            } finally {
                output.close();
                input.close();
                optionSupport.close();
                mapper.close();
            }
        }
    }

    @Nested
    @FusionSupport(modules = MemoryModule.class)
    class PendingMemory {
        @TempDir
        static Path tmp;

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void eofExtractsPendingMemoryOncePerRootWithoutReenablingDisabledSession(
                final boolean automatic,
                @Fusion final CodingAgentConfiguration config,
                @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var memory = new MemoryStore(config);
            final var policy = new MemoryProductionPolicy();
            final var roots = new ConcurrentHashMap<String, Path>();
            final var extractors = new ConcurrentHashMap<String, MemoryExtractor>();
            final var calls = new ConcurrentHashMap<String, AtomicInteger>();
            final var forced = new ConcurrentHashMap<String, AtomicInteger>();
            final var ids = new AtomicInteger();
            final var messages = List.of(Message.user("Remember this durable project choice"));
            final var store = new SessionStore() {
                @Override
                public String create() {
                    return "s" + ids.incrementAndGet();
                }

                @Override
                public void markProject(final String id, final String root) {
                    roots.put(id, Path.of(root));
                }

                @Override
                public List<Message> load(final String id) {
                    return messages;
                }
            };
            try (final var executor = Executors.newSingleThreadExecutor();
                    final var connection = new Fixture(config, commandOptions, tmp, store, null, null, fixture -> {
                        fixture.loopFactory = id -> {
                            final var chat = new ChatClient() {
                                @Override
                                public StructuredResult chatStructured(
                                        final ChatCompletionRequest request,
                                        final String schemaName,
                                        final Map<String, Object> schema) {
                                    calls.computeIfAbsent(id, ignored -> new AtomicInteger())
                                            .incrementAndGet();
                                    return new StructuredResult(
                                            new ChatResult(
                                                    Message.assistant(
                                                            "{\"facts\":[{\"text\":\"" + id
                                                                    + " durable project"
                                                                    + " choice\",\"scope\":\"project\"}]}",
                                                            null),
                                                    new Usage(0, 0, 0)),
                                            true);
                                }
                            };
                            final var extractor = new MemoryExtractor(
                                    chat,
                                    config,
                                    null,
                                    memory,
                                    fixture.mapper,
                                    executor,
                                    null,
                                    Clock.systemUTC(),
                                    null,
                                    id,
                                    null,
                                    policy,
                                    null,
                                    roots.get(id));

                            extractors.put(id, extractor);
                            if ("s3".equals(id)) {
                                policy.set(id, false);
                            }
                            return new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                                private ChatSettings selection = ChatSettings.EMPTY;

                                @Override
                                public ChatSettings chatSettings() {
                                    return selection;
                                }

                                @Override
                                public void updateChatSettings(final ChatSettings settings) {
                                    selection = settings;
                                }

                                @Override
                                public boolean forceExtractNow(
                                        final List<Message> loaded, final ChatSettings settings) {
                                    assertEquals(messages, loaded);
                                    assertEquals(selection, settings);
                                    forced.computeIfAbsent(id, ignored -> new AtomicInteger())
                                            .incrementAndGet();
                                    return extractor.forceExtractNow(loaded, settings);
                                }

                                @Override
                                public void close() {
                                    policy.clear(id);
                                }
                            };
                        };
                        fixture.turnHandler = request -> {
                            extractors
                                    .get(request.sessionId())
                                    .asyncExtractNow(messages, ChatSettings.EMPTY, automatic);
                            return CompletableFuture.completedFuture(true);
                        };
                    })) {
                for (int i = 1; i <= 3; i++) {
                    final var root = Files.createDirectories(tmp.resolve("root" + i));
                    connection.sendNew(i, root);
                    assertNotNull(connection.response().result());
                    connection.sendPrompt(10 + i, "s" + i);
                    assertNotNull(connection.response().result());
                }
                executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertEquals(
                        automatic ? 2 : 0,
                        calls.values().stream().mapToInt(AtomicInteger::get).sum());
                connection.writer.close();
                connection.done.get(5, TimeUnit.SECONDS);
                for (final var id : List.of("s1", "s2")) {
                    assertEquals(1, calls.get(id).get());
                    assertEquals(1, forced.get(id).get());
                    final var text = memory.readProject(roots.get(id));
                    assertTrue(text.contains(id + " durable project choice"), text);
                    assertFalse(text.contains(("s1".equals(id) ? "s2" : "s1") + " durable project choice"), text);
                    assertFalse(extractors.get(id).forceExtractNow(messages));
                }
                assertEquals(1, forced.get("s3").get());
                assertFalse(calls.containsKey("s3"));
                assertFalse(memory.readProject(roots.get("s3")).contains("durable project choice"));
            }
        }
    }

    public static class MemoryModule extends OptionConfigurationTestModule {
        public MemoryModule() {
            super(Map.of(
                    "yca.storageHome",
                    PendingMemory.tmp.resolve("storage").toString(),
                    "yca.workingDir",
                    PendingMemory.tmp.toString(),
                    "yca.projectDir",
                    PendingMemory.tmp.toString(),
                    "yca.memory.enabled",
                    "true",
                    "yca.memory.maxChars",
                    "8000",
                    "yca.memory.maxRatio",
                    "0.2",
                    "yca.memory.extractMinChars",
                    "0"));
        }
    }

    @Nested
    @FusionSupport(modules = OptionConfigurationTestModule.class)
    class Defaults {
        @Fusion
        private CodingAgentConfiguration configuration;

        @Fusion
        private AcpCommand.Conf commandOptions;

        @ParameterizedTest
        @ValueSource(strings = {"mark", "factory"})
        void releasesIdWhenCreationFailsBeforeRuntime(final String failure, @TempDir final Path tmp) throws Exception {
            final var deleted = new CopyOnWriteArrayList<String>();
            final var mcpClosed = new CountDownLatch(2);
            final var store = new SessionStore() {
                @Override
                public String create() {
                    return "failed";
                }

                @Override
                public void markProject(final String id, final String dir) {
                    if ("mark".equals(failure)) {
                        throw new IllegalStateException("mark failed");
                    }
                }

                @Override
                public boolean isEmpty(final String id) {
                    return true;
                }

                @Override
                public void delete(final String id) {
                    deleted.add(id);
                }
            };
            try (final var connection = new Fixture(
                    configuration,
                    commandOptions,
                    tmp,
                    store,
                    id -> {
                        throw new IllegalStateException("factory failed");
                    },
                    null,
                    fixture -> fixture.mcpSessions = root -> new McpManager.SessionTools(
                            List.of(), List.of(closer(mcpClosed::countDown), closer(mcpClosed::countDown))))) {
                connection.sendNew(1);
                assertNotNull(connection.response().error());
                assertEquals(List.of("failed"), deleted);
                await(mcpClosed);
            }
        }

        @Test
        void shutdownDuringPublicationReleasesRuntimeAndShell(@TempDir final Path tmp) throws Exception {
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var shells =
                    new ShellRegistry(new Emitter() {
                        @Override
                        public <T> void emit(final T event) {}
                    }) {
                        @Override
                        public void register(final String id, final ShellEnv env) {
                            super.register(id, env);
                            entered.countDown();
                            await(release);
                        }
                    };
            try (final var connection = new Fixture(configuration, commandOptions, tmp, null, null, shells, null)) {
                connection.sendNew(1);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                connection.writer.close();
                release.countDown();
                connection.done.get(10, TimeUnit.SECONDS);
                assertTrue(shells.sessions().isEmpty());
                assertEquals(1, connection.disposed.get());
                assertEquals(1, connection.released.get());
            } finally {
                release.countDown();
            }
        }

        @Test
        void initializingSessionsReserveCapacityAtomically(@TempDir final Path tmp) throws Exception {
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            try (final var connection = new Fixture(
                    configuration,
                    commandOptions,
                    tmp,
                    null,
                    id -> {
                        if ("s64".equals(id)) {
                            entered.countDown();
                            await(release);
                        }
                    },
                    null,
                    null)) {
                for (int i = 1; i <= 63; i++) {
                    connection.sendNew(i);
                    assertNotNull(connection.response().result());
                }
                connection.sendNew(64);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                connection.sendNew(65);
                final var rejected = connection.response();
                assertEquals("65", rejected.id().toString());
                assertNotNull(rejected.error());
                release.countDown();
                assertNotNull(connection.response().result());
                assertEquals(64, connection.created.get());
            } finally {
                release.countDown();
            }
        }

        @Test
        void lateRuntimeAfterDisconnectIsDisposedExactlyOnce(@TempDir final Path tmp) throws Exception {
            final var entered = new CountDownLatch(1);
            final var disconnected = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            try (final var connection = new Fixture(
                    configuration,
                    commandOptions,
                    tmp,
                    null,
                    id -> {
                        entered.countDown();
                        boolean interrupted = false;
                        while (true) {
                            try {
                                release.await();
                                break;
                            } catch (final InterruptedException e) {
                                disconnected.countDown();
                                interrupted = true;
                            }
                        }
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    null,
                    null)) {
                connection.sendNew(1);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                connection.writer.close();
                assertTrue(disconnected.await(5, TimeUnit.SECONDS));
                release.countDown();
                connection.done.get(10, TimeUnit.SECONDS);
                assertEquals(1, connection.disposed.get());
                assertEquals(1, connection.released.get());
            } finally {
                release.countDown();
            }
        }

        @Test
        void failedInitializationsReturnTheirCapacityReservation(@TempDir final Path tmp) throws Exception {
            final var attempts = new AtomicInteger();
            try (final var connection = new Fixture(
                    configuration,
                    commandOptions,
                    tmp,
                    null,
                    id -> {
                        if (attempts.incrementAndGet() <= 65) {
                            throw new IllegalStateException("startup failure");
                        }
                    },
                    null,
                    null)) {
                for (int i = 1; i <= 65; i++) {
                    connection.sendNew(i);
                    assertNotNull(connection.response().error());
                }
                connection.sendNew(66);
                assertNotNull(connection.response().result());
            }
        }

        @Test
        void childDeltasDoNotEnterMainResponseAndTruncationReturnsMaxTokens(@TempDir final Path tmp) throws Exception {
            try (final var connection = new Fixture(configuration, commandOptions, tmp, null, null, null, null)) {
                connection.sendNew(1);
                assertNotNull(connection.response().result());
                connection.writer.println(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"s1\",\"prompt\":[{\"type\":\"text\",\"text\":\"go\"}]}}");
                final var updates = new ArrayList<String>();
                Envelope answer;
                while ((answer = connection.response()).id() == null) {
                    assertEquals("session/update", answer.method());
                    updates.add(connection.mapper.toString(answer.params()));
                }
                assertEquals("2", answer.id().toString());
                assertTrue(updates.stream().anyMatch(update -> update.contains("parent")), updates.toString());
                assertFalse(
                        updates.stream()
                                .anyMatch(update -> update.contains("child-text") || update.contains("child-thinking")),
                        updates.toString());
                assertNull(answer.error());
                assertEquals(Map.of("stopReason", "max_tokens"), answer.result());
            }
        }

        @Test
        void closesClientsIndependentlyAndInterruptsCreationBeforeWaiting(@TempDir final Path tmp) throws Exception {
            final var firstClosing = new CountDownLatch(1);
            final var secondClosing = new CountDownLatch(1);
            final var otherSessionClosing = new CountDownLatch(1);
            final var uncreatedSessionClosing = new CountDownLatch(1);
            final var releaseCloser = new CountDownLatch(1);
            final var creating = new CountDownLatch(1);
            final var interrupted = new CountDownLatch(1);
            final var opens = new AtomicInteger();
            try (final var connection = new Fixture(
                    configuration,
                    commandOptions,
                    tmp,
                    null,
                    id -> {
                        if ("s3".equals(id)) {
                            creating.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (final InterruptedException e) {
                                interrupted.countDown();
                                throw new IllegalStateException(e);
                            }
                        }
                    },
                    null,
                    fixture -> fixture.mcpSessions = root -> switch (opens.incrementAndGet()) {
                        case 1 ->
                            new McpManager.SessionTools(
                                    List.of(),
                                    List.of(
                                            closer(() -> {
                                                firstClosing.countDown();
                                                await(releaseCloser);
                                            }),
                                            closer(secondClosing::countDown)));
                        case 2 ->
                            new McpManager.SessionTools(List.of(), List.of(closer(otherSessionClosing::countDown)));
                        default ->
                            new McpManager.SessionTools(List.of(), List.of(closer(uncreatedSessionClosing::countDown)));
                    })) {
                connection.sendNew(1);
                assertNotNull(connection.response().result());
                connection.sendNew(2);
                assertNotNull(connection.response().result());
                connection.sendNew(3);
                await(creating);
                connection.writer.close();
                await(firstClosing);
                await(secondClosing);
                await(otherSessionClosing);
                await(interrupted);
                await(uncreatedSessionClosing);
                // No expiry wait: the blocked closer is released only after all independent work started.
                releaseCloser.countDown();
                connection.done.get(5, TimeUnit.SECONDS);
                assertEquals(2, connection.disposed.get());
                assertEquals(3, opens.get(), "all three MCP sessions were opened and independently closed");
                assertEquals(2, connection.released.get(), "only the two created runtimes own gates to release");
            } finally {
                releaseCloser.countDown();
            }
        }

        @Test
        void configurationIsInterruptedBeforeBlockedMcpCloseCompletes(@TempDir final Path tmp) throws Exception {
            final var configuring = new CountDownLatch(1);
            final var interrupted = new CountDownLatch(1);
            final var closing = new CountDownLatch(1);
            final var releaseCloser = new CountDownLatch(1);
            final var disposed = new CountDownLatch(1);
            try (final var connection = new Fixture(configuration, commandOptions, tmp, null, null, null, fixture -> {
                fixture.loopFactory = id -> new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                    private volatile ChatSettings selection = ChatSettings.EMPTY;

                    @Override
                    public ChatSettings chatSettings() {
                        return selection;
                    }

                    @Override
                    public void updateChatSettings(final ChatSettings settings) {
                        if ("default".equals(settings.providerId())) {
                            configuring.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (final InterruptedException e) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        }
                        selection = settings;
                    }

                    @Override
                    public void close() {
                        assertEquals(0, interrupted.getCount());
                        disposed.countDown();
                    }
                };
                fixture.mcpSessions = root -> new McpManager.SessionTools(List.of(), List.of(closer(() -> {
                    closing.countDown();
                    await(releaseCloser);
                })));
            })) {
                connection.sendNew(1);
                assertNotNull(connection.response().result());
                connection.writer.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/set_config_option\","
                        + "\"params\":{\"sessionId\":\"s1\",\"configId\":\"provider\",\"value\":\"default\"}}");
                await(configuring);
                connection.writer.close();
                await(closing);
                await(interrupted);
                await(disposed);
                releaseCloser.countDown();
                connection.done.get(5, TimeUnit.SECONDS);
            } finally {
                releaseCloser.countDown();
            }
        }

        @Test
        void interruptedShutdownDoesNotDisposeOrExtractUnderActiveTurn(@TempDir final Path tmp) throws Exception {
            final var turn = new CompletableFuture<Boolean>();
            final var started = new CountDownLatch(1);
            final var closing = new CountDownLatch(1);
            final var disposed = new CountDownLatch(1);
            final var extracts = new AtomicInteger();
            try (final var connection = new Fixture(configuration, commandOptions, tmp, null, null, null, fixture -> {
                fixture.loopFactory = id -> new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                    @Override
                    public boolean forceExtractNow(final List<Message> messages, final ChatSettings settings) {
                        extracts.incrementAndGet();
                        return true;
                    }

                    @Override
                    public void close() {
                        disposed.countDown();
                    }
                };
                fixture.turnHandler = request -> {
                    started.countDown();
                    return turn;
                };
                fixture.mcpSessions =
                        root -> new McpManager.SessionTools(List.of(), List.of(closer(closing::countDown)));
            })) {
                connection.sendNew(1);
                assertNotNull(connection.response().result());
                connection.sendPrompt(2, "s1");
                await(started);
                connection.writer.close();
                await(closing);
                connection.reader.interrupt();
                connection.done.get(5, TimeUnit.SECONDS);
                assertEquals(1, disposed.getCount());
                assertEquals(0, extracts.get());
                turn.complete(true);
                await(disposed);
                assertEquals(0, extracts.get());
            } finally {
                turn.complete(true);
            }
        }

        @Test
        void finalExtractionRunsOffReaderAndIsInterruptedWithShutdown(@TempDir final Path tmp) throws Exception {
            final var extracting = new CountDownLatch(1);
            final var interrupted = new CountDownLatch(1);
            final var disposed = new CountDownLatch(1);
            try (final var connection = new Fixture(
                    configuration,
                    commandOptions,
                    tmp,
                    null,
                    null,
                    null,
                    fixture ->
                            fixture.loopFactory = id -> new AgentLoop(testWorkdir, new DisabledSandbox(testWorkdir)) {
                                @Override
                                public boolean forceExtractNow(
                                        final List<Message> messages, final ChatSettings settings) {
                                    assertNotSame(fixture.reader, Thread.currentThread());
                                    extracting.countDown();
                                    try {
                                        new CountDownLatch(1).await();
                                    } catch (final InterruptedException e) {
                                        interrupted.countDown();
                                        Thread.currentThread().interrupt();
                                    }
                                    return false;
                                }

                                @Override
                                public void close() {
                                    disposed.countDown();
                                }
                            })) {
                connection.sendNew(1);
                assertNotNull(connection.response().result());
                connection.writer.close();
                await(extracting);
                connection.reader.interrupt();
                connection.done.get(5, TimeUnit.SECONDS);
                await(interrupted);
                await(disposed);
            }
        }
    }
}
