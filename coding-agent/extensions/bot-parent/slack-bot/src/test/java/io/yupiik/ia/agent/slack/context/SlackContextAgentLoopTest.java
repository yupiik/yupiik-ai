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
package io.yupiik.ia.agent.slack.context;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmission;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmissionConfiguration;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.MemoryExtractor;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextWindowCache;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.FileSessionStore;
import io.yupiik.ia.agent.core.todo.InMemoryTodoStore;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.slack.configuration.AgentSlackContextConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.configuration.SlackThreadConfiguration;
import io.yupiik.ia.agent.slack.context.agent.SlackContextClient;
import io.yupiik.ia.agent.slack.context.agent.SlackContextTool;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.service.Sandbox;
import io.yupiik.ia.agent.slack.service.SlackAdmission;
import io.yupiik.ia.agent.slack.service.SlackFeedback;
import io.yupiik.ia.agent.slack.service.SlackMentionParser;
import io.yupiik.ia.agent.slack.service.SlackPromptRenderer;
import io.yupiik.ia.agent.slack.service.SlackWorker;
import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.support.SlackConfigurationTestModule;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import io.yupiik.ia.agent.slack.support.SlackJsonMapper;
import io.yupiik.llm.client.openai.model.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Assembled admission/worker/mailbox/core loop acceptance. Only Slack and inference HTTP and the
 * container boundary are faked. The sandbox runs real ChatClient, AgentLoop, tool dispatch and file
 * sessions; it translates the container mount path and writes the loop's answer to the worker result.
 * The mount fixture tests wiring, not real tmpfs or Docker isolation (covered separately).
 */
@FusionSupport(modules = SlackContextAgentLoopTest.AgentModule.class)
class SlackContextAgentLoopTest {
    @Fusion
    private SlackPrompts baselinePrompts;

