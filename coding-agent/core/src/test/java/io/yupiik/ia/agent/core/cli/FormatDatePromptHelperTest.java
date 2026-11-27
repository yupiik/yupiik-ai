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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class FormatDatePromptHelperTest {
    private static final Pattern TIME_ONLY = Pattern.compile("\\d{2}:\\d{2}:\\d{2}");

    private static String render(final String template) {
        final var functions = new LinkedHashMap<String, Function<HelperContext, Object>>();
        functions.put("formatDate", new FormatDatePromptHelper(Clock.systemDefaultZone())::apply);
        return new HandlebarsCompiler()
                .compile(new HandlebarsCompiler.CompilationContext(
                        new HandlebarsCompiler.Settings().helpers(functions), template))
                .render(Map.of());
    }

    @Test
    void defaultPatternIsTimeOnly() {
        final var rendered = render("{{formatDate}}");
        assertTrue(TIME_ONLY.matcher(rendered).matches(), rendered);
    }

    @Test
    void positionalPatternIsApplied() {
        // the pattern decides the shape; the value is the current instant rendered in the JVM
        // default zone (surefire pins it to UTC for the whole suite) - only proximity to now matters
        final var rendered = render("{{formatDate \"yyyy-MM-dd HH:mm:ss.SSS\"}}");
        final var parsed = LocalDateTime.parse(rendered, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
                .atZone(ZoneOffset.UTC)
                .toInstant();
        final var delta = Duration.between(parsed, Instant.now()).abs();
        assertTrue(delta.toMillis() < 5_000, rendered);
    }

    @Test
    void rendersInTheJvmDefaultZone() {
        // the rendered offset must be the JVM default zone, not a hardcoded one; the suite
        // pins that zone to UTC (surefire argLine) so the assertion is machine-independent
        final var rendered = render("{{formatDate \"yyyy-MM-dd HH:mm:ssXXX\"}}");
        final var parsed = OffsetDateTime.parse(rendered, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX"));
        assertEquals(ZoneOffset.UTC, parsed.getOffset(), rendered);
    }

    @Test
    void blankPatternFallsBackToTheDefault() {
        final var rendered = render("{{formatDate \"\"}}");
        assertTrue(TIME_ONLY.matcher(rendered).matches(), rendered);
    }

    @Test
    void tooManyArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> render("{{formatDate \"HH:mm:ss\" \"extra\"}}"));
    }

    @Test
    void inlineCallRendersInThePromptLine() {
        final var rendered = render("t:{{formatDate}}");
        assertTrue(TIME_ONLY.matcher(rendered.substring(2)).matches(), rendered);
        assertEquals('t', rendered.charAt(0));
        assertEquals(':', rendered.charAt(1));
    }
}
