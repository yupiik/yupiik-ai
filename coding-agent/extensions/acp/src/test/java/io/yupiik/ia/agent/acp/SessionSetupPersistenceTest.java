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
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.events.ModelSwitchedEvent;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.security.SecretNames;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.FileSessionStore;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionMetadata;
import io.yupiik.ia.agent.core.shell.*;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class SessionSetupPersistenceTest {

    private Map<String, Object> prompt(final String mode, final String text) {
        return Map.of("mode", mode, "content", List.of(Map.of("type", "text", "text", text)));
    }

    private ChatClient.SamplingOverride sampling() {
        return new ChatClient.SamplingOverride(Map.of(
                ChatClient.SamplingField.TEMPERATURE,
                Optional.of(0.25),
                ChatClient.SamplingField.TOP_P,
                Optional.empty(),
                ChatClient.SamplingField.TOP_K,
                Optional.of(8.0)));
    }

    private final class Fixture implements AutoCloseable {
        private final JsonMapper mapper = CodecsTest.mapper();
        private final OptionTestSupport support;
        private final AcpSessionOptions options;
        private final FileSessionStore store;
        private final Map<String, SessionRuntime> runtimes = new ConcurrentHashMap<>();
        private final List<Boolean> sandboxOverrides = new ArrayList<>();
        private final Map<String, String> contexts = new ConcurrentHashMap<>();
        private final Map<String, String> providerHistory = new ConcurrentHashMap<>();
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final Map<String, Envelope> responses = new HashMap<>();
        private final List<Object> notifications = new ArrayList<>();
        private final PipedInputStream input = new PipedInputStream(65536);
        private final PrintWriter writer = new PrintWriter(new PipedOutputStream(input), true);
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final ShellRegistry shells = new ShellRegistry(new Emitter() {
            @Override
            public <T> void emit(final T event) {}
        });
        private volatile Predicate<ChatSettings> accept = ignored -> true;
        private volatile CompletableFuture<Boolean> turnCompletion;
        private final CountDownLatch turnEntered = new CountDownLatch(1);
        private final AcpServer server;
        private final Envelope initialized;
        private final PrintStream output = new PrintStream(OutputStream.nullOutputStream()) {
            @Override
            public void println(final String line) {
                lines.add(line);
            }
        };

        private Fixture(final CodingAgentConfiguration configuration, final AcpCommand.Conf commandOptions)
                throws Exception {
            this(configuration, commandOptions, null);
        }

        private Fixture(
                final CodingAgentConfiguration configuration,
                final AcpCommand.Conf commandOptions,
                final AcpCommand.Conf commandBoundOptions)
                throws Exception {
            support = new OptionTestSupport(
                    configuration,
                    commandOptions,
                    mapper,
                    settings -> CompletableFuture.completedFuture(new ModelsResponse(List.of())));

            store = new FileSessionStore(support.configuration, mapper, Clock.systemUTC());
            final var agents = new AgentProvider(support.configuration);
            options = new AcpSessionOptions(
                    support.chat,
                    support.providers,
                    new AgentSessionService(
                            support.chat,
                            null,
                            agents,
                            store,
                            null,
                            null,
                            null,
                            null,
                            support.configuration,
                            null,
                            null));
            final var factory = new SessionRuntimeFactory() {
                @Override
                public void validateSessionContext(
                        final ChatSettings settings,
                        final Path root,
                        final List<Path> roots,
                        final SessionMetadata.Instructions instructions) {
                    final var builder = builder(root, new PermissionService(support.configuration, root));
                    builder.attach(settings);
                    builder.configureSessionContext(roots, instructions);
                }

                @Override
                public SessionRuntime create(
                        final String key,
                        final String id,
                        final ToolRegistry registry,
                        final Path root,
                        final Boolean sandboxOverride) {
                    sandboxOverrides.add(sandboxOverride);
                    final var permissions = new PermissionService(support.configuration, root);
                    final var loop = new AgentLoop(root, new DisabledSandbox(root)) {
                        private volatile ChatSettings selection = ChatSettings.EMPTY;

                        @Override
                        public ChatSettings chatSettings() {
                            return selection;
                        }

                        @Override
                        public void updateChatSettings(final ChatSettings value) {
                            if (accept.test(value)) {
                                selection = value;
                            }
                        }

                        @Override
                        public boolean iterationLimitReached() {
                            return false;
                        }

                        @Override
                        public boolean forceExtractNow(final List<Message> messages, final ChatSettings settings) {
                            return false;
                        }

                        @Override
                        public void close() {}
                    };
                    final var runtime = new SessionRuntime(
                            loop,
                            permissions,
                            new UserFeedbackService(),
                            null,
                            null,
                            null,
                            null,
                            builder(root, permissions),
                            root,
                            new DisabledSandbox(root));
                    runtimes.put(id, runtime);
                    return runtime;
                }
            };
            final var mcp = new McpManager(null, mapper, List.of(), null, null) {
                @Override
                public void start() {}
            };
            final var turns = new TurnRunner(null, null, null) {
                @Override
                public CompletionStage<Boolean> submit(final TurnRequest request) {
                    final var id = request.env().session().sessionId();
                    contexts.put(id, runtimes.get(id).systemPromptBuilder().build());
                    providerHistory.put(id, mapper.toString(store.load(id)));
                    store.append(id, SessionEntry.of(1, "user", "visible user", null, null));
                    store.append(id, SessionEntry.of(2, "assistant", "visible answer", null, null));
                    turnEntered.countDown();
                    return turnCompletion == null ? CompletableFuture.completedFuture(true) : turnCompletion;
                }
            };
            server =
                    new AcpServer(
                            mapper,
                            factory,
                            new SessionLifecycle(store),
                            shells,
                            store,
                            turns,
                            new ToolRegistry(mcp),
                            mcp,
                            options,
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
                            support.commandOptions) {
                        @Override
                        public void run(
                                final InputStream commandInput,
                                final PrintStream commandOutput,
                                final ChatSettings settings,
                                final Boolean sandboxOverride) {
                            super.run(input, commandOutput, settings, sandboxOverride);
                        }
                    };
            Thread.ofVirtual().start(() -> {
                try {
                    if (commandBoundOptions == null) {
                        server.run(input, output, ChatSettings.EMPTY);
                    } else {
                        new AcpCommand(
                                        commandBoundOptions,
                                        server,
                                        new AcpStdio(Optional.empty()) {
                                            @Override
                                            public PrintStream protocolOutput() {
                                                return output;
                                            }
                                        },
                                        support.providers,
                                        support.chat,
                                        null,
                                        null)
                                .run();
                    }
                    done.complete(null);
                } catch (final Throwable error) {
                    done.completeExceptionally(error);
                }
            });
            send(0, "initialize", new Initialize(1, Map.of(), null));
            initialized = response(0);
            assertNull(initialized.error());
        }

        private SystemPromptBuilder builder(final Path root, final PermissionService permissions) {
            return new SystemPromptBuilder(
                    support.configuration,
                    null,
                    null,
                    permissions,
                    new AgentProvider(support.configuration),
                    support.providers,
                    null,
                    null,
                    null,
                    root);
        }

        private String create(
                final int request, final Path cwd, final List<String> roots, final String mode, final String text)
                throws Exception {
            send(
                    request,
                    "session/new",
                    Map.of(
                            "cwd",
                            cwd.toString(),
                            "mcpServers",
                            List.of(),
                            "additionalDirectories",
                            roots,
                            "systemPrompt",
                            prompt(mode, text)));
            final var response = response(request);
            assertNull(response.error(), () -> mapper.toString(response));
            return decode(Created.class, response.result()).sessionId();
        }

        private void config(final int request, final String id, final String option, final String value)
                throws Exception {
            send(request, "session/set_config_option", new SetConfig(id, option, value));
            assertNull(response(request).error());
        }

        private void closeSession(final int request, final String id) throws Exception {
            send(request, "session/close", new Session(id));
            assertNull(response(request).error());
        }

        private void send(final Integer id, final String method, final Object params) {
            writer.println(mapper.toString(Envelope.of("2.0", id, method, params, null, null)));
        }

        private Envelope response(final int id) throws Exception {
            final var key = Integer.toString(id);
            while (!responses.containsKey(key)) {
                final var line = lines.poll(10, TimeUnit.SECONDS);
                assertNotNull(line, "Missing ACP response " + id);
                final var message = mapper.fromString(Envelope.class, line);
                if (message.id() != null) {
                    responses.put(message.id().toString(), message);
                } else {
                    notifications.add(message.params());
                }
            }
            return responses.remove(key);
        }

        private <T> T decode(final Class<T> type, final Object value) {
            return mapper.fromString(type, mapper.toString(value));
        }

        @Override
        public void close() throws Exception {
            if (turnCompletion != null) {
                turnCompletion.complete(true);
            }
            writer.close();
            done.get(5, TimeUnit.SECONDS);
            output.close();
            input.close();
            support.close();
            mapper.close();
        }
    }

    @Nested
    @FusionSupport(modules = ExactCapabilitiesAndTwo.Module.class)
    class ExactCapabilitiesAndTwo {
        @TempDir
        static Path home;

        @ParameterizedTest
        @NullSource
        @ValueSource(booleans = {false, true})
        void commandSandboxOverrideSurvivesNewLoadAndResume(
                final Boolean sandbox,
                @Fusion final CodingAgentConfiguration configuration,
                @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var bound = new AcpCommand.Conf(
                    null,
                    null,
                    null,
                    false,
                    false,
                    false,
                    null,
                    commandOptions.elicitationSensitiveMarkers(),
                    commandOptions.elicitationSensitiveWords(),
                    sandbox);
            // The server's injected configuration must not win over the command-bound override.
            final var injected = new AcpCommand.Conf(
                    null,
                    null,
                    null,
                    false,
                    false,
                    false,
                    null,
                    commandOptions.elicitationSensitiveMarkers(),
                    commandOptions.elicitationSensitiveWords(),
                    !Boolean.TRUE.equals(sandbox));
            try (final var f = new Fixture(configuration, injected, bound)) {
                final var first = f.create(1, home, List.of(), "append", "sandbox test");
                f.closeSession(2, first);
                f.send(3, "session/load", Map.of("sessionId", first, "cwd", home.toString(), "mcpServers", List.of()));
                assertNull(f.response(3).error());
                f.closeSession(4, first);
                f.send(
                        5,
                        "session/resume",
                        Map.of("sessionId", first, "cwd", home.toString(), "mcpServers", List.of()));
                assertNull(f.response(5).error());
                f.create(6, home, List.of(), "append", "another session");
                assertEquals(Arrays.asList(sandbox, sandbox, sandbox, sandbox), f.sandboxOverrides);
            }
        }

        @Test
        void exactCapabilitiesAndTwoRootPromptPresetsRoundTripWithoutGrantsOrMetadataReplay(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var a = Files.createDirectory(home.resolve("A"));
            final var b = Files.createDirectory(home.resolve("B"));
            final String first;
            final String second;
            try (final var f = new Fixture(configuration, commandOptions)) {
                final var init = f.decode(Initialized.class, f.initialized.result());
                final var caps = init.agentCapabilities().sessionCapabilities();
                assertEquals(Map.of(), caps.get("additionalDirectories"));
                assertEquals(Map.of("mode", Map.of("override", Map.of())), caps.get("systemPrompt"));
                assertFalse(caps.containsKey("fork"));
                assertFalse(init.agentCapabilities().promptCapabilities().image());
                first = f.create(
                        1, a, List.of(b.toString(), b.resolve(".").toString(), a.toString()), "append", "PRIVATE_A");
                second = f.create(2, b, List.of(), "override", "PRIVATE_B");
                f.config(3, first, "provider", "default");
                f.config(4, first, "variant", "low");
                f.config(5, first, "mode", "plan");
                f.config(6, second, "variant", "high");
                f.config(7, second, "mode", "build");
                final var selected = new ChatSettings("default", "b-main", "low", sampling());
                f.runtimes.get(first).updateChatSettings(selected);
                f.shells.active(first).session().activeAgent("reviewer");
                f.runtimes.get(first).permissions().grantSession("read");
                // A normal settled model-switch event must persist sampling, not only selector requests.
                f.server.onModelSwitched(new ModelSwitchedEvent(
                        new EventContext(first, null, null, a.toString(), null, null, null, null),
                        null,
                        "b-main",
                        "low"));
                f.config(8, first, "variant", "low"); // queue barrier, preserving sampling on no-op
                assertEquals(selected, f.options.restore(f.store.metadata(first)));
                f.send(9, "session/prompt", new Prompt(first, List.of(Content.text("visible user"))));
                assertNull(f.response(9).error());
                assertTrue(f.contexts.get(first).contains("PRIVATE_A"));
                assertTrue(f.contexts.get(first).contains(b.toString()));
                assertFalse(f.contexts.get(first).contains("PRIVATE_B"));
                assertFalse(f.providerHistory.get(first).contains("TEMPERATURE"));
                assertFalse(f.providerHistory.get(first).contains("PRIVATE_A"));
                assertTrue(f.runtimes.get(second).systemPromptBuilder().build().contains("PRIVATE_B"));
                assertFalse(f.runtimes.get(second).systemPromptBuilder().build().contains("You are yca"));
                f.store.replace(
                        first,
                        List.of(
                                Message.user("<compacted-context>public summary</compacted-context>"),
                                Message.assistant("visible tail", null)));
                f.closeSession(10, first);
                f.closeSession(11, second); // legitimate empty session remains loadable
                assertEquals(2, f.store.list().size());
                assertEquals("reviewer", f.store.metadata(first).agent());
                assertEquals("plan", f.store.metadata(first).mode());
                assertEquals("build", f.store.metadata(second).mode());
                assertTrue(f.store.isEmpty(second));
            }
            try (final var f = new Fixture(configuration, commandOptions)) {
                f.send(1, "session/load", Map.of("sessionId", first, "cwd", a.toString(), "mcpServers", List.of()));
                final var loaded = f.decode(Loaded.class, f.response(1).result());
                assertEquals("plan", loaded.modes().currentModeId());
                assertEquals(
                        List.of("provider", "model", "variant", "mode"),
                        loaded.configOptions().stream().map(ConfigOption::id).toList());
                assertEquals(
                        new ChatSettings("default", "b-main", "low", sampling()),
                        f.runtimes.get(first).chatSettings());
                assertEquals("reviewer", f.shells.active(first).session().activeAgent());
                assertEquals(
                        PermissionMode.ASK, f.runtimes.get(first).permissions().permissionMode());
                assertTrue(f.runtimes.get(first).permissions().sessionGrants().isEmpty());
                final var prompt = f.runtimes.get(first).systemPromptBuilder().build();
                assertTrue(prompt.contains("PRIVATE_A"));
                assertFalse(prompt.contains("Additional workspace directories"), "Omitted roots must not be restored");
                assertTrue(f.notifications.stream()
                        .anyMatch(it -> f.mapper.toString(it).contains("visible tail")));
                assertFalse(f.mapper.toString(f.notifications).contains("PRIVATE_A"));
                assertFalse(f.mapper.toString(f.notifications).contains("TEMPERATURE"));
                f.notifications.clear();
                f.send(
                        2,
                        "session/resume",
                        Map.of(
                                "sessionId",
                                second,
                                "cwd",
                                b.toString(),
                                "mcpServers",
                                List.of(),
                                "additionalDirectories",
                                List.of(a.toString()),
                                "systemPrompt",
                                prompt("append", "EXPLICIT_B")));
                final var resumed = f.decode(Loaded.class, f.response(2).result());
                assertEquals("build", resumed.modes().currentModeId());
                final var replacement =
                        f.runtimes.get(second).systemPromptBuilder().build();
                assertTrue(replacement.contains("EXPLICIT_B"));
                assertTrue(replacement.contains("You are yca"));
                assertFalse(replacement.contains("PRIVATE_B"));
                assertTrue(replacement.contains("Additional workspace directories"));
                assertFalse(f.notifications.stream()
                        .anyMatch(it -> f.mapper.toString(it).contains("message_chunk")));
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(home);
            }
        }
    }

    @Nested
    @FusionSupport(modules = InvalidShapesOversizedInstructions.Module.class)
    class InvalidShapesOversizedInstructions {
        @TempDir
        static Path home;

        @Test
        void invalidShapesOversizedInstructionsAndRootsHaveNoCreationOrLoadSideEffects(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var a = Files.createDirectory(home.resolve("A"));
            try (final var f = new Fixture(configuration, commandOptions)) {
                final var invalid = List.of(
                        Map.of("additionalDirectories", List.of("relative")),
                        Map.of(
                                "additionalDirectories",
                                List.of(home.resolve("missing").toString())),
                        Map.of("additionalDirectories", List.of(42)),
                        Map.of("additionalDirectories", Map.of()),
                        Map.of("systemPrompt", prompt("_unknown", "instruction")),
                        Map.of("systemPrompt", prompt("append", "x".repeat(8001))),
                        Map.of("systemPrompt", Map.of("mode", "append", "content", List.of())),
                        Map.of("systemPrompt", Map.of("content", List.of(Map.of("type", "text", "text", "x")))),
                        Map.of(
                                "systemPrompt",
                                Map.of(
                                        "mode",
                                        "append",
                                        "content",
                                        List.of(Map.of("type", "image", "data", "secret")))),
                        Map.of(
                                "systemPrompt",
                                Map.of(
                                        "mode",
                                        "append",
                                        "content",
                                        List.of(Map.of("type", "text", "content", Map.of("text", "x"))))),
                        Map.of(
                                "systemPrompt",
                                Map.of("mode", "append", "content", List.of(Map.of("type", "text", "text", 42)))));
                var request = 1;
                for (final var extra : invalid) {
                    final var params =
                            new HashMap<String, Object>(Map.of("cwd", a.toString(), "mcpServers", List.of()));
                    params.putAll(extra);
                    f.send(request, "session/new", params);
                    assertEquals(-32602, f.response(request++).error().code());
                    assertTrue(f.store.list().isEmpty());
                    assertTrue(f.runtimes.isEmpty());
                }
                final var id = f.create(request++, a, List.of(), "override", "keep");
                final var before = f.store.metadata(id);
                f.closeSession(request++, id);
                for (final var extra : invalid) {
                    final var params = new HashMap<String, Object>(
                            Map.of("sessionId", id, "cwd", a.toString(), "mcpServers", List.of()));
                    params.putAll(extra);
                    f.send(request, "session/load", params);
                    assertEquals(-32602, f.response(request++).error().code());
                    assertEquals(before, f.store.metadata(id));
                    assertEquals(1, f.store.list().size());
                    assertNull(f.shells.active(id));
                }
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(home);
            }
        }
    }

    @Nested
    @FusionSupport(modules = SettledVetoAndConcurrent.Module.class)
    class SettledVetoAndConcurrent {
        @TempDir
        static Path home;

        @Test
        void settledVetoAndConcurrentDeleteDoNotPersistTheRequestedSelectionOrResurrectSession(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var root = Files.createDirectory(home.resolve("A"));
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            try (final var f = new Fixture(configuration, commandOptions)) {
                final var id = f.create(1, root, List.of(), "append", "retained");
                f.accept = settings -> {
                    if ("default".equals(settings.providerId())) {
                        entered.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return false;
                    }
                    return true;
                };
                f.send(2, "session/set_config_option", new SetConfig(id, "provider", "default"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                f.send(3, "session/delete", new Session(id));
                // Reader still handles cancellation/barriers while the selection hook is blocked.
                f.send(null, "session/cancel", new Session(id));
                f.send(4, "initialize", new Initialize(1, null, null));
                assertNotNull(f.response(4).error());
                release.countDown();
                assertNull(f.response(2).error());
                assertNull(f.response(3).error());
                assertTrue(f.store.list().isEmpty());
                assertNull(f.store.metadata(id));
                assertFalse(f.store.metadata(
                        id, new SessionMetadata(1, "a", "a-main", null, List.of(), null, "plan", null)));
                assertNull(f.shells.active(id));
            } finally {
                release.countDown();
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(home);
            }
        }
    }

    @Nested
    @FusionSupport(modules = DeleteWaitsForThe.Module.class)
    class DeleteWaitsForThe {
        @TempDir
        static Path home;

        @Test
        void deleteWaitsForTheActualTurnBeforeRemovingPreset(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var root = Files.createDirectory(home.resolve("A"));
            try (final var f = new Fixture(configuration, commandOptions)) {
                final var id = f.create(1, root, List.of(), "append", "retained");
                f.turnCompletion = new CompletableFuture<>();
                f.send(2, "session/prompt", new Prompt(id, List.of(Content.text("work"))));
                assertTrue(f.turnEntered.await(5, TimeUnit.SECONDS));
                f.send(3, "session/delete", new Session(id));
                f.send(4, "initialize", new Initialize(1, null, null));
                assertNotNull(f.response(4).error());
                assertNotNull(f.store.metadata(id), "A still-owned turn must retain its session preset");
                f.turnCompletion.complete(true);
                assertNull(f.response(2).error());
                assertNull(f.response(3).error());
                assertTrue(f.store.list().isEmpty());
                assertNull(f.store.metadata(id));
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(home);
            }
        }
    }

    @Nested
    @FusionSupport(modules = UnavailablePresetReferencesFail.Module.class)
    class UnavailablePresetReferencesFail {
        @TempDir
        static Path home;

        @Test
        void unavailablePresetReferencesFailBeforePublicationAndKeepStoredHistory(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var root = Files.createDirectory(home.resolve("A"));
            try (final var f = new Fixture(configuration, commandOptions)) {
                final var id = f.create(1, root, List.of(), "append", "retained");
                f.closeSession(2, id);
                final var history = f.store.load(id);
                final var invalid = List.of(
                        new SessionMetadata(
                                1, "removed-private-reference", "a-main", "high", List.of(), null, "plan", null),
                        new SessionMetadata(
                                1, "a", "a-main", "removed-private-reference", List.of(), null, "plan", null),
                        new SessionMetadata(
                                1, "a", "a-main", "high", List.of(), "removed-private-reference", "plan", null));
                var request = 3;
                for (final var preset : invalid) {
                    assertTrue(f.store.metadata(id, preset));
                    f.send(
                            request,
                            "session/resume",
                            Map.of("sessionId", id, "cwd", root.toString(), "mcpServers", List.of()));
                    final var error = f.response(request++).error();
                    assertEquals(-32602, error.code());
                    assertTrue(error.message().contains("unavailable"));
                    assertFalse(error.message().contains("removed-private-reference"));
                    assertEquals(preset, f.store.metadata(id));
                    assertEquals(history, f.store.load(id));
                    assertNull(f.shells.active(id));
                    assertEquals(1, f.runtimes.size());
                }
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(home);
            }
        }
    }

    public abstract static class ConfigurationModule extends OptionConfigurationTestModule {
        protected ConfigurationModule(final Path root) {
            super(Map.of(
                    "yca.storageHome",
                    root.toString(),
                    "yca.home",
                    root.toString(),
                    "yca.memory.enabled",
                    "false",
                    "yca.agents.length",
                    "1",
                    "yca.agents.0.name",
                    "reviewer",
                    "yca.agents.0.prompt",
                    "review carefully"));
        }
    }
}
