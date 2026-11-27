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
package io.yupiik.ia.agent.core.snapshot;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.session.SessionStorageLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Holds per-session context checkpoints ("snapshots") so the model can periodically roll up its
 * exploration into durable summaries that survive subsequent context compaction (SwivL-style
 * proactive collapse / knowledge survival). The retained summaries are re-injected into the system
 * prompt each turn, protecting them from compaction.
 *
 * <p>State is persisted per session under the session folder ({@code snapshot.snap}, or the
 * legacy {@code <storageHome>/snapshots/s&lt;id&gt;.snap}), written atomically (tmp + atomic
 * move) so a crash cannot corrupt it.</p>
 */
@ApplicationScoped
public class SnapshotStore {
    /** Maximum number of past summaries retained per session. */
    static final int MAX_RETAINED_SUMMARIES = 10;

    private final CodingAgentConfiguration configuration;
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastSeenModified = new ConcurrentHashMap<>();

    public SnapshotStore(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected SnapshotStore() {
        this.configuration = null;
    }

    /** Creates a checkpoint with the given label for a session. */
    public synchronized Checkpoint save(final String sessionId, final String label) {
        final var state = state(sessionId);
        final var cp =
                new Checkpoint(label == null || label.isBlank() ? "checkpoint" : label, System.currentTimeMillis());
        state.checkpoint(cp);
        persist(sessionId, state);
        return cp;
    }

    /**
     * Clears the active checkpoint and records a new retained summary. The summary is pushed into the
     * persisted ring so it is returned to the prompt builder every turn.
     */
    public synchronized Retained restore(final String sessionId, final String summary) {
        final var state = state(sessionId);
        state.checkpoint(null);
        final var retained = state.pushSummary(summary == null ? "" : summary.trim());
        persist(sessionId, state);
        return retained;
    }

    public synchronized void cancel(final String sessionId) {
        final var state = state(sessionId);
        state.checkpoint(null);
        persist(sessionId, state);
    }

    /** The active checkpoint label for a session, or null when none is set. */
    public String checkpointLabel(final String sessionId) {
        final var state = state(sessionId);
        return state == null || state.checkpoint() == null
                ? null
                : state.checkpoint().label();
    }

    /** The retained summaries to inject into the system prompt for a session (most recent last). */
    public List<Retained> retained(final String sessionId) {
        final var state = state(sessionId);
        return state == null || state.retained() == null ? List.of() : List.copyOf(state.retained());
    }

    public void drop(final String sessionId) {
        sessions.remove(sessionId);
        lastSeenModified.remove(sessionId);
        try {
            Files.deleteIfExists(file(sessionId));
            Files.deleteIfExists(legacyFile(sessionId));
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot delete snapshot state for '" + sessionId + "'", e);
        }
    }

    private SessionState state(final String sessionId) {
        final var cached = sessions.get(sessionId);
        if (cached != null) {
            // cross-frontend/cross-process invalidation: another writer may have replaced the
            // file on disk, so reload when the mtime moved
            final var current = modified(file(sessionId));
            if (current != null && !current.equals(lastSeenModified.get(sessionId))) {
                sessions.remove(sessionId);
            } else {
                return cached;
            }
        }
        return sessions.computeIfAbsent(sessionId, id -> load(id));
    }

    private Long modified(final Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : null;
        } catch (final IOException e) {
            return null;
        }
    }

    private SessionState load(final String id) {
        // backward compatibility: read the legacy snapshots/<id>.snap when the session
        // folder snapshot does not exist yet
        var file = file(id);
        if (!Files.isRegularFile(file)) {
            final var legacy = legacyFile(id);
            if (Files.isRegularFile(legacy)) {
                file = legacy;
            }
        }
        if (!Files.isRegularFile(file)) {
            return new SessionState(null, List.of());
        }
        try {
            final var raw = Files.readString(file);
            return parse(raw);
        } catch (final IOException e) {
            return new SessionState(null, List.of());
        }
    }

    private void persist(final String sessionId, final SessionState state) {
        final var file = file(sessionId);
        try {
            Files.createDirectories(file.getParent());
            final var sb = new StringBuilder();
            if (state.checkpoint() != null) {
                sb.append("CHECKPOINT ")
                        .append(escape(state.checkpoint().label()))
                        .append('\n');
            }
            for (final var r : state.retained()) {
                sb.append("SUMMARY ").append(escape(r.summary())).append('\n');
            }
            final var tmp = file.resolveSibling(
                    file.getFileName() + ".tmp-" + ThreadLocalRandom.current().nextLong());
            Files.writeString(tmp, sb.toString());
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            lastSeenModified.put(sessionId, Files.getLastModifiedTime(file).toMillis());
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot persist snapshot state for '" + sessionId + "'", e);
        }
    }

    private static String escape(final String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\n", "\\n");
    }

    private static String unescape(final String raw) {
        return raw == null ? "" : raw.replace("\\n", "\n").replace("\\\\", "\\");
    }

    private SessionState parse(final String raw) {
        Checkpoint checkpoint = null;
        final var retained = new ArrayList<Retained>();
        for (final var line : raw.lines().toList()) {
            if (line.startsWith("CHECKPOINT ")) {
                checkpoint = new Checkpoint(unescape(line.substring("CHECKPOINT ".length())), 0L);
            } else if (line.startsWith("SUMMARY ")) {
                final var s = line.substring("SUMMARY ".length());
                retained.add(new Retained(unescape(s)));
            }
        }
        return new SessionState(checkpoint, retained);
    }

    private Path file(final String sessionId) {
        final var home = Path.of(configuration.resolveStorageHome());
        if (SessionStorageLayout.isGeneratedSessionId(sessionId)) {
            return SessionStorageLayout.sessionDir(home, sessionId).resolve("snapshot.snap");
        }
        return home.resolve("snapshots").resolve(sessionId + ".snap");
    }

    /** The legacy out-of-band snapshot file ({@code snapshots/<id>.snap}). */
    private Path legacyFile(final String sessionId) {
        return Path.of(configuration.resolveStorageHome()).resolve("snapshots").resolve(sessionId + ".snap");
    }

    public record Checkpoint(String label, long createdAt) {}

    public record Retained(String summary) {}

    private static final class SessionState {
        private volatile Checkpoint checkpoint;
        private final CopyOnWriteArrayList<Retained> retained;

        private SessionState(final Checkpoint checkpoint, final List<Retained> retained) {
            this.checkpoint = checkpoint;
            this.retained = new CopyOnWriteArrayList<>(retained);
        }

        private Checkpoint checkpoint() {
            return checkpoint;
        }

        private void checkpoint(final Checkpoint cp) {
            this.checkpoint = cp;
        }

        private List<Retained> retained() {
            return retained;
        }

        private Retained pushSummary(final String summary) {
            if (summary.isBlank()) {
                return new Retained("");
            }
            retained.add(new Retained(summary));
            final var overflow = retained.size() - MAX_RETAINED_SUMMARIES;
            if (overflow > 0) {
                for (var i = 0; i < overflow; i++) {
                    retained.remove(0);
                }
            }
            return retained.get(retained.size() - 1);
        }
    }
}
