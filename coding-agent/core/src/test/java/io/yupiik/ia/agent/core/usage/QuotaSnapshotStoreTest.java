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
package io.yupiik.ia.agent.core.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.CodexAuthTemplateHelper;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.codex.CodexOAuthConfig;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Tests the per-session quota cache: TTL refresh, forced refresh and session isolation. */
@ExtendWith(TestJsonMapper.class)
class QuotaSnapshotStoreTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private String serverUrl;

    @TempDir
    static Path storage;

    private ExtendedHttpClient httpClient;
    private HttpClient delegate;
    private CodingAgentConfiguration configuration;
    private ProviderManager providerManager;
    private CodexAuthTemplateHelper auth;
    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicInteger usedPercent = new AtomicInteger(10);
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    /** When true, the very next /wham/usage request blocks until {@link #releaseFirstCall} is counted down. */
    private volatile boolean holdFirstCall;

    private CountDownLatch releaseFirstCall;

    @BeforeEach
    void start(
            final JsonMapper mapper,
            @Fusion final CodingAgentConfiguration configuration,
            @Fusion final HttpServer server)
            throws IOException {
        this.server = server;
        this.configuration = configuration;
        serverUrl = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/wham/usage", exchange -> {
            fetches.incrementAndGet();
            final int pct = usedPercent.get(); // capture before any hold so each in-flight request keeps its value
            if (holdFirstCall) {
                holdFirstCall = false;
                try {
                    releaseFirstCall.await(
                            30, TimeUnit.SECONDS); // bounded so a test failure never leaks a blocked worker
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(exchange, responseStatus.get(), """
          {"plan_type":"plus",
           "rate_limit":{"primary_window":{"used_percent":%d,"limit_window_seconds":18000,"reset_at":1700000000}},
           "credits":{"has_credits":true,"balance":"10"}}
          """.formatted(pct));
        });
        serverExecutor = Executors.newCachedThreadPool();
        // set the executor BEFORE start(): the default HttpServer executor is single-threaded, which
        // would
        // serialize /wham/usage and deadlock tests that need two in-flight fetches concurrently (a held
        // fetch blocks the second); a cached pool lets the provider-switch test's A/B run in parallel
        server.setExecutor(serverExecutor);
        server.start();
        delegate = HttpClient.newBuilder().build();
        httpClient = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));

        providerManager = new ProviderManager(configuration, mapper);
        auth = new CodexAuthTemplateHelper(mapper, httpClient, configuration);
        auth.setOAuthConfig(new CodexOAuthConfig(serverUrl, "test", "openid", serverUrl + "/device", 1455));
        // seed a valid session
        final Path file = storage.resolve("providers/codex/codex.auth.json");
        Files.createDirectories(file.getParent());
        Files.write(file, mapper.toBytes(new CodexTokenStore("access", "refresh", "acc", farFuture(), "id")));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor = null;
        }
        if (httpClient != null) {
            httpClient.close();
            delegate.shutdownNow();
            delegate.close();
            delegate = null;
            httpClient = null;
        }
    }

    private QuotaSnapshotStore store(final JsonMapper mapper) {
        return new QuotaSnapshotStore(mapper, httpClient, auth, providerManager, Clock.systemUTC());
    }

    /** A store with an injected shared pool: the async (non-blocking, coalesced) refresh path. */
    private QuotaSnapshotStore asyncStore(final JsonMapper mapper, final ExecutorService executor) {
        return new QuotaSnapshotStore(mapper, httpClient, auth, providerManager, Clock.systemUTC(), executor);
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class TtlAndForcedRefresh {
        @Test
        void ttlAndForcedRefresh(final JsonMapper mapper) {
            final var store = store(mapper);
            final ChatSettings settings = ChatSettings.EMPTY.withProviderId("codex");
            store.attach("s1", settings);
            assertEquals(1, fetches.get(), "attach must fetch on provider init");

            // within the TTL, a non-forced snapshot must NOT refetch
            store.snapshot("s1", false);
            assertEquals(1, fetches.get(), "no refetch within TTL");

            // a forced snapshot must bypass the TTL
            usedPercent.set(55);
            final var fresh = store.snapshot("s1", true);
            assertEquals(2, fetches.get(), "forced refresh must fetch");
            assertEquals(55.0, fresh.primaryWindow().usedPercent(), 0.001);
        }
    }

    @Nested
    @FusionSupport(modules = PlainModule.class)
    class UnsupportedProviderHidesTheGauge {
        @Test
        void unsupportedProviderHidesTheGauge(final JsonMapper mapper) {
            // provider without enableUsage -> supported false, and no fetch attempted
            final var cfg = configuration;
            final var pm = new ProviderManager(cfg, mapper);
            final var store = new QuotaSnapshotStore(mapper, httpClient, auth, pm, Clock.systemUTC());
            store.attach("s2", ChatSettings.EMPTY.withProviderId("plain"));
            final var view = store.snapshot("s2", false);
            assertFalse(view.supported(), "a provider without enableUsage is not supported");
            assertEquals(0, fetches.get(), "no quota fetch for an unsupported provider");
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class SessionsAreIsolatedAndCloseReleases {
        @Test
        void sessionsAreIsolatedAndCloseReleases(final JsonMapper mapper) {
            final var store = store(mapper);
            final ChatSettings codex = ChatSettings.EMPTY.withProviderId("codex");
            store.attach("tab-a", codex);
            store.attach("tab-b", codex);
            assertEquals(2, fetches.get(), "each session attaches its own independent quota fetch");

            // closing one session must not affect the other's cached view
            store.close("tab-a");
            final var remaining = store.snapshot("tab-b", false);
            assertTrue(remaining.supported(), "the remaining session keeps its quota");
            assertEquals(2, fetches.get(), "closing a session does not refetch the other");
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class NullOrBlankKeyDoesNotNpeOnCachedSnapshot {
        @Test
        void nullOrBlankKeyDoesNotNpeOnCachedSnapshot(final JsonMapper mapper) {
            // regression: session-store.cachedSnapshot(null) used to NPE inside
            // ConcurrentHashMap.get(null)
            // (surfacing on the web snapshot path as a silently-hung session restore when a session had
            // not
            // run its first turn yet). A null/blank key is simply "no quota yet".
            final var store = store(mapper);
            final var nullView = store.cachedSnapshot(null);
            assertFalse(nullView.supported(), "a null key yields an unsupported/empty view, not an NPE");
            final var blankView = store.cachedSnapshot("   ");
            assertFalse(blankView.supported(), "a blank key yields an unsupported/empty view, not an NPE");
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class NullOrBlankKeyDoesNotNpeOnSnapshotOrRefresh {
        @Test
        void nullOrBlankKeyDoesNotNpeOnSnapshotOrRefresh(final JsonMapper mapper) {
            // regression: a provider switch (attach + pushUsageQuota) issued as the very first command,
            // before the session ran a turn, read the quota with a null currentSessionId and NPE'd here.
            final var store = store(mapper);
            assertFalse(store.snapshot(null, false).supported(), "snapshot(null) must not NPE");
            assertFalse(store.snapshot("   ", true).supported(), "snapshot(blank, forced) must not NPE");
            assertFalse(store.refresh(null).supported(), "refresh(null) must not NPE");
            assertFalse(store.refresh("   ").supported(), "refresh(blank) must not NPE");
        }
    }

    /**
     * The async path must be non-blocking: attach returns immediately (the fetch runs on the executor)
     * and the snapshot becomes available asynchronously.
     */
    @Nested
    @FusionSupport(modules = ClientModule.class)
    class AsyncAttachIsNonBlockingAndPopulates {
        @Test
        void asyncAttachIsNonBlockingAndPopulates(final JsonMapper mapper) throws Exception {
            final var executor = Executors.newSingleThreadExecutor();
            try (executor) {
                final var store = asyncStore(mapper, executor);
                final long t0 = System.nanoTime();
                store.attach("s1", ChatSettings.EMPTY.withProviderId("codex"));
                final long attachNanos = System.nanoTime() - t0;
                // the handler responds immediately here, so just assert attach did not wait long before
                // returning
                assertTrue(
                        attachNanos < TimeUnit.SECONDS.toNanos(5),
                        "attach must not block: took " + attachNanos + " ns");
                awaitFetches(1);
                // the view becomes available once the background fetch lands
                awaitPrimaryWindow(store, "s1");
                assertEquals(10.0, store.snapshot("s1", false).primaryWindow().usedPercent(), 0.001);
                assertEquals(1, fetches.get(), "exactly one fetch for the first attached session");
            }
        }
    }

    /** Concurrent refreshes of one session coalesce into a single backend call, even across a forced /usage. */
    @Nested
    @FusionSupport(modules = ClientModule.class)
    class AsyncRefreshesAreCoalesced {
        @Test
        void asyncRefreshesAreCoalesced(final JsonMapper mapper) throws Exception {
            final var executor = Executors.newSingleThreadExecutor();
            try (executor) {
                final var store = asyncStore(mapper, executor);
                releaseFirstCall = new CountDownLatch(1);
                holdFirstCall = true;
                store.attach("s1", ChatSettings.EMPTY.withProviderId("codex")); // fires fetch #1 (blocked in handler)
                awaitFetches(1);
                // a stale non-forced read while #1 is in flight must coalesce, not start a second fetch
                store.snapshot("s1", false);
                // a forced refresh while #1 is in flight must also coalesce onto it
                final var forced = new AtomicReference<QuotaSnapshotStore.QuotaView>();
                final var forcedThread = new Thread(() -> forced.set(store.refresh("s1")));
                forcedThread.start();
                Thread.sleep(200); // give the forced read time to call scheduleAsync (it should coalesce)
                assertEquals(1, fetches.get(), "refreshes must coalesce onto the single in-flight call");
                releaseFirstCall.countDown(); // let fetch #1 complete
                forcedThread.join(TimeUnit.SECONDS.toMillis(10));
                awaitFetchesAtLeast(1);
                assertNotNull(forced.get(), "forced /usage must complete after the coalesced fetch lands");
                assertEquals(1, fetches.get(), "no extra backend call for a forced refresh that coalesced");
            }
        }
    }

    /**
     * A stale async refresh of a previous provider must not overwrite the snapshot of the newly selected
     * provider: after switching to {@code codex2} while {@code codex}'s fetch is still in flight, the late
     * {@code codex} completion is dropped so the reported quota stays the one {@code codex2} fetched.
     */
    @Nested
    @FusionSupport(modules = SwitchModule.class)
    class StaleAsyncRefreshDoesNotOverwriteSwitchedProvider {
        @Test
        void staleAsyncRefreshDoesNotOverwriteSwitchedProvider(final JsonMapper mapper) throws Exception {
            final var cfg = configuration;
            final var pm = new ProviderManager(cfg, mapper);
            // seed auth for the second codex provider too, so its fetch is allowed (mirrors @BeforeEach
            // for codex)
            final Path second = storage.resolve("providers/codex2/codex.auth.json");
            Files.createDirectories(second.getParent());
            Files.write(second, mapper.toBytes(new CodexTokenStore("access", "refresh", "acc", farFuture(), "id")));

            final var executor = Executors.newFixedThreadPool(2); // A (blocked) and B (fast) run concurrently
            try (executor) {
                final var store = new QuotaSnapshotStore(mapper, httpClient, auth, pm, Clock.systemUTC(), executor);
                releaseFirstCall = new CountDownLatch(1);
                holdFirstCall = true;
                usedPercent.set(50);
                store.attach("s1", ChatSettings.EMPTY.withProviderId("codex")); // A: in flight and blocked
                awaitFetches(1);
                usedPercent.set(99);
                store.attach("s1", ChatSettings.EMPTY.withProviderId("codex2")); // switch -> schedules B
                // B completes fast and publishes its (99) snapshot; wait until it lands
                awaitPrimaryWindow(store, "s1");
                assertEquals(99.0, store.snapshot("s1", false).primaryWindow().usedPercent(), 0.001);
                releaseFirstCall.countDown(); // let the stale A completion land (it must be dropped)
                awaitFetchesAtLeast(2);
                // Wait for both provider tasks, including the stale write, rather than guessing when A
                // finishes.
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "provider refreshes must finish");
                assertEquals(
                        99.0,
                        store.snapshot("s1", false).primaryWindow().usedPercent(),
                        0.001,
                        "the stale in-flight refresh of the previous provider must not overwrite the new"
                                + " provider's quota");
            }
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class ActivePollingAndOneFinalRefresh {
        @Test
        void activePollingAndOneFinalRefresh(final JsonMapper mapper) {
            final var clock = new MutableClock();
            try (final var worker = new ControlledExecutor();
                    final var scheduler = new CapturedScheduler()) {
                final var store =
                        new QuotaSnapshotStore(mapper, httpClient, auth, providerManager, clock, worker, scheduler);
                final var notifications = new AtomicInteger();
                store.listen("s1", notifications::incrementAndGet); // registration before attach is retained
                store.attach("s1", ChatSettings.EMPTY.withProviderId("codex"));
                worker.drain();
                assertEquals(1, notifications.get());
                assertEquals(0, scheduler.pending(), "attach alone must not poll");

                store.activity("s1", true);
                assertEquals(QuotaSnapshotStore.TTL_MILLIS, scheduler.latest().delay);
                for (int i = 0; i < 2; i++) {
                    clock.advance(QuotaSnapshotStore.TTL_MILLIS);
                    scheduler.fire();
                    assertEquals(i + 1, fetches.get(), "scheduler must only dispatch, never fetch");
                    worker.drain();
                    assertEquals(i + 2, fetches.get());
                    assertEquals(1, scheduler.pending());
                }

                clock.advance(1000);
                store.activity("s1", false);
                assertEquals(QuotaSnapshotStore.TTL_MILLIS - 1000, scheduler.latest().delay);
                assertEquals(3, fetches.get(), "turn end must not force an early refresh");
                clock.advance(QuotaSnapshotStore.TTL_MILLIS - 1000);
                scheduler.fire();
                worker.drain();
                assertEquals(4, fetches.get());
                assertEquals(4, notifications.get());
                assertEquals(0, scheduler.pending(), "final refresh must stop polling");
                store.close("s1");
                assertFalse(scheduler.isShutdown(), "store must not close the shared scheduler");
                assertFalse(worker.isShutdown(), "store must not close the shared executor");
            }
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class ListenerReplacementFailureIsolationAndClose {
        @Test
        void listenerReplacementFailureIsolationAndClose(final JsonMapper mapper) {
            final var store = store(mapper);
            final var old = new AtomicInteger();
            final var replacement = new AtomicInteger();
            store.listen("s1", old::incrementAndGet);
            store.listen("s1", () -> {
                replacement.incrementAndGet();
                throw new IllegalStateException("test callback failure");
            });
            store.attach("s1", ChatSettings.EMPTY.withProviderId("codex"));
            assertEquals(0, old.get());
            assertEquals(1, replacement.get());
            assertNotNull(store.cachedSnapshot("s1").primaryWindow());
            store.refresh("s1");
            assertEquals(2, replacement.get(), "listener failure must not prevent subsequent publications");
            store.close("s1");
            store.attach("s1", ChatSettings.EMPTY.withProviderId("codex"));
            assertEquals(2, replacement.get(), "close must remove the listener");
            store.listen(null, old::incrementAndGet);
            store.activity(" ", true);
            store.close(null);
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class TimerHonorsBackoffAndDoesNotNotifyOnFailure {
        @Test
        void timerHonorsBackoffAndDoesNotNotifyOnFailure(final JsonMapper mapper) {
            final var clock = new MutableClock();
            try (final var worker = new ControlledExecutor();
                    final var scheduler = new CapturedScheduler()) {
                final var store =
                        new QuotaSnapshotStore(mapper, httpClient, auth, providerManager, clock, worker, scheduler);
                final var notifications = new AtomicInteger();
                store.listen("s1", notifications::incrementAndGet);
                store.attach("s1", ChatSettings.EMPTY.withProviderId("codex"));
                worker.drain();
                store.activity("s1", true);
                responseStatus.set(500);
                clock.advance(QuotaSnapshotStore.TTL_MILLIS);
                scheduler.fire();
                worker.drain();
                assertEquals(2, fetches.get());
                assertEquals(1, notifications.get());
                assertEquals(QuotaSnapshotStore.TTL_MILLIS, scheduler.latest().delay);
                store.snapshot("s1", false);
                worker.drain();
                assertEquals(2, fetches.get(), "failure must back off stale reads too");
                responseStatus.set(200);
                store.activity("s1", false);
                clock.advance(QuotaSnapshotStore.TTL_MILLIS);
                scheduler.fire();
                worker.drain();
                assertEquals(3, fetches.get());
                assertEquals(2, notifications.get());
                assertEquals(0, scheduler.pending());
            }
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class CloseCancelsTimerAndRejectsOldFetchForRecreatedSession {
        @Test
        void closeCancelsTimerAndRejectsOldFetchForRecreatedSession(final JsonMapper mapper) {
            final var clock = new MutableClock();
            try (final var worker = new ControlledExecutor();
                    final var scheduler = new CapturedScheduler()) {
                final var store =
                        new QuotaSnapshotStore(mapper, httpClient, auth, providerManager, clock, worker, scheduler);
                store.attach("s1", ChatSettings.EMPTY.withProviderId("codex"));
                store.activity("s1", true);
                final var oldTimer = scheduler.latest();
                store.close("s1");
                assertTrue(oldTimer.isCancelled());
                store.activity("s1", false); // a turn may unwind after the tab has closed
                assertTrue(store.sessions().isEmpty(), "late turn end must not recreate a closed session");
                final var notifications = new AtomicInteger();
                store.listen("s1", notifications::incrementAndGet);
                store.attach("s1", ChatSettings.EMPTY.withProviderId("codex"));
                worker.runNext(); // old identity's queued fetch must not publish into the recreated session
                assertEquals(0, notifications.get());
                assertEquals(null, store.cachedSnapshot("s1").primaryWindow());
                oldTimer.callback.run(); // model a timer already dispatched when cancellation happened
                worker.drain();
                assertEquals(2, fetches.get());
                assertEquals(1, notifications.get());
                assertEquals(0, scheduler.pending());
            }
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class StaleTimerIsCancelledByProviderChangeAndReadsCoalesce {
        @Test
        void staleTimerIsCancelledByProviderChangeAndReadsCoalesce(final JsonMapper mapper) {
            final var clock = new MutableClock();
            try (final var worker = new ControlledExecutor();
                    final var scheduler = new CapturedScheduler()) {
                final var store =
                        new QuotaSnapshotStore(mapper, httpClient, auth, providerManager, clock, worker, scheduler);
                store.attach("s1", ChatSettings.EMPTY.withProviderId("codex"));
                worker.drain();
                store.activity("s1", true);
                clock.advance(QuotaSnapshotStore.TTL_MILLIS);
                scheduler.fire();
                worker.runNext(); // timer dispatch queues the actual fetch
                store.snapshot("s1", false);
                store.snapshot("s1", false);
                worker.drain();
                assertEquals(2, fetches.get(), "timer and stale reads must coalesce");
                final var oldTimer = scheduler.latest();
                store.attach("s1", ChatSettings.EMPTY.withProviderId("plain"));
                assertTrue(oldTimer.isCancelled());
                oldTimer.callback.run();
                worker.drain();
                assertEquals(2, fetches.get());
                assertEquals(0, scheduler.pending());
            }
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class OldProviderGenerationDoesNotNotify {
        @Test
        void oldProviderGenerationDoesNotNotify(final JsonMapper mapper) {
            try (final var worker = new ControlledExecutor();
                    final var scheduler = new CapturedScheduler()) {
                final var store = new QuotaSnapshotStore(
                        mapper, httpClient, auth, providerManager, new MutableClock(), worker, scheduler);
                final var notifications = new AtomicInteger();
                store.listen("s1", notifications::incrementAndGet);
                store.attach("s1", ChatSettings.EMPTY.withProviderId("codex"));
                store.attach("s1", ChatSettings.EMPTY.withProviderId("plain"));
                store.attach("s1", ChatSettings.EMPTY.withProviderId("codex"));
                worker.runNext();
                assertEquals(0, notifications.get(), "old generation must not publish even on the same provider");
                worker.drain();
                assertEquals(2, fetches.get());
                assertEquals(1, notifications.get());
            }
        }
    }

    @Nested
    @FusionSupport(modules = ClientModule.class)
    class ForcedPublicationMovesPollingDeadline {
        @Test
        void forcedPublicationMovesPollingDeadline(final JsonMapper mapper) throws Exception {
            final var clock = new MutableClock();
            try (final var worker = Executors.newSingleThreadExecutor();
                    final var scheduler = new CapturedScheduler()) {
                final var store =
                        new QuotaSnapshotStore(mapper, httpClient, auth, providerManager, clock, worker, scheduler);
                store.attach("s1", ChatSettings.EMPTY.withProviderId("codex"));
                worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
                store.activity("s1", true);
                final var oldTimer = scheduler.latest();
                clock.advance(1000);
                store.refresh("s1");
                worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
                assertTrue(oldTimer.isCancelled());
                assertEquals(QuotaSnapshotStore.TTL_MILLIS, scheduler.latest().delay);
                assertEquals(2, fetches.get());
                store.close("s1");
            }
        }
    }

    private static final class MutableClock extends Clock {
        private long now = 1_800_000_000_000L;

        private void advance(final long millis) {
            now += millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }

    private static final class ControlledExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        private void runNext() {
            tasks.removeFirst().run();
        }

        private void drain() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }

        @Override
        public void execute(final Runnable command) {
            tasks.addLast(command);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            final var remaining = List.copyOf(tasks);
            tasks.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return isTerminated();
        }
    }

    /** Captures one-shot tasks without starting any scheduler threads. */
    private static final class CapturedScheduler extends ScheduledThreadPoolExecutor {
        private final List<CapturedTask> tasks = new ArrayList<>();

        private CapturedScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
            final var task = new CapturedTask(command, unit.toMillis(delay));
            tasks.add(task);
            return task;
        }

        private CapturedTask latest() {
            return tasks.getLast();
        }

        private long pending() {
            return tasks.stream().filter(t -> !t.isDone()).count();
        }

        private void fire() {
            final var pending = tasks.stream().filter(t -> !t.isDone()).toList();
            assertEquals(1, pending.size());
            pending.getFirst().run();
        }
    }

    private static final class CapturedTask extends FutureTask<Void> implements ScheduledFuture<Void> {
        private final Runnable callback;
        private final long delay;

        private CapturedTask(final Runnable callback, final long delay) {
            super(callback, null);
            this.callback = callback;
            this.delay = delay;
        }

        @Override
        public long getDelay(final TimeUnit unit) {
            return unit.convert(delay, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(final Delayed other) {
            return Long.compare(delay, other.getDelay(TimeUnit.MILLISECONDS));
        }
    }

    private void awaitFetches(final int expected) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fetches.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(expected, fetches.get(), "expected " + expected + " backend fetch(es)");
    }

    private void awaitFetchesAtLeast(final int expected) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fetches.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(fetches.get() >= expected, "expected at least " + expected + " backend fetch(es)");
    }

    private void awaitPrimaryWindow(final QuotaSnapshotStore store, final String session) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            final var view = store.snapshot(session, false);
            if (view.primaryWindow() != null) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("quota snapshot was not populated within the timeout");
    }

    private static long farFuture() {
        return System.currentTimeMillis() + 3_600_000;
    }

    private void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (final var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static class ClientModule extends ConfigurationTestModule {
        private final HttpServer server;

        public ClientModule() throws IOException {
            this(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
        }

        private ClientModule(final HttpServer server) {
            super(values(server));
            this.server = server;
        }

        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.concat(
                    super.beans(),
                    Stream.of(new ProvidedInstanceBean<>(DefaultScoped.class, HttpServer.class, () -> server)));
        }

        private static Map<String, String> values(final HttpServer server) {
            final var props = new HashMap<String, String>();
            props.put("yca.storageHome", storage.toString());
            props.put("yca.provider.providers.length", "2");
            // a codex provider with usage enabled
            props.put("yca.provider.providers.0.id", "codex");
            props.put(
                    "yca.provider.providers.0.baseUrl",
                    ("http://localhost:" + server.getAddress().getPort()) + "/codex");
            props.put("yca.provider.providers.0.apiKey", "");
            props.put("yca.provider.providers.0.capabilities.codex.enableUsage", "true");
            props.put("yca.provider.providers.1.id", "plain");
            props.put(
                    "yca.provider.providers.1.baseUrl",
                    ("http://localhost:" + server.getAddress().getPort()) + "/v1");
            props.put("yca.provider.providers.1.apiKey", "k");
            return props;
        }
    }

    public static class PlainModule extends ConfigurationTestModule {
        private final HttpServer server;

        public PlainModule() throws IOException {
            this(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
        }

        private PlainModule(final HttpServer server) {
            super(values(server));
            this.server = server;
        }

        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.concat(
                    super.beans(),
                    Stream.of(new ProvidedInstanceBean<>(DefaultScoped.class, HttpServer.class, () -> server)));
        }

        private static Map<String, String> values(final HttpServer server) {
            final var props = new HashMap<String, String>();
            props.put("yca.storageHome", storage.toString());
            props.put("yca.provider.providers.length", "1");
            props.put("yca.provider.defaultProvider", "plain");
            props.put("yca.provider.providers.0.id", "plain");
            props.put(
                    "yca.provider.providers.0.baseUrl",
                    ("http://localhost:" + server.getAddress().getPort()) + "/v1");
            props.put("yca.provider.providers.0.apiKey", "k");
            return props;
        }
    }

    public static class SwitchModule extends ConfigurationTestModule {
        private final HttpServer server;

        public SwitchModule() throws IOException {
            this(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
        }

        private SwitchModule(final HttpServer server) {
            super(values(server));
            this.server = server;
        }

        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.concat(
                    super.beans(),
                    Stream.of(new ProvidedInstanceBean<>(DefaultScoped.class, HttpServer.class, () -> server)));
        }

        private static Map<String, String> values(final HttpServer server) {
            final var props = new HashMap<String, String>();
            props.put("yca.storageHome", storage.toString());
            props.put("yca.provider.providers.length", "2");
            props.put("yca.provider.providers.0.id", "codex");
            props.put(
                    "yca.provider.providers.0.baseUrl",
                    ("http://localhost:" + server.getAddress().getPort()) + "/codex");
            props.put("yca.provider.providers.0.apiKey", "");
            props.put("yca.provider.providers.0.capabilities.codex.enableUsage", "true");
            props.put("yca.provider.providers.1.id", "codex2");
            props.put(
                    "yca.provider.providers.1.baseUrl",
                    ("http://localhost:" + server.getAddress().getPort()) + "/codex");
            props.put("yca.provider.providers.1.apiKey", "");
            props.put("yca.provider.providers.1.capabilities.codex.enableUsage", "true");
            return props;
        }
    }
}
