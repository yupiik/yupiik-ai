/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The timestamp format the specification mandates: {@code google.protobuf.Timestamp} in the proto, i.e.
 * {@code YYYY-MM-DDTHH:mm:ss.sssZ} in JSON.
 * <p>
 * Formatting is strict - what goes on the wire is what the specification asks for - while parsing is lenient, since a
 * client provided {@code statusTimestampAfter} is only ever compared.
 */
class A2ATimestampsTest {
    @Test
    void formattingIsUtcWithMilliseconds() {
        assertEquals(
                "2026-08-01T09:32:46.123Z",
                A2ATimestamps.format(Instant.parse("2026-08-01T09:32:46.123456Z")),
                "the precision is truncated to the millisecond, not rounded up");
        // a whole second still carries its three digits, the format is fixed width
        assertEquals("2026-08-01T09:32:46.000Z", A2ATimestamps.format(Instant.parse("2026-08-01T09:32:46Z")));
        assertNull(A2ATimestamps.format(null));
    }

    @Test
    void now() {
        final var now = A2ATimestamps.format(Instant.parse("2026-08-01T09:32:46Z"));

        assertTrue(now.endsWith("Z"), now);
        assertEquals(24, now.length(), now);
        // and it round trips, which is what makes A2ATask#statusInstant able to read back its own status
        assertEquals(now, A2ATimestamps.format(A2ATimestamps.parse(now)));
    }

    @Test
    void parsingIsLenientOnThePrecisionAndTheOffset() {
        final var expected = Instant.parse("2026-08-01T09:32:46Z");

        assertEquals(expected, A2ATimestamps.parse("2026-08-01T09:32:46Z"));
        assertEquals(expected, A2ATimestamps.parse("2026-08-01T09:32:46.000Z"));
        assertEquals(expected, A2ATimestamps.parse("  2026-08-01T09:32:46Z  "), "the value is stripped first");
        // only the resulting instant matters, so a non UTC offset is fine
        assertEquals(expected, A2ATimestamps.parse("2026-08-01T11:32:46+02:00"));
    }

    @Test
    void anAbsentTimestampIsNotAFailure() {
        assertNull(A2ATimestamps.parse(null));
        assertNull(A2ATimestamps.parse(""));
        assertNull(A2ATimestamps.parse("   "));
    }

    @Test
    void anInvalidTimestampIsRejected() {
        // A2AService maps this on InvalidParamsError, so the message has to name the offending value
        final var error = assertThrows(IllegalArgumentException.class, () -> A2ATimestamps.parse("yesterday"));
        assertTrue(error.getMessage().contains("yesterday"), error::getMessage);

        // a local date time carries no offset, so it does not designate an instant
        assertThrows(IllegalArgumentException.class, () -> A2ATimestamps.parse("2026-08-01T09:32:46"));
        assertThrows(IllegalArgumentException.class, () -> A2ATimestamps.parse("2026-08-01"));
    }
}
