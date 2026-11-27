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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.model.SlackTask;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Controller-only, bounded ephemeral authority. Never persisted or renewed by duplicate delivery/retry. */
@ApplicationScoped
public class SlackActionRegistry implements AutoCloseable {
    private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{1,20}\\.[0-9]{1,20}");
    private final SlackContextConfiguration configuration;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, Grant> pending = new LinkedHashMap<>();
    private final LinkedHashMap<String, Grant> active = new LinkedHashMap<>();
    private boolean closed;

    public SlackActionRegistry(final SlackContextConfiguration configuration, final Clock clock) {
        this.configuration = configuration;
        this.clock = clock;
    }

    /** Prevent first-attempt consumption between durable admission and ephemeral registration. */
    public <T> T admission(final Supplier<T> admission) {
        lock.lock();
        try {
            return admission.get();
        } finally {
            lock.unlock();
        }
    }

    /** Authorization ends exactly at the deadline; physical cleanup runs on the next sweep or poll. */
    public void expire(final long now, final Supplier<Set<String>> activeTaskSnapshot) {
        final var removed = new ArrayList<Grant>();
        lock.lock();
        try {
            // Admission holds this same lock across commit and registration. Obtain the store
            // snapshot in that lock order too, so a new grant cannot be compared with stale state.
            final var activeTasks = activeTaskSnapshot.get();
            for (final var grants : List.of(pending, active)) {
                grants.entrySet().removeIf(entry -> {
                    final var grant = entry.getValue();
                    if (!activeTasks.contains(entry.getKey()) || !grant.active(now)) {
                        removed.add(grant);
                        return true;
                    }
                    return false;
                });
            }
        } finally {
            lock.unlock();
        }
        removed.forEach(grant -> grant.revoke(now >= grant.deadline() ? Terminal.EXPIRED : Terminal.REVOKED));
    }

    /** Called only after a newly accepted task has been durably committed. Snapshots admission identity. */
    public void admitted(final SlackTask task, final String actionToken, final String triggerTs, final long now) {
        if (!configuration.enabled()
                || task.thread() == null
                || triggerTs == null
                || !TIMESTAMP.matcher(task.thread()).matches()
                || !TIMESTAMP.matcher(triggerTs).matches()
                || new BigDecimal(triggerTs).compareTo(new BigDecimal(task.thread())) < 0) {
            return;
        }
        final var token = configuration.searchEnabled()
                        && actionToken != null
                        && !actionToken.isBlank()
                        && actionToken.length() <= 4096
                        && actionToken.chars().noneMatch(c -> c <= 32 || c > 126)
                ? actionToken
                : null;
        lock.lock();
        try {
            if (closed
                    || pending.containsKey(task.id())
                    || active.containsKey(task.id())
                    || pending.size() + active.size() >= 10000) {
                return;
            }
            pending.put(
                    task.id(),
                    new Grant(
                            task,
                            token,
                            triggerTs,
                            now,
                            now + Math.min(600, configuration.actionRetentionSeconds()) * 1000L,
                            clock));
        } finally {
            lock.unlock();
        }
    }

    /** A durable task can match an admission, but can never manufacture authority. */
    public Optional<Grant> claim(final SlackTask task, final int previousAttempts, final long now) {
        final Grant grant;
        lock.lock();
        try {
            grant = pending.remove(task.id());
            if (!closed && previousAttempts == 0 && grant != null && grant.matches(task) && grant.active(now)) {
                active.put(task.id(), grant);
                return Optional.of(grant);
            }
        } finally {
            lock.unlock();
        }
        if (grant != null) {
            grant.revoke(now >= grant.deadline() ? Terminal.EXPIRED : Terminal.REVOKED);
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        final var removed = new ArrayList<Grant>();
        lock.lock();
        try {
            closed = true;
            removed.addAll(pending.values());
            removed.addAll(active.values());
            pending.clear();
            active.clear();
        } finally {
            lock.unlock();
        }
        removed.forEach(Grant::close);
    }

    public enum Terminal {
        EXPIRED,
        REVOKED,
        ACTION_REJECTED
    }

    /** Secret-bearing object intentionally has no record-generated toString. */
    public static final class Grant implements Supplier<String>, AutoCloseable {
        private final String taskId;
        private final String workspace;
        private final String channel;
        private final String requester;
        private final String request;
        private final String threadRoot;
        private final String triggerTs;
        private final long admittedAt;
        private final long deadline;
        private final Clock clock;
        private final ReentrantLock lock = new ReentrantLock();
        private final List<Runnable> cancellations = new ArrayList<>();
        private volatile String token;
        private volatile Terminal terminal;

        private Grant(
                final SlackTask task,
                final String token,
                final String triggerTs,
                final long admittedAt,
                final long deadline,
                final Clock clock) {
            taskId = task.id();
            workspace = task.workspace();
            channel = task.channel();
            requester = task.requester();
            request = task.request();
            threadRoot = task.thread();
            this.triggerTs = triggerTs;
            this.token = token;
            this.admittedAt = admittedAt;
            this.deadline = deadline;
            this.clock = clock;
        }

        private boolean matches(final SlackTask task) {
            return taskId.equals(task.id())
                    && workspace.equals(task.workspace())
                    && channel.equals(task.channel())
                    && requester.equals(task.requester())
                    && threadRoot.equals(task.thread())
                    && Objects.equals(request, task.request());
        }

        public String threadRoot() {
            return threadRoot;
        }

        public String triggerTs() {
            return triggerTs;
        }

        public String workspace() {
            return workspace;
        }

        public String channel() {
            return channel;
        }

        public String requester() {
            return requester;
        }

        public String request() {
            return request;
        }

        public long admittedAt() {
            return admittedAt;
        }

        public long deadline() {
            return deadline;
        }

        public boolean active(final long now) {
            return terminal == null && now < deadline;
        }

        /** Serializes publication against terminal revocation without retaining the published data. */
        public void whileActive(final Runnable publication) {
            lock.lock();
            try {
                if (active(clock.millis())) {
                    publication.run();
                }
            } finally {
                lock.unlock();
            }
        }

        public String status() {
            return terminal == Terminal.EXPIRED || terminal == Terminal.ACTION_REJECTED || clock.millis() >= deadline
                    ? "authorization_expired"
                    : terminal != null || token == null ? "authorization_unavailable" : "ok";
        }

        /** Registration races safely with revocation; callbacks never run under the grant lock. */
        public void onRevoke(final Runnable cancellation) {
            final boolean run;
            lock.lock();
            try {
                run = terminal != null;
                if (!run) {
                    cancellations.add(cancellation);
                }
            } finally {
                lock.unlock();
            }
            if (run) {
                cancellation.run();
            }
        }

        public void revoke(final Terminal reason) {
            final List<Runnable> callbacks;
            lock.lock();
            try {
                if (terminal != null) {
                    return;
                }
                terminal = reason;
                token = null;
                callbacks = List.copyOf(cancellations);
                cancellations.clear();
            } finally {
                lock.unlock();
            }
            callbacks.forEach(Runnable::run);
        }

        @Override
        public String get() {
            return "ok".equals(status()) ? token : null;
        }

        @Override
        public void close() {
            revoke(Terminal.REVOKED);
        }

        @Override
        public String toString() {
            return "SlackContextGrant[redacted]";
        }
    }
}
