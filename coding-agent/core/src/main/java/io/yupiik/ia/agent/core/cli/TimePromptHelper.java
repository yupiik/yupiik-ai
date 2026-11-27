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
package io.yupiik.ia.agent.core.cli;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Overrides the built-in {@code {{time}}} helper: renders the current time (default {@code HH:mm:ss}) in the
 * JVM default timezone (the end-user local time), or {@code {{time pattern="..."}} with any java.time pattern
 * (e.g. {@code yyyy-MM-dd HH:mm:ss.SSS}). Display-only: the rendering deliberately uses
 * {@link Clock#systemDefaultZone()} and is independent of the internal UTC {@code Clock} bean used by
 * sessions/compaction/the LLM payload.
 */
@ApplicationScoped
public class TimePromptHelper implements PromptHandlebarsHelper {
    private static final String DEFAULT_TIME_PATTERN = "HH:mm:ss";

    private final Clock clock = Clock.systemDefaultZone();
    private final ConcurrentMap<String, DateTimeFormatter> formatters = new ConcurrentHashMap<>();

    public TimePromptHelper() {
        // DI instantiates the helper directly (no parameterized constructor) and tests
        // construct it explicitly; the rendering always uses the JVM default zone.
    }

    @Override
    public String name() {
        return "time";
    }

    @Override
    public String description() {
        return "the current time in the JVM default timezone (end-user local time, HH:mm:ss), or with any java.time pattern when given as {{time pattern=\"...\"}}";
    }

    @Override
    public Object apply(final HelperContext context) {
        if (context.args().size() > 1) {
            throw new IllegalArgumentException(
                    "time expects 0 or 1 argument, got " + context.args().size());
        }
        final var pattern = context.hash().getOrDefault("pattern", DEFAULT_TIME_PATTERN);
        final var value =
                context.args().isEmpty() ? clock.instant() : context.args().get(0);
        return format(value, pattern.toString());
    }

    private String format(final Object value, final String pattern) {
        final var formatter = formatters.computeIfAbsent(pattern, DateTimeFormatter::ofPattern);
        if (value instanceof Instant instant) {
            return formatter.withZone(clock.getZone()).format(instant);
        }
        if (value instanceof Date date) {
            return formatter.withZone(clock.getZone()).format(date.toInstant());
        }
        if (value instanceof Number number) {
            return formatter.withZone(clock.getZone()).format(Instant.ofEpochMilli(number.longValue()));
        }
        if (value instanceof LocalDate localDate) {
            return formatter.format(localDate);
        }
        if (value instanceof LocalTime localTime) {
            return formatter.format(localTime);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return formatter.format(localDateTime);
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return formatter.format(offsetDateTime);
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return formatter.format(zonedDateTime);
        }
        throw new IllegalArgumentException("Unsupported time value: " + value + " ("
                + (value == null ? "null" : value.getClass().getName()) + ")");
    }
}
