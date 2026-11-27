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

import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * Reads the duration attributes of {@link A2AConfiguration}.
 * <p>
 * A duration is written the way {@link Duration#parse(CharSequence)} spells it - {@code PT0.5S}, {@code PT30S},
 * {@code PT5M}, {@code PT1H} - and a bare number is read as seconds, which is what these attributes used to be: an
 * existing {@code -Dfusion.a2a.taskTimeout=300} keeps meaning five minutes.
 */
public final class A2ADurations {
    private A2ADurations() {
        // no-op
    }

    /**
     * @param value    a duration, {@code null} or blank to get {@code fallback}.
     * @param fallback what an unset attribute means.
     * @return the parsed duration.
     * @throws IllegalArgumentException when {@code value} is neither a number of seconds nor an ISO-8601 duration.
     */
    public static Duration parse(final String value, final Duration fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        final var current = value.strip();
        try {
            return Duration.ofSeconds(Long.parseLong(current));
        } catch (final NumberFormatException nfe) {
            // not a bare number of seconds, so it has to be the ISO-8601 form
        }
        try {
            return Duration.parse(current);
        } catch (final DateTimeParseException dtpe) {
            throw new IllegalArgumentException("Invalid duration '" + value
                    + "', use a number of seconds or an ISO-8601 duration such as PT0.5S, PT30S, PT5M or PT1H");
        }
    }

    /**
     * @param value    a duration, {@code null} or blank to get {@code fallback}.
     * @param fallback what an unset attribute means, and what a value which is not a positive duration gets too.
     * @return the parsed duration when it is positive, else {@code fallback} - a timeout of zero would fire before the
     * work it guards even started, and the HTTP client refuses one outright.
     */
    public static Duration positive(final String value, final Duration fallback) {
        final var parsed = parse(value, fallback);
        return parsed.isZero() || parsed.isNegative() ? fallback : parsed;
    }
}
