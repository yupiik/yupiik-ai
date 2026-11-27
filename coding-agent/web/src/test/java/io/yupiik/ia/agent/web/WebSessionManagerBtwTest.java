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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.sandbox.Sandbox;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the web /btw lifecycle in {@link WebSessionManager}: async non-blocking dispatch,
 * the latest-wins single slot (a newer /btw cancels an in-flight one), the running-then-answer/error
 * {@code btw} SSE broadcasts, {@code clearBtw} and the snapshot re-seed of a live btw.
 */
@ExtendWith(TestWebJsonMapper.class)
@FusionSupport(modules = WebSessionManagerBtwTest.SessionModule.class)
class WebSessionManagerBtwTest {

    @TempDir
    static Path storageHome;

    public static class SessionModule extends WebConfigurationTestModule {
        public SessionModule() {
            super(Map.of("yca.storageHome", storageHome.toString(), "yca.workingDir", storageHome.toString()));
        }
    }

    private JsonMapper mapper;
    private WebSessionManager manager;
    private FakeSseBroker broker;
    private FakeBtwAnswerer answerer;
    private SessionStore sessionStore;

    @BeforeEach
    void setup(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
        this.mapper = mapper;
        this.broker = new FakeSseBroker(mapper);
        this.answerer = new FakeBtwAnswerer();

        sessionStore = new InMemorySessionStore(Clock.systemUTC());
        final var runtimeFactory = new SessionRuntimeFactory(
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
            try {
                if (answerer != null) {
                    answerer.pending.values().forEach(future -> future.cancel(false));
                    answerer.pending.clear();
                    answerer.cancelled.clear();
                    answerer.requests.clear();
                }
            } finally {
                if (broker != null) {
                    broker.close();
                }
            }
        }
    }

    @Test
    void btwBroadcastsRunningThenAnswerWithoutBlocking() throws Exception {
        manager.attach("s1");

        // dispatch does not block: the RPC future completes immediately, before the answer resolves
        final var future = manager.slash("s1", "/btw what is xyz?");
        assertTrue(
                future.isDone() && Boolean.TRUE.equals(future.get(5, TimeUnit.SECONDS)),
                "slash must return without blocking");

        // a running frame was broadcast first, carrying the question
        final var runningFrame = broker.frames.poll();
        assertTrue(runningFrame.contains("\"type\":\"btw\""), runningFrame);
        assertTrue(runningFrame.contains("\"running\":true"), runningFrame);
        assertTrue(runningFrame.contains("\"question\":\"what is xyz?\""), runningFrame);

        // complete the answer; the manager then broadcasts the final btw frame with the answer
        answerer.complete("what is xyz?", "**the answer**");
        final var doneFrame = broker.awaitFrame();
        assertTrue(doneFrame.contains("\"running\":false"), doneFrame);
        assertTrue(doneFrame.contains("\"answer\":\"**the answer**\""), doneFrame);

        // the session's btw slot is now settled as answered
        final var state = manager.btwState("s1");
        assertEquals("what is xyz?", state.question());
        assertEquals("**the answer**", state.answer());
        assertFalse(state.running());
    }

