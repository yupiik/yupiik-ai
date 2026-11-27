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

import io.yupiik.ia.agent.core.configuration.SessionCleanConfiguration;
import java.time.Duration;
import java.util.regex.Pattern;

/** Validated, conjunctive criteria for an explicit session cleanup. Null thresholds are disabled. */
public record SessionCleanPolicy(Duration olderThan, Long biggerThanBytes) {
    private static final Pattern DURATION = Pattern.compile("([0-9]+)(s|m|h|d|w)");
    private static final Pattern SIZE = Pattern.compile("([0-9]+)(B|K|M|G|T|KB|MB|GB|TB|KiB|MiB|GiB|TiB)?");

    public SessionCleanPolicy {
        if (olderThan != null && (olderThan.isNegative() || olderThan.isZero())) {
            throw new IllegalArgumentException("older-than must be positive");
        }
        if (biggerThanBytes != null && biggerThanBytes <= 0) {
            throw new IllegalArgumentException("bigger-than must be positive");
        }
        if (olderThan != null) {
            try {
                olderThan.toMillis();
            } catch (final ArithmeticException overflow) {
                throw new IllegalArgumentException("older-than is too large", overflow);
            }
        }
    }

    /** Explicit CLI criteria replace the configured criteria as a set, not field by field. */
    public static SessionCleanPolicy resolve(
            final SessionCleanConfiguration configuration, final String olderThan, final String biggerThan) {
        if (olderThan != null || biggerThan != null) {
            return new SessionCleanPolicy(
                    olderThan == null ? null : duration(olderThan), biggerThan == null ? null : size(biggerThan));
        }
        return new SessionCleanPolicy(
                configuration == null || configuration.retentionDays() <= 0
                        ? null
                        : Duration.ofDays(configuration.retentionDays()),
                configuration == null || configuration.biggerThan() == null ? null : size(configuration.biggerThan()));
    }

    public boolean enabled() {
        return olderThan != null || biggerThanBytes != null;
    }

    public boolean matches(final long now, final long modifiedAt, final long sizeBytes) {
        // Subtract from now rather than from an untrusted file timestamp (which can be in the future).
        return enabled()
                && (olderThan == null || modifiedAt < Math.subtractExact(now, olderThan.toMillis()))
                && (biggerThanBytes == null || sizeBytes > biggerThanBytes);
    }

    private static Duration duration(final String value) {
        final var matcher = DURATION.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Invalid older-than '" + value + "': use a positive integer and s, m, h, d or w");
        }
        try {
            final var count = Long.parseLong(matcher.group(1));
            final var seconds =
                    switch (matcher.group(2)) {
                        case "s" -> count;
                        case "m" -> Math.multiplyExact(count, 60);
                        case "h" -> Math.multiplyExact(count, 3600);
                        case "d" -> Math.multiplyExact(count, 86400);
                        case "w" -> Math.multiplyExact(count, 604800);
                        default -> throw new IllegalArgumentException("Invalid duration unit");
                    };
            return Duration.ofSeconds(seconds);
        } catch (final ArithmeticException | NumberFormatException overflow) {
            throw new IllegalArgumentException("older-than is too large: " + value, overflow);
        }
    }

    private static long size(final String value) {
        final var matcher = SIZE.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Invalid bigger-than '" + value + "': use bytes, K/M/G/T, KB/MB/GB/TB or KiB/MiB/GiB/TiB");
        }
        try {
            final var unit = matcher.group(2);
            final var power = unit == null || "B".equals(unit)
                    ? 0
                    : switch (unit.charAt(0)) {
                        case 'K' -> 1;
                        case 'M' -> 2;
                        case 'G' -> 3;
                        case 'T' -> 4;
                        default -> throw new IllegalArgumentException("Invalid size unit");
                    };
            var bytes = Long.parseLong(matcher.group(1));
            for (var i = 0; i < power; i++) {
                bytes = Math.multiplyExact(bytes, unit.contains("i") ? 1024 : 1000);
            }
            return bytes;
        } catch (final ArithmeticException | NumberFormatException overflow) {
            throw new IllegalArgumentException("bigger-than is too large: " + value, overflow);
        }
    }
}
