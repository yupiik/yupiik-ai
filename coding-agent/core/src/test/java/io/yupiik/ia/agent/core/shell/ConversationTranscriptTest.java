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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.SubagentDeliveryBus;
import io.yupiik.ia.agent.core.configuration.CompressConfiguration;
import io.yupiik.ia.agent.core.configuration.ContextLimit;
import io.yupiik.ia.agent.core.context.ContextCompression;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.ToolCall.FunctionCall;
import io.yupiik.ia.agent.core.llm.Usage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationTranscriptTest {
    private static final Pattern USER_HEADER = Pattern.compile("\\*\\*You\\*\\*");

    @TempDir
    private static Path eventWorkdir;

    @Test
    void fullInputIsIndependentOfRawPromptAndResetsWithUsage() {
        final var transcript = new ConversationTranscript();
        transcript.onEvent(
                new AgentEvent.UsageUpdated(new Usage(5, 2, 7, 100, 20, 0, 0, 125), 68, 14, "s1", eventWorkdir));
        assertEquals(5, transcript.promptTokens());
        assertEquals(125, transcript.contextInputTokens());
        assertEquals(100, transcript.cacheReadTokens());
        assertEquals(68, transcript.estimatedContext());

        transcript.reset();
        assertEquals(0, transcript.promptTokens());
        assertEquals(0, transcript.contextInputTokens());
        assertEquals(0, transcript.cacheReadTokens());

        transcript.onEvent(
                new AgentEvent.UsageUpdated(new Usage(0, 2, 2, 100, 0, 0, 0, 100), 68, 14, "s1", eventWorkdir));
        assertEquals(100, transcript.contextInputTokens());
        transcript.onEvent(new AgentEvent.UsageUpdated(null, 68, 14, "s1", eventWorkdir));
        assertEquals(0, transcript.contextInputTokens());
    }

    private static CompressConfiguration config() {
        return new CompressConfiguration(
                true,
                new ContextLimit("50000", "100000"),
                15,
                5,
                true,
                false,
                false,
                List.of("my_tool"),
                false,
                4,
                true,
                true,
                4);
    }

    @Test
    void foldsTurnEventsIntoTranscript() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.ThinkingDelta("let me", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("read", "{\"path\":\"a.txt\"}", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStream("read", "body", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolFinished("read", "body", true, "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TurnDone("answer", "s1", eventWorkdir));

        final var md = t.transcript().toString();
        assertTrue(md.contains("**You**\n\nhi"));
        assertTrue(md.contains(ConversationTranscript.THOUGHT_BEGIN));
        assertTrue(md.contains("**Thinking**"));
        assertTrue(md.contains(ConversationTranscript.TOOL_BEGIN));
        assertTrue(md.contains("*streaming `read`: a.txt*"));
        assertTrue(md.contains("```text\nbody\n```"));
        assertTrue(md.contains("**Assistant**\n\nanswer"));
        assertFalse(t.busy());
        assertEquals("ready", t.status());
    }

    @Test
    void failedToolIsRecordedAsBlockquote() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.ToolStarted("bash", "{}", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolFinished("bash", "boom\nsecond", false, "s1", eventWorkdir));

        final var md = t.transcript().toString();
        assertTrue(md.contains("> tool `bash` failed: boom"));
    }

    @Test
    void textResetFlushesThinkingInsteadOfDroppingIt() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.ThinkingDelta("reasoning deeply", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TextReset("s1", eventWorkdir));
        assertEquals("", t.thinking().toString());
        assertTrue(t.transcript()
                .toString()
                .contains(ConversationTranscript.THOUGHT_BEGIN + "\n**Thinking**\n\nreasoning deeply\n"
                        + ConversationTranscript.THOUGHT_END));
    }

    @Test
    void cancellationDoesNotWriteAnErrorLine() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("partial answer", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TurnCancelled("s1", eventWorkdir));

        final var md = t.transcript().toString();
        assertTrue(md.contains("partial answer"), "streamed content stays in the transcript");
        assertFalse(md.contains("> error:"), "a user cancel is not an error: " + md);
        assertFalse(t.busy(), "a cancelled turn must not stay busy");
        assertEquals("cancelled", t.status(), "the transcript reports the cancellation status");
    }

    @Test
    void thinkTagAtTurnDoneBecomesThoughtRegion() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.TurnDone("<think>\nstep one\nstep two\n</think>\n\nThe answer", "s1", eventWorkdir));
        final var transcript = t.transcript().toString();
        assertTrue(transcript.contains(ConversationTranscript.THOUGHT_BEGIN));
        assertTrue(transcript.contains("**Thinking**\n\nstep one\nstep two"));
        assertTrue(transcript.contains(ConversationTranscript.THOUGHT_END));
        // raw text keeps the newline that followed </think>
        assertTrue(transcript.contains("The answer"));
        assertTrue(
                ConversationTranscript.regions(transcript).stream().anyMatch(r -> r.thought()),
                "expected a thought region in: " + transcript);
    }

    @Test
    void thinkTagStreamsLiveOnThoughtPanel() {
        final var t = new ConversationTranscript();
        t.startUserTurn("go");
        t.onEvent(new AgentEvent.TextDelta("<think>\nreasoning line\n</think>\n\ndrafting", "s1", eventWorkdir));
        // live reasoning is classified into the thinking buffer, visible text into pending
        assertTrue(
                t.thinking().toString().contains("reasoning line"), t.thinking().toString());
        // the raw newlines after </think> stay in the visible stream
        assertEquals("\n\ndrafting", t.pending().toString());
    }

    @Test
    void fencedThinkTagUntouched() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.TurnDone("```text\n<think>not a thought</think>\n```\n\nok", "s1", eventWorkdir));
        final var transcript = t.transcript().toString();
        assertFalse(transcript.contains(ConversationTranscript.THOUGHT_BEGIN), transcript);
        assertTrue(transcript.contains("<think>not a thought</think>"));
    }

    @Test
    void unclosedThinkTailAndWhitespaceTagAreReasoning() {
        final var parts = new ThinkTagSplitter().split("<think >\npartial reasoning");
        assertEquals("", parts.visible());
        assertEquals("partial reasoning", parts.thinking().strip());
        final var plain = new ThinkTagSplitter().split("plain text");
        assertEquals("plain text", plain.visible());
        assertEquals("", plain.thinking());
    }

    @Test
    void firstAnswerDeltaFlushesThinkingAboveAnswer() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.ThinkingDelta("reasoning deeply", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TextDelta("answer part", "s1", eventWorkdir));
        assertEquals("", t.thinking().toString());
        assertEquals("answer part", t.pending().toString());
        assertTrue(t.transcript()
                .toString()
                .contains(ConversationTranscript.THOUGHT_BEGIN + "\n**Thinking**\n\nreasoning deeply\n"
                        + ConversationTranscript.THOUGHT_END));
    }

    @Test
    void errorKeepsThinkingVisibleAboveTheErrorLine() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.ThinkingDelta("why it broke", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.Error("kaput", "s1", eventWorkdir));
        assertEquals("", t.thinking().toString());
        assertFalse(t.busy());
        final var transcript = t.transcript().toString();
        assertTrue(transcript.contains("why it broke"));
        assertTrue(transcript.contains("> error: kaput"));
        assertTrue(
                transcript.indexOf("why it broke") < transcript.indexOf("> error: kaput"),
                "expected reasoning to stay above the error line: " + transcript);
    }

    @Test
    void runningToolBlockIsWrappedInToolMarkers() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(
                new AgentEvent.ToolStarted("bash", "{\"command\":\"tail -f log\"}", 3, "s1", null, null, eventWorkdir));
        t.onEvent(new AgentEvent.ToolStream("bash", "streamed line", "s1", eventWorkdir));

        final var md = t.markdown("|", "welcome");
        assertTrue(md.contains(ConversationTranscript.TOOL_BEGIN));
        assertTrue(md.contains(ConversationTranscript.TOOL_END));
        assertTrue(md.contains("`| running tail -f log (#3)`"));
        assertTrue(md.contains("```text\nstreamed line\n```"));

        final var regions = ConversationTranscript.regions(md);
        assertTrue(regions.stream().anyMatch(ConversationTranscript.Region::tool));
    }

    @Test
    void runningToolBlockOmitsOrdinalWhenUnknown() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.ToolStarted("read", "{}", 0, "s1", null, null, eventWorkdir));

        final var md = t.markdown("|", "welcome");
        assertTrue(md.contains("`| running read`"), md);
        assertFalse(md.contains("(#"), md);
    }

    @Test
    void parallelRunningToolsEachCarryTheirOrdinal() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"mvn test\"}", 1, "s1", null, null, eventWorkdir));
        t.onEvent(
                new AgentEvent.ToolStarted("bash", "{\"command\":\"tail -f log\"}", 2, "s1", null, null, eventWorkdir));
        // stream events must land on the matching (last-started) in-flight call
        t.onEvent(new AgentEvent.ToolStream("bash", "line 1", "s1", eventWorkdir));

        final var md = t.markdown("|", "welcome");
        assertTrue(md.contains("`| running mvn test (#1)`"), md);
        assertTrue(md.contains("`| running tail -f log (#2)`"), md);
        assertTrue(md.contains("line 1"), md);
    }

    @Test
    void markdownIsCachedUntilContentActuallyChanges() {
        final var t = new ConversationTranscript();
        final var welcome = "> welcome";
        assertEquals(welcome, t.markdown("|", welcome), "blank transcript renders the fallback");

        t.appendTranscript("\n**Assistant**\n\nhello\n");
        final var second = t.markdown("|", welcome);
        final var third = t.markdown("|", welcome);
        assertSame(second, third, "no mutation -> the same cached String instance is reused (O(1) per-tick path)");

        t.appendTranscript("\nmore\n");
        assertNotSame(third, t.markdown("|", welcome), "a mutation must invalidate the markdown cache");
        assertTrue(t.markdown("|", welcome).contains("more"));
    }

    @Test
    void markdownCacheIsSpinnerSensitiveButNotThinkingSensitive() {
        final var t = new ConversationTranscript();
        t.appendTranscript("**Assistant**\n\nhi\n");
        final var a = t.markdown("|", "");
        final var b = t.markdown("*", "");
        assertNotSame(a, b, "a different spinner marker must rebuild the running-tool header");
        t.onEvent(new AgentEvent.ThinkingDelta("hm", "s1", eventWorkdir));
        final var c = t.markdown("*", "");
        assertSame(b, c, "live thinking is not part of markdown until it is flushed");
        t.flushThinking();
        assertTrue(t.markdown("*", "").contains("hm"), "flushing thinking invalidates markdown");
    }

    @Test
    void thinkingSnapshotHasAnIndependentSingleSlot() {
        final var compression = new ContextCompression(null);
        final var t = new ConversationTranscript(new ThinkTagSplitter(), false, compression);
        t.startUserTurn("go");
        final var md = t.markdown("|", "welcome");
        t.onEvent(new AgentEvent.ThinkingDelta("reason\n@123", "s1", eventWorkdir));
        final var thought = t.thinkingText();
        assertEquals("reason", thought);
        for (var i = 0; i < 1000; i++) {
            assertSame(thought, t.thinkingText());
            assertSame(md, t.markdown("|", "different fallback"));
        }

        t.onEvent(new AgentEvent.ThinkingDelta("@\nmore", "s1", eventWorkdir));
        assertEquals("reason\nmore", t.thinkingText());
        final var beforeReplay = t.thinkingText();
        t.replay(List.of());
        assertEquals("reason\nmore", t.thinkingText());
        assertNotSame(beforeReplay, t.thinkingText(), "replay invalidates the snapshot");
        final var beforeRebind = t.thinkingText();
        t.bindCompression(compression);
        assertEquals("reason\nmore", t.thinkingText());
        assertNotSame(beforeRebind, t.thinkingText());
        t.flushThinking();
        assertEquals("", t.thinkingText());
        assertTrue(t.markdown("|", "").contains("reason\nmore"));
        t.onEvent(new AgentEvent.ThinkingDelta("old", "s1", eventWorkdir));
        assertEquals("old", t.thinkingText());
        t.startUserTurn("next");
        assertEquals("", t.thinkingText());
        t.onEvent(new AgentEvent.ThinkingDelta("clear me", "s1", eventWorkdir));
        assertEquals("clear me", t.thinkingText());
        t.clearConversation();
        assertEquals("", t.thinkingText());
        t.onEvent(new AgentEvent.ThinkingDelta("reset me", "s1", eventWorkdir));
        assertEquals("reset me", t.thinkingText());
        t.reset();
        assertEquals("", t.thinkingText());
    }

    @Test
    void exposedThinkingBuilderNeverLeavesAStaleSnapshot() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.ThinkingDelta("first", "s1", eventWorkdir));
        assertEquals("first", t.thinkingText());
        final var builder = t.thinking();
        builder.replace(0, builder.length(), "other");
        assertEquals("other", t.thinkingText(), "same-length external edits must be observed");
        t.reset();
        assertEquals("", t.thinkingText());
        builder.append("retained");
        assertEquals("retained", t.thinkingText(), "a builder can remain exposed across reset");
    }

    @Test
    void inlineThinkingAndDrainedVisibleCarryInvalidateOnlyTheirOwnSurfaces() {
        final var t = new ConversationTranscript();
        t.startUserTurn("go");
        final var md = t.markdown("|", "");
        t.onEvent(new AgentEvent.TextDelta("<think>reasoning", "s1", eventWorkdir));
        assertEquals("reasoning", t.thinkingText());
        assertSame(md, t.markdown("|", ""), "inline thinking alone does not change markdown");
        t.onEvent(new AgentEvent.TextReset("s1", eventWorkdir));
        assertEquals("", t.thinkingText());
        assertTrue(t.markdown("|", "").contains("reasoning"));

        t.onEvent(new AgentEvent.TextDelta("answer`", "s1", eventWorkdir));
        final var beforeDrain = t.markdown("|", "");
        t.flushThinking();
        assertNotSame(beforeDrain, t.markdown("|", ""), "draining visible splitter carry changes markdown");
        assertTrue(t.markdown("|", "").endsWith("answer`"));
    }

    @Test
    void markdownNoTailPathPreservesFallbackAndWhitespaceSemantics() {
        final var t = new ConversationTranscript();
        assertEquals("first", t.markdown("|", "first"));
        assertEquals("first", t.markdown("|", "second"), "fallback is historically not a cache key");
        assertEquals("second", t.markdown("*", "second"));
        t.appendTranscript(" \n body \n ");
        assertEquals("body", t.markdown("*", "second"));
        t.onEvent(new AgentEvent.TextDelta("hidden", "s1", eventWorkdir));
        assertEquals("body", t.markdown("*", "second"), "pending is hidden when not busy");
        t.busy(true);
        assertEquals("body \n \n\n**Assistant**\n\nhidden", t.markdown("*", "second"));
    }

    @Test
    void oversizedThinkingBypassesSnapshotRetention() {
        final var transcript = new ConversationTranscript();
        final var source = "x".repeat(64 * 1024 + 1);
        transcript.onEvent(new AgentEvent.ThinkingDelta(source, "session", eventWorkdir));
        final var first = transcript.thinkingText();
        assertEquals(source, first);
        assertNotSame(first, transcript.thinkingText());
        transcript.reset();
        assertEquals("", transcript.thinkingText());
    }

    @Test
    void regionsPreserveEmptyAdjacentNestedAndUnclosedBlocks() {
        final var plain = new ConversationTranscript.Region("", false, false, false);
        assertEquals(List.of(plain), ConversationTranscript.regions(""));
        assertEquals(
                List.of(
                        plain,
                        new ConversationTranscript.Region("", true, false, false),
                        plain,
                        new ConversationTranscript.Region("", false, true, false),
                        plain,
                        new ConversationTranscript.Region("", false, false, true),
                        plain),
                ConversationTranscript.regions(ConversationTranscript.THOUGHT_BEGIN
                        + ConversationTranscript.THOUGHT_END
                        + ConversationTranscript.TOOL_BEGIN
                        + ConversationTranscript.TOOL_END
                        + ConversationTranscript.LOG_BEGIN
                        + ConversationTranscript.LOG_END));
        assertEquals(
                List.of(
                        new ConversationTranscript.Region("before", false, false, false),
                        new ConversationTranscript.Region(
                                "nested" + ConversationTranscript.LOG_BEGIN + "tail", true, false, false)),
                ConversationTranscript.regions("before" + ConversationTranscript.THOUGHT_BEGIN + "nested"
                        + ConversationTranscript.LOG_BEGIN + "tail"));
        assertEquals(
                List.of(
                        plain,
                        new ConversationTranscript.Region(ConversationTranscript.TOOL_BEGIN, true, false, false),
                        new ConversationTranscript.Region(ConversationTranscript.TOOL_END, false, false, false)),
                ConversationTranscript.regions(ConversationTranscript.THOUGHT_BEGIN
                        + ConversationTranscript.TOOL_BEGIN
                        + ConversationTranscript.THOUGHT_END
                        + ConversationTranscript.TOOL_END));
    }

    @Test
    void regionsScanRepeatedSingleTypeAndMixedBlocksWithinABoundedBudget() {
        for (final var types : List.of(List.of(0), List.of(1), List.of(2), List.of(0, 1, 2))) {
            final var md = new StringBuilder();
            final var expected = new ArrayList<ConversationTranscript.Region>();
            for (var i = 0; i < 1500; i++) {
                final var type = types.get(i % types.size());
                final var begin = type == 0
                        ? ConversationTranscript.THOUGHT_BEGIN
                        : type == 1 ? ConversationTranscript.TOOL_BEGIN : ConversationTranscript.LOG_BEGIN;
                final var end = type == 0
                        ? ConversationTranscript.THOUGHT_END
                        : type == 1 ? ConversationTranscript.TOOL_END : ConversationTranscript.LOG_END;
                md.append("plain<").append(begin).append("inner<").append(end);
                expected.add(new ConversationTranscript.Region("plain<", false, false, false));
                expected.add(new ConversationTranscript.Region("inner<", type == 0, type == 1, type == 2));
            }
            expected.add(new ConversationTranscript.Region("", false, false, false));
            assertEquals(expected, ConversationTranscript.regions(md.toString()));
        }
    }

    @Test
    void streamingTailIsScrubbedWhileDurableHeadStaysCached() {
        final var t = new ConversationTranscript();
        // a durable head carrying echoed seams + a bare anchor line that the per-write scrub of
        // flushPending/TurnDone would normally remove; appendTranscript is the RAW path so the
        // head still needs the render-time scrub (parity with the pre-split whole-string pass)
        t.appendTranscript("\n**Assistant**\n\nhello @9@ world\n");
        final var md = t.markdown("|", "");
        // a seam embedded in prose is INTENDED content and is preserved (the user policy);
        // only standalone seam LINES are scrubbed from the durable head
        assertTrue(md.contains("hello @9@ world"), "durable head prose seams must be preserved: " + md);

        // a streaming turn only mutates the pending tail: the durable head must be REUSED, so a
        // seam that shows up in the tail is scrubbed but the head is not re-derived each frame
        t.startUserTurn("go");
        t.onEvent(new AgentEvent.TextDelta("streaming @7@ answer", "s1", eventWorkdir));
        final var streaming = t.markdown("|", "");
        assertTrue(
                streaming.contains("streaming @7@ answer"),
                "streaming tail prose seams must be preserved: " + streaming);
        assertTrue(
                streaming.contains("hello @9@ world"),
                "durable head content must still render (prose seam preserved): " + streaming);
    }

    @Test
    void markdownHeadCacheIsInvalidatedByDurableMutationsAndReboundCompression() {
        final var t = new ConversationTranscript();
        t.appendTranscript("\n**Assistant**\n\nfirst\n");
        final var first = t.markdown("|", "");
        assertTrue(first.contains("first"));

        // appendTranscript is a durable mutation: the head (and hence the whole markdown) rebuilds
        t.appendTranscript("\n**Assistant**\n\nsecond\n");
        final var second = t.markdown("|", "");
        assertTrue(second.contains("second"));
        assertNotSame(first, second, "a durable append must rebuild the markdown");

        // a streaming delta alone is NOT a durable mutation: same head, new pending tail rendered
        t.startUserTurn("go");
        t.onEvent(new AgentEvent.TextDelta("streaming tail", "s1", eventWorkdir));
        final var third = t.markdown("|", "");
        assertTrue(third.contains("second"), "durable head must still render after a stream delta");
        assertTrue(third.contains("streaming tail"), "streaming pending tail must render");
        assertFalse(third.contains("@9@"), "no seam may leak through the reused head");

        // bindCompression swaps the scrubber: the cached head was sanitized against the old refs,
        // so it must be dropped and rebuilt on the next render (no stale seam refs leak)
        t.bindCompression(new ContextCompression(null));
        final var rebound = t.markdown("|", "");
        assertTrue(rebound.contains("second"), "rebinding must not lose the durable head: " + rebound);
    }

    @Test
    void markdownHeadCacheIsDroppedOnResetAndClearAndTrim() {
        final var t = new ConversationTranscript();
        t.appendTranscript("\n**Assistant**\n\nhello\n");
        assertTrue(t.markdown("|", "").contains("hello"));

        t.reset();
        assertTrue(t.markdown("|", "welcome").contains("welcome"), "reset must drop the cached head");
        // the outer markdown cache legitimately holds "welcome" from the call above (same spinner,
        // same contentVersion) - a *different* fallback still returns that cached string, which is
        // the pre-existing cache semantics (fallback is not part of the key), so assert through a
        // fresh mutation instead
        t.appendTranscript("\n**Assistant**\n\nnew start\n");
        assertTrue(t.markdown("|", "").contains("new start"), "post-reset content must render");

        t.appendTranscript("\n**Assistant**\n\nagain\n");
        assertTrue(t.markdown("|", "").contains("again"));

        t.clearConversation();
        assertTrue(t.markdown("|", "welcome").contains("welcome"), "clear must drop the cached head");

        // trim() head-truncates the durable transcript: the head cache must rebuild on the shorter
        // content (the classic compaction eviction path)
        t.appendTranscript("\n**Assistant**\n\nkept tail\n");
        t.maxHistoryCharacters(12);
        t.trim();
        final var trimmed = t.markdown("|", "");
        assertFalse(trimmed.contains("hello"), "trimmed head must not render the cut head");
        assertTrue(trimmed.contains("kept tail"), "trimmed head must keep the surviving tail: " + trimmed);
    }

    @Test
    void turnMetricsKeepDurationAndResetClearsIt() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.TurnMetrics(3, 120, 400, 500, 2, 1500, "s1", eventWorkdir));
        assertEquals(3, t.turnLlmCalls());
        assertEquals(1500, t.turnDurationMs());
        t.onEvent(new AgentEvent.ThinkingDelta("thought", "s1", eventWorkdir));
        assertEquals("thought", t.thinkingText());
        assertEquals(7, t.thinkingLength());

        t.reset();
        assertEquals(0, t.turnDurationMs());
        assertEquals("", t.thinkingText());
    }

    @Test
    void turnThroughputIsExposedAndResetClearsIt() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.TurnMetrics(
                1,
                0,
                0,
                0,
                0,
                900,
                new AgentEvent.Throughput(150, 0, 3000, 4000, 150, 1, 1, false),
                "s1",
                eventWorkdir));
        assertEquals(150, t.turnThroughput().outputTokens());
        assertEquals(50d, t.turnOutputTokensPerSecond(), 0.001d, "150 tokens over 3s of request time");
        assertEquals(37.5d, t.turnThroughput().reportedTokensPerSecond(), 0.001d, "the server view stays available");

        t.reset();
        assertEquals(0, t.turnOutputTokensPerSecond());
        assertFalse(t.turnThroughput().measurable());
    }

    @Test
    void turnThroughputPreservesEstimatesAndMatchedPartialServerTotals() {
        final var t = new ConversationTranscript();
        final var throughput = new AgentEvent.Throughput(200, 20, 4_000, 2_000, 80, 3, 1, true);
        t.onEvent(new AgentEvent.TurnMetrics(3, 0, 0, 0, 0, 5_000, throughput, "s1", eventWorkdir));
        assertEquals(throughput, t.turnThroughput());
        assertEquals(50d, t.turnOutputTokensPerSecond(), 0.001d);
        assertEquals(40d, t.turnThroughput().reportedTokensPerSecond(), 0.001d);
        assertEquals("request ~50.0 tok/s · server 40.0 tok/s (partial)", MetricsFormat.tokPerSec(t.turnThroughput()));
        t.reset();
        assertEquals(AgentEvent.Throughput.EMPTY, t.turnThroughput());
    }

    @Test
    void multiRoundTurnStaysChronologicalAndKeepsTheAnswersSupersededByTools() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        // round 1: think, say something, call a tool
        t.onEvent(new AgentEvent.ThinkingDelta("first I read the file", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TextDelta("let me look", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("read", "{\"path\":\"a.txt\"}", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolFinished("read", "body", true, "s1", eventWorkdir));
        // round 2: think again, then the real answer (TurnDone carries it in full)
        t.onEvent(new AgentEvent.ThinkingDelta("now I answer", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TurnDone("here is the answer", "s1", eventWorkdir));

        final var md = t.transcript().toString();
        // chronological: thought, what it said before the call, the tool, the final thought, the answer
        assertOrdered(
                md, "first I read the file", "let me look", "*streaming `read`", "now I answer", "here is the answer");
        // one label for the whole turn, not one per chunk
        assertEquals(1, countOccurrences(md, "**Assistant**"), md);
        // what the model said before calling the tool is not lost when the turn ends
        assertTrue(md.contains("**Assistant**\n\nlet me look"), md);
    }

    @Test
    void liveAnswerRendersAfterTheToolItFollowed() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("first part", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls\"}", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolFinished("bash", "a.txt", true, "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ThinkingDelta("hmm", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TextDelta(" second part", "s1", eventWorkdir));

        // the finished tool is part of the transcript, the streaming tail is not
        final var md = t.markdown("|", "welcome");
        assertOrdered(md, "**Assistant**\n\nfirst part", "*streaming `bash`", "hmm", " second part");
        // the live tail does not re-label the answer
        assertEquals(1, countOccurrences(md, "**Assistant**"), md);
    }

    @Test
    void splitterCarryDoesNotLeakIntoTheNextMessage() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        // round 1: the message ends with an inline-code backtick + " :" - the splitter
        // holds that tail back (waiting for a possible fence continuation) and the tool
        // call in the same round must flush it with THIS message, not the next one
        t.onEvent(new AgentEvent.TextDelta("je commence par le code de `AgentLoop.java` :", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("edit_file", "{}", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolFinished("edit_file", "oldString not found", false, "s1", eventWorkdir));
        // round 2: the next message must start clean, without the leaked "` :" prefix
        t.onEvent(new AgentEvent.TextDelta("Le fichier a peut-être bougé depuis.", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("read", "{\"path\":\"a.txt\"}", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolFinished("read", "body", true, "s1", eventWorkdir));

        final var md = t.transcript().toString();
        // round 1 keeps its full tail (backtick + " :") attached to the message that produced it
        assertTrue(md.contains("code de `AgentLoop.java` :"), md);
        // the flush writes the whole round-1 message before the failed tool, no truncation
        assertOrdered(md, "code de `AgentLoop.java` :", "> tool `edit_file` failed");
        // round 2 starts with the clean text: no "` :" residual prefix, no leading colon
        assertFalse(
                md.contains("> tool `edit_file` failed: oldString not found\n\n` :Le fichier"),
                "the splitter carry must not leak into the next message: " + md);
        // the failed tool footer carries the elapsed duration, so match on the message
        // boundary that follows it rather than on the exact footer text
        assertTrue(
                md.indexOf("Le fichier a peut-être bougé depuis.") > md.indexOf("oldString not found"),
                "round 2 must follow the failed tool: " + md);
        assertTrue(
                md.contains("\n\nLe fichier a peut-être bougé depuis."),
                "round 2 must start right after the tool block, un-prefixed: " + md);
        assertFalse(md.contains("` :Le"), "the splitter carry must not leak into the next message: " + md);
        assertEquals(
                1,
                countOccurrences(md, "`AgentLoop.java`"),
                "the inline code must appear exactly once, complete: " + md);
    }

    @Test
    void integratedPromptRendersBelowTheAnswerItInterrupted() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("I was saying something", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.PromptIntegrated("actually, do this instead", "s1", eventWorkdir));

        final var md = t.transcript().toString();
        assertOrdered(md, "I was saying something", "**You**\n\nactually, do this instead");
        assertEquals("", t.pending().toString());
    }

    @Test
    void resumedStreamAfterIntegratedPromptOpensAFreshAssistantBlock() {
        // a queued prompt picked up mid-turn cuts the assistant answer off with a **You** block;
        // when the turn then keeps streaming, the continued output must open a new **Assistant**
        // segment instead of gluing onto the **You** block and inheriting the user rail colour
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("I was saying something", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.PromptIntegrated("actually, do this instead", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TextDelta("continued output", "s1", eventWorkdir));

        final var md = t.markdown("|", "welcome");
        assertOrdered(
                md,
                "I was saying something",
                "**You**\n\nactually, do this instead",
                "**Assistant**\n\ncontinued output");
        assertTrue(
                md.indexOf("**You**\n\nactually, do this instead") < md.indexOf("**Assistant**\n\ncontinued output"),
                "the resumed assistant output must follow the integrated **You** block as its own turn: " + md);
    }

    @Test
    void errorKeepsTheStreamedAnswerAboveTheErrorLine() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("partial answer", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.Error("kaput", "s1", eventWorkdir));

        final var md = t.transcript().toString();
        assertOrdered(md, "partial answer", "> error: kaput");
    }

    @Test
    void aRetryResetDropsThePartialAnswerInsteadOfDuplicatingIt() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("half an ans", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TextReset("s1", eventWorkdir));
        t.onEvent(new AgentEvent.TextDelta("half an answer", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TurnDone("half an answer", "s1", eventWorkdir));

        final var md = t.transcript().toString();
        // the provider re-sends the whole text after a reset: the partial must not survive
        assertEquals(1, countOccurrences(md, "half an answer"), md);
        assertFalse(md.contains("half an ans\n"), md);
    }

    @Test
    void truncatedTurnDoneDoesNotClipTheStreamedTail() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        // the stream delivered the full answer, but the final message TurnDone carries is
        // truncated (a clipped provider reply): the fuller streamed text must survive
        t.onEvent(new AgentEvent.TextDelta(
                "the file is present. Let me look at the git diff of `TuiCommand.java` specifically",
                "s1",
                eventWorkdir));
        t.onEvent(new AgentEvent.TurnDone("the file is present. Let me look at the git diff of ", "s1", eventWorkdir));

        final var md = t.transcript().toString();
        assertTrue(
                md.contains("`TuiCommand.java` specifically"),
                "the streamed tail must survive a truncated TurnDone: " + md);
        assertEquals(
                1,
                countOccurrences(md, "the file is present. Let me look at the git diff of"),
                "the answer must appear exactly once, no duplication: " + md);
    }

    @Test
    void extendedTurnDoneAppendsOnlyTheExtraTail() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        // the stream ended early but the final message is complete (longer): only the
        // un-streamed remainder must be appended, without duplicating the prefix
        t.onEvent(new AgentEvent.TextDelta("let me look at the git diff of ", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TurnDone("let me look at the git diff of `TuiCommand.java`", "s1", eventWorkdir));

        final var md = t.transcript().toString();
        assertEquals(1, countOccurrences(md, "let me look at the git diff of"), md);
        assertTrue(md.contains("`TuiCommand.java`"), md);
    }

    @Test
    void identicalStreamedAndFinalTextDoesNotDuplicate() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("the answer", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TurnDone("the answer", "s1", eventWorkdir));

        final var md = t.transcript().toString();
        assertEquals(1, countOccurrences(md, "the answer"), md);
    }

    private static void assertOrdered(final String md, final String... fragments) {
        var previous = -1;
        for (final var fragment : fragments) {
            final var index = md.indexOf(fragment);
            assertTrue(index >= 0, () -> "missing \"" + fragment + "\" in: " + md);
            assertTrue(index > previous, () -> "\"" + fragment + "\" is out of order in: " + md);
            previous = index;
        }
    }

    private static int countOccurrences(final String text, final String needle) {
        var count = 0;
        for (var index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void statusNoticeAndUsageAreTracked() {
        final var t = new ConversationTranscript();
        t.onEvent(AgentEvent.Notice.warn("careful", "s1", eventWorkdir));
        // WARN notices are durable transcript lines only: the leading status slot stays empty
        // (it is reserved for the busy/ready state, errors and the dedicated Status channel)
        assertEquals("", t.status());
        assertTrue(t.transcript().toString().contains("> ⚠ warning: careful"));

        t.onEvent(new AgentEvent.Status("working", "s1", eventWorkdir));
        assertEquals("working", t.status());

        t.onEvent(new AgentEvent.UsageUpdated(
                null, 42, 12.4, 40, 1.25, null, "s1", null, null, AgentEvent.UsageUpdated.Quota.NONE, eventWorkdir));
        assertEquals(42, t.estimatedContext());
        assertEquals(12, t.contextPercent());
        assertEquals(40, t.lastRealPromptTokens());
        assertEquals(1.25, t.calibrationRatio(), 0.0001);
        assertEquals(0, t.cacheReadTokens(), "null usage: cache-read must be 0");

        t.onEvent(new AgentEvent.UsageUpdated(
                new Usage(4000, 1200, 5200, 1500, 0),
                42,
                12.4,
                40,
                1.25,
                null,
                "s1",
                null,
                null,
                AgentEvent.UsageUpdated.Quota.NONE,
                eventWorkdir));
        assertEquals(1500, t.cacheReadTokens(), "usage cache-read must be captured");
    }

    @Test
    void quotaIsPersistedAndHiddenWhenNone() {
        final var t = new ConversationTranscript();
        // no quota -> not visible
        t.onEvent(new AgentEvent.UsageUpdated(
                null, 42, 12.4, 40, 1.25, null, "s1", null, null, AgentEvent.UsageUpdated.Quota.NONE, eventWorkdir));
        assertFalse(t.quotaVisible());

        // a real quota -> persisted fields
        final var quota =
                new AgentEvent.UsageUpdated.Quota(true, true, "plus", 42.5, 18000, 1700000000L, "primary", "10", false);
        t.onEvent(new AgentEvent.UsageUpdated(null, 42, 12.4, 40, 1.25, null, "s1", null, null, quota, eventWorkdir));
        assertTrue(t.quotaVisible());
        assertEquals("plus", t.quotaPlanType());
        assertEquals(42.5, t.quotaUsedPercent(), 0.0001);
        assertEquals(18000, t.quotaWindowSeconds());
        assertEquals(1700000000L, t.quotaResetAtEpochSeconds());
        assertEquals("primary", t.quotaWindowLabel());
        assertEquals("10", t.quotaCreditsBalance());
        assertFalse(t.quotaCreditsUnlimited());
    }

    @Test
    void replayAppendsPersistedHistory() {
        final var t = new ConversationTranscript();
        t.replay(List.of(
                Message.system("ignored"),
                Message.user("question"),
                Message.assistant("reply", null),
                Message.tool("call-1", "tool output\nmore")));

        final var md = t.transcript().toString();
        assertFalse(md.contains("ignored"));
        assertTrue(md.contains("---\n\n**You**\n\nquestion"));
        assertTrue(md.contains("---\n\n**Assistant**\n\nreply"));
        assertTrue(md.contains("*tool -> tool output*"));
    }

    @Test
    void replayOmitsInternalCompactionDigestsWithoutChangingModelHistory() {
        final var digest = "<compacted-context>\n## Objective\nInternal history\n</compacted-context>";
        final var guidance = """
                When a compacted context is provided, treat it as historical state.
                Do not reload, reconstruct, or repeat actions already described in it.
                Continue from the state it represents; only call tools when needed for the current task.""";
        for (final var text : List.of(digest, digest + "\n\n" + guidance, digest + "\n  ")) {
            final var message = Message.user(text);
            final var history = List.of(message, Message.user("continue"), Message.assistant("answer", null));
            final var transcript = new ConversationTranscript();
            final var md = transcript.replay(history);

            assertEquals("\n\n---\n\n**You**\n\ncontinue\n\n\n---\n\n**Assistant**\n\nanswer\n", md);
            assertEquals(md, transcript.transcript().toString());
            assertEquals(3, history.size());
            assertEquals("user", message.role());
            assertEquals(text, message.content());
        }
    }

    @Test
    void replayKeepsQuotedMalformedAndAssistantCompactionText() {
        final var digest = "<compacted-context>\nInternal history\n</compacted-context>";
        for (final var text : List.of(
                "Example: " + digest,
                "```xml\n" + digest + "\n```",
                digest + "\nWhy is this shown?",
                "<compacted-context>\nunclosed example")) {
            final var transcript = new ConversationTranscript();
            assertEquals("\n\n---\n\n**You**\n\n" + text + '\n', transcript.replay(List.of(Message.user(text))));
        }
        final var transcript = new ConversationTranscript();
        assertEquals(
                "\n\n---\n\n**Assistant**\n\n" + digest + '\n',
                transcript.replay(List.of(Message.assistant(digest, null))));
    }

    @Test
    void replayDisplaysSubagentDeliveriesWithoutChangingStoredMessages() {
        final var summary = "## Conclusion\n\n**Reviewed** the code.\n\n```java\nclass Review {}\n```\n";
        for (final var state : List.of("completed", "failed", "cancelled")) {
            final var delivery = new SubagentDeliveryBus.Delivery(
                    "child-1", "review", summary, "completed".equals(state), "cancelled".equals(state));
            final var message = Message.user(delivery.message());
            final var transcript = new ConversationTranscript();
            final var md = transcript.replay(List.of(Message.user("review it"), message));

            assertTrue(md.contains("**You**\n\nreview it"), md);
            assertEquals(1, USER_HEADER.split(md, -1).length - 1, md);
            assertTrue(md.contains("**Assistant**\n\n\n\n> subagent `review` " + state), md);
            assertTrue(md.contains(summary), md);
            assertTrue(md.endsWith("> full report: /subagents child-1\n"), md);
            assertFalse(md.contains("<subagent"), md);
            assertFalse(md.contains("</subagent>"), md);
            assertEquals(md, transcript.transcript().toString());
            assertEquals("user", message.role());
            assertEquals(delivery.message(), message.content());
            assertFalse(transcript.busy());
            assertEquals("", transcript.status());
        }
    }

    @Test
    void replayKeepsOrdinaryAndMalformedDeliveryTextAsUserTurns() {
        final var envelope = new SubagentDeliveryBus.Delivery("child-1", "review", "## Result", true, false).message();
        for (final var text : List.of(
                "Please review **this**",
                "Example: " + envelope,
                "> " + envelope.replace("\n", "\n> "),
                "```xml\n" + envelope + "\n```",
                envelope + "\nMore user text",
                envelope.replace("</subagent>", ""),
                envelope.replace("state=\"completed\"", "state=\"unknown\""),
                envelope.replace("sessionID=\"child-1\"", "sessionID=\"\""))) {
            final var transcript = new ConversationTranscript();
            assertEquals("\n\n---\n\n**You**\n\n" + text + '\n', transcript.replay(List.of(Message.user(text))));
        }
    }

    @Test
    void replayOnlyReclassifiesUserRoleDeliveries() {
        final var text = new SubagentDeliveryBus.Delivery("child-1", null, null, true, false).message();
        final var transcript = new ConversationTranscript();
        assertEquals(
                "\n\n---\n\n**Assistant**\n\n" + text + '\n',
                transcript.replay(List.of(Message.assistant(text, null))));
    }

    @Test
    void replaySubagentReportUsesIdForMissingNameAndShieldsSpeakerMarkers() {
        final var transcript = new ConversationTranscript();
        final var delivery = new SubagentDeliveryBus.Delivery(
                "child-1", null, "## Report\n\n**You**\n\n**Assistant**\n\nrest", true, false);
        final var md = transcript.replay(List.of(Message.user(delivery.message())));
        assertTrue(md.contains("> subagent `child-1` completed"), md);
        assertTrue(md.contains("\u00a0**You**"), md);
        assertTrue(md.contains("\u00a0**Assistant**"), md);
        assertFalse(md.contains("<subagent"), md);
    }

    @Test
    void replayRendersPersistedSystemNotices() {
        final var t = new ConversationTranscript();
        t.replay(List.of(
                Message.user("question"),
                Message.assistant("reply", null),
                new Message(
                        "system",
                        "Maximum iterations reached (250): turn stopped; resume starts a fresh window.",
                        null,
                        null,
                        null,
                        null)));

        final var md = t.transcript().toString();
        assertTrue(
                md.contains("> ⚠ Maximum iterations reached (250)"),
                "the persisted cap notice must be rendered in the replay: " + md);
    }

    @Test
    void replayResolvesToolNameFromAssistantCall() {
        final var t = new ConversationTranscript();
        t.replay(List.of(
                Message.assistant(
                        "reading", List.of(new ToolCall("call-1", "function", new FunctionCall("grep", "{}"), null))),
                Message.tool("call-1", "tool output\nmore")));

        final var md = t.transcript().toString();
        assertTrue(md.contains("*tool `grep` -> tool output*"));
        assertFalse(md.contains("null"));
    }

    @Test
    void replayUsesStoredToolNameOnToolMessage() {
        final var t = new ConversationTranscript();
        t.replay(List.of(
                Message.assistant(
                        "reading", List.of(new ToolCall("call-1", "function", new FunctionCall("grep", "{}"), null))),
                Message.toolResult("call-1", "tool output\nmore", "grep")));

        final var md = t.transcript().toString();
        assertTrue(md.contains("*tool `grep` -> tool output*"));
        assertFalse(md.contains("null"));
    }

    @Test
    void regionsSplitThoughtAndToolBlocks() {
        final var md = "before"
                + ConversationTranscript.THOUGHT_BEGIN
                + "thinking"
                + ConversationTranscript.THOUGHT_END
                + "middle"
                + ConversationTranscript.TOOL_BEGIN
                + "tool"
                + ConversationTranscript.TOOL_END
                + "after";

        final var regions = ConversationTranscript.regions(md);
        assertEquals(5, regions.size());
        assertEquals(new ConversationTranscript.Region("before", false, false, false), regions.get(0));
        assertTrue(regions.get(1).thought());
        assertTrue(regions.get(3).tool());
        assertEquals("after", regions.get(4).text());
    }

    @Test
    void regionsSplitLogBlocks() {
        final var md =
                "before" + ConversationTranscript.LOG_BEGIN + "captured" + ConversationTranscript.LOG_END + "after";

        final var regions = ConversationTranscript.regions(md);
        assertEquals(3, regions.size());
        assertEquals("before", regions.get(0).text());
        assertFalse(regions.get(0).thought());
        assertFalse(regions.get(0).tool());
        assertFalse(regions.get(0).log());
        assertEquals("captured", regions.get(1).text());
        assertTrue(regions.get(1).log());
        assertFalse(regions.get(1).thought());
        assertFalse(regions.get(1).tool());
        assertEquals("after", regions.get(2).text());
    }

    @Test
    void logRegionInterleavedWithThoughtAndToolIsTyped() {
        final var md = ConversationTranscript.THOUGHT_BEGIN
                + "thinking"
                + ConversationTranscript.THOUGHT_END
                + ConversationTranscript.LOG_BEGIN
                + "logged"
                + ConversationTranscript.LOG_END
                + ConversationTranscript.TOOL_BEGIN
                + "tooled"
                + ConversationTranscript.TOOL_END;

        final var regions = ConversationTranscript.regions(md);
        assertEquals(7, regions.size(), "open and tail plain regions surround each marker block");
        assertTrue(regions.get(1).thought());
        assertTrue(regions.get(3).log());
        assertTrue(regions.get(5).tool());
    }

    @Test
    void appendTranscriptLogRegionIsRoundTripped() {
        final var t = new ConversationTranscript();
        t.appendTranscript(ConversationTranscript.LOG_BEGIN + "\n[10:00:00]\nINFO StructuralCodeGraph\nindexed\n"
                + ConversationTranscript.LOG_END);

        final var regions = ConversationTranscript.regions(t.transcript().toString());
        final var region = regions.stream()
                .filter(ConversationTranscript.Region::log)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log region in: " + t.transcript()));
        assertFalse(region.thought());
        assertFalse(region.tool());
        assertTrue(region.text().contains("[10:00:00]"));
        assertTrue(region.text().contains("indexed"));
    }

    @Test
    void trimKeepsTailAndDropsDanglingRegions() {
        final var t = new ConversationTranscript();
        t.transcript().append("x\n".repeat(100));
        t.flushThinking();
        t.maxHistoryCharacters(50);
        t.trim();

        final var md = t.transcript().toString();
        assertTrue(md.length() < 100);
        assertFalse(md.contains(ConversationTranscript.THOUGHT_BEGIN));
        assertFalse(md.contains(ConversationTranscript.THOUGHT_END));
    }

    @Test
    void resetClearsEverything() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("streaming", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.UsageUpdated(null, 10, 5.0, "s1", eventWorkdir));
        t.reset();

        assertEquals("", t.transcript().toString());
        assertEquals("", t.pending().toString());
        assertFalse(t.busy());
        assertEquals("", t.status());
        assertEquals(0, t.estimatedContext());
        assertEquals(0, t.turnLlmCalls());
    }

    @Test
    void queuedPromptIntegratesIntoRunningTurn() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.ThinkingDelta("thought", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.PromptIntegrated("follow up", "s1", eventWorkdir));

        assertTrue(t.busy(), "the turn must stay busy while a queued prompt runs");
        assertEquals("queued prompt integrated", t.status());
        assertEquals("", t.thinking().toString(), "pending thinking must be flushed into the transcript");
        final var md = t.transcript().toString();
        assertTrue(md.contains("thought"), "flushed thinking must be visible above the prompt: " + md);
        assertTrue(md.contains("**You**\n\nfollow up"), "queued prompt must be folded under a You block: " + md);
        assertTrue(md.indexOf("thought") < md.indexOf("**You**\n\nfollow up"));

        t.onEvent(new AgentEvent.TurnDone("answer", "s1", eventWorkdir));
        assertFalse(t.busy());
    }

    @Test
    void parallelToolsEachKeepTheirOwnStreamAndCommand() {
        final var t = new ConversationTranscript();
        // two parallel read-only tools interleave their starts and streams
        t.onEvent(new AgentEvent.ToolStarted("read", "{\"path\":\"a.txt\"}", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStream("read", "content-of-a", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("grep", "{\"command\":\"grep -r x\"}", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStream("grep", "match-line", "s1", eventWorkdir));
        // A finishes first: its card must carry A's command and A's stream only
        t.onEvent(new AgentEvent.ToolFinished("read", "content-of-a", true, "s1", eventWorkdir));
        final var mdAfterA = t.transcript().toString();
        assertTrue(mdAfterA.contains("*streaming `read`: a.txt*"), mdAfterA);
        assertTrue(mdAfterA.contains("content-of-a"), mdAfterA);
        assertFalse(mdAfterA.contains("match-line"), "B's stream must not leak into A's card: " + mdAfterA);
        // B finishes: its own card with its own content, ordinal strictly after A's
        t.onEvent(new AgentEvent.ToolFinished("grep", "match-line", true, "s1", eventWorkdir));
        final var md = t.transcript().toString();
        assertTrue(md.contains("*streaming `grep`: grep -r x*"), md);
        assertTrue(md.contains("match-line"), md);
        assertEquals(2, t.toolCallCount(), "one ordinal per finished call, in finish order");
        final var iA = md.indexOf("read");
        final var iB = md.indexOf("grep");
        assertTrue(iA < iB, "cards must follow finish order: " + md);
    }

    @Test
    void subagentStreamsNeverPolluteTheParentTranscript() {
        final var t = new ConversationTranscript();
        // subagent-tagged events are dropped from a parent transcript...
        t.onEvent(new AgentEvent.ThinkingDelta("child reasoning", "s1", "sub-1", "child", eventWorkdir));
        t.onEvent(new AgentEvent.TextDelta("child answer", "s1", "sub-1", "child", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("bash", "{}", -1, "s1", "sub-1", "child", eventWorkdir));
        t.onEvent(new AgentEvent.ToolFinished("bash", "out", true, "s1", "sub-1", "child", eventWorkdir));
        assertEquals("", t.transcript().toString());
        assertEquals("", t.pending().toString());
        assertEquals("", t.thinking().toString());
        // ...while the lifecycle markers are still folded in
        t.onEvent(new AgentEvent.SubagentStarted("do the thing", "s1", "sub-1", "child", eventWorkdir));
        t.onEvent(new AgentEvent.SubagentCompleted("done", true, "s1", "sub-1", "child", eventWorkdir));
        final var md = t.transcript().toString();
        assertTrue(md.contains("> subagent `child` started: do the thing"), md);
        assertTrue(md.contains("> subagent `child` completed"), md);
        assertTrue(md.contains("> full report: /subagents sub-1"), md);
        assertFalse(md.contains("child reasoning"), md);
        assertFalse(md.contains("child answer"), md);
        assertFalse(md.contains("bash"), md);
    }

    @Test
    void subagentCompletedEmbedsTheFullSummaryAsMarkdownBody() {
        final var t = new ConversationTranscript();
        final var summary = "## Findings\n"
                + "- item one\n"
                + "- item two\n"
                + "\n"
                + "```java\n"
                + "var x = 1;\n"
                + "```\n"
                + "done";
        t.onEvent(new AgentEvent.SubagentCompleted(
                summary, true, false, "s1", "sub-9", "explorer", "gpt-5.5", eventWorkdir));
        final var md = t.transcript().toString();
        // the header carries the effective model
        assertTrue(md.contains("> subagent `explorer` completed — model `gpt-5.5`"), md);
        // the body is embedded raw (no blockquote prefix) so the markdown renderer highlights it
        assertTrue(md.contains("## Findings"), md);
        assertTrue(md.contains("- item one"), md);
        assertTrue(md.contains("```java"), md);
        assertTrue(md.contains("var x = 1;"), md);
        // the pointer line closes the report
        assertTrue(md.contains("> full report: /subagents sub-9"), md);
        // the body lines must not be quoted: a line starting with '>' would render as a quote
        final var after = md.substring(md.indexOf("## Findings"));
        assertFalse(
                after.lines().anyMatch(l -> l.startsWith("> ") && l.contains("item")), "body must be unquoted: " + md);
    }

    @Test
    void subagentCompletedSummaryLineMatchingASpeakerMarkerIsShielded() {
        final var t = new ConversationTranscript();
        // a summary line equal to the assistant marker would split the pane into a new turn
        t.onEvent(new AgentEvent.SubagentCompleted(
                "findings\n\n**Assistant**\n\nrest", false, true, "s1", "sub-2", "child", eventWorkdir));
        final var md = t.transcript().toString();
        // the marker-like line is shielded with a non-breaking space so the TUI never opens a turn
        assertTrue(md.contains("\u00A0**Assistant**"), md);
        assertTrue(md.contains("findings"), md);
        assertTrue(md.contains("rest"), md);
    }

    @Test
    void subagentCompletedCapsAVeryLongSummary() {
        final var t = new ConversationTranscript();
        final var huge = "word\n".repeat(3000); // 15000 chars, way past the 4000 cap
        t.onEvent(new AgentEvent.SubagentCompleted(huge, true, "s1", "sub-3", "child", eventWorkdir));
        final var md = t.transcript().toString();
        assertTrue(md.endsWith("> full report: /subagents sub-3\n"), "pointer line must close the report");
        assertTrue(md.length() < 6000, "body must be capped: " + md.length());
        assertTrue(md.contains("..."), "truncation marker must be present");
    }

    @Test
    void captureTranscriptReportsACancelledSubagentAsCancelled() {
        // a sub-agent stopped on an interrupt is a cancellation, not a failure: the parent
        // transcript must not paint it as a red error
        final var t = new ConversationTranscript(true);
        t.startUserTurn("go");
        t.onEvent(new AgentEvent.SubagentStarted("do the thing", "s1", "sub-1", "child", eventWorkdir));
        t.onEvent(new AgentEvent.SubagentCompleted(
                "Sub-agent cancelled", false, true, "s1", "sub-1", "child", eventWorkdir));
        final var md = t.transcript().toString();
        assertTrue(md.contains("> subagent `child` cancelled"), md);
        assertFalse(md.contains("failed"), md);
    }

    @Test
    void captureTranscriptFoldsSubagentStreams() {
        final var t = new ConversationTranscript(true);
        t.startUserTurn("go");
        t.onEvent(new AgentEvent.ThinkingDelta("child reasoning", "s1", "sub-1", "child", eventWorkdir));
        t.onEvent(new AgentEvent.TextDelta("child answer", "s1", "sub-1", "child", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("bash", "{}", -1, "s1", "sub-1", "child", eventWorkdir));
        t.onEvent(new AgentEvent.ToolFinished("bash", "out", true, "s1", "sub-1", "child", eventWorkdir));
        final var md = t.markdown("|", "");
        assertTrue(md.contains("child reasoning"), md);
        assertTrue(md.contains("child answer"), md);
        assertTrue(md.contains("out"), md);
    }

    @Test
    void retryableErrorClearsFromStatusOnNextProgress() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.Notice(
                AgentEvent.Notice.Level.ERROR, "LLM request failed (429) retry-after=1", "s1", eventWorkdir));
        assertTrue(t.status().startsWith("error:"), t.status());
        t.onEvent(new AgentEvent.TextDelta("recovered", "s1", eventWorkdir));
        assertFalse(t.status().startsWith("error:"), "a successful retry must clear the stale error: " + t.status());
    }

    @Test
    void retryWaitPauseIsTransientAndNeverTouchesStatus() {
        // provider-imposed delays (throttle/retry backoff) surface as a dedicated Pause event:
        // they must never write a durable transcript line nor replace the leading status
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.Pause(
                AgentEvent.Pause.Kind.RETRY, "LLM call failed, retrying in ~0s (attempt 2/3)", 0, "s1", eventWorkdir));
        assertFalse(t.status().startsWith("error:"), t.status());
        assertFalse(t.status().startsWith("LLM"), "a pause must not leak into the leading status: " + t.status());
        final var md = t.transcript().toString();
        assertFalse(md.contains("retrying in ~0s"), "a pause must not write a durable transcript line: " + md);
    }

    @Test
    void fatalErrorSurvivesTurnProgress() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.Error("kaput", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TextDelta("still streaming", "s1", eventWorkdir));
        assertEquals("error: kaput", t.status(), "non-retryable errors stay until acknowledged");
    }

    @Test
    void danglingFragmentBeforeAToolCardIsStillWithheld() {
        // a fragment arriving mid-turn is no longer the trailing run of the whole
        // markdown once the running-tool block is appended after the pending tail:
        // the withhold must apply to the assistant content before the UI wrappers
        final var compression = new ContextCompression(config());
        final var messages = List.of(Message.system("system"), Message.user("instruction"));
        compression.publish(messages);
        compression.renderRefs(messages);

        final var t = new ConversationTranscript();
        t.bindCompression(compression);
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("Let me run the proper typecheck.\n\n@1", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"git status\"}", "s1", eventWorkdir));

        final var md = t.markdown("|", "welcome");
        assertTrue(md.contains("Let me run the proper typecheck."), md);
        assertTrue(md.contains("git status"), md);
        assertFalse(md.contains("@1"), "fragment buried by the tool card must not render: " + md);
    }

    @Test
    void flushedPendingTailKeepsBareAnchorLineHidden() {
        // when flushPending() freezes the pending tail into the durable transcript
        // a leaked bare anchor line (the reported @1290-style leak) must not reappear
        // in the flushed text even once the tool card is appended after it
        final var compression = new ContextCompression(config());

        final var t = new ConversationTranscript();
        t.bindCompression(compression);
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("Good picture forming.\n\n@1290\n\n@1291", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls\"}", "s1", eventWorkdir));

        final var md = t.markdown("|", "welcome");
        assertTrue(md.contains("Good picture forming."), md);
        assertTrue(md.contains("ls"), md);
        assertFalse(md.contains("@1290"), "flushed bare anchor must stay hidden: " + md);
        assertFalse(md.contains("@1291"), "flushed bare anchor must stay hidden: " + md);
    }

    @Test
    void partialSeamAtFlushIsRequeuedAndCompletedByTheNextDelta() {
        // a seam tag split across a flush boundary: the trailing "@1250" (no closing @) must not
        // freeze into the durable transcript AND must not duplicate when the full content resumes
        final var compression = new ContextCompression(config());
        final var t = new ConversationTranscript();
        t.bindCompression(compression);

        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("answer @1250", "s1", eventWorkdir));
        // a tool call flushes the pending tail mid-stream (the seam is still incomplete)
        t.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls\"}", "s1", eventWorkdir));
        var md = t.markdown("|", "welcome");
        assertTrue(md.contains("answer"), md);
        assertFalse(md.contains("@1250"), "partial seam half must not freeze: " + md);

        // the closing half arrives after the tool block and the turn ends
        t.onEvent(new AgentEvent.ToolFinished("bash", "ok", true, "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TextDelta("4@ done", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TurnDone("answer @12504@ done", "s1", eventWorkdir));
        md = t.markdown("|", "welcome");
        // the re-queued first half + the second half re-form the complete tag, but it is glued to
        // prose ("answer ... done"), so under the content-preservation policy the intended content
        // `` survives; the trailing partial fragment "@1250" itself never freezes as a
        // half-seam (only the completed tag remains)
        assertFalse(md.contains("@1250\n"), "half-seam line must not freeze: " + md);
        assertTrue(md.contains("@12504@"), "prose-glued seam stays (intended content): " + md);
        assertTrue(md.contains("answer"), md);
        assertTrue(md.contains("done"), md);
        // the fragment was never persisted, so the final content is not duplicated
        assertEquals(1, occurrences(md, "done"), "the final content must appear exactly once: " + md);
    }

    @Test
    void incompleteSeamAtFlushDiesNaturallyAtTurnEnd() {
        // when the second half of a split tag never arrives (turn ends, or a TextReset clears the
        // pending tail), the re-queued fragment must be dropped without persisting anything
        final var compression = new ContextCompression(config());
        final var t = new ConversationTranscript();
        t.bindCompression(compression);

        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("answer @1250", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls\"}", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolFinished("bash", "ok", true, "s1", eventWorkdir));
        // the authoritative final content supersedes the streamed tail (no second half)
        t.onEvent(new AgentEvent.TurnDone("answer done", "s1", eventWorkdir));

        final var md = t.markdown("|", "welcome");
        assertFalse(md.contains("@1250"), "never-completed seam must not persist: " + md);
        assertTrue(md.contains("answer"), md);
        assertTrue(md.contains("done"), md);
    }

    private static int occurrences(final String text, final String needle) {
        var count = 0;
        var at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    @Test
    void perFrameFramesNeverRenderTheSeam() {
        // each intermediate frame of a seam streamed one character at a time must be
        // clean: "@", "@3", "@31" withheld as prefixes, "@3149@" stripped when complete
        final var compression = new ContextCompression(config());
        final var messages = List.of(Message.system("system"), Message.user("instruction"));
        compression.publish(messages);
        compression.renderRefs(messages);

        final var t = new ConversationTranscript();
        t.bindCompression(compression);
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("Let me commit.\n\n", "s1", eventWorkdir));
        for (final var frame : new String[] {"@", "1", "@"}) {
            t.onEvent(new AgentEvent.TextDelta(frame, "s1", eventWorkdir));
            final var md = t.markdown("|", "welcome");
            assertTrue(md.contains("Let me commit."), md);
            assertFalse(md.contains("@1"), "frame must not render the seam: " + md);
        }
    }

    @Test
    void literalNewlineSplitSeamIsScrubbedFromLiveMarkdown() {
        // some models emit the seam literally split across lines ("@3149\n@"):
        // with live-ref evidence both lines must disappear from the display
        final var compression = new ContextCompression(config());
        final var messages = List.of(Message.system("system"), Message.user("instruction"));
        compression.publish(messages);
        compression.renderRefs(messages);

        final var t = new ConversationTranscript();
        t.bindCompression(compression);
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("Let me commit.\n\n@1\n@", "s1", eventWorkdir));

        final var md = t.markdown("|", "welcome");
        assertTrue(md.contains("Let me commit."), md);
        assertFalse(md.contains("@1\n@"), "newline-split seam must not render live: " + md);
    }

    @Test
    void echoedSeamTagIsStrippedFromLiveMarkdown() {
        // a flash-class model may echo the injected "@N@" compact seam as a STANDALONE line:
        // the whole line is scrubbed from the live display
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("Let me run the proper typecheck.\n\n", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TextDelta("@535@\n\n", "s1", eventWorkdir));

        final var md = t.markdown("|", "welcome");
        assertTrue(md.contains("Let me run the proper typecheck."), md);
        assertFalse(md.contains("@535@"), "echoed seam must not render live: " + md);
    }

    @Test
    void echoedSeamTagSplitAcrossDeltasIsStrippedFromLiveMarkdown() {
        // the strip must run on the accumulated markdown, not per-delta, so a seam split
        // by the streamer ("@5" + "35@") is still removed when it forms a complete
        // STANDALONE line
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("answer\n@5", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.TextDelta("35@\n", "s1", eventWorkdir));

        final var md = t.markdown("|", "welcome");
        assertTrue(md.contains("answer"), md);
        assertFalse(md.contains("@535@"), "split echoed seam must not render live: " + md);
    }

    @Test
    void echoedSeamTagIsStrippedFromFlushedTranscript() {
        // the seam may arrive before a tool call flushes the pending tail into the
        // durable transcript: markdown() must scrub a STANDALONE seam line either way
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("let me check\n@12@\n", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls\"}", "s1", eventWorkdir));

        final var md = t.markdown("|", "welcome");
        assertTrue(md.contains("let me check"), md);
        assertFalse(md.contains("@12@"), "flushed echoed seam must not render live: " + md);
    }

    @Test
    void echoedSeamTagIsStrippedFromLiveThinkingText() {
        // the live (not yet flushed) reasoning tail is rendered directly off thinkingText(),
        // bypassing markdown(): a flash-class model echoing the injected "@N@" seam as a
        // STANDALONE line must not show it on the thought panel; a seam inside prose is
        // intended content and survives
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.ThinkingDelta("reasoning about\n@12@\n", "s1", eventWorkdir));
        t.onEvent(new AgentEvent.ThinkingDelta("then @b21@ [high] step", "s1", eventWorkdir));

        assertTrue(t.thinkingText().contains("reasoning about"), t.thinkingText());
        assertTrue(t.thinkingText().contains("then"), t.thinkingText());
        assertTrue(t.thinkingText().contains("step"), t.thinkingText());
        assertFalse(
                t.thinkingText().contains("@12@"),
                "standalone live thinking seam must not render: " + t.thinkingText());
        assertTrue(t.thinkingText().contains("@b21@"), "prose seam stays (intended content): " + t.thinkingText());
    }
}
