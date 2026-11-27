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
package io.yupiik.fusion.a2a.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * How the duration attributes of {@link A2AConfiguration} are read.
 * <p>
 * These attributes used to be a plain number of seconds, so that spelling has to keep working: an existing
 * {@code -Dfusion.a2a.taskTimeout=300} must still mean five minutes and not three hundred milliseconds.
 */
class A2ADurationsTest {
    private static final Duration FALLBACK = Duration.ofSeconds(300);

    @Test
    void aBareNumberIsSeconds() {
        assertEquals(Duration.ofSeconds(300), A2ADurations.parse("300", FALLBACK));
        assertEquals(Duration.ofSeconds(1), A2ADurations.parse("1", FALLBACK));
        assertEquals(Duration.ofSeconds(-1), A2ADurations.parse("-1", FALLBACK));
        assertEquals(Duration.ZERO, A2ADurations.parse("0", FALLBACK));
        assertEquals(Duration.ofSeconds(300), A2ADurations.parse("  300  ", FALLBACK), "the value is stripped first");
    }

    @Test
    void theIsoFormIsWhatDurationItselfSpells() {
        assertEquals(Duration.ofMillis(100), A2ADurations.parse("PT0.1S", FALLBACK));
        assertEquals(Duration.ofMillis(500), A2ADurations.parse("PT0.5S", FALLBACK));
        assertEquals(Duration.ofSeconds(30), A2ADurations.parse("PT30S", FALLBACK));
        assertEquals(Duration.ofMinutes(5), A2ADurations.parse("PT5M", FALLBACK));
        assertEquals(Duration.ofHours(1), A2ADurations.parse("PT1H", FALLBACK));
        assertEquals(Duration.ofMinutes(90), A2ADurations.parse("PT1H30M", FALLBACK));
        assertEquals(Duration.ZERO, A2ADurations.parse("PT0S", FALLBACK));
        assertEquals(Duration.ofSeconds(-1), A2ADurations.parse("PT-1S", FALLBACK));
        assertEquals(Duration.ofMinutes(5), A2ADurations.parse("pt5m", FALLBACK), "the units are case insensitive");
    }

    @Test
    void anUnsetAttributeGetsTheDefault() {
        assertSame(FALLBACK, A2ADurations.parse(null, FALLBACK));
        assertSame(FALLBACK, A2ADurations.parse("", FALLBACK));
        assertSame(FALLBACK, A2ADurations.parse("   ", FALLBACK));
    }

    @Test
    void aValueWhichIsNeitherIsRefused() {
        for (final var value : new String[] {"soon", "5 minutes", "30s", "P", "PT", "1.5"}) {
            final var error =
                    assertThrows(IllegalArgumentException.class, () -> A2ADurations.parse(value, FALLBACK), value);
            assertTrue(error.getMessage().contains(value), error::getMessage);
            assertTrue(error.getMessage().contains("ISO-8601"), error::getMessage);
        }
    }

    @Test
    void aTimeoutHasToBePositive() {
        assertEquals(Duration.ofMillis(100), A2ADurations.positive("PT0.1S", FALLBACK));

        // a timeout of zero would fire before the work it guards even started, and the HTTP client refuses one
        assertSame(FALLBACK, A2ADurations.positive("0", FALLBACK));
        assertSame(FALLBACK, A2ADurations.positive("PT0S", FALLBACK));
        assertSame(FALLBACK, A2ADurations.positive("-1", FALLBACK));
        assertSame(FALLBACK, A2ADurations.positive(null, FALLBACK));
    }
}
