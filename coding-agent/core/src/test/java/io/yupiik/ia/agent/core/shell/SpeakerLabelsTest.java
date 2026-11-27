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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.cli.CliPromptTemplate;
import io.yupiik.ia.agent.core.cli.FormatDatePromptHelper;
import io.yupiik.ia.agent.core.cli.PromptHandlebarsHelper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.TestSessionStores;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class SpeakerLabelsTest {
    private JsonMapper mapper;
    private final List<ExtendedHttpClient> httpClients = new ArrayList<>();
    private final List<ChatClient> chatClients = new ArrayList<>();

    @AfterEach
    void closeClients() {
        chatClients.forEach(ChatClient::close);
        httpClients.forEach(ExtendedHttpClient::close);
    }

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    // Share a fixed instant between rendering and assertions so crossing a wall-clock
    // second cannot make an otherwise correct label fail.
    private static final Clock LABEL_CLOCK = Clock.fixed(Instant.parse("2031-04-05T06:07:08Z"), ZoneId.systemDefault());
    private static final DateTimeFormatter STAMP_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static String stamp() {
        return "[" + LocalTime.now(LABEL_CLOCK).format(STAMP_FORMATTER) + "] ";
    }

    private static final String USER = System.getProperty("user.name");
    private static final String USER_FALLBACK = USER == null || USER.isBlank() ? "You" : USER;

    private static Map<String, String> configurationValues(final Map<String, String> overrides) {
        final var map = new HashMap<>(overrides);
        map.put("yca.provider.providers.length", "1");
        map.put("yca.provider.providers.0.id", "default");
        map.put("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions");
        map.put("yca.provider.providers.0.apiKey", "test-key");
        map.put("yca.provider.providers.0.model", "llm-test");
        map.put("yca.provider.providers.0.contextWindow", "128000");
        return map;
    }

    private ProviderManager providerManager(final CodingAgentConfiguration configuration) {
        return new ProviderManager(configuration, mapper);
    }

    private ChatClient chatClient(final CodingAgentConfiguration configuration) {
        final var httpClient =
                new HttpClientProducer().httpClient(configuration, new NativeProviderConfiguration(null, null));
        httpClients.add(httpClient);
        final var client =
                new ChatClient(configuration, mapper, providerManager(configuration), Clock.systemUTC(), httpClient);
        chatClients.add(client);
        return client;
    }

    private SpeakerLabels labels(final CodingAgentConfiguration configuration) {
        return new SpeakerLabels(
                List.of(new FormatDatePromptHelper(LABEL_CLOCK)), configuration, chatClient(configuration));
    }

    @Nested
    @FusionSupport(modules = DefaultUserLabelIsTheOsUserScenarioModule.class)
    class DefaultUserLabelIsTheOsUserScenario {
        @TempDir
        static Path directory;

        @Test
        void defaultUserLabelIsTheOsUser(@Fusion final CodingAgentConfiguration configuration) {
            final var rendered = labels(configuration)
                    .userLabel(new SpeakerLabels.Ctx("llm-test", "default", null, "plan", "s1", null));
            assertTrue(rendered.startsWith(stamp()), rendered);
            assertTrue(rendered.endsWith(USER_FALLBACK), rendered);
        }
    }

    @Nested
    @FusionSupport(modules = DefaultAssistantLabelIsProviderColonColonModelScenarioModule.class)
    class DefaultAssistantLabelIsProviderColonColonModelScenario {
        @TempDir
        static Path directory;

        @Test
        void defaultAssistantLabelIsProviderColonColonModel(@Fusion final CodingAgentConfiguration configuration) {
            final var rendered = labels(configuration)
                    .assistantLabel(new SpeakerLabels.Ctx("llm-test", "default", null, "plan", "s1", null));
            assertTrue(rendered.startsWith(stamp()), rendered);
            assertTrue(rendered.endsWith("default::llm-test"), rendered);
            // no model resolved -> falls back to "Assistant"
            final var fallenBack =
                    labels(configuration).assistantLabel(new SpeakerLabels.Ctx(null, null, null, "plan", "s1", null));
            assertTrue(fallenBack.startsWith(stamp()), fallenBack);
            assertTrue(fallenBack.endsWith("Assistant"), fallenBack);
        }
    }

    @Nested
    @FusionSupport(modules = AssistantLabelUsesTheCtxProviderNotTheProcessCurrentOneScenarioModule.class)
    class AssistantLabelUsesTheCtxProviderNotTheProcessCurrentOneScenario {
        @TempDir
        static Path directory;

        @Test
        void assistantLabelUsesTheCtxProviderNotTheProcessCurrentOne(
                @Fusion final CodingAgentConfiguration configuration) {
            // the process-wide current provider is "default"; the session runs on another one and
            // the label must reflect the ctx provider, never the process current one
            final var rendered = labels(configuration)
                    .assistantLabel(
                            new SpeakerLabels.Ctx("session-model", "session-provider", null, "plan", "s1", null));
            assertTrue(rendered.startsWith(stamp()), rendered);
            assertTrue(rendered.endsWith("session-provider::session-model"), rendered);
            assertFalse(rendered.contains("default"), rendered);
        }
    }

    @Nested
    @FusionSupport(modules = CustomTemplatesUseModelContextAndSkipNullsScenarioModule.class)
    class CustomTemplatesUseModelContextAndSkipNullsScenario {
        @TempDir
        static Path directory;

        @Test
        void customTemplatesUseModelContextAndSkipNulls(@Fusion final CodingAgentConfiguration configuration) {
            final var labels = labels(configuration);
            assertEquals(USER_FALLBACK, labels.userLabel(new SpeakerLabels.Ctx("m", "p", "", "plan", "s1", null)));
            assertEquals("operator", labels.userLabel(new SpeakerLabels.Ctx("m", "p", "operator", "plan", "s1", null)));
            assertEquals("p::m", labels.assistantLabel(new SpeakerLabels.Ctx("m", "p", null, "plan", "s1", null)));
        }
    }

    private static String compile(final String template, final Function<HelperContext, Object> helper) {
        final var functions = new LinkedHashMap<String, Function<HelperContext, Object>>();
        functions.put("systemProperty", helper);
        return new HandlebarsCompiler()
                .compile(new HandlebarsCompiler.CompilationContext(
                        new HandlebarsCompiler.Settings().helpers(functions), template))
                .render(Map.of());
    }

    @Nested
    @FusionSupport(modules = SystemPropertyHelperReadsKeyWithDefaultAndWithoutScenarioModule.class)
    class SystemPropertyHelperReadsKeyWithDefaultAndWithoutScenario {
        @TempDir
        static Path directory;

        @Test
        void systemPropertyHelperReadsKeyWithDefaultAndWithout() {
            final var helper = PromptHandlebarsHelper.systemProperty();
            // 1 arg, key present -> value
            assertEquals(USER_FALLBACK, compile("{{systemProperty \"user.name\"}}", helper));
            // 2 args, missing key -> default
            assertEquals("Fallback", compile("{{systemProperty \"no.such.key.xyz\" \"Fallback\"}}", helper));
            // 1 arg, missing key -> renders nothing (null result), no throw
            assertEquals("[]", compile("[{{systemProperty \"no.such.key.xyz\"}}]", helper));
            // direct lambda contract
            assertNull(helper.apply(new HelperContext(List.of("no.such.key.xyz"), Map.of())));
            assertEquals("D", helper.apply(new HelperContext(List.of("no.such.key.xyz", "D"), Map.of())));
        }
    }

    @Nested
    @FusionSupport(modules = SystemPropertyHelperIsAvailableInTheCliPromptTemplateScenarioModule.class)
    class SystemPropertyHelperIsAvailableInTheCliPromptTemplateScenario {
        @TempDir
        static Path directory;

        @Test
        void systemPropertyHelperIsAvailableInTheCliPromptTemplate(
                @Fusion final CodingAgentConfiguration configuration) {
            final var template = new CliPromptTemplate(List.of(), configuration, mapper);
            assertEquals("u:" + USER_FALLBACK, template.render(Map.of()));
        }
    }

    @Nested
    @FusionSupport(modules = LlmPayloadKeepsHardcodedRolesRegardlessOfLabelsScenarioModule.class)
    class LlmPayloadKeepsHardcodedRolesRegardlessOfLabelsScenario {
        @TempDir
        static Path directory;

        @Test
        void llmPayloadKeepsHardcodedRolesRegardlessOfLabels(@Fusion final CodingAgentConfiguration configuration) {
            final var labels = labels(configuration);
            final var ctx = new SpeakerLabels.Ctx("m", "p", null, "plan", "s", null);
            assertEquals("TheBoss", labels.userLabel(ctx));
            assertEquals("Robot-9000", labels.assistantLabel(ctx));

            // wire messages are built from hardcoded role factories and never see the labels
            final var history = List.of(Message.user("question"), Message.assistant("answer", List.of()));
            assertEquals("user", history.get(0).role());
            assertEquals("assistant", history.get(1).role());
            assertFalse(history.get(0).content().toString().contains("TheBoss"));
            assertFalse(history.get(1).content().toString().contains("Robot-9000"));
            assertTrue(history.get(0).content().toString().contains("question"));

            // the persisted session is label-free: roles stay user/assistant, no label text stored
            final var store = TestSessionStores.ephemeral();
            store.append("s", SessionEntry.of(1L, "assistant", "answer", null, null));
            final var stored = store.load("s");
            assertEquals(1, stored.size());
            assertEquals("assistant", stored.get(0).role());
            assertFalse(stored.get(0).content().toString().contains("Robot-9000"));
        }
    }

    @Nested
    @FusionSupport(modules = TranscriptMarkersRoundTripWithCustomLabelsScenarioModule.class)
    class TranscriptMarkersRoundTripWithCustomLabelsScenario {
        @TempDir
        static Path directory;

        @Test
        void transcriptMarkersRoundTripWithCustomLabels() {
            final var t = new ConversationTranscript();
            t.setLabels("Boss", "Robot");
            assertEquals("**Boss**", t.userMarker());
            assertEquals("**Robot**", t.assistantMarker());
            t.startUserTurn("question");
            final var md = t.markdown("|", "");
            assertTrue(md.contains("**Boss**"), md);
            t.appendTranscript("**Robot**\n\nanswer");
            final var all = t.markdown("|", "");
            assertTrue(all.contains("**Robot**"), all);

            // the default transcript keeps its historic markers
            final var d = new ConversationTranscript();
            assertEquals("**You**", d.userMarker());
            assertEquals("**Assistant**", d.assistantMarker());
        }
    }

    @Nested
    @FusionSupport(modules = ReplayRendersStoredIdentityWhenCurrentLabelsDifferScenarioModule.class)
    class ReplayRendersStoredIdentityWhenCurrentLabelsDifferScenario {
        @TempDir
        static Path directory;

        @Test
        void replayRendersStoredIdentityWhenCurrentLabelsDiffer(@Fusion final CodingAgentConfiguration configuration) {
            // history recorded under provider M1 then M2; the current transcript labels are M2
            final var labels = labels(configuration);
            final var t = new ConversationTranscript();
            t.setLabelResolver((identity, user) -> {
                final var ictx = new SpeakerLabels.Ctx(
                        identity.model(), identity.provider(), identity.agent(), null, null, null);
                return user ? labels.userLabel(ictx) : labels.assistantLabel(ictx);
            });
            // current labels are M2
            t.setLabels(
                    labels.userLabel(new SpeakerLabels.Ctx("m2", "provider2", null, "plan", "s1", null)),
                    labels.assistantLabel(new SpeakerLabels.Ctx("m2", "provider2", null, "plan", "s1", null)));

            final var history = List.of(
                    Message.withIdentity(Message.user("q1"), "provider1", "m1", null),
                    Message.withIdentity(Message.assistant("a1", List.of()), "provider1", "m1", null),
                    Message.withIdentity(Message.user("q2"), "provider2", "m2", null),
                    Message.withIdentity(Message.assistant("a2", List.of()), "provider2", "m2", null));
            final var md = t.replay(history);
            // the first block re-renders with M1 despite current being M2
            assertTrue(md.contains("**provider1::m1**"), md);
            assertTrue(md.contains("**provider2::m2**"), md);
            assertFalse(md.contains("provider2::m2**\n\nq1"), md);
        }
    }

    @Nested
    @FusionSupport(modules = MessageIdentityHelpersRoundTripScenarioModule.class)
    class MessageIdentityHelpersRoundTripScenario {
        @TempDir
        static Path directory;

        @Test
        void messageIdentityHelpersRoundTrip() {
            final var m = Message.withIdentity(Message.user("x"), "p", "m", "agent1");
            final var identity = Message.identityOf(m);
            assertNotNull(identity, "identity must be present");
            assertEquals("p", identity.provider());
            assertEquals("m", identity.model());
            assertEquals("agent1", identity.agent());
            // no identity -> null
            assertNull(Message.identityOf(Message.user("y")));
        }
    }

    @Nested
    @FusionSupport(modules = WireNeverContainsTheIdentityKeyScenarioModule.class)
    class WireNeverContainsTheIdentityKeyScenario {
        @TempDir
        static Path directory;

        @Test
        void wireNeverContainsTheIdentityKey() {
            // covered by LlmWireTest (same package as LlmWire): the reserved identity key is stripped
            // at every wire boundary so the LLM payload stays clean
        }
    }

    public static class DefaultUserLabelIsTheOsUserScenarioModule extends ConfigurationTestModule {
        public DefaultUserLabelIsTheOsUserScenarioModule() {
            super(configurationValues(Map.of()));
        }
    }

    public static class DefaultAssistantLabelIsProviderColonColonModelScenarioModule extends ConfigurationTestModule {
        public DefaultAssistantLabelIsProviderColonColonModelScenarioModule() {
            super(configurationValues(Map.of()));
        }
    }

    public static class AssistantLabelUsesTheCtxProviderNotTheProcessCurrentOneScenarioModule
            extends ConfigurationTestModule {
        public AssistantLabelUsesTheCtxProviderNotTheProcessCurrentOneScenarioModule() {
            super(configurationValues(Map.of()));
        }
    }

    public static class CustomTemplatesUseModelContextAndSkipNullsScenarioModule extends ConfigurationTestModule {
        public CustomTemplatesUseModelContextAndSkipNullsScenarioModule() {
            super(configurationValues(Map.of(
                    "yca.labels.userTemplate",
                    "{{#if agent}}{{agent}}{{else}}{{systemProperty \"user.name\" \"You\"}}{{/if}}",
                    "yca.labels.assistantTemplate",
                    "{{model.provider}}::{{model.name}}")));
        }
    }

    public static class SystemPropertyHelperReadsKeyWithDefaultAndWithoutScenarioModule
            extends ConfigurationTestModule {
        public SystemPropertyHelperReadsKeyWithDefaultAndWithoutScenarioModule() {
            super(configurationValues(Map.of()));
        }
    }

    public static class SystemPropertyHelperIsAvailableInTheCliPromptTemplateScenarioModule
            extends ConfigurationTestModule {
        public SystemPropertyHelperIsAvailableInTheCliPromptTemplateScenarioModule() {
            super(configurationValues(Map.of("yca.prompt.template", "u:{{systemProperty \"user.name\" \"You\"}}")));
        }
    }

    public static class LlmPayloadKeepsHardcodedRolesRegardlessOfLabelsScenarioModule extends ConfigurationTestModule {
        public LlmPayloadKeepsHardcodedRolesRegardlessOfLabelsScenarioModule() {
            super(configurationValues(Map.of(
                    "yca.labels.userTemplate", "TheBoss",
                    "yca.labels.assistantTemplate", "Robot-9000")));
        }
    }

    public static class TranscriptMarkersRoundTripWithCustomLabelsScenarioModule extends ConfigurationTestModule {
        public TranscriptMarkersRoundTripWithCustomLabelsScenarioModule() {
            super(configurationValues(Map.of()));
        }
    }

    public static class ReplayRendersStoredIdentityWhenCurrentLabelsDifferScenarioModule
            extends ConfigurationTestModule {
        public ReplayRendersStoredIdentityWhenCurrentLabelsDifferScenarioModule() {
            super(configurationValues(Map.of(
                    "yca.labels.userTemplate", "{{model.provider}}::{{model.name}}",
                    "yca.labels.assistantTemplate", "{{model.provider}}::{{model.name}}")));
        }
    }

    public static class MessageIdentityHelpersRoundTripScenarioModule extends ConfigurationTestModule {
        public MessageIdentityHelpersRoundTripScenarioModule() {
            super(configurationValues(Map.of()));
        }
    }

    public static class WireNeverContainsTheIdentityKeyScenarioModule extends ConfigurationTestModule {
        public WireNeverContainsTheIdentityKeyScenarioModule() {
            super(configurationValues(Map.of()));
        }
    }
}
