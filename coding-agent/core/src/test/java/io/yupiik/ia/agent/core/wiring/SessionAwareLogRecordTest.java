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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class SessionAwareLogRecordTest {

    @Test
    void carriesTheSessionIdAndLevelAndMessage() {
        final var logger = Logger.getLogger("io.yupiik.ia.agent.core.wiring.SessionAwareLogRecordTest");
        final var record = SessionAwareLogRecord.of(logger, "s1", Level.WARNING, "boom");
        assertEquals("s1", record.sessionId());
        assertEquals(Level.WARNING, record.getLevel());
        assertEquals("boom", record.getMessage());
        assertInstanceOf(LogRecord.class, record, "must remain a plain LogRecord for the JUL handlers");
    }

    @Test
    void loggerNameIsPopulatedFromTheEmittingLogger() {
        // JDK's Logger.log(LogRecord) does NOT populate getLoggerName() on a caller-supplied record,
        // so the factory must - every JUL handler (the TUI log-redirect among them) reads it.
        final var logger = Logger.getLogger("io.yupiik.ia.agent.core.wiring.SessionAwareLogRecordTest");
        final var record = SessionAwareLogRecord.of(logger, "s1", Level.INFO, "m");
        assertEquals("io.yupiik.ia.agent.core.wiring.SessionAwareLogRecordTest", record.getLoggerName());
    }

    @Test
    void thrownVariantCarriesTheCause() {
        final var logger = Logger.getLogger("io.yupiik.ia.agent.core.wiring.SessionAwareLogRecordTest");
        final var cause = new IllegalStateException("x");
        final var record = SessionAwareLogRecord.of(logger, "s2", Level.SEVERE, "failed", cause);
        assertEquals("s2", record.sessionId());
        assertSame(cause, record.getThrown());
        assertEquals(Level.SEVERE, record.getLevel());
    }

    @Test
    void blankAndNullSessionIdsAreNormalizedToNull() {
        final var logger = Logger.getLogger("io.yupiik.ia.agent.core.wiring.SessionAwareLogRecordTest");
        assertNull(SessionAwareLogRecord.of(logger, null, Level.INFO, "m").sessionId());
        assertNull(SessionAwareLogRecord.of(logger, "  ", Level.INFO, "m").sessionId());
        assertNull(SessionAwareLogRecord.of(logger, "", Level.INFO, "m").sessionId());
    }

    @Test
    void nullMessageIsNormalizedToEmptyString() {
        final var logger = Logger.getLogger("io.yupiik.ia.agent.core.wiring.SessionAwareLogRecordTest");
        assertEquals(
                "", SessionAwareLogRecord.of(logger, "s3", Level.INFO, null).getMessage());
    }
}
