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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import io.yupiik.ia.agent.core.agent.SubagentDeliveryBus;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TranscriptRendererTest {
    private final TranscriptRenderer renderer = new TranscriptRenderer();

    @Test
    void emptySourceRendersEmpty() {
        assertEquals(List.of(), renderer.renderRegion("", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
    }

    @Test
    void replayedSubagentSummaryRendersMarkdownWithoutEnvelope() {
        final var model = new TuiTranscriptModel(new ThinkTagSplitter());
        final var delivery = new SubagentDeliveryBus.Delivery(
                "child-1",
                "review",
                "## Conclusion\n\n**Reviewed** the code.\n\n```java\nclass Review {}\n```",
                true,
                false);
        model.replay(List.of(Message.user("review it"), Message.user(delivery.message())));
        final var report =
                TuiTranscriptModel.splitTurns(model.markdown("|", "welcome")).get(1);
        assertFalse(report.user());
        final var rendered = renderer.renderRegion(report.text(), 100, Style.EMPTY, MarkdownStyles.DEFAULTS);
        final var text = String.join("\n", plain(rendered));
        assertTrue(text.contains("Conclusion"), text);
        assertTrue(text.contains("Reviewed the code."), text);
        assertTrue(text.contains("class Review {}"), text);
        assertFalse(text.contains("## Conclusion"), text);
        assertFalse(text.contains("**Reviewed**"), text);
        assertFalse(text.contains("```"), text);
        assertFalse(text.contains("<subagent"), text);
        assertFalse(text.contains("</subagent>"), text);
    }

    @Test
    void streamingCalloutMatchesColdRender() {
        assertEveryFrameMatchesAFullRender("before\n\n> [!WARNING]\n> first\n>\n> second\n\nafter");
    }

    @Test
    void lastSafeNewlineBeforeFence() {
        // safe after "a\n" (outside fence), not after "```\n", not at "code\n"
        assertEquals(2, renderer.lastSafeNewline("a\n```\ncode\n"));
        // safe again after the closing fence (after the line ending at index 14)
        assertEquals(15, renderer.lastSafeNewline("a\n```\ncode\n```\n"));
    }

    @Test
    void lastSafeNewlineOnlyTrailingPartialLine() {
        assertEquals(0, renderer.lastSafeNewline("no newline yet"));
        assertEquals(5, renderer.lastSafeNewline("abcd\nstill growing"));
        assertEquals(0, renderer.lastSafeNewline("```\ninside fence, no close\n"));
    }

    @Test
    void safeScannerPreservesLegacyHeuristicsForMixedAndPartialLines() {
        final var random = new Random(18371);
        final var rows = List.of(
                "plain",
                "",
                " \r\t",
                "\u2003",
                "\u00a0",
                "\u2003> quote",
                "lazy continuation",
                "```java",
                "~~~",
                " ```` |",
                "~~~ |",
                "| a |",
                "b | c",
                "---|---",
                "> | quote",
                "\ud83d\ude00",
                "  >",
                "`",
                "~~",
                "|",
                "\r```",
                "\u00a0> not stripped");
        assertTimeout(Duration.ofSeconds(1), () -> {
            for (var sample = 0; sample < 200; sample++) {
                final var source = new StringBuilder();
                for (var row = 0; row < 8; row++) {
                    source.append(rows.get(random.nextInt(rows.size()))).append('\n');
                }
                final var text = source.toString();
                // Each growing prefix includes incomplete fences and pipe rows.
                for (var cut = 0; cut <= text.length(); cut++) {
                    final var partial = text.substring(0, cut);
                    final var expected = legacySafeNewline(partial);
                    assertEquals(expected, renderer.lastSafeNewline(partial), partial);
                    assertEquals(expected, renderer.lastSafeNewline(new String(partial)), partial);
                }
            }
        });
    }

    @Test
    void boundaryBlankScansMatchSplitIncludingWhitespaceAndTerminatorArtifacts() {
        final var texts = List.of(
                "",
                "a",
                "a\n",
                "a\n\n",
                "\n",
                "\n\n",
                " \t\r",
                "\n \n",
                " \na\n ",
                "\u2003\n\u2002",
                "\u00a0\n",
                "\u001c\n",
                "\ud83d\ude00\n",
                "\ud800\n",
                "\n\udc00",
                "\n\r\n",
                "x\n \t\r\n\u2003");
        assertEquals(0, TranscriptRenderer.boundaryBlanks(null, null));
        for (final var left : texts) {
            for (final var right : texts) {
                assertEquals(
                        legacyBoundaryBlanks(left, right),
                        TranscriptRenderer.boundaryBlanks(left, right),
                        () -> "left=" + left + ", right=" + right);
            }
        }
        final var large = "body\n".repeat(20_000);
        for (var i = 0; i < 1_000; i++) {
            assertEquals(0, TranscriptRenderer.boundaryBlanks(large, large));
        }
    }

    @Test
    void safeMemoIsExactBoundedAndClearedByTheme() throws Exception {
        final var slot = TranscriptRenderer.class.getDeclaredField("lastSafeSource");
        slot.setAccessible(true);
        final var source = "lead\n| header\n";
        assertEquals(source.length(), renderer.lastSafeNewline(source));
        assertEquals(source, slot.get(renderer));
        assertEquals(5, renderer.lastSafeNewline(source + "| partial"));
        assertEquals(source.length(), renderer.lastSafeNewline(source));
        // Same length and same prefix do not constitute a memo hit.
        assertEquals(0, renderer.lastSafeNewline("```\ncode\n"));
        assertEquals(9, renderer.lastSafeNewline("abc\ncode\n"));
        final var large = "x\n".repeat(40_000);
        assertEquals(large.length(), renderer.lastSafeNewline(large));
        assertNull(slot.get(renderer));
        renderer.lastSafeNewline(source);
        renderer.theme(null, null, false);
        assertNull(slot.get(renderer));
        assertEquals(source.length(), renderer.lastSafeNewline(source));
    }

    @Test
    void prefixCacheEvictsByWeightAndDoesNotRetainOversizedSources() throws Exception {
        final var cacheField = TranscriptRenderer.class.getDeclaredField("prefixCache");
        cacheField.setAccessible(true);
        final var weightField = TranscriptRenderer.class.getDeclaredField("prefixCacheWeight");
        weightField.setAccessible(true);
        final var lastField = TranscriptRenderer.class.getDeclaredField("lastPrefix");
        lastField.setAccessible(true);
        final var cache = (Map<?, ?>) cacheField.get(renderer);
        final var padding = " ".repeat(12_000);
        for (var i = 0; i < 30; i++) {
            // No delta relationship; each is a different completed region.
            final var source = "[ref" + i + "]: https://example.com" + padding + "\n";
            final var rendered = renderer.renderRegionWithKinds(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
            assertTrue(rendered.lines().isEmpty());
            assertTrue(rendered.kinds().isEmpty());
            assertTrue(weightField.getInt(renderer) <= 256 * 1024);
        }
        assertTrue(cache.size() < 30, "weight budget must evict before the 64-entry cap");
        final var entries = cache.size();
        final var oversized = " ".repeat(66_000) + "\n";
        renderer.renderRegionWithKinds(oversized, 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        assertEquals(entries, cache.size());
        assertNull(lastField.get(renderer));
        renderer.theme(null, null, false);
        assertTrue(cache.isEmpty());
        assertEquals(0, weightField.getInt(renderer));
    }

    @Test
    void cachedResultsKeepDefensiveLinesAndKindsThroughHitsAndEviction() {
        final var source = "first\n\nsecond\n";
        final var expected = renderer.renderRegionWithKinds(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        final var changed = renderer.renderRegionWithKinds(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        changed.lines().clear();
        changed.kinds().clear();
        final var same = renderer.renderRegionWithKinds(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        assertEquals(plain(expected.lines()), plain(same.lines()));
        assertEquals(expected.kinds(), same.kinds());
        for (var i = 0; i < 70; i++) {
            renderer.renderRegion("region " + i + "\n", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)
                    .clear();
        }
        final var after = renderer.renderRegionWithKinds(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        assertEquals(plain(expected.lines()), plain(after.lines()));
        assertEquals(expected.kinds(), after.kinds());
    }

    private static int legacyBoundaryBlanks(final String left, final String right) {
        var count = 0;
        if (!left.isEmpty()) {
            final var lines = left.split("\n", -1);
            final var end = left.endsWith("\n") ? lines.length - 1 : lines.length;
            for (var i = end - 1; i >= 0 && lines[i].isBlank(); i--) {
                count++;
            }
        }
        if (!right.isEmpty()) {
            for (final var line : right.split("\n", -1)) {
                if (!line.isBlank()) {
                    break;
                }
                count++;
            }
        }
        return count;
    }

    private static int legacySafeNewline(final String source) {
        final var lines = source.split("\n", -1);
        var inFence = false;
        var inQuote = false;
        var safe = 0;
        var offset = 0;
        for (var i = 0; i < lines.length - 1; i++) {
            final var trimmed = lines[i].strip();
            if (trimmed.startsWith(">")) {
                inQuote = true;
            } else if (trimmed.isEmpty()) {
                inQuote = false;
            }
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
            }
            final var table =
                    lines[i].contains("|") && ((i > 0 && lines[i - 1].contains("|")) || lines[i + 1].contains("|"));
            offset += lines[i].length() + 1;
            if (!inFence && !inQuote && !table) {
                safe = offset;
            }
        }
        return safe;
    }

    @Test
    void renderMatchesMarkdownLines() {
        final var source = "hello\nworld";
        final var expected = new MarkdownLines().render(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        assertEquals(plain(expected), plain(renderer.renderRegion(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void growingStreamKeepsPrefixAndAddsSuffix() {
        final var a = plain(renderer.renderRegion("one\ntwo", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(List.of("one", "two"), a);
        // extend the stream: prefix "one\n" is stable, unknown tail appended
        final var b = plain(renderer.renderRegion("one\ntwo\nthree", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(List.of("one", "two", "three"), b);
    }

    @Test
    void growingPartialLineReRendersTail() {
        final var a = plain(renderer.renderRegion("hello", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        final var b = plain(renderer.renderRegion("hello world", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(List.of("hello"), a);
        assertEquals(List.of("hello world"), b);
    }

    @Test
    void widthChangeFallsBackToFullRender() {
        final var a = plain(renderer.renderRegion("alpha beta gamma", 10, Style.EMPTY, MarkdownStyles.DEFAULTS));
        final var wide = plain(renderer.renderRegion("alpha beta gamma", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(List.of("alpha beta gamma"), wide);
        assertNotEquals(a, wide);
    }

    @Test
    void fencedCodeStaysIntactAcrossFrames() {
        final var md = "before\n```\ncode\n```\nafter";
        final var a = plain(renderer.renderRegion(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        // growing: keep fence intact, append
        final var b = plain(renderer.renderRegion(md + " line", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(a.size(), b.size());
        assertEquals("before", b.get(0));
        assertEquals("after line", b.get(b.size() - 1));
    }

    @Test
    void incrementalStreamingMatchesFullRenderAtEveryStep() {
        final var full =
                new MarkdownLines().render("alpha\nbeta\ngamma\ndelta", 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        final var soFar = new StringBuilder();
        final var seen = new ArrayList<String>();
        for (final var line : List.of("alpha", "beta", "gamma", "delta")) {
            if (!soFar.isEmpty()) {
                soFar.append('\n');
            }
            soFar.append(line);
            seen.add(line);
            assertEquals(
                    seen,
                    plain(renderer.renderRegion(soFar.toString(), 80, Style.EMPTY, MarkdownStyles.DEFAULTS)),
                    () -> "frame after appending '" + line + "'");
        }
        // the incremental (prefix-cached) renderer must agree with a full render at the end
        assertEquals(plain(full), seen);
    }

    @Test
    void multiLineDeltaAppendsThroughTheDeltaPath() {
        final var a = plain(renderer.renderRegion("one\ntwo", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(List.of("one", "two"), a);
        // several lines flushed between two frames: all land through the delta path
        final var b = plain(renderer.renderRegion("one\ntwo\nthree\nfour", 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
        assertEquals(List.of("one", "two", "three", "four"), b);
    }

    @Test
    void streamedBlankLinesMatchAFullRenderAtEveryStep() {
        // a blank line only separates two blocks *inside* one markdown document: the streaming
        // renderer cuts the region and parses both halves independently, so the separator row must
        // be put back or paragraphs are glued together while the stream grows
        final var md = "para one\n\npara two\n\npara three";
        assertEquals(List.of("para one", "", "para two", "", "para three"), fullRender(md));
        assertEveryFrameMatchesAFullRender(md);
        assertEquals(List.of("para one", "", "para two", "", "para three"), plainOf(md));
        // the flushed text is rendered again from scratch (thinking flushed, answer re-rendered):
        // the cached prefix lines must not carry a lost separator for the rest of the session
        assertEquals(List.of("para one", "", "para two", "", "para three"), plainOf(md));
    }

    @Test
    void blankLineAroundAFenceMatchesAFullRenderWhileStreaming() {
        final var md = "para\n\n```\ncode\n```\n\nafter";
        assertEquals(List.of("para", "", "code", "", "after"), fullRender(md));
        assertEveryFrameMatchesAFullRender(md);
    }

    @Test
    void twoRowTableWithoutTrailingNewlineRendersAsATable() {
        // the /usage regression: the transcript strip() removes the last row's newline,
        // so the final row must not be split off from its header/separator and parsed
        // independently (which would render its pipes literally)
        final var md = "| window | used | reset |\n" + "|---|---|---|\n"
                + "| primary | 6% | 2026-11-04T08:02:45Z |\n"
                + "| secondary | 6% | 2026-11-05T08:02:45Z |";
        final var expected = new MarkdownLines().render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        final var actual = plainOf(md);
        assertEquals(plain(expected), actual);
        assertTrue(actual.getFirst().startsWith("┌"));
        assertTrue(actual.stream().anyMatch(line -> line.contains("│ secondary")));
        assertTrue(actual.stream().noneMatch(line -> line.startsWith("| secondary")));
    }

    @Test
    void twoRowTableWithTrailingNewlineRendersAsATable() {
        final var md = "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n";
        final var expected = new MarkdownLines().render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        assertEquals(plain(expected), plainOf(md));
    }

    @Test
    void streamedTableMatchesAFullRenderAtEveryFrame() {
        // the header/separator/rows arrive over successive frames: the safe boundary must
        // never fall between table lines, so every growing prefix renders exactly like a
        // full markdown render of the same text
        final var md = "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |";
        assertEveryFrameMatchesAFullRender(md);
    }

    @Test
    void completeTableThenBlankAndTextTransitionsTheCachedPrefix() {
        // once a table is fully committed (followed by a blank + paragraph), the next frame
        // grows through the cached-prefix delta path: the table keeps its border and the
        // trailing paragraph lands right behind it
        final var withTable = "lead\n\n| a | b |\n|---|---|\n| 1 | 2 |\n";
        final var a = plainOf(withTable);
        assertEquals(fullRender(withTable), a);
        // extend: blank line + a paragraph after the (now complete) table
        final var b = plainOf(withTable + "\nafter");
        assertEquals(fullRender(withTable + "\nafter"), b);
        assertTrue(b.stream().anyMatch(line -> line.startsWith("┌")));
        assertEquals("after", b.getLast());
    }

    @Test
    void lonePipeParagraphStillSplitsSafely() {
        // a single pipe line is a paragraph, not a table: splitting after it must keep working
        final var md = "one\n| not a table |\ntwo";
        assertEquals(fullRender(md), plainOf(md));
    }

    /** Renders every growing prefix of {@code md}, as a token-by-token stream would. */
    @Test
    void referenceDefinitionOnlyRendersAlignedEmpty() {
        // a non-blank turn body that renders to zero rows (here a markdown reference
        // definition) must come back with aligned empty lines AND kinds. This is the root
        // condition behind the transcript ArrayIndexOutOfBounds(-1): a chunk built from such
        // a body previously carried a non-null empty rowKinds array and the render loop hit
        // localKinds[-1]. The fix normalizes empty kinds to null at the chunk producer, so a
        // chunk can never expose an empty kinds array.
        final var rendered = renderer.renderRegionWithKinds(
                "[ref]: https://example.com\n", 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        assertTrue(rendered.lines().isEmpty(), () -> "expected no rows: " + rendered.lines());
        assertTrue(rendered.kinds().isEmpty(), () -> "kinds must stay aligned with rows: " + rendered.kinds());
        assertEquals(rendered.lines().size(), rendered.kinds().size());
    }

    private void assertEveryFrameMatchesAFullRender(final String md) {
        for (var cut = 1; cut <= md.length(); cut++) {
            final var frame = cut;
            final var partial = md.substring(0, cut);
            assertEquals(fullRender(partial), plainOf(partial), () -> "frame after " + frame + " characters");
        }
    }

    private List<String> plainOf(final String source) {
        return plain(renderer.renderRegion(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
    }

    private static List<String> fullRender(final String source) {
        return plain(new MarkdownLines().render(source, 80, Style.EMPTY, MarkdownStyles.DEFAULTS));
    }

    private static List<String> plain(final List<Line> lines) {
        return lines.stream().map(Line::rawContent).toList();
    }
}
