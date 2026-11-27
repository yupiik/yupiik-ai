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

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.sandbox.Sandbox;
import io.yupiik.ia.agent.core.security.SecretNames;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.*;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.btw.BtwSlashCommands;
import io.yupiik.ia.agent.core.shell.slash.ConfigCommands;
import io.yupiik.ia.agent.core.shell.slash.SessionCommands;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Raw-wire integration. No provider, process, sleeps, JVM properties, or production test hooks. */
@Timeout(15)
class StableV1IntegrationTest {

    private static final Pattern FINAL_TEXT = Pattern.compile("final text", Pattern.LITERAL);

    private static Field field(final Object owner, final String name) throws Exception {
        final var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Map<String, Object> lifecycle(final String id, final Path root) {
        return Map.of("sessionId", id, "cwd", root.toString(), "mcpServers", List.of());
    }

    private static Map<String, Object> nullable(final String key, final Object value) {
        final var map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }

    private static int code(final Map<String, Object> value) {
        return ((Number) object(value.get("error")).get("code")).intValue();
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private record Turn(TurnRunner.TurnRequest request, CompletableFuture<Boolean> done) {}

    private record SideQuestion(
            String question, BooleanSupplier cancelled, CompletableFuture<BtwAnswerer.BtwAnswer> done) {}

    private static final class Store implements SessionStore {
        private final Map<String, List<Message>> data = new ConcurrentHashMap<>();
        private final AtomicInteger ids = new AtomicInteger();

        @Override
        public String create() {
            final var id = "s" + ids.incrementAndGet();
            data.put(id, List.of());
            return id;
        }

        @Override
        public List<Message> load(final String id) {
            return data.getOrDefault(id, List.of());
        }

        @Override
        public void replace(final String id, final List<Message> messages) {
            data.put(id, List.copyOf(messages));
        }

        @Override
        public List<SessionInfo> list() {
            return data.keySet().stream()
                    .map(id -> new SessionInfo(id, 1000, 0))
                    .toList();
        }

        @Override
        public boolean isEmpty(final String id) {
            return load(id).stream().allMatch(Message::isProjectMarker);
        }

        @Override
        public void delete(final String id) {
            data.remove(id);
        }
    }

    private final class Fixture implements AutoCloseable {
        private final JsonMapper mapper = CodecsTest.mapper();
        private final OptionTestSupport support;
        private final Store store = new Store();
        private final ShellRegistry shells = new ShellRegistry(new Emitter() {
            @Override
            public <T> void emit(final T event) {}
        });
        private final Map<String, SessionRuntime> runtimes = new ConcurrentHashMap<>();
        private final Map<String, ToolRegistry> registries = new ConcurrentHashMap<>();
        private final BlockingQueue<Turn> turns = new LinkedBlockingQueue<>();
        private final BlockingQueue<SideQuestion> sideQuestions = new LinkedBlockingQueue<>();
        private final List<Turn> outstanding = new CopyOnWriteArrayList<>();
        private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicInteger disposals = new AtomicInteger();
        private final AtomicInteger extractions = new AtomicInteger();
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final List<Map<String, Object>> seen = new ArrayList<>();
        private final List<Map<String, Object>> deferred = new ArrayList<>();
        private final PipedInputStream input = new PipedInputStream(65536);
        private final PrintWriter writer = new PrintWriter(new PipedOutputStream(input), true);
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private volatile Consumer<String> beforeCreate = id -> {};
        private final Path root;
        private final Map<String, Object> initialized;

        private Fixture(
                final CodingAgentConfiguration configuration,
                final Path root,
                final Map<String, Object> capabilities,
                final AcpCommand.Conf commandOptions)
                throws Exception {
            this(configuration, root, capabilities, commandOptions, true);
        }

        private Fixture(
                final CodingAgentConfiguration configuration,
                final Path root,
                final Map<String, Object> capabilities,
                final AcpCommand.Conf commandOptions,
                final boolean initialize)
                throws Exception {
            support = new OptionTestSupport(
                    configuration,
                    commandOptions,
                    mapper,
                    settings -> CompletableFuture.completedFuture(new ModelsResponse(List.of())));
            this.root = root;
            final var factory = new SessionRuntimeFactory() {
                @Override
                public SessionRuntime create(
                        final String key,
                        final String id,
                        final ToolRegistry registry,
                        final Path cwd,
                        final Boolean sandboxOverride) {
                    assertFalse(
                            store.load(id).isEmpty(),
                            "new root marker precedes factory; restore preserves stored history");
                    beforeCreate.accept(id);
                    final var loop = new AgentLoop(cwd, new DisabledSandbox(cwd)) {
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
                        public boolean updateChatSettings(
                                final ChatSettings settings, final BooleanSupplier cancelled) {
                            if (cancelled.getAsBoolean()) {
                                return false;
                            }
                            selection = settings;
                            return true;
                        }

                        @Override
                        public boolean iterationLimitReached() {
                            return false;
                        }

                        @Override
                        public boolean forceExtractNow(final List<Message> messages, final ChatSettings settings) {
                            extractions.incrementAndGet();
                            return true;
                        }

                        @Override
                        public void refreshUsage() {}

                        @Override
                        public void close() {
                            disposals.incrementAndGet();
                        }

                        @Override
                        public ToolRegistry effectiveToolRegistry() {
                            return registry;
                        }

                        @Override
                        public Set<String> availableToolNames() {
                            return registry.tools().stream()
                                    .map(tool -> tool.name())
                                    .collect(Collectors.toSet());
                        }
                    };
                    final var permissions = new PermissionService(support.configuration, cwd);
                    permissions.setMode(AgentMode.BUILD);
                    final var runtime = new SessionRuntime(
                            loop,
                            permissions,
                            new UserFeedbackService(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            cwd,
                            new DisabledSandbox(cwd));
                    runtimes.put(id, runtime);
                    registries.put(id, registry);
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
                    final var turn = new Turn(request, new CompletableFuture<>());
                    outstanding.add(turn);
                    turns.add(turn);
                    return turn.done;
                }
            };
            final var tools = new ToolRegistry(mcp);
            final var config = new ConfigCommands(null, support.chat, support.providers, null, null, null, null);
            final var btw =
                    new BtwAnswerer(
                            support.chat,
                            store,
                            support.configuration,
                            tasks,
                            null,
                            tools,
                            null,
                            mapper,
                            support.providers,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null) {
                        @Override
                        public CompletionStage<BtwAnswer> ask(
                                final String question,
                                final String sessionId,
                                final UsageTracker usage,
                                final BooleanSupplier cancelled,
                                final ApprovalGate gate,
                                final ChatSettings settings,
                                final SystemPromptBuilder systemPrompt,
                                final UserFeedbackService feedback,
                                final Path workdir,
                                final ToolRegistry registry,
                                final Set<String> ceiling,
                                final Sandbox sandbox) {
                            assertEquals(root, workdir);
                            assertSame(runtimes.get(sessionId).feedback(), feedback);
                            assertEquals(runtimes.get(sessionId).chatSettings(), settings);
                            assertNotNull(gate);
                            assertSame(registries.get(sessionId), registry);
                            assertEquals(runtimes.get(sessionId).loop().availableToolNames(), ceiling);
                            final var done = new CompletableFuture<BtwAnswer>();
                            sideQuestions.add(new SideQuestion(question, cancelled, done));
                            return done;
                        }
                    };
            final var server = new AcpServer(
                    mapper,
                    factory,
                    new SessionLifecycle(store),
                    shells,
                    store,
                    runner,
                    tools,
                    mcp,
                    support.options,
                    null,
                    new SecretNames(support.configuration),
                    List.of(config, new SessionCommands(store, null), new BtwSlashCommands(btw)),
                    btw,
                    null,
                    null,
                    support.providers,
                    support.configuration,
                    null,
                    null,
                    commandOptions);
            final var output = new PrintStream(OutputStream.nullOutputStream()) {
                @Override
                public void println(final String line) {
                    lines.add(line);
                }
            };
            Thread.ofVirtual().start(() -> {
                try {
                    server.run(input, output, ChatSettings.EMPTY);
                    done.complete(null);
                } catch (final Throwable failure) {
                    done.completeExceptionally(failure);
                }
            });
            if (!initialize) {
                initialized = Map.of();
                return;
            }
            final var params = new LinkedHashMap<String, Object>();
            params.put("protocolVersion", 1);
            if (capabilities != null) {
                params.put("clientCapabilities", capabilities);
            }
            send("initialize", "initialize", params);
            final var initializedResponse = response("initialize");
            assertNull(
                    initializedResponse.get("error"),
                    () -> String.valueOf(capabilities) + " -> " + initializedResponse);
            initialized = object(initializedResponse.get("result"));
        }

        private String create() throws Exception {
            send("new-session", "session/new", Map.of("cwd", root.toString(), "mcpServers", List.of()));
            final var response = response("new-session");
            assertNull(response.get("error"), response.toString());
            return (String) object(response.get("result")).get("sessionId");
        }

        private void prompt(final String request, final String session, final String text) {
            send(
                    request,
                    "session/prompt",
                    Map.of("sessionId", session, "prompt", List.of(Map.of("type", "text", "text", text))));
        }

        private void send(final Object id, final String method, final Object params) {
            final var envelope = new LinkedHashMap<String, Object>();
            envelope.put("jsonrpc", "2.0");
            if (id != null) {
                envelope.put("id", id);
            }
            envelope.put("method", method);
            envelope.put("params", params);
            raw(mapper.toString(envelope));
        }

        private void raw(final String line) {
            writer.println(line);
        }

        private void reply(final Map<String, Object> request, final Object result) {
            raw(mapper.toString(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result)));
        }

        private Map<String, Object> response(final Object id) throws Exception {
            return until(value -> !value.containsKey("method") && Objects.equals(id, value.get("id")));
        }

        private Map<String, Object> method(final String name) throws Exception {
            return until(value -> name.equals(value.get("method")));
        }

        private Map<String, Object> updateContaining(final String text) throws Exception {
            return object(until(value -> "session/update".equals(value.get("method"))
                            && mapper.toString(value).contains(text))
                    .get("params"));
        }

        private Map<String, Object> until(final Predicate<Map<String, Object>> matches) throws Exception {
            for (final var iterator = deferred.iterator(); iterator.hasNext(); ) {
                final var value = iterator.next();
                if (matches.test(value)) {
                    iterator.remove();
                    return value;
                }
            }
            while (true) {
                final var line = lines.poll(5, TimeUnit.SECONDS);
                assertNotNull(line, "Expected a protocol message");
                final var value = object(mapper.fromString(Object.class, line));
                seen.add(value);
                if (matches.test(value)) {
                    return value;
                }
                deferred.add(value);
            }
        }

        private ToolContext context(final String id) {
            final var runtime = runtimes.get(id);
            return new ToolContext(
                    id,
                    null,
                    null,
                    runtime.permissions(),
                    runtime.feedback(),
                    null,
                    null,
                    null,
                    null,
                    () -> false,
                    runtime.chatSettings(),
                    () -> false,
                    runtime.workdir(),
                    new DisabledSandbox(runtime.workdir()),
                    1,
                    null,
                    null,
                    runtime.loop().effectiveToolRegistry());
        }

        @Override
        public void close() throws Exception {
            outstanding.forEach(turn -> turn.done.complete(true));
            writer.close();
            done.get(10, TimeUnit.SECONDS);
            tasks.shutdownNow();
            assertTrue(tasks.awaitTermination(5, TimeUnit.SECONDS));
            support.close();
            mapper.close();
        }
    }

    @Nested
    @FusionSupport(modules = AddedPolicyModule.class)
    class AddedPolicy {

        @Test
        void configuredElicitationPolicyIsAppliedBeforeSessionPublication(
                @TempDir final Path root,
                @Fusion final CodingAgentConfiguration configuration,
                @Fusion final AcpCommand.Conf conf)
                throws Exception {
            final var additions = true;
            final var capabilities = Map.<String, Object>of("elicitation", Map.of("form", Map.of()));
            try (final var f = new Fixture(configuration, root, capabilities, conf)) {
                final var id = f.create();
                f.prompt("turn", id, "go");
                final var turn = f.turns.take();
                final var feedback = f.shells.active(id).io().feedbackGate();
                assertNull(
                        feedback.ask(List.of(new Question("api_token", "Value", List.of(), false))),
                        "empty or customized ACP additions cannot disable core SecretNames");
                if (additions) {
                    for (final var question : List.of(
                            new Question("TEAM_ENTRY", "Value", List.of(), false),
                            new Question("choice", "Select stepup mode", List.of(), false),
                            new Question("choice", "Value", List.of("stepup_code"), false))) {
                        assertNull(feedback.ask(List.of(question)));
                    }
                }
                // Replacing the defaults is intentional; policy is not independent approval to collect credentials.
                final var question = new Question(
                        "choice", additions ? "Select spinning mode" : "Select stepup mode", List.of("blue"), false);
                final var answer = f.tasks.submit(() -> feedback.ask(List.of(question)));
                final var request = f.method("elicitation/create");
                assertEquals(id, object(request.get("params")).get("sessionId"));
                f.reply(request, Map.of("action", "accept", "content", Map.of("choice", "blue")));
                assertEquals(Map.of("choice", "blue"), answer.get(5, TimeUnit.SECONDS));
                turn.done.complete(true);
                assertNull(f.response("turn").get("error"));
                assertEquals(
                        1,
                        f.seen.stream()
                                .filter(message -> "elicitation/create".equals(message.get("method")))
                                .count());
            }
        }
    }

    @Nested
    @FusionSupport(modules = EmptyPolicyModule.class)
    class EmptyPolicy {

        @Test
        void configuredElicitationPolicyIsAppliedBeforeSessionPublication(
                @TempDir final Path root,
                @Fusion final CodingAgentConfiguration configuration,
                @Fusion final AcpCommand.Conf conf)
                throws Exception {
            final var additions = false;
            final var capabilities = Map.<String, Object>of("elicitation", Map.of("form", Map.of()));
            try (final var f = new Fixture(configuration, root, capabilities, conf)) {
                final var id = f.create();
                f.prompt("turn", id, "go");
                final var turn = f.turns.take();
                final var feedback = f.shells.active(id).io().feedbackGate();
                assertNull(
                        feedback.ask(List.of(new Question("api_token", "Value", List.of(), false))),
                        "empty or customized ACP additions cannot disable core SecretNames");
                if (additions) {
                    for (final var question : List.of(
                            new Question("TEAM_ENTRY", "Value", List.of(), false),
                            new Question("choice", "Select stepup mode", List.of(), false),
                            new Question("choice", "Value", List.of("stepup_code"), false))) {
                        assertNull(feedback.ask(List.of(question)));
                    }
                }
                // Replacing the defaults is intentional; policy is not independent approval to collect credentials.
                final var question = new Question(
                        "choice", additions ? "Select spinning mode" : "Select stepup mode", List.of("blue"), false);
                final var answer = f.tasks.submit(() -> feedback.ask(List.of(question)));
                final var request = f.method("elicitation/create");
                assertEquals(id, object(request.get("params")).get("sessionId"));
                f.reply(request, Map.of("action", "accept", "content", Map.of("choice", "blue")));
                assertEquals(Map.of("choice", "blue"), answer.get(5, TimeUnit.SECONDS));
                turn.done.complete(true);
                assertNull(f.response("turn").get("error"));
                assertEquals(
                        1,
                        f.seen.stream()
                                .filter(message -> "elicitation/create".equals(message.get("method")))
                                .count());
            }
        }
    }

    public static class AddedPolicyModule extends OptionConfigurationTestModule {
        public AddedPolicyModule() {
            super(Map.of("elicitationSensitiveMarkers", "team entry", "elicitationSensitiveWords", "stepup"));
        }
    }

    public static class EmptyPolicyModule extends OptionConfigurationTestModule {
        public EmptyPolicyModule() {
            super(Map.of("elicitationSensitiveMarkers", "", "elicitationSensitiveWords", ""));
        }
    }

    @Nested
    @FusionSupport(modules = OptionConfigurationTestModule.class)
    class Defaults {
        @Fusion
        private CodingAgentConfiguration configuration;

        @Fusion
        private AcpCommand.Conf commandOptions;

        @Test
        void protocolVersionNegotiationUsesTheUnsignedSixteenBitDomain(@TempDir final Path root) throws Exception {
            final var conf = commandOptions;
            for (final var version : List.of(0, 65535)) {
                try (final var f = new Fixture(configuration, root, Map.of(), conf, false)) {
                    for (final var invalid : List.of(-1, 65536, 1.5)) {
                        f.send("invalid", "initialize", Map.of("protocolVersion", invalid));
                        assertEquals(-32602, code(f.response("invalid")));
                    }
                    f.send("valid", "initialize", Map.of("protocolVersion", version));
                    final var response = f.response("valid");
                    assertNull(response.get("error"), response.toString());
                    assertEquals(1, ((Number) object(response.get("result")).get("protocolVersion")).intValue());
                }
            }
        }

        @Test
        void resumeDefaultsAnOmittedMcpListWithoutRelaxingNewOrLoad(@TempDir final Path root) throws Exception {
            try (final var f = new Fixture(configuration, root, Map.of(), commandOptions)) {
                f.send("invalid-new", "session/new", Map.of("cwd", root.toString()));
                assertEquals(-32602, code(f.response("invalid-new")));
                final var id = f.create();
                f.send("close", "session/close", Map.of("sessionId", id));
                f.response("close");
                final var params = Map.<String, Object>of("sessionId", id, "cwd", root.toString());
                f.send("invalid-load", "session/load", params);
                assertEquals(-32602, code(f.response("invalid-load")));
                f.send("resume", "session/resume", params);
                assertNull(f.response("resume").get("error"));
                assertEquals(root, f.runtimes.get(id).workdir());
            }
        }

        @Test
        void emptyPromptsCompleteWithoutProviderWorkAndResourceLinksRequireAName(@TempDir final Path root)
                throws Exception {
            try (final var f = new Fixture(configuration, root, Map.of(), commandOptions)) {
                final var id = f.create();
                for (final var blocks : List.of(List.of(), List.of(Map.of("type", "text", "text", "")))) {
                    f.send("empty", "session/prompt", Map.of("sessionId", id, "prompt", blocks));
                    final var response = f.response("empty");
                    assertNull(response.get("error"), response.toString());
                    assertEquals("end_turn", object(response.get("result")).get("stopReason"));
                    assertTrue(f.turns.isEmpty(), "An empty prompt must not invoke the provider");
                }
                f.send("missing", "session/prompt", Map.of("sessionId", id));
                assertEquals(-32602, code(f.response("missing")));
                f.send(
                        "link",
                        "session/prompt",
                        Map.of(
                                "sessionId",
                                id,
                                "prompt",
                                List.of(Map.of("type", "resource_link", "uri", "file:///reference"))));
                assertEquals(-32602, code(f.response("link")));
                assertTrue(f.turns.isEmpty());
            }
        }

        @Test
        void airV1CapabilitiesKeepClientToolsDisabledAndAllowFormElicitation(@TempDir final Path root)
                throws Exception {
            // Serialized by the installed AIR SDK 0.30.1-dev-134's local client capability factory.
            final Map<String, Object> capabilities;
            try (final var mapper = CodecsTest.mapper();
                    final var in = new InputStreamReader(
                            Objects.requireNonNull(getClass().getResourceAsStream("air-v1-client-capabilities.json")),
                            StandardCharsets.UTF_8)) {
                capabilities = object(mapper.read(Object.class, in));
            }
            try (final var f = new Fixture(configuration, root, capabilities, commandOptions)) {
                assertEquals(1, ((Number) f.initialized.get("protocolVersion")).intValue());
                assertEquals(List.of(), f.initialized.get("authMethods"));
                final var id = f.create();
                for (final var tool : List.of("client_read_text_file", "client_write_text_file", "client_terminal")) {
                    assertNull(f.registries.get(id).tool(tool), tool);
                }
                f.shells.active(id).frontend().notice(AgentEvent.Notice.Level.WARN, "AIR warning");
                assertEquals(
                        "agent_message_chunk",
                        object(f.updateContaining("AIR warning").get("update")).get("sessionUpdate"));
                f.prompt("turn", id, "go");
                final var turn = f.turns.take();
                final var answer = f.tasks.submit(() -> turn.request
                        .env()
                        .io()
                        .feedbackGate()
                        .ask(List.of(new Question("choice", "Choose a color", List.of("blue", "green"), false))));
                final var request = f.method("elicitation/create");
                assertEquals(id, object(request.get("params")).get("sessionId"));
                f.reply(request, Map.of("action", "accept", "content", Map.of("choice", "blue")));
                assertEquals(Map.of("choice", "blue"), answer.get(5, TimeUnit.SECONDS));
                turn.done.complete(true);
                assertEquals(
                        "end_turn", object(f.response("turn").get("result")).get("stopReason"));
                assertFalse(f.seen.stream().anyMatch(message -> {
                    final var method = message.get("method");
                    return method instanceof String name && (name.startsWith("fs/") || name.startsWith("terminal/"));
                }));
            }
        }

        @Test
        void lifecycleReplaysOnlyLoadAndPreservesEmptyHistoryUntilExplicitDelete(@TempDir final Path root)
                throws Exception {
            final var other = Files.createDirectory(root.resolve("other"));
            try (final var f = new Fixture(configuration, root, Map.of(), commandOptions)) {
                final var caps = object(f.initialized.get("agentCapabilities"));
                assertEquals(Boolean.TRUE, caps.get("loadSession"));
                assertEquals(
                        Set.of("list", "resume", "close", "delete", "additionalDirectories", "systemPrompt"),
                        object(caps.get("sessionCapabilities")).keySet());
                assertEquals(List.of(), f.initialized.get("authMethods"));
                final var id = f.create();
                assertEquals(root, f.runtimes.get(id).workdir());
                f.send("close", "session/close", Map.of("sessionId", id));
                assertEquals(Map.of(), f.response("close").get("result"));
                assertTrue(f.store.data.containsKey(id), "even a known empty session survives close");
                f.send("again", "session/close", Map.of("sessionId", id));
                assertEquals(Map.of(), f.response("again").get("result"));
                assertEquals(1, f.disposals.get());
                assertEquals(1, f.extractions.get());
                f.store.replace(
                        id,
                        List.of(
                                Message.projectDir(root.toString()),
                                Message.user("stored question"),
                                Message.assistant("stored answer", null)));
                f.send("list", "session/list", Map.of("cwd", root.toString()));
                final var listed = f.response("list");
                assertNull(listed.get("error"), listed.toString());
                assertEquals(
                        id,
                        object(((List<?>) object(listed.get("result")).get("sessions")).getFirst())
                                .get("sessionId"));
                f.seen.clear();
                f.send("load", "session/load", lifecycle(id, other));
                final var loaded = f.response("load");
                assertNull(loaded.get("error"));
                assertTrue(
                        f.seen.stream().anyMatch(line -> f.mapper.toString(line).contains("stored question")));
                assertTrue(
                        f.seen.stream().anyMatch(line -> f.mapper.toString(line).contains("stored answer")));
                assertEquals("load", f.seen.getLast().get("id"), "replay must finish before load response");
                final var live = f.runtimes.get(id);
                assertEquals(other, live.workdir(), "explicit cwd overrides stored root and startup root");
                f.send("busy", "session/load", lifecycle(id, root));
                assertEquals(-32602, code(f.response("busy")));
                assertSame(live, f.runtimes.get(id));
                f.send("close2", "session/close", Map.of("sessionId", id));
                f.response("close2");
                f.seen.clear();
                f.send("resume", "session/resume", lifecycle(id, root));
                assertNull(f.response("resume").get("error"));
                assertFalse(
                        f.seen.stream().anyMatch(line -> f.mapper.toString(line).contains("stored answer")));
                f.send("delete", "session/delete", Map.of("sessionId", id));
                assertEquals(Map.of(), f.response("delete").get("result"));
                assertFalse(f.store.data.containsKey(id));
                f.send("missing", "session/load", lifecycle(id, root));
                assertEquals(-32002, code(f.response("missing")));
                f.send("delete2", "session/delete", Map.of("sessionId", id));
                assertEquals(Map.of(), f.response("delete2").get("result"));
                assertTrue(f.shells.sessions().isEmpty());
            }
        }

        @Test
        void malformedLifecycleDoesNotCreateMarkOrReplaceHistory(@TempDir final Path root) throws Exception {
            try (final var f = new Fixture(configuration, root, Map.of(), commandOptions)) {
                for (final var extra : List.of(
                        Map.of("additionalDirectories", List.of("relative")),
                        Map.of(
                                "systemPrompt",
                                Map.of(
                                        "mode",
                                        "unsupported",
                                        "content",
                                        List.of(Map.of("type", "text", "text", "instruction")))))) {
                    final var params =
                            new LinkedHashMap<String, Object>(Map.of("cwd", root.toString(), "mcpServers", List.of()));
                    params.putAll(extra);
                    f.send("invalid", "session/new", params);
                    assertEquals(-32602, code(f.response("invalid")));
                    assertTrue(f.store.data.isEmpty());
                }
                f.send("relative", "session/new", Map.of("cwd", "relative", "mcpServers", List.of()));
                assertEquals(-32602, code(f.response("relative")));
                assertTrue(f.store.data.isEmpty());
                f.store.replace("known", List.of(Message.projectDir(root.toString()), Message.user("unchanged")));
                final var before = f.store.load("known");
                final var params = new LinkedHashMap<>(lifecycle("known", root));
                params.put("systemPrompt", Map.of("mode", "override", "content", List.of()));
                f.send("invalid-load", "session/load", params);
                assertEquals(-32602, code(f.response("invalid-load")));
                assertEquals(before, f.store.load("known"));
                assertTrue(f.runtimes.isEmpty());
                f.send("traversal", "session/delete", Map.of("sessionId", "../known"));
                assertEquals(-32602, code(f.response("traversal")));
            }
        }

        @Test
        void lifecycleReservationMakesCloseFollowAnInFlightLoadWithoutResurrection(@TempDir final Path root)
                throws Exception {
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            try (final var f = new Fixture(configuration, root, Map.of(), commandOptions)) {
                f.store.replace("saved", List.of(Message.projectDir(root.toString())));
                f.beforeCreate = id -> {
                    entered.countDown();
                    await(release);
                };
                f.send("load", "session/load", lifecycle("saved", root));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                f.send("close", "session/close", Map.of("sessionId", "saved"));
                // A reader barrier proves close was received while creation was still reserved.
                f.send("barrier", "initialize", Map.of("protocolVersion", 1));
                assertEquals(-32602, code(f.response("barrier")));
                release.countDown();
                assertNull(f.response("load").get("error"));
                assertNull(f.response("close").get("error"));
                assertTrue(f.shells.sessions().isEmpty());
                assertEquals(1, f.disposals.get());
                assertTrue(f.store.data.containsKey("saved"));
                f.beforeCreate = id -> {};
                assertNotNull(f.create(), "closed slots remain reusable");
            } finally {
                release.countDown();
            }
        }

        @Test
        void rawCapabilityNegotiationDoesNotInventNestedSupport(@TempDir final Path root) throws Exception {
            final var cases = Arrays.<Map<String, Object>>asList(
                    null,
                    Map.of(),
                    nullable("elicitation", null),
                    Map.of("elicitation", Map.of()),
                    Map.of("elicitation", nullable("form", null)),
                    Map.of("elicitation", Map.of("form", Map.of())),
                    Map.of(
                            "session",
                            Map.of(
                                    "notices",
                                    Map.of(),
                                    "compaction",
                                    Map.of(),
                                    "configOptions",
                                    Map.of("boolean", Map.of())),
                            "auth",
                            Map.of("terminal", true),
                            "fs",
                            Map.of("readTextFile", true),
                            "terminal",
                            true));
            for (final var capabilities : cases) {
                try (final var f = new Fixture(configuration, root, capabilities, commandOptions)) {
                    final var id = f.create();
                    final var clientRead = f.registries.get(id).tool("client_read_text_file");
                    assertEquals(capabilities != null && capabilities.containsKey("fs"), clientRead != null);
                    assertEquals(
                            capabilities != null && Boolean.TRUE.equals(capabilities.get("terminal")),
                            f.registries.get(id).tool("client_terminal") != null);
                    f.shells.active(id).frontend().notice(AgentEvent.Notice.Level.WARN, "visible warning");
                    final var warning = f.updateContaining("visible warning");
                    assertEquals(
                            capabilities != null && capabilities.containsKey("session")
                                    ? "notice"
                                    : "agent_message_chunk",
                            object(warning.get("update")).get("sessionUpdate"));
                    f.prompt("turn", id, "go");
                    final var turn = f.turns.take();
                    final var answer = f.tasks.submit(() -> turn.request
                            .env()
                            .io()
                            .feedbackGate()
                            .ask(List.of(new Question("choice", "Choose a color", List.of("blue", "green"), false))));
                    if (AcpElicitation.supportsForm(capabilities)) {
                        final var request = f.method("elicitation/create");
                        assertEquals(id, object(request.get("params")).get("sessionId"));
                        f.reply(request, Map.of("action", "accept", "content", Map.of("choice", "blue")));
                        assertEquals(Map.of("choice", "blue"), answer.get(5, TimeUnit.SECONDS));
                    } else {
                        assertNull(answer.get(5, TimeUnit.SECONDS));
                    }
                    turn.done.complete(true);
                    assertNull(f.response("turn").get("error"));
                    assertEquals(
                            List.of(),
                            f.initialized.get("authMethods"),
                            "no unsafe login descriptor even with auth.terminal");
                }
            }
        }

        @Test
        void malformedResponsesNeverConsumePermissionAndExplicitNullDenies(@TempDir final Path root) throws Exception {
            try (final var f = new Fixture(configuration, root, Map.of(), commandOptions)) {
                final var id = f.create();
                f.prompt("turn", id, "go");
                final var turn = f.turns.take();
                final var approval = f.tasks.submit(() ->
                        f.runtimes.get(id).permissions().check("write_file", ToolCategory.EDIT, Map.of("path", "a")));
                final var request = f.method("session/request_permission");
                final var wireId = f.mapper.toString(request.get("id"));
                for (final var suffix : List.of(
                        "\"result\":null,\"error\":null",
                        "\"error\":{\"code\":1.5,\"message\":\"secret-marker\"}",
                        "\"error\":{\"code\":2147483648,\"message\":\"secret-marker\"}",
                        "\"method\":null,\"result\":null")) {
                    f.raw("{\"jsonrpc\":\"2.0\",\"id\":" + wireId + "," + suffix + "}");
                    assertEquals(-32600, code(f.response(null)));
                    assertFalse(approval.isDone());
                }
                f.raw("{\"jsonrpc\":\"2.0\",\"id\":" + wireId + ",\"result\":null}");
                assertFalse(approval.get(5, TimeUnit.SECONDS).allowed());
                turn.done.complete(true);
                assertEquals(
                        Map.of("stopReason", "end_turn"), f.response("turn").get("result"));
                assertFalse(
                        f.seen.stream().anyMatch(line -> f.mapper.toString(line).contains("secret-marker")));
                f.raw("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"session/list\",\"params\":{}}");
                final var nullIdResponse = f.response(null);
                assertTrue(nullIdResponse.containsKey("id"));
                assertNull(nullIdResponse.get("id"));
                assertNull(nullIdResponse.get("error"));
                assertTrue(nullIdResponse.containsKey("result"));
                for (final var invalidId : List.of("1.5", "9223372036854775808")) {
                    f.raw("{\"jsonrpc\":\"2.0\",\"id\":" + invalidId + ",\"method\":\"session/list\",\"params\":{}}");
                    assertEquals(-32600, code(f.response(null)));
                }
            }
        }

        @Test
        void questionsGrantsAndClientRequestsRemainSessionScopedAndCancellationUnblocksOnlyOwner(
                @TempDir final Path root) throws Exception {
            final var capabilities =
                    Map.<String, Object>of("elicitation", Map.of("form", Map.of()), "fs", Map.of("readTextFile", true));
            try (final var f = new Fixture(configuration, root, capabilities, commandOptions)) {
                final var a = f.create();
                final var b = f.create();
                f.prompt("pa", a, "go");
                f.prompt("pb", b, "go");
                final var one = f.turns.take();
                final var two = f.turns.take();
                final var aTurn = one.request.sessionId().equals(a) ? one : two;
                final var bTurn = one.request.sessionId().equals(b) ? one : two;
                final var allow = f.tasks.submit(() ->
                        f.runtimes.get(a).permissions().check("write_file", ToolCategory.EDIT, Map.of("path", "a")));
                final var permission = f.method("session/request_permission");
                assertEquals(a, object(permission.get("params")).get("sessionId"));
                assertTrue(f.mapper.toString(permission).contains("Allow for this session only"));
                f.reply(permission, Map.of("outcome", Map.of("outcome", "selected", "optionId", "allow_session")));
                assertTrue(allow.get(5, TimeUnit.SECONDS).allowed());
                assertFalse(f.runtimes.get(a).permissions().sessionGrants().isEmpty());
                assertTrue(f.runtimes.get(b).permissions().sessionGrants().isEmpty());
                final var qa = f.tasks.submit(() -> f.shells
                        .active(a)
                        .io()
                        .feedbackGate()
                        .ask(List.of(new Question("a", "Color A", List.of(), false))));
                final var qb = f.tasks.submit(() -> f.shells
                        .active(b)
                        .io()
                        .feedbackGate()
                        .ask(List.of(new Question("b", "Color B", List.of(), false))));
                final var first = f.method("elicitation/create");
                final var second = f.method("elicitation/create");
                final var bQuestion =
                        object(first.get("params")).get("sessionId").equals(b) ? first : second;
                f.send(null, "session/cancel", Map.of("sessionId", a));
                assertNull(qa.get(5, TimeUnit.SECONDS));
                assertFalse(qb.isDone());
                f.reply(bQuestion, Map.of("action", "accept", "content", Map.of("b", "blue")));
                assertEquals(Map.of("b", "blue"), qb.get(5, TimeUnit.SECONDS));
                final var read = f.tasks.submit(() -> f.registries
                        .get(b)
                        .tool("client_read_text_file")
                        .execute(Map.of("path", "unsaved.txt"), f.context(b)));
                final var request = f.method("fs/read_text_file");
                assertEquals(b, object(request.get("params")).get("sessionId"));
                assertEquals(
                        root.resolve("unsaved.txt").toString(),
                        object(request.get("params")).get("path"));
                f.reply(request, Map.of("content", "unsaved editor contents"));
                assertEquals(
                        "unsaved editor contents", read.get(5, TimeUnit.SECONDS).output());
                aTurn.done.complete(true);
                bTurn.done.complete(true);
                assertEquals(Map.of("stopReason", "cancelled"), f.response("pa").get("result"));
                assertEquals(Map.of("stopReason", "end_turn"), f.response("pb").get("result"));
            }
        }

        @Test
        void requestDeadlineCancelsTimerAndLateTerminalCreationIsReleasedWithoutPersistentTombstones(
                @TempDir final Path root) throws Exception {
            try (final var f = new Fixture(configuration, root, Map.of("terminal", true), commandOptions)) {
                final var id = f.create();
                final var runtime = f.runtimes.get(id);
                runtime.permissions().setMode(AgentMode.BUILD);
                runtime.permissions().setPermissionMode(PermissionMode.YOLO);
                f.prompt("turn", id, "go");
                final var turn = f.turns.take();
                final var terminal = f.tasks.submit(() -> f.registries
                        .get(id)
                        .tool("client_terminal")
                        .execute(Map.of("command", "test-executable", "args", List.of("--exact")), f.context(id)));
                final var create = f.method("terminal/create");
                final var state = f.shells.active(id).frontend();
                final var connection = Arrays.stream(state.getClass().getDeclaredFields())
                        .filter(field -> field.getType().getSimpleName().equals("Connection"))
                        .findFirst()
                        .orElseThrow();
                connection.setAccessible(true);
                final var owner = connection.get(state);
                @SuppressWarnings("unchecked")
                final var pending = (Map<String, ?>) field(owner, "pending").get(owner);
                final var waiting = pending.get(create.get("id"));
                // Advance this real request's deadline, not wall-clock time; no test-only production API.
                field(waiting, "deadline").setLong(waiting, System.nanoTime() - 1);
                final var monitor =
                        (ScheduledFuture<?>) field(waiting, "monitor").get(waiting);
                final var check = waiting.getClass().getDeclaredMethod("check");
                check.setAccessible(true);
                check.invoke(waiting);
                final var result = terminal.get(5, TimeUnit.SECONDS);
                assertFalse(result.success());
                assertTrue(result.output().contains("timed out"));
                assertThrows(CancellationException.class, () -> monitor.get(5, TimeUnit.SECONDS));
                assertTrue(
                        pending.containsKey(create.get("id")), "late-create cleanup is bounded but remains available");
                f.reply(create, Map.of("terminalId", "late-terminal"));
                final var kill = f.method("terminal/kill");
                final var release = f.method("terminal/release");
                assertEquals("late-terminal", object(kill.get("params")).get("terminalId"));
                assertEquals(id, object(release.get("params")).get("sessionId"));
                f.reply(kill, Map.of());
                f.reply(release, Map.of());
                turn.done.complete(true);
                f.response("turn");
                f.send("close", "session/close", Map.of("sessionId", id));
                f.response("close");
                assertFalse(pending.containsKey(create.get("id")));
            }
        }

        @Test
        void commandsUseActualOutcomesAndTextResetNeverDuplicatesRetriedText(@TempDir final Path root)
                throws Exception {
            try (final var f =
                    new Fixture(configuration, root, Map.of("session", Map.of("notices", Map.of())), commandOptions)) {
                final var id = f.create();
                f.prompt("help", id, "/help");
                assertNull(f.response("help").get("error"));
                assertTrue(
                        f.seen.stream().anyMatch(line -> f.mapper.toString(line).contains("ACP commands")));
                assertTrue(f.turns.isEmpty());
                f.prompt("unknown", id, "/not-a-command");
                assertEquals(-32603, code(f.response("unknown")));
                assertTrue(f.turns.isEmpty());
                f.prompt("mode", id, "/thinking hide");
                f.response("mode");
                assertFalse(f.shells.active(id).session().showThinking());
                f.prompt("new", id, "/new");
                f.response("new");
                assertEquals(id, f.shells.active(id).session().sessionId());
                f.prompt("turn", id, "go");
                final var turn = f.turns.take();
                final var frontend = turn.request.env().frontend();
                frontend.onEvent(new AgentEvent.TextDelta("discarded retry", id, root));
                frontend.onEvent(new AgentEvent.TextReset(id, null, null, root));
                frontend.onEvent(new AgentEvent.TextDelta("final text", id, root));
                frontend.notice(AgentEvent.Notice.Level.ERROR, "ordinary error notice, not turn failure");
                frontend.onEvent(new AgentEvent.TurnDone("final text", id, root));
                turn.done.complete(true);
                assertEquals(
                        Map.of("stopReason", "end_turn"), f.response("turn").get("result"));
                final var wire =
                        f.seen.stream().map(value -> f.mapper.toString(value)).reduce("", String::concat);
                assertFalse(wire.contains("discarded retry"));
                assertEquals(1, FINAL_TEXT.split(wire, -1).length - 1);
                f.prompt("quit", id, "/quit");
                assertEquals(
                        Map.of("stopReason", "end_turn"), f.response("quit").get("result"));
                f.send("close", "session/close", Map.of("sessionId", id));
                f.response("close");
                assertNotNull(f.create(), "quit closes a session, not the connection");
            }
        }

        @Test
        void mixedCommandResourcesAreRejectedAndCancelledPickersCannotSwitchProvider(@TempDir final Path root)
                throws Exception {
            try (final var f =
                    new Fixture(configuration, root, Map.of("elicitation", Map.of("form", Map.of())), commandOptions)) {
                final var id = f.create();
                f.send(
                        "mixed",
                        "session/prompt",
                        Map.of(
                                "sessionId",
                                id,
                                "prompt",
                                List.of(
                                        Map.of("type", "text", "text", "/provider default"),
                                        Map.of(
                                                "type",
                                                "resource_link",
                                                "uri",
                                                "file:///untrusted",
                                                "description",
                                                " injected"))));
                assertEquals(-32602, code(f.response("mixed")));
                assertEquals("a", f.runtimes.get(id).chatSettings().providerId());
                f.prompt("picker", id, "/provider");
                final var pick = f.method("elicitation/create");
                f.send(null, "session/cancel", Map.of("sessionId", id));
                assertEquals(
                        Map.of("stopReason", "cancelled"), f.response("picker").get("result"));
                f.reply(pick, Map.of("action", "accept", "content", Map.of("answer", "default")));
                f.prompt("help", id, "/help");
                assertNull(f.response("help").get("error"));
                assertEquals("a", f.runtimes.get(id).chatSettings().providerId());
                f.prompt("switch", id, "/provider default");
                assertNull(f.response("switch").get("error"));
                assertEquals("default", f.runtimes.get(id).chatSettings().providerId());
                assertTrue(
                        f.seen.stream().anyMatch(line -> f.mapper.toString(line).contains("Provider: default")));
                assertFalse(
                        f.seen.stream().anyMatch(line -> f.mapper.toString(line).contains("Authorize Codex")),
                        "ordinary provider selection must not request authentication");
                f.shells.active(id).session().activeAgent("selected-agent");
                f.prompt("turn", id, "go");
                final var turn = f.turns.take();
                assertEquals("selected-agent", turn.request.agentName());
                turn.done.complete(true);
                assertNull(f.response("turn").get("error"));
                assertTrue(f.turns.isEmpty(), "slash commands never reach ordinary turns");
            }
        }

        @Test
        void sideAnswerIsAwaitedAndCancellationReachesItsRealProbe(@TempDir final Path root) throws Exception {
            try (final var f = new Fixture(configuration, root, Map.of(), commandOptions)) {
                final var id = f.create();
                f.prompt("btw", id, "/btw actual question");
                final var first = f.sideQuestions.poll(5, TimeUnit.SECONDS);
                assertNotNull(first);
                assertEquals("actual question", first.question());
                assertFalse(f.shells.active(id).session().busy(), "command admission is not a model turn");
                f.send("barrier", "initialize", Map.of("protocolVersion", 1));
                f.response("barrier");
                assertFalse(f.seen.stream().anyMatch(line -> "btw".equals(line.get("id"))));
                first.done().complete(new BtwAnswerer.BtwAnswer("real side answer", false));
                assertNull(f.response("btw").get("error"));
                assertTrue(
                        f.seen.stream().anyMatch(line -> f.mapper.toString(line).contains("real side answer")));
                f.prompt("cancelled-btw", id, "/btw cancelled question");
                final var second = f.sideQuestions.poll(5, TimeUnit.SECONDS);
                assertNotNull(second);
                f.send(null, "session/cancel", Map.of("sessionId", id));
                f.send("cancel-barrier", "initialize", Map.of("protocolVersion", 1));
                f.response("cancel-barrier");
                assertTrue(second.cancelled().getAsBoolean());
                assertFalse(
                        second.done().isDone(),
                        "the underlying operation is drained, not detached by cancelling a wrapper");
                second.done().complete(new BtwAnswerer.BtwAnswer("must not leak late output", false));
                assertEquals(
                        Map.of("stopReason", "cancelled"),
                        f.response("cancelled-btw").get("result"));
                final var frontend = f.shells.active(id).frontend();
                f.send("close", "session/close", Map.of("sessionId", id));
                f.response("close");
                frontend.status("must not leak disposed output");
                f.send("list", "session/list", Map.of());
                f.response("list");
                assertFalse(
                        f.seen.stream().anyMatch(line -> f.mapper.toString(line).contains("must not leak")));
                assertTrue(f.turns.isEmpty());
            }
        }
    }
}