    @Test
    void btwKeepsEachSessionsRuntimeRootSettingsAndCancellationAcrossABA() throws Exception {
        final var first = Files.createDirectories(storageHome.resolve("first"));
        final var second = Files.createDirectories(storageHome.resolve("second"));
        sessionStore.markProject("a", first.toString());
        sessionStore.markProject("b", second.toString());
        final var a = manager.runtime("a");
        final var b = manager.runtime("b");
        final var aSettings = new ChatSettings("a", "model-a", null, null);
        final var bSettings = new ChatSettings("b", "model-b", null, null);
        a.updateChatSettings(aSettings);
        b.updateChatSettings(bSettings);
        sessionStore.replaceProject("a", second.toString());

        manager.slash("a", "/btw first-a").join();
        manager.slash("b", "/btw first-b").join();
        manager.slash("a", "/btw second-a").join();

        assertEquals(first, answerer.requests.get("first-a").workdir());
        assertEquals(second, answerer.requests.get("first-b").workdir());
        assertEquals(first, answerer.requests.get("second-a").workdir());
        assertEquals(aSettings, answerer.requests.get("first-a").settings());
        assertEquals(bSettings, answerer.requests.get("first-b").settings());
        assertEquals(aSettings, answerer.requests.get("second-a").settings());
        assertSame(a.usage(), answerer.requests.get("second-a").usage());
        assertSame(a.systemPromptBuilder(), answerer.requests.get("second-a").prompt());
        assertSame(b.usage(), answerer.requests.get("first-b").usage());
        assertSame(
                a.loop().effectiveToolRegistry(),
                answerer.requests.get("second-a").registry());
        assertSame(
                b.loop().effectiveToolRegistry(),
                answerer.requests.get("first-b").registry());
        assertEquals(
                a.loop().availableToolNames(), answerer.requests.get("second-a").ceiling());
        assertEquals(
                b.loop().availableToolNames(), answerer.requests.get("first-b").ceiling());
        assertTrue(answerer.cancelledSupplier("first-a").getAsBoolean());
        assertFalse(answerer.cancelledSupplier("first-b").getAsBoolean());
        assertFalse(answerer.cancelledSupplier("second-a").getAsBoolean());
    }

    @Test
    void btwWithNoQuestionReportsUsageAndDoesNotBroadcast() throws Exception {
        manager.attach("s1");
        final var future = manager.slash("s1", "/btw");
        assertEquals(Boolean.FALSE, future.get(5, TimeUnit.SECONDS));
        assertEquals(0, broker.frames.size());
        assertNull(manager.btwState("s1"));
    }

    @Test
    void btwRequiresAnExactCommandNameAndIsCaseInsensitive() throws Exception {
        manager.attach("s1");
        manager.slash("s1", "/btw-other not a side question").get(5, TimeUnit.SECONDS);
        assertNull(manager.btwState("s1"));
        assertTrue(answerer.pending.isEmpty());
        assertTrue(broker.frames.stream().noneMatch(frame -> frame.contains("\"type\":\"btw\"")));
        manager.slash("s1", "/BTW a side question").get(5, TimeUnit.SECONDS);
        assertEquals("a side question", manager.btwState("s1").question());
        answerer.complete("a side question", "answer");
    }

    @Test
    void aNewerBtwCancelsAnInflightOneAndIsNotOverwrittenByTheStaleAnswer() throws Exception {
        manager.attach("s1");
        manager.slash("s1", "/btw first question").get(5, TimeUnit.SECONDS);
        broker.frames.poll(); // first running frame

        // second /btw overrides: the first answerer loop is cancelled
        final var second = manager.slash("s1", "/btw second question");
        assertEquals(Boolean.TRUE, second.get(5, TimeUnit.SECONDS));
        assertTrue(answerer.cancelledSupplier("first question").getAsBoolean(), "first btw must be cancelled");
        broker.frames.poll(); // discard the "second question" running frame

        // the runner received both questions
        assertTrue(answerer.pending.containsKey("second question"));

        // the stale first answer must NOT win: cross the guard by a delayed completion
        answerer.complete("first question", "stale first answer");
        answerer.complete("second question", "fresh second answer");
        final var doneFrame = broker.awaitFrame();
        assertTrue(doneFrame.contains("\"question\":\"second question\""), doneFrame);
        assertTrue(doneFrame.contains("\"answer\":\"fresh second answer\""), doneFrame);

        final var state = manager.btwState("s1");
        assertEquals("second question", state.question());
        assertEquals("fresh second answer", state.answer());
    }

