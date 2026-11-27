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

import io.yupiik.ia.agent.core.llm.ModelsResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** No clocks, subprocesses, sockets or sleeps: the executor and deadline are driven explicitly. */
class ModelCatalogTest {
    @Test
    void lazyOncePerProviderAndSuccessfulCompletionNotifiesWithDeduplicatedModels() {
        final var workers = new ManualExecutor();
        final var timer = new ManualTimer();
        final var source = new CompletableFuture<ModelsResponse>();
        final var fetched = new ArrayList<String>();
        final var changed = new ArrayList<String>();
        try (final var catalog = new AcpModelCatalog(
                settings -> {
                    fetched.add(settings.providerId());
                    return source;
                },
                workers,
                timer,
                changed::add)) {
            assertEquals(List.of(), catalog.models("a"));
            catalog.request("a");
            catalog.request("a");
            assertTrue(fetched.isEmpty());
            workers.drain();
            assertEquals(List.of("a"), fetched);
            assertTrue(catalog.models("a").isEmpty());
            source.complete(models("z", "a", "a"));
            workers.drain();
            assertEquals(List.of("a", "z"), catalog.models("a"));
            assertEquals(List.of("a"), changed);
            assertTrue(timer.scheduled.getFirst().isCancelled());
        }
    }

    @Test
    void timeoutCancelsTransportWithoutWaitingForTheRealDeadlineAndDoesNotRetry() {
        final var workers = new ManualExecutor();
        final var timer = new ManualTimer();
        final var source = new CompletableFuture<ModelsResponse>();
        try (final var catalog =
                new AcpModelCatalog(settings -> source, workers, timer, ignored -> fail("Timed out"))) {
            catalog.request("a");
            workers.drain();
            timer.scheduled.getFirst().run();
            assertTrue(source.isCancelled());
            assertEquals(List.of(), catalog.models("a"));
            catalog.request("a");
            assertEquals(1, timer.scheduled.size());
            workers.drain();
        }
    }

    @Test
    void failureKeepsFallbackAndIsolatedProvidersCanStillSucceed() {
        final var workers = new ManualExecutor();
        final var timer = new ManualTimer();
        final var changed = new ArrayList<String>();
        try (final var catalog = new AcpModelCatalog(
                settings -> "a".equals(settings.providerId())
                        ? CompletableFuture.failedFuture(new IllegalStateException("offline"))
                        : CompletableFuture.completedFuture(models("b-model")),
                workers,
                timer,
                changed::add)) {
            catalog.request("a");
            catalog.request("b");
            workers.drain();
            assertEquals(List.of(), catalog.models("a"));
            assertEquals(List.of("b-model"), catalog.models("b"));
            assertEquals(List.of("b"), changed);
        }
    }

    @Test
    void closeCancelsOutstandingAndQueuedLookupsAndSuppressesLateResults() {
        final var workers = new ManualExecutor();
        final var timer = new ManualTimer();
        final var source = new CompletableFuture<ModelsResponse>();
        final var catalog = new AcpModelCatalog(settings -> source, workers, timer, ignored -> fail("Closed"));
        catalog.request("a");
        workers.drain();
        catalog.request("b");
        catalog.close();
        workers.drain();
        assertTrue(source.isCancelled());
        assertFalse(source.complete(models("late")));
        assertEquals(List.of(), catalog.models("a"));
        catalog.request("c");
        assertEquals(2, timer.scheduled.size());
        assertTrue(timer.scheduled.stream().allMatch(Future::isCancelled));
    }

    private static ModelsResponse models(final String... ids) {
        return new ModelsResponse(Arrays.stream(ids)
                .map(id -> new ModelsResponse.Model(id, null, null))
                .toList());
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(final Runnable task) {
            tasks.add(task);
        }

        private void drain() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(final long time, final TimeUnit unit) {
            return true;
        }
    }

    private static final class ManualTimer extends ScheduledThreadPoolExecutor {
        private final List<Deadline> scheduled = new ArrayList<>();

        private ManualTimer() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(final Runnable task, final long delay, final TimeUnit unit) {
            assertEquals(5, delay);
            assertEquals(TimeUnit.SECONDS, unit);
            final var future = new Deadline(task);
            scheduled.add(future);
            return future;
        }
    }

    private static final class Deadline extends FutureTask<Void> implements ScheduledFuture<Void> {
        private Deadline(final Runnable task) {
            super(task, null);
        }

        @Override
        public long getDelay(final TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(final Delayed other) {
            return 0;
        }
    }
}
