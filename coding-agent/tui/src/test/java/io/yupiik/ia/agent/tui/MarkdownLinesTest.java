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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarkdownLinesTest {
    private final MarkdownLines renderer = new MarkdownLines();

    @Test
    void codePreviewMatchesFullWrappingIncludingStylesAndBlankLines() {
        final var input = List.of(
                Line.from(Span.styled("abcdefghijk", Style.EMPTY.bold()), Span.styled(" xyz", Style.EMPTY.italic())),
                Line.empty(),
                Line.from("last line"));
        final var full = MarkdownLines.wrapCode(input, 4);
        for (var limit = 0; limit <= full.size() + 1; limit++) {
            final var preview = MarkdownLines.wrapCode(input, 4, limit);
            assertEquals(full.subList(0, Math.min(limit, full.size())), preview);
        }
    }

    @Test
    void longCodeLinePreservesEveryCharacterAndContinuationKind() {
        final var source = "0123456789".repeat(200);
        final var result = MarkdownLines.wrapCodeWithKinds(List.of(Line.from(source)), 80);
        assertEquals(source, String.join("", plain(result.lines())));
        assertEquals(SelectionState.RowKind.HARD, result.kinds().getFirst());
        assertTrue(result.kinds().subList(1, result.kinds().size()).stream()
                .allMatch(kind -> kind == SelectionState.RowKind.CHAR_SPLIT));
        assertEquals(
                3, MarkdownLines.wrapCode(List.of(Line.from(source)), 80, 3).size());
    }

    @Test
    void plainParagraph() {
        assertEquals(
                List.of("hello world"),
                plain(renderer.render("hello world", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void singleNewlineIsHardBreak() {
        assertEquals(
                List.of("one", "two"), plain(renderer.render("one\ntwo", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void softWrapsLongParagraph() {
        final var text = "aaaa bbbb cccc dddd eeee ffff gggg hhhh iiii";
        assertEquals(
                List.of("aaaa bbbb", "cccc dddd", "eeee ffff", "gggg hhhh", "iiii"),
                plain(renderer.render(text, 10, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void heading() {
        // block-level rhythm is preserved: one blank row between sibling blocks
        assertEquals(
                List.of("Title", "", "body"),
                plain(renderer.render("# Title\n\nbody", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void blankLineBetweenParagraphs() {
        assertEquals(
                List.of("one", "", "two"),
                plain(renderer.render("one\n\ntwo", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void htmlBlockContentIsNotDropped() {
        // multi-line <think>...</think> parses as an HTML block: its content must be
        // rendered literally, never silently discarded
        final var text = String.join(
                "\n",
                plain(renderer.render(
                        "<think>\nreasoning line\n</think>\n\nAnswer", 60, Style.EMPTY, MarkdownStyles.DEFAULTS)));
        assertTrue(text.contains("reasoning line"), text);
        assertTrue(text.contains("Answer"), text);
    }

    @Test
    void bulletList() {
        assertEquals(
                List.of("• one", "• two"),
                plain(renderer.render("- one\n- two", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void bulletListContinuationIndentsWrappedLine() {
        assertEquals(
                List.of("• alpha beta gamma delta epsilon zeta eta", "  theta iota kappa lambda"),
                plain(renderer.render(
                        "- alpha beta gamma delta epsilon zeta eta theta iota kappa lambda",
                        45,
                        Style.EMPTY,
                        MarkdownStyles.DEFAULTS)));
    }

    @Test
    void orderedList() {
        assertEquals(
                List.of("1. one", "2. two"),
                plain(renderer.render("1. one\n2. two", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void taskList() {
        assertEquals(
                List.of("[x] done", "[ ] todo"),
                plain(renderer.render("- [x] done\n- [ ] todo", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void blockquotePrefixedOnEveryLine() {
        assertEquals(
                List.of("│ quoted", "│ second"),
                plain(renderer.render("> quoted\n> second", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void quoteKeepsRailAcrossBlocksAndNestedContent() {
        final var result = renderer.renderWithKinds(
                "> first\n>\n> - item\n>\n>     code", 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        assertEquals(List.of("│ first", "│ ", "│ • item", "│   code"), plain(result.lines()));
        assertEquals(result.lines().size(), result.kinds().size());
    }

    @Test
    void calloutsHaveLabelsAndUnknownMarkersRemainLiteral() {
        assertEquals(
                List.of("│ WARNING", "│ body"),
                plain(renderer.render("> [!WARNING]\n> body", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
        assertEquals(
                List.of("│ [!OTHER]", "│ body"),
                plain(renderer.render("> [!OTHER]\n> body", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void quotedCodeAndTableKeepRailsAndRowKinds() {
        final var result = renderer.renderWithKinds(
                "> [!TIP]\n> code follows\n>\n> ```\n> alpha\n> beta\n> ```\n>\n> | a | b |\n> | - | - |\n> | c | d |",
                40,
                Style.EMPTY,
                MarkdownStyles.DEFAULTS);
        assertTrue(plain(result.lines()).stream().allMatch(line -> line.startsWith("│ ")));
        assertTrue(plain(result.lines()).stream().anyMatch(line -> line.contains("alpha")));
        assertTrue(plain(result.lines()).stream().anyMatch(line -> line.contains("c")));
        assertEquals(result.lines().size(), result.kinds().size());
    }

    @Test
    void thematicBreak() {
        assertEquals(List.of("─────"), plain(renderer.render("---", 5, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void inlineStylesKeepText() {
        assertEquals(
                List.of("the bold italic gone and code here"),
                plain(renderer.render(
                        "the **bold** *italic* ~~gone~~ and `code` here", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void consecutiveSpacesInsideInlineFormattingAreKept() {
        // commonmark preserves 2+ spaces inside/around inline formatting, and the
        // word wrapper must too: a run of k spaces renders as k spaces (the wrapper
        // joins tokens with a single space, so each extra space needs its own empty
        // word token, even when the whole run lives inside one styled span)
        final var cases = Map.of(
                "**a  b**", "a  b",
                "*a  b*", "a  b",
                "~~a  b~~", "a  b",
                "`a  b`", "a  b",
                "**a**  **b**", "a  b",
                "**a**   **b**", "a   b",
                "a  **b**  c", "a  b  c");
        for (final var entry : cases.entrySet()) {
            assertEquals(
                    List.of(entry.getValue()),
                    plain(renderer.render(entry.getKey(), 80, Style.EMPTY, MarkdownStyles.DEFAULTS)),
                    () -> "double space must survive '" + entry.getKey() + "'");
        }
    }

    @Test
    void consecutiveSpacesKeepLengthAtTheWrapBoundary() {
        // the extra space is its own zero-width word: it must neither vanish nor push
        // the next word onto the next row when the line nearly fills the width
        assertEquals(
                List.of("aa b  tail"),
                plain(renderer.render("aa **b**  tail", 10, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void tableRendersBorderedGrid() {
        final var md = """
                | a | bb |
                |---|----|
                | 1 | 22 |
                """;
        assertEquals(
                List.of("┌───┬────┐", "│ a │ bb │", "├───┼────┤", "│ 1 │ 22 │", "└───┴────┘"),
                plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void tableCellInlineCodeIsKept() {
        // inline code (`Code` nodes have no Text child) must not be dropped from cells
        final var md = """
                | Situation | Type résolu |
                |---|---|
                | Driver donne un type standard (`DECIMAL`, `VARCHAR`, ...) | Mapping direct (BigNumber/Integer/String/...) |
                | Driver ne sait pas (`Types.NULL`, `OTHER`, exotique) | Fallback → String (`ValueMetaBase.java:5349`) |
                | Règle dialecte explicite (SQLite expression, Oracle RAW...) | String voulu |
                """;
        final var text = String.join("\n", plain(renderer.render(md, 120, Style.EMPTY, MarkdownStyles.DEFAULTS)));
        for (final var kept : List.of(
                "DECIMAL",
                "VARCHAR",
                "Types.NULL",
                "OTHER",
                "ValueMetaBase.java:5349",
                "SQLite expression",
                "Oracle RAW",
                "BigNumber/Integer/String",
                "String voulu")) {
            assertTrue(text.contains(kept), () -> "missing " + kept + " in:\n" + text);
        }
        assertFalse(text.contains("`"), text);
    }

    @Test
    void tableCellInlineStylesKeepText() {
        final var md = """
                | a | b |
                |---|---|
                | **bold** *em* [link](https://example.com) | `code` |
                """;
        final var text = String.join("\n", plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
        for (final var kept : List.of("bold", "em", "link", "code")) {
            assertTrue(text.contains(kept), () -> "missing " + kept + " in:\n" + text);
        }
        for (final var gone : List.of("**", "`", "[link]")) {
            assertFalse(text.contains(gone), () -> "unexpected " + gone + " in:\n" + text);
        }
    }

    @Test
    void tableCellInlineCodeKeepsItsStyle() {
        final var md = """
                | Situation | Type résolu |
                |---|---|
                | `DECIMAL` | Mapping `direct` |
                """;
        final var lines = renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        final Span codeSpan = lines.stream()
                .flatMap(l -> l.spans().stream())
                .filter(s -> s.content().equals("DECIMAL"))
                .findFirst()
                .orElseThrow();
        // the theme inline-code style (gray + dim) is preserved on the cell span
        assertEquals(MarkdownStyles.DEFAULTS.inlineCode(), codeSpan.style());
    }

    @Test
    void codeYieldsToTheRegionForegroundWhileLinkKeepsItsColor() {
        final var base = Style.EMPTY.fg(Color.rgb(0x11, 0x22, 0x33));
        final var lines = renderer.render("`code` and [docs](https://example.com)", 80, base, MarkdownStyles.DEFAULTS);
        final var spans = lines.stream().flatMap(l -> l.spans().stream()).toList();
        final var code = spans.stream()
                .filter(s -> s.content().equals("code"))
                .findFirst()
                .orElseThrow();
        // same composition rule as strong: the code accent fills in only on a plain base,
        // inside a colored region the region fg wins (only the dim/italic modifiers stay)
        assertEquals(base.fg(), code.style().fg(), "code fg must yield to a colored base");
        final var link = spans.stream()
                .filter(s -> s.content().equals("docs"))
                .findFirst()
                .orElseThrow();
        assertEquals(MarkdownStyles.DEFAULTS.link().fg(), link.style().fg(), "link fg must survive a colored base");
    }

    @Test
    void strongKeepsTheRegionColorOverAColoredBase() {
        final var base = Style.EMPTY.fg(Color.rgb(0x6b, 0x6b, 0x6b)).dim().italic();
        final var lines = renderer.render("**bold** plain", 80, base, MarkdownStyles.DEFAULTS);
        final var spans = lines.stream().flatMap(l -> l.spans().stream()).toList();
        final var bold = spans.stream()
                .filter(s -> s.content().equals("bold"))
                .findFirst()
                .orElseThrow();
        // inside a colored region bold keeps the region tone (bold but dimmed) instead of
        // overriding it with the strong accent color
        assertEquals(base.fg(), bold.style().fg(), "bold must keep the region color");
        assertTrue(bold.style().effectiveModifiers().contains(Modifier.BOLD), "bold must stay bold");
    }

    @Test
    void strongInAColoredBaseCellKeepsTheRegionColor() {
        final var base = Style.EMPTY.fg(Color.rgb(0x6b, 0x6b, 0x6b)).dim();
        final var lines = renderer.render("| a |\n|---|\n| **bold** |\n", 40, base, MarkdownStyles.DEFAULTS);
        final var bold = lines.stream()
                .flatMap(l -> l.spans().stream())
                .filter(s -> s.content().equals("bold"))
                .findFirst()
                .orElseThrow();
        assertEquals(base.fg(), bold.style().fg(), "cell bold must keep the region color");
        assertTrue(bold.style().effectiveModifiers().contains(Modifier.BOLD), "cell bold must stay bold");
    }

    @Test
    void tableWrapsNarrowGrid() {
        final var md = """
                | name | value       |
                |------|-------------|
                | long | alpha beta  |
                """;
        // the value column is narrowed so "alpha beta" wraps; every row stays within width
        final var lines = plain(renderer.render(md, 18, Style.EMPTY, MarkdownStyles.DEFAULTS));
        for (final var line : lines) {
            assertTrue(line.length() <= 18, line);
        }
        assertTrue(lines.stream().anyMatch(l -> l.contains("alpha")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("beta")), lines.toString());
    }

    @Test
    void markdownLinkShowsText() {
        assertEquals(
                List.of("docs site"),
                plain(renderer.render("[docs site](https://example.com)", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void wrapCodeIsCharacterExact() {
        // no word-aware rewrapping: every character is preserved, overlong rows hard-split
        final var longLine = Line.from("aaaaaaaaaa bbbbbbbbbb cccccccccc");
        assertEquals(
                List.of("aaaaaaaaaa", " bbbbbbbbb", "b cccccccc", "cc"),
                plain(MarkdownLines.wrapCode(List.of(longLine), 10)));
    }

    @Test
    void wrapCodePreservesIndentationAndRuns() {
        final var code = List.of(Line.from("    final var x = 1;    // note"));
        assertEquals(List.of("    final var x = 1;    // note"), plain(MarkdownLines.wrapCode(code, 80)));
    }

    @Test
    void wrapCodeKeepsBlankRows() {
        assertEquals(
                List.of("a", "", "b"),
                plain(MarkdownLines.wrapCode(List.of(Line.from("a"), Line.empty(), Line.from("b")), 80)));
    }

    @Test
    void baseStyleFgSurvivesHeadings() {
        final var dim = Style.EMPTY.fg(Color.rgb(0x6b, 0x6b, 0x6b));
        final var lines = renderer.render("# Title\n\nbody", 80, dim, MarkdownStyles.DEFAULTS);
        final var headingSpan = lines.stream()
                .flatMap(l -> l.spans().stream())
                .filter(s -> s.content().contains("Title"))
                .findFirst()
                .orElseThrow();
        final var rgb = headingSpan.style().fg().orElseThrow().toRgb();
        assertEquals(0x6b, rgb.r());
        assertEquals(0x6b, rgb.g());
        assertEquals(0x6b, rgb.b());
    }

    @Test
    void baseStyleFgSurvivesTables() {
        final var dim = Style.EMPTY.fg(Color.rgb(0x6b, 0x6b, 0x6b));
        final var lines = renderer.render("| a | b |\n|---|---|\n| 1 | 2 |\n", 80, dim, MarkdownStyles.DEFAULTS);
        final var spans = lines.stream().flatMap(l -> l.spans().stream()).toList();
        final var cellA = spans.stream()
                .filter(s -> s.content().strip().equals("a"))
                .findFirst()
                .orElseThrow();
        final var rgb = cellA.style().fg().orElseThrow().toRgb();
        assertEquals(0x6b, rgb.r());
        assertEquals(0x6b, rgb.g());
        assertEquals(0x6b, rgb.b());
    }

    @Test
    void indentedXmlBlockKeepsIndentationVerbatim() {
        final var xml = String.join(
                "\n",
                "Before the block.",
                "",
                "<plan>",
                "    <step>One</step>",
                "    <step>Two</step>",
                "</plan>",
                "",
                "After the block.");
        assertEquals(
                List.of(
                        "Before the block.",
                        "",
                        "<plan>",
                        "    <step>One</step>",
                        "    <step>Two</step>",
                        "</plan>",
                        "",
                        "After the block."),
                plain(renderer.render(xml, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void inlineXmlMentionIsNotTreatedAsAcodeBlock() {
        final var text = "Use the <plan> to organise and the <files> to list, shown inline.";
        assertEquals(
                List.of("Use the <plan> to organise and the <files> to list, shown inline."),
                plain(renderer.render(text, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void loneLessThanLineDoesNotCrash() {
        // a line that trims to "<" alone must not throw (charAt(1) guard)
        assertEquals(List.of("<"), plain(renderer.render("<", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void lessThanInterleavedWithProse() {
        // splitSegments runs isXmlLine per line; a stray "<" among prose must stay prose
        assertEquals(
                List.of("before", "<", "after"),
                plain(renderer.render("before\n<\nafter", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void indentedCodeBlockKeepsIndentationVerbatim() {
        // commonmark's IndentedCodeBlock.getLiteral() strips the common 4-space indent;
        // we must keep the source bytes exactly as written
        final var md = String.join(
                "\n", "Before the block.", "", "    if (a) {", "        b();", "    }", "", "After the block.");
        assertEquals(
                List.of("Before the block.", "", "    if (a) {", "        b();", "    }", "", "After the block."),
                plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void leadingIndentedLineAtStartIsPreserved() {
        final var md = String.join("\n", "    indented", "", "text");
        assertEquals(
                List.of("    indented", "", "text"),
                plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void interiorBlankLineInsideIndentedCodeIsKept() {
        final var md = String.join("\n", "", "    first", "", "    second", "");
        assertEquals(
                List.of("", "    first", "", "    second", ""),
                plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void tinyWidthDoesNotHangOrCrash() {
        // a non-empty code block rendered at width <= 1 must terminate (clamped to 1),
        // wrapping per character instead of hanging or throwing
        final var md = String.join("\n", "```", "abc", "```");
        assertEquals(List.of("a", "b", "c"), plain(renderer.render(md, 1, Style.EMPTY, MarkdownStyles.DEFAULTS)));
        // wrapCode entry point is clamped too
        assertEquals(List.of("a", "b", "c"), plain(MarkdownLines.wrapCode(List.of(Line.from("abc")), 0)));
        assertEquals(List.of("a", "b", "c"), plain(MarkdownLines.wrapCode(List.of(Line.from("abc")), 1)));
    }

    @Test
    void indentedLineUnderListItemIsLeftToTheList() {
        // a nested code block inside a list item must not be stolen as a top-level block
        final var md = String.join("\n", "- item", "", "      nested code", "", "tail");
        final var rendered = String.join("\n", plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
        assertTrue(rendered.contains("nested code"), rendered);
        assertTrue(rendered.contains("item"), rendered);
        assertTrue(rendered.contains("tail"), rendered);
    }

    @Test
    void indentedContinuationAfterParagraphIsNotExtracted() {
        // no blank line before: commonmark treats this as a lazy paragraph continuation
        final var md = String.join("\n", "para", "    indent");
        assertEquals(List.of("para", "indent"), plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void markdownLinkAttachesOsc8Hyperlink() {
        final var dest = "https://example.com/a/b?x=1";
        final var lines = renderer.render("[docs site](" + dest + ")", 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        final var span = lines.stream()
                .flatMap(l -> l.spans().stream())
                .filter(s -> s.style().hyperlink().isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no span carries an OSC-8 hyperlink in " + lines));
        assertEquals(dest, span.style().hyperlink().orElseThrow().url());
    }

    @Test
    void autolinkAttachesOsc8Hyperlink() {
        // commonmark 0.28 renders <https://...> as a Link node; the full URI must be the OSC-8 target
        final var url =
                "https://auth.openai.com/oauth/authorize?response_type=code&client_id=app_EMoamEEZ73f0CkXaXp7hrann";
        final var lines = renderer.render("<" + url + ">", 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        final var text = String.join("", plain(lines));
        assertTrue(text.contains("oauth/authorize"), text);
        final var span = lines.stream()
                .flatMap(l -> l.spans().stream())
                .filter(s -> s.style().hyperlink().isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no OSC-8 hyperlink span in " + lines));
        assertEquals(url, span.style().hyperlink().orElseThrow().url());
    }

    @Test
    void overwideUrlHardSplitsAndKeepsEveryCharacterCopyable() {
        // a long URL token wider than the pane must hard-split across rows (not wrap silently at the
        // terminal edge) so selection/copy captures the whole URL intact
        final var url = "https://auth.openai.com/oauth/authorize?response_type=code&client_id=app_X";
        final var rendered = String.join("", plain(renderer.render(url, 30, Style.EMPTY, MarkdownStyles.DEFAULTS)));
        assertEquals(url, rendered);
        for (final var line : plain(renderer.render(url, 30, Style.EMPTY, MarkdownStyles.DEFAULTS))) {
            assertTrue(line.length() <= 30, line);
        }
    }

    @Test
    void escapedHeaderStarsSurviveRendering() {
        // a tool header emitted by ConversationTranscript after flatten+escapeMarkdown: the
        // asterisk/underscore are backslash-escaped, the backtick became an apostrophe; rendering
        // it must produce the literal stars/underscores, never consume them as emphasis
        final var lines = renderer.render(
                "*streaming `bash`: cat <<'EOF' plain \\* one two \\_ three use 'echo' EOF* (#1, 0 ms)",
                80,
                Style.EMPTY,
                MarkdownStyles.DEFAULTS);
        final var text = String.join("\n", plain(lines));
        assertTrue(text.contains("plain * one"), text);
        assertTrue(text.contains("two _ three"), text);
        assertTrue(text.contains("use 'echo'"), text);
    }

    @Test
    void codeLikeStarsSurviveRendering() {
        // regression pin: a multi-line SQL heredoc echoed by the model inside a paragraph used to
        // pair its bare stars (part_*.csv.gz / COUNT(*)) into an emphasis that hid both; the
        // code-like pre-pass escapes them so the rendered text keeps them literal
        final var lines = renderer.render(
                "cat > /tmp/validate_ext_modify.sql <<'SQL'\n"
                        + "ALTER TABLE T MODIFY PARTITION P_2026 LOCATION ('part_*.csv.gz');\n"
                        + "SELECT COUNT(*) AS CNT FROM T;",
                80,
                Style.EMPTY,
                MarkdownStyles.DEFAULTS);
        final var text = String.join("\n", plain(lines));
        assertTrue(text.contains("'part_*.csv.gz'"), text);
        assertTrue(text.contains("COUNT(*)"), text);
        assertFalse(text.contains("part_.csv.gz"), text);
        assertFalse(text.contains("COUNT()"), text);
    }

    @Test
    void englishProseWithSqlKeywordsIsNotEscaped() {
        // WITH/FROM/cat in lowercase appear in plain sentences; only uppercase SQL and shell
        // redirections claim a line. The prose keeps its emphasis delimiters (so CommonMark
        // renders them as emphasis, i.e. the asterisks are consumed -> no literal backslashes).
        final var lines = renderer.render(
                "the data from the table is *ready*\nwith him as advisor, I _decided_ to go\nthe cat sat on the *mat*",
                80,
                Style.EMPTY,
                MarkdownStyles.DEFAULTS);
        final var text = String.join("\n", plain(lines));
        // no escape was injected: no backslash character, and the words rendered (emphasis consumed)
        assertFalse(text.contains("\\*"), text);
        assertFalse(text.contains("\\_"), text);
        assertTrue(text.contains("ready"), text);
        assertTrue(text.contains("decided"), text);
        assertTrue(text.contains("mat"), text);
    }

    @Test
    void fencedCodeBlockStarsStayLiteral() {
        final var lines = renderer.render(
                "```sql\nSELECT * FROM t WHERE c = 'part_*.csv.gz';\n```", 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        final var text = String.join("\n", plain(lines));
        assertTrue(text.contains("SELECT * FROM t"), text);
        assertTrue(text.contains("'part_*.csv.gz'"), text);
    }

    @Test
    void inlineCodeSpanOnCodeLikeLineIsNotDoubleEscaped() {
        final var lines = renderer.render(
                "ALTER TABLE t; use `SELECT *` now\nSELECT COUNT(*) FROM t;", 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        final var text = String.join("\n", plain(lines));
        assertTrue(text.contains("SELECT *"), text); // inside the code span, unescaped
        assertTrue(text.contains("COUNT(*)"), text); // bare star escaped then rendered literally
    }

    @Test
    void existingBackslashIsNotDoubleEscaped() {
        final var lines = renderer.render("grep '\\*' SELECT * FROM t;", 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        final var text = String.join("\n", plain(lines));
        assertTrue(text.contains("grep '*'"), text);
        assertTrue(text.contains("SELECT * FROM"), text);
    }

    private static List<String> plain(final List<Line> lines) {
        return lines.stream().map(Line::rawContent).toList();
    }

    @Test
    void renderWithKindsMarksSoftWrappedContinuations() {
        final var result = renderer.renderWithKinds(
                "the quick brown fox jumps over the lazy dog", 12, Style.EMPTY, MarkdownStyles.DEFAULTS);
        assertEquals(4, result.lines().size());
        assertEquals(SelectionState.RowKind.HARD, result.kinds().get(0));
        assertEquals(SelectionState.RowKind.SOFT_CONT, result.kinds().get(1));
        assertEquals(SelectionState.RowKind.SOFT_CONT, result.kinds().get(2));
        assertEquals(SelectionState.RowKind.SOFT_CONT, result.kinds().get(3));
    }

    @Test
    void renderWithKindsKeepsHoldOnANarrowSingleWord() {
        // a single word narrower than the width must stay HARD
        final var result = renderer.renderWithKinds("hello", 12, Style.EMPTY, MarkdownStyles.DEFAULTS);
        assertEquals(1, result.lines().size());
        assertEquals(SelectionState.RowKind.HARD, result.kinds().get(0));
    }

    @Test
    void wrapCodeWithKindsMarksCharSplits() {
        final var lines = List.of(Line.from(Span.styled("abcdefghij", Style.EMPTY)));
        final var result = MarkdownLines.wrapCodeWithKinds(lines, 3);
        assertEquals(4, result.lines().size());
        assertEquals(SelectionState.RowKind.HARD, result.kinds().get(0));
        assertEquals(SelectionState.RowKind.CHAR_SPLIT, result.kinds().get(1));
        assertEquals(SelectionState.RowKind.CHAR_SPLIT, result.kinds().get(2));
        assertEquals(SelectionState.RowKind.CHAR_SPLIT, result.kinds().get(3));
    }

    @Test
    void wrapCodeWithKindsSingleLineIsHard() {
        final var lines = List.of(Line.from(Span.styled("abc", Style.EMPTY)));
        final var result = MarkdownLines.wrapCodeWithKinds(lines, 10);
        assertEquals(1, result.lines().size());
        assertEquals(SelectionState.RowKind.HARD, result.kinds().get(0));
    }

    @Test
    void wrapCodeHardSplitsALongToken() {
        // character-exact: a single token wider than the width is hard-split, no character dropped
        final var lines = List.of(Line.from(Span.raw("abcdefghijklmnop")));
        final var wrapped = MarkdownLines.wrapCode(lines, 5);
        assertEquals(List.of("abcde", "fghij", "klmno", "p"), plain(wrapped));
    }

    @Test
    void wrapCodePreservesIndentation() {
        // leading indentation and internal whitespace runs survive the wrap unchanged
        final var lines = List.of(Line.from(Span.raw("    return value;")));
        final var wrapped = MarkdownLines.wrapCode(lines, 8);
        assertEquals(List.of("    retu", "rn value", ";"), plain(wrapped));
    }
}
