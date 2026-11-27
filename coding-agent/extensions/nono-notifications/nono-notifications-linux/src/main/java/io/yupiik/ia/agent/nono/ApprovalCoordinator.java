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
package io.yupiik.ia.agent.nono;

import io.yupiik.ia.agent.nono.ui.ApprovalHandle;
import io.yupiik.ia.agent.nono.ui.ApprovalUi;
import io.yupiik.ia.agent.nono.ui.NotificationSink;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/** One launch, one session, one visible dialog, bounded waiting and retry ledger. */
public final class ApprovalCoordinator implements AutoCloseable {
    private final ApprovalUi ui;
    private final NotificationSink notifications;
    private final PrivateState state;
    private final int timeoutSeconds;
    private final ReentrantLock lock = new ReentrantLock();
    private final ReentrantLock dialog = new ReentrantLock(true);
    private final Map<String, Entry> entries = new HashMap<>();
    private String session;
    private int active;
    private boolean closed;
    private ApprovalHandle handle;

    public ApprovalCoordinator(
            final ApprovalUi ui,
            final NotificationSink notifications,
            final PrivateState state,
            final int timeoutSeconds) {
        this.ui = ui;
        this.notifications = notifications;
        this.state = state;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String decide(final ApprovalProtocol.Approval request, final int clientSeconds, final long receivedAt) {
        final Entry entry;
        final boolean leader;
        lock.lock();
        try {
            if (closed
                    || request.presentation().length() > 3500
                    || (session != null && !session.equals(request.session()))) {
                return "denied";
            }
            final var previous = entries.get(request.id());
            if (previous != null) {
                if (!previous.request.equals(request)) {
                    return "denied";
                }
                entry = previous;
                leader = false;
            } else {
                // No eviction: reusing an old identity must never open a fresh dialog.
                if (active >= 128 || entries.size() >= 4096) {
                    return "denied";
                }
                session = request.session();
                entry = new Entry(
                        request,
                        receivedAt
                                + TimeUnit.SECONDS.toNanos(Math.min(timeoutSeconds, clientSeconds))
                                - TimeUnit.MILLISECONDS.toNanos(250));
                entries.put(request.id(), entry);
                active++;
                leader = true;
            }
        } finally {
            lock.unlock();
        }
        if (!leader) {
            return await(entry);
        }
        var result = "denied";
        var acquired = false;
        try {
            final var remaining = entry.deadline - System.nanoTime();
            if (remaining <= 0 || !(acquired = dialog.tryLock(remaining, TimeUnit.NANOSECONDS))) {
                result = "timeout";
            } else {
                lock.lock();
                try {
                    if (!closed && entry.deadline > System.nanoTime()) {
                        handle = ui.present(
                                request.presentation(), Duration.ofNanos(entry.deadline - System.nanoTime()));
                    }
                } finally {
                    lock.unlock();
                }
                final var current = handle;
                if (current != null) {
                    try (current) {
                        final var granted = current.decision()
                                .toCompletableFuture()
                                .get(Math.max(1, entry.deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                        result = Boolean.TRUE.equals(granted) && System.nanoTime() < entry.deadline
                                ? "granted"
                                : "denied";
                    }
                }
            }
        } catch (final TimeoutException error) {
            result = "timeout";
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (final Exception error) {
            result = "denied";
        } finally {
            lock.lock();
            try {
                if (closed) {
                    result = "denied";
                }
                if (acquired) {
                    handle = null;
                }
                active--;
                try {
                    state.audit(request, result);
                } catch (final Exception error) {
                    result = "denied";
                }
                if ("granted".equals(result) && System.nanoTime() >= entry.deadline) {
                    result = "timeout";
                }
                entry.result.complete(result);
            } finally {
                lock.unlock();
                if (acquired) {
                    dialog.unlock();
                }
            }
        }
        try {
            notifications.publish("Nono approval " + result);
        } catch (final RuntimeException ignored) {
            // Informational delivery cannot alter authorization.
        }
        return result;
    }

    private String await(final Entry entry) {
        try {
            final var remaining = entry.deadline - System.nanoTime();
            return remaining > 0 ? entry.result.get(remaining, TimeUnit.NANOSECONDS) : "timeout";
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            return "denied";
        } catch (final Exception error) {
            return "timeout";
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            if (handle != null) {
                handle.close();
            }
            entries.values().forEach(e -> e.result.complete("denied"));
        } finally {
            lock.unlock();
        }
    }

    private record Entry(ApprovalProtocol.Approval request, long deadline, CompletableFuture<String> result) {
        private Entry(final ApprovalProtocol.Approval request, final long deadline) {
            this(request, deadline, new CompletableFuture<>());
        }
    }
}
