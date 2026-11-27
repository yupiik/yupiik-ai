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
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.security.SecretNames;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class AcpElicitationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final List<Question> QUESTIONS = List.of(
            new Question("notes", "Any notes?", List.of(), false),
            new Question("strategy", "Choose an approach", List.of("Careful", "Broad"), false),
            new Question("colors", "Choose colors", List.of("Red", "Green", "Blue"), true));

    private <T> T await(final CompletionStage<T> answer) throws Exception {
        return answer.toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    private static final class Channel implements AcpElicitation.RequestChannel {
        private final CompletableFuture<AcpElicitation.Reply> response = new CompletableFuture<>();
        private int requests;
        private String method;
        private AcpElicitation.Form params;
        private Duration timeout;

        @Override
        public CompletionStage<AcpElicitation.Reply> request(
                final String method, final AcpElicitation.Form params, final Duration timeout) {
            requests++;
            this.method = method;
            this.params = params;
            this.timeout = timeout;
            return response;
        }
    }

    @Nested
    @FusionSupport(modules = CustomMarkersModule.class)
    class CustomMarkers {

        @Test
        void commonMarkersComeOnlyFromTheInjectedCoreConfiguration(
                @Fusion final CodingAgentConfiguration customConfiguration, @Fusion final AcpCommand.Conf conf) {
            final var channel = new Channel();
            final var configured = new AcpElicitation(
                            "s", null, true, new SecretNames(customConfiguration), TIMEOUT, channel)
                    .withSensitiveInputs(conf.elicitationSensitiveMarkers(), conf.elicitationSensitiveWords());
            for (final var marker :
                    List.of("projectSensitive", "PROJECT / SENSITIVE", "project_sensitive", "TEAM_entry", "stepup")) {
                assertNull(configured.ask(List.of(new Question(marker, "Enter value", List.of(), false))), marker);
                assertNull(configured.ask(List.of(new Question("q", "Enter " + marker, List.of(), false))), marker);
                assertNull(configured.ask(List.of(new Question("q", "Choose", List.of(marker), false))), marker);
            }
            assertEquals(0, channel.requests, "The environment-harvest allowlist must not allow sensitive form fields");

            // A deliberately replaced core keyword set must not leave a second, hidden TOKEN rule in ACP.
            channel.response.complete(new AcpElicitation.Reply("accept", Map.of("q", "Use the parser")));
            assertEquals(
                    Map.of("q", "Use the parser"),
                    configured.ask(List.of(new Question("q", "Describe the tokenization strategy", List.of(), false))));
            assertEquals(1, channel.requests);
        }
    }

    public static class CustomMarkersModule extends AcpConfigurationTestModule {
        public CustomMarkersModule() {
            super(Map.of(
                    "elicitationSensitiveMarkers",
                    "team / entry",
                    "elicitationSensitiveWords",
                    "stepup",
                    "yca.secrets.environment.keywords",
                    "PROJECTSENSITIVE",
                    "yca.secrets.environment.knownSafeValues",
                    "PROJECTSENSITIVE"));
        }
    }

    @Nested
    @FusionSupport(modules = AcpConfigurationTestModule.class)
    class Defaults {
        @Fusion
        private CodingAgentConfiguration configuration;

        @Fusion
        private AcpCommand.Conf commandOptions;

        @Test
        void formCapabilityMustBeAnExplicitNonNullObject() {
            assertFalse(AcpElicitation.supportsForm(null));
            final List<Map<String, ?>> unsupported = List.of(
                    Map.of(),
                    Collections.singletonMap("elicitation", null),
                    Map.of("elicitation", Map.of()),
                    Map.of("elicitation", Collections.singletonMap("form", null)),
                    Map.of("elicitation", Map.of("url", Map.of())),
                    Map.of("elicitation", Map.of("form", false)),
                    Map.of("elicitation", Map.of("form", true)),
                    Map.of("elicitation", Map.of("form", "{}")),
                    Map.of("elicitation", Map.of("form", List.of())),
                    Map.of("elicitation", true),
                    Map.of("form", Map.of()));
            for (final var capabilities : unsupported) {
                assertFalse(AcpElicitation.supportsForm(capabilities), capabilities.toString());
            }
            final var allNull = new LinkedHashMap<String, Object>();
            allNull.put("form", null);
            allNull.put("url", null);
            assertFalse(AcpElicitation.supportsForm(Map.of("elicitation", Collections.unmodifiableMap(allNull))));
            allNull.put("url", Map.of());
            assertFalse(AcpElicitation.supportsForm(Map.of("elicitation", Collections.unmodifiableMap(allNull))));
            assertTrue(AcpElicitation.supportsForm(Map.of("elicitation", Map.of("form", Map.of()))));
            assertTrue(AcpElicitation.supportsForm(Map.of("elicitation", Map.of("form", Map.of(), "url", Map.of()))));
            assertTrue(AcpElicitation.supportsForm(Map.of("elicitation", Map.of("form", Map.of("_meta", Map.of())))));
        }

        @Test
        void unsupportedModesNeverIssueAnyRequest() throws Exception {
            final var channel = new Channel();
            final var adapter = adapter(false, TIMEOUT, channel);
            assertNull(adapter.ask(QUESTIONS));
            assertEquals(Optional.empty(), await(adapter.readLine("Display name?", true)));
            assertFalse(await(adapter.confirm("Continue displaying results?")));
            assertEquals(Optional.empty(), await(adapter.pick("Choose", List.of("A", "B"))));
            assertEquals(0, channel.requests);
        }

        @Test
        void sendsExactRestrictedFlatSchemaAndReturnsTypedAnswers() {
            final var channel = new Channel();
            final Map<String, Object> content =
                    Map.of("notes", "Keep the public API", "strategy", "Careful", "colors", List.of("Green", "Blue"));
            channel.response.complete(new AcpElicitation.Reply("accept", content));
            assertEquals(content, adapter(true, TIMEOUT, channel).ask(QUESTIONS));
            assertEquals(1, channel.requests);
            assertEquals("elicitation/create", channel.method);
            assertEquals(TIMEOUT, channel.timeout);
            try (final var mapper = CodecsTest.acpMapper()) {
                final var json = mapper.toString(channel.params);
                assertEquals(channel.params, mapper.fromString(AcpElicitation.Form.class, json));
                assertEquals(mapper.fromString(Object.class, """
                    {
                      "sessionId": "session-1", "toolCallId": "tool-1", "mode": "form",
                      "message": "Please answer these non-sensitive questions.",
                      "requestedSchema": {
                        "type": "object",
                        "properties": {
                          "notes": {"type": "string", "title": "Any notes?"},
                          "strategy": {"type": "string", "title": "Choose an approach",
                                       "enum": ["Careful", "Broad"]},
                          "colors": {"type": "array", "title": "Choose colors",
                                     "items": {"type": "string", "enum": ["Red", "Green", "Blue"]},
                                     "minItems": 0, "maxItems": 3}
                        },
                        "required": ["notes", "strategy", "colors"]
                      }
                    }
                    """), mapper.fromString(Object.class, json));
            }
        }

        @Test
        void acceptedWireContentUsesTheGeneratedReplyModelAndPreservesStringArrayTypes() {
            try (final var mapper = CodecsTest.acpMapper()) {
                final var channel = new Channel();
                channel.response.complete(mapper.fromString(AcpElicitation.Reply.class, """
                    {"action":"accept","content":{"notes":"Keep changes small","strategy":"Careful","colors":["Red"]}}
                    """));
                assertEquals(
                        Map.of("notes", "Keep changes small", "strategy", "Careful", "colors", List.of("Red")),
                        adapter(true, TIMEOUT, channel).ask(QUESTIONS));
            }
        }

        @Test
        void optionalToolCallIsNotRequiredAndNullOptionsAreFreeText() {
            final var channel = new Channel();
            channel.response.complete(new AcpElicitation.Reply("accept", Map.of("name", "")));
            final var adapter =
                    new AcpElicitation("session-1", null, true, new SecretNames(configuration), TIMEOUT, channel);
            assertEquals(Map.of("name", ""), adapter.ask(List.of(new Question("name", "Display name?", null, false))));
            assertNull(channel.params.toolCallId());
            assertEquals(
                    "string",
                    channel.params.requestedSchema().properties().get("name").type());
            assertNull(channel.params.requestedSchema().properties().get("name").options());
        }

        @Test
        void acceptedEmptyMultiChoiceIsAnAnswerNotCancellation() {
            final var channel = new Channel();
            channel.response.complete(new AcpElicitation.Reply("accept", Map.of("colors", List.of())));
            assertEquals(
                    Map.of("colors", List.of()), adapter(true, TIMEOUT, channel).ask(List.of(QUESTIONS.get(2))));
        }

        @Test
        void validatesEveryRequiredQuestionAndRejectsUnknownFieldsTypesAndOptions() {
            final var valid = new LinkedHashMap<String, Object>();
            valid.put("notes", "Keep changes small");
            valid.put("strategy", "Careful");
            valid.put("colors", List.of("Red"));
            final List<Object> badNotes = Arrays.asList(null, true, 42, List.of("text"), Map.of("text", "value"));
            for (final var value : badNotes) {
                final var content = new LinkedHashMap<>(valid);
                content.put("notes", value);
                assertRejected(content);
            }
            for (final var value : List.of("careful", "Careful ", "other", 1, List.of("Careful"))) {
                final var content = new LinkedHashMap<>(valid);
                content.put("strategy", value);
                assertRejected(content);
            }
            final List<Object> badMultiple = Arrays.asList(
                    null,
                    "Red",
                    true,
                    Map.of("Red", true),
                    List.of(1),
                    List.of("Purple"),
                    List.of("red"),
                    List.of(List.of("Red")),
                    List.of("Red", "Red"),
                    List.of("Red", "Green", "Blue", "Red"),
                    Arrays.asList("Red", null));
            for (final var value : badMultiple) {
                final var content = new LinkedHashMap<>(valid);
                content.put("colors", value);
                assertRejected(content);
            }
            for (final var id : valid.keySet()) {
                final var content = new LinkedHashMap<>(valid);
                content.remove(id);
                assertRejected(content);
            }
            final var extra = new LinkedHashMap<>(valid);
            extra.put("unexpected", "ignored?");
            assertRejected(extra);
            assertRejected(Map.of());
            assertRejected(Collections.singletonMap(42, "not a question id"));
        }

        @Test
        void declineCancelMissingAndUnknownActionsCannotSucceedAndTheirContentIsIgnored() throws Exception {
            final var actions = Arrays.asList("decline", "cancel", "ACCEPT", "future", "_custom", "", null);
            for (final var action : actions) {
                final var channel = new Channel();
                channel.response.complete(new AcpElicitation.Reply(action, Map.of("notes", "an apparent answer")));
                assertNull(adapter(true, TIMEOUT, channel).ask(List.of(QUESTIONS.getFirst())), String.valueOf(action));
            }
            for (final var action : List.of("decline", "cancel")) {
                final var channel = new Channel();
                channel.response.complete(new AcpElicitation.Reply(action, List.of("not an object")));
                final var adapter = adapter(true, TIMEOUT, channel);
                assertNull(adapter.ask(QUESTIONS));
                assertEquals(Optional.empty(), await(adapter.readLine("Display name?", true)));
                assertEquals(Optional.empty(), await(adapter.pick("Choose", List.of("A", "B"))));
                assertFalse(await(adapter.confirm("Show more?")));
            }
        }

        @Test
        void omittedAndNullAcceptedContentAreEquivalentButCannotAnswerRequiredQuestions() {
            try (final var mapper = CodecsTest.acpMapper()) {
                for (final var json : List.of("{\"action\":\"accept\"}", "{\"action\":\"accept\",\"content\":null}")) {
                    final var channel = new Channel();
                    final var reply = mapper.fromString(AcpElicitation.Reply.class, json);
                    assertNull(reply.content());
                    channel.response.complete(reply);
                    assertNull(adapter(true, TIMEOUT, channel).ask(QUESTIONS));
                }
            }
        }

        @Test
        void malformedJsonAndNonObjectContentFailClosedThroughTheInjectedDecoder() {
            try (final var mapper = CodecsTest.acpMapper()) {
                for (final var json : List.of(
                        "null",
                        "[]",
                        "true",
                        "\"accept\"",
                        "{",
                        "{}",
                        "{\"action\":23}",
                        "{\"action\":{\"value\":\"accept\"}}",
                        "{\"action\":\"accept\",\"content\":\"answer\"}",
                        "{\"action\":\"accept\",\"content\":[\"answer\"]}")) {
                    final AcpElicitation.RequestChannel channel = (method, params, timeout) -> {
                        try {
                            return CompletableFuture.completedFuture(
                                    mapper.fromString(AcpElicitation.Reply.class, json));
                        } catch (final RuntimeException e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    };
                    assertNull(adapter(true, TIMEOUT, channel).ask(QUESTIONS), json);
                }
            }
            final var channel = new Channel();
            channel.response.complete(null);
            assertNull(adapter(true, TIMEOUT, channel).ask(QUESTIONS));
        }

        @Test
        void invalidQuestionsNeverProduceAPartialOrDowngradedRequest() {
            final var channel = new Channel();
            final var adapter = adapter(true, TIMEOUT, channel);
            assertNull(adapter.ask(null));
            final List<List<Question>> invalid = List.of(
                    List.of(),
                    Collections.singletonList(null),
                    List.of(new Question(null, "Question?", List.of(), false)),
                    List.of(new Question("", "Question?", List.of(), false)),
                    List.of(new Question("  ", "Question?", List.of(), false)),
                    List.of(new Question("q", null, List.of(), false)),
                    List.of(new Question("q", " ", List.of(), false)),
                    List.of(
                            new Question("q", "First?", List.of(), false),
                            new Question("q", "Second?", List.of(), false)),
                    List.of(new Question("q", "Choose", List.of("A", "A"), false)),
                    List.of(new Question("q", "Choose", List.of(" "), false)),
                    List.of(new Question("q", "Choose", Collections.singletonList(null), false)),
                    List.of(new Question("q", "Choose", List.of(), true)),
                    List.of(new Question("q", "Choose", null, true)));
            for (final var questions : invalid) {
                assertNull(adapter.ask(questions), questions.toString());
            }
            assertEquals(0, channel.requests);
        }

        @Test
        void explicitlySensitiveQuestionsAreRejectedInsteadOfDowngradedEvenWithUrlSupport() throws Exception {
            final var channel = new Channel();
            final var supported =
                    AcpElicitation.supportsForm(Map.of("elicitation", Map.of("form", Map.of(), "url", Map.of())));
            final var adapter = adapter(supported, TIMEOUT, channel);
            for (final var marker : List.of(
                    "password",
                    "passphrase",
                    "API key",
                    "accessToken",
                    "refresh-token",
                    "private key",
                    "recovery codes",
                    "backup codes",
                    "payment details",
                    "credit card",
                    "card number",
                    "PIN",
                    "CVV",
                    "CVC",
                    "OTP",
                    "one-time code",
                    "credentials",
                    "sEcReT",
                    "https://auth.example")) {
                assertNull(
                        adapter.ask(List.of(new Question("value", "Enter your " + marker, List.of(), false))), marker);
                assertNull(adapter.ask(List.of(new Question(marker, "Enter value", List.of(), false))), marker);
                assertNull(adapter.ask(List.of(new Question("q", "Choose", List.of(marker), false))), marker);
                assertEquals(Optional.empty(), await(adapter.readLine("Enter " + marker, true)), marker);
                assertFalse(await(adapter.confirm("Provide " + marker + "?")), marker);
                assertEquals(Optional.empty(), await(adapter.pick("Choose", List.of(marker))), marker);
            }
            assertEquals(0, channel.requests);
        }

        @Test
        void acpExtensionsAreNormalizedLiteralAndSnapshottedWithoutChangingCoreRules() throws Exception {
            final var channel = new Channel();
            final var markers = new ArrayList<>(List.of("Team / Entry"));
            final var words = new ArrayList<>(List.of(" stepup ", "o.tp", "p(in)", ".*"));
            final var adapter = adapter(true, TIMEOUT, channel).withSensitiveInputs(markers, words);
            markers.clear();
            words.clear();
            for (final var marker :
                    List.of("TEAM_entry", "team-entry", "Team Entry", "STEPUP", "o.tp", "p(in)", ".*", "password")) {
                assertNull(adapter.ask(List.of(new Question(marker, "Enter value", List.of(), false))), marker);
                assertNull(adapter.ask(List.of(new Question("q", "Enter " + marker, List.of(), false))), marker);
                assertNull(adapter.ask(List.of(new Question("q", "Choose", List.of(marker), false))), marker);
                assertEquals(Optional.empty(), await(adapter.readLine("Enter " + marker, true)), marker);
                assertFalse(await(adapter.confirm("Provide " + marker + "?")), marker);
                assertEquals(Optional.empty(), await(adapter.pick("Choose", List.of(marker))), marker);
            }
            assertEquals(0, channel.requests);
            channel.response.complete(new AcpElicitation.Reply("accept", Map.of("q", "Public notes")));
            assertEquals(
                    Map.of("q", "Public notes"),
                    adapter.ask(List.of(new Question("q", "Notes about oXtp, pin and stepupper", List.of(), false))));
            assertEquals(1, channel.requests, "Literal word extensions must not become regular expressions");
        }

        @Test
        void wholeWordDefaultsDoNotRejectHarmlessSubstrings() {
            for (final var word :
                    List.of("spinning", "pinwheel", "hotpot", "cvvalue", "cvcache", "\u00e9pin", "otp\u00e9")) {
                final var channel = new Channel();
                channel.response.complete(new AcpElicitation.Reply("accept", Map.of(word, word)));
                assertEquals(
                        Map.of(word, word),
                        adapter(true, TIMEOUT, channel)
                                .ask(List.of(new Question(word, "Notes on " + word, List.of(word), false))),
                        word);
                assertEquals(1, channel.requests);
            }
        }

        @Test
        void wholeWordsStillRejectPunctuationAndIdentifierSeparatedCredentialNames() {
            final var channel = new Channel();
            final var adapter = adapter(true, TIMEOUT, channel);
            for (final var marker : List.of("(PIN)", "otp_code", "CVV?", "cvc-code")) {
                assertNull(adapter.ask(List.of(new Question(marker, "Enter value", List.of(), false))), marker);
                assertNull(adapter.ask(List.of(new Question("q", "Enter " + marker, List.of(), false))), marker);
                assertNull(adapter.ask(List.of(new Question("q", "Choose", List.of(marker), false))), marker);
            }
            assertEquals(0, channel.requests);
        }

        @Test
        void emptyAcpExtensionsCannotBypassCommonRulesUrlsOrNonsecretApproval() throws Exception {
            final var channel = new Channel();
            final var adapter = adapter(true, TIMEOUT, channel).withSensitiveInputs(List.of(), List.of());
            for (final var marker :
                    List.of("password", "passwd", "secret", "API key", "access token", "private key", "credentials")) {
                assertNull(adapter.ask(List.of(new Question("q", "Enter " + marker, List.of(), false))), marker);
                assertEquals(Optional.empty(), await(adapter.readLine("Enter " + marker, true)), marker);
            }
            assertEquals(Optional.empty(), await(adapter.readLine("https://auth.example/authorize", true)));
            assertEquals(Optional.empty(), await(adapter.readLine("Enter value", false)));

            // Even an inert core matcher and empty ACP additions cannot approve generic authentication input.
            final var inert = new AcpElicitation("s", null, true, new SecretNames(null), TIMEOUT, channel)
                    .withSensitiveInputs(List.of(), List.of());
            assertEquals(Optional.empty(), await(inert.readLine("Enter value", false)));
            assertEquals(Optional.empty(), await(inert.readLine("Enter your password", false)));
            assertEquals(0, channel.requests);
        }

        @Test
        void invalidExtensionsAreRejectedBeforeReplacingTheCurrentPolicy() {
            final var channel = new Channel();
            final var adapter = adapter(true, TIMEOUT, channel);
            assertThrows(NullPointerException.class, () -> adapter.withSensitiveInputs(null, List.of()));
            assertThrows(NullPointerException.class, () -> adapter.withSensitiveInputs(List.of(), null));
            for (final var invalid : Arrays.asList(null, "", " ", ".*")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adapter.withSensitiveInputs(Collections.singletonList(invalid), List.of()));
            }
            for (final var invalid : Arrays.asList(null, "", " ")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adapter.withSensitiveInputs(List.of(), Collections.singletonList(invalid)));
            }
            assertNull(adapter.ask(List.of(new Question("q", "Enter PIN", List.of(), false))));
            assertNull(adapter.ask(List.of(new Question("q", "Enter recovery code", List.of(), false))));
            assertEquals(0, channel.requests);
        }

        @Test
        void configuredExtensionsDoNotWeakenMalformedAnswerValidation() {
            for (final var content :
                    List.of(Map.of("other", "value"), Map.of("answer", 42), Map.of("answer", List.of("value")))) {
                final var channel = new Channel();
                channel.response.complete(new AcpElicitation.Reply("accept", content));
                assertNull(adapter(true, TIMEOUT, channel)
                        .withSensitiveInputs(List.of("team entry"), List.of("stepup"))
                        .ask(List.of(new Question("answer", "Display name?", List.of(), false))));
                assertEquals(1, channel.requests);
            }
        }

        @Test
        void ordinaryProfileInformationIsNotCategoricallyForbidden() {
            final var channel = new Channel();
            channel.response.complete(new AcpElicitation.Reply("accept", Map.of("email", "person@example.test")));
            assertEquals(
                    Map.of("email", "person@example.test"),
                    adapter(true, TIMEOUT, channel)
                            .ask(List.of(new Question("email", "Your email address?", List.of(), false))));
            assertEquals(1, channel.requests);
        }

        @Test
        void genericReadLineRequiresIndependentExplicitNonsecretApproval() throws Exception {
            final var channel = new Channel();
            channel.response.complete(new AcpElicitation.Reply("accept", Map.of("answer", "Ada")));
            final var adapter = adapter(true, TIMEOUT, channel);
            assertEquals(Optional.empty(), await(adapter.readLine("Enter value", false)));
            assertEquals(0, channel.requests);
            assertEquals(Optional.of("Ada"), await(adapter.readLine("Display name?", true)));
            assertEquals(1, channel.requests);
        }

        @Test
        void confirmOnlyAcceptsTheOfferedYesAndNoValues() throws Exception {
            for (final var value : List.of("Yes", "No", "yes", "true", "allow_once")) {
                final var channel = new Channel();
                channel.response.complete(new AcpElicitation.Reply("accept", Map.of("answer", value)));
                assertEquals(
                        "Yes".equals(value),
                        await(adapter(true, TIMEOUT, channel).confirm("Show more results?")));
                assertEquals(
                        List.of("Yes", "No"),
                        channel.params
                                .requestedSchema()
                                .properties()
                                .get("answer")
                                .options());
                assertEquals("elicitation/create", channel.method);
            }
        }

        @Test
        void shellMethodsAreAsyncAndSelectionValidationUsesTheOriginalImmutableOptions() throws Exception {
            final var channel = new Channel();
            final var options = new ArrayList<>(List.of("First", "Second"));
            final var adapter = adapter(true, TIMEOUT, channel);
            final var answer = adapter.pick("Choose an approach", options);
            assertFalse(answer.toCompletableFuture().isDone());
            assertEquals(TIMEOUT, channel.timeout);
            options.clear();
            options.add("Injected");
            assertEquals(
                    List.of("First", "Second"),
                    channel.params.requestedSchema().properties().get("answer").options());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> channel.params.requestedSchema().properties().clear());
            channel.response.complete(new AcpElicitation.Reply("accept", Map.of("answer", "Injected")));
            assertEquals(Optional.empty(), await(answer));

            final var picked = new Channel();
            picked.response.complete(new AcpElicitation.Reply("accept", Map.of("answer", "Second")));
            assertEquals(
                    Optional.of("Second"),
                    await(adapter(true, TIMEOUT, picked).pick("Choose", List.of("First", "Second"))));
        }

        @Test
        void validatedAnswersAreDefensivelyCopied() {
            final var selections = new ArrayList<>(List.of("Green"));
            final var content = new LinkedHashMap<String, Object>();
            content.put("colors", selections);
            final var channel = new Channel();
            channel.response.complete(new AcpElicitation.Reply("accept", content));
            final var answers = adapter(true, TIMEOUT, channel).ask(List.of(QUESTIONS.get(2)));
            selections.add("not offered");
            content.clear();
            assertEquals(Map.of("colors", List.of("Green")), answers);
            assertThrows(UnsupportedOperationException.class, answers::clear);
            assertThrows(UnsupportedOperationException.class, () -> ((List<?>) answers.get("colors")).clear());
        }

        @Test
        void emptyPickDoesNotTurnIntoFreeText() throws Exception {
            final var channel = new Channel();
            final var adapter = adapter(true, TIMEOUT, channel);
            assertEquals(Optional.empty(), await(adapter.pick("Choose", List.of())));
            assertEquals(Optional.empty(), await(adapter.pick("Choose", null)));
            assertEquals(0, channel.requests);
        }

        @Test
        void protocolErrorsDisconnectAndChannelDeadlineFailClosed() throws Exception {
            for (final var error : List.of(
                    new IOException("Disconnected"),
                    new IllegalArgumentException("JSON-RPC error -32602"),
                    new TimeoutException("Connection request deadline"))) {
                final var channel = new Channel();
                final var answer = adapter(true, TIMEOUT, channel).readLine("Display name?", true);
                channel.response.completeExceptionally(error);
                assertEquals(Optional.empty(), await(answer));
                assertNull(adapter(true, TIMEOUT, channel).ask(QUESTIONS));
                assertFalse(await(adapter(true, TIMEOUT, channel).confirm("Show more?")));
            }
            assertNull(adapter(true, TIMEOUT, (method, params, timeout) -> {
                        throw new IllegalStateException("Connection closed before send");
                    })
                    .ask(QUESTIONS));
            assertNull(adapter(true, TIMEOUT, (method, params, timeout) -> null).ask(QUESTIONS));
        }

        @Test
        void sourceCancellationFailsClosedAndLateAcceptCannotResurrectIt() throws Exception {
            final var channel = new Channel();
            final var answer = adapter(true, TIMEOUT, channel).pick("Choose", List.of("A", "B"));
            assertTrue(channel.response.cancel(true));
            assertFalse(channel.response.complete(new AcpElicitation.Reply("accept", Map.of("answer", "A"))));
            assertEquals(Optional.empty(), await(answer));
            assertNull(adapter(true, TIMEOUT, channel).ask(QUESTIONS));
        }

        @Test
        void consumerCancellationIsForwardedToTheActualCallbackFutureForEveryShellOperation() {
            for (final var operation : List.of("read", "confirm", "pick")) {
                final var channel = new Channel();
                final var adapter = adapter(true, TIMEOUT, channel);
                final CompletionStage<?> answer =
                        switch (operation) {
                            case "read" -> adapter.readLine("Display name?", true);
                            case "confirm" -> adapter.confirm("Show more results?");
                            default -> adapter.pick("Choose", List.of("A", "B"));
                        };
                assertTrue(answer.toCompletableFuture().cancel(true));
                assertTrue(channel.response.isCancelled(), operation);
                assertFalse(channel.response.complete(new AcpElicitation.Reply("accept", Map.of("answer", "Yes"))));
            }
        }

        @Test
        void blockingFeedbackHasABoundedWaitAndCancelsTheCallbackOnTimeout() {
            final var channel = new Channel();
            assertNull(adapter(true, Duration.ofNanos(1), channel).ask(QUESTIONS));
            assertTrue(channel.response.isCancelled());
            assertEquals(Duration.ofNanos(1), channel.timeout);
        }

        @Test
        void interruptionWhileWaitingRestoresTheFlagAndCancelsTheCallback() throws Exception {
            final var requested = new CountDownLatch(1);
            final var response = new CompletableFuture<AcpElicitation.Reply>();
            final var answer = new AtomicReference<Map<String, Object>>(Map.of("sentinel", "not finished"));
            final var interrupted = new AtomicBoolean();
            final var adapter = adapter(true, Duration.ofSeconds(2), (method, params, timeout) -> {
                requested.countDown();
                return response;
            });
            final var worker = Thread.ofVirtual().start(() -> {
                answer.set(adapter.ask(QUESTIONS));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            try {
                assertTrue(requested.await(1, TimeUnit.SECONDS));
                worker.interrupt();
                assertTrue(worker.join(Duration.ofSeconds(1)));
                assertNull(answer.get());
                assertTrue(interrupted.get());
                assertTrue(response.isCancelled());
            } finally {
                worker.interrupt();
                assertTrue(worker.join(Duration.ofSeconds(1)));
            }
        }

        @Test
        void preInterruptedFeedbackDoesNotSendAndPreservesTheInterruptFlag() throws Exception {
            final var channel = new Channel();
            final var interrupted = new AtomicBoolean();
            final var answer = new AtomicReference<Map<String, Object>>(Map.of("sentinel", "not finished"));
            final var worker = Thread.ofVirtual().start(() -> {
                Thread.currentThread().interrupt();
                answer.set(adapter(true, TIMEOUT, channel).ask(QUESTIONS));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            try {
                assertTrue(worker.join(Duration.ofSeconds(1)));
                assertNull(answer.get());
                assertTrue(interrupted.get());
                assertEquals(0, channel.requests);
            } finally {
                worker.interrupt();
                assertTrue(worker.join(Duration.ofSeconds(1)));
            }
        }

        @Test
        void sessionScopeAndTimeoutMustBeExplicitAndValid() {
            final var channel = new Channel();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AcpElicitation(" ", null, true, new SecretNames(configuration), TIMEOUT, channel));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AcpElicitation("s", "", true, new SecretNames(configuration), TIMEOUT, channel));
            assertThrows(IllegalArgumentException.class, () -> adapter(true, Duration.ZERO, channel));
            assertThrows(IllegalArgumentException.class, () -> adapter(true, Duration.ofSeconds(-1), channel));
            assertThrows(ArithmeticException.class, () -> adapter(true, Duration.ofSeconds(Long.MAX_VALUE), channel));
        }

        @Test
        void defaultCoreKeywordsRejectTokenizationPrompts() {
            final var defaults = new Channel();
            assertNull(adapter(true, TIMEOUT, defaults)
                    .ask(List.of(new Question("q", "Describe the tokenization strategy", List.of(), false))));
            assertEquals(0, defaults.requests);
        }

        private void assertRejected(final Object content) {
            final var channel = new Channel();
            channel.response.complete(new AcpElicitation.Reply("accept", content));
            assertNull(adapter(true, TIMEOUT, channel).ask(QUESTIONS), String.valueOf(content));
        }

        private AcpElicitation adapter(
                final boolean supported, final Duration timeout, final AcpElicitation.RequestChannel channel) {
            return new AcpElicitation(
                    "session-1", "tool-1", supported, new SecretNames(configuration), timeout, channel);
        }
    }
}
