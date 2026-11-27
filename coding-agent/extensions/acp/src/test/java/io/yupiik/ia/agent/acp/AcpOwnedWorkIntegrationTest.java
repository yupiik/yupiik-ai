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

import io.yupiik.ai.acp.protocol.AcpProtocolModule;
import io.yupiik.ai.mcp.protocol.McpProtocolModule;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.main.CliLauncher;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.json.serialization.JsonCodec;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.YcaCoreModule;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent.CompactionPass;
import io.yupiik.ia.agent.core.agent.AgentEvent.ToolFilesChanged;
import io.yupiik.ia.agent.core.agent.AgentEvent.ToolFinished;
import io.yupiik.ia.agent.core.agent.AgentEvent.ToolStarted;
import io.yupiik.ia.agent.core.agent.AgentEvent.ToolStream;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.CodexLoginService;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.security.SecretNames;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.shell.*;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.shell.slash.CodexLoginCommands;
import io.yupiik.ia.agent.core.shell.slash.ConfigCommands;
import io.yupiik.ia.agent.core.shell.slash.InspectCommands;
import io.yupiik.ia.agent.core.shell.slash.MaintainCommands;
import io.yupiik.ia.agent.core.todo.PlanFileService;
import io.yupiik.ia.agent.core.todo.PlanTodoSeeder;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.tools.builtin.EditFileTool;
import io.yupiik.ia.agent.core.tools.builtin.WriteFileTool;
import io.yupiik.llm.client.codex.CodexModule;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.*;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Raw protocol plus real owned core handles; HTTP and indexing work are entirely controlled. */
@Timeout(15)
class AcpOwnedWorkIntegrationTest {

    private static Map<String, Object> object(final Object value) {
        assertInstanceOf(Map.class, value);
        @SuppressWarnings("unchecked")
        final var map = (Map<String, Object>) value;
        return map;
    }