    @Test
    void aStaleOverrideAnswerNeverOverwritesTheNewerSlot() throws Exception {
        manager.attach("s1");
        manager.slash("s1", "/btw q1").get(5, TimeUnit.SECONDS);
        broker.frames.poll();
        manager.slash("s1", "/btw q2").get(5, TimeUnit.SECONDS);
        broker.frames.poll();

        // q1 completes AFTER q2 already settled -> its completion must be discarded
        answerer.complete("q2", "q2 answer");
        broker.awaitFrame();
        answerer.complete("q1", "q1 stale");
        // no extra frame and the slot still shows q2
        assertEquals(0, broker.frames.size(), "stale completion must not broadcast");
        final var state = manager.btwState("s1");
        assertEquals("q2 answer", state.answer());
    }

    @Test
    void clearBtwCancelsTheInflightAnswerAndClearsTheSlot() throws Exception {
        manager.attach("s1");
        manager.slash("s1", "/btw question").get(5, TimeUnit.SECONDS);
        assertNotNull(answerer.cancelledSupplier("question"));
        answerer.complete("question", "an answer");
        broker.awaitFrame();

        manager.clearBtw("s1");
        assertNull(manager.btwState("s1"), "clear must remove the slot");
    }

    @Test
    void btwErrorIsBroadcastAndHeldInTheSlot() throws Exception {
        manager.attach("s1");
        manager.slash("s1", "/btw why?").get(5, TimeUnit.SECONDS);
        broker.frames.poll();
        answerer.fail("why?", new IllegalStateException("boom"));
        final var doneFrame = broker.awaitFrame();
        assertTrue(doneFrame.contains("\"running\":false"), doneFrame);
        assertTrue(doneFrame.contains("\"error\":\"boom\""), doneFrame);
        final var state = manager.btwState("s1");
        assertEquals("boom", state.error());
    }

    @Test
    void retryableAnswerIsBroadcastAndHeldInTheSlot() throws Exception {
        manager.attach("s1");
        manager.slash("s1", "/btw retry me").get(5, TimeUnit.SECONDS);
        broker.frames.poll();
        answerer.completeRetryable("retry me", "could not answer, retry");
        final var doneFrame = broker.awaitFrame();
        assertTrue(doneFrame.contains("\"running\":false"), doneFrame);
        assertTrue(doneFrame.contains("\"answer\":\"could not answer, retry\""), doneFrame);
        assertTrue(doneFrame.contains("\"retryable\":true"), doneFrame);
        final var state = manager.btwState("s1");
        assertEquals("could not answer, retry", state.answer());
        assertTrue(state.retryable());
    }

    @Test
    void snapshotForReseedsALiveAnswer() throws Exception {
        manager.attach("s1");
        manager.slash("s1", "/btw reseed me").get(5, TimeUnit.SECONDS);
        // let it settle
        answerer.complete("reseed me", "persisted answer");
        broker.awaitFrame();

        final var events = manager.snapshotFor("s1");
        final var btwEvent =
                events.stream().filter(e -> "btw".equals(e.type())).findFirst().orElse(null);
        assertNotNull(btwEvent, "snapshot must carry the btw event");
        assertEquals("reseed me", btwEvent.message());
        assertEquals("persisted answer", btwEvent.data().get("answer"));
    }

    @Test
    void snapshotPreservesCumulativeExplicitFullInputWithoutNarrowing() {
        manager.attach("s1");
        final var usage = manager.runtime("s1").usage();
        usage.record(new Usage(100, 25, 125, 400, 0, 0, 0, 500));
        usage.record(new Usage(100, 25, 125, 400, 0, 0, 0, 500));
        var data = manager.snapshotFor("s1").stream()
                .filter(e -> "usage-updated".equals(e.type()))
                .findFirst()
                .orElseThrow()
                .data();
        assertEquals(200, data.get("promptTokens"));
        assertEquals(1000L, data.get("contextInputTokens"));
        assertEquals(800L, data.get("cacheReadTokens"));

        usage.record(new Usage(0, 0, 0, 0, 0, 0, 0, 1_500_000_000));
        usage.record(new Usage(0, 0, 0, 0, 0, 0, 0, 1_500_000_000));
        data = manager.snapshotFor("s1").stream()
                .filter(e -> "usage-updated".equals(e.type()))
                .findFirst()
                .orElseThrow()
                .data();
        assertEquals(3_000_001_000L, data.get("contextInputTokens"));
    }

