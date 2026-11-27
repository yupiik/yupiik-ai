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
package io.yupiik.ia.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.CompressConfiguration;
import io.yupiik.ia.agent.core.configuration.ContextLimit;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ContextCompressionTest {

    @Test
    void seamScrubRespectsFenceValidityAndMultilineCodeRuns() {
        final var compression = new ContextCompression(null);
        for (final var code : List.of(
                "``literal\n@1@\n@b2\nend``",
                "text ```literal\n@1@\nend```",
                "```\n    ```\n@1@\n```",
                "~~~\n\t~~~\n@1@\n~~~")) {
            assertEquals(code, compression.stripEchoedRefs(code));
            assertEquals(code, compression.withholdDanglingPrefix(code));
        }
        assertEquals("\t~~~\n", compression.stripEchoedRefs("\t~~~\n@1@\n"));
        assertEquals("\\`literal\n", compression.stripEchoedRefs("\\`literal\n@1@\n"));
        // Backticks in a backtick fence's info string invalidate the fence. The equal runs
        // here form a closed inline span instead, leaving the following seam outside code.
        assertEquals("```bad```\n", compression.stripEchoedRefs("```bad```\n@1@\n"));
        assertEquals("``a ` b``\n", compression.stripEchoedRefs("``a ` b``\n@1@\n"));
    }

    @Test
    void closedTurnIncludesToolRoundsAcrossInternalStateMessages() {
        final var compression = new ContextCompression(config());
        final var call = new ToolCall("c1", "function", new ToolCall.FunctionCall("read", "{}"), 0);
        final var messages = List.of(
                Message.system("system"),
                Message.user("inspect sample"),
                Message.assistant("checking", List.of()),
                new Message("user", "state", null, null, null, Map.of("volatileTail", true)),
                Message.assistant("", List.of(call)),
                Message.tool("c1", ""),
                new Message("user", "updated state", null, null, null, Map.of("volatileTail", true)),
                Message.user("next task"));
        compression.publish(messages);
        final var range = compression.compressibleClosedTurns().getFirst();
        assertEquals(Integer.toString(compression.refOf(messages.get(1))), range.startId());
        assertEquals(Integer.toString(compression.refOf(messages.get(5))), range.endId());
        final var rendered = compression.renderRefs(messages, true);
        assertTrue(
                rendered.get(1).content().toString().contains("compactIds=" + range.startId() + "-" + range.endId()));
        assertEquals(messages.get(3), rendered.get(3));
        final var result = compression.compress(
                "sample", List.of(new ContextCompression.Range(range.startId(), range.endId(), "inspection complete")));
        assertTrue(result.success(), result.message());
        assertFalse(result.message().contains("snapped"), result.message());
        assertEquals(
                Integer.parseInt(range.startId()),
                compression.blocks().getFirst().startRef());
        assertEquals(
                Integer.parseInt(range.endId()), compression.blocks().getFirst().endRef());
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

    private static CompressConfiguration protectedConfig() {
        return new CompressConfiguration(
                true,
                new ContextLimit("50000", "100000"),
                15,
                5,
                true,
                true,
                true,
                List.of("my_tool"),
                true,
                1,
                true,
                true,
                4);
    }

    private static List<Message> simpleConversation() {
        return List.of(
                Message.system("system"),
                Message.user("first task"),
                Message.assistant("done", List.of()),
                Message.user("next task"),
                Message.assistant("also done", List.of()));
    }

    @Test
    void projectionMatchesWireRenderingWithoutPublishingOrAbsorbing() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        assertTrue(compression
                .compress("closed", List.of(new ContextCompression.Range("1", "2", "done")))
                .success());
        final var tracker = new UsageTracker(null, null, null, Clock.systemUTC());
        tracker.contextProjection(
                (history, tools) -> tracker.payload("model", "tools", compression.project(history, false), tools));
        final var projected = compression.project(messages, false);
        assertEquals(compression.renderRefs(compression.rewrite(messages), false), projected);
        final var raw = tracker.estimateContext(messages, 0);
        final var effective = tracker.effectiveContext(messages, 0);
        assertTrue(effective > 0);
        tracker.anchor(100, tracker.contextPayload(messages, 0));
        assertEquals(100, tracker.effectiveContext(messages, 0), "compressed savings must not be deducted twice");
        compression.project(List.of(Message.system("new context")), false);
        assertEquals(
                ContextCompression.Status.ACTIVE,
                compression.blocks().getFirst().status(),
                "preview must not absorb missing blocks");
        assertTrue(raw > 0);
    }

    private static List<Message> toolConversation() {
        return List.of(
                Message.system("system"),
                Message.user("task one"),
                Message.assistant("calling", List.of()),
                Message.toolResult("call-1", "OK from my_tool", "my_tool"),
                Message.user("task two"),
                Message.assistant("done", List.of()));
    }

    private static List<Message> assignedRefs(final ContextCompression compression, final List<Message> messages) {
        compression.publish(messages);
        return compression.renderRefs(messages);
    }

    @Test
    void refsAreStableForAMessageIdentity() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var first = compression.refOf(messages.get(1));
        final var second = compression.refOf(messages.get(2));
        assertEquals(1, first);
        assertEquals(2, second);
        assertEquals(first, compression.refOf(messages.get(1)), "refs must never be reassigned");
        assertSame(messages.get(2), compression.messageOf(second));
    }

    @Test
    void renderRefsTagsOnlySpeakableMessages() {
        final var compression = new ContextCompression(config());
        final var tagged = assignedRefs(compression, simpleConversation());
        assertFalse(tagged.get(0).content().toString().contains("@"), "the system prompt is never tagged");
        assertTrue(
                tagged.get(1).content().toString().endsWith("\n@1@"),
                tagged.get(1).content().toString());
        assertTrue(
                tagged.get(2).content().toString().endsWith("\n@2@"),
                tagged.get(2).content().toString());
    }

    @Test
    void boundaryRenderingUsesPublishedTurnEndsAndKeepsLegacyRendering() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        compression.publish(messages);
        final var rendered = compression.renderRefs(messages, true);
        // only the first (closed) turn is advertised, as one explicit inclusive pair
        assertEquals("first task\ncompactIds=1-2", rendered.get(1).content());
        assertEquals("done", rendered.get(2).content());
        // the second user turn is the current open turn: it must not be advertised
        assertEquals("next task", rendered.get(3).content());
        assertEquals("also done", rendered.get(4).content());
        assertNotNull(compression
                .compress("first", List.of(new ContextCompression.Range("1", "2", "done")))
                .blockId());
        final var rewritten = compression.renderRefs(compression.rewrite(messages), true);
        assertTrue(rewritten.get(1).content().toString().contains("@b1@"));
        assertFalse(rewritten.get(2).content().toString().contains("@2@"));
        assertFalse(rewritten.get(2).content().toString().contains("compactIds="));
        assertTrue(compression.renderRefs(messages).get(2).content().toString().contains("@2@"));
    }

    @Test
    void boundaryRenderingRespectsProtectedTurnsAndSyntheticUsers() {
        final var compression = new ContextCompression(protectedConfig());
        final var messages = List.of(
                Message.system("system"),
                Message.user("first"),
                Message.assistant("done", List.of()),
                Message.user("<compacted-context>digest</compacted-context>"),
                Message.user("last"),
                Message.assistant("answer", List.of()),
                new Message("user", "tail", null, null, null, Map.of("volatileTail", true)));
        compression.publish(messages);
        final var rendered = compression.renderRefs(messages, true);
        // the first turn is closed and not protected (turnProtection=1 shields only the last)
        assertEquals("first\ncompactIds=1-2", rendered.get(1).content());
        assertEquals(messages.get(3), rendered.get(3)); // digest-only user never advertised
        assertEquals(messages.get(4), rendered.get(4)); // current open turn not advertised
        assertEquals(messages.get(6), rendered.get(6)); // volatile tail never advertised
    }

    @Test
    void compressibleClosedTurnsAdvertiseOnlyValidInclusivePairs() {
        final var compression = new ContextCompression(config());
        final var messages = toolConversation();
        compression.publish(messages);
        final var ranges = compression.compressibleClosedTurns();
        assertEquals(1, ranges.size(), "only the first closed turn is eligible");
        final var range = ranges.get(0);
        assertEquals("1", range.startId(), "start is the user message opening the turn");
        assertEquals("3", range.endId(), "end is the last tool result before the next user message");
        // the advertised pair compresses its exact turn without snapping
        final var result = compression.compress("t", List.of(range));
        assertTrue(result.success(), result.message());
        assertNotNull(result.blockId());
        // after compression the first turn is covered by an ACTIVE block, so it stops being advertised
        final var after = compression.compressibleClosedTurns();
        assertTrue(after.isEmpty(), "the compressed turn is no longer eligible");
        assertEquals(0, compression.compressibleClosedTurnCount());
    }

    @Test
    void boundaryRenderingMarksEachClosedTurnWithItsOwnPair() {
        final var compression = new ContextCompression(config());
        final var messages = toolConversation();
        compression.publish(messages);
        final var rendered = compression.renderRefs(messages, true);
        // only the first turn is closed; the second is the current open turn
        assertEquals("task one\ncompactIds=1-3", rendered.get(1).content());
        assertEquals("calling", rendered.get(2).content());
        assertEquals("OK from my_tool", rendered.get(3).content());
        assertEquals("task two", rendered.get(4).content());
        assertEquals("done", rendered.get(5).content());
        // no bare @N@ seams leak onto assistant/tool messages in boundary-only mode
        for (final var m : rendered) {
            if (m == messages.get(1)) {
                continue;
            }
            assertFalse(m.content() instanceof String c && c.contains("\n@"), String.valueOf(m.content()));
        }
    }

    @Test
    void compressibleClosedTurnCountReflectsMultipleClosedTurns() {
        final var compression = new ContextCompression(config());
        final var messages = List.of(
                Message.system("system"),
                Message.user("q1"),
                Message.assistant("a1", List.of()),
                Message.user("q2"),
                Message.assistant("a2", List.of()),
                Message.user("q3"),
                Message.assistant("a3", List.of()));
        compression.publish(messages);
        // turns for q1 and q2 are closed (each followed by a later user), q3 is the current open turn
        assertEquals(2, compression.compressibleClosedTurnCount());
        final var ranges = compression.compressibleClosedTurns();
        assertEquals(2, ranges.size());
        assertEquals("1", ranges.get(0).startId());
        assertEquals("2", ranges.get(0).endId());
        assertEquals("3", ranges.get(1).startId());
        assertEquals("4", ranges.get(1).endId());
        for (final var range : ranges) {
            assertTrue(compression.compress("t", List.of(range)).success());
        }
    }

    @Test
    void stripEchoedRefsRemovesCompactSeamsFromModelOutput() {
        final var compression = new ContextCompression(null);
        // a complete seam line ALONE on its line is stripped (with and without priority suffix)
        assertEquals("done\ncontinue", compression.stripEchoedRefs("done\n@90@\ncontinue"));
        assertEquals("", compression.stripEchoedRefs("@7@\n"));
        assertEquals("x\ny", compression.stripEchoedRefs("x\n@b2@\ny"));
        assertEquals("a\nb", compression.stripEchoedRefs("a\n@blocked@\nb"));
        assertEquals("a\nb", compression.stripEchoedRefs("a\n@3@ [high]\nb"));
        // a seam embedded in prose is INTENDED content and survives (the user policy)
        assertEquals("just a ref @7@", compression.stripEchoedRefs("just a ref @7@"));
        assertEquals("nested @b2@ sum", compression.stripEchoedRefs("nested @b2@ sum"));
        assertEquals("blocked @blocked@ turn", compression.stripEchoedRefs("blocked @blocked@ turn"));
        assertEquals("prioritized @3@ [high]", compression.stripEchoedRefs("prioritized @3@ [high]"));
        assertEquals("no refs here", compression.stripEchoedRefs("no refs here"));
        assertEquals("keeps at-word mention @user", compression.stripEchoedRefs("keeps at-word mention @user"));
        assertNull(compression.stripEchoedRefs(null));
        // the observed failure mode: a leaked '@' autocompleted with the wire content which ends
        // with the ref id. Only the '@' is removed - the word is real content and survives.
        final var withRef = new ContextCompression(null);
        for (var i = 0; i < 10631; i++) {
            withRef.refOf(Message.user("m" + i));
        }
        withRef.refOf(Message.user("last"));
        assertEquals("EntCompactionTruncates10632", withRef.stripEchoedRefs("@EntCompactionTruncates10632"));
        // the same word WITHOUT any assigned ref (fresh instance high-water 0) is untouched
        assertEquals(
                "@EntCompactionTruncates10632",
                new ContextCompression(null).stripEchoedRefs("@EntCompactionTruncates10632"));
    }

    @Test
    void gluedSeamStripIsEvidenceGatedOnAssignedRefsAndKeepsTheWord() {
        final var compression = new ContextCompression(null);
        // assign refs up to 12504 (monotonic, cheap: fresh Message identities, no wire render)
        for (var i = 0; i < 12503; i++) {
            compression.refOf(Message.user("m" + i));
        }
        compression.refOf(Message.user("last"));
        assertEquals(12504, compression.maxAssignedRef() - 1, "last assigned ref must be 12504");
        // the leaked glued form: '@' + word ending with the ref id -> only '@' removed
        assertEquals(
                "LocallyWhenTheSummarizer125FailsTransiently",
                compression.stripEchoedRefs("@LocallyWhenTheSummarizer125FailsTransiently"));
        // mid-word embedding with the ref id as a digit-run of the word: only the '@' is dropped,
        // the space and the word (real content) survive
        assertEquals(" man12504que", compression.stripEchoedRefs("@ man12504que"));
        // a NEVER-assigned ref id (fresh instance) keeps the '@'
        assertEquals(
                "@LocallyWhenTheSummarizer125FailsTransiently",
                new ContextCompression(null).stripEchoedRefs("@LocallyWhenTheSummarizer125FailsTransiently"));
        // a digit-run shorter than the 3-digit threshold is not a seam even when it equals a live ref
        compression.refOf(Message.user("m12"));
        assertEquals("@step12", compression.stripEchoedRefs("@step12"));
        // a 3-digit run equals a live ref (125 was assigned above) -> the '@' is stripped
        assertEquals("m125", compression.stripEchoedRefs("@m125"));
    }

    @Test
    void gluedSeamStripPreservesRealContentShapes() {
        final var compression = new ContextCompression(null);
        compression.refOf(Message.user("m1"));
        compression.refOf(Message.user("m2"));
        // pinned shapes that must survive every layer
        assertEquals("@user", compression.stripEchoedRefs("@user"));
        assertEquals("@RequestMapping", compression.stripEchoedRefs("@RequestMapping"));
        assertEquals("answer@1", compression.stripEchoedRefs("answer@1"));
        assertEquals("tail @1", compression.stripEchoedRefs("tail @1"));
        assertEquals("check @1290 out", compression.stripEchoedRefs("check @1290 out"));
        assertEquals("@", compression.stripEchoedRefs("@"));
        assertEquals("@1234567890123456789012345678", compression.stripEchoedRefs("@1234567890123456789012345678"));
        assertEquals("@blockedX", compression.stripEchoedRefs("@blockedX"));
        // an email-like address is not a seam
        assertEquals("mail@example.com", compression.stripEchoedRefs("mail@example.com"));
    }

    @Test
    void stripEchoedRefsPreservesSeamShapesInsideInlineCodeAndProse() {
        // the user-reported repro: the model emits `@1@` wrapped in backticks; the delimiters
        // must stay paired (previously the inner token was stripped and the backticks orphaned
        // into an empty inline-code span rendered as a literal pair)
        final var compression = new ContextCompression(null);
        assertEquals("use `@1@` like so", compression.stripEchoedRefs("use `@1@` like so"));
        assertEquals("`@12@` and `@b3@`", compression.stripEchoedRefs("`@12@` and `@b3@`"));
        // a seam in plain prose is intended content
        assertEquals("@1@ is the marker", compression.stripEchoedRefs("@1@ is the marker"));
        // a seam glued to prose (no line of its own) survives
        assertEquals("suffix @90@", compression.stripEchoedRefs("suffix @90@"));
    }

    @Test
    void stripEchoedRefsPreservesStandaloneSeamLinesInsideFencedCode() {
        // a seam alone on its own content line INSIDE a fenced block is literal code, not an echo:
        // the delimiters must never be orphaned and the code must stay byte-identical
        final var compression = new ContextCompression(null);
        final var fenced = "```\n@7@\n```\n";
        assertEquals(fenced, compression.stripEchoedRefs(fenced));
        final var fencedB = "```java\n@b2@\n```\n";
        assertEquals(fencedB, compression.stripEchoedRefs(fencedB));
        final var tilde = "~~~\n@blocked@\n~~~\n";
        assertEquals(tilde, compression.stripEchoedRefs(tilde));
        // a seam on a code line that also carries real code survives too
        assertEquals("```\nint x = @3@;\n```\n", compression.stripEchoedRefs("```\nint x = @3@;\n```\n"));
        // an OPEN fence (never closed) preserves everything after it to EOF
        assertEquals("```\n@4@", compression.stripEchoedRefs("```\n@4@"));
    }

    @Test
    void stripEchoedRefsStripsOnlyWholeStandaloneLinesAndPreservesLineEndings() {
        final var compression = new ContextCompression(null);
        // standalone line, both terminator styles
        assertEquals("a\nc", compression.stripEchoedRefs("a\n@2@\nc"));
        assertEquals("a\r\nc", compression.stripEchoedRefs("a\r\n@2@\r\nc"));
        // a line that is only padding around the seam is still standalone
        assertEquals("a\nc", compression.stripEchoedRefs("a\n  @2@  \nc"));
        // a priority suffix is part of the standalone shape
        assertEquals("a\nc", compression.stripEchoedRefs("a\n@2@ [high]\nc"));
        // standalone seam at EOF without trailing newline
        assertEquals("a\n", compression.stripEchoedRefs("a\n@2@"));
        // the whole text is a standalone seam -> empty
        assertEquals("", compression.stripEchoedRefs("@2@"));
        assertEquals("", compression.stripEchoedRefs("@2@\n"));
        // a seam on a line with other content is NOT standalone (survives)
        assertEquals("a @2@ c", compression.stripEchoedRefs("a @2@ c"));
        // tail occupied by a bare fragment (no closing @) is not a complete seam
        assertEquals("a\n@2", compression.stripEchoedRefs("a\n@2"));
    }

    @Test
    void stripEchoedRefsPathologyLongRunsAndAtHeavyInputAreBounded() {
        final var compression = new ContextCompression(null);
        // a 28-digit numeric token can never be a ref (not stripped)
        assertEquals("@1234567890123456789012345678", compression.stripEchoedRefs("@1234567890123456789012345678"));
        // a seam line with an over-long quoted suffix is not a recognized priority suffix (survives)
        assertEquals("a\n@1@ [urgent]\nb", compression.stripEchoedRefs("a\n@1@ [urgent]\nb"));
        // a huge run of '@' must process without pathological behavior and preserve the input
        final var heavy = "@".repeat(10_000) + "\n";
        assertEquals(heavy, compression.stripEchoedRefs(heavy));
        // many complete standalone seams in a row are all removed
        final var many = "@1@\n@2@\n@3@\n".repeat(500);
        assertEquals("", compression.stripEchoedRefs(many));
    }

    @Test
    void stripEchoedRefsRejectsLeadingZeroAndBareSuffixShapes() {
        final var compression = new ContextCompression(null);
        // complete seams must use the [1-9]\d* ref shape (historic ECHOED_REF_LINE)
        assertEquals("@01@", compression.stripEchoedRefs("@01@"));
        assertEquals("@b01@", compression.stripEchoedRefs("@b01@"));
        // a bare fragment (no closing @) is not a complete seam
        assertEquals("@1", compression.stripEchoedRefs("@1"));
        // @blocked@ is the literal sentinel (letter token, not b+digits)
        assertEquals("x\n", compression.stripEchoedRefs("x\n@blocked@\n"));
    }

    @Test
    void withholdDanglingPrefixIsFenceAwareAndPreservesCodeBlocks() {
        final var compression = new ContextCompression(config());
        // a bare-anchor-shaped line INSIDE a fenced block is literal code and survives
        assertEquals("```\n@1290\n```\n", compression.withholdDanglingPrefix("```\n@1290\n```\n"));
        assertEquals("~~~\n@b7\n~~~\n", compression.withholdDanglingPrefix("~~~\n@b7\n~~~\n"));
        // a bare anchor OUTSIDE a fence is still dropped
        assertEquals("real code\n", compression.withholdDanglingPrefix("real code\n@1290\n"));
        // an OPEN fence (never closed) preserves everything after it
        assertEquals("```\n@1290", compression.withholdDanglingPrefix("```\n@1290"));
    }

    @Test
    void withholdDanglingPrefixDropsBareAnchorLinesAnywhere() {
        // the reported leak: two standalone anchors frozen mid-block once the tool block follows
        final var compression = new ContextCompression(config());
        assertEquals(
                "Good picture forming.\n\n\n\ntool block",
                compression.withholdDanglingPrefix("Good picture forming.\n\n@1290\n\n@1291\n\ntool block"));
        // a trailing bare anchor line is dropped too
        assertEquals("done", compression.withholdDanglingPrefix("done\n@24"));
        // leading anchor, block refs and the blocked sentinel are dropped as well
        assertEquals("real prose", compression.withholdDanglingPrefix("@7\nreal prose"));
        assertEquals("a\nb", compression.withholdDanglingPrefix("a\n@b7\nb"));
        assertEquals("a\nb", compression.withholdDanglingPrefix("a\n@blocked\nb"));
        // CRLF: the anchor line is dropped but the CR of a surviving CRLF line is kept as-is
        assertEquals("done\r\nrest", compression.withholdDanglingPrefix("done\r\n@1290\r\nrest"));
        assertEquals("done", compression.withholdDanglingPrefix("done\n  @1290  "));
        assertNull(compression.withholdDanglingPrefix(null));
        assertEquals("", compression.withholdDanglingPrefix(""));
    }

    @Test
    void withholdDanglingPrefixKeepsRealContentShapes() {
        final var compression = new ContextCompression(config());
        // at-mentions / annotations lines are letters-only, not anchor tokens: untouched
        assertEquals("@user", compression.withholdDanglingPrefix("@user"));
        assertEquals("@RequestMapping", compression.withholdDanglingPrefix("@RequestMapping"));
        // inline fragments glued to prose are content, not line-leading anchors
        assertEquals("answer@1", compression.withholdDanglingPrefix("answer@1"));
        assertEquals("answer @1", compression.withholdDanglingPrefix("answer @1"));
        assertEquals("tail @1", compression.withholdDanglingPrefix("tail @1"));
        assertEquals("reasoning about step @1", compression.withholdDanglingPrefix("reasoning about step @1"));
        assertEquals("check @1290 out", compression.withholdDanglingPrefix("check @1290 out"));
        // lone '@', over-long tokens and non-token suffixes are not anchors
        assertEquals("@", compression.withholdDanglingPrefix("@"));
        assertEquals(
                "@1234567890123456789012345678", compression.withholdDanglingPrefix("@1234567890123456789012345678"));
        assertEquals("@blockedX", compression.withholdDanglingPrefix("@blockedX"));
        // '@1' is a bare digit anchor line -> dropped; the lone trailing '@' is content
        assertEquals("done\n@", compression.withholdDanglingPrefix("done\n@1\n@"));
        // complete seams are still stripped first (whole-line and inline), and a bare '@9' line
        // (shape match) is dropped even without any live ref
        assertEquals("x\ny", compression.withholdDanglingPrefix("x\n@5@\ny"));
        assertEquals("done\n@", compression.withholdDanglingPrefix("done\n@9\n@"));
    }

    @Test
    void withholdDanglingPrefixIsSafeWhileTheLiveListIsMutated() throws InterruptedException {
        final var compression = new ContextCompression(config());
        final var live = new ArrayList<Message>();
        live.add(Message.system("system"));
        for (var i = 0; i < 64; i++) {
            live.add(Message.user("turn " + i));
        }
        compression.publish(live);
        compression.renderRefs(List.copyOf(live));
        final var stop = new AtomicBoolean();
        final var writer = new Thread(() -> {
            while (!stop.get()) {
                live.add(Message.assistant("streamed", List.of()));
                live.remove(live.size() - 1);
            }
        });
        writer.start();
        try {
            for (var i = 0; i < 10_000; i++) {
                compression.withholdDanglingPrefix("tail @1");
            }
        } finally {
            stop.set(true);
            writer.join(1_000);
        }
    }

    @Test
    void publishPrunesMessagesThatLeftTheWire() {
        final var compression = new ContextCompression(null);
        final var first = new Message("user", "one", null, null, null, Map.of());
        final var second = new Message("user", "two", null, null, null, Map.of());
        compression.publish(List.of(first, second));
        final var ref1 = compression.refOf(first);
        final var ref2 = compression.refOf(second);
        // second stays on the wire, first is pruned
        compression.publish(List.of(second));
        assertSame(second, compression.messageOf(ref2));
        assertNull(compression.messageOf(ref1));
    }

    @Test
    void compressReplacesTheSpanInRequestsOnly() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var result =
                compression.compress("first task", List.of(new ContextCompression.Range("1", "2", "first task done")));
        assertTrue(result.success(), result.message());
        assertEquals("b1", result.blockId());
        assertEquals(1, compression.blocks().size());
        assertEquals(
                ContextCompression.Status.ACTIVE, compression.blocks().get(0).status());
        final var rewritten = compression.rewrite(messages);
        assertEquals(4, rewritten.size(), "two messages collapse into one block");
        assertTrue(
                rewritten.get(1).content().toString().startsWith("[Compressed conversation section @b1@]"),
                rewritten.get(1).content().toString());
        assertEquals(5, messages.size(), "the transcript itself must never be mutated");
        assertTrue(compression.savedTokens(messages) > 0);
        assertEquals(
                Math.max(0, 1_000 - compression.savedTokens(messages)), compression.effectiveTokens(messages, 1_000));
    }

    @Test
    void targetsExtendsTheHardLimitBySummaryTokensWhenBuffered() {
        final var compression = new ContextCompression(config()); // summaryBuffer=true
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var result =
                compression.compress("first task", List.of(new ContextCompression.Range("1", "2", "first task done")));
        assertTrue(result.success(), result.message());
        assertTrue(compression.summaryTokens() > 0, "a summary must carry tokens");
        final var targets = compression.targets(200_000, new ContextLimit("50000", "100000"));
        assertEquals(50_000, targets.baseMin());
        assertEquals(100_000, targets.baseMax());
        assertEquals(100_000 + compression.summaryTokens(), targets.effectiveMax());
        assertTrue(targets.summaryBuffer());
    }

    @Test
    void targetsKeepsHardLimitWhenSummaryBufferIsOff() {
        final var noBuffer = new CompressConfiguration(
                true,
                new ContextLimit("50000", "100000"),
                15,
                5,
                false,
                false,
                false,
                List.of("my_tool"),
                false,
                4,
                true,
                true,
                4);
        final var compression = new ContextCompression(noBuffer);
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var result =
                compression.compress("first task", List.of(new ContextCompression.Range("1", "2", "first task done")));
        assertTrue(result.success(), result.message());
        final var targets = compression.targets(200_000, new ContextLimit("50000", "100000"));
        assertEquals(100_000, targets.effectiveMax());
        assertFalse(targets.summaryBuffer());
    }

    @Test
    void rangeWithUnreachableUserStartIsSkippedWithNudge() {
        // a range that cannot reach a user start (here an assistant ref with no later user before end)
        // is dropped and the model is nudged, never hard-rejected
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var result = compression.compress("bad", List.of(new ContextCompression.Range("2", "2", "nope")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("skipped"), result.message());
        assertTrue(compression.blocks().isEmpty(), "the folded range must not create a block");
    }

    @Test
    void startSnapsForwardToNextUser() {
        final var compression = new ContextCompression(config());
        final var messages = toolConversation(); // refs: 1=user t1, 2=assistant, 3=tool, 4=user t2, 5=assistant
        assignedRefs(compression, messages);
        final var result = compression.compress("t", List.of(new ContextCompression.Range("2", "5", "t2 chap")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("adjusted"), result.message());
        assertTrue(result.message().contains("start ref"), result.message());
        assertEquals(1, compression.blocks().size());
        assertEquals(4, compression.blocks().get(0).startRef());
        assertEquals(5, compression.blocks().get(0).endRef());
    }

    @Test
    void endSnapsBackwardToTurnBoundary() {
        final var compression = new ContextCompression(config());
        final var messages = toolConversation();
        assignedRefs(compression, messages);
        // end ref 4 (user t2) is not a turn boundary (an assistant reply follows) - snap it back to the
        // preceding tool result so the tool is never separated from its caller
        final var result = compression.compress("t", List.of(new ContextCompression.Range("1", "4", "t1 chap")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("adjusted"), result.message());
        assertTrue(result.message().contains("turn boundary"), result.message());
        assertEquals(1, compression.blocks().size());
        assertEquals(1, compression.blocks().get(0).startRef());
        assertEquals(3, compression.blocks().get(0).endRef());
    }

    @Test
    void collapsingRangeIsSkippedWithNudge() {
        final var compression = new ContextCompression(config());
        final var messages = toolConversation();
        assignedRefs(compression, messages);
        // start 2 (assistant) snaps forward to ref 4 (user) and end 4 snaps backward to the same ref:
        // start >= end after snapping, so the range collapses and is skipped
        final var result = compression.compress("t", List.of(new ContextCompression.Range("2", "4", "nope")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("skipped"), result.message());
        assertTrue(compression.blocks().isEmpty(), "the collapsing range must not create a block");
    }

    @Test
    void rangeWithNoReachableTurnBoundaryIsSkippedWithNudge() {
        final var compression = new ContextCompression(config());
        final var messages = List.of(
                Message.system("system"),
                Message.user("task"),
                Message.assistant("calling", List.of()),
                Message.toolResult("call-1", "OK", "my_tool"));
        assignedRefs(compression, messages);
        // end ref 2 (assistant, followed by a tool result) cannot be snapped back to any turn boundary
        // within the range, so the whole entry is dropped with a nudge
        final var result = compression.compress("bad", List.of(new ContextCompression.Range("1", "2", "nope")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("skipped"), result.message());
        assertTrue(compression.blocks().isEmpty());
    }

    @Test
    void containedOverlappingRangeIsDroppedNotFatal() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        // [1..4] fully covers [3..4], so the contained (later) range is dropped as redundant and the
        // covering one is applied - the batch must succeed instead of hard-failing on overlap
        final var result = compression.compress(
                "bad",
                List.of(
                        new ContextCompression.Range("1", "4", "whole"),
                        new ContextCompression.Range("3", "4", "tail")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("fully covered by"), result.message());
        assertEquals(1, compression.blocks().size(), "only the covering range must create a block");
        assertEquals(1, compression.blocks().get(0).startRef());
        assertEquals(4, compression.blocks().get(0).endRef());
        assertTrue(compression.blocks().get(0).summary().contains("whole"));
    }

    @Test
    void laterCoveringRangeAbsorbsEarlierOne() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        // the later [1..4] fully covers the earlier [3..4]: the earlier span is absorbed (rewritten
        // once by the covering summary) instead of both being applied twice
        final var result = compression.compress(
                "bad",
                List.of(
                        new ContextCompression.Range("3", "4", "tail"),
                        new ContextCompression.Range("1", "4", "whole")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("absorbs the previously applied"), result.message());
        assertEquals(1, compression.blocks().size(), "the absorbed range must not create its own block");
        assertEquals(1, compression.blocks().get(0).startRef(), "the covering range starts at ref 1");
        assertEquals(4, compression.blocks().get(0).endRef());
        assertTrue(compression.blocks().get(0).summary().contains("whole"));
    }

    @Test
    void adjacentRangesBothCompressWithoutOverlapDetection() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        // two genuinely disjoint turns must both compress - the pairwise overlap check must not treat
        // adjacency (end ref 2, start ref 3) as an overlap
        final var result = compression.compress(
                "turns",
                List.of(
                        new ContextCompression.Range("1", "2", "first turn"),
                        new ContextCompression.Range("3", "4", "second turn")));
        assertTrue(result.success(), result.message());
        assertEquals(2, compression.blocks().size(), "both disjoint ranges must become blocks");
        assertEquals(1, compression.blocks().get(0).startRef());
        assertEquals(3, compression.blocks().get(1).startRef());
    }

    @Test
    void overlapSurfacesCompressibleUserTurnRefs() {
        final var compression = new ContextCompression(config());
        final var messages = List.of(
                Message.system("system"),
                Message.user("t1"),
                Message.assistant("a1", List.of()),
                Message.user("t2"),
                Message.assistant("a2", List.of()),
                Message.user("t3"),
                Message.assistant("a3", List.of()));
        assignedRefs(compression, messages);
        // [1..4] straddles [3..6] on refs 3-4 (a range spans multiple user turns), so the earlier
        // range is kept and the later overlapping one dropped; user turn t3 (ref 5) stays outside the
        // block, so the overlap guidance lists the real surviving compressible start
        final var result = compression.compress(
                "bad",
                List.of(
                        new ContextCompression.Range("1", "4", "first two turns"),
                        new ContextCompression.Range("3", "6", "last two turns")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("straddles"), result.message());
        assertTrue(result.message().contains("kept the earlier range"), result.message());
        assertTrue(result.message().contains("No structurally eligible historical ranges"), result.message());
        assertEquals(1, compression.blocks().size(), "only the kept earlier range must create a block");
        assertEquals(1, compression.blocks().get(0).startRef());
        assertEquals(4, compression.blocks().get(0).endRef());
    }

    @Test
    void broadCoveringRangeAbsorbsAllEarlierOverlappingOnes() {
        final var compression = new ContextCompression(config());
        final var messages = List.of(
                Message.system("system"),
                Message.user("t1"),
                Message.assistant("a1", List.of()),
                Message.user("t2"),
                Message.assistant("a2", List.of()),
                Message.user("t3"),
                Message.assistant("a3", List.of()));
        assignedRefs(compression, messages);
        // [1..2] and [5..6] are already applied, then a broad [1..6] covers both: it must absorb
        // every narrower covered span so `resolved` stays pairwise disjoint - never two overlapping
        // blocks like [[5..6],[1..6]]
        final var result = compression.compress(
                "whole",
                List.of(
                        new ContextCompression.Range("1", "2", "first"),
                        new ContextCompression.Range("5", "6", "last"),
                        new ContextCompression.Range("1", "6", "whole")));
        assertTrue(result.success(), result.message());
        assertEquals(1, compression.blocks().size(), "the broad range must collapse all covered spans into one block");
        assertEquals(1, compression.blocks().get(0).startRef());
        assertEquals(6, compression.blocks().get(0).endRef());
        assertTrue(compression.blocks().get(0).summary().contains("whole"));
        assertTrue(result.message().contains("absorbs the previously applied"), result.message());
    }

    @Test
    void overlappingActiveBlockIsSkippedWithNudge() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        assertTrue(compression
                .compress("first", List.of(new ContextCompression.Range("1", "2", "first done")))
                .success());
        final var withoutRef =
                compression.compress("greedy", List.of(new ContextCompression.Range("1", "4", "no placeholder")));
        assertTrue(withoutRef.success(), withoutRef.message());
        assertTrue(withoutRef.message().contains("overlaps active block"), withoutRef.message());
        assertEquals(1, compression.blocks().size(), "the skipped overlapped range must not create a block");
    }

    @Test
    void nestingACoveredBlockViaPlaceholderSupersedesIt() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        assertTrue(compression
                .compress("first", List.of(new ContextCompression.Range("1", "2", "first task completed")))
                .success());
        final var nested = compression.compress(
                "whole", List.of(new ContextCompression.Range("1", "4", "@b1@ plus the second user turn")));
        assertTrue(nested.success(), nested.message());
        assertEquals("b2", nested.blockId());
        final var b1 = compression.blocks().stream()
                .filter(block -> block.id().equals("b1"))
                .findFirst()
                .orElseThrow();
        assertEquals(ContextCompression.Status.SUPERSEDED, b1.status());
        assertEquals("b2", b1.supersededBy());
        final var b2 = compression.blocks().stream()
                .filter(block -> block.id().equals("b2"))
                .findFirst()
                .orElseThrow();
        assertTrue(b2.summary().startsWith("first task completed"), b2.summary());
        assertTrue(b2.consumedBlockIds().contains("b1"), b2.consumedBlockIds().toString());
        assertEquals(2, compression.rewrite(messages).size(), "only the outer block remains applied");
    }

    @Test
    void decompressOnlyRestoresActiveBlocks() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        assertTrue(compression
                .compress("first", List.of(new ContextCompression.Range("1", "2", "first done")))
                .success());
        final var restored = compression.decompress("b1");
        assertTrue(restored.success(), restored.message());
        assertEquals(
                ContextCompression.Status.DECOMPRESSED,
                compression.blocks().get(0).status());
        assertEquals(5, compression.rewrite(messages).size(), "nothing is rewritten once decompressed");

        final var again = compression.decompress("@b1@");
        assertFalse(again.success());
        assertTrue(again.message().contains("cannot be decompressed"), again.message());

        final var unknown = compression.decompress("b99");
        assertFalse(unknown.success());
        assertTrue(unknown.message().contains("Unknown"), unknown.message());
    }

    @Test
    void absorbAllMarksEveryActiveBlockAbsorbed() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        compression.compress("first", List.of(new ContextCompression.Range("1", "2", "first done")));
        compression.compress("next", List.of(new ContextCompression.Range("3", "4", "second done")));
        compression.absorbAll();
        assertTrue(
                compression.blocks().stream().allMatch(block -> block.status() == ContextCompression.Status.ABSORBED));
        assertEquals(0, compression.savedTokens(messages));
    }

    @Test
    void protectionsAppendUserMessagesTagsAndToolOutputs() {
        final var compression = new ContextCompression(protectedConfig());
        final var messages = toolConversation();
        assignedRefs(compression, messages);
        final var result = compression.compress(
                "first", List.of(new ContextCompression.Range("1", "3", "work done: fixed the rounding")));
        assertTrue(result.success(), result.message());
        final var summary = compression.blocks().get(0).summary();
        assertTrue(summary.contains("task one"), summary);
        assertTrue(summary.contains("[protected tool output: my_tool]"), summary);
        assertTrue(summary.contains("OK from my_tool"), summary);
    }

    @Test
    void protectsRuntimeToolResultWithNameResolvedFromAssistantCall() {
        final var compression = new ContextCompression(config());
        final var messages = List.of(
                Message.system("system"),
                Message.user("task one"),
                Message.assistant(
                        "calling",
                        List.of(new ToolCall("call-1", "function", new ToolCall.FunctionCall("my_tool", "{}"), 0))),
                Message.tool("call-1", "OK from my_tool"),
                Message.user("task two"),
                Message.assistant("done", List.of()));
        assignedRefs(compression, messages);

        final var result = compression.compress(
                "first", List.of(new ContextCompression.Range("1", "3", "work done: fixed the rounding")));

        assertTrue(result.success(), result.message());
        final var summary = compression.blocks().getFirst().summary();
        assertTrue(summary.contains("[protected tool output: my_tool]"), summary);
        assertTrue(summary.contains("OK from my_tool"), summary);
    }

    @Test
    void turnProtectionSkipsTouchingRangeButAppliesOthers() {
        final var compression = new ContextCompression(protectedConfig()); // turnProtection=true, turns=1
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var oldTurn = compression.compress("old", List.of(new ContextCompression.Range("1", "2", "old turn")));
        assertTrue(oldTurn.success(), oldTurn.message());
        // range 3..4 touches the last protected user turn -> per-item skip, not a batch hard-fail
        final var active = compression.compress("active", List.of(new ContextCompression.Range("3", "4", "no")));
        assertTrue(active.success(), active.message());
        assertTrue(active.message().contains("protected"), active.message());
        assertTrue(active.message().contains("skipped"), active.message());
        assertEquals(1, compression.blocks().size(), "the protected range must not create a block");
    }

    @Test
    void mixedBatchAppliesGoodRangesAndSkipsBadOnes() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation(); // refs: 1 user, 2 assistant, 3 user, 4 assistant
        assignedRefs(compression, messages);
        // range 1..2 is valid (assistant ref 2 ends at a turn boundary before user ref 3); the second
        // range 2..2 (assistant, no later user before its end) cannot snap -> skipped, and the good
        // range must still be applied so a single bad range never rejects the batch
        final var result = compression.compress(
                "t",
                List.of(
                        new ContextCompression.Range("1", "2", "t1 done"),
                        new ContextCompression.Range("2", "2", "bad")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("skipped"), result.message());
        assertEquals("b1", result.blockId());
        assertEquals(1, compression.blocks().size(), "only the good range is compressed");
    }

    @Test
    void volatileTailRangeIsSkippedNotRejectingBatch() {
        final var compression = new ContextCompression(config());
        // the live session-state tail is a user message flagged volatileTail and is never ref'd by
        // renderRefs, so a range citing it resolves to "not in the live conversation" and must be
        // skipped without rejecting the rest of the batch
        final var messages = List.of(
                Message.system("system"),
                Message.user("task one"),
                Message.assistant("done", List.of()),
                new Message("user", "live session-state tail", null, null, null, Map.of("volatileTail", true)));
        assignedRefs(compression, messages);
        final var result = compression.compress(
                "t",
                List.of(
                        new ContextCompression.Range("1", "2", "task one done"),
                        new ContextCompression.Range("3", "3", "tail")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("skipped"), result.message());
        assertEquals(1, compression.blocks().size(), "only the non-volatile range is compressed");
    }

    @Test
    void malformedContentStillHardFails() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        // top-level absent/empty range list stays a hard failure (nothing to take)
        assertFalse(compression.compress("t", List.of()).success());
        assertFalse(compression.compress("t", null).success());
        // a null element is a per-item skip (success) matching the non-null-skip behavior
        assertTrue(compression
                .compress("t", Collections.singletonList((ContextCompression.Range) null))
                .success());
        assertTrue(compression.blocks().isEmpty());
    }

    @Test
    void digestOnlyTranscriptContinuesWithoutWholeCompactEscalation() {
        // a transcript whose only user content is a previous <compacted-context> digest (the
        // digest-only dead-end: compaction folded the history, then a long tool-churn round) cannot
        // anchor a compress range. A skipped range must surface the digest diagnostic, not the
        // generic "send several small disjoint ranges" guidance.
        final var compression = new ContextCompression(config());
        final var messages = List.of(
                Message.system("system"),
                Message.user("<compacted-context>old digest</compacted-context>"),
                Message.assistant("tool call", List.of()),
                Message.tool("call_1", "x".repeat(2_000)),
                Message.assistant("more churn", List.of()));
        assignedRefs(compression, messages);
        final var result = compression.compress("t", List.of(new ContextCompression.Range("3", "3", "cannot snap")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("skipped"), result.message());
        assertTrue(result.message().contains("No structurally eligible historical ranges"), result.message());
        assertTrue(result.message().contains("Continue the current task"), result.message());
        assertFalse(result.message().contains("/compact"), result.message());
        assertFalse(
                result.message().contains("send several small disjoint ranges"),
                "the generic disjoint-ranges guidance must be absent for a digest-only transcript, got: "
                        + result.message());
    }

    @Test
    void normalTranscriptRecoversWithExactClosedPairs() {
        // control: a transcript with real user messages keeps the generic guidance on a skip.
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var result = compression.compress("t", List.of(new ContextCompression.Range("2", "2", "cannot snap")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("skipped"), result.message());
        assertTrue(result.message().contains("compressible inclusive ranges: 1-2"), result.message());
    }

    @Test
    void disabledCompressionRefusesEverything() {
        final var disabled = new CompressConfiguration(
                false,
                new ContextLimit("50000", "100000"),
                15,
                5,
                true,
                false,
                false,
                List.of(),
                false,
                4,
                true,
                true,
                4);
        final var compression = new ContextCompression(disabled);
        assertFalse(compression.enabled());
        final var result = compression.compress("t", List.of(new ContextCompression.Range("1", "2", "s")));
        assertFalse(result.success());
        assertTrue(result.message().contains("disabled"), result.message());
    }

    @Test
    void disabledCompressionReturnsInputUnchanged() {
        final var disabled = new ContextCompression(new CompressConfiguration(
                false,
                new ContextLimit("50000", "100000"),
                15,
                5,
                true,
                false,
                false,
                List.of(),
                false,
                4,
                true,
                true,
                4));
        final var messages = simpleConversation();
        assertSame(messages, disabled.renderRefs(messages));
        assertSame(messages, disabled.rewrite(messages));
        assertEquals(0, disabled.savedTokens(messages));
    }

    @Test
    void stripEchoedRefsFastPathReturnsSameInstanceWithoutAtSign() {
        // the char gate: text without '@' can never carry a seam or a glued marker, so the input is
        // returned verbatim (same instance, zero Matcher allocations) - the streaming render hot path
        final var compression = new ContextCompression(null);
        final var clean = "just some streaming text, no markers here 12345";
        assertSame(clean, compression.stripEchoedRefs(clean));
        assertSame("", compression.stripEchoedRefs(""));
        // an '@' anywhere engages the scan (the fast path is off); the output is unchanged here
        // because "a@b" is an email-like identifier, but the scan ran over it
        assertEquals("a@b", compression.stripEchoedRefs("a@b"));
    }

    @Test
    void stripEchoedRefsCharLoopMatchesRegexParity() {
        // the char-level GLUED_SEAM_WORD replacement must reproduce the regex semantics exactly,
        // including the ASCII-only lookbehind and the evidence gate
        final var compression = new ContextCompression(null);
        for (var i = 0; i < 10631; i++) {
            compression.refOf(Message.user("m" + i));
        }
        compression.refOf(Message.user("last"));
        // suffix run, blanks preserved, only the '@' dropped
        assertEquals("EntCompactionTruncates10632", compression.stripEchoedRefs("@EntCompactionTruncates10632"));
        assertEquals(" man10632que", compression.stripEchoedRefs("@ man10632que"));
        // lookbehind is ASCII-only ([A-Za-z0-9]): a non-ASCII letter before '@' does NOT exempt it
        compression.refOf(Message.user("w125"));
        assertEquals("éword125", compression.stripEchoedRefs("é@word125"));
        // gate fails (digit-run too short, or not a letter-initial word) -> untouched
        assertEquals("@step12", compression.stripEchoedRefs("@step12"));
        assertEquals("@1290", compression.stripEchoedRefs("@1290")); // digit-initial: no letter word
        // consecutive candidates: both embed assigned refs (the 1..10633 range covers 125 and 10632),
        // so both are stripped and the scan resumes correctly across two strips
        assertEquals("no125 yes10632", compression.stripEchoedRefs("@no125 @yes10632"));
        // emails/identifiers untouched
        assertEquals("mail@example.com", compression.stripEchoedRefs("mail@example.com"));
        assertEquals("answer@1", compression.stripEchoedRefs("answer@1"));
    }

    @Test
    void stripEchoedRefsCharLoopMultipleStripsAcrossText() {
        final var compression = new ContextCompression(null);
        for (var i = 0; i < 12503; i++) {
            compression.refOf(Message.user("m" + i));
        }
        compression.refOf(Message.user("last"));
        // two separated glued leaks in one text: both stripped, surrounding prose preserved
        assertEquals("a Ent12504 b Locally125 c", compression.stripEchoedRefs("a @Ent12504 b @Locally125 c"));
        // stripping resumes correctly after a skipped (non-gated) candidate
        assertEquals("x @step12 y word12504 z", compression.stripEchoedRefs("x @step12 y @word12504 z"));
    }

    @Test
    void withholdDanglingPrefixFastPathWithoutAtSign() {
        final var compression = new ContextCompression(config());
        // withholdDanglingPrefix composes stripEchoedRefs (identity fast path) with the manual
        // stripBareAnchorLines pass, which always rebuilds the string; the outcome must be the
        // input unchanged for clean text without any '@'
        final var clean = "normal markdown line\nsecond line 42";
        assertEquals(clean, compression.withholdDanglingPrefix(clean));
        // a trailing newline yields a final empty line that the manual re-join keeps, so the
        // trailing '\n' is preserved (existing stripBareAnchorLines behavior, unchanged)
        assertEquals(clean + "\n", compression.withholdDanglingPrefix(clean + "\n"));
    }

    @Test
    void compressibleClosedTurnsListsOnlyRealUserTurnsOldestFirst() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation(); // refs: 1 user, 2 assistant, 3 user, 4 assistant
        assignedRefs(compression, messages);
        assertEquals(List.of(new ContextCompression.Range("1", "2", null)), compression.compressibleClosedTurns());
        assertEquals(1, compression.compressibleClosedTurnCount());
    }

    @Test
    void compressibleClosedTurnsExcludesVolatileTailAndDigestAndActiveBlocks() {
        final var compression = new ContextCompression(config());
        final var messages = List.of(
                Message.system("system"),
                Message.user("first task"),
                Message.assistant("done", List.of()),
                Message.user("<compacted-context>prior digest</compacted-context>"),
                Message.user("third task"),
                Message.assistant("also done", List.of()),
                new Message("user", "live session-state tail", null, null, null, Map.of("volatileTail", true)));
        assignedRefs(compression, messages);
        // eligible: ref 1 (first task) and ref 4 (third task); ref 3 is the digest, the tail is never ref'd
        assertEquals(List.of(new ContextCompression.Range("1", "2", null)), compression.compressibleClosedTurns());
        // compress 1..2 -> block b1 covers ref 1; now only ref 4 stays eligible
        compression.compress("first", List.of(new ContextCompression.Range("1", "2", "first task done")));
        assertEquals(List.of(), compression.compressibleClosedTurns());
        assertEquals(0, compression.compressibleClosedTurnCount());
    }

    @Test
    void compressibleClosedTurnsRespectsTurnProtectionSuffix() {
        final var compression = new ContextCompression(protectedConfig()); // turnProtection=1
        final var messages = simpleConversation(); // refs: 1 user, 2 assistant, 3 user(last), 4 assistant
        assignedRefs(compression, messages);
        // the last user turn (ref 3) is inside the protected suffix, ref 1 (older) stays eligible
        assertEquals(List.of(new ContextCompression.Range("1", "2", null)), compression.compressibleClosedTurns());
        assertEquals(1, compression.compressibleClosedTurnCount());
    }

    @Test
    void compressibleClosedTurnsCapsAndReportsOmittedCount() {
        final var compression = new ContextCompression(config());
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        for (var i = 0; i < 30; i++) { // 30 user turns -> refs 1..60 (assistant steps between)
            messages.add(Message.user("task " + i));
            messages.add(Message.assistant("done " + i, List.of()));
        }
        assignedRefs(compression, messages);
        final var refs = compression.compressibleClosedTurns();
        assertEquals(ContextCompression.MAX_GUIDANCE_REFS, refs.size(), "the guidance list must be capped");
        assertEquals(29, compression.compressibleClosedTurnCount(), "the current turn must be excluded");
        assertEquals("1", refs.getFirst().startId(), "oldest-first ordering");
        assertEquals(
                Integer.toString(1 + 2 * (ContextCompression.MAX_GUIDANCE_REFS - 1)),
                refs.getLast().startId());
    }

    @Test
    void missingEndpointsReportNoChangeAndSafeRecovery() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var result = compression.compress(
                "missing",
                List.of(
                        new ContextCompression.Range("999", "2", "missing start"),
                        new ContextCompression.Range("1", "998", "missing end"),
                        new ContextCompression.Range("997", "996", "both missing")));

        assertTrue(result.success(), result.message());
        assertNull(result.blockId());
        assertTrue(compression.blocks().isEmpty());
        assertEquals(messages, compression.rewrite(messages));
        assertTrue(result.message().startsWith("outcome: no_change\ncreated: 0\nskipped: 3\n"), result.message());
        assertTrue(result.message().contains("startId 999 is not in the live conversation"), result.message());
        assertTrue(result.message().contains("endId 998 is not in the live conversation"), result.message());
        assertTrue(
                result.message().contains("startId 997 and endId 996 are not in the live conversation"),
                result.message());
        assertFalse(result.message().contains("precedes"), result.message());
        assertTrue(result.message().contains("Do not retry rejected ranges unchanged"), result.message());
        assertTrue(result.message().contains("compressible inclusive ranges: 1-2"), result.message());
        assertTrue(result.message().contains("Do not substitute these IDs into old summaries"), result.message());
        assertTrue(result.message().contains("Verify the content of a current pair"), result.message());
        assertTrue(
                result.message().contains("if its content is unavailable or the work is unfinished"), result.message());
    }

    @Test
    void reversedLiveEndpointsAreNotReportedAsUnknownRefs() {
        final var compression = new ContextCompression(config());
        assignedRefs(compression, simpleConversation());
        final var notified = new AtomicBoolean();
        compression.onUnknownRefs(ranges -> notified.set(true));
        final var result = compression.compress("reversed", List.of(new ContextCompression.Range("2", "1", "wrong")));

        assertTrue(result.success(), result.message());
        assertNull(result.blockId());
        assertTrue(compression.blocks().isEmpty());
        assertTrue(result.message().startsWith("outcome: no_change\ncreated: 0\nskipped: 1\n"), result.message());
        assertTrue(
                result.message().contains("both endpoints exist, but endId precedes startId in conversation order"),
                result.message());
        assertFalse(result.message().contains("not in the live conversation"), result.message());
        assertFalse(notified.get(), "reversed live endpoints must not fire the unknown-refs observer");
    }

    @Test
    void appliedBatchReportsCreatedBlocksWithoutSkips() {
        final var compression = new ContextCompression(config());
        assignedRefs(compression, simpleConversation());
        final var result = compression.compress("finished", List.of(new ContextCompression.Range("1", "2", "done")));

        assertTrue(result.success(), result.message());
        assertEquals("b1", result.blockId());
        assertEquals(1, compression.blocks().size());
        assertTrue(result.message().startsWith("outcome: applied\ncreated: 1\nskipped: 0\n"), result.message());
        assertFalse(result.message().contains("Do not retry"), result.message());
    }

    @Test
    void mixedMissingRefBatchReportsPartialSuccessWithoutRemapping() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var result = compression.compress(
                "mixed",
                List.of(
                        new ContextCompression.Range("999", "998", "stale summary"),
                        new ContextCompression.Range("1", "2", "finished work")));

        assertTrue(result.success(), result.message());
        assertEquals("b1", result.blockId());
        assertTrue(result.message().startsWith("outcome: partial_success\ncreated: 1\nskipped: 1\n"), result.message());
        assertTrue(result.message().contains("Skipped ranges were not applied"), result.message());
        assertEquals(1, compression.blocks().size());
        assertEquals("finished work", compression.blocks().getFirst().summary());
        assertEquals(messages.get(3), compression.rewrite(messages).get(2));
    }

    @Test
    void unknownRefsObserverFiresOnlyOnRefLookupSkip() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var observed = new AtomicReference<List<ContextCompression.Range>>();
        final var fired = new AtomicBoolean(false);
        compression.onUnknownRefs(refs -> {
            fired.set(true);
            observed.set(refs);
        });

        // a bogus ref is not in the live conversation -> the observer gets the valid list
        final var skipped = compression.compress("t", List.of(new ContextCompression.Range("999", "999", "bogus")));
        assertTrue(skipped.success(), skipped.message());
        assertTrue(skipped.message().contains("not in the live conversation"), skipped.message());
        assertTrue(fired.get(), "the observer must fire on a ref-lookup skip");
        assertEquals(List.of(new ContextCompression.Range("1", "2", null)), observed.get());

        // a clean success must NOT fire the observer
        fired.set(false);
        observed.set(null);
        final var ok = compression.compress("first", List.of(new ContextCompression.Range("1", "2", "done")));
        assertTrue(ok.success(), ok.message());
        assertFalse(fired.get(), "a successful compress must not fire the unknown-refs observer");

        // a shape skip (assistant start with no prior user before its end) must NOT fire it either
        fired.set(false);
        final var shape = compression.compress("t", List.of(new ContextCompression.Range("2", "2", "collapses")));
        assertTrue(shape.success(), shape.message());
        assertTrue(shape.message().contains("cannot be bounded"), shape.message());
        assertFalse(fired.get(), "a shape skip must not fire the unknown-refs observer");
    }

    @Test
    void unknownRefsObserverHandlesNothingEligibleAndNullListener() {
        final var compression = new ContextCompression(protectedConfig()); // turnProtection=1
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        // compress the only eligible turn (ref 1) so an ACTIVE block covers it, leaving nothing eligible
        compression.compress("first", List.of(new ContextCompression.Range("1", "2", "done")));
        assertEquals(0, compression.compressibleClosedTurnCount());
        final var fired = new AtomicBoolean(false);
        compression.onUnknownRefs(refs -> fired.set(true));
        final var skipped = compression.compress("t", List.of(new ContextCompression.Range("999", "999", "bogus")));
        assertTrue(skipped.success(), skipped.message());
        assertTrue(fired.get(), "the observer must still fire even when nothing is eligible");

        // a null listener is a no-op (never throws)
        compression.onUnknownRefs(null);
        assertTrue(compression
                .compress("t", List.of(new ContextCompression.Range("999", "999", "x")))
                .success());
    }

    @Test
    void unknownRefsObserverExcludesRangesCreatedInTheSameBatch() {
        final var compression = new ContextCompression(config());
        assignedRefs(compression, simpleConversation());
        final var observed = new AtomicReference<List<ContextCompression.Range>>();
        compression.onUnknownRefs(observed::set);
        final var result = compression.compress(
                "sample",
                List.of(
                        new ContextCompression.Range("1", "2", "finished"),
                        new ContextCompression.Range("999", "999", "missing")));
        assertTrue(result.success(), result.message());
        assertEquals(List.of(), observed.get());
    }

    @Test
    void compressibleGuidanceIsDeterministicAcrossReadsWithoutMutation() {
        // the ACP #207 feedback-loop class of bug: a shared per-call baseline silently reset on
        // "nothing to compress" made guidance vanish or misfire. Here the guidance is a pure function
        // of published+blocks with no cross-call mutation, so repeated reads (as the volatile tail
        // does when nothing changed) must return the identical bounded list, and the empty case must
        // stay empty rather than flip. Simulates two consecutive tail builds without any state change.
        final var compression = new ContextCompression(protectedConfig()); // turnProtection=1
        final var messages = simpleConversation();
        assignedRefs(compression, messages);

        assertEquals(List.of(new ContextCompression.Range("1", "2", null)), compression.compressibleClosedTurns());
        assertEquals(
                List.of(new ContextCompression.Range("1", "2", null)),
                compression.compressibleClosedTurns(),
                "guidance must not drift between reads");
        assertEquals(1, compression.compressibleClosedTurnCount());
        assertEquals(1, compression.compressibleClosedTurnCount());

        // compress the only eligible turn: now the empty case must be stable too
        compression.compress("first", List.of(new ContextCompression.Range("1", "2", "done")));
        assertEquals(0, compression.compressibleClosedTurnCount());
        assertEquals(0, compression.compressibleClosedTurnCount(), "the empty case must stay empty, never flip");
        assertEquals(List.of(), compression.compressibleClosedTurns());
        assertEquals(List.of(), compression.compressibleClosedTurns());
    }
}
