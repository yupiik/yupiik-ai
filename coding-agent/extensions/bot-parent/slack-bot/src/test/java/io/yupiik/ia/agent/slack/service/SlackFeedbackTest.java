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
package io.yupiik.ia.agent.slack.service;

import static io.yupiik.ia.agent.slack.support.SlackDomainFixture.task;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.feedback.FeedbackMailbox;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.model.SlackJson;
import io.yupiik.ia.agent.slack.model.SlackQuestion;
import io.yupiik.ia.agent.slack.model.SlackQuestionItem;
import io.yupiik.ia.agent.slack.model.SlackQuestionRequest;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.support.SlackConfigurationTestModule;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = SlackConfigurationTestModule.class)
class SlackFeedbackTest {
    @Fusion
    private SlackPrompts baselinePrompts;

    private static final String NONCE = "12345678-1234-4234-8234-123456789abc";

    @TempDir
    Path root;

    @Test
    void publishesQuestionOpensBoundModalAndDeliversValidatedAnswerToMailbox() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var mailboxRoot = Files.createDirectory(
                    root.resolve("mailbox"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try (final var mailbox = new FeedbackMailbox(mailboxRoot, fixture.json)) {
                final var request = mailbox.request(
                        List.of(new Question("choice", "{{untrusted}}", List.of("yes", "no"), false)),
                        System.currentTimeMillis() + 30000);
                final var original = task("task", "U1", TaskStatus.running, 1);
                fixture.add(original);
                final var feedback = feedback(fixture);
                feedback.poll(original.id(), mailbox);
                assertEquals(
                        "waiting", fixture.store.list().getFirst().question().state());
                assertEquals(
                        "/api/chat.postMessage",
                        fixture.exchanges.getFirst().request().uri().getPath());
                final var post = (Map<?, ?>) fixture.json.fromString(
                        Object.class, fixture.exchanges.getFirst().body());
                assertEquals("C1", post.get("channel"));
                assertEquals("1.1", post.get("thread_ts"));
                interact(fixture, feedback, action("U1", request.nonce(), "answer"));
                final var opening = fixture.exchanges.getLast();
                assertEquals("/api/views.open", opening.request().uri().getPath());
                final var open = (Map<?, ?>) fixture.json.fromString(Object.class, opening.body());
                assertEquals("trigger", open.get("trigger_id"));
                final var modal = (Map<?, ?>) open.get("view");
                assertEquals(request.nonce(), modal.get("private_metadata"));
                assertEquals("yca_feedback", modal.get("callback_id"));
                assertTrue(opening.body().contains("{{untrusted}}"));
                final var block = (Map<?, ?>) ((List<?>) modal.get("blocks")).getFirst();
                final var element = (Map<?, ?>) block.get("element");
                assertEquals("o0", ((Map<?, ?>) ((List<?>) element.get("options")).getFirst()).get("value"));
                assertEquals("open", fixture.store.list().getFirst().question().state());
                assertTrue(interact(fixture, feedback, submission("U1", request.nonce(), "V1", "yca_feedback", "o1"))
                        .isEmpty());
                feedback.poll(original.id(), mailbox);
                assertEquals(
                        Map.of("choice", "no"),
                        mailbox.response(request.nonce()).answers());
                assertFalse(mailbox.response(request.nonce()).cancelled());
                assertEquals(2, fixture.exchanges.size(), "Neither answer delivery nor replay may publish again");
            }
        }
    }

