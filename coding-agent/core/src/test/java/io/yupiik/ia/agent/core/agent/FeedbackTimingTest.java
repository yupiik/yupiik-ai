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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.llm.LlmWire;
import io.yupiik.ia.agent.core.llm.Message;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedbackTimingTest {
    @TempDir
    private static Path eventWorkdir;

    @Test
    void payloadSizeFollowsBlocksNotStreamedTextOrTokenCountAndCancellationReleasesIt() {
        final var timing = new FeedbackTiming();
        final var delta = "content that must not be retained by timing".repeat(1000);
        for (int i = 0; i < 1000; i++) {
            timing.accept(new AgentEvent.TextDelta(delta, "s", eventWorkdir), clock(i), ignored -> {});
        }
        final var message = message(timing, "answer");
        assertEquals(List.of(Map.of("kind", "text", "at", 0L, "offset", 0)), FeedbackTiming.blocksOf(message));
        timing.accept(new AgentEvent.TurnCancelled("s", eventWorkdir), clock(2000), ignored -> {});
        assertTrue(timing.merge(null).isEmpty());
        assertEquals(0L, FeedbackTiming.startedAt(message, "text"));
    }

    @Test
    void capturesStructuralStartsOnceAndReplaysOrderedChannels() {
        final var timing = new FeedbackTiming();
        final var starts = new ArrayList<AgentEvent>();
        timing.accept(new AgentEvent.TextDelta("one", "s", "child", "worker", eventWorkdir), clock(1000), starts::add);
        timing.accept(new AgentEvent.TextDelta(" two", "s", "child", "worker", eventWorkdir), clock(2000), starts::add);
        timing.accept(
                new AgentEvent.ThinkingDelta("why", "s", "child", "worker", eventWorkdir), clock(3000), starts::add);
        timing.accept(
                new AgentEvent.TextDelta("three", "s", "child", "worker", eventWorkdir), clock(4000), starts::add);
        assertEquals(
                List.of(
                        new AgentEvent.FeedbackStarted("text", 1000, "s", "child", "worker", eventWorkdir),
                        new AgentEvent.FeedbackStarted("thinking", 3000, "s", "child", "worker", eventWorkdir),
                        new AgentEvent.FeedbackStarted("text", 4000, "s", "child", "worker", eventWorkdir)),
                starts);
        final var message = new Message(
                "assistant",
                "one twothree",
                null,
                null,
                null,
                timing.merge(Map.of("yca", Map.of("provider", "test"), "extension", "kept")));
        assertEquals(
                List.of(
                        new FeedbackTiming.Segment("text", 1000L, "one two"),
                        new FeedbackTiming.Segment("thinking", 3000L, "why"),
                        new FeedbackTiming.Segment("text", 4000L, "three")),
                FeedbackTiming.segments(message, "one twothree", "why"));
        final var identified = Message.withIdentity(message, "other", "model", "agent");
        assertEquals(1000L, FeedbackTiming.startedAt(identified, "text"));
        assertEquals("other", Message.identityOf(identified).provider());
        assertEquals(
                Map.of("extension", "kept"), LlmWire.toLlmMessage(identified).extensions());
        timing.accept(new AgentEvent.TurnDone("one twothree", "s", eventWorkdir), clock(5000), starts::add);
        assertFalse(timing.has("text"));
        assertTrue(timing.merge(null).isEmpty());
        assertEquals(
                1000L, FeedbackTiming.startedAt(identified, "text"), "cleanup must not erase the persisted snapshot");
    }

    @Test
    void retryAndIndependentActorsNeverInheritAnEarlierStart() {
        final var timing = new FeedbackTiming();
        final var other = new FeedbackTiming();
        final var starts = new ArrayList<AgentEvent>();
        timing.accept(new AgentEvent.TextDelta("failed", "s", eventWorkdir), clock(1000), starts::add);
        timing.accept(new AgentEvent.TextReset("s", eventWorkdir), clock(2000), starts::add);
        timing.accept(new AgentEvent.TextDelta("retry", "s", eventWorkdir), clock(3000), starts::add);
        other.accept(new AgentEvent.TextDelta("child", "s", "c", "child", eventWorkdir), clock(4000), starts::add);
        assertEquals(2, starts.stream().filter(it -> it.subagentId() == null).count());
        assertEquals(3000L, FeedbackTiming.startedAt(message(timing, "retry"), "text"));
        assertEquals(4000L, FeedbackTiming.startedAt(message(other, "child"), "text"));
        timing.reset();
        assertFalse(timing.has("text"));
    }

    @Test
    void finalSuffixKeepsItsOwnStartAndLegacyIsUnknown() {
        final var timing = new FeedbackTiming();
        timing.accept(new AgentEvent.TextDelta("preface", "s", eventWorkdir), clock(1000), ignored -> {});
        timing.accept(new AgentEvent.ThinkingDelta("why", "s", eventWorkdir), clock(2000), ignored -> {});
        timing.accept(new AgentEvent.TextDelta("answer", "s", eventWorkdir), clock(3000), ignored -> {});
        timing.reconcile("answer");
        assertEquals(3000L, FeedbackTiming.startedAt(message(timing, "answer"), "text"));
        assertEquals(
                List.of(
                        new FeedbackTiming.Segment("thinking", 2000L, "why"),
                        new FeedbackTiming.Segment("text", 3000L, "answer")),
                FeedbackTiming.segments(message(timing, "answer"), "answer", "why"));
        assertNull(FeedbackTiming.startedAt(Message.assistant("old", null), "text"));
        assertEquals(
                List.of(new FeedbackTiming.Segment("text", null, "old")),
                FeedbackTiming.segments(Message.assistant("old", null), "old", null));
    }

    private Message message(final FeedbackTiming timing, final String content) {
        return new Message("assistant", content, null, null, null, timing.merge(null));
    }

    private Clock clock(final long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }
}
