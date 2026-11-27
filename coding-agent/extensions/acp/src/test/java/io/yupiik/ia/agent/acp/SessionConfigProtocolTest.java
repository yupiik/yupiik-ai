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
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.events.ModelSwitchedEvent;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.security.SecretNames;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.*;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** In-process protocol regressions: futures and latches control every slow collaborator. */
@Timeout(5)
@FusionSupport(modules = OptionConfigurationTestModule.class)
class SessionConfigProtocolTest {
    @Fusion
    private CodingAgentConfiguration configuration;

    @Fusion
    private AcpCommand.Conf commandOptions;

    @TempDir
    private static Path eventWorkdir;

    @TempDir
    private static Path testWorkdir;

    @Test
    void cancellationDuringPromptDecodeCannotBeResetByStartup() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (final var f = new Fixture()) {
            final var id = f.create(1).sessionId();
            f.beforePromptDecode = () -> {
                entered.countDown();
                await(release);
            };
            f.prompt(2, id);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            f.send(null, "session/cancel", new Session(id));
            f.send(3, "initialize", new Initialize(1, null, null));
            assertNotNull(f.response(3).error()); // reader barrier: cancel has completed
            release.countDown();
            assertEquals(Map.of("stopReason", "cancelled"), f.response(2).result());
            assertTrue(f.turns.isEmpty());
            f.beforePromptDecode = () -> {};
            f.prompt(4, id); // a prompt admitted after cancellation can start normally
            f.turns.take().done().complete(true);
            assertEquals(Map.of("stopReason", "end_turn"), f.response(4).result());
        } finally {
            release.countDown();
        }
    }

    @Test
    void selectorOrderIsIdenticalInInitialStateUpdatesAndResponses() throws Exception {
        try (final var f = new Fixture()) {
            final var created = f.create(1);
            final var expected = List.of("provider", "model", "variant", "mode");
            assertEquals(
                    expected,
                    created.configOptions().stream().map(ConfigOption::id).toList());
            f.config(2, created.sessionId(), "provider", "default");
            assertEquals(
                    expected,
                    f.configResult(2).configOptions().stream()
                            .map(ConfigOption::id)
                            .toList());
            final var update = f.updates.stream()
                    .filter(it -> "config_option_update".equals(it.get("sessionUpdate")))
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    expected,
                    f.mapper.fromString(ConfigUpdate.class, f.mapper.toString(update)).configOptions().stream()
                            .map(ConfigOption::id)
                            .toList());
        }
    }

    @Test
    void mapperEnvelopeValidationRejectsMixedAndMalformedShapesButAcceptsNullResults() throws Exception {
        try (final var f = new Fixture()) {
            for (final var json : List.of(
                    "[]",
                    "null",
                    "42",
                    "{\"jsonrpc\":\"2.0\",\"id\":1}",
                    "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"initialize\"}",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":null,\"result\":null}",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":42}",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"result\":null}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"error\":null}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":false}",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null,\"error\":null}",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":1}}")) {
                f.writer.println(json);
                assertEquals(
                        -32600,
                        f.mapper
                                .fromString(Envelope.class, f.lines.take())
                                .error()
                                .code(),
                        json);
            }
            f.writer.println("{");
            assertEquals(
                    -32700,
                    f.mapper.fromString(Envelope.class, f.lines.take()).error().code());
            f.writer.println("{\"jsonrpc\":\"2.0\",\"id\":\"unknown\",\"result\":null}");
            f.send(9, "initialize", new Initialize(1, null, null));
            assertNotNull(f.response(9).error());
            assertTrue(f.replies.isEmpty(), "A valid null response must not produce an Invalid Request reply");
        }
    }

    @Test
    void configurationThenPromptUsesNewSelectionInWireOrderAndDoesNotTouchOtherSession() throws Exception {
        try (final var f = new Fixture()) {
            final var first = f.create(1);
            final var second = f.create(2);
            assertEquals(
                    List.of("provider", "model", "variant", "mode"),
                    first.configOptions().stream().map(ConfigOption::id).toList());
            f.config(3, first.sessionId(), "provider", "default");
            f.prompt(4, first.sessionId());
            final var turn = f.turns.take();
            assertEquals(new ChatSettings("default", null, null, null), turn.settings());
            assertEquals("default", f.configResult(3).configOptions().getFirst().currentValue());
            assertEquals("a", f.runtimes.get(second.sessionId()).chatSettings().providerId());
            turn.done().complete(true);
            assertEquals(
                    "end_turn",
                    f.mapper
                            .fromString(
                                    PromptResult.class,
                                    f.mapper.toString(f.response(4).result()))
                            .stopReason()
                            .name());
        }
    }

    @Test
    void midTurnChangesAreAcceptedAndLegacyModeAndSelectorsRemainSynchronized() throws Exception {
        try (final var f = new Fixture()) {
            final var id = f.create(1).sessionId();
            f.prompt(2, id);
            final var turn = f.turns.take();
            f.config(3, id, "provider", "default");
            assertEquals("b-main", f.configResult(3).configOptions().get(1).currentValue());
            assertEquals("a", turn.settings().providerId());
            f.send(4, "session/set_mode", new SetMode(id, "build"));
            assertNull(f.response(4).error());
            assertEquals(PermissionMode.ASK, f.runtimes.get(id).permissions().permissionMode());
            f.config(5, id, "mode", "plan");
            assertEquals("plan", f.configResult(5).configOptions().get(3).currentValue());
            assertTrue(f.updates.stream()
                    .anyMatch(update -> "current_mode_update".equals(update.get("sessionUpdate"))
                            && "build".equals(update.get("currentModeId"))));
            assertTrue(f.updates.stream()
                    .anyMatch(update -> "current_mode_update".equals(update.get("sessionUpdate"))
                            && "plan".equals(update.get("currentModeId"))));
            turn.done().complete(true);
            assertNull(f.response(2).error());
        }
    }

    @Test
    void blockedSwitchDoesNotBlockReaderAndCancellationAlsoCancelsQueuedPrompt() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (final var f = new Fixture()) {
            final var id = f.create(1).sessionId();
            f.accept = settings -> {
                if ("default".equals(settings.providerId())) {
                    entered.countDown();
                    await(release);
                }
                return true;
            };
            f.config(2, id, "provider", "default");
            f.prompt(3, id);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // initialize is handled on the reader. A response proves a blocked hook cannot hold it.
            f.send(4, "initialize", new Initialize(1, null, null));
            assertNotNull(f.response(4).error());
            f.send(null, "session/cancel", new Session(id));
            f.send(5, "initialize", new Initialize(1, null, null));
            assertNotNull(f.response(5).error()); // cancellation has been read before releasing the hook
            release.countDown();
            assertEquals("default", f.configResult(2).configOptions().getFirst().currentValue());
            assertEquals(
                    "cancelled",
                    f.mapper
                            .fromString(
                                    PromptResult.class,
                                    f.mapper.toString(f.response(3).result()))
                            .stopReason()
                            .name());
            assertTrue(f.turns.isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    void invalidIdsTypesValuesAndHookVetoReturnActualUnchangedSelection() throws Exception {
        try (final var f = new Fixture()) {
            final var id = f.create(1).sessionId();
            int request = 2;
            for (final var value : List.of(true, 42, List.of("high"), Map.of("value", "high"), "unavailable")) {
                f.config(request, id, "variant", value);
                assertEquals(-32602, f.response(request++).error().code());
            }
            f.config(request, id, "missing", "high");
            assertEquals(-32602, f.response(request++).error().code());
            f.accept = settings -> !"default".equals(settings.providerId());
            f.config(request, id, "provider", "default");
            assertEquals("a", f.configResult(request).configOptions().getFirst().currentValue());
            assertEquals("a", f.runtimes.get(id).chatSettings().providerId());
        }
    }

    @Test
    void lazyDiscoveryIsSharedAndLateOldProviderResultCannotReplaceCurrentModels() throws Exception {
        try (final var f = new Fixture()) {
            final var first = f.create(1);
            assertEquals("a", f.lookups.take());
            final var second = f.create(2);
            assertTrue(f.lookups.isEmpty());
            f.config(3, first.sessionId(), "provider", "default");
            assertEquals("b-main", f.configResult(3).configOptions().get(1).currentValue());
            assertEquals("default", f.lookups.take());
            f.sources.get("a").complete(models("a-extra"));
            f.sources.get("default").complete(models("b-extra"));
            final var firstUpdate = f.untilOptions(first.sessionId(), "b-extra");
            assertEquals("default", firstUpdate.getFirst().currentValue());
            assertFalse(firstUpdate.get(1).options().stream()
                    .anyMatch(value -> value.value().equals("a-extra")));
            final var secondUpdate = f.untilOptions(second.sessionId(), "a-extra");
            assertEquals("a", secondUpdate.getFirst().currentValue());
            f.config(4, first.sessionId(), "model", "b-extra");
            assertEquals("b-extra", f.configResult(4).configOptions().get(1).currentValue());
        }
    }

    @Test
    void agentOriginatedModelAndModeChangesNotifyEvenOutsideActiveTurnAndDisconnectCancelsDiscovery() throws Exception {
        final var f = new Fixture();
        try (f) {
            final var id = f.create(1).sessionId();
            assertEquals("a", f.lookups.take());
            final var runtime = f.runtimes.get(id);
            runtime.updateChatSettings(new ChatSettings("a", "agent-picked", "low", null));
            f.server.onModelSwitched(new ModelSwitchedEvent(
                    new EventContext(id, null, null, f.cwd.toString(), "ASK", "PLAN", null, null),
                    "a-main",
                    "agent-picked",
                    "low"));
            assertEquals("low", f.untilOptions(id, "agent-picked").get(2).currentValue());
            f.config(2, id, "model", "a-main");
            assertTrue(f.configResult(2).configOptions().get(1).options().stream()
                    .anyMatch(value -> "agent-picked".equals(value.value())));
            f.config(3, id, "model", "agent-picked");
            assertEquals(
                    "agent-picked", f.configResult(3).configOptions().get(1).currentValue());
            runtime.permissions().setMode(AgentMode.BUILD);
            runtime.permissions().setPermissionMode(PermissionMode.YOLO);
            f.shells.active(id).frontend().onEvent(new AgentEvent.TextDelta("", id, eventWorkdir));
            f.untilMode(id, "build");
            // The notification does not await the queued permission reset; a no-op model
            // request is a session command barrier without resetting permissions itself.
            f.config(4, id, "model", "agent-picked");
            assertEquals(
                    "agent-picked", f.configResult(4).configOptions().get(1).currentValue());
            assertEquals(PermissionMode.ASK, runtime.permissions().permissionMode());
        }
        assertTrue(f.sources.get("a").isCancelled());
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ModelsResponse models(final String id) {
        return new ModelsResponse(List.of(new ModelsResponse.Model(id, null, null)));
    }

    private record Turn(ChatSettings settings, CompletableFuture<Boolean> done) {}

    private final class Fixture implements AutoCloseable {
        private final JsonMapper mapper = CodecsTest.acpMapper();
        private final Map<String, CompletableFuture<ModelsResponse>> sources = new ConcurrentHashMap<>();
        private final BlockingQueue<String> lookups = new LinkedBlockingQueue<>();
        private final OptionTestSupport support;
        private final Map<String, SessionRuntime> runtimes = new ConcurrentHashMap<>();
        private final BlockingQueue<Turn> turns = new LinkedBlockingQueue<>();
        private final List<CompletableFuture<Boolean>> completions = new CopyOnWriteArrayList<>();
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final Map<Object, Envelope> replies = new HashMap<>();
        private final List<Map<String, Object>> updates = new ArrayList<>();
        private final List<UpdateParams> notifications = new ArrayList<>();
        private final PipedInputStream input = new PipedInputStream(65536);
        private final PrintWriter writer = new PrintWriter(new PipedOutputStream(input), true);
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final PrintStream output = new PrintStream(OutputStream.nullOutputStream()) {
            @Override
            public void println(final String line) {
                lines.add(line);
            }
        };
        private final ShellRegistry shells = new ShellRegistry(new Emitter() {
            @Override
            public <T> void emit(final T event) {}
        });
        private volatile Predicate<ChatSettings> accept = settings -> true;
        private volatile Runnable beforePromptDecode = () -> {};
        private final Path cwd = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        private final AcpServer server;
        private int sessionId;

        private Fixture() throws Exception {
            support = new OptionTestSupport(configuration, commandOptions, mapper, settings -> {
                final var source = new CompletableFuture<ModelsResponse>();
                sources.put(settings.providerId(), source);
                lookups.add(settings.providerId());
                return source;
            });
            final var store = new SessionStore() {
                @Override
                public String create() {
                    return "s" + ++sessionId;
                }

                @Override
                public void markProject(final String id, final String path) {}
            };
            final var lifecycle = new SessionLifecycle(store) {
                @Override
                public void release(final String id, final SessionRuntime runtime) {}
            };
            final var factory = new SessionRuntimeFactory() {
                @Override
                public Path resolveWorkdir(final Path explicitWorkdir, final String resumedSessionId) {
                    return Objects.requireNonNull(explicitWorkdir, "workdir");
                }

                @Override
                public SessionRuntime create(
                        final String key,
                        final String id,
                        final ToolRegistry registry,
                        final Path workdir,
                        final Boolean sandboxOverride) {
                    final var runtime = support.runtime(ChatSettings.EMPTY, settings -> accept.test(settings), workdir);
                    runtimes.put(id, runtime);
                    return runtime;
                }
            };
            final var mcp = new McpManager(null, mapper, List.of(), null, null) {
                @Override
                public void start() {}
            };
            final var runner = new TurnRunner(null, null, null) {
                @Override
                public CompletionStage<Boolean> submit(final TurnRequest request) {
                    final var completion = new CompletableFuture<Boolean>();
                    completions.add(completion);
                    turns.add(new Turn(request.loop().chatSettings(), completion));
                    return completion;
                }
            };
            server = new AcpServer(
                    (JsonMapper) Proxy.newProxyInstance(
                            JsonMapper.class.getClassLoader(),
                            new Class<?>[] {JsonMapper.class},
                            (proxy, method, args) -> {
                                if ("fromString".equals(method.getName()) && args[0] == Prompt.class) {
                                    beforePromptDecode.run();
                                }
                                try {
                                    return method.invoke(mapper, args);
                                } catch (final InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            }),
                    factory,
                    lifecycle,
                    shells,
                    store,
                    runner,
                    new ToolRegistry(mcp),
                    mcp,
                    support.options,
                    null,
                    new SecretNames(support.configuration),
                    List.of(),
                    null,
                    null,
                    null,
                    support.providers,
                    support.configuration,
                    null,
                    null,
                    support.commandOptions);
            Thread.ofVirtual().start(() -> {
                try {
                    server.run(input, output, ChatSettings.EMPTY);
                    done.complete(null);
                } catch (final Throwable e) {
                    done.completeExceptionally(e);
                }
            });
            send(0, "initialize", new Initialize(1, Map.of(), null));
            assertNull(response(0).error());
        }

        private Created create(final int request) throws Exception {
            send(request, "session/new", new NewSession(cwd.toString(), List.of()));
            final var reply = response(request);
            assertNull(reply.error(), () -> mapper.toString(reply));
            return mapper.fromString(Created.class, mapper.toString(reply.result()));
        }

        private void send(final Integer id, final String method, final Object params) {
            writer.println(mapper.toString(Envelope.of("2.0", id, method, params, null, null)));
        }

        private void config(final int request, final String id, final String option, final Object value) {
            send(request, "session/set_config_option", new SetConfig(id, option, value));
        }

        private void prompt(final int request, final String id) {
            send(request, "session/prompt", new Prompt(id, List.of(Content.text("hello"))));
        }

        private ConfigResult configResult(final int request) throws Exception {
            final var reply = response(request);
            assertNull(reply.error(), () -> mapper.toString(reply));
            return mapper.fromString(ConfigResult.class, mapper.toString(reply.result()));
        }

        private Envelope response(final int id) throws Exception {
            final var key = Integer.toString(id);
            while (!replies.containsKey(key)) {
                receive();
            }
            return replies.remove(key);
        }

        @SuppressWarnings("unchecked")
        private void receive() throws Exception {
            final var message = mapper.fromString(Envelope.class, lines.take());
            if (message.id() != null) {
                replies.put(message.id().toString(), message);
            } else {
                final var params = mapper.fromString(UpdateParams.class, mapper.toString(message.params()));
                notifications.add(params);
                updates.add((Map<String, Object>) params.update());
            }
        }

        private List<ConfigOption> untilOptions(final String session, final String model) throws Exception {
            while (true) {
                for (final var params : notifications) {
                    if (session.equals(params.sessionId())) {
                        final var update = mapper.fromString(ConfigUpdate.class, mapper.toString(params.update()));
                        if ("config_option_update".equals(update.sessionUpdate())
                                && update.configOptions().get(1).options().stream()
                                        .anyMatch(value -> value.value().equals(model))) {
                            return update.configOptions();
                        }
                    }
                }
                receive();
            }
        }

        private void untilMode(final String session, final String mode) throws Exception {
            while (true) {
                for (final var params : notifications) {
                    final var update = mapper.fromString(ModeUpdate.class, mapper.toString(params.update()));
                    if (session.equals(params.sessionId())
                            && "current_mode_update".equals(update.sessionUpdate())
                            && mode.equals(update.currentModeId())) {
                        return;
                    }
                }
                receive();
            }
        }

        @Override
        public void close() throws Exception {
            completions.forEach(completion -> completion.complete(true));
            writer.close();
            done.get(5, TimeUnit.SECONDS);
            output.close();
            input.close();
            support.close();
            mapper.close();
        }
    }
}