    @Test
    void snapshotReseedsFullyCachedAndOutputOnlyUsageButNotFreshUsage() {
        manager.attach("s1");
        assertTrue(manager.snapshotFor("s1").stream().noneMatch(e -> "usage-updated".equals(e.type())));
        manager.runtime("s1").usage().record(new Usage(0, 0, 0, 500, 0, 0, 0, 500));
        final var cached = manager.snapshotFor("s1").stream()
                .filter(e -> "usage-updated".equals(e.type()))
                .findFirst()
                .orElseThrow()
                .data();
        assertEquals(0, cached.get("promptTokens"));
        assertEquals(500L, cached.get("contextInputTokens"));
        assertEquals(500L, cached.get("cacheReadTokens"));

        manager.attach("s2");
        manager.runtime("s2").usage().record(new Usage(0, 25, 25));
        final var output = manager.snapshotFor("s2").stream()
                .filter(e -> "usage-updated".equals(e.type()))
                .findFirst()
                .orElseThrow()
                .data();
        assertEquals(0L, output.get("contextInputTokens"));
        assertEquals(25, output.get("completionTokens"));
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

    /** Captures every {@link #broadcast} frame for assertions. */
    private static final class FakeSseBroker extends SseBroker {
        private final Queue<String> frames = new ConcurrentLinkedQueue<>();

        private FakeSseBroker(final JsonMapper mapper) {
            super(mapper);
        }

        @Override
        public void broadcast(final String sessionId, final String frame) {
            frames.add(frame);
        }

        private String awaitFrame() {
            for (int i = 0; i < 100; i++) {
                final var head = frames.poll();
                if (head != null) {
                    return head;
                }
                try {
                    Thread.sleep(20);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new AssertionError("no broadcast frame arrived");
        }
    }

    /** Controllable {@code BtwAnswerer}: returns per-question futures from the rooted request. */
    private static final class FakeBtwAnswerer extends BtwAnswerer {
        private final Map<String, CompletableFuture<BtwAnswerer.BtwAnswer>> pending = new ConcurrentHashMap<>();
        // the real cancellation supplier the manager passed in, keyed by question - so a canceled test
        // reflects the actual override/clear the production code performs on the BtwState it owns.
        private final Map<String, BooleanSupplier> cancelled = new ConcurrentHashMap<>();
        private final Map<String, Request> requests = new ConcurrentHashMap<>();

        private record Request(
                Path workdir,
                ChatSettings settings,
                UsageTracker usage,
                SystemPromptBuilder prompt,
                ToolRegistry registry,
                Set<String> ceiling) {}

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
            this.pending.put(question, new CompletableFuture<>());
            this.cancelled.put(question, cancelled);
            return this.pending.get(question);
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
            requests.put(
                    question,
                    new Request(workdir, sessionSettings, usage, systemPromptBuilder, registry, Set.copyOf(ceiling)));
            return ask(question, sessionId, usage, cancelled, sessionGate, sessionSettings, workdir, sandbox);
        }

        private BooleanSupplier cancelledSupplier(final String question) {
            return cancelled.get(question);
        }

        private void complete(final String question, final String answer) {
            final var future = pending.get(question);
            if (future != null) {
                future.complete(new BtwAnswerer.BtwAnswer(answer, false));
            }
        }

        private void completeRetryable(final String question, final String answer) {
            final var future = pending.get(question);
            if (future != null) {
                future.complete(new BtwAnswerer.BtwAnswer(answer, true));
            }
        }

        private void fail(final String question, final Throwable error) {
            final var future = pending.get(question);
            if (future != null) {
                future.completeExceptionally(error);
            }
        }
    }
}
