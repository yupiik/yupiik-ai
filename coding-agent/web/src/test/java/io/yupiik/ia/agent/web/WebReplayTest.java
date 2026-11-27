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
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.FeedbackTiming;
import io.yupiik.ia.agent.core.agent.SubagentDeliveryBus;
import io.yupiik.ia.agent.core.cli.FormatDatePromptHelper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Replaying a persisted session must restore thought blocks, not only the visible answer. */
@FusionSupport(modules = WebReplayTest.LabelsModule.class)
class WebReplayTest {
    public static class LabelsModule extends WebConfigurationTestModule {
        public LabelsModule() {
            super(Map.of(
                    "yca.labels.logsTemplate", "{{#if timestampKnown}}[{{formatDate \"yyyy\"}}]{{/if}}",
                    "yca.labels.assistantTemplate", "{{> logsTemplate}} {{model.name}}"));
        }
    }

    @Test
    void feedbackReplayCarriesCapturedTimeAndTheExistingServerSideSpeakerTemplate(
            @Fusion final CodingAgentConfiguration configuration) {
        final var labels =
                new SpeakerLabels(List.of(new FormatDatePromptHelper(Clock.systemUTC())), configuration, null);
        final var at = Instant.parse("2000-01-01T12:00:00Z").toEpochMilli();
        final var timed = new Message(
                "assistant",
                "answer",
                null,
                null,
                null,
                FeedbackTiming.stamp(Map.of("yca", Map.of("model", "test-model")), "text", at));
        final var events = WebReplay.replay("s", List.of(timed, Message.assistant("old", null)), labels);
        assertEquals(2, events.size());
        assertEquals(
                Map.of("startedAt", at, "feedbackStart", true, "speakerLabel", "[2000] test-model"),
                events.getFirst().data());
        assertEquals(Map.of("speakerLabel", "Assistant"), events.getLast().data());
    }

    @Test
    void compactionDigestsAreNotReplayedAsUserTurnsOrRemovedFromHistory() {
        final var digest = "<compacted-context>\n## Objective\nInternal history\n</compacted-context>";
        final var guidance = """
                When a compacted context is provided, treat it as historical state.
                Do not reload, reconstruct, or repeat actions already described in it.
                Continue from the state it represents; only call tools when needed for the current task.""";
        for (final var text : List.of(digest, digest + "\n\n" + guidance, digest + "\n  ")) {
            final var message = Message.user(text);
            final var history = List.of(message, Message.user("continue"), Message.assistant("answer", null));

            assertEquals(
                    List.of(
                            new WebEventDto("user", "s1", null, null, "continue", Map.of()),
                            new WebEventDto("text-delta", "s1", null, null, "answer", Map.of())),
                    WebReplay.replay("s1", history));
            assertEquals(3, history.size());
            assertEquals("user", message.role());
            assertEquals(text, message.content());
        }
    }

    @Test
    void quotedMalformedAndAssistantCompactionTextRemainsVisible() {
        final var digest = "<compacted-context>\nInternal history\n</compacted-context>";
        for (final var text : List.of(
                "Example: " + digest,
                "```xml\n" + digest + "\n```",
                digest + "\nWhy is this shown?",
                "<compacted-context>\nunclosed example")) {
            assertEquals(
                    List.of(new WebEventDto("user", "s1", null, null, text, Map.of())),
                    WebReplay.replay("s1", List.of(Message.user(text))));
        }
        assertEquals(
                List.of(new WebEventDto("text-delta", "s1", null, null, digest, Map.of())),
                WebReplay.replay("s1", List.of(Message.assistant(digest, null))));
    }

    @Test
    void deliveredSubagentsReplayAsCompletionEventsWithoutChangingMessages() {
        final var summary = "## Conclusion\n\n**Reviewed** the code.\n\n```java\nclass Review {}\n```\n";
        for (final var state : List.of("completed", "failed", "cancelled")) {
            final var delivery = new SubagentDeliveryBus.Delivery(
                    "child-1", "active-instana-review", summary, "completed".equals(state), "cancelled".equals(state));
            final var message = Message.user(delivery.message());
            final var messages = List.of(Message.user("review it"), message, Message.assistant("Thanks", null));

            final var events = WebReplay.replay("parent", messages);

            assertEquals(
                    List.of("user", "subagent-completed", "text-delta"),
                    events.stream().map(WebEventDto::type).toList());
            assertEquals(
                    new WebEventDto(
                            "subagent-completed",
                            "parent",
                            "child-1",
                            "active-instana-review",
                            summary,
                            Map.of(
                                    "subagentId",
                                    "child-1",
                                    "subagentName",
                                    "active-instana-review",
                                    "success",
                                    "completed".equals(state),
                                    "cancelled",
                                    "cancelled".equals(state))),
                    events.get(1));
            assertEquals("user", message.role());
            assertEquals(delivery.message(), message.content());
        }
    }

