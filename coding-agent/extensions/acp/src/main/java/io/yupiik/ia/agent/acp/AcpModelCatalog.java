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

import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/** Connection-owned implementation detail: one bounded, cancellable lookup per provider, including failures. */
final class AcpModelCatalog implements AutoCloseable {
    private final Map<String, Entry> entries = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Function<ChatSettings, CompletableFuture<ModelsResponse>> fetch;
    private final ExecutorService workers;
    private final ScheduledExecutorService timer;
    private final Consumer<String> changed;
    private boolean closed;

    AcpModelCatalog(
            final Function<ChatSettings, CompletableFuture<ModelsResponse>> fetch,
            final ExecutorService workers,
            final ScheduledExecutorService timer,
            final Consumer<String> changed) {
        this.fetch = fetch;
        this.workers = workers;
        this.timer = timer;
        this.changed = changed;
    }

    List<String> models(final String provider) {
        lock.lock();
        try {
            final var entry = entries.get(provider);
            return entry == null || !entry.result.isDone() || entry.result.isCompletedExceptionally()
                    ? List.of()
                    : entry.result.getNow(List.of());
        } finally {
            lock.unlock();
        }
    }

    void request(final String provider) {
        lock.lock();
        try {
            if (closed || entries.containsKey(provider)) {
                return;
            }
            final var entry = new Entry();
            entries.put(provider, entry);
            final var task = new FutureTask<Void>(() -> {
                try {
                    final var source = fetch.apply(new ChatSettings(provider, null, null, null));
                    entry.source.set(source);
                    if (entry.result.isDone()) {
                        source.cancel(true);
                    } else {
                        source.whenComplete((models, failure) -> {
                            if (failure != null) {
                                entry.result.completeExceptionally(failure);
                            } else {
                                entry.result.complete(models.ids());
                            }
                        });
                    }
                } catch (final Exception e) {
                    entry.result.completeExceptionally(e);
                }
                return null;
            });
            final var timeout = timer.schedule(
                    () -> {
                        entry.result.completeExceptionally(
                                new TimeoutException("ACP catalog lookup deadline exceeded"));
                    },
                    5,
                    TimeUnit.SECONDS);
            entry.result.whenComplete((value, failure) -> {
                timeout.cancel(false);
                if (failure != null) {
                    if (entry.result.isCancelled() || failure instanceof TimeoutException) {
                        task.cancel(true);
                    }
                    final var source = entry.source.get();
                    if (source != null) {
                        source.cancel(true);
                    }
                }
            });
            entry.result.thenAcceptAsync(ignored -> changed.accept(provider), workers);
            workers.execute(task);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            entries.values().forEach(entry -> entry.result.cancel(true));
            entries.clear();
        } finally {
            lock.unlock();
        }
    }

    private static final class Entry {
        private final CompletableFuture<List<String>> result = new CompletableFuture<>();
        private final AtomicReference<CompletableFuture<ModelsResponse>> source = new AtomicReference<>();
    }
}
