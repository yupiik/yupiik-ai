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
package io.yupiik.ia.agent.core.session;

import io.yupiik.ia.agent.core.llm.Message;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory session store for subagents. No persistence, purely ephemeral.
 * Thread-safe for concurrent access from multiple subagents.
 */
public class InMemorySessionStore implements SessionStore {
    private final Clock clock;
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    private static final class StoredSession {
        private final List<SessionEntry> entries = new ArrayList<>();
        private SessionMetadata metadata;
        private long modifiedAt;
    }

    public InMemorySessionStore(final Clock clock) {
        this.clock = clock;
    }

    @Override
    public String create() {
        lock.lock();
        try {
            var id = "mem-" + System.nanoTime();
            while (sessions.containsKey(id)) {
                id = "mem-" + System.nanoTime();
            }
            final var session = new StoredSession();
            session.modifiedAt = now();
            sessions.put(id, session);
            return id;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void append(final String id, final SessionEntry entry) {
        lock.lock();
        try {
            final var session = sessions.computeIfAbsent(id, k -> new StoredSession());
            session.entries.add(entry);
            session.modifiedAt = now();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Message> load(final String id) {
        lock.lock();
        try {
            final var session = sessions.get(id);
            if (session == null) {
                return List.of();
            }
            return session.entries.stream()
                    .filter(e -> e.role() != null)
                    .map(e -> new Message(e.role(), e.content(), e.name(), e.toolCalls(), e.toolCallId(), e.others()))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void replace(final String id, final List<Message> messages) {
        final var entries = messages.stream()
                .map(m -> SessionEntry.of(
                        clock != null ? clock.millis() : System.currentTimeMillis(),
                        m.role(),
                        m.content(),
                        m.toolCalls(),
                        m.toolCallId(),
                        m.name(),
                        m.others()))
                .toList();
        lock.lock();
        try {
            final var session = sessions.computeIfAbsent(id, k -> new StoredSession());
            final var marker = session.entries.isEmpty() ? null : session.entries.getFirst();
            session.entries.clear();
            if (marker != null
                    && "system".equals(marker.role())
                    && "project".equals(marker.name())
                    && messages.stream().noneMatch(Message::isProjectMarker)) {
                session.entries.add(marker);
            }
            session.entries.addAll(entries);
            session.modifiedAt = now();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isEmpty(final String id) {
        final var messages = load(id);
        if (messages.isEmpty()) {
            return true;
        }
        var first = 0;
        while (first < messages.size() && "system".equals(messages.get(first).role())) {
            first++;
        }
        return first >= messages.size()
                || messages.size() - first == 1
                        && "user".equals(messages.get(first).role());
    }

    @Override
    public void delete(final String id) {
        lock.lock();
        try {
            sessions.remove(id);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public SessionMetadata metadata(final String id) {
        lock.lock();
        try {
            final var session = sessions.get(id);
            return session == null ? null : session.metadata;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean metadata(final String id, final SessionMetadata metadata) {
        lock.lock();
        try {
            final var session = sessions.get(id);
            if (session == null) {
                return false;
            }
            session.metadata = Objects.requireNonNull(metadata, "metadata");
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<SessionInfo> list() {
        lock.lock();
        try {
            return sessions.entrySet().stream()
                    .map(entry -> new SessionInfo(entry.getKey(), entry.getValue().modifiedAt, 0))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markProject(final String id, final String dir) {
        lock.lock();
        try {
            SessionStore.super.markProject(id, dir);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void replaceProject(final String id, final String dir) {
        lock.lock();
        try {
            SessionStore.super.replaceProject(id, dir);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String summary(final String id) {
        return load(id).stream()
                .filter(m -> "user".equals(m.role())
                        && m.content() instanceof final String text
                        && !text.isBlank()
                        && !text.startsWith("<compacted-context>")
                        && !text.startsWith("<pruned-context>"))
                .map(m -> (String) m.content())
                .map(AgentSessionService::firstLine)
                .findFirst()
                .orElse("");
    }

    /**
     * Returns all session IDs currently in memory.
     */
    public List<String> listIds() {
        return new ArrayList<>(sessions.keySet());
    }

    /**
     * Returns the number of entries in a session.
     */
    public int size(final String id) {
        return count(id);
    }

    /**
     * Clears all sessions.
     */
    public void clear() {
        lock.lock();
        try {
            sessions.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long now() {
        return clock == null ? System.currentTimeMillis() : clock.millis();
    }
}
