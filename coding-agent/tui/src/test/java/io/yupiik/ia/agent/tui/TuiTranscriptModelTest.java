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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.SubagentDeliveryBus;
import io.yupiik.ia.agent.core.context.ContextMeasurement.Snapshot;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TuiTranscriptModelTest {
    @TempDir
    private static Path eventWorkdir;

    private final TuiTranscriptModel model = new TuiTranscriptModel(new ThinkTagSplitter());

    @Test
    void freshModelFallsBackToWelcomeMarkdown() {
        assertFalse(model.busy());
        assertEquals("", model.status());
        assertEquals("welcome", model.markdown("|", "welcome"));
    }

    @Test
    void startUserTurnAppendsUserBlockAndSetsBusy() {
        model.startUserTurn("do it");
        assertTrue(model.transcript().toString().contains("**You**\n\ndo it"));
        assertTrue(model.busy());
        assertEquals(0, model.anchorRow());
        assertTrue(model.markdown("|", "welcome").contains("do it"));
    }

    @Test
    void startUserTurnWithoutPinPreservesScrollWhileUserIsBrowsing() {
        model.maxViewOffset(120);
        model.anchorRow(42);
        model.userScrolled(true);
        model.startUserTurn("queued", false);
        assertEquals(42, model.anchorRow());
        assertTrue(model.userScrolled());
        assertTrue(model.transcript().toString().contains("**You**\n\nqueued"));
        assertTrue(model.busy());

        model.onEvent(new AgentEvent.TextDelta("more output", "s1", eventWorkdir));
        assertEquals(42, model.anchorRow());
    }

    @Test
    void startUserTurnWithPinRePinsScrollToLiveTail() {
        model.maxViewOffset(120);
        model.anchorRow(42);
        model.userScrolled(true);
        model.startUserTurn("explicit", true);
        assertEquals(120, model.anchorRow());
        assertFalse(model.userScrolled());
    }

    @Test
    void textDeltaAccumulatesPendingAndShowsItWhileBusy() {
        model.startUserTurn("hi");
        model.onEvent(new AgentEvent.TextDelta("hel", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.TextDelta("lo", "s1", eventWorkdir));
        assertEquals("hello", model.pending().toString());
        assertTrue(model.markdown("|", "welcome").contains("**Assistant**\n\nhello"));
    }

    @Test
    void turnDoneFlushesPendingAndClosesTurn() {
        model.startUserTurn("hi");
        model.onEvent(new AgentEvent.TextDelta("partial", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.TurnDone("final answer", "s1", eventWorkdir));
        assertTrue(model.transcript().toString().contains("**Assistant**\n\nfinal answer"));
        assertEquals("", model.pending().toString());
        assertFalse(model.busy());
        assertEquals("ready", model.status());
    }

    @Test
    void thinkingDeltaRePinsScrollToLiveTail() {
        model.maxViewOffset(80);
        model.anchorRow(42);
        model.onEvent(new AgentEvent.ThinkingDelta("pondering", "s1", eventWorkdir));
        assertEquals(80, model.anchorRow());
    }

    @Test
    void streamedEventLeavesAnchorRowUntouchedWhileUserBrowsing() {
        model.maxViewOffset(120);
        model.anchorRow(42);
        model.userScrolled(true);
        model.onEvent(new AgentEvent.TextDelta("more", "s1", eventWorkdir));
        assertEquals(42, model.anchorRow());
    }

    @Test
    void promptIntegratedRePinsScrollToLiveTail() {
        model.maxViewOffset(90);
        model.anchorRow(42);
        model.onEvent(new AgentEvent.PromptIntegrated("boost", "s1", eventWorkdir));
        assertEquals(90, model.anchorRow());
    }

    @Test
    void promptIntegratedLeavesAnchorRowUntouchedWhileUserBrowsing() {
        model.maxViewOffset(90);
        model.anchorRow(42);
        model.userScrolled(true);
        model.onEvent(new AgentEvent.PromptIntegrated("boost", "s1", eventWorkdir));
        assertEquals(42, model.anchorRow());
    }

    @Test
    void splitTurnsSeparatesAssistantFromUserBlocks() {
        final var md = "**Assistant**\n\nhello\n\n---\n\n**You**\n\nhi there\n\n---\n\n**Assistant**\n\nbye";
        final var turns = TuiTranscriptModel.splitTurns(md);
        assertEquals(3, turns.size());
        assertFalse(turns.get(0).user());
        assertTrue(turns.get(0).text().startsWith("**Assistant**"));
        assertTrue(turns.get(1).user());
        assertTrue(turns.get(1).text().contains("hi there"));
        assertFalse(turns.get(2).user());
        assertTrue(turns.get(2).text().contains("bye"));
    }

    @Test
    void replayedSubagentReportsAreNonUserMarkdownTurns() {
        for (final var state : List.of("completed", "failed", "cancelled")) {
            model.clearConversation();
            final var summary = "## Conclusion\n\n**Reviewed** the code.\n\n```java\nclass Review {}\n```\n";
            final var delivery = new SubagentDeliveryBus.Delivery(
                    "child-1", "review", summary, "completed".equals(state), "cancelled".equals(state));
            model.replay(List.of(
                    Message.user("review it"), Message.user(delivery.message()), Message.assistant("Thanks", null)));

            final var turns = TuiTranscriptModel.splitTurns(model.markdown("|", "welcome"));
            assertEquals(3, turns.size());
            assertTrue(turns.get(0).user());
            assertTrue(turns.get(0).text().contains("review it"));
            final var report = turns.get(1);
            assertFalse(report.user());
            assertTrue(report.text().contains("> subagent `review` " + state));
            assertTrue(report.text().contains(summary));
            assertFalse(report.text().contains("<subagent"));
            assertFalse(turns.get(2).user());
            assertTrue(turns.get(2).text().contains("Thanks"));
        }
    }

    @Test
    void splitTurnsKeepsPlainThematicBreaksInline() {
        final var md = "intro\n\n---\n\nstill assistant";
        final var turns = TuiTranscriptModel.splitTurns(md);
        assertEquals(1, turns.size());
        assertFalse(turns.get(0).user());
        assertTrue(turns.get(0).text().contains("---"));
    }

    @Test
    void splitTurnsHandlesLeadingContentAndEmptyInput() {
        final var first = TuiTranscriptModel.splitTurns("welcome");
        assertEquals(1, first.size());
        assertFalse(first.get(0).user());
        final var userFirst = TuiTranscriptModel.splitTurns("---\n\n**You**\n\nquestion");
        assertEquals(1, userFirst.size());
        assertTrue(userFirst.get(0).user());
        // the thematic break introduced the heading: it is chrome, not content
        assertFalse(userFirst.get(0).text().contains("---"));
    }

    @Test
    void splitTurnsSplitsOnSpeakerHeadingWithoutThematicBreak() {
        // this is the shape ConversationTranscript actually writes: the user marker gets a
        // `---` break, the assistant marker does not. The assistant answer must still start
        // its own segment, otherwise it renders inside the user turn.
        final var md = "---\n\n**You**\n\nquestion\n\n\n**Assistant**\n\nanswer";
        final var turns = TuiTranscriptModel.splitTurns(md);
        assertEquals(2, turns.size());
        assertTrue(turns.get(0).user());
        assertEquals("**You**\n\nquestion", turns.get(0).text());
        assertFalse(turns.get(1).user());
        assertTrue(turns.get(1).labelled());
        assertEquals("**Assistant**\n\nanswer", turns.get(1).text());
    }

    @Test
    void splitTurnsAttributedContinuedAssistantOutputAfterIntegratedPrompt() {
        // shape produced by ConversationTranscript after a queued prompt is integrated
        // mid-turn and the model keeps streaming: the resumed answer opens a fresh
        // **Assistant** segment so it renders with the output (accent) rail, not the
        // open **You** block's user rail
        final var md = "**Assistant**\n\nbefore\n\n---\n\n**You**\n\nqueued prompt\n\n**Assistant**\n\ncontinued";
        final var turns = TuiTranscriptModel.splitTurns(md);
        assertEquals(3, turns.size());
        assertFalse(turns.get(0).user());
        assertTrue(turns.get(1).user());
        assertTrue(turns.get(1).text().contains("queued prompt"));
        // the continued model output must not be glued onto the **You** segment
        assertFalse(turns.get(2).user());
        assertTrue(turns.get(2).labelled());
        assertEquals("**Assistant**\n\ncontinued", turns.get(2).text());
    }

    @Test
    void splitTurnsLabelsOnlyMarkerOpenedTurns() {
        // the leading welcome block carries no marker: it must not draw a speaker header,
        // while every turn opened by a real **You**/**Assistant** marker must
        final var md = "welcome text\n\n---\n\n**You**\n\nquestion\n\n---\n\n**Assistant**\n\nanswer";
        final var turns = TuiTranscriptModel.splitTurns(md);
        assertEquals(3, turns.size());
        assertFalse(turns.get(0).labelled(), "leading content has no marker and must stay unlabelled");
        assertTrue(turns.get(1).labelled());
        assertTrue(turns.get(1).user());
        assertTrue(turns.get(2).labelled());
        assertFalse(turns.get(2).user());
    }

    @Test
    void splitTurnsMarksMarkerOpenedFirstSegmentAsLabelled() {
        // a transcript starting directly on a marker still opened a labelled turn
        final var turns = TuiTranscriptModel.splitTurns("**You**\n\nquestion");
        assertEquals(1, turns.size());
        assertTrue(turns.get(0).labelled());
        assertTrue(turns.get(0).user());
    }

    @Test
    void splitTurnsMatchesMarkersWhoseTimestampDrifted() {
        // speaker labels embed {{formatDate}}: the line written when the turn started keeps
        // its own time while the marker looked up at render time shows the latest refresh,
        // so the two no longer compare as equal strings. The leading [time] token must not
        // break the boundary: the user header would otherwise leak into the body and inherit
        // the previous (assistant) coloring.
        final var md = "---\n\n**[10:11:26] rmannibucau**\n\nquestion\n\n"
                + "**[10:11:38] freeinference::deepseek-v4-flash**\n\nanswer";
        final var turns = TuiTranscriptModel.splitTurns(
                md, "**[10:11:39] rmannibucau**", "**[10:11:38] freeinference::deepseek-v4-flash**");
        assertEquals(2, turns.size());
        assertTrue(turns.get(0).user());
        assertTrue(turns.get(0).labelled());
        assertEquals("[10:11:26] rmannibucau", turns.get(0).label());
        assertTrue(turns.get(0).text().contains("question"));
        assertFalse(turns.get(1).user());
        assertTrue(turns.get(1).labelled());
        assertEquals("[10:11:38] freeinference::deepseek-v4-flash", turns.get(1).label());
        assertTrue(turns.get(1).text().contains("answer"));
    }

    @Test
    void splitTurnsMatchesMarkersDriftedOnBothSides() {
        // both markers drifted by a second: the roles must still be told apart and a user turn
        // following an assistant marker must not swallow the assistant coloring
        final var md = "**[10:11:20] Assistant**\n\nhello\n\n---\n\n" + "**[10:11:26] rmannibucau**\n\nquestion";
        final var turns = TuiTranscriptModel.splitTurns(md, "**[10:11:27] rmannibucau**", "**[10:11:21] Assistant**");
        assertEquals(2, turns.size());
        assertFalse(turns.get(0).user());
        assertTrue(turns.get(1).user());
        assertTrue(turns.get(1).labelled());
        assertEquals("[10:11:26] rmannibucau", turns.get(1).label());
    }

    @Test
    void splitTurnsCarriesWrittenLabelForStrictMarkers() {
        // labels without a timestamp keep strict equality and still expose their label so a
        // later template change cannot redraw an older header with different content
        final var turns = TuiTranscriptModel.splitTurns("**Boss**\n\ngo", "**Boss**", "**Robot**");
        assertEquals(1, turns.size());
        assertTrue(turns.get(0).user());
        assertTrue(turns.get(0).labelled());
        assertEquals("Boss", turns.get(0).label());
    }

    @Test
    void textResetDiscardsPendingAndThinkingBuffers() {
        model.onEvent(new AgentEvent.TextDelta("partial", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.ThinkingDelta("pondering", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.TextReset("s1", eventWorkdir));
        assertEquals("", model.pending().toString());
        assertFalse(model.markdown("|", "welcome").contains("partial"));
    }

    @Test
    void flushThinkingWrapsThoughtBetweenMarkersOnce() {
        model.onEvent(new AgentEvent.ThinkingDelta("let me ", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.ThinkingDelta("think", "s1", eventWorkdir));
        model.flushThinking();
        final var transcript = model.transcript().toString();
        assertTrue(transcript.contains(TuiTranscriptModel.THOUGHT_BEGIN + "\n**Thinking**\n\nlet me think\n"
                + TuiTranscriptModel.THOUGHT_END));
        model.flushThinking();
        assertEquals(1, count(transcript, TuiTranscriptModel.THOUGHT_BEGIN));
    }

    @Test
    void flushThinkingWithBlankContentWritesNothing() {
        model.onEvent(new AgentEvent.ThinkingDelta("   ", "s1", eventWorkdir));
        model.flushThinking();
        assertEquals("", model.transcript().toString());
    }

    @Test
    void toolStartedKeepsParallelToolsSeparate() {
        model.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}", "s1", eventWorkdir));
        assertEquals("bash", model.runningTool());
        assertEquals("ls -la", model.runningToolCommand());
        model.onEvent(new AgentEvent.ToolStream("bash", "file.txt", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.ToolStarted("read", "{}", "s1", eventWorkdir));
        // the newest pending tool drives the running label; the older parallel tool
        // keeps its own stream (no aliasing of a single running-tool slot)
        assertEquals("read", model.runningTool());
        assertEquals("", model.runningToolCommand());
        final var md = model.markdown("|", "welcome");
        assertTrue(md.contains("running read`"), "expected running line in: " + md);
        assertTrue(md.contains("running ls -la`"), "expected parallel running line in: " + md);
        assertTrue(md.contains("file.txt"), "parallel stream must survive: " + md);
    }

    @Test
    void markdownShowsRunningToolAndStreamedOutput() {
        model.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls\"}", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.ToolStream("bash", "out.txt", "s1", eventWorkdir));
        final var md = model.markdown("*", "welcome");
        assertTrue(md.contains("`* running ls`"));
        assertTrue(md.contains("```text\nout.txt\n```"));
    }

    @Test
    void toolStreamIgnoresOtherToolsAndCapsSize() {
        model.onEvent(new AgentEvent.ToolStarted("bash", "{}", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.ToolStream("other", "nope", "s1", eventWorkdir));
        assertEquals("", model.transcript().toString());
        model.onEvent(new AgentEvent.ToolStream("bash", "x".repeat(2000), "s1", eventWorkdir));
        assertTrue(model.markdown("|", "welcome").length() < 2200, "stream must stay capped");
    }

    @Test
    void toolFinishedFlushesStreamAsToolRegion() {
        model.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls\"}", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.ToolStream("bash", "a.txt", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.ToolFinished("bash", "", true, "s1", eventWorkdir));
        final var transcript = model.transcript().toString();
        assertTrue(transcript.contains(TuiTranscriptModel.TOOL_BEGIN));
        assertTrue(transcript.contains("*streaming `bash`: ls*"));
        assertTrue(transcript.contains("```text\na.txt\n```"));
        assertTrue(transcript.contains(TuiTranscriptModel.TOOL_END));
        assertNull(model.runningTool());
    }

    @Test
    void failedToolIsReported() {
        model.onEvent(new AgentEvent.ToolStarted("bash", "{}", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.ToolFinished("bash", "boom\n details", false, "s1", eventWorkdir));
        assertTrue(model.transcript().toString().contains("> tool `bash` failed: boom"));
    }

    @Test
    void nonStreamedToolSuccessFallsBackToFinishedOutput() {
        model.onEvent(new AgentEvent.ToolStarted("read", "{}", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.ToolFinished("read", "line1\nline2", true, "s1", eventWorkdir));
        final var transcript = model.transcript().toString();
        assertTrue(transcript.contains(TuiTranscriptModel.TOOL_BEGIN));
        assertTrue(transcript.contains("*streaming `read`*"));
        assertTrue(transcript.contains("```text\nline1\nline2\n```"));
        assertTrue(transcript.contains(TuiTranscriptModel.TOOL_END));
        assertNull(model.runningTool());
    }

    @Test
    void nonStreamedToolSuccessWithEmptyOutputWritesNothing() {
        model.onEvent(new AgentEvent.ToolStarted("read", "{}", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.ToolFinished("read", "", true, "s1", eventWorkdir));
        assertFalse(model.transcript().toString().contains(TuiTranscriptModel.TOOL_BEGIN));
    }

    @Test
    void usageUpdatedTracksTokens() {
        final var usage = new Usage(4000, 1200, 5200, 1500, 0);
        final var measurement = new Snapshot(42000, 42400, "provider-anchored", 40000, 2000, 1.05);
        model.onEvent(new AgentEvent.UsageUpdated(
                usage,
                42000,
                55.44,
                40000,
                1.05,
                null,
                "s1",
                null,
                null,
                AgentEvent.UsageUpdated.Quota.NONE,
                measurement,
                eventWorkdir));
        assertEquals(measurement, model.contextMeasurement());
        assertEquals(4000, model.promptTokens());
        assertEquals(1200, model.completionTokens());
        assertEquals(1500, model.cacheReadTokens());
        assertEquals(42000, model.estimatedContext());
        assertEquals(55, model.contextPercent());
        assertEquals(40000, model.lastRealPromptTokens());
        assertEquals(1.05, model.calibrationRatio(), 0.0001);
    }

    @Test
    void usageUpdatedWithoutUsageKeepsZeroCacheRead() {
        model.onEvent(new AgentEvent.UsageUpdated(null, 42000, 55.44, "s1", eventWorkdir));
        assertEquals(0, model.cacheReadTokens(), "no usage: cache-read must be 0");
    }

    @Test
    void usageUpdatedWithoutAnchorKeepsZeroLastReal() {
        model.onEvent(new AgentEvent.UsageUpdated(null, 42000, 55.44, "s1", eventWorkdir));
        assertEquals(42000, model.estimatedContext());
        assertEquals(0, model.lastRealPromptTokens(), "no anchor: lastReal must be 0");
        assertEquals(-1, model.calibrationRatio(), 0.0001, "no anchor: ratio must be -1");
    }

    @Test
    void compactedUpdatesStatusAndTranscript() {
        // a compaction/dedup event is side feedback only: it must not clobber the leading
        // status (spinner/ready/error) - the appliance is recorded for the sidebar instead
        model.onEvent(new AgentEvent.Compacted("auto", "SUMMARY", "s1", eventWorkdir));
        assertEquals("", model.status());
        assertEquals(1, model.appliances().size());
        assertEquals(List.of("auto"), model.appliances().getFirst().kinds());
        assertEquals("SUMMARY", model.appliances().getFirst().mode());
        assertTrue(model.transcript().toString().contains("> context auto applied (summary)"));
        // the next appliance replaces the previous in the sidebar slot (newest first, capped)
        model.onEvent(new AgentEvent.Compacted("dedup", "CLEARED", "s1", eventWorkdir));
        assertEquals(2, model.appliances().size());
        assertEquals(List.of("dedup"), model.appliances().getFirst().kinds());
        assertEquals("CLEARED", model.appliances().getFirst().mode());
        // the status is still untouched by the second event
        assertEquals("", model.status());
    }

    @Test
    void mergedCompactedRendersASingleJointLine() {
        // several reductions of the same checkpoint are merged into one event carrying all the
        // kinds: the transcript must show a single line instead of one line per strategy
        model.onEvent(new AgentEvent.Compacted(List.of("dedup", "prune"), "CLEARED", "s1", eventWorkdir));
        assertEquals(1, model.appliances().size());
        assertEquals(List.of("dedup", "prune"), model.appliances().getFirst().kinds());
        assertTrue(
                model.transcript().toString().contains("> context dedup, prune applied (cleared)"),
                "transcript: " + model.transcript());
    }

    @Test
    void compactedShowsTheReclaimMetricsWhenCarried() {
        // the event carries the raw estimated before/after pair: the durable transcript appends
        // the formatted segment and the sidebar appliance records the pair for the " -83%" suffix
        final var tokens = new AgentEvent.Compacted.CompactionTokens(513_000, 87_000);
        model.onEvent(new AgentEvent.Compacted(
                List.of("dedup", "compact-spill"), "SHRUNK", "s1", null, null, tokens, eventWorkdir));
        assertEquals(1, model.appliances().size());
        assertEquals(
                tokens,
                model.appliances().getFirst().tokens(),
                "the sidebar appliance must keep the metrics for its reclaim suffix");
        assertTrue(
                model.transcript()
                        .toString()
                        .contains("> context dedup, compact-spill applied (shrunk), 513k -> 87k (83%)"),
                "transcript: " + model.transcript());
    }

    @Test
    void noticeRendersDistinctTranscriptAndStatus() {
        model.onEvent(AgentEvent.Notice.error("boom", "s1", eventWorkdir));
        assertTrue(model.transcript().toString().contains("> ⚠ error: boom"));
        assertEquals("error: boom", model.status());
        model.onEvent(AgentEvent.Notice.info("all good", "s1", eventWorkdir));
        assertTrue(model.transcript().toString().contains("> ⓘ: all good"));
    }

    @Test
    void statusAndErrorEventsMapDirectly() {
        model.startUserTurn("hi");
        model.onEvent(new AgentEvent.TextDelta("draft", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.Status("working", "s1", eventWorkdir));
        assertEquals("working", model.status());
        model.onEvent(new AgentEvent.Error("kaput", "s1", eventWorkdir));
        assertTrue(model.transcript().toString().contains("> error: kaput"));
        assertFalse(model.busy());
        assertEquals("error: kaput", model.status());
        assertEquals("", model.pending().toString());
    }

    @Test
    void regionsParsesPlainThoughtAndToolBlocks() {
        final var md = "intro\n\n"
                + TuiTranscriptModel.THOUGHT_BEGIN + "\nthink\n" + TuiTranscriptModel.THOUGHT_END + "\n"
                + "middle\n\n"
                + TuiTranscriptModel.TOOL_BEGIN + "\ntool body\n" + TuiTranscriptModel.TOOL_END + "\n"
                + "outro";
        final var regions = TuiTranscriptModel.regions(md);
        assertEquals(5, regions.size());
        assertEquals("intro", regions.get(0).text().strip());
        assertFalse(regions.get(0).thought());
        assertFalse(regions.get(0).tool());
        assertTrue(regions.get(1).thought());
        assertFalse(regions.get(1).tool());
        assertEquals("think", regions.get(1).text().strip());
        assertEquals("middle", regions.get(2).text().strip());
        assertTrue(regions.get(3).tool());
        assertFalse(regions.get(3).thought());
        assertEquals("tool body", regions.get(3).text().strip());
        assertEquals("outro", regions.get(4).text().strip());
        assertFalse(regions.get(4).thought());
        assertFalse(regions.get(4).tool());
    }

    @Test
    void regionsToleratesDanglingMarkers() {
        assertTrue(TuiTranscriptModel.regions(TuiTranscriptModel.THOUGHT_END + "\nstray end")
                        .size()
                >= 1);
        assertTrue(TuiTranscriptModel.regions("start " + TuiTranscriptModel.TOOL_BEGIN + "\nnever closed")
                        .size()
                >= 1);
    }

    @Test
    void trimKeepsTranscriptUnderLimitOnLineBoundaries() {
        model.maxHistoryCharacters(40);
        model.transcript().setLength(0);
        model.transcript().append("aaaaaaaaaa\nbbbbbbbbbb\ncccccccccc\ndddddddddd\neeeeeeeeee\nffffffffff");
        model.trim();
        final var trimmed = model.transcript().toString();
        assertTrue(trimmed.length() <= 40, "expected trimmed transcript: " + trimmed);
        assertTrue(
                trimmed.startsWith("cccccccccc")
                        || trimmed.startsWith("dddddddddd")
                        || trimmed.startsWith("eeeeeeeeee")
                        || trimmed.startsWith("ffffffffff"),
                "expected trim to align on a line boundary: " + trimmed);
    }

    @Test
    void trimDropsDanglingRegions() {
        model.maxHistoryCharacters(30);
        model.transcript().setLength(0);
        model.transcript()
                .append(TuiTranscriptModel.TOOL_BEGIN + "\nlong tool output spanning lines\n"
                        + TuiTranscriptModel.TOOL_END + "\ntail");
        model.trim();
        final var trimmed = model.transcript().toString();
        assertEquals(
                count(trimmed, TuiTranscriptModel.TOOL_BEGIN),
                count(trimmed, TuiTranscriptModel.TOOL_END),
                "trimmed transcript must not carry a dangling tool region: " + trimmed);
    }

    @Test
    void trimIgnoresSmallTranscripts() {
        model.maxHistoryCharacters(10_000);
        model.transcript().setLength(0);
        model.transcript().append("small");
        model.trim();
        assertEquals("small", model.transcript().toString());
    }

    @Test
    void clearConversationResetsConversationButKeepsInstance() {
        model.startUserTurn("hi");
        model.onEvent(new AgentEvent.Status("busy", "s1", eventWorkdir));
        model.clearConversation();
        assertEquals("", model.transcript().toString());
        assertEquals("", model.thinking().toString());
        assertEquals(0, model.anchorRow());
        assertEquals("busy", model.status());
    }

    @Test
    void resetClearsEverything() {
        model.startUserTurn("hi");
        model.onEvent(new AgentEvent.TextDelta("x", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.ToolStarted("bash", "{}", "s1", eventWorkdir));
        model.onEvent(new AgentEvent.Status("busy", "s1", eventWorkdir));
        model.reset();
        assertFalse(model.busy());
        assertEquals("", model.status());
        assertEquals("", model.transcript().toString());
        assertEquals("", model.pending().toString());
        assertEquals("", model.thinking().toString());
        assertNull(model.runningTool());
        assertEquals(0, model.anchorRow());
        assertEquals(0, model.promptTokens());
        assertEquals(0, model.completionTokens());
        assertEquals(0, model.estimatedContext());
        assertEquals(0, model.contextPercent());
    }

    private static int count(final String value, final String needle) {
        var count = 0;
        for (int i = value.indexOf(needle); i >= 0; i = value.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
