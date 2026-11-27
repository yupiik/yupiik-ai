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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class CliPromptTemplateTest {
    private static final Pattern TIME_ONLY = Pattern.compile("\\d{2}:\\d{2}:\\d{2}");
    private static final Pattern SEPARATOR = Pattern.compile("\\|");
    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** Synthetic palette so tests do not depend on the real ANSI codes. */
    private static final Map<String, Object> COLORS = Map.of(
            "accent",
            "[accent]",
            "success",
            "[success]",
            "error",
            "[error]",
            "warning",
            "[warning]",
            "status",
            "[status]",
            "info",
            "[info]",
            "dim",
            "[dim]",
            "bold",
            "[bold]",
            "model",
            "[model]",
            "reset",
            "[reset]");

    private CliPromptTemplate template(final CodingAgentConfiguration configuration) {
        return new CliPromptTemplate(
                List.of(new TimePromptHelper(), new FormatDatePromptHelper(Clock.systemDefaultZone())),
                configuration,
                mapper);
    }

    private static Map<String, Object> data(final String mode, final String permission, final String agent) {
        return data(mode, permission, agent, 42, COLORS);
    }

    private static Map<String, Object> data(
            final String mode,
            final String permission,
            final String agent,
            final int contextPercentage,
            final Map<String, Object> colors) {
        final var model = new LinkedHashMap<String, Object>();
        model.put("name", "gpt-x");
        model.put("contextPercentage", contextPercentage);
        model.put("contextTokens", 1000);
        model.put("contextWindow", 8000);
        final var data = new LinkedHashMap<String, Object>();
        data.put("mode", mode);
        data.put("modelName", "gpt-x");
        data.put("permission", permission);
        data.put("agent", agent);
        data.put("model", model);
        data.put("prune", 65);
        data.put("compact", 80);
        data.put("color", !colors.isEmpty());
        data.put("colors", colors);
        return data;
    }

    @Nested
    @FusionSupport(modules = ConfigurationTestModule.class)
    class Defaults {
        @Fusion
        CodingAgentConfiguration configuration;

        @Test
        void defaultTemplateRendersPlainWithoutAgentOrPermission() {
            assertEquals("[plan@gpt-x] 42%> ", template(configuration).render(data("plan", null, "", 42, Map.of())));
        }

        @Test
        void defaultTemplateRendersPermissionAndAgentWhenPresent() {
            assertEquals(
                    "[build@gpt-x] (yolo) [reviewer] 42%> ",
                    template(configuration).render(data("build", "yolo", "reviewer", 42, Map.of())));
        }

        @Test
        void defaultTemplateColorizesWithPalette() {
            final var rendered = template(configuration).render(data("plan", "yolo", "reviewer"));
            assertTrue(rendered.contains("[accent]plan[reset]@"), "expected accent-wrapped mode: " + rendered);
            assertTrue(rendered.contains("[status]yolo[reset])"), "expected status-wrapped permission: " + rendered);
            assertTrue(rendered.contains("[model]gpt-x[reset]]"), "expected model-wrapped name: " + rendered);
            assertTrue(rendered.contains("[warning]42%[reset]> "), "expected warning-wrapped context: " + rendered);
        }

        @Test
        void defaultTemplateContextThresholds() {
            final var cli = template(configuration);
            assertTrue(cli.render(data("plan", null, "", 25, COLORS)).contains("[dim]25%[reset]> "));
            assertTrue(cli.render(data("plan", null, "", 55, COLORS)).contains("[warning]55%[reset]> "));
            assertTrue(cli.render(data("plan", null, "", 80, COLORS)).contains("[error]80%[reset]> "));
        }
    }

    @Nested
    @FusionSupport(modules = MissingPaletteModule.class)
    class MissingPalette {
        @Test
        void lookupMissRendersEmpty(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals("[pre]", template(configuration).render(data("plan", null, "", 42, COLORS)));
        }
    }

    @Nested
    @FusionSupport(modules = TimeModule.class)
    class Time {
        @Test
        void timeHelpersRenderNowInTheJvmDefaultZone(@Fusion final CodingAgentConfiguration configuration) {
            final var parts = SEPARATOR.split(template(configuration).render(data("plan", null, "")));
            assertEquals(2, parts.length);
            assertTrue(TIME_ONLY.matcher(parts[0]).matches(), parts[0]);
            assertRecent(parts[1]);
        }
    }

    @Nested
    @FusionSupport(modules = FormatDateModule.class)
    class FormatDate {
        @Test
        void formatDateHelperRendersNowInTheJvmDefaultZone(@Fusion final CodingAgentConfiguration configuration) {
            final var parts = SEPARATOR.split(template(configuration).render(data("plan", null, "")));
            assertEquals(2, parts.length);
            assertTrue(TIME_ONLY.matcher(parts[0]).matches(), parts[0]);
            assertRecent(parts[1]);
        }
    }

    private static void assertRecent(final String rendered) {
        final var parsed = LocalDateTime.parse(rendered, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
                .atZone(ZoneOffset.UTC)
                .toInstant();
        assertTrue(Duration.between(parsed, Instant.now()).abs().toMillis() < 5_000, rendered);
    }

    @Nested
    @FusionSupport(modules = ContextModule.class)
    class Context {
        @Test
        void contextPercentageZeroBeforeFirstTurn(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals("0", template(configuration).render(data("plan", null, "", 0, COLORS)));
        }
    }

    @Nested
    @FusionSupport(modules = PlaceholdersModule.class)
    class Placeholders {
        @Test
        void modeAndModelNamePlaceholders(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals("plan|gpt-x|gpt-x", template(configuration).render(data("plan", null, "")));
        }
    }

    @Nested
    @FusionSupport(modules = ConfigurationKeyModule.class)
    class ConfigurationKey {
        @Test
        void configurationKeyExposesSerializedConfig(@Fusion final CodingAgentConfiguration configuration) {
            final var cli = template(configuration);
            final var parts = SEPARATOR.split(cli.render(data("plan", null, "")), 5);
            assertEquals(5, parts.length);
            assertEquals("yes", parts[0]);
            assertEquals("plan", parts[1]);
            assertTrue(parts[2].startsWith("\u001b[38;5;141m"), "expected ANSI accent from config: " + parts[2]);
            assertEquals("\u001b[0m", parts[3], "expected ANSI reset from config");
            assertEquals("t:" + configuration.prompt().template(), parts[4]);
            assertTrue(cli.helpers().stream().map(PromptHandlebarsHelper::name).anyMatch("time"::equals));
        }
    }

    public static class MissingPaletteModule extends ConfigurationTestModule {
        public MissingPaletteModule() {
            super(Map.of("yca.prompt.template", "[{{lookup colors \"missing\"}}pre]"));
        }
    }

    public static class TimeModule extends ConfigurationTestModule {
        public TimeModule() {
            super(Map.of("yca.prompt.template", "{{time}}|{{time pattern=\"yyyy-MM-dd HH:mm:ss.SSS\"}}"));
        }
    }

    public static class FormatDateModule extends ConfigurationTestModule {
        public FormatDateModule() {
            super(Map.of("yca.prompt.template", "{{formatDate}}|{{formatDate \"yyyy-MM-dd HH:mm:ss.SSS\"}}"));
        }
    }

    public static class ContextModule extends ConfigurationTestModule {
        public ContextModule() {
            super(Map.of("yca.prompt.template", "{{{model.contextPercentage}}}"));
        }
    }

    public static class PlaceholdersModule extends ConfigurationTestModule {
        public PlaceholdersModule() {
            super(Map.of("yca.prompt.template", "{{{mode}}}|{{{modelName}}}|{{{model.name}}}"));
        }
    }

    public static class ConfigurationKeyModule extends ConfigurationTestModule {
        public ConfigurationKeyModule() {
            super(
                    Map.of(
                            "yca.prompt.template",
                            "{{#if configuration}}yes{{/if}}|{{{mode}}}|{{{configuration.prompt.colors.accent}}}|{{{configuration.prompt.colors.reset}}}|t:{{{configuration.prompt.template}}}"));
        }
    }
}
