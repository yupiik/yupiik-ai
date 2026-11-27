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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DurationFormatTest {
    @Test
    void millisecondsBelowTheSecondKeepTheirUnit() {
        assertEquals("0ms", DurationFormat.humanize(0));
        assertEquals("850ms", DurationFormat.humanize(850));
        assertEquals("999ms", DurationFormat.humanize(999));
    }

    @Test
    void seconds() {
        assertEquals("1s", DurationFormat.humanize(1_000));
        assertEquals("1.5s", DurationFormat.humanize(1_500));
        assertEquals("12.3s", DurationFormat.humanize(12_300));
        assertEquals("59.4s", DurationFormat.humanize(59_400));
    }

    @Test
    void minutesWithRoundingSeconds() {
        assertEquals("1m", DurationFormat.humanize(60_000));
        assertEquals("1m 1s", DurationFormat.humanize(61_000));
        assertEquals("3m 25s", DurationFormat.humanize(205_000));
        // two significant units: a plain minute drops the zero seconds
        assertEquals("2m", DurationFormat.humanize(120_000));
    }

    @Test
    void hours() {
        assertEquals("1h", DurationFormat.humanize(3_600_000));
        assertEquals("1h 5m", DurationFormat.humanize(3_900_000));
        assertEquals("8h 20m", DurationFormat.humanize(30_023_846));
        assertEquals("23h 59m", DurationFormat.humanize(86_399_999));
    }

    @Test
    void days() {
        assertEquals("1d", DurationFormat.humanize(86_400_000));
        assertEquals("1d 2h", DurationFormat.humanize(93_600_000));
        assertEquals("2d 3h", DurationFormat.humanize(183_600_000));
    }

    @Test
    void negativeIsClampedToZero() {
        assertEquals("0ms", DurationFormat.humanize(-5));
    }
}
