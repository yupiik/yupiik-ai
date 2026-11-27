/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.SessionCleanConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SessionCleanPolicyTest {
    @ParameterizedTest
    @CsvSource({"2s,2", "2m,120", "2h,7200", "2d,172800", "2w,1209600"})
    void parsesDurationUnits(final String value, final long seconds) {
        final var policy = SessionCleanPolicy.resolve(null, value, null);
        assertEquals(Duration.ofSeconds(seconds), policy.olderThan());
        assertNull(policy.biggerThanBytes());
        assertTrue(policy.enabled());
    }

    @ParameterizedTest
    @CsvSource({
        "2,2",
        "2B,2",
        "2K,2000",
        "2KB,2000",
        "2KiB,2048",
        "2M,2000000",
        "2MB,2000000",
        "2MiB,2097152",
        "2G,2000000000",
        "2GB,2000000000",
        "2GiB,2147483648",
        "2T,2000000000000",
        "2TB,2000000000000",
        "2TiB,2199023255552",
        "9223372036854775807B,9223372036854775807"
    })
    void parsesSizeUnits(final String value, final long bytes) {
        final var policy = SessionCleanPolicy.resolve(null, null, value);
        assertEquals(Long.valueOf(bytes), policy.biggerThanBytes());
        assertNull(policy.olderThan());
        assertTrue(policy.enabled());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "-1d", "+1d", "1.5h", "1ms", "1D", "1y", " 1d", "1d ", "1 d"})
    void rejectsInvalidDurations(final String value) {
        assertThrows(IllegalArgumentException.class, () -> SessionCleanPolicy.resolve(null, value, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "+1B", "1.5M", "1k", "1kb", "1KIB", "1Ki", "1P", " 1B", "1B ", "1 B"})
    void rejectsInvalidSizes(final String value) {
        assertThrows(IllegalArgumentException.class, () -> SessionCleanPolicy.resolve(null, null, value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "0m", "0h", "0d", "0w"})
    void rejectsZeroDurations(final String value) {
        assertThrows(IllegalArgumentException.class, () -> SessionCleanPolicy.resolve(null, value, null));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"0", "0B", "0K", "0KB", "0KiB", "0M", "0MB", "0MiB", "0G", "0GB", "0GiB", "0T", "0TB", "0TiB"})
    void rejectsZeroSizes(final String value) {
        assertThrows(IllegalArgumentException.class, () -> SessionCleanPolicy.resolve(null, null, value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808s", "9223372036854775807w", "9223372036854776s"})
    void rejectsDurationParsingMultiplicationAndMillisecondOverflow(final String value) {
        assertThrows(IllegalArgumentException.class, () -> SessionCleanPolicy.resolve(null, value, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "9223372036854775807K", "8388608TiB"})
    void rejectsSizeParsingAndMultiplicationOverflow(final String value) {
        assertThrows(IllegalArgumentException.class, () -> SessionCleanPolicy.resolve(null, null, value));
    }

    @Test
    void validatesDirectConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new SessionCleanPolicy(Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class, () -> new SessionCleanPolicy(Duration.ofSeconds(-1), null));
        assertThrows(IllegalArgumentException.class, () -> new SessionCleanPolicy(null, 0L));
        assertThrows(IllegalArgumentException.class, () -> new SessionCleanPolicy(null, -1L));
        assertThrows(
                IllegalArgumentException.class, () -> new SessionCleanPolicy(Duration.ofSeconds(Long.MAX_VALUE), null));
    }

    @Test
    void usesConfiguredDefaultsOnlyWithoutCliCriteria() {
        final var configuration = new SessionCleanConfiguration(15, "2MiB");
        assertEquals(
                new SessionCleanPolicy(Duration.ofDays(15), 2097152L),
                SessionCleanPolicy.resolve(configuration, null, null));
        assertEquals(
                new SessionCleanPolicy(Duration.ofHours(2), null),
                SessionCleanPolicy.resolve(configuration, "2h", null));
        assertEquals(new SessionCleanPolicy(null, 1000L), SessionCleanPolicy.resolve(configuration, null, "1K"));
        assertEquals(
                new SessionCleanPolicy(Duration.ofDays(3), 42L), SessionCleanPolicy.resolve(configuration, "3d", "42"));
    }

    @Test
    void explicitCriteriaDoNotEvenValidateReplacedConfiguration() {
        final var configuration = new SessionCleanConfiguration(15, "invalid");
        assertEquals(
                new SessionCleanPolicy(Duration.ofDays(1), null),
                SessionCleanPolicy.resolve(configuration, "1d", null));
        assertEquals(new SessionCleanPolicy(null, 1L), SessionCleanPolicy.resolve(configuration, null, "1B"));
        assertThrows(IllegalArgumentException.class, () -> SessionCleanPolicy.resolve(configuration, null, null));
    }

    @Test
    void combinesStrictBoundariesWithAnd() {
        final var policy = new SessionCleanPolicy(Duration.ofSeconds(1), 100L);
        final var now = 10000L;
        assertTrue(policy.matches(now, 8999, 101));
        assertFalse(policy.matches(now, 9000, 101));
        assertFalse(policy.matches(now, 8999, 100));
        assertFalse(policy.matches(now, 9000, 100));
        assertFalse(policy.matches(now, 9001, 101));
        assertFalse(policy.matches(now, 8999, 99));
        assertFalse(policy.matches(now, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void eachCriterionCanOperateAlone() {
        final var ageOnly = new SessionCleanPolicy(Duration.ofSeconds(1), null);
        assertTrue(ageOnly.matches(10000, 8999, 0));
        assertFalse(ageOnly.matches(10000, 9000, Long.MAX_VALUE));
        final var sizeOnly = new SessionCleanPolicy(null, 100L);
        assertTrue(sizeOnly.matches(10000, Long.MAX_VALUE, 101));
        assertFalse(sizeOnly.matches(10000, 0, 100));
    }

    @Test
    void noEnabledCriteriaNeverMatches() {
        for (final var policy : new SessionCleanPolicy[] {
            new SessionCleanPolicy(null, null),
            SessionCleanPolicy.resolve(null, null, null),
            SessionCleanPolicy.resolve(new SessionCleanConfiguration(0, null), null, null),
            SessionCleanPolicy.resolve(new SessionCleanConfiguration(-1, null), null, null)
        }) {
            assertFalse(policy.enabled());
            assertFalse(policy.matches(10000, Long.MIN_VALUE, Long.MAX_VALUE));
        }
        assertEquals(
                new SessionCleanPolicy(null, 1L),
                SessionCleanPolicy.resolve(new SessionCleanConfiguration(0, "1B"), null, null));
    }
}
