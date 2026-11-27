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
package io.yupiik.ia.agent.core.wiring;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * A {@link LogRecord} that carries the session id of the work that produced it.
 * <p>
 * The TUI's log-redirect handler routes these records to the owning session's
 * tab instead of the currently active one, so a background tab's log (LLM
 * stream failure, compaction warning, turn failure) never lands in another
 * tab's content. Records without a session id ({@link LogRecord} or a
 * {@code null}/{@code blank} sessionId) keep the historical active-tab
 * fallback: they are process-wide infrastructure logs (code graph indexing,
 * MCP, HTTP client) that no session owns.
 * <p>
 * Producers in the session turn path must use {@link #of(Logger, String, Level, String)}
 * (or the {@code thrown} variant) whenever a session id is in scope, even in
 * asynchronous callbacks: the id travels with the request data down to the
 * emission point, never through thread-locals (see
 * {@code ChatCompletionRequest#sessionId()} for the main carrier).
 * <p>
 * The factory takes the emitting {@link Logger} on purpose: JDK's
 * {@link Logger#log(LogRecord)} does <em>not</em> populate
 * {@link LogRecord#getLoggerName()} on a caller-supplied record (the
 * string-message {@code log(level, msg)} paths do). Without it every JUL
 * handler would see a {@code null} logger name - the TUI log-redirect handler
 * among them - so the record must be born fully initialized.
 */
public class SessionAwareLogRecord extends LogRecord {

    private final String sessionId;

    private SessionAwareLogRecord(
            final Logger logger, final String sessionId, final Level level, final String message) {
        super(level, message);
        this.sessionId = sessionId == null || sessionId.isBlank() ? null : sessionId;
        if (logger != null) {
            setLoggerName(logger.getName());
        }
    }

    private SessionAwareLogRecord(
            final Logger logger,
            final String sessionId,
            final Level level,
            final String message,
            final Throwable thrown) {
        this(logger, sessionId, level, message);
        setThrown(thrown);
    }

    /** @return the session that produced this record, or {@code null} when unknown/global. */
    public String sessionId() {
        return sessionId;
    }

    /** Builds a session-attributed record; a {@code null}/{@code blank} sessionId is kept as-is (global). */
    public static SessionAwareLogRecord of(
            final Logger logger, final String sessionId, final Level level, final String message) {
        return new SessionAwareLogRecord(logger, sessionId, level, message == null ? "" : message);
    }

    /** Builds a session-attributed record carrying a throwable; null-tolerant like the non-thrown variant. */
    public static SessionAwareLogRecord of(
            final Logger logger,
            final String sessionId,
            final Level level,
            final String message,
            final Throwable thrown) {
        return new SessionAwareLogRecord(logger, sessionId, level, message == null ? "" : message, thrown);
    }
}