    private static final class Block {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private void await() {
            entered.countDown();
            while (true) {
                try {
                    release.await();
                    return;
                } catch (final InterruptedException ignored) {
                    interrupted.countDown();
                }
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        // Login uses real durable credential serialization, not just ACP/core protocol codecs.
        private final JsonMapper mapper = new JsonMapperImpl(
                Stream.<FusionModule>of(
                                new McpProtocolModule(),
                                new AcpProtocolModule(),
                                new AcpModule(),
                                new YcaCoreModule(),
                                new CodexModule())
                        .flatMap(FusionModule::beans)
                        .filter(bean -> (bean.type() instanceof Class<?> type && JsonCodec.class.isAssignableFrom(type))
                                || (bean.type() instanceof java.lang.reflect.ParameterizedType parameterizedType
                                        && parameterizedType.getRawType() instanceof Class<?> raw
                                        && JsonCodec.class.isAssignableFrom(raw)))
                        .<JsonCodec<?>>map(bean -> (JsonCodec<?>) bean.create(null, new ArrayList<>()))
                        .toList(),
                key -> Optional.empty());
        private final OptionTestSupport support;
        private final InMemorySessionStore store = new InMemorySessionStore(Clock.systemUTC());
        private final AtomicReference<ShellRegistry> eventTarget = new AtomicReference<>();
        private final RuntimeContainer bus = ConfiguringContainer.of()
                .disableAutoDiscovery(true)
                .register(new FusionListener<AgentEvent>() {
                    @Override
                    public Type eventType() {
                        return AgentEvent.class;
                    }

                    @Override
                    public void onEvent(final RuntimeContainer container, final AgentEvent event) {
                        eventTarget.get().onEvent(event);
                    }
                })
                .start();
        private final ShellRegistry shells = new ShellRegistry(new Emitter() {
            @Override
            public <T> void emit(final T event) {
                bus.emit(event);
            }
        });
        private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpClient delegate = HttpClient.newHttpClient();
        private final FakeHttp http = new FakeHttp(delegate);
        private final CodexLoginService login;
        private final List<CodexLoginService.LoginRequest> loginRequests = new CopyOnWriteArrayList<>();
        private final List<CodexLoginService.LoginAttempt> attempts = new CopyOnWriteArrayList<>();
        private final List<MavenRepositoryKnowledgeService.IndexRun> jobs = new CopyOnWriteArrayList<>();
        private volatile boolean interruptIndexStartup;
        private final BlockingQueue<MavenRepositoryKnowledgeService.IndexRun> publishedJobs =
                new LinkedBlockingQueue<>();
        private final Map<String, SessionRuntime> runtimes = new ConcurrentHashMap<>();
        private final AtomicInteger disposals = new AtomicInteger();
        private final BlockingQueue<String> disposed = new LinkedBlockingQueue<>();
        private volatile Consumer<String> beforeDispose = id -> {};
        private volatile String failOutputWhen;
        private volatile Predicate<ChatSettings> acceptSettings = settings -> true;
        private volatile BiFunction<String, ToolRegistry, ToolRegistry> sessionTools = (id, registry) -> registry;
        private volatile Consumer<CodexLoginService.LoginAttempt> beforeAttemptReturn = attempt -> {};
        private volatile Block discovery;
        private volatile Supplier<String> indexWork = () -> "indexed";
        private volatile Function<TurnRunner.TurnRequest, CompletionStage<Boolean>> turnWork =
                request -> CompletableFuture.completedFuture(true);
        private final Map<String, Consumer<String>> progress = new ConcurrentHashMap<>();
        private final PipedInputStream input = new PipedInputStream(65536);
        private final PrintWriter writer;
        private final BlockingQueue<Map<String, Object>> output = new LinkedBlockingQueue<>();
        private final List<Map<String, Object>> deferred = new ArrayList<>();
        private final List<String> all = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final Map<String, Object> initialized;

        private Fixture(
                final CodingAgentConfiguration configuration,
                final AcpCommand.Conf commandOptions,
                final Path root,
                final Map<String, Object> caps,
                final ChatSettings selection)
                throws Exception {
            eventTarget.set(shells);
            support = new OptionTestSupport(
                    configuration,
                    commandOptions,
                    mapper,
                    settings -> CompletableFuture.completedFuture(new ModelsResponse(List.of())));

            login = new CodexLoginService(mapper, http, support.configuration) {
                @Override
                public LoginAttempt newAttempt(final LoginRequest request, final LoginListener listener) {
                    final var attempt = super.newAttempt(request, listener);
                    loginRequests.add(request);
                    attempts.add(attempt);
                    beforeAttemptReturn.accept(attempt);
                    return attempt;
                }

                @Override
                public CodexTokenStore load(final String provider) {
                    return null; // Never read any existing credentials, even in status/autologin tests.
                }
            };
            final var maven = new MavenRepositoryKnowledgeService() {
                @Override
                public IndexRun refreshJob(
                        final Path cwd,
                        final String gav,
                        final Consumer<String> progress,
                        final Consumer<String> onFinished,
                        final ChatSettings settings) {
                    assertNull(onFinished, "completion, not success-only callback, owns the prompt");
                    runtimes.forEach((id, runtime) -> {
                        if (cwd.equals(runtime.workdir())) {
                            Fixture.this.progress.putIfAbsent(id, progress);
                        }
                    });
                    if (discovery != null) {
                        discovery.await();
                    }
                    final var work = indexWork;
                    try {
                        final var ctor = IndexRun.class.getDeclaredConstructor(
                                Path.class, int.class, ChatSettings.class, List.class);
                        ctor.setAccessible(true);
                        final var run = ctor.newInstance(cwd, 1, settings, List.of("index started"));
                        jobs.add(run);
                        final var start = IndexRun.class.getDeclaredMethod(
                                "start", ExecutorService.class, Supplier.class, Consumer.class);
                        start.setAccessible(true);
                        final var started = new CountDownLatch(1);
                        final Supplier<String> actual = () -> {
                            started.countDown();
                            return work.get();
                        };
                        start.invoke(run, tasks, actual, null);
                        if (interruptIndexStartup) {
                            Thread.currentThread().interrupt();
                        }
                        try {
                            started.await();
                        } catch (final InterruptedException interrupted) {
                            // Work already owns a worker: return its handle even if cancellation races
                            // startup, so the caller can cancel and drain that exact operation.
                            Thread.currentThread().interrupt();
                        }
                        publishedJobs.add(run);
                        return run;
                    } catch (final ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                }

                @Override
                public IndexRun currentIndex(final Path cwd) {
                    throw new AssertionError("must retain exact returned job, never query latest");
                }
            };
            final var mcp = new McpManager(null, mapper, List.of(), null, null) {
                @Override
                public void start() {}
            };
            final var base = new ToolRegistry(mcp);
            final var factory = new SessionRuntimeFactory() {
                @Override
                public SessionRuntime create(
                        final String key,
                        final String id,
                        final ToolRegistry registry,
                        final Path cwd,
                        final Boolean sandboxOverride) {
                    final var effective = sessionTools.apply(id, registry);
                    final var loop = new AgentLoop(cwd, new DisabledSandbox(cwd)) {
                        private ChatSettings settings = ChatSettings.EMPTY;

                        @Override
                        public ChatSettings chatSettings() {
                            return settings;
                        }

                        @Override
                        public void updateChatSettings(final ChatSettings next) {
                            if (acceptSettings.test(next)) {
                                settings = next;
                            }
                        }

                        @Override
                        public boolean updateChatSettings(final ChatSettings next, final BooleanSupplier cancelled) {
                            if (acceptSettings.test(next) && !cancelled.getAsBoolean()) {
                                settings = next;
                                return true;
                            }
                            return false;
                        }

                        @Override
                        public ToolRegistry effectiveToolRegistry() {
                            return effective;
                        }

                        @Override
                        public Set<String> availableToolNames() {
                            return effective.tools().stream()
                                    .map(tool -> tool.name())
                                    .collect(Collectors.toSet());
                        }

                        @Override
                        public boolean forceExtractNow(final List<Message> messages, final ChatSettings settings) {
                            return false;
                        }

                        @Override
                        public boolean iterationLimitReached() {
                            return false;
                        }

                        @Override
                        public void refreshUsage() {}

                        @Override
                        public void close() {
                            beforeDispose.accept(id);
                            disposals.incrementAndGet();
                            disposed.add(id);
                        }
                    };
                    final var runtime = new SessionRuntime(
                            loop,
                            new PermissionService(support.configuration, cwd),
                            new UserFeedbackService(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            cwd,
                            new DisabledSandbox(cwd));
                    runtimes.put(id, runtime);
                    return runtime;
                }
            };
            final var turns = new TurnRunner(null, null, null) {
                @Override
                public CompletionStage<Boolean> submit(final TurnRequest request) {
                    return turnWork.apply(request);
                }
            };
            // AcpServer intentionally selects core command sets by package, so use a real MaintainCommands set.
            final var maintain = new MaintainCommands(null, mcp, maven);
            final var config = new ConfigCommands(null, support.chat, support.providers, null, null, null, null);
            final var server = new AcpServer(
                    mapper,
                    factory,
                    new SessionLifecycle(store),
                    shells,
                    store,
                    turns,
                    base,
                    mcp,
                    support.options,
                    null,
                    new SecretNames(support.configuration),
                    List.of(config, maintain, new InspectCommands(null, null), new CodexLoginCommands(login)),
                    null,
                    login,
                    maven,
                    support.providers,
                    support.configuration,
                    new PlanFileService(store),
                    new PlanTodoSeeder(store),
                    support.commandOptions);
            writer = new PrintWriter(new PipedOutputStream(input), true);
            final var stream = new PrintStream(OutputStream.nullOutputStream()) {
                @Override
                public void println(final String line) {
                    if (failOutputWhen != null && line.contains(failOutputWhen)) {
                        throw new IllegalStateException("provider-password-secret");
                    }
                    all.add(line);
                    output.add(object(mapper.fromString(Object.class, line)));
                }
            };
            Thread.ofVirtual().start(() -> {
                try {
                    server.run(input, stream, selection);
                    done.complete(null);
                } catch (final Throwable failure) {
                    done.completeExceptionally(failure);
                }
            });
            send("init", "initialize", Map.of("protocolVersion", 1, "clientCapabilities", caps));
            initialized = object(response("init").get("result"));
        }

        private String create(final Path root) throws Exception {
            send("new", "session/new", Map.of("cwd", root.toString(), "mcpServers", List.of()));
            final var response = response("new");
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
            final var message = new LinkedHashMap<String, Object>();
            message.put("jsonrpc", "2.0");
            if (id != null) {
                message.put("id", id);
            }
            message.put("method", method);
            message.put("params", params);
            writer.println(mapper.toString(message));
        }

        private void reply(final Map<String, Object> request, final Object result) {
            writer.println(mapper.toString(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result)));
        }

        private void barrier() throws Exception {
            send("barrier", "initialize", Map.of("protocolVersion", 1));
            response("barrier");
        }

        private Map<String, Object> response(final String id) throws Exception {
            return until(value -> !value.containsKey("method") && id.equals(value.get("id")));
        }

        private Map<String, Object> method(final String name) throws Exception {
            return until(value -> name.equals(value.get("method")));
        }

        private Map<String, Object> updateContaining(final String text) throws Exception {
            return until(value -> "session/update".equals(value.get("method"))
                    && mapper.toString(value).contains(text));
        }

        private Map<String, Object> until(final Predicate<Map<String, Object>> predicate) throws Exception {
            for (final var iterator = deferred.iterator(); iterator.hasNext(); ) {
                final var value = iterator.next();
                if (predicate.test(value)) {
                    iterator.remove();
                    return value;
                }
            }
            while (true) {
                final var value = output.poll(5, TimeUnit.SECONDS);
                assertNotNull(value, "missing protocol response; " + allOutput());
                if (predicate.test(value)) {
                    return value;
                }
                deferred.add(value);
            }
        }

        private boolean hasResponse(final String id) {
            return all.stream()
                    .map(line -> object(mapper.fromString(Object.class, line)))
                    .anyMatch(value -> !value.containsKey("method") && id.equals(value.get("id")));
        }

        private String allOutput() {
            return String.join("\n", all);
        }

        private List<Map<String, Object>> sessionUpdates(final String id) {
            return all.stream()
                    .map(line -> object(mapper.fromString(Object.class, line)))
                    .filter(value -> "session/update".equals(value.get("method")))
                    .map(value -> object(value.get("params")))
                    .filter(value -> id.equals(value.get("sessionId")))
                    .map(value -> object(value.get("update")))
                    .toList();
        }

        private List<Map<String, Object>> compactions(final String id) {
            return sessionUpdates(id).stream()
                    .filter(value -> "compaction_update".equals(value.get("sessionUpdate")))
                    .toList();
        }

        @Override
        public void close() throws Exception {
            writer.close();
            done.get(5, TimeUnit.SECONDS);
            tasks.shutdownNow();
            assertTrue(tasks.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, http.closes.get());
            http.close();
            delegate.close();
            support.close();
            bus.close();
            mapper.close();
        }
    }

    private static final class FakeHttp extends ExtendedHttpClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private volatile Consumer<HttpRequest> before = request -> {};
        private volatile boolean fail;

        private FakeHttp(final HttpClient delegate) {
            super(new ExtendedHttpClientConfiguration().setDelegate(delegate));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            calls.incrementAndGet();
            before.accept(request);
            if (fail) {
                throw new IllegalStateException("provider-password-secret");
            }
            final var path = request.uri().getPath();
            final var body = path.endsWith("/deviceauth/usercode")
                    ? "{\"device_auth_id\":\"private-device-id\",\"user_code\":\"USER-CODE\",\"interval\":1}"
                    : path.endsWith("/deviceauth/token")
                            ? "{\"authorization_code\":\"fake-code\",\"code_verifier\":\"private-verifier\"}"
                            : path.endsWith("/oauth/token")
                                    ? "{\"access_token\":\"access-secret\",\"refresh_token\":\"refresh-secret\"}"
                                    : path.endsWith("/v1/agent/register")
                                            ? "{\"agent_runtime_id\":\"runtime\"}"
                                            : path.endsWith("/task/register") ? "{\"task_id\":\"task\"}" : null;
            assertNotNull(body, "Unexpected fake HTTP request");
            return (HttpResponse<T>) new Response(request, body);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            super.close();
        }
    }

