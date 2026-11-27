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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.usage.CodexUsageSnapshot;
import io.yupiik.ia.agent.core.usage.QuotaSnapshotStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionUsageReporterTest {
    @TempDir
    private static Path eventWorkdir;

    @Test
    void usageEventsPreserveFullInputAndRawBillingForCachedAndOutputOnlyCalls() {
        for (final var usage : List.of(
                new Usage(10, 5, 15, 60, 30, 2, 0, 100),
                new Usage(100, 5, 105, 60, 0, 2, 0, 100),
                new Usage(0, 0, 0, 100, 0, 0, 0, 100),
                new Usage(0, 5, 5, 0, 0, 2, 0, 0))) {
            final var tracker = new UsageTracker(null, null, null, Clock.systemUTC());
            tracker.record(usage);
            final var events = new ArrayList<AgentEvent.UsageUpdated>();
            final var reporter = new SessionUsageReporter(
                    tracker,
                    null,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            if (event instanceof AgentEvent.UsageUpdated updated) {
                                events.add(updated);
                            }
                        }
                    },
                    () -> null,
                    "s-input",
                    () -> ChatSettings.EMPTY,
                    null,
                    null,
                    eventWorkdir);
            reporter.emitUsage(List.of(), 0, "model-x");
            reporter.pushUsageContext(123, "model-x");
            reporter.pushUsageQuota("model-x");
            assertEquals(3, events.size());
            for (final var event : events) {
                assertEquals(
                        new Usage(
                                usage.promptTokens(),
                                usage.completionTokens(),
                                usage.totalTokens(),
                                usage.cacheReadTokens(),
                                0,
                                usage.reasoningTokens(),
                                0,
                                usage.contextInputTokens()),
                        event.usage());
                assertEquals("s-input", event.sessionId());
                assertSame(eventWorkdir, event.workdir());
            }

            tracker.recordAuxiliary(usage);
            reporter.pushUsageQuota("model-x");
            final var aggregated = events.getLast().usage();
            assertEquals(2 * usage.contextInputTokens(), aggregated.contextInputTokens());
            assertEquals(2 * usage.promptTokens(), aggregated.promptTokens());
            assertEquals(2 * usage.completionTokens(), aggregated.completionTokens());
            assertEquals(2 * usage.totalTokens(), aggregated.totalTokens());
            assertEquals(2 * usage.cacheReadTokens(), aggregated.cacheReadTokens());
            reporter.close();
        }
    }

    @Test
    void completionPushesCachedQuotaWithoutRefreshingOrResettingContext() {
        final var events = new ArrayList<AgentEvent.UsageUpdated>();
        final var store = new CompletionStore();
        final var reporter = new SessionUsageReporter(
                null,
                store,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        if (event instanceof AgentEvent.UsageUpdated usage) {
                            events.add(usage);
                        }
                    }
                },
                () -> null,
                "s1",
                () -> ChatSettings.EMPTY,
                null,
                null,
                eventWorkdir);
        reporter.pushUsageContext(1234, "model-x");
        reporter.refreshUsage("model-x");
        final var reads = store.refreshingReads;
        final var before = events.getLast();
        store.completion.run();
        final var after = events.getLast();
        assertSame(eventWorkdir, before.workdir());
        assertSame(eventWorkdir, after.workdir(), "background quota completion keeps the session root");
        assertEquals(before.estimatedContextTokens(), after.estimatedContextTokens());
        assertEquals(before.contextPercent(), after.contextPercent());
        assertEquals(before.usage(), after.usage());
        assertEquals("model-x", after.model());
        assertEquals("s1", after.sessionId());
        assertTrue(after.quota().supported());
        assertEquals(reads, store.refreshingReads, "completion must not trigger a quota refresh");
        final var count = events.size();
        reporter.close();
        assertEquals("s1", store.closedSession);
        store.completion.run();
        assertEquals(count, events.size(), "late completion after close must not publish");
    }

    private static class CompletionStore extends QuotaSnapshotStore {
        private Runnable completion;
        private int refreshingReads;
        private String closedSession;

        @Override
        public void listen(final String id, final Runnable listener) {
            assertEquals("s1", id);
            completion = listener;
        }

        @Override
        public void attach(final String id, final ChatSettings settings) {
            assertTrue(completion != null, "subscribe before starting the initial fetch");
        }

        @Override
        public QuotaView snapshot(final String id, final boolean force) {
            refreshingReads++;
            return cachedSnapshot(id);
        }

        @Override
        public QuotaView cachedSnapshot(final String id) {
            return new QuotaView(true, true, CodexUsageSnapshot.empty());
        }

        @Override
        public void close(final String id) {
            closedSession = id;
        }
    }

    @Test
    void attachSuppressesOnlyInlineCompletionAndRestoresScopeAfterFailure() throws Exception {
        final var events = new CopyOnWriteArrayList<AgentEvent.UsageUpdated>();
        final var listener = new AtomicReference<Runnable>();
        final var fail = new AtomicBoolean();
        final var store = new CompletionStore() {
            @Override
            public void listen(final String id, final Runnable callback) {
                listener.set(callback);
            }

            @Override
            public void attach(final String id, final ChatSettings settings) {
                listener.get().run();
                assertTrue(events.isEmpty(), "inline completion must be suppressed");
                if (fail.get()) {
                    throw new IllegalStateException("attach failed");
                }
                try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    executor.submit(listener.get()).get(5, TimeUnit.SECONDS);
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        final var reporter = new SessionUsageReporter(
                null,
                store,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        if (event instanceof AgentEvent.UsageUpdated usage) {
                            events.add(usage);
                        }
                    }
                },
                () -> null,
                "s1",
                () -> ChatSettings.EMPTY,
                null,
                null,
                eventWorkdir);
        reporter.attachQuota(ChatSettings.EMPTY);
        assertEquals(1, events.size(), "background completion during attach must still publish");
        events.clear();
        fail.set(true);
        Assertions.assertThrows(IllegalStateException.class, () -> reporter.attachQuota(ChatSettings.EMPTY));
        assertTrue(events.isEmpty());
        listener.get().run();
        assertEquals(1, events.size(), "exceptional attach must restore the unbound scope");
        reporter.close();
    }

    @Test
    void pushUsageContextCarriesTheProvidedEstimateAndRefreshesTheGaugeCache() {
        final var events = new ArrayList<AgentEvent>();
        final var reporter = new SessionUsageReporter(
                new UsageTracker(null, null, null, Clock.systemUTC()) {
                    @Override
                    public double contextPercent(final int estimatedTokens) {
                        return 42.5;
                    }
                },
                null,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        if (event instanceof AgentEvent agentEvent) {
                            events.add(agentEvent);
                        }
                    }
                },
                () -> null,
                "s1",
                () -> ChatSettings.EMPTY,
                null,
                null,
                eventWorkdir);

        reporter.pushUsageContext(12_345, "model-x");

        assertEquals(1, events.size(), "pushUsageContext must emit exactly one event: " + events);
        final var pushed = (AgentEvent.UsageUpdated) events.get(0);
        assertEquals(12_345, pushed.estimatedContextTokens(), "the provided estimate must be carried verbatim");
        assertEquals(42.5, pushed.contextPercent(), "the percent must be recomputed from the provided estimate");
        assertEquals("model-x", pushed.model());
        assertEquals("s1", pushed.sessionId());
        assertEquals(-1, pushed.lastRealPromptTokens(), "no anchor: no real prompt base yet");
        assertEquals(-1, pushed.calibrationRatio(), "no anchor: no calibration ratio yet");
        assertSame(pushed.quota(), AgentEvent.UsageUpdated.Quota.NONE, "no quota store: NONE quota");
    }
}
