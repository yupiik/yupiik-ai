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
package io.yupiik.ia.agent.documentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.configuration.JsonConfigurationSource;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationJsonSchemaGeneratorTest {
    private static final Pattern JSON_BLOCK = Pattern.compile("(?s)\\[source,json\\]\\s*----\\s*(.*?)\\s*----");

    @Test
    void schemaMatchesRuntimeConfigurationAndAuthoredExamples(@TempDir final Path temp) throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final var pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        final var excludedRoots = XPathFactory.newInstance()
                .newXPath()
                .evaluate(
                        "/project/build/plugins/plugin[artifactId='yupiik-tools-maven-plugin']/configuration/preActions/"
                                + "preAction[type='io.yupiik.ia.agent.documentation.ConfigurationJsonSchemaGenerator']"
                                + "/configuration/excludedRoots",
                        pom);
        assertFalse(excludedRoots.isBlank(), "Use the minisite's schema root selection");
        new ConfigurationJsonSchemaGenerator(
                        temp,
                        Map.of("output", "schema.json", "partialOutput", "copy.json", "excludedRoots", excludedRoots))
                .run();
        try (final var container = ConfiguringContainer.of()
                        .register(new SourceModule("{}"))
                        .start();
                final var mapper = container.lookup(JsonMapper.class)) {
            final var schema = mapper.instance().fromString(Map.class, Files.readString(temp.resolve("schema.json")));
            final var validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(Files.readString(temp.resolve("schema.json")));
            for (final var json : new String[] {
                "{\"provider\":{\"api\":\"auto\",\"model\":\"audit\"},\"mode\":\"plan\"}",
                "{\"yca\":{\"provider\":{\"api\":\"AUTO\"}},\"$inherit\":true}",
                "{\"$inherit\":false,\"yca\":{\"memory\":{\"maxRatio\":0.2}}}"
            }) {
                final var errors = validator.validate(json, InputFormat.JSON);
                assertTrue(errors.isEmpty(), () -> json + "\n" + errors);
            }
            assertFalse(validator
                    .validate("{\"unknownSetting\":true}", InputFormat.JSON)
                    .isEmpty());
            assertFalse(validator
                    .validate("{\"$inherit\":\"true\"}", InputFormat.JSON)
                    .isEmpty());
            final var page = Files.readString(Path.of("src/main/minisite/content/coding-agent/configuration.adoc"));
            final var examples = JSON_BLOCK.matcher(page);
            int validated = 0;
            while (examples.find()) {
                final var json = examples.group(1);
                if (json.equals("include::content/coding-agent/_partials/generated/configuration.jsonschema.json[]")) {
                    continue; // This block displays the schema itself, not a configuration example.
                }
                final var errors = validator.validate(json, InputFormat.JSON);
                assertTrue(errors.isEmpty(), () -> json + "\n" + errors);
                // Each discovered example owns its source and generated binding until all assertions finish.
                try (final var scenario = ConfiguringContainer.of()
                                .register(new SourceModule(json))
                                .start();
                        final var configuration = scenario.lookup(CodingAgentConfiguration.class)) {
                    final var bound = configuration.instance();
                    if (json.contains("command-auth")) {
                        assertEquals(1, bound.provider().providers().size());
                        final var configured = bound.provider().providers().getFirst();
                        assertEquals("command-auth", configured.id());
                        assertEquals(1, configured.dynamicHeaders().size());
                        assertTrue(
                                configured.dynamicHeaders().get("authorization").startsWith("Bearer {{exec"));
                    }
                }
                validated++;
            }
            assertTrue(validated >= 5, "Validate the authored configuration examples, not isolated fixtures");
            final var defs = map(schema.get("$defs"));
            assertEquals(
                    0.2,
                    ((Number) map(map(map(defs.get("Memory")).get("properties")).get("maxRatio"))
                                    .get("default"))
                            .doubleValue());
            final var slackContext =
                    resolve(defs, map(map(schema.get("properties")).get("slackContext")));
            final var slackContextProperties = map(slackContext.get("properties"));
            assertEquals("boolean", map(slackContextProperties.get("enabled")).get("type"));
            assertEquals(false, map(slackContextProperties.get("enabled")).get("default"));
            assertEquals("string", map(slackContextProperties.get("directory")).get("type"));
            assertEquals("string", map(slackContextProperties.get("channel")).get("type"));
            assertEquals(
                    "integer", map(slackContextProperties.get("timeoutSeconds")).get("type"));
            final var slack = resolve(defs, map(map(schema.get("properties")).get("slack")));
            final var channelMembers = map(map(slack.get("properties")).get("allowChannelMembers"));
            assertEquals("boolean", channelMembers.get("type"));
            assertEquals(false, channelMembers.get("default"));
            assertFalse(map(schema.get("properties")).containsKey("github"));
            assertFalse(map(schema.get("properties")).containsKey("githubContext"));
            assertFalse(defs.keySet().stream().anyMatch(key -> key.toString().contains("GithubBot")));
            assertFalse(defs.keySet().stream()
                    .anyMatch(key -> key.toString().contains("AgentContextConfiguration")
                            || key.toString().contains("FixConfiguration")
                            || key.toString().contains("github.configuration.PromptConfiguration")
                            || key.toString().contains("ReviewPresentationConfiguration")));
            final var yca = map(map(map(schema.get("properties")).get("yca")).get("properties"));
            final var notifications = resolve(defs, map(yca.get("notifications")));
            final var notificationProperties = map(notifications.get("properties"));
            assertEquals("boolean", map(notificationProperties.get("enabled")).get("type"));
            assertEquals(false, map(notificationProperties.get("enabled")).get("default"));
            assertEquals("array", map(notificationProperties.get("command")).get("type"));
            assertEquals(
                    "string",
                    map(map(notificationProperties.get("command")).get("items")).get("type"));
            final var on = resolve(defs, map(notificationProperties.get("on")));
            assertEquals("object", on.get("type"));
            final var onProperties = map(on.get("properties"));
            assertEquals(Set.of("event", "tools"), onProperties.keySet());
            for (final var group : Map.of("event", Set.of("turnEnd", "approval"), "tools", Set.of("ask_user", "btw"))
                    .entrySet()) {
                final var groupSchema = resolve(defs, map(onProperties.get(group.getKey())));
                assertEquals("object", groupSchema.get("type"));
                final var switches = map(groupSchema.get("properties"));
                assertEquals(group.getValue(), switches.keySet());
                for (final var setting : switches.values()) {
                    assertEquals("boolean", map(setting).get("type"));
                    assertEquals(true, map(setting).get("default"));
                }
            }
            assertFalse(notificationProperties.containsKey("onInteraction"));
            final var ycaBot = map(map(yca.get("bot")).get("properties"));
            assertFalse(ycaBot.containsKey("admission"), "Host admission is not a yca.* configuration root");
            final var feedback = resolve(defs, map(ycaBot.get("feedback")));
            final var feedbackProperties = map(feedback.get("properties"));
            assertEquals("boolean", map(feedbackProperties.get("enabled")).get("type"));
            assertEquals(
                    "integer", map(feedbackProperties.get("timeoutSeconds")).get("type"));
            assertEquals("string", map(feedbackProperties.get("directory")).get("type"));
            final var bot = map(map(map(schema.get("properties")).get("bot")).get("properties"));
            final var admission = resolve(defs, map(bot.get("admission")));
            final var admissionProperties = map(admission.get("properties"));
            assertEquals("string", map(admissionProperties.get("root")).get("type"));
            assertEquals("integer", map(admissionProperties.get("slots")).get("type"));
            assertEquals("string", map(admissionProperties.get("trustedGroup")).get("type"));
            final var nativeProperties = map(map(yca.get("native")).get("properties"));
            final var nativeProvider = resolve(defs, map(nativeProperties.get("provider")));
            assertEquals(
                    "string",
                    map(map(nativeProvider.get("properties")).get("socket")).get("type"));
            final var provider = resolve(defs, map(yca.get("provider")));
            final var budget = resolve(defs, map(map(provider.get("properties")).get("budget")));
            final var budgetProperties = map(budget.get("properties"));
            for (final var name : new String[] {"root", "id", "slots"}) {
                assertEquals("string", map(budgetProperties.get(name)).get("type"), name);
            }
            assertTrue(map(budgetProperties.get("slots"))
                    .get("description")
                    .toString()
                    .contains("fail closed"));
            assertTrue(
                    map(provider.get("properties")).containsKey("contextWindow"),
                    "Adding yca.provider.budget must preserve the existing provider schema properties");
            assertTrue(map(map(provider.get("properties")).get("contextWindow"))
                    .get("description")
                    .toString()
                    .contains("falls back to 32000"));
            final var providers = map(map(provider.get("properties")).get("providers"));
            final var entry = resolve(defs, map(providers.get("items")));
            assertTrue(map(map(entry.get("properties")).get("contextWindow"))
                    .get("description")
                    .toString()
                    .contains("falls back to 32000"));
            final var tui = resolve(defs, map(yca.get("tui")));
            final var properties = map(tui.get("properties"));
            assertTrue(properties.containsKey("accent"));
            assertTrue(properties.containsKey("history"));
            final var theme = resolve(defs, map(properties.get("theme")));
            assertEquals("string", map(map(theme.get("properties")).get("name")).get("type"));
        }
    }

    @Nested
    @FusionSupport(modules = LowercaseSourceModule.class)
    class Lowercase {
        @Test
        void bindsEnums(@Fusion final CodingAgentConfiguration bound) {
            assertEquals(ApiFlavor.AUTO, bound.provider().api());
            assertEquals(AgentMode.PLAN, bound.mode());
        }
    }

    @Nested
    @FusionSupport(modules = UppercaseSourceModule.class)
    class Uppercase {
        @Test
        void bindsEnums(@Fusion final CodingAgentConfiguration bound) {
            assertEquals(ApiFlavor.AUTO, bound.provider().api());
            assertEquals(AgentMode.PLAN, bound.mode());
        }
    }

    public static class LowercaseSourceModule extends SourceModule {
        public LowercaseSourceModule() {
            super("{\"provider\":{\"api\":\"auto\"},\"mode\":\"plan\"}");
        }
    }

    public static class UppercaseSourceModule extends SourceModule {
        public UppercaseSourceModule() {
            super("{\"provider\":{\"api\":\"AUTO\"},\"mode\":\"PLAN\"}");
        }
    }

    private static class SourceModule implements FusionModule {
        private final ConfigurationSource source;

        private SourceModule(final String json) {
            source = new JsonConfigurationSource(new StringReader(json), key -> {
                final var shortKey = key.startsWith("yca.") ? key.substring(4) : key;
                return shortKey.replace('-', '.');
            });
        }

        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.of(new ProvidedInstanceBean<>(DefaultScoped.class, ConfigurationSource.class, () -> source));
        }

        @Override
        public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
            return (container, bean) -> !(bean.type() instanceof Class<?> type)
                    || !ConfigurationSource.class.isAssignableFrom(type)
                    || bean instanceof ProvidedInstanceBean<?>;
        }

        @Override
        public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
            return (container, listener) -> false;
        }
    }

    private Map<?, ?> resolve(final Map<?, ?> defs, final Map<?, ?> reference) {
        final var ref = (String) reference.get("$ref");
        if (ref == null) {
            assertTrue(
                    reference.containsKey("properties"), "Expected an inline object schema or a definition reference");
            return reference;
        }
        assertTrue(ref.startsWith("#/$defs/"));
        return map(defs.get(ref.substring("#/$defs/".length())));
    }

    private Map<?, ?> map(final Object value) {
        return (Map<?, ?>) value;
    }
}
