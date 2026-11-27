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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.style.Color;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SyntaxHighlighterTest {
    private final SyntaxHighlighter highlighter = new SyntaxHighlighter();

    private final SyntaxHighlighter shortHighlighter = new SyntaxHighlighter().maxLineLength(2000);

    private final SyntaxHighlighter longHighlighter = new SyntaxHighlighter().maxLineLength(50_000);

    private static String concat(final List<Line> lines) {
        return lines.stream()
                .flatMap(l -> l.spans().stream())
                .map(Span::content)
                .reduce("", String::concat);
    }

    @Test
    void splitWithoutFenceIsSingleTextSegment() {
        final var segments = highlighter.split("just some text\non two lines");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.TEXT, segments.get(0).type());
        assertEquals("just some text\non two lines", segments.get(0).text());
    }

    @Test
    void splitWithFenceExtractsLanguageAndBody() {
        final var segments = highlighter.split("before\n\n```java\nint a = 1;\n```\n\nafter");
        assertEquals(3, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.TEXT, segments.get(0).type());
        assertEquals("before", segments.get(0).text().strip());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(1).type());
        assertEquals("java", segments.get(1).language());
        assertEquals("int a = 1;", segments.get(1).text());
        assertEquals(SyntaxHighlighter.Segment.Type.TEXT, segments.get(2).type());
        assertEquals("after", segments.get(2).text().strip());
    }

    @Test
    void splitFenceWithoutLanguageUsesEmptyLanguage() {
        final var segments = highlighter.split("```\nplain code\n```");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(0).type());
        assertEquals("", segments.get(0).language());
        assertEquals("plain code", segments.get(0).text());
    }

    @Test
    void tildesDoNotCloseBacktickFence() {
        // a fence discussing fences: the ~~~ line must not truncate the block
        final var segments = highlighter.split("```java\n\ncode\n~~~\nafter\n```");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(0).type());
        assertEquals("java", segments.get(0).language());
        assertTrue(segments.get(0).text().contains("~~~"), "body must keep the tilde line");
        assertEquals("\ncode\n~~~\nafter", segments.get(0).text());
    }

    @Test
    void tildeFenceIsHighlightedAndClosesOnlyOnTildes() {
        final var segments = highlighter.split("before\n\n~~~sql\nSELECT 1;\n~~~\n\nafter");
        assertEquals(3, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(1).type());
        assertEquals("sql", segments.get(1).language());
        assertEquals("SELECT 1;", segments.get(1).text());
    }

    @Test
    void fenceInfoStringMayCarryExtraTokens() {
        final var segments = highlighter.split("```java title=App.java\nint a;\n```");
        assertEquals(1, segments.size());
        assertEquals("java", segments.get(0).language());
        assertEquals("int a;", segments.get(0).text());
    }

    @Test
    void splitSkipsBlankTextSegments() {
        final var segments = highlighter.split("```java\nint a;\n```");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(0).type());
    }

    @Test
    void fenceBodyDropsTheTerminatingNewLine() {
        // the newline closing the last code line belongs to the fence, not to the code: keeping it
        // highlights one phantom trailing empty line, which renders as a blank row after the block
        assertEquals("code", highlighter.split("```\ncode\n```").get(0).text());
        assertEquals("code", highlighter.split("```\ncode\n```\nnext").get(0).text());
        assertEquals("code", highlighter.split("```\ncode\n```").get(0).text());
        // an empty fence body stays empty (no lone "\n" left behind)
        assertEquals("", highlighter.split("```\n```").get(0).text());
        // internal blank lines are source and must survive
        assertEquals(
                "one\n\ntwo", highlighter.split("```\none\n\ntwo\n```").get(0).text());
        assertEquals(
                List.of("one", "", "two"),
                highlighter.highlightLines("", "one\n\ntwo").stream()
                        .map(Line::rawContent)
                        .toList());
    }

    @Test
    void splitHandlesNullAndBlank() {
        assertEquals(List.of(), highlighter.split(null));
        assertEquals(List.of(), highlighter.split("   \n  "));
    }

    @Test
    void highlightProducesOneLinePerSourceLine() {
        final var text = highlighter.highlight("java", "public class A {\n    int a;\n}");
        assertEquals(3, text.height());
        assertTrue(text.rawContent().contains("public"));
    }

    @Test
    void highlightColorsJavaKeywords() {
        final var line = highlighter.highlight("java", "public class A").lines().get(0);
        final Optional<Color> keyword = line.spans().stream()
                .filter(s -> s.content().equals("public"))
                .findFirst()
                .flatMap(s -> s.style().fg());
        assertTrue(keyword.isPresent(), "expected the keyword to carry a foreground color");
        assertEquals(0xc6, keyword.get().toRgb().r());
        assertEquals(0x78, keyword.get().toRgb().g());
        assertEquals(0xdd, keyword.get().toRgb().b());
    }

    @Test
    void highlightColorsJavaAnnotations() {
        final var line = highlighter
                .highlight("java", "@Override\n@Deprecated void run()")
                .lines()
                .get(0);
        final Optional<Color> annotation = line.spans().stream()
                .filter(s -> s.content().equals("@Override"))
                .findFirst()
                .flatMap(s -> s.style().fg());
        assertTrue(annotation.isPresent(), "expected the annotation to carry a foreground color");
        assertEquals(0xc5, annotation.get().toRgb().r());
        assertEquals(0x86, annotation.get().toRgb().g());
        assertEquals(0xc0, annotation.get().toRgb().b());
    }

    @Test
    void highlightColorsJavaTypes() {
        final var line = highlighter
                .highlight("java", "CompletableFuture<String> f;")
                .lines()
                .get(0);
        final Optional<Color> type = line.spans().stream()
                .filter(s -> s.content().equals("CompletableFuture"))
                .findFirst()
                .flatMap(s -> s.style().fg());
        assertTrue(type.isPresent(), "expected the type to carry a foreground color");
        assertEquals(0x61, type.get().toRgb().r());
        assertEquals(0xaf, type.get().toRgb().g());
        assertEquals(0xef, type.get().toRgb().b());
    }

    @Test
    void highlightColorsStrings() {
        final var line =
                highlighter.highlight("java", "var s = \"str\";").lines().get(0);
        assertTrue(
                line.spans().stream()
                        .anyMatch(s ->
                                s.content().contains("str") && s.style().fg().isPresent()),
                "expected the string literal to be colored");
    }

    @Test
    void highlightUnknownLanguageStillTokenizes() {
        final var text = highlighter.highlight("cobol", "MOVE A TO B.");
        assertEquals(1, text.height());
        assertFalse(text.isEmpty());
    }

    @Test
    void highlightEmptyCodeIsSafe() {
        assertTrue(highlighter.highlight("java", "").isEmpty());
    }

    @Test
    void highlightShellAndSqlAndXmlDoNotThrow() {
        assertFalse(highlighter.highlight("bash", "echo 'hi' # comment").isEmpty());
        assertFalse(highlighter.highlight("sql", "SELECT * FROM t WHERE x = 1").isEmpty());
        assertFalse(highlighter.highlight("xml", "<a b='c'>text</a>").isEmpty());
        assertFalse(
                highlighter.highlight("python", "def f(x):\n    return None").isEmpty());
    }

    @Test
    void escapedStringsAreStillHighlightedAsStrings() {
        final var escaped = highlighter.highlight("java", "var s = \"a\\\"b\";").lines().get(0).spans().stream()
                .filter(s -> s.content().contains("a"))
                .filter(s -> s.content().contains("b"))
                .findFirst();
        assertTrue(escaped.isPresent(), "expected the escaped string to be present");
        assertTrue(escaped.get().style().fg().isPresent(), "expected the escaped string literal to be colored");
    }

    @Test
    void unclosedEscapedStringDoesNotThrow() {
        for (final var q : new String[] {"\"", "'", "`"}) {
            assertDoesNotThrow(() -> shortHighlighter.highlight("python", q + "\\\\".repeat(9_000)));
            assertDoesNotThrow(() -> shortHighlighter.highlight("javascript", q + "a".repeat(9_000)));
        }
    }

    @Test
    void unclosedMultiLineCommentDoesNotThrow() {
        assertDoesNotThrow(() -> shortHighlighter.highlight("java", "/*" + "a".repeat(9_000)));
        assertDoesNotThrow(() -> shortHighlighter.highlight("xml", "<!--" + "a".repeat(9_000)));
    }

    @Test
    void overLongLineFallsBackToPlain() {
        final var longLine = "var s = \"x\"; " + "a".repeat(10_500);
        final var line = shortHighlighter.highlight("java", longLine).lines().get(0);
        assertEquals(1, line.spans().size());
        assertEquals(longLine, line.spans().get(0).content());
        assertTrue(line.spans().get(0).style().fg().isEmpty(), "over-long line should not be tokenized");
    }

    @Test
    void yamlIsHighlighted() {
        final var lines = highlighter.highlightLines("yaml", "key: value\n# comment\n- item\nflag: true");
        assertEquals(4, lines.size());
        assertTrue(
                lines.stream()
                        .flatMap(l -> l.spans().stream())
                        .anyMatch(s -> s.style().fg().isPresent()),
                "expected at least one styled span");
        final var text = lines.stream()
                .flatMap(l -> l.spans().stream())
                .map(Span::content)
                .reduce("", String::concat);
        assertTrue(text.contains("key: value"), text);
        assertTrue(text.contains("flag: true"), text);
    }

    @Test
    void highlightLinesReturnsCachedInstanceOnRepeat() {
        final var first = highlighter.highlightLines("java", "public class A {\n    int a;\n}");
        final var second = highlighter.highlightLines("java", "public class A {\n    int a;\n}");
        assertSame(first, second, "identical blocks must reuse the cached result");
        assertSame(first.get(0), second.get(0), "identical lines must reuse the cached line");
        final var other = highlighter.highlightLines("java", "public class B {\n}");
        assertNotSame(first, other, "a different block must not alias the cache entry");
    }

    @Test
    void splitReturnsCachedInstanceOnRepeat() {
        final var first = highlighter.split("```java\nint a;\n```");
        final var second = highlighter.split("```java\nint a;\n```");
        assertSame(first, second, "identical markdown must reuse the cached segments");
    }

    @Test
    @Timeout(1)
    void splitWeightEvictsLeastRecentlyUsedBelowEntryLimit() {
        final var a = "a".repeat(90_000);
        final var b = "b".repeat(90_000);
        final var c = "c".repeat(90_000);
        final var first = highlighter.split(a);
        final var second = highlighter.split(b);
        assertSame(first, highlighter.split(a)); // refresh a, so b is oldest
        highlighter.split(c);
        assertSame(first, highlighter.split(a));
        assertNotSame(second, highlighter.split(b));
    }

    @Test
    @Timeout(1)
    void splitOversizedSourceAndLayoutBypassWithoutEvictingSmallEntries() {
        final var small = highlighter.split("small");
        for (final var source : List.of("x".repeat(300_000), "```\nx\n```\n".repeat(18_000))) {
            final var first = highlighter.split(source);
            final var second = highlighter.split(source);
            assertEquals(first, second);
            assertNotSame(first, second);
            assertSame(small, highlighter.split("small"));
        }
    }

    @Test
    @Timeout(1)
    void blockWeightEvictsLeastRecentlyUsedBelowEntryLimit() {
        // Over the tokenization cutoff: large plain blocks exercise retention, not regex speed.
        final var a = "a".repeat(300_000);
        final var b = "b".repeat(300_000);
        final var c = "c".repeat(300_000);
        final var first = highlighter.highlightLines("java", a);
        final var second = highlighter.highlightLines("java", b);
        highlighter.highlightLines("java", c);
        assertSame(first, highlighter.highlightLines("java", a));
        highlighter.highlightLines("java", "d".repeat(300_000));
        assertSame(first, highlighter.highlightLines("java", a));
        assertNotSame(second, highlighter.highlightLines("java", b));
    }

    @Test
    @Timeout(1)
    void blockOversizedSourceLanguageAndLayoutBypassWithoutEvictingSmallEntries() {
        final var small = highlighter.highlightLines("java", "public class A");
        for (final var source : List.of("x".repeat(1_100_000), "\n".repeat(20_000))) {
            final var first = highlighter.highlightLines("java", source);
            final var second = highlighter.highlightLines("java", source);
            assertNotSame(first, second);
            assertEquals(first.size(), second.size());
            assertEquals(source, first.stream().map(this::lineText).collect(Collectors.joining("\n")));
            assertSame(small, highlighter.highlightLines("java", "public class A"));
        }
        final var language = "java" + "x".repeat(2_100_000);
        assertNotSame(highlighter.highlightLines(language, "x"), highlighter.highlightLines(language, "x"));
        assertSame(small, highlighter.highlightLines("java", "public class A"));
    }

    @Test
    @Timeout(1)
    void lineWeightEvictsLeastRecentlyUsedBelowEntryLimit() {
        highlighter.maxLineLength(100_000);
        final var a = "// a" + "x".repeat(40_000);
        final var b = "// b" + "x".repeat(40_000);
        final var first =
                highlighter.highlightLines("java", a).getFirst().spans().getFirst();
        final var second =
                highlighter.highlightLines("java", b).getFirst().spans().getFirst();
        // Different block keys, same tokenizer: observe line cache hits independently of block hits.
        assertSame(
                first, highlighter.highlightLines("JAVA", a).getFirst().spans().getFirst());
        for (var i = 0; i < 11; i++) {
            highlighter.highlightLines("java", "// " + i + "x".repeat(40_000));
        }
        assertSame(
                first,
                highlighter
                        .highlightLines("java title=refresh", a)
                        .getFirst()
                        .spans()
                        .getFirst());
        highlighter.highlightLines("java", "// last" + "x".repeat(40_000));
        assertSame(
                first,
                highlighter
                        .highlightLines("java title=still", a)
                        .getFirst()
                        .spans()
                        .getFirst());
        assertNotSame(
                second,
                highlighter
                        .highlightLines("java title=evicted", b)
                        .getFirst()
                        .spans()
                        .getFirst());
    }

    @Test
    @Timeout(1)
    void oversizedLineBypassesWithoutEvictingSmallEntries() {
        highlighter.maxLineLength(600_000);
        final var small =
                highlighter.highlightLines("java", "public").getFirst().spans().getFirst();
        final var source = "// " + "x".repeat(530_000);
        final var first =
                highlighter.highlightLines("java", source).getFirst().spans().getFirst();
        final var second =
                highlighter.highlightLines("JAVA", source).getFirst().spans().getFirst();
        assertEquals(source, first.content());
        assertEquals(first.content(), second.content());
        assertNotSame(first, second);
        assertSame(
                small,
                highlighter
                        .highlightLines("java title=other", "public")
                        .getFirst()
                        .spans()
                        .getFirst());
    }

    @Test
    @Timeout(1)
    void oversizedLineLayoutBypassesEvenWithSmallSource() {
        highlighter.maxLineLength(20_000);
        final var small =
                highlighter.highlightLines("java", "public").getFirst().spans().getFirst();
        // Short source, but two spans per number: output layout alone exceeds the line budget.
        final var source = "1 ".repeat(9_000);
        final var first = highlighter.highlightLines("java", source).getFirst();
        final var second = highlighter.highlightLines("JAVA", source).getFirst();
        assertEquals(source, lineText(first));
        assertEquals(source, lineText(second));
        assertNotSame(first.spans().getFirst(), second.spans().getFirst());
        assertSame(
                small,
                highlighter
                        .highlightLines("java title=other", "public")
                        .getFirst()
                        .spans()
                        .getFirst());
    }

    @Test
    @Timeout(1)
    void publicClearReleasesAllCachesAndResetsWeightAccounting() {
        final var source = "x".repeat(90_000);
        final var split = highlighter.split(source);
        final var block = highlighter.highlightLines("java", "public class A");
        final var span = block.getFirst().spans().getFirst();
        highlighter.clearCaches();
        final var after = highlighter.split(source);
        assertNotSame(split, after);
        assertSame(after, highlighter.split(source));
        final var highlighted = highlighter.highlightLines("java", "public class A");
        assertNotSame(block, highlighted);
        assertNotSame(span, highlighted.getFirst().spans().getFirst());
        assertSame(highlighted, highlighter.highlightLines("java", "public class A"));
        highlighter.theme(CodeTheme.ONE_LIGHT);
        final var light = highlighter.highlightLines("java", "public class A");
        assertNotSame(highlighted, light);
        assertNotSame(
                highlighted.getFirst().spans().getFirst().style(),
                light.getFirst().spans().getFirst().style());
        assertSame(light, highlighter.highlightLines("java", "public class A"));
        assertNotSame(after, highlighter.split(source));
    }

    @Test
    void unterminatedFenceStaysPlainText() {
        // no closing fence: the whole region is one TEXT segment, matching the regex behavior
        final var segments = highlighter.split("before\n```\nnever closes");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.TEXT, segments.get(0).type());
        assertEquals("before\n```\nnever closes", segments.get(0).text());
    }

    @Test
    void openerWithoutCloseIsTextThenLaterClosedFenceStillResolves() {
        // the first ``` has no close, so it is plain text; the later ~~~ fence closes normally
        final var segments = highlighter.split("AAA\n```\nBBB\n```x\n~~~\nCCC\n~~~\nDDD");
        assertEquals(3, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.TEXT, segments.get(0).type());
        assertEquals("AAA\n```\nBBB\n```x", segments.get(0).text().strip());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(1).type());
        assertEquals("", segments.get(1).language());
        assertEquals("CCC", segments.get(1).text());
        assertEquals(SyntaxHighlighter.Segment.Type.TEXT, segments.get(2).type());
        assertEquals("DDD", segments.get(2).text().strip());
    }

    @Test
    void largeFencedBodySplitsLinearlyWithinBudget() {
        // the old DOTALL-lazy backreference regex was quadratic on a large fenced body (tens of
        // seconds even for a few hundred KB); the line scanner must finish far inside the budget
        // even for a multi-megabyte block
        final var body = "line " + "x".repeat(200) + "\n";
        final var bigBlock = "```text\n" + body.repeat(30_000) + "```\n";
        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> {
                    final var segments = highlighter.split(bigBlock);
                    assertEquals(1, segments.size());
                    assertEquals(
                            SyntaxHighlighter.Segment.Type.CODE, segments.get(0).type());
                },
                "a large fenced body must be split in linear (not quadratic) time");
    }

    @Test
    void appendOnlyGrowthKeepsSplittingCorrectly() {
        // the growing trailing answer is re-scanned each change; it must stay linear and correct
        // (the code block keeps its CODE segment and the appended prose its trailing TEXT one)
        var stream = "```java\nint a = 1;\n```\n";
        for (var i = 0; i < 2_000; i++) {
            stream += " more prose " + i + " ";
            final var segments = highlighter.split(stream);
            assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(0).type());
            assertEquals(
                    SyntaxHighlighter.Segment.Type.TEXT,
                    segments.get(segments.size() - 1).type());
        }
    }

    @Test
    void themeChangeInvalidatesCachedHighlights() {
        final var oneDark = highlighter.highlightLines("java", "public class A");
        highlighter.theme(CodeTheme.ONE_LIGHT);
        final var oneLight = highlighter.highlightLines("java", "public class A");
        assertNotSame(oneDark, oneLight, "a theme change must re-tokenize cached blocks");
        final Optional<Color> keyword = oneLight.get(0).spans().stream()
                .filter(s -> s.content().equals("public"))
                .findFirst()
                .flatMap(s -> s.style().fg());
        assertTrue(keyword.isPresent(), "expected the keyword to carry a foreground color");
        assertEquals(0xa6, keyword.get().toRgb().r());
        assertEquals(0x26, keyword.get().toRgb().g());
        assertEquals(0xa4, keyword.get().toRgb().b());
    }

    @Test
    void maxLineLengthChangeInvalidatesCachedHighlights() {
        final var longLine = "var s = \"x\"; " + "a".repeat(10_500);
        final var plain = shortHighlighter.highlightLines("java", longLine).get(0);
        assertEquals(1, plain.spans().size());
        assertEquals(longLine, concat(List.of(plain)));
        shortHighlighter.maxLineLength(20_000);
        final var tokenized = shortHighlighter.highlightLines("java", longLine).get(0);
        assertNotSame(plain, tokenized, "a maxLineLength change must re-tokenize cached blocks");
        assertTrue(tokenized.spans().size() > 1, "expected tokenization after the maxLineLength bump");
    }

    @Test
    void keywordsDoNotMatchInsideWords() {
        final var publicity = highlighter.highlightLines("java", "publicity").get(0);
        assertFalse(
                publicity.spans().stream().anyMatch(s -> s.content().equals("public")),
                "public must not match inside publicity");
        assertEquals("publicity", concat(List.of(publicity)));

        final var index = highlighter.highlightLines("typescript", "index").get(0);
        assertFalse(index.spans().stream().anyMatch(s -> s.content().equals("in")), "in must not match inside index");
        assertEquals("index", concat(List.of(index)));

        final var print = highlighter.highlightLines("java", "print").get(0);
        assertFalse(print.spans().stream().anyMatch(s -> s.content().equals("int")), "int must not match inside print");
        assertEquals("print", concat(List.of(print)));

        final var forLine =
                highlighter.highlightLines("typescript", "for (const x in y)").get(0);
        final var inCount =
                forLine.spans().stream().filter(s -> s.content().equals("in")).count();
        assertEquals(1, inCount, "keyword in must be matched exactly once");
        assertEquals("for (const x in y)", concat(List.of(forLine)));
    }

    @Test
    void semanticTagAndAttributeNamesAreColoredButPunctuationInheritsForeground() {
        final var xml =
                highlighter.highlightLines("xml", "<a id=\"x\">text</a>").get(0);
        assertEquals("<a id=\"x\">text</a>", lineText(xml));
        assertColor(xml, "a", CodeTheme.ONE_DARK.tag());
        assertColor(xml, "id", CodeTheme.ONE_DARK.attr());
        assertColor(xml, "\"x\"", CodeTheme.ONE_DARK.string());
        for (final var punctuation : List.of("<", "=", ">", "</")) {
            assertRaw(xml, punctuation);
        }
        final var assignment = highlighter.highlightLines("javascript", "a=b;").get(0);
        assertEquals("a=b;", lineText(assignment));
        // Assignment is not a markup attribute: identifiers and operators inherit the UI foreground.
        assertRaw(assignment, "a");
        assertRaw(assignment, "=");
        assertRaw(assignment, "b");
    }

    @Test
    void highlightLinesIsThreadSafe() {
        final var errors = new ConcurrentLinkedQueue<Throwable>();
        final var threads = new ArrayList<Thread>();
        for (var i = 0; i < 8; i++) {
            final var toggle = i % 2 == 0;
            threads.add(new Thread(() -> {
                for (var j = 0; j < 250; j++) {
                    try {
                        if (toggle && j % 50 == 0) {
                            highlighter.theme(j % 100 == 0 ? CodeTheme.ONE_LIGHT : CodeTheme.ONE_DARK);
                        }
                        final var lines = highlighter.highlightLines("java", "public class A { int a; }");
                        assertEquals(
                                "public class A { int a; }",
                                concat(lines),
                                "concurrent tokenization corrupted the line");
                        final var segments = highlighter.split("```java\nint a;\n```");
                        assertEquals(
                                SyntaxHighlighter.Segment.Type.CODE,
                                segments.get(0).type());
                    } catch (final Throwable t) {
                        errors.add(t);
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        for (final var t : threads) {
            try {
                t.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
        }
        assertTrue(errors.isEmpty(), () -> "concurrent highlight failed: " + errors);
    }

    @Test
    void messyLongLineRendersWithinBudget() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    final var line = "\"a\\\"b\" key=value 010203040506070809 "
                            .repeat(700)
                            .concat("a".repeat(9_000));
                    final var lines = longHighlighter.highlightLines("java", line);
                    assertEquals(line, concat(lines));
                },
                "the possessive-quantified tokenizer must stay linear on messy long lines");
    }

    @Test
    void guessLanguageMapsExtensionsAndIgnoresUnknown() {
        assertEquals("java", SyntaxHighlighter.guessLanguage("src/main/java/Foo.java"));
        assertEquals("kotlin", SyntaxHighlighter.guessLanguage("Foo.KT"), "extensions are case-insensitive");
        assertEquals("xml", SyntaxHighlighter.guessLanguage("pom.xml"));
        assertEquals("json5", SyntaxHighlighter.guessLanguage("/data/config.json5"));
        assertEquals("jsonc", SyntaxHighlighter.guessLanguage("/data/config.jsonc"));
        assertEquals("groovy", SyntaxHighlighter.guessLanguage("build.gradle"));
        assertEquals("properties", SyntaxHighlighter.guessLanguage("app.properties"));
        assertEquals("toml", SyntaxHighlighter.guessLanguage("app.toml"));
        assertEquals("ini", SyntaxHighlighter.guessLanguage("app.ini"));
        assertEquals("yaml", SyntaxHighlighter.guessLanguage("build.yaml"));
        assertEquals("javascript", SyntaxHighlighter.guessLanguage("app.js"));
        assertEquals("typescript", SyntaxHighlighter.guessLanguage("app.ts"));
        assertEquals("python", SyntaxHighlighter.guessLanguage("script.py"));
        assertEquals("shell", SyntaxHighlighter.guessLanguage("run.sh"));
        assertEquals("sql", SyntaxHighlighter.guessLanguage("query.sql"));
        assertEquals("markdown", SyntaxHighlighter.guessLanguage("README.md"));
        assertEquals("markdown", SyntaxHighlighter.guessLanguage("CHANGELOG.markdown"));
        assertEquals("asciidoc", SyntaxHighlighter.guessLanguage("README.adoc"));
        assertEquals("asciidoc", SyntaxHighlighter.guessLanguage("doc.asciidoc"));
        assertNull(SyntaxHighlighter.guessLanguage("Makefile"), "extension-less files are unknown");
        assertNull(SyntaxHighlighter.guessLanguage("noext"));
        assertNull(SyntaxHighlighter.guessLanguage(null));
        assertNull(SyntaxHighlighter.guessLanguage(""));
    }

    @Test
    void markdownIsHighlighted() {
        final var lines = highlighter.highlightLines(
                "markdown",
                "# Title\n\n**bold** and *em* and _em2_ text\n\n- item\n\n`code` span\n\n| a | b |\n\n---\n");
        final var all = lines.stream().flatMap(l -> l.spans().stream()).toList();
        final var colored = all.stream()
                .filter(s -> s.style().fg().isPresent())
                .map(Span::content)
                .toList();
        assertTrue(colored.stream().anyMatch(c -> c.startsWith("#")), "heading must be colored");
        assertTrue(colored.stream().anyMatch(c -> c.startsWith("**")), "bold must be colored");
        assertTrue(colored.stream().anyMatch(c -> c.startsWith("*e") || c.equals("*em*")), "emphasis must be colored");
        assertTrue(colored.stream().anyMatch(c -> c.startsWith("-")), "list marker must be colored");
        assertTrue(
                lines.stream()
                        .anyMatch(l -> l.spans().stream()
                                .anyMatch(s -> s.content().startsWith("`")
                                        && s.style().fg().isPresent())),
                "code span must be colored");
        assertTrue(
                lines.stream()
                        .anyMatch(l -> l.spans().stream()
                                .anyMatch(s -> s.content().startsWith("|")
                                        && s.style().fg().isPresent())),
                "table row must be colored");
        // the whole source must reconstruct verbatim (no token is ever swallowed)
        assertEquals(
                "# Title\n\n**bold** and *em* and _em2_ text\n\n- item\n\n`code` span\n\n| a | b |\n\n---\n",
                lines.stream().map(this::lineText).collect(Collectors.joining("\n")));
    }

    @Test
    void asciidocIsHighlighted() {
        final var lines = highlighter.highlightLines(
                "asciidoc",
                "= Title\n\n:author: me\n\n*bold* and _italic_ text\n\n+code+ and `code2`\n\n[[anchor]]\n\n[source,java]\n----\nint a;\n----\n\n// comment\n");
        final var all = lines.stream().flatMap(l -> l.spans().stream()).toList();
        final var colored = all.stream()
                .filter(s -> s.style().fg().isPresent())
                .map(Span::content)
                .toList();
        assertTrue(colored.stream().anyMatch(c -> c.startsWith("=")), "heading must be colored");
        assertTrue(colored.stream().anyMatch(c -> c.startsWith(":author")), "attribute must be colored");
        assertTrue(
                colored.stream().anyMatch(c -> c.startsWith("+code") || c.equals("+code+")),
                "passthrough must be colored");
        assertTrue(colored.stream().anyMatch(c -> c.equals("[[anchor]]")), "anchor must be colored");
        // Block delimiters are semantic operators, not tags: retain them without overriding foreground.
        assertRaw(lines.get(11), "----");
        assertRaw(lines.get(13), "----");
        assertTrue(colored.stream().anyMatch(c -> c.startsWith("// comment")), "comment must be colored");
        assertEquals(
                "= Title\n\n:author: me\n\n*bold* and _italic_ text\n\n+code+ and `code2`\n\n[[anchor]]\n\n[source,java]\n----\nint a;\n----\n\n// comment\n",
                lines.stream().map(this::lineText).collect(Collectors.joining("\n")));
    }

    @Test
    void mdAndAdocDoNotThrowAndRenderWithinBudget() {
        assertFalse(highlighter.highlight("markdown", "# t\n\n- a\n\n**b**").isEmpty());
        assertFalse(highlighter.highlight("md", "`c` and *e*").isEmpty());
        assertFalse(highlighter
                .highlight("asciidoc", "= T\n\n: k: v\n\n----\nx\n----\n")
                .isEmpty());
        assertFalse(highlighter
                .highlight("adoc", "[source,java]\n----\nint a;\n----\n")
                .isEmpty());
        // adversarial unterminated spans must render within budget: the bounded lookahead keeps
        // a single attempt O(512) instead of super-linear backtracking on the whole line.
        assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> {
                    final var line = "*a".repeat(5_000);
                    assertEquals(line, concat(highlighter.highlightLines("markdown", line)));
                },
                "unterminated bold/emphasis must stay within the bounded lookahead budget");
    }

    @Test
    void pathologicalMarkdownLineHighlightsWithinBudget() {
        final var inputs = new String[] {
            "*a".repeat(5_000),
            "`".repeat(10_000),
            "*_".repeat(5_000),
            "**x".repeat(3_000),
            "[a](b".repeat(3_000),
            "#".repeat(10_000),
        };
        for (final var line : inputs) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(1),
                    () -> assertEquals(
                            line,
                            concat(highlighter.highlightLines("markdown", line)),
                            "markdown tokenizer must never corrupt the source"),
                    "pathological markdown line must stay linear: " + line.length());
        }
    }

    @Test
    void pathologicalAsciidocLineHighlightsWithinBudget() {
        final var inputs = new String[] {
            "//".repeat(5_000), "+x".repeat(4_000), "=".repeat(10_000), "link:".repeat(3_000),
        };
        for (final var line : inputs) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(1),
                    () -> assertEquals(
                            line,
                            concat(highlighter.highlightLines("asciidoc", line)),
                            "asciidoc tokenizer must never corrupt the source"),
                    "pathological asciidoc line must stay linear: " + line.length());
        }
        // a line full of `[[` anchor openers has no `]]` closer: every position is a valid
        // candidate, so the check is O(n*512) by design (bounded, not super-linear). The
        // 10k case stays inside a generous ceiling; the common case (few anchors) is instant.
        final var anchorLine = "[[".repeat(5_000);
        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> assertEquals(anchorLine, concat(highlighter.highlightLines("asciidoc", anchorLine))),
                "a closer-less anchor line must stay bounded (O(n*512)), not super-linear");
    }

    @Test
    void largeMarkdownDocumentHighlightsWithinBudget() {
        // a ~20k-line mixed markdown document: the per-line tokenizer plus the bounded
        // lookahead must render far inside the ceiling (the linear-split precedent uses
        // a 6 MB fenced body at 2s; here the doc is a realistic mix of all constructs)
        final var doc = new StringBuilder();
        for (var i = 0; i < 2_000; i++) {
            doc.append("# Heading ").append(i).append('\n');
            doc.append("Some **bold** and *em* text with `code` and a [link](https://example.com/a).\n\n");
            doc.append("- item one\n- item two\n\n");
            doc.append("| a | b |\n| --- | --- |\n| 1 | 2 |\n\n");
            doc.append("```java\nint x = ").append(i).append(";\n```\n\n");
        }
        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> {
                    final var lines = longHighlighter.highlightLines("markdown", doc.toString());
                    assertEquals(
                            doc.toString(), lines.stream().map(this::lineText).collect(Collectors.joining("\n")));
                },
                "a large markdown document must render within the linear budget");
    }

    @Test
    void javascriptResolutionDoesNotAccidentallySelectJava() {
        final var source = "const value = typeof input;";
        final var javascript = highlighter.highlightLines("javascript", source).getFirst();
        assertColor(javascript, "const", CodeTheme.ONE_DARK.keyword());
        assertColor(javascript, "typeof", CodeTheme.ONE_DARK.keyword());
        assertRaw(javascript, "=");
        assertEquals(source, lineText(javascript));
        for (final var alias : List.of("js", "mjs", "JavaScript title=example")) {
            final var aliased = highlighter.highlightLines(alias, source).getFirst();
            assertSame(
                    javascript.spans().getFirst(),
                    aliased.spans().getFirst(),
                    "resolved aliases must reuse the canonical language's line cache");
        }
    }

    @Test
    void versionedJavaAndTsxAndJsoncResolveThroughSharedHighlighter() {
        final var java = highlighter.highlightLines("java", "public class A").getFirst();
        final var java21 =
                highlighter.highlightLines("Java 21", "public class A").getFirst();
        assertSame(java.spans().getFirst(), java21.spans().getFirst());
        final var tsx =
                highlighter.highlightLines("tsx", "const value: string = 'x';").getFirst();
        assertColor(tsx, "const", CodeTheme.ONE_DARK.keyword());
        assertColor(tsx, "string", CodeTheme.ONE_DARK.type());
        final var jsonc = highlighter.highlightLines("jsonc", "// comment").getFirst();
        assertColor(jsonc, "// comment", CodeTheme.ONE_DARK.comment());
    }

    @Test
    void explicitPlainAliasesStayPlainButUnknownAndMissingLanguagesUseGenericColors() {
        final var source = "return \"value\" + 42; // comment";
        for (final var alias : List.of("text", "txt", "plain", "plaintext", "console", "TEXT title=sample")) {
            final var line = highlighter.highlightLines(alias, source).getFirst();
            assertEquals(source, lineText(line));
            assertTrue(line.spans().stream().allMatch(s -> s.style().fg().isEmpty()), alias);
        }
        for (final var alias : new String[] {null, "", "not-a-language", "notpython", "java-unknown"}) {
            final var line = highlighter.highlightLines(alias, source).getFirst();
            assertEquals(source, lineText(line));
            assertColor(line, "return", CodeTheme.ONE_DARK.keyword());
            assertColor(line, "\"value\"", CodeTheme.ONE_DARK.string());
            assertColor(line, "42", CodeTheme.ONE_DARK.number());
            assertColor(line, "// comment", CodeTheme.ONE_DARK.comment());
        }
    }

    @Test
    void nullCodeAndCrLfAndTrailingEmptyLinesKeepTheirExistingSemantics() {
        assertEquals(List.of(), highlighter.highlightLines("java", null));
        assertEquals(List.of(), highlighter.highlightLines(null, null));
        assertTrue(highlighter.highlight("java", null).isEmpty());
        assertEquals(
                List.of(""),
                highlighter.highlightLines("java", "").stream()
                        .map(Line::rawContent)
                        .toList());
        assertEquals(
                List.of("public class A", "", " ", ""),
                highlighter.highlightLines("java", "public class A\r\n\r\n \r\n").stream()
                        .map(Line::rawContent)
                        .toList());
    }

    @Test
    void linesReuseCachedSpansAcrossBlocksWithoutCarryingLexicalContext() {
        final var source = "public class A";
        final var standalone = highlighter.highlightLines("java", source).getFirst();
        final var afterCommentOpener =
                highlighter.highlightLines("java", "/* comment\n" + source).get(1);
        assertSame(standalone.spans().getFirst(), afterCommentOpener.spans().getFirst());
        assertColor(afterCommentOpener, "public", CodeTheme.ONE_DARK.keyword());
        highlighter.clearCaches();
        final var refreshed =
                highlighter.highlightLines("java", "/* comment\n" + source).get(1);
        assertNotSame(standalone.spans().getFirst(), refreshed.spans().getFirst());
        assertColor(refreshed, "public", CodeTheme.ONE_DARK.keyword());
    }

    private String lineText(final Line line) {
        return line.spans().stream().map(Span::content).collect(Collectors.joining());
    }

    private static void assertColor(final Line line, final String text, final String hex) {
        final var spans =
                line.spans().stream().filter(s -> s.content().equals(text)).toList();
        assertFalse(spans.isEmpty(), () -> "missing semantic token: " + text);
        for (final var span : spans) {
            assertEquals(Optional.of(Color.hex(hex)), span.style().fg(), () -> "wrong color for: " + text);
        }
    }

    private static void assertRaw(final Line line, final String text) {
        final var spans =
                line.spans().stream().filter(s -> s.content().equals(text)).toList();
        assertFalse(spans.isEmpty(), () -> "missing separate unstyled token: " + text);
        for (final var span : spans) {
            assertTrue(span.style().fg().isEmpty(), () -> "unexpected foreground for: " + text);
        }
    }
}