    private record Response(HttpRequest request, String body) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("content-type", List.of("application/json")), (k, v) -> true);
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

    @Test
    @Timeout(45)
    void spawnedLoginOnlyFailureExitsNonzeroWithoutProtocolOutput(@TempDir final Path root) throws Exception {
        final var stdout = root.resolve("stdout");
        final var stderr = root.resolve("stderr");
        final var command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + root,
                "-cp",
                System.getProperty("java.class.path"),
                CliLauncher.class.getName(),
                "acp",
                "--login",
                "true",
                "--yca-home",
                root.toString(),
                "--yca-storageHome",
                root.resolve("storage").toString(),
                "--yca-workingDir",
                root.toString(),
                "--yca-projectDir",
                root.toString(),
                "--yca-mavenRepositoryKnowledge-enabled",
                "false",
                "--yca-structuralCodeGraph-enabled",
                "false",
                "--yca-provider-api",
                "openai",
                "--yca-provider-apiKey",
                "offline-private-marker");
        final var process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        try {
            process.getOutputStream()
                    .write(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}\n"
                                    .getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            // Include cold container startup in the budget, as in the other spawned CLI tests.
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), () -> {
                try {
                    return "Login-only process did not exit; stdout:\n" + Files.readString(stdout) + "\nstderr:\n"
                            + Files.readString(stderr);
                } catch (final IOException failure) {
                    return "Login-only process did not exit; could not read captured output: " + failure;
                }
            });
            assertNotEquals(0, process.exitValue());
            assertEquals("", Files.readString(stdout));
            final var diagnostic = Files.readString(stderr);
            assertTrue(
                    diagnostic.contains("Terminal login requires an existing configured Codex provider"), diagnostic);
            assertFalse(diagnostic.contains("offline-private-marker"));
            assertFalse(diagnostic.contains("\"jsonrpc\""));
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    @Nested
    @FusionSupport(modules = CancelledProviderHookCannot.Module.class)
    class CancelledProviderHookCannot {
        @TempDir
        static Path root;

