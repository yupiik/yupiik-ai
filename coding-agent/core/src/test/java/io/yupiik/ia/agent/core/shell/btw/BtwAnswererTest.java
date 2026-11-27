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
package io.yupiik.ia.agent.core.shell.btw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.configuration.BtwConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.SlashCommandConfiguration;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.ToolUsePreEvent;
import io.yupiik.ia.agent.core.events.hooks.EventContextBuilder;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.InBandToolCallExtractor;
import io.yupiik.ia.agent.core.llm.MarkupToolCallScanner;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ProviderConcurrencyGates;
import io.yupiik.ia.agent.core.llm.ToolCallArgumentsRepairer;
import io.yupiik.ia.agent.core.llm.ToolCallPairingRepair;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.session.TestSessionStores;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.tools.builtin.AskUserTool;
import io.yupiik.ia.agent.core.tools.builtin.BashTool;
import io.yupiik.ia.agent.core.tools.builtin.ReadFileTool;
import io.yupiik.ia.agent.core.tools.builtin.SkillTool;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.spi.ConcurrencySlotTimeoutException;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class BtwAnswererTest {
    // The module binds the ephemeral port before Fusion caches the root; setup takes ownership of the server.
    private static final Map<Path, HttpServer> SERVERS = new ConcurrentHashMap<>();
    private static final Pattern TOOL_NAME = Pattern.compile("\"name\":\"([a-z_]+)\"");
    private static final Pattern ROLE_FIELD = Pattern.compile("\"role\"");

    private Path storageHome;
    private CodingAgentConfiguration baseline;

    /** A realistic DSML tool-call block as emitted by DeepSeek V4. */
    private static final String DSML_BLOCK = "<\uFF5CDSML\uFF5Ctool_calls>\n"
            + "<\uFF5CDSML\uFF5Cinvoke name=read>\n"
            + "<\uFF5CDSML\uFF5Cparameter name=path"
            + " string=true>src/App.java</\uFF5CDSML\uFF5Cparameter>\n"
            + "</\uFF5CDSML\uFF5Cinvoke>\n"
            + "</\uFF5CDSML\uFF5Ctool_calls>";

    private static final Emitter NOOP = new Emitter() {
        @Override
        public <T> void emit(final T event) {
            // no-op
        }
    };

    private HttpServer server;
    private ExecutorService serverExecutor;
    private ExecutorService btwExecutor;
    private InMemorySessionStore sessionStore;
    private UsageTracker usageTracker;
    private BtwAnswerer answerer;
    private ToolRegistry toolRegistry;
    private JsonMapper mapper;
    private final List<ChatClient> clients = new ArrayList<>();
    private final List<ExtendedHttpClient> httpClients = new ArrayList<>();
    private final List<BtwAnswerer> answerers = new ArrayList<>();
    private final List<ProviderConcurrencyGates> gateRegistries = new ArrayList<>();
    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) throws IOException {
        this.mapper = mapper;
        storageHome = Path.of(configuration.resolveStorageHome());
        server = SERVERS.remove(storageHome);
        server.createContext("/v1/chat/completions", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            respond(exchange, 200, response(body, bodies.size()), "application/json");
        });
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.start();
        baseline = configuration;
        sessionStore = TestSessionStores.ephemeral();
        usageTracker = new UsageTracker(null, null, null, Clock.systemUTC());
        toolRegistry = registry();
        answerer = answerer(baseline);
    }

    @AfterEach
    void stop() {
        answerers.forEach(BtwAnswerer::release);
        clients.forEach(ChatClient::close);
        gateRegistries.forEach(ProviderConcurrencyGates::close);
        httpClients.forEach(ExtendedHttpClient::close);
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        if (btwExecutor != null) {
            btwExecutor.shutdownNow();
        }
    }

    private BtwAnswerer answerer(final CodingAgentConfiguration configuration) {
        return answerer(configuration, NOOP, false);
    }

    private BtwAnswerer answerer(
            final CodingAgentConfiguration configuration, final Emitter emitter, final boolean hookContext) {
        final var providerManager = new ProviderManager(configuration, mapper);
        final var httpClient =
                new HttpClientProducer().httpClient(configuration, new NativeProviderConfiguration(null, null));
        httpClients.add(httpClient);
        final var gates = new ProviderConcurrencyGates(configuration);
        gateRegistries.add(gates);
        final var client = new ChatClient(
                configuration,
                mapper,
                providerManager,
                Clock.systemUTC(),
                httpClient,
                new ToolCallArgumentsRepairer(mapper),
                gates);
        clients.add(client);
        if (btwExecutor == null) {
            btwExecutor = Executors.newVirtualThreadPerTaskExecutor();
        }
        final var created = new BtwAnswerer(
                client,
                sessionStore,
                configuration,
                btwExecutor,
                new InBandToolCallExtractor(new MarkupToolCallScanner()),
                toolRegistry,
                new ToolExecutionService(configuration, NOOP, List.of()),
                mapper,
                providerManager,
                Clock.systemUTC(),
                emitter,
                hookContext ? new EventContextBuilder() : null,
                new ToolCallPairingRepair(sessionStore, Clock.systemUTC()),
                httpClient,
                gates);
        answerers.add(created);
        return created;
    }

    /**
     * The /btw tool set: a fake read-only file reader, a fake writer (never allowed in /btw),
     * and the real bash tool whose plan-mode rules gate which commands may actually run.
     */
    private ToolRegistry registry() {
        final var tools = List.of(
                readFileTool(), writeFileTool(), new BashTool(NOOP, new ProcessService()), new AskUserTool(mapper));
        return new ToolRegistry(null) {
            @Override
            public List<EmbeddedTool> tools() {
                return tools;
            }
        };
    }

    private static EmbeddedTool readFileTool() {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return "read";
            }

            @Override
            public String description(final Path workdir) {
                return "read a file";
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
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                return ToolResult.ok("file content: " + args.get("path"));
            }
        };
    }

    private static EmbeddedTool writeFileTool() {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return "write_file";
            }

            @Override
            public String description(final Path workdir) {
                return "write a file";
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.EDIT;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                return ToolResult.error("must never run: write_file is not a /btw tool");
            }
        };
    }

    /**
     * What the stub returns for a given request, driven by magic words in the question. The
     * round-trip scenarios return a tool call for the first request and the final answer for
     * the following ones, mirroring a real model answering with tools.
     */
    private String response(final String body, final int request) {
        if (body.contains("SESSION ROOT")) {
            if (body.contains("\"role\":\"tool\"")) {
                return textResponse("root done");
            }
            final var read = toolResponse("read", "{\"path\":\"root.txt\"}");
            final var bash =
                    toolResponse("bash", "{\"command\":\"cat root.txt\"}").replace("call_1", "call_2");
            final var skill = toolResponse("skill", "{\"name\":\"shared\"}").replace("call_1", "call_3");
            // One model round asks all three tools, exercising the same frozen request root.
            final var start = "\"tool_calls\":[";
            final var end = "]},\"finish_reason\"";
            final var calls = List.of(read, bash, skill).stream()
                    .map(value -> value.substring(value.indexOf(start) + start.length(), value.indexOf(end)))
                    .toList();
            return read.substring(0, read.indexOf(start) + start.length())
                    + String.join(",", calls)
                    + read.substring(read.indexOf(end));
        }
        if (body.contains("ASK FEEDBACK")) {
            return request == 1
                    ? toolResponse(
                            "ask_user",
                            "{\"questions\":[{\"id\":\"q\",\"prompt\":\"Which?\",\"options\":[\"one\",\"two\"],\"multiple\":false}]}")
                    : textResponse("feedback done");
        }
        if (body.contains("NATIVE READ")) {
            return request == 1
                    ? toolResponse("read", "{\"path\":\"src/App.java\"}")
                    : textResponse("read round-trip done");
        }
        if (body.contains("BASH CAT")) {
            return request == 1
                    ? toolResponse("bash", "{\"command\":\"cat " + catPath() + "\"}")
                    : textResponse("cat done");
        }
        if (body.contains("BASH RM")) {
            return request == 1
                    ? toolResponse("bash", "{\"command\":\"rm -rf " + catPath() + "\"}")
                    : textResponse("rm done");
        }
        if (body.contains("WRITE TOOL")) {
            return request == 1
                    ? toolResponse("write_file", "{\"path\":\"" + catPath() + "\",\"content\":\"x\"}")
                    : textResponse("write done");
        }
        if (body.contains("TOOLCALL MARKUP")) {
            // first request carries the markup the parser must turn into a real read call
            return request == 1
                    ? textResponse("the parsed answer\n\n" + DSML_BLOCK)
                    : textResponse("the parsed answer");
        }
        if (body.contains("TOOLCALL ONLY")) {
            // the model insists on tools: the loop must give up with a note, never an exception
            return textResponse(DSML_BLOCK);
        }
        if (body.contains("TRUNCATED FIRST")) {
            // blank first answer that spent tokens (thinking eaten the budget): retry, then succeed
            return request == 1 ? textResponse("") : textResponse("hello there");
        }
        if (body.contains("EMPTY PLEASE")) {
            // always blank: retry once, then fall back to a note
            return textResponse("");
        }
        return textResponse("hello there");
    }

    @Nested
    @FusionSupport(modules = ToolsEnabledFeedbackUsesOwningServiceScenarioModule.class)
    class ToolsEnabledFeedbackUsesOwningServiceScenario {
        @TempDir
        static Path directory;

        @Test
        void toolsEnabledFeedbackUsesOwningService() throws Exception {
            final var feedback = new UserFeedbackService();
            final var asked = new AtomicInteger();
            feedback.setGate(questions -> {
                asked.incrementAndGet();
                assertEquals("q", questions.getFirst().id());
                return Map.of("q", "two");
            });
            final var tooled = answerer(withBtw(
                    baseline,
                    new BtwConfiguration(
                            baseline.slashcommands().btw().model(),
                            baseline.slashcommands().btw().maxTokens(),
                            baseline.slashcommands().btw().maxHistoryMessages(),
                            baseline.slashcommands().btw().recall(),
                            true)));
            final var answer = tooled.ask(
                            "ASK FEEDBACK",
                            "s",
                            usageTracker,
                            () -> false,
                            null,
                            null,
                            null,
                            feedback,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("feedback done", answer.text());
            assertEquals(1, asked.get());
            assertTrue(bodies.getLast().contains("two"), bodies.getLast());
            assertTrue(bodies.getFirst().contains("ask_user"));
            assertFalse(bodies.getFirst().contains("\"name\":\"write_file\""));
        }
    }

    @Nested
    @FusionSupport(modules = ToolLessFeedbackAttemptNeverUsesGateScenarioModule.class)
    class ToolLessFeedbackAttemptNeverUsesGateScenario {
        @TempDir
        static Path directory;

        @Test
        void toolLessFeedbackAttemptNeverUsesGate() throws Exception {
            final var feedback = new UserFeedbackService();
            feedback.setGate(questions -> {
                throw new AssertionError("tool-less side question must never ask");
            });
            final var answer = answerer.ask(
                            "ASK FEEDBACK",
                            "s",
                            usageTracker,
                            () -> false,
                            null,
                            null,
                            null,
                            feedback,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertFalse(answer.text().isBlank());
            assertTrue(bodies.stream().noneMatch(body -> body.contains("\"role\":\"tool\"")));
        }
    }

    private String catPath() {
        // a real temp file so the plan-mode `cat` rule actually reads something
        try {
            final var file = storageHome.resolve("cat-target.txt");
            Files.writeString(file, "cat content");
            return file.toString();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String textResponse(final String content) {
        return "{\"id\":\"c1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + content.replace("\n", "\\n").replace("\"", "\\\"")
                + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
    }

    private static String toolResponse(final String name, final String arguments) {
        return "{\"id\":\"c1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\""
                + name + "\",\"arguments\":\"" + arguments.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"}}]},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
    }

    private static Map<String, String> configurationValues(final Path directory, final Map<String, String> extra) {
        final HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        SERVERS.put(directory, server);
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", directory.toString());
        entries.put("yca.home", directory.resolve("home").toString());
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put(
                "yca.provider.providers.0.baseUrl",
                "http://localhost:" + server.getAddress().getPort() + "/v1");
        entries.put("yca.provider.providers.0.apiKey", "test-key");
        entries.put("yca.provider.providers.0.model", "test-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", "128000");
        entries.put("yca.provider.providers.0.timeoutSeconds", "5");
        entries.put("yca.llm.retriesMaxAttempts", "1");
        entries.put("yca.llm.retriesBaseDelayMs", "1");
        entries.putAll(extra);
        return entries;
    }

    private static void respond(
            final HttpExchange exchange, final int status, final String body, final String contentType) {
        try {
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nested
    @FusionSupport(modules = MainAndDedicatedBtwClientUseTheSameBudgetScenarioModule.class)
    class MainAndDedicatedBtwClientUseTheSameBudgetScenario {
        @TempDir
        static Path directory;

        @Test
        void mainAndDedicatedBtwClientUseTheSameBudget() throws Exception {
            final var bounded = answerer(baseline);
            sessionStore.replace("s1", List.of(Message.user("hi")));
            final var main = clients.getLast();
            final var registry = gateRegistries.getLast();
            final var gate = registry.gate(null, 1);
            final var held = gate.tryAcquire();
            assertNotNull(held);
            try {
                assertThrows(
                        Exception.class,
                        () -> main.chat(new ChatCompletionRequest(
                                "test-model",
                                List.of(Message.user("hi")),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)));
                final var failure = assertThrows(
                        ExecutionException.class,
                        () -> bounded.ask(
                                        "what did I say?",
                                        "s1",
                                        usageTracker,
                                        () -> false,
                                        null,
                                        storageHome,
                                        new DisabledSandbox(storageHome))
                                .toCompletableFuture()
                                .get(5, TimeUnit.SECONDS));
                assertInstanceOf(ConcurrencySlotTimeoutException.class, failure.getCause());
                assertTrue(bodies.isEmpty(), "neither client may reach the provider without a slot");
            } finally {
                held.run();
            }
            assertEquals(
                    "hello there",
                    bounded.ask(
                                    "what did I say?",
                                    "s1",
                                    usageTracker,
                                    () -> false,
                                    null,
                                    storageHome,
                                    new DisabledSandbox(storageHome))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS)
                            .text());
            main.close();
            final var next = gate.tryAcquire();
            assertNotNull(next, "closing a client must not close its injected registry");
            next.run();
        }
    }

    @Nested
    @FusionSupport(modules = AnswersOutOfBandWithoutPersistingScenarioModule.class)
    class AnswersOutOfBandWithoutPersistingScenario {
        @TempDir
        static Path directory;

        @Test
        void answersOutOfBandWithoutPersisting() throws Exception {
            sessionStore.replace("s1", List.of(Message.user("hi"), Message.assistant("hello", List.of())));
            final var answer = answerer.ask(
                            "what did I say?",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("hello there", answer.text());
            assertFalse(answer.retryable(), "a successful tool-less (default) answer is not retryable");
            final var body = bodies.getLast();
            assertTrue(body.contains("\"model\":\"test-model\""), body);
            // the tiny default budget would starve reasoning: when not configured, it follows the provider
            assertFalse(body.contains("\"max_tokens\""), body);
            // /btw is tool-less in behavior but keeps the session's tool schema in the request (never
            // executed)
            // so the provider prompt-cache prefix stays byte-identical with the main agent loop; the
            // anti-reflex
            // "answer directly, never act" direction rides in the system prompt on this fallback path.
            assertFalse(body.contains("\"tools\":[]"), body);
            assertTrue(body.contains("\"read\""), body);
            assertTrue(body.contains("Never write 'Let me check...'"), body);
            assertTrue(body.contains("Side question: what did I say?"), body);
            assertTrue(body.contains("hello"), body);
            // a tool-less /btw is a single one-shot generation
            assertEquals(1, bodies.size(), bodies.toString());
            assertEquals(2, sessionStore.size("s1"));
            assertEquals(1L, usageTracker.auxiliaryRequests());
            assertEquals(3L, usageTracker.auxiliaryPromptTokens());
        }
    }

    @Nested
    @FusionSupport(modules = AnswersWhenHistoryStartsWithOrphanToolResultScenarioModule.class)
    class AnswersWhenHistoryStartsWithOrphanToolResultScenario {
        @TempDir
        static Path directory;

        @Test
        void answersWhenHistoryStartsWithOrphanToolResult() throws Exception {
            // the persisted session's tail can start mid-tool-round (a cancelled turn before /btw ran):
            // an orphan tool result at the head of the slice would reach Azure/litellm as a hard 400
            // ("messages with role 'tool' must be a response to a preceding message with 'tool_calls'").
            // The repair must drop the orphan in memory (never persist - /btw is a read-only side channel).
            sessionStore.replace(
                    "s1",
                    List.of(
                            Message.user("hi"),
                            Message.assistant("hello", List.of()),
                            Message.tool("c1", "orphan result")));
            final var answer = answerer.ask(
                            "what did I say?",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("hello there", answer.text());
            // the orphan is gone from the wire, and the session store was NOT written by the repair
            final var body = bodies.getLast();
            assertFalse(body.contains("orphan result"), body);
            assertEquals(3, sessionStore.size("s1"));
            assertEquals(1L, usageTracker.auxiliaryRequests());
        }
    }

    @SuppressWarnings("unchecked")
    @Nested
    @FusionSupport(modules = KeepsSystemPromptAndToolSchemaByteIdenticalToMainLoopForCacheScenarioModule.class)
    class KeepsSystemPromptAndToolSchemaByteIdenticalToMainLoopForCacheScenario {
        @TempDir
        static Path directory;

        @Test
        void keepsSystemPromptAndToolSchemaByteIdenticalToMainLoopForCache() throws Exception {
            // the /btw request must reuse the session's system prompt and the full wire tool schema (never
            // executed) so the provider prompt-cache prefix stays byte-identical with the main agent loop.
            final var configuration = baseline;
            final var builder = new SystemPromptBuilder(
                    configuration, null, null, null, null, null, null, null, null, (configuration).resolveWorkingDir());
            final var expectedSystem = builder.build(null, null);
            final var expectedNames = toolRegistry.toWireTools(configuration.resolveWorkingDir()).stream()
                    .map(t -> t.function().name())
                    .sorted()
                    .toList();
            sessionStore.replace("s1", List.of(Message.user("hi")));
            answerer.ask(
                            "what did I say?",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            null,
                            builder,
                            configuration.resolveWorkingDir(),
                            new DisabledSandbox(configuration.resolveWorkingDir()))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            final var body = bodies.getLast();
            // parse the request back and assert the system message is byte-identical to the main loop's
            final var request = (Map<String, Object>) mapper.fromString(Object.class, body);
            final var msgs = (List<Map<String, Object>>) request.get("messages");
            assertEquals(3, msgs.size(), msgs.toString());
            assertEquals("system", msgs.get(0).get("role"));
            assertEquals(expectedSystem, msgs.get(0).get("content"));
            // tools are declared (cache-consistent), never empty
            assertFalse(body.contains("\"tools\":[]"), body);
            final var actualNames = new ArrayList<String>();
            final var m = TOOL_NAME.matcher(body);
            while (m.find()) {
                actualNames.add(m.group(1));
            }
            assertTrue(actualNames.containsAll(expectedNames), actualNames + " vs " + expectedNames);
        }
    }

    @Nested
    @FusionSupport(modules = BoundsInheritedHistoryScenarioModule.class)
    class BoundsInheritedHistoryScenario {
        @TempDir
        static Path directory;

        @Test
        void boundsInheritedHistory() throws Exception {
            final var messages = new ArrayList<Message>();
            for (var i = 0; i < 250; i++) {
                messages.add(Message.user("m" + i));
            }
            sessionStore.replace("s1", messages);
            answerer(baseline)
                    .ask("q", "s1", usageTracker, () -> false, null, storageHome, new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            final var body = bodies.getLast();
            assertTrue(body.contains("\"m50\""), body);
            assertFalse(body.contains("\"m49\""), body);
            assertEquals(202, ROLE_FIELD.split(body, -1).length - 1, body);
        }
    }

    @Nested
    @FusionSupport(modules = ReplaysPriorExchangesAndHonorsRecallZeroScenarioModule.class)
    class ReplaysPriorExchangesAndHonorsRecallZeroScenario {
        @TempDir
        static Path directory;

        @Test
        void replaysPriorExchangesAndHonorsRecallZero() throws Exception {
            sessionStore.replace("s1", List.of());
            final var replaying = answerer(baseline);
            replaying
                    .ask(
                            "first question",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            replaying
                    .ask(
                            "second question",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            final var body = bodies.getLast();
            assertTrue(body.contains("Q: first question"), body);
            assertTrue(body.contains("A: hello there"), body);
            assertTrue(body.contains("Side question: second question"), body);

            bodies.clear();
            final var noRecall = answerer(withBtw(
                    baseline,
                    new BtwConfiguration(
                            baseline.slashcommands().btw().model(),
                            baseline.slashcommands().btw().maxTokens(),
                            baseline.slashcommands().btw().maxHistoryMessages(),
                            0,
                            baseline.slashcommands().btw().tools())));
            noRecall.ask(
                            "first question",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            noRecall.ask(
                            "second question",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertFalse(bodies.getLast().contains("Earlier side questions"), bodies.getLast());
        }
    }

    @Nested
    @FusionSupport(modules = ModelOverrideAppliesScenarioModule.class)
    class ModelOverrideAppliesScenario {
        @TempDir
        static Path directory;

        @Test
        void modelOverrideApplies() throws Exception {
            answerer(baseline)
                    .ask("q", "s1", usageTracker, () -> false, null, storageHome, new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertTrue(bodies.getLast().contains("\"model\":\"btw-model\""), bodies.getLast());
        }
    }

    @Nested
    @FusionSupport(modules = NativeToolCallsRoundTripScenarioModule.class)
    class NativeToolCallsRoundTripScenario {
        @TempDir
        static Path directory;

        @Test
        void nativeToolCallsRoundTrip() throws Exception {
            sessionStore.replace("s1", List.of(Message.user("hi")));
            final var answer = answerer(withBtw(
                            baseline,
                            new BtwConfiguration(
                                    baseline.slashcommands().btw().model(),
                                    baseline.slashcommands().btw().maxTokens(),
                                    baseline.slashcommands().btw().maxHistoryMessages(),
                                    baseline.slashcommands().btw().recall(),
                                    true)))
                    .ask(
                            "NATIVE READ",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("read round-trip done", answer.text());
            assertFalse(answer.retryable());
            assertEquals(2, bodies.size(), bodies.toString());
            // the first request declared the tools, the second carried the tool result back to the model
            assertTrue(bodies.get(0).contains("\"tools\""), bodies.get(0));
            assertTrue(bodies.get(1).contains("file content: src/App.java"), bodies.get(1));
            // a round-trip still does not touch the session store
            assertEquals(1, sessionStore.size("s1"));
            assertEquals(2L, usageTracker.auxiliaryRequests());
        }
    }

    @Nested
    @FusionSupport(modules = SharedAnswererUsesBuilderAndPersistedRootsForReadBashAndSkillScenarioModule.class)
    class SharedAnswererUsesBuilderAndPersistedRootsForReadBashAndSkillScenario {
        @TempDir
        static Path directory;

        @Test
        void sharedAnswererUsesBuilderAndPersistedRootsForReadBashAndSkill() throws Exception {
            final var first = rootFixture("first");
            final var second = rootFixture("second");
            rootRegistry(first);
            final var events = new CopyOnWriteArrayList<ToolUsePreEvent>();
            final var shared = answerer(rootConfig(first), collecting(events), true);
            final var builder = rootBuilder(second);
            // The live session builder wins over a valid but different persisted marker.
            sessionStore.replace("builder", List.of(Message.projectDir(first.toString())));
            sessionStore.replace("marker", List.of(Message.projectDir(first.toString())));
            assertRootRound(shared, "builder", builder, second, "second", "first", events);
            assertRootRound(shared, "marker", null, first, "first", "second", events);
            assertRootRound(shared, "builder", builder, second, "second", "first", events);
            assertEquals(first.toString(), sessionStore.projectDirOf("builder"));
            assertEquals(first.toString(), sessionStore.projectDirOf("marker"));
        }
    }

    @Nested
    @FusionSupport(modules = MissingStaleAndInvalidMarkersFallBackToConfiguredWorkingDirectoryScenarioModule.class)
    class MissingStaleAndInvalidMarkersFallBackToConfiguredWorkingDirectoryScenario {
        @TempDir
        static Path directory;

        @Test
        void missingStaleAndInvalidMarkersFallBackToConfiguredWorkingDirectory() throws Exception {
            final var fallback = rootFixture("fallback");
            rootRegistry(fallback);
            final var events = new CopyOnWriteArrayList<ToolUsePreEvent>();
            // Also exercise direct construction without an EventContextBuilder.
            final var shared = answerer(rootConfig(fallback), collecting(events), false);
            sessionStore.replace(
                    "stale",
                    List.of(Message.projectDir(storageHome.resolve("gone").toString())));
            sessionStore.replace("invalid", List.of(Message.projectDir("invalid\u0000path")));
            sessionStore.replace(
                    "file",
                    List.of(Message.projectDir(fallback.resolve("root.txt").toString())));
            sessionStore.replace("relative", List.of(Message.projectDir("relative")));
            for (final var session : List.of("missing", "stale", "invalid", "file", "relative")) {
                assertRootRound(shared, session, null, fallback, "fallback", "not-this-root", events);
            }
        }
    }

    @Nested
    @FusionSupport(modules = ToolLessRequestProjectsSessionRootWithoutExecutingToolsScenarioModule.class)
    class ToolLessRequestProjectsSessionRootWithoutExecutingToolsScenario {
        @TempDir
        static Path directory;

        @Test
        void toolLessRequestProjectsSessionRootWithoutExecutingTools() throws Exception {
            final var first = rootFixture("first");
            final var second = rootFixture("second");
            rootRegistry(first);
            final var rootConfiguration = rootConfig(first);
            final var settings = withBtw(
                    rootConfiguration,
                    new BtwConfiguration(
                            rootConfiguration.slashcommands().btw().model(),
                            rootConfiguration.slashcommands().btw().maxTokens(),
                            rootConfiguration.slashcommands().btw().maxHistoryMessages(),
                            0,
                            false));
            final var events = new CopyOnWriteArrayList<ToolUsePreEvent>();
            final var shared = answerer(settings, collecting(events), true);
            sessionStore.replace("s", List.of(Message.projectDir(second.toString())));
            final var answer = shared.ask(
                            "what did I say?",
                            "s",
                            usageTracker,
                            () -> false,
                            null,
                            second,
                            new DisabledSandbox(second))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("hello there", answer.text());
            assertEquals(1, bodies.size());
            assertTrue(bodies.getFirst().contains("Read for session root " + second), bodies.getFirst());
            assertTrue(events.isEmpty());
            assertFalse(bodies.getFirst().contains("\"role\":\"tool\""));
            assertEquals(1, sessionStore.size("s"));
        }
    }

    @Nested
    @FusionSupport(modules = SideRequestFreezesRootWhenBuilderAndMarkerChangeDuringToolsScenarioModule.class)
    class SideRequestFreezesRootWhenBuilderAndMarkerChangeDuringToolsScenario {
        @TempDir
        static Path directory;

        @Test
        void sideRequestFreezesRootWhenBuilderAndMarkerChangeDuringTools() throws Exception {
            final var first = rootFixture("frozen");
            final var second = rootFixture("changed");
            rootRegistry(first);
            final var builder = rootBuilder(first);
            final var events = new CopyOnWriteArrayList<ToolUsePreEvent>();
            final var emitter = new Emitter() {
                @Override
                public <T> void emit(final T event) {
                    if (event instanceof final ToolUsePreEvent pre) {
                        events.add(pre);

                        sessionStore.replace("freeze", List.of(Message.projectDir(second.toString())));
                    }
                }
            };
            final var shared = answerer(rootConfig(first), emitter, true);
            assertRootRound(shared, "freeze", builder, first, "frozen", "changed", events);
            assertRootRound(shared, "freeze", builder, second, "changed", "frozen", events);
        }
    }

    private Path rootFixture(final String name) throws IOException {
        final var root = storageHome.resolve(name).toAbsolutePath().normalize();
        final var skill = root.resolve(".opencode/skills/shared/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(root.resolve("root.txt"), "file-for-" + name);
        Files.writeString(skill, "skill-for-" + name);
        return root;
    }

    private CodingAgentConfiguration rootConfig(final Path root) {
        final var configured = withBtw(
                baseline,
                new BtwConfiguration(
                        baseline.slashcommands().btw().model(),
                        baseline.slashcommands().btw().maxTokens(),
                        baseline.slashcommands().btw().maxHistoryMessages(),
                        0,
                        true));
        return new CodingAgentConfiguration(
                configured.storageHome(),
                configured.home(),
                root.toString(),
                configured.projectDir(),
                configured.provider(),
                configured.model(),
                configured.llm(),
                configured.compaction(),
                configured.pruning(),
                configured.compression(),
                configured.memory(),
                configured.todos(),
                configured.optimize(),
                configured.permissions(),
                configured.sandbox(),
                configured.mcp(),
                configured.mavenRepositoryKnowledge(),
                configured.structuralCodeGraph(),
                configured.sessions(),
                configured.skills(),
                configured.agents(),
                configured.mode(),
                configured.httpClient(),
                configured.tools(),
                configured.subagents(),
                configured.schedulerThreads(),
                configured.metrics(),
                configured.maxLineLength(),
                configured.resumeAliases(),
                configured.slashcommands(),
                configured.prompt(),
                configured.labels(),
                configured.logging(),
                configured.hooks(),
                configured.notifications(),
                configured.secrets(),
                null);
    }

    private SystemPromptBuilder rootBuilder(final Path root) {
        final var configuration = rootConfig(root);
        final var builder =
                new SystemPromptBuilder(configuration, null, null, null, null, null, null, null, null, root);

        return builder;
    }

    private void rootRegistry(final Path fallback) {
        final var configuration = rootConfig(fallback);
        final var tools = List.of(
                new ReadFileTool(configuration) {
                    @Override
                    public String description(final Path root) {
                        return "Read for session root " + root;
                    }
                },
                new BashTool(NOOP, new ProcessService()),
                new SkillTool(configuration));
        toolRegistry = new ToolRegistry(null) {
            @Override
            public List<EmbeddedTool> tools() {
                return tools;
            }
        };
    }

    private Emitter collecting(final List<ToolUsePreEvent> events) {
        return new Emitter() {
            @Override
            public <T> void emit(final T event) {
                if (event instanceof final ToolUsePreEvent pre) {
                    events.add(pre);
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    private void assertRootRound(
            final BtwAnswerer shared,
            final String session,
            final SystemPromptBuilder builder,
            final Path root,
            final String expected,
            final String unwanted,
            final List<ToolUsePreEvent> events)
            throws Exception {
        bodies.clear();
        events.clear();
        assertEquals(
                "root done",
                shared.ask(
                                "SESSION ROOT",
                                session,
                                usageTracker,
                                () -> false,
                                null,
                                null,
                                builder,
                                root,
                                new DisabledSandbox(root))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
                        .text());
        assertEquals(2, bodies.size(), bodies.toString());
        assertTrue(bodies.getFirst().contains("Read for session root " + root), bodies.getFirst());
        final var request = (Map<String, Object>) mapper.fromString(Object.class, bodies.getLast());
        final var messages = (List<Map<String, Object>>) request.get("messages");
        final var outputs = messages.stream()
                .filter(message -> "tool".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .toList();
        assertEquals(3, outputs.size(), outputs.toString());
        assertTrue(outputs.get(0).contains("file-for-" + expected), outputs.toString());
        assertTrue(outputs.get(1).contains("file-for-" + expected), outputs.toString());
        assertTrue(outputs.get(2).contains("skill-for-" + expected), outputs.toString());
        assertFalse(outputs.toString().contains("file-for-" + unwanted), outputs.toString());
        assertFalse(outputs.toString().contains("skill-for-" + unwanted), outputs.toString());
        assertEquals(3, events.size());
        for (final var event : events) {
            assertEquals(root.toString(), event.eventContext().cwd());
            // Public permission grants expose the root used to scope authorization to this request.
            event.permissions().addSessionGrantAnchor("cat");
            assertTrue(event.permissions().sessionGrants().contains(root + "|bash:cat"));
        }
    }

    @Nested
    @FusionSupport(modules = ToolCallMarkupIsParsedAndExecutedScenarioModule.class)
    class ToolCallMarkupIsParsedAndExecutedScenario {
        @TempDir
        static Path directory;

        @Test
        void toolCallMarkupIsParsedAndExecuted() throws Exception {
            sessionStore.replace("s1", List.of(Message.user("hi")));
            final var answer = answerer(withBtw(
                            baseline,
                            new BtwConfiguration(
                                    baseline.slashcommands().btw().model(),
                                    baseline.slashcommands().btw().maxTokens(),
                                    baseline.slashcommands().btw().maxHistoryMessages(),
                                    baseline.slashcommands().btw().recall(),
                                    true)))
                    .ask(
                            "TOOLCALL MARKUP",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("the parsed answer", answer.text());
            // the DSML block was turned into a real read execution, not just stripped
            assertEquals(2, bodies.size(), bodies.toString());
            assertTrue(bodies.get(1).contains("file content: src/App.java"), bodies.get(1));
            assertEquals(1, sessionStore.size("s1"));
        }
    }

    @Nested
    @FusionSupport(modules = BashReadCommandRunsButDestructiveIsRefusedScenarioModule.class)
    class BashReadCommandRunsButDestructiveIsRefusedScenario {
        @TempDir
        static Path directory;

        @Test
        void bashReadCommandRunsButDestructiveIsRefused() throws Exception {
            sessionStore.replace("s1", List.of(Message.user("hi")));
            final var answer = answerer(withBtw(
                            baseline,
                            new BtwConfiguration(
                                    baseline.slashcommands().btw().model(),
                                    baseline.slashcommands().btw().maxTokens(),
                                    baseline.slashcommands().btw().maxHistoryMessages(),
                                    baseline.slashcommands().btw().recall(),
                                    true)))
                    .ask(
                            "BASH CAT",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("cat done", answer.text());
            assertEquals(2, bodies.size(), bodies.toString());
            // the plan-mode allow-list ran `cat` and fed the output back
            assertTrue(bodies.get(1).contains("cat content"), bodies.get(1));
            bodies.clear();

            // a fresh answerer so the previous exchange is not replayed into this side question
            final var denied = answerer(withBtw(
                            baseline,
                            new BtwConfiguration(
                                    baseline.slashcommands().btw().model(),
                                    baseline.slashcommands().btw().maxTokens(),
                                    baseline.slashcommands().btw().maxHistoryMessages(),
                                    baseline.slashcommands().btw().recall(),
                                    true)))
                    .ask(
                            "BASH RM",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("rm done", denied.text());
            assertEquals(2, bodies.size(), bodies.toString());
            // `rm -rf` is not a plan-mode read-only command: it is refused, and the tool never ran
            assertTrue(bodies.get(1).contains("Permission denied by user"), bodies.get(1));
            assertFalse(bodies.get(1).contains("cat content"), bodies.get(1));
        }
    }

    @Nested
    @FusionSupport(modules = NonReadToolIsRefusedWithoutRunningScenarioModule.class)
    class NonReadToolIsRefusedWithoutRunningScenario {
        @TempDir
        static Path directory;

        @Test
        void nonReadToolIsRefusedWithoutRunning() throws Exception {
            final var answer = answerer(withBtw(
                            baseline,
                            new BtwConfiguration(
                                    baseline.slashcommands().btw().model(),
                                    baseline.slashcommands().btw().maxTokens(),
                                    baseline.slashcommands().btw().maxHistoryMessages(),
                                    baseline.slashcommands().btw().recall(),
                                    true)))
                    .ask(
                            "WRITE TOOL",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("write done", answer.text());
            assertEquals(2, bodies.size(), bodies.toString());
            // write_file is never declared (-tools- has no writer) and is refused by plan-mode rules
            assertFalse(bodies.get(0).contains("write_file"), bodies.get(0));
            assertTrue(bodies.get(1).contains("Permission denied by user"), bodies.get(1));
        }
    }

    @Nested
    @FusionSupport(modules = ToolCallOnlyAnswerFallsBackToRawNoteAndIsNotReplayedScenarioModule.class)
    class ToolCallOnlyAnswerFallsBackToRawNoteAndIsNotReplayedScenario {
        @TempDir
        static Path directory;

        @Test
        void toolCallOnlyAnswerFallsBackToRawNoteAndIsNotReplayed() throws Exception {
            sessionStore.replace("s1", List.of());
            final var answerer = answerer(withBtw(
                    baseline,
                    new BtwConfiguration(
                            baseline.slashcommands().btw().model(),
                            baseline.slashcommands().btw().maxTokens(),
                            baseline.slashcommands().btw().maxHistoryMessages(),
                            baseline.slashcommands().btw().recall(),
                            true)));
            final var answer = answerer.ask(
                            "TOOLCALL ONLY",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertTrue(answer.text().startsWith("> "), answer.text());
            assertTrue(answer.text().contains("Attempted tools: read"), answer.text());
            // a tooled-mode round-cap is surfaced as a retryable failure, never an exception
            assertTrue(answer.retryable(), "the round-cap glitch must be retryable");
            // the round cap was reached: six model round-trips, all tool-only
            assertEquals(6, bodies.size(), bodies.toString());
            // a broken exchange is never replayed into the next side question
            answerer.ask(
                            "TOOLCALL MARKUP",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            final var body = bodies.getLast();
            assertFalse(body.contains("Q: TOOLCALL ONLY"), body);
            assertTrue(body.contains("Side question: TOOLCALL MARKUP"), body);
        }
    }

    @Nested
    @FusionSupport(modules = BlankAnswerRetriesOnceThenReturnsRetryableNoteScenarioModule.class)
    class BlankAnswerRetriesOnceThenReturnsRetryableNoteScenario {
        @TempDir
        static Path directory;

        @Test
        void blankAnswerRetriesOnceThenReturnsRetryableNote() throws Exception {
            final var first = answerer(baseline)
                    .ask(
                            "TRUNCATED FIRST",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("hello there", first.text());
            assertFalse(first.retryable());
            // the blank first answer was retried, then succeeded
            assertEquals(2, bodies.size(), bodies.toString());

            // a fresh answerer so the previous exchange is not replayed into this side question
            bodies.clear();
            final var alwaysBlank = answerer(baseline)
                    .ask(
                            "EMPTY PLEASE",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            // a still-blank answer becomes a retryable note, never an exception
            assertTrue(alwaysBlank.text().startsWith("The model returned an empty answer"), alwaysBlank.text());
            assertTrue(alwaysBlank.text().contains("Check the output budget"), alwaysBlank.text());
            assertTrue(alwaysBlank.retryable(), "an empty answer must be retryable");
        }
    }

    @Nested
    @FusionSupport(modules = ToolAttemptMarkupIsNeverExecutedAndSurfacesAsRetryableScenarioModule.class)
    class ToolAttemptMarkupIsNeverExecutedAndSurfacesAsRetryableScenario {
        @TempDir
        static Path directory;

        @Test
        void toolAttemptMarkupIsNeverExecutedAndSurfacesAsRetryable() throws Exception {
            sessionStore.replace("s1", List.of(Message.user("hi")));
            final var answer = answerer(baseline)
                    .ask(
                            "TOOLCALL MARKUP",
                            "s1",
                            usageTracker,
                            () -> false,
                            null,
                            storageHome,
                            new DisabledSandbox(storageHome))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            // the default mode never executes the stray in-band call; a model that reached for a tool (DSML
            // markup in the text channel) must not look like a clean success - the leftover prose becomes a
            // retryable note so the web/TUI Retry affordance is armed.
            assertTrue(answer.text().contains("the parsed answer"), answer.text());
            assertTrue(answer.text().contains("tried to call a tool"), answer.text());
            assertTrue(answer.retryable(), "a tool-attempt (markup) answer must be retryable");
            // exactly one request (no tool feedback round) and no tool ever ran
            assertEquals(1, bodies.size(), bodies.toString());
            assertFalse(bodies.get(0).contains("file content: src/App.java"), bodies.get(0));
            assertEquals(1, sessionStore.size("s1"));
        }
    }

    private CodingAgentConfiguration withBtw(final CodingAgentConfiguration baseline, final BtwConfiguration btw) {
        return new CodingAgentConfiguration(
                baseline.storageHome(),
                baseline.home(),
                baseline.workingDir(),
                baseline.projectDir(),
                baseline.provider(),
                baseline.model(),
                baseline.llm(),
                baseline.compaction(),
                baseline.pruning(),
                baseline.compression(),
                baseline.memory(),
                baseline.todos(),
                baseline.optimize(),
                baseline.permissions(),
                baseline.sandbox(),
                baseline.mcp(),
                baseline.mavenRepositoryKnowledge(),
                baseline.structuralCodeGraph(),
                baseline.sessions(),
                baseline.skills(),
                baseline.agents(),
                baseline.mode(),
                baseline.httpClient(),
                baseline.tools(),
                baseline.subagents(),
                baseline.schedulerThreads(),
                baseline.metrics(),
                baseline.maxLineLength(),
                baseline.resumeAliases(),
                new SlashCommandConfiguration(btw),
                baseline.prompt(),
                baseline.labels(),
                baseline.logging(),
                baseline.hooks(),
                baseline.notifications(),
                baseline.secrets(),
                null);
    }

    public static class ToolsEnabledFeedbackUsesOwningServiceScenarioModule extends ConfigurationTestModule {
        public ToolsEnabledFeedbackUsesOwningServiceScenarioModule() {
            super(configurationValues(ToolsEnabledFeedbackUsesOwningServiceScenario.directory, Map.of()));
        }
    }

    public static class ToolLessFeedbackAttemptNeverUsesGateScenarioModule extends ConfigurationTestModule {
        public ToolLessFeedbackAttemptNeverUsesGateScenarioModule() {
            super(configurationValues(ToolLessFeedbackAttemptNeverUsesGateScenario.directory, Map.of()));
        }
    }

    public static class MainAndDedicatedBtwClientUseTheSameBudgetScenarioModule extends ConfigurationTestModule {
        public MainAndDedicatedBtwClientUseTheSameBudgetScenarioModule() {
            super(configurationValues(
                    MainAndDedicatedBtwClientUseTheSameBudgetScenario.directory,
                    Map.of("yca.llm.maxConcurrent", "1", "yca.llm.concurrencyAcquireTimeoutMs", "50")));
        }
    }

    public static class AnswersOutOfBandWithoutPersistingScenarioModule extends ConfigurationTestModule {
        public AnswersOutOfBandWithoutPersistingScenarioModule() {
            super(configurationValues(AnswersOutOfBandWithoutPersistingScenario.directory, Map.of()));
        }
    }

    public static class AnswersWhenHistoryStartsWithOrphanToolResultScenarioModule extends ConfigurationTestModule {
        public AnswersWhenHistoryStartsWithOrphanToolResultScenarioModule() {
            super(configurationValues(AnswersWhenHistoryStartsWithOrphanToolResultScenario.directory, Map.of()));
        }
    }

    public static class KeepsSystemPromptAndToolSchemaByteIdenticalToMainLoopForCacheScenarioModule
            extends ConfigurationTestModule {
        public KeepsSystemPromptAndToolSchemaByteIdenticalToMainLoopForCacheScenarioModule() {
            super(configurationValues(
                    KeepsSystemPromptAndToolSchemaByteIdenticalToMainLoopForCacheScenario.directory, Map.of()));
        }
    }

    public static class BoundsInheritedHistoryScenarioModule extends ConfigurationTestModule {
        public BoundsInheritedHistoryScenarioModule() {
            super(configurationValues(BoundsInheritedHistoryScenario.directory, Map.of()));
        }
    }

    public static class ReplaysPriorExchangesAndHonorsRecallZeroScenarioModule extends ConfigurationTestModule {
        public ReplaysPriorExchangesAndHonorsRecallZeroScenarioModule() {
            super(configurationValues(ReplaysPriorExchangesAndHonorsRecallZeroScenario.directory, Map.of()));
        }
    }

    public static class ModelOverrideAppliesScenarioModule extends ConfigurationTestModule {
        public ModelOverrideAppliesScenarioModule() {
            super(configurationValues(
                    ModelOverrideAppliesScenario.directory, Map.of("yca.slashcommands.btw.model", "btw-model")));
        }
    }

    public static class NativeToolCallsRoundTripScenarioModule extends ConfigurationTestModule {
        public NativeToolCallsRoundTripScenarioModule() {
            super(configurationValues(NativeToolCallsRoundTripScenario.directory, Map.of()));
        }
    }

    public static class SharedAnswererUsesBuilderAndPersistedRootsForReadBashAndSkillScenarioModule
            extends ConfigurationTestModule {
        public SharedAnswererUsesBuilderAndPersistedRootsForReadBashAndSkillScenarioModule() {
            super(configurationValues(
                    SharedAnswererUsesBuilderAndPersistedRootsForReadBashAndSkillScenario.directory, Map.of()));
        }
    }

    public static class MissingStaleAndInvalidMarkersFallBackToConfiguredWorkingDirectoryScenarioModule
            extends ConfigurationTestModule {
        public MissingStaleAndInvalidMarkersFallBackToConfiguredWorkingDirectoryScenarioModule() {
            super(configurationValues(
                    MissingStaleAndInvalidMarkersFallBackToConfiguredWorkingDirectoryScenario.directory, Map.of()));
        }
    }

    public static class ToolLessRequestProjectsSessionRootWithoutExecutingToolsScenarioModule
            extends ConfigurationTestModule {
        public ToolLessRequestProjectsSessionRootWithoutExecutingToolsScenarioModule() {
            super(configurationValues(
                    ToolLessRequestProjectsSessionRootWithoutExecutingToolsScenario.directory, Map.of()));
        }
    }

    public static class SideRequestFreezesRootWhenBuilderAndMarkerChangeDuringToolsScenarioModule
            extends ConfigurationTestModule {
        public SideRequestFreezesRootWhenBuilderAndMarkerChangeDuringToolsScenarioModule() {
            super(configurationValues(
                    SideRequestFreezesRootWhenBuilderAndMarkerChangeDuringToolsScenario.directory, Map.of()));
        }
    }

    public static class ToolCallMarkupIsParsedAndExecutedScenarioModule extends ConfigurationTestModule {
        public ToolCallMarkupIsParsedAndExecutedScenarioModule() {
            super(configurationValues(ToolCallMarkupIsParsedAndExecutedScenario.directory, Map.of()));
        }
    }

    public static class BashReadCommandRunsButDestructiveIsRefusedScenarioModule extends ConfigurationTestModule {
        public BashReadCommandRunsButDestructiveIsRefusedScenarioModule() {
            super(configurationValues(BashReadCommandRunsButDestructiveIsRefusedScenario.directory, Map.of()));
        }
    }

    public static class NonReadToolIsRefusedWithoutRunningScenarioModule extends ConfigurationTestModule {
        public NonReadToolIsRefusedWithoutRunningScenarioModule() {
            super(configurationValues(NonReadToolIsRefusedWithoutRunningScenario.directory, Map.of()));
        }
    }

    public static class ToolCallOnlyAnswerFallsBackToRawNoteAndIsNotReplayedScenarioModule
            extends ConfigurationTestModule {
        public ToolCallOnlyAnswerFallsBackToRawNoteAndIsNotReplayedScenarioModule() {
            super(configurationValues(
                    ToolCallOnlyAnswerFallsBackToRawNoteAndIsNotReplayedScenario.directory, Map.of()));
        }
    }

    public static class BlankAnswerRetriesOnceThenReturnsRetryableNoteScenarioModule extends ConfigurationTestModule {
        public BlankAnswerRetriesOnceThenReturnsRetryableNoteScenarioModule() {
            super(configurationValues(BlankAnswerRetriesOnceThenReturnsRetryableNoteScenario.directory, Map.of()));
        }
    }

    public static class ToolAttemptMarkupIsNeverExecutedAndSurfacesAsRetryableScenarioModule
            extends ConfigurationTestModule {
        public ToolAttemptMarkupIsNeverExecutedAndSurfacesAsRetryableScenarioModule() {
            super(configurationValues(
                    ToolAttemptMarkupIsNeverExecutedAndSurfacesAsRetryableScenario.directory, Map.of()));
        }
    }
}