    private static final String TOKEN = "agent-loop-action-secret";
    private static final String NAME = "CanaryMemberAgentLoop";
    private static final String QUERY = "canary query agent loop";
    private static final String EVIDENCE = "Canary evidence retrieved only through Slack context";
    private static final String FINAL = "Canary final answer: " + NAME + ": " + EVIDENCE;

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inferenceChoosesContextAndReceivesRealToolResultsBeforeWorkerPublication(final boolean ambiguous)
            throws Exception {
        final var config = new SlackContextConfiguration(
                true,
                true,
                true,
                List.of("C1"),
                30,
                32,
                12,
                20,
                131072,
                20,
                2,
                600,
                false,
                new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
                false);
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var actions = new SlackActionRegistry(config, Clock.systemUTC());
                final var dispatcher = new SlackContextAttempt.Dispatcher(config);
                final var sandbox = new CoreSandbox(fixture)) {
            final var admission = new SlackAdmission(
                    fixture.configuration, new SlackMentionParser(), fixture.store, actions, Clock.systemUTC());
            final var payload = fixture.payload(Map.of(
                    "type", "event_callback",
                    "event_id", "EvCoreContext",
                    "team_id", "T1",
                    "is_ext_shared_channel", false,
                    "event",
                            Map.of(
                                    "type",
                                    "app_mention",
                                    "channel",
                                    "C1",
                                    "user",
                                    "U1",
                                    "ts",
                                    Instant.now().getEpochSecond() + ".000100",
                                    "thread_ts",
                                    "1.1",
                                    "text",
                                    "<@UBOT> Find the release evidence from <@U1>.",
                                    "action_token",
                                    TOKEN)));
            final var id = admission.admit(payload).orElseThrow();
            assertEquals(TaskStatus.queued, fixture.store.list().getFirst().status());
            final var timestamp = (Instant.now().getEpochSecond() - 10) + ".000001";
            final var permalink = "https://example.slack.com/archives/C1/p" + timestamp.replace(".", "");
            sandbox.permalink = permalink;
            fixture.response = exchange -> switch (exchange.request().uri().getPath()) {
                case "/api/users.info" -> {
                    assertEquals("user=U1", exchange.body());
                    yield fixture.json.toString(Map.of(
                            "ok",
                            true,
                            "user",
                            Map.of(
                                    "id",
                                    "U1",
                                    "team_id",
                                    "T1",
                                    "deleted",
                                    false,
                                    "is_bot",
                                    false,
                                    "name",
                                    NAME,
                                    "profile",
                                    Map.of("display_name", NAME, "real_name", NAME))));
                }
                case "/api/assistant.search.context" -> {
                    final var body = object(fixture.json, exchange.body());
                    assertEquals(TOKEN, body.get("action_token"));
                    assertEquals("in:<#C1> from:<@U1> \"" + QUERY + "\"", body.get("query"));
                    assertEquals("C1", body.get("context_channel_id"));
                    assertEquals(List.of("public_channel"), body.get("channel_types"));
                    yield fixture.json.toString(Map.of(
                            "ok",
                            true,
                            "results",
                            Map.of(
                                    "messages",
                                    List.of(Map.of(
                                            "author_user_id",
                                            "U1",
                                            "team_id",
                                            "T1",
                                            "channel_id",
                                            "C1",
                                            "message_ts",
                                            timestamp,
                                            "content",
                                            EVIDENCE,
                                            "permalink",
                                            permalink)))));
                }
                case "/api/chat.postMessage" -> {
                    assertTrue(sandbox.stopped.get(), "Runtime stop must be confirmed before publication");
                    assertEquals(3, sandbox.requests.size(), "Two real tool rounds precede the final answer");
                    assertTrue(Files.isDirectory(sandbox.directory), "Attempt lives through publication");
                    assertDurableExclusion(fixture, TaskStatus.publishing, permalink);
                    final var body = object(fixture.json, exchange.body());
                    assertEquals("C1", body.get("channel"));
                    assertEquals("1.1", body.get("thread_ts"));
                    final var section = (Map<?, ?>) ((List<?>) body.get("blocks")).getFirst();
                    assertEquals(Map.of("type", "plain_text", "text", FINAL + " " + permalink), section.get("text"));
                    assertNoCredentials(exchange.body());
                    yield ambiguous
                            ? "{\"ok\":true,\"channel\":\"C1\"}"
                            : "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"2.1\"}";
                }
                default ->
                    throw new AssertionError(
                            "Unexpected Slack request: " + exchange.request().uri());
            };

            final var worker = worker(fixture, sandbox, config, actions, dispatcher);
            assertTrue(worker.runOne());
            // Worker catches sandbox failures; do not let a failed core assertion become a false pass.
            sandbox.completed.get(5, TimeUnit.SECONDS);
            assertDurableExclusion(fixture, ambiguous ? TaskStatus.recovery_blocked : TaskStatus.done, permalink);
            final var saved =
                    new SlackStore(fixture.configuration, fixture.json).list().getFirst();
            assertEquals(id, saved.id());
            assertEquals(ambiguous ? null : "2.1", saved.messageTs());
            assertEquals(saved.runtimeId() + "-answer", saved.publicationId());
            assertEquals(
                    List.of("/api/users.info", "/api/assistant.search.context", "/api/chat.postMessage"),
                    fixture.exchanges.stream()
                            .map(e -> e.request().uri().getPath())
                            .toList());
            assertFalse(
                    Files.exists(sandbox.directory), "Transient sessions, configuration and result must be removed");
            try (final var children = Files.list(root.resolve("attempts"))) {
                assertEquals(0, children.count());
            }
            assertTrue(actions.claim(saved, 0, System.currentTimeMillis()).isEmpty());
            assertFalse(worker.runOne(), "Neither success nor ambiguous publication may replay the answer");
            assertEquals(3, fixture.exchanges.size());
            assertEquals(3, sandbox.requests.size());
            final var nextPrompt = new SlackPromptRenderer(SlackDomainFixture.prompts(baselinePrompts), fixture.json)
                    .render(
                            SlackDomainFixture.task("next", "U1", TaskStatus.queued, System.currentTimeMillis()),
                            fixture.store.list(),
                            false,
                            Map.of());
            assertExcluded(nextPrompt, permalink);
        }
    }

    private SlackWorker worker(
            final SlackDomainFixture fixture,
            final Sandbox sandbox,
            final SlackContextConfiguration config,
            final SlackActionRegistry actions,
            final SlackContextAttempt.Dispatcher dispatcher)
            throws IOException {
        final var defaults = fixture.executor(root);
        final var attemptRoot = Files.createDirectory(
                        Path.of(defaults.attemptRoot()),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                .toRealPath();
        final var services = new ExecutorConfiguration.Docker.Services(
                "test-services:local",
                "test-control",
                root.resolve("unused.p12").toString(),
                "YCA_SERVICES_KEYSTORE_PASSWORD",
                "attempt-services.yca.internal",
                "http://192.168.1.2:8080/v1",
                new ExecutorConfiguration.Docker.Egress("test-services:local", List.of(), "127.0.0.1", false));
        final var executor = new ExecutorConfiguration(
                defaults.backend(),
                attemptRoot.toString(),
                defaults.timeoutSeconds(),
                defaults.commandTimeoutSeconds(),
                defaults.showToolOutput(),
                new ExecutorConfiguration.Docker("docker", "test-agent:local", "", null, services, true),
                defaults.nono(),
                defaults.kubernetes(),
                defaults.relay());
        final var storage = new SlackContextStorage() {
            @Override
            protected List<String> mountInfo() {
                return List.of("41 1 0:41 / " + attemptRoot + " rw,nosuid,nodev,noexec - tmpfs tmpfs rw,noswap");
            }

            @Override
            protected String operatingSystem() {
                return "Linux";
            }
        };
        return new SlackWorker(
                fixture.configuration,
                executor,
                fixture.json,
                fixture.store,
                new SlackPromptRenderer(SlackDomainFixture.prompts(baselinePrompts), fixture.json),
                sandbox,
                new SlackFeedback(
                        fixture.configuration, SlackDomainFixture.prompts(baselinePrompts), fixture.store, fixture.api),
                fixture.api,
                new HostAdmission(new HostAdmissionConfiguration("", 1, "")),
                config,
                actions,
                dispatcher,
                storage);
    }

    private static void assertDurableExclusion(
            final SlackDomainFixture fixture, final TaskStatus status, final String permalink) {
        final var saved = fixture.store.list().getFirst();
        assertEquals(status, saved.status());
        assertTrue(saved.contextBearing());
        assertNull(saved.answer());
        assertNull(saved.question());
        assertDoesNotThrow(() -> {
            try (final var files = Files.walk(fixture.configuration.stateRoot())) {
                for (final var file : files.filter(Files::isRegularFile).toList()) {
                    assertExcluded(Files.readString(file), permalink);
                }
            }
            assertExcluded(Files.readString(Path.of(fixture.configuration.agentConfiguration())), permalink);
        });
    }

    private static void assertExcluded(final String text, final String permalink) {
        assertNoCredentials(text);
        for (final var canary : List.of(NAME, QUERY, EVIDENCE, FINAL, permalink)) {
            assertFalse(text.contains(canary), "Context-derived data must not reach durable state or later history");
        }
    }

    private static void assertNoCredentials(final String text) {
        for (final var secret : List.of(TOKEN, "xoxb-test", "xapp-test")) {
            assertFalse(text.contains(secret), "Controller credentials must not reach inference or publication");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final JsonMapper mapper, final String text) {
        return (Map<String, Object>) mapper.fromString(Object.class, text);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(final Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static JsonMapper mapper() {
        return SlackJsonMapper.create();
    }

    @Fusion
    private CodingAgentConfiguration baselineAgent;

    public static class AgentModule extends SlackConfigurationTestModule {
        public AgentModule() {
            super(Map.ofEntries(
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://model.invalid/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.api", "completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "synthetic-inference-key"),
                    Map.entry("yca.provider.providers.0.model", "slack-context-acceptance"),
                    Map.entry("yca.provider.providers.0.contextWindow", "128000"),
                    Map.entry("yca.provider.providers.0.outputTokens", "1024"),
                    Map.entry("yca.llm.maxIterations", "4"),
                    Map.entry("yca.permissions.mode", "yolo"),
                    Map.entry("yca.memory.enabled", "false"),
                    Map.entry("yca.pruning.enabled", "false"),
                    Map.entry("yca.compaction.enabled", "false"),
                    Map.entry("yca.compression.enabled", "false"),
                    Map.entry("yca.structuralCodeGraph.enabled", "false"),
                    Map.entry("yca.mcp.servers.length", "0")));
        }
    }

    private final class CoreSandbox implements Sandbox {
        private final SlackDomainFixture fixture;
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        private volatile Path directory;
        private String permalink;

        private CoreSandbox(final SlackDomainFixture fixture) {
            this.fixture = fixture;
        }

        @Override
        public int execute(
                final Path attempt, final String runtime, final String prompt, final Path temporaryDirectory) {
            directory = attempt;
            try {
                final var task = fixture.store.list().getFirst();
                assertEquals(TaskStatus.running, task.status());
                assertTrue(task.contextBearing());
                assertEquals(runtime, task.runtimeId());
                assertEquals(attempt.resolve("tmp"), temporaryDirectory);
                assertTrue(Files.isDirectory(temporaryDirectory));
                assertTrue(prompt.contains("slack_context"));
                assertExcluded(prompt, permalink);
                final var document = fixture.json.fromString(
                        AgentConfigurationDocument.class, Files.readString(attempt.resolve("configuration.json")));
                final var context = (Map<?, ?>) document.configuration().get("slackContext");
                assertEquals(true, context.get("enabled"));
                assertEquals("/attempt/storage/slack-context", context.get("directory"));
                assertNoCredentials(fixture.json.toString(document));
                final var mailbox = attempt.resolve("storage/slack-context");
                assertTrue(Files.isDirectory(mailbox));
                final var home = attempt.resolve("storage");
                final var workdir = attempt.resolve("workspace");
                try (final var json = mapper();
                        final var transport = new InferenceTransport(json, requests, permalink);
                        final var http =
                                new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(transport))) {
                    final var configuration = new CodingAgentConfiguration(
                            home.toString(),
                            baselineAgent.home(),
                            workdir.toString(),
                            workdir.toString(),
                            baselineAgent.provider(),
                            baselineAgent.model(),
                            baselineAgent.llm(),
                            baselineAgent.compaction(),
                            baselineAgent.pruning(),
                            baselineAgent.compression(),
                            baselineAgent.memory(),
                            baselineAgent.todos(),
                            baselineAgent.optimize(),
                            baselineAgent.permissions(),
                            baselineAgent.sandbox(),
                            baselineAgent.mcp(),
                            baselineAgent.mavenRepositoryKnowledge(),
                            baselineAgent.structuralCodeGraph(),
                            baselineAgent.sessions(),
                            baselineAgent.skills(),
                            baselineAgent.agents(),
                            baselineAgent.mode(),
                            baselineAgent.httpClient(),
                            baselineAgent.tools(),
                            baselineAgent.subagents(),
                            baselineAgent.schedulerThreads(),
                            baselineAgent.metrics(),
                            baselineAgent.maxLineLength(),
                            baselineAgent.resumeAliases(),
                            baselineAgent.slashcommands(),
                            baselineAgent.prompt(),
                            baselineAgent.labels(),
                            baselineAgent.logging(),
                            baselineAgent.hooks(),
                            baselineAgent.notifications(),
                            baselineAgent.secrets(),
                            null);
                    final var clock = Clock.systemUTC();
                    final var providers = new ProviderManager(configuration, json);
                    try (final var chat = new ChatClient(configuration, json, providers, clock, http)) {
                        final var sessions = new FileSessionStore(configuration, json, clock);
                        final var sessionId = sessions.create();
                        final var permissions = new PermissionService(configuration, workdir);
                        final var usage = new UsageTracker(
                                providers, chat, new ContextWindowCache(configuration, json, clock), clock);
                        final var todos = new InMemoryTodoStore();
                        final var events = new CopyOnWriteArrayList<AgentEvent>();
                        final var emitter = new Emitter() {
                            @Override
                            public <T> void emit(final T event) {
                                if (event instanceof AgentEvent agentEvent) {
                                    events.add(agentEvent);
                                }
                            }
                        };
                        final var agents = new AgentProvider(configuration);
                        final var system = new SystemPromptBuilder(
                                configuration, null, todos, permissions, agents, providers, null, null, null, workdir);

                        final var tool = new SlackContextTool(new SlackContextClient(
                                new AgentSlackContextConfiguration(
                                        true, mailbox.toString(), (String) context.get("channel"), 15),
                                json));
                        final var mcp = new McpManager(configuration, json, List.of(tool), null, null);
                        try {
                            final var loop = new AgentLoop(
                                    chat,
                                    new ToolRegistry(mcp),
                                    new ToolExecutionService(configuration, emitter, List.of()),
                                    configuration,
                                    permissions,
                                    usage,
                                    null,
                                    new ContextManager(configuration, usage, null),
                                    null,
                                    sessions,
                                    system,
                                    new MemoryExtractor(
                                            chat,
                                            configuration,
                                            providers,
                                            null,
                                            json,
                                            null,
                                            emitter,
                                            clock,
                                            usage,
                                            sessionId,
                                            null,
                                            null,
                                            null,
                                            workdir),
                                    new StatusReporter(),
                                    emitter,
                                    json,
                                    new StructuralCodeGraph(
                                            configuration, List.of(), json, chat, providers, clock, null),
                                    providers,
                                    agents,
                                    clock,
                                    null,
                                    todos,
                                    null,
                                    null,
                                    workdir,
                                    new DisabledSandbox(workdir));
                            try {

                                final var answer = loop.run(prompt, "build", sessionId, List.of(), () -> false);
                                assertEquals(
                                        FINAL + " " + permalink,
                                        answer,
                                        () -> "Core events: " + events + "; inference rounds=" + requests.size());
                                assertEquals(
                                        answer,
                                        sessions.load(sessionId).getLast().content());
                                assertFalse(events.stream().anyMatch(AgentEvent.Error.class::isInstance));
                                assertEquals(3, requests.size());
                                assertFalse(Files.exists(mailbox.resolve("request.json")));
                                assertFalse(Files.exists(mailbox.resolve("response.json")));
                                assertDurableExclusion(fixture, TaskStatus.running, permalink);
                                // Prove context really was persisted, but only under the transient attempt.
                                try (final var files = Files.walk(home)) {
                                    assertTrue(
                                            files.filter(Files::isRegularFile).anyMatch(file -> {
                                                try {
                                                    return Files.readString(file)
                                                            .contains(EVIDENCE);
                                                } catch (final IOException failure) {
                                                    throw new IllegalStateException(failure);
                                                }
                                            }),
                                            "Real file sessions must contain the tool result before attempt cleanup");
                                }
                                Files.writeString(
                                        attempt.resolve("output/result.json"),
                                        fixture.json.toString(Map.of("taskId", task.id(), "summary", answer)));
                            } finally {
                                loop.close();
                            }
                        } finally {
                            mcp.close();
                        }
                    }
                }
                completed.complete(null);
                return 0;
            } catch (final Throwable failure) {
                completed.completeExceptionally(failure);
                throw new IllegalStateException("Assembled core agent failed", failure);
            }
        }

        @Override
        public void stop(final String runtime) {
            stopped.set(true);
        }

        @Override
        public void close() {}
    }

    /** In-memory HTTP/SSE boundary. No direct tool invocation or synthetic tool-result messages. */
    private static final class InferenceTransport extends HttpClient {
        private final JsonMapper json;
        private final List<Map<String, Object>> requests;
        private final String permalink;

        private InferenceTransport(
                final JsonMapper json, final List<Map<String, Object>> requests, final String permalink) {
            this.json = json;
            this.requests = requests;
            this.permalink = permalink;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            assertEquals("model.invalid", request.uri().getHost());
            if ("GET".equals(request.method())
                    && "/v1/models".equals(request.uri().getPath())) {
                return respond(request, handler, "application/json", "{\"data\":[]}");
            }
            assertEquals("POST", request.method());
            assertEquals("/v1/chat/completions", request.uri().getPath());
            final var bytes = new ByteArrayOutputStream();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                @Override
                public void onSubscribe(final Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(final ByteBuffer buffer) {
                    final var chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    bytes.writeBytes(chunk);
                }

                @Override
                public void onError(final Throwable error) {
                    throw new AssertionError(error);
                }

                @Override
                public void onComplete() {}
            });
            final var text = bytes.toString(StandardCharsets.UTF_8);
            assertNoCredentials(text);
            final var body = object(json, text);
            requests.add(body);
            final var round = requests.size();
            assertTrue(round <= 3, "Unexpected extra inference request (no retries expected)");
            assertTrue(objects(body.get("tools")).stream()
                    .anyMatch(tool -> "slack_context".equals(((Map<?, ?>) tool.get("function")).get("name"))));
            final var messages = objects(body.get("messages"));
            if (round == 1) {
                assertExcluded(text, permalink);
                assertFalse(messages.stream().anyMatch(message -> "tool".equals(message.get("role"))));
            } else {
                assertToolResult(messages, "resolve-call", NAME);
                if (round == 3) {
                    assertToolResult(messages, "search-call", EVIDENCE);
                    assertToolResult(messages, "search-call", permalink);
                }
            }
            final Map<String, Object> delta = round == 3
                    ? Map.of("role", "assistant", "content", FINAL + " " + permalink)
                    : Map.of(
                            "role",
                            "assistant",
                            "tool_calls",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "id",
                                    round == 1 ? "resolve-call" : "search-call",
                                    "type",
                                    "function",
                                    "function",
                                    Map.of(
                                            "name",
                                            "slack_context",
                                            "arguments",
                                            json.toString(
                                                    round == 1
                                                            ? Map.of("operation", "resolve_user", "userId", "U1")
                                                            : Map.of(
                                                                    "operation",
                                                                    "search",
                                                                    "authorUserId",
                                                                    "U1",
                                                                    "terms",
                                                                    List.of(QUERY)))))));
            final var sse = "data: "
                    + json.toString(
                            Map.of("id", "completion-" + round, "choices", List.of(Map.of("index", 0, "delta", delta))))
                    + "\n\ndata: "
                    + json.toString(Map.of(
                            "id",
                            "completion-" + round,
                            "choices",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "delta",
                                    Map.of(),
                                    "finish_reason",
                                    round == 3 ? "stop" : "tool_calls"))))
                    + "\n\ndata: [DONE]\n\n";
            return respond(request, handler, "text/event-stream", sse);
        }

        private void assertToolResult(
                final List<Map<String, Object>> messages, final String callId, final String canary) {
            assertTrue(messages.stream()
                    .anyMatch(message -> "assistant".equals(message.get("role"))
                            && message.get("tool_calls") instanceof List<?> calls
                            && calls.stream().anyMatch(call -> callId.equals(((Map<?, ?>) call).get("id")))));
            final var results = messages.stream()
                    .filter(message -> "tool".equals(message.get("role")) && callId.equals(message.get("tool_call_id")))
                    .toList();
            assertEquals(1, results.size(), "Real tool_result must match the model's original call ID");
            final var result = (String) results.getFirst().get("content");
            assertTrue(result.contains(canary), "Canary must reach inference inside tool_result, not just a prompt");
            assertEquals("ok", object(json, result).get("status"));
            assertTrue(result.contains("\"untrusted\":true"));
        }

        private <T> CompletableFuture<HttpResponse<T>> respond(
                final HttpRequest request,
                final HttpResponse.BodyHandler<T> handler,
                final String type,
                final String content) {
            final var headers = HttpHeaders.of(Map.of("content-type", List.of(type)), (a, b) -> true);
            final var subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                @Override
                public int statusCode() {
                    return 200;
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            });
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean sent;

                @Override
                public void request(final long count) {
                    if (!sent && count > 0) {
                        sent = true;
                        subscriber.onNext(List.of(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8))));
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    sent = true;
                }
            });
            return subscriber.getBody().toCompletableFuture().thenApply(body -> new Response<>(request, headers, body));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request,
                final HttpResponse.BodyHandler<T> handler,
                final HttpResponse.PushPromiseHandler<T> push) {
            throw new AssertionError("No HTTP push expected");
        }

        @Override
        public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            throw new AssertionError("No blocking HTTP expected");
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            throw new AssertionError("No TLS expected");
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        private record Response<T>(HttpRequest request, HttpHeaders headers, T body) implements HttpResponse<T> {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public Optional<HttpResponse<T>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return request.uri();
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        }
    }
}
