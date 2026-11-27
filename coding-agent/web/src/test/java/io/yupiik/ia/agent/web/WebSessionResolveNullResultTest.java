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
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.yupiik.fusion.httpserver.jre.JreHttpServer;
import io.yupiik.fusion.httpserver.jre.JreHttpServerConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.sandbox.Sandbox;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for the {@code session.resolve} cancel path: the frontend answers a pending
 * approval/ask/question with {@code result: null} to cancel, and fusion's {@code @JsonRpcParam}
 * {@code required} flag treats {@code null} as *missing*, so marking {@code result} required rejected
 * the whole call with {@code Missing 'result'} before {@link WebSessionManager#resolve} ever ran. That
 * left the browser's cancel unanswered and the running turn blocked until the RPC timeout watchdog.
 *
 * <p>The cancel ({@code result: null}) must dispatch through the real fusion-generated parameter
 * mapping (which previously compiled with {@code findParameter(...,"result",2,true,...)}) and resolve
 * the pending ask into the documented {@code {"cancelled":true}} answer instead of surfacing a
 * JSON-RPC error.
 */
@ExtendWith(TestWebJsonMapper.class)
@FusionSupport(modules = WebSessionResolveNullResultTest.SessionModule.class)
@Timeout(30)
class WebSessionResolveNullResultTest {
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    @TempDir
    static Path storageHome;

    public static class SessionModule extends WebConfigurationTestModule {
        public SessionModule() {
            super(Map.of("yca.storageHome", storageHome.toString()));
        }
    }

    private JsonMapper mapper;
    private WebSessionManager manager;
    private SessionRuntimeFactory runtimeFactory;
    private FakeSseBroker broker;
    private FakeBtwAnswerer answerer;

    @BeforeEach
    void setup(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
        this.mapper = mapper;
        this.broker = new FakeSseBroker(mapper);
        this.answerer = new FakeBtwAnswerer();

        final var sessionStore = new SessionStore() {};
        runtimeFactory = new SessionRuntimeFactory(
                null,
                null,
                null,
                configuration,
                null,
                sessionStore,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                this.mapper,
                null,
                Clock.systemUTC(),
                new PrecisionDurationMarkerProvider(),
                new BackoffSleeper(),
                null,
                null,
                null, // memory production policy
                null, // quota store
                null, // SecretRedactor
                null); // NotificationService
        this.manager = new WebSessionManager(
                configuration,
                runtimeFactory,
                new NoopShellRegistry(),
                new WebSlashCommands(new SlashRegistry() {}, null, sessionStore),
                new TurnRunner() {},
                sessionStore,
                new SessionLifecycle(sessionStore),
                broker,
                this.mapper,
                answerer);
    }

    @AfterEach
    void cleanup() {
        try {
            if (manager != null) {
                manager.close();
            }
        } finally {
            if (broker != null) {
                broker.close();
            }
        }
    }

    @Test
    void aNullResultCancelResolvesThroughTheGeneratedSessionResolveMapping() throws Exception {
        manager.attach("s1");
        final var frames = new ConcurrentLinkedQueue<String>();
        broker.subscribe("s1", bytes -> frames.add(new String(bytes, StandardCharsets.UTF_8)));

        // a pending ask blocked on awaitRpc, driven from a background thread (mirrors a tool round
        // awaiting the browser's answer).
        final var result = new AtomicReference<Map<String, Object>>();
        final var done = new CompletableFuture<Void>();
        final var asking = Thread.startVirtualThread(() -> {
            try {
                result.set(manager.runtime("s1")
                        .feedback()
                        .ask(List.of(new Question("q1", "Pick?", List.of("a", "b"), false))));
            } finally {
                done.complete(null);
            }
        });

        try {
            for (int i = 0; i < 100; i++) {
                if (frames.stream().anyMatch(f -> f.contains("session.ask"))) {
                    break;
                }
                Thread.sleep(20);
                if (i == 99) {
                    throw new AssertionError("no session.ask frame arrived");
                }
            }

            // capture the request id the frame carries so the resolve targets the actual pending request
            final long requestId = frames.stream()
                    .filter(f -> f.contains("session.ask"))
                    .flatMap(f -> Arrays.stream(LINE_BREAK.split(f)))
                    .filter(l -> l.startsWith("data:") && l.contains("\"id\""))
                    .map(l -> l.substring("data:".length()))
                    .map(l -> (Map<?, ?>) mapper.read(Map.class, new StringReader(l)))
                    .map(m -> ((Number) m.get("id")).longValue())
                    .findFirst()
                    .orElseThrow();

            // the frontend's cancel: session.resolve with result:null. This goes through the real
            // fusion-generated param mapping for resolveRequest - the exact path that used to throw
            // "Missing 'result' parameter." because result was marked required.
            final var webJsonRpc = new WebJsonRpc(
                    null, null, manager, null, null, null, null, null, null, null, null, null, runtimeFactory);
            try (final var bindings = WebBindings.create(webJsonRpc, mapper);
                    final var methods = bindings.lookups(JsonRpcMethod.class)) {
                final var generated = methods.instance().stream()
                        .filter(method -> method.name().equals("session.resolve"))
                        .findFirst()
                        .orElseThrow();
                final Map<String, Object> params = new HashMap<>();
                params.put("id", "s1");
                params.put("requestId", String.valueOf(requestId));
                params.put("result", null); // the browser cancel - null is a valid resolution
                final CompletionStage<?> dispatched = generated.invoke(new JsonRpcMethod.Context(null, params));
                final var response =
                        (Map<?, ?>) dispatched.toCompletableFuture().get(5, TimeUnit.SECONDS);

                done.get(5, TimeUnit.SECONDS);

                // the JSON-RPC call succeeded (no Missing 'result' error) and marked the resolve as handled
                assertEquals(Map.of("resolved", true), response);
                // and the pending ask got the documented cancelled answer instead of spinning forever
                assertNotNull(result.get(), "the ask must have returned (not hang) after the null-result resolve");
                assertEquals(Map.of("cancelled", true), result.get());
            }
        } finally {
            try {
                manager.cancel("s1");
            } finally {
                asking.interrupt();
                asking.join(5_000);
                assertFalse(asking.isAlive(), "asking thread must terminate");
            }
        }
    }

    @Test
    void readLineUsesAnInteractiveRequestAndSupportsEmptyInputAndCancel() throws Exception {
        final var session = manager.attach("s1");
        final var requests = new ConcurrentLinkedQueue<Map<?, ?>>();
        final SseBroker.SseSubscription subscription = bytes -> {
            final var frame = new String(bytes, StandardCharsets.UTF_8);
            if (frame.contains("session.question")) {
                requests.add(mapper.read(
                        Map.class,
                        new StringReader(
                                frame.substring(frame.indexOf("data: ") + 6).strip())));
            }
        };
        broker.subscribe("s1", subscription);
        try {
            final var line = session.env()
                    .io()
                    .readLine("Edit plan, then submit to reload")
                    .toCompletableFuture();
            final var request = requests.remove();
            assertEquals("session.question", request.get("method"));
            final var params = (Map<?, ?>) request.get("params");
            assertNotNull(params.get("questions"));
            assertEquals(false, line.isDone());
            manager.resolve("s1", String.valueOf(request.get("id")), "");
            assertEquals(Optional.of(""), line.get(5, TimeUnit.SECONDS));

            final var cancelled = session.env().io().readLine("Cancel").toCompletableFuture();
            manager.resolve("s1", String.valueOf(requests.remove().get("id")), null);
            assertEquals(Optional.empty(), cancelled.get(5, TimeUnit.SECONDS));
        } finally {
            broker.unsubscribe("s1", subscription);
        }
    }

    @Test
    void pendingQuestionsReplayOnlyToTheirSessionAndDisappearOnResolveOrStop() throws Exception {
        final var first = manager.attach("s1");
        final var second = manager.attach("s2");
        final var firstResult =
                first.env().io().readLine("First pending question").toCompletableFuture();
        final var secondResult =
                second.env().io().readLine("Second pending question").toCompletableFuture();
        final var original = new ConcurrentLinkedQueue<String>();
        final SseBroker.SseSubscription originalTab = bytes -> original.add(new String(bytes, StandardCharsets.UTF_8));
        manager.subscribe("s1", originalTab);
        assertEquals(1, original.size());
        final var request = rpcPayload(original.element());
        final var id = String.valueOf(request.get("id"));
        assertEquals("session.question", request.get("method"));
        assertEquals(false, manager.resolve("s2", id, "wrong session"));
        assertEquals(false, firstResult.isDone());

        final var reconnect = new ConcurrentLinkedQueue<String>();
        final SseBroker.SseSubscription reconnectedTab =
                bytes -> reconnect.add(new String(bytes, StandardCharsets.UTF_8));
        broker.unsubscribe("s1", originalTab);
        manager.subscribe("s1", reconnectedTab);
        assertEquals(List.copyOf(original), List.copyOf(reconnect), "replay must preserve the exact payload and id");
        manager.subscribe("s1", bytes -> assertEquals(original.element(), new String(bytes, StandardCharsets.UTF_8)));
        assertEquals(1, reconnect.size(), "replaying another tab must not broadcast to existing tabs");
        assertEquals(true, manager.resolve("s1", id, "answered"));
        assertEquals(Optional.of("answered"), firstResult.get(5, TimeUnit.SECONDS));
        assertEquals(false, manager.resolve("s1", id, "late answer"));

        final var afterResolve = new ConcurrentLinkedQueue<String>();
        manager.subscribe("s1", bytes -> afterResolve.add(new String(bytes, StandardCharsets.UTF_8)));
        assertEquals(List.of(), List.copyOf(afterResolve));
        final var other = new ConcurrentLinkedQueue<String>();
        manager.subscribe("s2", bytes -> other.add(new String(bytes, StandardCharsets.UTF_8)));
        assertEquals(1, other.size());
        assertEquals(false, other.element().contains("First pending question"));
        manager.cancel("s2");
        assertEquals(Optional.empty(), secondResult.get(5, TimeUnit.SECONDS));
        final var afterCancel = new ConcurrentLinkedQueue<String>();
        manager.subscribe("s2", bytes -> afterCancel.add(new String(bytes, StandardCharsets.UTF_8)));
        assertEquals(List.of(), List.copyOf(afterCancel));
        manager.close();
    }

    @Test
    void pendingApprovalAndAskReplayAfterDisconnectAndAreRemovedOnResolutionOrInterruption() throws Exception {
        final var session = manager.attach("s1");
        final var live = new ConcurrentLinkedQueue<String>();
        final SseBroker.SseSubscription tab = bytes -> live.add(new String(bytes, StandardCharsets.UTF_8));
        manager.subscribe("s1", tab);
        final var approval = new CompletableFuture<ApprovalGate.Result>();
        final var approving = Thread.startVirtualThread(() -> approval.complete(session.env()
                .io()
                .approvalGate()
                .approve(new ApprovalGate.Request(
                        ApprovalGate.Origin.PERMISSION, "Run?", "Detail", "exec", Map.of("command", "pwd")))));
        try {
            final var original = awaitFrame(live);
            broker.unsubscribe("s1", tab);
            final var reconnect = new ConcurrentLinkedQueue<String>();
            manager.subscribe("s1", bytes -> reconnect.add(new String(bytes, StandardCharsets.UTF_8)));
            assertEquals(List.of(original), List.copyOf(reconnect));
            final var request = rpcPayload(original);
            assertEquals("session.approval", request.get("method"));
            assertEquals(true, manager.resolve("s1", String.valueOf(request.get("id")), "allow_once"));
            assertEquals(ApprovalGate.Result.ALLOW_ONCE, approval.get(5, TimeUnit.SECONDS));
        } finally {
            approving.interrupt();
            approving.join(5_000);
            assertFalse(approving.isAlive(), "approving thread must terminate");
        }

        final var answer = new CompletableFuture<Map<String, Object>>();
        final var asking = Thread.startVirtualThread(() -> answer.complete(session.runtime()
                .feedback()
                .ask(List.of(new Question("only", "One question?", List.of("yes", "no"), false)))));
        try {
            final var replay = new ConcurrentLinkedQueue<String>();
            // Subscribe while publication may be in flight: either live or replay must deliver it.
            manager.subscribe("s1", bytes -> replay.add(new String(bytes, StandardCharsets.UTF_8)));
            final var request = rpcPayload(awaitFrame(replay));
            assertEquals("session.ask", request.get("method"));
            assertEquals(
                    List.of(Map.of(
                            "id",
                            "only",
                            "prompt",
                            "One question?",
                            "options",
                            List.of("yes", "no"),
                            "multiple",
                            false)),
                    ((Map<?, ?>) request.get("params")).get("questions"));
            asking.interrupt();
            asking.join(5_000);
            assertFalse(asking.isAlive(), "asking thread must terminate");
            assertEquals(Map.of("cancelled", true), answer.get(5, TimeUnit.SECONDS));
            final var after = new ConcurrentLinkedQueue<String>();
            manager.subscribe("s1", bytes -> after.add(new String(bytes, StandardCharsets.UTF_8)));
            assertEquals(List.of(), List.copyOf(after));
            assertEquals(false, manager.resolve("s1", String.valueOf(request.get("id")), Map.of("only", "yes")));
        } finally {
            asking.interrupt();
            asking.join(5_000);
            assertFalse(asking.isAlive(), "asking thread must terminate");
        }
    }

    @Test
    void closingSessionDropsOutstandingRequests() throws Exception {
        final var session = manager.attach("s1");
        final var pending = session.env().io().readLine("Closing").toCompletableFuture();
        manager.close();
        assertEquals(Optional.empty(), pending.get(5, TimeUnit.SECONDS));
        final var frames = new ConcurrentLinkedQueue<String>();
        manager.subscribe("s1", bytes -> frames.add(new String(bytes, StandardCharsets.UTF_8)));
        assertEquals(List.of(), List.copyOf(frames));
    }

    @Test
    void sseBootstrapReplaysPendingRpcAfterSnapshotOnEveryConnection() throws Exception {
        final var pending = manager.attach("s1").env().io().readLine("Survives reload");
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
                final var client = HttpClient.newHttpClient();
                final var server = new JreHttpServer(
                        new JreHttpServerConfiguration(null, 0, true),
                        List.of(new SseExchangeHandler(broker, manager::snapshotFor, 10, manager::subscribe)))) {
            server.start();
            final var request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + "/api/stream?session=s1"))
                    .GET()
                    .build();
            List<String> original = null;
            try {
                for (int connection = 0; connection < 2; connection++) {
                    final var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                            .get(5, TimeUnit.SECONDS);
                    try (final var body = response.body()) {
                        assertEquals(200, response.statusCode());
                        final var frames =
                                executor.submit(() -> body.limit(6).toList()).get(5, TimeUnit.SECONDS);
                        assertEquals("event: snapshot", frames.get(0));
                        assertEquals("event: rpc", frames.get(3));
                        assertEquals(true, frames.get(4).contains("Survives reload"));
                        if (original == null) {
                            original = frames;
                        } else {
                            assertEquals(original, frames);
                        }
                    }
                }
            } finally {
                broker.close();
            }
        } finally {
            manager.close();
        }
        assertEquals(Optional.empty(), pending.toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void concurrentPublicationAndSubscriptionNeverLosePendingQuestion() throws Exception {
        final var session = manager.attach("s1");
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attempt = 0; attempt < 100; attempt++) {
                final var start = new CountDownLatch(1);
                final var frames = new ConcurrentLinkedQueue<String>();
                final SseBroker.SseSubscription tab = bytes -> frames.add(new String(bytes, StandardCharsets.UTF_8));
                final var publication = executor.submit(() -> {
                    start.await();
                    return session.env().io().readLine("Concurrent");
                });
                final var subscription = executor.submit(() -> {
                    start.await();
                    manager.subscribe("s1", tab);
                    return true;
                });
                start.countDown();
                final var result = publication.get(5, TimeUnit.SECONDS);
                subscription.get(5, TimeUnit.SECONDS);
                assertEquals(false, frames.isEmpty(), "publication must be delivered live or replayed");
                final var request = rpcPayload(frames.element());
                for (final var frame : frames) {
                    assertEquals(request, rpcPayload(frame), "duplicates must retain the same id and payload");
                }
                manager.resolve("s1", String.valueOf(request.get("id")), "ok");
                assertEquals(Optional.of("ok"), result.toCompletableFuture().get(5, TimeUnit.SECONDS));
                broker.unsubscribe("s1", tab);
            }
        } finally {
            manager.close();
        }
    }

    private Map<?, ?> rpcPayload(final String frame) {
        return mapper.read(
                Map.class,
                new StringReader(frame.substring(frame.indexOf("data: ") + 6).strip()));
    }

    private String awaitFrame(final ConcurrentLinkedQueue<String> frames) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (frames.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(false, frames.isEmpty(), "no RPC delivered");
        return frames.element();
    }

    private static final class NoopShellRegistry extends ShellRegistry {
        private NoopShellRegistry() {
            super(null);
        }

        @Override
        public void register(final String sessionId, final ShellEnv env) {
            // no-op: routing is not exercised by these units
        }

        @Override
        public void unregister(final String sessionId, final ShellEnv env) {
            // no-op
        }
    }

    /** Concrete {@code SseBroker} for wiring; rpc frames are captured via a test subscription. */
    private static final class FakeSseBroker extends SseBroker {
        private FakeSseBroker(final JsonMapper mapper) {
            super(mapper);
        }
    }

    /** Controllable {@code BtwAnswerer}: overrides the 6-arg ask to return per-question futures. */
    private static final class FakeBtwAnswerer extends BtwAnswerer {
        private FakeBtwAnswerer() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public CompletionStage<BtwAnswerer.BtwAnswer> ask(
                final String question,
                final String sessionId,
                final UsageTracker usage,
                final BooleanSupplier cancelled,
                final ApprovalGate sessionGate,
                final ChatSettings sessionSettings,
                final Path workdir,
                final Sandbox sandbox) {
            return CompletableFuture.completedFuture(new BtwAnswerer.BtwAnswer("unused", false));
        }

        @Override
        public CompletionStage<BtwAnswerer.BtwAnswer> ask(
                final String question,
                final String sessionId,
                final UsageTracker usage,
                final BooleanSupplier cancelled,
                final ApprovalGate sessionGate,
                final ChatSettings sessionSettings,
                final SystemPromptBuilder systemPromptBuilder,
                final UserFeedbackService feedback,
                final Path workdir,
                final ToolRegistry registry,
                final Set<String> ceiling,
                final Sandbox sandbox) {
            assertNotNull(ceiling);
            return ask(question, sessionId, usage, cancelled, sessionGate, sessionSettings, workdir, sandbox);
        }
    }
}
