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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registers the {@code {{formatDate}}} helper: renders the current date/time in the
 * JVM default timezone (the end-user local time), {@code {{formatDate}}} using the default
 * {@code HH:mm:ss} pattern or {@code {{formatDate "yyyy-MM-dd HH:mm:ss"}}} with any java.time
 * pattern. Display-only: the timezone is forced to {@link Clock#systemDefaultZone()} regardless of
 * the injected {@link Clock} (DI injects the internal UTC {@code Clock} bean used by
 * sessions/compaction/the LLM payload, whose timezone must not leak into end-user timestamps).
 * Tests inject a fixed {@link Clock} so the rendered value is deterministic. The injected clock's
 * instant is kept as-is (it reads the same hardware clock), only its zone is re-based on the JVM
 * default. Formatters are cached per pattern (created once per JVM, never recompiled per render).
 */
@ApplicationScoped
public class FormatDatePromptHelper implements PromptHandlebarsHelper {
    private static final String DEFAULT_PATTERN = "HH:mm:ss";

    private final Clock clock;
    private final ConcurrentMap<String, DateTimeFormatter> formatters = new ConcurrentHashMap<>();

    /**
     * DI instantiates the helper with the app-wide {@link Clock} bean (Fusion resolves it
     * automatically); the instance's timezone is re-based on the JVM default zone so display always
     * reads as end-user local time. A {@code null} clock (never produced by DI, only a manual
     * caller) falls back to {@link Clock#systemDefaultZone()} so production cannot render in a
     * foreign zone by accident.
     */
    public FormatDatePromptHelper(final Clock clock) {
        this.clock = clock == null ? Clock.systemDefaultZone() : clock.withZone(ZoneId.systemDefault());
    }

    @Override
    public String name() {
        return "formatDate";
    }

    @Override
    public String description() {
        return "the current date/time in the JVM default timezone (end-user local time): {{formatDate}} (HH:mm:ss) or with any java.time pattern when given as {{formatDate \"pattern\"}}";
    }

    @Override
    public Object apply(final HelperContext context) {
        return applyAt(context, clock.millis());
    }

    /** Label rendering uses a captured instant; null deliberately means unknown legacy time. */
    public Object applyAt(final HelperContext context, final Long timestamp) {
        if (context.args().size() > 1) {
            throw new IllegalArgumentException("formatDate expects 0 or 1 argument (a pattern), got "
                    + context.args().size());
        }
        // a bare {{formatDate}} call passes the current data as its single argument (the engine routes
        // non-default helpers that way) - only a String is a pattern, anything else means "now"
        final var arg = context.args().isEmpty() ? null : context.args().get(0);
        final var pattern = arg instanceof String s ? s : DEFAULT_PATTERN;
        final var effective = pattern == null || pattern.isBlank() ? DEFAULT_PATTERN : pattern;
        if (timestamp == null) {
            return "";
        }
        return formatters
                .computeIfAbsent(effective, DateTimeFormatter::ofPattern)
                .withZone(clock.getZone())
                .format(Instant.ofEpochMilli(timestamp));
    }
}