        @Test
        void cancelledProviderHookCannotPublishSettingsMetadataOrStartAuth(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var hook = new Block();
            try (final var f = new Fixture(configuration, commandOptions, root, Map.of(), ChatSettings.EMPTY)) {
                final var id = f.create(root);
                final var original = f.runtimes.get(id).chatSettings();
                final var metadata = f.store.metadata(id);
                f.acceptSettings = settings -> {
                    hook.await();
                    return true;
                };
                f.prompt("switch", id, "/provider default");
                hook.entered.await();
                f.send(null, "session/cancel", Map.of("sessionId", id));
                f.barrier();
                hook.release.countDown();
                assertEquals(
                        Map.of("stopReason", "cancelled"), f.response("switch").get("result"));
                assertEquals(original, f.runtimes.get(id).chatSettings());
                assertEquals(metadata, f.store.metadata(id));
                assertTrue(f.attempts.isEmpty());
                assertFalse(f.allOutput().contains("config_option_update"));
                f.acceptSettings = settings -> true;
                f.beforeAttemptReturn = attempt -> attempt.cancel();
                f.prompt("completed", id, "/provider default");
                assertNotNull(f.response("completed").get("error"));
                f.send(null, "session/cancel", Map.of("sessionId", id));
                f.barrier();
                assertEquals("default", f.runtimes.get(id).chatSettings().providerId());
                assertEquals("default", f.store.metadata(id).provider());
            } finally {
                hook.release.countDown();
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = NormalFusionCompactionDelivery.Module.class)
    class NormalFusionCompactionDelivery {
        @TempDir
        static Path root;

        @Test
        void normalFusionCompactionDeliveryIncludesIdleManualChildrenAndCancelledDrain(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            try (final var f = new Fixture(
                    configuration,
                    commandOptions,
                    root,
                    Map.of("session", Map.of("compaction", Map.of())),
                    ChatSettings.EMPTY)) {
                final var a = f.create(root);
                final var b = f.create(Files.createDirectory(root.resolve("b")));
                final var coordinator = new CompactionCoordinator(
                        f.support.configuration,
                        f.support.chat,
                        null,
                        f.support.providers,
                        f.bus,
                        a,
                        new BackoffSleeper() {
                            @Override
                            public boolean sleep(final long millis) {
                                return true;
                            }
                        },
                        root);
                coordinator.compact(List.of(Message.user("small")), true, null, 0);
                final var idle = f.compactions(a);
                assertEquals(
                        List.of("in_progress", "completed"),
                        idle.stream().map(it -> it.get("status")).toList());
                assertEquals(
                        "SKIPPED",
                        object(object(idle.getLast().get("_meta")).get("yca")).get("outcome"));
                assertTrue(f.compactions(b).isEmpty());
                for (final var outcome : List.of(
                        AgentEvent.CompactionPass.Status.SUCCEEDED,
                        AgentEvent.CompactionPass.Status.FAILED,
                        AgentEvent.CompactionPass.Status.SKIPPED)) {
                    final var pass = "child-" + outcome;
                    f.bus.emit(new CompactionPass(
                            pass, AgentEvent.CompactionPass.Status.STARTED, null, null, a, "child", "Worker", root));
                    final var terminal =
                            new CompactionPass(pass, outcome, null, "safe outcome", a, "child", "Worker", root);
                    f.bus.emit(terminal);
                    f.bus.emit(terminal);
                    assertEquals(
                            2,
                            f.compactions(a).stream()
                                    .filter(it -> pass.equals(it.get("compactionId")))
                                    .count());
                }
                final var work = new CompletableFuture<Boolean>();
                final var admitted = new CountDownLatch(1);
                f.turnWork = request -> {
                    admitted.countDown();
                    return work;
                };
                f.prompt("turn", a, "work");
                admitted.await();
                f.bus.emit(new CompactionPass(
                        "cancel-pass", AgentEvent.CompactionPass.Status.STARTED, null, null, a, null, null, root));
                f.send(null, "session/cancel", Map.of("sessionId", a));
                f.barrier();
                f.send("close", "session/close", Map.of("sessionId", a));
                f.barrier();
                assertEquals(0, f.disposals.get(), "cancellation and close must wait for the actual operation");
                assertFalse(f.hasResponse("turn"));
                f.bus.emit(new CompactionPass(
                        "cancel-pass", AgentEvent.CompactionPass.Status.CANCELLED, null, null, a, null, null, root));
                assertEquals(
                        List.of("in_progress", "cancelled"),
                        f.compactions(a).stream()
                                .filter(it -> "cancel-pass".equals(it.get("compactionId")))
                                .map(it -> it.get("status"))
                                .toList());
                assertFalse(f.hasResponse("turn"), "terminal projection precedes prompt completion/endTurn");
                assertEquals(0, f.disposals.get(), "terminal projection precedes runtime disposal");
                work.complete(true);
                assertEquals(
                        Map.of("stopReason", "cancelled"), f.response("turn").get("result"));
                assertEquals(
                        List.of("in_progress", "cancelled"),
                        f.compactions(a).stream()
                                .filter(it -> "cancel-pass".equals(it.get("compactionId")))
                                .map(it -> it.get("status"))
                                .toList());
                assertNull(f.response("close").get("error"));
                final var count = f.compactions(a).size();
                f.bus.emit(new CompactionPass(
                        "late", AgentEvent.CompactionPass.Status.STARTED, null, null, a, null, null, root));
                assertEquals(count, f.compactions(a).size());
                assertTrue(f.compactions(b).isEmpty());
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = IdleAndChildCompaction.Module.class)
    class IdleAndChildCompaction {
        @TempDir
        static Path root;

        @Test
        void idleAndChildCompactionCancellationIsProjectedBeforeDisposal(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            try (final var f = new Fixture(
                    configuration,
                    commandOptions,
                    root,
                    Map.of("session", Map.of("compaction", Map.of())),
                    ChatSettings.EMPTY)) {
                final var id = f.create(root);
                for (final var child : List.of("main", "child")) {
                    f.bus.emit(new CompactionPass(
                            child,
                            AgentEvent.CompactionPass.Status.STARTED,
                            null,
                            null,
                            id,
                            "main".equals(child) ? null : child,
                            null,
                            root));
                }
                final var beforeDisposal = new AtomicReference<List<Map<String, Object>>>();
                f.beforeDispose = disposed -> beforeDisposal.set(f.compactions(disposed));
                f.send("close", "session/close", Map.of("sessionId", id));
                assertNull(f.response("close").get("error"));
                assertEquals(1, f.disposals.get());
                final var projected = beforeDisposal.get();
                assertNotNull(projected);
                for (final var pass : List.of("main", "child")) {
                    final var updates = projected.stream()
                            .filter(it -> pass.equals(it.get("compactionId")))
                            .toList();
                    assertEquals(
                            List.of("in_progress", "cancelled"),
                            updates.stream().map(it -> it.get("status")).toList());
                    assertEquals(
                            "CANCELLED",
                            object(object(updates.getLast().get("_meta")).get("yca"))
                                    .get("outcome"));
                }
                assertEquals(projected, f.compactions(id), "all terminal updates were sent before runtime.close");
                f.bus.emit(new CompactionPass(
                        "late", AgentEvent.CompactionPass.Status.STARTED, null, null, id, null, null, root));
                assertEquals(projected, f.compactions(id), "disposed sessions cannot resume event delivery");
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = NormalFusionFileEvents.Module.class)
    class NormalFusionFileEvents {
        @TempDir
        static Path root;

        @Test
        void normalFusionFileEventsKeepCanonicalIdExactDiffAndFinalReplacement(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            try (final var f = new Fixture(configuration, commandOptions, root, Map.of(), ChatSettings.EMPTY)) {
                final var a = f.create(root);
                final var b = f.create(Files.createDirectory(root.resolve("b")));
                final var work = new CompletableFuture<Boolean>();
                final var admitted = new CountDownLatch(1);
                f.turnWork = request -> {
                    admitted.countDown();
                    return work;
                };
                f.prompt("turn", a, "work");
                admitted.await();
                f.prompt("visibility", b, "/tool-output shown");
                f.response("visibility");
                // Set A's display policy directly through its normal shell command before starting tools.
                f.shells.active(a).frontend().showToolOutput(ToolOutputVisibility.SHOWN);
                final var path = root.resolve("file.txt");
                Files.writeString(path, "before\r\n");
                final var result = new EditFileTool()
                        .execute(
                                Map.of("path", path.toString(), "oldString", "before", "newString", "after"),
                                ToolContext.empty(root, new DisabledSandbox(root)));
                assertTrue(result.success());
                f.bus.emit(new ToolStarted("edit_file", "{}", -1, a, "child", "Worker", root));
                f.bus.emit(new ToolFilesChanged("edit_file", result.fileMutations(), -1, a, "child", "Worker", root));
                f.bus.emit(new ToolStream("edit_file", "preview", -1, a, "child", "Worker", root));
                f.bus.emit(new ToolFinished("edit_file", "final text", true, false, -1, a, "child", "Worker", root));
                final var updates = f.sessionUpdates(a).stream()
                        .filter(it -> it.get("toolCallId") != null)
                        .toList();
                assertEquals(4, updates.size());
                assertEquals(
                        1,
                        updates.stream()
                                .map(it -> it.get("toolCallId"))
                                .distinct()
                                .count());
                final var content = (List<?>) updates.getLast().get("content");
                final var diff = content.stream()
                        .map(AcpOwnedWorkIntegrationTest::object)
                        .filter(it -> "diff".equals(it.get("type")))
                        .findFirst()
                        .orElseThrow();
                assertEquals("before\r\n", diff.get("oldText"));
                assertEquals("after\r\n", diff.get("newText"));
                assertTrue(f.mapper.toString(content).contains("final text"));
                assertFalse(f.mapper.toString(content).contains("preview"));
                assertTrue(f.sessionUpdates(b).stream().noneMatch(it -> it.get("toolCallId") != null));
                final var unknown = new WriteFileTool()
                        .execute(
                                Map.of("path", path.toString(), "content", "unread replacement"),
                                ToolContext.empty(root, new DisabledSandbox(root)));
                f.bus.emit(new ToolStarted("write_file", "{}", 2, a, null, null, root));
                f.bus.emit(new ToolFilesChanged("write_file", unknown.fileMutations(), 2, a, null, null, root));
                f.bus.emit(new ToolFinished("write_file", "written", true, false, 2, a, null, null, root));
                final var last = f.sessionUpdates(a).getLast();
                assertFalse(f.mapper.toString(last).contains("oldText"));
                assertTrue(f.mapper.toString(last).contains(path.toString()));
                assertEquals("completed", last.get("status"));
                assertFalse(f.hasResponse("turn"), "file mutations and final replacement precede prompt completion");
                work.complete(true);
                assertNull(f.response("turn").get("error"));
                assertEquals(
                        7,
                        f.sessionUpdates(a).stream()
                                .filter(it -> it.get("toolCallId") != null)
                                .count(),
                        "endTurn must not replace already-completed tools with missing-result failures");
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = DeviceLoginKeepsPrompt.Module.class)
    class DeviceLoginKeepsPrompt {
        @TempDir
        static Path root;

        @Test
        void deviceLoginKeepsPromptAliveUntilCommitAndNeverAddsSecretsToHistory(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            try (final var f = new Fixture(configuration, commandOptions, root, Map.of(), ChatSettings.EMPTY)) {
                final var block = new Block();
                f.http.before = request -> {
                    if (request.uri().getPath().endsWith("/deviceauth/token")) {
                        block.await();
                    }
                };
                final var a = f.create(root);
                final var b = f.create(root);
                f.prompt("login", a, "/codex login\tdefault  --device");
                block.entered.await();
                assertFalse(f.hasResponse("login"));
                final var challenge = f.updateContaining("USER-CODE");
                assertEquals(a, object(challenge.get("params")).get("sessionId"));
                assertFalse(f.mapper.toString(challenge).contains("private-device-id"));
                f.prompt("other", b, "ordinary question");
                assertNull(f.response("other").get("error"), "another session is independent");
                block.release.countDown();
                assertNull(f.response("login").get("error"));
                final var credentials = root.resolve("storage/providers/default/codex.auth.json");
                assertTrue(Files.exists(credentials));
                assertEquals(
                        "access-secret",
                        f.mapper
                                .fromString(CodexTokenStore.class, Files.readString(credentials))
                                .accessToken());
                assertEquals(
                        CodexLoginService.LoginState.SUCCEEDED,
                        f.attempts.getFirst().state());
                assertTrue(f.attempts
                        .getFirst()
                        .completion()
                        .toCompletableFuture()
                        .join()
                        .credentialsCommitted());
                assertTrue(f.attempts.getFirst().drained().toCompletableFuture().isDone());
                assertFalse(f.mapper.toString(f.store.load(a)).contains("USER-CODE"));
                assertFalse(f.mapper.toString(f.store.load(a)).contains("Authorize"));
                assertFalse(f.allOutput().contains("access-secret"));
                assertFalse(f.allOutput().contains("refresh-secret"));
                assertEquals(0, f.http.closes.get());
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = CancellationReachesActualLogin.Module.class)
    class CancellationReachesActualLogin {
        @TempDir
        static Path root;

        @Test
        void cancellationReachesActualLoginButPromptAndCloseWaitForDrain(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var block = new Block();
            try (final var f = new Fixture(configuration, commandOptions, root, Map.of(), ChatSettings.EMPTY)) {
                f.http.before = request -> {
                    if (request.uri().getPath().endsWith("/deviceauth/token")) {
                        block.await();
                    }
                };
                final var a = f.create(root);
                f.prompt("login", a, "/codex login default --device --enterprise --workspace org");
                block.entered.await();
                assertEquals(
                        new CodexLoginService.LoginRequest("default", true, true, "org"), f.loginRequests.getFirst());
                f.send(null, "session/cancel", Map.of("sessionId", a));
                block.interrupted.await();
                f.barrier();
                assertEquals(
                        CodexLoginService.LoginState.CANCELLED,
                        f.attempts.getFirst().state());
                assertFalse(
                        f.attempts.getFirst().drained().toCompletableFuture().isDone());
                assertFalse(f.hasResponse("login"));
                f.send("close", "session/close", Map.of("sessionId", a));
                f.barrier();
                assertEquals(0, f.disposals.get());
                block.release.countDown();
                assertEquals(
                        Map.of("stopReason", "cancelled"), f.response("login").get("result"));
                assertNull(f.response("close").get("error"));
                assertEquals(1, f.disposals.get());
                assertFalse(Files.exists(root.resolve("storage/providers/default/codex.auth.json")));
                assertFalse(f.allOutput().contains("access-secret"));
            } finally {
                block.release.countDown();
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = LoginFailureIsSafe.Module.class)
    class LoginFailureIsSafe {
        @TempDir
        static Path root;

        @Test
        void loginFailureIsSafeAndMalformedRequestsNeverStartWork(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            try (final var f = new Fixture(configuration, commandOptions, root, Map.of(), ChatSettings.EMPTY)) {
                final var id = f.create(root);
                for (final var args : List.of(
                        "login default --workspace",
                        "login default --workspace org",
                        "login a --device",
                        "login default --unknown")) {
                    f.prompt("invalid", id, "/codex " + args);
                    f.response("invalid");
                }
                assertTrue(f.attempts.isEmpty());
                f.http.fail = true;
                f.prompt("failed", id, "/codex login default --device");
                assertNotNull(f.response("failed").get("error"));
                assertFalse(f.allOutput().contains("provider-password-secret"));
                assertFalse(f.allOutput().contains("access-secret"));
                f.prompt("status", id, "/codex status default");
                assertNull(f.response("status").get("error"));
                f.prompt("logout", id, "/codex logout default");
                assertNotNull(f.response("logout").get("error"));
                assertEquals(1, f.attempts.size());
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = ProviderReadbackAndVeto.Module.class)
    class ProviderReadbackAndVeto {
        @TempDir
        static Path root;

        @Test
        void providerReadbackAndVetoNeverAuthenticateAnUnselectedProvider(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            try (final var f = new Fixture(configuration, commandOptions, root, Map.of(), ChatSettings.EMPTY)) {
                final var id = f.create(root);
                f.acceptSettings = settings -> !"default".equals(settings.providerId());
                f.prompt("veto", id, "/provider default");
                assertNull(f.response("veto").get("error"));
                assertEquals(
                        "a", f.support.options.providerId(f.runtimes.get(id).chatSettings()));
                assertTrue(f.attempts.isEmpty());
                f.prompt("ordinary", id, "/provider a");
                assertNull(f.response("ordinary").get("error"));
                assertTrue(f.attempts.isEmpty(), "ordinary provider selection never starts OAuth");
                f.acceptSettings = settings -> true;
                // Avoid browser callback sockets: capture and cancel before start, while still using the real attempt.
                f.beforeAttemptReturn = attempt -> attempt.cancel();
                f.prompt("codex", id, "/provider default");
                assertNotNull(f.response("codex").get("error"));
                assertEquals(
                        "default",
                        f.support.options.providerId(f.runtimes.get(id).chatSettings()));
                assertEquals(
                        new CodexLoginService.LoginRequest("default", false, false, null), f.loginRequests.getFirst());
                assertEquals(0, f.http.calls.get());
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = TerminalDescriptorIsExplicit.Module.class)
    class TerminalDescriptorIsExplicit {
        @TempDir
        static Path root;

        @Test
        void terminalDescriptorIsExplicitAndLoginOnlyPathNeverStartsJsonServer(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var selected = new ChatSettings("default", null, null, null);
            for (final var capability : List.<Object>of(false, Map.of(), true)) {
                try (final var f = new Fixture(
                        configuration,
                        commandOptions,
                        root,
                        Map.of("auth", Map.of("terminal", capability)),
                        selected)) {
                    final var methods = (List<?>) f.initialized.get("authMethods");
                    assertEquals(Boolean.TRUE.equals(capability) ? 1 : 0, methods.size());
                    if (!methods.isEmpty()) {
                        final var descriptor = object(methods.getFirst());
                        assertEquals("terminal", descriptor.get("type"));
                        assertEquals(List.of("--login", "true"), descriptor.get("args"));
                        assertFalse(descriptor.containsKey("command"));
                        assertFalse(descriptor.containsKey("env"));
                    }
                    assertFalse(object(f.initialized.get("agentCapabilities")).containsKey("auth"));
                    final var command = new AcpCommand(
                            new AcpCommand.Conf(
                                    "default",
                                    null,
                                    null,
                                    true,
                                    true,
                                    false,
                                    null,
                                    AcpCommand.DEFAULT_ELICITATION_SENSITIVE_MARKERS,
                                    AcpCommand.DEFAULT_ELICITATION_SENSITIVE_WORDS),
                            null,
                            null,
                            f.support.providers,
                            f.support.chat,
                            f.login,
                            f.tasks);
                    final var protocolBeforeLogin = f.allOutput();
                    command.run(); // null server and stdio make accidental ACP startup fail this test.
                    assertEquals(
                            CodexLoginService.LoginState.SUCCEEDED,
                            f.attempts.getLast().state());
                    assertTrue(f.attempts
                            .getLast()
                            .completion()
                            .toCompletableFuture()
                            .join()
                            .credentialsCommitted());
                    assertTrue(
                            f.attempts.getLast().drained().toCompletableFuture().isDone());
                    f.http.fail = true;
                    final var failure = assertThrows(IllegalStateException.class, command::run);
                    assertEquals("Codex login failed or was cancelled.", failure.getMessage());
                    assertFalse(failure.toString().contains("provider-password-secret"));
                    assertEquals(
                            CodexLoginService.LoginState.FAILED,
                            f.attempts.getLast().state());
                    assertTrue(
                            f.attempts.getLast().drained().toCompletableFuture().isDone());
                    assertEquals(
                            protocolBeforeLogin,
                            f.allOutput(),
                            "login-only success and failure never start protocol output");
                }
            }
            try (final var f = new Fixture(
                    configuration,
                    commandOptions,
                    root,
                    Map.of("auth", Map.of("terminal", true)),
                    ChatSettings.EMPTY)) {
                assertEquals(
                        List.of(), f.initialized.get("authMethods"), "non-Codex invocation has no login descriptor");
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = MavenSuccessFailureAnd.Module.class)
    class MavenSuccessFailureAnd {
        @TempDir
        static Path root;

        @Test
        void mavenSuccessFailureAndActualCancellationHaveOwnedTerminalLifecycle(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var block = new Block();
            try (final var f = new Fixture(configuration, commandOptions, root, Map.of(), ChatSettings.EMPTY)) {
                final var a = f.create(root);
                final var bRoot = Files.createDirectory(root.resolve("b"));
                final var b = f.create(bRoot);
                f.indexWork = () -> {
                    block.await();
                    return "indexed A";
                };
                f.prompt("index", a, "/maven-repository-knowledge refresh g:a:1");
                block.entered.await();
                final var job = f.jobs.getFirst();
                assertEquals(root, job.root());
                assertEquals(f.runtimes.get(a).chatSettings(), job.settings());
                assertFalse(f.hasResponse("index"));
                f.indexWork = () -> "indexed B";
                f.prompt("other", b, "/maven-repository-knowledge refresh g:b:1");
                assertNull(f.response("other").get("error"));
                f.send(null, "session/cancel", Map.of("sessionId", a));
                block.interrupted.await();
                f.barrier();
                assertTrue(job.cancellationRequested());
                assertTrue(job.inFlight());
                assertFalse(job.completion().toCompletableFuture().isDone());
                f.progress.get(a).accept("late-progress-marker");
                f.barrier();
                assertFalse(f.allOutput().contains("late-progress-marker"));
                assertFalse(f.hasResponse("index"));
                block.release.countDown();
                assertEquals(
                        Map.of("stopReason", "cancelled"), f.response("index").get("result"));
                assertFalse(job.inFlight());
                f.indexWork = () -> {
                    throw new IllegalStateException("provider-password-secret");
                };
                f.prompt("failure", a, "/maven-repository-knowledge refresh g:c:1");
                assertNotNull(f.response("failure").get("error"));
                assertFalse(f.allOutput().contains("provider-password-secret"));
            } finally {
                block.release.countDown();
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = CancelledDiscoveryCannotPublish.Module.class)
    class CancelledDiscoveryCannotPublish {
        @TempDir
        static Path root;

        @Test
        void cancelledDiscoveryCannotPublishAnUnownedLateJob(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var discovery = new Block();
            final var work = new Block();
            try (final var f = new Fixture(configuration, commandOptions, root, Map.of(), ChatSettings.EMPTY)) {
                final var id = f.create(root);
                f.discovery = discovery;
                f.indexWork = () -> {
                    work.await();
                    return "late success";
                };
                f.prompt("index", id, "/maven-repository-knowledge refresh");
                discovery.entered.await();
                f.send(null, "session/cancel", Map.of("sessionId", id));
                discovery.interrupted.await();
                f.barrier();
                assertTrue(f.jobs.isEmpty());
                assertFalse(f.hasResponse("index"));
                discovery.release.countDown();
                work.entered.await();
                final var job = f.publishedJobs.take();
                // Reader barrier cannot overtake publication on the worker; wait for actual worker interruption.
                work.interrupted.await();
                assertTrue(job.cancellationRequested());
                assertFalse(job.completion().toCompletableFuture().isDone());
                assertEquals(0, f.disposals.get());
                work.release.countDown();
                assertEquals(
                        Map.of("stopReason", "cancelled"), f.response("index").get("result"));
                assertFalse(f.allOutput().contains("late success"));
            } finally {
                discovery.release.countDown();
                work.release.countDown();
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = PlanEditingUsesNonsecret.Module.class)
    class PlanEditingUsesNonsecret {
        @TempDir
        static Path root;

        @Test
        void planEditingUsesNonsecretConfirmAndReloadsUnsavedClientBufferWithoutDiskWrite(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            try (final var f = new Fixture(
                    configuration,
                    commandOptions,
                    root,
                    Map.of(
                            "elicitation",
                            Map.of("form", Map.of()),
                            "fs",
                            Map.of("readTextFile", true, "writeTextFile", true)),
                    ChatSettings.EMPTY)) {
                final var id = f.create(root);
                f.store.append(id, SessionEntry.of(1, "assistant", "original plan", null, null));
                f.runtimes.get(id).permissions().setMode(AgentMode.PLAN);
                final var path = root.resolve("plan.md");
                Files.writeString(path, "disk must remain unchanged");
                f.prompt("edit", id, "/editplan " + path);
                final var export = f.method("elicitation/create");
                assertEquals("form", object(export.get("params")).get("mode"));
                f.reply(export, Map.of("action", "accept", "content", Map.of("answer", "Yes")));
                final var write = f.method("fs/write_text_file");
                assertEquals(path.toString(), object(write.get("params")).get("path"));
                f.reply(write, Map.of());
                final var reload = f.method("elicitation/create");
                f.reply(reload, Map.of("action", "accept", "content", Map.of("answer", "Yes")));
                final var read = f.method("fs/read_text_file");
                f.reply(read, Map.of("content", "# Plan\n\nunsaved buffer plan\n\n# Todos\n"));
                assertNull(f.response("edit").get("error"));
                assertTrue(f.mapper.toString(f.store.load(id)).contains("unsaved buffer plan"));
                assertEquals("disk must remain unchanged", Files.readString(path));
                assertTrue(f.shells
                        .active(id)
                        .io()
                        .readLine("Password")
                        .toCompletableFuture()
                        .join()
                        .isEmpty());
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = DisconnectedLoginIsCancelled.Module.class)
    class DisconnectedLoginIsCancelled {
        @TempDir
        static Path root;

        @Test
        void disconnectedLoginIsCancelledAndRuntimeIsRetainedUntilActualDrain(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var block = new Block();
            try (final var f = new Fixture(configuration, commandOptions, root, Map.of(), ChatSettings.EMPTY)) {
                f.http.before = request -> {
                    if (request.uri().getPath().endsWith("/deviceauth/token")) {
                        block.await();
                    }
                };
                final var id = f.create(root);
                f.prompt("login", id, "/codex login default --device");
                block.entered.await();
                f.writer.close();
                block.interrupted.await();
                // Executor shutdown can interrupt HTTP before State.cancel reaches the owned attempt.
                final var result =
                        f.attempts.getFirst().completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(CodexLoginService.LoginState.CANCELLED, result.state());
                assertFalse(result.credentialsCommitted());
                assertEquals(0, f.disposals.get());
                assertFalse(
                        f.attempts.getFirst().drained().toCompletableFuture().isDone());
                block.release.countDown();
                f.done.get(5, TimeUnit.SECONDS);
                assertEquals(1, f.disposals.get());
                assertFalse(Files.exists(root.resolve("storage/providers/default/codex.auth.json")));
            } finally {
                block.release.countDown();
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = ExpiredCloseDeadlineDoes.Module.class)
    class ExpiredCloseDeadlineDoes {
        @TempDir
        static Path root;

        @Test
        void expiredCloseDeadlineDoesNotDisposeAnUndrainedIndex(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            final var block = new Block();
            try (final var f = new Fixture(configuration, commandOptions, root, Map.of(), ChatSettings.EMPTY)) {
                f.interruptIndexStartup = true;
                f.indexWork = () -> {
                    block.await();
                    return "must not publish";
                };
                final var id = f.create(root);
                f.prompt("index", id, "/maven-repository-knowledge refresh g:a:1");
                block.entered.await();
                f.send(null, "session/cancel", Map.of("sessionId", id));
                block.interrupted.await();
                final var owner = f.shells.active(id).frontend();
                // Exercise the existing bounded-close path with an already exhausted budget; no real timeout wait.
                final var drain = owner.getClass().getDeclaredMethod("drainAndClose", long.class);
                drain.setAccessible(true);
                drain.invoke(owner, System.nanoTime());
                assertEquals(0, f.disposals.get());
                assertTrue(f.jobs.getFirst().inFlight());
                block.release.countDown();
                f.response("index");
                assertEquals(id, f.disposed.take());
                assertNull(f.shells.active(id));
                assertFalse(f.allOutput().contains("must not publish"));
            } finally {
                block.release.countDown();
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = FailedLiveAuthInstructions.Module.class)
    class FailedLiveAuthInstructions {
        @TempDir
        static Path root;

        @Test
        void failedLiveAuthInstructionsFailSafelyWithoutCredentialPublication(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            try (final var f = new Fixture(configuration, commandOptions, root, Map.of(), ChatSettings.EMPTY)) {
                final var id = f.create(root);
                f.failOutputWhen = "USER-CODE";
                f.prompt("login", id, "/codex login default --device");
                assertNotNull(f.response("login").get("error"));
                assertTrue(f.attempts.getFirst().drained().toCompletableFuture().isDone());
                assertFalse(Files.exists(root.resolve("storage/providers/default/codex.auth.json")));
                assertFalse(f.allOutput().contains("provider-password-secret"));
                assertEquals(0, f.http.closes.get());
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    @Nested
    @FusionSupport(modules = ToolAndMcpInspection.Module.class)
    class ToolAndMcpInspection {
        @TempDir
        static Path root;

        @Test
        void toolAndMcpInspectionUseOnlyTheOwningOverlayAndNeverConnectionValues(
                @Fusion final CodingAgentConfiguration configuration, @Fusion final AcpCommand.Conf commandOptions)
                throws Exception {
            try (final var f = new Fixture(configuration, commandOptions, root, Map.of(), ChatSettings.EMPTY)) {
                f.sessionTools = (id, registry) -> registry.withTools(List.of(new EmbeddedTool() {
                    @Override
                    public String name() {
                        return "only_" + id;
                    }

                    @Override
                    public String description(final Path cwd) {
                        return "https://private.example/secretHeaderMarker";
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.MCP;
                    }

                    @Override
                    public boolean readonly() {
                        return true;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of("password", "secretHeaderMarker");
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        throw new AssertionError("inspection must not execute tools");
                    }
                }));
                final var a = f.create(root);
                final var b = f.create(root);
                f.prompt("tools", a, "/tools");
                final var aUpdate = f.updateContaining("only_" + a);
                assertEquals(a, object(aUpdate.get("params")).get("sessionId"));
                assertFalse(f.mapper.toString(aUpdate).contains("only_" + b));
                assertNull(f.response("tools").get("error"));
                f.prompt("mcp", b, "/mcp status");
                final var bUpdate = f.updateContaining("only_" + b);
                assertEquals(b, object(bUpdate.get("params")).get("sessionId"));
                assertFalse(f.mapper.toString(bUpdate).contains("only_" + a));
                assertNull(f.response("mcp").get("error"));
                assertFalse(f.allOutput().contains("secretHeaderMarker"));
                assertFalse(f.allOutput().contains("private.example"));
            }
        }

        public static class Module extends ConfigurationModule {
            public Module() {
                super(root);
            }
        }
    }

    public abstract static class ConfigurationModule extends OptionConfigurationTestModule {
        protected ConfigurationModule(final Path root) {
            super(Map.of(
                    "yca.storageHome",
                    root.resolve("storage").toString(),
                    "yca.workingDir",
                    root.toString(),
                    "yca.projectDir",
                    root.toString(),
                    "yca.provider.providers.1.api",
                    "codex",
                    "yca.provider.providers.1.baseUrl",
                    "https://chatgpt.com/backend-api/codex"));
        }
    }
}