    @Test
    void ordinaryUserMessagesAndMalformedOrQuotedEnvelopesStayUserMessages() {
        final var envelope = new SubagentDeliveryBus.Delivery("child-1", "review", "## Result", true, false).message();
        for (final var text : List.of(
                "Please review **this**",
                "Example: " + envelope,
                "```xml\n" + envelope + "\n```",
                envelope + "\nMore user text",
                envelope.replace("</subagent>", ""),
                envelope.replace("state=\"completed\"", "state=\"unknown\""),
                envelope.replace("sessionID=\"child-1\"", "sessionID=\"\""))) {
            final var events = WebReplay.replay("parent", List.of(Message.user(text)));
            assertEquals(List.of(new WebEventDto("user", "parent", null, null, text, Map.of())), events);
        }
    }

    @Test
    void subagentEnvelopeInAssistantTextIsNotReclassified() {
        final var text = new SubagentDeliveryBus.Delivery("child-1", null, null, true, false).message();
        assertEquals(
                List.of(new WebEventDto("text-delta", "parent", null, null, text, Map.of())),
                WebReplay.replay("parent", List.of(Message.assistant(text, null))));
    }

    @Test
    void persistedThinkingIsReplayedAsThinkingDeltaBeforeTheAnswer() {
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("do it"));
        messages.add(new Message(
                "assistant", "the answer", null, null, null, Map.of("thinking", "let me think about this")));

        final var events = WebReplay.replay("s1", messages);

        final var types = events.stream().map(WebEventDto::type).toList();
        assertEquals(List.of("user", "thinking-delta", "text-delta"), types);
        // the thinking must precede the answer so the reducer groups it as a thought block above it
        assertEquals("let me think about this", events.get(1).message());
        assertEquals("the answer", events.get(2).message());
    }

    @Test
    void assistantWithoutThinkingStillReplaysPlainly() {
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("do it"));
        messages.add(Message.assistant("plain answer", null));

        final var events = WebReplay.replay("s1", messages);

        final var types = events.stream().map(WebEventDto::type).toList();
        assertEquals(List.of("user", "text-delta"), types);
        assertTrue(events.stream().noneMatch(e -> "thinking-delta".equals(e.type())));
    }

    @Test
    void blankThinkingIsSkipped() {
        final var messages = new ArrayList<Message>();
        messages.add(new Message("assistant", "answer", null, null, null, Map.of("thinking", "  \n")));

        final var events = WebReplay.replay("s1", messages);

        assertEquals(
                List.of("text-delta"), events.stream().map(WebEventDto::type).toList());
    }

    @Test
    void legacyUnScrubbedPersistedThinkingIsPassedThroughVerbatim() {
        // WebReplay is a faithful mirror: it does not scrub. A legacy persisted thinking that
        // predates the AgentLoop persistence scrub is passed through as-is; the frontend render
        // chokepoint (stripEchoedRefs in the Markdown component) is what hides any remnant.
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("hi"));
        messages.add(
                new Message("assistant", "the answer", null, null, null, Map.of("thinking", "reasoning @42@ here")));

        final var events = WebReplay.replay("s1", messages);

        final var types = events.stream().map(WebEventDto::type).toList();
        assertEquals(List.of("user", "thinking-delta", "text-delta"), types);
        assertEquals("reasoning @42@ here", events.get(1).message());
        assertEquals("the answer", events.get(2).message());
    }

    @Test
    void scrubbedPersistedThinkingReplaysWithoutAnySeam() {
        // AgentLoop strips echoed "@N@" seams at persistence, so a clean stored message must
        // replay without inventing a seam either.
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("hi"));
        messages.add(new Message(
                "assistant", "the answer", null, null, null, Map.of("thinking", "reasoning carefully here")));

        final var events = WebReplay.replay("s1", messages);

        final var types = events.stream().map(WebEventDto::type).toList();
        assertEquals(List.of("user", "thinking-delta", "text-delta"), types);
        assertTrue(events.stream()
                .filter(e -> e.message() != null)
                .noneMatch(e -> e.message().contains("@42@")));
    }
}
