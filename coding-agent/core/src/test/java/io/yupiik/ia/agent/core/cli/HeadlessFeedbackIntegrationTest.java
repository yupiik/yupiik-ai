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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.MemoryExtractor;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextWindowCache;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import io.yupiik.ia.agent.core.llm.ToolCallArgumentsRepairer;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.todo.InMemoryTodoStore;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.builtin.AskUserTool;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.ByteArrayOutputStream;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real headless command, loop, tool dispatch and ChatClient; only the model's HTTP transport is scripted.
 * No TCP listener, shared mapper or excluded warmup: the deadline includes fixture construction
 * and cleanup. Default feedback cancellation and independent approval denial also have focused coverage in
 * RunCommandTest.fusionDefaultFeedbackCancelsWithoutChangingOtherSessions and
 * RunCommandTest.fusionExactContractOverrideAnswersWithoutApprovingToolsOrChangingOtherSessions.
 */
@FusionSupport(modules = HeadlessFeedbackIntegrationTest.Module.class)
@Isolated // RunCommand installs a process-wide SIGINT handler and writes to the CLI streams.
class HeadlessFeedbackIntegrationTest {
    @TempDir
    private static Path testWorkdir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("try") // The resource restores the process-wide SIGINT handler after RunCommand.
    void answerReachesNextModelRequestAndFinalStoredResult(
            final boolean fromFile, @Fusion final CodingAgentConfiguration configuration) throws Exception {
        final var home = testWorkdir;
        try (final var signal = preserveInterruptHandler();
                final var mapper = mapper();
                final var transport = new ModelTransport(mapper);
                final var http = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(transport))) {
            final var clock = Clock.systemUTC();
            final var providers = new ProviderManager(configuration, mapper);
            try (final var chat = new ChatClient(
                    configuration, mapper, providers, clock, http, new ToolCallArgumentsRepairer(mapper))) {
                final var store = new InMemorySessionStore(clock);
                final var sessionId = store.create();
                final var permissions = new PermissionService(configuration, (configuration).resolveWorkingDir());
                final var feedback = new UserFeedbackService();
                // RunCommand must replace this stale session gate with its injected headless gate.
                feedback.setGate(questions -> {
                    throw new AssertionError("RunCommand did not install its feedback gate");
                });
                final var usage =
                        new UsageTracker(providers, chat, new ContextWindowCache(configuration, mapper, clock), clock);
                final var todos = new InMemoryTodoStore();
                final var status = new StatusReporter();
                final var events = new ArrayList<AgentEvent>();
                final var registry = new ShellRegistry(new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        // ShellRegistry's outbound events need no other application observers here.
                    }
                });
                final var emitter = new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        if (event instanceof AgentEvent agentEvent) {
                            events.add(agentEvent);
                            registry.onEvent(agentEvent);
                        }
                    }
                };
                final var agents = new AgentProvider(configuration);
                final var prompt = new SystemPromptBuilder(
                        configuration, null, todos, permissions, agents, providers, null, null, null, home);

                final var context = new ContextManager(configuration, usage, null);
                final var mcp = new McpManager(configuration, mapper, List.of(new AskUserTool(mapper)), null, null);
                try {
                    final var loop = new AgentLoop(
                            chat,
                            new ToolRegistry(mcp),
                            new ToolExecutionService(configuration, emitter, List.of()),
                            configuration,
                            permissions,
                            usage,
                            null,
                            context,
                            null,
                            store,
                            prompt,
                            new MemoryExtractor(
                                    chat,
                                    configuration,
                                    providers,
                                    null,
                                    mapper,
                                    null,
                                    emitter,
                                    clock,
                                    usage,
                                    sessionId,
                                    null,
                                    null,
                                    null,
                                    (configuration).resolveWorkingDir()),
                            status,
                            emitter,
                            mapper,
                            new StructuralCodeGraph(configuration, List.of(), mapper, chat, providers, clock, null),
                            providers,
                            agents,
                            clock,
                            null,
                            todos,
                            null,
                            sessionId,
                            null,
                            null,
                            feedback,
                            new PrecisionDurationMarkerProvider(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            (configuration).resolveWorkingDir(),
                            new DisabledSandbox((configuration).resolveWorkingDir()));
                    final var runtime = new SessionRuntime(
                            loop,
                            permissions,
                            feedback,
                            usage,
                            todos,
                            status,
                            context,
                            prompt,
                            home,
                            new DisabledSandbox(home));
                    final var factory = new SessionRuntimeFactory() {
                        @Override
                        public Path resolveWorkdir(final Path explicitWorkdir, final String resumedSessionId) {
                            return explicitWorkdir == null ? home : explicitWorkdir;
                        }

                        @Override
                        public SessionRuntime create(final String id, final Path workdir, final Boolean sandbox) {
                            assertEquals(sessionId, id);
                            assertEquals(home, workdir);
                            return runtime;
                        }
                    };
                    final var answered = new AtomicInteger();
                    final var taskPrompt = "Ask which choice to use, then finish.";
                    final var promptFile = home.resolve("prompt.txt");
                    if (fromFile) {
                        Files.createFile(
                                promptFile,
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                        Files.writeString(promptFile, taskPrompt);
                    }
                    new RunCommand(
                                    new RunCommand.RunConf(
                                            fromFile ? "" : taskPrompt,
                                            sessionId,
                                            "",
                                            false,
                                            "",
                                            "hidden",
                                            "",
                                            "",
                                            "",
                                            "",
                                            1000000,
                                            "",
                                            fromFile ? promptFile.toString() : null,
                                            null),
                                    registry,
                                    factory,
                                    store,
                                    agents,
                                    mcp,
                                    mapper,
                                    questions -> {
                                        answered.incrementAndGet();
                                        assertEquals(
                                                List.of(new Question("choice", "Pick?", List.of("yes", "no"), false)),
                                                questions);
                                        return Map.of("choice", "yes");
                                    })
                            .run();
                    assertEquals(1, answered.get());
                    assertEquals(2, transport.requests.size(), "one ask_user round and one final answer");
                    final var first = transport.requests.getFirst();
                    assertTrue(
                            objects(first.get("messages")).stream()
                                    .anyMatch(message -> "user".equals(message.get("role"))
                                            && taskPrompt.equals(message.get("content"))),
                            "the file prompt must reach the real model wire request unchanged");
                    assertTrue(objects(first.get("tools")).stream()
                            .anyMatch(tool -> "ask_user".equals(((Map<?, ?>) tool.get("function")).get("name"))));
                    final var messages = objects(transport.requests.getLast().get("messages"));
                    assertTrue(messages.stream()
                            .anyMatch(message -> "assistant".equals(message.get("role"))
                                    && message.get("tool_calls") instanceof List<?> calls
                                    && calls.stream()
                                            .anyMatch(call -> "feedback-call".equals(((Map<?, ?>) call).get("id")))));
                    assertTrue(
                            messages.stream()
                                    .anyMatch(message -> "tool".equals(message.get("role"))
                                            && "feedback-call".equals(message.get("tool_call_id"))
                                            && Map.of("choice", "yes").equals(mapper.fromString(Object.class, (String)
                                                    message.get("content")))),
                            "real ask_user output must reach the next wire request with the original call id");
                    assertEquals(
                            "Feedback accepted: yes.",
                            store.load(sessionId).getLast().content());
                    assertEquals("assistant", store.load(sessionId).getLast().role());
                    assertFalse(events.stream().anyMatch(AgentEvent.Error.class::isInstance));
                    assertFalse(
                            permissions
                                    .check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                                    .allowed(),
                            "answering a question must not approve a separate headless permission request");
                } finally {
                    mcp.close();
                }
            }
        }
    }

    private static JsonMapper mapper() {
        return JsonMappers.create(ignored -> Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(final Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static AutoCloseable preserveInterruptHandler() throws Exception {
        final var signalType = Class.forName("sun.misc.Signal");
        final var handlerType = Class.forName("sun.misc.SignalHandler");
        final var signal = signalType.getConstructor(String.class).newInstance("INT");
        final var handle = signalType.getMethod("handle", signalType, handlerType);
        final var previous =
                handle.invoke(null, signal, handlerType.getField("SIG_DFL").get(null));
        return () -> handle.invoke(null, signal, previous);
    }

    /** In-process HTTP boundary: consumes real request bytes and feeds SSE bytes to the real body handler. */
    private static final class ModelTransport extends HttpClient {
        private final JsonMapper mapper;
        private final List<Map<String, Object>> requests = new ArrayList<>();

        private ModelTransport(final JsonMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            if ("GET".equals(request.method())
                    && "/v1/models".equals(request.uri().getPath())) {
                return respond(request, handler, "application/json", "{\"data\":[]}");
            }
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
            @SuppressWarnings("unchecked")
            final var body =
                    (Map<String, Object>) mapper.fromString(Object.class, bytes.toString(StandardCharsets.UTF_8));
            requests.add(body);
            assertTrue(requests.size() <= 2, "unexpected extra model request");
            final var delta = requests.size() == 1
                    ? Map.of(
                            "role",
                            "assistant",
                            "tool_calls",
                            List.of(
                                    Map.of(
                                            "index",
                                            0,
                                            "id",
                                            "feedback-call",
                                            "type",
                                            "function",
                                            "function",
                                            Map.of(
                                                    "name",
                                                    "ask_user",
                                                    "arguments",
                                                    "{\"questions\":[{\"id\":\"choice\",\"prompt\":\"Pick?\",\"options\":[\"yes\",\"no\"]}]}"))))
                    : Map.of("role", "assistant", "content", "Feedback accepted: yes.");
            final var sse = "data: "
                    + mapper.toString(
                            Map.of("id", "completion", "choices", List.of(Map.of("index", 0, "delta", delta))))
                    + "\n\ndata: "
                    + mapper.toString(Map.of(
                            "id",
                            "completion",
                            "choices",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "delta",
                                    Map.of(),
                                    "finish_reason",
                                    requests.size() == 1 ? "tool_calls" : "stop"))))
                    + "\n\ndata: [DONE]\n\n";
            return respond(request, handler, "text/event-stream", sse);
        }

        private <T> CompletableFuture<HttpResponse<T>> respond(
                final HttpRequest request,
                final HttpResponse.BodyHandler<T> handler,
                final String contentType,
                final String content) {
            final var headers = HttpHeaders.of(Map.of("content-type", List.of(contentType)), (a, b) -> true);
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
                public void request(final long n) {
                    if (!sent) {
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
            return subscriber.getBody().toCompletableFuture().thenApply(result -> new HttpResponse<T>() {
                @Override
                public int statusCode() {
                    return 200;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public T body() {
                    return result;
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
                public Version version() {
                    return Version.HTTP_1_1;
                }
            });
        }

        @Override
        public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            return sendAsync(request, handler).join();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request,
                final HttpResponse.BodyHandler<T> handler,
                final HttpResponse.PushPromiseHandler<T> push) {
            return sendAsync(request, handler);
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
            throw new UnsupportedOperationException();
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

        @Override
        public void close() {}
    }

    public static class Module extends ConfigurationTestModule {
        public Module() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", testWorkdir.toString()),
                    Map.entry("yca.workingDir", testWorkdir.toString()),
                    Map.entry("yca.projectDir", testWorkdir.toString()),
                    Map.entry("yca.provider.providers.length", "1"),
                    Map.entry("yca.provider.providers.0.id", "default"),
                    Map.entry("yca.provider.providers.0.baseUrl", "http://model.invalid/v1/chat/completions"),
                    Map.entry("yca.provider.providers.0.api", "completions"),
                    Map.entry("yca.provider.providers.0.apiKey", "test"),
                    Map.entry("yca.provider.providers.0.model", "feedback-regression"),
                    Map.entry("yca.provider.providers.0.contextWindow", "128000"),
                    Map.entry("yca.provider.providers.0.outputTokens", "1024"),
                    Map.entry("yca.llm.maxIterations", "3"),
                    Map.entry("yca.llm.maxConcurrent", "0"),
                    Map.entry("yca.llm.retriesMaxAttempts", "0"),
                    Map.entry("yca.permissions.mode", "ask"),
                    Map.entry("yca.memory.enabled", "false"),
                    Map.entry("yca.pruning.enabled", "false"),
                    Map.entry("yca.compaction.enabled", "false"),
                    Map.entry("yca.compression.enabled", "false"),
                    Map.entry("yca.structuralCodeGraph.enabled", "false"),
                    Map.entry("yca.mcp.servers.length", "0")));
        }
    }
}
