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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores conversation sessions. Implementations can be file-backed (persistent)
 * or in-memory (ephemeral, for subagents).
 */
public interface SessionStore {

    /** Out-of-band preset, never included by load/tail/count/summary or history replacement. */
    default SessionMetadata metadata(final String id) {
        return null;
    }

    /**
     * Atomically replaces the preset only if the session still exists. Does not create a session.
     * Returns false for a deleted session or a store without preset support.
     */
    default boolean metadata(final String id, final SessionMetadata metadata) {
        return false;
    }

    /**
     * Creates a new session and returns its ID.
     *
     * @return the new session ID
     */
    default String create() {
        return null;
    }

    /**
     * Appends an entry to the session.
     *
     * @param id the session ID
     * @param entry the entry to append
     */
    default void append(String id, SessionEntry entry) {}

    /**
     * Loads all messages from a session.
     *
     * @param id the session ID
     * @return the list of messages, empty if session doesn't exist
     */
    default List<Message> load(String id) {
        return List.of();
    }

    /**
     * Loads only the {@code count} most recent messages of a session (tail-first), used by
     * paginated replay (the web transcript loads earlier history in slices). File-backed
     * implementations read only the last lines; the default falls back to a full load so
     * in-memory/subagent stores behave identically.
     *
     * @param id    the session ID
     * @param count the maximum number of trailing messages to return ({@code <= 0} -> empty)
     * @return the last {@code count} messages in order, empty if the session doesn't exist
     */
    default List<Message> tail(String id, int count) {
        if (count <= 0) {
            return List.of();
        }
        final var all = load(id);
        return all.size() <= count ? all : List.copyOf(all.subList(all.size() - count, all.size()));
    }

    /**
     * Total number of persisted messages of a session (used by the web transcript pagination
     * to report how much older history remains). File-backed implementations count lines;
     * the default falls back to a full load.
     *
     * @param id the session ID
     * @return the number of persisted messages, 0 when the session doesn't exist
     */
    default int count(String id) {
        return load(id).size();
    }

    /**
     * Replaces the entire session content.
     *
     * @param id the session ID
     * @param messages the new messages
     */
    default void replace(String id, List<Message> messages) {}

    /**
     * Checks if a session is empty (no messages or only initial user prompt).
     *
     * @param id the session ID
     * @return true if empty
     */
    default boolean isEmpty(String id) {
        return false;
    }

    /**
     * Deletes a session.
     *
     * @param id the session ID
     */
    default void delete(String id) {}

    /**
     * Returns a one-line summary of the session (first user prompt).
     *
     * @param id the session ID
     * @return the summary, or empty string
     */
    default String summary(String id) {
        return "";
    }

    /**
     * Ensures the session carries a leading {@link Message#projectDir(String)} marker recording
     * the working directory it belongs to. No-op when the marker is already present (or the store
     * does not persist markers, e.g. in-memory subagent stores). The default implementation is
     * purely additive: it loads the session and, only when the marker is absent, rewrites it with
     * the marker at index 0 -- file and in-memory stores both work through {@link #load}/
     * {@link #replace}.
     *
     * @param id the session ID
     * @param dir the absolute working directory to record
     */
    default void markProject(final String id, final String dir) {
        if (dir == null || dir.isBlank()) {
            return;
        }
        final var messages = load(id);
        if (Message.isProjectMarker(messages.isEmpty() ? null : messages.get(0))) {
            return;
        }
        final var withMarker = new ArrayList<Message>(messages.size() + 1);
        withMarker.add(Message.projectDir(dir));
        withMarker.addAll(messages);
        replace(id, withMarker);
    }

    /**
     * Replaces (or installs) the session's leading project marker with {@code dir}. Unlike
     * {@link #markProject} this always rewrites the marker, even when the session already carries
     * one - which is exactly what a mid-session working-directory change needs (e.g. the web
     * {@code session.setProject} operation). No-op when {@code dir} is blank.
     *
     * @param id the session ID
     * @param dir the new absolute working directory to record
     */
    default void replaceProject(final String id, final String dir) {
        if (dir == null || dir.isBlank()) {
            return;
        }
        final var messages = load(id);
        final var withNewMarker = new ArrayList<Message>(messages.size() + 1);
        withNewMarker.add(Message.projectDir(dir));
        for (final var message : messages) {
            if (!Message.isProjectMarker(message)) {
                withNewMarker.add(message);
            }
        }
        replace(id, withNewMarker);
    }

    /**
     * Returns the working directory recorded by the session's leading project marker, or an empty
     * string when none is set. Default implementation loads the whole session (cheap for the
     * unicast callers); file-backed stores override to peek only the first line.
     *
     * @param id the session ID
     * @return the recorded working directory, or empty string
     */
    default String projectDirOf(final String id) {
        final var all = load(id);
        if (all.isEmpty()) {
            return "";
        }
        final var dir = Message.projectDirOf(all.get(0));
        return dir == null ? "" : dir;
    }

    /**
     * Returns the file path for a session ID (file-backed implementations).
     *
     * @param id the session ID
     * @return the session file path
     */
    default Path path(String id) {
        throw new UnsupportedOperationException("path() not supported by this SessionStore implementation");
    }

    /**
     * The session storage directory (file-backed implementations).
     *
     * @return the session directory path
     */
    default Path dir() {
        throw new UnsupportedOperationException("dir() not supported by this SessionStore implementation");
    }

    /** Current time in millis (used to timestamp session entries). */
    default long now() {
        return System.currentTimeMillis();
    }

    /** Housekeeping convenience: cleans without deleting empty sessions. */
    default void cleanup() {
        housekeeping(false);
    }

    /**
     * Cleans the session directory: deletes sessions older than the retention
     * window, compresses sessions older than the compress window.
     *
     * @param deleteEmpty whether to delete empty sessions
     * @return report of what was done
     */
    default HousekeepingReport housekeeping(boolean deleteEmpty) {
        return new HousekeepingReport(0, 0, 0);
    }

    /**
     * Lists all stored sessions.
     *
     * @return list of session info
     */
    default List<SessionInfo> list() {
        return List.of();
    }

    /**
     * Returns the last session ID, if any.
     *
     * @return the last session ID or null
     */
    default String lastSessionId() {
        return null;
    }

    record SessionInfo(String id, long modifiedAt, long sizeBytes) {}

    record HousekeepingReport(int compressed, int deleted, int kept) {}
}
