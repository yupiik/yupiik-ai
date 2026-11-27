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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * The A2A timestamps are {@code google.protobuf.Timestamp} in the proto, i.e. ISO-8601 UTC strings with millisecond
 * precision in JSON ({@code YYYY-MM-DDTHH:mm:ss.sssZ}).
 * <p>
 * They are kept as {@code String} in the model - and not as a {@code java.time} type - so what is put on the wire is
 * exactly what the specification asks for whatever the JSON mapper does with dates.
 */
public final class A2ATimestamps {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private A2ATimestamps() {
        // no-op
    }

    /**
     * @param instant the instant to format.
     * @return {@code instant} as an ISO-8601 UTC string with a millisecond precision, {@code null} for {@code null}.
     */
    public static String format(final Instant instant) {
        return instant == null ? null : FORMATTER.format(instant.atOffset(ZoneOffset.UTC));
    }

    /**
     * Parses a client provided timestamp, it is lenient on the precision and the offset - only the resulting instant
     * matters - since it is used for comparisons and not to be sent back.
     *
     * @param timestamp an ISO-8601 timestamp.
     * @return the matching instant or {@code null} when {@code timestamp} is {@code null} or blank.
     * @throws IllegalArgumentException when {@code timestamp} is not an ISO-8601 date time.
     */
    public static Instant parse(final String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(timestamp.strip()).toInstant();
        } catch (final DateTimeParseException dtpe) {
            throw new IllegalArgumentException("Invalid ISO-8601 timestamp '" + timestamp + "'", dtpe);
        }
    }
}
