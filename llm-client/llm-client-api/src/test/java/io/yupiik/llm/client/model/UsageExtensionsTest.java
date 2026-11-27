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
package io.yupiik.llm.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UsageExtensionsTest {
    private static final List<String> MILLIS = List.of("generation_time_ms", "generation_time_millis", "generation_ms");
    private static final List<String> SECONDS = List.of("generation_time", "generation_time_seconds");
    private static final List<String> RATES = List.of("tokens_per_second", "tps", "output_tokens_per_second");

    @Test
    void absentTiming() {
        assertNull(UsageExtensions.generationTimeMs(null, 10L));
        assertNull(UsageExtensions.generationTimeMs(Map.of(), 10L));
        assertNull(UsageExtensions.generationTimeMs(Map.of("unknown", 100), 10L));
    }

    @Test
    void aliasesAndPriority() {
        for (final var aliases : List.of(MILLIS, SECONDS, RATES)) {
            final var extensions = new HashMap<String, Object>();
            // Insert in reverse priority so map insertion order cannot determine the winner.
            for (int i = aliases.size() - 1; i >= 0; i--) {
                extensions.put(aliases.get(i), (i + 1) * 10);
                final long expected = aliases == MILLIS
                        ? (i + 1) * 10L
                        : aliases == SECONDS ? (i + 1) * 10_000L : Math.round(100_000d / ((i + 1) * 10));
                assertEquals(expected, UsageExtensions.generationTimeMs(extensions, 100L));
            }
        }
        assertEquals(
                12L,
                UsageExtensions.generationTimeMs(
                        Map.of("generation_ms", " 12 ", "generation_time", 2, "tokens_per_second", 10), 100L));
        assertEquals(
                2_000L,
                UsageExtensions.generationTimeMs(
                        Map.of("generation_time_seconds", " 2 ", "tokens_per_second", 10), 100L));
        assertEquals(10_000L, UsageExtensions.generationTimeMs(Map.of("output_tokens_per_second", " 10 "), 100L));
    }

    @Test
    void invalidCandidatesDoNotHideLaterAliases() {
        for (final var aliases : List.of(MILLIS, SECONDS, RATES)) {
            for (final var invalid : List.of(
                    "bad",
                    "",
                    "  ",
                    true,
                    List.of(1),
                    "NaN",
                    "Infinity",
                    "-Infinity",
                    Double.NaN,
                    Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY,
                    0,
                    -1,
                    "-2")) {
                for (int i = 0; i < aliases.size(); i++) {
                    final var extensions = new HashMap<String, Object>();
                    extensions.put(aliases.get(i), invalid);
                    assertNull(UsageExtensions.generationTimeMs(extensions, 100L), extensions::toString);
                    if (i + 1 < aliases.size()) {
                        extensions.put(aliases.get(i + 1), 10);
                        final long expected = aliases == MILLIS ? 10 : 10_000;
                        assertEquals(
                                expected, UsageExtensions.generationTimeMs(extensions, 100L), extensions::toString);
                    }
                }
            }
            final var extensions = new HashMap<String, Object>();
            extensions.put(aliases.getFirst(), null);
            extensions.put(aliases.get(1), 10);
            assertEquals(aliases == MILLIS ? 10L : 10_000L, UsageExtensions.generationTimeMs(extensions, 100L));
        }
    }

    @Test
    void validatesConvertedDurationBeforeChoosingAlias() {
        for (final var invalid : List.of(0.49, Double.MIN_VALUE, 0x1p63, Double.MAX_VALUE, Long.MAX_VALUE)) {
            assertNull(UsageExtensions.generationTimeMs(Map.of("generation_time_ms", invalid), 1L));
            assertEquals(
                    2L,
                    UsageExtensions.generationTimeMs(
                            Map.of("generation_time_ms", invalid, "generation_time_millis", 2), 1L));
        }
        for (final var invalid : List.of(0.00049, Double.MIN_VALUE, 0x1p63 / 1_000d, Double.MAX_VALUE)) {
            assertNull(UsageExtensions.generationTimeMs(Map.of("generation_time", invalid), 1L));
            assertEquals(
                    2_000L,
                    UsageExtensions.generationTimeMs(
                            Map.of("generation_time", invalid, "generation_time_seconds", 2), 1L));
        }
        for (final var invalid : List.of(10_000d, Double.MAX_VALUE, Double.MIN_VALUE, 1_000d / 0x1p63)) {
            assertNull(UsageExtensions.generationTimeMs(Map.of("tokens_per_second", invalid), 1L));
            assertEquals(500L, UsageExtensions.generationTimeMs(Map.of("tokens_per_second", invalid, "tps", 2), 1L));
        }
    }

    @Test
    void fallsBackAcrossUnitsAfterInvalidConversion() {
        assertEquals(
                2_000L,
                UsageExtensions.generationTimeMs(
                        Map.of(
                                "generation_time_ms",
                                0x1p63,
                                "generation_time_millis",
                                0.1,
                                "generation_ms",
                                "bad",
                                "generation_time",
                                2),
                        null));
        assertEquals(
                500L,
                UsageExtensions.generationTimeMs(
                        Map.of(
                                "generation_time_ms",
                                Double.NaN,
                                "generation_time",
                                Double.MAX_VALUE,
                                "generation_time_seconds",
                                0.0001,
                                "tokens_per_second",
                                2),
                        1L));
    }

    @Test
    void roundingAndLargestRepresentableDuration() {
        assertEquals(1L, UsageExtensions.generationTimeMs(Map.of("generation_time_ms", 0.5), null));
        assertEquals(1L, UsageExtensions.generationTimeMs(Map.of("generation_time", 0.0005), null));
        assertEquals(1L, UsageExtensions.generationTimeMs(Map.of("tps", 2_000), 1L));
        final var belowLimit = Math.nextDown(0x1p63);
        assertEquals(
                Math.round(belowLimit), UsageExtensions.generationTimeMs(Map.of("generation_ms", belowLimit), null));
        assertEquals(1_000L, UsageExtensions.generationTimeMs(Map.of("tps", Long.MAX_VALUE), Long.MAX_VALUE));
    }

    @Test
    void ratesRequirePositiveCompletionCountFromThisPayload() {
        for (final var alias : RATES) {
            final var extensions = Map.<String, Object>of(alias, 10, "completion_tokens", 100);
            assertNull(UsageExtensions.generationTimeMs(extensions, null));
            assertNull(UsageExtensions.generationTimeMs(extensions, 0L));
            assertNull(UsageExtensions.generationTimeMs(extensions, -1L));
            assertEquals(10_000L, UsageExtensions.generationTimeMs(extensions, 100L));
        }
        assertEquals(5L, UsageExtensions.generationTimeMs(Map.of("generation_ms", 5), -1L));
    }
}