    @Test
    void channelMemberFeedbackRemainsBoundToTheOriginalRequester() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var request = new SlackQuestionRequest(
                    NONCE,
                    System.currentTimeMillis() + 30000,
                    List.of(new SlackQuestionItem("choice", "Choose", List.of("yes", "no"), false)));
            fixture.add(task("task", "U3", TaskStatus.running, 1)
                    .feedback(
                            new SlackQuestion(request, "waiting", "2.1", null, null), TaskStatus.waiting_for_user, 1));
            final var feedback = new SlackFeedback(
                    fixture.channelMemberConfiguration(),
                    SlackDomainFixture.prompts(baselinePrompts),
                    fixture.store,
                    fixture.api);
            for (final var actor : List.of("U1", "U4", "UBOT", "B1", "U.*")) {
                interact(fixture, feedback, action(actor, NONCE, "answer"));
                interact(fixture, feedback, action(actor, NONCE, "cancel"));
                interact(fixture, feedback, submission(actor, NONCE, "V1", "yca_feedback", "o0"));
            }
            for (final var override : List.of(
                    Map.of("team", Map.of("id", "T2")),
                    Map.of("channel", Map.of("id", "C3")),
                    Map.of("message", Map.of("ts", "3.1")))) {
                final var invalid = new HashMap<>(action("U3", NONCE, "answer"));
                invalid.putAll(override);
                interact(fixture, feedback, invalid);
            }
            assertTrue(fixture.exchanges.isEmpty());
            assertEquals("waiting", fixture.store.list().getFirst().question().state());
            interact(fixture, feedback, action("U3", NONCE, "answer"));
            assertEquals(1, fixture.exchanges.size());
            interact(fixture, feedback, submission("U4", NONCE, "V1", "yca_feedback", "o0"));
            assertEquals("open", fixture.store.list().getFirst().question().state());
            interact(fixture, feedback, submission("U3", NONCE, "V1", "yca_feedback", "o0"));
            assertEquals(
                    Map.of("choice", "yes"),
                    fixture.store.list().getFirst().question().answers());
        }
    }

    @Test
    void rejectsOtherActorWorkspaceNonceChannelAndMessageBeforeHttp() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            addQuestion(fixture, "waiting", System.currentTimeMillis() + 30000, "2.1", null);
            final var feedback = feedback(fixture);
            assertTrue(
                    interact(fixture, feedback, action("U2", NONCE, "answer")).isEmpty());
            assertTrue(
                    interact(fixture, feedback, action("U1", "wrong", "answer")).isEmpty());
            for (final var override : List.of(
                    Map.of("team", Map.of("id", "T2")),
                    Map.of("channel", Map.of("id", "C2")),
                    Map.of("message", Map.of("ts", "3.1")))) {
                final var invalid = new HashMap<>(action("U1", NONCE, "answer"));
                invalid.putAll(override);
                assertTrue(interact(fixture, feedback, invalid).isEmpty());
            }
            assertTrue(fixture.exchanges.isEmpty());
            assertEquals("waiting", fixture.store.list().getFirst().question().state());
        }
    }

    @Test
    void rejectsWrongActorNonceViewAndExpiredSubmissions() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            addQuestion(fixture, "open", System.currentTimeMillis() + 30000, "2.1", "V1");
            final var feedback = feedback(fixture);
            assertTrue(interact(fixture, feedback, submission("U2", NONCE, "V1", "yca_feedback", "o0"))
                    .isPresent());
            assertTrue(interact(fixture, feedback, submission("U1", "wrong", "V1", "yca_feedback", "o0"))
                    .isPresent());
            assertTrue(interact(fixture, feedback, submission("U1", NONCE, "V2", "yca_feedback", "o0"))
                    .isPresent());
            assertEquals("open", fixture.store.list().getFirst().question().state());
            fixture.store.transaction(tasks -> {
                final var current = tasks.getFirst();
                final var expired = new SlackQuestionRequest(
                        NONCE, 1, current.question().request().questions());
                tasks.set(
                        0,
                        current.feedback(
                                new SlackQuestion(expired, "open", "2.1", "V1", null), TaskStatus.waiting_for_user, 1));
                return null;
            });
            assertTrue(interact(fixture, feedback, submission("U1", NONCE, "V1", "yca_feedback", "o0"))
                    .isPresent());
            assertEquals("open", fixture.store.list().getFirst().question().state());
            assertTrue(fixture.exchanges.isEmpty());
        }
    }

    @Test
    void invalidOpaqueOptionDoesNotChangeQuestionAndValidSubmissionIsIdempotent() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            addQuestion(fixture, "open", System.currentTimeMillis() + 30000, "2.1", "V1");
            final var feedback = feedback(fixture);
            assertTrue(interact(fixture, feedback, submission("U1", NONCE, "V1", "yca_feedback", "yes"))
                    .isPresent());
            assertTrue(interact(fixture, feedback, submission("U1", NONCE, "V1", "yca_feedback", "o99"))
                    .isPresent());
            final var valid = submission("U1", NONCE, "V1", "yca_feedback", "o0");
            interact(fixture, feedback, valid);
            interact(fixture, feedback, valid);
            assertEquals(
                    Map.of("choice", "yes"),
                    fixture.store.list().getFirst().question().answers());
            assertEquals(TaskStatus.running, fixture.store.list().getFirst().status());
            assertTrue(fixture.exchanges.isEmpty());
        }
    }

    @Test
    void rejectsWrongCallback() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            addQuestion(fixture, "open", System.currentTimeMillis() + 30000, "2.1", "V1");
            interact(fixture, feedback(fixture), submission("U1", NONCE, "V1", "other_callback", "o0"));
            assertEquals("open", fixture.store.list().getFirst().question().state());
            assertTrue(fixture.exchanges.isEmpty());
        }
    }

    @Test
    void answeredQuestionCannotBeCancelledByReplayedButton() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            addQuestion(fixture, "open", System.currentTimeMillis() + 30000, "2.1", "V1");
            final var feedback = feedback(fixture);
            interact(fixture, feedback, submission("U1", NONCE, "V1", "yca_feedback", "o0"));
            interact(fixture, feedback, action("U1", NONCE, "cancel"));
            assertEquals("answered", fixture.store.list().getFirst().question().state());
            assertEquals(TaskStatus.running, fixture.store.list().getFirst().status());
        }
    }

    @Test
    void publishingQuestionRejectsInteractionWithoutThrowing() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            addQuestion(fixture, "publishing", System.currentTimeMillis() + 30000, null, null);
            assertDoesNotThrow(() -> interact(fixture, feedback(fixture), action("U1", NONCE, "answer")));
            assertTrue(fixture.exchanges.isEmpty());
            assertEquals(
                    "publishing", fixture.store.list().getFirst().question().state());
        }
    }

    private Optional<SlackJson.Reply> interact(
            final SlackDomainFixture fixture, final SlackFeedback feedback, final Map<String, Object> payload) {
        return feedback.interaction(fixture.payload(payload));
    }

    private SlackFeedback feedback(final SlackDomainFixture fixture) {
        return new SlackFeedback(
                fixture.configuration, SlackDomainFixture.prompts(baselinePrompts), fixture.store, fixture.api);
    }

    private void addQuestion(
            final SlackDomainFixture fixture,
            final String state,
            final long expiry,
            final String message,
            final String view) {
        final var request = new SlackQuestionRequest(
                NONCE, expiry, List.of(new SlackQuestionItem("choice", "Choose", List.of("yes", "no"), false)));
        fixture.add(task("task", "U1", TaskStatus.running, 1)
                .feedback(new SlackQuestion(request, state, message, view, null), TaskStatus.waiting_for_user, 1));
    }

    private Map<String, Object> action(final String actor, final String nonce, final String action) {
        return Map.of(
                "type",
                "block_actions",
                "team",
                Map.of("id", "T1"),
                "user",
                Map.of("id", actor),
                "channel",
                Map.of("id", "C1"),
                "message",
                Map.of("ts", "2.1"),
                "trigger_id",
                "trigger",
                "actions",
                List.of(Map.of("action_id", action, "value", nonce)));
    }

    private Map<String, Object> submission(
            final String actor, final String nonce, final String view, final String callback, final String option) {
        return Map.of(
                "type",
                "view_submission",
                "team",
                Map.of("id", "T1"),
                "user",
                Map.of("id", actor),
                "view",
                Map.of(
                        "id",
                        view,
                        "private_metadata",
                        nonce,
                        "callback_id",
                        callback,
                        "state",
                        Map.of(
                                "values",
                                Map.of("q0", Map.of("value", Map.of("selected_option", Map.of("value", option)))))));
    }
}
